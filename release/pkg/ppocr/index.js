// No default URLs, automatic inference downloads or required ORT dependency.
export { ppocrPort, ppocrAdapter, ppocrLexicon } from './ppocr.js';

/** Read the pinned model payload from a caller-selected native release tar.gz.
 * Keep ORT's platform files out of the browser filesystem. The port and lexicon
 * verify their own SHA-256 pins before using any returned bytes.
 */
export async function ppocrAssets(source) {
  if (typeof source === 'string' || source instanceof URL) {
    const response = await fetch(source);
    if (!response.ok) throw new Error(`ppocr-models-missing: ${response.status}`);
    source = response.body;
  }
  const stream = source instanceof ReadableStream ? source : new Blob([source]).stream();
  const reader = stream.pipeThrough(new DecompressionStream('gzip')).getReader();
  const chunks = [];
  let size = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 512 * 1024 * 1024) throw new Error('ppocr-archive-too-large');
      chunks.push(value);
    }
  } finally { await reader.cancel(); }
  const tar = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { tar.set(chunk, offset); offset += chunk.length; }
  const names = new Set(['ch_PP-OCRv5_det_mobile.onnx',
    'ch_ppocr_mobile_v2.0_cls_mobile.onnx', 'en_PP-OCRv5_rec_mobile.onnx', 'eng.traineddata']);
  const files = new Map(), decoder = new TextDecoder();
  const text = bytes => decoder.decode(bytes).replace(/\0.*$/s, '');
  for (let pos = 0; pos + 512 <= tar.length;) {
    const header = tar.subarray(pos, pos + 512);
    if (header.every(x => x === 0)) break;
    const name = text(header.subarray(0, 100));
    const length = Number.parseInt(text(header.subarray(124, 136)).trim(), 8);
    if (!Number.isSafeInteger(length) || length < 0 || pos + 512 + length > tar.length)
      throw new Error('ppocr-bad-archive');
    const leaf = name.split('/').pop();
    if ((header[156] === 0 || header[156] === 48) && /\/models\/[^/]+$/.test(name) && names.has(leaf)) {
      if (files.has(leaf)) throw new Error('ppocr-duplicate-asset');
      files.set(leaf, tar.slice(pos + 512, pos + 512 + length));
    }
    pos += 512 + Math.ceil(length / 512) * 512;
  }
  if (files.size !== names.size) throw new Error('ppocr-models-missing');
  const traineddata = files.get('eng.traineddata'); files.delete('eng.traineddata');
  return { models: Object.fromEntries(files), traineddata };
}
