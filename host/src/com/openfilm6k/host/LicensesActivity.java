package com.openfilm6k.host;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** full-screen Win98 licenses viewer: a list of bundled components, tap one to read its full license text. */
public class LicensesActivity extends Activity {

    /** {display name, license short name, asset file} — component names stay fixed; license names are constants */
    static final String[][] ENTRIES = {
        {"OpenFilm6K",        "GPL-3.0",                "openfilm6k.txt"},
        {"libjpeg-turbo",     "BSD-3-Clause / IJG",     "libjpeg-turbo.txt"},
        {"IJG JPEG library",  "IJG License",            "ijg-libjpeg.txt"},
        {"DSEG",              "SIL OFL 1.1",            "dseg.txt"},
        {"Liberation Sans",   "SIL OFL 1.1",            "liberation-sans.txt"},
        {"Archivo Black",     "SIL OFL 1.1",            "archivo-black.txt"},
        {"Smiley Sans",       "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"Zhuque Fangsong",   "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"Poppins",           "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"Playfair Display",  "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"Libre Baskerville", "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"Lora",              "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"EB Garamond",       "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"Bebas Neue",        "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"Oswald",            "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"JetBrains Mono",    "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"Zhi Mang Xing",     "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"Great Vibes",       "SIL OFL 1.1",            "ofl-fonts.txt"},
        {"WenQuanYi Zen Hei", "GPL-2.0 + font exception", "wqy-zen-hei.txt"},
    };

    private LinearLayout content;   // the area below the title bar: list view or full-text view
    private TextView title;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        getWindow().setStatusBarColor(Ux.NAVY);
        android.view.View dcv = getWindow().getDecorView();
        dcv.setSystemUiVisibility(dcv.getSystemUiVisibility() & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setNavigationBarColor(Ux.FACE);

        LinearLayout frame = new LinearLayout(this);            // win98 window frame
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setBackgroundDrawable(Ux.frame98());
        frame.setPadding(Ux.dp(2), Ux.dp(2), Ux.dp(2), Ux.dp(2));

        LinearLayout tb = new LinearLayout(this);               // title bar
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setBackground(Ux.navySunkenBar());
        tb.setGravity(Gravity.CENTER_VERTICAL);
        tb.setPadding(Ux.dp(6), Ux.dp(1), Ux.dp(1), Ux.dp(1));
        title = new TextView(this);
        title.setText(L.s("licensesTitle"));
        title.setTextColor(0xFFFFFFFF);
        title.getPaint().setFakeBoldText(true);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14));
        tb.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button x = new Button(this); x.setText("✕");
        Ux.styleButton(x);
        x.setPadding(0, 0, Ux.dp(5), Ux.dp(5));
        x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); }});
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(Ux.dp(34), Ux.dp(34));
        xlp.gravity = Gravity.CENTER_VERTICAL;
        tb.addView(x, xlp);
        frame.addView(tb, new LinearLayout.LayoutParams(-1, -2));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Ux.FACE);
        content.setPadding(Ux.dp(8), Ux.dp(8), Ux.dp(8), Ux.dp(8));
        frame.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));

        showList();
        setContentView(frame);   // buttons are styled explicitly; no themeTree (it would re-center the list rows)
    }

    /** entry list: one Win98 button per component */
    private void showList() {
        title.setText(L.s("licensesTitle"));
        content.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < ENTRIES.length; i++) {
            final int idx = i;
            Button row = new Button(this);
            row.setText(ENTRIES[i][0] + "   —   " + ENTRIES[i][1]);
            Ux.styleButton(row);
            row.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showText(idx); }});
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ux.dp(4);
            list.addView(row, lp);
        }
        sv.addView(list, new LinearLayout.LayoutParams(-1, -2));
        content.addView(sv, new LinearLayout.LayoutParams(-1, -1));
    }

    /** full text of one entry: back-to-list button + scrollable monospace text */
    private void showText(int idx) {
        title.setText(ENTRIES[idx][0]);
        content.removeAllViews();
        Button back = new Button(this);
        back.setText("\u2190 " + L.s("licensesBack"));
        Ux.styleButton(back);
        back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showList(); }});
        content.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView tv = new TextView(this);
        tv.setText(readAsset(ENTRIES[idx][2]));
        tv.setTextColor(Ux.TXT);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(12));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(Ux.dp(8), Ux.dp(8), Ux.dp(8), Ux.dp(8));
        ScrollView sv = new ScrollView(this);
        sv.setBackground(Ux.sunkenField());
        sv.addView(tv);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = Ux.dp(8);
        content.addView(sv, slp);
    }

    /** read a bundled license asset (UTF-8) */
    private String readAsset(String name) {
        try {
            java.io.InputStream in = getAssets().open("licenses/" + name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "Missing asset: licenses/" + name;
        }
    }
}
