package com.raad.sauditv.home;

/**
 * The home screen's «تشغيل القرآن» / «إيقاف القرآن» chip (and the quick-settings tile's press), without Android
 * (tests/java_test.py runs it): from the screensaver app's /radio (RadioLink.read: on "1"/"0" and RadioService's state).
 */
public final class RadioChip {
    private RadioChip() {}

    public static final String PLAY = "تشغيل القرآن", STOP = "إيقاف القرآن";

    /** on, and playing or trying to (loading, a failed stream that is tried again): a press stops it */
    public static boolean playing(String on, String state) {
        return "1".equals(on) && ("playing".equals(state) || "loading".equals(state) || "error".equals(state));
    }

    /** the chip's label: STOP while it plays, else PLAY (off, paused by the remote, or silent here by «متى تعمل الإذاعة») */
    public static String label(String on, String state) { return playing(on, state) ? STOP : PLAY; }

    /** what a press asks: false = off (stopped, and it stays off), true = on and playing now */
    public static boolean pressTurnsOn(String on, String state) { return !playing(on, state); }
}
