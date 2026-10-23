package com.raad.sauditv.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The favourites row: one set of rules for the home screen, its «ترتيب المفضلة» screen and ADB (DockReceiver). */
final class Dock {
    static final int MIN = 5;    // the row never shows fewer apps than this
    static final int MAX = 10;   // tiles share the row width, so 10 still fit at 1080p
    static final String CHANGED = "com.raad.sauditv.home.DOCK_CHANGED";   // tells a running home screen to redraw

    private Dock() {}

    /** The launcher's settings file (named after MainActivity, which wrote it first). */
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    }

    /** Saved order, or the defaults from config.xml; may name apps that are no longer installed. */
    static List<String> saved(Context c) {
        String s = prefs(c).getString("dock", null);
        return s != null ? Arrays.asList(s.isEmpty() ? new String[0] : s.split(","))
                         : Arrays.asList(c.getResources().getStringArray(R.array.dock_apps));
    }

    /**
     * Every app the home screen can open, one activity per package: its TV activity (LEANBACK_LAUNCHER) if it has one,
     * else its ordinary launcher activity, which is all that OEM stores, settings and phone apps have on many
     * Chinese sets (CVTE, MediaTek). Left out: the hidden apps (config.xml), this launcher, and other home screens
     * (the stock one and its setup app answer HOME). The same list for the grid, «ترتيب المفضلة» and ADB.
     * Queried with GET_RESOLVED_FILTER: {@link #isTv} tells which kind each one is.
     */
    static List<ResolveInfo> catalogue(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> skip = new HashSet<>(Arrays.asList(c.getResources().getStringArray(R.array.hidden_apps)));
        skip.add(c.getPackageName());
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
            skip.add(ri.activityInfo.packageName);
        }
        Map<String, ResolveInfo> out = new LinkedHashMap<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(q, PackageManager.GET_RESOLVED_FILTER)) {
                String p = ri.activityInfo.packageName;
                if (!skip.contains(p) && !out.containsKey(p)) out.put(p, ri);
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Found as a TV activity (else as an ordinary launcher one): it is started with that same category. */
    static boolean isTv(ResolveInfo ri) {
        return ri.filter != null && ri.filter.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
    }

    /** Apps the home screen can show, package -> name, from {@link #catalogue}. */
    static Map<String, String> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, String> out = new LinkedHashMap<>();
        for (ResolveInfo ri : catalogue(c)) out.put(ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm)));
        return out;
    }

    /** The row as the home screen shows it: saved apps still installed, topped up to MIN alphabetically. */
    static List<String> shown(Context c, Map<String, String> apps) {
        List<String> row = new ArrayList<>();
        for (String p : saved(c)) if (apps.containsKey(p) && !row.contains(p) && row.size() < MAX) row.add(p);
        List<String> rest = new ArrayList<>(apps.keySet());
        rest.removeAll(row);
        Collections.sort(rest, (x, y) -> apps.get(x).compareToIgnoreCase(apps.get(y)));
        while (row.size() < MIN && !rest.isEmpty()) row.add(rest.remove(0));
        return row;
    }

    static void save(Context c, List<String> row) {
        prefs(c).edit().putString("dock", String.join(",", row)).apply();
        c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName()));
    }

    static void reset(Context c) {
        prefs(c).edit().remove("dock").apply();
        c.sendBroadcast(new Intent(CHANGED).setPackage(c.getPackageName()));
    }
}
