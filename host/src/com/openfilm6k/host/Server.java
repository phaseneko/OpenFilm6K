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
            // camera upload: POST /ingest?film=&name=&orig=&len=[&stamp=..&stamp2=..&b=1&x=1], body = JPEG bytes.
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
                    for (String k : new String[]{"stamp", "stamp2", "b", "b2", "x"})
                        if (q.get(k) != null) extras.append(' ').append(k).append('=').append(q.get(k));
                    if (extras.length() > 0) MainActivity.say("ingest stamps:" + extras);
                    // grab the camera's EXIF (APP1) from the untouched upload BEFORE anything
                    // re-encodes it, then carry it onto the final graded JPEG.
                    byte[] exifApp1 = Exif.app1Of(src);
                    if (exifApp1 == null) Engine.dbg("ingest: source carries no APP1/EXIF: " + name);
                    // render order: watermark goes onto the ORIGINAL right after capture, BEFORE all our grading passes
                    java.io.File pipeIn = textStampOriginal(src, q);
                    // render straight into the gallery album: one output file, no second copy
                    galDir.mkdirs();
                    java.io.File out = new java.io.File(galDir, "graded_" + name);
                    String r = Engine.get().process(pipeIn.getAbsolutePath(), film, out.getAbsolutePath());
                    if (pipeIn != src) pipeIn.delete();
                    if (r != null && !r.startsWith("ERR") && new java.io.File(r).exists())
                        r = stamped(out, src, film, q);   // polaroid frame / collage stay output-level
                    // EXIF goes on last, so it survives the engine's encode AND the polaroid/collage frame
                    if (exifApp1 != null && r != null && !r.startsWith("ERR") && new java.io.File(r).exists())
                        Exif.carryBytes(out, exifApp1);
                    resp = r != null && r.startsWith("ERR") == false && new java.io.File(r).exists()
                            ? "OK " + out.getName() : "ERR render " + r;

                    // update the standing notification from the scan callback — the URI Film6K-style
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
            resp = r != null ? "OK " + r : "FAIL";
        } else resp = "?";
        } catch (Throwable t) { resp = "EX " + t; }
        respond(s, resp);
    }

    // ---- camera watermarking: text stamps / polaroid frame / 4-film collage ----

    /** stamp mode the camera last reported (0 none, 1 date, 2 expo, 3 date+expo, 4 polaroid, 5 collage, 6 film, 7 film+expo) */
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

    /** classic polaroid: white frame, wide bottom band carrying the film name */

    /** 2x2 collage of the same photo under 4 films (requested first, then 3 others); no text */

    /** burn watermark(s) / polaroid / collage onto the graded output per the camera's params */
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
        return ok ? "OK " + out.getName() : "ERR " + r;
    }

    /** H = half-frame, 2 = double-exposure. Dispatches the per-mode composition. */
    static String composePair(String mode, java.io.File g0, java.io.File g1, java.io.File out) {
        if ("half".equals(mode)) return composeHalf(g0, g1, out);
        return composeDouble(g0, g1, out);
    }

    /** 8-bit stacked-exposure LUT: each channel -> linear light, SUM the two exposures (the film
     *  plane integrates photons), each frame pulled to 0.8 (the standard per-shot compensation),
     *  then a tanh shoulder (knee 0.6) plays the negative's shoulder + paper latitude: highlight-
     *  over-midtone lands ~0.94 sRGB (the old 0.5 average capped it at ~0.79, washing both frames
     *  into midtones). 65536 entries = one array lookup per channel. */
    private static byte[] STACK_FT;
    private static synchronized byte[] stackTable() {
        if (STACK_FT == null) {
            float[] lin = new float[256];
            for (int i = 0; i < 256; i++) lin[i] = srgb2lin(i / 255f);
            byte[] t = new byte[65536];
            for (int a = 0; a < 256; a++)
                for (int b = 0; b < 256; b++) {
                    float s = (lin[a] + lin[b]) * 0.8f;              // film-plane sum, per-shot pull
                    float v = s <= 0.6f ? s : 0.6f + 0.4f * (float) Math.tanh((s - 0.6f) / 0.4f);
                    t[(a << 8) | b] = (byte) Math.round(lin2srgb(v) * 255f);
                }
            STACK_FT = t;
        }
        return STACK_FT;
    }
    private static float srgb2lin(float c) { return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4); }
    private static float lin2srgb(float c) { c = c < 0 ? 0 : c > 1 ? 1 : c; return c <= 0.0031308f ? c * 12.92f : (float) (1.055 * Math.pow(c, 1.0 / 2.4) - 0.055); }

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
     *  3:2 pure-black canvas, with outer margin AND the gap between them = half the polaroid
     *  narrow border (side/2). Each half gets the polaroid soft inner edge + small rounded corners.
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
            int side = Math.round(mn * 0.030f);                              // polaroid narrow border
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
    static String spyIngest(java.io.File src, String film, int mode) {
        try {
            java.util.HashMap<String,String> q = new java.util.HashMap<>();
            String name = src.getName();
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            if (mode == 4) q.put("b", "1");                       // B1: polaroid frame
            else if (mode == 10) q.put("b2", "1");                // B2: film edge, sprockets + stock name
            else if (mode == 5) q.put("x", "1");                  // 4-film collage
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
            byte[] exifApp1 = Exif.app1Of(src);
            java.io.File pipeIn = textStampOriginal(src, q);
            galDir.mkdirs();
            java.io.File out = new java.io.File(galDir, "graded_" + base + ".jpg");
            String r = Engine.get().process(pipeIn.getAbsolutePath(), film, out.getAbsolutePath());
            if (pipeIn != src) pipeIn.delete();
            if (r != null && !r.startsWith("ERR") && new java.io.File(r).exists())
                r = stamped(out, src, film, q);
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
        try {
            String mode = half ? "half" : "double";
            String name = src0.getName();
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            byte[] exifApp1 = Exif.app1Of(src0);
            galDir.mkdirs();
            java.io.File out = new java.io.File(galDir, "graded_" + base + "_pair.jpg");
            String r;
            if (!half) {
                java.io.File merged = new java.io.File(workDir, "m_" + base + ".jpg");
                String rm = mergeLight(src0, src1, merged);
                if (rm != null && !rm.startsWith("ERR") && merged.exists())
                    r = Engine.get().process(merged.getAbsolutePath(), film, out.getAbsolutePath());
                else r = "ERR merge " + rm;
                merged.delete();
            } else {
                java.io.File g0 = new java.io.File(workDir, "p0_" + base + ".jpg");
                java.io.File g1 = new java.io.File(workDir, "p1_" + base + ".jpg");
                String r0 = Engine.get().process(src0.getAbsolutePath(), film, g0.getAbsolutePath());
                String r1 = Engine.get().process(src1.getAbsolutePath(), film, g1.getAbsolutePath());
                if (r0 != null && !r0.startsWith("ERR") && g0.exists() && r1 != null && !r1.startsWith("ERR") && g1.exists())
                    r = composePair(mode, g0, g1, out);
                else r = "ERR render " + r0 + " / " + r1;
                g0.delete(); g1.delete();
            }
            if (exifApp1 != null && r != null && !r.startsWith("ERR") && out.exists())
                Exif.carryBytes(out, exifApp1);
            boolean ok = r != null && !r.startsWith("ERR") && out.exists();
            try {
                if (ok) {
                    final String filmF = film + " + " + film;
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
            return ok ? "OK " + out.getName() : "ERR " + r;
        } catch (Throwable t) { return "ERR " + t; }
    }

    /** B2 (film edge): the scanned-negative look — near-black rebate strips above and below the
     *  photo, a row of sprocket holes through each, and the film stock name printed MIRRORED in
     *  gold edge-print style (edge marks read reversed when the rebate is shot emulsion-side up),
     *  plus a direction arrow on the top strip. Rebate is 12% of the photo height per strip. */
    private static String filmEdge(java.io.File graded, String film) {
        try {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());
            if (bm == null) return graded.getAbsolutePath();
            if (!bm.isMutable()) bm = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            int W = bm.getWidth(), H = bm.getHeight();
            int rb = Math.round(H * 0.12f);
            android.graphics.Bitmap ob = android.graphics.Bitmap.createBitmap(W, H + rb * 2, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(ob);
            cv.drawColor(0xFF101010);                             // film rebate: near-black
            cv.drawBitmap(bm, 0, rb, null);
            bm.recycle();
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            // sprocket holes: 10 across, centered in each strip
            float holeW = W / 16f, holeH = rb * 0.5f, pitch = W / 10f;
            float x0 = (W - (pitch * 9 + holeW)) / 2f;
            p.setColor(0xFFE8E4DC);
            for (float top : new float[]{rb / 2f - holeH / 2f, rb + H + rb / 2f - holeH / 2f})
                for (int i = 0; i < 10; i++) {
                    float hx = x0 + i * pitch;
                    cv.drawRoundRect(new android.graphics.RectF(hx, top, hx + holeW, top + holeH), holeW * 0.18f, holeW * 0.18f, p);
                }
            // mirrored gold edge print (edge marks read reversed on a scanned rebate)
            String nm = (film == null ? "" : film.trim()).toUpperCase(java.util.Locale.US);
            if (nm.length() > 0) {
                p.setColor(0xFFC9A24B);
                p.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
                p.setTextSize(rb * 0.42f);
                p.setLetterSpacing(0.12f);
                float tw = p.measureText(nm);
                cv.save();
                cv.scale(-1f, 1f);
                cv.drawText(nm, -(W * 0.86f), rb + H + rb * 0.68f, p);      // bottom strip: stock name
                cv.restore();
                cv.save();
                cv.scale(-1f, 1f);
                cv.drawText(nm, -(W * 0.14f + tw), rb * 0.68f, p);          // top strip: stock name
                cv.restore();
            }
            // direction arrow, gold, top strip
            p.setColor(0xFFC9A24B);
            float ay = rb * 0.5f, ax1 = W * 0.62f, ax2 = W * 0.75f;
            cv.drawRect(ax1, ay - 2, ax2, ay + 2, p);
            android.graphics.Path tri = new android.graphics.Path();
            tri.moveTo(ax1, ay); tri.lineTo(ax1 + 14, ay - 9); tri.lineTo(ax1 + 14, ay + 9);
            tri.close();
            cv.drawPath(tri, p);
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

    /** output-level extras: polaroid frame / 4-film collage dressing on the graded image */
    private static String stamped(java.io.File graded, java.io.File src, String film, Map<String,String> q) {
        boolean pol = q.get("b") != null, col = q.get("x") != null, b2 = q.get("b2") != null;
        if (!pol && !col && !b2) return graded.getAbsolutePath();
        try {
            if (b2) return filmEdge(graded, film);
            if (col) return collage(graded, film);
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());
            if (bm == null) return graded.getAbsolutePath();
            if (!bm.isMutable()) bm = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true);   // Canvas needs mutable
            decoratePolaroid(bm, false);
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

    /** polaroid/collage dressing, drawn on the FULL-SIZE graded image (no canvas expansion — the white
     *  frame bites into the photo edge): side=min/30, bottom bar=min/10, inner shadow+highlight per edge,
     *  rounded-corner mask painted white; quadrants adds the 2x2 cells and a white cross divider. */
    private static void decoratePolaroid(android.graphics.Bitmap raw, boolean quadrants) {
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

    /** X mode: ONE photo graded with 4 RANDOM films (shuffled film list, tail-padded), stretched into
     *  2x2 quadrants of the full canvas (odd remainder kept right/bottom), then dressed as a quadrant polaroid. */
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
            decoratePolaroid(out, true);
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
    private static java.io.File textStampOriginal(java.io.File src, Map<String,String> q) {
        String s1 = q.get("stamp"), s2 = q.get("stamp2");
        if (s1 == null && s2 == null) return src;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(src.getAbsolutePath(), o);
            if (bm == null) return src;
            if (!bm.isMutable()) bm = bm.copy(android.graphics.Bitmap.Config.ARGB_8888, true);   // Canvas needs a mutable bitmap
            android.graphics.Canvas cv = new android.graphics.Canvas(bm);
            float ts = Math.max(17f, bm.getWidth() * 2 / 85f);   // 2/3 * 6/5 of the original
            float m = ts * 0.9f;
            if (s1 != null) {
                String t = s1.equals("@DATE") ? exifDate(src) : s1;
                drawSegTokens(cv, t, m, bm.getHeight() - m, false, ts);
            }
            if (s2 != null) drawSegTokens(cv, s2, bm.getWidth() - m, bm.getHeight() - m, true, ts);
            java.io.File tmp = new java.io.File(src.getParentFile(), ".stamped_src.jpg");
            java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
            bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 97, fo);
            fo.close();
            return tmp;
        } catch (Throwable t) {
            MainActivity.say("stamp pre ex " + t);
            return src;
        }
    }

    /** output-level extras: polaroid frame / 4-film collage (the text stamps were already burned pre-grade) */

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
