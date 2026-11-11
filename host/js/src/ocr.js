// THE OCR PORT'S FORM (SPEC 7.3, `emon.ocr`, D211-D214): what a JS recogniser answers, and how it becomes the integers the WASM
// binding writes into the `ocr` answer (the kin builders write the record, so its layout exists once).
//
// A port is `async (request) => result`. The request:
//
//     { id, psm, flags, dpi, rect, langs, symbols, osd, bitmap() }
//
//       id       the request's id (kin's)
//       psm      the page-segmentation mode (Tesseract's numbering)
//       flags    bit 1 symbols and their alternatives wanted, bit 2 orientation and script detection; above the low byte, the
//                bitmap's resolution in dpi (`dpi` is that, 0 when it says none)
//       rect     { x, y, w, h } to read inside the bitmap, or null for all of it
//       langs    ISO 639-3 codes (`chs` and `cht` for the two Chinese scripts), or an ISO 15924 script (`Cyrl`) for the models that
//                read it; none means every model the engine has (D038). An engine with a list of models asks `models(installed)`
//                which of them read the request (indices into the list) and `wordLang(model)` what the answer calls a model's language
//       bitmap() the pixels the request names, as a lossless PNG (`Uint8Array`)
//
// and the result (every box is `[x, y, w, h]` in the bitmap's pixels, every confidence 0..1000):
//
//     { orientation: { orient, writingDirection, textlineOrder, deskew },       // optional; centidegrees for deskew
//       osd: { degrees, script, scriptConfidence, orientationConfidence },      // optional
//       blocks: [{ box, orient, writingDirection, textlineOrder }],
//       lines:  [{ box, baseline: [x1, y1, x2, y2], block, paragraph, type }],  // type 0 text, 1 table, 2 image, 3 rule, 4 other
//       words:  [{ box, conf, line, block, text, flags, lang, pointSize, dict, numeric,
//                  symbols: [{ box, conf, text, alts: [{ text, conf }] }] }] }
//
// `null` (or `undefined`) answers `unsupported`: an answer, and the page then has no text (D038). A rejection answers `failed`.

const pack4 = (s) => {
  let v = 0;
  for (let i = 0; i < 4; i++) v = v * 256 + (i < s.length ? s.charCodeAt(i) & 255 : 0);
  return v | 0;
};

export const unpack4 = (v) => {
  let s = '';
  for (let i = 0; i < 4; i++) {
    const b = (v >>> (24 - 8 * i)) & 255;
    if (b) s += String.fromCharCode(b);
  }
  return s;
};

const cps = (s) => Array.from(s || '', (c) => c.codePointAt(0));
// Private handshake for the optional Rust-built canonical answer.
export const canonicalOcr = Symbol('emon canonical OCR answer');

/**
 * The request's fields from kin's record `[len kind id bitmap psm flags x y w h lang... status?]`. WHERE the languages are, which
 * installed models read them and what a model's language is called in an answer are kin's (`emon.ocrport`, P10.7, D396), asked of
 * the module `w`: `models(installed)` answers the indices into the `installed` model names, `wordLang(model)` the request's name
 * for the language a model read a word in, `blockType(polyBlockType)` a line's `type` from Tesseract's block type.
 */
export function ocrRequest(rec, bitmap, w) {
  const args = rec.slice(3);
  const flags = args[2];
  const langs = [];
  const marker = w.ocrIdentityAt(rec);
  for (let k = w.ocrLangFirst(rec); k < (marker < 0 ? args.length : marker - 3); k++) langs.push(unpack4(args[k]));
  return {
    ...(marker >= 0 ? { record: Int32Array.from(rec) } : {}),
    models: (installed) => w.ocrModels(rec, installed),
    wordLang: (model) => unpack4(w.ocrWordLang(model)),
    blockType: (polyBlockType) => w.ocrBlockType(polyBlockType),
    id: rec[2],
    psm: args[1],
    flags,
    dpi: Math.floor(flags / 256),
    rect: args[5] > 0 ? { x: args[3], y: args[4], w: args[5], h: args[6] } : null,
    langs,
    symbols: (flags & 1) !== 0,
    osd: (flags & 2) !== 0,
    bitmap,
  };
}

/** The integer form `emon_answer_ocr` reads (its layout is `oil-flat` in `kin/oil.kin`); `c` is the module's `ocrWord` constants. */
export function encodeOcr(r, c) {
  const o = r.orientation;
  const osd = r.osd;
  const blocks = r.blocks || [];
  const lines = r.lines || [];
  const words = r.words || [];
  const baseline = lines.length > 0 && lines.every(l => l.baseline?.length === 4 && l.baseline.every(Number.isFinite));
  const symbols = words.some(w => w.symbols?.length);
  const alts = words.some(w => w.symbols?.some(s => s.alts?.length));
  const lexicon = words.length > 0 && words.every(w => typeof w.dict === 'boolean');
  let caps = 256 | 2097152;
  if (blocks.length) caps |= 1 | 4;
  if (lines.some(l => l.paragraph !== undefined)) caps |= 2;
  if (baseline) caps |= 8;
  if (osd) caps |= 512 | 2048;
  if (o?.deskew !== undefined) caps |= 1024;
  if (o?.writingDirection !== undefined) caps |= 4096;
  if (words.some(w => w.lang)) caps |= 8192;
  if (lexicon) caps |= 16384;
  if (words.some(w => w.flags !== undefined)) caps |= 32768;
  if (words.some(w => w.pointSize >= 0)) caps |= 65536;
  if (symbols) caps |= 131072 | 262144;
  if (alts) caps |= 524288;
  const v = [1, r.engine ?? 1, 0, 0, caps, r.native ?? (caps & ~(r.inferred ?? 0)), r.seg ?? 1, 1, 1000,
    osd ? osd.degrees / 90 : 0, osd?.orientationConfidence ?? 0,
    o?.deskew ?? 0, osd ? pack4(osd.script || '') : 0, osd?.scriptConfidence ?? 0,
    o?.writingDirection ?? 0, o?.textlineOrder ?? 0, 0, r.granularity ?? 0,
    blocks.length, lines.length, words.length, 0, 0, 0];
  for (let bi = 0; bi < blocks.length; bi++) {
    const b = blocks[bi];
    v.push(...b.box, -1);
    if (caps & 4096) v.push(b.orient ?? 0, b.writingDirection ?? 0, b.textlineOrder ?? 0);
    if (caps & 4) v.push(lines.find(l => l.block === bi)?.type ?? 0);
  }
  for (let li = 0; li < lines.length; li++) {
    const l = lines[li];
    v.push(...l.box, blocks.length ? l.block : -1, words.filter(w => w.line === li).length);
    if (caps & 2) v.push(l.paragraph ?? -1);
    if (baseline) v.push(...l.baseline);
  }
  for (const w of words) {
    const t = cps(w.text);
    v.push(...w.box, w.line, w.conf);
    if (caps & 8192) v.push(w.lang ? pack4(w.lang) : 0);
    if (caps & 32768) v.push(w.flags ?? 0);
    if (caps & 65536) v.push(w.pointSize ?? -1);
    if (lexicon) v.push(1 | (w.dict ? 2 : 0));
    const syms = w.symbols || [];
    v.push(syms.length, t.length, ...t);
    for (const s of syms) {
      const st = cps(s.text), choices = s.alts || [];
      v.push(...s.box, s.conf, st.length, choices.length, ...st);
      for (const a of choices) { const text = cps(a.text); v.push(text.length, a.conf, ...text); }
    }
  }
  return Int32Array.from(v);
}
