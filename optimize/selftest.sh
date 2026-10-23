#!/usr/bin/env bash
# Read-only checks of the TV tools on this TV and computer (docs/SETUP.md): an upload, Arabic text, the
# screensaver from ADB, and the apps a home screen can list. Everything runs in here, so an agent passes no quotes,
# pipes or semicolons through its own shell (PowerShell runs those itself). Leaves nothing behind.
#   optimize/selftest.sh
set -uo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
tv() { "$PY" -I optimize/tv.py "$@"; }
packages() { grep -oE '[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+' | cut -d/ -f1 | sort -u; }
apps() {   # packages with a MAIN activity in category $1 (--brief where the TV knows it)
  local out
  out=$(tv "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.$1" | packages)
  [ -n "$out" ] || out=$(tv "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.$1" | packages)
  echo "$out"
}

echo "== 1. upload"
echo sauditv-test > optimize/sauditv-test.txt
tv --push-b64 optimize/sauditv-test.txt /data/local/tmp/sauditv-test.txt
got=$(tv "cat /data/local/tmp/sauditv-test.txt" | tr -d "\r")
tv "rm -f /data/local/tmp/sauditv-test.txt" > /dev/null
rm -f optimize/sauditv-test.txt
[ "$got" = sauditv-test ] && echo "OK upload" || echo "FAILED upload (read back: ${got:-nothing})"

echo "== 2. Arabic"
got=$(tv "echo مرحبا" | tr -d "\r")
[ "$got" = "مرحبا" ] && echo "OK Arabic" || echo "FAILED Arabic (got: ${got:-nothing})"

echo "== 3. screensaver from ADB"
optimize/start-saver.sh || true
tv "input keyevent KEYCODE_HOME" > /dev/null

echo "== 4. apps a home screen can list"
tvapps=$(apps LEANBACK_LAUNCHER)
others=$(apps LAUNCHER)
homes=$(apps HOME)
echo "TV apps (LEANBACK_LAUNCHER): $(grep -c . <<< "$tvapps")"
echo "Only an ordinary launcher activity (the home screen lists these too):"
comm -13 <(echo "$tvapps") <(echo "$others") | sed 's/^/   /'
echo "Home screens (HOME):"
echo "$homes" | sed 's/^/   /'
