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
        if (path.startsWith("/ingest")) {
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
                    for (String k : new String[]{"stamp", "stamp2", "b", "x"})
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
    private static android.graphics.Bitmap polaroid(android.graphics.Bitmap photo, String film) {
        int bw = Math.max(8, photo.getWidth() / 25);          // side/top border
        int bb = Math.max(bw * 3, photo.getWidth() / 8);      // bottom band
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                photo.getWidth() + 2 * bw, photo.getHeight() + bw + bb, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        cv.drawColor(0xFFF8F8F2);
        cv.drawBitmap(photo, bw, bw, null);
        android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFF2A2A2A);
        tp.setTextSize(bb * 0.38f);
        tp.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC));
        float tw = tp.measureText(film);
        cv.drawText(film, (out.getWidth() - tw) / 2f, bw + photo.getHeight() + bb * 0.62f, tp);
        return out;
    }

    /** 2x2 collage of the same photo under 4 films (requested first, then 3 others); no text */
    private static String collage(java.io.File graded, java.io.File src, String film) {
        java.util.List<String> all = Films.list();
        java.util.ArrayList<String> picks = new java.util.ArrayList<String>();
        if (all.contains(film)) picks.add(film);
        for (String f : all) {
            if (picks.size() >= 4) break;
            if (!f.equals(film) && !f.startsWith("EDITTMP")) picks.add(f);
        }
        if (picks.isEmpty()) return graded.getAbsolutePath();
        // scratch, NOT graded's folder: graded now lives in the gallery album, and a stray
        // tmp_collage.jpg would flash there as its own "photo". Prefixed with the graded name
        // so two concurrent ingests can't collide.
        java.io.File tmp = new java.io.File(workDir, "tmp_collage_" + graded.getName());
        java.util.ArrayList<android.graphics.Bitmap> cells = new java.util.ArrayList<android.graphics.Bitmap>();
        try {
            for (int i = 0; i < picks.size(); i++) {
                java.io.File in = i == 0 ? graded : tmp;
                if (i > 0) {
                    String r = Engine.get().process(src.getAbsolutePath(), picks.get(i), tmp.getAbsolutePath());
                    if (r == null || r.startsWith("ERR")) continue;
                }
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(in.getAbsolutePath());
                if (b != null) cells.add(b);
            }
            if (cells.size() < 2) return graded.getAbsolutePath();
            int cw = cells.get(0).getWidth(), ch = cells.get(0).getHeight();
            int gap = Math.max(6, cw / 90);
            int cols = cells.size() <= 2 ? cells.size() : 2;
            int rows = (cells.size() + cols - 1) / cols;
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                    cols * cw + (cols + 1) * gap, rows * ch + (rows + 1) * gap, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(out);
            cv.drawColor(0xFF101010);
            for (int i = 0; i < cells.size(); i++) {
                int cx = i % cols, cy = i / cols;
                // cells can differ in size slightly: fit into the first cell's slot
                android.graphics.Bitmap b = cells.get(i);
                float s = Math.min(cw / (float) b.getWidth(), ch / (float) b.getHeight());
                float dw = b.getWidth() * s, dh = b.getHeight() * s;
                android.graphics.RectF dst = new android.graphics.RectF(
                        gap + cx * (cw + gap) + (cw - dw) / 2f, gap + cy * (ch + gap) + (ch - dh) / 2f, 0, 0);
                dst.right = dst.left + dw; dst.bottom = dst.top + dh;
                cv.drawBitmap(b, null, dst, null);
            }
            java.io.FileOutputStream fo = new java.io.FileOutputStream(graded);
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            return graded.getAbsolutePath();
        } catch (Throwable t) {
            MainActivity.say("collage ex " + t);
            return graded.getAbsolutePath();
        } finally {
            tmp.delete();
        }
    }

    /** burn watermark(s) / polaroid / collage onto the graded output per the camera's params */
    /** screen-blend the TEXT stamps onto a copy of the ORIGINAL (before grading); returns a temp file, or src when nothing to do */
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
    private static String stamped(java.io.File graded, java.io.File src, String film, Map<String,String> q) {
        boolean pol = q.get("b") != null, col = q.get("x") != null;
        if (!pol && !col) return graded.getAbsolutePath();
        try {
            if (col) return collage(graded, src, film);
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(graded.getAbsolutePath());
            if (bm == null) return graded.getAbsolutePath();
            android.graphics.Bitmap out = polaroid(bm, film);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(graded);
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            return graded.getAbsolutePath();
        } catch (Throwable t) {
            MainActivity.say("stamp ex " + t);
            return graded.getAbsolutePath();
        }
    }

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
