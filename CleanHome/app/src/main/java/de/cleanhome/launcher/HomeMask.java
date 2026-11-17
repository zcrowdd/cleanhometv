package de.cleanhome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * "Sofort-Home": Android laesst nach dem Home-Druck keine fremde App sofort nach vorne
 * (App-Switch-Sperre). Damit man Amazons Startseite trotzdem nicht sieht, legt CleanHome
 * sofort ein Standbild des eigenen Home Screens als Overlay ueber alles. Sobald CleanHome
 * wirklich vorne ist, wird es unsichtbar ausgeblendet.
 *
 * Voraussetzung (einmalig per ADB, macht install.ps1):
 *   appops set de.cleanhome.launcher SYSTEM_ALERT_WINDOW allow
 */
public final class HomeMask {

    /** Letztes Standbild des Home Screens (wird in MainActivity.onPause gemacht). */
    static volatile Bitmap snapshot;

    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static View mask;
    private static final long MAX_SHOW_MS = 7000;
    private static final Runnable autoHide = () -> hide(null, false);
    private static long shownAt;
    /** Falls CleanHome schon vorne ist (Wettlauf), das Standbild nicht stehen lassen. */
    private static final Runnable frontCheck = new Runnable() {
        @Override public void run() {
            if (mask == null) return;
            if (MainActivity.isFront) { hide(null, true); return; }
            ui.postDelayed(this, 250);
        }
    };

    private static View fill;
    private static android.animation.ValueAnimator progressAnim;

    private HomeMask() { }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static android.graphics.Typeface font(Context c, String f, android.graphics.Typeface fb) {
        try { return android.graphics.Typeface.createFromAsset(c.getAssets(), "fonts/" + f); }
        catch (Exception e) { return fb; }
    }

    /**
     * Ladebildschirm im CleanHome-Look: schwarz, "zcrowd", duenner Ladebalken.
     * Der Balken laeuft in ~5 s (so lange haelt Android hoechstens zurueck) bis 90 %
     * und springt auf 100 %, sobald CleanHome wirklich vorne ist.
     */
    private static FrameLayout buildLoader(Context c) {
        FrameLayout v = new FrameLayout(c);
        v.setBackgroundColor(0xFF000000);

        android.widget.LinearLayout box = new android.widget.LinearLayout(c);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView word = new TextView(c);
        word.setText("zcrowd");
        word.setTextColor(0xFFEDEDED);
        word.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 44);
        word.setTypeface(font(c, "Inter-Bold.otf", android.graphics.Typeface.DEFAULT_BOLD));
        word.setLetterSpacing(-0.03f);
        word.setIncludeFontPadding(false);
        box.addView(word, new android.widget.LinearLayout.LayoutParams(-2, -2));

        FrameLayout track = new FrameLayout(c);
        android.graphics.drawable.GradientDrawable tb = new android.graphics.drawable.GradientDrawable();
        tb.setColor(0xFF262626);
        tb.setCornerRadius(dp(c, 2));
        track.setBackground(tb);
        track.setClipToOutline(true);
        android.widget.LinearLayout.LayoutParams tl = new android.widget.LinearLayout.LayoutParams(dp(c, 220), dp(c, 3));
        tl.topMargin = dp(c, 22);
        box.addView(track, tl);

        View f = new View(c);
        f.setBackgroundColor(0xFFEDEDED);
        f.setPivotX(0);
        f.setScaleX(0.04f);
        track.addView(f, new FrameLayout.LayoutParams(-1, -1));
        fill = f;

        TextView sub = new TextView(c);
        sub.setText("home wird geladen");
        sub.setTextColor(0xFF666666);
        sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTypeface(font(c, "DejaVuSansMono.ttf", android.graphics.Typeface.MONOSPACE));
        sub.setLetterSpacing(0.15f);
        android.widget.LinearLayout.LayoutParams sl = new android.widget.LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = dp(c, 14);
        box.addView(sub, sl);

        v.addView(box, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        if (progressAnim != null) progressAnim.cancel();
        progressAnim = android.animation.ValueAnimator.ofFloat(0.04f, 0.9f);
        progressAnim.setDuration(5000);
        progressAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        progressAnim.addUpdateListener(a -> { if (fill == f) f.setScaleX((float) a.getAnimatedValue()); });
        progressAnim.start();
        return v;
    }

    public static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** Sofort das Standbild zeigen (darf aus jedem Thread aufgerufen werden). */
    public static void show(Context ctx) {
        final Context c = ctx.getApplicationContext();
        ui.post(() -> {
            if (mask != null || !allowed(c)) return;
            try {
                WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                FrameLayout v = buildLoader(c);
                // WICHTIG: das Overlay darf KEINEN Fokus nehmen. Sonst bekommt Amazons Startseite nie den
                // Fokus, Android gibt die Home-Sperre nicht frei und CleanHome kommt gar nicht mehr nach vorne.
                int type = Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.OPAQUE);
                lp.windowAnimations = 0;
                wm.addView(v, lp);
                mask = v;
                ui.removeCallbacks(autoHide);
                ui.postDelayed(autoHide, MAX_SHOW_MS);       // Sicherheitsnetz
                shownAt = android.os.SystemClock.uptimeMillis();
                ui.removeCallbacks(frontCheck);
                ui.postDelayed(frontCheck, 250);
            } catch (Exception ignored) {
                mask = null;
            }
        });
    }

    /** Standbild wegnehmen. smooth = kurz ausblenden (wenn CleanHome schon darunter liegt). */
    public static void hide(Context ctx, boolean smooth) {
        ui.post(() -> {
            final View v = mask;
            if (v == null) return;
            mask = null;
            ui.removeCallbacks(autoHide);
            ui.removeCallbacks(frontCheck);
            Runnable remove = () -> {
                try {
                    WindowManager wm = (WindowManager) v.getContext().getSystemService(Context.WINDOW_SERVICE);
                    wm.removeViewImmediate(v);
                } catch (Exception ignored) { }
            };
            if (progressAnim != null) progressAnim.cancel();
            if (smooth && fill != null) {
                // Balken sauber auf 100 % fahren, dann ausblenden
                fill.animate().scaleX(1f).setDuration(160).withEndAction(() ->
                        v.animate().alpha(0f).setDuration(180).withEndAction(remove).start()).start();
            } else {
                remove.run();
            }
            fill = null;
        });
    }
}
