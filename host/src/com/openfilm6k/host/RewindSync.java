package com.openfilm6k.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Proxy;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewind mode: push the three OpenFilm6K film names + neutral camera params to the PS135,
 *  upload the combo config, and pull new Original_Film shots into the normal ingest pipeline.
 *
 *  The camera is an HTTP server (the official app is its client), so this class is the client.
 *  It serializes every camera request with a small gap: the camera is single-client and wedges
 *  under concurrent requests. */
public class RewindSync {
    private static final int GAP_MS = 120;
    private static final String NEUTRAL = "0:0:0:0:0:0:0";
    private static final RewindSync I = new RewindSync();

    private static volatile boolean up;
    private static volatile boolean force;
    private static volatile String camIp;
    private static volatile String status = "";
    private static volatile long lastSync;
    private static volatile int lastPulled;
    private static volatile boolean baselineThisRun;
    private static volatile long beaconAt;
    private static volatile long lastCfgPushAt;
    private static volatile int progressDone, progressTotal;
    private static volatile String progressName = "";

    private Thread thread;
    private static Context ctx;

    private RewindSync() {}

    static synchronized void sync(Context c) {
        if (ctx == null) ctx = c.getApplicationContext();
        boolean want = prefs().getBoolean("rewind_on", false);
        if (want && !up) start();
        else if (!want && up) stop();
    }

    /** manual one-shot (the dialog's Sync now button); works even if the master switch is off */
    static synchronized void requestSync(Context c) {
        if (ctx == null) ctx = c.getApplicationContext();
        force = true;
        if (!up) start();
    }

    /** camera registered itself over UDP; remember its source IP immediately */
    static void noteBeacon(Context c, String ip, String payload) {
        if (ctx == null) ctx = c.getApplicationContext();
        if (ip == null || ip.length() == 0) return;
        camIp = ip;
        beaconAt = SystemClock.elapsedRealtime();
        prefs().edit().putString("rewind_cam_ip", ip).commit();
        status = "beacon " + ip + (payload == null ? "" : " " + payload);
    }

    static void noteBeaconError(Context c, String err) {
        if (ctx == null) ctx = c.getApplicationContext();
        status = "beacon ERR " + err;
    }

    static String statusText() {
        String cam = camIp != null ? camIp : L.s("rewindNotFound");
        String t = lastSync > 0 ? new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(lastSync)) : "-";
        return L.s("rewindCamera") + ": " + cam + "\n"
                + L.s("rewindStatus") + ": " + status + "\n"
                + L.s("rewindLast") + ": " + t + "   " + L.s("rewindPulled") + ": " + lastPulled;
    }

    private static SharedPreferences prefs() {
        return ctx.getSharedPreferences("of6k", 0);
    }

    private static synchronized void start() {
        if (up) return;
        up = true;
        I.thread = new Thread(new Runnable() { public void run() { I.loop(); } }, "rewind-sync");
        I.thread.setDaemon(true);
        I.thread.start();
    }

    private static synchronized void stop() {
        up = false;
        if (I.thread != null) I.thread.interrupt();
    }

    private void loop() {
        while (up) {
            boolean on = prefs().getBoolean("rewind_on", false);
            if (!on && !force) { up = false; break; }
            force = false;
            try {
                Server.get().start(ctx);   // idempotent; ensures the ingest pipeline is alive
                doSync();
            } catch (Throwable t) {
                status = "ERR " + t;
            }
            if (!prefs().getBoolean("rewind_on", false)) { up = false; break; }
            try { Thread.sleep(8000); } catch (InterruptedException e) { break; }
        }
        up = false;
    }

    private void doSync() {
        status = L.s("rewindPulling");
        String ip = findCamera();
        if (ip == null) {
            camIp = null;
            status = L.s("rewindNotFound");
            return;
        }
        camIp = ip;
        lastPulled = 0;
        baselineThisRun = false;
        try { pushIfNeeded(ip); } catch (Throwable t) { status = "push ERR " + t; }
        try { pull(ip); } catch (Throwable t) { status = "pull ERR " + t; }
        lastSync = System.currentTimeMillis();
        if (!baselineThisRun && lastPulled == 0 && !status.startsWith("pull ERR")) status = L.s("rewindPulled") + "0";
    }

    /** one-line camera/status summary for the Rewind dialog */
    static String statusLine() {
        String cam = camIp != null ? camIp : L.s("rewindNotFound");
        String st = status == null ? "" : status.replace('\n', ' ').replace('\r', ' ');
        return L.s("rewindCamera") + ": " + cam + "   " + L.s("rewindStatus") + ": " + st;
    }

    static int[] progress() {
        return new int[]{progressDone, progressTotal};
    }

    static String progressName() { return progressName == null ? "" : progressName; }

    // ---------------------------------------------------------------- camera discovery

    /** UDP-only discovery: accept only a fresh PS135 beacon, never actively scan. */
    private String findCamera() {
        if (!up) return null;
        long now = SystemClock.elapsedRealtime();
        if (camIp != null && beaconAt > 0 && now - beaconAt <= 15000L) return camIp;
        status = L.s("rewindSearching") + " (UDP)";
        return null;
    }

    // ---------------------------------------------------------------- push config

    private void pushIfNeeded(String ip) throws Exception {
        String cfg = buildCfg();
        String hash = Integer.toHexString(cfg.hashCode());
        SharedPreferences pf = prefs();
        long now = System.currentTimeMillis();
        boolean need = !hash.equals(pf.getString("rewind_cfg_hash", ""))
                || !ip.equals(pf.getString("rewind_cfg_ip", ""))
                || now - lastCfgPushAt > 30000L;
        if (now - pf.getLong("rewind_clock_at", 0) > 3600000L) {
            try {
                String d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(now));
                String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now));
                camGet(ip, 3005, null, d, 2000, 3000);
                camGet(ip, 3006, null, t, 2000, 3000);
                pf.edit().putLong("rewind_clock_at", now).commit();
            } catch (Throwable t) { status = "clock ERR " + t; }
        }
        if (!need) return;

        status = L.s("rewindPushing");
        String[] labels = labels();
        camGet(ip, 3001, "2", null, 2000, 3000);
        camGet(ip, 3001, "0", null, 2000, 3000);
        camGet(ip, 8002, null, labels[0] + ":" + labels[1] + ":" + labels[2], 2000, 3000);
        for (int i = 0; i < 3; i++) camGet(ip, 8012 + i * 2, null, NEUTRAL, 2000, 3000);
        for (int i = 0; i < 3; i++) camGet(ip, 8006 + i * 2, null, NEUTRAL, 2000, 3000);
        camGet(ip, 3001, "0", null, 2000, 3000);
        uploadCfg(ip, cfg);
        lastCfgPushAt = now;
        pf.edit().putString("rewind_cfg_hash", hash).putString("rewind_cfg_ip", ip).commit();
    }

    /** label sent to the camera: slot-film-border, e.g. C1-LK-200-WB2 */
    static String slotLabel(int idx) {
        int n = idx + 1;
        SharedPreferences pf = prefs();
        String film = pf.getString("rewind_film" + n, "");
        if (film == null || film.trim().length() == 0) film = "FILM" + n;
        String border = borderCode(pf.getInt("rewind_stamp" + n, idx));
        String s = "C" + n + "-" + film + "-" + border;
        s = s.toUpperCase(Locale.US).replaceAll("[^A-Z0-9_. -]", "-").trim();
        if (s.length() > 40) s = s.substring(0, 40);
        return s;
    }

    private static String borderCode(int stamp) {
        String[] st = {"N", "D", "E", "DE", "B1", "B2", "F", "FE", "H", "2", "X", "W", "WB1", "WB2", "WB3"};
        return stamp >= 0 && stamp < st.length ? st[stamp] : "N";
    }

    private static int slotFromLabel(String label) {
        if (label == null) return -1;
        String s = label.trim().toUpperCase(Locale.US);
        if (s.length() >= 2 && s.charAt(0) == 'C'
                && s.charAt(1) >= '1' && s.charAt(1) <= '3') {
            return s.charAt(1) - '1';
        }
        return -1;
    }

    private static String[] labels() {
        String[] out = new String[3];
        for (int i = 0; i < 3; i++) out[i] = slotLabel(i);
        return out;
    }

    private static String[] films() {
        SharedPreferences pf = prefs();
        String[] out = new String[3];
        List<String> all = null;
        try { all = Films.listUserFirst(); } catch (Throwable ig) {}
        for (int i = 0; i < 3; i++) {
            out[i] = pf.getString("rewind_film" + (i + 1), "");
            if (out[i] == null || out[i].length() == 0) {
                if (all != null && i < all.size()) out[i] = all.get(i);
                else out[i] = "";
            }
        }
        return out;
    }

    private static String buildCfg() {
        String[] lab = labels(), fil = films();
        SharedPreferences pf = prefs();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 3; i++) b.append("F").append(i + 1).append("=").append(lab[i]).append('\n');
        for (int i = 0; i < 3; i++) b.append("N").append(i + 1).append("=").append(oneLine(fil[i])).append('\n');
        for (int i = 0; i < 3; i++) {
            String c = pf.getString("rewind_combo_label" + (i + 1), "");
            if (c == null || c.length() == 0) c = "C" + (i + 1);
            b.append("C").append(i + 1).append("=").append(c.toUpperCase(Locale.US).replaceAll("[^A-Z0-9_. +-]", "-")).append('\n');
            b.append("S").append(i + 1).append("=").append(pf.getInt("rewind_stamp" + (i + 1), 1)).append('\n');
            b.append("P").append(i + 1).append("=").append(oneLine(pf.getString("rewind_post" + (i + 1), ""))).append('\n');
        }
        return b.toString();
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').replace('=', '-');
    }

    // ---------------------------------------------------------------- pull photos

    private void pull(String ip) throws Exception {
        String xml = camGet(ip, 3015, null, null, 3000, 10000);
        if (xml == null) return;
        Set<String> seen = new HashSet<String>(prefs().getStringSet("rewind_seen", new HashSet<String>()));
        File dir = new File("/sdcard/OpenFilm6K/rewind");
        dir.mkdirs();
        Matcher m = Pattern.compile("<File>(.*?)</File>", Pattern.DOTALL).matcher(xml);
        if (!prefs().getBoolean("rewind_baseline", false)) {
            int n = 0;
            while (m.find()) {
                String block = m.group(1);
                String fpath = tag(block, "FPATH");
                String name = tag(block, "NAME");
                if (fpath == null || name == null) continue;
                if (!"Original_Film".equals(folderOf(fpath))) continue;
                String un = name.toUpperCase(Locale.US);
                if (!un.endsWith(".JPG") && !un.endsWith(".JPEG")) continue;
                if (seen.add(fpath)) n++;
            }
            prefs().edit().putStringSet("rewind_seen", seen).putBoolean("rewind_baseline", true).commit();
            baselineThisRun = true;
            status = L.s("rewindBaseline") + " " + n;
            return;
        }
        progressDone = 0;
        progressTotal = 0;
        progressName = "";
        while (m.find()) {
            String block = m.group(1);
            String fpath = tag(block, "FPATH");
            String name = tag(block, "NAME");
            if (fpath == null || name == null) continue;
            if (!isPullFolder(fpath)) continue;
            String un = name.toUpperCase(Locale.US);
            if (!un.endsWith(".JPG") && !un.endsWith(".JPEG")) continue;
            if (seen.contains(fpath)) continue;
            progressTotal++;
            progressName = name;
            File dest = new File(dir, name);
            try {
                download(ip, fpath, dest);
                if (dest.length() == 0) { dest.delete(); continue; }
                String film = filmForFile(name);
                int combo = comboForFile(ip, name);
                int stamp = prefs().getInt("rewind_stamp" + combo, 1);
                String post = prefs().getString("rewind_post" + combo, "");
                String r = Server.spyIngest(dest, film, stamp, post);
                if (r != null && r.startsWith("OK")) {
                    seen.add(fpath);
                    lastPulled++;
                    progressDone++;
                    status = L.s("rewindPulled") + lastPulled + " " + name;
                    dest.delete();
                } else {
                    status = "render ERR " + r;
                }
            } catch (Throwable t) {
                status = "ERR " + name + ": " + t;
            }
        }
        prefs().edit().putStringSet("rewind_seen", seen).commit();
    }

    private static boolean isPullFolder(String fpath) {
        return "Original_Film".equals(folderOf(fpath));
    }

    /** user-initiated "pull everything": keep the baseline flag but forget which paths were synced */
    static synchronized void resetSeen(Context c) {
        if (ctx == null) ctx = c.getApplicationContext();
        prefs().edit().putBoolean("rewind_baseline", true)
                .putStringSet("rewind_seen", new HashSet<String>()).commit();
        force = true;
        if (!up) start();
    }

    private static String folderOf(String fpath) {
        String p = fpath.replace('\\', '/');
        int a = p.lastIndexOf('/');
        if (a < 0) return "";
        String head = p.substring(0, a);
        int b = head.lastIndexOf('/');
        return b < 0 ? head : head.substring(b + 1);
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(xml);
        return m.find() ? unescape(m.group(1)).trim() : null;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String filmForFile(String name) {
        String label = labelFromName(name);
        String[] labs = labels(), fil = films();
        for (int i = 0; i < 3; i++) {
            if (label.equalsIgnoreCase(labs[i])) return fil[i];
        }
        int sk = slotFromLabel(label);
        if (sk >= 0) return fil[sk];
        // the in-camera bank has fixed names; the slot order is C1/C2/C3
        String lb = label.toUpperCase(Locale.US).replace(' ', '-');
        if (lb.equals("SUNNY-WARM")) return fil[0];
        if (lb.equals("BREEZY-COOL")) return fil[1];
        if (lb.equals("SIMPLY-MONO")) return fil[2];
        return fil[0];
    }

    private static String labelFromName(String name) {
        Matcher m = Pattern.compile("^DCIM\\d{8}(.*)_\\d{4}\\.(?:JPG|JPEG)$", Pattern.CASE_INSENSITIVE).matcher(name);
        return m.find() ? m.group(1).trim() : "";
    }

    /** per-shot sidecar written by the patched firmware: DCIM/R<counter>.TXT with combo=N */
    private static int comboForFile(String ip, String name) {
        int fromName = slotFromLabel(labelFromName(name));
        if (fromName >= 0) return fromName + 1;
        Matcher m = Pattern.compile("_(\\d{4})\\.(?:JPG|JPEG)$", Pattern.CASE_INSENSITIVE).matcher(name);
        if (!m.find()) return 1;
        String path = "A:\\DCIM\\R" + m.group(1) + ".TXT";
        try {
            String s = downloadString(ip, path, 1500, 2500);
            if (s != null) {
                Matcher c = Pattern.compile("combo\\s*=\\s*(\\d+)").matcher(s);
                if (c.find()) {
                    int n = Integer.parseInt(c.group(1));
                    if (n >= 1 && n <= 3) return n;
                }
            }
        } catch (Throwable ig) {}
        // first firmware build: the M handler rewrites DCIM/COMBO.TXT with the current index
        try {
            String s = downloadString(ip, "A:\\DCIM\\COMBO.TXT", 1500, 2500);
            if (s != null) {
                Matcher c = Pattern.compile("(\\d)").matcher(s);
                if (c.find()) {
                    int n = Integer.parseInt(c.group(1));
                    if (n >= 1 && n <= 3) return n;
                }
            }
        } catch (Throwable ig) {}
        return 1;
    }

    // ---------------------------------------------------------------- HTTP helpers
    // Raw sockets, not HttpURLConnection. Android marks Java HTTP sockets with the app's
    // default network (home Wi-Fi / VPN); that per-network table has no route to the
    // hotspot's 10.105.34.0/24. An unmarked raw socket follows the main table (ap0),
    // exactly like Termux's native curl — the only path from this app to a client of
    // its own hotspot.

    private static byte[] httpBody(String ip, String path, byte[] body, String contentType,
                                   int ct, int rt) throws Exception {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(ip, 80), ct);
            s.setSoTimeout(rt);
            java.io.OutputStream o = s.getOutputStream();
            StringBuilder h = new StringBuilder();
            h.append(body == null ? "GET " : "POST ").append(path).append(" HTTP/1.0\r\n");
            h.append("Host: ").append(ip).append("\r\n");
            h.append("Connection: close\r\n");
            if (body != null) {
                h.append("Content-Type: ").append(contentType).append("\r\n");
                h.append("Content-Length: ").append(body.length).append("\r\n");
            }
            h.append("\r\n");
            o.write(h.toString().getBytes("UTF-8"));
            if (body != null) o.write(body);
            o.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] all = bos.toByteArray();
            int sep = -1;
            for (int i = 0; i + 3 < all.length; i++) {
                if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') { sep = i; break; }
            }
            if (sep < 0) return null;
            String head = new String(all, 0, sep, "ISO-8859-1");
            if (!head.startsWith("HTTP/") || head.indexOf(" 200") < 0) return null;
            byte[] out = new byte[all.length - sep - 4];
            System.arraycopy(all, sep + 4, out, 0, out.length);
            return out;
        } finally {
            try { s.close(); } catch (Throwable ig) {}
            gap();
        }
    }

    private static String camGet(String ip, int cmd, String par, String str, int ct, int rt) throws Exception {
        StringBuilder path = new StringBuilder("/?custom=1&cmd=").append(cmd);
        if (par != null) path.append("&par=").append(par);
        if (str != null) path.append("&str=").append(Uri.encode(str, ":/"));
        byte[] b = httpBody(ip, path.toString(), null, null, ct, rt);
        return b == null ? null : new String(b, "UTF-8");
    }

    private static String downloadString(String ip, String fpath, int ct, int rt) throws Exception {
        byte[] b = httpBody(ip, camPath(fpath), null, null, ct, rt);
        return b == null ? null : new String(b, "UTF-8");
    }

    private static void download(String ip, String fpath, File dest) throws Exception {
        byte[] b = httpBody(ip, camPath(fpath), null, null, 4000, 30000);
        if (b == null) throw new java.io.IOException("HTTP error");
        FileOutputStream out = new FileOutputStream(dest);
        out.write(b);
        out.flush();
        out.close();
    }

    private static String camPath(String fpath) {
        String p = fpath;
        if (p.startsWith("A:") || p.startsWith("a:")) p = p.substring(2);
        p = p.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        String[] seg = p.split("/");
        StringBuilder u = new StringBuilder("/");
        for (int i = 0; i < seg.length; i++) {
            if (i > 0) u.append('/');
            u.append(Uri.encode(seg[i]));
        }
        return u.toString();
    }

    private static void uploadCfg(String ip, String cfg) throws Exception {
        String boundary = "----OpenFilm6KRewind" + System.currentTimeMillis();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"fileupload1\"; filename=\"rewind.cfg\"\r\n"
                + "Content-Type: text/plain\r\n\r\n";
        String tail = "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"upbtn\"\r\n\r\nUpload files\r\n"
                + "--" + boundary + "--\r\n";
        byte[] body = (head + cfg + tail).getBytes("UTF-8");
        httpBody(ip, "/DCIM/", body, "multipart/form-data; boundary=" + boundary, 4000, 15000);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    private static void gap() {
        try { Thread.sleep(GAP_MS); } catch (InterruptedException ig) {}
    }
}
