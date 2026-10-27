package com.openfilm6k.host;

import android.content.Context;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;

/** Listens for the PS135 camera's UDP beacon on IPv4 port 8765. */
public final class CameraBeacon {
    public static final int PORT = 8765;
    private static final String TAG = "CameraBeacon";
    private static volatile boolean running;
    private static DatagramChannel channel;

    private CameraBeacon() {}

    public static synchronized void start(final Context c) {
        if (running) return;
        running = true;
        final Context app = c.getApplicationContext();
        Thread t = new Thread(new Runnable() { public void run() {
            DatagramChannel ch = null;
            try {
                ch = DatagramChannel.open(StandardProtocolFamily.INET);
                ch.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
                ch.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT));
                ch.configureBlocking(true);
                channel = ch;
                Log.i(TAG, "IPv4 UDP listening on 0.0.0.0:" + PORT);
                ByteBuffer buf = ByteBuffer.allocate(256);
                while (running) {
                    buf.clear();
                    SocketAddress src = ch.receive(buf);
                    if (src == null) continue;
                    buf.flip();
                    byte[] data = new byte[buf.remaining()];
                    buf.get(data);
                    String msg = new String(data, StandardCharsets.UTF_8).trim();
                    String ip = ((InetSocketAddress) src).getAddress().getHostAddress();
                    Log.i(TAG, "packet from " + ip + ": " + msg);
                    if (!msg.startsWith("PS135")) continue;
                    RewindSync.noteBeacon(app, ip, msg);
                }
            } catch (Throwable t) {
                Log.e(TAG, "beacon failed", t);
                RewindSync.noteBeaconError(app, t.toString());
            } finally {
                running = false;
                try { if (ch != null) ch.close(); } catch (Throwable ig) {}
                channel = null;
            }
        } }, "camera-beacon");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stop() {
        running = false;
        try { if (channel != null) channel.close(); } catch (Throwable ig) {}
        channel = null;
    }
}
