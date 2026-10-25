#!/usr/bin/env python3
"""The settings app's plain-Java logic (tests/java/LogicTest.java): a change of country (CountryChange: news, league,
clubs; never on a reload), a new install's guessed place (PlaceGuess) and default cards, older builds' saved cards without the gone TV shows card (PanelMigrate) and the Quran
radio's stations and volume (RadioStations) and the photos' size for the screen (PhotoSize). Compiled and run with javac and java, without Android: JAVA_HOME's, else Android Studio's JBR, else the PATH's. No JDK at all: SKIP (exit 0).
Then «الأذان» (tests/java/AthanTest.java): the prayer times of PrayerTimes against the page's own computePrayers, run
under node for many cities, methods and days (tests/prayer_times_fixture.mjs; without node, only the answers kept in the
test), the page's Aladhan request built the same way (AladhanTimes, against the page's own code under node), and
AthanPlan's scheduling decisions.

    python3 tests/java_test.py
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver"
CLASSES = ["CountryChange.java", "Leagues.java", "PanelMigrate.java", "PhotoSize.java", "PlaceGuess.java", "RadioStations.java",
           "RadioWhen.java", "SetupState.java", "Tape.java", "Look.java",
           "PrayerTimes.java", "AthanPlan.java", "AthanVoices.java", "AladhanTimes.java"]   # plain Java only (no android.*)
HOME = ROOT / "android/launcher/src/main/java/com/raad/sauditv/home"
HOME_CLASSES = ["RadioChip.java", "HomeLook.java"]   # the launcher's, plain Java too


def jdk():
    """javac and java of one JDK (JAVA_HOME may be a JRE without javac: then Android Studio's JBR, then the PATH's)"""
    ext = ".exe" if os.name == "nt" else ""
    homes = [os.environ.get("JAVA_HOME", ""), r"C:\Program Files\Android\Android Studio\jbr",
             "/Applications/Android Studio.app/Contents/jbr/Contents/Home", "/opt/android-studio/jbr"]
    for h in homes:
        if h and (Path(h) / "bin" / ("javac" + ext)).is_file() and (Path(h) / "bin" / ("java" + ext)).is_file():
            return str(Path(h) / "bin" / ("javac" + ext)), str(Path(h) / "bin" / ("java" + ext))
    javac = shutil.which("javac")
    java = str(Path(javac).parent / ("java" + ext)) if javac else None
    return (javac, java) if java and Path(java).is_file() else (None, None)


def main():
    for f in [SRC / c for c in CLASSES] + [HOME / c for c in HOME_CLASSES]:
        text = f.read_text(encoding="utf-8")
        if "import android." in text:
            print(f"FAIL {f} must stay plain Java (tests/java_test.py compiles it without Android)")
            return 1
    javac, java = jdk()
    if not javac or not java:
        print("SKIP java logic tests: no JDK (set JAVA_HOME, e.g. Android Studio's jbr)")
        return 0
    with tempfile.TemporaryDirectory() as out:
        r = subprocess.run([javac, "-encoding", "UTF-8", "-nowarn", "-Xlint:-options", "--release", "8", "-d", out]
                           + [str(SRC / f) for f in CLASSES] + [str(HOME / f) for f in HOME_CLASSES]
                           + [str(ROOT / "tests/java/LogicTest.java"), str(ROOT / "tests/java/AthanTest.java")],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        if r.returncode != 0:
            print("FAIL java logic tests: javac\n" + r.stdout + r.stderr)
            return 1
        r = subprocess.run([java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", out,
                            "com.raad.sauditv.screensaver.LogicTest"],
                           capture_output=True, text=True, encoding="utf-8", errors="replace")
        sys.stdout.reconfigure(encoding="utf-8", errors="replace") if hasattr(sys.stdout, "reconfigure") else None
        print(r.stdout + r.stderr, end="")
        logic = r.returncode
        # «الأذان»: the page's own prayer times and Aladhan request (node runs them out of src/template.html), a case a line
        args = [java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", out,
                "com.raad.sauditv.screensaver.AthanTest", str(ROOT)]
        node = shutil.which("node")
        if node:
            f = subprocess.run([node, str(ROOT / "tests/prayer_times_fixture.mjs")], capture_output=True, text=True,
                               encoding="utf-8", errors="replace")
            if f.returncode != 0:
                print("FAIL the page's prayer times under node (tests/prayer_times_fixture.mjs)\n" + f.stdout + f.stderr)
                return 1
            page = json.loads(f.stdout)
            lines = []
            for c in page["prayers"]:
                t = c["times"]
                lines.append("|".join(str(x) for x in [c["name"], c["lat"], c["lon"], c["tz"], c["method"], c["day"], c["at"],
                                                       c["offset"], str(c["ramadan"]).lower()]
                                      + [t[k] for k in ("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha")]))
            fixture = Path(out) / "prayer-times.txt"
            fixture.write_text("\n".join(lines) + "\n", encoding="utf-8")
            # the page's Aladhan request for the same settings (AladhanTimes.url): lat, lon, method, zone, day, URL
            urls = Path(out) / "aladhan-urls.txt"
            urls.write_text("".join("\t".join(str(u[k]) for k in ("lat", "lon", "method", "tz", "day", "url")) + "\n"
                                    for u in page["urls"]), encoding="utf-8")
            args += [str(fixture), str(urls)]
        else:
            print("(no node: the prayer times and the Aladhan URL are checked against the answers kept in AthanTest only)")
        r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace")
        print(r.stdout + r.stderr, end="")
        return logic or r.returncode


if __name__ == "__main__":
    sys.exit(main())
