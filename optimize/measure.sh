#!/usr/bin/env bash
# Measure the TV's memory and load, fairly.
#   optimize/measure.sh before                 restart, 3 minutes on the home screen, then read (about 5 minutes)
#   optimize/measure.sh after --now            read right away, no restart
#   optimize/measure.sh saver --screensaver    start the screensaver, read after 60 s while it runs, then leave it
# The TV is kept awake while it settles (its screensaver timeout is stretched, then put back), so a screensaver
# kicking in can't spoil an idle reading. Writes optimize/run/ram-<label>.txt, adds a line to optimize/run/summary.md.
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
label="${1:?usage: optimize/measure.sh LABEL [--now|--screensaver]}"
mode="${2:-}"
mkdir -p optimize/run
out="optimize/run/ram-$label.txt"
tv() { "$PY" -I optimize/tv.py "$@"; }

if [ "$mode" = --screensaver ]; then
  tv "input keyevent KEYCODE_HOME" > /dev/null
  if ! optimize/start-saver.sh > /dev/null; then   # some Android 14 sets don't let ADB start it: nothing to measure
    echo "Android didn't start the screensaver from ADB on this TV: $label not measured." | tee "$out"
    if [ -f optimize/run/summary.md ]; then
      echo "| $label | not measured (Android didn't start the screensaver from ADB) | | | | |" >> optimize/run/summary.md
    fi
    tv "input keyevent KEYCODE_HOME" > /dev/null
    exit 0
  fi
  echo "screensaver running; reading in 60 s…"
  sleep "${SAVER_S:-60}"
else
  if [ "$mode" != --now ]; then
    tv --reboot
  fi
  timeout=$(tv "settings get system screen_off_timeout" | tr -dc '0-9')
  tv "settings put system screen_off_timeout 1800000" > /dev/null        # 30 min: stay on the home screen
  trap 'tv "settings put system screen_off_timeout ${timeout:-300000}" > /dev/null' EXIT
  tv "input keyevent KEYCODE_HOME" > /dev/null
  if [ "$mode" != --now ]; then
    echo "letting the TV settle for 3 minutes (kept awake)…"
    sleep "${SETTLE_S:-180}"
  fi
fi

printf '%s\n' \
  "echo '== memory'; grep -E 'MemTotal|MemAvailable|SwapTotal|SwapFree' /proc/meminfo" \
  "echo '== android'; dumpsys meminfo | grep -E 'Total RAM|Free RAM|Used RAM|Lost RAM'" \
  "echo '== processes'; ps -A | wc -l" \
  "echo '== cpu'; top -b -n 1 | head -14" \
  "echo '== disabled packages'; pm list packages -d | wc -l" \
  "echo '== on screen'; dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'; dumpsys dreams | grep -m1 -iE 'dreaming|mCurrentDream'" \
  "echo '== biggest'; dumpsys meminfo | sed -n '/Total [PR]SS by process/,/^$/p' | head -16" \
  | tv --batch > "$out"
[ "$mode" = --screensaver ] && tv "input keyevent KEYCODE_HOME" > /dev/null

kb() { local v; v=$(grep -m1 "^$1:" "$out" | tr -dc '0-9' || true); echo "${v:-0}"; }   # 0 if the TV has no such line
avail=$(( $(kb MemAvailable) / 1024 )); total=$(( $(kb MemTotal) / 1024 ))
swap=$(( ($(kb SwapTotal) - $(kb SwapFree)) / 1024 ))
procs=$(sed -n '/== processes/{n;n;p;}' "$out" | tr -dc '0-9')
cpus=$(grep -m1 -oE '[0-9]+%cpu' "$out" | tr -dc '0-9' || true)        # e.g. 400%cpu = 4 cores
idle=$(grep -m1 -oE '[0-9]+%idle' "$out" | tr -dc '0-9' || true)       # summed over the cores
[ -n "$cpus" ] && [ -n "$idle" ] && [ "$cpus" -gt 0 ] && idle=$(( idle * 100 / cpus ))
disabled=$(sed -n '/== disabled packages/{n;n;p;}' "$out" | tr -dc '0-9')
[ -f optimize/run/summary.md ] || printf '| state | free RAM (MB of %s) | swap used (MB) | processes | CPU idle | disabled packages |\n|---|---|---|---|---|---|\n' "$total" > optimize/run/summary.md
printf '| %s | %s | %s | %s | %s | %s |\n' "$label" "$avail" "$swap" "$procs" "${idle:+$idle%}" "$disabled" | tee -a optimize/run/summary.md
