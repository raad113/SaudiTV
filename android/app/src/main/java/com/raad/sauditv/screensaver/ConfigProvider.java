package com.raad.sauditv.screensaver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Read-only: hands the launcher (same signing key, see READ_CONFIG in the manifest) what its home-screen
 * card needs: the page settings as a URL fragment and which card to show ("none" = off).
 * content://com.raad.sauditv.screensaver.config/update: the last app-update check, for the home screen's
 * «تحديث متوفر» pill: available (the newer versionCode, 0 = none), name (its versionName), installed (this app's).
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
        MatrixCursor c = new MatrixCursor(new String[] {"fragment", "home_panel"});
        c.addRow(new Object[] {p.fragment(), p.homePanel()});
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
