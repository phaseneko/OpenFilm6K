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

    private static File file() {
        // FuFsys only accepts new files inside firmware-created dirs — try in order
        for (String sub : new String[]{"OpenFilm6K", "DCIM/OpenFilm6K", "DCIM"}) {
            File d = new File(Environment.getExternalStorageDirectory(), sub);
            if (d.isDirectory() || d.mkdirs()) return new File(d, "LOG.TXT");
        }
        return new File(Environment.getExternalStorageDirectory(), "OpenFilm6K/LOG.TXT");
    }

    private static void start() {
        if (started) return;
        started = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        String m = Q.take();
                        StringBuilder sb = new StringBuilder(m);
                        while (!Q.isEmpty()) sb.append('\n').append(Q.poll());
                        File f = file();
                        try {
                            f.getParentFile().mkdirs();
                            BufferedWriter w = new BufferedWriter(new FileWriter(f, true));
                            w.append(sb.toString());
                            w.newLine();
                            w.close();
                        } catch (Throwable inner) { /* the next write attempt retries the chain */ }
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
            Q.offer(msg);
        } catch (Throwable t) {}
    }

    /** block until queued log lines are on disk (used right before finishing) */
    public static void flush() {
        while (!Q.isEmpty()) {
            try { Thread.sleep(20); } catch (InterruptedException e) { return; }
        }
    }
}
