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
import { Packs } from './packs.js';

const ASYNC = 1;
const REMOTE = 2;

export class Engines {
  #w;
  #entries = [];
  #allowRemote = false;
  #notes = [];
  #optionLines;
  /** The packs (D896, P13.16): the mode, the app's hook, download and events, `ensure`. See `packs.js`. */
  packs;

  /** @param w the loaded module (`emon.w`): use `emon.engines()`. */
  constructor(w, optionLines = () => '') {
    this.#w = w;
    this.#optionLines = optionLines;
    this.packs = new Packs(w, (line) => this.#notes.push(line));
  }

  /** D896's `engines()`: every engine of kin's table that runs here, with its state (`built-in`, `installed`, `downloadable` or `unavailable`) and why, as the C ABI's `emon_engines_describe` JSON
   *  (`{ platform, mode, offline, store, engines: [{ name, kind, impl, state, registered, calibrated, caps, oen, role, needs, download_bytes, reason }] }`). `renderer`: say a renderer port is in use. */
  state({ renderer = false } = {}) {
    const p = this.packs;
    const cat = p.catalogue.pfetch;
    return this.#w.packDescribe(this.#entries.map((e) => e.engine | 0), renderer, p.installed(), cat[p.mode], p.offline, 'memory');
  }

  /** D896's `prepare(options)`: the packs a conversion with these options (`ocr`, `ocrEngine`, `locale`) needs and which are missing, as the C ABI's `emon_prepare` JSON
   *  (`{ verdict, mode, offline, engines, languages, osd, packs, unsupported, missing, missing_bytes, conditional, conditional_bytes }`). */
  prepare(options = {}) {
    const p = this.packs;
    return this.#w.packPrepare(this.#optionLines(options), p.installed(), p.catalogue.pfetch[p.mode], p.offline);
  }

  // The packs a request needs, made present or refused (D896), for an adapter that reads its models from them (`adapter.packs: true`): `{ ok: adapter }`, `{ ok: other }` (the app's substitute)
  // or `{ unread: reason }`. An adapter without `packs: true` brings its own models (tesseract.js's `langPath`, PP-OCR's URLs) and no pack is asked for.
  async #ensure(adapter, req) {
    if (!adapter.packs || !(adapter.needs | 0)) return { ok: adapter };
    const w = this.#w;
    const eng = w.packOenEng(adapter.engine | 0);
    if (eng < 0) return { ok: adapter };
    const packs = this.packs;
    const langs = adapter.engine === 1 ? (req.langs ?? []).filter((l) => /^[a-z]{3}$/.test(l)).map((l) => ((l.charCodeAt(0) << 24) | (l.charCodeAt(1) << 16) | (l.charCodeAt(2) << 8)) | 0) : [];
    const osd = !!req.osd && ((adapter.needs | 0) & 2) !== 0;
    let rows;
    try {
      rows = w.packResolve(osd, langs, [eng], packs.installed());
    } catch (e) {
      return { unread: `engine-unavailable: ${e.message}` };
    }
    const c = packs.catalogue;
    const unreadable = rows.filter((r) => r[0] === c.pstate.unsupported);
    if (langs.length && unreadable.length >= langs.length) return { unread: `lang-unavailable:${req.langs[0]}` };
    const engineName = adapter.name ?? 'adapter';
    const kindOf = (code) => packs.catalogue.packs.find((x) => x.code === code)?.kind;
    for (const r of rows.filter((x) => x[0] === c.pstate.missing)) {
      const got = await packs.need(r[2], 'conversion', engineName);
      if (got.ready) continue;
      // orientation detection degrades to kin's own policy without its model; it never leaves the page unread
      if (got.unread !== undefined && kindOf(r[2]) === 'osd') continue;
      if (got.unread !== undefined) return { unread: got.unread };
      // another engine would answer under its own name: kin's plan chose this engine, so the request stays unread
      const named = this.#entries.find((e) => (e.name ?? 'adapter') === got.substitute);
      if (named && named.engine !== adapter.engine) return { unread: `pack-substitute-differs:${got.substitute}` };
      const other = this.#entries.find((e) => e !== adapter && e.packs && e.engine === adapter.engine && (!named || e === named));
      return other ? { ok: other } : { unread: `pack-substitute-unavailable:${got.substitute}` };
    }
    return { ok: adapter };
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
      let adapter = this.#entries[at];
      try {
        const got = await this.#ensure(adapter, req);
        if (got.unread !== undefined) {
          this.#notes.push(`${adapter.name ?? 'adapter'}: unsupported ${got.unread}`);
          return null;
        }
        adapter = got.ok;
        const r = await adapter.read(req, adapter.packs ? { packs: this.packs.view(adapter.name ?? 'adapter') } : undefined);
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
