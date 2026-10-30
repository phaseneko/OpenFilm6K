package com.openfilm6k.host;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;

/**
 * Camera-brand logos for the watermark cards.
 *
 * The logos are NOT shipped in the APK (trademark reasons). The user downloads them from
 * Wikimedia Commons (public-domain files) over their own network connection — the download is a
 * user action, triggered explicitly from the editor. Files land in
 * /sdcard/OpenFilm6K/logos/<slug>.png and are picked at render time from the photo's EXIF.
 */
class Logo {

    /** slug + Wikimedia Commons file. The SVG is fetched through Special:FilePath, which serves a PNG. */
    static final String[][] BRANDS = {
            {"openfilm6k", "", "OpenFilm6K", "OpenFilm6K"},   // the app's own mark (never inverted, never downloaded)
            {"canon", "File:Canon logo.svg", "佳能 Canon", "Canon"},
            {"nikon", "File:Nikon Logo.svg", "尼康 Nikon", "Nikon"},
            {"sony", "File:Sony logo.svg", "索尼 Sony", "Sony"},
            {"fujifilm", "File:Fujifilm logo.svg", "富士 Fujifilm", "Fujifilm"},
            {"panasonic", "File:Panasonic logo.svg", "松下 Panasonic", "Panasonic"},
            {"lumix", "File:Lumix logo.svg", "LUMIX", "LUMIX"},
            {"olympus", "File:Olympus Corporation logo.svg", "奥林巴斯 Olympus", "Olympus"},
            {"omsystem", "File:OM-System-Logo.svg", "OM SYSTEM", "OM SYSTEM"},
            {"pentax", "File:Pentax Logo.svg", "宾得 PENTAX", "PENTAX"},
            {"ricoh", "File:Ricoh logo 2012.svg", "理光 RICOH", "RICOH"},
            {"leica", "File:Leica Camera logo.svg", "徕卡 Leica", "Leica"},
            {"sigma", "File:SIGMA CORPORATION.svg", "适马 SIGMA", "SIGMA"},
            {"tamron", "File:Tamron Logo 2019.svg", "腾龙 TAMRON", "TAMRON"},
            {"hasselblad", "File:Hasselblad logo.svg", "哈苏 Hasselblad", "Hasselblad"},
            {"casio", "File:Casio logo.svg", "卡西欧 CASIO", "CASIO"},
            {"kodak", "File:Eastman Kodak Company logo (2016)(no background).svg", "柯达 Kodak", "Kodak"},
            {"gopro", "File:GoPro logo.svg", "GoPro", "GoPro"},
            {"dji", "File:DJI Innovations logo.svg", "大疆 DJI", "DJI"},
            {"zeiss", "File:Zeiss logo.svg", "蔡司 ZEISS", "ZEISS"},
            {"minolta", "File:Minolta Logo.svg", "美能达 MINOLTA", "MINOLTA"},
            {"konica", "File:Logo Konica Minolta.svg", "柯尼卡美能达 Konica Minolta", "Konica Minolta"},
            {"yashica", "File:YASHICA LOGO.svg", "雅西卡 YASHICA", "YASHICA"},
            {"insta360", "File:Insta360 logo (transparent).svg", "影石 Insta360", "Insta360"},
            {"samsung", "File:Samsung logo.svg", "三星 Samsung", "Samsung"},
            {"rewindpix", "https://rewindpix.com/cdn/shop/files/logo.png?width=1000", "倒卷 PS135 Rewindpix", "Rewindpix PS135"},
    };

    static final String UA = "OpenFilm6K/1.0 (personal photo app; user-initiated logo download)";

    /** sources drawn on a white canvas: knock the white out and crop to the mark */
    private static final java.util.HashSet<String> WHITE_BG = new java.util.HashSet<String>();
    static { WHITE_BG.add("rewindpix"); }

    private static final HashMap<String, Bitmap> CACHE = new HashMap<String, Bitmap>();
    private static Bitmap OWN;

    /** the app's own mark: shown while the element follows the photo (the real brand is only known at render) */
    static Bitmap own() {
        if (OWN != null) return OWN;
        try {
            // the landscape film-frame wordmark (falls back to the square app icon)
            android.graphics.drawable.Drawable d = null;
            try { d = Watermark.ctx.getResources().getDrawable(R.drawable.logo_own); } catch (Throwable ig) {}
            if (d == null) d = Watermark.ctx.getResources().getDrawable(R.drawable.ic_launcher);
            int w = Math.max(8, d.getIntrinsicWidth()), h = Math.max(8, d.getIntrinsicHeight());
            Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bm);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            OWN = bm;
        } catch (Throwable ig) {
            return null;
        }
        return OWN;
    }

    static File dir() {
        return new File(Watermark.ROOT, "logos");
    }

    /** how many logos are already on disk */
    static int have() {
        File[] fs = dir().listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) if (f.getName().endsWith(".png") && f.length() > 0) n++;
        return n;
    }

    /** which brand logo belongs to this photo (EXIF Make + Model); "" when unknown */
    static String slugFor(String make, String model) {
        String mk = make == null ? "" : make.toUpperCase(Locale.US);
        String md = model == null ? "" : model.toUpperCase(Locale.US);
        if (mk.contains("CANON")) return "canon";
        if (mk.contains("NIKON")) return "nikon";
        if (mk.contains("SONY")) return "sony";
        if (mk.contains("FUJI")) return "fujifilm";
        if (mk.contains("PANASONIC") || mk.contains("LUMIX"))
            return (md.startsWith("DC-") || md.contains("LUMIX") || mk.contains("LUMIX")) ? "lumix" : "panasonic";
        if (mk.contains("OM DIGITAL") || mk.contains("OM SYSTEM") || md.startsWith("OM-")) return "omsystem";
        if (mk.contains("OLYMPUS")) return md.startsWith("OM-") ? "omsystem" : "olympus";
        if (mk.contains("PENTAX")) return "pentax";
        if (mk.contains("RICOH")) return md.contains("PENTAX") ? "pentax" : "ricoh";
        if (mk.contains("LEICA")) return "leica";
        if (mk.contains("SIGMA")) return "sigma";
        if (mk.contains("HASSELBLAD")) return "hasselblad";
        if (mk.contains("CASIO")) return "casio";
        if (mk.contains("KODAK")) return "kodak";
        if (mk.contains("GOPRO")) return "gopro";
        if (mk.contains("DJI")) return "dji";
        if (mk.contains("ZEISS")) return "zeiss";
        if (mk.contains("MINOLTA")) return "minolta";
        if (mk.contains("KONICA")) return "konica";
        if (mk.contains("YASHICA")) return "yashica";
        if (mk.contains("INSTA360")) return "insta360";
        if (mk.contains("SAMSUNG")) return "samsung";
        if (mk.contains("TAMRON")) return "tamron";
        if (mk.contains("REWINDPIX") || md.contains("REWINDPIX") || md.contains("PS135")) return "rewindpix";
        return "";
    }

    /** decoded logo, cached; null when the file has not been downloaded */
    static Bitmap get(String slug) {
        if (slug == null || slug.length() == 0) return null;
        if ("openfilm6k".equals(slug)) return own();   // always the app's own wordmark
        if (CACHE.containsKey(slug)) return CACHE.get(slug);
        Bitmap bm = null;
        try {
            File f = new File(dir(), slug + ".png");
            if (f.isFile() && f.length() > 0) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                if (o.outWidth > 0) {
                    o.inJustDecodeBounds = false;
                    int s = 1;
                    while (o.outWidth / s > 700) s *= 2;
                    o.inSampleSize = s;
                    bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                }
            }
        } catch (Throwable ig) {}
        if (bm != null) CACHE.put(slug, bm);   // never cache a miss: a later download must show up at once
        return bm;
    }


    // ---------------------------------------------------------------- adaptive (明暗适配)

    /**
     * Vision-reviewed list: marks that carry a regular enclosing shape (square / circle badge, lens
     * block) or are one solid coloured block. 明暗适配 leaves them exactly as drawn — inverting them
     * would break the artwork without improving contrast.
     */
    private static final java.util.HashSet<String> KEEP_AS_IS = new java.util.HashSet<String>();
    static {
        String[] keep = {"gopro", "kodak", "konica", "leica", "minolta", "nikon", "omsystem", "zeiss"};
        for (String k : keep) KEEP_AS_IS.add(k);
    }

    /** adapted copies, keyed by identity + target polarity (the source bitmaps are shared/cached) */
    private static final HashMap<String, Bitmap> ADAPT = new HashMap<String, Bitmap>();

    /**
     * 明暗适配 for a logo, so it stands out from the background:
     *  - purely black/white mark  -> flip all its colours when it is on the wrong side of the background;
     *  - coloured mark with a regular enclosing shape (square/circle badge) -> leave it alone;
     *  - coloured bare lettering -> flip only the achromatic (black/white/grey) pixels, keep the colours.
     *
     * @param bgBright the background under the logo is bright (mean luminance > 0.5)
     */
    static Bitmap adapt(Bitmap src, boolean bgBright, String slug) {
        if (src == null) return null;
        if (src == OWN) return src;                                  // the app's own wordmark is never flipped
        if (slug != null && KEEP_AS_IS.contains(slug)) return src;   // badge / solid block: hands off
        String key = System.identityHashCode(src) + (bgBright ? "#B" : "#D");
        Bitmap c = ADAPT.get(key);
        if (c != null && !c.isRecycled()) return c;
        Bitmap out = null;
        try { out = buildAdapted(src, bgBright); } catch (Throwable ig) { out = null; }
        if (out == null) out = src;
        ADAPT.put(key, out);
        return out;
    }

    private static Bitmap buildAdapted(Bitmap src, boolean bgBright) {
        int w = src.getWidth(), h = src.getHeight();
        if (w < 4 || h < 4) return src;
        // analyse a downscaled copy (cheap, and noise does not matter here)
        int aw = Math.min(160, w);
        int ah = Math.max(1, Math.round(h * aw / (float) w));
        Bitmap small = Bitmap.createScaledBitmap(src, aw, ah, true);
        int[] px = new int[aw * ah];
        small.getPixels(px, 0, aw, 0, 0, aw, ah);
        if (small != src) small.recycle();

        int opaque = 0, coloured = 0, dark = 0, light = 0, nDark = 0, nLight = 0;
        for (int c : px) {
            if ((c >>> 24) < 40) continue;
            opaque++;
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
            int lum = (299 * r + 587 * g + 114 * b) / 1000;
            if (mx - mn > 40) { coloured++; continue; }
            if (lum < 100) { dark++; nDark++; } else if (lum > 180) { light++; nLight++; }
        }
        if (opaque == 0) return src;
        boolean mono = coloured < opaque * 0.02f;          // "pure black & white": no coloured pixels at all

        boolean invertAll;
        if (mono) {
            boolean inkIsLight = light > dark;             // is the mark white-on-transparent or black?
            invertAll = (inkIsLight == bgBright);          // wrong polarity for this background -> flip
            if (!invertAll) return src;
        } else {
            if (hasBoundary(px, aw, ah)) return src;       // badge / framed mark: already safe, leave it
            if (nDark + nLight == 0) return src;           // nothing neutral to flip
            boolean neutralIsLight = nLight > nDark;
            if (neutralIsLight != bgBright) return src;    // neutral parts already read on this background
            invertAll = false;                             // bare lettering: only the neutral parts flip
        }

        Bitmap out = src.copy(src.getConfig() == null ? Bitmap.Config.ARGB_8888 : src.getConfig(), true);
        if (out == null) return src;
        int[] full = new int[w * h];
        out.getPixels(full, 0, w, 0, 0, w, h);
        for (int i = 0; i < full.length; i++) {
            int c = full[i], a = c >>> 24;
            if (a == 0) continue;
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            if (!invertAll) {
                int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                if (mx - mn > 28) continue;                // coloured pixel: keep it
            }
            full[i] = (a << 24) | ((255 - r) << 16) | ((255 - g) << 8) | (255 - b);
        }
        out.setPixels(full, 0, w, 0, 0, w, h);
        return out;
    }

    /** does the mark carry a regular enclosing shape? A badge is one big opaque blob that covers most
     *  of the mark's own box AND whose outline runs all the way round that box; lettering is many blobs
     *  that only merge (at 160px) into a fat strip — its bbox edge is mostly empty. */
    private static boolean hasBoundary(int[] px, int w, int h) {
        int[] comp = new int[w * h];
        int[] q = new int[w * h];
        int bestArea = 0, bestId = 0, bx0 = 0, bx1 = -1, by0 = 0, by1 = -1;
        int id = 0;
        for (int i = 0; i < px.length; i++) {
            if (comp[i] != 0 || (px[i] >>> 24) < 40) continue;
            id++;
            int qh = 0, qt = 0, area = 0, minX = w, maxX = -1, minY = h, maxY = -1;
            q[qt++] = i; comp[i] = id;
            while (qh < qt) {
                int j = q[qh++], x = j % w, y = j / w;
                area++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                int[] nb = {x > 0 ? j - 1 : -1, x < w - 1 ? j + 1 : -1, y > 0 ? j - w : -1, y < h - 1 ? j + w : -1};
                for (int k : nb) {
                    if (k >= 0 && comp[k] == 0 && (px[k] >>> 24) >= 40) { comp[k] = id; q[qt++] = k; }
                }
            }
            if (area > bestArea) { bestArea = area; bestId = id; bx0 = minX; bx1 = maxX; by0 = minY; by1 = maxY; }
        }
        if (bestArea == 0 || bx1 < bx0 || by1 < by0) return false;
        int bw = bx1 - bx0 + 1, bh = by1 - by0 + 1;
        if ((bw * bh) < (w * h) * 55 / 100) return false;          // too small to be a plate
        if (bestArea * 100 / (bw * bh) < 45) return false;         // too sparse to be a plate
        int cover = 0, total = 0;                                 // how much of the box outline IS the blob
        for (int x = bx0; x <= bx1; x++) {
            total += 2;
            if (comp[by0 * w + x] == bestId) cover++;
            if (comp[by1 * w + x] == bestId) cover++;
        }
        for (int y = by0; y <= by1; y++) {
            total += 2;
            if (comp[y * w + bx0] == bestId) cover++;
            if (comp[y * w + bx1] == bestId) cover++;
        }
        return cover * 100 >= total * 60;                          // lettering only grazes its box
    }

    /** enqueue index i when it is transparent and unseen; returns the new tail */
    private static int push(int[] px, boolean[] seen, int[] q, int qt, int i, int ignored) {
        if (i < 0 || i >= px.length || seen[i] || (px[i] >>> 24) >= 40) return qt;
        seen[i] = true;
        q[qt] = i;
        return qt + 1;
    }

    static void clearCache() { CACHE.clear(); ADAPT.clear(); }

    /** localised brand name for the properties dropdown */
    static String label(String slug) {
        if (slug == null || slug.length() == 0) return "";
        boolean zh = L.lang != null && L.lang.toLowerCase(Locale.US).startsWith("zh");
        for (String[] b : BRANDS) if (b[0].equals(slug)) return zh ? b[2] : b[3];
        return slug;
    }

    /** progress: (done, total, message) on the caller's thread */
    interface Cb { void onProgress(int done, int total); }

    /** download every logo; returns how many now exist on disk */
    static int downloadAll(Cb cb) {
        File d = dir();
        if (!d.isDirectory() && !d.mkdirs()) return have();
        clearCache();
        int total = BRANDS.length;
        for (int i = 0; i < total; i++) {
            String slug = BRANDS[i][0];
            File out = new File(d, slug + ".png");
            String url = BRANDS[i][1];
            if (url != null && url.length() > 0 && !(out.isFile() && out.length() > 2048)) fetch(url, out);
            if (cb != null) cb.onProgress(i + 1, total);
        }
        Engine.dbg("logo download done: " + have() + "/" + total + " on disk");
        return have();
    }

    /** white canvas -> transparency, then crop to the visible mark; writes PNG into `out` */
    private static boolean knockOutWhite(File tmp) {
        Bitmap src = null;
        try {
            src = BitmapFactory.decodeFile(tmp.getAbsolutePath());
            if (src == null) return false;
            int w = src.getWidth(), h = src.getHeight();
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);
            int x0 = w, y0 = h, x1 = 0, y1 = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = px[y * w + x];
                    int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                    int mn = Math.min(r, Math.min(g, b));
                    int a = mn >= 248 ? 0 : Math.min(255, (255 - mn) * 2);
                    if (a > 8) {
                        if (x < x0) x0 = x;
                        if (x > x1) x1 = x;
                        if (y < y0) y0 = y;
                        if (y > y1) y1 = y;
                    }
                    px[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            if (x1 <= x0 || y1 <= y0) return false;
            Bitmap cut = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            cut.setPixels(px, 0, w, 0, 0, w, h);
            Bitmap crop = Bitmap.createBitmap(cut, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
            FileOutputStream fo = new FileOutputStream(tmp.getAbsolutePath());
            crop.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            if (cut != crop) cut.recycle();
            crop.recycle();
            return tmp.exists();
        } catch (Throwable ig) {
            return false;
        } finally {
            if (src != null) src.recycle();
        }
    }

    private static void fetch(String commonsFile, File out) {
        File tmp = new File(out.getParentFile(), out.getName() + ".part");
        InputStream in = null;
        FileOutputStream fo = null;
        HttpURLConnection c = null;
        try {
            String enc = commonsFile.replace(" ", "%20");
            String href = commonsFile.startsWith("http") ? enc
                    : "https://commons.wikimedia.org/wiki/Special:FilePath/" + enc + "?width=512";
            URL u = new URL(href);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "image/png,image/*;q=0.8");
            int code = c.getResponseCode();
            if (code != 200) { Engine.dbg("logo fetch HTTP " + code + " " + href); return; }
            in = c.getInputStream();
            fo = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                total += n;
                if (total > 4L * 1024 * 1024) break;      // sanity cap
            }
            fo.close();
            fo = null;
            // the server may answer with an HTML error page: only keep real images
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getAbsolutePath(), o);
            if (o.outWidth > 8 && o.outHeight > 8 && tmp.length() > 512) {
                String slug = out.getName().substring(0, out.getName().length() - 4);
                if (WHITE_BG.contains(slug)) knockOutWhite(tmp);   // re-writes tmp with alpha + crop
                if (!tmp.renameTo(out)) { tmp.delete(); }
            } else {
                tmp.delete();
            }
        } catch (Throwable ig) {
            Engine.dbg("logo fetch ex " + ig + " " + out.getName());
        } finally {
            try { if (fo != null) fo.close(); } catch (Throwable ig) {}
            try { if (in != null) in.close(); } catch (Throwable ig) {}
            if (c != null) c.disconnect();
        }
    }
}
