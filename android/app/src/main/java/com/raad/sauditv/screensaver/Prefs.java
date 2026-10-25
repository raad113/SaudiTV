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

    // TVMaze genre (sent to the page), Arabic label
    static final String[][] GENRES = {
        {"Drama", "دراما"}, {"Comedy", "كوميديا"}, {"Action", "أكشن"}, {"Crime", "جريمة"},
        {"Thriller", "إثارة"}, {"Mystery", "غموض"}, {"Science-Fiction", "خيال علمي"},
        {"Fantasy", "فانتازيا"}, {"Adventure", "مغامرة"}, {"Romance", "رومانسي"},
        {"Family", "عائلي"}, {"History", "تاريخي"}, {"Horror", "رعب"},
        {"Anime", "أنمي"}, {"Documentary", "وثائقي"}, {"Sports", "رياضة"},
    };

    // TVMaze language, Arabic label
    static final String[][] LANGS = {
        {"English", "الإنجليزية"}, {"Arabic", "العربية"}, {"Korean", "الكورية"},
        {"Japanese", "اليابانية"}, {"Turkish", "التركية"}, {"Spanish", "الإسبانية"},
        {"French", "الفرنسية"}, {"Hindi", "الهندية"}, {"German", "الألمانية"},
    };

    static final String[] MIN_RATINGS = {"0", "6", "6.5", "7", "7.5", "8"};

    private final SharedPreferences sp;

    Prefs(Context c) { sp = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }

    /** {name, latitude, longitude}; Riyadh until the user picks */
    String[] city() {
        return new String[] {sp.getString("cityName", "الرياض"), sp.getString("cityLat", "24.6877"), sp.getString("cityLon", "46.7219")};
    }
    void setCity(String name, String lat, String lon) {
        sp.edit().putString("cityName", name).putString("cityLat", lat).putString("cityLon", lon).apply();
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

    /** false when already followed or the list is full */
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
        {"shows", "جديد المسلسلات"}, {"prayers", "مواقيت الصلاة اليوم"}, {"forecast", "طقس الأيام القادمة"},
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
    static final String[][] NUMERALS = {{"latn", "⁦‭0 1 2 3‬⁩"}, {"arab", "⁦‭٠ ١ ٢ ٣‬⁩"}};

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
    static final String[] NEWS_PAGES = {"news", "tech", "local", "world"};
    static final String[][][] NEWS_SOURCES = {
        {   // news: أخبار السعودية
            {"aawsat", "الشرق الأوسط", "1"}, {"okaz", "عكاظ", "1"}, {"sabq", "سبق", "1"}, {"spa", "واس", "1"},
            {"alarabiya", "العربية", "0"}, {"alriyadh", "الرياض", "0"}, {"alwatan", "الوطن", "0"},
            {"bbc", "بي بي سي عربي", "0"}, {"google", "أخبار Google", "0"},
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
            {"cnn", "CNN بالعربية", "1"}, {"aawsat", "الشرق الأوسط", "0"}, {"aljazeera", "الجزيرة", "0"},
            {"france24", "فرانس 24", "0"}, {"google", "أخبار Google", "0"},
        },
    };

    static String[][] newsSources(String page) {
        for (int i = 0; i < NEWS_PAGES.length; i++) if (NEWS_PAGES[i].equals(page)) return NEWS_SOURCES[i];
        return new String[0][];
    }

    /** the picked sources of a news page, in the list's order; none picked = its defaults */
    List<String> sources(String page) {
        String[][] all = newsSources(page);
        List<String> want = list("src_" + page), out = new ArrayList<>(), def = new ArrayList<>();
        for (String[] x : all) { if (want.contains(x[0])) out.add(x[0]); if ("1".equals(x[2])) def.add(x[0]); }
        return out.isEmpty() ? def : out;
    }
    /** an empty list (or exactly the defaults) is stored as none: the page's own defaults */
    void setSources(String page, List<String> ids) {
        List<String> def = new ArrayList<>(), keep = new ArrayList<>();
        for (String[] x : newsSources(page)) { if ("1".equals(x[2])) def.add(x[0]); if (ids.contains(x[0])) keep.add(x[0]); }
        if (keep.isEmpty() || keep.equals(def)) sp.edit().remove("src_" + page).apply();
        else setList("src_" + page, keep);
    }

    /** Older builds had an on/off switch for the shows card; off becomes "none". */
    String panel() { return sp.getString("panel", sp.getBoolean("showWeek", true) ? "shows" : "none"); }
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

    List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString(key, "").split(";")) if (!s.isEmpty()) out.add(s);
        return out;
    }
    void setList(String key, List<String> v) { sp.edit().putString(key, join(v, ";")).apply(); }

    /** shows picked one by one, stored as "id|name" */
    List<String> shows() { return list("shows"); }

    String minRating() { return sp.getString("minRating", "7"); }
    void setMinRating(String v) { sp.edit().putString("minRating", v).apply(); }

    // on by default: the page comes from this project's own repository (Updater), so page fixes reach the TV
    // without a reinstall; the settings screen or `optimize/saver.sh autoupdate off` turn it off
    boolean autoUpdate() { return sp.getBoolean("autoUpdate", true); }

    /** The TV's time zone (Riyadh only if the TV has none). */
    static String timeZone() {
        String id = java.util.TimeZone.getDefault().getID();
        return id == null || id.isEmpty() || "GMT".equals(id) ? TZ : id;
    }

    /** ISNA (2) in the Americas, Umm al-Qura (4) elsewhere; "method" overrides. */
    int prayerMethod() {
        return sp.getInt("method", timeZone().startsWith("America/") ? 2 : 4);
    }
    void setAutoUpdate(boolean v) { sp.edit().putBoolean("autoUpdate", v).apply(); }

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
        List<String> ids = new ArrayList<>();
        for (String s : shows()) ids.add(s.split("\\|", 2)[0]);
        return "city=" + Uri.encode(city[0]) + "&lat=" + city[1] + "&lon=" + city[2]
            // the TV's own time zone and a prayer method that fits it
            + "&tz=" + Uri.encode(timeZone())
            + "&method=" + prayerMethod()
            + "&teams=" + join(teamIds(), ",")
            // the followed teams' Arabic names, same order (the page maps Saudi clubs to ESPN by name)
            + "&teamnames=" + join(teamNames(), ",")
            + panelParams(panel())
            + "&week=" + ("none".equals(panel()) ? "0" : "1")   // for a page older than #panel
            + "&genres=" + Uri.encode(join(list("genres"), ","))
            + "&langs=" + Uri.encode(join(list("langs"), ","))
            + "&shows=" + join(ids, ",")
            + "&minrating=" + minRating()
            + "&numerals=" + numerals()
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

    /** The card on the launcher's home screen: one of PANELS, off ("none") until the user picks one. */
    String homePanel() { return sp.getString("homePanel", "none"); }
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
