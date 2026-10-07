// emon's JavaScript SDK (DEVLOG P9.3, SPEC 13, 13.1, 7.3; D058, D089, D117; D356-D361): PDFs and images to semantic HTML and
// emon JSON in the browser, over the WASM binding (`host/wasm`, the Rust host built for wasm32).
//
//     import { Emon } from '@3sln/emon';
//     const emon = await Emon.load('/emon.wasm');
//     const model = await emon.convert(file /* a File, Blob, URL, ArrayBuffer, ReadableStream or { size, read } */, { assets: 'images' });
//     const html = model.html({ assets: 'external' });          // assets are files, not data: URIs
//     for (const a of model.assets) img.src = URL.createObjectURL(a.blob());
//     const tree = model.object();                               // the JSON, parsed
//     model.dispose();
//
// THE LOOP IS THIS FILE'S (D058 revised, SPEC 13.1, 7.3). JavaScript cannot block on a promise, so the engine never calls out: it
// runs until it needs something, says what, and this loop awaits it and runs it again. What the engine needs is (1) BYTES of the
// document its `Src` missed (the engine re-runs the unit that missed from scratch, so the bytes it ends up with are the bytes it
// touched), read from the byte source a chunk at a time with doubling read-ahead; (2) answers to `ocr` requests, from
// `ports.ocr` (tesseract.js: `@3sln/emon/tesseract`). `decode` and `decode-img` are the engine's own (PNG, JPEG, TIFF, CCITT,
// DCT in Rust/WASM); `render` and `crop` are answered `unsupported` (pdfium's WebAssembly build is SPEC 7.3's, not built), which
// is an answer: a page that needed a render then has no text from it, and nothing is guessed (D038, D199).
//
// There is no whole-file read: a Blob or a URL is read by range. (An ArrayBuffer is already in memory, so its source says so and
// the engine is handed it at once; `{ lazy: true }` reads it by range like the others.)

import { Wasm } from './wasm.js';
import { toSource, bytesSource, blobSource, urlSource, streamSource, SourceChangedError, EmonError } from './sources.js';
import { ocrRequest, encodeOcr } from './ocr.js';

export { bytesSource, blobSource, urlSource, streamSource, toSource, SourceChangedError, EmonError };

const STAGE = 4 * 1024 * 1024; // bytes handed to the module per call: a read of a big range is staged, never doubled in memory
const READS = 8; // reads of the source in flight at once

const optionLines = (o, chunk, record) => {
  const L = [];
  const put = (k, v) => v !== undefined && v !== null && L.push(`${k}=${v}`);
  put('locale', o.locale);
  put('ocr', o.ocr);
  put('furniture', o.furniture);
  put('assets', o.assets);
  put('asset-dpi', o.assetDpi);
  put('asset-pixels', o.assetPixels);
  put('asset-bytes', o.assetBytes);
  put('assets-bytes', o.assetsBytes);
  if (o.assetSignatures !== undefined) put('asset-signatures', o.assetSignatures === true || o.assetSignatures === 'on' ? 'on' : 'off');
  if (o.keepEvidence) put('keep-evidence', 1);
  put('ocr-scope', o.ocrScope);
  put('max-rounds', o.maxRounds);
  put('fetch-rounds', o.fetchRounds);
  for (const [k, v] of Object.entries(o.limits || {})) put(`limit.${k}`, v);
  if (record) put('record', 1);
  for (const [k, v] of Object.entries(o.producer || {})) put(`producer.${k}`, v);
  if (o.ports && o.ports.ocr) put('js-ocr', 1);
  return L.join('\n');
};

const pairs = (i32) => {
  const out = [];
  for (let i = 0; i + 1 < i32.length; i += 2) out.push({ offset: i32[i], length: i32[i + 1] });
  return out;
};

const entriesOf = (t) => (t instanceof Map ? [...t.entries()] : Array.isArray(t) ? t : Object.entries(t));

export class Asset {
  constructor(model, i, name, type, size) {
    this.model = model;
    this.index = i;
    /** the file name, `a<id>.jpg` or `a<id>.png` (what `external` HTML refers to) */
    this.name = name;
    /** `image/jpeg` or `image/png` */
    this.type = type;
    /** the encoded size in bytes */
    this.size = size;
  }

  /** The encoded bytes: a copy in a `Uint8Array` (an embedded JPEG's own bytes, or the canonical PNG of its pixels). */
  bytes() {
    const w = this.model.w;
    const n = w.x.emon_model_asset_bytes(this.model.h, this.index);
    if (n < 0) throw new EmonError('internal', 'no such asset');
    const b = w.outBytes();
    w.outFree();
    return b;
  }

  /** The same as a `Blob` of the asset's media type (made from the module's memory: the page's heap holds no copy). */
  blob() {
    const w = this.model.w;
    const n = w.x.emon_model_asset_bytes(this.model.h, this.index);
    if (n < 0) throw new EmonError('internal', 'no such asset');
    const blob = new Blob([w.outView()], { type: this.type });
    w.outFree();
    return blob;
  }
}

const registry = typeof FinalizationRegistry !== 'undefined' ? new FinalizationRegistry(({ w, h }) => w.x.emon_model_free(h)) : null;

export class Model {
  constructor(w, h, stats, evidenceKept, transcript) {
    this.w = w;
    this.h = h;
    this.stats = stats;
    this._evidence = evidenceKept;
    this._transcript = transcript;
    this._assets = null;
    registry?.register(this, { w, h }, this);
  }

  _text(rc) {
    if (rc < 0) throw new EmonError('internal', this.w.outText());
    const s = this.w.outText();
    this.w.outFree();
    return s;
  }

  /** SPEC 4: emon JSON, as text. */
  json({ pretty = false } = {}) {
    return this._text(this.w.x.emon_model_json(this.h, pretty ? 1 : 0));
  }

  /** The JSON, parsed. */
  object() {
    return JSON.parse(this.json());
  }

  /**
   * SPEC 5: HTML. Options: `ids`, `geometry`, `confidenceBelow` (0..1000), `ledger`, `pretty`, `furnitureDrop`, `milestones`
   * (default true), `initialsPlain`, `alignStyle`, `assets`: `inline` (data: URIs, the default), `external` (the HTML names each
   * asset file by `assetPrefix + name`, and the caller serves them: `model.assets[i].blob()`) or `omit`; `assetPrefix`.
   */
  html(o = {}) {
    const L = [];
    if (o.ids) L.push('ids');
    if (o.geometry) L.push('geometry');
    if (o.confidenceBelow !== undefined) L.push(`confidence=${o.confidenceBelow}`);
    if (o.ledger) L.push('ledger');
    if (o.pretty) L.push('pretty');
    if (o.furnitureDrop) L.push('drop-furniture');
    if (o.milestones === false) L.push('no-milestones');
    if (o.initialsPlain) L.push('initials-plain');
    if (o.alignStyle) L.push('align-style');
    if (o.assets) L.push(`assets=${o.assets}`);
    if (o.assetPrefix) L.push(`prefix=${o.assetPrefix}`);
    const w = this.w;
    return this._text(w.withText(L.join(','), (p, n) => w.x.emon_model_html(this.h, p, n)));
  }

  /**
   * `external` mode in the browser's form: the HTML refers to each asset by an object URL of its Blob. `revoke()` frees them.
   * @returns {{ html: string, urls: Map<string, string>, revoke(): void }}
   */
  htmlWithBlobURLs(o = {}) {
    const prefix = 'emon-asset:';
    let html = this.html({ ...o, assets: 'external', assetPrefix: prefix });
    const urls = new Map();
    for (const a of this.assets) {
      const u = URL.createObjectURL(a.blob());
      urls.set(a.name, u);
      html = html.split(prefix + a.name).join(u);
    }
    return { html, urls, revoke: () => urls.forEach((u) => URL.revokeObjectURL(u)) };
  }

  /** The text layer's evidence dump, when the conversion was asked to `keepEvidence`. */
  evidence() {
    return this._text(this.w.x.emon_model_evidence(this.h));
  }

  /** The Model's assets (SPEC 10.11, D117; empty unless `assets` was on), in admission order. */
  get assets() {
    if (!this._assets) {
      const w = this.w;
      const n = w.x.emon_model_asset_count(this.h);
      this._assets = [];
      for (let i = 0; i < n; i++) {
        w.x.emon_model_asset_info(this.h, i);
        const [name, type, size] = w.outText().split('\n');
        this._assets.push(new Asset(this, i, name, type, Number(size)));
      }
    }
    return this._assets;
  }

  /** The plan/fulfil transcript this conversion recorded (`record: true`): a Map of file name to bytes. */
  transcript() {
    if (!this._transcript) throw new EmonError('usage', 'this conversion did not record (record: true)');
    return this._transcript;
  }

  /** Free the Model's memory (the WASM heap's: it is not the garbage collector's until a finalizer runs). */
  dispose() {
    if (this.h) {
      registry?.unregister(this);
      this.w.x.emon_model_free(this.h);
      this.h = 0;
    }
  }

  [Symbol.dispose]() {
    this.dispose();
  }
}

export class Emon {
  /**
   * @param {ArrayBuffer|Uint8Array|WebAssembly.Module|Response|Promise<Response>|string|URL} wasm the module (`emon.wasm`)
   * @param {{ chunk?: number }} [defaults] chunk: the byte source's chunk size (default 64 KiB)
   */
  static async load(wasm, defaults = {}) {
    return new Emon(await Wasm.instantiate(wasm), defaults);
  }

  constructor(w, defaults = {}) {
    this.w = w;
    this.defaults = defaults;
  }

  _open(source, password, mode, options) {
    const w = this.w;
    const chunk = options.chunk ?? this.defaults.chunk ?? 65536;
    const pw = typeof password === 'string' ? new TextEncoder().encode(password) : password || new Uint8Array(0);
    const text = new TextEncoder().encode(optionLines(options, chunk, !!options.record));
    const h = w.withBytes(pw, (pp, pl) => w.withBytes(text, (op, ol) => w.x.emon_open(source.size, chunk, mode, pp, pl, op, ol)));
    if (h < 0) throw this._error(h);
    return { h, chunk };
  }

  _error(rc) {
    const [code, ...rest] = this.w.outText().split('\n');
    this.w.outFree();
    return new EmonError(code, rest.join('\n') || code);
  }

  _provide(h, offset, u8, chunk) {
    const w = this.w;
    const step = Math.max(chunk, Math.floor(STAGE / chunk) * chunk);
    for (let o = 0; o < u8.length; o += step) {
      const part = u8.subarray(o, Math.min(o + step, u8.length));
      w.withBytes(part, (p, l) => w.x.emon_provide(h, offset + o, p, l));
    }
  }

  async _fetch(h, source, ranges, chunk, ctx) {
    const w = this.w;
    let next = 0;
    let failed = false;
    const one = async () => {
      while (next < ranges.length && !failed) {
        const { offset, length } = ranges[next++];
        let data;
        try {
          data = await source.read(offset, length);
          ctx.reads++;
          if (!(data instanceof Uint8Array)) data = ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data);
          ctx.bytesRead += data.length;
        } catch (e) {
          failed = true;
          const short = !!(e && (e.code === 'source-changed' || e.name === 'SourceChangedError'));
          w.withText(String((e && e.message) || e), (p, n) => w.x.emon_fail(h, short ? 1 : 0, p, n));
          return;
        }
        if (data.length !== length) {
          failed = true;
          w.withText(`the source gave ${data.length} bytes at offset ${offset} and its length said ${length}`, (p, n) => w.x.emon_fail(h, 1, p, n));
          return;
        }
        this._provide(h, offset, data, chunk);
      }
    };
    await Promise.all(Array.from({ length: Math.min(READS, ranges.length) }, one));
  }

  async _fulfil(h, reqs, ports, ctx) {
    const w = this.w;
    const k = w.kind;
    await Promise.all(
      reqs.map(async (rec) => {
        const kind = rec[1];
        const failed = (status) => w.withBytes(new Uint8Array(Int32Array.of(4, kind, rec[2], status).buffer), (p) => w.x.emon_answer(h, p, 4));
        if (kind === k.ocr && ports && ports.ocr) {
          const bitmapId = rec[3];
          const req = ocrRequest(rec, () => {
            const n = w.x.emon_bitmap_png(h, bitmapId);
            if (n < 0) throw new EmonError('internal', 'the ocr request names no bitmap');
            const b = w.outBytes();
            w.outFree();
            return b;
          });
          let result;
          try {
            result = await ports.ocr(req);
          } catch (e) {
            ctx.notes.push(`ocr ${rec[2]} failed: ${(e && e.message) || e}`);
            failed(w.status.failed);
            return;
          }
          if (result === null || result === undefined) {
            failed(w.status.unsupported);
            return;
          }
          const cells = encodeOcr(result, w.ocrWord);
          const rc = w.withBytes(new Uint8Array(cells.buffer), (p) => w.x.emon_answer_ocr(h, rec[2], p, cells.length));
          if (rc < 0) throw this._error(rc);
          return;
        }
        // nothing in this host fulfils it (the engine does not route a request here it has no port for)
        failed(w.status.unsupported);
      }),
    );
  }

  async _run(h, source, chunk, options, mode) {
    const w = this.w;
    const ctx = { reads: 0, bytesRead: 0, notes: [] };
    try {
      if (options.transcript) {
        for (const [name, data] of entriesOf(options.transcript)) {
          const u8 = data instanceof Uint8Array ? data : new Uint8Array(data);
          const rc = w.withText(name, (np, nl) => w.withBytes(u8, (dp, dl) => w.x.emon_replay_file(h, np, nl, dp, dl)));
          if (rc < 0) throw this._error(rc);
        }
        const rc = w.x.emon_replay_end(h);
        if (rc < 0) throw this._error(rc);
      }
      if (source.bytes && !options.lazy) {
        this._provide(h, 0, source.bytes, chunk);
        ctx.reads++;
        ctx.bytesRead += source.bytes.length;
      } else {
        w.x.emon_prefetch(h);
        await this._fetch(h, source, pairs(w.outI32()), chunk, ctx);
      }
      for (;;) {
        const st = w.x.emon_step(h);
        if (st === 0) return ctx;
        if (st === 1) {
          await this._fetch(h, source, pairs(w.outI32()), chunk, ctx);
          continue;
        }
        if (st === 2) {
          const v = w.outBytes();
          const dv = new DataView(v.buffer, v.byteOffset, v.byteLength);
          const n = dv.getInt32(0, true);
          const reqs = [];
          let at = 4;
          for (let i = 0; i < n; i++) {
            const len = dv.getInt32(at, true);
            at += 4;
            const rec = [];
            for (let j = 0; j < len; j++, at += 4) rec.push(dv.getInt32(at, true));
            reqs.push(rec);
          }
          await this._fulfil(h, reqs, options.ports, ctx);
          continue;
        }
        throw this._error(st);
      }
    } catch (e) {
      w.x.emon_close(h);
      throw e;
    }
  }

  _input(input) {
    if (input && typeof input === 'object' && 'source' in input && !('read' in input) && !(input instanceof Blob)) return { src: input.source, password: input.password };
    return { src: input, password: undefined };
  }

  /**
   * Convert one input (SPEC 13: stages 0-3) to a Model. An input is anything `toSource` takes, or `{ source, password }`.
   * Options are SPEC 13's settings by their names (`locale`, `ocr`, `furniture`, `assets`, `assetDpi`, `assetPixels`,
   * `assetBytes`, `assetsBytes`, `assetSignatures`), `limits` (`{ 'input-bytes': n, ... }`), `maxRounds`, `fetchRounds`, `chunk`,
   * `keepEvidence`, `lazy` (read even an in-memory input by range), `ports: { ocr }`, `transcript` (replay: a Map of file name
   * to bytes: no port is called) and `record` (the Model then has `transcript()`).
   */
  async convert(input, options = {}) {
    const { src, password } = this._input(input);
    const source = await toSource(src, { lazy: options.lazy, max: options.limits?.['input-bytes'] ?? 1 << 30 });
    const { h, chunk } = this._open(source, password, 0, options);
    const ctx = await this._run(h, source, chunk, options, 0);
    const w = this.w;
    let transcript = null;
    if (options.record) {
      if (w.x.emon_transcript(h) < 0) throw new EmonError('internal', 'no transcript');
      const v = w.outBytes();
      const dv = new DataView(v.buffer, v.byteOffset, v.byteLength);
      const dec = new TextDecoder();
      transcript = new Map();
      let at = 4;
      for (let i = 0, n = dv.getUint32(0, true); i < n; i++) {
        const nl = dv.getUint32(at, true);
        const name = dec.decode(v.subarray(at + 4, at + 4 + nl));
        at += 4 + nl;
        const dl = dv.getUint32(at, true);
        transcript.set(name, v.slice(at + 4, at + 4 + dl));
        at += 4 + dl;
      }
    }
    const mh = w.x.emon_finish(h);
    if (mh < 0) {
      const e = this._error(mh);
      w.x.emon_close(h);
      throw e;
    }
    const s = w.outI32();
    w.x.emon_close(h);
    const stats = { attempts: s[0], fetchRounds: s[1], bytesRequests: s[2], requests: s[3], sourceReads: s[4], bytesFetched: s[5], inputBytes: s[6], reads: ctx.reads, bytesRead: ctx.bytesRead, notes: ctx.notes };
    return new Model(w, mh, stats, !!options.keepEvidence, transcript);
  }

  /** Stage 0 of each input, cheap: `{ format, version, pages, encrypted, bytes, line }` (the line is the drivers'). */
  async intake(inputs, options = {}) {
    const list = Array.isArray(inputs) ? inputs : [inputs];
    const out = [];
    for (const input of list) {
      const { src, password } = this._input(input);
      const source = await toSource(src, { lazy: options.lazy, max: options.limits?.['input-bytes'] ?? 1 << 30 });
      const { h, chunk } = this._open(source, password, 1, { ...options, ports: undefined, record: false, transcript: undefined });
      await this._run(h, source, chunk, { ...options, ports: undefined, transcript: undefined }, 1);
      const rc = this.w.x.emon_finish_intake(h);
      if (rc < 0) {
        const e = this._error(rc);
        this.w.x.emon_close(h);
        throw e;
      }
      const line = this.w.outText();
      this.w.x.emon_close(h);
      // (a damaged header can have a version that is not a number: `pdf -38.-16`, as the engine says it)
      const m = /^(\S+) (-?\d+)\.(-?\d+) pages=(\d+) encrypted=(true|false) bytes=(\d+)$/.exec(line);
      out.push({ format: m[1], version: [Number(m[2]), Number(m[3])], pages: Number(m[4]), encrypted: m[5] === 'true', bytes: Number(m[6]), line });
    }
    return out;
  }
}
