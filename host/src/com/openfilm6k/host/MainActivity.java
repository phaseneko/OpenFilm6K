package com.openfilm6k.host;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    static MainActivity inst;
    static TextView status;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        status = new TextView(this);
        status.setTextSize(14);
        status.setText("OpenFilm6K starting…\n");
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.Button edit = new android.widget.Button(this);
        edit.setText(L.s("editHint"));
        edit.setOnClickListener(new android.view.View.OnClickListener() {
            public void onClick(android.view.View v) { startActivity(new Intent(MainActivity.this, EditorActivity.class)); }
        });
        wrap.addView(edit);
        wrap.addView(status);
        setContentView(wrap);
        startForegroundService(new Intent(this, HostService.class));
    }
    @Override protected void onResume() { super.onResume(); }
    @Override protected void onPause() { super.onPause(); }

    // ---- first-run / data-install flow (called from EditorActivity, the real launcher) ----

    private static android.app.Dialog permDlg;

    static boolean storageGranted(Activity a) {
        try {   // API 30+: All-Files-Access toggle
            java.lang.reflect.Method esm = android.os.Environment.class.getMethod("isExternalStorageManager");
            return (Boolean) esm.invoke(null);
        } catch (Throwable t) {}
        if (android.os.Build.VERSION.SDK_INT >= 23)   // API 23-29: plain runtime grant
            return a.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return true;
    }

    static boolean filmsMissing() {
        String[] f = new java.io.File("/sdcard/OpenFilm6K/pipelines").list();
        return f == null || f.length == 0;
    }

    /** win98 dialog chrome: raised bevel frame + navy recessed title bar, matching the editor */
    static android.app.Dialog winDlg(Activity a, String title, android.view.View body) {
        final android.app.Dialog dlg = new android.app.Dialog(a);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));   // kill the system's rounded dialog backdrop: the window takes the square frame98 shape
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int fp = Ux.dp(2);
        box.setPadding(fp, fp, fp, fp);
        box.setBackgroundDrawable(Ux.frame98());
        android.widget.LinearLayout bar = new android.widget.LinearLayout(a);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        bar.setBackgroundDrawable(Ux.navySunkenBar());
        TextView t = new TextView(a);
        t.setText(title); t.setTextColor(0xFFFFFFFF);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-bold", 0));
        t.setTextSize(14); t.setPadding(Ux.dp(8), Ux.dp(4), Ux.dp(8), Ux.dp(4));
        bar.addView(t);
        android.widget.LinearLayout bodyWrap = new android.widget.LinearLayout(a);
        bodyWrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        bodyWrap.setBackgroundColor(Ux.FACE);
        bodyWrap.setPadding(Ux.dp(12), Ux.dp(12), Ux.dp(12), Ux.dp(12));
        bodyWrap.addView(body);
        box.addView(bar);
        box.addView(bodyWrap);
        dlg.setContentView(box);
        return dlg;
    }

    /** permission warning (win98) -> system grant screen; install (win98, progress) when data is missing */
    public static void firstRunFlow(final Activity a) {
        if (!storageGranted(a)) {
            if (permDlg != null && permDlg.isShowing()) return;
            android.widget.LinearLayout b = new android.widget.LinearLayout(a);
            b.setOrientation(android.widget.LinearLayout.VERTICAL);
            TextView msg = new TextView(a);
            msg.setText(L.s("permMsg")); msg.setTextSize(14); msg.setTextColor(Ux.TXT);
            android.widget.Button ok = new android.widget.Button(a);
            Ux.styleButton(ok); ok.setText(L.s("btnOk"));
            ok.setOnClickListener(new android.view.View.OnClickListener() { public void onClick(android.view.View v) {
                try { permDlg.dismiss(); } catch (Throwable ig) {}
                if (android.os.Build.VERSION.SDK_INT < 30) {
                    a.requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
                    return;
                }
                try {
                    a.startActivity(new android.content.Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                            android.net.Uri.parse("package:" + a.getPackageName())));
                } catch (Throwable t) {
                    try { a.startActivity(new android.content.Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION")); } catch (Throwable t2) { toast("open settings: " + t2); }
                }
            }});
            android.widget.LinearLayout row = new android.widget.LinearLayout(a);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.RIGHT);
            row.addView(ok);
            b.addView(msg);
            b.addView(row);
            permDlg = winDlg(a, L.s("permTitle"), b);
            permDlg.show();
            return;
        }
        if (filmsMissing() || Server.versionNeedsInstall(a)) showInstall(a);   // first run or a new APK version: restate official data
    }

    /** win98 progress dialog; incremental install — only missing files are written */
    public static void showInstall(final Activity a) {
        android.widget.LinearLayout b = new android.widget.LinearLayout(a);
        b.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView msg = new TextView(a);
        msg.setText(L.s("dataInstalling")); msg.setTextSize(14); msg.setTextColor(Ux.TXT);
        final android.widget.ProgressBar pb = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(false); pb.setMax(1);
        pb.setProgressDrawable(Ux.thinTrack98());   // win98 sunken groove — the framework default has rounded caps
        b.addView(msg);
        b.addView(pb, new android.widget.LinearLayout.LayoutParams(-1, Ux.dp(18)));
        final android.app.Dialog dlg = winDlg(a, "OpenFilm6K", b);
        dlg.setCancelable(false);
        dlg.show();
        final android.app.Dialog[] resDlg = new android.app.Dialog[1];
        new Thread(new Runnable() { public void run() {
            try { Server.get().startForInstall(a.getApplicationContext()); } catch (Throwable ig) {}
            Server.installAssets(new Server.InstallReporter() {
                public void onProgress(final int done, final int total) {
                    a.runOnUiThread(new Runnable() { public void run() { pb.setMax(Math.max(1, total)); pb.setProgress(done); } });
                }
                public void onDone(final int copied, final String error) {
                    a.runOnUiThread(new Runnable() { public void run() {
                        try { dlg.dismiss(); } catch (Throwable ig) {}
                        // result dialog: success count or the full error log for debugging
                        android.widget.LinearLayout rb = new android.widget.LinearLayout(a);
                        rb.setOrientation(android.widget.LinearLayout.VERTICAL);
                        TextView rm = new TextView(a);
                        rm.setTextSize(copied >= 0 ? 14 : 11);
                        rm.setTextColor(Ux.TXT);
                        if (copied >= 0) rm.setText(L.s("installDone") + copied + L.s("filesUnit"));
                        else rm.setText(error == null ? "?" : error);
                        android.widget.ScrollView sc = new android.widget.ScrollView(a);
                        sc.addView(rm);
                        rb.addView(sc, new android.widget.LinearLayout.LayoutParams(-1, copied >= 0 ? -2 : Ux.dp(220)));
                        android.widget.Button ok = new android.widget.Button(a);
                        Ux.styleButton(ok); ok.setText(L.s("btnOk"));
                        ok.setOnClickListener(new android.view.View.OnClickListener() { public void onClick(android.view.View v) {
                            try { resDlg[0].dismiss(); } catch (Throwable ig) {}
                            if (copied > 0 && a instanceof EditorActivity)
                                ((EditorActivity) a).recreate();   // the editor was built before the data existed — rebuild everything
                        }});
                        android.widget.LinearLayout row = new android.widget.LinearLayout(a);
                        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.RIGHT);
                        row.addView(ok);
                        rb.addView(row);
                        resDlg[0] = winDlg(a, copied >= 0 ? "OpenFilm6K" : L.s("installFail"), rb);
                        resDlg[0].show();
                    } });
                }
            });
        }}, "filminstall").start();
    }

    static void toast(final String s) {
        final MainActivity m = inst;
        if (m == null) return;
        m.runOnUiThread(new Runnable() { public void run() {
            try { Toast.makeText(m.getApplicationContext(), s, Toast.LENGTH_LONG).show(); } catch (Throwable ig) {}
        }});
    }

    static void say(final String s) {
        final MainActivity m = inst;
        if (m == null) return;
        m.runOnUiThread(new Runnable() { public void run() {
            try { status.append(s + "\n"); } catch (Throwable ig) {}
            try { Toast.makeText(m.getApplicationContext(), s, Toast.LENGTH_SHORT).show(); } catch (Throwable ig) {}
        }});
    }
}
