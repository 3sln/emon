// The Rust host's binding to Tesseract's C API (DEVLOG P2.6 spike, D094). LIVE
// ONLY: nothing in `bb gates` compiles or runs this file (a gate replays a
// transcript; the engine is the host's opinion, D008). The spike driver
// (`test/ocr/rust/main.rs`) includes it under `--cfg live` and links
// `-ltesseract`.
//
// It fulfils one `ocr` request (`emon.plan`, D089): a `Bitmap` named by the
// request, a page-segmentation mode, flags (`emon.ocr`: symbols and alternatives,
// OSD), an optional region and languages. It answers the words (`an-ocr-word`)
// and the extension (`emon.ocr`: lines, baselines, symbols, alternatives,
// orientation, script), all in integers, through the kin builders so that the
// layout is written once.
//
// NO LOCALE ASSUMPTION (D038): with no language in the request the engine is
// loaded with EVERY installed language model (not "eng"); each word says which
// language read it. Initialised engines are kept per language string.
use crate::emon;
use crate::plan::*;
use crate::port::{cells_at, cells_len, Bitmap, Cells};
use std::collections::HashMap;
use std::ffi::{c_char, c_float, c_int, c_void, CStr, CString};
use std::ptr;

type Api = *mut c_void;
type Iter = *mut c_void;

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

const RIL_BLOCK: c_int = 0;
const RIL_PARA: c_int = 1;
const RIL_TEXTLINE: c_int = 2;
const RIL_WORD: c_int = 3;
const RIL_SYMBOL: c_int = 4;

pub fn version() -> String {
    unsafe { CStr::from_ptr(TessVersion()).to_string_lossy().to_string() }
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

// "Latn" / "eng" packed big-endian into one cell (a request's languages, kin's `rq-ocr-lang`).
fn pack4(s: &str) -> i32 {
    let mut v: u32 = 0;
    let b = s.as_bytes();
    for i in 0..4 {
        v = v * 256 + if i < b.len() { b[i] as u32 } else { 0 };
    }
    v as i32
}

fn unpack4(v: i32) -> String {
    let u = v as u32;
    (0..4).map(|i| ((u >> (24 - 8 * i)) & 255) as u8).filter(|&b| b != 0).map(|b| b as char).collect()
}

// Tesseract's OSD names a script in words ("Latin", "Canadian_Aboriginal"); a cell
// holds four ASCII chars, so the host answers the ISO 15924 code ("Latn"). The
// table is the OSD's own list (`osd.traineddata`); anything else is cut to four.
fn iso15924(name: &str) -> &str {
    match name {
        "Latin" | "Vietnamese" => "Latn",
        "Fraktur" => "Latf",
        "Cyrillic" => "Cyrl",
        "Greek" => "Grek",
        "Arabic" => "Arab",
        "Hebrew" => "Hebr",
        "Devanagari" => "Deva",
        "Han" => "Hani",
        "Hangul" => "Hang",
        "Japanese" => "Jpan",
        "Thai" => "Thai",
        "Bengali" => "Beng",
        "Tamil" => "Taml",
        "Telugu" => "Telu",
        "Kannada" => "Knda",
        "Malayalam" => "Mlym",
        "Gujarati" => "Gujr",
        "Gurmukhi" => "Guru",
        "Oriya" => "Orya",
        "Sinhala" => "Sinh",
        "Khmer" => "Khmr",
        "Lao" => "Laoo",
        "Myanmar" => "Mymr",
        "Tibetan" => "Tibt",
        "Georgian" => "Geor",
        "Armenian" => "Armn",
        "Ethiopic" => "Ethi",
        "Syriac" => "Syrc",
        "Thaana" => "Thaa",
        "Cherokee" => "Cher",
        "Canadian_Aboriginal" => "Cans",
        other => other,
    }
}

fn x10(f: f32) -> i32 {
    (f * 10.0).round() as i32
}

fn cps(s: &str) -> Vec<i32> {
    s.chars().map(|c| c as i32).collect()
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
    bx: [i32; 4],
    conf: i32,
    line: i32,
    block: i32,
    text: Vec<i32>,
    flags: i32,
    lang: i32,
    syms: Vec<Sym>,
}
struct Line {
    bx: [i32; 4],
    base: [i32; 4],
    block: i32,
    par: i32,
    btype: i32,
}

pub struct Tess {
    tessdata: String,
    apis: HashMap<String, Api>,
    // The resolution of the bitmaps, a hint to the engine (font-size heuristics).
    pub dpi: i32,
    // Oddities worth reporting (stderr of the driver).
    pub notes: Vec<String>,
}

impl Tess {
    pub fn new(tessdata: &str, dpi: i32) -> Tess {
        Tess { tessdata: tessdata.to_string(), apis: HashMap::new(), dpi, notes: Vec::new() }
    }

    fn installed(&self) -> Vec<String> {
        let mut v: Vec<String> = std::fs::read_dir(&self.tessdata)
            .map(|d| {
                d.filter_map(|e| e.ok())
                    .filter_map(|e| e.file_name().to_string_lossy().strip_suffix(".traineddata").map(|s| s.to_string()))
                    .filter(|l| l != "osd" && l != "snum")
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
            // Alternatives: the final beam-search candidates per symbol, not one per timestep.
            let n = CString::new("lstm_choice_mode").unwrap();
            let v = CString::new("2").unwrap();
            TessBaseAPISetVariable(h, n.as_ptr(), v.as_ptr());
            self.apis.insert(langs.to_string(), h);
            Some(h)
        }
    }

    // One `ocr` request, answered in emon's record layout.
    pub fn fulfil(&mut self, req: &Request, bm: &Bitmaps) -> Result<Answer, PlanError> {
        let id = req.id();
        let mut c = Cells::new();
        let failed = |status: i32| -> Result<Answer, PlanError> {
            let mut c = Cells::new();
            emon::plan::an_fail(&mut c, KIND_OCR, id, status);
            Ok(Answer { rec: to_vec(&c), bitmap: None })
        };
        let b: std::sync::Arc<Bitmap> = match bm.get(&req.arg(0)) {
            Some(b) => b.clone(),
            None => return failed(STATUS_FAILED),
        };
        if b.channels() != 1 && b.channels() != 3 {
            return failed(2);
        }
        let (psm, flags) = (req.arg(1), req.arg(2));
        let want_syms = flags & 1 != 0;
        let (rx, ry, rw, rh) = (req.arg(3), req.arg(4), req.arg(5), req.arg(6));
        let mut langs: Vec<String> = Vec::new();
        for k in 7..req.rec.len() - 3 {
            langs.push(unpack4(req.arg(k)));
        }
        let lang = if langs.is_empty() { self.installed().join("+") } else { langs.join("+") };
        if lang.is_empty() {
            return failed(2);
        }
        let h = match self.api(&lang) {
            Some(h) => h,
            None => return failed(STATUS_FAILED),
        };
        let px = b.to_bytes();
        let (w, hh, ch) = (b.width(), b.height(), b.channels());
        let dpi = self.dpi;
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
                    script = pack4(iso15924(&borrowed(sn)));
                } else {
                    self.notes.push("osd failed".into());
                }
                TessBaseAPIClear(ho);
            }
        }
        let mut words: Vec<Word> = Vec::new();
        let mut lines: Vec<Line> = Vec::new();
        let (mut orient, mut wdir, mut tlo, mut deskew) = (0, 0, 0, 0);
        unsafe {
            TessBaseAPISetPageSegMode(h, psm);
            setimg(h);
            if TessBaseAPIRecognize(h, ptr::null_mut()) != 0 {
                TessBaseAPIClear(h);
                return failed(STATUS_FAILED);
            }
            let it = TessBaseAPIGetIterator(h);
            if !it.is_null() {
                let pg = TessResultIteratorGetPageIterator(it);
                let (mut o, mut wd, mut tl, mut dk): (c_int, c_int, c_int, c_float) = (0, 0, 0, 0.0);
                TessPageIteratorOrientation(pg, &mut o, &mut wd, &mut tl, &mut dk);
                orient = o;
                wdir = wd;
                tlo = tl;
                deskew = (dk.to_degrees() * 100.0).round() as i32;
                let (mut block, mut par, mut line): (i32, i32, i32) = (-1, -1, -1);
                loop {
                    let pi = TessResultIteratorGetPageIterator(it);
                    if TessPageIteratorIsAtBeginningOf(pi, RIL_BLOCK) != 0 {
                        block += 1;
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
                        let btype = match TessPageIteratorBlockType(pi) {
                            1 | 2 | 3 | 7 | 8 => 0,
                            6 => 1,
                            9 | 10 | 11 => 2,
                            12 | 13 => 3,
                            _ => 4,
                        };
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
                        let mut wd = Word {
                            bx: [l, t, r - l, bt - t],
                            conf: x10(TessResultIteratorConfidence(it, RIL_WORD)),
                            line,
                            block,
                            text: cps(&text),
                            flags: fl,
                            lang: pack4(&borrowed(TessResultIteratorWordRecognitionLanguage(it))),
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
            TessBaseAPIClear(h);
        }
        // Words, then the extension, through the kin builders.
        let at = emon::plan::an_ocr(&mut c, id);
        for w in &words {
            let wd = emon::plan::an_ocr_word(&mut c, at, w.bx[0], w.bx[1], w.bx[2], w.bx[3], w.conf, w.line, w.block);
            for cp in &w.text {
                emon::plan::an_ocr_cp(&mut c, wd, *cp);
            }
        }
        emon::ocr::ox_begin(&mut c, orient, wdir, tlo, deskew, osd, script, sconf, oconf, lines.len() as i32);
        for l in &lines {
            emon::ocr::ox_line(&mut c, l.bx[0], l.bx[1], l.bx[2], l.bx[3], l.base[0], l.base[1], l.base[2], l.base[3], l.block, l.par, l.btype);
        }
        for w in &words {
            let wx = emon::ocr::ox_word(&mut c, w.flags, w.lang);
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
        Ok(Answer { rec: to_vec(&c), bitmap: None })
    }
}

fn to_vec(c: &Cells) -> Vec<i32> {
    (0..cells_len(c)).map(|i| cells_at(c, i)).collect()
}
