package com.techgenius.tvoslauncher.liquid;

import android.content.Context;
import android.content.SharedPreferences;

import com.techgenius.tvoslauncher.Scenes;

/**
 * The Liquid Glass layout switch and its settings. Everything is stored in its own preferences
 * file ("liquid"), so using Liquid Glass never changes one of the original launcher's settings.
 *
 * Liquid Glass runs on top of the Night Scenes home: turning it on remembers the layout in use
 * (Night Scenes or Classic) and switches to Night Scenes; turning it off restores that layout.
 */
public final class Liquid {
    private Liquid() {}

    private static final String FILE = "liquid";
    private static final String K_ON = "on", K_PREV = "prev_look", K_INIT = "initialized";

    /** Parts of the interface that each have their own glass values in Advanced settings. */
    public static final String DOCK = "dock", WIDGETS = "widgets", PANEL = "panel", BUTTONS = "buttons", SETTINGS = "settings";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** First run of a build that has Liquid Glass: it starts on (decision 9). */
    public static void ensureDefault(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean(K_INIT, false)) return;
        p.edit().putBoolean(K_INIT, true).apply();
        setOn(c, true);
    }

    public static boolean isOn(Context c) {
        return c != null && prefs(c).getBoolean(K_ON, false) && Scenes.isScenesLook(c);
    }

    /** Turns Liquid Glass on or off; off returns to the layout used before it was turned on. */
    public static void setOn(Context c, boolean on) {
        SharedPreferences p = prefs(c);
        if (on) {
            if (!isOn(c)) p.edit().putString(K_PREV, Scenes.getLook(c)).apply();
            Scenes.setLook(c, Scenes.LOOK_SCENES);
            p.edit().putBoolean(K_ON, true).apply();
        } else {
            boolean was = isOn(c);
            p.edit().putBoolean(K_ON, false).apply();
            if (was) Scenes.setLook(c, p.getString(K_PREV, Scenes.LOOK_SCENES));
        }
    }

    /** Another layout was chosen directly (Night Scenes or Classic): Liquid Glass just turns off. */
    public static void leave(Context c) {
        prefs(c).edit().putBoolean(K_ON, false).apply();
    }

    // ------------------------------------------------------------------ glass look

    /** Glass values for one part of the interface, each 0..100. */
    public static final class Look {
        public int trans, blur, refr, tint, rim, bg;

        Look(int trans, int blur, int refr, int tint, int rim, int bg) {
            this.trans = trans;
            this.blur = blur;
            this.refr = refr;
            this.tint = tint;
            this.rim = rim;
            this.bg = bg;
        }
    }

    /** Preset values: Clear, Frosted, Tinted, Dark glass (same numbers as the prototype). */
    static int[] preset(String id) {
        switch (id == null ? "clear" : id) {
            case "frosted": return new int[]{70, 60, 40, 45, 80};
            case "tinted": return new int[]{75, 50, 40, 30, 70};
            case "dark": return new int[]{50, 60, 30, 20, 50};
            default: return new int[]{85, 20, 30, 10, 90};
        }
    }

    public static String preset(Context c) {
        return prefs(c).getString("preset", "clear");
    }

    public static boolean isAdvanced(Context c) {
        return "advanced".equals(prefs(c).getString("mode", "basic"));
    }

    /**
     * The glass for a part. Basic mode: one set of values for everything, and the dock's own
     * background amount (0 by default: no dock plate). Advanced mode: each part has its own.
     */
    public static Look look(Context c, String part) {
        SharedPreferences p = prefs(c);
        int[] pv = preset(preset(c));
        String pre = isAdvanced(c) ? "adv_" + part + "_" : "basic_";
        int defBg = DOCK.equals(part) ? 0 : 100;
        int bg = isAdvanced(c) ? p.getInt(pre + "bg", defBg)
                : DOCK.equals(part) ? p.getInt("basic_dock", 0) : 100;
        return new Look(p.getInt(pre + "trans", pv[0]), p.getInt(pre + "blur", pv[1]),
                p.getInt(pre + "refr", pv[2]), p.getInt(pre + "tint", pv[3]), p.getInt(pre + "rim", pv[4]), bg);
    }

    // ------------------------------------------------------------------ icons

    /** "bare": coloured glass only. "tile": the glass sits on a frosted white tile. */
    public static String iconStyle(Context c) {
        return prefs(c).getString("icon_style", "bare");
    }

    /** How thick the coloured glass looks, 0..100. */
    public static int iconDepth(Context c) {
        return prefs(c).getInt("icon_depth", 60);
    }

    /** How see-through the coloured glass is, 0..100. */
    public static int iconClarity(Context c) {
        return prefs(c).getInt("icon_clarity", 0);
    }

    /** Dock magnification: "off", "subtle" or "strong". */
    public static String magnify(Context c) {
        return prefs(c).getString("magnify", "strong");
    }

    /** Tilt and shine on the focused dock icon. */
    public static boolean tilt(Context c) {
        return prefs(c).getBoolean("tilt", true);
    }

    /** Changes when any icon setting changes, so cached icons can be rebuilt. */
    public static String iconSignature(Context c) {
        return iconStyle(c) + "/" + iconDepth(c) + "/" + iconClarity(c);
    }
}
