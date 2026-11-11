export { ppocrPort, ppocrAdapter, ppocrLexicon } from './ppocr.js';
/** Caller-selected native release .tar.gz URL, compressed bytes or byte stream.
 * The PP port and lexicon verify all returned model bytes before inference.
 */
export function ppocrAssets(source: string | URL | Uint8Array | ArrayBuffer | ReadableStream<Uint8Array>): Promise<{
  models: Record<'ch_PP-OCRv5_det_mobile.onnx' | 'ch_ppocr_mobile_v2.0_cls_mobile.onnx' | 'en_PP-OCRv5_rec_mobile.onnx', Uint8Array>;
  traineddata: Uint8Array;
}>;
