package com.openfilm6k.host;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Custom watermark templates.
 *
 * Templates live in /sdcard/OpenFilm6K/watermarks/<id>/template.json with their imported images
 * next to them. The renderer composites the ALREADY GRADED photo onto a white or black card and
 * draws the template's images / texts on top — everything here runs after Engine.process(), so the
 * card, the text and the decorations never receive the film look or any post processing.
 */
final class Watermark {
    static final File ROOT = new File("/sdcard/OpenFilm6K/watermarks");
    private static final File MODEL_DB = new File("/sdcard/OpenFilm6K/camera_names.json");
    private static final int MAX_EDGE = 4096;            // ARGB canvas cap: ~45MB at 3:2

    private static Map<String, String> models;           // normalised EXIF model -> marketing name
    private static long modelsStamp = -1L;

    private static final Paint FILTER = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private Watermark() {}

    // ---------------------------------------------------------------- template model

    static final class El {
        String type;                 // photo | img | text
        float x, y, w, h;            // fractions of the canvas
        String ratio;                // photo only (editor guidance)
        String dir = "h";            // line: h | v (the box's thin side is the thickness)
        String brand = "";           // logo: "" = follow the photo EXIF, else a fixed brand slug
        String edge = "soft";        // soft | jagged (hard = soft with size 0)
        float edgeSize = 0.1f;       // unified edge size: default 0.2% feather (jagged defaults to 1)
        String src;                  // img: file name inside the template dir
        float opacity = 1f;
        float cx0 = 0f, cy0 = 0f, cx1 = 1f, cy1 = 1f;   // crop viewport inside the element box (photo/img)
        String text = "";
        float size = 0.03f;          // text: fraction of the canvas height
        int color = 0xFF222222;
        int blend = 1;               // 0 normal, 1 adaptive (black/white by background, DEFAULT), 2 screen
        float alpha = 1f;            // text ink opacity
        String align = "left";       // left | center | right
        String font = "sans";        // typeface id, see face()
        boolean bold;
        boolean shOn;
        int shColor = 0x8C000000;
        int shBlend = 0;             // shadow blend: 0 normal, 1 adaptive, 2 screen
        float shBlur = 0.02f, shDx = 0f, shDy = 0.01f;
    }

    /** bitmap currently being drawn: adaptive text samples it for its 5-point brightness */
    private static Bitmap sTarget;

    static final class Tpl {
        String id, name;
        float ar = 1.5f;
        int bg = 0xFFFFFFFF;
        boolean padapt = true;       // portrait fit: re-anchor the layout onto a portrait card (on by default)
        final List<El> els = new ArrayList<El>();
    }

    /** "3:2" -> 1.5 etc; unknown falls back to 3:2 */
    static float ratioOf(String s) {
        try {
            int i = s.indexOf(':');
            if (i > 0) {
                float a = Float.parseFloat(s.substring(0, i).trim());
                float b = Float.parseFloat(s.substring(i + 1).trim());
                if (a > 0 && b > 0) return a / b;
            }
            float v = Float.parseFloat(s.trim());   // free-form card: a plain decimal aspect
            if (v > 0.05f) return v;
        } catch (Throwable ig) {}
        return 1.5f;
    }

    /** null when the template is missing or invalid (caller falls back to the plain graded photo) */
    static Tpl load(String id) {
        if (id == null || id.trim().isEmpty()) return null;
        File dir = new File(ROOT, id.trim());
        File f = new File(dir, "template.json");
        if (!f.isFile()) return null;
        try {
            JSONObject o = new JSONObject(readText(f));
            Tpl t = new Tpl();
            t.id = id.trim();
            t.name = o.optString("name", t.id);
            JSONObject cv = o.optJSONObject("canvas");
            if (cv != null) {
                double arNum = cv.optDouble("ar", -1);
                t.ar = arNum > 0.05 ? (float) arNum : ratioOf(cv.optString("ratio", "3:2"));
                String bg = cv.optString("bg", "#FFFFFF");
                try { t.bg = Color.parseColor(bg); } catch (Throwable ig) { t.bg = 0xFFFFFFFF; }
            }
            t.padapt = o.optBoolean("padapt", true);       // portrait fit is on unless a template says otherwise
            JSONArray a = o.optJSONArray("elements");
            if (a != null) {
                for (int i = 0; i < a.length() && t.els.size() < 40; i++) {
                    JSONObject e = a.optJSONObject(i);
                    if (e == null) continue;
                    El el = new El();
                    el.type = e.optString("t", "");
                    if ("hline".equals(el.type) || "vline".equals(el.type)) {   // legacy rule types
                        el.dir = "vline".equals(el.type) ? "v" : "h";
                        el.type = "line";
                    } else if ("line".equals(el.type)) {
                        el.dir = "v".equals(e.optString("dir", "h")) ? "v" : "h";
                    }
                    if (!"photo".equals(el.type) && !"img".equals(el.type) && !"text".equals(el.type)
                            && !"line".equals(el.type) && !"logo".equals(el.type)
                            && !"rect".equals(el.type)) continue;
                    el.w = clamp01((float) e.optDouble("w", 0.5));
                    el.h = clamp01((float) e.optDouble("h", 0.5));
                    el.x = clampKeep((float) e.optDouble("x", 0), el.w);
                    el.y = clampKeep((float) e.optDouble("y", 0), el.h);
                    if (el.w <= 0.002f || el.h <= 0.002f) continue;
                    el.ratio = e.optString("r", null);
                    String ed = e.optString("edge", "soft");
                    el.edge = "jagged".equals(ed) ? "jagged" : "soft";          // legacy "hard" becomes soft
                    float edDef = "hard".equals(ed) ? 0f : ("jagged".equals(el.edge) ? 1f : 0.1f);
                    el.edgeSize = Math.max(0f, Math.min(5f, (float) e.optDouble("edgeSize", edDef)));
                    el.src = e.optString("src", null);
                    JSONArray cp = e.optJSONArray("crop");
                    if (cp != null && cp.length() == 4) {
                        el.cx0 = clamp01((float) cp.optDouble(0, 0));
                        el.cy0 = clamp01((float) cp.optDouble(1, 0));
                        el.cx1 = clamp01((float) cp.optDouble(2, 1));
                        el.cy1 = clamp01((float) cp.optDouble(3, 1));
                        if (el.cx1 - el.cx0 < 0.02f) { el.cx0 = 0f; el.cx1 = 1f; }
                        if (el.cy1 - el.cy0 < 0.02f) { el.cy0 = 0f; el.cy1 = 1f; }
                    }
                    el.opacity = (float) Math.max(0, Math.min(1, e.optDouble("opacity", 1)));
                    el.text = e.optString("s", "");
                    el.size = (float) Math.max(0.002, Math.min(0.5, e.optDouble("size", 0.03)));
                    String col = e.optString("color", "#FF222222");
                    try { el.color = Color.parseColor(col); } catch (Throwable ig) { el.color = 0xFF222222; }
                    el.align = e.optString("align", "left");
                    el.bold = e.optBoolean("bold", false);
                    el.font = e.optString("font", "sans");
                    el.blend = Math.max(0, Math.min(2, e.optInt("blend", 1)));   // default: adaptive
                    el.alpha = (float) Math.max(0, Math.min(1, e.optDouble("alpha", 1)));
                    el.brand = e.optString("brand", "");
                    JSONObject sh = e.optJSONObject("shadow");
                    if (sh != null) {
                        el.shOn = sh.optBoolean("on", false);
                        try { el.shColor = Color.parseColor(sh.optString("color", "#8C000000")); }
                        catch (Throwable ig) { el.shColor = 0x8C000000; }
                        float op = (float) Math.max(0, Math.min(1, sh.optDouble("opacity", 0.55)));
                        el.shColor = (el.shColor & 0x00FFFFFF) | (Math.round(op * 255f) << 24);
                        el.shBlur = (float) Math.max(0, Math.min(0.2, sh.optDouble("blur", 0.02)));
                        el.shDx = (float) Math.max(-0.2, Math.min(0.2, sh.optDouble("dx", 0)));
                        el.shDy = (float) Math.max(-0.2, Math.min(0.2, sh.optDouble("dy", 0.01)));
                        el.shBlend = Math.max(0, Math.min(2, sh.optInt("blend", 0)));
                    }
                    t.els.add(el);
                }
            }
            int photos = 0;
            for (El el : t.els) if ("photo".equals(el.type)) photos++;
            for (El el : t.els) if ("photo".equals(el.type)) fitPhoto(el, t.ar);
            if (photos < 1) {
                Engine.dbg("wm: template " + t.id + " has no photo element (need at least 1)");
                return null;
            }
            return t;
        } catch (Throwable ex) {
            Engine.dbg("wm load " + id + ": " + ex);
            return null;
        }
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** same rule as the editor: an element may hang off the top/left, as long as one corner stays
     *  inside the card (eps = 1%). Loading must not yank it back to the card origin. */
    static float clampKeep(float v, float size) {
        final float eps = 0.01f;
        float lo = -size + eps, hi = 1f - eps;
        return lo >= hi ? v : Math.max(lo, Math.min(hi, v));
    }

    /** the photo is ALWAYS a 135 frame (3:2) whatever the card ratio is. The slot KEEPS its height
     *  (stable across ratio changes) and the width follows 3:2, so switching 3:2 -> 16:9 -> 1:1 never
     *  makes the photo grow or drift. */
    static void fitPhoto(El e, float ar) {
        e.ratio = "3:2";
        float oldX = e.x, oldW = e.w;
        float h = Math.max(0.02f, Math.min(3f, e.h));
        float w = h * 1.5f / ar;
        if (w > 3f) { w = 3f; h = w * ar / 1.5f; }
        if (w < 0.02f) { w = 0.02f; h = w * ar / 1.5f; }
        e.w = w;
        e.h = h;
        // the slot may hang off the card (the user places photos outside the frame on purpose):
        // keep the horizontal centre and only require that one corner stays inside
        e.x = clampKeep(oldX + (oldW - w) / 2f, w);
        e.y = clampKeep(e.y, h);
    }

    /**
     * Template ids in THE one canonical order: creation order, oldest first — a newly added card
     * always lands at the END of every list. The editor dropdown, the stamp/border dialogs and the
     * camera menu feed all use this, so they can never disagree (the raw filesystem order is arbitrary).
     */
    static List<String> ids() {
        final Map<String, Long> mt = new HashMap<String, Long>();
        List<String> out = new ArrayList<String>();
        File[] ds = ROOT.listFiles();
        if (ds != null) {
            for (File d : ds) {
                if (d.isDirectory() && new File(d, "template.json").isFile()) {
                    out.add(d.getName());
                    mt.put(d.getName(), d.lastModified());
                }
            }
        }
        Collections.sort(out, new Comparator<String>() {
            public int compare(String a, String b) {
                long la = mt.containsKey(a) ? mt.get(a) : 0L, lb = mt.containsKey(b) ? mt.get(b) : 0L;
                if (la != lb) return la < lb ? -1 : 1;       // oldest first, newest last
                return a.compareTo(b);                        // ids carry the creation time
            }
        });
        return out;
    }

    // ---------------------------------------------------------------- authoring

    static File dir(String id) { return new File(ROOT, id); }

    static String newId() { return "wm-" + System.currentTimeMillis(); }

    /** a fresh template: 3:2 white card with the required photo slot */
    static Tpl create(String name) {
        Tpl t = new Tpl();
        t.id = newId();
        t.name = name == null || name.trim().isEmpty() ? "Untitled" : name.trim();
        t.ar = 1.5f;
        t.bg = 0xFFFFFFFF;
        t.padapt = true;
        El p = new El();
        p.type = "photo";
        p.x = 0.05f; p.y = 0.05f; p.w = 0.90f; p.h = 0.70f;
        p.ratio = "3:2";
        t.els.add(p);
        return t;
    }

    static String ratioStr(float ar) {
        if (Math.abs(ar - 1f) < 0.02f) return "1:1";
        if (Math.abs(ar - 1.3333f) < 0.02f) return "4:3";
        if (Math.abs(ar - 1.7778f) < 0.02f) return "16:9";
        if (Math.abs(ar - 2.7f) < 0.03f) return "2.7:1";
        return String.format(Locale.US, "%.4f", ar);      // free-form card aspect
    }

    private static String colorStr(int c) { return String.format(Locale.US, "#%08X", c); }

    private static float r4(float v) { return Math.round(v * 10000f) / 10000f; }

    /** deep copy of an element (used by "new from current") */
    private static El copyEl(El s) {
        El e = new El();
        e.type = s.type; e.x = s.x; e.y = s.y; e.w = s.w; e.h = s.h;
        e.ratio = s.ratio; e.edge = s.edge; e.edgeSize = s.edgeSize; e.dir = s.dir; e.brand = s.brand;
        e.src = s.src; e.opacity = s.opacity;
        e.cx0 = s.cx0; e.cy0 = s.cy0; e.cx1 = s.cx1; e.cy1 = s.cy1;
        e.text = s.text; e.size = s.size; e.color = s.color; e.align = s.align; e.bold = s.bold; e.font = s.font;
        e.shOn = s.shOn; e.shColor = s.shColor; e.shBlur = s.shBlur; e.shDx = s.shDx; e.shDy = s.shDy; e.shBlend = s.shBlend; e.blend = s.blend; e.alpha = s.alpha;
        return e;
    }

    /** duplicate a template (geometry + imported images) under a new name */
    static Tpl duplicate(Tpl src, String name) {
        Tpl t = new Tpl();
        t.id = newId();
        t.name = name == null || name.trim().isEmpty() ? "Untitled" : name.trim();
        t.ar = src.ar;
        t.bg = src.bg;
        for (El e : src.els) t.els.add(copyEl(e));
        try {
            File dir = dir(t.id);
            dir.mkdirs();
            File sd = dir(src.id);
            for (El e : t.els) {
                if (!"img".equals(e.type) || e.src == null) continue;
                File from = new File(sd, e.src);
                File to = new File(dir, e.src);
                if (!from.isFile()) continue;
                java.io.FileInputStream in = new java.io.FileInputStream(from);
                java.io.FileOutputStream out = new java.io.FileOutputStream(to);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                out.close();
            }
        } catch (Throwable ex) {
            Engine.dbg("wm duplicate " + ex);
        }
        save(t, null);
        return t;
    }

    static boolean save(Tpl t, android.graphics.Bitmap preview) {
        try {
            File d = dir(t.id);
            if (!d.isDirectory() && !d.mkdirs()) return false;
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("id", t.id);
            o.put("name", t.name);
            JSONObject cv = new JSONObject();
            cv.put("ratio", ratioStr(t.ar));
            cv.put("ar", r4(t.ar));
            cv.put("bg", colorStr(t.bg));
            o.put("canvas", cv);
            o.put("padapt", t.padapt);
            JSONArray a = new JSONArray();
            for (El e : t.els) {
                JSONObject j = new JSONObject();
                j.put("t", e.type);
                j.put("x", r4(e.x)); j.put("y", r4(e.y));
                j.put("w", r4(e.w)); j.put("h", r4(e.h));
                if ("line".equals(e.type)) j.put("dir", lineIsV(e.w, e.h / Math.max(0.2f, t.ar)) ? "v" : "h");
                if ("logo".equals(e.type) && e.brand != null && e.brand.length() > 0) j.put("brand", e.brand);
                if ("photo".equals(e.type)) {
                    j.put("r", "3:2");                                        // 135 frame, fixed
                    j.put("edge", e.edge);
                    j.put("edgeSize", r4(e.edgeSize));
                } else if ("img".equals(e.type)) {
                    j.put("src", e.src == null ? "" : e.src);
                    j.put("opacity", r4(e.opacity));
                } else if ("rect".equals(e.type)) {
                    j.put("color", colorStr(e.color));
                    j.put("blend", e.blend);
                    j.put("alpha", r4(e.alpha));
                    j.put("edge", e.edge);
                    j.put("edgeSize", r4(e.edgeSize));
                } else {
                    j.put("s", e.text == null ? "" : e.text);
                    j.put("size", r4(e.size));
                    j.put("color", colorStr(e.color));
                    j.put("align", e.align);
                    j.put("bold", e.bold);
                    j.put("font", e.font);
                    j.put("blend", e.blend);          // 0 (normal) must persist too — omitted 0 would load back as 1
                    j.put("alpha", r4(e.alpha));
                }
                if (e.cx0 > 0.001f || e.cy0 > 0.001f || e.cx1 < 0.999f || e.cy1 < 0.999f) {
                    JSONArray cp = new JSONArray();
                    cp.put(r4(e.cx0)); cp.put(r4(e.cy0)); cp.put(r4(e.cx1)); cp.put(r4(e.cy1));
                    j.put("crop", cp);
                }
                if (e.shOn) {
                    JSONObject sh = new JSONObject();
                    sh.put("on", true);
                    sh.put("color", colorStr((e.shColor & 0x00FFFFFF) | 0xFF000000));
                    sh.put("opacity", r4(android.graphics.Color.alpha(e.shColor) / 255f));
                    sh.put("blur", r4(e.shBlur));
                    sh.put("dx", r4(e.shDx));
                    sh.put("dy", r4(e.shDy));
                    sh.put("blend", e.shBlend);
                    j.put("shadow", sh);
                }
                a.put(j);
            }
            o.put("elements", a);
            writeText(new File(d, "template.json"), o.toString(2));
            if (preview != null) {
                File pf = new File(d, "preview.jpg");
                FileOutputStream fo = new FileOutputStream(pf);
                preview.compress(Bitmap.CompressFormat.JPEG, 88, fo);
                fo.close();
            }
            return true;
        } catch (Throwable ex) {
            Engine.dbg("wm save " + ex);
            return false;
        }
    }

    private static void writeText(File f, String s) throws Exception {
        FileOutputStream fo = new FileOutputStream(f);
        try { fo.write(s.getBytes("UTF-8")); } finally { fo.close(); }
    }

    /** editor preview substitution: same tokens, fixed sample values (real EXIF is used at render time) */
    static String sample(String s) {
        if (s == null) return "";
        return s
            .replace("{model}", "Model")
            .replace("{make}", "Sony")
            .replace("{lens}", "FE 35mm F1.8")
            .replace("{shutter}", "1/250s")
            .replace("{aperture}", "F2.8")
            .replace("{iso}", "ISO400")
            .replace("{exposure}", "1/250s F2.8 ISO400")
            .replace("{date}", "2026.10.24")
            .replace("{time}", "18:42")
            .replace("{film}", "CC-200");
    }

    // ---------------------------------------------------------------- render

    /**
     * Re-layout a template for a portrait card. Distances are handled in SHORT-EDGE units: the
     * landscape reference is (ar x 1) and the portrait card is (1 x ar), so both share one unit and
     * the same area. Per axis: a box spanning the axis stays stretched; a box with symmetric margins
     * keeps both margins when the axis grows (bars / bands) or its size and centre when it shrinks;
     * otherwise it keeps its size and its distance to the nearer edge.
     */
    /**
     * Re-layout a template for a portrait card.
     *
     * The landscape card is the ground truth: how an element is pinned (start edge, end edge, centred,
     * or stretched across the axis) is decided ONCE from the landscape numbers and never re-derived
     * after scaling. Two siblings therefore cannot swap sides or drift apart, whatever unit is chosen.
     * The unit only scales sizes: it is the mean of the card's short edge and its width, i.e. half-way
     * between "everything by the short edge" and "everything by the width".
     */
    static List<El> mapPortrait(List<El> els, float ar) {
        float k = 2f / (1f + ar);                 // design length unit = k * landscape short edge
        final float lx1 = 1f, ly1 = ar;           // portrait card: width 1 unit, height ar units
        final float eps = 0.01f, tol = 0.02f;     // both measured on the LANDSCAPE card
        List<El> out = new ArrayList<El>(els.size());
        for (El e : els) {
            El m = copyEl(e);
            // a photo / image lives in its crop viewport: map the VIEWPORT (so an inset photo keeps
            // its bars) and make the element box the viewport itself
            boolean cropped = ("photo".equals(e.type) || "img".equals(e.type))
                    && (e.cx0 > 0.001f || e.cy0 > 0.001f || e.cx1 < 0.999f || e.cy1 < 0.999f);
            float bx = e.x, by = e.y, bw = e.w, bh = e.h;
            if (cropped) {
                bx = e.x + e.cx0 * e.w; by = e.y + e.cy0 * e.h;
                bw = (e.cx1 - e.cx0) * e.w; bh = (e.cy1 - e.cy0) * e.h;
                m.cx0 = 0f; m.cy0 = 0f; m.cx1 = 1f; m.cy1 = 1f;
            }
            boolean photo = "photo".equals(e.type) || "img".equals(e.type);
            // landscape margins (short-edge units): x axis spans ar, y axis spans 1
            float[] hx = place(bx * ar, bw * ar, ar, lx1, k, ar, photo, eps, tol);
            float[] vy = place(by, bh, 1f, ly1, k, ar, photo, eps, tol);
            m.x = hx[0] / lx1; m.w = hx[1] / lx1;
            m.y = vy[0] / ly1; m.h = vy[1] / ly1;
            m.size = e.size * k / ar;          // text size follows the unit, not the box
            out.add(m);
        }
        return out;
    }

    /**
     * Place one element on one axis. {@code a,s} and {@code L0} are the landscape geometry; the pin is
     * decided there and only the sizes are converted with {@code k} into the portrait axis of length
     * {@code L1}. A photo that dominates its axis keeps both margins so the band absorbs the extra room.
     */
    static float[] place(float a, float s, float L0, float L1, float k, float ar,
                         boolean photo, float eps, float tol) {
        float m1 = a, m2 = L0 - a - s;
        if (m1 <= eps && m2 <= eps) return new float[]{0f, L1};                    // spans the axis
        if (photo && s >= 0.5f * L0) return new float[]{m1 * k, L1 - (m1 + m2) * k};   // band: keep margins
        if (Math.abs(m1 - m2) <= tol) {                                            // centred in the design
            float c = (m1 + s / 2f) / L0;
            return new float[]{c * L1 - s * k / 2f, s * k};
        }
        if (m1 < m2) return new float[]{m1 * k, s * k};                            // pinned to the start edge
        return new float[]{L1 - m2 * k - s * k, s * k};                            // pinned to the end edge
    }

    /** Overwrites {@code graded} with the composited card; returns its path. Never throws. */
    static String render(File graded, File src, String film, String id) {
        Tpl t = load(id);
        if (t == null) {
            Engine.dbg("wm: no usable template '" + id + "' — leaving the photo unframed");
            return graded.getAbsolutePath();
        }
        Bitmap out = null;
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(graded.getAbsolutePath(), b);
            int pw = b.outWidth, ph = b.outHeight;
            if (pw <= 0 || ph <= 0) return graded.getAbsolutePath();

            // portrait fit: a portrait shot takes the template's reciprocal aspect and the design is
            // RE-ANCHORED per axis in short-edge units — nothing is rotated, so whatever sat on the
            // top / bottom edge stays there (the bars and their text keep their orientation).
            boolean portrait = t.padapt && ph > pw;
            float ar = Math.max(0.05f, t.ar);
            List<El> els = portrait ? mapPortrait(t.els, ar) : t.els;
            El photo = null;
            for (El e : els) if ("photo".equals(e.type)) { photo = e; break; }

            // canvas: the photo slot's pixel height follows the photo, the card is capped at MAX_EDGE
            // card size: landscape follows the photo slot height as before; in portrait the card is
            // sized so the photo band keeps 1:1 pixels across its width (no upscaling)
            float ch, cw;
            if (portrait) {
                float bw = (photo != null && photo.w > 0.05f) ? photo.w : 1f;
                cw = Math.min((float) MAX_EDGE, Math.max(2f, pw / Math.min(1f, bw)));
                ch = cw * ar;
            } else {
                ch = Math.min(ph, 2400f) / Math.max(0.05f, (photo != null && photo.h > 0.05f) ? photo.h : 1f);
                cw = ch * ar;
            }
            if (cw > MAX_EDGE) { float k = MAX_EDGE / cw; cw = MAX_EDGE; ch *= k; }
            if (ch > MAX_EDGE) { float k = MAX_EDGE / ch; ch = MAX_EDGE; cw *= k; }
            int CW = Math.max(2, Math.round(cw)), CH = Math.max(2, Math.round(ch));
            int DW = CW, DH = CH;
            int mn = Math.min(DW, DH);

            Bitmap buf = Bitmap.createBitmap(DW, DH, Bitmap.Config.ARGB_8888);
            sTarget = buf;                                  // adaptive text reads the pixels under it
            Canvas cv = new Canvas(buf);
            cv.drawColor(t.bg);

            int idx = 0;
            boolean toneReady = false, photoPainted = false;
            for (El el : els) {
                if (!toneReady && photoPainted && adapts(el)) {
                    sBgGlobal = globalTone(els, DW, DH);   // the photo is under the overlays by now
                    toneReady = true;
                }
                RectF r = new RectF(el.x * DW, el.y * DH, (el.x + el.w) * DW, (el.y + el.h) * DH);
                if (r.width() < 4f || r.height() < 4f) continue;
                if ("photo".equals(el.type)) drawPhoto(cv, el, t, graded, mn, ++idx);
                else if ("img".equals(el.type)) drawImg(cv, el, t, mn);
                else if ("line".equals(el.type)) drawLine(cv, el, DW, DH);
                else if ("rect".equals(el.type)) drawRectEl(cv, el, DW, DH, mn, ++idx, t.bg);
                else if ("logo".equals(el.type)) drawLogo(cv, el, DW, DH, mn, src);
                else drawText(cv, el, t, DW, DH, mn, src, film, ++idx);
                if ("photo".equals(el.type)) photoPainted = true;
            }
            out = buf;

            FileOutputStream fo = new FileOutputStream(graded);
            out.compress(Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            return graded.getAbsolutePath();
        } catch (Throwable ex) {
            Engine.dbg("wm render " + id + ": " + ex);
            MainActivity.say("wm render " + ex);
            return graded.getAbsolutePath();
        } finally {
            sTarget = null;
            sBgGlobal = null;
            if (out != null) out.recycle();
        }
    }


    // ---------------------------------------------------------------- fonts

    /** set once from MainActivity.onCreate: res/raw fonts need an app context to extract */
    static android.content.Context ctx;
    private static final java.util.HashMap<String, android.graphics.Typeface> FACES =
            new java.util.HashMap<String, android.graphics.Typeface>();

    /** template text faces: system Noto families plus the bundled OFL faces (res/raw) */
    static final String[] FONT_IDS = {"sans", "serif", "fangsong", "smiley", "caoshu", "wqy",
            "poppins", "oswald", "archivo", "bebas",
            "playfair", "baskerville", "lora", "garamond", "script",
            "mono", "jbmono", "dseg14"};

    static android.graphics.Typeface face(String id, boolean bold) {
        String k = (id == null || id.length() == 0 ? "sans" : id) + (bold ? "#B" : "#R");
        android.graphics.Typeface t = FACES.get(k);
        if (t != null) return t;
        android.graphics.Typeface base = baseFace(id);
        try { t = android.graphics.Typeface.create(base, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL); }
        catch (Throwable ig) { t = base; }
        FACES.put(k, t);
        return t;
    }

    private static android.graphics.Typeface baseFace(String id) {
        android.content.Context c = ctx;
        try {
            if ("serif".equals(id)) return android.graphics.Typeface.SERIF;
            if ("mono".equals(id)) return android.graphics.Typeface.MONOSPACE;
            if ("wqy".equals(id) && c != null) return Ux.wqyZen(c);
            if ("archivo".equals(id) && c != null) return Ux.archivo(c);
            if ("fangsong".equals(id) && c != null) return rawFace(c, "zhuquefangsong.ttf", R.raw.zhuquefangsong);
            if ("smiley".equals(id) && c != null) return rawFace(c, "smileysans.ttf", R.raw.smileysans);
            if ("caoshu".equals(id) && c != null) return rawFace(c, "zhimangxing.ttf", R.raw.zhimangxing);         // 志莽行书（行草）
            if ("script".equals(id) && c != null) return rawFace(c, "greatvibes.ttf", R.raw.greatvibes);        // Great Vibes（花体）
            if ("poppins".equals(id) && c != null) return rawFace(c, "poppins.ttf", R.raw.poppins);
            if ("oswald".equals(id) && c != null) return rawFace(c, "oswald.ttf", R.raw.oswald);
            if ("playfair".equals(id) && c != null) return rawFace(c, "playfair.ttf", R.raw.playfair);
            if ("baskerville".equals(id) && c != null) return rawFace(c, "baskerville.ttf", R.raw.baskerville);
            if ("lora".equals(id) && c != null) return rawFace(c, "lora.ttf", R.raw.lora);
            if ("garamond".equals(id) && c != null) return rawFace(c, "garamond.ttf", R.raw.garamond);
            if ("bebas".equals(id) && c != null) return rawFace(c, "bebas.ttf", R.raw.bebas);
            if ("jbmono".equals(id) && c != null) return rawFace(c, "jbmono.ttf", R.raw.jbmono);
            if ("dseg14".equals(id) && c != null) return rawFace(c, "dseg14classic.ttf", R.raw.dseg14classic);
        } catch (Throwable ig) {}
        return android.graphics.Typeface.SANS_SERIF;
    }

    private static android.graphics.Typeface rawFace(android.content.Context c, String name, int res) throws Exception {
        java.io.File f = new java.io.File(c.getCacheDir(), name);
        if (!f.exists() || f.length() == 0) {
            java.io.InputStream in = c.getResources().openRawResource(res);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close(); in.close();
        }
        return android.graphics.Typeface.createFromFile(f);
    }

    // ---------------------------------------------------------------- photo element

    private static void drawPhoto(Canvas cv, El el, Tpl t, File graded, int mn, int idx) {
        RectF r = new RectF(el.x * cv.getWidth(), el.y * cv.getHeight(),
                (el.x + el.w) * cv.getWidth(), (el.y + el.h) * cv.getHeight());
        float[] eg = edgeGeom(mn, el);
        int amp = Math.round(eg[0]);
        float fade = eg[1], freq = eg[2];
        long seed = 0x0F6A1E5AL + idx * 977L;

        RectF vis = cropRect(r, el);          // the crop viewport (== the slot when there is no crop)

        if (el.shOn) drawPhotoShadow(cv, vis, el, mn, amp, fade, seed, freq);

        Bitmap bm = null;
        try {
            // cover-crop to the SLOT (not to the viewport): the crop only masks, it never re-scales
            bm = decodeCover(graded, Math.max(2, Math.round(r.width())), Math.max(2, Math.round(r.height())));
            if (bm == null) return;
            if (amp > 0 || fade >= 1f) {
                cv.save();
                RectF ph = new RectF(r.left - amp, r.top - amp, r.right + amp, r.bottom + amp);
                // the layer bounds clip twice: to the viewport (mask) and with 2px slack for the cut
                RectF lay = new RectF(vis.left - amp - 2, vis.top - amp - 2,
                        vis.right + amp + 2, vis.bottom + amp + 2);
                cv.saveLayer(lay, null);
                cv.drawBitmap(bm, null, ph, FILTER);
                edgeBands(cv, vis, t.bg, mn, amp, fade, seed, freq);
                cv.restore();
                cv.restore();
            } else {
                cv.save();
                cv.clipRect(vis);
                cv.drawBitmap(bm, null, r, FILTER);
                cv.restore();
            }
        } catch (Throwable ex) {
            Engine.dbg("wm photo " + ex);
        } finally {
            if (bm != null) bm.recycle();
        }
    }

    /** {amp px, fade px, noise frequency scale}. Soft: feather radius = 2.2% of the short side x size.
     *  Jagged: a geometric zoom of the half-frame tear — amplitude x size AND wavelength x size
     *  (frequency / size), so the teeth get bigger without getting denser. size 0 = no edge (old "hard"). */
    static float[] edgeGeom(int mn, El el) {
        float k = Math.max(0f, Math.min(5f, el.edgeSize));
        if ("jagged".equals(el.edge)) {
            if (k <= 0.001f) return new float[]{0f, 0f, 1f};
            return new float[]{Math.max(1f, Math.round(tornAmp(mn) * k)), tornFade(mn) * k, 1f / k};
        }
        return new float[]{0f, mn * 0.02f * k, 1f};     // k: 1 = 2% feather, 5 = 10% (the cap)
    }

    /** torn-edge amplitude, EXACTLY the half-frame border's (Server.composeHalf): mn/300 x 3/4 */
    static int tornAmp(int mn) { return Math.max(1, Math.round(Math.max(2, mn / 300) * 0.5625f)); }

    /** torn-edge fade, EXACTLY the half-frame border's (Server.dressHalf): (mn/300) x 3/8 */
    static float tornFade(int mn) { return Math.max(1f, Math.round(Math.max(2, mn / 300) * 0.375f)); }

    /** the photo edge is CUT (alpha erased), not painted over: the card and any shadow underneath stay
     *  visible through the nibbled band, so a cast shadow never ends on a straight line.
     *  amp 0 + a wide fade = the soft edge; amp > 0 = the half-frame style torn edge. */
    static void edgeBands(Canvas cv, RectF r, int bg, int mn, int amp, float fade, long seed, float freq) {   // shared with the editor preview
        Paint p = new Paint();
        p.setAntiAlias(false);      // AA seams between adjacent bands would leave a 25% alpha residue
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        float ext = amp + 3f;       // erase PAST the slot: a hard clip at the slot edge would leave a 1px ring
        int solid = 0xFF000000, clear = 0x00000000;
        final int step = 2;          // same 2px bands as Server.dressHalf (half-frame border)
        int h = Math.max(2, (int) r.height()), w = Math.max(2, (int) r.width());
        float[] nl = wavh(h, amp, seed * 31 + 7, freq);
        float[] nr = wavh(h, amp, seed * 131 + 17, freq);
        float[] nt = wavh(w, amp, seed * 61 + 23, freq);
        float[] nb = wavh(w, amp, seed * 97 + 41, freq);

        for (int i = 0; i < h; i += step) {
            float y0 = Math.round(r.top + i), y1 = Math.round(Math.min(r.bottom + ext, r.top + i + step));
            // left
            float b = r.left + nl[i];
            if (b > r.left - ext) { p.setShader(null); p.setColor(solid); cv.drawRect(r.left - ext, y0, b, y1, p); }
            p.setShader(new LinearGradient(b, 0, b + fade, 0, solid, clear, Shader.TileMode.CLAMP));
            cv.drawRect(b, y0, Math.min(r.right, b + fade), y1, p);
            // right
            float b2 = r.right + nr[i];
            if (b2 < r.right + ext) { p.setShader(null); p.setColor(solid); cv.drawRect(b2, y0, r.right + ext, y1, p); }
            p.setShader(new LinearGradient(b2, 0, b2 - fade, 0, solid, clear, Shader.TileMode.CLAMP));
            cv.drawRect(Math.max(r.left, b2 - fade), y0, b2, y1, p);
        }
        for (int i = 0; i < w; i += step) {
            float x0 = Math.round(r.left + i), x1 = Math.round(Math.min(r.right + ext, r.left + i + step));
            // top
            float b = r.top + nt[i];
            if (b > r.top - ext) { p.setShader(null); p.setColor(solid); cv.drawRect(x0, r.top - ext, x1, b, p); }
            p.setShader(new LinearGradient(0, b, 0, b + fade, solid, clear, Shader.TileMode.CLAMP));
            cv.drawRect(x0, b, x1, Math.min(r.bottom, b + fade), p);
            // bottom
            float b2 = r.bottom + nb[i];
            if (b2 < r.bottom + ext) { p.setShader(null); p.setColor(solid); cv.drawRect(x0, b2, x1, r.bottom + ext, p); }
            p.setShader(new LinearGradient(0, b2, 0, b2 - fade, solid, clear, Shader.TileMode.CLAMP));
            cv.drawRect(x0, Math.max(r.top, b2 - fade), x1, b2, p);
        }
        if (amp > 0) {              // the four outward corner squares belong to no band
            p.setShader(null);
            p.setColor(solid);
            cv.drawRect(r.left - ext, r.top - ext, r.left, r.top, p);
            cv.drawRect(r.right, r.top - ext, r.right + ext, r.top, p);
            cv.drawRect(r.left - ext, r.bottom, r.left, r.bottom + ext, p);
            cv.drawRect(r.right, r.bottom, r.right + ext, r.bottom + ext, p);
        }
        p.setShader(null);
        p.setXfermode(null);
    }

    /** coverage of the photo silhouette at a content point: 0 outside the (noise warped, faded) edge,
     *  1 well inside it. Uses the very wave tables edgeBands cuts with, so shadow and paper agree. */
    private static float covAt(float x, float y, RectF r, int w, int h, float fade,
                               float[] nl, float[] nr, float[] nt, float[] nb) {
        int iy = (int) Math.max(0, Math.min(h - 1, Math.round(y - r.top)));
        int ix = (int) Math.max(0, Math.min(w - 1, Math.round(x - r.left)));
        float f = Math.max(0.5f, fade);
        float m = Math.min(Math.min(x - (r.left + nl[iy]), (r.right + nr[iy]) - x),
                Math.min(y - (r.top + nt[ix]), (r.bottom + nb[ix]) - y)) / f;
        return m <= 0f ? 0f : (m >= 1f ? 1f : m);
    }

    /** the shadow is the blurred, offset copy of the photo's own silhouette: a torn edge casts a torn
     *  shadow, a feathered edge casts a feathered one, and nothing ends on a straight line. */
    static void drawPhotoShadow(Canvas cv, RectF r, El el, int mn, int amp, float fade, long seed, float freq) {   // shared with the editor preview
        Bitmap mask = null;
        try {
            float blur = Math.max(0.5f, el.shBlur * mn);
            float dx = el.shDx * mn, dy = el.shDy * mn;
            int w = Math.max(2, (int) r.width()), h = Math.max(2, (int) r.height());
            float[] nl = wavh(h, amp, seed * 31 + 7, freq);
            float[] nr = wavh(h, amp, seed * 131 + 17, freq);
            float[] nt = wavh(w, amp, seed * 61 + 23, freq);
            float[] nb = wavh(w, amp, seed * 97 + 41, freq);
            float pad = blur * 2.5f + 2f;
            float L = r.left - amp - pad, T = r.top - amp - pad;
            float R = r.right + amp + pad, B = r.bottom + amp + pad;
            float sc = Math.min(1f, 600f / Math.max(R - L, B - T));   // the shadow is soft: a coarse mask is enough
            int mw = Math.max(4, Math.round((R - L) * sc)), mh = Math.max(4, Math.round((B - T) * sc));
            int[] px = new int[mw * mh];
            for (int j = 0; j < mh; j++) {
                float y = T + (j + 0.5f) / sc;
                int row = j * mw;
                for (int i = 0; i < mw; i++) {
                    float x = L + (i + 0.5f) / sc;
                    px[row + i] = Math.round(covAt(x, y, r, w, h, fade, nl, nr, nt, nb) * 255f) << 24;
                }
            }
            int br = Math.max(1, Math.round(blur * sc));
            blurAlpha(px, mw, mh, br);
            blurAlpha(px, mw, mh, Math.max(1, Math.round(br * 0.5f)));
            int col = shadowInk(el, r.left, r.top, r.width(), r.height()), ca = Color.alpha(col);
            int cr = (Color.red(col) << 16) | (Color.green(col) << 8) | Color.blue(col);
            for (int i = 0; i < px.length; i++) {
                int a = ((px[i] >>> 24) * ca) / 255;
                px[i] = (a << 24) | cr;
            }
            mask = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
            mask.setPixels(px, 0, mw, 0, 0, mw, mh);
            Paint sp = new Paint(FILTER);
            android.graphics.Paint xf = shadowXfer(el);
            if (xf != null) sp.setXfermode(xf.getXfermode());
            cv.drawBitmap(mask, null, new RectF(L + dx, T + dy, R + dx, B + dy), sp);
        } catch (Throwable ex) {
            Engine.dbg("wm photo shadow " + ex);
        } finally {
            if (mask != null) mask.recycle();
        }
    }

    private static int shadowColor(El el) { return el.shColor; }

    /** editor previews have no render bitmap to sample: fall back to the card colour */
    static float sFallbackLum = 1f;

    /** editor preview: its own crop-aware sampler decides (null = sample sTarget as usual) */
    static Boolean sBgBrightOverride = null;

    /** 明暗适配 is decided for the WHOLE photo, not per element: every adaptive element votes and the
     *  side with more sample points wins. Non-adaptive elements do not vote. null = not computed. */
    static Boolean sBgGlobal = null;

    /** the shadow's colour after the adaptive flip (alpha is kept) */
    static int shadowInk(El el, float l, float t, float w, float h) {
        int c = el.shColor;
        if (el.shBlend == 1) {
            int rgb = bgBright9(l, t, w, h) ? 0x000000 : 0xFFFFFF;
            return (c & 0xFF000000) | rgb;
        }
        return c;
    }

    /** the shadow's blend paint (null for plain compositing) */
    static android.graphics.Paint shadowXfer(El el) {
        if (el.shBlend != 2) return null;
        android.graphics.Paint p = new android.graphics.Paint();
        p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
        return p;
    }

    /** the element's crop viewport inside its slot (the slot itself when there is no crop) */
    static RectF cropRect(RectF r, El e) {
        if (e.cx0 <= 0.001f && e.cy0 <= 0.001f && e.cx1 >= 0.999f && e.cy1 >= 0.999f) return new RectF(r);
        return new RectF(r.left + e.cx0 * r.width(), r.top + e.cy0 * r.height(),
                r.left + e.cx1 * r.width(), r.top + e.cy1 * r.height());
    }

    /** crop the decoded frame to the element's viewport; returns the same bitmap when the crop is full */
    private static Bitmap applyCrop(Bitmap bm, El e) {
        if (bm == null) return null;
        if (e.cx0 <= 0.001f && e.cy0 <= 0.001f && e.cx1 >= 0.999f && e.cy1 >= 0.999f) return bm;
        int bw = bm.getWidth(), bh = bm.getHeight();
        int x0 = Math.max(0, Math.min(bw - 2, Math.round(e.cx0 * bw)));
        int y0 = Math.max(0, Math.min(bh - 2, Math.round(e.cy0 * bh)));
        int x1 = Math.max(x0 + 2, Math.min(bw, Math.round(e.cx1 * bw)));
        int y1 = Math.max(y0 + 2, Math.min(bh, Math.round(e.cy1 * bh)));
        try {
            Bitmap c = Bitmap.createBitmap(bm, x0, y0, x1 - x0, y1 - y0);
            if (c != bm) bm.recycle();
            return c;
        } catch (Throwable t) {
            return bm;
        }
    }

    // ---------------------------------------------------------------- brand logo element

    /** which logo this source photo wants, from its EXIF Make/Model ("" = none) */
    static String logoSrc(File src) {
        if (src == null) return "";
        try {
            ExifInterface e = new ExifInterface(src.getAbsolutePath());
            String make = clean(e.getAttribute(ExifInterface.TAG_MAKE));
            String model = clean(e.getAttribute(ExifInterface.TAG_MODEL));
            return Logo.slugFor(make, model);
        } catch (Throwable ig) {
            return "";
        }
    }

    /** brand logo chosen from the photo's EXIF, fitted inside the element box (aspect preserved) */
    static void drawLogo(Canvas cv, El el, int CW, int CH, int mn, File src) {
        String slug = (el.brand != null && el.brand.length() > 0) ? el.brand : logoSrc(src);
        Bitmap bm = Logo.get(slug);
        if (bm == null) bm = Logo.own();          // unknown brand: the app's own wordmark is the fallback
        if (bm == null) return;
        drawLogoBitmap(cv, el, new RectF(el.x * CW, el.y * CH, (el.x + el.w) * CW, (el.y + el.h) * CH), mn, bm);
    }

    /** draw a logo inside the element box: every brand is scaled by the SAME factor (the union box of
     *  all logos), then aligned left / centre / right. Shared with the editor preview. */
    static void drawLogoBitmap(Canvas cv, El el, RectF box, int mn, Bitmap bm) {
        drawLogoBitmap(cv, el, box, mn, bm, null);
    }

    /** @param bgBrightOverride editor preview decision (its own 5-point sampler); null = sample here */
    static void drawLogoBitmap(Canvas cv, El el, RectF box, int mn, Bitmap bm, Boolean bgBrightOverride) {
        if (bm == null) return;
        // fill the element box (aspect preserved): the widest marks touch the width, the tallest the height
        float sc = Math.min(box.width() / bm.getWidth(), box.height() / bm.getHeight());
        float dw = bm.getWidth() * sc, dh = bm.getHeight() * sc;
        float lx = "right".equals(el.align) ? box.right - dw
                : ("center".equals(el.align) ? box.centerX() - dw / 2f : box.left);
        RectF dst = new RectF(lx, box.centerY() - dh / 2f, lx + dw, box.centerY() + dh / 2f);
        if (el.blend == 1)                       // 明暗适配: 5-point vote over the mark itself
            bm = Logo.adapt(bm, bgBrightOverride != null ? bgBrightOverride
                    : bgBright9(dst.left, dst.top, dst.width(), dst.height()), el.brand);
        // same unit as every other element (the card short side): the same blur value must give the
        // same blur distance everywhere; drawImgShadow pads for the blur so nothing is clipped
        if (el.shOn) drawImgShadow(cv, bm, dst, el, mn);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        p.setAlpha(Math.round(255f * el.opacity));
        cv.drawBitmap(bm, null, dst, p);
    }

    // ---------------------------------------------------------------- rule / line element

    /** horizontal / vertical rule: a plain filled rect (the box IS the line; thin by design) */
    /** rule shadow: a blurred, offset copy of the rule's rect (the box is already the exact shape) */
    static void drawLineShadow(Canvas cv, RectF r, El el, int mn) {
        try {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(shadowInk(el, r.left, r.top, r.width(), r.height()));
            android.graphics.Paint xf = shadowXfer(el);
            if (xf != null) p.setXfermode(xf.getXfermode());
            // a rule is only a few px thick: the card-scale blur (tens of px) would smear it into
            // nothing, so cap the blur at twice the rule's own thickness
            float th = Math.max(1f, Math.min(r.width(), r.height()));
            float blur = Math.min(Math.max(0.5f, el.shBlur * mn), Math.max(1.5f, th * 2f));
            p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
            float dx = el.shDx * mn, dy = el.shDy * mn;
            cv.drawRect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy, p);
        } catch (Throwable ig) {}
    }

    /** plain filled rectangle: colour (明暗适配 / screen), opacity, the photo-style edge treatment
     *  (soft / jagged) and a shadow that follows that edge's silhouette */
    private static void drawRectEl(Canvas cv, El el, int CW, int CH, int mn, int idx, int bg) {
        RectF r = new RectF(el.x * CW, el.y * CH, (el.x + el.w) * CW, (el.y + el.h) * CH);
        float[] eg = edgeGeom(mn, el);
        int amp = Math.round(eg[0]);
        float fade = eg[1], freq = eg[2];
        long seed = 0x5EC7A11AL + idx * 977L;
        if (el.shOn) drawPhotoShadow(cv, r, el, mn, amp, fade, seed, freq);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int ink = el.color;
        if (el.blend == 1) ink = bgBright9(r.left, r.top, r.width(), r.height()) ? 0xFF000000 : 0xFFFFFFFF;
        int a = Math.round(Color.alpha(el.color) * el.alpha);
        p.setColor((ink & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24));
        if (amp > 0 || fade >= 1f) {
            RectF lay = new RectF(r.left - amp - 2, r.top - amp - 2, r.right + amp + 2, r.bottom + amp + 2);
            Paint lp = null;
            if (el.blend == 2) {                    // screen must apply when the layer is composited
                lp = new Paint();
                lp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
            }
            cv.save();
            cv.saveLayer(lay, lp);
            cv.drawRect(r, p);
            edgeBands(cv, r, bg, mn, amp, fade, seed, freq);
            cv.restore();
            cv.restore();
            return;
        }
        if (el.blend == 2) p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
        cv.drawRect(r, p);
    }

    private static void drawLine(Canvas cv, El el, int CW, int CH) {
        RectF r = new RectF(el.x * CW, el.y * CH, (el.x + el.w) * CW, (el.y + el.h) * CH);
        if (el.shOn) drawLineShadow(cv, r, el, Math.min(CW, CH));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int ink = el.color;
        if (el.blend == 1) ink = bgBright9(r.left, r.top, r.width(), r.height()) ? 0xFF000000 : 0xFFFFFFFF;
        int a = Math.round(Color.alpha(el.color) * el.alpha);
        p.setColor((ink & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24));
        if (el.blend == 2) p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
        cv.drawRect(r, p);
    }

    /** a rule is vertical when its box is taller than wide. The thin side decides — never the stored
     *  `dir`, which is only a UI hint (and may be stale in older templates). */
    static boolean lineIsV(float wPx, float hPx) { return hPx > wPx; }

    /** the rule's segment inside a box of w x h pixels (used by the editor chrome) */
    static float[] lineSeg(float x, float y, float w, float h) {
        return lineIsV(w, h) ? new float[]{x + w / 2f, y, x + w / 2f, y + h}
                : new float[]{x, y + h / 2f, x + w, y + h / 2f};
    }

    // ---------------------------------------------------------------- image element

    private static void drawImg(Canvas cv, El el, Tpl t, int mn) {
        if (el.src == null) return;
        File f = new File(new File(ROOT, t.id), el.src);
        if (!f.isFile()) return;
        Bitmap bm = null;
        try {
            bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bm == null) return;
            RectF r = new RectF(el.x * cv.getWidth(), el.y * cv.getHeight(),
                    (el.x + el.w) * cv.getWidth(), (el.y + el.h) * cv.getHeight());
            RectF vis = cropRect(r, el);
            if (el.shOn) {
                Bitmap sc = applyCrop(bm, el);     // shadow of the VISIBLE part only
                if (sc != null) {
                    drawImgShadow(cv, sc, vis, el, mn);
                    if (sc != bm) sc.recycle();
                }
            }
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            p.setAlpha(Math.round(255f * el.opacity));
            cv.save();
            cv.clipRect(vis);
            cv.drawBitmap(bm, null, r, p);
            cv.restore();
        } catch (Throwable ex) {
            Engine.dbg("wm img " + ex);
        } finally {
            if (bm != null) bm.recycle();
        }
    }

    /** drawBitmap ignores MaskFilter, so the alpha silhouette is rendered small, blurred, then scaled up */
    static void drawImgShadow(Canvas cv, Bitmap bm, RectF r, El el, int mn) {
        Bitmap small = null;
        try {
            float blurPx = Math.max(0.5f, el.shBlur * mn);
            float dx = el.shDx * mn, dy = el.shDy * mn;
            float pad = blurPx * 2.5f + 2f + Math.max(Math.abs(dx), Math.abs(dy));
            RectF outer = new RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad);
            float sc = Math.min(1f, 384f / Math.max(outer.width(), outer.height()));
            int sw = Math.max(4, Math.round(outer.width() * sc));
            int sh = Math.max(4, Math.round(outer.height() * sc));
            small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(small);
            // the mark sits in the middle of the padded canvas, so the blur can spread outside it
            RectF inner = new RectF((r.left - outer.left) * sc, (r.top - outer.top) * sc,
                    (r.right - outer.left) * sc, (r.bottom - outer.top) * sc);
            c.drawBitmap(bm, null, inner, FILTER);
            int[] px = new int[sw * sh];
            small.getPixels(px, 0, sw, 0, 0, sw, sh);
            int col = shadowInk(el, r.left, r.top, r.width(), r.height()), ca = Color.alpha(col);
            int cr = Color.red(col), cg = Color.green(col), cb = Color.blue(col);
            for (int i = 0; i < px.length; i++) {
                int a = (px[i] >>> 24) * ca / 255;
                px[i] = (a << 24) | (cr << 16) | (cg << 8) | cb;
            }
            int br = Math.max(1, Math.round(blurPx * sc));
            blurAlpha(px, sw, sh, br);
            blurAlpha(px, sw, sh, Math.max(1, Math.round(br * 0.5f)));
            small.setPixels(px, 0, sw, 0, 0, sw, sh);
            RectF dst = new RectF(outer.left + dx, outer.top + dy, outer.right + dx, outer.bottom + dy);
            Paint sp = new Paint(FILTER);
            android.graphics.Paint xf = shadowXfer(el);
            if (xf != null) sp.setXfermode(xf.getXfermode());
            cv.drawBitmap(small, null, dst, sp);
        } catch (Throwable ex) {
            Engine.dbg("wm img shadow " + ex);
        } finally {
            if (small != null) small.recycle();
        }
    }

    /** separable sliding-window box blur on the alpha channel only (RGB is a constant shadow colour) */
    private static void blurAlpha(int[] px, int w, int h, int r) {
        if (r <= 0) return;
        int[] a = new int[w * h], b = new int[w * h];
        for (int i = 0; i < a.length; i++) a[i] = px[i] >>> 24;
        for (int y = 0; y < h; y++) {
            int row = y * w, sum = 0, cnt = 0;
            for (int x = 0; x < w; x++) {
                if (x == 0) {
                    for (int k = 0; k <= Math.min(r, w - 1); k++) { sum += a[row + k]; cnt++; }
                } else {
                    int add = x + r, rem = x - r - 1;
                    if (add < w) { sum += a[row + add]; cnt++; }
                    if (rem >= 0) { sum -= a[row + rem]; cnt--; }
                }
                b[row + x] = sum / Math.max(1, cnt);
            }
        }
        for (int x = 0; x < w; x++) {
            int sum = 0, cnt = 0;
            for (int y = 0; y < h; y++) {
                if (y == 0) {
                    for (int k = 0; k <= Math.min(r, h - 1); k++) { sum += b[k * w + x]; cnt++; }
                } else {
                    int add = y + r, rem = y - r - 1;
                    if (add < h) { sum += b[add * w + x]; cnt++; }
                    if (rem >= 0) { sum -= b[rem * w + x]; cnt--; }
                }
                a[y * w + x] = sum / Math.max(1, cnt);
            }
        }
        for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0x00FFFFFF) | (a[i] << 24);
    }

    // ---------------------------------------------------------------- text element

    private static void drawText(Canvas cv, El el, Tpl t, int CW, int CH, int mn, File src, String film, int idx) {
        String txt = tokens(pickVariant(el.text), src, film);
        if (txt == null || txt.isEmpty()) return;
        try {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(face(el.font, el.bold));
            float ts = Math.max(4f, el.size * CH);
            p.setTextSize(ts);
            String[] lines = txt.split("\n", -1);
            float lh = ts * 1.25f;
            float top = el.y * CH;
            float left = el.x * CW, boxW = el.w * CW;
            int ink = el.color;
            if (el.blend == 1) {                       // adaptive: pick black or white so the text stands out
                ink = bgBright9(left, top, boxW, lines.length * lh) ? 0xFF000000 : 0xFFFFFFFF;
            }
            int a = Math.round(android.graphics.Color.alpha(el.color) * el.alpha);
            p.setColor((ink & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24));
            if (el.shOn && el.blend != 2 && el.shBlend != 2) {   // screen + shadow would only brighten the backdrop
                int sc = shadowInk(el, left, top, boxW, lines.length * lh);
                int sa = Math.round(android.graphics.Color.alpha(sc) * el.alpha);
                p.setShadowLayer(Math.max(0.5f, el.shBlur * mn), el.shDx * mn, el.shDy * mn,
                        (sc & 0x00FFFFFF) | (Math.max(0, Math.min(255, sa)) << 24));
            }
            if (el.blend == 2) p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
            for (int i = 0; i < lines.length; i++) {
                float lineW = p.measureText(lines[i]);
                float x = left;
                if ("center".equals(el.align)) x = left + (boxW - lineW) / 2f;
                else if ("right".equals(el.align)) x = left + boxW - lineW;
                cv.drawText(lines[i], x, top + ts + i * lh, p);
            }
        } catch (Throwable ex) {
            Engine.dbg("wm text " + ex);
        }
    }

    /** "a | b | c" -> one segment at random, equal chance each; every segment is trimmed */
    static String pickVariant(String s) {
        if (s == null) return "";
        if (s.indexOf('|') < 0) return s;
        String[] parts = s.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts[(int) (Math.random() * parts.length) % parts.length];
    }

    /**
     * Background tone under a box: a 3x3 grid (four corners, four edge midpoints, centre) is sampled
     * and VOTED on — five or more of the nine decide (minority obeys majority). The mean luminance is
     * only used when no class reaches five votes.
     * @return true when the background is "bright" there
     */
    static boolean bgBright9(float l, float t, float w, float h) {
        if (sBgBrightOverride != null) return sBgBrightOverride;
        if (sBgGlobal != null) return sBgGlobal;          // one tone for the whole photo
        float[] v = new float[3];
        toneVotes(l, t, w, h, v);
        return toneVerdict(v);
    }

    /** adds the 9 points of one box to {bright, dark, luminance sum} */
    static void toneVotes(float l, float t, float w, float h, float[] acc) {
        Bitmap b = sTarget;
        float[] xs = {l, l + w / 2f, l + w, l, l + w / 2f, l + w, l, l + w / 2f, l + w};
        float[] ys = {t, t, t, t + h / 2f, t + h / 2f, t + h / 2f, t + h, t + h, t + h};
        for (int i = 0; i < 9; i++) {
            float lum;
            if (b == null || b.isRecycled()) {
                lum = sFallbackLum;
            } else {
                int x = Math.max(0, Math.min(b.getWidth() - 1, Math.round(xs[i])));
                int y = Math.max(0, Math.min(b.getHeight() - 1, Math.round(ys[i])));
                int c = 0;
                try { c = b.getPixel(x, y); } catch (Throwable ig) {}
                lum = (0.299f * android.graphics.Color.red(c)
                        + 0.587f * android.graphics.Color.green(c)
                        + 0.114f * android.graphics.Color.blue(c)) / 255f;
            }
            acc[2] += lum;
            if (lum > 0.5f) acc[0]++; else acc[1]++;
        }
    }

    /** the aggregate verdict: more bright points than dark ones (a tie falls back to the mean) */
    static boolean toneVerdict(float[] v) {
        if (v[0] != v[1]) return v[0] > v[1];
        float n = v[0] + v[1];
        return n > 0 && v[2] / n > 0.5f;
    }

    /**
     * The frame's single 明暗 decision: every element with 明暗适配 enabled (its ink flips, or its
     * shadow's ink does) contributes its 9 points here; elements without it do not take part.
     */
    /** 明暗适配 can only change an overlay's ink; a photo has nothing to flip and never votes */
    static boolean adapts(El e) {
        return !"photo".equals(e.type) && !"img".equals(e.type) && (e.blend == 1 || e.shBlend == 1);
    }

    static Boolean globalTone(List<El> els, int CW, int CH) {
        float[] acc = new float[3];
        int voters = 0;
        for (El e : els) {
            if (!adapts(e)) continue;
            float x = e.x * CW, y = e.y * CH;
            float w = Math.max(2f, e.w * CW), h = Math.max(2f, e.h * CH);
            toneVotes(x, y, w, h, acc);
            voters++;
        }
        return voters == 0 ? null : Boolean.valueOf(toneVerdict(acc));
    }

    /** {token} substitution: EXIF-derived info, the film name, and the marketing model name */
    static String tokens(String s, File src, String film) {
        if (s == null) return "";
        if (s.indexOf('{') < 0) return s;
        Map<String, String> m = exifVals(src);
        m.put("film", film == null ? "" : film);
        StringBuilder out = new StringBuilder(s.length() + 32);
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf('{', i);
            if (a < 0) { out.append(s, i, s.length()); break; }
            int b = s.indexOf('}', a + 1);
            if (b < 0) { out.append(s, i, s.length()); break; }
            out.append(s, i, a);
            String key = s.substring(a + 1, b).trim().toLowerCase(Locale.US);
            String v = m.get(key);
            out.append(v != null ? v : s.substring(a, b + 1));
            i = b + 1;
        }
        return out.toString();
    }

    private static Map<String, String> exifVals(File f) {
        Map<String, String> m = new HashMap<String, String>();
        if (f == null) return m;
        try {
            ExifInterface e = new ExifInterface(f.getAbsolutePath());
            m.put("make", clean(e.getAttribute(ExifInterface.TAG_MAKE)));
            String model = clean(e.getAttribute(ExifInterface.TAG_MODEL));
            m.put("model", marketingModel(model));
            m.put("lens", clean(e.getAttribute("LensModel")));
            m.put("shutter", shutter(e.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)));
            m.put("aperture", fnum(e.getAttribute(ExifInterface.TAG_F_NUMBER)));
            m.put("iso", iso(e.getAttribute(ExifInterface.TAG_ISO)));
            String dt = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
            if (dt == null) dt = e.getAttribute(ExifInterface.TAG_DATETIME);
            if (dt != null && dt.length() >= 19) {
                m.put("date", dt.substring(0, 4) + "." + dt.substring(5, 7) + "." + dt.substring(8, 10));
                m.put("time", dt.substring(11, 16));
            }
            String exp = readExposureText(f);
            if (exp != null) m.put("exposure", exp);
        } catch (Throwable ig) {}
        for (Map.Entry<String, String> e : m.entrySet()) if (e.getValue() == null) e.setValue("");
        return m;
    }

    private static String readExposureText(File f) {
        try { return Server.readExposure(f); } catch (Throwable t) { return null; }
    }

    private static String clean(String s) {
        if (s == null) return "";
        int i = s.indexOf('\u0000');
        if (i >= 0) s = s.substring(0, i);
        return s.trim();
    }

    private static String shutter(String v) {
        if (v == null) return "";
        try {
            double d = Double.parseDouble(v.trim());
            if (d <= 0) return "";
            return d < 1 ? ("1/" + Math.round(1 / d) + "s") : (Math.round(d) + "s");
        } catch (Throwable ig) { return ""; }
    }

    private static String fnum(String v) {
        if (v == null || v.trim().isEmpty()) return "";
        return "F" + v.trim();
    }

    private static String iso(String v) {
        if (v == null || v.trim().isEmpty()) return "";
        return "ISO" + v.trim();
    }

    /** EXIF model -> marketing name via the device-side table; raw model when unknown */
    private static String marketingModel(String model) {
        if (model == null || model.isEmpty()) return "";
        Map<String, String> db = modelDb();
        if (db != null) {
            String hit = db.get(normModel(model));
            if (hit != null) return hit;
        }
        return model;
    }

    private static String normModel(String s) {
        String u = s.toUpperCase(Locale.US).replace("DIGITAL", " ");
        return u.replaceAll("\\s+", " ").trim();
    }

    private static Map<String, String> modelDb() {
        try {
            long lm = MODEL_DB.lastModified();
            if (models != null && lm == modelsStamp) return models;
            if (!MODEL_DB.isFile()) { models = null; modelsStamp = lm; return null; }
            JSONObject root = new JSONObject(readText(MODEL_DB));
            Map<String, String> m = new HashMap<String, String>();
            java.util.Iterator<String> it = root.keys();
            while (it.hasNext()) {
                String sec = it.next();
                if (sec.startsWith("_")) continue;
                JSONObject o = root.optJSONObject(sec);
                if (o == null) continue;
                java.util.Iterator<String> kt = o.keys();
                while (kt.hasNext()) {
                    String k = kt.next();
                    if (k.startsWith("_")) continue;
                    JSONObject v = o.optJSONObject(k);
                    String name = v == null ? null : v.optString("n", null);
                    if (name != null && !name.isEmpty()) m.put(normModel(k), name);
                }
            }
            models = m;
            modelsStamp = lm;
            return m;
        } catch (Throwable t) {
            Engine.dbg("wm model db " + t);
            return models;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** decode the CENTRAL cover-crop of the photo at roughly the slot size.
     *  The pipeline hands us upright pixels (the camera bakes the orientation into the pixels and the
     *  EXIF tag is gone), so nothing is rotated here — a portrait file is simply a portrait photo. */
    private static Bitmap decodeCover(File f, int tw, int th) {
        BitmapRegionDecoder rd = null;
        try {
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), b);
            int iw = b.outWidth, ih = b.outHeight;
            if (iw <= 0 || ih <= 0) return null;
            float slotA = tw / (float) Math.max(1, th), srcA = iw / (float) ih;
            int cw, ch;
            if (srcA > slotA) { ch = ih; cw = Math.max(1, Math.round(ih * slotA)); }
            else { cw = iw; ch = Math.max(1, Math.round(iw / slotA)); }
            int cx = (iw - cw) / 2, cy = (ih - ch) / 2;

            int s = 1;
            while (cw / (s * 2) >= tw && ch / (s * 2) >= th) s *= 2;

            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = s;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            rd = BitmapRegionDecoder.newInstance(f.getAbsolutePath(), false);
            return rd.decodeRegion(new Rect(cx, cy, cx + cw, cy + ch), o);
        } catch (Throwable ex) {
            Engine.dbg("wm decode " + ex);
            return null;
        } finally {
            if (rd != null) rd.recycle();
        }
    }

    private static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(1 << 20, f.length())];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, "UTF-8");
        } finally { in.close(); }
    }

    /** low + high frequency noise profile (same recipe as Server.wavh, amplitude `amp`, mean 0) */
    private static float[] wavh(int n, float amp, long seed, float freq) {
        if (amp <= 0) return new float[Math.max(1, n)];
        java.util.Random r = new java.util.Random(seed);
        float fre = Math.max(0.05f, freq);
        float[] lo = octaveSum(n, r, 4.5f * fre, 2, 0.6f);
        float[] hi = octaveSum(n, r, 36f * fre, 5, 0.75f);
        normRms(lo); normRms(hi);
        float[] out = new float[n];
        float mx = 1e-6f;
        for (int i = 0; i < n; i++) { out[i] = 0.5f * lo[i] + hi[i]; mx = Math.max(mx, Math.abs(out[i])); }
        float s = amp / mx;
        for (int i = 0; i < n; i++) out[i] *= s;
        return out;
    }

    private static float[] octaveSum(int n, java.util.Random r, float freq, int octs, float pers) {
        float[] o = new float[n];
        float a = 1f;
        for (int k = 0; k < octs; k++) {
            int m = Math.max(2, (int) Math.ceil(freq));
            if (n / (float) m < 3f) { freq *= 2f; a *= pers; continue; }   // finer than ~3px: sub-pixel, skip it
            float[] lat = new float[m + 2];
            for (int i = 0; i <= m + 1; i++) lat[i] = (float) r.nextGaussian();
            for (int i = 0; i < n; i++) {
                float t = (i / (float) Math.max(1, n - 1)) * freq;         // fractional cycles: smooth scaling
                int i0 = (int) t; if (i0 < 0) i0 = 0; if (i0 > m) i0 = m;
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
}
