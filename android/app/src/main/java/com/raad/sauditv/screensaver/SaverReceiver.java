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
 *   am broadcast -n com.raad.sauditv.screensaver/.SaverReceiver -a com.raad.sauditv.screensaver.SET
 *       [--es city 'الرس'] [--es lat 25.86 --es lon 43.49] [--es teams '136013:Al-Hilal,150643:Al-Zulfi']
 *       [--es panel prayers | prayers,forecast,news,local,world,verse,hadith] [--es home_panel none] [--es minrating 7]
 *       [--es genres Drama,Comedy] [--es langs English] [--es autoupdate true|false] [--es numerals latn|arab]
 *       [--es src_news aawsat,okaz] [--es src_tech …] [--es src_local …] [--es src_world …]   ("" = the page's defaults)
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
                setResultData(describe(p) + "\npage: " + (p.usesDownloadedPage(c) ? "downloaded" : "bundled in the APK")
                    + "\nvpn app: " + (VpnStatus.appInstalled(c) ? "yes (badge shown)" : "none (no VPN badge)"));
                return;
            case "com.raad.sauditv.screensaver.CITIES": {
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
        String[] city = null;
        String name = i.getStringExtra("city"), lat = i.getStringExtra("lat"), lon = i.getStringExtra("lon");
        if (name != null) {
            if (lat != null && lon != null) {
                if (!lat.matches("-?\\d{1,2}(\\.\\d+)?") || !lon.matches("-?\\d{1,3}(\\.\\d+)?"))
                    return "error: lat/lon must be numbers like 25.86 / 43.49";
                city = new String[] {name, lat, lon};
            } else {
                for (Cities.Region r : Cities.REGIONS) for (String[] x : r.cities) if (x[0].equals(name)) city = x;
                if (city == null) return "error: city not in the built-in list; give --es lat and --es lon too (CITIES lists them)";
            }
        }
        List<String> teams = null;
        String t = i.getStringExtra("teams");
        if (t != null) {
            teams = new ArrayList<>();
            for (String part : t.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                String[] kv = part.split(":", 2);
                if (!kv[0].matches("\\d+")) return "error: teams are id:EnglishName, e.g. 136013:Al-Hilal";
                String en = kv.length > 1 ? kv[1].trim() : "";
                String ar = en.isEmpty() ? "#" + kv[0] : Arabic.team(c, en);
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
        java.util.Map<String, List<String>> sources = new java.util.LinkedHashMap<>();
        for (String page : Prefs.NEWS_PAGES) {
            String raw = i.getStringExtra("src_" + page);
            if (raw == null) continue;
            List<String> ids = new ArrayList<>();
            for (String s : raw.split(",")) {
                s = s.trim();
                if (s.isEmpty()) continue;
                boolean known = false;
                for (String[] x : Prefs.newsSources(page)) known |= x[0].equals(s);
                if (!known) return "error: src_" + page + " is a comma list of " + names(Prefs.newsSources(page)) + " (\"\" = the defaults)";
                if (!ids.contains(s)) ids.add(s);
            }
            sources.put(page, ids);
        }
        List<String> genres = codes(i.getStringExtra("genres"), Prefs.GENRES);
        List<String> langs = codes(i.getStringExtra("langs"), Prefs.LANGS);
        if (genres != null && genres.contains(null)) return "error: genres are " + names(Prefs.GENRES);
        if (langs != null && langs.contains(null)) return "error: langs are " + names(Prefs.LANGS);

        if (city != null) p.setCity(city[0], city[1], city[2]);
        if (teams != null) p.setList("teams", teams);
        if (panel != null) p.setPanel(panel);
        if (home != null) p.setHomePanel(home);
        if (rating != null) p.setMinRating(rating);
        if (auto != null) p.setAutoUpdate(Boolean.parseBoolean(auto));
        if (genres != null) p.setList("genres", genres);
        if (langs != null) p.setList("langs", langs);
        if (numerals != null) p.setNumerals(numerals);
        for (java.util.Map.Entry<String, List<String>> e : sources.entrySet()) p.setSources(e.getKey(), e.getValue());
        // a home screen already in front shows the new settings now, not at its next resume
        c.sendBroadcast(new Intent(CHANGED).setPackage("com.raad.sauditv.home"));
        return "saved\n" + describe(p);
    }

    private static String describe(Prefs p) {
        String[] city = p.city();
        return "city: " + city[0] + " (" + city[1] + ", " + city[2] + ")"
            + "\nteams: " + Prefs.join(p.teams(), ", ")
            + "\npanel: " + p.panel() + "\nhome_panel: " + p.homePanel()
            + "\nminrating: " + p.minRating()
            + "\ngenres: " + Prefs.join(p.list("genres"), ",") + "\nlangs: " + Prefs.join(p.list("langs"), ",")
            + "\nautoupdate: " + p.autoUpdate()
            + "\nnumerals: " + p.numerals()
            + sourcesLine(p);
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
