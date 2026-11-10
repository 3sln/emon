// JS recording/native replay interoperability driver; invoked by bb.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { Emon } from '../../host/js/src/index.js';
import { tesseractPort } from '../../host/js/src/tesseract.js';
import { ppocrPort, ppocrLexicon } from '../../host/js/src/ppocr.js';
const require = createRequire(new URL('../../host/js/package.json', import.meta.url));
const [mode, input, recording, output] = process.argv.slice(2);
const emonWasm = fs.readFileSync('.cache/emon-wasm-ppocr/target/wasm32-unknown-unknown/release/emon.wasm');
const emon = await Emon.load(emonWasm);
let ocr, model, ortLoads = 0;
const options = { ocrEngine: 'ppocr', keepEvidence: true };
if (mode === 'record') {
  const ortPath = require.resolve('onnxruntime-web/wasm');
  const corePath = require.resolve('tesseract.js-core/tesseract-core-simd-lstm.wasm.js');
  const incumbent = tesseractPort({ createWorker: require('tesseract.js').createWorker,
    workerOptions: { langPath: path.resolve('.cache/tessdata'), gzip: false, corePath, cacheMethod: 'none' } });
  const lexicon = ppocrLexicon({ createCore: require(corePath),
    coreOptions: { wasmBinary: fs.readFileSync(corePath.replace(/\.wasm\.js$/, '.wasm')) },
    traineddata: fs.readFileSync('.cache/tessdata/eng.traineddata') });
  const models = Object.fromEntries(['ch_PP-OCRv5_det_mobile.onnx', 'ch_ppocr_mobile_v2.0_cls_mobile.onnx', 'en_PP-OCRv5_rec_mobile.onnx']
    .map(n => [n, fs.readFileSync(`.cache/ppocr/${n}`)]));
  ocr = ppocrPort({ incumbent, wasm: emonWasm, lexicon, models,
    loadOrt: () => { ortLoads++; return import(pathToFileURL(ortPath)); }, wasmPaths: path.dirname(ortPath) + '/',
    ortWasm: fs.readFileSync(path.join(path.dirname(ortPath), 'ort-wasm-simd-threaded.wasm')) });
  options.ports = { ocr }; options.record = true; options.producer = ocr.producer;
} else if (mode === 'replay' || mode === 'replay-default') {
  if (mode === 'replay-default') options.ocrEngine = 'tesseract';
  options.transcript = new Map(fs.readdirSync(recording).filter(n => !n.startsWith('.')).map(n => [n, fs.readFileSync(path.join(recording, n))]));
} else { throw new Error('usage: web_replay.mjs record|replay INPUT RECORDING OUTPUT'); }
try {
  model = await emon.convert(fs.readFileSync(input), options);
  if (mode === 'record' && ortLoads === 0) throw new Error('fixture did not exercise the PP runtime');
  const intake = (await emon.intake(fs.readFileSync(input))).map(x => x.line).join('\n');
  let bundle = '== json\n' + model.json() + '\n';
  bundle += '== json-pretty\n' + model.json({ pretty: true }) + '\n';
  bundle += '== html\n' + model.html() + '\n';
  bundle += '== html-full\n' + model.html({ ids: true, geometry: true, confidenceBelow: 950, ledger: true, pretty: true }) + '\n';
  bundle += '== evidence\n' + model.evidence() + '\n';
  bundle += '== intake\n' + intake + '\n';
  fs.writeFileSync(output, bundle);
  if (mode === 'record') {
    fs.mkdirSync(recording, { recursive: true });
    for (const [name, bytes] of model.transcript()) fs.writeFileSync(path.join(recording, name), bytes);
  }
} catch (error) { console.error(error.code || error.name); throw error; }
finally { model?.dispose(); await ocr?.terminate(); }
