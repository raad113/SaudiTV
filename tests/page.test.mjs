// Screen tests in a real browser, offline: both pages, every card, card-only mode, old settings links.
//
//   npm install playwright@1.56.1 && npx playwright install chromium     (once)
//   node tests/page.test.mjs                                              (CHROMIUM=… for another Chromium)
//
// Network is cut; the photos and fonts the app serves from the device are served from the repo instead,
// the way PhotoCache.java answers them on the TV. The clock is fixed (FIXED: a Tuesday morning in Riyadh, outside
// Ramadan, an even hour, so the screensaver's sides are not swapped and its photo-only minute is not running): every
// run sees the same day, the same verse and hadith, the same layout, whatever the time it is run.
import { chromium } from 'playwright';
import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const page = name => 'file://' + path.join(ROOT, 'dist', name);
const INDEX = readFileSync(path.join(ROOT, 'photos/index.json'), 'utf8');
const FIXED = new Date('2026-05-12T05:20:00Z');   // 08:20 in Riyadh
let failed = 0;
const check = (name, ok, detail = '') => {
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (ok ? '' : '  -> ' + detail));
  if (!ok) failed++;
};
const LATIN = /[0-9]/, ARABIC = /[٠-٩]/;

const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});

// feeds: {url regex source: RSS text} answered instead of the network; init: a script run before the page's own
// route: (url, route) => a promise when it answers that request itself (else the usual answers below)
// timers: true installs Playwright's fake timers at `at` (time still flows; p.clock.runFor jumps ahead)
async function open(file, hash, { width = 1920, height = 1080, seed = true, at = FIXED, feeds = {}, init = null, route = null, timers = false } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height } });
  if (at && timers) await ctx.clock.install({ time: at });
  else if (at) await ctx.clock.setFixedTime(at);
  await ctx.route(/^https?:/, r => {
    const u = r.request().url(), i = u.indexOf('/main/'), rel = i > 0 ? u.slice(i + 6) : '';
    const own = route && route(u, r);
    if (own) return own;
    for (const [re, body] of Object.entries(feeds))
      if (new RegExp(re).test(u)) return r.fulfill({ body, contentType: 'application/rss+xml; charset=utf-8' });
    if (/^(assets\/(backgrounds|fonts)|photos)\//.test(rel))
      return r.fulfill({ path: path.join(ROOT, rel), headers: { 'Access-Control-Allow-Origin': '*' } });
    return r.abort();
  });
  if (seed) await ctx.addInitScript(i => { try { localStorage.setItem('photoIndex', i); } catch (e) {} }, INDEX);
  if (init) await ctx.addInitScript(init);
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  await p.goto(page(file) + '#' + hash);
  await p.waitForFunction(() => document.documentElement.classList.contains('ready'), null, { timeout: 4000 })
    .catch(() => errors.push('never revealed'));
  await p.waitForTimeout(300);
  return { p, ctx, errors };
}

// ---- both pages, three screen sizes: no errors, shown, clock right, nothing off screen, the digits as set
const stageDigits = () => {
  const hh = document.getElementById('ck-h').textContent, mm = document.getElementById('ck-m').textContent;
  const toN = s => +s.replace(/[٠-٩]/g, d => '٠١٢٣٤٥٦٧٨٩'.indexOf(d));
  const off = [...document.querySelectorAll('.stage *')].filter(e => {
    const b = e.getBoundingClientRect(), cs = getComputedStyle(e);
    return cs.display !== 'none' && b.width && (b.left < -2 || b.right > innerWidth + 2 || b.bottom > innerHeight + 2);
  }).map(e => e.className || e.id || e.tagName).slice(0, 5);
  const text = document.querySelector('.stage').innerText;
  return { hh, mm, h: toN(hh), m: toN(mm), ap: document.getElementById('ck-ap').textContent,
           sub: document.getElementById('clock-sub').textContent, off, hijri: document.getElementById('hijri').textContent,
           latin: (text.match(/[0-9]+/g) || []).filter(x => x !== '500'), arabic: text.match(/[٠-٩]+/g) || [],
           photo: !!document.querySelector('.photo').style.backgroundImage };
};
for (const file of ['saudi-tv.html', 'tv.html']) {
  for (const [w, h] of [[1920, 1080], [1280, 720], [960, 540]]) {
    const { p, ctx, errors } = await open(file, 'city=جدة&lat=21.5433&lon=39.1728', { width: w, height: h });
    const tag = `${file} ${w}x${h}`;
    check(`${tag}: no script errors, page shown`, !errors.length, errors.join('; '));
    const r = await p.evaluate(stageDigits);
    check(`${tag}: 12-hour clock in 0-9 (the default)`, /^[0-9]{1,2}$/.test(r.hh) && r.h === 8
      && /^[0-9]{2}$/.test(r.mm) && r.m === 20 && r.ap === 'ص', JSON.stringify(r));
    check(`${tag}: city from the settings`, r.sub === 'بتوقيت جدة', r.sub);
    check(`${tag}: nothing off screen`, !r.off.length, r.off.join(', '));
    check(`${tag}: no Arabic-Indic digits by default (Hijri date too)`, !r.arabic.length && LATIN.test(r.hijri),
      r.arabic.join(' ') + ' | ' + r.hijri);
    check(`${tag}: a background photo`, r.photo);
    await ctx.close();
  }
}
// «شكل الأرقام» ٠ ١ ٢ ٣: every number on the page, the clock and both dates included
for (const file of ['saudi-tv.html', 'tv.html']) {
  const { p, ctx, errors } = await open(file, 'city=جدة&lat=21.5433&lon=39.1728&numerals=arab');
  const r = await p.evaluate(stageDigits);
  check(`${file} #numerals=arab: clock, dates and everything else in ٠-٩, no Latin digits (except S&P 500)`,
    !errors.length && r.hh === '٨' && r.mm === '٢٠' && !r.latin.length && ARABIC.test(r.hijri), JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- every card beside the clock, with sample data where the card needs the network
const SAMPLE = {
  forecast: () => { FORECAST = [0, 1, 2, 3, 4].map(i => ({ date: new Date(Date.now() + i * 864e5).toISOString().slice(0, 10), code: 2, hi: 37 - i, lo: 24 - i })); },
  matches: () => {
    const ev = (id, h, a, hrs) => ({ idEvent: id, strHomeTeam: h, strAwayTeam: a, strTimestamp: new Date(Date.now() + hrs * 36e5).toISOString() });
    NEXT_LIST = { '136013': [ev('a', 'Al-Hilal', 'Al-Nassr', 3), ev('b', 'Al-Ahli', 'Al-Hilal', 80)] };
  },
  // the city's news: four items (one page shows three), an English one among them
  local: () => {
    NEWS.local = [0, 1, 2, 3].map(i => ({ headline: i ? 'خبر محلي ' + i : 'City council approves the new park', source: i ? 'جريدة' : 'City Herald', t: Date.now() - i * 36e5, link: '' }));
  },
  // two sources: a card page holds at most two items of one source (newsCardPages)
  world: () => { NEWS.world = [0, 1, 2, 3].map(i => ({ headline: 'خبر من العالم ' + i, source: i % 2 ? 'العربية' : 'سكاي نيوز عربية', t: Date.now() - i * 36e5, link: '' })); },
};
const EXPECT = { prayers: [6, 'مواقيت اليوم'], forecast: [5, 'الأيام القادمة · الرياض'], matches: [2, 'مباريات فرقك'],
                 verse: [2, 'آية اليوم'], hadith: [2, 'حديث اليوم'], local: [3, 'أخبار الرياض'],
                 world: [3, 'أخبار العالم'] };
for (const [panel, [rows, title]] of Object.entries(EXPECT)) {
  const { p, ctx, errors } = await open('tv.html', 'teams=136013&panel=' + panel);
  if (SAMPLE[panel]) await p.evaluate(`(${SAMPLE[panel]})(); renderPanel(false);`);
  if (panel === 'hadith') await p.waitForFunction(() => !!HADITH_DAY, null, { timeout: 40000 }).catch(() => {});   // offline: a bundled one
  const r = await p.evaluate(() => ({ n: document.querySelectorAll('#week-list li').length,
    hd: document.getElementById('week-hd').textContent, shown: getComputedStyle(document.querySelector('.week')).display !== 'none' }));
  check(`card ${panel}: no errors`, !errors.length, errors.join('; '));
  check(`card ${panel}: ${rows} rows, title right`, r.shown && r.n === rows && r.hd === title, JSON.stringify(r));
  await ctx.close();
}
{
  const { p, ctx } = await open('tv.html', 'panel=verse');
  const r = await p.evaluate(() => {
    const day = riyadhDayNumber, out = [];
    for (const k of [0, 1]) { window.riyadhDayNumber = () => day() + k; renderPanel(false);
      out.push([document.getElementById('week-hd').textContent, document.querySelector('#week-list .quote').textContent]); }
    return out;
  });
  check('card verse: a verse of the Quran every day (no hadith), another one the next day',
    r.every(([t, q]) => t === 'آية اليوم' && q.startsWith('﴿')) && r[0][1] !== r[1][1], JSON.stringify(r));
  await ctx.close();
}
{
  const { p, ctx, errors } = await open('tv.html', 'panel=hadith');
  await p.waitForFunction(() => !!HADITH_DAY, null, { timeout: 40000 }).catch(() => {});
  const r = await p.evaluate(() => ({ src: document.querySelector('#week-list .src').textContent, n: HADITH_DAY.n,
    bundled: BUKHARI.fallback.some(h => h.n === HADITH_DAY.n && h.head === HADITH_DAY.head && hadithMatn(h.text) === HADITH_DAY.matn),
    saw: !!document.querySelector('#week-list .quote .saw'), q: document.querySelector('#week-list .quote').textContent }));
  check('card hadith offline: a bundled narration of Sahih al-Bukhari in the short form («عن <companion> …: «…»»), «صحيح البخاري، رقم N»',
    !errors.length && r.bundled && r.src === 'صحيح البخاري، رقم ' + r.n && r.saw && /^عن .+ رضي الله (عنه|عنها|عنهما) .*ﷺ.*: «.+»$/.test(r.q)
    && !/حدثنا|أخبرنا|حَدَّثَنَا|أَخْبَرَنَا/.test(r.q), JSON.stringify(r) + errors.join('; '));
  const same = await p.evaluate(async () => { const a = HADITH_DAY.n; await loadHadith(); renderPanel(false); return [a, HADITH_DAY.n]; });
  check('card hadith: the same one all day', same[0] === same[1], JSON.stringify(same));
  await ctx.close();
}
{
  // online: the day's number from the short list, fetched from jsDelivr once, kept for the day
  const B = JSON.parse(readFileSync(path.join(ROOT, 'assets/shared/bukhari.json'), 'utf8'));
  const [n, head] = B.short.find(([k]) => !B.fallback.some(h => h.n === k));   // not bundled: it has to be fetched
  const text = 'حَدَّثَنَا فُلاَنٌ، عَنْ أَبِي هُرَيْرَةَ، قَالَ قَالَ رَسُولُ اللَّهِ صلى الله عليه وسلم ‏ "‏ كَلِمَةٌ طَيِّبَةٌ صَدَقَةٌ ‏"‏‏.‏';
  const api = JSON.stringify({ hadiths: [{ hadithnumber: n, arabicnumber: n, text }] });
  const { p, ctx, errors } = await open('tv.html', 'panel=hadith', { feeds: { 'hadith-api@1/editions/ara-bukhari/\\d+\\.min\\.json': api } });
  await p.evaluate(e => { BUKHARI.short = [e]; HADITH_DAY = null; try { localStorage.removeItem(HADITH_KEY); } catch (x) {} }, [n, head]);
  const r = await p.evaluate(async () => { const ok = await loadHadith(); renderPanel(false);
    return { ok, n: HADITH_DAY.n, kept: JSON.parse(localStorage.getItem(HADITH_KEY)).n, src: document.querySelector('#week-list .src').textContent,
             q: document.querySelector('#week-list .quote').textContent }; });
  check('card hadith online: fetched from the ara-bukhari edition, shown in the short form, kept for the day', !errors.length
    && r.ok && r.n === n && r.kept === n && r.src === 'صحيح البخاري، رقم ' + n && r.q.startsWith(head.replace(/ﷺ/g, 'ﷺ'))
    && r.q.endsWith('«كَلِمَةٌ طَيِّبَةٌ صَدَقَةٌ»') && !r.q.includes('فُلاَن'), JSON.stringify(r) + errors.join('; '));
  // a fetched narration that is not the one the head was made for (no single quote of his words): the bundled one
  await p.evaluate(() => { HADITH_DAY = null; try { localStorage.removeItem(HADITH_KEY); } catch (x) {} });
  await ctx.route(/hadith-api/, rr => rr.fulfill({ body: JSON.stringify({ hadiths: [{ hadithnumber: n, text: 'حدثنا فلان قال كذا' }] }), contentType: 'application/json' }));
  const off = await p.evaluate(async () => [await loadHadith(), HADITH_DAY.offline, BUKHARI.fallback.some(h => h.n === HADITH_DAY.n)]);
  check('card hadith: a fetched text that cannot be split as expected is not shown (a bundled one instead)',
    JSON.stringify(off) === '[false,true,true]', JSON.stringify(off));
  await ctx.close();
}

{
  const { p, ctx, errors } = await open('tv.html', 'panel=local');
  await p.evaluate(`(${SAMPLE.local})(); renderPanel(false);`);
  const r = await p.evaluate(() => [...document.querySelectorAll('#week-list .nw')].map(e => e.getAttribute('dir') || ''));
  check('card local: an English title reads left to right (dir=auto), Arabic ones as the page', !errors.length
    && r.join(',') === 'auto,,', JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- a news page's picked sources: read together, each story once, newest first, each with its own label;
// a Google search standing in for a site keeps only that site's items
{
  const ago = h => new Date(FIXED.getTime() - h * 36e5).toUTCString();
  const rss = items => `<?xml version="1.0"?><rss><channel>${items.map(([t, h, src, url]) => `<item><title>${t}</title>`
    + `<link>https://example.com/${encodeURIComponent(t)}</link><pubDate>${ago(h)}</pubDate>`
    + (src ? `<source url="${url}">${src}</source>` : '') + '</item>').join('')}</channel></rss>`;
  const feeds = {
    'skynewsarabia\\.com/rss': rss([['خبر سكاي الأحدث', 1], ['خبر مشترك', 5]]),
    'feeds\\.bbci\\.co\\.uk/arabic': rss([['خبر بي بي سي', 3], ['خبر مشترك', 4]]),
    'news\\.google\\.com/rss/search\\?q=site%3Aalarabiya\\.net': rss([['خبر العربية - العربية', 2, 'العربية', 'https://www.alarabiya.net'],
                                                                      ['خبر من موقع آخر - موقع', 0.5, 'موقع', 'https://other.example']]),
  };
  const { p, ctx, errors } = await open('tv.html', 'panel=world&src_world=skynews,bbc,alarabiya,cnn', { feeds });
  await p.waitForFunction(() => NEWS.world.length > 0, null, { timeout: 20000 }).catch(() => {});
  const r = await p.evaluate(() => NEWS.world.map(x => x.headline + ' | ' + x.source));
  check('world news: the picked sources together, each story once, newest first, labelled; Google kept to the site',
    !errors.length && JSON.stringify(r) === JSON.stringify(['خبر سكاي الأحدث | سكاي نيوز عربية', 'خبر العربية | العربية',
      'خبر بي بي سي | بي بي سي عربي', 'خبر مشترك | بي بي سي عربي']), JSON.stringify(r) + errors.join('; '));
  const d = await p.evaluate(() => [newsSources('world').map(x => x.id).join(','), (CONFIG.sources.world = [], newsSources('world').map(x => x.id).join(','))]);
  check('news sources: the picks from the settings; none valid = the page\'s defaults', d[0] === 'alarabiya,skynews,bbc'
    && d[1] === 'alarabiya,skynews,bbc', JSON.stringify(d));
  await ctx.close();
}

// ---- every verse and every bundled hadith fits the card: drawn, clear of the clock and the bottom bar
for (const [width, height] of [[1920, 1080], [1280, 720], [960, 540]]) {
  for (const panel of ['verse', 'hadith']) {
    const { p, ctx, errors } = await open('tv.html', 'panel=' + panel, { width, height });
    const r = await p.evaluate(P => {
      const day = riyadhDayNumber(), card = document.querySelector('.week'), bad = [];
      const items = P === 'verse' ? VERSES.map((_, k) => () => { window.riyadhDayNumber = () => day + k; })
                                  : BUKHARI.fallback.map(h => () => { HADITH_DAY = { day, n: h.n, head: h.head, matn: hadithMatn(h.text) }; });
      let tallest = 0;
      for (const set of items) {
        set();
        renderPanel(false);
        const c = card.getBoundingClientRect(), clock = document.querySelector('.clockwrap').getBoundingClientRect();
        const bar = document.querySelector('.bottom').getBoundingClientRect(), top = document.querySelector('.top').getBoundingClientRect();
        tallest = Math.max(tallest, c.height);
        // a card beside the clock, not a page: clear of the top line, the clock and the bottom bar, at most 42% high
        if (c.top < top.bottom || c.bottom > bar.top - 4 || c.right > clock.left - 4 || c.height > 0.42 * innerHeight)
          bad.push(`${Math.round(c.top)}-${Math.round(c.bottom)}/${Math.round(bar.top)} ` + card.querySelector('.quote').textContent.slice(0, 40));
      }
      return { n: items.length, bad, tallest: Math.round(tallest) };
    }, panel);
    check(`every ${panel === 'verse' ? 'verse' : 'bundled hadith'} fits the card at ${width}x${height} (${r.n}, tallest ${r.tallest}px)`,
      !errors.length && !r.bad.length, `${r.bad.length} spill: ` + r.bad.slice(0, 3).join(' | ') + errors.join('; '));
    await ctx.close();
  }
}

// ---- worldwide: a card page holds at most two headlines of one source; the card's later page takes the rest
{
  const { p, ctx, errors } = await open('tv.html', 'panel=news');
  const r = await p.evaluate(() => {
    const list = [0, 1, 2, 3, 4, 5].map(i => ({ headline: 'خبر ' + i, source: i < 4 ? 'مصدر نشيط' : 'مصدر آخر', t: Date.now() - i * 6e5, link: '' }));
    const pages = newsCardPages(list).map(pg => pg.map(x => x.headline.slice(4)).join(''));
    const one = newsCardPages(list.slice(0, 4)).map(pg => pg.length);
    NEWS.news = list; LIST_PAGE = 0; renderPanel(false);
    const shown = [...document.querySelectorAll('#week-list .dt')].map(e => e.textContent.split(' · ')[0]);
    return { pages, one, shown, paged: newsPaged('news') };
  });
  check('news cap: at most 2 headlines of a source per card page, the others fill it, the rest on the next page',
    !errors.length && JSON.stringify(r.pages) === '["014","235"]' && JSON.stringify(r.one) === '[2,2]'
    && r.shown.filter(s => s === 'مصدر نشيط').length === 2 && r.shown.length === 3 && r.paged, JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- worldwide: the country news («أخبار مصر»), its sources and Google / Bing in the country's language
{
  const { p, ctx, errors } = await open('tv.html', 'panel=news&newscc=EG&newsname=' + encodeURIComponent('مصر') + '&newslang=ar');
  const r = await p.evaluate(() => ({ hd: document.getElementById('week-hd').textContent, def: newsSources('news').map(x => x.id).join(','),
    all: countryNews('EG', 'مصر', 'ar', '').map(x => x.id).join(','),
    fr: countryNews('FR', 'فرنسا', 'fr', 'France').filter(x => x.on).map(x => x.id + ' ' + (x.url || x.bing)).join(' | '),
    sa: countryNews('SA', 'السعودية', 'ar', '').filter(x => x.on).map(x => x.id).join(',') }));
  check('country news: «أخبار مصر», Egypt\'s outlets by default, Google and Bing after them; France: Google and Bing in French; Saudi as before',
    !errors.length && r.hd === 'أخبار مصر' && r.def === 'ahram,youm7,almasryalyoum' && r.all.endsWith(',google,bing')
    && /^google https:\/\/news\.google\.com\/rss\?hl=fr&gl=FR&ceid=FR:fr \| bing https:\/\/www\.bing\.com\/news\?format=rss&mkt=fr-FR$/.test(r.fr)
    && r.sa === 'aawsat,okaz,sabq,spa', JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- worldwide: an outlet whose Google search does not answer (Google News gives the TVs' VPN HTTP 403) is read from
// a Bing News search of its site instead, kept to its own links and labelled with its name
{
  const at = new Date(FIXED.getTime() - 36e5).toUTCString();
  const bing = `<?xml version="1.0"?><rss><channel><item><title>خبر من اليوم السابع</title><link>https://www.youm7.com/story/1</link>`
    + `<pubDate>${at}</pubDate></item><item><title>خبر من موقع آخر</title><link>https://other.example/2</link><pubDate>${at}</pubDate></item></channel></rss>`;
  const { p, ctx, errors } = await open('tv.html', 'panel=news&newscc=EG&newsname=' + encodeURIComponent('مصر') + '&newslang=ar',
    { feeds: { 'bing\\.com/news/search\\?q=site%3Ayoum7\\.com': bing } });
  await p.waitForFunction(() => NEWS.news.length > 0, null, { timeout: 20000 }).catch(() => {});
  const r = await p.evaluate(() => NEWS.news.map(x => x.headline + ' | ' + x.source));
  check('country news: Bing stands in for an outlet Google does not answer, its own links only, its name',
    !errors.length && JSON.stringify(r) === JSON.stringify(['خبر من اليوم السابع | اليوم السابع']), JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- worldwide: the table's league in its title; team ids of every kind; °F
{
  const { p, ctx, errors } = await open('tv.html', 'panel=standings&league=eng.1&leaguename=' + encodeURIComponent('الدوري الإنجليزي')
    + '&teams=E359@eng.1,138995@tsdb.4829,136013,bogus&units=f');
  const r = await p.evaluate(() => ({ hd: document.getElementById('week-hd').textContent, ids: CONFIG.teamIds.join(','), units: CONFIG.units,
    url: espnStandingsUrl(CONFIG.league), espn: JSON.stringify(espnOf('E359@eng.1')), tsdb: tsdbOf('138995@tsdb.4829') }));
  check('standings: «ترتيب الدوري الإنجليزي» from ESPN\'s eng.1; ESPN, TheSportsDB-league and older ids; units °F',
    !errors.length && r.hd === 'ترتيب الدوري الإنجليزي' && r.ids === 'E359@eng.1,138995@tsdb.4829,136013' && r.units === 'f'
    && r.url.endsWith('/soccer/eng.1/standings') && r.espn === '{"espn":"359","league":"eng.1"}' && r.tsdb === '138995', JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}
{
  const { p, ctx, errors } = await open('tv.html', 'panel=standings');
  const hd = await p.evaluate(() => document.getElementById('week-hd').textContent);
  check('standings: older settings links keep «ترتيب دوري روشن»', !errors.length && hd === 'ترتيب دوري روشن', hd + errors.join('; '));
  await ctx.close();
}

// ---- worldwide: the city's time zone for its prayer times, «بتوقيت <city>» when it is not the TV's
{
  const cairo = 'city=' + encodeURIComponent('القاهرة') + '&lat=30.0626&lon=31.2497&method=5&panel=prayers';
  const a = await open('tv.html', cairo + '&tz=America%2FLos_Angeles&ptz=Africa%2FCairo');
  const r = await a.p.evaluate(() => ({ hd: document.getElementById('week-hd').textContent, sub: document.getElementById('clock-sub').textContent,
    label: document.getElementById('prayer-label').textContent, cairo: placeParts().h, tv: riyadhParts().h }));
  check('another time zone: «بتوقيت القاهرة» by the prayers, the clock «التوقيت المحلي», the prayers in Cairo time',
    !a.errors.length && r.hd === 'مواقيت اليوم · بتوقيت القاهرة' && r.sub === 'التوقيت المحلي' && r.label.endsWith('· بتوقيت القاهرة')
    && ((r.cairo - r.tv + 24) % 24) === 10, JSON.stringify(r) + a.errors.join('; '));
  await a.ctx.close();
  const b = await open('tv.html', cairo + '&tz=Africa%2FCairo&ptz=Africa%2FCairo');
  const s = await b.p.evaluate(() => [document.getElementById('week-hd').textContent, document.getElementById('clock-sub').textContent]);
  check('the same time zone: no note, the clock «بتوقيت القاهرة»', !b.errors.length && s[0] === 'مواقيت اليوم' && s[1] === 'بتوقيت القاهرة',
    JSON.stringify(s) + b.errors.join('; '));
  await b.ctx.close();
}

// ---- worldwide: the offline prayer times of each method, and far north in summer (no missing Fajr or Isha)
{
  const { p, ctx, errors } = await open('tv.html', 'city=x&lat=30.0626&lon=31.2497&ptz=Africa%2FCairo&panel=prayers');
  const r = await p.evaluate(() => {
    const m = s => { const [h, mi] = s.split(':').map(Number); return h * 60 + mi; };
    const at = new Date('2026-05-12T10:00:00Z'), out = {};
    for (const k of [1, 2, 3, 4, 5, 9, 10, 13, 16]) { CONFIG.prayerMethod = k; const t = computePrayers(at); out[k] = [m(t.Fajr), m(t.Isha), m(t.Maghrib)]; }
    CONFIG.latitude = 51.5072; CONFIG.longitude = -0.1276; CONFIG.cityTz = 'Europe/London';
    const london = []; for (const k of [3, 5, 2]) { CONFIG.prayerMethod = k; london.push(computePrayers(new Date('2026-06-21T12:00:00Z'))); }
    return { out, london };
  });
  const o = r.out, ok = o[5][0] < o[3][0] && o[2][0] > o[3][0] && o[4][1] === o[4][2] + 90 && o[10][1] === o[10][2] + 90
    && o[5][1] > o[2][1] && o[3][1] !== o[16][1] && Object.values(o).every(v => v.every(Number.isFinite))
    && r.london.every(t => /^\d\d:\d\d$/.test(t.Fajr) && /^\d\d:\d\d$/.test(t.Isha) && t.Fajr < t.Sunrise && t.Isha > t.Maghrib);
  check('offline prayer times: each method its own Fajr and Isha (Egypt 19.5°, ISNA 15°, Umm al-Qura and Qatar +90 min); London in June',
    !errors.length && ok, JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- the VPN badge: hidden when the TV has no VPN app at all; an installed one that is off still shows ✕
for (const [label, native, want] of [
  ['no VPN app installed: no badge, no flag', { vpnApp: false, vpnState: 0 }, 'hidden'],
  ['a VPN app, disconnected: the red ✕', { vpnApp: true, vpnState: 0 }, 'vpn off ✕'],
  ['a VPN app, working: ✓', { vpnApp: true, vpnState: 1 }, 'vpn on ✓'],
  ['an older app without vpnApp: as before (✕)', { vpnState: 0 }, 'vpn off ✕'],
]) {
  const init = new Function(`window.SaudiTVNative = {vpn: () => false, vpnCountry: () => ''${'vpnApp' in native ? `, vpnApp: () => ${native.vpnApp}` : ''}, vpnState: () => ${native.vpnState}};`);
  const { p, ctx, errors } = await open('tv.html', 'home=1&panel=prayers', { init });
  await p.waitForTimeout(500);
  const r = await p.evaluate(() => { const el = document.getElementById('vpn');
    return el.hidden ? 'hidden' : el.className + ' ' + el.querySelector('b').textContent; });
  check('VPN badge, ' + label, !errors.length && r === want, r + errors.join('; '));
  await ctx.close();
}

// ---- old settings links and «لا شيء». The TV shows card is gone: panel=shows (an older settings app) is the prayers
// card, and the old #week and the shows' filters (#genres, #langs, #shows, #minrating) are ignored
for (const [hash, panel, shown] of [['', 'prayers', true], ['week=0', 'prayers', true], ['week=1', 'prayers', true],
                                     ['panel=none', 'none', false], ['panel=bogus', 'prayers', true],
                                     ['panel=shows', 'prayers', true],
                                     ['panel=news&genres=Drama&langs=English&shows=82,169&minrating=8&week=1', 'news', true]]) {
  const { p, ctx, errors } = await open('tv.html', hash);
  const r = await p.evaluate(() => [CONFIG.panel, getComputedStyle(document.querySelector('.week')).display !== 'none']);
  check(`link #${hash || '(empty)'} → ${panel}, card ${shown ? 'shown' : 'hidden'}`,
    !errors.length && r[0] === panel && r[1] === shown, JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- card-only mode for the launcher: transparent, only the card, no photo
for (const panel of ['prayers', 'verse', 'hadith']) {
  const { p, ctx, errors } = await open('tv.html', `widget=1&panel=${panel}`, { width: 461, height: 250 });
  const r = await p.evaluate(() => ({
    bg: getComputedStyle(document.body).backgroundColor,
    visible: [...document.querySelectorAll('.top,.clockwrap,.bottom,.photo')].filter(e => getComputedStyle(e).display !== 'none').length,
    card: document.querySelector('.week').getBoundingClientRect().width }));
  check(`launcher card ${panel}: transparent, only the card`, !errors.length && r.bg === 'rgba(0, 0, 0, 0)'
    && r.visible === 0 && r.card > 400, JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- the detail pages of the new cards: their title, their icon, the large text
for (const [panel, title] of [['world', 'أخبار العالم'], ['hadith', 'حديث اليوم'], ['verse', 'آية اليوم']]) {
  const { p, ctx, errors } = await open('tv.html', 'detail=' + panel, { width: 960, height: 540 });
  await p.waitForTimeout(panel === 'hadith' ? 1500 : 300);
  const r = await p.evaluate(() => ({ t: document.querySelector('#dv .dv-title').textContent, ic: !!document.querySelector('#dv .dv-ic svg'),
    q: !!document.querySelector('#dv .dv-q li.quote') }));
  check(`detail ${panel}: title, icon` + (panel === 'world' ? '' : ', the text large'), !errors.length && r.t === title && r.ic
    && (panel === 'world' || r.q), JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- the TV shows card is gone: neither page names its API (GONE_API) any more, and no link (an older settings app's
// panel=shows, a rotation or detail page that lists it, its filters) makes a request to it. The name is spelled in two
// parts so that a search of the repo for it finds nothing at all.
const GONE_API = new RegExp(['tv', 'maze'].join(''), 'i');
for (const file of ['saudi-tv.html', 'tv.html'])
  check(`${file}: the TV shows API is not in the page`, !GONE_API.test(readFileSync(path.join(ROOT, 'dist', file), 'utf8')));
for (const [file, hash] of [['tv.html', 'panel=shows&genres=Drama&langs=English&shows=82&minrating=7'],
                            ['tv.html', 'panel=shows&panels=shows,prayers,news&rotate=5'],
                            ['tv.html', 'widget=1&panel=shows'], ['tv.html', 'home=1&panel=shows'],
                            ['tv.html', 'detail=shows&panels=shows,prayers'], ['saudi-tv.html', 'panel=shows&week=1']]) {
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.clock.setFixedTime(FIXED);
  const asked = [];
  await ctx.route(/^https?:/, r => { asked.push(r.request().url()); return r.abort(); });
  const p = await ctx.newPage();
  const errors = [];
  p.on('pageerror', e => errors.push(e.message));
  await p.goto(page(file) + '#' + hash);
  await p.waitForTimeout(1500);
  const shows = asked.filter(u => GONE_API.test(u));
  check(`${file} #${hash}: no request to the TV shows API, no errors`, !shows.length && !errors.length, shows.concat(errors).join('; '));
  await ctx.close();
}

// ---- last panels come back at once on the next start
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.clock.setFixedTime(FIXED);
  await ctx.route(/^https?:/, r => r.abort());
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#panel=prayers');
  await p.waitForTimeout(800);
  // the match row: offline, nothing redraws it (every card beside the clock draws itself at once)
  await p.evaluate(() => { document.getElementById('match-row').innerHTML = '<div>SAVED</div>'; saveSnap(); });
  await p.reload();
  const t = await p.evaluate(() => document.getElementById('match-row').textContent);
  check('saved panels restored on the next start', t === 'SAVED', t);
  await ctx.close();
}

// ---- a finished game's score stays up for a day, even when the API has already moved on. The bottom bar shows the
// next fixture of the week first (pickMatch), so the API's next fixture here is further off than a week.
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.clock.setFixedTime(FIXED);
  await ctx.route(/^https?:/, r => r.abort());
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#teams=136013&panel=matches');
  await p.waitForTimeout(800);
  const slot = () => p.evaluate(() => document.getElementById('match-row').innerText.replace(/\s+/g, ' ').trim());
  const game = (id, hoursAgo, score, status) => `({idEvent:'${id}', strHomeTeam:'Al-Hilal', strAwayTeam:'Al-Nassr',
    strTimestamp:new Date(Date.now() - ${hoursAgo} * 36e5).toISOString(), intHomeScore:${score ? score[0] : null},
    intAwayScore:${score ? score[1] : null}, strStatus:'${status}'})`;

  await p.evaluate(`MATCHES['136013'] = ${game('g1', 1.8, [2, 1], 'FT')}; renderMatches();`);
  let t = await slot();
  check('whistle blown: final score at once, not «مباشر»', t.includes('النتيجة النهائية') && t.includes('2 - 1') && !t.includes('مباشر'), t);

  await p.evaluate(`keepResult('136013', MATCHES['136013']); MATCHES['136013'] = ${game('g2', -200, null, 'NS')};
                    RESULTS = {}; renderMatches();`);
  t = await slot();
  check('API moved to the next fixture: score still shown', t.includes('النتيجة النهائية') && t.includes('2 - 1'), t);

  await p.reload();
  await p.waitForTimeout(800);
  await p.evaluate(`MATCHES['136013'] = ${game('g2', -200, null, 'NS')}; renderMatches();`);
  t = await slot();
  check('after a restart the same day: score still shown', t.includes('2 - 1'), t);

  await p.evaluate(`KEPT['136013'] = [${game('g1', 27, [2, 1], 'FT')}]; MATCHES['136013'] = ${game('g3', -72, null, 'NS')}; renderMatches();`);
  t = await slot();
  check('a day later: score gone, next fixture shown', !t.includes('2 - 1') && t.includes('×'), t);
  await ctx.close();
}

// ---- the ticker tape follows the city's country (tapeList): its index and the dollar against its currency
{
  const tape = async (hash) => {
    const { p, ctx, errors } = await open('tv.html', hash);
    const r = await p.evaluate(() => {
      TAPE.forEach((t, i) => { TAPE_Q[i] = mkTapeHtml(t.fmt === 'fx' ? 3.75 : 1234, 0.5, t.fmt); });
      tapeRender();
      const d = document.createElement('div'); d.innerHTML = TAPE_HTML;
      return { names: TAPE.map(t => t.name), syms: TAPE.map(t => t.sym || t.cg),
               shown: [...d.querySelectorAll('.tape-n')].map(e => e.textContent) };
    });
    await ctx.close();
    return { ...r, errors };
  };
  const SA_NAMES = ['تاسي', 'S&P 500', 'ناسداك', 'داو جونز', 'الذهب', 'برنت', 'الدولار/الريال', 'بيتكوين', 'إيثريوم'];
  const SA_SYMS = ['^TASI.SR', '^GSPC', '^IXIC', '^DJI', 'GC=F', 'BZ=F', 'SAR=X', 'bitcoin', 'ethereum'];
  for (const [tag, hash] of [['no country', 'city=جدة&lat=21.5433&lon=39.1728'], ['SA', 'city=جدة&lat=21.5433&lon=39.1728&cc=SA']]) {
    const r = await tape(hash);
    check(`ticker tape, ${tag}: as before (تاسي … الدولار/الريال …)`, !r.errors.length && JSON.stringify(r.names) === JSON.stringify(SA_NAMES)
      && JSON.stringify(r.syms) === JSON.stringify(SA_SYMS) && JSON.stringify(r.shown) === JSON.stringify(SA_NAMES), JSON.stringify(r));
  }
  let r = await tape('city=القاهرة&lat=30.0444&lon=31.2357&cc=EG&numerals=arab');
  check('ticker tape, EG: EGX30 first, «الدولار/الجنيه» (EGP=X), the rest as before, the index\'s digits as set',
    !r.errors.length && r.syms[0] === '^CASE30' && r.shown[0] === 'إيجي إكس ٣٠' && r.names[6] === 'الدولار/الجنيه' && r.syms[6] === 'EGP=X'
      && JSON.stringify(r.syms.slice(1, 6)) === JSON.stringify(SA_SYMS.slice(1, 6)) && r.syms.slice(7).join() === 'bitcoin,ethereum', JSON.stringify(r));
  r = await tape('city=Cairo&lat=30.0444&lon=31.2357&cc=EG');
  check('ticker tape, EG with 0-9: «إيجي إكس 30»', r.shown[0] === 'إيجي إكس 30', JSON.stringify(r.shown));
  r = await tape('city=New York&lat=40.71&lon=-74.0&cc=US');
  check('ticker tape, US: no local index (S&P 500 first), «اليورو/الدولار» (EURUSD=X)',
    !r.errors.length && r.syms[0] === '^GSPC' && r.names.includes('اليورو/الدولار') && r.syms.includes('EURUSD=X')
      && !r.names.some(n => n.startsWith('الدولار/')) && r.names.length === 8, JSON.stringify(r));
  r = await tape('city=دبي&lat=25.2&lon=55.27&cc=AE');
  check('ticker tape, AE Dubai: «مؤشر دبي» and «الدولار/الدرهم»', r.names[0] === 'مؤشر دبي' && r.syms[0] === 'DFMGI.AE'
    && r.names[6] === 'الدولار/الدرهم' && r.syms[6] === 'AED=X', JSON.stringify(r));
  r = await tape('city=أبو ظبي&lat=24.45&lon=54.38&cc=AE');
  check('ticker tape, AE Abu Dhabi: «مؤشر أبوظبي»', r.names[0] === 'مؤشر أبوظبي' && r.syms[0] === 'FADGI.FGI', JSON.stringify(r.names));
  r = await tape('city=مسقط&lat=23.6&lon=58.4&cc=OM');
  check('ticker tape, OM (no index on Yahoo): the index left out, «الدولار/الريال» (OMR=X)',
    r.syms[0] === '^GSPC' && r.names.length === 8 && r.names[5] === 'الدولار/الريال' && r.syms[5] === 'OMR=X', JSON.stringify(r));
  r = await tape('city=Paris&lat=48.85&lon=2.35&cc=FR');
  check('ticker tape, FR: its index and «الدولار/اليورو»', r.names[0] === 'كاك 40' && r.names[6] === 'الدولار/اليورو' && r.syms[6] === 'EUR=X', JSON.stringify(r.names));
  r = await tape('city=Suva&lat=-18.1&lon=178.4&cc=FJ');
  check('ticker tape, a currency without an Arabic name: its code («الدولار/FJD»)', r.names.includes('الدولار/FJD'), JSON.stringify(r.names));
  const { p, ctx } = await open('tv.html', 'city=جدة&lat=21.5433&lon=39.1728');
  const f = await p.evaluate(() => [erRate({EUR: 0.8, EGP: 48.5}, '1/EUR'), erRate({EGP: 48.5}, 'EGP'), erRate(null, 'EGP'), erRate({}, 'XYZ'),
    mkTapeHtml(3.75, 0, 'fx').value, mkTapeHtml(48.5, 0, 'fx').value, mkTapeHtml(145.2, 0, 'fx').value, mkTapeHtml(16234.5, 0, 'fx').value]);
  check('ticker tape: open.er-api.com rates (and the inverse for the euro); the rate\'s decimals (4, 2 from 100, none from 1,000)',
    f[0] === 1.25 && f[1] === 48.5 && f[2] === null && f[3] === null && f[4].includes('3.7500') && f[5].includes('48.5000')
      && f[6].includes('145.20') && f[7].includes('16,235') && !f[7].includes('.'), JSON.stringify(f));
  await ctx.close();
}

// ---- the ticker's rates are sanity-checked (mkFxQuote): Yahoo's SAR=X closes near 3.63 for days while its price is
// 3.752, and the tape showed «الدولار/الريال +3.22%». Pegged currencies: the previous close is the last bar near the
// peg, a price off the peg is no price (open.er-api.com instead), a change over 1 % is dropped; any other rate, over 10 %
{
  const { p, ctx } = await open('tv.html', 'city=جدة&lat=21.5433&lon=39.1728');
  const r = await p.evaluate(() => {
    const day = 86400, t0 = 1792364400;   // daily bars, then today's price at the last bar's day
    const chart = (price, closes, prevClose) => ({
      meta: {regularMarketPrice: price, chartPreviousClose: prevClose, regularMarketTime: t0 + (closes.length - 1) * day + 3600,
             exchangeTimezoneName: 'Europe/London', gmtoffset: 0},
      timestamp: closes.map((_, i) => t0 + i * day), indicators: {quote: [{close: closes}]}});
    const pct = q => q ? mkIndexPct(q) : 'none';
    return {
      // SAR=X as Yahoo answered on 2026-10-24: every earlier bar about 3 % low, chartPreviousClose too
      sarBogus: pct(mkFxQuote(chart(3.7521, [3.6363, 3.6467, 3.6520, 3.6283, 3.6352, 3.7521], 3.6363), 'SAR=X')),
      sarPrice: (mkFxQuote(chart(3.7521, [3.6363, 3.6467, 3.6520, 3.6283, 3.6352, 3.7521], 3.6363), 'SAR=X') || {}).price,
      // AED=X with sane bars: its small real change stays
      aed: pct(mkFxQuote(chart(3.6726, [3.6715, 3.6715, 3.6715, 3.6715, 3.6715, 3.6726], 3.6715), 'AED=X')),
      // QAR=X: one bad bar right before today: the last good one counts
      qar: pct(mkFxQuote(chart(3.64, [3.6447, 3.6447, 3.5137, 3.6447, 3.5084, 3.64], 3.6447), 'QAR=X')),
      // a price off the peg (3.63 for the riyal): no price, open.er-api.com instead
      sarOff: mkFxQuote(chart(3.63, [3.75, 3.75, 3.63], 3.75), 'SAR=X'),
      // a floating rate: +2 % is a day, +15 % a bad bar
      egp: pct(mkFxQuote(chart(49.47, [48.5, 48.5, 49.47], 48.5), 'EGP=X')),
      egpBad: pct(mkFxQuote(chart(55.8, [48.5, 48.5, 55.8], 48.5), 'EGP=X')),
      eur: pct(mkFxQuote(chart(1.0920, [1.0870, 1.0890, 1.0920], 1.0870), 'EURUSD=X')),
      // an index is not a rate: a big move stays (mkIndexQuote as before)
      idx: pct(mkIndexQuote(chart(12000, [10000, 10500, 12000], 10000))),
      shown: mkTapeHtml(3.7521, mkIndexPct(mkFxQuote(chart(3.7521, [3.6363, 3.6467, 3.7521], 3.6363), 'SAR=X')), 'fx'),
      pegs: Object.keys(FX_PEG).sort().join(','),
      saItem: TAPE.find(t => t.sym === 'SAR=X'),
    };
  });
  check('pegged rate: Yahoo\'s bogus SAR=X bars give no daily change (was +3.22 %), the price stays',
    r.sarBogus === null && r.sarPrice === 3.7521 && r.shown.value.includes('3.7521') && r.shown.change === '', JSON.stringify(r));
  check('pegged rate: AED=X\'s real small change stays (+0.03 %)', typeof r.aed === 'number' && Math.abs(r.aed - 0.03) < 0.01, r.aed);
  check('pegged rate: QAR=X skips a bad bar, against the last close near the peg', typeof r.qar === 'number' && Math.abs(r.qar - -0.13) < 0.01, r.qar);
  check('pegged rate: a price off the peg is no price (open.er-api.com\'s rate instead; the Saudi item has er:SAR)',
    r.sarOff === null && r.saItem && r.saItem.er === 'SAR', JSON.stringify([r.sarOff, r.saItem]));
  check('other rates: +2 % a day stays, +15 % is dropped; EUR/USD as before; an index keeps a big move',
    Math.abs(r.egp - 2) < 0.01 && r.egpBad === null && Math.abs(r.eur - 0.28) < 0.01 && Math.abs(r.idx - 14.29) < 0.01, JSON.stringify(r));
  check('pegs listed: the Gulf currencies and the Jordanian dinar at least',
    ['AED', 'BHD', 'JOD', 'OMR', 'QAR', 'SAR'].every(c => r.pegs.split(',').includes(c)), r.pegs);
  await ctx.close();
}

// ---- «يُتلى الآن» (tickRadio): the app's Quran radio chip under the clock, from window.SaudiTVRadio.state()
{
  const radio = st => `window.SaudiTVRadio = {state: () => window.__radio}; window.__radio = ${JSON.stringify(st)};`;
  const chip = () => {
    const el = document.getElementById('qr'), b = el.getBoundingClientRect(), cs = getComputedStyle(el);
    return { hidden: el.hidden || cs.display === 'none', text: el.textContent, cls: el.className, opacity: +cs.opacity,
             inStage: !!el.closest('.stage'), on: b.width > 0 && b.left >= 0 && b.right <= innerWidth && b.bottom <= innerHeight };
  };
  let { p, ctx, errors } = await open('tv.html', 'city=جدة&lat=21.5433&lon=39.1728', { init: radio('playing|إذاعة القرآن الكريم من السعودية') });
  await p.waitForTimeout(700);
  let c = await p.evaluate(chip);
  check('radio chip, screensaver: «يُتلى الآن: <station>» on screen, in the stage (moves with pixelShift), dimmed .78',
    !errors.length && !c.hidden && c.text === 'يُتلى الآن: إذاعة القرآن الكريم من السعودية' && c.on && c.inStage && Math.abs(c.opacity - 0.78) < 0.01,
    JSON.stringify(c) + errors.join('; '));
  await p.evaluate(() => { window.__radio = 'error|إذاعة القرآن الكريم من القاهرة'; tickRadio(); });
  c = await p.evaluate(chip);
  check('radio chip: the error state («تعذّر تشغيل …»)', !c.hidden && c.cls.includes('error') && c.text === 'تعذّر تشغيل إذاعة القرآن الكريم من القاهرة', JSON.stringify(c));
  for (const st of ['', 'loading|إذاعة', 'paused|إذاعة']) {
    await p.evaluate(s => { window.__radio = s; tickRadio(); }, st);
    c = await p.evaluate(chip);
    check(`radio chip: hidden for «${st || 'off'}»`, c.hidden, JSON.stringify(c));
  }
  await p.evaluate(() => { window.__radio = 'playing|<b>x</b>'; tickRadio(); });
  check('radio chip: the station\'s name is text, never markup', await p.evaluate(() => !document.querySelector('#qr-t b b')
    && document.getElementById('qr-t').textContent === 'يُتلى الآن: <b>x</b>'));
  await ctx.close();
  ({ p, ctx, errors } = await open('tv.html', 'city=جدة&lat=21.5433&lon=39.1728&home=1', { init: radio('playing|ماهر المعيقلي') }));
  await p.waitForTimeout(700);
  c = await p.evaluate(chip);
  check('radio chip, home screen: shown under the clock, full opacity', !errors.length && !c.hidden && c.on && c.opacity === 1
    && c.text === 'يُتلى الآن: ماهر المعيقلي', JSON.stringify(c));
  await ctx.close();
  ({ p, ctx } = await open('tv.html', 'panel=prayers&widget=1', { init: radio('playing|ماهر المعيقلي') }));
  await p.waitForTimeout(700);
  c = await p.evaluate(chip);
  check('radio chip: never in card-only mode', c.hidden, JSON.stringify(c));
  await ctx.close();
  ({ p, ctx } = await open('tv.html', 'city=جدة&lat=21.5433&lon=39.1728'));
  await p.waitForTimeout(700);
  c = await p.evaluate(chip);
  check('radio chip: hidden in a browser (no SaudiTVRadio)', c.hidden, JSON.stringify(c));
  await ctx.close();
}

// ---- the screensaver's footer: the middle panel (Ramadan) on the screen's centre line, whatever the pixel shift; a long
// match and the prayer's countdown stay in their own columns
for (const [w, h] of [[1280, 720], [1920, 1080]]) {
  const { p, ctx, errors } = await open('tv.html', 'city=جدة&lat=21.5433&lon=39.1728&teams=136013', { width: w, height: h });
  const r = await p.evaluate(() => {
    MATCHES['136013'] = {idEvent: 'g9', strHomeTeam: 'الأهلي المصري', strAwayTeam: 'سيراميكا كليوباترا',
      strTimestamp: new Date(Date.now() + 4 * 864e5).toISOString(), intHomeScore: null, intAwayScore: null, strStatus: 'NS'};
    renderMatches();
    const box = q => document.querySelector(q).getBoundingClientRect();
    const mid = box('.p-mid'), match = box('.p-match'), pray = box('.p-prayer');
    const inside = (q, b) => [...document.querySelectorAll(q)].every(e => { const r = e.getBoundingClientRect();
      return !r.width || (r.left >= b.left - 1 && r.right <= b.right + 1); });
    return { off: (mid.left + mid.right) / 2 - innerWidth / 2 - SHIFT_AT[0], gapL: mid.left - match.right, gapR: pray.left - mid.right,
             matchIn: inside('.p-match .mblock, .p-match .big > span, .p-match .sub', match),
             prayIn: inside('.p-prayer .big, .p-prayer .sub', pray), text: document.getElementById('match-row').innerText.replace(/\s+/g, ' ') };
  });
  check(`screensaver footer ${w}x${h}: the middle panel centred (±3 px, pixel shift aside), the others clear of it`,
    !errors.length && Math.abs(r.off) <= 3 && r.gapL > 10 && r.gapR > 10 && r.matchIn && r.prayIn && r.text.includes('سيراميكا'),
    JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- the ticker follows the news country; the settings' chosen items (#tape) in their order
{
  const tape = async (hash) => {
    const { p, ctx, errors } = await open('tv.html', hash);
    const r = await p.evaluate(() => ({ ids: TAPE.map(t => t.sym || t.cg), names: TAPE.map(t => t.name) }));
    await ctx.close();
    return { ...r, errors };
  };
  let r = await tape('city=Los%20Angeles&lat=34.0522&lon=-118.2437&cc=US&newscc=SA');
  check('ticker: country US, news SA: «تاسي» and «الدولار/الريال» (SAR=X), as at home', !r.errors.length
    && r.ids.join() === '^TASI.SR,^GSPC,^IXIC,^DJI,GC=F,BZ=F,SAR=X,bitcoin,ethereum', JSON.stringify(r));
  r = await tape('city=Los%20Angeles&lat=34.0522&lon=-118.2437&cc=US&newscc=US');
  check('ticker: country US, news US: as the US (no index, «اليورو/الدولار»)', r.ids[0] === '^GSPC' && r.ids.includes('EURUSD=X') && r.ids.length === 8, JSON.stringify(r));
  r = await tape('city=القاهرة&lat=30.04&lon=31.24&cc=EG&newscc=EG');
  check('ticker: Egypt with its news: EGX30 and EGP=X', r.ids[0] === '^CASE30' && r.ids[6] === 'EGP=X', JSON.stringify(r));
  r = await tape('city=أبو ظبي&lat=24.45&lon=54.38&cc=AE&newscc=AE');
  check('ticker: Abu Dhabi with its news: Abu Dhabi\'s index', r.ids[0] === 'FADGI.FGI', JSON.stringify(r));
  r = await tape('city=Los%20Angeles&lat=34.0522&lon=-118.2437&cc=US&newscc=SA&tape=gold,btc,cur');
  check('ticker, chosen items: gold, bitcoin, the country\'s currency, in that order', r.ids.join() === 'GC=F,bitcoin,SAR=X'
    && r.names.join() === 'الذهب,بيتكوين,الدولار/الريال', JSON.stringify(r));
  r = await tape('city=القاهرة&lat=30.04&lon=31.24&cc=EG&newscc=EG&tape=cur,idx,silver,i:SA,c:AED,eurusd,bogus,gold,gold,c:XYZ');
  check('ticker, chosen items: «عملة/مؤشر الدولة» follow the country (EG), more items, unknown ones and repeats left out',
    r.ids.join() === 'EGP=X,^CASE30,SI=F,^TASI.SR,AED=X,EURUSD=X,GC=F', JSON.stringify(r));
  r = await tape('city=Los%20Angeles&lat=34.0522&lon=-118.2437&cc=US&newscc=US&tape=idx,cur,i:AD');
  check('ticker, chosen items in the US: no index of its own, the euro; Abu Dhabi\'s by id', r.ids.join() === 'EURUSD=X,FADGI.FGI', JSON.stringify(r));
  r = await tape('city=جدة&lat=21.5&lon=39.2&tape=bogus');
  check('ticker: only unknown items chosen: the country\'s own list', r.ids.length === 9 && r.ids[0] === '^TASI.SR', JSON.stringify(r));
}

// ---- the mouse: never on the screensaver; on the launcher's pages the pointer while it moves (hidden after 3 s);
// detail pages: hover = the D-pad's focus, click = OK, a dot opens its page, the wheel moves a row or scrolls the text
{
  let { p, ctx, errors } = await open('tv.html', 'city=جدة&lat=21.5433&lon=39.1728', { width: 1280, height: 720 });
  await p.mouse.move(300, 300); await p.mouse.move(320, 310);
  let r = await p.evaluate(() => ({ cls: document.documentElement.classList.contains('mouse'), cur: getComputedStyle(document.body).cursor }));
  check('mouse, screensaver: never a pointer (cursor none, no html.mouse)', !errors.length && !r.cls && r.cur === 'none', JSON.stringify(r));
  await ctx.close();

  const native = () => { window.__opened = []; window.SaudiTVNative = { openUrl: u => window.__opened.push(u) }; };
  ({ p, ctx, errors } = await open('tv.html', 'detail=world&panels=world,tech,verse', { width: 1280, height: 720, init: native }));
  await p.evaluate(() => {
    NEWS.world = Array.from({ length: 8 }, (_, i) => ({ headline: 'خبر رقم ' + i, source: 'bbc', t: Date.now() - i * 6e4, link: 'https://example.com/' + i }));
    DV.answered.world = true; renderDetail();
  });
  const item = async i => p.evaluate(k => { const b = document.querySelectorAll('#dv .dv-item')[k].getBoundingClientRect();
    return [b.left + b.width / 2, b.top + b.height / 2]; }, i);
  const focus = () => p.evaluate(() => [DV.focus, [...document.querySelectorAll('#dv .dv-item')].findIndex(e => e.classList.contains('focus'))]);
  let [x, y] = await item(2);
  await p.mouse.move(x, y);
  r = await p.evaluate(() => ({ cls: document.documentElement.classList.contains('mouse'),
    cur: getComputedStyle(document.querySelector('#dv .dv-item')).cursor }));
  const f1 = await focus();
  await p.keyboard.press('ArrowDown');
  const f2 = await focus();
  check('mouse, detail: the pointer shows while it moves (cursor), hover focuses the item, ▼ goes on from there',
    !errors.length && r.cls && r.cur === 'pointer' && f1.join() === '2,2' && f2.join() === '3,3', JSON.stringify([r, f1, f2]));
  [x, y] = await item(1);
  await p.mouse.click(x, y);
  r = await p.evaluate(() => [DV.focus, window.__opened.slice()]);
  check('mouse, detail: click on a news item = OK (focused, its article opened)', r[0] === 1 && r[1].join() === 'https://example.com/1', JSON.stringify(r));
  await p.mouse.wheel(0, 120); await p.waitForTimeout(50);
  const w1 = await focus();
  await p.waitForTimeout(450); await p.mouse.wheel(0, -120); await p.waitForTimeout(50);
  const w2 = await focus();
  check('mouse, detail: the wheel moves one item a notch (down, then up)', w1[0] === 2 && w2[0] === 1, JSON.stringify([w1, w2]));
  await p.waitForTimeout(3200);
  r = await p.evaluate(() => document.documentElement.classList.contains('mouse'));
  check('mouse: the pointer hidden after 3 s still', r === false, r);
  const dot = await p.evaluate(() => { const b = document.querySelectorAll('#dv .dv-dot')[2].getBoundingClientRect(); return [b.left + b.width / 2, b.top + b.height / 2]; });
  await p.mouse.click(dot[0], dot[1]);
  await p.waitForTimeout(600);
  r = await p.evaluate(() => [CONFIG.detail, document.querySelector('#dv .dv-title').textContent, !!document.querySelector('#dv .dv-scroll')]);
  check('mouse, detail: a click on a page\'s dot opens that page (the third: the verse)', r[0] === 'verse' && r[1] === 'آية اليوم', JSON.stringify(r));
  const before = await p.evaluate(() => { const sc = document.querySelector('#dv .dv-scroll'); sc.style.height = '120px'; sc.scrollTop = 0; return sc.scrollHeight > 130; });
  await p.mouse.move(640, 500); await p.mouse.wheel(0, 90); await p.waitForTimeout(100);
  r = await p.evaluate(() => document.querySelector('#dv .dv-scroll').scrollTop);
  check('mouse, detail: the wheel scrolls a page\'s text', before && r > 0, JSON.stringify([before, r]));
  check('mouse, detail: no script errors', !errors.length, errors.join('; '));
  await ctx.close();

  // home: «يُتلى الآن»'s box for the launcher's focusable view over it, and its focus look
  const radio = () => {
    window.__rects = []; window.__radio = 'playing|إذاعة القرآن الكريم من السعودية';
    window.SaudiTVRadio = { state: () => window.__radio };
    window.SaudiTVNative = { cardRect: () => {}, qrRect: (...a) => window.__rects.push(a) };
  };
  ({ p, ctx, errors } = await open('tv.html', 'city=جدة&lat=21.5433&lon=39.1728&home=1&panel=prayers', { width: 1280, height: 720, init: radio }));
  await p.waitForTimeout(900);
  r = await p.evaluate(() => { const b = document.getElementById('qr').getBoundingClientRect(), a = window.__rects[window.__rects.length - 1];
    return { a, b: [b.left, b.top, b.width, b.height].map(Math.round) }; });
  const near = (a, b) => a && b && a.slice(0, 4).every((v, i) => Math.abs(v - b[i]) <= 1);
  check('home: «يُتلى الآن»\'s box sent to the launcher (qrRect, CSS px and the viewport\'s width)', !errors.length && near(r.a, r.b) && r.a[4] === 1280,
    JSON.stringify(r) + errors.join('; '));
  await p.evaluate(() => { window.sauditvQrFocused(true); });
  r = await p.evaluate(() => ({ bg: getComputedStyle(document.getElementById('qr')).backgroundColor, c: getComputedStyle(document.querySelector('#qr b')).color }));
  check('home: «يُتلى الآن» focused looks like a focused pill (white, dark text)', /255, 255, 255/.test(r.bg) && r.c === 'rgb(17, 17, 17)', JSON.stringify(r));
  await p.evaluate(() => { window.sauditvQrFocused(false); window.__radio = ''; tickRadio(); });
  r = await p.evaluate(() => window.__rects[window.__rects.length - 1]);
  check('home: «يُتلى الآن» gone: a 0 x 0 box (the launcher drops its view)', r && r.slice(0, 4).join() === '0,0,0,0', JSON.stringify(r));
  await p.mouse.move(200, 200); await p.mouse.move(210, 205);
  r = await p.evaluate(() => [document.documentElement.classList.contains('mouse'), getComputedStyle(document.body).cursor]);
  check('mouse, home: the pointer shows while it moves', r[0] && r[1] === 'default', JSON.stringify(r));
  await ctx.close();
}

// ---- «توفير الطاقة» (#lowpower=1): the ticker tape's animation in 30 steps a second (steps() easing), at the same
// speed; without it a smooth (linear) one, as before. Both the compositor's: the page sets nothing while it runs
{
  const tapeRun = async (hash, ms = 1000) => {
    const { p, ctx, errors } = await open('tv.html', hash);
    await p.evaluate(() => { TAPE.forEach((t, i) => { TAPE_Q[i] = mkTapeHtml(t.fmt === 'fx' ? 3.75 : 1234, 0.5, t.fmt); }); tapeRender(); });
    await p.waitForFunction(() => !!TAPE_ANIM, null, { timeout: 3000 }).catch(() => errors.push('no tape animation'));
    await p.waitForTimeout(200);
    // the run's place on every frame for t ms: how many different places a second, how far it went
    const sample = t => p.evaluate(t => new Promise(done => {
      const run = document.getElementById('tape-run'), x = () => new DOMMatrix(getComputedStyle(run).transform).m41;
      const seen = new Set(), x0 = x(), t0 = performance.now();
      let n = 0;
      const mo = new MutationObserver(() => n++);
      mo.observe(run, { attributes: true, attributeFilter: ['style'] });
      const f = () => {
        seen.add(x().toFixed(2));
        if(performance.now() - t0 < t) return requestAnimationFrame(f);
        mo.disconnect();
        const a = run.getAnimations();
        done({ places: seen.size, s: (performance.now() - t0) / 1000, moved: x() - x0, styleSets: n, anims: a.length,
               easing: a[0] ? a[0].effect.getTiming().easing : '' });
      };
      requestAnimationFrame(f);
    }), t);
    return { p, ctx, errors, sample: () => sample(ms) };
  };
  let { p, ctx, errors, sample } = await tapeRun('city=جدة&lat=21.5433&lon=39.1728&lowpower=1');
  let r = await sample();
  check('low power: the tape\'s animation in steps, about 30 places a second, nothing set by the page',
    !errors.length && r.anims === 1 && /^steps\(\d+/.test(r.easing) && r.styleSets === 0 && r.places / r.s >= 22 && r.places / r.s <= 34,
    JSON.stringify(r) + errors.join('; '));
  check('low power: the same speed as before (55 px a second, to the right)', Math.abs(r.moved / r.s - 55) < 8, JSON.stringify(r));
  await ctx.close();
  ({ p, ctx, errors, sample } = await tapeRun('city=جدة&lat=21.5433&lon=39.1728'));
  r = await sample();
  check('ticker without «توفير الطاقة»: the smooth animation as before, 55 px a second',
    !errors.length && r.anims === 1 && r.easing === 'linear' && r.places / r.s > 40 && Math.abs(r.moved / r.s - 55) < 8,
    JSON.stringify(r) + errors.join('; '));
  await ctx.close();
  // held while the launcher's apps grid is open, then on from the same place
  ({ p, ctx, errors, sample } = await tapeRun('city=جدة&lat=21.5433&lon=39.1728&home=1&lowpower=1', 500));
  await p.evaluate(() => window.sauditvAppsOpen(true));
  await p.waitForTimeout(100);   // a pause takes effect on the next frame
  r = await sample();
  check('low power, home: the tape holds still while the apps grid is open', r.places === 1 && Math.abs(r.moved) < 0.5, JSON.stringify(r));
  const held = await p.evaluate(() => new DOMMatrix(getComputedStyle(document.getElementById('tape-run')).transform).m41);
  await p.evaluate(() => window.sauditvAppsOpen(false));
  r = await sample();
  const after = await p.evaluate(() => new DOMMatrix(getComputedStyle(document.getElementById('tape-run')).transform).m41);
  check('low power, home: closed, it goes on from where it held', r.places > 8 && after - held > 15 && after - held < 45,
    JSON.stringify({ r, held, after }));
  check('low power: no script errors', !errors.length, errors.join('; '));
  await ctx.close();
}

// ---- «يُتلى الآن» told, not asked (#radiopush=1): the home screen's page asks SaudiTVRadio.state() once at load and
// whenever the launcher says the radio changed (window.sauditvRadioChanged); without it (the screensaver, an older
// launcher) every 3 s as before
{
  const counting = () => {
    window.__asks = 0; window.__radio = '';
    window.SaudiTVRadio = { state: () => { window.__asks++; return window.__radio; } };
  };
  let { p, ctx, errors } = await open('tv.html', 'city=جدة&lat=21.5433&lon=39.1728&home=1&radiopush=1', { init: counting });
  await p.waitForTimeout(3700);
  let r = await p.evaluate(() => window.__asks);
  check('radio push, home: asked once when the page loads, not every 3 s', !errors.length && r === 1, r + ' ' + errors.join('; '));
  await p.evaluate(() => { window.__radio = 'playing|ماهر المعيقلي'; window.sauditvRadioChanged(); });
  r = await p.evaluate(() => ({ asks: window.__asks, hidden: document.getElementById('qr').hidden, text: document.getElementById('qr').textContent }));
  check('radio push, home: told the radio changed, «يُتلى الآن» follows at once', r.asks === 2 && !r.hidden
    && r.text === 'يُتلى الآن: ماهر المعيقلي', JSON.stringify(r));
  await ctx.close();
  for (const [tag, hash] of [['a launcher that does not tell (no #radiopush)', 'city=جدة&lat=21.5433&lon=39.1728&home=1'],
                             ['the screensaver', 'city=جدة&lat=21.5433&lon=39.1728']]) {
    ({ p, ctx } = await open('tv.html', hash, { init: counting }));
    await p.waitForTimeout(3700);
    r = await p.evaluate(() => window.__asks);
    check(`radio chip, ${tag}: asked every 3 s as before`, r >= 2, r);
    await ctx.close();
  }
}

// ---- the channel and commentator of a followed team's match in the next 48 h (bcastOf): btolat's day page first, filgoal
// only for a field btolat lacks; both teams must match; any failure shows nothing. FIXED is 12 May in Riyadh (UTC+3).
// SHOTS=1 also saves the card and the home strip to optimize/run/shots/m_channel_card.png and m_channel_strip.png.
{
  const btolat = (title, rows) => `<html><head><title>${title}</title></head><body><ul>` + rows.map(([h, a, ch, com, tm = '09:15 م'], i) =>
    `<li id='m_${i + 1}' class="item NotStarted" title="مباراة ${h} و ${a} فى الدوري السعودي"><a href="/team/${i}" class="team">`
    + `<img src="x.png"><span>${h}</span></a><span class="match-time"> ${tm} </span><a href="/team/${i + 50}" class="team"><span>${a}</span></a>`
    + '<div class="match-footer">'
    + (ch ? `<span data-dc-tpl="42"><svg><rect x="2" y="7" width="20" height="13" rx="2"/><path d="m17 2-5 5-5-5"/></svg>`
      + `<span data-dc-tpl="44"><span class="sc-interp"> ${ch} </span></span></span>` : '')
    + (com ? `<span data-dc-tpl="83"></span><svg data-dc-tpl="84"><path d="M12 2a3 3 0 0 0-3 3v7"/><line x1="12" y1="19" x2="12" y2="22"/></svg>`
      + `<span data-dc-tpl="44"><span class="sc-interp">${com}</span></span>` : '')
    + '</div></li>').join('') + '</ul></body></html>';
  const DAY = 'جدول مباريات الثلاثاء 12-05-2026 - بطولات';
  const BT = 'mobile\\.btolat\\.com/match-score\\?d=5\\.12\\.2026$', FG = 'filgoal\\.com/matches/ajaxlist\\?date=2026-05-12$';
  const filgoal = (h, a, ch, com) => JSON.stringify([{ Id: 1, HomeTeamName: h, AwayTeamName: a, Date: '2026-05-12T18:20:00',
    TvCoverage: ch ? [{ TvChannelId: 59, TvChannelName: ch, CommenterName: com || null }] : [] }]);
  // the followed team's next match `hrs` from now (النصر × away; 10 h = 18:20 in Riyadh), one bcastUpdate; cache: BC
  // entries to start with ({key: {ago: hours, rows}}); again: a second update right after
  const run = async (feeds, { hrs = 10, hash = 'teams=136013&panel=matches', cache = null, again = false, away = 'الخلود' } = {}) => {
    const { p, ctx, errors } = await open('tv.html', hash, { feeds });
    const asked = [];
    p.on('request', r => { if (/btolat|filgoal/.test(r.url())) asked.push(r.url()); });
    const once = c => p.evaluate(async ({ hrs, c, away }) => {
      if (c) Object.entries(c).forEach(([k, v]) => { BC[k] = { at: Date.now() - v.ago * 36e5, rows: v.rows.map(r => [bcKey(r[0]), bcKey(r[1]), ...r.slice(2)]) }; });
      MATCHES['136013'] = { idEvent: 'bc1', strHomeTeam: 'النصر', strAwayTeam: away, idHomeTeam: '136013', idAwayTeam: '999', strLeague: 'Saudi Pro League',
        strTimestamp: new Date(Date.now() + hrs * 36e5).toISOString().slice(0, 19), intHomeScore: null, intAwayScore: null, strStatus: 'NS' };
      NEXT_LIST = {};
      await bcastUpdate();
      renderPanel(false);
      const ev = MATCHES['136013'], bc = document.querySelector('#week-list li .bc');
      return { x: bcastOf(ev), line: bc ? bc.textContent : '', html: bc ? bc.innerHTML : '',
               strip: homeMatch({ ev, done: false }, Date.now()), dv: dvMatches(), saved: localStorage.getItem('bcast') || '',
               awayKey: bcKey(teamAr(away)) };
    }, { hrs, c, away });
    const r = await once(cache);
    if (again) r.second = await once(null);
    return { r, asked, p, ctx, errors };
  };

  let { r, asked, p, ctx, errors } = await run({ [BT]: btolat(DAY, [['النصر السعودي', 'الخلود', 'BEIN SPORTS 3', 'أحمد البلوشي'],
                                                               ['الهلال السعودي', 'الشباب السعودي', 'ثمانية 1', '']]) }, { again: true });
  check('channel: both teams match on btolat -> «القناة: X · المعلق: Y» («BEIN» written «beIN») under the match, « · X» in the home strip, rows in the detail view; filgoal not asked',
    !errors.length && r.line === 'القناة: beIN SPORTS 3 · المعلق: أحمد البلوشي' && r.strip.endsWith(' · <bdi>beIN SPORTS 3</bdi></span>')
    && r.dv.includes('<span class="dv-meta dv-bc">القناة: <bdi>beIN SPORTS 3</bdi></span><span class="dv-meta dv-bc">المعلق: <bdi>أحمد البلوشي</bdi></span>')
    && asked.length === 1 && /btolat/.test(asked[0]), JSON.stringify({ r, asked }) + errors.join('; '));
  check('channel: cache honoured: a second update within 3 h asks nothing; the day is saved', r.second.line === r.line && asked.length === 1
    && r.saved.includes('btolat|2026-05-12'), JSON.stringify({ asked, s: r.second }));
  // a Latin name in an <bdi> isolate, so the Arabic line keeps its order (label on the right, the channel left of it)
  const iso = await p.evaluate(() => {
    const bc = document.querySelector('#week-list .bc'), b = bc && bc.querySelector('bdi');
    if (!b) return null;
    const label = document.createRange(); label.setStart(bc.firstChild, 0); label.setEnd(bc.firstChild, 6);
    return { iso: getComputedStyle(b).unicodeBidi, text: b.textContent, left: b.getBoundingClientRect().right <= label.getBoundingClientRect().left + 1 };
  });
  check('channel: a Latin channel name stays isolated (<bdi>) and left of «القناة:» in the RTL line', iso && iso.iso === 'isolate'
    && iso.text === 'beIN SPORTS 3' && iso.left && r.html.includes('<bdi>beIN SPORTS 3</bdi>'), JSON.stringify(iso));
  if (process.env.SHOTS) await p.locator('.week').screenshot({ path: path.join(ROOT, 'optimize/run/shots/m_channel_card.png') });
  await ctx.close();

  if (process.env.SHOTS) {
    ({ p, ctx } = await run({ [BT]: btolat(DAY, [['النصر السعودي', 'الخلود', 'BEIN SPORTS 3', 'أحمد البلوشي']]) },
      { hash: 'city=جدة&lat=21.5433&lon=39.1728&home=1&teams=136013' }));
    await p.evaluate(() => renderMatches());
    await p.locator('.hinfo').screenshot({ path: path.join(ROOT, 'optimize/run/shots/m_channel_strip.png') });
    await ctx.close();
  }

  ({ r, errors, ctx } = await run({ [BT]: btolat(DAY, [['النصر السعودي', 'الوصل', 'BEIN SPORTS 3', 'أحمد البلوشي'],
                                                    ['الهلال', 'الخلود', 'ثمانية 2', 'فارس عوض']]) }));
  check('channel: only one team matches (النصر × الوصل, الهلال × الخلود) -> nothing shown', !errors.length && !r.x && !r.line
    && !r.strip.includes('bdi') && !r.dv.includes('dv-bc'), JSON.stringify(r));
  await ctx.close();

  ({ r, asked, errors, ctx } = await run({ [BT]: btolat(DAY, [['النصر السعودي', 'الخلود', 'BEIN SPORTS 3', '']]),
                                          [FG]: filgoal('النصر', 'الخلود', 'beIN SPORTS 3 HD', 'فهد العتيبي') }));
  check('channel: btolat lacks the commentator, filgoal has it -> filled; the channel stays btolat\'s', !errors.length
    && r.line === 'القناة: beIN SPORTS 3 · المعلق: فهد العتيبي' && asked.length === 2, JSON.stringify({ r, asked }));
  await ctx.close();

  ({ r, errors, ctx } = await run({ [BT]: btolat(DAY, [['النصر السعودي', 'الخلود', 'ثمانية 1', '']]),
                                   [FG]: filgoal('الخلود', 'النصر', 'ثمانية 2', 'جعفر الصليح') }));
  check('channel: btolat and filgoal conflict -> btolat\'s value (home/away swapped on filgoal still matches)', !errors.length
    && r.line === 'القناة: ثمانية 1 · المعلق: جعفر الصليح', JSON.stringify(r));
  await ctx.close();

  ({ r, asked, errors, ctx } = await run({ [BT]: btolat(DAY, [['النصر السعودي', 'الخلود', 'BEIN SPORTS 3', 'أحمد البلوشي']]) }, { hrs: 60 }));
  check('channel: no followed match within 48 h -> no request at all, nothing shown', !errors.length && asked.length === 0 && !r.x && !r.line,
    JSON.stringify({ r, asked }));
  await ctx.close();

  const cached = ago => ({ 'btolat|2026-05-12': { ago, rows: [['النصر', 'الخلود', '2026-05-12', ['SSC 1'], 'محمد البكر']] },
                           'btolat|2026-05-08': { ago: 80, rows: [] } });
  ({ r, asked, errors, ctx } = await run({}, { cache: cached(3.5) }));
  check('channel: a cached day 3.5 h old (kick-off 10 h away) -> shown from the cache, nothing asked; a 3-day-old entry dropped',
    !errors.length && asked.length === 0 && r.line === 'القناة: SSC 1 · المعلق: محمد البكر' && !r.saved.includes('2026-05-08'),
    JSON.stringify({ r, asked }));
  await ctx.close();
  ({ r, asked, errors, ctx } = await run({}, { cache: cached(4.2) }));
  check('channel: cached 4.2 h ago -> btolat asked again once; down: the last good values stay, no errors', !errors.length
    && asked.length === 1 && /btolat/.test(asked[0]) && r.line === 'القناة: SSC 1 · المعلق: محمد البكر', JSON.stringify({ r, asked }));
  await ctx.close();
  ({ asked, ctx } = await run({}, { cache: cached(1.2), hrs: 5 }));
  check('channel: within 6 h of kick-off with both fields known -> nothing asked any more', asked.length === 0, JSON.stringify(asked));
  await ctx.close();
  const noCom = { 'btolat|2026-05-12': { ago: 1.2, rows: [['النصر', 'الخلود', '2026-05-12', ['SSC 1'], '']] } };
  ({ asked, ctx } = await run({}, { cache: noCom, hrs: 5 }));
  check('channel: within 6 h of kick-off, the commentator missing -> btolat asked again after 1 h, filgoal (never asked) too',
    asked.length === 2 && /btolat/.test(asked[0]) && /filgoal/.test(asked[1]), JSON.stringify(asked));
  await ctx.close();
  ({ asked, ctx } = await run({}, { cache: Object.assign({ 'filgoal|2026-05-12': { ago: 1.2, rows: [] } }, noCom), hrs: 5 }));
  check('channel: filgoal at most every 4 h, even within 6 h of kick-off', asked.length === 1 && /btolat/.test(asked[0]), JSON.stringify(asked));
  await ctx.close();

  // the opponent transliterated in our data (Al Sadd): the followed club alone, when the kick-off agrees within 20 min
  const sadd = rows => run({ [BT]: btolat(DAY, rows) }, { away: 'Al Saad' });
  ({ r, errors, ctx } = await sadd([['النصر السعودي', 'السد القطري', 'BEIN SPORTS 2', '', '06:20 م'], ['الهلال السعودي', 'الشباب', 'ثمانية 1', '', '06:20 م']]));
  check('channel: Al Nassr × Al Sadd (opponent transliterated, «Al Saad») -> matched by the followed club and the kick-off time',
    !errors.length && r.awayKey !== 'سد' && r.line === 'القناة: beIN SPORTS 2', JSON.stringify(r));
  await ctx.close();
  ({ r, errors, ctx } = await sadd([['النصر السعودي', 'السد القطري', 'BEIN SPORTS 2', '', '06:45 م']]));
  check('channel: the followed club alone with a kick-off 25 min off -> nothing shown', !errors.length && !r.x && !r.line, JSON.stringify(r));
  await ctx.close();
  ({ r, errors, ctx } = await sadd([['النصر السعودي', 'السد القطري', 'BEIN SPORTS 2', '', '06:20 م'], ['النصر', 'الوصل', 'BEIN SPORTS 4', '', '06:20 م']]));
  check('channel: the followed club in two rows of the day -> nothing shown', !errors.length && !r.x && !r.line, JSON.stringify(r));
  await ctx.close();
  ({ r, errors, ctx } = await sadd([['النصر السعودي', 'السد القطري', 'BEIN SPORTS 2', '', 'قريباً']]));
  check('channel: no kick-off time on the row -> both names needed, nothing shown', !errors.length && !r.x && !r.line, JSON.stringify(r));
  await ctx.close();

  ({ r, asked, errors, ctx } = await run({}));
  check('channel: both sources down -> nothing shown, no script errors (console only)', !errors.length && !r.x && !r.line
    && !r.strip.includes('bdi') && asked.length === 2, JSON.stringify({ r, asked }) + errors.join('; '));
  await ctx.close();

  ({ r, errors, ctx } = await run({ [BT]: btolat('جدول مباريات اليوم - بطولات', [['النصر السعودي', 'الخلود', 'BEIN SPORTS 3', 'أحمد البلوشي']]),
                                   [FG]: '<html>Just a moment...</html>' }));
  check('channel: btolat answers with today\'s page (not the asked day), filgoal with a challenge page -> nothing shown',
    !errors.length && !r.x && !r.line, JSON.stringify(r));
  await ctx.close();
}

// ---- online background photos (photos/online.json, #photos=saudi,ours,bing,world): picked in turn, an online one's
// credit on screen and its darkening from its brightness, the bundled ones offline
{
  const ONLINE_SAMPLE = { v: 1,
    saudi: [{ u: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/6/67/Al_Ula.jpg/1920px-Al_Ula.jpg',
              page: 'https://commons.wikimedia.org/wiki/File:Al_Ula.jpg', a: 'Sammy Six', l: 'CC BY-SA 4.0', p: 'العُلا', b: 0.8 }],
    world: [{ u: 'https://upload.wikimedia.org/wikipedia/commons/a/a2/World.jpg', page: 'https://commons.wikimedia.org/wiki/File:World.jpg',
              a: 'Diego Delso', l: 'CC BY-SA 4.0', p: '', b: 0.3 }],
    bing: [{ u: 'https://www.bing.com/th?id=OHR.Hegra_EN-US1_1920x1080.jpg', page: 'https://www.bing.com/', a: 'Getty Images',
             l: 'Bing', p: 'Hegra, AlUla' }] };
  const JPG = path.join(ROOT, 'photos/9hWJs7iblh8.jpg');
  // online: the list the page is given (null: online.json unreachable); images: the online photos load (else they fail)
  const photos = (hash, { online = ONLINE_SAMPLE, images = true, cached = online, width = 1920, height = 1080 } = {}) => open('tv.html', hash, { width, height,
    init: cached ? `try{ localStorage.setItem('photoOnline', ${JSON.stringify(JSON.stringify(cached))}); }catch(e){}` : null,
    route: (u, r) => /\/photos\/online\.json$/.test(u) ? (online ? r.fulfill({ body: JSON.stringify(online), contentType: 'application/json' }) : r.abort())
      : /^https:\/\/((upload|thumb)\.wikimedia\.org|www\.bing\.com\/th)/.test(u) ? (images ? r.fulfill({ path: JPG }) : r.abort()) : null });
  const shown = () => {
    const ph = document.querySelector('.photo'), cr = document.getElementById('credit'), b = cr.getBoundingClientRect(), cs = getComputedStyle(cr);
    // what the credit would cover: the ticker tape, the bottom bar's and the home strip's text, the stage's
    const hit = e => { const x = e.getBoundingClientRect(); return x.width && x.height && getComputedStyle(e).visibility !== 'hidden'
      && x.left < b.right && x.right > b.left && x.top < b.bottom && x.bottom > b.top; };
    const clash = [...document.querySelectorAll('.tape, .stage *, .hinfo *')]
      .filter(e => getComputedStyle(e).display !== 'none' && (e.matches('.tape') || (!e.children.length && e.textContent.trim())) && hit(e))
      .map(e => e.className || e.id || e.tagName).slice(0, 5);
    return { bg: ph.style.backgroundImage, dim: ph.style.getPropertyValue('--dim'), credit: cr.textContent,
             visible: cs.display !== 'none', bottom: b.bottom, left: b.left, h: innerHeight, photos: CONFIG.photos,
             size: parseFloat(cs.fontSize), alpha: (cs.color.match(/[\d.]+/g) || [])[3], shadow: cs.textShadow,
             bdi: [...cr.querySelectorAll('bdi')].map(x => x.textContent), clash };
  };
  const settled = p => p.waitForFunction(() => document.querySelector('.photo').style.backgroundImage, null, { timeout: 4000 }).catch(() => {});

  let { p, ctx, errors } = await photos('photos=saudi');
  await settled(p); await p.waitForTimeout(300);
  let r = await p.evaluate(shown);
  check('photos: #photos=saudi -> a Saudi photo from online.json, its credit «المكان · تصوير المصوّر · الرخصة» on screen, above the tape',
    !errors.length && r.bg.includes('thumb.wikimedia.org') && r.credit === 'العُلا · تصوير Sammy Six · CC BY-SA 4.0' && r.visible
    && r.bottom <= r.h - 42, JSON.stringify(r) + errors.join('; '));
  check('photos: the credit readable on a TV: at least 18 px at 1080p, 70% opaque, a soft shadow; author and licence in <bdi>',
    r.size >= 18 && r.alpha === '0.7' && r.shadow !== 'none' && JSON.stringify(r.bdi) === '["Sammy Six","CC BY-SA 4.0"]', JSON.stringify(r));
  check('photos: the credit bottom left, over nothing (the tape, the bottom bar)', r.left < 100 && !r.clash.length, JSON.stringify(r));
  await ctx.close();
  for (const [w, h] of [[1920, 1080], [1280, 720]]) {
    ({ p, ctx, errors } = await photos('photos=saudi&home=1&dockh=104&dockb=72&pillsh=40', { width: w, height: h }));
    await settled(p); await p.waitForTimeout(300);
    const rh = await p.evaluate(shown);
    check(`photos: home ${w}x${h}: the credit above the apps row (dock), bottom left, over nothing`, !errors.length && rh.visible
      && rh.bottom <= rh.h - 72 - 104 && rh.left < 100 && !rh.clash.length, JSON.stringify(rh) + errors.join('; '));
    await ctx.close();
  }
  ({ p, ctx, errors } = await photos('photos=saudi'));
  await settled(p); await p.waitForTimeout(300);
  check('photos: a bright one (b 0.8) gets the strongest darkening (0.32); the default is 0.05',
    r.dim === '0.32' && await p.evaluate(() => photoDim(undefined) === .05 && photoDim(.3) === .05 && Math.abs(photoDim(.6) - .2) < 1e-9), r.dim);
  await ctx.close();

  ({ p, ctx, errors } = await photos(''));
  r = await p.evaluate(() => { const P = photoPools(); return { photos: CONFIG.photos, turn: [0, 1, 2, 3].map(s => photoFor(s, P).on ? 'saudi' : 'ours') }; });
  check('photos: by default saudi and ours, in turn (one each 5-minute slot)',
    JSON.stringify(r) === JSON.stringify({ photos: ['saudi', 'ours'], turn: ['saudi', 'ours', 'saudi', 'ours'] }), JSON.stringify(r));
  await ctx.close();

  // (no list fetched or kept: the bundled one, dist/online.js, picks the photo; offline its picture fails too)
  ({ p, ctx, errors } = await photos('photos=saudi', { online: null, cached: null, images: false }));
  await settled(p); await p.waitForTimeout(1500);
  r = await p.evaluate(shown);
  check('photos: offline (no online.json, none kept, the pictures unreachable) -> a bundled photo, no credit', !errors.length
    && r.bg.includes('/assets/backgrounds/') && !r.visible, JSON.stringify(r) + errors.join('; '));
  await ctx.close();

  ({ p, ctx, errors } = await photos('photos=saudi', { online: null, cached: ONLINE_SAMPLE }));
  await settled(p); await p.waitForTimeout(300);
  r = await p.evaluate(shown);
  check('photos: online.json unreachable -> the list kept from before (localStorage)', !errors.length && r.bg.includes('thumb.wikimedia.org'),
    JSON.stringify(r));
  await ctx.close();

  ({ p, ctx, errors } = await photos('photos=saudi', { images: false }));
  await settled(p); await p.waitForTimeout(1500);
  r = await p.evaluate(shown);
  check('photos: the online photo does not load -> a bundled one, no credit', !errors.length
    && r.bg.includes('/assets/backgrounds/') && !r.visible && !r.credit.includes('Sammy'), JSON.stringify(r) + errors.join('; '));
  await ctx.close();

  ({ p, ctx, errors } = await photos('photos=ours'));
  await settled(p); await p.waitForTimeout(300);
  r = await p.evaluate(shown);
  check('photos: #photos=ours -> the repo\'s own photos (photos/), their credit kept off screen', !errors.length
    && r.bg.includes('/main/photos/') && !r.visible, JSON.stringify(r));
  await ctx.close();

  // the Saudi list switched off (tools/online_photos.py SAUDI = False: an empty saudi list): #photos= with saudi still
  // copes, no errors and no credit line; with ours too, our own photos every slot
  const NO_SAUDI = Object.assign({}, ONLINE_SAMPLE, { saudi: [] });
  ({ p, ctx, errors } = await photos('photos=saudi,ours', { online: NO_SAUDI, cached: NO_SAUDI }));
  await settled(p); await p.waitForTimeout(300);
  r = await p.evaluate(shown);
  const turnNS = await p.evaluate(() => { const P = photoPools(); return [0, 1, 2, 3].map(s => photoFor(s, P).on ? 'online' : 'ours'); });
  check('photos: an empty saudi list, #photos=saudi,ours -> our own photos every slot, no credit, no errors', !errors.length
    && r.bg.includes('/main/photos/') && !r.visible && !r.credit.includes('تصوير') && JSON.stringify(turnNS) === '["ours","ours","ours","ours"]',
    JSON.stringify(r) + JSON.stringify(turnNS) + errors.join('; '));
  await ctx.close();

  ({ p, ctx, errors } = await photos('photos=saudi', { online: NO_SAUDI, cached: NO_SAUDI }));
  await settled(p); await p.waitForTimeout(1500);
  r = await p.evaluate(shown);
  check('photos: an empty saudi list, #photos=saudi alone -> a bundled photo, no credit, no errors', !errors.length
    && r.bg.includes('/assets/backgrounds/') && !r.visible && !r.credit.includes('تصوير'), JSON.stringify(r) + errors.join('; '));
  await ctx.close();

  ({ p, ctx, errors } = await photos('photos=bing'));
  await settled(p); await p.waitForTimeout(300);
  r = await p.evaluate(shown);
  check('photos: #photos=bing -> Bing\'s photo of the day, «المكان · تصوير … · Bing»', !errors.length && r.bg.includes('bing.com')
    && r.credit === 'Hegra, AlUla · تصوير Getty Images · Bing' && r.visible && r.dim === '0.05', JSON.stringify(r));
  await ctx.close();

  ({ p, ctx, errors } = await photos('photos=world,nonsense'));
  await settled(p); await p.waitForTimeout(300);
  r = await p.evaluate(shown);
  check('photos: #photos=world -> Commons\' picture of the day, no place in its credit; an unknown id ignored',
    !errors.length && JSON.stringify(r.photos) === '["world"]' && r.credit === 'تصوير Diego Delso · CC BY-SA 4.0', JSON.stringify(r));
  await ctx.close();

  ({ p, ctx, errors } = await photos('photos=nonsense'));
  r = await p.evaluate(() => CONFIG.photos);
  check('photos: nothing known in #photos -> the defaults (saudi, ours)', JSON.stringify(r) === '["saudi","ours"]', JSON.stringify(r));
  await ctx.close();

  // a real one from photos/online.json, for a look (SHOTS=1; optimize/run/photos-check/shot.jpg: its photo, if downloaded)
  if (process.env.SHOTS) {
    const real = JSON.parse(readFileSync(path.join(ROOT, 'photos/online.json'), 'utf8'));
    const pick = real.saudi.find(x => x.p === (process.env.SHOT_PLACE || 'العُلا')) || real.saudi[0];
    const shotJpg = path.join(ROOT, 'optimize/run/photos-check/shot.jpg');
    for (const [hash, shot] of [['photos=saudi', 'bg_saudi.png'], ['photos=saudi&home=1&dockh=104&dockb=72&pillsh=40', 'bg_saudi_home.png']]) {
      ({ p, ctx, errors } = await open('tv.html', hash, {
        init: `try{ localStorage.setItem('photoOnline', ${JSON.stringify(JSON.stringify({ v: 1, saudi: [pick], world: [], bing: [] }))}); }catch(e){}`,
        route: (u, r) => /\/photos\/online\.json$/.test(u) ? r.abort()
          : /^https:\/\/(upload|thumb)\.wikimedia\.org/.test(u) ? r.fulfill({ path: existsSync(shotJpg) ? shotJpg : JPG }) : null }));
      await settled(p); await p.waitForTimeout(1500);
      await p.screenshot({ path: path.join(ROOT, 'optimize/run/shots', shot) });
      await ctx.close();
    }
  }
}

// ---- «الجديد» (#look=new, the settings' «شكل الواجهة»): the home screen and the screensaver in the new look (nxBoot),
// with sample data at the mock-ups' time (Saturday 3:24 pm in Riyadh): what it shows, nothing overlapping or cut at
// 1280 x 720 and 1920 x 1080, the standfirst without some of its data, the row's cards from the pages ticked, the
// launcher's bridge over the «اليوم» row, a switch of look (a new address, as the launcher reloads), the screensaver
{
  const AT = new Date('2026-10-24T12:24:00Z');
  const NXH = 'city=الرياض&lat=24.7136&lon=46.6753&tz=Asia%2FRiyadh&teams=136013&home=1&look=new';
  // the room the launcher's own views take at that size (its dp are the page's CSS px times the scale: 1 at 960 x 540)
  const room = (w, h) => { const z = Math.min(w / 960, h / 540); return { dockh: Math.round(100 * z), dockb: Math.round(44 * z) }; };
  const SAMPLE = () => {
    const now = Date.now(), it = (headline, source, h, summary) => ({ headline, source, t: now - h * 36e5, link: 'https://example.com/' + h, summary });
    NX_WX = { t: 31, code: 0, hum: 12, hi: 33, lo: 19 };
    FORECAST = [0, 1, 2, 3, 4, 5, 6].map(i => ({ date: new Date(now + i * 864e5).toISOString().slice(0, 10), code: i % 3, hi: 33 - i, lo: 19 + (i % 4) }));
    NEWS.news = [it('انطلاق موسم الرياض 2026 بـ11 منطقة ترفيهية', 'واس', 1, 'يبدأ الموسم الليلة في بوليفارد سيتي بعروض وحفلات وبطولات، ومنطقة جديدة للعائلات قرب وادي حنيفة.'),
      it('أمطار رعدية على مرتفعات عسير والباحة حتى الثلاثاء', 'الشرق الأوسط', 2, ''), it('تاسي يغلق منخفضاً 2.3% بضغط من أسهم البنوك', 'العربية', 3, '')];
    NEWS.world = [it('قمة المناخ تتفق على تمويل جديد للدول النامية', 'سكاي نيوز عربية', 1, 'اتفق المشاركون على صندوق جديد.')];
    NEWS.tech = [it('آبل تكشف عن جيل جديد من رقائقها', 'عالم التقنية', 2, '')];
    const t = now + 5.6 * 36e5, ev = { idEvent: 'x1', strHomeTeam: 'Al-Hilal', strAwayTeam: 'Al-Nassr', strTimestamp: new Date(t).toISOString().slice(0, 19), idHomeTeam: '136013' };
    MATCHES = { '136013': ev }; NEXT_LIST = { '136013': [ev] };
    BC['btolat|' + bcDay(t)] = { at: now, rows: [[bcKey(teamAr('Al-Hilal')), bcKey(teamAr('Al-Nassr')), bcDay(t), ['beIN SPORTS 1'], 'فهد العتيبي', t]] };
    if (!NXS) { TAPE.forEach((x, i) => { TAPE_Q[i] = mkTapeHtml(x.fmt === 'fx' ? 3.75 : 9696.4, i % 3 ? 1.21 : -2.32, x.fmt); }); tapeRender(); }
    STANDINGS = [['Al-Hilal', 30, 1, '136013'], ['Al-Nassr', 27, 2], ['Al-Ittihad', 25, 3], ['Al-Ahli', 22, 4], ['Al-Qadsiah', 20, 5], ['Al-Taawoun', 18, 6]]
      .map(([name, pts, rank, tsdbId]) => ({ name, pts, rank, gp: 10, tsdbId }));
    if (NXH && !HADITH_DAY) HADITH_DAY = { day: riyadhDayNumber(), n: 1, head: 'عن عمر بن الخطاب رضي الله عنه', matn: 'إنما الأعمال بالنيات', offline: true };
    renderMatches(); tickPrayer(); tickRamadan(); nxDraw();
  };
  const nxOpen = async (hash, o = {}) => {
    const r = await open('tv.html', hash, Object.assign({ at: AT }, o));
    await r.p.evaluate(SAMPLE);
    await r.p.waitForTimeout(400);   // the tape's layout waits for the fonts
    return r;
  };
  // the parts' boxes, top to bottom, and anything cut inside a card
  const layout = () => {
    const R = e => { const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom }; };
    const q = s => document.querySelector(s), shown = e => e && getComputedStyle(e).display !== 'none' && e.getBoundingClientRect().height > 0;
    const dlBox = R(q('#nx-dl')), dl = [...q('#nx-dl').children].map(R).filter(x => x.t < dlBox.b - 1);
    const cards = [...q('#nx-rail').children].map(c => ({ k: c.dataset.k, ...R(c), sh: c.scrollHeight, ch: c.clientHeight }));
    // (a line that ends in «…» by design, the headlines, the sources, the meta lines, is not counted)
    const cut = [...document.querySelectorAll('.nx-c *')].filter(e => shown(e) && !e.closest('.bc, .nx-nl') && !e.closest('svg')
      && e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).overflow === 'hidden' && getComputedStyle(e).textOverflow !== 'ellipsis').map(e => e.className || e.tagName);
    const tape = q('#tape');
    return { W: innerWidth, H: innerHeight, dl, dlBox, deck: R(q('.nx-dk')), clock: R(q('.nx-ck')), hero: R(q('.nx-hero')),
             today: R(q('.nx-rh')), cards, apps: R(q('#nx-ah')), mk: R(q('#nx-mk')), mkShown: shown(q('#nx-mk')),
             tape: tape.hidden ? null : R(tape), cut, credit: shown(q('#credit')) ? R(q('#credit')) : null,
             classic: [...document.querySelectorAll('.stage > :not(#nx)')].filter(shown).length,
             stand: q('#nx-st').textContent, keys: cards.map(c => c.k).join(),
             chips: [...document.querySelectorAll('.nx-ch span')].map(x => x.textContent + (x.className ? '*' : '')).join() };
  };
  const apart = (a, b) => a.r <= b.l + 1 || b.r <= a.l + 1 || a.b <= b.t + 1 || b.b <= a.t + 1;
  const ROW = 'prayers,news,deen,sports,forecast', WIDE = { prayers: 170, news: 250, deen: 168, sports: 140, forecast: 104 };
  // 960 x 540 too: the TV's own CSS px (1280 x 720 at 1.33 dp a px), the launcher's apps row exactly HomeLook's 100 / 44
  for (const [w, h] of [[1920, 1080], [1280, 720], [960, 540]]) {
    const { dockh, dockb } = room(w, h), z = Math.min(w / 960, h / 540);
    for (const focus of [0, 4]) {   // the first card focused (as the row starts), then the last
      const { p, ctx, errors } = await nxOpen(`${NXH}&panel=none&rotate=30&dockh=${dockh}&dockb=${dockb}`, { width: w, height: h });
      // the cards as seen: four shown wholly between the margins, a fifth (.o) out past an edge, its sliver in the margin
      const seen = () => [...document.querySelectorAll('#nx-rail > .nx-c')].map(c => { const b = c.getBoundingClientRect();
        return { k: c.dataset.k, l: Math.round(b.left), r: Math.round(b.right), w: b.width, o: c.classList.contains('o'), shown: getComputedStyle(c).visibility !== 'hidden' }; });
      const whole = s => s.every(c => c.shown && !c.o && c.l >= Math.floor(40 * z) - 1 && c.r <= w - Math.floor(40 * z) + 1);
      const offL = c => c.o && c.l < 40 * z && c.r <= 40 * z, offR = c => c.o && c.l >= w - 40 * z - 1 && c.l < w;   // its sliver in the left, the right margin
      if (!focus) {   // at rest (nothing focused yet), the match on a long weekday name («الأربعاء 4:24 م», not cut) beside the standfirst
        await p.evaluate(() => { const ev = MATCHES['136013']; ev.strTimestamp = new Date(Date.now() + 97 * 36e5).toISOString().slice(0, 19); NEXT_LIST['136013'] = [ev]; renderMatches(); nxDraw(); });
        await p.waitForTimeout(200);
        const s = await p.evaluate(seen), mx = await p.evaluate(() => { const R = e => { const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom }; };
          const e = document.getElementById('nx-mx'), wh = e.querySelector('.wh');
          return { shown: !e.hidden && getComputedStyle(e).display !== 'none', box: R(e), dl: R(document.getElementById('nx-dl')), dk: R(document.querySelector('.nx-dk')),
                   wh: wh && wh.textContent, whole: !!wh && wh.scrollWidth <= wh.clientWidth + 1, k: (e.querySelector('.k') || {}).textContent,
                   rail: document.getElementById('nx-rail').style.transform }; });
        check(`new look home ${w}x${h} at rest: the five cards (prayer, news, deen, sports, weather), the first four wholly between the margins in proportion to 170/250/168/140 filling the 880, the weather past the left edge, dimmed, the row unmoved`,
          s.map(c => c.k).join() === ROW && whole(s.slice(0, 4)) && s.slice(0, 4).every(c => Math.abs(c.w - WIDE[c.k] * 844 / 728 * z) < 1) && offL(s[4]) && !mx.rail, JSON.stringify({ s, mx }));
        check(`new look home ${w}x${h}: the next match beside the standfirst («المباراة القادمة», «الأربعاء 4:24 م» whole), inside the 40 px margins, clear of the dateline and the standfirst`,
          mx.shown && mx.k === 'المباراة القادمة' && mx.whole && mx.wh.startsWith('الأربعاء') && mx.box.l >= 40 * z - 1 && mx.box.r <= w - 40 * z + 1
          && apart(mx.box, mx.dl) && apart(mx.box, mx.dk), JSON.stringify(mx));
        await p.evaluate(() => { const ev = MATCHES['136013']; ev.strTimestamp = new Date(Date.now() + 5.6 * 36e5).toISOString().slice(0, 19); NEXT_LIST['136013'] = [ev]; renderMatches(); nxDraw(); });
      }
      await p.evaluate(k => { window.sauditvSetFocused(true); for (let i = 0; i < k; i++) window.sauditvStep(1); }, focus);
      await p.waitForTimeout(300);
      const seenNow = await p.evaluate(seen), moved = await p.evaluate(() => document.getElementById('nx-rail').style.transform);
      if (process.env.SHOTS && w === 1920) await p.screenshot({ path: path.join(ROOT, `optimize/run/shots/nx-row-${focus ? 'weather' : 'prayers'}.png`) });
      if (!focus) check(`new look home ${w}x${h}, the prayer focused: the first four cards wholly on the screen, the weather past the left edge, the row not slid`,
        seenNow.length === 5 && whole(seenNow.slice(0, 4)) && offL(seenNow[4]) && !moved, JSON.stringify(seenNow));
      else {
        check(`new look home ${w}x${h}, the weather focused: the row slid, the last four wholly on the screen, the prayer card out past the right edge, dimmed`,
          seenNow.length === 5 && whole(seenNow.slice(1)) && offR(seenNow[0]) && /translateX/.test(moved), JSON.stringify({ seenNow, moved }));
      }
      const r = await p.evaluate(layout), tag = `new look home ${w}x${h}${focus ? ', the weather focused' : ''}`;
      const band = h - dockb - dockh, cardsTop = Math.min(...r.cards.map(c => c.t)), cardsBottom = Math.max(...r.cards.map(c => c.b));
      if (!focus) {
        check(`${tag}: no errors; the classic stage hidden; the row: nothing ticked, the five cards`,
          !errors.length && r.classic === 0 && r.keys === ROW, JSON.stringify(r.keys) + errors.join('; '));
        check(`${tag}: the dateline, the clock and the standfirst from the data`, r.dl.length === 3 && r.stand.startsWith('سماء صافية و31°، والمغرب بعد ')
          && r.stand.endsWith('.'), JSON.stringify([r.dl.length, r.stand]));
      }
      check(`${tag}: top to bottom without overlap: the dateline, the clock and its standfirst, «اليوم», the cards, «التطبيقات», the launcher's apps row, «الأسواق»`,
        r.dlBox.t >= 0 && r.dl.every(x => x.l >= 0 && x.r <= r.W) && r.deck.t >= r.dlBox.b - 1 && r.clock.t >= r.dlBox.b - 1
        && r.hero.l >= 0 && r.hero.r <= r.W && Math.max(r.deck.b, r.clock.b) <= r.today.t + 1 && r.today.b <= cardsTop + 1
        && cardsBottom <= r.apps.t + 1 && r.apps.b <= band + 1 && r.mk.t >= h - dockb - 1 && r.mk.b <= h && r.mkShown
        && r.tape && r.tape.t >= r.mk.t - 1 && r.tape.b <= h && r.tape.l >= 0 && r.tape.r <= r.W,
        JSON.stringify({ band, cardsTop, cardsBottom, dl: r.dlBox, deck: r.deck, clock: r.clock, today: r.today, apps: r.apps, mk: r.mk, tape: r.tape }));
      check(`${tag}: nothing cut in the cards (their text whole, no card taller than its box), the photo's credit clear of the clock`,
        !r.cut.length && r.cards.every(c => c.sh <= c.ch + 1) && (!r.credit || (apart(r.credit, r.hero) && apart(r.credit, r.dlBox))),
        JSON.stringify({ cut: r.cut, cards: r.cards.map(c => [c.k, c.sh, c.ch]), credit: r.credit }));
      const f = await p.evaluate(() => { const n = document.querySelector('.nx-c.f'); return n && { k: n.dataset.k, wide: n.classList.contains('w'),
        w: n.getBoundingClientRect().width, bg: getComputedStyle(n).backgroundColor, panel: window.sauditvCurrentPanel() }; });
      check(focus ? `${tag}: the weather card in paper, widened to 340 px; OK opens the weather`
                  : `${tag}: the prayer card in paper, widened to 340 px, on the next prayer; OK opens the prayers`,
        f && f.k === (focus ? 'forecast' : 'prayers') && !f.wide && Math.abs(f.w - 340 * z) < 1 && f.bg === 'rgb(246, 239, 225)'
        && f.panel === (focus ? 'forecast' : 'prayers'), JSON.stringify(f));
      if (focus) {   // the deen card's views, the sports card's table, the weather (on either view: today and the week), each whole in its box
        for (const [k, v] of [['deen', 'verse'], ['deen', 'hadith'], ['sports', 'standings'], ['forecast', 'week'], ['forecast', 'today']]) {
          const c = await p.evaluate(([k, v]) => { NX_CUR[k] = v; NX_K = k; nxDraw();
            const e = document.querySelector(`#nx-rail > [data-k="${k}"]`), R = x => x.getBoundingClientRect(), C = R(e), dt = e.querySelector('.nx-dots');
            const on = x => getComputedStyle(x).display !== 'none' && R(x).height > 0, days = [...e.querySelectorAll('.nx-wk li')].filter(on), nb = e.querySelector('.nx-td .nb');
            return { cls: e.className, sh: e.scrollHeight, ch: e.clientHeight, on: (e.querySelector('.nx-ch .on') || {}).textContent, rows: e.querySelectorAll('.nx-sl li').length,
                     days: days.length, first: days.length ? days[0].firstChild.textContent : '', deg: nb && on(nb) ? nb.textContent : '', kicker: e.querySelector('.k').innerText.trim(),
                     // the weather's parts (today's column, each day's row and its parts) whole: inside the card, above its dots, not cut
                     whole: [...e.querySelectorAll('.nx-td, .nx-td > *, .nx-wk')].filter(on).concat(days, days.flatMap(x => [...x.children])).every(x => { const b = R(x);
                       return b.left >= C.left + 1 && b.right <= C.right - 1 && b.bottom <= (dt ? R(dt).top : C.bottom) + .5 && x.scrollWidth <= x.clientWidth + 1; }),
                     body: [...e.querySelectorAll('.nx-db > *, .nx-sl')].every(x => R(x).bottom <= R(e).bottom + .5), panel: window.sauditvCurrentPanel() }; }, [k, v]);
          if (process.env.SHOTS && w === 1920 && v === 'today') await p.screenshot({ path: path.join(ROOT, 'optimize/run/shots/nx-row-weather-week.png') });
          check(k === 'forecast' ? `${tag}: the weather card focused on ${v}: «الطقس», today's degrees in its column and the week from «غداً» (five days or more) beside it, all whole in the card`
                                 : `${tag}: the ${k} card on ${v} whole in its box`, c.sh <= c.ch + 1 && c.body && c.panel === (k === 'forecast' ? 'forecast' : v)
            && (v === 'standings' ? c.rows === 5 && c.on === 'الترتيب' : k === 'forecast' ? c.days >= 5 && c.first === 'غداً' && /31°/.test(c.deg) && c.kicker === 'الطقس' && c.whole
              : c.cls.includes('nx-q') && c.on === (v === 'verse' ? 'آية اليوم' : 'حديث اليوم')), JSON.stringify(c));
        }
        // ▶ back to the sports card: the window kept (the prayer card still out past the right); on to the prayer card: slid back
        await p.evaluate(() => window.sauditvStep(-1));
        await p.waitForTimeout(300);
        const sticky = await p.evaluate(seen), stickyMoved = await p.evaluate(() => [NX_K, document.getElementById('nx-rail').style.transform]);
        await p.evaluate(() => { for (let i = 0; i < 3; i++) window.sauditvStep(-1); });
        await p.waitForTimeout(300);
        const home = await p.evaluate(seen), homeMoved = await p.evaluate(() => [NX_K, document.getElementById('nx-rail').style.transform]);
        check(`${tag}: ▶ back to the sports card keeps the row slid (the last four in view); on to the prayer card the row back, the weather past the left edge again`,
          stickyMoved[0] === 'sports' && /translateX/.test(stickyMoved[1]) && whole(sticky.slice(1)) && offR(sticky[0])
          && homeMoved[0] === 'prayers' && !homeMoved[1] && whole(home.slice(0, 4)) && offL(home[4]), JSON.stringify({ sticky, stickyMoved, home, homeMoved }));
      }
      await ctx.close();
    }
  }

  // the dateline with the years («… 1448 هـ», one «هـ»; «… 2026») at 960 x 540, the launcher's pills to 300 px: all of
  // it clear of them, Ramadan's countdown on the line over the standfirst when it no longer fits there
  {
    const { p, ctx, errors } = await nxOpen(`${NXH}&panel=none&rotate=30`, { width: 960, height: 540 });
    const r = await p.evaluate(async () => {
      window.sauditvPillsEnd(300);
      await new Promise(r => setTimeout(r, 200));
      const dl = document.getElementById('nx-dl'), b = dl.getBoundingClientRect(), on = [...dl.children].filter(s => s.getBoundingClientRect().top < b.bottom - 1);
      return { left: Math.min(...on.map(s => s.getBoundingClientRect().left)), boxLeft: b.left, shown: on.map(s => s.textContent),
               all: [...dl.children].map(s => s.textContent), kicker: document.getElementById('nx-kk').textContent };
    });
    const [hj, gr] = r.all;
    check('new look 960x540: the dateline\'s Hijri date with its year and one «هـ», the Gregorian with its year, clear of the launcher\'s pills (300 px)',
      !errors.length && /^السبت \d+ جمادى الأولى 1448 هـ$/.test(hj) && (hj.match(/هـ/g) || []).length === 1 && /^\d+ أكتوبر 2026$/.test(gr)
      && r.shown.includes(hj) && r.shown.includes(gr) && r.left >= 300 && r.boxLeft >= 300,
      JSON.stringify(r) + errors.join('; '));
    check('new look 960x540: Ramadan\'s countdown on the dateline, or (left out there) over the standfirst',
      r.shown.some(t => t.includes('رمضان')) !== r.kicker.endsWith('· رمضان بعد 107 يوم'), JSON.stringify(r));
    await ctx.close();
  }

  // the standfirst with some of its data missing: that half left out, nothing put in its place
  {
    const { p, ctx, errors } = await nxOpen(`${NXH}&panel=none`);
    const r = await p.evaluate(() => {
      const P = nxPrayer(), W = NX_WX, out = {};
      out.both = nxLine(P, W);
      out.noWeather = nxLine(P, null);
      out.noPrayer = nxLine(null, W);
      out.none = nxLine(null, null);
      out.dust = nxLine(null, Object.assign({}, W, { dust: 'غبار' }));
      out.cloudy = nxLine(null, Object.assign({}, W, { code: 3 }));
      const keep = PRAYERS; PRAYERS = []; NX_WX = null; nxDraw();
      out.shown = getComputedStyle(document.getElementById('nx-st')).display; out.meta = getComputedStyle(document.getElementById('nx-mt')).display;
      out.cards = [...document.querySelectorAll('#nx-rail > *')].map(c => c.dataset.k).join();
      out.list = window.sauditvDetailList(); out.wx = NX_CUR.forecast;
      out.wxDots = document.querySelectorAll('[data-k="forecast"] .nx-dots, [data-k="forecast"] .nx-ln').length;
      PRAYERS = keep;
      return out;
    });
    const txt = s => s.replace(/<[^>]+>/g, '');
    check('new look: the standfirst «سماء صافية و31°، والمغرب بعد ساعة و…» with both halves',
      !errors.length && /^سماء صافية و.*31°.*، والمغرب بعد ساعة و\d+ دقيقة\.$/.test(txt(r.both)), JSON.stringify(r));
    check('new look: without the weather only the prayer («المغرب بعد ….»), without the prayers only the weather («سماء صافية و31°.»)',
      /^المغرب بعد ساعة و\d+ دقيقة\.$/.test(txt(r.noWeather)) && txt(r.noPrayer) === 'سماء صافية و31°.' && txt(r.dust) === 'غبار و31°.'
      && txt(r.cloudy) === 'غائم و31°.', JSON.stringify(r));
    check('new look: with neither, no standfirst at all (hidden, no placeholder), no weather line; no prayer card, the weather card on the week alone (no dots)',
      r.none === '' && r.shown === 'none' && r.meta === 'none' && r.cards === 'news,deen,sports,forecast' && r.wx === 'week' && !r.wxDots
      && !r.list.includes('prayers') && r.list.endsWith(',forecast') && !/[—…]|undefined|NaN/.test(JSON.stringify(r)), JSON.stringify(r));
    await ctx.close();
  }

  // the news card's tabs: the news pages ticked (#panel/#panels), in their order, each only with its data; none
  // ticked: the country's, the world's, tech, the city's; no news page ticked: no news card
  for (const [hash, a0, b0, tag] of [['&panel=prayers&panels=prayers,world,tech&rotate=30', 'العالم*,التقنية', 'العالم*', 'world and tech ticked'],
                                     ['&panel=local&panels=local,news,hadith&rotate=30', 'مدينتك,مصر*', 'مدينتك,مصر*', 'the city and the country ticked; the country on, its news came first'],
                                     ['&panel=none', 'مصر*,العالم,التقنية,مدينتك', 'مصر*,العالم,مدينتك', 'none ticked'], ['&panel=forecast', '', '', 'no news ticked']]) {
    const { p, ctx, errors } = await nxOpen(NXH + '&newscc=EG&newsname=' + encodeURIComponent('مصر') + hash);
    const chips = () => [...document.querySelectorAll('[data-k="news"] .nx-ch span')].map(x => x.textContent + (x.className ? '*' : '')).join();
    await p.evaluate(() => { NEWS.local = NEWS.news.slice(); NEWS.world = NEWS.news.slice(); NEWS.tech = NEWS.news.slice(); nxDraw(); });
    const a = await p.evaluate(chips);
    await p.evaluate(() => { NEWS.tech = []; nxDraw(); });
    const b = await p.evaluate(chips);
    check(`new look: the news tabs are the news pages ticked, in their order (${tag}), a tab with no data left out`,
      !errors.length && a === a0 && b === b0, JSON.stringify({ a, b }) + errors.join('; '));
    await ctx.close();
  }

  // the home loads what its row shows: nothing ticked, the news pages, the city's news and the league's table are asked for
  {
    const asked = [];
    const { p, ctx, errors } = await open('tv.html', NXH + '&panel=none', { at: AT, route: u => { asked.push(u); return null; } });
    await p.waitForTimeout(800);
    const u = asked.join('\n');
    check('new look: the home asks for the news pages, the table and the week\'s weather with nothing ticked',
      !errors.length && /espn|thesportsdb/i.test(u) && /news/i.test(u) && /forecast_days=7/.test(u), asked.slice(0, 40).join(' | ') + errors.join('; '));
    await ctx.close();
  }

  // the launcher's bridge over the «اليوم» row: its box (cardRect), ◀ ▶ through the cards without wrapping, the page OK
  // opens (the view shown's) and the row's views for the detail window, the views last read there, the mouse
  {
    const init = () => { window.__rects = []; window.SaudiTVNative = { cardRect: (...a) => window.__rects.push(a), qrRect: () => {} }; };
    const { p, ctx, errors } = await nxOpen(`${NXH}&panel=prayers&panels=prayers,news,world,forecast,matches,hadith&rotate=30&dockh=200&dockb=88`, { init });
    const r = await p.evaluate(() => {
      nxDraw();
      const out = { rect: window.__rects[window.__rects.length - 1] };
      window.sauditvSetFocused(true);
      out.first = window.sauditvCurrentPanel();
      window.sauditvStep(-1); out.right = window.sauditvCurrentPanel();
      window.sauditvStep(1); out.news = window.sauditvCurrentPanel();
      window.sauditvStep(1); out.sp = window.sauditvCurrentPanel(); window.sauditvStep(1); out.dn = window.sauditvCurrentPanel();
      window.sauditvStep(1); out.wx = window.sauditvCurrentPanel(); NX_CUR.forecast = 'week'; nxDraw(); out.wk = window.sauditvCurrentPanel();
      for (let i = 0; i < 9; i++) window.sauditvStep(1);
      out.last = window.sauditvCurrentPanel();
      out.keys = [...document.querySelectorAll('#nx-rail > *')].map(c => c.dataset.k);
      out.dots = [...document.querySelectorAll('#nx-rail > *')].map(c => c.dataset.k + ':' + c.querySelectorAll('.nx-dots b').length + (c.querySelector('.nx-ln') ? 'l' : ''));
      out.list = window.sauditvDetailList();
      localStorage.setItem('sauditv-card-pos', JSON.stringify({ panel: 'world', at: Date.now() }));
      window.sauditvHold(false);
      return out;
    });
    await p.waitForTimeout(300);
    // the last focused: the four before it in view, the first out past the right (.o)
    r.inView = await p.evaluate(() => { const L = [...document.querySelectorAll('.nx-c')], v = L.filter(e => !e.classList.contains('o'));
      return v.length === 4 && L[0].classList.contains('o') && v.every(e => { const c = e.getBoundingClientRect(); return c.left >= 80 - 1 && c.right <= innerWidth - 80 + 1; }); });
    const tab = await p.evaluate(() => { const b = document.querySelector('.nx-n').getBoundingClientRect();
      window.sauditvPoint(b.left + 20, b.top + 20);
      return { chips: [...document.querySelectorAll('.nx-n .nx-ch span')].map(x => x.textContent + (x.className ? '*' : '')).join(), panel: window.sauditvCurrentPanel() }; });
    const sp = await p.evaluate(async () => {
      localStorage.setItem('sauditv-card-pos', JSON.stringify({ panel: 'hadith', at: Date.now() }));
      window.sauditvHold(true); window.sauditvHold(false);
      await new Promise(r => setTimeout(r, 200));
      const d = document.querySelector('[data-k="deen"]'), b = d.getBoundingClientRect();
      window.sauditvPoint(b.left + 20, b.top + 20);
      return { chips: [...d.querySelectorAll('.nx-ch span')].map(x => x.textContent + (x.className ? '*' : '')).join(), panel: window.sauditvCurrentPanel(), tab: NX_CUR.news };
    });
    check('new look: the row\'s box goes to the launcher (cardRect: the screen\'s left edge to the right margin, CSS px)',
      !errors.length && r.rect && r.rect[0] === 0 && r.rect[1] === 376 && r.rect[2] === 1920 - 80 && r.rect[3] === 352 && r.rect[4] === 1920,
      JSON.stringify(r.rect) + errors.join('; '));
    check('new look: the row folded in the settings\' order, the weather always last (the prayer, the news, the sports, the deen card, the weather); ◀ ▶ through it, no wrapping, the last four in view at the end',
      r.first === 'prayers' && r.right === 'prayers' && r.news === 'news' && r.sp === 'matches' && r.dn === 'hadith' && r.wx === 'forecast' && r.wk === 'forecast' && r.last === 'forecast'
      && r.keys.join() === 'prayers,news,sports,deen,forecast' && r.inView, JSON.stringify(r));
    check('new look: only a card with two views or more carries the dots and the line (the news, the weather last)',
      r.dots.join() === 'prayers:0,news:2l,sports:0,deen:0,forecast:2l', JSON.stringify(r.dots));
    check('new look: the detail window goes through the row\'s cards in the row\'s order, each its views with data (the weather once, last)',
      r.list === 'prayers,news,world,matches,hadith,forecast', r.list);
    check('new look: the news tab last read in the detail window is the one shown; the mouse over the news card focuses it',
      tab.chips === 'السعودية,العالم*' && tab.panel === 'world', JSON.stringify(tab));
    check('new look: the view last read on a single-view card (the hadith) is shown after the detail window; OK there opens it, the news tab kept',
      sp.chips === 'حديث اليوم*' && sp.panel === 'hadith' && sp.tab === 'world', JSON.stringify(sp));
    // the launcher's pills end at 1300 px (its window's px; this page's devicePixelRatio is 1)
    const pe = await p.evaluate(async () => {
      window.sauditvPillsEnd(1300);
      await new Promise(r => setTimeout(r, 200));
      const dl = document.getElementById('nx-dl'), b = dl.getBoundingClientRect();
      return { left: b.left, kicker: document.getElementById('nx-kk').textContent,
               shown: [...dl.children].filter(s => s.getBoundingClientRect().top < b.bottom - 1).map(s => s.textContent) };
    });
    check('new look: the dateline keeps clear of the launcher\'s pills (sauditvPillsEnd); Ramadan\'s countdown left out there goes over the standfirst',
      pe.left >= 1300 && pe.shown.length >= 1 && !pe.shown.some(t => t.includes('رمضان')) && pe.kicker.endsWith('· رمضان بعد 107 يوم'), JSON.stringify(pe));
    // no match: the block beside the standfirst hidden
    const nm = await p.evaluate(() => { MATCHES = {}; NEXT_LIST = {}; renderMatches(); nxDraw(); const e = document.getElementById('nx-mx');
      return { hidden: e.hidden, display: getComputedStyle(e).display }; });
    check('new look: with no match, no match block beside the standfirst', nm.hidden && nm.display === 'none', JSON.stringify(nm));
    await ctx.close();
  }

  // the «اليوم» row from the pages ticked for the home screen (#panels), folded, each card at its first page's place
// (the weather always last);
  // at 960 x 540 four at most wholly between the margins, sharing the row's 880 px in proportion to their widths; four
  // or fewer never slid, a fifth past the left edge until focused, then the row slid and the first past the right.
  // SHOTS=1: optimize/run/shots/nx-home-order.png (960 x 540)
  for (const [hash, keys, news, tag] of [['&panel=hadith&panels=hadith,world,forecast,news,prayers,tech,matches', 'deen,news,prayers,sports,forecast', 'world,news,tech', 'seven ticked, the weather last'],
                                         ['&panel=verse&panels=verse,news', 'deen,news', 'news', 'the verse and the news'], ['&panel=forecast', 'forecast', '', 'the weather alone'],
                                         ['&panel=none', ROW, 'news,world,tech,local', 'nothing ticked'], ['&panel=standings&panels=standings,matches', 'sports', '', 'the table and the matches'],
                                         ['&panel=matches&panels=matches,prayers,hadith,tech,news', 'sports,prayers,deen,news', 'tech,news', 'five ticked, folded']]) {
    const o = await nxOpen(`${NXH}${hash}&rotate=15&dockh=100&dockb=44`, { width: 960, height: 540 });
    await o.p.waitForTimeout(200);
    const k = await o.p.evaluate(async () => {
      const box = () => [...document.querySelectorAll('#nx-rail > *')].map(c => { const b = c.getBoundingClientRect(); return [Math.round(b.left * 10) / 10, Math.round(b.right * 10) / 10, c.classList.contains('o')]; });
      const out = { keys: [...document.querySelectorAll('#nx-rail > *')].map(c => c.dataset.k).join(), news: (NX_VIEWS.news || []).join(), boxes: box() };
      window.sauditvSetFocused(true); for (let i = 0; i < 5; i++) window.sauditvStep(1);
      await new Promise(r => setTimeout(r, 200));
      out.after = box(); out.slid = document.getElementById('nx-rail').style.transform;
      out.fw = document.querySelector('.nx-c.f').getBoundingClientRect().width;
      window.sauditvSetFocused(false);
      await new Promise(r => setTimeout(r, 200));
      out.back = box();
      return out; });
    if (process.env.SHOTS && tag === 'nothing ticked') await o.p.screenshot({ path: path.join(ROOT, 'optimize/run/shots/nx-home-order.png') });
    // four in view at most: with five the fifth past the left edge (.o, its sliver in the margin) at rest, the first
    // past the right once the last is focused, the row slid
    const ks = k.keys.split(','), five = ks.length > 4, share = k.boxes.slice(0, 4).map((b, i) => (b[1] - b[0]) / WIDE[ks[i]]);
    const inRow = B => B[0][1] === 920 && B[B.length - 1][0] === 40 && B.every(b => !b[2] && b[0] >= 40 && b[1] <= 920 && b[1] - b[0] >= 89.5);
    const offL = b => b[2] && b[0] < 40 && b[1] <= 28.5, offR = b => b[2] && b[0] >= 931.5;
    check(`new look: the row's cards (${tag}): ${keys}, the news tabs ${news || 'none'}, ${five ? 'the first four' : 'all'} wholly between the 40 px margins at 960 x 540, filling the row in proportion${five ? ', the fifth past the left edge' : ''}`,
      !o.errors.length && k.keys === keys && k.news === news && k.boxes.length === ks.length && inRow(k.boxes.slice(0, 4)) && (!five || offL(k.boxes[4]))
      && share.every(x => Math.abs(x - share[0]) < .02), JSON.stringify(k) + o.errors.join('; '));
    check(`new look: the row's cards (${tag}) on focus: the last one widened (340 px or more), the others narrower, ${five ? 'the row slid: the last four filling it, the first past the right edge' : 'all still filling the row between the margins, not slid'}; back as they were without focus`,
      (five ? inRow(k.after.slice(1)) && offR(k.after[0]) && /translateX/.test(k.slid) : inRow(k.after) && !k.slid) && k.fw >= 330
      && k.back.every((b, i) => Math.abs(b[0] - k.boxes[i][0]) < .2 && Math.abs(b[1] - k.boxes[i][1]) < .2), JSON.stringify(k));
    await o.ctx.close();
  }

  // the cards' views in turn every CONFIG.rotateMs (#rotate=8: 8 s), staggered by the interval over the number of
  // turning cards (four: 2 s): the news card at 8, 16 s, the deen card at 10 s, the sports card at 12 s, the weather
  // at 14 s (today -> the week -> today); never two at once; each in its order, past a view with no data, the marker
  // and the body together; held while the detail window is open (on from the view last read there); no switch for
  // the card focused, nor with fewer than two views to show
  {
    const { p, ctx, errors } = await nxOpen(`${NXH}&panel=none&rotate=8`, { timers: true });
    const st = () => p.evaluate(() => { const on = document.querySelector('.nx-n .nx-ch .on');
      return { cur: Object.assign({}, NX_CUR), on: on && on.textContent, onShown: !!on && !on.hidden && on.getBoundingClientRect().width > 0,
               head: (document.querySelector('.nx-n .nx-nl b') || {}).textContent, wk: ((document.querySelector('[data-k="forecast"] .k') || {}).innerText || '').trim(),   // as shown (the week's view holds «الطقس» too, for its focus)
               dn: (document.querySelector('[data-k="deen"] .nx-ch .on') || {}).textContent, timers: NX_ROT ? 1 : 0 }; });
    const run = async ms => { await p.clock.runFor(ms); await p.waitForTimeout(50); };
    const diff = (x, y) => Object.keys(x.cur).filter(k => x.cur[k] !== y.cur[k]);
    await p.evaluate(() => { NEWS.world = []; nxDraw(); });   // the world has no news: the country's, tech
    const s = [await st()];
    for (const ms of [8200, 2000, 2000, 2000, 2000, 2000, 2000, 2000]) { await run(ms); s.push(await st()); }   // 8.2, 10.2, … 22.2 s
    const ch = s.slice(1).map((x, i) => diff(s[i], x).join());
    await p.evaluate(() => window.sauditvHold(true));
    await run(20000);
    const g = await st();
    await p.evaluate(() => { localStorage.setItem('sauditv-card-pos', JSON.stringify({ panel: 'tech', at: Date.now() })); window.sauditvHold(false); });
    await run(100);
    const h = await st();   // back: the tab last read
    await run(8200);   // the first switch one interval on (8 s) and its 150 ms fade
    const i = await st();   // then on from it
    await p.evaluate(() => { window.sauditvSetFocused(true); window.sauditvStep(1); });   // the news card focused: it stays, the others turn
    await run(16000);
    const j = await st();
    await p.evaluate(() => { window.sauditvStep(-1); NEWS.tech = []; nxDraw(); });   // one tab with news: no news switch
    await run(16000);
    const l = await st();
    const all = { s, ch, g, h, i, j, l };
    const [a] = s;
    check('new look: the turning cards switch one at a time, staggered by a quarter interval: the news at 8 s, the deen card at 10, the sports at 12, the weather at 14, the news again at 16',
      !errors.length && ch.join('|') === 'news|deen|sports|forecast|news|deen|sports|forecast' && a.timers === 1, JSON.stringify(all) + errors.join('; '));
    check('new look: the news card\'s tabs in their order, past a tab with no news, the marker and the headlines together',
      a.cur.news === 'news' && a.on === 'السعودية' && s[1].cur.news === 'tech' && s[1].on === 'التقنية' && s[1].head.startsWith('آبل')
      && s[5].cur.news === 'news' && s[5].head.startsWith('انطلاق') && [a, s[1], s[5]].every(x => x.onShown), JSON.stringify(all));
    check('new look: the deen card the verse -> the hadith, the sports card the table -> the matches, the weather today -> the week -> today',
      a.cur.deen === 'verse' && s[2].cur.deen === 'hadith' && s[2].dn === 'حديث اليوم' && a.cur.sports === 'standings' && s[3].cur.sports === 'matches'
      && a.cur.forecast === 'today' && a.wk === 'الطقس' && s[4].cur.forecast === 'week' && s[4].wk === 'الأسبوع' && s[8].cur.forecast === 'today', JSON.stringify(all));
    check('new look: the cards\' rotation held while the detail window is open (sauditvHold(true)), on from the tab last read after it',
      JSON.stringify(g.cur) === JSON.stringify(s[8].cur) && g.timers === 0 && h.cur.news === 'tech' && h.on === 'التقنية' && i.cur.news === 'news' && i.timers === 1, JSON.stringify(all));
    check('new look: no switch for the card focused (the news card), the others turning; none with only one tab to show',
      j.cur.news === i.cur.news && j.cur.deen !== i.cur.deen && l.cur.news === 'news', JSON.stringify(all));
    await ctx.close();
  }

  // each turning card's rotation indicator (nxLn): a 2px line along its bottom edge that fills over its interval in
  // steps of a second (the first turning card's first switch one interval after the row is armed, the second's one and
  // a half), started again at its switch, never at a redraw of the row; pinned where it is while the card is focused;
  // one dot per view, the current one gold. Real time here (the line is a CSS transition): #rotate=5, the news and
  // the deen card ticked
  {
    const { p, ctx, errors } = await nxOpen(`${NXH}&panel=news&panels=news,world,tech,verse,hadith&rotate=5`, { timers: true, width: 960, height: 540 });
    const st = () => p.evaluate(() => Object.fromEntries(['deen', 'news'].map(k => {
      const c = document.querySelector(`#nx-rail > [data-k="${k}"]`), f = c.querySelector('.nx-ln i'), d = c.querySelector('.nx-dots'), v = NX_VIEWS[k].filter(nxHas);
      const m = f && /^matrix\(([-\d.e]+)/.exec(getComputedStyle(f).transform), R = e => e.getBoundingClientRect();
      return [k, { el: !!f, x: m ? +(+m[1]).toFixed(3) : 0, dots: d ? d.children.length : 0, views: v.length, at: v.indexOf(nxView(k)),
        on: d ? [...d.children].findIndex(b => b.classList.contains('on')) : -1, onBg: d && getComputedStyle(d.querySelector('.on')).backgroundColor,
        ln: f && [Math.round(R(f.parentNode).bottom - R(c).bottom), Math.round(R(f.parentNode).height), Math.round(R(f.parentNode).width - R(c).width)],
        dotsOrder: d ? R(d.children[0]).left > R(d.children[d.children.length - 1]).left : null, view: nxView(k), pinned: !!NX_LN[k] && NX_LN[k].p != null }];
    })));
    await p.evaluate(() => { window.sauditvSetFocused(false); window.__nl = document.querySelector('[data-k="news"] .nx-ln i'); });   // armed now
    await p.waitForTimeout(2350);
    const a = await st();
    // a redraw of the row mid-cycle (a news load): new cards, the lines where they were
    const rd = await p.evaluate(() => { NEWS.news[0].headline = 'عنوان جديد للخبر'; nxDraw(); const f = document.querySelector('[data-k="news"] .nx-ln i');
      const ms = v => parseFloat(v) * (/ms$/.test(v) ? 1 : 1000);
      return { fresh: f !== window.__nl, delay: ms(f.style.transitionDelay), dur: ms(f.style.transitionDuration), fn: f.style.transitionTimingFunction }; });
    const b = await st();
    await p.waitForTimeout(3100);   // 5.45 s: the news card switched (its line again from empty), the deen card not yet
    const c = await st();
    await p.evaluate(() => { window.sauditvSetFocused(true); window.sauditvStep(1); });   // the deen card focused: its line pinned, the news card's on the new schedule
    await p.waitForTimeout(1600);
    await p.evaluate(() => { NEWS.news[1].headline = 'عنوان آخر'; nxDraw(); });   // a redraw keeps the pin
    const d = await st();
    const all = { a, rd, b, c, d };
    const near = (x, y) => Math.abs(x - y) <= .21;   // within a step of the news card's (1/5) and the deen card's (1/8)
    check('new look: both cards carry the rotation line and one dot per view (RTL: the first on the right), the current one gold; the line along the card\'s bottom edge, its width',
      !errors.length && ['deen', 'news'].every(k => a[k].el && a[k].dots === a[k].views && a[k].views > 1 && a[k].on === a[k].at && a[k].dotsOrder
        && a[k].onBg === 'rgb(235, 203, 139)' && a[k].ln.join() === '-1,2,-2'), JSON.stringify(all) + errors.join('; '));
    check('new look: the lines fill in steps from the arming: the news card\'s 5 steps over 5 s, the deen card\'s over its 7.5 s to its first switch',
      near(a.news.x, .4) && a.news.x > 0 && near(a.deen.x, .25) && a.deen.x > 0, JSON.stringify(all));
    check('new look: a redraw of the row mid-cycle does not start the lines again (new elements, the same fill, a negative delay into the same stepped transition)',
      rd.fresh && rd.delay < -2000 && rd.dur === 5000 && /steps\(5/.test(rd.fn) && b.news.x === a.news.x && b.deen.x === a.deen.x, JSON.stringify(all));
    check('new look: the news card\'s line starts again at its switch, its next dot gold; the deen card\'s runs on, half a phase behind',
      c.news.view !== a.news.view && c.news.on === c.news.at && c.news.on !== a.news.on && c.news.x === 0 && c.deen.view === a.deen.view && c.deen.x > a.deen.x,
      JSON.stringify(all));
    check('new look: the focused card\'s line pinned where it was (a redraw too), the other card\'s again from the new arming',
      d.deen.pinned && near(d.deen.x, c.deen.x) && d.deen.x > 0 && d.deen.view === c.deen.view && !d.news.pinned && near(d.news.x, .2), JSON.stringify(all));
    await ctx.close();
  }

  // every view of every card in its width at 960 x 540, at rest, focused (wide, on paper) and squeezed beside the
  // focused one: its content inside the card (a line clipped with «…» by design counts to its clip) and above the
  // rotation dots, no text under 11 px; the prayer card's five rows whole (nothing cut in it), a long headline on two
  // lines, nothing cut in a focused card, the week's days above the dots
  {
    const { p, ctx, errors } = await nxOpen(`${NXH}&panel=none`, { width: 960, height: 540 });
    await p.evaluate(() => { const now = Date.now(), ev = (id, h, a, hrs) => ({ idEvent: id, strHomeTeam: h, strAwayTeam: a, idHomeTeam: '136013',
        strTimestamp: new Date(now + hrs * 36e5).toISOString().slice(0, 19) });
      const L = [ev('m1', 'Al-Hilal', 'Al-Nassr', 2), ev('m2', 'Al-Ittihad', 'Al-Hilal', 4), ev('m3', 'Al-Hilal', 'Al-Ahli', 6)];   // three today, each with its channel
      NEXT_LIST = { '136013': L }; MATCHES = { '136013': L[0] };
      Object.keys(BC).forEach(k => delete BC[k]);
      L.forEach(x => { const t = toDate(x).getTime(), k = 'btolat|' + bcDay(t); BC[k] = { at: now, rows: [...(BC[k] ? BC[k].rows : []),
        [bcKey(teamAr(x.strHomeTeam)), bcKey(teamAr(x.strAwayTeam)), bcDay(t), ['beIN SPORTS 1'], 'فهد العتيبي', t]] }; });
      NEWS.news[0].headline = 'وزارة الداخلية تعلن عن إجراءات جديدة لتنظيم حركة المرور في المدن الكبرى خلال موسم الشتاء';
      NEWS.local = NEWS.news.slice(); renderMatches(); });
    const out = [];
    for (const state of ['rest', 'focused', 'squeezed']) {
      out.push(...await p.evaluate(state => { window.sauditvSetFocused(state !== 'rest'); const r = [];
        for (const k of NX_ORDER) for (const v of NX_VIEWS[k]) {
          // squeezed: its neighbour focused, the four in view (NX_S) holding both
          const i = NX_ORDER.indexOf(k), s0 = Math.max(0, Math.min(i, NX_ORDER.length - 4));
          NX_CUR[k] = v; NX_K = state === 'squeezed' ? NX_ORDER[i > s0 ? i - 1 : i + 1] : k; NX_S = s0; nxDraw();
          const e = document.querySelector(`#nx-rail > [data-k="${k}"]`), d = e.querySelector('.nx-dots'), R = x => x.getBoundingClientRect(), C = R(e);
          const on = x => !x.closest('svg') && R(x).height > 0 && getComputedStyle(x).display !== 'none';
          // a leaf's box cut to the boxes of the ancestors that clip it (an ellipsis line, a clamped paragraph)
          const box = x => { let b = R(x), l = b.left, rr = b.right, bt = b.bottom;
            for (let y = x.parentElement; y && y !== e; y = y.parentElement) if (getComputedStyle(y).overflow === 'hidden') { const a = R(y); l = Math.max(l, a.left); rr = Math.min(rr, a.right); bt = Math.min(bt, a.bottom); }
            return { l, r: rr, b: bt }; };
          const leaves = [...e.querySelectorAll('*')].filter(x => !x.closest('.nx-dots, .nx-ln') && !x.children.length && on(x)).map(box).filter(b => b.r > b.l);
          const bot = Math.max(...leaves.map(b => b.b));
          const small = [...e.querySelectorAll('*')].filter(x => on(x) && [...x.childNodes].some(n => n.nodeType === 3 && n.textContent.trim()) && parseFloat(getComputedStyle(x).fontSize) < 11)
            .map(x => x.tagName + ' ' + getComputedStyle(x).fontSize);
          const cut = [...e.querySelectorAll('*')].filter(x => on(x) && getComputedStyle(x).overflow === 'hidden' && x.scrollWidth > x.clientWidth + 1).map(x => x.className || x.tagName);
          const days = [...e.querySelectorAll('.nx-wk li')].filter(on), hl = e.querySelector('.nx-n .nx-nl b');
          r.push({ k, v, state, cls: e.className, w: Math.round(C.width), shown: nxView(k), cut, prows: [...e.querySelectorAll('.nx-pl li:not(.h)')].filter(on).length,
                   stories: [...e.querySelectorAll('.nx-n .nx-nl li')].filter(on).length, hlLines: hl ? Math.round(R(hl).height / parseFloat(getComputedStyle(hl).lineHeight)) : 0, gap: +((d ? R(d).top : C.bottom - 6) - bot).toFixed(1), dots: !!d, inCard: (!d || R(d).bottom <= C.bottom) && leaves.every(b => b.l >= C.left + 1 && b.r <= C.right - 1),
                   h: Math.round(C.height), small, days: days.length, daysFit: days.every(x => R(x).bottom <= (d ? R(d).top : C.bottom)),
                   rows: [...e.querySelectorAll('.nx-ml li')].filter(on).length, chs: [...e.querySelectorAll('.nx-ml li > span i')].filter(on).length, sh: e.scrollHeight, ch: e.clientHeight });
        } return r; }, state));
    }
    const S = st => out.filter(x => x.state === st);
    check('new look 960x540: every view of every card inside its card and above its rotation dots (no overlap), the 164 px card, at rest, focused and squeezed',
      !errors.length && out.length === 33 && out.every(x => x.shown === x.v && x.gap >= 1 && x.inCard && x.h === 164 && x.sh <= x.ch + 1)
      && out.every(x => x.dots === (x.k !== 'prayers')) && S('focused').every(x => x.cls.includes(' f') && x.w >= 330) && S('squeezed').every(x => x.cls.includes('sq') && x.w >= 89.5),
      JSON.stringify(out) + errors.join('; '));
    check('new look 960x540: no text under 11 px in the row', out.every(x => !x.small.length), JSON.stringify(out.filter(x => x.small.length)));
    check('new look 960x540: the prayer card: its five rows whole at rest and focused (with the iqama), nothing cut in it in any state',
      out.filter(x => x.v === 'prayers').every(x => !x.cut.length && x.prows === (x.state === 'squeezed' ? 0 : 5)), JSON.stringify(out.filter(x => x.v === 'prayers')));
    check('new look 960x540: the news card at rest: two stories (with three), a long headline on two lines; nothing cut in a focused card',
      S('rest').filter(x => x.v === 'news' || x.v === 'local').every(x => x.stories >= 2 && x.hlLines === 2) && S('focused').every(x => !x.cut.length), JSON.stringify(out.filter(x => x.k === 'news' || (x.state === 'focused' && x.cut.length))));
    check('new look 960x540: the matches\' three rows with their channel when focused; the week\'s days above the dots (five or more at rest; focused, on either view, the six from «غداً» beside today\'s column; none at rest on today)',
      S('focused').some(x => x.v === 'matches' && x.rows === 3 && x.chs === 3) && out.filter(x => x.k === 'forecast').every(x => x.daysFit)
      && S('rest').find(x => x.v === 'week').days >= 5 && S('rest').find(x => x.v === 'today').days === 0
      && S('focused').filter(x => x.k === 'forecast').every(x => x.days === 6), JSON.stringify(out.filter(x => ['matches', 'week', 'today'].includes(x.v))));
    await ctx.close();
  }

  // the sports card's «المباريات» (after «الترتيب»): the followed clubs' games as the classic matches card's (a game in
  // progress first, then the next, then the results of the last day), up to three rows of two lines, the teams (the
  // score once on), then when and the channel when known; skipped without games; OK opens the matches
  {
    const { p, ctx, errors } = await nxOpen(`${NXH}&panel=standings&panels=standings,matches`, { width: 960, height: 540 });
    const r = await p.evaluate(() => {
      const now = Date.now(), ev = (id, h, a, hrs, x = {}) => Object.assign({ idEvent: id, strHomeTeam: h, strAwayTeam: a, idHomeTeam: '136013',
        strTimestamp: new Date(now + hrs * 36e5).toISOString().slice(0, 19) }, x);
      const out = { list0: window.sauditvDetailList() };
      const live = ev('l1', 'Al-Hilal', 'Al-Nassr', -.6, { intHomeScore: '2', intAwayScore: '1', strProgress: '33', strStatus: '1H' });
      const next = ev('n1', 'Al-Ittihad', 'Al-Hilal', 2), done = ev('d1', 'Al-Hilal', 'Al-Ahli', -20, { intHomeScore: '0', intAwayScore: '3', strStatus: 'FT' });
      const t = toDate(next).getTime();
      BC['btolat|' + bcDay(t)] = { at: now, rows: [[bcKey(teamAr('Al-Ittihad')), bcKey(teamAr('Al-Hilal')), bcDay(t), ['SSC 1'], '', t]] };
      MATCHES = { '136013': live }; NEXT_LIST = { '136013': [next, live] }; RESULTS = { '136013': [done] };
      window.sauditvSetFocused(true); NX_CUR.sports = 'matches'; nxDraw(); nxFocus();
      const c = document.querySelector('[data-k="sports"]'), R = x => x.getBoundingClientRect(), dots = c.querySelector('.nx-dots');
      out.panel = window.sauditvCurrentPanel(); out.list = window.sauditvDetailList();
      out.chips = [...c.querySelectorAll('.nx-ch span')].filter(x => !x.hidden).map(x => x.textContent + (x.className ? '*' : '')).join();
      out.rows = [...c.querySelectorAll('.nx-ml li')].map(li => ({ teams: li.querySelector('b').textContent, when: li.querySelector('.w').textContent,
        live: !!li.querySelector('.w .live'), ch: [...li.querySelectorAll(':scope > span i')].map(s => s.textContent),
        fits: R(li).bottom <= R(dots).top && R(li).left >= R(c).left && R(li).right <= R(c).right }));
      out.dots = dots.children.length; out.on = [...dots.children].findIndex(b => b.classList.contains('on'));
      MATCHES = {}; NEXT_LIST = {}; RESULTS = {}; Object.keys(KEPT).forEach(k => delete KEPT[k]); nxDraw();
      out.after = { has: nxHas('matches'), view: NX_CUR.sports, list: window.sauditvDetailList(), chips: [...document.querySelectorAll('[data-k="sports"] .nx-ch span')].map(x => x.textContent).join(),
                    dots: document.querySelectorAll('[data-k="sports"] .nx-dots b').length };
      return out;
    });
    const [l, n, d] = r.rows || [];
    check('new look: the sports card\'s «المباريات» after «الترتيب», in the detail list after the table; OK on it opens the matches',
      !errors.length && r.list0 === 'standings,matches' && r.panel === 'matches' && r.list === 'standings,matches'
      && r.chips.endsWith('المباريات*') && r.dots === 2 && r.on === 1, JSON.stringify(r) + errors.join('; '));
    check('new look: «المباريات» rows: the game in progress first (its score, «مباشر» and the minute), the next today («اليوم» and its time), the result («انتهت»)',
      r.rows.length === 3 && /الهلال 2 - 1 النصر/.test(l.teams) && l.live && /مباشر الدقيقة 33/.test(l.when) && /الاتحاد × الهلال/.test(n.teams)
      && /^اليوم \d/.test(n.when) && /الهلال 0 - 3 الأهلي/.test(d.teams) && d.when === 'انتهت', JSON.stringify(r));
    check('new look: «المباريات» rows inside the card above its dots; the channel after the time of the game that has one, none on the others',
      r.rows.every(x => x.fits) && n.ch.length === 1 && n.ch[0].includes('SSC 1') && !l.ch.length && !d.ch.length, JSON.stringify(r));
    check('new look: no games: no «المباريات» chip (nor dots: the table alone), the sports card on the table, the detail list without it',
      !r.after.has && r.after.view === 'standings' && r.after.list === 'standings' && r.after.chips === 'الترتيب' && r.after.dots === 0, JSON.stringify(r.after));
    await ctx.close();
  }

  // the new look's screensaver: the card's title (#week-hd) shown on every panel, the verse and the hadith too
  {
    const ALL = ['prayers', 'forecast', 'matches', 'verse', 'hadith', 'standings', 'news', 'local', 'world', 'tech'];
    const { p, ctx, errors } = await nxOpen(`city=الرياض&lat=24.7136&lon=46.6753&tz=Asia%2FRiyadh&teams=136013&look=new&panel=prayers&panels=${ALL.join(',')}&rotate=5`,
      { width: 960, height: 540 });
    const t = await p.evaluate(ALL => ALL.map(P => { CONFIG.panel = P; CARD_BUSY_UNTIL = 0; renderPanel(false, true);
      const h = document.getElementById('week-hd'), b = h.getBoundingClientRect(), cs = getComputedStyle(h);
      return { P, text: h.textContent.trim(), shown: cs.display !== 'none' && cs.visibility !== 'hidden' && +cs.opacity > 0 && b.width > 0 && b.height > 0 && b.top >= 0 }; }), ALL);
    check('new look screensaver: every panel\'s card keeps its title (#week-hd), visible and not empty', !errors.length && t.length === 10
      && t.every(x => x.text && x.shown), JSON.stringify(t) + errors.join('; '));
    await ctx.close();
  }

  // a switch of look: the launcher gives the page a new address (the settings fragment's look=new or not) and reloads it
  {
    const { p, ctx, errors } = await open('tv.html', 'city=الرياض&home=1&panel=prayers', { at: AT });
    const look = () => ({ look: CONFIG.look, nx: !!document.getElementById('nx'), html: document.documentElement.className,
      week: getComputedStyle(document.querySelector('.week')).display, tape: getComputedStyle(document.getElementById('tape')).left });
    const a = await p.evaluate(look);
    await p.evaluate(() => { location.hash = location.hash + '&look=new'; });
    await p.reload();
    await p.waitForFunction(() => document.documentElement.classList.contains('ready'), null, { timeout: 5000 }).catch(() => errors.push('never revealed'));
    const b = await p.evaluate(look);
    await p.evaluate(() => { location.hash = location.hash.replace('&look=new', ''); });
    await p.reload();
    await p.waitForFunction(() => document.documentElement.classList.contains('ready'), null, { timeout: 5000 }).catch(() => errors.push('never revealed'));
    const c = await p.evaluate(look);
    check('switching looks: «الكلاسيكي» (no #look) as before, «الجديد» after the reload with look=new, then «الكلاسيكي» again',
      !errors.length && a.look === 'classic' && !a.nx && a.week !== 'none' && !/\bnx/.test(a.html)
      && b.look === 'new' && b.nx && /\bnxh\b/.test(b.html) && c.look === 'classic' && !c.nx && c.week !== 'none' && c.tape === a.tape,
      JSON.stringify({ a, b, c }) + errors.join('; '));
    const d = await open('tv.html', 'city=الرياض&look=bogus', { at: AT });
    check('switching looks: an unknown #look is «الكلاسيكي»', await d.p.evaluate(() => CONFIG.look === 'classic' && !document.getElementById('nx')), '');
    await d.ctx.close();
    await ctx.close();
  }

  // «يُتلى الآن · <reciter>» on the home screen in «الجديد» (#radiopush=1: told by sauditvRadioChanged): nxBoot moves #qr
  // into the standfirst's block in its meta line's place (the meta hidden while it plays, back when it stops); gold, 14 px,
  // the name in a <bdi> and whole (no «…»: a reciter, the Saudi radio), within the block; the block, the «اليوم» row where
  // they were, clear of the dateline even with a two-line standfirst; its box (as seen, #nx scaled) to the launcher
  // (qrRect), 0 x 0 once off. SHOTS_QR=1: optimize/run/shots/nx-home-quran.png (960 x 540)
  for (const [w, h] of [[960, 540], [1920, 1080]]) {
    const { dockh, dockb } = room(w, h);
    const { p, ctx, errors } = await nxOpen(`${NXH}&panel=prayers&radiopush=1&dockh=${dockh}&dockb=${dockb}`, { width: w, height: h,
      init: "window.__radio = ''; window.SaudiTVRadio = {state: () => window.__radio}; window.__rects = [];"
        + "window.SaudiTVNative = {cardRect: () => {}, qrRect: (...a) => window.__rects.push(a)};" });
    await p.waitForTimeout(700);
    const box = () => {
      const R = e => { const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom, w: b.width, h: b.height }; };
      const el = document.getElementById('qr'), cs = getComputedStyle(el), q = s => document.querySelector(s);
      return { hidden: el.hidden || cs.display === 'none', text: el.textContent, bdi: (el.querySelector('bdi') || {}).textContent,
               inDeck: !!el.closest('.nx-dk'), meta: R(q('#nx-mt')), metaShown: getComputedStyle(q('#nx-mt')).display !== 'none' && !!q('#nx-mt').textContent,
               whole: q('#qr-t').scrollWidth <= q('#qr-t').clientWidth, afterMeta: q('#nx-mt').nextElementSibling === el, color: cs.color,
               size: parseFloat(cs.fontSize) * Math.min(innerWidth / 960, innerHeight / 540), box: R(el), deck: R(q('.nx-dk')),
               dl: R(q('#nx-dl')), today: R(q('.nx-rh')), rail: R(q('#nx-rail')), rect: window.__rects[window.__rects.length - 1] };
    };
    const off = await p.evaluate(box), tag = `new look home ${w}x${h}: «يُتلى الآن · ماهر المعيقلي»`;
    await p.evaluate(() => { window.__radio = 'playing|ماهر المعيقلي'; window.sauditvRadioChanged(); });
    await p.waitForTimeout(300);
    const on = await p.evaluate(box);
    const k = Math.min(w / 960, h / 540);
    check(`${tag} in the meta line's place (the meta hidden), whole, gold, 14 px, within its block; the block and the «اليوم» row unmoved, clear of the dateline`,
      !errors.length && off.hidden && !on.hidden && on.text === 'يُتلى الآن · ماهر المعيقلي' && on.bdi === 'ماهر المعيقلي' && on.inDeck && on.afterMeta
      && on.color === 'rgb(235, 203, 139)' && Math.abs(on.size - 14 * k) < .5 && on.box.l >= on.deck.l - 1 && on.box.r <= on.deck.r + 1
      && on.today.t === off.today.t && on.rail.t === off.rail.t && on.deck.t >= on.dl.b && on.box.b <= on.today.t
      && Math.abs(on.deck.t - off.deck.t) < .5 && off.metaShown && !on.metaShown && on.whole
      && Math.abs((on.box.t + on.box.b) - (off.meta.t + off.meta.b)) < 2 * k,
      JSON.stringify({ off, on }) + errors.join('; '));
    check(`${tag}: its box as seen sent to the launcher (qrRect)`, !!on.rect && on.rect[4] === w
      && [on.box.l, on.box.t, on.box.w, on.box.h].every((v, i) => Math.abs(v - on.rect[i]) <= 1), JSON.stringify(on));
    if (w === 960 && process.env.SHOTS_QR) await p.screenshot({ path: path.join(ROOT, 'optimize/run/shots/nx-home-quran.png') });
    const two = await p.evaluate(() => { document.getElementById('nx-st').textContent = 'وزارة الموارد البشرية تعلن عن حزمة جديدة من البرامج لدعم توظيف الشباب في القطاع الخاص في جميع المناطق'; });
    const t2 = await p.evaluate(box);
    check(`${tag}: a two-line standfirst still clear of the dateline`, t2.deck.t >= t2.dl.b && t2.today.t === off.today.t, JSON.stringify(t2) + two);
    await p.evaluate(() => { window.__radio = 'playing|إذاعة القرآن الكريم من السعودية'; window.sauditvRadioChanged(); });
    await p.waitForTimeout(300);
    const sa = await p.evaluate(box);
    check(`new look home ${w}x${h}: «يُتلى الآن · إذاعة القرآن الكريم من السعودية» whole (no «…»), in the meta's place`,
      !sa.hidden && sa.text === 'يُتلى الآن · إذاعة القرآن الكريم من السعودية' && sa.whole && !sa.metaShown && sa.box.l >= sa.deck.l - 1, JSON.stringify(sa));
    for (const st of ['', 'paused|ماهر المعيقلي']) {
      await p.evaluate(s => { window.__radio = s; window.sauditvRadioChanged(); }, st);
      await p.waitForTimeout(300);
      const r = await p.evaluate(box);
      check(`${tag}: hidden for «${st || 'off'}», the meta back, a 0 x 0 box to the launcher`, r.hidden && r.metaShown && r.rect && r.rect.slice(0, 4).join() === '0,0,0,0', JSON.stringify(r));
    }
    await ctx.close();
  }

  // the screensaver in «الجديد»: the card top right, the photo's place top left, the dates and the clock bottom right,
  // the four facts bottom left (each only with its data), no ticker tape; the hourly swap mirrors the corners
  const saverCredit = {};   // its photo credit's size and colour, for the home screen's (below)
  for (const [w, h] of [[1920, 1080], [1280, 720], [960, 540]]) {
    const online = { v: 1, saudi: [{ u: 'https://thumb.wikimedia.org/x/alula.jpg', page: '', a: 'Heritage Commission', l: 'CC BY-SA 4.0', p: 'العُلا', b: 0.5 }], world: [], bing: [] };
    const { p, ctx, errors } = await nxOpen('city=الرياض&lat=24.7136&lon=46.6753&tz=Asia%2FRiyadh&teams=136013&panel=verse&look=new&photos=saudi', {
      width: w, height: h, init: `try{ localStorage.setItem('photoOnline', ${JSON.stringify(JSON.stringify(online))}); }catch(e){}`
        + "window.SaudiTVNative = {vpn: () => true, vpnApp: () => true, vpnState: () => 1, vpnCountry: () => ''};",
      route: (u, r) => /thumb\.wikimedia\.org/.test(u) ? r.fulfill({ path: path.join(ROOT, 'photos/-RlktmDy3Ec.jpg') }) : /online\.json$/.test(u) ? r.abort() : null });
    await p.evaluate(() => window.sauditvSaver.swap(false));
    await p.waitForTimeout(1800);
    const saver = () => {
      const R = e => { const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom }; };
      const q = s => document.querySelector(s);
      return { W: innerWidth, H: innerHeight, card: R(q('#nx .week')), place: q('#nx-pl').textContent, placeBox: R(q('#nx-pl')),
               cover: R(q('.nx-cv')), facts: [...document.querySelectorAll('.nx-fx li')].map(li => li.textContent), factsBox: R(q('.nx-fx')),
               dates: q('.nx-dt').textContent, clock: q('#nx-ck').textContent, quote: !!q('#nx .week .quote'),
               tape: getComputedStyle(q('#tape')).display, classic: [...document.querySelectorAll('.stage > :not(#nx)')].filter(e => getComputedStyle(e).display !== 'none').length };
    };
    const r = await p.evaluate(saver), tag = `new look screensaver ${w}x${h}`;
    check(`${tag}: no errors; the verse top right, the place top left, the dates and the clock bottom right, the facts bottom left; no tape`,
      !errors.length && r.quote && r.card.r > r.W * .8 && r.card.t < r.H * .2 && r.place === 'العُلا' && r.placeBox.l < r.W * .1
      && r.cover.r > r.W * .9 && r.cover.b > r.H * .85 && r.factsBox.l < r.W * .1 && r.factsBox.b > r.H * .85 && r.tape === 'none'
      && r.classic === 0 && r.dates.startsWith('السبت 13 جمادى الأولى') && /^3:24م$/.test(r.clock),
      JSON.stringify(r) + errors.join('; '));
    check(`${tag}: four facts: the next prayer, the match, the weather, Ramadan; nothing overlapping`,
      r.facts.length === 4 && r.facts[0].startsWith('المغرب') && r.facts[1].includes('×') && r.facts[2].includes('31°') && r.facts[3].startsWith('رمضان')
      && apart(r.factsBox, r.cover) && apart(r.card, r.placeBox) && r.card.b < r.cover.t, JSON.stringify(r));
    await p.evaluate(() => { MATCHES = {}; NEXT_LIST = {}; NX_WX = null; nxDraw(); });
    const f = await p.evaluate(() => [...document.querySelectorAll('.nx-fx li')].map(li => li.textContent));
    check(`${tag}: a fact without data is left out`, f.length === 2 && f[0].startsWith('المغرب') && f[1].startsWith('رمضان'), JSON.stringify(f));
    // the VPN badge: the concept's shield and its check (an inline SVG in the badge's green), about the cap height of «VPN»
    const v = await p.evaluate(() => {
      const el = document.getElementById('vpn'), svg = el.querySelector('svg'), cs = getComputedStyle(el);
      const shown = [...el.querySelectorAll('svg path')].filter(x => getComputedStyle(x).display !== 'none').map(x => x.getAttribute('class') || 'shield');
      return { hidden: el.hidden, cls: el.className, shown, glyph: getComputedStyle(el.querySelector('b')).display, stroke: svg && getComputedStyle(svg).stroke,
               h: svg && svg.getBoundingClientRect().height, font: parseFloat(cs.fontSize) * Math.min(innerWidth / 960, innerHeight / 540), text: el.innerText };
    });
    check(`${tag}: the VPN badge is the shield with its check in green (no «✓» glyph), about the text's cap height`,
      !v.hidden && v.cls === 'vpn on' && v.shown.join() === 'shield,ok' && v.glyph === 'none' && v.stroke === 'rgb(166, 235, 185)'
      && v.h >= v.font * .7 && v.h <= v.font * 1.05 && !v.text.includes('✓'), JSON.stringify(v));
    saverCredit[w] = await p.evaluate(() => { const cs = getComputedStyle(document.getElementById('credit')); return [cs.fontSize, cs.color]; });
    await p.evaluate(() => window.sauditvSaver.swap(true));
    await p.waitForTimeout(1800);
    const s = await p.evaluate(saver);
    check(`${tag}: the hourly swap mirrors the corners (the clock left, the facts right, the card left, the place right)`,
      s.cover.l < s.W * .1 && s.factsBox.r > s.W * .9 && s.card.l < s.W * .2 && s.placeBox.r > s.W * .9, JSON.stringify(s));
    const shift = await p.evaluate(() => { pixelShift(); return document.querySelector('.stage').style.transform; });
    check(`${tag}: pixelShift still moves it all (the stage)`, /^translate\(-?\d+px, ?-?\d+px\)$/.test(shift), shift);
    await ctx.close();
  }

  // «يُتلى الآن · <reciter>» on the screensaver in «الجديد»: nxBoot moves #qr under the clock (inside .nx-cv); a quiet
  // gold line in the dates' small text size, the name in a <bdi>; hidden while off or paused; at 960 x 540 clear of the
  // facts and of the tallest cards, in both corners (the hourly swap). SHOTS=1: optimize/run/shots/nx-saver-quran.png
  {
    const radio = st => `window.SaudiTVRadio = {state: () => window.__radio}; window.__radio = ${JSON.stringify(st)};`;
    const line = () => {
      const R = e => { const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom, w: b.width }; };
      const el = document.getElementById('qr'), cs = getComputedStyle(el), ck = document.getElementById('nx-ck');
      return { hidden: el.hidden || cs.display === 'none', text: el.textContent, bdi: (el.querySelector('bdi') || {}).textContent,
               inCover: !!el.closest('.nx-cv'), afterClock: ck.nextElementSibling === el, color: cs.color, size: cs.fontSize,
               dtSize: getComputedStyle(document.querySelector('.nx-dt span')).fontSize, box: R(el), cover: R(document.querySelector('.nx-cv')),
               card: R(document.querySelector('#nx .week')), facts: R(document.querySelector('.nx-fx')), W: innerWidth, H: innerHeight };
    };
    for (const panel of ['verse', 'prayers', 'hadith', 'news', 'standings']) {
      const { p, ctx, errors } = await nxOpen(`city=الرياض&lat=24.7136&lon=46.6753&tz=Asia%2FRiyadh&teams=136013&look=new&panel=${panel}`,
        { width: 960, height: 540, init: radio('playing|ماهر المعيقلي') });
      if (panel === 'hadith') await p.waitForFunction(() => !!HADITH_DAY, null, { timeout: 40000 }).catch(() => {});
      for (const swap of [false, true]) {
        await p.evaluate(s => { window.sauditvSaver.swap(s); renderPanel(false); }, swap);
        await p.waitForTimeout(1600);
        const r = await p.evaluate(line), tag = `new look screensaver 960x540, card ${panel}${swap ? ', swapped' : ''}: «يُتلى الآن · ماهر المعيقلي»`;
        check(`${tag} under the clock, gold, the dates' small size, clear of the facts and the card`,
          !errors.length && !r.hidden && r.text === 'يُتلى الآن · ماهر المعيقلي' && r.bdi === 'ماهر المعيقلي' && r.inCover && r.afterClock
          && r.color === 'rgb(235, 203, 139)' && r.size === r.dtSize && r.box.w > 0 && r.box.l >= 0 && r.box.r <= r.W && r.box.b <= r.H
          && apart(r.cover, r.facts) && apart(r.cover, r.card) && apart(r.box, r.facts), JSON.stringify(r) + errors.join('; '));
      }
      if (panel === 'verse') {
        await p.evaluate(() => window.sauditvSaver.swap(false));
        await p.waitForTimeout(1600);
        if (process.env.SHOTS) await p.screenshot({ path: path.join(ROOT, 'optimize/run/shots/nx-saver-quran.png') });
        for (const st of ['', 'paused|ماهر المعيقلي', 'loading|ماهر المعيقلي']) {
          await p.evaluate(s => { window.__radio = s; tickRadio(); }, st);
          const r = await p.evaluate(line);
          check(`new look screensaver: «يُتلى الآن» hidden for «${st || 'off'}»`, r.hidden, JSON.stringify(r));
        }
        await p.evaluate(() => { window.__radio = 'playing|<b>x</b>'; tickRadio(); });
        check('new look screensaver: the station\'s name is text, never markup',
          await p.evaluate(() => !document.querySelector('#qr bdi b') && document.getElementById('qr').textContent === 'يُتلى الآن · <b>x</b>'));
      }
      await ctx.close();
    }
    // the classic screensaver: the reciter's name in its chip; hidden while off or paused
    const { p, ctx, errors } = await open('tv.html', 'city=الرياض&lat=24.7136&lon=46.6753&tz=Asia%2FRiyadh', { init: radio('playing|ماهر المعيقلي') });
    await p.waitForTimeout(700);
    const shown = () => { const el = document.getElementById('qr'); return { hidden: el.hidden || getComputedStyle(el).display === 'none', text: el.textContent }; };
    let c = await p.evaluate(shown);
    check('classic screensaver: «يُتلى الآن: ماهر المعيقلي»', !errors.length && !c.hidden && c.text === 'يُتلى الآن: ماهر المعيقلي', JSON.stringify(c) + errors.join('; '));
    for (const st of ['', 'paused|ماهر المعيقلي']) {
      await p.evaluate(s => { window.__radio = s; tickRadio(); }, st);
      c = await p.evaluate(shown);
      check(`classic screensaver: «يُتلى الآن» hidden for «${st || 'off'}»`, c.hidden, JSON.stringify(c));
    }
    await ctx.close();
  }

  // the screensaver's rotating card in «الجديد»: its quiet dots (one per card picked, 6 px, 6 apart, the current one gold,
  // from the card's right under its content, on its plate within its 420), no progress line, none for a single card; the
  // facts picked by #facts= (in that order, at most five, unknown ids left out; hijri: the Hijri date, the Gregorian
  // under it). SHOTS=1: optimize/run/shots/nx-saver-dots.png (960 x 540)
  {
    const SAVER = 'city=الرياض&lat=24.7136&lon=46.6753&tz=Asia%2FRiyadh&teams=136013&look=new';
    const { p, ctx, errors } = await nxOpen(`${SAVER}&panel=prayers&panels=prayers,verse,forecast&rotate=15&facts=hijri,weather,prayer,match,ramadan`,
      { width: 960, height: 540 });
    await p.evaluate(() => window.sauditvSaver.swap(false));
    await p.waitForTimeout(1800);
    const dots = () => {
      const R = e => { const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom, w: b.width, h: b.height }; };
      const card = document.querySelector('#nx .week'), rot = document.getElementById('rot'), ds = [...document.querySelectorAll('#rot-dots b')];
      const text = [...card.querySelectorAll('#week-list *')].filter(e => !e.children.length && e.textContent.trim() && getComputedStyle(e).display !== 'none').map(R);
      return { shown: !rot.hidden && getComputedStyle(rot).display !== 'none', line: getComputedStyle(document.querySelector('#rot .rot-ln')).display,
               card: R(card), n: ds.length, on: ds.findIndex(b => b.classList.contains('on')), boxes: ds.map(R),
               colors: ds.map(b => getComputedStyle(b).backgroundColor), textBottom: Math.max(...text.map(x => x.b)),
               max: 420 * Math.min(innerWidth / 960, innerHeight / 540), panel: CONFIG.panel, list: CONFIG.panels.join(),
               facts: [...document.querySelectorAll('.nx-fx li')].map(li => [li.querySelector('b').textContent, li.querySelector('span').textContent]) };
    };
    const d = await p.evaluate(dots);
    if (process.env.SHOTS) await p.screenshot({ path: path.join(ROOT, 'optimize/run/shots/nx-saver-dots.png') });
    const gap = d.boxes.length > 1 ? d.boxes[0].l - d.boxes[1].r : 0;
    check('new look screensaver: one quiet dot per card picked, the current one gold, 6 px and 6 apart, no progress line',
      !errors.length && d.shown && d.line === 'none' && d.n === 3 && d.on === d.list.split(',').indexOf(d.panel)
      && d.boxes.every(b => Math.abs(b.w - 6) < .5 && Math.abs(b.h - 6) < .5) && Math.abs(gap - 6) < .5
      && d.colors.filter((c, i) => i !== d.on).every(c => c === 'rgba(255, 255, 255, 0.35)') && d.colors[d.on] === 'rgb(235, 203, 139)',
      JSON.stringify(d) + errors.join('; '));
    check('new look screensaver: the dots from the card\'s right, under its content, within its plate (420) and clear of its text',
      Math.abs(d.boxes[0].r - d.card.r) < 1 && d.boxes.every(b => b.t >= d.textBottom - .5 && b.b <= d.card.b + .5 && b.l >= d.card.l - .5)
      && d.card.w <= d.max + 1, JSON.stringify(d));
    check('new look screensaver: #facts= picks the facts in its order, all five (hijri: «13 جمادى الأولى 1448», the Gregorian under it)',
      d.facts.length === 5 && d.facts[0][0] === '13 جمادى الأولى 1448' && /2026/.test(d.facts[0][1]) && d.facts[1][0].includes('31°')
      && d.facts[2][0].startsWith('المغرب') && d.facts[3][0].includes('×') && d.facts[4][0].startsWith('رمضان'), JSON.stringify(d.facts));
    // the five lines within the 960 x 540 frame, clear of the photo's place and of the card, in both corners (the hourly swap)
    for (const swap of [false, true]) {
      await p.evaluate(s => window.sauditvSaver.swap(s), swap);
      await p.waitForTimeout(300);
      const f = await p.evaluate(() => { const R = e => { const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom }; };
        return { n: document.querySelectorAll('.nx-fx li').length, fx: R(document.querySelector('.nx-fx')), pl: R(document.getElementById('nx-pl')),
                 card: R(document.querySelector('#nx .week')), W: innerWidth, H: innerHeight }; });
      check(`new look screensaver${swap ? ' (swapped)' : ''}: five facts inside the 960x540 frame, clear of the photo's place and the card`,
        f.n === 5 && f.fx.l >= 0 && f.fx.t >= 0 && f.fx.r <= f.W && f.fx.b <= f.H && apart(f.fx, f.pl) && apart(f.fx, f.card), JSON.stringify(f));
    }
    await ctx.close();
    for (const [facts, want, tag] of [['ramadan,bogus,prayer,ramadan', ['رمضان', 'المغرب'], 'unknown and repeated ids left out'],
                                      ['', ['المغرب', '', '', 'رمضان'], 'none given: the next prayer, the match, the weather, Ramadan']]) {
      const o = await nxOpen(`${SAVER}&panel=verse` + (facts ? '&facts=' + facts : ''));
      const r = await o.p.evaluate(() => ({ facts: [...document.querySelectorAll('.nx-fx li b')].map(b => b.textContent),
        rot: document.getElementById('rot').hidden }));
      check(`new look screensaver: the facts (${tag}); a single card has no dots`, !o.errors.length && r.rot && r.facts.length === want.length
        && want.every((w, i) => r.facts[i].startsWith(w)), JSON.stringify(r) + o.errors.join('; '));
      await o.ctx.close();
    }
  }

  // the home screen's photo credit in «الجديد»: on the «الأسواق» line at its left, the ticker after it; clear of the
  // launcher's pills (the top 110 px at 1080p), its apps row and the ticker's text; the screensaver's size and opacity;
  // shortened, never cut: without the licence's version, then without the author, the place always whole
  const credit = async (w, h, photo) => {
    const { dockh, dockb } = room(w, h);
    const online = { v: 1, saudi: [Object.assign({ u: 'https://thumb.wikimedia.org/x/alula.jpg', page: '', a: 'Heritage Commission', l: 'CC BY-SA 4.0', p: 'العُلا', b: 0.5 }, photo)], world: [], bing: [] };
    const { p, ctx, errors } = await nxOpen(`${NXH}&panel=prayers&photos=saudi&dockh=${dockh}&dockb=${dockb}`, {
      width: w, height: h, init: `try{ localStorage.setItem('photoOnline', ${JSON.stringify(JSON.stringify(online))}); }catch(e){}`,
      route: (u, r) => /thumb\.wikimedia\.org/.test(u) ? r.fulfill({ path: path.join(ROOT, 'photos/01u4zLK54tE.jpg') }) : /online\.json$/.test(u) ? r.abort() : null });
    await p.waitForFunction(() => document.getElementById('credit').classList.contains('on'), null, { timeout: 4000 }).catch(() => errors.push('no credit'));
    await p.waitForTimeout(600);   // the tape laid out again after it
    const r = await p.evaluate(() => {
      const R = e => { const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom }; };
      const cr = document.getElementById('credit'), cs = getComputedStyle(cr), tape = document.getElementById('tape');
      const items = [...document.querySelectorAll('.tape-i')].map(R).filter(x => x.r > tape.getBoundingClientRect().left + 1);
      return { text: cr.textContent, box: R(cr), cut: cr.scrollWidth > cr.clientWidth + 1, size: cs.fontSize, color: cs.color,
               tape: R(tape), items: items.length, mk: R(document.getElementById('nx-mk')) };
    });
    await ctx.close();
    return { r, errors, dockh, dockb };
  };
  for (const [w, h] of [[1920, 1080], [1280, 720], [960, 540]]) {
    const { r, errors, dockh, dockb } = await credit(w, h, {}), tag = `new look home ${w}x${h}`;
    check(`${tag}: the photo's credit whole, bottom left on the «الأسواق» line, the ticker after it`,
      !errors.length && r.text === 'العُلا · تصوير Heritage Commission · CC BY-SA 4.0' && !r.cut && r.box.l >= 0 && r.box.b <= h
      && r.box.t >= r.mk.t - 1 && r.tape.l >= r.box.r && r.items > 0, JSON.stringify(r) + errors.join('; '));
    check(`${tag}: the credit clear of the pills (the top ${Math.round(110 * h / 1080)} px), the apps row and the ticker's text`,
      r.box.t >= 110 * h / 1080 && r.box.t >= h - dockb - 1 && apart(r.box, r.tape), JSON.stringify({ box: r.box, tape: r.tape, dockb, dockh }));
    check(`${tag}: the credit in the screensaver's size and opacity`, saverCredit[w] && r.size === saverCredit[w][0] && r.color === saverCredit[w][1],
      JSON.stringify([r.size, r.color, saverCredit[w]]));
  }
  {
    const long = 'Studio of the Photographers of the Kingdom of Saudi Arabia and Their Many Friends, Assistants and Archivists';
    let { r, errors } = await credit(1920, 1080, { a: long });
    check('new look home: a credit too long for the line: without the licence\'s version, then without the author («العُلا · CC BY-SA»)',
      !errors.length && r.text === 'العُلا · CC BY-SA' && !r.cut && r.tape.l >= r.box.r, JSON.stringify(r) + errors.join('; '));
    const place = 'محمية الإمام عبدالعزيز بن محمد الملكية ووادي حنيفة والدرعية التاريخية وروضة خريم ومتنزه الثمامة البري';
    ({ r, errors } = await credit(960, 540, { p: place, a: long }));
    check('new look home: the place is never cut, whatever its length', !errors.length && r.text.startsWith(place) && !r.cut, JSON.stringify(r) + errors.join('; '));
  }

  // the screensaver's card top right, each kind it rotates through (prayers, the verse, the hadith, the weather, the
  // matches, the news), at the three sizes: no wider than its 420, its text whole inside it, its plate behind it (never
  // over it), clear of the place, the dates and clock, and the facts
  {
    const more = {
      forecast: () => { FORECAST = [0, 1, 2, 3, 4].map(i => ({ date: new Date(Date.now() + i * 864e5).toISOString().slice(0, 10), code: i ? 2 : 96, hi: 37 - i, lo: 24 - i })); },
      matches: () => {
        const ev = (id, a, b, hrs) => ({ idEvent: id, strHomeTeam: a, strAwayTeam: b, strTimestamp: new Date(Date.now() + hrs * 36e5).toISOString().slice(0, 19), idHomeTeam: '136013' });
        const first = MATCHES['136013'];
        NEXT_LIST = { '136013': [first, ev('b', 'Al-Ahli', 'Al-Hilal', 80), ev('c', 'Al-Hilal', 'Al-Ittihad', 150), ev('d', 'Al-Shabab', 'Al-Hilal', 160)] };
      },
      news: () => { NEWS.news.unshift({ headline: 'وزارة الموارد البشرية تعلن عن حزمة جديدة من البرامج لدعم توظيف الشباب في القطاع الخاص في جميع مناطق المملكة', source: 'واس', t: Date.now() - 6e5, link: '' }); },
    };
    for (const [w, h] of [[1920, 1080], [1280, 720], [960, 540]]) for (const panel of ['prayers', 'verse', 'hadith', 'forecast', 'matches', 'news']) {
      const { p, ctx, errors } = await nxOpen(`city=الرياض&lat=24.7136&lon=46.6753&tz=Asia%2FRiyadh&teams=136013&look=new&panel=${panel}`, { width: w, height: h });
      if (more[panel]) await p.evaluate(`(${more[panel]})()`);
      if (panel === 'hadith') await p.waitForFunction(() => !!HADITH_DAY, null, { timeout: 40000 }).catch(() => {});
      await p.evaluate(() => { window.sauditvSaver.swap(false); renderPanel(false); });
      await p.waitForTimeout(1600);
      const r = await p.evaluate(() => {
        const R = e => { const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom, w: b.width }; };
        const card = document.querySelector('#nx .week'), c = R(card), z = Math.min(innerWidth / 960, innerHeight / 540);
        const leaves = [...card.querySelectorAll('*')].filter(e => !e.children.length && e.textContent.trim() && getComputedStyle(e).display !== 'none');
        const out = leaves.filter(e => { const b = R(e); return b.l < c.l - 1 || b.r > c.r + 1 || b.t < c.t - 1 || b.b > c.b + 1; }).map(e => e.textContent.slice(0, 20));
        const cut = leaves.filter(e => e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).textOverflow !== 'ellipsis').map(e => e.textContent.slice(0, 20));
        const covered = leaves.filter(e => { const b = R(e), x = document.elementFromPoint((b.l + b.r) / 2, (b.t + b.b) / 2);
          return !x || !(e.contains(x) || x.contains(e)); }).map(e => e.textContent.slice(0, 20));
        return { c, max: 420 * z, rows: leaves.length, out, cut, covered, plate: getComputedStyle(card, '::before').zIndex, title: document.getElementById('week-hd').textContent,
                 others: ['#nx-pl', '.nx-cv', '.nx-fx'].map(q => R(document.querySelector(q))), W: innerWidth };
      });
      const clear = r.others.every(o => o.w === 0 || r.c.r <= o.l + 1 || o.r <= r.c.l + 1 || r.c.b <= o.t + 1 || o.b <= r.c.t + 1);
      check(`new look screensaver ${w}x${h}, card ${panel}: within its ${Math.round(r.max)} px, its text whole inside it, nothing over it, clear of the other corners`,
        !errors.length && r.rows > 0 && r.c.w <= r.max + 1 && r.c.r <= r.W && r.c.l >= 0 && !r.out.length && !r.cut.length && !r.covered.length
        && r.plate === '-1' && clear, JSON.stringify(r) + errors.join('; '));
      await ctx.close();
    }
  }

  // the screensaver's card in «الجديد» goes through exactly the cards picked in «المحتوى» (#panels), in their order,
  // every rotateMs (#rotate=15, «مدة كل بطاقة»'s default) as the classic screensaver's card does: at 960 x 540, each one in turn shown whole on its plate
  // (within its 420, nothing cut or over it), clear of the facts, the place and the clock
  {
    const LIST = ['prayers', 'verse', 'hadith', 'forecast', 'matches', 'standings', 'news'];
    const SEC = 15;   // «مدة كل بطاقة» (the settings' rotateSec, its default): the page's #rotate
    const TITLE = { prayers: 'مواقيت اليوم', forecast: 'الأيام القادمة · الرياض', matches: 'مباريات فرقك', standings: 'ترتيب دوري روشن', news: 'أخبار السعودية' };
    const DATA = () => {   // every card with its data; each switch of the rotation logged ([time, panel])
      const now = Date.now(), ev = { idEvent: 'x1', strHomeTeam: 'Al-Hilal', strAwayTeam: 'Al-Nassr', strTimestamp: new Date(now + 5.6 * 36e5).toISOString().slice(0, 19), idHomeTeam: '136013' };
      NEWS.news = [1, 2, 3].map(h => ({ headline: 'خبر رقم ' + h + ' من أخبار اليوم', source: 'واس', t: now - h * 36e5, link: '' }));
      FORECAST = [0, 1, 2, 3, 4].map(i => ({ date: new Date(now + i * 864e5).toISOString().slice(0, 10), code: 2, hi: 37 - i, lo: 24 - i }));
      STANDINGS = ['Al Hilal', 'Al Nassr', 'Al Ittihad', 'Al Ahli', 'Al Qadsiah', 'Al Shabab'].map((name, i) => ({ rank: i + 1, name, gp: 12, pts: 30 - 2 * i, espnId: String(i) }));
      MATCHES = { '136013': ev }; NEXT_LIST = { '136013': [ev] };
      renderMatches(); tickPrayer(); tickRamadan();
      if (NX) { NX_WX = { t: 31, code: 0, hum: 12, hi: 33, lo: 19 }; nxDraw(); }
      renderPanel(false);
      window.__swaps = [];
      const sw = swapPanel;
      window.swapPanel = P => { window.__swaps.push([Date.now(), P]); return sw(P); };
    };
    const state = () => {
      const R = e => { const b = e.getBoundingClientRect(); return { l: b.left, t: b.top, r: b.right, b: b.bottom, w: b.width }; };
      const card = document.querySelector('.week'), hd = document.getElementById('week-hd'), c = R(card), cs = getComputedStyle(card);
      const leaves = [...card.querySelectorAll('*')].filter(e => !e.children.length && e.textContent.trim() && getComputedStyle(e).display !== 'none');
      const q = card.querySelector('#week-list .quote');
      return { panel: CONFIG.panel, nx: !!card.closest('#nx'), c, max: 420 * Math.min(innerWidth / 960, innerHeight / 540), W: innerWidth, rows: leaves.length,
               shown: cs.display !== 'none' && cs.visibility === 'visible' && +cs.opacity === 1 && +getComputedStyle(document.getElementById('week-list')).opacity === 1,
               title: getComputedStyle(hd).display !== 'none' ? hd.textContent : '', quote: q ? (q.classList.contains('hadith') ? 'hadith' : q.textContent.slice(0, 1)) : '',
               out: leaves.filter(e => { const b = R(e); return b.l < c.l - 1 || b.r > c.r + 1 || b.t < c.t - 1 || b.b > c.b + 1; }).map(e => e.textContent.slice(0, 20)),
               cut: leaves.filter(e => e.scrollWidth > e.clientWidth + 1 && getComputedStyle(e).textOverflow !== 'ellipsis').map(e => e.textContent.slice(0, 20)),
               covered: leaves.filter(e => { const b = R(e), x = document.elementFromPoint((b.l + b.r) / 2, (b.t + b.b) / 2); return !x || !(e.contains(x) || x.contains(e)); })
                 .map(e => e.textContent.slice(0, 20)),
               others: ['#nx-pl', '.nx-cv', '.nx-fx'].map(s => document.querySelector(s)).filter(Boolean).map(R),
               facts: document.querySelectorAll('.nx-fx li').length };
    };
    const runs = {};
    for (const look of ['new', 'classic']) {
      const hash = `city=الرياض&lat=24.7136&lon=46.6753&tz=Asia%2FRiyadh&teams=136013&panel=${LIST[0]}&panels=${LIST.join(',')}&rotate=${SEC}` + (look === 'new' ? '&look=new' : '');
      const { p, ctx, errors } = await open('tv.html', hash, { width: 960, height: 540, at: AT, timers: true });
      await p.waitForFunction(() => !!HADITH_DAY, null, { timeout: 40000 }).catch(() => errors.push('no hadith'));
      await p.evaluate(DATA);
      await p.evaluate(() => window.sauditvSaver.swap(false));
      for (let i = 0; i < 40 && !(await p.evaluate(() => window.__swaps.length)); i++) await p.clock.runFor(1000);   // the rotation's first switch
      const t0 = await p.evaluate(() => window.__swaps[0][0]), seen = [];
      for (let k = 0; k < LIST.length; k++) {   // halfway through each card's turn (time also flows while the rows slide in)
        await p.clock.runFor(Math.max(0, t0 + SEC * 500 + k * SEC * 1000 - await p.evaluate(() => Date.now())));
        if (look === 'new') await p.waitForTimeout(1800);   // its rows' slide-in (weekIn) over
        seen.push(await p.evaluate(state));
      }
      runs[look] = { errors, seen, swaps: await p.evaluate(() => window.__swaps), rotateMs: await p.evaluate(() => CONFIG.rotateMs) };
      await ctx.close();
    }
    const want = LIST.slice(1).concat(LIST[0]), N = runs.new, C = runs.classic;
    const gaps = r => r.swaps.slice(1).map((s, i) => s[0] - r.swaps[i][0]);   // (time flows: a few ms either way)
    const everySec = r => gaps(r).length === LIST.length - 1 && gaps(r).every(g => Math.abs(g - SEC * 1000) < 250);
    const brief = r => ({ errors: r.errors, seen: r.seen.map(s => [s.panel, s.title || s.quote, s.shown]), swaps: r.swaps.map(s => s[1]), gaps: gaps(r) });
    check(`new look screensaver, ${LIST.length} cards picked: its card shows each in turn, in their order, every ${SEC} s (#rotate), as the classic one does`,
      !N.errors.length && !C.errors.length && N.rotateMs === SEC * 1000 && C.rotateMs === SEC * 1000
      && N.seen.map(s => s.panel).join() === want.join() && N.swaps.map(s => s[1]).join() === want.join()
      && C.seen.map(s => s.panel).join() === want.join() && C.swaps.map(s => s[1]).join() === want.join()
      && everySec(N) && everySec(C),
      JSON.stringify({ new: brief(N), classic: brief(C) }));
    for (const s of N.seen) {
      const clear = s.others.length === 3 && s.others.every(o => o.w === 0 || s.c.r <= o.l + 1 || o.r <= s.c.l + 1 || s.c.b <= o.t + 1 || o.b <= s.c.t + 1);
      const right = TITLE[s.panel] ? s.title === TITLE[s.panel] && !s.quote : s.quote === (s.panel === 'verse' ? '﴿' : 'hadith');
      check(`new look screensaver, the rotation's ${s.panel} card: shown whole on its plate within its ${Math.round(s.max)} px, its own content, clear of the facts, the place and the clock`,
        s.nx && s.shown && right && s.rows > 0 && s.c.w <= s.max + 1 && s.c.l >= 0 && s.c.r <= s.W && !s.out.length && !s.cut.length && !s.covered.length
        && clear && s.facts >= 3, JSON.stringify(s));
    }
  }

  // a new or offline TV: the online photos' list bundled with the page (dist/online.js), until a fetch of it succeeds
  {
    const offline = (u, r) => /\/photos\/(online|index)\.json$|wikimedia\.org/.test(u) ? r.abort() : null;
    // the bundled list as built: its saudi list is empty while tools/online_photos.py's SAUDI is off (2026-10-25); then
    // the bundled world list (Commons' Picture of the Day) stands in for the online photo tests below
    const BUNDLED = JSON.parse(readFileSync(path.join(ROOT, 'dist/online.js'), 'utf8').replace(/^window\.SAUDITV_ONLINE = /, '').replace(/;\s*$/, ''));
    const SRC = BUNDLED.saudi.length ? 'saudi' : 'world';
    let { p, ctx, errors } = await open('tv.html', 'photos=saudi', { seed: false, route: offline });
    let r = await p.evaluate(() => { const b = photoFor(BG_SLOT, photoPools());
      return { bundled: !!ONLINE_BUNDLED && ONLINE === ONLINE_BUNDLED, n: ONLINE.saudi.length, pick: b && { on: b.on, p: b.p, u: b.u }, credit: b ? photoCredit(b) : '',
               shown: document.querySelector('.photo').style.backgroundImage, creditOn: document.getElementById('credit').classList.contains('on') }; });
    if (BUNDLED.saudi.length)
      check('online photos: a new TV offline picks a Saudi photo from the bundled list (dist/online.js), with its place and credit',
        !errors.length && r.bundled && r.n >= 100 && r.pick && r.pick.on && r.pick.p && /^https:\/\/(thumb|upload)\.wikimedia\.org\//.test(r.pick.u)
        && r.credit.startsWith(r.pick.p + ' · '), JSON.stringify(r) + errors.join('; '));
    else
      check('online photos: the bundled list (dist/online.js) has no Saudi photos (switched off): #photos=saudi picks none, no errors',
        !errors.length && r.bundled && r.n === 0 && !r.pick && !r.credit, JSON.stringify(r) + errors.join('; '));
    check('online photos: offline its picture cannot load: a bundled background shows, without a credit', /assets\/backgrounds\//.test(r.shown) && !r.creditOn, JSON.stringify(r));
    await ctx.close();
    // the picture at hand (the app has it on disk): it shows with its place and credit
    ({ p, ctx, errors } = await open('tv.html', 'photos=' + SRC, { seed: false,
      route: (u, r) => /wikimedia\.org/.test(u) ? r.fulfill({ path: path.join(ROOT, 'photos/-RlktmDy3Ec.jpg') }) : /\/photos\/(online|index)\.json$/.test(u) ? r.abort() : null }));
    await p.waitForFunction(() => document.getElementById('credit').classList.contains('on'), null, { timeout: 4000 }).catch(() => {});
    r = await p.evaluate(() => ({ credit: document.getElementById('credit').textContent, place: photoFor(BG_SLOT, photoPools()).p, bg: document.querySelector('.photo').style.backgroundImage }));
    check(`online photos: the bundled list's photo (${SRC}) shows, its credit «المكان · تصوير … · الرخصة» on screen`, !errors.length && /wikimedia/.test(r.bg)
      && (r.place ? r.credit.startsWith(r.place + ' · ') : r.credit.startsWith('تصوير ')), JSON.stringify(r) + errors.join('; '));
    await ctx.close();
    // a fetch of the list succeeds: it is used and kept; offline later, the kept one (not the bundled one)
    const fresh = { v: 1, saudi: [{ u: 'https://thumb.wikimedia.org/x/fresh.jpg', a: 'A', l: 'CC BY 4.0', p: 'جديد', b: 0.5 }], world: [], bing: [] };
    let serve = true;
    ({ p, ctx, errors } = await open('tv.html', 'photos=saudi', { seed: false,
      route: (u, r) => serve && /\/photos\/online\.json$/.test(u) ? r.fulfill({ body: JSON.stringify(fresh), contentType: 'application/json' }) : offline(u, r) }));
    await p.waitForFunction(() => ONLINE.saudi.length === 1, null, { timeout: 4000 }).catch(() => {});
    r = await p.evaluate(() => ({ n: ONLINE.saudi.length, kept: JSON.parse(localStorage.getItem('photoOnline') || 'null') }));
    serve = false;   // offline now
    await p.reload();
    await p.waitForTimeout(500);
    const again = await p.evaluate(() => ({ n: ONLINE.saudi.length, bundled: ONLINE === ONLINE_BUNDLED }));
    check('online photos: a fetched list is used and kept, and offline afterwards the kept one, not the bundled one',
      !errors.length && r.n === 1 && r.kept && r.kept.saudi.length === 1 && again.n === 1 && !again.bundled, JSON.stringify({ r, again }));
    await ctx.close();
  }

  // «توفير الطاقة» in «الجديد»: the «الأسواق» line is the ticker tape, in steps as before
  {
    const { p, ctx, errors } = await nxOpen(`${NXH}&panel=prayers&lowpower=1`);
    await p.waitForFunction(() => !!TAPE_ANIM, null, { timeout: 3000 }).catch(() => errors.push('no tape animation'));
    const r = await p.evaluate(() => ({ easing: TAPE_ANIM ? TAPE_ANIM.effect.getTiming().easing : '', label: getComputedStyle(document.getElementById('nx-mk')).display,
      bg: getComputedStyle(document.getElementById('tape')).backgroundColor, frost: getComputedStyle(document.getElementById('tape-frost')).display }));
    check('new look: low power keeps the tape in steps; it is the «الأسواق» line (no glass, no frost)',
      !errors.length && /^steps\(/.test(r.easing) && r.label !== 'none' && r.bg === 'rgba(0, 0, 0, 0)' && r.frost === 'none', JSON.stringify(r) + errors.join('; '));
    await ctx.close();
  }
}

await browser.close();
console.log(failed ? `\n${failed} failed` : '\nall page tests passed');
process.exit(failed ? 1 : 0);
