package de.cleanhome.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Echte "Weiterschauen"-Daten (Serie, Staffel, Folge, Fortschritt, Bild, Direktlink).
 *
 * Netflix & Co. melden ihre Weiterschauen-Liste ueber Amazons "Fire TV Integration SDK" an die
 * Amazon-Startseite. Das SDK schreibt jede Meldung ins System-Protokoll (Tag FTVIntegrationSDK).
 * CleanHome hat das Recht READ_LOGS und liest diese Zeilen mit - aber nur kurz, solange eine App
 * gestartet wird (das SDK meldet beim App-Start), damit der Stick nicht dauernd Protokolle liest.
 */
final class ContinueWatching {
    private ContinueWatching() { }

    private static final String TAG = "CleanHomeCW";
    static final String PREF = "cw_data";
    private static final long READ_FOR_MS = 120_000;   // nach App-Start 2 Minuten mitlesen

    private static volatile Process proc;
    private static volatile long stopAt;
    private static Thread reader;

    /** Vom Waechter: eine App ist nach vorne gekommen -> kurz mitlesen. */
    static synchronized void onAppForeground(Context c, String pkg) {
        listen(c, READ_FOR_MS);
    }

    private static long lastPoke;

    /**
     * Zurueck auf CleanHome (Zurueck- oder Home-Taste): Netflix & Co. bitten, ihre Weiterschauen-Liste
     * neu zu melden. Das geht ueber das normale "Programme initialisieren"-Signal, auf das diese Apps
     * hoeren - sie melden dann im Hintergrund nach ca. 10-20 s. Hoechstens alle 30 s.
     */
    static synchronized void refresh(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastPoke < 30_000) return;
        lastPoke = now;
        final Context app = c.getApplicationContext();
        listen(app, 90_000);
        new Thread(() -> poke(app), "cleanhome-cw-poke").start();
    }

    private static void poke(Context c) {
        try {
            java.util.Set<String> want = new java.util.HashSet<>();
            want.add("com.netflix.ninja");
            Iterator<String> it = load(c).keys();
            while (it.hasNext()) want.add(it.next());
            String last = HomeWatchService.lastForegroundPkg;
            if (last != null) want.add(last);
            android.content.Intent q = new android.content.Intent("android.media.tv.action.INITIALIZE_PROGRAMS");
            List<android.content.pm.ResolveInfo> rs = c.getPackageManager().queryBroadcastReceivers(q, 0);
            for (android.content.pm.ResolveInfo r : rs) {
                if (r.activityInfo == null || !want.contains(r.activityInfo.packageName)) continue;
                android.content.Intent i = new android.content.Intent(q.getAction())
                        .setClassName(r.activityInfo.packageName, r.activityInfo.name)
                        .addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                                | android.content.Intent.FLAG_RECEIVER_FOREGROUND);
                try { c.sendBroadcast(i); Log.i(TAG, "Aktualisieren angefragt: " + r.activityInfo.packageName); }
                catch (Exception e) { Log.w(TAG, "Anfrage abgelehnt: " + r.activityInfo.packageName, e); }
            }
        } catch (Exception e) {
            Log.w(TAG, "Aktualisieren fehlgeschlagen", e);
        }
    }

    private static synchronized void listen(Context c, long forMs) {
        stopAt = Math.max(stopAt, System.currentTimeMillis() + forMs);
        if (reader != null && reader.isAlive()) return;
        final Context app = c.getApplicationContext();
        reader = new Thread(() -> readLoop(app), "cleanhome-cw");
        reader.start();
        // Aufpasser: beendet logcat zuverlaessig, auch wenn keine Zeilen mehr kommen
        new Thread(() -> {
            while (System.currentTimeMillis() < stopAt) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
            }
            Process p = proc;
            if (p != null) p.destroy();
        }, "cleanhome-cw-stop").start();
    }

    private static void readLoop(Context c) {
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-v", "brief", "-b", "main", "-T", "1",
                    "FTVIntegrationSDK:I", "*:S").redirectErrorStream(true).start();
            proc = p;
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line, curPkg = null, curFn = null;
            List<JSONObject> items = new ArrayList<>();
            while ((line = r.readLine()) != null && System.currentTimeMillis() < stopAt) {
                int k = line.indexOf("invoked by package: ");
                if (k >= 0) {
                    if ("setContinueWatchingItems".equals(curFn) && curPkg != null) saveItems(c, curPkg, items);
                    items.clear();
                    curPkg = line.substring(k + 20).replaceAll("[ .]*(with data.*)?$", "").trim();
                    Matcher fm = FN.matcher(line);
                    curFn = fm.find() ? fm.group(1) : null;
                    continue;
                }
                if (curPkg == null) continue;
                if (line.contains("AmazonContinueWatchingItemMetadata(")) {
                    saveMeta(c, curPkg, line);
                } else if (line.contains("AmazonContinueWatchingItem(")) {
                    JSONObject it = new JSONObject();
                    it.put("id", find(ID, line));
                    it.put("prog", num(PROG, line));
                    it.put("dur", num(DUR, line));
                    it.put("ts", num(LAST, line));
                    it.put("next", line.contains("isNextEpisodeInSeries=true"));
                    items.add(it);
                    if (line.trim().endsWith("))")) {                 // letzter Eintrag der Liste
                        saveItems(c, curPkg, items);
                        items.clear();
                        curFn = null;
                    }
                }
            }
            if ("setContinueWatchingItems".equals(curFn) && curPkg != null && !items.isEmpty()) saveItems(c, curPkg, items);
        } catch (Exception e) {
            Log.w(TAG, "Lesen fehlgeschlagen", e);
        } finally {
            if (p != null) p.destroy();
            proc = null;
        }
    }

    private static final Pattern FN = Pattern.compile("function: (\\w+)");
    private static final Pattern ID = Pattern.compile("opaqueId=([^,\\s)]+)");
    private static final Pattern PROG = Pattern.compile("progressMs=(\\d+)");
    private static final Pattern DUR = Pattern.compile("durationMs=(\\d+)");
    private static final Pattern LAST = Pattern.compile("lastWatchedTimestampMs=(\\d+)");
    private static final Pattern TITLE = Pattern.compile("[(, ]title=(.*?), description=");
    private static final Pattern LINK = Pattern.compile("deeplinkUri=(\\S+?), locale=");
    private static final Pattern LOC = Pattern.compile("locale=([\\w_-]+)");
    private static final Pattern IMG = Pattern.compile("tileImage=AmazonImageMetadata\\(imageUri=(\\S+?), ");
    private static final Pattern SEASON = Pattern.compile("seasonNumber=(\\d+)");
    private static final Pattern EPISODE = Pattern.compile("episodeNumber=(\\d+)");
    private static final Pattern EPTITLE = Pattern.compile("episodeTitle=(.*?), (actors|directors|isOriginal)=");

    private static String find(Pattern p, String s) { Matcher m = p.matcher(s); return m.find() ? m.group(1) : ""; }
    private static long num(Pattern p, String s) { try { return Long.parseLong(find(p, s)); } catch (Exception e) { return 0; } }

    private static synchronized JSONObject load(Context c) {
        try { return new JSONObject(c.getSharedPreferences(Health.PREFS, Context.MODE_PRIVATE).getString(PREF, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static synchronized void store(Context c, JSONObject all) {
        c.getSharedPreferences(Health.PREFS, Context.MODE_PRIVATE).edit().putString(PREF, all.toString()).apply();
    }

    private static synchronized void saveItems(Context c, String pkg, List<JSONObject> items) {
        try {
            JSONObject all = load(c);
            JSONObject app = all.optJSONObject(pkg);
            if (app == null) app = new JSONObject();
            JSONArray arr = new JSONArray();
            for (JSONObject o : items) arr.put(o);
            app.put("items", arr);                                    // die App schickt immer die ganze Liste
            all.put(pkg, app);
            store(c, all);
            Log.i(TAG, pkg + ": " + items.size() + " Weiterschauen-Eintraege");
            MainActivity.continueWatchingChanged();
        } catch (Exception ignored) { }
    }

    private static synchronized void saveMeta(Context c, String pkg, String line) {
        try {
            String id = find(ID, line);
            if (id.isEmpty()) return;
            String loc = find(LOC, line);
            JSONObject all = load(c);
            JSONObject app = all.optJSONObject(pkg);
            if (app == null) app = new JSONObject();
            JSONObject meta = app.optJSONObject("meta");
            if (meta == null) meta = new JSONObject();
            JSONObject old = meta.optJSONObject(id);
            // pro Folge nur eine Sprache: Deutsch bevorzugen
            if (old != null && old.optString("loc").startsWith("de") && !loc.startsWith("de")) return;
            JSONObject m = new JSONObject();
            m.put("title", find(TITLE, line));
            m.put("link", find(LINK, line));
            m.put("loc", loc);
            m.put("img", find(IMG, line));
            m.put("s", num(SEASON, line));
            m.put("e", num(EPISODE, line));
            String et = find(EPTITLE, line);
            m.put("et", "null".equals(et) ? "" : et);
            m.put("prog", num(PROG, line));
            m.put("dur", num(DUR, line));
            meta.put(id, m);
            // alte Eintraege begrenzen
            if (meta.length() > 40) {
                Iterator<String> it = meta.keys();
                if (it.hasNext()) meta.remove(it.next());
            }
            app.put("meta", meta);
            all.put(pkg, app);
            store(c, all);
            MainActivity.continueWatchingChanged();
        } catch (Exception ignored) { }
    }

    /** Ein anzeigbarer Eintrag. */
    static final class Entry {
        String pkg, id, title, episodeTitle, link, img;
        int season, episode;
        long progress, duration, when;
        boolean next;
    }

    /** Alle Eintraege aller Apps, neueste zuerst. */
    static List<Entry> entries(Context c) {
        List<Entry> out = new ArrayList<>();
        JSONObject all = load(c);
        Iterator<String> pk = all.keys();
        while (pk.hasNext()) {
            String pkg = pk.next();
            JSONObject app = all.optJSONObject(pkg);
            if (app == null) continue;
            JSONArray items = app.optJSONArray("items");
            JSONObject meta = app.optJSONObject("meta");
            if (items == null || meta == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                JSONObject m = meta.optJSONObject(it.optString("id"));
                if (m == null || m.optString("title").isEmpty()) continue;
                Entry e = new Entry();
                e.pkg = pkg;
                e.id = it.optString("id");
                e.title = m.optString("title");
                e.episodeTitle = m.optString("et");
                e.link = m.optString("link");
                e.img = m.optString("img");
                e.season = m.optInt("s");
                e.episode = m.optInt("e");
                e.progress = it.optLong("prog", m.optLong("prog"));
                e.duration = it.optLong("dur", m.optLong("dur"));
                e.when = it.optLong("ts");
                e.next = it.optBoolean("next");
                out.add(e);
            }
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.when, a.when));
        return out;
    }

    /** Vorschaubild (klein, ~30 KB) einmal laden und zwischenspeichern. Blockiert. */
    static File image(Context c, String url) {
        if (url == null || url.isEmpty()) return null;
        File dir = new File(c.getCacheDir(), "cw");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".jpg");
        if (f.exists() && f.length() > 0) return f;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(5000);
            h.setReadTimeout(8000);
            if (h.getResponseCode() != 200) return null;
            File tmp = new File(dir, f.getName() + ".part");
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[16 * 1024];
                int r;
                while ((r = in.read(b)) > 0) out.write(b, 0, r);
            }
            if (!tmp.renameTo(f)) return null;
            // Cache klein halten
            File[] all = dir.listFiles();
            if (all != null && all.length > 60) for (File x : all) if (x.lastModified() < System.currentTimeMillis() - 14L * 86400_000L) x.delete();
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** Netflix-Direktlinks laufen nach ~1 Tag ab -> dann zur Serienseite (m=Titel-ID). */
    static String usableLink(Entry e) {
        String l = e.link;
        if (l == null || l.isEmpty()) return null;
        try {
            Matcher x = Pattern.compile("expireAt%3D(\\d+)").matcher(l);
            if (x.find() && Long.parseLong(x.group(1)) * 1000L < System.currentTimeMillis()) {
                Matcher m = Pattern.compile("[%&]26m%3D(\\d+)|[?&]m%3D(\\d+)|%26m%3D(\\d+)").matcher(l);
                if (m.find()) {
                    String id = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                    return "https://www.netflix.com/title/" + id;
                }
            }
        } catch (Exception ignored) { }
        return l;
    }
}
