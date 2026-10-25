package com.raad.sauditv.screensaver;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The bundled countries and cities (assets/geo/, made by .github/tools/gen_geo.py from GeoNames and CLDR), and what a
 * country decides by default: the prayer method («طريقة الحساب») and the temperature unit.
 */
final class Geo {
    private Geo() {}

    static final class Country {
        final String cc, ar, shortAr, en, continent, lang;
        final int cities;
        Country(String[] f) {
            cc = f[0]; ar = f[1]; shortAr = f[2]; en = f[3]; continent = f[4]; lang = f[5];
            int n = 0;
            try { n = Integer.parseInt(f[6]); } catch (RuntimeException ignored) { }
            cities = n;
        }
    }

    static final class City {
        final String name, alt, lat, lon, tz, region;
        final int pop;
        private String nameKey, regionKey;   // key(name) + "\n" + key(alt), key(region): made on first search
        City(String name, String alt, String lat, String lon, int pop, String tz, String region) {
            this.name = name; this.alt = alt; this.lat = lat; this.lon = lon; this.pop = pop; this.tz = tz; this.region = region;
        }
    }

    private static List<Country> countries;
    // the countries whose cities were read, the last used kept: the US file alone is 16,000 cities (a few hundred ms
    // to read on a TV), so a country is read once while the app runs (up to CACHE countries)
    private static final int CACHE = 6;
    private static final Map<String, List<City>> CITIES = new java.util.LinkedHashMap<String, List<City>>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<City>> e) { return size() > CACHE; }
    };
    /** the cities shown before a search: the biggest TOP (the full list is searched: search) */
    static final int TOP = 40;
    /** at most this many cities a search lists */
    static final int SEARCH_MAX = 50;

    /** Every country, in Arabic order (the file's) */
    static synchronized List<Country> countries(Context c) {
        if (countries != null) return countries;
        List<Country> out = new ArrayList<>();
        try (InputStream in = c.getAssets().open("geo/countries.tsv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                if (f.length >= 7) out.add(new Country(f));
            }
        } catch (Exception ignored) { }
        countries = Collections.unmodifiableList(out);
        return countries;
    }

    /** null when unknown or empty */
    static Country country(Context c, String cc) {
        if (cc == null || cc.isEmpty()) return null;
        for (Country x : countries(c)) if (x.cc.equalsIgnoreCase(cc)) return x;
        return null;
    }

    /** true when countries() answers without reading the file */
    static synchronized boolean countriesLoaded() { return countries != null; }

    /** true when cities(cc) answers without reading the file */
    static synchronized boolean citiesLoaded(String cc) { return cc != null && CITIES.containsKey(cc.toUpperCase(Locale.ROOT)); }

    /** a country's bundled cities, biggest first (kept in memory: CITIES). Reads a file: not on the main thread */
    static synchronized List<City> cities(Context c, String cc) {
        if (cc == null) return Collections.emptyList();
        cc = cc.toUpperCase(Locale.ROOT);
        List<City> got = CITIES.get(cc);
        if (got != null) return got;
        List<City> out = new ArrayList<>();
        try (InputStream in = open(c, "geo/cities/" + cc + ".tsv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String[] zones = new String[0], regions = new String[0];
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("#tz\t")) { zones = line.substring(4).split("\t", -1); continue; }
                if (line.startsWith("#region\t")) { regions = line.substring(8).split("\t", -1); continue; }
                String[] f = line.split("\t", -1);
                if (f.length < 6) continue;
                int z = 0, g = -1, pop = 0;
                try {
                    z = Integer.parseInt(f[5]);
                    pop = Integer.parseInt(f[4]);
                    if (f.length > 6) g = Integer.parseInt(f[6]);
                } catch (RuntimeException ignored) { }
                out.add(new City(f[0], f[1], f[2], f[3], pop, z < zones.length ? zones[z] : "",
                    g >= 0 && g < regions.length ? regions[g] : ""));
            }
        } catch (Exception ignored) { }
        got = Collections.unmodifiableList(out);
        CITIES.put(cc, got);
        return got;
    }

    /** the first n of a list (the biggest cities) */
    static List<City> top(List<City> all, int n) { return all.size() <= n ? all : all.subList(0, n); }

    /** the country's cities whose Arabic or English name holds q, then those whose region does (each biggest first), at
     *  most SEARCH_MAX. Reads and folds the whole list: not on the main thread */
    static List<City> search(Context c, String cc, String q) {
        String want = key(q);
        List<City> out = new ArrayList<>(), byRegion = new ArrayList<>();
        if (want.isEmpty()) return out;
        for (City x : cities(c, cc)) {
            synchronized (x) {
                if (x.nameKey == null) { x.nameKey = key(x.name) + "\n" + key(x.alt); x.regionKey = key(x.region); }
            }
            if (x.nameKey.contains(want)) { out.add(x); if (out.size() >= SEARCH_MAX) return out; }
            else if (byRegion.size() < SEARCH_MAX && x.regionKey.contains(want)) byRegion.add(x);
        }
        for (City x : byRegion) { if (out.size() >= SEARCH_MAX) break; out.add(x); }
        return out;
    }

    // ---------- «اكتب اسم مدينتك»: a city of any country (assets/geo/names.tsv.gz, every country's cities biggest
    // first: name, English name, country, its line in the country's file). Held as two long strings (the names' search
    // keys, and the lines as they are) rather than 139,000 objects; dropped when memory runs short.

    /** a city found by searchAll: its names and country; the rest of the place is line `line` of its country's file */
    static final class Place {
        final String name, alt, cc;
        final int line;
        Place(String name, String alt, String cc, int line) { this.name = name; this.alt = alt; this.cc = cc; this.line = line; }
    }

    private static final class Names {
        String keys;     // "\n" + key(name) + "\t" + key(alt) per city, in the file's order
        int[] keyAt;     // where each city starts in keys
        String raw;      // the file's lines
        int[] rawAt;     // where each city's line starts in raw
    }
    private static java.lang.ref.SoftReference<Names> names = new java.lang.ref.SoftReference<>(null);
    /** at most this many cities searchAll lists */
    static final int ALL_MAX = 30;

    /** reads names.tsv.gz once (kept until memory runs short). Reads a file: not on the main thread */
    private static synchronized Names names(Context c) {
        Names n = names.get();
        if (n != null) return n;
        n = new Names();
        StringBuilder keys = new StringBuilder(4_000_000), raw = new StringBuilder(4_200_000);
        int[] keyAt = new int[150_000], rawAt = new int[150_000];
        int count = 0;
        try (InputStream in = open(c, "geo/names.tsv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            for (String line; (line = r.readLine()) != null; ) {
                int t = line.indexOf('\t');
                if (t < 0) continue;
                int t2 = line.indexOf('\t', t + 1);
                if (t2 < 0) continue;
                if (count == keyAt.length) { keyAt = Arrays.copyOf(keyAt, count * 2); rawAt = Arrays.copyOf(rawAt, count * 2); }
                keyAt[count] = keys.length();
                rawAt[count] = raw.length();
                keys.append('\n').append(key(line.substring(0, t))).append('\t').append(key(line.substring(t + 1, t2)));
                raw.append(line).append('\n');
                count++;
            }
        } catch (Exception ignored) { }
        n.keys = keys.toString();
        n.raw = raw.toString();
        n.keyAt = Arrays.copyOf(keyAt, count);
        n.rawAt = Arrays.copyOf(rawAt, count);
        names = new java.lang.ref.SoftReference<>(n);
        return n;
    }

    /** reads the cities of every country ahead of a search (while the name is being typed) */
    static void preloadNames(Context c) { names(c); }

    /** cities of any country whose Arabic or English name starts with q, then those that hold it elsewhere (each
     *  biggest first), at most ALL_MAX. Not on the main thread */
    static List<Place> searchAll(Context c, String q) {
        String want = key(q);
        List<Place> out = new ArrayList<>();
        if (want.isEmpty()) return out;
        Names n = names(c);
        List<Integer> first = new ArrayList<>(), rest = new ArrayList<>();
        int from = 0, len = n.keys.length();
        while (from < len && first.size() < ALL_MAX) {
            int at = n.keys.indexOf(want, from);
            if (at < 0) break;
            int i = Arrays.binarySearch(n.keyAt, at);
            if (i < 0) i = -i - 2;
            char before = n.keys.charAt(at - 1);
            boolean start = before == '\n' || before == '\t' || before == ' ';
            if (start) { first.add(i); rest.remove(Integer.valueOf(i)); }
            else if (rest.size() < ALL_MAX && !first.contains(i) && !rest.contains(i)) rest.add(i);
            // the same city again only for a better match: its next one at the start of a name or word
            from = start || i + 1 >= n.keyAt.length ? (i + 1 < n.keyAt.length ? n.keyAt[i + 1] : len) : at + 1;
        }
        for (Integer i : rest) { if (first.size() >= ALL_MAX) break; first.add(i); }
        for (int i : first) {
            int s = n.rawAt[i], e = n.raw.indexOf('\n', s);
            String[] f = n.raw.substring(s, e < 0 ? n.raw.length() : e).split("\t", -1);
            if (f.length < 4) continue;
            try { out.add(new Place(f[0], f[1], f[2], Integer.parseInt(f[3]))); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    /** the whole place of a city found by searchAll (from its country's file); null when not there */
    static City cityOf(Context c, Place p) {
        List<City> l = cities(c, p.cc);
        return p.line >= 0 && p.line < l.size() ? l.get(p.line) : null;
    }

    /** a search key: fold, plus ؤ = و, ئ = ي, no ء, - = space, and no «ال» at the start of a word (الإسكندرية =
     *  اسكندريه, القاهرة = قاهره). Made for every bundled city, so it avoids Normalizer for plain letters */
    static String key(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 0xC0 && ch < 0x250) || (ch >= 0x1E00 && ch < 0x1F00))   // Latin with accents: the letter alone
                ch = Normalizer.normalize(String.valueOf(ch), Normalizer.Form.NFD).charAt(0);
            if ((ch >= 0x300 && ch < 0x370) || (ch >= 0x64B && ch <= 0x65F) || ch == 0x670 || ch == 'ـ' || ch == 'ء') continue;
            switch (ch) {
                case 'أ': case 'إ': case 'آ': case 'ٱ': ch = 'ا'; break;
                case 'ة': ch = 'ه'; break;
                case 'ى': case 'ئ': ch = 'ي'; break;
                case 'ؤ': ch = 'و'; break;
                case '’': ch = '\''; break;
                case '-': ch = ' '; break;
                default: ch = Character.toLowerCase(ch);
            }
            b.append(ch);
        }
        // «ال» at the start of a word, when more than two letters follow
        for (int i = 0; i < b.length(); ) {
            boolean wordStart = i == 0 || b.charAt(i - 1) == ' ';
            if (wordStart && b.charAt(i) == 'ا' && i + 1 < b.length() && b.charAt(i + 1) == 'ل') {
                int end = i;
                while (end < b.length() && b.charAt(end) != ' ') end++;
                if (end - i > 4) { b.delete(i, i + 2); continue; }
            }
            i++;
        }
        return b.toString().trim();
    }

    /** a city file: the build stores assets/geo/cities/XX.tsv.gz unpacked as XX.tsv (the APK compresses it itself);
     *  the .gz as it is in the repository otherwise */
    private static InputStream open(Context c, String path) throws java.io.IOException {
        try { return c.getAssets().open(path); }
        catch (java.io.IOException e) { return new GZIPInputStream(c.getAssets().open(path + ".gz")); }
    }

    /** the bundled city of that name (Arabic or English, as listed), the biggest of that name; null when none */
    static City find(Context c, String cc, String name) {
        String want = fold(name);
        for (City x : cities(c, cc)) if (fold(x.name).equals(want) || (!x.alt.isEmpty() && fold(x.alt).equals(want))) return x;
        return null;
    }

    /** the bundled city nearest to lat/lon in that country (its time zone stands for the place's); null when none */
    static City nearest(Context c, String cc, double lat, double lon) {
        City best = null;
        double bd = Double.MAX_VALUE, k = Math.cos(Math.toRadians(lat));
        for (City x : cities(c, cc)) {
            double dy = Double.parseDouble(x.lat) - lat, dx = (Double.parseDouble(x.lon) - lon) * k;
            double d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = x; }
        }
        return best;
    }

    /** for search: lower case, no accents (Sulţānah = sultanah), Arabic without harakat and with one alif, ه for ة */
    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "");
        return n.toLowerCase(Locale.ROOT).replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي').replace("ـ", "").replace('’', '\'').trim();
    }

    // ---------- «طريقة الحساب»: Aladhan's method numbers (the page's PRAYER_METHODS lists the same, for its offline times)
    static final int KARACHI = 1, ISNA = 2, MWL = 3, UMM_AL_QURA = 4, EGYPT = 5, KUWAIT = 9, QATAR = 10, DIYANET = 13, DUBAI = 16;
    // value, Arabic label, saver.sh name
    static final String[][] METHODS = {
        {"4", "أم القرى (السعودية)", "umm-al-qura"},
        {"2", "أمريكا الشمالية (ISNA)", "isna"},
        {"5", "الهيئة المصرية العامة للمساحة", "egypt"},
        {"9", "الكويت", "kuwait"},
        {"10", "قطر", "qatar"},
        {"16", "دبي (الإمارات)", "dubai"},
        {"3", "رابطة العالم الإسلامي", "mwl"},
        {"1", "جامعة العلوم الإسلامية بكراتشي", "karachi"},
        {"13", "رئاسة الشؤون الدينية التركية", "diyanet"},
    };
    private static final Set<String> EUROPE = new HashSet<>(Arrays.asList(
        "AD", "AL", "AT", "AX", "BA", "BE", "BG", "BY", "CH", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FO", "FR", "GB",
        "GG", "GI", "GR", "HR", "HU", "IE", "IM", "IS", "IT", "JE", "LI", "LT", "LU", "LV", "MC", "MD", "ME", "MK", "MT",
        "NL", "NO", "PL", "PT", "RO", "RS", "SE", "SI", "SJ", "SK", "SM", "UA", "VA", "XK"));

    /** the method a country uses: SA Umm al-Qura, US/CA ISNA, EG Egyptian, KW, QA, AE their own, Europe MWL,
     *  PK/IN/BD Karachi, TR Diyanet, the others MWL */
    static int methodFor(String cc) {
        if (cc == null) cc = "";
        switch (cc.toUpperCase(Locale.ROOT)) {
            case "SA": return UMM_AL_QURA;
            case "US": case "CA": return ISNA;
            case "EG": return EGYPT;
            case "KW": return KUWAIT;
            case "QA": return QATAR;
            case "AE": return DUBAI;
            case "PK": case "IN": case "BD": return KARACHI;
            case "TR": return DIYANET;
            default: return MWL;   // Europe (EUROPE) and everywhere else
        }
    }

    static boolean europe(String cc) { return cc != null && EUROPE.contains(cc.toUpperCase(Locale.ROOT)); }

    static String methodLabel(int m) {
        for (String[] x : METHODS) if (x[0].equals(String.valueOf(m))) return x[1];
        return String.valueOf(m);
    }

    /** a method from saver.sh: its number or its name; -1 when unknown */
    static int method(String v) {
        if (v == null) return -1;
        for (String[] x : METHODS) if (x[0].equals(v) || x[2].equalsIgnoreCase(v)) return Integer.parseInt(x[0]);
        return -1;
    }

    // ---------- the temperature unit: °F where the weather is given in Fahrenheit (the US and its territories, and a
    // few others), °C everywhere else
    private static final Set<String> FAHRENHEIT = new HashSet<>(Arrays.asList(
        "US", "PR", "GU", "VI", "AS", "MP", "UM", "BS", "BZ", "KY", "LR", "PW", "FM", "MH"));

    static boolean fahrenheit(String cc) { return cc != null && FAHRENHEIT.contains(cc.toUpperCase(Locale.ROOT)); }

    /** the Arab League countries: their news in Arabic, their local news searched in Arabic */
    static final Set<String> ARAB = new HashSet<>(Arrays.asList(
        "SA", "AE", "KW", "QA", "BH", "OM", "YE", "IQ", "JO", "SY", "LB", "PS", "EG", "SD", "LY", "TN", "DZ", "MA", "MR",
        "SO", "DJ", "KM"));

    /** English country name -> the bundled short Arabic one (for national teams); null when none */
    static String arabicOf(Context c, String en) {
        if (en == null) return null;
        Map<String, String> m = byEnglish(c);
        String hit = m.get(en.toLowerCase(Locale.ROOT));
        return hit != null ? hit : NATIONAL_AR.get(en);
    }

    private static Map<String, String> english;
    private static synchronized Map<String, String> byEnglish(Context c) {
        if (english == null) {
            english = new HashMap<>();
            for (Country x : countries(c)) english.put(x.en.toLowerCase(Locale.ROOT), x.shortAr);
        }
        return english;
    }

    // ESPN's national teams whose English name is not the country's (or that are not countries)
    private static final Map<String, String> NATIONAL_AR = new HashMap<>();
    static {
        String[] v = {"England", "إنجلترا", "Scotland", "اسكتلندا", "Wales", "ويلز", "Northern Ireland", "أيرلندا الشمالية",
            "Republic of Ireland", "أيرلندا", "Ivory Coast", "ساحل العاج", "Congo DR", "الكونغو الديمقراطية",
            "Bosnia-Herzegovina", "البوسنة والهرسك", "Chinese Taipei", "تايبيه الصينية", "Hong Kong", "هونغ كونغ",
            "Macau", "ماكاو", "Myanmar", "ميانمار", "Kyrgyz Republic", "قيرغيزستان", "Sao Tome and Principe", "ساو تومي وبرينسيبي",
            "St. Kitts and Nevis", "سانت كيتس ونيفيس", "St. Vincent and the Grenadines", "سانت فنسنت وجزر غرينادين",
            "Trinidad and Tobago", "ترينيداد وتوباغو", "US Virgin Islands", "جزر فيرجن الأمريكية", "Tahiti", "تاهيتي",
            "Bonaire", "بونير", "USA", "الولايات المتحدة"};
        for (int i = 0; i < v.length; i += 2) NATIONAL_AR.put(v[i], v[i + 1]);
    }
}
