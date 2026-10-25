package com.raad.sauditv.screensaver;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** The WebView shared by the preview activity and the dream. */
final class ScreenView {
    private ScreenView() {}

    /** this app's pages now (the dream's, the preview's): the page's renderer process ends with the last (destroy) */
    private static int live;

    /**
     * Destroys a page made by create(). The last one also ends its renderer process (Android 10+): WebView keeps it
     * after the last page goes, and it stayed up between screensavers, 26 to 48 MB doing nothing (measured on TV 1).
     * The next page starts a new one, as the first did. Main thread only (the dream's and the preview's lifecycle).
     */
    static void destroy(WebView w) {
        if (w == null) return;
        Object renderer = null;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try { renderer = w.getWebViewRenderProcess(); } catch (RuntimeException ignored) { }
        }
        w.destroy();   // first: a renderer ended under a live page would take the app down with it
        live = Math.max(0, live - 1);
        if (live == 0 && renderer != null && android.os.Build.VERSION.SDK_INT >= 29) {
            try { ((android.webkit.WebViewRenderProcess) renderer).terminate(); } catch (RuntimeException ignored) { }
        }
    }

    static WebView create(final Context c) {
        return create(c, null);
    }

    /**
     * {@code test} = test values for the page, added to its address: the preview from ADB only, e.g.
     * {@code am start -n com.raad.sauditv.screensaver/.MainActivity --es test 'swapmin=4&breakmin=4'} (see the page's CONFIG).
     * Only name=number pairs; anything else is ignored.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    static WebView create(final Context c, String test) {
        WebView w = new WebView(c);
        live++;
        w.setBackgroundColor(0xFF040E09);   // no white flash before the page paints
        w.setFocusable(false);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        w.setOverScrollMode(View.OVER_SCROLL_NEVER);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse res = PhotoCache.serve(c, r.getUrl().toString());
                return res != null ? res : super.shouldInterceptRequest(v, r);
            }
        });
        // the page's script warnings and errors go to logcat (tag SaudiTV-Screen), as the home screen's do; the rest is
        // dropped (the page's #feedtest=1 reports there)
        w.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                android.webkit.ConsoleMessage.MessageLevel l = m.messageLevel();
                if (l == android.webkit.ConsoleMessage.MessageLevel.ERROR || l == android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                    String src = m.sourceId() == null ? "" : m.sourceId().replaceAll("#.*", "");   // not the long fragment
                    android.util.Log.w("SaudiTV-Screen", m.message() + " (" + src + ":" + m.lineNumber() + ")");
                }
                return true;
            }
        });
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        // lets the page call Yahoo and other APIs directly, without a CORS gateway
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);   // photos are cached by PhotoCache, data must be fresh
        if (android.os.Build.VERSION.SDK_INT >= 23) s.setOffscreenPreRaster(true);   // draw before it is shown
        w.addJavascriptInterface(new VpnStatus(c), "SaudiTVNative");   // VPN badge
        w.addJavascriptInterface(new RadioBridge(), "SaudiTVRadio");   // the «يُتلى الآن» chip (RadioService's state)
        String url = new Prefs(c).dashboardUrl(c);
        if (test != null && test.matches("[a-z]+=[0-9.]+(&[a-z]+=[0-9.]+)*")) url += "&" + test;
        w.loadUrl(url);
        UpdateJob.schedule(c);
        return w;
    }
}
