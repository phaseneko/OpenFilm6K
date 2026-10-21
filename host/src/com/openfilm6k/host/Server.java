package com.openfilm6k.host;

import java.io.*;
import java.net.*;
import java.util.*;

/** HTTP :8800 — /ping /films /process?img=&film=&out= */
public class Server {
    private static final Server I = new Server();
    private static final java.io.File galDir = new java.io.File("/sdcard/DCIM/OpenFilm6K");
    /** Scratch area for the upload + in-flight render. The leading dot keeps MediaStore from
     *  indexing it: it used to be OpenFilm6K/camera/, whose JPEGs became a second "camera"
     *  album in the gallery and duplicated every shot. The graded result now only ever
     *  exists in DCIM/OpenFilm6K. The original stays here because collage() re-renders the
     *  other three films from it and the EXIF/notification read it. */
    private static final java.io.File workDir = new java.io.File("/sdcard/OpenFilm6K/.work");

    /** progress reporter for the film-library extraction */
    public interface InstallReporter { void onProgress(int done, int total); void onDone(int copied, String error); }

    /** extract the bundled films from APK assets to the SD card.
     *  OVERWRITE mode: every file in the shipped manifest (official pipelines, LUTs, scene
     *  previews) is written unconditionally, so corrupted or stale official data is repaired
     *  on every start. The manifest only ever lists files we bundle — anything the user made
     *  (custom films, imported LUTs, custom/scenes) is not in it and is never touched, and
     *  nothing is ever deleted. Safe to call repeatedly; synchronized so the service and the
     *  activity never race. */
    public static synchronized void installAssets(InstallReporter r) {
        try {
            java.io.File base = new java.io.File("/sdcard/OpenFilm6K");
            final int ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
            // read the file manifest — AssetManager.list() returns NOTHING for plain zip entries
            // added by `aapt add` (no directory records), so we ship films/index.txt instead
            java.io.BufferedReader rd = new java.io.BufferedReader(new java.io.InputStreamReader(ctx.getAssets().open("films/index.txt")));
            java.util.ArrayList<String> jobs = new java.util.ArrayList<String>();
            String line;
            while ((line = rd.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) continue;
                String rel = line.startsWith("films/") ? line.substring(6) : line;   // pipelines/x / luts/y / root/preview_z.jpg (scene thumbs)
                jobs.add(line);                                                       // overwrite: official data is always restated from the APK
            }
            rd.close();
            int copied = 0;
            for (int i = 0; i < jobs.size(); i++) {
                String src = jobs.get(i);
                String rel = src.substring(6);
                if (rel.startsWith("root/")) rel = rel.substring(5);   // scene previews land at the data root
                java.io.File out = new java.io.File(base, rel);
                out.getParentFile().mkdirs();
                java.io.InputStream in = ctx.getAssets().open(src);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                byte[] b = new byte[65536]; int k;
                while ((k = in.read(b)) > 0) fo.write(b, 0, k);
                fo.close(); in.close();
                copied++;
                if (r != null) r.onProgress(copied, jobs.size());
            }
            ctx.getSharedPreferences("of6k", 0).edit().putInt("installedVer", ver).commit();   // done: this version's data is in place
            if (r != null) r.onDone(copied, null);
            else if (copied > 0) MainActivity.say("installed " + copied + " bundled film files");
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String err = t + "\n" + sw;
            if (r != null) r.onDone(-1, err);
            MainActivity.say("assets: " + t);
        }
    }

    /** progress reporter for the film-library extraction */
    /** "1/125s F3.5 ISO400" from the ORIGINAL capture's EXIF */
    static String readExposure(java.io.File f) {
        try {
            android.media.ExifInterface e = new android.media.ExifInterface(f.getAbsolutePath());
            String et = e.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME);
            String ap = e.getAttribute(android.media.ExifInterface.TAG_F_NUMBER);
            String iso = e.getAttribute(android.media.ExifInterface.TAG_ISO);
            StringBuilder sb = new StringBuilder();
            if (et != null) {
                try {
                    double d = Double.parseDouble(et);
                    if (d > 0) sb.append(d < 1 ? ("1/" + Math.round(1 / d) + "s") : (Math.round(d) + "s"));
                } catch (NumberFormatException ig) {}
            }
            if (ap != null && sb.length() > 0) sb.append(' ');
            if (ap != null) sb.append("F").append(ap);
            if (iso != null && sb.length() > 0) sb.append(' ');
            if (iso != null) sb.append("ISO").append(iso);
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Throwable t) { return null; }
    }

    /** capture time from the ORIGINAL's EXIF (fallback: file mtime), "MM-dd HH:mm" */
    static String readTime(java.io.File f) {
        try {
            android.media.ExifInterface e = new android.media.ExifInterface(f.getAbsolutePath());
            String dt = e.getAttribute(android.media.ExifInterface.TAG_DATETIME);   // "yyyy:MM:dd HH:mm:ss"
            if (dt != null && dt.length() >= 16) return dt.substring(5, 7) + "-" + dt.substring(8, 10) + " " + dt.substring(11, 16);
        } catch (Throwable ig) {}
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(f.lastModified()));
    }

    /** ~256px thumbnail of the graded result */
    static android.graphics.Bitmap decodeThumb(java.io.File f) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sc = 1;
            while (o.outWidth / (sc * 2) >= 256 && o.outHeight / (sc * 2) >= 256) sc *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sc;
            return android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) { return null; }
    }

    static Server get() { return I; }
    private volatile boolean up = false;
    private static android.content.Context ctx;   // app context, for the stamp typeface

    /** One-time cleanup for builds that used to keep the upload + a graded copy under
     *  OpenFilm6K/camera/. Those JPEGs were indexed by MediaStore as a second "camera" album
     *  holding a duplicate of every shot. Both the files and their media-store rows go, so the
     *  stale album disappears instead of lingering as an empty folder.
     *  Only touches OpenFilm6K/camera/ — graded files in DCIM are the ones we keep. */
    private void dropLegacyCameraDir() {
        try {
            java.io.File old = new java.io.File("/sdcard/OpenFilm6K/camera");
            java.io.File[] fs = old.listFiles();
            if (fs == null || fs.length == 0) { if (old.isDirectory()) old.delete(); return; }
            java.util.ArrayList<String> paths = new java.util.ArrayList<String>();
            for (java.io.File f : fs) paths.add(f.getAbsolutePath());
            for (java.io.File f : fs) f.delete();
            old.delete();
            // scan AFTER deleting: the scanner sees the paths are gone and purges their
            // media-store rows, which is what actually makes the stale album disappear
            if (ctx != null) {
                try {
                    android.media.MediaScannerConnection.scanFile(ctx,
                            paths.toArray(new String[0]), new String[]{"image/jpeg", "image/png"}, null);
                } catch (Throwable ig) {}
            }
            MainActivity.say("cleanup: removed legacy camera/ album (" + fs.length + " files)");
        } catch (Throwable t) { MainActivity.say("cleanup skipped: " + t); }
    }

    /** last time the camera hit /ping or /films (0 = never) — drives the notification's connection status */
    static volatile long lastCamSeen = 0;
    static boolean camConnected() {
        return lastCamSeen > 0 && System.currentTimeMillis() - lastCamSeen < 600000;   // camera idles quietly between pings
    }

    /** true when the running APK version has not installed its data yet (or the marker predates it) */
    public static boolean versionNeedsInstall(android.content.Context c) {
        try {
            int ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
            return c.getSharedPreferences("of6k", 0).getInt("installedVer", -1) != ver;
        } catch (Throwable t) { return true; }
    }

    void start(android.content.Context c) {
        ctx = c.getApplicationContext();
        if (versionNeedsInstall(ctx)) installAssets(null);   // overwrite once per APK version, silent (service boots)
        start();
    }

    /** bootstrap for the visible install dialog: set ctx + start http, but do NOT run a silent
     *  copy first — the reporter-driven installAssets() that follows is the one the bar shows. */
    void startForInstall(android.content.Context c) {
        ctx = c.getApplicationContext();
        start();
    }

    void start() {
        dropLegacyCameraDir();
        if (up) return;
        up = true;
        Thread t = new Thread(new Runnable() { public void run() { loop(); } }, "http");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        try {
            ServerSocket ss = new ServerSocket(8800);
            MainActivity.say("http on :8800");
            while (true) {
                Socket s = ss.accept();
                try { handle(s); } catch (Exception e) { MainActivity.say("http ex " + e); }
                s.close();
            }
        } catch (Exception e) {
            MainActivity.say("http dead: " + e);
        }
    }

    private void handle(Socket s) {
        try { handleInner(s); } catch (Throwable t) { try { respond(s, "EX " + t); } catch (Throwable ig) {} }
    }

    private void handleInner(Socket s) throws Exception {
        // byte-level request parsing: /ingest carries a raw JPEG body (Reader would corrupt it)
        java.io.InputStream raw = new BufferedInputStream(s.getInputStream());
        StringBuilder head = new StringBuilder();
        int prev = -1;
        while (true) {
            int c = raw.read();
            if (c < 0) break;
            if (prev == '\r' && c == '\n') {
                if (head.length() >= 1 && head.charAt(head.length() - 1) == '\n') break;   // blank line: end of headers
                head.append('\n');
            } else if (c != '\r') head.append((char) c);
            prev = c;
        }
        String[] hl = head.toString().split("\n");
        String req = hl.length > 0 ? hl[0] : "";
        long len = 0;
        for (int i = 1; i < hl.length; i++)
            if (hl[i].toLowerCase().startsWith("content-length:")) len = Long.parseLong(hl[i].substring(15).trim());
        Map<String,String> q = new HashMap<>();
        String path = req.split(" ").length > 1 ? req.split(" ")[1] : "/";
        int qi = path.indexOf('?');
        if (qi >= 0) for (String kv : path.substring(qi + 1).split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) q.put(URLDecoder.decode(kv.substring(0, e)), URLDecoder.decode(kv.substring(e + 1)));
        }
        path = path.substring(0, qi < 0 ? path.length() : qi);

        String resp;
        try {
        if (path.startsWith("/ingest2")) {   // MUST precede "/ingest": "/ingest2" startsWith "/ingest"
            // H (half-frame) / 2 (double-exposure): two shots (each may use a different film)
            // uploaded atomically. body = JPEG0 then JPEG1.
            resp = ingestPair(raw, q);
        }
        else if (path.startsWith("/ingest")) {
            // camera upload: POST /ingest?film=&name=&orig=&len=[&stamp=..&stamp2=..&b=1&x=1&w=1], body = JPEG bytes.
            // Save the original to the hidden scratch dir, render it into the gallery album.
            String film = q.get("film"), name = q.get("name"), orig = q.get("orig");
            if (film == null || name == null || len <= 0) { resp = "ERR params"; }
            else {
                java.io.File dir = workDir;
                dir.mkdirs();
                java.io.File src = new java.io.File(dir, name);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(src);
                long left = len;
                byte[] buf = new byte[16384];
                while (left > 0) {
                    int r = raw.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r < 0) break;
                    fo.write(buf, 0, r);
                    left -= r;
                }
                fo.close();
                if (left > 0) { src.delete(); resp = "ERR short read"; }
                else {
                    StringBuilder extras = new StringBuilder();
                    for (String k : new String[]{"stamp", "stamp2", "b", "b2", "x", "w", "wb1", "wb2", "wb3"})
                        if (q.get(k) != null) extras.append(' ').append(k).append('=').append(q.get(k));
                    if (extras.length() > 0) MainActivity.say("ingest stamps:" + extras);
                    // grab the camera's EXIF (APP1) from the untouched upload BEFORE anything
                    // re-encodes it, then carry it onto the final graded JPEG.
                    byte[] exifApp1 = Exif.app1Of(src);
                    if (exifApp1 == null) Engine.dbg("ingest: source carries no APP1/EXIF: " + name);
                    // render straight into the gallery album: one output file, no second copy
                    galDir.mkdirs();
                    java.io.File out = new java.io.File(galDir, "graded_" + name);
                    String post = q.get("post");
                    String leakdust = postSubset(post, "leak", "dust", "veiling", "softfocus", "hdf");   // exposure overlays: composited BEFORE the pipeline
                    String scratch = postSubset(post, "scratch");         // surface overlay: output-level, before any photo frame
                    String photoOnly = postSubset(leakdust, "dust", "veiling", "softfocus", "hdf");   // lens imaging -> photo only
                    String leakOnly = postSubset(leakdust, "leak");               // film-base exposure -> whole canvas
                    boolean isX = q.get("x") != null;
                    boolean photoFrame = (q.get("b") != null) || (q.get("wb1") != null);
                    boolean filmBorder = isFilmBorder(q);
                    // dust/veiling are lens imaging: apply them to the clean photo BEFORE the date print, otherwise the
                    // bright printed digits would themselves be picked up as highlights and grow dust around them
                    java.io.File dusted = src;
                    if (!photoOnly.isEmpty()) {
                        java.io.File dv = new java.io.File(workDir, "dv_" + name);
                        String rd = Engine.get().applyPost(src.getAbsolutePath(), dv.getAbsolutePath(), photoOnly);
                        if (rd != null && !rd.equals(src.getAbsolutePath()) && new java.io.File(rd).exists()) dusted = new java.io.File(rd);
                    }
                    // render order: watermark goes onto the ORIGINAL right after capture, BEFORE all our grading passes
                    java.io.File pipeIn = textStampOriginal(dusted, src, q);
                    if (dusted != src && dusted != pipeIn) dusted.delete();
                    String r = null;
                    if (isX) {   // X collage: each of the 4 films gets its own overlay; the card frame is last
                        r = collagePost(pipeIn, film, leakOnly, scratch, out);
                        if (pipeIn != src) pipeIn.delete();
                    } else {
                    if (filmBorder) {   // place the (already dusted) photo on the final-res black canvas
                        pipeIn = buildBorderCanvas(pipeIn, workDir, name, q, null);
                        if (!leakOnly.isEmpty()) {
                            java.io.File ov = new java.io.File(workDir, "ov_" + name);
                            String rr = Engine.get().applyPost(pipeIn.getAbsolutePath(), ov.getAbsolutePath(), leakOnly);
                            if (rr != null && !rr.equals(pipeIn.getAbsolutePath()) && new java.io.File(rr).exists()) pipeIn = new java.io.File(rr);
                        }
                    } else if (!leakOnly.isEmpty()) {   // leak joins the film exposure -> graded by the pipeline
                        java.io.File ov = new java.io.File(workDir, "ov_" + name);
                        String rr = Engine.get().applyPost(pipeIn.getAbsolutePath(), ov.getAbsolutePath(), leakOnly);
                        if (rr != null && !rr.equals(pipeIn.getAbsolutePath()) && new java.io.File(rr).exists()) pipeIn = new java.io.File(rr);
                    }
                    r = Engine.get().process(pipeIn.getAbsolutePath(), film, out.getAbsolutePath());
                    if (pipeIn != src) pipeIn.delete();
                    if (r != null && !r.startsWith("ERR") && new java.io.File(r).exists()) {
                        if (filmBorder) { r = screenBorderLayer(out, src, film, q); if (!scratch.isEmpty()) r = scratchOverBorder(new java.io.File(r), film, scratch); }   // border screen, then the film scratch (kept out of the sprocket holes)
                        else if (photoFrame) {                                       // B1/WB1: scratch first, then the frame on top
                            if (!scratch.isEmpty()) r = scratchColorized(out, film, scratch);
                            r = stamped(out, src, film, q);
                        } else {                                                     // borderless / W: output-level scratch after everything
                            r = stamped(out, src, film, q);
                            if (!scratch.isEmpty() && r != null && !r.startsWith("ERR"))
                                r = scratchColorized(new java.io.File(r), film, scratch);
                        }
                    }
                    }
                    // EXIF goes on last, so it survives the engine's encode AND the white-card/collage frame
                    if (exifApp1 != null && r != null && !r.startsWith("ERR") && new java.io.File(r).exists())
                        Exif.carryBytes(out, exifApp1);
                    resp = r != null && r.startsWith("ERR") == false && new java.io.File(r).exists()
                            ? "OK " + out.getName() : "ERR render " + r;

                    // update the standing notification from the scan callback — the URI the app hands us
                    // flow hands us there is registered AND grantable, unlike a racing sync query
                    try {
                        final String filmF = film;
                        final String expoF = readExposure(src);
                        final String timeF = readTime(src);
                        final android.graphics.Bitmap thumbF = decodeThumb(new java.io.File(r));
                        final String gpath = out.getAbsolutePath();
                        android.media.MediaScannerConnection.scanFile(ctx,
                            new String[]{gpath}, new String[]{"image/jpeg"},
                            new android.media.MediaScannerConnection.OnScanCompletedListener() {
                                public void onScanCompleted(String p, android.net.Uri u) {
                                    try { HostService.photoInfo(filmF, expoF, timeF, thumbF, u); }
                                    catch (Throwable t2) { MainActivity.say("notif: " + t2); }
                                }
                            });
                    } catch (Throwable t3) { MainActivity.say("notif prep: " + t3); }
                    // the original has served its purpose (EXIF, collage, notification): drop it now
                    // that the notification has already read it, so the scratch dir keeps no JPEGs
                    if (src.exists()) src.delete();
                }
            }
        }
        else if (path.startsWith("/ping")) { lastCamSeen = System.currentTimeMillis(); resp = "pong openfilm6k b4"; }
        else if (path.startsWith("/films")) {
            lastCamSeen = System.currentTimeMillis();
            String sm = q.get("stampmode");   // the camera reports its current stamp mode with every pull
            if (sm != null) try { camStamp = Integer.parseInt(sm.trim()); } catch (Throwable ig) {}
            java.util.List<String> user = new java.util.ArrayList<String>(), off = new java.util.ArrayList<String>();
            for (String k : Films.listUserFirst()) {
                if ("user".equals(Films.s(k, "origin", ""))) user.add(k); else off.add(k);
            }
            StringBuilder fb = new StringBuilder();
            for (String k : user) fb.append(k).append('\n');
            if (!user.isEmpty() && !off.isEmpty()) fb.append("----\n");   // separator row: the camera renders it un-selectable
            for (String k : off) fb.append(k).append('\n');
            resp = fb.toString();
        }
        else if (path.startsWith("/dbglist")) {
            String qd = q.get("dir") != null ? q.get("dir") : "/sdcard/OpenFilm6K";
            StringBuilder sb = new StringBuilder("dir=" + qd + "\n");
            try { java.io.File[] fs = new java.io.File(qd).listFiles();
                sb.append("listFiles=" + (fs == null ? "NULL" : String.valueOf(fs.length)) + "\n");
                if (fs != null) for (java.io.File f : fs) sb.append(f.getName()).append(f.isDirectory() ? "/ " : " ").append(f.canRead() ? "r" : "-").append("\n");
            } catch (Throwable e) { sb.append("EX " + e); }
            resp = sb.toString();
        }
        else if (path.startsWith("/setgrade")) {
            // /setgrade?film=X&exposure=..&contrast=..&saturation=..&temp=..&tint=..  (all optional)
            String film = q.get("film");
            if (film == null) resp = "no film";
            else {
                java.util.Map<String, Float> g = new java.util.LinkedHashMap<String, Float>();
                String[][] keys = {{"exposure", q.get("exposure")}, {"contrast", q.get("contrast")},
                        {"saturation", q.get("saturation")}, {"temp", q.get("temp")}, {"tint", q.get("tint")},
                        {"low", q.get("low")}, {"mid", q.get("mid")}, {"high", q.get("high")}};
                for (String[] kv : keys) if (kv[1] != null) g.put("grade." + kv[0], Float.parseFloat(kv[1]));
                Films.saveGrades(film, g);
                resp = "OK grades=" + g;
            }
        }
        else if (path.startsWith("/process")) {
            String sc = q.get("score");
            if (sc != null) try { Engine.scoreOverride = Float.valueOf(sc.trim()); } catch (Throwable ig) {}
            String img = q.get("img"), film = q.get("film"), out = q.get("out");
            String r = Engine.get().process(img, film, out);
            String post = q.get("post");
            if (post != null && r != null && !r.startsWith("ERR") && new java.io.File(r).exists()) {
                String rp = Engine.get().applyPost(r, out + ".post.jpg", post);
                if (rp != null && new java.io.File(rp).exists()) r = rp;
            }
            resp = r != null ? "OK " + r : "FAIL";
        } else resp = "?";
        } catch (Throwable t) { resp = "EX " + t; }
        respond(s, resp);
    }

    // ---- camera watermarking: text stamps / white-card frame / 4-film collage ----

    /** stamp mode the camera last reported (0 none, 1 date, 2 expo, 3 date+expo, 4 white-card, 5 collage, 6 film, 7 film+expo) */
    static volatile int camStamp = 0;

    /** editor preview watermark mode, from the settings dialog (0 none, 1 D, 2 E, 3 DE, 6 F, 7 FE) — nothing to do with the camera */
    static int pvStamp(android.content.Context c) {
        try { return c.getSharedPreferences("of6k", 0).getInt("pvstamp", 1); } catch (Throwable t) { return 1; }
    }

    /** editor preview: burn the selected watermark onto a cached COPY of the source BEFORE grading (same order as real capture) */
    private static String pvKey = null;
    private static java.io.File pvFile = null;
    static java.io.File pvStampSource(String src, String film) {
        int m = pvStamp(ctx);
        if (m <= 0) return new java.io.File(src);
        java.io.File f = new java.io.File(src);
        String key = src + "|" + f.lastModified() + "|" + m;
        if (key.equals(pvKey) && pvFile != null && pvFile.exists()) return pvFile;
        java.util.Map<String,String> q = new java.util.HashMap<String,String>();
        String expo = Engine.exposureText(src);
        if (m == 1 || m == 3) q.put("stamp", "@DATE");
        if (m == 2 && !expo.isEmpty()) q.put("stamp", expo);
        if (m == 3 && !expo.isEmpty()) q.put("stamp2", expo);
        if (m == 6) q.put("stamp", film);
        if (m == 7) { q.put("stamp", film); if (!expo.isEmpty()) q.put("stamp2", expo); }
        if (q.isEmpty()) return f;
        java.io.File tmp = textStampOriginal(f, q);
        if (tmp != f) { pvKey = key; pvFile = tmp; }
        return tmp;
    }

    /** editor preview: overlay the user-selected watermark on a COPY of the graded bitmap (editor-only, camera-independent) */
    static android.graphics.Bitmap camStampPreview(android.graphics.Bitmap adj, String film, String src) {
        if (adj == null) return adj;
        int m = pvStamp(ctx);
        if (m <= 0) return adj;
        String s1 = null, s2 = null;
        String expo = Engine.exposureText(src);
        try {
            if (m == 1 || m == 3) s1 = exifDate(new java.io.File(src));
            if (m == 2 && !expo.isEmpty()) s1 = expo;
            if (m == 3 && !expo.isEmpty()) s2 = expo;
            if (m == 6) s1 = film;
            if (m == 7) { s1 = film; if (!expo.isEmpty()) s2 = expo; }
        } catch (Throwable t) { return adj; }
        if (s1 == null && s2 == null) return adj;
        android.graphics.Bitmap out = adj.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        float ts = Math.max(20f, out.getWidth() / 34f);
        android.graphics.Typeface tf = null;
        try { tf = Ux.seg14(ctx); } catch (Throwable ig) {}
        android.graphics.Paint glow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        glow.setColor(0xFFFF9500);
        if (tf != null) glow.setTypeface(tf);
        glow.setTextSize(ts);
        glow.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
        glow.setStrokeWidth(ts * 0.06f);
        glow.setShadowLayer(ts * 0.45f, 0, 0, 0xFFFF9500);
        android.graphics.Paint edge = new android.graphics.Paint(glow);
        edge.clearShadowLayer();
        edge.setStyle(android.graphics.Paint.Style.STROKE);
        edge.setStrokeWidth(ts * 0.10f);
        edge.setColor(0x66000000);
        float mg = ts * 0.9f;
        float ts2 = Math.max(17f, out.getWidth() * 2 / 85f);   // 2/3 * 6/5 of the original
        if (s1 != null) drawSegTokens(cv, s1, mg, out.getHeight() - mg, false, ts2);
        if (s2 != null) drawSegTokens(cv, s2, out.getWidth() - mg, out.getHeight() - mg, true, ts2);
        return out;
    }

    /** EXIF DateTimeOriginal of the original upload -> "yyyy.MM.dd"; falls back to file time */
    private static String exifDate(java.io.File src) {
        String d = null;
        try {
            android.media.ExifInterface ex = new android.media.ExifInterface(src.getAbsolutePath());
            String dt = ex.getAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL);
            if (dt == null) dt = ex.getAttribute(android.media.ExifInterface.TAG_DATETIME);
            if (dt != null && dt.length() >= 10)
                d = dt.substring(0, 4) + " " + dt.substring(5, 7) + " " + dt.substring(8, 10);   // parts joined by spaces; drawn with hand-placed dots below
        } catch (Throwable ig) {}
        if (d == null) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy MM dd", java.util.Locale.US);
            d = f.format(new java.util.Date(src.lastModified()));
        }
        return d;
    }

    /** white-card frame: white frame, wide bottom band carrying the film name */

    /** 2x2 collage of the same photo under 4 films (requested first, then 3 others); no text */

    /** burn watermark(s) / white-card / collage onto the graded output per the camera's params */
    /** screen-blend the TEXT stamps onto a copy of the ORIGINAL (before grading); returns a temp file, or src when nothing to do */
    // ---- H (half-frame) / 2 (double-exposure): paired two-shot ingest ----

    /** read exactly {@code len} bytes of the request body into {@code out}; false on a short read */
    private static boolean readBody(java.io.InputStream in, java.io.File out, long len) {
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            long left = len;
            byte[] buf = new byte[16384];
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) break;
                fo.write(buf, 0, r);
                left -= r;
            }
            fo.close();
            return left <= 0;
        } catch (Throwable t) { return false; }
    }

    private static long parseLen(String s) {
        try { return s == null ? -1 : Long.parseLong(s.trim()); } catch (Throwable t) { return -1; }
    }

    /** H (half-frame) / 2 (double-exposure): the camera shoots two frames back to back (each may
     *  use a different film) and uploads them atomically as one request. Query:
     *  {@code mode=half|double&film0=&film1=&name=&len0=&len1=}; body = JPEG0 then JPEG1.
     *  Each frame is graded with its own film, then handed to composePair(), which writes the
     *  single final image. Output: one {@code graded_<name>.jpg} in the album. */
    private String ingestPair(java.io.InputStream raw, Map<String,String> q) {
        String mode = q.get("mode"), film0 = q.get("film0"), film1 = q.get("film1"), name = q.get("name");
        long len0 = parseLen(q.get("len0")), len1 = parseLen(q.get("len1"));
        if (mode == null || film0 == null || film1 == null || name == null || len0 <= 0 || len1 <= 0)
            return "ERR params";
        workDir.mkdirs();
        java.io.File src0 = new java.io.File(workDir, name);
        java.io.File src1 = new java.io.File(workDir, "b_" + name);
        if (!readBody(raw, src0, len0) || !readBody(raw, src1, len1)) {
            src0.delete(); src1.delete();
            return "ERR short read";
        }
        byte[] exifApp1 = Exif.app1Of(src0);
        galDir.mkdirs();
        java.io.File out = new java.io.File(galDir, "graded_" + name);
        // post-process on a pair: the lens imaging (dust / veiling / soft focus / HDF) and the film-base
        // exposure (leak) belong to EACH exposure, so they go on the sources before the grade/merge, in the
        // same order as the single-photo pipeline; the scratch is a film-surface overlay, applied to the
        // composed frame at the end.
        String post = q.get("post");
        String lens = postSubset(post, "dust", "veiling", "softfocus", "hdf");
        String leak = postSubset(post, "leak");
        String scratch = postSubset(post, "scratch");
        java.util.ArrayList<java.io.File> ptmp = new java.util.ArrayList<java.io.File>();
        if (!lens.isEmpty()) { src0 = postTmp(src0, "a_" + name, lens, ptmp); src1 = postTmp(src1, "b_" + name, lens, ptmp); }
        if (!leak.isEmpty()) { src0 = postTmp(src0, "c_" + name, leak, ptmp); src1 = postTmp(src1, "d_" + name, leak, ptmp); }
        String r;
        if ("double".equals(mode) && film0.equals(film1)) {
            // A: same film -> accumulate the two exposures in LIGHT, then develop ONCE through the engine
            java.io.File merged = new java.io.File(workDir, "m_" + name);
            String rm = mergeLight(src0, src1, merged);
            if (rm != null && !rm.startsWith("ERR") && merged.exists())
                r = Engine.get().process(merged.getAbsolutePath(), film0, out.getAbsolutePath());
            else r = "ERR merge " + rm;
            merged.delete();
        } else {
            // B (different films), or H (half): grade each, then compose
            java.io.File g0 = new java.io.File(workDir, "p0_" + name);
            java.io.File g1 = new java.io.File(workDir, "p1_" + name);
            String r0 = Engine.get().process(src0.getAbsolutePath(), film0, g0.getAbsolutePath());
            String r1 = Engine.get().process(src1.getAbsolutePath(), film1, g1.getAbsolutePath());
            if (r0 != null && !r0.startsWith("ERR") && g0.exists()
                    && r1 != null && !r1.startsWith("ERR") && g1.exists())
                r = composePair(mode, g0, g1, out);
            else r = "ERR render " + r0 + " / " + r1;
            g0.delete(); g1.delete();
        }
        if (!scratch.isEmpty() && r != null && !r.startsWith("ERR") && out.exists())
            r = scratchColorized(out, film0, scratch);       // film-surface overlay on the composed frame
        if (exifApp1 != null && r != null && !r.startsWith("ERR") && out.exists())
            Exif.carryBytes(out, exifApp1);
        boolean ok = r != null && !r.startsWith("ERR") && out.exists();
        try {   // refresh the standing notification (label shows both films)
            final String filmF = film0 + " + " + film1;
            final String expoF = readExposure(src0);
            final String timeF = readTime(src0);
            final android.graphics.Bitmap thumbF = decodeThumb(out);
            final String gpath = out.getAbsolutePath();
            android.media.MediaScannerConnection.scanFile(ctx, new String[]{gpath}, new String[]{"image/jpeg"},
                new android.media.MediaScannerConnection.OnScanCompletedListener() {
                    public void onScanCompleted(String p, android.net.Uri u) {
                        try { HostService.photoInfo(filmF, expoF, timeF, thumbF, u); }
                        catch (Throwable t2) { MainActivity.say("notif: " + t2); }
                    }
                });
        } catch (Throwable t3) { MainActivity.say("notif prep: " + t3); }
        src0.delete(); src1.delete();
        for (java.io.File t : ptmp) if (t != null && t.exists()) t.delete();
        return ok ? "OK " + out.getName() : "ERR " + r;
    }

    /** apply a post-process spec to a temp copy of f; returns f itself when the spec is empty or failed */
    private static java.io.File postTmp(java.io.File f, String tag, String spec, java.util.List<java.io.File> tmp) {
        if (spec == null || spec.isEmpty()) return f;
        java.io.File o = new java.io.File(workDir, "q_" + tag);
        String r = Engine.get().applyPost(f.getAbsolutePath(), o.getAbsolutePath(), spec);
        if (r != null && !r.equals(f.getAbsolutePath()) && new java.io.File(r).exists()) {
            java.io.File nf = new java.io.File(r); tmp.add(nf); return nf;
        }
        return f;
    }

    /** H = half-frame, 2 = double-exposure. Dispatches the per-mode composition. */
    static String composePair(String mode, java.io.File g0, java.io.File g1, java.io.File out) {
        if ("half".equals(mode)) return composeHalf(g0, g1, out);
        return composeDouble(g0, g1, out);
    }

    /** 8-bit stacked-exposure LUT: each channel -> linear light, SUM the two exposures (the film
     *  plane integrates photons), each frame pulled to 0.75, then a tanh shoulder. The knee sits at
     *  0.70 with a 0.60 scale (was 0.60 / 0.40 with a 0.80 pull): the old curve collapsed the top of
     *  the range into ~flat white, so bright areas lost their gradation. The later, gentler shoulder
     *  keeps a roughly 2x steeper slope in the highlights (more separation and detail up there) and
     *  leaves headroom (~0.95 instead of ~0.99 at the top). A gentle toe (0.18 * s * (1-s), applied
     *  only below 1.0 so the highlights keep their headroom) makes the shadows and midtones a little
     *  denser, as a summed double exposure should be. 65536 entries = one lookup per channel. */
    private static byte[] STACK_FT;
    private static synchronized byte[] stackTable() {
        if (STACK_FT == null) {
            float[] lin = new float[256];
            for (int i = 0; i < 256; i++) lin[i] = srgb2lin(i / 255f);
            byte[] t = new byte[65536];
            for (int a = 0; a < 256; a++)
                for (int b = 0; b < 256; b++) {
                    float s = (lin[a] + lin[b]) * 0.75f;             // film-plane sum, per-shot pull
                    float g = s - ((s < 1.0f) ? 0.18f * s * (1.0f - s) : 0.0f);   // gentle toe: denser shadows/midtones
                    float v = g <= 0.70f ? g : 0.70f + 0.30f * (float) Math.tanh((g - 0.70f) / 0.60f);
                    t[(a << 8) | b] = (byte) Math.round(lin2srgb(v) * 255f);
                }
            STACK_FT = t;
        }
        return STACK_FT;
    }
    private static float srgb2lin(float c) { return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4); }
    private static float lin2srgb(float c) { c = c < 0 ? 0 : c > 1 ? 1 : c; return c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1.0 / 2.4) - 0.055); }

    /** data-back segments for the B2 edge print: {mode, shutter, aperture, EV}; null where absent */
    private static String[] readExposureSegments(java.io.File f) {
        String[] out = new String[4];
        try {
            android.media.ExifInterface e = new android.media.ExifInterface(f.getAbsolutePath());
            int prog = e.getAttributeInt(android.media.ExifInterface.TAG_EXPOSURE_PROGRAM, 0);
            if (prog == 1) out[0] = "M"; else if (prog == 2) out[0] = "P";
            else if (prog == 3) out[0] = "A"; else if (prog == 4) out[0] = "S";
            String et = e.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME);
            if (et != null) try {
                double d = Double.parseDouble(et);
                if (d > 0) out[1] = d < 1 ? String.valueOf(Math.round(1 / d)) : String.valueOf(Math.round(d));
            } catch (NumberFormatException ig) {}
            String ap = e.getAttribute(android.media.ExifInterface.TAG_F_NUMBER);
            if (ap != null) try {
                double d = Double.parseDouble(ap);
                out[2] = "F" + (d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d));
            } catch (NumberFormatException ig) {}
            double ev = Exif.exposureBias(Exif.app1Of(f));   // manual APP1 parse: ExifInterface misses this tag on some cameras
            if (!Double.isNaN(ev))
                out[3] = (ev > 0 ? "+" : ev < 0 ? "-" : "±") + String.format(java.util.Locale.US, "%.1f", Math.abs(ev));
            if (out[3] == null) out[3] = "±0.0";   // data backs always print EV, even at neutral
        } catch (Throwable ig) {}
        return out;
    }

    /** banded, full-res 2-image blend: out(channel) = ft[(a<<8)|b]; writes a JPEG. Uses
     *  BitmapRegionDecoder so only one full-size (the output) bitmap is ever resident. */
    static String blendTwo(java.io.File fa, java.io.File fb, java.io.File out, byte[] ft) {
        android.graphics.Bitmap ob = null;
        android.graphics.BitmapRegionDecoder r0 = null, r1 = null;
        try {
            r0 = android.graphics.BitmapRegionDecoder.newInstance(fa.getAbsolutePath(), false);
            r1 = android.graphics.BitmapRegionDecoder.newInstance(fb.getAbsolutePath(), false);
            int W = r0.getWidth(), H = r0.getHeight();
            int w1 = r1.getWidth(), h1 = r1.getHeight();
            if (W <= 0 || H <= 0 || w1 <= 0 || h1 <= 0) return "ERR bounds";
            ob = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888);
            int band = Math.max(1, Math.min(H, 2000000 / Math.max(1, W)));   // bound the transient band buffers
            int[] pa = new int[W * band], pb = new int[W * band];
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            for (int y = 0; y < H; y += band) {
                int bh = Math.min(band, H - y);
                android.graphics.Bitmap a = r0.decodeRegion(new android.graphics.Rect(0, y, W, y + bh), o);
                android.graphics.Bitmap b;
                if (w1 == W && h1 == H) {
                    b = r1.decodeRegion(new android.graphics.Rect(0, y, W, y + bh), o);
                } else {   // defensive: differing geometry -> scale frame1's slice to match
                    int sy = (int) ((long) y * h1 / H), sy2 = (int) ((long) (y + bh) * h1 / H);
                    if (sy2 <= sy) sy2 = sy + 1;
                    android.graphics.Bitmap rb = r1.decodeRegion(new android.graphics.Rect(0, sy, w1, sy2), o);
                    b = android.graphics.Bitmap.createBitmap(W, bh, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas bc = new android.graphics.Canvas(b);
                    android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                    bc.drawBitmap(rb, new android.graphics.Rect(0, 0, rb.getWidth(), rb.getHeight()),
                            new android.graphics.Rect(0, 0, W, bh), bp);
                    rb.recycle();
                }
                if (a == null || b == null) { if (a != null) a.recycle(); if (b != null) b.recycle(); return "ERR decode"; }
                a.getPixels(pa, 0, W, 0, 0, W, bh);
                b.getPixels(pb, 0, W, 0, 0, W, bh);
                int n = W * bh;
                for (int i = 0; i < n; i++) {
                    int av = pa[i], bv = pb[i];
                    int rr = ft[(((av >> 16) & 0xFF) << 8) | ((bv >> 16) & 0xFF)] & 0xFF;
                    int rg = ft[(((av >> 8) & 0xFF) << 8) | ((bv >> 8) & 0xFF)] & 0xFF;
                    int rb2 = ft[((av & 0xFF) << 8) | (bv & 0xFF)] & 0xFF;
                    pa[i] = 0xFF000000 | (rr << 16) | (rg << 8) | rb2;
                }
                ob.setPixels(pa, 0, W, 0, y, W, bh);
                a.recycle(); b.recycle();
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            ob.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            return out.getAbsolutePath();
        } catch (Throwable t) { return "ERR blend " + t; }
        finally {
            if (ob != null) ob.recycle();
            if (r0 != null) r0.recycle();
            if (r1 != null) r1.recycle();
        }
    }

    /** 2 = double exposure with DIFFERENT films (the two graded frames can't be developed together):
     *  average them in linear light — each exposure at half, exposure-neutral, no highlight clipping. */
    static String composeDouble(java.io.File g0, java.io.File g1, java.io.File out) {
        return blendTwo(g0, g1, out, stackTable());
    }

    /** same-film double exposure: accumulate the two ORIGINAL exposures in light, then develop ONCE
     *  through the film engine — the physically correct film behaviour (curve applied to the sum). */
    static String mergeLight(java.io.File src0, java.io.File src1, java.io.File out) {
        return blendTwo(src0, src1, out, stackTable());
    }

    /** H (half-frame): each source's CENTRAL HALF (3:4, full height) is placed left/right on a
     *  3:2 pure-black canvas, with outer margin AND the gap between them = half the white-card
     *  narrow border (side/2). Each half gets the white-card soft inner edge + small rounded corners.
     *  Only the needed central strip is decoded (BitmapRegionDecoder) to keep Java-heap low, so real
     *  24MP frames stay FULL resolution without enabling largeHeap. Two 3:4 halves side by side = 3:2. */
    static String composeHalf(java.io.File g0, java.io.File g1, java.io.File out) {
        android.graphics.Bitmap ob = null;
        try {
            android.graphics.BitmapFactory.Options b = new android.graphics.BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(g0.getAbsolutePath(), b);
            if (b.outWidth <= 0 || b.outHeight <= 0) return "ERR bounds";
            int sc = 1;   // only downsample absurdly large inputs: the output bitmap is ~1.5·H·H·4 bytes
            while (1.5f * (b.outHeight / sc) * (b.outHeight / sc) * 4f > 120e6f) sc *= 2;
            int effH = b.outHeight / sc;
            int H = effH, W = Math.round(effH * 1.5f);                       // output 3:2
            int mn = Math.min(W, H);
            int side = Math.round(mn * 0.030f);                              // white-card narrow border
            int m = 0;                                                        // NO margin: the photos reach the canvas edges
            int gap = Math.round(5.625f * Math.max(1, side / 4));            // spacing kept (decoupled from the now-zero margin)
            int hh = H - 2 * m;
            int hw = Math.round((W - 2 * m - gap) / 2f);
            int xR = m + hw + gap;
            int amp = Math.max(1, Math.round(Math.max(2, mn / 300) * 0.5625f)); // inner-edge overflow amplitude (x3/4)
            ob = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(ob);
            cv.drawColor(0xFF000000);                                        // pure black ground
            android.graphics.Paint bp = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
            drawHalfSrc(cv, g0, sc, m - amp, m - amp, hw + 2 * amp, hh + 2 * amp, bp);   // outer overflow is clipped by the canvas -> hard edges
            drawHalfSrc(cv, g1, sc, xR - amp, m - amp, hw + 2 * amp, hh + 2 * amp, bp);
            dressHalf(cv, m, m, hw, hh, mn, amp, true);                      // left photo: rough only on its gap-facing (right) edge
            dressHalf(cv, xR, m, hw, hh, mn, amp, false);                    // right photo: rough only on its gap-facing (left) edge
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            ob.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            return out.getAbsolutePath();
        } catch (Throwable t) { return "ERR half " + t; }
        finally { if (ob != null) ob.recycle(); }
    }

    /** decode ONLY the central half strip of the source and draw it scaled into the dest rect */
    private static void drawHalfSrc(android.graphics.Canvas cv, java.io.File f, int sc,
                                    int x, int y, int w, int h, android.graphics.Paint bp) throws Exception {
        android.graphics.BitmapRegionDecoder rd = android.graphics.BitmapRegionDecoder.newInstance(f.getAbsolutePath(), false);
        try {
            int iw = rd.getWidth(), ih = rd.getHeight();
            // cover-crop the source to the slot aspect, centered. A landscape 3:2 frame reduces to
            // its central half (camera half-frame semantics, full height); any other aspect — 4:3,
            // 16:9, portrait, square (spy mode accepts anything) — gets a plain center crop instead
            // of being stretched out of shape.
            float slotA = (float) w / (float) h, srcA = (float) iw / (float) ih;
            int cw, ch;
            if (srcA > slotA) { ch = ih; cw = Math.max(1, Math.round(ih * slotA)); }
            else              { cw = iw; ch = Math.max(1, Math.round(iw / slotA)); }
            int cx = (iw - cw) / 2, cy = (ih - ch) / 2;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sc;
            o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            android.graphics.Bitmap half = rd.decodeRegion(new android.graphics.Rect(cx, cy, cx + cw, cy + ch), o);
            if (half == null) return;
            cv.drawBitmap(half, new android.graphics.Rect(0, 0, half.getWidth(), half.getHeight()),
                    new android.graphics.RectF(x, y, x + w, y + h), bp);
            half.recycle();
        } finally { rd.recycle(); }
    }

    /** rough foam edge ONLY on the side facing the central gap (roughRight = the photo's RIGHT edge is
     *  rough). The other three sides are plain canvas edges (their overflow is clipped away), and there
     *  is no margin/corner rounding any more. */
    private static void dressHalf(android.graphics.Canvas cv, int x, int y, int w, int h, int mn, int amp, boolean roughRight) {
        int sh = Math.max(2, mn / 300);
        int fade = Math.max(1, Math.round(sh * 0.375f));                     // soft-edge fade length (x3/2)
        int x0 = x, y0 = y, x1 = x + w, y1 = y + h;
        java.util.Random rnd = new java.util.Random(0x0F6A1E5AL);
        float[] wr = wavh(h, amp, rnd.nextLong());
        android.graphics.Paint sp = new android.graphics.Paint();
        int black = 0xFF000000, klar = 0x00000000;
        if (roughRight) {                                                    // blend the RIGHT edge to black
            for (int i = 0; i < h; i += 2) {
                float b = x1 + wr[i];
                sp.setShader(new android.graphics.LinearGradient(b, 0, b - fade, 0, black, klar, android.graphics.Shader.TileMode.CLAMP));
                cv.drawRect(b - fade, y0 + i, x1 + amp, y0 + Math.min(h, i + 2), sp);
            }
        } else {                                                             // blend the LEFT edge to black
            for (int i = 0; i < h; i += 2) {
                float b = x0 + wr[i];
                sp.setShader(new android.graphics.LinearGradient(b, 0, b + fade, 0, black, klar, android.graphics.Shader.TileMode.CLAMP));
                cv.drawRect(x0 - amp, y0 + i, b + fade, y0 + Math.min(h, i + 2), sp);
            }
        }
    }

    /** edge profile = low-frequency undulation band + high-frequency roughness band. Each band is a
     *  value-noise fBm (Gaussian lattice, smoothstep), RMS-normalised, then mixed by LOWW/HIGHW so the
     *  two scales are independently controllable. Final peak normalised to `amp`. */
    /** B2 variant: same shape as wavh but all frequencies /5 (long slow undulation) */
    private static float[] wavh5(int n, float amp, long seed) {
        java.util.Random r = new java.util.Random(seed);
        float[] lo = octaveSum(n, r, 0.9f, 2, 0.6f);     // low band: 0.9, 1.8 cycles
        float[] hi = octaveSum(n, r, 7.2f, 5, 0.75f);    // high band: 7.2..1.15 cycles
        normRms(lo); normRms(hi);
        float LOWW = 0.5f, HIGHW = 1.0f;
        float[] out = new float[n];
        float mx = 1e-6f;
        for (int i = 0; i < n; i++) { out[i] = LOWW * lo[i] + HIGHW * hi[i]; mx = Math.max(mx, Math.abs(out[i])); }
        float s = amp / mx;
        for (int i = 0; i < n; i++) out[i] *= s;
        return out;
    }

    private static float[] wavh(int n, float amp, long seed) {
        java.util.Random r = new java.util.Random(seed);
        float[] lo = octaveSum(n, r, 4.5f, 2, 0.6f);     // low band: 4.5, 9 cycles  (big undulation)
        float[] hi = octaveSum(n, r, 36f, 5, 0.75f);     // high band: 36..576 cycles (roughness)
        normRms(lo); normRms(hi);
        float LOWW = 0.5f, HIGHW = 1.0f;                 // relative energy: low undulation vs high roughness
        float[] out = new float[n];
        float mx = 1e-6f;
        for (int i = 0; i < n; i++) { out[i] = LOWW * lo[i] + HIGHW * hi[i]; mx = Math.max(mx, Math.abs(out[i])); }
        float s = amp / mx;
        for (int i = 0; i < n; i++) out[i] *= s;
        return out;
    }

    /** sum of `octs` fBm octaves (freq x2, amp x`pers` per octave) starting at `freq` cycles */
    private static float[] octaveSum(int n, java.util.Random r, float freq, int octs, float pers) {
        float[] o = new float[n];
        float a = 1f;
        for (int k = 0; k < octs; k++) {
            int m = Math.max(2, Math.round(freq));
            float[] lat = new float[m + 1];
            for (int i = 0; i <= m; i++) lat[i] = (float) r.nextGaussian();
            for (int i = 0; i < n; i++) {
                float t = (i / (float) Math.max(1, n - 1)) * m;
                int i0 = (int) t; if (i0 >= m) i0 = m - 1;
                float f = t - i0;
                float u = f * f * (3 - 2 * f);
                o[i] += a * (lat[i0] * (1 - u) + lat[i0 + 1] * u);
            }
            freq *= 2f; a *= pers;
        }
        return o;
    }

    private static void normRms(float[] v) {
        float s = 0;
        for (float x : v) s += x * x;
        float rms = (float) Math.sqrt(s / v.length) + 1e-6f;
        for (int i = 0; i < v.length; i++) v[i] /= rms;
    }

    /** spy mode: run the ingest pipeline on a local file — arrival-time stamps built host-side, output into the DCIM album */
    static String spyIngest(java.io.File src, String film, int mode, String post) {
        try {
            java.util.HashMap<String,String> q = new java.util.HashMap<>();
            String name = src.getName();
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            if (mode == 4) q.put("b", "1");                       // B1: white-card frame
            else if (mode == 5) q.put("b2", "1");                 // B2: film edge, sprockets + stock name (right after B1)
            else if (mode == 10) q.put("x", "1");                 // 4-film collage
            else if (mode == 11) q.put("w", "1");                 // W: wide 2.7:1 crop
            else if (mode == 12) q.put("wb1", "1");               // WB1: wide white-card
            else if (mode == 13) q.put("wb2", "1");               // WB2: wide film edge
            else if (mode == 14) q.put("wb3", "1");               // WB3: wide sprocket (photo covers the strip)
            else {
                String bl = null, br = null;
                if (mode == 1) bl = "@DATE";                      // exifDate() resolves, mtime fallback
                else if (mode == 2) bl = readExposure(src);
                else if (mode == 3) { bl = "@DATE"; br = readExposure(src); }
                else if (mode == 6) bl = film;
                else if (mode == 7) { bl = film; br = readExposure(src); }
                if (bl != null) q.put("stamp", bl);
                if (br != null) q.put("stamp2", br);
            }
            if (post != null && post.length() > 0) q.put("post", post);
            byte[] exifApp1 = Exif.app1Of(src);
            galDir.mkdirs();
            java.io.File out = new java.io.File(galDir, "graded_" + base + ".jpg");
            String leakdust = postSubset(post, "leak", "dust", "veiling", "softfocus", "hdf");
            String scratch = postSubset(post, "scratch");
            String photoOnly = postSubset(leakdust, "dust", "veiling", "softfocus", "hdf");
            String leakOnly = postSubset(leakdust, "leak");
            boolean isX = q.get("x") != null;
            boolean photoFrame = (q.get("b") != null) || (q.get("wb1") != null);
            boolean filmBorder = isFilmBorder(q);
            java.io.File dusted = src;
            if (!photoOnly.isEmpty()) {
                java.io.File dv = new java.io.File(workDir, "dv_" + base + ".jpg");
                String rd = Engine.get().applyPost(src.getAbsolutePath(), dv.getAbsolutePath(), photoOnly);
                if (rd != null && !rd.equals(src.getAbsolutePath()) && new java.io.File(rd).exists()) dusted = new java.io.File(rd);
            }
            java.io.File pipeIn = textStampOriginal(dusted, src, q);
            if (dusted != src && dusted != pipeIn) dusted.delete();
            String r = null;
            if (isX) { r = collagePost(pipeIn, film, leakOnly, scratch, out); if (pipeIn != src) pipeIn.delete(); }
            else {
            if (filmBorder) {
                pipeIn = buildBorderCanvas(pipeIn, workDir, base, q, null);
                if (!leakOnly.isEmpty()) {
                    java.io.File ov = new java.io.File(workDir, "ov_" + base + ".jpg");
                    String rr = Engine.get().applyPost(pipeIn.getAbsolutePath(), ov.getAbsolutePath(), leakOnly);
                    if (rr != null && !rr.equals(pipeIn.getAbsolutePath()) && new java.io.File(rr).exists()) pipeIn = new java.io.File(rr);
                }
            } else if (!leakOnly.isEmpty()) {
                java.io.File ov = new java.io.File(workDir, "ov_" + base + ".jpg");
                String rr = Engine.get().applyPost(pipeIn.getAbsolutePath(), ov.getAbsolutePath(), leakOnly);
                if (rr != null && !rr.equals(pipeIn.getAbsolutePath()) && new java.io.File(rr).exists()) pipeIn = new java.io.File(rr);
            }
            r = Engine.get().process(pipeIn.getAbsolutePath(), film, out.getAbsolutePath());
            if (pipeIn != src) pipeIn.delete();
            if (r != null && !r.startsWith("ERR") && new java.io.File(r).exists()) {
                if (filmBorder) { r = screenBorderLayer(out, src, film, q); if (!scratch.isEmpty()) r = scratchOverBorder(new java.io.File(r), film, scratch); }
                else if (photoFrame) { if (!scratch.isEmpty()) r = scratchColorized(out, film, scratch); r = stamped(out, src, film, q); }
                else { r = stamped(out, src, film, q); if (!scratch.isEmpty()) r = scratchColorized(new java.io.File(r), film, scratch); }
            }
            }
            if (exifApp1 != null && r != null && !r.startsWith("ERR") && new java.io.File(r).exists())
                Exif.carryBytes(out, exifApp1);
            try {   // register into the gallery album + refresh the standing notification (same as camera ingest)
                final String filmF = film;
                final String expoF = readExposure(src);
                final String timeF = readTime(src);
                final android.graphics.Bitmap thumbF = decodeThumb(new java.io.File(r));
                final String gpath = out.getAbsolutePath();
                android.media.MediaScannerConnection.scanFile(ctx, new String[]{gpath},
                    new String[]{"image/jpeg"},
                    new android.media.MediaScannerConnection.OnScanCompletedListener() {
                        public void onScanCompleted(String p, android.net.Uri u) {
                            try { HostService.photoInfo(filmF, expoF, timeF, thumbF, u); }
                            catch (Throwable t2) { MainActivity.say("notif: " + t2); }
                        }
                    });
            } catch (Throwable ig) {}
            boolean ok = r != null && !r.startsWith("ERR") && new java.io.File(r).exists();
            return ok ? "OK " + out.getName() : "ERR " + r;
        } catch (Throwable t) { return "ERR " + t; }
    }

    /** spy pair ingest (半格/双重曝光): two arrived files compose into ONE graded image.
     *  spy mode has a single film, so double always takes the same-film physics path
     *  (merge in light, develop once); half grades both and places the two halves. */
    static String spyPairIngest(java.io.File src0, java.io.File src1, String film, boolean half) {
        return spyPairIngest(src0, src1, film, film, half, "");
    }

    /** Rewind variant: the two exposures may belong to different slots/films, and each slot has
     *  its own post spec.  Lens-level post (dust/veiling/softfocus/hdf) and leak are applied to
     *  each exposure before grading; scratch is a film-surface overlay on the composed frame. */
    static String spyPairIngest(java.io.File src0, java.io.File src1, String film0, String film1,
                                boolean half, String post) {
        try {
            if (film0 == null || film0.length() == 0) film0 = "LK-200";
            if (film1 == null || film1.length() == 0) film1 = film0;
            String mode = half ? "half" : "double";
            String name = src0.getName();
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            byte[] exifApp1 = Exif.app1Of(src0);
            String lens = postSubset(post, "dust", "veiling", "softfocus", "hdf");
            String leak = postSubset(post, "leak");
            String scratch = postSubset(post, "scratch");
            java.util.ArrayList<java.io.File> ptmp = new java.util.ArrayList<java.io.File>();
            java.io.File p0 = src0, p1 = src1;
            if (!lens.isEmpty()) {
                p0 = postTmp(p0, "a_" + base + ".jpg", lens, ptmp);
                p1 = postTmp(p1, "b_" + base + ".jpg", lens, ptmp);
            }
            if (!leak.isEmpty()) {
                p0 = postTmp(p0, "c_" + base + ".jpg", leak, ptmp);
                p1 = postTmp(p1, "d_" + base + ".jpg", leak, ptmp);
            }
            galDir.mkdirs();
            java.io.File out = new java.io.File(galDir, "graded_" + base + "_pair.jpg");
            String r;
            if (!half && film0.equals(film1)) {
                java.io.File merged = new java.io.File(workDir, "m_" + base + ".jpg");
                String rm = mergeLight(p0, p1, merged);
                if (rm != null && !rm.startsWith("ERR") && merged.exists())
                    r = Engine.get().process(merged.getAbsolutePath(), film0, out.getAbsolutePath());
                else r = "ERR merge " + rm;
                merged.delete();
            } else {
                java.io.File g0 = new java.io.File(workDir, "p0_" + base + ".jpg");
                java.io.File g1 = new java.io.File(workDir, "p1_" + base + ".jpg");
                String r0 = Engine.get().process(p0.getAbsolutePath(), film0, g0.getAbsolutePath());
                String r1 = Engine.get().process(p1.getAbsolutePath(), film1, g1.getAbsolutePath());
                if (r0 != null && !r0.startsWith("ERR") && g0.exists()
                        && r1 != null && !r1.startsWith("ERR") && g1.exists())
                    r = composePair(mode, g0, g1, out);
                else r = "ERR render " + r0 + " / " + r1;
                g0.delete(); g1.delete();
            }
            if (!scratch.isEmpty() && r != null && !r.startsWith("ERR") && out.exists())
                r = scratchColorized(out, film0, scratch);
            if (exifApp1 != null && r != null && !r.startsWith("ERR") && out.exists())
                Exif.carryBytes(out, exifApp1);
            boolean ok = r != null && !r.startsWith("ERR") && out.exists();
            try {
                if (ok) {
                    final String filmF = film0.equals(film1) ? film0 : film0 + " + " + film1;
                    final String expoF = readExposure(src0);
                    final String timeF = readTime(src0);
                    final android.graphics.Bitmap thumbF = decodeThumb(out);
                    final String gpath = out.getAbsolutePath();
                    android.media.MediaScannerConnection.scanFile(ctx, new String[]{gpath}, new String[]{"image/jpeg"},
                        new android.media.MediaScannerConnection.OnScanCompletedListener() {
                            public void onScanCompleted(String p, android.net.Uri u) {
                                try { HostService.photoInfo(filmF, expoF, timeF, thumbF, u); }
                                catch (Throwable t2) { MainActivity.say("notif: " + t2); }
                            }
                        });
                }
            } catch (Throwable ig) {}
            for (java.io.File t : ptmp) if (t != null && t.exists()) t.delete();
            return ok ? "OK " + out.getName() : "ERR " + r;
        } catch (Throwable t) { return "ERR " + t; }
    }

    /** 5x7 dot-matrix glyphs (camera data-back look), MSB = leftmost column */
    private static final String DM_KEY = "0123456789PASMF/.+-\u00b1";
    private static final byte[][] DM_GLYPH = {
        {0x0E,0x11,0x13,0x15,0x19,0x11,0x0E}, // 0
        {0x04,0x0C,0x04,0x04,0x04,0x04,0x0E}, // 1
        {0x0E,0x11,0x01,0x02,0x04,0x08,0x1F}, // 2
        {0x1F,0x02,0x04,0x02,0x01,0x11,0x0E}, // 3
        {0x02,0x06,0x0A,0x12,0x1F,0x02,0x02}, // 4
        {0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E}, // 5
        {0x06,0x08,0x10,0x1E,0x11,0x11,0x0E}, // 6
        {0x1F,0x01,0x02,0x04,0x08,0x08,0x08}, // 7
        {0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E}, // 8
        {0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C}, // 9
        {0x1E,0x11,0x11,0x1E,0x10,0x10,0x10}, // P
        {0x0E,0x11,0x11,0x1F,0x11,0x11,0x11}, // A
        {0x0F,0x10,0x10,0x0E,0x01,0x01,0x1E}, // S
        {0x11,0x1B,0x15,0x15,0x11,0x11,0x11}, // M
        {0x1F,0x10,0x10,0x1E,0x10,0x10,0x10}, // F
        {0x01,0x02,0x02,0x04,0x08,0x08,0x10}, // /
        {0x00,0x00,0x00,0x00,0x00,0x0C,0x0C}, // .
        {0x00,0x04,0x04,0x1F,0x04,0x04,0x00}, // +
        {0x00,0x00,0x00,0x1F,0x00,0x00,0x00}, // -
        {0x04,0x04,0x1F,0x04,0x1F,0x00,0x00}, // plus-minus
    };

    /** one dot-matrix token centered at (cx, cy): 5x7 glyphs at pitch d — dots overlap so the
     *  glyph reads solid with no dark seams between pixels */
    private static void drawDotToken(android.graphics.Canvas cv, String s, float cx, float cy, float d, android.graphics.Paint glow) {
        float x0 = cx - (6 * s.length() - 1) * d / 2f;
        for (int i = 0; i < s.length(); i++) {
            int k = DM_KEY.indexOf(s.charAt(i));
            if (k < 0) continue;
            for (int r = 0; r < 7; r++)
                for (int c = 0; c < 5; c++)
                    if ((DM_GLYPH[k][r] & (0x10 >> c)) != 0) {
                        float dx = x0 + (i * 6 + c) * d + d / 2f;
                        cv.drawCircle(dx, cy - 3 * d + r * d + d / 2f, d * 0.68f, glow);
                    }
        }
    }

    /** B2 (film edge): the scanned-negative look — generous near-black rebate strips above and
     *  below the photo, each carrying a row of sprocket holes (portrait, 35mm BH-perforation
     *  proportions), mirrored gold edge print (stock name + shooting data line; edge marks read
     *  reversed when the rebate is scanned emulsion-side up) and a frame mark. The photo/rebate
     *  boundary dissolves with the same wavy soft edge the half-frame mode uses. */
    /** B2 own edge frame code (non-patented): START(1,1,0) then N(6, MSB-first) then even-parity(1) then STOP(0,1,1) */
    /** keep narrow bars (0) more numerous than wide bars (1); flip all bits if not */
    private static int[] balance(int[] bits) {
        int ones = 0;
        for (int b : bits) ones += b;
        if (ones * 2 > bits.length) {
            int[] out = new int[bits.length];
            for (int i = 0; i < bits.length; i++) out[i] = 1 - bits[i];
            return out;
        }
        return bits;
    }
    /** lower barcode half: a 13-bit multiplicative hash of the frame code (same bit count) */
    private static int[] codeHash(int[] code) {
        int x = 0;
        for (int b : code) x = (x << 1) | b;
        int h = (int) (((x + 1) * 2654435761L) >>> 16);
        int[] out = new int[code.length];
        for (int i = 0; i < code.length; i++) out[i] = (h >> (code.length - 1 - i)) & 1;
        return out;
    }
    /** 10-bit frame code: d0 = half flag (0=full, 1=back half), d1..d6 = frame number (LSB-first),
     *  d7..d9 = 3 check bits = low 3 bits of the sum of d0..d6 (2 more check bits than before) */
    private static int[] frameCode(int n, boolean half) {
        int[] d = new int[10];
        d[0] = half ? 1 : 0;
        int f = n & 63;
        int sum = d[0];
        for (int i = 0; i < 6; i++) { d[1 + i] = (f >> i) & 1; sum += d[1 + i]; }
        for (int i = 0; i < 3; i++) d[7 + i] = (sum >> i) & 1;
        return d;
    }
    private static int[] of6kCode(int n) {
        int[] b = new int[13];
        b[0] = 1; b[1] = 1; b[2] = 0;
        int v = n & 63;
        for (int i = 0; i < 6; i++) b[3 + i] = (v >> (5 - i)) & 1;
        int par = 0;
        for (int i = 0; i < 6; i++) par ^= (v >> i) & 1;
        b[9] = par;
        b[10] = 0; b[11] = 1; b[12] = 1;
        return b;
    }

    private static float fractN(float v) { return v - (float) Math.floor(v); }
    private static float mixN(float a, float b, float t) { return a + (b - a) * t; }
    private static float hashN(float px, float py, float seed) {
        float a = fractN(px * 0.1031f + seed * 0.317f);
        float b = fractN(py * 0.1031f + seed * 0.317f);
        float c = fractN(px * 0.1031f + seed * 0.317f);
        float d = a * (b + 33.33f) + b * (c + 33.33f) + c * (a + 33.33f);
        a += d; b += d; c += d;
        return fractN((a + b) * c);
    }
    private static float vnN(float px, float py, float seed) {
        float ix = (float) Math.floor(px), iy = (float) Math.floor(py);
        float fx = px - ix, fy = py - iy;
        fx = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f);
        fy = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f);
        return mixN(mixN(hashN(ix, iy, seed), hashN(ix + 1, iy, seed), fx),
                    mixN(hashN(ix, iy + 1, seed), hashN(ix + 1, iy + 1, seed), fx), fy);
    }
    private static float gnN(float px, float py, float seed) {
        float rx = 0.891f * px + 0.454f * py, ry = -0.454f * px + 0.891f * py;
        return 0.5f * vnN(px, py, seed) + 0.5f * vnN(rx + 31.7f, ry + 11.3f, seed);
    }
    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    /** GD-200-style film grain (mono value-noise fBm) applied to an ARGB layer; size scales with height */
    private static void grainLayer(android.graphics.Bitmap layer, float amount, float size) {
        int w = layer.getWidth(), h = layer.getHeight();
        int[] px = new int[w * h];
        layer.getPixels(px, 0, w, 0, 0, w, h);
        float usize = Math.max(size * (h / 4000f), 1f);
        for (int y = 0; y < h; y++) {
            float gy = y / usize;
            for (int x = 0; x < w; x++) {
                int i = y * w + x, p = px[i];
                if (((p >>> 24) & 0xff) == 0) continue;
                float r = ((p >> 16) & 0xff) / 255f, g = ((p >> 8) & 0xff) / 255f, b = (p & 0xff) / 255f;
                float gx = x / usize;
                float n1 = gnN(gx, gy, 1f), n2 = gnN(gx + 37.7f, gy + 17.3f, 1f), n3 = gnN(gx + 11.7f, gy + 73.9f, 1f);
                float lum = 0.299f * r + 0.587f * g + 0.114f * b;
                float nv = ((n1 + n2 + n3) / 3f - 0.5f) * (amount / 50f) * (1f - Math.abs(lum - 0.5f) * 1.2f) * 0.35f;
                r = clamp01(r + nv); g = clamp01(g + nv); b = clamp01(b + nv);
                px[i] = (p & 0xFF000000) | (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
            }
        }
        layer.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static boolean isCjk(char ch) { return ch >= 0x2E80 && ch <= 0x9FFF; }

    /** film-edge text with per-character fonts: Archivo Black for Latin, WenQuanYi Zen Hei for CJK
     *  (CJK scaled 0.726x so its glyph height matches Archivo's cap height) */
    private static void drawEdge(android.graphics.Canvas c, String s, float x, float baseY,
                                 android.graphics.Paint.Align al, android.graphics.Paint p, float size) {
        float cjkSize = size * 0.85f;                  // CJK scaled toward Archivo's cap height
        p.setTextAlign(android.graphics.Paint.Align.LEFT);
        float total = 0;
        for (int i = 0; i < s.length(); i++) {
            boolean cj = isCjk(s.charAt(i));
            p.setTypeface(cj ? Ux.wqyZen(ctx) : Ux.archivo(ctx));
            p.setTextSize(cj ? cjkSize : size);
            p.setFakeBoldText(cj);                     // CJK bold
            total += p.measureText(s, i, i + 1);
        }
        float sx = x;
        if (al == android.graphics.Paint.Align.CENTER) sx = x - total / 2f;
        else if (al == android.graphics.Paint.Align.RIGHT) sx = x - total;
        for (int i = 0; i < s.length(); i++) {
            boolean cj = isCjk(s.charAt(i));
            p.setTypeface(cj ? Ux.wqyZen(ctx) : Ux.archivo(ctx));
            p.setTextSize(cj ? cjkSize : size);
            p.setFakeBoldText(cj);
            c.drawText(s, i, i + 1, sx, baseY, p);
            sx += p.measureText(s, i, i + 1);
        }
    }

    /** apply a 3D LUT (film's own, LUT node only) to a bitmap, skipping the photo rect;
     *  alpha-aware: colour is unpremultiplied before lookup and re-premultiplied after */
    private static void lutRegion(android.graphics.Bitmap bm, CubeLib.Lut lut, int sx0, int sy0, int sx1, int sy1) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        int n = lut.n, nm1 = n - 1, n2 = n * n;
        float[] d = lut.data;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= sx0 && x < sx1 && y >= sy0 && y < sy1) continue;   // leave the graded photo untouched
                int c = px[y * w + x];
                int a = (c >>> 24) & 255;
                if (a == 0) continue;
                float fr = ((c >> 16) & 255) / 255f, fg = ((c >> 8) & 255) / 255f, fb = (c & 255) / 255f;
                float ri = fr * nm1, gi = fg * nm1, bi = fb * nm1;
                int r0 = (int) ri, g0 = (int) gi, b0 = (int) bi;
                int r1 = Math.min(r0 + 1, nm1), g1 = Math.min(g0 + 1, nm1), b1 = Math.min(b0 + 1, nm1);
                float rt = ri - r0, gt = gi - g0, bt = bi - b0;
                int i000 = (b0 * n + g0) * n + r0, i100 = (b0 * n + g0) * n + r1;
                int i010 = (b0 * n + g1) * n + r0, i110 = (b0 * n + g1) * n + r1;
                int i001 = (b1 * n + g0) * n + r0, i101 = (b1 * n + g0) * n + r1;
                int i011 = (b1 * n + g1) * n + r0, i111 = (b1 * n + g1) * n + r1;
                float[] oc = new float[3];
                for (int ch = 0; ch < 3; ch++) {
                    float c00 = d[i000 * 3 + ch] + (d[i100 * 3 + ch] - d[i000 * 3 + ch]) * rt;
                    float c10 = d[i010 * 3 + ch] + (d[i110 * 3 + ch] - d[i010 * 3 + ch]) * rt;
                    float c01 = d[i001 * 3 + ch] + (d[i101 * 3 + ch] - d[i001 * 3 + ch]) * rt;
                    float c11 = d[i011 * 3 + ch] + (d[i111 * 3 + ch] - d[i011 * 3 + ch]) * rt;
                    float c0 = c00 + (c10 - c00) * gt, c1 = c01 + (c11 - c01) * gt;
                    oc[ch] = c0 + (c1 - c0) * bt;
                }
                int nr = Math.max(0, Math.min(255, Math.round(oc[0] * 255f)));
                int ng = Math.max(0, Math.min(255, Math.round(oc[1] * 255f)));
                int nb = Math.max(0, Math.min(255, Math.round(oc[2] * 255f)));
                px[y * w + x] = (a << 24) | (nr << 16) | (ng << 8) | nb;
            }
        }
        bm.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** W (wide): crop the graded frame to the wide 2.7:1 ratio — full width, centred height.
     *  Portrait frames are rotated 90 deg CW first (same rule the B2 film edge uses), so a
     *  portrait-held shot still yields a landscape panorama. Overwrites the graded file. */
    private static String cropWide(java.io.File graded) {
        android.graphics.Bitmap bm0 = null, bm = null;
        try {
            bm0 = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());
            if (bm0 == null) return graded.getAbsolutePath();
            if (bm0.getHeight() > bm0.getWidth()) {                    // portrait: rotate 90 deg CW first, so the frame ends up landscape
                android.graphics.Matrix rm = new android.graphics.Matrix();
                rm.postRotate(90f);
                android.graphics.Bitmap rot = android.graphics.Bitmap.createBitmap(bm0, 0, 0, bm0.getWidth(), bm0.getHeight(), rm, true);
                bm0.recycle();
                bm0 = rot;
            }
            int fw = bm0.getWidth(), fh = bm0.getHeight();
            final float target = 2.7f;                                 // wide (2.7:1) ratio
            int cw, ch;
            if (fw > fh * target) { ch = fh; cw = Math.round(fh * target); }
            else                  { cw = fw; ch = Math.round(fw / target); }
            bm = (cw == fw && ch == fh) ? bm0
                : android.graphics.Bitmap.createBitmap(bm0, (fw - cw) / 2, (fh - ch) / 2, cw, ch);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(graded);
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            if (bm != bm0) bm.recycle();
            bm0.recycle();
            return graded.getAbsolutePath();
        } catch (Throwable t) {
            MainActivity.say("wide ex " + t);
            Engine.dbg("wide ex " + t);
            return graded.getAbsolutePath();
        }
    }

    /** WB1 (wide white-card): a 2.7:1 photo mounted on white paper. Canvas = photo + margins; the paper
     *  uses B1's soft-edge algorithm and constants (inner shadow mn/300 + highlight, rounded corners),
     *  only the margins differ — top and bottom are the wide band (mn/10), left and right the narrow
     *  one (mn/30), where mn = the photo height. */
    private static String cardWide(java.io.File graded) {
        android.graphics.Bitmap src = null, ob = null;
        try {
            src = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());
            if (src == null) return graded.getAbsolutePath();
            int W = src.getWidth(), H = src.getHeight(), mn = Math.min(W, H);
            int side = Math.round(mn * 0.030f), bar = Math.round(mn * 0.100f);
            int CW = W + 2 * side, CH = H + 2 * bar;
            ob = android.graphics.Bitmap.createBitmap(CW, CH, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(ob);
            cv.drawColor(0xFFFFFFFF);                                   // white paper
            cv.drawBitmap(src, side, bar, null);
            int x0 = side, y0 = bar, x1 = side + W, y1 = bar + H;
            int sh = Math.max(2, mn / 300), wl = Math.max(1, sh / 2);
            android.graphics.Paint sp = new android.graphics.Paint();
            int dark = 0x59000000;
            sp.setShader(new android.graphics.LinearGradient(0, y0, 0, y0 + sh, dark, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(x0, y0, x1, y0 + sh, sp);
            sp.setShader(new android.graphics.LinearGradient(0, y1 - sh, 0, y1, 0x00000000, dark, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(x0, y1 - sh, x1, y1, sp);
            sp.setShader(new android.graphics.LinearGradient(x0, 0, x0 + sh, 0, dark, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(x0, y0, x0 + sh, y1, sp);
            sp.setShader(new android.graphics.LinearGradient(x1 - sh, 0, x1, 0, 0x00000000, dark, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(x1 - sh, y0, x1, y1, sp);
            sp.setShader(new android.graphics.LinearGradient(0, y0, 0, y0 + wl, 0x99FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(x0, y0, x1, y0 + wl, sp);
            sp.setShader(new android.graphics.LinearGradient(0, y1 - wl, 0, y1, 0x00FFFFFF, 0x99FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(x0, y1 - wl, x1, y1, sp);
            sp.setShader(new android.graphics.LinearGradient(x0, 0, x0 + wl, 0, 0x99FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(x0, y0, x0 + wl, y1, sp);
            sp.setShader(new android.graphics.LinearGradient(x1 - wl, 0, x1, 0, 0x00FFFFFF, 0x99FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(x1 - wl, y0, x1, y1, sp);
            android.graphics.RectF rf = new android.graphics.RectF(x0, y0, x1, y1);   // round the photo corners
            android.graphics.Path p = new android.graphics.Path();
            p.addRect(x0, y0, x1, y1, android.graphics.Path.Direction.CW);
            p.addRoundRect(rf, sh, sh, android.graphics.Path.Direction.CW);
            p.setFillType(android.graphics.Path.FillType.EVEN_ODD);
            android.graphics.Paint wp = new android.graphics.Paint();
            wp.setColor(0xFFFFFFFF);
            cv.drawPath(p, wp);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(graded);
            ob.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            return graded.getAbsolutePath();
        } catch (Throwable t) {
            MainActivity.say("WB1 ex " + t);
            Engine.dbg("WB1 ex " + t);
            return graded.getAbsolutePath();
        } finally {
            if (ob != null) ob.recycle();
            if (src != null) src.recycle();
        }
    }

    /** WB2/WB3: a wide 2.7:1 photo carried by a 135 film strip. The strip keeps B2's physical scale
     *  — one 135 frame is U = 1.5 * photoHeight wide — so the wide frame spans several frame widths
     *  and the edge print (stock name / frame number / barcode) tiles once per frame width, the number
     *  advancing. No exposure data. fills=false -> WB2 (photo centred, black rebate, cream sprockets);
     *  fills=true -> WB3 (photo covers the whole strip, holes punched opaque black with normal alpha,
     *  the edge-print layer screen-blended over the photo). */
    /** true multiply that keeps destination alpha (W3C); API29 BlendMode via reflection, legacy fallback */
    private static void multiply(android.graphics.Paint p) {
        try {
            Class<?> bm = Class.forName("android.graphics.BlendMode");
            Object v = bm.getField("MULTIPLY").get(null);
            android.graphics.Paint.class.getMethod("setBlendMode", bm).invoke(p, v);
        } catch (Throwable t) {
            p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY));
        }
    }

    // perforation rects of the LAST film-edge render, so the surface post (scratch) can be masked out of the
    // sprocket holes — the holes are punched through the film, so no post-process may appear inside them.
    private static final java.util.List<android.graphics.RectF> g_holeRects = new java.util.ArrayList<android.graphics.RectF>();
    private static int g_holeW = 0, g_holeH = 0;
    private static void recordHoles(int oW, int oH, int count, float x0, float pitch, float hw, float hh, float inset, float top, float bot, int dx) {
        g_holeRects.clear(); g_holeW = oW; g_holeH = oH;
        for (int i = 0; i < count; i++) {
            float hx = x0 + i * pitch;
            g_holeRects.add(new android.graphics.RectF(hx + dx, top - inset, hx + hw + dx, top + hh - inset));
            g_holeRects.add(new android.graphics.RectF(hx + dx, bot - inset, hx + hw + dx, bot + hh - inset));
        }
    }

    /** screen the film scratch over a finished border image, then put the sprocket holes back so no scratch
     *  shows inside them (the holes are holes — the scratch is on the film surface around them). */
    private static String scratchOverBorder(java.io.File graded, String film, String scratch) {
        try {
            android.graphics.Bitmap pre = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());
            String sc = scratchColorized(graded, film, scratch);
            java.util.List<android.graphics.RectF> holes = new java.util.ArrayList<android.graphics.RectF>(g_holeRects);
            if (pre == null || holes.isEmpty()) { if (pre != null) pre.recycle(); return sc; }
            android.graphics.Bitmap post = android.graphics.BitmapFactory.decodeFile(sc);
            if (post == null || post.getWidth() != pre.getWidth() || post.getHeight() != pre.getHeight()) { pre.recycle(); if (post != null) post.recycle(); return sc; }
            android.graphics.Bitmap mut = post.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            android.graphics.Path path = new android.graphics.Path();
            for (android.graphics.RectF rf : holes) path.addRoundRect(rf, Math.max(1f, rf.width() * 0.2f), Math.max(1f, rf.height() * 0.2f), android.graphics.Path.Direction.CW);
            android.graphics.Canvas c = new android.graphics.Canvas(mut);
            c.save(); c.clipPath(path);
            c.drawBitmap(pre, 0, 0, new android.graphics.Paint());
            c.restore();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(sc);
            mut.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo); fo.close();
            pre.recycle(); post.recycle(); mut.recycle();
            return sc;
        } catch (Throwable t) { Engine.dbg("scratchOverBorder ex " + t); return graded.getAbsolutePath(); }
    }

    private static String filmEdgeWide(java.io.File graded, String film, boolean fills) {
        android.graphics.Bitmap src = null, ob = null, layer = null;
        try {
            src = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());
            if (src == null) return graded.getAbsolutePath();
            int Wp = src.getWidth(), Hp = src.getHeight();
            final float U = 1.5f * Hp;                       // one 135 frame width at the photo's height scale
            final int W = Wp, H = Hp;
            int rl = Math.round(U * 0.05f);
            int rb = Math.round(U * 0.16f);
            int CW = fills ? Wp : Wp + 2 * rl, CH = fills ? Hp : Hp + 2 * rb;   // WB3 keeps the photo's own 2.7:1 frame
            ob = android.graphics.Bitmap.createBitmap(CW, CH, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(ob);
            cv.drawColor(0xFF000000);                        // film rebate
            if (fills) {
                cv.drawBitmap(src, 0, 0, null);              // WB3: photo fills the strip 1:1, aspect preserved
            } else {
                cv.drawBitmap(src, rl, rb, null);
            }
            int mn = Math.min(W, H);
            int amp = Math.max(2, Math.round(mn / 750f));
            int fade = Math.max(1, Math.round(Math.max(2, mn / 300) * 0.375f));
            if (!fills) {                                     // soft dissolve at the photo boundaries (WB2 only)
                float[] wr = wavh5(W, amp, 0x0F6A1E5AL);
                android.graphics.Paint sp = new android.graphics.Paint();
                sp.setColor(0xFF000000);
                for (int x = 0; x < W; x += 2) {
                    float t = rb + wr[x];
                    sp.setShader(new android.graphics.LinearGradient(0, t, 0, t + fade, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                    cv.drawRect(x + rl, t - fade, Math.min(W, x + 2) + rl, t + fade, sp);
                    float b = rb + H + wr[x];
                    sp.setShader(new android.graphics.LinearGradient(0, b, 0, b - fade, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                    cv.drawRect(x + rl, b - fade, Math.min(W, x + 2) + rl, b + fade, sp);
                }
                float[] wv = wavh5(H, amp, 0x0F6A1E5AL);
                for (int y = 0; y < H; y += 2) {
                    float lf = wv[y];
                    sp.setShader(new android.graphics.LinearGradient(lf, 0, lf + fade, 0, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                    cv.drawRect(lf - fade + rl, rb + y, lf + fade + rl, rb + Math.min(H, y + 2), sp);
                    float rg = W + wv[y];
                    sp.setShader(new android.graphics.LinearGradient(rg, 0, rg - fade, 0, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                    cv.drawRect(rg - fade + rl, rb + y, rg + fade + rl, rb + Math.min(H, y + 2), sp);
                }
            }
            // ---- sprockets ----
            float pitch = U / 8f;
            float hw = U / 18.8f, hh = U / 13.46f;
            float rad = U / 70f, gap = U / 69f;
            float feather = U / 2400f + 3f, inset = feather * 0.4f;
            int n = Math.max(1, (int) Math.floor((CW - hw) / pitch) + 1);   // most holes that fit the full strip, none cut
            float x0 = (CW - (pitch * (n - 1) + hw)) / 2f;
            float hTopTop = rb - gap - hh + inset;
            float hBotTop = fills ? (H - rb + gap + inset) : (rb + H + gap + inset);   // WB3: bottom band sits 2rb higher
            if (fills) {                                      // WB3: black hole, soft edge bled OUTWARD onto the photo
                float ht = rb - gap - hh, hb = H - rb + gap; // nominal hole rects (no inset)
                recordHoles(CW, CH, n, x0, pitch, hw, hh, 0f, ht, hb, 0);   // for the post-process hole mask
                float hr = feather * 2.25f;                  // red-brown halo: radius x2.25
                float hblur = hr * 1.8f;                     // halo blur radius
                float gr = hblur / 3f;                       // glow radius = 1/3 the halo radius
                // BlurMaskFilter is ignored on a Paint that carries a blend mode, so bake the soft halo +
                // glow into an offscreen layer (plain SRC_OVER, where blur DOES apply), then multiply-blit it.
                android.graphics.Bitmap soft = android.graphics.Bitmap.createBitmap(CW, CH, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas sc = new android.graphics.Canvas(soft);
                android.graphics.Paint halo = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                halo.setColor(0x40FF8000);                   // halo color (255,128,0), opacity 0x40
                halo.setMaskFilter(new android.graphics.BlurMaskFilter(hblur, android.graphics.BlurMaskFilter.Blur.NORMAL));
                for (int i = 0; i < n; i++) {
                    float hx = x0 + i * pitch;
                    sc.drawRoundRect(new android.graphics.RectF(hx - hr, ht - hr, hx + hw + hr, ht + hh + hr), rad, rad, halo);
                    sc.drawRoundRect(new android.graphics.RectF(hx - hr, hb - hr, hx + hw + hr, hb + hh + hr), rad, rad, halo);
                }
                android.graphics.Paint glow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                glow.setColor(0x80000000);                   // OUTWARD soft glow: its OWN opacity (independent of the opaque core)
                glow.setMaskFilter(new android.graphics.BlurMaskFilter(gr, android.graphics.BlurMaskFilter.Blur.NORMAL));
                for (int i = 0; i < n; i++) {
                    float hx = x0 + i * pitch;
                    sc.drawRoundRect(new android.graphics.RectF(hx, ht, hx + hw, ht + hh), rad, rad, glow);
                    sc.drawRoundRect(new android.graphics.RectF(hx, hb, hx + hw, hb + hh), rad, rad, glow);
                }
                android.graphics.Paint blit = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                multiply(blit);
                cv.drawBitmap(soft, 0, 0, blit);
                soft.recycle();
                android.graphics.Paint core = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                core.setColor(0xFF000000);                   // opaque black core — the actual perforation
                multiply(core);
                for (int i = 0; i < n; i++) {
                    float hx = x0 + i * pitch;
                    cv.drawRoundRect(new android.graphics.RectF(hx, ht, hx + hw, ht + hh), rad, rad, core);
                    cv.drawRoundRect(new android.graphics.RectF(hx, hb, hx + hw, hb + hh), rad, rad, core);
                }
                android.graphics.Paint oh = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);   // outline offset 0px, line width unchanged
                oh.setColor(0x80000000);
                oh.setStyle(android.graphics.Paint.Style.STROKE);
                oh.setStrokeWidth(1f);
                multiply(oh);
                for (int i = 0; i < n; i++) {
                    float hx = x0 + i * pitch;
                    cv.drawRoundRect(new android.graphics.RectF(hx, ht, hx + hw, ht + hh), rad, rad, oh);
                    cv.drawRoundRect(new android.graphics.RectF(hx, hb, hx + hw, hb + hh), rad, rad, oh);
                }
            } else {                                         // WB2: B2's cream holes + red-brown halo
                recordHoles(CW, CH, n, x0, pitch, hw, hh, inset, hTopTop, hBotTop, 0);   // for the post-process hole mask
                android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                p.setColor(0xFFE8E4DC);                                  // cream hole (sharp)
                p.setMaskFilter(null);
                for (int i = 0; i < n; i++) {
                    float hx = x0 + i * pitch + inset;
                    cv.drawRoundRect(new android.graphics.RectF(hx, hTopTop, hx + hw - inset * 2, hTopTop + hh - inset * 2), rad, rad, p);
                    cv.drawRoundRect(new android.graphics.RectF(hx, hBotTop, hx + hw - inset * 2, hBotTop + hh - inset * 2), rad, rad, p);
                }
                p.setColor(0xA08A4224);                                  // red-brown halation bleeding INTO the bright hole
                p.setStyle(android.graphics.Paint.Style.STROKE);
                p.setStrokeWidth(feather * 3f);
                p.setMaskFilter(new android.graphics.BlurMaskFilter(feather * 2.7f, android.graphics.BlurMaskFilter.Blur.NORMAL));
                for (int i = 0; i < n; i++) {
                    float hx = x0 + i * pitch + inset;
                    cv.drawRoundRect(new android.graphics.RectF(hx, hTopTop, hx + hw - inset * 2, hTopTop + hh - inset * 2), rad, rad, p);
                    cv.drawRoundRect(new android.graphics.RectF(hx, hBotTop, hx + hw - inset * 2, hBotTop + hh - inset * 2), rad, rad, p);
                }
                android.graphics.Paint oh = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                oh.setColor(0x80000000);
                oh.setStyle(android.graphics.Paint.Style.STROKE);
                oh.setStrokeWidth(1f);
                for (int i = 0; i < n; i++) {
                    float hx = x0 + i * pitch + inset;
                    cv.drawRoundRect(new android.graphics.RectF(hx - 4f, hTopTop - 4f, hx + hw - inset * 2 + 4f, hTopTop + hh - inset * 2 + 4f), rad, rad, oh);
                    cv.drawRoundRect(new android.graphics.RectF(hx - 4f, hBotTop - 4f, hx + hw - inset * 2 + 4f, hBotTop + hh - inset * 2 + 4f), rad, rad, oh);
                }
            }
            // ---- edge-print layer: stock name + frame number + barcode, tiled once per 135 frame width ----
            layer = android.graphics.Bitmap.createBitmap(CW, CH, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas lc = new android.graphics.Canvas(layer);
            int nT = Math.max(1, Math.round(W / U));
            android.content.SharedPreferences pf = ctx.getSharedPreferences("of6k_b2", 0);
            int base = pf.getInt("frame", 0), lastN = base;
            String nm = (film == null ? "" : film.trim()).toUpperCase(java.util.Locale.US);
            float softR = U / 14400f + 0.25f, rimR = U / 300f;
            for (int t = 0; t < nT; t++) {
                int N = (base + t) % 36 + 1;
                lastN = N;
                float tile = rl + t * U;
                lc.save();
                lc.translate(tile, 0);
                if (nm.length() > 0) {                       // stock name, top-right of the frame
                    android.graphics.Paint np = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    np.setLetterSpacing(0.06f);
                    float nameX = (U * 0.92f) / 1.5f;
                    float nameY = (rb - gap - hh - 0.013f * U) / 1.5f;
                    android.graphics.Paint rim = new android.graphics.Paint(np);
                    rim.setColor(0xFFD8301A);
                    rim.setMaskFilter(new android.graphics.BlurMaskFilter(rimR, android.graphics.BlurMaskFilter.Blur.NORMAL));
                    np.setMaskFilter(new android.graphics.BlurMaskFilter(softR, android.graphics.BlurMaskFilter.Blur.NORMAL));
                    np.setColor(0xFFF0961E);
                    lc.save(); lc.scale(1.5f, 1.5f);
                    drawEdge(lc, nm, nameX, nameY, android.graphics.Paint.Align.RIGHT, rim, U / 34f);
                    drawEdge(lc, nm, nameX, nameY, android.graphics.Paint.Align.RIGHT, np, U / 34f);
                    lc.restore();
                }
                float holeBot = (fills ? H - rb + gap : rb + H + gap) + hh;   // WB3: bottom band 2rb higher
                float band = rb - gap - hh;
                float bTop = holeBot + 4f, bBot = holeBot + band * 0.80f + 4f;   // barcode top near the bottom holes' lower edge (+4px)
                android.graphics.Paint rimP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                rimP.setColor(0xFFD8301A);
                rimP.setMaskFilter(new android.graphics.BlurMaskFilter(rimR, android.graphics.BlurMaskFilter.Blur.NORMAL));
                android.graphics.Paint bodyP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                bodyP.setColor(0xFFF0961E);
                bodyP.setMaskFilter(new android.graphics.BlurMaskFilter(softR, android.graphics.BlurMaskFilter.Blur.NORMAL));
                float[] zc = {0.3146f * U, 0.8076f * U};
                float zhw = 0.050f * U;
                int[][] zcodes = { frameCode(N - 1, true), frameCode(N, false), frameCode(N, true) };
                float bcW = U * 0.24f;
                float[] bleft = { zc[0] - (zc[1] - zc[0]) + zhw - 12f, zc[0] + zhw - 12f, zc[1] + zhw - 12f };
                int[] ztop = new int[12];
                ztop[0] = 1; ztop[11] = 1;
                float zMid = (bTop + bBot) / 2f;
                android.graphics.Path zpath = new android.graphics.Path();
                for (int g = (t == 0 ? 0 : 1); g < 3; g++) {   // t>0: skip the "previous frame" barcode so adjacent tiles don't overlap
                    int[] zbot = new int[12];
                    zbot[0] = 1; zbot[11] = 1;
                    for (int i = 0; i < 10; i++) zbot[1 + i] = zcodes[g][i];
                    float cell = bcW / 12f;
                    float bx0 = bleft[g];
                    for (int i = 0; i < 12; i++) {
                        float x = bx0 + i * cell;
                        float tw = (ztop[i] == 1 ? cell : cell * 0.5f);
                        zpath.addRect(x, bTop, x + tw, zMid, android.graphics.Path.Direction.CW);
                        float bx = (zbot[i] == 1 ? x + cell * 0.5f : x);
                        zpath.addRect(bx, zMid, bx + cell * 0.5f, bBot, android.graphics.Path.Direction.CW);
                    }
                }
                lc.drawPath(zpath, rimP);
                lc.drawPath(zpath, bodyP);
                android.graphics.Paint nr = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                nr.setTypeface(Ux.archivo(ctx));
                nr.setTextSize(0.045f * U);
                nr.setTextAlign(android.graphics.Paint.Align.CENTER);
                nr.setColor(0xFFD8301A);
                nr.setMaskFilter(new android.graphics.BlurMaskFilter(rimR, android.graphics.BlurMaskFilter.Blur.NORMAL));
                android.graphics.Paint nb = new android.graphics.Paint(nr);
                nb.setColor(0xFFF0961E);
                nb.setMaskFilter(new android.graphics.BlurMaskFilter(softR, android.graphics.BlurMaskFilter.Blur.NORMAL));
                float numBase = holeBot + band * 0.72f;
                String[] labs = {String.valueOf(N), N + "A"};
                for (int s2 = 0; s2 < 2; s2++) {
                    drawEdge(lc, labs[s2], zc[s2], numBase, android.graphics.Paint.Align.CENTER, nr, 0.045f * U);
                    drawEdge(lc, labs[s2], zc[s2], numBase, android.graphics.Paint.Align.CENTER, nb, 0.045f * U);
                }
                float holeTop = rb - gap - hh;
                drawEdge(lc, String.valueOf(N), 0.3299f * U, holeTop - 0.013f * U, android.graphics.Paint.Align.CENTER, nr, 0.045f * U);
                drawEdge(lc, String.valueOf(N), 0.3299f * U, holeTop - 0.013f * U, android.graphics.Paint.Align.CENTER, nb, 0.045f * U);
                lc.restore();
            }
            pf.edit().putInt("frame", lastN).apply();
            grainLayer(layer, 45.0f, 3.0f);
            if (fills) {
                android.graphics.Paint scr = new android.graphics.Paint();
                scr.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
                cv.drawBitmap(layer, 0, 0, scr);
            } else {
                cv.drawBitmap(layer, 0, 0, null);
            }
            layer.recycle(); layer = null;
            if (!fills) {                                     // WB2: the film's LUT conditions the border only
                // border colour (lut + grade + hue) now applied by Engine.processColor in screenBorderLayer
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(graded);
            ob.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            ob.recycle(); ob = null;
            src.recycle(); src = null;
            return graded.getAbsolutePath();
        } catch (Throwable t) {
            MainActivity.say("WB ex " + t);
            Engine.dbg("WB ex " + t);
            return graded.getAbsolutePath();
        } finally {
            if (layer != null) layer.recycle();
            if (ob != null) ob.recycle();
            if (src != null) src.recycle();
        }
    }

    /** B2/WB2/WB3: place the (stamped) original on the final-resolution black canvas (photo centred, base margins black).
     *  The overlay then composites over the WHOLE canvas, and the pipeline runs on it — so the leak/dust reach the base. */
    private static java.io.File buildBorderCanvas(java.io.File photo, java.io.File wd, String name, java.util.Map<String,String> q, String photoPost) {
        try {
            android.graphics.Bitmap bm0 = android.graphics.BitmapFactory.decodeFile(photo.getAbsolutePath());
            if (bm0 == null) return photo;
            if (bm0.getHeight() > bm0.getWidth()) {   // portrait: rotate 90 CW (same geometry as the border renderer)
                android.graphics.Matrix rm = new android.graphics.Matrix(); rm.postRotate(90f);
                android.graphics.Bitmap rot = android.graphics.Bitmap.createBitmap(bm0, 0, 0, bm0.getWidth(), bm0.getHeight(), rm, true);
                bm0.recycle(); bm0 = rot;
            }
            boolean wide = q.get("wb2") != null || q.get("wb3") != null;   // WB family crops to 2.7:1 first
            boolean fills = q.get("wb3") != null;                          // WB3: photo fills the strip 1:1
            float target = wide ? 2.7f : 1.5f;
            int fw0 = bm0.getWidth(), fh0 = bm0.getHeight();
            int cw, ch;
            if (fw0 > fh0 * target) { ch = fh0; cw = Math.round(fh0 * target); }
            else { cw = fw0; ch = Math.round(fw0 / target); }
            android.graphics.Bitmap bm = (cw == fw0 && ch == fh0) ? bm0
                : android.graphics.Bitmap.createBitmap(bm0, (fw0 - cw) / 2, (fh0 - ch) / 2, cw, ch);
            // dust + veiling are lens imaging: bake them into the photo bitmap only, never the film base
            if (photoPost != null && !photoPost.isEmpty()) {
                try {
                    java.io.File tin = new java.io.File(wd, "dcin_" + name + ".jpg");
                    java.io.FileOutputStream f0 = new java.io.FileOutputStream(tin);
                    bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 96, f0); f0.close();
                    java.io.File tout = new java.io.File(wd, "dcout_" + name + ".jpg");
                    String rd = Engine.get().applyPost(tin.getAbsolutePath(), tout.getAbsolutePath(), photoPost);
                    android.graphics.Bitmap bd = android.graphics.BitmapFactory.decodeFile(rd);
                    if (bd != null) { if (bm != bm0) bm.recycle(); bm = bd; }
                    if (tin.exists()) tin.delete();
                    if (tout.exists()) tout.delete();
                } catch (Throwable tt) { Engine.dbg("dust-on-photo ex " + tt); }
            }
            int W = bm.getWidth(), H = bm.getHeight();
            int rl, rb, CW, CH, px, py;
            if (wide) {
                float U = 1.5f * H;
                rl = Math.round(U * 0.05f); rb = Math.round(U * 0.16f);
                CW = fills ? W : W + 2 * rl; CH = fills ? H : H + 2 * rb;
                px = fills ? 0 : rl; py = fills ? 0 : rb;
            } else {
                rb = Math.round(W * 0.16f); rl = Math.round(W * 0.05f);
                CW = W + 2 * rl; CH = H + 2 * rb; px = rl; py = rb;
            }
            android.graphics.Bitmap ob = android.graphics.Bitmap.createBitmap(CW, CH, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(ob);
            cv.drawColor(0xFF000000);
            cv.drawBitmap(bm, px, py, null);
            if (bm != bm0) bm.recycle();
            bm0.recycle();
            java.io.File out = new java.io.File(wd, "bc_" + name + ".jpg");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            ob.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo); fo.close(); ob.recycle();
            return out;
        } catch (Throwable t) { Engine.dbg("buildBorderCanvas ex " + t); return photo; }
    }

    /** B2/WB2/WB3: render the LUT-only film edge (via a black photo, so its region stays pure black = screen identity)
     *  and SCREEN it over the graded canvas — the leak/dust in A's margins then light the base + print, while the
     *  photo frame is untouched. */
    /** Scratch is film imaging: render the scratch layer on a black canvas, run it through the film's
     *  per-pixel COLOUR pipeline (lut / grade / hue), then screen the result onto the graded canvas.
     *  (No grain / vignette / glow / sharpen -- only the steps that change a single pixel's colour.) */
    private static String scratchColorized(java.io.File graded, String film, String scratch) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath(), o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return graded.getAbsolutePath();
            android.graphics.Bitmap black = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            new android.graphics.Canvas(black).drawColor(0xFF000000);
            java.io.File blackF = new java.io.File(workDir, "scrblk_" + graded.getName());
            java.io.FileOutputStream fo = new java.io.FileOutputStream(blackF);
            black.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo); fo.close(); black.recycle();
            // layer = scratch composited onto black
            java.io.File layer = new java.io.File(workDir, "scrlay_" + graded.getName());
            String l1 = Engine.get().applyPost(blackF.getAbsolutePath(), layer.getAbsolutePath(), scratch);
            blackF.delete();
            if (l1 == null || !new java.io.File(l1).exists()) return graded.getAbsolutePath();
            // film colour response (lut + grade + hue) on the layer only
            java.io.File layer2 = new java.io.File(workDir, "scrlay2_" + graded.getName());
            String l2 = Engine.get().processColor(l1, film, layer2.getAbsolutePath());
            java.io.File use = (l2 != null && new java.io.File(l2).exists()) ? layer2 : new java.io.File(l1);
            // screen onto the graded canvas
            android.graphics.Bitmap A = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());
            android.graphics.Bitmap B = android.graphics.BitmapFactory.decodeFile(use.getAbsolutePath());
            if (A == null || B == null) { if (A != null) A.recycle(); if (B != null) B.recycle(); return graded.getAbsolutePath(); }
            android.graphics.Bitmap ob = A.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas c = new android.graphics.Canvas(ob);
            android.graphics.Paint p = new android.graphics.Paint();
            p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
            c.drawBitmap(B, 0, 0, p);
            java.io.FileOutputStream fo2 = new java.io.FileOutputStream(graded);
            ob.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo2); fo2.close();
            A.recycle(); B.recycle(); ob.recycle();
            if (layer.exists()) layer.delete();
            if (layer2.exists()) layer2.delete();
            return graded.getAbsolutePath();
        } catch (Throwable t) { Engine.dbg("scratchColorized ex " + t); return graded.getAbsolutePath(); }
    }

    private static String screenBorderLayer(java.io.File gradedCanvas, java.io.File src, String film, java.util.Map<String,String> q) {
        g_holeRects.clear();   // filled by the edge renderer below; empty on failure = no masking
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeFile(src.getAbsolutePath(), o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return gradedCanvas.getAbsolutePath();
            android.graphics.Bitmap black = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            new android.graphics.Canvas(black).drawColor(0xFF000000);
            java.io.File blackF = new java.io.File(workDir, "blk_" + gradedCanvas.getName());
            java.io.FileOutputStream fo = new java.io.FileOutputStream(blackF);
            black.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo); fo.close(); black.recycle();
            String bp;
            if (q.get("wb2") != null || q.get("wb3") != null) {   // wide film edge: crop to 2.7:1 first, then the W renderer
                cropWide(blackF);
                bp = filmEdgeWide(blackF, film, q.get("wb3") != null);
            } else {
                bp = filmEdge(blackF, src, film);   // B2: border with a BLACK photo region; LUT applied to the base only
            }
            // the border print is film imaging: give it the film's per-pixel colour response (lut+grade+hue)
            {
                java.io.File bpg = new java.io.File(workDir, "bpg_" + gradedCanvas.getName());
                String bp2 = Engine.get().processColor(bp, film, bpg.getAbsolutePath());
                if (bp2 != null && new java.io.File(bp2).exists()) bp = bp2;
            }
            android.graphics.Bitmap A = android.graphics.BitmapFactory.decodeFile(gradedCanvas.getAbsolutePath());
            android.graphics.Bitmap B = android.graphics.BitmapFactory.decodeFile(bp);
            if (A == null || B == null) { if (A != null) A.recycle(); if (B != null) B.recycle(); if (blackF.exists()) blackF.delete(); return gradedCanvas.getAbsolutePath(); }
            android.graphics.Bitmap ob = A.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas c = new android.graphics.Canvas(ob);
            android.graphics.Paint p = new android.graphics.Paint();
            p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
            c.drawBitmap(B, 0, 0, p);
            java.io.FileOutputStream fo2 = new java.io.FileOutputStream(gradedCanvas);
            ob.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo2); fo2.close();
            A.recycle(); B.recycle(); ob.recycle();
            if (blackF.exists()) blackF.delete();
            return gradedCanvas.getAbsolutePath();
        } catch (Throwable t) { Engine.dbg("screenBorderLayer ex " + t); return gradedCanvas.getAbsolutePath(); }
    }

    private static String filmEdge(java.io.File graded, java.io.File src, String film) {
        try {
            android.graphics.Bitmap bm0 = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());
            if (bm0 == null) return graded.getAbsolutePath();
            // portrait shots: rotate 90 deg CW first, so the frame ends up landscape
            if (bm0.getHeight() > bm0.getWidth()) {
                android.graphics.Matrix rm = new android.graphics.Matrix();
                rm.postRotate(90f);
                android.graphics.Bitmap rot = android.graphics.Bitmap.createBitmap(bm0, 0, 0, bm0.getWidth(), bm0.getHeight(), rm, true);
                bm0.recycle();
                bm0 = rot;
            }
            // crop to the 135 frame ratio (36:24 = 3:2 landscape), centred
            int fw0 = bm0.getWidth(), fh0 = bm0.getHeight();
            float target = 1.5f;
            int cw, ch;
            if (fw0 > fh0 * target) { ch = fh0; cw = Math.round(fh0 * target); }
            else { cw = fw0; ch = Math.round(fw0 / target); }
            android.graphics.Bitmap bm = (cw == fw0 && ch == fh0) ? bm0
                : android.graphics.Bitmap.createBitmap(bm0, (fw0 - cw) / 2, (fh0 - ch) / 2, cw, ch);
            int W = bm.getWidth(), H = bm.getHeight();
            int rb = Math.round(W * 0.16f);   // rebate geometry scales with the FILM WIDTH (35mm), not the frame aspect
            int rl = Math.round(W * 0.05f);   // black frame-line border on the left and right
            android.graphics.Bitmap ob = android.graphics.Bitmap.createBitmap(W + rl * 2, H + rb * 2, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(ob);
            cv.drawColor(0xFF000000);                             // film rebate: near-black
            cv.translate(rl, 0);                                  // inset the frame; left/right black margins stay black
            cv.drawBitmap(bm, 0, rb, null);
            if (bm != bm0) bm.recycle();
            bm0.recycle();
            // soft dissolve across both photo boundaries (wavy, like a real scan's frame edge)
            int mn = Math.min(W, H);
            int amp = Math.max(2, Math.round(mn / 750f));    // B2: amplitude 1/5 of the half-frame edge
            int fade = Math.max(1, Math.round(Math.max(2, mn / 300) * 0.375f));   // fade = half-frame radius
            float[] wr = wavh5(W, amp, 0x0F6A1E5AL);
            android.graphics.Paint sp = new android.graphics.Paint();
            sp.setColor(0xFF000000);
            for (int x = 0; x < W; x += 2) {
                float t = rb + wr[x];
                sp.setShader(new android.graphics.LinearGradient(0, t, 0, t + fade, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                cv.drawRect(x, t - fade, Math.min(W, x + 2), t + fade, sp);
                float b = rb + H + wr[x];
                sp.setShader(new android.graphics.LinearGradient(0, b, 0, b - fade, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                cv.drawRect(x, b - fade, Math.min(W, x + 2), b + fade, sp);
            }
            float[] wv = wavh5(H, amp, 0x0F6A1E5AL);              // left/right edges: SAME amp/fade/seed
            for (int y = 0; y < H; y += 2) {
                float lf = wv[y];
                sp.setShader(new android.graphics.LinearGradient(lf, 0, lf + fade, 0, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                cv.drawRect(lf - fade, rb + y, lf + fade, rb + Math.min(H, y + 2), sp);
                float rg = W + wv[y];
                sp.setShader(new android.graphics.LinearGradient(rg, 0, rg - fade, 0, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
                cv.drawRect(rg - fade, rb + y, rg + fade, rb + Math.min(H, y + 2), sp);
            }
            // sprocket holes: 8 per film width; holes HUG the photo edge (tiny gap, as scanned) —
            // reference-measured: hole W/18.8 x W/13.46, pitch W/8, corner r ~W/70, gap W/69
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            float pitch = W / 8f;
            float hw = W / 18.8f, hh = W / 13.46f;
            float x0 = (W - (pitch * 7 + hw)) / 2f;
            float rad = W / 70f;
            float gap = W / 69f;
            float feather = W / 2400f + 3f;                       // sprocket soft edge back to the original radius (half of the 2x)
            float inset = feather * 0.4f;                         // keep the perceived hole size after feathering
            float hTopTop = rb - gap - hh + inset;                // top strip: holes sit just above the photo
            float hBotTop = rb + H + gap + inset;                 // bottom strip: holes just below the photo
            recordHoles(ob.getWidth(), ob.getHeight(), 8, x0, pitch, hw, hh, inset, hTopTop, hBotTop, rl);   // for the post-process hole mask
            // red-brown film-base halo bleeding around each perforation (drawn under the white hole)
            p.setColor(0xFFE8E4DC);                                  // cream hole (sharp)
            p.setMaskFilter(null);
            for (int i = 0; i < 8; i++) {
                float hx = x0 + i * pitch + inset;
                cv.drawRoundRect(new android.graphics.RectF(hx, hTopTop, hx + hw - inset * 2, hTopTop + hh - inset * 2), rad, rad, p);
                cv.drawRoundRect(new android.graphics.RectF(hx, hBotTop, hx + hw - inset * 2, hBotTop + hh - inset * 2), rad, rad, p);
            }
            p.setColor(0xA08A4224);                                  // red-brown halation bleeding INTO the bright hole
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(feather * 3f);
            p.setMaskFilter(new android.graphics.BlurMaskFilter(feather * 2.7f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            for (int i = 0; i < 8; i++) {
                float hx = x0 + i * pitch + inset;
                cv.drawRoundRect(new android.graphics.RectF(hx, hTopTop, hx + hw - inset * 2, hTopTop + hh - inset * 2), rad, rad, p);
                cv.drawRoundRect(new android.graphics.RectF(hx, hBotTop, hx + hw - inset * 2, hBotTop + hh - inset * 2), rad, rad, p);
            }
            android.graphics.Paint oh = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);   // 1px AA black outline on the hole edge
            oh.setColor(0x80000000);                              // 50% opacity
            oh.setStyle(android.graphics.Paint.Style.STROKE);
            oh.setStrokeWidth(1f);
            float ohX = 4f;                                       // outline offset outward 4px (line width unchanged)
            float ohDX = 0f, ohDY = 0f;                           // outline centered on the hole edge (no offset)
            for (int i = 0; i < 8; i++) {
                float hx = x0 + i * pitch + inset;
                cv.drawRoundRect(new android.graphics.RectF(hx - ohX + ohDX, hTopTop - ohX + ohDY, hx + hw - inset * 2 + ohX + ohDX, hTopTop + hh - inset * 2 + ohX + ohDY), rad, rad, oh);
                cv.drawRoundRect(new android.graphics.RectF(hx - ohX + ohDX, hBotTop - ohX + ohDY, hx + hw - inset * 2 + ohX + ohDX, hBotTop + hh - inset * 2 + ohX + ohDY), rad, rad, oh);
            }
            // ---- edge-print layer (name + code numbers + barcode) so GD-200 film grain can be
            //      applied to it independently of the photo ----
            android.graphics.Bitmap layer = android.graphics.Bitmap.createBitmap(ob.getWidth(), ob.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas lc = new android.graphics.Canvas(layer);
            lc.translate(2 * rl, 0);
            // ---- edge print part 1: stock name — Helvetica-like (Liberation Sans, OFL), pale
            //      yellow with a soft edge (sprocket-feather radius) and a red-brown rim; ONE copy,
            //      top-right corner of the top rebate ----
            String nm = (film == null ? "" : film.trim()).toUpperCase(java.util.Locale.US);
            if (nm.length() > 0) {
                android.graphics.Paint np = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                np.setLetterSpacing(0.06f);
                float nameX = (W * 0.92f) / 1.5f;                 // Hx1.5: right edge lands at 0.92W (char width matches the number code)
                float nameY = (rb - gap - hh - 0.013f * W) / 1.5f; // baseline aligned with the top frame number
                android.graphics.Paint rim = new android.graphics.Paint(np);   // red-brown rim: same spot, wider blur
                rim.setColor(0xFFD8301A);
                rim.setMaskFilter(new android.graphics.BlurMaskFilter(W / 300f, android.graphics.BlurMaskFilter.Blur.NORMAL));
                np.setMaskFilter(new android.graphics.BlurMaskFilter(W / 14400f + 0.25f, android.graphics.BlurMaskFilter.Blur.NORMAL));   // soft edge (half radius)
                np.setColor(0xFFF0961E);                          // orange
                lc.save(); lc.scale(1.5f, 1.5f);                  // uniform 1.5x (char width matches the number code)
                drawEdge(lc, nm, nameX, nameY, android.graphics.Paint.Align.RIGHT, rim, W / 34f);
                drawEdge(lc, nm, nameX, nameY, android.graphics.Paint.Align.RIGHT, np, W / 34f);
                lc.restore();
            }
            // ---- edge print part 2: camera data back — mode / shutter / aperture / EV as separate
            //      dot-matrix tokens in the gaps between the TOP holes, same look as DE/FE stamps ----
            String[] segs = readExposureSegments(src);
            if (segs != null) {
                // one uniform dot pitch for every token: the most constrained slot+token sets it
                java.util.ArrayList<String> toks = new java.util.ArrayList<String>();
                java.util.ArrayList<Float> cs = new java.util.ArrayList<Float>();
                java.util.ArrayList<Float> mws = new java.util.ArrayList<Float>();
                float gapMax = pitch - hw - W / 40f;
                if (segs[0] != null) { toks.add(segs[0]); cs.add(x0 + hw + (pitch - hw) / 2f); mws.add(pitch - hw - W / 40f); }
                for (int g = 0; g < 3; g++) if (segs[g + 1] != null) {
                    toks.add(segs[g + 1]);
                    cs.add(x0 + hw + (g + 1) * pitch + (pitch - hw) / 2f);   // start at the SECOND hole gap
                    mws.add(gapMax);
                }
                if (!toks.isEmpty()) {
                    float d = Float.MAX_VALUE;
                    for (int i = 0; i < toks.size(); i++) d = Math.min(d, mws.get(i) / (6 * toks.get(i).length() - 1));
                    d = Math.min(d, hh * 1.1f / 7f);
                    // watermark render, scaled to the dot pitch: glow pass (fill+stroke+halo) then dark edge
                    android.graphics.Paint glow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    glow.setColor(0xFFFF9500);
                    glow.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
                    glow.setStrokeWidth(d * 0.10f);
                    glow.setMaskFilter(new android.graphics.BlurMaskFilter(d * 0.40f, android.graphics.BlurMaskFilter.Blur.NORMAL));   // softer dot edges
                    glow.setShadowLayer(d * 1.80f, 0, 0, 0xFFFF3D00);   // bloom radius 2x; brightness 2x via double pass below
                    for (int i = 0; i < toks.size(); i++) {
                        drawDotToken(cv, toks.get(i), cs.get(i), hTopTop + hh - 3.5f * d, d, glow);   // bottom-aligned to the holes
                        drawDotToken(cv, toks.get(i), cs.get(i), hTopTop + hh - 3.5f * d, d, glow);   // 2nd pass: glow doubled
                    }
                }
            }
            // ---- edge print part 3: bottom edge frame code (our OWN bar-width code) + frame number,
            //      and the matching full-frame number on the top edge. Counter increments once per photo
            //      and wraps 1..36. Code = START(110), N(6), even-parity(1), STOP(011); bit 0 = narrow
            //      bar, bit 1 = wide bar; bars fill the bottom rebate with the two frame numbers in
            //      carved gaps. All carry the stock-name edge effect (red-brown rim + soft edge, half radius) ----
            {
                android.content.SharedPreferences pf = ctx.getSharedPreferences("of6k_b2", 0);
                int N = pf.getInt("frame", 0) + 1;
                if (N > 36) N = 1;
                pf.edit().putInt("frame", N).apply();
                float holeBot = rb + H + gap + hh;
                float band = rb - gap - hh;
                float bTop = holeBot + 4f, bBot = holeBot + band * 0.80f + 4f;   // barcode top near the bottom holes' lower edge (+4px)
                float softR = W / 14400f + 0.25f, rimR = W / 300f;
                android.graphics.Paint rimP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                rimP.setColor(0xFFD8301A);
                rimP.setMaskFilter(new android.graphics.BlurMaskFilter(rimR, android.graphics.BlurMaskFilter.Blur.NORMAL));
                android.graphics.Paint bodyP = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                bodyP.setColor(0xFFF0961E);
                bodyP.setMaskFilter(new android.graphics.BlurMaskFilter(softR, android.graphics.BlurMaskFilter.Blur.NORMAL));
                float[] zc = {0.3146f * W, 0.8076f * W};
                float zhw = 0.050f * W;
                float[][] zgaps = {
                    {W * 0.015f, zc[0] - zhw},
                    {zc[0] + zhw, zc[1] - zhw},
                    {zc[1] + zhw, W * 0.985f}
                };
                // left = previous frame (N-1) back half, middle = current frame N, right = current frame back half (N A)
                int[][] zcodes = { frameCode(N - 1, true), frameCode(N, false), frameCode(N, true) };
                float bcW = W * 0.24f;                               // FIXED width: all three identical (was 0.30W, now 4/5)
                float[] bleft = { zc[0] - (zc[1] - zc[0]) + zhw - 12f, zc[0] + zhw - 12f, zc[1] + zhw - 12f };   // all three shifted left 12px
                int[] ztop = new int[12];                            // top row FIXED: wide + 10 narrow + wide
                ztop[0] = 1; ztop[11] = 1;
                float zMid = (bTop + bBot) / 2f;                     // two rows, columns aligned
                android.graphics.Path zpath = new android.graphics.Path();
                for (int g = 0; g < 3; g++) {
                    int[] zbot = new int[12];                        // bottom row = the 10-bit code + wide guards
                    zbot[0] = 1; zbot[11] = 1;
                    for (int i = 0; i < 10; i++) zbot[1 + i] = zcodes[g][i];
                    float cell = bcW / 12f;
                    float bx0 = bleft[g];
                    for (int i = 0; i < 12; i++) {
                        float x = bx0 + i * cell;
                        float tw = (ztop[i] == 1 ? cell : cell * 0.5f);   // top row unchanged: wide / narrow
                        zpath.addRect(x, bTop, x + tw, zMid, android.graphics.Path.Direction.CW);
                        float bx = (zbot[i] == 1 ? x + cell * 0.5f : x);  // bottom row: a wide bar becomes [gap + narrow], same width
                        zpath.addRect(bx, zMid, bx + cell * 0.5f, bBot, android.graphics.Path.Direction.CW);
                    }
                }
                lc.save(); lc.clipRect(-2f * rl, 0, W, ob.getHeight());     // let the outer barcodes run off to the frame edges
                lc.drawPath(zpath, rimP);                            // same rim+soft edge as name/numbers
                lc.drawPath(zpath, bodyP);                           // (grain applied after, on the layer)
                lc.restore();
                android.graphics.Paint nr = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                nr.setTypeface(Ux.archivo(ctx));
                nr.setTextSize(0.045f * W);
                nr.setTextAlign(android.graphics.Paint.Align.CENTER);
                nr.setColor(0xFFD8301A);
                nr.setMaskFilter(new android.graphics.BlurMaskFilter(rimR, android.graphics.BlurMaskFilter.Blur.NORMAL));
                android.graphics.Paint nb = new android.graphics.Paint(nr);
                nb.setColor(0xFFF0961E);
                nb.setMaskFilter(new android.graphics.BlurMaskFilter(softR, android.graphics.BlurMaskFilter.Blur.NORMAL));
                float numBase = holeBot + band * 0.72f;
                String[] labs = {String.valueOf(N), N + "A"};
                for (int s2 = 0; s2 < 2; s2++) {
                    drawEdge(lc, labs[s2], zc[s2], numBase, android.graphics.Paint.Align.CENTER, nr, 0.045f * W);
                    drawEdge(lc, labs[s2], zc[s2], numBase, android.graphics.Paint.Align.CENTER, nb, 0.045f * W);
                }
                float holeTop = rb - gap - hh;
                drawEdge(lc, String.valueOf(N), 0.3299f * W, holeTop - 0.013f * W, android.graphics.Paint.Align.CENTER, nr, 0.045f * W);
                drawEdge(lc, String.valueOf(N), 0.3299f * W, holeTop - 0.013f * W, android.graphics.Paint.Align.CENTER, nb, 0.045f * W);
            }
            // ---- GD-200 film grain on the edge-print layer (mono, independent of the photo) ----
            grainLayer(layer, 45.0f, 3.0f);
            cv.drawBitmap(layer, -rl, 0, null);
            layer.recycle();
            // ---- the border colour (lut + grade + hue) is applied by Engine.processColor in screenBorderLayer ----
java.io.FileOutputStream fo = new java.io.FileOutputStream(graded);
            ob.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            ob.recycle();
            return graded.getAbsolutePath();
        } catch (Throwable t) {
            MainActivity.say("B2 ex " + t);
            Engine.dbg("B2 ex " + t);
            return graded.getAbsolutePath();
        }
    }

    /** output-level extras: white-card frame / 4-film collage / wide crop + wide frames */
    /** post-process: apply the selected categories to a rendered file IN PLACE (screen overlay, randomised).
     *  engine.applyPost returns the posted file; copy it back over {@code f} so callers keep one path. */
    /** comma-subset of a post spec, keeping only the named categories (order preserved) */
    static String postSubset(String post, String... cats) {
        if (post == null) return "";
        java.util.HashSet<String> want = new java.util.HashSet<String>(java.util.Arrays.asList(cats));
        StringBuilder sb = new StringBuilder();
        for (String c : post.split(",")) {
            c = c.trim();
            if (c.length() > 0 && want.contains(c)) { if (sb.length() > 0) sb.append(','); sb.append(c); }
        }
        return sb.toString();
    }

    /** B2 / WB2 / WB3 = film edge (rebate) borders: the overlay reaches the base beyond the photo */
    private static boolean isFilmBorder(java.util.Map<String,String> q) {
        // B2 / WB2 only: additive cream sprockets on a dark rebate (screen-safe).
        // WB3 carves BLACK holes into the photo -> handled by the direct filmEdgeWide path.
        return q.get("b2") != null || q.get("wb2") != null;
    }

    static java.io.File postApply(java.io.File f, String post) {
        if (post == null || post.trim().isEmpty() || f == null || !f.exists()) return f;
        try {
            java.io.File tmp = new java.io.File(workDir, "post_" + f.getName());
            String r = Engine.get().applyPost(f.getAbsolutePath(), tmp.getAbsolutePath(), post);
            java.io.File res = new java.io.File(r);
            if (res.exists() && !res.getAbsolutePath().equals(f.getAbsolutePath())) {
                java.io.FileInputStream in = new java.io.FileInputStream(res);
                java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                in.close(); out.close();
            }
            if (tmp.exists() && !tmp.getAbsolutePath().equals(f.getAbsolutePath())) tmp.delete();
        } catch (Throwable t) { Engine.dbg("postApply ex " + t); }
        return f;
    }

    private static String stamped(java.io.File graded, java.io.File src, String film, Map<String,String> q) {
        boolean pol = q.get("b") != null, col = q.get("x") != null, b2 = q.get("b2") != null;
        boolean wide = q.get("w") != null, wb1 = q.get("wb1") != null, wb2 = q.get("wb2") != null, wb3 = q.get("wb3") != null;
        if (!pol && !col && !b2 && !wide && !wb1 && !wb2 && !wb3) return graded.getAbsolutePath();
        try {
            if (wide || wb1 || wb2 || wb3) cropWide(graded);   // W family: crop to the wide 2.7:1 first
            if (wb1) return cardWide(graded);
            if (wb2) return filmEdgeWide(graded, film, false);
            if (wb3) return filmEdgeWide(graded, film, true);
            if (wide) return graded.getAbsolutePath();
            if (b2) return filmEdge(graded, src, film);
            if (col) return collage(graded, film);
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());
            if (bm == null) return graded.getAbsolutePath();
            if (!bm.isMutable()) bm = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true);   // Canvas needs mutable
            decorateCardFrame(bm, false);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(graded);
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            bm.recycle();
            return graded.getAbsolutePath();
        } catch (Throwable t) {
            MainActivity.say("stamp ex " + t);
            Engine.dbg("stamp ex " + t);
            return graded.getAbsolutePath();
        }
    }

    /** white-card/collage dressing, drawn on the FULL-SIZE graded image (no canvas expansion — the white
     *  frame bites into the photo edge): side=min/30, bottom bar=min/10, inner shadow+highlight per edge,
     *  rounded-corner mask painted white; quadrants adds the 2x2 cells and a white cross divider. */
    private static void decorateCardFrame(android.graphics.Bitmap raw, boolean quadrants) {
        int w = raw.getWidth(), h = raw.getHeight();
        int mn = Math.min(w, h);
        int side = Math.round(mn * 0.030f), bottomBar = Math.round(mn * 0.100f);
        int div = side;
        android.graphics.Canvas cv = new android.graphics.Canvas(raw);
        android.graphics.Paint wp = new android.graphics.Paint();
        wp.setColor(0xFFFFFFFF);
        int[][] cells;
        if (quadrants) {
            int xa = side, xb = w / 2 - div / 2, xc = w / 2 + div / 2, xd = w - side;
            int ya = side, yb = h / 2 - div / 2, yc = h / 2 + div / 2, yd = h - bottomBar;
            cells = new int[][]{ {xa, ya, xb, yb}, {xc, ya, xd, yb}, {xa, yc, xb, yd}, {xc, yc, xd, yd} };
        } else {
            cells = new int[][]{ {side, side, w - side, h - bottomBar} };
        }
        int sh = Math.max(2, mn / 300);
        android.graphics.Paint sp = new android.graphics.Paint();
        int dark = 0x59000000;
        for (int[] c : cells) {
            int cx0 = c[0], cy0 = c[1], cx1 = c[2], cy1 = c[3];
            if (cx1 - cx0 < 4 || cy1 - cy0 < 4) continue;
            sp.setShader(new android.graphics.LinearGradient(0, cy0, 0, cy0 + sh, dark, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(cx0, cy0, cx1, cy0 + sh, sp);
            sp.setShader(new android.graphics.LinearGradient(0, cy1 - sh, 0, cy1, 0x00000000, dark, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(cx0, cy1 - sh, cx1, cy1, sp);
            sp.setShader(new android.graphics.LinearGradient(cx0, 0, cx0 + sh, 0, dark, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(cx0, cy0, cx0 + sh, cy1, sp);
            sp.setShader(new android.graphics.LinearGradient(cx1 - sh, 0, cx1, 0, 0x00000000, dark, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(cx1 - sh, cy0, cx1, cy1, sp);
            int wl = Math.max(1, sh / 2);
            sp.setShader(new android.graphics.LinearGradient(0, cy0, 0, cy0 + wl, 0x99FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(cx0, cy0, cx1, cy0 + wl, sp);
            sp.setShader(new android.graphics.LinearGradient(0, cy1 - wl, 0, cy1, 0x00FFFFFF, 0x99FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(cx0, cy1 - wl, cx1, cy1, sp);
            sp.setShader(new android.graphics.LinearGradient(cx0, 0, cx0 + wl, 0, 0x99FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(cx0, cy0, cx0 + wl, cy1, sp);
            sp.setShader(new android.graphics.LinearGradient(cx1 - wl, 0, cx1, 0, 0x00FFFFFF, 0x99FFFFFF, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRect(cx1 - wl, cy0, cx1, cy1, sp);
        }
        android.graphics.RectF rf = new android.graphics.RectF();
        for (int[] c : cells) {
            int cx0 = c[0], cy0 = c[1], cx1 = c[2], cy1 = c[3];
            if (cx1 - cx0 < 4 || cy1 - cy0 < 4) continue;
            rf.set(cx0, cy0, cx1, cy1);
            android.graphics.Path p = new android.graphics.Path();
            p.addRect(cx0, cy0, cx1, cy1, android.graphics.Path.Direction.CW);
            p.addRoundRect(rf, sh, sh, android.graphics.Path.Direction.CW);
            p.setFillType(android.graphics.Path.FillType.EVEN_ODD);
            cv.drawPath(p, wp);
        }
        if (quadrants) {
            cv.drawRect(w / 2 - div / 2, 0, w / 2 + div / 2, h, wp);
            cv.drawRect(0, h / 2 - div / 2, w, h / 2 + div / 2, wp);
        }
        cv.drawRect(0, 0, w, side, wp);
        cv.drawRect(0, h - bottomBar, w, h, wp);
        cv.drawRect(0, 0, side, h, wp);
        cv.drawRect(w - side, 0, w, h, wp);
    }

    /** X mode WITH post: one photo graded under 4 films (selected + 3 random), EACH composited with its own
     *  overlay before its pipeline (all four treated equally); scratch is screened over the four cells; the
     *  card frame is drawn LAST and is unaffected. Returns the output path or null on failure. */
    private static String collagePost(java.io.File srcInput, String film, String leakdust, String scratch, java.io.File outFile) {
        try {
            java.util.List<String> all = Films.list();
            java.util.ArrayList<String> picks = new java.util.ArrayList<String>();
            java.util.Collections.shuffle(all, new java.util.Random());
            for (String f : all) { if (picks.size() >= 4) break; if (!f.startsWith("EDITTMP")) picks.add(f); }
            if (picks.size() < 2) return null;
            while (picks.size() < 4) picks.add(picks.get(picks.size() - 1));
            picks.set(0, film);
            int w = 0, h = 0;
            android.graphics.Bitmap[] cells = new android.graphics.Bitmap[4];
            for (int i = 0; i < 4; i++) {
                java.io.File input = srcInput;
                if (!leakdust.isEmpty()) {
                    java.io.File ov = new java.io.File(workDir, "xov_" + i + "_" + srcInput.getName());
                    String rr = Engine.get().applyPost(srcInput.getAbsolutePath(), ov.getAbsolutePath(), leakdust);
                    if (rr != null && !rr.equals(srcInput.getAbsolutePath()) && new java.io.File(rr).exists()) input = new java.io.File(rr);
                }
                java.io.File cf = new java.io.File(workDir, "xc_" + i + "_" + srcInput.getName());
                String r = Engine.get().process(input.getAbsolutePath(), picks.get(i), cf.getAbsolutePath());
                android.graphics.Bitmap b = (r == null || r.startsWith("ERR")) ? null : android.graphics.BitmapFactory.decodeFile(cf.getAbsolutePath());
                if (b != null) {
                    if (w == 0) { w = b.getWidth(); h = b.getHeight(); }
                    else if (b.getWidth() != w || b.getHeight() != h) b = android.graphics.Bitmap.createScaledBitmap(b, w, h, true);
                }
                cells[i] = b;
                if (cf.exists()) cf.delete();
            }
            if (w == 0) return null;
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(out);
            int wq = w / 2, hq = h / 2;
            int[] qx = { 0, wq, 0, wq }, qy = { 0, 0, hq, hq }, gw = { wq, w - wq, wq, w - wq }, gh = { hq, hq, h - hq, h - hq };
            for (int i = 0; i < 4; i++) {
                if (cells[i] == null) continue;
                cv.drawBitmap(cells[i], null, new android.graphics.RectF(qx[i], qy[i], qx[i] + gw[i], qy[i] + gh[i]), null);
                cells[i].recycle();
            }
            if (!scratch.isEmpty()) {   // surface overlay over the four cells, BEFORE the card frame
                java.io.File sf = new java.io.File(workDir, "xs_" + outFile.getName());
                java.io.FileOutputStream sfo = new java.io.FileOutputStream(sf);
                out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, sfo); sfo.close();
                java.io.File sf2 = new java.io.File(workDir, "xs2_" + outFile.getName());
                String sr = Engine.get().applyPost(sf.getAbsolutePath(), sf2.getAbsolutePath(), scratch);
                android.graphics.Bitmap sb = android.graphics.BitmapFactory.decodeFile(sr);
                if (sb != null) { out.recycle(); out = sb.copy(android.graphics.Bitmap.Config.ARGB_8888, true); sb.recycle(); cv = new android.graphics.Canvas(out); }
                sf.delete(); if (sf2.exists()) sf2.delete();
            }
            decorateCardFrame(out, true);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(outFile);
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo); fo.close();
            out.recycle();
            return outFile.getAbsolutePath();
        } catch (Throwable t) { MainActivity.say("collagePost ex " + t); return null; }
    }

    /** X mode: ONE photo graded with 4 RANDOM films (shuffled film list, tail-padded), stretched into
     *  2x2 quadrants of the full canvas (odd remainder kept right/bottom), then dressed as a quadrant white-card. */
    private static String collage(java.io.File graded, String film) {
        java.util.List<String> all = Films.list();
        java.util.ArrayList<String> picks = new java.util.ArrayList<String>();
        java.util.Collections.shuffle(all, new java.util.Random());
        for (String f : all) {
            if (picks.size() >= 4) break;
            if (!f.startsWith("EDITTMP")) picks.add(f);
        }
        if (picks.size() < 2) return graded.getAbsolutePath();
        while (picks.size() < 4) picks.add(picks.get(picks.size() - 1));
        java.io.File tmp = new java.io.File(workDir, "tmp_collage_" + graded.getName());
        try {
            int w = 0, h = 0;
            android.graphics.Bitmap[] cells = new android.graphics.Bitmap[4];
            for (int i = 0; i < 4; i++) {
                android.graphics.Bitmap b;
                if (i == 0) {
                    b = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());   // already graded with the selected film
                } else {
                    String r = Engine.get().process(graded.getAbsolutePath(), picks.get(i), tmp.getAbsolutePath());
                    if (r == null || r.startsWith("ERR")) { b = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath()); }
                    else b = android.graphics.BitmapFactory.decodeFile(tmp.getAbsolutePath());
                }
                if (b == null) continue;
                if (w == 0) { w = b.getWidth(); h = b.getHeight(); }
                else if (b.getWidth() != w || b.getHeight() != h) {
                    b = android.graphics.Bitmap.createScaledBitmap(b, w, h, true);
                }
                cells[i] = b;
            }
            if (w == 0) return graded.getAbsolutePath();
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(out);
            int wq = w / 2, hq = h / 2;
            int[] qx = { 0, wq, 0, wq };
            int[] qy = { 0, 0, hq, hq };
            int[] gw = { wq, w - wq, wq, w - wq };
            int[] gh = { hq, hq, h - hq, h - hq };
            for (int i = 0; i < 4; i++) {
                if (cells[i] == null) continue;
                cv.drawBitmap(cells[i], null,
                    new android.graphics.RectF(qx[i], qy[i], qx[i] + gw[i], qy[i] + gh[i]), null);   // stretch-fill each quadrant
                cells[i].recycle();
            }
            decorateCardFrame(out, true);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(graded);
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            out.recycle();
            return graded.getAbsolutePath();
        } catch (Throwable t) {
            MainActivity.say("collage ex " + t);
            return graded.getAbsolutePath();
        } finally {
            tmp.delete();
        }
    }
    private static java.io.File textStampOriginal(java.io.File src, Map<String,String> q) { return textStampOriginal(src, src, q); }
    private static java.io.File textStampOriginal(java.io.File photo, java.io.File exifSrc, Map<String,String> q) {
        String s1 = q.get("stamp"), s2 = q.get("stamp2");
        if (s1 == null && s2 == null) return photo;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(photo.getAbsolutePath(), o);
            if (bm == null) return photo;
            if (!bm.isMutable()) bm = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true);   // Canvas needs a mutable bitmap
            android.graphics.Canvas cv = new android.graphics.Canvas(bm);
            float ts = Math.max(17f, bm.getWidth() * 2 / 85f);   // 2/3 * 6/5 of the original
            float m = ts * 0.9f;
            if (s1 != null) {
                String t = s1.equals("@DATE") ? exifDate(exifSrc) : s1;
                drawSegTokens(cv, t, m, bm.getHeight() - m, false, ts);
            }
            if (s2 != null) drawSegTokens(cv, s2, bm.getWidth() - m, bm.getHeight() - m, true, ts);
            java.io.File tmp = new java.io.File(photo.getParentFile(), ".stamped_src.jpg");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 97, fo);
            fo.close();
            return tmp;
        } catch (Throwable t) {
            MainActivity.say("stamp pre ex " + t);
            return photo;
        }
    }

    /** output-level extras: white-card frame / 4-film collage (the text stamps were already burned pre-grade) */

    /** stamp glyph paints: orange-red italic DSEG14; the GLYPH itself is blurred, then a glow halo goes on top */
    private static android.graphics.Paint[] stampPaints(float ts) {
        android.graphics.Typeface tf = null;
        try { tf = Ux.seg14it(ctx); } catch (Throwable ig) {}
        if (tf == null) try { tf = Ux.seg14(ctx); } catch (Throwable ig) {}

        android.graphics.PorterDuffXfermode screen = new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN);

        android.graphics.Paint bloom = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        bloom.setColor(0x66FF4D00);                       // wide soft red bloom under everything
        if (tf != null) bloom.setTypeface(tf);
        bloom.setTextSize(ts);
        bloom.setMaskFilter(new android.graphics.BlurMaskFilter(ts * 0.11f, android.graphics.BlurMaskFilter.Blur.NORMAL));   // reduced bloom
        bloom.setXfermode(screen);

        android.graphics.Paint glyph = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        glyph.setColor(0xEEFF7A30);                       // THE glyph: bright red-orange, softly blurred edges
        if (tf != null) glyph.setTypeface(tf);
        glyph.setTextSize(ts);
        glyph.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
        glyph.setStrokeWidth(ts * 0.05f);
        glyph.setMaskFilter(new android.graphics.BlurMaskFilter(ts * 0.012f, android.graphics.BlurMaskFilter.Blur.NORMAL));   // crisp edges, minimal AA
        glyph.setXfermode(screen);

        // glow = stacked blurred glyphs (shadowLayer never renders on this path)
        android.graphics.Paint h2 = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        h2.setColor(0x99FFB060);                          // wide warm haze
        if (tf != null) h2.setTypeface(tf);
        h2.setTextSize(ts);
        h2.setMaskFilter(new android.graphics.BlurMaskFilter(ts * 0.6f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        h2.setXfermode(screen);

        android.graphics.Paint h1 = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        h1.setColor(0xAEFFA040);                          // tight bright halo
        if (tf != null) h1.setTypeface(tf);
        h1.setTextSize(ts);
        h1.setMaskFilter(new android.graphics.BlurMaskFilter(ts * 0.25f, android.graphics.BlurMaskFilter.Blur.NORMAL));
        h1.setXfermode(screen);

        return new android.graphics.Paint[]{bloom, h2, h1, glyph};   // order: bloom -> wide glow -> tight glow -> crisp glyph
    }

    /** draw one stamp token: blurred glyph, then glow */
    private static void drawStampToken(android.graphics.Canvas cv, String t, float x, float y, android.graphics.Paint[] ps) {
        for (android.graphics.Paint psp : ps) cv.drawText(t, x, y, psp);
    }

    /** draw a stamp string laid out by INK bounds, tokens separated by exactly one character width (DSEG "1" is right-aligned in its cell) */
    private static void drawSegTokens(android.graphics.Canvas cv, String text, float x, float y, boolean right, float ts) {
        String[] p = text.trim().split(" +");
        if (p.length == 0) return;
        android.graphics.Paint[] ps = stampPaints(ts);
        android.graphics.Paint core = ps[2];
        android.graphics.Rect b = new android.graphics.Rect();
        float cw = core.measureText("0");
        float[] w = new float[p.length], off = new float[p.length];
        float total = 0;
        for (int i = 0; i < p.length; i++) {
            core.getTextBounds(p[i], 0, p[i].length(), b);
            w[i] = b.width(); off[i] = b.left;
            total += w[i] + (i < p.length - 1 ? cw : 0);
        }
        float cx = right ? x - total : x;
        for (int i = 0; i < p.length; i++) {
            drawStampToken(cv, p[i], cx - off[i], y, ps);
            cx += w[i] + cw;
        }
    }

    private void respond(Socket s, String resp) throws Exception {        byte[] b = resp.getBytes();
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.0 200 OK\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes());
        os.write(b);
        os.flush();
    }
}
