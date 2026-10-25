package com.raad.sauditv.screensaver;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.TimeZone;

/**
 * The page's online prayer times for the athan. While it is online the page shows Aladhan's answer (loadPrayers), and
 * offline its own formula; the two can differ by a minute (Los Angeles, 28 Oct 2026: Asr 15:41 from Aladhan, 15:40 from the
 * formula). So the athan schedules from the same answer: the page's own request (AladhanTimes.url, no key, the page's
 * 12 s), for the city's today and tomorrow, kept per day + city + method + zone in the athan's state file
 * (AthanScheduler.STATE). A new answer moves the alarm (AthanScheduler.schedule); until there is one, PrayerTimes.
 *
 * The asking is a job (this JobService): it runs once the TV has a network, keeps this app's process up while it waits
 * for the answer, and, while a day is still missing, Android runs it again with a growing back-off (30 s, 1 min, 2 min…),
 * so a failure at install or boot is not left until the next prayer. Each try's outcome: aladhan_last in athan-show.
 */
public class AthanOnline extends JobService {
    private static final int JOB_ID = 2;   // UpdateJob is 1
    /** the page's own limit (loadPrayers: fetchT(url, 12000)) for connecting and for reading */
    static final int TIMEOUT_MS = 12000;
    static final long BACKOFF_MS = 30000;
    private static final String KEY = "aladhan|";

    /** the kept answers for this place (never the network): AthanPrefs.place */
    static AthanPlan.Online cached(Context c, final String lat, final String lon, final int method, final String tz) {
        final SharedPreferences st = AthanScheduler.state(c);
        return ymd -> AladhanTimes.load(st.getString(KEY + AladhanTimes.key(ymd[0], ymd[1], ymd[2], lat, lon, method, tz), null));
    }

    /** whether the page's online answer is kept for that day (saver.sh athan-show) */
    static boolean has(Context c, int[] ymd) {
        Prefs p = new Prefs(c);
        String[] city = p.city();
        return AthanScheduler.state(c).contains(KEY + AladhanTimes.key(ymd[0], ymd[1], ymd[2], city[1], city[2], p.prayerMethod(), p.placeTz()));
    }

    /** the last try's outcome, for saver.sh athan-show */
    static String last(Context c) { return AthanScheduler.state(c).getString("aladhanLast", "(not asked yet)"); }

    /** asks for the days not kept yet: the job, unless every day is kept or it is already waiting or running */
    static void refresh(Context c) {
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        try {
            if (missing(app).length == 0) return;
            JobScheduler js = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;   // a new schedule() would stop it
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(app, AthanOnline.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build());
        } catch (RuntimeException e) { Log.w(AthanScheduler.TAG, "aladhan job: " + e); }
    }

    /** the request of each day (the city's today and tomorrow) whose answer is not kept: {key, url} */
    private static String[][] missing(Context app) {
        AthanPrefs ap = new AthanPrefs(app);
        if (!ap.on()) return new String[0][];
        Prefs p = ap.prefs();
        String[] city = p.city();
        int method = p.prayerMethod();
        String tz = p.placeTz();
        SharedPreferences st = AthanScheduler.state(app);
        int[] today = AthanPlan.cityDate(TimeZone.getTimeZone(tz), System.currentTimeMillis());
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (int k = 0; k <= 1; k++) {
            int[] d = AthanPlan.addDays(today, k);
            String key = AladhanTimes.key(d[0], d[1], d[2], city[1], city[2], method, tz);
            if (!st.contains(KEY + key)) out.add(new String[] {key, AladhanTimes.url(d[0], d[1], d[2], city[1], city[2], method, tz)});
        }
        return out.toArray(new String[0][]);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean again = false, got = false;
            try {
                SharedPreferences st = AthanScheduler.state(app);
                AthanPrefs ap = new AthanPrefs(app);
                prune(st, AthanPlan.addDays(AthanPlan.cityDate(TimeZone.getTimeZone(ap.prefs().placeTz()), System.currentTimeMillis()), -1));
                StringBuilder outcome = new StringBuilder();
                for (String[] m : missing(app)) {
                    String[] why = new String[1];
                    int[] t = AladhanTimes.parse(get(m[1], why));
                    String day = m[0].substring(0, 10);
                    if (t != null) {
                        st.edit().putString(KEY + m[0], AladhanTimes.store(t)).apply();
                        got = true;
                        outcome.append(day).append(" ok; ");
                    } else {
                        again = true;
                        outcome.append(day).append(" failed: ").append(why[0] == null ? "no timings in the answer" : why[0]).append("; ");
                    }
                }
                if (outcome.length() > 0) {
                    String line = AthanScheduler.stamp(app, System.currentTimeMillis()) + " " + outcome.substring(0, outcome.length() - 2)
                        + (again ? " (asked again later)" : "");
                    st.edit().putString("aladhanLast", line).apply();
                    Log.i(AthanScheduler.TAG, "aladhan: " + line);
                }
            } catch (RuntimeException e) {
                again = true;
                Log.w(AthanScheduler.TAG, "aladhan: " + e);
            }
            if (got) AthanScheduler.schedule(app);   // the alarm at the page's minute
            jobFinished(params, again);
        }, "athan-aladhan").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }   // stopped (the network went): again later

    /** drops the kept answers of the days before `from` */
    private static void prune(SharedPreferences st, int[] from) {
        String first = from[0] + "-" + (from[1] < 10 ? "0" : "") + from[1] + "-" + (from[2] < 10 ? "0" : "") + from[2];
        SharedPreferences.Editor e = null;
        for (Map.Entry<String, ?> x : st.getAll().entrySet()) {
            String k = x.getKey();
            if (!k.startsWith(KEY)) continue;
            String rest = k.substring(KEY.length());
            if (rest.length() < 10 || rest.substring(0, 10).compareTo(first) >= 0) continue;
            if (e == null) e = st.edit();
            e.remove(k);
        }
        if (e != null) e.apply();
    }

    /** a GET of at most 256 KB within TIMEOUT_MS each to connect and to read; null when it failed (why[0]: how) */
    private static String get(String url, String[] why) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setRequestProperty("Accept", "application/json");
            int code = con.getResponseCode();
            if (code != 200) { why[0] = "HTTP " + code; return null; }
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    b.write(buf, 0, n);
                    if (b.size() > 256 * 1024) { why[0] = "answer too big"; return null; }
                }
                return b.toString("UTF-8");
            }
        } catch (Exception e) {
            why[0] = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
