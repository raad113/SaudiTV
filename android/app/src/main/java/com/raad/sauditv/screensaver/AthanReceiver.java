package com.raad.sauditv.screensaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * The athan's alarm (AthanScheduler; not exported: only our own PendingIntent reaches it). AthanPlan.decide says what
 * to do: a TV that is off or in standby (the screen not interactive) hears nothing and is never woken; an alarm that
 * comes late (held back while the TV slept) or was turned off since is skipped. Otherwise AthanService plays it. Then
 * the next alarm, after this one.
 */
public class AthanReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!AthanScheduler.ACTION_ALARM.equals(i.getAction())) return;
        AthanPlan.Event e = AthanScheduler.event(i);
        long now = System.currentTimeMillis();
        if (e != null) fire(c, e, false);
        else Log.w(AthanScheduler.TAG, "an alarm without its event");
        AthanScheduler.scheduleAfter(c, Math.max(now, e == null ? now : e.at) + 1000);
    }

    /** an event now: the alarm's, or a test's (saver.sh athan-test, the settings' «تجربة الأذان»); the outcome */
    static int fire(Context c, AthanPlan.Event e, boolean test) {
        AthanPrefs p = new AthanPrefs(c);
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean interactive = pm == null || pm.isInteractive();
        int what = AthanPlan.decide(e, p.settings(), System.currentTimeMillis(), interactive, test);
        AthanScheduler.done(c, e, AthanPlan.OUTCOMES[what] + (test ? " (test)" : ""));
        if (what == AthanPlan.PLAY_FULL || what == AthanPlan.PLAY_SHORT || what == AthanPlan.BANNER) {
            if (!AthanService.start(c, e, test)) {
                AthanScheduler.done(c, e, AthanPlan.OUTCOMES[what] + ": the service could not start");
                return -1;
            }
        }
        return what;
    }
}
