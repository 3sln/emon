// The JS engine registry's laws (DEVLOG D925), in Node over the built module: registration is held to kin's engine table and refused by name, a request goes to the adapter
// registered for the engine it names (kin's `ad-route`), an engine nobody registered answers unsupported, the producer combines. The rules are the module's; this holds that the
// host asks them. Not the browser: `node --experimental-wasm-exnref test/driver/wasm/engines.mjs --wasm PATH`.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { Emon } from '../../../host/js/src/index.js';
import { tesseractAdapter } from '../../../host/js/src/tesseract.js';

const i = process.argv.indexOf('--wasm');
const emon = await Emon.load(fs.readFileSync(path.resolve(process.argv[i + 1])));
const w = emon.w;
const request = (engine) => ({ id: 3, engine, bitmap: () => new Uint8Array(0) });
const refused = (p) => p.then(() => 'accepted', (e) => e.message);
let n = 0;
const ok = (what) => console.log('  ok   ' + what) || n++;

// registration is kin's rule
let e = emon.engines();
assert.match(await refused(e.add({ name: 'nobody', engine: 99, read: async () => null })), /^adapter-engine-unknown: nobody/);
assert.match(await refused(e.add({ name: 't', engine: 1, caps: -1, read: async () => null })), /^adapter-caps-beyond-engine: t/);
assert.match(await refused(e.add({ name: 't', engine: 1, flags: 1, read: async () => null })), /^adapter-abi-unsupported: t is async/);
assert.match(await refused(e.add({ name: 't', engine: 1, flags: 2, read: async () => null })), /^adapter-remote-refused: t/);
assert.match(await refused(e.add({ name: 't', engine: 1 })), /^adapter-abi-unsupported: t has no `read`/);
let closed = 0;
assert.match(await refused(e.add({ name: 'bad', engine: 99, read: async () => null, close: () => { closed++; } })), /adapter-engine-unknown/);
assert.equal(closed, 1);
assert.deepEqual(e.describe(), []);
ok('registration is held to kin\'s table, each refusal by name, and a refused adapter is closed');

// allowRemote first
e.allowRemote(true);
await e.add({ name: 'remote', engine: 1, flags: 2, read: async () => null });
assert.equal(e.describe().length, 1);
await e.close();
ok('a remote adapter registers after allowRemote(true)');

// routing by the engine a request names; nobody registered: unsupported
e = emon.engines();
const port0 = e.port();
assert.equal(await port0(request(undefined)), null);
const calls = [];
await e.add({ name: 'tesseract', engine: 1, needs: 1, producer: { engine: 'tesseract.js', host: 'a' }, read: async (r) => { calls.push('t'); return { words: [] }; } });
const port = e.port();
assert.deepEqual(await port(request(undefined)), { words: [] }, 'a request with no trailer is Tesseract\'s');
assert.deepEqual(await port(request(1)), { words: [] });
assert.equal(await port(request(2)), null, 'nobody registered for PP-OCR');
assert.deepEqual(calls, ['t', 't']);
await e.add({ name: 'ppocr', engine: 2, needs: 13, producer: { 'ocr-model': 'm', host: 'b' }, read: async () => { calls.push('p'); return null; } });
assert.equal(await port(request(2)), null);
assert.deepEqual(calls, ['t', 't', 'p']);
ok('a request goes to the adapter registered for its engine; an engine nobody registered is unsupported');

// the producer: the first adapter's pairs, the combined name
assert.deepEqual(e.producer(), { engine: 'tesseract.js', host: 'a', 'ocr-model': 'm', 'ocr-engine': 'tesseract+ppocr' });
assert.equal(port.producer['ocr-engine'], 'tesseract+ppocr');
ok('the producer combines the adapters');

// a rejection is noted and propagates (the loop answers failed)
await e.add({ name: 'mlkit', engine: 4, read: async () => { throw new Error('boom'); } });
await assert.rejects(() => port(request(4)), /boom/);
assert.ok(e.notes().some((l) => l.startsWith('mlkit: failed boom')));
ok('a rejection is noted');

// tesseract.js's adapter is an adapter of the Tesseract role
const t = tesseractAdapter({ createWorker: async () => { throw new Error('not here'); } });
assert.equal(t.engine, 1);
assert.equal(t.name, 'tesseract');
const e2 = emon.engines();
await e2.add(t);
assert.equal(e2.describe()[0].name, 'tesseract');
ok('tesseractAdapter registers as the Tesseract role');

// THE PACKS (P13.16, D896): the catalogue, the hook, verification, events and the app's download are the JS host's; what to do about a missing pack, `engines()` and `prepare()` are kin's.
{
  const cat = emon.engines().packs.catalogue;
  const pack = cat.packs.find((p) => p.id === 'tess-fast-eng');
  assert.ok(pack && pack.files.length === 1 && /^[0-9a-f]{64}$/.test(pack.files[0].sha), 'the catalogue holds tess-fast-eng with its SHA-256');
  const file = pack.files[0];
  const modelPath = path.resolve(process.env.EMON_TESS_ENG ?? '.cache/native/dl/eng.traineddata');
  const real = fs.existsSync(modelPath) ? new Uint8Array(fs.readFileSync(modelPath)) : null;
  assert.ok(real, `the English model is not at ${modelPath} (EMON_TESS_ENG)`);
  const tampered = real.slice();
  tampered[1000] ^= 1;
  const sawFiles = [];
  const packAdapter = (name = 'tesseract') => ({ name, engine: 1, needs: 1, packs: true, read: async (r, ctx) => { sawFiles.push([name, [...ctx.packs.keys()]]); return { words: [] }; }, close: () => {} });
  const fresh = async (...adapters) => {
    const g = emon.engines();
    for (const a of adapters) await g.add(a);
    return g;
  };
  delete process.env.EMON_OFFLINE;

  // kin's platform and ids reach the host
  assert.equal(cat.class, 'web');
  ok('the catalogue is the module\'s: class web, tess-fast-eng with its SHA-256');

  // mode never (the default): nothing is asked, nothing is fetched; the page is unread, never blank
  let g = await fresh(packAdapter());
  let asked = 0;
  g.packs.onPackNeeded(() => { asked++; return { action: 'provide', data: real }; });
  assert.equal(g.packs.mode, 'never');
  assert.equal(await g.port()(request(1)), null);
  assert.equal(asked, 0, 'never wins over the hook');
  assert.ok(g.notes().some((l) => l.includes('pack-missing:tess-fast-eng')));
  ok('mode never: the hook is not asked and the page is unread with pack-missing');

  // a hook that provides: verified, kept, handed to the adapter
  g = await fresh(packAdapter());
  g.packs.setMode('cache');
  g.packs.onPackNeeded((need) => {
    assert.equal(need.pack, 'tess-fast-eng');
    assert.equal(need.engine, 'tesseract');
    assert.equal(need.bytes, file.bytes);
    assert.equal(need.reason, 'conversion');
    return { action: 'provide', data: real };
  });
  assert.deepEqual(await g.port()(request(1)), { words: [] });
  assert.deepEqual(sawFiles.at(-1), ['tesseract', [file.name]]);
  assert.deepEqual(g.packs.installed(), [pack.code]);
  asked = 0;
  g.packs.onPackNeeded(() => { asked++; });
  await g.port()(request(1));
  assert.equal(asked, 0, 'a pack that is here is not asked for again');
  ok('a provided pack is verified, kept and handed to the adapter; present is used without asking');

  // tampered bytes are never used or kept
  g = await fresh(packAdapter());
  g.packs.setMode('cache');
  g.packs.onPackNeeded(() => ({ action: 'provide', data: tampered }));
  const reads = sawFiles.length;
  assert.equal(await g.port()(request(1)), null);
  assert.equal(sawFiles.length, reads);
  assert.deepEqual(g.packs.installed(), []);
  assert.ok(g.notes().some((l) => l.includes('pack-hash-mismatch:tess-fast-eng')));
  await assert.rejects(() => g.packs.install('tess-fast-eng', real.slice(1)), /pack-hash-mismatch/);
  ok('tampered bytes are refused, nothing is kept, the adapter never reads');

  // decline: unread; EMON_OFFLINE=1 wins over a hook that would provide
  g = await fresh(packAdapter());
  g.packs.setMode('auto');
  g.packs.onPackNeeded(() => ({ action: 'decline' }));
  assert.equal(await g.port()(request(1)), null);
  assert.ok(g.notes().some((l) => l.includes('pack-missing:tess-fast-eng')));
  asked = 0;
  g.packs.onPackNeeded(() => { asked++; return { action: 'provide', data: real }; });
  process.env.EMON_OFFLINE = '1';
  assert.equal(await g.port()(request(1)), null);
  assert.equal(asked, 0, 'offline wins over the hook');
  await assert.rejects(() => g.packs.ensure(['tess-fast-eng']), /EMON_OFFLINE/);
  delete process.env.EMON_OFFLINE;
  ok('decline is unread with pack-missing; EMON_OFFLINE=1 wins over a hook');

  // fetch with no fetcher: the library has no HTTP client
  g = await fresh(packAdapter());
  g.packs.setMode('auto');
  g.packs.onPackNeeded(() => ({ action: 'fetch' }));
  assert.equal(await g.port()(request(1)), null);
  assert.ok(g.notes().some((l) => l.includes('pack-fetch-unavailable:tess-fast-eng')));
  ok('a fetch with no fetcher is pack-fetch-unavailable');

  // the app's download in auto with no hook; one progress stream; cancel
  g = await fresh(packAdapter());
  g.packs.setMode('auto');
  const events = [];
  g.packs.onPackEvent((ev) => { events.push(ev); });
  g.packs.setFetcher(async (info, f, progress) => {
    assert.equal(info.id, 'tess-fast-eng');
    progress(f.bytes >> 1);
    progress(f.bytes);
    return real;
  });
  assert.deepEqual(await g.port()(request(1)), { words: [] });
  assert.deepEqual(events.map((x) => x.kind), ['start', 'progress', 'progress', 'done']);
  assert.ok(events.every((x) => x.pack === 'tess-fast-eng' && x.initiator === 'library' && x.total === pack.bytes));
  assert.equal(events.at(-1).done, pack.bytes);
  ok('auto with no hook fetches through the app\'s fetcher; start, progress and done on one stream');

  g = await fresh(packAdapter());
  g.packs.setMode('auto');
  const ev2 = [];
  g.packs.onPackEvent((ev) => { ev2.push(ev.kind); return ev.kind === 'progress'; });
  g.packs.setFetcher(async (info, f, progress) => { progress(1); return real; });
  await assert.rejects(() => g.packs.ensure(['tess-fast-eng']), /pack-fetch-cancelled:tess-fast-eng/);
  assert.deepEqual(ev2, ['start', 'progress', 'cancelled']);
  assert.deepEqual(g.packs.installed(), []);
  g.packs.onPackEvent(() => {});
  g.packs.setFetcher(async () => tampered);
  await assert.rejects(() => g.packs.ensure(['tess-fast-eng']), /pack-hash-mismatch/);
  g.packs.setFetcher(async () => real);
  const seen = [];
  g.packs.onPackEvent((ev) => { seen.push([ev.kind, ev.initiator]); });
  await g.packs.ensure(['tess-fast-eng']);
  assert.deepEqual(seen.at(-1), ['done', 'app']);
  assert.deepEqual(g.packs.installed(), [pack.code]);
  ok('a listener cancels a fetch; a download that does not verify is not kept; ensure() reports as the app');

  // substitute: another adapter of the same engine, else unread
  const who = [];
  const second = { ...packAdapter('tess-alt'), read: async () => { who.push('alt'); return { words: [] }; } };
  g = await fresh(packAdapter(), second);
  g.packs.setMode('cache');
  g.packs.onPackNeeded(() => ({ action: 'substitute', engine: 'tess-alt' }));
  assert.deepEqual(await g.port()(request(1)), { words: [] });
  assert.deepEqual(who, ['alt']);
  g.packs.onPackNeeded(() => ({ action: 'substitute', engine: 'ppocr' }));
  await g.add({ name: 'ppocr', engine: 2, needs: 1, packs: false, read: async () => null });
  assert.equal(await g.port()(request(1)), null);
  assert.ok(g.notes().some((l) => l.includes('pack-substitute-differs:ppocr')));
  ok('a substitute is another adapter of the same engine; any other engine leaves the page unread');

  // engines() and prepare() are kin's, in the C ABI's shapes
  g = await fresh(packAdapter());
  g.packs.setMode('auto');
  let st = g.state();
  assert.equal(st.platform, 'web');
  assert.equal(st.mode, 'auto');
  assert.equal(st.offline, false);
  const tess = st.engines.find((x) => x.name === 'tesseract');
  assert.equal(tess.registered, true);
  assert.equal(tess.state, 'downloadable');
  assert.ok(tess.download_bytes > 0 && tess.needs.some((n) => n.id === 'tess-fast-eng' && n.installed === false));
  const ppo = st.engines.find((x) => x.name === 'ppocr-web');
  assert.equal(ppo.registered, false);
  assert.equal(ppo.reason, 'no adapter is registered for it');
  await g.packs.install('tess-fast-eng', real);
  st = g.state();
  const tess2 = st.engines.find((x) => x.name === 'tesseract');
  assert.equal(tess2.state, 'installed');
  assert.equal(tess2.download_bytes, 0);
  g.packs.setMode('never');
  process.env.EMON_OFFLINE = '1';
  assert.equal(g.state().offline, true);
  delete process.env.EMON_OFFLINE;
  ok('engines() states: registered, downloadable, installed, with sizes and reasons');

  g = await fresh(packAdapter());
  g.packs.setMode('auto');
  let pr = g.prepare({ ocr: 'auto' });
  assert.equal(pr.verdict, 'downloadable');
  assert.deepEqual(pr.languages, ['eng']);
  assert.ok(pr.missing >= 1 && pr.missing_bytes > 0 && pr.packs.some((x) => x.id === 'tess-fast-eng' && x.state === 'missing'));
  await g.packs.install('tess-fast-eng', real);
  pr = g.prepare({ ocr: 'auto' });
  assert.ok(pr.packs.some((x) => x.id === 'tess-fast-eng' && x.state === 'installed'));
  g.packs.setMode('never');
  assert.equal(g.prepare({ ocr: 'never' }).verdict, 'ready');
  assert.equal(g.prepare({ ocr: 'never' }).engines.length, 0);
  ok('prepare(): the verdict and the packs, as kin resolves them');
}


// ONE LIVE CONVERT through the registry: tesseract.js (Node) reads a scan, the registry routes, and the adapter reads with its own `langPath` (an adapter without `packs: true` brings its
// own models, as `tesseractAdapter` always has). Skipped, loudly, where tesseract.js or the sample is not installed.
{
  let T = null;
  try { T = createRequire(path.resolve('host/js/package.json'))('tesseract.js'); } catch { /* not installed */ }
  const sample = path.resolve('samples/image-statement.png');
  const lang = path.resolve('host/js/node_modules/@tesseract.js-data/eng/4.0.0_best_int');
  if (!T?.createWorker || !fs.existsSync(sample) || !fs.existsSync(lang)) {
    console.log('  SKIP live convert -- tesseract.js, its English data or samples/image-statement.png is not here');
  } else {
    const live = emon.engines();
    const adapter = tesseractAdapter({ createWorker: T.createWorker, languages: ['eng'], workerOptions: { langPath: lang, gzip: true, cacheMethod: 'none' } });
    await live.add(adapter);
    const states = live.state().engines.find((x) => x.name === 'tesseract');
    assert.equal(states.registered, true);
    const model = await emon.convert(new Uint8Array(fs.readFileSync(sample)), { ocr: 'always', ports: { ocr: live.port() } });
    const html = await model.html();
    assert.ok(html.length > 500 && /statement|account|balance/i.test(html), 'the scan was read through the registry');
    assert.deepEqual(live.notes(), []);
    await live.close();
    ok('a live convert through the registry (tesseract.js reads the scan)');
  }
}

await e.close();
await e2.close();
console.log(`engines: ${n} groups passed`);
