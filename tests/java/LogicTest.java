package com.raad.sauditv.screensaver;

import com.raad.sauditv.home.RadioChip;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The settings app's plain-Java logic, run by tests/java_test.py (javac and java, no Android): a change of country
 * (CountryChange: news, league, clubs; never on a reload or a re-pick in the same country), the Quran radio's
 * stations and volume (RadioStations), where it plays (RadioWhen) and the home screen's chip (RadioChip, the launcher's),
 * the ticker's items (Tape), older builds' saved cards without the gone TV shows card (PanelMigrate) and the size the
 * photos are kept at for the screen (PhotoSize).
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

        // ---- «متى تعمل الإذاعة» (RadioWhen.plays: when, on, home, saver, preview, override)
        String b = RadioWhen.BOTH, s = RadioWhen.SAVER, h = RadioWhen.HOME;
        check("radio when: the default is both (as before), unknown values too", RadioWhen.of(null).equals(b) && RadioWhen.of("x").equals(b)
            && RadioWhen.of("saver").equals(s) && RadioWhen.label(s).equals("شاشة التوقف فقط"), "");
        // home -> the screensaver starts (home still bound for its 3 s) -> the screensaver alone -> back home
        boolean[][] steps = {{true, false}, {true, true}, {false, true}, {true, false}};
        for (String[] w : new String[][] {{b, "1111"}, {s, "0110"}, {h, "1001"}}) {
            StringBuilder got = new StringBuilder();
            for (boolean[] st : steps) got.append(RadioWhen.plays(w[0], true, st[0], st[1], false, false) ? '1' : '0');
            check("radio when " + w[0] + ": home, the screensaver starts, the screensaver, back home = " + w[1], got.toString().equals(w[1]), got);
        }
        check("radio when saver: «تشغيل القرآن» on the home screen plays it there (the override)",
            RadioWhen.plays(s, true, true, false, false, true) && !RadioWhen.plays(s, true, true, false, false, false), "");
        check("radio when home: the override does not play it over the screensaver", !RadioWhen.plays(h, true, false, true, false, true), "");
        check("radio when: off is silent anywhere; nothing in front is silent; the settings' preview always plays",
            !RadioWhen.plays(b, false, true, true, false, true) && !RadioWhen.plays(b, true, false, false, false, true)
            && RadioWhen.plays(s, false, false, false, true, false) && RadioWhen.plays(h, false, true, true, true, false), "");

        // ---- «إذاعة القرآن الكريم» as one choice (RadioWhen.MODES), stored as before: radio on/off + radioWhen
        check("radio choice: four, in order: off, always (both), the screensaver only, the home screen only",
            RadioWhen.MODES.length == 4 && RadioWhen.MODES[0][0].equals(RadioWhen.OFF) && RadioWhen.MODES[0][1].equals("متوقفة")
            && RadioWhen.MODES[1][0].equals(b) && RadioWhen.MODES[1][1].equals("تعمل دائمًا")
            && RadioWhen.MODES[2][0].equals(s) && RadioWhen.MODES[2][1].equals("شاشة التوقف فقط")
            && RadioWhen.MODES[3][0].equals(h) && RadioWhen.MODES[3][1].equals("الشاشة الرئيسية فقط"), "");
        check("radio choice from the prefs: off whatever when; on -> its when (unknown when: always)",
            RadioWhen.mode(false, s).equals(RadioWhen.OFF) && RadioWhen.mode(false, null).equals(RadioWhen.OFF)
            && RadioWhen.mode(true, b).equals(b) && RadioWhen.mode(true, s).equals(s) && RadioWhen.mode(true, h).equals(h)
            && RadioWhen.mode(true, "x").equals(b) && RadioWhen.mode(true, null).equals(b), "");
        check("radio choice to the prefs: off = radio off keeping its when; each other = on with that when",
            !RadioWhen.modeOn(RadioWhen.OFF) && RadioWhen.modeWhen(RadioWhen.OFF, s).equals(s) && RadioWhen.modeWhen(RadioWhen.OFF, null).equals(b)
            && RadioWhen.modeOn(b) && RadioWhen.modeWhen(b, s).equals(b)
            && RadioWhen.modeOn(s) && RadioWhen.modeWhen(s, h).equals(s)
            && RadioWhen.modeOn(h) && RadioWhen.modeWhen(h, b).equals(h)
            && !RadioWhen.modeOn("x") && !RadioWhen.modeOn(null) && RadioWhen.modeWhen("x", h).equals(h), "");
        boolean round = true;
        for (String[] mo : RadioWhen.MODES) for (String keep : new String[] {b, s, h})
            round &= RadioWhen.mode(RadioWhen.modeOn(mo[0]), RadioWhen.modeWhen(mo[0], keep)).equals(mo[0]);
        check("radio choice: each choice saved and read back is itself (whatever when was saved before)", round, "");
        check("radio choice: its subtitle is the choice's label (unknown: off)", RadioWhen.modeLabel(s).equals("شاشة التوقف فقط")
            && RadioWhen.modeLabel(RadioWhen.OFF).equals("متوقفة") && RadioWhen.modeLabel("x").equals("متوقفة"), "");

        // ---- the first-run setup: only a new install sees it (SetupState.initial, written once by Prefs.migrate)
        check("setup: a new install (nothing saved, never read) -> setupDone false", !SetupState.initial(false, false), "");
        check("setup: an older build's settings -> setupDone true", SetupState.initial(false, true), "");
        check("setup: settings this build already read before (the household's TVs) -> setupDone true",
            SetupState.initial(true, true) && SetupState.initial(true, false), "");
        check("setup: the pref is setupDone", SetupState.DONE.equals("setupDone"), SetupState.DONE);

        // ---- the home screen's «تشغيل القرآن» / «إيقاف القرآن» (RadioChip)
        check("radio chip: «إيقاف القرآن» while it plays, loads or retries", RadioChip.label("1", "playing").equals("إيقاف القرآن")
            && RadioChip.label("1", "loading").equals(RadioChip.STOP) && RadioChip.label("1", "error").equals(RadioChip.STOP), "");
        check("radio chip: «تشغيل القرآن» while off, paused by the remote, or silent here (idle)", RadioChip.label("0", "idle").equals("تشغيل القرآن")
            && RadioChip.label("1", "paused").equals(RadioChip.PLAY) && RadioChip.label("1", "idle").equals(RadioChip.PLAY)
            && RadioChip.label("0", "playing").equals(RadioChip.PLAY), "");
        check("radio chip: a press turns it off while it plays, else on (and playing now)", !RadioChip.pressTurnsOn("1", "playing")
            && RadioChip.pressTurnsOn("1", "paused") && RadioChip.pressTurnsOn("0", "idle"), "");

        // ---- «عناصر شريط الأسعار» (Tape)
        check("tape: automatic = each country's own list (SA: its index first; US: none)",
            Tape.auto("SA").toString().equals("[idx, spx, ndx, dji, gold, brent, cur, btc, eth]")
            && Tape.auto("EG").get(0).equals("idx") && !Tape.auto("US").contains("idx") && Tape.auto("US").size() == 8
            && Tape.auto(null).equals(Tape.auto("SA")), Tape.auto("US"));
        check("tape: a choice in its order, each once; ids in any case; auto or empty = automatic; unknown refused",
            Tape.parse("gold, BTC,gold,c:sar,i:ae").toString().equals("[gold, btc, c:SAR, i:AE]") && Tape.parse("auto").isEmpty()
            && Tape.parse("").isEmpty() && Tape.parse("gold,nope") == null, Tape.parse("gold, BTC,gold,c:sar,i:ae"));
        check("tape: the country's own list saved is automatic again; another order is kept",
            Tape.store(Tape.auto("SA"), "SA").isEmpty() && Tape.store(Arrays.asList("gold", "btc", "cur"), "SA").equals("gold,btc,cur")
            && Tape.store(Arrays.asList("spx", "idx"), "SA").equals("spx,idx"), "");
        check("tape: a choice stays whatever the country (cur and idx are the country's: they follow it)",
            Tape.store(Arrays.asList("gold", "btc", "cur"), "US").equals("gold,btc,cur")
            && Tape.store(Arrays.asList("gold", "btc", "cur"), "EG").equals("gold,btc,cur"), "");
        check("tape: the picker does not list the country's own index and currency twice (SA: no i:SA, c:SAR)",
            !Tape.offered("SA").contains("i:SA") && !Tape.offered("SA").contains("c:SAR") && Tape.offered("SA").contains("i:EG")
            && Tape.offered("EG").contains("i:SA") && !Tape.offered("EG").contains("c:EGP") && Tape.offered("US").size() == Tape.ITEMS.length, "");

        // ---- older builds' saved cards: the TV shows card («جديد المسلسلات») is gone (PanelMigrate, read by Prefs)
        check("cards: a saved list drops shows, the others keep their order",
            PanelMigrate.dropShows("shows,prayers,news").equals("prayers,news")
            && PanelMigrate.dropShows("prayers, shows ,forecast").equals("prayers,forecast")
            && PanelMigrate.dropShows("matches,shows").equals("matches"), PanelMigrate.dropShows("shows,prayers,news"));
        check("cards: a saved panel=shows (or a list of only shows) becomes prayers",
            PanelMigrate.dropShows("shows").equals("prayers") && PanelMigrate.dropShows(" shows ").equals("prayers")
            && PanelMigrate.dropShows("shows,shows").equals("prayers"), PanelMigrate.dropShows("shows"));
        check("cards: without shows nothing changes (none, one card, a list; nothing saved stays null)",
            PanelMigrate.dropShows("none").equals("none") && PanelMigrate.dropShows("prayers").equals("prayers")
            && PanelMigrate.dropShows("prayers,forecast,matches").equals("prayers,forecast,matches")
            && PanelMigrate.dropShows(null) == null, "");

        // ---- a new install's card beside the clock: the verse and the hadith (Prefs.migrate); saved ones stay
        check("cards: a new install shows «آية اليوم» and «حديث اليوم» (verse,hadith), in the panel list format",
            PanelMigrate.FRESH.equals("verse,hadith") && PanelMigrate.dropShows(PanelMigrate.FRESH).equals("verse,hadith"),
            PanelMigrate.FRESH);

        // ---- a new install's place (PlaceGuess, run once by PlaceDetect): the time zone's country against the IP's
        final java.util.Map<String, List<PlaceGuess.City>> geo = new java.util.HashMap<>();
        geo.put("US", Arrays.asList(
            new PlaceGuess.City("نيويورك", "New York City", "40.7143", "-74.0060", "America/New_York"),
            new PlaceGuess.City("لوس أنجلوس", "Los Angeles", "34.0522", "-118.2437", "America/Los_Angeles"),
            new PlaceGuess.City("شيكاغو", "Chicago", "41.8500", "-87.6500", "America/Chicago"),
            new PlaceGuess.City("سان فرانسيسكو", "San Francisco", "37.7749", "-122.4194", "America/Los_Angeles"),
            new PlaceGuess.City("بويسي", "Boise", "43.6135", "-116.2035", "America/Boise")));
        geo.put("SA", Arrays.asList(
            new PlaceGuess.City("جدة", "Jeddah", "21.4901", "39.1862", "Asia/Riyadh"),
            new PlaceGuess.City("الرياض", "Riyadh", "24.6877", "46.7219", "Asia/Riyadh"),
            new PlaceGuess.City("الدمام", "Dammam", "26.4344", "50.1033", "Asia/Riyadh")));
        PlaceGuess.Cities cities = cc -> geo.containsKey(cc) ? geo.get(cc) : new ArrayList<PlaceGuess.City>();
        PlaceGuess.Pick g = PlaceGuess.choose("America/Los_Angeles", "US",
            new PlaceGuess.Ip("us", "Daly City", 37.70, -122.47), cities);
        check("place: the IP in the time zone's country -> its nearest bundled city (Daly City: San Francisco)",
            g != null && g.cc.equals("US") && g.city.alt.equals("San Francisco"), g == null ? null : g.city.alt);
        g = PlaceGuess.choose("Asia/Riyadh", "SA", new PlaceGuess.Ip("SA", "Dammam", 26.40, 50.10), cities);
        check("place: a Saudi IP in Dammam -> الدمام", g != null && g.cc.equals("SA") && g.city.name.equals("الدمام"),
            g == null ? null : g.city.name);
        g = PlaceGuess.choose("America/Los_Angeles", "US", new PlaceGuess.Ip("SA", "Riyadh", 24.69, 46.72), cities);
        check("place: a VPN in another country -> the time zone's own city (America/Los_Angeles: Los Angeles)",
            g != null && g.cc.equals("US") && g.city.alt.equals("Los Angeles"), g == null ? null : g.cc + " " + g.city.alt);
        g = PlaceGuess.choose("Asia/Riyadh", "SA", new PlaceGuess.Ip("US", "Ashburn", 39.04, -77.49), cities);
        check("place: Asia/Riyadh behind a US VPN -> الرياض (not Jeddah, the biggest)",
            g != null && g.cc.equals("SA") && g.city.name.equals("الرياض"), g == null ? null : g.city.name);
        g = PlaceGuess.choose("America/New_York", "US", null, cities);
        check("place: no IP answer -> the time zone's city (New York = GeoNames' New York City)",
            g != null && g.city.alt.equals("New York City"), g == null ? null : g.city.alt);
        g = PlaceGuess.choose("America/Denver", "US", null, cities);
        check("place: a zone whose city is not listed -> the biggest city in that zone, else the country's biggest",
            g != null && g.city.alt.equals("New York City")
            && PlaceGuess.choose("America/Boise", "US", null, cities).city.alt.equals("Boise"), g == null ? null : g.city.alt);
        g = PlaceGuess.choose("Etc/UTC", null, new PlaceGuess.Ip("US", "Chicago", 41.88, -87.63), cities);
        check("place: a time zone of no country -> the IP's city (Chicago)",
            g != null && g.cc.equals("US") && g.city.alt.equals("Chicago"), g == null ? null : g.city.alt);
        g = PlaceGuess.choose("Etc/UTC", "001", new PlaceGuess.Ip("SA", "Riyadh", Double.NaN, Double.NaN), cities);
        check("place: an IP without coordinates -> its city by name (Riyadh); \"001\" is no country",
            g != null && g.city.name.equals("الرياض"), g == null ? null : g.city.name);
        check("place: nothing known (no zone country, no IP) -> null: the default city stays",
            PlaceGuess.choose("Etc/UTC", null, null, cities) == null
            && PlaceGuess.choose("Europe/Paris", "FR", null, cities) == null
            && PlaceGuess.choose(null, "", new PlaceGuess.Ip("FR", "Paris", 48.85, 2.35), cities) == null, "");
        check("place: a time zone's exemplar city", PlaceGuess.exemplar("America/Argentina/Buenos_Aires").equals("Buenos Aires")
            && PlaceGuess.exemplar("Asia/Riyadh").equals("Riyadh") && PlaceGuess.exemplar("UTC").isEmpty(), "");

        // ---- the photos at the size the screen draws them (PhotoSize, PhotoScale): 1920 x 1080 photos, a TV that draws
        // 1280 x 720 (TV 1) gets 1408 x 792 (1.1 times the screen: the page zooms a photo by up to 1.1); 1920 x 1080 as is
        int[] t = PhotoSize.target(1920, 1080, 1280, 720);
        check("photos: 1920 x 1080 on a 1280 x 720 screen -> kept at 1408 x 792 (1.1 x the screen)",
            t != null && t[0] == 1408 && t[1] == 792, Arrays.toString(t));
        t = PhotoSize.target(1920, 1080, 720, 1280);
        check("photos: the screen's size the other way round (portrait metrics) -> the same", t != null && t[0] == 1408 && t[1] == 792,
            Arrays.toString(t));
        check("photos: a 1920 x 1080 screen (or bigger) -> as they are", PhotoSize.target(1920, 1080, 1920, 1080) == null
            && PhotoSize.target(1920, 1080, 3840, 2160) == null, "");
        check("photos: a copy only when it saves at least a quarter of the pixels (1600 x 900 screen: as they are)",
            PhotoSize.target(1920, 1080, 1600, 900) == null, Arrays.toString(PhotoSize.target(1920, 1080, 1600, 900)));
        t = PhotoSize.target(1920, 1280, 1280, 720);
        check("photos: another shape covers the screen on its tighter side (3:2 on 16:9 -> 1408 wide)",
            t != null && t[0] == 1408 && t[1] == 939, Arrays.toString(t));
        check("photos: an unknown size -> as it is", PhotoSize.target(0, 0, 1280, 720) == null && PhotoSize.target(1920, 1080, 0, 0) == null, "");
        check("photos: decode at full size for 1408 from 1920, at half from 3840 (inSampleSize)",
            PhotoSize.sample(1920, 1408) == 1 && PhotoSize.sample(3840, 1408) == 2 && PhotoSize.sample(5760, 1408) == 4, "");

        System.out.println(failed == 0 ? "\nall logic tests passed" : "\n" + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
