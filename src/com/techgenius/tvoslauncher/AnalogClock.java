package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;

/**
 * Analog clock faces for the Liquid Glass home, drawn in code:
 *  liquid      — a disc of Liquid Glass with white marks and hands
 *  glass       — clear glass dial, outlined bar markers, brushed-metal centre (glass wall clock)
 *  minimal     — light rounded tile, thin ring, dark hands, red second hand
 *  neumorphic  — soft raised dial with 12/3/6/9, pink hour hand, blue second hand
 *  meridian    — watch dial with minute track and red second hand, light or dark
 * The dial is drawn once into a bitmap; only the hands are drawn every second.
 */
final class AnalogClock extends View {

    static final String[] FACES = {"liquid", "glass", "minimal", "neumorphic", "meridian", "meridian_dark"};
    static final String[] FACE_NAMES = {"Liquid glass", "Clear glass", "Minimal", "Neumorphic", "Meridian", "Meridian dark"};

    private final String face, marks;
    private final boolean seconds;
    private Bitmap dial;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();

    /** @param marks Liquid glass face only: numbers (12, 3, 6, 9), all (1–12), lines, none */
    AnalogClock(Context c, String face, boolean seconds, String marks) {
        super(c);
        this.face = face == null ? "liquid" : face;
        this.seconds = seconds;
        this.marks = marks == null ? "numbers" : marks;
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null);  // shadows on lines
    }

    /** The second hand sweeps: redraw every frame while it shows; otherwise once a second. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            invalidate();
            boolean onScreen = getWindowVisibility() == VISIBLE && isShown();
            if (seconds && onScreen) postOnAnimation(this);
            else postDelayed(this, 1000);
        }
    };

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        post(tick);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        dial = null;
    }

    @Override protected void onDraw(Canvas c) {
        int s = Math.min(getWidth(), getHeight());
        if (s <= 0) return;
        if (dial == null || dial.getWidth() != s) {
            dial = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            drawDial(new Canvas(dial), s);
        }
        c.drawBitmap(dial, 0, 0, null);
        drawHands(c, s);
    }

    // ------------------------------------------------------------------ dials

    private void drawDial(Canvas c, int s) {
        float cx = s / 2f, cy = s / 2f, R = s / 2f * 0.92f;
        Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (face) {
            case "glass": {
                q.setShader(new RadialGradient(cx - R * 0.3f, cy - R * 0.35f, R * 1.3f,
                        new int[]{0x4DFFFFFF, 0x1AFFFFFF, 0x26FFFFFF}, new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, R, q);
                q.setShader(null);
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeWidth(R * 0.012f);
                q.setColor(0xB3FFFFFF);
                c.drawCircle(cx, cy, R, q);
                q.setStrokeWidth(R * 0.02f);
                q.setColor(0x66FFFFFF);
                c.drawArc(new RectF(cx - R * 0.95f, cy - R * 0.95f, cx + R * 0.95f, cy + R * 0.95f), 200, 70, false, q);
                // outlined bars, the quarter ones longer
                q.setStrokeWidth(R * 0.014f);
                q.setColor(0xF2FFFFFF);
                q.setShadowLayer(R * 0.03f, 0, R * 0.01f, 0x66000000);
                for (int i = 0; i < 12; i++) {
                    float len = i % 3 == 0 ? R * 0.24f : R * 0.17f, w = R * 0.045f;
                    c.save();
                    c.rotate(i * 30, cx, cy);
                    float top = cy - R * 0.86f;
                    c.drawRoundRect(new RectF(cx - w / 2, top, cx + w / 2, top + len), w / 2, w / 2, q);
                    c.restore();
                }
                q.clearShadowLayer();
                q.setStyle(Paint.Style.FILL);
                // brushed-metal centre
                float cr = R * 0.2f;
                q.setShadowLayer(R * 0.04f, 0, R * 0.015f, 0x59000000);
                q.setShader(new RadialGradient(cx - cr * 0.3f, cy - cr * 0.3f, cr * 1.4f,
                        new int[]{0xFFF6F7F9, 0xFFC4C8CF, 0xFF8E939C}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, cr, q);
                q.clearShadowLayer();
                q.setShader(new android.graphics.SweepGradient(cx, cy,
                        new int[]{0x33FFFFFF, 0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF, 0x33FFFFFF}, null));
                c.drawCircle(cx, cy, cr, q);
                q.setShader(null);
                break;
            }
            case "minimal": {
                float r = s * 0.22f;
                q.setShader(new LinearGradient(0, 0, s, s, 0xFFD9E4F6, 0xFFF5F8FD, Shader.TileMode.CLAMP));
                q.setShadowLayer(s * 0.03f, 0, s * 0.012f, 0x4D000000);
                c.drawRoundRect(new RectF(s * 0.03f, s * 0.03f, s * 0.97f, s * 0.97f), r, r, q);
                q.clearShadowLayer();
                q.setShader(null);
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeWidth(R * 0.022f);
                q.setColor(0xFFC3C9D2);
                c.drawCircle(cx, cy, R * 0.76f, q);
                break;
            }
            case "neumorphic": {
                q.setColor(0xFFD3DAE3);
                q.setShadowLayer(R * 0.07f, R * 0.035f, R * 0.045f, 0x73000000);
                c.drawCircle(cx, cy, R * 0.94f, q);
                q.setShadowLayer(R * 0.06f, -R * 0.03f, -R * 0.03f, 0x99FFFFFF);
                c.drawCircle(cx, cy, R * 0.94f, q);
                q.clearShadowLayer();
                // gentle concave inner face
                q.setShader(new LinearGradient(cx - R, cy - R, cx + R, cy + R, 0xFFC9D0DA, 0xFFDDE3EA, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, R * 0.86f, q);
                q.setShader(null);
                Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
                t.setColor(0xFF77808E);
                t.setTypeface(Ui.font(getContext(), "semibold"));
                t.setTextSize(R * 0.2f);
                t.setTextAlign(Paint.Align.CENTER);
                String[] nums = {"12", "3", "6", "9"};
                for (int i = 0; i < 4; i++) {
                    double a = Math.toRadians(i * 90 - 90);
                    float x = cx + (float) Math.cos(a) * R * 0.68f, y = cy + (float) Math.sin(a) * R * 0.68f + t.getTextSize() * 0.36f;
                    c.drawText(nums[i], x, y, t);
                }
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeCap(Paint.Cap.ROUND);
                q.setStrokeWidth(R * 0.028f);
                q.setColor(0xFF6F7784);
                for (int i = 0; i < 12; i++) {
                    if (i % 3 == 0) continue;
                    double a = Math.toRadians(i * 30 - 90);
                    c.drawLine(cx + (float) Math.cos(a) * R * 0.7f, cy + (float) Math.sin(a) * R * 0.7f,
                            cx + (float) Math.cos(a) * R * 0.78f, cy + (float) Math.sin(a) * R * 0.78f, q);
                }
                break;
            }
            case "meridian":
            case "meridian_dark": {
                boolean dark = face.endsWith("dark");
                int bg = dark ? 0xFF2B2B2D : 0xFFF3F0EB, ink = dark ? 0xFFEAE6DF : 0xFF242426;
                q.setColor(bg);
                q.setShadowLayer(R * 0.05f, 0, R * 0.02f, 0x66000000);
                c.drawCircle(cx, cy, R, q);
                q.clearShadowLayer();
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeCap(Paint.Cap.BUTT);
                q.setColor(ink);
                for (int i = 0; i < 60; i++) {
                    boolean hour = i % 5 == 0;
                    q.setStrokeWidth(hour ? R * 0.022f : R * 0.008f);
                    float r0 = hour ? R * 0.78f : R * 0.86f;
                    if (i == 0) continue;
                    double a = Math.toRadians(i * 6 - 90);
                    c.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0,
                            cx + (float) Math.cos(a) * R * 0.92f, cy + (float) Math.sin(a) * R * 0.92f, q);
                }
                // double bar at 12
                q.setStrokeWidth(R * 0.03f);
                c.drawLine(cx - R * 0.035f, cy - R * 0.92f, cx - R * 0.035f, cy - R * 0.72f, q);
                c.drawLine(cx + R * 0.035f, cy - R * 0.92f, cx + R * 0.035f, cy - R * 0.72f, q);
                break;
            }
            default: {  // liquid: the glass disc is the view background; a plain rim, no marks
                q.setStyle(Paint.Style.STROKE);
                q.setStrokeWidth(Math.max(1f, R * 0.012f));
                q.setColor(0x80FFFFFF);
                c.drawCircle(cx, cy, s / 2f - q.getStrokeWidth(), q);
                q.clearShadowLayer();
                break;
            }
        }
    }

    // ------------------------------------------------------------------ hands

    private void hand(Canvas c, float cx, float cy, float deg, float len, float tail, float w, int color, boolean shadow, Paint.Cap cap) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(cap);
        p.setStrokeWidth(w);
        p.setColor(color);
        if (shadow) p.setShadowLayer(w * 1.2f, 0, w * 0.5f, 0x66000000);
        else p.clearShadowLayer();
        double a = Math.toRadians(deg - 90);
        float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
        c.drawLine(cx - dx * tail, cy - dy * tail, cx + dx * len, cy + dy * len, p);
        p.clearShadowLayer();
    }

    /** A tapered hand (Meridian). */
    private void taper(Canvas c, float cx, float cy, float deg, float len, float w, int color) {
        c.save();
        c.rotate(deg, cx, cy);
        path.reset();
        path.moveTo(cx - w / 2, cy + w);
        path.lineTo(cx - w * 0.18f, cy - len);
        path.lineTo(cx + w * 0.18f, cy - len);
        path.lineTo(cx + w / 2, cy + w);
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(path, p);
        c.restore();
    }

    private void drawHands(Canvas c, int s) {
        Calendar cal = Calendar.getInstance();
        float sec = cal.get(Calendar.SECOND) + cal.get(Calendar.MILLISECOND) / 1000f;
        float min = cal.get(Calendar.MINUTE) + sec / 60f;
        float hr = cal.get(Calendar.HOUR) + min / 60f;
        float cx = s / 2f, cy = s / 2f, R = s / 2f * 0.92f;
        float h = hr * 30, m = min * 6, sc = sec * 6;
        switch (face) {
            case "glass":
                hand(c, cx, cy, h, R * 0.5f, 0, R * 0.035f, 0xFFFFFFFF, true, Paint.Cap.ROUND);
                hand(c, cx, cy, m, R * 0.76f, 0, R * 0.022f, 0xFFFFFFFF, true, Paint.Cap.ROUND);
                if (seconds) hand(c, cx, cy, sc, R * 0.8f, R * 0.18f, R * 0.01f, 0xFF1C1C1E, true, Paint.Cap.ROUND);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF1C1C1E);
                c.drawCircle(cx, cy, R * 0.03f, p);
                break;
            case "minimal":
                hand(c, cx, cy, h, R * 0.42f, 0, R * 0.08f, 0xFF26282C, false, Paint.Cap.ROUND);
                hand(c, cx, cy, m, R * 0.62f, 0, R * 0.075f, 0xFF26282C, false, Paint.Cap.ROUND);
                if (seconds) hand(c, cx, cy, sc, R * 0.55f, R * 0.16f, R * 0.02f, 0xFFD0312D, false, Paint.Cap.ROUND);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF26282C);
                c.drawCircle(cx, cy, R * 0.075f, p);
                break;
            case "neumorphic":
                hand(c, cx, cy, h, R * 0.42f, 0, R * 0.055f, 0xFFFF2D6B, true, Paint.Cap.ROUND);
                hand(c, cx, cy, m, R * 0.62f, 0, R * 0.026f, 0xFF15181E, true, Paint.Cap.ROUND);
                if (seconds) hand(c, cx, cy, sc, R * 0.7f, R * 0.16f, R * 0.012f, 0xFF4A90E2, false, Paint.Cap.ROUND);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF2F7BD8);
                c.drawCircle(cx, cy, R * 0.05f, p);
                break;
            case "meridian":
            case "meridian_dark": {
                int ink = face.endsWith("dark") ? 0xFFF3F0EB : 0xFF1B1B1D;
                taper(c, cx, cy, h, R * 0.5f, R * 0.07f, ink);
                taper(c, cx, cy, m, R * 0.8f, R * 0.05f, ink);
                if (seconds) hand(c, cx, cy, sc, R * 0.86f, R * 0.2f, R * 0.012f, 0xFFE5483C, false, Paint.Cap.BUTT);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(R * 0.018f);
                p.setColor(0xFFE5483C);
                c.drawCircle(cx, cy, R * 0.045f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy, R * 0.02f, p);
                break;
            }
            default:
                hand(c, cx, cy, h, R * 0.46f, 0, R * 0.06f, 0xFFFFFFFF, true, Paint.Cap.ROUND);
                hand(c, cx, cy, m, R * 0.68f, 0, R * 0.04f, 0xFFFFFFFF, true, Paint.Cap.ROUND);
                if (seconds) hand(c, cx, cy, sc, R * 0.74f, R * 0.14f, R * 0.014f, 0xFF8FD3FF, true, Paint.Cap.ROUND);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, R * 0.055f, p);
                break;
        }
    }
}
