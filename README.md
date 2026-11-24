<h1 align="center">سعودي TV · SaudiTV</h1>

<div dir="rtl">

## وش هو

**سعودي TV** واجهة رئيسية عربية وشاشة توقف سعودية لتلفزيونات Android TV و Google TV: الساعة، التاريخ الهجري
والميلادي، مواقيت الصلاة والأذان، رمضان، مباريات فرقك، الطقس، الأسواق والأخبار، فوق صور من السعودية.
ما يحتاج كمبيوتر.

## التثبيت

1. من **Play Store** في التلفزيون ثبّت تطبيق **Downloader** (من AFTVnews).
2. افتح Downloader واكتب الرمز **4791832** في خانة الرابط، ثم **Go**. أو اكتب الرابط كامل:

   ```
   https://github.com/raad113/SaudiTV/releases/latest/download/saudi-tv-installer.apk
   ```
3. إذا طلب التلفزيون إذن «تثبيت التطبيقات غير المعروفة» لـ Downloader فعّله، ثم اضغط **تثبيت**.

**إذا طلعت لك رسالة «App blocked to protect your device» من Google Play Protect:** كانت تطلع مع النسخ القديمة
(قبل 9.254) بسبب ميزة «زر النجمة»، وانشالت. نزّل التطبيق من جديد بنفس الرمز **4791832**، والرابط دايم يجيب آخر نسخة.
إذا ما زالت تطلع: افتح **Play Store** ← صورة الحساب ← **Play Protect** ← ⚙️ وأوقف «فحص التطبيقات باستخدام Play Protect»
مؤقتًا، ثبّت التطبيق، ثم رجّع الخيار.

## الإعداد

- افتح **سعودي TV** من قائمة التطبيقات، وتطلع لك شاشة **«الإعداد السريع»**: اضغط على كل سطر وتابع إلى أن تصير
  علامته ✓ خضراء (يركّب الشاشة الرئيسية، ويختار شاشة التوقف، ويطلب الأذونات).
- اللي ما يسمح فيه التلفزيون من داخله (جعلها **الشاشة الرئيسية الافتراضية**، وشاشة التوقف على Google TV) يسويه
  **«الإعداد الكامل»**: الإعدادات ← «التطبيق» ← «الإعداد الكامل». تفعّل «خيارات المطوّر» و«تصحيح أخطاء USB» مرة وحدة
  ويشرح لك كيف، ومعه «تراجع» يرجّع كل شي.
- **أجهزة Google TV الحديثة** (مثل Google TV Streamer، وأغلب أجهزة Android 12 وأحدث) ما تفتح منفذ التصحيح العادي،
  فيطلب «الإعداد الكامل» إقران مرة وحدة من **جوالك**:
  1. في «خيارات المطوّر» فعّل **Wireless debugging** (تصحيح الأخطاء اللاسلكي).
  2. يطلع لك في التلفزيون رمز QR: امسحه بجوالك وهو على **نفس الواي فاي**، وتنفتح صفحة الإقران في الجوال.
  3. في التلفزيون اضغط **«فتح الإقران»** ← **Wireless debugging** ← **Pair device with pairing code**، ويطلع رمز من 6 أرقام.
  4. اكتب الأرقام الستة في الجوال واضغط **«إقران»**. بعدها لا تلمس شي: التلفزيون يرجع للإعداد ويكمله لحاله.

  الجوال يكلم التلفزيون مباشرة داخل شبكة البيت، وما يمر شي عبر الإنترنت.
- بعدها يكمل معك «معالج الإعداد»: الشكل والمدينة والبطاقات والإذاعة والفريق، ثم جولة قصيرة في التطبيق. والتحديثات توصل من الإعدادات ← «تحديث التطبيق».

## الإزالة

من الإعدادات ← «التطبيق» ← **«إزالة سعودي TV»**: يرجّع الشاشة الرئيسية وشاشة التوقف الأصلية أول، ويتأكد إنها رجعت،
وبعدين يحذف التطبيقين. لا تحذف الشاشة الرئيسية من إعدادات التلفزيون مباشرة.

</div>

## English

**سعودي TV** is an Arabic home screen and a Saudi screensaver for Android TV and Google TV: clock, Hijri and Gregorian
dates, prayer times and the athan, Ramadan, your teams' matches, weather, markets and news, over photos of Saudi Arabia.

**Install:** get **Downloader** from the Play Store on the TV, enter the code **4791832** (or
`https://github.com/raad113/SaudiTV/releases/latest/download/saudi-tv-installer.apk`), install «سعودي TV» and open it.

- «الإعداد السريع» (quick setup) installs the home screen and opens each TV setting it needs, with a ✓ per step.
- «الإعداد الكامل» (full setup: settings → «التطبيق» → «الإعداد الكامل») makes it the default Home and picks the
  screensaver on Google TV, over the TV's own ADB (Developer options and USB debugging, once), with «تراجع» (undo).
  Newer Google TV devices (e.g. Google TV Streamer, most Android 12+) keep that port closed: turn on **Wireless
  debugging** in Developer options, scan the QR code on the TV with a phone on the same Wi-Fi, press «فتح الإقران» →
  Wireless debugging → Pair device with pairing code, and type the 6 digits on the phone. The TV finishes on its own;
  nothing leaves your home network.
- **"App blocked to protect your device" (Play Protect):** versions before 9.254 asked for Accessibility (the star
  key) and were blocked. Download the latest again with the same code. If it still shows, turn off "Scan apps with Play
  Protect" in the Play Store's Play Protect settings for the install, then turn it back on.
- Then the setup wizard: look, city, cards, radio and team. Updates: settings → «تحديث التطبيق».

**Removing:** settings → «التطبيق» → «إزالة سعودي TV» puts the TV's own home screen and screensaver back first, then
uninstalls both apps.

## Credits · الشكر

- Based on UrTV by tshi-dev, (portions © tshi-dev).
- Home screen adapted from [mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home) (MIT,
  [android/launcher/LICENSE-tv-home](android/launcher/LICENSE-tv-home)).
- Photos: Unsplash photographers, listed in [docs/PHOTO-CREDITS.md](docs/PHOTO-CREDITS.md).
- Countries and cities: [GeoNames](https://www.geonames.org) (CC BY 4.0); the Arabic country names: Unicode CLDR (Unicode
  license). Made by `.github/tools/gen_geo.py`.
- Quran text: [Tanzil Project](https://tanzil.net), Uthmani, verbatim in `assets/quran/quran-uthmani.txt` with its notice
  (Creative Commons Attribution 3.0; changing the text is not allowed). التفسير الميسر: King Fahad Quran Complex, from
  Tanzil's [translations](https://tanzil.net/trans/), for non-commercial use. The Madani mushaf's page table: Tanzil's
  [Quran metadata](https://tanzil.net/docs/quran_metadata) (CC BY 3.0). Made by `.github/tools/fetch_quran.py`.
- Recitations and verse timings: [mp3quran.net](https://mp3quran.net), streamed, nothing bundled.
- أذكار الصباح والمساء: [Seen-Arabic/Morning-And-Evening-Adhkar-DB](https://github.com/Seen-Arabic/Morning-And-Evening-Adhkar-DB)
  (MIT, [assets/adhkar/LICENSE.txt](assets/adhkar/LICENSE.txt)), in `assets/adhkar/adhkar.txt` with one fatha added.
- Fonts: Amiri Quran for the Quran's text (SIL Open Font License, [assets/fonts/AmiriQuran-OFL.txt](assets/fonts/AmiriQuran-OFL.txt)),
  Thmanyah (by Thmanyah), and Noto Naskh Arabic for ﷺ ﴿﴾ (SIL Open Font License,
  `assets/fonts/NotoNaskhArabic-OFL.txt`).
- Athan recordings: «حي على الصلاة», the default athan, a private recording supplied by the project's owner; three from
  [islamweb.net](https://audio.islamweb.net) (© islamweb.net, used with credit); and the others CC0 and CC BY 3.0, each
  credited in [assets/athan/CREDITS.txt](assets/athan/CREDITS.txt) (and in the settings' «الأذان» › «حقوق التسجيلات»).
- Package safety ratings from [UAD-ng](https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation).

Photo credits: [docs/PHOTO-CREDITS.md](docs/PHOTO-CREDITS.md).

## License

MIT, see [LICENSE](LICENSE). The parts listed under Credits keep their own licenses and permissions.
