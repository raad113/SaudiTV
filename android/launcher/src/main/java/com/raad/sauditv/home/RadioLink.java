package com.raad.sauditv.home;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * «إذاعة القرآن الكريم» from the home screen: the screensaver app plays it (its RadioService) while one of our screens
 * is in front. The home screen and its full-screen card (DetailActivity) say so here (front/back); while either is in
 * front and the radio is on, this binds RadioService as "home", and lets go GRACE_MS after the last one went, so the
 * screensaver (which binds as "saver") takes over without a gap and Back from the card never cuts it. An app that plays
 * takes the audio focus at once anyway, and the radio stops then. The radio off: no binding at all (it kept the
 * screensaver app's process up, unkillable, 55 to 65 MB, while the home screen showed).
 * The chips («تشغيل القرآن» / «إيقاف القرآن» among the pills, «يُتلى الآن» under the clock) and the quick-settings tile
 * read and write the screensaver app's ConfigProvider (/radio). While one of our screens is in front this watches /radio
 * (the screensaver app notifies it on every change: on or off, the station, playing, loading…: its RadioNotify) and
 * tells the listeners (listen): no more asking every 2 and 3 s.
 */
final class RadioLink {
    static final String SAVER_PKG = "com.raad.sauditv.screensaver";
    static final Uri RADIO = Uri.parse("content://com.raad.sauditv.screensaver.config/radio");
    static final long GRACE_MS = 3000;
    /** a burst of changes (a station is three settings; loading, then playing) is told once, this long after the first */
    static final long SETTLE_MS = 150;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> FRONT = new HashSet<>();
    private static final Set<Runnable> LISTENERS = new LinkedHashSet<>();
    private static Context app;
    private static boolean bound, watching;
    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) { }
        @Override public void onServiceDisconnected(ComponentName n) { }   // its process went: Android binds again
        // the screensaver app was updated or stopped: this binding is over for good; a new one while we are in front
        @Override public void onBindingDied(ComponentName n) {
            unbind();
            MAIN.postDelayed(RadioLink::sync, 1000);
        }
    };
    private static final Runnable UNBIND = () -> { if (FRONT.isEmpty()) unbind(); };
    private static final Runnable CHANGED = () -> {
        sync();
        for (Runnable r : new LinkedHashSet<>(LISTENERS)) r.run();
    };
    private static final ContentObserver OBSERVER = new ContentObserver(MAIN) {
        @Override public void onChange(boolean self) {
            MAIN.removeCallbacks(CHANGED);
            MAIN.postDelayed(CHANGED, SETTLE_MS);
        }
    };

    private RadioLink() {}

    /** a screen of the home app is in front (its onResume) */
    static void front(Context c, String who) {
        app = c.getApplicationContext();
        FRONT.add(who);
        MAIN.removeCallbacks(UNBIND);
        watch(true);
        sync();
    }

    /** it went (its onPause) */
    static void back(String who) {
        FRONT.remove(who);
        if (FRONT.isEmpty()) {
            watch(false);
            MAIN.removeCallbacks(CHANGED);
            MAIN.removeCallbacks(UNBIND);
            MAIN.postDelayed(UNBIND, GRACE_MS);
        }
    }

    /** r runs (main thread) after /radio changed while one of our screens is in front; until unlisten */
    static void listen(Runnable r) { LISTENERS.add(r); }
    static void unlisten(Runnable r) { LISTENERS.remove(r); }

    /** bound while one of our screens is in front and the radio is on; unbound at once when it is turned off */
    static void sync() {
        if (app == null || FRONT.isEmpty()) return;
        String[] r = read(app);
        if (r != null && "1".equals(r[0])) bind();
        else { MAIN.removeCallbacks(UNBIND); unbind(); }
    }

    private static void bind() {
        if (bound || FRONT.isEmpty() || app == null) return;
        Intent i = new Intent("com.raad.sauditv.screensaver.RADIO")
            .setClassName(SAVER_PKG, SAVER_PKG + ".RadioService").setData(Uri.parse("sauditv-radio://home"));
        try { bound = app.bindService(i, CONN, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException e) { Log.w("SaudiTV-Home", "radio: " + e); }   // screensaver app missing or older
    }

    private static void unbind() {
        if (!bound) return;
        bound = false;
        try { app.unbindService(CONN); } catch (RuntimeException ignored) { }
    }

    /** /radio watched while one of our screens is in front (no screensaver app, or an older one: nothing to watch) */
    private static void watch(boolean on) {
        if (on == watching || app == null) return;
        try {
            if (on) app.getContentResolver().registerContentObserver(RADIO, false, OBSERVER);
            else app.getContentResolver().unregisterContentObserver(OBSERVER);
            watching = on;
        } catch (RuntimeException e) {
            Log.w("SaudiTV-Home", "radio: " + e);
        }
    }

    /** {on, state, name}: on "1"/"0", state "idle", "loading", "playing", "error" or "paused"; null without the app */
    static String[] read(Context c) {
        try (Cursor k = c.getContentResolver().query(RADIO, null, null, null, null)) {
            if (k == null || !k.moveToFirst()) return null;
            return new String[] {k.getString(0), k.getString(1), k.getString(2)};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** the chip's and the tile's press (RadioChip): playing, off (stopped, and it stays off); else on and playing now, here
     *  too whatever «متى تعمل الإذاعة» says (ConfigProvider). false when the screensaver app can't be reached */
    static boolean toggle(Context c) {
        String[] r = read(c);
        return r != null && set(c, RadioChip.pressTurnsOn(r[0], r[1]));
    }

    /** on (and playing now) or off (RadioService follows); false when the screensaver app can't be reached. The binding
     *  follows at once (on: RadioService starts and plays here; off: let go). */
    static boolean set(Context c, boolean on) {
        ContentValues v = new ContentValues();
        v.put("on", on ? 1 : 0);
        boolean done;
        try { done = c.getContentResolver().update(RADIO, v, null, null) > 0; }
        catch (RuntimeException e) { Log.w("SaudiTV-Home", "radio: " + e); return false; }
        if (done) sync();
        return done;
    }

    /** the page's SaudiTVRadio.state() (the «يُتلى الآن» chip): "playing|<station>" … or "" */
    static String chip(Context c) {
        String[] r = read(c);
        if (r == null || r[1] == null || "idle".equals(r[1])) return "";
        return r[1] + "|" + (r[2] == null ? "" : r[2]);
    }
}
