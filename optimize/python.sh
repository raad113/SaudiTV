# Sourced by find-tv.sh and setup.sh: sets SYS_PY (an array) to a Python 3.8+ that really runs.
# On Windows `python`/`python3` can be the Microsoft Store shortcut that does nothing, and right after
# `winget install Python.Python.3.12` the terminal's PATH doesn't have the new Python yet, so its install
# folders are tried too, and the `py -3` launcher last.
pick_python() {
  local c la
  local -a cands=(python3 python)
  if command -v cygpath >/dev/null 2>&1; then   # Git Bash on Windows
    la=$(cygpath -u "${LOCALAPPDATA:-}" 2>/dev/null || true)
    for c in "$la"/Programs/Python/Python3*/python.exe "/c/Program Files/Python3"*/python.exe; do
      [ -f "$c" ] && cands+=("$c")
    done
  fi
  for c in "${cands[@]}"; do
    if "$c" -c "import sys; sys.exit(sys.version_info < (3, 8))" >/dev/null 2>&1; then SYS_PY=("$c"); return 0; fi
  done
  if py -3 -c "import sys; sys.exit(sys.version_info < (3, 8))" >/dev/null 2>&1; then SYS_PY=(py -3); return 0; fi
  echo "!! No working Python 3 here. Install it (Windows: winget install -e --id Python.Python.3.12), then run the"
  echo "   same command again: it is found without opening a new terminal."
  return 1
}
