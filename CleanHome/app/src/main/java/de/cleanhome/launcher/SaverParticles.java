package de.cleanhome.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/** Feine Lichtpunkte, die langsam nach oben treiben und funkeln (wie Staub im Projektorlicht). */
public class SaverParticles extends View {
    private final int n;
    private final float[] x, y, r, sp, ph;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();
    private final long frameMs;
    private boolean running;
    public boolean external;          // gemeinsamer Takt statt eigener Schleife
    private int accent = 0xFF0A72EF;
    private long last;

    @Override public boolean hasOverlappingRendering() { return false; }

    public SaverParticles(Context c, boolean lite) {
        super(c);
        n = lite ? 22 : 46;
        frameMs = lite ? 40 : 25;
        x = new float[n]; y = new float[n]; r = new float[n]; sp = new float[n]; ph = new float[n];
        float d = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextFloat(); y[i] = rnd.nextFloat();
            r[i] = (0.8f + rnd.nextFloat() * 1.8f) * d;
            sp[i] = 0.004f + rnd.nextFloat() * 0.012f;      // Bildschirmhoehen pro Sekunde
            ph[i] = rnd.nextFloat() * 6.28f;
        }
    }

    public void setAccent(int c) { accent = c; }
    public void start() { running = true; last = System.currentTimeMillis(); invalidate(); }
    public void stop() { running = false; }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1f, (now - last) / 1000f);
        last = now;
        int w = getWidth(), h = getHeight();
        float t = now / 1000f;
        for (int i = 0; i < n; i++) {
            y[i] -= sp[i] * dt;
            x[i] += (float) Math.sin(t * 0.3f + ph[i]) * 0.0006f;
            if (y[i] < -0.02f) { y[i] = 1.02f; x[i] = rnd.nextFloat(); }
            float tw = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * 1.6f + ph[i]));
            int base = (i % 3 == 0) ? accent : 0xFFFFFFFF;
            p.setColor((base & 0x00FFFFFF) | ((int) (tw * 150) << 24));
            c.drawCircle(x[i] * w, y[i] * h, r[i], p);
        }
        if (running && !external) postInvalidateDelayed(frameMs);
    }
}
