#!/system/bin/sh
# Safety switch, runs ON the TV. Started by optimize/install.sh before the stock home is disabled.
# If /data/local/tmp/home_ok does not appear within LIMIT seconds (the new home did not come up),
# it puts the stock home back on its own: re-enable it, re-enable the setup app, make it home, press Home.
# Without this, a TCL set left with no home shows a black screen and can reboot-loop with Wi-Fi down.
# Usage (on the TV): nohup sh /data/local/tmp/deadman.sh <stock home component> [seconds] &

STOCK="${1:-com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity}"
LIMIT="${2:-180}"
i=0
while [ $i -lt "$LIMIT" ]; do
  [ -f /data/local/tmp/home_ok ] && exit 0
  sleep 1
  i=$((i + 1))
done

pm enable "${STOCK%%/*}"
pm enable com.google.android.tungsten.setupwraith
cmd package set-home-activity --user 0 "$STOCK"
am start -n "$STOCK"
input keyevent KEYCODE_HOME
