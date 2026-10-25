package com.raad.sauditv.screensaver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A league's table now, in rank order, as {followed-team id, English name}: ESPN's standings (no key) for its slugs,
 * TheSportsDB's lookuptable.php (its current season, else the one before; the free key gives the top 5) for a tsdb
 * league, as the page reads them (loadStandingsNow). For CountryChange's top 3. Blocking: off the main thread.
 */
final class TopClubs {
    private TopClubs() {}

    private static final String TSDB = "https://www.thesportsdb.com/api/v1/json/123/";

    static List<String[]> table(Leagues.League l) throws Exception {
        return l.espn() ? espn(l) : tsdb(l);
    }

    private static List<String[]> espn(Leagues.League l) throws Exception {
        JSONObject j = new JSONObject(get("https://site.api.espn.com/apis/v2/sports/soccer/" + l.id + "/standings"));
        JSONArray kids = j.optJSONArray("children");
        JSONObject st = kids != null && kids.length() > 0 ? kids.getJSONObject(0).optJSONObject("standings") : j.optJSONObject("standings");
        JSONArray es = st == null ? null : st.optJSONArray("entries");
        final List<Object[]> rows = new ArrayList<>();   // rank, id, name
        for (int i = 0; es != null && i < es.length(); i++) {
            JSONObject e = es.getJSONObject(i), t = e.optJSONObject("team");
            if (t == null || t.optString("id").isEmpty()) continue;
            double rank = 999;
            JSONArray stats = e.optJSONArray("stats");
            for (int k = 0; stats != null && k < stats.length(); k++) {
                JSONObject s = stats.getJSONObject(k);
                if ("rank".equals(s.optString("name"))) rank = s.optDouble("value", 999);
            }
            rows.add(new Object[] {rank, "E" + t.optString("id") + "@" + l.id, t.optString("displayName", t.optString("name"))});
        }
        return sorted(rows);
    }

    private static List<String[]> tsdb(Leagues.League l) throws Exception {
        JSONArray ls = new JSONObject(get(TSDB + "lookupleague.php?id=" + l.tsdb())).optJSONArray("leagues");
        String season = ls == null || ls.length() == 0 ? "" : ls.getJSONObject(0).optString("strCurrentSeason", "");
        List<String> seasons = new ArrayList<>();
        if (!season.isEmpty()) seasons.add(season);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{4})-(\\d{4})$").matcher(season);
        if (m.find()) seasons.add((Integer.parseInt(m.group(1)) - 1) + "-" + (Integer.parseInt(m.group(2)) - 1));
        else if (season.matches("\\d{4}")) seasons.add(String.valueOf(Integer.parseInt(season) - 1));
        for (String s : seasons) {
            JSONArray t = new JSONObject(get(TSDB + "lookuptable.php?l=" + l.tsdb() + "&s=" + android.net.Uri.encode(s))).optJSONArray("table");
            List<Object[]> rows = new ArrayList<>();
            for (int i = 0; t != null && i < t.length(); i++) {
                JSONObject x = t.getJSONObject(i);
                if (x.optString("idTeam").isEmpty()) continue;
                rows.add(new Object[] {x.optDouble("intRank", 999), x.optString("idTeam") + "@" + l.id, x.optString("strTeam")});
            }
            if (!rows.isEmpty()) return sorted(rows);
        }
        return new ArrayList<>();
    }

    private static List<String[]> sorted(List<Object[]> rows) {
        Collections.sort(rows, (a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        List<String[]> out = new ArrayList<>();
        for (Object[] r : rows) out.add(new String[] {(String) r[1], (String) r[2]});
        return out;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            if (con.getResponseCode() != 200) throw new java.io.IOException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } finally {
            con.disconnect();
        }
    }
}
