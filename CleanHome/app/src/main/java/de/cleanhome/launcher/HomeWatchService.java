package de.cleanhome.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
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
    /** Zuletzt vorne gesehene fremde App (fuer "nach dem Bildschirmschoner dorthin zurueck"). */
    public static volatile String lastForegroundPkg;
    private static final java.util.regex.Pattern RESUMED_PKG =
            java.util.regex.Pattern.compile("[\\[,]\\s*([A-Za-z0-9_.]+)/");
    private Thread worker;
    private volatile long lastLaunch;
    private static final String TAG = "CleanHomeWatch";
    private final long[] recent = new long[10];  // Schutz gegen Endlosschleifen
    private int recentIdx;
    private volatile long pausedUntil;
    private volatile Process logcat;
    /** Merkt fuer die Diagnose, wann der Bildschirm schwarz/wieder an war - der Dienst laeuft immer. */
    private final android.content.BroadcastReceiver screen = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            boolean off = Intent.ACTION_SCREEN_OFF.equals(i.getAction());
            Health.stamp(c, off ? "screen_off" : "screen_on");
            if (!off) Health.ensureSaver(c);
        }
    };
    private boolean screenRegistered;

    /**
     * Amazons Startseite meldet einen Druck auf Home per Broadcast - etwa eine halbe Sekunde frueher
     * als das Protokoll (Idee aus dem Open-Source-Projekt FireFrame). logcat bleibt als Absicherung.
     */
    static final String HOME_PRESSED = "com.amazon.tv.launcher.HOME_PRESSED";
    private volatile long lastBcHome;
    private final BroadcastReceiver homeBc = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Health.stamp(c, "home_bc");
            if (System.currentTimeMillis() - MainActivity.settingsOpenedAt < 4000) return;
            if (MainActivity.isFront) return;                     // CleanHome ist schon vorne
            lastBcHome = System.currentTimeMillis();
            Log.i(TAG, "Home erkannt (Broadcast)");
            HomeMask.show(c);
            later(0, "home-bc");
            later(700, "home-bc+700");
        }
    };

    /** Laeuft der Waechter schon? Dann nicht jede Minute neu starten (spart Benachrichtigungs-Updates). */
    static volatile boolean alive;
    static boolean isRunning() { return alive; }

    public static void start(Context c) {
        Intent i = new Intent(c, HomeWatchService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        if (!screenRegistered) {
            try {
                android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
                f.addAction(Intent.ACTION_SCREEN_ON);
                registerReceiver(screen, f);
                registerReceiver(homeBc, new android.content.IntentFilter(HOME_PRESSED));
                screenRegistered = true;
            } catch (Exception ignored) { }
        }
        if (worker == null || !worker.isAlive()) {
            Log.i(TAG, "Waechter gestartet");
            running = true;
            alive = true;
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
                // system: "START ... category.HOME ... com.amazon.tv.launcher" (Home gedrueckt)
                // events:      "am_set_resumed_activity ... com.amazon.tv.launcher" (Amazon ist wirklich vorne)
                p = Runtime.getRuntime().exec(new String[]{"logcat", "-T", "1", "-b", "system,events",
                        "ActivityManager:I", "ActivityTaskManager:I",
                        "am_set_resumed_activity:I", "am_resume_activity:I", "*:S"});
                logcat = p;
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.contains("am_set_resumed_activity")) {
                        java.util.regex.Matcher m = RESUMED_PKG.matcher(line);
                        if (m.find()) {
                            String pk = m.group(1);
                            if (pk.equals(getPackageName())) { /* CleanHome selbst */ }
                            else if (pk.equals(AMAZON_HOME) || pk.startsWith("com.android.systemui")) lastForegroundPkg = null;
                            else lastForegroundPkg = pk;
                            MainActivity.foregroundChanged();
                        }
                    }
                    if (!line.contains(AMAZON_HOME)) continue;
                    // Nur die echte Amazon-STARTSEITE zaehlt - andere Bildschirme derselben App
                    // (z. B. Einstellungen, Profile, Suche) duerfen ganz normal offen bleiben.
                    if (!line.contains("HomeActivity")) continue;
                    // Fire-TV-Einstellungen laufen ueber Amazons Startseiten-App und sehen im Protokoll
                    // wie "Home gedrueckt" aus. Kurz nach dem Oeffnen deshalb ALLES ignorieren.
                    if (System.currentTimeMillis() - MainActivity.settingsOpenedAt < 4000) {
                        Log.i(TAG, "Einstellungen geoeffnet - ignoriere");
                        continue;
                    }
                    if (line.contains("category.HOME")) {
                        if (System.currentTimeMillis() - lastBcHome < 1500) continue;   // schon per Broadcast erledigt
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
        alive = false;
        try { if (logcat != null) logcat.destroy(); } catch (Exception ignored) { }   // blockierendes readLine beenden
        if (screenRegistered) {
            try { unregisterReceiver(screen); } catch (Exception ignored) { }
            try { unregisterReceiver(homeBc); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
