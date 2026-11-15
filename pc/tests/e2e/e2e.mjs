// End-to-end browser tests: real ferry.py server + headless Chrome with phone and Fire TV user agents.
// Usage: node tests/e2e/e2e.mjs   (needs `npm i playwright` reachable via NODE_PATH, and a Chrome binary)
import { chromium } from 'playwright';
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, readdirSync, readFileSync, existsSync, mkdirSync, utimesSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..', '..');
const tmp = mkdtempSync(path.join(tmpdir(), 'ferry-e2e-'));
const inbox = path.join(tmp, 'in'), outbox = path.join(tmp, 'out'), src = path.join(tmp, 'src');
mkdirSync(src);
const PORT = 18000 + Math.floor(Math.random() * 1000);
const ANDROID = 'Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36';
const FIRETV = 'Mozilla/5.0 (Linux; Android 9; AFTKA Build/PS7633) AppleWebKit/537.36 (KHTML, like Gecko) Silk/131.3.1 like Chrome/131.0.0.0 Safari/537.36';
const results = [];

function check(name, fn) { return fn().then(() => results.push(['PASS', name]), (e) => results.push(['FAIL', name + ': ' + e.message])); }
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const server = spawn(process.env.FERRY_PY || 'python3', [path.join(ROOT, 'ferry.py'), '--port', String(PORT), '--inbox', inbox, '--outbox', outbox, '--no-open'],
  { env: { ...process.env, HOME: tmp, FERRY_LAUNCHER: 'test' }, stdio: ['ignore', 'pipe', 'pipe'] });
let log = '';
server.stdout.on('data', d => { log += d; });
server.stderr.on('data', d => { log += d; });

async function waitUp() {
  for (let i = 0; i < 50; i++) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/api/v1/info`); if (r.ok) return; } catch (e) {}
    await sleep(100);
  }
  throw new Error('server did not start: ' + log);
}

const exe = ['/usr/bin/google-chrome', '/usr/bin/chromium'].find(existsSync);
let browser;
try {
  await waitUp();
  browser = await chromium.launch({ executablePath: exe, args: ['--no-sandbox'] });

  // ---------- Phone: upload ----------
  const phone = await browser.newContext({ userAgent: ANDROID, viewport: { width: 400, height: 860 }, hasTouch: true, isMobile: true, acceptDownloads: true });
  const page = await phone.newPage();
  const consoleErrors = [];
  page.on('pageerror', e => consoleErrors.push(String(e)));
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  await page.goto(`http://127.0.0.1:${PORT}/`);

  const asBuf = (p, name) => ({ name: name || path.basename(p), mimeType: 'application/octet-stream', buffer: readFileSync(p) });
  await check('phone opens on Send tab with PC name', async () => {
    assert.equal(await page.getAttribute('#tab-send', 'aria-selected'), 'true');
    assert.equal(await page.isVisible('#send-ready'), true);
    assert.ok((await page.textContent('#pcname')).length > 0);
    assert.equal(await page.textContent('#inbox-label'), inbox);
  });

  const f1 = path.join(src, 'IMG_0001.jpg'), f2 = path.join(src, 'Report न.pdf'), f3 = path.join(src, 'big.bin');
  writeFileSync(f1, Buffer.alloc(150000, 1)); writeFileSync(f2, Buffer.from('pdf data')); writeFileSync(f3, Buffer.alloc(8 * 1024 * 1024, 7));
  utimesSync(f1, new Date(1700000000000), new Date(1700000000000));

  await check('upload 3 mixed files via Any file -> All done', async () => {
    // Playwright drops files given by a non-ASCII *path*; pass the Unicode-named one as a buffer.
    await page.setInputFiles('#pick-any', [asBuf(f1), asBuf(f2, 'Report न.pdf'), asBuf(f3)]);
    await page.waitForSelector('#send-done:not([hidden])', { timeout: 20000 });
    await page.click('summary');
    const det = await page.textContent('#d-list');
    assert.equal(await page.textContent('#d-title'), 'All done', det + ' || ' + log);
    await page.click('summary');
    assert.match(await page.textContent('#d-line1'), /^3 saved · 0 already on PC/, 'details: ' + det + ' | inbox: ' + readdirSync(inbox).join(','));
    const files = readdirSync(inbox).sort();
    assert.deepEqual(files, ['IMG_0001.jpg', 'Report न.pdf', 'big.bin'].sort());
    assert.equal(readFileSync(path.join(inbox, 'big.bin')).length, 8 * 1024 * 1024);
  });

  await check('re-send same files -> Already on PC (pre-check, no re-upload)', async () => {
    await page.click('#more-btn');
    await page.setInputFiles('#pick-any', [asBuf(f1), asBuf(f2, 'Report न.pdf')]);
    await page.waitForSelector('#send-done:not([hidden])', { timeout: 20000 });
    assert.match(await page.textContent('#d-line1'), /^0 saved · 2 already on PC/);
    assert.equal(readdirSync(inbox).length, 3);
  });

  await check('same name, different content -> renamed', async () => {
    const f4 = path.join(tmp, 'IMG_0001.jpg'); writeFileSync(f4, Buffer.from('different'));
    await page.click('#more-btn');
    await page.setInputFiles('#pick-any', [f4]);
    await page.waitForSelector('#send-done:not([hidden])', { timeout: 20000 });
    assert.match(await page.textContent('#d-line2'), /^1 renamed/);
    assert.ok(readdirSync(inbox).includes('IMG_0001 (1).jpg'));
    await page.click('summary');
    assert.match(await page.textContent('#d-list'), /Saved as/);
  });

  // ---------- Phone: From PC ----------
  writeFileSync(path.join(outbox, 'Kodi.apk'), Buffer.from('apk-bytes'));
  writeFileSync(path.join(outbox, 'movie.mp4'), Buffer.alloc(2000, 3));
  mkdirSync(path.join(outbox, 'Holiday'));
  await sleep(3500);

  await check('From PC tab lists outbox files, folder hint, Download all', async () => {
    await page.click('#tab-from');
    await page.waitForSelector('#o-list .outrow', { timeout: 8000 });
    const names = await page.$$eval('#o-list .nm', els => els.map(e => e.textContent));
    assert.deepEqual(names, ['movie.mp4', 'Kodi.apk'].length === names.length ? names : ['?']);
    assert.ok(names.includes('Kodi.apk') && names.includes('movie.mp4'));
    assert.equal(await page.isVisible('#o-folders'), true);
    assert.equal(await page.isVisible('#dl-all'), true);
    assert.match(await page.textContent('#badge'), /\(2\)/);
  });

  await check('tap a row downloads the file', async () => {
    const [dl] = await Promise.all([page.waitForEvent('download'), page.click('#o-list .outrow:has-text("Kodi.apk")')]);
    assert.equal(dl.suggestedFilename(), 'Kodi.apk');
    const p = await dl.path();
    assert.equal(readFileSync(p).toString(), 'apk-bytes');
  });

  await check('Download all starts one download per file', async () => {
    const got = [];
    page.on('download', d => got.push(d.suggestedFilename()));
    await page.click('#dl-all');
    await sleep(3000);
    assert.deepEqual(got.sort(), ['Kodi.apk', 'movie.mp4']);
  });

  await check('banner appears when the PC is unreachable', async () => {
    await page.route('**/api/v1/outbox', r => r.abort());
    await page.evaluate(() => document.getElementById('tab-from').click());
    await page.waitForSelector('#banner:not([hidden])', { timeout: 5000 });
    await page.unroute('**/api/v1/outbox');
  });

  await check('no page errors (phone)', async () => { assert.deepEqual(consoleErrors.filter(e => !/ERR_FAILED|Failed to load resource/.test(e)), []); });
  await phone.close();

  // ---------- Fire TV ----------
  const tv = await browser.newContext({ userAgent: FIRETV, viewport: { width: 1280, height: 720 } });
  const tpage = await tv.newPage();
  const tvErrors = [];
  tpage.on('pageerror', e => tvErrors.push(String(e)));
  await tpage.goto(`http://127.0.0.1:${PORT}/`);
  await check('TV mode: no tabs, list only, Play only on mp4, first control focused', async () => {
    await tpage.waitForSelector('#tv-list .tvrow', { timeout: 8000 });
    assert.equal(await tpage.isVisible('#tabs'), false);
    assert.equal(await tpage.isVisible('#pane-send'), false);
    assert.equal(await tpage.evaluate(() => document.body.classList.contains('tv')), true);
    const rows = await tpage.$$eval('#tv-list .tvrow', rs => rs.map(r => r.innerText.replace(/\s+/g, ' ')));
    assert.ok(rows.some(r => /movie\.mp4.*Play.*Save/.test(r)), rows.join(' | '));
    assert.ok(rows.some(r => /Kodi\.apk.*Install \/ Save/.test(r) && !/Play/.test(r)), rows.join(' | '));
    const first = await tpage.$eval('#tv-list a', a => a.textContent);
    const focused = await tpage.evaluate(() => document.activeElement && document.activeElement.textContent);
    assert.equal(focused, first);
  });
  await check('TV D-pad: Tab/arrow moves focus through controls', async () => {
    const all = await tpage.$$eval('#tv-list a', as => as.map(a => a.textContent));
    await tpage.keyboard.press('Tab');
    const f = await tpage.evaluate(() => document.activeElement.textContent);
    assert.equal(f, all[1]);
  });
  await check('Play link serves inline with range support', async () => {
    const href = await tpage.getAttribute('#tv-list a:has-text("Play")', 'href');
    assert.match(href, /\?inline=1$/);
    const r = await fetch(`http://127.0.0.1:${PORT}${href}`, { headers: { Range: 'bytes=0-9' } });
    assert.equal(r.status, 206);
    assert.match(r.headers.get('content-disposition'), /^inline/);
  });
  await check('no page errors (TV)', async () => { assert.deepEqual(tvErrors, []); });
  await tv.close();

  // ---------- Diag page ----------
  const d = await browser.newContext({ userAgent: ANDROID });
  const dpage = await d.newPage();
  await dpage.goto(`http://127.0.0.1:${PORT}/diag`);
  await check('diag inspect reports a picked file without saving it', async () => {
    const before = readdirSync(inbox).length;
    await dpage.setInputFiles('#i-any', f1);
    await dpage.waitForFunction(() => /bytes \| GPS:/.test(document.getElementById('pick').textContent), null, { timeout: 8000 });
    assert.match(await dpage.textContent('#pick'), /GPS: (NONE|UNKNOWN)/);
    assert.equal(readdirSync(inbox).length, before);
  });
  await d.close();

  // ---------- Stage 7 audit regressions (fresh page, small phone) ----------
  const p2ctx = await browser.newContext({ userAgent: ANDROID, viewport: { width: 360, height: 700 }, hasTouch: true, isMobile: true });
  const p2 = await p2ctx.newPage();
  const p2Errors = [];
  p2.on('pageerror', e => p2Errors.push(String(e)));
  await p2.goto(`http://127.0.0.1:${PORT}/`);
  const tiny = (n, tag) => Array.from({ length: n }, (_, i) => ({ name: `${tag}_${String(i).padStart(3, '0')}.jpg`, mimeType: 'image/jpeg', buffer: Buffer.from(tag + i) }));

  await check('S5: closing the picker without choosing stops the keep-awake video', async () => {
    p2.once('filechooser', () => {});
    await p2.click('#pick-any-btn');
    await sleep(200);
    await p2.evaluate(() => document.getElementById('pick-any').dispatchEvent(new Event('cancel')));
    assert.equal(await p2.evaluate(() => document.getElementById('keepawake').paused), true);
  });

  await check('S2: long queue - page is not auto-scrolled, Cancel stays reachable', async () => {
    await p2.route('**/api/v1/upload**', async r => { await sleep(120); await r.continue().catch(() => {}); });   // (the request may be cancelled meanwhile)
    await p2.setInputFiles('#pick-any', tiny(80, 'long'));
    await p2.waitForSelector('#send-progress:not([hidden])');
    await p2.waitForFunction(() => /Sending [3-9] of 80/.test(document.getElementById('p-title').textContent), null, { timeout: 10000 });
    await p2.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    const ys = [];
    for (let i = 0; i < 4; i++) { await sleep(300); ys.push(await p2.evaluate(() => Math.round(window.scrollY))); }
    assert.ok(ys.every(y => y === ys[0]), 'page scroll moved by itself: ' + ys.join(','));
    const box = await p2.evaluate(() => { const r = document.getElementById('cancel-btn').getBoundingClientRect(); return { top: r.top, bottom: r.bottom, h: innerHeight }; });
    assert.ok(box.top >= 0 && box.bottom <= box.h, 'Cancel not in view: ' + JSON.stringify(box));
    const follows = await p2.evaluate(() => {      // the current row is visible inside the list box
      const l = document.getElementById('p-list').getBoundingClientRect();
      const cur = Array.from(document.querySelectorAll('#p-list li')).find(li => /%|Checking/.test(li.firstChild.textContent));
      if (!cur) return true;
      const r = cur.getBoundingClientRect(); return r.top >= l.top - 1 && r.bottom <= l.bottom + 1;
    });
    assert.ok(follows, 'current row not visible in the list');
    await p2.click('#cancel-btn');
    await p2.waitForSelector('#send-done:not([hidden])', { timeout: 5000 });
    assert.equal(await p2.textContent('#d-title'), 'Stopped');
    await p2.unroute('**/api/v1/upload**');
    await p2.click('#more-btn');
  });

  await check('S1: screen off then on before the upload fails -> continues by itself', async () => {
    let n = 0;
    await p2.route('**/api/v1/upload**', async r => { if (n++ === 0) { await sleep(700); await r.abort().catch(() => {}); } else await r.continue().catch(() => {}); });
    await p2.setInputFiles('#pick-any', tiny(2, 'scr'));
    await p2.waitForFunction(() => /%/.test(document.getElementById('p-list').textContent), null, { timeout: 5000 });
    await p2.evaluate(() => {
      const set = v => { Object.defineProperty(document, 'visibilityState', { get: () => v, configurable: true }); document.dispatchEvent(new Event('visibilitychange')); };
      set('hidden'); set('visible');
    });
    await p2.waitForSelector('#send-done:not([hidden])', { timeout: 10000 });
    assert.equal(await p2.textContent('#d-title'), 'All done');
    assert.match(await p2.textContent('#d-line1'), /^2 saved/);
    await p2.unroute('**/api/v1/upload**');
    assert.ok(await p2.evaluate(() => !document.getElementById('live').closest('[hidden]')), 'S10: live region must not sit in a hidden container');
    assert.match(await p2.textContent('#live'), /All done/);
    await p2.click('#more-btn');
  });

  await check('S3/S4/S9: PC gone -> stops at once with guidance in the card; Retry sends everything', async () => {
    const dead = r => r.abort();
    await p2.route('**/api/v1/check**', dead); await p2.route('**/api/v1/info', dead); await p2.route('**/api/v1/outbox', dead);
    const t0 = Date.now();
    await p2.setInputFiles('#pick-any', tiny(6, 'gone'));
    await p2.waitForSelector('#send-done:not([hidden])', { timeout: 8000 });
    assert.ok(Date.now() - t0 < 6000, 'should stop after the first file, not grind through all six');
    assert.equal(await p2.textContent('#d-title'), "Can't reach the PC");
    assert.equal(await p2.textContent('#retry-btn'), 'Retry 6 not sent');
    assert.equal(await p2.isVisible('#d-help'), true);
    assert.match(await p2.textContent('#d-help'), /same Wi-Fi, then tap "Retry 6 not sent"/);
    assert.match(await p2.textContent('#banner-text'), /^Can't reach the PC/);
    await p2.unroute('**/api/v1/check**'); await p2.unroute('**/api/v1/info'); await p2.unroute('**/api/v1/outbox');
    await p2.click('#retry-btn');
    await p2.waitForFunction(() => document.getElementById('d-title').textContent === 'All done' && !document.getElementById('send-done').hidden, null, { timeout: 10000 });
    assert.match(await p2.textContent('#d-line1'), /^6 saved/);
    assert.equal(await p2.isVisible('#d-help'), false);
    await p2.click('#more-btn');
  });

  await check('S4/S9: disk full -> guidance stays in the card, banner text untouched', async () => {
    await p2.route('**/api/v1/check**', r => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: false, reason: 'disk_full', free_bytes: 5 }) }));
    await p2.setInputFiles('#pick-any', tiny(3, 'full'));
    await p2.waitForSelector('#send-done:not([hidden])', { timeout: 5000 });
    assert.equal(await p2.textContent('#d-title'), 'PC disk is full');
    assert.match(await p2.textContent('#d-help'), /^Free up space on the PC, then tap "Retry 3 not sent"/);
    assert.match(await p2.textContent('#banner-text'), /^Can't reach the PC/);
    assert.equal(await p2.isVisible('#banner'), false);
    await p2.click('summary');
    const row = await p2.evaluate(() => {           // S8: the reason has its own line; the name keeps its room
      const li = document.querySelectorAll('#d-list li')[1];
      return { name: li.querySelector('.fname').clientWidth, over: li.scrollWidth - li.clientWidth, reason: li.querySelector('.fsize').textContent };
    });
    assert.equal(row.reason, 'Not sent - PC disk full');
    assert.ok(row.name > 100 && row.over <= 0, JSON.stringify(row));
    await p2.unroute('**/api/v1/check**');
    await p2.click('#more-btn');
  });

  await check('S8: a long error message from the PC does not hide the file name', async () => {
    const msg = "The PC couldn't save the file: " + 'Access is denied because of something very long indeed '.repeat(4);
    await p2.route('**/api/v1/upload**', r => r.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ result: 'error', message: msg }) }));
    await p2.setInputFiles('#pick-any', tiny(1, 'IMG_20240101_123456_a_long_name'));
    await p2.waitForSelector('#send-done:not([hidden])', { timeout: 5000 });
    const row = await p2.evaluate(() => {
      const li = document.querySelector('#d-list li');
      return { name: li.querySelector('.fname').clientWidth, over: li.scrollWidth - li.clientWidth, page: document.documentElement.scrollWidth - innerWidth };
    });
    assert.ok(row.name > 100 && row.over <= 0 && row.page <= 0, JSON.stringify(row));
    await p2.unroute('**/api/v1/upload**');
  });
  await check('no page errors (audit scenarios)', async () => { assert.deepEqual(p2Errors, []); });
  await p2ctx.close();
} catch (e) {
  results.push(['FAIL', 'harness: ' + e.stack]);
} finally {
  if (browser) await browser.close();
  server.kill('SIGINT');
}
for (const [s, n] of results) console.log(s + '  ' + n);
const failed = results.filter(r => r[0] === 'FAIL').length;
console.log(failed ? `\n${failed} FAILED` : `\nALL ${results.length} PASSED`);
process.exit(failed ? 1 : 0);
