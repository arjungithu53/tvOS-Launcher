package com.techgenius.tvoslauncher.liquid;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * The Liquid Glass surface used for every panel, button and widget card in the Liquid Glass layout.
 * It samples two small copies of the wallpaper (a sharp one and a blurred one, shared by every
 * panel) at the panel's spot on screen, then adds the glass on top. Each part is one rounded-rect
 * draw, so it costs the GPU very little and needs no extra memory per panel.
 *
 * Settings, all 0..100:
 *  transparency — how much of the scene shows through (the rest is a dark glass body)
 *  blur         — sharp scene at 0, fully blurred at 100
 *  refraction   — a magnified ring along the edge, as thick glass bends light
 *  tint         — milky white frost
 *  rim          — bright edge light
 *  amount (bg)  — the whole surface's opacity; 0 hides it (the dock's default)
 */
public final class GlassPanel extends Drawable {

    /** The shared wallpaper copies, full-screen sized in their own coordinates. */
    public interface Source {
        Bitmap sharp();
        Bitmap blurred();
    }

    private final Source source;
    private final int screenW, screenH;
    private final float radius, density;
    private Liquid.Look look;

    private final Paint sharpP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint blurP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint lensP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint bodyP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frostP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF(), ring = new RectF();
    private final Matrix m = new Matrix();
    private final int[] loc = new int[2];
    private Bitmap sharpFor, blurFor;
    private BitmapShader sharpSh, blurSh, lensSh;
    private int placedX = Integer.MIN_VALUE, placedY = Integer.MIN_VALUE;
    private float placedW = -1, placedH = -1;
    private boolean lit;

    public GlassPanel(Source source, Liquid.Look look, float radius, int screenW, int screenH, float density) {
        this.source = source;
        this.radius = radius;
        this.screenW = screenW;
        this.screenH = screenH;
        this.density = density;
        lensP.setStyle(Paint.Style.STROKE);
        rimP.setStyle(Paint.Style.STROKE);
        rimP.setStrokeWidth(Math.max(1f, 1.1f * density));
        setLook(look);
    }

    public void setLook(Liquid.Look look) {
        this.look = look;
        float amount = look.bg / 100f;
        int dark = Math.round((100 - look.trans) / 100f * 0.78f * 255 * amount);
        bodyP.setColor((dark << 24) | 0x10131A);
        int frost = Math.round(look.tint / 100f * 0.5f * 255 * amount);
        frostP.setColor((frost << 24) | 0xFFFFFF);
        sharpP.setAlpha(Math.round(255 * amount));
        blurP.setAlpha(Math.round(255 * amount * Math.min(1f, look.blur / 70f)));
        lensP.setAlpha(Math.round(255 * amount * Math.min(1f, 0.25f + look.refr / 100f * 0.75f)));
        rebuildGradients();
        invalidateSelf();
    }

    /** Focused state: a brighter frost, used for buttons. */
    public void setLit(boolean lit) {
        if (this.lit == lit) return;
        this.lit = lit;
        invalidateSelf();
    }

    private int rimA(int a) {
        float k = look.rim / 100f * look.bg / 100f;
        return (Math.round(a * k) << 24) | 0xFFFFFF;
    }

    private void rebuildGradients() {
        if (box.isEmpty()) return;
        float h = box.height();
        glowP.setShader(new LinearGradient(0, box.top, 0, box.top + h * 0.5f,
                rimA(0x24), 0x00FFFFFF, Shader.TileMode.CLAMP));
        rimP.setShader(new LinearGradient(box.left, box.top, box.right, box.bottom,
                new int[]{rimA(0xE0), rimA(0x44), rimA(0x14), rimA(0x2A), rimA(0x96)},
                new float[]{0f, 0.22f, 0.5f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        float lens = Math.min(10f * density, Math.min(box.width(), box.height()) / 3f) * look.refr / 100f;
        lensP.setStrokeWidth(Math.max(0f, lens));
    }

    @Override protected void onBoundsChange(Rect b) {
        box.set(b);
        placedW = -1;
        rebuildGradients();
    }

    private void place(BitmapShader sh, Bitmap b, float zoom) {
        m.reset();
        m.setScale(screenW / (float) b.getWidth(), screenH / (float) b.getHeight());
        m.postTranslate(box.left - loc[0], box.top - loc[1]);
        if (zoom != 1f) m.postScale(zoom, zoom, box.centerX(), box.centerY());
        sh.setLocalMatrix(m);
    }

    @Override public void draw(Canvas c) {
        if (look.bg <= 0) return;
        Object cb = getCallback();
        if (cb instanceof View) ((View) cb).getLocationOnScreen(loc);
        Bitmap sharp = source != null ? source.sharp() : null;
        Bitmap blur = source != null ? source.blurred() : null;
        if (sharp != null && sharp.isRecycled()) sharp = null;
        if (blur != null && blur.isRecycled()) blur = null;
        if (sharp == null) sharp = blur;  // until the sharp copy is ready

        boolean moved = loc[0] != placedX || loc[1] != placedY || box.width() != placedW || box.height() != placedH;
        if (sharp != null && (sharp != sharpFor || moved)) {
            if (sharp != sharpFor) {
                sharpSh = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                lensSh = new BitmapShader(sharp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                sharpP.setShader(sharpSh);
                lensP.setShader(lensSh);
                sharpFor = sharp;
            }
            place(sharpSh, sharp, 1f);
            place(lensSh, sharp, 1f + look.refr / 100f * 0.08f);
        }
        if (blur != null && (blur != blurFor || moved)) {
            if (blur != blurFor) {
                blurSh = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                blurP.setShader(blurSh);
                blurFor = blur;
            }
            place(blurSh, blur, 1f);
        }
        placedX = loc[0];
        placedY = loc[1];
        placedW = box.width();
        placedH = box.height();

        float r = radius;
        if (sharp != null) {
            c.drawRoundRect(box, r, r, sharpP);
            if (blur != null && blurP.getAlpha() > 0) c.drawRoundRect(box, r, r, blurP);
            float lw = lensP.getStrokeWidth();
            if (lw > 0.5f) {
                ring.set(box.left + lw / 2, box.top + lw / 2, box.right - lw / 2, box.bottom - lw / 2);
                c.drawRoundRect(ring, Math.max(0, r - lw / 2), Math.max(0, r - lw / 2), lensP);
            }
        }
        c.drawRoundRect(box, r, r, bodyP);
        c.drawRoundRect(box, r, r, frostP);
        if (lit) {
            frostP.setAlpha(Math.min(255, frostP.getAlpha() + 0x40));
            c.drawRoundRect(box, r, r, frostP);
            frostP.setAlpha(Math.round(look.tint / 100f * 0.5f * 255 * look.bg / 100f));
        }
        c.drawRoundRect(box, r, r, glowP);
        float in = rimP.getStrokeWidth() / 2f;
        c.drawRoundRect(box.left + in, box.top + in, box.right - in, box.bottom - in, r - in, r - in, rimP);
    }

    @Override public void setAlpha(int alpha) {}

    @Override public void setColorFilter(ColorFilter cf) {}

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
