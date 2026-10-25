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

# ---- the verse of the day (the Quran) and the hadith of the day (Sahih al-Bukhari only): fetched text, every
# character has a glyph
page = (ROOT / "dist/tv.html").read_text(encoding="utf-8")
verses = json.loads(re.search(r"const VERSES = (\[.*?\]);", page, re.S).group(1))
bukhari = json.loads(re.search(r"const BUKHARI = (\{.*?\});\n", page, re.S).group(1))
check("verses present", len(verses) >= 200, len(verses))
basmala = [v["surah"] for v in verses if v["surah"] != "سورة الفاتحة"
           and re.sub("[ً-ٰٟ]", "", v["text"]).startswith("بسم الله الرحمن الرحيم")]
check("no basmala in front of a verse (it is not part of the verse)", not basmala, basmala)
check("surah names keep آ (آل عمران)", all("سورة ال " not in v["surah"] for v in verses), sorted({v["surah"] for v in verses if "سورة ال " in v["surah"]}))
check("no verse twice", len({v["text"] for v in verses}) == len(verses))
src = json.loads((ROOT / "assets/shared/bukhari.json").read_text(encoding="utf-8"))
check("hadith: Sahih al-Bukhari only, the ara-bukhari edition of fawazahmed0/hadith-api (.github/tools/fetch_bukhari.py)",
      "fawazahmed0/hadith-api" in src.get("source", "") and "/ara-bukhari" in src["source"] and src.get("book") == "صحيح البخاري", src.get("source"))
check("hadith: the page has the file's short list and bundled texts", bukhari == {k: src[k] for k in ("maxWords", "short", "fallback")})
short, fallback = bukhari["short"], bukhari["fallback"]
check("hadith: over a year of short narrations, each once", len(short) >= 366 and len(set(short)) == len(short), len(short))
check("hadith: about 60 bundled for a TV offline, each from the short list, each once",
      50 <= len(fallback) <= 70 and all(f["n"] in short for f in fallback) and len({f["n"] for f in fallback}) == len(fallback), len(fallback))
check("hadith: each bundled text the Prophet's words ﷺ between the book's quotes, at most maxWords words",
      all(f["text"].count('"') == 2 and "صلى الله عليه وسلم" in re.sub("[ً-ْٰ]", "", f["text"])
          and len(f["text"].split()) <= bukhari["maxWords"] <= 45 for f in fallback))
check("no other hadith collection anywhere on the screen (the six books' hadith are gone)",
      "const HADITH = " not in page and not re.search("صحيح مسلم|سنن الترمذي|سنن أبي داود|سنن النسائي|سنن ابن ماجه|جامع الترمذي", page))
font = set(TTFont(ROOT / "assets/fonts/Thmanyah-Regular.woff2").getBestCmap())
marks = set(TTFont(ROOT / "assets/fonts/NotoNaskhArabic-Marks.woff2").getBestCmap())
saw = re.compile(r"\s+".join("[ً-ْٰ]*".join(w) + "[ً-ْٰ]*" for w in "صلى الله عليه وسلم".split()))
text = "".join(v["text"] + v["surah"] for v in verses) + "".join(saw.sub("ﷺ", re.sub("[‎‏\"]", "", f["text"])) for f in fallback)
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

# ---- the cards and the news sources: the page, the settings app and the launcher list the same ones
tmpl = (ROOT / "src/template.html").read_text(encoding="utf-8")
prefs = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/Prefs.java").read_text(encoding="utf-8")
home = (ROOT / "android/launcher/src/main/java/com/raad/sauditv/home/HomePage.java").read_text(encoding="utf-8")
page_panels = re.findall(r"'(\w+)'", re.search(r"const PANEL_IDS = \[(.*?)\];", tmpl).group(1))
prefs_panels = [x for x in re.findall(r'\{"(\w+)", "', re.search(r"static final String\[\]\[\] PANELS = \{(.*?)\n    \};", prefs, re.S).group(1)) if x != "none"]
home_panels = re.findall(r'"(\w+)"', re.search(r"PANELS = Arrays\.asList\((.*?)\);", home, re.S).group(1))
check("cards: the page, the settings app (Prefs.PANELS) and the launcher (HomePage.PANELS) list the same ones",
      sorted(page_panels) == sorted(prefs_panels) == sorted(home_panels), (page_panels, prefs_panels, home_panels))
block = re.search(r"const NEWS_SOURCES = \{(.*?)\n\};", tmpl, re.S).group(1)
page_src = {}
for m in re.finditer(r"\n  (\w+): \[(.*?)\n  \],", block, re.S):
    page_src[m.group(1)] = [(i.group(1), "on: true" in i.group(0)) for i in re.finditer(r"\{id: '(\w+)'.*?\}", m.group(2))]
pages = re.findall(r'"(\w+)"', re.search(r"NEWS_PAGES = \{(.*?)\};", prefs).group(1))
java_blocks = re.findall(r"\{   // .*?\n(.*?)\n        \},", re.search(r"NEWS_SOURCES = \{(.*?)\n    \};", prefs, re.S).group(1), re.S)
java_src = {pg: [(i, d == "1") for i, _, d in re.findall(r'\{"(\w+)", "([^"]+)", "([01])"\}', b)] for pg, b in zip(pages, java_blocks)}
check("news sources: the page (NEWS_SOURCES) and the settings app (Prefs.NEWS_SOURCES) have the same ids, order and defaults",
      page_src == java_src and set(pages) == {"news", "tech", "local", "world"}, (page_src, java_src))
check("news sources: 3 or 4 on by default on every page", all(3 <= sum(d for _, d in v) <= 4 for v in java_src.values()),
      {k: sum(d for _, d in v) for k, v in java_src.items()})

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
