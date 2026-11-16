package com.techgenius.tvoslauncher;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Posted notifications for the Liquid Glass layout, read through the launcher's existing
 * notification listener (MediaWatchService). Nothing is stored except which apps are muted and
 * when the list was last looked at (for the red count).
 */
final class Notifs {
    private Notifs() {}

    static final class Item {
        String key, pkg, title, text;
        long time;
        boolean clearable;
        android.app.PendingIntent open;
    }

    /** Called on the main thread when a notification is posted (with it) or removed (null). */
    interface Listener {
        void onChange(Item posted);
    }

    static Listener listener;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("liquid_notifs", Context.MODE_PRIVATE);
    }

    static boolean available() {
        return MediaWatchService.instance != null;
    }

    static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        MediaWatchService s = MediaWatchService.instance;
        if (s == null) return out;
        StatusBarNotification[] all;
        try {
            all = s.getActiveNotifications();
        } catch (Throwable t) {
            return out;
        }
        if (all == null) return out;
        Set<String> muted = muted(c);
        for (StatusBarNotification sbn : all) {
            Item it = item(c, sbn);
            if (it != null && !muted.contains(it.pkg)) out.add(it);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    /** Null for notifications that don't belong in the list (ongoing, media, group summaries, ours). */
    static Item item(Context c, StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return null;
        Notification n = sbn.getNotification();
        if (c.getPackageName().equals(sbn.getPackageName())) return null;
        if (sbn.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        if (Notification.CATEGORY_TRANSPORT.equals(n.category) || Notification.CATEGORY_SERVICE.equals(n.category)) return null;
        Bundle e = n.extras;
        CharSequence title = e != null ? e.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence text = e != null ? e.getCharSequence(Notification.EXTRA_TEXT) : null;
        if (title == null && text == null) return null;
        Item it = new Item();
        it.key = sbn.getKey();
        it.pkg = sbn.getPackageName();
        it.title = title != null ? title.toString() : "";
        it.text = text != null ? text.toString() : "";
        it.time = sbn.getPostTime();
        it.clearable = sbn.isClearable();
        it.open = n.contentIntent;
        return it;
    }

    static int unread(Context c) {
        long seen = prefs(c).getLong("seen", 0);
        int n = 0;
        for (Item it : list(c)) if (it.time > seen) n++;
        return n;
    }

    static void markSeen(Context c) {
        prefs(c).edit().putLong("seen", System.currentTimeMillis()).apply();
    }

    static void dismiss(String key) {
        MediaWatchService s = MediaWatchService.instance;
        if (s == null || key == null) return;
        try {
            s.cancelNotification(key);
        } catch (Throwable ignored) {
        }
    }

    static void clearAll(Context c) {
        MediaWatchService s = MediaWatchService.instance;
        if (s == null) return;
        for (Item it : list(c)) {
            if (it.clearable) dismiss(it.key);
        }
    }

    static Set<String> muted(Context c) {
        return new HashSet<>(prefs(c).getStringSet("muted", Collections.emptySet()));
    }

    /** "Turn off for this app": its notifications stop showing in the launcher. */
    static void mute(Context c, String pkg) {
        Set<String> m = muted(c);
        m.add(pkg);
        prefs(c).edit().putStringSet("muted", m).apply();
    }

    static void unmuteAll(Context c) {
        prefs(c).edit().remove("muted").apply();
    }

    /** From the listener service. */
    static void posted(Context c, StatusBarNotification sbn) {
        Listener l = listener;
        if (l == null) return;
        Item it = item(c, sbn);
        if (it != null && muted(c).contains(it.pkg)) it = null;
        final Item fin = it;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (listener != null) listener.onChange(fin);
        });
    }

    static void removed() {
        if (listener == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (listener != null) listener.onChange(null);
        });
    }
}
