// Actual Node wasm/ORT path. Orchestration and fixture preparation live in bb.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { ppocrAdapter, ppocrLexicon } from '../../host/js/src/ppocr.js';
const require = createRequire(new URL('../../host/js/package.json', import.meta.url));
const out = process.argv[2];
const ortPath = require.resolve('onnxruntime-web/wasm');
const corePath = require.resolve('tesseract.js-core/tesseract-core-simd-lstm.wasm.js');
const lexicon = ppocrLexicon({ createCore: require(corePath),
  coreOptions: { wasmBinary: fs.readFileSync(corePath.replace(/\.wasm\.js$/, '.wasm')) },
  traineddata: fs.readFileSync('.cache/tessdata/eng.traineddata') });
const models = Object.fromEntries(['ch_PP-OCRv5_det_mobile.onnx', 'ch_ppocr_mobile_v2.0_cls_mobile.onnx', 'en_PP-OCRv5_rec_mobile.onnx']
  .map(n => [n, fs.readFileSync(`.cache/ppocr/${n}`)]));
const read = ppocrAdapter({ wasm: fs.readFileSync('.cache/emon-wasm-ppocr/target/wasm32-unknown-unknown/release/emon.wasm'),
  loadOrt: () => import(pathToFileURL(ortPath)), wasmPaths: path.dirname(ortPath) + '/',
  ortWasm: fs.readFileSync(path.join(path.dirname(ortPath), 'ort-wasm-simd-threaded.wasm')), models, lexicon });
const rows = JSON.parse(fs.readFileSync(`${out}/manifest.json`));
try {
  for (let i = 0; i < rows.length; i++) {
    const answer = await read({ png: fs.readFileSync(rows[i].file) });
    fs.appendFileSync(`${out}/web.jsonl`, JSON.stringify(Array.from(answer)) + '\n');
    console.log(`${i + 1}/${rows.length}`);
  }
} finally {
  fs.writeFileSync(`${out}/web-resources.json`, JSON.stringify({ resourceUsage: process.resourceUsage(), memoryUsage: process.memoryUsage() }, null, 2));
  await read.terminate();
}
