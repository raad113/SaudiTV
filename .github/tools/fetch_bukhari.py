#!/usr/bin/env python3
"""«حديث اليوم»: Sahih al-Bukhari from fawazahmed0/hadith-api (the ara-bukhari edition, on jsDelivr) ->
assets/shared/bukhari.json, which src/build.py puts in the page.

    python3 .github/tools/fetch_bukhari.py            fetch the edition, pick, write assets/shared/bukhari.json
    python3 .github/tools/fetch_bukhari.py --verify   fetch again and check every bundled text against it, unchanged

Run by hand where jsDelivr is reachable (pip install fonttools brotli). The screen never holds hadith text typed by
hand: only what this script copied, verbatim, from the edition. Nothing but Sahih al-Bukhari.

What it writes:
  short     the numbers of the narrations the card can show in full, isnad and all: at most MAX_WORDS words, the
            Prophet's words ﷺ between the book's quotes right after «… صلى الله عليه وسلم» (or «… قال / يقول»),
            nothing after them, and every letter in the page's font. The page shows one a day and fetches it
            (editions/ara-bukhari/<n>.min.json).
  maxWords  the longest of them, in words (the page refuses a fetched text that is longer)
  fallback  FALLBACK of them, each {"n": number, "text": the edition's text, verbatim}, for a TV without the internet:
            the LONGEST longest (so the tests see the tallest card) and the rest spread over the book.
"""
import json
import re
import sys
import time
import urllib.request
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "assets/shared/bukhari.json"
API = "https://cdn.jsdelivr.net/gh/fawazahmed0/hadith-api@1/editions/ara-bukhari"
MAX_WORDS = 45
FALLBACK, LONGEST = 60, 10
HARAKAT = re.compile("[ً-ْٰ]")


def get(url):
    err = None
    for wait in (0, 2, 5, 10):
        time.sleep(wait)
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "sauditv"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            err = e
    raise err


def edition():
    for ext in (".min.json", ".json"):   # the API's advice: the other form when one fails
        try:
            return get(API + ext)["hadiths"]
        except Exception as e:  # noqa: BLE001
            print(f"{API}{ext}: {e}", file=sys.stderr)
    sys.exit("!! the ara-bukhari edition could not be fetched")


def bare(s):
    return " ".join(HARAKAT.sub("", s).replace("‏", " ").replace("‎", " ").split())


SAW = re.compile(r"\s+".join("[ً-ْٰ]*".join(w) + "[ً-ْٰ]*" for w in "صلى الله عليه وسلم".split()))


def shown(text):
    """The text as the page draws it (template.html, hadithParts): typography only."""
    t = re.sub("[‎‏]", "", text)
    t = SAW.sub("ﷺ", t).replace("{", "﴿").replace("}", "﴾")
    return re.sub(r"\s+([،,.:؛])", r"\1", t)


def fits(h, font):
    t = h["text"].strip()
    if not t or len(t.split()) > MAX_WORDS or t.count('"') != 2:
        return False
    before, quote, after = t.split('"')
    b = re.sub(r"[^ء-ي ]", " ", bare(before))
    b = " ".join(b.split())
    if not re.search(r"صلى الله عليه وسلم( قال| يقول)?$", b):   # his words, right after his name
        return False
    if re.sub(r"[\s.،,:‎‏]", "", after) or len(bare(quote).split()) < 4:
        return False
    missing = {c for c in shown(t).replace('"', "") if not c.isspace() and ord(c) not in font and c not in "ﷺ﴿﴾"}
    return not missing


def main():
    from fontTools.ttLib import TTFont
    font = set(TTFont(ROOT / "assets/fonts/Thmanyah-Regular.woff2").getBestCmap())
    hadiths = edition()
    print(len(hadiths), "narrations in the edition")
    if "--verify" in sys.argv:
        data = json.loads(OUT.read_text(encoding="utf-8"))
        by = {h["hadithnumber"]: h["text"] for h in hadiths}
        bad = [f["n"] for f in data["fallback"] if by.get(f["n"]) != f["text"]]
        gone = [n for n in data["short"] if n not in by or not fits({"text": by[n]}, font)]
        print(f"fallback: {len(data['fallback'])} checked, {len(bad)} differ {bad}; short: {len(data['short'])}, {len(gone)} no longer fit {gone[:20]}")
        sys.exit(1 if bad or gone else 0)
    picked = [h for h in hadiths if fits(h, font)]
    picked.sort(key=lambda h: h["hadithnumber"])
    short = [h["hadithnumber"] for h in picked]
    longest = sorted(picked, key=lambda h: -len(shown(h["text"])))[:LONGEST]
    rest = [h for h in picked if h not in longest]
    step = len(rest) / (FALLBACK - LONGEST)
    spread = [rest[int(i * step)] for i in range(FALLBACK - LONGEST)]
    fallback = sorted(longest + spread, key=lambda h: h["hadithnumber"])
    doc = {
        "source": API + ".min.json",
        "book": "صحيح البخاري",
        "fetched": date.today().isoformat(),
        "maxWords": max(len(h["text"].split()) for h in picked),
        "short": short,
        "fallback": [{"n": h["hadithnumber"], "text": h["text"]} for h in fallback],
    }
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(short)} short narrations (up to {doc['maxWords']} words), {len(fallback)} bundled")


if __name__ == "__main__":
    main()
