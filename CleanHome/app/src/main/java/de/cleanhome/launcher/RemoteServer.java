package de.cleanhome.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Winziger Webserver fuer die Handy-Steuerung (nur im Heim-WLAN, Port 8080).
 * Jede Anfrage braucht den geheimen Schluessel ?k=..., der im QR-Code steckt.
 */
public class RemoteServer {

    public interface Handler {
        /** Antwort als JSON-Text. Wird im Server-Thread aufgerufen. */
        String api(String cmd, Map<String, String> q);
    }

    public static final int PORT = 8080;
    private final String key;
    private final Handler handler;
    private volatile boolean running;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    public RemoteServer(String key, Handler h) { this.key = key; this.handler = h; }

    public void start() {
        if (running) return;
        running = true;
        new Thread(() -> {
            while (running) {
                try (ServerSocket ss = new ServerSocket(PORT)) {
                    server = ss;
                    while (running) {
                        Socket s = ss.accept();
                        pool.submit(() -> handle(s));
                    }
                } catch (Exception e) {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
                }
            }
        }, "remoteServer").start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(5000);
            java.net.InetAddress ip = sock.getInetAddress();
            if (ip == null || !(ip.isSiteLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress())) return;   // nur Heimnetz
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String first = in.readLine();
            if (first == null || first.length() > 4096) return;
            String line;
            int headers = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) { if (++headers > 100) return; }   // Header ignorieren
            String[] parts = first.split(" ");
            if (parts.length < 2) return;
            String target = parts[1];
            String path = target, query = "";
            int qi = target.indexOf('?');
            if (qi >= 0) { path = target.substring(0, qi); query = target.substring(qi + 1); }
            Map<String, String> q = new HashMap<>();
            for (String kv : query.split("&")) {
                if (kv.isEmpty()) continue;
                int e = kv.indexOf('=');
                String k = e < 0 ? kv : kv.substring(0, e), v = e < 0 ? "" : kv.substring(e + 1);
                q.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            }
            OutputStream out = sock.getOutputStream();
            String k = q.get("k");
            if (k == null || !java.security.MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), k.getBytes(StandardCharsets.UTF_8))) {
                write(out, 403, "text/html; charset=utf-8",
                        "<meta name=viewport content='width=device-width'><body style='background:#000;color:#ededed;font-family:sans-serif;padding:40px'>"
                                + "<h2>Kein Zugriff</h2><p style='color:#888'>Scanne den QR-Code am Fernseher:<br>CleanHome &rarr; Einstellungen &rarr; Handy-Steuerung</p>");
                return;
            }
            if (path.equals("/") || path.equals("/index.html")) {
                write(out, 200, "text/html; charset=utf-8", RemotePage.HTML);
            } else if (path.startsWith("/api/")) {
                String res;
                try { res = handler.api(path.substring(5), q); } catch (Exception e) { res = "{\"ok\":false}"; }
                write(out, 200, "application/json; charset=utf-8", res == null ? "{\"ok\":true}" : res);
            } else {
                write(out, 404, "text/plain", "404");
            }
        } catch (Exception ignored) { }
    }

    private static void write(OutputStream out, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }
}
