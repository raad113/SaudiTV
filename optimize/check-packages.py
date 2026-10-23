#!/usr/bin/env python3
"""Rate every package on the TV (or in a debloat list) with the community list from
Universal Android Debloater Next Generation (github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation).

    optimize/venv/bin/python -I optimize/check-packages.py                 every package on the TV
    optimize/venv/bin/python -I optimize/check-packages.py lists/x.txt     only the packages in a list

Ratings: Recommended (safe) / Advanced / Expert / Unsafe. UAD-ng is phone-focused, so most TV packages
are "unknown" — that is not a green light, only "Unsafe" is a hard stop.
"""
import json
import os
import re
import subprocess
import sys
import urllib.request

UAD = ("https://raw.githubusercontent.com/Universal-Debloater-Alliance/"
       "universal-android-debloater-next-generation/main/resources/assets/uad_lists.json")
HERE = os.path.dirname(os.path.abspath(__file__))


def tv_packages():
    out = subprocess.run([sys.executable, "-I", os.path.join(HERE, "tv.py"), "pm list packages"],
                         capture_output=True, text=True, check=True).stdout
    return sorted(l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:"))


def list_packages(path):
    return [l.split()[0] for l in open(path, encoding="utf-8") if l.strip() and not l.lstrip().startswith("#")]


def main():
    pkgs = list_packages(sys.argv[1]) if len(sys.argv) > 1 else tv_packages()
    with urllib.request.urlopen(UAD, timeout=60) as r:
        uad = json.load(r)
    unsafe = 0
    for p in pkgs:
        e = uad.get(p) or {}
        rating = e.get("removal", "unknown")
        desc = re.sub(r"\s+", " ", e.get("description") or "")[:90]
        unsafe += rating == "Unsafe"
        print(f"{rating:12} {p:55} {desc}")
    if unsafe:
        print(f"\n!! {unsafe} package(s) rated Unsafe by UAD-ng — do not disable them.")


if __name__ == "__main__":
    main()
