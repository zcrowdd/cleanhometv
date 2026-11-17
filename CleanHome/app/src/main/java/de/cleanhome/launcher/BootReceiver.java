package de.cleanhome.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startet den Home-Tasten-Waechter nach dem Einschalten und nach Updates. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        HomeWatchService.start(c);
        Intent home = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(home); } catch (Exception ignored) { }
    }
}
