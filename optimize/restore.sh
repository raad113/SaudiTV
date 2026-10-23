#!/usr/bin/env bash
# Undo everything: re-enable every package in the given debloat lists, bring back the stock launcher, Google
# TV's setup app and the stock home's helpers (lists/home-replaced.txt), and make the stock launcher home again.
# Usage: optimize/restore.sh [debloat list ...]        (no list: only the home screen)
#   STOCK_LAUNCHER=com.google.android.tvlauncher/.MainActivity optimize/restore.sh list.txt   (older Android TV)
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx/.home.HomeActivity}"
{
  for LIST in "$@" optimize/lists/home-replaced.txt; do grep -vE '^\s*(#|$)' "$LIST" | awk '{print "pm enable " $1}'; done
  echo "pm enable com.google.android.tungsten.setupwraith"
  echo "touch /data/local/tmp/home_ok"      # stop a running safety switch, we restore by hand
  echo "pm enable com.google.android.apps.tv.launcherx"
  echo "pm enable com.google.android.tvlauncher"
  echo "pm enable ${STOCK_LAUNCHER%%/*}"
  echo "cmd package set-home-activity --user 0 $STOCK_LAUNCHER"
  echo "input keyevent KEYCODE_HOME"
} | "$PY" -I optimize/tv.py --batch
echo "Restored. Press Home on the remote."
