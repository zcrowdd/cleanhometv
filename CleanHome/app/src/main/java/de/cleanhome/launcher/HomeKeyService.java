package de.cleanhome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS laesst keinen fremden Standard-Launcher zu. Dieser Dienst
 * (Bedienungshilfe) faengt deshalb die Home-Taste ab und ersetzt die
 * Amazon-Startseite sofort durch CleanHome, sobald sie auftaucht.
 */
public class HomeKeyService extends AccessibilityService {

    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private long lastLaunch;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        goHome();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isLongPress()) goHome();
            return true; // Amazon bekommt die Home-Taste nicht mehr
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && AMAZON_LAUNCHER.contentEquals(pkg)) goHome();
    }

    @Override
    public void onInterrupt() { }

    private void goHome() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 700) return;
        lastLaunch = now;
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }
}
