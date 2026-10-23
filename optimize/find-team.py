#!/usr/bin/env python3
"""Find a club's TheSportsDB id, by its Arabic or English name: optimize/saver.sh find-team الهلال

Arabic names go through assets/shared/teams-ar.json (the names the screen shows) to their English spellings, and
the usual spellings are tried too (Al-Hilal, Al Hilal, Hilal, … FC), so one call is enough. Saudi clubs first.
Prints `id:Name  |  league  |  country`, ready for `optimize/saver.sh teams`.
"""
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

for stream in (sys.stdout, sys.stderr):   # Windows writes pipes in its own code page
    if hasattr(stream, "reconfigure"):
        stream.reconfigure(encoding="utf-8", errors="replace", newline="\n")

ARABIC = json.loads((Path(__file__).resolve().parents[1] / "assets/shared/teams-ar.json").read_text(encoding="utf-8"))
API = "https://www.thesportsdb.com/api/v1/json/123/searchteams.php?t="


def plain(ar):   # Arabic compared without harakat, tatweel or hamza forms
    ar = re.sub(r"[\u064B-\u0652\u0640]", "", ar.strip())
    return ar.translate(str.maketrans("أإآة", "اااه"))


def spellings(name):
    base = re.sub(r"\s+(S?FC|F\.C\.|Club|Football Club)$", "", name.strip(), flags=re.I)
    if " " in base and not re.match(r"Al[- ]", base, flags=re.I):   # Saudi Arabia, Real Madrid: as they are
        return list(dict.fromkeys([name, base]))
    bare = re.sub(r"^Al[- ]", "", base, flags=re.I)
    return list(dict.fromkeys([name, base, f"Al-{bare}", f"Al {bare}", bare]))


def search(name):
    try:
        with urllib.request.urlopen(API + urllib.parse.quote(name), timeout=20) as r:
            return json.load(r).get("teams") or []
    except Exception as e:  # noqa: BLE001
        print(f"   (TheSportsDB didn't answer for {name}: {e})", file=sys.stderr)
        return []


def main():
    q = " ".join(sys.argv[1:]).strip()
    if not q:
        sys.exit("usage: optimize/saver.sh find-team NAME   (Arabic or English)")
    if re.search(r"[\u0600-\u06FF]", q):
        names = [en for en, ar in ARABIC.items() if plain(ar) == plain(q)]
        if not names:
            sys.exit(f"«{q}» isn't in our Arabic list (assets/shared/teams-ar.json): try its English name.")
    else:
        names = [q]
    tried, found = set(), {}
    for name in names:   # the Arabic list has many spellings of one club: stop at the first that finds it
        if found:
            break
        for s in spellings(name):
            if s.lower() in tried or len(tried) >= 8:
                continue
            tried.add(s.lower())
            for t in search(s):
                if t.get("strSport") == "Soccer":
                    found[t["idTeam"]] = t
    if not found:
        sys.exit(f"Nothing found for {q} (tried: {', '.join(sorted(tried))}). Try the city, or the English name.")
    for t in sorted(found.values(), key=lambda t: (t.get("strCountry") != "Saudi Arabia", t["strTeam"])):
        print(f"{t['idTeam']}:{t['strTeam']}  |  {t.get('strLeague') or ''}  |  {t.get('strCountry') or ''}")


if __name__ == "__main__":
    main()
