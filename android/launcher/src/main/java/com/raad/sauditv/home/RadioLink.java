package com.raad.sauditv.home;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * «إذاعة القرآن الكريم» from the home screen: the screensaver app plays it (its RadioService) while one of our screens
 * is in front. The home screen and its full-screen card (DetailActivity) say so here (front/back); while either is in
 * front this binds RadioService as "home", and lets go GRACE_MS after the last one went, so the screensaver (which
 * binds as "saver") takes over without a gap and Back from the card never cuts it. An app that plays takes the audio
 * focus at once anyway, and the radio stops then.
 * The chips («تشغيل القرآن» / «إيقاف القرآن» among the pills, «يُتلى الآن» under the clock) and the quick-settings tile
 * read and write the screensaver app's ConfigProvider (/radio).
 */
final class RadioLink {
    static final String SAVER_PKG = "com.raad.sauditv.screensaver";
    static final Uri RADIO = Uri.parse("content://com.raad.sauditv.screensaver.config/radio");
    static final long GRACE_MS = 3000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> FRONT = new HashSet<>();
    private static Context app;
    private static boolean bound;
    private static final ServiceConnection CONN = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) { }
        @Override public void onServiceDisconnected(ComponentName n) { }   // its process went: Android binds again
        // the screensaver app was updated or stopped: this binding is over for good; a new one while we are in front
        @Override public void onBindingDied(ComponentName n) {
            if (bound) {
                bound = false;
                try { app.unbindService(this); } catch (RuntimeException ignored) { }
            }
            MAIN.postDelayed(RadioLink::bindIfFront, 1000);
        }
    };
    private static final Runnable UNBIND = () -> {
        if (!FRONT.isEmpty() || !bound) return;
        bound = false;
        try { app.unbindService(CONN); } catch (RuntimeException ignored) { }
    };

    private RadioLink() {}

    /** a screen of the home app is in front (its onResume) */
    static void front(Context c, String who) {
        app = c.getApplicationContext();
        FRONT.add(who);
        MAIN.removeCallbacks(UNBIND);
        bindIfFront();
    }

    private static void bindIfFront() {
        if (bound || FRONT.isEmpty() || app == null) return;
        Intent i = new Intent("com.raad.sauditv.screensaver.RADIO")
            .setClassName(SAVER_PKG, SAVER_PKG + ".RadioService").setData(Uri.parse("sauditv-radio://home"));
        try { bound = app.bindService(i, CONN, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException e) { Log.w("SaudiTV-Home", "radio: " + e); }   // screensaver app missing or older
    }

    /** it went (its onPause) */
    static void back(String who) {
        FRONT.remove(who);
        if (FRONT.isEmpty()) {
            MAIN.removeCallbacks(UNBIND);
            MAIN.postDelayed(UNBIND, GRACE_MS);
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

    /** on (and playing now) or off (RadioService follows); false when the screensaver app can't be reached */
    static boolean set(Context c, boolean on) {
        ContentValues v = new ContentValues();
        v.put("on", on ? 1 : 0);
        try { return c.getContentResolver().update(RADIO, v, null, null) > 0; }
        catch (RuntimeException e) { Log.w("SaudiTV-Home", "radio: " + e); return false; }
    }

    /** the page's SaudiTVRadio.state() (the «يُتلى الآن» chip): "playing|<station>" … or "" */
    static String chip(Context c) {
        String[] r = read(c);
        if (r == null || r[1] == null || "idle".equals(r[1])) return "";
        return r[1] + "|" + (r[2] == null ? "" : r[2]);
    }
}
