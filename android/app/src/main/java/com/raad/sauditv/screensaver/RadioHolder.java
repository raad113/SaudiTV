package com.raad.sauditv.screensaver;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

/**
 * One of this app's screens in front for «إذاعة القرآن الكريم» (RadioService): bound while it shows (the dream, the
 * screensaver's preview, the settings' «معاينة»). The page's chip reads RadioBridge.
 */
final class RadioHolder {
    private final Context app;
    private final String who;
    private boolean bound;
    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) { }
        @Override public void onServiceDisconnected(ComponentName n) { }
    };

    RadioHolder(Context c, String who) {
        this.app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        this.who = who;
    }

    void front() {
        if (bound) return;
        try { bound = app.bindService(RadioService.bindIntent(app, who), conn, Context.BIND_AUTO_CREATE); }
        catch (RuntimeException e) { android.util.Log.w(RadioService.TAG, "bind " + who + ": " + e); }
    }

    void back() {
        if (!bound) return;
        bound = false;
        try { app.unbindService(conn); } catch (RuntimeException ignored) { }
    }

    boolean isFront() { return bound; }
}
