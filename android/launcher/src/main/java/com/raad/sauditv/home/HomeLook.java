package com.raad.sauditv.home;

import java.util.regex.Pattern;

/**
 * «شكل الواجهة» on the home screen, without Android (tests/java_test.py runs it). The screensaver app's settings
 * fragment carries &look=new for «الجديد» (its Prefs.fragment, Look.param); anything else is «الكلاسيكي», as before.
 * «الجديد» (design/concepts/1-ambient-editorial) keeps the launcher's own views but lays them out its way: the apps row
 * without its glass band, from the right (the start of an Arabic row), its banners taller, just above the page's
 * «الأسواق» line; the pills in the concept's glass with gold icons, the VPN badge first; the side margin the page's.
 */
public final class HomeLook {
    private HomeLook() {}

    private static final Pattern NEW = Pattern.compile("(^|&)look=new(&|$)");

    /** the apps row in «الجديد», in dp (= the page's CSS px on the TV, 960 x 540): its height and its distance from the
     *  bottom edge (above the page's 28px «الأسواق» line, 12px up); a banner 64 tall (16:9), 10 under the row's top, its
     *  name 11 under it: a focused banner with its ring or lift and its name stay inside the 100 */
    public static final int DOCK_H_DP = 100, DOCK_B_DP = 44, TILE_H_DP = 64;
    public static final float TOP_DP = 10f, LABEL_GAP_DP = 11f;
    /** the page's side margin: 40 of its 960 x 540 (nxLay scales it with the screen, as this does) */
    public static final float MARGIN = 40f, PAGE_W = 960f, PAGE_H = 540f;

    /** «الجديد» in the settings fragment (the page's #look=new) */
    public static boolean isNew(String fragment) {
        return fragment != null && NEW.matcher(fragment).find();
    }

    private static final Pattern ROTATE = Pattern.compile("(?:^|&)rotate=(\\d+)(?:&|$)");
    /** an older settings app's fragment has no &rotate= (it rotated every 30 s, as the launcher did then) */
    public static final int ROTATE_OLD = 30;

    /** «مدة كل بطاقة» from the settings fragment (Prefs.fragment's &rotate=, 10 to 60 s); none, or not 5 to 600: 30 */
    public static int rotateSec(String fragment) {
        java.util.regex.Matcher m = fragment == null ? null : ROTATE.matcher(fragment);
        if (m == null || !m.find()) return ROTATE_OLD;
        try {
            int s = Integer.parseInt(m.group(1));
            return s >= 5 && s <= 600 ? s : ROTATE_OLD;
        } catch (NumberFormatException e) { return ROTATE_OLD; }
    }

    /** the side margin in dp for a screen wDp x hDp: the page's 40, scaled as the page scales (its smaller side) */
    public static float marginDp(float wDp, float hDp) {
        return MARGIN * Math.min(wDp / PAGE_W, hDp / PAGE_H);
    }

    /** what the home screen opens of the screensaver app's SetupActivity (MainActivity.openSetupIfNew) */
    public static final int ASK_NONE = 0, ASK_SETUP = 1, ASK_LOOK = 2;

    /**
     * ConfigProvider /setup's done and look_pending, each -1 when the column is missing (an older app, or none at all):
     * the first-run setup while done is 0 (it has its own look step); else the one-time look chooser while
     * look_pending is 1; else nothing (a missing column is never pending)
     */
    public static int ask(long done, long lookPending) {
        if (done == 0) return ASK_SETUP;
        return lookPending == 1 ? ASK_LOOK : ASK_NONE;
    }

    /** the favourites row of «الجديد» from the right: the view index (left to right) of favourite i of n */
    public static int viewIndex(int i, int n) {
        return n - 1 - i;
    }
}
