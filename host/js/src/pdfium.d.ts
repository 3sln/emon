// Type declarations of `@3sln/emon/pdfium` (P11.4, D542).
import type { Renderer } from './index.js';

export function pdfiumRenderer(options?: {
  /** pdfium's factory (`lib/pdfium.mjs`'s default export), a function that returns it, or a promise of either: called once, on the first render.
   *  Omitted: the package `@3sln/pdfium-wasm` (an optional peer dependency), imported on the first render; its .wasm is then the default `wasm`. */
  module?: Function | Promise<unknown>;
  /** `pdfium.wasm`: its bytes, its URL, or a `Response` */
  wasm?: ArrayBuffer | Uint8Array | string | URL | Response | Promise<unknown>;
  /** the largest page, in pixels, this renderer will make (default 64 Mpx) */
  maxPixels?: number;
}): Renderer & { layers: number; producer: Record<string, string>; terminate(): Promise<void> };
