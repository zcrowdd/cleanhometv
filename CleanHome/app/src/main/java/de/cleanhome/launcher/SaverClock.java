package de.cleanhome.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;

/**
 * Selbst gezeichnete Uhr fuer den Ruhebildschirm.
 * - Breite wird aus "00:00" berechnet -> keine Ziffer wird je abgeschnitten
 * - jede Ziffer, die sich aendert, rollt weich nach oben heraus, die neue kommt von unten
 * - der Doppelpunkt atmet im Sekundentakt
 * - darunter ein duenner Sekunden-Balken in der Akzentfarbe
 */
public class SaverClock extends View {

    private final Paint digit = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private String shown = "";        // aktuell angezeigt
    private String previous = "";     // fuer die Roll-Animation
    private long changedAt;
    private int accent = 0xFF0A72EF;
    private boolean running;
    private float cellW, colonW, textH, ascent;
    private static final long ROLL_MS = 700;

    public SaverClock(Context c, Typeface tf, float sizePx) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        digit.setTypeface(tf);
        digit.setTextSize(sizePx);
        digit.setTextAlign(Paint.Align.CENTER);
        bar.setStrokeCap(Paint.Cap.ROUND);
        bar.setStrokeWidth(3 * density);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(3 * density);
        track.setColor(0x22FFFFFF);
        // feste Zellenbreite = breiteste Ziffer -> Ziffern wackeln nicht, nichts wird abgeschnitten
        float w = 0;
        for (char d = '0'; d <= '9'; d++) w = Math.max(w, digit.measureText(String.valueOf(d)));
        cellW = w * 0.96f;
        colonW = digit.measureText(":") * 1.3f;
        Paint.FontMetrics fm = digit.getFontMetrics();
        ascent = -fm.ascent * 0.86f;
        textH = ascent + fm.descent * 0.2f;
    }

    public void setAccent(int c) { accent = c; updateShader(); invalidate(); }

    private void updateShader() {
        if (getHeight() == 0) return;
        digit.setShader(new LinearGradient(0, 0, 0, textH, 0xFFFFFFFF, blend(0xFF8A8A8A, accent, 0.35f), Shader.TileMode.CLAMP));
    }

    private static int blend(int a, int b, float f) {
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    public void start() { running = true; tick(); }
    public void stop() { running = false; }

    private String now() {
        Calendar c = Calendar.getInstance();
        return String.format(java.util.Locale.GERMANY, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private void tick() {
        if (!running) return;
        String n = now();
        if (!n.equals(shown)) {
            previous = shown.isEmpty() ? n : shown;
            shown = n;
            changedAt = SystemClock.uptimeMillis();
        }
        invalidate();
        // waehrend der Roll-Animation fluessig, sonst nur fuer Doppelpunkt und Sekunden-Balken
        long roll = SystemClock.uptimeMillis() - changedAt;
        postDelayed(this::tick, roll < ROLL_MS ? 16 : 50);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = (int) Math.ceil(cellW * 4 + colonW + 24 * density);
        int h = (int) Math.ceil(textH + 30 * density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) { updateShader(); }

    @Override
    protected void onDraw(Canvas c) {
        if (shown.isEmpty()) shown = previous = now();
        float total = cellW * 4 + colonW;
        float x0 = (getWidth() - total) / 2f;
        float t = Math.min(1f, (SystemClock.uptimeMillis() - changedAt) / (float) ROLL_MS);
        float ease = 1f - (float) Math.pow(1f - t, 3);
        float x = x0;
        for (int i = 0; i < 5; i++) {
            char ch = shown.charAt(i);
            if (i == 2) {
                // Doppelpunkt atmet sanft (1 s Takt)
                float ph = (System.currentTimeMillis() % 1000) / 1000f;
                int a = (int) (110 + 145 * (0.5f + 0.5f * Math.cos(ph * Math.PI * 2)));
                digit.setAlpha(a);
                c.drawText(":", x + colonW / 2f, ascent - textH * 0.04f, digit);
                digit.setAlpha(255);
                x += colonW;
                continue;
            }
            float cx = x + cellW / 2f;
            char old = previous.length() == 5 ? previous.charAt(i) : ch;
            if (old != ch && t < 1f) {
                float shift = textH * 0.55f;
                c.save();
                c.clipRect(x, 0, x + cellW, textH + 4 * density);
                digit.setAlpha((int) (255 * (1 - ease)));
                c.drawText(String.valueOf(old), cx, ascent - shift * ease, digit);
                digit.setAlpha((int) (255 * ease));
                c.drawText(String.valueOf(ch), cx, ascent + shift * (1 - ease), digit);
                digit.setAlpha(255);
                c.restore();
            } else {
                c.drawText(String.valueOf(ch), cx, ascent, digit);
            }
            x += cellW;
        }
        // Sekunden-Balken
        float y = textH + 20 * density;
        float bw = total * 0.42f, bx = (getWidth() - bw) / 2f;
        c.drawLine(bx, y, bx + bw, y, track);
        Calendar cal = Calendar.getInstance();
        float sec = (cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f) / 60f;
        bar.setColor(accent);
        c.drawLine(bx, y, bx + Math.max(2, bw * sec), y, bar);
    }
}
