package com.raad.sauditv.screensaver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * «عناصر شريط الأسعار»: the ticker's items, without Android (tests/java_test.py runs it). The page knows the same ids
 * (src/template.html: TAPE_SA, TAPE_MORE, tapeList): cur and idx follow the ticker's country (the news country), i:XX is
 * a country's index (i:AD Abu Dhabi's), c:XXX the dollar in that currency. Nothing chosen («تلقائي», stored as ""): each
 * country's own list, as before. A choice is kept whatever the country (only cur and idx follow it).
 */
final class Tape {
    private Tape() {}

    /** id, the settings' label, in the picker's order (each checked through the TVs' VPN: the page's #tapetest=1) */
    static final String[][] ITEMS = {
        {"cur", "عملة الدولة"}, {"idx", "مؤشر الدولة"},
        {"gold", "الذهب"}, {"silver", "الفضة"}, {"brent", "نفط برنت"}, {"btc", "بيتكوين"}, {"eth", "إيثريوم"},
        {"spx", "S&P 500"}, {"ndx", "ناسداك"}, {"dji", "داو جونز"},
        {"i:SA", "تاسي (السعودية)"}, {"i:AE", "مؤشر دبي"}, {"i:AD", "مؤشر أبوظبي"}, {"i:QA", "مؤشر قطر"},
        {"i:KW", "بورصة الكويت"}, {"i:EG", "إيجي إكس 30 (مصر)"},
        {"eurusd", "اليورو/الدولار"}, {"c:SAR", "الدولار/الريال السعودي"}, {"c:AED", "الدولار/الدرهم الإماراتي"},
        {"c:EGP", "الدولار/الجنيه المصري"}, {"c:KWD", "الدولار/الدينار الكويتي"}, {"c:QAR", "الدولار/الريال القطري"},
        {"c:BHD", "الدولار/الدينار البحريني"}, {"c:OMR", "الدولار/الريال العماني"},
    };
    /** the countries with an index of their own (the page's TAPE_INDEX; tests/check.py compares the two) */
    static final List<String> INDEX = Arrays.asList(("SA EG AE QA KW GB DE FR ES IT NL BE AT CH SE DK FI NO PT IE PL HU GR "
        + "TR JP CN HK KR TW IN ID MY SG TH PH AU NZ CA BR MX ZA").split(" "));
    /** the currencies offered above and their countries: their items are the country's own there (not listed twice) */
    private static final String[][] CUR_OF = {{"c:SAR", "SA"}, {"c:AED", "AE"}, {"c:EGP", "EG"}, {"c:KWD", "KW"},
        {"c:QAR", "QA"}, {"c:BHD", "BH"}, {"c:OMR", "OM"}};

    private static String cc(String cc) {
        return cc == null || !cc.trim().matches("[A-Za-z]{2}") ? "SA" : cc.trim().toUpperCase(Locale.ROOT);
    }

    static boolean hasIndex(String cc) { return INDEX.contains(cc(cc)); }

    /** a country's own list, in the page's order: its index first where it has one */
    static List<String> auto(String cc) {
        List<String> out = new ArrayList<>(Arrays.asList("idx", "spx", "ndx", "dji", "gold", "brent", "cur", "btc", "eth"));
        if (!hasIndex(cc)) out.remove("idx");
        return out;
    }

    static boolean known(String id) {
        for (String[] x : ITEMS) if (x[0].equals(id)) return true;
        return false;
    }

    static String label(String id) {
        for (String[] x : ITEMS) if (x[0].equals(id)) return x[1];
        return id;
    }

    /** the picker's ids for country cc: the country's own index and currency are not listed again by name */
    static List<String> offered(String cc) {
        String c = cc(cc);
        List<String> out = new ArrayList<>();
        for (String[] x : ITEMS) {
            boolean twice = x[0].equals("i:" + c);
            for (String[] k : CUR_OF) twice |= k[0].equals(x[0]) && k[1].equals(c);
            if (!twice) out.add(x[0]);
        }
        return out;
    }

    /** "gold, btc,gold" -> [gold, btc] (each once, in order); "" or "auto" -> [] (the country's own); null when an id is
     *  unknown */
    static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String r = raw.trim();
        if (r.isEmpty() || r.equalsIgnoreCase("auto")) return out;
        for (String s : r.split("[,;]")) {
            s = s.trim();
            if (s.isEmpty()) continue;
            if (s.length() > 2 && s.charAt(1) == ':') s = s.substring(0, 2).toLowerCase(Locale.ROOT) + s.substring(2).toUpperCase(Locale.ROOT);
            else s = s.toLowerCase(Locale.ROOT);
            if (!known(s)) return null;
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    /** what is stored (and sent as #tape): "" when nothing is chosen or the choice is the country's own list */
    static String store(List<String> ids, String cc) {
        if (ids == null || ids.isEmpty() || ids.equals(auto(cc))) return "";
        StringBuilder b = new StringBuilder();
        for (String s : ids) { if (b.length() > 0) b.append(','); b.append(s); }
        return b.toString();
    }
}
