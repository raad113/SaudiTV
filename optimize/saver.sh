#!/usr/bin/env bash
# سعودي TV's settings, for agents: no remote, no menus.
#   optimize/saver.sh show                         current settings
#   optimize/saver.sh cities                       built-in Saudi cities by region
#   optimize/saver.sh city الرس                    a built-in city (Arabic name as listed)
#   optimize/saver.sh city 'اسم' 25.86 43.49       any place: name, latitude, longitude
#   optimize/saver.sh find-team الهلال             a club's id (Arabic or English name): id:Name | league
#   optimize/saver.sh teams 136013:Al-Hilal 150643:Al-Zulfi    followed teams in order (up to 10)
#   optimize/saver.sh card prayers,forecast,news   beside the clock: one, or several that rotate every 30 s, of
#                                                  prayers verse forecast matches standings news local tech shows; or none
#   optimize/saver.sh home-card prayers,news       the home screen's card: the same choices
#   optimize/saver.sh genres Drama,Comedy          episodes card: Drama Comedy Action Crime Thriller Mystery
#                                                  Science-Fiction Fantasy Adventure Romance Family History Horror
#                                                  Anime Documentary Sports  ("" = all)
#   optimize/saver.sh langs Arabic,English         episodes card: English Arabic Korean Japanese Turkish Spanish
#                                                  French Hindi German  ("" = all)
#   optimize/saver.sh minrating 7.5                episodes card: lowest rating, one of 0 6 6.5 7 7.5 8
#   optimize/saver.sh autoupdate on|off            the page's auto-update from github.com/raad113/SaudiTV (on by default)
#   optimize/saver.sh update                       download the latest screen now; says where from and which page shows
# Only ADB can change these (com.raad.sauditv.screensaver/.SaverReceiver needs the shell's DUMP).
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"
send() {   # $1 action, $2 extras; prints the receiver's answer (the "data" of am broadcast)
  "$PY" -I optimize/tv.py "am broadcast -n com.raad.sauditv.screensaver/.SaverReceiver -a com.raad.sauditv.screensaver.$1 ${2:-}" \
    | sed -n '/data="/,$p' | sed '1s/.*data="//; $s/"$//'
}
quote() {  # one shell-safe argument for the TV's shell
  case "$1" in *"'"*) echo "no apostrophes please: $1" >&2; exit 1 ;; esac
  printf "'%s'" "$1"
}
case "${1:-}" in
  show)   send GET ;;
  cities) send CITIES ;;
  update) send UPDATE ;;
  city)
    [ $# -eq 2 ] || [ $# -eq 4 ] || { echo "usage: optimize/saver.sh city NAME [LAT LON]"; exit 1; }
    extra="--es city $(quote "$2")"
    [ $# -eq 4 ] && extra="$extra --es lat $(quote "$3") --es lon $(quote "$4")"
    send SET "$extra" ;;
  teams)
    shift
    [ $# -gt 0 ] || { echo "usage: optimize/saver.sh teams ID:EnglishName ..."; exit 1; }
    send SET "--es teams $(quote "$(IFS=,; echo "$*")")" ;;
  card)      send SET "--es panel $(quote "${2:?usage: optimize/saver.sh card prayers}")" ;;
  home-card) send SET "--es home_panel $(quote "${2:?usage: optimize/saver.sh home-card none}")" ;;
  genres)    send SET "--es genres $(quote "${2-}")" ;;
  langs)     send SET "--es langs $(quote "${2-}")" ;;
  minrating) send SET "--es minrating $(quote "${2:?usage: optimize/saver.sh minrating 7}")" ;;
  autoupdate)
    case "${2:-}" in on|true) v=true ;; off|false) v=false ;; *) echo "usage: optimize/saver.sh autoupdate on|off"; exit 1 ;; esac
    send SET "--es autoupdate $v" ;;
  find-team) shift; "$PY" -I optimize/find-team.py "$@" ;;   # Arabic or English name
  *) sed -n '2,19p' "$0"; exit 1 ;;
esac
