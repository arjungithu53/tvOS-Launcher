package com.techgenius.tvoslauncher.cursor;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Settings screen, in a dark "glass" style.
 * Everything is a plain button, so it works both with the remote's normal focus
 * and with clicks from the cursor.
 */
public class CursorSettingsActivity extends Activity {

    private static final long LEARN_MS = 10000;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xB3FFFFFF;
    private static final int TEXT_FAINT = 0x80FFFFFF;
    private static final int ACCENT = 0xFF7FD1FF;
    private static final int GOOD = 0xFF4ADE80;
    private static final int BAD = 0xFFF87171;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private float density;

    private TextView status;
    private final View[] styleTiles = new View[CursorArt.NAMES.length];
    private final TextView[] styleChecks = new TextView[CursorArt.NAMES.length];
    private TextView tapValue, speedValue, accelValue;
    private TextView toggleValue, toggleHelp, changeButton;
    private TextView urlValue;

    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    refresh();
                }
            };

    private final Runnable learnTimeout = new Runnable() {
        @Override public void run() { refresh(); }
    };

    // ================================================================== layout

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(TvCursor.PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(64), dp(40), dp(64), dp(48));

        // ---- header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("TV Cursor", 34, WHITE);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        status = pill("", new View.OnClickListener() {
            @Override public void onClick(View v) {
                // The cursor runs in the launcher's own service: switch the feature, or turn the service on.
                if (!serviceEnabled()) openAccessibility();
                else {
                    TvCursor.setOn(CursorSettingsActivity.this, !TvCursor.isOn(CursorSettingsActivity.this));
                    com.techgenius.tvoslauncher.PowerService.cursorSettingChanged();
                    refresh();
                }
            }
        });
        header.addView(status);
        page.addView(header);

        // ---- cursor look
        LinearLayout look = card(page, "Cursor");
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < styleTiles.length; i++) {
            final int style = i;
            View tile = styleTile(i);
            tile.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putInt(TvCursor.KEY_STYLE, style).apply();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(150), 1);
            if (i > 0) lp.leftMargin = dp(16);
            tiles.addView(tile, lp);
            styleTiles[i] = tile;
        }
        look.addView(tiles);

        // ---- speed
        LinearLayout speed = card(page, "Speed");
        tapValue = stepper(speed, "Tap step", "How far one press moves the cursor",
                TvCursor.KEY_TAP, TvCursor.DEF_TAP, 2, 80, 2);
        speedValue = stepper(speed, "Hold speed", "Top speed while you hold an arrow",
                TvCursor.KEY_SPEED, TvCursor.DEF_SPEED, 2, 80, 2);
        accelValue = stepper(speed, "Acceleration", "How long holding takes to reach top speed (0 = instant)",
                TvCursor.KEY_ACCEL, TvCursor.DEF_ACCEL, 0, 2000, 100);
        LinearLayout resetRow = row(speed);
        resetRow.addView(spacer());
        resetRow.addView(pill("Reset speed", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().remove(TvCursor.KEY_TAP).remove(TvCursor.KEY_SPEED)
                        .remove(TvCursor.KEY_ACCEL).apply();
            }
        }));

        // ---- show / hide button
        LinearLayout button = card(page, "Show / hide button");
        LinearLayout bRow = row(button);
        LinearLayout bLabels = labels(bRow);
        toggleValue = text("", 22, WHITE);
        toggleHelp = text("", 15, TEXT_DIM);
        bLabels.addView(toggleValue);
        bLabels.addView(toggleHelp);
        changeButton = pill("Change", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putLong(TvCursor.KEY_LEARN, SystemClock.uptimeMillis() + LEARN_MS).apply();
                ui.removeCallbacks(learnTimeout);
                ui.postDelayed(learnTimeout, LEARN_MS + 100);
            }
        });
        bRow.addView(changeButton);
        TextView reset = pill("Use Hold BACK", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putInt(TvCursor.KEY_TOGGLE, KeyEvent.KEYCODE_BACK)
                        .putLong(TvCursor.KEY_LEARN, 0).apply();
            }
        });
        ((LinearLayout.LayoutParams) reset.getLayoutParams()).leftMargin = dp(12);
        bRow.addView(reset);

        // ---- browser
        LinearLayout browser = card(page, "Browser");
        LinearLayout uRow = row(browser);
        LinearLayout uLabels = labels(uRow);
        uLabels.addView(text("Select the address bar when the cursor hides", 20, WHITE));
        uLabels.addView(text("So the remote's arrows start from the address bar", 15, TEXT_DIM));
        urlValue = pill("", new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = prefs.getBoolean(TvCursor.KEY_FOCUS_URL, true);
                prefs.edit().putBoolean(TvCursor.KEY_FOCUS_URL, !on).apply();
            }
        });
        uRow.addView(urlValue);

        // ---- help
        LinearLayout help = card(page, "How to use");
        help.addView(text(
                "Arrows move the cursor. Hold an arrow to glide.\n"
              + "OK clicks. Hold OK for a long press.\n"
              + "Move to the edge of a page and keep pressing that way to scroll. "
              + "When the page can't scroll further, the cursor carries on, "
              + "for example up into the address bar.", 17, TEXT_DIM));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(backdrop());
        root.addView(scroll);
        setContentView(root);

        refresh();
        int sel = prefs.getInt(TvCursor.KEY_STYLE, CursorArt.CHROME);
        styleTiles[sel >= 0 && sel < styleTiles.length ? sel : 0].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.registerOnSharedPreferenceChangeListener(listener);
        refresh();
    }

    @Override
    protected void onPause() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
        ui.removeCallbacks(learnTimeout);
        if (SystemClock.uptimeMillis() < prefs.getLong(TvCursor.KEY_LEARN, 0)) {
            prefs.edit().putLong(TvCursor.KEY_LEARN, 0).apply();   // stop listening when leaving
        }
        super.onPause();
    }

    // ================================================================== state -> screen

    private void refresh() {
        boolean svcOn = serviceEnabled();
        boolean on = svcOn && TvCursor.isOn(this);
        status.setText(!svcOn ? "●  Launcher service off · turn on" : on ? "●  Cursor on" : "●  Cursor off");
        status.setTextColor(on ? GOOD : BAD);

        int style = prefs.getInt(TvCursor.KEY_STYLE, CursorArt.CHROME);
        for (int i = 0; i < styleTiles.length; i++) {
            styleTiles[i].setSelected(i == style);
            styleChecks[i].setText(i == style ? "✓  " + CursorArt.NAMES[i] : CursorArt.NAMES[i]);
            styleChecks[i].setTextColor(i == style ? WHITE : TEXT_DIM);
        }

        tapValue.setText(String.valueOf(prefs.getInt(TvCursor.KEY_TAP, TvCursor.DEF_TAP)));
        speedValue.setText(String.valueOf(prefs.getInt(TvCursor.KEY_SPEED, TvCursor.DEF_SPEED)));
        accelValue.setText(prefs.getInt(TvCursor.KEY_ACCEL, TvCursor.DEF_ACCEL) + " ms");

        int toggle = prefs.getInt(TvCursor.KEY_TOGGLE, KeyEvent.KEYCODE_BACK);
        boolean learning = SystemClock.uptimeMillis() < prefs.getLong(TvCursor.KEY_LEARN, 0);
        if (learning) {
            toggleValue.setText("Press a button on the remote…");
            toggleHelp.setText(on ? "Waiting 10 seconds. Arrows, OK, Home and Power can't be used."
                                  : "The service is off, so no button can be detected. Turn it on first.");
            changeButton.setText("Waiting…");
        } else {
            toggleValue.setText(TvCursor.toggleHint(toggle));
            toggleHelp.setText(toggle == KeyEvent.KEYCODE_BACK
                    ? "Hold for about half a second. A short press still goes back."
                    : "Press once to show, again to hide.");
            changeButton.setText("Change");
        }

        boolean url = prefs.getBoolean(TvCursor.KEY_FOCUS_URL, true);
        urlValue.setText(url ? "On" : "Off");
    }

    private boolean serviceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo info : list) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Open Settings > System > Accessibility", Toast.LENGTH_LONG).show();
        }
    }

    // ================================================================== building blocks

    /** A frosted card with a small heading. Returns the card's content column. */
    private LinearLayout card(LinearLayout page, String heading) {
        TextView h = text(heading.toUpperCase(), 14, TEXT_FAINT);
        h.setLetterSpacing(0.12f);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(32);
        hp.bottomMargin = dp(10);
        hp.leftMargin = dp(8);
        page.addView(h, hp);

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(28), dp(22), dp(28), dp(22));
        c.setBackground(round(0x1FFFFFFF, 0x2EFFFFFF, 1, 24));
        page.addView(c, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (parent.getChildCount() > 0) lp.topMargin = dp(18);
        parent.addView(r, lp);
        return r;
    }

    private LinearLayout labels(LinearLayout row) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return l;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        return v;
    }

    /** "Label   [ − ]  value  [ + ]". Returns the value text. */
    private TextView stepper(LinearLayout card, String label, String hint, final String key,
                             final int def, final int min, final int max, final int step) {
        LinearLayout r = row(card);
        LinearLayout l = labels(r);
        l.addView(text(label, 20, WHITE));
        l.addView(text(hint, 15, TEXT_DIM));

        TextView minus = roundButton("−", new View.OnClickListener() {
            @Override public void onClick(View v) { change(key, def, min, max, -step); }
        });
        r.addView(minus);

        TextView value = text("", 22, WHITE);
        value.setGravity(Gravity.CENTER);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        r.addView(value, new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView plus = roundButton("+", new View.OnClickListener() {
            @Override public void onClick(View v) { change(key, def, min, max, step); }
        });
        r.addView(plus);
        return value;
    }

    private void change(String key, int def, int min, int max, int delta) {
        int cur = prefs.getInt(key, def);
        int next = cur + delta;
        if (next < min) next = min;
        if (next > max) next = max;
        if (next != cur) prefs.edit().putInt(key, next).apply();
    }

    private View styleTile(int style) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(tileBackground());
        liftOnFocus(t);

        ImageView img = new ImageView(this);
        img.setImageBitmap(CursorArt.render(this, style).bmp);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        t.addView(img, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView name = text(CursorArt.NAMES[style], 16, TEXT_DIM);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(10);
        t.addView(name, np);
        styleChecks[style] = name;
        return t;
    }

    /** Rounded glass button; turns white with dark text when the remote's focus is on it. */
    private TextView pill(String label, View.OnClickListener click) {
        TextView b = text(label, 18, WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setPadding(dp(26), dp(12), dp(26), dp(12));
        b.setMinWidth(dp(120));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(buttonBackground(28));
        b.setTextColor(buttonText());
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        liftOnFocus(b);
        return b;
    }

    private TextView roundButton(String label, View.OnClickListener click) {
        TextView b = text(label, 26, WHITE);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(buttonBackground(28));
        b.setTextColor(buttonText());
        b.setOnClickListener(click);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
        liftOnFocus(b);
        return b;
    }

    private void liftOnFocus(View v) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean has) {
                float s = has ? 1.06f : 1f;
                view.animate().scaleX(s).scaleY(s).setDuration(140).start();
                view.setElevation(has ? dp(8) : 0);
            }
        });
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    // ================================================================== drawables

    private GradientDrawable round(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private Drawable buttonBackground(int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(0xCCFFFFFF, 0, 0, radiusDp));
        s.addState(new int[]{android.R.attr.state_focused}, round(0xF2FFFFFF, 0, 0, radiusDp));
        s.addState(new int[]{}, round(0x26FFFFFF, 0x33FFFFFF, 1, radiusDp));
        return s;
    }

    private ColorStateList buttonText() {
        return new ColorStateList(
                new int[][]{{android.R.attr.state_pressed}, {android.R.attr.state_focused}, {}},
                new int[]{0xFF111418, 0xFF111418, WHITE});
    }

    /** Tiles keep a dark glass look when focused (the cursors are white), with a bright rim instead. */
    private Drawable tileBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, round(0x40FFFFFF, WHITE, 3, 22));
        s.addState(new int[]{android.R.attr.state_pressed}, round(0x40FFFFFF, WHITE, 3, 22));
        s.addState(new int[]{android.R.attr.state_selected}, round(0x2EFFFFFF, ACCENT, 2, 22));
        s.addState(new int[]{}, round(0x14FFFFFF, 0x26FFFFFF, 1, 22));
        return s;
    }

    private Drawable backdrop() {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF101B30, 0xFF1A1630, 0xFF2A1634});
        GradientDrawable glowA = new GradientDrawable();
        glowA.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowA.setColors(new int[]{0x403B9EFF, 0x003B9EFF});
        glowA.setGradientCenter(0.12f, 0.08f);
        glowA.setGradientRadius(dp(700));
        GradientDrawable glowB = new GradientDrawable();
        glowB.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowB.setColors(new int[]{0x30C054FF, 0x00C054FF});
        glowB.setGradientCenter(0.9f, 0.95f);
        glowB.setGradientRadius(dp(650));
        return new LayerDrawable(new Drawable[]{base, glowA, glowB});
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }
}
