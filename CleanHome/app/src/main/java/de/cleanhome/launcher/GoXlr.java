package de.cleanhome.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GoXLR am PC ueber die kostenlose "GoXLR Utility" (nicht die offizielle App).
 * Die Utility hat eine HTTP-Schnittstelle (Port 14564). Damit der Stick sie erreicht, muss in der
 * Utility "Allow UI Network Access" an sein (danach Utility neu starten).
 *
 * Befehle: POST /api/command  {"Command":["<Seriennummer>", {"SetGlobalColour":"RRGGBB"}]}
 */
final class GoXlr {
    static final int PORT = 14564;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile String host, serial;
    private volatile int lastColor = 1;   // 1 = noch nichts gesendet
    private volatile long lastSent;

    void setHost(String ip) { if (ip == null || !ip.equals(host)) { host = ip; serial = null; lastColor = 1; } }

    /** Verbindung pruefen. Rueckgabe: Seriennummer oder null (Grund in lastError). Blockiert. */
    volatile String lastError;
    /** Profilnamen aus der Utility (nach probe()). */
    volatile java.util.List<String> profiles = new java.util.ArrayList<>();
    String probe() {
        lastError = null;
        try {
            String js = http("GET", "/api/get-devices", null);
            JSONObject st = new JSONObject(js);
            JSONObject mixers = st.getJSONObject("mixers");
            JSONArray pr = st.optJSONObject("files") != null ? st.getJSONObject("files").optJSONArray("profiles") : null;
            java.util.List<String> names = new java.util.ArrayList<>();
            if (pr != null) for (int i = 0; i < pr.length(); i++) names.add(pr.optString(i));
            profiles = names;
            Iterator<String> it = mixers.keys();
            if (!it.hasNext()) { lastError = "Utility läuft, aber kein GoXLR angeschlossen"; return null; }
            serial = it.next();
            return serial;
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            lastError = "Keine Verbindung – läuft die GoXLR Utility mit „Allow UI Network Access“?";
        } catch (Exception e) {
            lastError = "Fehler: " + e.getClass().getSimpleName();
        }
        return null;
    }

    /** Beleuchtung des GoXLR in einer Farbe (gleiche Farbe wie die LEDs hinter dem Fernseher). */
    void color(int argb) {
        final int c = argb & 0xFFFFFF;
        if (c == lastColor) return;
        lastColor = c;
        worker.execute(() -> {
            long wait = 250 - (System.currentTimeMillis() - lastSent);    // hoechstens 4 Befehle pro Sekunde
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
            if (lastColor != c) return;                                   // inzwischen neue Farbe
            String hex = String.format(Locale.ROOT, "%06X", c);
            if (serial == null && probe() == null) return;
            command("{\"SetGlobalColour\":\"" + hex + "\"}");
            command("{\"SetAllFaderColours\":[\"" + hex + "\",\"" + hex + "\"]}");
            lastSent = System.currentTimeMillis();
        });
    }

    /** Profil laden (z. B. beim PC-Start das Gaming-Profil). */
    void loadProfile(String name) {
        if (name == null || name.isEmpty()) return;
        worker.execute(() -> {
            if (serial == null && probe() == null) return;
            command("{\"LoadProfile\":[" + JSONObject.quote(name) + ",false]}");
        });
    }

    private void command(String cmdJson) {
        try {
            JSONArray arr = new JSONArray().put(serial).put(new JSONObject(cmdJson));
            http("POST", "/api/command", new JSONObject().put("Command", arr).toString());
        } catch (Exception e) {
            serial = null;                                                // naechstes Mal neu suchen
        }
    }

    private String http(String method, String path, String body) throws Exception {
        String h = host;
        if (h == null || h.isEmpty()) throw new java.net.ConnectException("kein PC");
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + h + ":" + PORT + path).openConnection();
        c.setConnectTimeout(1500);
        c.setReadTimeout(2500);
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    void shutdown() { worker.shutdownNow(); }
}
