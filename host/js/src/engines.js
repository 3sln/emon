// THE ENGINE REGISTRY, JavaScript host (DEVLOG D925, K3; the twin of `host/go/engines.go` and `host/rust/src/adapter.rs`: read those for the reasoning).
//
// An OCR engine is an ADAPTER that is registered, never wired. `emon.engines()` is an empty registry bound to the loaded module; `engines.add(adapter)` registers one
// (`tesseractAdapter` of `@3sln/emon/tesseract`, `ppocrEngine` of `@3sln/emon/ppocr`, or the embedder's own), and `engines.port()` is the `ports.ocr` of a conversion: the registry
// routes each request to the adapter registered for the engine it names. Kin decides every rule and this file asks the module for them: what an adapter may declare
// (`ad-register-check`, against the engine table) and which adapter a request goes to (`ad-route`: the first registered for the OIL engine the request names). An engine nobody
// registered answers `unsupported`; a rejection from an adapter answers `failed`, and either way the page is unread, never blank.
//
// An adapter is an object:
//
//     { name: 'tesseract',              // the engine's name in kin's table, or the embedder's own
//       engine: 1,                      // the OIL engine it answers as: 1 tesseract, 2 ppocr, 3 apple-vision, 4 mlkit-text, 5 windows-media-ocr
//       caps, measured, needs, flags,   // optional (0): what it declares, what it measures, the pack kinds it reads with (1 recognizer, 2 osd, 4 detector, 8 classifier), its flags
//       producer: { engine: '...' },    // optional: what the transcript says produced it
//       read(request) { ... },          // `ocr.js`'s port form: a result, `null` (unsupported) or a rejection (failed)
//       close() { ... } }               // optional
//
// A result here is `ocr.js`'s form, which the module's own builders turn into the OIL record (so its layout is held by kin, not by this file); the canonical answers of the
// Rust-built PP-OCR module come back through `canonicalOcr` and are checked by the module. The flags `1` (async) is refused, `2` (remote: the pixels leave the device) is refused
// unless `allowRemote(true)` came first. Adapters in this host are in-process values: there are no adapter libraries to load, so there is no `defaults()`; a page that registers
// no Tesseract adapter has none (D920).

import { EmonError } from './sources.js';

const ASYNC = 1;
const REMOTE = 2;

export class Engines {
  #w;
  #entries = [];
  #allowRemote = false;
  #notes = [];

  /** @param w the loaded module (`emon.w`): use `emon.engines()`. */
  constructor(w) {
    this.#w = w;
  }

  /** Lets adapters that send the pixels off the device register (default no: documents may hold personal data). Call it before registering them. */
  allowRemote(allow) {
    this.#allowRemote = !!allow;
  }

  /** Registers an adapter, held to kin's engine table; it is closed when refused, and the error's message is the refusal's name and sentence. */
  async add(adapter) {
    const { name = 'adapter', engine, caps = 0, measured = 0, needs = 0, flags = 0 } = adapter;
    const code = this.#w.adapterCheck(1, engine | 0, caps | 0, measured | 0, needs | 0, flags | 0);
    const refuse = async (reason, sentence) => {
      try { await adapter.close?.(); } catch { /* the adapter is refused whatever it says */ }
      throw new EmonError('usage', `${reason}: ${name}${sentence ? ` ${sentence}` : ''} (engine ${engine}, caps ${caps}, needs ${needs}, flags ${flags})`);
    };
    if (typeof adapter.read !== 'function') await refuse('adapter-abi-unsupported', 'has no `read`');
    if (code !== 0) await refuse(this.#w.adapterReason(code));
    if (flags & ASYNC) await refuse('adapter-abi-unsupported', 'is async; this host registers in-process adapters only');
    if (flags & REMOTE && !this.#allowRemote) await refuse('adapter-remote-refused', 'sends the pixels off the device; call allowRemote(true) first');
    this.#entries.push(adapter);
  }

  /** The registered adapters, in registration order. */
  describe() {
    return this.#entries.map(({ name = 'adapter', engine, caps = 0, measured = 0, needs = 0, flags = 0, producer = {} }) => ({ name, engine, caps, measured, needs, flags, producer: { ...producer } }));
  }

  /** Why requests were left unread since the last call, one line each. */
  notes() {
    return this.#notes.splice(0);
  }

  /** The first adapter's pairs, the combined engine name when there are several (`tesseract+ppocr`), then the other adapters' further keys. */
  producer() {
    const out = {};
    const combined = this.#entries.length > 1 ? this.#entries.map((e) => e.name ?? 'adapter').join('+') : null;
    this.#entries.forEach((e, i) => {
      for (const [k, v] of Object.entries(e.producer ?? {})) if (!(combined && k === 'ocr-engine') && !(k in out)) out[k] = v;
      if (i === 0 && combined) out['ocr-engine'] = combined;
    });
    return out;
  }

  /** The `ports.ocr` of a conversion: routes each request by the engine it names. */
  port() {
    const port = async (req) => {
      const at = this.#w.adapterRoute(req.engine ?? 0, this.#entries.map((e) => e.engine | 0));
      if (at < 0) return null;
      const adapter = this.#entries[at];
      try {
        const r = await adapter.read(req);
        if (r === null || r === undefined) this.#notes.push(`${adapter.name ?? 'adapter'}: unsupported`);
        return r;
      } catch (e) {
        this.#notes.push(`${adapter.name ?? 'adapter'}: failed ${(e && e.message) || e}`);
        throw e;
      }
    };
    Object.defineProperty(port, 'producer', { get: () => this.producer(), enumerable: true });
    port.terminate = () => this.close();
    return port;
  }

  /** Closes every adapter; the registry is empty after it. */
  async close() {
    const es = this.#entries.splice(0);
    for (const e of es) await e.close?.();
  }
}
