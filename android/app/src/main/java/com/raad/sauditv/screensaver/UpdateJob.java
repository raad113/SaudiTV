package com.raad.sauditv.screensaver;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Periodic page update, and the apps' update check (AppUpdate: only checks; installing waits for the user). The job is
 * persisted, so after a reboot the system itself starts the app's process again, which also wakes it up for the first
 * screensaver run.
 */
public class UpdateJob extends JobService {
    private static final int ID = 1;

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == ID) return;
        js.schedule(new JobInfo.Builder(ID, new ComponentName(c, UpdateJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(Updater.INTERVAL_MS)
            .setPersisted(true)
            .build());
    }

    @Override
    public boolean onStartJob(final JobParameters p) {
        new Thread(new Runnable() {
            @Override public void run() {
                Updater.check(getApplicationContext(), false);
                AppUpdate.check(getApplicationContext(), false);   // the settings row and the home screen's pill
                jobFinished(p, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }
}
