package com.openfilm6k.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Full-screen custom watermark (card) editor — UI follows the rest of the app: navy title bar with
 * 38dp buttons, win98 group boxes with etched frames, spinners and sliders built from Ux helpers.
 */
public class WatermarkActivity extends Activity {
    private static final int REQ_IMG = 4101;
    private static final String[] ICON_GLYPH = {"\u2699", "\u21E7", "\u21E9", "\u2715"};   // props / front / back / delete
    private static final int[] PALETTE = {0xFF000000, 0xFFFFFFFF, 0xFF555555, 0xFF999999,
            0xFFC0392B, 0xFF2E86DE, 0xFF27AE60, 0xFFF39C12};

    private Watermark.Tpl tpl;
    private WmCanvasView canvas;
    private TextView nameView;
    private Button snapBtn;
    private Spinner tplSpin, refSpin, addTypeSpin;
    /** macros that can be inserted into a text element (same tokens the renderer substitutes) */
    private static final String[] MACRO_TOKENS = {"{model}", "{shutter}", "{aperture}", "{iso}",
            "{exposure}", "{date}", "{time}", "{film}"};
    private static final String[] MACRO_KEYS = {"wmTokModel", "wmTokShutter", "wmTokAperture", "wmTokIso",
            "wmTokExposure", "wmTokDate", "wmTokTime", "wmTokFilm"};
    private static final String[] FONT_KEYS = {"wmFontSans", "wmFontSerif", "wmFontFangsong",
            "wmFontSmiley", "wmFontCaoshu", "wmFontWqy", "wmFontPoppins", "wmFontOswald",
            "wmFontArchivo", "wmFontBebas", "wmFontPlayfair", "wmFontBaskerville", "wmFontLora",
            "wmFontGaramond", "wmFontScript", "wmFontMono", "wmFontJbmono", "wmFontDseg14"};
    private static final String[] ALIGN_IDS = {"left", "center", "right"};
    private static final String[] ALIGN_KEYS = {"wmLeft", "wmCenter", "wmRight"};
    /** the card is treated as a 4-inch-tall print, so 1 pt = 1/288 of its height */
    private static final float PT_PER_CARD = 288f;

    /** the app's standard compat combo; writes the selected index into `out` */
    private View combo(ArrayList<String> labels, int sel, final int[] out) {
        Spinner sp = new Spinner(this);
        EditorActivity.StdSpinnerAdapter a = new EditorActivity.StdSpinnerAdapter(this, labels);
        a.spin = sp;
        sp.setAdapter(a);
        sp.setSelection(Math.max(0, Math.min(labels.size() - 1, sel)));
        out[0] = sel;
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { out[0] = pos; }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        return Ux.wrapSpinner(this, sp);
    }

    private ArrayList<String> macroLabels() {
        ArrayList<String> out = new ArrayList<String>();
        for (String k : MACRO_KEYS) out.add(L.s(k));
        return out;
    }

    /** dropdown rows preview their own face: each font name is drawn IN that font, a bit larger */
    private final class FontAdapter extends EditorActivity.StdSpinnerAdapter {
        FontAdapter(ArrayList<String> items) { super(WatermarkActivity.this, items); }
        private void face(TextView v, int pos) {
            if (pos < 0 || pos >= Watermark.FONT_IDS.length) return;
            v.setTypeface(Watermark.face(Watermark.FONT_IDS[pos], false));
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(19));
        }
        @Override public View getView(int pos, View cv, android.view.ViewGroup pg) {
            View v = super.getView(pos, cv, pg);
            if (v instanceof TextView) face((TextView) v, pos);
            return v;
        }
        @Override public View getDropDownView(int pos, View cv, android.view.ViewGroup pg) {
            View v = super.getDropDownView(pos, cv, pg);
            if (v instanceof TextView) face((TextView) v, pos);
            return v;
        }
    }

    private View fontCombo(int sel, final int[] out) {
        Spinner sp = new Spinner(this);
        FontAdapter a = new FontAdapter(fontLabels());
        a.spin = sp;
        sp.setAdapter(a);
        sp.setSelection(Math.max(0, Math.min(a.getCount() - 1, sel)));
        out[0] = sel;
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { out[0] = pos; }
            public void onNothingSelected(AdapterView<?> p) {}
        });
        return Ux.wrapSpinner(this, sp);
    }

    private ArrayList<String> fontLabels() {
        ArrayList<String> out = new ArrayList<String>();
        for (String k : FONT_KEYS) out.add(L.s(k));
        return out;
    }

    private ArrayList<String> dirLabels() {
        ArrayList<String> out = new ArrayList<String>();
        out.add(L.s("wmDirH"));
        out.add(L.s("wmDirV"));
        return out;
    }

    private ArrayList<String> blendLabels() {
        ArrayList<String> out = new ArrayList<String>();
        out.add(L.s("wmBlendNormal"));
        out.add(L.s("wmBlendAdapt"));
        out.add(L.s("wmBlendScreen"));
        return out;
    }

    private ArrayList<String> alignLabels() {
        ArrayList<String> out = new ArrayList<String>();
        for (String k : ALIGN_KEYS) out.add(L.s(k));
        return out;
    }

    private static final String[] ADD_TOKENS = {"image", "photo", "text", "hline", "vline", "logo", "rect",
            "{model}", "{shutter}", "{aperture}", "{iso}", "{exposure}", "{date}", "{time}", "{film}"};
    private boolean tplSpinInit, refSpinInit;
    private boolean snap = true;
    private boolean showPreview = true;
    private boolean dirty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Watermark.ctx = getApplicationContext();   // res/raw typefaces (this activity can be the entry point)
        try { getWindow().setStatusBarColor(Ux.NAVY); } catch (Throwable ig) {}
        try { getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ig) {}
        android.content.SharedPreferences pf = getSharedPreferences("of6k", 0);
        snap = pf.getBoolean("wm_snap", true);
        showPreview = pf.getBoolean("wm_preview_on", true);
        String id = pf.getString("wm_last", "");
        if (!id.isEmpty()) tpl = Watermark.load(id);
        if (tpl == null) tpl = newest();
        buildUi();
        canvas.setTemplate(tpl);
        bindTitle();
        // the APK ships no brand logos: remind on EVERY entry while none are on disk
        if (Logo.have() == 0) canvas.post(new Runnable() {
            public void run() { logoPrompt(); }
        });
    }

    private Watermark.Tpl newest() {
        Watermark.Tpl best = null;
        long bestT = -1;
        for (String s2 : Watermark.ids()) {
            Watermark.Tpl t = Watermark.load(s2);
            if (t == null) continue;
            long lm = Watermark.dir(s2).lastModified();
            if (lm > bestT) { bestT = lm; best = t; }
        }
        return best != null ? best : Watermark.create("Card 1");
    }

    // ------------------------------------------------------------------ chrome

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ux.FACE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Ux.NAVY);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ux.dp(6), Ux.dp(3), Ux.dp(6), Ux.dp(3));   // 38dp controls + 3dp padding == the main screen bar (100px)

        Button back = new Button(this);
        back.setText("\u2190");
        back.setMinWidth(0); back.setMinimumWidth(0); back.setPadding(0, 0, 0, 0);
        back.setContentDescription(L.s("close"));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmExit(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(Ux.dp(38), Ux.dp(38)));

        nameView = new TextView(this);
        nameView.setText(L.s("wmTitle"));
        nameView.setTextColor(0xFFFFFFFF);
        nameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY));
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nameView.setPadding(Ux.dp(8), 0, Ux.dp(8), 0);
        bar.addView(nameView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        addTypeSpin = new Spinner(this);
        EditorActivity.StdSpinnerAdapter addA =
                new EditorActivity.CompactSpinnerAdapter(this, addTypeLabels());   // same as the C1-C3 switcher
        addA.spin = addTypeSpin;
        addTypeSpin.setAdapter(addA);
        FrameLayout addWrap = Ux.wrapCompactCombo(this, addTypeSpin, Ux.dp(38));   // identical to Rewind C1/C2/C3
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(Ux.dp(184), Ux.dp(30));   // 2x the C1-C3 width
        addLp.leftMargin = Ux.dp(4);
        bar.addView(addWrap, addLp);

        bar.addView(iconButton("\u2295", "Add element", new View.OnClickListener() {
            public void onClick(View v) { addSelectedType(); }
        }), new LinearLayout.LayoutParams(Ux.dp(38), Ux.dp(38)));

        snapBtn = iconButton("\u25A6", "Snap to grid", new View.OnClickListener() {
            public void onClick(View v) { toggleSnap(); }
        });
        bar.addView(snapBtn, new LinearLayout.LayoutParams(Ux.dp(42), Ux.dp(38)));

        root.addView(bar, new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout selG = new FrameLayout(this);
        LinearLayout selRow = new LinearLayout(this);
        selRow.setOrientation(LinearLayout.HORIZONTAL);
        selRow.setGravity(Gravity.CENTER_VERTICAL);
        selRow.setBackground(new Ux.Etched());
        selRow.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        tplSpin = new Spinner(this);
        refSpin = new Spinner(this);
        selRow.addView(Ux.wrapSpinner(this, tplSpin), new LinearLayout.LayoutParams(0, -2, 2.025f));   // same button width as the film row (0.45 / 5.4)
        selRow.addView(iconButton("\uff0b", L.s("newFilm"), new View.OnClickListener() {
            public void onClick(View v) { showNewTemplate(); }
        }), new LinearLayout.LayoutParams(0, Ux.dp(44), 0.45f));
        selRow.addView(iconButton("\u2715", L.s("delFilm"), new View.OnClickListener() {
            public void onClick(View v) { deleteCurrentTemplate(); }
        }), new LinearLayout.LayoutParams(0, Ux.dp(44), 0.45f));
        selRow.addView(iconButton("\uD83D\uDCBE", L.s("saveCfg"), new View.OnClickListener() {
            public void onClick(View v) { saveNow(); }
        }), new LinearLayout.LayoutParams(0, Ux.dp(44), 0.45f));
        selRow.addView(iconButton("\u27F3", L.s("reload"), new View.OnClickListener() {
            public void onClick(View v) { reloadNow(); }
        }), new LinearLayout.LayoutParams(0, Ux.dp(44), 0.45f));
        LinearLayout.LayoutParams rgap = new LinearLayout.LayoutParams(0, -2, 2.025f);
        rgap.leftMargin = Ux.dp(6);
        selRow.addView(Ux.wrapSpinner(this, refSpin), rgap);
        TextView selH = new TextView(this);
        selH.setText(L.s("wmSelTitle"));
        Ux.styleHeader(selH);
        selG.setClipChildren(false);
        FrameLayout.LayoutParams sil = new FrameLayout.LayoutParams(-1, -2);
        sil.topMargin = Ux.dp(10);
        selG.addView(selRow, sil);
        FrameLayout.LayoutParams shl = new FrameLayout.LayoutParams(-2, -2);
        shl.leftMargin = Ux.dp(10);
        shl.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        selG.addView(selH, shl);
        LinearLayout selWrap = new LinearLayout(this);
        selWrap.setPadding(Ux.dp(6), Ux.dp(2), Ux.dp(6), Ux.dp(2));
        selWrap.addView(selG, new LinearLayout.LayoutParams(-1, -2));
        root.addView(selWrap);

        FrameLayout holder = new FrameLayout(this);
        holder.setBackgroundColor(0xFF008080);   // win98 desktop teal
        holder.setPadding(Ux.dp(6), Ux.dp(6), Ux.dp(6), Ux.dp(6));
        canvas = new WmCanvasView(this);
        holder.addView(canvas, new FrameLayout.LayoutParams(-1, -1));
        root.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1f));

        Ux.themeTree(root);
        nameView.setTextColor(0xFFFFFFFF);   // themeTree recolours plain TextViews: restore the navy-bar white
        setContentView(root);
        bindSnapBtn();
        refreshTplSpin();
        refreshRefSpin();
    }

    private java.util.List<String> tplIds = new ArrayList<String>();

    /** template dropdown: all saved templates by name */
    private void refreshTplSpin() {
        if (tplSpin == null) return;
        final java.util.List<Watermark.Tpl> list = new ArrayList<Watermark.Tpl>();
        tplIds.clear();
        ArrayList<String> names = new ArrayList<String>();
        for (String id : Watermark.ids()) {
            Watermark.Tpl t = Watermark.load(id);
            if (t == null) continue;
            list.add(t);
            tplIds.add(id);
            names.add(t.name);
        }
        EditorActivity.StdSpinnerAdapter a = new EditorActivity.StdSpinnerAdapter(this, names);
        a.spin = tplSpin;
        tplSpinInit = true;
        tplSpin.setAdapter(a);
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(tpl.id)) { tplSpin.setSelection(i); break; }
        tplSpin.post(new Runnable() { public void run() { tplSpinInit = false; } });
        tplSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (tplSpinInit) return;
                if (pos < 0 || pos >= tplIds.size()) return;
                String want = tplIds.get(pos);
                if (tpl != null && tpl.id.equals(want)) return;
                // ALWAYS the persisted version: the snapshot in `list` may hold unsaved edits
                Watermark.Tpl t = Watermark.load(want);
                if (t == null) t = list.get(pos);          // just-created / unreadable: keep the snapshot
                tpl = t;
                canvas.setTemplate(tpl);
                bindTitle();
                dirty = false;
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    /** reference dropdown: Off + every reference photo, official ones shown with their localised scene name */
    private java.util.List<java.io.File> refList = new ArrayList<java.io.File>();

    private void refreshRefSpin() {
        if (refSpin == null) return;
        refList = refFiles();
        ArrayList<String> names = new ArrayList<String>();
        names.add(L.s("wmPreviewOff"));
        for (java.io.File f : refList) names.add(refLabel(f));
        android.content.SharedPreferences pf = getSharedPreferences("of6k", 0);
        String cur = pf.getString("wm_preview_ref", "");
        boolean on = pf.getBoolean("wm_preview_on", true);
        int sel;
        if (!on) sel = 0;
        else if (!cur.isEmpty()) {
            sel = 0;
            for (int i = 0; i < refList.size(); i++) if (refList.get(i).getAbsolutePath().equals(cur)) sel = i + 1;
            if (sel == 0 && !refList.isEmpty()) sel = 1;      // stored file vanished: fall back to the first reference
        } else sel = refList.isEmpty() ? 0 : 1;               // default: preview the first reference
        EditorActivity.StdSpinnerAdapter a = new EditorActivity.StdSpinnerAdapter(this, names);
        a.spin = refSpin;
        refSpinInit = true;
        refSpin.setAdapter(a);
        refSpin.setSelection(sel);
        refSpin.post(new Runnable() { public void run() { refSpinInit = false; } });
        refSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (refSpinInit) return;
                if (pos == 0) chooseRef("", false);
                else if (pos - 1 < refList.size()) chooseRef(refList.get(pos - 1).getAbsolutePath(), true);
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    /** official preview_<tag>.jpg -> localised scene name; user references keep their file name */
    private String refLabel(java.io.File f) {
        String n = f.getName();
        if (n.startsWith("preview_") && n.endsWith(".jpg")) return L.scene(n.substring(8, n.length() - 4));
        return n;
    }

    /** localised labels for the add-type dropdown (the inserted value stays the raw token) */
    private java.util.ArrayList<String> addTypeLabels() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        out.add(L.s("wmAddImage"));
        out.add(L.s("wmAddPhoto"));
        out.add(L.s("wmAddText"));
        out.add(L.s("wmAddHLine"));
        out.add(L.s("wmAddVLine"));
        out.add(L.s("wmAddLogo"));
        out.add(L.s("wmAddRect"));
        out.add(L.s("wmTokModel"));
        out.add(L.s("wmTokShutter"));
        out.add(L.s("wmTokAperture"));
        out.add(L.s("wmTokIso"));
        out.add(L.s("wmTokExposure"));
        out.add(L.s("wmTokDate"));
        out.add(L.s("wmTokTime"));
        out.add(L.s("wmTokFilm"));
        return out;
    }

    /** the add button adds whatever the type dropdown shows, with no dialog */
    private void addSelectedType() {
        int i = addTypeSpin == null ? 0 : addTypeSpin.getSelectedItemPosition();
        if (i < 0 || i >= ADD_TOKENS.length) return;
        String t = ADD_TOKENS[i];
        if ("image".equals(t)) pickImage();
        else if ("photo".equals(t)) addPhoto();
        else if ("text".equals(t)) addText("TEXT");
        else if ("hline".equals(t)) addLine(true);
        else if ("vline".equals(t)) addLine(false);
        else if ("logo".equals(t)) addLogo();
        else if ("rect".equals(t)) addRect();
        else addText(t);
    }

    /** 38dp title-bar button with a glyph, same size/behaviour as the main screen's Luma/Spy buttons */
    private Button iconButton(String glyph, String desc, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(glyph);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(0, 0, 0, 0);
        b.setContentDescription(desc);   // default text metrics, exactly like the main screen's buttons
        b.setOnClickListener(click);
        return b;
    }

    private void bindTitle() {
        if (tpl == null || nameView == null) return;
        nameView.setText(L.s("wmTitle"));     // the template name already lives in the dropdown
    }

    private boolean isWhite() { return Color.red(tpl.bg) > 128; }

    /** photo / image: the only types with a crop viewport and edge treatment */
    private static boolean isBox(String t) { return "photo".equals(t) || "img".equals(t); }

    private void refreshTools() {
        if (canvas != null) canvas.invalidate();   // selection chrome lives on the canvas now
    }

    private void bindSnapBtn() {
        if (snapBtn != null) snapBtn.setText(snap ? "\u25A6\u2713" : "\u25A6\u2717");
    }


    private void setDirty(boolean d) {
        dirty = d;   // only drives the "save changes?" prompt; the title stays clean
    }

    private void toggleBg() {
        tpl.bg = isWhite() ? 0xFF000000 : 0xFFFFFFFF;
        canvas.invalidate();
        bindTitle();
        setDirty(true);
    }

    /** the card already matches the photo's frame (3:2, photo filling it) -> nothing to reset */
    private boolean needsSizeReset() {
        if (tpl == null) return false;
        if (Math.abs(tpl.ar - 1.5f) > 0.005f) return true;
        for (Watermark.El e : tpl.els) {
            if (!"photo".equals(e.type)) continue;
            return Math.abs(e.x) > 0.005f || Math.abs(e.y) > 0.005f
                    || Math.abs(e.w - 1f) > 0.005f || Math.abs(e.h - 1f) > 0.005f;
        }
        return false;
    }

    /** back to the photo's own frame: 3:2 card, photo filling it edge to edge */
    private void resetSize() {
        Watermark.El p = null;
        for (Watermark.El e : tpl.els) if ("photo".equals(e.type)) { p = e; break; }
        if (p == null) return;
        tpl.ar = 1.5f;
        p.x = 0f;
        p.y = 0f;
        p.w = 1f;
        p.h = 1f;
        p.ratio = "3:2";
        canvas.computeCard();
        canvas.invalidate();
        setDirty(true);
    }

    private void toggleSnap() {
        snap = !snap;
        getSharedPreferences("of6k", 0).edit().putBoolean("wm_snap", snap).commit();
        bindSnapBtn();
        canvas.invalidate();
    }

    /** ⟳: drop unsaved edits and reload the current template from disk (same idea as the main page) */
    private void reloadNow() {
        if (tpl == null) return;
        Watermark.Tpl t = Watermark.load(tpl.id);
        if (t == null) { MainActivity.toast(L.s("wmLoadFail")); return; }
        tpl = t;
        canvas.setTemplate(tpl);
        dirty = false;
        bindTitle();
        MainActivity.toast(tpl.name + " " + L.s("loaded"));
    }

    // ------------------------------------------------------------------ save / exit

    private void saveNow() {
        if (!validate()) return;
        Bitmap prev = canvas.exportCard(480);
        boolean ok = Watermark.save(tpl, prev);
        if (prev != null) prev.recycle();
        if (ok) {
            getSharedPreferences("of6k", 0).edit().putString("wm_last", tpl.id).commit();
            dirty = false;
            bindTitle();                      // keep the localised title (was overwritten with the template name)
            MainActivity.toast(tpl.name + " " + L.s("wmSaved"));
        } else {
            MainActivity.toast(L.s("installFail"));
        }
    }

    private boolean validate() {
        int photos = 0;
        for (Watermark.El e : tpl.els) if ("photo".equals(e.type)) photos++;
        if (photos < 1) {
            MainActivity.toast(L.s("wmPhotoKeep"));
            return false;
        }
        return true;
    }

    private void confirmExit() {
        if (!dirty) { finish(); return; }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ux.dp(10), Ux.dp(8), Ux.dp(10), Ux.dp(4));
        TextView m = new TextView(this);
        m.setText(L.s("wmSaveAsk"));
        m.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY));
        m.setTextColor(Ux.TXT);
        m.setPadding(0, 0, 0, Ux.dp(8));
        body.addView(m);
        final Dialog[] dh = new Dialog[1];
        LinearLayout btns = new LinearLayout(this);
        Button cancel = new Button(this);
        cancel.setText(L.s("btnCancel"));
        Ux.styleButton(cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dh[0] != null) dh[0].dismiss(); }
        });
        Button discard = new Button(this);
        discard.setText(L.s("wmDiscard"));
        Ux.styleButton(discard);
        discard.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dh[0] != null) dh[0].dismiss(); finish(); }
        });
        Button save = new Button(this);
        save.setText(L.s("saveCfg"));
        Ux.styleButton(save);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dh[0] != null) dh[0].dismiss(); saveNow(); finish(); }
        });
        btns.addView(cancel);
        btns.addView(discard);
        btns.addView(save);
        dh[0] = MainActivity.winDlgX(this, L.s("wmExitTitle"), dialogWrap(body, btns));
        sizeDialog(dh[0]);
        dh[0].show();
    }

    // ------------------------------------------------------------------ win98 widgets

    /** group box: etched frame with the header sitting on the border (Luma/Spy dialog look) */
    private FrameLayout group(String header, LinearLayout content) {
        FrameLayout g = new FrameLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackground(new Ux.Etched());
        content.setPadding(Ux.dp(8), Ux.dp(14), Ux.dp(8), Ux.dp(8));
        TextView h = new TextView(this);
        h.setText(header);
        Ux.styleHeader(h);
        g.setClipChildren(false);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(-1, -2);
        il.topMargin = Ux.dp(10);
        g.addView(content, il);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2);
        hl.leftMargin = Ux.dp(10);
        hl.topMargin = Ux.dp(10) - Ux.dp(1) - (int) (Ux.BODY * 0.5f * Ux.tscale());
        g.addView(h, hl);
        return g;
    }

    private TextView lab(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY));
        t.setTextColor(Ux.TXT);
        t.setPadding(0, Ux.dp(4), 0, Ux.dp(2));
        return t;
    }

    /** labelled row: − [slider] + value — the same shape as the editor's parameter rows */
    /** one shadow-offset unit: the slider reads -1..+1, 1.0 = 3% of the card's short side */
    private static final float OFF_UNIT = 0.03f;
    /** one shadow-blur unit: the slider reads 0..1, 1.0 = 8% of the card's short side */
    private static final float BLUR_UNIT = 0.08f;

    private final class Num {
        static final int PLAIN = 0, JAG = 1, SOFT = 2;
        final SeekBar sb;
        final TextView tv;
        final float min, step;
        final String fmt;
        int kind = PLAIN;            // JAG: "x2.35"; SOFT: "4.4%" of the card's short side
        Num(SeekBar s, TextView t, float mn, float st, String f) { sb = s; tv = t; min = mn; step = st; fmt = f; }
        float value() { return min + sb.getProgress() * step; }
        void show() {
            float v = value();
            if (kind == JAG) tv.setText(String.format(Locale.US, "\u00D7%.2f", v));
            else if (kind == SOFT) tv.setText(String.format(Locale.US, "%.1f%%", v * 2.0f));
            else tv.setText(String.format(Locale.US, fmt, v));
        }
    }

    private Num num(LinearLayout parent, String name, float min, float max, float step, float val, String fmt) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(lab(name), new LinearLayout.LayoutParams(Ux.dp(80), -2));
        SeekBar sb = new SeekBar(this);
        sb.setMax(Math.max(1, Math.round((max - min) / step)));
        sb.setThumb(Ux.thumb98());
        sb.setProgress(Math.max(0, Math.min(sb.getMax(), Math.round((val - min) / step))));
        Button minus = new Button(this);
        minus.setText("\u2212");
        minus.setMinWidth(0); minus.setMinimumWidth(0); minus.setPadding(0, 0, 0, 0);
        row.addView(minus, new LinearLayout.LayoutParams(Ux.dp(34), Ux.dp(34)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, Ux.dp(34), 1f);
        slp.leftMargin = Ux.dp(6);
        row.addView(sb, slp);
        Button plus = new Button(this);
        plus.setText("+");
        plus.setMinWidth(0); plus.setMinimumWidth(0); plus.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(Ux.dp(34), Ux.dp(34));
        plp.leftMargin = Ux.dp(6);
        row.addView(plus, plp);
        TextView tv = new TextView(this);
        tv.setTypeface(Ux.seg(this));
        tv.setLetterSpacing(0.25f);
        tv.getPaint().setFakeBoldText(true);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(13));
        tv.setGravity(Gravity.RIGHT);
        tv.setSingleLine(true);            // values like 0.0040 must never wrap
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, -2);   // wrap: "72 pt" must not clip
        vlp.leftMargin = Ux.dp(6);
        tv.setMinWidth(Ux.dp(56));
        row.addView(tv, vlp);
        final Num n = new Num(sb, tv, min, step, fmt);
        n.show();
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s2, int p, boolean u) { n.show(); }
            public void onStartTrackingTouch(SeekBar s2) {}
            public void onStopTrackingTouch(SeekBar s2) {}
        });
        minus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { n.sb.setProgress(Math.max(0, n.sb.getProgress() - 1)); }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { n.sb.setProgress(Math.min(n.sb.getMax(), n.sb.getProgress() + 1)); }
        });
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return n;
    }

    /** the ✓ mark drawn as a path (white tick with a black outline): themeTree cannot recolour it */
    private final class Tick extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Tick(android.content.Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(w * 0.20f, h * 0.52f);
            path.lineTo(w * 0.42f, h * 0.74f);
            path.lineTo(w * 0.82f, h * 0.26f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.SQUARE);
            p.setStrokeJoin(Paint.Join.MITER);
            p.setStrokeWidth(w * 0.22f);
            p.setColor(0xFF000000);
            cv.drawPath(path, p);
            p.setStrokeWidth(w * 0.14f);
            p.setColor(0xFFFFFFFF);
            cv.drawPath(path, p);
        }
    }

    /** the palette entry closest to a colour: custom colours (1A1A1A, F2F2F2…) still light up a well */
    private static int nearestChip(int argb) {
        int best = PALETTE[0];
        long bd = Long.MAX_VALUE;
        for (int c : PALETTE) {
            long d = (long) (Color.red(c) - Color.red(argb)) * (Color.red(c) - Color.red(argb))
                    + (long) (Color.green(c) - Color.green(argb)) * (Color.green(c) - Color.green(argb))
                    + (long) (Color.blue(c) - Color.blue(argb)) * (Color.blue(c) - Color.blue(argb));
            if (d < bd) { bd = d; best = c; }
        }
        return best & 0x00FFFFFF;
    }

    /** win98 colour wells: every chip is a sunken 3D well; the CURRENT colour carries a ✓ */
    private LinearLayout palette(final int[] out) {
        LinearLayout row = new LinearLayout(this);
        final java.util.List<View> ticks = new ArrayList<View>();
        for (final int c : PALETTE) {
            FrameLayout chip = new FrameLayout(this);
            chip.setBackground(Ux.sunken());                 // all wells are recessed
            View fill = new View(this);
            fill.setBackgroundColor(c);
            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-1, -1);
            flp.setMargins(Ux.dp(3), Ux.dp(3), Ux.dp(3), Ux.dp(3));
            chip.addView(fill, flp);
            View tick = new Tick(this);                       // just the ✓ mark (no checkbox frame)
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(Ux.dp(24), Ux.dp(24), Gravity.CENTER);
            chip.addView(tick, tlp);
            tick.setVisibility((c & 0x00FFFFFF) == nearestChip(out[0]) ? View.VISIBLE : View.INVISIBLE);
            ticks.add(tick);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View view) {
                    out[0] = (c & 0x00FFFFFF) | (out[0] & 0xFF000000);
                    for (int i = 0; i < ticks.size(); i++)
                        ticks.get(i).setVisibility((PALETTE[i] & 0x00FFFFFF) == nearestChip(out[0]) ? View.VISIBLE : View.INVISIBLE);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ux.dp(26), 1f);
            row.addView(chip, lp);
        }
        return row;
    }

    private LinearLayout choiceRow(String[] labels, final String[] values, final String[] out) {
        LinearLayout row = new LinearLayout(this);
        final List<Button> btns = new ArrayList<Button>();
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setMinWidth(0); b.setMinimumWidth(0);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(12));
            Ux.styleButton(b);
            b.setSelected(values[i].equals(out[0]));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    out[0] = values[k];
                    for (int j = 0; j < btns.size(); j++) btns.get(j).setSelected(j == k);
                }
            });
            btns.add(b);
            row.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return row;
    }

    private LinearLayout checkRow(String text, final boolean[] out) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText(text);
        cb.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY));
        cb.setTextColor(Ux.TXT);
        cb.setButtonDrawable(Ux.checkbox98States(this));
        cb.setChecked(out[0]);
        cb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { out[0] = cb.isChecked(); }
        });
        row.addView(cb);
        return row;
    }

    /** buttons row helper: equal-width 98 buttons */
    private LinearLayout btnRow(String[] labels, View.OnClickListener[] actions) {
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setMinWidth(0); b.setMinimumWidth(0);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(12));
            Ux.styleButton(b);
            b.setOnClickListener(actions[i]);
            row.addView(b, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return row;
    }

    private Button okButton(final Dialog[] holder) {
        Button ok = new Button(this);
        ok.setText(L.s("btnOk"));
        Ux.styleButton(ok);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (holder[0] != null) holder[0].dismiss(); }
        });
        return ok;
    }

    /** size a win98 tool dialog like the app's own dialogs: 420dp wide, height wraps content */
    private void sizeDialog(Dialog d) {
        if (d == null) return;
        android.view.Window w = d.getWindow();
        if (w == null) return;
        w.getDecorView().setPadding(0, 0, 0, 0);
        w.setBackgroundDrawable(Ux.frame98());
        w.setLayout(Math.min(Ux.dp(420), getResources().getDisplayMetrics().widthPixels - Ux.dp(24)), -2);
    }

    private LinearLayout dialogWrap(View body, View buttons) { return dialogWrap(body, buttons, 0); }

    /** wider variant for short dialogs: without it the window hugs a narrow message and every control
     *  inside looks oversized */
    private LinearLayout dialogWrap(View body, View buttons, int minWidthDp) {
        Ux.themeTree(body);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        ScrollView sc = body instanceof ScrollView ? (ScrollView) body : null;
        if (sc != null) {
            int cap = Math.min(Ux.dp(420), (int) (getResources().getDisplayMetrics().heightPixels * 0.60f));
            int w = Math.min(Ux.dp(420), getResources().getDisplayMetrics().widthPixels - Ux.dp(24));
            // snug: a short body must not leave a big empty area, a tall one scrolls
            body.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.AT_MOST),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
            int h = Math.min(cap, Math.max(Ux.dp(60), body.getMeasuredHeight()));
            wrap.addView(sc, new LinearLayout.LayoutParams(-1, h));
        } else wrap.addView(body);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.RIGHT);
        r.setPadding(Ux.dp(8), Ux.dp(6), Ux.dp(8), Ux.dp(8));
        if (buttons != null) {                       // 34dp button slots, like the app's other dialogs
            if (buttons instanceof android.view.ViewGroup) {
                android.view.ViewGroup bg = (android.view.ViewGroup) buttons;
                for (int i = 0; i < bg.getChildCount(); i++) {
                    android.view.View c = bg.getChildAt(i);
                    android.view.ViewGroup.LayoutParams lp = c.getLayoutParams();
                    if (lp instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) lp).height = Ux.dp(34);
                    else c.setLayoutParams(new LinearLayout.LayoutParams(-2, Ux.dp(34)));
                }
            }
            r.addView(buttons);
        }
        wrap.addView(r);
        if (minWidthDp > 0) wrap.setMinimumWidth(Ux.dp(minWidthDp));
        return wrap;
    }

    // ------------------------------------------------------------------ add elements


    private boolean hasPhoto() {
        for (Watermark.El e : tpl.els) if ("photo".equals(e.type)) return true;
        return false;
    }

    private void addPhoto() {
        Watermark.El e = new Watermark.El();
        e.type = "photo";
        e.w = 0.60f;
        e.h = Math.min(0.85f, e.w * tpl.ar / 1.5f);
        e.ratio = "3:2";
        Watermark.fitPhoto(e, tpl.ar);
        tpl.els.add(e);                    // on top: visible and selected right away
        canvas.setTemplate(tpl);
        placeAtViewCenter(e);
        canvas.select(tpl.els.size() - 1);
        canvas.invalidate();
        setDirty(true);
    }

    /** a thin rule (2pt): the direction lives in `dir`, the box's thin side is the thickness */
    /** new elements land in the middle of the current preview viewport (not the card centre) */
    private void placeAtViewCenter(Watermark.El e) {
        if (canvas == null) return;
        float[] c = canvas.viewCenterCard();
        e.x = Math.max(0f, Math.min(1f - e.w, c[0] - e.w / 2f));
        e.y = Math.max(0f, Math.min(1f - e.h, c[1] - e.h / 2f));
    }

    private void addLine(boolean horizontal) {
        Watermark.El e = new Watermark.El();
        e.type = "line";
        e.dir = horizontal ? "h" : "v";
        float th = 2f / PT_PER_CARD;
        if (horizontal) { e.w = 0.70f; e.h = th; }
        else            { e.w = th;     e.h = 0.60f; }
        e.color = isWhite() ? 0xFF000000 : 0xFFFFFFFF;   // exact palette entries -> the well shows a tick
        tpl.els.add(e);
        canvas.setTemplate(tpl);
        placeAtViewCenter(e);
        canvas.invalidate();
        canvas.select(tpl.els.size() - 1);
        setDirty(true);
    }

    /** plain filled rectangle: 8 resize handles, colour + opacity + blend + shadow */
    private void addRect() {
        Watermark.El e = new Watermark.El();
        e.type = "rect";
        e.w = 0.30f; e.h = 0.20f;
        e.color = isWhite() ? 0xFF1A1A1A : 0xFFF2F2F2;
        e.blend = 1;
        e.shOn = !isWhite();
        e.shColor = isWhite() ? 0x40000000 : 0x40FFFFFF;
        e.shBlur = 0.012f;
        e.shDy = 0.004f;
        tpl.els.add(e);
        canvas.setTemplate(tpl);
        placeAtViewCenter(e);
        canvas.invalidate();
        canvas.select(tpl.els.size() - 1);
        setDirty(true);
    }

    /** brand logo: the picture comes from the photo's EXIF at render time, never from the template */
    private void addLogo() {
        Watermark.El e = new Watermark.El();
        e.type = "logo";
        e.w = 0.30f; e.h = 0.10f;
        e.opacity = 1f;
        e.align = "center";
        tpl.els.add(e);
        canvas.setTemplate(tpl);
        placeAtViewCenter(e);
        canvas.invalidate();
        canvas.select(tpl.els.size() - 1);
        setDirty(true);
        logoPrompt();                       // every add: remind that the logos must be downloaded
    }

    private Dialog logoPromptDlg;                          // one prompt at a time

    /** while NOTHING is downloaded: every editor entry and every logo add reminds */
    private void logoPrompt() {
        if (Logo.have() > 0) return;                       // something is on disk: never nag
        if (logoPromptDlg != null && logoPromptDlg.isShowing()) return;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ux.dp(10), Ux.dp(8), Ux.dp(10), Ux.dp(4));
        TextView m = new TextView(this);
        m.setText(L.s("wmLogoMsgNeed"));
        m.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY - 1));
        m.setTextColor(Ux.TXT);
        m.setPadding(0, 0, 0, Ux.dp(8));
        body.addView(m);
        final Dialog[] dh = new Dialog[1];
        LinearLayout btns = new LinearLayout(this);
        Button later = new Button(this);
        later.setText(L.s("wmLogoLater"));
        Ux.styleButton(later);
        later.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dh[0] != null) dh[0].dismiss(); }
        });
        Button dl = new Button(this);
        dl.setText(L.s("wmLogoDownload"));
        Ux.styleButton(dl);
        dl.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dh[0] != null) dh[0].dismiss(); logoDownload(); }
        });
        btns.addView(later);
        btns.addView(dl);
        dh[0] = MainActivity.winDlgX(this, L.s("wmLogoTitle"), dialogWrap(body, btns, 400));
        sizeDialog(dh[0]);
        logoPromptDlg = dh[0];
        dh[0].show();
    }

    /** user-initiated download from Wikimedia Commons (nothing brand-related ships in the APK) */
    /** logo download: same progress block as the Rewind / Luma dialogs (etched group + 98 progress bar) */
    private void logoDownload() {
        final int total = Logo.BRANDS.length;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ux.dp(10), Ux.dp(8), Ux.dp(10), Ux.dp(8));

        final TextView msg = new TextView(this);
        msg.setText(L.s("wmLogoDownloading"));
        msg.setTextColor(Ux.TXT);
        msg.setTypeface(android.graphics.Typeface.MONOSPACE);
        msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY - 1));
        msg.setSingleLine(true);
        msg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(msg, new LinearLayout.LayoutParams(-1, -2));
        final Ux.Progress98 bar = new Ux.Progress98(this);
        bar.setMax(total);
        bar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, Ux.dp(18));
        blp.topMargin = Ux.dp(8);
        body.addView(bar, blp);
        final TextView cnt = new TextView(this);
        cnt.setTextColor(Ux.TXT);
        cnt.setTypeface(android.graphics.Typeface.MONOSPACE);
        cnt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY - 2));
        cnt.setSingleLine(true);
        cnt.setText("0 / " + total);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Ux.dp(4);
        body.addView(cnt, clp);

        final Dialog d = MainActivity.winDlgX(this, L.s("wmLogoTitle"), dialogWrap(body, null));
        sizeDialog(d);
        d.show();
        new Thread(new Runnable() {
            public void run() {
                final int n = Logo.downloadAll(new Logo.Cb() {
                    public void onProgress(final int done, final int t) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                try { bar.setProgress(done); cnt.setText(done + " / " + t); }
                                catch (Throwable ig) {}
                            }
                        });
                    }
                });
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { d.dismiss(); } catch (Throwable ig) {}
                        Logo.clearCache();                 // whatever was drawn while files were missing
                        MainActivity.toast(n > 0 ? n + " " + L.s("wmLogoDone") : L.s("wmLogoFail"));
                        canvas.invalidate();
                    }
                });
            }
        }).start();
    }

    private void addText(String str) {
        Watermark.El e = new Watermark.El();
        e.type = "text";
        e.text = str;
        e.size = str.startsWith("{") ? 0.035f : 0.055f;
        e.color = isWhite() ? 0xFF1A1A1A : 0xFFF2F2F2;
        e.align = "left";
        e.bold = !str.startsWith("{");
        e.shOn = !isWhite();
        e.shColor = isWhite() ? 0x40000000 : 0x40FFFFFF;
        e.shBlur = 0.008f;
        e.shDy = 0.003f;
        e.w = 0.05f; e.h = 0.04f;
        tpl.els.add(e);
        canvas.setTemplate(tpl);   // refits the box to its glyphs first
        placeAtViewCenter(e);
        canvas.invalidate();
        canvas.select(tpl.els.size() - 1);
        setDirty(true);
    }

    private static float[] aabb(Watermark.El e) {
        return new float[]{e.x, e.y, e.x + e.w, e.y + e.h};
    }

    private static boolean overlap(float[] a, float[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    /** move ONE layer, stepping over the nearest overlapping element; falls back to top/bottom */
    private void zorder(boolean front) {
        int i = canvas.selected();
        if (i < 0) return;
        java.util.List<Watermark.El> els = tpl.els;
        Watermark.El e = els.get(i);
        float[] a = aabb(e);
        int target = -1;
        if (front) {
            for (int j = i + 1; j < els.size(); j++) if (overlap(a, aabb(els.get(j)))) { target = j; break; }
        } else {
            for (int j = i - 1; j >= 0; j--) if (overlap(a, aabb(els.get(j)))) { target = j; break; }
        }
        els.remove(i);
        if (target < 0) {
            if (front) els.add(e); else els.add(0, e);
            target = front ? els.size() - 1 : 0;
        } else {
            els.add(target, e);
        }
        canvas.setTemplate(tpl);
        canvas.select(target);
        setDirty(true);
    }

    private void deleteSel() {
        int i = canvas.selected();
        if (i < 0) return;
        Watermark.El e = tpl.els.get(i);
        if ("photo".equals(e.type)) {
            int photos = 0;
            for (Watermark.El q : tpl.els) if ("photo".equals(q.type)) photos++;
            if (photos <= 1) { MainActivity.toast(L.s("wmPhotoKeep")); return; }   // never end up with none
        }
        tpl.els.remove(i);
        canvas.setTemplate(tpl);
        setDirty(true);
    }

    private void pickImage() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("image/*");
        try { startActivityForResult(it, REQ_IMG); }
        catch (Throwable e) { MainActivity.toast("No picker: " + e); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMG || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            in.close();
            byte[] bytes = bo.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (o.outWidth <= 0) { MainActivity.toast("Unreadable image"); return; }
            int sc = 1;
            while (o.outWidth / (sc * 2) >= 2048 || o.outHeight / (sc * 2) >= 2048) sc *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sc;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
            if (bm == null) { MainActivity.toast("Unreadable image"); return; }
            File dir = Watermark.dir(tpl.id);
            dir.mkdirs();
            String name = "img_" + System.currentTimeMillis() + ".png";
            FileOutputStream fo = new FileOutputStream(new File(dir, name));
            bm.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            int iw = bm.getWidth(), ih = bm.getHeight();
            bm.recycle();

            Watermark.El e = new Watermark.El();
            e.type = "img";
            e.src = name;
            e.w = Math.min(0.30f, 0.9f);
            e.h = e.w * tpl.ar * ih / (float) iw;
            if (e.h > 0.5f) { e.h = 0.5f; e.w = e.h * iw / (float) ih / tpl.ar; }
            e.shOn = !isWhite();
            e.shColor = isWhite() ? 0x66000000 : 0x44FFFFFF;
            e.shBlur = 0.012f;
            e.shDy = 0.005f;
            tpl.els.add(e);
            canvas.setTemplate(tpl);
            placeAtViewCenter(e);
            canvas.invalidate();
            canvas.select(tpl.els.size() - 1);
            setDirty(true);
        } catch (Throwable ex) {
            MainActivity.toast("Import failed: " + ex);
        }
    }

    // ------------------------------------------------------------------ reference image

    private java.util.List<java.io.File> refFiles() {
        java.util.ArrayList<java.io.File> out = new java.util.ArrayList<java.io.File>();
        try {
            java.io.File cs = new java.io.File("/sdcard/OpenFilm6K/custom/scenes");
            java.io.File[] cf = cs.listFiles();
            java.util.ArrayList<java.io.File> custom = new java.util.ArrayList<java.io.File>();
            if (cf != null) for (java.io.File f : cf) {
                String cn = f.getName();
                if (!cn.startsWith(".") && cn.toLowerCase(Locale.US).endsWith(".jpg")) custom.add(f);   // skip temp artifacts
            }
            java.util.Collections.sort(custom, new java.util.Comparator<java.io.File>() {
                public int compare(java.io.File a, java.io.File b) {
                    return a.lastModified() == b.lastModified() ? a.getName().compareTo(b.getName())
                            : (a.lastModified() > b.lastModified() ? -1 : 1);
                }
            });
            out.addAll(custom);
            java.io.File root = new java.io.File("/sdcard/OpenFilm6K");
            java.io.File[] rf = root.listFiles();
            java.util.TreeSet<java.io.File> sys = new java.util.TreeSet<java.io.File>(new java.util.Comparator<java.io.File>() {
                public int compare(java.io.File a, java.io.File b) { return a.getName().compareTo(b.getName()); }
            });
            if (rf != null) for (java.io.File f : rf) {
                String n = f.getName();
                if (n.startsWith("preview_") && n.endsWith(".jpg")) sys.add(f);
            }
            out.addAll(sys);
        } catch (Throwable ig) {}
        return out;
    }

    private void chooseRef(String path, boolean on) {
        getSharedPreferences("of6k", 0).edit()
                .putBoolean("wm_preview_on", on)
                .putString("wm_preview_ref", path == null ? "" : path)
                .commit();
        showPreview = on;
        canvas.resetPreview();
        canvas.invalidate();
    }

    // ------------------------------------------------------------------ templates

    /** "new watermark", mirroring the app's new-film dialog: name + blank/copy + Create/Cancel */
    private void showNewTemplate() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ux.dp(10), Ux.dp(6), Ux.dp(10), Ux.dp(8));

        LinearLayout g1 = new LinearLayout(this);
        final EditText et = new EditText(this);
        et.setHint(L.s("wmTplNameHint"));
        et.setSingleLine(true);
        et.setBackground(Ux.sunkenField());
        et.setPadding(Ux.dp(8), Ux.dp(8), Ux.dp(8), Ux.dp(8));
        et.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY));
        g1.addView(et, new LinearLayout.LayoutParams(-1, -2));
        body.addView(group(L.s("wmTplName"), g1));

        LinearLayout g2 = new LinearLayout(this);
        final boolean[] copyMode = {true};                  // default: copy the current watermark
        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        rg.setOrientation(LinearLayout.VERTICAL);
        android.widget.RadioButton rbBlank = new android.widget.RadioButton(this);
        rbBlank.setText(L.s("wmTplBlank"));
        rbBlank.setPadding(Ux.dp(8), Ux.dp(6), Ux.dp(8), Ux.dp(6));
        android.widget.RadioButton rbCopy = new android.widget.RadioButton(this);
        rbCopy.setText(L.s("wmTplCopy") + (tpl == null ? "" : " (" + tpl.name + ")"));
        rbCopy.setPadding(Ux.dp(8), Ux.dp(6), Ux.dp(8), Ux.dp(6));
        rg.addView(rbBlank);
        rg.addView(rbCopy);
        rg.check(rbCopy.getId());
        rg.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.RadioGroup g, int id) { copyMode[0] = (id == rbCopy.getId()); }
        });
        g2.addView(rg, new LinearLayout.LayoutParams(-1, -2));
        body.addView(group(L.s("createMode"), g2));

        ScrollView sc = new ScrollView(this);
        sc.addView(body);
        final Dialog[] dh = new Dialog[1];

        Button ok = new Button(this);
        ok.setText(L.s("create"));
        Ux.styleButton(ok);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String nm = et.getText().toString().trim().replaceAll("[/\\\\]", "_");
                if (nm.isEmpty()) { MainActivity.toast(L.s("invalidName")); return; }
                for (String id : Watermark.ids()) {
                    Watermark.Tpl t = Watermark.load(id);
                    if (t != null && t.name.equals(nm)) { MainActivity.toast(L.s("exists") + nm); return; }
                }
                Watermark.Tpl nt;
                if (copyMode[0] && tpl != null) nt = Watermark.duplicate(tpl, nm);
                else {
                    nt = Watermark.create(nm);
                    Watermark.save(nt, null);
                }
                tpl = nt;
                canvas.setTemplate(tpl);
                bindTitle();
                refreshTplSpin();
                dirty = false;
                if (dh[0] != null) dh[0].dismiss();
            }
        });
        Button cancel = new Button(this);
        cancel.setText(L.s("btnCancel"));
        Ux.styleButton(cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dh[0] != null) dh[0].dismiss(); }
        });
        LinearLayout btns = new LinearLayout(this);
        btns.addView(cancel);
        btns.addView(ok);
        dh[0] = MainActivity.winDlgX(this, L.s("wmNewTpl"), dialogWrap(sc, btns));
        sizeDialog(dh[0]);
        dh[0].show();
        et.requestFocus();
    }

    /** delete the current watermark after a confirmation (win98 window) */
    private void deleteCurrentTemplate() {
        if (tpl == null) return;
        if (Watermark.ids().size() <= 1) { MainActivity.toast(L.s("noFilmToDelete")); return; }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ux.dp(10), Ux.dp(8), Ux.dp(10), Ux.dp(4));
        TextView m = new TextView(this);
        m.setText(L.s("confirmDel") + tpl.name + "\"");
        m.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY));
        m.setTextColor(Ux.TXT);
        m.setPadding(0, 0, 0, Ux.dp(8));
        body.addView(m);
        final Dialog[] dh = new Dialog[1];
        LinearLayout btns = new LinearLayout(this);
        Button cancel = new Button(this);
        cancel.setText(L.s("btnCancel"));
        Ux.styleButton(cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dh[0] != null) dh[0].dismiss(); }
        });
        Button del = new Button(this);
        del.setText(L.s("wmDelete"));
        Ux.styleButton(del);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (dh[0] != null) dh[0].dismiss();
                File dir = Watermark.dir(tpl.id);
                File[] fs = dir.listFiles();
                if (fs != null) for (File f : fs) f.delete();
                dir.delete();
                tpl = newest();
                canvas.setTemplate(tpl);
                bindTitle();
                refreshTplSpin();
                dirty = false;
            }
        });
        btns.addView(cancel);
        btns.addView(del);
        dh[0] = MainActivity.winDlgX(this, L.s("wmDelTpl"), dialogWrap(body, btns));
        sizeDialog(dh[0]);
        dh[0].show();
    }

    // ------------------------------------------------------------------ element properties

    private void showProps() {
        final int idx = canvas.selected();
        if (idx < 0) return;
        final Watermark.El el = tpl.els.get(idx);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ux.dp(10), Ux.dp(6), Ux.dp(10), Ux.dp(8));

        final EditText[] et = {null};
        final int[] color = {el.color};
        final String[] align = {el.align};
        final boolean[] bold = {el.bold};
        final boolean[] shOn = {el.shOn};
        final int[] shColor = {el.shColor};
        final String[] edge = {el.edge};
        Num size = null, edgeSize = null, opacity = null, thickness = null, shOp = null, shBlur = null, shDx = null, shDy = null;
        Num txtAlpha = null;

        final int[] dirSel = {0};
        final int[] brandSel = {0};
        final int[] fontSel = {0};
        final int[] alignSel = {1};
        final int[] blendSel = {Math.max(0, Math.min(2, el.blend))};
        for (int i = 0; i < Watermark.FONT_IDS.length; i++)
            if (Watermark.FONT_IDS[i].equals(el.font)) fontSel[0] = i;
        for (int i = 0; i < ALIGN_IDS.length; i++)
            if (ALIGN_IDS[i].equals(el.align)) alignSel[0] = i;

        if ("text".equals(el.type)) {
            LinearLayout g1 = new LinearLayout(this);
            LinearLayout mrow = new LinearLayout(this);
            mrow.setGravity(Gravity.CENTER_VERTICAL);
            final int[] macroSel = {0};
            mrow.addView(combo(macroLabels(), 0, macroSel), new LinearLayout.LayoutParams(0, Ux.dp(38), 1f));
            final Button ins = new Button(this);
            ins.setText("+");
            ins.setMinWidth(0); ins.setMinimumWidth(0); ins.setPadding(0, 0, 0, 0);
            ins.setContentDescription(L.s("wmInsert"));
            ins.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(18));
            Ux.styleButton(ins);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ux.dp(34), Ux.dp(34));
            ilp.leftMargin = Ux.dp(6);
            mrow.addView(ins, ilp);
            g1.addView(mrow, new LinearLayout.LayoutParams(-1, -2));

            et[0] = new EditText(this);
            et[0].setText(el.text);
            et[0].setHint(L.s("wmTextHint"));
            et[0].setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(Ux.BODY));
            et[0].setTextColor(Ux.TXT);
            et[0].setBackground(Ux.sunkenField());          // win98 sunken input, same as the name dialogs
            et[0].setPadding(Ux.dp(8), Ux.dp(6), Ux.dp(8), Ux.dp(6));
            et[0].setMinLines(2);
            et[0].setMaxLines(4);
            g1.addView(et[0], new LinearLayout.LayoutParams(-1, -2));
            body.addView(group(L.s("wmGText"), g1));
            ins.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {                 // append space + the picked macro
                    int i = macroSel[0];
                    if (i < 0 || i >= MACRO_TOKENS.length) return;
                    String cur = et[0].getText().toString();
                    if (cur.length() > 0 && !cur.endsWith(" ") && !cur.endsWith("\n")) cur += " ";
                    cur += MACRO_TOKENS[i];
                    et[0].setText(cur);
                    et[0].setSelection(cur.length());
                }
            });

            LinearLayout g2 = new LinearLayout(this);
            g2.addView(lab(L.s("wmFont")));
            g2.addView(fontCombo(fontSel[0], fontSel), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            size = num(g2, L.s("wmSizePt"), 4f, 72f, 1f, el.size * PT_PER_CARD, "%.0f");   // the seg readout has no letters
            g2.addView(lab(L.s("wmColor")));
            g2.addView(palette(color));
            g2.addView(lab(L.s("wmBlend")));
            g2.addView(combo(blendLabels(), blendSel[0], blendSel), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            txtAlpha = num(g2, L.s("wmOpacity"), 0f, 100f, 5f, el.alpha * 100f, "%.0f");
            LinearLayout arow = new LinearLayout(this);
            arow.setGravity(Gravity.CENTER_VERTICAL);
            arow.addView(combo(alignLabels(), alignSel[0], alignSel), new LinearLayout.LayoutParams(0, Ux.dp(38), 1f));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = Ux.dp(10);
            arow.addView(checkRow(L.s("wmBold"), bold), blp);
            g2.addView(arow, new LinearLayout.LayoutParams(-1, -2));
            body.addView(group(L.s("wmGType"), g2));
        } else if ("photo".equals(el.type)) {
            LinearLayout g1 = new LinearLayout(this);
            g1.addView(lab(L.s("wmEdge")));
            final Spinner esp = new Spinner(this);
            ArrayList<String> en = new ArrayList<String>();
            en.add(L.s("wmEdgeSoft"));
            en.add(L.s("wmEdgeJagged"));
            EditorActivity.StdSpinnerAdapter ea = new EditorActivity.StdSpinnerAdapter(this, en);
            ea.spin = esp;
            esp.setAdapter(ea);
            esp.setSelection("jagged".equals(edge[0]) ? 1 : 0);
            g1.addView(Ux.wrapSpinner(this, esp), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            edgeSize = num(g1, L.s("wmEdgeSize"), 0f, 5f, 0.05f, el.edgeSize, "%.2f");
            edgeSize.kind = "jagged".equals(edge[0]) ? Num.JAG : Num.SOFT;
            edgeSize.show();
            final Num sz = edgeSize;
            esp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    edge[0] = pos == 1 ? "jagged" : "soft";
                    sz.kind = pos == 1 ? Num.JAG : Num.SOFT;      // x2.00 (jagged) vs 0.2% of the short side
                    sz.sb.setProgress(pos == 1 ? Math.round(1f / 0.05f) : Math.round(0.1f / 0.05f));   // mode default
                    sz.show();
                }
                public void onNothingSelected(AdapterView<?> p) {}
            });
            body.addView(group(L.s("wmGPhoto"), g1));
        } else if ("logo".equals(el.type)) {
            LinearLayout g1 = new LinearLayout(this);
            g1.addView(lab(L.s("wmLogoBrand")));
            ArrayList<String> bl = new ArrayList<String>();
            bl.add(L.s("wmLogoFollow"));
            for (String[] b : Logo.BRANDS) bl.add(Logo.label(b[0]));
            int bsel0 = 0;
            for (int i = 0; i < Logo.BRANDS.length; i++)
                if (Logo.BRANDS[i][0].equals(el.brand)) bsel0 = i + 1;
            g1.addView(combo(bl, bsel0, brandSel), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            g1.addView(lab(L.s("wmBlend")));
            g1.addView(combo(blendLabels(), blendSel[0], blendSel), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            opacity = num(g1, L.s("wmOpacity"), 0f, 100f, 1f, el.opacity * 100f, "%.0f");
            g1.addView(lab(L.s("wmAlign")));
            g1.addView(combo(alignLabels(), alignSel[0], alignSel), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            g1.addView(lab(L.s("wmLogoHint")));
            Button dl = new Button(this);
            dl.setText(L.s("wmLogoDownload"));
            Ux.styleButton(dl);
            dl.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { logoDownload(); }
            });
            g1.addView(dl, new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            body.addView(group(L.s("wmGLogo"), g1));
        } else if ("rect".equals(el.type)) {
            LinearLayout g1 = new LinearLayout(this);
            g1.addView(lab(L.s("wmColor")));
            g1.addView(palette(color));
            g1.addView(lab(L.s("wmBlend")));
            g1.addView(combo(blendLabels(), blendSel[0], blendSel), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            txtAlpha = num(g1, L.s("wmOpacity"), 0f, 100f, 5f, el.alpha * 100f, "%.0f");
            g1.addView(lab(L.s("wmEdge")));
            final Spinner rsp = new Spinner(this);
            ArrayList<String> rl = new ArrayList<String>();
            rl.add(L.s("wmEdgeSoft"));
            rl.add(L.s("wmEdgeJagged"));
            EditorActivity.StdSpinnerAdapter ra = new EditorActivity.StdSpinnerAdapter(this, rl);
            ra.spin = rsp;
            rsp.setAdapter(ra);
            rsp.setSelection("jagged".equals(el.edge) ? 1 : 0);
            g1.addView(Ux.wrapSpinner(this, rsp), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            edgeSize = num(g1, L.s("wmEdgeSize"), 0f, 5f, 0.05f, el.edgeSize, "%.2f");
            edgeSize.kind = "jagged".equals(el.edge) ? Num.JAG : Num.SOFT;
            edgeSize.show();
            final Num rsz = edgeSize;
            rsp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                    edge[0] = pos == 1 ? "jagged" : "soft";
                    rsz.kind = pos == 1 ? Num.JAG : Num.SOFT;
                    rsz.sb.setProgress(pos == 1 ? Math.round(1f / 0.05f) : Math.round(0.1f / 0.05f));
                    rsz.show();
                }
                public void onNothingSelected(AdapterView<?> p) {}
            });
            body.addView(group(L.s("wmGRect"), g1));
        } else if ("line".equals(el.type)) {
            LinearLayout g1 = new LinearLayout(this);
            boolean hor = !Watermark.lineIsV(el.w, el.h / Math.max(0.2f, tpl.ar));
            g1.addView(lab(L.s("wmDir")));
            dirSel[0] = Watermark.lineIsV(el.w, el.h / Math.max(0.2f, tpl.ar)) ? 1 : 0;
            g1.addView(combo(dirLabels(), dirSel[0], dirSel), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            g1.addView(lab(L.s("wmColor")));
            g1.addView(palette(color));
            g1.addView(lab(L.s("wmBlend")));
            g1.addView(combo(blendLabels(), blendSel[0], blendSel), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
            txtAlpha = num(g1, L.s("wmOpacity"), 0f, 100f, 5f, el.alpha * 100f, "%.0f");
            float ar0 = Math.max(0.2f, tpl.ar);
            thickness = num(g1, L.s("wmThickness"), 0.5f, 12f, 0.5f,
                    (hor ? el.h : el.w * ar0) * PT_PER_CARD, "%.1f");   // v: width units -> height units
            body.addView(group(L.s("wmGLine"), g1));
        } else {
            LinearLayout g1 = new LinearLayout(this);
            opacity = num(g1, L.s("wmOpacity"), 0f, 100f, 1f, el.opacity * 100f, "%.0f");
            body.addView(group(L.s("wmGImage"), g1));
        }

        boolean rules = "line".equals(el.type);
        LinearLayout g3 = new LinearLayout(this);
        g3.addView(checkRow(L.s("wmShadowOn"), shOn));
        g3.addView(lab(L.s("wmColor")));
        g3.addView(palette(shColor));
        g3.addView(lab(L.s("wmBlend")));
        final int[] shBlendSel = {Math.max(0, Math.min(2, el.shBlend))};
        g3.addView(combo(blendLabels(), shBlendSel[0], shBlendSel), new LinearLayout.LayoutParams(-1, Ux.dp(38)));
        shBlur = num(g3, L.s("wmBlur"), 0f, 1f, 0.1f, el.shBlur / BLUR_UNIT, "%.1f");
        shOp = num(g3, L.s("wmOpacity"), 0f, 100f, 10f, Color.alpha(el.shColor) / 2.55f, "%.0f");
        shDy = num(g3, L.s("wmOffsetY"), -1f, 1f, 0.1f, el.shDy / OFF_UNIT, "%.1f");
        shDx = num(g3, L.s("wmOffsetX"), -1f, 1f, 0.1f, el.shDx / OFF_UNIT, "%.1f");
        body.addView(group(L.s("wmGShadow"), g3));

        ScrollView sc = new ScrollView(this);
        sc.addView(body);
        final Dialog[] dh = new Dialog[1];
        final Num fSize = size, fEdgeSize = edgeSize, fOpacity = opacity, fThickness = thickness,
                fShOp = shOp, fShBlur = shBlur, fShDx = shDx, fShDy = shDy, fTxtAlpha = txtAlpha;
        Button ok = new Button(this);
        ok.setText(L.s("btnOk"));
        Ux.styleButton(ok);
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (et[0] != null) el.text = et[0].getText().toString();
                if (fSize != null) el.size = fSize.value() / PT_PER_CARD;   // pt of a 4-inch-tall card
                el.color = color[0];
                el.align = ALIGN_IDS[Math.max(0, Math.min(2, alignSel[0]))];
                if ("text".equals(el.type)) el.font = Watermark.FONT_IDS[Math.max(0, Math.min(Watermark.FONT_IDS.length - 1, fontSel[0]))];
                el.bold = bold[0];
                if ("text".equals(el.type) || "line".equals(el.type) || "logo".equals(el.type)
                        || "rect".equals(el.type)) {
                    el.blend = Math.max(0, Math.min(2, blendSel[0]));
                    if (fTxtAlpha != null) el.alpha = Math.max(0f, Math.min(1f, fTxtAlpha.value() / 100f));
                }
                el.shBlend = Math.max(0, Math.min(2, shBlendSel[0]));
                if (fOpacity != null) el.opacity = fOpacity.value() / 100f;
                if ("logo".equals(el.type)) el.brand = brandSel[0] <= 0 ? "" : Logo.BRANDS[brandSel[0] - 1][0];
                el.shOn = shOn[0];
                if (fShOp != null) {
                    int a = Math.round(fShOp.value() / 100f * 255f);
                    el.shColor = (shColor[0] & 0x00FFFFFF) | ((a & 0xFF) << 24);
                    el.shBlur = fShBlur.value() * BLUR_UNIT;   // unitised: 1.0 = 8% of the short side
                    el.shDx = fShDx.value() * OFF_UNIT;        // unitised: 1.0 = 3% of the short side
                    el.shDy = fShDy.value() * OFF_UNIT;
                }
                if (fThickness != null) {
                    float ar = Math.max(0.2f, tpl.ar);
                    boolean newV = dirSel[0] == 1;
                    boolean oldV = Watermark.lineIsV(el.w, el.h / ar);   // geometry decides, not the hint
                    float thH = fThickness.value() / PT_PER_CARD;   // thickness in card-HEIGHT units
                    if (newV != oldV) {                       // swap the axes: keep centre + physical length
                        float cx = el.x + el.w / 2f, cy = el.y + el.h / 2f;
                        float lenW = oldV ? el.h * ar : el.w;            // length in card-width units
                        el.dir = newV ? "v" : "h";
                        if (newV) { el.w = thH / ar; el.h = Math.min(1f, lenW * ar); }
                        else      { el.h = thH;      el.w = Math.min(1f, lenW); }
                        el.x = Math.max(0f, Math.min(1f - el.w, cx - el.w / 2f));
                        el.y = Math.max(0f, Math.min(1f - el.h, cy - el.h / 2f));
                    } else if (newV) {
                        el.w = thH / ar;                          // width units
                    } else {
                        el.h = thH;
                    }
                }
                if ("photo".equals(el.type)) {
                    el.ratio = "3:2";
                    el.h = Math.min(1f - el.y, Math.max(0.05f, el.w * tpl.ar / 1.5f));
                }
                if (isBox(el.type) || "rect".equals(el.type)) {
                    el.edge = edge[0];
                    if (fEdgeSize != null) el.edgeSize = fEdgeSize.value();
                }
                if ("text".equals(el.type)) canvas.fitText(el);
                canvas.invalidate();
                setDirty(true);
                if (dh[0] != null) dh[0].dismiss();
            }
        });
        Button cancel = new Button(this);
        cancel.setText(L.s("btnCancel"));
        Ux.styleButton(cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { if (dh[0] != null) dh[0].dismiss(); }
        });
        LinearLayout btns = new LinearLayout(this);
        btns.addView(cancel);
        btns.addView(ok);
        String ttl = "text".equals(el.type) ? L.s("wmPropsText")
                : ("photo".equals(el.type) ? L.s("wmPropsPhoto")
                : ("rect".equals(el.type) ? L.s("wmGRect")
                : (rules ? L.s("wmPropsLine")
                : ("logo".equals(el.type) ? L.s("wmPropsLogo") : L.s("wmPropsImage")))));
        dh[0] = MainActivity.winDlgX(this, ttl, dialogWrap(sc, btns));
        sizeDialog(dh[0]);
        dh[0].show();
    }

    private class WmCanvasView extends View {
        private final RectF card = new RectF();
        private Watermark.Tpl t;
        private int sel = -1;
        private int drag;
        private float downX, downY, startX, startY, startW, startH, startSize, rl, rt, rr, rb0;
        private final Map<String, Bitmap> cache = new HashMap<String, Bitmap>();
        private final RectF[] iconRects = {new RectF(), new RectF(), new RectF(), new RectF()};
        private final RectF resetRect = new RectF(), bgRect = new RectF(), padRect = new RectF();   // board buttons
        private float zoom = 1f, panX = 0f, panY = 0f;      // viewport transform (pinch / two-finger pan)
        private boolean twoFinger;
        private float gSpan, gX, gY;
        private final RectF card0 = new RectF();            // card rect when a card-corner drag started
        private final java.util.List<float[]> geo = new ArrayList<float[]>();   // element pixel geometry at that moment
        private boolean cardResizing;
        private boolean moved;          // press moved past the tap threshold -> it is a drag, not a tap
        private int downHit = -1;       // element under the press
        private boolean onButton;       // the press landed on an icon button: never changes the selection
        private float panStartX, panStartY, downRawX, downRawY;   // viewport panning (drag on empty space)
        private float cornerOffX, cornerOffY;   // corner position minus finger at DOWN: keeps the first MOVE jump-free

        WmCanvasView(android.content.Context c) {
            super(c);
            setFocusable(true);
        }

        void setTemplate(Watermark.Tpl tt) {
            t = tt;
            sel = -1;
            cache.clear();
            computeCard();
            refitTexts();
            invalidate();
            refreshTools();
        }

        /** text boxes always hug their glyphs (also for templates authored before auto-sizing) */
        void refitTexts() {
            if (t == null) return;
            for (Watermark.El e : t.els) measureText(e);
        }

        void fitText(Watermark.El e) {
            measureText(e);
            invalidate();
        }

        /** card-relative centre of what the user currently sees (zoom + pan taken into account) */
        float[] viewCenterCard() {
            if (card.isEmpty()) return new float[]{0.5f, 0.5f};
            float vx = (getWidth() / 2f - panX) / zoom;
            float vy = (getHeight() / 2f - panY) / zoom;
            float cx = (vx - card.left) / card.width();
            float cy = (vy - card.top) / card.height();
            return new float[]{Math.max(0f, Math.min(1f, cx)), Math.max(0f, Math.min(1f, cy))};
        }

        int selected() { return sel; }

        void select(int i) {
            sel = i;
            invalidate();
            refreshTools();
        }

        void computeCard() {
            float pad = Ux.dp(8);
            float aw = getWidth() - 2 * pad, ah = getHeight() - 2 * pad;
            if (t == null || aw < 20 || ah < 20) { card.setEmpty(); return; }
            float cw = Math.min(aw, ah * t.ar);
            float ch = cw / t.ar;
            float l = (getWidth() - cw) / 2f, tp = (getHeight() - ch) / 2f;
            card.set(l, tp, l + cw, tp + ch);
            refitTexts();   // text boxes are in card-relative units: re-measure whenever the card size changes
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            computeCard();
        }

        @Override
        protected void onDraw(Canvas cv) {
            if (t == null || card.isEmpty()) return;
            int bgc = t.bg;
            Watermark.sFallbackLum = (0.299f * android.graphics.Color.red(bgc)
                    + 0.587f * android.graphics.Color.green(bgc)
                    + 0.114f * android.graphics.Color.blue(bgc)) / 255f;
            cv.save();
            cv.translate(panX, panY);
            cv.scale(zoom, zoom);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(t.bg);
            cv.drawRect(card, p);
            cv.save();
            cv.clipRect(card);
            toneCache = null;
            for (int i = 0; i < t.els.size(); i++) drawEl(cv, t.els.get(i), false);
            cv.restore();
            if (sel >= 0 && sel < t.els.size()) drawChrome(cv, t.els.get(sel));   // content-space chrome only
            Paint bd = new Paint(Paint.ANTI_ALIAS_FLAG);
            bd.setStyle(Paint.Style.STROKE);
            bd.setStrokeWidth(Ux.dp(1));
            bd.setColor(0xFF303030);
            cv.drawRect(card, bd);
            if (cardResizing) drawPhotoGuide(cv);
            cv.restore();
            // ---- screen space: handles and floating buttons keep their size at any zoom
            if (sel >= 0 && sel < t.els.size()) {
                drawHandlesScreen(cv, t.els.get(sel));
                drawIcons(cv, t.els.get(sel));
            } else {
                drawCardHandles(cv);
                drawBoardButtons(cv);
            }
        }

        /** board buttons (screen space, only while nothing is selected): portrait switch + reset + background */
        private void drawBoardButtons(Canvas cv) {
            float is = Ux.dp(40), gap = Ux.dp(8);
            float right = getWidth() - is - Ux.dp(10);
            float bottom = getHeight() - is - Ux.dp(10);
            bgRect.set(right, bottom, right + is, bottom + is);
            float l = right - (needsSizeReset() ? is + gap : 0f);
            resetRect.set(needsSizeReset() ? l : 0, needsSizeReset() ? bottom : 0,
                    needsSizeReset() ? l + is : 0, needsSizeReset() ? bottom + is : 0);
            Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);      // measure the label so the pill fits any locale
            m.setTypeface(Typeface.DEFAULT_BOLD);
            m.setTextSize(Ux.dp(12));
            float pw = Ux.dp(13) + Ux.dp(6) + m.measureText(L.s("wmPortrait")) + Ux.dp(11) * 2;
            float lp = l - pw - gap;                         // portrait switch, leftmost
            padRect.set(lp, bottom, lp + pw, bottom + is);

            boolean on = tpl != null && tpl.padapt;
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint bd = new Paint(Paint.ANTI_ALIAS_FLAG);
            bd.setStyle(Paint.Style.STROKE);
            bd.setStrokeWidth(Ux.dp(1));
            bd.setColor(0x99FFFFFF);
            Paint gl = new Paint(Paint.ANTI_ALIAS_FLAG);
            gl.setColor(0xFFFFFFFF);
            gl.setTypeface(Typeface.DEFAULT_BOLD);
            gl.setTextAlign(Paint.Align.CENTER);
            float[][] rects = {{resetRect.left, resetRect.top, resetRect.right, resetRect.bottom},
                    {bgRect.left, bgRect.top, bgRect.right, bgRect.bottom},
                    {padRect.left, padRect.top, padRect.right, padRect.bottom}};
            String[] glyphs = {"⟳", "◐", ""};
            for (int i = 0; i < 3; i++) {
                float[] r = rects[i];
                if (r[2] - r[0] < 2) continue;
                bg.setColor(i == 2 && on ? 0xEE7A4A00 : 0xEE000080);
                cv.drawRoundRect(new RectF(r[0], r[1], r[2], r[3]), Ux.dp(4), Ux.dp(4), bg);
                bd.setColor(i == 2 && on ? 0xFFFFC107 : 0x99FFFFFF);
                cv.drawRoundRect(new RectF(r[0], r[1], r[2], r[3]), Ux.dp(4), Ux.dp(4), bd);
                if (i < 2) {
                    gl.setTextSize(Ux.dp(20));
                    gl.setColor(0xFFFFFFFF);
                    cv.drawText(glyphs[i], (r[0] + r[2]) / 2f, (r[1] + r[3]) / 2f + Ux.dp(7), gl);
                } else {
                    float cb = Ux.dp(13);
                    float bx = r[0] + Ux.dp(11), by = (r[1] + r[3]) / 2f - cb / 2f;
                    Paint cbBg = new Paint(Paint.ANTI_ALIAS_FLAG);
                    cbBg.setColor(on ? 0xFFFFC107 : 0x00000000);
                    cv.drawRoundRect(new RectF(bx, by, bx + cb, by + cb), Ux.dp(2), Ux.dp(2), cbBg);
                    Paint cbBd = new Paint(Paint.ANTI_ALIAS_FLAG);
                    cbBd.setStyle(Paint.Style.STROKE);
                    cbBd.setStrokeWidth(Ux.dp(1.2f));
                    cbBd.setColor(on ? 0xFF7A4A00 : 0xCCFFFFFF);
                    cv.drawRoundRect(new RectF(bx, by, bx + cb, by + cb), Ux.dp(2), Ux.dp(2), cbBd);
                    if (on) {                                // tick
                        Paint tk = new Paint(Paint.ANTI_ALIAS_FLAG);
                        tk.setStyle(Paint.Style.STROKE);
                        tk.setStrokeWidth(Ux.dp(2));
                        tk.setStrokeCap(Paint.Cap.ROUND);
                        tk.setColor(0xFF201000);
                        android.graphics.Path path = new android.graphics.Path();
                        path.moveTo(bx + cb * 0.22f, by + cb * 0.52f);
                        path.lineTo(bx + cb * 0.44f, by + cb * 0.76f);
                        path.lineTo(bx + cb * 0.80f, by + cb * 0.26f);
                        cv.drawPath(path, tk);
                    }
                    gl.setTextSize(Ux.dp(12));
                    gl.setTextAlign(Paint.Align.LEFT);
                    gl.setColor(on ? 0xFFFFD54F : 0xFFFFFFFF);
                    cv.drawText(L.s("wmPortrait"), bx + cb + Ux.dp(6), (r[1] + r[3]) / 2f + Ux.dp(4), gl);
                }
            }
        }

        /** element handles in SCREEN space: fixed dp size and fixed reach at any zoom */
        private void drawHandlesScreen(Canvas cv, Watermark.El e) {
            float x = panX + zoom * (card.left + e.x * card.width());
            float y = panY + zoom * (card.top + e.y * card.height());
            float w = zoom * e.w * card.width();
            float h = zoom * e.h * card.height();
            if ("line".equals(e.type)) {                 // two length knobs + one move knob
                float[] seg = lineSegScreen(e);
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(0xFFFFFFFF);
                Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
                edge.setStyle(Paint.Style.STROKE);
                edge.setStrokeWidth(Ux.dp(1.5f));
                edge.setColor(0xFF00A8FF);
                float hs = Ux.dp(13), hs2 = Ux.dp(17);
                cv.drawRect(seg[0] - hs / 2, seg[1] - hs / 2, seg[0] + hs / 2, seg[1] + hs / 2, fill);
                cv.drawRect(seg[0] - hs / 2, seg[1] - hs / 2, seg[0] + hs / 2, seg[1] + hs / 2, edge);
                cv.drawRect(seg[2] - hs / 2, seg[3] - hs / 2, seg[2] + hs / 2, seg[3] + hs / 2, fill);
                cv.drawRect(seg[2] - hs / 2, seg[3] - hs / 2, seg[2] + hs / 2, seg[3] + hs / 2, edge);
                float mx = (seg[0] + seg[2]) / 2f, my = (seg[1] + seg[3]) / 2f;
                RectF mk = new RectF(mx - hs2 / 2, my - hs2 / 2, mx + hs2 / 2, my + hs2 / 2);
                cv.drawRoundRect(mk, Ux.dp(3), Ux.dp(3), fill);
                cv.drawRoundRect(mk, Ux.dp(3), Ux.dp(3), edge);
                Paint gl = new Paint(Paint.ANTI_ALIAS_FLAG);
                gl.setColor(0xFF00A8FF);
                gl.setStyle(Paint.Style.STROKE);
                gl.setStrokeWidth(Ux.dp(1.6f));
                float a = Ux.dp(4);                      // little cross = "move"
                cv.drawLine(mx - a, my, mx + a, my, gl);
                cv.drawLine(mx, my - a, mx, my + a, gl);
                return;
            }
            if ("rect".equals(e.type)) {                 // scaling, not cropping: same white/blue as the corners
                float longSide = Ux.dp(20), thick = Ux.dp(9);
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(0xFFFFFFFF);
                Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
                edge.setStyle(Paint.Style.STROKE);
                edge.setStrokeWidth(Ux.dp(1.5f));
                edge.setColor(0xFF00A8FF);
                float[][] mids = {{x + w / 2, y}, {x + w / 2, y + h}, {x, y + h / 2}, {x + w, y + h / 2}};
                for (int i = 0; i < 4; i++) {
                    boolean vertical = i >= 2;
                    float kw = vertical ? thick : longSide, kh = vertical ? longSide : thick;
                    RectF k = new RectF(mids[i][0] - kw / 2, mids[i][1] - kh / 2,
                            mids[i][0] + kw / 2, mids[i][1] + kh / 2);
                    cv.drawRoundRect(k, Ux.dp(2), Ux.dp(2), fill);
                    cv.drawRoundRect(k, Ux.dp(2), Ux.dp(2), edge);
                }
            }
            if (isBox(e.type)) {
                float cx = x + e.cx0 * w, cy = y + e.cy0 * h;
                float cw2 = Math.max(2f, (e.cx1 - e.cx0) * w), ch2 = Math.max(2f, (e.cy1 - e.cy0) * h);
                float longSide = Ux.dp(20), thick = Ux.dp(9);          // reachable, clearly visible knobs
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(0xFFFFB300);
                Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
                edge.setStyle(Paint.Style.STROKE);
                edge.setStrokeWidth(Ux.dp(1.6f));
                edge.setColor(0xFF5A3200);
                Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
                white.setColor(0x66FFFFFF);
                RectF k = new RectF();
                // top / bottom (wide), left / right (tall)
                float[][] mids = {
                        {cx + cw2 / 2, cy}, {cx + cw2 / 2, cy + ch2},
                        {cx, cy + ch2 / 2}, {cx + cw2, cy + ch2 / 2}};
                for (int i = 0; i < 4; i++) {
                    boolean vertical = i >= 2;
                    float kw = vertical ? thick : longSide, kh = vertical ? longSide : thick;
                    k.set(mids[i][0] - kw / 2, mids[i][1] - kh / 2, mids[i][0] + kw / 2, mids[i][1] + kh / 2);
                    cv.drawRoundRect(k, Ux.dp(2), Ux.dp(2), fill);
                    cv.drawRoundRect(k, Ux.dp(2), Ux.dp(2), white);     // glossy top half feel
                    cv.drawRoundRect(k, Ux.dp(2), Ux.dp(2), edge);
                }
            }
            float hs = Ux.dp(9);
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(0xFFFFFFFF);
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Ux.dp(1.5f));
            edge.setColor(0xFF00A8FF);
            float[][] cs = {{x, y}, {x + w, y}, {x, y + h}, {x + w, y + h}};
            for (float[] c : cs) {
                cv.drawRect(c[0] - hs / 2, c[1] - hs / 2, c[0] + hs / 2, c[1] + hs / 2, fill);
                cv.drawRect(c[0] - hs / 2, c[1] - hs / 2, c[0] + hs / 2, c[1] + hs / 2, edge);
            }
        }

        /** green frame handles: four corner squares (both axes) + four edge bars (one axis) */
        private void drawCardHandles(Canvas cv) {
            float hs = Ux.dp(11);
            Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
            hp.setColor(0xFF00C853);
            Paint bd = new Paint(Paint.ANTI_ALIAS_FLAG);
            bd.setStyle(Paint.Style.STROKE);
            bd.setStrokeWidth(Ux.dp(1.2f));
            bd.setColor(0xFFFFFFFF);
            float l = panX + zoom * card.left, t = panY + zoom * card.top;
            float r = panX + zoom * card.right, b = panY + zoom * card.bottom;
            float[][] cs = {{l, t}, {r, t}, {l, b}, {r, b}};
            for (float[] c : cs) {
                cv.drawRect(c[0] - hs / 2, c[1] - hs / 2, c[0] + hs / 2, c[1] + hs / 2, hp);
                cv.drawRect(c[0] - hs / 2, c[1] - hs / 2, c[0] + hs / 2, c[1] + hs / 2, bd);
            }
            float lo = Ux.dp(20), th = Ux.dp(9);
            float[][] edges = {
                    {(l + r) / 2, t, lo, th}, {(l + r) / 2, b, lo, th},      // top / bottom (horizontal bars)
                    {l, (t + b) / 2, th, lo}, {r, (t + b) / 2, th, lo}};     // left / right (vertical bars)
            for (float[] e2 : edges) {
                RectF k = new RectF(e2[0] - e2[2] / 2, e2[1] - e2[3] / 2, e2[0] + e2[2] / 2, e2[1] + e2[3] / 2);
                cv.drawRoundRect(k, Ux.dp(2), Ux.dp(2), hp);
                cv.drawRoundRect(k, Ux.dp(2), Ux.dp(2), bd);
            }
        }

        /** dashed outline of the photo slot while the card is being resized: the photo keeps its
         *  original pixel size, so its scale can never drift */
        private void drawPhotoGuide(Canvas cv) {
            for (int i = 0; i < t.els.size(); i++) {
                Watermark.El e = t.els.get(i);
                if (!"photo".equals(e.type)) continue;
                Paint d = new Paint(Paint.ANTI_ALIAS_FLAG);
                d.setStyle(Paint.Style.STROKE);
                d.setStrokeWidth(Ux.dp(1.6f) / zoom);
                d.setColor(0xFFFF4081);
                d.setPathEffect(new android.graphics.DashPathEffect(
                        new float[]{Ux.dp(9) / zoom, Ux.dp(6) / zoom}, 0));
                float x = card.left + e.x * card.width(), y = card.top + e.y * card.height();
                cv.drawRect(x, y, x + e.w * card.width(), y + e.h * card.height(), d);
                return;
            }
        }

        /** selection chrome, drawn OUTSIDE the card clip so an element that overhangs the canvas still
         *  shows its full box and handles */
        private void drawChrome(Canvas cv, Watermark.El e) {
            float x = card.left + e.x * card.width();
            float y = card.top + e.y * card.height();
            float w = Math.max(2f, e.w * card.width());
            float h = Math.max(2f, e.h * card.height());
            if ("line".equals(e.type)) return;          // rules: the three knobs ARE the chrome
            if (isBox(e.type)) {
                float cx = x + e.cx0 * w, cy = y + e.cy0 * h;
                float cw2 = Math.max(2f, (e.cx1 - e.cx0) * w), ch2 = Math.max(2f, (e.cy1 - e.cy0) * h);
                Paint red = new Paint();
                red.setColor(0x55FF0000);                       // cropped-away area, selection only
                if (e.cy0 > 0.001f) cv.drawRect(x, y, x + w, cy, red);
                if (e.cy1 < 0.999f) cv.drawRect(x, cy + ch2, x + w, y + h, red);
                if (e.cx0 > 0.001f) cv.drawRect(x, cy, cx, cy + ch2, red);
                if (e.cx1 < 0.999f) cv.drawRect(cx + cw2, cy, x + w, cy + ch2, red);
                Paint cf = new Paint(Paint.ANTI_ALIAS_FLAG);
                cf.setStyle(Paint.Style.STROKE);
                cf.setStrokeWidth(Ux.dp(2f));
                cf.setColor(0xFFFFB300);
                cv.drawRect(cx, cy, cx + cw2, cy + ch2, cf);
            }
            if (true) {
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setStyle(Paint.Style.STROKE);
                sp.setStrokeWidth(Ux.dp(1.5f));
                sp.setColor(0xFF00A8FF);
                cv.drawRect(x, y, x + w, y + h, sp);
            }
        }

        private Boolean toneCache;          // one tone per draw pass, cleared by the callers

        private void drawEl(Canvas cv, Watermark.El e, boolean selected) {
            float x = card.left + e.x * card.width();
            float y = card.top + e.y * card.height();
            float w = Math.max(2f, e.w * card.width());
            float h = Math.max(2f, e.h * card.height());
            int mn = (int) Math.min(card.width(), card.height());
            boolean cropOn = !(e.cx0 <= 0.001f && e.cy0 <= 0.001f && e.cx1 >= 0.999f && e.cy1 >= 0.999f);
            // the preview samples the reference photo itself (crop-aware); hand that decision to the
            // shared shadow/logo code so previews and the final render agree
            Watermark.sBgBrightOverride = tone();
            boolean clipCrop = cropOn && !selected;          // unselected: never show the discarded part
            // the shadow is OUTSIDE the element: draw it before the crop clip, otherwise a cropped
            // element loses it entirely in the preview (the renderer never clipped it)
            if (e.shOn && isBox(e.type)) {
                RectF vis = new RectF(x, y, x + w, y + h);
                if (cropOn) vis.set(x + e.cx0 * w, y + e.cy0 * h, x + e.cx1 * w, y + e.cy1 * h);
                if ("photo".equals(e.type)) {
                    float[] eg = Watermark.edgeGeom(mn, e);
                    Watermark.drawPhotoShadow(cv, vis, e, mn, Math.round(eg[0]), eg[1], 0x0F6A1E5AL + 977L, eg[2]);
                } else {
                    Bitmap sb = img(e.src);
                    if (sb != null) Watermark.drawImgShadow(cv, sb, vis, e, mn);
                }
            }
            if (clipCrop) {
                cv.save();
                cv.clipRect(x + e.cx0 * w, y + e.cy0 * h, x + e.cx1 * w, y + e.cy1 * h);
            }
            try {
            if ("photo".equals(e.type)) {
                Bitmap pv = showPreview ? preview() : null;
                if (pv != null) {
                    float[] eg = Watermark.edgeGeom(mn, e);
                    int amp = Math.round(eg[0]);
                    RectF ph = new RectF(x - amp, y - amp, x + w + amp, y + h + amp);
                    RectF lay = new RectF(ph.left - 2, ph.top - 2, ph.right + 2, ph.bottom + 2);
                    boolean cut = amp > 0 || eg[1] >= 1f;
                    if (cut) cv.saveLayer(lay, null);              // isolate the cut: DST_OUT must not eat the card
                    cv.drawBitmap(pv, src3to2(pv), ph, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
                    if (cut) {                                     // same edge treatment as the renderer
                        RectF vis = new RectF(x, y, x + w, y + h);
                        if (cropOn) vis.set(x + e.cx0 * w, y + e.cy0 * h, x + e.cx1 * w, y + e.cy1 * h);
                        Watermark.edgeBands(cv, vis, t.bg, mn, amp, eg[1], 0x0F6A1E5AL + 977L, eg[2]);
                        cv.restore();
                    }
                } else {
                Paint bgp = new Paint();
                bgp.setShader(new LinearGradient(0, y, 0, y + h, 0xFFA6A6A6, 0xFF6A6A6A, Shader.TileMode.CLAMP));
                cv.drawRect(x, y, x + w, y + h, bgp);
                Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                sp.setStyle(Paint.Style.STROKE);
                sp.setColor(0x55FFFFFF);
                sp.setStrokeWidth(Ux.dp(1));
                cv.drawRect(x + Ux.dp(5), y + Ux.dp(5), x + w - Ux.dp(5), y + h - Ux.dp(5), sp);
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setColor(0xCCFFFFFF);
                tp.setTextSize(Ux.ts(14));
                tp.setTypeface(Typeface.DEFAULT_BOLD);
                tp.setTextAlign(Paint.Align.CENTER);
                cv.drawText("PHOTO " + (e.ratio == null ? "3:2" : e.ratio), x + w / 2f, y + h / 2f + Ux.ts(5), tp);
                }
            } else if ("logo".equals(e.type)) {
                boolean follow = e.brand == null || e.brand.length() == 0;
                Bitmap lb = follow ? Logo.own() : Logo.get(e.brand);
                if (lb == null) lb = Logo.own();       // unknown / not downloaded: same fallback as the render
                if (lb != null) {
                    Watermark.drawLogoBitmap(cv, e, new RectF(x, y, x + w, y + h), mn, lb, tone());
                } else {
                    Paint ph = new Paint(Paint.ANTI_ALIAS_FLAG);
                    ph.setStyle(Paint.Style.STROKE);
                    ph.setStrokeWidth(Ux.dp(1.5f));
                    ph.setColor(0x88FF3B30);
                    cv.drawRect(x, y, x + w, y + h, ph);
                    Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    tp.setColor(0xAAFF3B30);
                    tp.setTextSize(Ux.ts(12));
                    tp.setTextAlign(Paint.Align.CENTER);
                    cv.drawText(L.s("wmLogoMissing"), x + w / 2f, y + h / 2f + Ux.ts(4), tp);
                }
            } else if ("line".equals(e.type)) {
                if (e.shOn) Watermark.drawLineShadow(cv, new RectF(x, y, x + w, y + h), e, mn);
                Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
                int link = e.color;
                if (e.blend == 1) link = tone() ? 0xFF000000 : 0xFFFFFFFF;
                int lal = Math.round(android.graphics.Color.alpha(e.color) * e.alpha);
                lp.setColor((link & 0x00FFFFFF) | (Math.max(0, Math.min(255, lal)) << 24));
                if (e.blend == 2) lp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
                cv.drawRect(x, y, x + w, y + h, lp);
            } else if ("rect".equals(e.type)) {
                RectF rr = new RectF(x, y, x + w, y + h);
                float[] eg = Watermark.edgeGeom(mn, e);
                int amp = Math.round(eg[0]);
                if (e.shOn) Watermark.drawPhotoShadow(cv, rr, e, mn, amp, eg[1], 0x5EC7A11AL + 977L, eg[2]);
                Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
                int link = e.color;
                if (e.blend == 1) link = tone() ? 0xFF000000 : 0xFFFFFFFF;
                int lal = Math.round(android.graphics.Color.alpha(e.color) * e.alpha);
                lp.setColor((link & 0x00FFFFFF) | (Math.max(0, Math.min(255, lal)) << 24));
                if (amp > 0 || eg[1] >= 1f) {                 // same cut as the renderer
                    RectF lay = new RectF(rr.left - amp - 2, rr.top - amp - 2, rr.right + amp + 2, rr.bottom + amp + 2);
                    Paint lpl = null;
                    if (e.blend == 2) {
                        lpl = new Paint();
                        lpl.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
                    }
                    cv.saveLayer(lay, lpl);
                    cv.drawRect(rr, lp);
                    Watermark.edgeBands(cv, rr, t.bg, mn, amp, eg[1], 0x5EC7A11AL + 977L, eg[2]);
                    cv.restore();
                } else {
                    if (e.blend == 2) lp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
                    cv.drawRect(rr, lp);
                }
            } else if ("img".equals(e.type)) {
                Bitmap bm = img(e.src);
                if (bm != null) {
                    Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                    ip.setAlpha(Math.round(255f * e.opacity));
                    cv.drawBitmap(bm, null, new RectF(x, y, x + w, y + h), ip);
                } else {
                    Paint ap = new Paint();
                    ap.setColor(0x33FF0000);
                    cv.drawRect(x, y, x + w, y + h, ap);
                }
            } else {
                String txt = Watermark.sample(Watermark.pickVariant(e.text));
                Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
                tp.setTypeface(Watermark.face(e.font, e.bold));
                float ts = Math.max(3f, e.size * card.height());
                tp.setTextSize(ts);
                String[] lines = txt.split("\n", -1);
                float lh = ts * 1.25f;
                int ink = e.color;
                if (e.blend == 1) ink = tone() ? 0xFF000000 : 0xFFFFFFFF;
                int al = Math.round(android.graphics.Color.alpha(e.color) * e.alpha);
                tp.setColor((ink & 0x00FFFFFF) | (Math.max(0, Math.min(255, al)) << 24));
                if (e.shOn && e.blend != 2 && e.shBlend != 2) {
                    int sc = Watermark.shadowInk(e, x, y, w, lines.length * lh);
                    int sa = Math.round(android.graphics.Color.alpha(sc) * e.alpha);
                    tp.setShadowLayer(Math.max(0.5f, e.shBlur * card.height()), e.shDx * card.height(), e.shDy * card.height(),
                            (sc & 0x00FFFFFF) | (Math.max(0, Math.min(255, sa)) << 24));
                }
                if (e.blend == 2) tp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
                for (int i = 0; i < lines.length; i++) {
                    float lw = tp.measureText(lines[i]);
                    float lx = x;
                    if ("center".equals(e.align)) lx = x + (w - lw) / 2f;
                    else if ("right".equals(e.align)) lx = x + w - lw;
                    cv.drawText(lines[i], lx, y + ts + i * lh, tp);
                }
            }
            } finally {
                if (clipCrop) cv.restore();
                Watermark.sBgBrightOverride = null;
            }
        }

        /** preview estimate of the background luminance under a view-space box (card colour, or the
         *  reference photo where the box overlaps it). The final renderer samples the real pixels. */
        /** preview estimate of the tone under a view-space box; adds its 9 points to {bright, dark, sum} */
        private void adaptiveVotes(float x, float y, float w, float h, float[] acc) {
            Bitmap pv = showPreview ? preview() : null;
            android.graphics.RectF pr = null;
            if (pv != null && t != null) {
                for (Watermark.El q : t.els) if ("photo".equals(q.type)) {
                    pr = new android.graphics.RectF(          // the VISIBLE part: honour the crop viewport
                            card.left + (q.x + q.cx0 * q.w) * card.width(),
                            card.top + (q.y + q.cy0 * q.h) * card.height(),
                            card.left + (q.x + q.cx1 * q.w) * card.width(),
                            card.top + (q.y + q.cy1 * q.h) * card.height());
                    break;
                }
            }
            float[] xs = {x, x + w / 2f, x + w, x, x + w / 2f, x + w, x, x + w / 2f, x + w};
            float[] ys = {y, y, y, y + h / 2f, y + h / 2f, y + h / 2f, y + h, y + h, y + h};
            for (int i = 0; i < 9; i++) {
                int c = t.bg;
                if (pv != null && pr != null && pr.width() > 1f && pr.height() > 1f && pr.contains(xs[i], ys[i])) {
                    float u = (xs[i] - pr.left) / pr.width(), v = (ys[i] - pr.top) / pr.height();
                    int px = Math.max(0, Math.min(pv.getWidth() - 1, Math.round(u * pv.getWidth())));
                    int py = Math.max(0, Math.min(pv.getHeight() - 1, Math.round(v * pv.getHeight())));
                    c = pv.getPixel(px, py);
                }
                double lum = (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c)
                        + 0.114 * android.graphics.Color.blue(c)) / 255.0;
                acc[2] += lum;
                if (lum > 0.5) acc[0]++; else acc[1]++;
            }
        }

        private boolean adaptiveVerdict(float[] v) {
            if (v[0] != v[1]) return v[0] > v[1];
            float n = v[0] + v[1];
            return n > 0 && v[2] / n > 0.5f;
        }

        /** the preview's single 明暗 decision: same rule as the renderer — all adaptive elements vote */
        private boolean tone() {
            if (toneCache != null) return toneCache.booleanValue();
            float[] acc = new float[3];
            int voters = 0;
            if (t != null) for (Watermark.El e : t.els) {
                if (!Watermark.adapts(e)) continue;
                adaptiveVotes(card.left + e.x * card.width(), card.top + e.y * card.height(),
                        Math.max(2f, e.w * card.width()), Math.max(2f, e.h * card.height()), acc);
                voters++;
            }
            toneCache = Boolean.valueOf(voters > 0 && adaptiveVerdict(acc));
            return toneCache.booleanValue();
        }

        /** the same paint the editor draws text with (kept 1:1 with the renderer's metrics) */
        private android.graphics.Paint textPaint(Watermark.El e) {
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setTypeface(Watermark.face(e.font, e.bold));
            p.setTextSize(Math.max(3f, e.size * card.height()));
            return p;
        }

        /** {width, height} of the text block in card pixels at the element's current size */
        private float[] measureSize(Watermark.El e) {
            android.graphics.Paint p = textPaint(e);
            String[] lines = Watermark.sample(e.text).split("\n", -1);
            float maxW = 0;
            for (String ln : lines) maxW = Math.max(maxW, p.measureText(ln));
            float ts = p.getTextSize();
            if (maxW <= 0.5f) maxW = ts * 0.6f;                     // empty text keeps a grabable handle
            return new float[]{maxW, lines.length * ts * 1.25f};
        }

        /** text elements have no free-form box: w/h always hug the glyphs (sample text in the editor) */
        private void measureText(Watermark.El e) {
            if (!"text".equals(e.type)) return;
            if (card.width() < 2f || card.height() < 2f) return;   // before the first layout pass
            float[] wh = measureSize(e);
            float cw = Math.max(1f, card.width()), ch = Math.max(1f, card.height());
            float oldL = e.x * cw, oldT = e.y * ch, oldW = e.w * cw;
            if ("right".equals(e.align)) e.x = (oldL + oldW - wh[0]) / cw;
            else if ("center".equals(e.align)) e.x = (oldL + oldW / 2f - wh[0] / 2f) / cw;
            else e.x = oldL / cw;
            e.y = oldT / ch;
            e.w = wh[0] / cw;
            e.h = wh[1] / ch;
        }

        private Bitmap previewBmp;
        private boolean previewTried;

        /** the reference photo the preview comes from: a user-imported reference (custom/scenes) first,
         *  then the official preview_*.jpg scene images, then the newest graded shot */
        private String previewPath() {
            try {
                java.io.File cs = new java.io.File("/sdcard/OpenFilm6K/custom/scenes");
                java.io.File[] cf = cs.listFiles();
                java.io.File newest = null;
                if (cf != null) for (java.io.File f : cf) {
                    if (!f.getName().toLowerCase(Locale.US).endsWith(".jpg")) continue;
                    if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
                }
                if (newest != null) return newest.getAbsolutePath();
                java.io.File root = new java.io.File("/sdcard/OpenFilm6K");
                java.io.File[] rf = root.listFiles();
                java.util.TreeSet<String> sys = new java.util.TreeSet<String>();
                if (rf != null) for (java.io.File f : rf) {
                    String n = f.getName();
                    if (n.startsWith("preview_") && n.endsWith(".jpg")) sys.add(n);
                }
                if (!sys.isEmpty()) return new java.io.File(root, sys.first()).getAbsolutePath();
                // last resort: the newest graded photo
                java.io.File dir = new java.io.File("/sdcard/DCIM/OpenFilm6K");
                java.io.File[] fs = dir.listFiles();
                java.io.File best = null;
                if (fs != null) for (java.io.File f : fs) {
                    if (!f.getName().toLowerCase(Locale.US).endsWith(".jpg")) continue;
                    if (best == null || f.lastModified() > best.lastModified()) best = f;
                }
                return best == null ? null : best.getAbsolutePath();
            } catch (Throwable t) {
                return null;
            }
        }

        /** centre-cropped 3:2 source rect: the editor always shows the reference at 135 ratio */
        private android.graphics.Rect src3to2(Bitmap bm) {
            int bw = bm.getWidth(), bh = bm.getHeight();
            float target = 1.5f;
            int cw, ch;
            if (bw / (float) bh > target) { ch = bh; cw = Math.round(bh * target); }
            else { cw = bw; ch = Math.round(bw / target); }
            int l = (bw - cw) / 2, t = (bh - ch) / 2;
            return new android.graphics.Rect(l, t, l + cw, t + ch);
        }

        void resetPreview() {
            previewTried = false;
            if (previewBmp != null) { previewBmp.recycle(); previewBmp = null; }
        }

        /** the reference image file currently shown (used for EXIF-driven decisions like the brand logo) */
        private File previewSource() {
            try {
                String path = getSharedPreferences("of6k", 0).getString("wm_preview_ref", "");
                if (path == null || path.isEmpty() || !new java.io.File(path).isFile()) path = previewPath();
                return path == null ? null : new java.io.File(path);
            } catch (Throwable ig) {
                return null;
            }
        }

        private Bitmap preview() {
            if (previewTried) return previewBmp;
            previewTried = true;
            try {
                String path = getSharedPreferences("of6k", 0).getString("wm_preview_ref", "");
                if (path == null || path.isEmpty() || !new java.io.File(path).isFile()) path = previewPath();
                if (path == null) return null;
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(path, o);
                int sc = 1;
                while (o.outWidth / (sc * 2) >= 1024) sc *= 2;
                android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                o2.inSampleSize = sc;
                previewBmp = android.graphics.BitmapFactory.decodeFile(path, o2);
            } catch (Throwable t) {
                Engine.dbg("wm preview " + t);
            }
            return previewBmp;
        }

        private Bitmap img(String src) {
            if (src == null || t == null) return null;
            Bitmap bm = cache.get(src);
            if (bm != null) return bm;
            try {
                File f = new File(Watermark.dir(t.id), src);
                bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm != null) cache.put(src, bm);
            } catch (Throwable ig) {}
            return bm;
        }

        private float span(MotionEvent ev) {
            if (ev.getPointerCount() < 2) return 1f;
            float dx = ev.getX(0) - ev.getX(1), dy = ev.getY(0) - ev.getY(1);
            return Math.max(1f, (float) Math.hypot(dx, dy));
        }

        private float focalX(MotionEvent ev) { return ev.getPointerCount() < 2 ? ev.getX() : (ev.getX(0) + ev.getX(1)) / 2f; }

        private float focalY(MotionEvent ev) { return ev.getPointerCount() < 2 ? ev.getY() : (ev.getY(0) + ev.getY(1)) / 2f; }

        /** screen (view) -> content coordinates */
        private float bx(float sx) { return (sx - panX) / zoom; }

        private float by(float sy) { return (sy - panY) / zoom; }

        /** 1=TL 2=TR 3=BL 4=BR card corner under the finger (screen coords), 0 = none */
        private int cardHandleHit(float sx, float sy) {
            if (sel >= 0) return 0;                  // an element is selected: the frame is not grabbable
            float r = Ux.dp(30);
            float l = panX + zoom * card.left, t = panY + zoom * card.top;
            float rr2 = panX + zoom * card.right, bb2 = panY + zoom * card.bottom;
            float[][] cs = {{l, t}, {rr2, t}, {l, bb2}, {rr2, bb2}};
            for (int k = 0; k < 4; k++) {
                if (Math.abs(sx - cs[k][0]) <= r && Math.abs(sy - cs[k][1]) <= r) return k + 1;
            }
            float er = Ux.dp(24);
            if (Math.abs(sx - (l + rr2) / 2) <= er && Math.abs(sy - t) <= er) return 5;   // top
            if (Math.abs(sx - (l + rr2) / 2) <= er && Math.abs(sy - bb2) <= er) return 6; // bottom
            if (Math.abs(sx - l) <= er && Math.abs(sy - (t + bb2) / 2) <= er) return 7;   // left
            if (Math.abs(sx - rr2) <= er && Math.abs(sy - (t + bb2) / 2) <= er) return 8;  // right
            return 0;
        }

        /** snapshot the card + every element's CONTENT-pixel geometry, then rebuild the elements against
         *  the new card rect so their on-screen size never changes while the frame grows/shrinks. */
        private void beginCardResize() {
            card0.set(card);
            geo.clear();
            for (Watermark.El e : t.els) {
                geo.add(new float[]{
                        card.left + e.x * card.width(),
                        card.top + e.y * card.height(),
                        e.w * card.width(),
                        e.h * card.height(),
                        e.size});
            }
            cardResizing = true;
        }

        private void resizeCard(int corner, float bx, float by) {
            float minW = Ux.dp(140), minH = Ux.dp(90);
            float x0, x1, y0, y1;
            if (corner >= 5) {                       // edge handles: only one axis moves
                x0 = card0.left; x1 = card0.right; y0 = card0.top; y1 = card0.bottom;
                if (corner == 5) y0 = Math.min(by, y1 - minH);        // top
                else if (corner == 6) y1 = Math.max(by, y0 + minH);   // bottom
                else if (corner == 7) x0 = Math.min(bx, x1 - minW);   // left
                else x1 = Math.max(bx, x0 + minW);                    // right
            } else {
                float ax = (corner == 1 || corner == 3) ? card0.right : card0.left;
                float ay = (corner == 1 || corner == 2) ? card0.bottom : card0.top;
                x0 = Math.min(ax, bx); x1 = Math.max(ax, bx);
                y0 = Math.min(ay, by); y1 = Math.max(ay, by);
            }
            if (x1 - x0 < minW || y1 - y0 < minH) return;   // keep the card usable
            card.set(x0, y0, x1, y1);
            t.ar = card.width() / Math.max(1f, card.height());
            float cw = Math.max(1f, card.width()), ch = Math.max(1f, card.height());
            for (int i = 0; i < t.els.size() && i < geo.size(); i++) {
                Watermark.El e = t.els.get(i);
                float[] g = geo.get(i);
                e.x = (g[0] - card.left) / cw;
                e.y = (g[1] - card.top) / ch;
                e.w = g[2] / cw;
                e.h = g[3] / ch;
                // size is a fraction of the card height: to keep the GLYPH pixel size constant the
                // fraction must be scaled by the OLD height (card0), not the new one
                if ("text".equals(e.type)) e.size = g[4] * card0.height() / ch;
            }
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (t == null || card.isEmpty()) return false;
            float px = ev.getX(), py = ev.getY();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (ev.getPointerCount() < 2) return true;
                    twoFinger = true;
                    drag = 0;
                    cardResizing = false;
                    gSpan = span(ev);
                    gX = focalX(ev);
                    gY = focalY(ev);
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (ev.getPointerCount() - 1 < 2) { twoFinger = false; drag = 0; }
                    return true;
                case MotionEvent.ACTION_DOWN: {
                    downRawX = px;
                    downRawY = py;
                    panStartX = panX;
                    panStartY = panY;
                    moved = false;
                    onButton = false;
                    if (sel < 0) {
                        if (bgRect.contains(px, py)) { onButton = true; toggleBg(); return true; }
                        if (padRect.contains(px, py)) { onButton = true; togglePortraitFit(); return true; }
                        if (resetRect.contains(px, py)) { onButton = true; resetSize(); return true; }
                    }
                    int ch0 = cardHandleHit(px, py);
                    if (ch0 > 0) {                                // card corner: free-form frame resize
                        beginCardResize();
                        drag = 20 + ch0;
                        return true;
                    }
                    float cxs = bx(px), cys = by(py);             // content coords for all geometry work
                    downX = cxs;
                    downY = cys;
                    if (sel >= 0) {
                        placeIcons(t.els.get(sel));
                        for (int k = 0; k < iconCount(t.els.get(sel)); k++) {
                            if (iconRects[k].contains(px, py)) {   // icons live in screen space
                                onButton = true;
                                if (k == 0) showProps();
                                else if (k == 1) zorder(true);
                                else if (k == 2) zorder(false);
                                else deleteSel();
                                return true;
                            }
                        }
                        if ("line".equals(t.els.get(sel).type)) {
                            int lk = lineKnobHit(t.els.get(sel), px, py);
                            if (lk > 0) { capture(sel); drag = lk; invalidate(); return true; }
                        }
                        int[] ch = "line".equals(t.els.get(sel).type) ? null : cornerHit(px, py);
                        if (ch != null) {
                            capture(sel);
                            drag = ch[1];
                            float ccx = (ch[1] == 2 || ch[1] == 4) ? rl : rr;
                            float ccy = (ch[1] == 2 || ch[1] == 3) ? rt : rb0;
                            cornerOffX = ccx - cxs;
                            cornerOffY = ccy - cys;
                            invalidate();
                            refreshTools();
                            return true;
                        }
                        Watermark.El se = t.els.get(sel);
                        if ("rect".equals(se.type)) {
                            int re = rectEdgeHit(px, py);
                            if (re > 0) { capture(sel); drag = re; return true; }
                        }
                        if (isBox(se.type)) {
                            int ce = cropEdgeHit(se, px, py);      // screen space
                            if (ce > 0) { drag = ce; return true; }
                        }
                        downHit = hitTest(cxs, cys);
                        if (downHit == sel) {
                            capture(sel);
                            drag = 1;                              // body drag: move (also when it fills the view)
                        } else {
                            drag = 30;                             // press outside it: pan the viewport
                        }
                        return true;
                    }
                    downHit = hitTest(cxs, cys);                    // selection is decided on release
                    drag = 30;                                      // nothing selected: dragging pans
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!moved) {
                        float cpx0 = bx(px), cpy0 = by(py);
                        if (Math.hypot(cpx0 - downX, cpy0 - downY) > Ux.dp(10)) moved = true;
                        else return true;                       // still inside the tap threshold: keep waiting
                    }
                    if (twoFinger && ev.getPointerCount() < 2) { twoFinger = false; return true; }
                    if (twoFinger) {                             // pinch zoom + two-finger pan of the whole viewport
                        float f = span(ev), fx = focalX(ev), fy = focalY(ev);
                        if (gSpan > 20f) {
                            float ratio = Math.max(0.5f, Math.min(2f, f / gSpan));   // never jump more than 2x per event
                            float nz = Math.max(0.25f, Math.min(6f, zoom * ratio));
                            float cx = (gX - panX) / zoom, cy = (gY - panY) / zoom;
                            zoom = nz;
                            panX = fx - cx * zoom;
                            panY = fy - cy * zoom;
                        }
                        gSpan = f;
                        gX = fx;
                        gY = fy;
                        invalidate();
                        return true;
                    }
                    if (drag == 30) {                            // drag on empty space: pan the viewport
                        panX = panStartX + (ev.getX() - downRawX);
                        panY = panStartY + (ev.getY() - downRawY);
                        invalidate();
                        return true;
                    }
                    if (drag >= 40) {                            // rectangle edge handles
                        resizeRectEdge(t.els.get(sel), drag, bx(px), by(py));
                        invalidate();
                        return true;
                    }
                    if (drag == 6 || drag == 7 || drag == 8) {    // rule knobs (codes stay below 10)
                        dragLine(t.els.get(sel), drag, bx(px), by(py));
                        invalidate();
                        return true;
                    }
                    if (drag >= 20) {                            // card corner drag (free-form frame)
                        resizeCard(drag - 20, bx(px), by(py));
                        return true;
                    }
                    if (drag == 0 || sel < 0) return true;
                    Watermark.El e = t.els.get(sel);
                    float cpx = bx(px), cpy = by(py);             // MOVE events are screen coords: map them to content too
                    float dx = cpx - downX, dy = cpy - downY;
                    if (drag >= 10) {
                        dragCropEdge(e, drag, cpx, cpy);
                        invalidate();
                        return true;
                    }
                    if (drag == 1) {
                        float nx = snapPx(startX * card.width() + dx, card.width()) / Math.max(1f, card.width());
                        float ny = snapPx(startY * card.height() + dy, card.height()) / Math.max(1f, card.height());
                        e.x = clampKeepCorner(nx, e.w);
                        e.y = clampKeepCorner(ny, e.h);
                    } else {
                        float tx = cpx + cornerOffX, ty = cpy + cornerOffY;
                        if (snap && (Math.abs(cpx - downX) > Ux.dp(10) || Math.abs(cpy - downY) > Ux.dp(10))) {
                            tx = snapEdge(tx, card.left, card.width(), Ux.dp(9));
                            ty = snapEdge(ty, card.top, card.height(), Ux.dp(9));
                        }
                        resize(e, drag, tx, ty);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    boolean tap = !moved && ev.getActionMasked() == MotionEvent.ACTION_UP && !twoFinger;
                    if (cardResizing) { cardResizing = false; invalidate(); }
                    if (drag != 0) { if (drag != 30) setDirty(true); drag = 0; }   // panning is not an edit
                    twoFinger = false;
                    if (tap && !onButton) {                 // in-place tap on the artwork: select / clear
                        int hit = hitTest(bx(px), by(py));
                        // an element that overflows the view in BOTH axes (zoomed in) cannot be picked:
                        // such a tap behaves exactly like a tap on the background (deselect). An element
                        // that is already selected can still be dragged — dragging never uses this path.
                        if (hit >= 0 && biggerThanView(t.els.get(hit))) hit = -1;
                        sel = hit;
                        invalidate();
                        refreshTools();
                    }
                    moved = false;
                    return true;
                }
            }
            return false;
        }

        /** magnetic grid: snap only when within `tol` of a 1/50 line (never yanks the box on touch-down) */
        private float snapEdge(float v, float origin, float extent, float tol) {
            if (!snap || extent < 2f) return v;
            float step = extent / 50f;
            float line = origin + Math.round((v - origin) / step) * step;
            return Math.abs(v - line) <= tol ? line : v;
        }

        /** snap a card-relative fraction to the 1/50 grid (no-op when snapping is off) */
        private float snapFrac(float v, float extent) {
            return snapPx(v * extent, extent) / Math.max(1f, extent);
        }

        /** 1/50 grid of the given extent */
        private float snapPx(float v, float extent) {
            if (!snap || extent < 2f) return v;
            float step = extent / 50f;
            return Math.round(v / step) * step;
        }

        private void capture(int i) {
            Watermark.El e = t.els.get(i);
            startX = e.x; startY = e.y;
            startW = e.w; startH = e.h;
            startSize = e.size;
            rl = card.left + e.x * card.width();
            rt = card.top + e.y * card.height();
            rr = rl + e.w * card.width();
            rb0 = rt + e.h * card.height();
        }

        private int hitTest(float px, float py) {
            for (int i = t.els.size() - 1; i >= 0; i--) {
                Watermark.El e = t.els.get(i);
                float x = card.left + e.x * card.width(), y = card.top + e.y * card.height();
                float w = e.w * card.width(), h = e.h * card.height();
                if ("line".equals(e.type)) {
                    float[] s2 = Watermark.lineSeg(x, y, w, h);
                    float reach = Math.max(Ux.dp(9), (Math.abs(s2[2] - s2[0]) < 1f ? w : h) / 2f + Ux.dp(4));
                    if (segDist(px, py, s2) <= reach) return i;
                    continue;
                }
                if (px >= x && px <= x + w && py >= y && py <= y + h) return i;
            }
            return -1;
        }

        /** the element's SCREEN box overflows the visible area in BOTH axes (zoomed in far enough): such
         *  a box cannot be picked up by a tap — the user cannot see it whole. One axis alone is fine.
         *  Photo / image slots count by their CROP viewport (the part that is actually drawn). */
        private boolean biggerThanView(Watermark.El e) {
            float w = zoom * e.w * card.width(), h = zoom * e.h * card.height();
            if (isBox(e.type)) {
                w *= Math.max(0f, e.cx1 - e.cx0);
                h *= Math.max(0f, e.cy1 - e.cy0);
            }
            return w > getWidth() && h > getHeight();
        }

        /** portrait fit switch: a portrait shot is laid out on a portrait card with the whole design rotated */
        private void togglePortraitFit() {
            if (tpl == null) return;
            tpl.padapt = !tpl.padapt;
            setDirty(true);
            invalidate();
            MainActivity.toast(L.s("wmPortrait") + (tpl.padapt ? "  ✓" : "  ✕"));
        }

        /** the rule segment in SCREEN space */
        private float[] lineSegScreen(Watermark.El e) {
            float x = panX + zoom * (card.left + e.x * card.width());
            float y = panY + zoom * (card.top + e.y * card.height());
            float w = zoom * e.w * card.width(), h = zoom * e.h * card.height();
            float[] s2 = Watermark.lineSeg(x, y, w, h);
            return s2;
        }

        private float segDist(float px, float py, float[] s2) {
            float dx = s2[2] - s2[0], dy = s2[3] - s2[1];
            float l2 = dx * dx + dy * dy;
            float t = l2 < 1e-4f ? 0f : ((px - s2[0]) * dx + (py - s2[1]) * dy) / l2;
            t = Math.max(0f, Math.min(1f, t));
            return (float) Math.hypot(px - (s2[0] + t * dx), py - (s2[1] + t * dy));
        }

        /** 6 = start knob, 7 = end knob, 8 = middle (move) knob; 0 = none */
        private int lineKnobHit(Watermark.El e, float px, float py) {
            float[] seg = lineSegScreen(e);
            float r = Ux.dp(18);
            if (Math.hypot(px - seg[0], py - seg[1]) <= r) return 6;
            if (Math.hypot(px - seg[2], py - seg[3]) <= r) return 7;
            float mx = (seg[0] + seg[2]) / 2f, my = (seg[1] + seg[3]) / 2f;
            if (Math.hypot(px - mx, py - my) <= r) return 8;
            return 0;
        }

        /** drag one knob: the opposite end stays anchored, the thickness never changes; the middle moves it */
        private void dragLine(Watermark.El e, int code, float px, float py) {
            float CW = Math.max(1f, card.width()), CH = Math.max(1f, card.height());
            boolean v = Watermark.lineIsV(startW * CW, startH * CH);
            float minLen = Math.max(Ux.dp(12) / (v ? CH : CW), 0.01f);
            if (code == 8) {                                     // middle knob: move the whole rule
                e.x = clampKeepCorner(snapFrac(startX + (px - downX) / CW, CW), e.w);
                e.y = clampKeepCorner(snapFrac(startY + (py - downY) / CH, CH), e.h);
                return;
            }
            if (v) {
                float top0 = startY, bot0 = startY + startH;
                float pyN = snapFrac((py - card.top) / CH, CH);
                if (code == 6) {                                 // upper end
                    float ny = Math.max(0f, Math.min(Math.min(bot0 - minLen, 1f - minLen), pyN));
                    e.y = ny;
                    e.h = bot0 - ny;
                } else {                                         // lower end (bottom stays where it was)
                    e.h = Math.max(minLen, Math.min(1f - top0, pyN - top0));
                    e.y = top0;
                }
            } else {
                float l0 = startX, r0 = startX + startW;
                float pxN = snapFrac((px - card.left) / CW, CW);
                if (code == 6) {                                 // left end
                    float nx = Math.max(0f, Math.min(Math.min(r0 - minLen, 1f - minLen), pxN));
                    e.x = nx;
                    e.w = r0 - nx;
                } else {                                         // right end (left end stays)
                    e.w = Math.max(minLen, Math.min(1f - l0, pxN - l0));
                    e.x = l0;
                }
            }
        }

        /** every element gets the ✕ — except a photo when it is the only one left */
        private int iconCount(Watermark.El e) {
            if ("photo".equals(e.type) && t != null) {
                int n = 0;
                for (Watermark.El q : t.els) if ("photo".equals(q.type)) n++;
                if (n <= 1) return 3;
            }
            return 4;
        }

        /** action icons in SCREEN space: fixed size and reach at any zoom. Placement is decided by the
         *  VIEWPORT (not the card, which may be larger than the screen): above the box if it fits,
         *  otherwise below, and always clamped into view. */
        private void placeIcons(Watermark.El e) {
            float x = panX + zoom * (card.left + e.x * card.width());
            float y = panY + zoom * (card.top + e.y * card.height());
            float w = zoom * e.w * card.width(), h = zoom * e.h * card.height();
            float is = Ux.dp(36), gap = Ux.dp(6);
            int n = iconCount(e);
            float rowW = n * is + (n - 1) * gap;
            float W = Math.max(1, getWidth()), H = Math.max(1, getHeight());
            float ix = Math.max(2, Math.min(W - rowW - 2, x + w - rowW));
            float above = y - is - gap;
            float below = y + h + gap;
            float cardTopS = panY + zoom * card.top;
            // "above" must fit inside the CARD *and* the viewport; otherwise the row goes BELOW the box
            boolean aboveFits = above >= 4 && above >= cardTopS + 4;
            boolean belowInView = below + is <= H - 4;
            float iy;
            if (y < 0 && y + h > H) iy = H - is - gap;          // element spans the viewport -> bottom
            else if (aboveFits) iy = above;                     // ideal: above, inside the card
            else if (belowInView) iy = below;                   // else below the box (inside the viewport)
            else if (above >= 4) iy = above;                    // never cover the photo: above, even outside the card
            else iy = Math.max(4, Math.min(H - is - 4, y + gap));
            iy = Math.max(2, Math.min(H - is - 2, iy));
            for (int k = 0; k < 4; k++) {
                if (k >= n) { iconRects[k].setEmpty(); continue; }
                float l = ix + k * (is + gap);
                iconRects[k].set(l, iy, l + is, iy + is);
            }
        }

        private void drawIcons(Canvas cv, Watermark.El e) {
            placeIcons(e);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(0xEE000080);
            Paint bd = new Paint(Paint.ANTI_ALIAS_FLAG);
            bd.setStyle(Paint.Style.STROKE);
            bd.setStrokeWidth(Ux.dp(1));
            bd.setColor(0x99FFFFFF);
            Paint gl = new Paint(Paint.ANTI_ALIAS_FLAG);
            gl.setColor(0xFFFFFFFF);
            gl.setTypeface(Typeface.DEFAULT_BOLD);
            gl.setTextAlign(Paint.Align.CENTER);
            for (int k = 0; k < iconCount(e); k++) {
                RectF r = iconRects[k];
                cv.drawRoundRect(r, Ux.dp(4), Ux.dp(4), bg);
                cv.drawRoundRect(r, Ux.dp(4), Ux.dp(4), bd);
                gl.setTextSize(r.height() * 0.5f);
                cv.drawText(ICON_GLYPH[k], r.centerX(), r.centerY() + r.height() * 0.19f, gl);
            }
        }

        /** crop-edge handle under the finger (screen coords): 10 top, 11 bottom, 12 left, 13 right, 0 none */
        private int cropEdgeHit(Watermark.El e, float px, float py) {
            float x = panX + zoom * (card.left + e.x * card.width());
            float y = panY + zoom * (card.top + e.y * card.height());
            float w = zoom * e.w * card.width(), h = zoom * e.h * card.height();
            float cx = x + e.cx0 * w, cy = y + e.cy0 * h;
            float cw2 = (e.cx1 - e.cx0) * w, ch2 = (e.cy1 - e.cy0) * h;
            float r = Ux.dp(22);
            if (Math.abs(px - (cx + cw2 / 2)) <= r && Math.abs(py - cy) <= r) return 10;
            if (Math.abs(px - (cx + cw2 / 2)) <= r && Math.abs(py - (cy + ch2)) <= r) return 11;
            if (Math.abs(px - cx) <= r && Math.abs(py - (cy + ch2 / 2)) <= r) return 12;
            if (Math.abs(px - (cx + cw2)) <= r && Math.abs(py - (cy + ch2 / 2)) <= r) return 13;
            return 0;
        }

        private void dragCropEdge(Watermark.El e, int code, float px, float py) {
            float x = card.left + e.x * card.width(), y = card.top + e.y * card.height();
            float w = Math.max(1f, e.w * card.width()), h = Math.max(1f, e.h * card.height());
            float nx = clamp01((px - x) / w), ny = clamp01((py - y) / h);
            final float min = 0.05f;
            if (code == 10) e.cy0 = Math.min(ny, e.cy1 - min);
            else if (code == 11) e.cy1 = Math.max(ny, e.cy0 + min);
            else if (code == 12) e.cx0 = Math.min(nx, e.cx1 - min);
            else if (code == 13) e.cx1 = Math.max(nx, e.cx0 + min);
        }

        /** corner handle of the SELECTED element under the finger (screen coords): {index, cornerCode} or null */
        private int[] cornerHit(float px, float py) {
            if (sel < 0) return null;
            float r = Ux.dp(26);
            Watermark.El e = t.els.get(sel);
            float x = panX + zoom * (card.left + e.x * card.width());
            float y = panY + zoom * (card.top + e.y * card.height());
            float w = zoom * e.w * card.width(), h = zoom * e.h * card.height();
            float[][] cs = {{x, y}, {x + w, y}, {x, y + h}, {x + w, y + h}};
            int[] codes = {2, 3, 4, 5};
            for (int k = 0; k < 4; k++) {
                if (Math.abs(px - cs[k][0]) <= r && Math.abs(py - cs[k][1]) <= r) return new int[]{sel, codes[k]};
            }
            return null;
        }

        /** 40=top 41=bottom 42=left 43=right: one edge follows the finger, the opposite edge stays */
        private int rectEdgeHit(float px, float py) {
            if (sel < 0) return 0;
            Watermark.El e = t.els.get(sel);
            if (!"rect".equals(e.type)) return 0;
            float x = panX + zoom * (card.left + e.x * card.width());
            float y = panY + zoom * (card.top + e.y * card.height());
            float w = zoom * e.w * card.width(), h = zoom * e.h * card.height();
            float r = Ux.dp(24), keep = Ux.dp(16);
            if (Math.abs(py - y) <= r && Math.abs(px - (x + w / 2)) <= Math.max(0, w / 2 - keep)) return 40;
            if (Math.abs(py - (y + h)) <= r && Math.abs(px - (x + w / 2)) <= Math.max(0, w / 2 - keep)) return 41;
            if (Math.abs(px - x) <= r && Math.abs(py - (y + h / 2)) <= Math.max(0, h / 2 - keep)) return 42;
            if (Math.abs(px - (x + w)) <= r && Math.abs(py - (y + h / 2)) <= Math.max(0, h / 2 - keep)) return 43;
            return 0;
        }

        private void resizeRectEdge(Watermark.El e, int code, float px, float py) {
            float cw = Math.max(1f, card.width()), ch = Math.max(1f, card.height());
            if (snap) {                    // same 1/50 card grid as the corner resize / move drag
                px = snapEdge(px, card.left, cw, cw);
                py = snapEdge(py, card.top, ch, ch);
            }
            float x0 = rl, y0 = rt, x1 = rr, y1 = rb0;
            float minW = Math.max(Ux.dp(8), cw / 50f), minH = Math.max(Ux.dp(8), ch / 50f);
            if (code == 40) y0 = Math.min(py, y1 - minH);
            else if (code == 41) y1 = Math.max(py, y0 + minH);
            else if (code == 42) x0 = Math.min(px, x1 - minW);
            else if (code == 43) x1 = Math.max(px, x0 + minW);
            float nw = (x1 - x0) / cw, nh = (y1 - y0) / ch;
            e.x = clampKeepCorner((x0 - card.left) / cw, nw);
            e.y = clampKeepCorner((y0 - card.top) / ch, nh);
            e.w = nw;
            e.h = nh;
        }

        /** 2=TL 3=TR 4=BL 5=BR; the opposite corner stays anchored */
        private void resize(Watermark.El e, int corner, float px, float py) {
            float ax = (corner == 2 || corner == 4) ? rr : rl;
            float ay = (corner == 2 || corner == 3) ? rb0 : rt;
            float w0 = Math.max(1f, rr - rl), h0 = Math.max(1f, rb0 - rt);
            // follow the finger on BOTH axes (aspect-locked elements used to react to the X drag only,
            // which made the handle feel sluggish / short)
            float fx = Math.max(0f, Math.abs(px - ax) - 0f) / w0;
            float fy = Math.max(0f, Math.abs(py - ay) - 0f) / h0;
            float f = Math.max(fx, fy);
            if ("text".equals(e.type)) {
                e.size = Math.max(0.006f, Math.min(0.4f, startSize * f));
                float[] wh = measureSize(e);                     // hug the new glyphs
                float cw = Math.max(1f, card.width()), ch = Math.max(1f, card.height());
                // place from the anchor corner directly (no reliance on alignment), so the opposite handle never moves.
                // rr/rl/rt/rb0 are VIEW pixels: subtract the card origin before normalising.
                float nx = (corner == 2 || corner == 4) ? rr - wh[0] : rl;
                float ny = (corner == 2 || corner == 3) ? rb0 - wh[1] : rt;
                e.x = (nx - card.left) / cw;
                e.y = (ny - card.top) / ch;
                e.w = wh[0] / cw;
                e.h = wh[1] / ch;
                return;
            }
            if ("line".equals(e.type) || "logo".equals(e.type) || "rect".equals(e.type)) {   // no ratio lock
                float cw = Math.max(1f, card.width()), ch = Math.max(1f, card.height());
                float nw = Math.max(Ux.dp(8), w0 * Math.max(0.02f, fx));
                float nh = Math.max(Ux.dp(8), h0 * Math.max(0.02f, fy));
                if (snap) {
                    nw = Math.max(cw / 50f, Math.round(nw / (cw / 50f)) * (cw / 50f));
                    nh = Math.max(ch / 50f, Math.round(nh / (ch / 50f)) * (ch / 50f));
                }
                float x0 = (corner == 2 || corner == 4) ? ax - nw : ax;
                float y0 = (corner == 2 || corner == 3) ? ay - nh : ay;
                float nwN = nw / cw, nhN = nh / ch;
                e.x = clampKeepCorner((x0 - card.left) / cw, nwN);
                e.y = clampKeepCorner((y0 - card.top) / ch, nhN);
                e.w = nwN;
                e.h = nhN;
                return;
            }
            float ar0 = "photo".equals(e.type) ? 1.5f : w0 / h0;   // 135 frame is not resizable in ratio
            float nw = Math.max(Ux.dp(10), w0 * f);
            if (snap) {                                        // quantise the new SIZE to the 1/50 grid
                float step = Math.max(1f, card.width() / 50f);
                nw = Math.max(step, Math.round(nw / step) * step);
            }
            float nh = Math.max(Ux.dp(10), nw / ar0);
            float x0 = (corner == 2 || corner == 4) ? ax - nw : ax;
            float y0 = (corner == 2 || corner == 3) ? ay - nh : ay;
            float nwN = nw / Math.max(1f, card.width()), nhN = nh / Math.max(1f, card.height());
            e.x = clampKeepCorner((x0 - card.left) / Math.max(1f, card.width()), nwN);
            e.y = clampKeepCorner((y0 - card.top) / Math.max(1f, card.height()), nhN);
            e.w = nwN;
            e.h = nhN;
        }

        Bitmap exportCard(int targetW) {
            if (t == null) return null;
            int w = Math.max(2, targetW), h = Math.max(2, Math.round(w / t.ar));
            Bitmap bm = null;
            try {
                bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bm);
                RectF save = new RectF(card);
                card.set(0, 0, w, h);
                cv.drawColor(t.bg);
                toneCache = null;
                for (int i = 0; i < t.els.size(); i++) drawEl(cv, t.els.get(i), false);
                card.set(save);
            } catch (Throwable ex) {
                if (bm != null) { bm.recycle(); bm = null; }
            }
            return bm;
        }
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** elements may overhang EVERY edge (negative x/y included) but never leave the canvas completely:
     *  a small sliver — and therefore at least one corner — always stays inside. */
    private static float clampKeepCorner(float v, float size) { return Watermark.clampKeep(v, size); }
}
