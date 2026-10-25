package com.raad.sauditv.screensaver;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * «الأذان»'s decisions, in plain Java (tests/java_test.py compiles it without Android): the settings' values, each
 * prayer's moment (PrayerTimes, the page's own times, at the city's wall clock), the next thing to do (an athan, a short
 * alert or a reminder) and, when its alarm comes, whether to do it: never on a TV that is off or in standby (the screen
 * not interactive), never long after its time (an alarm held back while the TV slept), never once turned off.
 *
 * AthanScheduler (Android) keeps one alarm for next(); AthanService does what decide() says.
 */
final class AthanPlan {
    private AthanPlan() {}

    // ---------------------------------------------------------------- the prayers and the settings' values

    /** ids (saver.sh, the prefs' keys athan_<id>), in the day's order */
    static final String[] PRAYERS = {"fajr", "dhuhr", "asr", "maghrib", "isha"};
    static final String[] NAMES = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};
    /** each prayer's place in PrayerTimes.minutes() */
    static final int[] TIMES = {PrayerTimes.FAJR, PrayerTimes.DHUHR, PrayerTimes.ASR, PrayerTimes.MAGHRIB, PrayerTimes.ISHA};
    static final int FAJR = 0, DHUHR = 1;

    /** a prayer's alert: the whole athan (pauses what plays), a short one (a banner and the opening takbir), none */
    static final String FULL = "full", SHORT = "short", OFF = "off";
    static final String[][] MODES = {{FULL, "الأذان كاملاً"}, {SHORT, "تنبيه قصير"}, {OFF, "بدون"}};
    /** every prayer's alert until one is picked: the short one */
    static final String MODE_DEFAULT = SHORT;
    /** «تذكير قبل الأذان»: minutes, 0 = off; 5 by default */
    static final int[] REMINDERS = {0, 5, 10, 15, 20, 30};
    static final int REMINDER_DEFAULT = 5;
    /** the athan's own volume, percent (as the radio's: RadioStations.gain squares it) */
    static final int VOLUME_MIN = 10, VOLUME_MAX = 100, VOLUME_STEP = 10, VOLUME_DEFAULT = 80;

    /** a prayer id (fajr, dhuhr … or Arabic names) -> 0 … 4; -1 unknown */
    static int prayer(String id) {
        if (id == null) return -1;
        String s = id.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < PRAYERS.length; i++) if (PRAYERS[i].equals(s) || NAMES[i].equals(id.trim())) return i;
        if (s.equals("zuhr") || s.equals("duhr") || s.equals("jumuah") || s.equals("الجمعة")) return DHUHR;
        return -1;
    }

    /** a stored or typed mode: full, short or off; anything else: the default (short) */
    static String mode(String v) {
        if (FULL.equals(v) || SHORT.equals(v) || OFF.equals(v)) return v;
        return MODE_DEFAULT;
    }

    static boolean validMode(String v) { return FULL.equals(v) || SHORT.equals(v) || OFF.equals(v); }

    static String modeLabel(String v) {
        for (String[] m : MODES) if (m[0].equals(v)) return m[1];
        return MODES[0][1];
    }

    static int reminder(int v) {
        for (int r : REMINDERS) if (r == v) return v;
        return 0;
    }

    static int clampVolume(int v) {
        int r = Math.round(v / (float) VOLUME_STEP) * VOLUME_STEP;
        return Math.max(VOLUME_MIN, Math.min(VOLUME_MAX, r));
    }

    /** «10» … «100» or «40%»; -1 out of range or not a number */
    static int volume(String s) {
        if (s == null) return -1;
        try {
            int v = Integer.parseInt(s.trim().replace("%", ""));
            return v < VOLUME_MIN || v > VOLUME_MAX ? -1 : clampVolume(v);
        } catch (NumberFormatException e) { return -1; }
    }

    /** a prayer's name, Dhuhr on a Friday «الجمعة» (the page's prayerName) */
    static String name(int prayer, boolean friday) { return prayer == DHUHR && friday ? "الجمعة" : NAMES[prayer]; }

    /** «٤:٣٥ ص» as the page's fmt12, in Latin digits (Prefs.digits turns them) */
    static String time12(int minutes) {
        int h = (minutes / 60) % 24, m = minutes % 60, h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + ":" + (m < 10 ? "0" : "") + m + " " + (h < 12 ? "ص" : "م");
    }

    /** What the settings say, read once (AthanPrefs on Android, plain values in the tests). */
    static final class Settings {
        boolean on = true;
        final String[] modes = {MODE_DEFAULT, MODE_DEFAULT, MODE_DEFAULT, MODE_DEFAULT, MODE_DEFAULT};
        int reminder = REMINDER_DEFAULT;

        boolean any() {
            if (!on) return false;
            for (String m : modes) if (!OFF.equals(m)) return true;
            return false;
        }
    }

    /** the place: latitude, longitude, the city's time zone and the prayer method (Prefs), and the page's online times
     *  where they are known (online: null, or none for a day -> PrayerTimes, as the page offline) */
    static final class Place {
        final double lat, lng;
        final TimeZone tz;
        final int method;
        final Online online;
        Place(double lat, double lng, TimeZone tz, int method) { this(lat, lng, tz, method, null); }
        Place(double lat, double lng, TimeZone tz, int method, Online online) {
            this.lat = lat; this.lng = lng; this.tz = tz; this.method = method; this.online = online;
        }
    }

    /** the page's online times of a city's day (Aladhan, AladhanTimes): Fajr … Isha in minutes after midnight, or null */
    interface Online { int[] minutes(int[] ymd); }

    /** the page's hijriMonthNow(at) === 9 (the TV's time zone, Umm al-Qura): Umm al-Qura's Isha in Ramadan */
    interface Hijri { boolean ramadan(long at); }

    static final Hijri NEVER_RAMADAN = at -> false;

    // ---------------------------------------------------------------- the day's moments

    /** the city's date at this moment: {y, mo, d} */
    static int[] cityDate(TimeZone tz, long at) {
        GregorianCalendar c = new GregorianCalendar(tz, Locale.ROOT);
        c.setTimeInMillis(at);
        return new int[] {c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)};
    }

    /** y-mo-d plus `days`, as {y, mo, d} */
    static int[] addDays(int[] ymd, int days) {
        GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.clear();
        c.set(ymd[0], ymd[1] - 1, ymd[2]);
        c.add(Calendar.DAY_OF_MONTH, days);
        return new int[] {c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)};
    }

    /** the moment of a wall-clock time of the city: minute m (may be below 0 or past 1440) of day y-mo-d */
    static long wall(TimeZone tz, int[] ymd, int m) {
        GregorianCalendar c = new GregorianCalendar(tz, Locale.ROOT);
        c.clear();
        c.setLenient(true);
        int day = m >= 0 ? m / 1440 : -((-m + 1439) / 1440), rest = m - day * 1440;   // floor (Math.floorDiv: API 24)
        c.set(ymd[0], ymd[1] - 1, ymd[2] + day, rest / 60, rest % 60, 0);
        return c.getTimeInMillis();
    }

    /** a prayer the sun gives no time for (inside the polar circles) */
    static final int NONE = Integer.MIN_VALUE;

    /**
     * The page's times of the city's day y-mo-d, minutes after that day's midnight: its online ones (Aladhan, as the page
     * shows them) when known, else PrayerTimes.minutes() computed for the day's local noon (as the page's next days) for
     * Fajr … Isha, NONE where there is none. Its wall-clock times wrap
     * at midnight; here a Fajr after Dhuhr is the evening before (below 0), and an Isha before Maghrib after midnight
     * (1440 or more: far north in summer).
     */
    static int[] day(Place p, int[] ymd, Hijri hijri) {
        int[] out = new int[PRAYERS.length];
        int[] online = p.online == null ? null : p.online.minutes(ymd);
        if (online != null && online.length == PRAYERS.length) System.arraycopy(online, 0, out, 0, out.length);
        else {
            long noon = wall(p.tz, ymd, 12 * 60);
            int[] all = PrayerTimes.minutes(ymd[0], ymd[1], ymd[2], p.lat, p.lng, PrayerTimes.offsetHours(p.tz, noon), p.method,
                hijri.ramadan(noon));
            for (int i = 0; i < out.length; i++) out[i] = all[TIMES[i]] < 0 ? NONE : all[TIMES[i]];
        }
        if (out[FAJR] != NONE && out[DHUHR] != NONE && out[FAJR] > out[DHUHR]) out[FAJR] -= 1440;
        for (int i = DHUHR + 1; i < out.length; i++) {
            if (out[i] == NONE || out[i - 1] == NONE) continue;
            while (out[i] < out[i - 1]) out[i] += 1440;
        }
        return out;
    }

    /** the moments (epoch ms) of the day's five athans; Long.MIN_VALUE where the sun gives none */
    static long[] moments(Place p, int[] ymd, Hijri hijri) {
        int[] m = day(p, ymd, hijri);
        long[] out = new long[m.length];
        for (int i = 0; i < m.length; i++) out[i] = m[i] == NONE ? Long.MIN_VALUE : wall(p.tz, ymd, m[i]);
        return out;
    }

    // ---------------------------------------------------------------- the next thing to do

    static final String KIND_FULL = "full", KIND_SHORT = "short", KIND_REMINDER = "reminder";

    /** one alarm: a prayer's athan (full or short) or its reminder, due at `at`, for the athan at `athanAt` */
    static final class Event {
        final int prayer;
        final String kind;
        final long at, athanAt;
        Event(int prayer, String kind, long at, long athanAt) { this.prayer = prayer; this.kind = kind; this.at = at; this.athanAt = athanAt; }
        @Override public String toString() { return PRAYERS[prayer] + " " + kind + " @" + at; }
    }

    /** every event of the city's days around `now` (yesterday's Isha may still be ahead, after midnight) */
    static List<Event> events(Place p, Settings s, long now, Hijri hijri) {
        List<Event> out = new ArrayList<>();
        if (!s.any()) return out;
        int[] today = cityDate(p.tz, now);
        for (int k = -1; k <= 1; k++) {
            long[] at = moments(p, addDays(today, k), hijri);
            for (int i = 0; i < at.length; i++) {
                if (at[i] == Long.MIN_VALUE || OFF.equals(s.modes[i])) continue;
                if (s.reminder > 0) out.add(new Event(i, KIND_REMINDER, at[i] - s.reminder * 60000L, at[i]));
                out.add(new Event(i, SHORT.equals(s.modes[i]) ? KIND_SHORT : KIND_FULL, at[i], at[i]));
            }
        }
        return out;
    }

    /** the first event after `after` (its alarm), or null when the athan is off or nothing is on */
    static Event next(Place p, Settings s, long after, Hijri hijri) {
        Event best = null;
        for (Event e : events(p, s, after, hijri)) if (e.at > after && (best == null || e.at < best.at)) best = e;
        if (best == null && s.any()) {   // nothing in these three days (polar night): look a day further
            for (Event e : events(p, s, after + 86400000L, hijri)) if (e.at > after && (best == null || e.at < best.at)) best = e;
        }
        return best;
    }

    // ---------------------------------------------------------------- an alarm came: what now

    /** an athan more than this late is not called any more (the TV was asleep at its time) */
    static final long LATE_MS = 3 * 60 * 1000L;
    /** a reminder more than this late: its athan is close, it says nothing new */
    static final long REMINDER_LATE_MS = 60 * 1000L;

    static final int PLAY_FULL = 1, PLAY_SHORT = 2, BANNER = 3, SILENT_SCREEN_OFF = 4, SKIP_LATE = 5, SKIP_OFF = 6;
    static final String[] OUTCOMES = {"", "full athan", "short alert", "reminder banner", "silent (screen off)",
        "skipped (late)", "skipped (turned off)"};

    /**
     * What to do with an event whose alarm came at `now`: turned off since -> SKIP_OFF; too late -> SKIP_LATE; the TV off
     * or in standby (not interactive) -> SILENT_SCREEN_OFF, nothing is heard and the TV is never woken; else play.
     * test (saver.sh athan-test, the settings' preview): now, whatever the time and the settings, but never with the
     * screen off either.
     */
    static int decide(Event e, Settings s, long now, boolean interactive, boolean test) {
        if (!test) {
            if (!s.on || OFF.equals(s.modes[e.prayer]) || (KIND_REMINDER.equals(e.kind) && s.reminder == 0)) return SKIP_OFF;
            long late = now - e.at;
            if (late > (KIND_REMINDER.equals(e.kind) ? REMINDER_LATE_MS : LATE_MS)) return SKIP_LATE;
        }
        if (!interactive) return SILENT_SCREEN_OFF;
        if (KIND_REMINDER.equals(e.kind)) return BANNER;
        return KIND_SHORT.equals(e.kind) ? PLAY_SHORT : PLAY_FULL;
    }

    // ---------------------------------------------------------------- the short alert

    /** the short alert plays the voice's opening (the first «الله أكبر الله أكبر») for this long, fading out at the end */
    static final long SHORT_PLAY_MS = 9000, SHORT_FADE_MS = 3000;

    /** the short alert's gain at `ms` into it (1 … 0 over its last SHORT_FADE_MS) */
    static float shortFade(long ms) {
        long fadeFrom = SHORT_PLAY_MS - SHORT_FADE_MS;
        if (ms <= fadeFrom) return 1f;
        if (ms >= SHORT_PLAY_MS) return 0f;
        float x = (SHORT_PLAY_MS - ms) / (float) SHORT_FADE_MS;
        return x * x;   // a gentle curve: most of the fall at the end
    }
}
