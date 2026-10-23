#!/usr/bin/env bash
# Does Home bring back the new home screen from inside an app? Opens an app, presses Home, and says what is in
# front. Prints «OK: Home opens سعودي TV» or «!! Home opened …».
#   optimize/check-home.sh [package]      (default: YouTube, else the first app of the favourites row)
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
tv() { "$PY" -I optimize/tv.py "$@"; }
app="${1:-com.google.android.youtube.tv}"
tv "monkey -p $app 1" > /dev/null || true
sleep 8
tv "input keyevent KEYCODE_HOME" > /dev/null
sleep 4
front=$(tv "dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'" | tr -d '\r')
if grep -q com.raad.sauditv.home <<< "$front"; then
  echo "OK: Home opens سعودي TV"
else
  echo "!! Home opened: ${front:-nothing}"
  exit 1
fi
