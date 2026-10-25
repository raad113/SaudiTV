package com.raad.sauditv.screensaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

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
 * The settings' pickers that more than one screen opens: the place («المدينة»: the country, its cities, a typed name or
 * a search) and «إضافة فريق» (a country's leagues, their clubs, a search by name), with the small dialog helpers they
 * use. SettingsActivity and SetupActivity (the first-run wizard) each hold one; refresh runs after a choice is saved
 * (the screen shows the new value), and the home screen is told (CONFIG_CHANGED) as before.
 */
final class Pickers {
    static final int DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert;

    private final Activity a;
    private final Prefs prefs;
    private final Runnable refresh;
    private final Handler ui = new Handler(Looper.getMainLooper());

    Pickers(Activity a, Prefs prefs, Runnable refresh) {
        this.a = a;
        this.prefs = prefs;
        this.refresh = refresh;
    }

    /** every dialog here takes the mouse too (Mouse.dialog: hover = the D-pad's focus, the wheel = ▲▼, right-click = Back) */
    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(a, DIALOG_THEME) {
            @Override public AlertDialog create() { AlertDialog d = super.create(); Mouse.dialog(d); return d; }
        };
    }

    /** a home screen in front (behind a dialog) shows the new settings now, as after saver.sh */
    void changed() {
        a.sendBroadcast(new android.content.Intent(SaverReceiver.CHANGED).setPackage("com.raad.sauditv.home"));
    }

    void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); }

    /** a number in the chosen digits («شكل الأرقام») */
    private String num(long n) { return Prefs.digits(a, n); }

    // ---------- the place: the country (every one, CLDR's Arabic names), then its cities (GeoNames, biggest first; the
    // Saudi ones from the regions list, as before). The city keeps its country and its time zone.

    // The bundled lists are read off the main thread (loadThen): the US alone has 16,000 cities, read and listed on it
    // they froze this screen («… isn't responding»). A country's list shows its biggest Geo.TOP cities; the search
    // («بحث في مدن …») looks through all of them, also off the main thread.
    void pickCountry() {
        final android.content.Context app = a.getApplicationContext();
        loadThen(() -> Geo.countries(app), this::showCountries);
    }

    private void showCountries(final List<Geo.Country> all) {
        if (all == null || all.isEmpty()) { pickCity(); return; }   // no bundled list (an incomplete build): the Saudi regions
        final int head = 1;   // first «اكتب اسم مدينتك» (any country's city by its name), then the countries
        String[] labels = new String[all.size() + head];
        labels[0] = "اكتب اسم مدينتك";
        int sel = -1;
        String cur = prefs.country().isEmpty() ? "SA" : prefs.country();
        for (int i = 0; i < all.size(); i++) {
            labels[i + head] = all.get(i).ar;
            if (all.get(i).cc.equals(cur)) sel = i + head;
        }
        AlertDialog d = dialog().setTitle("الدولة")
            .setItems(labels, (x, i) -> { if (i < head) typeCity(); else pickCityOf(all.get(i - head)); }).create();
        d.show();
        if (sel >= 0) d.getListView().setSelection(sel);   // the current country in front
    }

    /** «اكتب اسم مدينتك»: a city of any country by its Arabic or English name (Geo.searchAll, off the main thread);
     *  picking one sets its country too, as picking it from the country's list does */
    private void typeCity() {
        final android.content.Context app = a.getApplicationContext();
        new Thread(() -> Geo.preloadNames(app), "geo").start();   // every country's cities read while the name is typed
        ask("اسم مدينتك بالعربية أو الإنجليزية", q -> {
            final long t0 = android.os.SystemClock.uptimeMillis();
            loadThen(() -> placesFor(app, q), f -> showPlaces(f, q, t0));
        });
    }

    /** the cities a search found and their rows */
    private static final class Found {
        final List<Geo.Place> places = new ArrayList<>();
        final List<String> rows = new ArrayList<>();
    }

    /** the rows «المدينة، الدولة», with the region between when two found share a name («…، إلينوي، الولايات المتحدة») */
    private static Found placesFor(android.content.Context app, String q) {
        Found f = new Found();
        f.places.addAll(Geo.searchAll(app, q));
        java.util.Map<String, Integer> same = new java.util.HashMap<>();
        for (Geo.Place p : f.places) { Integer n = same.get(p.name); same.put(p.name, n == null ? 1 : n + 1); }
        for (Geo.Place p : f.places) {
            Geo.Country k = Geo.country(app, p.cc);
            String region = "";
            if (same.get(p.name) > 1) {
                Geo.City x = Geo.cityOf(app, p);
                if (x != null && !x.region.isEmpty() && !Geo.key(x.region).equals(Geo.key(p.name))) region = x.region + "، ";
            }
            f.rows.add("‏" + p.name + "، " + region + (k == null ? p.cc : k.shortAr));   // an RLM first: a Latin name on the right too
        }
        return f;
    }

    private void showPlaces(final Found f, final String q, final long t0) {
        if (f == null || f.places.isEmpty()) { toast("لا توجد نتائج، جرّب اسماً آخر أو اختر الدولة"); return; }
        final android.content.Context app = a.getApplicationContext();
        AlertDialog d = dialog().setTitle("«⁨" + q + "⁩»")   // the query isolated (FSI … PDI)
            .setItems(f.rows.toArray(new String[0]), (x, i) -> {
                final Geo.Place p = f.places.get(i);
                loadThen(() -> Geo.cityOf(app, p), city -> {
                    if (city == null) toast("تعذّر قراءة المدينة");
                    else setPlace(city.name, city.lat, city.lon, p.cc, city.tz);
                });
            }).create();
        d.show();
        d.getListView().post(() -> android.util.Log.i("SaudiTV-Settings", "places «" + q + "» (" + f.places.size()
            + ") shown in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms"));
    }

    private void pickCityOf(final Geo.Country c) {
        if ("SA".equals(c.cc)) { pickCity(); return; }
        final android.content.Context app = a.getApplicationContext();
        final long t0 = android.os.SystemClock.uptimeMillis();
        loadThen(() -> Geo.top(Geo.cities(app, c.cc), Geo.TOP), l -> showCities(c, l, null, t0));
    }

    /** a country's biggest cities, or those a search kept (query); first a search, then «ابحث عن مدينة أخرى». t0: when
     *  the country was picked (the time to the list shown goes to the log, tag SaudiTV-Settings), 0 for none */
    private void showCities(final Geo.Country c, final List<Geo.City> found, final String query, final long t0) {
        final List<Geo.City> list = found == null ? new ArrayList<Geo.City>() : found;
        final android.content.Context app = a.getApplicationContext();
        final int head = 2;
        String[] labels = new String[list.size() + head];
        labels[0] = query == null ? "بحث في مدن " + c.shortAr + "…" : "بحث آخر…";
        labels[1] = "ابحث عن مدينة أخرى";
        int sel = -1;
        String[] cur = prefs.city();
        for (int i = 0; i < list.size(); i++) {
            Geo.City x = list.get(i);
            // the region beside the name, unless it is the same place (القاهرة, الإسكندرية · الاسكندرية); an RLM first, so a
            // Latin name keeps the right edge like the Arabic ones
            String rg = x.region.isEmpty() || Geo.fold(x.region).equals(Geo.fold(x.name)) ? "" : "  ·  " + x.region;
            labels[i + head] = "‏" + x.name + rg;
            if (x.name.equals(cur[0]) && x.lat.equals(cur[1]) && x.lon.equals(cur[2])) sel = i + head;
        }
        if (list.isEmpty() && query != null) toast("لا توجد نتائج، جرّب «ابحث عن مدينة أخرى»");
        AlertDialog d = dialog()
            .setTitle(query == null ? c.ar : c.shortAr + ": «⁨" + query + "⁩»")   // the query isolated (FSI … PDI)
            .setItems(labels, (x, i) -> {
                if (i == 0) ask("اسم المدينة بالعربية أو الإنجليزية",
                    q -> loadThen(() -> Geo.search(app, c.cc, q), l -> showCities(c, l, q, 0)));
                else if (i == 1) searchCity(c);
                else {
                    Geo.City city = list.get(i - head);
                    setPlace(city.name, city.lat, city.lon, c.cc, city.tz);
                }
            }).create();
        d.show();
        if (sel >= 0) d.getListView().setSelection(sel);
        if (t0 > 0) d.getListView().post(() -> android.util.Log.i("SaudiTV-Settings", "cities " + c.cc + " shown in "
            + (android.os.SystemClock.uptimeMillis() - t0) + " ms"));
    }

    interface Got<T> { void got(T v); }

    /** work off the main thread, then done with its answer (null when it failed) on it; «جارٍ التحميل…» shows when it
     *  takes more than 150 ms (Back there drops the answer) */
    <T> void loadThen(final java.util.concurrent.Callable<T> work, final Got<T> done) {
        final AlertDialog[] wait = {null};
        final boolean[] over = {false};
        final Runnable showWait = () -> {
            if (over[0] || a.isFinishing()) return;
            wait[0] = dialog().setMessage("جارٍ التحميل…").setCancelable(true).show();
        };
        ui.postDelayed(showWait, 150);
        new Thread(() -> {
            T r;
            try { r = work.call(); } catch (Exception e) { r = null; }
            final T got = r;
            a.runOnUiThread(() -> {
                over[0] = true;
                ui.removeCallbacks(showWait);
                boolean dropped = wait[0] != null && !wait[0].isShowing();
                if (wait[0] != null) wait[0].dismiss();
                if (!a.isFinishing() && !dropped) done.got(got);
            });
        }, "geo").start();
    }

    /** any place of the country, from Open-Meteo's GeoNames search (no key), in Arabic where it has the name */
    private void searchCity(final Geo.Country c) {
        ask("اسم المدينة أو القرية", q -> fetch("https://geocoding-api.open-meteo.com/v1/search?count=20&language=ar&countryCode="
            + c.cc + "&name=" + Uri.encode(q), json -> {
                JSONArray a = new JSONObject(json).optJSONArray("results");
                if (a == null || a.length() == 0) { toast("لا توجد نتائج"); return; }
                final int n = a.length();
                final String[] names = new String[n], lats = new String[n], lons = new String[n], zones = new String[n], rows = new String[n];
                for (int i = 0; i < n; i++) {
                    JSONObject x = a.getJSONObject(i);
                    names[i] = x.optString("name");
                    lats[i] = String.valueOf(x.optDouble("latitude"));
                    lons[i] = String.valueOf(x.optDouble("longitude"));
                    zones[i] = x.optString("timezone", "");
                    String region = x.optString("admin1", "");
                    rows[i] = region.isEmpty() ? names[i] : names[i] + "  ·  " + region;
                }
                dialog().setTitle("اختر المكان")
                    .setItems(rows, (d, i) -> setPlace(names[i], lats[i], lons[i], c.cc, zones[i])).show();
            }));
    }

    /** the city, its country and time zone (none known: the nearest bundled city's) */
    private void setPlace(final String name, final String lat, final String lon, final String cc, String tz) {
        if (tz == null || tz.isEmpty()) {   // the nearest bundled city's (reads the country's list: off the main thread)
            final android.content.Context app = a.getApplicationContext();
            loadThen(() -> {
                Geo.City near = Geo.nearest(app, cc, Double.parseDouble(lat), Double.parseDouble(lon));
                return near == null ? "" : near.tz;
            }, z -> savePlace(name, lat, lon, cc, z == null ? "" : z));
            return;
        }
        savePlace(name, lat, lon, cc, tz);
    }

    /** the city as picked (PlaceApply: a change of country brings its news, league and clubs) */
    private void savePlace(String name, String lat, String lon, String cc, String tz) {
        String note = PlaceApply.save(a, prefs, name, lat, lon, cc, tz, n -> a.runOnUiThread(() -> {
            changed();
            if (a.isFinishing()) return;
            refresh.run();
            toast(n);
        }));
        if (note != null) toast(note);
        changed();
        refresh.run();
    }

    String cityLabel() {
        Geo.Country c = Geo.country(a, prefs.country());
        String name = prefs.city()[0];
        return c == null || name.equals(c.shortAr) ? name : name + "، " + c.shortAr;
    }

    /** the leagues' countries, the TV's own first (then the list's order) */
    static List<Leagues.Group> groupsFirst(String cc, boolean national) {
        List<Leagues.Group> out = new ArrayList<>();
        for (Leagues.Group g : Leagues.GROUPS) if (!cc.isEmpty() && (g.cc.equals(cc) || g.cc.startsWith(cc + "-"))) out.add(g);
        for (Leagues.Group g : Leagues.GROUPS) if (!out.contains(g)) out.add(g);
        return out;
    }

    private void pickCity() {
        final Cities.Region[] regions = Cities.REGIONS;
        String[] labels = new String[regions.length + 1];
        labels[0] = "بحث عن مدينة أو قرية…";
        for (int i = 0; i < regions.length; i++) labels[i + 1] = regions[i].name + "  (" + regions[i].cities.length + ")";
        dialog().setTitle("المنطقة")
            .setItems(labels, (d, i) -> { if (i == 0) searchCity(); else pickCityIn(regions[i - 1]); })
            .show();
    }

    private void pickCityIn(final Cities.Region r) {
        String[] names = new String[r.cities.length];
        int sel = -1;
        for (int i = 0; i < names.length; i++) {
            names[i] = r.cities[i][0];
            if (names[i].equals(prefs.city()[0])) sel = i;
        }
        dialog().setTitle(r.name)
            .setSingleChoiceItems(names, sel, (d, i) -> {
                d.dismiss();
                savePlace(r.cities[i][0], r.cities[i][1], r.cities[i][2], "SA", Prefs.TZ);   // a Saudi city: Riyadh time
            }).show();
    }

    // any place in Saudi Arabia, from Open-Meteo's GeoNames search
    private void searchCity() {
        ask("اسم المدينة أو القرية", q -> fetch("https://geocoding-api.open-meteo.com/v1/search?count=20&language=ar&countryCode=SA&name="
            + Uri.encode(q), json -> {
                JSONArray a = new JSONObject(json).optJSONArray("results");
                if (a == null || a.length() == 0) { toast("لا توجد نتائج"); return; }
                final int n = a.length();
                final String[] names = new String[n], lats = new String[n], lons = new String[n], rows = new String[n];
                for (int i = 0; i < n; i++) {
                    JSONObject c = a.getJSONObject(i);
                    names[i] = c.optString("name");
                    lats[i] = String.valueOf(c.optDouble("latitude"));
                    lons[i] = String.valueOf(c.optDouble("longitude"));
                    String region = c.optString("admin1", "");
                    rows[i] = region.isEmpty() ? names[i] : names[i] + "  ·  " + region;
                }
                dialog().setTitle("اختر المكان")
                    .setItems(rows, (d, i) -> savePlace(names[i], lats[i], lons[i], "SA", Prefs.TZ)).show();
            }));
    }

    private void addTeam(String id, String name) {
        if (prefs.addTeam(id, name)) toast("تمت متابعة " + name);
        else toast(prefs.teams().size() >= Prefs.MAX_TEAMS ? "الحد الأعلى " + num(Prefs.MAX_TEAMS) + " فرق" : "متابَع مسبقاً");
        refresh.run();
    }

    /** «إضافة فريق»: the country (or «منتخبات»), its league, then the club (ESPN's lists; TheSportsDB's for the leagues
     *  ESPN does not carry); the search by name (TheSportsDB) last, as before */
    void pickTeam(final int slot) {
        final List<Leagues.Group> groups = groupsFirst(prefs.country(), true);
        String[] labels = new String[groups.size() + 2];
        labels[0] = "منتخبات";
        for (int i = 0; i < groups.size(); i++) labels[i + 1] = groups.get(i).ar;
        labels[labels.length - 1] = "بحث عن أي فريق أو منتخب…";
        AlertDialog d = dialog().setTitle("إضافة فريق")
            .setItems(labels, (x, i) -> {
                if (i == 0) espnTeams(Leagues.NATIONAL, "منتخبات", true);
                else if (i == labels.length - 1) searchTeam(slot);
                else pickLeagueOf(slot, groups.get(i - 1));
            }).create();
        d.show();
        String cc = prefs.country();
        if (!cc.isEmpty() && !groups.isEmpty() && (groups.get(0).cc.equals(cc) || groups.get(0).cc.startsWith(cc + "-")))
            d.getListView().setSelection(1);   // the country's own clubs in front, as its leagues are first
    }

    private void pickLeagueOf(final int slot, final Leagues.Group g) {
        if (g.leagues.length == 1) { clubsOf(slot, g.leagues[0]); return; }
        String[] names = new String[g.leagues.length];
        for (int i = 0; i < names.length; i++) names[i] = g.leagues[i].ar;
        dialog().setTitle(g.ar)
            .setItems(names, (d, i) -> clubsOf(slot, g.leagues[i])).show();
    }

    private void clubsOf(int slot, Leagues.League l) {
        if (l.espn()) espnTeams(l.id, l.ar, false); else tsdbTeams(slot, l);
    }

    /** a club's (or a national team's) name: the bundled Arabic one (teams-ar.json; a country's for a national team),
     *  else ESPN's English one */
    private String clubName(String en, boolean national) { return clubName(a, en, national); }

    private static String clubName(android.content.Context c, String en, boolean national) {
        return PlaceApply.clubName(c, en, national);
    }

    /** the teams of an ESPN league (site.api.espn.com, no key), by name; followed as E<id>@<league> */
    private void espnTeams(final String slug, final String title, final boolean national) {
        fetch("https://site.api.espn.com/apis/site/v2/sports/soccer/" + slug + "/teams", json -> {
            JSONArray sports = new JSONObject(json).optJSONArray("sports");
            JSONArray leagues = sports == null || sports.length() == 0 ? null : sports.getJSONObject(0).optJSONArray("leagues");
            JSONArray teams = leagues == null || leagues.length() == 0 ? null : leagues.getJSONObject(0).optJSONArray("teams");
            if (teams == null || teams.length() == 0) { toast("تعذّر تحميل هذا الدوري"); return; }
            final List<String[]> rows = new ArrayList<>();   // id, name
            for (int i = 0; i < teams.length(); i++) {
                JSONObject t = teams.getJSONObject(i).optJSONObject("team");
                if (t == null || t.optString("id").isEmpty()) continue;
                String en = t.optString("displayName", t.optString("name"));
                rows.add(new String[] {"E" + t.optString("id") + "@" + slug, clubName(en, national)});
            }
            showClubs(title, rows, null);
        });
    }

    /** a TheSportsDB league's clubs (no ESPN list): its table (the top 5 with the free key) and its list (10 of them),
     *  then «بحث عن نادٍ آخر…»; followed as <id>@tsdb.<league> */
    private void tsdbTeams(final int slot, final Leagues.League l) {
        fetch(SPORTS + "lookupleague.php?id=" + l.tsdb(), json -> {
            JSONArray ls = new JSONObject(json).optJSONArray("leagues");
            final String season = ls == null || ls.length() == 0 ? "" : ls.getJSONObject(0).optString("strCurrentSeason", "");
            fetch(SPORTS + "lookuptable.php?l=" + l.tsdb() + "&s=" + Uri.encode(season), table -> fetch(SPORTS + "search_all_teams.php?l="
                + Uri.encode(l.en), list -> {
                    final List<String[]> rows = new ArrayList<>();
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (String[] src : new String[][] {{table, "table"}, {list, "teams"}}) {
                        JSONArray a = null;
                        try { a = new JSONObject(src[0]).optJSONArray(src[1]); } catch (Exception ignored) { }
                        for (int i = 0; a != null && i < a.length(); i++) {
                            JSONObject t = a.getJSONObject(i);
                            String id = t.optString("idTeam");
                            if (id.isEmpty() || !seen.add(id)) continue;
                            rows.add(new String[] {id + "@" + l.id, clubName(t.optString("strTeam"), false)});
                        }
                    }
                    if (rows.isEmpty()) { toast("تعذّر تحميل هذا الدوري، جرّب البحث عن الفريق"); return; }
                    showClubs(l.ar, rows, () -> searchTeam(slot));
                }));
        });
    }

    /** the clubs in Arabic order; more: a last row «بحث عن نادٍ آخر…» */
    private void showClubs(String title, final List<String[]> rows, final Runnable more) {
        final java.text.Collator col = java.text.Collator.getInstance(new java.util.Locale("ar"));
        Collections.sort(rows, (a, b) -> col.compare(a[1], b[1]));
        final int n = rows.size();
        String[] labels = new String[n + (more == null ? 0 : 1)];
        for (int i = 0; i < n; i++) labels[i] = "‏" + rows.get(i)[1];   // RLM: an English name keeps the right edge too
        if (more != null) labels[n] = "بحث عن نادٍ آخر…";
        dialog().setTitle(title)
            .setItems(labels, (d, i) -> { if (i < n) addTeam(rows.get(i)[0], rows.get(i)[1]); else more.run(); })
            .show();
    }

    private void searchTeam(final int slot) {
        ask("اسم الفريق بالإنجليزية (مثل Al-Nassr أو Real Madrid)",
            q -> fetch(SPORTS + "searchteams.php?t=" + Uri.encode(q), json -> showTeams(slot, json, "اختر الفريق", null)));
    }

    private static final String SPORTS = "https://www.thesportsdb.com/api/v1/json/123/";

    /** league != null: keep only clubs that really play in it, so a wrong API answer never shows. */
    private void showTeams(final int slot, String json, String title, String league) throws Exception {
                JSONArray all = new JSONObject(json).optJSONArray("teams");
                JSONArray a = new JSONArray();
                for (int i = 0; all != null && i < all.length(); i++) {
                    JSONObject t = all.getJSONObject(i);
                    boolean inLeague = league == null;
                    for (int k = 1; !inLeague && k <= 7; k++)
                        inLeague = league.equalsIgnoreCase(t.optString(k == 1 ? "strLeague" : "strLeague" + k));
                    if (inLeague) a.put(t);
                }
                for (String[] x : Prefs.LEAGUE_EXTRA) {   // clubs the listing misses
                    if (league == null || !league.equalsIgnoreCase(x[0])) continue;
                    boolean listed = false;
                    for (int i = 0; i < a.length(); i++) listed |= x[1].equals(a.getJSONObject(i).optString("idTeam"));
                    if (!listed) a.put(new JSONObject().put("idTeam", x[1]).put("strTeam", x[2]).put("strLeague", x[0]));
                }
                if (a.length() == 0) {
                    toast(league == null ? "لا توجد نتائج" : "تعذّر تحميل هذا الدوري، جرّب البحث عن الفريق");
                    return;
                }
                final int n = Math.min(a.length(), 60);
                final String[] ids = new String[n], names = new String[n], rows = new String[n];
                for (int i = 0; i < n; i++) {
                    JSONObject t = a.getJSONObject(i);
                    ids[i] = t.optString("idTeam");
                    // a club of a league the table can show (Leagues' TheSportsDB ones): its league with it
                    for (Leagues.Group g : Leagues.GROUPS) for (Leagues.League l : g.leagues)
                        if (!l.espn() && l.en.equalsIgnoreCase(t.optString("strLeague", "")) && ids[i].matches("\\d+")) ids[i] += "@" + l.id;
                    names[i] = t.optString("strTeam");
                    rows[i] = Arabic.team(this.a, names[i]) + "  ·  " + leagueAr(t.optString("strLeague", ""));
                }
                dialog().setTitle(title)
                    .setItems(rows, (d, i) -> inArabic(names[i], Arabic.TEAM, ar -> addTeam(ids[i], ar)))
                    .show();
    }

    /** Wikidata's Arabic name (or a transliteration) fetched off the UI thread. */
    private void inArabic(final String en, final java.util.regex.Pattern kind, final Text then) {
        toast("جارٍ جلب الاسم بالعربية…");
        new Thread(() -> {
            String k = kind == Arabic.TEAM ? Arabic.known(a.getApplicationContext(), en) : null;
            final String ar = k != null ? k : Arabic.lookup(en, kind);
            a.runOnUiThread(() -> { if (!a.isFinishing()) then.got(ar); });
        }).start();
    }

    private static String leagueAr(String en) {
        for (String[] l : Prefs.LEAGUES) if (l[0].equalsIgnoreCase(en)) return l[1];
        return Arabic.translit(en);
    }

    interface Text { void got(String s); }
    interface Json { void got(String json) throws Exception; }

    void ask(String hint, final Text then) {
        final EditText in = new EditText(a);
        in.setHint(hint);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        final AlertDialog d = dialog().setTitle("بحث").setView(in)
            .setPositiveButton("بحث", (x, w) -> {
                String q = in.getText().toString().trim();
                if (!q.isEmpty()) then.got(q);
            })
            .setNegativeButton("إلغاء", null).show();
        // the keyboard's search key (the TV's keyboard, or the phone remote's) does what «بحث» does
        in.setOnEditorActionListener((v, action, ev) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH && action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        in.requestFocus();
    }

    /** GET on a background thread, result handled on the UI thread. */
    private void fetch(final String url, final Json then) {
        toast("جارٍ البحث…");
        new Thread(() -> {
            String body = null;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(12000);
                con.setReadTimeout(15000);
                InputStream in = con.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                in.close();
                body = b.toString("UTF-8");
            } catch (Exception ignored) {
            } finally {
                if (con != null) con.disconnect();
            }
            final String result = body;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (result == null) { toast("لا يوجد اتصال"); return; }
                try { then.got(result); } catch (Exception e) { toast("تعذرت قراءة النتائج"); }
            });
        }).start();
    }
}
