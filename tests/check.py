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
numbers = [n for n, _ in short]
check("hadith: over a year of narrations in the short form, each once", len(short) >= 366 and len(set(numbers)) == len(numbers), len(short))
check("hadith: each in the short form, the companion with his honorific, no chain of narrators",
      all(re.fullmatch(r"عن \S+(?: \S+){0,3} رضي الله (?:عنه|عنها|عنهما) (?:قالت?: )?\S+(?: \S+){1,4}:", h)
          and "ﷺ" in h and not re.search("حدثنا|أخبرنا|حدثني|أخبرني", h) for _, h in short), [h for _, h in short][:3])
heads = dict((n, h) for n, h in short)


def his_words(t):   # as the page's hadithMatn
    parts = t.split('"')
    if len(parts) != 3 or re.sub("[\\s.،,:‎‏]", "", parts[2]):
        return ""
    return " ".join(re.sub("[‎‏]", "", parts[1]).split()).strip(" .")


check("hadith: about 60 bundled for a TV offline, each from the pool with the same head, each once",
      50 <= len(fallback) <= 70 and all(heads.get(f["n"]) == f["head"] for f in fallback) and len({f["n"] for f in fallback}) == len(fallback), len(fallback))
check("hadith: each bundled text has his words between the book's quotes, at most maxWords words",
      all(his_words(f["text"]) and len(his_words(f["text"]).split()) <= bukhari["maxWords"] <= 30 for f in fallback))
check("no other hadith collection anywhere on the screen (the six books' hadith are gone)",
      "const HADITH = " not in page and not re.search("صحيح مسلم|سنن الترمذي|سنن أبي داود|سنن النسائي|سنن ابن ماجه|جامع الترمذي", page))
font = set(TTFont(ROOT / "assets/fonts/Thmanyah-Regular.woff2").getBestCmap())
marks = set(TTFont(ROOT / "assets/fonts/NotoNaskhArabic-Marks.woff2").getBestCmap())
saw = re.compile(r"\s+".join("[ً-ْٰ]*".join(w) + "[ً-ْٰ]*" for w in "صلى الله عليه وسلم".split()))
text = "".join(v["text"] + v["surah"] for v in verses) + "".join(f["head"] + saw.sub("ﷺ", his_words(f["text"])) for f in fallback) \
    + "".join(h for _, h in short)
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
# the other Arab countries' news («أخبار <الدولة>»): the page's COUNTRY_NEWS and Prefs.COUNTRY_NEWS
cblock = re.search(r"const COUNTRY_NEWS = \{(.*?)\n\};", tmpl, re.S).group(1)
page_cn = {m.group(1): [(i.group(1), "on: true" in i.group(0)) for i in re.finditer(r"\{id: '(\w+)'.*?\}", m.group(2))]
           for m in re.finditer(r"\n  ([A-Z]{2}): \[(.*?)\n  \],", cblock, re.S)}
jblock = re.search(r"COUNTRY_NEWS = \{(.*?)\n    \};", prefs, re.S).group(1)
java_cn = {}
for chunk in jblock.split('{{"')[1:]:
    java_cn[chunk[:2]] = [(i, d == "1") for i, _, d in re.findall(r'\{"(\w+)", "([^"]+)", "([01])"\}', chunk)]
check("country news: the page (COUNTRY_NEWS) and the settings app (Prefs.COUNTRY_NEWS) have the same countries, ids, order and defaults",
      page_cn == java_cn and len(page_cn) >= 17, (sorted(set(page_cn) ^ set(java_cn)), [k for k in page_cn if page_cn.get(k) != java_cn.get(k)]))
check("country news: each country 2 to 4 outlets on by default, no id twice, no google/bing of their own (added for every country)",
      all(2 <= sum(d for _, d in v) <= 4 and len({i for i, _ in v}) == len(v) and not {"google", "bing"} & {i for i, _ in v}
          for v in java_cn.values()), {k: [i for i, d in v if d] for k, v in java_cn.items()})

# ---- worldwide: the bundled countries and cities (.github/tools/gen_geo.py), the prayer methods, the leagues
import gzip
geo = ROOT / "assets/geo"
rows = [l.split("\t") for l in (geo / "countries.tsv").read_text(encoding="utf-8").splitlines() if l and not l.startswith("#")]
codes = [r[0] for r in rows]
check("geo: every country has its code, Arabic and English names, and its cities file", len(rows) >= 230 and all(
      len(r) == 7 and re.fullmatch(r"[A-Z]{2}", r[0]) and re.search("[؀-ۿ]", r[1]) and r[3] and (geo / "cities" / f"{r[0]}.tsv.gz").is_file()
      for r in rows) and {"SA", "EG", "US", "AE", "GB"} <= set(codes), len(rows))
# the ticker tape follows the country (tapeList): every bundled country has its currency or is on the dollar, each once;
# every index is a bundled country's; the Saudi tape (also the one when no country is set) is as it was
tcur = re.search(r"const TAPE_CUR = Object\.fromEntries\(Object\.entries\(\{(.*?)\n\}\)", tmpl, re.S).group(1)
tcur_cc = [cc for _, ccs in re.findall(r"([A-Z]{3}):'([A-Z ]+)'", tcur) for cc in ccs.split()]
tusd = re.search(r"const TAPE_USD = '([A-Z ]+)'", tmpl).group(1).split()
tidx = re.findall(r"\b([A-Z]{2}): \['[^']+', '[^']+'\]", re.search(r"const TAPE_INDEX = \{(.*?)\n\};", tmpl, re.S).group(1))
check("ticker tape: every bundled country has its currency (TAPE_CUR) or is on the dollar (TAPE_USD), each once",
      sorted(tcur_cc + tusd) == sorted(codes), (sorted(set(codes) - set(tcur_cc + tusd)), sorted(set(tcur_cc + tusd) - set(codes))))
check("ticker tape: every index (TAPE_INDEX) is a bundled country's, Saudi Arabia's is TASI",
      len(tidx) >= 30 and set(tidx) <= set(codes) and "SA: ['تاسي', '^TASI.SR']" in tmpl, sorted(set(tidx) - set(codes)))
tsa = re.search(r"const TAPE_SA = \[\n(.*?)\n\];", tmpl, re.S).group(1)
check("ticker tape: the Saudi tape is as before (TASI first, the dollar in riyals, the same nine markets in order)",
      re.findall(r"\{name:'([^']+)', +(?:sym|cg):'([^']+)'", tsa) == [
          ("تاسي", "^TASI.SR"), ("S&P 500", "^GSPC"), ("ناسداك", "^IXIC"), ("داو جونز", "^DJI"), ("الذهب", "GC=F"),
          ("برنت", "BZ=F"), ("الدولار/الريال", "SAR=X"), ("بيتكوين", "bitcoin"), ("إيثريوم", "ethereum")], tsa)
check("ticker tape: no API key in its sources (Yahoo, CoinGecko, open.er-api.com)",
      not re.search(r"apikey|api_key|access_key|appid=", tmpl, re.I) and "open.er-api.com/v6/latest/USD'" in tmpl)
bad = []
for cc in ("SA", "EG", "US", "MA"):
    lines = gzip.decompress((geo / "cities" / f"{cc}.tsv.gz").read_bytes()).decode("utf-8").splitlines()
    zones, regions = lines[0].split("\t")[1:], lines[1].split("\t")[1:]
    for l in lines[2:]:
        f = l.split("\t")
        if len(f) != 7 or not (-90 <= float(f[2]) <= 90 and -180 <= float(f[3]) <= 180) or int(f[5]) >= len(zones) or int(f[6]) >= len(regions):
            bad.append(f"{cc}: {l}")
    pops = [int(l.split("\t")[4]) for l in lines[2:]]
    if pops != sorted(pops, reverse=True):
        bad.append(f"{cc}: not biggest first")
check("geo: city files well formed (name, English, lat, lon, population, zone, region), biggest first", not bad, bad[:3])
# «اكتب اسم مدينتك»: names.tsv.gz lists every city of every country file once, biggest first, each pointing at its line
per_cc = {}
for f in (geo / "cities").glob("*.tsv.gz"):
    per_cc[f.name[:2]] = gzip.decompress(f.read_bytes()).decode("utf-8").splitlines()[2:]
idx = [l.split("\t") for l in gzip.decompress((geo / "names.tsv.gz").read_bytes()).decode("utf-8").splitlines()]
bad = [r for r in idx if len(r) != 4 or r[2] not in per_cc or not r[3].isdigit() or int(r[3]) >= len(per_cc[r[2]])
       or per_cc[r[2]][int(r[3])].split("\t")[:2] != r[:2]]
pops = [int(per_cc[r[2]][int(r[3])].split("\t")[4]) for r in idx if r not in bad]
check("geo: names.tsv.gz has every city once, biggest first, each at its line of its country's file (gen_geo.py --index)",
      not bad and len(idx) == sum(len(v) for v in per_cc.values()) == len({(r[2], r[3]) for r in idx}) and pops == sorted(pops, reverse=True),
      (len(idx), sum(len(v) for v in per_cc.values()), bad[:3]))
check("geo: names.tsv.gz stays under 1.6 MB", (geo / "names.tsv.gz").stat().st_size < 1_600_000, (geo / "names.tsv.gz").stat().st_size)
size = sum(f.stat().st_size for f in (geo / "cities").glob("*.tsv.gz"))
check("geo: the city files stay under 3 MB (they are in the screensaver APK)", size < 3_000_000, size)
geo_java = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/Geo.java").read_text(encoding="utf-8")
java_methods = sorted(int(x) for x in re.findall(r'\{"(\d+)", "[^"]+", "[a-z-]+"\}', re.search(r"METHODS = \{(.*?)\};", geo_java, re.S).group(1)))
page_methods = sorted(int(x) for x in re.findall(r"\n  (\d+): \{", re.search(r"const PRAYER_METHODS = \{(.*?)\n\};", tmpl, re.S).group(1)))
check("prayer methods: the settings app (Geo.METHODS) and the page's offline times (PRAYER_METHODS) have the same ones",
      java_methods == page_methods and {1, 2, 3, 4, 5, 9, 10, 13, 16} <= set(page_methods), (java_methods, page_methods))
lg = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/Leagues.java").read_text(encoding="utf-8")
ids = re.findall(r'l\("([^"]+)", "', lg) + (["ksa.1"] if "l(ROSHN, " in lg and 'ROSHN = "ksa.1"' in lg else [])
tsdb_named = set(re.findall(r'\{"(tsdb\.\d+)", "[^"]+"\}', lg))
check("leagues: ids are ESPN slugs or tsdb.<id>, each once; every TheSportsDB one has its TheSportsDB name",
      len(ids) == len(set(ids)) and all(re.fullmatch(r"tsdb\.\d+|[a-z]+(\.[a-z0-9_]+)+", i) for i in ids) and "ksa.1" in ids
      and {i for i in ids if i.startswith("tsdb.")} == tsdb_named, [i for i in ids if ids.count(i) > 1])
readme = (ROOT / "README.md").read_text(encoding="utf-8")
check("README credits GeoNames (CC BY 4.0) and CLDR", "GeoNames" in readme and "CC BY 4.0" in readme and "CLDR" in readme)

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
