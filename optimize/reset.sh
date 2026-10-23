#!/usr/bin/env bash
# Put a TV that was set up with these tools back the way it came, so it can be set up again "like new":
# every package from our lists re-enabled, the stock home and screensaver back, our apps (old and new names)
# removed, and TV Bro and SmartTube if the setup installed them, tweaks undone. Your own apps and data are not touched.
#   optimize/reset.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
tv() { "$PY" -I optimize/tv.py "$@"; }

echo "1/4 Stock home first (never leave the TV without one)…"
lists=(optimize/lists/tcl-safe.txt optimize/lists/tcl-optional.txt)
[ -f optimize/lists/mine.txt ] && lists+=(optimize/lists/mine.txt)
optimize/restore.sh "${lists[@]}" > /dev/null
stock=no
for try in 1 2 3 4; do   # a cold stock home can take a while to come up
  sleep 6
  if tv "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'" | grep -qE 'launcherx|tvlauncher'; then
    stock=yes; break
  fi
  tv "input keyevent KEYCODE_HOME" > /dev/null
done
if [ "$stock" = no ]; then
  echo "!! The stock home is not on screen. Stopping before removing anything: press Home on the remote,"
  echo "   then run optimize/reset.sh again."
  exit 1
fi

optimize/keep.sh || true   # a TV optimized before may have voice, mirroring or updates off
echo "2/4 The TV's own screensaver…"
printf '%s\n' "settings delete secure screensaver_components" "settings delete secure screensaver_default_component" \
  | tv --batch > /dev/null

echo "3/4 Removing our apps (old and new names; missing ones are skipped)…"
for p in com.raad.sauditv.home com.raad.sauditv.screensaver; do
  tv "pm uninstall $p" | grep -q Success && echo "   removed $p" || true
done
# TV Bro and SmartTube: only if the setup put them there (not in the TV's package list saved before it)
for p in com.phlox.tvwebbrowser org.smarttube.stable; do
  if [ -f optimize/run/backup.txt ] && ! grep -qx "package:$p" optimize/run/backup.txt; then
    tv "pm uninstall $p" | grep -q Success && echo "   removed $p" || true
  fi
done

echo "4/4 Tweaks back to Android's defaults…"
optimize/tweaks.sh restore > /dev/null
[ -d optimize/run ] && mv optimize/run "optimize/run-$(date +%Y%m%d-%H%M%S)"   # keep the old results apart

echo "Done. Still disabled on this TV (made so by the manufacturer, or by something else):"
tv "pm list packages -d" | sed 's/^package:/   /'
