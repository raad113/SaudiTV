#!/usr/bin/env bash
# Save the TV's current state before changing anything: every package, which ones are already
# disabled, the home app, the screensaver, the animation speeds and the cached-apps limit. Writes optimize/backup-<time>.txt.
# Usage: optimize/backup.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
OUT="optimize/backup-$(date +%Y%m%d-%H%M%S).txt"
printf '%s\n' \
  "echo '== model'; getprop ro.product.model; getprop ro.build.version.release" \
  "echo '== disabled packages'; pm list packages -d" \
  "echo '== all packages'; pm list packages" \
  "echo '== home'; cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN" \
  "echo '== screensaver'; settings get secure screensaver_components; settings get secure screensaver_enabled; settings get system screen_off_timeout" \
  "echo '== animation'; settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale" \
  "echo '== cached apps (setting, then the value in use since boot)'; device_config get activity_manager max_cached_processes; dumpsys activity settings | grep -m1 max_cached_processes; settings get global cached_apps_freezer" \
  | "$PY" -I optimize/tv.py --batch > "$OUT"
echo "Saved $OUT"
