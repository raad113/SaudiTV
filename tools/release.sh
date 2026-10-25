#!/usr/bin/env bash
# Publish a new numbered release of both apps, the one the TVs' «تحديث التطبيق» and optimize/install.sh pick up:
#   1. raises VERSION (one more, or --version N),
#   2. builds the page and both APKs, signed with the project's release key (~/.sauditv/, never a debug key),
#   3. writes update.json: versionCode, versionName, the Arabic notes, and each APK's URL, sha256 and size,
#   4. runs the tests (tests/run.sh's six) and the old-name and private-files checks,
#   5. commits VERSION, update.json and dist/, tags v<N>, pushes, and creates the GitHub release with
#      saudi-tv-screensaver.apk, saudi-tv-home.apk and update.json; then checks what GitHub serves.
#
#   tools/release.sh --notes "نص قصير بالعربي"         the release notes (one short paragraph), or
#   tools/release.sh --notes-file notes.txt
#   tools/release.sh --dry-run --notes "…"             everything up to the tests; nothing committed or published,
#                                                      VERSION put back
#   tools/release.sh --version 9040 --notes "…"         a given number (must be higher than VERSION)
#
# Needs: a clean main that is up to date with GitHub, gh (signed in), the release key, Gradle 8.10 and JDK 17 (Android
# Studio's JBR), the Android SDK (android/local.properties or ANDROID_HOME), a Python 3 with fonttools, brotli and
# pillow, and node with playwright (tests/page.test.mjs).
# Environment: GRADLE, JAVA_HOME, PYTHON, CHROMIUM (a Chromium for the browser tests when Playwright's own is not
# installed), COMMIT_TRAILER (a last line for the release commit, e.g. "Co-Authored-By: …"), ALLOW_TEST_FAILURES=1
# (go on when tests fail: only for failures known to have nothing to do with this release; it says so loudly).
set -euo pipefail
cd "$(dirname "$0")/.."
REPO=raad113/SaudiTV
APKS=(saudi-tv-screensaver.apk saudi-tv-home.apk)
PKGS=(com.raad.sauditv.screensaver com.raad.sauditv.home)

die() { echo "!! $*" >&2; exit 1; }
say() { printf '\n== %s\n' "$*"; }

notes="" dry=no want=""
while [ $# -gt 0 ]; do
  case "$1" in
    --notes) notes="${2:?--notes needs a text}"; shift 2 ;;
    --notes-file) notes="$(cat "${2:?--notes-file needs a file}")"; shift 2 ;;
    --version) want="${2:?--version needs a number}"; shift 2 ;;
    --dry-run) dry=yes; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
    *) die "unknown option $1 (see tools/release.sh --help)" ;;
  esac
done
notes="$(printf '%s' "$notes" | tr -d '\r' | sed -e 's/[[:space:]]*$//')"
[ -n "$notes" ] || die "release notes are required: --notes \"…\" or --notes-file FILE (Arabic, one short paragraph)"
[ "${#notes}" -le 600 ] || die "the notes are ${#notes} characters: keep them to one short paragraph (600 at most)"

# ---------------------------------------------------------------- 0. preconditions
say "Checks before anything changes"
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || die "not on main"
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "uncommitted changes: commit them first (the release commit holds only VERSION, update.json and dist/)"
git fetch -q origin main
[ "$(git rev-list --count HEAD..origin/main)" = 0 ] || die "main is behind GitHub: git pull first"
old=$(tr -d '[:space:]' < VERSION)
new=${want:-$((old + 1))}
[[ "$new" =~ ^[0-9]+$ ]] && [ "$new" -gt "$old" ] || die "the new version ($new) must be a number above VERSION ($old)"
tag="v$new"
git rev-parse -q --verify "refs/tags/$tag" > /dev/null && die "tag $tag exists already"
if [ "$dry" = no ]; then
  gh auth status > /dev/null 2>&1 || die "gh is not signed in: gh auth login"
  gh release view "$tag" --repo "$REPO" > /dev/null 2>&1 && die "release $tag exists already on GitHub"
fi
signing="${SAUDITV_SIGNING:-$HOME/.sauditv/keystore.properties}"
[ -f "$signing" ] || die "no release key ($signing): a release must be signed with the project's key (docs/SETUP.md, signing)"

# tools
if [ -z "${PYTHON:-}" ]; then
  for p in python3 python; do
    if command -v "$p" > /dev/null && "$p" -c "import fontTools, PIL, brotli" 2> /dev/null; then PYTHON=$p; break; fi
  done
fi
[ -n "${PYTHON:-}" ] || die "no Python 3 with fonttools, brotli and pillow: pip install fonttools brotli pillow (or set PYTHON)"
export PYTHONIOENCODING=utf-8
GRADLE="${GRADLE:-$(command -v gradle || true)}"
[ -n "$GRADLE" ] || GRADLE=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.10*-bin/*/gradle-8.10*/bin/gradle 2> /dev/null | head -1 || true)
[ -n "$GRADLE" ] || die "no gradle: install Gradle 8.10, or set GRADLE"
jdk() { [ -n "$1" ] && { [ -x "$1/bin/javac" ] || [ -x "$1/bin/javac.exe" ]; }; }   # a JDK, not only a JRE
if ! jdk "${JAVA_HOME:-}"; then   # unset, or a JRE (Gradle needs javac): Android Studio's JBR
  JAVA_HOME=""
  for j in "/c/Program Files/Android/Android Studio/jbr" "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "$HOME/android-studio/jbr" "/opt/android-studio/jbr"; do
    if jdk "$j"; then JAVA_HOME="$j"; break; fi
  done
fi
[ -n "$JAVA_HOME" ] || die "no JDK 17: set JAVA_HOME to one (Android Studio's jbr)"
export JAVA_HOME
sdk="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' android/local.properties 2> /dev/null | sed 's/\\:/:/g; s/\\\\/\//g')}"
[ -d "$sdk/build-tools" ] || die "no Android SDK build-tools (android/local.properties sdk.dir or ANDROID_HOME)"
bt="$sdk/build-tools/$(ls "$sdk/build-tools" | grep -vi rc | sort -V | tail -1)"
apksigner() { "$JAVA_HOME/bin/java" -jar "$bt/lib/apksigner.jar" "$@"; }
aapt2() { "$bt/aapt2" "$@"; }

# from here on a failure puts VERSION and update.json back
restore() {
  if [ "${published:-no}" = no ]; then
    git checkout -q -- VERSION 2> /dev/null || true
    if git ls-files --error-unmatch update.json > /dev/null 2>&1; then git checkout -q -- update.json; else rm -f update.json; fi
  fi
}
stopped() {
  local rc=$?
  [ $rc -eq 0 ] && return
  if [ "${published:-no}" = no ]; then
    restore
    echo "!! stopped: VERSION is back at $old, nothing was committed or published" >&2
  else
    echo "!! stopped after the release commit ($tag): finish by hand (git push origin main $tag; gh release create …)" >&2
  fi
  exit $rc
}
trap stopped EXIT

# ---------------------------------------------------------------- 1. version
say "Version $old -> $new"
printf '%s\n' "$new" > VERSION

# ---------------------------------------------------------------- 2. build and sign
say "The page (src/build.py)"
"$PYTHON" -I src/build.py
say "Both APKs (Gradle, release key)"
log=$(mktemp)
( cd android && env -u VERSION_CODE -u GITHUB_RUN_NUMBER "$GRADLE" --no-daemon -q assembleRelease \
    -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease ) 2>&1 | tee "$log"
grep -q "DEBUG key" "$log" && die "Gradle signed with the DEBUG key: fix ~/.sauditv/keystore.properties"
rm -f "$log"
mkdir -p android/out
cp android/app/build/outputs/apk/release/app-release.apk android/out/saudi-tv-screensaver.apk
cp android/launcher/build/outputs/apk/release/launcher-release.apk android/out/saudi-tv-home.apk

say "What was built"
certs=()
for i in 0 1; do
  apk="android/out/${APKS[$i]}"
  badging=$(aapt2 dump badging "$apk" | head -1 | tr -d '\r')
  grep -q "name='${PKGS[$i]}' versionCode='$new' " <<< "$badging" || die "$apk: expected ${PKGS[$i]} $new, got: $badging"
  signer=$(apksigner verify --print-certs "$apk" | tr -d '\r')
  grep -qi "Android Debug" <<< "$signer" && die "$apk is signed with a debug key"
  certs[$i]=$(sed -n 's/.*certificate SHA-256 digest: //p' <<< "$signer" | head -1)
  [ -n "${certs[$i]}" ] || die "$apk: no signing certificate"
  echo "${APKS[$i]}: ${PKGS[$i]} $new, $(wc -c < "$apk" | tr -d ' ') bytes, certificate ${certs[$i]:0:16}…"
done
[ "${certs[0]}" = "${certs[1]}" ] || die "the two APKs are signed with different keys"

# ---------------------------------------------------------------- 3. update.json
say "update.json"
NOTES="$notes" "$PYTHON" -I - "$new" "$REPO" android/out "${APKS[@]}" <<'PY'
import hashlib, json, os, sys
sys.stdout.reconfigure(encoding="utf-8")   # -I ignores PYTHONIOENCODING; Windows' console code page has no Arabic
new, repo, out, *apks = sys.argv[1:]
pkgs = {"saudi-tv-screensaver.apk": "com.raad.sauditv.screensaver", "saudi-tv-home.apk": "com.raad.sauditv.home"}
entries = []
for name in apks:
    data = open(os.path.join(out, name), "rb").read()
    entries.append({"package": pkgs[name], "file": name, "versionCode": int(new),
                    "url": f"https://github.com/{repo}/releases/download/v{new}/{name}",
                    "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})
doc = {"versionCode": int(new), "versionName": new, "tag": f"v{new}", "notes": os.environ["NOTES"], "apks": entries}
with open("update.json", "w", encoding="utf-8", newline="\n") as f:
    json.dump(doc, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(json.dumps(doc, ensure_ascii=False, indent=2))
PY

# ---------------------------------------------------------------- 4. tests and checks
say "Tests"
failed=0
"$PYTHON" tests/check.py || failed=1
"$PYTHON" tests/upload_test.py || failed=1
"$PYTHON" tests/find_tv_test.py || failed=1
"$PYTHON" tests/saver_sh_test.py || failed=1
"$PYTHON" tests/java_test.py || failed=1
node tests/page.test.mjs || failed=1
if [ "$failed" = 1 ]; then
  [ "${ALLOW_TEST_FAILURES:-}" = 1 ] || die "tests failed (ALLOW_TEST_FAILURES=1 goes on anyway, only for failures unrelated to this release)"
  echo "!! TESTS FAILED, going on because ALLOW_TEST_FAILURES=1: say why in the release notes or fix them next"
fi

say "Old names and private files"
# the project's earlier names and links never come back; the README's credits line (the user's own) is the one exception
old_names='ur''tv|ts''hi|تلفزيون''ك'   # split, so this file holds none of them
hits=$( { git grep -n -I -i -E "$old_names" -- . || true; grep -n -i -E "$old_names" update.json || true; } \
        | grep -v -E '^README\.md:[0-9]+:- Based on ' || true)
[ -z "$hits" ] || die "old names found:
$hits"
private=$(git ls-files | grep -E '(^|/)household(-[^/]*)?\.env$|\.(jks|keystore|p12)$|keystore\.properties$|(^|/)adbkey' || true)
[ -z "$private" ] || die "private files are tracked: $private"
echo "none"

if [ "$dry" = yes ]; then
  say "Dry run: built and checked $tag, nothing committed or published"
  ls -la android/out/
  restore
  trap - EXIT
  exit 0
fi

# ---------------------------------------------------------------- 5. commit, tag, push, release
say "Commit, tag and push $tag"
git add VERSION update.json dist/
msg="Release $tag"$'\n\n'"$notes"
[ -n "${COMMIT_TRAILER:-}" ] && msg="$msg"$'\n\n'"$COMMIT_TRAILER"
git commit -q -m "$msg"
git tag -a "$tag" -m "$tag"
published=yes   # from here on nothing is put back: the commit exists
git push -q origin main
git push -q origin "$tag"

say "GitHub release $tag"
gh release create "$tag" android/out/saudi-tv-screensaver.apk android/out/saudi-tv-home.apk update.json \
  --repo "$REPO" --title "$tag" --notes "$notes" --latest --verify-tag

say "What GitHub serves now"
tmp=$(mktemp -d)
ok=no
for try in 1 2 3 4 5 6; do
  if curl -fsSL -o "$tmp/update.json" "https://github.com/$REPO/releases/latest/download/update.json" \
     && grep -q "\"versionCode\": $new," "$tmp/update.json"; then ok=yes; break; fi
  sleep 10
done
[ "$ok" = yes ] || die "releases/latest/download/update.json is not $tag yet: check https://github.com/$REPO/releases"
for name in "${APKS[@]}"; do
  curl -fsSL -o "$tmp/$name" "https://github.com/$REPO/releases/download/$tag/$name"
  want=$("$PYTHON" -I -c 'import json,sys; print([a["sha256"] for a in json.load(open(sys.argv[1], encoding="utf-8"))["apks"] if a["file"] == sys.argv[2]][0])' "$tmp/update.json" "$name")
  got=$("$PYTHON" -I -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$tmp/$name")
  [ "$want" = "$got" ] || die "$name on GitHub does not match update.json"
  echo "$name: sha256 matches"
done
rm -rf "$tmp"
trap - EXIT
say "Released $tag: https://github.com/$REPO/releases/tag/$tag"
echo "The TVs see it within 6 hours (settings: «تحديث التطبيق» checks now). From this PC: optimize/install.sh all"
