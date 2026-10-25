package com.raad.sauditv.home;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * «شكل الأرقام» for the launcher's own text (the quick panel's clock, the favourites' counts and menus): 0 1 2 3 (the
 * default) or ٠ ١ ٢ ٣, as the screensaver app's settings say (its ConfigProvider's numerals column, also in the page's
 * fragment as numerals=). HomePage sets it with every settings read (start, resume, CONFIG_CHANGED); load() asks the
 * provider itself (a screen opened without the home screen).
 */
final class Numerals {
    private static volatile boolean arabic;

    private Numerals() {}

    static void set(String v) { arabic = "arab".equals(v); }

    /** from a settings fragment ("…&numerals=arab&…"); absent: 0-9 */
    static void fromFragment(String frag) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|&)numerals=([a-z]+)").matcher(frag == null ? "" : frag);
        set(m.find() ? m.group(1) : "latn");
    }

    static void load(Context c) {
        try (Cursor k = c.getContentResolver().query(Uri.parse("content://com.raad.sauditv.screensaver.config"), null, null, null, null)) {
            int i = k == null ? -1 : k.getColumnIndex("numerals");
            if (i >= 0 && k.moveToFirst()) set(k.getString(i));
        } catch (RuntimeException ignored) { }
    }

    /** the digits of s (either form) in the chosen one */
    static String digits(String s) {
        if (s == null) return "";
        boolean arab = arabic;
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (arab && ch >= '0' && ch <= '9') b.append((char) ('٠' + (ch - '0')));
            else if (!arab && ch >= '٠' && ch <= '٩') b.append((char) ('0' + (ch - '٠')));
            else b.append(ch);
        }
        return b.toString();
    }

    static String digits(long n) { return digits(String.valueOf(n)); }
}
