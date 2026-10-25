#!/usr/bin/env python3
"""«حديث اليوم»: Sahih al-Bukhari from fawazahmed0/hadith-api (the ara-bukhari edition, on jsDelivr) ->
assets/shared/bukhari.json, which src/build.py puts in the page.

    python3 .github/tools/fetch_bukhari.py            fetch the edition, pick, write assets/shared/bukhari.json
    python3 .github/tools/fetch_bukhari.py --verify   fetch again and check every bundled text against it, unchanged
    python3 .github/tools/fetch_bukhari.py --sample   print a few of the short forms, and what was left out and why

Run by hand where jsDelivr is reachable (pip install fonttools brotli). The screen never holds hadith text typed by
hand: only what this script copied, verbatim, from the edition. Nothing but Sahih al-Bukhari.

The card shows the short form: the companion who narrated it, then the Prophet's words ﷺ as the book has them, e.g.
  عن النعمان بن بشير رضي الله عنهما قال: قال النبي ﷺ: «لَتُسَوُّنَّ صُفُوفَكُمْ …»   صحيح البخاري، رقم 717
The chain of narrators before the companion goes; the Prophet's words stay verbatim (between the book's quotes).
A narration is used only when this split is certain (split): the text before his words ends with the companion, a
name of the COMPANIONS list right after «عن» or «سمعت», then one of the usual ways of quoting the Prophet ﷺ (HOW),
and nothing comes after his words. Anything else (another chain, a story, a narrator not in the list, a second
quote) is left out of the daily pool, never guessed. The companion's رضي الله عنه / عنها / عنهما comes from the list.

What it writes:
  short     [number, head] of every narration the card can show: the head is the short form before his words
            («عن … رضي الله عنه قال: قال رسول الله ﷺ:»); the page fetches the number (editions/ara-bukhari/<n>.min.json)
            and shows the head and the words between the book's quotes. At most MAX_WORDS of his words.
  maxWords  the longest of his words in the pool, in words (the page refuses a fetched text that is longer)
  fallback  FALLBACK of them, each {"n", "head", "text": the edition's text, verbatim}, for a TV without the internet:
            the LONGEST longest (so the tests see the tallest card) and the rest spread over the book.
"""
import json
import re
import sys
import time
import urllib.request
from collections import Counter
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "assets/shared/bukhari.json"
API = "https://cdn.jsdelivr.net/gh/fawazahmed0/hadith-api@1/editions/ara-bukhari"
MAX_WORDS = 30
FALLBACK, LONGEST = 60, 10
HARAKAT = re.compile("[ً-ْٰـ]")   # and the tatweel

# The companions the card names, as the book writes them after «عن» (after «سمعت» often with a final alif: أنسا),
# and the honorific that goes with each: عنه, عنها (a woman), عنهما (a companion whose father was one too).
COMPANIONS = {
    "عنه": ["أبي هريرة", "أنس", "أنس بن مالك", "أبي موسى", "أبي موسى الأشعري", "أبي سعيد", "أبي سعيد الخدري", "أبي ذر",
            "عمر", "عمر بن الخطاب", "علي", "علي بن أبي طالب", "عثمان", "عثمان بن عفان", "أبي بكر", "عبد الله بن مسعود",
            "ابن مسعود", "سهل بن سعد", "سهل بن سعد الساعدي", "أبي أيوب", "أبي أيوب الأنصاري", "أبي قتادة", "حذيفة",
            "المغيرة بن شعبة", "المغيرة", "عدي بن حاتم", "جرير بن عبد الله", "جرير", "سعد بن أبي وقاص", "أبي بكرة",
            "أبي مسعود", "أبي الدرداء", "عمار", "عمار بن ياسر", "عقبة بن عامر", "سلمة بن الأكوع", "أبي شريح",
            "أبي شريح العدوي", "معاذ بن جبل", "معاذ", "أبي طلحة", "زيد بن ثابت", "زيد بن خالد", "زيد بن خالد الجهني",
            "أبي جحيفة", "سمرة بن جندب", "عبادة بن الصامت", "عبد الله بن زيد", "أبي حميد", "أبي حميد الساعدي",
            "أبي برزة", "رافع بن خديج", "كعب بن مالك", "أبي بن كعب", "عبد الرحمن بن عوف", "عمرو بن العاص",
            "حكيم بن حزام", "بلال", "خباب", "سعيد بن زيد", "أبي أسيد", "عتبان بن مالك"],
    "عنها": ["عائشة", "أم سلمة", "حفصة", "ميمونة", "أم حبيبة", "أم عطية", "فاطمة", "زينب بنت جحش"],
    "عنهما": ["ابن عمر", "عبد الله بن عمر", "ابن عباس", "عبد الله بن عباس", "النعمان بن بشير", "عبد الله بن عمرو",
              "جابر", "جابر بن عبد الله", "البراء", "البراء بن عازب", "أسامة بن زيد", "عبد الله بن الزبير", "ابن الزبير",
              "أسماء", "أسماء بنت أبي بكر", "عبد الله بن أبي أوفى", "ابن أبي أوفى"],
}


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


def key(s):
    """for matching words: no harakat, one alif, ى as ي, ة as ه"""
    s = HARAKAT.sub("", s)
    return re.sub("[أإآ]", "ا", s).replace("ى", "ي").replace("ة", "ه")


NAMES = {}   # key -> (the name as the card writes it, honorific)
for hon, names in COMPANIONS.items():
    for n in names:
        NAMES[key(n)] = (n, hon)
        if n.startswith("أبي "):   # after «سمعت»: أبا هريرة; the book also writes أبو at times
            NAMES[key("أبا " + n[4:])] = (n, hon)
            NAMES[key("أبو " + n[4:])] = (n, hon)
NAME_RE = "|".join(sorted((re.escape(k) for k in NAMES), key=len, reverse=True))
# how the Prophet ﷺ is quoted right before his words; True: after the companion's own «قال» («قال: قال رسول الله ﷺ»)
HOW = {"قال رسول الله ﷺ": True, "قال النبي ﷺ": True, "سمعت رسول الله ﷺ يقول": True, "سمعت النبي ﷺ يقول": True,
       "أن رسول الله ﷺ قال": False, "أن النبي ﷺ قال": False, "عن النبي ﷺ قال": False, "عن رسول الله ﷺ قال": False}
HOW_BY_KEY = {key(h): h for h in HOW}
# the end of the narration before his words, on the plain text: «… عن|سمعت <companion> [قال|قالت|يقول] <how>»
TAIL = re.compile(r"(?:^| )(?:عن|سمعت) (" + NAME_RE + r")(ا?) (?:(قال|قالت|يقول|تقول) )?("
                  + "|".join(re.escape(k) for k in HOW_BY_KEY) + r")$")
SAW = re.compile(r"\s+".join("[ً-ْٰ]*".join(w) + "[ً-ْٰ]*" for w in "صلى الله عليه وسلم".split()))
HONORIFIC = re.compile(r"(?:ـ\s*)?رض[يى] الله (?:عنه|عنها|عنهما)(?:\s*ـ)?")


def plain(s):
    """the narration as plain words: no harakat, ﷺ for «صلى الله عليه وسلم», no honorific, no punctuation"""
    t = re.sub("[‎‏]", " ", s)
    t = SAW.sub(" ﷺ ", t)
    t = HARAKAT.sub("", t)
    t = HONORIFIC.sub(" ", t)
    t = re.sub(r"[،,:.؛\-]", " ", t)
    return key(" ".join(t.split()))


def matn(text):
    """his words, as the page takes them (template.html, hadithMatn): between the book's two quotes, verbatim but for
    the direction marks and the spaces and full stops around them; None unless there are exactly two quotes and
    nothing but punctuation after them"""
    parts = text.split('"')
    if len(parts) != 3 or re.sub(r"[\s.،,:‎‏]", "", parts[2]):
        return None
    m = " ".join(re.sub("[‎‏]", "", parts[1]).split()).strip(" .")
    return m or None


def split(text):
    """-> (head, None) or (None, why not). head: «عن <companion> رضي الله <…> [قال: |قالت: ]<how>:»"""
    if not matn(text):
        return None, "not one quote of his words"
    t = TAIL.search(plain(text.split('"')[0]))
    if not t:
        return None, "no known companion right before him"
    name, hon = NAMES[t.group(1)]
    verb, how = t.group(3), HOW_BY_KEY[t.group(4)]
    if HOW[how] != bool(verb):   # «قال: قال رسول الله ﷺ», «أن رسول الله ﷺ قال»; never «قال: أن …» or «… قال رسول»
        return None, "an unusual way of quoting"
    lead = ("قالت" if verb in ("قالت", "تقول") else "قال") + ": " if verb else ""
    return f"عن {name} رضي الله {hon} {lead}{how}:", None


def fits(h, font):
    """-> (True, head) or (False, why not)"""
    t = h["text"].strip()
    if not t:
        return False, "empty"
    head, why = split(t)
    if not head:
        return False, why
    words = len(matn(t).split())
    if words > MAX_WORDS or words < 4:
        return False, "his words too long or too short for the card"
    shown = head + SAW.sub("ﷺ", matn(t)).replace("{", "﴿").replace("}", "﴾")
    if any(not c.isspace() and ord(c) not in font and c not in "ﷺ﴿﴾" for c in shown):
        return False, "a letter the font lacks"
    return True, head


def main():
    from fontTools.ttLib import TTFont
    font = set(TTFont(ROOT / "assets/fonts/Thmanyah-Regular.woff2").getBestCmap())
    hadiths = edition()
    print(len(hadiths), "narrations in the edition")
    if "--verify" in sys.argv:
        data = json.loads(OUT.read_text(encoding="utf-8"))
        by = {h["hadithnumber"]: h["text"] for h in hadiths}
        bad = [f["n"] for f in data["fallback"] if by.get(f["n"]) != f["text"]]
        gone = [n for n, head in data["short"] if n not in by or fits({"text": by[n]}, font) != (True, head)]
        print(f"fallback: {len(data['fallback'])} checked, {len(bad)} differ {bad}; short: {len(data['short'])}, "
              f"{len(gone)} changed {gone[:20]}")
        sys.exit(1 if bad or gone else 0)
    picked, why = [], Counter()
    for h in hadiths:
        ok, info = fits(h, font)
        if ok:
            picked.append((h, info))
        else:
            why[info] += 1
    picked.sort(key=lambda x: x[0]["hadithnumber"])
    if "--sample" in sys.argv:
        for h, head in picked[::max(1, len(picked) // 15)]:
            print(h["hadithnumber"], head, "«" + matn(h["text"])[:50] + "»")
        print(len(picked), "kept;", dict(why))
        return
    longest = sorted(picked, key=lambda x: -len(x[1] + matn(x[0]["text"])))[:LONGEST]
    rest = [x for x in picked if x not in longest]
    step = len(rest) / (FALLBACK - LONGEST)
    fallback = sorted(longest + [rest[int(i * step)] for i in range(FALLBACK - LONGEST)], key=lambda x: x[0]["hadithnumber"])
    doc = {
        "source": API + ".min.json",
        "book": "صحيح البخاري",
        "fetched": date.today().isoformat(),
        "maxWords": max(len(matn(h["text"]).split()) for h, _ in picked),
        "short": [[h["hadithnumber"], head] for h, head in picked],
        "fallback": [{"n": h["hadithnumber"], "head": head, "text": h["text"]} for h, head in fallback],
    }
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(picked)} narrations in the short form (his words up to {doc['maxWords']} "
          f"words), {len(fallback)} bundled; left out: {dict(why)}")


if __name__ == "__main__":
    main()
