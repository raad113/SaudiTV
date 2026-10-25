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


def online_list():
    """photos/online.json (tools/online_photos.py), each photo with the fields the page reads: u (its address), a (the
    author), l (the licence), p (the place), b (its brightness); "page" (its Commons page) stays in the repo"""
    d = json.loads((ROOT / "photos/online.json").read_text(encoding="utf-8"))
    return {k: [{f: x[f] for f in ("u", "a", "l", "p", "b") if f in x} for x in v] if isinstance(v, list) else v
            for k, v in d.items()}


def data_uri(path, mime):
    return f"data:{mime};base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def css_without_comments(html):
    """The stylesheet's /* … */ notes stay in src/template.html only: they are a quarter of its bytes, and tv.html is
    parsed on every start of the screensaver and the home screen (tests/check.py holds it under 450 KB). Only inside
    <style>; a line left empty goes too. No rule contains "/*" (the data URIs are base64 or SVG without it)."""
    a, b = html.index("<style>"), html.index("</style>")
    css = re.sub(r"/\*.*?\*/", "", html[a:b], flags=re.S)
    css = "\n".join(l.rstrip() for l in css.split("\n") if l.strip())
    return html[:a] + css + "\n" + html[b:]


REGEX_AFTER = set("(,=:[!&|?{};+-*%<>~^")   # a "/" after one of these (or these words) starts a regular expression
REGEX_WORDS = {"return", "typeof", "case", "in", "of", "delete", "void", "throw", "new", "else", "do", "yield", "await"}


def js_comment_lines(js):
    """The numbers (0-based) of the script's lines that are a // comment and nothing else, outside strings, template
    literals (their ${…} too), regular expressions and /* */ comments. A string or a regular expression that runs into
    the end of its line stops the build: then this reader got the code wrong, and nothing is guessed."""
    out, n = set(), len(js)
    st = {"line": 0}

    def fail(i, what):
        raise SystemExit(f"build.py: {what} at line {js.count(chr(10), 0, i) + 1} of the script")

    def template(i):   # i just after the opening `; returns the index after the closing one
        while i < n:
            c = js[i]
            if c == "\\":
                i += 2
            elif c == "`":
                return i + 1
            elif js.startswith("${", i):
                i = code(i + 2, True)
            else:
                if c == "\n":
                    st["line"] += 1
                i += 1
        fail(i, "unclosed template literal")

    def code(i, in_expr):   # in_expr: inside ${…}; returns the index after its closing }
        prev, word, start, depth = "", "", True, 0
        while i < n:
            c = js[i]
            if c == "\n":
                st["line"] += 1
                start = True
                i += 1
                continue
            if c in " \t\r":
                i += 1
                continue
            if js.startswith("//", i):
                if start and not in_expr:
                    out.add(st["line"])
                j = js.find("\n", i)
                i = n if j < 0 else j
                continue
            if js.startswith("/*", i):
                j = js.find("*/", i + 2)
                if j < 0:
                    fail(i, "unclosed comment")
                st["line"] += js.count("\n", i, j)
                i, start = j + 2, False
                continue
            start = False
            if c in "'\"":
                j = i + 1
                while j < n and js[j] != c:
                    if js[j] == "\n":
                        fail(i, "string runs past its line")
                    j += 2 if js[j] == "\\" else 1
                i, prev, word = j + 1, c, ""
            elif c == "`":
                i, prev, word = template(i + 1), "`", ""
            elif c == "/":
                if prev == "" or prev in REGEX_AFTER or word in REGEX_WORDS:
                    j, cls = i + 1, False
                    while j < n and (js[j] != "/" or cls):
                        if js[j] == "\n":
                            fail(i, "regular expression runs past its line")
                        if js[j] == "\\":
                            j += 1
                        elif js[j] == "[":
                            cls = True
                        elif js[j] == "]":
                            cls = False
                        j += 1
                    j += 1
                    while j < n and js[j].isalpha():
                        j += 1
                    i = j
                else:
                    i += 1
                prev, word = "/", ""
            elif c.isalnum() or c in "_$":
                j = i
                while j < n and (js[j].isalnum() or js[j] in "_$"):
                    j += 1
                prev, word, i = "a", js[i:j], j
            else:
                if c == "{":
                    depth += 1
                elif c == "}":
                    if in_expr and depth == 0:
                        return i + 1
                    depth -= 1
                prev, word, i = c, "", i + 1
        if in_expr:
            fail(i, "unclosed ${")
        return i

    code(0, False)
    return out


def js_without_line_comments(html):
    """The script's // notes alone on their lines stay in src/template.html only (about a fifth of tv.html), as the
    stylesheet's (css_without_comments). The code and its line-end notes are kept as they are."""
    a = html.index("<script>") + len("<script>")
    b = html.index("</script>", a)
    lines = html[a:b].split("\n")
    drop = js_comment_lines(html[a:b])
    assert all(lines[k].lstrip().startswith("//") for k in drop)
    return html[:a] + "\n".join(l for k, l in enumerate(lines) if k not in drop) + html[b:]


def main():
    html = (ROOT / "src/template.html").read_text(encoding="utf-8")
    assert '<meta charset="utf-8">' in html[:300], "template.html must start its head with <meta charset>"
    html = js_without_line_comments(css_without_comments(html))
    html = html.replace('<meta charset="utf-8">', '<meta charset="utf-8">\n' + MARKER, 1)
    # 2 KB, so inlined in both pages
    html = html.replace("__F_MARKS__", data_uri(ROOT / "assets/fonts/NotoNaskhArabic-Marks.woff2", "font/woff2"))
    teams = json.loads((ROOT / "assets/shared/teams-ar.json").read_text(encoding="utf-8"))
    html = html.replace("__TEAMS_AR__", json.dumps(teams, ensure_ascii=False))
    html = html.replace("__VERSES__", json.dumps(verses_for_screen(), ensure_ascii=False))
    html = html.replace("__BUKHARI__", json.dumps(bukhari(), ensure_ascii=False, separators=(",", ":")))

    bg_dir = ROOT / "assets/backgrounds"
    bgs = json.loads((bg_dir / "backgrounds.json").read_text(encoding="utf-8"))
    # photos/online.json as it is now, with what the page reads of each photo (its ONLINE_BUNDLED): a new or offline TV
    # has the Saudi photos and their places before the page first fetches the list. Beside tv.html as online.js (the
    # screensaver app has dist/ in its assets, the launcher copies it with tv.html: 85 KB more would take tv.html past
    # 450 KB), inline in the stand-alone saudi-tv.html
    online = json.dumps(online_list(), ensure_ascii=False, separators=(",", ":")).replace("</", "<\\/")
    (ROOT / "dist").mkdir(exist_ok=True)
    (ROOT / "dist/online.js").write_text("window.SAUDITV_ONLINE = " + online + ";\n", encoding="utf-8")
    for name, inline in (("saudi-tv.html", True), ("tv.html", False)):
        page = html.replace("__ONLINE__", f"<script>window.SAUDITV_ONLINE = {online};</script>" if inline
                            else '<script src="online.js"></script>')
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
