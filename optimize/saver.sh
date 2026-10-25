#!/usr/bin/env bash
# سعودي TV's settings, for agents: no remote, no menus.
#   optimize/saver.sh show                         current settings
#   optimize/saver.sh countries                    every country: code, Arabic and English names
#   optimize/saver.sh cities [EG [filter]]         built-in Saudi cities by region; or a country's biggest bundled cities
#   optimize/saver.sh country EG|none              the city's country (its prayer method, units and news by default);
#                                                  none: not set, as older builds (the method from the TV's time zone)
#   optimize/saver.sh city الرس                    a built-in Saudi city, or a bundled city of the country set
#   optimize/saver.sh city القاهرة EG              a bundled city of that country (sets the country too)
#   optimize/saver.sh city 'اسم' 25.86 43.49 [ZONE] any place: name, latitude, longitude [and its IANA time zone;
#                                                  without one: the nearest bundled city's in the country set]
#   optimize/saver.sh method auto|umm-al-qura|isna|egypt|kuwait|qatar|dubai|mwl|karachi|diyanet
#                                                  «طريقة الحساب»; auto: the country's
#   optimize/saver.sh units auto|c|f               the temperature, °C or °F; auto: the country's (°F in the US)
#   optimize/saver.sh leagues                      the leagues of the table and the team picker, by country
#   optimize/saver.sh league auto|ksa.1|eng.1|tsdb.4829…   «الدوري في الترتيب»; auto: the first followed club's
#   optimize/saver.sh news-country auto|SA|EG…     «أخبار <الدولة>»; auto: the city's country
#   optimize/saver.sh find-team الهلال             a club's TheSportsDB id (Arabic or English name): id:Name | league
#   optimize/saver.sh clubs eng.1                  an ESPN league's clubs (or fifa.friendly: the national teams), as
#                                                  E<id>@<league>:Name for teams
#   optimize/saver.sh teams 136013:Al-Hilal E359@eng.1:Arsenal    followed teams in order (up to 10)
#   optimize/saver.sh card prayers,forecast,news   beside the clock: one, or several that rotate every 30 s, of
#                                                  prayers verse hadith forecast matches standings news local world tech
#                                                  shows; or none (verse: the Quran; hadith: Sahih al-Bukhari)
#   optimize/saver.sh home-card prayers,news       the home screen's card: the same choices
#   optimize/saver.sh genres Drama,Comedy          episodes card: Drama Comedy Action Crime Thriller Mystery
#                                                  Science-Fiction Fantasy Adventure Romance Family History Horror
#                                                  Anime Documentary Sports  ("" = all)
#   optimize/saver.sh langs Arabic,English         episodes card: English Arabic Korean Japanese Turkish Spanish
#                                                  French Hindi German  ("" = all)
#   optimize/saver.sh minrating 7.5                episodes card: lowest rating, one of 0 6 6.5 7 7.5 8
#   optimize/saver.sh numerals 0-9|arabic          «شكل الأرقام»: 0 1 2 3 (the default) or ٠ ١ ٢ ٣, everywhere
#   optimize/saver.sh tiles rounded|circle         «شكل التطبيقات», the home screen's apps: banners with rounded
#                                                  corners (the default) or round icons with their names
#   optimize/saver.sh focus google|apple           «شكل التحديد», the focused app: Google TV's white ring (the
#                                                  default) or Apple TV's lift and shadow
#   optimize/saver.sh sources world skynews,bbc    a news page's sources (news tech local world), read together; ""
#                                                  = its defaults. The ids: optimize/saver.sh show (sources_…)
#   optimize/saver.sh autoupdate on|off            the page's auto-update from github.com/raad113/SaudiTV (on by default)
#   optimize/saver.sh update                       download the latest screen now; says where from and which page shows
#   optimize/saver.sh app-check                    is there a newer release of both apps? (checks only; installing is
#                                                  the TV settings' «تحديث التطبيق»)
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
  countries) send COUNTRIES ;;
  leagues) send LEAGUES ;;
  cities)
    if [ $# -ge 2 ]; then
      extra="--es country $(quote "$2")"
      [ $# -ge 3 ] && extra="$extra --es filter $(quote "$3")"
      send CITIES "$extra"
    else send CITIES; fi ;;
  update) send UPDATE ;;
  app-check) send APP_CHECK ;;
  country)   send SET "--es country $(quote "${2:?usage: optimize/saver.sh country EG|none}")" ;;
  city)
    case $# in
      2) extra="--es city $(quote "$2")" ;;
      3) extra="--es city $(quote "$2") --es country $(quote "$3")" ;;   # a bundled city of that country
      4|5) extra="--es city $(quote "$2") --es lat $(quote "$3") --es lon $(quote "$4")"
           [ $# -eq 5 ] && extra="$extra --es tz $(quote "$5")" ;;
      *) echo "usage: optimize/saver.sh city NAME [COUNTRY] | city NAME LAT LON [ZONE]"; exit 1 ;;
    esac
    send SET "$extra" ;;
  method)    send SET "--es method $(quote "${2:?usage: optimize/saver.sh method auto|umm-al-qura|isna|egypt|kuwait|qatar|dubai|mwl|karachi|diyanet}")" ;;
  units)
    case "${2:-}" in auto|c|f) v=$2 ;; C|celsius) v=c ;; F|fahrenheit) v=f ;; *) echo "usage: optimize/saver.sh units auto|c|f"; exit 1 ;; esac
    send SET "--es units $v" ;;
  league)    send SET "--es league $(quote "${2:?usage: optimize/saver.sh league auto|ksa.1|eng.1|tsdb.4829 (optimize/saver.sh leagues)}")" ;;
  news-country) send SET "--es news_country $(quote "${2:?usage: optimize/saver.sh news-country auto|SA|EG}")" ;;
  clubs) "$PY" -I optimize/find-team.py --espn "${2:?usage: optimize/saver.sh clubs eng.1 (optimize/saver.sh leagues)}" ;;
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
  numerals)
    case "${2:-}" in 0-9|latin|western|latn) v=latn ;; arabic|arab|٠-٩) v=arab ;; *) echo "usage: optimize/saver.sh numerals 0-9|arabic"; exit 1 ;; esac
    send SET "--es numerals $v" ;;
  tiles)
    case "${2:-}" in rounded|round) v=rounded ;; circle|circles) v=circle ;; *) echo "usage: optimize/saver.sh tiles rounded|circle"; exit 1 ;; esac
    send SET "--es tile_shape $v" ;;
  focus)
    case "${2:-}" in google|google-tv) v=google ;; apple|apple-tv) v=apple ;; *) echo "usage: optimize/saver.sh focus google|apple"; exit 1 ;; esac
    send SET "--es focus_style $v" ;;
  sources)
    case "${2:-}" in news|tech|local|world) ;; *) echo "usage: optimize/saver.sh sources news|tech|local|world id,id (\"\" = the defaults)"; exit 1 ;; esac
    [ $# -ge 3 ] || { echo "usage: optimize/saver.sh sources $2 id,id (\"\" = the defaults)"; exit 1; }
    send SET "--es src_$2 $(quote "$3")" ;;
  find-team) shift; "$PY" -I optimize/find-team.py "$@" ;;   # Arabic or English name
  *) sed -n '2,42p' "$0"; exit 1 ;;
esac
