#!/usr/bin/env python3
"""Generate the settings app's bundled countries and cities (assets/geo/) from GeoNames and CLDR. Run by hand.

    python3 .github/tools/gen_geo.py --fetch DIR     download the sources into DIR (an empty folder), then generate
    python3 .github/tools/gen_geo.py DIR             generate from sources already in DIR
    python3 .github/tools/gen_geo.py --index         only assets/geo/names.tsv.gz, from the city files already there

Sources (no keys):
  GeoNames (CC BY 4.0, https://www.geonames.org): countryInfo.txt, cities1000.zip (every place of 1,000 people or
    more), admin1CodesASCII.txt (their regions) and alternateNamesV2.zip (the Arabic names), from
    https://download.geonames.org/export/dump/
  CLDR (Unicode license, https://cldr.unicode.org): the Arabic and English country names, cldr-localenames-full on npm

Writes:
  assets/geo/countries.tsv     one line per country: code, Arabic name (CLDR), short Arabic name (for «أخبار …»),
                               English name, continent, main language, number of cities; sorted by the Arabic name
  assets/geo/cities/XX.tsv.gz  one file per country (gzip): a line «#tz<TAB>zone<TAB>zone…», a line
                               «#region<TAB>name<TAB>name…» (the regions, Arabic when GeoNames has it), then one line
                               per city, biggest first: name (Arabic when GeoNames has one, else English), the English
                               name when the first is Arabic, latitude, longitude, population, time zone and region
                               (their index in the first two lines)
  assets/geo/names.tsv.gz      every city of every country in one file (gzip), biggest first, for «اكتب اسم مدينتك»:
                               name, English name (as in its country's file), country code and its line in the
                               country's file (0 = the first city), where the rest of the place is
"""
import gzip
import io
import json
import re
import sys
import unicodedata
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "assets" / "geo"
MIN_POP = 1000
CLDR = "https://cdn.jsdelivr.net/npm/cldr-localenames-full@46.0.0/main/{}/territories.json"
GEONAMES = "https://download.geonames.org/export/dump/"
SOURCES = {
    "countryInfo.txt": GEONAMES + "countryInfo.txt",
    "cities1000.zip": GEONAMES + "cities1000.zip",
    "alternateNamesV2.zip": GEONAMES + "alternateNamesV2.zip",
    "admin1CodesASCII.txt": GEONAMES + "admin1CodesASCII.txt",
    "ar-territories.json": CLDR.format("ar"),
    "en-territories.json": CLDR.format("en"),
}
# places that are not towns of their own: a section of a city, historical, abandoned, destroyed
SKIP_CODES = {"PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH"}
# the short name used in titles («أخبار السعودية»); CLDR's own short form ("-alt-short") otherwise, else its name
SHORT_AR = {
    "SA": "السعودية", "AE": "الإمارات", "PS": "فلسطين", "US": "الولايات المتحدة", "GB": "المملكة المتحدة",
    "KR": "كوريا الجنوبية", "KP": "كوريا الشمالية", "CD": "الكونغو الديمقراطية", "CG": "الكونغو",
    "BA": "البوسنة والهرسك", "VA": "الفاتيكان", "HK": "هونغ كونغ", "MO": "ماكاو",
}
ARTICLE = re.compile("^ال")


def fetch(dest):
    dest.mkdir(parents=True, exist_ok=True)
    for name, url in SOURCES.items():
        print("download", url)
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "SaudiTV gen_geo"}), timeout=600) as r:
            (dest / name).write_bytes(r.read())


def sort_key(ar):
    """Arabic order without the article (السعودية under س), alif forms together"""
    s = ARTICLE.sub("", ar)
    s = re.sub("[أإآ]", "ا", s)
    return unicodedata.normalize("NFKD", s)


def clean(name):
    """one line, no bracketed note; Arabic without harakat or tatweel, and ٱ as ا (the fonts have no glyph)"""
    name = re.sub("[ً-ٰٟـ]", "", name).replace("ٱ", "ا")
    return " ".join(re.sub(r"\s*\([^)]*\)\s*", " ", name).split()).replace("	", " ")


def main(src):
    ar_t = json.loads((src / "ar-territories.json").read_text(encoding="utf-8"))["main"]["ar"]["localeDisplayNames"]["territories"]
    en_t = json.loads((src / "en-territories.json").read_text(encoding="utf-8"))["main"]["en"]["localeDisplayNames"]["territories"]
    countries = {}
    for line in (src / "countryInfo.txt").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        f = line.split("\t")
        cc = f[0]
        if cc not in ar_t:
            continue
        lang = (f[15].split(",")[0].split("-")[0] or "en").lower() if len(f) > 15 else "en"
        countries[cc] = {"ar": ar_t[cc], "short": SHORT_AR.get(cc) or ar_t.get(cc + "-alt-short") or ar_t[cc],
                         "en": en_t.get(cc) or f[4], "continent": f[8], "lang": lang}

    cities = {}   # geonameid -> [name, ascii english, lat, lon, pop, tz, cc]
    with zipfile.ZipFile(src / "cities1000.zip") as z, z.open("cities1000.txt") as fh:
        for raw in io.TextIOWrapper(fh, encoding="utf-8"):
            f = raw.rstrip("\n").split("\t")
            if len(f) < 18 or f[6] != "P" or f[7] in SKIP_CODES or f[8] not in countries:
                continue
            pop = int(f[14] or 0)
            if pop < MIN_POP or not f[17]:
                continue
            cities[int(f[0])] = {"en": clean(f[1]), "lat": float(f[4]), "lon": float(f[5]), "pop": pop, "tz": f[17], "cc": f[8], "adm": f[10]}
    print(len(cities), "cities of", MIN_POP, "people or more")

    admins = {}   # "US.CA" -> [English name, geonameid]
    for line in (src / "admin1CodesASCII.txt").read_text(encoding="utf-8").splitlines():
        f = line.split("	")
        if len(f) >= 4 and f[3].isdigit():
            admins[f[0]] = [clean(f[1]), int(f[3])]
    admin_ids = {v[1] for v in admins.values()}

    best = {}   # geonameid -> (rank, Arabic name): preferred first, then short, then the first plain one
    with zipfile.ZipFile(src / "alternateNamesV2.zip") as z, z.open("alternateNamesV2.txt") as fh:
        for raw in io.TextIOWrapper(fh, encoding="utf-8"):
            f = raw.rstrip("\n").split("\t")
            if len(f) < 8 or f[2] != "ar":
                continue
            gid = int(f[1])
            if (gid not in cities and gid not in admin_ids) or f[6] == "1" or f[7] == "1":   # colloquial, historic
                continue
            name = clean(f[3])
            if not re.search("[؀-ۿ]", name):
                continue
            rank = 0 if f[4] == "1" else 1 if f[5] == "1" else 2
            if gid not in best or rank < best[gid][0]:
                best[gid] = (rank, name)
    print(len(best), "with an Arabic name")

    per = {}
    for gid, c in cities.items():
        per.setdefault(c["cc"], []).append((gid, c))
    (OUT / "cities").mkdir(parents=True, exist_ok=True)
    for old in (OUT / "cities").glob("*.tsv.gz"):
        old.unlink()
    total = 0
    for cc, lst in per.items():
        lst.sort(key=lambda x: (-x[1]["pop"], x[1]["en"]))
        zones = []
        regions = []   # the cities' regions (GeoNames admin1), in Arabic when it has one
        lines = []
        seen = set()
        for gid, c in lst:
            ar = best.get(gid, (9, ""))[1]
            name, alt = (ar, c["en"] if c["en"] != ar else "") if ar else (c["en"], "")
            key = (name, round(c["lat"], 2), round(c["lon"], 2))
            if key in seen:
                continue
            seen.add(key)
            if c["tz"] not in zones:
                zones.append(c["tz"])
            adm = admins.get(cc + "." + c["adm"])
            region = (best.get(adm[1], (9, ""))[1] or adm[0]) if adm else ""
            if region not in regions:
                regions.append(region)
            lines.append(f"{name}\t{alt}\t{c['lat']:.4f}\t{c['lon']:.4f}\t{c['pop']}\t{zones.index(c['tz'])}\t{regions.index(region)}")
        body = ("#tz\t" + "\t".join(zones) + "\n#region\t" + "\t".join(regions) + "\n" + "\n".join(lines) + "\n").encode("utf-8")
        data = gzip.compress(body, compresslevel=9, mtime=0)   # mtime 0: the same input gives the same bytes
        (OUT / "cities" / f"{cc}.tsv.gz").write_bytes(data)
        total += len(data)
        countries[cc]["n"] = len(lines)
    rows = sorted(((cc, c) for cc, c in countries.items() if c.get("n")), key=lambda x: sort_key(x[1]["ar"]))
    with open(OUT / "countries.tsv", "w", encoding="utf-8", newline="\n") as fh:
        fh.write("# code\tArabic (CLDR)\tshort Arabic\tEnglish\tcontinent\tlanguage\tcities. Generated by .github/tools/gen_geo.py "
                 "from GeoNames (CC BY 4.0) and CLDR: do not edit by hand\n")
        for cc, c in rows:
            fh.write("\t".join([cc, c["ar"], c["short"], c["en"], c["continent"], c["lang"], str(c["n"])]) + "\n")
    print(f"{len(rows)} countries, {sum(c['n'] for _, c in rows)} cities, {total / 1e6:.2f} MB of city files")
    write_index()


def write_index():
    """assets/geo/names.tsv.gz: every country's cities in one list, biggest first (the settings app searches it for a
    city of any country, then reads the place from its country's file)"""
    rows = []
    for f in sorted((OUT / "cities").glob("*.tsv.gz")):
        cc = f.name[:2]
        lines = gzip.decompress(f.read_bytes()).decode("utf-8").splitlines()
        for i, l in enumerate(lines[2:]):
            x = l.split("\t")
            rows.append((-int(x[4]), cc, i, x[0], x[1]))
    rows.sort()
    body = "".join(f"{r[3]}\t{r[4]}\t{r[1]}\t{r[2]}\n" for r in rows)
    data = gzip.compress(body.encode("utf-8"), compresslevel=9, mtime=0)
    (OUT / "names.tsv.gz").write_bytes(data)
    print(f"{len(rows)} cities in names.tsv.gz, {len(data) / 1e6:.2f} MB")


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    if args[0] == "--index":
        write_index()
        sys.exit(0)
    if args[0] == "--fetch":
        fetch(Path(args[1]))
        args = args[1:]
    main(Path(args[0]))
