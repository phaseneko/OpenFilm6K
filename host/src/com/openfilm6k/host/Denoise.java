package com.openfilm6k.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;

/** On-device noise reduction for incoming photos (ncnn U-Net, see jni/denoise.cpp).
 *  Runs before any post-processing: dust/leak/date/border/LUT all see the denoised pixels. */
public class Denoise {
    static final String[] MODES = {"cam", "spy", "ftp", "rewind", "luma"};
    private static final String ROOT = "/sdcard/OpenFilm6K/denoise";
    private static final String ASSET_VER = "2";

    private static SharedPreferences pf(Context c) { return c.getSharedPreferences("of6k", 0); }

    static File param() { return new File(ROOT, "denoise.param"); }
    static File bin() { return new File(ROOT, "denoise.bin"); }

    /** copy the model out of the APK once per version; returns true when both files are usable */
    static synchronized boolean install(Context c) {
        try {
            SharedPreferences p = pf(c);
            File d = new File(ROOT);
            if (ASSET_VER.equals(p.getString("dn_ver", "")) && param().exists() && bin().exists()
                    && param().length() > 0 && bin().length() > 0) return true;
            d.mkdirs();
            if (!copyAsset(c, "denoise/denoise.param", param())) return false;
            if (!copyAsset(c, "denoise/denoise.bin", bin())) return false;
            p.edit().putString("dn_ver", ASSET_VER).commit();
            Engine.dbg("denoise model installed");
            return true;
        } catch (Throwable t) {
            Engine.dbg("denoise install failed: " + t);
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
            Engine.dbg("denoise asset " + name + ": " + t);
            return false;
        }
    }

    /** is denoise switched on for this intake path? modeKey: cam / spy / ftp / rewind / luma */
    static boolean enabledFor(Context c, String modeKey) {
        String m = pf(c).getString("dn_modes", "");
        return m != null && ("," + m + ",").contains("," + modeKey + ",");
    }

    /** condition gate: ISO threshold, or day/night score threshold */
    static boolean gate(Context c, File f) {
        SharedPreferences p = pf(c);
        if ("tonal".equals(p.getString("dn_by", "iso"))) {
            float th = p.getFloat("dn_tonal", 0.5f);
            return tonal(f) >= th;
        }
        int th = p.getInt("dn_iso", 800);
        int iso = iso(f);
        return iso > 0 && iso >= th;
    }

    static int iso(File f) {
        try {
            ExifInterface ex = new ExifInterface(f.getAbsolutePath());
            String s = ex.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS);
            if (s == null) s = ex.getAttribute(ExifInterface.TAG_ISO);
            if (s == null) return 0;
            s = s.trim();
            int cut = s.indexOf(' ');
            if (cut > 0) s = s.substring(0, cut);
            return (int) Float.parseFloat(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 0 = day, 1 = night. Mean luminance of a small decode; robust to missing EXIF. */
    static float tonal(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int ss = 1;
            while (o.outWidth / ss > 256) ss *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = ss;
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (bm == null) return 0f;
            int w = bm.getWidth(), h = bm.getHeight();
            int[] px = new int[w * h];
            bm.getPixels(px, 0, w, 0, 0, w, h);
            bm.recycle();
            double sum = 0;
            for (int i = 0; i < px.length; i++) {
                int v = px[i];
                sum += 0.299 * ((v >> 16) & 255) + 0.587 * ((v >> 8) & 255) + 0.114 * (v & 255);
            }
            float mean = (float) (sum / px.length / 255.0);
            float sc = (0.42f - mean) / 0.32f;
            return Math.max(0f, Math.min(1f, sc));
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** denoise src into a sibling temp file when this path + gate are on; null = skip.
     *  exifApp1 (may be null) is re-attached so date/ISO stamps keep working. */
    static File maybe(Context c, File src, String modeKey, byte[] exifApp1) {
        try {
            if (src == null || !src.exists()) { Engine.dbg("denoise skip: no src"); return null; }
            if (!enabledFor(c, modeKey)) { Engine.dbg("denoise skip: mode " + modeKey + " off"); return null; }
            if (!gate(c, src)) { Engine.dbg("denoise skip: gate iso=" + iso(src) + " tonal=" + String.format(java.util.Locale.US, "%.2f", tonal(src)) + " by=" + pf(c).getString("dn_by","iso")); return null; }
            if (!install(c)) { Engine.dbg("denoise skip: install"); return null; }
            File o = new File(src.getParentFile(), "dn_" + System.currentTimeMillis() + ".jpg");
            int threads = 1;   // one stream: ncnn Vulkan is faster serial than with competing extractors
            float band = pf(c).getFloat("dn_band", 1.0f);   // sky 2-band strength (0..2)
            long t0 = System.currentTimeMillis();
            int rc = Engine.denoiseFile(src.getAbsolutePath(), o.getAbsolutePath(),
                    param().getAbsolutePath(), bin().getAbsolutePath(), threads, true, band);
            if (rc != 0) {   // GPU unavailable/failed: retry on CPU
                Engine.dbg("denoise gpu rc=" + rc + ", retrying cpu");
                rc = Engine.denoiseFile(src.getAbsolutePath(), o.getAbsolutePath(),
                        param().getAbsolutePath(), bin().getAbsolutePath(), threads, false, band);
            }
            if (rc != 0 || !o.exists() || o.length() == 0) {
                if (o.exists()) o.delete();
                Engine.dbg("denoise failed rc=" + rc + " " + src.getName());
                return null;
            }
            if (exifApp1 != null) {
                FileInputStream fi = new FileInputStream(o);
                byte[] jpg = new byte[(int) o.length()];
                int rd = 0;
                while (rd < jpg.length) {
                    int n = fi.read(jpg, rd, jpg.length - rd);
                    if (n <= 0) break;
                    rd += n;
                }
                fi.close();
                byte[] out = Exif.inject(jpg, exifApp1);
                if (out != null && out != jpg) {
                    FileOutputStream fo = new FileOutputStream(o);
                    fo.write(out);
                    fo.close();
                }
            }
            Engine.dbg("denoise " + modeKey + " " + src.getName() + " " + (System.currentTimeMillis() - t0) + "ms");
            return o;
        } catch (Throwable t) {
            Engine.dbg("denoise error: " + t);
            return null;
        }
    }
}
