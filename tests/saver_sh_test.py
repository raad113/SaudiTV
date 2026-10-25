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
        (("radio-when", "saver"), AM + "SET --es radio_when saver"),
        (("radio-when", "both"), AM + "SET --es radio_when both"),
        (("radio-when", "home"), AM + "SET --es radio_when home"),
        (("tape", "gold,btc,cur"), AM + "SET --es tape 'gold,btc,cur'"),
        (("tape", "auto"), AM + "SET --es tape 'auto'"),
        (("lowpower", "on"), AM + "SET --es lowpower true"),
        (("lowpower", "off"), AM + "SET --es lowpower false"),
    ]:
        code, out = saver(*args)
        check(f"saver.sh {' '.join(args)}: sends «{want[len(AM):]}»", code == 0 and out == want, (code, out))
    for args in [("radio",), ("radio", "maybe"), ("radio-volume", "5"), ("radio-volume", "101"), ("radio-volume", "loud"),
                 ("radio-volume",), ("radio-station",), ("radio-hold", "x"), ("radio-when",), ("radio-when", "always"),
                 ("tape",), ("lowpower",), ("lowpower", "maybe")]:
        code, out = saver(*args)
        check(f"saver.sh {' '.join(args)}: refused, nothing sent", code != 0 and "am broadcast" not in out, (code, out))
    code, out = saver("help")
    check("saver.sh usage lists the radio commands and the ticker's", code != 0 and all(c in out for c in (
        "radio on|off", "radio-station", "radio-volume", "radio-stations", "radio-test", "radio-hold", "radio-when both|saver|home",
        "tape auto|")), out[-900:])
    check("saver.sh usage lists lowpower («توفير الطاقة»)", "optimize/saver.sh lowpower on|off" in saver("help")[1], "")
    # the first-run setup, opened on the TV (am start of SetupActivity; the fake client prints the command it was given)
    code, out = saver("setup")
    check("saver.sh setup: opens SetupActivity (am start)", code == 0
          and "am start -n com.raad.sauditv.screensaver/.SetupActivity" in out and "am broadcast" not in out, (code, out))
    code, out = saver("help")
    check("saver.sh usage lists setup", code != 0 and "optimize/saver.sh setup" in out, out[-900:])
    # the TV shows card is gone: its commands are not there any more (refused like any unknown one) nor in the usage
    for args in [("genres", "Drama"), ("langs", "English"), ("minrating", "7")]:
        code, out = saver(*args)
        check(f"saver.sh {args[0]}: gone (refused, nothing sent)", code != 0 and "am broadcast" not in out, (code, out))
    code, out = saver("help")
    check("saver.sh usage: no genres, langs or minrating, and the cards are without shows",
          not re.search(r"\b(genres|langs|minrating|episodes)\b", out) and "local world tech;" in out
          and not re.search(r"tech\s*(#\s*)?shows\b", out), out[-900:])
    sh = (ROOT / "optimize/saver.sh").read_text(encoding="utf-8")
    check("saver.sh: no genres / langs / minrating extras", not re.search(r"--es (genres|langs|minrating)\b", sh))

# the app's side of the same commands
rx = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/SaverReceiver.java").read_text(encoding="utf-8")
man = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
check("SaverReceiver: takes radio, radio_station and radio_volume, and checks them before saving",
      all(f'getStringExtra("{k}")' in rx for k in ("radio", "radio_station", "radio_volume"))
      and rx.index('getStringExtra("radio_volume")') < rx.index("p.setRadioOn("))
check("SaverReceiver: show (GET) lists radio, radio_station, radio_volume, radio_when, tape and the radio's state now",
      all(f'"\\n{k}: "' in rx for k in ("radio", "radio_station", "radio_volume", "radio_when", "tape", "radio_now")))
check("SaverReceiver: no genres, langs or minrating (the TV shows card is gone)",
      not re.search(r'"(genres|langs|minrating)"|\\n(genres|langs|minrating): ', rx), "")
check("SaverReceiver: takes radio_when and tape, checks them (RadioWhen, Tape.parse) before saving",
      'getStringExtra("radio_when")' in rx and 'getStringExtra("tape")' in rx and "Tape.parse(" in rx
      and rx.index('getStringExtra("tape")') < rx.index("p.setTape(") and rx.index('getStringExtra("radio_when")') < rx.index("p.setRadioWhen("))
for a in ("RADIO_STATIONS", "RADIO_TEST", "PAUSE_FOR_ATHAN", "RESUME_AFTER_ATHAN"):
    check(f"manifest: SaverReceiver answers {a}", re.search(r'SaverReceiver.*?com\.raad\.sauditv\.screensaver\.' + a, man, re.S) is not None)
check("manifest: the radio's service and its athan hook need our signature (READ_CONFIG)",
      re.search(r'\.RadioService"\s+android:exported="true"\s+android:permission="com\.raad\.sauditv\.screensaver\.READ_CONFIG"', man)
      and re.search(r'\.RadioReceiver"\s+android:exported="true"\s+android:permission="com\.raad\.sauditv\.screensaver\.READ_CONFIG"', man))
prefs = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/Prefs.java").read_text(encoding="utf-8")
check("Prefs: the radio is off by default", 'sp.getBoolean("radioOn", false)' in prefs)
check("Prefs: the settings' one radio choice is stored as radio on/off and radioWhen (what saver.sh radio / radio-when set)",
      re.search(r'void setRadioMode\(String m\) \{\s*sp\.edit\(\)\.putString\("radioWhen", RadioWhen\.modeWhen\(m, radioWhen\(\)\)\)'
                r'\.putBoolean\("radioOn", RadioWhen\.modeOn\(m\)\)\.apply\(\);', prefs) is not None)
check("SaverReceiver: show (GET) has setup_done", '"\\nsetup_done: " + p.setupDone()' in rx)
check("SaverReceiver: takes lowpower (true|false, checked before saving) and shows it (lowpower: on|off)",
      'getStringExtra("lowpower")' in rx and rx.index('getStringExtra("lowpower")') < rx.index("p.setLowPower(")
      and '"\\nlowpower: " + (p.lowPower() ? "on" : "off")' in rx)
check("Prefs: «توفير الطاقة» is off by default and reaches the page as &lowpower=1",
      'sp.getBoolean("lowPower", false)' in prefs and '(lowPower() ? "&lowpower=1" : "")' in prefs)
check("manifest: SetupActivity is there and exported (the home screen and saver.sh setup open it)",
      re.search(r'android:name="\.SetupActivity"\s+android:exported="true"', man) is not None)
svc = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/RadioService.java").read_text(encoding="utf-8")
check("RadioService: the athan's hook (PAUSE_FOR_ATHAN / RESUME_AFTER_ATHAN, hold) is there and documented",
      'ACTION_PAUSE_FOR_ATHAN = "com.raad.sauditv.screensaver.PAUSE_FOR_ATHAN"' in svc
      and 'ACTION_RESUME_AFTER_ATHAN = "com.raad.sauditv.screensaver.RESUME_AFTER_ATHAN"' in svc
      and "static void hold(Context c, boolean on)" in svc and "athan" in (ROOT / "docs/SETUP.md").read_text(encoding="utf-8"))

print()
print(f"{len(failures)} failed" if failures else "all saver.sh tests passed")
sys.exit(1 if failures else 0)
