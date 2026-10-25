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
import { readFileSync } from 'node:fs';
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
async function open(file, hash, { width = 1920, height = 1080, seed = true, at = FIXED, feeds = {}, init = null } = {}) {
  const ctx = await browser.newContext({ viewport: { width, height } });
  if (at) await ctx.clock.setFixedTime(at);
  await ctx.route(/^https?:/, r => {
    const u = r.request().url(), i = u.indexOf('/main/'), rel = i > 0 ? u.slice(i + 6) : '';
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
                 verse: [2, 'آية اليوم'], hadith: [2, 'حديث اليوم'], shows: [1, 'هذا الأسبوع'], local: [3, 'أخبار الرياض'],
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
    'arabic\\.cnn\\.com': rss([['خبر سي إن إن', 3], ['خبر مشترك', 4]]),
    'news\\.google\\.com/rss/search\\?q=site%3Aalarabiya\\.net': rss([['خبر العربية - العربية', 2, 'العربية', 'https://www.alarabiya.net'],
                                                                      ['خبر من موقع آخر - موقع', 0.5, 'موقع', 'https://other.example']]),
  };
  const { p, ctx, errors } = await open('tv.html', 'panel=world&src_world=skynews,cnn,alarabiya,bogus', { feeds });
  await p.waitForFunction(() => NEWS.world.length > 0, null, { timeout: 20000 }).catch(() => {});
  const r = await p.evaluate(() => NEWS.world.map(x => x.headline + ' | ' + x.source));
  check('world news: the picked sources together, each story once, newest first, labelled; Google kept to the site',
    !errors.length && JSON.stringify(r) === JSON.stringify(['خبر سكاي الأحدث | سكاي نيوز عربية', 'خبر العربية | العربية',
      'خبر سي إن إن | CNN بالعربية', 'خبر مشترك | CNN بالعربية']), JSON.stringify(r) + errors.join('; '));
  const d = await p.evaluate(() => [newsSources('world').map(x => x.id).join(','), (CONFIG.sources.world = [], newsSources('world').map(x => x.id).join(','))]);
  check('news sources: the picks from the settings; none valid = the page\'s defaults', d[0] === 'alarabiya,skynews,cnn'
    && d[1] === 'alarabiya,skynews,bbc,cnn', JSON.stringify(d));
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

// ---- old settings links and «لا شيء»
for (const [hash, panel, shown] of [['', 'shows', true], ['week=0', 'none', false], ['week=1', 'shows', true],
                                     ['panel=none', 'none', false], ['panel=bogus', 'shows', true]]) {
  const { p, ctx, errors } = await open('tv.html', hash);
  const r = await p.evaluate(() => [CONFIG.panel, getComputedStyle(document.querySelector('.week')).display !== 'none']);
  check(`link #${hash || '(empty)'} → ${panel}, card ${shown ? 'shown' : 'hidden'}`,
    !errors.length && r[0] === panel && r[1] === shown, JSON.stringify(r) + errors.join('; '));
  await ctx.close();
}

// ---- card-only mode for the launcher: transparent, only the card, no photo
for (const panel of ['prayers', 'verse', 'hadith', 'shows']) {
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

// ---- last panels come back at once on the next start
{
  const ctx = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  await ctx.clock.setFixedTime(FIXED);
  await ctx.route(/^https?:/, r => r.abort());
  const p = await ctx.newPage();
  await p.goto(page('tv.html') + '#panel=shows');
  await p.waitForTimeout(800);
  await p.evaluate(() => { document.getElementById('week-list').innerHTML = '<li>SAVED</li>'; saveSnap(); });
  await p.reload();
  const t = await p.evaluate(() => document.getElementById('week-list').textContent);
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

await browser.close();
console.log(failed ? `\n${failed} failed` : '\nall page tests passed');
process.exit(failed ? 1 : 0);
