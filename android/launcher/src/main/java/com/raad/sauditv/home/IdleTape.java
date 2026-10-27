package com.raad.sauditv.home;

/**
 * «الأسواق» at rest (HomePage): the page's ticker tape moves in half its steps a second while the remote has not been
 * touched for HomePage.IDLE_MS (no key, no mouse), and in all of them again from the first key on (the page's
 * window.sauditvIdle, tapeEase). This keeps what the page was last told, so it is told only when that changes: a key
 * held down or a moving mouse sends nothing while the tape is at its full rate. A page loaded anew (a changed setting)
 * starts at its full rate and is told again when the tape was at rest. Plain Java (tests/java_test.py).
 */
public final class IdleTape {
    private boolean idle;

    /** a key or the mouse: the call that brings the full rate back, or null when the tape was not at rest */
    public String touched() {
        if (!idle) return null;
        idle = false;
        return call(false);
    }

    /** IDLE_MS without either: the call that rests the tape, or null when it rests already */
    public String rest() {
        if (idle) return null;
        idle = true;
        return call(true);
    }

    /** a page just loaded (its full rate): the call that rests it too while the tape is at rest, else null */
    public String loaded() {
        return idle ? call(true) : null;
    }

    public boolean idle() { return idle; }

    static String call(boolean on) {
        return "window.sauditvIdle && window.sauditvIdle(" + on + ")";
    }
}
