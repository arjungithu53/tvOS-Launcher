package com.techgenius.tvoslauncher;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Liquid Glass home, layered on the Night Scenes home (SceneHome): the original photo scenes,
 * a small clock top left, Search and Control Panel buttons top right, weather and now-playing
 * cards, and a single-row magnifying dock ending in All apps. Also the All apps grid (with Hidden
 * apps) and App info. Built only while the Liquid Glass layout is on.
 */
final class LiquidHome {

    final SceneHome sh;
    final MainActivity host;

    // clock
    private TextView dateText, timeText;
    // widgets
    private LinearLayout widgetCol;
    private FrameLayout weatherCard, playCard;
    private GlassPanel weatherGlass, playGlass;
    private TextView wxTemp, wxLabel, wxHiLo;
    private ImageView wxIcon;
    private LinearLayout wxHours;
    private TextView npTitle, npSub;
    private ImageView npArt, npButton;
    private View npBar, npBarFill;
    private MediaController npController;
    // dock
    private TextView pillLeft, pillRight;
    private int windowStart = 0;
    static final int VISIBLE = 10;
    private int magFocus = -1;
    private float[] curMargins = new float[0];
    private android.animation.ValueAnimator marginAnim;
    // sheets
    private FrameLayout infoLayer;
    private final List<View> infoButtons = new ArrayList<>();
    private View infoReturn;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            updateNowPlaying();
            host.handler.removeCallbacks(this);
            if (timeText != null && timeText.isAttachedToWindow() && host.resumed) host.handler.postDelayed(this, 3000);
        }
    };

    final LiquidControl control;

    LiquidHome(SceneHome sh) {
        this.sh = sh;
        this.host = sh.host;
        this.control = new LiquidControl(this);
        sh.sceneView.setNoClock(true);
        buildClock();
        buildWidgets();
        // The weather button becomes Search (weather has its own card).
        if (sh.weatherBtn != null) {
            sh.weatherIcon.setImageBitmap(Glyphs.get("search", dp(19), 0xFFF4F4F6));
            sh.weatherBtn.setOnClickListener(v -> openSearch());
            sh.weatherBtn.setOnLongClickListener(v -> {
                openSearch();
                return true;
            });
            sh.weatherBtn.setVisibility(View.VISIBLE);
        }
        if (sh.dockLabel != null && sh.dockLabel.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) sh.dockLabel.getLayoutParams();
            lp.bottomMargin = dp(40);
            sh.dockLabel.setLayoutParams(lp);
        }
        setupNotifications();
        host.handler.post(tick);
    }

    int dp(float u) {
        return host.dp(u);
    }

    void onResume() {
        if (control.isOverlayShowing()) control.closeOverlay();
        AppShots.refreshOpen(host, this::updateDots);
        refreshWeather();
        updateBadge();
        hideSaver();
        resetSaver();
        if (pendingControlPanel) {
            pendingControlPanel = false;
            String p = pendingRecent;
            pendingRecent = null;
            host.handler.postDelayed(() -> {
                if (sh.isSheetOpen()) sh.closeSheetImmediate();
                control.showControlPanel(sh.moonBtn, false, p);
            }, 250);
        }
        if (pendingSaver) {
            pendingSaver = false;
            host.handler.postDelayed(this::showSaver, 300);
        }
        if (pendingLookAndFeel) {
            pendingLookAndFeel = false;
            host.handler.postDelayed(() -> control.showLookAndFeel(null), 300);
        }
        if (pendingNotifications) {
            pendingNotifications = false;
            host.handler.postDelayed(() -> showNotifications(null), 300);
        }
        host.handler.removeCallbacks(tick);
        host.handler.post(tick);
    }

    private void openSearch() {
        host.showSearch("");
        host.popoverReturn = sh.weatherBtn;
    }

    // ================================================================== clock

    private FrameLayout clockBox;

    /** The home clock: an analog face with the day, date and 24-hour time; all set in Look & feel. */
    private void buildClock() {
        clockBox = new FrameLayout(host);
        clockBox.setClipChildren(false);
        sh.overlay.addView(clockBox, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layoutClock();
    }

    void rebuildClock() {
        if (clockBox == null) return;
        clockBox.removeAllViews();
        layoutClock();
    }

    private static String fontWeight(String f) {
        switch (f) {
            case "display": return "display";
            case "antonio": return "clock";
            case "bahnschrift": return "clock_duo";
            case "varela": return "clock_rounded";
            default: return "regular";
        }
    }

    private void layoutClock() {
        String pos = Liquid.clockPos(host), tpos = Liquid.clockTextPos(host);
        int d = dp(80 + Liquid.clockSize(host) * 1.6f);
        float ts = 8f + Liquid.clockTextSize(host) * 0.16f;
        boolean beside = "beside".equals(tpos), hidden = "hidden".equals(tpos);
        boolean right = pos.endsWith("r"), centre = pos.endsWith("c");

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(beside ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        box.setGravity(beside ? Gravity.CENTER_VERTICAL : (centre ? Gravity.CENTER_HORIZONTAL : right ? Gravity.END : Gravity.START));
        box.setClipChildren(false);

        AnalogClock clock = new AnalogClock(host, Liquid.clockFace(host), Liquid.clockSeconds(host), Liquid.clockMarks(host));
        if ("liquid".equals(Liquid.clockFace(host))) clock.setBackground(sh.liquidGlass(Liquid.WIDGETS, d / 2f));
        box.addView(clock, new LinearLayout.LayoutParams(d, d));

        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(beside ? Gravity.START : (centre ? Gravity.CENTER_HORIZONTAL : right ? Gravity.END : Gravity.START));
        String w = fontWeight(Liquid.clockFont(host));
        dateText = new TextView(host);
        Ui.textSize(dateText, ts * 0.5f);
        dateText.setTypeface(Ui.font(host, "regular".equals(w) ? "medium" : w));
        dateText.setLetterSpacing(0.14f);
        dateText.setSingleLine(true);
        dateText.setTextColor(0xE6FFFFFF);
        dateText.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        timeText = new TextView(host);
        Ui.textSize(timeText, ts);
        timeText.setTypeface(Ui.font(host, w));
        timeText.setTextColor(0xFFFFFFFF);
        timeText.setShadowLayer(dp(10), 0, dp(2), 0x66000000);
        timeText.setIncludeFontPadding(false);
        timeText.setSingleLine(true);
        col.addView(dateText);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(2);
        col.addView(timeText, tl);
        if (!hidden) {
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (beside) cl.leftMargin = dp(16);
            else cl.topMargin = dp(10);
            box.addView(col, cl);
        }

        int g = (pos.startsWith("t") ? Gravity.TOP : Gravity.CENTER_VERTICAL)
                | (centre ? Gravity.CENTER_HORIZONTAL : right ? Gravity.END : Gravity.START);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, g);
        lp.leftMargin = dp(60);
        lp.rightMargin = dp(60);
        lp.topMargin = pos.startsWith("t") ? dp(36) : 0;
        if (!pos.startsWith("t")) lp.bottomMargin = dp(60);  // keep clear of the dock
        clockBox.addView(box, lp);
        updateClock();
    }

    private void updateClock() {
        if (timeText == null) return;
        Date now = new Date();
        timeText.setText(new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(now));
        dateText.setText(new SimpleDateFormat("EEEE  ·  d MMMM", Locale.ENGLISH).format(now).toUpperCase(Locale.ENGLISH));
    }

    // ================================================================== widgets

    GlassPanel glass(String part, float r) {
        return sh.liquidGlass(part, r);
    }

    TextView text(float size, String weight, int color) {
        TextView t = new TextView(host);
        Ui.textSize(t, size);
        t.setTypeface(Ui.font(host, weight));
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    GradientDrawable ring(float r) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(r);
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(1.6f), Color.WHITE);
        return g;
    }

    private void buildWidgets() {
        widgetCol = new LinearLayout(host);
        widgetCol.setOrientation(LinearLayout.VERTICAL);
        widgetCol.setClipChildren(false);
        widgetCol.setClipToPadding(false);

        // ---- weather
        weatherCard = new FrameLayout(host);
        weatherGlass = glass(Liquid.WIDGETS, dp(20));
        weatherCard.setBackground(weatherGlass);
        weatherCard.setPadding(dp(17), dp(15), dp(17), dp(14));
        weatherCard.setFocusable(true);
        weatherCard.setClickable(true);
        LinearLayout wv = new LinearLayout(host);
        wv.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(host);
        top.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = new LinearLayout(host);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView cap = text(9f, "semibold", 0xD9FFFFFF);
        cap.setLetterSpacing(0.12f);
        cap.setText("OUTSIDE");
        wxTemp = text(40f, "regular", Color.WHITE);
        wxTemp.setLetterSpacing(-0.03f);
        wxLabel = text(11f, "medium", Color.WHITE);
        wxHiLo = text(9f, "regular", 0xD9FFFFFF);
        left.addView(cap);
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g2.topMargin = dp(3);
        left.addView(wxTemp, g2);
        LinearLayout.LayoutParams g3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g3.topMargin = dp(4);
        left.addView(wxLabel, g3);
        LinearLayout.LayoutParams g4 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        g4.topMargin = dp(3);
        left.addView(wxHiLo, g4);
        top.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        wxIcon = new ImageView(host);
        top.addView(wxIcon, new LinearLayout.LayoutParams(dp(46), dp(46)));
        wv.addView(top);
        View rule = new View(host);
        rule.setBackgroundColor(0x38FFFFFF);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        rl.topMargin = dp(12);
        wv.addView(rule, rl);
        wxHours = new LinearLayout(host);
        wxHours.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(10);
        wv.addView(wxHours, hl);
        weatherCard.addView(wv);
        weatherCard.setOnClickListener(v -> host.showWeather());
        weatherCard.setOnFocusChangeListener((v, has) -> focusCard(v, weatherGlass, has));

        // ---- now playing
        playCard = new FrameLayout(host);
        playGlass = glass(Liquid.WIDGETS, dp(20));
        playCard.setBackground(playGlass);
        playCard.setPadding(dp(12), dp(12), dp(14), dp(12));
        playCard.setFocusable(true);
        playCard.setClickable(true);
        LinearLayout pr = new LinearLayout(host);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        npArt = new ImageView(host);
        npArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        npArt.setOutlineProvider(host.roundedOutline(new float[]{dp(12)}));
        npArt.setClipToOutline(true);
        pr.addView(npArt, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout pt = new LinearLayout(host);
        pt.setOrientation(LinearLayout.VERTICAL);
        TextView pcap = text(8f, "semibold", 0xD9FFFFFF);
        pcap.setLetterSpacing(0.12f);
        pcap.setText("NOW PLAYING");
        npTitle = text(12f, "semibold", Color.WHITE);
        npSub = text(9f, "regular", 0xD9FFFFFF);
        pt.addView(pcap);
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t1.topMargin = dp(4);
        pt.addView(npTitle, t1);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2.topMargin = dp(3);
        pt.addView(npSub, t2);
        FrameLayout bar = new FrameLayout(host);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(1.5f));
        track.setColor(0x40FFFFFF);
        bar.setBackground(track);
        npBarFill = new View(host);
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(1.5f));
        fill.setColor(Color.WHITE);
        npBarFill.setBackground(fill);
        bar.addView(npBarFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        npBar = bar;
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        bl.topMargin = dp(6);
        pt.addView(bar, bl);
        LinearLayout.LayoutParams ptl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ptl.leftMargin = dp(11);
        ptl.rightMargin = dp(10);
        pr.addView(pt, ptl);
        npButton = new ImageView(host);
        npButton.setScaleType(ImageView.ScaleType.CENTER);
        npButton.setBackground(glass(Liquid.BUTTONS, dp(14)));
        pr.addView(npButton, new LinearLayout.LayoutParams(dp(28), dp(28)));
        playCard.addView(pr);
        playCard.setOnClickListener(v -> togglePlay());
        playCard.setOnLongClickListener(v -> {
            openPlayingApp();
            return true;
        });
        playCard.setOnFocusChangeListener((v, has) -> focusCard(v, playGlass, has));

        widgetCol.addView(weatherCard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(11);
        widgetCol.addView(playCard, pl);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.rightMargin = dp(40);
        lp.topMargin = dp(84);
        sh.overlay.addView(widgetCol, lp);
        refreshWeather();
        playCard.setVisibility(View.GONE);
    }

    private void focusCard(View v, GlassPanel g, boolean has) {
        g.setLit(has);
        v.setForeground(has ? ring(dp(20)) : null);
        v.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f)
                .setDuration(has ? 420 : 200).setInterpolator(has ? MainActivity.SPRING : MainActivity.EASE).start();
    }

    void refreshWeather() {
        if (weatherCard == null) return;
        Weather.Data d = Weather.enabled(host) ? Weather.cached(host) : null;
        if (d == null) {
            weatherCard.setVisibility(View.GONE);
            return;
        }
        weatherCard.setVisibility(View.VISIBLE);
        wxTemp.setText(d.temp + "°");
        wxLabel.setText(Weather.describe(host, d.code, d.day));
        wxHiLo.setText("H " + d.max + "°   L " + d.min + "°");
        wxIcon.setImageBitmap(WeatherIcons.get(d.code, d.day, dp(46)));
        wxHours.removeAllViews();
        int n = 0;
        for (int i = 1; i < d.hours.size() && n < 5; i++, n++) {
            Weather.Hour h = d.hours.get(i);
            LinearLayout c = new LinearLayout(host);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView a = text(9f, "regular", 0xD9FFFFFF);
            a.setText(h.label);
            TextView b = text(9f, "semibold", Color.WHITE);
            b.setText(h.temp + "°");
            c.addView(a);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = dp(3);
            c.addView(b, bl);
            wxHours.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        wxHours.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        String kind = WeatherIcons.kind(d.code);
        int fx = !Liquid.weatherFx(host) ? 0
                : ("rain".equals(kind) || "drizzle".equals(kind) || "thunder".equals(kind)) ? 1
                : ("snow".equals(kind) || d.temp <= 0) ? 2 : 0;
        GlassPanel.setWeather(fx);
        weatherCard.invalidate();
        if (playCard != null) playCard.invalidate();
        sh.invalidateSceneGlass();
    }

    private MediaController findController() {
        try {
            MediaSessionManager msm = host.getSystemService(MediaSessionManager.class);
            if (msm == null) return null;
            List<MediaController> list = msm.getActiveSessions(new ComponentName(host, MediaWatchService.class));
            MediaController any = null;
            for (MediaController mc : list) {
                if (mc == null || mc.getMetadata() == null) continue;
                PlaybackState s = mc.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) return mc;
                if (any == null && s != null && s.getState() == PlaybackState.STATE_PAUSED) any = mc;
            }
            return any;
        } catch (Throwable t) {
            return null;  // no notification access
        }
    }

    private void updateNowPlaying() {
        if (playCard == null) return;
        MediaController mc = findController();
        npController = mc;
        if (mc == null) {
            if (playCard.hasFocus()) sh.focusDock(sh.focusedDockIdx);
            playCard.setVisibility(View.GONE);
            return;
        }
        MediaMetadata m = mc.getMetadata();
        String title = first(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        String sub = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM);
        String app = appLabel(mc.getPackageName());
        npTitle.setText(title != null ? title : app);
        npSub.setText(sub != null ? sub : app);
        Bitmap art = null;
        try {
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        } catch (Throwable ignored) {
        }
        if (art != null) {
            npArt.setImageBitmap(art);
            npArt.setBackground(null);
        } else {
            npArt.setImageDrawable(null);
            sh.applyLiquidIconTo(npArt, entryFor(mc.getPackageName()), dp(52));
        }
        PlaybackState s = mc.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        npButton.setImageBitmap(playing ? pauseGlyph(dp(11)) : playGlyph(dp(11)));
        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (s != null && dur > 0) {
            long pos = s.getPosition();
            if (playing && s.getLastPositionUpdateTime() > 0) {
                pos += (long) ((android.os.SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
            float f = Math.max(0f, Math.min(1f, pos / (float) dur));
            npBar.setVisibility(View.VISIBLE);
            npBar.post(() -> {
                ViewGroup.LayoutParams lp = npBarFill.getLayoutParams();
                lp.width = Math.round(npBar.getWidth() * f);
                npBarFill.setLayoutParams(lp);
            });
        } else {
            npBar.setVisibility(View.GONE);
        }
        playCard.setVisibility(View.VISIBLE);
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            try {
                CharSequence v = m.getText(k);
                if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    String appLabel(String pkg) {
        MainActivity.AppEntry e = entryFor(pkg);
        if (e != null && e.label != null) return e.label;
        try {
            return host.getPackageManager().getApplicationLabel(host.getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private final java.util.Map<String, MainActivity.AppEntry> extraEntries = new java.util.HashMap<>();

    MainActivity.AppEntry entryFor(String pkg) {
        if (pkg == null) return null;
        for (MainActivity.AppEntry e : host.all.values()) if (pkg.equals(e.pkg)) return e;
        MainActivity.AppEntry x = extraEntries.get(pkg);
        if (x != null) return x;
        // Apps without a launcher entry (system services posting notifications): their own icon.
        try {
            android.content.pm.PackageManager pm = host.getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            x = new MainActivity.AppEntry();
            x.key = "notif:" + pkg;
            x.pkg = pkg;
            x.label = pm.getApplicationLabel(ai).toString();
            x.icon = pm.getApplicationIcon(ai);
            x.system = true;
            extraEntries.put(pkg, x);
            return x;
        } catch (Throwable t) {
            return null;
        }
    }

    private void togglePlay() {
        MediaController mc = npController;
        if (mc == null) return;
        try {
            PlaybackState s = mc.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) mc.getTransportControls().pause();
            else mc.getTransportControls().play();
        } catch (Throwable ignored) {
        }
        host.handler.postDelayed(this::updateNowPlaying, 350);
    }

    private void openPlayingApp() {
        MediaController mc = npController;
        if (mc == null) return;
        MainActivity.AppEntry e = entryFor(mc.getPackageName());
        if (e != null) host.launchAnimated(playCard, e);
    }

    private Bitmap playGlyph(int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        Path path = new Path();
        path.moveTo(s * 0.22f, s * 0.12f);
        path.lineTo(s * 0.88f, s * 0.5f);
        path.lineTo(s * 0.22f, s * 0.88f);
        path.close();
        c.drawPath(path, p);
        return b;
    }

    private Bitmap pauseGlyph(int s) {
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        float r = s * 0.08f;
        c.drawRoundRect(new RectF(s * 0.18f, s * 0.1f, s * 0.42f, s * 0.9f), r, r, p);
        c.drawRoundRect(new RectF(s * 0.58f, s * 0.1f, s * 0.82f, s * 0.9f), r, r, p);
        return b;
    }

    // ================================================================== dock

    /** Called after SceneHome rebuilt the dock: adds the ‹ N / N › pills and lays out the window. */
    void afterDockBuilt() {
        LinearLayout row = sh.dockTiles.isEmpty() ? null : (LinearLayout) sh.dockTiles.get(0).getParent();
        if (row == null) return;
        int size = sh.dockTiles.get(0).getLayoutParams().width;
        pillLeft = pill(size);
        pillRight = pill(size);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(29), dp(29));
        l.rightMargin = dp(6);
        l.gravity = Gravity.CENTER_VERTICAL;
        row.addView(pillLeft, 0, l);
        LinearLayout.LayoutParams r = new LinearLayout.LayoutParams(dp(29), dp(29));
        r.leftMargin = dp(6);
        r.gravity = Gravity.CENTER_VERTICAL;
        row.addView(pillRight, r);
        curMargins = new float[sh.dockTiles.size()];
        float gap = gap(size);
        for (int i = 0; i < sh.dockTiles.size(); i++) {
            View t = sh.dockTiles.get(i);
            t.setPivotX(size / 2f);
            t.setPivotY(size);
            t.setCameraDistance(8000 * host.getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams tl = (LinearLayout.LayoutParams) t.getLayoutParams();
            tl.leftMargin = Math.round(gap / 2);
            tl.rightMargin = Math.round(gap / 2);
            t.setLayoutParams(tl);
        }
        magFocus = -1;
        windowStart = -1;
        ensureWindow(sh.focusedDockIdx);
        row.setClipChildren(false);
        if (row.getParent() instanceof ViewGroup) ((ViewGroup) row.getParent()).setClipChildren(false);
        lens = new Lens();
        if (row.getBackground() == null) row.setBackground(lens);
        else lens = null;  // the row already paints something: leave it
        updateDots();
    }

    private float gap(int size) {
        return size * 26f / 132f;
    }

    private TextView pill(int size) {
        TextView t = text(8f, "semibold", Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setBackground(glass(Liquid.BUTTONS, dp(14.5f)));
        t.setVisibility(View.GONE);
        return t;
    }

    /** Keeps the dock to 10 icons: shows the window around idx and the counts either side. */
    void ensureWindow(int idx) {
        int total = sh.dockTiles.size();
        int start = total <= VISIBLE ? 0 : Math.max(0, Math.min(total - VISIBLE, idx - 4));
        if (start != windowStart) {
            windowStart = start;
            for (int i = 0; i < total; i++) {
                sh.dockTiles.get(i).setVisibility(i >= start && i < start + VISIBLE ? View.VISIBLE : View.GONE);
            }
        }
        int end = Math.min(total, windowStart + VISIBLE);
        if (pillLeft != null) {
            pillLeft.setText("‹" + windowStart);
            pillLeft.setVisibility(windowStart > 0 ? View.VISIBLE : View.GONE);
            pillRight.setText((total - end) + "›");
            pillRight.setVisibility(total - end > 0 ? View.VISIBLE : View.GONE);
        }
    }

    /** Focus moved within or out of the dock. */
    void onDockFocus(int index, boolean has) {
        if (has) {
            ensureWindow(index);
            magnify(index);
            schedulePreview(index);
        } else {
            hideDockPreview();
            sh.overlay.post(() -> {
                boolean any = false;
                for (View t : sh.dockTiles) if (t.isFocused()) any = true;
                if (!any) magnify(-1);
            });
        }
    }

    /** Laptop-dock magnification: the focused icon rises most, neighbours half as much, and so on. */
    void magnify(int f) {
        if (sh.dockTiles.isEmpty()) return;
        magFocus = f;
        int size = sh.dockTiles.get(0).getLayoutParams().width;
        String mode = Liquid.magnify(host);
        boolean tilt = Liquid.tilt(host);
        // {lift, scale, extra margin each side} in design pixels of a 132 px icon
        float[][] strong = {{26, 1.38f, 26}, {14, 1.2f, 13}, {6, 1.08f, 5}, {2, 1.02f, 1}};
        float[][] subtle = {{16, 1.25f, 17}, {8, 1.1f, 7}, {3, 1.03f, 2}, {0, 1f, 0}};
        float[][] off = {{6, 1.12f, 8}, {0, 1f, 0}, {0, 1f, 0}, {0, 1f, 0}};
        float[][] curve = "off".equals(mode) ? off : "subtle".equals(mode) ? subtle : strong;
        float k = size / 132f;
        final float[] from = curMargins.clone();
        final float[] to = new float[sh.dockTiles.size()];
        for (int i = 0; i < sh.dockTiles.size(); i++) {
            View t = sh.dockTiles.get(i);
            int d = f < 0 ? 99 : Math.abs(i - f);
            float[] c = d < 4 ? curve[d] : new float[]{0, 1f, 0};
            to[i] = c[2] * k;
            boolean focused = d == 0;
            t.animate().scaleX(c[1]).scaleY(c[1]).translationY(-c[0] * k)
                    .rotationX(focused && tilt ? 5f : 0f).rotationY(focused && tilt ? -6f : 0f)
                    .setDuration(420).setInterpolator(MainActivity.SPRING).start();
            t.setForeground(focused ? new Shine(tilt, "tile".equals(Liquid.iconStyle(host))) : null);
            t.setZ(focused ? dp(4) : 0);
        }
        if (marginAnim != null) marginAnim.cancel();
        final float base = gap(size) / 2f;
        marginAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        marginAnim.setDuration(420);
        marginAnim.setInterpolator(MainActivity.SPRING);
        marginAnim.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            for (int i = 0; i < sh.dockTiles.size() && i < from.length; i++) {
                float mm = from[i] + (to[i] - from[i]) * p;
                curMargins[i] = mm;
                View t = sh.dockTiles.get(i);
                LinearLayout.LayoutParams tl = (LinearLayout.LayoutParams) t.getLayoutParams();
                int v = Math.round(base + mm);
                if (tl.leftMargin != v || tl.rightMargin != v) {
                    tl.leftMargin = v;
                    tl.rightMargin = v;
                    t.setLayoutParams(tl);
                }
            }
        });
        if (curMargins.length != to.length) curMargins = new float[to.length];
        marginAnim.start();
        if (lens != null) lens.glideTo(f >= 0 && f < sh.dockTiles.size() ? sh.dockTiles.get(f) : null);
    }

    private Lens lens;

    /**
     * The gliding lens: one soft glass glow behind the dock icons that slides to the focused icon
     * on a spring, growing with it. Drawn as the dock row's background (no bitmaps, no layers).
     */
    private final class Lens extends Drawable {
        final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.Matrix m = new android.graphics.Matrix();
        final android.graphics.RadialGradient unit = new android.graphics.RadialGradient(0, 0, 1f,
                new int[]{0x40FFFFFF, 0x1FFFFFFF, 0x00FFFFFF}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        final RectF box = new RectF();
        float cx, cy, w, alpha;
        float fromX, fromY, fromW, fromA;
        View target;
        android.animation.ValueAnimator anim;

        Lens() {
            glow.setShader(unit);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, dp(0.8f)));
        }

        void glideTo(View t) {
            target = t;
            if (anim != null) anim.cancel();
            fromX = cx;
            fromY = cy;
            fromW = w;
            fromA = alpha;
            if (fromA <= 0.01f && t != null) {
                // Appearing: start where the icon is, just fade in.
                fromX = tx(t);
                fromY = ty(t);
                fromW = tw(t);
            }
            anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(460);
            anim.setInterpolator(MainActivity.SPRING);
            anim.addUpdateListener(a -> {
                float p = (float) a.getAnimatedValue();
                float q = Math.min(1f, a.getAnimatedFraction() * 2.5f);
                if (target != null) {
                    // Follow the icon live: the dock's margins and scales are animating too.
                    cx = fromX + (tx(target) - fromX) * p;
                    cy = fromY + (ty(target) - fromY) * p;
                    w = fromW + (tw(target) - fromW) * p;
                    alpha = fromA + (1f - fromA) * q;
                } else {
                    alpha = fromA * (1f - q);
                }
                invalidateSelf();
            });
            anim.start();
        }

        private float tx(View t) {
            return t.getLeft() + t.getWidth() / 2f + t.getTranslationX();
        }

        private float ty(View t) {
            // Tiles scale from their bottom edge and lift by translationY.
            return t.getTop() + t.getHeight() * (1f - t.getScaleY() / 2f) + t.getTranslationY();
        }

        private float tw(View t) {
            return t.getWidth() * t.getScaleX();
        }

        @Override public void draw(Canvas c) {
            if (alpha <= 0.01f || w <= 0) return;
            float r = w * 0.86f;
            m.setScale(r, r);
            m.postTranslate(cx, cy);
            unit.setLocalMatrix(m);
            glow.setAlpha(Math.round(255 * alpha));
            c.drawCircle(cx, cy, r, glow);
            float h = w * 0.62f;
            box.set(cx - h, cy - h, cx + h, cy + h);
            rim.setColor(Color.argb(Math.round(70 * alpha), 255, 255, 255));
            c.drawRoundRect(box, h * 0.5f, h * 0.5f, rim);
        }

        @Override public void setAlpha(int a) {}

        @Override public void setColorFilter(ColorFilter cf) {}

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Focused icon: a thin white ring and a diagonal shine across the glass. */
    private static final class Shine extends Drawable {
        final boolean shine, tile;
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), band = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();
        final Path clip = new Path();

        Shine(boolean shine, boolean tile) {
            this.shine = shine;
            this.tile = tile;
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(0x99FFFFFF);
        }

        @Override protected void onBoundsChange(Rect b) {
            float s = b.width();
            float in = tile ? 0 : s * 0.04f;
            box.set(b.left + in, b.top + in, b.right - in, b.bottom - in);
            float r = tile ? s * 0.27f : (s - 2 * in) * 0.27f / 0.92f;
            clip.reset();
            clip.addRoundRect(box, r, r, Path.Direction.CW);
            ring.setStrokeWidth(Math.max(1f, s / 88f));
            // 112° band: transparent → white → transparent, across the icon
            band.setShader(new LinearGradient(box.left, box.top + box.height() * 0.1f, box.right, box.bottom - box.height() * 0.1f,
                    new int[]{0x00FFFFFF, 0x00FFFFFF, 0x8CFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.28f, 0.42f, 0.56f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            if (shine) {
                c.save();
                c.clipPath(clip);
                c.drawRect(box, band);
                c.restore();
            }
            c.drawPath(clip, ring);
        }

        @Override public void setAlpha(int a) {}

        @Override public void setColorFilter(ColorFilter cf) {}

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ================================================================== keys

    private boolean visible(View v) {
        return v != null && v.getVisibility() == View.VISIBLE && v.isShown();
    }

    /** Remote navigation between the dock, the widget cards and the top buttons. */
    boolean dispatchKey(KeyEvent ev) {
        int k = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        if (dockPreview != null && down) hideDockPreview();
        if (saverLayer != null) {
            if (!down) hideSaver();
            return true;
        }
        if (infoLayer != null) {
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                if (!down) closeInfo();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                int i = infoButtons.indexOf(host.getCurrentFocus());
                int j = i + (k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (down && i >= 0 && j >= 0 && j < infoButtons.size()) infoButtons.get(j).requestFocus();
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            return false;
        }
        if (sh.sheetLayer != null || sh.moveDockIdx >= 0) return false;
        View f = host.getCurrentFocus();
        boolean inDock = sh.dockTiles.contains(f);
        boolean topBtn = f == sh.weatherBtn || f == sh.moonBtn;
        int dashIdx = sh.dashboardCards.indexOf(f);
        if (dashIdx >= 0) {
            lastDashIdx = dashIdx;
            if (k == KeyEvent.KEYCODE_DPAD_UP) {
                if (down) focusFirst(playCard, weatherCard, sh.moonBtn);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) sh.focusDock(sh.focusedDockIdx);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (com.techgenius.tvoslauncher.HomeDashboard.isMovingWidget()) return false;
                int j = dashIdx + (k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                if (down && j >= 0 && j < sh.dashboardCards.size()) sh.dashboardCards.get(j).requestFocus();
                return true;
            }
            return false;
        }
        if (k == KeyEvent.KEYCODE_DPAD_UP) {
            if (inDock) {
                if (down) {
                    if (dashShowing()) focusDash();
                    else focusFirst(playCard, weatherCard, sh.moonBtn);
                }
                return true;
            }
            if (f == playCard) {
                if (down) focusFirst(weatherCard, sh.moonBtn);
                return true;
            }
            if (f == weatherCard) {
                if (down) sh.moonBtn.requestFocus();
                return true;
            }
            if (topBtn) return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (topBtn) {
                if (down) {
                    if (visible(weatherCard)) weatherCard.requestFocus();
                    else if (visible(playCard)) playCard.requestFocus();
                    else sh.focusDock(sh.focusedDockIdx);
                }
                return true;
            }
            if (f == weatherCard) {
                if (down) {
                    if (visible(playCard)) playCard.requestFocus();
                    else if (dashShowing()) focusDash();
                    else sh.focusDock(sh.focusedDockIdx);
                }
                return true;
            }
            if (f == playCard) {
                if (down) {
                    if (dashShowing()) focusDash();
                    else sh.focusDock(sh.focusedDockIdx);
                }
                return true;
            }
            if (inDock) return true;
        }
        if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && (f == weatherCard || f == playCard)) {
            if (down && k == KeyEvent.KEYCODE_DPAD_LEFT) sh.focusDock(sh.focusedDockIdx);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- cards above the dock

    private int lastDashIdx;

    /** Puts the Night Scenes cards (cricket, news, top movies…) in a row just above the dock. */
    void placeDashboard(View box) {
        if (!(box.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) box.getLayoutParams();
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.topMargin = 0;
        lp.leftMargin = dp(40);
        lp.rightMargin = dp(40);
        lp.bottomMargin = dp(150);
        box.setLayoutParams(lp);
        box.post(() -> {
            // Sit 16 dp above the dock's row, wherever the dock ends up.
            if (sh.dockTiles.isEmpty()) return;
            View row = (View) sh.dockTiles.get(0).getParent();
            int top = 0;
            View v = row;
            while (v != null && v != sh.overlay) {
                top += v.getTop();
                v = v.getParent() instanceof View ? (View) v.getParent() : null;
            }
            if (v == null || sh.overlay.getHeight() <= 0) return;
            int want = sh.overlay.getHeight() - top + dp(16);
            if (want > 0 && want != lp.bottomMargin) {
                lp.bottomMargin = want;
                box.setLayoutParams(lp);
            }
            box.post(() -> avoidClock(box));
        });
    }

    /** Lifts the clock if its date line would run into the cards. */
    private void avoidClock(View box) {
        if (clockBox == null || clockBox.getChildCount() == 0) return;
        View c = clockBox.getChildAt(0);
        float clockBottom = c.getBottom();   // clockBox fills the screen, as the overlay does
        float cardsTop = sh.overlay.getHeight() - ((FrameLayout.LayoutParams) box.getLayoutParams()).bottomMargin - box.getHeight();
        float lift = Math.max(0, clockBottom - cardsTop + dp(14));
        lift = Math.min(lift, Math.max(0, c.getTop() - dp(24)));
        c.animate().translationY(-lift).setDuration(260).setInterpolator(MainActivity.LIFT).start();
    }

    private boolean dashShowing() {
        return sh.isDashboardShowing() && !sh.dashboardCards.isEmpty();
    }

    private void focusDash() {
        int n = sh.dashboardCards.size();
        sh.dashboardCards.get(Math.max(0, Math.min(n - 1, lastDashIdx))).requestFocus();
    }

    private void focusFirst(View... vs) {
        for (View v : vs) {
            if (visible(v)) {
                v.requestFocus();
                return;
            }
        }
    }

    // ================================================================== All apps

    private LinearLayout gridBox;
    private ScrollView gridScroll;
    private final List<View> gridCells = new ArrayList<>();

    void showAllApps(View returnTo) {
        if (sh.sheetLayer != null) return;
        FrameLayout layer = new FrameLayout(host);
        layer.setClickable(true);
        layer.setBackground(fullGlass());
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        TextView title = text(22f, "display", Color.WHITE);
        title.setText("All apps");
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        tl.leftMargin = dp(60);
        tl.topMargin = dp(36);
        layer.addView(title, tl);
        TextView hint = text(9f, "regular", 0xB3FFFFFF);
        hint.setText("Hold OK for options");
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        hl.rightMargin = dp(60);
        hl.topMargin = dp(46);
        layer.addView(hint, hl);

        gridScroll = new ScrollView(host);
        gridScroll.setVerticalScrollBarEnabled(false);
        gridScroll.setVerticalFadingEdgeEnabled(false);
        gridScroll.setFadingEdgeLength(0);
        gridScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        gridScroll.setClipToPadding(false);
        gridScroll.setPadding(0, dp(18), 0, dp(40));
        gridBox = new LinearLayout(host);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        gridBox.setClipChildren(false);
        gridBox.setClipToPadding(false);
        gridBox.setPadding(dp(60), 0, dp(60), 0);
        gridScroll.addView(gridBox);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gl.topMargin = dp(76);
        layer.addView(gridScroll, gl);

        sh.sheetLayer = layer;
        sh.sheetReturnFocus = returnTo;
        sh.overlay.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        host.root.addView(layer, full);
        layer.setAlpha(0f);
        layer.animate().alpha(1f).setDuration(180).start();
        fillGrid(0);
    }

    private Drawable fullGlass() {
        Liquid.Look l = Liquid.look(host, Liquid.PANEL);
        l.trans = Math.min(l.trans, 55);
        l.blur = Math.max(l.blur, 80);
        l.refr = 0;
        return new GlassPanel(sh.liquidSource, l, 0, host.screenW, host.screenH, host.unit);
    }

    private List<MainActivity.AppEntry> visibleApps() {
        List<MainActivity.AppEntry> list = new ArrayList<>();
        list.addAll(host.apps);
        list.addAll(host.otherApps);
        Collections.sort(list, (a, b) -> String.valueOf(a.label).compareToIgnoreCase(String.valueOf(b.label)));
        return list;
    }

    private void fillGrid(int focusIdx) {
        gridBox.removeAllViews();
        gridCells.clear();
        List<MainActivity.AppEntry> vis = visibleApps();
        List<MainActivity.AppEntry> hid = new ArrayList<>(host.hiddenApps);
        addGridRows(vis, false);
        if (!hid.isEmpty()) {
            TextView h = text(10f, "semibold", 0xC8C9D4FF);
            h.setLetterSpacing(0.14f);
            h.setText("HIDDEN APPS");
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hl.topMargin = dp(22);
            hl.bottomMargin = dp(10);
            gridBox.addView(h, hl);
            addGridRows(hid, true);
        }
        if (!gridCells.isEmpty()) {
            View target = gridCells.get(Math.max(0, Math.min(focusIdx, gridCells.size() - 1)));
            target.post(target::requestFocus);
        }
    }

    private void addGridRows(List<MainActivity.AppEntry> list, boolean hidden) {
        int cols = 8;
        int cellW = (host.screenW - dp(120)) / cols;
        int icon = dp(46);
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(host);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                gridBox.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final MainActivity.AppEntry a = list.get(i);
            LinearLayout cell = new LinearLayout(host);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, dp(12), 0, dp(8));
            cell.setClipToPadding(false);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setLongClickable(true);
            cell.setClipChildren(false);
            ImageView iv = new ImageView(host);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            sh.applyLiquidIconTo(iv, a, icon);
            if (hidden) iv.setAlpha(0.5f);
            cell.addView(iv, new LinearLayout.LayoutParams(icon, icon));
            TextView lab = text(9f, "medium", hidden ? 0x99FFFFFF : 0xE6FFFFFF);
            lab.setGravity(Gravity.CENTER);
            lab.setText(a.label);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(cellW - dp(10), ViewGroup.LayoutParams.WRAP_CONTENT);
            ll.topMargin = dp(7);
            cell.addView(lab, ll);
            boolean inDock = host.apps.contains(a);
            if (inDock && !hidden) {
                View dot = new View(host);
                GradientDrawable dd = new GradientDrawable();
                dd.setShape(GradientDrawable.OVAL);
                dd.setColor(0xCCFFFFFF);
                dot.setBackground(dd);
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(3), dp(3));
                dl.topMargin = dp(4);
                cell.addView(dot, dl);
            }
            cell.setOnFocusChangeListener((v, has) -> {
                iv.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).translationY(has ? -dp(3) : 0)
                        .setDuration(220).setInterpolator(MainActivity.LIFT).start();
                lab.setTextColor(has ? Color.WHITE : hidden ? 0x99FFFFFF : 0xE6FFFFFF);
                if (has) scrollIntoView(v);
            });
            final int idx = gridCells.size();
            cell.setOnClickListener(v -> {
                if (hidden) {
                    gridMenu(a, v, idx, true);
                    return;
                }
                sh.closeSheetImmediate();
                host.launchAnimated(null, a);
            });
            cell.setOnLongClickListener(v -> {
                gridMenu(a, v, idx, hidden);
                return true;
            });
            row.addView(cell, new LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT));
            gridCells.add(cell);
        }
    }

    private void scrollIntoView(View v) {
        View row = (View) v.getParent();
        if (row == null || gridScroll == null) return;
        int top = row.getTop(), bottom = row.getBottom();
        int sy = gridScroll.getScrollY(), h = gridScroll.getHeight() - gridScroll.getPaddingBottom();
        if (top < sy + dp(10)) gridScroll.smoothScrollTo(0, Math.max(0, top - dp(18)));
        else if (bottom > sy + h - dp(10)) gridScroll.smoothScrollTo(0, bottom - h + dp(20));
    }

    private void gridMenu(MainActivity.AppEntry a, View cell, int idx, boolean hidden) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (hidden) {
            labels.add("Unhide");
            actions.add(() -> {
                host.setHidden(a, false);
                afterChange(idx);
            });
        } else {
            labels.add("Open");
            actions.add(() -> {
                sh.closeSheetImmediate();
                host.launchAnimated(null, a);
            });
            if (!host.isSettings(a)) {
                if (host.apps.contains(a)) {
                    labels.add("Remove from dock");
                    actions.add(() -> {
                        host.setOtherApp(a, true);
                        afterChange(idx);
                    });
                } else {
                    labels.add("Add to dock");
                    actions.add(() -> {
                        host.setOtherApp(a, false);
                        afterChange(idx);
                    });
                }
                labels.add("Hide");
                actions.add(() -> {
                    host.setHidden(a, true);
                    afterChange(idx);
                });
            }
        }
        if (a.pkg != null && !MainActivity.INPUTS_KEY.equals(a.key)) {
            labels.add("App info");
            actions.add(() -> showAppInfo(a, cell));
        }
        host.showMenu(cell, a.label, labels, actions, -1);
    }

    private void afterChange(int idx) {
        sh.rebuildDock();
        if (gridBox != null && sh.sheetLayer != null) fillGrid(idx);
    }

    // ================================================================== App info

    void showAppInfo(MainActivity.AppEntry a, View returnTo) {
        if (a == null || a.pkg == null) return;
        closeInfo();
        infoReturn = returnTo;
        FrameLayout layer = new FrameLayout(host);
        layer.setClickable(true);
        layer.setBackgroundColor(0x99060810);
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(24), dp(22), dp(24), dp(20));
        Liquid.Look l = Liquid.look(host, Liquid.PANEL);
        l.trans = Math.min(l.trans, 45);
        card.setBackground(new GlassPanel(sh.liquidSource, l, dp(22), host.screenW, host.screenH, host.unit));
        card.setClipChildren(false);

        ImageView iv = new ImageView(host);
        sh.applyLiquidIconTo(iv, a, dp(54));
        card.addView(iv, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView name = text(16f, "semibold", Color.WHITE);
        name.setText(a.label);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.topMargin = dp(10);
        card.addView(name, nl);
        String version = "";
        try {
            PackageInfo pi = host.getPackageManager().getPackageInfo(a.pkg, 0);
            version = "Version " + pi.versionName;
        } catch (Throwable ignored) {
        }
        TextView ver = text(9f, "regular", 0xB3FFFFFF);
        ver.setText(version.isEmpty() ? a.pkg : version + "  ·  " + a.pkg);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vl.topMargin = dp(4);
        card.addView(ver, vl);

        TextView mem = text(11f, "medium", Color.WHITE);
        mem.setText("Memory: checking…");
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = dp(14);
        card.addView(mem, ml);

        LinearLayout btns = new LinearLayout(host);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setClipChildren(false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(18);
        card.addView(btns, bl);
        View first = infoButton(btns, "Open", () -> {
            closeInfo();
            if (sh.sheetLayer != null) sh.closeSheetImmediate();
            host.launchAnimated(null, a);
        });
        infoButton(btns, "Stop app", () -> {
            stopApp(a.pkg);
            mem.setText("Memory: stopping…");
            host.handler.postDelayed(() -> readMemory(a.pkg, mem), 1500);
        });
        if (!a.system) {
            infoButton(btns, "Uninstall", () -> {
                closeInfo();
                host.startSafe(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
            });
        }
        infoButton(btns, "Android settings", () -> {
            closeInfo();
            host.startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg)));
        });

        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        layer.addView(card, cl);
        infoLayer = layer;
        sh.overlay.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (sh.sheetLayer != null) sh.sheetLayer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        host.root.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layer.setAlpha(0f);
        layer.animate().alpha(1f).setDuration(160).start();
        first.post(first::requestFocus);
        readMemory(a.pkg, mem);
    }

    private View infoButton(LinearLayout row, String label, Runnable r) {
        TextView b = text(10f, "semibold", Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        GlassPanel g = glass(Liquid.BUTTONS, dp(15));
        b.setBackground(g);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(v -> r.run());
        b.setOnFocusChangeListener((v, has) -> {
            g.setLit(has);
            b.setForeground(has ? ring(dp(15)) : null);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(180).setInterpolator(MainActivity.LIFT).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (row.getChildCount() > 0) lp.leftMargin = dp(8);
        row.addView(b, lp);
        infoButtons.add(b);
        return b;
    }

    /** Memory from Android's own report (dumpsys meminfo), read over the launcher's ADB link. */
    private void readMemory(String pkg, TextView out) {
        host.io.execute(() -> {
            String text;
            try {
                String r = LocalAdb.execShellOutput(host, "dumpsys meminfo " + pkg + " | grep 'TOTAL PSS'");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("TOTAL PSS:\\s*(\\d+)").matcher(r == null ? "" : r);
                if (m.find()) {
                    long kb = Long.parseLong(m.group(1));
                    text = String.format(Locale.ENGLISH, "Memory: %d MB · running", Math.round(kb / 1024.0));
                } else {
                    text = "Not running";
                }
            } catch (Throwable t) {
                text = "Memory: unavailable (ADB link off)";
            }
            final String fin = text;
            host.handler.post(() -> {
                if (out.isAttachedToWindow()) out.setText(fin);
            });
        });
    }

    private void stopApp(String pkg) {
        try {
            ActivityManager am = (ActivityManager) host.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(pkg);
        } catch (Throwable ignored) {
        }
        LocalAdb.forceStopPackagesAsync(host, Collections.singletonList(pkg));
    }

    void closeInfo() {
        if (infoLayer == null) return;
        FrameLayout l = infoLayer;
        infoLayer = null;
        infoButtons.clear();
        if (sh.sheetLayer != null) sh.sheetLayer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        else sh.overlay.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        l.animate().alpha(0f).setDuration(140).withEndAction(() -> host.root.removeView(l)).start();
        View r = infoReturn;
        infoReturn = null;
        if (r != null && r.isAttachedToWindow()) r.requestFocus();
        else if (sh.sheetLayer == null) sh.focusDock(sh.focusedDockIdx);
    }

    boolean isInfoOpen() {
        return infoLayer != null;
    }

    // ================================================================== notifications

    private TextView badge;
    private FrameLayout banner;
    /** Set by the Control Panel tile when it was used over another app: open the list on return. */
    static boolean pendingNotifications;
    /** The remote's Settings button over another app: open the Control Panel on return. */
    static boolean pendingControlPanel;
    static String pendingRecent;

    /** The remote's Settings button (from the accessibility service): Control Panel, with pkg highlighted. */
    static void openControlPanelFor(android.content.Context c, String pkg) {
        String own = c.getPackageName();
        String target = pkg == null || pkg.equals(own) || Perms.isAnySystemLauncherPackage(c, pkg) ? null : pkg;
        MainActivity m = MainActivity.activeInstance;
        if (m != null && m.resumed && m.sceneHome != null && m.sceneHome.liquid != null) {
            SceneHome s = m.sceneHome;
            if (s.isSheetOpen()) {
                s.closeSheet();
                return;
            }
            s.liquid.control.showControlPanel(s.moonBtn, false, target);
            return;
        }
        // Over another app: show the panel right there, without going to Home first.
        if (m != null && m.sceneHome != null && m.sceneHome.liquid != null
                && m.sceneHome.liquid.control.showOverApp(target)) {
            return;
        }
        pendingControlPanel = true;
        pendingRecent = target;
        try {
            c.startActivity(MainActivity.homeIntent(c)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Throwable ignored) {
        }
    }

    /** Set by Settings › Appearance › Liquid Glass look & feel: open the glass panel over Home. */
    static boolean pendingLookAndFeel;
    /** Screensaver tile used over an app: start the screensaver once Home is in front. */
    static boolean pendingSaver;

    private void setupNotifications() {
        badge = text(8f, "semibold", Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(7));
        bg.setColor(0xFFFF4D5E);
        bg.setStroke(dp(1.5f), 0x990A0E1E);
        badge.setBackground(bg);
        badge.setMinWidth(dp(14));
        badge.setVisibility(View.GONE);
        badge.setElevation(dp(30));
        sh.overlay.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(14)));
        if (sh.moonBtn != null) {
            sh.moonBtn.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeBadge());
            sh.moonBtn.setOnClickListener(v -> control.showControlPanel(v, false));
            sh.moonBtn.setOnLongClickListener(v -> {
                control.showControlPanel(v, true);
                return true;
            });
        }
        Notifs.listener = posted -> {
            updateBadge();
            if (posted != null && host.resumed && sh.sheetLayer == null && infoLayer == null && saverLayer == null) showBanner(posted);
            control.onNotifsChanged();
        };
        updateBadge();
    }

    private void placeBadge() {
        if (badge == null || sh.moonBtn == null) return;
        int[] a = new int[2], o = new int[2];
        sh.moonBtn.getLocationInWindow(a);
        sh.overlay.getLocationInWindow(o);
        badge.setTranslationX(a[0] - o[0] + sh.moonBtn.getWidth() - dp(10));
        badge.setTranslationY(a[1] - o[1] - dp(3));
    }

    void updateBadge() {
        if (badge == null) return;
        int n = Notifs.unread(host);
        badge.setText(n > 9 ? "9+" : String.valueOf(n));
        badge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        placeBadge();
    }

    private String appName(String pkg) {
        return appLabel(pkg);
    }

    private void showBanner(Notifs.Item it) {
        if (banner != null) sh.overlay.removeView(banner);
        FrameLayout b = new FrameLayout(host);
        b.setBackground(glass(Liquid.PANEL, dp(18)));
        b.setPadding(dp(14), dp(10), dp(16), dp(10));
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(host);
        sh.applyLiquidIconTo(iv, entryFor(it.pkg), dp(30));
        row.addView(iv, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView app = text(8f, "semibold", 0xD9FFFFFF);
        app.setLetterSpacing(0.1f);
        app.setText(appName(it.pkg).toUpperCase(Locale.ENGLISH));
        TextView t = text(11f, "semibold", Color.WHITE);
        t.setText(it.title.isEmpty() ? it.text : it.title);
        TextView x = text(9.5f, "regular", 0xD9FFFFFF);
        x.setText(it.title.isEmpty() ? "" : it.text);
        col.addView(app);
        col.addView(t);
        if (!it.title.isEmpty() && !it.text.isEmpty()) col.addView(x);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.leftMargin = dp(10);
        row.addView(col, cl);
        b.addView(row);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(22);
        b.setElevation(dp(40));
        sh.overlay.addView(b, lp);
        banner = b;
        b.setAlpha(0f);
        b.setTranslationY(-dp(40));
        b.animate().alpha(1f).translationY(0).setDuration(320).setInterpolator(MainActivity.LIFT).start();
        host.handler.postDelayed(() -> {
            if (banner != b) return;
            b.animate().alpha(0f).translationY(-dp(30)).setDuration(260).withEndAction(() -> {
                sh.overlay.removeView(b);
                if (banner == b) banner = null;
            }).start();
        }, 4500);
    }

    /** The notifications list lives in the Control Panel. */
    void showNotifications(View returnTo) {
        control.showControlPanel(returnTo, true);
    }

    // ================================================================== screensaver

    private FrameLayout saverLayer;
    private final Runnable saverRunnable = this::showSaver;

    /** Any input: restart the screensaver countdown. */
    void resetSaver() {
        host.handler.removeCallbacks(saverRunnable);
        int min = Liquid.saverAfter(host);
        if (min > 0 && host.resumed) host.handler.postDelayed(saverRunnable, min * 60_000L);
    }

    void showSaver() {
        if (saverLayer != null || !host.resumed) return;
        if (sh.sheetLayer != null || infoLayer != null || host.menuLayer != null || host.pickerLayer != null) {
            resetSaver();
            return;
        }
        String style = Liquid.saverStyle(host);
        FrameLayout l = new FrameLayout(host);
        l.setClickable(true);
        TextView time = text("clock".equals(style) ? 64f : 48f, "regular", Color.WHITE);
        time.setLetterSpacing(-0.04f);
        TextView date = text(11f, "medium", 0xD9FFFFFF);
        date.setLetterSpacing(0.16f);
        Date now = new Date();
        time.setText(new SimpleDateFormat(Scenes.isClock24h(host) ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(now).toUpperCase(Locale.ENGLISH));
        LinearLayout col = new LinearLayout(host);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(date);
        col.addView(time);
        if ("clock".equals(style)) {
            l.setBackgroundColor(Color.BLACK);
            l.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            // move a little every minute so nothing stays in one place on the panel
            col.animate().translationX(dp(30)).translationY(dp(12)).setDuration(60_000).start();
        } else if ("glass".equals(style)) {
            col.setPadding(dp(34), dp(22), dp(34), dp(24));
            Liquid.Look gl = Liquid.look(host, Liquid.WIDGETS);
            col.setBackground(new GlassPanel(sh.liquidSource, gl, dp(28), host.screenW, host.screenH, host.unit));
            l.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            sh.overlay.animate().alpha(0f).setDuration(600).start();
        } else {  // drift: the scene slowly drifts, a small clock bottom left
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
            cl.leftMargin = dp(48);
            cl.bottomMargin = dp(40);
            col.setGravity(Gravity.START);
            l.addView(col, cl);
            sh.overlay.animate().alpha(0f).setDuration(600).start();
            sh.sceneView.setPivotX(host.screenW * 0.4f);
            sh.sceneView.setPivotY(host.screenH * 0.5f);
            sh.sceneView.animate().scaleX(1.12f).scaleY(1.12f).translationX(-dp(20)).setDuration(90_000)
                    .setInterpolator(new android.view.animation.LinearInterpolator()).start();
        }
        saverLayer = l;
        host.root.addView(l, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        l.setAlpha(0f);
        l.animate().alpha(1f).setDuration(800).start();
        // keep the clock current
        host.handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (saverLayer != l) return;
                time.setText(new SimpleDateFormat(Scenes.isClock24h(host) ? "HH:mm" : "h:mm", Locale.getDefault()).format(new Date()));
                host.handler.postDelayed(this, 15_000);
            }
        }, 15_000);
    }

    void hideSaver() {
        if (saverLayer == null) return;
        FrameLayout l = saverLayer;
        saverLayer = null;
        sh.sceneView.animate().cancel();
        sh.sceneView.animate().scaleX(1f).scaleY(1f).translationX(0).setDuration(400).start();
        sh.overlay.animate().alpha(1f).setDuration(300).start();
        l.animate().alpha(0f).setDuration(300).withEndAction(() -> host.root.removeView(l)).start();
        resetSaver();
    }

    boolean isSaverShowing() {
        return saverLayer != null;
    }

    // ================================================================== window previews

    /** A glass card with the app's last screenshot and its name; the picture is freed when it closes. */
    View previewCard(MainActivity.AppEntry a, int width, android.graphics.drawable.Drawable bg) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(7), dp(7), dp(7), dp(8));
        card.setBackground(bg);
        card.setElevation(dp(12));
        ImageView iv = new ImageView(host);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setOutlineProvider(host.roundedOutline(new float[]{dp(9)}));
        iv.setClipToOutline(true);
        iv.setBackgroundColor(0x33000000);
        int iw = width - dp(14);
        card.addView(iv, new LinearLayout.LayoutParams(iw, iw * 9 / 16));
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(host);
        sh.applyLiquidIconTo(ic, a, dp(16));
        row.addView(ic, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView t = text(9.5f, "semibold", Color.WHITE);
        int n = AppShots.windows(a.pkg);
        t.setText(a.label + (n > 1 ? "  ·  " + n + " windows" : ""));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = dp(7);
        row.addView(t, tl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.topMargin = dp(7);
        rl.leftMargin = dp(2);
        card.addView(row, rl);
        final Bitmap[] held = {null};
        final String pkg = a.pkg;
        host.io.execute(() -> {
            Bitmap b = AppShots.load(host, pkg);
            host.handler.post(() -> {
                if (b == null) return;
                if (!card.isAttachedToWindow()) {
                    b.recycle();
                    return;
                }
                held[0] = b;
                iv.setImageBitmap(b);
            });
        });
        card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}

            @Override public void onViewDetachedFromWindow(View v) {
                iv.setImageDrawable(null);
                if (held[0] != null) held[0].recycle();
                held[0] = null;
            }
        });
        return card;
    }

    /** Fade a card in, rising a little. */
    static void popIn(View v, float rise) {
        v.setAlpha(0f);
        v.setTranslationY(rise);
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(MainActivity.LIFT).start();
    }

    // ---- dock

    private View dockPreview;
    private int previewIdx = -1;
    private final Runnable showPreview = this::showDockPreview;

    /** Dots under dock icons: one per open window, up to three. */
    void updateDots() {
        for (int i = 0; i < sh.dockTiles.size() && i < sh.dockEntries.size(); i++) {
            View t = sh.dockTiles.get(i);
            MainActivity.AppEntry a = sh.dockEntries.get(i);
            Object old = t.getTag(R_DOTS);
            if (old instanceof android.graphics.drawable.Drawable) t.getOverlay().remove((android.graphics.drawable.Drawable) old);
            t.setTag(R_DOTS, null);
            int n = a == null || a.pkg == null ? 0 : Math.min(3, AppShots.windows(a.pkg));
            if (n == 0) continue;
            Dots d = new Dots(n, dp(4), dp(3.5f));
            int w = t.getLayoutParams().width, h = t.getLayoutParams().height;
            d.setBounds(0, h + dp(6), w, h + dp(10));
            t.getOverlay().add(d);
            t.setTag(R_DOTS, d);
        }
    }

    private static final int R_DOTS = 0x7f0a2001;

    private static final class Dots extends android.graphics.drawable.Drawable {
        final int n;
        final float size, gap;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Dots(int n, float size, float gap) {
            this.n = n;
            this.size = size;
            this.gap = gap;
            p.setColor(0xF2FFFFFF);
            p.setShadowLayer(size, 0, 0, 0x66000000);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float total = n * size + (n - 1) * gap;
            float x = b.centerX() - total / 2f + size / 2f, y = b.centerY();
            for (int i = 0; i < n; i++) {
                c.drawCircle(x, y, size / 2f, p);
                x += size + gap;
            }
        }

        @Override public void setAlpha(int a) {}

        @Override public void setColorFilter(ColorFilter cf) {}

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Focus resting on an open app: its window preview appears above it. */
    private void schedulePreview(int index) {
        hideDockPreview();
        previewIdx = index;
        host.handler.removeCallbacks(showPreview);
        if (index < 0 || index >= sh.dockEntries.size()) return;
        MainActivity.AppEntry a = sh.dockEntries.get(index);
        if (a == null || a.pkg == null || AppShots.windows(a.pkg) == 0 || !AppShots.has(host, a.pkg)) return;
        host.handler.postDelayed(showPreview, 650);
    }

    private void showDockPreview() {
        int i = previewIdx;
        if (i < 0 || i >= sh.dockTiles.size() || sh.sheetLayer != null || infoLayer != null || saverLayer != null) return;
        View tile = sh.dockTiles.get(i);
        if (!tile.isFocused()) return;
        int w = dp(210);
        View card = previewCard(sh.dockEntries.get(i), w, glass(Liquid.PANEL, dp(16)));
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] tl = new int[2], ol = new int[2];
        tile.getLocationInWindow(tl);
        sh.overlay.getLocationInWindow(ol);
        float cx = tl[0] - ol[0] + tile.getWidth() * tile.getScaleX() / 2f;
        int x = Math.round(Math.max(dp(20), Math.min(sh.overlay.getWidth() - w - dp(20), cx - w / 2f)));
        int y = tl[1] - ol[1] - card.getMeasuredHeight() - dp(18);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = x;
        lp.topMargin = Math.max(dp(10), y);
        sh.overlay.addView(card, lp);
        dockPreview = card;
        popIn(card, dp(10));
    }

    void hideDockPreview() {
        host.handler.removeCallbacks(showPreview);
        View v = dockPreview;
        dockPreview = null;
        if (v == null) return;
        v.animate().alpha(0f).setDuration(120).withEndAction(() -> sh.overlay.removeView(v)).start();
    }
}
