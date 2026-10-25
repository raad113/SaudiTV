package com.raad.sauditv.screensaver;

import android.webkit.JavascriptInterface;

/** The page's window.SaudiTVRadio on the screensaver (ScreenView): the «يُتلى الآن» chip's state (tickRadio). */
public final class RadioBridge {
    /** "playing|<station>", "loading|…", "error|…", "paused|…", or "" while the radio is not playing */
    @JavascriptInterface
    public String state() {
        String st = RadioService.state();
        return "idle".equals(st) ? "" : st + "|" + RadioService.stationName();
    }
}
