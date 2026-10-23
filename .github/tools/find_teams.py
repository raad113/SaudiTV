#!/usr/bin/env python3
"""Look up clubs by name on TheSportsDB (run in CI; the API is unreachable from the dev container).
Writes teams/found.json: every match with its id, league and country, for review.
Used to add clubs the league listing misses (Prefs.LEAGUE_EXTRA)."""
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://www.thesportsdb.com/api/v1/json/123/"
NAMES = ["Al-Zulfi", "Al Zulfi", "Zulfi"]


def get(path):
    for attempt in range(8):
        time.sleep(4)
        try:
            with urllib.request.urlopen(urllib.request.Request(API + path, headers={"User-Agent": "sauditv"}),
                                        timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code != 429:
                raise
            time.sleep(30 * (attempt + 1))
    raise RuntimeError("rate limited: " + path)


def main():
    found = {}
    for n in NAMES:
        for t in get("searchteams.php?" + urllib.parse.urlencode({"t": n})).get("teams") or []:
            found[t["idTeam"]] = {k: t.get(k) for k in ("idTeam", "strTeam", "strTeamAlternate", "strLeague",
                                                         "strLeague2", "strCountry", "strSport")}
    league = get("search_all_teams.php?" + urllib.parse.urlencode({"l": "Saudi First Division League"}))
    out = {"found": list(found.values()),
           "first_division": sorted(t["strTeam"] for t in league.get("teams") or [])}
    Path("teams").mkdir(exist_ok=True)
    Path("teams/found.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
