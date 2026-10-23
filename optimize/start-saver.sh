#!/usr/bin/env bash
# Start the TV's screensaver now, the ways Android allows over ADB, and say whether it really runs.
#   optimize/start-saver.sh             prints «dream» (it runs) or «not started»; exit 1 if not started
#   optimize/start-saver.sh --preview   if Android won't start it (some Android 14 sets refuse ADB), show
#                                       سعودي TV's full-screen preview instead: the same page; prints «preview»
# Leave it with Home or Back.
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
tv() { "$PY" -I optimize/tv.py "$@"; }

dreaming() {   # Android 11+ runs a dream in DreamActivity; dumpsys dreams has mCurrentDream (14) or
  local s      # mCurrentDreamToken (older), null while none runs
  s=$(tv "dumpsys dreams | grep -E 'mCurrentDream(Token)?='; dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'")
  grep -qE 'DreamActivity|mCurrentDream(Token)?=[^n ]' <<< "$s"
}

tv "am start -n com.android.systemui/.Somnambulator" > /dev/null
sleep "${SAVER_WAIT_S:-6}"
if dreaming; then echo dream; exit 0; fi
tv "cmd dreams start-dreaming" > /dev/null 2>&1 || true    # Android 14's own way; refused on some sets
sleep "${SAVER_WAIT_S:-6}"
if dreaming; then echo dream; exit 0; fi
if [ "${1:-}" = --preview ]; then
  tv "am start -n com.raad.sauditv.screensaver/.MainActivity" > /dev/null
  echo preview
  exit 0
fi
echo "not started"
exit 1
