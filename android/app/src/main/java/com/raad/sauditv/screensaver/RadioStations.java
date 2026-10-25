package com.raad.sauditv.screensaver;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * «إذاعة القرآن الكريم»: its stations, without Android (tests/java_test.py runs it). First the two Quran radios
 * (PINNED: each with a second address, tried when the first fails), then mp3quran.net's list of radios
 * (www.mp3quran.net/api/v3/radios?language=ar, no key: its reciters first, then its programmes; Radio reads and keeps
 * it), and FALLBACK while that list was never read. «رابط من عندي»: any http(s) address (CUSTOM).
 *
 * Station ids: "saudi", "cairo", an mp3quran.net radio id ("63") or "custom"; optimize/saver.sh radio-station takes
 * one of them or an address.
 */
final class RadioStations {
    private RadioStations() {}

    static final String SAUDI = "saudi", CAIRO = "cairo", CUSTOM = "custom";
    static final int VOLUME_MIN = 10, VOLUME_MAX = 100, VOLUME_DEFAULT = 50, VOLUME_STEP = 10;

    static final class Station {
        final String id, name;
        final String[] urls;   // the first, then the mirrors
        Station(String id, String name, String... urls) { this.id = id; this.name = name; this.urls = urls; }
    }

    // The Saudi Quran radio (the Saudi Broadcasting Authority's, on Radiojar: mp3quran.net lists the same address as
    // «إذاعة القرآن الكريم - السعودية», aloula.sba.sa/ar/live/quranradio links it), and Cairo's (Maspero's, on Radiojar;
    // the second address is the HLS stream misrquran.gov.eg plays). Both Radiojar addresses send on to an http:// node
    // (the manifest allows cleartext for that).
    static final Station[] PINNED = {
        new Station(SAUDI, "إذاعة القرآن الكريم من السعودية",
            "https://stream.radiojar.com/0tpy1h0kxtzuv", "http://n0a.radiojar.com/0tpy1h0kxtzuv"),
        new Station(CAIRO, "إذاعة القرآن الكريم من القاهرة",
            "https://stream.radiojar.com/8s5u5tpdtwzuv",
            "https://service.webvideocore.net/CL1olYogIrDWvwqiIKK7eCxOS4PStqG9DuEjAr2ZjZQtvS3d4y9r0cvRhvS17SGN/a_7a4vuubc6mo8.m3u8"),
    };

    // a few of mp3quran.net's reciters (id, name, address as its list has them), for a TV that never read the list
    static final String[][] FALLBACK = {
        {"63", "ماهر المعيقلي", "https://backup.qurango.net/radio/maher"},
        {"33", "عبدالرحمن السديس", "https://backup.qurango.net/radio/abdulrahman_alsudaes"},
        {"18", "سعود الشريم", "https://backup.qurango.net/radio/saud_alshuraim"},
        {"79", "مشاري العفاسي", "https://backup.qurango.net/radio/mishary_alafasi"},
        {"58", "ياسر الدوسري", "https://backup.qurango.net/radio/yasser_aldosari"},
        {"17", "سعد الغامدي", "https://backup.qurango.net/radio/saad_alghamdi"},
        {"3", "أحمد العجمي", "https://backup.qurango.net/radio/ahmad_alajmy"},
        {"53", "ناصر القطامي", "https://backup.qurango.net/radio/nasser_alqatami"},
        {"36", "عبدالله بصفر", "https://backup.qurango.net/radio/abdullah_basfer"},
        {"24", "صلاح البدير", "https://backup.qurango.net/radio/salah_albudair"},
        {"46", "علي بن عبدالرحمن الحذيفي", "https://backup.qurango.net/radio/ali_alhuthaifi"},
        {"68", "محمد جبريل", "https://backup.qurango.net/radio/mohammed_jibreel"},
        {"32", "عبدالباسط عبدالصمد", "https://backup.qurango.net/radio/abdulbasit_abdulsamad"},
        {"74", "محمود خليل الحصري", "https://backup.qurango.net/radio/mahmoud_khalil_alhussary"},
        {"69", "محمد صديق المنشاوي", "https://backup.qurango.net/radio/mohammed_siddiq_alminshawi"},
        {"80", "مصطفى إسماعيل", "https://backup.qurango.net/radio/mustafa_ismail"},
        {"108", "العامة - متنوعة لمختلف القراء", "https://backup.qurango.net/radio/mix"},
        {"123", "تراتيل قصيرة متميزة", "https://backup.qurango.net/radio/tarateel"},
    };

    /** an mp3quran.net address and its mirror: qurango.net and backup.qurango.net carry the same radios. qurango.net
     *  first: through the TVs' VPN backup.qurango.net answered several radios with HTTP 500 (2026-10-24), qurango.net
     *  all of them */
    static String[] mirrors(String url) {
        if (url == null) return new String[0];
        String u = url.trim();
        if (u.startsWith("https://backup.qurango.net/")) return new String[] {"https://qurango.net/" + u.substring(27), u};
        if (u.startsWith("https://qurango.net/")) return new String[] {u, "https://backup.qurango.net/" + u.substring(20)};
        return new String[] {u};
    }

    /** an address the player can open: http or https, a host, no spaces */
    static boolean validUrl(String u) {
        return u != null && u.matches("(?i)https?://[^\\s/?#]+\\.[^\\s/?#]+(/\\S*)?") && u.length() <= 500;
    }

    /** a name as mp3quran.net writes it, tidied: «تلاوات خاشعة-» and «سورة البقرة - …---» lose their trailing dashes */
    static String tidy(String name) {
        if (name == null) return "";
        String n = name.replaceAll("[\\s\\-*]+$", "").replaceAll("^[\\s\\-*]+", "").replaceAll("\\s+", " ").trim();
        return n;
    }

    /**
     * The picker's list: PINNED, then api's radios ({id, name, url} in its order; null or empty: FALLBACK), each once:
     * a radio of the list at a pinned station's address (the Saudi one) is left out, as is one without an id, a name
     * or a usable address.
     */
    static List<Station> merge(List<String[]> api) {
        List<String[]> src = api == null || api.isEmpty() ? java.util.Arrays.asList(FALLBACK) : api;
        List<Station> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Station p : PINNED) { out.add(p); for (String u : p.urls) seen.add(key(u)); }
        for (String[] r : src) {
            if (r == null || r.length < 3) continue;
            String id = r[0] == null ? "" : r[0].trim(), name = tidy(r[1]), url = r[2] == null ? "" : r[2].trim();
            if (!id.matches("\\d{1,9}") || name.isEmpty() || !validUrl(url) || !seen.add(key(url))) continue;
            out.add(new Station(id, name, mirrors(url)));
        }
        return out;
    }

    /** an address compared without its scheme and a trailing slash */
    private static String key(String url) {
        return url.toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("/+$", "");
    }

    static Station find(List<Station> all, String id) {
        if (all == null || id == null) return null;
        for (Station s : all) if (s.id.equals(id)) return s;
        return null;
    }

    /** the stations whose name holds q (folded: no harakat, one alif, ه for ة, ي for ى, no «ال» at a word's start) */
    static List<Station> search(List<Station> all, String q) {
        List<Station> out = new ArrayList<>();
        String want = fold(q);
        if (all == null) return out;
        for (Station s : all) if (want.isEmpty() || fold(s.name).contains(want)) out.add(s);
        return out;
    }

    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "").replaceAll("[ً-ٰٟـ]", "");
        n = n.toLowerCase(Locale.ROOT).replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
            .replace('ة', 'ه').replace('ى', 'ي').replace('-', ' ').replaceAll("\\s+", " ").trim();
        // «ال» at a word's start (القحطاني = قحطاني), so «قحطاني» and «القحطاني» find the same
        return (" " + n).replaceAll(" ال(?=\\S{2,})", " ").trim();
    }

    /** "40" or "40%" -> 40, rounded to VOLUME_STEP and kept in VOLUME_MIN … VOLUME_MAX; -1 when it is no number in range */
    static int volume(String v) {
        if (v == null) return -1;
        String t = v.trim().replace("%", "").replace("٪", "");
        if (!t.matches("\\d{1,3}")) return -1;
        int n = Integer.parseInt(t);
        if (n < VOLUME_MIN || n > VOLUME_MAX) return -1;
        return clampVolume(n);
    }

    static int clampVolume(int n) {
        int r = Math.round(n / (float) VOLUME_STEP) * VOLUME_STEP;
        return Math.max(VOLUME_MIN, Math.min(VOLUME_MAX, r));
    }

    /** the player's gain for a volume in percent, squared: a loudness step per 10 %, not a big jump at the low end */
    static float gain(int percent) {
        float x = clampVolume(percent) / 100f;
        return x * x;
    }
}
