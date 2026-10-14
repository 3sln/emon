// @3sln/pdfium-wasm: pdfium's WebAssembly build (bblanchon/pdfium-binaries, chromium/8076), as emon's `@3sln/emon/pdfium` wants it.
//   default      pdfium's factory: `pdfium({ wasmBinary, print, printErr })` resolves to the instantiated module (`_malloc`, `_FPDF_*`, `HEAPU8`)
//   wasmUrl      the URL of `pdfium.wasm` beside this file
//   wasmBinary   an async function: the bytes of `pdfium.wasm` (read from disk in Node, fetched in a browser)
// `pdfium.mjs` is the archive's `pdfium.js`, text unchanged, inside `export default function pdfium(Module, node)` (a classic script has no export). In
// Node that script reaches for `require` and `__dirname`; the factory below hands it Node's, so a caller does not (a browser or a Worker passes nothing).
import factory from './pdfium.mjs';

const inNode = typeof process !== 'undefined' && !!(process.versions && process.versions.node) && typeof window === 'undefined' && typeof importScripts === 'undefined';

export default async function pdfium(Module, node) {
  if (node === undefined && inNode) {
    // computed specifiers: a browser bundler does not follow them
    const [mod, url] = ['node:module', 'node:url'];
    const { createRequire } = await import(mod);
    const { fileURLToPath } = await import(url);
    node = { require: createRequire(import.meta.url), dirname: fileURLToPath(new URL('.', import.meta.url)) };
  }
  return factory(Module, node);
}

export const wasmUrl = new URL('./pdfium.wasm', import.meta.url);

export async function wasmBinary() {
  if (inNode) {
    const name = 'node:fs/promises';
    return new Uint8Array(await (await import(name)).readFile(wasmUrl));
  }
  const r = await fetch(wasmUrl);
  if (!r.ok) throw new Error(`pdfium.wasm: HTTP ${r.status}`);
  return new Uint8Array(await r.arrayBuffer());
}
