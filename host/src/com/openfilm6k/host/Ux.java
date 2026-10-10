package com.openfilm6k.host;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

/** Windows 98 design language: light gray chrome, hard 3D bevels, etched group frames, navy accents. No rounded corners. */
public class Ux {
    static final int FACE = Color.parseColor("#C0C0C0");
    static final int HL = Color.WHITE;          // top-left highlight
    static final int SH = Color.parseColor("#808080");   // bottom-right shadow
    static final int DK = Color.BLACK;
    static final int FIELD = Color.WHITE;       // sunken text field fill
    static final int NAVY = Color.parseColor("#000080");
    static final int TXT = DK;
    static final int TXT_DISABLED = Color.parseColor("#808080");

    /** unified body font size for every non-readout text (buttons, labels, headers, checkboxes, spinners) */
    static final float BODY = 15f;

    private static android.graphics.Typeface segTf;
    /** seven-segment typeface for numeric readouts (DSEG7, res/raw); cached */
    static android.graphics.Typeface seg(android.content.Context c) {
        if (segTf == null) {
            try {
                java.io.File f = new java.io.File(c.getCacheDir(), "dseg7classic.ttf");
                if (!f.exists() || f.length() == 0) {
                    java.io.InputStream in = c.getResources().openRawResource(R.raw.dseg7classic);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.close(); in.close();
                }
                segTf = android.graphics.Typeface.createFromFile(f);
                android.util.Log.i("of6kUI", "seg font loaded, size=" + f.length());
            } catch (Throwable t) {
                android.util.Log.e("of6kUI", "seg font failed", t);
                segTf = android.graphics.Typeface.MONOSPACE;
            }
        }
        return segTf;
    }

    private static android.graphics.Typeface seg14Tf;
    /** fourteen-segment typeface (DSEG14, res/raw): supports full uppercase letters */
    static android.graphics.Typeface seg14(android.content.Context c) {
        if (seg14Tf == null) {
            try {
                java.io.File f = new java.io.File(c.getCacheDir(), "dseg14classic.ttf");
                if (!f.exists() || f.length() == 0) {
                    java.io.InputStream in = c.getResources().openRawResource(R.raw.dseg14classic);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.close(); in.close();
                }
                seg14Tf = android.graphics.Typeface.createFromFile(f);
                android.util.Log.i("of6kUI", "seg14 font loaded, size=" + f.length());
            } catch (Throwable t) {
                android.util.Log.e("of6kUI", "seg14 font failed", t);
                seg14Tf = android.graphics.Typeface.MONOSPACE;
            }
        }
        return seg14Tf;
    }

    private static android.graphics.Typeface libTf;
    /** Fjalla One (OFL, res/raw): condensed bold gothic for the B2 film-edge print */
    static android.graphics.Typeface liberation(android.content.Context c) {
        if (libTf == null) {
            try {
                java.io.File f = new java.io.File(c.getCacheDir(), "liberationsans.ttf");
                if (!f.exists() || f.length() == 0) {
                    java.io.InputStream in = c.getResources().openRawResource(R.raw.liberationsans);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.close(); in.close();
                }
                libTf = android.graphics.Typeface.createFromFile(f);
                android.util.Log.i("of6kUI", "liberation font loaded, size=" + f.length());
            } catch (Throwable t) {
                android.util.Log.e("of6kUI", "liberation font failed", t);
                libTf = android.graphics.Typeface.SANS_SERIF;
            }
        }
        return libTf;
    }

    private static android.graphics.Typeface libBoldTf;
    /** Liberation Sans Bold for the B2 stock name */
    static android.graphics.Typeface liberationBold(android.content.Context c) {
        if (libBoldTf == null) {
            try {
                java.io.File f = new java.io.File(c.getCacheDir(), "liberationsansbold.ttf");
                if (!f.exists() || f.length() == 0) {
                    java.io.InputStream in = c.getResources().openRawResource(R.raw.liberationsansbold);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.close(); in.close();
                }
                libBoldTf = android.graphics.Typeface.createFromFile(f);
                android.util.Log.i("of6kUI", "liberation bold font loaded, size=" + f.length());
            } catch (Throwable t) {
                android.util.Log.e("of6kUI", "liberation bold font failed", t);
                libBoldTf = liberation(c);
            }
        }
        return libBoldTf;
    }

    private static android.graphics.Typeface archivoTf;
    /** Archivo Black (OFL, res/raw): heavy display face for the B2 film-edge print (Latin) */
    static android.graphics.Typeface archivo(android.content.Context c) {
        if (archivoTf == null) archivoTf = loadFont(c, "archivoblack.ttf", R.raw.archivoblack, android.graphics.Typeface.SANS_SERIF, "archivo");
        return archivoTf;
    }

    private static android.graphics.Typeface wqyTf;
    /** WenQuanYi Zen Hei (res/raw, .ttc): CJK companion for the B2 film-edge print */
    static android.graphics.Typeface wqyZen(android.content.Context c) {
        if (wqyTf == null) wqyTf = loadFont(c, "wqyzenhei.ttc", R.raw.wqyzenhei, android.graphics.Typeface.SANS_SERIF, "wqyzen");
        return wqyTf;
    }

    /** edge-print face: WenQuanYi Zen Hei when the string contains CJK, else Archivo Black */
    static android.graphics.Typeface edgeFace(android.content.Context c, String s) {
        return hasCjk(s) ? wqyZen(c) : archivo(c);
    }

    private static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x2E80 && ch <= 0x9FFF) return true;   // CJK radicals .. unified ideographs
        }
        return false;
    }

    /** copy a res/raw ttf/ttc into the cache and build a Typeface (fallback on failure) */
    private static android.graphics.Typeface loadFont(android.content.Context c, String name, int res, android.graphics.Typeface fb, String tag) {
        try {
            java.io.File f = new java.io.File(c.getCacheDir(), name);
            if (!f.exists() || f.length() == 0) {
                java.io.InputStream in = c.getResources().openRawResource(res);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                fo.close(); in.close();
            }
            android.graphics.Typeface tf = android.graphics.Typeface.createFromFile(f);
            android.util.Log.i("of6kUI", tag + " font loaded, size=" + f.length());
            return tf;
        } catch (Throwable t) {
            android.util.Log.e("of6kUI", tag + " font failed", t);
            return fb;
        }
    }

    private static android.graphics.Typeface seg14iTf;
    /** fourteen-segment italic (DSEG14 Italic) for watermarks */
    static android.graphics.Typeface seg14it(android.content.Context c) {
        if (seg14iTf == null) {
            try {
                java.io.File f = new java.io.File(c.getCacheDir(), "dseg14italic.ttf");
                if (!f.exists() || f.length() == 0) {
                    java.io.InputStream in = c.getResources().openRawResource(R.raw.dseg14italic);
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.close(); in.close();
                }
                seg14iTf = android.graphics.Typeface.createFromFile(f);
                android.util.Log.i("of6kUI", "seg14 italic font loaded, size=" + f.length());
            } catch (Throwable t) {
                android.util.Log.e("of6kUI", "seg14 italic font failed", t);
                seg14iTf = seg14(c);
            }
        }
        return seg14iTf;
    }

    /** app-pinned layout density. Baseline = the dev device at minimum system display size:
     *  1436px short edge at density 2.25 -> 638.22dp of content width on every device,
     *  regardless of the system display-size setting or panel resolution. */
    static final float BASE_SHORT_DP = 638.2222f;
    static final float FONT_RATIO = 0.85f;   // baseline includes the dev device font scale (2.25 * 0.85 = 1.9125 sp factor)
    private static Float sdCache;
    /** pinned layout density: dp() and text sizes derive from this, never from the system density */
    static float sd() {
        if (sdCache == null) {
            android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
            sdCache = Math.min(dm.widthPixels, dm.heightPixels) / BASE_SHORT_DP;
        }
        return sdCache;
    }
    /** pinned sp->px factor (baseline scaledDensity); system font scale cannot inflate the UI */
    static float tscale() { return sd() * FONT_RATIO; }
    /** pinned text size in px: setTextSize(TypedValue.COMPLEX_UNIT_PX, ts(units)) */
    static float ts(float spUnits) { return spUnits * tscale(); }

    static int dp(float v) { return Math.round(v * sd()); }

    /** classic 98 bevel: raised = white top/left + dark bottom/right; sunken = inverted */
    static class Bevel extends Drawable {
        final boolean raised; final boolean field; final int inset;
        final boolean insetBody;   // draw the body 3dp inside the view bounds (raised buttons + pressed tabs)
        final int iw, ih;   // intrinsic size (0 = none)
        Bevel(boolean raised, boolean field, int insetPx) { this(raised, field, insetPx, false, 0, 0); }
        Bevel(boolean raised, boolean field, int insetPx, boolean bodyInset, int intrinsicW, int intrinsicH) {
            this.raised = raised; this.field = field; this.inset = insetPx; this.insetBody = bodyInset;
            this.iw = intrinsicW; this.ih = intrinsicH;
        }
        @Override public int getIntrinsicWidth() { return iw > 0 ? iw : -1; }
        @Override public int getIntrinsicHeight() { return ih > 0 ? ih : -1; }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            int w = b.width(), h = b.height(), n = Math.max(1, inset);
            int m = insetBody ? dp(3) : 0;   // visual inset only for raised buttons: sunken fields stay full-bleed (a white rim leaked otherwise)
            w -= 2 * m; h -= 2 * m;
            c.translate(m, m);
            android.graphics.Paint p = new android.graphics.Paint();
            p.setColor(field ? FIELD : FACE);
            c.drawRect(0, 0, w, h, p);   // opaque face within the (possibly inset) body
            if (raised) {
                p.setColor(HL);      // top + left highlight
                c.drawRect(0, 0, w, n, p); c.drawRect(0, 0, n, h, p);
                p.setColor(DK);      // outermost bottom + right black line
                c.drawRect(0, h - n, w, h, p); c.drawRect(w - n, 0, w, h, p);
                p.setColor(SH);      // inner bottom + right shadow
                c.drawRect(0, h - 2 * n, w - n, h - n, p); c.drawRect(w - 2 * n, 0, w - n, h - n, p);
            } else {
                // sunken: BLACK top/left (recess shadow), WHITE bottom/right (light catch)
                p.setColor(DK);
                c.drawRect(0, 0, w, n, p); c.drawRect(0, 0, n, h, p);
                p.setColor(HL);
                c.drawRect(0, h - n, w, h, p); c.drawRect(w - n, 0, w, h, p);
            }
            c.translate(-m, -m);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    static Drawable raised() { return new Bevel(true, false, dp(2), true, 0, 0); }
    /** win98 window frame: full-bleed 2dp raised bevel (no body inset) for dialog backgrounds */
    static Drawable frame98() { return new Bevel(true, false, dp(2)); }
    static Drawable sunken() { return new Bevel(false, false, dp(2)); }
    static Drawable sunkenWhite() { return new Bevel(false, true, dp(2), true, 0, 0); }   // pressed-in tab: same 38dp body as raised, white face

    /** win98 radio button: the authentic 12x12 pixel art from 98.css, pre-rendered bitmaps scaled to dp(12) nearest-neighbour */
    static android.graphics.drawable.StateListDrawable radio98(android.content.Context c) {
        int px = dp(17);   // chunkier pixels: bigger cells read as pixel-art next to other UI elements
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.BitmapDrawable on = pixelRadio(c, R.drawable.radio98_on, px);
        android.graphics.drawable.BitmapDrawable off = pixelRadio(c, R.drawable.radio98_off, px);
        sld.addState(new int[]{android.R.attr.state_checked}, on);
        sld.addState(new int[]{}, off);
        return sld;
    }
    private static android.graphics.drawable.BitmapDrawable pixelRadio(android.content.Context c, int res, int px) {
        android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeResource(c.getResources(), res);
        android.graphics.Bitmap bm = android.graphics.Bitmap.createScaledBitmap(src, px, px, false);   // nearest: keep pixel-art crisp
        android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(c.getResources(), bm);
        d.setFilterBitmap(false);
        return d;
    }

    /** win98 checkbox: the authentic 13x13 pixel art from 98.css (white field + border-field sunken bevel + 7x7 checkmark), pre-rendered bitmaps scaled to dp(18) nearest-neighbour */
    static android.graphics.drawable.Drawable checkbox98(android.content.Context c, boolean checked) {
        return pixelRadio(c, checked ? R.drawable.checkbox98_on : R.drawable.checkbox98_off, dp(18));
    }

    /** checkbox98 as a checked/unchecked state list for CheckBox widgets (inset sideways like the native indicator) */
    static android.graphics.drawable.Drawable checkbox98States(android.content.Context c) {
        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_checked}, checkbox98(c, true));
        sld.addState(new int[]{}, checkbox98(c, false));
        return new android.graphics.drawable.InsetDrawable(sld, dp(2), 0, dp(2), 0);
    }

    /** spy glyph: original 16x16 pixel art in the win98 palette (fedora + glasses + high-collar coat), scaled to dp(22) nearest-neighbour */
    static android.graphics.drawable.Drawable spy98(android.content.Context c) {
        return pixelRadio(c, R.drawable.spy98, dp(22));
    }

    /** win98 tool-dialog title bar: navy body, recessed (dark top/left, light bottom/right) */
    static android.graphics.drawable.Drawable navySunkenBar() {
        return new android.graphics.drawable.Drawable() {
            @Override public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                int n = Math.max(1, dp(1));
                android.graphics.Paint p = new android.graphics.Paint();
                p.setColor(NAVY); c.drawRect(b.left, b.top, b.right, b.bottom, p);
                p.setColor(DK); c.drawRect(b.left, b.top, b.right, b.top + n, p); c.drawRect(b.left, b.top, b.left + n, b.bottom, p);
                p.setColor(SH); c.drawRect(b.left, b.bottom - n, b.right, b.bottom, p); c.drawRect(b.right - n, b.top, b.right, b.bottom, p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(ColorFilter f) {}
            @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
        };
    }
    static Drawable sunkenField() { return new Bevel(false, true, dp(2)); }

    /** etched 1px group frame (classic group box border) */
    static class Etched extends Drawable {
        @Override public void draw(Canvas c) {
            // classic etched pair: BLACK frame offset top-left, WHITE frame offset bottom-right
            android.graphics.Rect b = getBounds();
            int n = dp(2);
            android.graphics.Paint p = new android.graphics.Paint();
            p.setColor(DK);   // dark frame, top-left
            c.drawRect(0, 0, b.width() - n, n, p);
            c.drawRect(0, 0, n, b.height() - n, p);
            c.drawRect(0, b.height() - 2 * n, b.width() - n, b.height() - n, p);
            c.drawRect(b.width() - 2 * n, 0, b.width() - n, b.height() - n, p);
            p.setColor(HL);   // white frame, bottom-right (adjacent, diagonal offset)
            c.drawRect(n, n, b.width(), 2 * n, p);
            c.drawRect(n, n, 2 * n, b.height(), p);
            c.drawRect(n, b.height() - n, b.width(), b.height(), p);
            c.drawRect(b.width() - n, n, b.width(), b.height(), p);
        }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** pressed state: sunken bevel + content shifted 1px down-right (classic press-in) */
    static Drawable buttonDrawable() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, new Bevel(false, false, dp(2), true, 0, 0));
        s.addState(new int[]{-android.R.attr.state_enabled}, new Bevel(true, false, dp(2), true, 0, 0));
        s.addState(new int[]{}, raised());
        return s;
    }

    static boolean dangerText(String t) { return t != null && t.contains("✕"); }
    static boolean primaryText(String t) { return t != null && t.equals(L.s("tabPrev")); }

    static void styleButton(Button b) {
        b.setBackground(buttonDrawable());
        b.setTextColor(ColorStateList.valueOf(TXT));
        b.setAllCaps(false);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ts(BODY));
        b.setGravity(android.view.Gravity.CENTER);   // wrapped labels center vertically (default sits low)
        b.setMaxLines(1);   // win98 buttons never wrap; autosize below shrinks text to fit instead (NOT setSingleLine: it enables horizontal scrolling and disables autosize)
        if (android.os.Build.VERSION.SDK_INT >= 26)
            b.setAutoSizeTextTypeUniformWithConfiguration(Math.round(9 * sd()), Math.round(15 * sd()), 1, android.util.TypedValue.COMPLEX_UNIT_PX);   // pinned px: DIP would let the device density inflate wide-button labels
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinimumHeight(dp(48));   // theme Button minimum is 48 DEVICE-dp (wrap-content rows clamp to it); pin it so the clamp scales with the layout, not the panel
        b.setMinimumWidth(0);
        b.setMinHeight(dp(36));   // visual body is smaller than the 44dp slot: bg shrinks, slot stays
        if (primaryText(b.getText().toString())) b.getPaint().setFakeBoldText(true);
    }

    /** apply the theme to a whole tree; idempotent, call after any rebuild */
    static void themeTree(View root) {
        if (root instanceof android.widget.RadioButton) {   // radio: flat control — NO button bevel background
            ((android.widget.RadioButton) root).setBackground(null);
            ((android.widget.RadioButton) root).setTextColor(TXT);
            ((android.widget.RadioButton) root).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ts(BODY));
            ((android.widget.RadioButton) root).setAllCaps(false);
        } else if (root instanceof CheckBox) {   // MUST precede Button: CheckBox extends Button
            ((CheckBox) root).setTextColor(TXT);
            ((CheckBox) root).setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ts(BODY));
            ((CheckBox) root).setButtonDrawable(checkbox98States(root.getContext()));   // authentic 98.css pixel art, replaces the Material indicator
        } else if (root instanceof Button) {
            styleButton((Button) root);
        } else if (root instanceof TextView && !(root instanceof Button)) {
            ((TextView) root).setTextColor(TXT);
        } else if (root instanceof SeekBar) {
            SeekBar sb = (SeekBar) root;
            sb.setProgressDrawable(groove98());   // sunken bevel groove (full widget height — no vertical padding)
            sb.setPadding(dp(8), 0, dp(8), 0);   // ≥ thumb half-width so the knob stays inside at 0/max — late setThumb freezes thumb position here
        } else if (root instanceof Spinner) {
            ((Spinner) root).setPadding(dp(6), dp(4), dp(42), dp(4));   // room for the combo arrow button
            ((Spinner) root).setPopupBackgroundDrawable(popup98());
            ((Spinner) root).setDropDownVerticalOffset(dp(44));          // the list starts below the control
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) themeTree(vg.getChildAt(i));
        }
    }

    /** win98 slider track: thin sunken groove centered vertically */
    static android.graphics.drawable.Drawable thinTrack98() {
        android.graphics.drawable.Drawable groove = new Drawable() {
            @Override public void draw(Canvas c) {
                android.graphics.Rect b = getBounds();
                android.graphics.Paint p = new android.graphics.Paint();
                int th = dp(2);
                int cy = b.centerY();
                p.setColor(SH);
                c.drawRect(0, cy - th, b.width(), cy, p);
                p.setColor(HL);
                c.drawRect(0, cy, b.width(), cy + th, p);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(ColorFilter cf) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
        android.graphics.drawable.PaintDrawable prog = new android.graphics.drawable.PaintDrawable(NAVY);
        int g = dp(10);
        android.graphics.drawable.InsetDrawable pi = new android.graphics.drawable.InsetDrawable(prog, 0, g, 0, g);
        android.graphics.drawable.InsetDrawable bi = new android.graphics.drawable.InsetDrawable(groove, 0, g, 0, g);
        android.graphics.drawable.LayerDrawable ly = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{bi, pi});
        ly.setId(0, 16908288);   // android.R.id.background
        ly.setId(1, 16908301);   // android.R.id.progress
        ly.setLayerInset(1, 0, g, 0, g);
        return ly;
    }

    /** wrap a drawable with explicit intrinsic size (for thumb sizing) */
    static android.graphics.drawable.Drawable fixedSize(final android.graphics.drawable.Drawable d, final int w, final int h) {
        return new Drawable() {
            @Override public void draw(Canvas c) { d.setBounds(getBounds()); d.draw(c); }
            @Override public int getIntrinsicWidth() { return w; }
            @Override public int getIntrinsicHeight() { return h; }
            @Override public void setAlpha(int a) { d.setAlpha(a); }
            @Override public void setColorFilter(ColorFilter cf) { d.setColorFilter(cf); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /** win98 sunken trackbar channel: thin groove, dark top/left + white bottom/right, no fill */
    static android.graphics.drawable.Drawable groove98() {
        int n = dp(2), g = dp(8);
        android.graphics.drawable.Drawable[] bg = {
            rect(HL, 1, 1),                              // white shows at bottom/right
            rect(DK, 1, 1),                              // dark top/left (sunken)
            rect(FACE, 1, 1)                             // channel face
        };
        android.graphics.drawable.LayerDrawable channel = new android.graphics.drawable.LayerDrawable(bg);
        channel.setLayerInset(0, 0, 0, 0, 0);
        channel.setLayerInset(1, 0, 0, n, n);              // dark hugs top/left
        channel.setLayerInset(2, n, n, n, n);              // channel face
        return new android.graphics.drawable.InsetDrawable(channel, 0, g, 0, g);
    }

    /** win98 combo box: sunken field + separate raised square arrow button on the right */
    static android.widget.FrameLayout wrapSpinner(android.content.Context c, android.widget.Spinner sp) {
        sp.setBackground(sunkenField());
        sp.setPadding(dp(6), dp(4), dp(42), dp(4));
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(c);
        // field matches the buttons' VISUAL body: 44dp slot - 2*3dp bevel inset = 38dp
        wrap.addView(sp, new android.widget.FrameLayout.LayoutParams(-1, dp(38), android.view.Gravity.CENTER_VERTICAL));
        android.view.View arrow = new View(c);
        arrow.setClickable(false);   // touches fall through to the spinner
        arrow.setBackground(arrowButton98());
        android.widget.FrameLayout.LayoutParams ap = new android.widget.FrameLayout.LayoutParams(dp(38), dp(38),
                android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        ap.rightMargin = dp(3);
        wrap.addView(arrow, ap);
        return wrap;
    }

    /** raised square button face with a solid black down triangle */
    static android.graphics.drawable.Drawable arrowButton98() {
        android.graphics.Path pth = new android.graphics.Path();
        pth.moveTo(0, 0); pth.lineTo(12, 0); pth.lineTo(6, 8); pth.close();
        android.graphics.drawable.ShapeDrawable tri = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.PathShape(pth, 12, 8));
        tri.getPaint().setColor(DK);
        android.graphics.drawable.Drawable[] ds = { raised(), tri };
        android.graphics.drawable.LayerDrawable ly = new android.graphics.drawable.LayerDrawable(ds);
        int cx = dp(13), cy = dp(15);
        ly.setLayerInset(1, cx, cy, cx, cy);
        return ly;
    }

    /** win98 dropdown list popup: white sheet, black border */
    static android.graphics.drawable.Drawable popup98() {
        android.graphics.drawable.ShapeDrawable fill = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.RectShape());
        fill.getPaint().setColor(Color.WHITE);
        android.graphics.drawable.ShapeDrawable border = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.RectShape());
        border.getPaint().setColor(DK);
        border.getPaint().setStyle(android.graphics.Paint.Style.STROKE);
        border.getPaint().setStrokeWidth(dp(2));
        android.graphics.drawable.LayerDrawable ly = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{fill, border});
        return ly;
    }

    /** square filled rect with intrinsic size */
    static android.graphics.drawable.ShapeDrawable rect(int color, int w, int h) {
        android.graphics.drawable.ShapeDrawable sd = new android.graphics.drawable.ShapeDrawable(new android.graphics.drawable.shapes.RectShape());
        sd.setIntrinsicWidth(w);
        sd.setIntrinsicHeight(h);
        sd.getPaint().setColor(color);
        return sd;
    }

    /** win98 trackbar thumb: raised block, classic bevel */
    static android.graphics.drawable.Drawable thumb98() {
        int n = dp(2);
        android.graphics.drawable.Drawable[] ds = {
            rect(DK, dp(14), dp(22)),
            rect(HL, dp(14) - n, dp(22) - n),
            rect(FACE, dp(14) - 2 * n, dp(22) - 2 * n)
        };
        android.graphics.drawable.LayerDrawable ly = new android.graphics.drawable.LayerDrawable(ds);
        ly.setLayerInset(0, 0, 0, 0, 0);
        ly.setLayerInset(1, 0, 0, n, n);
        ly.setLayerInset(2, n, n, n, n);
        return ly;
    }

    /** uniform field label: fixed width, right-aligned, black */
    static void styleLabel(TextView tv) {
        tv.setTextColor(TXT);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ts(BODY));
        tv.setGravity(android.view.Gravity.RIGHT | android.view.Gravity.CENTER_VERTICAL);
        tv.setPadding(0, 0, dp(8), 0);
    }

    /** section header (group box label): bold black on chrome */
    static void styleHeader(TextView tv) {
        tv.setTextColor(TXT);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ts(BODY));
        tv.getPaint().setFakeBoldText(true);
        tv.setAllCaps(false);
        tv.setBackgroundColor(FACE);
        tv.setPadding(dp(4), 0, dp(4), 0);
    }

    /** node panel: sunken bevel (recessed into the group box) */
    static void card(View v) {
        v.setBackground(sunken());
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
    }
}
