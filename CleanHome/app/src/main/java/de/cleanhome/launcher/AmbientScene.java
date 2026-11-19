package de.cleanhome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * Stimmungs-Szenen fuer den Fernseher, komplett selbst gezeichnet (keine Videos):
 * KAMIN (Flammen, Glut, Funken), REGEN (Tropfen am Fenster, Lichter der Stadt, ab und zu Gewitter),
 * STERNE (funkelnder Himmel, Milchstrasse, Sternschnuppen).
 * Teure Teile (Hintergruende, Leucht-Sprites) werden einmal vorberechnet, pro Bild nur Sprites gezeichnet.
 */
public class AmbientScene extends View {

    public static final int FIRE = 0, RAIN = 1, STARS = 2;

    /** Meldet besondere Momente an die Lichtsteuerung (Blitz, Sternschnuppe). */
    public interface Events { void flash(int kind); }

    private final int type;
    private final boolean lite;
    private final Random rnd = new Random();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();
    private final long frameMs;
    private Events events;
    private boolean running;
    private long last;
    private Bitmap bg;
    private Bitmap[] sprites;            // weiche Leuchtpunkte in verschiedenen Farben
    private float flashA;                // Blitz-Helligkeit (Regen)
    private long nextEvent;

    // Partikel
    private float[] px, py, pv, ps, pl, pp;
    private int[] pc;
    private int n;
    // Tropfen am Fenster (Regen)
    private float[] dx, dy, dv, dr;
    // Sternschnuppe
    private float sx, sy, svx, svy, slife = -1;

    public AmbientScene(Context c, int type, boolean lite) {
        super(c);
        this.type = type;
        this.lite = lite;
        frameMs = lite ? 40 : 28;
        setBackgroundColor(0xFF000000);
    }

    public void setEvents(Events e) { events = e; }
    public void start() { running = true; last = System.currentTimeMillis(); nextEvent = last + 20000 + rnd.nextInt(40000); invalidate(); }
    public void stop() { running = false; }

    @Override public boolean hasOverlappingRendering() { return false; }

    private static Bitmap glow(int size, int color) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
        int rgb = color & 0x00FFFFFF;
        g.setShader(new RadialGradient(size / 2f, size / 2f, size / 2f,
                new int[]{0xFF000000 | rgb, 0x88000000 | rgb, 0x00000000 | rgb}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, g);
        return b;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (w <= 0 || h <= 0) return;
        bg = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bg);
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        float d = getResources().getDisplayMetrics().density;
        if (type == FIRE) {
            q.setShader(new LinearGradient(0, 0, 0, h, 0xFF050302, 0xFF1A0A03, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, q);
            q.setShader(new RadialGradient(w / 2f, h * 0.95f, w * 0.55f, 0x66FF5A00, 0x00FF5A00, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, q);
            // Holzscheite
            q.setShader(null);
            q.setColor(0xFF2A1408);
            float lw = w * 0.32f, lh = 34 * d, cy = h * 0.86f;
            c.save(); c.rotate(-8, w / 2f, cy); c.drawRoundRect(new RectF(w / 2f - lw, cy - lh / 2, w / 2f + lw * 0.2f, cy + lh / 2), lh / 2, lh / 2, q); c.restore();
            q.setColor(0xFF331A0B);
            c.save(); c.rotate(9, w / 2f, cy); c.drawRoundRect(new RectF(w / 2f - lw * 0.2f, cy - lh / 2, w / 2f + lw, cy + lh / 2), lh / 2, lh / 2, q); c.restore();
            q.setShader(new LinearGradient(0, h * 0.9f, 0, h, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.9f, w, h, q);
            sprites = new Bitmap[]{glow(128, 0xFFFFD27A), glow(128, 0xFFFF8A1E), glow(128, 0xFFE8410E), glow(32, 0xFFFFC46B)};
            n = lite ? 46 : 80;
        } else if (type == RAIN) {
            q.setShader(new LinearGradient(0, 0, 0, h, 0xFF050A14, 0xFF0B1424, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, q);
            // verschwommene Lichter der Stadt hinter der Scheibe
            int[] cols = {0x55FFB347, 0x553B8BFF, 0x44FF5E7E, 0x5545DEC4, 0x55FFFFFF};
            for (int i = 0; i < (lite ? 28 : 46); i++) {
                float r = (20 + rnd.nextFloat() * 70) * d;
                float x = rnd.nextFloat() * w, y = h * 0.45f + rnd.nextFloat() * h * 0.5f;
                int col = cols[rnd.nextInt(cols.length)];
                q.setShader(new RadialGradient(x, y, r, col, col & 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawCircle(x, y, r, q);
            }
            q.setShader(new LinearGradient(0, 0, 0, h, 0x33000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, q);
            n = lite ? 90 : 160;                         // Regenstreifen
            int m = lite ? 26 : 44;                      // Tropfen auf der Scheibe
            dx = new float[m]; dy = new float[m]; dv = new float[m]; dr = new float[m];
            for (int i = 0; i < m; i++) resetDrop(i, w, h, true, d);
        } else {
            q.setShader(new LinearGradient(0, 0, 0, h, 0xFF02030A, 0xFF0A0F22, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, q);
            // Milchstrasse: weiches diagonales Band
            c.save();
            c.rotate(-24, w / 2f, h / 2f);
            q.setShader(new LinearGradient(0, h * 0.36f, 0, h * 0.64f, new int[]{0x00000000, 0x22A78BFA, 0x33DDE6FF, 0x223B8BFF, 0x00000000}, null, Shader.TileMode.CLAMP));
            c.drawRect(-w * 0.3f, h * 0.36f, w * 1.3f, h * 0.64f, q);
            c.restore();
            q.setShader(null);
            for (int i = 0; i < (lite ? 260 : 420); i++) {     // feste, schwache Hintergrundsterne
                q.setColor(((20 + rnd.nextInt(90)) << 24) | 0xFFFFFF);
                c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, (0.4f + rnd.nextFloat() * 0.8f) * d, q);
            }
            q.setShader(new LinearGradient(0, h * 0.82f, 0, h, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.82f, w, h, q);
            sprites = new Bitmap[]{glow(32, 0xFFFFFFFF), glow(32, 0xFFBFD4FF), glow(32, 0xFFFFE2B8)};
            n = lite ? 70 : 120;                         // funkelnde helle Sterne
        }
        px = new float[n]; py = new float[n]; pv = new float[n]; ps = new float[n]; pl = new float[n]; pp = new float[n]; pc = new int[n];
        for (int i = 0; i < n; i++) resetParticle(i, w, h, true, d);
    }

    private void resetParticle(int i, int w, int h, boolean initial, float d) {
        if (type == FIRE) {
            boolean spark = rnd.nextInt(7) == 0;
            pc[i] = spark ? 3 : rnd.nextInt(3);
            px[i] = w / 2f + (float) rnd.nextGaussian() * w * (spark ? 0.12f : 0.09f);
            py[i] = h * 0.84f + rnd.nextFloat() * 20 * d;
            pv[i] = (spark ? 120 + rnd.nextFloat() * 160 : 60 + rnd.nextFloat() * 90) * d;     // px/s nach oben
            ps[i] = (spark ? 6 + rnd.nextFloat() * 6 : 60 + rnd.nextFloat() * 90) * d;
            pl[i] = initial ? rnd.nextFloat() : 0f;                                              // Lebenszeit 0..1
            pp[i] = rnd.nextFloat() * 6.28f;
        } else if (type == RAIN) {
            px[i] = rnd.nextFloat() * w * 1.2f;
            py[i] = initial ? rnd.nextFloat() * h : -rnd.nextFloat() * h * 0.3f;
            pv[i] = (900 + rnd.nextFloat() * 700) * d;
            ps[i] = (18 + rnd.nextFloat() * 30) * d;
            pl[i] = 0.08f + rnd.nextFloat() * 0.18f;                                             // Deckkraft
        } else {
            px[i] = rnd.nextFloat() * w;
            py[i] = rnd.nextFloat() * h * 0.85f;
            ps[i] = (6 + rnd.nextFloat() * 14) * d;
            pp[i] = rnd.nextFloat() * 6.28f;
            pv[i] = 0.4f + rnd.nextFloat() * 1.4f;                                               // Funkel-Tempo
            pc[i] = rnd.nextInt(3);
        }
    }

    private void resetDrop(int i, int w, int h, boolean initial, float d) {
        dx[i] = rnd.nextFloat() * w;
        dy[i] = initial ? rnd.nextFloat() * h : -10 * d;
        dr[i] = (3 + rnd.nextFloat() * 7) * d;
        dv[i] = rnd.nextInt(4) == 0 ? (30 + rnd.nextFloat() * 80) * d : 0;                       // manche rinnen herunter
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (bg == null) { if (running) postInvalidateDelayed(frameMs); return; }
        long now = System.currentTimeMillis();
        float dt = Math.min(0.08f, (now - last) / 1000f);
        last = now;
        float d = getResources().getDisplayMetrics().density;
        c.drawBitmap(bg, 0, 0, null);

        if (type == FIRE) {
            for (int i = 0; i < n; i++) {
                pl[i] += dt * (pc[i] == 3 ? 0.45f : 0.75f);
                if (pl[i] >= 1f) { resetParticle(i, w, h, false, d); continue; }
                py[i] -= pv[i] * dt;
                px[i] += (float) Math.sin(now / 260.0 + pp[i]) * 22 * d * dt;
                float life = pl[i];
                float size = ps[i] * (pc[i] == 3 ? 1f : (1f - life * 0.75f));
                int a = (int) (255 * (pc[i] == 3 ? (1 - life) : Math.sin(Math.PI * Math.min(1, life * 1.4f)) * (1 - life)));
                p.setAlpha(Math.max(0, Math.min(255, a)));
                dst.set(px[i] - size, py[i] - size * 1.3f, px[i] + size, py[i] + size * 0.9f);
                c.drawBitmap(sprites[pc[i]], null, dst, p);
            }
        } else if (type == RAIN) {
            p.setStrokeWidth(1.2f * d);
            for (int i = 0; i < n; i++) {
                py[i] += pv[i] * dt;
                px[i] -= pv[i] * dt * 0.12f;
                if (py[i] > h) resetParticle(i, w, h, false, d);
                p.setColor(((int) (pl[i] * 255) << 24) | 0xBFD4FF);
                c.drawLine(px[i], py[i], px[i] + ps[i] * 0.12f, py[i] - ps[i], p);
            }
            for (int i = 0; i < dx.length; i++) {
                if (dv[i] > 0) { dy[i] += dv[i] * dt; if (dy[i] > h + 20) resetDrop(i, w, h, false, d); }
                else if (rnd.nextInt(900) == 0) dv[i] = (30 + rnd.nextFloat() * 80) * d;
                p.setColor(0x30FFFFFF);
                c.drawCircle(dx[i], dy[i], dr[i], p);
                p.setColor(0x55FFFFFF);
                c.drawCircle(dx[i] - dr[i] * 0.3f, dy[i] - dr[i] * 0.3f, dr[i] * 0.3f, p);
            }
            // Gewitter: selten, ein heller Blitz (zwei Pulse, weit unter 3 pro Sekunde)
            if (now > nextEvent) {
                nextEvent = now + 40000 + rnd.nextInt(60000);
                flashA = 1f;
                if (events != null) events.flash(RAIN);
            }
            if (flashA > 0) {
                p.setColor(((int) (flashA * 90) << 24) | 0xDDE6FF);
                c.drawRect(0, 0, w, h, p);
                flashA = Math.max(0, flashA - dt * 1.6f);
            }
        } else {
            for (int i = 0; i < n; i++) {
                float tw = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(now / 1000.0 * pv[i] + pp[i]));
                p.setAlpha((int) (tw * 255));
                float s = ps[i] * (0.8f + tw * 0.4f);
                dst.set(px[i] - s, py[i] - s, px[i] + s, py[i] + s);
                c.drawBitmap(sprites[pc[i]], null, dst, p);
            }
            if (slife < 0 && now > nextEvent) {           // Sternschnuppe
                nextEvent = now + 15000 + rnd.nextInt(30000);
                sx = w * (0.2f + rnd.nextFloat() * 0.6f); sy = h * (0.05f + rnd.nextFloat() * 0.25f);
                float sp = (700 + rnd.nextFloat() * 400) * d;
                svx = (rnd.nextBoolean() ? 1 : -1) * sp; svy = sp * 0.45f; slife = 0f;
                if (events != null) events.flash(STARS);
            }
            if (slife >= 0) {
                slife += dt;
                sx += svx * dt; sy += svy * dt;
                float a = Math.max(0, 1f - slife / 1.1f);
                p.setShader(new LinearGradient(sx, sy, sx - svx * 0.18f, sy - svy * 0.18f, ((int) (a * 255) << 24) | 0xFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                p.setStrokeWidth(2.2f * d);
                c.drawLine(sx, sy, sx - svx * 0.18f, sy - svy * 0.18f, p);
                p.setShader(null);
                if (slife > 1.1f) slife = -1;
            }
        }
        if (running) postInvalidateDelayed(frameMs);
    }
}
