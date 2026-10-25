#!/usr/bin/env python3
"""tools/online_photos.py's rules, without the network: a recorded answer of Commons' API
(tests/fixtures/commons_imageinfo.json: eight files' imageinfo and categories) through its filter, its entries, the
Arabic places and the list's order; the licence names and authors as Commons writes them.

    python3 tests/online_photos_test.py
"""
import copy
import json
import socket
import urllib.parse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import online_photos as op   # noqa: E402

for _s in (sys.stdout, sys.stderr):   # Windows prints in its own code page: Arabic would crash
    if hasattr(_s, "reconfigure"):
        _s.reconfigure(encoding="utf-8", errors="replace")


def no_network(*a, **k):
    raise AssertionError("the test asked the network")


socket.socket.connect = no_network   # nothing here may go out
failures = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        failures.append(name)


pages = {p["title"]: p for p in json.loads((ROOT / "tests/fixtures/commons_imageinfo.json").read_text(encoding="utf-8"))["query"]["pages"]}
AlUla, Sarawat, Nabawi = "File:Al Ula (6749389063).jpg", "File:Sarawat Mountains, Asir Region, Saudi Arabia (8).jpg", "File:Masjid Nabwi 04.jpg"
Market, Book, Grave = "File:Craftsmen Market in al-Ahsa, Hofuf - Mar 7, 2020 03.jpg", \
    "File:The heart of Arabia, a record of travel and exploration (1922) (14749537136).jpg", "File:(S.A.W) grave.jpg"
Png, Inside = "File:10 معالم الأحساء.png", "File:Al-Masjed Al-Nabawi 3.jpg"

# ---- the filter
check("keeps a wide landscape (AlUla, 4608 x 2802, CC BY 2.0)", op.keep(pages[AlUla], "العُلا") is None, op.keep(pages[AlUla], "العُلا"))
check("keeps a wide mountain view (Sarawat, CC BY 2.0)", op.keep(pages[Sarawat], "عسير") is None)
QI = [{"ns": 14, "title": "Category:Quality images of Saudi Arabia"}]
check("keeps a holy mosque's wide outside view when it is a quality image (Exterior of Masjid al-Nabawi)",
      op.keep(dict(pages[Nabawi], _root_holy=True, _all_cats=QI), "المسجد النبوي") is None)
check("leaves out the same view when it is not featured or quality (holy mosques, Mecca)",
      op.keep(dict(pages[Nabawi], _root_holy=True), "المسجد النبوي") == "holy mosque: not featured or quality"
      and op.keep(dict(pages[AlUla], _all_cats=QI), "مكة المكرمة") == "holy mosque: not a wide outside view")
check("leaves out people (People at work in Saudi Arabia)", op.keep(pages[Market], "الأحساء") == "people", op.keep(pages[Market], "الأحساء"))
check("leaves out a licence not ours (No restrictions)", op.keep(pages[Book], "الطائف") == "licence", op.keep(pages[Book], "الطائف"))
check("leaves out 4:3 (2816 x 2112: under 1.6:1)", op.keep(pages[Grave], "المسجد النبوي") == "size")
check("leaves out a PNG (graphics) and portrait shapes", op.keep(pages[Png], "الأحساء") == "not a photo"
      and op.keep(pages[Inside], "المسجد النبوي") == "size")
inside = copy.deepcopy(pages[Inside])
inside["imageinfo"][0].update(width=2592, height=1458)   # the same file, were it wide
check("leaves out a holy mosque's inside, even wide (Interior of Masjid al-Nabawi)",
      op.keep(dict(inside, _root_holy=True), "المسجد النبوي") in ("not a photo", "holy mosque: not a wide outside view"))
court = copy.deepcopy(pages[Nabawi])
court["categories"] = [{"ns": 14, "title": "Category:Courtyard of al-Haram Mosque"}]
check("leaves out a holy mosque's courtyard, even from an outside-views root",
      op.keep(dict(court, _root_holy=True), "المسجد الحرام") == "holy mosque: not a wide outside view")
narrow = copy.deepcopy(pages[AlUla])
narrow["imageinfo"][0].update(width=1800, height=1000)
wide = copy.deepcopy(pages[AlUla])
wide["imageinfo"][0].update(width=6000, height=1000)
three_two = copy.deepcopy(pages[AlUla])
three_two["imageinfo"][0].update(width=3000, height=2000)
check("leaves out under 1920 px wide, and a strip wider than 2.2:1; keeps 3:2 (1.45:1 and wider)",
      op.keep(narrow, "العُلا") == "size" and op.keep(wide, "العُلا") == "size" and op.keep(three_two, "العُلا") is None)
holy_elsewhere = copy.deepcopy(pages[AlUla])
holy_elsewhere["title"] = "File:Tawaf around the Kaaba at night.jpg"
check("a holy mosque found under another place: only a wide outside view (Kaaba, tawaf: out)",
      op.keep(holy_elsewhere, "مكة المكرمة") == "holy mosque: not a wide outside view")
doc = copy.deepcopy(pages[AlUla])
doc["categories"] = [{"ns": 14, "title": "Category:Maps of Al-'Ula"}]
check("leaves out maps and documents by category", op.keep(doc, "العُلا") == "not a photo")
arab = copy.deepcopy(pages[AlUla])
arab["title"] = "File:طفل صغير في الأحساء.jpg"
check("people in an Arabic title too (طفل)", op.keep(arab, "الأحساء") == "people")

# ---- an entry: the 1920 px thumbnail without Commons' tracking, the file's page, a plain author, the licence
e = op.entry(pages[AlUla], "العُلا")
check("entry: the 1920 px thumbnail, no ?utm_", "/thumb/" in e["u"] and "/1920px-" in e["u"] and "?" not in e["u"], e["u"])
check("entry: the file's page, author, licence and place",
      e["page"].startswith("https://commons.wikimedia.org/wiki/File:") and e["a"] == "Sammy Six" and e["l"] == "CC BY 2.0"
      and e["p"] == "العُلا", e)
check("entry: the author without HTML", op.author('<a href="//commons.wikimedia.org/wiki/User:X" title="User:X">Ahmed &amp; Co</a>') == "Ahmed & Co"
      and op.author("", "Uploader") == "Uploader" and op.author("Unknown author", "Up") == "Up"
      and len(op.author("x" * 200)) == 61)

# ---- licences as Commons names them
cases = {"CC BY-SA 4.0": "CC BY-SA 4.0", "CC BY 2.0": "CC BY 2.0", "CC0": "CC0", "Public domain": "PD", "PD-self": "PD",
         "CC BY-SA 3.0 igo": "CC BY-SA 3.0", "CC-BY-SA-4.0": "CC BY-SA 4.0", "cc-by-3.0": "CC BY 3.0",
         "CC BY-NC 2.0": None, "GFDL": None, "No restrictions": None, "Attribution": None, "": None}
got = {k: op.licence(k) for k in cases}
check("licences: CC0, PD, CC BY and CC BY-SA only, short names", got == cases, {k: v for k, v in got.items() if v != cases[k]})

# ---- the Arabic places: from the root a file was found in, else from its categories and title
check("places from categories and titles", op.place_of("File:Madain Saleh (6735910601).jpg") == "مدائن صالح"
      and op.place_of("Category:Landscapes of Al-'Ula") == "العُلا" and op.place_of("Category:Old Jeddah") == "جدة التاريخية"
      and op.place_of("File:Kingdom Centre at night") == "الرياض" and op.place_of("File:Farasan beach") == "جزر فرسان"
      and op.place_of("Exterior of Masjid al-Nabawi") == "المسجد النبوي" and op.place_of("File:Some desert.jpg") is None)
roots = {c: p for c, p, _ in op.ROOTS}
check("roots: the user's places in Arabic", roots["Al-'Ula"] == "العُلا" and roots["Meda'in Saleh"] == "مدائن صالح"
      and roots["Tuwaiq Escarpment"] == "حافة العالم" and roots["Rub' al Khali"] == "الربع الخالي" and roots["Abha"] == "أبها"
      and roots["Old Jeddah"] == "جدة التاريخية" and roots["Diriyah"] == "الدرعية" and roots["Al-Ahsa"] == "الأحساء"
      and roots["Ta’if"] == "الطائف" and roots["Najran"] == "نجران" and roots["Ha'il"] == "حائل" and roots["Farasan"] == "جزر فرسان"
      and roots["Tabuk, Saudi Arabia"] == "تبوك" and roots["Kingdom Centre (Riyadh)"] == "الرياض", roots)
check("roots: only the holy mosques' outside-view categories are taken as outside views (HOLY_ROOTS)",
      op.HOLY_ROOTS <= {c for c, _, _ in op.ROOTS} and all(("Exterior" in c or "Bird's-eye" in c or "at night" in c) for c in op.HOLY_ROOTS))

# ---- the order: places in turn, at most MAX_SERIES of a series, the same list from the same answers in any order
check("series: a name without its number", op.series_of("File:Jeddah_Corniche_37.jpg") == op.series_of("File:Jeddah Corniche (2).jpg".replace(" ", "_"))
      == "jeddah_corniche" and op.series_of("File:Ruins in Al Ula - panoramio.jpg") == "ruins in al ula")
found = [("جدة التاريخية", {"u": f"j{i}"}, 3, f"File:Old_Jeddah_{i}.jpg") for i in range(10)] \
    + [("العُلا", {"u": f"a{i}"}, 3 if i else 0, f"File:Ula view {chr(97 + i)}.jpg") for i in range(4)] \
    + [("أبها", {"u": "b0"}, 2, "File:Abha.jpg")]
out = [x["u"] for x in op.order(found)]
check("order: places in turn (ROOTS order), best first (featured), a series at most 3",
      out == ["a0", "b0", "j0", "a1", "j1", "a2", "j2", "a3"], out)
check("order: the same list whatever order the answers came in", [x["u"] for x in op.order(list(reversed(found)))] == out)
many = [("العُلا", {"u": f"a{i:03d}"}, 3, f"File:Ula {chr(65 + i % 26)}{chr(65 + i // 26)} view.jpg") for i in range(400)] \
    + [("أبها", {"u": f"b{i:03d}"}, 3, f"File:Abha {chr(65 + i % 26)}{chr(65 + i // 26)} hill.jpg") for i in range(400)]
check(f"order: at most {op.MAX_PER_PLACE} a place, {op.MAX_SAUDI} in all", len(op.order(many)) == 2 * op.MAX_PER_PLACE
      and op.MAX_SAUDI == 300)
ranked = [("الرياض", {"u": f"q{i}"}, 1, f"File:Riyadh {chr(97 + i)}.jpg") for i in range(5)]     + [("الرياض", {"u": "plain"}, 3, "File:Riyadh plain.jpg"), ("الرياض", {"u": "featured"}, 0, "File:Riyadh z.jpg")]     + [("نجران", {"u": "n-valued"}, 2, "File:Najran v.jpg"), ("نجران", {"u": "n-plain"}, 3, "File:Najran p.jpg")]
got = [x["u"] for x in op.order(ranked)]
check("order: featured, quality, valued, then the rest; the rest only where a place has under 5 of the first three",
      [u for u in got if u[0] in "qfp"] == ["featured", "q0", "q1", "q2", "q3", "q4"] and "n-valued" in got and "n-plain" in got
      and got.index("n-valued") < got.index("n-plain"), got)
holy = [("المسجد النبوي", {"u": f"h{i}"}, 1, f"File:Nabawi {chr(97 + i)}.jpg") for i in range(6)]     + [("مكة المكرمة", {"u": f"m{i}"}, 0, f"File:Makkah {chr(97 + i)}.jpg") for i in range(6)]
got = [x["u"] for x in op.order(holy)]
check(f"order: at most {op.MAX_HOLY} photos of each holy place", sum(u[0] == "h" for u in got) == 3 and sum(u[0] == "m" for u in got) == 3, got)

# ---- tools/photo-blocklist.txt
import tempfile, os
with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as f:
    f.write("# a comment\nFile:A_b c.jpg   # why\n\nPlain name.JPG\n")
bl = op.blocklist(f.name)
os.unlink(f.name)
check("blocklist: file names with or without File:, underscores as spaces, # comments", bl == {"File:A b c.jpg", "File:Plain name.JPG"}, bl)
real_bl = op.blocklist()
check("blocklist: tools/photo-blocklist.txt has the spot checks' weak photos, none of them in photos/online.json",
      len(real_bl) >= 4 and not real_bl & {urllib.parse.unquote(e["page"].rsplit("/wiki/", 1)[-1]).replace("_", " ")
          for e in json.loads((ROOT / "photos/online.json").read_text(encoding="utf-8"))["saudi"]}, sorted(real_bl)[:3])

# ---- Picture of the Day's template
check("Picture of the Day: the file from {{Potd filename}} or a bare name",
      op.potd_file("{{Potd filename|1= Hoverfly October 2007-21.jpg\n<!--x-->|2=2026|3=10|4=21}}") == "Hoverfly October 2007-21.jpg"
      and op.potd_file("{{Potd filename| Gewone fopzwam.jpg|2026|10|20}}") == "Gewone fopzwam.jpg"
      and op.potd_file("Old day.jpg") == "Old day.jpg" and op.potd_file("") is None)

# ---- the SAUDI switch (off since 2026-10-25): main() with the sources stubbed, in both states
def run_main(switch, saudi_list):
    calls = []
    saved = (op.SAUDI, op.saudi, op.world, op.bing, sys.argv)
    op.SAUDI = switch
    op.saudi = lambda: (calls.append("saudi") or (copy.deepcopy(saudi_list), {}))
    op.world = lambda today: [{"u": "https://w/1.jpg", "page": "p", "a": "A", "l": "CC0", "p": ""}]
    op.bing = lambda: [{"u": "https://bing/1.jpg"}]
    with tempfile.TemporaryDirectory() as d:
        path = Path(d) / "online.json"
        sys.argv = ["online_photos.py", "--out", str(path), "--no-brightness"]
        try:
            op.main()
            got = json.loads(path.read_text(encoding="utf-8"))
        except SystemExit as e:
            got = ("exit", str(e.code))
        finally:
            op.SAUDI, op.saudi, op.world, op.bing, sys.argv = saved
    return got, calls


one = op.entry(pages[AlUla], "العُلا")
check("switch: SAUDI is off by default (the user's choice, 2026-10-25)", op.SAUDI is False)
got, calls = run_main(False, [one])
check("switch off: an empty saudi list, Commons' Saudi search not run; world and bing still written",
      isinstance(got, dict) and got["saudi"] == [] and calls == [] and len(got["world"]) == 1 and len(got["bing"]) == 1, (got, calls))
got, calls = run_main(True, [one])
check("switch on: the Saudi list as found, with world and bing",
      isinstance(got, dict) and calls == ["saudi"] and [x["u"] for x in got["saudi"]] == [one["u"]] and len(got["world"]) == 1, (got, calls))
got, calls = run_main(True, [])
check("switch on, nothing found: the list is left as it was (exit, no file)", isinstance(got, tuple) and got[0] == "exit", got)

# ---- the list in the repo: the shape the page reads
doc = json.loads((ROOT / "photos/online.json").read_text(encoding="utf-8"))
s = doc.get("saudi", [])
if op.SAUDI:
    check("photos/online.json: saudi photos with u, page, a, l, p (in Arabic), b", s and all(
        x["u"].startswith("https://") and x["page"].startswith("https://commons.wikimedia.org/wiki/File:") and x["l"]
        and x["p"] and any("؀" <= ch <= "ۿ" for ch in x["p"]) and 0 <= x.get("b", 0.5) <= 1 for x in s), len(s))
    check("photos/online.json: at most 300, no photo twice, licences ours only", len(s) <= 300 and len({x["u"] for x in s}) == len(s)
          and {x["l"] for x in s} <= {"CC0", "PD"} | {f"CC BY{sa} {v}" for sa in ("", "-SA") for v in ("1.0", "2.0", "2.5", "3.0", "4.0")})
else:
    check("photos/online.json: the saudi list is empty while SAUDI is off", s == [], len(s))
check("photos/online.json: world and bing are lists", isinstance(doc.get("world"), list) and isinstance(doc.get("bing"), list))
check("dist/online.js matches photos/online.json's saudi list", ('"saudi":[]' in (ROOT / "dist/online.js").read_text(encoding="utf-8")) == (s == []))

print()
print(f"{len(failures)} failed" if failures else "all online photos tests passed")
sys.exit(1 if failures else 0)
