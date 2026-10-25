package com.raad.sauditv.screensaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Everything the user picks in settings, and the dashboard URL built from it. */
final class Prefs {
    static final String TZ = "Asia/Riyadh";

    // TheSportsDB id, Arabic name; more teams come from the in-app search
    static final String[][] TEAM_PRESETS = {
        {"136013", "الهلال"}, {"136137", "المنتخب السعودي"},
    };

    // League name exactly as TheSportsDB spells it (checked from CI, .github/tools/fetch_leagues.py), Arabic label.
    // Teams are fetched by name: with the free key, lookup_all_teams.php ignores the id and always
    // returns the same English League One sample.
    static final String[][] LEAGUES = {
        {"Saudi-Arabian Pro League", "دوري روشن السعودي"},
        {"Saudi First Division League", "دوري يلو (الدرجة الأولى)"},
        {"English Premier League", "الدوري الإنجليزي"},
        {"Spanish La Liga", "الدوري الإسباني"},
        {"Italian Serie A", "الدوري الإيطالي"},
        {"German Bundesliga", "الدوري الألماني"},
        {"French Ligue 1", "الدوري الفرنسي"},
        {"Portuguese Primeira Liga", "الدوري البرتغالي"},
        {"Dutch Eredivisie", "الدوري الهولندي"},
        {"Turkish Super Lig", "الدوري التركي"},
        {"Egyptian Premier League", "الدوري المصري"},
        {"UAE Pro League", "الدوري الإماراتي"},
        {"Qatar Stars League", "الدوري القطري"},
        {"American Major League Soccer", "الدوري الأمريكي"},
    };

    // The free key lists only the first 10 clubs of a league (alphabetical): clubs we want listed anyway.
    // League name, team id, English name; ids from .github/tools/find_teams.py (CI).
    static final String[][] LEAGUE_EXTRA = {
        {"Saudi First Division League", "150643", "Al-Zulfi"},
    };

    private final SharedPreferences sp;
    private final Context app;

    Prefs(Context c) {
        sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        RadioNotify.watch(app, sp);   // the home screen hears of every change to the radio's settings
        AthanScheduler.watch(app, sp);   // the athan's alarm follows the city, the method and its own settings
        migrate();
        if (sp.getBoolean(PlaceDetect.PENDING, false)) PlaceDetect.start(app);   // a new install's place, once
    }

    /**
     * Once, the first time this build reads the settings. An older build's settings (any at all) keep what that build
     * showed: the Saudi news («أخبار السعودية») and the Roshn table, whatever country or teams are picked later. A new
     * install has none: its news follow the city's country, its table the first followed club's league; its card beside
     * the clock is «آية اليوم» and «حديث اليوم» (PanelMigrate.FRESH); its «شكل الواجهة» is «الجديد» (Look.FRESH; saved
     * settings without one stay «الكلاسيكي»); and its place is guessed once (PlaceDetect).
     * The first-run setup (SetupActivity) shows on a new install only: setupDone false there, true for any settings
     * saved before, including those whose first read was already done (SetupState.initial).
     */
    private void migrate() {
        if (sp.contains("geo")) {
            if (!sp.contains(SetupState.DONE)) sp.edit().putBoolean(SetupState.DONE, SetupState.initial(true, true)).apply();
            return;
        }
        boolean older = !sp.getAll().isEmpty();
        SharedPreferences.Editor e = sp.edit().putInt("geo", 1).putBoolean(SetupState.DONE, SetupState.initial(false, older));
        if (older) {
            if (!sp.contains("newsCountry")) e.putString("newsCountry", "SA");
            if (!sp.contains("league")) e.putString("league", Leagues.ROSHN);
        } else {
            e.putString("panel", PanelMigrate.FRESH);
            e.putString("look", Look.FRESH);
            e.putBoolean(PlaceDetect.PENDING, true);
        }
        e.apply();
    }

    /** {name, latitude, longitude}; Riyadh until the user picks */
    String[] city() {
        return new String[] {sp.getString("cityName", "الرياض"), sp.getString("cityLat", "24.6877"), sp.getString("cityLon", "46.7219")};
    }
    /** a city without its country or time zone (older callers): both stay as they are. A city set (the settings,
     *  ADB) is no longer the guessed one, and no guess replaces it any more (PlaceDetect) */
    void setCity(String name, String lat, String lon) {
        sp.edit().putString("cityName", name).putString("cityLat", lat).putString("cityLon", lon)
            .remove(PlaceDetect.PENDING).remove(PlaceDetect.AUTO).apply();
    }
    /** a city with its country (ISO code) and its IANA time zone ("" = the TV's); as above, no guess any more */
    void setCity(String name, String lat, String lon, String cc, String tz) {
        sp.edit().putString("cityName", name).putString("cityLat", lat).putString("cityLon", lon)
            .putString("country", cc == null ? "" : cc.toUpperCase(java.util.Locale.ROOT)).putString("cityTz", tz == null ? "" : tz)
            .remove(PlaceDetect.PENDING).remove(PlaceDetect.AUTO).apply();
    }
    /** the first-run setup was finished or skipped (or the TV had settings before it existed): SetupState */
    boolean setupDone() { return sp.getBoolean(SetupState.DONE, true); }
    void setSetupDone(boolean v) { sp.edit().putBoolean(SetupState.DONE, v).apply(); }

    /** the city is the one a new install guessed (PlaceDetect), not yet one the user picked: «(تلقائي)» */
    boolean cityAuto() { return sp.getBoolean(PlaceDetect.AUTO, false); }

    // ---------- the city's country: its prayer method, temperature unit and (on a new install) news follow it.
    // "" = not set (settings of an older build, and a new install until a city is picked): the method follows the TV's
    // time zone as before (ISNA in the Americas, else Umm al-Qura), °C.
    String country() { return sp.getString("country", ""); }
    void setCountry(String cc) { sp.edit().putString("country", cc == null ? "" : cc.toUpperCase(java.util.Locale.ROOT)).apply(); }

    /** the city's own time zone, "" = the TV's (older builds, or a place picked without one) */
    String cityTz() { return sp.getString("cityTz", ""); }
    void setCityTz(String tz) { sp.edit().putString("cityTz", tz == null ? "" : tz).apply(); }
    /** the time zone the prayer times and the weather are given in: the city's, else the TV's */
    String placeTz() { String z = cityTz(); return z.isEmpty() ? timeZone() : z; }

    // ---------- the temperature unit: "c" or "f"; not set = the country's (°F in the US)
    String units() {
        String v = sp.getString("units", "");
        return "f".equals(v) || "c".equals(v) ? v : Geo.fahrenheit(country()) ? "f" : "c";
    }
    boolean unitsAuto() { String v = sp.getString("units", ""); return !"f".equals(v) && !"c".equals(v); }
    /** null or "auto" = the country's */
    void setUnits(String v) {
        if ("f".equals(v) || "c".equals(v)) sp.edit().putString("units", v).apply(); else sp.edit().remove("units").apply();
    }

    // ---------- «الدوري في الترتيب»: the league of the table card (Leagues ids); not set = the first followed club's
    String league() {
        String v = sp.getString("league", "");
        if (Leagues.find(v) != null) return v;
        return autoLeague();
    }
    boolean leagueAuto() { return Leagues.find(sp.getString("league", "")) == null; }
    /** the first followed club's league, else the country's first, else Roshn */
    String autoLeague() {
        for (String t : teamIds()) { String lg = Leagues.ofTeam(t); if (lg != null && Leagues.find(lg) != null) return lg; }
        List<Leagues.League> own = Leagues.ofCountry(country());
        return own.isEmpty() ? Leagues.ROSHN : own.get(0).id;
    }
    /** null or "auto" = the first followed club's league */
    void setLeague(String id) {
        if (Leagues.find(id) != null) sp.edit().putString("league", id).apply(); else sp.edit().remove("league").apply();
    }

    // ---------- «أخبار <الدولة>»: the news card's country; not set = the city's country (Saudi Arabia when none)
    String newsCountry() {
        String v = sp.getString("newsCountry", "");
        if (Geo.country(app, v) != null) return v;
        String cc = country();
        return Geo.country(app, cc) != null ? cc : "SA";
    }
    boolean newsCountryAuto() { return Geo.country(app, sp.getString("newsCountry", "")) == null; }
    /** null or "auto" = the city's; a different country drops the picked news sources (they were the other country's) */
    void setNewsCountry(String cc) {
        String before = newsCountry();
        SharedPreferences.Editor e = sp.edit();
        if (Geo.country(app, cc) != null) e.putString("newsCountry", cc.toUpperCase(java.util.Locale.ROOT)); else e.remove("newsCountry");
        e.remove("newsMissing");   // the user's own pick: no note about a change of country any more
        e.apply();
        if (!newsCountry().equals(before)) sp.edit().remove("src_news").apply();
    }
    /** a change of country (CountryChange) to a country without a news list of its own: the news country set
     *  explicitly (it stays), and that country remembered for the row's note; null: no note */
    void setNewsKept(String news, String missing) {
        SharedPreferences.Editor e = sp.edit();
        if (Geo.country(app, news) != null) e.putString("newsCountry", news.toUpperCase(java.util.Locale.ROOT));
        if (missing == null) e.remove("newsMissing"); else e.putString("newsMissing", missing);
        e.apply();
    }
    /** the news of country cc with its default sources (a change of country to cc, CountryChange) */
    void setNewsOf(String cc) {
        sp.edit().putString("newsCountry", cc.toUpperCase(java.util.Locale.ROOT)).remove("src_news").remove("newsMissing").apply();
    }
    /** the country a change of country left without its own news («لا توجد قائمة أخبار خاصة بـ…»), "" for none */
    String newsMissing() { return sp.getString("newsMissing", ""); }
    /** Saudi Arabia's list or an Arab country's own outlets (Prefs.COUNTRY_NEWS): a country's own news list */
    static boolean hasNewsList(String cc) {
        if (cc == null || cc.isEmpty() || "SA".equalsIgnoreCase(cc)) return true;
        for (String[][] c : COUNTRY_NEWS) if (c[0][0].equalsIgnoreCase(cc)) return true;
        return false;
    }

    /** «السعودية», «مصر» … */
    String newsCountryName() {
        Geo.Country c = Geo.country(app, newsCountry());
        return c == null ? "السعودية" : c.shortAr;
    }

    static final int MAX_TEAMS = 10;

    /** Followed teams in priority order, "id|name". Migrates the old two team fields once. */
    List<String> teams() {
        if (!sp.contains("teams")) {
            List<String> v = new ArrayList<>();
            for (int slot = 1; slot <= 2; slot++) {
                String[] def = TEAM_PRESETS[slot - 1];
                String id = sp.getString("team" + slot + "Id", def[0]);
                if (!id.isEmpty()) v.add(id + "|" + sp.getString("team" + slot + "Name", def[1]));
            }
            return v;
        }
        return list("teams");
    }

    /** false when already followed or the list is full. id: Leagues.validTeamId */
    boolean addTeam(String id, String name) {
        List<String> v = teams();
        for (String t : v) if (t.startsWith(id + "|")) return false;
        if (v.size() >= MAX_TEAMS) return false;
        v.add(id + "|" + name.replace("|", " ").replace(";", " "));
        setList("teams", v);
        return true;
    }

    // The card beside the clock: value sent to the page (#panel=), Arabic label. The page (PANEL_IDS), the launcher
    // (HomePage.PANELS) and optimize/saver.sh list the same ids.
    static final String[][] PANELS = {
        {"prayers", "مواقيت الصلاة اليوم"}, {"forecast", "طقس الأيام القادمة"},
        {"matches", "مباريات فرقك القادمة"},
        {"verse", "آية اليوم"},     // a verse of the Quran
        {"hadith", "حديث اليوم"},   // a hadith of Sahih al-Bukhari (the page's loadHadith)
        {"standings", "ترتيب دوري روشن"}, {"news", "أخبار السعودية"},
        {"local", "أخبار المدينة"},   // the news of the city set above (the page's loadLocalNews)
        {"world", "أخبار العالم"},
        {"tech", "أخبار التقنية والذكاء الاصطناعي"},
        {"none", "لا شيء"},
    };

    // ---------- «شكل الأرقام»: 0 1 2 3 (latn, the default) or ٠ ١ ٢ ٣ (arab), on the page (#numerals), the launcher
    // (ConfigProvider's numerals) and this app's own screens
    // each label drawn left to right as written here (an isolate, LRI … PDI, holding an override, LRO … PDF): in the
    // right-to-left settings the spaced digits otherwise come out the other way round («3 2 1 0», «٣ ٢ ١ ٠»)
    /** s kept left to right inside Arabic text («0 1 2 3», a version): one isolate, LRI … PDI, nothing stacked */
    static String ltr(String s) { return s == null ? "" : "⁦" + s + "⁩"; }
    static final String[][] NUMERALS = {{"latn", ltr("0 1 2 3")}, {"arab", ltr("٠ ١ ٢ ٣")}};

    String numerals() { return "arab".equals(sp.getString("numerals", "latn")) ? "arab" : "latn"; }
    void setNumerals(String v) { sp.edit().putString("numerals", "arab".equals(v) ? "arab" : "latn").apply(); }

    /** the digits of s (either form) in the chosen one */
    static String digits(Context c, String s) {
        if (s == null) return "";
        boolean arab = "arab".equals(new Prefs(c).numerals());
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (arab && ch >= '0' && ch <= '9') b.append((char) ('٠' + (ch - '0')));
            else if (!arab && ch >= '٠' && ch <= '٩') b.append((char) ('0' + (ch - '٠')));
            else b.append(ch);
        }
        return b.toString();
    }
    static String digits(Context c, long n) { return digits(c, String.valueOf(n)); }
    /** the decimal point and the percent sign that go with the digits: ٫ ٪ or . % */
    static char decimal(Context c) { return "arab".equals(new Prefs(c).numerals()) ? '٫' : '.'; }
    static char percent(Context c) { return "arab".equals(new Prefs(c).numerals()) ? '٪' : '%'; }

    // ---------- the home screen's app tiles (the launcher reads them from ConfigProvider: tile_shape, focus_style):
    // «شكل التطبيقات», banners with rounded corners (the default) or round app icons; «شكل التحديد», the focused
    // tile's look: Google TV's white ring (the default) or Apple TV's lift and shadow. Value, Arabic label.
    static final String[][] TILE_SHAPES = {{"rounded", "زوايا دائرية"}, {"circle", "دوائر"}};
    static final String[][] FOCUS_STYLES = {{"google", "Google TV"}, {"apple", "Apple TV"}};

    String tileShape() { return pick(TILE_SHAPES, sp.getString("tileShape", null)); }
    void setTileShape(String v) { sp.edit().putString("tileShape", pick(TILE_SHAPES, v)).apply(); }
    String focusStyle() { return pick(FOCUS_STYLES, sp.getString("focusStyle", null)); }
    void setFocusStyle(String v) { sp.edit().putString("focusStyle", pick(FOCUS_STYLES, v)).apply(); }

    /** «شكل الواجهة» of the home screen and the screensaver (Look): «الكلاسيكي» unless «الجديد» was set (a new install's) */
    String look() { return Look.of(sp.getString("look", null)); }
    void setLook(String v) { sp.edit().putString("look", Look.of(v)).apply(); }

    /** v when it is one of the table's values, else its first (the default) */
    static String pick(String[][] table, String v) {
        for (String[] x : table) if (x[0].equals(v)) return v;
        return table[0][0];
    }

    /** the Arabic label of a table's value */
    static String label(String[][] table, String v) {
        for (String[] x : table) if (x[0].equals(v)) return x[1];
        return table[0][1];
    }

    // ---------- each news page's sources: id (the page's NEWS_SOURCES in src/template.html), label, on by default.
    // Sent as #src_<page>=id,id; a page without a pick reads its defaults. tests/check.py compares both lists.
    // The country news («أخبار <الدولة>», page "news") has a list per country: Saudi Arabia's here, the other Arab
    // countries' in COUNTRY_NEWS, and Google News and Bing for every country (GENERIC_NEWS: all a country without its
    // own list has, both on).
    static final String[] NEWS_PAGES = {"news", "tech", "local", "world"};
    static final String[][][] NEWS_SOURCES = {
        {   // news: أخبار السعودية
            {"aawsat", "الشرق الأوسط", "1"}, {"okaz", "عكاظ", "1"}, {"sabq", "سبق", "1"}, {"spa", "واس", "1"},
            {"alarabiya", "العربية", "0"}, {"alriyadh", "الرياض", "0"}, {"alwatan", "الوطن", "0"},
            {"bbc", "بي بي سي عربي", "0"}, {"google", "أخبار Google", "0"}, {"bing", "أخبار Bing", "0"},
        },
        {   // tech
            {"techwd", "عالم التقنية", "1"}, {"aitnews", "البوابة العربية للأخبار التقنية", "1"},
            {"aawsat", "الشرق الأوسط", "1"}, {"bbc", "بي بي سي عربي", "0"}, {"argaam", "أرقام", "0"},
            {"google", "أخبار Google", "0"},
        },
        {   // local: the city's news
            {"google", "أخبار Google", "1"}, {"bing", "أخبار Bing", "1"}, {"okaz", "عكاظ (محليات)", "1"},
            {"alwatan", "الوطن (محليات)", "0"},
        },
        {   // world
            {"alarabiya", "العربية", "1"}, {"skynews", "سكاي نيوز عربية", "1"}, {"bbc", "بي بي سي عربي", "1"},
            {"aawsat", "الشرق الأوسط", "0"}, {"aljazeera", "الجزيرة", "0"},
            {"france24", "فرانس 24", "0"}, {"google", "أخبار Google", "0"},
        },
    };

    // the Arab countries' own outlets for «أخبار <الدولة>» (country code, then id, label, on by default): only those that
    // answered through the TVs' VPN (the page's #feedtest=1; Google News answers it 403, so a Bing search of the site
    // stands in); Google News and Bing follow each list (GENERIC_NEWS)
    static final String[][][] COUNTRY_NEWS = {
        {{"EG"}, {"ahram", "الأهرام", "1"}, {"youm7", "اليوم السابع", "1"}, {"almasryalyoum", "المصري اليوم", "1"},
            {"masrawy", "مصراوي", "0"}, {"shorouk", "الشروق", "0"}},
        {{"AE"}, {"albayan", "البيان", "1"}, {"emaratalyoum", "الإمارات اليوم", "1"}, {"alkhaleej", "الخليج", "1"},
            {"wam", "وام", "0"}},
        {{"KW"}, {"alrai", "الراي", "1"}, {"alanba", "الأنباء", "1"}, {"kuna", "كونا", "1"}, {"aljarida", "الجريدة", "0"},
            {"alqabas", "القبس", "0"}},
        {{"QA"}, {"alsharq", "الشرق", "1"}, {"alraya", "الراية", "1"}, {"alwatan", "الوطن", "1"}, {"qna", "قنا", "0"}},
        {{"BH"}, {"alayam", "الأيام", "1"}, {"alwatan", "الوطن", "1"}, {"akhbaralkhaleej", "أخبار الخليج", "1"}},
        {{"OM"}, {"omandaily", "جريدة عمان", "1"}, {"alroya", "الرؤية", "1"}, {"atheer", "أثير", "1"}},
        {{"JO"}, {"alrai", "الرأي", "1"}, {"alghad", "الغد", "1"}, {"ammon", "عمون", "1"}, {"roya", "رؤيا", "0"},
            {"petra", "بترا", "0"}},
        {{"IQ"}, {"alsumaria", "السومرية", "1"}, {"shafaq", "شفق نيوز", "1"}, {"baghdadtoday", "بغداد اليوم", "1"},
            {"alsabaah", "الصباح", "0"}},
        {{"LB"}, {"annahar", "النهار", "1"}, {"almodon", "المدن", "1"}, {"lbci", "LBCI", "1"}, {"alakhbar", "الأخبار", "0"}},
        {{"SY"}, {"sana", "سانا", "1"}, {"enabbaladi", "عنب بلدي", "1"}, {"syriatv", "تلفزيون سوريا", "1"},
            {"alwatan", "الوطن", "0"}},
        {{"PS"}, {"wafa", "وفا", "1"}, {"maan", "معا", "1"}, {"alquds", "القدس", "1"}, {"arab48", "عرب 48", "0"},
            {"palinfo", "المركز الفلسطيني للإعلام", "0"}},
        {{"YE"}, {"almasdar", "المصدر أونلاين", "1"}, {"yemenmonitor", "يمن مونيتور", "1"}},
        {{"SD"}, {"altaghyeer", "التغيير", "1"}, {"sudantribune", "سودان تربيون", "1"}},
        {{"LY"}, {"alwasat", "بوابة الوسط", "1"}, {"eanlibya", "عين ليبيا", "1"}, {"almarsad", "المرصد", "1"}},
        {{"TN"}, {"mosaique", "موزاييك", "1"}, {"assabah", "الصباح نيوز", "1"}, {"alchourouk", "الشروق", "1"},
            {"tap", "وات", "0"}},
        {{"DZ"}, {"echorouk", "الشروق", "1"}, {"ennahar", "النهار", "1"}, {"elkhabar", "الخبر", "1"}, {"aps", "واج", "0"}},
        {{"MA"}, {"hespress", "هسبريس", "1"}, {"alyaoum24", "اليوم 24", "1"}, {"hibapress", "هبة بريس", "1"},
            {"le360", "لو360", "0"}},
    };
    // every country's Google News (its edition, in its language) and Bing (its market); on by default where they are
    // all there is
    static final String[][] GENERIC_NEWS = {{"google", "أخبار Google", "0"}, {"bing", "أخبار Bing", "0"}};

    static String[][] newsSources(String page) {
        for (int i = 0; i < NEWS_PAGES.length; i++) if (NEWS_PAGES[i].equals(page)) return NEWS_SOURCES[i];
        return new String[0][];
    }

    /** the country news' sources for country cc: Saudi Arabia's list, an Arab country's outlets then Google and
     *  Bing, or only Google and Bing (both on) */
    static String[][] countryNews(String cc) {
        if (cc == null || "SA".equalsIgnoreCase(cc)) return NEWS_SOURCES[0];
        for (String[][] c : COUNTRY_NEWS) {
            if (!c[0][0].equalsIgnoreCase(cc)) continue;
            String[][] out = new String[c.length - 1 + GENERIC_NEWS.length][];
            System.arraycopy(c, 1, out, 0, c.length - 1);
            System.arraycopy(GENERIC_NEWS, 0, out, c.length - 1, GENERIC_NEWS.length);
            return out;
        }
        String[][] out = new String[GENERIC_NEWS.length][];
        for (int i = 0; i < out.length; i++) out[i] = new String[] {GENERIC_NEWS[i][0], GENERIC_NEWS[i][1], "1"};
        return out;
    }

    /** a news page's sources as this TV has them: the country news' for the news country */
    String[][] sourcesOf(String page) {
        return "news".equals(page) ? countryNews(newsCountry()) : newsSources(page);
    }

    /** the picked sources of a news page, in the list's order; none picked = its defaults */
    List<String> sources(String page) {
        String[][] all = sourcesOf(page);
        List<String> want = list("src_" + page), out = new ArrayList<>(), def = new ArrayList<>();
        for (String[] x : all) { if (want.contains(x[0])) out.add(x[0]); if ("1".equals(x[2])) def.add(x[0]); }
        return out.isEmpty() ? def : out;
    }
    /** an empty list (or exactly the defaults) is stored as none: the page's own defaults */
    void setSources(String page, List<String> ids) {
        List<String> def = new ArrayList<>(), keep = new ArrayList<>();
        for (String[] x : sourcesOf(page)) { if ("1".equals(x[2])) def.add(x[0]); if (ids.contains(x[0])) keep.add(x[0]); }
        if (keep.isEmpty() || keep.equals(def)) sp.edit().remove("src_" + page).apply();
        else setList("src_" + page, keep);
    }

    // «مصادر الصور»: where the background photos come from (the page's #photos=…, photoPools), in this order; id, label,
    // on by default. saudi and world: photos/online.json (tools/online_photos.py, Wikimedia Commons); ours: photos/ of
    // the repository; bing: Bing's photo of the day. Offline, or none of them at hand: the bundled ones.
    static final String[][] PHOTO_SOURCES = {{"saudi", "صور السعودية", "1"}, {"ours", "صورنا المرفقة", "1"},
            {"bing", "صورة Bing اليومية", "0"}, {"world", "صور عالمية من ويكيميديا", "0"}};
    // «صور السعودية» is off for now (the user, 2026-10-25: only our own photos, like the old Unsplash ones; the list's
    // switch is tools/online_photos.py's SAUDI). Off: the checklist does not offer it, the default is ours only, and a
    // saved choice with saudi (saudi,ours of a test build) is read as ours. true puts it back as it was.
    static final boolean SAUDI_PHOTOS = false;

    /** a photo source the checklist offers (saudi only while SAUDI_PHOTOS) */
    static boolean photoSourceOffered(String id) { return SAUDI_PHOTOS || !"saudi".equals(id); }

    /** ids as kept: saudi read as ours while SAUDI_PHOTOS is off; then in PHOTO_SOURCES' order, offered ones only */
    static List<String> photoSourcesOf(List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String[] x : PHOTO_SOURCES) {
            boolean picked = ids.contains(x[0]) || (!SAUDI_PHOTOS && "ours".equals(x[0]) && ids.contains("saudi"));
            if (picked && photoSourceOffered(x[0])) out.add(x[0]);
        }
        return out;
    }

    /** the default photo sources: on by default and offered (ours only while SAUDI_PHOTOS is off) */
    static List<String> defaultPhotoSources() {
        List<String> def = new ArrayList<>();
        for (String[] x : PHOTO_SOURCES) if ("1".equals(x[2]) && photoSourceOffered(x[0])) def.add(x[0]);
        return def;
    }

    /** the picked photo sources, in PHOTO_SOURCES' order; none picked = the defaults */
    List<String> photoSources() {
        List<String> out = photoSourcesOf(list("photoSources"));
        return out.isEmpty() ? defaultPhotoSources() : out;
    }
    /** an empty list (or exactly the defaults) is stored as none: the page's own defaults */
    void setPhotoSources(List<String> ids) {
        List<String> keep = photoSourcesOf(ids);
        if (keep.isEmpty() || keep.equals(defaultPhotoSources())) sp.edit().remove("photoSources").apply();
        else setList("photoSources", keep);
    }

    /** the screensaver's card; prayers until one is picked (a new install: PanelMigrate.FRESH, set by migrate). A
     *  saved choice of an older build drops its gone «جديد المسلسلات» card (PanelMigrate). */
    String panel() { return PanelMigrate.dropShows(sp.getString("panel", "prayers")); }
    void setPanel(String v) { sp.edit().putString("panel", v).apply(); }

    static String panelLabel(String v) {
        if (v != null && v.contains(",")) {   // two home cards
            List<String> labels = new ArrayList<>();
            for (String part : v.split(",")) labels.add(panelLabel(part.trim()));
            return join(labels, " + ");
        }
        for (String[] p : PANELS) if (p[0].equals(v)) return p[1];
        return PANELS[0][1];
    }

    /** as panelLabel, with this TV's news country («أخبار مصر») and table league («ترتيب الدوري الإنجليزي») */
    String panelTitle(String v) {
        if (v != null && v.contains(",")) {
            List<String> labels = new ArrayList<>();
            for (String part : v.split(",")) labels.add(panelTitle(part.trim()));
            return join(labels, " + ");
        }
        if ("news".equals(v)) return "أخبار " + newsCountryName();
        if ("standings".equals(v)) { Leagues.League l = Leagues.find(league()); return "ترتيب " + (l == null ? "دوري روشن" : l.title); }
        return panelLabel(v);
    }

    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString(key, "").split(";")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    void setList(String key, List<String> v) { sp.edit().putString(key, join(v, ";")).apply(); }

    // ---------- «إذاعة القرآن الكريم» (RadioService plays it while our home screen or screensaver is in front; off by
    // default): the station (RadioStations ids: saudi, cairo, an mp3quran.net id, custom), its name and address as
    // picked (so it plays without the list), and its volume in percent (10 … 100, apart from the TV's own volume)
    boolean radioOn() { return sp.getBoolean("radioOn", false); }
    void setRadioOn(boolean on) { sp.edit().putBoolean("radioOn", on).apply(); }
    String radioStation() { return sp.getString("radioStation", RadioStations.SAUDI); }
    String radioName() { return sp.getString("radioName", RadioStations.PINNED[0].name); }
    String radioUrl() { return sp.getString("radioUrl", RadioStations.PINNED[0].urls[0]); }
    void setRadioStation(String id, String name, String url) {
        sp.edit().putString("radioStation", id).putString("radioName", name).putString("radioUrl", url).apply();
    }
    int radioVolume() { return RadioStations.clampVolume(sp.getInt("radioVolume", RadioStations.VOLUME_DEFAULT)); }
    void setRadioVolume(int v) { sp.edit().putInt("radioVolume", RadioStations.clampVolume(v)).apply(); }
    /** «متى تعمل الإذاعة» (RadioWhen): both (the default), saver or home */
    String radioWhen() { return RadioWhen.of(sp.getString("radioWhen", RadioWhen.BOTH)); }
    void setRadioWhen(String v) { sp.edit().putString("radioWhen", RadioWhen.of(v)).apply(); }
    /** «إذاعة القرآن الكريم» as one choice (RadioWhen.MODES): off, or on with where it plays; stored as radioOn and
     *  radioWhen (off keeps radioWhen, so the home screen's «تشغيل القرآن» and saver.sh radio on find it again) */
    String radioMode() { return RadioWhen.mode(radioOn(), radioWhen()); }
    void setRadioMode(String m) {
        sp.edit().putString("radioWhen", RadioWhen.modeWhen(m, radioWhen())).putBoolean("radioOn", RadioWhen.modeOn(m)).apply();
    }

    // ---------- «عناصر شريط الأسعار» (Tape): the ticker's items in order; none = each country's own list («تلقائي»).
    // Its country is the news country (the page's TAPE_CC): cur and idx follow it, the choice itself stays.
    List<String> tape() { List<String> v = Tape.parse(sp.getString("tape", "")); return v == null ? new ArrayList<>() : v; }
    boolean tapeAuto() { return tape().isEmpty(); }
    /** the items as shown: the chosen ones, else the news country's own list */
    List<String> tapeShown() { return tapeAuto() ? Tape.auto(newsCountry()) : tape(); }
    /** [] (or the news country's own list) = automatic */
    void setTape(List<String> ids) {
        String v = Tape.store(ids, newsCountry());
        if (v.isEmpty()) sp.edit().remove("tape").apply(); else sp.edit().putString("tape", v).apply();
    }

    // on by default: the page comes from this project's own repository (Updater), so page fixes reach the TV
    // without a reinstall; the settings screen or `optimize/saver.sh autoupdate off` turn it off
    boolean autoUpdate() { return sp.getBoolean("autoUpdate", true); }

    /** The TV's time zone (Riyadh only if the TV has none). */
    static String timeZone() {
        String id = java.util.TimeZone.getDefault().getID();
        return id == null || id.isEmpty() || "GMT".equals(id) ? TZ : id;
    }

    /** «طريقة الحساب» (Aladhan's numbers, Geo.METHODS): the one picked, else the city's country's (Geo.methodFor);
     *  without a country (older builds' settings) as before: ISNA (2) in the Americas, Umm al-Qura (4) elsewhere */
    int prayerMethod() {
        if (sp.contains("method")) return sp.getInt("method", Geo.UMM_AL_QURA);
        return autoMethod();
    }
    int autoMethod() {
        String cc = country();
        if (!cc.isEmpty()) return Geo.methodFor(cc);
        return timeZone().startsWith("America/") ? Geo.ISNA : Geo.UMM_AL_QURA;
    }
    boolean methodAuto() { return !sp.contains("method"); }
    /** a Geo.METHODS number, or -1 = the country's */
    void setMethod(int m) {
        if (m > 0) sp.edit().putInt("method", m).apply(); else sp.edit().remove("method").apply();
    }
    void setAutoUpdate(boolean v) { sp.edit().putBoolean("autoUpdate", v).apply(); }

    /** «توفير الطاقة» (off by default): the ticker tape on the home screen and the screensaver drawn 30 times a second
     *  instead of 60 (the page's #lowpower=1, tapeStepped); the settings screen or `optimize/saver.sh lowpower on|off` */
    boolean lowPower() { return sp.getBoolean("lowPower", false); }
    void setLowPower(boolean v) { sp.edit().putBoolean("lowPower", v).apply(); }

    SharedPreferences raw() { return sp; }

    /** The page reads its settings from the fragment; a ?query fails on file:// in WebView. */
    String dashboardUrl(Context c) {
        String base = usesDownloadedPage(c) ? Uri.fromFile(Updater.pageFile(c)).toString() : "file:///android_asset/tv.html";
        return base + "#" + fragment();
    }

    /** The downloaded page is shown only while auto-update is on and it is newer than the installed APK: a page
     *  downloaded before the APK was installed or updated is stale, the APK carries a newer copy. */
    boolean usesDownloadedPage(Context c) {
        File remote = Updater.pageFile(c);
        long installed = 0;
        try { installed = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime; }
        catch (Exception ignored) { }
        return autoUpdate() && remote.isFile() && remote.length() > 0 && remote.lastModified() > installed;
    }

    /** Everything the page needs, as "city=..&lat=..&..."; also read by the launcher (ConfigProvider). */
    String fragment() {
        String[] city = city();
        Leagues.League lg = Leagues.find(league());
        Geo.Country news = Geo.country(app, newsCountry());
        return "city=" + Uri.encode(city[0]) + "&lat=" + city[1] + "&lon=" + city[2]
            // the TV's own time zone (the clock), the city's (its prayer times and weather; the page notes «بتوقيت
            // <city>» when they differ) and the prayer method
            + "&tz=" + Uri.encode(timeZone())
            + "&ptz=" + Uri.encode(placeTz())
            + "&cc=" + country()
            + "&method=" + prayerMethod()
            + "&units=" + units()
            // the table's league and its name in the card's title («ترتيب دوري روشن»)
            + "&league=" + league() + "&leaguename=" + Uri.encode(lg == null ? "دوري روشن" : lg.title)
            // the news card's country: its code, its name («أخبار مصر») and its language (Google News, Bing)
            + "&newscc=" + newsCountry() + "&newsname=" + Uri.encode(newsCountryName())
            + "&newslang=" + (news == null ? "ar" : Geo.ARAB.contains(news.cc) ? "ar" : news.lang)
            + "&newsen=" + Uri.encode(news == null ? "Saudi Arabia" : news.en)
            + "&teams=" + join(teamIds(), ",")
            // the followed teams' Arabic names, same order (the page maps Saudi clubs to ESPN by name)
            + "&teamnames=" + join(teamNames(), ",")
            + panelParams(panel())
            + "&numerals=" + numerals()
            + Look.param(look())   // «شكل الواجهة»: &look=new for «الجديد», nothing for «الكلاسيكي»
            + (tapeAuto() ? "" : "&tape=" + Uri.encode(join(tape(), ",")))   // the ticker's chosen items
            + (lowPower() ? "&lowpower=1" : "")   // «توفير الطاقة»: the ticker at 30 frames a second
            // «مصادر الصور» when picked (else the page's defaults: saudi,ours); always while SAUDI_PHOTOS is off, so
            // a Saudi list the page kept from before is never shown (ours by default)
            + (sp.contains("photoSources") || !SAUDI_PHOTOS ? "&photos=" + join(photoSources(), ",") : "")
            + sourceParams();
    }

    /** "&src_news=a,b…" for each news page whose sources were picked (the others: the page's defaults) */
    String sourceParams() {
        StringBuilder b = new StringBuilder();
        for (String page : NEWS_PAGES) if (sp.contains("src_" + page)) b.append("&src_").append(page).append('=').append(join(sources(page), ","));
        return b.toString();
    }

    /** "a,b,c" becomes a rotating card (&panel=a&panels=a,b,c&rotate=30); one value stays &panel=v. */
    static String panelParams(String v) {
        if (v == null || !v.contains(",")) return "&panel=" + v;
        List<String> parts = new ArrayList<>();
        for (String s : v.split(",")) { s = s.trim(); if (!s.isEmpty()) parts.add(s); }
        if (parts.isEmpty()) return "&panel=none";
        if (parts.size() == 1) return "&panel=" + parts.get(0);
        return "&panel=" + parts.get(0) + "&panels=" + join(parts, ",") + "&rotate=30";
    }

    /** The card on the launcher's home screen: one of PANELS, off ("none") until the user picks one; a saved choice
     *  of an older build drops «جديد المسلسلات» (PanelMigrate). */
    String homePanel() { return PanelMigrate.dropShows(sp.getString("homePanel", "none")); }
    void setHomePanel(String v) { sp.edit().putString("homePanel", v).apply(); }

    List<String> teamIds() {
        List<String> ids = new ArrayList<>();
        for (String t : teams()) ids.add(t.split("\\|", 2)[0]);
        return ids;
    }

    /** The Arabic name of each followed team, Uri-encoded, in teamIds() order ("" when absent). */
    List<String> teamNames() {
        List<String> names = new ArrayList<>();
        for (String t : teams()) {
            String[] p = t.split("\\|", 2);
            names.add(Uri.encode(p.length > 1 ? p[1].replace(",", " ").trim() : ""));
        }
        return names;
    }

    static String join(List<String> v, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : v) { if (b.length() > 0) b.append(sep); b.append(s); }
        return b.toString();
    }
}
