package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.techgenius.tvoslauncher.liquid.GlassPanel;
import com.techgenius.tvoslauncher.liquid.Liquid;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * The Liquid Glass Control Panel (eight tiles, brightness and volume, recent apps with Pin and
 * Clear, notifications) and the Look & feel settings panel with live preview. Both open over the
 * Liquid Glass home.
 */
final class LiquidControl {

    final LiquidHome lh;
    final SceneHome sh;
    final MainActivity host;

    LiquidControl(LiquidHome lh) {
        this.lh = lh;
        this.sh = lh.sh;
        this.host = lh.host;
    }

    private int dp(float u) {
        return lh.dp(u);
    }

    private TextView text(float size, String weight, int color) {
        return lh.text(size, weight, color);
    }

    private TextView caps(String s) {
        TextView t = text(8f, "semibold", 0xFFDBE3FF);
        t.setLetterSpacing(0.14f);
        t.setText(s.toUpperCase(Locale.ENGLISH));
        return t;
    }

    private void close() {
        hidePreview();
        if (ovLayer != null) {
            removeOverlay();
        } else {
            sh.closeSheet();
        }
        cpNotifBox = null;
    }

    /** Close at once (before starting something else). */
    private void closeNow() {
        hidePreview();
        if (ovLayer != null) removeOverlay();
        else sh.closeSheetImmediate();
        cpNotifBox = null;
    }

    // ---------------------------------------------------------------- over other apps

    /** True while the Control Panel is shown over another app (the remote's Settings button). */
    private boolean overlayMode;
    private android.view.WindowManager ovWm;
    private FrameLayout ovLayer;
    /** The layer the open panel lives in (Home sheet or the overlay window). */
    private FrameLayout layerRoot;

    boolean isOverlayShowing() {
        return ovLayer != null;
    }

    void closeOverlay() {
        removeOverlay();
        cpNotifBox = null;
    }

    /** Opens the Control Panel over the app in front, without going to Home first. */
    boolean showOverApp(String highlightPkg) {
        PowerService svc = PowerService.instance;
        if (svc == null) return false;
        if (ovLayer != null) {
            close();
            return true;
        }
        if (sh.sheetLayer != null) sh.closeSheetImmediate();
        overlayMode = true;
        try {
            showControlPanel(null, false, highlightPkg);
        } catch (Throwable t) {
            overlayMode = false;
            removeOverlay();
            return false;
        }
        overlayMode = false;
        return ovLayer != null;
    }

    private void removeOverlay() {
        FrameLayout l = ovLayer;
        ovLayer = null;
        layerRoot = null;
        if (l == null || ovWm == null) return;
        try {
            ovWm.removeView(l);
        } catch (Throwable ignored) {
        }
    }

    private void startOutside(Intent i) {
        try {
            host.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
    }

    /** Full-screen layer with a glass panel; registered as SceneHome's sheet so Back closes it. */
    private LinearLayout openLayer(View returnTo, boolean right, int widthU, int shadeFrom, int shadeTo) {
        final boolean over = overlayMode;
        FrameLayout layer = new FrameLayout(host) {
            @Override public boolean dispatchKeyEvent(KeyEvent ev) {
                int k = ev.getKeyCode();
                if (over && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE)) {
                    if (ev.getAction() == KeyEvent.ACTION_UP) close();
                    return true;
                }
                return super.dispatchKeyEvent(ev);
            }
        };
        layer.setClickable(true);
        layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{shadeFrom, shadeTo}));
        ScrollView sv = new ScrollView(host);
        sv.setVerticalScrollBarEnabled(false);
        sv.setVerticalFadingEdgeEnabled(false);  // a fading edge made top and bottom rows look cut
        sv.setFadingEdgeLength(0);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipToPadding(false);
        if (over) {
            // Over an app there is no wallpaper to sample: frosted dark glass, with the app blurred behind.
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xB81A2236, 0xC70B1020});
            g.setCornerRadius(dp(24));
            g.setStroke(Math.max(1, dp(0.8f)), 0x59FFFFFF);
            sv.setBackground(g);
        } else {
            sv.setBackground(sh.liquidGlass(Liquid.PANEL, dp(24)));
        }
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(22), dp(20), dp(22), dp(22));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        sv.addView(col);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(widthU), ViewGroup.LayoutParams.MATCH_PARENT,
                right ? Gravity.END : Gravity.START);
        if (right) pl.setMargins(0, dp(30), dp(30), dp(30));
        else pl.setMargins(dp(115), dp(25), 0, dp(25));
        layer.addView(sv, pl);
        layerRoot = layer;
        if (over) {
            PowerService svc = PowerService.instance;
            ovWm = (android.view.WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            int flags = android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
            if (android.os.Build.VERSION.SDK_INT >= 31) flags |= android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            android.view.WindowManager.LayoutParams wlp = new android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, android.graphics.PixelFormat.TRANSLUCENT);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    wlp.setBlurBehindRadius(dp(30));
                } catch (Throwable ignored) {
                }
            }
            ovWm.addView(layer, wlp);
            ovLayer = layer;
        } else {
            sh.sheetLayer = layer;
            sh.sheetReturnFocus = returnTo;
            sh.overlay.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            host.root.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        layer.setAlpha(0f);
        sv.setTranslationX(right ? dp(40) : -dp(40));
        layer.animate().alpha(1f).setDuration(180).start();
        sv.animate().translationX(0).setDuration(280).setInterpolator(MainActivity.LIFT).start();
        scroll = sv;
        return col;
    }

    private ScrollView scroll;

    private void keepVisible(View v) {
        if (scroll == null) return;
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        scroll.getLocationInWindow(b);
        int top = a[1] - b[1], bottom = top + v.getHeight();
        int h = scroll.getHeight();
        int m = dp(30);
        if (top < m) scroll.smoothScrollBy(0, top - m);
        else if (bottom > h - m) scroll.smoothScrollBy(0, bottom - h + m);
    }

    /** A rounded pane that lights up when focused (white ring, slight lift). */
    private GradientDrawable pane(float r, boolean on) {
        GradientDrawable g = on
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xD978B4FF, 0xB34678FF})
                : new GradientDrawable();
        if (!on) {
            // Tiles: dark or light, as solid as set in Look & feel › Control Panel
            int a = Math.round(Liquid.cpTileOpacity(host) / 100f * 255f);
            g.setColor((a << 24) | (Liquid.cpTilesDark(host) ? 0x0A0E1E : 0xFFFFFF));
        }
        g.setCornerRadius(r);
        g.setStroke(Math.max(1, dp(0.5f)), on ? 0x99BED7FF : 0x2EFFFFFF);
        return g;
    }

    /** The one focus look of the panel: a white ring and a small lift. */
    private void focusable(View v, float r, float scale) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((x, has) -> focusLook(x, r, scale, has));
    }

    private void focusLook(View x, float r, float scale, boolean has) {
        if (x instanceof TextView) {
            // TextView chips don't show a foreground ring here, so the ring goes into the background
            android.graphics.drawable.Drawable bg = x.getBackground();
            if (has) {
                if (!(bg instanceof FocusLayer)) x.setBackground(new FocusLayer(bg, lh.ring(r)));
            } else if (bg instanceof FocusLayer) {
                x.setBackground(((FocusLayer) bg).base);
            }
        } else {
            x.setForeground(has ? lh.ring(r) : null);
        }
        x.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).setDuration(200).setInterpolator(MainActivity.LIFT).start();
        if (has) keepVisible(x);
    }

    private static final class FocusLayer extends android.graphics.drawable.LayerDrawable {
        final android.graphics.drawable.Drawable base;
        FocusLayer(android.graphics.drawable.Drawable base, android.graphics.drawable.Drawable ring) {
            super(base == null ? new android.graphics.drawable.Drawable[]{ring}
                               : new android.graphics.drawable.Drawable[]{base, ring});
            this.base = base;
        }
    }

    private TextView chip(String label, Runnable r) {
        TextView t = text(9f, "semibold", Color.WHITE);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(11), dp(5), dp(11), dp(5));
        t.setBackground(pane(dp(12), false));
        focusable(t, dp(12), 1.08f);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    // ================================================================== Control Panel

    private LinearLayout cpNotifBox;
    private LinearLayout recentRow;

    void showControlPanel(View returnTo, boolean focusNotifications) {
        showControlPanel(returnTo, focusNotifications, null);
    }

    /** @param highlightPkg app to focus in Recent apps (the remote's Settings button), or null */
    void showControlPanel(View returnTo, boolean focusNotifications, String highlightPkg) {
        if (sh.sheetLayer != null || ovLayer != null) return;
        LinearLayout col = openLayer(returnTo, true, 320, 0x26030612, 0xB3030612);

        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.BOTTOM);
        TextView title = text(18f, "semibold", Color.WHITE);
        title.setText("Control Panel");
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView time = text(12f, "regular", Color.WHITE);
        time.setText(new SimpleDateFormat(Scenes.isClock24h(host) ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date()));
        head.addView(time);
        col.addView(head);

        // ---- recent apps (first: the remote's Settings button lands here)
        LinearLayout rh = new LinearLayout(host);
        rh.setOrientation(LinearLayout.HORIZONTAL);
        rh.setGravity(Gravity.CENTER_VERTICAL);
        rh.addView(caps("Recent apps"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView gear = new ImageView(host);
        gear.setScaleType(ImageView.ScaleType.CENTER);
        gear.setImageBitmap(Glyphs.get("gear", dp(12), Color.WHITE));
        gear.setBackground(pane(dp(12), false));
        focusable(gear, dp(12), 1.1f);
        gear.setOnClickListener(v -> {
            if (ovLayer != null) {
                closeNow();
                startOutside(new Intent(host, SettingsActivity.class));
                return;
            }
            sh.closeSheetImmediate();
            sh.showGearSheet(sh.moonBtn);
        });
        LinearLayout.LayoutParams gearl = new LinearLayout.LayoutParams(dp(24), dp(24));
        gearl.rightMargin = dp(7);
        rh.addView(gear, gearl);
        clearChip = chip("Clear", this::clearRecents);
        clearChip.setId(View.generateViewId());
        rh.addView(clearChip);
        LinearLayout.LayoutParams rhl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rhl.topMargin = dp(14);
        col.addView(rh, rhl);
        // memory in use right now
        LinearLayout mem = new LinearLayout(host);
        mem.setOrientation(LinearLayout.HORIZONTAL);
        mem.setGravity(Gravity.CENTER_VERTICAL);
        memText = text(8.5f, "medium", 0xE6FFFFFF);
        mem.addView(memText);
        memBar = new Bar();
        View mb = new View(host);
        mb.setBackground(memBar);
        memBarView = mb;
        LinearLayout.LayoutParams mbl = new LinearLayout.LayoutParams(0, dp(4), 1f);
        mbl.leftMargin = dp(10);
        mem.addView(mb, mbl);
        LinearLayout.LayoutParams meml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        meml.topMargin = dp(8);
        col.addView(mem, meml);
        updateMemory();
        recentRow = new LinearLayout(host);
        recentRow.setOrientation(LinearLayout.HORIZONTAL);
        recentRow.setClipChildren(false);
        recentRow.setClipToPadding(false);
        recentRow.setPadding(0, dp(6), 0, 0);
        LinearLayout.LayoutParams rrl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rrl.topMargin = dp(8);
        col.addView(recentRow, rrl);
        fillRecents();

        // ---- 8 tiles
        List<View> tiles = new ArrayList<>();
        LinearLayout grid = new LinearLayout(host);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gl.topMargin = dp(15);
        col.addView(grid, gl);
        Context c = host;
        tiles.add(tile(grid, "wifi", "Wi-Fi", () -> ControlPanelOverlay.wifiState(c), () -> !ControlPanelOverlay.wifiState(c).toLowerCase(Locale.ENGLISH).contains("off"), t -> {
            close();
            ControlPanelOverlay.openWifiSettings(c);
        }));
        tiles.add(tile(grid, "bluetooth", "Bluetooth", () -> ControlPanelOverlay.bluetoothState(c), () -> !ControlPanelOverlay.bluetoothState(c).toLowerCase(Locale.ENGLISH).contains("off"), t -> {
            close();
            ControlPanelOverlay.openBluetoothSettings(c);
        }));
        tiles.add(tile(grid, "present", "Picture", () -> "Settings", () -> false, t -> {
            closeNow();
            Backlight.openPicture(host);  // the TV's own picture screen: backlight, colour, sharpness
        }));
        tiles.add(tile(grid, "timer", "Sleep timer", () -> ControlPanelOverlay.timerState(c), () -> SleepReceiver.timerLeft(c) > 0, t -> {
            ControlPanelOverlay.cycleSleepTimer(c);
            sh.refreshMoonBtn();
        }));
        tiles.add(tile(grid, "input", "Input", () -> "Switch", () -> false, t -> {
            boolean over = ovLayer != null;
            close();
            ControlPanelOverlay.openInputs(c, over ? null : () -> host.showInputs(sh.moonBtn));
        }));
        tiles.add(tile(grid, "ambient", "Screensaver", () -> "Start now", () -> false, t -> {
            if (ovLayer != null) {
                closeNow();
                LiquidHome.pendingSaver = true;
                startOutside(new Intent(host, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                return;
            }
            sh.closeSheetImmediate();
            lh.showSaver();
        }));
        tiles.add(tile(grid, "scene", "Wallpaper", () -> Liquid.isApod(c) ? "Astronomy" : "Night Scenes",
                () -> Liquid.isApod(c), t -> setWallpaper(Liquid.isApod(c) ? "scenes" : "apod")));
        tiles.add(tile(grid, "sliders", "Liquid Glass", () -> "On", () -> true, t -> {
            closeNow();
            Liquid.setOn(host, false);
            host.recreate();
        }));

        // ---- sliders
        LinearLayout sl = new LinearLayout(host);
        sl.setOrientation(LinearLayout.VERTICAL);
        sl.setClipChildren(false);
        LinearLayout.LayoutParams sll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sll.topMargin = dp(15);
        col.addView(sl, sll);
        AudioManager am = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
        // Backlight: the TV's real backlight through its picture slices. Until it's read (and if
        // the TV doesn't offer it), Left/Right open the TV's picture screen instead.
        final int[] bl = {-1};
        final long[] blSent = {0};
        final Runnable[] blRefresh = {null};
        blRefresh[0] = slider(sl, "Backlight", "ambient", () -> Math.max(0, bl[0]), v -> {
            if (bl[0] < 0) {
                closeNow();
                Backlight.openPicture(host);
                return;
            }
            bl[0] = v;
            final long stamp = ++blSent[0];
            // Send only the last value of a quick run of presses.
            host.handler.postDelayed(() -> {
                if (stamp != blSent[0]) return;
                host.io.execute(() -> Backlight.set(host, v));
            }, 180);
        });
        host.io.execute(() -> {
            int v = Backlight.read(host);
            host.handler.post(() -> {
                bl[0] = v;
                if (blRefresh[0] != null) blRefresh[0].run();
            });
        });
        slider(sl, "Volume", "audio", () -> {
            if (am == null) return 0;
            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
        }, v -> {
            if (am == null) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v / 100f * max), 0);
        });

        // ---- notifications
        LinearLayout nh = new LinearLayout(host);
        nh.setOrientation(LinearLayout.HORIZONTAL);
        nh.setGravity(Gravity.CENTER_VERTICAL);
        nh.addView(caps("Notifications"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nh.addView(chip("Clear all", () -> {
            Notifs.clearAll(host);
            fillNotifs();
            host.handler.postDelayed(this::fillNotifs, 400);
            lh.updateBadge();
        }));
        LinearLayout.LayoutParams nhl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nhl.topMargin = dp(16);
        col.addView(nh, nhl);
        cpNotifBox = new LinearLayout(host);
        cpNotifBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nbl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nbl.topMargin = dp(8);
        col.addView(cpNotifBox, nbl);
        fillNotifs();
        Notifs.markSeen(host);
        lh.updateBadge();

        View first = tiles.get(0);
        if (focusNotifications && cpNotifBox.getChildCount() > 0 && cpNotifBox.getChildAt(0).isFocusable()) {
            first = cpNotifBox.getChildAt(0);
        } else if (highlightPkg != null) {
            for (int i = 0; i < recentRow.getChildCount(); i++) {
                View v = recentRow.getChildAt(i);
                if (highlightPkg.equals(v.getTag(R_PKG))) first = v;
            }
        }
        first.post(first::requestFocus);
    }

    private interface TileAction {
        void run(View tile);
    }

    private View tile(LinearLayout grid, String glyph, String title, Supplier<String> status, Supplier<Boolean> on, TileAction act) {
        LinearLayout row;
        if (grid.getChildCount() == 0 || ((LinearLayout) grid.getChildAt(grid.getChildCount() - 1)).getChildCount() >= 2) {
            row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (grid.getChildCount() > 0) rl.topMargin = dp(9);
            grid.addView(row, rl);
        } else {
            row = (LinearLayout) grid.getChildAt(grid.getChildCount() - 1);
        }
        LinearLayout t = new LinearLayout(host);
        t.setOrientation(LinearLayout.HORIZONTAL);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(11), dp(11), dp(10), dp(11));
        ImageView iv = new ImageView(host);
        iv.setImageBitmap(Glyphs.get(glyph, dp(17), Color.WHITE));
        t.addView(iv, new LinearLayout.LayoutParams(dp(17), dp(17)));
        LinearLayout tx = new LinearLayout(host);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView a = text(11f, "semibold", Color.WHITE);
        a.setText(title);
        TextView b = text(8.5f, "regular", 0xFFE6ECFF);
        tx.addView(a);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(2);
        tx.addView(b, bl);
        LinearLayout.LayoutParams txl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        txl.leftMargin = dp(8);
        t.addView(tx, txl);
        Runnable refresh = () -> {
            String s;
            try {
                s = status.get();
            } catch (Throwable e) {
                s = "";
            }
            b.setText(s);
            t.setBackground(pane(dp(15), on.get()));
        };
        refresh.run();
        focusable(t, dp(15), 1.04f);
        t.setOnClickListener(v -> {
            act.run(v);
            if (sh.sheetLayer != null || ovLayer != null) refresh.run();
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) tl.leftMargin = dp(9);
        row.addView(t, tl);
        return t;
    }

    /** A focusable slider row: Left/Right change it by 10. */
    private Runnable slider(LinearLayout parent, String label, String glyph, java.util.function.IntSupplier get, IntConsumer set) {
        FrameLayout box = new FrameLayout(host);
        box.setPadding(dp(14), 0, dp(14), 0);
        TextView cap = caps(label);
        FrameLayout.LayoutParams capl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        capl.topMargin = dp(9);
        box.addView(cap, capl);
        LinearLayout r = new LinearLayout(host);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(host);
        iv.setImageBitmap(Glyphs.get(glyph, dp(13), Color.WHITE));
        r.addView(iv, new LinearLayout.LayoutParams(dp(13), dp(13)));
        Bar bar = new Bar();
        View bv = new View(host);
        bv.setBackground(bar);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(9), 1f);
        bl.leftMargin = dp(8);
        bl.rightMargin = dp(8);
        r.addView(bv, bl);
        TextView val = text(9f, "regular", Color.WHITE);
        val.setGravity(Gravity.END);
        r.addView(val, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        rlp.topMargin = dp(8);
        box.addView(r, rlp);
        Runnable refresh = () -> {
            int v = Math.max(0, Math.min(100, get.getAsInt()));
            bar.value = v / 100f;
            bv.invalidate();
            val.setText(String.valueOf(v));
        };
        refresh.run();
        box.setBackground(pane(dp(15), false));
        box.setFocusable(true);
        box.setOnFocusChangeListener((x, has) -> {
            bar.lit = has;
            bv.invalidate();
            focusLook(x, dp(15), 1.02f, has);
        });
        box.setOnKeyListener((x, k, ev) -> {
            if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                set.accept(Math.max(0, Math.min(100, get.getAsInt() + (k == KeyEvent.KEYCODE_DPAD_LEFT ? -10 : 10))));
                host.handler.postDelayed(refresh, 120);
                refresh.run();
            }
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        if (parent.getChildCount() > 0) lp.topMargin = dp(6);
        parent.addView(box, lp);
        return refresh;
    }

    private final class Bar extends android.graphics.drawable.Drawable {
        float value;
        boolean lit;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas c) {
            RectF r = new RectF(getBounds());
            float rad = r.height() / 2f;
            p.setColor(0x29FFFFFF);
            c.drawRoundRect(r, rad, rad, p);
            p.setColor(lit ? Color.WHITE : 0xE6FFFFFF);
            RectF f = new RectF(r.left, r.top, r.left + Math.max(r.height(), r.width() * value), r.bottom);
            c.drawRoundRect(f, rad, rad, p);
        }

        @Override public void setAlpha(int a) {}

        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}

        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private View clearChip;
    private TextView memText;
    private Bar memBar;
    private View memBarView;
    private static final int R_PKG = 0x7f0a1001;

    /** Memory in use, from Android's own figures. */
    private long[] memory() {
        android.app.ActivityManager am = (android.app.ActivityManager) host.getSystemService(Context.ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(mi);
        return new long[]{mi.totalMem, mi.availMem};
    }

    private void updateMemory() {
        if (memText == null) return;
        long[] m = memory();
        if (m[0] <= 0) return;
        long used = m[0] - m[1];
        memText.setText(String.format(Locale.ENGLISH, "Memory  %.1f of %.1f GB in use", used / 1073741824.0, m[0] / 1073741824.0));
        memBar.value = used / (float) m[0];
        memBarView.invalidate();
    }

    /** Apps in a row; each has a pin in its top-right corner. Up from an app: its pin, then Clear. */
    private void fillRecents() {
        if (recentRow == null) return;
        recentRow.removeAllViews();
        List<String> pkgs;
        try {
            pkgs = RecentAppsOverlay.getRecentPackages(host, 5);
        } catch (Throwable t) {
            pkgs = new ArrayList<>();
        }
        int cell = dp(46), gap = dp(14), pinS = dp(18);
        for (String pkg : pkgs) {
            MainActivity.AppEntry a = lh.entryFor(pkg);
            if (a == null || a.launch == null) continue;
            boolean first = recentRow.getChildCount() == 0;

            FrameLayout slot = new FrameLayout(host);
            slot.setClipChildren(false);
            slot.setClipToPadding(false);

            // app
            FrameLayout ic = new FrameLayout(host);
            ic.setId(View.generateViewId());
            ImageView iv = new ImageView(host);
            sh.applyLiquidIconTo(iv, a, cell);
            ic.addView(iv, new FrameLayout.LayoutParams(cell, cell));
            ic.setTag(R_PKG, pkg);
            focusable(ic, dp(13), 1.12f);
            ic.setOnFocusChangeListener((x, has) -> {
                focusLook(x, dp(13), 1.12f, has);
                if (has) schedulePreview(a);
                else hidePreview();
            });
            ic.setLongClickable(ovLayer == null);
            ic.setOnClickListener(v -> {
                if (ovLayer != null) {
                    closeNow();
                    startOutside(new Intent(a.launch));
                    return;
                }
                sh.closeSheetImmediate();
                host.launchAnimated(null, a);
            });
            if (ovLayer == null) ic.setOnLongClickListener(v -> {
                List<String> labels = new ArrayList<>(Arrays.asList("App info", "Close app"));
                List<Runnable> acts = new ArrayList<>();
                acts.add(() -> lh.showAppInfo(a, v));
                acts.add(() -> {
                    RecentAppsOverlay.removeAndKillApp(host, pkg);
                    AppShots.markClosed(pkg);
                    fillRecents();
                    host.handler.postDelayed(this::updateMemory, 1500);
                });
                host.showMenu(v, a.label, labels, acts, 1);
                return true;
            });
            slot.addView(ic, new FrameLayout.LayoutParams(cell, cell));

            // pin badge, overlapping the icon's top-right corner
            ImageView pin = new ImageView(host);
            pin.setId(View.generateViewId());
            pin.setScaleType(ImageView.ScaleType.CENTER);
            GradientDrawable pb = new GradientDrawable();
            pb.setShape(GradientDrawable.OVAL);
            pin.setBackground(pb);
            pin.setElevation(dp(3));
            Runnable paint = () -> {
                boolean p = RecentAppsOverlay.isPinned(host, pkg);
                pb.setColor(p ? 0xFFFFFFFF : 0xE6262A36);
                pb.setStroke(Math.max(1, dp(1f)), p ? 0xFFFFFFFF : 0x66FFFFFF);
                pin.setImageBitmap(Glyphs.get("pin", dp(9), p ? 0xFF0B1130 : Color.WHITE));
            };
            paint.run();
            pin.setFocusable(true);
            pin.setClickable(true);
            pin.setOnFocusChangeListener((x, has) -> {
                x.setForeground(has ? lh.ring(pinS / 2f) : null);
                x.animate().scaleX(has ? 1.25f : 1f).scaleY(has ? 1.25f : 1f).setDuration(180).setInterpolator(MainActivity.LIFT).start();
                if (has) keepVisible(slot);
            });
            pin.setOnClickListener(x -> {
                RecentAppsOverlay.setPinned(host, pkg, !RecentAppsOverlay.isPinned(host, pkg));
                paint.run();
            });
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(pinS, pinS, Gravity.TOP | Gravity.END);
            pl.topMargin = -dp(6);
            pl.rightMargin = -dp(6);
            slot.addView(pin, pl);
            pin.setNextFocusUpId(clearChip.getId());
            pin.setNextFocusDownId(ic.getId());
            ic.setNextFocusUpId(pin.getId());

            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(cell, cell);
            if (!first) sl.leftMargin = gap;
            recentRow.addView(slot, sl);
        }
        if (recentRow.getChildCount() == 0) {
            TextView e = text(9.5f, "regular", 0xB3FFFFFF);
            e.setText("No recent apps");
            recentRow.addView(e);
        }
    }

    // ---------------------------------------------------------------- window preview

    private View cpPreview;
    private MainActivity.AppEntry previewFor;
    private final Runnable cpShow = this::showPreview;

    private void schedulePreview(MainActivity.AppEntry a) {
        hidePreview();
        previewFor = a;
        if (a != null && AppShots.has(host, a.pkg)) host.handler.postDelayed(cpShow, 500);
    }

    /** Shown just below Recent apps, over the tiles, which stay where they are. */
    private void showPreview() {
        FrameLayout root = layerRoot;
        MainActivity.AppEntry a = previewFor;
        if (root == null || a == null || recentRow == null || !recentRow.isAttachedToWindow()) return;
        int[] rl = new int[2], ol = new int[2];
        recentRow.getLocationInWindow(rl);
        root.getLocationInWindow(ol);
        int w = recentRow.getWidth();
        android.graphics.drawable.Drawable bg;
        if (ovLayer != null) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xF21E2840, 0xF20E1424});
            g.setCornerRadius(dp(16));
            g.setStroke(Math.max(1, dp(0.8f)), 0x66FFFFFF);
            bg = g;
        } else {
            Liquid.Look l = Liquid.look(host, Liquid.PANEL);
            l.trans = Math.min(l.trans, 35);  // solid enough to read over the tiles
            bg = new GlassPanel(sh.liquidSource, l, dp(16), host.screenW, host.screenH, host.unit);
        }
        View card = lh.previewCard(a, w, bg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = rl[0] - ol[0];
        lp.topMargin = rl[1] - ol[1] + recentRow.getHeight() + dp(8);
        root.addView(card, lp);
        cpPreview = card;
        LiquidHome.popIn(card, -dp(8));
    }

    private void hidePreview() {
        host.handler.removeCallbacks(cpShow);
        View v = cpPreview;
        cpPreview = null;
        if (v == null) return;
        v.animate().alpha(0f).setDuration(110).withEndAction(() -> {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        }).start();
    }

    /** Closes every app that isn't pinned, then shows how much memory that freed. */
    private void clearRecents() {
        List<String> pkgs;
        try {
            pkgs = RecentAppsOverlay.getRecentPackages(host, 30);
        } catch (Throwable t) {
            return;
        }
        final long before = memory()[1];
        int n = 0;
        for (String p : pkgs) {
            if (RecentAppsOverlay.isPinned(host, p)) continue;
            RecentAppsOverlay.removeAndKillApp(host, p);
            AppShots.markClosed(p);
            n++;
        }
        fillRecents();
        final int closed = n;
        if (n == 0) {
            pill("Nothing to close · pinned apps stay open");
            return;
        }
        final String what = "Closed " + closed + (closed == 1 ? " app" : " apps");
        final TextView p = pill("Closing " + closed + (closed == 1 ? " app…" : " apps…"), 9000);
        // Force-stops land over a few seconds, so watch free memory and keep the best reading.
        final long[] best = {0};
        for (int i = 1; i <= 8; i++) {
            final boolean last = i == 8;
            host.handler.postDelayed(() -> {
                best[0] = Math.max(best[0], memory()[1] - before);
                updateMemory();
                if (p == null) return;
                long mb = Math.round(best[0] / 1048576.0);
                if (mb >= 5) p.setText(String.format(Locale.ENGLISH, "%s · %d MB freed", what, mb));
                else if (last) p.setText(what);
            }, i * 900L);
        }
    }

    /** A glass pill message to the left of the panel, as in the design. */
    private TextView pill(String msg) {
        return pill(msg, 3200);
    }

    private TextView pill(String msg, long showMs) {
        FrameLayout root = layerRoot;
        if (root == null) return null;
        TextView t = text(11f, "medium", Color.WHITE);
        t.setText(msg);
        t.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xD9283454, 0xD91C2440});
        g.setCornerRadius(dp(22));
        g.setStroke(Math.max(1, dp(0.8f)), 0x66FFFFFF);
        t.setBackground(g);
        t.setElevation(dp(8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.rightMargin = dp(380);  // centred in the space left of the panel
        root.addView(t, lp);
        t.setAlpha(0f);
        t.setScaleX(0.9f);
        t.setScaleY(0.9f);
        t.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(MainActivity.LIFT).start();
        host.handler.postDelayed(() -> t.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            if (t.getParent() instanceof ViewGroup) ((ViewGroup) t.getParent()).removeView(t);
        }).start(), showMs);
        return t;
    }

    // ---------------------------------------------------------------- wallpaper

    /** Night Scenes or NASA's Astronomy Picture of the Day; applies straight away. */
    void setWallpaper(String id) {
        if (id.equals(Liquid.wallpaper(host))) return;
        Liquid.putString(host, "wallpaper", id);
        CustomMedia.resetOnlineDeck(CustomMedia.SCOPE_SCENE);
        host.activeLiquidStamp = Liquid.stamp(host);
        try {
            sh.sceneView.syncSettings();
        } catch (Throwable ignored) {
        }
    }

    void onNotifsChanged() {
        if (cpNotifBox != null && (sh.sheetLayer != null || ovLayer != null)) fillNotifs();
    }

    private void fillNotifs() {
        if (cpNotifBox == null) return;
        cpNotifBox.removeAllViews();
        List<Notifs.Item> items = Notifs.list(host);
        if (items.isEmpty()) {
            TextView e = text(9.5f, "regular", 0xB3FFFFFF);
            e.setSingleLine(false);
            e.setText(Notifs.available() ? "No notifications" : "Needs notification access (one ADB command).");
            cpNotifBox.addView(e);
            return;
        }
        for (Notifs.Item it : items) {
            LinearLayout r = new LinearLayout(host);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setPadding(dp(10), dp(9), dp(10), dp(9));
            r.setBackground(pane(dp(13), false));
            ImageView iv = new ImageView(host);
            sh.applyLiquidIconTo(iv, lh.entryFor(it.pkg), dp(24));
            r.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
            LinearLayout tx = new LinearLayout(host);
            tx.setOrientation(LinearLayout.VERTICAL);
            TextView top = text(7.5f, "semibold", 0xC8FFFFFF);
            top.setText(lh.appLabel(it.pkg).toUpperCase(Locale.ENGLISH));
            TextView t = text(10f, "semibold", Color.WHITE);
            t.setText(it.title.isEmpty() ? it.text : it.title);
            tx.addView(top);
            tx.addView(t);
            if (!it.title.isEmpty() && !it.text.isEmpty()) {
                TextView x = text(8.5f, "regular", 0xCCFFFFFF);
                x.setSingleLine(false);
                x.setMaxLines(2);
                x.setText(it.text);
                tx.addView(x);
            }
            LinearLayout.LayoutParams txl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            txl.leftMargin = dp(8);
            r.addView(tx, txl);
            focusable(r, dp(13), 1.02f);
            r.setLongClickable(ovLayer == null);
            r.setOnClickListener(v -> openNotif(it));
            if (ovLayer == null) r.setOnLongClickListener(v -> {
                List<String> labels = new ArrayList<>();
                List<Runnable> acts = new ArrayList<>();
                labels.add("Open");
                acts.add(() -> openNotif(it));
                labels.add("Dismiss");
                acts.add(() -> {
                    Notifs.remove(host, it);
                    host.handler.postDelayed(this::fillNotifs, 250);
                    lh.updateBadge();
                });
                labels.add("Turn off for " + lh.appLabel(it.pkg));
                acts.add(() -> {
                    Notifs.mute(host, it.pkg);
                    fillNotifs();
                    lh.updateBadge();
                });
                host.showMenu(v, lh.appLabel(it.pkg), labels, acts, -1);
                return true;
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.bottomMargin = dp(6);
            cpNotifBox.addView(r, rl);
        }
    }

    private void openNotif(Notifs.Item it) {
        boolean over = ovLayer != null;
        closeNow();
        try {
            if (it.open != null) {
                it.open.send();
                if (it.clearable) Notifs.dismiss(it.key);
                return;
            }
        } catch (Throwable ignored) {
        }
        MainActivity.AppEntry e = lh.entryFor(it.pkg);
        if (e != null && e.launch != null) {
            if (over) startOutside(new Intent(e.launch));
            else host.launchAnimated(null, e);
        }
    }

    // ================================================================== Look & feel

    private LinearLayout lfList;
    private TextView memLabel;
    /** Part being adjusted in Advanced mode. */
    private String lfPart = Liquid.DOCK;

    /** Glass settings over Home (opened from Settings): every change shows behind it right away. */
    void showLookAndFeel(View returnTo) {
        if (sh.sheetLayer != null) return;
        LinearLayout col = openLayer(returnTo, false, 400, 0x99030612, 0x00030612);
        col.addView(caps("Settings"));
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(20f, "semibold", Color.WHITE);
        title.setText("Look & feel");
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View done = chip("Done", this::close);
        done.setTag("done");
        head.addView(done);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(3);
        col.addView(head, hl);
        lfList = new LinearLayout(host);
        lfList.setOrientation(LinearLayout.VERTICAL);
        lfList.setClipChildren(false);
        lfList.setClipToPadding(false);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(8);
        col.addView(lfList, ll);
        fillLookAndFeel(null);
    }

    private static final int GLASS = 1, ICONS = 2, WEATHER = 4, CLOCK = 8, NONE = 0;

    /** Applies a change on the spot: glass surfaces, icons, weather. */
    private void applyLive(int what) {
        if ((what & GLASS) != 0) {
            GlassPanel.relookAll(host);
            sh.invalidateSceneGlass();
            if (scroll != null) scroll.invalidate();
        }
        if ((what & ICONS) != 0) sh.rebuildDock();
        if ((what & WEATHER) != 0) lh.refreshWeather();
        if ((what & CLOCK) != 0) lh.rebuildClock();
        host.activeLiquidStamp = Liquid.stamp(host);
    }

    private final java.util.Map<String, View> lfKeys = new java.util.HashMap<>();

    private void refill() {
        View f = host.getCurrentFocus();
        String key = f != null && f.getTag() instanceof String ? (String) f.getTag() : null;
        fillLookAndFeel(key);
    }

    private void fillLookAndFeel(String focusKey) {
        lfList.removeAllViews();
        lfKeys.clear();
        Context c = host;
        boolean adv = Liquid.isAdvanced(c);

        // Basic | Advanced, and the memory estimate
        LinearLayout top = new LinearLayout(host);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ViewGroup seg = chips(new String[][]{{"basic", "Basic"}, {"advanced", "Advanced"}}, adv ? "advanced" : "basic", "mode:", id -> {
            Liquid.putString(c, "mode", id);
            applyLive(GLASS);
            refill();
        });
        top.addView(seg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        memLabel = text(8.5f, "semibold", 0xFFBFF5D2);
        memLabel.setPadding(dp(8), dp(4), dp(8), dp(4));
        GradientDrawable mb = new GradientDrawable();
        mb.setCornerRadius(dp(10));
        mb.setColor(0x3328C76F);
        mb.setStroke(Math.max(1, dp(0.5f)), 0x6628C76F);
        memLabel.setBackground(mb);
        memLabel.setText("Extra memory: about 4 MB");
        top.addView(memLabel);
        lfList.addView(top);

        if (!adv) {
            header("Glass style");
            add(chips(new String[][]{{"clear", "Clear"}, {"frosted", "Frosted"}, {"tinted", "Tinted"}, {"dark", "Dark glass"}}, Liquid.preset(c), "preset:", id -> {
                Liquid.applyPreset(c, id);
                applyLive(GLASS);
                refill();
            }));
            header("App icons");
            add(chips(new String[][]{{"tile", "With tile"}, {"bare", "No tile"}}, Liquid.iconStyle(c), "icons:", id -> {
                Liquid.putString(c, "icon_style", id);
                applyLive(ICONS);
                refill();
            }));
            header("Glass");
            slider("Dock background", "0 removes it completely", "basic", "dock");
            glassSliders("basic");
            intSlider("Glass depth", "How thick the coloured icon glass looks", "s:depth", () -> Liquid.iconDepth(c), v -> {
                Liquid.putInt(c, "icon_depth", v);
                applyLive(ICONS);
            });
            intSlider("See-through", "How clear the coloured icon glass is", "s:clarity", () -> Liquid.iconClarity(c), v -> {
                Liquid.putInt(c, "icon_clarity", v);
                applyLive(ICONS);
            });
            magnification();
        } else {
            header("Part to adjust");
            add(chips(new String[][]{{Liquid.DOCK, "Dock"}, {"icons", "App icons"}, {Liquid.WIDGETS, "Widgets"},
                    {Liquid.PANEL, "Control Panel"}, {Liquid.BUTTONS, "Top buttons"}}, lfPart, "part:", id -> {
                lfPart = id;
                refill();
            }));
            String part = lfPart;
            String name = "icons".equals(part) ? "App icons" : Liquid.DOCK.equals(part) ? "Dock" : Liquid.WIDGETS.equals(part) ? "Widgets"
                    : Liquid.PANEL.equals(part) ? "Control Panel" : "Top buttons";
            if ("icons".equals(part)) {
                header("App icons · style");
                add(chips(new String[][]{{"tile", "With tile"}, {"bare", "No tile"}}, Liquid.iconStyle(c), "icons:", id -> {
                    Liquid.putString(c, "icon_style", id);
                    applyLive(ICONS);
                    refill();
                }));
                intSlider("Glass depth", "How thick the coloured glass looks", "s:depth", () -> Liquid.iconDepth(c), v -> {
                    Liquid.putInt(c, "icon_depth", v);
                    applyLive(ICONS);
                });
                intSlider("See-through", "How clear the coloured glass is", "s:clarity", () -> Liquid.iconClarity(c), v -> {
                    Liquid.putInt(c, "icon_clarity", v);
                    applyLive(ICONS);
                });
            } else {
                header(name + " · style");
                String pp = Liquid.partPreset(c, part);
                add(chips(new String[][]{{"", "Same as Basic"}, {"clear", "Clear"}, {"frosted", "Frosted"}, {"tinted", "Tinted"}, {"dark", "Dark"}},
                        pp == null ? "" : pp, "pstyle:", id -> {
                            Liquid.applyPartPreset(c, part, id.isEmpty() ? null : id);
                            applyLive(GLASS);
                            refill();
                        }));
                slider("Background", Liquid.DOCK.equals(part) ? "0 removes it completely" : "How much glass this part has", part, "bg");
                glassSliders(part);
                if (Liquid.PANEL.equals(part)) {
                    header("Control Panel · tiles");
                    add(chips(new String[][]{{"dark", "Dark tiles"}, {"light", "Light tiles"}}, Liquid.cpTilesDark(c) ? "dark" : "light", "tiles:", id -> {
                        Liquid.putBool(c, "cp_tiles_dark", "dark".equals(id));
                        refill();
                    }));
                    intSlider("Tile opacity", "How solid the tiles are, for reading over bright scenes", "s:tileop",
                            () -> Liquid.cpTileOpacity(c), v -> Liquid.putInt(c, "cp_tile_opacity", v));
                }
                if (Liquid.DOCK.equals(part)) magnification();
            }
        }
        if (!adv || Liquid.WIDGETS.equals(lfPart)) clockSettings(c);

        header("Wallpaper");
        add(chips(new String[][]{{"scenes", "Night Scenes"}, {"apod", "Astronomy Picture of the Day"}}, Liquid.wallpaper(c), "wall:", id -> {
            setWallpaper(id);
            refill();
        }));

        tailSettings(c);

        View target = focusKey != null ? lfKeys.get(focusKey) : null;
        if (target == null) target = lfKeys.get(adv ? "mode:advanced" : "mode:basic");
        if (target != null) {
            View t = target;
            t.post(t::requestFocus);
        }
    }

    /** Home clock: in Basic, and in Advanced under Widgets only. */
    private void clockSettings(Context c) {
        header("Home clock");
        String[][] faces = new String[AnalogClock.FACES.length][];
        for (int i = 0; i < faces.length; i++) faces[i] = new String[]{AnalogClock.FACES[i], AnalogClock.FACE_NAMES[i]};
        add(chips(faces, Liquid.clockFace(c), "face:", id -> {
            Liquid.putString(c, "clock_face", id);
            applyLive(CLOCK);
            refill();
        }));
        intSlider("Clock size", "", "s:clocksize", () -> Liquid.clockSize(c), v -> {
            Liquid.putInt(c, "clock_size", v);
            applyLive(CLOCK);
        });
        header("Clock position");
        add(chips(new String[][]{{"tl", "Top left"}, {"tc", "Top centre"}, {"tr", "Top right"},
                {"cl", "Middle left"}, {"cc", "Centre"}, {"cr", "Middle right"}}, Liquid.clockPos(c), "cpos:", id -> {
            Liquid.putString(c, "clock_pos", id);
            applyLive(CLOCK);
            refill();
        }));
        header("Day, date and time");
        add(chips(new String[][]{{"below", "Below the clock"}, {"beside", "Beside it"}, {"hidden", "Hidden"}}, Liquid.clockTextPos(c), "tpos:", id -> {
            Liquid.putString(c, "clock_text_pos", id);
            applyLive(CLOCK);
            refill();
        }));
        intSlider("Text size", "", "s:clocktext", () -> Liquid.clockTextSize(c), v -> {
            Liquid.putInt(c, "clock_text_size", v);
            applyLive(CLOCK);
        });
        add(chips(new String[][]{{"inter", "Inter"}, {"display", "Inter Display"}, {"antonio", "Antonio"},
                {"bahnschrift", "Bahnschrift"}, {"varela", "Varela Round"}}, Liquid.clockFont(c), "font:", id -> {
            Liquid.putString(c, "clock_font", id);
            applyLive(CLOCK);
            refill();
        }));
        toggle("Second hand", "Sweeps smoothly", "t:secs", () -> Liquid.clockSeconds(c), v -> Liquid.putBool(c, "clock_seconds", v), CLOCK);
    }

    private void tailSettings(Context c) {
        header("Extras");
        toggle("Tilt and shine", "No extra memory", "t:tilt", () -> Liquid.tilt(c), v -> Liquid.putBool(c, "tilt", v), NONE);
        toggle("Weather on glass", "Rain and frost from the forecast", "t:wx", () -> Liquid.weatherFx(c), v -> Liquid.putBool(c, "weather_fx", v), WEATHER);
        header("Screensaver");
        add(chips(new String[][]{{"drift", "Scene drift"}, {"clock", "Minimal clock"}, {"glass", "Liquid glass"}}, Liquid.saverStyle(c), "ss:", id -> {
            Liquid.putString(c, "saver_style", id);
            refill();
        }));
        add(chips(new String[][]{{"1", "1 min"}, {"5", "5 min"}, {"15", "15 min"}, {"0", "Never"}}, String.valueOf(Liquid.saverAfter(c)), "ssa:", id -> {
            Liquid.putInt(c, "saver_after", Integer.parseInt(id));
            lh.resetSaver();
            refill();
        }));
    }

    private void magnification() {
        header("Dock magnification");
        add(chips(new String[][]{{"off", "Off"}, {"subtle", "Subtle"}, {"strong", "Strong"}}, Liquid.magnify(host), "mag:", id -> {
            Liquid.putString(host, "magnify", id);
            refill();
        }));
    }

    private void add(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        lfList.addView(v, lp);
    }

    private interface Pick {
        void on(String id);
    }

    /** A row of chips side by side; the chosen one is white. OK picks, Left/Right move. */
    private ViewGroup chips(String[][] items, String selected, String keyPrefix, Pick pick) {
        Flow wrap = new Flow(host, dp(8), dp(8));
        for (String[] it : items) {
            boolean sel = it[0].equals(selected);
            TextView t = text(9.5f, "semibold", sel ? 0xFF0B1130 : Color.WHITE);
            t.setEllipsize(null);  // chips wrap to a new line instead of being cut
            t.setText(it[1]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(11), dp(6), dp(11), dp(6));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(14));
            g.setColor(sel ? 0xF2FFFFFF : 0x1FFFFFFF);
            g.setStroke(Math.max(1, dp(0.6f)), sel ? 0xFFFFFFFF : 0x3DFFFFFF);
            t.setBackground(g);
            t.setTag(keyPrefix + it[0]);
            lfKeys.put(keyPrefix + it[0], t);
            t.setFocusable(true);
            t.setClickable(true);
            t.setOnFocusChangeListener((x, has) -> {
                x.setForeground(has ? lh.ring(dp(14)) : null);
                x.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(180).setInterpolator(MainActivity.LIFT).start();
                if (has) keepVisible(x);
            });
            final String id = it[0];
            t.setOnClickListener(x -> pick.on(id));
            wrap.addView(t, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return wrap;
    }

    /** Lays children left to right and wraps to the next line when the width runs out. */
    private static final class Flow extends ViewGroup {
        final int hgap, vgap;

        Flow(Context c, int hgap, int vgap) {
            super(c);
            this.hgap = hgap;
            this.vgap = vgap;
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override protected void onMeasure(int ws, int hs) {
            int max = MeasureSpec.getSize(ws) - getPaddingLeft() - getPaddingRight();
            if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) max = Integer.MAX_VALUE;
            int x = 0, y = 0, lineH = 0, widest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                measureChild(v, MeasureSpec.makeMeasureSpec(Math.max(0, max), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + vgap;
                    lineH = 0;
                }
                x += w + hgap;
                widest = Math.max(widest, x - hgap);
                lineH = Math.max(lineH, h);
            }
            int width = MeasureSpec.getMode(ws) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(ws) : widest + getPaddingLeft() + getPaddingRight();
            setMeasuredDimension(width, y + lineH + getPaddingTop() + getPaddingBottom());
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int max = r - l - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + w > max) {
                    x = 0;
                    y += lineH + vgap;
                    lineH = 0;
                }
                v.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + w, getPaddingTop() + y + h);
                x += w + hgap;
                lineH = Math.max(lineH, h);
            }
        }
    }

    private void glassSliders(String part) {
        String[][] items = {{"trans", "Transparency", "How see-through the glass is"}, {"blur", "Blur", "How soft the scene behind looks"},
                {"refr", "Refraction", "Glass bends the scene behind it"}, {"tint", "Tint", "How milky the glass is"}, {"rim", "Rim light", "Bright edge on the glass"}};
        for (String[] it : items) slider(it[1], it[2], part, it[0]);
    }

    private void slider(String label, String hint, String part, String what) {
        intSlider(label, hint, "s:" + part + ":" + what, () -> Liquid.value(host, part, what), v -> {
            Liquid.setValue(host, part, what, v);
            applyLive(GLASS);
        });
    }

    /** Label and hint on the left, a bar and the value on the right; Left/Right change it by 10. */
    private void intSlider(String label, String hint, String key, java.util.function.IntSupplier get, IntConsumer set) {
        LinearLayout r = new LinearLayout(host);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0);
        r.setBackground(bg);
        LinearLayout tx = new LinearLayout(host);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(10.5f, "semibold", Color.WHITE);
        l.setText(label);
        tx.addView(l);
        TextView h = text(7.5f, "regular", 0xB3FFFFFF);
        h.setText(hint);
        if (hint != null && !hint.isEmpty()) tx.addView(h);
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f));
        Bar bar = new Bar();
        View bv = new View(host);
        bv.setBackground(bar);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, dp(6), 1f);
        bl.leftMargin = dp(8);
        r.addView(bv, bl);
        TextView val = text(9.5f, "medium", Color.WHITE);
        val.setGravity(Gravity.END);
        r.addView(val, new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable refresh = () -> {
            int v = Math.max(0, Math.min(100, get.getAsInt()));
            bar.value = v / 100f;
            bv.invalidate();
            val.setText(v + "%");
        };
        refresh.run();
        r.setTag(key);
        lfKeys.put(key, r);
        r.setFocusable(true);
        r.setOnFocusChangeListener((x, has) -> {
            bg.setColor(has ? 0x33FFFFFF : 0);
            x.setForeground(has ? lh.ring(dp(12)) : null);
            bar.lit = has;
            bv.invalidate();
            if (has) keepVisible(x);
        });
        r.setOnKeyListener((x, k, ev) -> {
            if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                set.accept(Math.max(0, Math.min(100, get.getAsInt() + (k == KeyEvent.KEYCODE_DPAD_LEFT ? -10 : 10))));
                refresh.run();
            }
            return true;
        });
        add(r);
    }

    private void toggle(String label, String hint, String key, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set, int live) {
        LinearLayout r = new LinearLayout(host);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0);
        r.setBackground(bg);
        LinearLayout tx = new LinearLayout(host);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(10.5f, "semibold", Color.WHITE);
        l.setText(label);
        tx.addView(l);
        TextView h = text(7.5f, "regular", 0xB3FFFFFF);
        h.setText(hint);
        tx.addView(h);
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sw = text(9f, "semibold", Color.WHITE);
        sw.setGravity(Gravity.CENTER);
        sw.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable sb = new GradientDrawable();
        sb.setCornerRadius(dp(10));
        sw.setBackground(sb);
        r.addView(sw);
        Runnable refresh = () -> {
            boolean on = get.get();
            sw.setText(on ? "On" : "Off");
            sb.setColor(on ? 0xFF34C759 : 0x33FFFFFF);
        };
        refresh.run();
        r.setTag(key);
        lfKeys.put(key, r);
        r.setFocusable(true);
        r.setClickable(true);
        r.setOnFocusChangeListener((x, has) -> {
            bg.setColor(has ? 0x33FFFFFF : 0);
            x.setForeground(has ? lh.ring(dp(12)) : null);
            if (has) keepVisible(x);
        });
        r.setOnClickListener(x -> {
            set.accept(!get.get());
            applyLive(live);
            refresh.run();
        });
        add(r);
    }

    private void header(String s) {
        TextView h = caps(s);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = dp(13);
        l.bottomMargin = dp(6);
        l.leftMargin = dp(2);
        lfList.addView(h, l);
    }
}
