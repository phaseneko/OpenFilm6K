package com.openfilm6k.host;

import java.io.*;
import java.util.*;

/** film library: pipelines/<name>.properties + luts/<base>.cube (decoupled) */
public class Films {
    public static final String ROOT = "/sdcard/OpenFilm6K";
    public static final String PIPE = "/sdcard/OpenFilm6K/pipelines";   // <film>.properties per pipeline
    public static final String LUTS = "/sdcard/OpenFilm6K/luts";        // <name>.cube, decoupled from pipelines
    private static final Map<String, Properties> CACHE = new HashMap<>();
    private static final Map<String, Long> MT = new HashMap<>();

    public static List<String> list() {
        List<String> out = new ArrayList<>();
        File[] fs = new File(PIPE).listFiles();
        if (fs != null) for (File f : fs) {
            String n = f.getName();
            if (!n.endsWith(".properties")) continue;
            String k = n.substring(0, n.length() - ".properties".length());
            if (k.endsWith(".day") || k.endsWith(".night") || k.equals("EDITTMP")) continue;
            out.add(k);
        }
        Collections.sort(out);
        return out;
    }

    public static Properties props(String film) {
        synchronized (CACHE) {
            long mt = 0;
            try { File f = new File(PIPE, film + ".properties"); if (f.exists()) mt = f.lastModified(); } catch (Throwable ig) {}
            Long old = MT.get(film);
            Properties p = CACHE.get(film);
            if (p != null && old != null && old == mt) return p;    // cached & unchanged
            p = new Properties();
            try { p.load(new FileInputStream(new File(PIPE, film + ".properties"))); }
            catch (Throwable t) { MainActivity.say("film props " + film + ": " + t); }
            CACHE.put(film, p); MT.put(film, mt);
            return p;
        }
    }

    /** drop cached properties (editor saves new values) */
    public static void reload(String film) { synchronized (CACHE) { CACHE.remove(film); } }

    /** persist grade keys into a film's properties, keeping everything else */
    public static void saveGrades(String film, java.util.Map<String,Float> g) {
        try {
            File f = new File(PIPE, film + ".properties");
            java.util.Properties p = new java.util.Properties();
            try { p.load(new FileInputStream(f)); } catch (Throwable ig) {}
            for (java.util.Map.Entry<String,Float> e : g.entrySet())
                p.setProperty(e.getKey(), String.valueOf(e.getValue()));
            p.store(new FileOutputStream(f), null);
            synchronized (CACHE) {                    // prime cache in-memory: renders never re-read
                CACHE.put(film, p);                   // the file (FUSE read-after-write can be stale)
                MT.put(film, f.lastModified());
            }
        } catch (Throwable t) { MainActivity.say("saveGrades " + film + ": " + t); }
    }

    /** resolve the film's lut= against the shared luts dir (imported/ and legacy films/ as fallbacks) */
    public static File lutFile(String film) {
        String lut = props(film).getProperty("lut", film + ".cube").trim();
        File f = new File(LUTS, lut);
        if (f.exists()) return f;
        File imp = new File(LUTS + "/imported", lut);
        if (imp.exists()) return imp;
        File leg = new File(ROOT + "/films/" + film, lut);   // EDITTMP / legacy layouts
        return leg.exists() ? leg : null;
    }

    public static float f(String film, String key, float def) {
        try { return Float.parseFloat(props(film).getProperty(key, String.valueOf(def))); }
        catch (Throwable t) { return def; }
    }

    /** save full pipeline (chain + per-node params), wiping stale node keys; keeps lut= and other keys */
    public static void savePipeline(String film, String chain, java.util.Map<String,Float> params) {
        try {
            File f = new File(PIPE, film + ".properties");
            java.util.Properties p = new java.util.Properties();
            try { p.load(new FileInputStream(f)); } catch (Throwable ig) {}
            String[] pre = {"grade.", "glow.", "grain.", "vig.", "lut.on"};
            for (Object ko : new java.util.ArrayList<Object>(p.keySet())) { String k = String.valueOf(ko);
                if (k.equals("chain") || k.contains("@")) { p.remove(k); continue; }
                for (String s : pre) if (k.startsWith(s)) { p.remove(k); break; }
            }
            p.setProperty("chain", chain);
            for (java.util.Map.Entry<String,Float> e : params.entrySet())
                p.setProperty(e.getKey(), String.valueOf(e.getValue()));
            p.store(new FileOutputStream(f), null);
            synchronized (CACHE) {
                CACHE.put(film, p);
                MT.put(film, f.lastModified());
            }
        } catch (Throwable t) { MainActivity.say("savePipeline " + film + ": " + t); }
    }

    /** persist a single string key, priming the FUSE-sensitive cache */
    public static void setProp(String film, String key, String val) {
        try {
            File f = new File(PIPE, film + ".properties");
            java.util.Properties p = new java.util.Properties();
            try { p.load(new FileInputStream(f)); } catch (Throwable ig) {}
            p.setProperty(key, val);
            p.store(new FileOutputStream(f), null);
            synchronized (CACHE) { CACHE.put(film, p); MT.put(film, f.lastModified()); }
        } catch (Throwable t) { MainActivity.say("setProp " + t); }
    }

    /** day/night split management: base file holds the flag, variants hold the configs */
    public static boolean isSplit(String film) { return "split".equals(s(film, "daynight", "")); }

    /** enable split: copy current base config into .day and .night, set the flag */
    public static void enableSplit(String film) {
        try {
            File base = new File(PIPE, film + ".properties");
            for (String v : new String[]{".day", ".night"}) {
                File d = new File(PIPE, film + v + ".properties");
                if (!d.exists()) copyFile(base, d);
            }
            Properties p = new Properties();
            try { p.load(new FileInputStream(base)); } catch (Throwable ig) {}
            p.setProperty("daynight", "split");
            p.store(new FileOutputStream(base), null);
            synchronized (CACHE) {
                CACHE.remove(film); CACHE.remove(film + ".day"); CACHE.remove(film + ".night");
            }
        } catch (Throwable t) { MainActivity.say("enableSplit " + t); }
    }

    /** copy variant srcKey's whole properties file over dstKey's */
    public static void overwriteVariant(String srcKey, String dstKey) {
        try {
            File s2 = new File(PIPE, srcKey + ".properties");
            File d2 = new File(PIPE, dstKey + ".properties");
            copyFile(s2, d2);
            synchronized (CACHE) { CACHE.remove(dstKey); }
        } catch (Throwable t) { MainActivity.say("overwrite " + t); }
    }

    private static void copyFile(File s, File d) throws IOException {
        FileInputStream in = new FileInputStream(s);
        FileOutputStream out = new FileOutputStream(d);
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); out.close();
    }

    public static String s(String film, String key, String def) {
        String v = props(film).getProperty(key);
        return v == null ? def : v.trim();
    }
}
