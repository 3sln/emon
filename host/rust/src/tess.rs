// The Rust host's binding to Tesseract's C API (DEVLOG P2.6 spike, D095; P6.5, D211-D214). LIVE ONLY: it is built under the
// `tesseract` feature and links `libtesseract` and `libleptonica`; nothing in `bb gates` compiles or runs it (a gate replays a
// transcript; the engine is the host's opinion, D008). `TessOcr` is the `Ocr` port (`engine::Ocr`) an embedder puts in
// `Config::ocr`.
//
// It fulfils one `ocr` request (`emon.plan`, D089): a `Bitmap` named by the request, a page-segmentation mode, flags
// (`emon.ocr`: symbols and alternatives, OSD, and above the low byte the resolution in dpi), an optional region and
// languages. It answers the words (`an-ocr-word`) and the extension (`emon.ocr` version 2: lines, baselines, blocks with
// their own orientation, symbols, alternatives, a word's dictionary and numeric flags and point size, orientation,
// script, and the thresholded image the engine read), all in integers, through the kin builders so that the layout is
// written once.
//
// NO LOCALE ASSUMPTION (D038): with no language in the request the engine is loaded with EVERY installed language model
// (not "eng"); each word says which language read it. A request's languages are ISO 639-3 codes (`deu`; `chs` and `cht` for
// simplified and traditional Chinese, which Tesseract spells `chi_sim` and `chi_tra`); the ones with no model installed are
// dropped, and if none is left the engine reads with every model, as with no hint. Initialised engines are kept per
// language string.
//
// WHAT THE C API DOES NOT GIVE (D213): `PageIterator::RowAttributes` (x-height, ascenders, descenders) is not exported by
// `capi.h`, so a line's row attributes are answered as -1 and kin measures them from the symbols' boxes.
use crate::emon;
use crate::plan::*;
use crate::port::{cells_at, cells_len, cells_push, Bitmap, Cells};
use std::collections::HashMap;
use std::ffi::{c_char, c_float, c_int, c_void, CStr, CString};
use std::ptr;
#[cfg(feature = "tesseract")]
use std::sync::Mutex;

type Api = *mut c_void;
type Iter = *mut c_void;
type Pix = *mut c_void;

#[link(name = "tesseract")]
extern "C" {
    fn TessVersion() -> *const c_char;
    fn TessBaseAPICreate() -> Api;
    fn TessBaseAPIDelete(h: Api);
    fn TessBaseAPIInit3(h: Api, datapath: *const c_char, lang: *const c_char) -> c_int;
    fn TessBaseAPISetVariable(h: Api, name: *const c_char, value: *const c_char) -> c_int;
    fn TessBaseAPISetPageSegMode(h: Api, mode: c_int);
    fn TessBaseAPISetImage(h: Api, data: *const u8, w: c_int, hh: c_int, bpp: c_int, bpl: c_int);
    fn TessBaseAPISetSourceResolution(h: Api, ppi: c_int);
    fn TessBaseAPISetRectangle(h: Api, l: c_int, t: c_int, w: c_int, hh: c_int);
    fn TessBaseAPIRecognize(h: Api, monitor: *mut c_void) -> c_int;
    fn TessBaseAPIClear(h: Api);
    fn TessBaseAPIGetIterator(h: Api) -> Iter;
    fn TessBaseAPIGetThresholdedImage(h: Api) -> Pix;
    fn TessBaseAPIDetectOrientationScript(
        h: Api,
        deg: *mut c_int,
        oconf: *mut c_float,
        script: *mut *const c_char,
        sconf: *mut c_float,
    ) -> c_int;
    fn TessDeleteText(t: *const c_char);
    fn TessResultIteratorDelete(i: Iter);
    fn TessResultIteratorCopy(i: Iter) -> Iter;
    fn TessResultIteratorGetPageIterator(i: Iter) -> Iter;
    fn TessResultIteratorNext(i: Iter, level: c_int) -> c_int;
    fn TessResultIteratorGetUTF8Text(i: Iter, level: c_int) -> *mut c_char;
    fn TessResultIteratorConfidence(i: Iter, level: c_int) -> c_float;
    fn TessResultIteratorWordRecognitionLanguage(i: Iter) -> *const c_char;
    fn TessResultIteratorWordIsFromDictionary(i: Iter) -> c_int;
    fn TessResultIteratorWordIsNumeric(i: Iter) -> c_int;
    fn TessResultIteratorWordFontAttributes(
        i: Iter,
        b: *mut c_int,
        it: *mut c_int,
        u: *mut c_int,
        m: *mut c_int,
        s: *mut c_int,
        sc: *mut c_int,
        size: *mut c_int,
        id: *mut c_int,
    ) -> *const c_char;
    fn TessResultIteratorGetChoiceIterator(i: Iter) -> Iter;
    fn TessChoiceIteratorNext(c: Iter) -> c_int;
    fn TessChoiceIteratorGetUTF8Text(c: Iter) -> *const c_char;
    fn TessChoiceIteratorConfidence(c: Iter) -> c_float;
    fn TessChoiceIteratorDelete(c: Iter);
    fn TessPageIteratorIsAtBeginningOf(p: Iter, level: c_int) -> c_int;
    fn TessPageIteratorIsAtFinalElement(p: Iter, level: c_int, element: c_int) -> c_int;
    fn TessPageIteratorBoundingBox(p: Iter, level: c_int, l: *mut c_int, t: *mut c_int, r: *mut c_int, b: *mut c_int) -> c_int;
    fn TessPageIteratorBaseline(p: Iter, level: c_int, x1: *mut c_int, y1: *mut c_int, x2: *mut c_int, y2: *mut c_int) -> c_int;
    fn TessPageIteratorBlockType(p: Iter) -> c_int;
    fn TessPageIteratorOrientation(p: Iter, o: *mut c_int, w: *mut c_int, t: *mut c_int, deskew: *mut c_float);
}

#[link(name = "leptonica")]
extern "C" {
    fn pixGetWidth(p: Pix) -> c_int;
    fn pixGetHeight(p: Pix) -> c_int;
    fn pixGetDepth(p: Pix) -> c_int;
    fn pixGetPixel(p: Pix, x: c_int, y: c_int, val: *mut u32) -> c_int;
    fn pixDestroy(p: *mut Pix);
    fn getLeptonicaVersion() -> *mut c_char;
    fn lept_free(p: *mut c_void);
}

const RIL_BLOCK: c_int = 0;
const RIL_PARA: c_int = 1;
const RIL_TEXTLINE: c_int = 2;
const RIL_WORD: c_int = 3;
const RIL_SYMBOL: c_int = 4;

pub fn version() -> String {
    unsafe { CStr::from_ptr(TessVersion()).to_string_lossy().to_string() }
}

pub fn leptonica_version() -> String {
    unsafe {
        let p = getLeptonicaVersion();
        if p.is_null() {
            return String::new();
        }
        let s = CStr::from_ptr(p).to_string_lossy().to_string();
        lept_free(p as *mut c_void);
        s
    }
}

fn take(p: *mut c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    let s = unsafe { CStr::from_ptr(p).to_string_lossy().to_string() };
    unsafe { TessDeleteText(p) };
    s
}

fn borrowed(p: *const c_char) -> String {
    if p.is_null() {
        String::new()
    } else {
        unsafe { CStr::from_ptr(p).to_string_lossy().to_string() }
    }
}

// WHAT A REQUEST ASKS FOR AND HOW THE ENGINE'S ANSWER IS NAMED is kin's (`emon.ocrport`, P10.7, D396): which installed models read
// the request's languages and scripts, a model's name as a request spells it, the OSD's script as ISO 15924, a block's type. This
// file passes it cells and takes cells.
fn x10(f: f32) -> i32 {
    (f * 10.0).round() as i32
}

fn cps(s: &str) -> Vec<i32> {
    s.chars().map(|c| c as i32).collect()
}

fn cells_of(v: impl IntoIterator<Item = i32>) -> Cells {
    let mut c = Cells::new();
    for x in v {
        cells_push(&mut c, x);
    }
    c
}

struct Alt {
    cps: Vec<i32>,
    conf: i32,
}
struct Sym {
    bx: [i32; 4],
    conf: i32,
    cps: Vec<i32>,
    alts: Vec<Alt>,
}
struct Word {
    conf: i32,
    line: i32,
    block: i32,
    bx: [i32; 4],
    text: Vec<i32>,
    flags: i32,
    lang: i32,
    psize: i32,
    dflags: i32,
    syms: Vec<Sym>,
}
struct Line {
    bx: [i32; 4],
    base: [i32; 4],
    block: i32,
    par: i32,
    btype: i32,
}
struct Block {
    bx: [i32; 4],
    orient: i32,
    wdir: i32,
    tlo: i32,
    // the page's deskew angle, centi-degrees, as the engine reports it at this block
    deskew: i32,
}

pub struct Tess {
    tessdata: String,
    apis: HashMap<String, Api>,
    // The resolution of the bitmaps when a request does not say (a hint to the engine: font-size heuristics).
    pub dpi: i32,
    // Oddities worth reporting (stderr of the driver).
    pub notes: Vec<String>,
    // The models this engine may read with (the CLI's `--lang`, P11.4b): None is every model in the directory.
    pub only: Option<Vec<String>>,
}

// The engine instances are raw pointers; `TessOcr` (the `tesseract` feature) holds the whole thing behind a Mutex, so one thread
// uses them at a time. The ocr spike's driver (`test/ocr/rust/main.rs`, `--cfg live`) uses `Tess` alone and has no engine module.
unsafe impl Send for Tess {}

impl Tess {
    pub fn new(tessdata: &str, dpi: i32) -> Tess {
        Tess { tessdata: tessdata.to_string(), apis: HashMap::new(), dpi, notes: Vec::new(), only: None }
    }

    fn installed(&self) -> Vec<String> {
        let mut v: Vec<String> = std::fs::read_dir(&self.tessdata)
            .map(|d| {
                d.filter_map(|e| e.ok())
                    .filter_map(|e| e.file_name().to_string_lossy().strip_suffix(".traineddata").map(|s| s.to_string()))
                    .filter(|l| l != "osd" && l != "snum")
                    .filter(|l| self.only.as_ref().map(|o| o.contains(l)).unwrap_or(true))
                    .collect()
            })
            .unwrap_or_default();
        v.sort();
        v
    }

    fn api(&mut self, langs: &str) -> Option<Api> {
        if let Some(a) = self.apis.get(langs) {
            return Some(*a);
        }
        unsafe {
            let h = TessBaseAPICreate();
            let dp = CString::new(self.tessdata.clone()).unwrap();
            let lg = CString::new(langs).unwrap();
            if TessBaseAPIInit3(h, dp.as_ptr(), lg.as_ptr()) != 0 {
                TessBaseAPIDelete(h);
                return None;
            }
            // FINDING (P1.12 step 6, D130): words that score confidence 0 ("Chapter", "1" on
            // h-keyword) are NOT a reading bug here. The stock `tesseract` 5.5.3 CLI gives the
            // same 0.0 for them (tsv, word level) with lstm_choice_mode 0 and 2 alike, so the
            // choice mode and RIL_WORD vs RIL_SYMBOL order do not matter. Tesseract derives
            // word confidence from the LSTM word certainty, clipped to 0..100, so any word
            // whose certainty is poor scores exactly 0 while a misspelling can score high.
            // Alternatives under mode 2 are path-cost derived, so all but the best are
            // almost always 0 (1765 of the dump's alt confs). Confidence is an unreliable
            // signal; it is carried verbatim and 0 is never read as "wrong".
            // Alternatives: the final beam-search candidates per symbol, not one per timestep.
            let n = CString::new("lstm_choice_mode").unwrap();
            let v = CString::new("2").unwrap();
            TessBaseAPISetVariable(h, n.as_ptr(), v.as_ptr());
            self.apis.insert(langs.to_string(), h);
            Some(h)
        }
    }

    // The identity of this engine and what it reads with, for a transcript's `:producer` (D214).
    pub fn producer(&self) -> Vec<(String, String)> {
        let mut models = Vec::new();
        let mut names = self.installed();
        names.push("osd".to_string());
        for n in names {
            if let Ok(b) = std::fs::read(std::path::Path::new(&self.tessdata).join(format!("{}.traineddata", n))) {
                let mut h = 0xcbf29ce484222325u64;
                for &x in &b {
                    h ^= x as u64;
                    h = h.wrapping_mul(0x100000001b3);
                }
                models.push(format!("{}:{}:fnv64={:016x}", n, b.len(), h));
            }
        }
        vec![
            ("engine".to_string(), "tesseract".to_string()),
            ("version".to_string(), version()),
            ("leptonica".to_string(), leptonica_version()),
            ("engine-mode".to_string(), "default (LSTM)".to_string()),
            ("lstm-choice-mode".to_string(), "2".to_string()),
            ("models".to_string(), models.join(" ")),
            ("host".to_string(), "host/rust/src/tess.rs".to_string()),
        ]
    }

    // The engine's thresholded image as a gray bitmap (0 ink, 255 paper), when the engine made one.
    fn binary_of(&mut self, h: Api) -> Option<(i32, i32, Vec<u8>)> {
        unsafe {
            let mut pix = TessBaseAPIGetThresholdedImage(h);
            if pix.is_null() {
                return None;
            }
            let (w, hh, d) = (pixGetWidth(pix), pixGetHeight(pix), pixGetDepth(pix));
            let mut px = vec![255u8; (w as usize) * (hh as usize)];
            let mut v: u32 = 0;
            for y in 0..hh {
                for x in 0..w {
                    pixGetPixel(pix, x, y, &mut v);
                    let ink = if d == 1 { v != 0 } else { v < 128 };
                    if ink {
                        px[(y as usize) * (w as usize) + x as usize] = 0;
                    }
                }
            }
            pixDestroy(&mut pix);
            Some((w, hh, px))
        }
    }

    // One `ocr` request, answered in emon's record layout. `keep_binary`: the thresholded image goes in the answer's
    // bitmap (the record says the engine has one either way).
    pub fn fulfil(&mut self, req: &Request, bm: &Bitmaps, keep_binary: bool) -> Result<Answer, PlanError> {
        let id = req.id();
        let mut c = Cells::new();
        let failed = |status: i32| -> Result<Answer, PlanError> {
            let mut c = Cells::new();
            emon::plan::an_fail(&mut c, KIND_OCR, id, status);
            Ok(Answer { rec: to_vec(&c), bitmap: None })
        };
        let b: std::sync::Arc<Bitmap> = match bm.get(&req.field(emon::plan::oc_bitmap())) {
            Some(b) => b.clone(),
            None => return failed(STATUS_FAILED),
        };
        if b.channels() != 1 && b.channels() != 3 {
            return failed(2);
        }
        let (psm, flags) = (req.field(emon::plan::oc_psm()), req.field(emon::plan::oc_flags()));
        let want_syms = flags & 1 != 0;
        let (rx, ry, rw, rh) = (req.field(emon::plan::oc_x()), req.field(emon::plan::oc_y()), req.field(emon::plan::oc_w()), req.field(emon::plan::oc_h()));
        let installed = self.installed();
        // which installed models read the request is kin's (`op-models`: languages, scripts, the ones not installed dropped, none left
        // is every model); the models go in as code points separated by 0
        let mut reqc = cells_of(req.rec.iter().copied());
        let mut inst = Cells::new();
        for (i, m) in installed.iter().enumerate() {
            if i > 0 {
                cells_push(&mut inst, 0);
            }
            for ch in m.chars() {
                cells_push(&mut inst, ch as i32);
            }
        }
        let mut picked = Cells::new();
        emon::ocrport::op_models(&mut reqc, &mut inst, &mut picked);
        let mut gone = Cells::new();
        emon::ocrport::op_dropped(&mut reqc, &mut inst, &mut gone);
        let mut name = String::new();
        for i in 0..cells_len(&gone) {
            match cells_at(&gone, i) {
                0 => self.notes.push(format!("no model for {}: dropped", std::mem::take(&mut name))),
                cp => name.push(char::from_u32(cp as u32).unwrap_or('\u{FFFD}')),
            }
        }
        let lang = (0..cells_len(&picked)).map(|i| installed[cells_at(&picked, i) as usize].as_str()).collect::<Vec<_>>().join("+");
        if lang.is_empty() {
            return failed(2);
        }
        let h = match self.api(&lang) {
            Some(h) => h,
            None => return failed(STATUS_FAILED),
        };
        let px = b.to_bytes();
        let (w, hh, ch) = (b.width(), b.height(), b.channels());
        let dpi = match emon::ocr::ocf_dpi(flags) {
            0 => self.dpi,
            d => d,
        };
        let setimg = |h: Api| unsafe {
            TessBaseAPISetImage(h, px.as_ptr(), w, hh, ch, w * ch);
            if dpi > 0 {
                TessBaseAPISetSourceResolution(h, dpi);
            }
            if rw > 0 {
                TessBaseAPISetRectangle(h, rx, ry, rw, rh);
            }
        };
        let (mut osd, mut script, mut sconf, mut oconf) = (-1, 0, -1, -1);
        if flags & 2 != 0 {
            // OSD has its own model (`osd`) and its own engine instance.
            let ho = self.api("osd").unwrap_or(h);
            unsafe {
                TessBaseAPISetPageSegMode(ho, 0);
                setimg(ho);
                let (mut deg, mut oc, mut sc): (c_int, c_float, c_float) = (0, 0.0, 0.0);
                let mut sn: *const c_char = ptr::null();
                if TessBaseAPIDetectOrientationScript(ho, &mut deg, &mut oc, &mut sn, &mut sc) != 0 {
                    osd = deg;
                    oconf = x10(oc);
                    sconf = x10(sc);
                    script = emon::ocrport::op_osd_script(&mut cells_of(cps(&borrowed(sn))));
                } else {
                    self.notes.push("osd failed".into());
                }
                TessBaseAPIClear(ho);
            }
        }
        let mut words: Vec<Word> = Vec::new();
        let mut lines: Vec<Line> = Vec::new();
        let mut blocks: Vec<Block> = Vec::new();
        let mut binary: Option<(i32, i32, Vec<u8>)> = None;
        let mut has_binary = false;
        unsafe {
            TessBaseAPISetPageSegMode(h, psm);
            setimg(h);
            if TessBaseAPIRecognize(h, ptr::null_mut()) != 0 {
                TessBaseAPIClear(h);
                return failed(STATUS_FAILED);
            }
            let it = TessBaseAPIGetIterator(h);
            if !it.is_null() {
                let (mut block, mut par, mut line): (i32, i32, i32) = (-1, -1, -1);
                loop {
                    let pi = TessResultIteratorGetPageIterator(it);
                    if TessPageIteratorIsAtBeginningOf(pi, RIL_BLOCK) != 0 {
                        block += 1;
                        let (mut l, mut t, mut r, mut bt) = (0, 0, 0, 0);
                        TessPageIteratorBoundingBox(pi, RIL_BLOCK, &mut l, &mut t, &mut r, &mut bt);
                        let (mut o, mut wd, mut tl, mut dk): (c_int, c_int, c_int, c_float) = (0, 0, 0, 0.0);
                        TessPageIteratorOrientation(pi, &mut o, &mut wd, &mut tl, &mut dk);
                        blocks.push(Block { bx: [l, t, r - l, bt - t], orient: o, wdir: wd, tlo: tl, deskew: (dk.to_degrees() * 100.0).round() as i32 });
                    }
                    if TessPageIteratorIsAtBeginningOf(pi, RIL_PARA) != 0 {
                        par += 1;
                    }
                    if TessPageIteratorIsAtBeginningOf(pi, RIL_TEXTLINE) != 0 {
                        line += 1;
                        let (mut l, mut t, mut r, mut bt) = (0, 0, 0, 0);
                        TessPageIteratorBoundingBox(pi, RIL_TEXTLINE, &mut l, &mut t, &mut r, &mut bt);
                        let (mut x1, mut y1, mut x2, mut y2) = (0, 0, 0, 0);
                        TessPageIteratorBaseline(pi, RIL_TEXTLINE, &mut x1, &mut y1, &mut x2, &mut y2);
                        let btype = emon::ocrport::op_block_type(TessPageIteratorBlockType(pi));
                        lines.push(Line { bx: [l, t, r - l, bt - t], base: [x1, y1, x2, y2], block, par, btype });
                    }
                    let text = take(TessResultIteratorGetUTF8Text(it, RIL_WORD));
                    let text = text.trim().to_string();
                    if !text.is_empty() {
                        let (mut l, mut t, mut r, mut bt) = (0, 0, 0, 0);
                        TessPageIteratorBoundingBox(pi, RIL_WORD, &mut l, &mut t, &mut r, &mut bt);
                        let (mut bo, mut itl, mut un, mut mo, mut se, mut sc, mut sz, mut fid) = (0, 0, 0, 0, 0, 0, 0, 0);
                        TessResultIteratorWordFontAttributes(it, &mut bo, &mut itl, &mut un, &mut mo, &mut se, &mut sc, &mut sz, &mut fid);
                        let fl = (bo & 1) | ((itl & 1) << 1) | ((un & 1) << 2) | ((mo & 1) << 6) | ((sc & 1) << 7);
                        let mut dfl = 0;
                        if TessResultIteratorWordIsFromDictionary(it) != 0 {
                            dfl |= emon::ocr::ocw_dict();
                        }
                        if TessResultIteratorWordIsNumeric(it) != 0 {
                            dfl |= emon::ocr::ocw_numeric();
                        }
                        let mut wd = Word {
                            bx: [l, t, r - l, bt - t],
                            conf: x10(TessResultIteratorConfidence(it, RIL_WORD)),
                            line,
                            block,
                            text: cps(&text),
                            flags: fl,
                            lang: emon::ocrport::op_word_lang(&mut cells_of(cps(&borrowed(TessResultIteratorWordRecognitionLanguage(it))))),
                            psize: if sz > 0 { sz } else { -1 },
                            dflags: dfl,
                            syms: Vec::new(),
                        };
                        if want_syms {
                            let it2 = TessResultIteratorCopy(it);
                            loop {
                                let p2 = TessResultIteratorGetPageIterator(it2);
                                let (mut l, mut t, mut r, mut bt) = (0, 0, 0, 0);
                                TessPageIteratorBoundingBox(p2, RIL_SYMBOL, &mut l, &mut t, &mut r, &mut bt);
                                let st = take(TessResultIteratorGetUTF8Text(it2, RIL_SYMBOL));
                                let mut sy = Sym {
                                    bx: [l, t, r - l, bt - t],
                                    conf: x10(TessResultIteratorConfidence(it2, RIL_SYMBOL)),
                                    cps: cps(&st),
                                    alts: Vec::new(),
                                };
                                let ci = TessResultIteratorGetChoiceIterator(it2);
                                if !ci.is_null() {
                                    loop {
                                        let ct = borrowed(TessChoiceIteratorGetUTF8Text(ci));
                                        if ct != st {
                                            sy.alts.push(Alt { cps: cps(&ct), conf: x10(TessChoiceIteratorConfidence(ci)) });
                                        }
                                        if TessChoiceIteratorNext(ci) == 0 {
                                            break;
                                        }
                                    }
                                    TessChoiceIteratorDelete(ci);
                                }
                                wd.syms.push(sy);
                                if TessPageIteratorIsAtFinalElement(p2, RIL_WORD, RIL_SYMBOL) != 0
                                    || TessResultIteratorNext(it2, RIL_SYMBOL) == 0
                                {
                                    break;
                                }
                            }
                            TessResultIteratorDelete(it2);
                            let joined: Vec<i32> = wd.syms.iter().flat_map(|s| s.cps.clone()).collect();
                            if joined != wd.text {
                                self.notes.push(format!("word text and its symbols differ: {:?}", text));
                            }
                        }
                        words.push(wd);
                    }
                    if TessResultIteratorNext(it, RIL_WORD) == 0 {
                        break;
                    }
                }
                TessResultIteratorDelete(it);
            }
            // the image the engine thresholded to read this page; its pixels only when a transcript will keep them
            if keep_binary {
                binary = self.binary_of(h);
                has_binary = binary.is_some();
            } else {
                let mut pix = TessBaseAPIGetThresholdedImage(h);
                if !pix.is_null() {
                    has_binary = true;
                    binary = Some((pixGetWidth(pix), pixGetHeight(pix), Vec::new()));
                    pixDestroy(&mut pix);
                }
            }
            TessBaseAPIClear(h);
        }
        // Words, then the extension, through the kin builders.
        // the page's own orientation, direction, order and deskew are the first block's (as the page iterator reports them
        // at its start); each block's own are in the block table
        let (orient, wdir, tlo, deskew) = blocks.first().map(|b| (b.orient, b.wdir, b.tlo, b.deskew)).unwrap_or((0, 0, 0, 0));
        let (bw, bh) = binary.as_ref().map(|(w, h, _)| (*w, *h)).unwrap_or((0, 0));
        let at = emon::plan::an_ocr(&mut c, id);
        for w in &words {
            let wd = emon::plan::an_ocr_word(&mut c, at, w.bx[0], w.bx[1], w.bx[2], w.bx[3], w.conf, w.line, w.block);
            for cp in &w.text {
                emon::plan::an_ocr_cp(&mut c, wd, *cp);
            }
        }
        emon::ocr::ox_begin(&mut c, orient, wdir, tlo, deskew, osd, script, sconf, oconf, lines.len() as i32, blocks.len() as i32, has_binary as i32, bw, bh);
        for l in &lines {
            emon::ocr::ox_line(&mut c, l.bx[0], l.bx[1], l.bx[2], l.bx[3], l.base[0], l.base[1], l.base[2], l.base[3], l.block, l.par, l.btype, -1, -1, -1);
        }
        for b in &blocks {
            emon::ocr::ox_block(&mut c, b.bx[0], b.bx[1], b.bx[2], b.bx[3], b.orient, b.wdir, b.tlo);
        }
        for w in &words {
            let wx = emon::ocr::ox_word(&mut c, w.flags, w.lang, w.psize, w.dflags);
            for s in &w.syms {
                let sx = emon::ocr::ox_sym(&mut c, wx, s.bx[0], s.bx[1], s.bx[2], s.bx[3], s.conf);
                for cp in &s.cps {
                    emon::ocr::ox_sym_cp(&mut c, sx, *cp);
                }
                for a in &s.alts {
                    let ax = emon::ocr::ox_alt(&mut c, sx, a.conf);
                    for cp in &a.cps {
                        emon::ocr::ox_alt_cp(&mut c, ax, *cp);
                    }
                }
            }
        }
        emon::plan::an_ocr_end(&mut c, at);
        let bitmap = match (keep_binary, binary) {
            (true, Some((w, h, px))) if !px.is_empty() => Some(std::sync::Arc::new(Bitmap::from_bytes(w, h, 1, &px))),
            _ => None,
        };
        Ok(Answer { rec: to_vec(&c), bitmap })
    }
}

fn to_vec(c: &Cells) -> Vec<i32> {
    (0..cells_len(c)).map(|i| cells_at(c, i)).collect()
}

// THE `Ocr` PORT over Tesseract: an embedder gives `Config::ocr = Some(OcrPort(Arc::new(TessOcr::new(None)?)))`.
// A POOL OF ENGINES (P10.2, D509): Tesseract's API objects are not thread safe, so each reading takes an engine of its own from the pool (or makes
// one, which is a full model load) and gives it back; the pool holds as many as were ever wanted at once, which is as many as the conversions and the
// workers of a `parallel` conversion that read at the same moment. A serial run has one. A reading is a function of the request and the model alone
// (the LSTM engine learns nothing between pages), so which engine reads a page does not show in the answer.
#[cfg(feature = "tesseract")]
pub struct TessOcr {
    tessdata: String,
    idle: Mutex<Vec<Tess>>,
    producer: Vec<(String, String)>,
    only: Option<Vec<String>>,
}

#[cfg(feature = "tesseract")]
impl TessOcr {
    // `tessdata` is the directory of the `.traineddata` files and `langs` the models (`eng`, `deu`) the engine may read with: the ones
    // not in the directory are an Err naming them. (The CLI's `--lang`, P11.4b.)
    pub fn with_languages(tessdata: &str, langs: &[String]) -> Result<TessOcr, String> {
        let missing: Vec<&str> = langs.iter().filter(|l| !std::path::Path::new(tessdata).join(format!("{}.traineddata", l)).exists()).map(|l| l.as_str()).collect();
        if !missing.is_empty() {
            return Err(format!("no {} in {}", missing.iter().map(|l| format!("{}.traineddata", l)).collect::<Vec<_>>().join(", "), tessdata));
        }
        let mut t = Tess::new(tessdata, 0);
        t.only = Some(langs.to_vec());
        let producer = t.producer();
        Ok(TessOcr { tessdata: tessdata.to_string(), idle: Mutex::new(vec![t]), producer, only: Some(langs.to_vec()) })
    }

    // `tessdata`: the directory of the `.traineddata` files; None looks in $TESSDATA_PREFIX and the usual places.
    pub fn new(tessdata: Option<&str>) -> Result<TessOcr, String> {
        let dir = match tessdata {
            Some(d) => d.to_string(),
            None => {
                let mut cands: Vec<String> = Vec::new();
                if let Ok(p) = std::env::var("TESSDATA_PREFIX") {
                    cands.push(p.clone());
                    cands.push(format!("{}/tessdata", p));
                }
                for p in ["/opt/homebrew/share/tessdata", "/usr/local/share/tessdata", "/usr/share/tesseract-ocr/5/tessdata", "/usr/share/tesseract-ocr/4.00/tessdata", "/usr/share/tessdata"] {
                    cands.push(p.to_string());
                }
                cands
                    .into_iter()
                    .find(|d| std::path::Path::new(d).join("eng.traineddata").exists() || std::fs::read_dir(d).map(|r| r.count() > 0).unwrap_or(false))
                    .ok_or_else(|| "no tessdata directory found (set TESSDATA_PREFIX)".to_string())?
            }
        };
        let t = Tess::new(&dir, 0);
        let producer = t.producer();
        Ok(TessOcr { tessdata: dir, idle: Mutex::new(vec![t]), producer, only: None })
    }
}

#[cfg(feature = "tesseract")]
impl crate::engine::Ocr for TessOcr {
    fn fulfil(&self, req: &Request, bitmaps: &Bitmaps, keep_binary: bool) -> Result<Answer, PlanError> {
        let taken = self.idle.lock().unwrap_or_else(|e| e.into_inner()).pop();
        let mut t = taken.unwrap_or_else(|| {
            let mut t = Tess::new(&self.tessdata, 0);
            t.only = self.only.clone();
            t
        });
        // an engine that panicked is not given back: its state is not known
        let r = t.fulfil(req, bitmaps, keep_binary);
        self.idle.lock().unwrap_or_else(|e| e.into_inner()).push(t);
        r
    }
    fn producer(&self) -> Vec<(String, String)> {
        self.producer.clone()
    }
}
