package de.cleanhome.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Leerer Benachrichtigungs-Listener. Android verlangt ihn, damit CleanHome
 * sehen darf, welche Musik/Videos gerade laufen (fuer die "Jetzt laeuft"-Karte).
 * Freigabe einmalig per ADB (install.ps1).
 */
public class MediaListener extends NotificationListenerService { }
