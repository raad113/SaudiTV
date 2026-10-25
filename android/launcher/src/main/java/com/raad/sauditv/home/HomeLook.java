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

    /** the side margin in dp for a screen wDp x hDp: the page's 40, scaled as the page scales (its smaller side) */
    public static float marginDp(float wDp, float hDp) {
        return MARGIN * Math.min(wDp / PAGE_W, hDp / PAGE_H);
    }

    /** the favourites row of «الجديد» from the right: the view index (left to right) of favourite i of n */
    public static int viewIndex(int i, int n) {
        return n - 1 - i;
    }
}
