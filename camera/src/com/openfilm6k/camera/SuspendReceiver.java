package com.openfilm6k.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** firmware suspend/power-off signal: let the camera go so the lens can retract */
public class SuspendReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try { Logger.log("suspend: rx"); } catch (Throwable t) {}
        try { MainActivity.onFirmwareSuspend(context); } catch (Throwable t) {}
    }
}
