import { readFile, writeFile } from 'node:fs/promises';
import { createWorker } from 'tesseract.js';
import { Emon } from '@3sln/emon';
import { pdfiumRenderer } from '@3sln/emon/pdfium';
import { tesseractPort } from '@3sln/emon/tesseract';

const emon = await Emon.load(await readFile(new URL(import.meta.resolve('@3sln/emon/emon.wasm'))));

// pdfium's WebAssembly build: pdfium.mjs (the archive's pdfium.js wrapped as an ES module) and pdfium.wasm
const render = pdfiumRenderer({
  module: () => import('./vendor/pdfium/pdfium.mjs'),
  wasm: await readFile('./vendor/pdfium/pdfium.wasm'),
});

// tesseract.js, with the English model from the npm package @tesseract.js-data/eng
const ocr = tesseractPort({
  createWorker,
  languages: ['eng'],
  workerOptions: { langPath: './node_modules/@tesseract.js-data/eng/4.0.0_best_int', gzip: true, cacheMethod: 'none' },
});

const model = await emon.convert(await readFile('scan.pdf'), { ports: { render, ocr } });
await writeFile('scan.html', model.html());
model.dispose();
await render.terminate();
await ocr.terminate();
