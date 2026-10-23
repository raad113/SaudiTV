package com.raad.sauditv.home;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * VPN state for the badge beside the clock.
 *   0 = no VPN, 1 = VPN up and traffic gets through, 2 = a VPN is reported but nothing gets through
 * (a VPN app that shows "disconnected" can leave its tunnel up and swallow all traffic).
 * The page asks every 3 s; the reachability probe runs at most every 15 s, off the main thread.
 */
public final class VpnStatus {
    static final String PROBE = "https://www.google.com/generate_204";

    private static volatile boolean lastOk = true, probing, fetchingCountry, hadVpn;
    private static volatile long probedAt = -60_000, countryAt = -600_000;
    private static volatile String country = "";   // ISO code of the VPN exit, e.g. GB; "" = unknown

    private final Context app;

    VpnStatus(Context c) { app = c.getApplicationContext(); }

    /** Kept for pages that only know on/off. */
    @JavascriptInterface
    public boolean vpn() { return state(app) == 1; }

    @JavascriptInterface
    public int vpnState() { return state(app); }

    public static int state(Context c) {
        if (!hasVpn(c)) { hadVpn = false; country = ""; return 0; }
        if (!hadVpn) { hadVpn = true; countryAt = -600_000; probedAt = -60_000; }   // fresh connection: ask again now
        long now = SystemClock.elapsedRealtime();
        if (!probing && now - probedAt > 15_000) {
            probing = true;
            new Thread(() -> {
                boolean ok = reachable();
                lastOk = ok;
                probedAt = SystemClock.elapsedRealtime();
                probing = false;
            }, "vpn-probe").start();
        }
        if (lastOk && !fetchingCountry && now - countryAt > 60_000) {
            fetchingCountry = true;
            new Thread(() -> {
                String cc = lookupCountry();
                if (cc != null) country = cc;
                countryAt = SystemClock.elapsedRealtime();
                fetchingCountry = false;
            }, "vpn-country").start();
        }
        return lastOk ? 1 : 2;
    }

    /** Country of the VPN exit as a flag emoji (empty when unknown or no working VPN). */
    public static String flag() {
        String cc = country;
        if (cc == null || !cc.matches("[A-Z]{2}")) return "";
        return new String(Character.toChars(0x1F1E6 + cc.charAt(0) - 'A'))
             + new String(Character.toChars(0x1F1E6 + cc.charAt(1) - 'A'));
    }

    public static String country() { return country; }

    @JavascriptInterface
    public String vpnCountry() { return country; }

    @SuppressWarnings("deprecation")
    static boolean hasVpn(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /**
     * Where this connection comes out (with a VPN: the server's country). Free services without a key, tried in
     * turn, because some refuse VPN / data-centre addresses: each answer just has to contain a 2-letter code.
     */
    private static final String[] COUNTRY_SERVICES = {
        "https://ipinfo.io/country", "https://api.country.is/", "https://ipapi.co/country/",
    };
    private static final java.util.regex.Pattern CODE =
        java.util.regex.Pattern.compile("(?:\"country\"\\s*:\\s*\")?\\b([A-Z]{2})\\b");

    private static String lookupCountry() {
        for (String url : COUNTRY_SERVICES) {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setUseCaches(false);
                con.setRequestProperty("User-Agent", "SaudiTV");
                int code = con.getResponseCode();
                if (code != 200) { android.util.Log.w("SaudiTV-VPN", url + " -> HTTP " + code); continue; }
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(con.getInputStream(), "UTF-8"))) {
                    String l; while ((l = in.readLine()) != null) sb.append(l);
                }
                java.util.regex.Matcher m = CODE.matcher(sb.toString().trim());
                if (m.find()) { android.util.Log.i("SaudiTV-VPN", url + " -> " + m.group(1)); return m.group(1); }
                android.util.Log.w("SaudiTV-VPN", url + " -> no code in: " + sb);
            } catch (Exception e) {
                android.util.Log.w("SaudiTV-VPN", url + " -> " + e);
            } finally {
                if (con != null) con.disconnect();
            }
        }
        return null;
    }

    private static boolean reachable() {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(PROBE).openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            con.setUseCaches(false);
            con.setInstanceFollowRedirects(false);
            return con.getResponseCode() == 204;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
