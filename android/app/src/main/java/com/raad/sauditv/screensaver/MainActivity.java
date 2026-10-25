package com.raad.sauditv.screensaver;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;

/** Full-screen preview of the screensaver; any remote key, or a mouse's click or wheel, closes it (never a pointer). */
public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = ScreenView.create(this, getIntent().getStringExtra("test"));   // test values from ADB
        setContentView(Mouse.hidden(this, web));
        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // the preview is the screensaver in front: «إذاعة القرآن الكريم» plays here as on the dream
    private RadioHolder radio;

    @Override
    protected void onResume() {
        super.onResume();
        if (radio == null) radio = new RadioHolder(this, RadioService.SAVER);
        radio.front();
    }

    @Override
    protected void onPause() {
        if (radio != null) radio.back();
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (!Mouse.isMouse(e)) return super.dispatchTouchEvent(e);
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) {
        if (Mouse.isMouse(e) && e.getActionMasked() == android.view.MotionEvent.ACTION_SCROLL) { finish(); return true; }
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    protected void onDestroy() {
        if (web != null) { ScreenView.destroy(web); web = null; }   // and its renderer (ScreenView.destroy)
        super.onDestroy();
    }
}
