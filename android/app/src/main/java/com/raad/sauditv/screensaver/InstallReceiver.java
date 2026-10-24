package com.raad.sauditv.screensaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The result of each PackageInstaller session AppInstaller commits (not exported: only Android's installer, through
 *  the PendingIntent this app made, reaches it). */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        AppInstaller.onStatus(c.getApplicationContext(), i);
    }
}
