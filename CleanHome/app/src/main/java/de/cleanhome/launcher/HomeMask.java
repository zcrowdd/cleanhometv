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

    private HomeMask() { }

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
                FrameLayout v = new FrameLayout(c);
                v.setBackgroundColor(0xFF000000);
                Bitmap b = snapshot;
                if (b != null && !b.isRecycled()) {
                    ImageView iv = new ImageView(c);
                    iv.setScaleType(ImageView.ScaleType.FIT_XY);
                    iv.setImageBitmap(b);
                    v.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                } else {
                    TextView t = new TextView(c);       // noch kein Standbild (z. B. nach dem Einschalten)
                    t.setText("zcrowd");
                    t.setTextColor(0xFFEDEDED);
                    t.setTextSize(48);
                    t.setGravity(Gravity.CENTER);
                    v.addView(t, new FrameLayout.LayoutParams(-1, -1));
                }
                // Tasten waehrend der kurzen Ueberbrueckung abfangen, damit nichts in Amazons Menue landet
                v.setFocusable(true);
                v.setFocusableInTouchMode(true);
                v.setOnKeyListener((vv, code, e) -> true);
                int type = Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.OPAQUE);
                lp.windowAnimations = 0;
                wm.addView(v, lp);
                v.requestFocus();
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
            if (smooth) v.animate().alpha(0f).setDuration(120).withEndAction(remove).start();
            else remove.run();
        });
    }
}
