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
        (("fps", "30"), AM + "SET --es fps 30"),
        (("fps", "60"), AM + "SET --es fps 60"),
        (("lowpower", "on"), AM + "SET --es fps 30"),
        (("lowpower", "off"), AM + "SET --es fps 60"),
        (("photos", "saudi,ours,bing"), AM + "SET --es photos 'saudi,ours,bing'"),
        (("photos", "world"), AM + "SET --es photos 'world'"),
        (("photos", ""), AM + "SET --es photos ''"),
        (("athan", "on"), AM + "SET --es athan on"),
        (("athan", "off"), AM + "SET --es athan off"),
        (("athan-test", "asr"), AM + "ATHAN_TEST --es prayer asr"),
        (("athan-test", "fajr", "short"), AM + "ATHAN_TEST --es prayer fajr --es kind short"),
        (("athan-test", "maghrib", "reminder"), AM + "ATHAN_TEST --es prayer maghrib --es kind reminder"),
        (("athan-show",), AM + "ATHAN_SHOW"),
        (("look", "new"), AM + "SET --es look new"),
        (("look", "classic"), AM + "SET --es look classic"),
        (("look", "الجديد"), AM + "SET --es look new"),
        (("rotate", "15"), AM + "SET --es rotate 15"),
        (("rotate", "60"), AM + "SET --es rotate 60"),
        (("facts", "prayer,weather,hijri"), AM + "SET --es facts 'prayer,weather,hijri'"),
        (("facts", ""), AM + "SET --es facts ''"),
    ]:
        code, out = saver(*args)
        check(f"saver.sh {' '.join(args)}: sends «{want[len(AM):]}»", code == 0 and out == want, (code, out))
    for args in [("radio",), ("radio", "maybe"), ("radio-volume", "5"), ("radio-volume", "101"), ("radio-volume", "loud"),
                 ("radio-volume",), ("radio-station",), ("radio-hold", "x"), ("radio-when",), ("radio-when", "always"),
                 ("tape",), ("lowpower",), ("lowpower", "maybe"), ("fps",), ("fps", "45"), ("fps", "30;rm"), ("photos",), ("photos", "saudi;rm -rf"), ("athan",), ("athan", "maybe"), ("athan-test",),
                 ("athan-test", "noon"), ("athan-test", "asr", "loud"), ("look",), ("look", "modern"), ("look", "new;rm"),
                 ("rotate",), ("rotate", "45"), ("rotate", "15;rm"), ("facts",), ("facts", "hijri;rm -rf")]:
        code, out = saver(*args)
        check(f"saver.sh {' '.join(args)}: refused, nothing sent", code != 0 and "am broadcast" not in out, (code, out))
    code, out = saver("help")
    check("saver.sh usage lists the radio commands and the ticker's", code != 0 and all(c in out for c in (
        "radio on|off", "radio-station", "radio-volume", "radio-stations", "radio-test", "radio-hold", "radio-when both|saver|home",
        "tape auto|")), out[-900:])
    check("saver.sh usage lists fps («معدل الإطارات», lowpower its alias)",
          "optimize/saver.sh fps 30|60" in saver("help")[1] and "lowpower on|off" in saver("help")[1], "")
    check("saver.sh usage lists look («شكل الواجهة»: classic, new) and still ends with the athan's (the whole header)",
          "optimize/saver.sh look classic|new" in saver("help")[1] and "optimize/saver.sh athan-show" in saver("help")[1], "")
    check("saver.sh usage lists photos («مصادر الصور»: ours bing world; saudi off for now, read as ours)",
          "optimize/saver.sh photos ours,bing" in saver("help")[1]
          and all(w in saver("help")[1] for w in ("saudi (", "ours (", "bing (", "world (", '"" = ours', "read as ours")), "")
    usage = saver("help")[1]
    check("saver.sh usage lists rotate («مدة كل بطاقة») and facts («سطور شاشة التوقف»: its five ids, up to 4)",
          "optimize/saver.sh rotate 10|15|20|30|60" in usage and "optimize/saver.sh facts prayer,weather,hijri" in usage
          and all(w in usage for w in ("prayer match weather ramadan hijri", "up to 4", "«بطاقات شاشة التوقف»"))
          and "rotate every 30 s" not in usage, usage[-1500:])
    check("saver.sh usage lists the athan's commands (athan on|off, athan-test, athan-show)",
          all(c in usage for c in ("optimize/saver.sh athan on|off", "optimize/saver.sh athan-test fajr|dhuhr|asr|maghrib|isha",
                                   "optimize/saver.sh athan-show")), usage[-900:])
    # athan on also grants the screen over other apps through ADB (the fake client logs every command it is given)
    log = Path(tmp) / "calls.txt"
    logger = Path(tmp) / "fakelog"
    logger.write_text('#!/usr/bin/env bash\necho "$3" >> "' + log.as_posix() + '"\n'
                      'printf \'Broadcast completed: result=0, data="%s"\\n\' "$3"\n', encoding="utf-8", newline="\n")
    logger.chmod(0o755)
    r = subprocess.run([BASH, "optimize/saver.sh", "athan", "on"], cwd=ROOT, env=dict(os.environ, PY=logger.as_posix()),
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    calls = log.read_text(encoding="utf-8").splitlines() if log.exists() else []
    check("saver.sh athan on: grants SYSTEM_ALERT_WINDOW (appops) and then turns the athan on",
          r.returncode == 0 and calls == ["appops set com.raad.sauditv.screensaver SYSTEM_ALERT_WINDOW allow", AM + "SET --es athan on"],
          calls)
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
check("SaverReceiver: takes look (classic|new, Look.parse, checked before saving) and shows it (look: …)",
      'getStringExtra("look")' in rx and "Look.parse(" in rx and rx.index('getStringExtra("look")') < rx.index("p.setLook(")
      and '"\\nlook: " + p.look()' in rx)
check("SaverReceiver: takes rotate (SaverOptions.parseRotate: 10|15|20|30|60) and facts (SaverOptions.parseFacts), checked "
      "before saving; shows rotate: and facts:",
      'getStringExtra("rotate")' in rx and 'getStringExtra("facts")' in rx and "SaverOptions.parseRotate(" in rx
      and "SaverOptions.parseFacts(" in rx and rx.index('getStringExtra("rotate")') < rx.index("p.setCountry(cc)") < rx.index("p.setRotateSec(")
      and rx.index('getStringExtra("facts")') < rx.index("p.setCountry(cc)") < rx.index("p.setSaverFacts(")
      and '"\\nrotate: " + p.rotateSec()' in rx and '"\\nfacts: " + Prefs.join(p.saverFacts(), ",")' in rx)
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
settings = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/SettingsActivity.java").read_text(encoding="utf-8")
check("Prefs: the radio is off by default", 'sp.getBoolean("radioOn", false)' in prefs)
check("Prefs: the settings' one radio choice is stored as radio on/off and radioWhen (what saver.sh radio / radio-when set)",
      re.search(r'void setRadioMode\(String m\) \{\s*sp\.edit\(\)\.putString\("radioWhen", RadioWhen\.modeWhen\(m, radioWhen\(\)\)\)'
                r'\.putBoolean\("radioOn", RadioWhen\.modeOn\(m\)\)\.apply\(\);', prefs) is not None)
check("SaverReceiver: show (GET) has setup_done", '"\\nsetup_done: " + p.setupDone()' in rx)
check("SaverReceiver: takes fps (30|60) and lowpower (true|false, read as 30|60), checked before saving; shows fps: 30|60",
      'getStringExtra("fps")' in rx and 'getStringExtra("lowpower")' in rx and '"error: fps is 30 or 60"' in rx
      and rx.index('getStringExtra("fps")') < rx.index("p.setFps(fps)") and rx.index('getStringExtra("lowpower")') < rx.index("p.setFps(fps)")
      and 'lowPower.equals("true") ? Prefs.FPS_LOW : Prefs.FPS_HIGH' in rx
      and '"\\nfps: " + p.fps()' in rx and "lowpower: " not in rx.split("describe(Context c, Prefs p)")[1])
check("SaverReceiver: takes photos (a list of Prefs.PHOTO_SOURCES, checked before saving) and shows it (photos: …)",
      'getStringExtra("photos")' in rx and "known(Prefs.PHOTO_SOURCES, s)" in rx
      and rx.index('getStringExtra("photos")') < rx.index("p.setCountry(cc)") < rx.index("p.setPhotoSources(photos)")
      and '"\\nphotos: " + Prefs.join(p.photoSources(), ",")' in rx)
check("Prefs: «مصادر الصور» saudi and ours on by default, bing and world off; saudi switched off (SAUDI_PHOTOS)",
      '{"saudi", "صور السعودية", "1"}, {"ours", "صورنا المرفقة", "1"}' in prefs
      and '{"bing", "صورة Bing اليومية", "0"}, {"world", "صور عالمية من ويكيميديا", "0"}' in prefs
      and "static final boolean SAUDI_PHOTOS = false;" in prefs)
check("Prefs: while SAUDI_PHOTOS is off: saudi not offered, the default ours only, a saved saudi read as ours",
      'static boolean photoSourceOffered(String id) { return SAUDI_PHOTOS || !"saudi".equals(id); }' in prefs
      and '(!SAUDI_PHOTOS && "ours".equals(x[0]) && ids.contains("saudi"))' in prefs
      and 'if ("1".equals(x[2]) && photoSourceOffered(x[0])) def.add(x[0]);' in prefs
      and "List<String> out = photoSourcesOf(list(\"photoSources\"));" in prefs
      and "List<String> keep = photoSourcesOf(ids);" in prefs)
check("Prefs: reaches the page as &photos= once picked, and always while SAUDI_PHOTOS is off",
      '(sp.contains("photoSources") || !SAUDI_PHOTOS ? "&photos=" + join(photoSources(), ",") : "")' in prefs)
check("SettingsActivity: the «مصادر الصور» checklist offers only Prefs.photoSourceOffered sources",
      "if (Prefs.photoSourceOffered(x[0])) offered.add(x);" in settings
      and "final String[][] all = offered.toArray(new String[0][]);" in settings)
check("Prefs: «معدل الإطارات» is 30 by default (an int fps, the older lowPower boolean not read); 30 reaches the page as &lowpower=1",
      'sp.getInt("fps", FPS_LOW) == FPS_HIGH ? FPS_HIGH : FPS_LOW' in prefs and "FPS_LOW = 30, FPS_HIGH = 60" in prefs
      and "boolean lowPower() { return fps() == FPS_LOW; }" in prefs and 'getBoolean("lowPower"' not in prefs
      and '(lowPower() ? "&lowpower=1" : "")' in prefs)
check("manifest: SetupActivity is there and exported (the home screen and saver.sh setup open it)",
      re.search(r'android:name="\.SetupActivity"\s+android:exported="true"', man) is not None)
for a in ("ATHAN_TEST", "ATHAN_SHOW"):
    check(f"manifest: SaverReceiver answers {a}", re.search(r'SaverReceiver.*?com\.raad\.sauditv\.screensaver\.' + a, man, re.S) is not None)
check("SaverReceiver: ATHAN_TEST and ATHAN_SHOW go to AthanAdb; SET checks the athan's extras before saving anything; "
      "show (GET) has the athan's lines",
      '"com.raad.sauditv.screensaver.ATHAN_TEST"' in rx and "AthanAdb.test(c, i)" in rx and "AthanAdb.show(c)" in rx
      and rx.index("AthanAdb.check(i)") < rx.index("p.setCountry(cc)") and rx.index("AthanAdb.check(i)") < rx.index("AthanAdb.save(c, i)")
      and "AthanAdb.describe(c)" in rx)
adb = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/AthanAdb.java").read_text(encoding="utf-8")
check("AthanAdb: show lists athan, athan_next, athan_screen_over_apps, athan_exact_alarms and the last event",
      all(f'"\\n{k}: "' in adb for k in ("athan", "athan_next", "athan_screen_over_apps", "athan_exact_alarms", "athan_last")))
check("manifest: the athan's alarm receiver and service are private; the service is a media-playback one; exact alarms "
      "(USE_EXACT_ALARM, SCHEDULE_EXACT_ALARM up to Android 12L), SYSTEM_ALERT_WINDOW, the foreground service's permissions",
      re.search(r'\.AthanReceiver"\s+android:exported="false"', man) is not None
      and re.search(r'\.AthanService"\s+android:exported="false"\s+android:foregroundServiceType="mediaPlayback"', man) is not None
      and re.search(r'\.AthanActivity"\s+android:exported="false"', man) is not None
      and 'android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32"' in man
      and all(f'android.permission.{x}"' in man for x in ("USE_EXACT_ALARM", "SYSTEM_ALERT_WINDOW", "FOREGROUND_SERVICE",
                                                          "FOREGROUND_SERVICE_MEDIA_PLAYBACK", "WAKE_LOCK")))
check("manifest: BootReceiver sets the athan's alarm again after a boot, an update, a new clock or time zone, the exact-alarm "
      "permission changing",
      all(re.search(r'BootReceiver.*?' + re.escape(x), man, re.S) for x in ("BOOT_COMPLETED", "MY_PACKAGE_REPLACED", "TIME_SET",
          "TIMEZONE_CHANGED", "SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
      and "AthanScheduler.schedule(c)" in (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/BootReceiver.java").read_text(encoding="utf-8"))
# the athan's screen over other apps without saver.sh: the TV scripts grant it, the settings ask for it
SAW = "appops set com.raad.sauditv.screensaver SYSTEM_ALERT_WINDOW allow"
inst = (ROOT / "optimize/install.sh").read_text(encoding="utf-8")
saver_block = inst[inst.index('if [ "$what" = saver ]'):inst.index('if [ "$what" = launcher ]')]
check("install.sh saver|all: grants SYSTEM_ALERT_WINDOW with the screensaver's settings", f'"{SAW}"' in saver_block)
setup = (ROOT / "optimize/setup-new-tv.sh").read_text(encoding="utf-8")
check("setup-new-tv.sh: checks the athan's SYSTEM_ALERT_WINDOW (appops get) and grants it when missing (not in verify)",
      "appops get com.raad.sauditv.screensaver SYSTEM_ALERT_WINDOW" in setup and f'tvx "{SAW}"' in setup)
ui = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/AthanSettings.java").read_text(encoding="utf-8")
render = ui[ui.index("void render() {"):ui.index("\n    }\n", ui.index("void render() {"))]
check("settings: «السماح بالظهور فوق التطبيقات» is the section's first row when missing, opening the TV's page only if it "
      "resolves, else saying the athan is only heard and how to grant it",
      render.index("السماح بالظهور فوق التطبيقات") < render.index('a.row("الأذان"') and "resolveActivity" in ui
      and "يُسمع الأذان فقط" in render and "docs/SETUP.md" in ui and "السماح بالمنبّه الدقيق" in render)
check("manifest: the settings pages of both permissions are visible to resolveActivity (queries)",
      'android.settings.action.MANAGE_OVERLAY_PERMISSION' in man and 'android.settings.REQUEST_SCHEDULE_EXACT_ALARM' in man)
ath = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/AthanService.java").read_text(encoding="utf-8")
check("athan without its screen: a toast «اضغط إيقاف مؤقت لإيقاف الأذان» (again while it plays); the remote's pause, "
      "play/pause, play and stop stop it through the media session",
      'STOP_HINT = "اضغط إيقاف مؤقت لإيقاف الأذان"' in ath and "onMediaButtonEvent" in ath
      and all(k in ath for k in ("KEYCODE_MEDIA_PAUSE", "KEYCODE_MEDIA_PLAY_PAUSE", "KEYCODE_MEDIA_STOP", "KEYCODE_MEDIA_PLAY")))
svc = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/RadioService.java").read_text(encoding="utf-8")
check("RadioService: the athan's hook (PAUSE_FOR_ATHAN / RESUME_AFTER_ATHAN, hold) is there and documented",
      'ACTION_PAUSE_FOR_ATHAN = "com.raad.sauditv.screensaver.PAUSE_FOR_ATHAN"' in svc
      and 'ACTION_RESUME_AFTER_ATHAN = "com.raad.sauditv.screensaver.RESUME_AFTER_ATHAN"' in svc
      and "static void hold(Context c, boolean on)" in svc and "athan" in (ROOT / "docs/SETUP.md").read_text(encoding="utf-8"))
athan_prefs = (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/AthanPrefs.java").read_text(encoding="utf-8")
check("athan defaults: every prayer a short alert, a reminder 5 min before («بدون» still in the list)",
      "sp.getString(modeKey(prayer), AthanPlan.MODE_DEFAULT)" in athan_prefs and "sp.getInt(REMINDER, AthanPlan.REMINDER_DEFAULT)" in athan_prefs)
pick = ui[ui.index("private void pickVoice("):ui.index("private void pickCustom(")]
check("settings: a bundled voice picked is kept and heard at once, the list staying open («تم» closes); any close stops "
      "the preview; «صوت من عندي…» still closes it for its own list",
      "prefs.setVoice(fajr, voices.get(i).id);" in pick and "playPreview(voices.get(i));" in pick
      and '.setPositiveButton("تم", null)' in pick and "d.setOnDismissListener(x -> {" in pick and "stopPreview();" in pick
      and "x.dismiss(); pickCustom(fajr);" in pick and "اختر صوتًا لتسمعه" in render)
check("settings: the preview from the voice's asset at the athan's volume, 20 s at most, always released; the radio held "
      "(the athan's hook) only while it plays, and stopped when the settings are left",
      "a.getAssets().openFd(v.file)" in pick and "RadioStations.gain(prefs.volume())" in pick and "PREVIEW_MS = 20000" in pick
      and "p.release();" in pick and "RadioService.hold(a, true);" in pick and "RadioService.hold(a, false);" in pick
      and "athan.stopPreview();" in (ROOT / "android/app/src/main/java/com/raad/sauditv/screensaver/SettingsActivity.java").read_text(encoding="utf-8"))

print()
print(f"{len(failures)} failed" if failures else "all saver.sh tests passed")
sys.exit(1 if failures else 0)
