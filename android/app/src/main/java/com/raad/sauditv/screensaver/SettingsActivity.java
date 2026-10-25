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
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import java.util.List;

/** Remote-friendly settings: every row is a button that opens a list. */
public class SettingsActivity extends Activity {
    private static final int DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert;

    private Prefs prefs;
    private LinearLayout list;
    private View updateRow;   // «تحديث التطبيق»
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
        ScrollView scroll = new ScrollView(this);
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
        justUpdated = AppUpdate.takeJustUpdated(this);   // the first launch after a self-update
        if (justUpdated != null) toast("تم التحديث إلى " + AppUpdate.digits(this, justUpdated));
        render();
        AppInstaller.setListener(installListener);   // an update still running shows its progress again
        if (getIntent().getBooleanExtra("app_update", false)) openAppUpdate();   // the home screen's «تحديث متوفر»
        else quietCheck();
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
    protected void onDestroy() {
        AppInstaller.clearListener(installListener);
        if (progress != null) progress.dismiss();
        super.onDestroy();
    }

    private void render() {
        list.removeAllViews();
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

        row("معاينة الشاشة", "شاهد الشاشة الآن", R.drawable.ic_play, new Runnable() { public void run() {
            startActivity(new Intent(SettingsActivity.this, MainActivity.class)); } }).requestFocus();

        header("الموقع");
        row("المدينة", prefs.city()[0], R.drawable.ic_place, new Runnable() { public void run() { pickCity(); } });

        header("المباريات");
        row("الفرق المتابعة (" + num(prefs.teams().size()) + "/" + num(Prefs.MAX_TEAMS) + ")", teamsLabel(),
            R.drawable.ic_trophy, new Runnable() { public void run() { manageTeams(); } });

        header("بجانب الساعة");
        row("المحتوى", Prefs.panelLabel(prefs.panel()), R.drawable.ic_eye, new Runnable() { public void run() { pickPanel(false); } });

        header("الشاشة الرئيسية (سعودي TV)");
        row("بطاقة على الشاشة الرئيسية", Prefs.panelLabel(prefs.homePanel()), R.drawable.ic_eye,
            new Runnable() { public void run() { pickPanel(true); } });
        row("ترتيب التطبيقات المفضلة", "قائمة ترتّب فيها المفضلة بالأسهم", R.drawable.ic_star,
            new Runnable() { public void run() { openDockSettings(); } });

        // each news page on a card: its sources (Prefs.NEWS_SOURCES), ticked in a list like the cards'
        boolean anyNews = false;
        for (String page : Prefs.NEWS_PAGES) anyNews |= hasPanel(prefs.panel(), page) || hasPanel(prefs.homePanel(), page);
        if (anyNews) {
            header("مصادر الأخبار");
            for (final String page : Prefs.NEWS_PAGES) {
                if (!hasPanel(prefs.panel(), page) && !hasPanel(prefs.homePanel(), page)) continue;
                row(Prefs.panelLabel(page), sourcesLabel(page), R.drawable.ic_category, new Runnable() { public void run() { pickSources(page); } });
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
    }

    // ---------- pickers ----------

    private void pickCity() {
        final Cities.Region[] regions = Cities.REGIONS;
        String[] labels = new String[regions.length + 1];
        labels[0] = "بحث عن مدينة أو قرية…";
        for (int i = 0; i < regions.length; i++) labels[i + 1] = regions[i].name + "  (" + regions[i].cities.length + ")";
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle("المنطقة")
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
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(r.name)
            .setSingleChoiceItems(names, sel, (d, i) -> {
                prefs.setCity(r.cities[i][0], r.cities[i][1], r.cities[i][2]);
                d.dismiss();
                render();
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
                new AlertDialog.Builder(this, DIALOG_THEME).setTitle("اختر المكان")
                    .setItems(rows, (d, i) -> { prefs.setCity(names[i], lats[i], lons[i]); render(); }).show();
            }));
    }

    /** Followed teams: untick to remove, or add one (presets, league browsing, search). Up to 10. */
    private void manageTeams() {
        final List<String> cur = prefs.teams();
        String[] labels = new String[cur.size()];
        final boolean[] keep = new boolean[cur.size()];
        for (int i = 0; i < cur.size(); i++) { labels[i] = cur.get(i).split("\\|", 2)[1]; keep[i] = true; }
        AlertDialog.Builder b = new AlertDialog.Builder(this, DIALOG_THEME)
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

    private void pickTeam(final int slot) {
        final List<String[]> opts = new ArrayList<>();
        for (String[] t : Prefs.TEAM_PRESETS) opts.add(t);
        String[] labels = new String[opts.size() + 2];
        for (int i = 0; i < opts.size(); i++) labels[i] = opts.get(i)[1];
        labels[opts.size()] = "تصفح الدوريات…";
        labels[opts.size() + 1] = "بحث عن أي فريق أو منتخب…";
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle("إضافة فريق")
            .setItems(labels, (d, i) -> {
                if (i < opts.size()) addTeam(opts.get(i)[0], opts.get(i)[1]);
                else if (i == opts.size()) pickLeague(slot);
                else searchTeam(slot);
            }).show();
    }

    private void pickLeague(final int slot) {
        String[] labels = new String[Prefs.LEAGUES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = Prefs.LEAGUES[i][1];
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle("الدوري")
            .setItems(labels, (d, i) -> {
                final String[] lg = Prefs.LEAGUES[i];
                fetch(SPORTS + "search_all_teams.php?l=" + Uri.encode(lg[0]), json -> showTeams(slot, json, lg[1], lg[0]));
            }).show();
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
                    names[i] = t.optString("strTeam");
                    rows[i] = Arabic.team(this, names[i]) + "  ·  " + leagueAr(t.optString("strLeague", ""));
                }
                new AlertDialog.Builder(this, DIALOG_THEME).setTitle(title)
                    .setItems(rows, (d, i) -> inArabic(names[i], Arabic.TEAM, ar -> addTeam(ids[i], ar)))
                    .show();
    }

    private void pickMany(final String key, String title, final String[][] options) {
        List<String> cur = prefs.list(key);
        String[] labels = new String[options.length];
        final boolean[] on = new boolean[options.length];
        for (int i = 0; i < options.length; i++) { labels[i] = options[i][1]; on[i] = cur.contains(options[i][0]); }
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle(title + " (بدون اختيار = الكل)")
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
        AlertDialog.Builder b = new AlertDialog.Builder(this, DIALOG_THEME)
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
            new AlertDialog.Builder(this, DIALOG_THEME).setTitle("اختر المسلسل")
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
     * Several cards, OK ticks or unticks one; they take turns in the order they were ticked (the
     * gold number beside each). Saved as saver.sh does: "a,b,c", one id alone, or "none" when nothing is ticked.
     */
    private void pickPanel(final boolean home) {
        final List<String> ids = new ArrayList<>();   // every card but "none"
        for (String[] p : Prefs.PANELS) if (!"none".equals(p[0])) ids.add(p[0]);
        final List<String> order = new ArrayList<>();
        for (String s : (home ? prefs.homePanel() : prefs.panel()).split(",")) {
            s = s.trim();
            if (ids.contains(s) && !order.contains(s)) order.add(s);
        }
        final CharSequence[] labels = new CharSequence[ids.size()];
        final boolean[] on = new boolean[ids.size()];
        for (int i = 0; i < on.length; i++) on[i] = order.contains(ids.get(i));
        panelLabels(ids, order, labels);
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle((home ? "بطاقة على الشاشة الرئيسية" : "بجانب الساعة") + " (تتناوب بترتيب الأرقام)")
            .setMultiChoiceItems(labels, on, (d, i, checked) -> {
                on[i] = checked;
                order.remove(ids.get(i));
                if (checked) order.add(ids.get(i));
                panelLabels(ids, order, labels);   // the list adapter shows this same array
                android.widget.ListAdapter a = ((AlertDialog) d).getListView().getAdapter();
                if (a instanceof android.widget.BaseAdapter) ((android.widget.BaseAdapter) a).notifyDataSetChanged();
            })
            .setPositiveButton("تم", (d, w) -> {
                String v = order.isEmpty() ? "none" : Prefs.join(order, ",");
                if (home) prefs.setHomePanel(v); else prefs.setPanel(v);
                render();
            })
            .setNegativeButton("إلغاء", null).show();
    }

    /** Each card's name, with its turn (1، 2، … in the chosen digits) in gold when ticked. */
    private void panelLabels(List<String> ids, List<String> order, CharSequence[] out) {
        for (int i = 0; i < out.length; i++) {
            String name = Prefs.panelLabel(ids.get(i));
            int turn = order.indexOf(ids.get(i));
            if (turn < 0) { out[i] = name; continue; }
            android.text.SpannableString s = new android.text.SpannableString(name + "   " + num(turn + 1));
            s.setSpan(new android.text.style.ForegroundColorSpan(0xFFD6AF6B), name.length(), s.length(), 0);
            s.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), name.length(), s.length(), 0);
            out[i] = s;
        }
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
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle("شكل الأرقام")
            .setSingleChoiceItems(labels, sel, (d, i) -> { prefs.setNumerals(Prefs.NUMERALS[i][0]); d.dismiss(); changed(); render(); })
            .show();
    }

    private String numeralsLabel() {
        for (String[] x : Prefs.NUMERALS) if (x[0].equals(prefs.numerals())) return x[1];
        return Prefs.NUMERALS[0][1];
    }

    /** A news page's sources: tick the ones to read (they are shown together, newest first); none = the defaults. */
    private void pickSources(final String page) {
        final String[][] all = Prefs.newsSources(page);
        final List<String> cur = prefs.sources(page);
        String[] labels = new String[all.length];
        final boolean[] on = new boolean[all.length];
        for (int i = 0; i < all.length; i++) { labels[i] = all[i][1]; on[i] = cur.contains(all[i][0]); }
        new AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("مصادر " + Prefs.panelLabel(page) + " (بدون اختيار = الافتراضية)")
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
        for (String[] x : Prefs.newsSources(page)) if (ids.contains(x[0])) names.add(x[1]);
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
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle("أقل تقييم")
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
        new AlertDialog.Builder(this, DIALOG_THEME)
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
        new AlertDialog.Builder(this, DIALOG_THEME)
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
        progress = new AlertDialog.Builder(this, DIALOG_THEME)
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
        final LinearLayout card = new LinearLayout(this);
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
        new AlertDialog.Builder(this, DIALOG_THEME).setTitle("بحث").setView(in)
            .setPositiveButton("بحث", (d, w) -> {
                String q = in.getText().toString().trim();
                if (!q.isEmpty()) then.got(q);
            })
            .setNegativeButton("إلغاء", null).show();
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
