package com.raad.sauditv.screensaver;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Setting the city, as picking it in the settings does (SettingsActivity.savePlace) and as a new install's guessed
 * place does (PlaceDetect): the city with its country and time zone; at a change of country (CountryChange) its news
 * and its table's league now, and that league's top 3 clubs once its table is read (off the calling thread). The
 * prayer method, the temperature unit and the automatic news follow the country by themselves (Prefs).
 */
final class PlaceApply {
    private PlaceApply() {}

    /** the clubs of a change of country are read later: called on that thread with the note for the user (news ·
     *  league · clubs), not when another change of country came after */
    interface Clubs { void done(String note); }

    // the last change of country (its clubs arrive later; an earlier one's are dropped)
    private static final AtomicInteger SEQ = new AtomicInteger();

    /**
     * The city, then what a change of country brings. Returns the note to show now (a country without a league:
     * its news and «بقيت الفرق كما هي»), else null; clubs.done follows when a league's clubs are being read.
     */
    static String save(Context c, Prefs prefs, String name, String lat, String lon, String cc, String tz, Clubs clubs) {
        String before = prefs.country(), news = prefs.newsCountry();
        prefs.setCity(name, lat, lon, cc, tz);
        // another country than before (never a re-pick in the same one): its news and its football (CountryChange)
        CountryChange.Plan plan = CountryChange.plan(before, cc, news, Prefs.hasNewsList(cc));
        return plan == null ? null : applyCountry(c.getApplicationContext() != null ? c.getApplicationContext() : c, prefs, plan, clubs);
    }

    /** a change of country: the news now, the table's league now, its top 3 clubs once the table is read */
    private static String applyCountry(final Context app, final Prefs prefs, final CountryChange.Plan plan, final Clubs clubs) {
        if (plan.newsMissing == null) prefs.setNewsOf(plan.news); else prefs.setNewsKept(plan.news, plan.newsMissing);
        final String newsPart = plan.newsMissing == null ? "أخبار " + prefs.newsCountryName()
            : "بقيت أخبار " + prefs.newsCountryName();
        final Leagues.League l = plan.league;
        if (l == null) return newsPart + " · لا يوجد دوري لهذه الدولة، بقيت الفرق كما هي";
        prefs.setLeague(l.id);
        final int seq = SEQ.incrementAndGet();
        new Thread(() -> {
            List<String[]> table = null;
            try { table = TopClubs.table(l); }
            catch (Exception e) { android.util.Log.w("SaudiTV-Settings", "table " + l.id + ": " + e); }
            List<String[]> got = CountryChange.clubs(l.id, table);
            final List<String> names = new ArrayList<>();
            if (seq == SEQ.get() && !got.isEmpty()) {
                List<String> v = new ArrayList<>();
                for (String[] t : got) {
                    String ar = clubName(app, t[1], false).replace("|", " ").replace(";", " ");
                    v.add(t[0] + "|" + ar);
                    names.add(ar);
                }
                prefs.setList("teams", v);
            }
            if (seq != SEQ.get() || clubs == null) return;
            clubs.done(newsPart + " · " + l.ar + " · " + (names.isEmpty() ? "بقيت الفرق كما هي" : Prefs.join(names, "، ")));
        }, "country").start();
        return null;
    }

    /** a club's (or a national team's) name: the bundled Arabic one (teams-ar.json; a country's for a national team),
     *  else ESPN's English one */
    static String clubName(Context c, String en, boolean national) {
        String k = Arabic.known(c, en);
        if (k != null) return k;
        if (national) { String n = Geo.arabicOf(c, en); if (n != null) return n; }
        return en;
    }
}
