package com.raad.sauditv.screensaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The athan's hook into «إذاعة القرآن الكريم» for our other app (the home screen: same signing key, the manifest's
 * READ_CONFIG permission): RadioService.ACTION_PAUSE_FOR_ATHAN stops the radio, RadioService.ACTION_RESUME_AFTER_ATHAN
 * lets it play again (RadioService.hold). In this app, call RadioService.hold(context, on) directly; from ADB the same
 * actions go to SaverReceiver (optimize/saver.sh radio-hold on|off).
 *
 *   sendBroadcast(new Intent("com.raad.sauditv.screensaver.PAUSE_FOR_ATHAN")
 *       .setClassName("com.raad.sauditv.screensaver", "com.raad.sauditv.screensaver.RadioReceiver"));
 */
public class RadioReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = String.valueOf(i.getAction());
        if (RadioService.ACTION_PAUSE_FOR_ATHAN.equals(a)) RadioService.hold(c, true);
        else if (RadioService.ACTION_RESUME_AFTER_ATHAN.equals(a)) RadioService.hold(c, false);
    }
}
