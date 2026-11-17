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

    /** Weather on the glass: 0 none, 1 rain drops, 2 frost. Shared by every panel. */
    private static int weather;

    public static void setWeather(int w) {
        weather = w;
    }

    private final Paint fxP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path clipPath = new android.graphics.Path();

    // ---------------------------------------------------------------- refraction (Android 13+)

    /**
     * Thick-glass refraction: the scene under the glass is pulled outward near the rounded edge,
     * with a hint of colour fringing. One GPU program reading the shared wallpaper copy: no extra
     * images per panel and nothing new per frame.
     */
    private static final String REFRACT_AGSL =
            "uniform shader img;\n"
            + "uniform shader blr;\n"
            + "uniform float mixv;\n"
            + "uniform float2 origin;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float bend;\n"
            + "uniform float band;\n"
            + "uniform float zoom;\n"
            + "uniform float shine;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 c = size * 0.5;\n"
            + "  float2 pl = p - origin;\n"
            + "  float2 q = abs(pl - c) - (c - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            // t: 0 in the flat middle, 1 at the rim. The glass is thickest-curved near the edge.
            + "  float t = clamp(1.0 + d / band, 0.0, 1.0);\n"
            + "  float k = t * t * (3.0 - 2.0 * t);\n"
            + "  k = k * k;\n"
            // Smooth normal: treat the corners as if rounded by the band too, so there are no seams.
            + "  float2 g = normalize(max(q + float2(max(band, radius) - radius), float2(0.001)));\n"
            + "  float2 n = sign(pl - c) * g;\n"
            // A lens: the middle magnifies a little; the curved rim squeezes in the scene from just beyond the edge.
            + "  float2 sp = origin + c + (pl - c) * zoom + n * bend * k;\n"
            + "  float2 f = n * bend * k * 0.08;\n"
            + "  half m = half(mixv);\n"
            + "  half4 col = mix(img.eval(sp), blr.eval(sp), m);\n"
            + "  half r = mix(img.eval(sp - f).r, blr.eval(sp - f).r, m);\n"
            + "  half b = mix(img.eval(sp + f).b, blr.eval(sp + f).b, m);\n"
            + "  half3 rgb = half3(r, col.g, b);\n"
            // Specular: light from the top left catches the curved rim (and a little on the opposite side).
            + "  float lit = max(dot(n, normalize(float2(-0.55, -0.85))), 0.0);\n"
            + "  float back = max(dot(n, normalize(float2(0.55, 0.85))), 0.0);\n"
            + "  float rim = smoothstep(0.55, 1.0, t);\n"
            + "  float s = (pow(lit, 2.0) * 0.55 + pow(back, 3.0) * 0.22) * rim * shine;\n"
            + "  rgb = rgb + half3(s);\n"
            + "  return half4(rgb, 1.0);\n"
            + "}\n";

    private static boolean refractOn = true;
    private static boolean refractBroken;
    private Object refractSh;  // android.graphics.RuntimeShader, created on Android 13+

    public static void setRefraction(boolean on) {
        refractOn = on;
    }

    /** Up to about a third of the screen (the Control Panel); a full-screen sheet would cost too much GPU time. */
    private boolean useRefraction() {
        return refractOn && !refractBroken && android.os.Build.VERSION.SDK_INT >= 33 && look.refr > 0
                && box.width() * box.height() <= screenW * (float) screenH * 0.35f;
    }

    /** Points the refraction program at this surface; false if it can't run (the ring lens is used then). */
    private boolean prepareRefraction() {
        if (android.os.Build.VERSION.SDK_INT < 33) return false;
        try {
            android.graphics.RuntimeShader rs = (android.graphics.RuntimeShader) refractSh;
            if (rs == null) {
                rs = new android.graphics.RuntimeShader(REFRACT_AGSL);
                refractSh = rs;
            }
            // Again each time: the wallpaper copies' placement changes.
            rs.setInputShader("img", sharpSh);
            boolean hasBlur = blurSh != null && blurFor != null && !blurFor.isRecycled();
            rs.setInputShader("blr", hasBlur ? blurSh : sharpSh);
            rs.setFloatUniform("mixv", hasBlur ? Math.min(1f, look.blur / 70f) : 0f);
            rs.setFloatUniform("origin", box.left, box.top);
            rs.setFloatUniform("size", box.width(), box.height());
            rs.setFloatUniform("radius", Math.min(radius, Math.min(box.width(), box.height()) / 2f));
            float min = Math.min(box.width(), box.height());
            float k = look.refr / 100f;
            float band = Math.min(64f * density, min * 0.42f);
            rs.setFloatUniform("band", band);
            rs.setFloatUniform("bend", band * (0.15f + k * 0.55f));
            rs.setFloatUniform("zoom", 1f - k * 0.07f);
            rs.setFloatUniform("shine", 0.25f + look.rim / 100f * 0.75f);
            refractP.setShader(rs);
            refractP.setAlpha(sharpP.getAlpha());
            return true;
        } catch (Throwable t) {
            refractBroken = true;
            android.util.Log.w("tvOSGlass", "refraction off: " + t);
            return false;
        }
    }

    private final Paint refractP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private boolean refractReady;

    /** The part this surface belongs to (Liquid.DOCK, ...), or null for one-off looks. */
    public String part;
    private static final java.util.List<java.lang.ref.WeakReference<GlassPanel>> LIVE = new java.util.ArrayList<>();

    /** Live preview: gives every part-based surface its current look. */
    public static void relookAll(android.content.Context c) {
        java.util.Iterator<java.lang.ref.WeakReference<GlassPanel>> it = LIVE.iterator();
        while (it.hasNext()) {
            GlassPanel g = it.next().get();
            if (g == null) {
                it.remove();
                continue;
            }
            if (g.part != null) g.setLook(Liquid.look(c, g.part));
        }
    }

    public GlassPanel(Source source, Liquid.Look look, float radius, int screenW, int screenH, float density, String part) {
        this(source, look, radius, screenW, screenH, density);
        this.part = part;
        LIVE.add(new java.lang.ref.WeakReference<>(this));
    }

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
        refractReady = false;
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
        refractReady = false;
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
            refractReady = false;
        }
        if (blur != null && (blur != blurFor || moved)) {
            if (blur != blurFor) {
                blurSh = new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                blurP.setShader(blurSh);
                blurFor = blur;
            }
            place(blurSh, blur, 1f);
            refractReady = false;
        }
        placedX = loc[0];
        placedY = loc[1];
        placedW = box.width();
        placedH = box.height();

        float r = radius;
        if (sharp != null) {
            boolean refract = useRefraction() && (refractReady || (refractReady = prepareRefraction()));
            c.drawRoundRect(box, r, r, refract ? refractP : sharpP);
            // The refraction program already blends in the blurred copy.
            if (!refract && blur != null && blurP.getAlpha() > 0) c.drawRoundRect(box, r, r, blurP);
            float lw = refract ? 0f : lensP.getStrokeWidth();
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
        if (weather != 0 && look.bg > 0 && box.width() > 40 * density) drawWeather(c, r);
        float in = rimP.getStrokeWidth() / 2f;
        c.drawRoundRect(box.left + in, box.top + in, box.right - in, box.bottom - in, r - in, r - in, rimP);
    }

    /** Rain: a scatter of small drops (the same pattern for a panel every time). Frost: icy corners. */
    private void drawWeather(Canvas c, float r) {
        clipPath.reset();
        clipPath.addRoundRect(box, r, r, android.graphics.Path.Direction.CW);
        c.save();
        c.clipPath(clipPath);
        float a = look.bg / 100f;
        if (weather == 1) {
            java.util.Random rnd = new java.util.Random((long) (box.width() * 31 + box.height()));
            int n = Math.max(8, Math.round(box.width() * box.height() / (900f * density * density)));
            n = Math.min(n, 60);
            for (int i = 0; i < n; i++) {
                float x = box.left + rnd.nextFloat() * box.width(), y = box.top + rnd.nextFloat() * box.height();
                float rad = (0.8f + rnd.nextFloat() * 2.2f) * density;
                fxP.setShader(new android.graphics.RadialGradient(x - rad * 0.3f, y - rad * 0.35f, rad * 1.2f,
                        new int[]{(Math.round(0xCC * a) << 24) | 0xFFFFFF, (Math.round(0x33 * a) << 24) | 0xFFFFFF, 0x00FFFFFF},
                        new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
                c.drawOval(x - rad, y - rad * 1.2f, x + rad, y + rad * 1.2f, fxP);
            }
        } else {
            float w = box.width(), h = box.height();
            fxP.setShader(new android.graphics.RadialGradient(box.left, box.top, Math.max(w, h) * 0.55f,
                    (Math.round(0x6B * a) << 24) | 0xEBF5FF, 0x00EBF5FF, Shader.TileMode.CLAMP));
            c.drawRect(box, fxP);
            fxP.setShader(new android.graphics.RadialGradient(box.right, box.bottom, Math.max(w, h) * 0.5f,
                    (Math.round(0x5C * a) << 24) | 0xEBF5FF, 0x00EBF5FF, Shader.TileMode.CLAMP));
            c.drawRect(box, fxP);
        }
        fxP.setShader(null);
        c.restore();
    }

    @Override public void setAlpha(int alpha) {}

    @Override public void setColorFilter(ColorFilter cf) {}

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
