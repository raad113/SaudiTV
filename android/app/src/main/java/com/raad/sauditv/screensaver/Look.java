package com.raad.sauditv.screensaver;

/**
 * «شكل الواجهة», without Android (tests/java_test.py runs it): the home screen and the screensaver in «الكلاسيكي» (the
 * look they always had) or «الجديد» (design/concepts/1-ambient-editorial: the page's #look=new, nxBoot). Settings saved
 * before keep «الكلاسيكي»; a new install gets «الجديد» (Prefs.migrate writes FRESH). Either way the user chooses it once:
 * a new install in the setup's first step («الجديد» focused), a TV set up before in the same step on its own, opened
 * by the home screen while SetupState.lookPending (no lookChosen yet). The page and the launcher are told
 * by the settings fragment (param): a change reloads the home screen's page at once (CONFIG_CHANGED, as every setting)
 * and the screensaver's at its next start.
 */
final class Look {
    private Look() {}

    static final String CLASSIC = "classic", NEW = "new";
    /** value, Arabic label (the settings' list, in this order) */
    static final String[][] LOOKS = {{CLASSIC, "الكلاسيكي"}, {NEW, "الجديد"}};
    /** a new install's look (Prefs.migrate) */
    static final String FRESH = NEW;

    /** a stored value as a look: anything but "new" (none saved: an older build's settings) is «الكلاسيكي» */
    static String of(String v) { return NEW.equals(v) ? NEW : CLASSIC; }

    /** a value from the settings or ADB: "classic" or "new", else null */
    static String parse(String v) {
        if (v == null) return null;
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        return CLASSIC.equals(s) || NEW.equals(s) ? s : null;
    }

    /** the page's part of the fragment: "&look=new", nothing for «الكلاسيكي» (its address as before) */
    static String param(String v) { return NEW.equals(of(v)) ? "&look=new" : ""; }

    static String label(String v) { return NEW.equals(of(v)) ? LOOKS[1][1] : LOOKS[0][1]; }
}
