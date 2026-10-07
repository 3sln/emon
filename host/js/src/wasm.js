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
