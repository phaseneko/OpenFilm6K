package com.openfilm6k.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;

public class HostService extends Service {
    private static final Handler h = new Handler();
    private NotificationManager nm;
    private static HostService self;

    // ---- photo-inbox state: shown in the standing notification until the user taps it ----
    private static volatile String pFilm, pExpo, pTime;
    private static volatile android.graphics.Bitmap pThumb;
    private static volatile android.net.Uri pUri;

    /** called by Server after each successful ingest; updates the standing notification in place */
    public static void photoInfo(String film, String expo, String time, android.graphics.Bitmap thumb, android.net.Uri uri) {
        pFilm = film; pExpo = expo; pTime = time; pThumb = thumb; pUri = uri;
        HostService s = self;
        if (s != null) s.refresh();
    }

    private void refresh() {
        try { nm.notify(1, build()); } catch (Throwable ig) {}
    }

    /** back to the plain "camera connected/waiting" state */
    private void reset() {
        pFilm = pExpo = pTime = null; pThumb = null;
        refresh();
    }

    private final android.content.BroadcastReceiver tap = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) {
            android.net.Uri u = pUri;
            // tap opens the gallery only — the notification display is NOT changed
            // the proven open recipe (works on this phone): VIEW + callback URI + read grant,
            // no package pinning — the chooser must never see this intent
            if (u != null) {
                try {
                    Intent it = new Intent(Intent.ACTION_VIEW);
                    it.setDataAndType(u, "image/jpeg");
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    c.startActivity(it);
                } catch (Throwable t) { MainActivity.say("gallery open: " + t); }
            } else {
                try {
                    Intent lp = c.getPackageManager().getLaunchIntentForPackage("com.agui.gallery");
                    if (lp != null) c.startActivity(lp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable t2) { MainActivity.say("gallery open: " + t2); }
            }
        }
    };

    private Notification build() {
        if (pFilm != null) {
            // custom layout: thumbnail LEFT at 2x (the standard template pins largeIcon right + small)
            android.widget.RemoteViews rv = new android.widget.RemoteViews(getPackageName(), R.layout.notif_photo);
            rv.setTextViewText(R.id.title, pFilm);
            rv.setTextViewText(R.id.text, pExpo == null ? "" : pExpo);
            rv.setTextViewText(R.id.time, pTime == null ? "" : pTime);
            if (pThumb != null) rv.setImageViewBitmap(R.id.thumb, pThumb);
            Notification.Builder b = new Notification.Builder(this, "of6k")
                .setSmallIcon(animating ? BUSY[frame] : R.drawable.ic_notif)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(rv)
                .setOngoing(true);
            b.setContentIntent(android.app.PendingIntent.getBroadcast(this, 0,
                new Intent("com.openfilm6k.host.PHOTO_TAP").setPackage(getPackageName()),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE));
            return b.build();
        }
        return new Notification.Builder(this, "of6k").setSmallIcon(animating ? BUSY[frame] : R.drawable.ic_notif)
            .setContentTitle("OpenFilm6K").setOngoing(true).build();
    }

    // ---- busy icon animation: a lens ring with an orbiting focus point -------------------
    private static final int[] BUSY = {R.drawable.ic_busy_1, R.drawable.ic_busy_2, R.drawable.ic_busy_3,
                                       R.drawable.ic_busy_4, R.drawable.ic_busy_5, R.drawable.ic_busy_6};
    private volatile boolean animating = false;
    private volatile int frame = 0;
    private final Runnable animTick = new Runnable() { public void run() {
        if (!animating) return;
        frame = (frame + 1) % BUSY.length;
        try { nm.notify(1, build()); } catch (Throwable ig) {}
        h.postDelayed(this, 180);
    }};

    private void setBusy(boolean busy, final int okPhotos) {
        final HostService s = this;
        h.post(new Runnable() { public void run() {
            if (busy) {
                if (animating) return;
                animating = true;
                h.post(animTick);
            } else {
                if (!animating) return;
                animating = false;
                h.removeCallbacks(animTick);
                try { nm.notify(1, build()); } catch (Throwable ig) {}
                if (okPhotos > 0) try {   // only when this batch really produced photos
                    android.media.Ringtone r = android.media.RingtoneManager.getRingtone(s,
                            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION));
                    if (r != null) r.play();
                } catch (Throwable ig) {}
            }
        }});
    }

    private final Runnable tick = new Runnable() { public void run() {
        try { nm.notify(1, build()); } catch (Throwable ig) {}
        h.postDelayed(this, 5000);
    }};

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        SpyWatcher.sync(getApplicationContext());   // spy mode survives activity death: prefs are the truth
        CameraBeacon.start(getApplicationContext()); // PS135 UDP registration listener
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel("of6k", "OpenFilm6K", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        RenderQueue.setListener(new RenderQueue.Listener() { public void onBusyChanged(boolean busy, int ok) { setBusy(busy, ok); }});
        registerReceiver(tap, new android.content.IntentFilter("com.openfilm6k.host.PHOTO_TAP"));
    }
    @Override public int onStartCommand(Intent i, int flags, int id) {
        self = this;
        startForeground(1, build());
        Engine.get().start();
        Server.get().start(this);
        FtpServer.sync(getApplicationContext());   // FTP receiving follows the persisted ftp_on flag
        RewindSync.sync(getApplicationContext());  // PS135 Rewind sync follows the persisted rewind_on flag
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }
    @Override public void onDestroy() {
        try { unregisterReceiver(tap); } catch (Throwable ig) {}
        CameraBeacon.stop();
        self = null;
        super.onDestroy();
    }
}
