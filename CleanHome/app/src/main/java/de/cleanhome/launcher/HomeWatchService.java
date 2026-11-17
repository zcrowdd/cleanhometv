package de.cleanhome.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Home-Tasten-Waechter fuer Fire OS.
 *
 * Amazon blockiert fremde Launcher und das Abfangen der Home-Taste ueber Bedienungshilfen.
 * Dieser Dienst liest deshalb das Systemprotokoll (logcat) mit: Sobald dort steht, dass
 * Amazons Startseite geoeffnet wird, startet er sofort CleanHome.
 *
 * Voraussetzung (einmalig per ADB, macht install.ps1):
 *   pm grant de.cleanhome.launcher android.permission.READ_LOGS
 */
public class HomeWatchService extends Service {

    private static final String AMAZON_HOME = "com.amazon.tv.launcher";
    private static final String CHANNEL = "homewatch";
    private static volatile boolean running;
    private Thread worker;
    private volatile long lastLaunch;
    private static final String TAG = "CleanHomeWatch";
    private final long[] recent = new long[10];  // Schutz gegen Endlosschleifen
    private int recentIdx;
    private volatile long pausedUntil;

    public static void start(Context c) {
        Intent i = new Intent(c, HomeWatchService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        if (worker == null || !worker.isAlive()) {
            Log.i(TAG, "Waechter gestartet");
            running = true;
            worker = new Thread(this::watchLoop, "cleanhome-homewatch");
            worker.start();
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "CleanHome", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("CleanHome")
                .setContentText("Home-Taste aktiv")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
    }

    private void watchLoop() {
        while (running) {
            Process p = null;
            try {
                // -T 1: nur neue Zeilen, nicht das alte Protokoll
                // main/system: "START ... category.HOME ... com.amazon.tv.launcher" (Home gedrueckt)
                // events:      "am_set_resumed_activity ... com.amazon.tv.launcher" (Amazon ist wirklich vorne)
                p = Runtime.getRuntime().exec(new String[]{"logcat", "-T", "1", "-b", "main,system,events",
                        "ActivityManager:I", "ActivityTaskManager:I",
                        "am_set_resumed_activity:I", "am_resume_activity:I", "*:S"});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (!line.contains(AMAZON_HOME)) continue;
                    // Nur die echte Amazon-STARTSEITE zaehlt - andere Bildschirme derselben App
                    // (z. B. Einstellungen, Profile, Suche) duerfen ganz normal offen bleiben.
                    if (!line.contains("HomeActivity")) continue;
                    if (line.contains("category.HOME")) {
                        // Home-Taste gedrueckt: sofort einmal und nach Amazons Aufbau nochmal
                        Log.i(TAG, "Home erkannt");
                        HomeMask.show(this);
                        later(0, "home");
                        later(700, "home+700");
                    } else if (line.contains("am_set_resumed_activity") || line.contains("am_resume_activity")) {
                        // Kurz nachdem CleanHome selbst etwas geoeffnet hat (App, Einstellungen):
                        // nicht eingreifen, sonst springt man sofort zurueck.
                        if (System.currentTimeMillis() - MainActivity.lastLeftByLaunch < 3000) continue;
                        Log.i(TAG, "Amazon-Startseite vorne erkannt");
                        HomeMask.show(this);
                        later(0, "resumed");
                    }
                }
                Log.w(TAG, "logcat beendet");
            } catch (Exception e) {
                Log.w(TAG, "logcat Fehler", e);
            } finally {
                if (p != null) p.destroy();
            }
            SystemClock.sleep(2000);   // logcat beendet? kurz warten, neu starten
        }
    }

    private void later(long delayMs, String why) {
        if (delayMs <= 0) { goHome(why); return; }
        new Thread(() -> { SystemClock.sleep(delayMs); goHome(why); }).start();
    }

    private synchronized void goHome(String why) {
        long now = SystemClock.uptimeMillis();
        if (now < pausedUntil) { Log.i(TAG, "Pause aktiv, ignoriere " + why); return; }
        if (now - lastLaunch < 120) return;           // exakt doppelte Zeilen ignorieren
        lastLaunch = now;
        // Schleifenschutz: mehr als 10 Starts in 5 s -> Amazon kaempft dagegen, 10 s Pause
        recent[recentIdx] = now;
        recentIdx = (recentIdx + 1) % recent.length;
        long oldest = recent[recentIdx];
        if (oldest != 0 && now - oldest < 5000) {
            pausedUntil = now + 10000;
            Log.w(TAG, "Zu viele Starts - 10 s Pause");
            return;
        }
        Log.i(TAG, "Starte CleanHome (" + why + ")");
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { startActivity(i); } catch (Exception e) { Log.w(TAG, "Start fehlgeschlagen", e); }
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
