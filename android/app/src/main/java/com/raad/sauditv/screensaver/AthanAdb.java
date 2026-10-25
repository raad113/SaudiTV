package com.raad.sauditv.screensaver;

import android.content.Context;
import android.content.Intent;

import java.util.Locale;

/**
 * «الأذان» from ADB (SaverReceiver; optimize/saver.sh athan on|off, athan-test, athan-show, and show):
 *
 *   SET  [--es athan on|off] [--es athan_fajr|athan_dhuhr|athan_asr|athan_maghrib|athan_isha full|short|off]
 *        [--es athan_voice wikimedia|asim|custom] [--es athan_fajr_voice wikimedia|asim|malmo|custom]
 *        [--es athan_volume 10…100] [--es athan_dua on|off] [--es athan_reminder 0|5|10|15|20|30]
 *   ATHAN_TEST --es prayer fajr|dhuhr|asr|maghrib|isha [--es kind full|short|reminder]   (now, as its alarm would;
 *        silent with the TV off, as the alarm)
 *   ATHAN_SHOW   (today's times in the city, each prayer's alert, the voices, the next alarm, the last one, permissions)
 *
 * Every value is checked before anything is saved (check, then save), as SaverReceiver does.
 */
final class AthanAdb {
    private AthanAdb() {}

    private static String onOff(String v) {
        if (v == null) return null;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.equals("true")) return "on";
        if (v.equals("false")) return "off";
        return v.equals("on") || v.equals("off") ? v : "";
    }

    /** null when every athan extra is valid, else the error */
    static String check(Intent i) {
        if ("".equals(onOff(i.getStringExtra("athan")))) return "error: athan is on or off";
        for (String p : AthanPlan.PRAYERS) {
            String m = i.getStringExtra("athan_" + p);
            if (m != null && !AthanPlan.validMode(m.trim().toLowerCase(Locale.ROOT))) return "error: athan_" + p + " is full, short or off";
        }
        String v = i.getStringExtra("athan_voice");
        if (v != null && (AthanVoices.find(v.trim()) == null || AthanVoices.find(v.trim()).fajrOnly) && !AthanVoices.CUSTOM.equals(v.trim()))
            return "error: athan_voice is " + voices(false);
        String fv = i.getStringExtra("athan_fajr_voice");
        if (fv != null && AthanVoices.find(fv.trim()) == null && !AthanVoices.CUSTOM.equals(fv.trim()))
            return "error: athan_fajr_voice is " + voices(true);
        String vol = i.getStringExtra("athan_volume");
        if (vol != null && AthanPlan.volume(vol) < 0) return "error: athan_volume is a percent from 10 to 100";
        if ("".equals(onOff(i.getStringExtra("athan_dua")))) return "error: athan_dua is on or off";
        String r = i.getStringExtra("athan_reminder");
        if (r != null) {
            boolean ok = false;
            for (int m : AthanPlan.REMINDERS) ok |= String.valueOf(m).equals(r.trim());
            if (!ok) return "error: athan_reminder is minutes before the athan: 0 (off) 5 10 15 20 30";
        }
        return null;
    }

    /** saves the athan extras (check() first) */
    static void save(Context c, Intent i) {
        AthanPrefs p = new AthanPrefs(c);
        String on = onOff(i.getStringExtra("athan"));
        if (on != null) p.setOn(on.equals("on"));
        for (int k = 0; k < AthanPlan.PRAYERS.length; k++) {
            String m = i.getStringExtra("athan_" + AthanPlan.PRAYERS[k]);
            if (m != null) p.setMode(k, m.trim().toLowerCase(Locale.ROOT));
        }
        String v = i.getStringExtra("athan_voice");
        if (v != null) p.setVoice(false, v.trim());
        String fv = i.getStringExtra("athan_fajr_voice");
        if (fv != null) p.setVoice(true, fv.trim());
        String vol = i.getStringExtra("athan_volume");
        if (vol != null) p.setVolume(AthanPlan.volume(vol));
        String dua = onOff(i.getStringExtra("athan_dua"));
        if (dua != null) p.setDua(dua.equals("on"));
        String r = i.getStringExtra("athan_reminder");
        if (r != null) p.setReminder(Integer.parseInt(r.trim()));
    }

    private static String voices(boolean fajr) {
        StringBuilder b = new StringBuilder();
        for (AthanVoices.Voice v : AthanVoices.offered(fajr)) b.append(v.id).append(' ');
        return b.append(AthanVoices.CUSTOM).toString();
    }

    /** show's lines: on/off, each prayer's alert, the next alarm, the permissions */
    static String describe(Context c) {
        AthanPrefs p = new AthanPrefs(c);
        StringBuilder modes = new StringBuilder();
        for (int k = 0; k < AthanPlan.PRAYERS.length; k++) modes.append(k == 0 ? "" : " ").append(AthanPlan.PRAYERS[k]).append('=').append(p.mode(k));
        return "\nathan: " + (p.on() ? "on" : "off") + " (" + modes + ")"
            + "\nathan_next: " + AthanScheduler.state(c).getString("next", "(not scheduled yet)")
            + "\nathan_screen_over_apps: " + (AthanService.overlayAllowed(c) ? "yes" : "no (optimize/saver.sh athan on grants it)")
            + "\nathan_exact_alarms: " + (AthanScheduler.exactAllowed(c) ? "yes" : "no");
    }

    /** athan-show: today's athans in the city, the settings, the next alarm and the last one */
    static String show(Context c) {
        AthanPrefs p = new AthanPrefs(c);
        AthanPlan.Place place = p.place();
        int[] today = AthanPlan.cityDate(place.tz, System.currentTimeMillis());
        int[] m = AthanPlan.day(place, today, AthanScheduler.HIJRI);
        StringBuilder b = new StringBuilder("athan: " + (p.on() ? "on" : "off") + "\ncity: " + p.cityName() + " (" + place.lat + ", "
            + place.lng + ") " + place.tz.getID() + ", method " + place.method + " " + Geo.methodLabel(place.method)
            + "\ntoday (" + today[0] + "-" + two(today[1]) + "-" + two(today[2]) + ", "
            + (AthanOnline.has(c, today) ? "Aladhan, as the page online" : "offline formula, as the page offline") + "):");
        for (int k = 0; k < AthanPlan.PRAYERS.length; k++) {
            b.append("\n  ").append(AthanPlan.PRAYERS[k]).append(' ')
             .append(m[k] == AthanPlan.NONE ? "--:--" : PrayerTimes.hhmm(((m[k] % 1440) + 1440) % 1440)).append("  ").append(p.mode(k));
        }
        b.append("\nvoice: ").append(p.voice(false)).append(customNote(p, false))
         .append(", fajr: ").append(p.voice(true)).append(customNote(p, true))
         .append("\nvolume: ").append(p.volume()).append("%, dua: ").append(p.dua() ? "on" : "off")
         .append(", reminder: ").append(p.reminder() == 0 ? "off" : p.reminder() + " min before")
         .append(describe(c))
         .append("\nathan_last: ").append(AthanScheduler.state(c).getString("last", "(none yet)"))
         .append("\naladhan_last: ").append(AthanOnline.last(c));
        AthanOnline.refresh(c);   // a day still without the page's online answer: asked now (its job)
        return b.toString();
    }

    private static String customNote(AthanPrefs p, boolean fajr) {
        return AthanVoices.CUSTOM.equals(p.voice(fajr)) ? " (" + p.customName(fajr) + ")" : "";
    }

    private static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    /** athan-test: the prayer's event now (its alert, or the kind asked), as the alarm would do it */
    static String test(Context c, Intent i) {
        int prayer = AthanPlan.prayer(i.getStringExtra("prayer"));
        if (prayer < 0) return "error: prayer is fajr, dhuhr, asr, maghrib or isha";
        AthanPrefs p = new AthanPrefs(c);
        String kind = i.getStringExtra("kind");
        if (kind == null || kind.trim().isEmpty()) kind = AthanPlan.SHORT.equals(p.mode(prayer)) ? AthanPlan.KIND_SHORT : AthanPlan.KIND_FULL;
        kind = kind.trim().toLowerCase(Locale.ROOT);
        if (!kind.equals(AthanPlan.KIND_FULL) && !kind.equals(AthanPlan.KIND_SHORT) && !kind.equals(AthanPlan.KIND_REMINDER))
            return "error: kind is full, short or reminder";
        long now = System.currentTimeMillis();
        AthanPlan.Event e = testEvent(c, prayer, kind, now);
        int what = AthanReceiver.fire(c, e, true);
        return "athan-test " + AthanPlan.PRAYERS[prayer] + " " + kind + ": "
            + (what < 0 ? "the service could not start" : AthanPlan.OUTCOMES[what])
            + (what == AthanPlan.PLAY_FULL && !AthanService.overlayAllowed(c) ? " (no screen: SYSTEM_ALERT_WINDOW not granted)" : "");
    }

    /** a test's event: due now, for the prayer's time today (what its screen shows); a reminder 10 minutes ahead */
    static AthanPlan.Event testEvent(Context c, int prayer, String kind, long now) {
        AthanPrefs p = new AthanPrefs(c);
        AthanPlan.Place place = p.place();
        long at = AthanPlan.moments(place, AthanPlan.cityDate(place.tz, now), AthanScheduler.HIJRI)[prayer];
        if (at == Long.MIN_VALUE) at = now;
        if (AthanPlan.KIND_REMINDER.equals(kind)) at = now + 10 * 60000L;
        return new AthanPlan.Event(prayer, kind, now, at);
    }
}
