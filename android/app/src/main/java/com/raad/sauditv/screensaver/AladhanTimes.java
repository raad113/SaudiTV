package com.raad.sauditv.screensaver;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The page's online prayer times (src/template.html loadPrayers): the same Aladhan request, built the way the page builds
 * it, so the athan calls at the minute the page shows while it is online (offline both fall back to the same formula,
 * PrayerTimes). The page builds its URL from its #fragment (Prefs.fragment: lat, lon, method, ptz) through
 * parseFloat and JavaScript's number-to-text, and encodeURIComponent for the zone: url() does the same steps.
 * tests/java_test.py has node build the page's own URL for the same inputs and AthanTest compares them.
 *
 * Plain Java (no android.*): tests/java_test.py compiles it without Android.
 */
final class AladhanTimes {
    private AladhanTimes() {}

    /** the page's CONFIG defaults when #lat / #lon / #method are missing or not numbers */
    static final double DEFAULT_LAT = 24.7136, DEFAULT_LON = 46.6753;
    static final int DEFAULT_METHOD = 4;

    /**
     * The page's request for the city's day y-mo-d: https://api.aladhan.com/v1/timings/DD-MM-YYYY?latitude=…
     * &longitude=…&method=…&timezonestring=… . lat, lon: the settings' strings (Prefs.city), as the page's fragment
     * carries them; method: Prefs.prayerMethod; tz: Prefs.placeTz (the page's #ptz).
     */
    static String url(int y, int mo, int d, String lat, String lon, int method, String tz) {
        return "https://api.aladhan.com/v1/timings/" + pad(d) + "-" + pad(mo) + "-" + y
            + "?latitude=" + jsNumber(parseFloat(lat, DEFAULT_LAT)) + "&longitude=" + jsNumber(parseFloat(lon, DEFAULT_LON))
            + "&method=" + jsNumber(method) + "&timezonestring=" + encodeURIComponent(tz == null || tz.isEmpty() ? "Asia/Riyadh" : tz);
    }

    private static String pad(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    private static final Pattern FLOAT = Pattern.compile("^[\\t\\n\\x0B\\f\\r ]*([+-]?(?:\\d+\\.?\\d*(?:[eE][+-]?\\d+)?|\\.\\d+(?:[eE][+-]?\\d+)?|Infinity))");

    /** JavaScript's parseFloat (the page's _num): the longest number at the start; none -> def */
    static double parseFloat(String s, double def) {
        if (s == null) return def;
        Matcher m = FLOAT.matcher(s);
        if (!m.find()) return def;
        String n = m.group(1);
        if (n.endsWith("Infinity")) return n.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        try { return Double.parseDouble(n); } catch (NumberFormatException e) { return def; }
    }

    /**
     * A number as JavaScript writes it (String(x), in a template literal): the fewest digits that read back as the same
     * double, no ".0" on whole numbers, -0 as "0"; plain notation from 1e-6 to 1e21, exponent outside (as JS).
     */
    static String jsNumber(double x) {
        if (Double.isNaN(x)) return "NaN";
        if (Double.isInfinite(x)) return x > 0 ? "Infinity" : "-Infinity";
        if (x == 0) return "0";
        BigDecimal exact = new BigDecimal(x), best = null;
        for (int p = 1; p <= 17; p++) {
            BigDecimal r = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
            if (r.doubleValue() == x) { best = r; break; }
        }
        if (best == null) best = exact;
        best = best.stripTrailingZeros();
        double ax = Math.abs(x);
        if (ax >= 1e-6 && ax < 1e21) return best.toPlainString();
        // JS: d[.ddd]e±n
        String digits = best.unscaledValue().abs().toString();
        int exp = digits.length() - 1 - best.scale();
        String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        return (x < 0 ? "-" : "") + mant + "e" + (exp < 0 ? "-" : "+") + Math.abs(exp);
    }

    /** JavaScript's encodeURIComponent: UTF-8, all but A-Z a-z 0-9 - _ . ! ~ * ' ( ) percent-encoded (upper-case hex) */
    static String encodeURIComponent(String s) {
        StringBuilder b = new StringBuilder();
        for (byte by : s.getBytes(StandardCharsets.UTF_8)) {
            int c = by & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.!~*'()".indexOf(c) >= 0) b.append((char) c);
            else b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                  .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
        }
        return b.toString();
    }

    private static final String[] KEYS = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};

    /**
     * Aladhan's answer -> the minutes after midnight of Fajr, Dhuhr, Asr, Maghrib and Isha (AthanPlan.PRAYERS' order), as
     * the page reads them (data.timings.<name>, "HH:MM"); null when any is missing (the page then shows its offline
     * times, and so do we).
     */
    static int[] parse(String json) {
        if (json == null) return null;
        int t = json.indexOf("\"timings\"");
        if (t < 0) return null;
        int[] out = new int[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            Matcher m = Pattern.compile("\"" + KEYS[i] + "\"\\s*:\\s*\"(\\d{1,2}):(\\d{2})").matcher(json);
            if (!m.find(t)) return null;
            int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2));
            if (h > 23 || mi > 59) return null;
            out[i] = h * 60 + mi;
        }
        return out;
    }

    /** the cache's key: the day and everything of the request but the day (a new city, method or zone: a new key) */
    static String key(int y, int mo, int d, String lat, String lon, int method, String tz) {
        return y + "-" + pad(mo) + "-" + pad(d) + "|" + url(y, mo, d, lat, lon, method, tz).replaceFirst("^[^?]*\\?", "");
    }

    /** "260,698,890,1032,1122" <-> the five minutes */
    static String store(int[] m) {
        StringBuilder b = new StringBuilder();
        for (int v : m) b.append(b.length() > 0 ? "," : "").append(v);
        return b.toString();
    }

    static int[] load(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        if (p.length != KEYS.length) return null;
        int[] out = new int[p.length];
        try { for (int i = 0; i < p.length; i++) out[i] = Integer.parseInt(p[i].trim()); }
        catch (NumberFormatException e) { return null; }
        for (int v : out) if (v < 0 || v >= 1440) return null;
        return out;
    }
}
