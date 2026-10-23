#!/usr/bin/env python3
"""TheSportsDB ids of the 172 Saudi clubs in .github/tools/saudi-clubs-ar.json, from Wikidata (run in CI; Wikidata
is unreachable from the dev container). TheSportsDB's free name search misses some of them altogether (it never
returns the Saudi Al-Hilal), so optimize/find-team.py looks Saudi clubs up in this list first.

Which Wikidata property holds the TheSportsDB team id is found, not assumed: the one whose value on Al-Hilal's
entry is 136013, the id the app has always used for it. Writes teams/ids.json ({Arabic name: [id, English name]})
and teams/ids-missing.json for review before it goes to assets/shared/team-ids.json.
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://www.wikidata.org/w/api.php"
UA = {"User-Agent": "SaudiTV club ids (github.com/raad113/SaudiTV)"}
KNOWN = ("نادي الهلال (السعودية)", "136013")   # Al-Hilal: Prefs.TEAM_PRESETS


def get(params):
    url = API + "?" + urllib.parse.urlencode({**params, "format": "json", "maxlag": "5"})
    for attempt in range(6):
        time.sleep(1.5)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            time.sleep(15 * (attempt + 1))
    raise RuntimeError("rate limited")


def values(entity, prop):
    return [c["mainsnak"]["datavalue"]["value"] for c in entity.get("claims", {}).get(prop, [])
            if c["mainsnak"].get("datavalue") and isinstance(c["mainsnak"]["datavalue"]["value"], str)]


def main():
    clubs = json.load(open(".github/tools/saudi-clubs-ar.json", encoding="utf-8"))
    entities = {}
    for i in range(0, len(clubs), 40):   # wbgetentities takes up to 50 titles
        d = get({"action": "wbgetentities", "sites": "arwiki", "titles": "|".join(c["article"] for c in clubs[i:i + 40]),
                 "props": "claims|labels|sitelinks", "languages": "en", "sitefilter": "arwiki"})
        for e in d.get("entities", {}).values():
            title = (e.get("sitelinks", {}).get("arwiki") or {}).get("title")
            if title:
                entities[title] = e
    hilal = entities.get(KNOWN[0])
    if hilal is None:
        raise SystemExit(f"{KNOWN[0]} isn't on Wikidata any more: can't tell which property is TheSportsDB's")
    props = [p for p in hilal.get("claims", {}) if KNOWN[1] in values(hilal, p)]
    if len(props) != 1:
        raise SystemExit(f"expected one property with {KNOWN[1]} on Al-Hilal, found {props}")
    prop = props[0]
    print("TheSportsDB team id property:", prop)
    ids, missing = {}, []
    for c in clubs:
        e = entities.get(c["article"])
        found = values(e, prop) if e else []
        if found:
            ids[c["ar"]] = [found[0], ((e.get("labels") or {}).get("en") or {}).get("value", "")]
        else:
            missing.append(c["ar"])
    Path("teams").mkdir(exist_ok=True)
    Path("teams/ids.json").write_text(json.dumps(ids, ensure_ascii=False, indent=1), encoding="utf-8")
    Path("teams/ids-missing.json").write_text(json.dumps(missing, ensure_ascii=False, indent=1), encoding="utf-8")
    print(len(ids), "clubs with an id;", len(missing), "without")


if __name__ == "__main__":
    main()
