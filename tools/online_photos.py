#!/usr/bin/env python3
"""photos/online.json: the TVs' online background photos, built daily by .github/workflows/photos.yml (no keys).

    python -I tools/online_photos.py [--out photos/online.json] [--no-brightness]

  saudi  places of Saudi Arabia from Wikimedia Commons: the featured, quality and valued pictures of Saudi Arabia and
         the place categories of ROOTS (their subcategories to a limited depth). Kept: landscape at least 1.6:1 and
         1920 px wide; CC0, public domain, CC BY or CC BY-SA; no category or title about people or portraits; of the
         two holy mosques only wide exterior shots (HOLY). Each one's place in Arabic from the category it was found in.
  world  Commons' Picture of the Day of the last WORLD_DAYS days, the same rules.
  bing   Bing's photo of the day (its last 8), as Bing gives them.

Each entry: u the 1920 px thumbnail (a TV asks for its own width, PhotoSize.commonsWidth), page the file's page, a the
author (plain text), l the licence's short name, p the place in Arabic, b the average brightness 0..1 (of a 120 px
thumbnail; the page sets a stronger scrim on a bright one). At most MAX_SAUDI photos, in a fixed order (places in
turn), so the same answers give the same file and the Action commits only a real change.
"""
import argparse
import hashlib
import html
import io
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import date, timedelta
from pathlib import Path

# The Saudi (Wikimedia Commons) list. The user turned it off on 2026-10-25: for now the TVs show only our own photos
# (photos/, like the old Unsplash ones). Off, the script writes an empty saudi list; world and bing still go out (both
# stay off by default on the TVs). True puts the Commons search back as it was.
SAUDI = False

ROOT = Path(__file__).resolve().parents[1]
API = "https://commons.wikimedia.org/w/api.php"
UA = "SaudiTV-photos/1.0 (https://github.com/raad113/SaudiTV; daily background list) python-urllib"
MAX_SAUDI, MAX_PER_PLACE, MAX_WORLD, WORLD_DAYS = 300, 40, 30, 60
MIN_W, MIN_RATIO, MAX_RATIO, THUMB_W, PROBE_W = 1920, 1.45, 2.2, 1920, 120   # 3:2 is fine (the page crops to cover);
# wider than 2.2: a strip, blurred by cover
MIN_HIGH = 5   # a place with this many featured, quality or valued photos gets only those
MAX_HOLY = 3   # the photos of each holy place (Mecca, the Prophet's Mosque), at most
BLOCKLIST = Path(__file__).resolve().parent / "photo-blocklist.txt"
MAX_SERIES = 3   # photos of one series (one name but its number: Jeddah_Corniche_1 … _37), at most
MAX_CATS = 40   # subcategories read per root, at most

# (category, Arabic place, subcategory depth). The first root a file is found in names its place, so a place inside
# another comes before it (Meda'in Saleh is a subcategory of Al-'Ula).
ROOTS = [
    ("Meda'in Saleh", "مدائن صالح", 1),
    ("Qasr al-Farid", "مدائن صالح", 0),
    ("Al-'Ula", "العُلا", 1),
    ("Tuwaiq Escarpment", "حافة العالم", 0),
    ("The Edge of the World", "حافة العالم", 0),
    ("Rub' al Khali", "الربع الخالي", 1),
    ("Empty Quarter", "الربع الخالي", 0),
    ("Abha", "أبها", 1),
    ("Asir Province", "عسير", 1),
    ("Asir", "عسير", 1),
    ("Farasan", "جزر فرسان", 1),
    ("Umluj", "البحر الأحمر", 1),
    ("Jeddah Corniche", "البحر الأحمر", 0),
    ("Old Jeddah", "جدة التاريخية", 1),
    ("Al-Balad district", "جدة التاريخية", 1),
    ("Diriyah", "الدرعية", 1),
    ("Kingdom Centre (Riyadh)", "الرياض", 1),
    ("King Abdullah Financial District", "الرياض", 1),
    ("Views of Riyadh", "الرياض", 1),
    ("Night in Riyadh", "الرياض", 1),
    ("Al-Ahsa", "الأحساء", 1),
    ("Tabuk, Saudi Arabia", "تبوك", 1),
    ("Tabuk Province", "تبوك", 1),
    ("Neom", "نيوم", 0),
    ("Ta’if", "الطائف", 1),
    ("Najran", "نجران", 1),
    ("Najran Province", "نجران", 1),
    ("Ha'il", "حائل", 1),
    ("Jubbah, Saudi Arabia", "جبة", 0),
    ("Al Wahbah crater", "فوهة الوعبة", 0),
    ("Sarawat Mountains", "جبال السروات", 0),
    ("Al-Hada", "الطائف", 0),
    ("Mountains of Ta'if", "الطائف", 1),
    ("Skyscrapers in Riyadh", "الرياض", 1),
    ("Wadi Hanifa", "وادي حنيفة", 1),
    ("Al-Wajh", "الوجه", 0),
    ("Tayma", "تيماء", 1),
    ("Dumat al-Jandal", "دومة الجندل", 1),
    ("Views of Mecca", "مكة المكرمة", 1),
    # the two holy mosques: only their wide outside views (HOLY_OK)
    ("Bird's-eye views of al-Haram Mosque", "المسجد الحرام", 0),
    ("Exterior of Masjid al-Nabawi", "المسجد النبوي", 0),
    ("The Prophet's Mosque at night", "المسجد النبوي", 0),
    # the rest of them: only their featured or quality outside views (keep, HOLY_OK)
    ("Masjid al-Haram", "المسجد الحرام", 1),
    ("Al-Masjid an-Nabawi", "المسجد النبوي", 1),
]
HOLY_ROOTS = {"Bird's-eye views of al-Haram Mosque", "Exterior of Masjid al-Nabawi", "The Prophet's Mosque at night"}
# no place of their own: the place comes from the file's categories and title (KEYWORDS); none matched: skipped
QUALITY = ["Featured pictures of Saudi Arabia", "Quality images of Saudi Arabia", "Valued images of Saudi Arabia",
           "Landscapes of Saudi Arabia", "Mountains of Saudi Arabia", "Deserts of Saudi Arabia"]
KEYWORDS = [   # (lower-case words, Arabic place), the first that matches
    (("hegra", "mada'in", "meda'in", "madain", "qasr al-farid"), "مدائن صالح"),
    (("'ula", "alula", "al-ula", "el-ula"), "العُلا"),
    (("edge of the world", "tuwaiq"), "حافة العالم"),
    (("rub' al khali", "empty quarter"), "الربع الخالي"),
    (("farasan",), "جزر فرسان"),
    (("abha",), "أبها"), (("asir",), "عسير"),
    (("old jeddah", "al-balad", "historic jeddah"), "جدة التاريخية"),
    (("diriyah", "turaif"), "الدرعية"),
    (("riyadh", "kingdom centre", "kafd"), "الرياض"),
    (("ahsa", "hofuf"), "الأحساء"),
    (("neom",), "نيوم"), (("tabuk",), "تبوك"),
    (("ta'if", "ta’if", "taif"), "الطائف"),
    (("najran",), "نجران"),
    (("jubbah",), "جبة"), (("ha'il", "hail"), "حائل"),
    (("haram mosque", "masjid al-haram", "great mosque of mecca"), "المسجد الحرام"),
    (("nabawi", "prophet's mosque"), "المسجد النبوي"),
    (("mecca", "makkah"), "مكة المكرمة"),
    (("wajh",), "الوجه"), (("tayma",), "تيماء"), (("dumat",), "دومة الجندل"),
    (("sarawat",), "جبال السروات"), (("wahbah",), "فوهة الوعبة"),
    (("dahna",), "الدهناء"), (("nafud", "nefud"), "النفود"),
    (("jeddah",), "جدة"), (("red sea",), "البحر الأحمر"),
]
HOLY_PLACES = {"المسجد الحرام", "المسجد النبوي", "مكة المكرمة"}
HOLY = re.compile(r"haram mosque|masjid al-haram|great mosque of mecca|kaaba|ka'bah|nabawi|prophet's mosque", re.I)
HOLY_OK = re.compile(r"bird's-eye|aerial|exterior|skyline|panoram|from the|view of|views of|clock tower", re.I)
HOLY_NO = re.compile(r"interior|inside|courtyard|kaaba|ka'bah|tawaf|rawdah|mas'a|prayer|pilgrim|umrah|hajj|ihram|"
                     r"crowd|worshipp|close-up|closeup|detail", re.I)
PEOPLE = re.compile(r"\b(portraits?|people|persons?|men|women|children|girls|boys|selfies?|faces?|crowds?)\b|"
                    r"طفل|أطفال|رجل|رجال|امرأة|نساء|شخص|أشخاص", re.I)
# not a view of the place: maps, documents, art, objects, views from space, rooms, events (people, mostly), film
# stills, and the US Army Corps of Engineers' old slide scans (USACE, mostly people and faded colour)
NOT_PHOTO = re.compile(r"\b(usace|film|personnel|rall(y|ies)|"
                       r"maps?|plans?|diagrams?|documents?|manuscripts?|banknotes?|stamps?|logos?|flags?|banners?|"
                       r"inscriptions?|petroglyphs?|reliefs?|drawings?|paintings?|art|screenshots?|satellite|iss\d+|"
                       r"view of earth|specimens?|shells?|mollus\w*|fossils?|interiors?|indoor|museums?|airports?|"
                       r"stadiums?|markets?|campaign|launch\w*|conferences?|exhibitions?|festivals?|ceremon\w*|"
                       r"meetings?|meets|visits?|delegations?|signing|forum|summit|ministers?|secretary|models?|"
                       r"fruits?|food|apples?|figs?|pomegranates?|prickly pears?|flowers?|close-up|macro)\b|"
                       r"داخل|بطاقة|تذكرة|صناعة|تربية|ركوب|خريطة|شعار|متحجر", re.I)
SKIP_CAT = re.compile(r"\b(by (year|decade|user)|history|videos?|events?|sc|tour|people|portraits?|interior|"
                      r"maps?|plans?|art|inscriptions?|petroglyphs?|fauna|flora|animals?|plants?|birds?|insects?|"
                      r"mollus\w*|fossils?|museums?|airports?|stadiums?|education|companies)\b", re.I)


def licence(short):
    """Commons' LicenseShortName as kept here (CC0, PD, CC BY …, CC BY-SA …), else None: not one of ours"""
    s = " ".join(html.unescape(re.sub(r"<[^>]+>", "", short or "")).split())
    if re.match(r"(?i)^cc0\b", s):
        return "CC0"
    if re.match(r"(?i)^(public domain|pd\b)", s):
        return "PD"
    m = re.match(r"(?i)^cc[ -]by(-sa)?(?:[ -](\d\.\d))?(?:[ -]([a-z]{2,4}))?$", s)
    if m:
        return "CC BY" + ("-SA" if m.group(1) else "") + (" " + m.group(2) if m.group(2) else "")
    return None


def author(raw, user=""):
    """the Artist field without its HTML, short; else the uploader"""
    s = html.unescape(re.sub(r"<[^>]+>", " ", raw or ""))
    s = re.sub(r"\s+", " ", s).strip(" ,;-")
    s = re.sub(r"(?i)^(own work|unknown author)\b.*", "", s).strip() or s
    if not s or re.match(r"(?i)^(unknown|anonymous)", s):
        s = user or ""
    return s[:60].rstrip() + ("…" if len(s) > 60 else "")


def place_of(text):
    t = text.lower()
    for words, ar in KEYWORDS:
        if any(w in t for w in words):
            return ar
    return None


def keep(info, place):
    """why a file is left out (a short reason), or None to keep it"""
    ii = (info.get("imageinfo") or [{}])[0]
    title, cats = info.get("title", ""), " | ".join(c.get("title", "") if isinstance(c, dict) else c
                                                     for c in info.get("categories") or [])
    w, h = ii.get("width") or 0, ii.get("height") or 0
    if ii.get("mime") != "image/jpeg":   # PNGs here are mostly graphics, screenshots and scans
        return "not a photo"
    if w < MIN_W or not h or not MIN_RATIO <= w / h <= MAX_RATIO:
        return "size"
    meta = ii.get("extmetadata") or {}
    if not licence((meta.get("LicenseShortName") or {}).get("value")):
        return "licence"
    if PEOPLE.search(cats) or PEOPLE.search(title):
        return "people"
    if NOT_PHOTO.search(cats) or NOT_PHOTO.search(title):
        return "not a photo"
    text = title + " | " + cats
    if place in HOLY_PLACES or HOLY.search(text):   # Mecca and the two holy mosques: featured or quality, outside, wide
        if HOLY_NO.search(text) or not (HOLY_OK.search(text) or info.get("_root_holy")):
            return "holy mosque: not a wide outside view"
        if tier(info) > 1:
            return "holy mosque: not featured or quality"
    return None


def blocklist(path=None):
    """tools/photo-blocklist.txt: Commons file names left out (one a line, # comments), as File:Name with spaces.jpg"""
    try:
        lines = Path(path or BLOCKLIST).read_text(encoding="utf-8").splitlines()
    except OSError:
        return set()
    out = set()
    for line in lines:
        name = line.split("#", 1)[0].strip().replace("_", " ")
        if name:
            out.add(name if name.startswith("File:") else "File:" + name)
    return out


def entry(info, place):
    ii = info["imageinfo"][0]
    meta = ii.get("extmetadata") or {}
    return {
        "u": (ii.get("thumburl") or ii["url"]).split("?")[0],   # without Commons' ?utm_… tracking
        "page": ii.get("descriptionurl") or "https://commons.wikimedia.org/wiki/" + urllib.parse.quote(info["title"].replace(" ", "_")),
        "a": author((meta.get("Artist") or {}).get("value"), ii.get("user", "")),
        "l": licence((meta.get("LicenseShortName") or {}).get("value")),
        "p": place,
    }


def tier(info):
    cats = " ".join(c.get("title", "") if isinstance(c, dict) else c for c in info.get("_all_cats") or [])
    return 0 if "Featured pictures" in cats else 1 if "Quality images" in cats else 2 if "Valued images" in cats else 3


def series_of(title):
    """a file's series: its name without the extension, a trailing number, date or Flickr id, or "- panoramio\""""
    s = re.sub(r"\.[a-z0-9]+$", "", title.lower().removeprefix("file:"))
    s = re.sub(r"[\s_]*-[\s_]*panoramio$", "", s)
    while True:
        t = re.sub(r"[\s_,-]*(\(\d+\)|\d+)$", "", s).strip()
        if t == s:
            return s
        s = t


def order(found):
    """[(place, entry, tier, title)] -> the list: per place best first (featured, quality, valued, then the rest) then
    by title, only the first three tiers where a place has MIN_HIGH of them, at most MAX_PER_PLACE (MAX_HOLY for Mecca
    and the holy mosques); the places in turn in ROOTS order, at most MAX_SAUDI; the same input gives the same list"""
    places = []
    for _, ar, _ in ROOTS:
        if ar not in places:
            places.append(ar)
    for _, ar in KEYWORDS:
        if ar not in places:
            places.append(ar)
    by = {}
    for p in places:
        series, mine = {}, []
        xs = sorted([x for x in found if x[0] == p], key=lambda x: (x[2], x[3]))
        if sum(1 for x in xs if x[2] < 3) >= MIN_HIGH:
            xs = [x for x in xs if x[2] < 3]
        cap = MAX_HOLY if p in HOLY_PLACES else MAX_PER_PLACE
        for x in xs:
            k = series_of(x[3])
            series[k] = series.get(k, 0) + 1
            if series[k] <= MAX_SERIES and len(mine) < cap:
                mine.append(x)
        by[p] = mine
    out, i = [], 0
    while len(out) < MAX_SAUDI and any(i < len(v) for v in by.values()):
        for p in places:
            if i < len(by[p]) and len(out) < MAX_SAUDI:
                out.append(by[p][i][1])
        i += 1
    return out


# ---- the network (none of it in tests/online_photos_test.py)
CACHE = None   # --cache DIR: answers kept on disk (trying out the rules without asking Commons again)


def get(url, body=None, tries=4):
    """the answer to url (POSTing body, when given)"""
    if CACHE:
        f = CACHE / (hashlib.sha1((url + "\n" + (body or "")).encode()).hexdigest() + ".bin")
        if f.is_file():
            return f.read_bytes()
        CACHE.mkdir(parents=True, exist_ok=True)
        f.write_bytes(get_net(url, body, tries))
        return f.read_bytes()
    return get_net(url, body, tries)


def get_net(url, body, tries):
    for k in range(tries):
        time.sleep(0.2)   # gently: one request at a time
        try:
            req = urllib.request.Request(url, data=body.encode() if body is not None else None, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except Exception as e:
            if k == tries - 1:
                raise
            time.sleep(5 * (k + 1) if "429" in str(e) else 2)


def api(**p):
    """a query to Commons' API, POSTed (50 long titles do not fit in a URL)"""
    p.update(format="json", formatversion="2", maxlag="5")
    return json.loads(get(API, urllib.parse.urlencode(p)))


def api_all(**p):
    """every page of a query, merging the pages' lists (categories, imageinfo) across continuations"""
    pages, cont = {}, {}
    while True:
        r = api(**p, **cont)
        for pg in (r.get("query") or {}).get("pages") or []:
            old = pages.setdefault(pg.get("pageid") or pg.get("title"), {})
            for k, v in pg.items():
                if isinstance(v, list):
                    old.setdefault(k, []).extend(x for x in v if x not in old.get(k, []))
                else:
                    old.setdefault(k, v)
        for k in ("categorymembers",):
            for x in (r.get("query") or {}).get(k) or []:
                pages.setdefault(("m", x["title"]), x)
        if "continue" not in r:
            return list(pages.values())
        cont = r["continue"]


def redirect_target(cat):
    """a {{Category redirect|X}} category's X, else the category itself"""
    try:
        r = api(action="query", titles="Category:" + cat, prop="revisions", rvprop="content", rvslots="main")
        text = r["query"]["pages"][0]["revisions"][0]["slots"]["main"]["content"]
        m = re.search(r"\{\{\s*category redirect\s*\|\s*(?:1\s*=\s*)?([^|}]+)", text, re.I)
        return m.group(1).strip().removeprefix("Category:") if m else cat
    except Exception:
        return cat


def members(cat, kind):
    out = api_all(action="query", list="categorymembers", cmtitle="Category:" + cat, cmtype=kind, cmlimit="500")
    return [x["title"] for x in out if "title" in x and x.get("ns") == (14 if kind == "subcat" else 6)]


def files_under(cat, depth):
    cats, seen, files = [(redirect_target(cat), 0)], set(), []
    while cats and len(seen) < MAX_CATS:
        c, d = cats.pop(0)
        if c in seen:
            continue
        seen.add(c)
        files += members(c, "file")
        if d < depth:
            cats += [(s.removeprefix("Category:"), d + 1) for s in members(c, "subcat") if not SKIP_CAT.search(s)]
    return files


def infos(titles):
    out = {}
    for i in range(0, len(titles), 50):
        common = dict(action="query", titles="|".join(titles[i:i + 50]), redirects="1")
        got = api_all(**common, prop="imageinfo|categories", iiprop="url|size|mime|extmetadata|user",
                      iiurlwidth=str(THUMB_W), iiextmetadatafilter="Artist|LicenseShortName", clshow="!hidden",
                      cllimit="max")
        hidden = api_all(**common, prop="categories", clshow="hidden", cllimit="max")
        hid = {x.get("title"): x.get("categories") or [] for x in hidden}
        for x in got:
            if "title" in x:
                x["_all_cats"] = (x.get("categories") or []) + hid.get(x["title"], [])
                out[x["title"]] = x
    return out


def brightness(url, old):
    """the photo's average brightness 0..1 from a 120 px thumbnail; kept from the last list when it had one"""
    if url in old:
        return old[url]
    try:
        from PIL import Image, ImageStat
        small = re.sub(r"/(\d+)px-([^/]+)$", lambda m: f"/{PROBE_W}px-{m.group(2)}", url)
        im = Image.open(io.BytesIO(get(small))).convert("L")
        return round(ImageStat.Stat(im).mean[0] / 255, 2)
    except Exception:
        return None


def saudi():
    found, why, at = [], {}, {}
    for cat, ar, depth in ROOTS:
        for t in files_under(cat, depth):
            at.setdefault(t, (ar, cat))
    for cat in QUALITY:
        for t in files_under(cat, 1):
            at.setdefault(t, (None, cat))
    info, blocked = infos(sorted(at)), blocklist()
    for t, (ar, cat) in sorted(at.items()):
        x = info.get(t)
        if not x:
            continue
        if t in blocked:
            why.setdefault("blocklist", []).append(t)
            continue
        x["_root_holy"] = cat in HOLY_ROOTS
        names = t + " | " + " | ".join(c.get("title", "") for c in x.get("categories") or [])
        place = ar or place_of(names)
        reason = keep(x, place) if place else "no place"
        if reason:
            why.setdefault(reason, []).append(t)
            continue
        found.append((place, entry(x, place), tier(x), t))
    return order(found), why


def potd_file(text):
    """Template:Potd/<day>'s file: {{Potd filename|1= Name.jpg|2026|10|21}}, or the bare name (older days)"""
    m = re.search(r"\{\{\s*potd filename\s*\|\s*(?:1\s*=\s*)?([^|}\n]+)", text or "", re.I)
    name = (m.group(1) if m else text if text and "{" not in text else "").strip()
    return name or None


def world(today):
    days = [(today - timedelta(days=k)).isoformat() for k in range(WORLD_DAYS)]
    names = {}
    for i in range(0, len(days), 50):
        r = api_all(action="query", titles="|".join("Template:Potd/" + d for d in days[i:i + 50]), prop="revisions",
                    rvprop="content", rvslots="main")
        for pg in r:
            try:
                text = pg["revisions"][0]["slots"]["main"]["content"].strip()
            except Exception:
                continue
            name = potd_file(text)
            if name:
                names[pg["title"].rsplit("/", 1)[-1]] = "File:" + name
    info, blocked = infos(sorted(set(names.values()))), blocklist()
    out = []
    for d in sorted(names, reverse=True):
        x = info.get(names[d])
        if x and names[d] not in blocked and not keep(x, "") and len(out) < MAX_WORLD:
            e = entry(x, "")
            if e["u"] not in [y["u"] for y in out]:
                out.append(e)
    return out


def bing():
    j = json.loads(get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US"))
    out = []
    for im in j.get("images") or []:
        cr = im.get("copyright") or ""
        m = re.match(r"(.*?)\s*\(©\s*(.*?)\)\s*$", cr)
        out.append({"u": "https://www.bing.com" + im["urlbase"] + "_1920x1080.jpg",
                    "page": im.get("copyrightlink") or "https://www.bing.com/",
                    "a": (m.group(2) if m else cr)[:60], "l": "Bing", "p": (m.group(1) if m else "")[:60]})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "photos/online.json"))
    ap.add_argument("--no-brightness", action="store_true")
    ap.add_argument("--report", action="store_true", help="also print what was left out, by reason")
    ap.add_argument("--cache", help="keep the answers in this folder and use them again")
    a = ap.parse_args()
    global CACHE
    CACHE = Path(a.cache) if a.cache else None
    out = Path(a.out)
    old, prev = {}, {}
    try:
        prev = json.loads(out.read_text(encoding="utf-8"))
        old = {e["u"]: e["b"] for k in ("saudi", "world", "bing") for e in prev.get(k, []) if e.get("b") is not None}
    except Exception:
        pass
    s, why = saudi() if SAUDI else ([], {})
    lists = {"saudi": s}
    for name, fn in (("world", lambda: world(date.today())), ("bing", bing)):
        try:
            lists[name] = fn()
        except Exception as e:   # one source down: the others still go out (the last good list of this one stays)
            print(f"{name}: {e}", file=sys.stderr)
            lists[name] = prev.get(name) or []
    if SAUDI and not s:
        sys.exit("no Saudi photos found: the list is left as it was")
    for v in lists.values():
        for e in v:
            b = None if a.no_brightness else brightness(e["u"], old)
            if b is not None:
                e["b"] = b
    doc = {"v": 1, **lists}
    text = json.dumps(doc, ensure_ascii=False, indent=0, sort_keys=False) + "\n"
    out.write_text(text, encoding="utf-8")
    counts = {}
    for e in s:
        counts[e["p"]] = counts.get(e["p"], 0) + 1
    print(f"wrote {out} ({len(text.encode()) // 1024} KB): saudi {len(s)}, world {len(lists['world'])}, "
          f"bing {len(lists['bing'])}; sha1 {hashlib.sha1(text.encode()).hexdigest()[:10]}")
    for p, n in counts.items():
        print(f"  {p}: {n}")
    if a.report:
        for r, ts in sorted(why.items()):
            print(f"left out ({r}): {len(ts)}")
            for t in ts[:15]:
                print("   ", t)


if __name__ == "__main__":
    main()
