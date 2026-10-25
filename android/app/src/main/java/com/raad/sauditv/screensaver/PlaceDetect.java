package com.raad.sauditv.screensaver;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * A new install's place, guessed once in the background (no city was ever saved: Prefs.migrate sets PENDING). Started by
 * the first Prefs made while PENDING is set: every way into this app makes one (the settings, the screensaver, the
 * launcher's settings read through ConfigProvider, ADB), so the first of them starts it; at most once per process.
 *  1. the TV's time zone's country (android.icu, Android 7+)
 *  2. where the IP is, from keyless lookups (ipwho.is, ipapi.co, freeipapi.com; 5 s each, the first that answers)
 *  3. PlaceGuess picks the bundled city (a VPN's other country: the time zone's city instead)
 *  4. set as picking it in the settings sets it (PlaceApply: the country's news, league, clubs, prayer method,
 *     units), then the home screen is told (CONFIG_CHANGED)
 * Done once whatever happens (PENDING removed; nothing found: Riyadh stays), and never over a city the user set
 * (Prefs.setCity removes PENDING). AUTO marks the guessed city («(تلقائي)» in the settings) until one is picked.
 */
final class PlaceDetect {
    private PlaceDetect() {}

    static final String PENDING = "placeDetect", AUTO = "placeAuto";
    private static final String TAG = "SaudiTV-Place";
    static final String[] LOOKUPS = {"https://ipwho.is/", "https://ipapi.co/json/", "https://freeipapi.com/api/json"};
    private static final int TIMEOUT_MS = 5000;
    private static boolean started;
    private static final Object LOCK = new Object();

    static synchronized void start(Context c) {
        if (started) return;
        started = true;
        final Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        new Thread(() -> {
            try { run(app); } catch (Throwable e) { Log.w(TAG, "detect: " + e); }
        }, "place").start();
    }

    private static void run(final Context app) {
        String tzId = java.util.TimeZone.getDefault().getID();
        String tzCc = tzCountry(tzId);
        PlaceGuess.Ip ip = null;
        for (String url : LOOKUPS) {
            ip = lookup(url);
            if (ip != null) break;
        }
        PlaceGuess.Pick pick = PlaceGuess.choose(tzId, tzCc, ip, cc -> cities(app, cc));
        Log.i(TAG, "tz " + tzId + " (" + tzCc + "), ip " + (ip == null ? "none" : ip.cc + " " + ip.city)
            + " -> " + (pick == null ? "none (the default stays)" : pick.cc + " " + pick.city.alt));
        synchronized (LOCK) {
            Prefs p = new Prefs(app);
            if (!p.raw().getBoolean(PENDING, false)) return;   // a city was set meanwhile: it stays
            if (pick == null) { p.raw().edit().remove(PENDING).apply(); return; }
            PlaceApply.save(app, p, pick.city.name, pick.city.lat, pick.city.lon, pick.cc, pick.city.tz, note -> changed(app));
            p.raw().edit().remove(PENDING).putBoolean(AUTO, true).apply();
        }
        changed(app);
    }

    /** the home screen shows the new settings (as after a change in the settings) */
    private static void changed(Context app) {
        app.sendBroadcast(new Intent(SaverReceiver.CHANGED).setPackage("com.raad.sauditv.home"));
    }

    /** the country of an IANA time zone (America/Los_Angeles: US); null when it has none or before Android 7 */
    static String tzCountry(String tzId) {
        if (tzId == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null;
        try { return PlaceGuess.country(android.icu.util.TimeZone.getRegion(tzId)); }
        catch (RuntimeException e) { return null; }
    }

    private static List<PlaceGuess.City> cities(Context app, String cc) {
        List<PlaceGuess.City> out = new ArrayList<>();
        for (Geo.City c : Geo.cities(app, cc)) out.add(new PlaceGuess.City(c.name, c.alt, c.lat, c.lon, c.tz));
        return out;
    }

    /** one lookup's answer (ipwho.is, ipapi.co or freeipapi.com's fields); null when it failed */
    static PlaceGuess.Ip lookup(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setRequestProperty("Accept", "application/json");
            if (con.getResponseCode() != 200) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = con.getInputStream()) {
                byte[] buf = new byte[4096];
                for (int n; (n = in.read(buf)) > 0; ) { b.write(buf, 0, n); if (b.size() > 65536) break; }
            }
            JSONObject j = new JSONObject(b.toString("UTF-8"));
            if (j.has("success") && !j.optBoolean("success", true)) return null;   // ipwho.is
            if (j.optBoolean("error", false)) return null;                         // ipapi.co (rate limit)
            String cc = PlaceGuess.country(first(j, "country_code", "countryCode"));
            if (cc == null) return null;
            return new PlaceGuess.Ip(cc, first(j, "city", "cityName"), number(j, "latitude"), number(j, "longitude"));
        } catch (Exception e) {
            Log.i(TAG, url + ": " + e);
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String first(JSONObject j, String... keys) {
        for (String k : keys) { String v = j.optString(k, ""); if (!v.isEmpty() && !"null".equals(v)) return v; }
        return "";
    }

    private static double number(JSONObject j, String key) {
        Object v = j.opt(key);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) try { return Double.parseDouble((String) v); } catch (RuntimeException ignored) { }
        return Double.NaN;
    }
}
