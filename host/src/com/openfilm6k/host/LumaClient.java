package com.openfilm6k.host;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Luma mode engine: manual-trigger Wi-Fi link to a Panasonic camera SoftAP, pull new photos
 *  over the cam.cgi + UPnP ContentDirectory chain, hand each file to the spy ingest pipeline,
 *  then restore the previous Wi-Fi/data network. Protocol verified against a Panasonic LUMIX
 *  (req_acc x2 with a fixed client id, playmode, CDS Browse, per-file re-auth, :50001 download). */
public class LumaClient {
    private static final String TAG = "luma";
    private static final String CID = "4D454930-0100-1000-8001-4F46364B01";   // fixed id: the camera tracks clients by it
    private static final String CNAME = "OpenFilm6K";
    private static final String BASE = "http://192.168.54.1";

    /** the camera network once the specifier link is up — per-connection routing via
     *  Network.openConnection() sidesteps bindProcessToNetwork entirely (it returns false
     *  on some Android 16 ROMs even for app-requested networks). */
    static volatile android.net.Network camNet;

    public interface Progress {
        void update(String line);
        boolean cancelled();
        /** download bookkeeping: done counts finished files out of total fresh ones */
        default void fileDone(int done, int total) {}
    }

    // ---------------- HTTP (raw, no proxy: camera link is local-only) ----------------
    private static class Resp { int code; String body; Resp(int c, String b) { code = c; body = b; } }

    private static Resp http(String url, byte[] body, Map<String, String> extra, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) (camNet != null ? camNet.openConnection(new URL(url)) : new URL(url).openConnection(Proxy.NO_PROXY));
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", "Panasonic MIL DLNA CP UPnP/1.0");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Connection", "close");
            if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            if (body != null) {
                c.setDoOutput(true);
                if (extra == null || !extra.containsKey("Content-Type")) c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
                OutputStream os = c.getOutputStream(); os.write(body); os.flush(); os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); try { in.close(); } catch (Throwable t) {} }
            return new Resp(code, bo.toString("UTF-8"));
        } catch (Throwable t) {
            return new Resp(-1, String.valueOf(t));
        } finally { if (c != null) try { c.disconnect(); } catch (Throwable t) {} }
    }

    // ---------------- Wi-Fi link ----------------
    /** join the camera SoftAP. API 29+: WifiNetworkSpecifier app-scoped network (the whole
     *  process binds to it; the phone keeps its own default network for everything else).
     *  Below 29: legacy global switch. Returns the state Object[] for restore(), or null. */
    static Object[] linkCamera(Context ctx, String ssid, String pass, Progress p) {
        if (android.os.Build.VERSION.SDK_INT >= 29) return linkSpecifier(ctx, ssid, pass, p);
        return linkLegacy(ctx, ssid, pass, p);
    }

    /** scan for the camera SSID first: confirms input + tells WPA2 vs WPA3(SAE),
     *  the same check the stock app does (scanResult.capabilities). */
    private static android.net.wifi.ScanResult scanFor(String ssid, int waitSec) {
        try {
            android.net.wifi.WifiManager wm = null;
            // any context works for scanning; caller passes application context via ctx holder
            wm = wmHolder;
            if (wm == null) return null;
            for (android.net.wifi.ScanResult sr : wm.getScanResults())
                if (ssid.equals(sr.SSID)) return sr;
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            android.content.BroadcastReceiver br = new android.content.BroadcastReceiver() {
                @Override public void onReceive(android.content.Context c, android.content.Intent it) { latch.countDown(); }
            };
            try {
                wmCtx.registerReceiver(br, new android.content.IntentFilter(android.net.wifi.WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            } catch (Throwable t) { return null; }
            try { wm.startScan(); } catch (Throwable t) {}
            try { latch.await(waitSec, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) {}
            try { wmCtx.unregisterReceiver(br); } catch (Throwable t) {}
            for (android.net.wifi.ScanResult sr : wm.getScanResults())
                if (ssid.equals(sr.SSID)) return sr;
        } catch (Throwable t) { Engine.dbg(TAG + " scan: " + t); }
        return null;
    }

    static android.net.ConnectivityManager cmHolder;
    static android.net.wifi.WifiManager wmHolder;
    static android.content.Context wmCtx;

    private static Object[] requestSpecifier(Context ctx, String ssid, String pass, boolean sae, Progress p) {
        try {
            final Object cmObj = ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) cmObj;
            android.net.wifi.WifiNetworkSpecifier.Builder b = new android.net.wifi.WifiNetworkSpecifier.Builder().setSsid(ssid);
            if (sae) b.setWpa3Passphrase(pass); else b.setWpa2Passphrase(pass);
            android.net.NetworkRequest req = new android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(b.build())
                    .build();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final android.net.Network[] net = new android.net.Network[1];
            final boolean[] dead = new boolean[1];
            final android.net.ConnectivityManager.NetworkCallback cb = new android.net.ConnectivityManager.NetworkCallback() {
                private void note(android.net.Network n, String why) {
                    net[0] = n;
                    Engine.dbg(TAG + " cb " + why);
                    if (why.equals("available")) return;   // wait for ip/caps readiness
                    camNet = n;
                    latch.countDown();                     // network usable via openConnection regardless of bind
                }
                @Override public void onAvailable(android.net.Network n) { note(n, "available"); }
                @Override public void onCapabilitiesChanged(android.net.Network n, android.net.NetworkCapabilities c) { note(n, "caps"); }
                @Override public void onLinkPropertiesChanged(android.net.Network n, android.net.LinkProperties lp) { note(n, "linkprops"); }
                @Override public void onUnavailable() { Engine.dbg(TAG + " cb unavailable"); dead[0] = true; latch.countDown(); }
                @Override public void onLost(android.net.Network n) { Engine.dbg(TAG + " cb lost"); dead[0] = true; latch.countDown(); }
            };
            p.update("REQ " + (sae ? "WPA3" : "WPA2") + " ...confirm on screen");
            cm.requestNetwork(req, cb);
            if (!latch.await(35, java.util.concurrent.TimeUnit.SECONDS) || net[0] == null) {
                try { cm.unregisterNetworkCallback(cb); } catch (Throwable t) {}
                camNet = null;
                return null;
            }
            camNet = net[0];
            if (cmHolder != null) {
                try {
                    boolean pb = cmHolder.bindProcessToNetwork(net[0]);
                    Engine.dbg(TAG + " proc bind=" + pb);
                } catch (Throwable t) { Engine.dbg(TAG + " proc bind: " + t); }
            }
            // if the app-scoped network cannot carry our sockets on this ROM
            // (EPERM at bindSocket), fall back to the system's CURRENT Wi-Fi network -
            // the specifier join has already switched the system onto the camera AP.
            try {
                java.net.HttpURLConnection probe = (java.net.HttpURLConnection) camNet.openConnection(new java.net.URL(BASE + "/cam.cgi?mode=getstate"));
                probe.setConnectTimeout(4000); probe.setReadTimeout(4000);
                int rc = probe.getResponseCode();
                Engine.dbg(TAG + " probe camNet rc=" + rc);
                probe.disconnect();
            } catch (Throwable t) {
                Engine.dbg(TAG + " probe camNet: " + t);
                android.net.Network sys = null;
                try {
                    android.net.Network act = cmHolder != null ? cmHolder.getActiveNetwork() : null;
                    if (act != null && cmHolder.getNetworkCapabilities(act).hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) sys = act;
                    if (sys == null && cmHolder != null) for (android.net.Network n2 : cmHolder.getAllNetworks())
                        if (cmHolder.getNetworkCapabilities(n2).hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) { sys = n2; break; }
                } catch (Throwable t2) { Engine.dbg(TAG + " findWifi: " + t2); }
                if (sys != null && sys != camNet) {
                    Engine.dbg(TAG + " falling back to system wifi network");
                    try { if (cmHolder != null) cmHolder.bindProcessToNetwork(sys); } catch (Throwable t3) {}
                    camNet = sys;
                }
            }
            p.update("LINK " + ssid + " (" + (sae ? "WPA3" : "WPA2") + ") ok");
            return new Object[]{cb, cmObj};
        } catch (Throwable t) {
            Engine.dbg(TAG + " reqSpec: " + t);
            return null;
        }
    }

    private static Object[] linkSpecifier(Context ctx, String ssid, String pass, Progress p) {
        cmHolder = (android.net.ConnectivityManager) ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        // active VPN hijacks LAN traffic and blocks socket binding to non-VPN networks
        // (EPERM): warn up-front instead of failing deep inside the protocol chain.
        try {
            android.net.ConnectivityManager cm0 = (android.net.ConnectivityManager) ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network an = cm0 != null ? cm0.getActiveNetwork() : null;
            if (an != null && cm0.getNetworkCapabilities(an).hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                Engine.dbg(TAG + ": VPN detected on active network");
            }
        } catch (Throwable t) { Engine.dbg(TAG + " vpn probe: " + t); }
        wmHolder = (android.net.wifi.WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wmCtx = ctx.getApplicationContext();
        android.net.wifi.ScanResult sr = scanFor(ssid, 8);
        if (sr == null) {
            p.update("ERR: SSID not found in scan — check name/location service");
            return null;
        }
        String caps = sr.capabilities == null ? "" : sr.capabilities.toUpperCase();
        boolean sae = caps.contains("SAE");
        p.update("SCAN ok: " + ssid + " [" + caps.replace("[", "").replace("]", "") + "] " + sr.frequency + "MHz ch=" + (sr.frequency > 5000 ? (sr.frequency - 5000) / 5 : (sr.frequency - 2407) / 5));
        Object[] st = requestSpecifier(ctx, ssid, pass, sae, p);
        if (st == null) {   // wrong guess or transitional network: retry with the other mode
            p.update("RETRY with " + (sae ? "WPA2" : "WPA3"));
            st = requestSpecifier(ctx, ssid, pass, !sae, p);
        }
        if (st == null) p.update("ERR: link timeout");
        return st;
    }

    @SuppressWarnings({"deprecation", "UnspecifiedRegisterReflectionFlag"})
    private static Object[] linkLegacy(Context ctx, String ssid, String pass, Progress p) {
        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return null;
        boolean wifiWasOn = wm.isWifiEnabled();
        int prevId = -1;
        try { prevId = wm.getConnectionInfo().getNetworkId(); } catch (Throwable t) {}
        if (!wifiWasOn && !wm.setWifiEnabled(true)) { p.update("ERR: cannot enable Wi-Fi"); return null; }
        for (int i = 0; i < 10 && !wm.isWifiEnabled(); i++) sleep(500);
        android.net.wifi.WifiConfiguration wc = new android.net.wifi.WifiConfiguration();
        wc.SSID = "\"" + ssid + "\"";
        wc.preSharedKey = "\"" + pass + "\"";
        int nid = wm.addNetwork(wc);
        if (nid < 0) { p.update("ERR: addNetwork"); restoreLegacy(ctx, wifiWasOn, prevId, -1); return null; }
        wm.enableNetwork(nid, true);
        try { wm.reconnect(); } catch (Throwable t) {}
        String want = "\"" + ssid + "\"";
        for (int i = 0; i < 60; i++) {
            if (p.cancelled()) { restoreLegacy(ctx, wifiWasOn, prevId, nid); return null; }
            android.net.wifi.WifiInfo ci = null;
            try { ci = wm.getConnectionInfo(); } catch (Throwable t) {}
            if (ci != null && want.equals(ci.getSSID()) && ci.getIpAddress() != 0) {
                p.update("LINK " + ssid + " ip=" + intIp(ci.getIpAddress()));
                return new Object[]{Boolean.valueOf(wifiWasOn), Integer.valueOf(prevId), Integer.valueOf(nid)};
            }
            sleep(500);
        }
        p.update("ERR: association timeout");
        restoreLegacy(ctx, wifiWasOn, prevId, nid);
        return null;
    }

    /** put the link away: specifier path unbinds + unregisters (phone network was never touched);
     *  legacy path switches back to the previous AP / turns Wi-Fi off. */
    static void restore(Context ctx, Object[] st) {
        if (st == null || st.length == 0) return;
        try {
            if (st[0] instanceof android.net.ConnectivityManager.NetworkCallback) {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) st[1];
                camNet = null;
                cm.unregisterNetworkCallback((android.net.ConnectivityManager.NetworkCallback) st[0]);
                Engine.dbg(TAG + " unbound specifier network");
                return;
            }
            restoreLegacy(ctx, (Boolean) st[0], (Integer) st[1], st.length > 2 ? (Integer) st[2] : -1);
        } catch (Throwable t) { Engine.dbg(TAG + " restore: " + t); }
    }

    private static void restoreLegacy(Context ctx, boolean wifiWasOn, int prevId, int camId) {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            if (camId >= 0) wm.removeNetwork(camId);
            if (wifiWasOn) {
                if (prevId >= 0) wm.enableNetwork(prevId, true);
                try { wm.reconnect(); } catch (Throwable t) {}
            } else {
                wm.setWifiEnabled(false);
            }
            try { wm.saveConfiguration(); } catch (Throwable t) {}
        } catch (Throwable t) { Engine.dbg(TAG + " restoreLegacy: " + t); }
    }

    private static String intIp(int little) {
        return String.format("%d.%d.%d.%d", little & 255, (little >> 8) & 255, (little >> 16) & 255, (little >> 24) & 255);
    }

    // ---------------- protocol chain ----------------
    private static boolean grantAccess(Progress p, String tag) {
        http(BASE + "/cam.cgi?mode=accctrl&type=req_acc_g", null, null, 5000);   // probe: err_param is fine
        for (int i = 0; i < 10; i++) {
            if (p.cancelled()) return false;
            Resp r = http(BASE + "/cam.cgi?mode=accctrl&type=req_acc&value=" + CID + "&value2=" + CNAME, null, null, 8000);
            String st = r.body.trim();
            int c = st.indexOf(',');
            if (c > 0) st = st.substring(0, c);
            if (st.startsWith("ok_under_research") || st.equals("err_busy")) { sleep(1000); continue; }
            if (st.equals("ok")) { Engine.dbg(TAG + " " + tag + " access ok #" + i); return true; }
            p.update("ERR: access " + st);
            return false;
        }
        p.update("ERR: access timeout");
        return false;
    }

    private static boolean initSequence(Progress p) {
        String[] seq = {"getinfo&type=capability", "getstate", "getinfo&type=curmenu", "camcmd&value=playmode"};
        for (String s : seq) {
            if (p.cancelled()) return false;
            Resp r = http(BASE + "/cam.cgi?mode=" + s, null, null, 8000);
            boolean ok = r.body.contains("<result>ok");
            Engine.dbg(TAG + " init " + s + " -> " + r.code + (ok ? " ok" : " ?"));
            if (!ok) { p.update("ERR: init " + s); return false; }
        }
        return true;
    }

    private static final String SOAP_HDRS = "urn:schemas-upnp-org:service:ContentDirectory:1#Browse";

    private static void getProtocolInfo() {
        String cm = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:GetProtocolInfo xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\">"
                + "</u:GetProtocolInfo></s:Body></s:Envelope>";
        Map<String, String> h = new LinkedHashMap<>();
        h.put("SOAPAction", "\"urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo\"");
        h.put("X-AV-Client-Info", "DLNADOC/1.50");
        http("http://192.168.54.1:60606/Server0/CM_control", cm.getBytes(), h, 5000);            // 404 on this camera: sent anyway
        http("http://192.168.54.1:60606/Lumix/Server0/CM_control", cm.getBytes(), h, 5000);
    }

    /** one Browse page; returns raw BrowseResponse body or null */
    private static String browse(int start, int count, boolean withProtoInfo) {
        if (withProtoInfo) getProtocolInfo();
        String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\" xmlns:pana=\"urn:schemas-panasonic-com:pana\">"
                + "<ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>"
                + "<StartingIndex>" + start + "</StartingIndex><RequestedCount>" + count + "</RequestedCount>"
                + "<SortCriteria></SortCriteria><pana:X_FromCP>LumixLink2.0</pana:X_FromCP>"
                + "</u:Browse></s:Body></s:Envelope>";
        Map<String, String> h = new LinkedHashMap<>();
        h.put("SOAPAction", SOAP_HDRS);
        h.put("X-FromCP", "LumixLink2.0");
        Resp r = http("http://192.168.54.1:60606/Server0/CDS_control", soap.getBytes(), h, 12000);
        return (r.code == 200 && r.body.contains("BrowseResponse")) ? r.body : null;
    }

    static class Item { String id, title, url; long size; }

    private static final Pattern ITEM = Pattern.compile("<item id=\"([^\"]+)\"[^>]*>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<dc:title>([^<]*)</dc:title>");
    private static final Pattern RES_DO = Pattern.compile("<res[^>]*size=\"(\\d+)\"[^>]*>(http://[^<]*?/DO[^<]+)</res>");

    private static List<Item> parseDidl(String soapBody) {
        List<Item> out = new ArrayList<>();
        String un = soapBody.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&").replace("&apos;", "'");
        Matcher m = ITEM.matcher(un);
        while (m.find()) {
            String blk = m.group(2);
            Matcher t = TITLE.matcher(blk);
            Matcher r = RES_DO.matcher(blk);
            if (t.find() && r.find()) {
                Item it = new Item();
                it.id = m.group(1); it.title = t.group(1);
                it.size = Long.parseLong(r.group(1)); it.url = r.group(2);
                out.add(it);
            }
        }
        return out;
    }

    private static int totalMatches(String soapBody) {
        Matcher m = Pattern.compile("<TotalMatches>(\\d+)</TotalMatches>").matcher(soapBody);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    /** full pull: grant + init + browse pages (fresh grant per page on 403/500) + per-file download */
    static List<File> pull(Context ctx, Progress p, File tmpDir) {
        List<File> got = new ArrayList<>();
        SharedPreferences pf = ctx.getSharedPreferences("of6k", 0);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String s : pf.getString("luma_seen", "").split(",")) if (s.trim().length() > 0) seen.add(s.trim());
        boolean firstRun = seen.isEmpty();
        if (!grantAccess(p, "pull") || !initSequence(p)) return got;
        int start = 0;
        List<Item> all = new ArrayList<>();
        int total = -1;
        while (true) {
            if (p.cancelled()) return got;
            String body = browse(start, 50, true);
            if (body == null) {   // grant may be spent: refresh and retry the page
                if (!grantAccess(p, "page") || !initSequence(p)) return got;
                body = browse(start, 50, true);
                if (body == null) { p.update("ERR: browse@" + start); return got; }
            }
            List<Item> page = parseDidl(body);
            all.addAll(page);
            if (total < 0) total = totalMatches(body);
            if (total <= 0 || all.size() >= total || page.isEmpty()) break;
            start = all.size();
        }
        Engine.dbg(TAG + " listed " + all.size() + " total=" + total);
        if (firstRun && !all.isEmpty()) {   // baseline: photos already on the card are old, like spy mode's first snapshot
            for (Item it : all) seen.add(it.id);
            pf.edit().putString("luma_seen", join(seen)).commit();
            p.update("BASE " + all.size());
            return got;
        }
        List<Item> fresh = new ArrayList<>();
        for (Item it : all) if (!seen.contains(it.id)) fresh.add(it);
        p.update("NEW " + fresh.size() + "/" + all.size());
        int n = 0;
        for (Item it : fresh) {
            if (p.cancelled()) break;
            n++;
            if (!grantAccess(p, "file") || !initSequence(p)) break;   // each download consumes the browse grant
            if (browse(0, 1, true) == null) { p.update("ERR: grant-refresh"); break; }
            File f = new File(tmpDir, it.title + ".JPG");
            if (download(it.url, f, p)) {
                got.add(f);
                seen.add(it.id);
                pf.edit().putString("luma_seen", join(seen)).commit();
                p.update("DL " + n + "/" + fresh.size() + " " + it.title + " " + (it.size / 1024) + "KB");
            } else {
                p.update("ERR: dl " + it.title);
            }
            p.fileDone(n, fresh.size());
        }
        return got;
    }

    private static String join(java.util.Set<String> s) {
        StringBuilder sb = new StringBuilder();
        for (String x : s) { if (sb.length() > 0) sb.append(','); sb.append(x); }
        return sb.toString();
    }

    private static boolean download(String url, File out, Progress p) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) (camNet != null ? camNet.openConnection(new URL(url)) : new URL(url).openConnection(Proxy.NO_PROXY));
            c.setConnectTimeout(8000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "Panasonic MIL DLNA CP UPnP/1.0");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Connection", "close");
            if (c.getResponseCode() != 200) return false;
            InputStream in = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(out);
            byte[] b = new byte[131072];
            int n;
            while ((n = in.read(b)) > 0) fo.write(b, 0, n);
            fo.close(); in.close();
            long len = out.length();
            // JPEG sanity: SOI at head; the camera pads the tail (~188B) so EOI may sit before EOF
            java.io.FileInputStream fi = new java.io.FileInputStream(out);
            int b0 = fi.read(), b1 = fi.read();
            fi.close();
            if (b0 != 0xFF || b1 != 0xD8) { out.delete(); p.update("ERR: not jpeg"); return false; }
            Engine.dbg(TAG + " dl " + out.getName() + " " + len + "B");
            return true;
        } catch (Throwable t) {
            Engine.dbg(TAG + " dl: " + t);
            out.delete();
            return false;
        } finally { if (c != null) try { c.disconnect(); } catch (Throwable t) {} }
    }
}
