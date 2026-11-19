//! Apple Vision (`VNRecognizeTextRequest`) as an `Ocr` port and the OIL answer it gives (K8, D901; D883, D895; dart1 section 4.2). macOS and iOS: the same framework, the same API.
//!
//! The Objective-C is `host/vision/emon_vision.m`, a thin shim that hands Vision a bitmap and returns plain C rows (a line's text, confidence, quad, its words with their boxes, its
//! other candidates). Everything that is OIL is here: the mapping below, the capabilities, the observed identity. Nothing of it needs a pack, a model or the network.
//!
//! WHAT VISION GIVES AND WHAT THE ANSWER SAYS (the mapping table is in `.claude/coord/k8v-report.md`):
//!   line         the recognised line: the observation's quad (`quad`), its top edge's angle (`angle`), its confidence (`lconf`; Vision gives one per LINE, quantized, so there is no
//!                `wconf`: every word of the line carries its line's, `ck` 2), `lg` 2 (an OS API line: it joins small gaps and splits at columns)
//!   word         the line's top candidate cut at whitespace, each word's box from `boundingBox(for: range)` (the shim falls back to cutting the quad by character position)
//!   alternatives the line's other candidates that split into the same number of words, where a word differs: word alternatives (`walts`), with the candidate's confidence, only
//!                when the request asks for symbols and alternatives (flag bit 1), as Tesseract's do
//!   language     the word language is the request's when it names one language (`wlang`, derived); Vision reports no language
//! WHAT IT DOES NOT: blocks, paragraphs, block kinds, a baseline, row metrics, orientation or script detection (OSD: the answer has no `orient` or `script`, so kin judges the script
//! by its own census, D883 ruling 4, and a rotated page is not detected), a reading order (`order`: the lines are in Vision's order and kin sorts them by geometry), style, point
//! size, a dictionary flag, symbols, a thresholded image.
//! A SMALL CROP (a re-read of one cell or word) is padded with its own border colour and scaled up before Vision sees it: Vision answers nothing for a 46 x 45 pixel crop (dart1
//! section 3). A crop that still reads nothing is UNREAD, not blank (a one-line re-read that finds no text did not read; the page is not claimed empty by it).
use crate::adapter::PortInfo;
use crate::engine::Ocr;
use crate::platform::{config_text as kin_config, decode, fail, latin, prepare, unread};
use crate::plan::{Answer, Bitmaps, PlanError, Request};
use crate::port::{cells_at, cells_len, cells_push, Cells};
use std::ffi::{c_char, CStr};
use std::sync::Mutex;

#[repr(C)]
#[derive(Clone, Copy)]
struct EvQuad {
    x: [f64; 4],
    y: [f64; 4],
}
#[repr(C)]
struct EvWord {
    text: *mut c_char,
    quad: EvQuad,
    precise: i32,
}
#[repr(C)]
struct EvCand {
    text: *mut c_char,
    confidence: f32,
}
#[repr(C)]
struct EvLine {
    text: *mut c_char,
    confidence: f32,
    quad: EvQuad,
    nwords: i32,
    words: *mut EvWord,
    ncands: i32,
    cands: *mut EvCand,
}
#[repr(C)]
struct EvResult {
    nlines: i32,
    lines: *mut EvLine,
}

extern "C" {
    fn emon_vision_identity(out: *mut c_char, cap: usize);
    fn emon_vision_languages(out: *mut c_char, cap: usize) -> i32;
    fn emon_vision_read(
        pixels: *const u8,
        width: i32,
        height: i32,
        channels: i32,
        stride: i32,
        langs: *const *const c_char,
        nlangs: i32,
        fast: i32,
        correct: i32,
        ncands: i32,
        out: *mut EvResult,
        err: *mut c_char,
        errcap: usize,
    ) -> i32;
    fn emon_vision_free(r: *mut EvResult);
}

/// The capabilities this engine declares (kin's `oc` bits): quad, angle, line confidence, word language, word alternatives. The same number as the engine table's ceiling.
pub const CAPS: u32 = 32 | 64 | 128 | 8192 | 1048576;
/// Of them, what Vision measures and the adapter does not derive: the quad and the line confidence.
pub const MEASURED: u32 = 32 | 128;

fn text_of(p: *const c_char) -> String {
    if p.is_null() {
        String::new()
    } else {
        unsafe { CStr::from_ptr(p) }.to_string_lossy().to_string()
    }
}

/// The requested configuration text kin builds for this engine (`oe-vision-config`).
pub fn config_text() -> String {
    kin_config(crate::emon::oil::oen_apple_vision())
}

pub struct VisionOcr {
    observed: String,
    supported: Vec<String>,
    lock: Mutex<()>,
}

impl VisionOcr {
    pub fn new() -> Result<VisionOcr, String> {
        let mut id = vec![0 as c_char; 512];
        unsafe { emon_vision_identity(id.as_mut_ptr(), id.len()) };
        let observed = text_of(id.as_ptr());
        let mut langs = vec![0 as c_char; 2048];
        let supported = if unsafe { emon_vision_languages(langs.as_mut_ptr(), langs.len()) } == 0 {
            text_of(langs.as_ptr()).split(',').map(|s| s.to_string()).filter(|s| !s.is_empty()).collect()
        } else {
            vec!["en-US".to_string()]
        };
        Ok(VisionOcr { observed, supported, lock: Mutex::new(()) })
    }

    pub fn observed(&self) -> &str {
        &self.observed
    }

    pub fn info(&self) -> PortInfo {
        PortInfo {
            name: "apple-vision".to_string(),
            engine: crate::emon::oil::oen_apple_vision() as u32,
            caps: CAPS,
            measured: MEASURED,
            needs: 0,
            flags: 0, // not THREADSAFE: emon calls it one thread at a time, and the port holds a lock of its own besides
            config: config_text(),
            observed: self.observed.clone(),
            producer: Ocr::producer(self),
        }
    }
}

fn pm(conf: f32) -> i32 {
    crate::emon::oil::oil_pm((conf.clamp(0.0, 1.0) * 10000.0).round() as i32, 10)
}

// One line, read as a `Vec` of owned rows.
struct Line {
    conf: i32,
    quad: [(i32, i32); 4],
    words: Vec<(String, [i32; 4])>,
    cands: Vec<(String, i32)>,
}

impl Ocr for VisionOcr {
    fn producer(&self) -> Vec<(String, String)> {
        vec![("engine".to_string(), "apple-vision".to_string()), ("observed".to_string(), self.observed.clone())]
    }

    fn fulfil(&self, req: &Request, bitmaps: &Bitmaps, _keep_binary: bool) -> Result<Answer, PlanError> {
        // the request names this engine and this configuration, or it is not ours to read
        let mut d = decode(req, bitmaps, crate::emon::oil::oen_apple_vision(), &config_text(), "Apple Vision")?;
        let langs = d.langs(crate::emon::oil::oen_apple_vision());
        // the tags Vision has at its current revision, in the request's order; a request that names none is read in the Latin-script languages (no language is assumed, D038)
        let wanted = if langs.named == 0 { latin(crate::emon::oil::oen_apple_vision()) } else { langs.tags.clone() };
        let tags: Vec<String> = wanted.iter().filter(|t| self.supported.iter().any(|s| s == *t)).cloned().collect();
        if langs.named > 0 && tags.is_empty() {
            return Ok(unread(req)); // `lang-unavailable`: Vision reads none of the languages asked
        }
        let lang = langs.one;
        let want_alts = d.flags & crate::emon::ocr::ocf_symbols() != 0;
        let (single, cropped) = (d.single(), d.cropped());
        let (b, psm, flags) = (d.bitmap.clone(), d.psm, d.flags);
        let p = prepare(&b, d.rect);
        let cstrs: Vec<std::ffi::CString> = tags.iter().map(|t| std::ffi::CString::new(t.as_str()).unwrap_or_default()).collect();
        let ptrs: Vec<*const c_char> = cstrs.iter().map(|c| c.as_ptr()).collect();
        let mut res = EvResult { nlines: 0, lines: std::ptr::null_mut() };
        let mut err = vec![0 as c_char; 256];
        let rc = {
            let _g = self.lock.lock().unwrap_or_else(|e| e.into_inner());
            unsafe {
                emon_vision_read(
                    p.pixels.as_ptr(),
                    p.width,
                    p.height,
                    p.channels,
                    p.width * p.channels,
                    if ptrs.is_empty() { std::ptr::null() } else { ptrs.as_ptr() },
                    ptrs.len() as i32,
                    0,
                    1,
                    if want_alts { 4 } else { 0 },
                    &mut res,
                    err.as_mut_ptr(),
                    err.len(),
                )
            }
        };
        if rc != 0 {
            return Err(fail(&format!("Apple Vision: {}", text_of(err.as_ptr()))));
        }
        // the rows, owned, in the request bitmap's pixels
        let (bw, bh) = (b.width(), b.height());
        let mut lines: Vec<Line> = Vec::new();
        for i in 0..res.nlines.max(0) as usize {
            let l = unsafe { &*res.lines.add(i) };
            let q = l.quad;
            let quad = [0, 1, 2, 3].map(|k| (p.px(q.x[k], bw, true), p.px(q.y[k], bh, false)));
            let mut words = Vec::new();
            for k in 0..l.nwords.max(0) as usize {
                let wd = unsafe { &*l.words.add(k) };
                let xs = [0, 1, 2, 3].map(|j| p.px(wd.quad.x[j], bw, true));
                let ys = [0, 1, 2, 3].map(|j| p.px(wd.quad.y[j], bh, false));
                let (x0, x1) = (*xs.iter().min().unwrap_or(&0), *xs.iter().max().unwrap_or(&0));
                let (y0, y1) = (*ys.iter().min().unwrap_or(&0), *ys.iter().max().unwrap_or(&0));
                words.push((text_of(wd.text), [x0, y0, x1 - x0, y1 - y0]));
            }
            let mut cands = Vec::new();
            for k in 0..l.ncands.max(0) as usize {
                let c = unsafe { &*l.cands.add(k) };
                cands.push((text_of(c.text), pm(c.confidence)));
            }
            lines.push(Line { conf: pm(l.confidence), quad, words, cands });
        }
        unsafe { emon_vision_free(&mut res) };
        // a re-read of a cell or a word that finds nothing did not read: it is unread, never a blank claim
        if lines.is_empty() && (cropped || single) {
            return Ok(unread(req));
        }

        let caps = if want_alts { CAPS } else { CAPS & !1048576 };
        let mut out = Cells::new();
        let at = crate::emon::oil::oil_begin(&mut out, req.id(), crate::emon::oil::oen_apple_vision(), caps as i32, MEASURED as i32, crate::emon::oil::oil_seg_from_tess(psm), 2, lang, 2);
        if at < 0 {
            return Err(fail("invalid-native-oil"));
        }
        let mut observed = Cells::new();
        for ch in self.observed.chars() {
            cells_push(&mut observed, ch as i32);
        }
        let observed_len = cells_len(&observed);
        crate::emon::oil::oil_observed(&mut out, at, &mut observed, 0, observed_len);
        for l in &lines {
            let xs = l.quad.map(|p| p.0);
            let ys = l.quad.map(|p| p.1);
            let (x0, x1) = (*xs.iter().min().unwrap_or(&0), *xs.iter().max().unwrap_or(&0));
            let (y0, y1) = (*ys.iter().min().unwrap_or(&0), *ys.iter().max().unwrap_or(&0));
            let angle = crate::emon::evidence::ev_angle(l.quad[1].0 - l.quad[0].0, l.quad[1].1 - l.quad[0].1);
            let angle = if angle > 18000 { angle - 36000 } else { angle };
            let q = &l.quad;
            crate::emon::oil::oil_line(&mut out, at, x0, y0, x1 - x0, y1 - y0, -1, l.words.len() as i32, l.conf, -1, 0, 0, 0, 0, 0, 0, 0, q[0].0, q[0].1, q[1].0, q[1].1, q[2].0, q[2].1, q[3].0, q[3].1, angle);
        }
        for (li, l) in lines.iter().enumerate() {
            // the candidates that split into as many words as the top one
            let alts: Vec<(Vec<&str>, i32)> = if want_alts { l.cands.iter().map(|(t, c)| (t.split_whitespace().collect::<Vec<_>>(), *c)).filter(|(v, _)| v.len() == l.words.len()).collect() } else { Vec::new() };
            for (wi, (text, bx)) in l.words.iter().enumerate() {
                let k = crate::emon::oil::oil_word(&mut out, at, bx[0], bx[1], bx[2], bx[3], li as i32, l.conf, lang, 0, -1, 0);
                for cp in text.chars() {
                    crate::emon::oil::oil_word_cp(&mut out, at, k, cp as i32);
                }
                let mut seen: Vec<&str> = vec![text.as_str()];
                for (words, conf) in &alts {
                    let a = words[wi];
                    if seen.contains(&a) {
                        continue;
                    }
                    seen.push(a);
                    let alt = crate::emon::oil::oil_walt(&mut out, at, k, *conf);
                    for cp in a.chars() {
                        crate::emon::oil::oil_walt_cp(&mut out, alt, cp as i32);
                    }
                }
            }
        }
        crate::emon::oil::oil_end_source(&mut out, at, flags);
        let mut validation = Cells::new();
        if crate::emon::oil::oil_record_check_bitmap(&mut out, at, bw, bh, &mut validation) != 0 {
            return Err(fail("invalid-native-oil"));
        }
        Ok(Answer { rec: (0..cells_len(&out)).map(|i| cells_at(&out, i)).collect(), bitmap: None })
    }
}
