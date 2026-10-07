package com.openfilm6k.camera;

import android.app.Activity;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * OpenFilm6K camera — full shooting app: live preview, manual params, film choice,
 * native capture; each shot lands in DCIM and is graded automatically into
 * /sdcard/OpenFilm6K/GRADED/G<film><frame>.JPG.
 *
 * Keys: LEFT/RIGHT = dial mode (mode/shutter/aperture/ISO/EV/film), WHEEL = adjust,
 * top dial = adjust too, S1/S2 = focus/shoot, Fn = film browser, C1 = settings,
 * AEL = overlay visibility, MENU x2 = quit.
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    // ScalarInput scan codes (A6000, verified by Recipe Lab)
    private static final int K_UP = 103, K_DOWN = 108, K_LEFT = 105, K_RIGHT = 106;
    private static final int K_ENTER = 232, K_MENU = 514, K_AEL = 532, K_C1 = 622, K_FN = 520;
    private static final int K_S1 = 516, K_S2 = 518, K_DELETE = 595, K_PLAY = 207;
    private static final int K_C2 = 595;   // confirmed from LOG (C2 button)
    private static final int K_WHEEL_CW = 528, K_WHEEL_CCW = 529;   // real kernel codes (0x210/0x211); 522/523 never fired
    private static final int K_DIAL_CW = 525, K_DIAL_CCW = 526;

    private static final int SHUTTER_RESTART_MS = 1000;
    private static final int[] SCALES = {2, 4, 1, 0};   // 6MP, 2MP, 24MP, phone
    private static final int RET_PORT = 8790;
    private String phoneIp() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService("wifi");
            int g = wm.getDhcpInfo().gateway;
            if (g == 0) return "192.168.43.1";
            return (g & 255) + "." + ((g >> 8) & 255) + "." + ((g >> 16) & 255) + "." + ((g >> 24) & 255);
        } catch (Throwable t) { return "192.168.43.1"; }
    }
    private void startReturnServer() {
        new Thread(new Runnable() { public void run() {
            try {
                java.net.ServerSocket ss = new java.net.ServerSocket(RET_PORT);
                Logger.log("return-server on :" + RET_PORT);
                while (true) {
                    final java.net.Socket cs = ss.accept();
                    try {
                        java.io.InputStream in2 = cs.getInputStream();
                        String head = ""; int nl = 0, c;
                        while (nl < 4 && (c = in2.read()) >= 0) { head += (char) c; nl = (c == 10 || c == 13) ? nl + 1 : 0; }
                        java.util.Map<String,String> q2 = new java.util.HashMap<String,String>();
                        int qi = head.indexOf('?');
                        if (qi >= 0) { String u = head.substring(qi + 1); u = u.substring(0, Math.max(0, u.indexOf(' ')));
                            for (String kv : u.split("&")) { int e2 = kv.indexOf('='); if (e2 > 0) q2.put(kv.substring(0, e2), kv.substring(e2 + 1)); } }
                        int len = 0;
                        if (head.contains("/msg")) {
                            String mn = head.contains("name=") ? head.substring(head.indexOf("name=") + 5) : "?";
                            mn = mn.replaceAll("[^A-Za-z0-9_.]", "");
                            final String m = mn;
                            setStatus(m + " graded");
                            Logger.log("msg: " + m);
                            cs.getOutputStream().write(("HTTP/1.0 200 OK\r\nContent-Length: 2\r\n\r\nok").getBytes());
                            cs.close(); continue;
                        }
                        for (String l : head.split("\r\n")) if (l.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
                        String nm = q2.containsKey("name") ? q2.get("name") : ("R" + System.currentTimeMillis() + ".JPG");
                        if (nm.indexOf('/') >= 0 || nm.indexOf('\\') >= 0) nm = "BAD.JPG";
                        File out = new File(GRADED, nm);
                        File tmp = new File(GRADED, "r.tmp");
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
                        byte[] b2 = new byte[65536]; int left = len;
                        while (left > 0) { int r2 = in2.read(b2, 0, (int) Math.min(b2.length, left)); if (r2 < 0) break; fo.write(b2, 0, r2); left -= r2; }
                        fo.close();
                        boolean ok = tmp.length() == len && len > 0;
                        if (ok) { tmp.renameTo(out); } else { tmp.delete(); }
                        // replacement paused: the A6000 gallery does not index external files, replacing would only lose both the original and the graded copy
                        // re-enable only after fully understanding AvindexStore INSERT
                        String orig = null; // DISABLED 2026-09-25
                        orig = q2.get("orig");
                        if (false && orig != null && orig.indexOf('/') < 0) {
                            try {
                                File root = (LUTS != null && LUTS.getParentFile() != null && LUTS.getParentFile().getParentFile() != null)
                                        ? LUTS.getParentFile().getParentFile() : SD;
                                File dcDir = new File(root, "DCIM/101MSDCF");
                                if (!dcDir.isDirectory()) dcDir = new File("/mnt/sdcard/DCIM/101MSDCF");
                                File dc = new File(dcDir, orig);
                                File dt = new File(dcDir, "RTMP" + (System.currentTimeMillis() % 10000) + ".TMP");
                                Logger.log("replace target " + dc.getAbsolutePath() + " exists=" + dc.exists());
                                java.io.FileInputStream di = new java.io.FileInputStream(out);
                                java.io.FileOutputStream dfo = new java.io.FileOutputStream(dt);
                                byte[] db = new byte[65536]; int dr2;
                                while ((dr2 = di.read(db)) > 0) dfo.write(db, 0, dr2);
                                di.close(); dfo.close();
                                // find the next free DSC number
                                File nn = dc;
                                while (nn.exists()) {
                                    long num = 0;
                                    String b3 = nn.getName();
                                    for (int ci = 0; ci < 8; ci++) {
                                        char cc = b3.charAt(ci);
                                        num = (cc >= '0' && cc <= '9') ? num * 10 + (cc - '0') : num;
                                    }
                                    nn = new File(dcDir, String.format("DSC%05d.JPG", num + 1));
                                }
                                java.io.FileInputStream d1 = new java.io.FileInputStream(dt);
                                java.io.FileOutputStream d2 = new java.io.FileOutputStream(nn);
                                while ((dr2 = d1.read(db)) > 0) d2.write(db, 0, dr2);
                                d1.close(); d2.close();
                                dt.delete();
                                if (nn.length() == out.length()) {
                                    dc.delete();
                                    // never touch avindex: forced update/broadcast corrupts the camera DB (2026-09-25 incident)
                                    Logger.log("graded->" + nn.getName() + " orig deleted " + orig);
                                } else {
                                    nn.delete();
                                    Logger.log("graded write mismatch, kept orig");
                                }
                            } catch (Throwable t2) { Logger.log("replace EX " + t2); }
                        }
                        cs.getOutputStream().write(("HTTP/1.0 " + (ok ? 200 : 500) + " OK\r\nContent-Length: 2\r\n\r\nok").getBytes());
                        Logger.log("returned " + nm + " " + len + "B ok=" + ok);
                        if (ok) setStatus("graded in: " + nm);
                        cs.close();
                    } catch (Throwable t) { try { cs.close(); } catch (Throwable ig) {} }
                }
            } catch (Throwable t) { Logger.log("return-server dead " + t); }
        }}, "return").start();
    }
    private String packOf(int i) {
        if (i >= 0 && i < names.size()) return names.get(i);
        return names.isEmpty() ? "-" : names.get(0);   // film list pulled from the host at runtime
    }
    /** pull the film list from the OpenFilm6K host (:8800/films) — called after each successful connection */
    private void pullFilmList() {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL("http://" + phoneIp() + ":8800/films?stampmode=" + stampMode).openConnection();
            c.setConnectTimeout(1000); c.setReadTimeout(2500);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close(); c.disconnect();
            java.util.ArrayList<String> got = new java.util.ArrayList<String>();
            int cc = 0; boolean seenDiv = false;
            for (String s2 : new String(bo.toByteArray(), "UTF-8").split("\n")) {
                s2 = s2.trim();
                if (s2.isEmpty() || s2.startsWith("EDITTMP")) continue;
                if (s2.startsWith("----")) { cc = got.size(); seenDiv = true; continue; }   // host: customs end here
                got.add(s2);
            }
            if (!got.isEmpty() && !got.equals(new java.util.ArrayList<String>(names))) {
                names.clear(); names.addAll(got);
                customCount = seenDiv ? cc : 0;
                Logger.log("films pulled from host: " + got.size());
                applyWantFilm();                       // reselect the persisted film by name now that the list is here
                renderOverlay();
            } else if (!got.isEmpty()) {
                Logger.log("films pulled, unchanged (" + got.size() + ")");
            }
        } catch (Throwable t) { Logger.log("films pull fail: " + t); }
    }

    private static final String[] SCALE_NAMES = {"6MP", "2MP", "24MP", "Phone"};

    private static final File SD = android.os.Environment.getExternalStorageDirectory();
    private static final File[] FILM_ROOTS = {
        new File(SD, "OpenFilm6K"), new File("/mnt/sdcard/OpenFilm6K"), new File("/sdcard/OpenFilm6K"),
    };
    private static final String[] SCENE_MODES = {"program-auto", "aperture-priority", "shutter-speed", "manual-exposure"};
    private static final String[] SCENE_NAMES = {"P", "A", "S", "M"};

    private SurfaceView surface;
    private FBox fbox;   // lazily created in onCreate (field-init View crashes on this firmware)

    static class FBox extends View {
        volatile boolean netReady = true;
        volatile long verUntil = 0L;             // show build number top-left for ~1s after entry
        volatile int wifiPct = 0;
        volatile int state = 0;
        volatile String params = "";
        FBox(android.content.Context c) { super(c); }
        void set(int st) { state = st; postInvalidate(); }
        void setParams(String ps) { params = ps; postInvalidate(); }
        android.graphics.Typeface tf = null;
        volatile float progress = -1f;   // <0 hidden, 0..1 upload
        volatile long doneUntil = 0L;
        void beginUpload() { progress = 0f; doneUntil = 0L; postInvalidate(); }
        void setProgress(float f) { progress = f; postInvalidate(); }
        void endUpload() { progress = -1f; postInvalidate(); }
        void showDone() { progress = -1f; doneUntil = System.currentTimeMillis() + 2200L; postInvalidate(); postInvalidateDelayed(2300); }
        void setFilm(String f) { film = f; postInvalidate(); }
        volatile String film = "";
        volatile String batt = "";
        volatile String menuLines = null;
        volatile int menuSel = -1;
        volatile boolean[] menuFav = null;
        void setMenu(java.util.List<String> items, int selIdx, boolean[] favs) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) { if (i > 0) sb.append((char) 10); sb.append(items.get(i)); }
            menuLines = sb.toString(); menuSel = selIdx; menuFav = favs; postInvalidate();
        }
        void clearMenu() { if (menuLines != null) { menuLines = null; postInvalidate(); } }
        static final String PSEP = "|";
        volatile int hl = -1;
        volatile int spotX = 0, spotY = 0;
        volatile boolean spotOn = false;
        volatile boolean hideAf = false;   // lens-cap countdown: suppress AF area + grid
        volatile boolean filmFav = false;
        volatile boolean c1 = false;
        volatile String stampMark = "";
        volatile int pairDisp = -1;        // -1 hidden; 0 -> "[ 1 ]", 1 -> "[ 2 ]" (H/2 pair capture)
        volatile boolean pairBlink = true;
        volatile boolean halfMask = false;  // H (half-frame): black out the outer quarters, keep the central half
        volatile android.graphics.Bitmap ghostBmp;  // 1st-exposure still, drawn translucent over the live viewfinder
        volatile boolean ghostOn = false;
        volatile boolean ghostWait = false;         // dim the view while the 1st exposure's ghost is being prepared
        volatile float ghostAlpha = 1.0f;   // global multiplier on top of the per-pixel luminance alpha
        volatile float ghostScale = 0.75f;  // the live preview occupies ~75% of the screen width (side bars)
        volatile int ghostFinder = 0;       // 0 = LCD (9:8), 1 = EVF (3:2)
        android.graphics.Paint ghostPaint;
        volatile String afMode = "";
        volatile String afName = "";
        volatile String afDisp = "";
        static int segOf(char ch) { switch (ch) { case '0': return 0x3F; case '1': return 0x06; case '2': return 0x5B; case '3': return 0x4F; case '4': return 0x66; case '5': return 0x6D; case '6': return 0x7D; case '7': return 0x07; case '8': return 0x7F; case '9': return 0x6F; case 'A': return 0x77; case 'C': return 0x39; case 'E': return 0x79; case 'F': return 0x71; case 'I': return 0x30; case 'L': return 0x38; case 'O': return 0x3F; case 'P': return 0x73; case 'S': return 0x6D; case '-': return 0x40; default: return 0; } }
        void drawSegChar(android.graphics.Canvas cv, android.graphics.Paint p, char ch, float x, float y, float w, float h) {
            int m = segOf(ch); if (m == 0) return; float t = w * 0.22f;
            if ((m & 1) != 0) cv.drawRect(x, y, x + w, y + t, p);
            if ((m & 0x40) != 0) cv.drawRect(x, y + h / 2 - t / 2, x + w, y + h / 2 + t / 2, p);
            if ((m & 8) != 0) cv.drawRect(x, y + h - t, x + w, y + h, p);
            if ((m & 2) != 0) cv.drawRect(x + w - t, y, x + w, y + h / 2, p);
            if ((m & 4) != 0) cv.drawRect(x + w - t, y + h / 2, x + w, y + h, p);
            if ((m & 0x20) != 0) cv.drawRect(x, y, x + t, y + h / 2, p);
            if ((m & 0x10) != 0) cv.drawRect(x, y + h / 2, x + t, y + h, p);
        }
        android.graphics.Paint sysP = null;
        android.graphics.Typeface cjkTf = null;
        android.graphics.Typeface cjk() {
            if (cjkTf == null) {
                String[] cands = {
                    "/system/fonts/MYingHeiC-GB18030-SJ.ttf",
                    "/system/fonts/MYingHeiC-Big5HKSCS-SJ.ttf",
                    "/system/fonts/HeiseiKakuGothW5-SJ.ttf",
                };
                for (int i = 0; i < cands.length && cjkTf == null; i++) {
                    try { cjkTf = android.graphics.Typeface.createFromFile(cands[i]); } catch (Throwable t) {}
                }
                if (cjkTf == null) cjkTf = android.graphics.Typeface.DEFAULT;
            }
            return cjkTf;
        }
        android.graphics.Paint sys(android.graphics.Paint base) {
            if (sysP == null) sysP = new android.graphics.Paint(base);
            else sysP.set(base);
            sysP.setTypeface(cjk());
            return sysP;
        }
        void drawMixed(android.graphics.Canvas cv, android.graphics.Paint p, String txt, float x, float y) {
            if (tf == null) { cv.drawText(txt, x, y, p); return; }
            int i = 0, n = txt.length(); float cx = x;
            while (i < n) {
                boolean latin = txt.charAt(i) < 0x80;
                int j = i;
                while (j < n && (txt.charAt(j) < 0x80) == latin) j++;
                String run = txt.substring(i, j);
                android.graphics.Paint pp = latin ? p : sys(p);
                cv.drawText(run, cx, y, pp);
                cx += pp.measureText(run);
                i = j;
            }
        }
        float measureMixed(android.graphics.Paint p, String txt) {
            if (tf == null) return p.measureText(txt);
            int i = 0, n = txt.length(); float wsum = 0;
            while (i < n) {
                boolean latin = txt.charAt(i) < 0x80;
                int j = i;
                while (j < n && (txt.charAt(j) < 0x80) == latin) j++;
                android.graphics.Paint pp = latin ? p : sys(p);
                wsum += pp.measureText(txt.substring(i, j));
                i = j;
            }
            return wsum;
        }
        float segWidth(android.graphics.Paint p, String txt, float chh, float gap) {
            if (tf != null) { p.setTypeface(tf); p.setTextSize(chh); return measureMixed(p, txt); }
            return txt.length() * (chh * 0.62f + gap);
        }
        void drawSegTextCenter(android.graphics.Canvas cv, android.graphics.Paint p, String[] parts, float cx, float y, float chh, float gap, int hlSel) {
            float total = 0;
            for (int i = 0; i < parts.length; i++) total += segWidth(p, parts[i], chh, gap);
            total += gap * 2f * (parts.length - 1);
            float x = cx - total / 2f;
            for (int i = 0; i < parts.length; i++) {
                p.setColor(hlSel == i + 1 ? 0xFFFF9500 : 0xFFFFFFFF);
                drawSegText(cv, p, parts[i], x, y, chh, gap);
                x += segWidth(p, parts[i], chh, gap) + gap * 2f;
            }
        }
        void drawSegText(android.graphics.Canvas cv, android.graphics.Paint p, String txt, float x, float y, float chh, float gap) {
            if (tf != null) { p.setTypeface(tf); p.setTextSize(chh); drawMixed(cv, p, txt, x, y + chh); return; }
            float cx = x;
            for (int i = 0; i < txt.length(); i++) {
                char ch = txt.charAt(i);
                if (ch == '.') { cv.drawCircle(cx + chh * 0.12f, y + chh, chh * 0.08f, p); cx += chh * 0.4f; continue; }
                if (ch == '/') { cv.drawLine(cx + chh * 0.5f, y, cx + chh * 0.15f, y + chh, p); cx += chh * 0.65f; continue; }
                if (ch == ' ') { cx += chh * 0.6f; continue; }
                drawSegChar(cv, p, ch, cx, y, chh * 0.62f, chh);
                cx += chh * 0.62f + gap;
            }
        }
        void drawMenu(android.graphics.Canvas cv) {
            if (menuLines == null) return;
            int w = getWidth(), h = getHeight();
            android.graphics.Paint mp = new android.graphics.Paint();
            mp.setAntiAlias(true); mp.setShadowLayer(3, 1, 2, 0xFF000000);
            if (tf != null) mp.setTypeface(tf);
            float mch = h / 22f;
            mp.setTextSize(mch);
            String[] lines = menuLines.split("\n");
            float lh = mch * 1.35f;
            int maxLines = Math.max(3, (int) ((h * 0.62f) / lh));
            int start = 0;
            if (menuSel >= maxLines / 2) start = menuSel - maxLines / 2;
            if (start + maxLines > lines.length) start = Math.max(0, lines.length - maxLines);
            float y0 = h * 0.20f;
            for (int i = start; i < Math.min(lines.length, start + maxLines); i++) {
                boolean hot = (i == menuSel);
                boolean fav = (menuFav != null && i < menuFav.length && menuFav[i]);
                int col = hot ? 0xFFFF9500 : (fav ? 0xFF66CCFF : 0xFFFFFFFF);
                mp.setColor(col);
                String t = (fav ? "* " : "") + lines[i];
                float tw = measureMixed(mp, t);
                drawMixed(cv, mp, t, (w - tw) / 2f, y0 + (i - start) * lh);
            }
        }

        void drawBoot(android.graphics.Canvas cv) {
            int w = getWidth(), h = getHeight();
            cv.drawColor(0xFF000000);
            android.graphics.Paint vp = new android.graphics.Paint(); vp.setAntiAlias(true);
            if (tf != null) vp.setTypeface(tf);
            vp.setTextSize(h / 19f); vp.setColor(0xFFFF9500);
            cv.drawText("#" + BUILD, w * 0.03f, (h / 19f) * 1.25f, vp);
            if ((System.currentTimeMillis() / 500) % 2 == 0) {      // blink
                android.graphics.Paint tp = new android.graphics.Paint(); tp.setAntiAlias(true);
                if (tf != null) tp.setTypeface(tf);
                tp.setTextSize(h / 19f); tp.setColor(0xFFFFFFFF);   // same size as the bottom two info rows
                String s = "connecting";
                float tw = tp.measureText(s);
                cv.drawText(s, (w - tw) / 2f, h / 2f + (h / 19f) * 0.35f, tp);
            }
        }
        /** draw the ghost to match the live preview's framing on the physical screen.
         *  The rear panel is 16:9 (ro.panel.aspect=169) but the frame buffer is 640x480 (4:3), so on the
         *  panel everything is stretched horizontally x4/3. A true 3:2 viewfinder rect must therefore be
         *  pre-squeezed to 9:8 in the frame buffer: fill the height, width = (3/2)/h / (4/3) = 9h/8. */
        void drawFit(android.graphics.Canvas cv, android.graphics.Bitmap bm, android.graphics.Paint pt) {
            int w = getWidth(), h = getHeight();
            float dw, dh;
            if (ghostFinder == 1) {            // EVF (3:2, no panel stretch): the ghost is 3:2 in the frame buffer
                dw = w; dh = w / 1.5f;
            } else {                           // LCD: the 16:9 panel stretches the 4:3 frame buffer x4/3, so a 3:2 viewfinder rect is 9:8 in fb
                dh = h; dw = (1.5f * dh) / (4f / 3f);
            }
            float dx = (w - dw) / 2f, dy = (h - dh) / 2f;
            cv.drawBitmap(bm, null, new android.graphics.RectF(dx, dy, dx + dw, dy + dh), pt);
        }

        public void onDraw(android.graphics.Canvas cv) {
            if (verUntil == 0L) verUntil = System.currentTimeMillis() + 1000;   // 1s window starts at first real draw
            if (!netReady) { drawBoot(cv); return; }
            if (menuLines != null) {
                android.graphics.Paint mask = new android.graphics.Paint();
                mask.setColor(0xB0000000);
                cv.drawRect(0, 0, getWidth(), getHeight(), mask);
                drawMenu(cv);
                return;
            }
            if (hideAf) return;   // countdown on screen: no focus area / grid
            int w = getWidth(), h = getHeight();
            if (ghostWait) {                       // double-exposure: 1st shot taken, the ghost is being prepared
                cv.drawColor(0xFF000000);          // opaque black, no HUD — just a blinking centred "processing"
                if ((System.currentTimeMillis() / 450) % 2 == 0) {
                    android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    if (tf != null) tp.setTypeface(tf);
                    tp.setTextSize(h / 19f);           // identical font + size to the rest of the HUD
                    tp.setColor(0xFFFFFFFF);
                    String s = "processing";
                    cv.drawText(s, (w - tp.measureText(s)) / 2f, h / 2f + (h / 19f) * 0.35f, tp);
                }
                postInvalidateDelayed(450);        // keep it blinking until the ghost is ready
                return;
            }
            if (ghostOn && ghostBmp != null) {     // double-exposure: translucent ghost of the 1st exposure over the live viewfinder
                if (ghostPaint == null) ghostPaint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
                ghostPaint.setAlpha((int) (Math.max(0f, Math.min(1f, ghostAlpha)) * 255f));
                drawFit(cv, ghostBmp, ghostPaint);
            }
            if (System.currentTimeMillis() < verUntil) {
                android.graphics.Paint vp = new android.graphics.Paint(); vp.setAntiAlias(true);
                if (tf != null) vp.setTypeface(tf);
                vp.setTextSize(h / 19f); vp.setColor(0xFFFF9500);
                cv.drawText("#" + BUILD, w * 0.03f, (h / 19f) * 1.25f, vp);
                postInvalidateDelayed(verUntil - System.currentTimeMillis() + 80);
            }
            if (halfMask) {                         // H (half-frame): black out the outer quarters first (indicator/HUD stay on top)
                android.graphics.Paint hp = new android.graphics.Paint();
                hp.setColor(0xFF000000);
                float q = w * 0.25f;
                cv.drawRect(0, 0, q, h, hp);
                cv.drawRect(w - q, 0, w, h, hp);
            }
            if (pairDisp >= 0 && pairBlink) {       // H/2: blinking "[ 1 ]" / "[ 2 ]" frame counter (brackets rotated 90 deg)
                android.graphics.Paint pp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                if (tf != null) pp.setTypeface(tf);
                float psz = h / 19f;                        // same size as the other HUD text
                pp.setTextSize(psz);
                pp.setColor(0xFF00E676);                    // green
                pp.setShadowLayer(3, 1, 2, 0xFF000000);
                String digit = String.valueOf(pairDisp + 1);
                float bw = Math.max(2f, psz * 0.14f);       // bracket stroke
                float arm = psz * 0.22f;                    // bracket arm length
                float bh = psz * 1.30f;                     // bracket length
                float iw = psz * 0.95f + pp.measureText("0");   // FIXED interior spacing (same for 1 and 2)
                android.graphics.Rect tb = new android.graphics.Rect();
                pp.getTextBounds(digit, 0, digit.length(), tb);    // tight ink bounds (DSEG "1" is right-aligned in its cell)
                float rotW = bh, rotH = 2 * bw + iw;        // badge box after the 90deg rotation
                float cx = w * 0.045f + rotW * 0.5f;        // badge centre x
                float cy = psz * 0.35f + rotH * 0.5f;       // badge centre y
                cv.save();
                cv.rotate(90f, cx, cy);                     // rotate the pair 90deg; the interior spacing iw is preserved
                float ux0 = cx - (2 * bw + iw) * 0.5f, uy0 = cy - bh * 0.5f;   // unrotated layout centred on (cx,cy)
                cv.drawRect(ux0, uy0, ux0 + bw, uy0 + bh, pp);                 // bracket 1: '['
                cv.drawRect(ux0, uy0, ux0 + bw + arm, uy0 + bw, pp);
                cv.drawRect(ux0, uy0 + bh - bw, ux0 + bw + arm, uy0 + bh, pp);
                cv.save();
                cv.rotate(180f, cx, cy);                    // bracket 2: '[' rotated 180deg about the badge centre
                cv.drawRect(ux0, uy0, ux0 + bw, uy0 + bh, pp);
                cv.drawRect(ux0, uy0, ux0 + bw + arm, uy0 + bw, pp);
                cv.drawRect(ux0, uy0 + bh - bw, ux0 + bw + arm, uy0 + bh, pp);
                cv.restore();
                cv.restore();
                cv.drawText(digit, cx - (tb.left + tb.right) * 0.5f,   // digit stays upright, centred on the badge
                        cy - (tb.top + tb.bottom) * 0.5f, pp);
            }
            android.graphics.Paint p = new android.graphics.Paint();
            p.setAntiAlias(true);
            // 3x3 rule-of-thirds grid + diagonals (thin, subtle)
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(0x50FFFFFF);

            // center AF: '+' with two verticals left/right and two horizontals below (all font glyphs)
            android.graphics.Paint ap = new android.graphics.Paint(); ap.setAntiAlias(true); ap.setShadowLayer(3, 1, 2, 0xFF000000);
            float base = h / 19f;                      // glyph size = same as all other text
            if (tf != null) ap.setTypeface(tf);
            ap.setTextSize(base);
            boolean showRect = afMode.equals("local") || afMode.equals("center");
            float ms = afMode.equals("local") ? 2.0f : afMode.equals("center") ? 0.72f : 1.0f;
            ap.setColor(state == 1 ? 0xFFFFFFFF : state == 2 ? 0xFF00E676 : state == 3 ? 0xFFFF5252 : (spotOn ? 0xFFFF9500 : 0xA0FFFFFF));
            if (afMode.length() > 0 && progress < 0f && System.currentTimeMillis() >= doneUntil) {   // also hidden while Done shows
                android.graphics.Paint mp2 = new android.graphics.Paint(); mp2.setAntiAlias(true);
                if (tf != null) mp2.setTypeface(tf);
                mp2.setTextSize(h / 19f); mp2.setColor(spotOn ? 0xFFFF9500 : 0xFFFFFFFF);   // orange while adjusting, white otherwise
                mp2.setShadowLayer(3, 1, 2, 0xFF000000);
                String mt = "AF " + (afDisp.length() > 0 ? afDisp : afMode.toUpperCase());
                cv.drawText(mt, (w - mp2.measureText(mt)) / 2f, (h / 19f) * 1.6f, mp2);
            }
            int ccx = w / 2 + (int) (spotX * (w / 2f) / 1000f), ccy = h / 2 + (int) (spotY * (h / 2f) / 1000f);
            if (!showRect) { cv.drawText("+", ccx - ap.measureText("+") / 2f, ccy + base * 0.32f, ap); }
            else {
            float gapx = (h / 13f) * 1.6f * ms, gapy = (h / 13f) * 1.9f * ms;   // size via offsets
            float cw = ap.measureText("-"), lw = ap.measureText("|"), lh2 = base * 1.25f;
            android.graphics.Rect tb = new android.graphics.Rect();
            ap.getTextBounds("-", 0, 1, tb);
            float dCX = (tb.left + tb.right) / 2f, dCY = (tb.top + tb.bottom) / 2f;   // dash ink center
            ap.getTextBounds("|", 0, 1, tb);
            float pCX = (tb.left + tb.right) / 2f, pCY = (tb.top + tb.bottom) / 2f;
            ap.getTextBounds("+", 0, 1, tb);
            float xCX = (tb.left + tb.right) / 2f, xCY = (tb.top + tb.bottom) / 2f;
            int nH = Math.max(3, Math.round(gapx * 2f / cw));
            int nV = Math.max(3, Math.round(gapy * 2f / lh2));
            float x0 = ccx - (nH - 1) * cw / 2f - dCX;               // dash ink symmetric around ccx
            float ryT = ccy - gapy - dCY, ryB = ccy + gapy - dCY;    // ink centers on the edge lines
            for (int i = 0; i < nH; i++) {
                float x = x0 + i * cw;
                cv.drawText("-", x, ryT, ap);
                cv.drawText("-", x, ryB, ap);
            }
            float y0 = ccy - (nV - 1) * lh2 / 2f - pCY;              // pipe ink symmetric around ccy
            for (int i = 0; i < nV; i++) {
                float y = y0 + i * lh2;
                cv.drawText("|", ccx - gapx - pCX, y, ap);
                cv.drawText("|", ccx + gapx - pCX, y, ap);
            }
            cv.drawText("+", ccx - xCX, ccy - xCY, ap);              // cross ink centered
            }
            // bottom A6000-style spread: shutter  F5.6  EV  ISO
            android.graphics.Paint tp = new android.graphics.Paint(); tp.setAntiAlias(true); tp.setShadowLayer(3, 1, 2, 0xFF000000);
            float chh = h / 19f;
            float y2 = h - chh * 1.4f;
            float y1 = y2 - chh * 0.45f;   // film+battery row moved one char height lower
            String[] rowParts = params.length() > 0 ? params.split(java.util.regex.Pattern.quote(PSEP)) : new String[0];
            float rowGap = chh * 0.40f;
            float rowW = 0f;
            for (int ri = 0; ri < rowParts.length; ri++) rowW += segWidth(tp, rowParts[ri], chh, rowGap);
            if (rowParts.length > 1) rowW += rowGap * 2f * (rowParts.length - 1);
            float rowL = (w - rowW) / 2f;
            float rowR = rowL + rowW;
            android.graphics.Paint fp = new android.graphics.Paint(tp);
            float fch = chh * 0.95f;
            if (tf != null) { fp.setTypeface(tf); fp.setTextSize(fch); }
            else fp.setTextSize(chh * 0.85f);
            if (film.length() > 0) {
                fp.setColor(c1 ? 0xFF66CCFF : (hl == 0 ? 0xFFFF9500 : 0xFFFFFFFF));   // visible in focus state too
                drawMixed(cv, fp, (filmFav ? "* " : "") + film, rowL, y1);
            }
            float battLeft = rowR;
            if (batt.length() > 0) {
                fp.setColor(0xFFFFFFFF);
                float bw2 = fp.measureText(batt);
                float btx = rowR - bw2;
                cv.drawText(batt, btx, y1, fp);
                int pctv = 0;
                try { pctv = Integer.parseInt(batt.replace("%", "")); } catch (Throwable ig) {}
                float bh2 = chh * 0.72f, bwid = chh * 2.4f;   // height matches text cap height
                float bx2 = btx - bwid - chh * 0.5f;
                battLeft = bx2;
                if (stampMark.length() > 0) {
                    fp.setColor(0xFFFFFFFF);
                    float mw = fp.measureText(stampMark);
                    cv.drawText(stampMark, bx2 - chh * 0.45f - mw, y1, fp);
                }
                float by2 = y1 - bh2 / 2f - chh * 0.35f;   // center-align bar with text
                android.graphics.Paint ob = new android.graphics.Paint();
                ob.setAntiAlias(true); ob.setStyle(android.graphics.Paint.Style.STROKE);
                ob.setStrokeWidth(2); ob.setColor(0xFFFFFFFF);
                cv.drawRect(bx2, by2, bx2 + bwid, by2 + bh2, ob);
                android.graphics.Paint fl = new android.graphics.Paint();
                fl.setColor(pctv <= 15 ? 0xFFFF5252 : pctv <= 35 ? 0xFFFF9500 : 0xFF00E676);
                float fillw = (bwid - 4) * Math.max(0f, Math.min(1f, pctv / 100f));
                cv.drawRect(bx2 + 2, by2 + 2, bx2 + 2 + fillw, by2 + bh2 - 2, fl);
            }
            float pgtxt = 0f;
            if (progress >= 0f) {
                android.graphics.Paint up = new android.graphics.Paint(tp);
                float uch = chh * 0.85f;
                if (tf != null) { up.setTypeface(tf); up.setTextSize(uch); } else up.setTextSize(chh * 0.7f);
                up.setColor(0xFFFFFFFF);
                String pct = "DEVELOPING " + ((int) (progress * 100f)) + "%";
                float uw = up.measureText(pct);
                float uy = chh * 1.6f;   // same baseline as the AF mode label
                cv.drawText(pct, (w - uw) / 2f, uy, up);
                android.graphics.Paint bar = new android.graphics.Paint();
                bar.setAntiAlias(true);
                bar.setStyle(android.graphics.Paint.Style.STROKE);
                bar.setStrokeWidth(2);
                bar.setColor(0xFFFFFFFF);
                float bx = w * 0.15f, bw = w * 0.70f, by = uy + uch * 0.45f, bh = chh * 0.55f;
                cv.drawRect(bx, by, bx + bw, by + bh, bar);
                android.graphics.Paint fill = new android.graphics.Paint();
                fill.setColor(0xFFFF9500);
                cv.drawRect(bx + 2, by + 2, bx + 2 + (bw - 4) * Math.max(0f, Math.min(1f, progress)), by + bh - 2, fill);
            }
            if (System.currentTimeMillis() < doneUntil) {
                android.graphics.Paint dn = new android.graphics.Paint(tp);
                float dch = chh * 1.1f;
                if (tf != null) { dn.setTypeface(tf); dn.setTextSize(dch); } else dn.setTextSize(chh * 0.9f);
                dn.setColor(0xFF00E676);
                float dw = dn.measureText("Done");
                cv.drawText("Done", (w - dw) / 2f, chh * 1.6f, dn);   // aligned with AF label position
            }
            if (rowParts.length > 0) {
                android.graphics.Paint sp = new android.graphics.Paint(tp);
                drawSegTextCenter(cv, sp, rowParts, w / 2f, y2, chh, rowGap, hl);
            }
        }
    }
    private TextView hud, status, overlay;
    private android.widget.ImageView viewer;
    private final Handler handler = new Handler();
    private CameraRig rig = new CameraRig();

    private List<File> films = new ArrayList<File>();
    private List<String> names = new ArrayList<String>();
    private int sel = 0;
    private static final int NSPECIAL = 2;   // sel: 0=random, 1=favorite, >=2 => real film (sel-2)
    private int customCount = 0;             // user films at the head of names; >0 => divider row after them
    private final java.util.Random rnd = new java.util.Random();
    private int totalSel() { return names.size() + NSPECIAL + (customCount > 0 ? 1 : 0); }
    private int divSel() { return customCount > 0 ? NSPECIAL + customCount : -1; }   // display pos of the divider
    private int filmIdx(int s) { return s - NSPECIAL - (divSel() >= 0 && s > divSel() ? 1 : 0); }   // display pos -> names idx
    private int posOf(int idx) { return NSPECIAL + idx + (divSel() >= 0 && idx >= customCount ? 1 : 0); }   // names idx -> display pos
    private String selName(int s) {
        if (s == 0) return "random";
        if (s == 1) return "favorite";
        if (s == divSel()) return "----------------";
        int r = filmIdx(s);
        return (r >= 0 && r < names.size()) ? names.get(r) : "?";
    }
    private boolean selFav(int s) { int r = filmIdx(s); return r >= 0 && r < names.size() && favs.contains(names.get(r)); }
    private int resolveSel(int s) {
        if (names.isEmpty()) return 0;
        if (s == 0) return rnd.nextInt(names.size());
        if (s == 1) {
            java.util.ArrayList<Integer> f = new java.util.ArrayList<Integer>();
            for (int i = 0; i < names.size(); i++) if (favs.contains(names.get(i))) f.add(i);
            return f.isEmpty() ? rnd.nextInt(names.size()) : f.get(rnd.nextInt(f.size()));
        }
        int r = filmIdx(s);
        return (r >= 0 && r < names.size()) ? r : 0;
    }
    private java.util.List<String> displayNames() {
        java.util.ArrayList<String> l = new java.util.ArrayList<String>();
        l.add("random"); l.add("favorite");
        for (int i = 0; i < names.size(); i++) {
            if (i == customCount && customCount > 0) l.add("----------------");   // un-selectable Win98 separator
            l.add(names.get(i));
        }
        return l;
    }
    private File LUTS, TEX, GRADED;

    // dial state
    private static final String[] MODES = {"Mode", "Shutter", "Aperture", "ISO", "EV", "Film"};
    private int dialMode = 5;                 // start on film select
    private int sceneIdx = 0;
    private int isoIdx = 0;
    private List<Integer> isos = new ArrayList<Integer>();
    private int ev = 0, evMin = -2, evMax = 2;

    // ui state
    private static final int OV_FULL = 0, OV_MINI = 1, OV_NONE = 2;
    private int overlayState = OV_FULL;
    private int browser = -1;                 // >=0: film browser cursor
    private java.util.ArrayList<File> graded = new java.util.ArrayList<File>();
    private int viewing = -1;                 // >=0: viewer cursor into graded
    private int settings = -1;                // >=0: settings cursor
    private static final String[] SETTINGS_ROWS = {"Quality scale", "Process last shot", "Restart", "Close"};
    private int qualityIdx = 0;
    private long lastMenu = 0;

    // capture + grading
    private static class Job {
        final File src; final int filmIdx;
        final File src2; final int filmIdx2; final boolean pair;   // H/2: two-frame batch
        Job(File s, int f) { this(s, f, null, -1, false); }
        Job(File s, int f, File s2, int f2, boolean p) { src = s; filmIdx = f; src2 = s2; filmIdx2 = f2; pair = p; }
    }
    private final LinkedList<Job> queue = new LinkedList<Job>();
    private int done = 0;
    private volatile boolean grading = false;

    // ---- H (half-frame) / 2 (double-exposure): paired two-shot capture ----
    private static final int PAIR_OFF = -1, PAIR_FRAME1 = 0, PAIR_FRAME2 = 1;
    private volatile int pairSlot = PAIR_OFF;        // INDICATOR: 0->[1], 1->[2] (advances on shutter press)
    private volatile int pairStaged = 0;             // file-staging counter (0: expect frame1, 1: expect frame2)
    private File pair0File;                          // frame 1 staged for the batch
    private int pair0Film = -1;                      // film locked when frame 1 was pressed
    private final LinkedList<Integer> shotFilms = new LinkedList<Integer>();   // per-shot film FIFO
    private volatile boolean pairBlinkOn = true;

    // ---- double-exposure (mode '2'): the first exposure's still is shown as a translucent ghost over the
    //      live viewfinder while the second exposure is composed. The A6000 HAL never hands live preview
    //      frames to the app, so a true SCREEN blend over the viewfinder is impossible; this is an
    //      approximation (the exact SCREEN composite of the two stills is produced by the host). ----
    private volatile boolean ghostOn = false;
    private volatile boolean ghostWait = false;   // after the 1st shot: dim the view until the ghost is ready
    private volatile android.graphics.Bitmap ghostBmp;
    private volatile float ghostAlpha = 1.0f;   // global multiplier on top of the per-pixel luminance alpha
    private int ghostFinder = 0;                 // 0 = LCD (9:8), 1 = EVF (3:2): which screen the ghost is sized for
    private String wantFilm = "";                // persisted selected film (by NAME; index is unstable)

    // ---- lens-cap auto sleep: preview stays near-black -> cap on -> sleep after 10s ----
    private boolean capDim = false;                // screen dimmed by lens-cap sleep
    private long capDarkSince = 0;                 // manual-lock countdown start; 0 = idle
    private float capPrevBrightness = -1f;
    private long capGapRecent = 33;
    private long capOneShotAt = 0;        // guard: register buffers ONCE per camera open (2nd pair OOMs and kills the stream)                // recent typical inter-frame gap (ms); stall threshold = clamp(2x, 2s, 32s)
    private android.widget.TextView capCount;      // centered A-mode countdown (shutter pinned at 30s for 10s)
    private android.widget.TextView capLabel;      // "UNTIL SLEEP" under the number
    private android.widget.LinearLayout capBoxView;   // countdown container (visibility toggled as a whole)
    private android.widget.TextView filmWarn;        // centered red NO FILM LIST warning
    private long sDbg = 0;                         // throttled debug log
    private long sDump = 0;                        // throttled full parameter dump in S mode
    private volatile long captureStart = 0;
    private volatile boolean netReady = false;
    private android.view.SurfaceHolder pendingHolder;
    private long bootStart = 0;

    private static int BUILD = 0;

    // ---------------------------------------------------------------- setup

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        // runtime permissions (targetSdk 24+): camera + storage must be granted dynamically
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            String[] need = {
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            };
            boolean missing = false;
            for (String pm : need) if (checkSelfPermission(pm) != android.content.pm.PackageManager.PERMISSION_GRANTED) missing = true;
            if (missing) { requestPermissions(need, 1); /* carry on: camera retries once the user grants */ }
        }
        int build = 0;
        try { build = getResources().getInteger(R.integer.build_number); } catch (Throwable t) {}
        BUILD = build;
        Logger.log("--- OpenFilm6K camera build #" + build + " ---");

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                Logger.log("UNCAUGHT on " + t.getName() + ": " + e);
                StackTraceElement[] st = e.getStackTrace();
                for (int i = 0; i < st.length && i < 12; i++) Logger.log("  at " + st[i]);
            }
        });

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        surface.getHolder().setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        viewer = new android.widget.ImageView(this);
        viewer.setBackgroundColor(0xFF000000);
        viewer.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        viewer.setVisibility(View.INVISIBLE);
        viewer.setPadding(0, 0, 0, 0);

        hud = mkText(18, Color.WHITE, 0x66000000);
        status = mkText(16, 0xFFF2B85C, 0x66000000);
        overlay = mkText(18, Color.WHITE, 0xEE101010);

        FrameLayout root = new FrameLayout(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams wrap = new FrameLayout.LayoutParams(-2, -2);
        root.addView(hud, wrap);
        root.addView(status, wrap);
        fbox = new FBox(this);
        try { fbox.tf = android.graphics.Typeface.createFromAsset(getAssets(), "DSEG14.ttf"); } catch (Throwable t) { Logger.log("font load: " + t); }
        try {
            android.content.IntentFilter bf = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.BroadcastReceiver br = new android.content.BroadcastReceiver() {
                public void onReceive(android.content.Context c, android.content.Intent i) {
                    int lv = i.getIntExtra("level", -1), sc = i.getIntExtra("scale", 100);
                    if (lv >= 0 && fbox != null) { fbox.batt = (lv * 100 / Math.max(1, sc)) + "%"; fbox.postInvalidate(); }
                }
            };
            registerReceiver(br, bf);
            android.content.Intent bi = registerReceiver(null, bf);
            if (bi != null) { int lv = bi.getIntExtra("level", -1), sc = bi.getIntExtra("scale", 100); if (lv >= 0) fbox.batt = (lv * 100 / Math.max(1, sc)) + "%"; }
        } catch (Throwable t) { Logger.log("batt: " + t); }
        root.addView(fbox, new FrameLayout.LayoutParams(-1, -1));
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        android.widget.LinearLayout capBox = new android.widget.LinearLayout(this);
        capBoxView = capBox;
        capBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        capBox.setGravity(android.view.Gravity.CENTER);
        capCount = mkText(36, 0xFF2196F3, 0x00000000);
        capCount.setTypeface(fbox.tf);
        capCount.setShadowLayer(3, 1, 2, 0xFF000000);   // same drop shadow as all other on-screen text
        capCount.setGravity(android.view.Gravity.CENTER);
        capLabel = mkText(14, 0xFF64A9F5, 0x00000000);
        capLabel.setTypeface(fbox.tf);   // DSEG14, same as everything else
        capLabel.setShadowLayer(3, 1, 2, 0xFF000000);
        capLabel.setText("TO!SLEEP");   // DSEG14: '!' is a full-width blank cell (0.816em; ' ' is only 0.2em)
        capLabel.setGravity(android.view.Gravity.CENTER);
        android.widget.FrameLayout numWrap = new android.widget.FrameLayout(this);
        android.widget.FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2);
        cp.gravity = android.view.Gravity.CENTER_HORIZONTAL;   // number dead-center horizontally
        numWrap.addView(capCount, cp);
        capCount.setVisibility(View.VISIBLE);   // mkText defaults to INVISIBLE
        capLabel.setVisibility(View.VISIBLE);
        capBox.addView(numWrap, new android.widget.LinearLayout.LayoutParams(-1, -2));
        capBox.addView(capLabel, new android.widget.LinearLayout.LayoutParams(-1, -2));
        capBox.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -1);
        bp.gravity = android.view.Gravity.CENTER;   // whole box vertically centered on screen
        root.addView(capBox, bp);
        filmWarn = mkText(24, 0xFFFF5252, 0x00000000);   // same red as the AF-fail state
        filmWarn.setTypeface(fbox.tf);                    // DSEG14, same as everything else
        filmWarn.setShadowLayer(3, 1, 2, 0xFF000000);     // same drop shadow
        filmWarn.setText("NO FILM LIST");
        filmWarn.setGravity(android.view.Gravity.CENTER);
        filmWarn.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(-1, -1);
        wp.gravity = android.view.Gravity.CENTER;
        root.addView(filmWarn, wp);
        root.addView(viewer, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        android.content.SharedPreferences pf = getPreferences(MODE_PRIVATE);
        sel = pf.getInt("film2", -1);
        stampMode = pf.getInt("stamp", 0);
        ghostFinder = pf.getInt("gfinder", 0);
        wantFilm = pf.getString("selfilm", "");
        favs.clear();
        String fv = pf.getString("favs", "");
        if (fv.length() > 0) {
            String[] parts = fv.split(",");
            for (String p2 : parts) {
                try { int ix = Integer.parseInt(p2); if (ix >= 0 && ix < names.size()) favs.add(names.get(ix)); }   // legacy index entry, best effort
                catch (NumberFormatException nf) { if (!p2.isEmpty()) favs.add(p2); }                              // name entry
            }
        }
        qualityIdx = pf.getInt("quality", 0);

        onStampModeChanged();   // arm the H/2 pair capture if that mode was restored

        discoverFilms();
        if (sel < 0 || sel >= totalSel() || sel == divSel()) sel = names.isEmpty() ? 0 : NSPECIAL;
        applyWantFilm();
        hud.setPadding(dp(8), dp(4), dp(8), dp(4));
        status.setPadding(dp(8), dp(4), dp(8), dp(4));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) status.getLayoutParams();
        lp.topMargin = dp(120);
        status.setLayoutParams(lp);
        renderOverlay();
        handler.postDelayed(readoutTick, 700);
        self = this;
        startReturnServer();
        gradeThread = new Thread(gradeLoop, "grade");
        gradeThread.setDaemon(true);
        gradeThread.start();
        initWatchBaseline();
        bootWifi();
    }

    private TextView mkText(float size, int color, int bg) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setBackgroundColor(bg);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        t.setVisibility(View.INVISIBLE);
        return t;
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private void discoverFilms() {
        films.clear();
        names.clear();
        StringBuilder diag = new StringBuilder();
        for (File root : FILM_ROOTS) {
            if (!root.isDirectory()) continue;
            ArrayList<File> found = new ArrayList<File>();
            walk(root, 0, found);
            if (found.size() > 0 && LUTS == null) {
                LUTS = found.get(0).getParentFile();
                TEX = new File(LUTS.getParentFile(), "TEX");
                GRADED = new File(LUTS.getParentFile(), "GRADED");
                films = found;
            }
            diag.append(root).append(" -> ").append(found.size()).append('\n');
        }
        if (LUTS == null) { LUTS = FILM_ROOTS[0]; TEX = new File(FILM_ROOTS[0], "TEX"); GRADED = new File(FILM_ROOTS[0], "GRADED"); }
        films.clear();                                   // local .flm list; film names come from the host at runtime
        names.clear();
        Logger.log("films " + names.size() + " in " + LUTS + (films.isEmpty() ? " | " + diag : ""));
        if (GRADED != null) GRADED.mkdirs();
    }

    private static void walk(File d, int depth, ArrayList<File> found) {
        if (depth > 3) return;
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().toLowerCase().endsWith(".flm")) found.add(f);
            else if (f.isDirectory()) walk(f, depth + 1, found);
        }
    }

    private static String prettyName(String fn) {
        String n = fn.toUpperCase();
        if (n.endsWith(".FLM")) n = n.substring(0, n.length() - 4);
        return n;
    }

    private static String readName(File f) {
        try {
            DataInput in = new DataInputStream(new FileInputStream(f));
            byte[] head = new byte[216];
            in.readFully(head);
            if (head[0] != 'F' || head[1] != 'L' || head[2] != 'M') {
                Logger.log("readName " + f.getName() + " bad magic: " + head[0] + "," + head[1] + "," + head[2]);
                return f.getName();
            }
            String nm = utf8(head, 8, 40).trim();
            if (nm.length() == 0) nm = prettyName(f.getName());
            return nm;
        } catch (Throwable e) {
            Logger.log("readName " + f.getName() + " threw: " + e);
            return f.getName();
        }
    }

    private static String utf8(byte[] a, int off, int len) {
        int end = off;
        while (end < off + len && a[end] != 0) end++;
        try { return new String(a, off, end - off, "UTF-8"); } catch (Exception e) { return "?"; }
    }

    // ---------------------------------------------------------------- camera

    public void surfaceCreated(SurfaceHolder h) {}

    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        pendingHolder = h;
        if (netReady) startCamera(h);
    }

    public void surfaceDestroyed(SurfaceHolder h) {
        rig.release();
    }

    private void startCamera(SurfaceHolder h) {
        if (!rig.ready()) {
            try { java.io.FileWriter fw = new java.io.FileWriter("/sdcard/OpenFilm6K/open_marker.txt", true);
                  fw.write("open " + System.currentTimeMillis() + "\n"); fw.close(); } catch (Throwable ig) {}
            if (!rig.open()) {
                setStatus("camera open failed (see log)");
                return;
            }
            Camera.Parameters p = rig.camera().getParameters();
            evMin = p.getMinExposureCompensation();
            evMax = p.getMaxExposureCompensation();
            ev = p.getExposureCompensation();
            rig.syncFocusMode(p);
            int[] spt = CameraRig.readSystemSpot(p);            // adopt the system focus point position
            if (spt != null) { rig.adoptSpot(spt[0], spt[1]); spotX = spt[0]; spotY = spt[1]; Logger.log("sys spot " + spt[0] + "," + spt[1]); }
            else Logger.log("sys spot: unavailable");
            String sm = p.getSceneMode();                  // adopt the mode the system/firmware is currently in
            if (sm != null) {
                for (int i = 0; i < SCENE_MODES.length; i++) {
                    if (SCENE_MODES[i].equals(sm)) { sceneIdx = i; Logger.log("scene: system=" + sm + " -> " + SCENE_NAMES[i]); break; }
                }
            }
            List sup = rig.supportedIsos();
            isos.clear();
            if (sup != null) for (Object o : sup) isos.add(((Number) o).intValue());
            rig.setDriveSingle();
            rig.setAspectRatio32();
        }
        try {
            rig.camera().setPreviewDisplay(h);
            rig.startPreview();
        } catch (Throwable t) {
            Logger.log("preview: " + t);
            setStatus("viewfinder fail: " + t);
        }
    }



    /** 1s watchdog: with a lens cap in A-mode the exposure climbs to 30s and preview frames
     *  stall completely — no frames for >3s counts as black too, timed on the wall clock. */
    /** show the red NO FILM LIST warning for 2.5s (same design: DSEG14 + shadow, centered) */
    private void showFilmWarn() {
        if (filmWarn == null) return;
        filmWarn.setVisibility(View.VISIBLE);
        filmWarn.bringToFront();
        handler.removeCallbacks(filmWarnHide);
        handler.postDelayed(filmWarnHide, 2500);
    }
    private final Runnable filmWarnHide = new Runnable() { public void run() {
        if (filmWarn != null) filmWarn.setVisibility(View.GONE);
    } };

    private void capSleep() {
        // deliberately inert: no preview stop, no brightness change, no power/wakelock calls.
        // just flag the state and show the unlock hint — isolating what actually killed the keys.
        capDim = true; capDarkSince = 0;
        if (capBoxView != null) capBoxView.setVisibility(View.GONE);
        if (fbox != null) fbox.hideAf = false;
        try { rig.camera().stopPreview(); } catch (Throwable t) { Logger.log("sleep stopPreview: " + t); }
        try {
            overlay.setVisibility(View.INVISIBLE);
            hud.setVisibility(View.INVISIBLE);
            status.setVisibility(View.INVISIBLE);
            if (fbox != null) fbox.setVisibility(View.INVISIBLE);
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            capPrevBrightness = lp.screenBrightness;
            lp.screenBrightness = 0.01f;   // minimum SAFE value: 0.0f makes the whole system unresponsive on this firmware
            getWindow().setAttributes(lp);
        } catch (Throwable t) { Logger.log("lock ui: " + t); }
        setStatus("locked");
    }

    /** any key wakes: first press only restores, its UP is swallowed */
    private void capWake() {
        capDim = false; capDarkSince = 0;
        overlay.setVisibility(View.INVISIBLE);
        hud.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        if (fbox != null) fbox.setVisibility(View.VISIBLE);
        try {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = capPrevBrightness < 0 ? -1f : capPrevBrightness;
            getWindow().setAttributes(lp);
        } catch (Throwable t) { Logger.log("wake brightness: " + t); }
        try {
            rig.camera().setPreviewDisplay(surface.getHolder());
            rig.startPreview();
        } catch (Throwable t) { Logger.log("wake preview: " + t); }
        if (fbox != null) fbox.postInvalidate();
        setStatus("wake");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (fbox != null) fbox.verUntil = System.currentTimeMillis() + 1000;   // version hint on every entry
        if (surface.getHolder().getSurface() != null) startCamera(surface.getHolder());
        new Thread(new Runnable() { public void run() {           // suspend/resume can revive the process with a dead net:
            try { Thread.sleep(1500); } catch (InterruptedException e) { return; }   // let onCreate's bootWifi settle on cold start
            if (!netReady) return;                                 // boot flow still connecting — leave it alone
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService("wifi");
            if (wifiUsable(wm) && hostPingOk(wm)) return;          // genuinely fine
            Logger.log("resume: net unproven -> forcing reconnect");
            handler.post(new Runnable() { public void run() { if (fbox != null) fbox.netReady = false; } });
            bounceAndReconnect();
        }}, "resumenet").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePrefs();
        clearGhost();
        rig.release();
    }

    // ---------------------------------------------------------------- shoot + grade

    private void shoot() {
        if (!rig.ready()) return;
        captureStart = System.currentTimeMillis();
        // film locked at shutter press; a FIFO keeps per-shot films even if the user
        // changes the film before the watcher picks the file up (needed for H/2 pairs)
        synchronized (shotFilms) { shotFilms.add(resolveSel(sel)); }
        if (isPairMode()) {
            boolean firstOfPair = (pairSlot == PAIR_FRAME1);
            if (stampMode == 9) {
                if (firstOfPair) { setGhostWait(true); Logger.log("double: ghost wait"); }   // dim until the ghost is ready
                else { clearGhost(); Logger.log("double: ghost off (2nd shot taken)"); }
            }
            pairSlot = firstOfPair ? PAIR_FRAME2 : PAIR_FRAME1;
            refreshPair();
        }   // [1]<->[2] immediately on shutter
        rig.shoot();
        setStatus("capturing…");
    }

    private int takeShotFilm() {
        synchronized (shotFilms) {
            return shotFilms.isEmpty() ? resolveSel(sel) : shotFilms.removeFirst();
        }
    }

    private boolean isPairMode() { return stampMode == 8 || stampMode == 9; }
    private String pairModeName() { return stampMode == 8 ? "half" : "double"; }

    // ---- double-exposure ghost (see the field comment) ----

    /** decode the first exposure's still (scaled to cover the view) and show it as a translucent ghost */
    private void loadGhost(final java.io.File f) {
        try {
            int vw = (fbox != null && fbox.getWidth() > 0) ? fbox.getWidth() : 640;
            int vh = (fbox != null && fbox.getHeight() > 0) ? fbox.getHeight() : 480;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= vw && o.outHeight / (s * 2) >= vh) s *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = s;
            o2.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            final android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (src == null) { Logger.log("double: ghost decode null"); clearGhost(); return; }
            // alpha = luminance, so the window's source-over over the live viewfinder becomes screen(dst, L):
            //   out = rgb*(L/255) + dst*(1-L/255); scaling rgb by 255/L keeps the premultiplied source = the
            //   ghost's real RGB (exact for gray pixels), i.e. a colour-preserving screen.
            int gw = src.getWidth(), gh = src.getHeight();
            int[] px = new int[gw * gh];
            src.getPixels(px, 0, gw, 0, 0, gw, gh);
            src.recycle();
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                int lum = (r * 77 + g * 151 + b * 28) >> 8;
                if (lum < 1) { px[i] = 0; continue; }                 // black -> fully transparent
                int rr = Math.min(255, (r * 255 + lum / 2) / lum);
                int gg = Math.min(255, (g * 255 + lum / 2) / lum);
                int bb = Math.min(255, (b * 255 + lum / 2) / lum);
                px[i] = (lum << 24) | (rr << 16) | (gg << 8) | bb;    // alpha = luminance
            }
            final android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(gw, gh, android.graphics.Bitmap.Config.ARGB_8888);   // decoded bitmaps are immutable
            bm.setHasAlpha(true);
            bm.setPixels(px, 0, gw, 0, 0, gw, gh);
            ghostBmp = bm; ghostOn = true; ghostWait = false;
            handler.removeCallbacks(ghostWaitTimeout);
            handler.post(new Runnable() { public void run() {
                if (fbox != null) { fbox.ghostBmp = bm; fbox.ghostOn = true; fbox.ghostWait = false; fbox.ghostAlpha = ghostAlpha; fbox.postInvalidate(); }
            }});
            Logger.log("double: ghost loaded " + gw + "x" + gh + " (ss=" + s + ") from " + f.getName());
        } catch (Throwable t) { Logger.log("double: loadGhost " + t); clearGhost(); }
    }

    private void clearGhost() {
        ghostOn = false; ghostWait = false;
        handler.removeCallbacks(ghostWaitTimeout);
        if (fbox != null) { fbox.ghostOn = false; fbox.ghostBmp = null; fbox.ghostWait = false; fbox.postInvalidate(); }
    }

    /** after the 1st exposure: dim the viewfinder until the ghost is decoded and shown */
    private void setGhostWait(boolean wait) {
        ghostWait = wait;
        if (wait) ghostOn = false;
        if (fbox != null) {
            fbox.ghostWait = wait;
            if (wait) { fbox.ghostOn = false; fbox.ghostBmp = null; }
            fbox.postInvalidate();
        }
        handler.removeCallbacks(ghostWaitTimeout);
        if (wait) handler.postDelayed(ghostWaitTimeout, 12000);   // never stay dimmed forever
    }

    private final Runnable ghostWaitTimeout = new Runnable() { public void run() {
        if (ghostWait) { ghostWait = false; if (fbox != null) { fbox.ghostWait = false; fbox.postInvalidate(); } Logger.log("double: ghost wait timeout"); }
    }};

    /** UI-thread refresh of the blinking pair indicator (watcher runs off-thread) */
    private void refreshPair() {
        handler.post(new Runnable() { public void run() {
            if (fbox != null) { fbox.pairDisp = isPairMode() ? pairSlot : -1; fbox.postInvalidate(); }
        }});
    }

    /** C2 changed the stamp mode: (re)arm or disarm the H/2 pair capture */
    private void onStampModeChanged() {
        pair0File = null; pair0Film = -1; pairStaged = 0;
        synchronized (shotFilms) { shotFilms.clear(); }
        if (isPairMode()) {
            pairSlot = PAIR_FRAME1;
            pairBlinkOn = true;
            handler.removeCallbacks(pairBlinkTick);
            handler.post(pairBlinkTick);
        } else {
            pairSlot = PAIR_OFF;
            handler.removeCallbacks(pairBlinkTick);
            pairBlinkOn = true;
        }
        refreshPair();
        clearGhost();
    }

    /** blink phase: flips the [ 1 ]/[ 2 ] indicator while a pair mode is active */
    private final Runnable pairBlinkTick = new Runnable() { public void run() {
        if (!isPairMode()) { pairBlinkOn = true; refreshPair(); return; }
        pairBlinkOn = !pairBlinkOn;
        if (fbox != null) { fbox.pairBlink = pairBlinkOn; fbox.postInvalidate(); }
        handler.postDelayed(this, 380);
    }};

    /** S2 release: cancel immediately (PMCADemo pattern), restart preview after a beat */
    private void shutterUp() {
        if (names.isEmpty()) {   // no film list from the host: no graded result is possible — refuse to shoot
            Logger.log("shutter blocked: empty film list");
            setStatus("NO FILM LIST");
            showFilmWarn();
            return;
        }
        Logger.log("shutterUp");
        rig.cancelShot();
        handler.postDelayed(new Runnable() {
            public void run() {
                rig.startPreview();
                triggerWatch();
            }
        }, SHUTTER_RESTART_MS);
    }

    private final java.util.Set<String> sentPaths = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private final java.util.Set<String> failedPaths = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private volatile long cutoffTime = 0;      // ignore anything not newer than the last photo before launch
    private volatile boolean watching = false;
    private volatile long watchUntil = 0;
    private volatile String pendPath = null;
    private volatile long pendSize = -1;

    /** Only photos newer than the newest photo that existed at launch are uploaded:
     *  i.e. the first photo you shoot after launch, and everything after it. */
    private void initWatchBaseline() {
        File ln = newestPhoto();
        cutoffTime = (ln != null) ? ln.lastModified() : 0;
        Logger.log("watcher: cutoff " + cutoffTime + (ln != null ? " (" + ln.getName() + ")" : " (no prior photo)"));
        if (ln == null) {                                  // SD may not have been ready yet
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                    File l2 = newestPhoto();
                    if (l2 != null) { cutoffTime = l2.lastModified(); Logger.log("watcher: cutoff(late) " + cutoffTime); }
                }
            }, "cutoff").start();
        }
    }

    /** Called after each shot. Scans briefly (only while a shot is being processed),
     *  uploading new photos one at a time; stays idle the rest of the time so it never
     *  competes with focus/shutter. */
    private void triggerWatch() {
        if (!failedPaths.isEmpty()) {           // a new shot re-arms failed uploads for retry
            sentPaths.removeAll(failedPaths);
            failedPaths.clear();
            Logger.log("watcher: re-armed failed uploads");
        }
        watchUntil = System.currentTimeMillis() + 8000;
        if (watching) return;
        watching = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    while (System.currentTimeMillis() < watchUntil) {
                        try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                        if (grading) { watchUntil = System.currentTimeMillis() + 8000; continue; }   // keep window open while busy
                        synchronized (queue) { if (!queue.isEmpty()) { watchUntil = System.currentTimeMillis() + 8000; continue; } }
                        File f = oldestUnsentPhoto();
                        if (f == null) { pendPath = null; pendSize = -1; continue; }
                        long size = f.length();
                        String p = f.getAbsolutePath();
                        if (size > 0 && p.equals(pendPath) && size == pendSize) {
                            sentPaths.add(p);
                            int film = takeShotFilm();
                            if (isPairMode()) {
                                if (pairStaged == 0) {                      // stage frame 1, wait for frame 2
                                    pair0File = f; pair0Film = film;
                                    pairStaged = 1;
                                    Logger.log("watcher: pair frame1 " + f.getName() + " film#" + film);
                                    if (stampMode == 9 && pairSlot == PAIR_FRAME2) loadGhost(f);   // double: show 1st exposure as a ghost
                                } else {                                    // frame 2 -> atomic batch
                                    File f0 = pair0File; int film0 = pair0Film;
                                    pair0File = null; pair0Film = -1;
                                    pairStaged = 0;
                                    Logger.log("watcher: pair frame2 " + f.getName() + " -> batch");
                                    enqueue(new Job(f0, film0, f, film, true));
                                }
                            } else {
                                Logger.log("watcher: enqueue " + f.getName() + " " + size + "B");
                                enqueue(new Job(f, film));
                            }
                            pendPath = null; pendSize = -1;
                            watchUntil = System.currentTimeMillis() + 8000;   // keep alive for the burst
                        } else {
                            pendPath = p; pendSize = size;               // wait for write to settle
                        }
                    }
                } catch (Throwable t) { Logger.log("watcher EX " + t); }
                finally { watching = false; }
            }
        }, "media").start();
    }

    /** oldest jpg under DCIM newer than the last pre-launch photo and not yet enqueued */
    private File oldestUnsentPhoto() {
        File best = null;
        File[] dirs = new File(SD, "DCIM").listFiles();
        if (dirs == null) return null;
        for (File d : dirs) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (!n.endsWith(".jpg")) continue;
                String p = f.getAbsolutePath();
                if (sentPaths.contains(p)) continue;
                if (f.lastModified() <= cutoffTime) continue;        // older than / same as pre-launch newest
                if (best == null || f.lastModified() < best.lastModified()) best = f;
            }
        }
        return best;
    }

    private static File newestPhoto() {
        File best = null;
        File[] dirs = new File(SD, "DCIM").listFiles();
        if (dirs == null) return null;
        for (File d : dirs) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (!n.endsWith(".jpg")) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
        }
        return best;
    }

    /** nudge the camera album index after replacing a DCIM file: update size/date row, then media-scan */
    private void refreshAvindex(File f) {
        try {
            try {
                Object[] av = avindex();
                if (av != null) {
                    android.net.Uri uri = (android.net.Uri) av[0];
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put("_size", f.length());
                    cv.put("datetaken", f.lastModified());
                    int n = getContentResolver().update(uri, cv,
                            (String) av[1] + " LIKE ?", new String[]{ "%" + f.getName() });
                    Logger.log("avindex update rows=" + n);
                }
            } catch (Throwable t2) { Logger.log("avindex update unsupported: " + t2.getClass().getSimpleName()); }
            android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    android.net.Uri.fromFile(f));
            sendBroadcast(it);
            Logger.log("media scan sent " + f.getName());
        } catch (Throwable t) { Logger.log("avindex refresh EX " + t); }
    }

    /** Sony avindex media provider: the camera's own image index — the safe way to
     * find new photos (File.listFiles on DCIM while the firmware writes kills the
     * camera's storage stack — build #11 black-screen crash) */
    private Object avindexCache;   // Object[]{Uri, dataCol, dateCol}

    private Object[] avindex() {
        if (avindexCache instanceof Object[]) return (Object[]) avindexCache;
        try {
            Class<?> m = Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
            android.net.Uri uri = (android.net.Uri) m.getField("EXTERNAL_CONTENT_URI").get(null);
            String data = (String) m.getField("DATA").get(null);
            String date = (String) m.getField("CONTENT_CREATED_UTC_DATE_TIME").get(null);
            avindexCache = new Object[]{uri, data, date};
            return (Object[]) avindexCache;
        } catch (Throwable t) {
            Logger.log("avindex init: " + t);
            return null;
        }
    }

    /** newest real photo via the provider: walk the first rows, skip internal
     * entries (000004A5-... style), return the first *.JPG whose mtime is fresh */
    private String newestImageViaProvider(long minTime) {
        Object[] av = avindex();
        if (av == null) return null;
        try {
            android.database.Cursor c = getContentResolver().query(
                    (android.net.Uri) av[0], new String[]{(String) av[1]},
                    null, null, (String) av[2] + " DESC");
            if (c == null) return null;
            String path = null;
            if (c.moveToFirst()) {
                for (int i = 0; i < 12; i++) {
                    String p = c.getString(0);
                    if (p != null && p.toLowerCase().endsWith(".jpg")
                            && new File(p).lastModified() >= minTime) {
                        path = p;
                        break;
                    }
                    if (!c.moveToNext()) break;
                }
            }
            c.close();
            return path;
        } catch (Throwable t) {
            Logger.log("avindex query: " + t);
            return null;
        }
    }

    private void enqueue(Job j) {
        synchronized (queue) {
            queue.add(j);
            setStatus("queued " + queue.size());
            queue.notifyAll();
        }
    }

    private final Runnable gradeLoop = new Runnable() {
        public void run() {
            while (true) {
                Job j;
                synchronized (queue) {
                    while (queue.isEmpty()) {
                        try { queue.wait(); } catch (InterruptedException e) { return; }
                    }
                    j = queue.removeFirst();
                }
                grade(j);
            }
        }
    };
    private Thread gradeThread;

    private boolean gradeViaPhone(Job j, String fname, long t0) {
        String gname = gnameFor(j.src, j.filmIdx);
        String pack = packOf(j.filmIdx);
        java.net.HttpURLConnection conn = null;
        final java.util.concurrent.atomic.AtomicLong sent = new java.util.concurrent.atomic.AtomicLong();
        try {
            final long flen = j.src.length();
            if (fbox != null) fbox.beginUpload();
            String stampTxt = "", stamp2Txt = "";
            if (stampMode == 2 || stampMode == 3) {
                String exposure = shutterText() + " " + "F" + apertureText() + " ISO" + stampIso(j.src);
                if (stampMode == 2) stampTxt = exposure;       // exposure, bottom-left
                else stamp2Txt = exposure;                     // DE: exposure, bottom-right
            }
            if (stampMode == 1 || stampMode == 3) stampTxt = "@DATE";   // date, bottom-left
            if (stampMode == 6) stampTxt = pack;                        // F: film name, bottom-left
            else if (stampMode == 7) {                                  // FE: film name + exposure
                stampTxt = pack;
                stamp2Txt = shutterText() + " " + "F" + apertureText() + " ISO" + stampIso(j.src);
            }
            String stampQ = stampTxt.length() > 0 ? "&stamp=" + java.net.URLEncoder.encode(stampTxt) : "";
            if (stamp2Txt.length() > 0) stampQ += "&stamp2=" + java.net.URLEncoder.encode(stamp2Txt);
            if (stampMode == 4) stampQ = "&b=1";               // B: selected film + Polaroid frame
            else if (stampMode == 5) stampQ = "&x=1";          // X: no text, 4-film collage
            conn = (java.net.HttpURLConnection) new java.net.URL(
                "http://" + phoneIp() + ":8800/ingest?film=" + java.net.URLEncoder.encode(pack, "UTF-8")
                    + "&name=" + gname + "&orig=" + j.src.getName() + "&len=" + flen + stampQ).openConnection();   // film names may be any language — ALWAYS percent-encode
            conn.setDoOutput(true); conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000); conn.setReadTimeout(30000);
            conn.setFixedLengthStreamingMode((int) flen);
            final java.net.HttpURLConnection fc = conn;
            final java.io.File src = j.src;
            Thread up = new Thread(new Runnable() { public void run() {
                try {
                    java.io.OutputStream os = fc.getOutputStream();
                    java.io.FileInputStream fi2 = new java.io.FileInputStream(src);
                    byte[] buf = new byte[16384];
                    long s = 0; int r;
                    while ((r = fi2.read(buf)) >= 0) {
                        os.write(buf, 0, r); s += r; sent.set(s);
                        if (fbox != null) fbox.setProgress(flen > 0 ? (float) s / (float) flen : 0f);
                    }
                    fi2.close(); os.flush(); os.close();
                } catch (Throwable t) { Logger.log("upload-thread EX " + t); }
            }}, "up");
            up.setDaemon(true); up.start();

            long last = -1, lastChange = System.currentTimeMillis(), start = lastChange;
            while (up.isAlive()) {
                up.join(400);
                long now = System.currentTimeMillis();
                if (sent.get() != last) { last = sent.get(); lastChange = now; }
                if (now - lastChange > 15000 || now - start > 180000) {   // stalled or hard cap -> cancel
                    Logger.log("upload cancelled sent=" + sent.get() + "/" + flen);
                    setStatus("upload interrupted, cancelled");
                    try { conn.disconnect(); } catch (Throwable ig) {}
                    try { up.interrupt(); } catch (Throwable ig) {}
                    if (fbox != null) fbox.endUpload();
                    return false;
                }
            }
            int code = fc.getResponseCode();
            Logger.log("phone r=" + code + " " + pack + " " + gname + " " + sent.get() + "B");
            if (code == 200) { done++; if (fbox != null) fbox.showDone(); return true; }
            if (fbox != null) fbox.endUpload();
            return false;
        } catch (Throwable t) {
            setStatus("upload failed " + t.getMessage());
            Logger.log("phone EX " + t);
            if (fbox != null) fbox.endUpload();
            return false;
        } finally {
            grading = false;
        }
    }

    /** graded filename stem for a source shot: G<filmTag><frame4>.JPG */
    private String gnameFor(File src, int filmIdx) {
        String frame = "";
        String sn = src.getName();
        for (int i = 0; i < sn.length(); i++) { char ch = sn.charAt(i); if (ch >= '0' && ch <= '9') frame += ch; }
        while (frame.length() < 4) frame = "0" + frame;
        if (frame.length() > 4) frame = frame.substring(frame.length() - 4);
        int fi = (filmIdx < 9 ? 10 : 100) + filmIdx;
        return "G" + fi + frame + ".JPG";
    }

    /** H/2 batch upload: both frames + their films + mode in one atomic POST to /ingest2 */
    private boolean gradePairViaPhone(Job j) {
        java.net.HttpURLConnection conn = null;
        final java.util.concurrent.atomic.AtomicLong sent = new java.util.concurrent.atomic.AtomicLong();
        try {
            final long f0len = j.src.length(), f1len = j.src2.length();
            final long total = f0len + f1len;
            final File src0 = j.src, src1 = j.src2;
            String name = gnameFor(src0, j.filmIdx);
            String pack0 = packOf(j.filmIdx), pack1 = packOf(j.filmIdx2);
            if (fbox != null) fbox.beginUpload();
            conn = (java.net.HttpURLConnection) new java.net.URL(
                "http://" + phoneIp() + ":8800/ingest2?mode=" + pairModeName()
                    + "&film0=" + java.net.URLEncoder.encode(pack0, "UTF-8")
                    + "&film1=" + java.net.URLEncoder.encode(pack1, "UTF-8")
                    + "&name=" + name + "&len0=" + f0len + "&len1=" + f1len).openConnection();
            conn.setDoOutput(true); conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000); conn.setReadTimeout(120000);   // two grades + compose
            conn.setFixedLengthStreamingMode((int) total);
            final java.net.HttpURLConnection fc = conn;
            Thread up = new Thread(new Runnable() { public void run() {
                try {
                    java.io.OutputStream os = fc.getOutputStream();
                    long s = 0;
                    for (File src : new File[]{src0, src1}) {
                        java.io.FileInputStream fi = new java.io.FileInputStream(src);
                        byte[] buf = new byte[16384]; int r;
                        while ((r = fi.read(buf)) >= 0) {
                            os.write(buf, 0, r); s += r; sent.set(s);
                            if (fbox != null) fbox.setProgress(total > 0 ? (float) s / (float) total : 0f);
                        }
                        fi.close();
                    }
                    os.flush(); os.close();
                } catch (Throwable t) { Logger.log("pair upload-thread EX " + t); }
            }}, "up2");
            up.setDaemon(true); up.start();
            long last = -1, lastChange = System.currentTimeMillis(), start = lastChange;
            while (up.isAlive()) {
                up.join(400);
                long now = System.currentTimeMillis();
                if (sent.get() != last) { last = sent.get(); lastChange = now; }
                if (now - lastChange > 15000 || now - start > 240000) {
                    Logger.log("pair upload cancelled sent=" + sent.get() + "/" + total);
                    setStatus("upload interrupted, cancelled");
                    try { conn.disconnect(); } catch (Throwable ig) {}
                    try { up.interrupt(); } catch (Throwable ig) {}
                    if (fbox != null) fbox.endUpload();
                    return false;
                }
            }
            int code = fc.getResponseCode();
            Logger.log("phone pair r=" + code + " " + pack0 + "+" + pack1 + " " + name + " " + sent.get() + "B");
            if (code == 200) { done++; if (fbox != null) fbox.showDone(); return true; }
            if (fbox != null) fbox.endUpload();
            return false;
        } catch (Throwable t) {
            setStatus("pair upload failed " + t.getMessage());
            Logger.log("phone pair EX " + t);
            if (fbox != null) fbox.endUpload();
            return false;
        } finally {
            grading = false;
        }
    }

    private void grade(final Job j) {
        grading = true;
        Logger.log("grade: begin " + j.src.getName() + " film#" + j.filmIdx);
        final String fname = names.size() > j.filmIdx && j.filmIdx < names.size()
                ? names.get(j.filmIdx) : ("#" + j.filmIdx);
        setStatus("processing " + fname);
        long t0 = System.currentTimeMillis();
        boolean ok = j.pair ? gradePairViaPhone(j) : gradeViaPhone(j, fname, t0);
        if (ok) bounceCount = 0;
        else {
            failedPaths.add(j.src.getAbsolutePath());
            if (j.src2 != null) failedPaths.add(j.src2.getAbsolutePath());
            if (j.pair) handler.post(new Runnable() { public void run() {   // re-arm the pair for retry
                pairStaged = 0; pair0File = null; pair0Film = -1; refreshPair();
            }});
            Logger.log("grade: mark failed for retry " + j.src.getName());
            forceWifiReconnect();                      // any upload failure -> bounce wifi and reconnect
        }
        if (true) { return; }
        try {
            long h = FilmLib.nativeLoad(films.get(j.filmIdx).getAbsolutePath(), TEX.getAbsolutePath());
            if (h == 0) { setStatus("film load failed"); grading = false; return; }
            String frame = "";
            String sn = j.src.getName();
            for (int i = 0; i < sn.length(); i++) {
                char ch = sn.charAt(i);
                if (ch >= '0' && ch <= '9') frame += ch;
            }
            while (frame.length() < 4) frame = "0" + frame;
            if (frame.length() > 4) frame = frame.substring(frame.length() - 4);
            int fi = (j.filmIdx < 9 ? 10 : 100) + j.filmIdx;
            File temp = new File(GRADED, "G" + fi + frame + ".JPG");
            int r = 2;
            for (int attempt = 0; attempt < 3 && r == 2; attempt++) {
                if (attempt > 0) {
                    Logger.log("grade: retry after format error");
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                }
                r = FilmLib.nativeProcess(h, j.src.getAbsolutePath(), temp.getAbsolutePath(), SCALES[qualityIdx], 92);
            }
            FilmLib.nativeFree(h);
            long ms = System.currentTimeMillis() - t0;
            if (r == 0) {
                done++;
                setStatus("OK " + temp.getName() + "  " + (ms / 1000) + "." + (ms % 1000 / 100) + "s  total " + done
                        + "  PLAY to view");
            } else {
                setStatus("process failed r=" + r);
            }
            Logger.log("graded r=" + r + " " + ms + "ms " + j.src.getName() + " -> " + temp.getName());
        } catch (Throwable t) {
            Logger.log("grade: " + t);
            setStatus("process error: " + t);
        }
        grading = false;
    }

    /** graded takes the original's DCIM slot; the original is archived to
     * OpenFilm6K/ORIG. Falls back to leaving the graded file in GRADED. */
    private String place(File graded, File original) {
        try {
            File originals = new File(GRADED.getParentFile(), "ORIG");   // 8.3-safe: this FUSE rejects 9-char dir names
            originals.mkdirs();
            File backup = new File(originals, original.getName());
            if (backup.exists()) backup = new File(originals,
                    original.getName().substring(0, original.getName().lastIndexOf('.'))
                            + "_" + System.currentTimeMillis() + ".JPG");
            if (!move(original, backup)) {
                Logger.log("place: archive failed, graded stays in GRADED");
                return graded.getName();
            }
            if (!move(graded, original)) {
                Logger.log("place: slot write failed — restoring original");
                move(backup, original);
                return graded.getName();
            }
            Logger.log("place: " + original.getName() + " graded, original archived");
            return original.getName();
        } catch (Throwable t) {
            Logger.log("place: " + t);
            return graded.getName();
        }
    }

    /** copy + verify + delete. The FUSE renameTo lies on cross-directory moves
     * (returns true but only renames in place), so we never trust it. */
    private static boolean move(File from, File to) {
        try {
            if (to.exists() && !to.delete()) return false;
            java.io.InputStream in = new FileInputStream(from);
            java.io.OutputStream outV = new java.io.FileOutputStream(to);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) outV.write(buf, 0, n);
            outV.close();
            in.close();
            if (to.length() != from.length()) {
                Logger.log("move verify failed: " + from.length() + " vs " + to.length());
                to.delete();
                return false;
            }
            return from.delete();
        } catch (Throwable t) {
            Logger.log("move " + from.getName() + ": " + t);
            return false;
        }
    }

    // ---------------------------------------------------------------- ui

    private final Runnable readoutTick = new Runnable() {
        public void run() {
            if (overlayState == OV_FULL && browser < 0 && settings < 0) renderHud();
            handler.postDelayed(this, 700);
        }
    };

    private void renderHud() {
        if (hlIdx != 0 && !adjOk(hlIdx)) { hlIdx = adjOk(lastParam) ? lastParam : (adjOk(2) ? 2 : 1); }
        if (fbox != null) { fbox.spotX = spotX; fbox.spotY = spotY; fbox.spotOn = spotMode; fbox.afMode = rig.focusMode(); fbox.afName = rig.focusModeName(); fbox.afDisp = rig.focusModeDisplay(); }
        if (fbox != null) { fbox.filmFav = selFav(sel); fbox.c1 = c1Held; }
        if (fbox != null) fbox.stampMark = (stampMode == 1 ? "D" : stampMode == 2 ? "E" : stampMode == 3 ? "DE" : stampMode == 4 ? "B" : stampMode == 5 ? "X" : stampMode == 6 ? "F" : stampMode == 7 ? "FE" : stampMode == 8 ? "H" : stampMode == 9 ? "2" : "");
        if (fbox != null) { fbox.pairDisp = isPairMode() ? pairSlot : -1; fbox.pairBlink = pairBlinkOn; fbox.halfMask = (stampMode == 8); fbox.ghostFinder = ghostFinder; }
        String fn2 = selName(sel);
        int di = fn2.toUpperCase().indexOf(".FLM");
        if (di > 0) fn2 = fn2.substring(0, di);
        fbox.setFilm(fn2);
        fbox.hl = spotMode ? -1 : hlIdx;   // AEL spot-adjust mode takes over the highlight; bottom rows go plain white
        String ml = (sceneIdx == 0 ? "P" : sceneIdx == 1 ? "A" : sceneIdx == 2 ? "S" : "M");
        fbox.setParams(ml + "|" + shutterText() + "|" + "F" + apertureText() + "|" + (ev >= 0 ? "+" : "-") + Math.abs(ev) + "|" + "ISO" + isoText());
        fbox.postInvalidate();
        hud.setVisibility(View.INVISIBLE);
    }

    private String currentFilmLine() {
        return (sel + 1) + "/" + totalSel() + " " + selName(sel);
    }

    private String shutterText() {
        Object p = rig.shutterSpeed();
        try {
            if (p != null) {
                Object n = p.getClass().getField("first").get(p);
                Object d = p.getClass().getField("second").get(p);
                int ni = ((Number) n).intValue(), di = ((Number) d).intValue();
                if (di == 1) return ni + "s";
                if (ni == 1) return "1/" + di;
                return ni + "/" + di;
            }
        } catch (Throwable t) {}
        return "--";
    }

    private String apertureText() {
        int a = rig.aperture();
        if (a <= 0) return "--";
        int whole = a / 100, frac = a % 100;
        return frac == 0 ? String.valueOf(whole) : whole + "." + (frac / 10);
    }

    private String isoText() {
        if (isoIdx < isos.size()) {
            int v = isos.get(isoIdx);
            return v == 0 ? "AUTO" : String.valueOf(v);
        }
        return "--";
    }

    /** ISO to print on the stamp: when metering is AUTO, use the ISO actually
     *  recorded in the captured JPEG's EXIF instead of the word "AUTO". */
    private String stampIso(File src) {
        boolean auto = isoIdx < isos.size() && isos.get(isoIdx) == 0;
        if (!auto && isoIdx < isos.size()) return String.valueOf(isos.get(isoIdx));
        int ex = exifIso(src);
        return ex > 0 ? String.valueOf(ex) : isoText();
    }

    /** read ISOSpeedRatings (0x8827) / ISOSpeed (0x8833) from the JPEG EXIF, or -1 */
    private int exifIso(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] b = new byte[131072];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            in.close();
            if (n < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) return -1;
            int i = 2;
            while (i + 4 <= n) {
                if ((b[i] & 0xFF) != 0xFF) break;
                int mk = b[i + 1] & 0xFF;
                if (mk == 0xD8 || mk == 0x01 || (mk >= 0xD0 && mk <= 0xD7)) { i += 2; continue; }
                if (mk == 0xDA) break;                                  // start of scan
                int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                if (mk == 0xE1 && i + 10 <= n
                        && (b[i+4]&0xFF)=='E' && (b[i+5]&0xFF)=='x' && (b[i+6]&0xFF)=='i'
                        && (b[i+7]&0xFF)=='f' && b[i+8] == 0 && b[i+9] == 0) {
                    int iso = parseExifIso(b, i + 10);
                    if (iso > 0) return iso;
                }
                i += 2 + len;
            }
        } catch (Throwable t) { Logger.log("exifIso: " + t); }
        return -1;
    }

    private int parseExifIso(byte[] b, int t) {
        if (t + 8 > b.length) return -1;
        boolean le = b[t] == 'I';
        if (!((b[t]=='I'&&b[t+1]=='I') || (b[t]=='M'&&b[t+1]=='M'))) return -1;
        if (rd16(b, t + 2, le) != 42) return -1;
        int ifd0 = t + (int) rd32(b, t + 4, le);
        int exifIfd = 0;
        int cnt = rd16(b, ifd0, le);
        for (int k = 0; k < cnt; k++) {
            int e = ifd0 + 2 + k * 12;
            if (e + 12 > b.length) break;
            if (rd16(b, e, le) == 0x8769) exifIfd = t + (int) rd32(b, e + 8, le);
        }
        if (exifIfd == 0) return -1;
        int cnt2 = rd16(b, exifIfd, le);
        for (int k = 0; k < cnt2; k++) {
            int e = exifIfd + 2 + k * 12;
            if (e + 12 > b.length) break;
            int tag = rd16(b, e, le), type = rd16(b, e + 2, le);
            long count = rd32(b, e + 4, le);
            if (tag == 0x8827 || tag == 0x8833) {
                int sz = (type == 3 ? 2 : 4);                        // SHORT=3, LONG=4
                long v = (count * sz <= 4) ? rdVal(b, e + 8, sz, le)
                                           : rdVal(b, t + (int) rd32(b, e + 8, le), sz, le);
                if (v > 0) return (int) v;
            }
        }
        return -1;
    }

    private static int rd16(byte[] b, int o, boolean le) {
        if (o + 2 > b.length) return 0;
        int a = b[o] & 0xFF, c = b[o + 1] & 0xFF;
        return le ? (a | (c << 8)) : ((a << 8) | c);
    }

    private static long rd32(byte[] b, int o, boolean le) {
        if (o + 4 > b.length) return 0;
        long a = b[o] & 0xFF, c = b[o + 1] & 0xFF, d = b[o + 2] & 0xFF, e = b[o + 3] & 0xFF;
        return le ? (a | (c << 8) | (d << 16) | (e << 24)) : ((a << 24) | (c << 16) | (d << 8) | e);
    }

    private static long rdVal(byte[] b, int o, int sz, boolean le) {
        return sz == 2 ? rd16(b, o, le) : rd32(b, o, le);
    }

    private String evText() {
        return String.valueOf(ev);
    }

    private void setStatus(final String s) {
        Logger.log("status: " + s);
    }

    /** on launch: turn WiFi on and wait for a connection before the UI becomes usable */
    private volatile long lastWifiReconnect = 0;
    private volatile int bounceCount = 0;

    /** any upload failure bounces the wifi radio and reruns the connect flow (escalating backoff) */
    private void forceWifiReconnect() {
        long now = System.currentTimeMillis();
        long wait = 8000L << Math.min(bounceCount, 6);      // 8s -> 16s -> ... -> ~8.5min cap
        if (now - lastWifiReconnect < wait) return;
        bounceCount++;
        lastWifiReconnect = now;
        Logger.log("wifi: forcing reconnect after upload failure");
        bounceAndReconnect();
    }

    /** hard radio bounce + full connect flow (no debounce — used by failure path and resume check) */
    private void bounceAndReconnect() {
        new Thread(new Runnable() { public void run() {
            try {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService("wifi");
                wm.setWifiEnabled(false);
                Thread.sleep(1000);
                wm.setWifiEnabled(true);
            } catch (Throwable t) { Logger.log("wifi bounce EX " + t); }
            bootWifi();                                // poll loop waits for real IP + host probe
        }}, "wifireconnect").start();
    }

    private void bootWifi() {
        netReady = false;                          // entering a connect flow invalidates previous state (stale netReady froze the poll)
        bootStart = System.currentTimeMillis();
        if (fbox != null) fbox.netReady = false;
        final android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getSystemService("wifi");
        try {
            if (wm != null && wifiUsable(wm) && hostPingOk(wm)) { netReady = true; if (fbox != null) fbox.netReady = true; Logger.log("wifi: already up (ping ok)"); pullFilmList(); return; }
        } catch (Throwable t) { Logger.log("wifi check: " + t); }
        try {                                            // unproven/stale state -> hard radio reset forces a genuine connect
            if (wm != null && wm.isWifiEnabled()) {
                Logger.log("wifi: stale/unproven state, bouncing radio");
                wm.setWifiEnabled(false);
                Thread.sleep(1000);
            }
        } catch (Throwable t) { Logger.log("wifi bounce EX " + t); }
        Logger.log("wifi: enabling");
        try { wm.setWifiEnabled(true); } catch (Throwable t) { Logger.log("wifi enable EX " + t); }
        handler.post(new Runnable() { public void run() {
            if (netReady) return;
            int pct = (int) ((System.currentTimeMillis() - bootStart) / 150);
            if (pct > 90) pct = 90;
            try {
                int st = wm.getWifiState();
                if (st == android.net.wifi.WifiManager.WIFI_STATE_ENABLING) pct = Math.max(pct, 40);
                else if (st == android.net.wifi.WifiManager.WIFI_STATE_ENABLED) pct = Math.max(pct, 70);
            } catch (Throwable t) {}
            boolean up = false;
            try { up = wifiUsable(wm); } catch (Throwable t) {}
            if (up) up = hostReachable(wm);          // real end-to-end check: kills stale-IP false positives
            if (up) pct = 100;
            else if (pct < 85) pct = 85;             // associated but host not answering yet
            if (fbox != null) { fbox.wifiPct = pct; fbox.postInvalidate(); }
            if (up) {
                netReady = true;
                if (fbox != null) fbox.netReady = true;
                Logger.log("wifi: connected + host reachable");
                pullFilmList();
                triggerWatch();                        // retry anything that failed while offline
                if (pendingHolder != null) startCamera(pendingHolder);
                renderOverlay();
                if (fbox != null) fbox.postInvalidate();
                return;
            }
            if (System.currentTimeMillis() - bootStart > 15000) {   // degraded entry, uploads retry later
                netReady = true;
                if (fbox != null) fbox.netReady = true;
                Logger.log("wifi: 15s timeout, entering degraded (no host)");
                if (pendingHolder != null) startCamera(pendingHolder);
                return;
            }
            handler.postDelayed(this, 120);
        }});
    }

    private boolean wifiUsable(android.net.wifi.WifiManager wm) {
        try {
            if (wm == null || !wm.isWifiEnabled()) return false;
            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
            return wi != null && wi.getIpAddress() != 0
                && wi.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED;
        } catch (Throwable t) { return false; }
    }

    /** proven-live check: real HTTP /ping round trip against the host */
    private boolean hostPingOk(android.net.wifi.WifiManager wm) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL("http://" + phoneIp() + ":8800/ping").openConnection();
            c.setConnectTimeout(800); c.setReadTimeout(800);
            java.io.InputStream in = c.getInputStream();
            byte[] b = new byte[32];
            int n = in.read(b); in.close();
            c.disconnect();
            return n > 0 && new String(b, 0, n).contains("pong");
        } catch (Throwable t) { return false; }
    }

    /** can we actually reach the OpenFilm6K host (gateway:8800) right now? */
    private boolean hostReachable(android.net.wifi.WifiManager wm) {
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByName(phoneIp()), 8800), 600);
            s.close();
            return true;
        } catch (Throwable t) { return false; }
    }

    private void renderOverlay() {
        if (browser >= 0) {
            StringBuilder sb = new StringBuilder("Film (center=load / Fn=close)\n\n");
            java.util.List<String> dn = displayNames();
            for (int i = 0; i < dn.size(); i++) {
                if (i == browser) sb.append("> ");
                else if (i == sel) sb.append("* ");
                else sb.append("  ");
                sb.append(dn.get(i)).append('\n');
            }
            if (fbox != null) fbox.setMenu(dn, browser, favArr());
            overlay.setVisibility(View.INVISIBLE);
        } else if (settings >= 0) {
            StringBuilder sb = new StringBuilder("Settings (center=run)\n\n");
            for (int i = 0; i < SETTINGS_ROWS.length; i++) {
                sb.append(i == settings ? "> " : "  ").append(SETTINGS_ROWS[i]);
                if (i == 0) sb.append("  ").append(SCALE_NAMES[qualityIdx]);
                sb.append('\n');
            }
            overlay.setText(sb);
            overlay.setVisibility(View.VISIBLE);
        } else {
            overlay.setVisibility(View.INVISIBLE);
            if (fbox != null) fbox.clearMenu();
        }
        if (overlayState != OV_NONE) renderHud();
        else hud.setVisibility(View.INVISIBLE);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (capDim) {                 // screen locked (manual): first press wakes — FIRST so nothing can trap the keys
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) capWake();
            return true;
        }
        if (!netReady) return true;   // boot screen: nothing operable until WiFi is up
        int scan = e.getScanCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            Logger.log("key " + scan);
        }
        if (e.getAction() == KeyEvent.ACTION_UP || e.getAction() == KeyEvent.ACTION_MULTIPLE) {
                if (e.getAction() == KeyEvent.ACTION_UP && scan == K_S2 && browser < 0 && settings < 0) shutterUp();
            if (e.getAction() == KeyEvent.ACTION_UP && scan == K_C1) { c1Held = false; renderHud(); }
        if (e.getAction() == KeyEvent.ACTION_UP && scan == K_C2) {
            c2Held = false;
            handler.removeCallbacks(c2LongAction);
            if (!c2Fired) { stampMode = (stampMode + 1) % 10; onStampModeChanged(); savePrefs(); renderHud(); }   // short press cycles stamp modes incl. H/2
        }
            if (e.getAction() == KeyEvent.ACTION_UP && scan == K_AEL) {
                aelHeld = false;
                handler.removeCallbacks(aelLongAction);
                if (capDarkSince != 0 && !capDim) {   // manual lock countdown in progress: release cancels
                    capDarkSince = 0;
                    handler.removeCallbacks(capManualTick);
                    capBoxView.setVisibility(View.GONE);
                    if (fbox != null && fbox.hideAf) { fbox.hideAf = false; fbox.postInvalidate(); }
                }
                if (!aelFired) {
                    spotMode = !spotMode;
                    if (spotMode) {                                     // entering adjust: refresh from system
                        try {
                            int[] spt2 = CameraRig.readSystemSpot(rig.camera().getParameters());
                            if (spt2 != null) { rig.adoptSpot(spt2[0], spt2[1]); spotX = spt2[0]; spotY = spt2[1]; }
                        } catch (Throwable ig) {}
                    }
                    if (fbox != null) { fbox.spotOn = spotMode; fbox.spotX = spotX; fbox.spotY = spotY; }
                    renderHud();
                }
                aelFired = false;
            }
            return true;   // consume ALL keys, never let firmware/system handle PLAY etc.
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && scan == K_AEL && e.getRepeatCount() == 0) {
            aelHeld = true; aelFired = false;
            handler.postDelayed(aelLongAction, 600);
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
        int dir = (scan == K_WHEEL_CW || scan == K_DIAL_CW) ? 1
                : (scan == K_WHEEL_CCW || scan == K_DIAL_CCW) ? -1 : 0;
        if (viewing >= 0) return viewerKey(scan, dir);
        if (browser >= 0) return browserKey(scan, dir);
        if (settings >= 0) return settingsKey(scan, dir);
        if (scan == K_WHEEL_CW || scan == K_WHEEL_CCW || scan == 522 || scan == 523 || scan == 528 || scan == 529) {
            int wd = (scan == K_WHEEL_CCW || scan == 529) ? -1 : 1;
            ev = Math.max(evMin, Math.min(evMax, ev + wd));
            rig.setEv(ev); renderHud(); return true;         // wheel always = EV, no highlight change
        }
        if (scan == K_DIAL_CW || scan == K_DIAL_CCW) {
            int dd = (scan == K_DIAL_CW) ? 1 : -1;
            if (spotMode && !c1Held) {                        // focus state: dial alone cycles AF area mode,
                String m = (dd > 0) ? rig.cycleFocusModeBack() : rig.cycleFocusMode();   // C1+dial still changes film
                setStatus("AF " + m); renderHud(); return true;
            }
            return dialItem(dd);
        }


        if (scan == K_S1) { if (fbox != null) fbox.set(1); rig.focus(); return true; }
        if (scan == K_S2) { shoot(); return true; }
        if (scan == K_FN) { browser = sel; renderOverlay(); return true; }
        if (scan == K_C1) {                                        // in focus-adjust: exit it and jump to film row
            c1Held = true;
            if (spotMode) {
                spotMode = false;
                if (fbox != null) fbox.spotOn = false;
            }
            hlIdx = 0;
            renderHud(); return true;
        }
        if (scan == K_C2) {   // long-press = screenshot; short-press handled on UP
            c2Held = true; c2Fired = false;
            handler.removeCallbacks(c2LongAction);
            handler.postDelayed(c2LongAction, 600);
            return true;
        }
        if (scan == K_ENTER) {
            if (spotMode) {                            // center key in focus-adjust: switch the ghost ratio (LCD / EVF)
                ghostFinder = (ghostFinder + 1) % 2;
                if (fbox != null) { fbox.ghostFinder = ghostFinder; fbox.postInvalidate(); }
                savePrefs();
                setStatus("VIEW " + (ghostFinder == 0 ? "LCD" : "EVF"));
                Logger.log("ghost finder " + ghostFinder);
                return true;
            }
            sceneIdx = (sceneIdx + 1) % 4;
            rig.setSceneMode(SCENE_MODES[sceneIdx]);
            renderOverlay(); renderHud();
            setStatus("mode " + SCENE_NAMES[sceneIdx]);
            return true;
        }
        if (spotMode && (scan == K_UP || scan == K_DOWN || scan == K_LEFT || scan == K_RIGHT || scan == K_ENTER)) {
            int d = 120;
            if (scan == K_UP) spotY -= d;
            else if (scan == K_DOWN) spotY += d;
            else if (scan == K_LEFT) spotX -= d;
            else if (scan == K_RIGHT) spotX += d;
            else if (scan == K_ENTER) { spotX = 0; spotY = 0; }
            rig.setSpot(spotX, spotY);
            if (fbox != null) { fbox.spotX = spotX; fbox.spotY = spotY; fbox.postInvalidate(); }
            return true;
        }
        if (scan == K_UP) { hlIdx = 0; renderHud(); return true; }
        if (scan == K_DOWN) { hlIdx = adjOk(lastParam) ? lastParam : 2; renderHud(); return true; }
        if (scan == K_LEFT || scan == K_RIGHT) {
            int d = (scan == K_RIGHT) ? 1 : -1;
            if (hlIdx == 0) { sel = (sel + d + totalSel()) % totalSel(); savePrefs(); }
            else {
                int h = hlIdx;
                for (int i = 0; i < 5; i++) { h += d; if (h < 1) h = 5; if (h > 5) h = 1; if (adjOk(h)) break; }
                hlIdx = h; lastParam = h;
            }
            renderHud(); return true;
        }
        if (scan == K_ENTER) { sceneIdx = (sceneIdx + 1) % 4; rig.setSceneMode(SCENE_MODES[sceneIdx]); renderOverlay(); renderHud(); return true; }
        if (scan == K_MENU) { savePrefs(); finish(); return true; }
        if (scan == K_LEFT || scan == K_RIGHT) {
            dialMode = (dialMode + (scan == K_RIGHT ? 1 : MODES.length - 1)) % MODES.length;
            renderOverlay();
            return true;
        }
        if (dir != 0) {
            int dm = dialMode;
            if (dm == 0) dm = (sceneIdx == 1) ? 2 : (sceneIdx == 2 || sceneIdx == 3) ? 1 : 4;
            return dialAdjust(dm, dir);
        }
        return true;
    }

    private int hlIdx = 0;   // start on film row
    private int lastParam = 2;   // remembered param-row cursor
    private final java.util.HashSet<String> favs = new java.util.HashSet<String>();   // by FILM NAME: survives list reordering
    private boolean c1Held = false;
    private int stampMode = 0;   // 0 off, 1 date(D), 2 exposure(E), 3 DE, 4 B(frame), 5 X(collage), 6 F(film name), 7 FE(film name + exposure)
    private boolean spotMode = false;
    private boolean aelLong = false;
    private boolean aelHeld = false, aelFired = false;
    private final Runnable aelLongAction = new Runnable() { public void run() {
        if (!aelHeld || capDim) return;
        aelFired = true;
        capDarkSince = android.os.SystemClock.elapsedRealtime();   // manual lock: 3s countdown, ANY release cancels
        handler.removeCallbacks(capManualTick);
        handler.postDelayed(capManualTick, 200);
        Logger.log("AEL long: manual lock countdown");
    } };

    private final Runnable capManualTick = new Runnable() { public void run() {
        if (capDim || capDarkSince == 0) return;
        long left = 3 - (android.os.SystemClock.elapsedRealtime() - capDarkSince) / 1000;
        if (left <= 0) {
            capDarkSince = 0;
            capSleep();
        } else {
            capCount.setText(String.valueOf(left));
            capCount.setVisibility(View.VISIBLE);
            capLabel.setVisibility(View.VISIBLE);
            capBoxView.setVisibility(View.VISIBLE);
            if (fbox != null) fbox.hideAf = true;
            handler.postDelayed(this, 200);
        }
    } };
    private boolean c2Held = false, c2Fired = false;
    private final Runnable c2LongAction = new Runnable() { public void run() {
        if (!c2Held) return;
        c2Fired = true;
        ghostFinder = (ghostFinder + 1) % 2;                 // long-press C2: switch the ghost ratio (LCD / EVF)
        if (fbox != null) { fbox.ghostFinder = ghostFinder; fbox.postInvalidate(); }
        savePrefs();
        setStatus("VIEW " + (ghostFinder == 0 ? "LCD" : "EVF"));
        Logger.log("ghost finder " + ghostFinder + " (C2 long)");
    } };

    private int spotX = 0, spotY = 0;

    private boolean adjOk(int idx) {
        switch (idx) {
            case 0: return true;                       // film row
            case 1: return true;                       // mode
            case 2: return sceneIdx == 2 || sceneIdx == 3;   // shutter: S/M only
            case 3: return sceneIdx == 1 || sceneIdx == 3;   // aperture: A/M only
            case 4: return true;                       // EV
            case 5: return true;                       // ISO
        }
        return true;
    }


    private boolean dialItem(int dir) {
        if (!adjOk(hlIdx)) return true;
        switch (hlIdx) {
            case 0: if (c1Held) { jumpFav(dir); } else { sel = (sel + dir + totalSel()) % totalSel(); savePrefs(); } break;
            case 1: sceneIdx = (sceneIdx + dir + 4) % 4; rig.setSceneMode(SCENE_MODES[sceneIdx]); break;
            case 2: rig.adjustShutter(dir); break;
            case 3: rig.adjustAperture(dir); break;
            case 4: ev = Math.max(evMin, Math.min(evMax, ev + dir)); rig.setEv(ev); break;
            case 5: if (!isos.isEmpty()) { isoIdx = Math.max(0, Math.min(isos.size() - 1, isoIdx + dir)); rig.setIso(isos.get(isoIdx)); } break;
        }
        renderOverlay(); renderHud();
        return true;
    }

    private boolean dialAdjust(int dm, int dir) {
        switch (dialMode) {
            case 1: rig.adjustShutter(dir); break;     // shutter
            case 2: rig.adjustAperture(dir); break;    // aperture
            case 3:                                    // ISO
                if (!isos.isEmpty()) {
                    isoIdx = Math.max(0, Math.min(isos.size() - 1, isoIdx + dir));
                    rig.setIso(isos.get(isoIdx));
                }
                break;
            case 4:                                    // EV
                ev = Math.max(evMin, Math.min(evMax, ev + dir));
                rig.setEv(ev);
                break;
            case 5:                                    // film select
                sel = (sel + dir + totalSel()) % totalSel();
                savePrefs();
                break;
        }
        renderOverlay();
        return true;
    }

    private int vZoom = 0;      // 0=1x 1=2x 2=4x
    private float vPanY = 0f;

    static MainActivity self;
    static void focusFeedback(final boolean ok) {
        if (self == null) return;
        self.handler.post(new Runnable() {
            public void run() {
                self.setStatus(ok ? "focus ✓" : "focus ✗");
                if (self.fbox != null) self.fbox.set(ok ? 2 : 3);
                if (self.fbox != null) self.fbox.postDelayed(new Runnable() { public void run() { self.fbox.set(0); } }, 2500);
            }
        });
    }

    private android.graphics.Bitmap curBm;

    private void openViewer() {
        graded.clear();
        File[] fs = GRADED.listFiles();
        if (fs != null) {
            for (File f : fs) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".jpg")) graded.add(f);
            }
            java.util.Collections.sort(graded, new java.util.Comparator<File>() {
                public int compare(File a, File b) { return Long.valueOf(b.lastModified()).compareTo(a.lastModified()); }
            });
        }
        if (graded.isEmpty()) { setStatus("no graded shots yet"); return; }
        viewing = 0;
        showGraded();
    }

    private void showGraded() {
        File f = graded.get(viewing);
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
        o.inSampleSize = 8;                     // 750px ARGB_8888 = 2.2MB, fits 2.3 VM budget
        android.graphics.Bitmap bm;
        try {
            bm = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) { bm = null; }
        if (curBm != null) { try { curBm.recycle(); } catch (Throwable ig) {} curBm = null; }
        viewer.setImageBitmap(null);
        if (bm == null) { setStatus("decode failed (memory) " + f.getName()); return; }
        curBm = bm;
        viewer.setImageBitmap(bm);
        applyZoom();
        viewer.setVisibility(View.VISIBLE);
        renderViewerHud(f);
    }

    private void renderViewerHud(File f) {
        StringBuilder sb = new StringBuilder();
        sb.append("OpenFilm6K viewer  #").append(BUILD).append("\n")
          .append(f.getName()).append("  (").append(viewing + 1).append("/").append(graded.size()).append(")\n\n")
          .append("wheel=page  center=zoom  PLAY/MENU=back");
        overlay.setText(sb);
        overlay.setVisibility(View.VISIBLE);
        hud.setVisibility(View.INVISIBLE);
        status.setVisibility(View.INVISIBLE);
    }

    private void applyZoom() {
        float z = vZoom == 0 ? 1f : vZoom == 1 ? 2f : 4f;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postScale(z, z);
        m.postTranslate(0, vPanY);
        viewer.setImageMatrix(m);
        viewer.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
    }

    private boolean viewerKey(int scan, int dir) {
        if (vZoom > 0 && dir != 0) {              // zoomed: wheel pans vertically
            float step = dir * 150f * vZoom;
            float maxPan = 400f * vZoom;
            vPanY = Math.max(-maxPan, Math.min(0, vPanY + step));
            applyZoom();
            return true;
        }
        if (dir != 0 && !graded.isEmpty()) {
            viewing = (viewing + dir + graded.size()) % graded.size();
            vZoom = 0; vPanY = 0;
            showGraded();
            return true;
        }
        if (scan == K_ENTER) {                    // zoom cycle 1x->2x->4x
            vZoom = (vZoom + 1) % 3;
            if (vZoom == 0) vPanY = 0;
            applyZoom();
            setStatus("zoom x" + (vZoom == 0 ? 1 : vZoom == 1 ? 2 : 4));
            return true;
        }
        if (scan == K_PLAY || scan == K_MENU || scan == K_DELETE) {
            viewing = -1;
            viewer.setVisibility(View.INVISIBLE);
            overlay.setVisibility(View.INVISIBLE);
            renderOverlay();
            setStatus("");
            return true;
        }
        return true;
    }

    private boolean browserKey(int scan, int dir) {
        if (scan == K_LEFT) { browser = 0; renderOverlay(); return true; }               // jump to list start
        if (scan == K_RIGHT) { browser = totalSel() - 1; if (browser == divSel()) browser--; renderOverlay(); return true; } // jump to list end
        if (scan == K_C1) {
            if (browser >= NSPECIAL && browser != divSel()) {   // only real films can be favorited
                int r = filmIdx(browser);
                String nm = names.get(r);
                if (favs.contains(nm)) favs.remove(nm); else favs.add(nm);
                savePrefs();
            }
            renderOverlay(); return true;
        }
        if (scan == K_UP || scan == K_DOWN) { dir = (scan == K_DOWN) ? 1 : -1; }
        if (dir != 0) {
            browser = (browser + dir + totalSel()) % totalSel();
            if (browser == divSel()) browser = (browser + dir + totalSel()) % totalSel();   // the divider is never selectable
            renderOverlay();
            return true;
        }
        if (scan == K_ENTER) {
            if (browser == divSel()) return true;
            sel = browser;
            savePrefs();
            browser = -1;
            renderOverlay();
            setStatus("loaded " + selName(sel));
            return true;
        }
        if (scan == K_FN || scan == K_MENU) { browser = -1; renderOverlay(); return true; }
        if (scan == K_S2) { browser = -1; renderOverlay(); shoot(); return true; }
        return true;
    }

    private boolean settingsKey(int scan, int dir) {
        if (dir != 0) {
            settings = (settings + dir + SETTINGS_ROWS.length) % SETTINGS_ROWS.length;
            renderOverlay();
            return true;
        }
        if (scan == K_ENTER) {
            if (settings == 0) {
                qualityIdx = (qualityIdx + 1) % SCALES.length;
                savePrefs();
            } else if (settings == 1) {
                settings = -1;
                renderOverlay();
                String p = newestImageViaProvider(0);
                if (p == null) {
                    File pf = newestPhoto();
                    if (pf != null) p = pf.getAbsolutePath();
                }
                if (p != null) enqueue(new Job(new File(p), resolveSel(sel)));
                else setStatus("no photos found");
            } else if (settings == 2) {
                settings = -1;
                Logger.log("restart via settings");
                Logger.flush();
                System.exit(0);   // cold restart: monkey/launcher relaunches, fresh dex
            } else {
                settings = -1;
            }
            renderOverlay();
            return true;
        }
        if (scan == K_C1 || scan == K_MENU) { settings = -1; renderOverlay(); return true; }
        return true;
    }

    private String favsStr() {
        StringBuilder sb = new StringBuilder();
        java.util.HashSet<String> done = new java.util.HashSet<String>();
        for (int i = 0; i < names.size(); i++) { String n = names.get(i); if (favs.contains(n) && done.add(n)) { if (sb.length() > 0) sb.append(","); sb.append(n); } }
        for (String n : favs) if (done.add(n)) { if (sb.length() > 0) sb.append(","); sb.append(n); }   // keep favs whose film isn't in the current list (e.g. list not pulled yet)
        return sb.toString();
    }

    private boolean[] favArr() {
        boolean[] b = new boolean[totalSel()];
        for (int i = 0; i < names.size(); i++) b[posOf(i)] = favs.contains(names.get(i));
        return b;
    }

    private void jumpFav(int dir) {
        if (favs.isEmpty()) return;
        java.util.ArrayList<String> l = new java.util.ArrayList<String>();
        for (int i = 0; i < names.size(); i++) if (favs.contains(names.get(i))) l.add(names.get(i));   // list order
        if (l.isEmpty()) return;
        int cur = filmIdx(sel);
        String curName = (cur >= 0 && cur < names.size()) ? names.get(cur) : null;
        int pos = -1;
        for (int i = 0; i < l.size(); i++) if (l.get(i).equals(curName)) pos = i;
        if (pos < 0) { pos = 0; for (int i = 0; i < l.size(); i++) if (names.indexOf(l.get(i)) > cur) { pos = i; break; } }
        else pos = (pos + dir + l.size()) % l.size();
        sel = posOf(names.indexOf(l.get(pos)));
        savePrefs();
    }

    private void savePrefs() {
        android.content.SharedPreferences.Editor ed = getPreferences(MODE_PRIVATE).edit()
                .putInt("film2", sel)
                .putInt("quality", qualityIdx)
                .putString("favs", favsStr())
                .putInt("stamp", stampMode)
                .putInt("gfinder", ghostFinder);
        String nm = selName();                            // persist the selected film by NAME too (the index is unstable across list changes)
        if (nm.length() > 0) { wantFilm = nm; ed.putString("selfilm", nm); }
        ed.commit();
    }

    private String selName() {
        int fi = filmIdx(sel);
        return (fi >= 0 && fi < names.size()) ? names.get(fi) : "";
    }

    /** re-select the persisted film by name once the (host) list is available; no-op if absent */
    private void applyWantFilm() {
        if (wantFilm == null || wantFilm.isEmpty()) return;
        int idx = names.indexOf(wantFilm);
        if (idx >= 0) sel = posOf(idx);
    }

    @Override
    public void finish() {
        rig.release();
        super.finish();
    }
}
