package com.openfilm6k.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Color alignment for the Rewindpix camera: two 33^3 cubes (day / night renderings) fitted
 *  from paired Sony reference scenes, blended by the capture ISO and applied right after
 *  denoise, before any post process or film grading. Strength 0 = off.
 *  Only the rewind intake path is aligned: other cameras must not be touched. */
public class ColorAlign {
    private static final String ROOT = "/sdcard/OpenFilm6K/color";
    private static final String ASSET_VER = "2";
    private static final float LOG2_400 = 8.6439f, LOG2_1600 = 10.6439f;
    private static CubeLib.Lut lutDay, lutNight;
    private static float[] blendBuf;

    private static SharedPreferences pf(Context c) { return c.getSharedPreferences("of6k", 0); }
    static File cubeDay() { return new File(ROOT, "align_day.cube"); }
    static File cubeNight() { return new File(ROOT, "align_night.cube"); }
    static float strength(Context c) { return pf(c).getFloat("ca_strength", 1f); }

    /** copy the cubes out of the APK once per version */
    static synchronized boolean install(Context c) {
        try {
            SharedPreferences p = pf(c);
            File d = new File(ROOT);
            if (ASSET_VER.equals(p.getString("ca_ver", "")) && cubeDay().exists() && cubeNight().exists()
                    && cubeDay().length() > 0 && cubeNight().length() > 0) {
                if (lutDay == null) lutDay = CubeLib.parseCube(cubeDay());
                if (lutNight == null) lutNight = CubeLib.parseCube(cubeNight());
                return lutDay != null && lutNight != null;
            }
            d.mkdirs();
            if (!copyAsset(c, "color/align_day.cube", cubeDay())) return false;
            if (!copyAsset(c, "color/align_night.cube", cubeNight())) return false;
            lutDay = CubeLib.parseCube(cubeDay());
            lutNight = CubeLib.parseCube(cubeNight());
            if (lutDay == null || lutNight == null) return false;
            p.edit().putString("ca_ver", ASSET_VER).commit();
            Engine.dbg("color align lut installed day+n night n=" + lutDay.n + "/" + lutNight.n);
            return true;
        } catch (Throwable t) {
            Engine.dbg("color align install failed: " + t);
            return false;
        }
    }

    private static boolean copyAsset(Context c, String name, File dst) {
        try {
            InputStream in = c.getAssets().open(name);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close(); out.close();
            return true;
        } catch (Throwable t) {
            Engine.dbg("color align asset " + name + ": " + t);
            return false;
        }
    }

    /** write an aligned copy of src when this intake path is on; null = skip.
     *  exifApp1 (may be null) is re-attached so exposure/date reads downstream keep working. */
    static File maybe(Context c, File src, String modeKey, byte[] exifApp1) {
        long t0 = System.currentTimeMillis();
        try {
            if (src == null || !src.exists()) return null;
            if (!"rewind".equals(modeKey)) return null;   // Rewindpix camera only
            float k = strength(c);
            if (k <= 0.001f) return null;
            if (!install(c) || lutDay == null || lutNight == null) return null;
            if (exifApp1 == null) exifApp1 = Exif.app1Of(src);   // the re-encode below drops EXIF
            Bitmap bm = BitmapFactory.decodeFile(src.getAbsolutePath());
            if (bm == null) { Engine.dbg("color align: decode failed " + src.getName()); return null; }
            int w = bm.getWidth(), h = bm.getHeight();
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            bm.recycle();
            int iso = Denoise.iso(src);
            float dayW = iso > 0 ? weightForIso(iso) : weightForPixels(px);
            CubeLib.Lut lut = blend(lutDay, lutNight, dayW);
            applyLut(px, lut, k);
            Bitmap outBm = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
            File o = new File(src.getParentFile(), "ca_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream fo = new FileOutputStream(o);
            boolean ok = outBm.compress(Bitmap.CompressFormat.JPEG, 95, fo);
            fo.close();
            outBm.recycle();
            if (!ok || !o.exists() || o.length() == 0) {
                if (o.exists()) o.delete();
                Engine.dbg("color align: write failed");
                return null;
            }
            if (exifApp1 != null) {   // same re-attach as Denoise.maybe
                java.io.FileInputStream fi = new java.io.FileInputStream(o);
                byte[] jpg = new byte[(int) o.length()];
                int rd = 0;
                while (rd < jpg.length) {
                    int n = fi.read(jpg, rd, jpg.length - rd);
                    if (n <= 0) break;
                    rd += n;
                }
                fi.close();
                byte[] outJpg = Exif.inject(jpg, exifApp1);
                if (outJpg != null && outJpg != jpg) {
                    FileOutputStream fo2 = new FileOutputStream(o);
                    fo2.write(outJpg);
                    fo2.close();
                }
            }
            Engine.dbg("color align " + modeKey + " " + src.getName() + " iso=" + iso
                    + " dayW=" + String.format(java.util.Locale.US, "%.2f", dayW) + " k=" + k
                    + " " + (System.currentTimeMillis() - t0) + "ms");
            return o;
        } catch (Throwable t) {
            Engine.dbg("color align error: " + t);
            return null;
        }
    }

    /** day weight from ISO: 1 at <=400, 0 at >=1600, linear in log2 between */
    static float weightForIso(int iso) {
        float l = (float) (Math.log(iso) / Math.log(2.0));
        if (l <= LOG2_400) return 1f;
        if (l >= LOG2_1600) return 0f;
        return (LOG2_1600 - l) / (LOG2_1600 - LOG2_400);
    }

    /** fallback when EXIF carries no ISO: scene brightness decides (bright = day cube) */
    private static float weightForPixels(int[] px) {
        double sum = 0;
        int n = 0;
        for (int i = 0; i < px.length; i += 16) {
            int v = px[i];
            sum += 0.299 * ((v >> 16) & 255) + 0.587 * ((v >> 8) & 255) + 0.114 * (v & 255);
            n++;
        }
        if (n == 0) return 1f;
        float mean = (float) (sum / n / 255.0);
        return Math.max(0f, Math.min(1f, (mean - 0.30f) / 0.12f));
    }

    /** pointwise blend of the two cubes (identical size), reusing one scratch buffer */
    private static CubeLib.Lut blend(CubeLib.Lut day, CubeLib.Lut night, float w) {
        if (w >= 0.999f) return day;
        if (w <= 0.001f) return night;
        int n = day.data.length;
        if (blendBuf == null || blendBuf.length != n) blendBuf = new float[n];
        float[] a = day.data, b = night.data;
        for (int i = 0; i < n; i++) blendBuf[i] = a[i] * w + b[i] * (1f - w);
        CubeLib.Lut out = new CubeLib.Lut();
        out.n = day.n;
        out.data = blendBuf;
        return out;
    }

    /** trilinear cube lookup, blended against the untouched pixel by k (0..1) */
    private static void applyLut(int[] px, CubeLib.Lut lut, float k) {
        final int n = lut.n;
        final float[] d = lut.data;
        final float sc = n - 1;
        final int last = n - 1;
        for (int i = 0; i < px.length; i++) {
            final int v = px[i];
            final int r8 = (v >> 16) & 255, g8 = (v >> 8) & 255, b8 = v & 255;
            final float rf = r8 * sc / 255f, gf = g8 * sc / 255f, bf = b8 * sc / 255f;
            final int r0 = (int) rf, g0 = (int) gf, b0 = (int) bf;
            final int r1 = r0 < last ? r0 + 1 : r0, g1 = g0 < last ? g0 + 1 : g0, b1 = b0 < last ? b0 + 1 : b0;
            final float fr = rf - r0, fg = gf - g0, fb = bf - b0;
            final int i000 = ((b0 * n + g0) * n + r0) * 3, i100 = ((b0 * n + g0) * n + r1) * 3;
            final int i010 = ((b0 * n + g1) * n + r0) * 3, i110 = ((b0 * n + g1) * n + r1) * 3;
            final int i001 = ((b1 * n + g0) * n + r0) * 3, i101 = ((b1 * n + g0) * n + r1) * 3;
            final int i011 = ((b1 * n + g1) * n + r0) * 3, i111 = ((b1 * n + g1) * n + r1) * 3;
            float tr, tg, tb;
            {   // red
                float c00 = d[i000] + (d[i100] - d[i000]) * fr;
                float c01 = d[i001] + (d[i101] - d[i001]) * fr;
                float c10 = d[i010] + (d[i110] - d[i010]) * fr;
                float c11 = d[i011] + (d[i111] - d[i011]) * fr;
                float c0 = c00 + (c10 - c00) * fg, c1 = c01 + (c11 - c01) * fg;
                tr = c0 + (c1 - c0) * fb;
            }
            {   // green
                float c00 = d[i000 + 1] + (d[i100 + 1] - d[i000 + 1]) * fr;
                float c01 = d[i001 + 1] + (d[i101 + 1] - d[i001 + 1]) * fr;
                float c10 = d[i010 + 1] + (d[i110 + 1] - d[i010 + 1]) * fr;
                float c11 = d[i011 + 1] + (d[i111 + 1] - d[i011 + 1]) * fr;
                float c0 = c00 + (c10 - c00) * fg, c1 = c01 + (c11 - c01) * fg;
                tg = c0 + (c1 - c0) * fb;
            }
            {   // blue
                float c00 = d[i000 + 2] + (d[i100 + 2] - d[i000 + 2]) * fr;
                float c01 = d[i001 + 2] + (d[i101 + 2] - d[i001 + 2]) * fr;
                float c10 = d[i010 + 2] + (d[i110 + 2] - d[i010 + 2]) * fr;
                float c11 = d[i011 + 2] + (d[i111 + 2] - d[i011 + 2]) * fr;
                float c0 = c00 + (c10 - c00) * fg, c1 = c01 + (c11 - c01) * fg;
                tb = c0 + (c1 - c0) * fb;
            }
            float orr = r8 + (tr * 255f - r8) * k;
            float og = g8 + (tg * 255f - g8) * k;
            float ob = b8 + (tb * 255f - b8) * k;
            int nr = orr < 0f ? 0 : (orr > 255f ? 255 : (int) (orr + 0.5f));
            int ng = og < 0f ? 0 : (og > 255f ? 255 : (int) (og + 0.5f));
            int nb = ob < 0f ? 0 : (ob > 255f ? 255 : (int) (ob + 0.5f));
            px[i] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
        }
    }
}
