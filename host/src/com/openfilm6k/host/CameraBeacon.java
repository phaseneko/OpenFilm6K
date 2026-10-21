package com.openfilm6k.host;

import android.content.Context;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/** Listens for the PS135 camera's UDP beacon on port 8765. */
public final class CameraBeacon {
    public static final int PORT = 8765;
    private static volatile boolean running;
    private static DatagramSocket sock;

    private CameraBeacon() {}

    public static synchronized void start(final Context c) {
        if (running) return;
        running = true;
        final Context app = c.getApplicationContext();
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                DatagramSocket s = new DatagramSocket(null);
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                sock = s;
                byte[] buf = new byte[256];
                while (running) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                    if (!msg.startsWith("PS135")) continue;
                    String ip = p.getAddress().getHostAddress();
                    RewindSync.noteBeacon(app, ip, msg);
                }
            } catch (Throwable t) {
                RewindSync.noteBeaconError(app, t.toString());
            } finally {
                running = false;
                try { if (sock != null) sock.close(); } catch (Throwable ig) {}
                sock = null;
            }
        } }, "camera-beacon");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stop() {
        running = false;
        try { if (sock != null) sock.close(); } catch (Throwable ig) {}
        sock = null;
    }
}
