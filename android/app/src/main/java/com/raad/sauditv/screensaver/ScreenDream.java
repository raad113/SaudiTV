package com.raad.sauditv.screensaver;

import android.service.dreams.DreamService;
import android.webkit.WebView;

/** The system screensaver: starts on the TV's own idle timer, any key wakes it (and a mouse's move or click:
 *  DreamService does that while it is not interactive; never a pointer over it). While it dreams, it holds
 *  «إذاعة القرآن الكريم» (RadioService "saver"). The full athan (AthanService) ends it: endForAthan. */
public class ScreenDream extends DreamService {
    private WebView web;
    private RadioHolder radio;
    private static volatile ScreenDream dreaming;

    /** the full athan's screen comes over the screensaver: it ends, the TV staying awake (wakeUp, Android 10+) */
    static void endForAthan() {
        final ScreenDream d = dreaming;
        if (d == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (android.os.Build.VERSION.SDK_INT >= 29) d.wakeUp(); else d.finish();
        });
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        web = ScreenView.create(this);
        setContentView(Mouse.hidden(this, web));
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (radio == null) radio = new RadioHolder(this, RadioService.SAVER);
        radio.front();
        dreaming = this;
    }

    @Override
    public void onDreamingStopped() {
        if (dreaming == this) dreaming = null;
        if (radio != null) radio.back();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (radio != null) radio.back();
        if (web != null) { ScreenView.destroy(web); web = null; }   // and its renderer: nothing of ours stays up after it
        super.onDetachedFromWindow();
    }
}
