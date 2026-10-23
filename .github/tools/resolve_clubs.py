#!/usr/bin/env python3
"""English names for the 172 Saudi clubs in .github/tools/saudi-clubs-ar.json (from Arabic Wikipedia's
«قائمة أندية كرة القدم في السعودية»), via Wikidata: Arabic article -> English label, aliases and
English Wikipedia title. Run in CI (Wikidata is unreachable from the dev container).
Writes clubs/en-to-ar.json for review before merging into assets/shared/teams-ar.json."""
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://www.wikidata.org/w/api.php"
UA = {"User-Agent": "sauditv club names (github.com/raad113/SaudiTV)"}


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


def main():
    clubs = json.load(open(".github/tools/saudi-clubs-ar.json", encoding="utf-8"))
    out, missing = {}, []
    for i in range(0, len(clubs), 40):   # wbgetentities takes up to 50 titles
        batch = clubs[i:i + 40]
        d = get({"action": "wbgetentities", "sites": "arwiki", "titles": "|".join(c["article"] for c in batch),
                 "props": "labels|aliases|sitelinks", "languages": "en", "sitefilter": "arwiki|enwiki"})
        by_title = {}
        for e in d.get("entities", {}).values():
            t = (e.get("sitelinks", {}).get("arwiki") or {}).get("title")
            by_title[t] = e
        for c in batch:
            e = by_title.get(c["article"])
            if e is None:
                missing.append(c["article"])
                continue
            names = set()
            if "en" in e.get("labels", {}):
                names.add(e["labels"]["en"]["value"])
            names.update(a["value"] for a in e.get("aliases", {}).get("en", []))
            enwiki = (e.get("sitelinks", {}).get("enwiki") or {}).get("title")
            if enwiki:
                names.add(enwiki)
            for n in names:
                out.setdefault(n, c["ar"])
    Path("clubs").mkdir(exist_ok=True)
    Path("clubs/en-to-ar.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    Path("clubs/missing.json").write_text(json.dumps(missing, ensure_ascii=False, indent=1), encoding="utf-8")
    print(len(out), "names;", len(missing), "missing")


if __name__ == "__main__":
    main()
