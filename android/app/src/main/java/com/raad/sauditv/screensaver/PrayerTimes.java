package com.raad.sauditv.screensaver;

/**
 * The page's offline prayer times (src/template.html computePrayers and PRAYER_METHODS), line for line in plain Java,
 * so the athan (AthanScheduler) calls at the minute the page shows. StrictMath (fdlibm, as V8's own sin, cos, atan2…)
 * keeps the arithmetic the same to the last bit. tests/java_test.py runs the page's function under node for several
 * cities, methods and days and AthanTest compares every minute; the page's methods table must stay this one.
 *
 * Plain Java (no android.*): tests/java_test.py compiles it without Android.
 */
final class PrayerTimes {
    private PrayerTimes() {}

    static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;
    /** the page's keys, in the order of minutes() */
    static final String[] KEYS = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};

    /** a method of the page's PRAYER_METHODS: the sun's angle at Fajr and Isha, or Isha minutes after Maghrib */
    static final class Method {
        final double fajr, isha;
        final int ishaMin, ramadanMin;
        Method(double fajr, double isha, int ishaMin, int ramadanMin) {
            this.fajr = fajr; this.isha = isha; this.ishaMin = ishaMin; this.ramadanMin = ramadanMin;
        }
    }

    /** Aladhan's number -> the page's PRAYER_METHODS entry; an unknown number: MWL (3), as the page */
    static Method method(int n) {
        switch (n) {
            case 1: return new Method(18, 18, 0, 0);          // Karachi
            case 2: return new Method(15, 15, 0, 0);          // ISNA
            case 4: return new Method(18.5, 0, 90, 120);      // Umm al-Qura: Isha 90 min after Maghrib, 120 in Ramadan
            case 5: return new Method(19.5, 17.5, 0, 0);      // Egyptian General Authority of Survey
            case 9: return new Method(18, 17.5, 0, 0);        // Kuwait
            case 10: return new Method(18, 0, 90, 0);         // Qatar
            case 13: return new Method(18, 17, 0, 0);         // Diyanet
            case 16: return new Method(18.2, 18.2, 0, 0);     // Dubai
            case 3:
            default: return new Method(18, 17, 0, 0);         // Muslim World League
        }
    }

    /** the methods the page knows (its PRAYER_METHODS keys) */
    static final int[] METHODS = {1, 2, 3, 4, 5, 9, 10, 13, 16};

    /**
     * The minutes after midnight (0 … 1439, the page's "HH:MM") of Fajr, Sunrise, Dhuhr, Asr, Maghrib and Isha on the
     * city's day y-mo-d; -1 where the sun gives none (the page's "NaN:NaN", inside the polar circles).
     * tzHours: the city's UTC offset at the moment computed (offsetHours), as the page's tz; ramadan: the page's
     * hijriMonthNow(at) === 9 (Umm al-Qura's Isha 120 min after Maghrib).
     */
    static int[] minutes(int y, int mo, int d, double lat, double lng, double tzHours, int methodN, boolean ramadan) {
        double[] h = hours(y, mo, d, lat, lng, tzHours, methodN, ramadan);
        int[] out = new int[h.length];
        for (int i = 0; i < h.length; i++) out[i] = hm(h[i]);
        return out;
    }

    /** the page's offset: Math.round((Date.UTC(parts) - at) / 60000) / 60, the zone's offset at that moment */
    static double offsetHours(java.util.TimeZone tz, long at) {
        return Math.round(tz.getOffset(at) / 60000.0) / 60.0;
    }

    /** "HH:MM", or "--:--" for -1 */
    static String hhmm(int m) {
        if (m < 0) return "--:--";
        return (m / 60 < 10 ? "0" : "") + m / 60 + ":" + (m % 60 < 10 ? "0" : "") + m % 60;
    }

    // ---------------------------------------------------------------- computePrayers, as the page has it

    private static double rad(double d) { return d * Math.PI / 180; }
    private static double deg(double r) { return r * 180 / Math.PI; }
    private static double fix(double a, double b) { a = a - b * Math.floor(a / b); return a < 0 ? a + b : a; }
    private static boolean finite(double x) { return !Double.isNaN(x) && !Double.isInfinite(x); }

    /** hm(): Math.round(fix(h, 24) * 60) % 1440; NaN -> -1 */
    private static int hm(double h) {
        if (!finite(h)) return -1;
        double r = (double) Math.round(fix(h, 24) * 60);   // JS Math.round: half up, as Java's
        return (int) (r % 1440);
    }

    /** Date.UTC(y, mo - 1, d) / 86400000: the days since 1970-01-01 (proleptic Gregorian) */
    static long epochDay(int y, int mo, int d) {
        long yy = mo <= 2 ? y - 1 : y;
        long era = (yy >= 0 ? yy : yy - 399) / 400;
        long yoe = yy - era * 400;
        long doy = (153L * (mo + (mo > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    private static double[] hours(int y, int mo, int d, final double lat, final double lng, double tz, int methodN, boolean ramadan) {
        Method m = method(methodN);
        final double jd0 = epochDay(y, mo, d) + 2440587.5 - lng / (15 * 24);
        Sun sun = new Sun(jd0, lat);
        double dhuhr = adj(sun.noon(12), tz, lng), asr = adj(sun.asrTime(13), tz, lng);
        double sunrise = adj(sun.angleTime(.833, 6, true), tz, lng);
        double maghrib = adj(sun.angleTime(.833, 18, false), tz, lng);
        double night = fix(sunrise - maghrib, 24);   // sunset to sunrise, hours
        double fajr = adj(sun.angleTime(m.fajr, 5, true), tz, lng);
        if (!finite(fajr) || fix(sunrise - fajr, 24) > m.fajr / 60 * night) fajr = sunrise - m.fajr / 60 * night;
        double isha;
        if (m.ishaMin > 0) isha = maghrib + (m.ramadanMin > 0 && ramadan ? m.ramadanMin : m.ishaMin) / 60.0;
        else {
            isha = adj(sun.angleTime(m.isha, 18, false), tz, lng);
            if (!finite(isha) || fix(isha - maghrib, 24) > m.isha / 60 * night) isha = maghrib + m.isha / 60 * night;
        }
        return new double[] {fajr, sunrise, dhuhr, asr, maghrib, isha};
    }

    private static double adj(double h, double tz, double lng) { return h + tz - lng / 15; }

    /** the page's sun(), noon(), angleTime() and asrTime() for one day */
    private static final class Sun {
        final double jd0, lat;
        double decl, eqt;
        Sun(double jd0, double lat) { this.jd0 = jd0; this.lat = lat; }

        void at(double t) {
            double D = jd0 + t - 2451545;
            double g = fix(357.529 + .98560028 * D, 360), q = fix(280.459 + .98564736 * D, 360);
            double L = fix(q + 1.915 * StrictMath.sin(rad(g)) + .020 * StrictMath.sin(rad(2 * g)), 360);
            double e = 23.439 - .00000036 * D;
            double RA = fix(deg(StrictMath.atan2(StrictMath.cos(rad(e)) * StrictMath.sin(rad(L)), StrictMath.cos(rad(L)))) / 15, 24);
            decl = deg(StrictMath.asin(StrictMath.sin(rad(e)) * StrictMath.sin(rad(L))));
            eqt = q / 15 - RA;
        }

        double noon(double t) { at(t / 24); return fix(12 - eqt, 24); }

        double angleTime(double angle, double t, boolean ccw) {
            at(t / 24);
            double dd = decl, n = noon(t);
            double T = deg(StrictMath.acos((-StrictMath.sin(rad(angle)) - StrictMath.sin(rad(dd)) * StrictMath.sin(rad(lat)))
                / (StrictMath.cos(rad(dd)) * StrictMath.cos(rad(lat))))) / 15;
            return n + (ccw ? -T : T);
        }

        double asrTime(double t) {
            at(t / 24);
            double a = -deg(StrictMath.atan(1 / (1 + StrictMath.tan(rad(Math.abs(lat - decl))))));
            return angleTime(a, t, false);
        }
    }
}
