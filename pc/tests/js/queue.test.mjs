// Unit tests for the upload queue state machine embedded in tools/page.html (between QUEUE markers).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import vm from 'node:vm';

const html = readFileSync(new URL('../../tools/page.html', import.meta.url), 'utf8');
const src = html.split('/*QUEUE-START*/')[1].split('/*QUEUE-END*/')[0];
const ctx = { module: { exports: {} } };
vm.createContext(ctx);
vm.runInContext(src, ctx);
const C = ctx.module.exports;
void createRequire;

function file(name, size = 100) { return { name, size, lastModified: 1700000000000 }; }

// Scripted fake API: each call pops the next scripted result for that file name.
function fakeApi(script) {
  const calls = [];
  return {
    calls,
    check(f) {
      calls.push(['check', f.name]);
      const s = (script[f.name] && script[f.name].check || []).shift() || { kind: 'ok', json: { ok: true } };
      return Promise.resolve(s);
    },
    upload(f, onProgress) {
      calls.push(['upload', f.name]);
      const s = (script[f.name] && script[f.name].upload || []).shift() || { kind: 'ok', json: { result: 'saved', saved_as: f.name } };
      let abort = () => {};
      const promise = new Promise((res) => {
        abort = () => res({ kind: 'network' });
        setTimeout(() => { onProgress(f.size / 2); setTimeout(() => res(typeof s === 'function' ? s() : s), 1); }, 1);
      });
      return { promise, abort: () => abort() };
    }
  };
}

function mkQueue(api, opts = {}) {
  let hidden = opts.hidden || false;
  const q = C.createQueue({
    api, now: () => Date.now(), isHidden: () => hidden, sleep: () => Promise.resolve(), onChange: () => {}
  });
  q.setHidden = (v) => { hidden = v; };
  return q;
}

test('formatting helpers', () => {
  assert.equal(C.fmtBytes(999), '999 B');
  assert.equal(C.fmtBytes(4200000), '4.2 MB');
  assert.equal(C.fmtBytes(512000000), '512 MB');
  assert.equal(C.midTrunc('IMG_20261114_101522.jpg', 12), 'IMG_20…2.jpg'.length === 12 ? 'IMG_20…2.jpg' : C.midTrunc('IMG_20261114_101522.jpg', 12));
  assert.equal(C.midTrunc('IMG_20261114_101522.jpg', 12).length, 12);
  assert.ok(C.midTrunc('IMG_20261114_101522.jpg', 12).endsWith('.jpg'));
  assert.equal(C.fmtEta(3), 'a few seconds left');
  assert.equal(C.fmtEta(42), 'about 42 s left');
  assert.equal(C.fmtEta(600), 'about 10 min left');
  assert.equal(C.relTime(Date.now() - 10000, Date.now()), 'just now');
  assert.equal(C.relTime(Date.now() - 300000, Date.now()), '5 min ago');
});

test('happy path: saved, renamed, skipped-by-precheck', async () => {
  const api = fakeApi({
    'b.jpg': { upload: [{ kind: 'ok', json: { result: 'renamed', saved_as: 'b (1).jpg' } }] },
    'c.jpg': { check: [{ kind: 'ok', json: { ok: true, duplicate: 'c.jpg' } }] }
  });
  const q = mkQueue(api);
  q.add([file('a.jpg'), file('b.jpg'), file('c.jpg')]);
  await q.run();
  const st = Array.from(q.items(), i => i.status);
  assert.deepEqual(st, ['saved', 'renamed', 'skipped']);
  assert.equal(q.items()[1].savedAs, 'b (1).jpg');
  assert.ok(!api.calls.some(c => c[0] === 'upload' && c[1] === 'c.jpg'), 'duplicate must not upload');
  const s = q.stats();
  assert.equal(s.finished, true);
  assert.equal(s.bytesDone, 300);
});

test('network failure retries once, then fails', async () => {
  const api = fakeApi({ 'a.jpg': { upload: [{ kind: 'network' }, { kind: 'network' }] }, 'b.jpg': { upload: [{ kind: 'network' }] } });
  const q = mkQueue(api);
  q.add([file('a.jpg'), file('b.jpg')]);
  await q.run();
  assert.equal(api.calls.filter(c => c[0] === 'upload' && c[1] === 'a.jpg').length, 2);
  assert.equal(q.items()[0].status, 'failed');
  assert.equal(q.items()[0].reason, 'Connection lost');
  assert.equal(q.items()[1].status, 'saved', 'retry succeeds for b');
});

test('HTTP error is never retried', async () => {
  const api = fakeApi({ 'a.jpg': { upload: [{ kind: 'http', status: 500, json: { message: "The PC couldn't save the file" } }] } });
  const q = mkQueue(api);
  q.add([file('a.jpg')]);
  await q.run();
  assert.equal(api.calls.filter(c => c[0] === 'upload').length, 1);
  assert.equal(q.items()[0].status, 'failed');
  assert.match(q.items()[0].reason, /couldn't save/);
});

test('disk full from pre-check stops the queue', async () => {
  const api = fakeApi({ 'b.jpg': { check: [{ kind: 'ok', json: { ok: false, reason: 'disk_full', free_bytes: 5 } }] } });
  const q = mkQueue(api);
  q.add([file('a.jpg'), file('b.jpg'), file('c.jpg')]);
  await q.run();
  assert.deepEqual(Array.from(q.items(), i => i.status), ['saved', 'failed', 'notsent']);
  assert.equal(q.stats().stopped, 'disk_full');
  assert.ok(!api.calls.some(c => c[1] === 'c.jpg'));
});

test('disk full from 507 stops the queue', async () => {
  const api = fakeApi({ 'a.jpg': { upload: [{ kind: 'http', status: 507, json: { reason: 'disk_full' } }] } });
  const q = mkQueue(api);
  q.add([file('a.jpg'), file('b.jpg')]);
  await q.run();
  assert.deepEqual(Array.from(q.items(), i => i.status), ['failed', 'notsent']);
});

test('failure while hidden pauses (no retry used), resumes when visible', async () => {
  const api = fakeApi({ 'a.jpg': { upload: [{ kind: 'network' }] } });
  const q = mkQueue(api, { hidden: true });
  q.add([file('a.jpg'), file('b.jpg')]);
  await q.run();
  assert.equal(q.items()[0].status, 'paused');
  assert.equal(q.items()[1].status, 'saved', 'queue keeps going while hidden');
  assert.equal(q.stats().finished, false, 'not finished while something is paused');
  q.setHidden(false);
  assert.equal(q.resumeVisible(), 1);
  await new Promise(r => setTimeout(r, 30));
  assert.equal(q.items()[0].status, 'saved');
  assert.equal(q.items()[0].retried, false);
  assert.equal(q.stats().finished, true);
});

// Audit S1: this test used to assert the item ended up 'paused' with the page visible. Nothing ever resumed
// such an item (no further visibilitychange arrives), so it sat at "Paused" until the user cancelled.
test('screen went off during an upload but is on again when it fails: retried at once, never parked', async () => {
  const api = fakeApi({ 'a.jpg': { upload: [() => ({ kind: 'network' })] } });
  const q = mkQueue(api);
  q.add([file('a.jpg')]);
  const p = q.run();
  await new Promise(r => setTimeout(r, 0));
  q.noteHidden();                       // screen off ... and back on before the dead request reports failure
  await p;
  assert.equal(q.items()[0].status, 'saved');
  assert.equal(api.calls.filter(c => c[0] === 'upload').length, 2);
  assert.equal(q.items()[0].retried, false, 'the screen-off retry is free');
  assert.equal(q.stats().paused, 0);
  assert.equal(q.stats().finished, true);
});

test('screen-off retry is free only once per attempt; a dead connection still ends as failed', async () => {
  const api = fakeApi({ 'a.jpg': { upload: [() => ({ kind: 'network' }), { kind: 'network' }, { kind: 'network' }] } });
  const q = mkQueue(api);
  q.add([file('a.jpg')]);
  const p = q.run();
  await new Promise(r => setTimeout(r, 0));
  q.noteHidden();
  await p;
  assert.equal(api.calls.filter(c => c[0] === 'upload').length, 3);
  assert.equal(q.items()[0].status, 'failed');
  assert.equal(q.stats().finished, true);
});

// Audit S3: with the PC gone, every remaining file used to burn two attempts (up to 20 s each).
test('PC unreachable: queue stops after the first lost file, the rest can be retried', async () => {
  let pings = 0, pcUp = false;
  const api = fakeApi({ 'a.jpg': { check: [{ kind: 'network' }, { kind: 'network' }] } });
  api.ping = () => { pings++; return Promise.resolve(pcUp ? { kind: 'ok', json: {} } : { kind: 'network' }); };
  const q = mkQueue(api);
  q.add([file('a.jpg'), file('b.jpg'), file('c.jpg')]);
  await q.run();
  assert.deepEqual(Array.from(q.items(), i => i.status), ['failed', 'notsent', 'notsent']);
  assert.equal(q.items()[1].reason, 'Not sent - PC not reachable');
  assert.equal(q.stats().stopped, 'unreachable');
  assert.equal(q.stats().finished, true);
  assert.equal(pings, 1);
  assert.ok(!api.calls.some(c => c[1] === 'b.jpg'), 'no attempts wasted on the remaining files');
  pcUp = true;
  assert.equal(q.retryFailed(), 3);
  await new Promise(r => setTimeout(r, 40));
  assert.deepEqual(Array.from(q.items(), i => i.status), ['saved', 'saved', 'saved']);
  assert.equal(q.stats().stopped, null);
});

test('one file fails but the PC answers: only that file fails, the queue carries on', async () => {
  const api = fakeApi({ 'a.jpg': { upload: [{ kind: 'network' }, { kind: 'network' }] } });
  api.ping = () => Promise.resolve({ kind: 'ok', json: {} });
  const q = mkQueue(api);
  q.add([file('a.jpg'), file('b.jpg')]);
  await q.run();
  assert.deepEqual(Array.from(q.items(), i => i.status), ['failed', 'saved']);
  assert.equal(q.stats().stopped, null);
});

// Audit S14
test('title index points at the next file between items, capped at the total', async () => {
  const q = mkQueue(fakeApi({}));
  q.add([file('a.jpg'), file('b.jpg')]);
  assert.equal(q.stats().index, 1);
  await q.run();
  assert.equal(q.stats().index, 2);
});

test('cancel marks the rest as not sent', async () => {
  const api = fakeApi({});
  const q = mkQueue(api);
  q.add([file('a.jpg'), file('b.jpg'), file('c.jpg')]);
  const p = q.run();
  await new Promise(r => setTimeout(r, 0));
  q.cancel();
  await p;
  const st = Array.from(q.items(), i => i.status);
  assert.ok(st.every(s => s === 'notsent' || s === 'saved'), st.join());
  assert.ok(st.includes('notsent'));
  assert.equal(q.stats().cancelled, true);
  assert.equal(q.stats().finished, true);
});

test('retryFailed re-queues failed and not-sent', async () => {
  const api = fakeApi({ 'a.jpg': { upload: [{ kind: 'http', status: 500, json: {} }] } });
  const q = mkQueue(api);
  q.add([file('a.jpg')]);
  await q.run();
  assert.equal(q.items()[0].status, 'failed');
  assert.equal(q.retryFailed(), 1);
  await new Promise(r => setTimeout(r, 30));
  assert.equal(q.items()[0].status, 'saved');
});

test('speed meter uses a rolling window', () => {
  let t = 0;
  const s = C.createSpeed(() => t);
  s.add(0); t = 1000; s.add(10e6); t = 2000; s.add(20e6);
  assert.equal(Math.round(s.bps()), 10e6);
  t = 10000; s.add(30e6); t = 11000; s.add(40e6);
  assert.equal(Math.round(s.bps()), 10e6);
});
