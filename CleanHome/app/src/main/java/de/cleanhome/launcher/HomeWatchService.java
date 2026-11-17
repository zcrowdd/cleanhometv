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
                p = Runtime.getRuntime().exec(new String[]{"logcat", "-T", "1", "-b", "main,system",
                        "ActivityManager:I", "ActivityTaskManager:I", "*:S"});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while (running && (line = r.readLine()) != null) {
                    if (line.contains(AMAZON_HOME) && (line.contains("START") || line.contains("category.HOME"))) {
                        goHome();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (p != null) p.destroy();
            }
            SystemClock.sleep(2000);   // logcat beendet? kurz warten, neu starten
        }
    }

    private void goHome() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 1200) return;   // Entprellen: keine Startschleife
        lastLaunch = now;
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
