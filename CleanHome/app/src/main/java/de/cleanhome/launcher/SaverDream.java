package de.cleanhome.launcher;

import android.content.Intent;
import android.service.dreams.DreamService;

/**
 * Wird von Fire OS als Bildschirmschoner gestartet, wenn der Stick eine Weile nicht benutzt wurde -
 * egal in welcher App. Statt schwarz zu werden, springt er sofort in den CleanHome-Ruhebildschirm.
 * Der haelt den Bildschirm wach; mit einer Taste geht es zurueck in die App, in der man vorher war.
 */
public class SaverDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_SAVER, true)
                    .putExtra(MainActivity.EXTRA_RETURN_PKG, HomeWatchService.lastForegroundPkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            MainActivity.lastLeftByLaunch = System.currentTimeMillis();
            startActivity(i);
        } catch (Exception ignored) { }
        finish();
    }
}
