#!/usr/bin/env bash
# Disable every package in a debloat list (one pm call per shell command; chained pm calls can stop early).
# Packages are disabled for user 0, never uninstalled. Undo with optimize/restore.sh <same list>.
# Refuses anything in optimize/lists/keep.txt.
# Usage: optimize/disable.sh optimize/lists/tcl-safe.txt
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: optimize/disable.sh <debloat list>}"
PY="${PY:-optimize/venv/bin/python}"
KEEP=optimize/lists/keep.txt
pkgs=$(grep -vE '^\s*(#|$)' "$LIST" | awk '{print $1}')
blocked=$(printf '%s\n' $pkgs | grep -Fxf <(grep -vE '^\s*(#|$)' "$KEEP" | awk '{print $1}') || true)
if [ -n "$blocked" ]; then
  echo "Refusing: these are in $KEEP (features people use, or the TV needs them):"
  printf '  %s\n' $blocked
  exit 1
fi
printf '%s\n' $pkgs | awk '{print "pm disable-user --user 0 " $1}' | "$PY" -I optimize/tv.py --batch
