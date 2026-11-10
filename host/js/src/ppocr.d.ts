import type { OcrPort } from './index.js';

type AssetSource = string | URL | Uint8Array | ArrayBuffer;
type WasmSource = AssetSource | WebAssembly.Module | Response | Promise<Response>;
type Lexicon = {
  bindWasm(memory: WebAssembly.Memory): Promise<(ptr: number, len: number) => number>;
  terminate?(): Promise<void>;
};
interface AdapterOptions {
  wasm: WasmSource;
  /** A lazy import of onnxruntime-web/wasm, exactly 1.22.0. */
  loadOrt(): Promise<unknown>;
  wasmPaths: string | { wasm?: string | URL; mjs?: string | URL };
  /** Hash-verified ort-wasm-simd-threaded.wasm from ORT Web 1.22.0. */
  ortWasm: AssetSource;
  models: Record<'ch_PP-OCRv5_det_mobile.onnx' | 'ch_ppocr_mobile_v2.0_cls_mobile.onnx' | 'en_PP-OCRv5_rec_mobile.onnx', AssetSource>;
  lexicon: Lexicon;
}

/** Experimental optional port; requires the optional emon wasm build. */
export function ppocrPort(options: AdapterOptions & { incumbent: OcrPort & { terminate?(): Promise<void>; producer?: Record<string, string> } }):
  OcrPort & { terminate(): Promise<void>; producer: Record<string, string> };

/** Dedicated pinned English dictionary. Supply tesseract.js-core 7.0.0's
 * ordinary SIMD LSTM factory and local runtime options. */
export function ppocrLexicon(options: {
  createCore(options?: object): Promise<any>;
  coreOptions?: object;
  traineddata: AssetSource;
}): Lexicon & { terminate(): Promise<void> };

/** Low-level qualification adapter; returns canonical integer answer cells. */
export function ppocrAdapter(options: AdapterOptions):
  ((request: { png: Uint8Array; id?: number; rect?: { x: number; y: number; w: number; h: number } | null; record?: Int32Array }) => Promise<Int32Array | null>) & { terminate(): Promise<void> };
