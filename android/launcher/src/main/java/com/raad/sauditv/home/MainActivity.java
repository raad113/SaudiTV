package com.raad.sauditv.home;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX). Network only for hosted wallpapers and the optional card (HomeCard).
 *
 * the home screen looks like the screensaver now. One full-screen WebView (HomePage: the screensaver's
 * page in home mode) draws the photo, the dates, the weather, the clock with the VPN badge, the rotating card, the
 * info strip (next prayer, Ramadan, next match) and the moving market ticker; on top of it: the pills (compact
 * glass, top left, over the weather), an invisible focusable view over the page's card, and the dock in a slim glass
 * band above the info strip (hugging its smaller tiles, centred; banners or circles, the focused tile in Google TV's
 * or Apple TV's look, as the screensaver app's settings say). Down from the dock
 * scrolls the apps grid up over the page, which a dark shade dims. The native header (greeting, clock, date, VPN
 * chip), the native wallpaper rotation, the glow, the dock app's title and the separate card WebView are gone.
 *
 * the «الإعدادات» pill, the remote's gear key (where the TV lets it through) and a long press of Menu
 * open the quick-settings panel (QuickPanel), like Google TV's; its «كل الإعدادات» tile opens the TV's settings.
 *
 * Adapted from github.com/mihirgupta0900/tv-home (MIT, see android/launcher/LICENSE-tv-home):
 * Arabic text and digits, Hijri date, Thmanyah fonts, the screensaver's gold palette and Saudi photos,
 * clock on the right, and a pill that opens the screensaver's settings.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label, category;   // category: the one it was found with, LEANBACK_LAUNCHER or LAUNCHER
        ComponentName component;
        Drawable art;
        boolean isBanner;
        boolean fullBleed;   // «دوائر»: an older icon that is an opaque square, drawn to fill its circle
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();
    private final Map<View, Tile> tiles = new HashMap<>();   // each card's views, for its focus look
    // the tiles' look (the screensaver app's settings, read by HomePage): «دوائر», else banners; Apple TV, else Google TV
    private boolean circles, apple;

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View gridShade;
    private LinearLayout pillRow;   // the pills, top left
    private TextView wifiPill, appsLabel;
    private int lastFocusedId = View.NO_ID;
    private int lastDockId = View.NO_ID;   // dock card that last had focus (DOWN from the home card)
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;
    private HomePage homePage;   // the page behind everything (was HomeCard, a card-only WebView)
    private QuickPanel quick;   // the quick-settings panel
    private TextView settingsPill;   // the «الإعدادات» pill (the panel gives the focus back to it)
    private TextView updatePill;   // «تحديث متوفر»: in the row only while the screensaver app found a newer release
    private boolean gridOpen, menuLong;   // the apps grid is up; Menu is held (long press)

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateWifi(); }   // the page has the clock
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };
    // favourites changed in «ترتيب المفضلة» or from ADB while the home screen is up
    private final BroadcastReceiver dockChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dockApps = loadDock(); buildApps(); }
    };

    // the screensaver app's settings changed (e.g. over ADB) while the home screen is up: reload its page, and the
    // tiles when their look changed
    private final BroadcastReceiver configChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { homePage.refresh(); applyStyle(); }
    };

    // the screensaver app checked for an app update (or installed one) while the home screen is up
    private final BroadcastReceiver updateState = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshUpdate(); }
    };
    /** the screensaver app's last app-update check (its ConfigProvider): available = the newer versionCode, 0 = none */
    static final android.net.Uri UPDATE = android.net.Uri.parse("content://com.raad.sauditv.screensaver.config/update");

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = loadDock();
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        circles = homePage.circles();   // read with the page's settings
        apple = homePage.apple();
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        listen(packages, pf, false);
        listen(dockChanged, new IntentFilter(Dock.CHANGED), false);
    }

    /** Android 14 (targetSdk 34) wants every receiver registered here marked: other apps may send to it, or not.
     *  System broadcasts (packages, time, Wi-Fi) reach a not-exported receiver too. */
    private void listen(BroadcastReceiver r, IntentFilter f, boolean otherApps) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, otherApps ? RECEIVER_EXPORTED : RECEIVER_NOT_EXPORTED);
        else registerReceiver(r, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        listen(tick, tf, false);
        listen(configChanged, new IntentFilter("com.raad.sauditv.screensaver.CONFIG_CHANGED"), true);   // from the screensaver app
        listen(updateState, new IntentFilter("com.raad.sauditv.screensaver.UPDATE_STATE"), true);   // from the screensaver app
        updateWifi();
        refreshUpdate();
        homePage.resume();   // the page runs again (it has the clock, the photos and the VPN badge)
        applyStyle();   // the tiles' look may have changed in the settings meanwhile
        wireFocus();   // the card may have appeared or gone
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null && !last.isShown()) last = null;   // e.g. the card while the page has none
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (quick.isOpen()) quick.resume();   // Back from a screen the panel opened lands on its tile
        else if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(tick);
        unregisterReceiver(configChanged);
        unregisterReceiver(updateState);
        if (inputsPopup != null) inputsPopup.dismiss();
        homePage.pause();
        quick.pause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        quick.stop();   // the screensaver or the VPN app opened from the panel closes it behind them
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        unregisterReceiver(dockChanged);
        homePage.destroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed: land on the dock (last focused dock card, else the first), never a header pill.
        // lastFocusedId is set too, so onResume (which follows when coming back from another app) keeps it
        if (inputsPopup != null) inputsPopup.dismiss();
        quick.close(hasWindowFocus());   // Home closes the quick-settings panel (slides out if in view)
        if (!dockCards.isEmpty()) {
            lastFocusedId = dockDownId();
            View d = findViewById(lastFocusedId);
            if (d != null) d.requestFocus();
        }
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (quick.isOpen()) { quick.close(true); return; }   // Back slides the panel out
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // the screensaver's page in home mode fills the screen, under everything (it adds its WebView
        // at index 0); its photo, veils and glass replace the native wallpaper, scrim and glow
        homePage = new HomePage(this, root);
        // the apps grid scrolls up over the page, dimmed by this shade (the page's dark green-black);
        // light enough that the page's blurred photo (sauditvAppsOpen) still shows through, the page text is hidden there
        gridShade = new View(this);
        gridShade.setBackgroundColor(0x73040E09);
        gridShade.setAlpha(0f);
        root.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        // the page's first screen: the pills at its top left, the card's focus view where the page's card
        // is, the dock in the band the page leaves above its ticker tape; no title over the dock
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        int side = side();
        hero.addView(buildPills(), lp(-2, dp(HomePage.PILLS_H_DP), Gravity.LEFT | Gravity.TOP, side, dp(HomePage.TOP_DP), 0, 0));
        // the card is selectable; focused, it acts like a pill (first screen in view, no shade)
        hero.addView(homePage.cardView(), new FrameLayout.LayoutParams(0, 0, Gravity.LEFT | Gravity.TOP));
        homePage.setOnFocus(() -> { lastFocusedId = homePage.cardView().getId(); scroll.smoothScrollTo(0, 0); shade(false); });
        // the page shows a card, moves it or drops it (e.g. «none» chosen): rewire, never lose the focus
        homePage.setOnCardChanged(hadFocus -> {
            wireFocus();
            View d = dockCards.isEmpty() ? null : findViewById(dockDownId());
            if (d != null && (hadFocus || getCurrentFocus() == null)) d.requestFocus();
        });

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);   // the tiles near its top, names under them
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        // soft frosted glass that hugs the tiles, centred (GlassBand: the page's dark tint, lighter, a faint
        // sheen and a highlight along its top edge, no border); the page's info strip is under it
        dockRow.setPadding(dp(DOCK_PAD_DP), dp(DOCK_TOP_DP), dp(DOCK_PAD_DP), 0);
        dockRow.setBackground(new GlassBand(dp(DOCK_RADIUS_DP), dp(1)));
        hero.addView(dockRow, lp(-2, dp(HomePage.DOCK_H_DP), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, side, 0, side, dp(HomePage.DOCK_B_DP)));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("كل التطبيقات");
        appsLabel.setTextColor(GOLD);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        quick = new QuickPanel(this, root, scroll);   // over everything; blocks the scroll's views while open
        return root;
    }

    /** the pills in one row at the page's top left, over its weather (the page leaves PILLS_H_DP). */
    private View buildPills() {
        pillRow = new LinearLayout(this);
        pillRow.setGravity(Gravity.CENTER_VERTICAL);
        pillRow.setClipChildren(false);
        TextView saver = pill("شاشة التوقف", R.drawable.ic_screensaver);
        saver.setOnClickListener(v -> openPackage(SAVER_PKG));
        TextView inputs = pill("المصادر", R.drawable.ic_input);
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = pill("واي فاي", R.drawable.ic_wifi);
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        wifiPill.setMaxWidth(dp(150));   // a long network name ends in «…»
        wifiPill.setSingleLine(true);
        wifiPill.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView settings = pill("الإعدادات", R.drawable.ic_settings);
        // the quick-settings panel (was the TV's settings, now its «كل الإعدادات» tile)
        settings.setOnClickListener(v -> quick.open());
        settingsPill = settings;
        for (TextView p : isInstalled(SAVER_PKG) ? new TextView[]{inputs, wifiPill, settings, saver}
                                                  : new TextView[]{inputs, wifiPill, settings}) {
            pillRow.addView(p, llp(-2, -1, pills.isEmpty() ? 0 : dp(8), 0, 0, 0));
            pills.add(p);
        }
        // last in the row, only while an update is available (refreshUpdate); opens the screensaver app's update screen
        updatePill = pill("تحديث متوفر", R.drawable.ic_sync);
        updatePill.setOnClickListener(v -> openUpdate());
        return pillRow;
    }

    /** «تحديث متوفر» (the pill, the quick panel's «سعودي TV» tile): the screensaver app's settings, which check and
     *  offer «تثبيت» (its SettingsActivity, extra app_update). */
    void openUpdate() {
        launch(new Intent().setClassName(SAVER_PKG, SAVER_PKG + ".SettingsActivity")
                .putExtra("app_update", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    /** Shows or hides «تحديث متوفر» from the screensaver app's last check; nothing when that app is older (no such
     *  answer) or missing. */
    private void refreshUpdate() {
        long available = 0;
        try (android.database.Cursor c = getContentResolver().query(UPDATE, null, null, null, null)) {
            int i = c == null ? -1 : c.getColumnIndex("available");
            if (i >= 0 && c.moveToFirst()) available = c.getLong(i);
        } catch (RuntimeException e) {
            available = 0;
        }
        boolean on = available > 0;
        quick.setUpdate(on);
        if (on == pills.contains(updatePill)) return;
        if (on) {
            pillRow.addView(updatePill, llp(-2, -1, dp(8), 0, 0, 0));
            pills.add(updatePill);
        } else {
            boolean had = updatePill.hasFocus();
            pillRow.removeView(updatePill);
            pills.remove(updatePill);
            if (had) settingsPill.requestFocus();
        }
        wireFocus();
    }

    /** Pill button: label with a Material icon on its right (Arabic reading order), tinted with the text.
     *  compact glass, like the page's card (its dark tint, a thin gold edge); focused: white, as before */
    private TextView pill(String label, int icon) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(label);
        p.setGravity(Gravity.CENTER);
        android.graphics.drawable.Drawable ic = getDrawable(icon).mutate();
        ic.setBounds(0, 0, dp(14), dp(14));
        ic.setTint(0xE6FFFFFF);
        p.setCompoundDrawables(null, null, ic, null);
        p.setCompoundDrawablePadding(dp(6));
        p.setPadding(dp(11), 0, dp(11), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(HomePage.PILLS_H_DP / 2f));
        bg.setColor(GLASS);
        bg.setStroke(dp(1), GLASS_EDGE);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : GLASS);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            ic.setTint(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); shade(false); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        // a tile keeps the focus across a rebuild (new views, new ids): remembered by its app
        String focusPkg = pkgOf(lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId));
        String dockPkg = pkgOf(lastDockId == View.NO_ID ? null : findViewById(lastDockId));
        cols = circles ? GRID_CIRCLE_COLS : getResources().getInteger(R.integer.columns);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : Dock.catalogue(this)) {
            ActivityInfo ai = ri.activityInfo;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            a.category = Dock.isTv(ri) ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER;
            if (circles) {   // «دوائر»: the app's own icon (an adaptive icon's layers, or an older square one)
                Drawable icon;
                try { icon = pm.getApplicationIcon(ai.applicationInfo); } catch (RuntimeException e) { icon = ri.loadIcon(pm); }
                a.art = icon;
                a.isBanner = false;
                a.fullBleed = !(icon instanceof AdaptiveIconDrawable) && fullBleed(icon);
            } else {
                Drawable banner = ai.loadBanner(pm);
                if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
                a.isBanner = banner != null;
                a.art = banner != null ? banner : ri.loadIcon(pm);
                a.color = sampleColor(a.pkg, a.art);
            }
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockApps) if (byPkg.containsKey(p)) dock.add(byPkg.remove(p));
        cardByPkg.clear();
        List<App> rest = new ArrayList<>(byPkg.values());
        Collections.sort(rest, (x, y) -> x.label.compareToIgnoreCase(y.label));
        // Fewer than MIN_DOCK of the chosen apps installed: top the dock up from the rest.
        while (dock.size() < MIN_DOCK && !rest.isEmpty()) dock.add(rest.remove(0));

        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();
        tiles.clear();
        Shadow.forget();   // new tiles, new sizes

        // the dock's tiles: banners at most DOCK_TILE_H_DP tall (16:9), or circles CIRCLE_DP across in CIRCLE_SLOT_DP
        // wide slots (their names under them), centred in the band; more apps than fit share the band's width
        int n = Math.max(1, dock.size()), gap = dp(circles ? CIRCLE_GAP_DP : DOCK_GAP_DP);
        dockRow.setPadding(dp(DOCK_PAD_DP), dp(circles ? CIRCLE_TOP_DP : DOCK_TOP_DP), dp(DOCK_PAD_DP), 0);
        int inner = getResources().getDisplayMetrics().widthPixels - 2 * side() - 2 * dp(DOCK_PAD_DP);
        int tileW = Math.min(dp(circles ? CIRCLE_SLOT_DP : DOCK_TILE_H_DP * 16f / 9f), (inner - (n - 1) * gap) / n);
        int dockD = Math.min(dp(CIRCLE_DP), tileW - dp(CIRCLE_SLOT_DP - CIRCLE_DP));   // a circle's size (circles)
        for (int i = 0; i < dock.size(); i++) {
            View tile = tile(dock.get(i), true, dockD);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(tileW, -2);
            p.setMargins(i == 0 ? 0 : gap, 0, 0, 0);
            dockRow.addView(tile, p);
        }

        // the grid's cells: cols to a row, 20dp apart; a circle GRID_CIRCLE_DP across, smaller if a cell is narrower
        int cell = (getResources().getDisplayMetrics().widthPixels - 2 * dp(68) - (cols - 1) * dp(20)) / cols;
        int gridD = Math.min(dp(GRID_CIRCLE_DP), cell - dp(16));
        LinearLayout row = null;
        for (int i = 0; i < rest.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(rest.get(i), false, gridD);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest.size() % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        View kept = focusPkg == null ? null : cardByPkg.get(focusPkg);
        if (kept != null) lastFocusedId = kept.getId();
        View keptDock = dockPkg == null ? null : cardByPkg.get(dockPkg);
        if (keptDock != null && dockCards.contains(keptDock)) lastDockId = keptDock.getId();
        wireFocus();

        if (quick.isOpen()) return;   // the panel keeps the focus (e.g. a change over ADB while it is open)
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    /** the app of a tile's card (its tag), else null */
    private static String pkgOf(View v) {
        return v != null && v.getTag() instanceof App ? ((App) v.getTag()).pkg : null;
    }

    /** «شكل التطبيقات» or «شكل التحديد» changed (the screensaver app's settings, or ADB): the tiles again in the new
     *  look, the focus on the same app. HomePage read them with the page's settings. */
    private void applyStyle() {
        if (homePage.circles() == circles && homePage.apple() == apple) return;
        circles = homePage.circles();
        apple = homePage.apple();
        buildApps();
    }

    /**
     * An app's tile: a 16:9 banner with rounded corners (its name under it while focused), or («دوائر») its round icon
     * `circleD` across with its name always under it, dim, bright while focused. Focused, Google TV's look («شكل
     * التحديد»): 1.10 times, a white ring with a dark gap around it (Ring), the other tiles of its row or grid dimmed to
     * 70% (refreshDims); Apple TV's: 1.12 times and lifted, a soft shadow under it (Shadow), a sheen over it (Sheen).
     */
    private View tile(App app, boolean inDock, int circleD) {
        final Tile t = new Tile();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        // the card, with its shadow under it (Apple TV) or its ring over it (Google TV): the same size and place, scaled
        // and lifted with it, drawn outside their bounds (nothing up to the screen clips)
        FrameLayout frame = new FrameLayout(this);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);
        final float radius = dp(TILE_RADIUS_DP);
        if (apple) {
            t.shadow = shapeView();
            t.shadow.setBackground(new Shadow(radius, circles, circles ? CIRCLE_SHADOW : BANNER_SHADOW,
                    DESIGN_DP * getResources().getDisplayMetrics().density));
            t.shadow.setAlpha(0f);
            frame.addView(t.shadow, shapeParams(circleD));
        }

        FrameLayout card = circles ? new FrameLayout(this) : new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setDefaultFocusHighlightEnabled(false);   // the look below is the focus
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                if (circles) o.setOval(0, 0, v.getWidth(), v.getHeight());
                else o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);   // 20px at 1280 (was 11dp)
            }
        });
        if (circles) {
            card.addView(new CircleIcon(this, app.art, app.fullBleed), new FrameLayout.LayoutParams(-1, -1));
        } else {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
            card.setBackground(bg);
            ImageView img = new ImageView(this);
            img.setImageDrawable(app.art);
            if (app.isBanner) {
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            } else {
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                card.addView(img, new FrameLayout.LayoutParams(-1, -1));
                img.setPadding(dp(22), dp(14), dp(22), dp(14));
            }
        }
        if (apple) {   // the sheen, over the art and inside its shape
            t.sheen = new View(this);
            t.sheen.setBackground(new Sheen());
            t.sheen.setAlpha(0f);
            card.addView(t.sheen, new FrameLayout.LayoutParams(-1, -1));
        } else {       // a black veil that dims the tile while another one has the focus
            t.dim = new View(this);
            t.dim.setBackgroundColor(Color.BLACK);
            t.dim.setAlpha(0f);
            card.addView(t.dim, new FrameLayout.LayoutParams(-1, -1));
        }
        frame.addView(card, shapeParams(circleD));
        if (!apple) {
            t.ring = shapeView();
            t.ring.setBackground(new Ring(radius, circles, dp(RING_GAP_DP), dp(RING_DP)));
            t.ring.setAlpha(0f);
            frame.addView(t.ring, shapeParams(circleD));
        }
        box.addView(frame, circles ? new LinearLayout.LayoutParams(circleD, circleD) : new LinearLayout.LayoutParams(-1, -2));

        // the name: under a circle always (dim), under a banner while focused; clear of the scaled tile and its ring
        TextView label = text(circles ? 10 : 11, circles ? "sans-serif" : "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);   // a long name ends in «…»
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(dp(3), 0, dp(1), 0x99000000);   // readable on the glass and the photo
        label.setAlpha(circles ? NAME_DIM : 0f);
        float under = circles ? CIRCLE_LABEL_GAP_DP : inDock ? DOCK_LABEL_GAP_DP : GRID_LABEL_GAP_DP;
        box.addView(label, llp(-1, -2, 0, dp(under), 0, 0));
        t.card = card;
        t.label = label;
        tiles.put(card, t);

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(app.category)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnFocusChangeListener((v, has) -> {
            focusLook(t, has);
            v.post(this::refreshDims);   // once the focus has settled on its new tile
            if (!has) return;
            lastFocusedId = v.getId();
            if (inDock) {
                // DOWN from the home card returns to this dock card
                lastDockId = v.getId();
                homePage.cardView().setNextFocusDownId(lastDockId);
                shade(false);   // no title or glow over the page
                scroll.smoothScrollTo(0, 0);
            } else {
                shade(true);
                scrollToRow(box);
            }
        });

        // Long-press OK: add to / remove from / reorder the dock, right from the remote.
        card.setOnLongClickListener(v -> { showAppMenu(v, app.pkg, inDock); return true; });
        cardByPkg.put(app.pkg, card);

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** a view the card's size and place, for its ring or shadow: 16:9 at the frame's width, or the circle */
    private View shapeView() {
        return circles ? new View(this) : new AspectFrame(this);
    }

    private FrameLayout.LayoutParams shapeParams(int circleD) {
        return circles ? new FrameLayout.LayoutParams(circleD, circleD) : new FrameLayout.LayoutParams(-1, -2);
    }

    /** A tile gains or loses the focus: scale (and lift), its ring or shadow and sheen, its name. Eased out over
     *  FOCUS_MS, from wherever a running change has got to (a quick move interrupts it); Android's animator duration
     *  scale applies (ViewPropertyAnimator), 0 = at once. */
    private void focusLook(Tile t, boolean has) {
        float s = has ? (apple ? APPLE_SCALE : GOOGLE_SCALE) : 1f, y = has && apple ? -dp(APPLE_LIFT_DP) : 0f;
        ease(t.card.animate().scaleX(s).scaleY(s).translationY(y));
        if (t.ring != null) ease(t.ring.animate().scaleX(s).scaleY(s).translationY(y).alpha(has ? 1f : 0f));
        if (t.shadow != null) ease(t.shadow.animate().scaleX(s).scaleY(s).translationY(y).alpha(has ? 1f : 0f));
        if (t.sheen != null) ease(t.sheen.animate().alpha(has ? 1f : 0f));
        ease(t.label.animate().alpha(has ? 1f : circles ? NAME_DIM : 0f));
        if (circles) t.label.setTypeface(font(has ? "medium" : "regular"));   // the focused name bolder
    }

    private static void ease(android.view.ViewPropertyAnimator a) {
        a.setInterpolator(EASE_OUT).setDuration(FOCUS_MS).setStartDelay(0).start();
    }

    /** Google TV: while a tile has the focus, the others of its row (the dock) or of the grid are dimmed to 70%. */
    private void refreshDims() {
        dims(dockCards);
        dims(gridCards);
    }

    private void dims(List<View> cards) {
        View focused = null;
        for (View c : cards) if (c.isFocused()) focused = c;
        for (View c : cards) {
            Tile t = tiles.get(c);
            if (t == null || t.dim == null) continue;
            float want = focused != null && c != focused ? DIM : 0f;
            if (t.dimTo == want) continue;   // only the ones that change animate
            t.dimTo = want;
            ease(t.dim.animate().alpha(want));
        }
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        // the home card (when shown) sits between the pills and the dock
        View card = homePage.focusableCard();
        int dockMidId = d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : View.NO_ID;
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(card != null ? card.getId() : d > 0 ? dockMidId : p.getId());
        }
        int topId = pills.get(0).getId();
        if (card != null) {
            card.setNextFocusUpId(topId);
            card.setNextFocusDownId(d > 0 ? dockDownId() : card.getId());   // last focused dock card
            card.setNextFocusLeftId(card.getId());
            card.setNextFocusRightId(card.getId());
        }
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(card != null ? card.getId() : topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    /** the dock card that last had focus, else the first one (ids change when the dock is rebuilt). */
    private int dockDownId() {
        for (View c : dockCards) if (c.getId() == lastDockId) return lastDockId;
        return dockCards.isEmpty() ? View.NO_ID : dockCards.get(0).getId();
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + dockRow.getTop() - dp(16)   // the band sits in the hero itself
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    // ---------------------------------------------------------------- dock editing

    private final Map<String, View> cardByPkg = new HashMap<>();
    static final int MIN_DOCK = Dock.MIN, MAX_DOCK = Dock.MAX;

    /** The user's dock if they edited it, else res/values/config.xml. */
    /** The launcher's settings file, shared with DockReceiver. */
    private android.content.SharedPreferences prefs() { return Dock.prefs(this); }

    private String[] loadDock() {
        String saved = prefs().getString("dock", null);
        return saved != null ? (saved.isEmpty() ? new String[0] : saved.split(","))
                             : getResources().getStringArray(R.array.dock_apps);
    }

    /** Only installed apps are kept, so the dock never holds a removed app's slot. */
    private void saveDock(List<String> dock, String focusPkg) {
        List<String> installed = new ArrayList<>();
        for (String p : dock) if (isInstalled(p)) installed.add(p);
        prefs().edit().putString("dock", android.text.TextUtils.join(",", installed)).apply();
        dockApps = installed.toArray(new String[0]);
        buildApps();
        View c = cardByPkg.get(focusPkg);
        if (c != null) c.post(c::requestFocus);
    }

    private void showAppMenu(View anchor, String pkg, boolean inDock) {
        List<String> dock = new ArrayList<>();
        // The dock as shown, including apps added automatically to reach MIN_DOCK.
        for (View c : dockCards) for (Map.Entry<String, View> e : cardByPkg.entrySet()) if (e.getValue() == c) dock.add(e.getKey());
        int i = dock.indexOf(pkg);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("ترتيب المفضلة…");
        actions.add(() -> launch(new Intent(this, DockActivity.class)));
        if (inDock) {
            if (dock.size() > MIN_DOCK) {
                labels.add("إزالة من المفضلة");
                actions.add(() -> { dock.remove(pkg); saveDock(dock, dock.get(Math.max(0, i - 1))); });
            } else {
                labels.add("المفضلة لا تقل عن " + arabicDigits(String.valueOf(MIN_DOCK)));
                actions.add(() -> { });
            }
        } else if (dock.size() < MAX_DOCK) {
            labels.add("إضافة إلى المفضلة");
            actions.add(() -> { dock.add(pkg); saveDock(dock, pkg); });
        } else {
            labels.add("المفضلة ممتلئة (" + arabicDigits(String.valueOf(MAX_DOCK)) + ")");
            actions.add(() -> { });
        }
        labels.add("إعادة المفضلة الافتراضية");
        actions.add(() -> {
            prefs().edit().remove("dock").apply();
            dockApps = getResources().getStringArray(R.array.dock_apps);
            buildApps();
        });
        showMenu(anchor, labels, actions);
    }

    /** Small focusable popup in the same style as the Inputs menu. */
    private void showMenu(View anchor, List<String> labels, List<Runnable> actions) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);
        PopupWindow pw = new PopupWindow(list, dp(230), -2, true);
        List<TextView> rows = new ArrayList<>();
        for (int k = 0; k < labels.size(); k++) rows.add(inputRow(labels.get(k), actions.get(k)));
        for (TextView r : rows) {
            r.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int k = 0; k < rows.size(); k++) {
            TextView r = rows.get(k);
            r.setNextFocusUpId(rows.get(Math.max(0, k - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, k + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.showAsDropDown(anchor, 0, -anchor.getHeight() - dp(8) * (rows.size() + 2) - dp(36) * rows.size(), Gravity.START);
        rows.get(0).requestFocus();
    }

    /** the Inputs menu under the «المصادر» pill (the quick-settings panel's tile) */
    void showInputsMenu() {
        if (!pills.isEmpty()) showInputs(pills.get(0));
    }

    /** the quick-settings panel's focus goes back here when what had it before is gone */
    View settingsPill() { return settingsPill; }

    /** the quick-settings panel opened or closed: the page holds still under it, as under the grid */
    void quickShown(boolean on) {
        homePage.setAppsOpen(on || gridOpen);
    }

    private void showInputs(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("البث التلفزيوني", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("كل المصادر…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("الإعدادات", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    static String arabicDigits(String s) {   // also QuickPanel's clock: the digits as «شكل الأرقام» says (Numerals)
        return Numerals.digits(s);
    }

    /** Connected or not comes from ConnectivityManager: WifiManager hides the network (id -1, "<unknown ssid>")
     *  from apps without location access, which looked like "not connected". The name only when readable. */
    private void updateWifi() {
        String name = "غير متصل";
        try {
            android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                name = "شبكة سلكية";
            } else if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                name = "واي فاي";
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                String s = wm == null || wm.getConnectionInfo() == null ? null : wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid") && !s.replace("\"", "").isEmpty()) name = s.replace("\"", "");
            }
        } catch (SecurityException ignored) { }
        wifiPill.setText(name);
        quick.setWifi(name);   // the panel's Wi-Fi tile too
    }

    // ---------------------------------------------------------------- shade /** the apps grid scrolls up over the page; the page is dimmed under it (was the glow's fade). */
    private void shade(boolean on) {
        gridShade.animate().alpha(on ? 1f : 0f).setDuration(250).start();
        gridOpen = on;
        // under the grid only the page's blurred, darker photo, held still (under the panel too)
        homePage.setAppsOpen(on || quick.isOpen());
    }

    /** An older (not adaptive) icon whose four corners are opaque: a full square picture, which fills a circle as
     *  well; the others (a shape on transparency) sit on a disc. */
    private static boolean fullBleed(Drawable d) {
        try {
            Bitmap bm = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 32, 32);
            d.draw(new Canvas(bm));
            boolean full = true;
            for (int[] p : new int[][]{{1, 1}, {30, 1}, {1, 30}, {30, 30}}) full &= Color.alpha(bm.getPixel(p[0], p[1])) > 240;
            bm.recycle();
            return full;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = pillRow;   // the pills (the page fades itself in)
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    boolean launch(Intent i) {   // also QuickPanel (was private)
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    static final int GOLD = 0xFFD6AF6B;
    // glass as the page's card: rgba(4,14,9,.60) over the photo, a 1px gold edge rgba(220,184,111,.16)
    static final int GLASS = 0x99040E09, GLASS_EDGE = 0x29DCB86F;
    // ---- the app tiles, from the approved mock-ups (design/focus/gen2.js: CSS px of a 1280-wide screen, DESIGN_DP dp
    // each). The dock band is HomePage.DOCK_H_DP tall (104: 138px); in it, for either shape and focus look, the
    // focused tile with its ring or lift fits under its top edge and its name clear under the tile, inside the band.
    static final float DESIGN_DP = 0.75f;
    // the band's side padding and corners; «زوايا دائرية»: banners 58 tall (16:9), 16px from the band's top, 16px
    // apart, the name 16px under them (grid: 14dp, clear of a larger banner's ring), 20px corners (were 11dp)
    static final int DOCK_PAD_DP = 14, DOCK_TILE_H_DP = 58, DOCK_RADIUS_DP = 18;
    static final float DOCK_TOP_DP = 12f, DOCK_GAP_DP = 12f, DOCK_LABEL_GAP_DP = 12f, GRID_LABEL_GAP_DP = 14f;
    static final float TILE_RADIUS_DP = 15f;
    // «دوائر»: 84px icons in 104px slots 6px apart, 14px from the band's top; the names 12dp under them (the mock-up's
    // 10px touches the ring), 13px, 55% white, bright while focused; in the grid 7 to a row, 76dp across
    static final float CIRCLE_DP = 63f, CIRCLE_SLOT_DP = 78f, CIRCLE_GAP_DP = 4.5f, CIRCLE_TOP_DP = 10.5f;
    static final float CIRCLE_LABEL_GAP_DP = 12f, GRID_CIRCLE_DP = 76f, NAME_DIM = 0.55f;
    static final int ICON_DISC = 0xFF2C2E33;   // the subtle disc under an icon that doesn't fill its circle
    static final int GRID_CIRCLE_COLS = 7;
    // «شكل التحديد» Google TV: 1.10 times, a 3px dark gap then a 3px white ring, the other tiles at 70% (a 30% black
    // veil); Apple TV: 1.12 times, 3px up, two shadows {y, blur, spread, alpha} in px (rounded, circle), the sheen
    static final float GOOGLE_SCALE = 1.10f, APPLE_SCALE = 1.12f, APPLE_LIFT_DP = 3 * DESIGN_DP, DIM = 0.3f;
    static final float RING_GAP_DP = 3 * DESIGN_DP, RING_DP = 3 * DESIGN_DP;
    static final int RING_GAP_COLOR = 0xFF08090B, RING_COLOR = Color.WHITE;
    static final float[][] BANNER_SHADOW = {{18, 28, -6, .75f}, {6, 12, 0, .4f}}, CIRCLE_SHADOW = {{16, 24, -6, .8f}, {6, 12, 0, .45f}};
    // focus and blur: 180 ms, eased out (the brief: 150 to 200)
    static final int FOCUS_MS = 180;
    private static final android.animation.TimeInterpolator EASE_OUT = new DecelerateInterpolator();
    // the dock band's glass (GlassBand): the page's dark tint, lighter than the card's .60; a sheen from
    // the top; a highlight on its top edge that fades down its sides
    static final int BAND_TINT = 0x6B040E09, BAND_SHEEN_TOP = 0x14FFFFFF, BAND_SHEEN_BOTTOM = 0x05FFFFFF, BAND_RIM = 0x47FFFFFF;
    static final String SAVER_PKG = "com.raad.sauditv.screensaver";
    private final Map<String, Typeface> fonts = new HashMap<>();

    /** Thmanyah from assets/fonts; any old family name maps to the nearest weight. */
    private Typeface font(String family) {
        String file = family.contains("display") ? "ThmanyahDisplay-Bold"
                : family.contains("thin") || family.contains("light") ? "Thmanyah-Light"
                : family.contains("medium") ? "Thmanyah-Medium" : "Thmanyah-Regular";
        Typeface tf = fonts.get(file);
        if (tf == null) {
            try { tf = Typeface.createFromAsset(getAssets(), "fonts/" + file + ".otf"); }
            catch (RuntimeException e) { tf = Typeface.create("sans-serif", Typeface.NORMAL); }
            fonts.put(file, tf);
        }
        return tf;
    }

    TextView text(float sp, String family, int color) {   // also QuickPanel (was private)
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(font(family));
        t.setTextColor(color);
        return t;
    }

    int dp(float v) {   // also QuickPanel (was private)
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** the page's side margin in pixels (3.6vw), so the pills and the dock line up with its text */
    private int side() {
        return Math.round(getResources().getDisplayMetrics().widthPixels * HomePage.SIDE);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // the remote's gear key opens or closes the quick-settings panel. Only where the TV lets the key
        // through: Hisense (MediaTek) sets hand it to their global-key receiver (TV settings), the Google TV Streamer takes it itself.
        if (e.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) {
            if (e.getAction() == KeyEvent.ACTION_UP) quick.toggle();
            return true;
        }
        // Menu key on the remote opens the Inputs dropdown.
        // held (a long press), it opens or closes the quick-settings panel instead
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) menuLong = false;
                else if (e.isLongPress() && !menuLong) { menuLong = true; quick.toggle(); }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (!menuLong && !quick.isOpen() && !pills.isEmpty()) showInputs(pills.get(0));
                menuLong = false;
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** the dock band's glass. No blur (the photo is the page's, in another view): the page's dark tint,
     *  lighter than its card's; a white sheen fading down; a 1px highlight on the top edge that fades out halfway down
     *  the sides. No border. */
    static final class GlassBand extends Drawable {
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG),
                rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF(), edge = new RectF();
        private final float radius;

        GlassBand(float radius, float line) {
            this.radius = radius;
            tint.setColor(BAND_TINT);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(line);
        }

        @Override protected void onBoundsChange(Rect b) {
            float h = rim.getStrokeWidth() / 2f;
            box.set(b);
            edge.set(b.left + h, b.top + h, b.right - h, b.bottom - h);
            sheen.setShader(new LinearGradient(0, b.top, 0, b.bottom, BAND_SHEEN_TOP, BAND_SHEEN_BOTTOM, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * .55f, BAND_RIM, 0x00FFFFFF, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            float h = rim.getStrokeWidth() / 2f;
            c.drawRoundRect(box, radius, radius, tint);
            c.drawRoundRect(box, radius, radius, sheen);
            c.drawRoundRect(edge, radius - h, radius - h, rim);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** one app tile's views, for its focus look (focusLook, refreshDims) */
    static final class Tile {
        View card, ring, shadow, sheen, dim;   // ring and dim: Google TV; shadow and sheen: Apple TV
        TextView label;
        float dimTo;                           // the veil's alpha it is heading to
    }

    /** Google TV's focus ring: a dark gap around the tile, then a white ring, following its shape (its corner radius
     *  grown with the ring, or a circle); drawn outside the view's bounds. The gap starts 1px inside the tile's edge,
     *  over its anti-aliased rim. */
    static final class Ring extends Drawable {
        private final Paint gapPaint = new Paint(Paint.ANTI_ALIAS_FLAG), ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float radius, gap, width;
        private final boolean oval;

        Ring(float radius, boolean oval, float gap, float width) {
            this.radius = radius;
            this.oval = oval;
            this.gap = gap;
            this.width = width;
            gapPaint.setStyle(Paint.Style.STROKE);
            gapPaint.setStrokeWidth(gap + 1);
            gapPaint.setColor(RING_GAP_COLOR);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(width);
            ringPaint.setColor(RING_COLOR);
        }

        @Override public void draw(Canvas c) {
            stroke(c, gapPaint, (gap - 1) / 2f);
            stroke(c, ringPaint, gap + width / 2f);
        }

        /** a stroke centred `out` px outside the bounds */
        private void stroke(Canvas c, Paint p, float out) {
            Rect b = getBounds();
            r.set(b.left - out, b.top - out, b.right + out, b.bottom + out);
            if (oval) c.drawOval(r, p);
            else c.drawRoundRect(r, radius + out, radius + out, p);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Apple TV's shadow under a focused tile: soft shadows of its shape (the mock-up's box-shadow layers, {y, blur,
     *  spread, alpha} in CSS px, `k` px each), blurred once in software into a bitmap per size, shared by the tiles of
     *  that size; drawn outside the view's bounds. */
    static final class Shadow extends Drawable {
        private static final Map<String, Bitmap> CACHE = new HashMap<>();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final float radius, k;
        private final boolean oval;
        private final float[][] layers;
        private Bitmap bm;
        private int pad;

        Shadow(float radius, boolean oval, float[][] layers, float k) {
            this.radius = radius;
            this.oval = oval;
            this.layers = layers;
            this.k = k;
        }

        /** the bitmaps of tiles that are gone */
        static void forget() { CACHE.clear(); }

        @Override protected void onBoundsChange(Rect b) { bm = null; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            if (bm == null) bm = bitmap(b.width(), b.height());
            c.drawBitmap(bm, b.left - pad, b.top - pad, paint);
        }

        private Bitmap bitmap(int w, int h) {
            float most = 0;
            for (float[] l : layers) most = Math.max(most, (Math.abs(l[0]) + 1.5f * l[1]) * k);   // a blur reaches 3 sigma
            pad = (int) Math.ceil(most);
            String key = w + "x" + h + (oval ? "o" : "r" + radius) + "k" + k;
            Bitmap cached = CACHE.get(key);
            if (cached != null) return cached;
            Bitmap out = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            RectF r = new RectF();
            for (float[] l : layers) {
                float dy = l[0] * k, sigma = l[1] * k / 2f, in = -l[2] * k;   // CSS blur = 2 sigma; a negative spread shrinks
                p.setColor(Color.argb(Math.round(l[3] * 255), 0, 0, 0));
                // Skia's blur radius: sigma = 0.57735 radius + 0.5
                p.setMaskFilter(sigma > 0.5f ? new android.graphics.BlurMaskFilter((sigma - 0.5f) / 0.57735f,
                        android.graphics.BlurMaskFilter.Blur.NORMAL) : null);
                r.set(pad + in, pad + in + dy, pad + w - in, pad + h - in + dy);
                if (oval) c.drawOval(r, p);
                else c.drawRoundRect(r, Math.max(0, radius - in), Math.max(0, radius - in), p);
            }
            CACHE.put(key, out);
            return out;
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Apple TV's sheen over a focused tile: linear-gradient(125deg, white .28 0%, .08 38%, 0 55%), as CSS draws it */
    static final class Sheen extends Drawable {
        private final Paint p = new Paint();

        @Override protected void onBoundsChange(Rect b) {
            double a = Math.toRadians(125);   // CSS angles: 0 points up, 90 right
            float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            float half = (Math.abs(b.width() * dx) + Math.abs(b.height() * dy)) / 2f;
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            p.setShader(new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                    new int[]{0x47FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, new float[]{0f, .38f, .55f}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) { c.drawRect(getBounds(), p); }
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** «دوائر»: an app's icon in its circle (the card clips to it), on a subtle disc. An adaptive icon's two layers fill
     *  it as Android shows them (the middle 72 of 108dp: the layers 1.5 times the circle); an older icon fills it when
     *  it is a full square picture (fullBleed), else it sits in the middle, 0.6 of the circle. */
    static final class CircleIcon extends View {
        private final Drawable icon;
        private final boolean fill;

        CircleIcon(Context c, Drawable icon, boolean fill) {
            super(c);
            this.icon = icon;
            this.fill = fill;
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            c.drawColor(ICON_DISC);
            if (fill && icon != null) {
                icon.setBounds(0, 0, w, h);
                icon.draw(c);
            } else if (icon instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
                int in = w / 4;
                for (Drawable l : new Drawable[]{a.getBackground(), a.getForeground()}) {
                    if (l == null) continue;
                    l.setBounds(-in, -in, w + in, h + in);
                    l.draw(c);
                }
            } else {
                if (icon == null) return;
                int s = Math.round(w * 0.6f), l = (w - s) / 2, t = (h - s) / 2;
                icon.setBounds(l, t, l + s, t + s);
                icon.draw(c);
            }
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
