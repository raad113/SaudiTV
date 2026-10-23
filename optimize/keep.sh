#!/usr/bin/env bash
# Turn back on anything from optimize/lists/keep.txt that is disabled (AirPlay, casting, mirroring, voice, remote,
# inputs, tuner, firmware updates, Play Store, Google services). Our scripts never disable these, but a TV
# optimized before, by other tools or older versions, may have them off. Safe to run any time.
#   optimize/keep.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
off=$("$PY" -I optimize/tv.py "pm list packages -d" | sed -n 's/^package://p' | tr -d '\r' \
      | grep -Fxf <(grep -vE '^\s*(#|$)' optimize/lists/keep.txt | awk '{print $1}') || true)
# the stock home and setup app are in keep.txt but belong to install.sh launcher: leave them to it
off=$(printf '%s\n' $off | grep -vxE 'com.google.android.apps.tv.launcherx|com.google.android.tvlauncher|com.google.android.tungsten.setupwraith|com.tcl.keycustomfunctionservice' || true)
if [ -z "$off" ]; then echo "Protected features: all on."; exit 0; fi
echo "Turning back on (they must never be off):"
printf '   %s\n' $off
printf '%s\n' $off | awk '{print "pm enable " $1}' | "$PY" -I optimize/tv.py --batch > /dev/null
still=$("$PY" -I optimize/tv.py "pm list packages -d" | sed -n 's/^package://p' | tr -d '\r' | grep -Fxf <(printf '%s\n' $off) || true)
if [ -n "$still" ]; then echo "!! still off, tell the person: $(echo $still)"; exit 1; fi
echo "Protected features: all on."
