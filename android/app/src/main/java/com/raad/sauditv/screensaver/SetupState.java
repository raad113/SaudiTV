package com.raad.sauditv.screensaver;

/**
 * The first-run setup (SetupActivity) shows on a new install only, without Android (tests/java_test.py runs it).
 * Prefs.migrate writes setupDone once: an install that already had settings (an older build's, or this one's first read
 * already done: the household's TVs) never sees it; a new install, with nothing saved, does, until it is finished or
 * skipped. «معالج الإعداد» in the settings and optimize/saver.sh setup open it again any time.
 * «شكل الواجهة» is asked once too (lookPending): a new install in the setup's own step, a TV set up before this build
 * (that kept «الكلاسيكي» without being asked) by the home screen opening SetupActivity in its look-only mode.
 */
final class SetupState {
    private SetupState() {}

    static final String DONE = "setupDone";
    /** a look was picked (the setup's step or its look-only mode, the settings' «شكل الواجهة», ADB look: Prefs.setLook),
     *  or the setup was finished or skipped (its look kept), or the look-only screen was left with Back */
    static final String LOOK_CHOSEN = "lookChosen";

    /**
     * setupDone for settings that have none yet. migrated: this build's first read (Prefs.migrate, its "geo") had
     * already run, so the TV was set up before; anySaved: settings of an older build were there. A new install: false.
     */
    static boolean initial(boolean migrated, boolean anySaved) { return migrated || anySaved; }

    /**
     * the home screen should open the one-time look chooser (ConfigProvider /setup's look_pending): the setup is done
     * (while it is not, its own step asks) and no look was ever chosen (an older build's settings have no lookChosen)
     */
    static boolean lookPending(boolean setupDone, boolean lookChosen) { return setupDone && !lookChosen; }
}
