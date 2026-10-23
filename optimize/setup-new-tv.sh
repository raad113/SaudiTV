#!/usr/bin/env bash
# Set up a Google TV / Android TV with سعودي TV: bloat off, speed tweaks, the SaudiTV home screen and screensaver,
# your household's settings, and optional extras. Run it from the SaudiTV folder in Git Bash (guide: docs/SETUP.md):
#
#   optimize/setup-new-tv.sh [TV_IP]             set up. Safe to run again: it only changes what differs, and
#                                                asks [y/N] before removing apps or downloading anything.
#   optimize/setup-new-tv.sh [TV_IP] --dry-run   connect, read the TV and print every change it would make
#                                                (it reads GitHub's release list, downloads nothing)
#   optimize/setup-new-tv.sh [TV_IP] --verify    only the checks: what is on the TV now, and Home from an app
#
# TV_IP: the TV's address (optionally IP:PORT). Without it: optimize/.tv_host, else optimize/find-tv.sh scans.
# The apps come from the latest GitHub release (github.com/raad113/SaudiTV/releases); LOCAL_APK_DIR=<folder> installs
# your own builds from that folder instead (saudi-tv-screensaver.apk, saudi-tv-home.apk).
# Never run two TV scripts at once. Everything here can be undone: see "Undo" in docs/SETUP.md.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

# ================================ Your household's settings ==============================================
# They live in household.env at the repository root, which git never tracks (copy household.env.example and
# edit it). Another file, e.g. for a second TV: HOUSEHOLD_ENV=household-tv2.env optimize/setup-new-tv.sh ...
# Without the file, the public defaults below are used: Riyadh, Umm al-Qura, the big Saudi clubs, six cards.
HOUSEHOLD_ENV="${HOUSEHOLD_ENV:-household.env}"
HOUSEHOLD_NOTE="no $HOUSEHOLD_ENV: the public defaults (Riyadh)"
if [ -f "$HOUSEHOLD_ENV" ]; then
  # shellcheck disable=SC1090
  source "$HOUSEHOLD_ENV" || { echo "!! $HOUSEHOLD_ENV has an error (it is read by bash)"; exit 1; }
  HOUSEHOLD_NOTE="settings from $HOUSEHOLD_ENV"
fi
CITY_NAME="${CITY_NAME:-الرياض}"           # shown on screen; Arabic is fine. Saudi cities: optimize/saver.sh cities
LAT="${LAT:-24.6877}"
LON="${LON:-46.7219}"
TZ_ID="${TZ_ID:-Asia/Riyadh}"               # the TV's own time zone: the apps use the TV's, this script checks it
[[ "$TZ_ID" == America/* ]] && _m=2 || _m=4
METHOD="${METHOD:-$_m}"                     # prayer times: 4 = Umm al-Qura. Not sent to the TV: our APK picks 2 (ISNA)
                                            #   when the time zone is America/*, else 4 (Umm al-Qura); this is only checked
ROTATE="${ROTATE:-30}"                      # seconds per card. Fixed in our APK (Prefs.java, HomePage.java); checked
if ! declare -p TEAMS > /dev/null 2>&1; then
  TEAMS=(                                   # followed teams, in order: TheSportsDB id:English name (the app shows the
    "136137:Saudi Arabia"                   #   Arabic name from its own table). Find ids: optimize/saver.sh find-team الهلال
    "136013:Al-Hilal"
    "136022:Al-Nassr"
    "136018:Al-Ittihad"
    "137721:Al-Ahli"
  )
fi
SAVER_PANELS="${SAVER_PANELS:-prayers,forecast,matches,standings,news,tech}"   # screensaver card, rotating
HOME_PANELS="${HOME_PANELS:-prayers,forecast,matches,standings,news,tech}"     # home screen card, rotating
DOCK_APPS="${DOCK_APPS:-}"                  # favourites row, left to right (apps not installed are left out);
                                            #   empty: the home screen's own default (config.xml), left as it is
MINRATING="${MINRATING:-}"                  # episodes card's lowest rating (0 6 6.5 7 7.5 8); empty: the app's (7)
AUTOUPDATE="${AUTOUPDATE:-}"                # the page's auto-update: on / off; empty: the app's default (on)
EXTRA_OFF="${EXTRA_OFF:-}"                  # more packages to turn off, on top of optimize/extra/disable.txt
EXTRA_REMOVE="${EXTRA_REMOVE:-}"            # more packages to remove for the TV's user (asked first), on top of
                                            #   optimize/extra/remove.txt
SLEEP_TIMEOUT_MS="${SLEEP_TIMEOUT_MS:-}"    # screen off this long after the last key press (e.g. 10800000 = 3 h);
                                            #   empty: the TV's own setting, left as it is

# ================================ TV settings (one line each: namespace key value) ======================
# Applied with `settings put` only when the TV's value differs. To add one, add one line. To undo one:
#   optimize/venv/bin/python -I optimize/tv.py "settings delete <namespace> <key>"
TV_SETTINGS=(
  "global window_animation_scale 0.5"                                  # menus at half length
  "global transition_animation_scale 0.5"
  "global animator_duration_scale 0.5"
  "secure screensaver_enabled 1"                                       # our screensaver on (install.sh sets these
  "secure screensaver_components com.raad.sauditv.screensaver/.ScreenDream"     #   too; checked again here)
  "secure screensaver_default_component com.raad.sauditv.screensaver/.ScreenDream"
)
# the screensaver still starts after the TV's idle time; SLEEP_TIMEOUT_MS after the last key press it ends and the
# TV sleeps (the remote wakes it)
[ -n "$SLEEP_TIMEOUT_MS" ] && TV_SETTINGS+=("secure sleep_timeout $SLEEP_TIMEOUT_MS")

# ================================ Fixed parts =============================================================
EXTRA=optimize/extra
APKS="${LOCAL_APK_DIR:-}"                   # empty: the latest GitHub release
REPO=raad113/SaudiTV
PY=optimize/venv/bin/python
NUVIO_REPO=DavidVamaiotu/NuvioTV-Reshaped
NUVIO_PKG=com.nuvioreshaped.tv
NUVIO_PIN_TAG=1.0.0-autosync.20             # used if GitHub's "latest release" can't be read; sha256 per build:
declare -A NUVIO_PIN_SHA=(
  [arm32]=9d09c79df6b2aa2d71e14601e87e9a13f34b29f69c0bae08b2512f86062c88b4
  [arm64]=22a535d3529459a3ec1d15ee3ac76086786e881c030ab4ccbf49e582db8cf080
  [x32]=487e83150e5eea087d5d89b21bee4e14b9d8b121487fc6db474fa948e5306b53
  [x64]=7531cd3c5c1050bb6142a8f80039b0bcd50d8a84592fda36cdd3f21ea62c205e
  [any]=56006a46069485ee4b8a16230487cdc0bb09adaf841ee4f5eba25df81ad0cddd
)
# never disabled or removed by this script, whatever a list says (home screens, setup, store, Google core, inputs)
NEVER='^(android|com\.android\.(systemui|vending|tv\.settings|settings|providers\..*)|com\.google\.android\.(apps\.tv\.launcherx|tvlauncher|tungsten\.setupwraith|gms|gsf|webview)|com\.mediatek\.(wwtv\.tvcenter|tvinput|tv\.service)|com\.tcl\.(tv|tvinput|suspension)|com\.raad\.sauditv\..*)$'

MODE=apply
TV_IP=""
for a in "$@"; do
  case "$a" in
    --dry-run) MODE=dry ;;
    --verify)  MODE=verify ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    -*) echo "unknown option: $a (see --help)"; exit 2 ;;
    *) TV_IP=$a ;;
  esac
done

mkdir -p optimize/run
if ! mkdir optimize/run/.setup-new-tv.lock 2>/dev/null; then
  echo "!! Another setup-new-tv.sh seems to be running (optimize/run/.setup-new-tv.lock). If not, delete that folder."
  exit 1
fi
trap 'rmdir optimize/run/.setup-new-tv.lock 2>/dev/null' EXIT
exec > >(tee -a optimize/run/setup-new-tv.log) 2>&1
echo "===== setup-new-tv.sh $MODE $(date '+%Y-%m-%d %H:%M:%S')"

DONE=(); SKIPPED=(); WARNED=()
n=0
step()  { n=$((n + 1)); echo; echo "▶ $n. $*"; }
ok()    { echo "   ✓ $*"; }
did()   { local m="$*"; [ "$MODE" = dry ] && m="would: $m"; DONE+=("$m"); echo "   ✓ $m"; }
skip()  { SKIPPED+=("$*"); echo "   · $*"; }
warn()  { WARNED+=("$*"); echo "   !! $*"; }
tv()    { "$PY" -I optimize/tv.py "$@"; }                       # reads (and the changes below in apply mode)
tvq()   { tv "$@" 2>/dev/null | tr -d '\r'; }                   # a read, clean
tvx()   { if [ "$MODE" = apply ]; then tv "$@"; else echo "   [dry-run] tv: $*"; fi; }      # a change on the TV
tvxb()  { if [ "$MODE" = apply ]; then tv --batch; else sed 's/^/   [dry-run] tv: /'; fi; }  # changes, one per line
runx()  { if [ "$MODE" = apply ]; then "$@"; else echo "   [dry-run] run: $*"; fi; }      # a script that changes the TV
ask() {   # ask "question" [y|n default]. Dry run: shows the "yes" path. No terminal (an agent): the default.
  local def=${2:-n} ans="" hint="[y/N]"
  [ "$def" = y ] && hint="[Y/n]"
  if [ "$MODE" != apply ]; then echo "   [dry-run] would ask: $1 $hint (showing the yes path)"; return 0; fi
  { read -r -p "   ? $1 $hint " ans < /dev/tty; } 2>/dev/null || { echo "   ? $1 $hint -> no terminal, default"; ans=""; }
  case "${ans:-$def}" in y|Y|yes|Yes|YES|ن|نعم) return 0 ;; *) return 1 ;; esac
}
summary() {
  echo
  echo "================================ Summary ($MODE) ================================"
  [ ${#DONE[@]} -gt 0 ]    && { echo "Done:";    printf '  ✓ %s\n' "${DONE[@]}"; }
  [ ${#SKIPPED[@]} -gt 0 ] && { echo "Skipped / already so:"; printf '  · %s\n' "${SKIPPED[@]}"; }
  [ ${#WARNED[@]} -gt 0 ]  && { echo "Needs a look:"; printf '  !! %s\n' "${WARNED[@]}"; }
  cat <<'EOF'
Manual steps (no script can do these):
  1. On the TV, once: Settings > System > About > press OK on "Build" 7 times; then Developer options >
     USB debugging ON (and Network debugging if listed). On the first connection press "Allow" + "Always allow".
  2. Sign in to Google on the TV (Settings > Accounts & sign-in). Nobody types passwords for you.
  3. Surfshark: install it from the Play Store on the TV, sign in, connect, and turn on its auto-connect
     (and Android's "Always-on VPN" for Surfshark if the TV's Network > VPN menu offers it).
  4. If the summary says the time zone differs: Settings > System > Date & time.
  5. Keep the PC's own VPN (Surfshark on the PC) disconnected while this script runs.
Log: optimize/run/setup-new-tv.log    Undo: docs/SETUP.md, section "Undo"
EOF
}
die() { echo "!! $*"; WARNED+=("stopped: $*"); summary; exit 1; }

INSTALLED=""; DISABLED=""
refresh_pkgs() {
  INSTALLED=$(tvq "pm list packages" | sed -n 's/^package://p')
  DISABLED=$(tvq "pm list packages -d" | sed -n 's/^package://p')
}
has()    { grep -qxF "$1" <<< "$INSTALLED"; }
is_off() { grep -qxF "$1" <<< "$DISABLED"; }
list_pkgs() { grep -vE '^\s*(#|$)' "$1" | awk '{print $1}'; }

# ---------------------------------------------------------------------------------------------------------
step "PC checks and the TV's address"
[ -f optimize/tv.py ] || die "Run this from the SaudiTV folder (optimize/tv.py is missing)."
if [ -n "$APKS" ]; then
  [ -f "$APKS/saudi-tv-home.apk" ] && [ -f "$APKS/saudi-tv-screensaver.apk" ] \
    || die "LOCAL_APK_DIR=$APKS has no saudi-tv-home.apk and saudi-tv-screensaver.apk."
fi
ok "$HOUSEHOLD_NOTE"
if command -v powershell.exe > /dev/null 2>&1; then
  vpn=$(powershell.exe -NoProfile -Command "Get-NetAdapter | Where-Object { \$_.Status -eq 'Up' -and (\$_.Name + ' ' + \$_.InterfaceDescription) -match 'Surfshark|OpenVPN|WireGuard|Wintun|TAP-Windows|NordLynx|VPN' } | ForEach-Object { \$_.Name }" 2>/dev/null | tr -d '\r')
  if [ -n "$vpn" ]; then
    warn "A VPN is connected on this PC ($vpn). Earlier, Surfshark on the PC hid the TV from optimize/find-tv.sh."
    if [ "$MODE" = apply ]; then
      ask "Disconnect the PC's VPN first, then answer y to go on (n stops here)" n || die "PC VPN still on: disconnect it, then run again."
    fi
  else
    ok "no VPN connected on this PC"
  fi
fi
optimize/find-tv.sh --check || die "This terminal can't reach the home network (see the line above)."
if [ -n "$TV_IP" ]; then
  export TV_HOST="${TV_IP%%:*}"
  [[ "$TV_IP" == *:* ]] && export TV_PORT="${TV_IP##*:}"
  if [ "$MODE" = apply ]; then echo "$TV_HOST" > optimize/.tv_host; fi
  ok "TV at $TV_IP"
elif [ -s optimize/.tv_host ]; then
  ok "TV at $(cat optimize/.tv_host) (optimize/.tv_host; give an address to use another TV)"
else
  optimize/find-tv.sh || die "No TV found. On the TV: USB debugging on, TV awake, same Wi-Fi. Or give its IP."
fi

# ---------------------------------------------------------------------------------------------------------
step "Python ADB client (optimize/venv)"
if [ -x "$PY" ] && "$PY" -I -c "import adb_shell" 2> /dev/null; then
  ok "ready"
else
  [ "$MODE" = apply ] || die "optimize/venv is missing: run without --dry-run/--verify once, or optimize/setup.sh"
  ask "Create optimize/venv now? It downloads the Python ADB package from pypi.org (optimize/setup.sh)" y \
    || die "The scripts need optimize/venv (optimize/setup.sh)."
  optimize/setup.sh || die "optimize/setup.sh failed (its !! line says why); run this script again after."
  did "Python ADB client installed (optimize/venv)"
fi

# ---------------------------------------------------------------------------------------------------------
step "Connect to the TV"
echo "   On the TV: if «Allow USB debugging?» appears, press Allow and tick «Always allow»."
echo "   على التلفزيون: إذا طلعت «Allow USB debugging?» اضغط Allow وعلّم «Always allow». (ينتظر حتى دقيقتين)"
probe=$(tv --probe) || die "The TV did not answer (TV awake? USB debugging on? same Wi-Fi?). Then run again."
echo "$probe" | sed 's/^/   /'
ABIS=$(tvq "getprop ro.product.cpu.abilist")
refresh_pkgs
[ -n "$INSTALLED" ] || die "Could not read the TV's app list."
ok "connected; processor types: $ABIS"

# ---------------------------------------------------------------------------------------------------------
disable_list() {   # $1 list, $2 "upstream" also refuses optimize/lists/keep.txt (our extra lists may override it)
  local p todo=() missing=0 already=0
  for p in $(list_pkgs "$1"); do
    if [[ "$p" =~ $NEVER ]]; then warn "refused $p from $1: the TV needs it"; continue; fi
    if [ "$2" = upstream ] && list_pkgs optimize/lists/keep.txt | grep -qxF "$p"; then
      warn "refused $p from $1: in optimize/lists/keep.txt"; continue
    fi
    if ! has "$p"; then missing=$((missing + 1)); continue; fi
    if is_off "$p"; then already=$((already + 1)); continue; fi
    todo+=("$p")
  done
  if [ ${#todo[@]} -gt 0 ]; then
    printf 'pm disable-user --user 0 %s\n' "${todo[@]}" | tvxb | grep -vE '^\$ ' | sed 's/^/   /'
    did "$1: turned off ${todo[*]}"
  fi
  skip "$1: $already already off, $missing not on this TV"
}

step "Clean-up and speed"
if [ "$MODE" = verify ]; then
  skip "clean-up (verify only)"
else
  runx optimize/backup.sh | sed 's/^/   /'      # the TV's state before, for undo (optimize/backup-*.txt)
  disable_list optimize/lists/tcl-safe.txt upstream
  disable_list "$EXTRA/disable.txt" ours
  if [ -n "$EXTRA_OFF" ]; then   # the household's own picks (EXTRA_OFF in household.env)
    printf '%s\n' $EXTRA_OFF > optimize/run/household-off.txt
    disable_list optimize/run/household-off.txt ours
  fi
  gone=()
  for p in $(list_pkgs "$EXTRA/remove.txt") $EXTRA_REMOVE; do
    [[ "$p" =~ $NEVER ]] && { warn "refused to remove $p"; continue; }
    has "$p" && gone+=("$p")
  done
  if [ ${#gone[@]} -eq 0 ]; then
    skip "$EXTRA/remove.txt and EXTRA_REMOVE: nothing to remove"
  elif ask "Remove these apps for the TV's user: ${gone[*]}? (undo: cmd package install-existing <pkg>, or the Play Store)" n; then
    for p in "${gone[@]}"; do tvx "pm uninstall --user 0 $p" | sed 's/^/   /'; done
    did "removed ${gone[*]}"
  else
    skip "kept (you said no): ${gone[*]}"
  fi
  runx optimize/tweaks.sh apply | sed 's/^/   /'   # animations 0.5, Play Store not in background,
  did "speed tweaks (optimize/tweaks.sh apply)"     # closed apps frozen, caches trimmed
  refresh_pkgs
fi

# ---------------------------------------------------------------------------------------------------------
step "The سعودي TV home screen and screensaver (${APKS:-the latest GitHub release})"
apk_same() {   # $1 apk file, $2 package: is exactly this build installed?
  local mine theirs
  mine=$(md5sum "$1" | cut -c1-32)
  theirs=$(tvq "f=\$(pm path $2 2>/dev/null | head -1 | cut -d: -f2); [ -n \"\$f\" ] && md5sum \"\$f\"" | cut -c1-32)
  [ "$mine" = "$theirs" ]
}
installed_ver() { tvq "dumpsys package $1 2>/dev/null | grep -m1 versionCode" | sed 's/^ *versionCode=\([0-9]*\).*/\1/'; }
WANT_VER=""
if [ -z "$APKS" ]; then   # the latest release's number: v9034 -> 9034 ("" if GitHub can't be read)
  WANT_VER=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2> /dev/null | "$PY" -I -c '
import json, sys
try:
    print(json.load(sys.stdin).get("tag_name", "").lstrip("v"))
except Exception:
    pass')
  if [ -n "$WANT_VER" ]; then ok "latest release: v$WANT_VER"; else warn "GitHub's release list could not be read"; fi
fi
up_to_date() {   # $1 apk name, $2 package: is that build (the local file, or the latest release) installed?
  if [ -n "$APKS" ]; then apk_same "$APKS/$1" "$2"; else [ -n "$WANT_VER" ] && [ "$(installed_ver "$2")" = "$WANT_VER" ]; fi
}
home_now() { tvq "cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN" | tail -1; }
need_saver=no; need_home=no
up_to_date saudi-tv-screensaver.apk com.raad.sauditv.screensaver || need_saver=yes
up_to_date saudi-tv-home.apk com.raad.sauditv.home || need_home=yes
home_now | grep -q '^com.raad.sauditv.home/' || need_home=yes
if [ $need_saver = yes ] && [ $need_home = yes ]; then what=all
elif [ $need_saver = yes ]; then what=saver
elif [ $need_home = yes ]; then what=launcher
else what=""; fi
if [ "$MODE" = verify ]; then
  skip "install (verify only): screensaver up to date: $([ $need_saver = no ] && echo yes || echo NO), home: $([ $need_home = no ] && echo yes || echo NO)"
elif [ -z "$what" ]; then
  skip "this exact build is already installed and Home is ours"
else
  tvx "input keyevent KEYCODE_WAKEUP"               # a sleeping TV makes the Home check fail
  # install.sh arms a safety switch: the stock home comes back by itself within 5 minutes unless ours works
  if [ "$MODE" = apply ]; then
    LOCAL_APK_DIR="$APKS" optimize/install.sh "$what" 2>&1 | grep -vE 'uploading .*%' | sed 's/^/   /'
    [ "${PIPESTATUS[0]}" -eq 0 ] || die "optimize/install.sh $what failed. Leave the TV 5 minutes (the stock home comes back by itself), then run this again."
  else
    echo "   [dry-run] run: LOCAL_APK_DIR=$APKS optimize/install.sh $what"
  fi
  did "installed سعودي TV ($what) from ${APKS:-the latest GitHub release}"
fi
if [ "$MODE" = dry ]; then
  echo "   [dry-run] run: optimize/check-home.sh && tv: touch /data/local/tmp/home_ok"
else
  if optimize/check-home.sh 2>&1 | sed 's/^/   /'; then   # pipefail: check-home.sh's own answer
    tv "touch /data/local/tmp/home_ok" > /dev/null   # disarms a safety switch that may still be waiting
    ok "Home from inside an app opens our home screen (home_ok set)"
  else
    warn "Home did not open our home screen. Leave the TV 5 minutes, then run this script again."
  fi
fi

# ---------------------------------------------------------------------------------------------------------
step "TV settings"
for entry in "${TV_SETTINGS[@]}"; do
  read -r ns key val <<< "$entry"
  cur=$(tvq "settings get $ns $key")
  if [ "$cur" = "$val" ]; then ok "$ns $key = $val"
  elif [ "$MODE" = verify ]; then warn "$ns $key is $cur (want $val)"
  else tvx "settings put $ns $key $val"; did "$ns $key: $cur -> $val"; fi
done

# ---------------------------------------------------------------------------------------------------------
step "سعودي TV settings: city, time zone, teams, cards"
tz_now=$(tvq "getprop persist.sys.timezone")
if [ "$tz_now" = "$TZ_ID" ]; then
  ok "time zone $TZ_ID"
else
  warn "the TV's time zone is ${tz_now:-unknown}, not $TZ_ID (prayer times and clock follow the TV's)"
  if [ "$MODE" != verify ] && ask "Set the TV's time zone to $TZ_ID now? (turns the TV's automatic time zone off)" n; then
    tvx "cmd time_zone_detector set_auto_detection_enabled false"
    tvx "cmd time_zone_detector suggest_manual_time_zone --zone_id $TZ_ID" | sed 's/^/   /'
    if [ "$MODE" = apply ]; then
      if [ "$(tvq "getprop persist.sys.timezone")" = "$TZ_ID" ]; then did "time zone set to $TZ_ID"
      else warn "time zone did not change: set it on the TV (Settings > System > Date & time)"; fi
    fi
  fi
fi
want_method=4; [[ "$TZ_ID" == America/* ]] && want_method=2
if [ "$METHOD" = "$want_method" ]; then ok "prayer method $METHOD (the app picks it from the time zone)"
else warn "METHOD=$METHOD, but our APK uses $want_method for $TZ_ID (Prefs.prayerMethod in android/app; rebuild to change)"; fi
if [ "$ROTATE" = 30 ]; then ok "cards change every 30 s (built into our APK)"
else warn "ROTATE=$ROTATE, but our APK is built with 30 s (Prefs.panelParams, HomePage.java; rebuild to change)"; fi

show=$(optimize/saver.sh show 2>/dev/null | tr -d '\r')
changed=no
saver_set() {   # $1 label, $2.. the saver.sh command
  local label=$1 out; shift
  if [ "$MODE" = verify ]; then warn "$label differs (fix: run this script without --verify)"; return; fi
  if [ "$MODE" = apply ]; then
    out=$(optimize/saver.sh "$@" 2>&1); echo "$out" | sed 's/^/   /'
    grep -qiE 'error|usage' <<< "$out" && { warn "$label: optimize/saver.sh $* said no"; return; }
  else
    echo "   [dry-run] run: optimize/saver.sh $*"
  fi
  did "$label set"; changed=yes
}
want_city="city: $CITY_NAME ($LAT, $LON)"
if grep -qxF "$want_city" <<< "$show"; then ok "$want_city"; else saver_set "city ($CITY_NAME $LAT $LON)" city "$CITY_NAME" "$LAT" "$LON"; fi
want_ids=$(printf '%s\n' "${TEAMS[@]}" | cut -d: -f1 | paste -sd, -)
have_ids=$(sed -n 's/^teams: //p' <<< "$show" | grep -oE '[0-9]+\|' | tr -d '|' | paste -sd, -)
if [ "$want_ids" = "$have_ids" ]; then ok "teams: $(sed -n 's/^teams: //p' <<< "$show")"; else saver_set "teams ($want_ids)" teams "${TEAMS[@]}"; fi
if grep -qxF "panel: $SAVER_PANELS" <<< "$show"; then ok "screensaver card: $SAVER_PANELS"; else saver_set "screensaver card ($SAVER_PANELS)" card "$SAVER_PANELS"; fi
if grep -qxF "home_panel: $HOME_PANELS" <<< "$show"; then ok "home card: $HOME_PANELS"; else saver_set "home card ($HOME_PANELS)" home-card "$HOME_PANELS"; fi
if [ -n "$MINRATING" ]; then
  if grep -qxF "minrating: $MINRATING" <<< "$show"; then ok "episodes: rating $MINRATING and up"; else saver_set "episodes rating ($MINRATING)" minrating "$MINRATING"; fi
fi
if [ -n "$AUTOUPDATE" ]; then
  case "$AUTOUPDATE" in on|true) want_au=true ;; *) want_au=false ;; esac
  if grep -qxF "autoupdate: $want_au" <<< "$show"; then ok "page auto-update: $AUTOUPDATE"; else saver_set "page auto-update ($AUTOUPDATE)" autoupdate "$AUTOUPDATE"; fi
fi
if [ $changed = yes ]; then runx optimize/saver.sh update | sed 's/^/   /'; fi

# ---------------------------------------------------------------------------------------------------------
step "Extras (each asks first; nothing is downloaded without a yes)"
nuvio() {
  local have rel line tag url sha size name dir suffix out
  have=$(tvq "dumpsys package $NUVIO_PKG 2>/dev/null | grep -m1 versionName" | sed 's/.*versionName=//')
  if [ "$MODE" = verify ]; then
    if [ -n "$have" ]; then ok "Nuvio Reshaped $have"; else skip "Nuvio Reshaped not installed"; fi; return
  fi
  if [ -n "$have" ]; then
    ask "Nuvio Reshaped $have is installed. Look for a newer stable release on GitHub?" n || { skip "Nuvio Reshaped: kept $have"; return; }
  else
    ask "Install Nuvio Reshaped (latest stable from github.com/$NUVIO_REPO, the build for this TV's processor)?" n \
      || { skip "Nuvio Reshaped: not installed (you said no)"; return; }
  fi
  rel=$(curl -fsSL "https://api.github.com/repos/$NUVIO_REPO/releases/latest" 2>/dev/null) || rel=""
  line=$(printf '%s' "$rel" | "$PY" -I -c '
import json, re, sys
try:
    r = json.load(sys.stdin)
except Exception:
    sys.exit(0)
m = {"arm64-v8a": "arm64", "armeabi-v7a": "arm32", "armeabi": "arm32", "x86_64": "x64", "x86": "x32"}
want = []
for abi in sys.argv[1].split(","):
    s = m.get(abi.strip())
    if s and s not in want:
        want.append(s)
want.append("any")
assets = {a["name"]: a for a in r.get("assets", [])}
for s in want:
    hit = sorted(n for n in assets if re.fullmatch(r"NuvioRS-TV-.+-" + s + r"\.apk", n))
    if hit:
        a = assets[hit[0]]
        print(r.get("tag_name", ""), a["browser_download_url"], (a.get("digest") or "").replace("sha256:", ""), a.get("size", 0))
        break
' "$ABIS")
  read -r tag url sha size <<< "$line"
  if [ -z "${url:-}" ]; then   # GitHub not readable: the build we tested on the Hisense, pinned with its sha256
    case ",$ABIS," in *,arm64-v8a,*) suffix=arm64 ;; *,armeabi-v7a,*|*,armeabi,*) suffix=arm32 ;;
                      *,x86_64,*) suffix=x64 ;; *,x86,*) suffix=x32 ;; *) suffix=any ;; esac
    tag=$NUVIO_PIN_TAG; sha=${NUVIO_PIN_SHA[$suffix]}; size=0
    url="https://github.com/$NUVIO_REPO/releases/download/$tag/NuvioRS-TV-$tag-$suffix.apk"
    warn "GitHub's release list could not be read: using the pinned $tag"
  fi
  name=$(basename "$url")
  if [ "$have" = "$tag" ]; then skip "Nuvio Reshaped $have is the latest stable"; return; fi
  echo "   Nuvio Reshaped $tag: $name ($((size / 1048576)) MB)"
  if [ "$MODE" = dry ]; then
    echo "   [dry-run] run: curl -fL -o optimize/run/dl.*/$name $url (sha256 ${sha:-none given}), then pm install -r"
    return
  fi
  dir=$(mktemp -d optimize/run/dl.XXXXXX)       # downloads go in their own new folder, deleted after
  if ! curl -fL --retry 3 -o "$dir/$name" "$url"; then warn "Nuvio download failed"; rm -rf "$dir"; return; fi
  if [ -n "$sha" ] && [ "$(sha256sum "$dir/$name" | cut -c1-64)" != "$sha" ]; then
    warn "Nuvio download does not match its sha256: not installed"; rm -rf "$dir"; return
  fi
  tv --push-b64 "$dir/$name" /data/local/tmp/nuvio.apk | grep -vE 'uploading .*%' | sed 's/^/   /'
  out=$(tv "pm install -r /data/local/tmp/nuvio.apk"); tv "rm -f /data/local/tmp/nuvio.apk" > /dev/null
  rm -rf "$dir"
  if grep -q Success <<< "$out"; then did "Nuvio Reshaped $tag installed ($name)"
  else warn "Nuvio install said: $out"; fi
}
nuvio
if has com.phlox.tvwebbrowser && has org.smarttube.stable; then
  ok "TV Bro and SmartTube already installed"
elif [ "$MODE" = verify ]; then
  skip "TV Bro / SmartTube not both installed"
elif ask "Download and install TV Bro (web browser) and SmartTube (YouTube without ads) from their GitHub releases?" n; then
  runx optimize/install.sh extras | sed 's/^/   /'
  did "TV Bro and SmartTube (optimize/install.sh extras)"
else
  skip "TV Bro and SmartTube (you said no)"
fi
has com.surfshark.vpnclient.android && ok "Surfshark installed (sign-in and auto-connect are manual)" \
  || skip "Surfshark not installed: manual step 3"

refresh_pkgs
want_dock=$(for p in $DOCK_APPS; do has "$p" && echo "$p"; done | paste -sd, -)
have_dock=$(optimize/dock.sh show 2>/dev/null | tr -d '\r' | sed -n 's/^dock: //p' | head -1)
if [ -z "$DOCK_APPS" ]; then
  skip "favourites row: DOCK_APPS not set, left as it is (${have_dock:-the home screen default})"
elif [ -z "$want_dock" ]; then
  skip "favourites row: none of DOCK_APPS is installed"
elif [ "$want_dock" = "$have_dock" ]; then
  ok "favourites row: $have_dock"
elif [ "$MODE" = verify ]; then
  warn "favourites row is ${have_dock:-unknown}, not $want_dock"
elif ask "Set the favourites row to $want_dock (now: ${have_dock:-default})?" y; then
  runx optimize/dock.sh set ${want_dock//,/ } | sed 's/^/   /'
  did "favourites row: $want_dock"
else
  skip "favourites row kept: $have_dock"
fi

# ---------------------------------------------------------------------------------------------------------
step "Check"
refresh_pkgs
h=$(home_now); [[ "$h" == com.raad.sauditv.home/* ]] && ok "Home: $h" || warn "Home is $h, not com.raad.sauditv.home (run again; docs/SETUP.md)"
s=$(tvq "settings get secure screensaver_components")
[ "$s" = com.raad.sauditv.screensaver/.ScreenDream ] && ok "screensaver: $s" || warn "screensaver is $s"
for pair in "saudi-tv-home.apk com.raad.sauditv.home" "saudi-tv-screensaver.apk com.raad.sauditv.screensaver"; do
  set -- $pair
  v=$(tvq "dumpsys package $2 2>/dev/null | grep -m1 versionCode" | sed 's/^ *versionCode=\([0-9]*\).*/\1/')
  if up_to_date "$1" "$2"; then ok "$2 $v = ${APKS:+$APKS/$1}${APKS:-the latest release}"
  else warn "$2 (${v:-not installed}) is not ${APKS:+the build in $APKS/$1}${APKS:-the latest release (v${WANT_VER:-?})}"; fi
done
for list in optimize/lists/tcl-safe.txt "$EXTRA/disable.txt"; do
  on=(); off=0
  for p in $(list_pkgs "$list"); do has "$p" || continue; if is_off "$p"; then off=$((off + 1)); else on+=("$p"); fi; done
  if [ ${#on[@]} -eq 0 ]; then ok "$list: $off off, none on"
  elif [ "$MODE" = dry ]; then skip "$list: would turn off ${on[*]}"
  else warn "$list: still on: ${on[*]}"; fi
done
left=(); for p in $(list_pkgs "$EXTRA/remove.txt") $EXTRA_REMOVE; do has "$p" && left+=("$p"); done
[ ${#left[@]} -eq 0 ] && ok "$EXTRA/remove.txt and EXTRA_REMOVE: none installed" || skip "still installed (not removed): ${left[*]}"
optimize/saver.sh show 2>/dev/null | tr -d '\r' | grep -E '^(city|teams|panel|home_panel):' | sed 's/^/   /'
ok "$(tvq "grep MemAvailable /proc/meminfo" | tr -s ' ')"
[ "$MODE" = dry ] || tv "input keyevent KEYCODE_HOME" > /dev/null   # leave the TV on the home screen
summary
