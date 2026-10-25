package com.raad.sauditv.screensaver;

/**
 * The athan's voices: the recordings bundled in the APK (assets/athan, credited in assets/athan/CREDITS.txt and in the
 * settings' «حقوق التسجيلات»), and «صوت من عندي» (a file or an address the user picked, copied into this TV's app
 * storage, never shared). The Fajr athan has its own choice.
 *
 * The Fajr default is a regular athan: the one Fajr recording with a clean licence (Malmö, 2012) could not be confirmed
 * to contain «الصلاة خير من النوم» (its 12 phrases are the count of a regular athan), so it is offered for Fajr only,
 * never as the default; FAJR_DEFAULT is the line to change once someone has listened to it. The Maldives recording
 * (its uploader says it is the Fajr call) is Fajr-only the same way and has not been listened to either.
 *
 * Plain Java (no android.*): tests/java_test.py compiles it without Android.
 */
final class AthanVoices {
    private AthanVoices() {}

    static final String CUSTOM = "custom";

    static final class Voice {
        final String id, ar, file, title, author, licence, licenceUrl, source;
        final boolean fajrOnly;
        Voice(String id, String ar, String file, boolean fajrOnly, String title, String author, String licence, String licenceUrl,
              String source) {
            this.id = id; this.ar = ar; this.file = file; this.fajrOnly = fajrOnly; this.title = title; this.author = author;
            this.licence = licence; this.licenceUrl = licenceUrl; this.source = source;
        }
    }

    static final String CC0 = "https://creativecommons.org/publicdomain/zero/1.0/";
    static final Voice[] BUNDLED = {
        new Voice("wikimedia", "أذان (ويكيميديا كومنز)", "athan/athan-wikimedia.ogg", false,
            "Beautiful adhan", "Adam-synagda", "CC0 1.0", CC0,
            "https://commons.wikimedia.org/wiki/File:Beautiful_adhan.ogg"),
        new Voice("asim", "عاصم جاويد", "athan/athan-asim-javed.ogg", false,
            "ICR Friday Prayer 1st Adhan, 17 May 2024", "Asim Javed", "CC0 1.0", CC0,
            "https://archive.org/details/icr-friday-prayer-1st-adhan-17th-may-2024-9th-dhul-qadah-1445-by-asim-javed"),
        new Voice("malmo", "فجر العيد، مسجد مالمو", "athan/athan-malmo.ogg", true,
            "Eid al-Fitr Fajr azan at Malmö Mosque, 19 August 2012", "Islamic Center Malmö", "CC BY 3.0",
            "https://creativecommons.org/licenses/by/3.0/",
            "https://commons.wikimedia.org/wiki/File:Eid_al-Fitr_Fajr_azan_at_Malm%C3%B6_Mosque_-_19_August_2012.webm"),
        new Voice("abudhabi1", "أذان من أبوظبي ١", "athan/athan-abudhabi-evening.ogg", false,
            "muezzin_abends_medicalcenter.flac", "nikitralala", "CC0 1.0", CC0,
            "https://freesound.org/people/nikitralala/sounds/331518/"),
        new Voice("abudhabi2", "أذان من أبوظبي ٢", "athan/athan-abudhabi-noon.ogg", false,
            "AbuDhabi_12_muezzin.flac", "nikitralala", "CC0 1.0", CC0,
            "https://freesound.org/people/nikitralala/sounds/331438/"),
        new Voice("maldives", "أذان فجر من جزر المالديف", "athan/athan-maldives-fajr.ogg", true,
            "Morning call to prayer on Bodufolhudhoo, Maldives", "jrosin", "CC BY 4.0",
            "https://creativecommons.org/licenses/by/4.0/",
            "https://freesound.org/people/jrosin/sounds/861625/"),
        new Voice("alain", "أذان من العين", "athan/athan-alain.ogg", false,
            "Muezzin in Al Ain, United Arab Emirates with birds", "Agim", "CC0 1.0", CC0,
            "https://freesound.org/people/Agim/sounds/417865/"),
        new Voice("makkah", "أذان من المسجد الحرام", "athan/athan-makkah.ogg", false,
            "Maghrib Adhan at the Masjid al Haram, Mecca, 25 Feb 2012", "3omar Faruq", "CC BY 3.0",
            "https://creativecommons.org/licenses/by/3.0/",
            "https://commons.wikimedia.org/wiki/File:Maghrib_Adhan_at_the_Masjid_al_Haram,_Mecca_-_25_Feb,_2012.webm"),
    };

    static final String DEFAULT = "wikimedia";
    /** a regular athan until a recording with «الصلاة خير من النوم» is confirmed (see above) */
    static final String FAJR_DEFAULT = "wikimedia";

    static Voice find(String id) {
        for (Voice v : BUNDLED) if (v.id.equals(id)) return v;
        return null;
    }

    /** the voices offered for the athan (fajr: the Fajr athan's list, which also has the Fajr-only ones) */
    static java.util.List<Voice> offered(boolean fajr) {
        java.util.List<Voice> out = new java.util.ArrayList<>();
        for (Voice v : BUNDLED) if (fajr || !v.fajrOnly) out.add(v);
        return out;
    }

    /** a stored choice as it plays: a bundled voice it may have, custom, else the default */
    static String of(String id, boolean fajr) {
        if (CUSTOM.equals(id)) return CUSTOM;
        Voice v = find(id);
        if (v != null && (fajr || !v.fajrOnly)) return v.id;
        return fajr ? FAJR_DEFAULT : DEFAULT;
    }

    static String label(String id, boolean fajr) {
        String v = of(id, fajr);
        if (CUSTOM.equals(v)) return "صوت من عندي";
        return find(v).ar;
    }

    /** an address for «صوت من عندي»: http(s) with a host */
    static boolean validUrl(String u) { return RadioStations.validUrl(u); }
}
