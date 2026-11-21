package de.cleanhome.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Selbstpruefung und Protokoll fuer den Ruhebildschirm.
 * Laeuft aus App, Waechter und dem Minuten-Job - repariert also auch, wenn CleanHome gerade nicht offen ist.
 */
final class Health {
    private Health() { }

    static final String PREFS = "cleanhome";
    static final String DREAM = "de.cleanhome.launcher/de.cleanhome.launcher.SaverDream";

    static boolean granted(Context c, String perm) {
        try { return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; } catch (Exception e) { return false; }
    }

    static String secure(Context c, String key) {
        try { return Settings.Secure.getString(c.getContentResolver(), key); } catch (Exception e) { return null; }
    }

    /** Zeitstempel fuer die Diagnose merken (z. B. "dream_start", "screen_off"). */
    static void stamp(Context c, String key) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("ev_" + key, new SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(new Date())).apply();
        } catch (Exception ignored) { }
    }

    static String lastStamp(Context c, String key) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ev_" + key, null);
    }

    /** Prueft, ob Fire OS noch den CleanHome-Ruhebildschirm nutzt, und stellt ihn bei Bedarf wieder ein. */
    static void ensureSaver(Context c) {
        try {
            ContentResolver cr = c.getContentResolver();
            String comp = Settings.Secure.getString(cr, "screensaver_components");
            String en = Settings.Secure.getString(cr, "screensaver_enabled");
            if (DREAM.equals(comp) && "1".equals(en)) return;
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (granted(c, "android.permission.WRITE_SECURE_SETTINGS")) {
                Settings.Secure.putString(cr, "screensaver_components", DREAM);
                Settings.Secure.putString(cr, "screensaver_enabled", "1");
                Settings.Secure.putString(cr, "screensaver_activate_on_sleep", "1");
                Settings.Secure.putString(cr, "screensaver_activate_on_dock", "1");
                p.edit().putString("saver_repaired", new SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(new Date())).apply();
            } else {
                p.edit().putString("saver_broken", String.valueOf(comp)).apply();
            }
        } catch (Exception ignored) { }
    }
}
