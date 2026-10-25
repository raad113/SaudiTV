package com.raad.sauditv.screensaver;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * A new install's place, guessed once (PlaceDetect): which bundled city to start with, from the TV's time zone and a
 * keyless IP lookup. A VPN may show another country than the TV's: the IP's place counts only when its country is the
 * time zone's (or the time zone has none); else the time zone's own city (America/Los_Angeles: Los Angeles) or, not
 * listed, the country's biggest city in that time zone, else its biggest. Plain Java (no Android): tests/java_test.py
 * runs it.
 */
final class PlaceGuess {
    private PlaceGuess() {}

    /** a bundled city (Geo.City's fields): Arabic name, English name, latitude, longitude, IANA time zone */
    static final class City {
        final String name, alt, lat, lon, tz;
        City(String name, String alt, String lat, String lon, String tz) {
            this.name = name; this.alt = alt == null ? "" : alt; this.lat = lat; this.lon = lon; this.tz = tz == null ? "" : tz;
        }
    }

    /** what an IP lookup said: the country code, the city's name and where it is (NaN when not given) */
    static final class Ip {
        final String cc, city;
        final double lat, lon;
        Ip(String cc, String city, double lat, double lon) {
            this.cc = cc == null ? "" : cc.trim().toUpperCase(Locale.ROOT);
            this.city = city == null ? "" : city;
            this.lat = lat;
            this.lon = lon;
        }
        boolean located() { return !Double.isNaN(lat) && !Double.isNaN(lon) && !(lat == 0 && lon == 0); }
    }

    /** a country's bundled cities, biggest first (empty when none) */
    interface Cities { List<City> of(String cc); }

    /** the guess: the city and its country */
    static final class Pick {
        final String cc;
        final City city;
        Pick(String cc, City city) { this.cc = cc; this.city = city; }
    }

    /** a two-letter country code, upper case; null for none ("001", "", null: a time zone of no country) */
    static String country(String cc) {
        if (cc == null) return null;
        String v = cc.trim().toUpperCase(Locale.ROOT);
        return v.length() == 2 && Character.isLetter(v.charAt(0)) && Character.isLetter(v.charAt(1)) ? v : null;
    }

    /**
     * tzId: the TV's time zone (Asia/Riyadh); tzCountry: its country (null: unknown); ip: the lookup's answer (null:
     * none answered). null when nothing fits (the default city stays).
     */
    static Pick choose(String tzId, String tzCountry, Ip ip, Cities cities) {
        String tz = country(tzCountry);
        String ipCc = ip == null ? null : country(ip.cc);
        if (ipCc != null && (tz == null || tz.equals(ipCc))) {
            City c = fromIp(ip, cities.of(ipCc));
            if (c != null) return new Pick(ipCc, c);
        }
        if (tz != null) {
            City c = fromZone(tzId, cities.of(tz));
            if (c != null) return new Pick(tz, c);
        }
        return null;
    }

    /** the bundled city nearest the IP's place; not located: the one of its name, else the biggest */
    static City fromIp(Ip ip, List<City> list) {
        if (list == null || list.isEmpty()) return null;
        if (ip.located()) return nearest(list, ip.lat, ip.lon);
        City named = named(list, ip.city);
        return named != null ? named : list.get(0);
    }

    /** the time zone's exemplar city (its last part: Los_Angeles = Los Angeles), else the biggest city in that zone,
     *  else the biggest */
    static City fromZone(String tzId, List<City> list) {
        if (list == null || list.isEmpty()) return null;
        City named = named(list, exemplar(tzId));
        if (named != null) return named;
        if (tzId != null) for (City c : list) if (tzId.equals(c.tz)) return c;
        return list.get(0);
    }

    /** America/Argentina/Buenos_Aires -> Buenos Aires; "" for none */
    static String exemplar(String tzId) {
        if (tzId == null) return "";
        int s = tzId.lastIndexOf('/');
        return s < 0 ? "" : tzId.substring(s + 1).replace('_', ' ').trim();
    }

    /** the biggest city of that name (Arabic or English; «New York» is also GeoNames' «New York City»); null for none */
    static City named(List<City> list, String name) {
        String want = fold(name);
        if (want.isEmpty()) return null;
        for (City c : list) {
            String a = fold(c.alt), n = fold(c.name);
            if (want.equals(a) || want.equals(n) || (want + " city").equals(a)) return c;
        }
        return null;
    }

    /** the city nearest lat/lon (an equirectangular distance: enough within one country) */
    static City nearest(List<City> list, double lat, double lon) {
        City best = null;
        double bd = Double.MAX_VALUE, k = Math.cos(Math.toRadians(lat));
        for (City c : list) {
            double y, x;
            try { y = Double.parseDouble(c.lat); x = Double.parseDouble(c.lon); } catch (RuntimeException e) { continue; }
            double dx = (x - lon) * k, dy = y - lat, d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    /** lower case, no accents, one alif: for comparing names */
    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{Mn}+", "");
        return n.toLowerCase(Locale.ROOT).replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('-', ' ').trim();
    }
}
