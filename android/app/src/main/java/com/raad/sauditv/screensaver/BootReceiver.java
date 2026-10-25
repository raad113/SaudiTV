package com.raad.sauditv.screensaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Some TVs (TCL) leave the app cold after a power cycle and the first dream fails.
 * Any boot broadcast that does arrive brings the process up and re-arms the update job;
 * the persisted job covers TVs that never send one.
 * After this app is updated (MY_PACKAGE_REPLACED, also an in-app update): the downloaded APKs go, and a home
 * screen in front drops its «تحديث متوفر» pill.
 * Each of these, and a change of the clock, the time zone or the exact-alarm permission, sets the athan's next alarm
 * again (AthanScheduler: alarms do not outlive a reboot, and a new clock moves every prayer).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try { AthanScheduler.schedule(c); } catch (RuntimeException e) { android.util.Log.w(AthanScheduler.TAG, "boot: " + e); }
        UpdateJob.schedule(c);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            AppInstaller.cleanup(c);
            c.sendBroadcast(new Intent(AppUpdate.STATE_CHANGED).setPackage(AppUpdate.HOME_PKG));
        }
    }
}
