package com.raad.sauditv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * the home screen's page. The screensaver's page in home mode (tv.html#...&home=1) in one full-screen
 * WebView behind the launcher's own views: it draws the photo, the dates, the weather, the clock with the VPN badge,
 * the rotating card, the info strip under the apps row (next prayer, Ramadan, next match) and the moving market
 * ticker. The launcher's pills, apps row and apps grid sit on top, where the page leaves room (PILLS_H_DP,
 * DOCK_H_DP, DOCK_B_DP: sent in the URL).
 * Settings come from the screensaver app's ConfigProvider (same signing key); without it the page's defaults.
 *
 * The card is selected through an invisible focusable view (cardView) that the page places over its card
 * (SaudiTVNative.cardRect). OK on it opens the current panel full screen in DetailActivity; LEFT/RIGHT step the
 * panels; the page draws the focus ring and scale (window.sauditvSetFocused). The WebView never takes key focus.
 * The same for «يُتلى الآن» under the clock (qrView, SaudiTVNative.qrRect, window.sauditvQrFocused): OK or a click on it
 * stops the radio (setOnQr).
 *
 * «شكل الواجهة» «الجديد» (#look=new in the settings fragment, HomeLook): the page draws its «اليوم» row of cards instead
 * of the card; the same invisible view covers the row: LEFT/RIGHT (held too) step from card to card (sauditvStep), the
 * mouse points at one (sauditvPoint), OK opens the focused one with the row's cards for the detail window's LEFT/RIGHT
 * (sauditvDetailList). Its apps row is HomeLook's size (sent as dockh/dockb); the page keeps its dateline clear of the
 * pills (setPillsEnd, window.sauditvPillsEnd).
 */
final class HomePage {
    private static final Uri CONFIG = Uri.parse("content://com.raad.sauditv.screensaver.config");
    private static final String REPO = "https://raw.githubusercontent.com/raad113/SaudiTV/main/";
    private static final String FONTS = REPO + "assets/fonts/", BUNDLED = REPO + "assets/backgrounds/", HOSTED = REPO + "photos/";
    private static final int KEEP_PHOTOS = 12;   // the page's 12-photo set (photoSet), downloaded once each
    /** the page's panel ids (window.sauditvCurrentPanel() returns one of these) */
    static final List<String> PANELS = Arrays.asList("prayers", "forecast", "matches", "standings", "news", "local", "world",
            "tech", "verse", "hadith");   // as the page's PANEL_IDS: each has a detail page
    /** The room the launcher's views take, in dp (= the page's CSS px): the pills' height (top left, TOP_DP from the
     *  top), the apps row's height and its distance from the bottom edge (above the page's 42px ticker tape and,
     *  its 30px info strip: DOCK_B_DP - 42). */
    // 104 / 72 (was 90 / 72): the row holds a focused tile (scaled, with its ring or lift) and its name under it, for
    // both tile shapes and both focus styles (MainActivity's DOCK_* sizes); the page's own default (--dock-h) matches
    static final int PILLS_H_DP = 26, TOP_DP = 16, DOCK_H_DP = 104, DOCK_B_DP = 72;
    /** the page's side margin (.stage padding: 3.6vw), for the launcher's views to line up with it */
    static final float SIDE = 0.036f;

    private final Activity activity;
    private final ViewGroup parent;
    private final FrameLayout card;   // invisible, focusable, over the page's card
    private final FrameLayout qr;     // the same over «يُتلى الآن»
    private Runnable onQrFocus, onQrClick;
    private WebView web;
    private String url;
    private String fragment;     // the settings fragment without panel/panels/rotate/week/widget, for the detail page
    private String firstPanel;   // first of the configured panels, the fallback for the detail page
    private String panelList;    // the configured panels, comma-separated: LEFT/RIGHT on the detail page
    private boolean opening;     // one detail launch per OK press
    private boolean appsOpen;    // the launcher's apps grid is open (window.sauditvAppsOpen)
    // the app tiles' look from the same settings read (ConfigProvider's tile_shape, focus_style; absent: the defaults)
    private volatile boolean circles, apple;
    private volatile boolean newLook;   // «شكل الواجهة» «الجديد» (HomeLook.isNew of the settings fragment)
    private float cardL, cardT, cardK = 1;   // where the card view is (CSS px) and the page's px per CSS px (place)
    private int pillsEnd = -1;   // the pills' right edge in px (setPillsEnd), sent again to a (re)loaded page
    private Runnable onFocus;
    private Consumer<Boolean> onCardChanged;

    /** The WebView goes into parent at index 0, under everything else. */
    HomePage(Activity activity, ViewGroup parent) {
        this.activity = activity;
        this.parent = parent;
        card = new FrameLayout(activity);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);   // OK / Enter on the focused card = performClick
        card.setDefaultFocusHighlightEnabled(false);   // the page draws the focus
        card.setVisibility(View.GONE);   // until the page says where its card is
        card.setOnClickListener(v -> open());
        // LEFT/RIGHT on the focused card step through its panels (RTL: left = next); focus stays here. «الجديد»: from
        // card to card of its row, a held key too
        card.setOnKeyListener((v, code, e) -> {
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN && (e.getRepeatCount() == 0 || newLook)) step(code == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : -1);
            return true;
        });
        // «الجديد»: the mouse over the row focuses the card under it (the page finds it: window.sauditvPoint, CSS px)
        card.setOnHoverListener((v, e) -> {
            if (newLook && web != null && e.getActionMasked() == android.view.MotionEvent.ACTION_HOVER_MOVE)
                web.evaluateJavascript("window.sauditvPoint && window.sauditvPoint(" + (cardL + e.getX() / cardK) + ","
                    + (cardT + e.getY() / cardK) + ")", null);
            return false;
        });
        card.setOnFocusChangeListener((v, has) -> {
            setPageFocused(has);
            if (has && onFocus != null) onFocus.run();
        });
        qr = new FrameLayout(activity);
        qr.setId(View.generateViewId());
        qr.setFocusable(true);
        qr.setClickable(true);
        qr.setDefaultFocusHighlightEnabled(false);   // the page draws it, as a focused pill
        qr.setVisibility(View.GONE);
        qr.setOnClickListener(v -> { if (onQrClick != null) onQrClick.run(); });
        qr.setOnFocusChangeListener((v, has) -> {
            setQrFocused(has);
            if (has && onQrFocus != null) onQrFocus.run();
        });
        String u = readUrl();
        create(u);
    }

    /** «يُتلى الآن»'s view (added by the caller next to cardView), its focus and its press (the radio stops) */
    View qrView() { return qr; }
    View focusableQr() { return qr.getVisibility() == View.VISIBLE ? qr : null; }
    void setOnQr(Runnable focus, Runnable click) { onQrFocus = focus; onQrClick = click; }

    private void setQrFocused(boolean has) {
        if (web != null) web.evaluateJavascript("window.sauditvQrFocused && window.sauditvQrFocused(" + has + ")", null);
    }

    /** called when the card gains D-pad focus */
    void setOnFocus(Runnable r) { onFocus = r; }

    /** called when the card appears or goes (D-pad wiring); true when it went while it had the focus */
    void setOnCardChanged(Consumer<Boolean> r) { onCardChanged = r; }

    /** The invisible view over the card; the caller adds it to the home screen's first page (top left based). */
    View cardView() { return card; }

    /** The card view while the page shows a card, else null. */
    View focusableCard() { return card.getVisibility() == View.VISIBLE ? card : null; }

    /** «شكل التطبيقات»: round app icons (else banners with rounded corners), as of the last settings read */
    boolean circles() { return circles; }

    /** «شكل التحديد»: Apple TV's lift and shadow (else Google TV's ring), as of the last settings read */
    boolean apple() { return apple; }

    /** «شكل الواجهة» «الجديد», as of the last settings read */
    boolean newLook() { return newLook; }

    /** the apps row's height and its distance from the bottom edge (dp) in the current look */
    int dockH() { return newLook ? HomeLook.DOCK_H_DP : DOCK_H_DP; }
    int dockB() { return newLook ? HomeLook.DOCK_B_DP : DOCK_B_DP; }

    /** the launcher's pills end at px from the left edge: «الجديد»'s dateline keeps clear of them (window.sauditvPillsEnd) */
    void setPillsEnd(int px) {
        if (px == pillsEnd) return;
        pillsEnd = px;
        sendPillsEnd();
    }

    private void sendPillsEnd() {
        if (web != null && pillsEnd >= 0) web.evaluateJavascript("window.sauditvPillsEnd && window.sauditvPillsEnd(" + pillsEnd + ")", null);
    }

    /** onResume: follow the current settings (a changed choice gets a fresh page), run again, release the card. */
    void resume() {
        String want = readUrl();
        if (!want.equals(url)) { destroyWeb(); create(want); }
        web.onResume();
        web.resumeTimers();
        // release a rotation held by open(): continue from the same panel (or the detail page's last) with a fresh rotateMs
        web.evaluateJavascript("window.sauditvHold && window.sauditvHold(false)", null);
        radioChanged();   // «يُتلى الآن» as the radio is now: it may have changed while another app was in front
    }

    /** the radio changed (RadioLink): the page asks SaudiTVRadio.state() once (window.sauditvRadioChanged, on its bridge
     *  thread). It no longer asks every 3 s (#radiopush=1); a page older than this ignores the call and still asks. */
    void radioChanged() {
        if (web != null) web.evaluateJavascript("window.sauditvRadioChanged && window.sauditvRadioChanged()", null);
    }

    /** the settings changed while the home screen is in front (the screensaver app's CONFIG_CHANGED):
     *  a fresh page when its URL differs, as resume() does. */
    void refresh() {
        String want = readUrl();
        if (web != null && want.equals(url)) return;
        destroyWeb();
        create(want);
    }

    /** onPause: no timers, drawing or network while an app is in front. */
    void pause() {
        if (web != null) { web.onPause(); web.pauseTimers(); }
    }

    void destroy() {
        destroyWeb();
    }

    private void destroyWeb() {
        if (web == null) return;
        parent.removeView(web);
        web.removeJavascriptInterface("SaudiTVNative");
        web.removeJavascriptInterface("SaudiTVRadio");
        web.destroy();
        web = null;
        url = null;
        opening = false;
        place(card, 0, 0, 0, 0, 0);
        place(qr, 0, 0, 0, 0, 0);
    }

    /** The page's URL: the screensaver's settings, the home card's panels (rotating 30 s), home mode and its room. */
    private String readUrl() {
        String frag = "", panel = "none", shape = null, focus = null;
        try (Cursor c = activity.getContentResolver().query(CONFIG, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (c.getString(0) != null) frag = c.getString(0);
                if (c.getString(1) != null) panel = c.getString(1);
                int s = c.getColumnIndex("tile_shape"), f = c.getColumnIndex("focus_style");   // absent from older apps
                if (s >= 0) shape = c.getString(s);
                if (f >= 0) focus = c.getString(f);
            }
        } catch (RuntimeException e) {
            Log.w("SaudiTV-Home", "settings: " + e);   // screensaver app missing or too old: the page's defaults
        }
        circles = "circle".equals(shape);
        apple = "apple".equals(focus);
        newLook = HomeLook.isNew(frag);
        // a comma list ("prayers,forecast,matches") = one card that shows each in turn, 30 s each
        String[] panels = panel.replace(" ", "").split(",");
        panel = panels[0].isEmpty() ? "none" : panels[0];
        Numerals.fromFragment(frag);   // «شكل الأرقام» for the launcher's own text too
        frag = frag.replaceAll("(^|&)(panel|panels|rotate|week|widget|home)=[^&]*", "");   // the screensaver's own card choice
        fragment = frag;
        firstPanel = panel;
        panelList = "none".equals(panel) ? "" : String.join(",", panels);
        String rotate = panels.length > 1 ? "&panels=" + String.join(",", panels) + "&rotate=30" : "";
        return "file:///android_asset/web/tv.html#" + frag + "&panel=" + panel + rotate
                + "&home=1&pillsh=" + PILLS_H_DP + "&dockh=" + dockH() + "&dockb=" + dockB()
                + "&radiopush=1";   // «يُتلى الآن» is told when the radio changes (radioChanged): no asking every 3 s
    }

    /** OK on the card: hold the rotation, ask the page which panel it shows now, then open that one full screen. */
    private void open() {
        if (web == null || opening) return;
        opening = true;
        // the detail page also gets the card's list (&panels=a,b,c) for its LEFT/RIGHT
        final String frag = (fragment == null ? "" : fragment)
                + (panelList == null || panelList.isEmpty() ? "" : "&panels=" + panelList);
        final String fallback = firstPanel;
        // hold the rotation first (released in resume() when the home screen is back), then ask the panel; «الجديد» also
        // says its row's cards ("prayers,news,world,forecast|news"): the detail window's LEFT/RIGHT go through them
        web.evaluateJavascript("(window.sauditvHold && window.sauditvHold(true), (window.sauditvDetailList ? window.sauditvDetailList() + '|' : '')"
                + " + (window.sauditvCurrentPanel ? window.sauditvCurrentPanel() : ''))", value -> {
            opening = false;
            if (activity.isFinishing() || activity.isDestroyed()) return;
            String v = value == null ? "" : value.replace("\"", "").trim(), list = null;
            int bar = v.lastIndexOf('|');
            if (bar >= 0) { list = rowPanels(v.substring(0, bar)); v = v.substring(bar + 1); }
            String p = v;
            if (!PANELS.contains(p)) p = PANELS.contains(fallback) ? fallback : PANELS.get(0);
            String extra = list != null && list.contains(",") ? (fragment == null ? "" : fragment) + "&panels=" + list : frag;
            try {
                activity.startActivity(new Intent(activity, DetailActivity.class)
                        .putExtra(DetailActivity.EXTRA_FRAGMENT, extra)
                        .putExtra(DetailActivity.EXTRA_PANEL, p));
            } catch (RuntimeException e) {
                Log.w("SaudiTV-Home", "detail: " + e);
                if (web != null) web.evaluateJavascript("window.sauditvHold && window.sauditvHold(false)", null);
            }
        });
    }

    /** the page's list of panels, only the known ones, each once, comma-separated ("" for none) */
    static String rowPanels(String list) {
        java.util.LinkedHashSet<String> keep = new java.util.LinkedHashSet<>();
        for (String s : list.split(",")) if (PANELS.contains(s.trim())) keep.add(s.trim());
        return String.join(",", keep);
    }

    /** the card shows its next (+1) or previous (-1) panel and holds it a full rotation (window.sauditvStep) */
    private void step(int d) {
        if (web != null) web.evaluateJavascript("window.sauditvStep && window.sauditvStep(" + d + ")", null);
    }

    /** the apps grid opened or closed: the page shows only its blurred photo and holds its ticker and
     *  card while open (window.sauditvAppsOpen); sent again to a (re)loaded page */
    void setAppsOpen(boolean on) {
        if (on == appsOpen) return;
        appsOpen = on;
        if (web != null) web.evaluateJavascript("window.sauditvAppsOpen && window.sauditvAppsOpen(" + on + ")", null);
    }

    /** the page draws the focus ring and scale on its card (window.sauditvSetFocused) */
    private void setPageFocused(boolean has) {
        if (web != null) web.evaluateJavascript("window.sauditvSetFocused && window.sauditvSetFocused(" + has + ")", null);
    }

    /** The card's (or the chip's) box from the page, in CSS px of a viewport vw wide (0 x 0: none), as pixels over the
     *  WebView. */
    private void place(FrameLayout card, double l, double t, double w, double h, double vw) {
        float k = web != null && vw > 0 && web.getWidth() > 0 ? (float) (web.getWidth() / vw)
                : activity.getResources().getDisplayMetrics().density;
        boolean show = w > 0 && h > 0;
        FrameLayout.LayoutParams p = card.getLayoutParams() instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) card.getLayoutParams() : new FrameLayout.LayoutParams(0, 0);
        p.gravity = Gravity.LEFT | Gravity.TOP;
        p.width = show ? Math.round((float) w * k) : 0;
        p.height = show ? Math.round((float) h * k) : 0;
        p.leftMargin = show ? Math.round((float) l * k) : 0;
        p.topMargin = show ? Math.round((float) t * k) : 0;
        card.setLayoutParams(p);
        if (card == this.card) { cardL = (float) l; cardT = (float) t; cardK = k; }
        boolean was = card.getVisibility() == View.VISIBLE, hadFocus = card.isFocused();
        if (show != was) {
            card.setVisibility(show ? View.VISIBLE : View.GONE);
            if (onCardChanged != null) onCardChanged.accept(hadFocus && !show);   // true: the focus has to go somewhere
        }
    }

    /** Called from the page, on the WebView's bridge thread: the VPN badge beside the clock, and where the card is. */
    private final class Native {
        private final VpnStatus vpn = new VpnStatus(activity);
        @JavascriptInterface public boolean vpn() { return vpn.vpn(); }
        @JavascriptInterface public int vpnState() { return vpn.vpnState(); }
        @JavascriptInterface public String vpnCountry() { return vpn.vpnCountry(); }
        @JavascriptInterface public boolean vpnApp() { return vpn.vpnApp(); }
        @JavascriptInterface public void cardRect(double l, double t, double w, double h, double vw) {
            final WebView from = web;
            activity.runOnUiThread(() -> { if (web == from && !activity.isDestroyed()) place(card, l, t, w, h, vw); });
        }
        @JavascriptInterface public void qrRect(double l, double t, double w, double h, double vw) {
            final WebView from = web;
            activity.runOnUiThread(() -> { if (web == from && !activity.isDestroyed()) place(qr, l, t, w, h, vw); });
        }
    }

    /** The page's window.SaudiTVRadio: the «يُتلى الآن» chip's state from the screensaver app (RadioLink.chip), asked on
     *  the WebView's bridge thread (a content query, never the main thread) when the page loads and whenever the radio
     *  changes (radioChanged). */
    private final class RadioState {
        @JavascriptInterface public String state() { return RadioLink.chip(activity); }
    }

    /** the page asks GitHub for its fonts; answer from the APK (also used by DetailActivity) */
    static WebResourceResponse font(Context ctx, String s) {
        if (s == null || !s.startsWith(FONTS)) return null;
        String name = s.substring(FONTS.length());
        if (!name.matches("[A-Za-z0-9_-]+\\.woff2")) return null;
        try {
            return new WebResourceResponse("font/woff2", null, 200, "OK",
                Collections.singletonMap("Access-Control-Allow-Origin", "*"),
                ctx.getAssets().open("fonts/" + name));
        } catch (java.io.IOException ignored) {
            return null;
        }
    }

    /** The page's photos from the device, as the screensaver app does (PhotoCache): the bundled ones from the APK
     *  (assets/wallpapers), hosted ones downloaded once into files/photos and kept (the newest KEEP_PHOTOS); both at the
     *  size this screen draws them (PhotoScale), else as they are. Also the detail page's (DetailActivity). */
    static WebResourceResponse photo(Context c, String url) {
        try {
            if (url.startsWith(BUNDLED)) {
                final String name = safeName(url.substring(BUNDLED.length()));
                if (name == null) return null;
                File small = PhotoScale.of(c, "b-" + name, () -> c.getAssets().open("wallpapers/" + name));
                return new WebResourceResponse("image/jpeg", null,
                    small != null ? new FileInputStream(small) : c.getAssets().open("wallpapers/" + name));
            }
            if (url.startsWith(HOSTED)) {
                String name = safeName(url.substring(HOSTED.length()));
                if (name == null) return null;   // index.json and anything else go to the network
                final File f = hosted(c, url, name);
                if (f == null) return null;
                File small = PhotoScale.of(c, name, () -> new FileInputStream(f));
                return new WebResourceResponse("image/jpeg", null, new FileInputStream(small != null ? small : f));
            }
            // the online photos (photos/online.json), as the screensaver app does (PhotoCache.online): a Commons
            // thumbnail at this screen's width (PhotoSize.commonsWidth), downloaded once, the newest KEEP_ONLINE kept
            if (PhotoSize.online(url)) {
                android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
                String src = PhotoSize.commonsWidth(url, m.widthPixels, m.heightPixels);
                File f = download(c, "photos-online", src, PhotoSize.cacheName(src), KEEP_ONLINE);
                return f == null ? null : new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** the page asks for the next few ahead of their turn (photoAhead), so a rolling few are kept */
    private static final int KEEP_ONLINE = 30;

    private static String safeName(String s) {
        return s.matches("[A-Za-z0-9_-]+\\.jpg") ? s : null;
    }

    private static File hosted(Context c, String url, String name) throws java.io.IOException {
        return download(c, "photos", url, name, KEEP_PHOTOS);
    }

    /** url into files/<folder>/<name> once (the newest keep stay). Runs on the WebView's network thread, so a
     *  blocking download is fine. */
    private static synchronized File download(Context c, String folder, String url, String name, int keep) throws java.io.IOException {
        File dir = new File(c.getFilesDir(), folder);
        File f = new File(dir, name);
        if (f.isFile() && f.length() > 0) return f;
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "SaudiTV/1.0 (https://github.com/raad113/SaudiTV) Android");   // Wikimedia asks every client to name itself
        File tmp = new File(dir, name + ".tmp");
        try {
            if (con.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(f)) return null;
        } finally {
            con.disconnect();
            tmp.delete();
        }
        File[] all = dir.listFiles((d, n) -> n.endsWith(".jpg"));
        if (all != null && all.length > keep) {
            Arrays.sort(all, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = keep; i < all.length; i++) all[i].delete();
        }
        return f;
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @SuppressWarnings("deprecation")
    private void create(String u) {
        web = new WebView(activity);
        web.setBackgroundColor(0xFF040E09);   // the page's own background: no white flash before it paints
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        web.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final Context app = activity.getApplicationContext();
        web.setWebViewClient(new WebViewClient() {
            // fonts and photos from the device
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String s = r.getUrl().toString();
                WebResourceResponse f = font(app, s);
                if (f == null) f = photo(app, s);
                return f != null ? f : super.shouldInterceptRequest(v, r);
            }

            // a page (re)loaded while the card already has focus still gets its focus look
            @Override
            public void onPageFinished(WebView v, String u) {
                if (v == web && card.isFocused()) setPageFocused(true);
                if (v == web && qr.isFocused()) setQrFocused(true);
                if (v == web && appsOpen) v.evaluateJavascript("window.sauditvAppsOpen && window.sauditvAppsOpen(true)", null);
                if (v == web) sendPillsEnd();
            }
        });
        // the page's script warnings and errors go to logcat (tag SaudiTV-Home); the rest is dropped
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                ConsoleMessage.MessageLevel l = m.messageLevel();
                if (l == ConsoleMessage.MessageLevel.ERROR || l == ConsoleMessage.MessageLevel.WARNING) {
                    String src = m.sourceId() == null ? "" : m.sourceId().replaceAll("#.*", "");   // not the long fragment
                    Log.w("SaudiTV-Home", m.message() + " (" + src + ":" + m.lineNumber() + ")");
                }
                return true;
            }
        });
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // same as the screensaver: the page calls its data APIs directly from file://
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);   // photos come from photo(), data must be fresh
        web.addJavascriptInterface(new Native(), "SaudiTVNative");
        web.addJavascriptInterface(new RadioState(), "SaudiTVRadio");
        parent.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
        url = u;
        web.loadUrl(u);
    }
}
