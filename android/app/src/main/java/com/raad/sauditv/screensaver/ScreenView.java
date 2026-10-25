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
        String url = new Prefs(c).dashboardUrl(c);
        if (test != null && test.matches("[a-z]+=[0-9.]+(&[a-z]+=[0-9.]+)*")) url += "&" + test;
        w.loadUrl(url);
        UpdateJob.schedule(c);
        return w;
    }
}
