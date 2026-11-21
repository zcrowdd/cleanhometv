package de.cleanhome.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.dreams.DreamService;

/**
 * Wird von Fire OS als Bildschirmschoner gestartet, wenn der Stick eine Weile nicht benutzt wurde -
 * egal in welcher App. Er oeffnet den CleanHome-Ruhebildschirm; mit einer Taste geht es zurueck in die vorige App.
 *
 * Wichtig: Endet ein Bildschirmschoner, waehrend niemand eine Taste gedrueckt hat, schickt Android den Stick
 * schlafen (= schwarzes Bild). Deshalb haelt der Schoner den Bildschirm kurz wach und beendet sich erst,
 * wenn CleanHome vorne ist und selbst den Bildschirm wach haelt.
 */
public class SaverDream extends DreamService {
    private static final long HANDOFF_MS = 2500, WAKE_MS = 15000;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wake;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        Health.stamp(this, "dream_start");
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            @SuppressWarnings("deprecation")
            int flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE;
            wake = pm.newWakeLock(flags, "cleanhome:saverHandoff");
            wake.acquire(WAKE_MS);
        } catch (Exception ignored) { }
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_SAVER, true)
                    .putExtra(MainActivity.EXTRA_RETURN_PKG, HomeWatchService.lastForegroundPkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            MainActivity.lastLeftByLaunch = System.currentTimeMillis();
            startActivity(i);
        } catch (Exception ignored) { }
        ui.postDelayed(this::finishSafely, HANDOFF_MS);   // erst beenden, wenn CleanHome sicher vorne ist
    }

    private void finishSafely() {
        if (!MainActivity.isFront) {                       // noch nicht vorne? noch einmal warten (max. Wake-Dauer)
            if (wake != null && wake.isHeld()) { ui.postDelayed(this::finishSafely, 1000); return; }
        }
        finish();
    }

    @Override
    public void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        super.onDetachedFromWindow();
    }
}
