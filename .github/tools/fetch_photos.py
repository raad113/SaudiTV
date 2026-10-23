#!/usr/bin/env python3
"""Download the chosen Unsplash photos (.github/tools/unsplash-picks.json) already cropped to 1920x1080 by Unsplash's
image service (fit=crop, crop=entropy keeps the busiest part), and register each download with Unsplash as
its API guidelines ask. Run in CI. Writes photos/<id>.jpg."""
import json
import os
import time
import urllib.request
from pathlib import Path

KEY = os.environ.get("UNSPLASH_ACCESS_KEY", "")
UA = {"User-Agent": "sauditv", "Accept-Version": "v1", "Authorization": "Client-ID " + KEY}


def get(url, headers=None):
    time.sleep(1)
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {"User-Agent": "sauditv"}),
                                timeout=120) as r:
        return r.read()


def main():
    out = Path("photos")
    out.mkdir(exist_ok=True)
    for p in json.load(open(".github/tools/unsplash-picks.json", encoding="utf-8")):
        f = out / f"{p['id']}.jpg"
        if f.exists():
            continue
        sep = "&" if "?" in p["raw"] else "?"
        f.write_bytes(get(p["raw"] + sep + "w=1920&h=1080&fit=crop&crop=entropy&q=82&fm=jpg"))
        try:   # count the download for the photographer (Unsplash API guidelines)
            get(f"https://api.unsplash.com/photos/{p['id']}/download", UA)
        except Exception:
            pass
        print("ok", p["id"])


if __name__ == "__main__":
    main()
