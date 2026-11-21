package de.cleanhome.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * CleanHome – werbefreier Home Screen fuer Fire TV im Vercel-Look. made by zcrowd.
 *
 * Bedienung:
 *   Steuerkreuz = navigieren, OK = oeffnen
 *   ☰ Menue-Taste (oder OK lange) = App-Menue: Favorit, Reihe, Verschieben, Ausblenden
 *   Verschieben: ◀ ▶ bewegen, OK oder Zurueck = fertig
 */
public class MainActivity extends Activity {

    /** Zeitpunkt, an dem CleanHome bewusst eine App gestartet hat (dann kein Standbild zeigen). */
    static volatile long lastLeftByLaunch;
    static volatile boolean createdThisBoot;     // false nach Neustart, bis CleanHome einmal offen war
    static final String EXTRA_SAVER = "cleanhome_saver";
    static final String EXTRA_RETURN_PKG = "cleanhome_return_pkg";
    private String saverReturnPkg;      // nach dem Ruhebildschirm in diese App zurueck
    /** Zeitpunkt, an dem die Fire-TV-Einstellungen geoeffnet wurden (Waechter pausiert kurz). */
    static volatile long settingsOpenedAt;
    /** true, solange CleanHome wirklich vorne ist (fuer das Standbild). */
    static volatile boolean isFront;
    /** Leistungsmodus fuer schwache Geraete (z. B. Fire TV Stick 3. Gen mit 1 GB RAM). */
    static boolean LITE;

    // ---------------------------------------------------------------- Einstellungen
    private static final String PREFS = "cleanhome";
    private static final String KEY_ROWS = "rows";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_OLD_FAVS = "favs";
    private static final long SAVER_DELAY_MS = 4 * 60 * 1000L;   // Bildschirmschoner nach 4 Min.
    private static final long SAVER_DRIFT_MS = 15 * 1000L;       // Position wechselt alle 15 s
    private static final int GRID_COLUMNS = 5;
    private static final String FAV = "fav";
    private static final String ALL = "all";

    private static final String[] DEFAULT_FAVS = {
            "com.netflix.ninja", "com.amazon.avod", "com.amazon.firetv.youtube",
            "com.disney.disneyplus", "com.spotify.tv.android",
            "de.swr.ard.avp.mobile.android.amazon", "com.zdf.android.mediathek"
    };
    private static final String[] ROW_PRESETS = {
            "Streaming", "Mediatheken", "Musik", "Gaming", "Tools", "Sport", "Kinder", "Nachrichten"
    };
    private static final String[] SYSTEM_HIDDEN = {
            "com.amazon.tv.launcher", "com.amazon.tv.settings.v2", "com.amazon.tv.settings"
    };

    // ---------------------------------------------------------------- Farben (Vercel)
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFEDEDED;
    private static final int GRAY_1 = 0xFFA1A1A1;
    private static final int GRAY_2 = 0xFF666666;
    private static final int BORDER = 0xFF2E2E2E;
    private static final int SURFACE = 0xFF0A0A0A;
    private static final int GREEN = 0xFF45DEC4;
    private static final int AMBER = 0xFFF5A524;
    private static final int RED = 0xFFF2555A;
    private static final int BLUE = 0xFF0A72EF;

    // ---------------------------------------------------------------- Daten
    static class AppInfo {
        String pkg, label, src;     // src: bei "Weiterschauen" die eigentliche App
        Intent launch;
        Drawable banner, icon;
        int color = BLUE;
        int cat = -1;               // Kategorie in "Alle Apps" (siehe CAT_NAMES)
    }

    static class Row {
        String id, name;
        List<String> apps = new ArrayList<>();
        Row(String id, String name) { this.id = id; this.name = name; }
    }

    static class MItem {
        final String label; final Runnable action; final boolean danger;
        MItem(String l, Runnable a) { this(l, a, false); }
        MItem(String l, Runnable a, boolean d) { label = l; action = a; danger = d; }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<AppInfo> apps = new ArrayList<>();
    private List<Row> rows;
    private final Set<String> hidden = new HashSet<>();

    private Typeface fRegular, fMedium, fSemi, fBold, fLight, fMono;

    // ---------------------------------------------------------------- Views
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout rowsBox;
    private GridLayout grid;          // erstes Kategorie-Raster (fuer Fokus und Einblend-Animation)
    private LinearLayout allBox;
    private TextView heroTitle, heroPkg, heroHint, dateView, appCount, netView;
    private GradientDrawable glow, netDot;
    private int glowColor = BLUE;
    private ValueAnimator glowAnim;
    private String heroLabel;
    private boolean firstRender = true;

    // Verschieben
    private String movingPkg, movingRow;

    // Menue
    private FrameLayout menuLayer;
    private View focusBeforeMenu;

    // Bildschirmschoner
    private FrameLayout saver;
    private LinearLayout saverContent;
    private TextView sWeather, saverHello, saverMusic;
    private SaverClock saverClock;
    private SaverParticles saverDust;
    private GradientDrawable saverGlow;
    private final Random rnd = new Random();

    // Wetter
    private TextView wIcon, wTemp, wDesc, wMinMax;
    private String wLine = "";
    private long lastWeather;

    // ================================================================ Lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            LITE = am.isLowRamDevice() || mi.totalMem < 1600L * 1024 * 1024;
        } catch (Exception e) { LITE = false; }
        loadFonts();
        loadHidden();
        loadWiz();
        loadGovee();
        inst = new java.lang.ref.WeakReference<>(this);
        setContentView(buildUi());
        // Fokus wandert z. B. zu Einstellungen oder ins Menue -> Licht neu berechnen (nicht die alte App-Farbe behalten)
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((o, n) -> { if (homeVisible) scheduleLights(250); });
        root.getViewTreeObserver().addOnScrollChangedListener(this::eqVisibilityCheck);
        root.post(() -> {                     // Hintergrundbild deckt alles -> zwei Vollbild-Fuellungen pro Frame sparen
            root.setBackground(null);
            getWindow().setBackgroundDrawable(null);
        });
        readPcExtras(getIntent());
        createdThisBoot = true;
        installCrashLogger();
        ui.postDelayed(this::ensureSaverSettings, 3000);
        ui.postDelayed(updateTick, 90_000);           // 1,5 min nach dem Start, dann alle 6 Stunden
        try {
            ((android.hardware.input.InputManager) getSystemService(INPUT_SERVICE)).registerInputDeviceListener(padListener, ui);
        } catch (Exception ignored) { }
        startRemote();
        HomeWatchService.start(this);
        KeepAliveJob.schedule(this);
        initMedia();
        android.content.IntentFilter sf = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, sf);
        android.content.IntentFilter pf = new android.content.IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_SAVER, false)) handleSaverIntent(getIntent());
        else if (b == null) showSplash();   // nur beim Kaltstart (z. B. nach dem Einschalten)
    }

    @Override
    protected void onResume() {
        super.onResume();
        dateView.setText(new SimpleDateFormat("EEE, d. MMM", Locale.GERMANY).format(new Date()).toUpperCase(Locale.GERMANY));
        if (appsDirty || apps.isEmpty()) loadApps();   // nur neu laden, wenn sich Apps geaendert haben
        clearLaunchFx();
        ui.removeCallbacks(weatherTick); ui.post(weatherTick);
        ui.removeCallbacks(netTick); ui.post(netTick);
        isFront = true;
        closeVideoSession();              // aus einer Video-App zurueck -> ggf. in "Weiterschauen"
        // Solange CleanHome vorne ist, geht der Stick nie in den schwarzen Ruhezustand
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        HomeMask.hide(this, true);        // CleanHome ist vorne -> Standbild ausblenden
        if (msm == null) initMedia();     // z. B. wenn die Berechtigung erst jetzt erteilt wurde
        wizHome();
        resetIdle();
        ui.postDelayed(this::maybeOfferUpdate, 1500);
        for (android.animation.ObjectAnimator a : eqAnims) if (a != null && a.isPaused()) a.resume();
        ui.postDelayed(this::eqVisibilityCheck, 300);
    }

    /** Standbild des Home Screens merken, damit es beim naechsten Home-Druck sofort da ist. */
    private void takeSnapshot() {
        try {
            if (splash != null || root == null || root.getWidth() == 0) return;
            if (saver != null && saver.getVisibility() == View.VISIBLE) return;
            if (menuLayer != null && menuLayer.getVisibility() == View.VISIBLE) return;
            if (pcLayer != null && pcLayer.getVisibility() == View.VISIBLE) return;
            Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.RGB_565);
            root.draw(new Canvas(b));
            HomeMask.snapshot = b;
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onPause() {
        isFront = false;
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
        ui.removeCallbacks(weatherTick);
        ui.removeCallbacks(netTick);
        ui.removeCallbacks(showSaver);
        hideSaver();
        homeVisible = false;
        scheduleLights(400);
        for (android.animation.ObjectAnimator a : eqAnims) if (a != null && a.isRunning()) a.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMedia();
        if (remote != null) remote.stop();
        try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readPcExtras(intent);
        if (intent != null && intent.getBooleanExtra("reload_games", false)) {
            intent.removeExtra("reload_games");
            loadApps();
            ui.postDelayed(() -> toast(gameApps.size() + " PC-Spiele geladen"), 1200);
        }
        hideSaver();
        hideMenu();
        if (movingPkg != null) endMove();
        if (scroll != null) scroll.smoothScrollTo(0, 0);
        focusFirst();
        if (intent != null && intent.getBooleanExtra(EXTRA_SAVER, false)) handleSaverIntent(intent);
    }

    /** Vom System-Bildschirmschoner (SaverDream) gestartet: sofort Ruhebildschirm statt schwarz. */
    private void handleSaverIntent(Intent i) {
        String ret = i.getStringExtra(EXTRA_RETURN_PKG);
        i.removeExtra(EXTRA_SAVER);
        ui.removeCallbacks(showSaver);
        ui.postDelayed(() -> {
            if (splash != null) endSplash(false);
            showSaver.run();
            saverReturnPkg = ret;     // erst nach showSaver setzen (showSaver loescht nichts davon)
        }, 50);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { /* Home Screen: Zurueck macht nichts */ }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetIdle();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        // Controller: A = OK, B = Zurueck (damit CleanHome komplett mit dem Gamepad bedienbar ist)
        if (k == KeyEvent.KEYCODE_BUTTON_A || k == KeyEvent.KEYCODE_BUTTON_B) {
            int nk = k == KeyEvent.KEYCODE_BUTTON_A ? KeyEvent.KEYCODE_DPAD_CENTER : KeyEvent.KEYCODE_BACK;
            return dispatchKeyEvent(new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), nk, e.getRepeatCount(),
                    e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource()));
        }
        if (ambientLayer != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) closeAmbient();
            return true;
        }
        if (qrLayer != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) { final View q = qrLayer; qrLayer = null; q.animate().alpha(0f).setDuration(150).withEndAction(() -> root.removeView(q)).start(); }
            return true;
        }
        // PC-Start-Overlay: Zurueck bricht ab, sonst nichts durchlassen
        if (pcLayer != null && pcLayer.getVisibility() == View.VISIBLE) {
            if (k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) { pcCancel = true; hidePcLayer(); }
            return true;
        }
        // 0) Startanimation: Taste ueberspringt
        if (splash != null) {
            if (e.getAction() == KeyEvent.ACTION_UP) endSplash(true);
            return true;
        }
        // 1) Bildschirmschoner: jede Taste weckt auf, wird aber nicht weitergegeben
        if (saver.getVisibility() == View.VISIBLE) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                String ret = saverReturnPkg;
                hideSaver();
                if (ret != null) {           // vorher war man in einer App -> dorthin zurueck
                    Intent li = getPackageManager().getLaunchIntentForPackage(ret);
                    if (li == null) li = getPackageManager().getLeanbackLaunchIntentForPackage(ret);
                    if (li != null) {
                        lastLaunchedPkg = ret;
                        HomeWatchService.lastForegroundPkg = ret;
                        lastLeftByLaunch = System.currentTimeMillis();
                        try { startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
                    }
                }
            }
            return true;
        }
        // 2) Menue offen: Zurueck / ☰ schliesst
        if (menuLayer.getVisibility() == View.VISIBLE
                && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU)) {
            if (e.getAction() == KeyEvent.ACTION_UP) hideMenu();
            return true;
        }
        // 3) Verschieben: Zurueck beendet
        if (movingPkg != null && k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) endMove();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void loadFonts() {
        fRegular = font("Inter-Regular.otf", Typeface.DEFAULT);
        fMedium = font("Inter-Medium.otf", Typeface.DEFAULT);
        fSemi = font("Inter-SemiBold.otf", Typeface.DEFAULT_BOLD);
        fBold = font("Inter-Bold.otf", Typeface.DEFAULT_BOLD);
        fLight = font("Inter-Light.otf", Typeface.DEFAULT);
        fMono = font("DejaVuSansMono.ttf", Typeface.MONOSPACE);
    }

    private Typeface font(String file, Typeface fallback) {
        try { return Typeface.createFromAsset(getAssets(), "fonts/" + file); }
        catch (Exception e) { return fallback; }
    }

    // ================================================================ UI aufbauen

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BLACK);
        // Raster + Rand-Abdunklung werden EINMAL in ein Bild gerechnet (statt zwei Vollbild-Ebenen pro Frame)
        root.addView(new StaticBackground(this), new FrameLayout.LayoutParams(-1, -1));

        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glow.setGradientRadius(dp(470));
        setGlowColors(glowColor);
        View g = new View(this);
        g.setBackground(glow);
        FrameLayout.LayoutParams lg = new FrameLayout.LayoutParams(dp(1100), dp(940), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lg.topMargin = -dp(560);
        root.addView(g, lg);

        // Spotlight: weicher Lichtschein hinter der markierten Kachel
        spotDrawable = new GradientDrawable();
        spotDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        spotDrawable.setGradientRadius(dp(SPOT / 2));
        spotDrawable.setColors(new int[]{0x330A72EF, 0x000A72EF});
        spot = new View(this);
        spot.setBackground(spotDrawable);
        spot.setAlpha(0f);
        spot.setLayerType(View.LAYER_TYPE_HARDWARE, null);   // Verschieben = nur Komposition, kein Neuzeichnen
        root.addView(spot, new FrameLayout.LayoutParams(dp(SPOT), dp(SPOT)));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(56), dp(26), dp(56), dp(48));
        scroll.addView(col, new ScrollView.LayoutParams(-1, -2));

        col.addView(buildNav(), new LinearLayout.LayoutParams(-1, -2));

        View hr = new View(this);
        hr.setBackgroundColor(0xFF1A1A1A);
        LinearLayout.LayoutParams lhr = new LinearLayout.LayoutParams(-1, dp(1));
        lhr.topMargin = dp(16);
        col.addView(hr, lhr);

        col.addView(buildHero(), new LinearLayout.LayoutParams(-1, -2));
        col.addView(buildNowPlaying(), new LinearLayout.LayoutParams(-1, -2));

        rowsBox = new LinearLayout(this);
        rowsBox.setOrientation(LinearLayout.VERTICAL);
        rowsBox.setClipChildren(false);
        col.addView(rowsBox, new LinearLayout.LayoutParams(-1, -2));

        appCount = tv(11, GRAY_2, fMono);
        grid = newGrid();
        // "Alle Apps" nach Kategorien: je Kategorie eine Ueberschrift + ein Raster (wird in render() gefuellt)
        allBox = new LinearLayout(this);
        allBox.setOrientation(LinearLayout.VERTICAL);
        allBox.setClipChildren(false);
        col.addView(allBox, new LinearLayout.LayoutParams(-1, -2));

        // Ebenen ueber allem: Menue + Bildschirmschoner
        menuLayer = new FrameLayout(this);
        menuLayer.setBackgroundColor(0xB3000000);
        menuLayer.setVisibility(View.GONE);
        menuLayer.setClickable(true);
        root.addView(menuLayer, new FrameLayout.LayoutParams(-1, -1));

        toastView = tv(15, WHITE, fRegular);
        toastView.setPadding(dp(22), dp(11), dp(22), dp(11));
        toastView.setBackground(rounded(0xF20A0A0A, BORDER, 24));
        toastView.setAlpha(0f);
        toastView.setVisibility(View.GONE);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tl.bottomMargin = dp(48);
        root.addView(toastView, tl);
        root.getViewTreeObserver().addOnScrollChangedListener(() -> placeSpot(false));

        saver = buildSaver();
        saver.setVisibility(View.GONE);
        root.addView(saver, new FrameLayout.LayoutParams(-1, -1));

        return root;
    }

    private View edgeFade() {
        GradientDrawable fade = new GradientDrawable();
        fade.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        fade.setGradientRadius(dp(640));
        fade.setGradientCenter(0.5f, 0.0f);
        fade.setColors(new int[]{0x00000000, 0x99000000, 0xFF000000});
        View fv = new View(this);
        fv.setBackground(fade);
        return fv;
    }

    private View buildNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);

        TextView madeBy = tv(15, GRAY_1, fRegular);
        madeBy.setText("made by ");
        nav.addView(madeBy);
        TextView brand = tv(15, WHITE, fSemi);
        brand.setText("zcrowd");
        nav.addView(brand);
        TextView slash = tv(18, 0xFF3A3A3A, fLight);
        slash.setText("  /  ");
        nav.addView(slash);
        TextView crumb = tv(15, GRAY_1, fRegular);
        crumb.setText("home");
        nav.addView(crumb);
        nav.addView(badge("FIRE TV"), lpLeft(12));

        nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // Netzwerk-Status
        View dot = new View(this);
        netDot = new GradientDrawable();
        netDot.setShape(GradientDrawable.OVAL);
        netDot.setColor(GRAY_2);
        dot.setBackground(netDot);
        nav.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        netView = tv(12, GRAY_1, fMono);
        netView.setText("  Netzwerk …");
        nav.addView(netView);

        TextView sep = tv(12, 0xFF3A3A3A, fMono);
        sep.setText("   ·   ");
        nav.addView(sep);
        dateView = tv(12, GRAY_1, fMono);
        nav.addView(dateView);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clock.setTypeface(fMono);
        nav.addView(clock, lpLeft(10));

        TextView settings = button("Einstellungen");
        settingsBtn = settings;
        settings.setOnKeyListener((v, k, e) -> {
            if (k != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (npSection.getVisibility() == View.VISIBLE) npPlay.requestFocus();
                else if (!backToTile()) focusFirst();
            }
            return true;
        });
        settings.setOnClickListener(v -> openSettingsMenu());
        nav.addView(settings, lpLeft(20));
        return nav;
    }

    private View buildHero() {
        heroTitle = tv(52, WHITE, fBold);
        heroTitle.setLetterSpacing(-0.045f);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setPadding(0, dp(24), 0, dp(2));
        heroTitle.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, orr, ob) ->
                heroTitle.getPaint().setShader(new LinearGradient(0, heroTitle.getPaddingTop(), 0,
                        heroTitle.getHeight(), 0xFFFFFFFF, 0xFF8A8A8A, Shader.TileMode.CLAMP)));

        LinearLayout heroRow = new LinearLayout(this);
        heroRow.setGravity(Gravity.BOTTOM);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        heroRow.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        left.addView(heroTitle);

        LinearLayout meta = new LinearLayout(this);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(meta);
        heroPkg = tv(13, GRAY_2, fMono);
        meta.addView(heroPkg);
        heroHint = tv(13, GRAY_1, fRegular);
        meta.addView(new View(this), new LinearLayout.LayoutParams(dp(24), 1));
        meta.addView(kbd("OK"));
        TextView t1 = tv(13, GRAY_1, fRegular);
        t1.setText("  Öffnen");
        meta.addView(t1);
        meta.addView(new View(this), new LinearLayout.LayoutParams(dp(18), 1));
        meta.addView(kbd("☰"));
        heroHint.setText("  Optionen");
        meta.addView(heroHint);

        LinearLayout.LayoutParams lw = lpLeft(24);
        lw.topMargin = dp(20);
        heroRow.addView(weatherCard(), lw);
        return heroRow;
    }

    // ---------- kleine Bausteine

    private TextView tv(int sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout.LayoutParams lpLeft(int d) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(d);
        return lp;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView badge(String s) {
        TextView t = tv(10, GRAY_1, fMono);
        t.setText(s);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        t.setBackground(rounded(0xFF111111, BORDER, 20));
        return t;
    }

    private TextView kbd(String s) {
        TextView t = tv(11, WHITE, fMono);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(26));
        t.setPadding(dp(6), dp(3), dp(6), dp(3));
        t.setBackground(rounded(0xFF161616, BORDER, 6));
        return t;
    }

    private TextView button(String s) {
        TextView t = tv(13, BLACK, fMedium);
        t.setText(s);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable d = rounded(WHITE, 0, 8);
        t.setBackground(d);
        t.setOnFocusChangeListener((v, has) -> {
            d.setStroke(has ? dp(2) : 0, BLUE);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(140).start();
            if (has) setHero("Einstellungen", "home · system", BLUE);
        });
        return t;
    }

    private View sectionHeader(String title, TextView count, int topDp) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(topDp), 0, dp(12));
        TextView t = tv(11, GRAY_1, fMono);
        t.setText(title);
        t.setLetterSpacing(0.12f);
        row.addView(t);
        if (count != null) row.addView(count, lpLeft(10));
        View line = new View(this);
        line.setBackgroundColor(0xFF1A1A1A);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, dp(1), 1f);
        ll.leftMargin = dp(14);
        row.addView(line, ll);
        return row;
    }

    // ================================================================ Hero + Glow

    private void setHero(String label, String sub, int color) {
        if (!label.equals(heroLabel)) {
            heroLabel = label;
            heroTitle.animate().cancel();
            heroTitle.setAlpha(0f);
            heroTitle.setTranslationY(dp(8));
            heroTitle.setText(label);
            heroTitle.animate().alpha(1f).translationY(0).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        heroPkg.setText(sub);
        if (glowAnim != null) glowAnim.cancel();
        if (LITE) {                                    // kein Farbverlauf ueber 30 Bilder, nur einmal umfaerben
            ui.removeCallbacks(glowSet);
            glowTarget = color;
            ui.postDelayed(glowSet, 120);              // erst wenn man kurz auf einer Kachel bleibt
            return;
        }
        final int from = glowColor;
        final ArgbEvaluator ev = new ArgbEvaluator();
        glowAnim = ValueAnimator.ofFloat(0f, 1f);
        glowAnim.setDuration(500);
        glowAnim.addUpdateListener(an -> {
            glowColor = (int) ev.evaluate((float) an.getAnimatedValue(), from, color);
            setGlowColors(glowColor);
        });
        glowAnim.start();
    }

    private int glowTarget;
    private final Runnable glowSet = () -> { glowColor = glowTarget; setGlowColors(glowTarget); };

    private void setGlowColors(int c) {
        glow.setColors(new int[]{withAlpha(c, 0x59), withAlpha(c, 0x1A), 0x00000000});
    }

    // ================================================================ Apps laden

    private volatile boolean appsDirty = true;

    private final android.content.BroadcastReceiver packageReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getData() != null) {      // Farbe dieser App neu berechnen lassen
                getSharedPreferences("colors", MODE_PRIVATE).edit()
                        .remove(i.getData().getSchemeSpecificPart()).apply();
            }
            appsDirty = true;
            if (isFront) loadApps();
        }
    };

    private void loadApps() {
        appsDirty = false;
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Map<String, AppInfo> map = new LinkedHashMap<>();
            collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, map);
            collect(pm, Intent.CATEGORY_LAUNCHER, map);
            List<AppInfo> list = new ArrayList<>(map.values());
            final List<AppInfo> games = readGames();
            Collator c = Collator.getInstance(Locale.GERMANY);
            Collections.sort(list, (x, y) -> c.compare(x.label, y.label));
            ui.post(() -> {
                apps.clear();
                apps.add(pcApp());
                apps.addAll(list);
                gameApps.clear();
                gameApps.addAll(games);
                buildResumeApps();
                if (rows == null) loadRows();
                SharedPreferences pp = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (!pp.getBoolean(KEY_PC_ADDED, false)) {
                    Row fav = findRow(FAV);
                    if (fav != null && !fav.apps.contains(PC_PKG)) { fav.apps.add(0, PC_PKG); saveRows(); }
                    pp.edit().putBoolean(KEY_PC_ADDED, true).apply();
                }
                int junk = autoHideJunk();
                render(null, null);
                if (junk > 0) toast(junk + (junk == 1 ? " unnötige App" : " unnötige Apps")
                        + " ausgeblendet · zurückholen: Einstellungen → Ausgeblendete Apps");
            });
        }).start();
    }

    private void collect(PackageManager pm, String cat, Map<String, AppInfo> into) {
        List<ResolveInfo> res = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
        for (ResolveInfo ri : res) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || isSystemHidden(pkg) || into.containsKey(pkg)) continue;
            AppInfo a = new AppInfo();
            a.pkg = pkg;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try { a.banner = scaledBanner(ri.activityInfo.loadBanner(pm)); } catch (Exception ignored) { }
            try { a.icon = ri.loadIcon(pm); } catch (Exception ignored) { }
            SharedPreferences cp = getSharedPreferences("colors", MODE_PRIVATE);
            if (cp.contains(pkg)) a.color = cp.getInt(pkg, BLUE);
            else {
                a.color = dominantColor(a.banner != null ? a.banner : a.icon);
                cp.edit().putInt(pkg, a.color).apply();
            }
            into.put(pkg, a);
        }
    }

    /** Banner einmal auf Kachelgroesse rechnen, statt es bei jedem Bild aus voller Groesse zu skalieren. */
    // Kachelbilder: genau Kachelgroesse, 2 Byte pro Pixel (RGB_565) statt 4 - halber Speicher, gleiches Aussehen
    // (durchsichtige Stellen werden vorher mit dem Kachel-Hintergrund gefuellt).
    private Drawable scaledBanner(Drawable src) {
        if (src == null) return null;
        int w = dp(232), h = dp(131);
        Drawable d = src.getConstantState() != null ? src.getConstantState().newDrawable().mutate() : src;
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        b.eraseColor(SURFACE);
        Canvas c = new Canvas(b);
        if (iw > 0 && ih > 0) {                       // wie CENTER_CROP
            float s = Math.max(w / (float) iw, h / (float) ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s);
            int l = (w - dw) / 2, t = (h - dh) / 2;
            d.setBounds(l, t, l + dw, t + dh);
        } else d.setBounds(0, 0, w, h);
        d.draw(c);
        return new android.graphics.drawable.BitmapDrawable(getResources(), b);
    }

    /** Beliebiges Bild auf Kachelgroesse zuschneiden (wie CENTER_CROP), RGB_565. Das Original wird freigegeben. */
    private Drawable tileFromBitmap(Bitmap src) {
        int w = dp(232), h = dp(131);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        b.eraseColor(SURFACE);
        Canvas c = new Canvas(b);
        float sc = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        float dw = src.getWidth() * sc, dh = src.getHeight() * sc;
        Paint fp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(src, null, new android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f), fp);
        src.recycle();
        return new android.graphics.drawable.BitmapDrawable(getResources(), b);
    }

    private GridLayout newGrid() {
        GridLayout g = new GridLayout(this);
        g.setColumnCount(GRID_COLUMNS);
        g.setClipChildren(false);
        g.setClipToPadding(false);
        return g;
    }

    // ---- Kategorien fuer "Alle Apps"
    private static final String[] CAT_NAMES = {"STREAMING", "MUSIK", "SPIELE", "WERKZEUGE", "AMAZON", "SONSTIGE"};
    private static final String[] TOOL_WORDS = {
            "downloader", "browser", "silk", "cloud9", "firefox", "chrome", "file", "datei", "explorer", "x-plore",
            "vpn", "airscreen", "send", "speedtest", "analiti", "appstore", "venezia", "settings", "einstellung",
            "terminal", "adb", "remote", "wake", "manager", "tool"
    };

    private int category(AppInfo a) {
        int k = appKind(a.pkg);
        if (k == K_VIDEO) return 0;
        if (k == K_AUDIO) return 1;
        if (k == K_GAME) return 2;
        String s = (a.pkg + " " + a.label).toLowerCase(Locale.ROOT);
        for (String w : TOOL_WORDS) if (s.contains(w)) return 3;
        if (a.pkg.startsWith("com.amazon.")) return 4;
        return 5;
    }

    // ---- Unnoetige Amazon-Apps einmalig ausblenden (zurueckholen: Einstellungen -> Ausgeblendete Apps)
    private static final String KEY_AUTO_HIDE = "auto_hide_v1";
    private static final String[] JUNK_LABELS = {
            "minitv", "amazon minitv", "luna", "amazon luna", "amazon photos", "amazon fotos", "fotos",
            "amazon kids", "freevee", "amazon freevee", "imdb tv", "hilfe", "help", "tipps", "amazon shopping",
            "alexa shopping", "kindle"
    };
    private static final String[] JUNK_PKG_PARTS = {
            "minitv", ".luna", "tahoe", "freevee", "imdb", "bueller.photos", "shopping"
    };

    /** @return Anzahl neu ausgeblendeter Apps (nur beim allerersten Mal mit dieser Version > 0). */
    private int autoHideJunk() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (p.getBoolean(KEY_AUTO_HIDE, false)) return 0;
        int n = 0;
        for (AppInfo a : apps) {
            if (PC_PKG.equals(a.pkg) || hidden.contains(a.pkg)) continue;
            String l = a.label == null ? "" : a.label.trim().toLowerCase(Locale.ROOT);
            boolean junk = false;
            for (String j : JUNK_LABELS) if (l.equals(j)) junk = true;
            for (String j : JUNK_PKG_PARTS) if (a.pkg.toLowerCase(Locale.ROOT).contains(j)) junk = true;
            Row fav = findRow(FAV);
            if (junk && fav != null && fav.apps.contains(a.pkg)) junk = false;   // Favoriten nie anfassen
            if (junk) { hidden.add(a.pkg); n++; }
        }
        saveHidden();
        p.edit().putBoolean(KEY_AUTO_HIDE, true).apply();
        return n;
    }

    private boolean isSystemHidden(String pkg) {
        for (String h : SYSTEM_HIDDEN) if (h.equals(pkg)) return true;
        return false;
    }

    private AppInfo find(String pkg) {
        if (pkg == null) return null;
        for (AppInfo a : apps) if (a.pkg.equals(pkg)) return a;
        for (AppInfo a : extraApps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    // ================================================================ PC-Spiele + Weiterschauen

    private static final String GAME_PREFIX = "pcgame:", RESUME_PREFIX = "resume:";
    private static final String ROW_RESUME = "x_resume", ROW_GAMES = "x_games";
    private final List<AppInfo> extraApps = new ArrayList<>();
    private final List<AppInfo> gameApps = new ArrayList<>(), resumeApps = new ArrayList<>();
    private String gamePcUuid = "", gamePcName = "";

    /** Liest die von games.ps1 geschickte Spieleliste (games.json + Kachelbilder). Im Hintergrund-Thread aufrufen. */
    private List<AppInfo> readGames() {
        List<AppInfo> out = new ArrayList<>();
        try {
            java.io.File dir = getExternalFilesDir("games");
            java.io.File f = dir != null ? new java.io.File(dir, "games.json") : null;
            if (f == null || !f.exists()) return out;
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int o = 0, r; while (o < b.length && (r = in.read(b, o, b.length - o)) > 0) o += r; }
            String txt = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            if (txt.startsWith("\uFEFF")) txt = txt.substring(1);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            org.json.JSONObject pc = root.optJSONObject("pc");
            if (pc != null) { gamePcUuid = pc.optString("uuid", ""); gamePcName = pc.optString("name", ""); }
            org.json.JSONArray arr = root.optJSONArray("games");
            if (arr == null) { org.json.JSONObject one = root.optJSONObject("games"); arr = new org.json.JSONArray(); if (one != null) arr.put(one); }
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject g = arr.getJSONObject(i);
                String name = g.optString("name", "");
                if (name.isEmpty()) continue;
                AppInfo a = new AppInfo();
                a.pkg = GAME_PREFIX + name;
                a.label = name;
                String img = g.optString("img", "");
                Bitmap bm = null;
                if (!img.isEmpty()) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    bm = android.graphics.BitmapFactory.decodeFile(new java.io.File(dir, img).getAbsolutePath(), o);
                }
                int col = gameColor(name, bm);
                a.color = col;
                a.banner = bm != null ? tileFromBitmap(bm)
                        : textBanner(name, g.optString("src", "PC").toUpperCase(Locale.ROOT) + "  ·  PC", col, null);
                out.add(a);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static int gameColor(String name, Bitmap bm) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("valorant")) return 0xFFFF4655;
        if (n.contains("fortnite")) return 0xFF8B5CF6;
        if (bm != null) {
            Bitmap one = Bitmap.createScaledBitmap(bm, 1, 1, true);
            int c = one.getPixel(0, 0);
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.max(0.5f, hsv[1]); hsv[2] = Math.max(0.75f, hsv[2]);
            return Color.HSVToColor(hsv);
        }
        return C_GAMING;
    }

    /** Kachel aus Text: dunkler Verlauf in der Akzentfarbe, grosser Titel, Zusatzzeile. Optional ein abgedunkeltes Bild dahinter. */
    private Drawable textBanner(String title, String sub, int color, Drawable behind) {
        int w = 640, h = 360;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        if (behind != null) {
            behind.setBounds(0, 0, w, h);
            behind.draw(c);
            Paint dim = new Paint();
            dim.setShader(new LinearGradient(0, 0, 0, h, 0x66000000, 0xF0000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, dim);
        } else {
            Paint bg = new Paint();
            bg.setShader(new LinearGradient(0, 0, w, h, blendDark(color), 0xFF050505, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, bg);
            Paint glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
            glowP.setShader(new android.graphics.RadialGradient(w * 0.85f, h * 0.15f, 340,
                    (color & 0x00FFFFFF) | 0x99000000, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, glowP);
        }
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF);
        t.setTypeface(fBold);
        t.setLetterSpacing(-0.02f);
        float size = 72;
        t.setTextSize(size);
        while (t.measureText(title) > w - 88 && size > 36) { size -= 4; t.setTextSize(size); }
        String tt = title;
        while (t.measureText(tt + "…") > w - 88 && tt.length() > 3) tt = tt.substring(0, tt.length() - 1);
        if (!tt.equals(title)) tt = tt + "…";
        c.drawText(tt, 44, 250, t);
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setColor(0xFFB5B5B5);
        m.setTypeface(fMono);
        m.setTextSize(26);
        m.setLetterSpacing(0.08f);
        c.drawText(sub, 46, 300, m);
        Paint bar = new Paint();
        bar.setColor(color);
        c.drawRect(0, h - 8, w, h, bar);
        return tileFromBitmap(bmp);
    }

    private static int blendDark(int c) {
        return 0xFF000000 | ((((c >> 16) & 255) * 35 / 100) << 16) | ((((c >> 8) & 255) * 35 / 100) << 8) | ((c & 255) * 35 / 100);
    }

    // ---- Weiterschauen: was zuletzt in Video-Apps lief
    private String lastHistKey;

    private void recordHistory(String pkg, String title) {
        if (pkg == null || title == null || title.isEmpty() || "Unbekannt".equals(title)) return;
        if (appKind(pkg) != K_VIDEO) return;
        if (pkg.equals(vidPkg)) vidTitled = true;              // echter Titel bekannt -> kein Ersatz-Eintrag
        String key = pkg + "|" + title;
        if (key.equals(lastHistKey)) return;
        lastHistKey = key;
        try {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            org.json.JSONArray old = new org.json.JSONArray(p.getString("history", "[]"));
            org.json.JSONArray neu = new org.json.JSONArray();
            org.json.JSONObject e = new org.json.JSONObject();
            e.put("p", pkg); e.put("t", title); e.put("ts", System.currentTimeMillis());
            neu.put(e);
            for (int i = 0; i < old.length() && neu.length() < 8; i++) {
                org.json.JSONObject o = old.getJSONObject(i);
                if (o.optString("p").equals(pkg) && o.optString("t").equals(title)) continue;
                neu.put(o);
            }
            p.edit().putString("history", neu.toString()).apply();
            buildResumeApps();
            updateResumeRow();
        } catch (Exception ignored) { }
    }

    private void buildResumeApps() {
        resumeApps.clear();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("history", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                AppInfo src = null;
                for (AppInfo a : apps) if (a.pkg.equals(o.optString("p"))) src = a;
                if (src == null) continue;                       // App deinstalliert
                AppInfo a = new AppInfo();
                a.pkg = RESUME_PREFIX + i;
                a.label = o.optString("t");
                a.color = brandColor(src.pkg);
                a.launch = src.launch;
                a.src = src.pkg;
                a.banner = textBanner(a.label, (src.label + "  ·  " + ago(o.optLong("ts"))).toUpperCase(Locale.GERMANY), a.color,
                        src.banner != null ? src.banner.getConstantState() != null ? src.banner.getConstantState().newDrawable().mutate() : null : null);
                resumeApps.add(a);
            }
        } catch (Exception ignored) { }
        rebuildExtras();
    }

    private void rebuildExtras() {
        extraApps.clear();
        extraApps.addAll(resumeApps);
        extraApps.addAll(gameApps);
    }

    private static String ago(long ts) {
        long m = (System.currentTimeMillis() - ts) / 60000;
        if (m < 2) return "gerade eben";
        if (m < 60) return "vor " + m + " Min";
        long h = m / 60;
        if (h < 24) return "vor " + h + " Std";
        long d = h / 24;
        return d == 1 ? "gestern" : "vor " + d + " Tagen";
    }

    /** Eine automatische Reihe (Weiterschauen / PC-Spiele) in den Home Screen haengen. */
    private LinearLayout resumeLine;          // Reihe "Weiterschauen" - wird einzeln aktualisiert
    private TextView resumeCount;

    private void addAutoRow(String id, String name, List<AppInfo> list) {
        if (ROW_RESUME.equals(id)) { resumeLine = null; resumeCount = null; }
        if (list.isEmpty()) return;
        TextView cnt = tv(11, GRAY_2, fMono);
        cnt.setText(String.format(Locale.ROOT, "%02d", list.size()));
        rowsBox.addView(sectionHeader(name, cnt, rowsBox.getChildCount() == 0 ? 28 : 16));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout line = new LinearLayout(this);
        line.setClipChildren(false);
        line.setClipToPadding(false);
        line.setPadding(dp(32), dp(12), dp(32), dp(12));
        hs.addView(line);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.leftMargin = -dp(32);
        hlp.rightMargin = -dp(32);
        hlp.topMargin = -dp(8);
        hlp.bottomMargin = -dp(8);
        rowsBox.addView(hs, hlp);
        for (AppInfo a : list) line.addView(tile(a, id, true));
        if (ROW_RESUME.equals(id)) { resumeLine = line; resumeCount = cnt; }
    }

    /** Nur die Weiterschauen-Reihe neu fuellen statt die ganze Startseite neu aufzubauen. */
    private void updateResumeRow() {
        if (!isFront || !homeVisible) return;                     // beim naechsten Zurueckkommen wird ohnehin gezeichnet
        if (resumeLine == null || resumeApps.isEmpty()) { render(null, null); return; }   // Reihe entsteht/verschwindet
        View f = getCurrentFocus();
        int focusIdx = f != null && f.getParent() == resumeLine ? resumeLine.indexOfChild(f) : -1;
        resumeLine.removeAllViews();
        for (AppInfo a : resumeApps) resumeLine.addView(tile(a, ROW_RESUME, true));
        if (resumeCount != null) resumeCount.setText(String.format(Locale.ROOT, "%02d", resumeApps.size()));
        if (focusIdx >= 0 && resumeLine.getChildCount() > 0)
            resumeLine.getChildAt(Math.min(focusIdx, resumeLine.getChildCount() - 1)).requestFocus();
    }

    private boolean visible(String pkg) {
        return !hidden.contains(pkg) && find(pkg) != null;
    }

    // ================================================================ Reihen speichern / laden

    private void loadRows() {
        rows = new ArrayList<>();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String json = p.getString(KEY_ROWS, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Row r = new Row(o.getString("id"), o.getString("name"));
                    JSONArray a = o.getJSONArray("apps");
                    for (int j = 0; j < a.length(); j++) r.apps.add(a.getString(j));
                    rows.add(r);
                }
            } catch (Exception ignored) { rows.clear(); }
        }
        if (findRow(FAV) == null) {
            Row fav = new Row(FAV, "Favoriten");
            String old = p.getString(KEY_OLD_FAVS, null);   // Uebernahme aus alter Version
            if (old != null) {
                for (String s : old.split(",")) if (!s.isEmpty()) fav.apps.add(s);
            } else {
                for (String d : DEFAULT_FAVS) if (find(d) != null) fav.apps.add(d);
            }
            rows.add(0, fav);
            saveRows();
        }
    }

    private void saveRows() {
        try {
            JSONArray arr = new JSONArray();
            for (Row r : rows) {
                JSONObject o = new JSONObject();
                o.put("id", r.id);
                o.put("name", r.name);
                o.put("apps", new JSONArray(r.apps));
                arr.put(o);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ROWS, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    private Row findRow(String id) {
        if (rows == null) return null;
        for (Row r : rows) if (r.id.equals(id)) return r;
        return null;
    }

    /** Entfernt deinstallierte/ausgeblendete Apps und leere eigene Reihen. */
    private void cleanRows() {
        Iterator<Row> it = rows.iterator();
        while (it.hasNext()) {
            Row r = it.next();
            Iterator<String> ai = r.apps.iterator();
            while (ai.hasNext()) if (!visible(ai.next())) ai.remove();
            if (!r.id.equals(FAV) && r.apps.isEmpty()) it.remove();
        }
    }

    private void loadHidden() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_HIDDEN, "");
        hidden.clear();
        for (String s : raw.split(",")) if (!s.isEmpty()) hidden.add(s);
    }

    private void saveHidden() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_HIDDEN, TextUtils.join(",", hidden)).apply();
    }

    // ================================================================ Darstellen

    private void render(String focusRow, String focusPkg) {
        String keep = null;
        View cf = getCurrentFocus();
        if (focusPkg == null && cf != null && cf.getTag() instanceof String) keep = (String) cf.getTag();
        rowsBox.removeAllViews();
        allBox.removeAllViews();
        grid = newGrid();

        boolean resumeDone = false;
        for (Row r : rows) {
            if (!resumeDone && !r.id.equals(FAV)) { addAutoRow(ROW_RESUME, "WEITERSCHAUEN", resumeApps); resumeDone = true; }
            List<String> vis = new ArrayList<>();
            for (String p : r.apps) if (visible(p)) vis.add(p);
            if (vis.isEmpty() && !r.id.equals(FAV)) continue;

            TextView cnt = tv(11, GRAY_2, fMono);
            cnt.setText(String.format(Locale.ROOT, "%02d", vis.size()));
            rowsBox.addView(sectionHeader(r.name.toUpperCase(Locale.GERMANY), cnt,
                    rowsBox.getChildCount() == 0 ? 28 : 16));

            if (vis.isEmpty()) {
                TextView hint = tv(13, GRAY_1, fMono);
                hint.setText("$ Noch leer – App markieren, ☰ drücken, „Zu Favoriten“");
                hint.setPadding(0, dp(4), 0, dp(8));
                rowsBox.addView(hint);
                continue;
            }
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setClipChildren(false);
            hs.setClipToPadding(false);
            LinearLayout line = new LinearLayout(this);
            line.setClipChildren(false);
            line.setClipToPadding(false);   // sonst wird die vergroesserte Kachel am Innenrand abgeschnitten
            // Reihe ragt 32dp in den Rand hinein, damit die vergroesserte Kachel nicht abgeschnitten wird;
            // die Kacheln selbst bleiben genau an der Linie der Ueberschriften.
            line.setPadding(dp(32), dp(12), dp(32), dp(12));
            hs.addView(line);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
            hlp.leftMargin = -dp(32);
            hlp.rightMargin = -dp(32);
            hlp.topMargin = -dp(8);
            hlp.bottomMargin = -dp(8);
            rowsBox.addView(hs, hlp);
            for (String p : vis) line.addView(tile(find(p), r.id, true));
        }

        if (!resumeDone) addAutoRow(ROW_RESUME, "WEITERSCHAUEN", resumeApps);
        addAutoRow(ROW_GAMES, "PC-SPIELE", gameApps);

        boolean pcInRow = false;
        for (Row r : rows) if (r.apps.contains(PC_PKG)) pcInRow = true;
        int n = 0;
        List<List<AppInfo>> byCat = new ArrayList<>();
        for (int i = 0; i < CAT_NAMES.length; i++) byCat.add(new ArrayList<>());
        for (AppInfo a : apps) {
            if (hidden.contains(a.pkg)) continue;
            if (PC_PKG.equals(a.pkg) && pcInRow) continue;     // nicht doppelt anzeigen
            if (a.cat < 0) a.cat = category(a);
            byCat.get(a.cat).add(a);
            n++;
        }
        boolean firstGrid = true;
        for (int i = 0; i < CAT_NAMES.length; i++) {
            List<AppInfo> list = byCat.get(i);
            if (list.isEmpty()) continue;
            TextView cnt = tv(11, GRAY_2, fMono);
            cnt.setText(String.format(Locale.ROOT, "%02d", list.size()));
            allBox.addView(sectionHeader(CAT_NAMES[i], cnt, 16));
            GridLayout g = firstGrid ? grid : newGrid();
            firstGrid = false;
            for (AppInfo a : list) g.addView(tile(a, ALL, false));
            allBox.addView(g, new LinearLayout.LayoutParams(-2, -2));
        }
        appCount.setText(String.format(Locale.ROOT, "%02d", n));

        firstRowId = ALL;
        View ft = findFirstTile(rowsBox);
        if (ft != null) firstRowId = ((String) ft.getTag()).split("\\|")[0];
        if (firstRender) {
            if (splash != null) pendingIntro = true; else introRows();
        }

        if (menuLayer.getVisibility() == View.VISIBLE) return;
        View target = focusPkg != null ? root.findViewWithTag(focusRow + "|" + focusPkg)
                : keep != null ? root.findViewWithTag(keep) : null;
        if (target != null) target.requestFocus();
        else focusFirst();
        firstRender = false;
    }

    private void focusFirst() {
        View v = findFirstTile(rowsBox);
        if (v == null && grid.getChildCount() > 0) v = grid.getChildAt(0);
        if (v != null) v.requestFocus();
    }

    private View findFirstTile(View v) {
        if (v.getTag() instanceof String && ((String) v.getTag()).contains("|")) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findFirstTile(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    private View tile(AppInfo a, String rowId, boolean big) {
        FrameLayout t = new FrameLayout(this);
        t.setTag(rowId + "|" + a.pkg);
        t.setFocusable(true);
        t.setClickable(true);
        t.setClipToOutline(true);
        t.setAlpha(LITE ? 1f : 0.8f);

        final boolean moving = a.pkg.equals(movingPkg) && rowId.equals(movingRow);
        int radius = big ? 12 : 10;
        t.setBackground(rounded(SURFACE, 0, radius));

        if (a.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.banner);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            t.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            if (a.icon != null) iv.setImageDrawable(a.icon);
            int s = dp(big ? 48 : 34);
            box.addView(iv, new LinearLayout.LayoutParams(s, s));
            TextView l = tv(big ? 14 : 12, WHITE, fMedium);
            l.setText(a.label);
            l.setSingleLine(true);
            l.setEllipsize(TextUtils.TruncateAt.END);
            l.setGravity(Gravity.CENTER);
            l.setPadding(dp(8), dp(8), dp(8), 0);
            box.addView(l, new LinearLayout.LayoutParams(-1, -2));
            t.addView(box, new FrameLayout.LayoutParams(-1, -1));
        }

        GradientDrawable fg = new GradientDrawable();
        fg.setCornerRadius(dp(radius));
        fg.setColor(moving ? 0x260A72EF : 0x00000000);
        if (moving) fg.setStroke(dp(3), BLUE, dp(10), dp(6));
        else fg.setStroke(dp(1), BORDER);
        t.setForeground(fg);

        if (Build.VERSION.SDK_INT >= 28 && !LITE) {
            t.setOutlineSpotShadowColor(a.color);
            t.setOutlineAmbientShadowColor(a.color);
        }

        t.setOnFocusChangeListener((v, has) -> {
            if (!moving) fg.setStroke(has ? dp(2) : dp(1), has ? 0xFFFFFFFF : BORDER);
            v.animate().cancel();
            v.animate().setStartDelay(0)        // Verzoegerung vom Einblenden nicht uebernehmen
                    .scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f)
                    .alpha(LITE || has ? 1f : 0.8f)
                    .translationZ(has && !LITE ? dp(24) : 0)   // Schatten kosten auf schwachen Sticks viel
                    .setDuration(LITE ? 140 : 200)
                    .setInterpolator(new DecelerateInterpolator(1.6f))
                    .start();
            if (has) {
                lastTile = v;
                spotColor(a.color);
                ui.removeCallbacks(spotLater);
                ui.postDelayed(spotLater, LITE ? 180 : 230);   // nach dem automatischen Scrollen
                setHero(a.label, a.pkg, a.color);
                wizOnFocusColor(a.color);
                heroHint.setText(moving ? "  ◀ ▶ verschieben · OK fertig" : "  Optionen");
            }
        });
        t.setOnClickListener(v -> { if (moving) endMove(); else launchWithTransition(v, a); });
        t.setOnLongClickListener(v -> { if (!moving) openAppMenu(a, rowId); return true; });
        t.setOnKeyListener((v, keyCode, e) -> {
            if (moving) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (e.getAction() == KeyEvent.ACTION_DOWN)
                        moveBy(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP) openAppMenu(a, rowId);
                return true;
            }
            // Aus der obersten Reihe nach oben: zur "Jetzt laeuft"-Karte, sonst zu "Einstellungen"
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP && rowId.equals(firstRowId)) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    View up = npSection.getVisibility() == View.VISIBLE ? npPlay : settingsBtn;
                    if (up != null) up.requestFocus();
                }
                return true;
            }
            return false;
        });

        int m = dp(big ? 10 : 8);
        if (big) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(232), dp(131));
            lp.setMargins(0, m, m * 2, m);
            t.setLayoutParams(lp);
        } else {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(152);
            lp.height = dp(86);
            lp.setMargins(0, m, m * 2, m);
            t.setLayoutParams(lp);
        }
        return t;
    }

    // ================================================================ Verschieben (Punkt 2)

    private void startMove(String pkg, String rowId) {
        movingPkg = pkg;
        movingRow = rowId;
        render(rowId, pkg);
    }

    private void moveBy(int dir) {
        Row r = findRow(movingRow);
        if (r == null) { endMove(); return; }
        cleanRows();
        int i = r.apps.indexOf(movingPkg);
        int j = i + dir;
        if (i < 0 || j < 0 || j >= r.apps.size()) return;
        Collections.swap(r.apps, i, j);
        saveRows();
        render(movingRow, movingPkg);
    }

    private void endMove() {
        String p = movingPkg, r = movingRow;
        movingPkg = null;
        movingRow = null;
        render(r, p);
    }

    // ================================================================ Menues (Punkt 2, 3, 4)

    private void openAppMenu(AppInfo a, String rowId) {
        List<MItem> items = new ArrayList<>();
        items.add(new MItem("Öffnen", () -> launch(a)));
        if (PC_PKG.equals(a.pkg)) items.add(new MItem("Gaming-PC einrichten", this::openPcSetup));
        Row cur = findRow(rowId);
        Row fav = findRow(FAV);

        if (cur != null) {
            if (cur.apps.size() > 1)
                items.add(new MItem("Verschieben  ◀ ▶", () -> startMove(a.pkg, rowId)));
            items.add(new MItem("Aus „" + cur.name + "“ entfernen", () -> {
                cur.apps.remove(a.pkg);
                cleanRows();
                saveRows();
                render(ALL, a.pkg);
            }));
        }
        if (fav != null && !fav.apps.contains(a.pkg))
            items.add(new MItem("Zu Favoriten hinzufügen", () -> addToRow(fav, a, rowId)));

        items.add(new MItem("Zu Reihe hinzufügen …", () -> openRowPicker(a, rowId)));
        items.add(new MItem("App ausblenden", () -> {
            hidden.add(a.pkg);
            saveHidden();
            cleanRows();
            saveRows();
            toast(a.label + " ausgeblendet – zurückholen über Einstellungen");
            render(null, null);
            focusFirst();
        }, true));
        showMenu(a.label, items);
    }

    private void openRowPicker(AppInfo a, String fromRow) {
        List<MItem> items = new ArrayList<>();
        Set<String> existing = new HashSet<>();
        for (Row r : rows) {
            existing.add(r.name);
            if (r.id.equals(FAV) || r.apps.contains(a.pkg)) continue;
            items.add(new MItem("→  " + r.name, () -> addToRow(r, a, fromRow)));
        }
        for (String preset : ROW_PRESETS) {
            if (existing.contains(preset)) continue;
            items.add(new MItem("+  Neue Reihe „" + preset + "“", () -> {
                Row r = new Row("r_" + preset.toLowerCase(Locale.ROOT), preset);
                rows.add(r);
                addToRow(r, a, fromRow);
            }));
        }
        showMenu("Reihe für " + a.label, items);
    }

    private void addToRow(Row r, AppInfo a, String fromRow) {
        if (!r.apps.contains(a.pkg)) r.apps.add(a.pkg);
        saveRows();
        toast(a.label + " → " + r.name);
        render(r.id, a.pkg);
    }

    private void openSettingsMenu() {
        List<MItem> items = new ArrayList<>();
        // Fire-TV-Bereiche direkt (ohne Amazons Startseite), Zurueck fuehrt wieder zu CleanHome
        items.add(new MItem("FIRE TV", null));
        for (String[] fs : FIRE_SETTINGS) {
            if ("1".equals(fs[2])) items.add(new MItem(fs[0] + "   ↗ über Amazon", this::openSystemSettings));
            else items.add(new MItem(fs[0], () -> openFireSettings(fs[1])));
        }
        items.add(new MItem("⬆  Nach Updates suchen  ·  Version " + Updater.installedVersion(this), () -> checkUpdate(true)));
        items.add(new MItem("🩺  Diagnose (Ruhebildschirm, letzter Fehler)", this::showDiagnose));
        items.add(new MItem("STIMMUNG", null));
        items.add(new MItem("🔥  Kaminfeuer", () -> openAmbient(0)));
        items.add(new MItem("⛈  Gewitter am Fenster", () -> openAmbient(1)));
        items.add(new MItem("✦  Sternenhimmel", () -> openAmbient(2)));
        items.add(new MItem("🌌  Nordlicht", () -> openAmbient(3)));
        items.add(new MItem("SPEICHER", null));
        items.add(new MItem("RAM jetzt freimachen", () -> freeRam(null, false)));
        items.add(new MItem("Vor jedem App-Start freimachen  ·  " + (autoRam() ? "an" : "aus"), () -> {
            boolean n = !autoRam();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("auto_ram", n).apply();
            toast(n ? "RAM wird vor jedem App-Start freigemacht" : "Automatisches Freimachen aus");
        }));
        items.add(new MItem("CLEANHOME", null));
        items.add(new MItem("📱  Handy-Steuerung (QR-Code)", this::showRemoteQr));
        items.add(new MItem("Ausgeblendete Apps (" + hidden.size() + ")", this::openHiddenMenu));
        items.add(new MItem("WiZ-Licht  ·  " + (wizOn ? "automatisch" : "aus"), this::openWizMenu));
        items.add(new MItem("Govee-Licht  ·  " + (goveeOn ? "dimmt bei Streaming" : "aus"), this::openGoveeMenu));
        items.add(new MItem("Bildschirmschoner jetzt starten", () -> ui.postDelayed(showSaver, 300)));
        showMenu("Einstellungen", items);
    }

    private void openHiddenMenu() {
        List<MItem> items = new ArrayList<>();
        List<String> sorted = new ArrayList<>(hidden);
        Collections.sort(sorted);
        for (String p : sorted) {
            AppInfo a = find(p);
            String name = a != null ? a.label : p;
            items.add(new MItem("Einblenden:  " + name, () -> {
                hidden.remove(p);
                saveHidden();
                toast(name + " ist wieder da");
                render(ALL, p);
            }));
        }
        if (items.isEmpty()) items.add(new MItem("Keine Apps ausgeblendet", () -> { }));
        showMenu("Ausgeblendete Apps", items);
    }

    private void showMenu(String title, List<MItem> items) {
        if (focusBeforeMenu == null) focusBeforeMenu = getCurrentFocus();
        menuLayer.removeAllViews();

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(6), dp(6), dp(6));
        panel.setBackground(rounded(SURFACE, BORDER, 12));
        if (Build.VERSION.SDK_INT >= 21) panel.setElevation(dp(24));

        TextView head = tv(11, GRAY_2, fMono);
        head.setText(title.toUpperCase(Locale.GERMANY));
        head.setLetterSpacing(0.1f);
        head.setSingleLine(true);
        head.setEllipsize(TextUtils.TruncateAt.END);
        head.setPadding(dp(12), dp(10), dp(12), dp(8));
        panel.addView(head);

        View first = null, last = null;
        for (MItem mi : items) {
            if (mi.action == null) {                    // Zwischenueberschrift, nicht auswaehlbar
                TextView h = tv(10, GRAY_2, fMono);
                h.setText(mi.label);
                h.setLetterSpacing(0.15f);
                h.setPadding(dp(12), dp(14), dp(12), dp(6));
                panel.addView(h);
                continue;
            }
            TextView t = tv(15, mi.danger ? RED : WHITE, fRegular);
            t.setText(mi.label);
            t.setPadding(dp(12), dp(11), dp(16), dp(11));
            t.setFocusable(true);
            t.setClickable(true);
            GradientDrawable bg = rounded(0x00000000, 0, 8);
            t.setBackground(bg);
            t.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? 0xFF1F1F1F : 0x00000000);
                t.setTextColor(has ? (mi.danger ? RED : 0xFFFFFFFF) : (mi.danger ? 0xCCF2555A : 0xFFBDBDBD));
            });
            t.setTextColor(mi.danger ? 0xCCF2555A : 0xFFBDBDBD);
            t.setOnClickListener(v -> {
                hideMenuQuiet();
                mi.action.run();
                if (menuLayer.getVisibility() != View.VISIBLE) restoreFocusAfterMenu();
            });
            t.setId(View.generateViewId());
            t.setOnKeyListener((v, k, e) ->            // links/rechts verlaesst das Menue nicht
                    k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT);
            panel.addView(t, new LinearLayout.LayoutParams(-1, -2));
            if (first == null) first = t;
            last = t;
        }
        if (first != null) {                            // oben/unten bleibt die Auswahl im Menue
            first.setNextFocusUpId(first.getId());
            last.setNextFocusDownId(last.getId());
        }
        scroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        TextView foot = tv(11, GRAY_2, fMono);
        foot.setText("Zurück = schließen");
        foot.setPadding(dp(12), dp(8), dp(12), dp(6));
        panel.addView(foot);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(panel);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(520), -2, Gravity.CENTER);
        lp.topMargin = dp(40);
        lp.bottomMargin = dp(40);
        menuLayer.addView(sv, lp);

        menuLayer.setAlpha(0f);
        menuLayer.setVisibility(View.VISIBLE);
        menuLayer.animate().alpha(1f).setDuration(150).start();
        sv.setScaleX(0.96f);
        sv.setScaleY(0.96f);
        sv.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        if (first != null) first.requestFocus();
    }

    private void hideMenuQuiet() {
        scroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
        menuLayer.setVisibility(View.GONE);
        menuLayer.removeAllViews();
    }

    private void hideMenu() {
        if (menuLayer.getVisibility() != View.VISIBLE) return;
        hideMenuQuiet();
        restoreFocusAfterMenu();
    }

    private void restoreFocusAfterMenu() {
        View f = getCurrentFocus();
        View before = focusBeforeMenu;
        focusBeforeMenu = null;
        if (f != null && f.isAttachedToWindow() && f.isShown()) return;
        if (before != null && before.isAttachedToWindow() && before.isShown()) before.requestFocus();
        else focusFirst();
    }

    // ================================================================ PC-Gaming (Wake-on-LAN + Moonlight)

    private static final String PC_PKG = "zcrowd.pc.gaming";
    private static final String MOONLIGHT = "com.limelight";
    private static final int SUNSHINE_PORT = 47989;
    private static final int C_GAMING = 0xFF7C3AED;
    private static final String KEY_PC_IP = "pc_ip", KEY_PC_MAC = "pc_mac", KEY_PC_ADDED = "pc_added";
    private FrameLayout pcLayer;
    private TextView[] pcStepDot = new TextView[3], pcStepText = new TextView[3], pcStepInfo = new TextView[3];
    private TextView pcHeadline, pcFoot;
    private volatile boolean pcCancel;
    private String pcGameName;
    private final List<String[]> pcFound = new ArrayList<>();

    private AppInfo pcApp() {
        AppInfo a = new AppInfo();
        a.pkg = PC_PKG;
        a.label = "PC-Gaming";
        a.color = C_GAMING;
        a.banner = pcBanner();
        return a;
    }

    /** Selbst gezeichnetes Banner fuer die PC-Gaming-Kachel. */
    private Drawable pcBanner() {
        int w = 640, h = 360;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, w, h, 0xFF1B0B3A, 0xFF050505, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        Paint glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowP.setShader(new android.graphics.RadialGradient(w * 0.82f, h * 0.2f, 320,
                0x997C3AED, 0x007C3AED, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, glowP);
        Paint grid = new Paint();
        grid.setColor(0x14FFFFFF);
        grid.setStrokeWidth(1.5f);
        for (int x = 0; x <= w; x += 40) c.drawLine(x, 0, x, h, grid);
        for (int y = 0; y <= h; y += 40) c.drawLine(0, y, w, y, grid);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF);
        t.setTypeface(fBold);
        t.setTextSize(92);
        t.setLetterSpacing(-0.03f);
        c.drawText("PC-Gaming", 44, 222, t);
        Paint m = new Paint(Paint.ANTI_ALIAS_FLAG);
        m.setColor(0xFFA1A1A1);
        m.setTypeface(fMono);
        m.setTextSize(28);
        m.setLetterSpacing(0.12f);
        c.drawText("WAKE · STREAM · PLAY", 48, 284, m);
        Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        pill.setColor(0xFFEDEDED);
        c.drawRoundRect(new android.graphics.RectF(48, 52, 196, 104), 26, 26, pill);
        Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        pt.setColor(0xFF000000);
        pt.setTypeface(fMono);
        pt.setTextSize(26);
        c.drawText("▶ PLAY", 68, 87, pt);
        return tileFromBitmap(bmp);
    }

    private void readPcExtras(Intent i) {
        if (i == null) return;
        String ip = i.getStringExtra("pc_ip"), mac = i.getStringExtra("pc_mac");
        if (ip == null && mac == null) return;
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (ip != null) e.putString(KEY_PC_IP, ip.trim());
        if (mac != null) e.putString(KEY_PC_MAC, mac.trim().toUpperCase(Locale.ROOT).replace('-', ':'));
        e.apply();
        toast("Gaming-PC gespeichert");
    }

    private String pcIp() { return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PC_IP, ""); }
    private String pcMac() { return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_PC_MAC, ""); }

    private void startPcGaming() { startPcGaming(null); }

    private void startPcGaming(String game) {
        String ip = pcIp(), mac = pcMac();
        if (ip.isEmpty()) { openPcSetup(); return; }
        if (getPackageManager().getLaunchIntentForPackage(MOONLIGHT) == null) {
            toast("Moonlight ist nicht installiert (Amazon Appstore oder GitHub)");
            return;
        }
        pcStarting = true;
        pcGameName = game;
        scheduleLights(0);
        showPcLayer();
        pcCancel = false;
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            boolean on = isPcOnline(ip, 800);
            if (on) {
                pcStep(0, "done", "PC ist schon an");
            } else if (mac.isEmpty()) {
                pcStep(0, "fail", "MAC fehlt – PC einrichten");
                pcFail("Ohne MAC-Adresse kann ich den PC nicht wecken.");
                return;
            } else {
                for (int k = 0; k < 3; k++) sendWol(mac, ip);
                pcStep(0, "done", "Magic Packet → " + mac);
                pcStep(1, "run", "0 s");
                while (!pcCancel && System.currentTimeMillis() - t0 < 120_000) {
                    if (isPcOnline(ip, 1500)) { on = true; break; }
                    long sec = (System.currentTimeMillis() - t0) / 1000;
                    if (sec % 10 == 0) sendWol(mac, ip);
                    pcStep(1, "run", sec + " s");
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                }
            }
            if (pcCancel) return;
            if (!on) {
                pcStep(1, "fail", "keine Antwort");
                pcFail("PC nicht erreichbar. Wake-on-LAN im BIOS/Netzwerkkarte aktiv? Sunshine gestartet?");
                return;
            }
            long sec = (System.currentTimeMillis() - t0) / 1000;
            pcStep(1, "done", "online · " + ip + " · " + sec + " s");
            pcStep(2, "run", "starte …");
            try { Thread.sleep(700); } catch (InterruptedException ignored) { }
            if (pcCancel) return;
            ui.post(() -> {
                pcStep(2, "done", game != null ? game : "los geht's");
                Intent ml = getPackageManager().getLaunchIntentForPackage(MOONLIGHT);
                lastLaunchedPkg = MOONLIGHT;
                HomeWatchService.lastForegroundPkg = MOONLIGHT;
                pcStarting = false;
                boolean started = false;
                if (game != null) {
                    // Direkt ins Spiel: Moonlights eigener Verknuepfungs-Einstieg (PC + Spielname)
                    try {
                        Intent g = new Intent(Intent.ACTION_MAIN).setClassName(MOONLIGHT, "com.limelight.ShortcutTrampoline")
                                .putExtra("AppName", game).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (!gamePcUuid.isEmpty()) g.putExtra("UUID", gamePcUuid);
                        if (!gamePcName.isEmpty()) g.putExtra("Name", gamePcName);
                        startActivity(g);
                        started = true;
                    } catch (Exception ignored) { }
                }
                if (!started && ml != null) startActivity(ml.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                ui.postDelayed(this::hidePcLayer, 900);
            });
        }).start();
    }

    private void showPcLayer() {
        if (pcLayer == null) buildPcLayer();
        String[][] steps = {{"Wake-on-LAN senden", ""}, {"Warten bis der PC online ist", ""}, {pcGameName != null ? pcGameName + " starten" : "Moonlight starten", ""}};
        for (int i = 0; i < 3; i++) {
            pcStepText[i].setText(steps[i][0]);
            pcStep(i, "wait", "");
        }
        pcHeadline.setText("Gaming-PC wird gestartet");
        pcFoot.setText("Zurück = abbrechen");
        pcFoot.setTextColor(GRAY_2);
        pcLayer.setAlpha(0f);
        pcLayer.setVisibility(View.VISIBLE);
        pcLayer.animate().alpha(1f).setDuration(200).start();
    }

    private void hidePcLayer() {
        if (pcStarting) { pcStarting = false; scheduleLights(0); }
        if (pcLayer == null || pcLayer.getVisibility() != View.VISIBLE) return;
        pcLayer.animate().alpha(0f).setDuration(200).withEndAction(() -> pcLayer.setVisibility(View.GONE)).start();
    }

    private void buildPcLayer() {
        pcLayer = new FrameLayout(this);
        pcLayer.setBackgroundColor(0xD9000000);
        pcLayer.setClickable(true);
        pcLayer.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(28), dp(24), dp(28), dp(22));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x337C3AED, SURFACE, SURFACE});
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), BORDER);
        card.setBackground(bg);

        TextView tag = tv(11, GRAY_2, fMono);
        tag.setText("PC-GAMING  ·  " + pcIp());
        tag.setLetterSpacing(0.12f);
        card.addView(tag);
        pcHeadline = tv(28, WHITE, fBold);
        pcHeadline.setLetterSpacing(-0.03f);
        pcHeadline.setPadding(0, dp(6), 0, dp(18));
        card.addView(pcHeadline);

        for (int i = 0; i < 3; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));
            pcStepDot[i] = tv(15, GRAY_2, fMono);
            pcStepDot[i].setMinWidth(dp(26));
            row.addView(pcStepDot[i]);
            pcStepText[i] = tv(16, GRAY_1, fRegular);
            row.addView(pcStepText[i], new LinearLayout.LayoutParams(0, -2, 1f));
            pcStepInfo[i] = tv(12, GRAY_2, fMono);
            row.addView(pcStepInfo[i], lpLeft(20));
            card.addView(row);
            if (i < 2) {
                View sep = new View(this);
                sep.setBackgroundColor(0xFF1A1A1A);
                card.addView(sep, new LinearLayout.LayoutParams(-1, dp(1)));
            }
        }
        pcFoot = tv(11, GRAY_2, fMono);
        pcFoot.setPadding(0, dp(16), 0, 0);
        card.addView(pcFoot);

        pcLayer.addView(card, new FrameLayout.LayoutParams(dp(560), -2, Gravity.CENTER));
        root.addView(pcLayer, root.indexOfChild(saver), new FrameLayout.LayoutParams(-1, -1));
    }

    private void pcStep(int i, String state, String info) {
        ui.post(() -> {
            if (pcLayer == null) return;
            switch (state) {
                case "done": pcStepDot[i].setText("✓"); pcStepDot[i].setTextColor(GREEN); pcStepText[i].setTextColor(WHITE); break;
                case "run":  pcStepDot[i].setText("●"); pcStepDot[i].setTextColor(C_GAMING); pcStepText[i].setTextColor(WHITE); break;
                case "fail": pcStepDot[i].setText("✕"); pcStepDot[i].setTextColor(RED); pcStepText[i].setTextColor(WHITE); break;
                default:     pcStepDot[i].setText("○"); pcStepDot[i].setTextColor(GRAY_2); pcStepText[i].setTextColor(GRAY_2);
            }
            pcStepInfo[i].setText(info);
        });
    }

    private void pcFail(String msg) {
        ui.post(() -> {
            pcStarting = false;
            scheduleLights(0);
            pcHeadline.setText("Hat nicht geklappt");
            pcFoot.setText(msg + "   ·   Zurück = schließen");
            pcFoot.setTextColor(AMBER);
        });
    }

    private static boolean isPcOnline(String ip, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, SUNSHINE_PORT), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void sendWol(String mac, String ip) {
        try {
            String[] hex = mac.split("[:\\-]");
            if (hex.length != 6) return;
            byte[] macBytes = new byte[6];
            for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(hex[i], 16);
            byte[] pkt = new byte[6 + 16 * 6];
            for (int i = 0; i < 6; i++) pkt[i] = (byte) 0xFF;
            for (int i = 6; i < pkt.length; i += 6) System.arraycopy(macBytes, 0, pkt, i, 6);
            List<String> targets = new ArrayList<>();
            targets.add("255.255.255.255");
            if (ip != null && ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"))
                targets.add(ip.substring(0, ip.lastIndexOf('.')) + ".255");
            try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
                s.setBroadcast(true);
                for (String t : targets) for (int port : new int[]{9, 7}) {
                    s.send(new java.net.DatagramPacket(pkt, pkt.length, InetAddress.getByName(t), port));
                }
            }
        } catch (Exception ignored) { }
    }

    /** MAC aus der ARP-Tabelle lesen (klappt auf Fire OS 7 / Android 9). */
    private static String arpMac(String ip) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/net/arp"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] c = line.trim().split("\\s+");
                if (c.length >= 4 && c[0].equals(ip) && !c[3].equals("00:00:00:00:00:00")) return c[3].toUpperCase(Locale.ROOT);
            }
        } catch (Exception ignored) { }
        return "";
    }

    private void openPcSetup() {
        List<MItem> items = new ArrayList<>();
        String ip = pcIp(), mac = pcMac();
        items.add(new MItem("⌕  Gaming-PC im WLAN suchen (Sunshine)", this::scanPcs));
        for (String[] pc : pcFound) {
            items.add(new MItem("→  PC " + pc[0] + (pc[1].isEmpty() ? "  ·  MAC unbekannt" : "  ·  " + pc[1]), () -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_PC_IP, pc[0]).putString(KEY_PC_MAC, pc[1]).apply();
                if (pcLayer != null) { root.removeView(pcLayer); pcLayer = null; }
                toast(pc[1].isEmpty() ? "Gespeichert – MAC bitte per install.ps1 -PcMac setzen" : "Gaming-PC gespeichert");
            }));
        }
        if (!ip.isEmpty()) {
            items.add(new MItem("▶  Jetzt starten", this::startPcGaming));
            items.add(new MItem("⚡  WOL-Test senden", () -> {
                new Thread(() -> sendWol(pcMac(), pcIp())).start();
                toast("Magic Packet gesendet");
            }));
            items.add(new MItem("Konfiguration löschen", () -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_PC_IP).remove(KEY_PC_MAC).apply();
                toast("Gaming-PC entfernt");
            }, true));
        }
        String head = ip.isEmpty() ? "Gaming-PC einrichten" : "Gaming-PC · " + ip + (mac.isEmpty() ? "" : " · " + mac);
        showMenu(head, items);
    }

    /** Sucht im /24-Netz nach Rechnern mit offenem Sunshine-Port (der PC muss dafuer an sein). */
    private void scanPcs() {
        String me = localIp();
        if (!me.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) { toast("Kein Netzwerk"); return; }
        toast("Suche Gaming-PC … (PC muss an sein, Sunshine läuft)");
        String base = me.substring(0, me.lastIndexOf('.') + 1);
        new Thread(() -> {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(48);
            List<java.util.concurrent.Future<String>> fs = new ArrayList<>();
            for (int i = 1; i < 255; i++) {
                final String ip = base + i;
                fs.add(pool.submit(() -> isPcOnline(ip, 500) ? ip : null));
            }
            List<String[]> found = new ArrayList<>();
            for (java.util.concurrent.Future<String> f : fs) {
                try { String ip = f.get(); if (ip != null) found.add(new String[]{ip, arpMac(ip)}); } catch (Exception ignored) { }
            }
            pool.shutdown();
            ui.post(() -> {
                pcFound.clear();
                pcFound.addAll(found);
                toast(found.isEmpty() ? "Kein PC mit Sunshine gefunden" : found.size() + " PC(s) gefunden");
                openPcSetup();
            });
        }).start();
    }

    // ================================================================ WiZ-Licht (Backlights) – vollautomatisch
    //
    // Es gibt KEINE Modi zum Auswaehlen. Eine einzige Funktion (lightTarget) entscheidet aus der
    // aktuellen Situation, wie das Licht sein soll. Jede Aenderung (Fokus, App-Start, Musik, Video,
    // Bildschirmschoner, TV aus ...) ruft nur scheduleLights() auf. Dadurch:
    //  - ein Zustand, eine Wahrheit -> keine sich widersprechenden Befehle
    //  - Entprellung: schnelles Scrollen schickt nicht 20 Befehle, nur den letzten
    //  - gleicher Zielzustand wird nie doppelt gesendet (kein Flackern)
    //  - jeder neue Zustand wird nach 1,5 s einmal wiederholt (UDP kann Pakete verlieren)
    //  - Mindestabstand zwischen Befehlen, damit die Lampen nicht ueberfordert werden

    private static final String KEY_WIZ_IPS = "wiz_ips";
    private static final String KEY_WIZ_ON = "wiz_auto";
    private final WizLights wiz = new WizLights();
    private boolean wizOn;
    private final List<String> wizIps = new ArrayList<>();
    private final List<String> wizFound = new ArrayList<>();
    private int lastAppColor = BLUE;

    // Kontext, aus dem das Licht berechnet wird
    private boolean homeVisible = true;
    private boolean screenOn = true;
    private boolean pcStarting;
    private String lastLaunchedPkg;
    private String lightKey;            // zuletzt gesendeter Zielzustand
    private long lightSentAt;
    private static final long LIGHT_MIN_GAP_MS = 350;

    private static final int K_VIDEO = 1, K_AUDIO = 2, K_GAME = 3, K_OTHER = 0;
    private static final String[] VIDEO_PKGS = {
            "com.netflix.ninja", "com.amazon.avod", "com.amazon.firetv.youtube", "com.google.android.youtube.tv",
            "com.disney.disneyplus", "de.swr.ard.avp.mobile.android.amazon", "com.zdf.android.mediathek",
            "org.xbmc.kodi", "com.plexapp.android", "tv.twitch.android.app", "com.liskovsoft.smarttubetv",
            "com.liskovsoft.smarttubetv.beta", "com.wbd.stream", "de.prosiebensat1digital.seventv",
            "com.amazon.cloud9", "com.amazon.tv.photos"
    };
    private static final String[] AUDIO_PKGS = {
            "com.spotify.tv.android", "com.amazon.mp3", "deezer.android.tv", "com.apple.android.music",
            "tunein.player", "com.soundcloud.android"
    };

    private void loadWiz() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        // alte Versionen hatten Modi (app/cover/cinema) -> jeder davon wird zu "Automatisch"
        String old = p.getString("wiz_mode", null);
        wizOn = p.getBoolean(KEY_WIZ_ON, old != null && !"off".equals(old));
        wizIps.clear();
        for (String s : p.getString(KEY_WIZ_IPS, "").split(",")) if (!s.isEmpty()) wizIps.add(s);
        wiz.setLamps(wizIps);
    }

    private void saveWiz() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_WIZ_ON, wizOn)
                .remove("wiz_mode")
                .putString(KEY_WIZ_IPS, TextUtils.join(",", wizIps)).apply();
        wiz.setLamps(wizIps);
        lightKey = null;          // Zustand neu senden
    }

    private int appKind(String pkg) {
        if (pkg == null) return K_OTHER;
        if (MOONLIGHT.equals(pkg) || PC_PKG.equals(pkg) || pkg.startsWith(GAME_PREFIX)) return K_GAME;
        for (String v : VIDEO_PKGS) if (v.equals(pkg)) return K_VIDEO;
        for (String v : AUDIO_PKGS) if (v.equals(pkg)) return K_AUDIO;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                int c = getPackageManager().getApplicationInfo(pkg, 0).category;
                if (c == android.content.pm.ApplicationInfo.CATEGORY_VIDEO) return K_VIDEO;
                if (c == android.content.pm.ApplicationInfo.CATEGORY_AUDIO) return K_AUDIO;
                if (c == android.content.pm.ApplicationInfo.CATEGORY_GAME) return K_GAME;
            } catch (Exception ignored) { }
        }
        return K_OTHER;
    }

    private int stateOf(android.media.session.MediaController c) {
        android.media.session.PlaybackState st = c.getPlaybackState();
        return st == null ? 0 : st.getState();
    }

    /** Der Kern: aus der Situation den gewuenschten Lichtzustand berechnen. {weiss?, farbe/kelvin, helligkeit} */
    private int[] lightTarget() {
        final int P = android.media.session.PlaybackState.STATE_PLAYING;
        final int PA = android.media.session.PlaybackState.STATE_PAUSED;
        int[] t;
        if (!screenOn) {
            t = new int[]{1, 2700, 50};                               // TV aus -> normales Raumlicht
        } else if (saver != null && saver.getVisibility() == View.VISIBLE) {
            t = new int[]{1, 2200, 10};                               // Bildschirmschoner -> fast dunkel
        } else if (pcStarting) {
            t = new int[]{0, C_GAMING, 60};                           // PC wird gestartet -> lila
        } else if (!homeVisible) {
            return null;                                              // irgendeine App vorne -> Sync-Box (Cinematic)
        } else if (homeVisible) {
            android.media.session.MediaController music = null;
            for (android.media.session.MediaController c : watched)
                if (stateOf(c) == P && appKind(c.getPackageName()) != K_VIDEO && c.getMetadata() != null) { music = c; break; }
            if (music != null && npCtrl == music) t = new int[]{0, npColor, 70};      // Musik laeuft -> Cover-Farbe
            else if (!appTileFocused()) t = new int[]{1, 4000, 55};                     // Einstellungen, Menue, Leiste -> neutrales Weiss
            else t = new int[]{0, lastAppColor, 70};                                    // Farbe der markierten Kachel
        } else {
            android.media.session.MediaController video = null, music = null;
            int videoState = 0;
            for (android.media.session.MediaController c : watched) {
                int k = appKind(c.getPackageName()), s = stateOf(c);
                if (k == K_VIDEO && (s == P || s == PA) && (video == null || s == P)) { video = c; videoState = s; }
                if (k != K_VIDEO && s == P && c.getMetadata() != null && music == null) music = c;
            }
            // In jeder App leuchtet das Licht in ihrer Markenfarbe (Netflix rot, Disney+ blau ...),
            // nur die Helligkeit richtet sich danach, was gerade passiert.
            String fg = fgPkg();
            int brand = brandColor(fg);
            boolean justLaunched = android.os.SystemClock.uptimeMillis() - launchFlashAt < 2500;
            // Streaming-Dienste und Videos: CleanHome laesst die Lampen in Ruhe - sie laufen in dem Modus,
            // den sie vor CleanHome hatten (z. B. eigener Sync-/Szenen-Modus aus der WiZ-App).
            if (video != null || appKind(fg) == K_VIDEO) return null;
            if (music != null && npCtrl == music && appKind(fg) != K_AUDIO) t = new int[]{0, npColor, 70};  // Musik aus anderer App
            else t = new int[]{0, brand, 60};                                          // andere App
            if (justLaunched) t[2] = 85;                                               // beim Start kurz kraeftig
        }
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if ((h >= 23 || h < 6) && screenOn) t[2] = Math.min(t[2], 35);               // nachts gedaempft
        return t;
    }

    private final Runnable applyLights = this::applyLightsNow;
    private final Runnable resendLights = () -> { if (wizOn && lightKey != null) wiz.resend(); };

    /** Alle Ereignisse rufen nur das hier auf. Mehrere Aufrufe kurz hintereinander = ein Befehl. */
    private void scheduleLights(long delayMs) {
        ui.removeCallbacks(applyLights);
        ui.postDelayed(applyLights, delayMs);
    }

    private long launchFlashAt;

    /** Markenfarben bekannter Apps; sonst die aus dem App-Logo berechnete Farbe. */
    private static final java.util.Map<String, Integer> BRAND = new java.util.HashMap<>();
    static {
        BRAND.put("com.netflix.ninja", 0xFFE50914);
        BRAND.put("com.amazon.avod", 0xFF00A8E1);
        BRAND.put("com.disney.disneyplus", 0xFF113CCF);
        BRAND.put("com.amazon.firetv.youtube", 0xFFFF0000);
        BRAND.put("com.google.android.youtube.tv", 0xFFFF0000);
        BRAND.put("com.liskovsoft.smarttubetv", 0xFFFF0000);
        BRAND.put("com.liskovsoft.smarttubetv.beta", 0xFFFF0000);
        BRAND.put("com.spotify.tv.android", 0xFF1DB954);
        BRAND.put("tv.twitch.android.app", 0xFF9146FF);
        BRAND.put("com.wbd.stream", 0xFF002BE7);
        BRAND.put("com.zdf.android.mediathek", 0xFFFA7D19);
        BRAND.put("de.swr.ard.avp.mobile.android.amazon", 0xFF0060AF);
        BRAND.put("com.plexapp.android", 0xFFE5A00D);
        BRAND.put("org.xbmc.kodi", 0xFF17B2E7);
        BRAND.put("com.amazon.mp3", 0xFF25D1DA);
        BRAND.put("deezer.android.tv", 0xFFA238FF);
        BRAND.put("com.apple.android.music", 0xFFFA243C);
        BRAND.put("com.apple.atve.amazon.appletv", 0xFFAAAAAA);
        BRAND.put("de.prosiebensat1digital.seventv", 0xFFFFDD00);
        BRAND.put("com.dazn", 0xFFF8FF13);
        BRAND.put("com.sky.wow", 0xFF00E68A);
        BRAND.put("de.rtl.plus", 0xFFFF3C00);
        BRAND.put("com.crunchyroll.crunchyroid", 0xFFF47521);
        BRAND.put("com.limelight", C_GAMING);
    }

    private int brandColor(String pkg) {
        if (pkg == null) return lastAppColor;
        Integer b = BRAND.get(pkg);
        if (b != null) return b;
        AppInfo a = find(pkg);
        return a != null ? a.color : lastAppColor;
    }

    /** Bevor CleanHome nach dem Streaming wieder steuert: den Modus der Lampe merken (Szene, Weiss, Farbe). */
    // Sync-Modi der WiZ HDMI Sync Box (getModelConfig: syncboxAlgos 201-204), 201 = Cinematic
    private static final String SYNC_DEFAULT = "{\"state\":true,\"sceneId\":201,\"dimming\":26}";
    private final Runnable resendSync = () -> {
        if (EXT.equals(lightKey) && wizOn)
            wiz.restore(getSharedPreferences(PREFS, MODE_PRIVATE).getString("wiz_sync", SYNC_DEFAULT));
    };

    private void captureExternalThen(Runnable next) {
        wizCapturing = true;
        new Thread(() -> {
            String st = wiz.capture();
            ui.post(() -> {
                wizCapturing = false;
                // nur echte Sync-Modi (Szene 201-204) merken - z. B. wenn du in der WiZ-App
                // Helligkeit oder Modus (Cinematic/Relaxation/Vibrant/Rhythmic) geaendert hast
                if (st != null && st.matches(".*\"sceneId\":20[1-4].*")) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("wiz_sync", st).apply();
                }
                lightKey = "captured";
                next.run();
            });
        }, "wizCapture").start();
    }

    /** App, die gerade wirklich vorne ist (vom Waechter gemeldet, sonst die zuletzt gestartete). */
    private String fgPkg() {
        String f = HomeWatchService.lastForegroundPkg;
        return f != null ? f : lastLaunchedPkg;
    }

    private static java.lang.ref.WeakReference<MainActivity> inst;
    /** Vom Waechter: eine andere App ist nach vorne gekommen -> Licht neu berechnen. */
    static void foregroundChanged() {
        MainActivity a = inst != null ? inst.get() : null;
        if (a != null) a.ui.post(() -> { a.trackVideoSession(); a.scheduleLights(300); });
    }

    // ---- Weiterschauen ohne Titel: Netflix & Co. melden auf dem Fire TV oft keinen Titel.
    // Wer mindestens eine Minute in einer Video-App war, bekommt sie trotzdem in "Weiterschauen".
    private String vidPkg;
    private long vidSince;
    private boolean vidTitled;
    private static final long VIDEO_MIN_MS = 60_000L;

    private void trackVideoSession() {
        if (isFront) return;                                   // CleanHome selbst ist vorne
        String now = HomeWatchService.lastForegroundPkg;
        if (now != null && now.equals(vidPkg)) return;
        closeVideoSession();
        if (now != null && appKind(now) == K_VIDEO) {
            vidPkg = now;
            vidSince = System.currentTimeMillis();
            vidTitled = false;
        }
    }

    private void closeVideoSession() {
        String pkg = vidPkg;
        vidPkg = null;
        if (pkg == null || vidTitled || System.currentTimeMillis() - vidSince < VIDEO_MIN_MS) return;
        AppInfo app = find(pkg);
        if (app != null) recordHistory(pkg, app.label);
    }

    private String lightManual;   // vom Handy gesetzt: "bright" / "dim" / "off", null = Automatik
    private String lightManualSent;

    // Zustand der Lampen, den sie beim Streaming haben sollen ("ihr eigener Modus"). Wird jedes Mal gemerkt,
    // wenn man Streaming verlaesst, und beim naechsten Streaming wiederhergestellt.
    private static final String EXT = "external";
    private String wizExternal;
    private boolean wizCapturing;

    // ================================================================ Absturz-Protokoll + Ruhebildschirm-Einstellungen

    /** Stuerzt CleanHome ab, wird der Grund gespeichert (Einstellungen -> "Letzter Fehler"). */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                String txt = new java.text.SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(new java.util.Date())
                        + " · v" + Updater.installedVersion(this) + " · " + t.getName() + "\n" + sw;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("last_crash", txt.length() > 4000 ? txt.substring(0, 4000) : txt).commit();
                java.io.File d = getExternalFilesDir(null);
                if (d != null) try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(d, "crash.txt"), true)) { w.write(txt + "\n\n"); }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    private static final String DREAM = "de.cleanhome.launcher/de.cleanhome.launcher.SaverDream";

    /**
     * Prueft, ob Fire OS noch den CleanHome-Ruhebildschirm nutzt (ein Fire-OS- oder App-Update kann das zuruecksetzen).
     * Mit der per ADB vergebenen Berechtigung WRITE_SECURE_SETTINGS repariert CleanHome es selbst.
     */
    private void ensureSaverSettings() {
        try {
            android.content.ContentResolver cr = getContentResolver();
            String comp = android.provider.Settings.Secure.getString(cr, "screensaver_components");
            String en = android.provider.Settings.Secure.getString(cr, "screensaver_enabled");
            boolean ok = DREAM.equals(comp) && "1".equals(en);
            if (ok) return;
            if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.provider.Settings.Secure.putString(cr, "screensaver_components", DREAM);
                android.provider.Settings.Secure.putString(cr, "screensaver_enabled", "1");
                android.provider.Settings.Secure.putString(cr, "screensaver_activate_on_sleep", "1");
                android.provider.Settings.Secure.putString(cr, "screensaver_activate_on_dock", "1");
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("saver_repaired",
                        new java.text.SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(new java.util.Date())).apply();
            } else {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("saver_broken", String.valueOf(comp)).apply();
            }
        } catch (Exception ignored) { }
    }

    private void showDiagnose() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String comp = null;
        try { comp = android.provider.Settings.Secure.getString(getContentResolver(), "screensaver_components"); } catch (Exception ignored) { }
        List<MItem> items = new ArrayList<>();
        items.add(new MItem("RUHEBILDSCHIRM", null));
        items.add(new MItem((DREAM.equals(comp) ? "✓  Fire OS nutzt den CleanHome-Ruhebildschirm" : "✗  Fire OS nutzt: " + comp), () -> ui.postDelayed(this::showDiagnose, 150)));
        String rep = p.getString("saver_repaired", null);
        if (rep != null) items.add(new MItem("Automatisch repariert am " + rep, () -> ui.postDelayed(this::showDiagnose, 150)));
        items.add(new MItem("LETZTER FEHLER", null));
        String c = p.getString("last_crash", null);
        if (c == null) items.add(new MItem("Kein Absturz gespeichert", () -> ui.postDelayed(this::showDiagnose, 150)));
        else for (String line : c.split("\n", 6)) if (!line.trim().isEmpty()) items.add(new MItem(line.trim().length() > 90 ? line.trim().substring(0, 90) + "…" : line.trim(), () -> ui.postDelayed(this::showDiagnose, 150)));
        showMenu("Diagnose · Version " + Updater.installedVersion(this), items);
    }

    // ================================================================ Selbst-Update

    private static final long UPDATE_EVERY = 6 * 60 * 60 * 1000L;
    private volatile boolean updateBusy;
    private int updatePending = -1;          // gefundene neuere Version, wird gezeigt, sobald CleanHome vorne ist

    private final Runnable updateTick = new Runnable() {
        @Override public void run() {
            checkUpdate(false);
            ui.postDelayed(this, UPDATE_EVERY);
        }
    };

    private void checkUpdate(boolean manual) {
        if (updateBusy) return;
        updateBusy = true;
        if (manual) toast("Suche nach Updates …");
        new Thread(() -> {
            int latest = Updater.latestVersion(), mine = Updater.installedVersion(this);
            ui.post(() -> {
                updateBusy = false;
                if (latest < 0) { if (manual) toast("GitHub gerade nicht erreichbar"); return; }
                if (latest <= mine) { if (manual) toast("CleanHome ist aktuell · Version " + mine); return; }
                updatePending = latest;
                maybeOfferUpdate();
            });
        }, "updateCheck").start();
    }

    /** Update anbieten - aber nur auf dem Home Screen, nie mitten im Film. */
    private void maybeOfferUpdate() {
        if (updatePending < 0 || !isFront || !homeVisible || menuLayer.getVisibility() == View.VISIBLE
                || (saver != null && saver.getVisibility() == View.VISIBLE) || ambientLayer != null || splash != null) return;
        final int v = updatePending;
        updatePending = -1;
        List<MItem> items = new ArrayList<>();
        items.add(new MItem("⬆  Jetzt installieren", () -> runUpdate(v)));
        items.add(new MItem("Später", () -> { }));
        showMenu("Neue CleanHome-Version " + v + " verfügbar (installiert: " + Updater.installedVersion(this) + ")", items);
    }

    private void runUpdate(int v) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast("Einmalig erlauben: CleanHome darf Apps installieren – dann nochmal „Nach Updates suchen“");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                lastLeftByLaunch = System.currentTimeMillis();
                settingsOpenedAt = System.currentTimeMillis();
            } catch (Exception ignored) { }
            return;
        }
        toast("Lade Version " + v + " …");
        new Thread(() -> {
            final int[] last = {-1};
            java.io.File f = Updater.download(this, pc -> {
                if (pc / 25 != last[0]) { last[0] = pc / 25; ui.post(() -> toast("Update wird geladen · " + pc + " %")); }
            });
            ui.post(() -> {
                if (f == null) { toast("Download fehlgeschlagen – später nochmal versuchen"); return; }
                toast("Gleich erscheint „Installieren“ – einmal OK drücken");
                lastLeftByLaunch = System.currentTimeMillis();
                settingsOpenedAt = System.currentTimeMillis();       // Waechter nicht dazwischenfunken lassen
                if (!Updater.install(this, f)) toast("Installation konnte nicht gestartet werden");
            });
        }, "update").start();
    }

    // ================================================================ Stimmungs-Szenen

    private FrameLayout ambientLayer;
    private int ambientType = -1;
    private long lightFlashUntil;
    private final List<View> hiddenUnderAmbient = new ArrayList<>();

    private static final String[] AMBIENT_FILES = {"kamin.mp4", "regen.mp4", "sterne.mp4", "nordlicht.mp4"};
    private static final String[] AMBIENT_NAMES = {"Kaminfeuer", "Gewitter am Fenster", "Sternenhimmel", "Nordlicht"};
    // Eingebaute Szenen-Videos (Pexels, kostenlos nutzbar, Full HD). Neue Videos = neue Adresse + neue Version,
    // dann laedt der Stick sie beim naechsten Oeffnen selbst nach.
    private static final String[] AMBIENT_URLS = {
            // cinematisch: dunkle Flammen vor Schwarz, Regen mit Blitzen, Sternen-Zeitraffer, Polarlicht
            "https://videos.pexels.com/video-files/30796586/13171921_1920_1080_25fps.mp4",
            "https://videos.pexels.com/video-files/5930874/5930874-hd_1920_1080_24fps.mp4",
            "https://videos.pexels.com/video-files/10719866/10719866-hd_1920_1080_25fps.mp4",
            "https://videos.pexels.com/video-files/3222269/3222269-hd_1920_1080_24fps.mp4"};
    private volatile boolean ambientLoading;

    /** Video fehlt oder ist veraltet -> direkt am Stick herunterladen, danach abspielen. */
    private void downloadAmbient(int type, java.io.File f) {
        if (ambientLoading) { toast("Video wird schon geladen …"); return; }
        ambientLoading = true;
        toast(AMBIENT_NAMES[type] + " wird geladen …");
        new Thread(() -> {
            boolean ok = false;
            java.io.File tmp = new java.io.File(f.getParentFile(), f.getName() + ".part");
            try {
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(AMBIENT_URLS[type]).openConnection();
                h.setConnectTimeout(10000); h.setReadTimeout(20000);
                h.setRequestProperty("User-Agent", "Mozilla/5.0 (CleanHome)");
                h.setInstanceFollowRedirects(true);
                int len = h.getContentLength();
                try (java.io.InputStream in = h.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] b = new byte[128 * 1024]; long done = 0; int r, last = -1;
                    while ((r = in.read(b)) > 0) {
                        out.write(b, 0, r); done += r;
                        if (len > 0) { int pc = (int) (done * 100 / len); if (pc / 20 != last) { last = pc / 20; final int fp = pc; ui.post(() -> toast(AMBIENT_NAMES[type] + " · " + fp + " %")); } }
                    }
                }
                ok = tmp.length() > 1_000_000 && (len <= 0 || tmp.length() == len) && tmp.renameTo(f);
                if (ok) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("ambient_url_" + type, AMBIENT_URLS[type]).apply();
            } catch (Exception ignored) { }
            if (!ok) tmp.delete();
            final boolean done = ok;
            ui.post(() -> {
                ambientLoading = false;
                if (done) { if (isFront && homeVisible) openAmbient(type); }
                else toast("Video konnte nicht geladen werden – Internet prüfen");
            });
        }, "ambientDownload").start();
    }
    private android.view.TextureView ambientTexture;
    private android.media.MediaPlayer ambientPlayer;
    private Bitmap vlBitmap;
    private float vlR, vlG, vlB, vlDim;
    private String vlSent;

    /** Bild des Videos klein auslesen -> Lichtfarbe. Helle Pixel zaehlen viel mehr als dunkle (Flamme statt Schwarz). */
    private final Runnable videoLights = new Runnable() {
        @Override public void run() {
            if (ambientLayer == null || ambientTexture == null) return;
            try {
                if (vlBitmap == null) vlBitmap = Bitmap.createBitmap(48, 27, Bitmap.Config.ARGB_8888);
                Bitmap b = ambientTexture.getBitmap(vlBitmap);
                if (b != null) {
                    double sr = 0, sg = 0, sb = 0, sw = 0; int maxL = 0;
                    for (int y = 0; y < 27; y++) for (int x = 0; x < 48; x++) {
                        int c = b.getPixel(x, y);
                        int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                        int lum = (r * 3 + g * 6 + bl) / 10;
                        if (lum > maxL) maxL = lum;
                        double w = (double) lum * lum * lum;          // helle Stellen dominieren
                        sr += r * w; sg += g * w; sb += bl * w; sw += w;
                    }
                    if (sw > 0) {
                        float r = (float) (sr / sw), g = (float) (sg / sw), bl = (float) (sb / sw);
                        float mx = Math.max(r, Math.max(g, bl));
                        if (mx > 0) { r = r * 255f / mx; g = g * 255f / mx; bl = bl * 255f / mx; }   // volle Farbe, Helligkeit separat
                        float dim = 10 + 90 * (float) Math.pow(maxL / 255.0, 1.4);
                        float a = ambientType == 0 ? 0.65f : 0.4f;    // Feuer schnell, Rest ruhiger
                        vlR += (r - vlR) * a; vlG += (g - vlG) * a; vlB += (bl - vlB) * a;
                        // Blitze sofort durchlassen, sonst weich
                        vlDim = dim > vlDim + 35 ? dim : vlDim + (dim - vlDim) * a;
                        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
                        int d = Math.round(vlDim * ((h >= 23 || h < 6) ? 0.6f : 1f));
                        String j = "{\"state\":true,\"r\":" + Math.round(vlR) + ",\"g\":" + Math.round(vlG)
                                + ",\"b\":" + Math.round(vlB) + ",\"dimming\":" + Math.max(10, Math.min(100, d)) + "}";
                        if (!j.equals(vlSent) && wizOn && lightManual == null) { vlSent = j; wiz.restore(j); }
                    }
                }
            } catch (Exception ignored) { }
            ui.postDelayed(this, ambientType == 0 ? 180 : 300);
        }
    };

    /** Spielt ein echtes Video (vom PC mit stimmung.ps1 aufgespielt) bildschirmfuellend in Endlosschleife. */
    private void openAmbient(int type) {
        hideMenu();
        hideSaver();
        if (ambientLayer != null) closeAmbient();
        java.io.File dir = getExternalFilesDir("ambient");
        java.io.File f = dir != null ? new java.io.File(dir, AMBIENT_FILES[type]) : null;
        if (f == null) { toast("Kein Speicher verfügbar"); return; }
        String knownUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString("ambient_url_" + type, null);
        // App-Update brachte ein neues Video (unbekannte Herkunft = altes, vom PC aufgespieltes Video -> erneuern)
        boolean stale = knownUrl == null || !knownUrl.equals(AMBIENT_URLS[type]);
        if (!f.exists() || f.length() < 1_000_000 || stale) { downloadAmbient(type, f); return; }
        ambientType = type;
        FrameLayout l = new FrameLayout(this);
        l.setBackgroundColor(BLACK);
        l.setClickable(true);
        final android.view.TextureView vv = new android.view.TextureView(this);
        l.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        View cover = new View(this);                       // schwarz, bis das erste Bild da ist (kein Flackern)
        cover.setBackgroundColor(BLACK);
        l.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = tv(13, 0x88FFFFFF, fMono);
        hint.setText(AMBIENT_NAMES[type].toUpperCase(Locale.GERMANY) + "  ·  BELIEBIGE TASTE = ZURÜCK");
        hint.setLetterSpacing(0.25f);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = dp(28);
        l.addView(hint, hl);
        root.addView(l, new FrameLayout.LayoutParams(-1, -1));
        ambientLayer = l;
        ambientTexture = vv;
        final String path = f.getAbsolutePath();
        vv.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                try {
                    android.media.MediaPlayer mp = new android.media.MediaPlayer();
                    ambientPlayer = mp;
                    mp.setDataSource(path);
                    mp.setSurface(new android.view.Surface(st));
                    mp.setLooping(true);
                    mp.setOnPreparedListener(m -> {
                        m.start();
                        cover.animate().alpha(0f).setStartDelay(250).setDuration(1500).start();
                        hint.animate().alpha(0f).setStartDelay(4000).setDuration(1500).start();
                    });
                    mp.setOnErrorListener((m, what, extra) -> {
                        toast("Das Video lässt sich nicht abspielen");
                        closeAmbient();
                        return true;
                    });
                    mp.prepareAsync();
                } catch (Exception e) { toast("Das Video lässt sich nicht abspielen"); closeAmbient(); }
            }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) { return true; }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { }
        });
        ui.postDelayed(() -> {                              // darunter liegende Ebenen nicht mehr mitzeichnen
            if (ambientLayer != l) return;
            eqVisibilityCheck();
            int idx = root.indexOfChild(l);
            for (int k = 0; k < idx; k++) {
                View v = root.getChildAt(k);
                if (v.getVisibility() == View.VISIBLE) { v.setVisibility(View.INVISIBLE); hiddenUnderAmbient.add(v); }
            }
        }, 600);
        // Licht: CleanHome liest das laufende Video selbst aus (die Sync Box sieht an den dunklen Bildraendern
        // nur Schwarz) - helle Bildteile wie Flammen, Blitze oder Polarlicht geben Farbe und Helligkeit vor.
        if (wizOn && wiz.hasLamps() && lightManual == null) {
            lightKey = "video";
            ui.removeCallbacks(resendLights);
            ui.removeCallbacks(resendSync);
            wiz.cancel();
            vlR = vlG = vlB = 0; vlDim = 10; vlSent = null;
            ui.removeCallbacks(videoLights);
            ui.postDelayed(videoLights, 800);
        }
        ui.removeCallbacks(showSaver);
    }

    private void closeAmbient() {
        final FrameLayout l = ambientLayer;
        if (l == null) return;
        ambientLayer = null;
        ambientType = -1;
        for (View v : hiddenUnderAmbient) v.setVisibility(View.VISIBLE);
        hiddenUnderAmbient.clear();
        ui.post(this::eqVisibilityCheck);
        ui.removeCallbacks(videoLights);
        final android.media.MediaPlayer mp = ambientPlayer;
        ambientPlayer = null;
        ambientTexture = null;
        l.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            try { if (mp != null) { mp.stop(); mp.release(); } } catch (Exception ignored) { }
            root.removeView(l);
        }).start();
        ui.removeCallbacks(resendSync);
        lightKey = null;
        scheduleLights(0);
        resetIdle();
        focusFirst();
    }

    // ================================================================ Controller erkennen

    private long lastPadAt;
    private final android.hardware.input.InputManager.InputDeviceListener padListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int id) { ui.postDelayed(() -> onPadAdded(id), 400); }
                @Override public void onInputDeviceRemoved(int id) { }
                @Override public void onInputDeviceChanged(int id) { }
            };

    private static boolean isGamepad(android.view.InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        int s = d.getSources();
        return (s & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (s & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
    }

    private void onPadAdded(int id) {
        android.view.InputDevice d = android.view.InputDevice.getDevice(id);
        if (!isGamepad(d)) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPadAt < 60_000) return;                    // kurzes Wackeln der Verbindung ignorieren
        lastPadAt = now;
        int vendor = d.getVendorId();
        String kind; int col;
        if (vendor == 0x045E) { kind = "Xbox-Controller"; col = 0xFF107C10; }
        else if (vendor == 0x054C) { kind = "PlayStation-Controller"; col = 0xFF0070D1; }
        else { kind = "Controller"; col = C_GAMING; }
        // Licht leuchtet einmal in der Controller-Farbe auf
        if (wizOn && wiz.hasLamps() && lightManual == null && ambientLayer == null) {
            lightFlashUntil = now + 1400;
            wiz.restore("{\"state\":true,\"r\":" + Color.red(col) + ",\"g\":" + Color.green(col) + ",\"b\":" + Color.blue(col) + ",\"dimming\":100}");
            ui.postDelayed(() -> { lightKey = null; scheduleLights(0); }, 1500);
        }
        if (!isFront) { toast("🎮 " + kind + " verbunden"); return; }   // in einer App: nicht dazwischenfunken
        if (ambientLayer != null) closeAmbient();
        hideSaver();
        // in die Reihe PC-Spiele springen (oder auf die PC-Kachel)
        View target = !gameApps.isEmpty() ? root.findViewWithTag(ROW_GAMES + "|" + gameApps.get(0).pkg) : null;
        if (target == null) {
            for (Row r : rows) if (r.apps.contains(PC_PKG)) target = root.findViewWithTag(r.id + "|" + PC_PKG);
        }
        if (target == null) target = root.findViewWithTag(ALL + "|" + PC_PKG);
        if (target != null) target.requestFocus();
        List<MItem> items = new ArrayList<>();
        items.add(new MItem("▶  PC starten", this::startPcGaming));
        if (!gameApps.isEmpty()) {
            items.add(new MItem("SPIEL DIREKT STARTEN", null));
            for (int i = 0; i < Math.min(6, gameApps.size()); i++) {
                final AppInfo g = gameApps.get(i);
                items.add(new MItem(g.label, () -> launch(g)));
            }
        }
        items.add(new MItem("Nicht jetzt", () -> { }));
        showMenu("🎮  " + kind + " verbunden – PC starten?", items);
    }

    private void applyLightsNow() {
        if (ambientLayer != null || android.os.SystemClock.uptimeMillis() < lightFlashUntil) return;   // Video-Szene (Sync Box) / Aufleuchten
        if (lightManual != null) {
            if (lightManual.equals(lightManualSent)) return;
            lightManualSent = lightManual;
            lightKey = null;
            boolean w = wiz.hasLamps(), g = false;   // Govee: nur das programmierte Dimmen, sonst wie eingestellt
            switch (lightManual) {
                case "bright": if (w) wiz.white(3200, 100); if (g) { govee.power(true); govee.brightness(100); } break;
                case "dim":    if (w) wiz.white(2400, 20);  if (g) { govee.power(true); govee.brightness(15); } break;
                default:       if (w) wiz.off();            if (g) govee.power(false); break;
            }
            return;
        }
        if (lightManualSent != null) {             // zurueck zur Automatik
            lightManualSent = null;
            lightKey = null;
            govee.forget();
        }
        applyGovee();
        if (!wizOn || !wiz.hasLamps() || wizCapturing) return;
        if (screenOn && saver != null && saver.getVisibility() == View.VISIBLE) { sendSaverLights(); return; }
        int[] t = lightTarget();
        if (t == null) {
            // Eine App ist vorne: WiZ HDMI Sync Box in ihren Sync-Modus schalten (Cinematic = Szene 201,
            // bzw. der Sync-Modus, den du zuletzt in der WiZ-App gewaehlt hattest). Danach schickt
            // CleanHome nichts mehr - die Box reagiert auf Film, Serie oder Spiel.
            if (EXT.equals(lightKey)) return;
            lightKey = EXT;
            ui.removeCallbacks(resendLights);
            wiz.cancel();
            String sync = getSharedPreferences(PREFS, MODE_PRIVATE).getString("wiz_sync", SYNC_DEFAULT);
            wiz.restore(sync);
            ui.removeCallbacks(resendSync);
            ui.postDelayed(resendSync, 1500);                     // UDP absichern
            return;
        }
        if (EXT.equals(lightKey)) {                                // zurueck zu CleanHome: erst Sync-Einstellung merken
            ui.removeCallbacks(resendSync);
            captureExternalThen(() -> { lightKey = null; applyLightsNow(); });
            return;
        }
        String key;
        if (t[0] == 1) key = "w" + t[1] + ":" + t[2];
        else key = "c" + (Color.red(t[1]) >> 3) + "," + (Color.green(t[1]) >> 3) + "," + (Color.blue(t[1]) >> 3) + ":" + t[2];
        if (key.equals(lightKey)) return;                          // schon so -> nichts senden
        long wait = LIGHT_MIN_GAP_MS - (android.os.SystemClock.uptimeMillis() - lightSentAt);
        if (wait > 0) { scheduleLights(wait); return; }            // zu schnell -> kurz spaeter
        lightKey = key;
        lightSentAt = android.os.SystemClock.uptimeMillis();
        if (t[0] == 1) wiz.white(t[1], t[2]); else wiz.fadeTo(t[1], t[2]);
        ui.removeCallbacks(resendLights);
        ui.postDelayed(resendLights, 1500);                       // einmal absichern
    }

    // ---- kleine Helfer, die die restliche App aufruft
    private void wizOnFocusColor(int color) { lastAppColor = color; scheduleLights(250); }

    /** Ist gerade eine App-Kachel markiert? (Kacheln tragen den Tag "reihe|paket") */
    private boolean appTileFocused() {
        if (menuLayer != null && menuLayer.getVisibility() == View.VISIBLE) return false;
        View f = getCurrentFocus();
        return f != null && f.getTag() instanceof String && ((String) f.getTag()).contains("|");
    }
    private void wizOnCover(int color) { scheduleLights(150); }
    private void wizHome() { homeVisible = true; scheduleLights(50); }
    private void wizLaunch() {
        launchFlashAt = android.os.SystemClock.uptimeMillis();
        scheduleLights(400);
        ui.postDelayed(() -> scheduleLights(0), 2700);   // nach dem kurzen Aufleuchten in den passenden Modus
    }
    private void wizSaver(boolean on) { scheduleLights(on ? 0 : 50); }

    private final android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            screenOn = !Intent.ACTION_SCREEN_OFF.equals(i.getAction());
            lightKey = null;
            scheduleLights(screenOn ? 300 : 0);
        }
    };

    // ================================================================ Handy-Steuerung

    private RemoteServer remote;
    private FrameLayout qrLayer;

    private String remoteKey() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String k = p.getString("remote_key", null);
        if (k == null) {
            String abc = "abcdefghjkmnpqrstuvwxyz23456789";
            StringBuilder b = new StringBuilder();
            java.security.SecureRandom r = new java.security.SecureRandom();
            for (int i = 0; i < 10; i++) b.append(abc.charAt(r.nextInt(abc.length())));
            k = b.toString();
            p.edit().putString("remote_key", k).apply();
        }
        return k;
    }

    private String remoteUrl() { return "http://" + localIp() + ":" + RemoteServer.PORT + "/?k=" + remoteKey(); }

    private void startRemote() {
        if (remote != null) remote.stop();
        remote = new RemoteServer(remoteKey(), this::remoteApi);
        remote.start();
    }

    /** Befehle vom Handy (laeuft im Server-Thread; alles mit Oberflaeche geht ueber ui.post). */
    private String remoteApi(String cmd, java.util.Map<String, String> q) {
        switch (cmd) {
            case "apps": {
                org.json.JSONArray arr = new org.json.JSONArray();
                final List<AppInfo> copy = new ArrayList<>();
                final Object lock = new Object();
                final boolean[] done = {false};
                ui.post(() -> { synchronized (lock) { for (AppInfo a : apps) if (!hidden.contains(a.pkg)) copy.add(a); done[0] = true; lock.notifyAll(); } });
                synchronized (lock) { long end = System.currentTimeMillis() + 2000; while (!done[0] && System.currentTimeMillis() < end) { try { lock.wait(200); } catch (InterruptedException ignored) { } } }
                try {
                    for (AppInfo a : copy) {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("n", a.label); o.put("p", a.pkg); o.put("c", brandColor(a.pkg));
                        arr.put(o);
                    }
                    return new org.json.JSONObject().put("apps", arr).toString();
                } catch (Exception e) { return "{\"apps\":[]}"; }
            }
            case "launch": {
                String pk = q.get("p");
                ui.post(() -> {
                    AppInfo a = find(pk);
                    if (a != null) { hideSaver(); launch(a); }
                });
                return null;
            }
            case "vol": {
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                String d = q.get("d");
                int dir = "up".equals(d) ? android.media.AudioManager.ADJUST_RAISE
                        : "down".equals(d) ? android.media.AudioManager.ADJUST_LOWER
                        : android.media.AudioManager.ADJUST_TOGGLE_MUTE;
                try { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI); } catch (Exception ignored) { }
                return null;
            }
            case "media": {
                String a = q.get("a");
                int key = "next".equals(a) ? KeyEvent.KEYCODE_MEDIA_NEXT
                        : "prev".equals(a) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                try {
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
                } catch (Exception ignored) { }
                return null;
            }
            case "light": {
                String m = q.get("m");
                ui.post(() -> {
                    lightManual = "auto".equals(m) ? null : m;
                    scheduleLights(0);
                });
                return null;
            }
            case "home":
            case "saver": {
                final boolean sv = cmd.equals("saver");
                ui.post(() -> {
                    try {
                        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                    } catch (Exception ignored) { }
                    if (sv) ui.postDelayed(() -> { hideMenu(); showSaver.run(); }, 600);
                });
                return null;
            }
            case "pc": {
                if (!pcMac().isEmpty()) new Thread(() -> { for (int i = 0; i < 3; i++) sendWol(pcMac(), pcIp()); }).start();
                return "{\"ok\":" + !pcMac().isEmpty() + "}";
            }
            case "status": {
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    android.media.session.MediaController c = npCtrl;
                    if (c != null && c.getMetadata() != null) {
                        android.media.MediaMetadata md = c.getMetadata();
                        o.put("title", md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE));
                        o.put("artist", md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST));
                        o.put("playing", stateOf(c) == android.media.session.PlaybackState.STATE_PLAYING);
                        AppInfo a = find(c.getPackageName());
                        if (a != null) o.put("app", a.label);
                    }
                    String fg = homeVisible ? null : fgPkg();
                    AppInfo f = fg != null ? find(fg) : null;
                    o.put("front", f != null ? "Auf dem TV: " + f.label : "Auf dem TV: CleanHome");
                    return o.toString();
                } catch (Exception e) { return "{}"; }
            }
        }
        return "{\"ok\":false}";
    }

    private void showRemoteQr() {
        hideMenu();
        if (qrLayer != null) { root.removeView(qrLayer); qrLayer = null; }
        String url = remoteUrl();
        FrameLayout l = new FrameLayout(this);
        l.setBackgroundColor(0xF2000000);
        l.setClickable(true);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView h = tv(30, WHITE, fBold);
        h.setText("Handy-Steuerung");
        box.addView(h);
        TextView sub = tv(15, GRAY_1, fRegular);
        sub.setText("Mit der Handy-Kamera scannen – das Handy muss im selben WLAN sein");
        sub.setPadding(0, dp(8), 0, dp(26));
        box.addView(sub);
        ImageView qr = new ImageView(this);
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0);
            int w = m.getWidth(), hgt = m.getHeight(), q = 2;
            Bitmap bm = Bitmap.createBitmap(w + q * 2, hgt + q * 2, Bitmap.Config.RGB_565);
            bm.eraseColor(0xFFFFFFFF);
            for (int y = 0; y < hgt; y++) for (int x = 0; x < w; x++) if (m.get(x, y)) bm.setPixel(x + q, y + q, 0xFF000000);
            android.graphics.drawable.BitmapDrawable bd = new android.graphics.drawable.BitmapDrawable(getResources(), bm);
            bd.setFilterBitmap(false);           // scharfe Kanten beim Vergroessern
            qr.setImageDrawable(bd);
        } catch (Exception e) { qr.setBackgroundColor(0xFF333333); }
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setBackground(rounded(0xFFFFFFFF, 0xFFFFFFFF, 14));
        qr.setPadding(dp(14), dp(14), dp(14), dp(14));
        box.addView(qr, new LinearLayout.LayoutParams(dp(300), dp(300)));
        TextView u = tv(14, GRAY_2, fMono);
        u.setText(url);
        u.setPadding(0, dp(20), 0, 0);
        box.addView(u);
        TextView hint = tv(13, GRAY_2, fRegular);
        hint.setText("Tipp: auf dem Handy „Zum Startbildschirm hinzufügen“ – dann ist es wie eine App.   ·   Beliebige Taste schließt");
        hint.setPadding(0, dp(14), 0, 0);
        box.addView(hint);
        l.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        l.setAlpha(0f);
        root.addView(l, new FrameLayout.LayoutParams(-1, -1));
        l.animate().alpha(1f).setDuration(200).start();
        qrLayer = l;
    }

    // ================================================================ Govee (nur Helligkeit, nie Farbe)

    private final GoveeLights govee = new GoveeLights();
    private boolean goveeOn;
    private final List<String> goveeIps = new ArrayList<>();
    private final java.util.Map<String, String> goveeFound = new java.util.LinkedHashMap<>();
    private int goveeNormal = 100, goveeDim = 15;

    private void loadGovee() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        goveeOn = p.getBoolean("govee_on", false);
        goveeNormal = p.getInt("govee_normal", 100);
        goveeDim = p.getInt("govee_dim", 15);
        goveeIps.clear();
        for (String x : p.getString("govee_ips", "").split(",")) if (!x.isEmpty()) goveeIps.add(x);
        govee.setLamps(goveeIps);
    }

    private void saveGovee() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("govee_on", goveeOn)
                .putInt("govee_normal", goveeNormal).putInt("govee_dim", goveeDim)
                .putString("govee_ips", TextUtils.join(",", goveeIps)).apply();
        govee.setLamps(goveeIps);
    }

    /**
     * Govee: CleanHome fasst die Helligkeit NUR an, wenn eine Streaming-App vorne ist.
     * Vorher wird die aktuelle Helligkeit ausgelesen und gemerkt (auch ueber Neustarts),
     * danach kommt genau diese Helligkeit zurueck. Sonst wird nie etwas gesendet.
     */
    private boolean goveeBusy;

    private void applyGovee() {
        if (!goveeOn || !govee.hasLamps() || goveeBusy) return;
        boolean streaming = screenOn && !homeVisible
                && (saver == null || saver.getVisibility() != View.VISIBLE)
                && appKind(fgPkg()) == K_VIDEO;
        final SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean dimmed = p.getBoolean("govee_dimmed", false);
        if (streaming && !dimmed) {
            goveeBusy = true;
            new Thread(() -> {
                int cur = govee.currentBrightness();               // eigene Helligkeit merken
                ui.post(() -> {
                    goveeBusy = false;
                    if (cur > 0) p.edit().putInt("govee_saved", cur).apply();
                    else if (p.getInt("govee_saved", -1) <= 0) return;   // Helligkeit unbekannt -> lieber nicht dimmen
                    p.edit().putBoolean("govee_dimmed", true).apply();
                    govee.forget();
                    govee.brightness(goveeDim);
                });
            }, "goveeSave").start();
        } else if (!streaming && dimmed) {
            int back = p.getInt("govee_saved", -1);
            p.edit().putBoolean("govee_dimmed", false).apply();
            govee.forget();
            if (back > 0) govee.brightness(back);              // genau die gemerkte Helligkeit
        }
    }

    private void openGoveeMenu() {
        List<MItem> items = new ArrayList<>();
        items.add(new MItem((goveeOn ? "●  " : "○  ") + "An   — bei Streaming-Apps dimmen", () -> {
            if (goveeIps.isEmpty()) { toast("Erst Govee-Lichter suchen und auswählen"); ui.postDelayed(this::openGoveeMenu, 300); return; }
            goveeOn = true; saveGovee(); govee.forget(); scheduleLights(0);
            toast("Govee: dimmt bei Streaming");
            ui.postDelayed(this::openGoveeMenu, 200);
        }));
        items.add(new MItem((!goveeOn ? "●  " : "○  ") + "Aus   — CleanHome lässt Govee in Ruhe", () -> {
            if (goveeOn) {                       // falls gerade gedimmt: gemerkte Helligkeit zurueck
                SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
                if (sp.getBoolean("govee_dimmed", false) && sp.getInt("govee_saved", -1) > 0) govee.brightness(sp.getInt("govee_saved", 100));
                sp.edit().putBoolean("govee_dimmed", false).apply();
            }
            goveeOn = false; saveGovee();
            toast("Govee: aus");
            ui.postDelayed(this::openGoveeMenu, 200);
        }));
        int saved = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("govee_saved", -1);
        items.add(new MItem("ⓘ  Deine Helligkeit wird gemerkt" + (saved > 0 ? "  ·  zuletzt " + saved + " %" : ""), () -> ui.postDelayed(this::openGoveeMenu, 150)));
        items.add(new MItem("Helligkeit bei Streaming  ·  " + goveeDim + " %", () -> {
            int[] v = {5, 10, 15, 25, 40};
            int k = 0; for (int x = 0; x < v.length; x++) if (v[x] == goveeDim) k = (x + 1) % v.length;
            goveeDim = v[k]; saveGovee(); govee.forget(); scheduleLights(0);
            ui.postDelayed(this::openGoveeMenu, 150);
        }));
        items.add(new MItem("⌕  Govee-Lichter im WLAN suchen", this::searchGovee));
        List<String> all = new ArrayList<>(goveeIps);
        for (String f : goveeFound.keySet()) if (!all.contains(f)) all.add(f);
        for (String ip : all) {
            boolean on = goveeIps.contains(ip);
            String model = goveeFound.containsKey(ip) ? goveeFound.get(ip) + "  " : "";
            items.add(new MItem((on ? "☑  " : "☐  ") + model + ip, () -> {
                if (goveeIps.contains(ip)) goveeIps.remove(ip); else goveeIps.add(ip);
                saveGovee();
                ui.postDelayed(this::openGoveeMenu, 200);
            }));
        }
        showMenu("Govee · " + (goveeOn ? "an" : "aus") + " · " + goveeIps.size() + " Gerät(e)", items);
    }

    private void searchGovee() {
        toast("Suche Govee-Lichter …");
        GoveeLights.discover(this, found -> ui.post(() -> {
            goveeFound.clear();
            goveeFound.putAll(found);
            if (found.isEmpty()) toast("Nichts gefunden – in der Govee-App pro Licht „LAN-Steuerung“ einschalten");
            else {
                toast(found.size() + " Govee-Licht(er) gefunden");
                if (goveeIps.isEmpty()) {
                    goveeIps.addAll(found.keySet());
                    goveeOn = true;
                    saveGovee();
                    scheduleLights(2500);
                }
            }
            openGoveeMenu();
        }));
    }

    private void openWizMenu() {
        List<MItem> items = new ArrayList<>();
        items.add(new MItem((wizOn ? "●  " : "○  ") + "Automatisch   — Licht passt sich selbst an", () -> {
            if (wizIps.isEmpty()) { toast("Erst Lampen suchen und auswählen"); ui.postDelayed(this::openWizMenu, 300); return; }
            wizOn = true; saveWiz(); scheduleLights(0);
            toast("WiZ-Licht: automatisch");
            ui.postDelayed(this::openWizMenu, 200);
        }));
        items.add(new MItem((!wizOn ? "●  " : "○  ") + "Aus   — CleanHome steuert das Licht nicht", () -> {
            wizOn = false; saveWiz();
            wiz.white(2700, 60);          // einmal auf normales Licht zurueck
            toast("WiZ-Licht: aus");
            ui.postDelayed(this::openWizMenu, 200);
        }));
        items.add(new MItem("ⓘ  Was passiert automatisch?", this::openWizInfo));
        items.add(new MItem("⌕  Lampen im WLAN suchen", this::searchWiz));
        List<String> all = new ArrayList<>(wizIps);
        for (String f : wizFound) if (!all.contains(f)) all.add(f);
        for (String ip : all) {
            boolean on = wizIps.contains(ip);
            items.add(new MItem((on ? "☑  " : "☐  ") + "Lampe " + ip, () -> {
                if (wizIps.contains(ip)) wizIps.remove(ip); else { wizIps.add(ip); wiz.blink(ip); }
                saveWiz();
                scheduleLights(1500);
                ui.postDelayed(this::openWizMenu, 200);
            }));
        }
        showMenu("WiZ-Licht · " + (wizOn ? "automatisch" : "aus") + " · " + wizIps.size() + " Lampe(n)", items);
    }

    private void openWizInfo() {
        List<MItem> items = new ArrayList<>();
        String[] rules = {
                "Home Screen  → Farbe der markierten App",
                "Musik läuft  → Farbe des Covers",
                "App gestartet  → Sync Box: Cinematic (reagiert aufs Bild)",
                "Modus/Helligkeit in der WiZ-App geändert  → wird gemerkt",
                "PC-Gaming / Moonlight  → lila",
                "Ruhebildschirm  → Farben der Bildschirm-Ecken",
                "Fernseher aus  → normales Licht, 50 %",
                "23–6 Uhr  → höchstens 35 %"
        };
        for (String r : rules) items.add(new MItem(r, () -> ui.postDelayed(this::openWizMenu, 150)));
        showMenu("Automatik-Regeln", items);
    }

    private void searchWiz() {
        toast("Suche WiZ-Lampen …");
        wiz.discover(localIp(), found -> ui.post(() -> {
            wizFound.clear();
            wizFound.addAll(found);
            if (found.isEmpty()) toast("Keine Lampe gefunden – in der WiZ-App „Lokale Kommunikation“ an?");
            else {
                toast(found.size() + " Lampe(n) gefunden – sie blinken kurz");
                if (wizIps.isEmpty()) {           // beim ersten Mal: alle uebernehmen und Automatik an
                    wizIps.addAll(found);
                    wizOn = true;
                    saveWiz();
                    for (String ip : found) wiz.blink(ip);
                    scheduleLights(2000);
                }
            }
            openWizMenu();
        }));
    }

    // ================================================================ Jetzt laeuft (Now Playing)

    private LinearLayout npSection;
    private ImageView npArt;
    private TextView npApp, npTitle, npArtist, npTime, npPlay;
    private View npBar;
    private GradientDrawable npBg;
    private int npColor = BLUE;
    private String npKey;
    private android.media.session.MediaController npCtrl;
    private android.media.session.MediaSessionManager msm;
    private final List<android.media.session.MediaController> watched = new ArrayList<>();

    private final android.media.session.MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            this::onSessions;

    private final android.media.session.MediaController.Callback mediaCb =
            new android.media.session.MediaController.Callback() {
                @Override public void onPlaybackStateChanged(android.media.session.PlaybackState s) { pickController(); }
                @Override public void onMetadataChanged(android.media.MediaMetadata m) { pickController(); }
                @Override public void onSessionDestroyed() { pickController(); }
            };

    private final Runnable npTick = new Runnable() {
        @Override public void run() {
            updateProgress();
            if (npSection.getVisibility() == View.VISIBLE) ui.postDelayed(this, 1000);
        }
    };

    private View buildNowPlaying() {
        npSection = new LinearLayout(this);
        npSection.setOrientation(LinearLayout.VERTICAL);
        npSection.setVisibility(View.GONE);
        npSection.setClipChildren(false);
        npSection.addView(sectionHeader("JETZT LÄUFT", null, 26));

        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(14), dp(18), dp(14));
        npBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x400A72EF, SURFACE, SURFACE});
        npBg.setCornerRadius(dp(14));
        npBg.setStroke(dp(1), BORDER);
        card.setBackground(npBg);
        npSection.addView(card, new LinearLayout.LayoutParams(-1, -2));

        npArt = new ImageView(this);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setBackground(rounded(0xFF161616, 0, 10));
        npArt.setClipToOutline(true);
        card.addView(npArt, new LinearLayout.LayoutParams(dp(84), dp(84)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams li = new LinearLayout.LayoutParams(0, -2, 1f);
        li.leftMargin = dp(18);
        li.rightMargin = dp(18);
        card.addView(info, li);

        LinearLayout appRow = new LinearLayout(this);
        appRow.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(appRow);
        npApp = tv(10, GRAY_2, fMono);
        npApp.setLetterSpacing(0.12f);
        appRow.addView(npApp);
        LinearLayout eq = new LinearLayout(this);
        eq.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams eql = new LinearLayout.LayoutParams(-2, dp(12));
        eql.leftMargin = dp(10);
        appRow.addView(eq, eql);
        for (int i = 0; i < 3; i++) {
            View bar = new View(this);
            bar.setBackgroundColor(GREEN);
            bar.setPivotY(dp(12));
            bar.setScaleY(0.3f);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(3), dp(12));
            if (i > 0) bl.leftMargin = dp(2);
            eq.addView(bar, bl);
            eqBars[i] = bar;
        }
        npTitle = tv(22, WHITE, fSemi);
        npTitle.setLetterSpacing(-0.02f);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        npTitle.setPadding(0, dp(5), 0, dp(2));
        info.addView(npTitle);
        npArtist = tv(14, GRAY_1, fRegular);
        npArtist.setSingleLine(true);
        npArtist.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(npArtist);

        LinearLayout prog = new LinearLayout(this);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        prog.setPadding(0, dp(10), 0, 0);
        info.addView(prog, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout track = new FrameLayout(this);
        track.setBackground(rounded(0xFF262626, 0, 2));
        track.setClipToOutline(true);
        prog.addView(track, new LinearLayout.LayoutParams(0, dp(3), 1f));
        npBar = new View(this);
        npBar.setBackgroundColor(WHITE);
        npBar.setPivotX(0);
        npBar.setScaleX(0f);
        track.addView(npBar, new FrameLayout.LayoutParams(-1, -1));
        npTime = tv(11, GRAY_1, fMono);
        prog.addView(npTime, lpLeft(12));

        LinearLayout ctr = new LinearLayout(this);
        ctr.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(ctr);
        ctr.addView(mediaBtn("◀◀", () -> { if (npCtrl != null) npCtrl.getTransportControls().skipToPrevious(); }));
        npPlay = mediaBtn("❚❚", this::togglePlay);
        ctr.addView(npPlay, lpLeft(10));
        ctr.addView(mediaBtn("▶▶", () -> { if (npCtrl != null) npCtrl.getTransportControls().skipToNext(); }), lpLeft(10));
        return npSection;
    }

    private final View[] eqBars = new View[3];
    private final android.animation.ObjectAnimator[] eqAnims = new android.animation.ObjectAnimator[3];

    /** E3: kleine Equalizer-Balken, solange Musik laeuft. */
    /** Equalizer nur animieren, solange er wirklich zu sehen ist (sonst zeichnet der Stick jedes Bild umsonst neu). */
    private final android.graphics.Rect eqRect = new android.graphics.Rect();
    private void eqVisibilityCheck() {
        if (eqBars[0] == null) return;
        boolean vis = isFront && eqBars[0].isShown() && eqBars[0].getGlobalVisibleRect(eqRect)
                && (saver == null || saver.getVisibility() != View.VISIBLE) && ambientLayer == null;
        for (android.animation.ObjectAnimator a : eqAnims) {
            if (a == null || !a.isStarted()) continue;
            if (!vis && !a.isPaused()) a.pause();
            else if (vis && a.isPaused()) a.resume();
        }
    }

    private void setEq(boolean on) {
        long[] dur = {420, 560, 480};
        for (int i = 0; i < 3; i++) {
            if (eqBars[i] == null) continue;
            if (on) {
                if (eqAnims[i] != null && eqAnims[i].isRunning()) continue;
                eqAnims[i] = android.animation.ObjectAnimator.ofFloat(eqBars[i], "scaleY", 0.25f, 1f);
                eqAnims[i].setDuration(dur[i]);
                eqAnims[i].setRepeatCount(android.animation.ValueAnimator.INFINITE);
                eqAnims[i].setRepeatMode(android.animation.ValueAnimator.REVERSE);
                eqAnims[i].setStartDelay(i * 90L);
                eqAnims[i].start();
            } else {
                if (eqAnims[i] != null) eqAnims[i].cancel();
                eqBars[i].setScaleY(0.3f);
            }
        }
    }

    private TextView mediaBtn(String s, Runnable action) {
        TextView t = tv(14, WHITE, fMono);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable d = rounded(0xFF161616, BORDER, 24);
        t.setBackground(d);
        t.setMinWidth(dp(54));
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        t.setOnFocusChangeListener((v, has) -> {
            d.setColor(has ? 0xFFFFFFFF : 0xFF161616);
            t.setTextColor(has ? BLACK : WHITE);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
            if (has) {
                setHero(String.valueOf(npTitle.getText()), npCtrl != null ? npCtrl.getPackageName() : "", npColor);
                heroHint.setText("  Optionen");
            }
        });
        t.setOnClickListener(v -> action.run());
        t.setOnKeyListener((v, k, e) -> {
            if (k != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN && !backToTile()) focusFirst();
            return true;
        });
        return t;
    }

    private void togglePlay() {
        if (npCtrl == null) return;
        android.media.session.PlaybackState st = npCtrl.getPlaybackState();
        if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING)
            npCtrl.getTransportControls().pause();
        else npCtrl.getTransportControls().play();
    }

    private void initMedia() {
        try {
            msm = (android.media.session.MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            android.content.ComponentName cn = new android.content.ComponentName(this, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn, ui);
            onSessions(msm.getActiveSessions(cn));
        } catch (Exception e) {
            msm = null;   // Berechtigung fehlt -> Karte bleibt einfach aus
            updateNowPlaying(null);
        }
    }

    private void releaseMedia() {
        ui.removeCallbacks(npTick);
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) { }
        for (android.media.session.MediaController c : watched) {
            try { c.unregisterCallback(mediaCb); } catch (Exception ignored) { }
        }
        watched.clear();
    }

    private void onSessions(List<android.media.session.MediaController> list) {
        for (android.media.session.MediaController c : watched) {
            try { c.unregisterCallback(mediaCb); } catch (Exception ignored) { }
        }
        watched.clear();
        if (list != null) for (android.media.session.MediaController c : list) {
            if (getPackageName().equals(c.getPackageName())) continue;
            c.registerCallback(mediaCb, ui);
            watched.add(c);
        }
        pickController();
    }

    private void pickController() {
        android.media.session.MediaController best = null;
        for (android.media.session.MediaController c : watched) {
            android.media.session.PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING && c.getMetadata() != null) {
                best = c;
                break;
            }
        }
        if (best == null) for (android.media.session.MediaController c : watched) {
            android.media.session.PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PAUSED && c.getMetadata() != null) {
                best = c;
                break;
            }
        }
        updateNowPlaying(best);
    }

    private void updateNowPlaying(android.media.session.MediaController c) {
        npCtrl = c;
        android.media.MediaMetadata md = c != null ? c.getMetadata() : null;
        if (md == null) {
            boolean hadFocus = npSection.findFocus() != null;
            npSection.setVisibility(View.GONE);
            setEq(false);
            ui.removeCallbacks(npTick);
            npKey = null;
            if (hadFocus) focusFirst();
            scheduleLights(150);
            return;
        }
        String title = firstNonEmpty(md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE), "Unbekannt");
        String artist = firstNonEmpty(md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST), "");
        if (stateOf(c) == android.media.session.PlaybackState.STATE_PLAYING) recordHistory(c.getPackageName(), title);
        AppInfo app = find(c.getPackageName());
        npApp.setText(((app != null ? app.label : c.getPackageName()) + "  ·  ♪").toUpperCase(Locale.GERMANY));
        npTitle.setText(title);
        npArtist.setText(artist);

        String key = c.getPackageName() + "|" + title + "|" + artist;
        if (!key.equals(npKey)) {
            npKey = key;
            Bitmap art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            Drawable d = art != null ? new android.graphics.drawable.BitmapDrawable(getResources(), art)
                    : (app != null ? (app.banner != null ? app.banner : app.icon) : null);
            npArt.setImageDrawable(d);
            npColor = dominantColor(d);
            npBg.setColors(new int[]{withAlpha(npColor, 0x55), withAlpha(npColor, 0x14), SURFACE});
            wizOnCover(npColor);
            if (Build.VERSION.SDK_INT >= 28) {
                npArt.setOutlineSpotShadowColor(npColor);
            }
        }
        android.media.session.PlaybackState st = c.getPlaybackState();
        boolean playing = st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING;
        npPlay.setText(playing ? "❚❚" : "▶");
        setEq(playing);

        if (npSection.getVisibility() != View.VISIBLE) {
            npSection.setAlpha(0f);
            npSection.setVisibility(View.VISIBLE);
            npSection.animate().alpha(1f).setDuration(300).start();
        }
        ui.removeCallbacks(npTick);
        ui.post(npTick);
        scheduleLights(150);
    }

    private void updateProgress() {
        if (npCtrl == null) return;
        android.media.session.PlaybackState st = npCtrl.getPlaybackState();
        android.media.MediaMetadata md = npCtrl.getMetadata();
        if (st == null || md == null) return;
        long dur = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        long pos = st.getPosition();
        if (st.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        if (dur > 0) {
            pos = Math.max(0, Math.min(pos, dur));
            npBar.setScaleX(pos / (float) dur);
            npTime.setText(fmtTime(pos) + " / " + fmtTime(dur));
        } else {
            npBar.setScaleX(0f);
            npTime.setText(pos > 0 ? fmtTime(pos) : "LIVE");
        }
    }

    private static String fmtTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.trim().isEmpty()) return s;
        return "";
    }

    // ================================================================ Startanimation (zcrowd)

    private FrameLayout splash;
    private View splashBox, splashHalo;

    private void showSplash() {
        splash = new FrameLayout(this);
        splash.setBackgroundColor(BLACK);
        splash.setClickable(true);
        splash.addView(new GridBackground(this), new FrameLayout.LayoutParams(-1, -1));

        GradientDrawable halo = new GradientDrawable();
        halo.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        halo.setGradientRadius(dp(300));
        halo.setColors(new int[]{0x330A72EF, 0x0D0A72EF, 0x00000000});
        View hv = new View(this);
        hv.setBackground(halo);
        hv.setAlpha(0f);
        splash.addView(hv, new FrameLayout.LayoutParams(dp(600), dp(600), Gravity.CENTER));

        GradientDrawable fade = new GradientDrawable();
        fade.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        fade.setGradientRadius(dp(480));
        fade.setColors(new int[]{0x99000000, 0xE6000000, 0xFF000000});
        View fv = new View(this);
        fv.setBackground(fade);
        splash.addView(fv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        splash.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        splashBox = box;
        splashHalo = hv;

        TextView made = tv(12, GRAY_2, fMono);
        made.setText("made by");
        made.setLetterSpacing(0.3f);
        made.setAlpha(0f);
        box.addView(made);

        LinearLayout word = new LinearLayout(this);
        word.setPadding(dp(8), dp(10), dp(8), dp(14));
        // WRAP_CONTENT: sonst wird das Wort auf die Breite der Linie (220dp) gekuerzt -> "zcrow"
        box.addView(word, new LinearLayout.LayoutParams(-2, -2));
        String w = "zcrowd";
        List<TextView> letters = new ArrayList<>();
        for (int i = 0; i < w.length(); i++) {
            TextView l = tv(84, WHITE, fBold);
            l.setText(String.valueOf(w.charAt(i)));
            l.setLetterSpacing(-0.02f);
            l.setAlpha(0f);
            l.setTranslationY(dp(28));
            l.addOnLayoutChangeListener((v, a1, b1, c1, d1, e1, f1, g1, h1) ->
                    ((TextView) v).getPaint().setShader(new LinearGradient(0, 0, 0, v.getHeight(),
                            0xFFFFFFFF, 0xFF7A7A7A, Shader.TileMode.CLAMP)));
            word.addView(l);
            letters.add(l);
        }

        View line = new View(this);
        line.setBackgroundColor(WHITE);
        line.setScaleX(0f);
        box.addView(line, new LinearLayout.LayoutParams(dp(220), dp(1)));

        TextView sub = tv(11, GRAY_1, fMono);
        sub.setText("home · fire tv");
        sub.setLetterSpacing(0.2f);
        sub.setAlpha(0f);
        sub.setPadding(0, dp(14), 0, 0);
        box.addView(sub);

        root.addView(splash, new FrameLayout.LayoutParams(-1, -1));
        scroll.setScaleX(1.04f);
        scroll.setScaleY(1.04f);
        scroll.setAlpha(0f);

        // Der Stick braucht beim Kaltstart ~1,5 s bis zum ersten Bild. Deshalb laeuft die
        // Animation erst los, wenn wirklich etwas auf dem Bildschirm ist.
        final View[] parts = {hv, made, line, sub};
        final List<TextView> ls = letters;
        splash.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (splash != null) splash.getViewTreeObserver().removeOnPreDrawListener(this);
                ui.postDelayed(() -> playSplash(parts, ls), 250);
                return true;
            }
        });
        ui.postDelayed(() -> endSplash(false), 7000);   // Sicherheitsnetz, falls nie gezeichnet wird
    }

    private void playSplash(View[] parts, List<TextView> letters) {
        if (splash == null) return;
        View hv = parts[0], made = parts[1], line = parts[2], sub = parts[3];
        DecelerateInterpolator dec = new DecelerateInterpolator(2f);
        hv.animate().alpha(1f).setDuration(900).start();
        made.animate().alpha(1f).setStartDelay(100).setDuration(400).start();
        for (int i = 0; i < letters.size(); i++) {
            letters.get(i).animate().alpha(1f).translationY(0).setStartDelay(200 + i * 70L)
                    .setDuration(450).setInterpolator(dec).start();
        }
        line.animate().scaleX(1f).setStartDelay(650).setDuration(550).setInterpolator(dec).start();
        sub.animate().alpha(1f).setStartDelay(900).setDuration(400).start();
        ui.postDelayed(() -> endSplash(false), 2000);
    }

    /**
     * Sauberer Uebergang in zwei Schritten (kein gleichzeitiges Ineinanderblenden mehr):
     *  1. Logo blendet nach oben weg, Hintergrund bleibt schwarz
     *  2. erst danach erscheint der Home Screen
     */
    private void endSplash(boolean fast) {
        if (splash == null) return;
        final FrameLayout s = splash;
        splash = null;
        int out = fast ? 120 : 380;
        if (splashBox != null) {
            splashBox.animate().cancel();
            splashBox.animate().alpha(0f).translationY(-dp(16)).setDuration(out)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator()).start();
        }
        if (splashHalo != null) splashHalo.animate().alpha(0f).setDuration(out).start();
        ui.postDelayed(() -> {
            scroll.setAlpha(0f);
            scroll.setScaleX(1.03f);
            scroll.setScaleY(1.03f);
            scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(fast ? 200 : 500)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            s.animate().alpha(0f).setDuration(fast ? 150 : 350)
                    .withEndAction(() -> root.removeView(s)).start();
            if (pendingIntro) introRows();
        }, out);
    }

    // ================================================================ Bildschirmschoner (Punkt 1)

    private final Runnable showSaver = () -> {
        if (menuLayer.getVisibility() == View.VISIBLE || movingPkg != null || ambientLayer != null) { resetIdle(); return; }
        saver.setAlpha(0f);
        saver.setVisibility(View.VISIBLE);
        sWeather.setText(wLine);
        updateSaverInfo();
        saverClock.external = true;
        saverDust.external = true;
        saverDust.setAlpha(1f);
        saverDeep = false;
        saverShownAt = android.os.SystemClock.uptimeMillis();
        saverClock.start();
        saverDust.start();
        ui.removeCallbacks(this.saverFrame);
        ui.post(this.saverFrame);
        // Aufwachen: Inhalt gleitet sanft hoch und wird schaerfer
        saverContent.setAlpha(0f);
        saverContent.setScaleX(0.96f); saverContent.setScaleY(0.96f);
        saverContent.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(250).setDuration(1400)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        saver.animate().alpha(1f).setDuration(900).withEndAction(() -> { hideUnderSaver(); eqVisibilityCheck(); }).start();
        wizSaver(true);
        saverContent.setTranslationX(0);
        saverContent.setTranslationY(0);
        ui.removeCallbacks(this.driftTick);
        ui.postDelayed(this.driftTick, SAVER_DRIFT_MS);
        ui.removeCallbacks(this.cornerTick);
        ui.post(this.cornerTick);
    };

    // Der Ruhebildschirm deckt alles ab - der Home Screen darunter muss dann nicht jedes Frame mitgezeichnet werden.
    private final List<View> hiddenUnderSaver = new ArrayList<>();

    private void hideUnderSaver() {
        if (saver == null || saver.getVisibility() != View.VISIBLE || saver.getAlpha() < 1f) return;
        int idx = root.indexOfChild(saver);
        for (int i = 0; i < idx; i++) {
            View v = root.getChildAt(i);
            if (v.getVisibility() == View.VISIBLE) { v.setVisibility(View.INVISIBLE); hiddenUnderSaver.add(v); }
        }
    }

    private void showUnderSaver() {
        for (View v : hiddenUnderSaver) v.setVisibility(View.VISIBLE);
        hiddenUnderSaver.clear();
    }

    private final Runnable driftTick = new Runnable() {
        @Override public void run() {
            if (saver.getVisibility() != View.VISIBLE) return;
            int cw = saverContent.getWidth() - saverContent.getPaddingLeft() - saverContent.getPaddingRight();
            int ch = saverContent.getHeight() - saverContent.getPaddingTop() - saverContent.getPaddingBottom();
            int maxX = Math.max(0, (saver.getWidth() - cw) / 2 - dp(70));
            int maxY = Math.max(0, (saver.getHeight() - ch) / 2 - dp(40));
            float x = maxX == 0 ? 0 : rnd.nextInt(maxX * 2) - maxX;
            float y = maxY == 0 ? 0 : rnd.nextInt(maxY * 2) - maxY;
            driftFrom[0] = saverContent.getTranslationX(); driftFrom[1] = saverContent.getTranslationY();
            driftTo[0] = x; driftTo[1] = y;
            driftStart = android.os.SystemClock.uptimeMillis();
            ui.postDelayed(this, SAVER_DRIFT_MS);
        }
    };

    private void resetIdle() {
        ui.removeCallbacks(showSaver);
        ui.postDelayed(showSaver, SAVER_DELAY_MS);
    }

    private void hideSaver() {
        saverReturnPkg = null;
        showUnderSaver();
        ui.post(this::eqVisibilityCheck);
        if (saver == null || saver.getVisibility() != View.VISIBLE) return;
        ui.removeCallbacks(driftTick);
        ui.removeCallbacks(cornerTick);
        if (saverClock != null) saverClock.stop();
        if (saverDust != null) saverDust.stop();
        ui.removeCallbacks(saverFrame);
        saver.animate().alpha(0f).setDuration(250).withEndAction(() -> saver.setVisibility(View.GONE)).start();
        wizSaver(false);
        resetIdle();
    }

    // ---- Ecken-Farben des Ruhebildschirms (+ WiZ-Lampen folgen den Ecken)
    private static final int[] PALETTE = {0xFF0A72EF, 0xFF7928CA, 0xFFFF0080, 0xFFF5A623, 0xFF50E3C2, 0xFF00B3FF};
    private static final int[] CORNER_OFS = {0, 2, 4, 1};     // jede Ecke startet mit einer anderen Farbe
    private static final long CORNER_STEP_MS = 10000;          // alle 10 s naechste Farbe
    private final View[][] cornerViews = new View[4][2];
    private final FrameLayout[] cornerHolders = new FrameLayout[4];
    private final int[] cornerFront = new int[4];
    private final int[] cornerCols = new int[4];
    private int cornerStep;

    private GradientDrawable cornerDrawable(int color, int size) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(size / 2f);
        int rgb = color & 0x00FFFFFF;
        g.setColors(new int[]{0x73000000 | rgb, 0x30000000 | rgb, 0x00000000 | rgb});   // = 0x99/0x40 x 0.75
        return g;
    }

    private volatile int goveeBase;      // aktuelle Farbe der Govee-LEDs (0 = unbekannt / keine Govee)

    /** Govee-Farbe im Hintergrund abfragen; aendert sie sich, werden die Ecken sofort angepasst. */
    private void pollGoveeColor() {
        if (goveeIps.isEmpty()) { goveeBase = 0; return; }
        new Thread(() -> {
            int c = govee.currentColor();
            if (c == 0) return;
            int old = goveeBase;
            goveeBase = c;
            if (colorDist(old, c) > 40) ui.post(() -> {
                if (saver != null && saver.getVisibility() == View.VISIBLE) { ui.removeCallbacks(cornerTick); ui.post(cornerTick); }
            });
        }, "goveeColor").start();
    }

    private static int colorDist(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b));
    }

    /**
     * Ecken-Farben, die immer zusammenpassen: alle vier Ecken kommen aus EINEM Grundton
     * (benachbarte Farbtoene, gleiche Saettigung und Helligkeit). Grundton = aktuelle Govee-Farbe,
     * sonst wandert er langsam durch eine Reihe harmonischer Toene (Blau -> Violett -> Magenta -> ... ).
     */
    private static final float[] MOOD_HUES = {214f, 232f, 258f, 284f, 312f, 338f, 196f, 178f};   // abgestimmte Grundtoene
    private static final float[] CORNER_SHIFT = {0f, 18f, -14f, 30f};                             // kleine Abstaende = harmonisch

    private int cornerColor(int c) {
        int base = goveeBase;
        float hue, sat, val;
        if (base != 0) {
            float[] hsv = new float[3];
            Color.colorToHSV(base, hsv);
            hue = hsv[0];
            sat = hsv[1] < 0.15f ? hsv[1] : 0.78f;          // Weiss bleibt weiss, Farben einheitlich kraeftig
            val = 0.95f;
        } else {
            // alle 6 Schritte (~1 Minute) ein Grundton weiter, weich dazwischen
            float pos = cornerStep / 6f;
            int i0 = ((int) Math.floor(pos)) % MOOD_HUES.length, i1 = (i0 + 1) % MOOD_HUES.length;
            float f = pos - (float) Math.floor(pos);
            float h0 = MOOD_HUES[i0], h1 = MOOD_HUES[i1];
            if (Math.abs(h1 - h0) > 180f) { if (h1 > h0) h0 += 360f; else h1 += 360f; }
            hue = (h0 + (h1 - h0) * f) % 360f;
            sat = 0.78f;
            val = 0.95f;
        }
        float wobble = (float) Math.sin((cornerStep + c * 1.7) * 0.9) * 5f;
        float h = (hue + CORNER_SHIFT[c] + wobble + 360f) % 360f;
        return Color.HSVToColor(new float[]{h, sat, val});
    }

    private void applySaverAccent(int c) {
        if (saverClock != null) saverClock.setAccent(c);
        if (saverDust != null) saverDust.setAccent(c);
        if (saverGlow != null) saverGlow.setColors(new int[]{0x38000000 | (c & 0xFFFFFF), 0x00000000});
    }

    /** Begruessung je nach Uhrzeit + was gerade laeuft. */
    private void updateSaverInfo() {
        if (saverHello == null) return;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String g = h < 5 ? "GUTE NACHT" : h < 11 ? "GUTEN MORGEN" : h < 17 ? "GUTEN TAG" : h < 22 ? "GUTEN ABEND" : "GUTE NACHT";
        saverHello.setText(g + "  ·  BECKINGEN");
        if (wLine != null && !wLine.isEmpty()) sWeather.setText(wLine);
        android.media.session.MediaController c = npCtrl;
        String line = null;
        if (c != null && c.getMetadata() != null && stateOf(c) == android.media.session.PlaybackState.STATE_PLAYING) {
            String t = c.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
            String a = c.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            if (t != null) line = "♫  " + t + (a != null && !a.isEmpty() ? "  —  " + a : "");
        }
        saverMusic.setVisibility(line != null ? View.VISIBLE : View.GONE);
        if (line != null) saverMusic.setText(line);
    }

    // ---- Gemeinsamer Takt des Ruhebildschirms: EIN Bild pro Takt fuer Ecken, Inhalt, Lichtpunkte und Uhr
    // (vorher liefen mehrere Animationen unabhaengig mit 60 Bildern/s -> Grafik dauerhaft ausgelastet).
    private static final long SAVER_FRAME_MS = 40;            // 25 Bilder/s reichen fuer langsame Bewegungen
    private static final long SAVER_DEEP_AFTER = 20 * 60 * 1000L;   // nach 20 Min ohne Taste: Stromsparen
    private final long[] fadeStart = new long[4], moveStart = new long[4];
    private final float[][] moveFrom = new float[4][2], moveTo = new float[4][2];
    private final float[] driftFrom = new float[2], driftTo = new float[2];
    private long driftStart, saverShownAt;
    private boolean saverDeep;

    private static float ease(float t) { t = Math.max(0f, Math.min(1f, t)); return t * t * (3f - 2f * t); }

    private final Runnable saverFrame = new Runnable() {
        @Override public void run() {
            if (saver == null || saver.getVisibility() != View.VISIBLE) return;
            long now = android.os.SystemClock.uptimeMillis();
            boolean deep = now - saverShownAt > SAVER_DEEP_AFTER;
            if (deep != saverDeep) {
                saverDeep = deep;
                if (deep && saverDust != null) saverDust.animate().alpha(0f).setDuration(3000).start();   // Lichtpunkte aus
            }
            for (int c = 0; c < 4; c++) {
                if (cornerHolders[c] == null) continue;
                float fa = ease((now - fadeStart[c]) / 6000f);
                cornerViews[c][cornerFront[c]].setAlpha(fa);
                cornerViews[c][1 - cornerFront[c]].setAlpha(1f - fa);
                if (!deep) {
                    float m = ease((now - moveStart[c]) / (float) CORNER_STEP_MS);
                    cornerHolders[c].setTranslationX(moveFrom[c][0] + (moveTo[c][0] - moveFrom[c][0]) * m);
                    cornerHolders[c].setTranslationY(moveFrom[c][1] + (moveTo[c][1] - moveFrom[c][1]) * m);
                }
            }
            float d = ease((now - driftStart) / 4000f);
            saverContent.setTranslationX(driftFrom[0] + (driftTo[0] - driftFrom[0]) * d);
            saverContent.setTranslationY(driftFrom[1] + (driftTo[1] - driftFrom[1]) * d);
            if (!deep && saverDust != null) saverDust.invalidate();
            if (saverClock != null) saverClock.frame();
            // im Stromsparen nur noch 4 Bilder/s (Farbwechsel bleiben weich, Uhr und Sekunden laufen weiter)
            ui.postDelayed(this, deep ? 250 : SAVER_FRAME_MS);
        }
    };

    private final Runnable cornerTick = new Runnable() {
        @Override public void run() {
            if (saver == null || saver.getVisibility() != View.VISIBLE) return;
            cornerStep++;
            pollGoveeColor();
            int size = cornerHolders[0] != null ? cornerHolders[0].getLayoutParams().width : dp(700);
            for (int c = 0; c < 4; c++) {
                int col = cornerColor(c);
                cornerCols[c] = col;
                int back = 1 - cornerFront[c];
                View nb = cornerViews[c][back], of = cornerViews[c][cornerFront[c]];
                nb.setBackground(cornerDrawable(col, size));
                cornerFront[c] = back;
                long now = android.os.SystemClock.uptimeMillis();
                fadeStart[c] = now;
                moveFrom[c][0] = cornerHolders[c].getTranslationX(); moveFrom[c][1] = cornerHolders[c].getTranslationY();
                moveTo[c][0] = (rnd.nextFloat() * 2 - 1) * dp(70); moveTo[c][1] = (rnd.nextFloat() * 2 - 1) * dp(50);
                moveStart[c] = now;
            }
            if (!saverDeep || cornerStep % 6 == 0) sendSaverLights();     // Stromsparen: Licht nur jede Minute
            applySaverAccent(cornerCols[0]);
            updateSaverInfo();
            ui.postDelayed(this, saverDeep ? CORNER_STEP_MS * 3 : CORNER_STEP_MS);
        }
    };

    /** Die Lampen uebernehmen die Ecken-Farben (Lampe 1 = oben links, 2 = oben rechts, ...). */
    private void sendSaverLights() {
        if (lightManual != null || !wizOn || !wiz.hasLamps()) return;
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int dim = (h >= 23 || h < 6) ? 25 : 45;
        lightKey = "saver" + cornerStep;           // beim Verlassen wird sicher neu gesendet
        lightSentAt = android.os.SystemClock.uptimeMillis();
        ui.removeCallbacks(resendLights);
        wiz.fadeEach(cornerCols.clone(), dim);
    }

    private FrameLayout buildSaver() {
        FrameLayout s = new FrameLayout(this);
        s.setClickable(true);
        // Schwarz + Raster + Abdunklung werden EINMAL in ein Bild gerechnet (vorher drei Vollbild-Ebenen)
        s.addView(new SaverBackground(this), new FrameLayout.LayoutParams(-1, -1));

        // Farbflaechen in den vier Ecken, die langsam die Farbe wechseln und leicht wandern
        int[] grav = {Gravity.TOP | Gravity.START, Gravity.TOP | Gravity.END,
                Gravity.BOTTOM | Gravity.END, Gravity.BOTTOM | Gravity.START};
        int size = dp(LITE ? 620 : 760), off = -size * 4 / 10;
        for (int c = 0; c < 4; c++) {
            FrameLayout holder = new FrameLayout(this);
            for (int k = 0; k < 2; k++) {
                View b = new View(this) { @Override public boolean hasOverlappingRendering() { return false; } };
                b.setBackground(cornerDrawable(cornerColor(c), size));
                b.setAlpha(k == 0 ? 1f : 0f);
                holder.addView(b, new FrameLayout.LayoutParams(-1, -1));
                cornerViews[c][k] = b;
            }
            FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(size, size, grav[c]);
            if (c == 0 || c == 3) hl.leftMargin = off; else hl.rightMargin = off;
            if (c < 2) hl.topMargin = off; else hl.bottomMargin = off;
            s.addView(holder, hl);
            cornerHolders[c] = holder;
            cornerCols[c] = cornerColor(c);
        }

        // feine, funkelnde Lichtpunkte ueber dem Farbschein
        saverDust = new SaverParticles(this, LITE);
        s.addView(saverDust, new FrameLayout.LayoutParams(-1, -1));

        // Vignette: Raender etwas dunkler -> die Mitte leuchtet
        GradientDrawable vig = new GradientDrawable();
        vig.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        vig.setGradientRadius(dp(900));
        vig.setColors(new int[]{0x00000000, 0x00000000, 0x99000000});
        View vv = new View(this);
        vv.setBackground(vig);
        s.addView(vv, new FrameLayout.LayoutParams(-1, -1));

        saverContent = new LinearLayout(this);
        saverContent.setOrientation(LinearLayout.VERTICAL);
        saverContent.setGravity(Gravity.CENTER_HORIZONTAL);
        saverContent.setPadding(dp(160), dp(50), dp(160), dp(50));
        // weicher Lichtschein hinter der Uhr in der Akzentfarbe
        saverGlow = new GradientDrawable();
        saverGlow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        saverGlow.setGradientRadius(dp(300));
        saverGlow.setColors(new int[]{0x33000000 | (BLUE & 0xFFFFFF), 0x00000000});
        saverContent.setBackground(saverGlow);
        saverContent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) ->
                saverGlow.setGradientRadius(Math.min(r - l, b - t) * 0.5f));

        saverHello = tv(13, GRAY_2, fMono);
        saverHello.setLetterSpacing(0.32f);
        saverContent.addView(saverHello);

        saverClock = new SaverClock(this, fLight, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 150, getResources().getDisplayMetrics()));
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(-2, -2);
        lc.topMargin = dp(10);
        saverContent.addView(saverClock, lc);

        TextClock day = new TextClock(this);
        day.setFormat24Hour("EEEE, d. MMMM");
        day.setFormat12Hour("EEEE, d. MMMM");
        day.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        day.setTypeface(fRegular);
        day.setTextColor(GRAY_1);
        day.setPadding(dp(10), dp(2), dp(10), 0);
        saverContent.addView(day);

        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setGravity(Gravity.CENTER);
        sWeather = tv(15, WHITE, fMono);
        sWeather.setPadding(dp(16), dp(9), dp(16), dp(9));
        sWeather.setBackground(rounded(0x99111111, 0x33FFFFFF, 22));
        pills.addView(sWeather);
        saverMusic = tv(15, WHITE, fMono);
        saverMusic.setPadding(dp(16), dp(9), dp(16), dp(9));
        saverMusic.setBackground(rounded(0x99111111, 0x33FFFFFF, 22));
        saverMusic.setMaxWidth(dp(420));
        saverMusic.setSingleLine(true);
        saverMusic.setEllipsize(TextUtils.TruncateAt.END);
        saverMusic.setVisibility(View.GONE);
        LinearLayout.LayoutParams lm = new LinearLayout.LayoutParams(-2, -2);
        lm.leftMargin = dp(10);
        pills.addView(saverMusic, lm);
        LinearLayout.LayoutParams lw = new LinearLayout.LayoutParams(-2, -2);
        lw.topMargin = dp(26);
        saverContent.addView(pills, lw);

        TextView by = tv(11, 0xFF5A5A5A, fMono);
        by.setText("made by zcrowd");
        by.setLetterSpacing(0.3f);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-2, -2);
        lb.topMargin = dp(34);
        saverContent.addView(by, lb);

        s.addView(saverContent, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return s;
    }

    // ================================================================ Netzwerk-Status (Punkt 6)

    private final Runnable netTick = new Runnable() {
        @Override public void run() {
            checkNetwork();
            ui.postDelayed(this, 30 * 1000L);
        }
    };

    @SuppressWarnings("deprecation")
    private void checkNetwork() {
        new Thread(() -> {
            String type = "Offline";
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
                if (ni != null && ni.isConnected()) {
                    type = ni.getType() == ConnectivityManager.TYPE_ETHERNET ? "LAN"
                            : ni.getType() == ConnectivityManager.TYPE_WIFI ? "WLAN" : "Netz";
                }
            } catch (Exception ignored) { }
            String ip = localIp();
            long ms = -1;
            if (!"Offline".equals(type)) {
                try (Socket s = new Socket()) {
                    long t0 = System.nanoTime();
                    s.connect(new InetSocketAddress("1.1.1.1", 443), 2500);
                    ms = (System.nanoTime() - t0) / 1_000_000L;
                } catch (Exception ignored) { }
            }
            final String text;
            final int color;
            if ("Offline".equals(type)) { text = "  Offline"; color = RED; }
            else if (ms < 0) { text = "  " + ip + " · " + type + " · kein Internet"; color = AMBER; }
            else {
                text = "  " + ip + " · " + type + " · " + ms + " ms";
                color = ms < 60 ? GREEN : ms < 150 ? AMBER : RED;
            }
            ui.post(() -> { netView.setText(text); netDot.setColor(color); });
        }).start();
    }

    private static String localIp() {
        String fallback = "–";
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String n = ni.getName();
                        if (n.startsWith("wlan") || n.startsWith("eth")) return a.getHostAddress();
                        fallback = a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    // ================================================================ Wetter Beckingen (66701)

    private static final double LAT = 49.40, LON = 6.70;
    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;

    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            if (System.currentTimeMillis() - lastWeather > WEATHER_EVERY_MS - 5000) fetchWeather();
            ui.postDelayed(this, 60 * 1000L);
        }
    };

    private View weatherCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(12), dp(20), dp(12));
        card.setBackground(rounded(0xCC0A0A0A, BORDER, 12));
        card.setMinimumWidth(dp(220));

        TextView loc = tv(10, GRAY_2, fMono);
        loc.setText("BECKINGEN · 66701");
        loc.setLetterSpacing(0.1f);
        card.addView(loc);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(4));
        card.addView(row);
        wIcon = tv(34, WHITE, fMono);
        wIcon.setText("·");
        row.addView(wIcon);
        wTemp = tv(34, WHITE, fSemi);
        wTemp.setLetterSpacing(-0.03f);
        wTemp.setText("--°");
        row.addView(wTemp, lpLeft(10));

        wDesc = tv(13, GRAY_1, fRegular);
        wDesc.setText("Wetter wird geladen …");
        card.addView(wDesc);
        wMinMax = tv(11, GRAY_2, fMono);
        wMinMax.setPadding(0, dp(4), 0, 0);
        card.addView(wMinMax);
        return card;
    }

    private void fetchWeather() {
        lastWeather = System.currentTimeMillis();
        new Thread(() -> {
            java.net.HttpURLConnection con = null;
            try {
                java.net.URL url = new java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=" + LAT
                        + "&longitude=" + LON
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=Europe%2FBerlin");
                con = (java.net.HttpURLConnection) url.openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                java.io.InputStream in = con.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                JSONObject j = new JSONObject(bo.toString("UTF-8"));
                JSONObject cur = j.getJSONObject("current");
                JSONObject day = j.getJSONObject("daily");
                final int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                final int code = cur.getInt("weather_code");
                final boolean isDay = cur.optInt("is_day", 1) == 1;
                final int max = (int) Math.round(day.getJSONArray("temperature_2m_max").getDouble(0));
                final int min = (int) Math.round(day.getJSONArray("temperature_2m_min").getDouble(0));
                ui.post(() -> {
                    String icon = weatherIcon(code, isDay);
                    wTemp.setText(temp + "°");
                    wIcon.setText(icon);
                    wDesc.setText(weatherText(code));
                    wMinMax.setText("H " + max + "°   T " + min + "°");
                    wLine = icon + "  " + temp + "°  " + weatherText(code) + "  ·  Beckingen";
                    if (sWeather != null) sWeather.setText(wLine);
                });
            } catch (Exception e) {
                lastWeather = System.currentTimeMillis() - WEATHER_EVERY_MS + 2 * 60 * 1000L;
                ui.post(() -> { if ("--°".contentEquals(wTemp.getText())) wDesc.setText("Kein Wetter (offline?)"); });
            } finally {
                if (con != null) con.disconnect();
            }
        }).start();
    }

    private static String weatherIcon(int c, boolean day) {
        if (c == 0 || c == 1) return day ? "☀" : "☾";
        if (c == 2 || c == 3) return "☁";
        if (c == 45 || c == 48) return "≋";
        if ((c >= 51 && c <= 67) || (c >= 80 && c <= 82)) return "☂";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "❄";
        if (c >= 95) return "☈";
        return "☁";
    }

    private static String weatherText(int c) {
        if (c == 0) return "Klar";
        if (c == 1) return "Überwiegend klar";
        if (c == 2) return "Teilweise bewölkt";
        if (c == 3) return "Bedeckt";
        if (c == 45 || c == 48) return "Nebel";
        if (c >= 51 && c <= 57) return "Nieselregen";
        if (c >= 61 && c <= 67) return "Regen";
        if (c >= 71 && c <= 77) return "Schnee";
        if (c >= 80 && c <= 82) return "Regenschauer";
        if (c == 85 || c == 86) return "Schneeschauer";
        if (c >= 95) return "Gewitter";
        return "Bewölkt";
    }

    // ================================================================ Aktionen

    private void launch(AppInfo a) {
        if (PC_PKG.equals(a.pkg)) { startPcGaming(); return; }
        if (a.pkg.startsWith(GAME_PREFIX)) { startPcGaming(a.label); return; }
        if (a.pkg.startsWith(RESUME_PREFIX)) {
            AppInfo real = a.src != null ? find(a.src) : null;
            if (real != null) launch(real); else toast("Die App ist nicht mehr installiert");
            return;
        }
        lastLaunchedPkg = a.pkg;
        HomeWatchService.lastForegroundPkg = a.pkg;
        lastLeftByLaunch = System.currentTimeMillis();
        wizLaunch();
        if (autoRam()) freeRam(a.pkg, true);
        try {
            startActivity(a.launch);
        } catch (ActivityNotFoundException | SecurityException e) {
            Intent alt = getPackageManager().getLaunchIntentForPackage(a.pkg);
            if (alt != null) startActivity(alt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            else toast("Kann " + a.label + " nicht öffnen");
        }
    }

    /** Direkte Einstiege in Amazons Einstellungs-App (ausgelesen von deinem Stick). */
    // {Name, Aktion, "1" = von Amazon geschuetzt (signature|privileged|amazon) -> nur ueber Amazon moeglich}
    private static final String[][] FIRE_SETTINGS = {
            {"Netzwerk", "android.settings.WIRELESS_SETTINGS", "0"},
            {"Apps", "com.amazon.device.settings.action.APPLICATIONS", "0"},
            {"Fernbedienungen & Bluetooth", "com.amazon.device.settings.action.CONTROLLERS", "0"},
            {"Mein Fire TV (Info, Neustart, Entwickler)", "com.amazon.device.settings.action.DEVICE", "0"},
            {"Bild & Ton", "", "1"},
            {"Präferenzen (Datenschutz, Werbung, Sprache)", "", "1"},
            {"Bedienungshilfen", "", "1"},
            {"Konto & Profil", "", "1"},
    };
    private static final String FIRE_SETTINGS_PKG = "com.amazon.tv.settings.v2";

    /**
     * Oeffnet eine Fire-TV-Einstellungsseite direkt in Amazons Einstellungs-App.
     * OHNE NEW_TASK: die Seite liegt dann "auf" CleanHome, und Zurueck aus dem obersten
     * Menue landet direkt wieder in CleanHome (nicht bei Amazon).
     */
    private void openFireSettings(String action) {
        lastLaunchedPkg = "system.settings";
        lastLeftByLaunch = System.currentTimeMillis();
        settingsOpenedAt = System.currentTimeMillis();
        Intent i = new Intent(action).setPackage(FIRE_SETTINGS_PKG);
        try {
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e2) {
                toast("Diese Seite lässt Amazon nicht direkt öffnen");
            }
        }
    }

    private void openSystemSettings() {
        toast("Öffnet über Amazon – mit Zurück kommst du wieder hierher");
        lastLaunchedPkg = "system.settings";
        lastLeftByLaunch = System.currentTimeMillis();
        settingsOpenedAt = System.currentTimeMillis();
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Intent alt = getPackageManager().getLaunchIntentForPackage("com.amazon.tv.settings.v2");
            if (alt != null) startActivity(alt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private TextView toastView;
    private final Runnable toastHide = () -> toastView.animate().alpha(0f).translationY(dp(10)).setDuration(220)
            .withEndAction(() -> toastView.setVisibility(View.GONE)).start();

    /** Meldung im CleanHome-Look (dunkle Pille unten) statt Amazon-Toast. */
    // ---------- RAM freimachen ----------
    private static final String[] RAM_KEEP = {
            "android", "com.android.systemui", "com.android.phone", "com.android.providers",
            "com.amazon.tv.launcher", "com.amazon.device.software.ota", "com.amazon.tv.settings",
            "com.amazon.vizzini", "com.amazon.tv.inputpreference", "com.google.android.inputmethod",
            "com.amazon.tv.ime", "com.android.inputmethod"};
    private volatile boolean ramBusy;

    private boolean autoRam() { return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("auto_ram", true); }

    /** Beendet Apps, die nur noch im Hintergrund liegen. Laufende Musik/Video (Vordergrunddienst) bleibt unberuehrt. */
    private void freeRam(String exceptPkg, boolean silent) {
        if (ramBusy) return;
        ramBusy = true;
        final android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        new Thread(() -> {
            long before = availMb(am);
            int n = 0;
            try {
                for (android.content.pm.ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                    String pk = ai.packageName;
                    if (pk.equals(getPackageName()) || pk.equals(exceptPkg)) continue;
                    if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_PERSISTENT) != 0) continue;
                    boolean keep = false;
                    for (String k : RAM_KEEP) if (pk.equals(k) || pk.startsWith(k + ".")) { keep = true; break; }
                    if (keep) continue;
                    try { am.killBackgroundProcesses(pk); n++; } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            if (!silent) {
                try { Thread.sleep(600); } catch (InterruptedException ignored) { }
                long gained = Math.max(0, availMb(am) - before);
                long now = availMb(am);
                ui.post(() -> toast(gained > 0 ? gained + " MB freigemacht  ·  " + now + " MB frei" : "Schon aufgeräumt  ·  " + now + " MB frei"));
            }
            ramBusy = false;
        }, "freeRam").start();
    }

    private static long availMb(android.app.ActivityManager am) {
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    private void toast(String s) {
        ui.removeCallbacks(toastHide);
        toastView.animate().cancel();
        toastView.setText(s);
        toastView.setVisibility(View.VISIBLE);
        toastView.setAlpha(0f);
        toastView.setTranslationY(dp(12));
        toastView.animate().alpha(1f).translationY(0).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
        ui.postDelayed(toastHide, 2400);
    }

    // ================================================================ Effekte

    private TextView settingsBtn;
    private View lastTile;
    private String firstRowId = ALL;
    private boolean pendingIntro;

    /** Zurueck zur zuletzt markierten Kachel (fuer "runter" von Einstellungen / Jetzt laeuft). */
    private boolean backToTile() {
        if (lastTile != null && lastTile.isAttachedToWindow() && lastTile.isShown()) {
            lastTile.requestFocus();
            return true;
        }
        return false;
    }

    // E1: Start-Uebergang - die Kachel waechst auf Vollbild, dann startet die App
    private View launchFx;

    private void launchWithTransition(View tileV, AppInfo a) {
        if (PC_PKG.equals(a.pkg) || a.pkg.startsWith(GAME_PREFIX) || root.getWidth() == 0) { launch(a); return; }
        clearLaunchFx();
        int[] loc = new int[2], rl = new int[2];
        tileV.getLocationInWindow(loc);
        root.getLocationInWindow(rl);
        int w = Math.round(tileV.getWidth() * tileV.getScaleX());
        int h = Math.round(tileV.getHeight() * tileV.getScaleY());
        float x = loc[0] - rl[0], y = loc[1] - rl[1];
        ImageView fx = new ImageView(this);
        fx.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (a.banner != null) fx.setImageDrawable(a.banner);
        fx.setBackground(rounded(a.color, 0, 12));
        fx.setClipToOutline(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = Math.round(x);
        lp.topMargin = Math.round(y);
        root.addView(fx, root.indexOfChild(menuLayer), lp);
        launchFx = fx;
        float s = Math.max(root.getWidth() / (float) w, root.getHeight() / (float) h);
        fx.setPivotX(w / 2f);
        fx.setPivotY(h / 2f);
        fx.animate()
                .translationX(root.getWidth() / 2f - (x + w / 2f))
                .translationY(root.getHeight() / 2f - (y + h / 2f))
                .scaleX(s).scaleY(s)
                .setDuration(LITE ? 220 : 280)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    launch(a);
                    ui.postDelayed(this::clearLaunchFx, 2500);   // Sicherheitsnetz
                })
                .start();
    }

    private void clearLaunchFx() {
        if (launchFx != null) {
            root.removeView(launchFx);
            launchFx = null;
        }
    }

    // E4: Reihen beim ersten Laden nacheinander einblenden
    private void introRows() {
        pendingIntro = false;
        long d = 0;
        for (int i = 0; i < rowsBox.getChildCount(); i++) d = intro(rowsBox.getChildAt(i), d);
        for (int i = 0; i < grid.getChildCount() && i < 10; i++) d = intro(grid.getChildAt(i), d);
    }

    private long intro(View v, long delay) {
        v.setAlpha(0f);
        if (!LITE) v.setTranslationY(dp(12));
        v.animate().alpha(v.isFocused() || LITE ? 1f : v.getAlpha() == 0f && v.getTag() != null && !LITE ? 0.8f : 1f)
                .translationY(0).setStartDelay(delay).setDuration(LITE ? 180 : 320)
                .setInterpolator(new DecelerateInterpolator()).start();
        return delay + (LITE ? 25 : 45);
    }

    // E5: Spotlight hinter der markierten Kachel
    private static final int SPOT = 460;
    private View spot;
    private GradientDrawable spotDrawable;
    private int spotCol = 0;
    private final Runnable spotLater = () -> placeSpot(true);

    private void spotColor(int c) {
        if (c == spotCol) return;
        spotCol = c;
        spotDrawable.setColors(new int[]{withAlpha(c, LITE ? 0x40 : 0x55), withAlpha(c, 0)});
    }

    private void placeSpot(boolean animate) {
        if (spot == null) return;
        View f = getCurrentFocus();
        boolean onTile = f != null && f == lastTile && f.isAttachedToWindow() && f.isShown()
                && menuLayer.getVisibility() != View.VISIBLE;
        if (!onTile) {
            if (spot.getAlpha() > 0f) spot.animate().alpha(0f).setDuration(150).start();
            return;
        }
        int[] loc = new int[2], rl = new int[2];
        f.getLocationInWindow(loc);
        root.getLocationInWindow(rl);
        float cx = loc[0] - rl[0] + f.getWidth() * f.getScaleX() / 2f;
        float cy = loc[1] - rl[1] + f.getHeight() * f.getScaleY() / 2f;
        float tx = cx - dp(SPOT) / 2f, ty = cy - dp(SPOT) / 2f;
        if (animate) {
            spot.animate().x(tx).y(ty).alpha(1f).setStartDelay(0).setDuration(LITE ? 180 : 260)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            spot.animate().cancel();
            spot.setX(tx);
            spot.setY(ty);
        }
    }

    // ================================================================ Statischer Hintergrund (A)

    /** Schwarz + Raster + Abdunklung zu den Raendern, einmal als Bild gerechnet. */
    static class StaticBackground extends View {
        private Bitmap cache;
        StaticBackground(Context c) { super(c); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w <= 0 || h <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(b);
            c.drawColor(0xFF000000);
            Paint p = new Paint();
            p.setColor(0x14FFFFFF);
            p.setStrokeWidth(Math.max(1f, d * 0.5f));
            float step = 48 * d, x0 = (w % step) / 2f;
            for (float x = x0; x <= w; x += step) c.drawLine(x, 0, x, h, p);
            for (float y = 0; y <= h; y += step) c.drawLine(0, y, w, y, p);
            Paint f = new Paint();
            f.setShader(new android.graphics.RadialGradient(w / 2f, 0, 640 * d,
                    new int[]{0x00000000, 0x99000000, 0xFF000000}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, f);
            cache = b;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (cache != null) c.drawBitmap(cache, 0, 0, null);
        }
    }

    // ================================================================ Raster-Hintergrund

    /** Ruhebildschirm-Hintergrund: sieht aus wie Schwarz + Raster + runde Abdunklung, ist aber ein einziges Bild. */
    static class SaverBackground extends View {
        private Bitmap cache;
        SaverBackground(Context c) { super(c); }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w <= 0 || h <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(0xFF000000);
            Paint p = new Paint();
            p.setColor(0x14FFFFFF);
            p.setStrokeWidth(Math.max(1f, d * 0.5f));
            float step = 48 * d, x0 = (w % step) / 2f;
            for (float x = x0; x <= w; x += step) c.drawLine(x, 0, x, h, p);
            for (float y = 0; y <= h; y += step) c.drawLine(0, y, w, y, p);
            Paint f = new Paint();
            f.setShader(new android.graphics.RadialGradient(w / 2f, h / 2f, 520 * d,
                    new int[]{0x66000000, 0xE6000000, 0xFF000000}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, f);
            cache = b;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (cache != null) c.drawBitmap(cache, 0, 0, null);
        }
    }

    static class GridBackground extends View {
        private final Paint p = new Paint();
        private final float step;

        GridBackground(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            step = 48 * d;
            p.setColor(0x14FFFFFF);
            p.setStrokeWidth(Math.max(1f, d * 0.5f));
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float x0 = (w % step) / 2f;
            for (float x = x0; x <= w; x += step) c.drawLine(x, 0, x, h, p);
            for (float y = 0; y <= h; y += step) c.drawLine(0, y, w, y, p);
        }
    }

    // ================================================================ Farb-Helfer

    private static int dominantColor(Drawable src) {
        if (src == null) return BLUE;
        try {
            Drawable d = src.getConstantState() != null
                    ? src.getConstantState().newDrawable().mutate() : src;
            Bitmap bmp = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 32, 18);
            d.draw(new Canvas(bmp));
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
                int px = bmp.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                Color.colorToHSV(px, hsv);
                double weight = hsv[1] * hsv[1] * hsv[2] + 0.001;
                r += Color.red(px) * weight; g += Color.green(px) * weight; b += Color.blue(px) * weight; w += weight;
            }
            bmp.recycle();
            if (w < 0.5) return BLUE;
            int c = Color.rgb((int) (r / w), (int) (g / w), (int) (b / w));
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.max(hsv[1], 0.6f);
            hsv[2] = Math.max(hsv[2], 0.85f);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return BLUE;
        }
    }

    private static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
