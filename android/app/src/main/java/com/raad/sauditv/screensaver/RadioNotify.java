package com.raad.sauditv.screensaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Tells the home screen when «إذاعة القرآن الكريم» changes, so it no longer asks every 2 and 3 s (each ask was a query
 * to this app, which also kept it running): ConfigProvider's /radio is notified (ContentResolver.notifyChange) when a
 * setting of the radio changes, whoever wrote it (the settings, the setup, saver.sh, the home screen's chip: watch, on
 * this app's one SharedPreferences), and when RadioService's state, station or error changes (changed). The launcher's
 * RadioLink watches /radio while one of its screens is in front: its pill, its «يُتلى الآن» and its binding follow.
 */
final class RadioNotify {
    private RadioNotify() {}

    static final Uri RADIO = Uri.parse("content://com.raad.sauditv.screensaver.config/radio");
    /** the settings /radio answers from (ConfigProvider: on, the station's name, when) */
    private static final Set<String> KEYS = new HashSet<>(Arrays.asList("radioOn", "radioWhen", "radioStation", "radioName",
        "radioUrl"));
    /** kept here: SharedPreferences holds its listeners weakly */
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;

    /** once per process (the Prefs constructor): a change of a radio setting notifies /radio */
    static synchronized void watch(final Context app, SharedPreferences sp) {
        if (listener != null) return;
        listener = (p, key) -> { if (key == null || KEYS.contains(key)) changed(app); };
        sp.registerOnSharedPreferenceChangeListener(listener);
    }

    /** /radio changed: the home screen reads it again (if it is watching) */
    static void changed(Context c) {
        try { c.getContentResolver().notifyChange(RADIO, null); } catch (RuntimeException ignored) { }
    }
}
