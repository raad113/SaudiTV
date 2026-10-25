package com.raad.sauditv.screensaver;

/**
 * «متى تعمل الإذاعة»: where «إذاعة القرآن الكريم» plays by itself, without Android (tests/java_test.py runs it).
 * BOTH (the default, as before): the home screen (and its full-screen card) and the screensaver; SAVER: only while the
 * screensaver (or its preview) shows, silent on the home screen unless «تشغيل القرآن» was pressed there (the override,
 * until «إيقاف القرآن», another app, or the screen off); HOME: only on the home screen, silent once the screensaver starts.
 * The settings' «معاينة الإذاعة» plays whatever this says.
 */
final class RadioWhen {
    private RadioWhen() {}

    static final String BOTH = "both", SAVER = "saver", HOME = "home";
    /** value, label (the settings' choices, in order) */
    static final String[][] CHOICES = {
        {BOTH, "الشاشة الرئيسية وشاشة التوقف"}, {SAVER, "شاشة التوقف فقط"}, {HOME, "الشاشة الرئيسية فقط"},
    };

    /** «إذاعة القرآن الكريم»'s one choice in the settings and the first-run setup: off, or on and where it plays.
     *  Stored as before (Prefs: radioOn and radioWhen), so saver.sh radio on|off / radio-when and the home screen's
     *  «تشغيل / إيقاف القرآن» stay as they are. */
    static final String OFF = "off";
    static final String[][] MODES = {
        {OFF, "متوقفة"}, {BOTH, "تعمل دائمًا"}, {SAVER, "شاشة التوقف فقط"}, {HOME, "الشاشة الرئيسية فقط"},
    };

    /** the choice the saved radio on/off and when make */
    static String mode(boolean on, String when) { return on ? of(when) : OFF; }

    /** the radio on for this choice (an unknown one: off) */
    static boolean modeOn(String mode) {
        for (String[] c : CHOICES) if (c[0].equals(mode)) return true;
        return false;
    }

    /** where it plays for this choice; off (or an unknown one) keeps the saved one (keep) */
    static String modeWhen(String mode, String keep) { return modeOn(mode) ? mode : of(keep); }

    static String modeLabel(String mode) {
        for (String[] c : MODES) if (c[0].equals(mode)) return c[1];
        return MODES[0][1];
    }

    /** a known value, else BOTH */
    static String of(String v) {
        for (String[] c : CHOICES) if (c[0].equals(v)) return v;
        return BOTH;
    }

    static String label(String v) {
        for (String[] c : CHOICES) if (c[0].equals(of(v))) return c[1];
        return CHOICES[0][1];
    }

    /**
     * Whether it plays now: the radio on, which of our screens are in front (home: the home screen or its card; saver:
     * the screensaver or its preview; preview: the settings' «معاينة») and the home screen's «تشغيل القرآن» (override).
     */
    static boolean plays(String when, boolean on, boolean home, boolean saver, boolean preview, boolean override) {
        if (preview) return true;
        if (!on || !(home || saver)) return false;
        switch (of(when)) {
            case SAVER: return saver || override;
            case HOME: return home && !saver;
            default: return true;
        }
    }
}
