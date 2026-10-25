package com.raad.sauditv.screensaver;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Downloads, verifies and installs an update of both apps (AppUpdate's manifest), one PackageInstaller session per app.
 *
 * 1. Every APK the TV needs is downloaded to this app's cache (cache/update) and checked before anything is installed:
 *    its size and sha256 as the manifest says, then the APK itself: its package name, a versionCode that is the
 *    manifest's and newer than the installed one, and the signing certificate of this app (the project's key). A
 *    failed or cancelled download, or any mismatch, deletes the files and leaves both installed apps as they are.
 * 2. The home screen is installed first, then this app last. This app is the installer: installing it ends its own
 *    process, so nothing can come after it. Each session reports to InstallReceiver; Android asks the user to confirm
 *    (STATUS_PENDING_USER_ACTION, its own dialog), which the settings screen opens while it is in front.
 *    Updating the home screen closes it, and Android would then bring back whatever app was open before it (on the
 *    Google TV Streamer: YouTube) once the settings screen goes. So right after the home screen's install the settings
 *    screen puts the new home screen back behind itself (Listener.homeReplaced). When this app's own install ends the
 *    process, Android closes the settings screen and the TV is back on the new home screen; the next launch of the
 *    settings says «تم التحديث إلى …» (AppUpdate.takeJustUpdated).
 *    Each APK installs whole or not at all (a session is atomic). If this app's step is cancelled after the home
 *    screen's succeeded, the settings row still offers the update and installs only what is left.
 * One update at a time, in this process; the settings screen follows it through a Listener.
 */
final class AppInstaller {
    static final int DOWNLOADING = 1, VERIFYING = 2, INSTALLING = 3, CONFIRM = 4, DONE = 5, FAILED = 6;
    private static final String TAG = "SaudiTV-Update";

    static final class Status {
        final int phase, percent;   // percent -1: no progress to show
        final String text;
        Status(int phase, int percent, String text) { this.phase = phase; this.percent = percent; this.text = text; }
        boolean finished() { return phase == DONE || phase == FAILED; }
    }

    interface Listener {
        void onStatus(Status s);
        /** the home screen was just updated (which closed it): put it back behind the settings screen */
        void homeReplaced();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Listener listener;
    private static volatile Status last;
    private static boolean running;
    private static volatile boolean cancelled;
    private static AppUpdate.Manifest manifest;
    private static final List<Step> queue = new ArrayList<>();
    private static final List<String> done = new ArrayList<>();   // packages installed in this run
    private static Intent confirm;   // Android's own confirm screen, until the settings screen opens it
    private static volatile int session = -1;

    private static final class Step {
        final AppUpdate.Apk apk;
        final File file;
        Step(AppUpdate.Apk apk, File file) { this.apk = apk; this.file = file; }
    }

    private static final class Fail extends Exception {
        Fail(String message) { super(message); }
    }

    private AppInstaller() {}

    static boolean running() { return running; }

    /** Main thread. The settings screen follows the update; the last status is replayed at once. */
    static void setListener(Listener l) {
        listener = l;
        if (l != null && last != null) l.onStatus(last);
    }

    /** A closing settings screen lets go (only its own listener: a newer screen may have taken over). */
    static void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    /** Android's confirm screen for the current session, once (the settings screen starts it). */
    static Intent takeConfirm() {
        Intent i = confirm;
        confirm = null;
        return i;
    }

    /** Main thread. Downloads and checks everything first, then installs. */
    static void start(final Context c, final AppUpdate.Manifest m) {
        if (running) return;
        final Context app = c.getApplicationContext();
        running = true;
        cancelled = false;
        manifest = m;
        queue.clear();
        done.clear();
        confirm = null;
        session = -1;
        post(DOWNLOADING, 0, "جارٍ التنزيل… " + AppUpdate.digits(app, "0") + Prefs.percent(app));
        new Thread(() -> prepare(app, m)).start();
    }

    /** The «إلغاء» button: stops a download, or drops a session waiting for the user's confirmation. */
    static void cancel(Context c) {
        if (!running) return;
        cancelled = true;
        if (last != null && last.phase == CONFIRM) {
            abandon(c.getApplicationContext(), session);
            fail(c.getApplicationContext(), cancelledText());
        }
    }

    /** Leftovers of an update that never finished (this app was killed, a self-update ran). */
    static void cleanup(Context c) {
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    static File dir(Context c) { return new File(c.getCacheDir(), "update"); }

    // ---------------------------------------------------------------- 1. download and verify (a worker thread)

    private static void prepare(Context app, AppUpdate.Manifest m) {
        try {
            abandonMine(app);
            File dir = dir(app);
            cleanup(app);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new Fail("تعذّر حفظ التحديث على التلفاز");
            List<AppUpdate.Apk> need = m.needed(app);
            if (need.isEmpty()) {
                finish(app, "محدّث · النسخة " + AppUpdate.version(app, AppUpdate.installedName(app)));
                return;
            }
            long total = 0;
            for (AppUpdate.Apk a : need) total += a.size;
            if (dir.getUsableSpace() < total * 3 + 20L * 1024 * 1024) throw new Fail("لا توجد مساحة كافية على التلفاز للتحديث");
            List<Step> steps = new ArrayList<>();
            long before = 0;
            for (AppUpdate.Apk a : need) {
                File f = new File(dir, a.file);
                download(app, a, f, before, total);
                before += a.size;
                steps.add(new Step(a, f));
            }
            post(VERIFYING, 100, "جارٍ التحقق من الملفات…");
            for (Step s : steps) verify(app, s);
            final List<Step> ready = steps;
            MAIN.post(() -> {
                if (!running) return;
                queue.addAll(ready);
                next(app);
            });
        } catch (Fail f) {
            final String text = f.getMessage();
            MAIN.post(() -> fail(app, text));
        } catch (RuntimeException e) {
            Log.w(TAG, "prepare", e);
            MAIN.post(() -> fail(app, "تعذّر التحديث، لم يتغير شيء"));
        }
    }

    private static void download(Context app, AppUpdate.Apk a, File out, long before, long total) throws Fail {
        File part = new File(out.getPath() + ".part");
        long got = 0;
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(a.url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(true);   // github.com -> release-assets.githubusercontent.com, https both
            con.setRequestProperty("Accept-Encoding", "identity");
            int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new Fail("تعذّر تنزيل التحديث (رمز " + AppUpdate.digits(app, String.valueOf(code)) + ")، لم يتغير شيء");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = con.getInputStream();
            OutputStream o = new FileOutputStream(part);
            try {
                byte[] buf = new byte[65536];
                int n, shown = -1;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled) throw new Fail(cancelledText());
                    got += n;
                    if (got > a.size) throw new Fail(mismatchText());
                    md.update(buf, 0, n);
                    o.write(buf, 0, n);
                    int pct = (int) ((before + got) * 100 / Math.max(1, total));
                    if (pct != shown) {
                        shown = pct;
                        post(DOWNLOADING, pct, "جارٍ التنزيل… " + AppUpdate.digits(app, String.valueOf(pct)) + Prefs.percent(app));
                    }
                }
            } finally {
                o.close();
                in.close();
            }
            if (cancelled) throw new Fail(cancelledText());
            if (got != a.size || !hex(md.digest()).equals(a.sha256)) throw new Fail(mismatchText());
            if (!part.renameTo(out)) throw new Fail("تعذّر حفظ التحديث على التلفاز");
        } catch (Fail f) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw f;
        } catch (UnknownHostException e) {
            part.delete();
            throw new Fail("لا يوجد اتصال بالإنترنت، لم يتغير شيء");
        } catch (IOException e) {
            part.delete();
            Log.w(TAG, "download " + a.url + ": " + e);
            throw new Fail(got == 0 ? "لا يوجد اتصال بالإنترنت، لم يتغير شيء" : "انقطع التنزيل، لم يتغير شيء. حاول مرة أخرى");
        } catch (java.security.NoSuchAlgorithmException e) {
            part.delete();
            throw new Fail("تعذّر التحقق من التحديث");
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** The APK must be the package the manifest names, at its versionCode, newer than the installed one, and signed
     *  with this app's own key (both apps share the project's key). */
    private static void verify(Context app, Step s) throws Fail {
        PackageManager pm = app.getPackageManager();
        String path = s.file.getPath();
        boolean modern = Build.VERSION.SDK_INT >= 28;
        PackageInfo p = pm.getPackageArchiveInfo(path, modern ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        if (modern && (p == null || p.signingInfo == null)) {   // some builds leave signingInfo out for an archive
            modern = false;
            p = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES);
        }
        if (p == null) throw new Fail("ملف التحديث غير صالح، لم يُثبَّت شيء");
        long code = Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        if (!s.apk.pkg.equals(p.packageName) || code != s.apk.versionCode)
            throw new Fail("ملف التحديث لا يطابق سعودي TV (الحزمة أو النسخة)، لم يُثبَّت شيء");
        if (code <= AppUpdate.installed(app, s.apk.pkg)) throw new Fail("النسخة في الملف ليست أحدث من المثبتة، لم يُثبَّت شيء");
        Set<String> theirs = certs(modern, p), ours;
        try {
            ours = certs(modern, pm.getPackageInfo(app.getPackageName(),
                modern ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES));
        } catch (PackageManager.NameNotFoundException e) {
            throw new Fail("تعذّر التحقق من التحديث");
        }
        if (theirs.isEmpty() || !theirs.equals(ours)) throw new Fail("ملف التحديث ليس موقّعاً بمفتاح سعودي TV، لم يُثبَّت شيء");
    }

    @SuppressWarnings("deprecation")
    private static Set<String> certs(boolean modern, PackageInfo p) {
        Signature[] sigs = modern && p.signingInfo != null ? p.signingInfo.getApkContentsSigners() : p.signatures;
        Set<String> out = new HashSet<>();
        if (sigs == null) return out;
        try {
            for (Signature s : sigs) out.add(hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
        } catch (java.security.NoSuchAlgorithmException ignored) { }
        return out;
    }

    // ---------------------------------------------------------------- 2. install, one session per app (main thread)

    private static void next(final Context app) {
        if (!running) return;
        if (queue.isEmpty()) {
            finish(app, "تم التحديث إلى " + AppUpdate.version(app, manifest.versionName));
            return;
        }
        final Step s = queue.get(0);
        final boolean self = s.apk.pkg.equals(app.getPackageName());
        post(INSTALLING, -1, self
            ? "جارٍ تثبيت تطبيق الإعدادات وشاشة التوقف… يُغلق بعدها ويعود التلفاز إلى الشاشة الرئيسية"
            : "جارٍ تثبيت الواجهة الرئيسية…");
        if (self) AppUpdate.markSelfUpdate(app, manifest);   // the next launch says «تم التحديث إلى …»
        new Thread(() -> {
            try {
                commit(app, s);
            } catch (Exception e) {
                Log.w(TAG, "session " + s.apk.pkg, e);
                MAIN.post(() -> fail(app, "تعذّر بدء التثبيت" + (done.isEmpty() ? "، لم يتغير شيء" : partial())));
            }
        }).start();
    }

    private static void commit(Context app, Step s) throws IOException {
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(s.apk.pkg);
        p.setSize(s.file.length());
        if (Build.VERSION.SDK_INT >= 26) p.setInstallReason(PackageManager.INSTALL_REASON_USER);
        // this app updating itself, or an app it installed before: Android may skip its confirm screen
        // (UPDATE_PACKAGES_WITHOUT_USER_ACTION); else it asks, as for any install
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        PackageInstaller.Session ses = pi.openSession(id);
        try {
            OutputStream out = ses.openWrite("base.apk", 0, s.file.length());
            InputStream in = new FileInputStream(s.file);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                ses.fsync(out);
            } finally {
                in.close();
                out.close();
            }
            Intent cb = new Intent(app, InstallReceiver.class).putExtra("pkg", s.apk.pkg);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session = id;
            ses.commit(PendingIntent.getBroadcast(app, id, cb, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            ses.abandon();
            throw e;
        } finally {
            ses.close();
        }
        //noinspection ResultOfMethodCallIgnored
        s.file.delete();   // the session holds its own copy now
    }

    /** InstallReceiver, main thread: a session's result. */
    static void onStatus(Context app, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int id = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        String pkg = i.getStringExtra("pkg"), msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "session " + id + " " + pkg + ": status " + status + (msg == null ? "" : " " + msg));
        if (!running || id != session) {
            // not this run's (e.g. the new version of this app hearing about its own install): nothing to continue
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) abandon(app, id);
            return;
        }
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                @SuppressWarnings("deprecation") Intent ask = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (ask == null) { fail(app, "تعذّر فتح نافذة التثبيت" + (done.isEmpty() ? "، لم يتغير شيء" : partial())); return; }
                confirm = ask;
                post(CONFIRM, -1, "وافق على التثبيت في نافذة التلفاز… (إن أُغلقت النافذة فاضغط «إلغاء» وحاول مرة أخرى)");
                if (listener == null) {   // the settings screen isn't up: try from here (Android may not allow it)
                    Intent c = takeConfirm();
                    try { app.startActivity(c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (RuntimeException e) { Log.w(TAG, "confirm: " + e); }
                }
                return;
            }
            case PackageInstaller.STATUS_SUCCESS:
                if (!queue.isEmpty()) queue.remove(0);
                done.add(pkg);
                session = -1;
                if (AppUpdate.HOME_PKG.equals(pkg)) {
                    if (listener != null) listener.homeReplaced();
                    else {   // no settings screen: at least start the home screen (Android may not allow it from here)
                        Intent home = AppUpdate.homeIntent(app);
                        try { if (home != null) app.startActivity(home); } catch (RuntimeException e) { Log.w(TAG, "home: " + e); }
                    }
                }
                next(app);
                return;
            default:
                fail(app, failureText(status, msg));
        }
    }

    private static String failureText(int status, String msg) {
        String rest = done.isEmpty() ? "، لم يتغير شيء" : partial();
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "أُلغي التثبيت" + rest;
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "منع التلفاز التثبيت (حماية Play أو إذن التثبيت)" + rest;
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "تعارض مع النسخة المثبتة (توقيع مختلف؟)" + rest;
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "التحديث لا يناسب هذا التلفاز" + rest;
            case PackageInstaller.STATUS_FAILURE_INVALID: return "ملف التحديث غير صالح" + rest;
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "لا توجد مساحة كافية على التلفاز" + rest;
            case 8: return "انتهت مهلة التثبيت" + rest;   // STATUS_FAILURE_TIMEOUT (Android 14)
            default: return "تعذّر التثبيت" + (msg == null || msg.isEmpty() ? "" : " (" + msg + ")") + rest;
        }
    }

    /** the home screen is updated but this app is not (its own step was cancelled or failed) */
    private static String partial() {
        return ". حُدّثت الواجهة الرئيسية فقط؛ اضغط «تحديث التطبيق» لإكمال التحديث";
    }

    private static String cancelledText() { return "أُلغي التحديث، لم يتغير شيء"; }

    private static String mismatchText() { return "الملف المنزَّل لا يطابق البصمة (تالف أو ناقص)، لم يُثبَّت شيء"; }

    // ---------------------------------------------------------------- state

    private static void finish(final Context app, final String text) {
        MAIN.post(() -> {
            running = false;
            queue.clear();
            cleanup(app);
            app.sendBroadcast(new Intent(AppUpdate.STATE_CHANGED).setPackage(AppUpdate.HOME_PKG));
            post(DONE, 100, text);
        });
    }

    private static void fail(Context app, String text) {
        if (!running) return;
        running = false;
        queue.clear();
        confirm = null;
        session = -1;
        cleanup(app);
        AppUpdate.clearSelfUpdate(app);
        app.sendBroadcast(new Intent(AppUpdate.STATE_CHANGED).setPackage(AppUpdate.HOME_PKG));
        post(FAILED, -1, text);
    }

    private static void post(int phase, int percent, String text) {
        final Status s = new Status(phase, percent, text);
        last = s;
        MAIN.post(() -> { if (listener != null && last == s) listener.onStatus(s); });
    }

    private static void abandon(Context app, int id) {
        if (id < 0) return;
        try { app.getPackageManager().getPackageInstaller().abandonSession(id); }
        catch (RuntimeException e) { Log.w(TAG, "abandon " + id + ": " + e); }
    }

    /** Sessions an earlier, interrupted run of this app left open. */
    private static void abandonMine(Context app) {
        try {
            for (PackageInstaller.SessionInfo s : app.getPackageManager().getPackageInstaller().getMySessions())
                abandon(app, s.getSessionId());
        } catch (RuntimeException e) {
            Log.w(TAG, "sessions: " + e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x));
        return s.toString();
    }
}
