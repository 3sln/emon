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
//       langs    ISO 639-3 codes (`chs` and `cht` for the two Chinese scripts); none means every model the engine has (D038)
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

/** The request's fields from kin's record `[len kind id bitmap psm flags x y w h lang... status?]`. */
export function ocrRequest(rec, bitmap) {
  const args = rec.slice(3);
  const flags = args[2];
  const langs = [];
  for (let k = 7; k < args.length; k++) langs.push(unpack4(args[k]));
  return {
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

/** The integer form `emon_answer_ocr` reads (see `host/wasm/src/wasm.rs`); `c` is the module's `ocrWord` constants. */
export function encodeOcr(r, c) {
  const o = r.orientation || {};
  const osd = r.osd;
  const blocks = r.blocks || [];
  const lines = r.lines || [];
  const words = r.words || [];
  const v = [
    o.orient ?? 0, o.writingDirection ?? 0, o.textlineOrder ?? 2, o.deskew ?? 0,
    osd ? osd.degrees : -1, osd ? pack4(osd.script || '') : 0, osd ? osd.scriptConfidence : -1, osd ? osd.orientationConfidence : -1,
    lines.length, blocks.length, words.length, 0, 0, 0,
  ];
  for (const l of lines) {
    v.push(...l.box, ...l.baseline, l.block, l.paragraph, l.type ?? 0, -1, -1, -1);
  }
  for (const b of blocks) v.push(...b.box, b.orient ?? 0, b.writingDirection ?? 0, b.textlineOrder ?? 2);
  for (const w of words) {
    const t = cps(w.text);
    v.push(...w.box, w.conf, w.line, w.block, w.flags ?? 0, w.lang ? pack4(w.lang) : 0, w.pointSize ?? -1, (w.dict ? c.dict : 0) | (w.numeric ? c.numeric : 0), t.length, ...t);
    const syms = w.symbols || [];
    v.push(syms.length);
    for (const s of syms) {
      const st = cps(s.text);
      v.push(...s.box, s.conf, st.length, ...st);
      const alts = s.alts || [];
      v.push(alts.length);
      for (const a of alts) {
        const at = cps(a.text);
        v.push(a.conf, at.length, ...at);
      }
    }
  }
  return Int32Array.from(v);
}
