package com.raad.sauditv.screensaver;

import android.service.dreams.DreamService;
import android.webkit.WebView;

/** The system screensaver: starts on the TV's own idle timer, any key wakes it (and a mouse's move or click:
 *  DreamService does that while it is not interactive; never a pointer over it). While it dreams, it holds
 *  «إذاعة القرآن الكريم» (RadioService "saver"). */
public class ScreenDream extends DreamService {
    private WebView web;
    private RadioHolder radio;

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
    }

    @Override
    public void onDreamingStopped() {
        if (radio != null) radio.back();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (radio != null) radio.back();
        if (web != null) { web.destroy(); web = null; }
        super.onDetachedFromWindow();
    }
}
