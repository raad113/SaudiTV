package com.raad.sauditv.screensaver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * The first-run setup: five steps, everything else stays in the settings. Shown once on a new install (SetupState:
 * the home screen asks ConfigProvider /setup at its start, the settings check on opening), again from the settings'
 * «معالج الإعداد» or optimize/saver.sh setup.
 *  1. «شكل الواجهة»: two big cards side by side, each the home screen's picture in that look (LookPreview, 16:9) and
 *     its name; «الجديد» first and focused (Look.FRESH; once a look was chosen: that one); OK saves it (Prefs.setLook)
 *     and goes on. First, so PlaceDetect has had a moment when the city's step comes
 *  2. «مدينتك»: the city (PlaceDetect's guess, «جارٍ تحديد موقعك…» while it runs), «تغيير» (the settings' own place
 *     picker, Pickers); «التالي» takes it as it is
 *  3. «ماذا تعرض شاشة التوقف؟»: the cards beside the clock, ticked (Prefs.panel, at least one)
 *  4. «إذاعة القرآن الكريم»: off or where it plays (RadioWhen.MODES, Prefs.setRadioMode)
 *  5. «فريقك المفضل»: the followed teams (Prefs teams) and «إضافة فريق» (Pickers.pickTeam)
 * then «تم!» and «ابدأ»: the home screen. Each choice is saved at once, as in the settings; «تخطي» keeps what is set
 * and ends it. Finishing or skipping sets setupDone and lookChosen (the look kept). Back: the step before (on the
 * first: as «تخطي»).
 * LOOK_ONLY (the home screen, once, for a TV set up before this build: SetupState.lookPending): the look step alone,
 * no step count or buttons; OK saves the look, Back keeps the one set; either way lookChosen, then the home screen.
 */
public class SetupActivity extends Activity {
    private static final int STEPS = 5;
    private static final int STEP_LOOK = 0, STEP_CITY = 1, STEP_CARDS = 2, STEP_RADIO = 3, STEP_TEAMS = 4;
    /** an intent extra (boolean): the look step only (the home screen's one-time chooser, ConfigProvider look_pending) */
    static final String LOOK_ONLY = "lookOnly";
    /** the look cards' pictures, 16:9: two side by side with their names fit above the buttons on a 960 x 540 dp screen */
    static final int LOOK_W_DP = 320;
    private static final int GOLD = 0xFFD6AF6B, TEXT = 0xFFF6EFE0, DIM = 0xFFC6BBA4;
    private static final int DETECT_POLL_MS = 1000, DETECT_TRIES = 10;
    /** a choice row's height and the gap under it: four rows (the radio's four choices) fit whole above the buttons on
     *  a 960 x 540 dp screen (1280 x 720 at 213 dpi); a longer list scrolls, fading out at the bottom (FADE_DP) */
    static final int ROW_H_DP = 46, ROW_GAP_DP = 6, FADE_DP = 28;

    private Prefs prefs;
    private Pickers pickers;
    private Mouse mouse;
    private int step;   // 0 … STEPS-1, STEPS = the final screen
    private boolean lookOnly;   // LOOK_ONLY: the look step, nothing else
    private TextView stepLabel, title, subtitle;
    private LinearLayout dots, content, bar;
    private ScrollView contentScroll;
    private View primary;
    private final List<String> teamsShown = new ArrayList<>();   // step 4: the teams listed (an unticked one stays)
    private boolean teamsRead;   // teamsShown read on coming to step 4 (the city of step 1 may have changed the clubs)
    private int detectTries;
    private final Runnable detectPoll = new Runnable() {
        @Override public void run() {
            if (isFinishing() || step != STEP_CITY) return;
            if (!detecting() || ++detectTries >= DETECT_TRIES) { showStep(keyInFocus()); return; }
            content.postDelayed(this, DETECT_POLL_MS);
        }
    };

    /** Arabic locale, as the settings: every dialog title, list and button right to left */
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
        pickers = new Pickers(this, prefs, () -> showStep(keyInFocus()));
        lookOnly = getIntent() != null && getIntent().getBooleanExtra(LOOK_ONLY, false);
        if (b != null) step = Math.max(0, Math.min(STEPS, b.getInt("step", 0)));
        if (lookOnly) step = STEP_LOOK;

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFF0A2016, 0xFF04120C, 0xFF020806}));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        page.setPadding(dp(8), dp(36), dp(8), dp(28));
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(64), dp(860));
        root.addView(page, new FrameLayout.LayoutParams(width, -1, Gravity.CENTER_HORIZONTAL));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        stepLabel = text("", 15, DIM, "Thmanyah-Medium");
        top.addView(stepLabel);
        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.setMarginStart(dp(16));
        top.addView(dots, dl);
        page.addView(top);

        title = text("", 34, TEXT, "ThmanyahDisplay-Bold");
        title.setPadding(0, dp(14), 0, 0);
        page.addView(title);
        subtitle = text("", 17, DIM, "Thmanyah-Regular");
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        page.addView(subtitle);

        // a list longer than the room fades out at its bottom edge (only there: none at the top), and scrolls with the
        // focus; ScrollView keeps the focused row clear of the fade, so it is always whole above the buttons
        contentScroll = new ScrollView(this) {
            @Override protected float getTopFadingEdgeStrength() { return 0f; }
        };
        contentScroll.setVerticalScrollBarEnabled(false);
        contentScroll.setVerticalFadingEdgeEnabled(true);
        contentScroll.setFadingEdgeLength(dp(FADE_DP));
        contentScroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        contentScroll.addView(content);
        page.addView(contentScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        bar.setPadding(0, dp(16), 0, 0);
        page.addView(bar);

        setContentView(root);
        mouse = Mouse.attach(this, true);   // a mouse: hover = focus, the wheel = ▲▼, right-click = Back
        showStep(null);
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
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
    }

    @Override
    protected void onDestroy() {
        if (content != null) content.removeCallbacks(detectPoll);
        Geo.pickerClosed();   // the cities read for the place pickers go with the last screen that had them
        super.onDestroy();
    }

    /** Back: the step before; on the first, as «تخطي»; on the final screen, as «ابدأ»; look-only: the look kept */
    @Override
    public void onBackPressed() {
        if (lookOnly) finishLook();
        else if (step >= STEPS) finishSetup();
        else if (step == 0) finishSetup();
        else go(step - 1);
    }

    private void go(int to) {
        if (to == STEP_TEAMS && step != STEP_TEAMS) teamsRead = false;
        step = to;
        if (step >= STEPS) {   // «إنهاء»: done, whatever happens on the final screen
            prefs.setSetupDone(true);
            prefs.setLookChosen(true);
        }
        showStep(null);
    }

    /** done (finished or skipped; the look as it is, chosen): the home screen, ours when it is installed */
    private void finishSetup() {
        prefs.setSetupDone(true);
        prefs.setLookChosen(true);
        pickers.changed();
        goHome();
    }

    /** look-only done (a look picked, or Back: the one set kept): never asked again, then the home screen */
    private void finishLook() {
        prefs.setLookChosen(true);
        pickers.changed();
        goHome();
    }

    private void goHome() {
        Intent home = new Intent().setClassName("com.raad.sauditv.home", "com.raad.sauditv.home.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(home); }
        catch (RuntimeException e) {
            try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (RuntimeException ignored) { }
        }
        finish();
    }

    /** the focused view's key (its tag), so a redraw after a picker comes back to it */
    private String keyInFocus() {
        View f = getCurrentFocus();
        Object t = f == null ? null : f.getTag();
        return t instanceof String ? (String) t : null;
    }

    /** the current step drawn again; the focus on the view keyed focusKey, else on the primary button */
    private void showStep(String focusKey) {
        content.removeCallbacks(detectPoll);
        content.removeAllViews();
        bar.removeAllViews();
        dots.removeAllViews();
        boolean last = step >= STEPS;
        stepLabel.setVisibility(last || lookOnly ? View.GONE : View.VISIBLE);
        dots.setVisibility(last || lookOnly ? View.GONE : View.VISIBLE);
        if (!last && !lookOnly) {
            stepLabel.setText("الخطوة " + num(step + 1) + " من " + num(STEPS));
            for (int i = 0; i < STEPS; i++) {
                View d = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(i <= step ? GOLD : 0x33D6AF6B);
                d.setBackground(g);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(i == step ? 12 : 8), dp(i == step ? 12 : 8));
                lp.setMarginStart(dp(6));
                lp.gravity = Gravity.CENTER_VERTICAL;
                dots.addView(d, lp);
            }
        }
        switch (step) {
            case STEP_LOOK: stepLook(); break;
            case STEP_CITY: stepCity(); break;
            case STEP_CARDS: stepCards(); break;
            case STEP_RADIO: stepRadio(); break;
            case STEP_TEAMS: stepTeams(); break;
            default: stepDone(); break;
        }
        // the bottom bar: the primary (OK = the suggestion), «رجوع» (not on the first), «تخطي»; look-only: none
        if (lookOnly) {
            primary = null;
        } else if (last) {
            primary = button("start", "ابدأ", true, this::finishSetup);
        } else {
            primary = button("next", step == STEPS - 1 ? "إنهاء" : "التالي", true, () -> go(step + 1));
            if (step > 0) button("back", "رجوع", false, () -> go(step - 1));
            button("skip", "تخطي", false, this::finishSetup);
        }
        View want = focusKey == null ? null : findViewWithTagIn(focusKey);
        if (want == null && step == STEP_LOOK) want = content.findViewWithTag(lookKey(prefs.lookChosen() ? prefs.look() : Look.FRESH));
        final View target = want != null ? want : primary;
        if (target != null) target.requestFocus();
        if (want == null) contentScroll.scrollTo(0, 0);
    }

    private View findViewWithTagIn(String key) {
        View v = content.findViewWithTag(key);
        return v != null ? v : bar.findViewWithTag(key);
    }

    // ---------- the steps ----------

    private static String lookKey(String look) { return "look_" + Look.of(look); }

    /** two cards side by side, «الجديد» first (on the right: an Arabic row's start), each its picture and its name; OK
     *  saves it and goes on (look-only: back to the home screen) */
    private void stepLook() {
        title.setText("شكل الواجهة");
        subtitle.setText(lookOnly ? "للشاشة الرئيسية وشاشة التوقف، ويمكنك تغييره لاحقًا من الإعدادات"
            : "للشاشة الرئيسية وشاشة التوقف");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
        for (int i = Look.LOOKS.length - 1; i >= 0; i--) {   // Look.LOOKS is «الكلاسيكي», «الجديد»: «الجديد» first here
            final String look = Look.LOOKS[i][0];
            LinearLayout c = new LinearLayout(this);
            c.setTag(lookKey(look));
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setPadding(dp(12), dp(12), dp(12), dp(10));
            c.setFocusable(true);
            c.setClickable(true);
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            focusLook(bg, false);
            c.setBackground(bg);
            c.setOnFocusChangeListener((v, has) -> focusLook(bg, has));
            c.addView(new LookPreview(this, look, LOOK_W_DP));
            TextView name = text(Look.LOOKS[i][1], 19, TEXT, "Thmanyah-Medium");
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, dp(8), 0, 0);
            c.addView(name, new LinearLayout.LayoutParams(-1, -2));
            c.setOnClickListener(v -> {
                prefs.setLook(look);   // chosen (lookChosen), as in the settings
                if (lookOnly) finishLook();
                else { pickers.changed(); go(step + 1); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i < Look.LOOKS.length - 1) lp.setMarginStart(dp(24));
            row.addView(c, lp);
        }
    }

    private boolean detecting() { return prefs.raw().getBoolean(PlaceDetect.PENDING, false); }

    private void stepCity() {
        title.setText("مدينتك");
        subtitle.setText("تُضبط عليها مواقيت الصلاة والطقس والأخبار");
        boolean wait = detecting() && detectTries < DETECT_TRIES;
        TextView city = text(wait ? "جارٍ تحديد موقعك…" : pickers.cityLabel(), 30, TEXT, "ThmanyahDisplay-Bold");
        city.setPadding(dp(6), dp(8), dp(6), dp(20));
        content.addView(city);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.addView(row);
        button(row, "change", "تغيير", false, pickers::pickCountry);   // «التالي» takes the city as it is
        if (wait) content.postDelayed(detectPoll, DETECT_POLL_MS);   // PlaceDetect's answer, then drawn again
    }

    private void stepCards() {
        title.setText("ماذا تعرض شاشة التوقف؟");
        subtitle.setText("على شاشة التوقف، تتناوب البطاقات المختارة");
        final List<String> order = new ArrayList<>();
        for (String s : prefs.panel().split(",")) {
            s = s.trim();
            if (!s.isEmpty() && !"none".equals(s) && !order.contains(s)) order.add(s);
        }
        for (String[] p : Prefs.PANELS) {
            final String id = p[0];
            if ("none".equals(id)) continue;
            check("card_" + id, prefs.panelTitle(id), order.contains(id), false, (box, on) -> {
                if (!on && order.size() == 1 && order.contains(id)) { toast("يبقى عنصر واحد على الأقل"); return false; }
                order.remove(id);
                if (on) order.add(id);
                prefs.setPanel(Prefs.join(order, ","));
                pickers.changed();
                return true;
            });
        }
    }

    private void stepRadio() {
        title.setText("إذاعة القرآن الكريم");
        subtitle.setText("تعمل وحدها على الشاشة الرئيسية أو شاشة التوقف");
        final List<CompoundButton> boxes = new ArrayList<>();
        for (final String[] m : RadioWhen.MODES) {
            boxes.add(check("radio_" + m[0], m[1], m[0].equals(prefs.radioMode()), true, (box, on) -> {
                prefs.setRadioMode(m[0]);
                pickers.changed();
                for (CompoundButton b : boxes) b.setChecked(b == box);
                return true;
            }));
        }
    }

    private void stepTeams() {
        title.setText("فريقك المفضل");
        subtitle.setText("مبارياته ونتائجه على الشاشة");
        if (!teamsRead) { teamsShown.clear(); teamsRead = true; }
        for (String t : prefs.teams()) if (!teamsShown.contains(t)) teamsShown.add(t);   // and one just added
        final List<String> cur = prefs.teams();
        for (final String t : teamsShown) {
            String[] parts = t.split("\\|", 2);
            check("team_" + parts[0], parts.length > 1 ? parts[1] : parts[0], cur.contains(t), false, (box, on) -> {
                List<String> keep = new ArrayList<>();
                List<String> now = prefs.teams();
                for (String x : teamsShown) if (x.equals(t) ? on : now.contains(x)) keep.add(x);
                prefs.setList("teams", keep);
                pickers.changed();
                return true;
            });
        }
        if (prefs.teams().size() < Prefs.MAX_TEAMS) {
            View add = card("add_team", content);
            TextView name = text("+  إضافة فريق", 19, GOLD, "Thmanyah-Medium");
            ((LinearLayout) add).addView(name);
            add.setOnClickListener(v -> pickers.pickTeam(0));
        }
    }

    private void stepDone() {
        title.setText("تم!");
        subtitle.setText("يمكنك تغيير أي شيء لاحقًا من الإعدادات");
    }

    // ---------- small UI helpers, in the settings' look ----------

    interface Toggle { boolean set(CompoundButton box, boolean on); }

    /** a ticked (or, radio, one-of) row: OK flips it; toggle false keeps it as it was */
    private CompoundButton check(String key, String label, boolean on, boolean radio, final Toggle toggle) {
        final LinearLayout c = card(key, content);
        final CompoundButton box = radio ? new android.widget.RadioButton(this) : new android.widget.CheckBox(this);
        box.setChecked(on);
        box.setFocusable(false);
        box.setClickable(false);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(GOLD));
        box.setMinHeight(0);
        box.setMinimumHeight(0);
        c.addView(box, new LinearLayout.LayoutParams(-2, -1));   // the row's height, its mark in the middle
        TextView name = text(label, 19, TEXT, "Thmanyah-Medium");
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(dp(12));
        c.addView(name, tp);
        c.setOnClickListener(v -> {
            boolean want = radio || !box.isChecked();
            if (toggle.set(box, want)) box.setChecked(want);
        });
        return box;
    }

    /** a row card (the settings' row: dark, gold edge and fill in focus) */
    private LinearLayout card(String key, LinearLayout parent) {
        final LinearLayout card = new LinearLayout(this);
        card.setTag(key);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setPadding(dp(18), 0, dp(18), 0);
        card.setFocusable(true);
        card.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        focusLook(bg, false);
        card.setBackground(bg);
        card.setOnFocusChangeListener((v, has) -> focusLook(bg, has));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(ROW_H_DP));
        lp.bottomMargin = dp(ROW_GAP_DP);
        parent.addView(card, lp);
        return card;
    }

    private void focusLook(GradientDrawable bg, boolean has) {
        bg.setColor(has ? 0x33D6AF6B : 0x14FFFFFF);
        bg.setStroke(dp(2), has ? GOLD : 0x1FD6AF6B);
    }

    /** a button of the bottom bar */
    private View button(String key, String label, boolean main, Runnable onClick) { return button(bar, key, label, main, onClick); }

    private View button(LinearLayout parent, String key, String label, boolean main, final Runnable onClick) {
        TextView t = text(label, 19, main ? GOLD : TEXT, main ? "ThmanyahDisplay-Bold" : "Thmanyah-Medium");
        t.setTag(key);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(28), dp(12), dp(28), dp(12));
        t.setMinWidth(dp(140));
        t.setFocusable(true);
        t.setClickable(true);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        focusLook(bg, false);
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, has) -> focusLook(bg, has));
        t.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(12));
        parent.addView(t, lp);
        return t;
    }

    private TextView text(String s, int sp, int color, String font) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font(font));
        return t;
    }

    private final java.util.Map<String, Typeface> fonts = new java.util.HashMap<>();

    /** Thmanyah, shared with the launcher's assets; falls back to the system font (as the settings) */
    private Typeface font(String name) {
        Typeface tf = fonts.get(name);
        if (tf == null) {
            try { tf = Typeface.createFromAsset(getAssets(), "fonts/" + name + ".otf"); }
            catch (RuntimeException e) { tf = Typeface.DEFAULT; }
            fonts.put(name, tf);
        }
        return tf;
    }

    private String num(long n) { return Prefs.digits(this, n); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
