package com.raad.sauditv.screensaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The screensaver's settings from ADB, for agents (people use SettingsActivity). Only the shell can send
 * these: the manifest requires android.permission.DUMP. optimize/saver.sh wraps them.
 *
 *   am broadcast -n com.raad.sauditv.screensaver/.SaverReceiver -a com.raad.sauditv.screensaver.GET
 *   am broadcast -n com.raad.sauditv.screensaver/.SaverReceiver -a com.raad.sauditv.screensaver.CITIES
 *       [--es country EG [--es filter cair]]   (without a country: the built-in Saudi cities by region)
 *   am broadcast -n com.raad.sauditv.screensaver/.SaverReceiver -a com.raad.sauditv.screensaver.COUNTRIES
 *   am broadcast -n com.raad.sauditv.screensaver/.SaverReceiver -a com.raad.sauditv.screensaver.LEAGUES
 *   am broadcast -n com.raad.sauditv.screensaver/.SaverReceiver -a com.raad.sauditv.screensaver.SET
 *       [--es country EG|none] [--es city 'الرس'] [--es lat 25.86 --es lon 43.49 [--es tz Asia/Riyadh]]
 *       [--es method auto|umm-al-qura|isna|egypt|kuwait|qatar|dubai|mwl|karachi|diyanet] [--es units auto|c|f]
 *       [--es league auto|ksa.1|eng.1|tsdb.4829…] [--es news_country auto|SA|EG…]
 *       [--es teams '136013:Al-Hilal,E929@ksa.1:Al Hilal,138995@tsdb.4829:Al Ahly']
 *       [--es panel prayers | prayers,forecast,news,local,world,verse,hadith] [--es home_panel none] [--es minrating 7]
 *       [--es genres Drama,Comedy] [--es langs English] [--es autoupdate true|false] [--es numerals latn|arab]
 *       [--es src_news aawsat,okaz] [--es src_tech …] [--es src_local …] [--es src_world …]   ("" = the page's defaults)
 *       [--es tile_shape rounded|circle] [--es focus_style google|apple]   (the home screen's app tiles)
 *   am broadcast -n com.raad.sauditv.screensaver/.SaverReceiver -a com.raad.sauditv.screensaver.UPDATE
 *   am broadcast -n com.raad.sauditv.screensaver/.SaverReceiver -a com.raad.sauditv.screensaver.APP_CHECK
 *       (checks the GitHub release for a newer version of both apps; installing is the settings screen's)
 *
 * The answer is in the "data" of am's output. Unknown values are refused with a message, nothing half-saved.
 */
public class SaverReceiver extends BroadcastReceiver {
    /** Tells a running home screen (com.raad.sauditv.home) that the settings changed. */
    static final String CHANGED = "com.raad.sauditv.screensaver.CONFIG_CHANGED";

    @Override
    public void onReceive(Context c, Intent i) {
        Prefs p = new Prefs(c);
        String action = String.valueOf(i.getAction());
        switch (action) {
            case "com.raad.sauditv.screensaver.GET":
                setResultData(describe(c, p) + "\npage: " + (p.usesDownloadedPage(c) ? "downloaded" : "bundled in the APK")
                    + "\nvpn app: " + (VpnStatus.appInstalled(c) ? "yes (badge shown)" : "none (no VPN badge)"));
                return;
            case "com.raad.sauditv.screensaver.COUNTRIES": {
                StringBuilder b = new StringBuilder();
                for (Geo.Country x : Geo.countries(c)) b.append(x.cc).append(' ').append(x.ar).append(" (").append(x.en).append(")\n");
                setResultData(b.toString().trim());
                return;
            }
            case "com.raad.sauditv.screensaver.LEAGUES": {
                StringBuilder b = new StringBuilder("national teams: " + Leagues.NATIONAL + "\n");
                for (Leagues.Group g : Leagues.GROUPS) {
                    b.append(g.cc).append(' ').append(g.ar).append(": ");
                    for (Leagues.League l : g.leagues) b.append(l.id).append(' ').append(l.ar).append(", ");
                    b.setLength(b.length() - 2);
                    b.append('\n');
                }
                setResultData(b.toString().trim());
                return;
            }
            case "com.raad.sauditv.screensaver.CITIES": {
                String of = i.getStringExtra("country");   // a country's biggest bundled cities (filter: a part of the name)
                if (of != null && !of.equalsIgnoreCase("SA")) {
                    String want = Geo.fold(i.getStringExtra("filter"));
                    StringBuilder b = new StringBuilder();
                    int n = 0;
                    for (Geo.City x : Geo.cities(c, of)) {
                        if (!want.isEmpty() && !Geo.fold(x.name).contains(want) && !Geo.fold(x.alt).contains(want)) continue;
                        b.append(x.name).append(x.alt.isEmpty() ? "" : " (" + x.alt + ")").append(x.region.isEmpty() ? "" : " · " + x.region)
                         .append(": ").append(x.lat).append(", ").append(x.lon).append(' ').append(x.tz).append('\n');
                        if (++n >= 40) break;
                    }
                    setResultData(n == 0 ? "no city (country " + of + ")" : b.toString().trim());
                    return;
                }
                StringBuilder b = new StringBuilder();
                for (Cities.Region r : Cities.REGIONS) {
                    b.append(r.name).append(": ");
                    for (String[] city : r.cities) b.append(city[0]).append("، ");
                    b.setLength(b.length() - 2);
                    b.append('\n');
                }
                setResultData(b.toString().trim());
                return;
            }
            case "com.raad.sauditv.screensaver.UPDATE": {
                final PendingResult pending = goAsync();   // a download: off the main thread
                final Context app = c.getApplicationContext();
                new Thread(() -> {
                    pending.setResultData("update: " + Updater.check(app, true) + "\nsource: " + Updater.URL_PAGE
                        + "\npage: " + (new Prefs(app).usesDownloadedPage(app) ? "downloaded" : "bundled in the APK"));
                    pending.finish();
                }).start();
                return;
            }
            case "com.raad.sauditv.screensaver.APP_CHECK": {
                final PendingResult pending = goAsync();
                final Context app = c.getApplicationContext();
                new Thread(() -> {
                    AppUpdate.Result r = AppUpdate.check(app, true);
                    String installed = "screensaver " + AppUpdate.installed(app, app.getPackageName())
                        + ", home " + AppUpdate.installed(app, AppUpdate.HOME_PKG);
                    pending.setResultData("app update: " + r.message + "\ninstalled: " + installed
                        + (r.manifest != null ? "\navailable: " + r.manifest.versionCode : "")
                        + "\nsource: " + AppUpdate.sp(app).getString("source", "-"));
                    pending.finish();
                }).start();
                return;
            }
            case "com.raad.sauditv.screensaver.SET":
                setResultData(set(c, p, i));
                return;
            default:
                setResultData("error: unknown action " + action);
        }
    }

    private static String set(Context c, Prefs p, Intent i) {
        // check everything first, then save: a typo never leaves half the settings changed
        String cc = i.getStringExtra("country");   // ISO code; "" or none: not set (as older builds)
        if (cc != null) {
            cc = cc.trim().toUpperCase(java.util.Locale.ROOT);
            if (cc.equals("NONE")) cc = "";
            if (!cc.isEmpty() && Geo.country(c, cc) == null) return "error: country is a code of the bundled list (optimize/saver.sh countries), or none";
        }
        String place = cc != null ? cc : p.country();   // where a city name is looked up
        String tz = i.getStringExtra("tz");   // the city's time zone (with lat/lon)
        if (tz != null && !tz.isEmpty() && !Arrays.asList(java.util.TimeZone.getAvailableIDs()).contains(tz))
            return "error: tz is an IANA time zone, e.g. Africa/Cairo";
        String[] city = null;
        String cityCc = null, cityTz = null;
        String name = i.getStringExtra("city"), lat = i.getStringExtra("lat"), lon = i.getStringExtra("lon");
        if (name != null) {
            if (lat != null && lon != null) {
                if (!lat.matches("-?\\d{1,2}(\\.\\d+)?") || !lon.matches("-?\\d{1,3}(\\.\\d+)?"))
                    return "error: lat/lon must be numbers like 25.86 / 43.49";
                city = new String[] {name, lat, lon};
                cityCc = place;
                cityTz = tz;
                if ((cityTz == null || cityTz.isEmpty()) && !place.isEmpty()) {   // the nearest bundled city's
                    Geo.City near = Geo.nearest(c, place, Double.parseDouble(lat), Double.parseDouble(lon));
                    cityTz = near == null ? "" : near.tz;
                }
            } else {
                // a Saudi city of the built-in list (the country Saudi Arabia or not set), else the country's bundled one
                if (place.isEmpty() || place.equals("SA"))
                    for (Cities.Region r : Cities.REGIONS) for (String[] x : r.cities) if (x[0].equals(name)) { city = x; cityCc = "SA"; cityTz = Prefs.TZ; }
                if (city == null && !place.isEmpty()) {
                    Geo.City x = Geo.find(c, place, name);
                    if (x != null) { city = new String[] {x.name, x.lat, x.lon}; cityCc = place; cityTz = x.tz; }
                }
                if (city == null) return "error: city not in the " + (place.isEmpty() ? "built-in Saudi" : place + " bundled")
                    + " list; give --es lat and --es lon too (CITIES lists them)";
            }
        }
        String method = i.getStringExtra("method");   // auto, a number or a name (Geo.METHODS)
        int methodN = 0;
        if (method != null) {
            methodN = "auto".equals(method) ? -1 : Geo.method(method);
            if (methodN == -1 && !"auto".equals(method)) return "error: method is auto or one of " + methodNames();
        }
        String units = i.getStringExtra("units");
        if (units != null && !units.equals("auto") && !units.equals("c") && !units.equals("f")) return "error: units is auto, c or f";
        String league = i.getStringExtra("league");
        if (league != null && !league.equals("auto") && Leagues.find(league) == null) return "error: league is auto or one of " + leagueIds();
        String newsCc = i.getStringExtra("news_country");
        if (newsCc != null) {
            newsCc = newsCc.trim().toUpperCase(java.util.Locale.ROOT);
            if (!newsCc.equals("AUTO") && Geo.country(c, newsCc) == null) return "error: news_country is auto or a code of the bundled list (optimize/saver.sh countries)";
        }
        List<String> teams = null;
        String t = i.getStringExtra("teams");
        if (t != null) {
            teams = new ArrayList<>();
            for (String part : t.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                String[] kv = part.split(":", 2);
                if (!Leagues.validTeamId(kv[0]))
                    return "error: teams are id:EnglishName, e.g. 136013:Al-Hilal, E929@ksa.1:Al Hilal (ESPN) or 138995@tsdb.4829:Al Ahly";
                String en = kv.length > 1 ? kv[1].trim() : "";
                String known = en.isEmpty() ? null : Arabic.known(c, en);
                // older ids: as before (a transliteration when the name is not bundled); ESPN and league ids: English
                String ar = en.isEmpty() ? "#" + kv[0] : known != null ? known : kv[0].matches("\\d+") ? Arabic.team(c, en) : en;
                teams.add(kv[0] + "|" + ar.replace("|", " ").replace(";", " "));
            }
            if (teams.size() > Prefs.MAX_TEAMS) return "error: at most " + Prefs.MAX_TEAMS + " teams";
        }
        String panel = i.getStringExtra("panel"), home = i.getStringExtra("home_panel");
        // both cards can rotate through every panel (PANELS but "none": world and hadith too), comma-separated
        // (e.g. prayers,forecast,matches); "none" only on its own
        if (panel != null) {
            panel = panelList(panel);
            if (panel == null) return "error: panel is one, or up to " + (Prefs.PANELS.length - 1) + " comma-separated, of " + panels() + " (none only alone)";
        }
        if (home != null) {
            home = panelList(home);
            if (home == null) return "error: home_panel is one, or up to " + (Prefs.PANELS.length - 1) + " comma-separated, of " + panels() + " (none only alone)";
        }
        String auto = i.getStringExtra("autoupdate");
        if (auto != null && !auto.equals("true") && !auto.equals("false")) return "error: autoupdate is true or false";
        String rating = i.getStringExtra("minrating");
        if (rating != null && !Arrays.asList(Prefs.MIN_RATINGS).contains(rating))
            return "error: minrating is one of " + Prefs.join(Arrays.asList(Prefs.MIN_RATINGS), " ");
        String numerals = i.getStringExtra("numerals");
        if (numerals != null && !numerals.equals("latn") && !numerals.equals("arab"))
            return "error: numerals is latn (0 1 2 3) or arab (٠ ١ ٢ ٣)";
        String shape = i.getStringExtra("tile_shape"), focus = i.getStringExtra("focus_style");
        if (shape != null && !known(Prefs.TILE_SHAPES, shape)) return "error: tile_shape is " + names(Prefs.TILE_SHAPES);
        if (focus != null && !known(Prefs.FOCUS_STYLES, focus)) return "error: focus_style is " + names(Prefs.FOCUS_STYLES);
        java.util.Map<String, List<String>> sources = new java.util.LinkedHashMap<>();
        for (String page : Prefs.NEWS_PAGES) {
            String raw = i.getStringExtra("src_" + page);
            if (raw == null) continue;
            List<String> ids = new ArrayList<>();
            // the country news: the list of the news country being set now, if any
            String[][] table = "news".equals(page) && newsCc != null ? Prefs.countryNews("AUTO".equals(newsCc)
                ? (cityCc != null && !cityCc.isEmpty() ? cityCc : place.isEmpty() ? "SA" : place) : newsCc) : p.sourcesOf(page);
            for (String s : raw.split(",")) {
                s = s.trim();
                if (s.isEmpty()) continue;
                boolean known = false;
                for (String[] x : table) known |= x[0].equals(s);
                if (!known) return "error: src_" + page + " is a comma list of " + names(table) + " (\"\" = the defaults)";
                if (!ids.contains(s)) ids.add(s);
            }
            sources.put(page, ids);
        }
        List<String> genres = codes(i.getStringExtra("genres"), Prefs.GENRES);
        List<String> langs = codes(i.getStringExtra("langs"), Prefs.LANGS);
        if (genres != null && genres.contains(null)) return "error: genres are " + names(Prefs.GENRES);
        if (langs != null && langs.contains(null)) return "error: langs are " + names(Prefs.LANGS);

        if (cc != null && city == null) p.setCountry(cc);
        if (city != null) p.setCity(city[0], city[1], city[2], cityCc, cityTz);
        else if (tz != null) p.setCityTz(tz);
        if (method != null) p.setMethod(methodN);
        if (units != null) p.setUnits(units);
        if (league != null) p.setLeague(league);
        if (teams != null) p.setList("teams", teams);
        if (newsCc != null) p.setNewsCountry("AUTO".equals(newsCc) ? null : newsCc);
        if (panel != null) p.setPanel(panel);
        if (home != null) p.setHomePanel(home);
        if (rating != null) p.setMinRating(rating);
        if (auto != null) p.setAutoUpdate(Boolean.parseBoolean(auto));
        if (genres != null) p.setList("genres", genres);
        if (langs != null) p.setList("langs", langs);
        if (numerals != null) p.setNumerals(numerals);
        if (shape != null) p.setTileShape(shape);
        if (focus != null) p.setFocusStyle(focus);
        for (java.util.Map.Entry<String, List<String>> e : sources.entrySet()) p.setSources(e.getKey(), e.getValue());
        // a home screen already in front shows the new settings now, not at its next resume
        c.sendBroadcast(new Intent(CHANGED).setPackage("com.raad.sauditv.home"));
        return "saved\n" + describe(c, p);
    }

    private static String describe(Context c, Prefs p) {
        String[] city = p.city();
        Geo.Country country = Geo.country(c, p.country()), news = Geo.country(c, p.newsCountry());
        Leagues.League lg = Leagues.find(p.league());
        return "city: " + city[0] + " (" + city[1] + ", " + city[2] + ")"
            + "\ncountry: " + (country == null ? "(not set)" : country.cc + " " + country.ar)
            + "\ncity_tz: " + (p.cityTz().isEmpty() ? "(the TV's, " + Prefs.timeZone() + ")" : p.cityTz())
            + "\nmethod: " + p.prayerMethod() + " " + Geo.methodLabel(p.prayerMethod()) + (p.methodAuto() ? " (auto)" : "")
            + "\nunits: " + p.units() + (p.unitsAuto() ? " (auto)" : "")
            + "\nleague: " + p.league() + (lg == null ? "" : " " + lg.ar) + (p.leagueAuto() ? " (auto)" : "")
            + "\nnews_country: " + p.newsCountry() + (news == null ? "" : " " + news.ar) + (p.newsCountryAuto() ? " (auto)" : "")
            + "\nteams: " + Prefs.join(p.teams(), ", ")
            + "\npanel: " + p.panel() + "\nhome_panel: " + p.homePanel()
            + "\nminrating: " + p.minRating()
            + "\ngenres: " + Prefs.join(p.list("genres"), ",") + "\nlangs: " + Prefs.join(p.list("langs"), ",")
            + "\nautoupdate: " + p.autoUpdate()
            + "\nnumerals: " + p.numerals()
            + "\ntile_shape: " + p.tileShape() + "\nfocus_style: " + p.focusStyle()
            + sourcesLine(p);
    }

    private static boolean known(String[][] table, String v) {
        for (String[] x : table) if (x[0].equals(v)) return true;
        return false;
    }

    /** "\nsources_news: aawsat,okaz (default)" for each news page */
    private static String sourcesLine(Prefs p) {
        StringBuilder b = new StringBuilder();
        for (String page : Prefs.NEWS_PAGES)
            b.append("\nsources_").append(page).append(": ").append(Prefs.join(p.sources(page), ","))
             .append(p.raw().contains("src_" + page) ? "" : " (default)");
        return b.toString();
    }

    private static boolean isPanel(String v) {
        for (String[] x : Prefs.PANELS) if (x[0].equals(v)) return true;
        return false;
    }

    /** "a, b,c" -> "a,b,c" when every id is a panel, at most 9 (every card: PANELS but "none"), and "none" only alone; else null. */
    private static String panelList(String raw) {
        String[] parts = raw.split(",", -1);
        if (parts.length > Prefs.PANELS.length - 1) return null;
        List<String> out = new ArrayList<>();
        for (String s : parts) {
            s = s.trim();
            if (!isPanel(s)) return null;
            if (parts.length > 1 && "none".equals(s)) return null;
            out.add(s);
        }
        return Prefs.join(out, ",");
    }

    private static String panels() {
        return names(Prefs.PANELS);
    }

    private static String methodNames() {
        List<String> out = new ArrayList<>();
        for (String[] x : Geo.METHODS) out.add(x[2] + " (" + x[0] + ")");
        return Prefs.join(out, " ");
    }

    private static String leagueIds() {
        List<String> out = new ArrayList<>();
        for (Leagues.Group g : Leagues.GROUPS) for (Leagues.League l : g.leagues) out.add(l.id);
        return Prefs.join(out, " ");
    }

    private static String names(String[][] table) {
        List<String> out = new ArrayList<>();
        for (String[] x : table) out.add(x[0]);
        return Prefs.join(out, " ");
    }

    /** "Drama,Comedy" -> the same codes, checked against the table; null entry = unknown; "" = all. */
    private static List<String> codes(String raw, String[][] table) {
        if (raw == null) return null;
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            String hit = null;
            for (String[] x : table) if (x[0].equalsIgnoreCase(s)) hit = x[0];
            out.add(hit);
        }
        return out;
    }
}
