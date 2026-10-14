// The WASM binding's wrapper (DEVLOG P9.3): the module's C ABI (`host/wasm/src/wasm.rs`) as methods. The module has no imports,
// so instantiating it needs nothing of the page: no filesystem, no network, no glue. Everything here is integers and byte ranges
// of the module's memory; `memory.buffer` is re-read after every call because a call may grow it.

export class Wasm {
  /** @param {WebAssembly.Instance} instance */
  constructor(instance) {
    this.x = instance.exports;
    this.kind = { render: this.x.emon_const(0), decode: this.x.emon_const(1), ocr: this.x.emon_const(2), bytes: this.x.emon_const(3), crop: this.x.emon_const(4), decodeImg: this.x.emon_const(5) };
    this.status = { ok: this.x.emon_const(10), failed: this.x.emon_const(11), unsupported: this.x.emon_const(12), limit: this.x.emon_const(13) };
    this.ocrWord = { dict: this.x.emon_const(14), numeric: this.x.emon_const(15) };
    // the argument indices of a `render` / `crop` request (kin's `rd-*`; the record is `[len kind id arg...]`): read a request by these, never by a literal offset
    this.rd = { input: this.x.emon_const(16), page: this.x.emon_const(17), dpi: this.x.emon_const(18), layer: this.x.emon_const(19), x: this.x.emon_const(20), y: this.x.emon_const(21), w: this.x.emon_const(22), h: this.x.emon_const(23) };
    // kin's table of limits, by its kind: `emon_const(100 + K)` is the default of limit K (`input-bytes` is 0), so no default is typed here
    this.inputBytes = this.x.emon_const(100);
    // the kind (the row of kin's table of limits, `kin/limits.kin`) of `pixels`, the bound a renderer's bitmap keeps: `emon_limit(h, pixelsKind)` is the
    // conversion's own (the default or its `limit.pixels`). A kind is a row index, not a default; `bb embedders` holds this to the table.
    this.pixelsKind = 9;
  }

  /**
   * Instantiate the module from bytes, a compiled module, a `Response` (streamed), a promise of one, or a URL.
   * @param {ArrayBuffer|Uint8Array|WebAssembly.Module|Response|Promise<Response>|string|URL} src
   */
  static async instantiate(src) {
    if (typeof src === 'string' || src instanceof URL) src = fetch(src);
    src = await src;
    if (src instanceof WebAssembly.Module) return new Wasm(await WebAssembly.instantiate(src, {}));
    if (typeof Response !== 'undefined' && src instanceof Response) {
      if (WebAssembly.instantiateStreaming && (src.headers.get('content-type') || '').startsWith('application/wasm')) {
        return new Wasm((await WebAssembly.instantiateStreaming(src, {})).instance);
      }
      src = await src.arrayBuffer();
    }
    return new Wasm((await WebAssembly.instantiate(src, {})).instance);
  }

  // THE OCR POLICY IS KIN'S (`emon.ocrport`, P10.7, D396): these ask the module what an `ocr` request means.
  /** The argument index of the request record's first language. */
  ocrLangFirst(rec) {
    return this.withBytes(new Uint8Array(Int32Array.from(rec).buffer), (p) => this.x.emon_ocr_lang_first(p, rec.length));
  }

  /** Which of the `installed` model names read the request record: indices into `installed` (all of them when it names none). */
  ocrModels(rec, installed) {
    const names = [];
    installed.forEach((s, i) => {
      if (i) names.push(0);
      for (const ch of s) names.push(ch.codePointAt(0));
    });
    const n = this.withBytes(new Uint8Array(Int32Array.from(rec).buffer), (p) =>
      this.withBytes(new Uint8Array(Int32Array.from(names).buffer), (ip) => this.x.emon_ocr_models(p, rec.length, ip, names.length)),
    );
    const out = this.outI32();
    this.outFree();
    return n < 0 ? [] : Array.from(out);
  }

  /** The language a model read a word in, as a request packs it. */
  ocrWordLang(model) {
    const cps = Int32Array.from(Array.from(model, (c) => c.codePointAt(0)));
    return this.withBytes(new Uint8Array(cps.buffer), (p) => this.x.emon_ocr_word_lang(p, cps.length));
  }

  /** A line's type (0 text, 1 table, 2 image, 3 rule, 4 other) from Tesseract's PolyBlockType. */
  ocrBlockType(poly) {
    return this.x.emon_ocr_block_type(poly | 0);
  }

  get mem() {
    return new Uint8Array(this.x.memory.buffer);
  }

  /** Copy `u8` into the module's memory and run `f(ptr, len)`; the copy is freed after. */
  withBytes(u8, f) {
    const p = this.x.emon_alloc(u8.length);
    try {
      this.mem.set(u8, p);
      return f(p, u8.length);
    } finally {
      this.x.emon_free(p, u8.length);
    }
  }

  withText(s, f) {
    return this.withBytes(new TextEncoder().encode(s), f);
  }

  /** The shared output buffer, as a view of the module's memory (valid until the next call) */
  outView() {
    return new Uint8Array(this.x.memory.buffer, this.x.emon_out_ptr(), this.x.emon_out_len());
  }

  /** The output as bytes in the JS heap (a copy). */
  outBytes() {
    return this.outView().slice();
  }

  outText() {
    return new TextDecoder().decode(this.outView());
  }

  outI32() {
    const v = this.outView();
    return new Int32Array(v.buffer.slice(v.byteOffset, v.byteOffset + v.byteLength));
  }

  outFree() {
    this.x.emon_out_free();
  }
}
