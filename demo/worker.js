// THE DEMO'S WORKER (DEVLOG P11.4c): emon, pdfium and Tesseract, off the page's thread. A module Worker, so it imports the SDK by relative path (an import
// map does not reach a Worker): `./sdk/` is host/js/src, copied by `bb demo`.
//
//   page -> worker   { type: 'convert', id, file, options: { ocr, figures } }
//                    { type: 'ocr-result', id, result } | { type: 'ocr-error', id, message }     (OCR on the page's thread, below)
//   worker -> page   { type: 'ready', nested }                    the Worker is up; `nested`: it can start Workers of its own (Tesseract needs one)
//                    { type: 'stage', id, text }                   what it is doing
//                    { type: 'done', id, html, json, stats }       the Model, as the HTML (assets inline) and the JSON
//                    { type: 'error', id, code, message }
//                    { type: 'ocr', id, req }                      OCR is asked of the page (no nested Workers here)
//
// THE THREE PORTS. emon's engine owns the loop and asks for what it cannot do: bytes of the document (read by range from the File), the rasters of a page
// (`ports.render`: pdfium's WebAssembly build, `./vendor/pdfium/`, loaded when the first one is asked) and the reading of a scan (`ports.ocr`: tesseract.js,
// `./vendor/tesseract/`, loaded when the first one is asked). A born-digital document with healthy text loads neither.
//
// NESTED WORKERS. tesseract.js runs its engine in a Worker of its own, and a Worker starting a Worker is not everywhere (Safari had none before 15.5). When
// this one cannot, the page does the OCR: this file asks it with the request's PNG and the few numbers kin's policy computed from the module (the models, the
// language of the words, the block types), and it answers with the recogniser's result (`app.js`, the same `tesseractPort`).
import { Emon } from './sdk/index.js';
import { pdfiumRenderer } from './sdk/pdfium.js';
import { tesseractPort } from './sdk/tesseract.js';

const url = (p) => new URL(p, import.meta.url).href;
const nested = typeof Worker === 'function' && !new URL(self.location.href).searchParams.has('ocr-main');

let emonP = null;
const loadEmon = () => (emonP ||= Emon.load(fetch(url('./emon.wasm'))));

// pdfium: the factory `bb pdfium-fetch --platform wasm` made, and the .wasm; both fetched by the first render
const renderer = pdfiumRenderer({ module: () => import('./vendor/pdfium/pdfium.mjs'), wasm: url('./vendor/pdfium/pdfium.wasm') });

// tesseract.js, loaded by the first OCR request
const createWorker = async (...a) => (await import('./vendor/tesseract/tesseract.esm.min.js')).default.createWorker(...a);
const local = tesseractPort({
  createWorker,
  languages: ['eng'],
  workerOptions: { workerPath: url('./vendor/tesseract/worker.min.js'), corePath: url('./vendor/tesseract/core'), langPath: url('./vendor/tesseract/lang'), cacheMethod: 'none', gzip: true, workerBlobURL: false },
});

// OCR on the page's thread: the request crosses as plain data
const asked = new Map();
let nextAsk = 1;
const remote = async (req) => {
  const id = nextAsk++;
  const langs = req.models(['eng']);
  const png = req.bitmap();
  const msg = {
    psm: req.psm,
    flags: req.flags,
    dpi: req.dpi,
    rect: req.rect,
    symbols: req.symbols,
    langs,
    wordLang: langs.length ? req.wordLang('eng') : '',
    // kin's table from Tesseract's block types (PolyBlockType 0..15) to a line's type
    types: Array.from({ length: 16 }, (_, i) => req.blockType(i)),
    png,
  };
  return new Promise((resolve, reject) => {
    asked.set(id, { resolve, reject });
    postMessage({ type: 'ocr', id, req: msg }, [png.buffer]);
  });
};

let job = null; // the conversion in progress: its id, for the stages

const stage = (text) => job && postMessage({ type: 'stage', id: job, text });

// the ports, watched so the page can say what is going on
let opened = false;
const watchedRenderer = {
  layers: renderer.layers,
  producer: renderer.producer,
  async open(doc, limits) {
    if (!opened) stage('rendering pages with pdfium (first use: loading it)');
    opened = true;
    return renderer.open(doc, limits);
  },
  terminate: () => renderer.terminate(),
};
let recognising = 0;
const ocr = async (req) => {
  recognising += 1;
  stage(`reading text with Tesseract (${recognising} ${recognising === 1 ? 'page or region' : 'pages and regions'}${nested ? '' : ', on the page thread'})`);
  return (nested ? local : remote)(req);
};

async function convert({ id, file, options }) {
  job = id;
  opened = false;
  recognising = 0;
  const t0 = performance.now();
  try {
    stage('loading emon');
    const emon = await loadEmon();
    stage('converting');
    const model = await emon.convert(file, {
      ocr: options.ocr,
      assets: options.figures ? 'figures' : undefined,
      ports: { render: watchedRenderer, ocr },
    });
    const html = model.html({ assets: 'inline' });
    const json = model.json({ pretty: true });
    const stats = { ...model.stats, assets: model.assets.length, ms: Math.round(performance.now() - t0), pdfiumLoaded: opened, ocrRequests: recognising, ocrOnPageThread: recognising > 0 && !nested };
    model.dispose();
    postMessage({ type: 'done', id, html, json, stats });
  } catch (e) {
    const unsupported = typeof WebAssembly !== 'undefined' && e instanceof WebAssembly.CompileError;
    postMessage({ type: 'error', id, code: unsupported ? 'unsupported-browser' : (e && e.code) || 'error', message: String((e && e.message) || e) });
  } finally {
    job = null;
  }
}

self.onmessage = (ev) => {
  const m = ev.data;
  if (m.type === 'convert') convert(m);
  else if (m.type === 'ocr-result' || m.type === 'ocr-error') {
    const a = asked.get(m.id);
    asked.delete(m.id);
    if (a) m.type === 'ocr-result' ? a.resolve(m.result) : a.reject(new Error(m.message));
  }
};

postMessage({ type: 'ready', nested });
