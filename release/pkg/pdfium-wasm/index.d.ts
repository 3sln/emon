/** pdfium's factory: call it once with `{ wasmBinary, print, printErr }` (all optional); it resolves to the instantiated module (`_malloc`, `_FPDF_*`, `HEAPU8`). */
declare function pdfium(Module?: Record<string, unknown>, node?: { require?: unknown; dirname?: string }): Promise<Record<string, any>>;
export default pdfium;
/** The URL of `pdfium.wasm`, beside this package's `index.js`. */
export const wasmUrl: URL;
/** The bytes of `pdfium.wasm`: read from disk in Node, fetched from `wasmUrl` in a browser. */
export function wasmBinary(): Promise<Uint8Array>;
