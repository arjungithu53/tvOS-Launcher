package com.techgenius.tvoslauncher.cursor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Remote-controlled mouse cursor.
 *
 *   Show / hide button (default: hold BACK) -> cursor on / off
 *   Arrows   -> move. One press = one tap step, holding glides and speeds up.
 *   OK       -> click.   Hold OK -> long press.
 *   Reaching the edge of a scrollable area (e.g. a web page) and pushing further scrolls it.
 *   If that area can't scroll any further, the cursor simply carries on moving
 *   (for example up out of the page into the browser's address bar).
 *
 * Everything works through accessibility actions, because many TVs ignore simulated touches.
 */
public final class TvCursor {

    /** The launcher's accessibility service, which hosts the cursor. */
    private final AccessibilityService svc;

    public TvCursor(AccessibilityService svc) {
        this.svc = svc;
    }

    static final String KEY_ON = "on";                 // the cursor feature as a whole (Home settings)

    public static boolean isOn(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, true);
    }

    /** "Hold BACK" or "Press X": how to show the cursor. */
    public static String toggleHintFor(Context c) {
        return toggleHint(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TOGGLE, KeyEvent.KEYCODE_BACK));
    }

    public static void setOn(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
    }


    // ------------------------------------------------------------ settings (shared with MainActivity)
    static final String PREFS = "tvcursor";
    static final String KEY_STYLE = "style";
    static final String KEY_TAP = "tapStep";          // px per single press, at 1920 px screen width
    static final String KEY_SPEED = "holdSpeed";      // top glide speed, px per frame at 1920 px width
    static final String KEY_ACCEL = "accelMs";        // ms to reach top speed
    static final String KEY_TOGGLE = "toggleKey";     // key code; KEYCODE_BACK means "hold BACK"
    static final String KEY_LEARN = "learnUntil";     // uptime ms; until then the next button press becomes the toggle
    static final String KEY_FOCUS_URL = "focusUrlBar"; // select the browser address bar when the cursor is hidden

    static final int DEF_TAP = 12;
    static final int DEF_SPEED = 14;
    static final int DEF_ACCEL = 700;

    // ------------------------------------------------------------ timing
    private static final long HOLD_MS = 600;            // hold BACK / hold OK
    private static final long GLIDE_DELAY_MS = 180;     // after a press, wait this long before gliding
    private static final long FRAME_MS = 16;
    private static final long MAX_GLIDE_MS = 20000;     // safety stop if a button release is lost
    private static final long RELEASE_GRACE_MS = 150;   // see arrow()
    private static final long TARGET_REFRESH_MS = 250;  // how often to re-check what can scroll under the cursor
    private static final long SMALL_SCROLL_GAP_MS = 220;
    private static final long PAGE_SCROLL_GAP_MS = 450;
    private static final long PASS_BACK_MS = 400;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler bg;

    private SharedPreferences prefs;
    private WindowManager wm;
    private CursorView cursor;
    private boolean visible;
    private float x, y;
    private int w = 1920, h = 1080;

    // keys
    private boolean backHeld;
    private boolean okHeld;
    private long passBackUntil;
    private int swallowUp = -1;

    // movement
    private int moveKey = -1;
    private int mdx, mdy;
    private long moveStart;
    private float tapStep, holdSpeed;
    private long accelMs;
    private boolean releasePending;   // some remotes send a held button as quick press/release pairs
    private boolean repeating;

    // edge scrolling (main thread)
    private AccessibilityNodeInfo scrollNode;
    private final Rect scrollRect = new Rect();
    private int scrollDx, scrollDy;
    private long scrollQueriedAt;     // 0 = must re-check
    private long lastScroll;
    private long scrollGap = SMALL_SCROLL_GAP_MS;
    private boolean scrollBusy;
    private boolean scrollPending;
    private int pendingDx, pendingDy;
    private long pendingAt;

    // An area that says it can scroll but didn't move when asked (main thread)
    private String blockedKey;
    private int blockedDx, blockedDy;
    private long blockedUntil;

    // edge scrolling (worker thread only)
    private String lastShowKey;
    private long lastShowAt;
    private String lastFpTarget, lastFp;
    private int lastFpDx, lastFpDy, stuckCount;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if (KEY_STYLE.equals(key) && cursor != null) {
                        cursor.setStyle(p.getInt(KEY_STYLE, CursorArt.CHROME));
                    }
                }
            };

    private final Runnable backHold = new Runnable() {
        @Override public void run() {
            backHeld = true;
            setVisible(!visible);
        }
    };

    private final Runnable okHold = new Runnable() {
        @Override public void run() {
            okHeld = true;
            if (visible) longPress((int) x, (int) y);
        }
    };

    private final Runnable release = new Runnable() {
        @Override public void run() { stopMove(); }
    };

    private final Runnable glide = new Runnable() {
        @Override public void run() {
            if (moveKey < 0 || !visible) return;
            if (releasePending && !repeating) {           // probably a single tap; wait for the release timer
                ui.postDelayed(this, FRAME_MS);
                return;
            }
            long held = SystemClock.uptimeMillis() - moveStart;
            if (held > MAX_GLIDE_MS) { stopMove(); return; }
            float t = accelMs <= 0 ? 1f : (held - GLIDE_DELAY_MS) / (float) accelMs;
            if (t < 0) t = 0;
            if (t > 1) t = 1;
            float start = Math.min(holdSpeed, Math.max(1f, holdSpeed * 0.2f));
            float v = start + (holdSpeed - start) * t * t;
            step(mdx, mdy, v * w / 1920f);
            ui.postDelayed(this, FRAME_MS);
        }
    };

    // ================================================================== lifecycle

    public void start() {
        prefs = svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        worker = new HandlerThread("tvcursor-scroll");
        worker.start();
        bg = new Handler(worker.getLooper());

        measure();
        x = w / 2f;
        y = h / 2f;

        cursor = new CursorView(svc);
        cursor.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        wm.addView(cursor, lp);
        cursor.setStyle(prefs.getInt(KEY_STYLE, CursorArt.CHROME));
        cursor.moveTo(x, y);

    }

    public void stop() {
        ui.removeCallbacksAndMessages(null);
        if (worker != null) worker.quitSafely();
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        if (cursor != null && wm != null) {
            try { wm.removeView(cursor); } catch (RuntimeException ignored) { }
        }
    }

    // ================================================================== helpers shared with the UI

    static String keyName(int code) {
        return KeyEvent.keyCodeToString(code).replace("KEYCODE_", "").replace('_', ' ');
    }

    static String toggleHint(int code) {
        return code == KeyEvent.KEYCODE_BACK ? "Hold BACK" : "Press " + keyName(code);
    }

    /** Buttons that can't be the show/hide button, because the cursor itself needs them. */
    static boolean allowedToggle(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_UNKNOWN:
                return false;
            default:
                return true;
        }
    }

    private void toast(String s, int len) {
        Toast.makeText(svc, s, len).show();
    }

    @SuppressWarnings("deprecation")
    private void measure() {
        int mw, mh;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getMaximumWindowMetrics().getBounds();
            mw = b.width();
            mh = b.height();
        } else {
            android.graphics.Point p = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(p);
            mw = p.x;
            mh = p.y;
        }
        if (mw > 0 && mh > 0) {
            w = mw;
            h = mh;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ================================================================== show / hide

    private void setVisible(boolean show) {
        visible = show;
        stopMove();
        ui.removeCallbacks(okHold);
        scrollNode = null;
        scrollQueriedAt = 0;
        if (show) {
            measure();
            cursor.setStyle(prefs.getInt(KEY_STYLE, CursorArt.CHROME));
            x = clamp(x, 0, w - 1);
            y = clamp(y, 0, h - 1);
            cursor.moveTo(x, y);
        }
        cursor.setVisibility(show ? View.VISIBLE : View.GONE);
        toast(show ? "Cursor on" : "Cursor off", Toast.LENGTH_SHORT);
        if (!show && prefs.getBoolean(KEY_FOCUS_URL, true)) {
            try { focusAddressBar(); } catch (RuntimeException ignored) { }
        }
    }

    /** Put the remote's normal focus on the browser's address bar, if the app on screen has one. */
    private void focusAddressBar() {
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) return;
        CharSequence pkg = root.getPackageName();
        if (pkg == null) return;
        AccessibilityNodeInfo bar = null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(pkg + ":id/url_bar");
        for (AccessibilityNodeInfo n : found) {
            if (n.isVisibleToUser()) { bar = n; break; }
        }
        if (bar == null) bar = findAddressBarByName(root);
        if (bar == null) return;
        if (!bar.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            bar.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        }
    }

    /** Fallback for browsers that don't call it url_bar. Only looks at the app's own views, not page content. */
    private AccessibilityNodeInfo findAddressBarByName(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);
        for (int i = 0; i < queue.size() && i < 400; i++) {
            AccessibilityNodeInfo n = queue.get(i);
            if (n == null || !n.isVisibleToUser()) continue;
            String id = n.getViewIdResourceName();
            if (id != null) {
                String s = id.toLowerCase();
                if (s.endsWith("/url_bar") || s.endsWith("/omnibox") || s.endsWith("/url_field")
                        || s.endsWith("/address_bar") || s.endsWith("/location_bar_edit_text")) {
                    return n;
                }
            }
            if (isWebContainer(n)) continue;   // don't walk into web pages
            for (int c = 0; c < n.getChildCount(); c++) queue.add(n.getChild(c));
        }
        return null;
    }

    // ================================================================== keys

    /** True when the cursor used the key (the launcher's own key handling is skipped then). */
    public boolean onKeyEvent(KeyEvent e) {
        if (prefs == null || cursor == null) return false;
        if (!prefs.getBoolean(KEY_ON, true)) {
            if (visible) setVisible(false);
            return false;
        }
        int code = e.getKeyCode();
        int action = e.getAction();
        boolean down = action == KeyEvent.ACTION_DOWN;
        boolean up = action == KeyEvent.ACTION_UP;
        long now = SystemClock.uptimeMillis();

        // The BACK we send ourselves after a short BACK press: let it through untouched.
        if (code == KeyEvent.KEYCODE_BACK && now < passBackUntil) return false;

        // Finish swallowing a button that was just chosen as the toggle.
        if (code == swallowUp) {
            if (up) swallowUp = -1;
            return true;
        }

        // "Change button" in the app: the next press becomes the show/hide button.
        if (down && e.getRepeatCount() == 0 && now < prefs.getLong(KEY_LEARN, 0)) {
            if (!allowedToggle(code)) {
                toast(keyName(code) + " can't be used. Press a different button.", Toast.LENGTH_SHORT);
                return false;
            }
            prefs.edit().putInt(KEY_TOGGLE, code).putLong(KEY_LEARN, 0).apply();
            toast("Show / hide button: " + toggleHint(code), Toast.LENGTH_LONG);
            swallowUp = code;
            return true;
        }

        int toggle = prefs.getInt(KEY_TOGGLE, KeyEvent.KEYCODE_BACK);
        if (code == toggle) {
            if (toggle == KeyEvent.KEYCODE_BACK) {
                if (down && e.getRepeatCount() == 0) {
                    backHeld = false;
                    ui.removeCallbacks(backHold);
                    ui.postDelayed(backHold, HOLD_MS);
                } else if (up) {
                    ui.removeCallbacks(backHold);
                    if (!backHeld) {
                        passBackUntil = now + PASS_BACK_MS;
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    }
                    backHeld = false;
                }
            } else if (down && e.getRepeatCount() == 0) {
                setVisible(!visible);
            }
            return true;
        }

        if (!visible) return false;

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:    return arrow(e, code, 0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN:  return arrow(e, code, 0, 1);
            case KeyEvent.KEYCODE_DPAD_LEFT:  return arrow(e, code, -1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return arrow(e, code, 1, 0);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                stopMove();
                if (down && e.getRepeatCount() == 0) {
                    okHeld = false;
                    ui.removeCallbacks(okHold);
                    ui.postDelayed(okHold, HOLD_MS);
                } else if (up) {
                    ui.removeCallbacks(okHold);
                    if (!okHeld) click((int) x, (int) y);
                    okHeld = false;
                }
                return true;
            default:
                if (down) stopMove();
                return false;
        }
    }

    // ================================================================== movement

    /**
     * Arrows. We run our own repeat loop, because many remotes never report a held button.
     * Some report holding as rapid press/release pairs; a release is only trusted if no new
     * press of the same arrow follows within RELEASE_GRACE_MS.
     */
    private boolean arrow(KeyEvent e, int code, int dx, int dy) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() > 0) return true;
            if (moveKey == code && releasePending) {
                ui.removeCallbacks(release);
                releasePending = false;
                repeating = true;
            } else if (moveKey != code) {
                startMove(code, dx, dy);
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP && moveKey == code) {
            long held = SystemClock.uptimeMillis() - moveStart;
            if (!repeating && held > GLIDE_DELAY_MS) {
                stopMove();
            } else {
                releasePending = true;
                ui.removeCallbacks(release);
                ui.postDelayed(release, RELEASE_GRACE_MS);
            }
        }
        return true;
    }

    private void startMove(int code, int dx, int dy) {
        ui.removeCallbacks(release);
        ui.removeCallbacks(glide);
        releasePending = false;
        repeating = false;
        tapStep = prefs.getInt(KEY_TAP, DEF_TAP);
        holdSpeed = prefs.getInt(KEY_SPEED, DEF_SPEED);
        accelMs = prefs.getInt(KEY_ACCEL, DEF_ACCEL);
        moveKey = code;
        mdx = dx;
        mdy = dy;
        moveStart = SystemClock.uptimeMillis();
        lastScroll = 0;           // a fresh press always scrolls straight away
        scrollQueriedAt = 0;      // and re-checks what is under the cursor
        step(dx, dy, tapStep * w / 1920f);
        ui.postDelayed(glide, GLIDE_DELAY_MS);
    }

    private void stopMove() {
        moveKey = -1;
        releasePending = false;
        repeating = false;
        ui.removeCallbacks(glide);
        ui.removeCallbacks(release);
    }

    /** Move by dist in direction (dx, dy), or scroll if the cursor is at the edge of something scrollable. */
    private void step(int dx, int dy, float dist) {
        float nx = clamp(x + dx * dist, 0, w - 1);
        float ny = clamp(y + dy * dist, 0, h - 1);

        AccessibilityNodeInfo target = scrollTarget(dx, dy);
        if (target != null) {
            CursorArt.Sprite sp = cursor.sprite();
            Rect r = scrollRect;
            // Stop short of the edge by the cursor's own size, so the whole cursor stays visible.
            boolean atEdge = false;
            if (dy > 0) {
                float limit = r.bottom - 1 - Math.min(sp.extB, r.height() / 4f);
                if (ny >= limit) { ny = y >= limit ? y : limit; atEdge = true; }
            } else if (dy < 0) {
                float limit = r.top + Math.min(sp.extT, r.height() / 4f);
                if (ny <= limit) { ny = y <= limit ? y : limit; atEdge = true; }
            } else if (dx > 0) {
                float limit = r.right - 1 - Math.min(sp.extR, r.width() / 4f);
                if (nx >= limit) { nx = x >= limit ? x : limit; atEdge = true; }
            } else if (dx < 0) {
                float limit = r.left + Math.min(sp.extL, r.width() / 4f);
                if (nx <= limit) { nx = x <= limit ? x : limit; atEdge = true; }
            }
            if (atEdge) requestScroll(target, dx, dy);
        }

        if (nx != x || ny != y) {
            x = nx;
            y = ny;
            cursor.moveTo(x, y);
        }
    }

    // ================================================================== edge scrolling

    /**
     * The nearest thing under the cursor that can still scroll in this direction, or null.
     * Cached for a short time, because looking it up on every frame would be slow.
     */
    private AccessibilityNodeInfo scrollTarget(int dx, int dy) {
        long now = SystemClock.uptimeMillis();
        boolean fresh = scrollQueriedAt != 0
                && now - scrollQueriedAt < TARGET_REFRESH_MS
                && dx == scrollDx && dy == scrollDy
                && (scrollNode == null || scrollRect.contains((int) x, (int) y));
        if (fresh) return scrollNode;

        scrollDx = dx;
        scrollDy = dy;
        scrollQueriedAt = now;
        scrollNode = null;
        try {
            AccessibilityNodeInfo root = topRootAt((int) x, (int) y);
            AccessibilityNodeInfo n = deepestAt(root, (int) x, (int) y, 0);
            for (int i = 0; n != null && i < 60; i++, n = n.getParent()) {
                if (canScroll(n, dx, dy) && !isBlocked(n, dx, dy, now)) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    if (!r.intersect(0, 0, w, h) || r.isEmpty()) continue;
                    scrollRect.set(r);
                    scrollNode = n;
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            scrollNode = null;
        }
        return scrollNode;
    }

    private static String nodeKey(AccessibilityNodeInfo n) {
        return n.getWindowId() + ":" + n.hashCode();
    }

    private boolean isBlocked(AccessibilityNodeInfo n, int dx, int dy, long now) {
        return blockedKey != null && now < blockedUntil && dx == blockedDx && dy == blockedDy
                && blockedKey.equals(nodeKey(n));
    }

    private static int dirAction(int dx, int dy) {
        if (dy > 0) return android.R.id.accessibilityActionScrollDown;
        if (dy < 0) return android.R.id.accessibilityActionScrollUp;
        if (dx > 0) return android.R.id.accessibilityActionScrollRight;
        return android.R.id.accessibilityActionScrollLeft;
    }

    private static boolean hasAction(AccessibilityNodeInfo n, int id) {
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
            if (a.getId() == id) return true;
        }
        return false;
    }

    /** Sliders and similar controls also offer "scroll" actions (they change the value). Never use those. */
    private static boolean isValueControl(AccessibilityNodeInfo n) {
        if (n.getRangeInfo() != null) return true;
        CharSequence c = n.getClassName();
        if (c == null) return false;
        String s = c.toString();
        return s.contains("SeekBar") || s.contains("ProgressBar") || s.contains("RatingBar")
                || s.contains("NumberPicker") || s.contains("Slider") || s.contains("Spinner");
    }

    private static boolean isHorizontalOnly(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        String s = c == null ? "" : c.toString();
        if (s.contains("Horizontal") || s.contains("ViewPager")) return true;
        boolean horiz = hasAction(n, android.R.id.accessibilityActionScrollLeft)
                || hasAction(n, android.R.id.accessibilityActionScrollRight);
        boolean vert = hasAction(n, android.R.id.accessibilityActionScrollUp)
                || hasAction(n, android.R.id.accessibilityActionScrollDown);
        return horiz && !vert;
    }

    /** Can this node scroll in this direction right now? */
    private static boolean canScroll(AccessibilityNodeInfo n, int dx, int dy) {
        if (n == null || isValueControl(n)) return false;
        if (hasAction(n, dirAction(dx, dy))) return true;
        if (dy == 0 || !n.isScrollable() || isHorizontalOnly(n)) return false;
        return hasAction(n, dy > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                   : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    /** Runs the scroll on a worker thread so the cursor never freezes while a big page is inspected. */
    private void requestScroll(final AccessibilityNodeInfo target, final int dx, final int dy) {
        if (scrollBusy) {
            scrollPending = true;
            pendingDx = dx;
            pendingDy = dy;
            pendingAt = SystemClock.uptimeMillis();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastScroll < scrollGap) return;
        startScrollJob(target, dx, dy);
    }

    private void startScrollJob(final AccessibilityNodeInfo target, final int dx, final int dy) {
        lastScroll = SystemClock.uptimeMillis();
        scrollBusy = true;
        final Rect area = new Rect(scrollRect);
        bg.post(new Runnable() {
            @Override public void run() {
                int result = SCROLL_PAGE;
                try {
                    result = doScroll(target, area, dx, dy);
                } catch (RuntimeException ignored) { }
                final int res = result;
                final String key = res == SCROLL_STUCK ? safeKey(target) : null;
                ui.post(new Runnable() {
                    @Override public void run() {
                        scrollBusy = false;
                        scrollGap = res == SCROLL_SMALL ? SMALL_SCROLL_GAP_MS : PAGE_SCROLL_GAP_MS;
                        scrollQueriedAt = 0;        // the page moved: check again what can scroll
                        if (key != null) {
                            // It says it can scroll but nothing moves: let the cursor carry on past the edge.
                            blockedKey = key;
                            blockedDx = dx;
                            blockedDy = dy;
                            blockedUntil = SystemClock.uptimeMillis() + 2000;
                        }
                        if (scrollPending) {
                            scrollPending = false;
                            // Only if you're still pushing that way (or just did).
                            boolean still = SystemClock.uptimeMillis() - pendingAt < 800
                                    && mdx == pendingDx && mdy == pendingDy;
                            if (visible && still) {
                                AccessibilityNodeInfo t = scrollTarget(pendingDx, pendingDy);
                                if (t != null) startScrollJob(t, pendingDx, pendingDy);
                            }
                        }
                    }
                });
            }
        });
    }

    private static String safeKey(AccessibilityNodeInfo n) {
        try { return nodeKey(n); } catch (RuntimeException e) { return null; }
    }

    private static final int SCROLL_PAGE = 0;
    private static final int SCROLL_SMALL = 1;
    private static final int SCROLL_STUCK = 2;

    /** What is in the middle of the area right now; if it never changes, the area isn't actually scrolling. */
    private static String centerFingerprint(AccessibilityNodeInfo target, Rect area) {
        AccessibilityNodeInfo n = deepestAt(target, area.centerX(), area.centerY(), 0);
        if (n == null) return null;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        return nodeKey(n) + "@" + b.toShortString();
    }

    /**
     * Worker thread. Tries a small scroll first (bring the next item just past the edge into view).
     * Falls back to the app's own scroll step, which in browsers is most of a screen.
     */
    private int doScroll(AccessibilityNodeInfo target, Rect area, int dx, int dy) {
        if (!target.refresh()) return SCROLL_PAGE;

        // Three pushes in a row with nothing moving: treat it as "can't scroll".
        String tk = nodeKey(target);
        String fp = centerFingerprint(target, area);
        if (fp != null && fp.equals(lastFp) && tk.equals(lastFpTarget) && dx == lastFpDx && dy == lastFpDy) {
            stuckCount++;
        } else {
            stuckCount = 0;
        }
        lastFp = fp;
        lastFpTarget = tk;
        lastFpDx = dx;
        lastFpDy = dy;
        if (stuckCount >= 2) {
            stuckCount = 0;
            lastFp = null;
            return SCROLL_STUCK;
        }

        if (dy != 0 && smallScroll(target, area, dy)) return SCROLL_SMALL;
        int dir = dirAction(dx, dy);
        if (hasAction(target, dir) && target.performAction(dir)) return SCROLL_PAGE;
        if (dy != 0 && !isHorizontalOnly(target)) {
            int fb = dy > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            if (hasAction(target, fb)) target.performAction(fb);
        }
        return SCROLL_PAGE;
    }

    private static final int MAX_NODES = 4000;

    private boolean smallScroll(AccessibilityNodeInfo target, Rect area, int dy) {
        List<AccessibilityNodeInfo> units = new ArrayList<>();
        List<Boolean> vis = new ArrayList<>();
        int[] budget = {MAX_NODES};
        collect(target, units, vis, 0, budget);
        if (budget[0] <= 0 || units.isEmpty()) return false;

        // Visible items, nearest to the edge we're pushing against first.
        final List<Integer> visIdx = new ArrayList<>();
        final List<Rect> visRect = new ArrayList<>();
        for (int i = 0; i < units.size(); i++) {
            if (!vis.get(i)) continue;
            Rect b = new Rect();
            units.get(i).getBoundsInScreen(b);
            if (b.height() <= 0 || !Rect.intersects(b, area)) continue;
            visIdx.add(i);
            visRect.add(b);
        }
        Integer[] order = new Integer[visIdx.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final boolean down = dy > 0;
        java.util.Arrays.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return down ? Integer.compare(visRect.get(b).bottom, visRect.get(a).bottom)
                            : Integer.compare(visRect.get(a).top, visRect.get(b).top);
            }
        });

        // For each, look at the next hidden item in page order (previous, when scrolling up).
        for (int k = 0; k < order.length && k < 8; k++) {
            int i = visIdx.get(order[k]);
            for (int step = 1; step <= 40; step++) {
                int j = down ? i + step : i - step;
                if (j < 0 || j >= units.size()) break;
                if (vis.get(j)) continue;
                AccessibilityNodeInfo leaf = edgeLeaf(units.get(j), down);
                if (!plausible(leaf, area, down)) continue;
                String key = nodeKey(leaf);
                long now = SystemClock.uptimeMillis();
                if (key.equals(lastShowKey) && now - lastShowAt < 3000) {
                    // Same item as last time: showing it didn't move the page. Use the normal scroll instead.
                    lastShowKey = null;
                    return false;
                }
                if (leaf.performAction(android.R.id.accessibilityActionShowOnScreen)) {
                    lastShowKey = key;
                    lastShowAt = now;
                    return true;
                }
            }
        }
        return false;
    }

    /** Page-order list of visible leaves, plus whole hidden branches as single entries. */
    private void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> units, List<Boolean> vis,
                         int depth, int[] budget) {
        if (n == null || depth > 80 || budget[0] <= 0) return;
        budget[0]--;
        if (!n.isVisibleToUser()) {
            units.add(n);
            vis.add(Boolean.FALSE);
            return;
        }
        int c = n.getChildCount();
        if (c == 0) {
            units.add(n);
            vis.add(Boolean.TRUE);
            return;
        }
        for (int i = 0; i < c && budget[0] > 0; i++) collect(n.getChild(i), units, vis, depth + 1, budget);
    }

    /** First item inside a hidden branch (last item, when scrolling up). */
    private static AccessibilityNodeInfo edgeLeaf(AccessibilityNodeInfo n, boolean down) {
        for (int d = 0; d < 40 && n != null; d++) {
            int c = n.getChildCount();
            if (c == 0) return n;
            AccessibilityNodeInfo next = n.getChild(down ? 0 : c - 1);
            if (next == null) return n;
            n = next;
        }
        return n;
    }

    /** Is this hidden item really just past the edge (and not a hidden menu somewhere else)? */
    private static boolean plausible(AccessibilityNodeInfo leaf, Rect area, boolean down) {
        if (leaf == null) return false;
        Rect b = new Rect();
        leaf.getBoundsInScreen(b);
        if (b.isEmpty()) return true;                      // position not reported: trust page order
        if (b.width() <= 2 || b.height() <= 2) return false; // screen-reader-only text
        if (down) return b.top >= area.bottom - 4 && b.top <= area.bottom + area.height();
        return b.bottom <= area.top + 4 && b.bottom >= area.top - area.height();
    }

    // ================================================================== finding things on screen

    /** Root of the top-most app window under the point (our own cursor layer is skipped). */
    private AccessibilityNodeInfo topRootAt(int px, int py) {
        List<AccessibilityWindowInfo> wins = new ArrayList<>(svc.getWindows());
        Collections.sort(wins, new Comparator<AccessibilityWindowInfo>() {
            @Override public int compare(AccessibilityWindowInfo a, AccessibilityWindowInfo b) {
                return Integer.compare(b.getLayer(), a.getLayer());
            }
        });
        Rect r = new Rect();
        for (AccessibilityWindowInfo win : wins) {
            if (win.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            win.getBoundsInScreen(r);
            if (!r.contains(px, py)) continue;
            AccessibilityNodeInfo root = win.getRoot();
            if (root != null) return root;
        }
        return svc.getRootInActiveWindow();
    }

    /** Deepest visible element containing the point. */
    private static AccessibilityNodeInfo deepestAt(AccessibilityNodeInfo n, int px, int py, int depth) {
        if (n == null || depth > 80 || !n.isVisibleToUser()) return null;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (!b.contains(px, py)) return null;
        for (int i = n.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo hit = deepestAt(n.getChild(i), px, py, depth + 1);
            if (hit != null) return hit;
        }
        return n;
    }

    /** Deepest element containing the point that can be clicked (or long-pressed, if longClick). */
    private static AccessibilityNodeInfo findActionable(AccessibilityNodeInfo n, int px, int py, int depth,
                                                        boolean longClick) {
        if (n == null || depth > 80 || !n.isVisibleToUser()) return null;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        if (!b.contains(px, py)) return null;
        for (int i = n.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo hit = findActionable(n.getChild(i), px, py, depth + 1, longClick);
            if (hit != null) return hit;
        }
        if (longClick) {
            if (n.isLongClickable() || hasAction(n, AccessibilityNodeInfo.ACTION_LONG_CLICK)) return n;
        } else {
            if (n.isClickable() || hasAction(n, AccessibilityNodeInfo.ACTION_CLICK)) return n;
        }
        return null;
    }

    /** A whole web page reported as one box: its links aren't available (yet). */
    private static boolean isWebContainer(AccessibilityNodeInfo n) {
        CharSequence c = n.getClassName();
        if (c == null) return false;
        String s = c.toString();
        return s.endsWith("WebView") || s.contains("ContentView") || s.contains("RenderWidgetHostView");
    }

    // ================================================================== clicking

    private static final int HIT_NONE = 0;
    private static final int HIT_DONE = 1;
    private static final int HIT_WEB_CONTAINER = 2;

    private int actAt(int px, int py, boolean longClick) {
        try {
            AccessibilityNodeInfo root = topRootAt(px, py);
            AccessibilityNodeInfo t = findActionable(root, px, py, 0, longClick);
            if (t == null) return HIT_NONE;
            if (isWebContainer(t)) return HIT_WEB_CONTAINER;
            int action = longClick ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
            return t.performAction(action) ? HIT_DONE : HIT_NONE;
        } catch (RuntimeException e) {
            return HIT_NONE;
        }
    }

    private void click(final int px, final int py) {
        cursor.pulse();
        int r = actAt(px, py, false);
        if (r == HIT_WEB_CONTAINER) {
            // The browser builds the list of links when first asked. Ask again a moment later.
            ui.postDelayed(new Runnable() {
                @Override public void run() {
                    int r2 = actAt(px, py, false);
                    if (r2 == HIT_NONE) tap(px, py, 60);
                    else if (r2 == HIT_WEB_CONTAINER) toast("Nothing to click there yet. Try again.", Toast.LENGTH_SHORT);
                }
            }, 250);
            return;
        }
        if (r == HIT_NONE) tap(px, py, 60);
    }

    private void longPress(int px, int py) {
        cursor.pulse();
        int r = actAt(px, py, true);
        if (r != HIT_DONE) tap(px, py, 800);
    }

    /** Simulated touch. Many TVs ignore these; used only when nothing else applies. */
    private void tap(float px, float py, long ms) {
        try {
            Path p = new Path();
            p.moveTo(px, py);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, ms))
                    .build();
            svc.dispatchGesture(g, null, ui);
        } catch (RuntimeException ignored) { }
    }
}
