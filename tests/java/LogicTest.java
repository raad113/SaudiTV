package com.raad.sauditv.screensaver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The settings app's plain-Java logic, run by tests/java_test.py (javac and java, no Android): a change of country
 * (CountryChange: news, league, clubs; never on a reload or a re-pick in the same country) and the Quran radio's
 * stations and volume (RadioStations).
 */
public class LogicTest {
    static int failed = 0;

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
        if (!ok) failed++;
    }

    static List<String[]> rows(String... idName) {
        List<String[]> out = new ArrayList<>();
        for (String s : idName) { String[] p = s.split(":", 2); out.add(new String[] {p[0], p[1]}); }
        return out;
    }

    static String ids(List<String[]> l) {
        StringBuilder b = new StringBuilder();
        for (String[] r : l) b.append(b.length() > 0 ? "," : "").append(r[0]);
        return b.toString();
    }

    public static void main(String[] args) {
        // ---- a change of country, only an actual one
        check("country: re-picking a city of the same country is no change (SA, SA)", !CountryChange.isChange("SA", "SA"), "");
        check("country: older builds' settings (no country) are Saudi Arabia: a Saudi household re-picking Riyadh keeps its clubs",
            !CountryChange.isChange("", "SA") && CountryChange.plan("", "SA", "SA", true) == null, "");
        check("country: case and blanks do not count (sa, SA)", !CountryChange.isChange(" sa", "SA"), "");
        check("country: no new country (a reload, a city without one) is no change", !CountryChange.isChange("EG", null)
            && CountryChange.plan("SA", "SA", "SA", true) == null, "");
        check("country: SA to EG is a change", CountryChange.isChange("SA", "EG") && CountryChange.isChange(null, "EG"), "");

        CountryChange.Plan eg = CountryChange.plan("SA", "EG", "SA", true);
        check("country SA -> EG: Egypt's news (its own list), the Egyptian league", eg != null && "EG".equals(eg.news)
            && eg.newsMissing == null && eg.league != null && "tsdb.4829".equals(eg.league.id) && "الدوري المصري".equals(eg.league.title),
            eg == null ? null : eg.news + " " + eg.newsMissing + " " + (eg.league == null ? null : eg.league.id));
        CountryChange.Plan sa = CountryChange.plan("EG", "SA", "EG", true);
        check("country EG -> SA: the Saudi news and Roshn (ksa.1)", sa != null && "SA".equals(sa.news) && sa.newsMissing == null
            && sa.league != null && "ksa.1".equals(sa.league.id), sa == null ? null : sa.news);
        CountryChange.Plan fr = CountryChange.plan("SA", "FR", "SA", false);
        check("country SA -> FR (no news list of its own): the Saudi news stays, the row says France has none; Ligue 1",
            fr != null && "SA".equals(fr.news) && "FR".equals(fr.newsMissing) && fr.league != null && "fra.1".equals(fr.league.id),
            fr == null ? null : fr.news + " " + fr.newsMissing);
        CountryChange.Plan fr2 = CountryChange.plan("EG", "FR", "EG", false);
        check("country EG -> FR: the Egyptian news stays (the one shown before)", fr2 != null && "EG".equals(fr2.news), fr2 == null ? null : fr2.news);
        CountryChange.Plan gb = CountryChange.plan("SA", "GB", "SA", false);
        check("country -> GB: England's Premier League (not Scotland's)", gb != null && gb.league != null && "eng.1".equals(gb.league.id),
            gb == null || gb.league == null ? null : gb.league.id);
        CountryChange.Plan is = CountryChange.plan("SA", "IS", "SA", false);
        check("country -> IS (no league data): the table and the clubs stay", is != null && is.league == null, "");

        // ---- the clubs: the live table's top 3, else the known list, else none (they stay)
        List<String[]> live = CountryChange.clubs("tsdb.4829", rows("138995@tsdb.4829:Al Ahly", "138997@tsdb.4829:Zamalek",
            "139838@tsdb.4829:Pyramids", "1@tsdb.4829:Fourth"));
        check("clubs: the table's top 3 in its order", "138995@tsdb.4829,138997@tsdb.4829,139838@tsdb.4829".equals(ids(live)), ids(live));
        List<String[]> fallback = CountryChange.clubs("tsdb.4829", rows("138995@tsdb.4829:Al Ahly"));
        check("clubs: a table that could not be read whole: the known list (Egypt: 3 clubs)", fallback.size() == 3
            && ids(fallback).contains("138995@tsdb.4829"), ids(fallback));
        check("clubs: no table at all: the known list", CountryChange.clubs("ksa.1", null).size() == 3, "");
        check("clubs: bad ids and duplicates are skipped", ids(CountryChange.clubs("ksa.1", rows("E929@ksa.1:Al Hilal", "bad:X",
            "E929@ksa.1:Al Hilal", "E817@ksa.1:Al Nassr", "E22022@ksa.1:Al Qadsiah"))).equals("E929@ksa.1,E817@ksa.1,E22022@ksa.1"), "");
        check("clubs: a league without a known list and no table: none (the clubs stay)", CountryChange.clubs("ecu.1", null).isEmpty(), "");

        // ---- the known list: every entry a top league of Leagues, three valid team ids of that league each
        List<String> bad = new ArrayList<>();
        int known = 0;
        for (String[] k : CountryChange.KNOWN) {
            Leagues.Group g = Leagues.groupOf(k[0]);
            if (g == null || !g.leagues[0].id.equals(k[0]) || k.length != 4) { bad.add(k[0]); continue; }
            for (int i = 1; i < k.length; i++) {
                String id = k[i].split(":", 2)[0];
                if (!Leagues.validTeamId(id) || !id.endsWith("@" + k[0]) || k[i].split(":", 2).length != 2) bad.add(k[i]);
            }
            known++;
        }
        check("known clubs: top leagues only, three valid ids of that league each", bad.isEmpty(), bad);
        int groups = 0;
        for (Leagues.Group g : Leagues.GROUPS) groups++;
        check("known clubs: nearly every country's top league (" + known + " of " + groups + ")", known >= groups - 2, known);

        // ---- the radio's stations
        List<RadioStations.Station> fb = RadioStations.merge(null);
        check("radio: the Saudi and the Cairo Quran radios first, each with a second address", fb.size() > 10
            && "saudi".equals(fb.get(0).id) && "cairo".equals(fb.get(1).id) && fb.get(0).urls.length == 2 && fb.get(1).urls.length == 2
            && fb.get(0).name.equals("إذاعة القرآن الكريم من السعودية") && fb.get(1).name.equals("إذاعة القرآن الكريم من القاهرة"), fb.size());
        check("radio: without mp3quran.net's list, the bundled reciters (Maher first), qurango.net first, backup.qurango.net second",
            "63".equals(fb.get(2).id) && fb.get(2).urls.length == 2 && fb.get(2).urls[0].equals("https://qurango.net/radio/maher")
            && fb.get(2).urls[1].equals("https://backup.qurango.net/radio/maher"), fb.get(2).id);
        List<String[]> api = new ArrayList<>();   // as Radio.parse gives mp3quran.net's list: {id, name, url}
        api.add(new String[] {"1", "إبراهيم الأخضر", "https://backup.qurango.net/radio/ibrahim_alakdar"});
        api.add(new String[] {"109", "تلاوات خاشعة-", "https://backup.qurango.net/radio/salma"});
        api.add(new String[] {"109082", "إذاعة القرآن الكريم - السعودية", "https://stream.radiojar.com/0tpy1h0kxtzuv"});
        api.add(new String[] {"5", "", "https://backup.qurango.net/radio/x"});
        api.add(new String[] {"6", "بلا رابط", "not a url"});
        api.add(new String[] {"-1", "بلا رقم", "https://backup.qurango.net/radio/y"});
        api.add(new String[] {"1", "إبراهيم الأخضر", "https://backup.qurango.net/radio/ibrahim_alakdar"});
        List<RadioStations.Station> m = RadioStations.merge(api);
        List<String> mid = new ArrayList<>();
        for (RadioStations.Station s : m) mid.add(s.id);
        check("radio: mp3quran.net's list after the pinned ones, each once; its copy of the Saudi radio, a nameless, an "
            + "address-less or id-less one left out", mid.equals(Arrays.asList("saudi", "cairo", "1", "109")), mid);
        check("radio: names tidied («تلاوات خاشعة-» -> «تلاوات خاشعة»)", "تلاوات خاشعة".equals(RadioStations.find(m, "109").name), "");
        check("radio: find by id", RadioStations.find(fb, "cairo") != null && RadioStations.find(fb, "nope") == null, "");
        List<RadioStations.Station> q = RadioStations.search(RadioStations.merge(null), "السديس");
        check("radio: search in the names (Arabic, with or without «ال»)", q.size() == 1 && "33".equals(q.get(0).id)
            && RadioStations.search(RadioStations.merge(null), "سديس").size() == 1
            && RadioStations.search(RadioStations.merge(null), "اذاعه القران").size() == 2, q.size());
        check("radio: an empty search keeps them all", RadioStations.search(fb, "").size() == fb.size(), "");

        // ---- the radio's settings values
        check("radio volume: 10 … 100 in steps of 10", RadioStations.volume("40") == 40 && RadioStations.volume("100%") == 100
            && RadioStations.volume("10") == 10 && RadioStations.volume("45") == 50 && RadioStations.volume("٥٠٪") == -1, "");
        check("radio volume: refused out of range or not a number", RadioStations.volume("5") == -1 && RadioStations.volume("101") == -1
            && RadioStations.volume("abc") == -1 && RadioStations.volume(null) == -1 && RadioStations.volume("-20") == -1, "");
        check("radio volume: kept in range", RadioStations.clampVolume(0) == 10 && RadioStations.clampVolume(250) == 100
            && RadioStations.VOLUME_DEFAULT >= 10 && RadioStations.VOLUME_DEFAULT <= 100, "");
        check("radio volume: the player's gain is squared (50 % = 0.25)", Math.abs(RadioStations.gain(50) - 0.25f) < 1e-6
            && RadioStations.gain(100) == 1f, RadioStations.gain(50));
        check("radio: a custom address is http(s) with a host", RadioStations.validUrl("http://example.com:8000/live")
            && RadioStations.validUrl("https://a.b/x.m3u8") && !RadioStations.validUrl("ftp://a.b/x") && !RadioStations.validUrl("https://nohost")
            && !RadioStations.validUrl("javascript:alert(1)") && !RadioStations.validUrl("https://a.b/x y"), "");

        System.out.println(failed == 0 ? "\nall logic tests passed" : "\n" + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
