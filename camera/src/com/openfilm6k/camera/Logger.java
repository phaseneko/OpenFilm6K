package com.openfilm6k.camera;

import android.os.Environment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.LinkedBlockingQueue;

/** the camera has no logcat we can reach — log to the SD card, read via USB.
 *  Logging must never block the focus/shutter path, so writes happen on a
 *  background thread. The file is opened per flush and closed again, so adb
 *  can read it between writes (an open handle locks it "busy" on this FS). */
public final class Logger {
    private Logger() {}

    private static final LinkedBlockingQueue<String> Q = new LinkedBlockingQueue<String>();
    private static volatile boolean started = false;
    private static volatile boolean busy = false;   // writer thread is in the middle of a disk write

    // in-memory ring: the on-camera log viewer reads this without touching the SD card
    private static final int RING = 400;
    private static final String[] RING_BUF = new String[RING];
    private static final Object RING_LOCK = new Object();
    private static int ringNext = 0, ringCount = 0;

    /** append one line to the in-memory ring (cheap: one array store, no allocation) */
    private static void ringAdd(String line) {
        synchronized (RING_LOCK) {
            RING_BUF[ringNext] = line;
            ringNext = (ringNext + 1) % RING;
            if (ringCount < RING) ringCount++;
        }
    }

    /** the newest lines in chronological order (the newest is the LAST element) */
    public static String[] recent(int max) {
        if (max <= 0) return new String[0];
        synchronized (RING_LOCK) {
            int n = Math.min(max, ringCount);
            String[] out = new String[n];
            int start = (ringNext - n + RING) % RING;
            for (int i = 0; i < n; i++) out[i] = RING_BUF[(start + i) % RING];
            return out;
        }
    }

    /** the last lines of the on-disk log — the memory ring is empty after a crash+restart */
    public static String[] fileTail(int max) {
        if (max <= 0) return new String[0];
        java.io.RandomAccessFile raf = null;
        try {
            File f = file();
            if (!f.isFile() || f.length() <= 0) return new String[0];
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            int cap = 64 * 1024;                       // enough for ~400 short lines; grows if needed
            byte[] b;
            long start;
            while (true) {
                int n = (int) Math.min(cap, len);
                start = len - n;
                b = new byte[n];
                raf.seek(start);
                raf.readFully(b);
                int nl = 0;
                for (int i = 0; i < n; i++) if (b[i] == '\n') nl++;
                if (start == 0 || nl > max) break;
                cap *= 2;
            }
            String s = new String(b, "UTF-8");
            if (start > 0) {                           // drop the partial first line (and any split multibyte char)
                int i = s.indexOf('\n');
                s = i >= 0 ? s.substring(i + 1) : "";
            }
            String[] all = s.split("\n");
            int end = all.length;
            while (end > 0 && all[end - 1].trim().length() == 0) end--;   // the writer always ends with a newline
            int from = Math.max(0, end - max);
            String[] out = new String[end - from];
            System.arraycopy(all, from, out, 0, out.length);
            return out;
        } catch (Throwable t) {
            return new String[0];
        } finally {
            try { if (raf != null) raf.close(); } catch (Throwable ig) {}
        }
    }

    private static File privateDir;                 // app-private fallback (getFilesDir), set once at startup
    private static volatile File chosen;            // decided target after a failed root append

    /** app-private fallback directory — call before the first log */
    public static void setPrivateDir(File d) { privateDir = d; }

    /** the log target: SD-card root LOG.TXT unless the writer already fell back to privateDir */
    public static File file() {
        File c = chosen;
        if (c != null) return c;
        return new File(Environment.getExternalStorageDirectory(), "LOG.TXT");
    }

    private static boolean under(File f, File dir) {
        try {
            String a = f.getCanonicalPath(), b = dir.getCanonicalPath();
            return a.equals(b) || a.startsWith(b + File.separator);
        } catch (Throwable t) {
            return f.getAbsolutePath().startsWith(dir.getAbsolutePath());
        }
    }

    /** append one batch; never creates directories */
    private static void writeBatch(File f, String text) throws java.io.IOException {
        File p = f.getParentFile();
        if (p == null || !p.isDirectory()) throw new java.io.IOException("no dir: " + p);
        BufferedWriter w = new BufferedWriter(new FileWriter(f, true));
        try { w.append(text); w.newLine(); } finally { w.close(); }
    }

    private static void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        String m = Q.take();
                        busy = true;
                        try {
                            StringBuilder sb = new StringBuilder(m);
                            while (!Q.isEmpty()) sb.append('\n').append(Q.poll());
                            File f = file();
                            try {
                                writeBatch(f, sb.toString());
                            } catch (Throwable inner) {
                                File pd = privateDir;
                                if (pd != null && !under(f, pd)) {   // root not writable: switch once, retry the same batch
                                    chosen = new File(pd, "LOG.TXT");
                                    try { writeBatch(chosen, sb.toString()); } catch (Throwable inner2) { }
                                }
                            }
                        } finally { busy = false; }
                    } catch (Throwable t2) {}
                }
            }
        }, "logger");
        t.setDaemon(true);
        t.start();
    }

    public static void log(String msg) {
        if (!started) start();
        try { android.util.Log.i("OF6K", msg); } catch (Throwable t) {}   // mirror to logcat (readable via `adb logcat -s OF6K`)
        try {
            msg = new java.text.SimpleDateFormat("HH:mm:ss.SSS ").format(new java.util.Date()) + msg;
            ringAdd(msg);
            Q.offer(msg);
        } catch (Throwable t) {}
    }

    /** block until queued log lines are on disk (used right before finishing, and when logging a crash) */
    public static void flush() {
        long end = System.currentTimeMillis() + 3000;   // never hang a dying process forever
        while ((!Q.isEmpty() || busy) && System.currentTimeMillis() < end) {
            try { Thread.sleep(20); } catch (InterruptedException e) { return; }
        }
    }
}
