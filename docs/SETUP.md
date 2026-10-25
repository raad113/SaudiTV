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
انسخ `household.env.example` إلى `household.env` وعدّله: المدينة وإحداثياتها ودولتها (`COUNTRY`) ومنطقتها الزمنية، طريقة الحساب
(`METHOD`)، وحدة الحرارة (`UNITS`)، دوري الترتيب (`LEAGUE`)، دولة الأخبار (`NEWS_COUNTRY`)، المنطقة الزمنية للتلفزيون، الفرق، بطاقة شاشة التوقف
وبطاقة الواجهة، صف المفضلة، شكل الأرقام (`NUMERALS`)، شكل التطبيقات وشكل التحديد في الواجهة (`TILE_SHAPE` و `FOCUS_STYLE`)، أقل تقييم للمسلسلات، التحديث التلقائي، تطبيقات زيادة تنطفي أو تنحذف، ومدة إطفاء الشاشة.
- الملف **ما يُرفع لـ git أبداً** (فيه مكان سكنك). بدونه: الرياض، أم القرى، الأندية السعودية الكبيرة، وست بطاقات.
- تلفزيون ثاني بإعدادات مختلفة: `household-tv2.env` و `HOUSEHOLD_ENV=household-tv2.env optimize/setup-new-tv.sh <IP>`.
- أرقام الفرق: `optimize/saver.sh find-team الهلال`، وأندية أي دوري في ESPN: `optimize/saver.sh clubs eng.1` (المنتخبات:
  `fifa.friendly`). المدن السعودية الجاهزة: `optimize/saver.sh cities`، ومدن أي دولة: `optimize/saver.sh cities EG`.
  الدول: `optimize/saver.sh countries`، والدوريات: `optimize/saver.sh leagues`.

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
- **التطبيقات من التلفاز نفسه، بدون كمبيوتر:** إعدادات سعودي TV ← «تحديث التطبيق» يعرض النسخة المثبتة و«محدّث» أو
  «تحديث متوفر: …». تطبيق الإعدادات يفحص كل ٦ ساعات (ومع كل ضغطة على الصف) ملف `update.json` في آخر إصدار
  (`releases/latest/download/update.json`، وإذا ما وصل له: `update.json` في فرع `main`). لما يلقى نسخة أحدث، الواجهة
  الرئيسية تعرض «تحديث متوفر» في الشريط العلوي وتحت «سعودي TV» في الإعدادات السريعة، والضغط عليها يفتح التحديث.
  - «تثبيت» ينزّل الملفات لذاكرة التطبيق، ويتحقق قبل أي تثبيت من حجم كل ملف وبصمته (sha256)، واسم الحزمة، ورقم النسخة،
    وتوقيع مفتاح المشروع. أي خطأ (ما فيه إنترنت، ملف تالف، إلغاء) يحذف الملفات ويترك التطبيقين مثل ما هم.
  - يثبّت الواجهة الرئيسية أول، وبعدها تطبيق الإعدادات نفسه آخر شي (هو اللي يثبّت، فتركيبه يقفله): يقفل ويرجع التلفاز
    للواجهة الرئيسية الجديدة، وأول ما تفتحه بعدها يقول «تم التحديث إلى …».
  - أول مرة يطلب إذن «تثبيت التطبيقات غير المعروفة» لـ «سعودي TV»، مع زر يفتح الشاشة الصحيحة؛ ارجع بزر الرجوع ويكمل.
    التلفاز يطلب موافقتك على كل تثبيت (نافذته هو)، إلا لما تطبيق الإعدادات يحدّث نفسه أو تطبيق ثبّته هو قبل.
  - من ADB: `optimize/saver.sh app-check` (فحص بس، ما يثبّت شي).

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
- **رقم النسخة:** ملف `VERSION` (رقم واحد). **لازم يزيد** كل بناء تركّبه، وإلا أندرويد يرفض التركيب.
- **مفتاح التوقيع:** `~/.sauditv/sauditv-release.jks` و `~/.sauditv/keystore.properties` (على ويندوز:
  `C:\Users\<اسمك>\.sauditv\`). **خذ منهم نسخة احتياطية** في مكان آمن (مو في المستودع ولا GitHub): إذا ضاعوا، أي تحديث
  جاي ما يركب فوق التطبيقات الموجودة، ولازم تشيلهم وتركّب من جديد (وتضيع إعداداتهم؛ `setup-new-tv.sh` يرجّعها). بدون
  الملف، البناء يتوقّع بمفتاح debug ويطبع تنبيه. و `optimize/adbkey/` هو مفتاح ADB اللي التلفزيون يثق فيه: خاص، لا تشاركه.
- **إصدار جديد:** سكربت واحد يسوي كل شي:
  ```
  tools/release.sh --notes "ملاحظة قصيرة بالعربي عن هذا الإصدار"      # أو --notes-file ملف.txt
  ```
  يرفع `VERSION` واحد (أو `--version N`)، يبني الصفحة والتطبيقين ويوقّعهم بمفتاح المشروع (ويرفض مفتاح debug)، يكتب
  `update.json` (رقم النسخة، الملاحظات، ورابط كل APK وبصمته sha256 وحجمه)، يشغّل الفحوصات (`tests/`) وفحص الأسماء
  القديمة والملفات الخاصة، ثم commit و tag `v<رقم>` ويدفع، وينشئ الإصدار في GitHub بثلاث ملفات: `saudi-tv-screensaver.apk`
  و `saudi-tv-home.apk` و `update.json`، وبعدها يتأكد إن GitHub يقدّمهم صح. تجربة كاملة بدون نشر: `--dry-run`.
  - يحتاج: فرع `main` نظيف ومحدّث، `gh` مسجّل دخول، مفتاح التوقيع، Gradle 8.10 و Java حق Android Studio، Python فيه
    fonttools و brotli و pillow (`PYTHON=…`)، و node مع playwright (`CHROMIUM=…` إذا متصفح Playwright ما نزل).
  - الإصدار القديم بخطوات يدوية (لو احتجته): ارفع `VERSION`، ابنِ، ادفع للـ `main` (الصفحة)، وبعدين:
  `gh release create v9036 android/out/saudi-tv-screensaver.apk android/out/saudi-tv-home.apk --title v9036 --notes "…"`.

### ١٠. إذاعة القرآن الكريم
- إعدادات سعودي TV ← «إذاعة القرآن الكريم» (متوقفة افتراضياً): تشغيل وإيقاف، «المحطة» (إذاعة القرآن الكريم من السعودية
  ومن القاهرة أول، ثم قرّاء mp3quran.net، مع بحث و«رابط من عندي»)، «مستوى صوت الإذاعة» (١٠ إلى ١٠٠٪، غير صوت التلفاز)،
  و«معاينة الإذاعة». وفي الإعدادات السريعة للواجهة زر «إذاعة القرآن».
- تشتغل ما دامت الواجهة أو شاشة التوقف قدامك، وتوقف لما يفتح أي تطبيق ثاني (يوتيوب، نتفليكس…) أو تنطفي الشاشة، وترجع
  لما ترجع للواجهة. تحت الساعة «يُتلى الآن: …».
- من ADB: `optimize/saver.sh radio on|off`، `radio-station saudi|cairo|<رقم>|https://…`، `radio-volume 50`،
  `radio-stations [بحث]`، و `radio-test` (يجرّب روابط الإذاعتين من التلفاز نفسه، يعني من خلال الـ VPN).
- **تغيير الدولة:** اختيار مدينة في دولة ثانية من الإعدادات يجيب أخبار الدولة (لو لها قائمة أخبار، وإلا تبقى أخبارك
  ويقولها صف «دولة الأخبار»)، ودوريها الأول في الترتيب، وأول ثلاثة أندية في جدوله. مرة وحدة وقت التغيير، وتقدر تغيّرها بعدين.
  `optimize/saver.sh` ما يسوي كذا: كل قيمة بأمرها.

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
Copy `household.env.example` to `household.env` and edit it: the city, its coordinates, its country (`COUNTRY`) and time
zone, the prayer method (`METHOD`), the temperature unit (`UNITS`), the table's league (`LEAGUE`), the news country
(`NEWS_COUNTRY`), the TV's time zone, the teams, the
screensaver's and the home screen's cards, the favourites row, the number style (`NUMERALS`), the home screen's app
tiles and focused-app look (`TILE_SHAPE`, `FOCUS_STYLE`), the episodes' lowest rating, the page auto-update, extra
apps to turn off or remove, and when the screen turns off.
- git **never tracks** it (it says where you live). Without it: Riyadh, Umm al-Qura, the big Saudi clubs, six cards.
- A second TV with other settings: `household-tv2.env`, and `HOUSEHOLD_ENV=household-tv2.env optimize/setup-new-tv.sh <IP>`.
- Team ids: `optimize/saver.sh find-team Al-Hilal`, and any ESPN league's clubs: `optimize/saver.sh clubs eng.1` (national
  teams: `fifa.friendly`). Built-in Saudi cities: `optimize/saver.sh cities`; another country's: `optimize/saver.sh cities EG`.
  The countries: `optimize/saver.sh countries`; the leagues: `optimize/saver.sh leagues`.

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
- **The apps, from the TV itself, no PC:** سعودي TV's settings > «تحديث التطبيق» shows the installed version and
  «محدّث» (up to date) or «تحديث متوفر: …». The settings app checks every 6 hours (and whenever the row is pressed) the
  latest release's `update.json` (`releases/latest/download/update.json`; if that can't be reached, `update.json` on
  `main`). When there is a newer version, the home screen shows a «تحديث متوفر» pill in its top bar and under «سعودي TV»
  in the quick settings; pressing either opens the update.
  - «تثبيت» downloads to the app's own cache and, before anything is installed, checks each file's size and sha256, its
    package name, its versionCode and the project's signing certificate. Any failure (no internet, a damaged file, a
    cancel) deletes the files and leaves both apps as they were.
  - It installs the home screen first and the settings app itself last (it is the installer, so installing it ends its
    process): it closes, the TV is back on the new home screen, and its next launch says «تم التحديث إلى …».
  - The first time it asks for Android's «install unknown apps» for «سعودي TV», with a button that opens the right
    screen; Back from there carries on. Android asks the user to confirm each install with its own dialog, except when
    the settings app updates itself or an app it installed before.
  - Over ADB: `optimize/saver.sh app-check` (checks only, installs nothing).

### 9. Building and releasing
The commands are in section ٩ above: `src/build.py`, Gradle 8.10 with Android Studio's JBR, the two APKs copied to
`android/out/` as `saudi-tv-screensaver.apk` and `saudi-tv-home.apk`, then
`LOCAL_APK_DIR=android/out optimize/install.sh all && optimize/check-home.sh`.
- **Version:** the `VERSION` file (one number). It **must go up** with every build you install, or Android refuses it as a
  downgrade. `VERSION_CODE=…` (or `GITHUB_RUN_NUMBER=…`) overrides it for one build.
- **Signing key: MUST BACK UP** `~/.sauditv/sauditv-release.jks` and `~/.sauditv/keystore.properties` (Windows:
  `C:\Users\<you>\.sauditv\`) somewhere safe, never in the repository or on GitHub. Without them no later build can update
  the installed apps: they would have to be removed and installed again (their settings go; `setup-new-tv.sh` puts them
  back). `android/build.gradle` reads `keystore.properties` (or the file named by `SAUDITV_SIGNING`); without it, release
  builds are signed with the debug key and Gradle prints a warning. `optimize/adbkey/` is the ADB key the TVs trust:
  private too.
- **A release:** one script does all of it:
  ```
  tools/release.sh --notes "a short Arabic note about this release"      # or --notes-file notes.txt
  ```
  It raises `VERSION` by one (or `--version N`), builds the page and both APKs signed with the project's key (a debug
  key is refused), writes `update.json` (versionCode, versionName, the notes, and each APK's URL, sha256 and size), runs
  the tests (`tests/`) and the old-name and private-files checks, commits, tags `v<N>` and pushes, creates the GitHub
  release with three assets (`saudi-tv-screensaver.apk`, `saudi-tv-home.apk`, `update.json`), and checks what GitHub
  then serves. A full rehearsal that publishes nothing: `--dry-run`.
  - It needs: a clean `main` up to date with GitHub, `gh` signed in, the signing key, Gradle 8.10 and Android Studio's
    JBR, a Python with fonttools, brotli and pillow (`PYTHON=…`), and node with playwright (`CHROMIUM=…` when
    Playwright's own browser is not installed).
  - The manual way, if ever needed: raise `VERSION`, build, push to `main` (the page), then
  `gh release create v9036 android/out/saudi-tv-screensaver.apk android/out/saudi-tv-home.apk --title v9036 --notes "…"`.
  `optimize/install.sh` downloads from `releases/latest/download/`, so the newest release is picked up by itself.

### 10. The tools
| | |
|---|---|
| `optimize/saver.sh` | the screensaver's settings: `show`, `countries`, `cities`, `country`, `city`, `method`, `units`, `leagues`, `league`, `news-country`, `teams`, `find-team`, `clubs`, `card`, `home-card`, `numerals`, `tiles`, `focus`, `sources`, `minrating`, `autoupdate`, `update`, `app-check`, `radio`, `radio-station`, `radio-volume`, `radio-stations`, `radio-test`, `radio-hold` |
| `optimize/dock.sh` | the favourites row: `show`, `apps`, `set`, `reset` |
| `optimize/check-home.sh` | opens an app, presses Home, says whether our home screen came back |
| `optimize/start-saver.sh` · `shot.sh` | start the screensaver now · screenshots without navigating |
| `optimize/install.sh` | `saver`, `launcher`, `all`, `extras` (TV Bro and SmartTube) |
| `tools/release.sh` | a new release of both apps: version, build, `update.json`, tests, tag, GitHub release |
| `optimize/run.sh` | the step-by-step setup with measurements before and after; `status` |
| `optimize/keep.sh` · `reset.sh` · `restore.sh` | protected features back on · back to stock · the stock home only |

### 11. The Quran radio («إذاعة القرآن الكريم»)
- سعودي TV's settings > «إذاعة القرآن الكريم» (off by default): on/off, «المحطة» (the Quran radios from Saudi Arabia and
  from Cairo first, then mp3quran.net's reciters; a search, and «رابط من عندي» for any http(s) stream), «مستوى صوت
  الإذاعة» (10–100 %, apart from the TV's volume) and «معاينة الإذاعة». The home screen's quick settings have a
  «إذاعة القرآن» tile.
- The screensaver app plays it natively (`RadioService`, Android's MediaPlayer: MP3/AAC over HTTP or ICY, and HLS), so a
  page reload never cuts it, while the home screen (or its full-screen card) or the screensaver is in front. Another app
  taking the audio focus (YouTube, Netflix, any player) stops it; it plays again when our home screen or screensaver is
  back in front. Screen off: stopped. A failing stream: the station's next address, then «تعذّر التشغيل» and retries
  after 5, 15, 30, 60, 120, then every 300 s. «يُتلى الآن: …» under the clock while it plays (dimmed and moved with the
  clock on the screensaver).
- Over ADB: `optimize/saver.sh radio on|off`, `radio-station saudi|cairo|<id>|https://…`, `radio-volume 50`,
  `radio-stations [filter]`, `radio-test [id]` (reads each address from the TV itself, so through its VPN).
- **The athan's hook** (for the athan, not built yet): in the screensaver app, `RadioService.hold(context, true)` before
  the athan and `hold(context, false)` after it; from the home app (same signing key), the broadcasts
  `com.raad.sauditv.screensaver.PAUSE_FOR_ATHAN` / `RESUME_AFTER_ATHAN` to `com.raad.sauditv.screensaver/.RadioReceiver`;
  from ADB, `optimize/saver.sh radio-hold on|off`. A hold ends by itself after 15 minutes.
- **A change of country:** picking a city of another country in the settings (its list, or «اكتب اسم مدينتك») brings that
  country's news (when it has a news list; else the news stay and «دولة الأخبار» says so), its top league in the table
  and the top 3 clubs of that table (else a list of October 2026). Once, at the change, never on a reload or a re-pick in
  the same country (older builds' settings count as Saudi Arabia); `optimize/saver.sh` sets each value itself.

What was learned on real TCL sets, and what must stay on: [OPTIMIZE.md](OPTIMIZE.md).
