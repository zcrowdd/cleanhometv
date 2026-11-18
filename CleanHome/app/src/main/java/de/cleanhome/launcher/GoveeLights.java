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

    /** Ein/aus. Nach dem Einschalten wird die Helligkeit neu gesendet. */
    public void power(boolean on) {
        lastBrightness = -1;
        worker.submit(() -> send("{\"msg\":{\"cmd\":\"turn\",\"data\":{\"value\":" + (on ? 1 : 0) + "}}}"));
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

    /**
     * Aktuelle Helligkeit (1-100) des ersten Govee-Lichts abfragen, -1 wenn keine Antwort.
     * Blockiert bis ~1,5 s - NICHT im UI-Thread aufrufen.
     */
    public int currentBrightness() {
        JSONObject d = status();
        if (d == null) return -1;
        if (d.optInt("onOff", 1) == 0) return -1;
        int b = d.optInt("brightness", -1);
        return b >= 1 && b <= 100 ? b : -1;
    }

    private JSONObject status() {
        String ip;
        synchronized (this) { if (ips.isEmpty()) return null; ip = ips.get(0); }
        try (DatagramSocket rx = new DatagramSocket(null)) {
            rx.setReuseAddress(true);
            rx.bind(new InetSocketAddress(LISTEN_PORT));
            rx.setSoTimeout(500);
            for (int attempt = 0; attempt < 2; attempt++) {
                sendTo(ip, "{\"msg\":{\"cmd\":\"devStatus\",\"data\":{}}}");
                long end = System.currentTimeMillis() + 700;
                while (System.currentTimeMillis() < end) {
                    try {
                        byte[] buf = new byte[1024];
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        rx.receive(p);
                        String txt = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (txt.contains("devStatus")) return new JSONObject(txt).getJSONObject("msg").getJSONObject("data");
                    } catch (SocketTimeoutException ignored) { break; }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Aktuelle Farbe des ersten Govee-Lichts abfragen (blockiert bis ~1 s, NICHT im UI-Thread aufrufen).
     * Rueckgabe: Farbe (0xFFrrggbb) oder 0, wenn aus / keine Antwort.
     */
    public int currentColor() {
        String ip;
        synchronized (this) { if (ips.isEmpty()) return 0; ip = ips.get(0); }
        try (DatagramSocket rx = new DatagramSocket(null)) {
            rx.setReuseAddress(true);
            rx.bind(new InetSocketAddress(LISTEN_PORT));
            rx.setSoTimeout(500);
            for (int attempt = 0; attempt < 2; attempt++) {
                sendTo(ip, "{\"msg\":{\"cmd\":\"devStatus\",\"data\":{}}}");
                long end = System.currentTimeMillis() + 700;
                while (System.currentTimeMillis() < end) {
                    try {
                        byte[] buf = new byte[1024];
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        rx.receive(p);
                        String txt = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (!txt.contains("devStatus")) continue;
                        JSONObject d = new JSONObject(txt).getJSONObject("msg").getJSONObject("data");
                        if (d.optInt("onOff", 1) == 0) return 0;
                        JSONObject c = d.optJSONObject("color");
                        int r = c != null ? c.optInt("r") : 0, g = c != null ? c.optInt("g") : 0, b = c != null ? c.optInt("b") : 0;
                        int k = d.optInt("colorTemInKelvin", 0);
                        if (r + g + b == 0 && k > 0) return kelvinToRgb(k);
                        if (r + g + b == 0) return 0;
                        return 0xFF000000 | (r << 16) | (g << 8) | b;
                    } catch (SocketTimeoutException ignored) { break; }
                }
            }
        } catch (Exception ignored) { }
        return 0;
    }

    /** Grobe Umrechnung Farbtemperatur -> RGB (fuer Weiss-Modus). */
    private static int kelvinToRgb(int k) {
        double t = k / 100.0, r, g, b;
        r = t <= 66 ? 255 : 329.7 * Math.pow(t - 60, -0.1332);
        g = t <= 66 ? 99.47 * Math.log(t) - 161.1 : 288.1 * Math.pow(t - 60, -0.0755);
        b = t >= 66 ? 255 : (t <= 19 ? 0 : 138.5 * Math.log(t - 10) - 305.0);
        int ri = (int) Math.max(0, Math.min(255, r)), gi = (int) Math.max(0, Math.min(255, g)), bi = (int) Math.max(0, Math.min(255, b));
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
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
