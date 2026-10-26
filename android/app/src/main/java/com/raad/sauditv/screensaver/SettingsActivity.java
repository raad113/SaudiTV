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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly settings: every row is a button that opens a list. */
public class SettingsActivity extends Activity {
    private static final int DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert;

    private Prefs prefs;
    private Pickers pickers;   // the place and «إضافة فريق» (shared with SetupActivity)
    private AthanSettings athan;   // the «الأذان» section
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
    private boolean setupOpened;   // «معالج الإعداد» was opened from here
    private RadioHolder preview;   // the radio's preview, playing while this screen is in front
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
        Geo.pickerOpened();   // its place pickers' cities are let go when the last such screen closes (onDestroy)
        prefs = new Prefs(this);
        pickers = new Pickers(this, prefs, this::render);
        athan = new AthanSettings(this, pickers);
        // a new install's first-run setup first (SetupState), unless the home screen's «تحديث متوفر» opened this
        if (!prefs.setupDone() && !getIntent().getBooleanExtra("app_update", false)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
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
        if (justUpdated != null) toast("تم التحديث إلى " + AppUpdate.version(this, justUpdated));
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
    protected void onRestart() {
        super.onRestart();
        if (setupOpened) { setupOpened = false; render(); }   // back from «معالج الإعداد»: its choices shown here
        else if (athan != null && athan.backFromSettings()) render();   // a permission the athan asked for, granted or not
    }

    @Override
    protected void onPause() {
        stopPreview();   // the preview only plays while these settings are in front
        if (athan != null) athan.stopPreview();   // and the athan voice's (AthanSettings.pickVoice)
        super.onPause();
    }

    /** «صوت من عندي»'s file, picked in Android's file picker (AthanSettings) */
    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        if (athan == null || !athan.onResult(req, code, data)) super.onActivityResult(req, code, data);
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
        Geo.pickerClosed();   // the cities read for the place pickers go with the last screen that had them
        super.onDestroy();
    }

    void render() {
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
        // «(تلقائي)»: the place a new install guessed (PlaceDetect), until one is picked
        row("المدينة", pickers.cityLabel() + (prefs.cityAuto() ? " (تلقائي)" : ""), R.drawable.ic_place, new Runnable() { public void run() { pickers.pickCountry(); } });
        row("طريقة الحساب", Geo.methodLabel(prefs.prayerMethod()) + (prefs.methodAuto() ? " (تلقائي)" : ""),
            R.drawable.ic_place, new Runnable() { public void run() { pickMethod(); } });
        row("وحدة الحرارة", unitsLabel(prefs.units()) + (prefs.unitsAuto() ? " (تلقائي)" : ""),
            R.drawable.ic_place, new Runnable() { public void run() { pickUnits(); } });

        header("المباريات");
        row("الفرق المتابعة (" + num(prefs.teams().size()) + "/" + num(Prefs.MAX_TEAMS) + ")", teamsLabel(),
            R.drawable.ic_trophy, new Runnable() { public void run() { manageTeams(); } });
        row("الدوري في الترتيب", leagueLabel(prefs.league()) + (prefs.leagueAuto() ? " (تلقائي)" : ""),
            R.drawable.ic_trophy, new Runnable() { public void run() { pickTableLeague(); } });

        header("شاشة التوقف");
        row("بطاقات شاشة التوقف", prefs.panelTitle(prefs.panel()), R.drawable.ic_eye, new Runnable() { public void run() { pickPanel(false); } });
        // «مدة كل بطاقة»: each card's seconds (SaverOptions.ROTATE_SECS), the screensaver's and the home screen's, both looks
        row("مدة كل بطاقة", rotateLabel(prefs.rotateSec()), R.drawable.ic_screensaver, new Runnable() { public void run() { pickRotate(); } });
        // «سطور شاشة التوقف»: «الجديد»'s screensaver lines bottom left (SaverOptions.FACTS), only in that look
        if (Look.NEW.equals(prefs.look()))
            row("سطور شاشة التوقف", factsLabel(), R.drawable.ic_category, new Runnable() { public void run() { pickFacts(); } });

        header("الشاشة الرئيسية (سعودي TV)");
        // «شكل الواجهة»: the home screen and the screensaver in «الكلاسيكي» or «الجديد» (Look), each with its picture
        row("شكل الواجهة", Look.label(prefs.look()) + " · الشاشة الرئيسية وشاشة التوقف", R.drawable.ic_screensaver,
            new Runnable() { public void run() { pickLook(); } });
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

        // «إذاعة القرآن الكريم» (RadioService): one choice, off or where it plays (RadioWhen.MODES), the station, its
        // volume, and a preview here
        header("إذاعة القرآن الكريم");
        row("إذاعة القرآن الكريم", RadioWhen.modeLabel(prefs.radioMode()), R.drawable.ic_radio,
            new Runnable() { public void run() { pickRadio(); } });
        row("المحطة", stationLabel(), R.drawable.ic_radio, new Runnable() { public void run() { pickStation(); } });
        row("مستوى صوت الإذاعة", percent(prefs.radioVolume()), R.drawable.ic_volume,
            new Runnable() { public void run() { pickVolume(); } });
        previewRow = row("معاينة الإذاعة", previewLabel(), R.drawable.ic_play, new Runnable() { public void run() { togglePreview(); } });

        athan.render();   // «الأذان» (AthanSettings): on or off, each prayer's alert, the voices, a test, the credits

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

        header("التطبيق");
        // «مصادر الصور»: the background photos' sources, ticked like a news page's (Prefs.PHOTO_SOURCES, the page's #photos=)
        row("مصادر الصور", photoSourcesLabel(), R.drawable.ic_screensaver, new Runnable() { public void run() { pickPhotoSources(); } });
        row("شكل الأرقام", numeralsLabel(), R.drawable.ic_language, new Runnable() { public void run() { pickNumerals(); } });
        // «معدل الإطارات» (30 by default): the ticker tape of the home screen and the screensaver at 30 or 60 frames a
        // second (Prefs.fps; 30 is the page's #lowpower=1); a home screen in front reloads its page (changed)
        row("معدل الإطارات", fpsLabel(prefs.fps()), R.drawable.ic_eco, new Runnable() { public void run() { pickFps(); } });
        // «تحديث التطبيق»: both apps from the GitHub releases, and the page from the repository too (one row, one toast)
        updateRow = row("تحديث التطبيق", appUpdateLabel(), R.drawable.ic_sync, new Runnable() { public void run() { updateAllNow(); } });
        row("التحديث التلقائي للصفحة", prefs.autoUpdate() ? "يعمل · كل " + num(6) + " ساعات" : "متوقف", R.drawable.ic_sync, new Runnable() { public void run() {
            prefs.setAutoUpdate(!prefs.autoUpdate()); render(); } });
        row("وقت تشغيل شاشة التوقف", "يفتح إعدادات التلفاز (قد تظهر بالإنجليزية)", R.drawable.ic_screensaver, new Runnable() { public void run() { openDreamSettings(); } });
        // the first-run setup (SetupActivity) again: the city, the cards, the radio, the team
        row("معالج الإعداد", "شكل الواجهة والمدينة وشاشة التوقف والإذاعة والفريق خطوة بخطوة", R.drawable.ic_settings, new Runnable() { public void run() {
            setupOpened = true;
            startActivity(new Intent(SettingsActivity.this, SetupActivity.class)); } });

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


    /** «دولة الأخبار»'s note after a change to a country without a news list of its own */
    private String newsMissingNote() {
        String m = prefs.newsMissing();
        if (m.isEmpty()) return "";
        Geo.Country c = Geo.country(this, m);
        return " · لا توجد قائمة أخبار خاصة ب" + (c == null ? m : c.shortAr);
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
        final List<Leagues.Group> groups = Pickers.groupsFirst(prefs.country(), false);
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
        if (cur.size() < Prefs.MAX_TEAMS) b.setNeutralButton("إضافة فريق", (d, w) -> pickers.pickTeam(0));
        if (!cur.isEmpty()) b.setMultiChoiceItems(labels, keep, (d, i, checked) -> keep[i] = checked);
        b.show();
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
        orderedPicker((home ? "بطاقة على الشاشة الرئيسية" : "بطاقات شاشة التوقف") + " (تتناوب بترتيب الأرقام)", ids, order,
            prefs::panelTitle, false, null, () -> {
                String v = order.isEmpty() ? "none" : Prefs.join(order, ",");
                if (home) prefs.setHomePanel(v); else prefs.setPanel(v);
                render();
            });
    }

    /** a row's name in an ordered picker */
    interface Title { String of(String id); }

    /** «مدة كل بطاقة»'s value: «١٥ ثانية» */
    private String rotateLabel(int sec) { return num(sec) + " ثانية"; }

    /** «مدة كل بطاقة»: 10, 15 (the default), 20, 30 or 60 seconds; a home screen in front reloads its page (changed) */
    private void pickRotate() {
        final int[] values = SaverOptions.ROTATE_SECS;
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = rotateLabel(values[i]) + (values[i] == SaverOptions.DEFAULT_ROTATE ? " (الافتراضي)" : "");
            if (values[i] == prefs.rotateSec()) sel = i;
        }
        dialog().setTitle("مدة كل بطاقة")
            .setSingleChoiceItems(labels, sel, (d, i) -> { prefs.setRotateSec(values[i]); d.dismiss(); changed(); render(); })
            .show();
    }

    /** «سطور شاشة التوقف»'s value: the lines in order («الصلاة القادمة، الطقس») */
    private String factsLabel() {
        List<String> names = new ArrayList<>();
        for (String id : prefs.saverFacts()) names.add(SaverOptions.factLabel(id));
        return Prefs.join(names, "، ");
    }

    /** «سطور شاشة التوقف»: ticked and ordered as the cards are, one at least, five at most (all of them);
     *  «إعادة الافتراضي» = the page's four (the next prayer, the match, the weather, Ramadan) */
    private void pickFacts() {
        final List<String> ids = new ArrayList<>(), order = new ArrayList<>(prefs.saverFacts());
        for (String[] f : SaverOptions.FACTS) ids.add(f[0]);
        orderedPicker("سطور شاشة التوقف", ids, order, SaverOptions::factLabel, true, SaverOptions.MAX_FACTS,
            () -> { prefs.setSaverFacts(null); changed(); render(); },
            () -> { prefs.setSaverFacts(order); changed(); render(); });
    }

    /**
     * A list to tick and order (the cards, the ticker's items): OK ticks, ◀ ▶ move a ticked row, the ticked ones first
     * in their order (numbered); «تم» runs done with `order` as it is then. atLeastOne: the last ticked row stays
     * ticked; reset: a neutral «إعادة الافتراضي» button.
     */
    private void orderedPicker(String title, final List<String> ids, final List<String> order, final Title name,
                               final boolean atLeastOne, final Runnable reset, final Runnable done) {
        orderedPicker(title, ids, order, name, atLeastOne, 0, reset, done);
    }

    /** the same, at most `max` ticked (0: no limit): one more is refused with «أربعة على الأكثر» (four) or «max على الأكثر» */
    private void orderedPicker(String title, final List<String> ids, final List<String> order, final Title name,
                               final boolean atLeastOne, final int max, final Runnable reset, final Runnable done) {
        final List<String> rows = new ArrayList<>();   // the list, top to bottom: order, then the unticked ones
        final CharSequence[] labels = new CharSequence[ids.size()];
        final boolean[] on = new boolean[ids.size()];
        panelRows(ids, order, rows, labels, on, name);
        AlertDialog.Builder b = dialog()
            .setCustomTitle(pickerTitle(title, "OK للتحديد · ◀ ▶ لتغيير الترتيب"))
            .setMultiChoiceItems(labels, on, (d, i, checked) -> {
                String id = rows.get(i);
                if (!checked && atLeastOne && order.size() == 1 && order.contains(id)) toast("يبقى عنصر واحد على الأقل");
                else if (checked && max > 0 && order.size() >= max && !order.contains(id)) toast(max == 4 ? "أربعة على الأكثر" : num(max) + " على الأكثر");
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

    /** «شكل التطبيقات» (shape) or «شكل التحديد»: the home screen redraws its tiles at once (CONFIG_CHANGED). Each
     *  choice with its picture (TilePreview: the apps' icons are read first, off the main thread). */
    private void pickTileLook(final boolean shape) {
        final android.content.Context app = getApplicationContext();
        loadThen(() -> TilePreview.apps(app), apps -> showTileLook(shape, apps));
    }

    private void showTileLook(final boolean shape, final List<TilePreview.App> apps) {
        final String[][] table = shape ? Prefs.TILE_SHAPES : Prefs.FOCUS_STYLES;
        String cur = shape ? prefs.tileShape() : prefs.focusStyle();
        String[] labels = new String[table.length];
        int sel = 0;
        for (int i = 0; i < table.length; i++) {
            labels[i] = table[i][1];
            if (table[i][0].equals(cur)) sel = i;
        }
        AlertDialog d = dialog().setTitle(shape ? "شكل التطبيقات" : "شكل التحديد")
            .setSingleChoiceItems(labels, sel, (x, i) -> {
                if (shape) prefs.setTileShape(table[i][0]); else prefs.setFocusStyle(table[i][0]);
                x.dismiss();
                changed();
                render();
            })
            .show();
        if (apps == null || apps.isEmpty()) return;   // no apps read: the choices without pictures
        // each row with its picture: the shape's 3 tiles, or 2 tiles of the current shape, the second focused in that look
        final boolean circles = "circle".equals(prefs.tileShape());
        ListView lv = d.getListView();
        if (lv == null || lv.getAdapter() == null) return;
        lv.setAdapter(TilePreview.withPictures(d.getContext(), lv.getAdapter(), i -> shape
            ? TilePreview.shapes(this, "circle".equals(table[i][0]), apps)
            : TilePreview.focus(this, circles, "apple".equals(table[i][0]), apps)));
        lv.setItemChecked(sel, true);   // a new adapter clears the choice
    }

    /** «شكل الواجهة»: «الكلاسيكي» or «الجديد», each with a small picture of the home screen in it (LookPreview, drawn as
     *  TilePreview's are). A home screen in front reloads its page in the new look at once (changed: CONFIG_CHANGED, the
     *  page's #look); the screensaver shows it from its next start. */
    private void pickLook() {
        final String[][] table = Look.LOOKS;
        String[] labels = new String[table.length];
        int sel = 0;
        for (int i = 0; i < table.length; i++) {
            labels[i] = table[i][1];
            if (table[i][0].equals(prefs.look())) sel = i;
        }
        AlertDialog d = dialog().setTitle("شكل الواجهة")
            .setSingleChoiceItems(labels, sel, (x, i) -> {
                prefs.setLook(table[i][0]);
                x.dismiss();
                changed();
                render();
            })
            .show();
        ListView lv = d.getListView();
        if (lv == null || lv.getAdapter() == null) return;
        lv.setAdapter(TilePreview.withPictures(d.getContext(), lv.getAdapter(), i -> new LookPreview(this, table[i][0])));
        lv.setItemChecked(sel, true);   // a new adapter clears the choice
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

    /** «مصادر الصور»: tick where the background photos come from (shown in turn); none = the defaults. Offline, the
     *  bundled photos. */
    private void pickPhotoSources() {
        // the offered ones only: «صور السعودية» is left out while Prefs.SAUDI_PHOTOS is off
        List<String[]> offered = new ArrayList<>();
        for (String[] x : Prefs.PHOTO_SOURCES) if (Prefs.photoSourceOffered(x[0])) offered.add(x);
        final String[][] all = offered.toArray(new String[0][]);
        final List<String> cur = prefs.photoSources();
        String[] labels = new String[all.length];
        final boolean[] on = new boolean[all.length];
        for (int i = 0; i < all.length; i++) { labels[i] = all[i][1]; on[i] = cur.contains(all[i][0]); }
        dialog()
            .setTitle("مصادر الصور (بدون اختيار = الافتراضية)")
            .setMultiChoiceItems(labels, on, (d, i, checked) -> on[i] = checked)
            .setPositiveButton("تم", (d, w) -> {
                List<String> v = new ArrayList<>();
                for (int i = 0; i < all.length; i++) if (on[i]) v.add(all[i][0]);
                prefs.setPhotoSources(v);
                changed();
                render();
            })
            .setNegativeButton("إلغاء", null).show();
    }

    private String photoSourcesLabel() {
        List<String> names = new ArrayList<>(), ids = prefs.photoSources();
        for (String[] x : Prefs.PHOTO_SOURCES) if (ids.contains(x[0])) names.add(x[1]);
        return Prefs.join(names, "، ");
    }

    private String sourcesLabel(String page) {
        List<String> names = new ArrayList<>(), ids = prefs.sources(page);
        for (String[] x : prefs.sourcesOf(page)) if (ids.contains(x[0])) names.add(x[1]);
        return Prefs.join(names, "، ");
    }

    /** «معدل الإطارات»'s value: «٣٠ إطارًا في الثانية (أخف)» or «٦٠ إطارًا في الثانية (أنعم)» */
    private String fpsLabel(int fps) {
        return num(fps) + " إطارًا في الثانية " + (fps == Prefs.FPS_HIGH ? "(أنعم)" : "(أخف)");
    }

    /** «معدل الإطارات»: 30 (lighter, the default) or 60 (smoother) */
    private void pickFps() {
        final int[] values = {Prefs.FPS_LOW, Prefs.FPS_HIGH};
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = fpsLabel(values[i]);
            if (values[i] == prefs.fps()) sel = i;
        }
        dialog().setTitle("معدل الإطارات")
            .setSingleChoiceItems(labels, sel, (d, i) -> { prefs.setFps(values[i]); d.dismiss(); changed(); render(); })
            .show();
    }

    /** a home screen in front (behind a dialog) shows the new settings now, as after saver.sh */
    private void changed() {
        sendBroadcast(new Intent(SaverReceiver.CHANGED).setPackage("com.raad.sauditv.home"));
    }

    /** «تحديث التطبيق»'s row: the page from the repository now (Updater, quietly: its answer only in the log), and the
     *  apps' check with its one toast and its offer (appUpdateNow) */
    private void updateAllNow() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> android.util.Log.i("SaudiTV-Update", "page: " + Updater.check(app, true))).start();
        appUpdateNow();
    }

    // ---------- «تحديث التطبيق»: both apps from the GitHub releases (AppUpdate checks, AppInstaller installs) ----------

    /** The installed version, and «محدّث» or «تحديث متوفر: …» from the last check. */
    private String appUpdateLabel() {
        String v = "النسخة " + AppUpdate.version(this, AppUpdate.installedName(this));
        if (AppInstaller.running()) return v + " · جارٍ التحديث…";
        if (justUpdated != null) return "تم التحديث إلى " + AppUpdate.version(this, justUpdated);
        AppUpdate.Manifest m = AppUpdate.available(this);
        if (m != null) return v + " · تحديث متوفر: " + AppUpdate.version(this, m.versionName);
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
        msg.append("المثبتة: ").append(AppUpdate.version(this, AppUpdate.installedName(this)))
           .append(" · حجم التنزيل: ").append(AppUpdate.size(this, m.bytes(this)));
        msg.append("\n\nيُنزَّل التحديث ويُتحقَّق منه أولاً، ثم يطلب التلفاز موافقتك على تثبيت ")
           .append(self && home ? "كل تطبيق (الواجهة الرئيسية ثم الإعدادات)" : "التطبيق").append('.');
        if (self) msg.append(" في النهاية يُغلق هذا التطبيق ويعود التلفاز إلى الشاشة الرئيسية.");
        dialog()
            .setTitle("تحديث متوفر: " + AppUpdate.version(this, m.versionName))
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

    /** «إذاعة القرآن الكريم»: off, or where it plays (RadioWhen.MODES, saved as the radio on/off and RadioWhen);
     *  RadioService follows it at once */
    private void pickRadio() {
        String[] labels = new String[RadioWhen.MODES.length];
        int sel = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = RadioWhen.MODES[i][1];
            if (RadioWhen.MODES[i][0].equals(prefs.radioMode())) sel = i;
        }
        dialog().setTitle("إذاعة القرآن الكريم")
            .setSingleChoiceItems(labels, sel, (d, i) -> {
                prefs.setRadioMode(RadioWhen.MODES[i][0]);
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


    private String teamsLabel() {
        List<String> names = new ArrayList<>();
        for (String t : prefs.teams()) names.add(t.split("\\|", 2)[1]);
        return names.isEmpty() ? "لا شيء" : Prefs.join(names, "، ");
    }

    /** a number in the chosen digits («شكل الأرقام») */
    String num(long n) { return Prefs.digits(this, n); }

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

    /** every dialog here takes the mouse too (Pickers.dialog: Mouse.dialog) */
    private AlertDialog.Builder dialog() { return pickers.dialog(); }

    /** off the main thread, then on it (Pickers.loadThen) */
    private <T> void loadThen(java.util.concurrent.Callable<T> work, Pickers.Got<T> done) { pickers.loadThen(work, done); }

    private void ask(String hint, Pickers.Text then) { pickers.ask(hint, then); }

    void header(String text) {
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
    View row(String label, String value, int icon, final Runnable onClick) {
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


    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
