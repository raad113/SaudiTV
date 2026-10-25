#!/usr/bin/env python3
"""optimize/saver.sh's radio commands, without a TV: a stand-in for the ADB client (PY=…) prints the broadcast it was
asked to send, as `am broadcast` answers. Then the app's side: SaverReceiver takes the same extras and shows them.

    python3 tests/saver_sh_test.py
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
for _s in (sys.stdout, sys.stderr):   # Windows prints in its own code page: Arabic would crash
    if hasattr(_s, "reconfigure"):
        _s.reconfigure(encoding="utf-8", errors="replace")
BASH = shutil.which("bash") or "bash"   # PATH's bash (Git Bash on Windows), not System32's WSL one
failures = []


def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        failures.append(name)


with tempfile.TemporaryDirectory() as tmp:
    fake = Path(tmp) / "fakepy"
    # what am broadcast prints; saver.sh shows only the data (here: the command it was given)
    fake.write_text('#!/usr/bin/env bash\nprintf \'Broadcast completed: result=0, data="%s"\\n\' "$3"\n', encoding="utf-8", newline="\n")
    fake.chmod(0o755)
    env = dict(os.environ, PY=fake.as_posix())

    def saver(*args):
        r = subprocess.run([BASH, "optimize/saver.sh", *args], cwd=ROOT, env=env, capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
        return r.returncode, (r.stdout + r.stderr).strip()

    AM = "am broadcast -n com.raad.sauditv.screensaver/.SaverReceiver -a com.raad.sauditv.screensaver."
    for args, want in [
        (("radio", "on"), AM + "SET --es radio on"),
        (("radio", "off"), AM + "SET --es radio off"),
        (("radio", "true"), AM + "SET --es radio on"),
        (("radio-station", "cairo"), AM + "SET --es radio_station 'cairo'"),
        (("radio-station", "63"), AM + "SET --es radio_station '63'"),
        (("radio-station", "https://example.com/live.mp3"), AM + "SET --es radio_station 'https://example.com/live.mp3'"),
        (("radio-volume", "40"), AM + "SET --es radio_volume 40"),
        (("radio-volume", "100"), AM + "SET --es radio_volume 100"),
        (("radio-stations",), AM + "RADIO_STATIONS"),
        (("radio-stations", "ماهر"), AM + "RADIO_STATIONS --es filter 'ماهر'"),
        (("radio-test",), AM + "RADIO_TEST"),
        (("radio-test", "saudi"), AM + "RADIO_TEST --es station 'saudi'"),
        (("radio-hold", "on"), AM + "PAUSE_FOR_ATHAN"),
        (("radio-hold", "off"), AM + "RESUME_AFTER_ATHAN"),
        (("show",), AM + "GET"),
    ]:
        code, out = saver(*args)
        check(f"saver.sh {' '.join(args)}: sends «{want[len(AM):]}»", code == 0 and out == want, (code, out))
    for args in [("radio",), ("radio", "maybe"), ("radio-volume", "5"), ("radio-volume", "101"), ("radio-volume", "loud"),
                 ("radio-volume",), ("radio-station",), ("radio-hold", "x")]:
        code, out = saver(*args)
        check(f"saver.sh {' '.join(args)}: refused, nothing sent", code != 0 and "am broadcast" not in out, (code, out))
    code, out = saver("help")
    check("saver.sh usage lists the radio commands", code != 0 and all(c in out for c in (
        "radio on|off", "radio-station", "radio-volume", "radio-stations", "radio-test", "radio-hold")), out[-600:])

# the app's side of the same commands
rx = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/SaverReceiver.java").read_text(encoding="utf-8")
man = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
check("SaverReceiver: takes radio, radio_station and radio_volume, and checks them before saving",
      all(f'getStringExtra("{k}")' in rx for k in ("radio", "radio_station", "radio_volume"))
      and rx.index('getStringExtra("radio_volume")') < rx.index("p.setRadioOn("))
check("SaverReceiver: show (GET) lists radio, radio_station, radio_volume and the radio's state now",
      all(f'"\\n{k}: "' in rx for k in ("radio", "radio_station", "radio_volume", "radio_now")))
for a in ("RADIO_STATIONS", "RADIO_TEST", "PAUSE_FOR_ATHAN", "RESUME_AFTER_ATHAN"):
    check(f"manifest: SaverReceiver answers {a}", re.search(r'SaverReceiver.*?com\.raad\.sauditv\.screensaver\.' + a, man, re.S) is not None)
check("manifest: the radio's service and its athan hook need our signature (READ_CONFIG)",
      re.search(r'\.RadioService"\s+android:exported="true"\s+android:permission="com\.raad\.sauditv\.screensaver\.READ_CONFIG"', man)
      and re.search(r'\.RadioReceiver"\s+android:exported="true"\s+android:permission="com\.raad\.sauditv\.screensaver\.READ_CONFIG"', man))
prefs = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/Prefs.java").read_text(encoding="utf-8")
check("Prefs: the radio is off by default", 'sp.getBoolean("radioOn", false)' in prefs)
svc = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/RadioService.java").read_text(encoding="utf-8")
check("RadioService: the athan's hook (PAUSE_FOR_ATHAN / RESUME_AFTER_ATHAN, hold) is there and documented",
      'ACTION_PAUSE_FOR_ATHAN = "com.raad.sauditv.screensaver.PAUSE_FOR_ATHAN"' in svc
      and 'ACTION_RESUME_AFTER_ATHAN = "com.raad.sauditv.screensaver.RESUME_AFTER_ATHAN"' in svc
      and "static void hold(Context c, boolean on)" in svc and "athan" in (ROOT / "docs/SETUP.md").read_text(encoding="utf-8"))

print()
print(f"{len(failures)} failed" if failures else "all saver.sh tests passed")
sys.exit(1 if failures else 0)
