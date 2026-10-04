package com.openfilm6k.host;

import java.io.*;

/**
 * v3: carry the original capture's EXIF onto every JPEG this app writes.
 *
 * Why bytes and not android.media.ExifInterface: the framework class only knows a fixed
 * tag table, so a saveAttributes() round-trip silently DROPS everything it doesn't model —
 * GPS IFD, the A6000's vendor MakerNote, ImageUniqueID, focus/shot-info, proprietary
 * PictureEffect fields. The requirement is "all original EXIF", so we move the original
 * APP1 (FFE1) segment verbatim: it is a self-contained blob ("Exif\0\0" + TIFF structure)
 * and copying it byte-for-byte preserves every IFD, including unknown/private ones.
 *
 * A JPEG's APP1 must sit immediately after SOI (FFD8). The app's encoders
 * (Bitmap.compress, turbojpeg) write a bare JFIF stream with no APP1 at all, so we splice
 * the original segment in right after SOI. Nothing else about the file is touched.
 *
 * Orientation is preserved as-is and is CORRECT here: nothing in the render pipeline
 * rotates pixels (verified: no Matrix/rotate/EXIF_ORIENTATION use anywhere), so the pixel
 * order is unchanged and the original Orientation tag still describes the image truthfully.
 */
public class Exif {
    private static final byte[] SOI = {(byte) 0xFF, (byte) 0xD8};

    /** extract the first APP1 ("Exif\0\0") segment of a JPEG, or null if it has none */
    static byte[] app1Of(File jpg) {
        try {
            byte[] b = readAll(jpg);
            return app1OfBytes(b);
        } catch (Throwable t) { return null; }
    }

    static byte[] app1OfBytes(byte[] jpg) {
        if (jpg == null || jpg.length < 4) return null;
        if (jpg[0] != (byte) 0xFF || jpg[1] != (byte) 0xD8) return null;      // not a JPEG
        int p = 2;
        while (p + 4 <= jpg.length) {
            if (jpg[p] != (byte) 0xFF) return null;                            // desynced
            int marker = jpg[p + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                p += 2;                                                          // standalone
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) return null;                  // start of scan / EOI
            int len = ((jpg[p + 2] & 0xFF) << 8) | (jpg[p + 3] & 0xFF);
            if (len < 2 || p + 2 + len > jpg.length) return null;
            if (marker == 0xE1 && len >= 8 && jpg[p + 4] == 'E' && jpg[p + 5] == 'x'
                    && jpg[p + 6] == 'i' && jpg[p + 7] == 'f') {
                byte[] seg = new byte[2 + len];
                System.arraycopy(jpg, p, seg, 0, 2 + len);
                return seg;                                                     // includes FFE1 + length
            }
            p += 2 + len;
        }
        return null;
    }

    /** splice `app1` into `jpg` immediately after SOI; returns null on any problem */
    static byte[] inject(byte[] jpg, byte[] app1) {
        if (jpg == null || app1 == null || app1.length < 8) return null;
        if (jpg.length < 2 || jpg[0] != (byte) 0xFF || jpg[1] != (byte) 0xD8) return null;
        // already carries an APP1 Exif (shouldn't happen — our encoders write none): leave alone
        if (app1OfBytes(jpg) != null) return jpg;
        int n = 2 + app1.length + (jpg.length - 2);
        byte[] out = new byte[n];
        out[0] = SOI[0]; out[1] = SOI[1];
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpg, 2, out, 2 + app1.length, jpg.length - 2);
        return out;
    }

    /**
     * Copy EXIF from `srcJpg` onto `dstJpg`, in place.
     * Never throws and never shrinks the file: if anything fails the graded JPEG is left as-is,
     * which is exactly today's behaviour.
     */
    static void carry(File srcJpg, File dstJpg) {
        if (srcJpg == null || dstJpg == null || !dstJpg.exists()) return;
        if (srcJpg.equals(dstJpg)) return;
        try {
            byte[] app1 = app1Of(srcJpg);
            if (app1 == null) return;
            File tmp = new File(dstJpg.getParentFile(), dstJpg.getName() + ".exiftmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            try {
                fo.write(inject(readAll(dstJpg), app1));
                fo.flush();
            } finally { fo.close(); }
            // atomic-ish swap; a failure here keeps the un-tagged original
            if (!dstJpg.delete()) { tmp.delete(); return; }
            if (!tmp.renameTo(dstJpg)) { tmp.delete(); }
            Engine.dbg("exif carried " + app1.length + "B -> " + dstJpg.getName());
        } catch (Throwable t) {
            Engine.dbg("exif carry skipped: " + t);
        }
    }

    /** in-place variant: same file, but we already hold the original bytes (pre-stamp path) */
    static void carryBytes(File dstJpg, byte[] app1) {
        if (dstJpg == null || app1 == null || !dstJpg.exists()) return;
        try {
            File tmp = new File(dstJpg.getParentFile(), dstJpg.getName() + ".exiftmp");
            FileOutputStream fo = new FileOutputStream(tmp);
            try { fo.write(inject(readAll(dstJpg), app1)); fo.flush(); } finally { fo.close(); }
            if (!dstJpg.delete()) { tmp.delete(); return; }
            if (!tmp.renameTo(dstJpg)) tmp.delete();
        } catch (Throwable t) { Engine.dbg("exif carryBytes skipped: " + t); }
    }

    static byte[] readAll(File f) throws IOException {
        long len = f.length();
        if (len > Integer.MAX_VALUE - 8) throw new IOException("too big");
        byte[] b = new byte[(int) len];
        FileInputStream in = new FileInputStream(f);
        try {
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            if (n != b.length) throw new IOException("short read " + n + "/" + b.length);
        } finally { in.close(); }
        return b;
    }
}