package com.raad.sauditv.screensaver;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Updates of both apps, this one and the home screen (com.raad.sauditv.home), from the project's GitHub releases,
 * without a computer. Every release carries a small update.json beside its two APKs (tools/release.sh writes it):
 *
 *   {"versionCode": 9036, "versionName": "9036", "tag": "v9036", "notes": "…",
 *    "apks": [{"package": "com.raad.sauditv.screensaver", "file": "saudi-tv-screensaver.apk", "versionCode": 9036,
 *              "url": "https://github.com/raad113/SaudiTV/releases/download/v9036/saudi-tv-screensaver.apk",
 *              "sha256": "…", "size": 4977194}, {"package": "com.raad.sauditv.home", …}]}
 *
 * This class only checks (UpdateJob every 6 h, the settings row «تحديث التطبيق», ADB's APP_CHECK) and keeps the
 * answer for the settings screen and the home screen's «تحديث متوفر» pill (ConfigProvider, content://…config/update).
 * AppInstaller downloads, verifies and installs. Nothing is ever installed without the user pressing «تثبيت».
 */
final class AppUpdate {
    static final String SELF_PKG = "com.raad.sauditv.screensaver", HOME_PKG = "com.raad.sauditv.home";
    /** the newest release's manifest (GitHub redirects it to the release's own asset) */
    static final String URL_LATEST = "https://github.com/raad113/SaudiTV/releases/latest/download/update.json";
    /** the same file on main, if the release download can't be reached (raw.githubusercontent.com, as the page) */
    static final String URL_MAIN = PhotoCache.REPO + "update.json";
    /** APKs only ever come from this project's releases */
    static final String URL_PREFIX = "https://github.com/raad113/SaudiTV/releases/download/";
    /** tells a running home screen to show or hide its «تحديث متوفر» pill */
    static final String STATE_CHANGED = "com.raad.sauditv.screensaver.UPDATE_STATE";
    static final long INTERVAL_MS = Updater.INTERVAL_MS;
    static final long MAX_APK = 200L * 1024 * 1024;

    static final int NONE = 0, AVAILABLE = 1, NO_NET = 2, BAD = 3, SKIPPED = 4;

    private AppUpdate() {}

    static final class Apk {
        String pkg, file, url, sha256;
        long size, versionCode;
    }

    static final class Manifest {
        long versionCode;
        String versionName, tag, notes;
        final List<Apk> apks = new ArrayList<>();

        /** The APKs this TV needs, the home screen first and this app (the installer itself) last: installing it
         *  ends this process. An app that isn't installed (no home screen on this TV) is left out. */
        List<Apk> needed(Context c) {
            List<Apk> out = new ArrayList<>();
            Apk self = null;
            for (Apk a : apks) {
                long have = installed(c, a.pkg);
                if (have < 0 || have >= a.versionCode) continue;
                if (a.pkg.equals(c.getPackageName())) self = a; else out.add(a);
            }
            if (self != null) out.add(self);
            return out;
        }

        long bytes(Context c) {
            long n = 0;
            for (Apk a : needed(c)) n += a.size;
            return n;
        }
    }

    static final class Result {
        final int code;
        final String message;
        final Manifest manifest;
        Result(int code, String message, Manifest m) { this.code = code; this.message = message; this.manifest = m; }
    }

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("appupdate", Context.MODE_PRIVATE); }

    /** The installed versionCode of a package, -1 when it isn't installed. */
    static long installed(Context c, String pkg) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(pkg, 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    static String installedName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "?"; }
    }

    /** Blocking; call off the main thread. force = now, else at most every 6 hours. */
    static synchronized Result check(Context c, boolean force) {
        SharedPreferences sp = sp(c);
        long now = System.currentTimeMillis();
        if (!force && now - sp.getLong("checked", 0) < INTERVAL_MS) return new Result(SKIPPED, "تم الفحص مؤخراً", available(c));
        String body = null, used = null;
        IOException netError = null;
        for (String url : new String[] {URL_LATEST, URL_MAIN}) {
            try {
                body = fetch(url);
                used = url;
                break;
            } catch (IOException e) {
                netError = e;
                Log.w("SaudiTV-Update", url + ": " + e);
            }
        }
        if (body == null) {
            sp.edit().putString("error", "no-net").apply();
            return new Result(NO_NET, netError instanceof HttpError ? "تعذّر الوصول إلى ملف التحديث (" + netError.getMessage() + ")"
                : "لا يوجد اتصال بالإنترنت", available(c));
        }
        Manifest m = parse(body);
        if (m == null) {
            sp.edit().putString("error", "bad").apply();
            return new Result(BAD, "ملف التحديث غير صالح", available(c));
        }
        sp.edit().putString("manifest", body).putLong("checked", now).putString("source", used).remove("error").apply();
        // a running home screen shows or hides its pill now
        c.sendBroadcast(new Intent(STATE_CHANGED).setPackage(HOME_PKG));
        Manifest a = available(c);
        return a != null ? new Result(AVAILABLE, "تحديث متوفر: " + digits(a.versionName), a)
                         : new Result(NONE, "محدّث · النسخة " + digits(installedName(c)), null);
    }

    /** The last manifest checked, when it is newer than an installed app; else null. */
    static Manifest available(Context c) {
        Manifest m = parse(sp(c).getString("manifest", null));
        return m != null && !m.needed(c).isEmpty() ? m : null;
    }

    /** null for anything that isn't a whole, sane manifest: a broken file never offers an update. */
    static Manifest parse(String body) {
        if (body == null) return null;
        try {
            JSONObject o = new JSONObject(body);
            Manifest m = new Manifest();
            m.versionCode = o.getLong("versionCode");
            m.versionName = o.optString("versionName", String.valueOf(m.versionCode));
            m.tag = o.optString("tag", "v" + m.versionCode);
            m.notes = o.optString("notes", "").trim();
            if (m.notes.length() > 600) m.notes = m.notes.substring(0, 600) + "…";
            JSONArray a = o.getJSONArray("apks");
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                Apk k = new Apk();
                k.pkg = x.getString("package");
                k.file = x.optString("file", k.pkg + ".apk");
                k.url = x.getString("url");
                k.sha256 = x.getString("sha256").toLowerCase(Locale.US);
                k.size = x.getLong("size");
                k.versionCode = x.optLong("versionCode", m.versionCode);
                boolean ours = k.pkg.equals(SELF_PKG) || k.pkg.equals(HOME_PKG);
                if (!ours || !k.url.startsWith(URL_PREFIX) || !k.sha256.matches("[0-9a-f]{64}")
                    || k.size <= 0 || k.size > MAX_APK || k.versionCode <= 0) return null;
                m.apks.add(k);
            }
            return m.versionCode > 0 && !m.apks.isEmpty() ? m : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** An HTTP answer other than 200 (a missing release, a GitHub error page). */
    static final class HttpError extends IOException {
        HttpError(int code) { super(String.valueOf(code)); }
    }

    private static String fetch(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(true);   // github.com -> the release's asset (https all the way)
            con.setUseCaches(false);
            con.setRequestProperty("Cache-Control", "no-cache");
            int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new HttpError(code);
            InputStream in = con.getInputStream();
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.size() > 64 * 1024) throw new IOException("manifest too big");
                }
                return b.toString("UTF-8");
            } finally {
                in.close();
            }
        } finally {
            con.disconnect();
        }
    }

    /** This TV's Home, when it is our home screen (its explicit activity, a new task); else null. */
    static Intent homeIntent(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo r = c.getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (r == null || r.activityInfo == null || !HOME_PKG.equals(r.activityInfo.packageName)) return null;
        return i.setClassName(r.activityInfo.packageName, r.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // ---------- after a self-update ----------

    /** Before this app installs itself (the process ends there): the version it goes to. */
    static void markSelfUpdate(Context c, Manifest m) {
        sp(c).edit().putLong("selfTo", m.versionCode).putString("selfName", m.versionName).commit();   // now: the process ends soon
    }

    static void clearSelfUpdate(Context c) { sp(c).edit().remove("selfTo").remove("selfName").commit(); }

    /** The first launch after a self-update: its version name (once), else null. A self-update that never finished
     *  (cancelled, failed) is forgotten. */
    static String takeJustUpdated(Context c) {
        SharedPreferences sp = sp(c);
        long to = sp.getLong("selfTo", 0);
        if (to <= 0) return null;
        String name = sp.getString("selfName", String.valueOf(to));
        if (AppInstaller.running()) return null;
        clearSelfUpdate(c);
        return installed(c, c.getPackageName()) >= to ? name : null;
    }

    // ---------- labels ----------

    static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) b.append(ch >= '0' && ch <= '9' ? (char) ('٠' + (ch - '0')) : ch);
        return b.toString();
    }

    /** «٧٫٤ ميغابايت» */
    static String size(long bytes) {
        return digits(String.format(Locale.US, "%.1f", bytes / 1048576.0)).replace('.', '٫') + " ميغابايت";
    }
}
