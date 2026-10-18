package com.openfilm6k.host;

import android.app.Activity;
import android.content.Context;
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
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14));
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
    static android.app.Dialog activeDlg;          // the one app dialog currently shown (arbiter)
    static boolean installing;                    // data install running or its result dialog is up

    static boolean dlgBusy() { return activeDlg != null && activeDlg.isShowing(); }

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
        return winDlg(a, title, body, false);
    }

    /** same chrome with a ✕ in the title bar (permission dialog) */
    static android.app.Dialog winDlgX(Activity a, String title, android.view.View body) {
        return winDlg(a, title, body, true);
    }

    static android.app.Dialog winDlg(final Activity a, String title, android.view.View body, boolean close) {
        final android.app.Dialog dlg = new android.app.Dialog(a);
        dlg.setCanceledOnTouchOutside(false);   // no dialog may be dismissed by tapping outside
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
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14)); t.setPadding(Ux.dp(8), Ux.dp(4), Ux.dp(8), Ux.dp(4));
        if (close) {
            android.widget.LinearLayout trow = new android.widget.LinearLayout(a);
            trow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            trow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            trow.addView(t, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
            android.widget.Button x = new android.widget.Button(a); x.setText("✕"); Ux.styleButton(x);
            x.setMinWidth(0); x.setMinimumWidth(0); x.setPadding(0, 0, Ux.dp(4), Ux.dp(4));
            x.setOnClickListener(new android.view.View.OnClickListener() { public void onClick(android.view.View v) {
                try { dlg.dismiss(); } catch (Throwable ig) {}
            }});
            trow.addView(x, new android.widget.LinearLayout.LayoutParams(Ux.dp(34), Ux.dp(34)));
            bar.addView(trow, new android.widget.LinearLayout.LayoutParams(-1, -2));
        } else {
            bar.addView(t);
        }
        android.widget.LinearLayout bodyWrap = new android.widget.LinearLayout(a);
        bodyWrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        bodyWrap.setBackgroundColor(Ux.FACE);
        bodyWrap.setPadding(Ux.dp(12), Ux.dp(12), Ux.dp(12), Ux.dp(12));
        bodyWrap.addView(body);
        box.addView(bar);
        box.addView(bodyWrap);
        dlg.setContentView(box);
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) { if (activeDlg == dlg) activeDlg = null; }
        });
        return dlg;
    }

    /** permission warning (win98) -> system grant screen; install (win98, progress) when data is missing */
    public static void firstRunFlow(final Activity a) {
        if (installing) return;   // the install chain owns the dialogs until its result is dismissed
        if (!storageGranted(a)) {
            if (permDlg != null && permDlg.isShowing()) return;
            android.widget.LinearLayout b = new android.widget.LinearLayout(a);
            b.setOrientation(android.widget.LinearLayout.VERTICAL);
            TextView msg = new TextView(a);
            msg.setText(L.s("permMsg")); msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14)); msg.setTextColor(Ux.TXT);
            android.widget.Button ok = new android.widget.Button(a);
            Ux.styleButton(ok); ok.setText(L.s("btnOk"));
            ok.setOnClickListener(new android.view.View.OnClickListener() { public void onClick(android.view.View v) {
                try { permDlg.dismiss(); } catch (Throwable ig) {}
                requestStorage(a);
            }});
            android.widget.LinearLayout row = new android.widget.LinearLayout(a);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.RIGHT);
            row.addView(ok);
            b.addView(msg);
            b.addView(row);
            permDlg = winDlg(a, L.s("permTitle"), b);
            activeDlg = permDlg;
            permDlg.show();
            return;
        }
        if (filmsMissing() || Server.versionNeedsInstall(a)) showInstall(a);   // first run or a new APK version: restate official data
    }

    // ---- keep-alive onboarding: accessibility service + battery whitelist ----

    private static android.app.Dialog keepDlg;
    private static Context appCtx;
    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());
    private static int keepPoll;
    private static final Runnable keepPollTick = new Runnable() { public void run() {
        if (keepDlg == null || !keepDlg.isShowing()) return;
        if (appCtx != null && battIgnored(appCtx)) { dismissKeep(); return; }
        if (++keepPoll < 40) UI.postDelayed(this, 500);   // ~20 s max
    }};

    static void dismissKeep() {
        if (keepDlg != null) { try { keepDlg.dismiss(); } catch (Throwable ig) {} }
        keepDlg = null;
        if (activeDlg != null && !activeDlg.isShowing()) activeDlg = null;
    }
    private static android.app.Dialog installDlg, installResultDlg;   // chain guard for the permission dialog

    static boolean a11yEnabled(Context c) {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null) return false;
            java.util.List<android.accessibilityservice.AccessibilityServiceInfo> l =
                    am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            String want = new android.content.ComponentName(c, HostAccessibility.class).flattenToString();
            for (android.accessibilityservice.AccessibilityServiceInfo i : l) {
                if (i == null) continue;
                String id = i.getId();
                if (id != null && (id.equals(want)
                        || id.equals("com.openfilm6k.host/com.openfilm6k.host.HostAccessibility"))) return true;
                android.content.pm.ResolveInfo ri = i.getResolveInfo();
                if (ri != null && ri.serviceInfo != null
                        && c.getPackageName().equals(ri.serviceInfo.packageName)
                        && HostAccessibility.class.getName().equals(ri.serviceInfo.name)) return true;
            }
        } catch (Throwable t) {}
        return false;
    }

    static boolean battIgnored(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 23) return true;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) { return false; }
    }

    static void requestA11y(Activity a) {
        if (a == null) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.content.Intent i = new android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                i.putExtra(android.content.Intent.EXTRA_COMPONENT_NAME,
                        new android.content.ComponentName(a, HostAccessibility.class).flattenToString());
                a.startActivity(i);
                return;
            }
        } catch (Throwable t) {}
        try { a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
        catch (Throwable t) { toast("a11y settings: " + t); }
    }

    static void requestBatt(Activity a) {
        if (a == null) return;
        try {
            android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return;
        } catch (Throwable t) {}
        try { a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
        catch (Throwable t) { toast("battery settings: " + t); }
    }

    /** the only required permission: exemption from battery optimisation */
    static void requestAll(Activity a) {
        if (a == null) return;
        if (!battIgnored(a)) requestBatt(a);
    }

    static void permResume(Activity a) {   // returning from the battery screen: close the prompt once granted
        if (a == null) return;
        final Context app = a.getApplicationContext();
        appCtx = app;
        if (keepDlg == null) return;
        if (battIgnored(app)) { dismissKeep(); return; }
        for (final int d : new int[]{400, 900, 1600}) {   // slow system write: re-check a few times
            UI.postDelayed(new Runnable() { public void run() {
                if (keepDlg != null && battIgnored(app)) dismissKeep();
            }}, d);
        }
    }

    /** recommended keep-alive permission; only after storage/install dialogs are gone */
    static void permFlow(final Activity a) {
        if (a == null || a.isFinishing()) return;
        if (battIgnored(a)) return;
        if (dlgBusy() || installing) return;          // one app dialog at a time; install chain first
        if (permDlg != null && permDlg.isShowing()) return;          // chain: storage -> install -> permissions
        if (installDlg != null && installDlg.isShowing()) return;
        if (installResultDlg != null && installResultDlg.isShowing()) return;
        if (keepDlg != null && keepDlg.isShowing()) return;          // never stack
        try {
            android.widget.LinearLayout b = new android.widget.LinearLayout(a);
            b.setOrientation(android.widget.LinearLayout.VERTICAL);
            TextView msg = new TextView(a);
            msg.setText(L.s("keepMsg"));
            msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14));
            msg.setTextColor(Ux.TXT);
            b.addView(msg);
            android.widget.Button later = new android.widget.Button(a);
            Ux.styleButton(later); later.setText(L.s("keepLater"));
            android.widget.Button grant = new android.widget.Button(a);
            Ux.styleButton(grant); grant.setText(L.s("keepGrant"));
            android.widget.LinearLayout row = new android.widget.LinearLayout(a);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.RIGHT);
            android.widget.LinearLayout.LayoutParams rlp = new android.widget.LinearLayout.LayoutParams(-1, -2);
            rlp.topMargin = Ux.dp(12);
            android.widget.LinearLayout.LayoutParams blp = new android.widget.LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = Ux.dp(8);
            row.addView(later);
            row.addView(grant, blp);
            b.addView(row, rlp);
            final android.app.Dialog[] self = new android.app.Dialog[1];
            later.setOnClickListener(new android.view.View.OnClickListener() { public void onClick(android.view.View v) {
                try { self[0].dismiss(); } catch (Throwable ig) {}
            }});
            grant.setOnClickListener(new android.view.View.OnClickListener() { public void onClick(android.view.View v) {
                try { self[0].dismiss(); } catch (Throwable ig) {}
                requestBatt(a);   // battery exemption only
            }});
            self[0] = winDlgX(a, L.s("keepTitle"), b);   // ✕ top-right in the navy title bar
            keepDlg = self[0];
            activeDlg = keepDlg;
            appCtx = a.getApplicationContext();
            keepDlg.show();
            keepPoll = 0;
            UI.removeCallbacks(keepPollTick);
            UI.postDelayed(keepPollTick, 500);
        } catch (Throwable t) { say("permFlow " + t); }
    }

    /** request storage access: API<30 runtime grant, API30+ the All-Files-Access settings screen (with fallback) */
    static void requestStorage(Activity a) {
        if (a == null) return;
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
    }

    /** win98 progress dialog; incremental install — only missing files are written */
    public static void showInstall(final Activity a) {
        if (installing) return;   // never start a second install/result dialog
        installing = true;
        android.widget.LinearLayout b = new android.widget.LinearLayout(a);
        b.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView msg = new TextView(a);
        msg.setText(L.s("dataInstalling")); msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(14)); msg.setTextColor(Ux.TXT);
        final android.widget.ProgressBar pb = new android.widget.ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(false); pb.setMax(1);
        pb.setProgressDrawable(Ux.thinTrack98());   // win98 sunken groove — the framework default has rounded caps
        b.addView(msg);
        b.addView(pb, new android.widget.LinearLayout.LayoutParams(-1, Ux.dp(18)));
        final android.app.Dialog dlg = winDlg(a, "OpenFilm6K", b);
        installDlg = dlg;
        dlg.setCancelable(false);
        activeDlg = dlg;
        dlg.show();
        final android.app.Dialog[] resDlg = new android.app.Dialog[1];
        final boolean[] done = {false};   // Server.installAssets.onDone must only produce one result dialog
        new Thread(new Runnable() { public void run() {
            try { Server.get().startForInstall(a.getApplicationContext()); } catch (Throwable ig) {}
            Server.installAssets(new Server.InstallReporter() {
                public void onProgress(final int done, final int total) {
                    a.runOnUiThread(new Runnable() { public void run() { pb.setMax(Math.max(1, total)); pb.setProgress(done); } });
                }
                public void onDone(final int copied, final String error) {
                    if (done[0]) return;
                    done[0] = true;
                    a.runOnUiThread(new Runnable() { public void run() {
                        try { dlg.dismiss(); } catch (Throwable ig) {}
                        installDlg = null;
                        // result dialog: success count or the full error log for debugging
                        android.widget.LinearLayout rb = new android.widget.LinearLayout(a);
                        rb.setOrientation(android.widget.LinearLayout.VERTICAL);
                        TextView rm = new TextView(a);
                        rm.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ux.ts(copied >= 0 ? 14 : 11));
                        rm.setTextColor(Ux.TXT);
                        if (copied >= 0) rm.setText(L.s("installDone") + copied + L.s("filesUnit"));
                        else rm.setText(error == null ? "?" : error);
                        android.widget.ScrollView sc = new android.widget.ScrollView(a);
                        sc.addView(rm);
                        rb.addView(sc, new android.widget.LinearLayout.LayoutParams(-1, copied >= 0 ? -2 : Ux.dp(220)));
                        android.widget.Button ok = new android.widget.Button(a);
                        Ux.styleButton(ok); ok.setText(L.s("btnOk"));
                        ok.setOnClickListener(new android.view.View.OnClickListener() { public void onClick(android.view.View v) {
                            try { resDlg[0].dismiss(); } catch (Throwable ig) {}   // cleanup happens once in onDismiss
                        }});
                        android.widget.LinearLayout row = new android.widget.LinearLayout(a);
                        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.RIGHT);
                        row.addView(ok);
                        rb.addView(row);
                        final android.app.Dialog res = winDlg(a, copied >= 0 ? "OpenFilm6K" : L.s("installFail"), rb);
                        resDlg[0] = res;
                        installResultDlg = res;
                        activeDlg = res;
                        res.setCancelable(false);   // only OK ends the install phase
                        final boolean[] finished = {false};
                        res.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                            public void onDismiss(android.content.DialogInterface d) {
                                if (activeDlg == res) activeDlg = null;
                                if (finished[0]) return;
                                finished[0] = true;
                                installResultDlg = null;
                                installing = false;            // cleared exactly once, on dismissal
                                if (copied > 0 && a instanceof EditorActivity)
                                    ((EditorActivity) a).recreate();   // rebuild after the data existed
                                else permFlow(a);                      // chain: install done -> permissions
                            }
                        });
                        res.show();
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
