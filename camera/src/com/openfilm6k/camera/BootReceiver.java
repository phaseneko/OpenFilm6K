package com.openfilm6k.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** on boot: relaunch the camera app only if it was foreground when the previous session ended */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        boolean flag = false;
        try { flag = context.getSharedPreferences("of6k", 0).getBoolean("resume_app", false); } catch (Throwable t) {}
        try { Logger.log("boot: rx action=" + a + " resume=" + flag); } catch (Throwable t) {}
        try {
            if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                    && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) return;
            if (!flag) return;
            schedule(context, 15000);   // firmware may need a moment before an activity start succeeds
            schedule(context, 35000);   // retry once; a start while already foreground is a harmless no-op
        } catch (Throwable t) {}
    }

    private static void schedule(Context c, long delayMs) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent i = new Intent(c, LaunchReceiver.class);
            i.setAction("com.openfilm6k.camera.LAUNCH");
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    c, (int) (delayMs / 1000), i, android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            am.set(android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (Throwable t) {}
    }
}
