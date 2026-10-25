package com.raad.sauditv.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
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

/**
 * one home-card panel full screen (tv.html#<fragment>&detail=<panel>), opened with OK on the card.
 * The page takes the arrow keys and Enter; it opens links through SaudiTVNative.openUrl.
 * links open in an in-app article WebView laid over the panel (no external browser app).
 * Back steps back in the article, then closes it, and only then closes this activity.
 * Both WebViews live only as long as this activity.
 * A mouse: the page takes hover, clicks and the wheel (its bootMouse); right-click is Back here (Mouse).
 */
public class DetailActivity extends Activity {
    // the settings fragment plus the card's "&panels=a,b,c" (the page's LEFT/RIGHT); appended as is
    static final String EXTRA_FRAGMENT = "com.raad.sauditv.home.extra.FRAGMENT";
    static final String EXTRA_PANEL = "com.raad.sauditv.home.extra.PANEL";
    private static final int BG = 0xFF0F0D18;

    private WebView web;
    private FrameLayout root;
    private WebView article;   // in-app article view, null when closed
    private Mouse mouse;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        String panel = in.getStringExtra(EXTRA_PANEL);
        String frag = in.getStringExtra(EXTRA_FRAGMENT);
        if (panel == null || !HomePage.PANELS.contains(panel)) { finish(); return; }
        if (frag == null) frag = "";
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            // the page asks GitHub for its fonts; answer from the APK (same as the home card)
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse f = HomePage.font(DetailActivity.this, r.getUrl().toString());
                return f != null ? f : super.shouldInterceptRequest(v, r);
            }
        });
        // script warnings and errors go to logcat (tag SaudiTV-Detail); the rest is dropped
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                ConsoleMessage.MessageLevel l = m.messageLevel();
                if (l == ConsoleMessage.MessageLevel.ERROR || l == ConsoleMessage.MessageLevel.WARNING) {
                    String src = m.sourceId() == null ? "" : m.sourceId().replaceAll("#.*", "");   // not the long fragment
                    Log.w("SaudiTV-Detail", m.message() + " (" + src + ":" + m.lineNumber() + ")");
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
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Native(), "SaudiTVNative");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        mouse = Mouse.attach(this, false);
        web.loadUrl("file:///android_asset/web/tv.html#" + frag + "&detail=" + panel);
        web.requestFocus();
    }

    /** Called from the page (on the WebView's bridge thread). */
    private final class Native {
        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> open(url));
        }
    }

    private static boolean isWeb(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        return ("http".equals(scheme) || "https".equals(scheme)) && u.getHost() != null && !u.getHost().isEmpty();
    }

    /** show the link in a full-screen article WebView on top of the panel. */
    private void open(String url) {
        if (url == null || isFinishing() || isDestroyed() || root == null) return;
        Uri u = Uri.parse(url.trim());
        if (!isWeb(u)) return;
        if (article == null) {
            article = new WebView(this);
            article.setBackgroundColor(0xFF000000);
            article.setFocusable(true);
            article.setFocusableInTouchMode(true);
            article.setWebViewClient(new WebViewClient() {
                // http/https stay inside this WebView; other schemes are ignored
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    return !isWeb(r.getUrl());
                }
            });
            article.setWebChromeClient(new WebChromeClient());
            WebSettings s = article.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(false);
            s.setAllowContentAccess(false);
            s.setAllowFileAccessFromFileURLs(false);
            s.setAllowUniversalAccessFromFileURLs(false);
            s.setCacheMode(WebSettings.LOAD_DEFAULT);
            root.addView(article, new FrameLayout.LayoutParams(-1, -1));
            // keep the D-pad inside the article while it is open
            if (web != null) { web.setFocusable(false); web.setFocusableInTouchMode(false); }
        }
        article.loadUrl(u.toString());
        article.requestFocus();
    }

    /** close the article view, free it, and give the panel its focus back. */
    private void closeArticle() {
        if (article == null) return;
        WebView a = article;
        article = null;
        ViewGroup p = (ViewGroup) a.getParent();
        if (p != null) p.removeView(a);
        a.stopLoading();
        a.destroy();
        if (web != null) {
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            web.requestFocus();
        }
    }

    /** Back inside the article goes back, then closes it; with no article it closes this activity. */
    private void back() {
        if (article != null) {
            if (article.canGoBack()) article.goBack();
            else closeArticle();
        } else {
            finish();
        }
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
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Back is ours, whatever the WebViews would do with it
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) back();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        back();
    }

    @Override
    protected void onResume() {
        super.onResume();
        RadioLink.front(this, "detail");   // «إذاعة القرآن الكريم» plays while our screens are in front
        if (web != null) { web.onResume(); web.resumeTimers(); }
        if (article != null) { article.onResume(); article.requestFocus(); }
        else if (web != null) web.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        RadioLink.back("detail");
        if (article != null) article.onPause();
        if (web != null) { web.onPause(); web.pauseTimers(); }
    }

    @Override
    protected void onDestroy() {
        if (article != null) {
            WebView a = article;
            article = null;
            ViewGroup ap = (ViewGroup) a.getParent();
            if (ap != null) ap.removeView(a);
            a.destroy();
        }
        if (web != null) {
            ViewGroup p = (ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.removeJavascriptInterface("SaudiTVNative");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
