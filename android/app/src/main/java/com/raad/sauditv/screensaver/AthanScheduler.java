package com.raad.sauditv.screensaver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Locale;

/**
 * Keeps one alarm: the next athan, short alert or reminder of AthanPlan.next, at the times the page shows: Aladhan's
 * answer to the page's own request, kept per day and place (AthanOnline, asked again after each scheduling while a day
 * is missing), else the page's offline formula (PrayerTimes). Its receiver (AthanReceiver) does the event, then asks for the one after it, so the alarms go on day after day.
 * Scheduled again: after a boot or this app's update and when the clock or the time zone changes (BootReceiver), when
 * the exact-alarm permission changes, when a setting that moves it changes (the city, its time zone, the method, the
 * country, the athan's own: watch), and on any start of this app's process without one pending (watch).
 *
 * Exact alarms: Android 10 and 11 need no permission; 12 grants SCHEDULE_EXACT_ALARM at install (the user can take it
 * back); 13 and later grant USE_EXACT_ALARM at install, never revocable (the manifest asks for both, each where it
 * applies). Without it (a 12 whose user took it back): an inexact alarm, which may come a few minutes late; AthanPlan
 * then skips an athan more than 3 minutes late, and the settings' «الأذان» row says how to allow it.
 *
 * RTC, not RTC_WAKEUP: the alarm never wakes a TV that is asleep; held back until it wakes, it comes late and is skipped.
 */
final class AthanScheduler {
    private AthanScheduler() {}

    static final String TAG = "SaudiTV-Athan";
    static final String ACTION_ALARM = "com.raad.sauditv.screensaver.ATHAN_ALARM";
    /** the alarm's state for saver.sh athan-show and the settings: its own file, never watched */
    static final String STATE = "athan";

    /** Ramadan by the TV's own Umm al-Qura calendar (the page's hijriMonthNow: Intl's islamic-umalqura, the TV's zone) */
    static final AthanPlan.Hijri HIJRI = at -> {
        if (Build.VERSION.SDK_INT < 24) return false;
        try {
            android.icu.util.IslamicCalendar c = new android.icu.util.IslamicCalendar(
                android.icu.util.TimeZone.getTimeZone(Prefs.timeZone()), android.icu.util.ULocale.ROOT);
            c.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            c.setTimeInMillis(at);
            return c.get(android.icu.util.Calendar.MONTH) == android.icu.util.IslamicCalendar.RAMADAN;
        } catch (RuntimeException e) { return false; }
    };

    static SharedPreferences state(Context c) { return c.getSharedPreferences(STATE, Context.MODE_PRIVATE); }

    /** the next alarm from now */
    static void schedule(Context c) { scheduleAfter(c, System.currentTimeMillis()); }

    /** the next alarm after `after` (an event just done: the one after it) */
    static synchronized void scheduleAfter(Context c, long after) {
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        AthanPrefs p = new AthanPrefs(app);
        AthanPlan.Event e;
        try { e = AthanPlan.next(p.place(), p.settings(), after, HIJRI); }
        catch (RuntimeException x) { Log.w(TAG, "plan: " + x); e = null; }
        PendingIntent pi = pending(app, e, PendingIntent.FLAG_UPDATE_CURRENT);
        if (e == null) {
            am.cancel(pi);
            pi.cancel();
            state(app).edit().putString("next", "none (" + (p.on() ? "every prayer off" : "athan off") + ")").putLong("nextAt", 0).apply();
            Log.i(TAG, "no alarm: " + (p.on() ? "every prayer off" : "athan off"));
            return;
        }
        boolean exact = exactAllowed(app);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC, e.at, pi);
                else am.setAndAllowWhileIdle(AlarmManager.RTC, e.at, pi);
            } else am.setExact(AlarmManager.RTC, e.at, pi);
        } catch (SecurityException x) {   // the exact-alarm permission taken back in between
            exact = false;
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC, e.at, pi);
            else am.set(AlarmManager.RTC, e.at, pi);
        }
        String what = AthanPlan.PRAYERS[e.prayer] + " " + e.kind + " at " + stamp(app, e.at) + (exact ? " (exact)" : " (inexact: no exact-alarm permission)");
        state(app).edit().putString("next", what).putLong("nextAt", e.at).apply();
        Log.i(TAG, "next: " + what);
        AthanOnline.refresh(app);   // the page's online times for today and tomorrow, if not kept yet: a new one moves this
    }

    /** the alarm's PendingIntent, the event in its extras (AthanReceiver reads it back: event) */
    private static PendingIntent pending(Context c, AthanPlan.Event e, int flags) {
        Intent i = new Intent(c, AthanReceiver.class).setAction(ACTION_ALARM);
        if (e != null) put(i, e);
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, 0, i, flags);
    }

    static Intent put(Intent i, AthanPlan.Event e) {
        return i.putExtra("prayer", e.prayer).putExtra("kind", e.kind).putExtra("at", e.at).putExtra("athanAt", e.athanAt);
    }

    /** the event an intent carries (put), or null */
    static AthanPlan.Event event(Intent i) {
        if (i == null || !i.hasExtra("prayer") || !i.hasExtra("kind")) return null;
        int prayer = i.getIntExtra("prayer", -1);
        if (prayer < 0 || prayer >= AthanPlan.PRAYERS.length) return null;
        long at = i.getLongExtra("at", 0);
        return new AthanPlan.Event(prayer, i.getStringExtra("kind"), at, i.getLongExtra("athanAt", at));
    }

    /** exact alarms allowed: always before Android 12; then SCHEDULE_EXACT_ALARM (12) or USE_EXACT_ALARM (13+) */
    static boolean exactAllowed(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    /** "2026-10-25 11:37" in the city's time zone */
    static String stamp(Context c, long at) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone(new Prefs(c).placeTz()));
        return f.format(new java.util.Date(at));
    }

    /** a line about the last event done, for saver.sh athan-show */
    static void done(Context c, AthanPlan.Event e, String outcome) {
        String line = stamp(c, System.currentTimeMillis()) + " " + AthanPlan.PRAYERS[e.prayer] + " " + e.kind + ": " + outcome;
        state(c).edit().putString("last", line).apply();
        Log.i(TAG, line);
    }

    // ---------------------------------------------------------------- following the settings

    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static Handler main;
    private static final Runnable[] pendingRun = new Runnable[1];

    /** once per process (the Prefs constructor): a change of a key that moves the alarm reschedules it (once for a save
     *  of several keys); and an alarm is set now if none is pending (a force-stop, a lost one) */
    static synchronized void watch(final Context app, SharedPreferences sp) {
        if (listener != null) return;
        main = new Handler(Looper.getMainLooper());
        pendingRun[0] = () -> {
            try { schedule(app); } catch (RuntimeException e) { Log.w(TAG, "schedule: " + e); }
        };
        listener = (p, key) -> {
            if (!AthanPrefs.movesAlarm(key)) return;
            main.removeCallbacks(pendingRun[0]);
            main.postDelayed(pendingRun[0], 400);
        };
        sp.registerOnSharedPreferenceChangeListener(listener);
        main.post(() -> {
            try {
                if (pending(app, null, PendingIntent.FLAG_NO_CREATE) == null) schedule(app);
            } catch (RuntimeException e) { Log.w(TAG, "ensure: " + e); }
        });
    }
}
