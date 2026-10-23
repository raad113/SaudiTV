# تركيب سعودي TV · Setting up سعودي TV

العربي أولاً، والإنجليزي تحت. English below.

---

## بالعربي

### ١. على الكمبيوتر
- Git Bash و Python 3. على ويندوز: `winget install -e --id Git.Git` و `winget install -e --id Python.Python.3.12`.
- `git clone https://github.com/raad113/SaudiTV.git` ثم `cd SaudiTV`. كل الأوامر تحت تشتغل من هذا المجلد.
- أول مرة: `optimize/setup.sh` يجهّز `optimize/venv` (عميل ADB بالبايثون، تحميل من pypi.org). السكربت يسويها لك لو ناقصة.
- افصل VPN الكمبيوتر وقت التشغيل: يخرّب البحث عن التلفزيون.
- Android Studio (عشان Java حقته) **بس** إذا بتبني التطبيقات بنفسك (القسم ٩).

### ٢. على التلفزيون
- الإعدادات ← النظام ← حول ← اضغط OK على «Build» سبع مرات ← ارجع ← خيارات المطوّر ← شغّل **USB debugging**
  (و **Network debugging** إذا موجود).
- التلفزيون شغّال وعلى نفس الواي فاي. أول اتصال تطلع «Allow USB debugging?»: اضغط **Allow** وعلّم **Always allow**.
- عنوان التلفزيون: الإعدادات ← الشبكة ← الواي فاي.

### ٣. إعدادات بيتك: `household.env`
انسخ `household.env.example` إلى `household.env` وعدّله: المدينة وإحداثياتها، المنطقة الزمنية، الفرق، بطاقة شاشة التوقف
وبطاقة الواجهة، صف المفضلة، أقل تقييم للمسلسلات، التحديث التلقائي، تطبيقات زيادة تنطفي أو تنحذف، ومدة إطفاء الشاشة.
- الملف **ما يُرفع لـ git أبداً** (فيه مكان سكنك). بدونه: الرياض، أم القرى، الأندية السعودية الكبيرة، وست بطاقات.
- تلفزيون ثاني بإعدادات مختلفة: `household-tv2.env` و `HOUSEHOLD_ENV=household-tv2.env optimize/setup-new-tv.sh <IP>`.
- أرقام الفرق: `optimize/saver.sh find-team الهلال`. المدن السعودية الجاهزة: `optimize/saver.sh cities`.

### ٤. الأمر
```
optimize/setup-new-tv.sh 192.168.x.y         # عنوان التلفزيون؛ بدونه يستخدم optimize/.tv_host أو يدوّر عليه
```
- تجربة تطبع كل تغيير بدون ما تغيّر شي: `--dry-run`. فحص بس: `--verify`.
- تشغيله مرة ثانية آمن: ما يغيّر إلا اللي مختلف، ويسأل [y/N] قبل الحذف وقبل أي تحميل إضافي.
- يركّب التطبيقين من آخر إصدار في GitHub. نسختك المبنية بدل كذا: `LOCAL_APK_DIR=android/out optimize/setup-new-tv.sh <IP>`.

### ٥. وش يغيّر
1. يتأكد من الشبكة ويلقى التلفزيون، ويجهّز `optimize/venv` إذا ناقص.
2. ينظّف ويسرّع: يحفظ حالة التلفزيون (`optimize/backup-*.txt`)، يطفي `optimize/lists/tcl-safe.txt` (تتبّع، إعلانات،
   عروض) و `optimize/extra/disable.txt` و `EXTRA_OFF`، ويسأل قبل حذف `optimize/extra/remove.txt` و `EXTRA_REMOVE`.
   وتسريع (`optimize/tweaks.sh apply`): حركة القوائم 0.5، المتجر ما يشتغل بالخلفية، تجميد التطبيقات المقفلة.
3. يركّب الواجهة وشاشة التوقف (`optimize/install.sh all`) مع مفتاح أمان: إذا ما اشتغلت الواجهة ترجع الأصلية بعد ٥ دقايق.
   **تنبيه:** على بعض التلفزيونات مفتاح الأمان يوقف لما تنقطع جلسة ADB، فتأكد بنفسك إن زر Home يفتح الواجهة
   (`optimize/check-home.sh`).
4. إعدادات التلفزيون: الحركة 0.5، شاشة التوقف حقتنا، و `SLEEP_TIMEOUT_MS` إذا حطيته.
5. إعدادات سعودي TV من `household.env` (رسالة ADB للتطبيق عن طريق `optimize/saver.sh`).
6. إضافات (يسأل قبل كل تحميل): Nuvio Reshaped، TV Bro و SmartTube، وصف المفضلة.
7. فحص وملخص، ويخلّي التلفزيون على الواجهة.

### ٦. خطوات يدوية
- خيارات المطوّر و USB debugging و Allow (فوق).
- تسجيل الدخول بحساب Google على التلفزيون.
- تطبيق VPN على التلفزيون إذا تبي علامة VPN تعني شي: ركّبه من المتجر وسجّل دخول وشغّل الاتصال التلقائي.
- المنطقة الزمنية إذا قال السكربت إنها مختلفة: الإعدادات ← النظام ← التاريخ والوقت.
- **Google TV Streamer** وأجهزة Google TV الجديدة: وضع جوجل المحيطي (`com.google.android.apps.tv.dreamx`) لازم يكون مطفي
  وإلا ما تطلع شاشة التوقف حقتنا: حطه في `EXTRA_OFF` (مع `com.google.android.backdrop`).

### ٧. التراجع
- كل شي تقريباً: `optimize/reset.sh` (الواجهة وشاشة التوقف الأصلية، يرجّع القوائم، يشيل تطبيقاتنا، يرجّع السرعات).
- الواجهة الأصلية بس: `optimize/restore.sh`.
- تطبيق منطفي: `optimize/venv/bin/python -I optimize/tv.py "pm enable <الحزمة>"`.
- تطبيق محذوف: `optimize/venv/bin/python -I optimize/tv.py "cmd package install-existing <الحزمة>"`، أو من المتجر.
- إعداد تلفزيون: `optimize/venv/bin/python -I optimize/tv.py "settings delete <namespace> <key>"`؛ والحركة: `optimize/tweaks.sh restore`.

### ٨. التحديثات
- **الصفحة:** شاشة التوقف تجيب `dist/tv.html` من فرع `main` في هذا المستودع كل ٦ ساعات (شغّال افتراضياً). ما تقبل إلا
  صفحة كاملة فيها `<meta name="sauditv-page"`، والصفحة المحمّلة قبل تركيب أو تحديث التطبيق تنتجاهل (نسخة التطبيق أحدث).
  الآن: `optimize/saver.sh update` (يقول من وين جابها، وأي صفحة معروضة). إيقاف: `optimize/saver.sh autoupdate off`.
  الواجهة الرئيسية تعرض الصفحة اللي داخل تطبيقها، فتتحدّث مع تحديث التطبيق.
- **التطبيقات:** `optimize/install.sh all` يركّب آخر إصدار (فوق المركّب، مع إعداداته، لأن المفتاح نفسه).

### ٩. البناء والإصدار
```
optimize/venv/bin/python -I src/build.py
cd android
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" gradle --no-daemon -q assembleRelease \
  -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
mkdir -p out
cp app/build/outputs/apk/release/app-release.apk out/saudi-tv-screensaver.apk
cp launcher/build/outputs/apk/release/launcher-release.apk out/saudi-tv-home.apk
cd ..
LOCAL_APK_DIR=android/out optimize/install.sh all && optimize/check-home.sh
```
- **رقم النسخة:** ملف `VERSION` (الحين 9035). **لازم يزيد** كل بناء تركّبه، وإلا أندرويد يرفض التركيب.
- **مفتاح التوقيع:** `~/.sauditv/sauditv-release.jks` و `~/.sauditv/keystore.properties` (على ويندوز:
  `C:\Users\<اسمك>\.sauditv\`). **خذ منهم نسخة احتياطية** في مكان آمن (مو في المستودع ولا GitHub): إذا ضاعوا، أي تحديث
  جاي ما يركب فوق التطبيقات الموجودة، ولازم تشيلهم وتركّب من جديد (وتضيع إعداداتهم؛ `setup-new-tv.sh` يرجّعها). بدون
  الملف، البناء يتوقّع بمفتاح debug ويطبع تنبيه. و `optimize/adbkey/` هو مفتاح ADB اللي التلفزيون يثق فيه: خاص، لا تشاركه.
- **إصدار جديد:** ارفع `VERSION`، ابنِ، ادفع للـ `main` (الصفحة)، وبعدين:
  `gh release create v9036 android/out/saudi-tv-screensaver.apk android/out/saudi-tv-home.apk --title v9036 --notes "…"`.

---

## English

### 1. On the PC
- Git Bash and Python 3. On Windows: `winget install -e --id Git.Git` and `winget install -e --id Python.Python.3.12`.
- `git clone https://github.com/raad113/SaudiTV.git`, then `cd SaudiTV`. Every command below runs from that folder.
- The first time, `optimize/setup.sh` creates `optimize/venv` (the pure-Python ADB client, from pypi.org); the setup script
  does it for you if it is missing.
- Disconnect the PC's own VPN while it runs: it hides the TV from `optimize/find-tv.sh`.
- Android Studio (for its Java) **only** to build the apps yourself (section 9).

### 2. On the TV
- Settings > System > About > press OK on "Build" 7 times > back > Developer options > **USB debugging** on
  (and **Network debugging** if listed).
- TV on, same Wi-Fi. On the first connection "Allow USB debugging?" appears: press **Allow** and tick **Always allow**.
- The TV's address: Settings > Network > the Wi-Fi.

### 3. Your household's settings: `household.env`
Copy `household.env.example` to `household.env` and edit it: the city and its coordinates, the time zone, the teams, the
screensaver's and the home screen's cards, the favourites row, the episodes' lowest rating, the page auto-update, extra
apps to turn off or remove, and when the screen turns off.
- git **never tracks** it (it says where you live). Without it: Riyadh, Umm al-Qura, the big Saudi clubs, six cards.
- A second TV with other settings: `household-tv2.env`, and `HOUSEHOLD_ENV=household-tv2.env optimize/setup-new-tv.sh <IP>`.
- Team ids: `optimize/saver.sh find-team Al-Hilal`. Built-in Saudi cities: `optimize/saver.sh cities`.

### 4. The command
```
optimize/setup-new-tv.sh 192.168.x.y         # the TV's address; without it optimize/.tv_host, or a scan
```
- `--dry-run` prints every change without making it; `--verify` only checks.
- Re-running is safe: it only changes what differs, and asks [y/N] before removing apps or downloading extras.
- It installs both apps from the latest GitHub release. Your own build instead:
  `LOCAL_APK_DIR=android/out optimize/setup-new-tv.sh <IP>`.

### 5. What it changes
1. Checks the network, finds the TV, and creates `optimize/venv` if missing.
2. Clean-up and speed: saves the TV's state (`optimize/backup-*.txt`), turns off `optimize/lists/tcl-safe.txt`
   (telemetry, ads, demos), `optimize/extra/disable.txt` and `EXTRA_OFF`, and asks before removing
   `optimize/extra/remove.txt` and `EXTRA_REMOVE`. Speed tweaks (`optimize/tweaks.sh apply`): animations 0.5, the Play
   Store kept out of the background, closed apps frozen.
3. Installs the home screen and the screensaver (`optimize/install.sh all`) with a safety switch: the stock home comes
   back by itself within 5 minutes if ours does not work. **Careful:** on some TVs the switch stops when the ADB session
   ends, so check yourself that Home opens our home screen (`optimize/check-home.sh`).
4. TV settings: animations 0.5, our screensaver selected, and `SLEEP_TIMEOUT_MS` if you set it.
5. The سعودي TV settings from `household.env` (ADB broadcasts to the app, through `optimize/saver.sh`).
6. Extras, each asked first: Nuvio Reshaped, TV Bro and SmartTube, and the favourites row.
7. A check and a summary; it leaves the TV on the home screen.

### 6. Manual steps
- Developer options, USB debugging and Allow (above).
- Sign in to Google on the TV.
- A VPN app on the TV, if the VPN mark should mean something: install it from the Play Store, sign in, turn on its
  auto-connect.
- The time zone, if the script says it differs: Settings > System > Date & time.
- **Google TV Streamer** and newer Google TV devices: Google's ambient mode (`com.google.android.apps.tv.dreamx`) must be
  off or our screensaver never shows: put it in `EXTRA_OFF` (with `com.google.android.backdrop`).

### 7. Undo
- Almost everything: `optimize/reset.sh` (stock home and screensaver, the lists back on, our apps removed, speed tweaks
  back to Android's defaults).
- Only the stock home screen: `optimize/restore.sh`.
- A turned-off app: `optimize/venv/bin/python -I optimize/tv.py "pm enable <package>"`.
- A removed app: `optimize/venv/bin/python -I optimize/tv.py "cmd package install-existing <package>"`, or the Play Store.
- A TV setting: `optimize/venv/bin/python -I optimize/tv.py "settings delete <namespace> <key>"`; animations:
  `optimize/tweaks.sh restore`.

### 8. Updates
- **The page:** the screensaver fetches `dist/tv.html` from this repository's `main` branch every 6 hours (on by
  default). It only accepts a whole page carrying `<meta name="sauditv-page"`, and a page downloaded before the app was
  installed or updated is ignored (the app's own copy is newer). Now: `optimize/saver.sh update` (it says where it
  fetched from and which page is shown). Off: `optimize/saver.sh autoupdate off`. The home screen shows the page inside
  its own APK, so it changes with an app update.
- **The apps:** `optimize/install.sh all` installs the latest release (over the installed apps, keeping their settings:
  same key).

### 9. Building and releasing
The commands are in section ٩ above: `src/build.py`, Gradle 8.10 with Android Studio's JBR, the two APKs copied to
`android/out/` as `saudi-tv-screensaver.apk` and `saudi-tv-home.apk`, then
`LOCAL_APK_DIR=android/out optimize/install.sh all && optimize/check-home.sh`.
- **Version:** the `VERSION` file (9035 now). It **must go up** with every build you install, or Android refuses it as a
  downgrade. `VERSION_CODE=…` (or `GITHUB_RUN_NUMBER=…`) overrides it for one build.
- **Signing key: MUST BACK UP** `~/.sauditv/sauditv-release.jks` and `~/.sauditv/keystore.properties` (Windows:
  `C:\Users\<you>\.sauditv\`) somewhere safe, never in the repository or on GitHub. Without them no later build can update
  the installed apps: they would have to be removed and installed again (their settings go; `setup-new-tv.sh` puts them
  back). `android/build.gradle` reads `keystore.properties` (or the file named by `SAUDITV_SIGNING`); without it, release
  builds are signed with the debug key and Gradle prints a warning. `optimize/adbkey/` is the ADB key the TVs trust:
  private too.
- **A release:** raise `VERSION`, build, push to `main` (the page), then
  `gh release create v9036 android/out/saudi-tv-screensaver.apk android/out/saudi-tv-home.apk --title v9036 --notes "…"`.
  `optimize/install.sh` downloads from `releases/latest/download/`, so the newest release is picked up by itself.

### 10. The tools
| | |
|---|---|
| `optimize/saver.sh` | the screensaver's settings: `show`, `city`, `teams`, `find-team`, `card`, `home-card`, `minrating`, `autoupdate`, `update` |
| `optimize/dock.sh` | the favourites row: `show`, `apps`, `set`, `reset` |
| `optimize/check-home.sh` | opens an app, presses Home, says whether our home screen came back |
| `optimize/start-saver.sh` · `shot.sh` | start the screensaver now · screenshots without navigating |
| `optimize/install.sh` | `saver`, `launcher`, `all`, `extras` (TV Bro and SmartTube) |
| `optimize/run.sh` | the step-by-step setup with measurements before and after; `status` |
| `optimize/keep.sh` · `reset.sh` · `restore.sh` | protected features back on · back to stock · the stock home only |

What was learned on real TCL sets, and what must stay on: [OPTIMIZE.md](OPTIMIZE.md).
