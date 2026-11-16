package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.view.View;
import android.view.WindowManager;

/**
 * Brightness for TVs that ignore Android's brightness setting: a black layer over everything
 * (drawn by the launcher's accessibility service, so it covers every app and video). 100 = off.
 */
final class Dimmer {
    private Dimmer() {}

    private static View view;

    static int level(Context c) {
        return c.getSharedPreferences("liquid", Context.MODE_PRIVATE).getInt("brightness", 100);
    }

    static boolean available() {
        return PowerService.instance != null;
    }

    static void set(Context c, int v) {
        v = Math.max(10, Math.min(100, v));
        c.getSharedPreferences("liquid", Context.MODE_PRIVATE).edit().putInt("brightness", v).apply();
        apply(c);
    }

    /** Main thread. Also called when the accessibility service (re)connects. */
    static void apply(Context c) {
        PowerService s = PowerService.instance;
        if (s == null) return;
        int v = level(c);
        WindowManager wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;
        try {
            if (v >= 100) {
                if (view != null) {
                    wm.removeView(view);
                    view = null;
                }
                return;
            }
            int alpha = Math.round((100 - v) / 100f * 0.85f * 255f);
            if (view == null) {
                view = new View(s);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                view.setBackgroundColor(alpha << 24);
                wm.addView(view, lp);
            } else {
                view.setBackgroundColor(alpha << 24);
            }
        } catch (Throwable t) {
            view = null;
        }
    }

    static void forget() {
        view = null;
    }
}
