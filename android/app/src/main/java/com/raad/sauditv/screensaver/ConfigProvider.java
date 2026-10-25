package com.raad.sauditv.screensaver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Hands the launcher (same signing key, see READ_CONFIG in the manifest) what its home-screen
 * card needs: the page settings as a URL fragment, which card to show ("none" = off) and «شكل الأرقام» (numerals:
 * latn or arab, for the launcher's own text; the fragment carries it for the page), and the app tiles' look
 * (tile_shape: rounded or circle; focus_style: google or apple).
 * content://com.raad.sauditv.screensaver.config/update: the last app-update check, for the home screen's
 * «تحديث متوفر» pill: available (the newer versionCode, 0 = none), name (its versionName), installed (this app's).
 * content://com.raad.sauditv.screensaver.config/radio: «إذاعة القرآن الكريم» for the home screen's chip and its
 * quick-settings tile: on (1/0), state (RadioService.state(): idle, loading, playing, error, paused), name (the
 * station's), error, when (RadioWhen). The only write: update() of /radio with on = 1 or 0 (the home screen's
 * «تشغيل القرآن» / «إيقاف القرآن» chip and its quick-settings tile: on also plays it now on the home screen, whatever
 * RadioWhen says, and resumes a pause), the same signature permission (writePermission in the manifest).
 * content://com.raad.sauditv.screensaver.config/setup: done (1/0), the first-run setup finished or skipped
 * (Prefs.setupDone); the home screen opens SetupActivity once per start while it is 0. An older app without this
 * answers the settings' columns, no "done": the home screen then opens nothing.
 */
public class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if ("/update".equals(uri.getPath())) {
            AppUpdate.Manifest m = AppUpdate.available(getContext());
            MatrixCursor c = new MatrixCursor(new String[] {"available", "name", "installed"});
            c.addRow(new Object[] {m == null ? 0L : m.versionCode, m == null ? "" : m.versionName,
                AppUpdate.installed(getContext(), getContext().getPackageName())});
            return c;
        }
        Prefs p = new Prefs(getContext());
        if ("/setup".equals(uri.getPath())) {
            MatrixCursor c = new MatrixCursor(new String[] {"done"});
            c.addRow(new Object[] {p.setupDone() ? 1 : 0});
            return c;
        }
        if ("/radio".equals(uri.getPath())) {
            MatrixCursor c = new MatrixCursor(new String[] {"on", "state", "name", "error", "when"});
            String st = RadioService.state();
            c.addRow(new Object[] {p.radioOn() ? 1 : 0, st, "idle".equals(st) ? p.radioName() : RadioService.stationName(),
                RadioService.error(), p.radioWhen()});
            return c;
        }
        MatrixCursor c = new MatrixCursor(new String[] {"fragment", "home_panel", "numerals", "tile_shape", "focus_style"});
        c.addRow(new Object[] {p.fragment(), p.homePanel(), p.numerals(), p.tileShape(), p.focusStyle()});
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }

    /** /radio, on = 1 or 0: the home screen's chip and quick-settings tile turn the radio on (and play it now) or off */
    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        if (!"/radio".equals(uri.getPath()) || v == null || !v.containsKey("on")) return 0;
        Integer on = v.getAsInteger("on");
        if (on == null) return 0;
        new Prefs(getContext()).setRadioOn(on != 0);
        if (on != 0) RadioService.userPlay(); else RadioService.userStop();
        getContext().sendBroadcast(new Intent(SaverReceiver.CHANGED).setPackage("com.raad.sauditv.home"));
        return 1;
    }
}
