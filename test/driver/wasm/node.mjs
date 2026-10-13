// THE NODE DRIVER OF THE PDFIUM-WASM GATE (DEVLOG P11.4c, `bb pdfium-wasm`): the browser's renderer without a browser. The JS SDK (`host/js/src/index.js`),
// the pdfium adapter (`host/js/src/pdfium.js`), emon's WebAssembly module and pdfium's WebAssembly build run in Node, which is all they need (neither
// names a DOM; the SDK reads bytes and the adapter calls into WebAssembly), and the gate reads the answers as it reads a native driver's.
//
//   node [--experimental-wasm-exnref] test/driver/wasm/node.mjs convert --wasm emon.wasm --pdfium DIR --pdf PATH --out DIR
//                                  [--assets figures] [--ocr never] [--native DIR] [--native-json FILE] [--native-omit FILE] [--native-assets DIR]
//   node test/driver/wasm/node.mjs cases --pdfium DIR --native DIR --pdf PATH [--pdf PATH ...] [--out FILE]
//
//   convert   one conversion of PATH with the live pdfium-wasm as `ports.render`, RECORDING: DIR gets `transcript/` (the files a native host reads),
//             `model.json`, `html-inline.html`, `html-external.html` (prefix img/), `html-omit.html`, `assets/NAME`, `renders.json` (every page the renderer
//             made: its request, size and the SHA-256 of its RGB) and `stats.json`. With `--native DIR` (a transcript the native pdfium recorded for the same
//             document) it also compares: the requests and answers entry by entry, the pixels of every bitmap, and with `--native-json`, `--native-omit` and
//             `--native-assets` the Model (asset hashes and sizes masked), the HTML with assets omitted and the asset files.
//   cases     the requests of a native render transcript (layers, crops, failures) made of the live renderer: its answers and pixels compared to the transcript's.
//
// The comparison is D248's: two pdfium builds differ by at most 3 of 255 per channel on a few percent of the pixels (text anti-aliasing); the same build
// on another engine is held to the same bound, and what it measures (the largest difference, the share of pixels that differ at all) is printed.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Emon } from '../../../host/js/src/index.js';
import { pdfiumRenderer } from '../../../host/js/src/pdfium.js';
import { decodePng } from './png.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const mode = args.shift();
const opt = (k, d) => {
  const i = args.indexOf(k);
  return i >= 0 ? args[i + 1] : d;
};
const optAll = (k) => args.flatMap((a, i) => (a === k ? [args[i + 1]] : []));

const sha = (u8) => crypto.createHash('sha256').update(u8).digest('hex');

// pdfium's wasm build, as `ports.render` wants it: the factory `bb pdfium-fetch` wrapped (lib/pdfium.mjs) and the .wasm's bytes
async function pdfiumPort(dir) {
  const mod = await import(pathToFileURL(path.join(dir, 'lib', 'pdfium.mjs')).href);
  const req = createRequire(import.meta.url);
  const factory = (arg) => mod.default(arg, { require: req, dirname: path.join(dir, 'lib') });
  return pdfiumRenderer({ module: factory, wasm: fs.readFileSync(path.join(dir, 'lib', 'pdfium.wasm')) });
}

// ------------------------------------------------------------------------------------------------ transcripts and the comparison

/** A native transcript: its entries `{ round, kind, req, ans, bitmap }` (bitmap decoded when the entry has one). */
function readTranscript(dir) {
  const text = fs.readFileSync(path.join(dir, 'transcript.edn'), 'utf8');
  const entries = [];
  const re = /\{:round (\d+) :kind :([\w-]+) :req \[([^\]]*)\] :ans \[([^\]]*)\](?: :bitmap "([^"]*)")?\}/g;
  for (const m of text.matchAll(re)) {
    const ints = (s) => s.trim().split(/\s+/).map(Number);
    entries.push({ round: Number(m[1]), kind: m[2], req: ints(m[3]), ans: ints(m[4]), bitmap: m[5] ? decodePng(fs.readFileSync(path.join(dir, m[5]))) : null });
  }
  return entries;
}

/** The pixels of two same-sized RGB bitmaps: the largest per-channel difference and how many pixels differ at all. */
function diffPixels(a, b) {
  let max = 0;
  let differing = 0;
  const n = a.width * a.height;
  for (let i = 0; i < n; i++) {
    let d = 0;
    for (let c = 0; c < 3; c++) {
      const x = Math.abs(a.data[i * a.channels + c] - b.data[i * b.channels + c]);
      if (x > d) d = x;
    }
    if (d > 0) differing++;
    if (d > max) max = d;
  }
  return { max, differing, total: n };
}

function newReport() {
  return { entries: 0, problems: [], bitmaps: 0, max: 0, differing: 0, total: 0, sizes: [] };
}

function addBitmap(rep, label, a, b) {
  if (!a || !b) {
    rep.problems.push(`${label}: ${a ? 'the live run has no bitmap' : 'the native transcript has no bitmap'}`);
    return;
  }
  if (a.width !== b.width || a.height !== b.height || a.channels !== b.channels) {
    rep.problems.push(`${label}: ${a.width}x${a.height}x${a.channels} natively, ${b.width}x${b.height}x${b.channels} live`);
    return;
  }
  const d = diffPixels(a, b);
  rep.bitmaps++;
  rep.max = Math.max(rep.max, d.max);
  rep.differing += d.differing;
  rep.total += d.total;
  rep.sizes.push([a.width, a.height, d.max, d.differing]);
}

function compareEntries(native, live) {
  const rep = newReport();
  rep.entries = native.length;
  if (native.length !== live.length) rep.problems.push(`${native.length} entries natively, ${live.length} live`);
  for (let i = 0; i < Math.min(native.length, live.length); i++) {
    const [n, l] = [native[i], live[i]];
    if (n.kind !== l.kind || n.req.join() !== l.req.join()) rep.problems.push(`entry ${i}: the request is [${n.req}] natively and [${l.req}] live`);
    else if (n.ans.join() !== l.ans.join()) rep.problems.push(`entry ${i}: the answer is [${n.ans}] natively and [${l.ans}] live`);
    else if (n.bitmap || l.bitmap) addBitmap(rep, `entry ${i} (request ${n.req[2]})`, n.bitmap, l.bitmap);
  }
  return rep;
}

// ------------------------------------------------------------------------------------------------ convert

async function convert() {
  const out = path.resolve(opt('--out'));
  const pdf = fs.readFileSync(opt('--pdf'));
  fs.rmSync(out, { recursive: true, force: true });
  fs.mkdirSync(path.join(out, 'assets'), { recursive: true });
  const emon = await Emon.load(fs.readFileSync(opt('--wasm')));
  const real = await pdfiumPort(opt('--pdfium'));
  // the renderer, watched: every page it makes is named with the SHA-256 of its RGB
  const renders = [];
  let opened = 0;
  const port = {
    layers: real.layers,
    producer: real.producer,
    async open(doc, limits) {
      opened++;
      const d = await real.open(doc, limits);
      return {
        async render(r, sink) {
          const x = await d.render(r, sink);
          renders.push(x.status ? { ...r, status: x.status, reason: x.reason } : { ...r, width: x.width, height: x.height, sha256: sha(x.rgb) });
          return x;
        },
        close: () => d.close(),
      };
    },
    terminate: () => real.terminate(),
  };
  const t0 = performance.now();
  const model = await emon.convert(new Uint8Array(pdf), { assets: opt('--assets', 'figures'), ocr: opt('--ocr', 'never'), record: true, ports: { render: port } });
  const ms = Math.round(performance.now() - t0);
  const w = (f, s) => fs.writeFileSync(path.join(out, f), s);
  w('model.json', model.json());
  w('html-inline.html', model.html({ assets: 'inline' }));
  w('html-external.html', model.html({ assets: 'external', assetPrefix: 'img/' }));
  w('html-omit.html', model.html({ assets: 'omit' }));
  for (const a of model.assets) w(path.join('assets', a.name), a.bytes());
  fs.mkdirSync(path.join(out, 'transcript'));
  for (const [n, d] of model.transcript()) fs.writeFileSync(path.join(out, 'transcript', n), d);
  w('renders.json', JSON.stringify(renders, null, 1) + '\n');
  w('stats.json', JSON.stringify({ ...model.stats, opened, ms }) + '\n');
  const result = { mode: 'convert', pdf: opt('--pdf'), assets: model.assets.length, renders: renders.length, opened, ms, notes: model.stats.notes };
  // against what the native pdfium recorded
  const nat = opt('--native');
  if (nat) {
    const rep = compareEntries(readTranscript(nat), readTranscript(path.join(out, 'transcript')));
    const nj = opt('--native-json');
    if (nj) {
      // the Model, with the assets' hashes and sizes masked: they are the encoded bytes of the pixels
      const mask = (m) => {
        for (const a of m.assets || []) {
          delete a.sha256;
          delete a.bytes;
        }
        return JSON.stringify(m);
      };
      const [a, b] = [mask(JSON.parse(fs.readFileSync(nj, 'utf8'))), mask(JSON.parse(model.json()))];
      rep.modelEqual = a === b;
      if (a !== b) rep.problems.push('the Model differs from the native one (asset hashes and sizes masked)');
    }
    const no = opt('--native-omit');
    if (no) {
      rep.omitEqual = fs.readFileSync(no, 'utf8') === model.html({ assets: 'omit' });
      if (!rep.omitEqual) rep.problems.push('the HTML with assets omitted differs from the native one');
    }
    const na = opt('--native-assets');
    if (na) {
      const names = fs.readdirSync(na).sort();
      const live = model.assets.map((a) => a.name).sort();
      if (names.join() !== live.join()) rep.problems.push(`the assets are ${names} natively and ${live} live`);
      rep.assets = { files: 0, max: 0, differing: 0, total: 0, identical: 0 };
      for (const n of names) {
        if (!live.includes(n)) continue;
        const nb = fs.readFileSync(path.join(na, n));
        const lb = fs.readFileSync(path.join(out, 'assets', n));
        rep.assets.files++;
        if (Buffer.compare(nb, lb) === 0) rep.assets.identical++;
        const tmp = newReport();
        addBitmap(tmp, n, decodePng(nb), decodePng(lb));
        rep.assets.max = Math.max(rep.assets.max, tmp.max);
        rep.assets.differing += tmp.differing;
        rep.assets.total += tmp.total;
        rep.problems.push(...tmp.problems);
      }
    }
    result.compare = rep;
  }
  model.dispose();
  await port.terminate();
  console.log(JSON.stringify(result));
}

// ------------------------------------------------------------------------------------------------ cases

// the crop law of SPEC 7.2 (`render::cut`): a rectangle of the whole page's pixels, which must lie inside it
function cut(b, x, y, w, h) {
  if (w < 1 || h < 1 || x < 0 || y < 0 || x + w > b.width || y + h > b.height) return null;
  const data = new Uint8Array(w * h * 3);
  for (let r = 0; r < h; r++) data.set(b.data.subarray(((y + r) * b.width + x) * 3, ((y + r) * b.width + x + w) * 3), r * w * 3);
  return { width: w, height: h, channels: 3, data };
}

async function cases() {
  const native = readTranscript(opt('--native'));
  const pdfs = optAll('--pdf').map((p) => fs.readFileSync(p));
  const real = await pdfiumPort(opt('--pdfium'));
  // the `pixels` limit's default (kin/limits.kin, 268435456), as a native driver's renderer reads it: a test driver may say the number, the library may not
  const docs = new Map();
  let last = null;
  const live = [];
  const hashes = [];
  for (const e of native) {
    const [, kind, id, input, page, dpi, layer, cx, cy, cw, ch] = e.req;
    if (!docs.has(input)) {
      const bytes = new Uint8Array(pdfs[input]);
      docs.set(input, await real.open({ size: bytes.length, bytes, read: async (o, l) => bytes.subarray(o, o + l) }, { pixels: 268435456 }));
    }
    const doc = docs.get(input);
    const key = `${input}/${page}/${dpi}/${layer}`;
    let whole;
    if (last && last.key === key) whole = last.whole;
    else {
      const r = await doc.render({ page, dpi, layer });
      whole = r.status ? r : { width: r.width, height: r.height, channels: 3, data: new Uint8Array(r.rgb) };
      last = { key, whole };
    }
    let got = whole;
    if (!whole.status && e.kind === 'crop') got = cut(whole, cx, cy, cw, ch) || { status: 'failed', reason: 'the crop is not inside the page' };
    const ans = got.status ? [4, e.req[1], id, got.status === 'unsupported' ? 2 : 1] : [8, e.req[1], id, 0, got.width, got.height, 3, dpi];
    live.push({ round: e.round, kind: e.kind, req: e.req, ans, bitmap: got.status ? null : got });
    hashes.push({ id, kind: e.kind, status: got.status || 'ok', sha256: got.status ? null : sha(got.data) });
  }
  for (const d of docs.values()) d.close();
  // compared document by document: whether the pixels are held to the bound depends on the document's fonts (the gate's FIXTURES)
  const groups = {};
  for (const input of docs.keys()) {
    const pick = (list) => list.filter((e) => e.req[3] === input);
    groups[input] = compareEntries(pick(native), pick(live));
  }
  const result = { mode: 'cases', compare: compareEntries(native, live), groups, hashes };
  if (opt('--out')) fs.writeFileSync(opt('--out'), JSON.stringify(result, null, 1) + '\n');
  console.log(JSON.stringify(result));
}

try {
  if (mode === 'convert') await convert();
  else if (mode === 'cases') await cases();
  else throw new Error(`usage: node.mjs convert|cases ... (${mode})`);
} catch (e) {
  console.error(e && e.stack ? e.stack : String(e));
  process.exit(1);
}
void here;
process.exit(0);
