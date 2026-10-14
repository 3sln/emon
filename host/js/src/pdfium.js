// pdfium's WEBASSEMBLY BUILD AS emon's `render` PORT (DEVLOG P11.4c, SPEC 7.2, 7.3, 13.1): the browser's renderer, fulfilling `render` and `crop` requests
// through plan/fulfil, as `tesseract.js` fulfils `ocr`. pdfium is not a dependency of the SDK: the embedder hands over the build (bblanchon/pdfium-binaries'
// `pdfium-wasm`, the version `tools/pdfium/pdfium.edn` pins, which `bb pdfium-fetch --platform wasm` installs), which keeps its code and its 5 MiB of
// WebAssembly (the embedder's to host or cache) out of the library, and a born-digital document that asks for no raster never loads it.
//
//     import { Emon } from '@3sln/emon';
//     import { pdfiumRenderer } from '@3sln/emon/pdfium';
//
//     const render = pdfiumRenderer({ module: () => import('./vendor/pdfium/pdfium.mjs'), wasm: './vendor/pdfium/pdfium.wasm' });
//     const model = await emon.convert(scan, { ports: { render } });
//     await render.terminate();
//
// WITHOUT `module`, `pdfiumRenderer()` imports the package `@3sln/pdfium-wasm` (npm install @3sln/pdfium-wasm; an optional peer dependency, imported on the
// first render): that package IS this build, `pdfium.mjs` and `pdfium.wasm`, pinned to the version `tools/pdfium/pdfium.edn` names (D1542).
//
// THE BUILD. The archive's `lib/pdfium.js` is a classic script (no export; it fills a global `Module` and starts itself). `bb pdfium-fetch` wraps it,
// text unchanged, as `lib/pdfium.mjs`: `export default function pdfium(Module, node)`. `module` is that factory (or a function that returns it, as
// `() => import(...)` does, or a promise of either); it is called once, with `{ wasmBinary, print, printErr }`, and gives the instantiated
// module (`_malloc`, `_FPDF_*`, `HEAPU8`). `wasm` is the `.wasm`: bytes, a URL (fetched), or a `Response`.
//
// A PORT OF `host/rust/src/pdfium.rs`, call for call, so that the two builds agree on what they can: the same size (`W = floor(wpt * dpi / 72 + 1/2)`),
// the same bitmap (BGRX, filled white, `FPDF_ANNOT` and `FPDF_REVERSE_BYTE_ORDER`, 3 bytes kept a pixel), the same layers (no-text and text-only are made
// by removing page objects, recursively through forms; the watermark layers are `unsupported`), the same failures as answers. The document is
// reopened for each render, as natively (pruning edits the page, so a layer must not leak into the next render).
//
// WHAT IS THE BROWSER'S OWN. (1) The document is read whole on the first render (SPEC 13.1's one exception): pdfium's in-memory loader wants the file in its
// heap (`FPDF_LoadMemDocument64`); the engine's reads stay range reads. (2) `maxPixels` (default 64 Mpx, 192 MiB of RGB) bounds a page more tightly than
// the `pixels` limit (kin's 268 Mpx) because a browser tab has less memory than a native process; the smaller of the two applies.
// (3) An abort or a trap inside pdfium answers `failed` for that request and discards the instance; the next conversion instantiates a fresh one.

const FPDF_ANNOT = 0x01;
const FPDF_REVERSE_BYTE_ORDER = 0x10;
const BITMAP_BGRX = 3;
const OBJ_TEXT = 1;
const OBJ_FORM = 5;
const I32_MAX = 2147483647;

const REQUIRED = [
  '_malloc', '_free', '_FPDF_InitLibrary', '_FPDF_GetLastError', '_FPDF_LoadMemDocument64', '_FPDF_CloseDocument', '_FPDF_GetPageCount', '_FPDF_LoadPage', '_FPDF_ClosePage',
  '_FPDF_GetPageWidthF', '_FPDF_GetPageHeightF', '_FPDFBitmap_CreateEx', '_FPDFBitmap_FillRect', '_FPDFBitmap_Destroy', '_FPDF_RenderPageBitmap', '_FPDFPage_CountObjects',
  '_FPDFPage_GetObject', '_FPDFPage_RemoveObject', '_FPDFPageObj_Destroy', '_FPDFPageObj_GetType', '_FPDFFormObj_CountObjects', '_FPDFFormObj_GetObject', '_FPDFFormObj_RemoveObject',
];

// pdfium's last error, in words (the same table as the native binding)
const lastError = (f) => {
  const e = f._FPDF_GetLastError() >>> 0;
  switch (e) {
    case 0: return 'no error';
    case 2: return 'file not found or could not be opened';
    case 3: return 'not a PDF, or its structure is damaged';
    case 4: return 'password required or incorrect';
    case 5: return 'unsupported security scheme';
    case 6: return 'page not found or content error';
    default: return `pdfium error ${e}`;
  }
};

// the size in pixels of `pt` points at `dpi`: floor(pt * dpi / 72 + 1/2), at least 1 (and Rust's `as i32`, which makes NaN 0)
const pixelsOf = (pt, dpi) => {
  const v = Math.floor((pt * dpi) / 72 + 0.5);
  if (Number.isNaN(v)) return 0;
  if (v < 1) return 1;
  if (v > I32_MAX) return I32_MAX;
  return v;
};

// Does `layer` keep this object? null for a form object: it is walked, its contents decide.
const keeps = (f, layer, o) => {
  const ty = f._FPDFPageObj_GetType(o);
  if (ty === OBJ_FORM) return null;
  if (layer === 1) return ty !== OBJ_TEXT;
  if (layer === 2) return ty === OBJ_TEXT;
  return true;
};

const pruneForm = (f, layer, form) => {
  for (let i = f._FPDFFormObj_CountObjects(form) - 1; i >= 0; i--) {
    const o = f._FPDFFormObj_GetObject(form, i);
    if (!o) continue;
    const k = keeps(f, layer, o);
    if (k === false) {
      if (f._FPDFFormObj_RemoveObject(form, o) !== 0) f._FPDFPageObj_Destroy(o);
    } else if (k === null) pruneForm(f, layer, o);
  }
};

const prunePage = (f, layer, page) => {
  for (let i = f._FPDFPage_CountObjects(page) - 1; i >= 0; i--) {
    const o = f._FPDFPage_GetObject(page, i);
    if (!o) continue;
    const k = keeps(f, layer, o);
    if (k === false) {
      if (f._FPDFPage_RemoveObject(page, o) !== 0) f._FPDFPageObj_Destroy(o);
    } else if (k === null) pruneForm(f, layer, o);
  }
};

const failed = (reason) => ({ status: 'failed', reason });
const unsupported = (reason) => ({ status: 'unsupported', reason });

/**
 * @param {object} [o]
 * @param {Function|Promise} [o.module] pdfium's factory (`lib/pdfium.mjs`'s default export), a function that returns it (`() => import('./pdfium.mjs')`), or a
 *   promise of either: called once, on the first render. Without it: the package `@3sln/pdfium-wasm` (an optional peer dependency), whose .wasm is the default `wasm`
 * @param {ArrayBuffer|Uint8Array|string|URL|Response|Promise} [o.wasm] `pdfium.wasm`: its bytes, its URL, or a `Response` (with your own `module`, without it the
 *   factory looks for the file beside ITS OWN script, which in a Worker is the Worker's: pass it)
 * @param {number} [o.maxPixels] the largest page, in pixels, this renderer will make (default 64 Mpx); the conversion's `pixels` limit applies too
 * @returns {{ layers: number, producer: object, open(doc: object, limits: object): Promise<object>, terminate(): Promise<void> }} a Renderer (`render.js`)
 */
export function pdfiumRenderer({ module, wasm, maxPixels = 64 * 2 ** 20 } = {}) {
  let ready = null; // Promise<module> of the instantiated library; null before the first use and after an abort

  const instantiate = async () => {
    let factory = module;
    let wasmSrc = wasm;
    if (!factory) {
      // THE DEFAULT (D1542): the package @3sln/pdfium-wasm, an OPTIONAL peer dependency, imported only now, on the first render, so that this package stays
      // dependency-free and a document that asks for no raster never loads it. Its default export is the factory and `wasmBinary()` the bytes of the .wasm
      // (the package reads them, so this SDK names no file and no Node module, D058).
      let pkg;
      try {
        pkg = await import('@3sln/pdfium-wasm');
      } catch (e) {
        throw new Error(`pdfiumRenderer() without \`module\` uses the package @3sln/pdfium-wasm, which cannot be imported (npm install @3sln/pdfium-wasm; or pass \`module\` and \`wasm\`): ${e && e.message}`);
      }
      factory = pkg;
      if (wasmSrc === undefined) wasmSrc = pkg.wasmBinary();
    }
    const arg = { print() {}, printErr() {} };
    if (wasmSrc !== undefined) {
      let src = await wasmSrc;
      if (typeof src === 'string' || src instanceof URL) src = await fetch(src);
      if (typeof Response !== 'undefined' && src instanceof Response) {
        if (!src.ok) throw new Error(`pdfium.wasm: HTTP ${src.status}`);
        src = await src.arrayBuffer();
      }
      arg.wasmBinary = src instanceof Uint8Array ? src : new Uint8Array(src);
    }
    let m = await factory;
    // `() => import(...)`, a factory, or the namespace itself: the factory is the function that takes the module's arguments and gives the module
    if (typeof m === 'function') {
      const r = await m(arg);
      m = r && typeof r === 'object' && typeof r.default === 'function' ? await r.default(arg) : r;
    } else if (m && typeof m.default === 'function') {
      m = await m.default(arg);
    }
    if (!m || typeof m._malloc !== 'function') throw new Error('pdfium: the factory did not give a module (lib/pdfium.mjs, from `bb pdfium-fetch --platform wasm`)');
    const missing = REQUIRED.filter((n) => typeof m[n] !== 'function');
    if (missing.length) throw new Error(`pdfium: this build lacks ${missing.join(', ')}`);
    m._FPDF_InitLibrary();
    return m;
  };

  const load = () => {
    if (!ready) {
      const p = instantiate();
      ready = p;
      // a failed load is tried again by the next conversion, not remembered
      p.catch(() => {
        if (ready === p) ready = null;
      });
    }
    return ready;
  };

  const renderer = {
    // the layers pdfium separates: full, without text, text only (the watermark layers are kin's to separate, SPEC 8.1)
    layers: 0b00111,
    // what a transcript says made its pixels; the version is the build `tools/pdfium/pdfium.edn` pins
    producer: { renderer: 'pdfium-wasm', 'pdfium-version': 'chromium/8076', 'render-host': 'host/js/src/pdfium.js' },

    async open(doc, limits) {
      const f = await load();
      const bytes = doc.bytes || (await doc.read(0, doc.size));
      const n = bytes.length;
      const ptr = f._malloc(Math.max(1, n));
      if (!ptr) throw new Error(`pdfium: no memory for the ${n}-byte document`);
      f.HEAPU8.set(bytes, ptr);
      // the password is a C string
      let pw = 0;
      let pwBad = false;
      if (doc.password && doc.password.length) {
        pwBad = doc.password.includes(0);
        pw = f._malloc(doc.password.length + 1);
        if (pw) {
          f.HEAPU8.set(doc.password, pw);
          f.HEAPU8[pw + doc.password.length] = 0;
        }
      }
      const max = Math.min(limits && Number.isFinite(limits.pixels) ? limits.pixels : maxPixels, maxPixels);
      let dead = null; // set when pdfium trapped under this document: the instance is gone
      let closed = false;

      const poison = (e) => {
        dead = (e && e.message) || String(e);
        ready = null; // the next conversion instantiates afresh
      };

      return {
        async render({ page, dpi, layer }, sink) {
          if (layer === 3 || layer === 4) return unsupported("pdfium's mark API cannot read /Artifact /Subtype (a name)");
          if (!Number.isInteger(layer) || layer < 0 || layer > 4) return unsupported(`layer ${layer}`);
          if (!Number.isInteger(dpi) || dpi < 1 || dpi > 4800) return failed(`dpi ${dpi}`);
          if (closed) return failed('the document is closed');
          if (dead) return failed(`pdfium stopped: ${dead}`);
          if (pwBad) return failed('a password with a NUL');
          try {
            return renderOne(f, ptr, n, pw, max, page, dpi, layer, sink);
          } catch (e) {
            poison(e);
            return failed(`pdfium stopped: ${dead}`);
          }
        },
        close() {
          if (closed) return;
          closed = true;
          if (!dead) {
            f._free(ptr);
            if (pw) f._free(pw);
          }
        },
      };
    },

    async terminate() {
      // the instance is dropped; its memory goes with the last reference (WebAssembly memory cannot be returned any other way)
      ready = null;
    },
  };
  return renderer;
}

// One render: the port of `Pdfium::render` and `render_doc` / `render_page` (host/rust/src/pdfium.rs). Synchronous: pdfium is single-threaded, and no
// other call of this renderer can run between its first call and its last.
function renderOne(f, ptr, n, pw, max, page, dpi, layer, sink) {
  const doc = f._FPDF_LoadMemDocument64(ptr, n, pw);
  if (!doc) return failed(lastError(f));
  try {
    const pages = f._FPDF_GetPageCount(doc);
    if (page < 0 || page >= pages) return failed(`page ${page} of ${pages}`);
    const pg = f._FPDF_LoadPage(doc, page);
    if (!pg) return failed(lastError(f));
    try {
      return renderPage(f, pg, max, dpi, layer, sink);
    } finally {
      f._FPDF_ClosePage(pg);
    }
  } finally {
    f._FPDF_CloseDocument(doc);
  }
}

function renderPage(f, pg, max, dpi, layer, sink) {
  const [wpt, hpt] = [f._FPDF_GetPageWidthF(pg), f._FPDF_GetPageHeightF(pg)];
  const [w, h] = [pixelsOf(wpt, dpi), pixelsOf(hpt, dpi)];
  if (w * h > max) return failed(`${w}x${h} pixels is over the limit of ${max}`);
  let flags = FPDF_REVERSE_BYTE_ORDER;
  if (layer === 0) flags |= FPDF_ANNOT;
  else if (layer === 1) {
    prunePage(f, layer, pg);
    flags |= FPDF_ANNOT;
  } else prunePage(f, layer, pg);
  const stride = w * 4;
  const size = stride * h;
  if (w < 1 || h < 1 || size > I32_MAX) return failed('the bitmap could not be created');
  const buf = f._malloc(size);
  if (!buf) return failed('the bitmap could not be created');
  try {
    const bm = f._FPDFBitmap_CreateEx(w, h, BITMAP_BGRX, buf, stride);
    if (!bm) return failed('the bitmap could not be created');
    f._FPDFBitmap_FillRect(bm, 0, 0, w, h, 0xffffffff);
    f._FPDF_RenderPageBitmap(bm, pg, 0, 0, w, h, 0, flags);
    f._FPDFBitmap_Destroy(bm);
    // BGRX with the byte order reversed is RGBX in memory: the first 3 bytes of each pixel are kept
    const px = w * h;
    const out = sink ? sink(px * 3) : new Uint8Array(px * 3);
    const src = f.HEAPU8;
    for (let i = 0, s = buf, d = 0; i < px; i++, s += 4, d += 3) {
      out[d] = src[s];
      out[d + 1] = src[s + 1];
      out[d + 2] = src[s + 2];
    }
    return { width: w, height: h, rgb: out };
  } finally {
    f._free(buf);
  }
}
