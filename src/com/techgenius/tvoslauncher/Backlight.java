package com.techgenius.tvoslauncher;

import android.app.PendingIntent;
import android.app.slice.Slice;
import android.app.slice.SliceItem;
import android.app.slice.SliceManager;
import android.app.slice.SliceSpec;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The TV's real backlight, through the picture controls the TV's own Realtek settings app
 * publishes as slices (the same channel Google TV's Settings uses to show them). Android lets
 * the Home app read every app's slices, which is what makes this possible.
 *
 * Every call does inter-process work: call off the main thread.
 * Everything found is written to logcat under "tvOSSlice", to check on a new TV.
 */
final class Backlight {
    private Backlight() {}

    private static final String TAG = "tvOSSlice";
    static final String[] AUTHORITIES = {"com.realtek.tv.settings", "com.realtek.menuslices"};
    static final String PICTURE_PKG = "com.realtek.slices";
    static final String PICTURE_ACTIVITY = "com.realtek.tv.settings.activity.PictureSetupActivity";

    /** What the slider talks to: the row's value range and the action that changes it. */
    static final class Control {
        Uri slice;
        int value, min, max = 100;
        PendingIntent action;
    }

    private static Uri knownUri;

    private static Set<SliceSpec> specs() {
        Set<SliceSpec> s = new HashSet<>();
        s.add(new SliceSpec("androidx.slice.LIST", 1));
        s.add(new SliceSpec("androidx.app.slice.LIST", 1));
        s.add(new SliceSpec("android.slice.LIST", 1));
        return s;
    }

    /** Finds the backlight control, or null when the TV doesn't offer it. */
    static Control find(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 28) return null;
        SliceManager sm = c.getSystemService(SliceManager.class);
        if (sm == null) return null;
        List<Uri> candidates = new ArrayList<>();
        if (knownUri != null) candidates.add(knownUri);
        for (String auth : AUTHORITIES) {
            try {
                Collection<Uri> d = sm.getSliceDescendants(Uri.parse("content://" + auth + "/"));
                Log.i(TAG, auth + ": " + d);
                // picture pages first
                for (Uri u : d) if (u.toString().toLowerCase(Locale.ROOT).contains("pic")) candidates.add(u);
                for (Uri u : d) if (!candidates.contains(u)) candidates.add(u);
            } catch (Throwable t) {
                Log.i(TAG, auth + " descendants failed: " + t);
            }
        }
        for (Uri u : candidates) {
            try {
                Slice s = sm.bindSlice(u, specs());
                if (s == null) continue;
                Control ctl = search(s);
                if (ctl != null) {
                    ctl.slice = u;
                    knownUri = u;
                    Log.i(TAG, "backlight in " + u + " value " + ctl.value + " of " + ctl.min + ".." + ctl.max);
                    return ctl;
                }
            } catch (Throwable t) {
                Log.i(TAG, "bind " + u + " failed: " + t);
            }
        }
        Log.i(TAG, "no backlight control found in " + candidates.size() + " slices");
        return null;
    }

    /** Current backlight 0..100, or -1. */
    static int read(Context c) {
        Control ctl = find(c);
        if (ctl == null) return -1;
        int span = Math.max(1, ctl.max - ctl.min);
        return Math.round((ctl.value - ctl.min) * 100f / span);
    }

    /** Sets the backlight (0..100). Returns false when it couldn't. */
    static boolean set(Context c, int percent) {
        Control ctl = find(c);
        if (ctl == null || ctl.action == null) return false;
        int v = ctl.min + Math.round(Math.max(0, Math.min(100, percent)) / 100f * (ctl.max - ctl.min));
        Intent fill = new Intent();
        // The value under every name TV and Android slice sliders use for it.
        fill.putExtra(Slice.EXTRA_RANGE_VALUE, v);
        fill.putExtra("android.app.slice.extra.RANGE_VALUE", v);
        fill.putExtra("SEEKBAR_VALUE", v);
        fill.putExtra("EXTRA_SEEKBAR_VALUE", v);
        try {
            ctl.action.send(c, 0, fill);
            Log.i(TAG, "set backlight " + v);
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "set failed: " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ slice tree

    /** A row whose text says Backlight, with a number range and an action. */
    private static Control search(Slice s) {
        if (mentionsBacklight(s, 0)) {
            Control ctl = new Control();
            boolean got = collect(s, ctl, 0);
            if (got && ctl.action != null) return ctl;
        }
        for (SliceItem it : s.getItems()) {
            Slice sub = it.getFormat().equals(SliceItem.FORMAT_SLICE) ? it.getSlice()
                    : it.getFormat().equals(SliceItem.FORMAT_ACTION) ? it.getSlice() : null;
            if (sub == null) continue;
            Control ctl = search(sub);
            if (ctl != null) {
                if (ctl.action == null && it.getFormat().equals(SliceItem.FORMAT_ACTION)) ctl.action = it.getAction();
                if (ctl.action != null) return ctl;
            }
        }
        return null;
    }

    private static boolean mentionsBacklight(Slice s, int depth) {
        if (depth > 2) return false;
        for (SliceItem it : s.getItems()) {
            if (it.getFormat().equals(SliceItem.FORMAT_TEXT) && it.getText() != null
                    && it.getText().toString().toLowerCase(Locale.ROOT).contains("backlight")) return true;
            if (it.getFormat().equals(SliceItem.FORMAT_SLICE) && it.getSlice() != null && mentionsBacklight(it.getSlice(), depth + 1)) return true;
        }
        return false;
    }

    /** Reads value / min / max ints and the first action in a row. */
    private static boolean collect(Slice s, Control ctl, int depth) {
        boolean got = false;
        for (SliceItem it : s.getItems()) {
            String f = it.getFormat(), sub = it.getSubType() == null ? "" : it.getSubType().toLowerCase(Locale.ROOT);
            if (SliceItem.FORMAT_INT.equals(f)) {
                Log.i(TAG, "  int " + sub + " = " + it.getInt());
                if (sub.contains("max")) ctl.max = it.getInt();
                else if (sub.contains("min")) ctl.min = it.getInt();
                else if (sub.contains("value") || sub.contains("progress")) {
                    ctl.value = it.getInt();
                    got = true;
                }
            } else if (SliceItem.FORMAT_ACTION.equals(f)) {
                if (ctl.action == null) ctl.action = it.getAction();
                if (it.getSlice() != null && depth < 3) got |= collect(it.getSlice(), ctl, depth + 1);
            } else if (SliceItem.FORMAT_SLICE.equals(f) && it.getSlice() != null && depth < 3) {
                got |= collect(it.getSlice(), ctl, depth + 1);
            }
        }
        return got;
    }

    /** Opens the TV's own picture screen (with its Backlight slider). */
    static void openPicture(Context c) {
        try {
            c.startActivity(new Intent().setClassName(PICTURE_PKG, PICTURE_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            try {
                c.startActivity(new Intent("android.settings.DISPLAY_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
    }

    /** Opens the picture screen with the Backlight row already focused (found through the accessibility service). */
    static void openBacklight(Context c) {
        openPicture(c);
        final PowerService svc = PowerService.instance;
        if (svc == null) return;
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] tries = {0};
        h.postDelayed(new Runnable() {
            @Override public void run() {
                int r = focusRow(svc, "backlight");
                Log.i(TAG, "focus backlight try " + tries[0] + ": " + r);
                if (r == 1 || ++tries[0] > 24) return;
                h.postDelayed(this, 250);
            }
        }, 400);
    }

    /** 1 focused, 0 not there yet (scrolled if it could), -1 no window. */
    private static int focusRow(android.accessibilityservice.AccessibilityService svc, String word) {
        android.view.accessibility.AccessibilityNodeInfo root;
        try {
            root = svc.getRootInActiveWindow();
        } catch (Throwable t) {
            return -1;
        }
        if (root == null || root.getPackageName() == null || !PICTURE_PKG.contentEquals(root.getPackageName())) return -1;
        List<android.view.accessibility.AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(word);
        for (android.view.accessibility.AccessibilityNodeInfo n : hits) {
            CharSequence t = n.getText();
            if (t == null || !t.toString().toLowerCase(Locale.ROOT).startsWith(word)) continue;
            android.view.accessibility.AccessibilityNodeInfo x = n;
            while (x != null && !x.isFocusable()) x = x.getParent();
            if (x == null) continue;
            if (x.isFocused() || x.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)) return 1;
        }
        // Not laid out yet: page the list down.
        android.view.accessibility.AccessibilityNodeInfo list = scrollable(root);
        if (list != null) list.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        return 0;
    }

    private static android.view.accessibility.AccessibilityNodeInfo scrollable(android.view.accessibility.AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isScrollable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo r = scrollable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }
}
