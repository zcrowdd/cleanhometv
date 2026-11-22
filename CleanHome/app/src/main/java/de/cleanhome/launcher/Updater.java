package de.cleanhome.launcher;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Selbst-Update: fragt GitHub nach der neuesten erfolgreich gebauten Version,
 * laedt die APK aus dem Branch "apk", prueft sie und installiert sie ueber den System-Installer
 * (am Fernseher einmal "Installieren" bestaetigen). Kein PC noetig.
 */
public final class Updater {
    private Updater() { }

    private static final String RUNS = "https://api.github.com/repos/zcrowdd/cleanhometv/actions/runs?branch=main&status=success&per_page=1";
    private static final String APK = "https://raw.githubusercontent.com/zcrowdd/cleanhometv/apk/app-debug.apk";

    public interface Progress { void percent(int p); }

    public static int installedVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    /** Neueste Version auf GitHub (Lauf-Nummer = versionCode), -1 bei Fehler. Blockiert - nicht im UI-Thread. */
    public static int latestVersion() {
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(RUNS).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(8000);
            h.setRequestProperty("Accept", "application/vnd.github+json");
            h.setRequestProperty("User-Agent", "CleanHome");
            if (h.getResponseCode() != 200) return -1;
            String txt = read(h.getInputStream());
            JSONArray runs = new JSONObject(txt).optJSONArray("workflow_runs");
            if (runs == null || runs.length() == 0) return -1;
            return runs.getJSONObject(0).optInt("run_number", -1);
        } catch (Exception e) { return -1; }
    }

    /** Warum der letzte Download nicht geklappt hat (fuer eine verstaendliche Meldung). */
    public static volatile String lastError;

    /** Frische Kopie ueber die GitHub-API (kein 5-Minuten-Zwischenspeicher wie bei raw.githubusercontent.com). */
    private static final String APK_API = "https://api.github.com/repos/zcrowdd/cleanhometv/contents/app-debug.apk?ref=apk";

    /** APK herunterladen und pruefen. Rueckgabe: Datei oder null (Grund in lastError). Blockiert. */
    public static File download(Context c, Progress pr) {
        lastError = null;
        File f = fetch(c, APK_API, true, pr);
        int v = f != null ? apkVersion(c, f) : -1;
        if (f == null || v <= installedVersion(c)) {          // Ausweichweg: direkte Datei
            File g = fetch(c, APK + "?t=" + System.currentTimeMillis(), false, pr);
            int gv = g != null ? apkVersion(c, g) : -1;
            if (gv > v) { f = g; v = gv; }
        }
        if (f == null) { if (lastError == null) lastError = "Keine Verbindung zu GitHub"; return null; }
        if (v < 0) { f.delete(); lastError = "Datei ist keine CleanHome-App"; return null; }
        if (v <= installedVersion(c)) {
            f.delete();
            lastError = "Neue Version wird gerade bereitgestellt – in ein paar Minuten nochmal";
            return null;
        }
        return f;
    }

    private static int apkVersion(Context c, File f) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null || !c.getPackageName().equals(pi.packageName)) return -1;
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return -1; }
    }

    private static File fetch(Context c, String url, boolean api, Progress pr) {
        File dir = c.getExternalFilesDir("update");
        if (dir == null) dir = c.getCacheDir();
        File f = new File(dir, api ? "cleanhome.apk" : "cleanhome-raw.apk");
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(10000); h.setReadTimeout(30000);
            h.setRequestProperty("User-Agent", "CleanHome");
            h.setRequestProperty("Accept-Encoding", "identity");      // Groesse muss zur Datei passen
            h.setRequestProperty("Cache-Control", "no-cache");
            if (api) h.setRequestProperty("Accept", "application/vnd.github.raw");
            int code = h.getResponseCode();
            if (code != 200) { lastError = "GitHub antwortet mit Fehler " + code; return null; }
            int len = h.getContentLength();
            long done = 0;
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[64 * 1024];
                int r, lastP = -1;
                while ((r = in.read(b)) > 0) {
                    out.write(b, 0, r);
                    done += r;
                    if (len > 0 && pr != null) { int p = (int) (done * 100 / len); if (p != lastP) { lastP = p; pr.percent(p); } }
                }
            }
            if (len > 0 && done != len) { f.delete(); lastError = "Download abgebrochen"; return null; }
            return f;
        } catch (Exception e) {
            f.delete();
            lastError = "Download unterbrochen (" + e.getClass().getSimpleName() + ")";
            return null;
        }
    }

    /** Ueber den System-Installer installieren. Android zeigt dann "Installieren?" - einmal OK druecken. */
    public static boolean install(Context c, File apk) {
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("cleanhome", 0, apk.length())) {
                    byte[] b = new byte[64 * 1024]; int r;
                    while ((r = in.read(b)) > 0) out.write(b, 0, r);
                    s.fsync(out);
                }
                Intent i = new Intent(c, Result.class);
                PendingIntent p = PendingIntent.getBroadcast(c, id, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
                s.commit(p.getIntentSender());
            }
            return true;
        } catch (Exception e) { return false; }
    }

    /** Antwort des Installers: Bestaetigungs-Dialog anzeigen bzw. Fehler merken. */
    public static class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    MainActivity.lastLeftByLaunch = System.currentTimeMillis();
                    c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                c.getSharedPreferences("cleanhome", Context.MODE_PRIVATE).edit().remove("update_error").apply();
                cleanup(c);
            } else {
                c.getSharedPreferences("cleanhome", Context.MODE_PRIVATE).edit()
                        .putString("update_error", String.valueOf(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))).apply();
            }
        }
    }

    /** Heruntergeladene APK wieder loeschen (spart Speicher auf dem Stick). */
    public static void cleanup(Context c) {
        try {
            File dir = c.getExternalFilesDir("update");
            if (dir != null) { new File(dir, "cleanhome.apk").delete(); new File(dir, "cleanhome-raw.apk").delete(); }
            new File(c.getCacheDir(), "cleanhome.apk").delete();
        } catch (Exception ignored) { }
    }

    private static String read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[16384]; int r;
        while ((r = in.read(b)) > 0) bo.write(b, 0, r);
        return bo.toString("UTF-8");
    }
}
