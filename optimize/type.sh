#!/usr/bin/env bash
# Type text into whatever field is focused on the TV (open the on-screen keyboard first).
#
#   optimize/type.sh "hello world"        type the given text
#   optimize/type.sh                      prompt for the text, hidden (for passwords; stays out of shell history)
#   optimize/type.sh --enter "query"      press Enter afterwards
#   optimize/type.sh --dry-run "a b"      print the ADB command instead of sending it
set -euo pipefail
cd "$(dirname "$0")/.."
PY="${PY:-optimize/venv/bin/python}"

enter=0 dry=0
while [ $# -gt 0 ]; do
  case "$1" in
    --enter) enter=1; shift ;;
    --dry-run) dry=1; shift ;;
    *) break ;;
  esac
done

if [ $# -gt 0 ]; then
  text="$*"
else
  read -r -s -p "Text to type on the TV (hidden): " text; echo
fi
[ -n "$text" ] || { echo "nothing to type"; exit 1; }

# `input text` turns %s into a space and treats a real space as an argument break,
# so spaces become %s; wrap in single quotes for the TV's shell, escaping any ' inside.
esc=${text//\'/\'\\\'\'}
esc=${esc// /%s}
cmds="input text '$esc'"
[ $enter = 1 ] && cmds="$cmds"$'\n'"input keyevent KEYCODE_ENTER"

if [ $dry = 1 ]; then echo "$cmds"; exit 0; fi
printf '%s\n' "$cmds" | "$PY" -I optimize/tv.py --batch >/dev/null
echo "typed ${#text} characters"
