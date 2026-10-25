package com.raad.sauditv.screensaver;

import java.util.ArrayList;
import java.util.List;

/**
 * Saved card choices of older builds, read as this build has them, without Android (tests/java_test.py runs it).
 * Older builds had a «جديد المسلسلات» card ("shows"), now gone: a saved card or list of cards drops it, and one that
 * was only that card becomes the prayers card. Applied each time the settings are read (Prefs.panel / homePanel),
 * so nothing has to be rewritten.
 */
final class PanelMigrate {
    private PanelMigrate() {}

    static final String GONE = "shows";
    static final String FALLBACK = "prayers";
    /** a new install's card beside the clock (Prefs.migrate): «آية اليوم» and «حديث اليوم», in turn; settings saved
     *  before keep theirs, and an older build's settings that never picked one keep the prayers */
    static final String FRESH = "verse,hadith";

    /** v without "shows" ("shows,news" -> "news"); only "shows" -> "prayers"; null stays null */
    static String dropShows(String v) {
        if (v == null) return null;
        if (!v.contains(GONE)) return v;
        List<String> keep = new ArrayList<>();
        boolean dropped = false;
        for (String s : v.split(",")) {
            String t = s.trim();
            if (GONE.equals(t)) { dropped = true; continue; }
            if (!t.isEmpty()) keep.add(t);
        }
        if (!dropped) return v;
        if (keep.isEmpty()) return FALLBACK;
        StringBuilder b = new StringBuilder();
        for (String s : keep) { if (b.length() > 0) b.append(','); b.append(s); }
        return b.toString();
    }
}
