package com.raad.sauditv.screensaver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * «الأذان»'s plain-Java logic, run by tests/java_test.py (javac and java, no Android):
 *  - PrayerTimes against the page's own computePrayers (tests/prayer_times_fixture.mjs runs it under node for many
 *    cities, methods and days: args[1], one case a line), and against a copy of a few of those answers kept here (so the
 *    port is checked even without node);
 *  - AthanPlan: each day's moments at the city's wall clock (Isha after midnight, a DST day), the next alarm (reminders,
 *    a prayer turned off, a short alert, the athan off) and what an alarm does when it comes (the TV off, late, off);
 *  - AladhanTimes: the page's own Aladhan request (the same fixture builds it with the page's code from the settings'
 *    values: args[2], one a line, tab-separated), its answer read as the page reads it, the cache's key; AthanPlan using
 *    those online times when kept, else the offline ones;
 *  - AthanVoices and assets/athan: every bundled recording there, credited (CREDITS.txt), within the size budget.
 *
 *   java AthanTest <repository root> [<prayer times file> <Aladhan URLs file>]
 */
public class AthanTest {
    static int failed = 0;

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
        if (!ok) failed++;
    }

    // the page's answers (node tests/prayer_times_fixture.mjs, 2026-10-25): name, lat, lon, zone, method, day, the
    // moment computed (ms), the zone's offset then (h), Ramadan, then Fajr Sunrise Dhuhr Asr Maghrib Isha
    static final String[] KEPT = {
        "riyadh|24.7136|46.6753|Asia/Riyadh|4|2026-10-25|1792918800000|3|false|04:37|05:56|11:37|14:53|17:18|18:48",
        "riyadh-ramadan|24.7136|46.6753|Asia/Riyadh|4|2027-02-20|1803114000000|3|true|05:05|06:24|12:07|15:24|17:50|19:50",
        "cairo|30.0626|31.2497|Africa/Cairo|5|2026-07-15|1784106000000|3|false|04:21|06:04|13:01|16:37|19:58|21:29",
        "london-mwl|51.5072|-0.1276|Europe/London|3|2026-06-21|1782039600000|1|false|02:31|04:43|13:02|17:25|21:22|23:27",
        "oslo-summer|59.9139|10.7522|Europe/Oslo|3|2026-06-21|1782036000000|2|false|02:21|03:54|13:19|18:00|22:44|00:12",
        "tromso-midnight-sun|69.6492|18.9553|Europe/Oslo|3|2026-06-21|1782036000000|2|false|NaN:NaN|NaN:NaN|12:46|17:58|NaN:NaN|NaN:NaN",
        // the page's offline formula gives Asr 15:40 here; Aladhan answers 15:41 (the athan regression below)
        "los-angeles|34.0522|-118.2437|America/Los_Angeles|2|2026-10-28|1793214000000|-7|false|05:59|07:09|12:37|15:40|18:04|19:14",
    };

    // the page's Aladhan URL (node tests/prayer_times_fixture.mjs, 2026-10-25) for the settings' latitude and longitude
    // strings, method, city zone and day: lat, lon, method, zone, day, URL
    static final String[] KEPT_URLS = {
        "24.6877\t46.7219\t4\tAsia/Riyadh\t2026-10-25\thttps://api.aladhan.com/v1/timings/25-10-2026?latitude=24.6877"
            + "&longitude=46.7219&method=4&timezonestring=Asia%2FRiyadh",
        "51.50720\t-0.1276\t3\tEurope/London\t2026-01-09\thttps://api.aladhan.com/v1/timings/09-01-2026?latitude=51.5072"
            + "&longitude=-0.1276&method=3&timezonestring=Europe%2FLondon",
        "40.712800\t-74.0060\t2\tAmerica/New_York\t2026-03-08\thttps://api.aladhan.com/v1/timings/08-03-2026?latitude=40.7128"
            + "&longitude=-74.006&method=2&timezonestring=America%2FNew_York",
        "25.0000\t55.30\t16\tEtc/GMT-4\t2026-06-01\thttps://api.aladhan.com/v1/timings/01-06-2026?latitude=25&longitude=55.3"
            + "&method=16&timezonestring=Etc%2FGMT-4",
        "abc\t\t13\tEurope/Istanbul\t2026-04-20\thttps://api.aladhan.com/v1/timings/20-04-2026?latitude=24.7136"
            + "&longitude=46.6753&method=13&timezonestring=Europe%2FIstanbul",
        "34.0522\t-118.2437\t2\tAmerica/Los_Angeles\t2026-10-28\thttps://api.aladhan.com/v1/timings/28-10-2026?latitude=34.0522"
            + "&longitude=-118.2437&method=2&timezonestring=America%2FLos_Angeles",
    };

    /** one settings case: AladhanTimes.url = the URL the page builds */
    static void urlCase(String line, String from) {
        String[] f = line.split("\t", -1);
        String[] ymd = f[4].split("-");
        String got = AladhanTimes.url(Integer.parseInt(ymd[0]), Integer.parseInt(ymd[1]), Integer.parseInt(ymd[2]), f[0], f[1],
            Integer.parseInt(f[2]), f[3]);
        check("Aladhan URL = the page's (lat «" + f[0] + "», lon «" + f[1] + "», " + f[3] + ", " + f[4] + ", " + from + ")",
            got.equals(f[5]), "page " + f[5] + "\n      java " + got);
    }

    static int min(String hhmm) {
        if (hhmm.contains("NaN")) return -1;
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    /** one case against the port: the six minutes, the zone's offset, and AthanPlan.day() for the same day */
    static void prayerCase(String line, String from) {
        String[] f = line.split("\\|");
        String name = f[0] + " " + f[5] + " method " + f[4] + " (" + from + ")";
        double lat = Double.parseDouble(f[1]), lon = Double.parseDouble(f[2]);
        TimeZone tz = TimeZone.getTimeZone(f[3]);
        int method = Integer.parseInt(f[4]);
        String[] ymdS = f[5].split("-");
        int[] ymd = {Integer.parseInt(ymdS[0]), Integer.parseInt(ymdS[1]), Integer.parseInt(ymdS[2])};
        long at = Long.parseLong(f[6]);
        double off = Double.parseDouble(f[7]);
        final boolean ramadan = Boolean.parseBoolean(f[8]);
        int[] want = new int[6];
        for (int i = 0; i < 6; i++) want[i] = min(f[9 + i]);
        double javaOff = PrayerTimes.offsetHours(tz, at);
        int[] got = PrayerTimes.minutes(ymd[0], ymd[1], ymd[2], lat, lon, off, method, ramadan);
        check("prayer times = the page's: " + name, Arrays.equals(got, want),
            "page " + Arrays.toString(want) + " java " + Arrays.toString(got));
        check("prayer times: the city's offset as the page's (" + f[3] + " " + f[5] + ")", javaOff == off, javaOff + " vs " + off);
        long noon = AthanPlan.wall(tz, ymd, 12 * 60);
        check("prayer times: the plan computes at the city's noon, the moment the page's next days use (" + name + ")", noon == at,
            noon + " vs " + at);
        int[] day = AthanPlan.day(new AthanPlan.Place(lat, lon, tz, method), ymd, x -> ramadan);
        boolean same = true;
        for (int i = 0; i < 5; i++) {
            int w = want[AthanPlan.TIMES[i]];
            if (w < 0) { same &= day[i] == AthanPlan.NONE; continue; }
            same &= Math.floorMod(day[i], 1440) == w;
        }
        check("plan: the day's athans are the page's (" + name + ")", same, Arrays.toString(day));
    }

    static AthanPlan.Settings settings(String... modes) {
        AthanPlan.Settings s = new AthanPlan.Settings();
        for (int i = 0; i < modes.length; i++) s.modes[i] = modes[i];
        return s;
    }

    /** the moment of a city's wall-clock time */
    static long at(TimeZone tz, int y, int mo, int d, int h, int mi) { return AthanPlan.wall(tz, new int[] {y, mo, d}, h * 60 + mi); }

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : ".");

        // ---- the port, against the page's own answers
        for (String line : KEPT) prayerCase(line, "kept");
        int live = 0;
        if (args.length > 1) {
            for (String line : Files.readAllLines(new File(args[1]).toPath(), StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) continue;
                prayerCase(line.trim(), "node");
                live++;
            }
        }
        System.out.println(args.length > 1 ? "(" + live + " cases from the page under node)" : "(no node: only the kept answers)");
        check("prayer times: an unknown method is MWL, as the page", Arrays.equals(
            PrayerTimes.minutes(2026, 10, 25, 24.7, 46.7, 3, 77, false), PrayerTimes.minutes(2026, 10, 25, 24.7, 46.7, 3, 3, false)), "");
        check("prayer times: Umm al-Qura's Isha 90 min after Maghrib, 120 in Ramadan", PrayerTimes.minutes(2026, 10, 25, 24.7, 46.7, 3, 4, false)[5]
            - PrayerTimes.minutes(2026, 10, 25, 24.7, 46.7, 3, 4, false)[4] == 90
            && PrayerTimes.minutes(2026, 10, 25, 24.7, 46.7, 3, 4, true)[5] - PrayerTimes.minutes(2026, 10, 25, 24.7, 46.7, 3, 4, true)[4] == 120, "");
        check("prayer times: the days since 1970 (Date.UTC)", PrayerTimes.epochDay(1970, 1, 1) == 0 && PrayerTimes.epochDay(2026, 10, 25) == 20751
            && PrayerTimes.epochDay(2000, 3, 1) == 11017 && PrayerTimes.epochDay(1969, 12, 31) == -1, PrayerTimes.epochDay(2026, 10, 25));

        // ---- the page's online request and answer (AladhanTimes)
        for (String line : KEPT_URLS) urlCase(line, "kept");
        int liveUrls = 0;
        if (args.length > 2) {
            for (String line : Files.readAllLines(new File(args[2]).toPath(), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                urlCase(line, "node");
                liveUrls++;
            }
            check("Aladhan URL: the page's request built under node for every case (" + liveUrls + ")", liveUrls >= 8, liveUrls);
        }
        check("Aladhan URL: numbers as JavaScript writes them", "46".equals(AladhanTimes.jsNumber(46.0))
            && "0".equals(AladhanTimes.jsNumber(-0.0)) && "0.30000000000000004".equals(AladhanTimes.jsNumber(0.1 + 0.2))
            && "-74.006".equals(AladhanTimes.jsNumber(-74.006)) && "1e+21".equals(AladhanTimes.jsNumber(1e21))
            && "1e-7".equals(AladhanTimes.jsNumber(1e-7)) && "0.000001".equals(AladhanTimes.jsNumber(1e-6)),
            AladhanTimes.jsNumber(0.1 + 0.2) + " " + AladhanTimes.jsNumber(1e21) + " " + AladhanTimes.jsNumber(1e-7));
        check("Aladhan URL: parseFloat as the page's (leading blanks, a sign, junk after; none -> the default)",
            AladhanTimes.parseFloat(" 26.39x", 0) == 26.39 && AladhanTimes.parseFloat("+50.1", 0) == 50.1
            && AladhanTimes.parseFloat("abc", 7) == 7 && AladhanTimes.parseFloat("", 7) == 7 && AladhanTimes.parseFloat(".5", 0) == 0.5, "");
        check("Aladhan URL: the zone as encodeURIComponent", "Etc%2FGMT%2B3".equals(AladhanTimes.encodeURIComponent("Etc/GMT+3"))
            && "America%2FPort-au-Prince".equals(AladhanTimes.encodeURIComponent("America/Port-au-Prince"))
            && "%C3%A9_.!~*'()".equals(AladhanTimes.encodeURIComponent("é_.!~*'()")), AladhanTimes.encodeURIComponent("Etc/GMT+3"));
        String answer = "{\"code\":200,\"status\":\"OK\",\"data\":{\"timings\":{\"Fajr\":\"04:37\",\"Sunrise\":\"05:56\",\"Dhuhr\":\"11:37\","
            + "\"Asr\":\"14:53\",\"Sunset\":\"17:18\",\"Maghrib\":\"17:18\",\"Isha\":\"18:48\",\"Imsak\":\"04:27\",\"Midnight\":\"23:17\"},"
            + "\"date\":{\"readable\":\"25 Oct 2026\"}}}";
        check("Aladhan answer: data.timings' Fajr, Dhuhr, Asr, Maghrib, Isha (HH:MM) as the page reads them",
            Arrays.equals(AladhanTimes.parse(answer), new int[] {277, 697, 893, 1038, 1128}), Arrays.toString(AladhanTimes.parse(answer)));
        check("Aladhan answer: one missing, an error or nothing -> none (the offline times, as the page)",
            AladhanTimes.parse(answer.replace("\"Isha\":\"18:48\",", "")) == null
            && AladhanTimes.parse("{\"code\":400,\"status\":\"BAD_REQUEST\",\"data\":\"Invalid date\"}") == null
            && AladhanTimes.parse(null) == null && AladhanTimes.parse("<html>") == null, "");
        String k1 = AladhanTimes.key(2026, 10, 25, "24.6877", "46.7219", 4, "Asia/Riyadh");
        check("Aladhan cache: one key per day, city, method and zone", k1.startsWith("2026-10-25|")
            && k1.equals(AladhanTimes.key(2026, 10, 25, "24.68770", "46.7219", 4, "Asia/Riyadh"))
            && !k1.equals(AladhanTimes.key(2026, 10, 26, "24.6877", "46.7219", 4, "Asia/Riyadh"))
            && !k1.equals(AladhanTimes.key(2026, 10, 25, "24.6877", "46.7219", 2, "Asia/Riyadh"))
            && !k1.equals(AladhanTimes.key(2026, 10, 25, "21.4225", "39.8262", 4, "Asia/Riyadh"))
            && !k1.equals(AladhanTimes.key(2026, 10, 25, "24.6877", "46.7219", 4, "Asia/Kuwait")), k1);
        check("Aladhan cache: kept and read back; anything else is none", Arrays.equals(AladhanTimes.load(AladhanTimes.store(
            new int[] {277, 697, 893, 1038, 1128})), new int[] {277, 697, 893, 1038, 1128}) && AladhanTimes.load("1,2,3") == null
            && AladhanTimes.load("x,1,2,3,4") == null && AladhanTimes.load(null) == null && AladhanTimes.load("1,2,3,4,1440") == null, "");

        // ---- the day's moments at the city's wall clock
        TimeZone riyadh = TimeZone.getTimeZone("Asia/Riyadh"), oslo = TimeZone.getTimeZone("Europe/Oslo"),
            ny = TimeZone.getTimeZone("America/New_York");
        // the page's online times when kept (a day the online Asr is a minute later than the formula's), else the formula's
        final int[] online = {277, 697, 894, 1038, 1128};
        AthanPlan.Place ryOnline = new AthanPlan.Place(24.7136, 46.6753, riyadh, 4,
            ymd -> ymd[0] == 2026 && ymd[1] == 10 && ymd[2] == 25 ? online : null);
        AthanPlan.Event asrOnline = AthanPlan.next(ryOnline, settings("full", "off"), at(riyadh, 2026, 10, 25, 12, 0), AthanPlan.NEVER_RAMADAN);
        check("online: a kept answer moves the alarm to the page's minute (Asr 14:54, not the formula's 14:53)", asrOnline != null
            && asrOnline.prayer == 2 && asrOnline.at == at(riyadh, 2026, 10, 25, 14, 54), asrOnline);
        check("online: a day without an answer -> the formula (26 Oct as PrayerTimes)", Arrays.equals(
            AthanPlan.day(ryOnline, new int[] {2026, 10, 26}, AthanPlan.NEVER_RAMADAN),
            AthanPlan.day(new AthanPlan.Place(24.7136, 46.6753, riyadh, 4), new int[] {2026, 10, 26}, AthanPlan.NEVER_RAMADAN)), "");

        // ---- regression, a household TV on 9047: the page's card said Asr a minute later than the athan (the card shows
        // Aladhan's answer, read as it is: no rounding, no adjustment; the athan used the offline formula, no answer being
        // kept on the TV). With the page's request answered and kept under the key the plan reads, the athan follows the
        // card. Fixture: Aladhan's answer for Los Angeles, 28 Oct 2026, method 2 (Asr 15:41; the formula says 15:40).
        TimeZone la = TimeZone.getTimeZone("America/Los_Angeles");
        String uLat = "34.0522", uLon = "-118.2437";
        String uAnswer = "{\"code\":200,\"status\":\"OK\",\"data\":{\"timings\":{\"Fajr\":\"05:59\",\"Sunrise\":\"07:09\",\"Dhuhr\":\"12:37\","
            + "\"Asr\":\"15:41\",\"Sunset\":\"18:04\",\"Maghrib\":\"18:04\",\"Isha\":\"19:14\",\"Imsak\":\"05:49\",\"Midnight\":\"00:36\","
            + "\"Firstthird\":\"22:26\",\"Lastthird\":\"02:47\"},\"meta\":{\"latitude\":34.0522,\"longitude\":-118.2437,"
            + "\"timezone\":\"America/Los_Angeles\",\"method\":{\"id\":2}}}}";
        final java.util.Map<String, String> kept = new java.util.HashMap<>();   // the state file, as the job writes it
        kept.put(AladhanTimes.key(2026, 10, 28, uLat, uLon, 2, "America/Los_Angeles"), AladhanTimes.store(AladhanTimes.parse(uAnswer)));
        AthanPlan.Place laKept = new AthanPlan.Place(AladhanTimes.parseFloat(uLat, 0), AladhanTimes.parseFloat(uLon, 0), la, 2,
            ymd -> AladhanTimes.load(kept.get(AladhanTimes.key(ymd[0], ymd[1], ymd[2], uLat, uLon, 2, "America/Los_Angeles"))));
        AthanPlan.Event uAsr = AthanPlan.next(laKept, settings(), at(la, 2026, 10, 28, 12, 40), AthanPlan.NEVER_RAMADAN);
        check("regression (Los Angeles 28 Oct 2026): the page's answer kept -> Asr at 15:41 as the page's card",
            uAsr != null && uAsr.prayer == 2 && uAsr.at == at(la, 2026, 10, 28, 15, 41), uAsr);
        AthanPlan.Event uFormula = AthanPlan.next(new AthanPlan.Place(34.0522, -118.2437, la, 2), settings(), at(la, 2026, 10, 28, 12, 40),
            AthanPlan.NEVER_RAMADAN);
        check("regression: without it, the page's own offline formula -> 15:40 (what the TV called; the page offline too)",
            uFormula != null && uFormula.prayer == 2 && uFormula.at == at(la, 2026, 10, 28, 15, 40), uFormula);
        check("regression: the job's request is the page's own (same URL, so the same answer)",
            AladhanTimes.url(2026, 10, 28, uLat, uLon, 2, "America/Los_Angeles").equals("https://api.aladhan.com/v1/timings/28-10-2026"
                + "?latitude=34.0522&longitude=-118.2437&method=2&timezonestring=America%2FLos_Angeles"), "");
        check("regression: the other prayers of that answer are the page's too (05:59 12:37 15:41 18:04 19:14)",
            Arrays.equals(AthanPlan.day(laKept, new int[] {2026, 10, 28}, AthanPlan.NEVER_RAMADAN), new int[] {359, 757, 941, 1084, 1154}),
            Arrays.toString(AthanPlan.day(laKept, new int[] {2026, 10, 28}, AthanPlan.NEVER_RAMADAN)));
        AthanPlan.Place ry = new AthanPlan.Place(24.7136, 46.6753, riyadh, 4);
        long[] m = AthanPlan.moments(ry, new int[] {2026, 10, 25}, AthanPlan.NEVER_RAMADAN);
        check("plan: Riyadh 25 Oct 2026, Dhuhr at 11:37 Riyadh time (08:37 UTC)", m[1] == at(riyadh, 2026, 10, 25, 11, 37)
            && m[1] == 1792917420000L, m[1]);
        AthanPlan.Place os = new AthanPlan.Place(59.9139, 10.7522, oslo, 3);
        long[] o = AthanPlan.moments(os, new int[] {2026, 6, 21}, AthanPlan.NEVER_RAMADAN);
        check("plan: Oslo in June, Isha «00:12» is after midnight: 22 June 00:12, after Maghrib", o[4] == at(oslo, 2026, 6, 22, 0, 12)
            && o[4] > o[3], o[4]);
        AthanPlan.Place nyc = new AthanPlan.Place(40.7128, -74.006, ny, 2);
        long[] n = AthanPlan.moments(nyc, new int[] {2026, 3, 8}, AthanPlan.NEVER_RAMADAN);
        check("plan: New York on the day its clocks go forward: Fajr at 06:04 on the wall clock (10:04 UTC)",
            n[0] == at(ny, 2026, 3, 8, 6, 4) && n[0] == 1772964240000L, n[0]);
        AthanPlan.Place tr = new AthanPlan.Place(69.6492, 18.9553, oslo, 3);
        long[] t = AthanPlan.moments(tr, new int[] {2026, 6, 21}, AthanPlan.NEVER_RAMADAN);
        check("plan: midnight sun (Tromsø): no Fajr, Maghrib or Isha, Dhuhr and Asr only", t[0] == Long.MIN_VALUE && t[3] == Long.MIN_VALUE
            && t[4] == Long.MIN_VALUE && t[1] > 0 && t[2] > t[1], Arrays.toString(t));

        // ---- the next alarm
        long ten = at(riyadh, 2026, 10, 25, 10, 0);
        AthanPlan.Event e = AthanPlan.next(ry, settings(), ten, AthanPlan.NEVER_RAMADAN);
        check("next: 10:00 in Riyadh -> Dhuhr's full athan at 11:37", e != null && e.prayer == 1 && "full".equals(e.kind)
            && e.at == at(riyadh, 2026, 10, 25, 11, 37), e);
        AthanPlan.Settings rem = settings();
        rem.reminder = 10;
        e = AthanPlan.next(ry, rem, ten, AthanPlan.NEVER_RAMADAN);
        check("next: with «تذكير قبل الأذان» 10 min -> Dhuhr's reminder at 11:27, for the athan at 11:37", e != null && e.prayer == 1
            && "reminder".equals(e.kind) && e.at == at(riyadh, 2026, 10, 25, 11, 27) && e.athanAt == at(riyadh, 2026, 10, 25, 11, 37), e);
        e = AthanPlan.next(ry, rem, at(riyadh, 2026, 10, 25, 11, 27), AthanPlan.NEVER_RAMADAN);
        check("next: after the reminder, its athan", e != null && e.prayer == 1 && "full".equals(e.kind), e);
        e = AthanPlan.next(ry, settings("full", "off", "short"), ten, AthanPlan.NEVER_RAMADAN);
        check("next: Dhuhr off -> Asr, a short alert, at 14:53", e != null && e.prayer == 2 && "short".equals(e.kind)
            && e.at == at(riyadh, 2026, 10, 25, 14, 53), e);
        AthanPlan.Settings off = settings();
        off.on = false;
        check("next: the athan off -> no alarm at all", AthanPlan.next(ry, off, ten, AthanPlan.NEVER_RAMADAN) == null, "");
        check("next: every prayer off -> no alarm", AthanPlan.next(ry, settings("off", "off", "off", "off", "off"), ten,
            AthanPlan.NEVER_RAMADAN) == null, "");
        e = AthanPlan.next(ry, settings(), at(riyadh, 2026, 10, 25, 22, 0), AthanPlan.NEVER_RAMADAN);
        long fajr26 = AthanPlan.moments(ry, new int[] {2026, 10, 26}, AthanPlan.NEVER_RAMADAN)[0];
        check("next: after Isha -> tomorrow's Fajr", e != null && e.prayer == 0 && e.at == fajr26, e);
        e = AthanPlan.next(ry, settings(), at(riyadh, 2026, 10, 25, 11, 37), AthanPlan.NEVER_RAMADAN);
        check("next: at the athan's own minute (its alarm just fired) -> the one after it (Asr)", e != null && e.prayer == 2, e);
        e = AthanPlan.next(os, settings(), at(oslo, 2026, 6, 22, 0, 5), AthanPlan.NEVER_RAMADAN);
        check("next: Oslo, 00:05 on 22 June -> the 21st's Isha at 00:12 (yesterday's, still ahead)", e != null && e.prayer == 4
            && e.at == at(oslo, 2026, 6, 22, 0, 12), e);
        e = AthanPlan.next(tr, settings(), at(oslo, 2026, 6, 21, 20, 0), AthanPlan.NEVER_RAMADAN);
        check("next: midnight sun -> the next day's Dhuhr", e != null && e.prayer == 1, e);
        AthanPlan.Place ryRamadan = new AthanPlan.Place(24.7136, 46.6753, riyadh, 4);
        long maghrib = AthanPlan.moments(ryRamadan, new int[] {2027, 2, 20}, x -> true)[3];
        e = AthanPlan.next(ryRamadan, settings(), maghrib, x -> true);
        check("next: Umm al-Qura in Ramadan: Isha two hours after Maghrib", e != null && e.prayer == 4 && e.at - maghrib == 120 * 60000L, e);

        // ---- an alarm comes: what it does
        AthanPlan.Settings on = settings("full", "short", "full", "full", "full");
        on.reminder = 5;
        AthanPlan.Event dhuhr = new AthanPlan.Event(0, "full", 1000000L, 1000000L);
        check("alarm: on time, the TV on -> the full athan", AthanPlan.decide(dhuhr, on, 1000500L, true, false) == AthanPlan.PLAY_FULL, "");
        check("alarm: the TV off or in standby (not interactive) -> silent, never woken",
            AthanPlan.decide(dhuhr, on, 1000500L, false, false) == AthanPlan.SILENT_SCREEN_OFF, "");
        check("alarm: 2 min late -> still called", AthanPlan.decide(dhuhr, on, 1000000L + 120000L, true, false) == AthanPlan.PLAY_FULL, "");
        check("alarm: 4 min late (the TV was asleep at its time) -> skipped",
            AthanPlan.decide(dhuhr, on, 1000000L + 240000L, true, false) == AthanPlan.SKIP_LATE, "");
        check("alarm: a short alert -> the short one", AthanPlan.decide(new AthanPlan.Event(1, "short", 0, 0), on, 0, true, false)
            == AthanPlan.PLAY_SHORT, "");
        check("alarm: a reminder -> its banner; 2 min late -> skipped",
            AthanPlan.decide(new AthanPlan.Event(2, "reminder", 0, 300000L), on, 1000, true, false) == AthanPlan.BANNER
            && AthanPlan.decide(new AthanPlan.Event(2, "reminder", 0, 300000L), on, 120000L, true, false) == AthanPlan.SKIP_LATE, "");
        AthanPlan.Settings noRem = settings();
        check("alarm: a reminder whose setting was turned off since -> skipped",
            AthanPlan.decide(new AthanPlan.Event(2, "reminder", 0, 300000L), noRem, 0, true, false) == AthanPlan.SKIP_OFF, "");
        check("alarm: a prayer turned off since, or the athan off -> skipped",
            AthanPlan.decide(dhuhr, settings("off"), 1000000L, true, false) == AthanPlan.SKIP_OFF
            && AthanPlan.decide(dhuhr, off, 1000000L, true, false) == AthanPlan.SKIP_OFF, "");
        check("alarm: a test (saver.sh athan-test) plays whatever the time and the settings, but not with the TV off",
            AthanPlan.decide(dhuhr, off, 99999999L, true, true) == AthanPlan.PLAY_FULL
            && AthanPlan.decide(dhuhr, off, 99999999L, false, true) == AthanPlan.SILENT_SCREEN_OFF, "");

        // ---- values
        check("values: prayers by id or Arabic name; «الجمعة» is Dhuhr", AthanPlan.prayer("fajr") == 0 && AthanPlan.prayer("ISHA") == 4
            && AthanPlan.prayer("العصر") == 2 && AthanPlan.prayer("الجمعة") == 1 && AthanPlan.prayer("x") == -1, "");
        check("values: Dhuhr on a Friday is «الجمعة»", "الجمعة".equals(AthanPlan.name(1, true)) && "الظهر".equals(AthanPlan.name(1, false))
            && "الفجر".equals(AthanPlan.name(0, true)), "");
        check("values: the time as the page's fmt12", "12:00 ص".equals(AthanPlan.time12(0)) && "1:05 م".equals(AthanPlan.time12(13 * 60 + 5))
            && "11:37 ص".equals(AthanPlan.time12(11 * 60 + 37)) && "12:30 م".equals(AthanPlan.time12(12 * 60 + 30)), AthanPlan.time12(0));
        check("values: modes full|short|off; unknown -> full (the default)", "short".equals(AthanPlan.mode("short"))
            && "full".equals(AthanPlan.mode(null)) && "full".equals(AthanPlan.mode("loud")) && !AthanPlan.validMode("loud"), "");
        check("values: athan volume 10 … 100", AthanPlan.volume("40") == 40 && AthanPlan.volume("100%") == 100 && AthanPlan.volume("5") == -1
            && AthanPlan.volume("x") == -1 && AthanPlan.clampVolume(250) == 100, "");
        check("values: reminders 0 5 10 15 20 30, anything else off", AthanPlan.reminder(10) == 10 && AthanPlan.reminder(7) == 0, "");
        check("values: every prayer on, the full athan, no reminder, by default", new AthanPlan.Settings().any()
            && Arrays.equals(new AthanPlan.Settings().modes, new String[] {"full", "full", "full", "full", "full"})
            && new AthanPlan.Settings().reminder == 0, "");
        boolean down = true;
        for (long ms = 0; ms < AthanPlan.SHORT_PLAY_MS; ms += 250) down &= AthanPlan.shortFade(ms + 250) <= AthanPlan.shortFade(ms);
        check("short alert: 5 to 10 s of the opening, fading out at the end", AthanPlan.SHORT_PLAY_MS >= 5000 && AthanPlan.SHORT_PLAY_MS <= 10000
            && AthanPlan.shortFade(0) == 1f && AthanPlan.shortFade(AthanPlan.SHORT_PLAY_MS) == 0f && down, "");

        // ---- the voices and the bundled recordings
        check("voices: Malmö (Fajr, unconfirmed) only for Fajr, never a default", "wikimedia".equals(AthanVoices.of("malmo", false))
            && "malmo".equals(AthanVoices.of("malmo", true)) && !AthanVoices.find(AthanVoices.FAJR_DEFAULT).fajrOnly
            && !AthanVoices.find(AthanVoices.DEFAULT).fajrOnly && AthanVoices.offered(false).size() == AthanVoices.BUNDLED.length - 1, "");
        check("voices: custom and unknown choices", "custom".equals(AthanVoices.of("custom", false)) && "wikimedia".equals(AthanVoices.of(null, true))
            && "صوت من عندي".equals(AthanVoices.label("custom", true)), "");
        String credits = new String(Files.readAllBytes(new File(root, "assets/athan/CREDITS.txt").toPath()), StandardCharsets.UTF_8);
        long total = 0;
        List<String> bad = new ArrayList<>();
        for (AthanVoices.Voice v : AthanVoices.BUNDLED) {
            File f = new File(root, "assets/" + v.file);
            total += f.length();
            String name = f.getName();
            int at = credits.indexOf(name);
            String entry = at < 0 ? "" : credits.substring(at, Math.min(credits.length(), at + 700));
            if (!f.isFile() || !v.file.endsWith(".ogg")) bad.add(v.file + " missing");
            if (at < 0 || !entry.contains(v.source) || !entry.contains(v.licence.replace(" 1.0", "")) || !entry.contains(v.author))
                bad.add(name + " not credited as " + v.licence + " " + v.source);
            if (!v.licence.startsWith("CC0") && !v.licence.equals("CC BY 3.0")) bad.add(name + ": licence " + v.licence);
        }
        check("recordings: each bundled voice is in assets/athan and credited (author, licence, source) in CREDITS.txt; CC0 or CC BY only",
            bad.isEmpty(), bad);
        check("recordings: 6 MB at most in all (" + total / 1024 + " KB)", total > 0 && total <= 6L * 1024 * 1024, total);

        System.out.println(failed == 0 ? "\nall athan tests passed" : "\n" + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
