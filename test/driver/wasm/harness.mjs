// THE BROWSER HARNESS (DEVLOG P9.3): runs in a headless Chrome page and is driven by `test/driver/wasm/run.mjs`. It is the browser's
// twin of the native drivers (`test/driver/rust/main.rs`): one job is one conversion of one input through ONE byte-source adapter,
// and its answer is what the driver prints (the `--out=bundle` sections, or the error and its exit code), so `bb identity` and
// `bb slice` can hold the browser to the Rust host byte for byte.
import { Emon, bytesSource, blobSource, urlSource, streamSource, EmonError } from '/sdk/index.js';

let emon = null;

const hex = (b) => {
  const t = '0123456789abcdef';
  let s = '';
  for (let i = 0; i < b.length; i++) s += t[b[i] >> 4] + t[b[i] & 15];
  return s;
};

const b64 = (u8) => {
  let s = '';
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(s);
};

const fromB64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

// a path is a file of the repository, or `gen/NAME` (a document the check registered with the server)
const urlFor = (path) => (path.startsWith('gen/') ? '/' + path : '/repo/' + path);

const get = async (path) => {
  const r = await fetch(urlFor(path), { cache: 'no-store' });
  if (!r.ok) throw new Error(`${path}: HTTP ${r.status}`);
  return new Uint8Array(await r.arrayBuffer());
};

/** The adapters of SPEC 13.1: the same bytes, handed over in each of the ways a page has them. */
export const ADAPTERS = ['array', 'u8', 'blob', 'file', 'url', 'stream', 'custom', 'lazy', 'async512'];

async function makeInput(adapter, path, bytes) {
  switch (adapter) {
    case 'array':
      return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
    case 'u8': {
      // a view into the middle of a larger buffer: the offset must be honoured
      const big = new Uint8Array(bytes.length + 100);
      big.set(bytes, 37);
      return big.subarray(37, 37 + bytes.length);
    }
    case 'blob':
      return new Blob([bytes], { type: 'application/octet-stream' });
    case 'file':
      return new File([bytes], path.split('/').pop(), { type: 'application/pdf' });
    case 'url':
      return await urlSource(location.origin + urlFor(path));
    case 'stream': {
      let at = 0;
      return new ReadableStream({
        pull(c) {
          if (at >= bytes.length) return c.close();
          c.enqueue(bytes.slice(at, at + 1000));
          at += 1000;
        },
      });
    }
    case 'custom':
    case 'async512':
      // any { size, read }: every read is a promise that resolves on a later task, as a network read does
      return {
        size: bytes.length,
        read: (o, l) => new Promise((res) => {
          const ch = new MessageChannel();
          ch.port1.onmessage = () => {
            ch.port1.close();
            res(bytes.slice(o, o + l));
          };
          ch.port2.postMessage(0);
        }),
      };
    case 'lazy':
      return bytesSource(bytes, { lazy: true });
    default:
      throw new Error('no adapter ' + adapter);
  }
}

const fail = (e) => {
  if (e instanceof EmonError) return { exit: e.code === 'internal' ? 1 : 4, out: '', err: `error: ${e.code}\n${e.message}\n` };
  return { exit: 1, out: '', err: `error: ${e && e.stack ? e.stack : e}\n` };
};

// tesseract.js as the page's `ocr` port: its worker, WebAssembly core and language data come from the harness's server (nothing from a CDN)
let tess = null;
async function ocrPort() {
  if (!tess) {
    const T = (await import('/tess/tesseract.esm.min.js')).default;
    const { tesseractPort } = await import('/sdk/tesseract.js');
    tess = tesseractPort({
      createWorker: T.createWorker,
      languages: ['eng'],
      workerOptions: { workerPath: location.origin + '/tess/worker.min.js', corePath: location.origin + '/tess/core', langPath: location.origin + '/tess/lang', cacheMethod: 'none', gzip: true, workerBlobURL: false },
    });
  }
  return tess;
}

/**
 * One job: `{ path, adapter, password, assets, chunk, replay: [names], replayDir, record, ocr }`. `ocr` gives the conversion tesseract.js as
 * its OCR port. Answers `{ exit, out, err, transcript }`:
 * the bundle of `--out=bundle` (`== json`, `== json-pretty`, `== html`, `== html-full`, `== evidence`, `== intake`, and with assets on
 * `== html-external`, `== html-omit`, `== assets`), exactly as the Rust driver prints it, or the driver's error and exit code.
 */
export async function run(job) {
  if (!emon) emon = await Emon.load('/emon.wasm');
  const bytes = await get(job.path);
  const adapter = job.adapter || 'array';
  const opts = { keepEvidence: true, chunk: job.chunk ?? (adapter === 'async512' || adapter === 'lazy' ? 512 : undefined) };
  if (job.assets && job.assets !== 'none') opts.assets = job.assets;
  if (job.record) opts.record = true;
  if (job.ocr) {
    const p = await ocrPort();
    opts.ports = { ocr: p };
    opts.producer = p.producer;
  }
  if (job.replayDir) {
    const t = new Map();
    for (const n of job.replay) t.set(n, await get(job.replayDir + '/' + n));
    opts.transcript = t;
  }
  const mk = async () => {
    const src = await makeInput(adapter, job.path, bytes);
    return job.password ? { source: src, password: job.password } : src;
  };
  let model;
  try {
    model = await emon.convert(await mk(), opts);
  } catch (e) {
    return fail(e);
  }
  try {
    let intake;
    try {
      const i = await emon.intake(await mk(), { chunk: opts.chunk });
      intake = i.map((x) => x.line).join('\n');
    } catch (e) {
      intake = e instanceof EmonError ? `error: ${e.code}` : `error: ${e}`;
    }
    let s = '== json\n' + model.json() + '\n';
    s += '== json-pretty\n' + model.json({ pretty: true }) + '\n';
    s += '== html\n' + model.html() + '\n';
    s += '== html-full\n' + model.html({ ids: true, geometry: true, confidenceBelow: 950, ledger: true, pretty: true }) + '\n';
    s += '== evidence\n' + model.evidence() + '\n';
    s += '== intake\n' + intake + '\n';
    if (job.assets && job.assets !== 'none') {
      s += '== html-external\n' + model.html({ assets: 'external', assetPrefix: 'assets/' }) + '\n';
      s += '== html-omit\n' + model.html({ assets: 'omit' }) + '\n';
      s += '== assets\n';
      for (const a of model.assets) {
        const b = a.bytes();
        s += `${a.name} ${a.type} ${b.length}\n${hex(b)}\n`;
      }
    }
    const out = { exit: 0, out: s, err: '', stats: model.stats };
    if (job.record) out.transcript = [...model.transcript()].map(([n, d]) => [n, b64(d)]);
    return out;
  } finally {
    model.dispose();
  }
}

const MiB = 1024 * 1024;
const heapNow = () => performance.memory.usedJSHeapSize;

/**
 * The JS heap of converting a document with assets on and producing its HTML in `mode`: `inline` (every asset a base64 `data:` URI in
 * the HTML string) or `external` (the HTML names files, and each asset is a Blob made from the module's memory). Samples
 * `performance.memory.usedJSHeapSize` (precise: the page runs with --enable-precise-memory-info) before the run, after the conversion,
 * after each output, and takes the largest: a lower bound of the true peak, the wasm calls being synchronous (nothing can sample inside
 * one) and the collector free to run between samples. A full collection (`gc()`) comes first, and the module's linear memory
 * (`wasmBytes`, which never shrinks) is reported beside the heap: the assets live there, not in the JS heap, until a copy is asked for.
 */
async function heap(job) {
  const e = (emon ||= await Emon.load('/emon.wasm'));
  const bytes = await get(job.path);
  gc();
  await new Promise((r) => setTimeout(r, 50));
  gc();
  const base = heapNow();
  let peak = base;
  const mark = (label, extra) => {
    const h = heapNow();
    peak = Math.max(peak, h);
    samples.push({ label, heapMiB: +((h - base) / MiB).toFixed(1), ...extra });
  };
  const samples = [];
  const src = job.adapter === 'url' ? await urlSource(location.origin + urlFor(job.path)) : bytes.buffer;
  const model = await e.convert(src, { assets: 'images', ...(job.options || {}) });
  mark('convert');
  const assets = model.assets;
  const total = assets.reduce((n, a) => n + a.size, 0);
  let htmlBytes = 0;
  let blobs = [];
  if (job.mode === 'inline') {
    const html = model.html({ assets: 'inline' });
    htmlBytes = html.length;
    mark('html inline', { htmlChars: html.length });
    const j = model.json();
    mark('json', { jsonChars: j.length });
  } else {
    const html = model.html({ assets: 'external', assetPrefix: 'assets/' });
    htmlBytes = html.length;
    mark('html external', { htmlChars: html.length });
    blobs = assets.map((a) => a.blob());
    mark('asset.blob() x' + blobs.length);
    const j = model.json();
    mark('json', { jsonChars: j.length });
    // the peak of everything above (the browser's form: the HTML and a Blob per asset), then what `asset.bytes()` copies cost on top
    var blobPeak = peak;
    const copies = assets.map((a) => a.bytes());
    mark('asset.bytes() x' + copies.length, { copiedMiB: +(copies.reduce((n, c) => n + c.length, 0) / MiB).toFixed(1) });
  }
  const wasmBytes = e.w.x.memory.buffer.byteLength;
  const out = { mode: job.mode, baseMiB: +(base / MiB).toFixed(1), peakAboveBaseMiB: +(((blobPeak ?? peak) - base) / MiB).toFixed(1), assets: assets.length, assetMiB: +(total / MiB).toFixed(1), htmlMiB: +(htmlBytes / MiB).toFixed(1), wasmMiB: +(wasmBytes / MiB).toFixed(1), samples };
  if (blobPeak !== undefined) out.withBytesCopiesPeakAboveBaseMiB = +((peak - base) / MiB).toFixed(1);
  model.dispose();
  return out;
}

export { fromB64, get, hex, b64, makeInput, emon as _emon };
window.H = {
  run,
  heap,
  get,
  makeInput,
  ADAPTERS,
  load: async () => (emon ||= await Emon.load('/emon.wasm')),
  imports: async () => WebAssembly.Module.imports(await WebAssembly.compile(await (await fetch('/emon.wasm')).arrayBuffer())),
  sdk: { Emon, bytesSource, blobSource, urlSource, streamSource, EmonError },
};
window.__ready = true;
