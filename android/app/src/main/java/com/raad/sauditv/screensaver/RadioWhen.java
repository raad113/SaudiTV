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
