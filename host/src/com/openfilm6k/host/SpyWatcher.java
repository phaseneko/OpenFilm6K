package com.openfilm6k.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/** Spy mode (监视模式) engine: watch user-chosen folders for NEW photo arrivals.
 *
 *  Arrival-based by design: a filename snapshot taken at start is the baseline — camera
 *  clocks are never trusted, so mtimes/EXIF play no part in what counts as "new".
 *  New files are debounced (size must hold across one poll = transfer finished), then
 *  rendered through the camera-ingest pipeline with the spy dialog's film + stamp/border
 *  selection, straight into the DCIM album. */
public class SpyWatcher {
    private static final String TAG = "spy";
    private static final long POLL_MS = 3000;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService W =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private static Context ctx;                                 // app context of whoever armed us
    private static volatile boolean running;
    private static ArrayList<String> watching = new ArrayList<>();
    private static final HashMap<String, Long> pending = new HashMap<>();   // first-seen size per new file
    private static final HashSet<String> processed = new HashSet<>();       // baseline + already rendered

    /** reconcile the engine with the persisted spy_on flag — single semantic for the editor
     *  toggle, HostService boot and app relaunch: prefs are the truth, sync() aligns. */
    public static synchronized void sync(Context c) {
        SharedPreferences pf = pf(c);
        boolean want = pf.getBoolean("spy_on", false);
        if (want == running) return;
        if (want) {
            ArrayList<String> dirs = dirs(pf);
            if (dirs.isEmpty()) { MainActivity.say(L.s("spyDirs") + ": " + L.s("noneItem")); return; }
            ctx = c.getApplicationContext();
            watching = dirs;
            pending.clear();
            processed.clear();
            for (String d : watching) for (File f : ls(d)) processed.add(key(f));   // baseline: on disk NOW = old
            running = true;
            H.post(poll);
            Engine.dbg(TAG + ": watching " + watching.size() + " folder(s)");
        } else {
            running = false;
            H.removeCallbacks(poll);
            Engine.dbg(TAG + ": stopped");
        }
    }

    public static synchronized boolean running() { return running; }

    private static final Runnable poll = new Runnable() { public void run() {
        if (!running) return;
        try { scan(); } catch (Throwable t) { Engine.dbg(TAG + " poll: " + t); }
        H.postDelayed(poll, POLL_MS);
    }};

    private static void scan() {
        SharedPreferences pf = pf();
        for (String d : watching) for (File f : ls(d)) {
            String k = key(f);
            if (processed.contains(k)) continue;
            long sz = f.length();
            Long seen = pending.get(k);
            if (seen == null) { pending.put(k, sz); continue; }   // first sight — could still be transferring
            pending.remove(k);
            if (seen == sz) { processed.add(k); dispatch(f); }    // size stable across a poll: transfer done
            else pending.put(k, sz);                              // still growing, keep waiting
        }
    }

    private static void dispatch(final File f) {
        W.execute(new Runnable() { public void run() {
            try {
                SharedPreferences pf = pf();
                if (!pf.getBoolean("spy_on", false)) return;      // switched off while queued
                String film = pf.getString("spy_film", null);
                int mode = pf.getInt("spy_stamp", 0);
                if (film == null || film.length() == 0) { Engine.dbg(TAG + ": no film selected, skip"); return; }
                String r;
                if (mode == 8 || mode == 9) {           // 半格 / 双重曝光: every TWO arrivals compose into one
                    String first = pf.getString("spy_pair_first", "");
                    if (first.length() == 0 || !new File(first).exists()) {
                        pf.edit().putString("spy_pair_first", f.getPath()).commit();
                        MainActivity.say(L.s("spyPairWait"));
                        return;
                    }
                    pf.edit().putString("spy_pair_first", "").commit();
                    r = Server.spyPairIngest(new File(first), f, film, mode == 8);
                } else {
                    r = Server.spyIngest(f, film, mode);
                }
                if (r != null && r.startsWith("OK")) MainActivity.say(L.s("spyMode") + " " + r);
                else MainActivity.say(L.s("spyMode") + " ERR " + r);
            } catch (Throwable t) { Engine.dbg(TAG + " render: " + t); }
        }});
    }

    /** same newline-joined storage as the dialog's folder list */
    static ArrayList<String> dirs(SharedPreferences pf) {
        ArrayList<String> out = new ArrayList<>();
        String s = pf.getString("spy_dirs", "");
        if (s.length() > 0) for (String p : s.split("\n")) { p = p.trim(); if (p.length() > 0) out.add(p); }
        return out;
    }

    private static SharedPreferences pf(Context c) { return c.getApplicationContext().getSharedPreferences("of6k", 0); }
    private static SharedPreferences pf() { return pf(ctx); }

    private static ArrayList<File> ls(String dir) {
        ArrayList<File> out = new ArrayList<>();
        File[] a = new File(dir).listFiles();
        if (a == null) return out;
        for (File f : a) {
            String n = f.getName();
            if (!f.isFile() || n.startsWith(".")) continue;
            String lo = n.toLowerCase(java.util.Locale.US);
            if (lo.endsWith(".jpg") || lo.endsWith(".jpeg")) out.add(f);
        }
        return out;
    }

    private static String key(File f) { return f.getAbsolutePath(); }
}
