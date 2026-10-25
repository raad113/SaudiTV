package com.raad.sauditv.home;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.FrameLayout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * A mouse (USB or Bluetooth) on our screens, as the remote: hover = the D-pad's focus (only when the pointer really
 * moves, so the focus stays where the D-pad put it while the pointer rests), click = OK (Android's own click), the
 * wheel = ▲▼ (one row a notch), right-click = Back (the mouse's own back button is Back already), and the pointer hidden
 * after HIDE_MS still. A WebView's page (keys false) takes hover, clicks and the wheel itself (the page's bootMouse).
 * An activity sends its dispatchGenericMotionEvent / dispatchTouchEvent here first (attach); a dialog's window callback
 * is wrapped (dialog). The same file is in both apps (com.raad.sauditv.home and com.raad.sauditv.screensaver).
 */
final class Mouse {
    static final long HIDE_MS = 3000;
    /** a focus change made by the pointer (its listeners keep the screen still: no scrolling to it) */
    private static boolean byPointer;

    private final Window window;
    private final Window.Callback cb;
    private final boolean keys;
    private final Frame frame;
    private final int[] at = new int[2];
    private float x = Float.NaN, y = Float.NaN, wheel;
    private long wheelAt;
    private boolean right;   // a right-click is down: its events are ours until it is up
    private View last;       // the view focused last (touch mode drops the focus: key() puts it back)
    private AbsListView list;   // a list the pointer or the wheel moved in, and its row (touch mode drops a list's selection)
    private int listPos = -1;
    private View clicked;    // the view a click went to: the focus when touch mode ends (not Android's first row)
    private final Runnable hide = () -> show(false);

    private Mouse(Window w, Window.Callback cb, boolean keys, boolean wrap) {
        this.window = w;
        this.cb = cb;
        this.keys = keys;
        this.frame = wrap ? wrap(w) : null;
        w.getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> { if (now != null) last = now; });
        w.getDecorView().getViewTreeObserver().addOnTouchModeChangeListener(touch -> {
            View c = clicked;
            if (touch) return;
            clicked = null;
            if (c != null && c.isAttachedToWindow() && c.isShown() && !c.isFocused()) c.requestFocus();
        });
        show(true);
    }

    /** an activity's mouse, after its setContentView; keys false: a WebView takes hover and the wheel */
    static Mouse attach(Activity a, boolean keys) { return new Mouse(a.getWindow(), a, keys, true); }

    /** a dialog's mouse (after its create()), through its window's callback */
    static void dialog(Dialog d) {
        final Window w = d.getWindow();
        if (w == null) return;
        final Window.Callback orig = w.getCallback();
        final Mouse m = new Mouse(w, orig, true, false);
        w.setCallback((Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
            new Class<?>[] {Window.Callback.class}, (proxy, method, args) -> {
                if (args != null && args.length == 1 && args[0] instanceof MotionEvent) {
                    MotionEvent e = (MotionEvent) args[0];
                    if ("dispatchGenericMotionEvent".equals(method.getName()) && m.generic(e)) return true;
                    if ("dispatchTouchEvent".equals(method.getName()) && m.touch(e)) return true;
                }
                try { return method.invoke(orig, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            }));
    }

    /** the screensaver's views: never a pointer */
    static FrameLayout hidden(Context c, View content) {
        Frame f = new Frame(c);
        f.hidden = true;
        f.addView(content, new ViewGroup.LayoutParams(-1, -1));
        return f;
    }

    /** the focus moves because the pointer moved (not the D-pad) */
    static boolean byPointer() { return byPointer; }

    static boolean isMouse(MotionEvent e) {
        return e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && !e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN);
    }

    /** a right-click's press (Back): for a popup's touch interceptor */
    static boolean rightDown(MotionEvent e) {
        return isMouse(e) && e.getActionMasked() == MotionEvent.ACTION_DOWN && (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
    }

    /** hover, the wheel, the buttons; true = used here */
    boolean generic(MotionEvent e) {
        if (!isMouse(e)) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
            case MotionEvent.ACTION_HOVER_ENTER: {
                float rx = e.getRawX(), ry = e.getRawY();
                boolean first = Float.isNaN(x), moved = rx != x || ry != y;
                x = rx;
                y = ry;
                if (!moved) return false;   // the window changed under a resting pointer: the focus stays
                show(true);
                if (keys && !first && window.getDecorView().hasWindowFocus()) hover(rx, ry);   // not under a dialog
                return false;               // the views still get it (their hovered look, a page's mousemove)
            }
            case MotionEvent.ACTION_SCROLL: {
                show(true);
                if (!keys) return false;
                long now = SystemClock.uptimeMillis();
                if (now - wheelAt > 400) wheel = 0;
                wheelAt = now;
                wheel += e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                while (Math.abs(wheel) >= 1f) {
                    key(wheel > 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
                    wheel -= Math.signum(wheel);
                }
                return true;
            }
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return right || (e.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
            default:
                return false;
        }
    }

    /** the buttons' clicks: a right-click is Back, the others go to the views */
    boolean touch(MotionEvent e) {
        if (!isMouse(e)) return false;
        show(true);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN && !rightDown(e)) {
            View hit = under(window.getDecorView(), e.getRawX(), e.getRawY());
            clicked = hit != null && hit.isFocusable() ? hit : null;
        }
        if (rightDown(e)) {
            right = true;
            key(KeyEvent.KEYCODE_BACK);
            return true;
        }
        if (!right) return false;
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) right = false;
        return true;
    }

    /** a key as the remote sends it; an arrow nobody takes moves the focus that way, as Android does (nextFocus…Id) */
    private void key(int code) {
        if (code != KeyEvent.KEYCODE_BACK && last instanceof AbsListView && last.isAttachedToWindow() && last.isShown()
            && listStep((AbsListView) last, code == KeyEvent.KEYCODE_DPAD_DOWN)) return;
        if (code != KeyEvent.KEYCODE_BACK && window.getDecorView().isInTouchMode()) {
            // Android puts the window in touch mode on every notch and click (the focus is dropped): back on the view
            // it was on and on from there; with none yet the first notch only shows the focus
            boolean had = last != null && last.isAttachedToWindow() && last.isShown() && last.requestFocusFromTouch();
            if (!had) { focusHere(); return; }
        }
        long t = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0);
        boolean used = cb.dispatchKeyEvent(down);
        cb.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP));
        if (used || code == KeyEvent.KEYCODE_BACK) return;
        View f = window.getCurrentFocus();
        int dir = code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN;
        View next = f == null ? null : f.focusSearch(dir);
        if (next != null && next != f) next.requestFocus(dir);
    }

    /**
     * The wheel in a list: its row moved by one ourselves (each notch puts the window in touch mode, which hides the
     * list's selection, and the list's own ▲▼ would only bring it back); past its ends the focus leaves the list.
     */
    private boolean listStep(AbsListView lv, boolean down) {
        int n = lv.getCount(), pos = lv.getSelectedItemPosition();
        if (pos < 0) pos = lv == list && listPos >= 0 ? listPos : lv.getFirstVisiblePosition() - (down ? 1 : 0);
        int to = pos + (down ? 1 : -1);
        if (to < 0 || to >= n) {
            View out = lv.focusSearch(down ? View.FOCUS_DOWN : View.FOCUS_UP);
            return out != null && out != lv && out.requestFocusFromTouch();
        }
        lv.requestFocusFromTouch();
        View row = lv.getChildAt(to - lv.getFirstVisiblePosition()), any = lv.getChildAt(0);
        int h = any == null ? 0 : any.getHeight(), bottom = lv.getHeight() - lv.getPaddingBottom();
        int top = row != null ? Math.max(lv.getPaddingTop(), Math.min(row.getTop(), bottom - row.getHeight()))
            : down ? bottom - h : lv.getPaddingTop();   // just off the list: it scrolls by one row
        lv.setSelectionFromTop(to, top);
        list = lv;
        listPos = to;
        return true;
    }

    /**
     * The window in touch mode with no view focused before: the focus, out of touch mode, on the row under the pointer,
     * else the focused one, else the first.
     */
    private boolean focusHere() {
        View d = window.getDecorView(), f = window.getCurrentFocus();
        View hit = Float.isNaN(x) ? null : under(d, x, y);
        if (hit != null && hit.isFocusable() && !hit.onCheckIsTextEditor() && hit.requestFocusFromTouch()) return true;
        if (f != null && f.requestFocusFromTouch()) return true;
        java.util.ArrayList<View> all = d.getFocusables(View.FOCUS_DOWN);
        return !all.isEmpty() && all.get(0).requestFocusFromTouch();
    }

    /** the focus to what is under the pointer: a clickable, focusable view, or a list's row */
    private void hover(float rx, float ry) {
        View hit = under(window.getDecorView(), rx, ry);
        if (hit instanceof AbsListView) {
            AbsListView lv = (AbsListView) hit;
            lv.getLocationOnScreen(at);
            int pos = lv.pointToPosition(Math.round(rx) - at[0], Math.round(ry) - at[1]);
            View row = pos < 0 ? null : lv.getChildAt(pos - lv.getFirstVisiblePosition());
            if (row == null) return;
            if (!lv.hasFocus() || lv.isInTouchMode()) lv.requestFocusFromTouch();
            if (lv.getSelectedItemPosition() != pos) lv.setSelectionFromTop(pos, row.getTop());   // in place, no jump
            list = lv;
            listPos = pos;
            return;
        }
        if (hit == null || !hit.isFocusable() || (hit.isFocused() && !hit.isInTouchMode()) || hit.onCheckIsTextEditor()) return;
        byPointer = true;
        try { hit.requestFocusFromTouch(); } finally { byPointer = false; }   // out of touch mode too
    }

    /** the topmost clickable view (or list) under the pointer: what a click there would reach; null for none */
    private View under(View v, float rx, float ry) {
        if (v.getVisibility() != View.VISIBLE) return null;
        v.getLocationOnScreen(at);
        if (rx < at[0] || ry < at[1] || rx >= at[0] + v.getWidth() * v.getScaleX() || ry >= at[1] + v.getHeight() * v.getScaleY())
            return null;
        if (v instanceof AbsListView) return v;
        if (v instanceof ViewGroup && !(v instanceof android.webkit.WebView)) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View hit = under(g.getChildAt(i), rx, ry);
                if (hit != null) return hit;
            }
        }
        return v.isClickable() && v.isEnabled() ? v : null;
    }

    /** the pointer shown (it moved), hidden again HIDE_MS later */
    private void show(boolean on) {
        View d = window.getDecorView();
        d.removeCallbacks(hide);
        if (on) d.postDelayed(hide, HIDE_MS);
        if (frame == null || frame.hidden == !on) return;
        frame.hidden = !on;
        if (!on && Build.VERSION.SDK_INT >= 24) frame.setPointerIcon(null);   // Android asks for the icon again: none
    }

    /** the window's content wrapped in a Frame (its children moved into it) */
    private static Frame wrap(Window w) {
        ViewGroup content = w.getDecorView().findViewById(android.R.id.content);
        if (content == null) return null;
        Frame f = new Frame(content.getContext());
        while (content.getChildCount() > 0) {
            View c = content.getChildAt(0);
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            content.removeViewAt(0);
            f.addView(c, lp);
        }
        content.addView(f, new ViewGroup.LayoutParams(-1, -1));
        return f;
    }

    /** a layer that gives no pointer at all while hidden (over a page too: it answers before the WebView) */
    static final class Frame extends FrameLayout {
        boolean hidden;

        Frame(Context c) { super(c); }

        @Override
        public PointerIcon onResolvePointerIcon(MotionEvent e, int i) {
            return hidden ? PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL) : super.onResolvePointerIcon(e, i);
        }
    }
}
