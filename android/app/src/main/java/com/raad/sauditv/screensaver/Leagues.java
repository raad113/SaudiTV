package com.raad.sauditv.screensaver;

import java.util.ArrayList;
import java.util.List;

/**
 * The football leagues of the team picker («الفرق المتابعة») and of the table («الدوري في الترتيب»): country (or
 * «منتخبات»), then its leagues. A league id is ESPN's slug (site.api.espn.com/apis/site/v2/sports/soccer/<slug>/teams,
 * /apis/v2/sports/soccer/<slug>/standings: no key), or "tsdb.<id>" for one ESPN does not carry, read from TheSportsDB's
 * free API instead (search_all_teams.php lists 10 of its clubs and lookuptable.php the top 5 of its table with the free
 * key; ids checked by hand with search_all_leagues.php?c=<country>). The page reads the same ids (#league).
 *
 * Followed teams are stored as "E<ESPN team id>@<league>" (ESPN), "<TheSportsDB id>@tsdb.<league id>" (a club of a
 * TheSportsDB league) or "<TheSportsDB id>" (older builds, and the search by name).
 */
final class Leagues {
    private Leagues() {}

    /** ESPN's national teams (every one it has plays friendlies); «منتخبات» */
    static final String NATIONAL = "fifa.friendly";
    /** the table older builds always showed */
    static final String ROSHN = "ksa.1";

    static final class League {
        final String id, ar, title;   // title: in «ترتيب <title>»
        String en = "";               // TheSportsDB's name of a tsdb league (search_all_teams.php?l=)
        League(String id, String ar, String title) { this.id = id; this.ar = ar; this.title = title; }
        boolean espn() { return !id.startsWith("tsdb."); }
        /** TheSportsDB's league id ("4829"), "" for an ESPN league */
        String tsdb() { return espn() ? "" : id.substring(5); }
    }

    static final class Group {
        final String cc, ar;   // cc: ISO 3166 (England and Scotland: GB-ENG, GB-SCT)
        final League[] leagues;
        Group(String cc, String ar, League... leagues) { this.cc = cc; this.ar = ar; this.leagues = leagues; }
    }

    private static League l(String id, String ar, String title) { return new League(id, ar, title); }
    private static League l(String id, String ar) { return new League(id, ar, ar); }

    // Arab countries first (as in the news settings), then the others, each in Arabic order
    static final Group[] GROUPS = {
        new Group("SA", "السعودية", l(ROSHN, "دوري روشن السعودي", "دوري روشن"), l("tsdb.5627", "دوري يلو (الدرجة الأولى)", "دوري يلو")),
        new Group("AE", "الإمارات", l("tsdb.4678", "دوري المحترفين الإماراتي", "الدوري الإماراتي")),
        new Group("JO", "الأردن", l("tsdb.5055", "دوري المحترفين الأردني", "الدوري الأردني")),
        new Group("BH", "البحرين", l("tsdb.4826", "الدوري البحريني الممتاز", "الدوري البحريني")),
        new Group("DZ", "الجزائر", l("tsdb.4753", "الرابطة المحترفة الأولى الجزائرية", "الدوري الجزائري")),
        new Group("IQ", "العراق", l("tsdb.5056", "دوري نجوم العراق", "الدوري العراقي")),
        new Group("KW", "الكويت", l("tsdb.4823", "الدوري الكويتي الممتاز", "الدوري الكويتي")),
        new Group("MA", "المغرب", l("tsdb.4520", "البطولة الاحترافية المغربية", "الدوري المغربي")),
        new Group("TN", "تونس", l("tsdb.4828", "الرابطة المحترفة الأولى التونسية", "الدوري التونسي")),
        new Group("SY", "سوريا", l("tsdb.5256", "الدوري السوري الممتاز", "الدوري السوري")),
        new Group("OM", "عُمان", l("tsdb.5250", "دوري المحترفين العماني", "الدوري العماني")),
        new Group("QA", "قطر", l("tsdb.4663", "دوري نجوم قطر", "الدوري القطري")),
        new Group("LB", "لبنان", l("tsdb.5243", "الدوري اللبناني الممتاز", "الدوري اللبناني")),
        new Group("EG", "مصر", l("tsdb.4829", "الدوري المصري الممتاز", "الدوري المصري")),

        new Group("DE", "ألمانيا", l("ger.1", "الدوري الألماني"), l("ger.2", "الدرجة الثانية الألمانية")),
        new Group("AR", "الأرجنتين", l("arg.1", "الدوري الأرجنتيني"), l("arg.2", "الدرجة الثانية الأرجنتينية")),
        new Group("EC", "الإكوادور", l("ecu.1", "الدوري الإكوادوري")),
        new Group("ES", "إسبانيا", l("esp.1", "الدوري الإسباني"), l("esp.2", "الدرجة الثانية الإسبانية")),
        new Group("AU", "أستراليا", l("aus.1", "الدوري الأسترالي")),
        new Group("GB-SCT", "اسكتلندا", l("sco.1", "الدوري الاسكتلندي الممتاز", "الدوري الاسكتلندي"), l("sco.2", "دوري البطولة الاسكتلندي")),
        new Group("GB-ENG", "إنجلترا", l("eng.1", "الدوري الإنجليزي الممتاز", "الدوري الإنجليزي"),
            l("eng.2", "دوري البطولة الإنجليزي (تشامبيونشيب)", "دوري البطولة الإنجليزي"), l("eng.3", "دوري الدرجة الأولى الإنجليزي"),
            l("eng.4", "دوري الدرجة الثانية الإنجليزي")),
        new Group("UY", "الأوروغواي", l("uru.1", "الدوري الأوروغوياني")),
        new Group("IT", "إيطاليا", l("ita.1", "الدوري الإيطالي"), l("ita.2", "الدرجة الثانية الإيطالية")),
        new Group("PY", "باراغواي", l("par.1", "الدوري الباراغوياني")),
        new Group("BR", "البرازيل", l("bra.1", "الدوري البرازيلي"), l("bra.2", "الدرجة الثانية البرازيلية")),
        new Group("PT", "البرتغال", l("por.1", "الدوري البرتغالي")),
        new Group("BE", "بلجيكا", l("bel.1", "الدوري البلجيكي")),
        new Group("BO", "بوليفيا", l("bol.1", "الدوري البوليفي")),
        new Group("PE", "بيرو", l("per.1", "الدوري البيروفي")),
        new Group("TR", "تركيا", l("tur.1", "الدوري التركي")),
        new Group("CL", "تشيلي", l("chi.1", "الدوري التشيلي")),
        new Group("ZA", "جنوب أفريقيا", l("rsa.1", "الدوري الجنوب أفريقي")),
        new Group("DK", "الدنمارك", l("den.1", "الدوري الدنماركي")),
        new Group("RU", "روسيا", l("rus.1", "الدوري الروسي")),
        new Group("SV", "السلفادور", l("slv.1", "الدوري السلفادوري")),
        new Group("SE", "السويد", l("swe.1", "الدوري السويدي")),
        new Group("CN", "الصين", l("chn.1", "الدوري الصيني")),
        new Group("GT", "غواتيمالا", l("gua.1", "الدوري الغواتيمالي")),
        new Group("FR", "فرنسا", l("fra.1", "الدوري الفرنسي"), l("fra.2", "الدرجة الثانية الفرنسية")),
        new Group("VE", "فنزويلا", l("ven.1", "الدوري الفنزويلي")),
        new Group("CR", "كوستاريكا", l("crc.1", "الدوري الكوستاريكي")),
        new Group("CO", "كولومبيا", l("col.1", "الدوري الكولومبي")),
        new Group("MX", "المكسيك", l("mex.1", "الدوري المكسيكي"), l("mex.2", "الدرجة الثانية المكسيكية")),
        new Group("AT", "النمسا", l("aut.1", "الدوري النمساوي")),
        new Group("NO", "النرويج", l("nor.1", "الدوري النرويجي")),
        new Group("IN", "الهند", l("ind.1", "الدوري الهندي الممتاز", "الدوري الهندي"), l("ind.2", "دوري آي الهندي")),
        new Group("HN", "هندوراس", l("hon.1", "الدوري الهندوراسي")),
        new Group("NL", "هولندا", l("ned.1", "الدوري الهولندي"), l("ned.2", "الدرجة الثانية الهولندية")),
        new Group("US", "الولايات المتحدة", l("usa.1", "الدوري الأمريكي (MLS)", "الدوري الأمريكي"), l("usa.usl.1", "دوري USL الأمريكي")),
        new Group("JP", "اليابان", l("jpn.1", "الدوري الياباني")),
        new Group("GR", "اليونان", l("gre.1", "الدوري اليوناني")),
    };

    // TheSportsDB's names of the tsdb leagues, as search_all_leagues.php spells them (after GROUPS: static order)
    private static final String[][] TSDB_NAMES = {
        {"tsdb.5627", "Saudi First Division League"}, {"tsdb.4678", "UAE Pro League"}, {"tsdb.5055", "Jordanian Pro League"},
        {"tsdb.4826", "Bahrain Premier League"}, {"tsdb.4753", "Algerian Ligue 1"}, {"tsdb.5056", "Iraqi Premier League"},
        {"tsdb.4823", "Kuwait Premier League"}, {"tsdb.4520", "Moroccan Championship"}, {"tsdb.4828", "Tunisian Ligue 1"},
        {"tsdb.5256", "Syrian Premier League"}, {"tsdb.5250", "Oman Professional League"}, {"tsdb.4663", "Qatar Stars League"},
        {"tsdb.5243", "Lebanon Premier League"}, {"tsdb.4829", "Egyptian Premier League"},
    };
    static {
        for (String[] x : TSDB_NAMES) { League l = find(x[0]); if (l != null) l.en = x[1]; }
    }

    static League find(String id) {
        if (id == null) return null;
        for (Group g : GROUPS) for (League x : g.leagues) if (x.id.equals(id)) return x;
        return null;
    }

    static Group groupOf(String leagueId) {
        for (Group g : GROUPS) for (League x : g.leagues) if (x.id.equals(leagueId)) return g;
        return null;
    }

    /** the leagues of a country (England and Scotland for GB) */
    static List<League> ofCountry(String cc) {
        List<League> out = new ArrayList<>();
        if (cc == null) return out;
        for (Group g : GROUPS) if (g.cc.equals(cc) || g.cc.startsWith(cc + "-")) for (League x : g.leagues) out.add(x);
        return out;
    }

    /** a followed team's league: "E929@ksa.1" -> ksa.1, "138995@tsdb.4829" -> tsdb.4829, an older id (the Saudi clubs
     *  of older builds) -> ksa.1, the Saudi national team of older builds -> none (null) */
    static String ofTeam(String teamId) {
        if (teamId == null) return null;
        int at = teamId.indexOf('@');
        if (at > 0) {
            String lg = teamId.substring(at + 1);
            return NATIONAL.equals(lg) || lg.startsWith("fifa.") ? null : lg;
        }
        return "136137".equals(teamId) ? null : ROSHN;
    }

    /** "E929@ksa.1", "138995@tsdb.4829", "136013" */
    static boolean validTeamId(String id) {
        return id != null && id.matches("E\\d+@[a-z0-9._]+|\\d+(@tsdb\\.\\d+)?");
    }
}
