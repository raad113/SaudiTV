package com.raad.sauditv.screensaver;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.TimeZone;

/**
 * «الأذان»'s settings, in the app's one SharedPreferences (Prefs) beside the others, and the place they are computed
 * for (the city, its time zone and the prayer method: Prefs). Defaults: on, every prayer a short alert
 * (AthanPlan.MODE_DEFAULT), the bundled voices (AthanVoices; Fajr its own choice), a reminder 5 minutes before
 * (AthanPlan.REMINDER_DEFAULT; «بدون» can be picked), no dua.
 *
 * Keys: athanOn; athan_fajr … athan_isha (full | short | off); athanVoice, athanFajrVoice (AthanVoices ids or custom);
 * athanVolume (10 … 100); athanDua; athanReminder (minutes, 0 = off, 5 by default); athanCustom_main / athanCustom_fajr (the name of
 * «صوت من عندي», its file in customFile). A change of any key that moves an alarm reschedules it (AthanScheduler.watch).
 */
final class AthanPrefs {
    static final String ON = "athanOn", VOICE = "athanVoice", FAJR_VOICE = "athanFajrVoice", VOLUME = "athanVolume",
        DUA = "athanDua", REMINDER = "athanReminder";

    private final Prefs prefs;
    private final SharedPreferences sp;
    private final Context app;

    AthanPrefs(Context c) {
        prefs = new Prefs(c);
        sp = prefs.raw();
        app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
    }

    static String modeKey(int prayer) { return "athan_" + AthanPlan.PRAYERS[prayer]; }

    boolean on() { return sp.getBoolean(ON, true); }
    void setOn(boolean v) { sp.edit().putBoolean(ON, v).apply(); }

    String mode(int prayer) { return AthanPlan.mode(sp.getString(modeKey(prayer), AthanPlan.MODE_DEFAULT)); }
    void setMode(int prayer, String m) { sp.edit().putString(modeKey(prayer), AthanPlan.mode(m)).apply(); }

    /** the voice of a prayer's athan: Fajr its own (AthanVoices.of) */
    String voice(boolean fajr) {
        return AthanVoices.of(sp.getString(fajr ? FAJR_VOICE : VOICE, fajr ? AthanVoices.FAJR_DEFAULT : AthanVoices.DEFAULT), fajr);
    }
    void setVoice(boolean fajr, String id) { sp.edit().putString(fajr ? FAJR_VOICE : VOICE, id).apply(); }

    int volume() { return AthanPlan.clampVolume(sp.getInt(VOLUME, AthanPlan.VOLUME_DEFAULT)); }
    void setVolume(int v) { sp.edit().putInt(VOLUME, AthanPlan.clampVolume(v)).apply(); }

    boolean dua() { return sp.getBoolean(DUA, false); }
    void setDua(boolean v) { sp.edit().putBoolean(DUA, v).apply(); }

    int reminder() { return AthanPlan.reminder(sp.getInt(REMINDER, AthanPlan.REMINDER_DEFAULT)); }
    void setReminder(int m) { sp.edit().putInt(REMINDER, AthanPlan.reminder(m)).apply(); }

    /** «صوت من عندي»: the name it was picked by (a file's name, an address's host), "" when there is none */
    String customName(boolean fajr) {
        return sp.getString("athanCustom_" + (fajr ? "fajr" : "main"), "");
    }
    void setCustomName(boolean fajr, String name) {
        sp.edit().putString("athanCustom_" + (fajr ? "fajr" : "main"), name == null ? "" : name).apply();
    }

    /** where «صوت من عندي» is kept: the app's own storage, on this TV only (never in a backup: allowBackup is off) */
    static File customFile(Context c, boolean fajr) {
        File dir = new File(c.getFilesDir(), "athan");
        if (!dir.isDirectory() && !dir.mkdirs()) android.util.Log.w(AthanScheduler.TAG, "no " + dir);
        return new File(dir, "custom-" + (fajr ? "fajr" : "main"));
    }

    /** the settings as AthanPlan reads them */
    AthanPlan.Settings settings() {
        AthanPlan.Settings s = new AthanPlan.Settings();
        s.on = on();
        for (int i = 0; i < AthanPlan.PRAYERS.length; i++) s.modes[i] = mode(i);
        s.reminder = reminder();
        return s;
    }

    /** the city (Riyadh until one is picked), its time zone (else the TV's) and the prayer method, as the page has them
     *  (its numbers through the page's own parseFloat), and the page's online times kept for it (AthanOnline) */
    AthanPlan.Place place() {
        String[] city = prefs.city();
        double lat = AladhanTimes.parseFloat(city[1], AladhanTimes.DEFAULT_LAT), lon = AladhanTimes.parseFloat(city[2], AladhanTimes.DEFAULT_LON);
        String tz = prefs.placeTz();
        int method = prefs.prayerMethod();
        return new AthanPlan.Place(lat, lon, TimeZone.getTimeZone(tz), method, AthanOnline.cached(app, city[1], city[2], method, tz));
    }

    String cityName() { return prefs.city()[0]; }

    Prefs prefs() { return prefs; }

    /** the keys whose change moves the next alarm (the place, the method, the athan's on/off, modes and reminder) */
    static boolean movesAlarm(String key) {
        if (key == null) return true;
        switch (key) {
            case "cityLat": case "cityLon": case "cityTz": case "method": case "country": case ON: case REMINDER:
                return true;
            default:
                return key.startsWith("athan_");
        }
    }
}
