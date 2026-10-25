package com.raad.sauditv.screensaver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picking a city of another country in the settings (the country's list of cities, or «اكتب اسم مدينتك») brings that
 * country's news and football with it, once, at the change (SettingsActivity.savePlace); never on a reload, a re-pick
 * of a city of the same country, or from ADB (optimize/saver.sh sets each value itself). Everything stays the user's
 * to change afterwards.
 *  - the news («دولة الأخبار»): the new country with its default sources, when it has a news list of its own (Saudi
 *    Arabia's, or an Arab country's Prefs.COUNTRY_NEWS); else the news country it had stays, and its row says why
 *  - the table («الدوري في الترتيب»): the country's top league (Leagues.GROUPS); none: the table and the clubs stay
 *  - the followed clubs: the top 3 of that league's table now (ESPN, TheSportsDB), else KNOWN (a table of
 *    October 2026), else they stay
 * Settings of older builds have no country (""): that is Saudi Arabia, so a Saudi household re-picking Riyadh keeps
 * its clubs. Plain Java (no Android): tests/java_test.py runs it.
 */
final class CountryChange {
    private CountryChange() {}

    static final int CLUBS = 3;

    /** a country code as compared: upper case, "" (not set, older builds) = Saudi Arabia */
    static String norm(String cc) {
        return cc == null || cc.trim().isEmpty() ? "SA" : cc.trim().toUpperCase(Locale.ROOT);
    }

    /** only an actual change of country counts */
    static boolean isChange(String before, String after) {
        return after != null && !norm(before).equals(norm(after));
    }

    /** the country's top league: its own group's first (the United Kingdom: England's), null when Leagues has none */
    static Leagues.League topLeague(String cc) {
        String k = norm(cc);
        for (Leagues.Group g : Leagues.GROUPS) if (g.cc.equals(k)) return g.leagues[0];
        for (Leagues.Group g : Leagues.GROUPS) if (g.cc.equals(k + "-ENG")) return g.leagues[0];
        for (Leagues.Group g : Leagues.GROUPS) if (g.cc.startsWith(k + "-")) return g.leagues[0];
        return null;
    }

    /** what a change of country sets; null fields stay as they are */
    static final class Plan {
        String news;          // the news country to set (with its default sources)
        String newsMissing;   // the new country, when it has no news list (the news country stays: news is the old one)
        Leagues.League league;   // the table's league, and the league whose top clubs to follow; null: both stay
    }

    /** before/after: the city's country; news: the news country shown before the change; hasNewsList: whether the new
     *  country has a news list of its own. null when the country did not change (nothing to do). */
    static Plan plan(String before, String after, String news, boolean hasNewsList) {
        if (!isChange(before, after)) return null;
        Plan p = new Plan();
        String cc = norm(after);
        if (hasNewsList) p.news = cc;
        else { p.news = norm(news); p.newsMissing = cc; }
        p.league = topLeague(cc);
        return p;
    }

    /** the first CLUBS rows of a table in rank order ({id, English name}), as followed teams' ids and names */
    static List<String[]> top(List<String[]> table) {
        List<String[]> out = new ArrayList<>();
        if (table == null) return out;
        for (String[] r : table) {
            if (r == null || r.length < 2 || !Leagues.validTeamId(r[0]) || r[1] == null || r[1].trim().isEmpty()) continue;
            boolean seen = false;
            for (String[] o : out) seen |= o[0].equals(r[0]);
            if (seen) continue;
            out.add(new String[] {r[0], r[1].trim()});
            if (out.size() == CLUBS) break;
        }
        return out;
    }

    /** KNOWN's clubs of a league ({id, English name}), empty when it has none */
    static List<String[]> known(String leagueId) {
        List<String[]> out = new ArrayList<>();
        for (String[] k : KNOWN) {
            if (!k[0].equals(leagueId)) continue;
            for (int i = 1; i < k.length; i++) { String[] p = k[i].split(":", 2); out.add(new String[] {p[0], p[1]}); }
        }
        return out;
    }

    /** the clubs to follow: the live table's top 3 when it has 3, else KNOWN's, else none (the clubs stay) */
    static List<String[]> clubs(String leagueId, List<String[]> table) {
        List<String[]> live = top(table);
        if (live.size() == CLUBS) return live;
        List<String[]> k = top(known(leagueId));
        return k.size() == CLUBS ? k : new ArrayList<String[]>();
    }

    // The top 3 of each country's top league (CountryChange.topLeague) as their tables stood in October 2026: ESPN's
    // standings and TheSportsDB's lookuptable.php, the ids as the team picker stores them (Leagues.validTeamId). Only
    // when the table can't be read at the change.
    static final String[][] KNOWN = {
        {"ksa.1", "E929@ksa.1:Al Hilal", "E817@ksa.1:Al Nassr", "E22022@ksa.1:Al Qadsiah"},
        {"tsdb.4678", "137830@tsdb.4678:Al Jazira", "137828@tsdb.4678:Shabab Al-Ahli Dubai", "137835@tsdb.4678:Al Wasl"},
        {"tsdb.5055", "139529@tsdb.5055:Al-Faisaly Amman", "142129@tsdb.5055:Al-Baqa'a", "141297@tsdb.5055:Al-Wehdat"},
        {"tsdb.4826", "139524@tsdb.4826:Al-Riffa", "143645@tsdb.4826:Khalidiya", "156489@tsdb.4826:Al-Ittifaq"},
        {"tsdb.4753", "139279@tsdb.4753:MC Oran", "139278@tsdb.4753:CR Belouizdad", "139092@tsdb.4753:JS Kabylie"},
        {"tsdb.5056", "149361@tsdb.5056:Al-Karma", "143884@tsdb.5056:Newroz", "141846@tsdb.5056:Al-Naft"},
        {"tsdb.4823", "139510@tsdb.4823:Kuwait SC", "139875@tsdb.4823:Kazma", "139877@tsdb.4823:Al-Nasr SC"},
        {"tsdb.4520", "136417@tsdb.4520:CODM de Meknès", "136407@tsdb.4520:Kawkab Marrakech", "136402@tsdb.4520:Wydad Casablanca"},
        {"tsdb.4828", "139870@tsdb.4828:US Ben Guerdane", "137650@tsdb.4828:Espérance de Tunis", "156381@tsdb.4828:Sakiet Eddaïer"},
        {"tsdb.5256", "145423@tsdb.5256:Al-Karamah", "145431@tsdb.5256:Al-Shorta Damascus", "145429@tsdb.5256:Hutteen"},
        {"tsdb.5250", "145457@tsdb.5250:Al-Nahda", "149206@tsdb.5250:Al-Shabab Seeb", "141306@tsdb.5250:Al-Nasr Salalah"},
        {"tsdb.4663", "138050@tsdb.4663:Al-Rayyan", "137648@tsdb.4663:Al-Sadd", "137679@tsdb.4663:Al-Duhail"},
        {"tsdb.5243", "152958@tsdb.5243:Jwaya", "145409@tsdb.5243:Tadamon Sour", "145405@tsdb.5243:Safa"},
        {"tsdb.4829", "139838@tsdb.4829:Pyramids", "138995@tsdb.4829:Al Ahly", "138997@tsdb.4829:Zamalek"},
        {"ger.1", "E124@ger.1:Borussia Dortmund", "E132@ger.1:Bayern Munich", "E126@ger.1:SC Freiburg"},
        {"arg.1", "E2975@arg.1:Instituto (Córdoba)", "E8950@arg.1:Defensa y Justicia", "E5@arg.1:Boca Juniors"},
        {"esp.1", "E83@esp.1:Barcelona", "E1068@esp.1:Atlético Madrid", "E86@esp.1:Real Madrid"},
        {"aus.1", "E8352@aus.1:Wellington Phoenix", "E13696@aus.1:Western Sydney Wanderers FC", "E19340@aus.1:Macarthur FC"},
        {"sco.1", "E256@sco.1:Celtic", "E257@sco.1:Rangers", "E262@sco.1:Heart of Midlothian"},
        {"eng.1", "E382@eng.1:Manchester City", "E359@eng.1:Arsenal", "E331@eng.1:Brighton & Hove Albion"},
        {"uru.1", "E5492@uru.1:Liverpool", "E2684@uru.1:Nacional", "E19002@uru.1:Montevideo City Torque"},
        {"ita.1", "E112@ita.1:Lazio", "E110@ita.1:Internazionale", "E104@ita.1:AS Roma"},
        {"par.1", "E2675@par.1:Club Olimpia", "E2670@par.1:Libertad", "E7466@par.1:Sportivo Trinidense"},
        {"bra.1", "E2029@bra.1:Palmeiras", "E819@bra.1:Flamengo", "E2022@bra.1:Cruzeiro"},
        {"por.1", "E437@por.1:FC Porto", "E1929@por.1:Benfica", "E2250@por.1:Sporting CP"},
        {"bel.1", "E3611@bel.1:KAA Gent", "E570@bel.1:Club Brugge", "E3616@bel.1:Royal Charleroi SC"},
        {"bol.1", "E2681@bol.1:Bolívar", "E19425@bol.1:Always Ready", "E2687@bol.1:The Strongest"},
        {"per.1", "E10318@per.1:Sport Huancayo", "E2685@per.1:Universitario", "E2673@per.1:Sporting Cristal"},
        {"tur.1", "E132335@tur.1:Amed SFK", "E432@tur.1:Galatasaray", "E436@tur.1:Fenerbahce"},
        {"chi.1", "E2688@chi.1:Colo Colo", "E4139@chi.1:Universidad de Chile", "E885@chi.1:Universidad Católica"},
        {"rsa.1", "E7084@rsa.1:Mamelodi Sundowns", "E7085@rsa.1:Orlando Pirates", "E7079@rsa.1:AmaZulu"},
        {"den.1", "E909@den.1:F.C. København", "E572@den.1:FC Midtjylland", "E3153@den.1:Viborg FF"},
        {"rus.1", "E596@rus.1:Dinamo Moscow", "E2533@rus.1:Zenit St Petersburg", "E11336@rus.1:Krasnodar"},
        {"slv.1", "E7232@slv.1:Luis Ángel Firpo", "E7229@slv.1:CD FAS", "E7224@slv.1:Isidro Metapán"},
        {"swe.1", "E8547@swe.1:IK Sirius", "E2339@swe.1:Djurgården", "E2495@swe.1:Hammarby IF"},
        {"chn.1", "E21355@chn.1:Chengdu Rongcheng", "E22537@chn.1:Dalian Yingbo", "E2052@chn.1:Beijing Guoan"},
        {"gua.1", "E7253@gua.1:Antigua GFC", "E7258@gua.1:Municipal", "E20036@gua.1:Deportivo Mixco"},
        {"fra.1", "E167@fra.1:Lyon", "E160@fra.1:Paris Saint-Germain", "E166@fra.1:Lille"},
        {"ven.1", "E6037@ven.1:Carabobo", "E18995@ven.1:Academia Puerto Cabello", "E17090@ven.1:Deportivo La Guaira"},
        {"crc.1", "E858@crc.1:Saprissa", "E7239@crc.1:Cartaginés", "E20705@crc.1:Sporting San José"},
        {"col.1", "E8109@col.1:América de Cali", "E5264@col.1:Atlético Nacional", "E2672@col.1:Deportivo Cali"},
        {"mex.1", "E227@mex.1:América", "E219@mex.1:Guadalajara", "E223@mex.1:Toluca"},
        {"aut.1", "E519@aut.1:Rapid Vienna", "E2790@aut.1:RB Salzburg", "E4411@aut.1:LASK Linz"},
        {"nor.1", "E510@nor.1:Viking FK", "E2980@nor.1:Bodo/Glimt", "E2715@nor.1:Molde"},
        {"ind.1", "E18004@ind.1:NorthEast United FC", "E8897@ind.1:East Bengal FC", "E18851@ind.1:Bengaluru FC"},
        {"hon.1", "E7245@hon.1:Real España", "E884@hon.1:Club Deportivo Olimpia", "E7249@hon.1:Club Deportivo Marathón"},
        {"ned.1", "E142@ned.1:Feyenoord Rotterdam", "E140@ned.1:AZ Alkmaar", "E148@ned.1:PSV Eindhoven"},
        {"usa.1", "E18986@usa.1:Nashville SC", "E189@usa.1:New England Revolution", "E20232@usa.1:Inter Miami CF"},
        {"jpn.1", "E22167@jpn.1:Machida Zelvia", "E7114@jpn.1:Sanfrecce Hiroshima", "E7476@jpn.1:Kashiwa Reysol"},
        {"gre.1", "E443@gre.1:Panathinaikos", "E887@gre.1:AEK Athens", "E435@gre.1:Olympiacos"},
    };
}
