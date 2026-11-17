package com.techgenius.tvoslauncher.liquid;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.LruCache;

/**
 * Liquid Glass app icons: the app's logo sitting in a block of coloured glass, drawn once per app
 * and cached. The logo comes from the icon's foreground layer when the app ships one; otherwise the
 * icon's plain background is lifted off around the logo; when neither works, the whole icon goes
 * inside the glass. The glass colour comes from the icon's background.
 *
 * Layers, back to front (same recipe as the design): extrusion (depth), glass body with a
 * light-to-deep gradient (see-through lowers its opacity), soft inner rim, bottom glow, the logo's
 * coloured shadow, the logo.
 */
public final class GlassIcon {
    private GlassIcon() {}

    /** Icon pictures, about 150 KB each at the default size; capped at ~6 MB. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    public static Bitmap cached(String key) {
        return key == null ? null : CACHE.get(key);
    }

    public static void put(String key, Bitmap b) {
        if (key != null && b != null) CACHE.put(key, b);
    }

    public static void clearCache() {
        CACHE.evictAll();
    }

    // ------------------------------------------------------------------ app icons

    /**
     * @param square the launcher's full-bleed square picture of the app (may be null)
     * @param icon   the app's icon drawable (adaptive icons give a clean logo layer; may be null)
     * @param size   output size in pixels
     */
    public static Bitmap forApp(Bitmap square, Drawable icon, int size, String style, int depth, int clarity) {
        Bitmap symbol = null;
        int base = 0;
        float symbolScale = 0.62f;

        if (Build.VERSION.SDK_INT >= 26 && icon instanceof android.graphics.drawable.AdaptiveIconDrawable) {
            android.graphics.drawable.AdaptiveIconDrawable aid = (android.graphics.drawable.AdaptiveIconDrawable) icon;
            Drawable fg = aid.getForeground(), bgd = aid.getBackground();
            if (fg != null && bgd != null) {
                Bitmap f = drawLayer(fg, 216);
                Bitmap trimmed = trim(f);
                if (trimmed != null && coverage(trimmed) > 0.06f) {
                    symbol = trimmed;
                    base = average(drawLayer(bgd, 24));
                    symbolScale = 0.64f;
                }
                if (trimmed != f) f.recycle();
            }
        }
        if (symbol == null && square != null && !square.isRecycled()) {
            Bitmap small = Bitmap.createScaledBitmap(square, 216, 216, true);
            int[] lifted = new int[1];
            Bitmap logo = liftBackground(small, lifted);
            if (logo != null) {
                symbol = trim(logo);
                if (symbol != logo) logo.recycle();
                base = lifted[0];
                symbolScale = 0.62f;
            } else {
                // No plain background to lift (gradients, photos, dark art: SonyLIV, ZEE5...):
                // the whole icon becomes the glass face, so there's no band of mismatched colour.
                base = edgeAverage(small);
                symbol = Bitmap.createBitmap(small);
                symbolScale = FILL;
            }
            if (small != square) small.recycle();
        }
        if (symbol == null) base = 0xFF5A6276;
        Bitmap out = compose(symbol, palette(base), symbolScale, size, style, depth, clarity, false);
        if (symbol != null) symbol.recycle();
        return out;
    }

    /** Settings glass (grey), as designed. */
    public static final int[] SETTINGS = {0xFFC9D0DE, 0xFF7D879E, 0xFF353C50};
    /** All apps glass (blue-grey), as designed. */
    public static final int[] ALL_APPS = {0xFFC9D3E8, 0xFF7884A3, 0xFF343C56};

    /** A glass icon from a white glyph (Settings, All apps) in fixed light, mid and deep colours. */
    public static Bitmap forGlyph(Bitmap whiteGlyph, int[] palette, int size, String style, int depth, int clarity) {
        return compose(whiteGlyph, palette, 0.6f, size, style, depth, clarity, true);
    }

    // ------------------------------------------------------------------ drawing

    /** symbolScale value meaning "the picture fills the whole glass face". */
    private static final float FILL = 1f;

    private static Bitmap compose(Bitmap symbol, int[] pal, float symbolScale, int s,
                                  String style, int depth, int clarity, boolean whiteSymbol) {
        int light = pal[0], mid = pal[1], deep = pal[2];
        float gd = Math.max(0f, Math.min(100, depth)) / 100f * 1.6f;
        float gop = 1f - Math.max(0, Math.min(100, clarity)) / 100f * 0.65f;
        boolean tile = "tile".equals(style);

        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

        // The art square: the whole icon, or the inner part of a frosted tile.
        RectF art = new RectF(0, 0, s, s);
        if (tile) {
            float tr = s * 0.27f;
            RectF t = new RectF(0, 0, s, s);
            p.setShader(new LinearGradient(0, 0, s * 0.35f, s, 0xEBFFFFFF, 0xC8E4EAF6, Shader.TileMode.CLAMP));
            c.drawRoundRect(t, tr, tr, p);
            p.setShader(new LinearGradient(0, 0, 0, s * 0.43f, 0xB3FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(s * 0.06f, s * 0.03f, s * 0.94f, s * 0.43f), tr * 0.9f, tr * 0.9f, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, s / 132f));
            p.setColor(0xCCFFFFFF);
            c.drawRoundRect(new RectF(0.5f, 0.5f, s - 0.5f, s - 0.5f), tr, tr, p);
            p.setStyle(Paint.Style.FILL);
            art.set(s * 21f / 132f, s * 18f / 132f, s * 111f / 132f, s * 108f / 132f);
        }
        float a = art.width();
        RectF box = new RectF(art.left + a * 0.04f, art.top + a * 0.04f, art.right - a * 0.04f, art.bottom - a * 0.04f);
        float r = a * 0.27f;

        // Extrusion: the glass's thickness, showing below it.
        p.setColor(deep);
        c.save();
        c.translate(0, gd * a * 0.045f);
        c.drawRoundRect(box, r, r, p);
        c.restore();

        boolean fill = symbolScale >= FILL && symbol != null && !symbol.isRecycled();
        if (fill) {
            // The icon itself is the glass face, edge to edge.
            Path face = new Path();
            face.addRoundRect(box, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(face);
            Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(symbol, null, box, fp);
            // glass sheen on the top half, and a soft darkening towards the bottom edge
            fp.setShader(new LinearGradient(0, box.top, 0, box.top + box.height() * 0.5f, 0x47FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(box, fp);
            fp.setShader(new LinearGradient(0, box.bottom - box.height() * 0.3f, 0, box.bottom, 0x00000000, 0x33000000, Shader.TileMode.CLAMP));
            c.drawRect(box, fp);
            c.restore();
            Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, a * 0.012f));
            rim.setShader(new LinearGradient(0, box.top, 0, box.bottom, 0x8CFFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
            float in = rim.getStrokeWidth() / 2f;
            c.drawRoundRect(new RectF(box.left + in, box.top + in, box.right - in, box.bottom - in), r - in, r - in, rim);
            return out;
        }

        // Body.
        p.setShader(new LinearGradient(0, box.top, 0, box.bottom, new int[]{light, mid, deep},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        p.setAlpha(Math.round(255 * gop));
        if (gop < 1f) {
            // See-through: what shows through is the extrusion colour, so clear that first.
            Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
            clear.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            c.drawRoundRect(box, r, r, clear);
        }
        c.drawRoundRect(box, r, r, p);
        p.setShader(null);
        p.setAlpha(255);

        // Inner rim and bottom glow, kept inside the glass.
        Path clip = new Path();
        clip.addRoundRect(box, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        Paint soft = new Paint(Paint.ANTI_ALIAS_FLAG);
        soft.setMaskFilter(new BlurMaskFilter(a * 0.032f, BlurMaskFilter.Blur.NORMAL));
        soft.setStyle(Paint.Style.STROKE);
        soft.setStrokeWidth(a * 0.14f);
        soft.setColor(deep);
        soft.setAlpha(Math.round(255 * Math.min(1f, gd * 0.55f)));
        c.drawRoundRect(box, r, r, soft);
        soft.setStyle(Paint.Style.FILL);
        soft.setColor(light);
        soft.setAlpha(Math.round(255 * Math.min(1f, 0.35f + gd * 0.4f)));
        float cx = art.centerX(), cy = art.top + a * 0.96f;
        c.drawOval(new RectF(cx - a * 0.40f, cy - a * 0.14f, cx + a * 0.40f, cy + a * 0.14f), soft);
        c.restore();

        // Logo, with a soft shadow in the glass colour.
        if (symbol != null && !symbol.isRecycled()) {
            float maxW = a * symbolScale, maxH = a * symbolScale;
            float k = Math.min(maxW / symbol.getWidth(), maxH / symbol.getHeight());
            float w = symbol.getWidth() * k, h = symbol.getHeight() * k;
            RectF dst = new RectF(art.centerX() - w / 2f, art.centerY() - h / 2f, art.centerX() + w / 2f, art.centerY() + h / 2f);
            Bitmap mask = symbol.extractAlpha();
            Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            sh.setColor(deep);
            sh.setAlpha(Math.round(255 * 0.45f));
            sh.setMaskFilter(new BlurMaskFilter(Math.max(1f, a * 0.04f), BlurMaskFilter.Blur.NORMAL));
            RectF sd = new RectF(dst);
            sd.offset(0, a * 0.045f);
            c.drawBitmap(mask, null, sd, sh);
            mask.recycle();
            Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            if (whiteSymbol) lp.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            c.drawBitmap(symbol, null, dst, lp);
        }
        return out;
    }

    /** Average colour around the icon's edge (what borders the glass). */
    static int edgeAverage(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        long r = 0, g = 0, bl = 0, n = 0;
        int m = Math.max(2, w / 14);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (x >= m && x < w - m && y >= m && y < h - m) continue;
                int c = b.getPixel(x, y);
                if ((c >>> 24) < 128) continue;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                bl += c & 0xFF;
                n++;
            }
        }
        if (n == 0) return average(b);
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (bl / n);
    }

    /** light, mid and deep glass colours from one base colour. */
    static int[] palette(int base) {
        float[] hsv = new float[3];
        Color.colorToHSV(base | 0xFF000000, hsv);
        float h = hsv[0], sat = hsv[1], v = hsv[2];
        if (sat < 0.14f && v > 0.78f) {  // white or light grey: silver glass
            return new int[]{0xFFFFFFFF, 0xFFDCE1EA, 0xFF8C95A8};
        }
        if (v < 0.16f) {  // black: smoky glass
            return new int[]{0xFF5E6372, 0xFF2B2E37, 0xFF0B0C10};
        }
        if (sat < 0.14f) {  // mid grey
            return new int[]{0xFFC9D0DE, 0xFF7D879E, 0xFF353C50};
        }
        float ms = Math.max(0.55f, Math.min(1f, sat)), mv = Math.max(0.62f, Math.min(0.96f, v));
        int mid = Color.HSVToColor(new float[]{h, ms, mv});
        int light = Color.HSVToColor(new float[]{h, ms * 0.58f, Math.min(1f, mv + 0.2f)});
        int deep = Color.HSVToColor(new float[]{h, Math.min(1f, ms * 1.05f), mv * 0.52f});
        return new int[]{light, mid, deep};
    }

    // ------------------------------------------------------------------ logo extraction

    private static Bitmap drawLayer(Drawable d, int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, s, s);
        d.draw(c);
        d.setBounds(old);
        return b;
    }

    /** Average opaque colour. */
    static int average(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        long r = 0, g = 0, bl = 0, n = 0;
        for (int i = 0; i < px.length; i += 3) {
            int c = px[i];
            if ((c >>> 24) < 128) continue;
            r += (c >> 16) & 0xFF;
            g += (c >> 8) & 0xFF;
            bl += c & 0xFF;
            n++;
        }
        if (n == 0) return 0xFF5A6276;
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (bl / n);
    }

    /** Share of pixels that are visible. */
    private static float coverage(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int n = 0;
        for (int c : px) if ((c >>> 24) > 64) n++;
        return n / (float) px.length;
    }

    /** Crops away transparent margins; returns the input when nothing to crop, null when empty. */
    private static Bitmap trim(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 24) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < x0 || y1 < y0) return null;
        if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) return b;
        return Bitmap.createBitmap(b, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    /**
     * When the icon has a plain background (one colour all around its edge), removes that
     * background where it touches the edge, leaving the logo. Shapes inside the logo in the same
     * colour (YouTube's white triangle) stay because they don't touch the edge.
     * Returns null when the background isn't plain or the logo would be too small or too big.
     */
    private static Bitmap liftBackground(Bitmap src, int[] bgOut) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        // Most common edge colour (quantised).
        java.util.HashMap<Integer, Integer> counts = new java.util.HashMap<>();
        int edgeN = 0;
        for (int i = 0; i < w; i++) {
            edgeN += countEdge(counts, px[i]) + countEdge(counts, px[(h - 1) * w + i]);
        }
        for (int j = 1; j < h - 1; j++) {
            edgeN += countEdge(counts, px[j * w]) + countEdge(counts, px[j * w + w - 1]);
        }
        int bestKey = 0, best = 0;
        for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                bestKey = e.getKey();
            }
        }
        if (edgeN == 0 || best < edgeN * 0.7f) return null;
        int bg = averageNear(px, w, h, bestKey);

        // Flood fill from every edge pixel close to the background colour.
        boolean[] gone = new boolean[w * h];
        int[] stack = new int[w * h];
        int sp = 0;
        for (int i = 0; i < w; i++) {
            sp = seed(px, gone, stack, sp, i, bg);
            sp = seed(px, gone, stack, sp, (h - 1) * w + i, bg);
        }
        for (int j = 0; j < h; j++) {
            sp = seed(px, gone, stack, sp, j * w, bg);
            sp = seed(px, gone, stack, sp, j * w + w - 1, bg);
        }
        while (sp > 0) {
            int i = stack[--sp];
            int x = i % w, y = i / w;
            if (x > 0) sp = seed(px, gone, stack, sp, i - 1, bg);
            if (x < w - 1) sp = seed(px, gone, stack, sp, i + 1, bg);
            if (y > 0) sp = seed(px, gone, stack, sp, i - w, bg);
            if (y < h - 1) sp = seed(px, gone, stack, sp, i + w, bg);
        }
        int kept = 0;
        int[] out = new int[w * h];
        for (int i = 0; i < px.length; i++) {
            if (gone[i]) continue;
            int c = px[i];
            // Soften the cut edge: pixels next to removed background fade by how close they are to it.
            int x = i % w, y = i / w;
            boolean edge = (x > 0 && gone[i - 1]) || (x < w - 1 && gone[i + 1]) || (y > 0 && gone[i - w]) || (y < h - 1 && gone[i + w]);
            if (edge) {
                float d = Math.min(1f, dist(c, bg) / 120f);
                c = (Math.round((c >>> 24) * d) << 24) | (c & 0xFFFFFF);
            }
            out[i] = c;
            if ((c >>> 24) > 64) kept++;
        }
        float share = kept / (float) (w * h);
        if (share < 0.04f || share > 0.8f) return null;
        bgOut[0] = bg;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.setPixels(out, 0, w, 0, 0, w, h);
        return b;
    }

    private static int countEdge(java.util.HashMap<Integer, Integer> counts, int c) {
        if ((c >>> 24) < 200) return 1;  // transparent edges count against a plain background
        int k = (((c >> 16) & 0xFF) >> 4 << 8) | (((c >> 8) & 0xFF) >> 4 << 4) | ((c & 0xFF) >> 4);
        Integer v = counts.get(k);
        counts.put(k, v == null ? 1 : v + 1);
        return 1;
    }

    private static int averageNear(int[] px, int w, int h, int key) {
        long r = 0, g = 0, b = 0, n = 0;
        for (int i = 0; i < w; i++) {
            for (int idx : new int[]{i, (h - 1) * w + i}) {
                int c = px[idx];
                int k = (((c >> 16) & 0xFF) >> 4 << 8) | (((c >> 8) & 0xFF) >> 4 << 4) | ((c & 0xFF) >> 4);
                if (k != key || (c >>> 24) < 200) continue;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        if (n == 0) {
            return 0xFF000000 | ((key >> 8 & 0xF) * 17 << 16) | ((key >> 4 & 0xF) * 17 << 8) | ((key & 0xF) * 17);
        }
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    private static int seed(int[] px, boolean[] gone, int[] stack, int sp, int i, int bg) {
        if (gone[i]) return sp;
        int c = px[i];
        if ((c >>> 24) < 40 || dist(c, bg) < 48f) {
            gone[i] = true;
            stack[sp++] = i;
        }
        return sp;
    }

    private static float dist(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return (float) Math.sqrt(dr * dr * 0.3 + dg * dg * 0.59 + db * db * 0.11) * 1.4f;
    }

    private static Bitmap rounded(Bitmap src, float radius) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new android.graphics.BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(0, 0, w, h), w * radius, h * radius, p);
        return out;
    }
}
