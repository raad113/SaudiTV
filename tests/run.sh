#!/usr/bin/env bash
# Every check in one go: static checks, then the screen in a real browser. See tests/check.py and page.test.mjs.
#   pip install fonttools brotli pillow && npm install --no-save playwright@1.56.1 && npx playwright install chromium
set -euo pipefail
cd "$(dirname "$0")/.."
python3 tests/check.py
python3 tests/upload_test.py
python3 tests/find_tv_test.py
python3 tests/saver_sh_test.py   # optimize/saver.sh's radio commands, with a stand-in for the TV
python3 tests/java_test.py       # the settings app's plain-Java logic (a JDK: Android Studio's jbr)
node tests/page.test.mjs
