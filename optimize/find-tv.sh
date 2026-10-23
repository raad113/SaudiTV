#!/usr/bin/env bash
# Find the TV on this computer's Wi-Fi and save it to optimize/.tv_host (see optimize/find-tv.py, which runs on
# Windows, macOS and Linux).   optimize/find-tv.sh --check   first tells whether this terminal can reach the network.
cd "$(dirname "$0")/.."
. optimize/python.sh
pick_python || exit 1
exec "${SYS_PY[@]}" -I optimize/find-tv.py "$@"
