// The page's own prayer times, under node, for the Java port (tests/java_test.py hands them to AthanTest):
//  - prayers: its offline times (src/template.html: computePrayers, PRAYER_METHODS and the helpers they use) for
//    several cities, methods and days (PrayerTimes, AthanPlan);
//  - urls: its online request (loadPrayers' Aladhan URL, from CONFIG as the page reads its #fragment: _Q, _num and
//    CONFIG's latitude, longitude, cityTz and prayerMethod) for the settings' values as Prefs.fragment sends them
//    (AladhanTimes.url).
// No browser: the code is cut out of the template and run as it is.
//
//   node tests/prayer_times_fixture.mjs            (prints {"prayers": [...], "urls": [...]} as JSON)
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const T = readFileSync(path.join(ROOT, 'src/template.html'), 'utf8').replace(/\r\n/g, '\n');
const cut = (start, end = '\n}\n') => {
  const i = T.indexOf(start);
  if (i < 0) throw new Error('not in the template: ' + start);
  const j = T.indexOf(end, i);
  return T.slice(i, j + end.length);
};
const code = [
  cut('const pad = ', '\n'),
  cut('const RP_FMT = {};', '\n'),
  cut('function riyadhParts('),
  cut('const placeParts = ', '\n'),
  cut('function hijriMonthNow('),
  cut('const PRAYER_METHODS = {', '\n};\n'),
  cut('function computePrayers(at){'),
].join('\n');
const run = new Function('CONFIG', code + '\nreturn {computePrayers, hijriMonthNow, riyadhParts};');

// city, latitude, longitude, time zone, method, day
const CASES = [
  ['riyadh', 24.7136, 46.6753, 'Asia/Riyadh', 4, '2026-05-12'],
  ['riyadh', 24.7136, 46.6753, 'Asia/Riyadh', 4, '2026-10-25'],
  ['riyadh-ramadan', 24.7136, 46.6753, 'Asia/Riyadh', 4, '2027-02-20'],
  ['makkah', 21.4225, 39.8262, 'Asia/Riyadh', 4, '2026-12-21'],
  ['jeddah-isna', 21.5433, 39.1728, 'Asia/Riyadh', 2, '2026-03-01'],
  ['cairo', 30.0626, 31.2497, 'Africa/Cairo', 5, '2026-07-15'],
  ['cairo', 30.0626, 31.2497, 'Africa/Cairo', 5, '2026-01-15'],
  ['dubai', 25.2048, 55.2708, 'Asia/Dubai', 16, '2026-08-01'],
  ['kuwait', 29.3759, 47.9774, 'Asia/Kuwait', 9, '2026-04-10'],
  ['doha', 25.2854, 51.531, 'Asia/Qatar', 10, '2026-11-05'],
  ['istanbul', 41.0082, 28.9784, 'Europe/Istanbul', 13, '2026-06-01'],
  ['karachi', 24.8607, 67.0011, 'Asia/Karachi', 1, '2026-09-09'],
  ['london-mwl', 51.5072, -0.1276, 'Europe/London', 3, '2026-06-21'],
  ['london-isna', 51.5072, -0.1276, 'Europe/London', 2, '2026-06-21'],
  ['london-egypt', 51.5072, -0.1276, 'Europe/London', 5, '2026-12-21'],
  ['newyork-dst-day', 40.7128, -74.006, 'America/New_York', 2, '2026-03-08'],
  ['oslo-summer', 59.9139, 10.7522, 'Europe/Oslo', 3, '2026-06-21'],
  ['jakarta', -6.2088, 106.8456, 'Asia/Jakarta', 3, '2026-02-02'],
  ['tromso-midnight-sun', 69.6492, 18.9553, 'Europe/Oslo', 3, '2026-06-21'],
  // a day this formula and Aladhan differ by a minute (Asr 15:40 here, 15:41 from Aladhan), as on a household TV on 9047
  ['los-angeles', 34.0522, -118.2437, 'America/Los_Angeles', 2, '2026-10-28'],
];

const out = [];
for (const [name, lat, lon, tz, method, day] of CASES) {
  const CONFIG = { latitude: lat, longitude: lon, cityTz: tz, timezone: tz, prayerMethod: method };
  const f = run(CONFIG);
  const [y, mo, d] = day.split('-').map(Number);
  // the city's local noon of that day (as the page's next days): Date.UTC(noon) less the zone's offset there
  const offAt = t => { const p = f.riyadhParts(new Date(t), tz); return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - t; };
  let at = Date.UTC(y, mo - 1, d, 12);
  at -= offAt(at);
  at = Date.UTC(y, mo - 1, d, 12) - offAt(at);
  const t = f.computePrayers(new Date(at));
  out.push({ name, lat, lon, tz, method, day, at, offset: Math.round(offAt(at) / 60000) / 60,
             ramadan: f.hijriMonthNow(new Date(at)) === 9, times: t });
}

// ---- the page's Aladhan request (loadPrayers), from its #fragment as Prefs.fragment writes it
const prop = name => {
  const m = T.match(new RegExp('\n  ' + name + ': (.*?),[ \t]*(//[^\n]*)?\n'));
  if (!m) throw new Error('CONFIG.' + name + ' not in the template');
  return m[1];
};
const urlOf = new Function('location', 'p', [
  cut('const pad = ', '\n'),
  cut('const _Q = ', '\n'),
  cut('const _num = ', '\n'),
  `const CONFIG = {latitude: ${prop('latitude')}, longitude: ${prop('longitude')}, cityTz: ${prop('cityTz')}, `
    + `prayerMethod: ${prop('prayerMethod')}};`,
  cut('  const url = `https://api.aladhan.com', '`;\n'),
  'return url;',
].join('\n'));
// the settings' latitude and longitude strings (as kept: Prefs.city), method, the city's zone (#ptz), the TV's (#tz), day
const URLS = [
  ['24.6877', '46.7219', 4, 'Asia/Riyadh', 'Asia/Riyadh', '2026-10-25'],
  ['21.4225', '39.8262', 4, 'Asia/Riyadh', 'Asia/Riyadh', '2027-02-05'],
  ['51.50720', '-0.1276', 3, 'Europe/London', 'Asia/Riyadh', '2026-01-09'],
  ['30', '31.2497', 5, 'Africa/Cairo', 'Africa/Cairo', '2026-12-31'],
  ['40.712800', '-74.0060', 2, 'America/New_York', 'America/New_York', '2026-03-08'],
  ['-34.6037', '-58.3816', 3, 'America/Argentina/Buenos_Aires', 'Asia/Riyadh', '2026-07-04'],
  ['18.5944', '-72.3074', 2, 'America/Port-au-Prince', 'America/Port-au-Prince', '2026-11-11'],
  ['25.0000', '55.30', 16, 'Etc/GMT-4', 'Asia/Dubai', '2026-06-01'],
  [' 26.3927', '+50.1059', 4, 'Asia/Riyadh', 'Asia/Riyadh', '2026-08-15'],
  ['abc', '', 13, 'Europe/Istanbul', 'Europe/Istanbul', '2026-04-20'],
  ['0.000001', '1e2', 1, 'Asia/Karachi', 'Asia/Karachi', '2026-09-09'],
  ['34.0522', '-118.2437', 2, 'America/Los_Angeles', 'America/Los_Angeles', '2026-10-28'],   // Los Angeles (the athan regression)
];
const urls = [];
for (const [lat, lon, method, ptz, tvTz, day] of URLS) {
  const [y, mo, d] = day.split('-').map(Number);
  const hash = `#city=x&lat=${lat}&lon=${lon}&tz=${encodeURIComponent(tvTz)}&ptz=${encodeURIComponent(ptz)}&method=${method}`;
  urls.push({ lat, lon, method, tz: ptz, day, url: urlOf({ hash }, { y, mo, d }) });
}
console.log(JSON.stringify({ prayers: out, urls }));
