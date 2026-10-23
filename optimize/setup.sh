#!/usr/bin/env bash
# One-time setup: the pure-Python ADB client (no adb binary needed, works where macOS blocks adb).
# The APKs come from the GitHub releases, so no Android SDK is needed here. Windows: run it in Git Bash.
# Adapted from github.com/mihirgupta0900/tv-home (MIT).
set -euo pipefail
cd "$(dirname "$0")"
. ./python.sh
pick_python || exit 1
"${SYS_PY[@]}" -m venv venv
if [ ! -e venv/bin/python ] && [ -e venv/Scripts/python.exe ]; then
  # Windows puts it in venv\Scripts: every script calls optimize/venv/bin/python, so point that there
  mkdir -p venv/bin
  printf '#!/usr/bin/env bash\nexec "$(dirname "$0")/../Scripts/python.exe" "$@"\n' > venv/bin/python
  chmod +x venv/bin/python
fi
if ! venv/bin/python -m pip install -q --no-cache-dir -r requirements.txt; then
  echo "!! Couldn't install the Python packages into optimize/venv (pip's error is above). If it is a network"
  echo "   error, allow this terminal network access (pypi.org), then run optimize/setup.sh again."
  exit 1
fi
if ! venv/bin/python -c "import adb_shell" 2>/dev/null; then   # pip said yes: make sure tv.py will really start
  echo "!! The ADB package is still missing from optimize/venv. Delete the folder optimize/venv, then run"
  echo "   optimize/setup.sh again."
  exit 1
fi
echo "✓ Python ADB client in optimize/venv"
