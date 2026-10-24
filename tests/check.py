#!/usr/bin/env python3
"""Static checks for the whole repo: build, data files, photos, fonts, scripts, safety lists, private data.

    python3 tests/check.py          (needs: pip install fonttools brotli pillow)

Prints one line per check and exits non-zero if any failed. No network.
"""
import json
import py_compile
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
failures = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        failures.append(name)


def tracked(*globs):
    out = subprocess.run(["git", "ls-files", *globs], cwd=ROOT, capture_output=True, text=True).stdout.split()
    return [ROOT / f for f in out]


# ---- build
before = {n: (ROOT / "dist" / n).read_bytes() for n in ("saudi-tv.html", "tv.html") if (ROOT / "dist" / n).is_file()}
r = subprocess.run([sys.executable, "src/build.py"], cwd=ROOT, capture_output=True, text=True)
check("src/build.py runs", r.returncode == 0, r.stderr[-400:])
for page, limit in (("saudi-tv.html", 4_000_000), ("tv.html", 450_000)):
    html = (ROOT / "dist" / page).read_text(encoding="utf-8")
    left = sorted(set(re.findall(r"__[A-Z][A-Z_]+__", html)))
    check(f"{page}: every build token replaced", not left, left)
    check(f"{page}: size under {limit // 1000} KB", len(html.encode()) < limit, len(html.encode()))
stale = [n for n, b in before.items() if (ROOT / "dist" / n).read_bytes() != b]
check("dist/ matched a fresh build (run src/build.py after editing src/)", len(before) == 2 and not stale, stale)

# ---- data files
for f in tracked("*.json"):
    try:
        json.loads(f.read_text(encoding="utf-8"))
        ok, err = True, ""
    except ValueError as e:
        ok, err = False, e
    check(f"valid JSON: {f.relative_to(ROOT)}", ok, err)

# ---- photos: every listed photo exists, is 1920x1080, has a credit; nothing unlisted
index = json.loads((ROOT / "photos/index.json").read_text(encoding="utf-8"))
listed = {p["f"] for p in index}
on_disk = {p.name for p in (ROOT / "photos").glob("*.jpg")}
check("photos: every index entry has a file", listed <= on_disk, sorted(listed - on_disk))
check("photos: no file missing from the index", on_disk <= listed, sorted(on_disk - listed))
bad = [f for f in sorted(on_disk) if Image.open(ROOT / "photos" / f).size != (1920, 1080)]
check("photos: all 1920x1080", not bad, bad)
credits = (ROOT / "docs/PHOTO-CREDITS.md").read_text(encoding="utf-8")
check("photos: every photo credited in docs/PHOTO-CREDITS.md", all(f in credits for f in on_disk),
      [f for f in on_disk if f not in credits])
bundled = json.loads((ROOT / "assets/backgrounds/backgrounds.json").read_text(encoding="utf-8"))
check("bundled backgrounds exist", all((ROOT / "assets/backgrounds" / b["file"]).is_file() for b in bundled))

# ---- verse and hadith: only checked text, and every character has a glyph
page = (ROOT / "dist/tv.html").read_text(encoding="utf-8")
verses = json.loads(re.search(r"const VERSES = (\[.*?\]);", page, re.S).group(1))
hadith = json.loads(re.search(r"const HADITH = (\[.*?\]);", page, re.S).group(1))
check("verses present", len(verses) >= 200, len(verses))
check("hadith present", len(hadith) >= 200, len(hadith))
check("at least 500 verses and hadith together (a new one every day for over a year)", len(verses) + len(hadith) >= 500, len(verses) + len(hadith))
basmala = [v["surah"] for v in verses if v["surah"] != "سورة الفاتحة"
           and re.sub("[\u064B-\u065F\u0670]", "", v["text"]).startswith("بسم الله الرحمن الرحيم")]
check("no basmala in front of a verse (it is not part of the verse)", not basmala, basmala)
check("surah names keep آ (آل عمران)", all("سورة ال " not in v["surah"] for v in verses), sorted({v["surah"] for v in verses if "سورة ال " in v["surah"]}))
shown = [json.dumps(h["parts"], ensure_ascii=False) for h in hadith]
check("no verse or hadith twice", len({v["text"] for v in verses}) == len(verses) and len(set(shown)) == len(shown))
raw = json.loads((ROOT / "assets/shared/hadith.json").read_text(encoding="utf-8"))
sys.path.insert(0, str(ROOT / "src"))
from build import narration_parts, simple_parts, weak_in_tirmidhi, weak_by_scholars, HADITH_MAX_WORDS, ISNAD_MAX_WORDS   # noqa: E402
sys.path.insert(0, str(ROOT / ".github/tools"))
from verify_hadith import norm as plain   # noqa: E402  (the six-book check's own matching)
form = lambda h: json.dumps(simple_parts(narration_parts(h["full"]["text"])), ensure_ascii=False)
found = {form(h) for h in raw if h.get("found") and h.get("full")}
check("hadith on screen come from narrations found in the six books", set(shown) <= found, len(set(shown) - found))
full_text = {plain(h["full"]["text"]) for h in raw if h.get("full")}
spelled = lambda t: plain(t.replace("ﷺ", " صلى الله عليه وسلم "))   # ﷺ on screen is the book's «صلى الله عليه وسلم»
cut = [t[:40] for h in hadith for k, t in h["parts"] if k == "q" and not any(spelled(t) in f for f in full_text)]
check("his words on screen are the book's words, uncut", not cut, cut)
opener = lambda h: plain(h["parts"][0][1]).split()[1:2]
not_a_name = [plain(h["parts"][0][1])[:30] for h in hadith if h["parts"][0][0] != "n" or not h["parts"][0][1].startswith("عن ")
              or opener(h)[0] in ("رسول", "النبي", "لي", "لنا", "قال", "سمع", "يقول", "ابا", "ابو", "ابيه", "جده", "اذناي", "رجل", "انسا", "سعدا")]
check("each hadith starts from the companion («عن أبي هريرة …»)", not not_a_name, not_a_name)
check("each full narration holds the words it was found by",
      all(plain(h["text"]) in plain(h["full"]["text"]) for h in raw if h.get("full")),
      [h["text"] for h in raw if h.get("full") and plain(h["text"]) not in plain(h["full"]["text"])])
weak = {form(h) for h in raw if h.get("full") and weak_in_tirmidhi(h["full"]["text"])}
check("no hadith al-Tirmidhi grades only غريب/ضعيف, or as a companion's words, is shown", weak and not (weak & set(shown)), len(weak & set(shown)))
long_chain = {form(h) for h in raw if h.get("full") and (lambda w: w and w[0][0] == "n" and len(w[0][1].split()) > ISNAD_MAX_WORDS)(narration_parts(h["full"]["text"]))}
check(f"no hadith with an isnad over {ISNAD_MAX_WORDS} words is shown", not (long_chain & set(shown)), len(long_chain & set(shown)))
graded_weak = {form(h) for h in raw if h.get("full") and weak_by_scholars(h["full"].get("grades"))}
check("no hadith the scholars grade weak (Al-Albani first) is shown", graded_weak and not (graded_weak & set(shown)), len(graded_weak & set(shown)))
sunan = [h for h in raw if h.get("full") and h["full"]["book"] not in ("صحيح البخاري", "صحيح مسلم")]
check("every narration from the four Sunan carries the scholars' grades", all(h["full"].get("grades") for h in sunan),
      [h["text"][:30] for h in sunan if not h["full"].get("grades")])
check("every hadith on screen has the Prophet's words and fits the card",
      all(any(k == "q" for k, _ in h["parts"]) and sum(len(t.split()) for _, t in h["parts"]) <= HADITH_MAX_WORDS for h in hadith))
font = set(TTFont(ROOT / "assets/fonts/Thmanyah-Regular.woff2").getBestCmap())
marks = set(TTFont(ROOT / "assets/fonts/NotoNaskhArabic-Marks.woff2").getBestCmap())
text = "".join(v["text"] + v["surah"] for v in verses) + "".join(t for h in hadith for _, t in h["parts"]) \
    + "".join(h["source"] for h in hadith)
missing = sorted({c for c in text if not c.isspace() and ord(c) not in font and c not in "ﷺ﴿﴾"})   # those: NotoMarks
check("verse/hadith text: every character in Thmanyah", not missing, missing)
check("NotoMarks has ﷺ ﴿ ﴾", {0xFDFA, 0xFD3E, 0xFD3F} <= marks)
team_ar = json.loads((ROOT / "assets/shared/teams-ar.json").read_text(encoding="utf-8"))
names = "".join(v for v in (team_ar.values() if isinstance(team_ar, dict) else []) if isinstance(v, str))
missing = sorted({c for c in names if not c.isspace() and ord(c) not in font})
check("Arabic team names: every character in Thmanyah", not missing, missing)

# ---- update.json: what the apps' «تحديث التطبيق» reads (tools/release.sh writes it for each release)
upd = ROOT / "update.json"
if upd.is_file():
    u = json.loads(upd.read_text(encoding="utf-8"))
    version = int((ROOT / "VERSION").read_text().strip())
    apks = {a.get("package"): a for a in u.get("apks", [])}
    check("update.json: both apps", set(apks) == {"com.raad.sauditv.screensaver", "com.raad.sauditv.home"}, sorted(apks))
    check("update.json: versionCode not above VERSION", isinstance(u.get("versionCode"), int) and u["versionCode"] <= version,
          (u.get("versionCode"), version))
    tag = f"https://github.com/raad113/SaudiTV/releases/download/v{u.get('versionCode')}/"
    check("update.json: each APK from its own release, with sha256 and size",
          all(a.get("url", "").startswith(tag) and re.fullmatch(r"[0-9a-f]{64}", a.get("sha256", "")) and a.get("size", 0) > 0
              and a.get("versionCode") == u.get("versionCode") for a in apks.values()), list(apks.values()))
    check("update.json: Arabic notes", bool(re.search(r"[\u0600-\u06FF]", u.get("notes", ""))), u.get("notes"))

# ---- launcher config
cfg = ET.parse(ROOT / "android/launcher/src/main/res/values/config.xml").getroot()
arr = {a.get("name"): [i.text for i in a] for a in cfg.findall("string-array")}
check("launcher: no dock app is also hidden", not set(arr["dock_apps"]) & set(arr["hidden_apps"]))
check("launcher: screensaver app not hidden", "com.raad.sauditv.screensaver" not in arr["hidden_apps"])

# ---- optimize: never disable a kept package
def pkgs(path):
    return {l.split("#")[0].strip() for l in path.read_text().splitlines() if l.split("#")[0].strip()}
keep = pkgs(ROOT / "optimize/lists/keep.txt")
for lst in sorted((ROOT / "optimize/lists").glob("*.txt")):
    if lst.name != "keep.txt":
        check(f"optimize: {lst.name} disables nothing from keep.txt", not pkgs(lst) & keep, sorted(pkgs(lst) & keep))

# ---- scripts
for f in tracked("*.py"):
    try:
        py_compile.compile(str(f), doraise=True)
        ok, err = True, ""
    except py_compile.PyCompileError as e:
        ok, err = False, e
    check(f"python compiles: {f.relative_to(ROOT)}", ok, err)
BASH = shutil.which("bash") or "bash"   # PATH's bash (Git Bash on Windows), not System32's WSL one
for f in tracked("*.sh"):
    r = subprocess.run([BASH, "-n", str(f)], capture_output=True, text=True)
    check(f"bash syntax: {f.relative_to(ROOT)}", r.returncode == 0, r.stderr)

# ---- nothing private or secret in the repo
texts = [f for f in tracked() if f.suffix in {".md", ".py", ".sh", ".java", ".xml", ".json", ".yml", ".html", ".txt", ".gradle"}]
ip = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
allowed_ip = {"0.0.0.0", "127.0.0.1"}
# the network check and its simulation hold only address ranges and made-up addresses, never someone's network
network_code = {ROOT / "optimize/find-tv.py", ROOT / "tests/find_tv_test.py"}
found = []
for f in texts:
    if f.name.endswith(".html") or f.parent.name == "photos" or f == Path(__file__).resolve():   # not this scan
        continue
    if f in network_code:
        s = f.read_text(encoding="utf-8", errors="ignore")
        if re.search(r"(?i)tailscale|\.ts\.net", s):
            found.append(f"{f.relative_to(ROOT)}: tailscale")
        continue
    s = f.read_text(encoding="utf-8", errors="ignore")
    found += [f"{f.relative_to(ROOT)}: {m}" for m in set(ip.findall(s)) - allowed_ip
              if all(0 <= int(x) <= 255 for x in m.split(".")) and not m.startswith(("1.", "2.", "3.", "4."))
              and not m.startswith(("192.0.2.", "198.51.100.", "203.0.113."))]   # reserved for examples
    if re.search(r"(?i)(client-id|access_key|api_key)\s*[:=]\s*['\"][A-Za-z0-9_-]{20,}", s):
        found.append(f"{f.relative_to(ROOT)}: looks like a key")
    if re.search(r"(?i)tailscale|\.ts\.net", s):
        found.append(f"{f.relative_to(ROOT)}: tailscale")
check("no private IPs, keys or network names", not found, found[:10])

print()
print(f"{len(failures)} failed" if failures else "all checks passed")
sys.exit(1 if failures else 0)
