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
        sh.closeSheet();
        cpNotifBox = null;
    }

    /** Full-screen layer with a glass panel; registered as SceneHome's sheet so Back closes it. */
    private LinearLayout openLayer(View returnTo, boolean right, int widthU, int shadeFrom, int shadeTo) {
        FrameLayout layer = new FrameLayout(host);
        layer.setClickable(true);
        layer.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{shadeFrom, shadeTo}));
        ScrollView sv = new ScrollView(host);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        sv.setBackground(sh.liquidGlass(Liquid.PANEL, dp(24)));
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
        sh.sheetLayer = layer;
        sh.sheetReturnFocus = returnTo;
        sh.overlay.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        host.root.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        if (top < dp(20)) scroll.smoothScrollBy(0, top - dp(20));
        else if (bottom > h - dp(20)) scroll.smoothScrollBy(0, bottom - h + dp(20));
    }

    /** A rounded pane that lights up when focused (white ring, slight lift). */
    private GradientDrawable pane(float r, boolean on) {
        GradientDrawable g = on
                ? new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0x8C78B4FF, 0x594678FF})
                : new GradientDrawable();
        if (!on) g.setColor(0x1AFFFFFF);
        g.setCornerRadius(r);
        g.setStroke(Math.max(1, dp(0.5f)), on ? 0x99BED7FF : 0x2EFFFFFF);
        return g;
    }

    private void focusable(View v, float r, float scale) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((x, has) -> {
            x.setForeground(has ? lh.ring(r) : null);
            x.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).setDuration(200).setInterpolator(MainActivity.LIFT).start();
            if (has) keepVisible(x);
        });
    }

    private TextView chip(String label, Runnable r) {
        TextView t = text(9f, "semibold", Color.WHITE);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(11), dp(5), dp(11), dp(5));
        t.setBackground(pane(dp(12), false));
        focusable(t, dp(12), 1.06f);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    // ================================================================== Control Panel

    private LinearLayout cpNotifBox;
    private LinearLayout recentRow;

    void showControlPanel(View returnTo, boolean focusNotifications) {
        if (sh.sheetLayer != null) return;
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
            close();
            if (!Suggestions.start(host, new Intent("android.settings.DISPLAY_SETTINGS"))) host.startSafe(new Intent(Settings.ACTION_SETTINGS));
        }));
        tiles.add(tile(grid, "timer", "Sleep timer", () -> ControlPanelOverlay.timerState(c), () -> SleepReceiver.timerLeft(c) > 0, t -> {
            ControlPanelOverlay.cycleSleepTimer(c);
            sh.refreshMoonBtn();
        }));
        tiles.add(tile(grid, "input", "Input", () -> "Switch", () -> false, t -> {
            close();
            ControlPanelOverlay.openInputs(c, () -> host.showInputs(sh.moonBtn));
        }));
        tiles.add(tile(grid, "ambient", "Screensaver", () -> "Start now", () -> false, t -> {
            sh.closeSheetImmediate();
            lh.showSaver();
        }));
        tiles.add(tile(grid, "scene", "Wallpaper", () -> "Night Scenes", () -> false, t -> {
            close();
            host.returningFromSettings = true;
            host.startActivity(new Intent(host, SettingsActivity.class));
        }));
        tiles.add(tile(grid, "sliders", "Liquid Glass", () -> "On", () -> true, t -> {
            sh.closeSheetImmediate();
            Liquid.setOn(host, false);
            host.recreate();
        }));

        // ---- sliders
        LinearLayout sl = new LinearLayout(host);
        sl.setOrientation(LinearLayout.VERTICAL);
        sl.setBackground(pane(dp(15), false));
        sl.setPadding(dp(13), dp(11), dp(13), dp(12));
        LinearLayout.LayoutParams sll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sll.topMargin = dp(15);
        col.addView(sl, sll);
        AudioManager am = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
        slider(sl, "Brightness", "ambient", this::brightness, this::setBrightness);
        slider(sl, "Volume", "audio", () -> {
            if (am == null) return 0;
            int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
        }, v -> {
            if (am == null) return;
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v / 100f * max), 0);
        });

        // ---- recent apps
        LinearLayout rh = new LinearLayout(host);
        rh.setOrientation(LinearLayout.HORIZONTAL);
        rh.setGravity(Gravity.CENTER_VERTICAL);
        rh.addView(caps("Recent apps"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rh.addView(chip("Clear", this::clearRecents));
        LinearLayout.LayoutParams rhl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rhl.topMargin = dp(16);
        col.addView(rh, rhl);
        recentRow = new LinearLayout(host);
        recentRow.setOrientation(LinearLayout.HORIZONTAL);
        recentRow.setClipChildren(false);
        LinearLayout.LayoutParams rrl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rrl.topMargin = dp(9);
        col.addView(recentRow, rrl);
        fillRecents();

        // ---- notifications
        LinearLayout nh = new LinearLayout(host);
        nh.setOrientation(LinearLayout.HORIZONTAL);
        nh.setGravity(Gravity.CENTER_VERTICAL);
        nh.addView(caps("Notifications"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nh.addView(chip("Clear all", () -> {
            Notifs.clearAll(host);
            host.handler.postDelayed(this::fillNotifs, 300);
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

        View first = focusNotifications && cpNotifBox.getChildCount() > 0 && cpNotifBox.getChildAt(0).isFocusable()
                ? cpNotifBox.getChildAt(0) : tiles.get(0);
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
            if (sh.sheetLayer != null) refresh.run();
        });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (row.getChildCount() > 0) tl.leftMargin = dp(9);
        row.addView(t, tl);
        return t;
    }

    /** A focusable slider row: Left/Right change it by 10. */
    private void slider(LinearLayout parent, String label, String glyph, java.util.function.IntSupplier get, IntConsumer set) {
        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(5), dp(6), dp(5));
        box.addView(caps(label));
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
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(6);
        box.addView(r, rl);
        Runnable refresh = () -> {
            int v = Math.max(0, Math.min(100, get.getAsInt()));
            bar.value = v / 100f;
            bv.invalidate();
            val.setText(String.valueOf(v));
        };
        refresh.run();
        GradientDrawable fb = new GradientDrawable();
        fb.setCornerRadius(dp(10));
        fb.setColor(0x00000000);
        box.setBackground(fb);
        box.setFocusable(true);
        box.setOnFocusChangeListener((x, has) -> {
            fb.setColor(has ? 0x26FFFFFF : 0);
            bar.lit = has;
            bv.invalidate();
            if (has) keepVisible(x);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (parent.getChildCount() > 0) lp.topMargin = dp(6);
        parent.addView(box, lp);
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

    private int brightnessCache = -1;

    private int brightness() {
        if (brightnessCache >= 0) return brightnessCache;
        try {
            int b = Settings.System.getInt(host.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            brightnessCache = Math.round(b * 100f / 255f / 10f) * 10;
        } catch (Throwable t) {
            brightnessCache = 70;
        }
        return brightnessCache;
    }

    /** Brightness through Android's setting: directly when allowed, otherwise over the ADB link. */
    private void setBrightness(int v) {
        brightnessCache = v;
        int raw = Math.max(1, Math.round(v / 100f * 255f));
        boolean done = false;
        try {
            if (android.os.Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(host)) {
                Settings.System.putInt(host.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                Settings.System.putInt(host.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, raw);
                done = true;
            }
        } catch (Throwable ignored) {
        }
        if (!done) {
            host.io.execute(() -> {
                try {
                    LocalAdb.execShellOutput(host, "settings put system screen_brightness_mode 0; settings put system screen_brightness " + raw);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private void fillRecents() {
        if (recentRow == null) return;
        recentRow.removeAllViews();
        List<String> pkgs;
        try {
            pkgs = RecentAppsOverlay.getRecentPackages(host, 5);
        } catch (Throwable t) {
            pkgs = new ArrayList<>();
        }
        if (pkgs.isEmpty()) {
            TextView e = text(9.5f, "regular", 0xB3FFFFFF);
            e.setText("No recent apps");
            recentRow.addView(e);
            return;
        }
        for (String pkg : pkgs) {
            MainActivity.AppEntry a = lh.entryFor(pkg);
            if (a == null) continue;
            LinearLayout cell = new LinearLayout(host);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false);
            FrameLayout ic = new FrameLayout(host);
            ImageView iv = new ImageView(host);
            sh.applyLiquidIconTo(iv, a, dp(44));
            ic.addView(iv, new FrameLayout.LayoutParams(dp(44), dp(44)));
            ImageView pin = new ImageView(host);
            pin.setImageBitmap(Glyphs.get("pin", dp(10), Color.WHITE));
            GradientDrawable pb = new GradientDrawable();
            pb.setShape(GradientDrawable.OVAL);
            pb.setColor(0xE6467CFF);
            pin.setBackground(pb);
            pin.setScaleType(ImageView.ScaleType.CENTER);
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.TOP | Gravity.END);
            pl.topMargin = -dp(3);
            pl.rightMargin = -dp(3);
            ic.setClipChildren(false);
            ic.addView(pin, pl);
            pin.setVisibility(RecentAppsOverlay.isPinned(host, pkg) ? View.VISIBLE : View.GONE);
            cell.addView(ic, new LinearLayout.LayoutParams(dp(44), dp(44)));
            focusable(cell, dp(12), 1.12f);
            cell.setLongClickable(true);
            cell.setOnClickListener(v -> {
                sh.closeSheetImmediate();
                host.launchAnimated(null, a);
            });
            cell.setOnLongClickListener(v -> {
                boolean pinned = RecentAppsOverlay.isPinned(host, pkg);
                List<String> labels = new ArrayList<>(Arrays.asList(pinned ? "Unpin" : "Pin (stays open on Clear)", "App info", "Close app"));
                List<Runnable> acts = new ArrayList<>();
                acts.add(() -> {
                    RecentAppsOverlay.setPinned(host, pkg, !pinned);
                    pin.setVisibility(!pinned ? View.VISIBLE : View.GONE);
                });
                acts.add(() -> lh.showAppInfo(a, v));
                acts.add(() -> {
                    RecentAppsOverlay.removeAndKillApp(host, pkg);
                    fillRecents();
                });
                host.showMenu(v, a.label, labels, acts, 2);
                return true;
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (recentRow.getChildCount() > 0) cl.leftMargin = dp(11);
            recentRow.addView(cell, cl);
        }
    }

    private void clearRecents() {
        List<String> pkgs;
        try {
            pkgs = RecentAppsOverlay.getRecentPackages(host, 30);
        } catch (Throwable t) {
            return;
        }
        int n = 0;
        for (String p : pkgs) {
            if (RecentAppsOverlay.isPinned(host, p)) continue;
            RecentAppsOverlay.removeAndKillApp(host, p);
            n++;
        }
        host.toast(n == 0 ? "Nothing to clear · pinned apps stay open" : "Closed " + n + (n == 1 ? " app" : " apps"));
        fillRecents();
    }

    void onNotifsChanged() {
        if (cpNotifBox != null && sh.sheetLayer != null) fillNotifs();
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
            r.setLongClickable(true);
            r.setOnClickListener(v -> openNotif(it));
            r.setOnLongClickListener(v -> {
                List<String> labels = new ArrayList<>();
                List<Runnable> acts = new ArrayList<>();
                labels.add("Open");
                acts.add(() -> openNotif(it));
                if (it.clearable) {
                    labels.add("Dismiss");
                    acts.add(() -> {
                        Notifs.dismiss(it.key);
                        host.handler.postDelayed(this::fillNotifs, 250);
                    });
                }
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
        sh.closeSheetImmediate();
        cpNotifBox = null;
        try {
            if (it.open != null) {
                it.open.send();
                if (it.clearable) Notifs.dismiss(it.key);
                return;
            }
        } catch (Throwable ignored) {
        }
        MainActivity.AppEntry e = lh.entryFor(it.pkg);
        if (e != null) host.launchAnimated(null, e);
    }

    // ================================================================== Look & feel

    private LinearLayout lfList;

    /** Glass settings over Home: every change shows on the screen behind it right away. */
    void showLookAndFeel(View returnTo) {
        if (sh.sheetLayer != null) return;
        LinearLayout col = openLayer(returnTo, false, 400, 0x99030612, 0x00030612);
        TextView k = caps("Settings");
        col.addView(k);
        LinearLayout head = new LinearLayout(host);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(20f, "semibold", Color.WHITE);
        title.setText("Look & feel");
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View done = chip("Done", this::close);
        head.addView(done);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(3);
        col.addView(head, hl);
        lfList = new LinearLayout(host);
        lfList.setOrientation(LinearLayout.VERTICAL);
        lfList.setClipChildren(false);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(10);
        col.addView(lfList, ll);
        fillLookAndFeel(0);
    }

    private static final int GLASS = 1, ICONS = 2, WEATHER = 4, NONE = 0;

    /** Applies a change on the spot: glass surfaces, icons, weather. */
    private void applyLive(int what) {
        if ((what & GLASS) != 0) {
            GlassPanel.relookAll(host);
            sh.invalidateSceneGlass();
        }
        if ((what & ICONS) != 0) sh.rebuildDock();
        if ((what & WEATHER) != 0) lh.refreshWeather();
        host.activeLiquidStamp = Liquid.stamp(host);
    }

    private final List<View> lfRows = new ArrayList<>();

    private void fillLookAndFeel(int focusIdx) {
        lfList.removeAllViews();
        lfRows.clear();
        Context c = host;
        boolean adv = Liquid.isAdvanced(c);
        List<String> styles = Arrays.asList("clear", "frosted", "tinted", "dark");
        List<String> styleNames = Arrays.asList("Clear", "Frosted", "Tinted", "Dark glass");
        row("Settings mode", "Basic: one glass for everything. Advanced: per part", () -> adv ? "Advanced" : "Basic",
                () -> {
                    Liquid.putString(c, "mode", adv ? "basic" : "advanced");
                    applyLive(GLASS);
                    fillLookAndFeel(0);
                },
                d -> {
                    Liquid.putString(c, "mode", d < 0 ? "basic" : "advanced");
                    applyLive(GLASS);
                    fillLookAndFeel(0);
                });
        row("Glass style", "Clear, Frosted, Tinted, Dark glass", () -> "‹ " + styleNames.get(Math.max(0, styles.indexOf(Liquid.preset(c)))) + " ›",
                () -> {
                    Liquid.applyPreset(c, styles.get((styles.indexOf(Liquid.preset(c)) + 1) % 4));
                    applyLive(GLASS);
                },
                d -> {
                    Liquid.applyPreset(c, styles.get(((styles.indexOf(Liquid.preset(c)) + d) % 4 + 4) % 4));
                    applyLive(GLASS);
                });
        if (!adv) {
            pct("Dock background", "0 hides the dock plate", "basic", "dock");
            glassRows("basic");
        } else {
            String[][] parts = {{Liquid.DOCK, "Dock"}, {Liquid.WIDGETS, "Widgets"}, {Liquid.PANEL, "Panels"}, {Liquid.BUTTONS, "Buttons"}};
            for (String[] p : parts) {
                header(p[1]);
                pct("Background", "How much glass this part has", p[0], "bg");
                glassRows(p[0]);
            }
        }
        header("App icons");
        row("Icon style", "Glass only, or glass on a frosted tile", () -> "tile".equals(Liquid.iconStyle(c)) ? "‹ With tile ›" : "‹ No tile ›",
                () -> {
                    Liquid.putString(c, "icon_style", "tile".equals(Liquid.iconStyle(c)) ? "bare" : "tile");
                    applyLive(ICONS);
                },
                d -> {
                    Liquid.putString(c, "icon_style", "tile".equals(Liquid.iconStyle(c)) ? "bare" : "tile");
                    applyLive(ICONS);
                });
        intRow("Glass depth", "How thick the coloured glass looks", () -> Liquid.iconDepth(c), v -> {
            Liquid.putInt(c, "icon_depth", v);
            applyLive(ICONS);
        });
        intRow("See-through", "How clear the coloured glass is", () -> Liquid.iconClarity(c), v -> {
            Liquid.putInt(c, "icon_clarity", v);
            applyLive(ICONS);
        });
        header("Dock");
        List<String> mags = Arrays.asList("off", "subtle", "strong");
        row("Magnification", "Off, Subtle, Strong", () -> "‹ " + cap(Liquid.magnify(c)) + " ›",
                () -> Liquid.putString(c, "magnify", mags.get((mags.indexOf(Liquid.magnify(c)) + 1) % 3)),
                d -> Liquid.putString(c, "magnify", mags.get(Math.max(0, Math.min(2, mags.indexOf(Liquid.magnify(c)) + d)))));
        toggle("Tilt and shine", "No extra memory", () -> Liquid.tilt(c), v -> Liquid.putBool(c, "tilt", v), NONE);
        header("Extras");
        toggle("Weather on glass", "Rain and frost from the forecast", () -> Liquid.weatherFx(c), v -> Liquid.putBool(c, "weather_fx", v), WEATHER);
        List<String> ss = Arrays.asList("drift", "clock", "glass");
        List<String> ssn = Arrays.asList("Scene drift", "Minimal clock", "Liquid glass");
        row("Screensaver", "Style", () -> "‹ " + ssn.get(Math.max(0, ss.indexOf(Liquid.saverStyle(c)))) + " ›",
                () -> Liquid.putString(c, "saver_style", ss.get((ss.indexOf(Liquid.saverStyle(c)) + 1) % 3)),
                d -> Liquid.putString(c, "saver_style", ss.get(((ss.indexOf(Liquid.saverStyle(c)) + d) % 3 + 3) % 3)));
        int[] afters = {1, 5, 15, 0};
        row("Start screensaver after", "", () -> {
            int m = Liquid.saverAfter(c);
            return "‹ " + (m == 0 ? "Never" : m + " min") + " ›";
        }, () -> step(afters, 1), d -> step(afters, d));
        header("More");
        row("Wallpaper", "Night Scenes and your pictures", () -> "›", () -> openSettings(), null);
        row("All settings", "Weather, apps, Control Panel, updates…", () -> "›", () -> openSettings(), null);
        if (!lfRows.isEmpty()) {
            View t = lfRows.get(Math.max(0, Math.min(focusIdx, lfRows.size() - 1)));
            t.post(t::requestFocus);
        }
    }

    private void step(int[] afters, int d) {
        int i = 0;
        for (int j = 0; j < afters.length; j++) if (afters[j] == Liquid.saverAfter(host)) i = j;
        Liquid.putInt(host, "saver_after", afters[Math.max(0, Math.min(afters.length - 1, i + d))]);
        lh.resetSaver();
    }

    private void openSettings() {
        sh.closeSheetImmediate();
        host.returningFromSettings = true;
        host.startActivity(new Intent(host, SettingsActivity.class));
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void glassRows(String part) {
        String[][] items = {{"trans", "Transparency", "How see-through the glass is"}, {"blur", "Blur", "How soft the scene behind looks"},
                {"refr", "Refraction", "Glass bends the scene behind it"}, {"tint", "Tint", "How milky the glass is"}, {"rim", "Rim light", "Bright edge on the glass"}};
        for (String[] it : items) pct(it[1], it[2], part, it[0]);
    }

    private void pct(String label, String hint, String part, String what) {
        intRow(label, hint, () -> Liquid.value(host, part, what), v -> {
            Liquid.setValue(host, part, what, v);
            applyLive(GLASS);
        });
    }

    private void intRow(String label, String hint, java.util.function.IntSupplier get, IntConsumer set) {
        row(label, hint, () -> "‹ " + get.getAsInt() + "% ›",
                () -> set.accept(get.getAsInt() >= 100 ? 0 : get.getAsInt() + 10),
                d -> set.accept(Math.max(0, Math.min(100, get.getAsInt() + d * 10))));
    }

    private void toggle(String label, String hint, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set, int live) {
        row(label, hint, () -> get.get() ? "On" : "Off", () -> {
            set.accept(!get.get());
            applyLive(live);
        }, d -> {
            set.accept(d > 0);
            applyLive(live);
        });
    }

    private void header(String s) {
        TextView h = caps(s);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = dp(14);
        l.bottomMargin = dp(6);
        l.leftMargin = dp(4);
        lfList.addView(h, l);
    }

    private void row(String label, String hint, Supplier<String> value, Runnable ok, IntConsumer lr) {
        LinearLayout r = new LinearLayout(host);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(13));
        bg.setColor(0x00FFFFFF);
        r.setBackground(bg);
        LinearLayout tx = new LinearLayout(host);
        tx.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(11f, "semibold", Color.WHITE);
        l.setText(label);
        tx.addView(l);
        TextView h = null;
        if (hint != null && !hint.isEmpty()) {
            h = text(8.5f, "regular", 0xB3FFFFFF);
            h.setText(hint);
            tx.addView(h);
        }
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(10f, "medium", 0xE6FFFFFF);
        r.addView(v);
        Runnable refresh = () -> v.setText(value.get());
        refresh.run();
        final TextView hh = h;
        r.setFocusable(true);
        r.setClickable(true);
        r.setOnFocusChangeListener((x, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x00FFFFFF);
            l.setTextColor(has ? 0xFF0B1130 : Color.WHITE);
            v.setTextColor(has ? 0xFF0B1130 : 0xE6FFFFFF);
            if (hh != null) hh.setTextColor(has ? 0x990B1130 : 0xB3FFFFFF);
            if (has) keepVisible(x);
        });
        r.setOnClickListener(x -> {
            ok.run();
            refresh.run();
        });
        if (lr != null) {
            r.setOnKeyListener((x, k, ev) -> {
                if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                    lr.accept(k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (r.isAttachedToWindow()) refresh.run();
                }
                return true;
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        lfList.addView(r, lp);
        lfRows.add(r);
    }
}
