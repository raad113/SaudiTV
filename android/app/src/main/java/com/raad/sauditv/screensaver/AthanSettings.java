package com.raad.sauditv.screensaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The settings' «الأذان» section (SettingsActivity.render calls render here), in its rows and dialogs: on or off with
 * the next athan; each prayer's alert (the full athan, a short one, none); the voice and the Fajr voice (the bundled
 * recordings, or «صوت من عندي»: a file picked on the TV or an address, copied into this app's own storage); the athan's
 * volume; the dua after it; a reminder before it; «تجربة الأذان» (a test now); the permission to show it over other
 * apps when it is missing; the recordings' credits.
 */
final class AthanSettings {
    static final int REQ_FILE = 7401, REQ_FILE_FAJR = 7402;
    private static final long MAX_BYTES = 30L * 1024 * 1024;

    private final SettingsActivity a;
    private final Pickers pickers;
    private final AthanPrefs prefs;
    private boolean askedSettings;   // the TV's settings opened for a permission: the rows again on the way back

    AthanSettings(SettingsActivity a, Pickers pickers) {
        this.a = a;
        this.pickers = pickers;
        this.prefs = new AthanPrefs(a);
    }

    void render() {
        a.header("الأذان");
        // a missing permission first: the TV's own page for it when it has one, else what happens and how to grant it
        if (!AthanService.overlayAllowed(a))
            a.row("السماح بالظهور فوق التطبيقات", overlayPage() != null
                ? "ليظهر الأذان على كامل الشاشة فوق يوتيوب والتطبيقات · اضغط للسماح"
                : "لا توجد صفحة لهذا الإذن في التلفاز: يُسمع الأذان فقط · اضغط لطريقة السماح", R.drawable.ic_eye, this::askOverlay);
        if (!AthanScheduler.exactAllowed(a))
            a.row("السماح بالمنبّه الدقيق", exactPage() != null ? "بدونه قد يتأخر الأذان دقائق · اضغط للسماح"
                : "لا توجد صفحة لهذا الإذن في التلفاز: قد يتأخر الأذان دقائق · اضغط لطريقة السماح", R.drawable.ic_sync, this::askExact);
        a.row("الأذان", onLabel(), R.drawable.ic_volume, () -> { prefs.setOn(!prefs.on()); a.render(); });
        a.row("الصلوات", modesLabel(), R.drawable.ic_screensaver, this::pickPrayers);
        a.row("صوت المؤذن", voiceLabel(false), R.drawable.ic_radio, () -> pickVoice(false));
        a.row("صوت أذان الفجر", voiceLabel(true), R.drawable.ic_radio, () -> pickVoice(true));
        a.row("مستوى صوت الأذان", a.num(prefs.volume()) + Prefs.percent(a), R.drawable.ic_volume, this::pickVolume);
        a.row("دعاء بعد الأذان", prefs.dua() ? "يظهر على الشاشة بعد الأذان" : "متوقف", R.drawable.ic_star,
            () -> { prefs.setDua(!prefs.dua()); a.render(); });
        a.row("تذكير قبل الأذان", reminderLabel(prefs.reminder()), R.drawable.ic_sync, this::pickReminder);
        a.row("تجربة الأذان", "شغّله الآن كما يأتي في وقته", R.drawable.ic_play, this::pickTest);
        a.row("حقوق التسجيلات", "أصوات الأذان ورخصها", R.drawable.ic_category, this::showCredits);
    }

    // ---------------------------------------------------------------- labels

    /** «يعمل · التالي: العصر ٣:١٥ م» or «متوقف» */
    private String onLabel() {
        if (!prefs.on()) return "متوقف";
        AthanPlan.Settings s = prefs.settings();
        if (!s.any()) return "يعمل · كل الصلوات بدون تنبيه";
        try {
            AthanPlan.Place place = prefs.place();
            AthanPlan.Event e = AthanPlan.next(place, s, System.currentTimeMillis(), AthanScheduler.HIJRI);
            if (e == null) return "يعمل";
            java.util.GregorianCalendar c = new java.util.GregorianCalendar(place.tz);
            c.setTimeInMillis(e.athanAt);
            boolean friday = c.get(java.util.Calendar.DAY_OF_WEEK) == java.util.Calendar.FRIDAY;
            return "يعمل · التالي: " + AthanPlan.name(e.prayer, friday) + " "
                + Prefs.digits(a, AthanPlan.time12(c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)));
        } catch (RuntimeException x) { return "يعمل"; }
    }

    /** «الخمس: الأذان كاملاً», else each prayer with its alert */
    private String modesLabel() {
        String first = prefs.mode(0);
        boolean same = true;
        for (int i = 1; i < AthanPlan.PRAYERS.length; i++) same &= prefs.mode(i).equals(first);
        if (same) return "الصلوات الخمس: " + AthanPlan.modeLabel(first);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < AthanPlan.PRAYERS.length; i++) parts.add(AthanPlan.NAMES[i] + " " + shortMode(prefs.mode(i)));
        return Prefs.join(parts, " · ");
    }

    private static String shortMode(String m) {
        return AthanPlan.FULL.equals(m) ? "كاملاً" : AthanPlan.SHORT.equals(m) ? "قصير" : "بدون";
    }

    private String voiceLabel(boolean fajr) {
        String v = prefs.voice(fajr);
        if (AthanVoices.CUSTOM.equals(v)) {
            String n = prefs.customName(fajr);
            return "صوت من عندي" + (n.isEmpty() ? "" : ": ⁨" + n + "⁩");
        }
        return AthanVoices.label(v, fajr);
    }

    private String reminderLabel(int m) {
        if (m == 0) return "بدون";
        return "قبل " + a.num(m) + " " + (m <= 10 ? "دقائق" : "دقيقة") + " (شريط على الشاشة فقط)";
    }

    // ---------------------------------------------------------------- pickers

    /** the five prayers, each with its alert; one picked: full, short or none, then the list again */
    private void pickPrayers() {
        String[] labels = new String[AthanPlan.PRAYERS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = AthanPlan.NAMES[i] + ": " + AthanPlan.modeLabel(prefs.mode(i));
        pickers.dialog().setTitle("الصلوات")
            .setItems(labels, (d, i) -> pickMode(i))
            .setPositiveButton("تم", (d, w) -> a.render())
            .show();
    }

    private void pickMode(final int prayer) {
        String[] labels = new String[AthanPlan.MODES.length];
        int sel = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = AthanPlan.MODES[i][1];
            if (AthanPlan.MODES[i][0].equals(prefs.mode(prayer))) sel = i;
        }
        pickers.dialog().setTitle(AthanPlan.NAMES[prayer])
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                prefs.setMode(prayer, AthanPlan.MODES[i][0]);
                d.dismiss();
                a.render();   // the row's value now, under the list that opens again
                pickPrayers();
            }).show();
    }

    /** the bundled voices (the Fajr list with the Fajr-only ones), then «صوت من عندي…» */
    private void pickVoice(final boolean fajr) {
        final List<AthanVoices.Voice> voices = AthanVoices.offered(fajr);
        String[] labels = new String[voices.size() + 1];
        int sel = -1;
        String cur = prefs.voice(fajr);
        for (int i = 0; i < voices.size(); i++) {
            labels[i] = voices.get(i).ar;
            if (voices.get(i).id.equals(cur)) sel = i;
        }
        labels[voices.size()] = "صوت من عندي…";
        if (AthanVoices.CUSTOM.equals(cur)) sel = voices.size();
        pickers.dialog().setTitle(fajr ? "صوت أذان الفجر" : "صوت المؤذن")
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                d.dismiss();
                if (i < voices.size()) { prefs.setVoice(fajr, voices.get(i).id); a.render(); }
                else pickCustom(fajr);
            }).show();
    }

    /** «صوت من عندي»: the one kept here, a file on the TV, or an address */
    private void pickCustom(final boolean fajr) {
        final boolean kept = AthanPrefs.customFile(a, fajr).isFile() && !prefs.customName(fajr).isEmpty();
        List<String> labels = new ArrayList<>();
        if (kept) labels.add("المحفوظ: ⁨" + prefs.customName(fajr) + "⁩");
        labels.add("ملف من التلفاز أو ذاكرة USB…");
        labels.add("رابط ملف صوتي…");
        pickers.dialog().setTitle("صوت من عندي (يبقى على هذا التلفاز فقط)")
            .setItems(labels.toArray(new String[0]), (d, i) -> {
                int k = kept ? i : i + 1;
                if (k == 0) { prefs.setVoice(fajr, AthanVoices.CUSTOM); a.render(); }
                else if (k == 1) openFile(fajr);
                else askUrl(fajr);
            }).show();
    }

    private void openFile(boolean fajr) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
        try { a.startActivityForResult(i, fajr ? REQ_FILE_FAJR : REQ_FILE); }
        catch (ActivityNotFoundException e) { a.toast("لا يوجد في هذا التلفاز منتقي ملفات، استعمل «رابط ملف صوتي»"); }
    }

    /** SettingsActivity.onActivityResult: a file picked for «صوت من عندي» */
    boolean onResult(int req, int code, Intent data) {
        if (req != REQ_FILE && req != REQ_FILE_FAJR) return false;
        if (code != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        final boolean fajr = req == REQ_FILE_FAJR;
        final Uri uri = data.getData();
        final Context app = a.getApplicationContext();
        pickers.loadThen(() -> {
            String name = displayName(app, uri);
            try (InputStream in = app.getContentResolver().openInputStream(uri)) { return keep(app, in, fajr, name); }
        }, err -> saved(fajr, err));
        return true;
    }

    private void askUrl(final boolean fajr) {
        final EditText in = new EditText(a);
        in.setHint("https://…/athan.mp3");
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setTextDirection(View.TEXT_DIRECTION_LTR);
        final AlertDialog d = pickers.dialog().setTitle("رابط ملف الأذان (MP3 أو OGG أو M4A)").setView(in)
            .setPositiveButton("تنزيل", (x, w) -> {
                final String u = in.getText().toString().trim();
                if (!AthanVoices.validUrl(u)) { a.toast("رابط غير صالح: يبدأ بـ http:// أو https://"); return; }
                final Context app = a.getApplicationContext();
                pickers.loadThen(() -> download(app, u, fajr), err -> saved(fajr, err));
            })
            .setNegativeButton("إلغاء", null).show();
        in.setOnEditorActionListener((v, action, ev) -> { d.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); return true; });
        in.requestFocus();
    }

    /** what keep() answers once the sound is kept; anything else says why not (null: it failed on the way) */
    private static final String KEPT = "kept";

    private void saved(boolean fajr, String result) {
        if (KEPT.equals(result)) {
            prefs.setVoice(fajr, AthanVoices.CUSTOM);
            a.toast("حُفظ الصوت على هذا التلفاز");
        } else a.toast(result == null ? "تعذّر تنزيل الصوت أو حفظه" : result);
        a.render();
    }

    private static String download(Context c, String u, boolean fajr) throws Exception {
        HttpURLConnection con = null;
        try {
            URL url = new URL(u);
            for (int hops = 0; ; hops++) {   // http -> https redirects too (HttpURLConnection follows same-protocol ones only)
                con = (HttpURLConnection) url.openConnection();
                con.setConnectTimeout(12000);
                con.setReadTimeout(20000);
                con.setInstanceFollowRedirects(false);
                con.setRequestProperty("User-Agent", Radio.userAgent());
                int code = con.getResponseCode();
                if (code >= 300 && code < 400 && con.getHeaderField("Location") != null && hops < 5) {
                    url = new URL(url, con.getHeaderField("Location"));
                    con.disconnect();
                    continue;
                }
                if (code != 200) return "تعذّر التنزيل (" + code + ")";
                break;
            }
            if (con.getContentLength() > MAX_BYTES) return "الملف أكبر من " + (MAX_BYTES >> 20) + " ميغابايت";
            String host = Uri.parse(u).getHost();
            String path = Uri.parse(u).getLastPathSegment();
            try (InputStream in = con.getInputStream()) { return keep(c, in, fajr, path == null ? host : path); }
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** copies the sound into the app's own storage (a temporary file first), checks it plays; KEPT, or why not */
    private static String keep(Context c, InputStream in, boolean fajr, String name) throws Exception {
        if (in == null) return "تعذّر فتح الملف";
        File dst = AthanPrefs.customFile(c, fajr), tmp = new File(dst.getPath() + ".part");
        long n = 0;
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                n += r;
                if (n > MAX_BYTES) { out.close(); tmp.delete(); return "الملف أكبر من " + (MAX_BYTES >> 20) + " ميغابايت"; }
                out.write(buf, 0, r);
            }
        }
        long ms = 0;
        MediaMetadataRetriever mr = new MediaMetadataRetriever();
        try {
            mr.setDataSource(tmp.getPath());
            String d = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            ms = d == null ? 0 : Long.parseLong(d);
        } catch (RuntimeException ignored) {
        } finally {
            try { mr.release(); } catch (Exception ignored) { }
        }
        if (ms <= 0) { tmp.delete(); return "هذا ليس ملفاً صوتياً يعمل على التلفاز"; }
        if (dst.exists() && !dst.delete()) { tmp.delete(); return "تعذّر الحفظ"; }
        if (!tmp.renameTo(dst)) { tmp.delete(); return "تعذّر الحفظ"; }
        new AthanPrefs(c).setCustomName(fajr, name == null || name.isEmpty() ? "ملف صوتي" : name);
        return KEPT;
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (RuntimeException ignored) { }
        return uri.getLastPathSegment();
    }

    /** the athan's own volume, 10 … 100 % (the TV's volume apart) */
    private void pickVolume() {
        final int n = (AthanPlan.VOLUME_MAX - AthanPlan.VOLUME_MIN) / AthanPlan.VOLUME_STEP + 1;
        String[] labels = new String[n];
        int sel = 0;
        for (int i = 0; i < n; i++) {
            int v = AthanPlan.VOLUME_MIN + i * AthanPlan.VOLUME_STEP;
            labels[i] = a.num(v) + Prefs.percent(a);
            if (v == prefs.volume()) sel = i;
        }
        pickers.dialog().setTitle("مستوى صوت الأذان")
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                prefs.setVolume(AthanPlan.VOLUME_MIN + i * AthanPlan.VOLUME_STEP);
                d.dismiss();
                a.render();
            }).show();
    }

    private void pickReminder() {
        String[] labels = new String[AthanPlan.REMINDERS.length];
        int sel = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = AthanPlan.REMINDERS[i] == 0 ? "بدون" : "قبل " + a.num(AthanPlan.REMINDERS[i]) + " "
                + (AthanPlan.REMINDERS[i] <= 10 ? "دقائق" : "دقيقة");
            if (AthanPlan.REMINDERS[i] == prefs.reminder()) sel = i;
        }
        pickers.dialog().setTitle("تذكير قبل الأذان (شريط على الشاشة)")
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                prefs.setReminder(AthanPlan.REMINDERS[i]);
                d.dismiss();
                a.render();
            }).show();
    }

    /** «تجربة الأذان»: the full athan (Dhuhr), Fajr's, the short alert or the reminder, now */
    private void pickTest() {
        final String[][] tests = {
            {"dhuhr", AthanPlan.KIND_FULL, "الأذان كاملاً (" + AthanPlan.NAMES[AthanPlan.DHUHR] + ")"},
            {"fajr", AthanPlan.KIND_FULL, "أذان الفجر"},
            {"asr", AthanPlan.KIND_SHORT, "تنبيه قصير"},
            {"maghrib", AthanPlan.KIND_REMINDER, "التذكير قبل الأذان"},
        };
        String[] labels = new String[tests.length];
        for (int i = 0; i < tests.length; i++) labels[i] = tests[i][2];
        pickers.dialog().setTitle("تجربة الأذان")
            .setItems(labels, (d, i) -> {
                int prayer = AthanPlan.prayer(tests[i][0]);
                int what = AthanReceiver.fire(a, AthanAdb.testEvent(a, prayer, tests[i][1], System.currentTimeMillis()), true);
                if (what < 0) a.toast("تعذّر تشغيل الأذان");
            }).show();
    }

    /** SettingsActivity.onRestart: back from the TV's settings opened here (its rows are drawn again) */
    boolean backFromSettings() {
        boolean was = askedSettings;
        askedSettings = false;
        return was;
    }

    /** the TV's page for «الظهور فوق التطبيقات» (this app's own, else the list), null when it has none */
    private Intent overlayPage() {
        if (Build.VERSION.SDK_INT < 23) return null;
        Intent[] tries = {
            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName())),
            new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        };
        for (Intent i : tries) if (i.resolveActivity(a.getPackageManager()) != null) return i;
        return null;
    }

    /** the TV's page for exact alarms (Android 12+), null when it has none */
    private Intent exactPage() {
        if (Build.VERSION.SDK_INT < 31) return null;
        Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + a.getPackageName()));
        return i.resolveActivity(a.getPackageManager()) != null ? i : null;
    }

    private void askOverlay() {
        Intent page = overlayPage();
        if (page != null) {
            try { askedSettings = true; a.startActivity(page); return; } catch (RuntimeException ignored) { askedSettings = false; }
        }
        noPage("الظهور فوق التطبيقات", "هذا التلفاز ليس فيه صفحة لهذا الإذن، فيُسمع الأذان بدون شاشته، "
            + "ويوقفه زر الإيقاف المؤقت في الريموت.", "SYSTEM_ALERT_WINDOW", "optimize/saver.sh athan on");
    }

    private void askExact() {
        Intent page = exactPage();
        if (page != null) {
            try { askedSettings = true; a.startActivity(page); return; } catch (RuntimeException ignored) { askedSettings = false; }
        }
        noPage("المنبّه الدقيق", "هذا التلفاز ليس فيه صفحة لهذا الإذن، فقد يتأخر الأذان دقائق (وإن تأخر أكثر من ٣ دقائق لا يُؤذَّن).",
            "SCHEDULE_EXACT_ALARM", null);
    }

    /** a permission the TV has no page for: what that means, and how to grant it from a computer (docs/SETUP.md) */
    private void noPage(String title, String what, String op, String saver) {
        String adb = "adb shell appops set " + a.getPackageName() + " " + op + " allow";
        pickers.dialog().setTitle(title)
            .setMessage(what + "\n\nللسماح به من كمبيوتر متصل بالتلفاز:\n"
                + (saver == null ? "" : ltr(saver) + "\nأو:\n") + ltr(adb)
                + "\n\nالتفاصيل في " + ltr("docs/SETUP.md") + " (الأذان).")
            .setPositiveButton("حسناً", null).show();
    }

    /** a command kept left to right inside the Arabic text (an isolate, LRI … PDI) */
    private static String ltr(String s) { return "⁦" + s + "⁩"; }

    /** «حقوق التسجيلات»: each bundled recording, its author, licence and source (assets/athan/CREDITS.txt) */
    private void showCredits() {
        StringBuilder b = new StringBuilder();
        for (AthanVoices.Voice v : AthanVoices.BUNDLED) {
            if (b.length() > 0) b.append("\n\n");
            b.append(v.ar).append("\n⁦").append(v.title).append(" · ").append(v.author).append("\n").append(v.licence)
             .append(" · ").append(v.licenceUrl).append("\n").append(v.source).append("⁩");
        }
        b.append("\n\nصوت من عندي يُحفظ في ذاكرة هذا التطبيق على هذا التلفاز فقط، ولا يُرفع أو يُشارك.");
        pickers.dialog().setTitle("حقوق التسجيلات").setMessage(b.toString()).setPositiveButton("حسناً", null).show();
    }
}
