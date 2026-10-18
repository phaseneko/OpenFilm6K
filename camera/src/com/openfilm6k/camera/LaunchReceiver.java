package com.openfilm6k.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** alarm target: bring the camera activity to the front (no-op if it is already there) */
public class LaunchReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try { Logger.log("boot: launch alarm"); } catch (Throwable t) {}
        try {
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable t) {
            try { Logger.log("boot: launch EX " + t); } catch (Throwable ig) {}
        }
    }
}
