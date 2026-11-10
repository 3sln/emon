// Headless Chrome uses the same optional bridge and pinned runtime as Node.
import fs from 'node:fs';
import { launch } from '../../test/driver/wasm/browser.mjs';
import { startServer } from '../../test/driver/wasm/server.mjs';
const out = process.argv[2];
const server = await startServer({ wasm: '.cache/emon-wasm-ppocr/target/wasm32-unknown-unknown/release/emon.wasm' });
let browser;
try {
  browser = await launch();
  const page = await browser.newPage();
  await page.goto(`${server.origin}/repo/test/driver/wasm/page.html`);
  await page.addScriptTag({ url: `${server.origin}/repo/host/js/node_modules/tesseract.js-core/tesseract-core-simd-lstm.wasm.js` });
  await page.evaluate(async () => {
    const { ppocrAdapter, ppocrLexicon } = await import('/sdk/ppocr.js');
    const base = location.origin + '/repo/';
    const lexicon = ppocrLexicon({ createCore: window.TesseractCore,
      coreOptions: { locateFile: name => base + 'host/js/node_modules/tesseract.js-core/' + name },
      traineddata: base + '.cache/tessdata/eng.traineddata' });
    const models = Object.fromEntries(['ch_PP-OCRv5_det_mobile.onnx', 'ch_ppocr_mobile_v2.0_cls_mobile.onnx', 'en_PP-OCRv5_rec_mobile.onnx']
      .map(n => [n, base + '.cache/ppocr/' + n]));
    window.pp = ppocrAdapter({ wasm: '/emon.wasm',
      loadOrt: () => import('/repo/host/js/node_modules/onnxruntime-web/dist/ort.wasm.min.mjs'),
      wasmPaths: base + 'host/js/node_modules/onnxruntime-web/dist/',
      ortWasm: base + 'host/js/node_modules/onnxruntime-web/dist/ort-wasm-simd-threaded.wasm', models, lexicon });
  });
  fs.writeFileSync(`${out}/browser.jsonl`, '');
  const rows = JSON.parse(fs.readFileSync(`${out}/manifest.json`));
  for (let i = 0; i < rows.length; i++) {
    server.add('pp.png', fs.readFileSync(rows[i].file));
    const answer = await page.evaluate(async () => Array.from(await window.pp({ png: new Uint8Array(await (await fetch('/gen/pp.png')).arrayBuffer()) })));
    fs.appendFileSync(`${out}/browser.jsonl`, JSON.stringify(answer) + '\n');
    console.log(`${i + 1}/${rows.length}`);
  }
  await page.evaluate(() => window.pp.terminate());
} finally { await browser?.close(); await server.close(); }
