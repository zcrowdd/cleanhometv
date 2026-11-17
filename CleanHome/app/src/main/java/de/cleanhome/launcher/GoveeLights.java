package de.cleanhome.launcher;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Govee-Lichter ueber die offizielle lokale LAN-Schnittstelle (ohne Cloud).
 * In der Govee-Home-App muss pro Geraet "LAN-Steuerung" (LAN Control) eingeschaltet sein.
 * CleanHome aendert hier NIE die Farbe - nur die Helligkeit.
 */
public class GoveeLights {

    public interface Callback { void done(Map<String, String> found); }   // ip -> Modell

    private static final String MCAST = "239.255.255.250";
    private static final int SCAN_PORT = 4001, LISTEN_PORT = 4002, CMD_PORT = 4003;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<String> ips = new ArrayList<>();
    private volatile int lastBrightness = -1;

    public synchronized void setLamps(List<String> list) { ips.clear(); ips.addAll(list); lastBrightness = -1; }
    public synchronized boolean hasLamps() { return !ips.isEmpty(); }

    /** Helligkeit 1-100 setzen. Gleicher Wert wird nicht doppelt geschickt; einmal nach 1,5 s wiederholt. */
    public void brightness(int value) {
        final int v = Math.max(1, Math.min(100, value));
        if (v == lastBrightness) return;
        lastBrightness = v;
        worker.submit(() -> {
            String j = "{\"msg\":{\"cmd\":\"brightness\",\"data\":{\"value\":" + v + "}}}";
            send(j);
            try { Thread.sleep(1500); } catch (InterruptedException ignored) { return; }
            if (lastBrightness == v) send(j);       // UDP kann Pakete verlieren
        });
    }

    public void forget() { lastBrightness = -1; }

    /** Zum Erkennen: kurz dunkel, dann wieder hell. */
    public void blink(String ip) {
        worker.submit(() -> {
            for (int v : new int[]{5, 100, 5, 100}) {
                sendTo(ip, "{\"msg\":{\"cmd\":\"brightness\",\"data\":{\"value\":" + v + "}}}");
                try { Thread.sleep(450); } catch (InterruptedException ignored) { return; }
            }
            lastBrightness = -1;
        });
    }

    /** Sucht Govee-Geraete mit eingeschalteter LAN-Steuerung (~3 s). */
    public static void discover(Context ctx, Callback cb) {
        new Thread(() -> {
            Map<String, String> found = new LinkedHashMap<>();
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) { lock = wm.createMulticastLock("cleanhome-govee"); lock.setReferenceCounted(false); lock.acquire(); }
            } catch (Exception ignored) { }
            try (DatagramSocket rx = new DatagramSocket(null)) {
                rx.setReuseAddress(true);
                rx.bind(new InetSocketAddress(LISTEN_PORT));
                rx.setSoTimeout(400);
                byte[] q = "{\"msg\":{\"cmd\":\"scan\",\"data\":{\"account_topic\":\"reserve\"}}}".getBytes(StandardCharsets.UTF_8);
                try (MulticastSocket tx = new MulticastSocket()) {
                    long end = System.currentTimeMillis() + 3000;
                    int round = 0;
                    while (System.currentTimeMillis() < end) {
                        if (round++ % 3 == 0) tx.send(new DatagramPacket(q, q.length, InetAddress.getByName(MCAST), SCAN_PORT));
                        try {
                            byte[] buf = new byte[1024];
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            rx.receive(p);
                            String txt = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                            if (!txt.contains("\"scan\"")) continue;
                            JSONObject d = new JSONObject(txt).getJSONObject("msg").getJSONObject("data");
                            String ip = d.optString("ip", p.getAddress().getHostAddress());
                            found.put(ip, d.optString("sku", "Govee"));
                        } catch (SocketTimeoutException ignored) { }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                try { if (lock != null) lock.release(); } catch (Exception ignored) { }
            }
            cb.done(found);
        }, "goveeScan").start();
    }

    private void send(String json) {
        List<String> copy;
        synchronized (this) { copy = new ArrayList<>(ips); }
        for (String ip : copy) sendTo(ip, json);
    }

    private static void sendTo(String ip, String json) {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(b, b.length, InetAddress.getByName(ip), CMD_PORT));
        } catch (Exception ignored) { }
    }
}
