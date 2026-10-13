// THE DEMO'S PAGE (DEVLOG P11.4c): a drop zone, a file picker and a few samples; the conversion runs in `worker.js`, and this shows the document next to the
// semantic HTML emon made of it. Nothing is uploaded: the document is read by range from the File the browser holds.
//
// No framework and no bundler: this is an ES module and the SDK is `./sdk/` (host/js/src, copied by `bb demo`). `window.__demo` is the page's own door, the
// one the UI uses, so `bb demo --check` drives the page the way a person does.
import { tesseractPort } from './sdk/tesseract.js';

const $ = (id) => document.getElementById(id);
const el = { drop: $('drop'), file: $('file'), status: $('status'), out: $('out'), source: $('source'), html: $('html'), json: $('json'), stats: $('stats'), tabs: [...document.querySelectorAll('[role=tab]')], panes: [...document.querySelectorAll('[role=tabpanel]')], dlHtml: $('dl-html'), dlJson: $('dl-json'), ocr: $('ocr'), figures: $('figures'), samples: $('samples'), notice: $('notice'), busy: $('busy') };

const SAMPLES = [
  { file: 'invoice.pdf', label: 'Invoice', note: 'born-digital: its own text, no pdfium and no OCR' },
  { file: 'bank-statement.pdf', label: 'Bank statement', note: 'three pages, tables' },
  { file: 'columns-two.pdf', label: 'Two columns', note: 'reading order down each column' },
  { file: 'table-multipage.pdf', label: 'Table over four pages', note: 'a table that continues, header and all' },
  { file: 'figures-charts.pdf', label: 'Charts and figures', note: 'figures become images: pdfium renders them', figures: true },
  { file: 'scan-plain.pdf', label: 'Scanned page', note: 'a picture of text: Tesseract reads it' },
  { file: 'image-invoice.jpg', label: 'JPEG invoice', note: 'an image is a page too: Tesseract reads it' },
];

// the iframe that shows emon's HTML gets a few lines of styling, spliced in here only (the Model's HTML is unstyled)
const VIEWER_CSS = 'html{font:15px/1.5 system-ui,sans-serif;color:#222;background:#fff}body{margin:1em 1.5em;max-width:60em}h1,h2,h3,h4{line-height:1.25}table{border-collapse:collapse;margin:1em 0}th,td{border:1px solid #bbb;padding:.2em .5em;vertical-align:top}th{background:#f0f0f0}aside[data-emon=furniture]{color:#888;font-size:.85em;border-left:3px solid #ddd;padding-left:.5em;margin:.5em 0}figure{border:1px solid #ddd;padding:.5em;margin:1em 0}figcaption{color:#555;font-size:.9em}img{max-width:100%}[data-emon-kind=seal],[data-emon=seal]{outline:1px dashed #c33}[hidden]{display:block!important;opacity:.6}';

const styled = (html) => {
  const style = `<style>${VIEWER_CSS}</style>`;
  const i = html.indexOf('<head>');
  return i >= 0 ? html.slice(0, i + 6) + style + html.slice(i + 6) : style + html;
};

// ---------------------------------------------------------------------------------------------------------------- the browser
// emon's module uses WebAssembly's standardised exception handling (`try_table`): Chrome 137, Firefox 131, Safari 18.4. A module with one tells.
const TRY_TABLE = new Uint8Array([0, 0x61, 0x73, 0x6d, 1, 0, 0, 0, 1, 4, 1, 0x60, 0, 0, 3, 2, 1, 0, 0x0a, 0x0e, 1, 0x0c, 0, 0x02, 0x40, 0x1f, 0x40, 1, 2, 0, 1, 0x0b, 0x0b, 0x0b]);
const supported = typeof WebAssembly === 'object' && typeof Worker === 'function' && WebAssembly.validate(TRY_TABLE);

// ---------------------------------------------------------------------------------------------------------------- the worker
let worker = null;
let nested = true;
let ready = null;
let nextId = 1;
const jobs = new Map();

// OCR on this thread, for a Worker that cannot start one (tesseract.js keeps its engine in a Worker of its own)
let pageOcr = null;
const pageThreadOcr = () =>
  (pageOcr ||= tesseractPort({
    createWorker: async (...a) => (await import('./vendor/tesseract/tesseract.esm.min.js')).default.createWorker(...a),
    languages: ['eng'],
    workerOptions: { workerPath: new URL('./vendor/tesseract/worker.min.js', import.meta.url).href, corePath: new URL('./vendor/tesseract/core', import.meta.url).href, langPath: new URL('./vendor/tesseract/lang', import.meta.url).href, cacheMethod: 'none', gzip: true, workerBlobURL: false },
  }));

async function answerOcr(m) {
  const r = m.req;
  const req = { id: m.id, psm: r.psm, flags: r.flags, dpi: r.dpi, rect: r.rect, symbols: r.symbols, models: () => r.langs, wordLang: () => r.wordLang, blockType: (p) => r.types[p], bitmap: () => r.png };
  try {
    worker.postMessage({ type: 'ocr-result', id: m.id, result: await pageThreadOcr()(req) });
  } catch (e) {
    worker.postMessage({ type: 'ocr-error', id: m.id, message: String((e && e.message) || e) });
  }
}

function start() {
  if (ready) return ready;
  ready = new Promise((resolve, reject) => {
    // `?ocr-main` is passed on: it makes the Worker behave as one that cannot start Workers (the page-thread OCR path)
    worker = new Worker(new URL('./worker.js', import.meta.url).href + location.search, { type: 'module' });
    worker.onerror = (e) => {
      reject(new Error(e.message || 'the worker failed to start'));
      for (const j of jobs.values()) j.reject(new Error(e.message || 'the worker failed'));
      jobs.clear();
    };
    worker.onmessage = (ev) => {
      const m = ev.data;
      if (m.type === 'ready') {
        nested = m.nested;
        resolve();
      } else if (m.type === 'stage') jobs.get(m.id)?.stage?.(m.text);
      else if (m.type === 'ocr') answerOcr(m);
      else if (m.type === 'done' || m.type === 'error') {
        const j = jobs.get(m.id);
        jobs.delete(m.id);
        if (!j) return;
        if (m.type === 'done') j.resolve(m);
        else j.reject(Object.assign(new Error(m.message), { code: m.code }));
      }
    };
  });
  return ready;
}

/** Convert one File or Blob: -> { html, json, stats }. `options`: { ocr: 'auto'|'always'|'never', figures: boolean }. */
async function convert(file, options, stage) {
  await start();
  const id = nextId++;
  return new Promise((resolve, reject) => {
    jobs.set(id, { resolve, reject, stage });
    worker.postMessage({ type: 'convert', id, file, options });
  });
}

// ---------------------------------------------------------------------------------------------------------------- the page
let urls = [];
const forget = () => {
  for (const u of urls) URL.revokeObjectURL(u);
  urls = [];
};
const blobUrl = (data, type) => {
  const u = URL.createObjectURL(new Blob([data], { type }));
  urls.push(u);
  return u;
};

function select(tab) {
  for (const t of el.tabs) t.setAttribute('aria-selected', String(t === tab));
  for (const p of el.panes) p.hidden = p.getAttribute('aria-labelledby') !== tab.id;
}
for (const t of el.tabs) t.addEventListener('click', () => select(t));

const fmt = (n) => (n >= 1024 * 1024 ? (n / 1024 / 1024).toFixed(1) + ' MiB' : n >= 1024 ? (n / 1024).toFixed(1) + ' KiB' : n + ' bytes');

function show(file, name, r) {
  forget();
  const isPdf = file.type === 'application/pdf' || /\.pdf$/i.test(name);
  el.source.replaceChildren();
  if (isPdf) {
    const f = document.createElement('iframe');
    f.title = `the document: ${name}`;
    f.src = blobUrl(file, 'application/pdf');
    el.source.append(f);
  } else {
    const i = document.createElement('img');
    i.alt = `the document: ${name}`;
    i.src = blobUrl(file, file.type || 'image/png');
    el.source.append(i);
  }
  el.html.srcdoc = styled(r.html);
  el.json.textContent = r.json;
  const s = r.stats;
  const rows = [
    ['document', `${name} (${fmt(file.size)})`],
    ['time', `${(s.ms / 1000).toFixed(2)} s, in your browser`],
    ['read from the file', `${fmt(s.bytesRead)} in ${s.reads} reads`],
    ['conversion attempts', `${s.attempts} (a unit that missed bytes is run again), ${s.fetchRounds} fetch rounds`],
    ['pdfium', s.pdfiumLoaded ? 'used: pages rendered for figures or for reading' : 'not loaded: nothing here needed a render'],
    ['Tesseract', s.ocrRequests ? `${s.ocrRequests} recognitions${s.ocrOnPageThread ? ' (on the page thread: this browser has no nested Workers)' : ''}` : 'not loaded: nothing here needed OCR'],
    ['figure images', String(s.assets)],
    ['notes', s.notes && s.notes.length ? s.notes.join('; ') : 'none'],
  ];
  el.stats.replaceChildren(...rows.flatMap(([k, v]) => {
    const dt = document.createElement('dt');
    dt.textContent = k;
    const dd = document.createElement('dd');
    dd.textContent = v;
    return [dt, dd];
  }));
  const base = name.replace(/\.[^.]+$/, '');
  el.dlHtml.href = blobUrl(r.html, 'text/html');
  el.dlHtml.download = `${base}.html`;
  el.dlJson.href = blobUrl(r.json, 'application/json');
  el.dlJson.download = `${base}.json`;
  el.out.hidden = false;
  select(el.tabs[0]);
}

let busy = false;
async function run(file, name) {
  if (busy) return null;
  busy = true;
  el.busy.hidden = false;
  el.out.hidden = true;
  el.status.className = '';
  el.status.textContent = `Reading ${name}`;
  try {
    const r = await convert(file, { ocr: el.ocr.value, figures: el.figures.checked }, (t) => (el.status.textContent = `${name}: ${t}`));
    show(file, name, r);
    el.status.textContent = `${name}: done in ${(r.stats.ms / 1000).toFixed(1)} s. Everything ran in this page; nothing was uploaded.`;
    return r;
  } catch (e) {
    el.status.className = 'error';
    el.status.textContent = e.code === 'unsupported-browser' ? unsupportedText : `${name}: ${e.code ? e.code + ': ' : ''}${e.message}`;
    throw e;
  } finally {
    busy = false;
    el.busy.hidden = true;
  }
}

async function sample(s) {
  el.figures.checked = !!s.figures;
  const res = await fetch(new URL(`./samples/${s.file}`, import.meta.url));
  const blob = await res.blob();
  const file = new File([blob], s.file, { type: /\.pdf$/.test(s.file) ? 'application/pdf' : /\.png$/.test(s.file) ? 'image/png' : 'image/jpeg' });
  return run(file, s.file);
}

const unsupportedText = 'This browser cannot run emon: its WebAssembly module uses the standardised exception handling that Chrome 137, Firefox 131 and Safari 18.4 (and newer) have. Update the browser and try again.';

for (const s of SAMPLES) {
  const b = document.createElement('button');
  b.type = 'button';
  b.textContent = s.label;
  b.title = s.note;
  b.addEventListener('click', () => sample(s).catch(() => {}));
  el.samples.append(b);
}

el.file.addEventListener('change', () => {
  const f = el.file.files[0];
  if (f) run(f, f.name).catch(() => {});
});
el.drop.addEventListener('click', () => el.file.click());
el.drop.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault();
    el.file.click();
  }
});
for (const t of ['dragenter', 'dragover']) el.drop.addEventListener(t, (e) => { e.preventDefault(); el.drop.classList.add('over'); });
for (const t of ['dragleave', 'drop']) el.drop.addEventListener(t, () => el.drop.classList.remove('over'));
el.drop.addEventListener('drop', (e) => {
  e.preventDefault();
  const f = e.dataTransfer.files[0];
  if (f) run(f, f.name).catch(() => {});
});

if (!supported) {
  el.notice.hidden = false;
  el.notice.textContent = unsupportedText;
  el.drop.setAttribute('aria-disabled', 'true');
  el.file.disabled = true;
  for (const b of el.samples.querySelectorAll('button')) b.disabled = true;
} else {
  // the module and the Worker load while the person chooses
  start().catch((e) => {
    el.status.className = 'error';
    el.status.textContent = String(e.message || e);
  });
}

window.__demo = {
  supported,
  ready: () => start().then(() => ({ nested })),
  /** Convert a bundled sample by file name (the buttons' own path): -> { html, json, stats }. */
  sample: (file) => sample(SAMPLES.find((s) => s.file === file)),
  /** Convert a File or Blob. */
  convert: (file, name, options = {}) => {
    if (options.ocr) el.ocr.value = options.ocr;
    if ('figures' in options) el.figures.checked = !!options.figures;
    return run(file, name);
  },
  samples: SAMPLES.map((s) => s.file),
};
