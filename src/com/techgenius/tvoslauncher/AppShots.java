package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Window previews for the Liquid Glass dock and Control Panel.
 *
 * Android only gives the system its own window thumbnails, so the launcher keeps its own: a small
 * screenshot of an app, taken through its accessibility service while you're in that app (every
 * 20 seconds and right before the Settings button opens the Control Panel). Each is a 480 × 270
 * JPEG on storage; one is loaded into memory only while its preview is on screen.
 *
 * Which apps are open, and how many windows each has, comes from Android's task list read over
 * the launcher's ADB link.
 */
final class AppShots {
    private AppShots() {}

    static final int W = 480, H = 270;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile long lastShot;
    private static volatile boolean busy;

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "shots");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File file(Context c, String pkg) {
        return new File(dir(c), pkg + ".jpg");
    }

    static boolean has(Context c, String pkg) {
        return pkg != null && file(c, pkg).exists();
    }

    /** The saved picture, or null. Call off the main thread; recycle when the preview closes. */
    static Bitmap load(Context c, String pkg) {
        File f = file(c, pkg);
        if (!f.exists()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable t) {
            return null;
        }
    }

    static void forget(Context c, String pkg) {
        if (pkg != null) file(c, pkg).delete();
    }

    /** Screenshot of the app in front, saved under its package. done (may be null) runs on the main thread. */
    static void capture(android.accessibilityservice.AccessibilityService svc, String pkg, Runnable done) {
        if (android.os.Build.VERSION.SDK_INT < 30 || svc == null || pkg == null || busy) {
            if (done != null) MAIN.post(done);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastShot < 400) {  // Android allows about three a second
            if (done != null) MAIN.post(done);
            return;
        }
        lastShot = now;
        busy = true;
        final Context app = svc.getApplicationContext();
        try {
            svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, java.util.concurrent.Executors.newSingleThreadExecutor(),
                    new android.accessibilityservice.AccessibilityService.TakeScreenshotCallback() {
                        @Override public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult res) {
                            try {
                                save(app, pkg, res);
                            } catch (Throwable ignored) {
                            }
                            busy = false;
                            if (done != null) MAIN.post(done);
                        }

                        @Override public void onFailure(int code) {
                            busy = false;
                            if (done != null) MAIN.post(done);
                        }
                    });
        } catch (Throwable t) {
            busy = false;
            if (done != null) MAIN.post(done);
        }
    }

    private static void save(Context c, String pkg, android.accessibilityservice.AccessibilityService.ScreenshotResult res) throws Exception {
        android.hardware.HardwareBuffer hb = res.getHardwareBuffer();
        if (hb == null) return;
        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
        hb.close();
        if (hw == null) return;
        Bitmap small = null;
        try {
            // Scale on the GPU first so no full-screen copy is ever made in memory.
            Matrix m = new Matrix();
            m.setScale(W / (float) hw.getWidth(), H / (float) hw.getHeight());
            Bitmap scaledHw = Bitmap.createBitmap(hw, 0, 0, hw.getWidth(), hw.getHeight(), m, true);
            small = scaledHw.copy(Bitmap.Config.ARGB_8888, false);
            if (scaledHw != hw) scaledHw.recycle();
        } catch (Throwable t) {
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            if (sw != null) {
                small = Bitmap.createScaledBitmap(sw, W, H, true);
                if (small != sw) sw.recycle();
            }
        }
        hw.recycle();
        if (small == null) return;
        if (isBlank(small)) {  // protected video and secure screens come back black: keep the last good one
            small.recycle();
            return;
        }
        File tmp = new File(dir(c), pkg + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            small.compress(Bitmap.CompressFormat.JPEG, 82, out);
        }
        small.recycle();
        tmp.renameTo(file(c, pkg));
    }

    private static boolean isBlank(Bitmap b) {
        int lit = 0;
        for (int y = 8; y < b.getHeight(); y += 24) {
            for (int x = 8; x < b.getWidth(); x += 24) {
                int p = b.getPixel(x, y);
                if (((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF) > 60) lit++;
            }
        }
        return lit < 4;
    }

    // ------------------------------------------------------------------ open apps and windows

    /** Open windows per package, from the last refresh. */
    private static volatile Map<String, Integer> open = new HashMap<>();

    static int windows(String pkg) {
        Integer n = pkg == null ? null : open.get(pkg);
        return n == null ? 0 : n;
    }

    private static final Pattern TASK = Pattern.compile("A=\\d+:([\\w.]+)");

    /** Reads Android's task list (tasks whose app is still running). done runs on the main thread. */
    static void refreshOpen(Context c, Runnable done) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            Map<String, Integer> next = new HashMap<>();
            boolean ok = false;
            try {
                String out = LocalAdb.execShellOutput(app,
                        "dumpsys activity recents | grep 'Recent #'; echo ===; ps -A -o NAME");
                if (out != null && out.contains("===")) {
                    String[] parts = out.split("===", 2);
                    java.util.Set<String> running = new java.util.HashSet<>();
                    for (String line : parts[1].split("\n")) {
                        String n = line.trim();
                        int colon = n.indexOf(':');
                        if (colon > 0) n = n.substring(0, colon);
                        if (!n.isEmpty()) running.add(n);
                    }
                    Matcher m = TASK.matcher(parts[0]);
                    String own = app.getPackageName();
                    while (m.find()) {
                        String pkg = m.group(1);
                        if (pkg.equals(own) || !running.contains(pkg) || Perms.isAnySystemLauncherPackage(app, pkg)) continue;
                        Integer n = next.get(pkg);
                        next.put(pkg, n == null ? 1 : n + 1);
                    }
                    ok = true;
                }
            } catch (Throwable ignored) {
            }
            if (!ok) {
                // No ADB link: the launcher's own recent list, one window each.
                try {
                    for (String p : RecentAppsOverlay.getRecentPackages(app, 12)) next.put(p, 1);
                } catch (Throwable ignored) {
                }
            }
            open = next;
            if (done != null) MAIN.post(done);
        }, "app-windows").start();
    }

    static void markClosed(String pkg) {
        Map<String, Integer> m = new HashMap<>(open);
        m.remove(pkg);
        open = m;
    }
}
