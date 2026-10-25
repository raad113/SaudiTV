#!/usr/bin/env python3
"""src/template.html -> two pages in dist/:

saudi-tv.html       stand-alone: fonts and the bundled photos inlined, works offline from a double-click.
tv.html             for the Android app: same page, but fonts and bundled photos are URLs the app serves from
                    its own assets (PhotoCache.java). 2.2 MB less to parse, so the screensaver starts faster.
"""
import base64
import json
import re
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FONTS = {
    "__F_LIGHT__": "Thmanyah-Light.woff2",
    "__F_REGULAR__": "Thmanyah-Regular.woff2",
    "__F_MEDIUM__": "Thmanyah-Medium.woff2",
    "__F_DISPLAY__": "ThmanyahDisplay-Bold.woff2",
}
# tv.html points here for fonts and bundled photos; the app answers from its assets, a browser from GitHub.
REPO = "https://raw.githubusercontent.com/raad113/SaudiTV/main/"
# Both pages carry this in their head. The screensaver's page auto-update (Updater.MARKER in android/app) only
# accepts a downloaded page that has it, so an error page or an empty or cut-off download is never shown.
MARKER = '<meta name="sauditv-page" content="github.com/raad113/SaudiTV">'


BASMALA = "بسم الله الرحمن الرحيم"   # compared without harakat: the API orders them its own way


def plain(s):
    return re.sub("[\u064B-\u065F\u0670]", "", s)


def verses_for_screen():
    """assets/shared/verses.json as fetched from the Quran API (.github/tools/fetch_verses.py), made fit for the font:
    the pause marks and ۞ are left out (Thmanyah has no glyph for them); letters and harakat stay as fetched.
    The API puts the basmala in front of the first verse of a surah; it is not part of that verse, so it goes
    (Al-Fatiha's first verse is the basmala itself). Surah names are shown plain, without harakat."""
    path = ROOT / "assets/shared/verses.json"
    if not path.is_file():
        return []
    out = []
    for v in json.loads(path.read_text(encoding="utf-8")):
        text = " ".join(re.sub("[\u06D6-\u06DE\u06E9]", "", v["text"]).split())   # pause marks, ۞ and ۩
        words = text.split()
        if v["ayah"] == 1 and not v["ref"].startswith("1:") and plain(" ".join(words[:4])) == BASMALA:
            text = " ".join(words[4:])
        # NFC first: the API writes آ as ا + madda, which stripping the harakat would turn into ا (ال عمران)
        name = re.sub("[\u064B-\u065F\u0670\u06D6-\u06ED]", "", unicodedata.normalize("NFC", v["surah"]))
        name = name.replace("\u0671", "ا")   # ٱ: no glyph in Thmanyah
        out.append({"text": text, "surah": name, "ayah": v["ayah"]})
    return out


def bukhari():
    """assets/shared/bukhari.json (.github/tools/fetch_bukhari.py): «حديث اليوم», Sahih al-Bukhari only. The page gets
    the short list, its longest in words and the bundled texts; nothing else of the file."""
    d = json.loads((ROOT / "assets/shared/bukhari.json").read_text(encoding="utf-8"))
    return {"maxWords": d["maxWords"], "short": d["short"], "fallback": d["fallback"]}


def data_uri(path, mime):
    return f"data:{mime};base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def main():
    html = (ROOT / "src/template.html").read_text(encoding="utf-8")
    assert '<meta charset="utf-8">' in html[:300], "template.html must start its head with <meta charset>"
    html = html.replace('<meta charset="utf-8">', '<meta charset="utf-8">\n' + MARKER, 1)
    # 2 KB, so inlined in both pages
    html = html.replace("__F_MARKS__", data_uri(ROOT / "assets/fonts/NotoNaskhArabic-Marks.woff2", "font/woff2"))
    teams = json.loads((ROOT / "assets/shared/teams-ar.json").read_text(encoding="utf-8"))
    html = html.replace("__TEAMS_AR__", json.dumps(teams, ensure_ascii=False))
    html = html.replace("__VERSES__", json.dumps(verses_for_screen(), ensure_ascii=False))
    html = html.replace("__BUKHARI__", json.dumps(bukhari(), ensure_ascii=False, separators=(",", ":")))

    bg_dir = ROOT / "assets/backgrounds"
    bgs = json.loads((bg_dir / "backgrounds.json").read_text(encoding="utf-8"))
    for name, inline in (("saudi-tv.html", True), ("tv.html", False)):
        page = html
        for token, font in FONTS.items():
            page = page.replace(token, data_uri(ROOT / "assets/fonts" / font, "font/woff2") if inline
                                else REPO + "assets/fonts/" + font)
        entries = []
        for bg in bgs:
            u = data_uri(bg_dir / bg["file"], "image/jpeg") if inline else REPO + "assets/backgrounds/" + bg["file"]
            entries.append(f'  {{u:"{u}", c:{json.dumps(bg["credit"], ensure_ascii=False)}, d:{bg["brightness"]}}}')
        out = ROOT / "dist" / name
        out.parent.mkdir(exist_ok=True)
        out.write_text(page.replace("__BG_SET__", ",\n".join(entries)), encoding="utf-8")
        print(f"wrote {out.relative_to(ROOT)} ({out.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
