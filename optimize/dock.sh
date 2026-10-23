#!/usr/bin/env bash
# The home screen's favourites row, for agents and scripts: no remote, no long-press.
#   optimize/dock.sh show                  the current order, left to right
#   optimize/dock.sh apps                  every app that can go in it: package | name
#   optimize/dock.sh set PKG1 PKG2 ...     a new order, left to right (up to 10; fewer than 5 is topped up)
#   optimize/dock.sh reset                 back to the defaults in android/launcher/.../config.xml
# The home screen redraws at once. Only ADB can do this (com.raad.sauditv.home/.DockReceiver needs the shell's DUMP).
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
send() {   # $1 action, $2 extra arguments; prints the receiver's answer (the "data" of am broadcast)
  "$PY" -I optimize/tv.py "am broadcast -n com.raad.sauditv.home/.DockReceiver -a com.raad.sauditv.home.$1 ${2:-}" \
    | sed -n '/data="/,$p' | sed '1s/.*data="//; $s/"$//'
}
case "${1:-}" in
  show)  send GET_DOCK ;;
  apps)  send LIST_APPS ;;
  reset) send RESET_DOCK ;;
  set)
    shift
    [ $# -gt 0 ] || { echo "usage: optimize/dock.sh set PKG1 PKG2 ..."; exit 1; }
    for p in "$@"; do [[ "$p" =~ ^[A-Za-z0-9_.]+$ ]] || { echo "not a package name: $p"; exit 1; }; done
    send SET_DOCK "--es apps $(IFS=,; echo "$*")" ;;
  *) sed -n '2,7p' "$0"; exit 1 ;;
esac
