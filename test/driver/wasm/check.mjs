// THE BROWSER CHECKS BEYOND THE IDENTITY ARM (DEVLOG P9.3, `bb wasm`): what `bb identity` and `bb slice` do not say. Run in headless Chrome:
//
//   1. the module imports NOTHING: no filesystem, no clock, no network (`wasm32-unknown-unknown` links no filesystem import);
//   2. a URL fixture, a one-page document that is mostly picture, is read by `Range` and fetches measurably less than its length;
//   3. the URL source pins its validator: a document that changes under a conversion ends it with `source-changed`; a server that ignores
//      `Range` still works (spooled); a read that fails is `io`; a stream longer than its bound is `input-bytes`;
//   4. `asset.bytes()` and `asset.blob()` agree, `external` HTML in its browser form (object URLs) shows the asset;
//   5. tesseract.js, through plan/fulfil, reads a scanned page; what it recorded replays NATIVELY (the Rust host) to the same bundle;
//   6. the JS heap at the 32 MiB `assets-bytes` default, in `inline` and `external` modes (measured, printed).
//
//   node test/driver/wasm/check.mjs --wasm PATH       prints `  ok ` and `  FAIL ` lines, exits 1 on a FAIL
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { startServer, repo } from './server.mjs';
import { launch, harnessPage } from './browser.mjs';
import { bigImagePdf, pictureBookPdf } from './fixtures.mjs';

const arg = (k, d) => {
  const i = process.argv.indexOf(k);
  return i >= 0 ? process.argv[i + 1] : d;
};
const wasm = path.resolve(arg('--wasm'));
let fails = 0;
const ok = (...x) => console.log('  ok   ' + x.join(''));
const bad = (...x) => {
  fails += 1;
  console.log('  FAIL ' + x.join(''));
};
const check = (c, ...msg) => (c ? ok(...msg) : bad(...msg));
const pct = (a, b) => ((100 * a) / b).toFixed(1) + '%';
const MiB = 1024 * 1024;

const server = await startServer({ wasm });
const browser = await launch();
const page = await harnessPage(browser, server.origin);
const rust = path.join(repo, '.cache', 'emon-rs', 'target', 'release', 'emon-rs');

try {
  // ---------------------------------------------------------------------------------------------------- 1. no imports
  const imports = await page.evaluate(() => window.H.imports());
  check(imports.length === 0, 'the wasm module has no imports at all (so none is a filesystem, a clock or the network): ' + JSON.stringify(imports));

  // ---------------------------------------------------------------------------------------------------- 2. the URL fixture
  const big = bigImagePdf();
  server.add('big.pdf', big, { type: 'application/pdf' });
  const viaArray = await page.evaluate(async () => {
    const b = new Uint8Array(await (await fetch('/gen/big.pdf')).arrayBuffer());
    const e = await window.H.load();
    const m = await e.convert(b.buffer);
    const j = m.json();
    m.dispose();
    return j;
  });
  server.reset();
  const viaUrl = await page.evaluate(async (origin) => {
    const e = await window.H.load();
    const s = await window.H.sdk.urlSource(origin + '/gen/big.pdf');
    const m = await e.convert(s);
    const r = { json: m.json(), stats: m.stats, size: s.size };
    m.dispose();
    return r;
  }, server.origin);
  const sent = server.stats('/gen/big.pdf');
  check(viaUrl.json === viaArray, 'the URL source gives the in-memory Model for a one-page PDF of ' + big.length + ' bytes');
  check(sent.bytes < big.length / 10, 'URL fixture: the server sent ' + sent.bytes + ' of ' + big.length + ' bytes (' + pct(sent.bytes, big.length) + ') in ' + sent.requests + ' range requests (one probe, the first and last chunk, and what the unit then missed): the picture was never read');
  check(viaUrl.stats.bytesRead + 1 <= big.length / 10 && viaUrl.stats.attempts >= 1, 'the SDK counts the same: ' + viaUrl.stats.reads + ' reads, ' + viaUrl.stats.bytesRead + ' bytes, ' + viaUrl.stats.attempts + ' attempt(s), ' + viaUrl.stats.fetchRounds + ' fetch round(s)');
  const lazyBlob = await page.evaluate(async () => {
    const b = new Uint8Array(await (await fetch('/gen/big.pdf')).arrayBuffer());
    const e = await window.H.load();
    const m = await e.convert(new Blob([b]), { chunk: 4096 });
    const r = { json: m.json(), stats: m.stats };
    m.dispose();
    return r;
  });
  check(lazyBlob.json === viaArray && lazyBlob.stats.bytesRead < big.length / 10, 'a Blob read by range, in 4 KiB chunks, gives the same Model and reads ' + lazyBlob.stats.bytesRead + ' of ' + big.length + ' bytes in ' + lazyBlob.stats.reads + ' reads and ' + lazyBlob.stats.fetchRounds + ' fetch rounds');

  // ---------------------------------------------------------------------------------------------------- 3. the source's faults
  const pdfA = fs.readFileSync(path.join(repo, 'test/corpus/synth/pdf/h-plain.pdf'));
  const pdfB = fs.readFileSync(path.join(repo, 'test/corpus/synth/pdf/h-keyword.pdf'));
  server.add('mut.pdf', pdfA, { etag: '"v1"' });
  const pending = page.evaluate(async (origin) => {
    const e = await window.H.load();
    const s = await window.H.sdk.urlSource(origin + '/gen/mut.pdf', {});
    window.__opened = s.validator;
    await new Promise((r) => (window.__resume = r));
    try {
      await e.convert(s, { lazy: true, chunk: 512 });
      return 'converted';
    } catch (err) {
      return err.code + ': ' + err.message;
    }
  }, server.origin);
  await page.waitForFunction('window.__opened !== undefined');
  const pinned = await page.evaluate('window.__opened');
  server.add('mut.pdf', pdfB, { etag: '"v2"' });
  await page.evaluate('window.__resume()');
  const changed = await pending;
  check(pinned === '"v1"' && changed.startsWith('source-changed'), 'the URL source pins its validator (' + pinned + '); the document changed under the conversion and it ended: ' + changed.slice(0, 80));

  server.add('same-etag-shorter.pdf', pdfA, { etag: '"x"' });
  const shorter = await page.evaluate(async (origin) => {
    const e = await window.H.load();
    const s = await window.H.sdk.urlSource(origin + '/gen/same-etag-shorter.pdf');
    // the source says it is longer than it is: a read past the end comes back short
    const liar = { size: s.size + 4096, read: (o, l) => s.read(o, Math.min(l, Math.max(0, s.size - o))) };
    try {
      await e.convert(liar, { lazy: true });
      return 'converted';
    } catch (err) {
      return err.code;
    }
  }, server.origin);
  check(shorter === 'source-changed', 'a source that gives fewer bytes than its length said is `source-changed`: ' + shorter);

  server.add('norange.pdf', pdfA, { noRange: true });
  const noRange = await page.evaluate(async (origin) => {
    const e = await window.H.load();
    const s = await window.H.sdk.urlSource(origin + '/gen/norange.pdf');
    const m = await e.convert(s);
    const j = m.json();
    m.dispose();
    return { j, resident: !!s.bytes };
  }, server.origin);
  const refJson = await page.evaluate(async () => {
    const b = new Uint8Array(await (await fetch('/repo/test/corpus/synth/pdf/h-plain.pdf')).arrayBuffer());
    const e = await window.H.load();
    const m = await e.convert(b.buffer);
    const j = m.json();
    m.dispose();
    return j;
  });
  check(noRange.j === refJson && noRange.resident, 'a server that ignores Range (answers 200) is a spooled source and gives the same Model');

  const faults = await page.evaluate(async () => {
    const e = await window.H.load();
    const b = new Uint8Array(await (await fetch('/repo/test/corpus/synth/pdf/h-plain.pdf')).arrayBuffer());
    const out = {};
    try {
      await e.convert({ size: b.length, read: async () => { throw new Error('the network went away'); } }, { lazy: true });
    } catch (err) {
      out.io = err.code + ': ' + err.message;
    }
    try {
      await e.convert(new ReadableStream({ pull(c) { c.enqueue(new Uint8Array(1000)); } }), { limits: { 'input-bytes': 5000 } });
    } catch (err) {
      out.stream = err.code + ': ' + err.message;
    }
    try {
      await e.convert(b.buffer, { limits: { 'input-bytes': 1000 } });
    } catch (err) {
      out.limit = err.code + ': ' + err.message;
    }
    try {
      await e.convert(b.buffer, { ocr: 'sometimes' });
    } catch (err) {
      out.usage = err.code;
    }
    return out;
  });
  check(faults.io && faults.io.startsWith('io:'), 'a read that rejects is `io`: ' + faults.io);
  check(faults.stream && faults.stream.startsWith('input-bytes:'), 'a stream longer than the input-bytes bound is `input-bytes`, refused while spooling: ' + faults.stream);
  check(faults.limit && faults.limit.startsWith('input-bytes:'), 'the input-bytes limit is checked against the length before a byte is read: ' + faults.limit);
  check(faults.usage === 'usage', 'an option the engine does not know is `usage`');

  // ---------------------------------------------------------------------------------------------------- 4. assets
  const assetCheck = await page.evaluate(async () => {
    const e = await window.H.load();
    const out = [];
    for (const f of ['test/assets/dct-rgb.pdf', 'test/assets/flate-rgb8.pdf', 'test/corpus/synth/pdf/figures-charts.pdf']) {
      const b = new Uint8Array(await (await fetch('/repo/' + f)).arrayBuffer());
      const m = await e.convert(b.buffer, { assets: 'images' });
      const r = { f, assets: [], urls: 0, urlsOk: true };
      for (const a of m.assets) {
        const bytes = a.bytes();
        const blob = a.blob();
        const same = blob.size === bytes.length && blob.type === a.type && (await blob.arrayBuffer()).byteLength === bytes.length && new Uint8Array(await blob.arrayBuffer()).every((x, i) => x === bytes[i]);
        r.assets.push({ name: a.name, type: a.type, size: a.size, bytes: bytes.length, same });
      }
      const w = m.htmlWithBlobURLs({});
      r.urls = w.urls.size;
      for (const [name, u] of w.urls) {
        const got = new Uint8Array(await (await fetch(u)).arrayBuffer());
        const want = m.assets.find((a) => a.name === name).bytes();
        r.urlsOk = r.urlsOk && got.length === want.length && w.html.includes(u);
      }
      r.external = m.html({ assets: 'external', assetPrefix: 'assets/' }).includes('assets/' + (m.assets[0] ? m.assets[0].name : 'none'));
      w.revoke();
      m.dispose();
      out.push(r);
    }
    return out;
  });
  const nAssets = assetCheck.reduce((n, r) => n + r.assets.length, 0);
  check(nAssets > 0 && assetCheck.every((r) => r.assets.every((a) => a.same && a.size === a.bytes)), 'asset.bytes() and asset.blob() agree (bytes, size, media type) over ' + nAssets + ' assets of ' + assetCheck.length + ' documents: ' + assetCheck.map((r) => r.f.split('/').pop() + ' ' + r.assets.map((a) => a.name + ' ' + a.type + ' ' + a.size).join(',')).join('; '));
  check(assetCheck.every((r) => r.urlsOk && r.urls === r.assets.length), '`external` in its browser form: the HTML names each asset by an object URL of its Blob, and the URL gives the asset\'s bytes');

  // ---------------------------------------------------------------------------------------------------- 5. tesseract.js
  const scan = 'test/corpus/synth/scan/h-keyword.pdf';
  const live = await page.evaluate((s) => window.H.run({ path: s, adapter: 'blob', ocr: true, record: true }), scan);
  check(live.exit === 0, 'tesseract.js, through plan/fulfil, converts the scan ' + scan + ' in the browser' + (live.exit === 0 ? '' : ': ' + live.err));
  if (live.exit === 0) {
    const sections = {};
    for (const m of live.out.matchAll(/(?:^|\n)== (json|json-pretty|html|html-full|evidence|intake)\n/g)) sections[m[1]] = m.index + m[0].length;
    const sec = (n, next) => live.out.slice(sections[n], next ? sections[next] - ('\n== ' + next + '\n').length : live.out.length - 1);
    const ev = JSON.parse(sec('evidence', 'intake'));
    const model = JSON.parse(sec('json', 'json-pretty'));
    const gold = JSON.parse(fs.readFileSync(path.join(repo, 'test/corpus/synth/gold/h-keyword.gold.json'), 'utf8'));
    const norm = (s) => (s || '').split(/\s+/).filter(Boolean).join(' ');
    const goldText = (b) => [b.text ? (b.number ? b.number + ' ' + b.text : b.text) : null, ...(b.children || []).flatMap(goldText)].filter((x) => x !== null);
    const lev = (a, b) => {
      let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
      for (let i = 0; i < a.length; i++) {
        const row = [i + 1];
        for (let j = 0; j < b.length; j++) row.push(Math.min(row[j] + 1, prev[j + 1] + 1, prev[j] + (a[i] === b[j] ? 0 : 1)));
        prev = row;
      }
      return prev[b.length];
    };
    const g = norm(goldText(gold.model.body).join(' '));
    const o = norm((ev.owords || []).map((w) => w.text).join(' '));
    const cer = lev(g, o) / g.length;
    check((ev.owords || []).length > 100 && cer < 0.1, 'tesseract.js read ' + (ev.owords || []).length + ' words of the scan: CER ' + cer.toFixed(4) + ' against the gold (' + g.length + ' chars; ceiling 0.10; the native Tesseract reads it at 0.0205: another build, another reading)');
    check(JSON.stringify(model).includes('"kind":"scanned"') && live.out.includes('"ocr"'), 'the Model says the page was scanned and OCR\'d');
    // what the browser recorded replays natively: the same bundle
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'emon-wasm-tj-'));
    for (const [n, b64] of live.transcript) fs.writeFileSync(path.join(dir, n), Buffer.from(b64, 'base64'));
    const edn = fs.readFileSync(path.join(dir, 'transcript.edn'), 'utf8');
    check(edn.includes(':producer') && edn.includes('tesseract.js'), 'the transcript the browser recorded names its engine (tesseract.js)');
    if (fs.existsSync(rust)) {
      const r = spawnSync(rust, ['--out=bundle', `--replay=${dir}`, path.join(repo, scan)], { maxBuffer: 1 << 30 });
      check(r.status === 0 && r.stdout.toString() === live.out, 'the transcript recorded in the browser (tesseract.js, ' + live.transcript.length + ' files) REPLAYS NATIVELY on the Rust host to the browser\'s own bundle, byte for byte' + (r.status === 0 ? '' : ': ' + r.stderr.toString().slice(0, 200)));
    } else {
      console.log('  SKIP the native replay of the browser\'s transcript -- no .cache/emon-rs driver (kin/scripts/emon-rs-build)');
    }
    fs.rmSync(dir, { recursive: true, force: true });
    // and the browser replays what the native host recorded: no engine, no port
    const nat = await page.evaluate((s) => window.H.run({ path: s, adapter: 'array', replayDir: 'test/ocr/scan/h-keyword', replay: ['transcript.edn'] }), scan);
    check(nat.exit === 0 && JSON.stringify(JSON.parse(nat.out.slice(nat.out.indexOf('\n') + 1, nat.out.indexOf('\n== json-pretty')))).includes('"kind":"scanned"'), 'the browser replays the transcript the native Tesseract recorded (test/ocr/scan/h-keyword): the scan is OCR\'d with no engine run');
  }

  // ---------------------------------------------------------------------------------------------------- 6. the heap
  const book = pictureBookPdf();
  server.add('book.pdf', book, { type: 'application/pdf' });
  const heap = {};
  for (const mode of ['inline', 'external']) {
    // each mode in a page of its own: the module's memory never shrinks, and one mode's run must not be the other's baseline
    const p = await harnessPage(browser, server.origin);
    heap[mode] = await p.evaluate((m) => window.H.heap({ path: 'gen/book.pdf', mode: m }), mode);
    await p.close();
  }
  for (const mode of ['inline', 'external']) {
    const h = heap[mode];
    console.log(`  HEAP ${mode}: ${JSON.stringify(h)}`);
  }
  check(heap.inline.assets >= 9 && heap.inline.assetMiB > 28 && heap.inline.assetMiB <= 32, 'the document fills the 32 MiB assets-bytes default: ' + heap.inline.assets + ' assets, ' + heap.inline.assetMiB + ' MiB of them (the document is ' + (book.length / MiB).toFixed(1) + ' MiB)');
  check(true, 'PEAK JS HEAP above the baseline (' + heap.inline.baseMiB + ' MiB) at the 32 MiB assets-bytes default, sampled after the conversion and each output: inline ' + heap.inline.peakAboveBaseMiB + ' MiB (the HTML with its data: URIs is ' + heap.inline.htmlMiB + ' MiB of string), external ' + heap.external.peakAboveBaseMiB + ' MiB (the HTML is ' + heap.external.htmlMiB + ' MiB; the assets are ' + heap.external.assets + ' Blobs made from the module\'s memory, none in the heap), or ' + heap.external.withBytesCopiesPeakAboveBaseMiB + ' MiB if every asset is also copied out with bytes(); the module\'s memory, where the assets live, is ' + heap.inline.wasmMiB + ' and ' + heap.external.wasmMiB + ' MiB');
  check(heap.external.peakAboveBaseMiB < heap.inline.peakAboveBaseMiB, 'external mode keeps the heap lower than inline: ' + heap.external.peakAboveBaseMiB + ' < ' + heap.inline.peakAboveBaseMiB + ' MiB');
} finally {
  await browser.close();
  await server.close();
}
if (page.errors && page.errors.length) console.log('  note page errors: ' + page.errors.slice(0, 3).join(' | '));
if (fails) {
  console.log('  FAIL ' + fails + ' check(s) failed');
  process.exit(1);
}
ok('the browser checks: the module imports nothing, a URL fixture is read by range, the source faults are named, assets as bytes and Blobs, tesseract.js through plan/fulfil and its transcript replayed natively, the JS heap at the 32 MiB assets-bytes default measured');
process.exit(0);
