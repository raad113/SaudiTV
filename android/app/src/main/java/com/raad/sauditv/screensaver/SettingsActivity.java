package com.raad.sauditv.screensaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
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

/** Remote-friendly settings: every row is a button that opens a list. */
public class SettingsActivity extends Activity {
    private static final int DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert;

    private Prefs prefs;
    private ScrollView scroll;
    private Mouse mouse;
    private LinearLayout list;
    // the row in focus (its key, rowKey) and the list's scroll: a render() puts both back, so choosing something in a
    // dialog, a list or a screen opened from a row comes back to that same row, not to the first one; kept across a
    // re-creation of this screen too (onSaveInstanceState)
    private String focusKey;
    private int focusIndex = -1, restoreScroll = -1;
    private final List<View> rows = new ArrayList<>();
    private View updateRow;   // «تحديث التطبيق»
    private View previewRow;   // «معاينة الإذاعة»
    private RadioHolder preview;   // the radio's preview, playing while this screen is in front
    private int countrySeq;   // the last change of country (its clubs arrive later)
    private final Runnable previewTick = new Runnable() {
        @Override public void run() {
            setRowValue(previewRow, previewLabel());
            if (preview != null && preview.isFront()) list.postDelayed(this, 1000);
        }
    };
    private String justUpdated;   // after this app updated itself: «تم التحديث إلى …» while this screen is open
    private AlertDialog progress;   // the update's progress (AppInstaller)
    private TextView progressText;
    private android.widget.ProgressBar progressBar;
    private final AppInstaller.Listener installListener = new AppInstaller.Listener() {
        @Override public void onStatus(AppInstaller.Status s) { onInstallStatus(s); }
        @Override public void homeReplaced() { restoreHome(); }
    };

    /** Arabic locale for this screen, so every dialog title, list and button is laid out right-to-left. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        android.content.res.Configuration c = new android.content.res.Configuration(base.getResources().getConfiguration());
        c.setLocale(new java.util.Locale("ar"));
        c.setLayoutDirection(new java.util.Locale("ar"));
        super.attachBaseContext(base.createConfigurationContext(c));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        UpdateJob.schedule(this);
        if (b != null) {
            focusKey = b.getString("focusKey");
            focusIndex = b.getInt("focusIndex", -1);
            restoreScroll = b.getInt("scrollY", -1);
        }
        scroll = new ScrollView(this);
        scroll.setBackground(new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF0A2016, 0xFF04120C, 0xFF020806}));
        scroll.setVerticalScrollBarEnabled(false);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        list.setPadding(dp(8), dp(36), dp(8), dp(48));
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(64), dp(860));
        frame.addView(list, new android.widget.FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL));
        scroll.addView(frame);
        setContentView(scroll);
        mouse = Mouse.attach(this, true);   // a mouse: hover = focus, the wheel = ▲▼, right-click = Back
        justUpdated = AppUpdate.takeJustUpdated(this);   // the first launch after a self-update
        if (justUpdated != null) toast("تم التحديث إلى " + AppUpdate.digits(this, justUpdated));
        render();
        AppInstaller.setListener(installListener);   // an update still running shows its progress again
        if (getIntent().getBooleanExtra("app_update", false)) openAppUpdate();   // the home screen's «تحديث متوفر»
        else quietCheck();
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        return (mouse != null && mouse.generic(e)) || super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        return (mouse != null && mouse.touch(e)) || super.dispatchTouchEvent(e);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra("app_update", false)) openAppUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumeAfterPermission();
    }

    @Override
    protected void onPause() {
        stopPreview();   // the preview only plays while these settings are in front
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("focusKey", focusKey);
        out.putInt("focusIndex", focusIndex);
        out.putInt("scrollY", scroll == null ? 0 : scroll.getScrollY());
    }

    @Override
    protected void onDestroy() {
        AppInstaller.clearListener(installListener);
        if (progress != null) progress.dismiss();
        super.onDestroy();
    }

    private void render() {
        final int y = restoreScroll >= 0 ? restoreScroll : scroll.getScrollY();
        restoreScroll = -1;
        final String want = focusKey;
        final int wantIndex = focusIndex;
        list.removeAllViews();
        rows.clear();
        TextView title = new TextView(this);
        title.setText("سعودي TV");
        title.setTextColor(0xFFF6EFE0);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(font("ThmanyahDisplay-Bold"));
        list.addView(title);
        TextView sub = new TextView(this);
        sub.setText("إعدادات شاشة التوقف");
        sub.setTextColor(0xFFC6BBA4);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sub.setTypeface(font("Thmanyah-Regular"));
        sub.setPadding(0, 0, 0, dp(18));
        list.addView(sub);

        View first = row("معاينة الشاشة", "شاهد الشاشة الآن", R.drawable.ic_play, new Runnable() { public void run() {
            startActivity(new Intent(SettingsActivity.this, MainActivity.class)); } });

        header("الموقع");
        row("المدينة", cityLabel(), R.drawable.ic_place, new Runnable() { public void run() { pickCountry(); } });
        row("طريقة الحساب", Geo.methodLabel(prefs.prayerMethod()) + (prefs.methodAuto() ? " (تلقائي)" : ""),
            R.drawable.ic_place, new Runnable() { public void run() { pickMethod(); } });
        row("وحدة الحرارة", unitsLabel(prefs.units()) + (prefs.unitsAuto() ? " (تلقائي)" : ""),
            R.drawable.ic_place, new Runnable() { public void run() { pickUnits(); } });

        header("المباريات");
        row("الفرق المتابعة (" + num(prefs.teams().size()) + "/" + num(Prefs.MAX_TEAMS) + ")", teamsLabel(),
            R.drawable.ic_trophy, new Runnable() { public void run() { manageTeams(); } });
        row("الدوري في الترتيب", leagueLabel(prefs.league()) + (prefs.leagueAuto() ? " (تلقائي)" : ""),
            R.drawable.ic_trophy, new Runnable() { public void run() { pickTableLeague(); } });

        header("بجانب الساعة");
        row("المحتوى", prefs.panelTitle(prefs.panel()), R.drawable.ic_eye, new Runnable() { public void run() { pickPanel(false); } });

        header("الشاشة الرئيسية (سعودي TV)");
        row("بطاقة على الشاشة الرئيسية", prefs.panelTitle(prefs.homePanel()), R.drawable.ic_eye,
            new Runnable() { public void run() { pickPanel(true); } });
        row("ترتيب التطبيقات المفضلة", "قائمة ترتّب فيها المفضلة بالأسهم", R.drawable.ic_star,
            new Runnable() { public void run() { openDockSettings(); } });
        row("شكل التطبيقات", Prefs.label(Prefs.TILE_SHAPES, prefs.tileShape()), R.drawable.ic_category,
            new Runnable() { public void run() { pickTileLook(true); } });
        // an RLM first: «Google TV» on the right like the other values, not on the left as a Latin line would be
        row("شكل التحديد", "‏" + Prefs.label(Prefs.FOCUS_STYLES, prefs.focusStyle()), R.drawable.ic_eye,
            new Runnable() { public void run() { pickTileLook(false); } });
        // the ticker tape (the home screen's and the screensaver's): its items in order, or each country's own
        row("عناصر شريط الأسعار", tapeLabel(), R.drawable.ic_category, new Runnable() { public void run() { pickTape(); } });

        // «إذاعة القرآن الكريم» (RadioService): on or off, the station, its volume, and a preview here
        header("إذاعة القرآن الكريم");
        row("إذاعة القرآن الكريم", prefs.radioOn() ? "تعمل على " + RadioWhen.label(prefs.radioWhen()) : "متوقفة", R.drawable.ic_radio,
            new Runnable() { public void run() { prefs.setRadioOn(!prefs.radioOn()); changed(); render(); } });
        row("متى تعمل الإذاعة", RadioWhen.label(prefs.radioWhen()), R.drawable.ic_screensaver,
            new Runnable() { public void run() { pickRadioWhen(); } });
        row("المحطة", stationLabel(), R.drawable.ic_radio, new Runnable() { public void run() { pickStation(); } });
        row("مستوى صوت الإذاعة", percent(prefs.radioVolume()), R.drawable.ic_volume,
            new Runnable() { public void run() { pickVolume(); } });
        previewRow = row("معاينة الإذاعة", previewLabel(), R.drawable.ic_play, new Runnable() { public void run() { togglePreview(); } });

        // each news page on a card: its sources (Prefs.NEWS_SOURCES), ticked in a list like the cards'
        boolean anyNews = false;
        for (String page : Prefs.NEWS_PAGES) anyNews |= hasPanel(prefs.panel(), page) || hasPanel(prefs.homePanel(), page);
        if (anyNews) {
            header("مصادر الأخبار");
            if (hasPanel(prefs.panel(), "news") || hasPanel(prefs.homePanel(), "news"))
                row("دولة الأخبار", newsCountryLabel() + (prefs.newsCountryAuto() ? " (تلقائي)" : "") + newsMissingNote(), R.drawable.ic_language,
                    new Runnable() { public void run() { pickNewsCountry(); } });
            for (final String page : Prefs.NEWS_PAGES) {
                if (!hasPanel(prefs.panel(), page) && !hasPanel(prefs.homePanel(), page)) continue;
                row("src_" + page, prefs.panelTitle(page), sourcesLabel(page), R.drawable.ic_category, new Runnable() { public void run() { pickSources(page); } });
            }
        }

        if (hasPanel(prefs.panel(), "shows") || hasPanel(prefs.homePanel(), "shows")) {   // only matter for the shows card (in a list too)
        header("جديد المسلسلات");
        row("التصنيفات", labels(Prefs.GENRES, prefs.list("genres"), "الكل"),
            R.drawable.ic_category, new Runnable() { public void run() { pickMany("genres", "التصنيفات", Prefs.GENRES); } });
        row("اللغات", labels(Prefs.LANGS, prefs.list("langs"), "الكل"),
            R.drawable.ic_language, new Runnable() { public void run() { pickMany("langs", "اللغات", Prefs.LANGS); } });
        row("مسلسلات مختارة", showsLabel(), R.drawable.ic_star, new Runnable() { public void run() { manageShows(); } });
        row("أقل تقييم", "0".equals(prefs.minRating()) ? "بدون حد" : prefs.minRating(),
            R.drawable.ic_star, new Runnable() { public void run() { pickRating(); } });
        }

        header("التطبيق");
        row("شكل الأرقام", numeralsLabel(), R.drawable.ic_language, new Runnable() { public void run() { pickNumerals(); } });
        updateRow = row("تحديث التطبيق", appUpdateLabel(), R.drawable.ic_sync, new Runnable() { public void run() { appUpdateNow(); } });
        row("التحديث التلقائي للصفحة", prefs.autoUpdate() ? "يعمل · كل " + num(6) + " ساعات" : "متوقف", R.drawable.ic_sync, new Runnable() { public void run() {
            prefs.setAutoUpdate(!prefs.autoUpdate()); render(); } });
        row("تحقق من التحديث الآن", null, R.drawable.ic_sync, new Runnable() { public void run() { updateNow(); } });
        row("وقت تشغيل شاشة التوقف", "يفتح إعدادات التلفاز (قد تظهر بالإنجليزية)", R.drawable.ic_screensaver, new Runnable() { public void run() { openDreamSettings(); } });

        // the row that had the focus before (by its key; gone: the one now at its place), at the same scroll
        View back = null;
        if (want != null) for (View r : rows) if (want.equals(r.getTag())) back = r;
        if (back == null && wantIndex >= 0 && !rows.isEmpty()) back = rows.get(Math.min(wantIndex, rows.size() - 1));
        if (back == null) { first.requestFocus(); return; }
        final View target = back;
        scroll.scrollTo(0, y);
        target.requestFocus();
        // after the new rows' layout: the same scroll, then just enough to show the row whole
        scroll.post(() -> {
            scroll.scrollTo(0, y);
            if (!target.hasFocus()) target.requestFocus();
            android.graphics.Rect r = new android.graphics.Rect();
            target.getDrawingRect(r);
            scroll.offsetDescendantRectToMyCoords(target, r);
            int top = scroll.getScrollY(), bottom = top + scroll.getHeight();
            if (r.top < top) scroll.scrollTo(0, r.top - dp(12));
            else if (r.bottom > bottom) scroll.scrollTo(0, r.bottom - scroll.getHeight() + dp(12));
        });
    }

    /** a row's key for render(): its label without a count or a note in brackets («الفرق المتابعة (3/10)») */
    private static String rowKey(String label) {
        return label.replaceAll("\\s*\\(.*$", "").trim();
    }

    // ---------- pickers ----------

    // ---------- the place: the country (every one, CLDR's Arabic names), then its cities (GeoNames, biggest first; the
    // Saudi ones from the regions list, as before). The city keeps its country and its time zone.

    // The bundled lists are read off the main thread (loadThen): the US alone has 16,000 cities, read and listed on it
    // they froze this screen («… isn't responding»). A country's list shows its biggest Geo.TOP cities; the search
    // («بحث في مدن …») looks through all of them, also off the main thread.
    private void pickCountry() {
        final android.content.Context app = getApplicationContext();
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
        final android.content.Context app = getApplicationContext();
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
        final android.content.Context app = getApplicationContext();
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
        final android.content.Context app = getApplicationContext();
        final long t0 = android.os.SystemClock.uptimeMillis();
        loadThen(() -> Geo.top(Geo.cities(app, c.cc), Geo.TOP), l -> showCities(c, l, null, t0));
    }

    /** a country's biggest cities, or those a search kept (query); first a search, then «ابحث عن مدينة أخرى». t0: when
     *  the country was picked (the time to the list shown goes to the log, tag SaudiTV-Settings), 0 for none */
    private void showCities(final Geo.Country c, final List<Geo.City> found, final String query, final long t0) {
        final List<Geo.City> list = found == null ? new ArrayList<Geo.City>() : found;
        final android.content.Context app = getApplicationContext();
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
    private <T> void loadThen(final java.util.concurrent.Callable<T> work, final Got<T> done) {
        final AlertDialog[] wait = {null};
        final boolean[] over = {false};
        final Runnable showWait = () -> {
            if (over[0] || isFinishing()) return;
            wait[0] = dialog().setMessage("جارٍ التحميل…").setCancelable(true).show();
        };
        list.postDelayed(showWait, 150);
        new Thread(() -> {
            T r;
            try { r = work.call(); } catch (Exception e) { r = null; }
            final T got = r;
            runOnUiThread(() -> {
                over[0] = true;
                list.removeCallbacks(showWait);
                boolean dropped = wait[0] != null && !wait[0].isShowing();
                if (wait[0] != null) wait[0].dismiss();
                if (!isFinishing() && !dropped) done.got(got);
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
            final android.content.Context app = getApplicationContext();
            loadThen(() -> {
                Geo.City near = Geo.nearest(app, cc, Double.parseDouble(lat), Double.parseDouble(lon));
                return near == null ? "" : near.tz;
            }, z -> savePlace(name, lat, lon, cc, z == null ? "" : z));
            return;
        }
        savePlace(name, lat, lon, cc, tz);
    }

    private void savePlace(String name, String lat, String lon, String cc, String tz) {
        String before = prefs.country(), news = prefs.newsCountry();
        prefs.setCity(name, lat, lon, cc, tz);
        // another country than before (never a re-pick in the same one): its news and its football (CountryChange)
        CountryChange.Plan plan = CountryChange.plan(before, cc, news, Prefs.hasNewsList(cc));
        if (plan != null) applyCountry(plan);
        changed();
        render();
    }

    /** a change of country: the news now, the table's league now, its top 3 clubs once the table is read */
    private void applyCountry(final CountryChange.Plan plan) {
        if (plan.newsMissing == null) prefs.setNewsOf(plan.news); else prefs.setNewsKept(plan.news, plan.newsMissing);
        final String newsPart = plan.newsMissing == null ? "أخبار " + prefs.newsCountryName()
            : "بقيت أخبار " + prefs.newsCountryName();
        final Leagues.League l = plan.league;
        if (l == null) { toast(newsPart + " · لا يوجد دوري لهذه الدولة، بقيت الفرق كما هي"); return; }
        prefs.setLeague(l.id);
        final int seq = ++countrySeq;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            List<String[]> table = null;
            try { table = TopClubs.table(l); }
            catch (Exception e) { android.util.Log.w("SaudiTV-Settings", "table " + l.id + ": " + e); }
            List<String[]> clubs = CountryChange.clubs(l.id, table);
            final List<String> names = new ArrayList<>();
            if (seq == countrySeq && !clubs.isEmpty()) {
                List<String> v = new ArrayList<>();
                for (String[] c : clubs) {
                    String ar = clubName(app, c[1], false).replace("|", " ").replace(";", " ");
                    v.add(c[0] + "|" + ar);
                    names.add(ar);
                }
                prefs.setList("teams", v);
            }
            runOnUiThread(() -> {
                if (seq != countrySeq) return;
                changed();
                if (isFinishing()) return;
                render();
                toast(newsPart + " · " + l.ar + " · " + (names.isEmpty() ? "بقيت الفرق كما هي" : Prefs.join(names, "، ")));
            });
        }, "country").start();
    }

    /** «دولة الأخبار»'s note after a change to a country without a news list of its own */
    private String newsMissingNote() {
        String m = prefs.newsMissing();
        if (m.isEmpty()) return "";
        Geo.Country c = Geo.country(this, m);
        return " · لا توجد قائمة أخبار خاصة ب" + (c == null ? m : c.shortAr);
    }

    private String cityLabel() {
        Geo.Country c = Geo.country(this, prefs.country());
        String name = prefs.city()[0];
        return c == null || name.equals(c.shortAr) ? name : name + "، " + c.shortAr;
    }

    /** «طريقة الحساب»: the country's (تلقائي) or one of Geo.METHODS */
    private void pickMethod() {
        String[] labels = new String[Geo.METHODS.length + 1];
        labels[0] = "تلقائي: " + Geo.methodLabel(prefs.autoMethod());
        int sel = prefs.methodAuto() ? 0 : -1;
        for (int i = 0; i < Geo.METHODS.length; i++) {
            labels[i + 1] = Geo.METHODS[i][1];
            if (!prefs.methodAuto() && Geo.METHODS[i][0].equals(String.valueOf(prefs.prayerMethod()))) sel = i + 1;
        }
        dialog().setTitle("طريقة الحساب")
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                prefs.setMethod(i == 0 ? -1 : Integer.parseInt(Geo.METHODS[i - 1][0]));
                d.dismiss();
                changed();
                render();
            }).show();
    }

    private static String unitsLabel(String u) { return "f".equals(u) ? "فهرنهايت (°F)" : "مئوية (°C)"; }

    /** «وحدة الحرارة»: the country's (°F in the US), °C or °F */
    private void pickUnits() {
        String auto = Geo.fahrenheit(prefs.country()) ? "f" : "c";
        String[] labels = {"تلقائي: " + unitsLabel(auto), unitsLabel("c"), unitsLabel("f")};
        int sel = prefs.unitsAuto() ? 0 : "f".equals(prefs.units()) ? 2 : 1;
        dialog().setTitle("وحدة الحرارة")
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                prefs.setUnits(i == 0 ? null : i == 1 ? "c" : "f");
                d.dismiss();
                changed();
                render();
            }).show();
    }

    private static String leagueLabel(String id) {
        Leagues.League l = Leagues.find(id);
        return l == null ? id : l.ar;
    }

    /** «الدوري في الترتيب»: the first followed club's league (تلقائي), or a country's league */
    private void pickTableLeague() {
        final List<Leagues.Group> groups = groupsFirst(prefs.country(), false);
        String[] labels = new String[groups.size() + 1];
        labels[0] = "تلقائي: " + leagueLabel(prefs.autoLeague());
        for (int i = 0; i < groups.size(); i++) labels[i + 1] = groups.get(i).ar;
        dialog().setTitle("الدوري في الترتيب")
            .setItems(labels, (d, i) -> {
                if (i == 0) { prefs.setLeague(null); changed(); render(); return; }
                final Leagues.Group g = groups.get(i - 1);
                if (g.leagues.length == 1) { prefs.setLeague(g.leagues[0].id); changed(); render(); return; }
                String[] names = new String[g.leagues.length];
                int sel = -1;
                for (int k = 0; k < names.length; k++) { names[k] = g.leagues[k].ar; if (g.leagues[k].id.equals(prefs.league())) sel = k; }
                dialog().setTitle(g.ar)
                    .setSingleChoiceItems(names, sel, (d2, k) -> { prefs.setLeague(g.leagues[k].id); d2.dismiss(); changed(); render(); })
                    .show();
            }).show();
    }

    /** the leagues' countries, the TV's own first (then the list's order) */
    private static List<Leagues.Group> groupsFirst(String cc, boolean national) {
        List<Leagues.Group> out = new ArrayList<>();
        for (Leagues.Group g : Leagues.GROUPS) if (!cc.isEmpty() && (g.cc.equals(cc) || g.cc.startsWith(cc + "-"))) out.add(g);
        for (Leagues.Group g : Leagues.GROUPS) if (!out.contains(g)) out.add(g);
        return out;
    }

    private String newsCountryLabel() {
        Geo.Country c = Geo.country(this, prefs.newsCountry());
        return c == null ? "السعودية" : c.ar;
    }

    /** «دولة الأخبار»: the city's country (تلقائي) or any other */
    private void pickNewsCountry() {
        final android.content.Context app = getApplicationContext();
        loadThen(() -> Geo.countries(app), this::showNewsCountries);
    }

    private void showNewsCountries(final List<Geo.Country> all) {
        if (all == null) return;
        String[] labels = new String[all.size() + 1];
        Geo.Country own = Geo.country(this, prefs.country());
        labels[0] = "تلقائي: دولة المدينة (" + (own == null ? "السعودية" : own.ar) + ")";
        int sel = prefs.newsCountryAuto() ? 0 : -1;
        for (int i = 0; i < all.size(); i++) {
            labels[i + 1] = all.get(i).ar;
            if (!prefs.newsCountryAuto() && all.get(i).cc.equals(prefs.newsCountry())) sel = i + 1;
        }
        dialog().setTitle("دولة الأخبار")
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                prefs.setNewsCountry(i == 0 ? null : all.get(i - 1).cc);
                d.dismiss();
                changed();
                render();
            }).show();
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

    /** Followed teams: untick to remove, or add one (presets, league browsing, search). Up to 10. */
    private void manageTeams() {
        final List<String> cur = prefs.teams();
        String[] labels = new String[cur.size()];
        final boolean[] keep = new boolean[cur.size()];
        for (int i = 0; i < cur.size(); i++) { labels[i] = cur.get(i).split("\\|", 2)[1]; keep[i] = true; }
        AlertDialog.Builder b = dialog()
            .setTitle(cur.isEmpty() ? "لا توجد فرق متابعة" : "الفرق المتابعة (ألغِ التحديد للحذف)")
            .setPositiveButton("حفظ", (d, w) -> {
                List<String> v = new ArrayList<>();
                for (int i = 0; i < cur.size(); i++) if (keep[i]) v.add(cur.get(i));
                prefs.setList("teams", v);
                render();
            })
            .setNegativeButton("إلغاء", null);
        if (cur.size() < Prefs.MAX_TEAMS) b.setNeutralButton("إضافة فريق", (d, w) -> pickTeam(0));
        if (!cur.isEmpty()) b.setMultiChoiceItems(labels, keep, (d, i, checked) -> keep[i] = checked);
        b.show();
    }

    private void addTeam(String id, String name) {
        if (prefs.addTeam(id, name)) toast("تمت متابعة " + name);
        else toast(prefs.teams().size() >= Prefs.MAX_TEAMS ? "الحد الأعلى " + num(Prefs.MAX_TEAMS) + " فرق" : "متابَع مسبقاً");
        render();
    }

    /** «إضافة فريق»: the country (or «منتخبات»), its league, then the club (ESPN's lists; TheSportsDB's for the leagues
     *  ESPN does not carry); the search by name (TheSportsDB) last, as before */
    private void pickTeam(final int slot) {
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
    private String clubName(String en, boolean national) { return clubName(this, en, national); }

    private static String clubName(android.content.Context c, String en, boolean national) {
        String k = Arabic.known(c, en);
        if (k != null) return k;
        if (national) { String n = Geo.arabicOf(c, en); if (n != null) return n; }
        return en;
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
                    rows[i] = Arabic.team(this, names[i]) + "  ·  " + leagueAr(t.optString("strLeague", ""));
                }
                dialog().setTitle(title)
                    .setItems(rows, (d, i) -> inArabic(names[i], Arabic.TEAM, ar -> addTeam(ids[i], ar)))
                    .show();
    }

    private void pickMany(final String key, String title, final String[][] options) {
        List<String> cur = prefs.list(key);
        String[] labels = new String[options.length];
        final boolean[] on = new boolean[options.length];
        for (int i = 0; i < options.length; i++) { labels[i] = options[i][1]; on[i] = cur.contains(options[i][0]); }
        dialog().setTitle(title + " (بدون اختيار = الكل)")
            .setMultiChoiceItems(labels, on, (d, i, checked) -> on[i] = checked)
            .setPositiveButton("حفظ", (d, w) -> {
                List<String> v = new ArrayList<>();
                for (int i = 0; i < options.length; i++) if (on[i]) v.add(options[i][0]);
                prefs.setList(key, v);
                render();
            })
            .setNegativeButton("إلغاء", null).show();
    }

    private void manageShows() {
        final List<String> cur = prefs.shows();
        String[] labels = new String[cur.size()];
        final boolean[] keep = new boolean[cur.size()];
        for (int i = 0; i < cur.size(); i++) { labels[i] = cur.get(i).split("\\|", 2)[1]; keep[i] = true; }
        AlertDialog.Builder b = dialog()
            .setTitle(cur.isEmpty() ? "لا توجد مسلسلات مختارة" : "المسلسلات المختارة (ألغِ التحديد للحذف)")
            .setPositiveButton("حفظ", (d, w) -> {
                List<String> v = new ArrayList<>();
                for (int i = 0; i < cur.size(); i++) if (keep[i]) v.add(cur.get(i));
                prefs.setList("shows", v);
                render();
            })
            .setNeutralButton("إضافة مسلسل", (d, w) -> searchShow())
            .setNegativeButton("إلغاء", null);
        if (!cur.isEmpty()) b.setMultiChoiceItems(labels, keep, (d, i, checked) -> keep[i] = checked);
        b.show();
    }

    private void searchShow() {
        ask("اسم المسلسل", q -> fetch("https://api.tvmaze.com/search/shows?q=" + Uri.encode(q), json -> {
            JSONArray a = new JSONArray(json);
            if (a.length() == 0) { toast("لا توجد نتائج"); return; }
            final int n = Math.min(a.length(), 15);
            final String[] ids = new String[n], names = new String[n], rows = new String[n];
            for (int i = 0; i < n; i++) {
                JSONObject s = a.getJSONObject(i).getJSONObject("show");
                ids[i] = String.valueOf(s.optInt("id"));
                names[i] = s.optString("name").replace("|", " ").replace(";", " ");
                String year = s.optString("premiered", "");
                rows[i] = names[i] + (year.length() >= 4 ? "  ·  " + year.substring(0, 4) : "")
                    + "  ·  " + labelOf(Prefs.LANGS, s.optString("language", ""));
            }
            dialog().setTitle("اختر المسلسل")
                .setItems(rows, (d, i) -> {
                    for (String s : prefs.shows()) if (s.startsWith(ids[i] + "|")) { toast("مضاف مسبقاً"); return; }
                    List<String> v = prefs.shows();
                    v.add(ids[i] + "|" + names[i]);
                    prefs.setList("shows", v);
                    toast("تمت إضافة " + names[i]);
                    render();
                }).show();
        }));
    }

    /** «ترتيب المفضلة» lives in the launcher (it owns the row). */
    private void openDockSettings() {
        try {
            startActivity(new Intent().setClassName("com.raad.sauditv.home", "com.raad.sauditv.home.DockActivity"));
        } catch (RuntimeException e) {
            toast("ثبّت الواجهة الرئيسية «سعودي TV» أولاً");
        }
    }

    /**
     * The card beside the screensaver's clock, or (home) the one on the launcher's home screen.
     * The ticked cards come first, in the order they take turns (the gold number beside each), then the others.
     * OK ticks or unticks one (a newly ticked card takes the last turn). On a ticked card ◀ ▶ move it one place, as
     * the list reads right to left: ▶ one turn earlier (towards 1), ◀ one later; the focus stays on it. Saved as
     * saver.sh does: "a,b,c" in that order, one id alone, or "none" when nothing is ticked.
     */
    private void pickPanel(final boolean home) {
        final List<String> ids = new ArrayList<>();   // every card but "none"
        for (String[] p : Prefs.PANELS) if (!"none".equals(p[0])) ids.add(p[0]);
        final List<String> order = new ArrayList<>();
        for (String s : (home ? prefs.homePanel() : prefs.panel()).split(",")) {
            s = s.trim();
            if (ids.contains(s) && !order.contains(s)) order.add(s);
        }
        orderedPicker((home ? "بطاقة على الشاشة الرئيسية" : "بجانب الساعة") + " (تتناوب بترتيب الأرقام)", ids, order,
            prefs::panelTitle, false, null, () -> {
                String v = order.isEmpty() ? "none" : Prefs.join(order, ",");
                if (home) prefs.setHomePanel(v); else prefs.setPanel(v);
                render();
            });
    }

    /** a row's name in an ordered picker */
    interface Title { String of(String id); }

    /**
     * A list to tick and order (the cards, the ticker's items): OK ticks, ◀ ▶ move a ticked row, the ticked ones first
     * in their order (numbered); «تم» runs done with `order` as it is then. atLeastOne: the last ticked row stays
     * ticked; reset: a neutral «إعادة الافتراضي» button.
     */
    private void orderedPicker(String title, final List<String> ids, final List<String> order, final Title name,
                               final boolean atLeastOne, final Runnable reset, final Runnable done) {
        final List<String> rows = new ArrayList<>();   // the list, top to bottom: order, then the unticked ones
        final CharSequence[] labels = new CharSequence[ids.size()];
        final boolean[] on = new boolean[ids.size()];
        panelRows(ids, order, rows, labels, on, name);
        AlertDialog.Builder b = dialog()
            .setCustomTitle(pickerTitle(title, "OK للتحديد · ◀ ▶ لتغيير الترتيب"))
            .setMultiChoiceItems(labels, on, (d, i, checked) -> {
                String id = rows.get(i);
                if (!checked && atLeastOne && order.size() == 1 && order.contains(id)) toast("يبقى عنصر واحد على الأقل");
                else { order.remove(id); if (checked) order.add(id); }
                showPanelRows((AlertDialog) d, ids, order, rows, labels, on, id, 0, name);
            })
            .setPositiveButton("تم", (d, w) -> done.run())
            .setNegativeButton("إلغاء", null);
        if (reset != null) b.setNeutralButton("إعادة الافتراضي", (d, w) -> reset.run());
        final AlertDialog dialog = b.create();
        final ListView lv = dialog.getListView();   // made by create()
        lv.setOnKeyListener((v, code, e) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;   // never out of the list sideways
            int at = lv.getSelectedItemPosition();
            int to = at + (code == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : -1);
            if (at < 0 || at >= order.size() || to < 0 || to >= order.size()) return true;   // unticked, or at an end
            Collections.swap(order, at, to);
            showPanelRows(dialog, ids, order, rows, labels, on, order.get(to), to - at, name);
            return true;
        });
        dialog.show();
    }

    /** The list's rows (the ticked ids in order, then the others in their own order), their labels and ticks. */
    private void panelRows(List<String> ids, List<String> order, List<String> rows, CharSequence[] labels, boolean[] on, Title name) {
        rows.clear();
        rows.addAll(order);
        for (String id : ids) if (!order.contains(id)) rows.add(id);
        for (int i = 0; i < rows.size(); i++) on[i] = i < order.size();
        panelLabels(rows, order, labels, name);
    }

    /** The list again after a tick or a move; the focus stays on `focus`, which moved `step` rows (0: anywhere,
     *  kept where the focus was, the list shifting under it). */
    private void showPanelRows(AlertDialog d, List<String> ids, List<String> order, List<String> rows,
                               CharSequence[] labels, boolean[] on, String focus, int step, Title name) {
        panelRows(ids, order, rows, labels, on, name);   // the list adapter shows these same arrays
        ListView lv = d.getListView();
        View sel = lv.getSelectedView();
        int y = sel == null ? 0 : sel.getTop(), h = sel == null ? 0 : sel.getHeight();
        android.widget.ListAdapter a = lv.getAdapter();
        if (a instanceof android.widget.BaseAdapter) ((android.widget.BaseAdapter) a).notifyDataSetChanged();
        for (int i = 0; i < on.length; i++) lv.setItemChecked(i, on[i]);
        int bottom = Math.max(0, lv.getHeight() - lv.getPaddingBottom() - h);
        lv.setSelectionFromTop(rows.indexOf(focus), Math.max(lv.getPaddingTop(), Math.min(bottom, y + step * h)));
    }

    /** A dialog title with a hint line under it (a list dialog has no room for a message). */
    private View pickerTitle(String title, String hint) {
        android.content.Context dc = new android.view.ContextThemeWrapper(this, DIALOG_THEME);
        LinearLayout box = new LinearLayout(dc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(dp(24), dp(18), dp(24), dp(6));
        TextView t = new TextView(dc);
        t.setTextAppearance(android.R.style.TextAppearance_Material_DialogWindowTitle);
        t.setText(title);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(t);
        TextView h = new TextView(dc);
        h.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        h.setTextColor(0xB3FFFFFF);
        h.setTextDirection(View.TEXT_DIRECTION_RTL);   // it starts with «OK»: still a right-to-left line
        h.setText(hint);
        h.setPadding(0, dp(4), 0, 0);
        box.addView(h);
        return box;
    }

    /** Each row's name, with its turn (1، 2، … in the chosen digits) in gold when ticked. */
    private void panelLabels(List<String> ids, List<String> order, CharSequence[] out, Title title) {
        for (int i = 0; i < out.length; i++) {
            String name = title.of(ids.get(i));
            int turn = order.indexOf(ids.get(i));
            if (turn < 0) { out[i] = name; continue; }
            android.text.SpannableString s = new android.text.SpannableString(name + "   " + num(turn + 1));
            s.setSpan(new android.text.style.ForegroundColorSpan(0xFFD6AF6B), name.length(), s.length(), 0);
            s.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), name.length(), s.length(), 0);
            out[i] = s;
        }
    }

    /** «عناصر شريط الأسعار»: the ticker's items, ticked and ordered as the cards are (Tape); «إعادة الافتراضي» = each
     *  country's own list. The country's currency and index follow the news country, the rest stays. */
    private void pickTape() {
        final String cc = prefs.newsCountry();
        final List<String> order = new ArrayList<>(prefs.tapeShown()), ids = Tape.offered(cc);
        for (String id : order) if (!ids.contains(id)) ids.add(id);   // chosen in another country
        orderedPicker("عناصر شريط الأسعار", ids, order, this::tapeName, true,
            () -> { prefs.setTape(new ArrayList<>()); changed(); render(); },
            () -> { prefs.setTape(order); changed(); render(); });
    }

    /** an item's name; the country's own currency and index with the news country's name */
    private String tapeName(String id) {
        if ("cur".equals(id) || "idx".equals(id)) {
            String c = prefs.newsCountryName();
            boolean none = "idx".equals(id) && !Tape.hasIndex(prefs.newsCountry());
            return Tape.label(id) + " (" + (none ? "لا يوجد لـ" + c : c) + ")";
        }
        return Tape.label(id);
    }

    private String tapeLabel() {
        List<String> names = new ArrayList<>();
        for (String id : prefs.tapeShown()) names.add(tapeName(id));
        return (prefs.tapeAuto() ? "تلقائي · " : "") + Prefs.join(names, "، ");
    }

    /** «شكل التطبيقات» (shape) or «شكل التحديد»: the home screen redraws its tiles at once (CONFIG_CHANGED). */
    private void pickTileLook(final boolean shape) {
        final String[][] table = shape ? Prefs.TILE_SHAPES : Prefs.FOCUS_STYLES;
        String cur = shape ? prefs.tileShape() : prefs.focusStyle();
        String[] labels = new String[table.length];
        int sel = 0;
        for (int i = 0; i < table.length; i++) {
            labels[i] = table[i][1];
            if (table[i][0].equals(cur)) sel = i;
        }
        dialog().setTitle(shape ? "شكل التطبيقات" : "شكل التحديد")
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                if (shape) prefs.setTileShape(table[i][0]); else prefs.setFocusStyle(table[i][0]);
                d.dismiss();
                changed();
                render();
            })
            .show();
    }

    /** A card setting holds a comma list now */
    private static boolean hasPanel(String list, String id) {
        for (String s : list.split(",")) if (s.trim().equals(id)) return true;
        return false;
    }

    /** «شكل الأرقام»: 0 1 2 3 or ٠ ١ ٢ ٣, everywhere (the page, the home screen, these screens) */
    private void pickNumerals() {
        String[] labels = new String[Prefs.NUMERALS.length];
        int sel = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = Prefs.NUMERALS[i][1];
            if (Prefs.NUMERALS[i][0].equals(prefs.numerals())) sel = i;
        }
        dialog().setTitle("شكل الأرقام")
            .setSingleChoiceItems(labels, sel, (d, i) -> { prefs.setNumerals(Prefs.NUMERALS[i][0]); d.dismiss(); changed(); render(); })
            .show();
    }

    private String numeralsLabel() {
        for (String[] x : Prefs.NUMERALS) if (x[0].equals(prefs.numerals())) return x[1];
        return Prefs.NUMERALS[0][1];
    }

    /** A news page's sources: tick the ones to read (they are shown together, newest first); none = the defaults. */
    private void pickSources(final String page) {
        final String[][] all = prefs.sourcesOf(page);
        final List<String> cur = prefs.sources(page);
        String[] labels = new String[all.length];
        final boolean[] on = new boolean[all.length];
        for (int i = 0; i < all.length; i++) { labels[i] = all[i][1]; on[i] = cur.contains(all[i][0]); }
        dialog()
            .setTitle("مصادر " + prefs.panelTitle(page) + " (بدون اختيار = الافتراضية)")
            .setMultiChoiceItems(labels, on, (d, i, checked) -> on[i] = checked)
            .setPositiveButton("تم", (d, w) -> {
                List<String> v = new ArrayList<>();
                for (int i = 0; i < all.length; i++) if (on[i]) v.add(all[i][0]);
                prefs.setSources(page, v);
                changed();
                render();
            })
            .setNegativeButton("إلغاء", null).show();
    }

    private String sourcesLabel(String page) {
        List<String> names = new ArrayList<>(), ids = prefs.sources(page);
        for (String[] x : prefs.sourcesOf(page)) if (ids.contains(x[0])) names.add(x[1]);
        return Prefs.join(names, "، ");
    }

    /** a home screen in front (behind a dialog) shows the new settings now, as after saver.sh */
    private void changed() {
        sendBroadcast(new Intent(SaverReceiver.CHANGED).setPackage("com.raad.sauditv.home"));
    }

    private void pickRating() {
        String[] labels = Prefs.MIN_RATINGS.clone();
        labels[0] = "بدون حد";
        int sel = 0;
        for (int i = 0; i < Prefs.MIN_RATINGS.length; i++) if (Prefs.MIN_RATINGS[i].equals(prefs.minRating())) sel = i;
        dialog().setTitle("أقل تقييم")
            .setSingleChoiceItems(labels, sel, (d, i) -> { prefs.setMinRating(Prefs.MIN_RATINGS[i]); d.dismiss(); render(); })
            .show();
    }

    private void updateNow() {
        toast("جارٍ التحقق…");
        new Thread(() -> {
            final String msg = Updater.check(getApplicationContext(), true);
            runOnUiThread(() -> toast(msg));
        }).start();
    }

    // ---------- «تحديث التطبيق»: both apps from the GitHub releases (AppUpdate checks, AppInstaller installs) ----------

    /** The installed version, and «محدّث» or «تحديث متوفر: …» from the last check. */
    private String appUpdateLabel() {
        String v = "النسخة " + AppUpdate.digits(this, AppUpdate.installedName(this));
        if (AppInstaller.running()) return v + " · جارٍ التحديث…";
        if (justUpdated != null) return "تم التحديث إلى " + AppUpdate.digits(this, justUpdated);
        AppUpdate.Manifest m = AppUpdate.available(this);
        if (m != null) return v + " · تحديث متوفر: " + AppUpdate.digits(this, m.versionName);
        return AppUpdate.sp(this).getLong("checked", 0) > 0 ? v + " · محدّث" : v;
    }

    /** Only the row's second line: a full render() would move the focus. */
    private void refreshUpdateRow() {
        if (updateRow == null) return;
        View texts = ((android.view.ViewGroup) updateRow).getChildAt(1);
        View val = texts instanceof android.view.ViewGroup ? ((android.view.ViewGroup) texts).getChildAt(1) : null;
        if (val instanceof TextView) ((TextView) val).setText(appUpdateLabel());
    }

    /** Opening the settings checks again when the last check is over 6 hours old (UpdateJob's interval). */
    private void quietCheck() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            AppUpdate.Result r = AppUpdate.check(app, false);
            if (r.code != AppUpdate.SKIPPED) runOnUiThread(() -> { if (!isFinishing()) refreshUpdateRow(); });
        }).start();
    }

    /** From the home screen's pill: the row in focus, then the check and the offer. */
    private void openAppUpdate() {
        if (updateRow != null) updateRow.requestFocus();
        appUpdateNow();
    }

    /** Checks now, then offers «تثبيت». */
    private void appUpdateNow() {
        if (AppInstaller.running()) { showProgress(); return; }
        toast("جارٍ التحقق من التحديث…");
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            final AppUpdate.Result r = AppUpdate.check(app, true);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                refreshUpdateRow();
                if (r.code == AppUpdate.AVAILABLE && r.manifest != null) offerInstall(r.manifest);
                else toast(r.message);
            });
        }).start();
    }

    private void offerInstall(final AppUpdate.Manifest m) {
        boolean self = false, home = false;
        for (AppUpdate.Apk a : m.needed(this)) {
            if (a.pkg.equals(getPackageName())) self = true; else home = true;
        }
        StringBuilder msg = new StringBuilder();
        if (!m.notes.isEmpty()) msg.append(m.notes).append("\n\n");
        msg.append("المثبتة: ").append(AppUpdate.digits(this, AppUpdate.installedName(this)))
           .append(" · حجم التنزيل: ").append(AppUpdate.size(this, m.bytes(this)));
        msg.append("\n\nيُنزَّل التحديث ويُتحقَّق منه أولاً، ثم يطلب التلفاز موافقتك على تثبيت ")
           .append(self && home ? "كل تطبيق (الواجهة الرئيسية ثم الإعدادات)" : "التطبيق").append('.');
        if (self) msg.append(" في النهاية يُغلق هذا التطبيق ويعود التلفاز إلى الشاشة الرئيسية.");
        dialog()
            .setTitle("تحديث متوفر: " + AppUpdate.digits(this, m.versionName))
            .setMessage(msg)
            .setPositiveButton("تثبيت", (d, w) -> install(m))
            .setNegativeButton("لاحقاً", null)
            .show();
    }

    private boolean canInstall() {
        return android.os.Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void install(AppUpdate.Manifest m) {
        if (!canInstall()) { askInstallPermission(); return; }
        showProgress();
        AppInstaller.start(this, m);
        refreshUpdateRow();   // «جارٍ التحديث…»
    }

    /** Once per TV: Android's «تثبيت التطبيقات غير المعروفة» for this app. Back from there continues the update. */
    private void askInstallPermission() {
        dialog()
            .setTitle("اسمح لـ «سعودي TV» بتثبيت تحديثاته")
            .setMessage("مرة واحدة فقط: ليثبّت «سعودي TV» تحديثاته من صفحة المشروع على GitHub يحتاج إذن "
                + "«تثبيت التطبيقات غير المعروفة».\n\nاضغط «فتح الإعدادات» وفعّل «سعودي TV»، ثم ارجع بزر الرجوع ويكمل التحديث."
                + "\n\nقبل أي تثبيت يتحقق التطبيق من كل ملف: البصمة، والحزمة، والنسخة، وتوقيع سعودي TV.")
            .setPositiveButton("فتح الإعدادات", (d, w) -> openInstallSettings())
            .setNegativeButton("إلغاء", null)
            .show();
    }

    private void openInstallSettings() {
        AppUpdate.sp(this).edit().putLong("askedInstall", System.currentTimeMillis()).apply();
        Intent[] tries = {
            new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + getPackageName())),
            new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES"),
            new Intent(Settings.ACTION_SECURITY_SETTINGS),
            new Intent(Settings.ACTION_SETTINGS),
        };
        for (Intent i : tries) {
            try { startActivity(i); return; } catch (ActivityNotFoundException ignored) { }
        }
        AppUpdate.sp(this).edit().remove("askedInstall").apply();
        toast("تعذّر فتح الإعدادات");
    }

    /** Back from «تثبيت التطبيقات غير المعروفة» (Android may have restarted this app on the way): go on, or say why not. */
    private void resumeAfterPermission() {
        android.content.SharedPreferences sp = AppUpdate.sp(this);
        long asked = sp.getLong("askedInstall", 0);
        if (asked == 0) return;
        sp.edit().remove("askedInstall").apply();
        if (System.currentTimeMillis() - asked > 30 * 60 * 1000L || AppInstaller.running()) return;
        if (!canInstall()) { toast("لم يُسمح بتثبيت التحديثات بعد، لم يتغير شيء"); return; }
        AppUpdate.Manifest m = AppUpdate.available(this);
        if (m != null) install(m); else appUpdateNow();
    }

    /** The progress dialog: a line of text, a bar, and «إلغاء» (download, or Android's confirm screen) / «حسناً». */
    private void showProgress() {
        if (progress != null) {
            if (!progress.isShowing()) progress.show();
            return;
        }
        android.content.Context dc = new android.view.ContextThemeWrapper(this, DIALOG_THEME);
        LinearLayout box = new LinearLayout(dc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), dp(4));
        progressText = new TextView(dc);
        progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        progressText.setText("جارٍ التحضير…");
        box.addView(progressText);
        progressBar = new android.widget.ProgressBar(dc, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFD6AF6B));
        progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFD6AF6B));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        box.addView(progressBar, lp);
        progress = dialog()
            .setTitle("تحديث التطبيق")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("إلغاء", null)
            .create();
        progress.show();
        progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            if (AppInstaller.running()) AppInstaller.cancel(this);
            else { progress.dismiss(); progress = null; }
        });
    }

    /**
     * The home screen's update closed it. Without this, once this screen goes (Back, or this app's own update ending
     * its process) Android brings back the app that was open before the home screen, not the home screen. One call
     * starts the new home screen and brings this screen back on top of it, so the home screen is right behind.
     */
    private void restoreHome() {
        Intent home = AppUpdate.homeIntent(this);
        if (home == null) return;   // our home screen isn't this TV's Home: nothing of ours to put back
        Intent self = new Intent(this, SettingsActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivities(new Intent[] {home, self}); }
        catch (RuntimeException e) { android.util.Log.w("SaudiTV-Update", "home: " + e); }
    }

    private void onInstallStatus(AppInstaller.Status s) {
        if (isFinishing()) return;
        if (progress == null && s.finished()) return;   // an earlier run's end: nothing to show
        showProgress();
        progressText.setText(s.text);
        progressBar.setIndeterminate(s.percent < 0);
        if (s.percent >= 0) progressBar.setProgress(s.percent);
        android.widget.Button b = progress.getButton(AlertDialog.BUTTON_NEGATIVE);
        b.setText(s.finished() ? "حسناً" : "إلغاء");
        // no «إلغاء» while Android installs: a session can't be stopped half-way (it installs whole or not at all)
        b.setVisibility(s.phase == AppInstaller.INSTALLING || s.phase == AppInstaller.VERIFYING ? View.GONE : View.VISIBLE);
        b.requestFocus();
        if (s.phase == AppInstaller.CONFIRM) {
            Intent ask = AppInstaller.takeConfirm();   // Android's own «تثبيت» screen, in this task
            if (ask != null) {
                try { startActivity(ask); } catch (RuntimeException e) { toast("تعذّر فتح نافذة التثبيت"); AppInstaller.cancel(this); }
            }
        }
        if (s.finished()) refreshUpdateRow();
    }

    private void openDreamSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    // ---------- «إذاعة القرآن الكريم»: the station (mp3quran.net's list, read off the main thread), its volume, a preview

    private String stationLabel() {
        return RadioStations.CUSTOM.equals(prefs.radioStation()) ? "رابط من عندي: ⁨" + prefs.radioName() + "⁩" : prefs.radioName();
    }

    private String percent(int v) { return num(v) + Prefs.percent(this); }

    private void pickStation() {
        final android.content.Context app = getApplicationContext();
        loadThen(() -> Radio.stations(app, true), l -> showStations(l, l, null));
    }

    /** the stations (all, or those a search found); first a search and «رابط من عندي», the current station in front */
    private void showStations(final List<RadioStations.Station> all, final List<RadioStations.Station> found, final String query) {
        if (all == null || found == null) { toast("تعذّر تحميل المحطات"); return; }
        final int head = 2;
        String[] labels = new String[found.size() + head];
        labels[0] = query == null ? "بحث في المحطات…" : "بحث آخر…";
        labels[1] = "رابط من عندي…";
        int sel = -1;
        for (int i = 0; i < found.size(); i++) {
            labels[i + head] = found.get(i).name;
            if (found.get(i).id.equals(prefs.radioStation())) sel = i + head;
        }
        if (found.isEmpty() && query != null) toast("لا توجد نتائج");
        AlertDialog d = dialog()
            .setTitle(query == null ? "المحطة" : "«⁨" + query + "⁩»")
            .setItems(labels, (x, i) -> {
                if (i == 0) ask("اسم المحطة أو القارئ", q -> loadThen(() -> RadioStations.search(all, q), r -> showStations(all, r, q)));
                else if (i == 1) askStreamUrl();
                else {
                    RadioStations.Station st = found.get(i - head);
                    prefs.setRadioStation(st.id, st.name, st.urls[0]);
                    changed();
                    render();
                }
            }).create();
        d.show();
        if (sel >= 0) d.getListView().setSelection(sel);
    }

    /** «رابط من عندي»: any http(s) stream (MP3, AAC, HLS) */
    private void askStreamUrl() {
        final EditText in = new EditText(this);
        in.setHint("https://…");
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setTextDirection(View.TEXT_DIRECTION_LTR);
        if (RadioStations.CUSTOM.equals(prefs.radioStation())) in.setText(prefs.radioUrl());
        final AlertDialog d = dialog().setTitle("رابط البث").setView(in)
            .setPositiveButton("حفظ", (x, w) -> {
                String u = in.getText().toString().trim();
                if (!RadioStations.validUrl(u)) { toast("رابط غير صالح: يبدأ بـ http:// أو https://"); return; }
                String host = Uri.parse(u).getHost();
                prefs.setRadioStation(RadioStations.CUSTOM, host == null ? u : host, u);
                changed();
                render();
            })
            .setNegativeButton("إلغاء", null).show();
        in.setOnEditorActionListener((v, action, ev) -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        in.requestFocus();
    }

    /** the radio's own volume, 10 … 100 % (the TV's volume apart); plays at once at the new one */
    private void pickVolume() {
        final int n = (RadioStations.VOLUME_MAX - RadioStations.VOLUME_MIN) / RadioStations.VOLUME_STEP + 1;
        String[] labels = new String[n];
        int sel = 0;
        for (int i = 0; i < n; i++) {
            int v = RadioStations.VOLUME_MIN + i * RadioStations.VOLUME_STEP;
            labels[i] = percent(v);
            if (v == prefs.radioVolume()) sel = i;
        }
        dialog().setTitle("مستوى صوت الإذاعة")
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                prefs.setRadioVolume(RadioStations.VOLUME_MIN + i * RadioStations.VOLUME_STEP);
                d.dismiss();
                render();
            }).show();
    }

    /** «متى تعمل الإذاعة» (RadioWhen): RadioService follows it at once */
    private void pickRadioWhen() {
        String[] labels = new String[RadioWhen.CHOICES.length];
        int sel = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = RadioWhen.CHOICES[i][1];
            if (RadioWhen.CHOICES[i][0].equals(prefs.radioWhen())) sel = i;
        }
        dialog().setTitle("متى تعمل الإذاعة")
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                prefs.setRadioWhen(RadioWhen.CHOICES[i][0]);
                changed();
                d.dismiss();
                render();
            }).show();
    }

    /** «معاينة الإذاعة»: the station plays here (even while the radio is off) until pressed again or this screen goes */
    private void togglePreview() {
        if (preview == null) preview = new RadioHolder(this, RadioService.PREVIEW);
        if (preview.isFront()) stopPreview();
        else {
            preview.front();
            list.removeCallbacks(previewTick);
            list.postDelayed(previewTick, 300);
        }
        setRowValue(previewRow, previewLabel());
    }

    private void stopPreview() {
        if (preview == null || !preview.isFront()) return;
        preview.back();
        list.removeCallbacks(previewTick);
        setRowValue(previewRow, previewLabel());
    }

    private String previewLabel() {
        if (preview == null || !preview.isFront()) return "استمع إلى المحطة هنا";
        switch (RadioService.state()) {
            case "playing": return "يُتلى الآن، اضغط للإيقاف";
            case "error": return "تعذّر التشغيل، تُعاد المحاولة… (" + RadioService.error() + ")";
            case "paused": return "متوقفة مؤقتاً";
            default: return "جارٍ التشغيل…";
        }
    }

    /** a row's second line only (render() would rebuild the list) */
    private static void setRowValue(View row, String text) {
        if (!(row instanceof android.view.ViewGroup)) return;
        View texts = ((android.view.ViewGroup) row).getChildAt(1);
        View val = texts instanceof android.view.ViewGroup ? ((android.view.ViewGroup) texts).getChildAt(1) : null;
        if (val instanceof TextView) ((TextView) val).setText(text);
    }

    // ---------- labels ----------

    /** Wikidata's Arabic name (or a transliteration) fetched off the UI thread. */
    private void inArabic(final String en, final java.util.regex.Pattern kind, final Text then) {
        toast("جارٍ جلب الاسم بالعربية…");
        new Thread(() -> {
            String k = kind == Arabic.TEAM ? Arabic.known(getApplicationContext(), en) : null;
            final String ar = k != null ? k : Arabic.lookup(en, kind);
            runOnUiThread(() -> { if (!isFinishing()) then.got(ar); });
        }).start();
    }

    private static String leagueAr(String en) {
        for (String[] l : Prefs.LEAGUES) if (l[0].equalsIgnoreCase(en)) return l[1];
        return Arabic.translit(en);
    }

    private static String labelOf(String[][] options, String key) {
        for (String[] o : options) if (o[0].equalsIgnoreCase(key)) return o[1];
        return Arabic.translit(key);
    }

    private String teamsLabel() {
        List<String> names = new ArrayList<>();
        for (String t : prefs.teams()) names.add(t.split("\\|", 2)[1]);
        return names.isEmpty() ? "لا شيء" : Prefs.join(names, "، ");
    }

    /** a number in the chosen digits («شكل الأرقام») */
    private String num(long n) { return Prefs.digits(this, n); }

    private String showsLabel() {
        List<String> v = prefs.shows();
        if (v.isEmpty()) return "لا شيء";
        List<String> names = new ArrayList<>();
        for (String s : v) names.add(s.split("\\|", 2)[1]);
        return Prefs.join(names, "، ");
    }

    private static String labels(String[][] options, List<String> picked, String none) {
        List<String> out = new ArrayList<>();
        for (String[] o : options) if (picked.contains(o[0])) out.add(o[1]);
        return out.isEmpty() ? none : Prefs.join(out, "، ");
    }

    // ---------- small UI helpers ----------

    private final java.util.Map<String, Typeface> fonts = new java.util.HashMap<>();

    /** Thmanyah, shared with the launcher's assets; falls back to the system font. */
    private Typeface font(String name) {
        Typeface tf = fonts.get(name);
        if (tf == null) {
            try { tf = Typeface.createFromAsset(getAssets(), "fonts/" + name + ".otf"); }
            catch (RuntimeException e) { tf = Typeface.DEFAULT; }
            fonts.put(name, tf);
        }
        return tf;
    }

    /** every dialog here takes the mouse too (Mouse.dialog: hover = the D-pad's focus, the wheel = ▲▼, right-click = Back) */
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, DIALOG_THEME) {
            @Override public AlertDialog create() { AlertDialog d = super.create(); Mouse.dialog(d); return d; }
        };
    }

    private void header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFD6AF6B);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(font("Thmanyah-Medium"));
        t.setLetterSpacing(0.05f);
        t.setPadding(dp(6), dp(22), dp(6), dp(8));
        list.addView(t);
    }

    /** A card row: icon, label and its current value on a second line, chevron; gold when focused. */
    private View row(String label, String value, int icon, final Runnable onClick) {
        return row(rowKey(label), label, value, icon, onClick);
    }

    /** key: what render() finds the row by again (a label that changes, «أخبار مصر», needs one of its own) */
    private View row(final String key, String label, String value, int icon, final Runnable onClick) {
        final LinearLayout card = new LinearLayout(this);
        card.setTag(key);
        final int index = rows.size();
        rows.add(card);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setPadding(dp(18), dp(12), dp(18), dp(12));
        card.setFocusable(true);
        card.setClickable(true);
        final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x14FFFFFF);
        bg.setStroke(dp(2), 0x1FD6AF6B);
        card.setBackground(bg);

        final android.widget.ImageView ic = new android.widget.ImageView(this);
        final android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
        d.setTint(0xFFD6AF6B);
        ic.setImageDrawable(d);
        card.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(0xFFF6EFE0);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        name.setTypeface(font("Thmanyah-Medium"));
        name.setSingleLine(true);
        texts.addView(name);
        final TextView val = new TextView(this);
        if (value != null) {
            val.setText(value);
            val.setTextColor(0xFFC6BBA4);
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            val.setTypeface(font("Thmanyah-Regular"));
            val.setSingleLine(true);
            val.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(val);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(dp(16));
        card.addView(texts, tp);

        final TextView chevron = new TextView(this);
        chevron.setText("›");   // shown mirrored (‹) in the RTL row
        chevron.setTextColor(0x99C6BBA4);
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        card.addView(chevron);

        card.setOnClickListener(v -> onClick.run());
        card.setOnFocusChangeListener((v, has) -> {
            if (has) { focusKey = key; focusIndex = index; }
            // No scaling: a scaled rounded card overlapped its neighbours and blurred the text.
            bg.setColor(has ? 0x33D6AF6B : 0x14FFFFFF);
            bg.setStroke(dp(2), has ? 0xFFD6AF6B : 0x1FD6AF6B);
            chevron.setTextColor(has ? 0xFFD6AF6B : 0x99C6BBA4);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        list.addView(card, lp);
        return card;
    }

    interface Text { void got(String s); }
    interface Json { void got(String json) throws Exception; }

    private void ask(String hint, final Text then) {
        final EditText in = new EditText(this);
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
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (result == null) { toast("لا يوجد اتصال"); return; }
                try { then.got(result); } catch (Exception e) { toast("تعذرت قراءة النتائج"); }
            });
        }).start();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
