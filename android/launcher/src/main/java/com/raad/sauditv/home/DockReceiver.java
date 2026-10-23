package com.raad.sauditv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The favourites row from ADB, for agents and scripts. Only the shell can send these: the manifest requires
 * android.permission.DUMP, which apps can't hold.
 *
 *   am broadcast -n com.raad.sauditv.home/.DockReceiver -a com.raad.sauditv.home.GET_DOCK
 *   am broadcast -n com.raad.sauditv.home/.DockReceiver -a com.raad.sauditv.home.LIST_APPS
 *   am broadcast -n com.raad.sauditv.home/.DockReceiver -a com.raad.sauditv.home.SET_DOCK --es apps pkg1,pkg2,...
 *   am broadcast -n com.raad.sauditv.home/.DockReceiver -a com.raad.sauditv.home.RESET_DOCK
 *
 * The answer is in the "data" of am's output. optimize/dock.sh wraps all four. People use «ترتيب المفضلة»
 * (DockActivity) instead.
 */
public class DockReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String action = String.valueOf(i.getAction());
        Map<String, String> apps = Dock.launchable(c);
        switch (action) {
            case "com.raad.sauditv.home.LIST_APPS": {
                StringBuilder b = new StringBuilder();
                for (Map.Entry<String, String> e : apps.entrySet()) b.append(e.getKey()).append(" | ").append(e.getValue()).append('\n');
                setResultData(b.toString().trim());
                return;
            }
            case "com.raad.sauditv.home.GET_DOCK":
                setResultData("dock: " + String.join(",", Dock.shown(c, apps)));
                return;
            case "com.raad.sauditv.home.RESET_DOCK":
                Dock.reset(c);
                setResultData("dock: " + String.join(",", Dock.shown(c, apps)) + " (defaults)");
                return;
            case "com.raad.sauditv.home.SET_DOCK": {
                String raw = i.getStringExtra("apps");
                if (raw == null) { setResultData("error: add --es apps pkg1,pkg2,..."); return; }
                List<String> row = new ArrayList<>(), skipped = new ArrayList<>();
                for (String p : raw.split(",")) {
                    p = p.trim();
                    if (p.isEmpty() || row.contains(p)) continue;
                    if (apps.containsKey(p) && row.size() < Dock.MAX) row.add(p); else skipped.add(p);
                }
                if (row.isEmpty()) { setResultData("error: none of these apps are on this TV's home screen"); return; }
                Dock.save(c, row);
                setResultData("dock: " + String.join(",", Dock.shown(c, apps))
                    + (skipped.isEmpty() ? "" : "  skipped (not installed, hidden or over " + Dock.MAX + "): "
                       + String.join(",", skipped)));
                return;
            }
            default:
                setResultData("error: unknown action " + action);
        }
    }
}
