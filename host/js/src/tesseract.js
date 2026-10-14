// tesseract.js AS emon's `ocr` PORT (DEVLOG P9.3, SPEC 7.3, D211-D214): the browser's OCR engine, fulfilling `ocr` requests through
// plan/fulfil. `tesseract.js` is not a dependency of the SDK: the embedder imports it and hands over `createWorker`, which keeps
// the engine's code, its core WebAssembly and its language data (large, and the embedder's to host or cache) out of the library.
//
//     import { createWorker } from 'tesseract.js';
//     import { Emon } from '@3sln/emon';
//     import { tesseractPort } from '@3sln/emon/tesseract';
//
//     const ocr = tesseractPort({ createWorker, languages: ['eng'], workerOptions: { langPath: '/tessdata', corePath: '/tesseract-core' } });
//     const model = await emon.convert(scan, { ports: { ocr } });
//     await ocr.terminate();
//
// WHAT IT GIVES AND WHAT IT CANNOT. tesseract.js reports the page as blocks, paragraphs, lines (with their baselines), words and
// symbols, each with a box and a confidence, and a block's type: that is the answer, in the form `ocr.js` documents. It does not
// report what the native C API does (D213): a block's orientation and writing direction (answered as page up, left to right, top
// to bottom: the engine is not run with orientation detection here, so a request for OSD, bit 2 of the flags, is answered without
// one), a word's language, dictionary and numeric flags, font attributes and point size (answered as the request's first language,
// not in the dictionary, not numeric, no style, size unknown), a symbol's alternatives (its `choices` are not in the JSON), and
// the engine's thresholded image (the answer says it has none). A line's x-height, ascenders and descenders are answered as -1:
// kin then measures them from the symbols' boxes, as it does for the native engine (D213). None of that is guessed.

const box = (b) => [b.x0, b.y0, b.x1 - b.x0, b.y1 - b.y0];
const x10 = (c) => Math.round((c ?? 0) * 10);

/** tesseract.js's `blocks` of one recognition as the `ocr.js` result (not exported since P11.4, D542: it was a helper for a test, and no test used it). */
function resultOf(blocks, { wantSymbols, lang, blockType }) {
  const out = { orientation: { orient: 0, writingDirection: 0, textlineOrder: 2, deskew: 0 }, blocks: [], lines: [], words: [] };
  let par = -1;
  (blocks || []).forEach((b, bi) => {
    out.blocks.push({ box: box(b.bbox), orient: 0, writingDirection: 0, textlineOrder: 2 });
    for (const p of b.paragraphs || []) {
      par += 1;
      for (const l of p.lines || []) {
        const li = out.lines.length;
        const bl = l.baseline || {};
        out.lines.push({
          box: box(l.bbox),
          baseline: [bl.x0 ?? 0, bl.y0 ?? 0, bl.x1 ?? 0, bl.y1 ?? 0],
          block: bi,
          paragraph: par,
          type: blockType(b.blocktype),
        });
        for (const w of l.words || []) {
          const text = (w.text || '').trim();
          if (!text) continue;
          const word = { box: box(w.bbox), conf: x10(w.confidence), line: li, block: bi, text, flags: 0, lang, pointSize: -1, dict: false, numeric: false };
          if (wantSymbols) {
            word.symbols = (w.symbols || []).map((s) => ({ box: box(s.bbox), conf: x10(s.confidence), text: s.text || '', alts: [] }));
            // the symbols must spell the word, as the engine's own do
            if (word.symbols.map((s) => s.text).join('') !== text) word.symbols = [];
          }
          out.words.push(word);
        }
      }
    }
  });
  return out;
}

/**
 * @param {object} o
 * @param {Function} o.createWorker tesseract.js's `createWorker`
 * @param {string[]} [o.languages] the models the engine loads (tesseract.js's names: `eng`, `deu`, `chi_sim`); a request that names
 *   languages is served by those of them it names, one that names none by all of them (D038). Default `['eng']`.
 * @param {number} [o.oem] the engine mode (1: LSTM, the default)
 * @param {object} [o.workerOptions] tesseract.js's `createWorker` options (`langPath`, `corePath`, `workerPath`, `logger`, ...)
 * @param {number} [o.workers] workers per language set, so recognitions of one round run side by side (default 1)
 * @returns {((request: object) => Promise<object>) & { terminate(): Promise<void>, producer: object }}
 */
export function tesseractPort({ createWorker, languages = ['eng'], oem = 1, workerOptions = {}, workers = 1 }) {
  const pools = new Map(); // language key -> [{ promise, busy }]
  const slot = (key) => {
    let pool = pools.get(key);
    if (!pool) pools.set(key, (pool = []));
    // an idle worker, a new one while the pool has room, else the least busy
    let s = pool.find((x) => x.busy === 0);
    if (!s && pool.length < workers) {
      s = {
        busy: 0,
        promise: (async () => {
          const w = await createWorker(key, oem, workerOptions);
          // the final candidates of the beam search, not one per timestep: the native adapter's setting (D213)
          await w.setParameters({ lstm_choice_mode: '2' });
          return w;
        })(),
      };
      pool.push(s);
    }
    return s || pool.reduce((a, b) => (a.busy <= b.busy ? a : b));
  };
  const port = async (req) => {
    // which of the engine's models read the request is kin's (`emon.ocrport`): a language is its model, a script (`Cyrl`) the models that
    // read it, one with no model is dropped, none left is every model (D038, P10.7)
    const langs = req.models(languages).map((i) => languages[i]);
    if (!langs.length) return null;
    const s = slot(langs.join('+'));
    s.busy += 1;
    try {
      const w = await s.promise;
      await w.setParameters({ tessedit_pageseg_mode: String(req.psm), user_defined_dpi: req.dpi > 0 ? String(req.dpi) : '' });
      const opts = req.rect ? { rectangle: { left: req.rect.x, top: req.rect.y, width: req.rect.w, height: req.rect.h } } : {};
      const { data } = await w.recognize(req.bitmap(), opts, { text: false, blocks: true });
      return resultOf(data.blocks, { wantSymbols: req.symbols, lang: req.wordLang(langs[0]), blockType: req.blockType });
    } finally {
      s.busy -= 1;
    }
  };
  port.terminate = async () => {
    for (const pool of pools.values()) for (const s of pool) await (await s.promise).terminate();
    pools.clear();
  };
  port.producer = { engine: 'tesseract.js', 'engine-mode': oem === 1 ? 'default (LSTM)' : String(oem), 'lstm-choice-mode': '2', models: languages.join(' '), host: 'host/js/src/tesseract.js' };
  return port;
}
