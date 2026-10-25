package com.raad.sauditv.home;

import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * the quick-settings panel, like Google TV's: a full-height glass sheet that slides in from the right
 * edge (where these TVs' own settings panels open, and over the page's clock, so it shows the time and the VPN
 * mark itself) over the dimmed home screen, with shortcut tiles in two columns (Arabic order: the first on the
 * right). Opened by the «الإعدادات» pill, the remote's gear key and a long press of Menu; Back slides it out, Home
 * closes it. While it is open the D-pad stays in it (the home screen's views are blocked) and the page shows only
 * its blurred photo and holds its ticker and card (sauditvAppsOpen, as under the apps grid).
 *
 * Each tile opens the most specific screen this TV has, else the general settings. Settings tiles leave the panel
 * open, so Back from there comes back to it (as on Google TV); the screensaver, the VPN app and the inputs close it.
 */
final class QuickPanel {
    static final int WIDTH_DP = 400, SHADOW_DP = 28, PAD_DP = 24, GAP_DP = 8, TILE_RADIUS_DP = 14;
    // the sheet: the page's dark green-black, all but opaque (the dimmed dock would show through), a faint sheen from
    // the top, a gold line on its inner edge
    static final int PANEL_TINT = 0xFA0A1610, PANEL_SHEEN_TOP = 0x12FFFFFF, PANEL_SHEEN_BOTTOM = 0x03FFFFFF,
            PANEL_RIM = 0x66DCB86F, DIM = 0x8C000000;
    // tiles: faint glass with a thin edge; focused, white with dark text, like the pills
    static final int TILE_BG = 0x12FFFFFF, TILE_EDGE = 0x1CFFFFFF, TILE_FOCUSED = 0xF2FFFFFF,
            INK = 0xF2FFFFFF, INK_DIM = 0x99FFFFFF, INK_FOCUSED = 0xFF111111, INK_FOCUSED_DIM = 0x99111111;
    // the page's VPN mark colours: working, reported but nothing gets through, none
    static final int VPN_OK = 0xFF34C759, VPN_STUCK = 0xFFFF9F0A, VPN_OFF = 0xFFFF453A;

    static final String VPN_PKG = "com.surfshark.vpnclient.android";
    static final String TV_SETTINGS = "com.android.tv.settings";
    /** Hisense / MediaTek: «Device Preferences», Picture and Sound, as a side panel */
    static final String ACTION_PICTURE = "android.settings.PQAQ.SETTINGS";
    /** Google TV's energy modes (only where the TV has low-power standby: else the screen closes at once) */
    static final String ACTION_ENERGY = "com.google.android.tv.settings.energymodes";
    /** Google TV's «sleep» voice action: starts the screensaver now (Somnambulator isn't on these TVs) */
    static final String ACTION_SCREENSAVER = "com.google.android.pano.action.SLEEP";
    /** The TV settings' «Pair accessory» (Bluetooth search): no activity answers BLUETOOTH_SETTINGS on Google TV, and
     *  «Remotes & Accessories» itself is a slice screen apps can't open (it needs WRITE_SECURE_SETTINGS). Opening it
     *  needs BLUETOOTH_CONNECT (granted over ADB at install, optimize/install.sh). */
    static final String ACTION_ACCESSORIES = "com.google.android.intent.action.CONNECT_INPUT";

    private final MainActivity a;
    private final ViewGroup home;   // the home screen's views (pills, card, dock, grid): no focus while open
    private final FrameLayout layer;   // the dim and the sheet, over everything
    private final View dim;
    private final FrameLayout slider;   // the sheet and its shadow, slid in from the right
    private final List<View> tiles = new ArrayList<>();
    private final TextView clock, vpnMark, vpnDot;
    private TextView wifiSub, vpnSub, saudiSub, radioSub;
    private boolean update;   // the screensaver app found a newer release: «تحديث متوفر» under «سعودي TV»
    private View before, lastTile;   // the focus before it opened; the tile that had it last
    private int homeFocus;
    private boolean open, closeWhenHidden;

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); layer.postDelayed(this, 3000); }
    };

    QuickPanel(MainActivity a, FrameLayout root, ViewGroup home) {
        this.a = a;
        this.home = home;
        layer = new FrameLayout(a);
        layer.setVisibility(View.GONE);
        layer.setClipChildren(false);
        dim = new View(a);
        dim.setBackgroundColor(DIM);
        dim.setOnClickListener(v -> close(true));   // touch or a mouse outside the sheet
        layer.addView(dim, new FrameLayout.LayoutParams(-1, -1));

        slider = new FrameLayout(a);
        slider.setClipChildren(false);
        View shadow = new View(a);
        shadow.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0x66000000, 0x00000000}));
        slider.addView(shadow, new FrameLayout.LayoutParams(a.dp(SHADOW_DP), -1, Gravity.LEFT));

        LinearLayout sheet = new LinearLayout(a);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);
        sheet.setPadding(a.dp(PAD_DP), a.dp(22), a.dp(PAD_DP), a.dp(18));
        sheet.setBackground(new GlassPanel(a.dp(1)));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(a.dp(WIDTH_DP), -1, Gravity.RIGHT);
        slider.addView(sheet, sp);
        layer.addView(slider, new FrameLayout.LayoutParams(a.dp(WIDTH_DP + SHADOW_DP), -1, Gravity.RIGHT));

        // header, one row: the title on the right; on the left the time (the panel covers the page's clock) and the
        // VPN mark beside it, as the page has them
        LinearLayout header = new LinearLayout(a);
        header.setGravity(Gravity.CENTER_VERTICAL);
        vpnMark = a.text(11, "sans-serif-medium", INK_DIM);
        vpnMark.setIncludeFontPadding(false);
        TextView dot = a.text(11, "sans-serif", 0x59FFFFFF);
        dot.setText("  ·  ");
        vpnDot = dot;
        clock = a.text(11, "sans-serif-medium", MainActivity.GOLD);
        clock.setIncludeFontPadding(false);
        TextView title = a.text(18, "display", 0xFFFFFFFF);
        title.setText("الإعدادات السريعة");
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        header.addView(vpnMark);   // left of the time: Arabic order, the time first
        header.addView(dot);
        header.addView(clock);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        sheet.addView(header, new LinearLayout.LayoutParams(-1, -2));

        View line = new View(a);
        line.setBackgroundColor(MainActivity.GLASS_EDGE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(1));
        lp.setMargins(0, a.dp(14), 0, a.dp(14));
        sheet.addView(line, lp);

        LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        sheet.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        View wifi = tile("واي فاي", R.drawable.ic_wifi, () -> settings(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        wifiSub = sub(wifi);
        tile("بلوتوث", R.drawable.ic_bluetooth, () -> settings(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                new Intent(ACTION_ACCESSORIES)));
        tile("الصورة", R.drawable.ic_picture, () -> settings(new Intent(ACTION_PICTURE),
                new Intent(Settings.ACTION_DISPLAY_SETTINGS), tvSettings(".device.displaysound.DisplaySoundActivity")));
        tile("الصوت", R.drawable.ic_sound, () -> settings(new Intent(Settings.ACTION_SOUND_SETTINGS)));
        tile("المصادر", R.drawable.ic_input, () -> { close(true); a.showInputsMenu(); });
        tile("شاشة التوقف", R.drawable.ic_screensaver, () -> leave(new Intent(ACTION_SCREENSAVER),
                new Intent().setClassName(MainActivity.SAVER_PKG, MainActivity.SAVER_PKG + ".MainActivity")));
        View vpn = tile("VPN", R.drawable.ic_vpn, () -> leave(launchIntent(VPN_PKG)));
        vpnSub = sub(vpn);
        tile("الطاقة", R.drawable.ic_power, () -> settings(energyModes() ? new Intent(ACTION_ENERGY) : null));
        // the explicit screen first: on Hisense sets DATE_SETTINGS also matches its power-off timer (a chooser)
        tile("التاريخ والوقت", R.drawable.ic_time, () -> settings(tvSettings(".system.DateTimeActivity"),
                new Intent(Settings.ACTION_DATE_SETTINGS)));
        tile("الحساب", R.drawable.ic_account, () -> settings(new Intent(Settings.ACTION_SYNC_SETTINGS),
                new Intent(Settings.ACTION_ADD_ACCOUNT)));
        // with an update available it opens the update screen, as the «تحديث متوفر» pill does
        View saudi = tile("سعودي TV", R.drawable.ic_tune, () -> {
            if (update) a.openUpdate();
            else settings(new Intent().setClassName(MainActivity.SAVER_PKG, MainActivity.SAVER_PKG + ".SettingsActivity"));
        });
        saudiSub = sub(saudi);
        // «إذاعة القرآن الكريم» on or off (the screensaver app's setting; it plays while the home screen is in front)
        View radio = tile("إذاعة القرآن", R.drawable.ic_radio, this::toggleRadio);
        radioSub = sub(radio);
        tile("كل الإعدادات", R.drawable.ic_settings, () -> settings());

        // two per row, the first on the right; rows share the sheet's height
        int rows = (tiles.size() + 1) / 2;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(a);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 0, 1f);
            rp.topMargin = r == 0 ? 0 : a.dp(GAP_DP);
            grid.addView(row, rp);
            for (int c = 1; c >= 0; c--) {
                int i = r * 2 + c;
                View t = i < tiles.size() ? tiles.get(i) : new View(a);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1f);
                tp.leftMargin = c == 0 ? a.dp(GAP_DP) : 0;
                row.addView(t, tp);
            }
        }
        wire();
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));
    }

    boolean isOpen() { return open; }

    void toggle() { if (open) close(true); else open(); }

    /** Slides in; the focus goes to the tile that had it last (the first one the first time). */
    void open() {
        if (open) return;
        open = true;
        closeWhenHidden = false;
        before = a.getCurrentFocus();
        refresh();
        layer.setVisibility(View.VISIBLE);
        layer.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        float w = a.dp(WIDTH_DP + SHADOW_DP);
        slider.animate().cancel();
        slider.setTranslationX(w);
        slider.animate().translationX(0).setInterpolator(new DecelerateInterpolator(2.2f)).setStartDelay(0)
                .setDuration(320).start();
        dim.animate().cancel();
        dim.setAlpha(0f);
        dim.animate().alpha(1f).setStartDelay(0).setDuration(260).start();
        // the tiles follow the sheet in, row by row
        for (int i = 0; i < tiles.size(); i++) {
            View t = tiles.get(i);
            t.setAlpha(0f);
            t.setTranslationX(a.dp(18));
            t.animate().alpha(1f).translationX(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setStartDelay(90 + (i / 2) * 30L).setDuration(280).start();
        }
        focus();
        homeFocus = home.getDescendantFocusability();
        home.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);   // after the focus left it
        a.quickShown(true);
        layer.removeCallbacks(tick);
        layer.postDelayed(tick, 3000);
    }

    /** Slides out (or just goes, when the home screen isn't in view) and gives the focus back. */
    void close(boolean animate) {
        if (!open) return;
        open = false;
        closeWhenHidden = false;
        layer.removeCallbacks(tick);
        home.setDescendantFocusability(homeFocus);
        a.quickShown(false);
        View back = before != null && before.isShown() && before.isFocusable() ? before : a.settingsPill();
        if (back != null) back.requestFocus();
        layer.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);   // no D-pad into it while it slides out
        if (animate) {
            slider.animate().translationX(a.dp(WIDTH_DP + SHADOW_DP)).setInterpolator(new AccelerateInterpolator(1.6f))
                    .setStartDelay(0).setDuration(220).withEndAction(() -> { if (!open) layer.setVisibility(View.GONE); })
                    .start();
            dim.animate().alpha(0f).setStartDelay(0).setDuration(200).start();
        } else {
            slider.animate().cancel();
            dim.animate().cancel();
            layer.setVisibility(View.GONE);
        }
    }

    /** onResume: the time, the VPN and the focus again (Back from a settings screen lands here). */
    void resume() {
        if (!open) return;
        refresh();
        focus();
        layer.removeCallbacks(tick);
        layer.postDelayed(tick, 3000);
    }

    /** onPause: no refreshing while another app is in front. */
    void pause() { layer.removeCallbacks(tick); }

    /** onStop: a tile that left for the screensaver or the VPN app closes the panel behind it. */
    void stop() { if (closeWhenHidden) close(false); }

    /** The Wi-Fi tile shows the network's name (or «غير متصل»), as the pill does; «متصل» when Android hides it. */
    void setWifi(String name) {
        wifiSub.setText("واي فاي".equals(name) ? "متصل" : name);
        wifiSub.setVisibility(View.VISIBLE);
    }

    /** «تحديث متوفر» under the «سعودي TV» tile, as the home screen's pill. */
    void setUpdate(boolean on) {
        update = on;
        saudiSub.setText("تحديث متوفر");
        saudiSub.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    void focus() {
        View f = lastTile != null ? lastTile : tiles.get(0);
        f.requestFocus();
    }

    /** the radio tile, as the home screen's «تشغيل القرآن» / «إيقاف القرآن» (RadioLink.toggle): playing, it turns it off;
     *  else on and playing now; its second line says which, at once, and so does the chip */
    private void toggleRadio() {
        if (!RadioLink.toggle(a)) { radioSub.setText("غير متاحة"); radioSub.setVisibility(View.VISIBLE); return; }
        refreshRadio();
        a.radioChanged();
    }

    /** «تعمل» / «يُتلى الآن» / «متوقفة» under the radio tile (nothing when the screensaver app can't be reached) */
    private void refreshRadio() {
        String[] r = RadioLink.read(a);
        if (r == null) { radioSub.setVisibility(View.GONE); return; }
        boolean on = "1".equals(r[0]);
        radioSub.setText(!on ? "متوقفة" : "playing".equals(r[1]) ? "يُتلى الآن" : "تعمل");
        radioSub.setVisibility(View.VISIBLE);
    }

    /** The time (as the page writes it: Arabic digits, ص / م) and the VPN mark: ✓ and the exit country's flag,
     *  ! (reported but nothing gets through) or ✕; the VPN tile says the same in words. */
    private void refresh() {
        Calendar now = Calendar.getInstance();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(a);
        int h = now.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && h == 0) h = 12;
        String t = MainActivity.arabicDigits(h + ":" + String.format(Locale.US, "%02d", now.get(Calendar.MINUTE)));
        clock.setText(h24 ? t : t + (now.get(Calendar.AM_PM) == Calendar.AM ? " ص" : " م"));
        refreshRadio();

        // no VPN app on the TV at all: no VPN mark, no flag, nothing under the VPN tile (an app that is off: ✕)
        boolean app = VpnStatus.appInstalled(a);
        vpnMark.setVisibility(app ? View.VISIBLE : View.GONE);
        vpnDot.setVisibility(app ? View.VISIBLE : View.GONE);
        if (!app) { vpnSub.setVisibility(View.GONE); return; }
        int st = VpnStatus.state(a);
        String flag = VpnStatus.flag();
        String mark = st == 1 ? "✓" : st == 2 ? "!" : "✕";
        SpannableStringBuilder b = new SpannableStringBuilder("VPN ");
        b.append(mark, new ForegroundColorSpan(st == 1 ? VPN_OK : st == 2 ? VPN_STUCK : VPN_OFF), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (st == 1 && !flag.isEmpty()) b.append(" ").append(flag);
        vpnMark.setText(b);
        vpnSub.setText(st == 1 ? ("✓ يعمل" + (flag.isEmpty() ? "" : " " + flag)) : st == 2 ? "! بلا إنترنت" : "غير متصل");
        vpnSub.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- tiles

    /** A tile: its icon on top, its name under it, and a small second line when there is one (Wi-Fi, VPN). */
    private View tile(String label, int icon, Runnable action) {
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setId(View.generateViewId());
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(a.dp(8), a.dp(2), a.dp(8), a.dp(2));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(a.dp(TILE_RADIUS_DP));
        bg.setColor(TILE_BG);
        bg.setStroke(a.dp(1), TILE_EDGE);
        t.setBackground(bg);

        Drawable d = a.getDrawable(icon).mutate();
        d.setTint(INK);
        ImageView ic = new ImageView(a);
        ic.setImageDrawable(d);
        t.addView(ic, new LinearLayout.LayoutParams(a.dp(20), a.dp(20)));

        TextView name = a.text(12, "sans-serif-medium", INK);
        name.setText(label);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setIncludeFontPadding(false);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = a.dp(5);
        t.addView(name, np);

        TextView sub = a.text(9, "sans-serif", INK_DIM);
        sub.setGravity(Gravity.CENTER);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setIncludeFontPadding(false);
        sub.setVisibility(View.GONE);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1, -2);
        sbp.topMargin = a.dp(2);
        t.addView(sub, sbp);

        t.setOnClickListener(v -> action.run());
        t.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? TILE_FOCUSED : TILE_BG);
            bg.setStroke(a.dp(1), has ? 0x00FFFFFF : TILE_EDGE);
            d.setTint(has ? INK_FOCUSED : INK);
            name.setTextColor(has ? INK_FOCUSED : INK);
            sub.setTextColor(has ? INK_FOCUSED_DIM : INK_DIM);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setStartDelay(0)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has) lastTile = v;
        });
        tiles.add(t);
        return t;
    }

    private static TextView sub(View tile) { return (TextView) ((ViewGroup) tile).getChildAt(2); }

    /** Explicit D-pad targets inside the grid; its edges keep the focus (it never leaves the panel). */
    private void wire() {
        int n = tiles.size(), lastRow = (n - 1) / 2;
        for (int i = 0; i < n; i++) {
            View t = tiles.get(i);
            int r = i / 2, c = i % 2;   // c 0: the right column
            t.setNextFocusUpId(r == 0 ? t.getId() : tiles.get(i - 2).getId());
            t.setNextFocusDownId(i + 2 < n ? tiles.get(i + 2).getId() : r < lastRow ? tiles.get(n - 1).getId() : t.getId());
            t.setNextFocusLeftId(c == 0 && i + 1 < n ? tiles.get(i + 1).getId() : t.getId());
            t.setNextFocusRightId(c == 1 ? tiles.get(i - 1).getId() : t.getId());
        }
    }

    // ---------------------------------------------------------------- what the tiles open

    /** The first of these this TV opens, else the general settings; the panel stays open behind it. */
    private void settings(Intent... tries) {
        for (Intent i : tries) if (i != null && a.launch(i)) return;
        a.launch(new Intent(Settings.ACTION_SETTINGS));
    }

    /** As settings(), for a tile that leaves the settings: the panel closes once the home screen is out of view. */
    private void leave(Intent... tries) {
        closeWhenHidden = true;
        for (Intent i : tries) if (i != null && a.launch(i)) return;
        closeWhenHidden = false;
        settings();
    }

    private static Intent tvSettings(String cls) {
        return new Intent().setClassName(TV_SETTINGS, TV_SETTINGS + cls);
    }

    private Intent launchIntent(String pkg) {
        Intent i = a.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : a.getPackageManager().getLaunchIntentForPackage(pkg);
    }

    /** Energy modes only open where the TV supports low-power standby (Hisense sets; not the Google TV Streamer). */
    private static boolean energyModes() {
        if (Build.VERSION.SDK_INT < 33) return false;
        try {
            int id = Resources.getSystem().getIdentifier("config_lowPowerStandbySupported", "bool", "android");
            return id != 0 && Resources.getSystem().getBoolean(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The sheet's glass: the page's dark tint, a faint sheen fading down, a gold line on the inner (left) edge that
     *  fades out towards the top and the bottom. */
    static final class GlassPanel extends Drawable {
        private final Paint tint = new Paint(), sheen = new Paint(), rim = new Paint();
        private final float line;

        GlassPanel(float line) {
            this.line = line;
            tint.setColor(PANEL_TINT);
        }

        @Override protected void onBoundsChange(Rect b) {
            sheen.setShader(new LinearGradient(0, b.top, 0, b.bottom, PANEL_SHEEN_TOP, PANEL_SHEEN_BOTTOM, Shader.TileMode.CLAMP));
            rim.setShader(new LinearGradient(0, b.top, 0, b.bottom, new int[]{0x00DCB86F, PANEL_RIM, 0x00DCB86F},
                    new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            c.drawRect(b, tint);
            c.drawRect(b, sheen);
            c.drawRect(b.left, b.top, b.left + line, b.bottom, rim);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
