package de.cleanhome.launcher;

import android.graphics.Color;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Lokale Steuerung von WiZ-Lampen per UDP (Port 38899), ganz ohne Cloud.
 * In der WiZ-App muss "Lokale Kommunikation zulassen" aktiv sein (Standard).
 */
public class WizLights {

    public interface Callback { void done(List<String> ips); }

    private static final int PORT = 38899;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Future<?> running;
    private final List<String> ips = new ArrayList<>();
    private int curR = 255, curG = 180, curB = 120, curDim = 60;
    private volatile String lastJson;

    public synchronized void setLamps(List<String> list) {
        ips.clear();
        ips.addAll(list);
    }

    public synchronized boolean hasLamps() { return !ips.isEmpty(); }

    /**
     * Farbe fuer die LEDs aufbereiten: Marken- und UI-Farben sind oft leicht entsaettigt (z. B. Spotify-Gruen #1DB954).
     * Auf den Lampen wirkt das blass. Farbton bleibt, Saettigung und Helligkeit gehen hoch - die Helligkeit
     * regelt ohnehin "dimming". Fast weisse/graue Farben bleiben, wie sie sind.
     */
    public static int ledColor(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        if (hsv[1] < 0.18f) return c;
        hsv[1] = Math.min(1f, hsv[1] * 1.25f + 0.1f);
        hsv[2] = 1f;
        // Gruen (z. B. Spotify) wirkt auf den LEDs sonst grell -> satteres, dunkleres Gruen
        if (hsv[0] >= 90f && hsv[0] <= 170f) { hsv[0] = 140f; hsv[1] = 1f; hsv[2] = 0.55f; }
        return Color.HSVToColor(hsv);
    }

    /** Weicher Farbwechsel (~0,5 s) zu einer RGB-Farbe. */
    public void fadeTo(int color0, int dimming0) {
        final int color = ledColor(color0);
        float[] hv = new float[3];
        Color.colorToHSV(color0, hv);
        // Gruen etwas dunkler leuchten lassen (Spotify soll satt statt grell wirken)
        final int dimming = hv[1] >= 0.18f && hv[0] >= 90f && hv[0] <= 170f ? Math.max(10, Math.round(dimming0 * 0.65f)) : dimming0;
        final int tr = Color.red(color), tg = Color.green(color), tb = Color.blue(color);
        submit(() -> {
            int sr = curR, sg = curG, sb = curB, sd = curDim;
            int steps = 5;
            for (int i = 1; i <= steps; i++) {
                if (Thread.currentThread().isInterrupted()) return;
                float f = i / (float) steps;
                curR = Math.round(sr + (tr - sr) * f);
                curG = Math.round(sg + (tg - sg) * f);
                curB = Math.round(sb + (tb - sb) * f);
                curDim = Math.round(sd + (dimming - sd) * f);
                send("{\"method\":\"setPilot\",\"params\":{\"state\":true,\"r\":" + curR + ",\"g\":" + curG
                        + ",\"b\":" + curB + ",\"c\":0,\"w\":0,\"dimming\":" + Math.max(10, curDim) + "}}");
                sleep(100);
            }
        });
    }

    private int[][] eachCur;

    /** Jede Lampe bekommt ihre eigene Farbe (Lampe i -> colors[i % n]), sanft ueber ~3 s. */
    public void fadeEach(int[] colors, int dimming) {
        if (colors == null || colors.length == 0) return;
        submit(() -> {
            List<String> copy;
            synchronized (this) { copy = new ArrayList<>(ips); }
            int n = copy.size();
            if (eachCur == null || eachCur.length != n) {
                eachCur = new int[n][];
                for (int i = 0; i < n; i++) eachCur[i] = new int[]{curR, curG, curB};
            }
            int[][] start = new int[n][];
            for (int i = 0; i < n; i++) start[i] = eachCur[i].clone();
            int steps = 12;
            for (int s = 1; s <= steps; s++) {
                if (Thread.currentThread().isInterrupted()) return;
                float f = s / (float) steps;
                for (int i = 0; i < n; i++) {
                    int c = ledColor(colors[i % colors.length]);
                    int r = Math.round(start[i][0] + (Color.red(c) - start[i][0]) * f);
                    int g = Math.round(start[i][1] + (Color.green(c) - start[i][1]) * f);
                    int b = Math.round(start[i][2] + (Color.blue(c) - start[i][2]) * f);
                    eachCur[i] = new int[]{r, g, b};
                    String j = "{\"method\":\"setPilot\",\"params\":{\"state\":true,\"r\":" + r + ",\"g\":" + g
                            + ",\"b\":" + b + ",\"c\":0,\"w\":0,\"dimming\":" + Math.max(10, dimming) + "}}";
                    sendTo(copy.get(i), j);
                }
                sleep(250);
            }
            int c0 = ledColor(colors[0]);
            curR = Color.red(c0); curG = Color.green(c0); curB = Color.blue(c0); curDim = dimming;
            lastJson = null;
        });
    }

    /**
     * Aktuellen Zustand der ersten Lampe lesen (blockiert bis ~1 s, NICHT im UI-Thread).
     * Rueckgabe: fertige setPilot-Parameter, mit denen sich genau dieser Zustand wiederherstellen laesst
     * (Szene inkl. Tempo, Weisston oder Farbe), oder null.
     */
    public String capture() {
        String ip;
        synchronized (this) { if (ips.isEmpty()) return null; ip = ips.get(0); }
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(700);
            byte[] q = "{\"method\":\"getPilot\",\"params\":{}}".getBytes(StandardCharsets.UTF_8);
            for (int attempt = 0; attempt < 2; attempt++) {
                s.send(new DatagramPacket(q, q.length, InetAddress.getByName(ip), PORT));
                try {
                    byte[] buf = new byte[1024];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    JSONObject r = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8)).getJSONObject("result");
                    JSONObject o = new JSONObject();
                    if (!r.optBoolean("state", true)) return "{\"state\":false}";
                    o.put("state", true);
                    int scene = r.optInt("sceneId", 0);
                    if (scene > 0) {
                        o.put("sceneId", scene);
                        if (r.has("speed")) o.put("speed", r.optInt("speed"));
                    } else if (r.optInt("temp", 0) > 0) {
                        o.put("temp", r.optInt("temp"));
                    } else {
                        for (String k : new String[]{"r", "g", "b", "c", "w"}) if (r.has(k)) o.put(k, r.optInt(k));
                    }
                    if (r.has("dimming")) o.put("dimming", Math.max(10, r.optInt("dimming")));
                    return o.toString();
                } catch (SocketTimeoutException ignored) { }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Einen mit capture() gemerkten Zustand wieder auf alle Lampen schicken. */
    public void restore(String params) {
        if (params == null) return;
        submit(() -> send("{\"method\":\"setPilot\",\"params\":" + params + "}"));
    }

    /** Laufende Ueberblendung sofort abbrechen (z. B. wenn die Sync-Box uebernimmt). */
    public synchronized void cancel() {
        if (running != null) running.cancel(true);
        lastJson = null;
    }

    /** Lampen ausschalten. */
    public void off() {
        submit(() -> send("{\"method\":\"setPilot\",\"params\":{\"state\":false}}"));
    }

    /** Warmweiss (Kelvin), z. B. fuer Kino-Modus. */
    public void white(int kelvin, int dimming) {
        submit(() -> {
            curDim = dimming;
            curR = 255; curG = kelvin < 3000 ? 160 : 200; curB = kelvin < 3000 ? 90 : 150;  // grobe Naeherung fuer naechsten Fade
            send("{\"method\":\"setPilot\",\"params\":{\"state\":true,\"temp\":" + kelvin + ",\"dimming\":" + Math.max(10, dimming) + "}}");
        });
    }

    /** Den zuletzt gesendeten Zustand nochmal schicken (falls ein UDP-Paket verloren ging). */
    public void resend() {
        final String j = lastJson;
        if (j == null) return;
        synchronized (this) {
            if (ips.isEmpty()) return;
            if (running != null && !running.isDone()) return;   // laeuft noch ein Fade -> der sendet ohnehin
            running = worker.submit(() -> send(j));
        }
    }

    /** Sucht alle WiZ-Lampen im WLAN (ca. 3 s). */
    public void discover(String localIp, Callback cb) {
        new Thread(() -> {
            Set<String> found = new LinkedHashSet<>();
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                s.setSoTimeout(400);
                byte[] q1 = "{\"method\":\"getPilot\",\"params\":{}}".getBytes(StandardCharsets.UTF_8);
                byte[] q2 = "{\"method\":\"registration\",\"params\":{\"phoneMac\":\"AAAAAAAAAAAA\",\"register\":false,\"phoneIp\":\"1.2.3.4\",\"id\":\"1\"}}"
                        .getBytes(StandardCharsets.UTF_8);
                List<InetAddress> targets = new ArrayList<>();
                targets.add(InetAddress.getByName("255.255.255.255"));
                if (localIp != null && localIp.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                    targets.add(InetAddress.getByName(localIp.substring(0, localIp.lastIndexOf('.')) + ".255"));
                }
                long end = System.currentTimeMillis() + 3000;
                int round = 0;
                while (System.currentTimeMillis() < end) {
                    if (round++ % 3 == 0) {
                        for (InetAddress t : targets) {
                            s.send(new DatagramPacket(q1, q1.length, t, PORT));
                            s.send(new DatagramPacket(q2, q2.length, t, PORT));
                        }
                    }
                    try {
                        byte[] buf = new byte[1024];
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        s.receive(p);
                        String txt = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                        if (txt.contains("\"result\"") || txt.contains("\"mac\"")) {
                            found.add(p.getAddress().getHostAddress());
                        }
                    } catch (SocketTimeoutException ignored) { }
                }
            } catch (Exception ignored) { }
            cb.done(new ArrayList<>(found));
        }).start();
    }

    /** Kurzes Aufblitzen zum Erkennen einer Lampe. */
    public void blink(String ip) {
        worker.submit(() -> {
            sendTo(ip, "{\"method\":\"setPilot\",\"params\":{\"r\":255,\"g\":255,\"b\":255,\"dimming\":100}}");
            sleep(400);
            sendTo(ip, "{\"method\":\"setPilot\",\"params\":{\"r\":10,\"g\":114,\"b\":239,\"dimming\":40}}");
            sleep(400);
            sendTo(ip, "{\"method\":\"setPilot\",\"params\":{\"r\":255,\"g\":255,\"b\":255,\"dimming\":100}}");
        });
    }

    private synchronized void submit(Runnable r) {
        if (ips.isEmpty()) return;
        if (running != null) running.cancel(true);
        running = worker.submit(r);
    }

    private void send(String json) {
        lastJson = json;
        List<String> copy;
        synchronized (this) { copy = new ArrayList<>(ips); }
        for (String ip : copy) sendTo(ip, json);
    }

    private static void sendTo(String ip, String json) {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            s.send(new DatagramPacket(b, b.length, InetAddress.getByName(ip), PORT));
        } catch (Exception ignored) { }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Fuer den Fall, dass jemand das JSON sehen will (Debug). */
    static String describe(int color) {
        try {
            return new JSONObject().put("r", Color.red(color)).put("g", Color.green(color)).put("b", Color.blue(color)).toString();
        } catch (Exception e) { return ""; }
    }
}
