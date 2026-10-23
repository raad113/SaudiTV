#!/usr/bin/env python3
"""Look up TheSportsDB's real league ids per country and the clubs in each (run in CI; the API is
unreachable from the dev container). Writes leagues/leagues.json for review."""
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = "https://www.thesportsdb.com/api/v1/json/123/"
COUNTRIES = ["Saudi Arabia", "England", "Spain", "Italy", "Germany", "France", "Portugal", "Netherlands",
             "Turkey", "Egypt", "United Arab Emirates", "Qatar", "United States"]


def get(path):
    """Free key allows only a few requests a minute: pace them and back off on 429."""
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
    out = {}
    for c in COUNTRIES:
        leagues = []
        for name in (c, c.replace(" ", "-"), c.replace(" ", "_")):
            d = get("search_all_leagues.php?" + urllib.parse.urlencode({"c": name, "s": "Soccer"}))
            leagues = d.get("countries") or d.get("countrys") or d.get("leagues") or []
            if leagues:
                break
        rows = []
        for lg in leagues[:4]:
            teams = (get(f"lookup_all_teams.php?id={lg['idLeague']}").get("teams") or [])
            rows.append({"id": lg["idLeague"], "name": lg.get("strLeague"), "alt": lg.get("strLeagueAlternate"),
                         "country_param": name, "team_count": len(teams),
                         "team_countries": sorted({t.get("strCountry") or "" for t in teams}),
                         "teams": [t.get("strTeam") for t in teams][:40]})
        out[c] = rows
        print(c, [(r["id"], r["name"], r["team_count"]) for r in rows])
    Path("leagues").mkdir(exist_ok=True)
    Path("leagues/leagues.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
