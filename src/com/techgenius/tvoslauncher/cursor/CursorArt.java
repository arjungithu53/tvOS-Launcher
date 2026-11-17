package com.techgenius.tvoslauncher.cursor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/** Renders each cursor style once into a bitmap. */
final class CursorArt {

    static final int CHROME = 0;
    static final int IRIDESCENT = 1;
    static final int CLASSIC = 2;
    static final int BUBBLE = 3;
    static final String[] NAMES = {"Chrome glass", "Iridescent glass", "Classic white", "Glass bubble"};

    /**
     * bmp: the picture. (ax, ay): the click point inside it.
     * extL/T/R/B: how far the visible cursor reaches left/up/right/down from the click point.
     */
    static final class Sprite {
        final Bitmap bmp;
        final float ax, ay;
        final float extL, extT, extR, extB;
        Sprite(Bitmap bmp, float ax, float ay, float extL, float extT, float extR, float extB) {
            this.bmp = bmp; this.ax = ax; this.ay = ay;
            this.extL = extL; this.extT = extT; this.extR = extR; this.extB = extB;
        }
    }

    private CursorArt() { }

    static float unit(Context c) {
        return c.getResources().getDisplayMetrics().density * 1.5f;
    }

    static Sprite render(Context c, int style) {
        float u = unit(c);
        if (style == BUBBLE) return bubble(u);
        if (style < 0 || style > BUBBLE) style = CHROME;
        return arrow(u, style);
    }

    // ------------------------------------------------------------------ arrows

    private static final float[][] PTS =
            {{0, 0}, {0, 18}, {4.5f, 14}, {7.5f, 21}, {10.5f, 19.8f}, {7.6f, 13.2f}, {13, 13}};

    private static Path arrowPath(float u, float ox, float oy) {
        Path p = new Path();
        p.moveTo(ox + PTS[0][0] * u, oy + PTS[0][1] * u);
        for (int i = 1; i < PTS.length; i++) p.lineTo(ox + PTS[i][0] * u, oy + PTS[i][1] * u);
        p.close();
        return p;
    }

    private static Sprite arrow(float u, int s) {
        float pad = 6 * u;
        int bw = (int) Math.ceil(13 * u + 2 * pad + u);
        int bh = (int) Math.ceil(21 * u + 2 * pad + 2 * u);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.translate(pad, pad);
        Path a = arrowPath(u, 0, 0);

        if (s == CLASSIC) {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.WHITE);
            c.drawPath(a, fill);
            Paint edge = stroke(1.4f * u);
            edge.setColor(Color.BLACK);
            c.drawPath(a, edge);
            return new Sprite(b, pad, pad, 0, 0, 13.7f * u, 21.7f * u);
        }
        boolean iri = s == IRIDESCENT;

        // Drop shadow, cut out from under the glass so the body stays see-through.
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setStyle(Paint.Style.FILL_AND_STROKE);
        shadow.setStrokeWidth(1.4f * u);
        shadow.setStrokeJoin(Paint.Join.ROUND);
        shadow.setColor(0x70000000);
        shadow.setMaskFilter(new BlurMaskFilter(3f * u, BlurMaskFilter.Blur.NORMAL));
        c.drawPath(arrowPath(u, 0.8f * u, 2f * u), shadow);
        Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawPath(a, clear);

        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setShader(new LinearGradient(0, 0, 13 * u, 21 * u,
                iri ? new int[]{0x80FFFFFF, 0x299AD7FF, 0x40FFC48A, 0x30C59BFF}
                    : new int[]{0x8CFFFFFF, 0x33DDE8F5, 0x55FFFFFF, 0x2BB0C4DE},
                new float[]{0f, 0.45f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(a, body);

        c.save();
        c.clipPath(a);
        Paint depth = stroke(3.2f * u);
        depth.setShader(new LinearGradient(13 * u, 21 * u, 0, 0,
                0x30000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawPath(a, depth);
        Paint streak = stroke(1.6f * u);
        streak.setStrokeCap(Paint.Cap.ROUND);
        streak.setColor(0xCCFFFFFF);
        streak.setMaskFilter(new BlurMaskFilter(0.9f * u, BlurMaskFilter.Blur.NORMAL));
        c.drawLine(1.2f * u, 2f * u, 1.2f * u, 15f * u, streak);
        Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glint.setColor(0x99FFFFFF);
        glint.setMaskFilter(new BlurMaskFilter(1.3f * u, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(2.2f * u, 4f * u, 1.6f * u, glint);
        c.restore();

        Paint halo = stroke(2f * u);
        halo.setColor(0x24000000);
        c.drawPath(a, halo);

        Paint rim = stroke(1.3f * u);
        rim.setShader(iri
                ? new LinearGradient(0, 0, 13 * u, 21 * u,
                    new int[]{0xFF5FE1FF, 0xFFB57BFF, 0xFFFF9F43, 0xFFFFFFFF, 0xFF5FE1FF},
                    new float[]{0f, 0.3f, 0.55f, 0.8f, 1f}, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 0, 13 * u, 21 * u,
                    new int[]{0xFFFFFFFF, 0xFF9AA4B0, 0xFFFFFFFF, 0xFF6E7884, 0xFFE8EEF4},
                    new float[]{0f, 0.3f, 0.5f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(a, rim);
        return new Sprite(b, pad, pad, 0, 0, 13.7f * u, 21.7f * u);
    }

    // ------------------------------------------------------------------ bubble

    private static Sprite bubble(float u) {
        float r = 8 * u;
        float pad = 4 * u;
        int size = (int) Math.ceil(2 * r + 2 * pad);
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = size / 2f, cy = size / 2f;

        // Faint outer halo so it stays visible on white pages.
        Paint halo = stroke(1f * u);
        halo.setColor(0x1A000000);
        halo.setMaskFilter(new BlurMaskFilter(0.9f * u, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy, r + 0.6f * u, halo);

        // Clear centre, brighter towards the edge (like real glass).
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setShader(new RadialGradient(cx, cy, r,
                new int[]{0x00FFFFFF, 0x08FFFFFF, 0x30DDEEFF, 0x55FFFFFF},
                new float[]{0f, 0.6f, 0.88f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, body);

        // Refraction colour fringe: orange top-left, blue bottom-right, fading in between.
        Paint chroma = stroke(0.9f * u);
        chroma.setShader(new SweepGradient(cx, cy,
                new int[]{0x004FC3FF, 0xB04FC3FF, 0x004FC3FF, 0x00FF9A3C, 0xB0FF9A3C, 0x00FF9A3C, 0x004FC3FF},
                new float[]{0f, 45f / 360, 110f / 360, 160f / 360, 225f / 360, 290f / 360, 1f}));
        chroma.setMaskFilter(new BlurMaskFilter(0.4f * u, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx, cy, r - 0.6f * u, chroma);

        Paint rim = stroke(0.45f * u);
        rim.setColor(0xBFFFFFFF);
        c.drawCircle(cx, cy, r, rim);

        // Specular highlights.
        Paint spec = stroke(1.4f * u);
        spec.setStrokeCap(Paint.Cap.ROUND);
        spec.setColor(0xDEFFFFFF);
        spec.setMaskFilter(new BlurMaskFilter(0.45f * u, BlurMaskFilter.Blur.NORMAL));
        float ri = r - 1.8f * u;
        c.drawArc(new RectF(cx - ri, cy - ri, cx + ri, cy + ri), 195, 70, false, spec);
        Paint spec2 = stroke(1f * u);
        spec2.setStrokeCap(Paint.Cap.ROUND);
        spec2.setColor(0x80FFFFFF);
        spec2.setMaskFilter(new BlurMaskFilter(0.45f * u, BlurMaskFilter.Blur.NORMAL));
        float r2 = r - 1.6f * u;
        c.drawArc(new RectF(cx - r2, cy - r2, cx + r2, cy + r2), 20, 35, false, spec2);

        return new Sprite(b, cx, cy, r + 0.6f * u, r + 0.6f * u, r + 0.6f * u, r + 0.6f * u);
    }

    private static Paint stroke(float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }
}
