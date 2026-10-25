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
        City(String name, String alt, String lat, String lon, int pop, String tz, String region) {
            this.name = name; this.alt = alt; this.lat = lat; this.lon = lon; this.pop = pop; this.tz = tz; this.region = region;
        }
    }

    private static List<Country> countries;
    private static String citiesOf;   // the country whose cities are cached
    private static List<City> cities;

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

    /** a country's bundled cities, biggest first; the last country asked is kept */
    static synchronized List<City> cities(Context c, String cc) {
        if (cc == null) return Collections.emptyList();
        cc = cc.toUpperCase(Locale.ROOT);
        if (cc.equals(citiesOf) && cities != null) return cities;
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
        citiesOf = cc;
        cities = Collections.unmodifiableList(out);
        return cities;
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
