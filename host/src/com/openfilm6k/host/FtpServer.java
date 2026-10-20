package com.openfilm6k.host;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Locale;

/** Minimal FTP server for cameras that push stills over FTP (Sony "FTP Transfer Func.").
 *
 *  The camera is the FTP client: it logs in, CWD/MKDs into a destination folder and
 *  STORs each shot (passive or active data connection). Received files are spooled,
 *  then rendered through the grade pipeline with the configured film, exactly like a
 *  camera-app upload — gallery album + standing notification included.
 *
 *  Port 2121: port 21 is privileged and cannot be bound by an app; the camera's
 *  "Port Number" setting must match.
 */
public class FtpServer {
    private static final String TAG = "ftp";
    static final int PORT = 2121;
    static final String USER = "of6k";
    static final String PASS = "of6k";

    private static FtpServer I;
    private static android.content.Context ctx;
    private static volatile boolean up;

    private ServerSocket control;
    private File spool;

    static synchronized FtpServer get() {
        if (I == null) I = new FtpServer();
        return I;
    }

    /** reconcile with the persisted ftp_on flag — same semantics as SpyWatcher.sync */
    static synchronized void sync(android.content.Context c) {
        if (ctx == null) ctx = c.getApplicationContext();
        boolean want = ctx.getSharedPreferences("of6k", 0).getBoolean("ftp_on", false);
        if (want && !up) start(ctx);
        else if (!want && up) stop();
    }

    static synchronized void stop() {
        up = false;
        if (I != null) {
            try { if (I.control != null) I.control.close(); } catch (Throwable ig) {}
            I.control = null;
        }
        Engine.dbg(TAG + ": stopped");
    }

    /** connection info shown in the FTP dialog */
    static String infoText() {
        return localAddress() + " : " + PORT + "\n"
                + L.s("ftpUser") + ": " + USER + "   " + L.s("ftpPass") + ": " + PASS;
    }

    static synchronized void start(android.content.Context c) {
        if (up) return;
        ctx = c.getApplicationContext();
        get();
        up = true;
        Thread t = new Thread(new Runnable() { public void run() { I.loop(); } }, "ftp");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        spool = new File("/sdcard/OpenFilm6K/ftp");
        spool.mkdirs();
        try {
            control = new ServerSocket(PORT);
            String ip = localAddress();
            MainActivity.say("ftp on " + ip + ":" + PORT + " " + USER + "/" + PASS);
            Engine.dbg(TAG + ": listening on " + ip + ":" + PORT + ", spool " + spool
                    + ", addresses " + addresses());
            while (true) {
                final Socket s = control.accept();
                s.setSoTimeout(30000);
                Thread t = new Thread(new Runnable() { public void run() { session(s); } }, "ftp-session");
                t.setDaemon(true);
                t.start();
            }
        } catch (Throwable t) {
            if (up) { up = false; MainActivity.say("ftp dead: " + t); }
        }
    }

    /** every IPv4 address this phone holds, tagged with the interface name.
     *  "ap"/"swlan"/"softap"/"wlan1" is the hotspot on most Android builds. */
    static java.util.List<String> addresses() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> en =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                try { if (!ni.isUp() || ni.isLoopback()) continue; } catch (Throwable ig) { continue; }
                java.util.Enumeration<InetAddress> a = ni.getInetAddresses();
                while (a.hasMoreElements()) {
                    InetAddress ia = a.nextElement();
                    if (!(ia instanceof java.net.Inet4Address) || ia.isLoopbackAddress()) continue;
                    String h = ia.getHostAddress();
                    if (h != null) out.add(ni.getName() + "=" + h);
                }
            }
        } catch (Throwable t) { Engine.dbg(TAG + " addr: " + t); }
        return out;
    }

    /** address the camera should use: hotspot interface if it is up, else the best LAN one */
    static String localAddress() {
        String fallback = null;
        for (String e : addresses()) {
            int i = e.indexOf('=');
            if (i <= 0) continue;
            String name = e.substring(0, i).toLowerCase(Locale.US);
            String ip = e.substring(i + 1);
            if (name.startsWith("ap") || name.contains("softap") || name.contains("swlan")
                    || name.equals("wlan1") || name.equals("wlan2")) return ip;
            if (fallback == null && !name.startsWith("rmnet")
                    && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) fallback = ip;
        }
        return fallback != null ? fallback : "192.168.43.1";
    }

    // ------------------------------------------------------------------ session

    private static class Sess {
        String cwd = "/";
        ServerSocket pasv;
        Socket data;
        int activePort = -1;
        String activeHost;
        String renameFrom;
        boolean loggedIn;
    }

    private void session(Socket s) {
        Sess st = new Sess();
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            OutputStream raw = s.getOutputStream();
            send(raw, "220 OpenFilm6K FTP ready");
            String line;
            while ((line = in.readLine()) != null) {
                if (line.length() == 0) continue;
                int sp = line.indexOf(' ');
                String cmd = (sp < 0 ? line : line.substring(0, sp)).toUpperCase(Locale.US);
                String arg = sp < 0 ? "" : line.substring(sp + 1).trim();
                Engine.dbg(TAG + ": " + cmd + " " + (cmd.equals("PASS") ? "***" : arg));

                if (cmd.equals("USER")) { st.loggedIn = false; send(raw, "331 password required"); }
                else if (cmd.equals("PASS")) {
                    if (USER.equals(arg) || arg.length() > 0) { st.loggedIn = true; send(raw, "230 logged in"); }
                    else send(raw, "530 denied");
                }
                else if (cmd.equals("SYST")) send(raw, "215 UNIX Type: L8");
                else if (cmd.equals("FEAT")) {
                    send(raw, "211-Features:\r\n SIZE\r\n MDTM\r\n PASV\r\n EPSV\r\n UTF8\r\n211 End");
                }
                else if (cmd.equals("OPTS")) send(raw, "200 ok");
                else if (cmd.equals("TYPE")) send(raw, "200 type set");
                else if (cmd.equals("MODE")) send(raw, "200 mode set");
                else if (cmd.equals("STRU")) send(raw, "200 stru set");
                else if (cmd.equals("NOOP")) send(raw, "200 ok");
                else if (cmd.equals("PWD") || cmd.equals("XPWD")) {
                    send(raw, "257 \"" + (st.cwd.length() == 0 ? "/" : st.cwd) + "\" is current directory");
                }
                else if (cmd.equals("CWD") || cmd.equals("XCWD")) {
                    st.cwd = arg.startsWith("/") ? arg : (st.cwd.equals("/") ? "/" : st.cwd + "/") + arg;
                    send(raw, "250 changed to " + st.cwd);
                }
                else if (cmd.equals("CDUP") || cmd.equals("XCUP")) {
                    int i = st.cwd.lastIndexOf('/');
                    st.cwd = i > 0 ? st.cwd.substring(0, i) : "/";
                    send(raw, "250 ok");
                }
                else if (cmd.equals("MKD") || cmd.equals("XMKD")) send(raw, "257 \"" + arg + "\" created");
                else if (cmd.equals("DELE")) send(raw, "250 deleted");
                else if (cmd.equals("RNFR")) { st.renameFrom = arg; send(raw, "350 ready"); }
                else if (cmd.equals("RNTO")) { st.renameFrom = null; send(raw, "250 renamed"); }
                else if (cmd.equals("SIZE")) {
                    File f = resolve(arg);
                    send(raw, f.exists() ? "213 " + f.length() : "550 no such file");
                }
                else if (cmd.equals("MDTM")) send(raw, "213 20260101000000");
                else if (cmd.equals("PASV")) {
                    closeData(st);
                    st.pasv = new ServerSocket(0);
                    int p = st.pasv.getLocalPort();
                    String ip = localAddress().replace('.', ',');
                    send(raw, "227 Entering Passive Mode (" + ip + "," + (p >> 8) + "," + (p & 255) + ")");
                }
                else if (cmd.equals("EPSV")) {
                    closeData(st);
                    st.pasv = new ServerSocket(0);
                    send(raw, "229 Entering Extended Passive Mode (|||" + st.pasv.getLocalPort() + "|)");
                }
                else if (cmd.equals("PORT")) {
                    String[] p = arg.split(",");
                    if (p.length == 6) {
                        st.activeHost = p[0] + "." + p[1] + "." + p[2] + "." + p[3];
                        st.activePort = (Integer.parseInt(p[4]) << 8) + Integer.parseInt(p[5]);
                        send(raw, "200 port command ok");
                    } else send(raw, "501 bad port");
                }
                else if (cmd.equals("EPRT")) {
                    String[] p = arg.split("\\|");
                    if (p.length >= 5) {
                        st.activeHost = p[2];
                        st.activePort = Integer.parseInt(p[3]);
                        send(raw, "200 ok");
                    } else send(raw, "501 bad eprt");
                }
                else if (cmd.equals("LIST") || cmd.equals("NLST")) {
                    Socket d = openData(st);
                    if (d == null) { send(raw, "425 cannot open data"); continue; }
                    send(raw, "150 Opening ASCII mode data connection");
                    try { d.getOutputStream().write(new byte[0]); } catch (Throwable ig) {}
                    closeSocket(d);
                    send(raw, "226 transfer complete");
                }
                else if (cmd.equals("STOR") || cmd.equals("STOU") || cmd.equals("APPE")) {
                    File dest = resolve(cmd.equals("STOU") ? uniqueName() : arg);
                    Socket d = openData(st);
                    if (d == null) { send(raw, "425 cannot open data"); continue; }
                    send(raw, "150 Opening BINARY mode data connection");
                    File part = new File(dest.getAbsolutePath() + ".part");
                    long got = 0;
                    try {
                        InputStream di = d.getInputStream();
                        FileOutputStream fo = new FileOutputStream(part);
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = di.read(buf)) > 0) { fo.write(buf, 0, n); got += n; }
                        fo.flush(); fo.close();
                    } catch (Throwable t) {
                        Engine.dbg(TAG + ": store " + dest.getName() + " failed: " + t);
                        try { part.delete(); } catch (Throwable ig) {}
                        send(raw, "426 transfer aborted");
                        closeSocket(d);
                        continue;
                    }
                    closeSocket(d);
                    if (got == 0) {
                        part.delete();
                        send(raw, "426 no data on the data connection");
                        continue;
                    }
                    if (part.renameTo(dest)) {
                        send(raw, "226 transfer complete (" + got + " bytes)");
                        ingest(dest);
                    } else {
                        part.delete();
                        send(raw, "550 cannot save");
                    }
                }
                else if (cmd.equals("RETR")) send(raw, "550 not available");
                else if (cmd.equals("ABOR")) send(raw, "226 aborted");
                else if (cmd.equals("QUIT")) { send(raw, "221 bye"); break; }
                else send(raw, "502 not implemented: " + cmd);
            }
        } catch (Throwable t) {
            Engine.dbg(TAG + " session: " + t);
        } finally {
            closeData(st);
            try { s.close(); } catch (Throwable ig) {}
        }
    }

    private static void send(OutputStream o, String line) throws Exception {
        o.write((line + "\r\n").getBytes("UTF-8"));
        o.flush();
    }

    private File resolve(String name) {
        if (name == null) name = "";
        name = name.replace('\\', '/');
        if (name.startsWith("/")) name = name.substring(1);
        int i = name.lastIndexOf('/');
        if (i >= 0) name = name.substring(i + 1);
        if (name.length() == 0) name = uniqueName();
        File f = new File(spool, name);
        if (f.exists() && isPhoto(f)) f = new File(spool, uniqueName());
        return f;
    }

    private static String uniqueName() {
        return "cam_" + System.currentTimeMillis() + ".jpg";
    }

    private static boolean isPhoto(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".arw")
                || n.endsWith(".hif") || n.endsWith(".heif");
    }

    private Socket openData(Sess st) {
        try {
            if (st.pasv != null) {
                st.pasv.setSoTimeout(20000);
                Socket d = st.pasv.accept();
                closePasv(st);
                return d;
            }
            if (st.activePort > 0) {
                Socket d = new Socket();
                d.connect(new InetSocketAddress(st.activeHost, st.activePort), 15000);
                return d;
            }
        } catch (Throwable t) {
            Engine.dbg(TAG + " data: " + t);
            closePasv(st);
        }
        return null;
    }

    private static void closePasv(Sess st) {
        try { if (st.pasv != null) st.pasv.close(); } catch (Throwable ig) {}
        st.pasv = null;
    }

    private static void closeData(Sess st) {
        closePasv(st);
        if (st.data != null) closeSocket(st.data);
        st.data = null;
    }

    private static void closeSocket(Socket s) {
        try { s.close(); } catch (Throwable ig) {}
    }

    // ------------------------------------------------------------------ render

    /** render a received still with the configured film, same pipeline as a camera upload */
    private static void ingest(final File f) {
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                String film = film();
                if (film == null) { MainActivity.say("ftp: no film selected"); return; }
                String post = prefs().getString("ftp_post", "");
                int mode = prefs().getInt("ftp_stamp", 3);
                String r = Server.spyIngest(f, film, mode, post);
                if (r != null && r.startsWith("OK")) MainActivity.say("ftp " + r);
                else MainActivity.say("ftp ERR " + r);
                Engine.dbg(TAG + ": ingest " + f.getName() + " -> " + r);
            } catch (Throwable t) { Engine.dbg(TAG + " ingest: " + t); }
        }}, "ftp-render");
        t.setDaemon(true);
        t.start();
    }

    private static android.content.SharedPreferences prefs() {
        return ctx.getSharedPreferences("of6k", 0);
    }

    /** ftp_film if set, else the film last used by spy mode / the camera, else the first film */
    private static String film() {
        android.content.SharedPreferences pf = prefs();
        String f = pf.getString("ftp_film", "");
        if (f != null && f.length() > 0) return f;
        f = pf.getString("spy_film", "");
        if (f != null && f.length() > 0) return f;
        try {
            List<String> all = Films.listUserFirst();
            if (all != null && all.size() > 0) return all.get(0);
        } catch (Throwable ig) {}
        return null;
    }

    /** legacy: copy helper kept for symmetry with Server's file handling */
    static boolean copy(File src, File dst) {
        try {
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush(); out.close(); in.close();
            return true;
        } catch (Throwable t) { return false; }
    }
}
