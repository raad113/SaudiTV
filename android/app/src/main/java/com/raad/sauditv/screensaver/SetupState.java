package com.raad.sauditv.screensaver;

/**
 * The first-run setup (SetupActivity) shows on a new install only, without Android (tests/java_test.py runs it).
 * Prefs.migrate writes setupDone once: an install that already had settings (an older build's, or this one's first read
 * already done: the household's TVs) never sees it; a new install, with nothing saved, does, until it is finished or
 * skipped. «معالج الإعداد» in the settings and optimize/saver.sh setup open it again any time.
 */
final class SetupState {
    private SetupState() {}

    static final String DONE = "setupDone";

    /**
     * setupDone for settings that have none yet. migrated: this build's first read (Prefs.migrate, its "geo") had
     * already run, so the TV was set up before; anySaved: settings of an older build were there. A new install: false.
     */
    static boolean initial(boolean migrated, boolean anySaved) { return migrated || anySaved; }
}
