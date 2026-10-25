package com.raad.sauditv.screensaver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Two screensaver choices, without Android (tests/java_test.py runs it):
 * «سطور شاشة التوقف» (Prefs "saverFacts", the page's #facts=, «الجديد» only): the lines bottom left of the screensaver,
 * up to MAX_FACTS of FACTS in the order picked, each still shown only with its data; hijri is the Hijri date with the
 * Gregorian under it. Nothing saved is DEFAULT_FACTS (the page's own default: no #facts= sent).
 * «مدة كل بطاقة» (Prefs "rotateSec", the page's #rotate=): how long each card stays before the next, one of ROTATE_SECS,
 * DEFAULT_ROTATE by default; both looks, the screensaver's card and the home screen's (HomePage reads it from the fragment).
 */
final class SaverOptions {
    private SaverOptions() {}

    /** id, Arabic label (the settings' list, in this order) */
    static final String[][] FACTS = {
        {"prayer", "الصلاة القادمة"}, {"match", "المباراة القادمة"}, {"weather", "الطقس"},
        {"ramadan", "رمضان"}, {"hijri", "التاريخ الهجري"}};
    static final int MAX_FACTS = 4;
    static final List<String> DEFAULT_FACTS = Arrays.asList("prayer", "match", "weather", "ramadan");

    static final int[] ROTATE_SECS = {10, 15, 20, 30, 60};
    static final int DEFAULT_ROTATE = 15;

    static boolean knownFact(String id) {
        for (String[] f : FACTS) if (f[0].equals(id)) return true;
        return false;
    }

    /** "a,b" from the settings or ADB: its ids in order, each once; null for an unknown id or more than MAX_FACTS;
     *  "" or "default" = the default list */
    static List<String> parseFacts(String v) {
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("default")) return new ArrayList<>(DEFAULT_FACTS);
        for (String id : s.split(",")) {
            id = id.trim();
            if (id.isEmpty()) continue;
            if (!knownFact(id)) return null;
            if (!out.contains(id)) out.add(id);
        }
        return out.isEmpty() || out.size() > MAX_FACTS ? null : out;
    }

    /** a stored value as the facts shown: a bad or missing one is the default */
    static List<String> facts(String stored) {
        List<String> f = stored == null ? null : parseFacts(stored);
        return f == null ? new ArrayList<>(DEFAULT_FACTS) : f;
    }

    /** the page's part of the fragment: "&facts=a,b", nothing for the default (the page's own) */
    static String factsParam(List<String> f) {
        return f == null || f.equals(DEFAULT_FACTS) ? "" : "&facts=" + String.join(",", f);
    }

    /** a fact's Arabic label */
    static String factLabel(String id) {
        for (String[] f : FACTS) if (f[0].equals(id)) return f[1];
        return id;
    }

    /** seconds from the settings or ADB ("15", "15s"): one of ROTATE_SECS, else -1 */
    static int parseRotate(String v) {
        if (v == null) return -1;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith("s")) s = s.substring(0, s.length() - 1).trim();
        try {
            int n = Integer.parseInt(s);
            for (int x : ROTATE_SECS) if (x == n) return n;
        } catch (NumberFormatException ignored) { }
        return -1;
    }

    /** a stored value as one of ROTATE_SECS (anything else: the default) */
    static int rotate(int stored) {
        for (int x : ROTATE_SECS) if (x == stored) return stored;
        return DEFAULT_ROTATE;
    }
}
