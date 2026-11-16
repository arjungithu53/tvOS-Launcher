package com.techgenius.tvoslauncher.liquid;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * The one sharp, small copy of the current wallpaper that every glass surface samples
 * (the blurred copy is the launcher's existing one). 384 × 216 pixels: about 0.3 MB.
 */
public final class Backdrop {
    private Backdrop() {}

    public static final int W = 384, H = 216;
    private static volatile Bitmap sharp;

    public static Bitmap sharp() {
        return sharp;
    }

    /** Builds the copy from a new wallpaper. Call off the main thread; publish with {@link #set}. */
    public static Bitmap make(Bitmap wallpaper) {
        if (wallpaper == null || wallpaper.isRecycled()) return null;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(wallpaper, null, new Rect(0, 0, W, H), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Main thread. The previous copy is left for the garbage collector (panels may still draw it). */
    public static void set(Bitmap b) {
        if (b != null) sharp = b;
    }
}
