package com.raad.sauditv.screensaver;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * «إذاعة القرآن الكريم»'s list and the station that plays (RadioStations has the stations themselves, RadioService
 * plays them). mp3quran.net's list of radios is read at most every 3 days (files/radios.json keeps the last one read);
 * without it, its last copy, else RadioStations.FALLBACK. Network calls: off the main thread only.
 */
final class Radio {
    private Radio() {}

    /** mp3quran.net/api/v3/radios sends here (a 301 HttpURLConnection follows, same scheme) */
    static final String API = "https://www.mp3quran.net/api/v3/radios?language=ar";
    static final long FRESH_MS = 3 * 24 * 3600 * 1000L;

    private static File cache(Context c) { return new File(c.getFilesDir(), "radios.json"); }

    /** the picker's stations; refresh: read the list again when the kept one is over 3 days old (or missing) */
    static List<RadioStations.Station> stations(Context c, boolean refresh) {
        File f = cache(c);
        String json = read(f);
        if (refresh && (json == null || System.currentTimeMillis() - f.lastModified() > FRESH_MS)) {
            String got = get(API, 10000, 15000);
            if (got != null && !parse(got).isEmpty()) {
                json = got;
                write(f, got);
            }
        }
        return RadioStations.merge(json == null ? null : parse(json));
    }

    /** the kept list only (no network): the receiver's checks of an id */
    static List<RadioStations.Station> kept(Context c) { return stations(c, false); }

    /** {"radios":[{"id":63,"name":"ماهر المعيقلي","url":"https://backup.qurango.net/radio/maher"}, …]} -> {id, name, url} */
    static List<String[]> parse(String json) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(json).optJSONArray("radios");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject r = a.optJSONObject(i);
                if (r == null) continue;
                out.add(new String[] {String.valueOf(r.optLong("id", -1)), r.optString("name", ""), r.optString("url", "")});
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** the station picked in the settings, with its mirrors: a pinned one as RadioStations has it, else as picked
     *  (its address kept with the pick, so it plays without the list) */
    static RadioStations.Station current(Context c) {
        Prefs p = new Prefs(c);
        String id = p.radioStation();
        for (RadioStations.Station s : RadioStations.PINNED) if (s.id.equals(id)) return s;
        String url = p.radioUrl();
        if (RadioStations.CUSTOM.equals(id)) return new RadioStations.Station(id, p.radioName(), url);
        return new RadioStations.Station(id, p.radioName(), RadioStations.mirrors(url));
    }

    /** RADIO_TEST (optimize/saver.sh radio-test): each address of a station, read for up to 3 s: "HTTP 200 audio/mpeg,
     *  48 KB in 3.0 s" (a stream sends on and on), or why not. Off the main thread. */
    static String probe(String url) {
        HttpURLConnection con = null;
        long t0 = System.currentTimeMillis();
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(8000);
            con.setRequestProperty("User-Agent", userAgent());
            int code = con.getResponseCode();
            String type = con.getContentType();
            // radiojar answers 302 to an http:// node: HttpURLConnection does not follow a change of scheme
            if (code / 100 == 3 && con.getHeaderField("Location") != null) {
                String to = con.getHeaderField("Location");
                con.disconnect();
                return "-> " + to.replaceAll("\\?.*", "?…") + ": " + probe(to);
            }
            if (code != 200) return "HTTP " + code;
            long n = 0;
            byte[] buf = new byte[8192];
            try (InputStream in = con.getInputStream()) {
                int k;
                while (System.currentTimeMillis() - t0 < 3000 && (k = in.read(buf)) > 0) n += k;
            }
            return "HTTP 200 " + type + ", " + (n / 1024) + " KB in " + String.format(java.util.Locale.US, "%.1f",
                (System.currentTimeMillis() - t0) / 1000f) + " s";
        } catch (Exception e) {
            return "failed: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static String userAgent() { return "SaudiTV/" + android.os.Build.VERSION.RELEASE + " (Android TV)"; }

    private static String get(String url, int connect, int read) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(connect);
            con.setReadTimeout(read);
            con.setRequestProperty("User-Agent", userAgent());
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) { return slurp(in); }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static String read(File f) {
        if (!f.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) { return slurp(in); }
        catch (Exception e) { return null; }
    }

    private static void write(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes("UTF-8"));
        } catch (Exception e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static String slurp(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
