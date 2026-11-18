//! Windows.Media.Ocr as an `Ocr` port and the OIL answer it gives (K8, D900; D883 ruling 3; dart1 section 4.2). Windows 10 and later, through the `windows` crate: nothing but the
//! system's own WinRT API, no model, no pack, no network. Built under the `winocr` feature on Windows only.
//!
//! THE MAPPING DISCIPLINE is Apple Vision's (`vision.rs`), with what this engine has:
//!   line         `OcrLine`: the engine's layout line. It has no box: the box is the union of its words' rectangles (`lg` 2, an OS API line); no quad, no baseline
//!   word         `OcrWord`: the text and `BoundingRect`, in the pixels of the image that was read
//!   confidence   NONE. `OcrWord` and `OcrLine` have only text and boxes, so the answer's confidence kind is `none` (`ck` 0), no word or line carries a number, and kin treats the
//!                engine as uncalibrated for good (D883 ruling 3: it may vote, it may not insert or replace, and it is never the incumbent)
//!   language     the one language the request names (`wlang`, derived); the engine reads with the language pack of that tag, and a language with no pack installed is
//!                `lang-unavailable`: the page is unread, never blank (D038). A request naming no language reads with the user's profile languages
//! WHAT IT DOES NOT SAY: blocks, paragraphs, a baseline, row metrics, orientation or script (no OSD: kin judges the script itself, ruling 4), a reading order, alternatives, style,
//! point size, a dictionary flag, symbols, a thresholded image. `OcrResult.TextAngle` is read but NOT declared: the documented reference of its sign is not one this port could check
//! (it cannot run off Windows), and a wrong sign would turn pages the wrong way.
//! The image is Gray8 or Bgra8 (the formats the engine reads). A small region is padded and scaled up (`platform.rs`), and one larger than `OcrEngine.MaxImageDimension` is reduced
//! by a whole factor and its boxes scaled back.
use crate::adapter::PortInfo;
use crate::engine::Ocr;
use crate::platform::{config_text as kin_config, decode, fail, prepare, unread, Prepared};
use crate::plan::{Answer, Bitmaps, PlanError, Request};
use crate::port::{cells_at, cells_len, cells_push, Cells};
use std::collections::HashMap;
use std::sync::Mutex;
use windows::core::HSTRING;
use windows::Globalization::Language;
use windows::Graphics::Imaging::{BitmapPixelFormat, SoftwareBitmap};
use windows::Media::Ocr::OcrEngine;
use windows::Storage::Streams::DataWriter;

/// The capabilities this engine declares (kin's `oc` bits): the word language, derived from the request.
pub const CAPS: u32 = 8192;
/// What it measures itself: nothing it can say a number of (no confidence, no angle declared).
pub const MEASURED: u32 = 0;

/// The requested configuration text kin builds for this engine (`oe-winocr-config`).
pub fn config_text() -> String {
    kin_config(crate::emon::oil::oen_windows_media_ocr())
}

fn werr<T>(r: windows::core::Result<T>, what: &str) -> Result<T, PlanError> {
    r.map_err(|e| fail(&format!("Windows.Media.Ocr {}: {}", what, e.message())))
}

// WinRT wants the thread to be in an apartment: the multithreaded one, once for each thread this port is called from. A thread that is already in another apartment (the
// embedder's own) is left in it.
fn init_apartment() {
    use std::cell::Cell;
    use windows::Win32::System::WinRT::{RoInitialize, RO_INIT_MULTITHREADED};
    thread_local! { static DONE: Cell<bool> = const { Cell::new(false) }; }
    DONE.with(|d| {
        if !d.get() {
            // S_OK, S_FALSE (already initialised) and RPC_E_CHANGED_MODE (another apartment) all leave a usable apartment
            let _ = unsafe { RoInitialize(RO_INIT_MULTITHREADED) };
            d.set(true);
        }
    });
}

pub struct WinOcr {
    observed: String,
    max_dim: i32,
    engines: Mutex<HashMap<String, OcrEngine>>,
    lock: Mutex<()>,
}

impl WinOcr {
    pub fn new() -> Result<WinOcr, String> {
        init_apartment();
        let max_dim = OcrEngine::MaxImageDimension().map_err(|e| format!("Windows.Media.Ocr is not available: {}", e.message()))? as i32;
        // "10.0.<build>.<revision>" from the device family version (a packed 64-bit number): the OS build is part of the observed identity
        let version = windows::System::Profile::AnalyticsInfo::VersionInfo()
            .and_then(|v| v.DeviceFamilyVersion())
            .ok()
            .and_then(|s| s.to_string().parse::<u64>().ok())
            .map(|v| format!("{}.{}.{}.{}", v >> 48, (v >> 32) & 0xffff, (v >> 16) & 0xffff, v & 0xffff))
            .unwrap_or_else(|| "unknown".to_string());
        Ok(WinOcr { observed: format!("Windows {}; Windows.Media.Ocr.OcrEngine; max-image-dimension={}", version, max_dim), max_dim, engines: Mutex::new(HashMap::new()), lock: Mutex::new(()) })
    }

    pub fn observed(&self) -> &str {
        &self.observed
    }

    pub fn info(&self) -> PortInfo {
        PortInfo {
            name: "windows-media-ocr".to_string(),
            engine: crate::emon::oil::oen_windows_media_ocr() as u32,
            caps: CAPS,
            measured: MEASURED,
            needs: 0,
            flags: 0, // not THREADSAFE: emon calls it one thread at a time, and the port holds a lock of its own besides
            config: config_text(),
            observed: self.observed.clone(),
            producer: Ocr::producer(self),
        }
    }

    // The engine of a language tag (cached), or of the user's profile languages for the empty tag; None when no pack of that language is installed.
    fn engine(&self, tag: &str) -> Option<OcrEngine> {
        let mut m = self.engines.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(e) = m.get(tag) {
            return Some(e.clone());
        }
        let e = if tag.is_empty() {
            OcrEngine::TryCreateFromUserProfileLanguages().ok()?
        } else {
            let lang = Language::CreateLanguage(&HSTRING::from(tag)).ok()?;
            if !OcrEngine::IsLanguageSupported(&lang).ok()? {
                return None;
            }
            OcrEngine::TryCreateFromLanguage(&lang).ok()?
        };
        m.insert(tag.to_string(), e.clone());
        Some(e)
    }
}

// A line as read: its words and the union of their rectangles, in the request bitmap's pixels.
struct Line {
    bbox: [i32; 4],
    words: Vec<(String, [i32; 4])>,
}

// The image as the engine reads it: Gray8 for a gray bitmap, Bgra8 (opaque) for a colour one.
fn software_bitmap(p: &Prepared) -> Result<SoftwareBitmap, PlanError> {
    let bytes: Vec<u8> = if p.channels == 1 {
        p.pixels.clone()
    } else {
        p.pixels.chunks_exact(3).flat_map(|c| [c[2], c[1], c[0], 255]).collect()
    };
    let format = if p.channels == 1 { BitmapPixelFormat::Gray8 } else { BitmapPixelFormat::Bgra8 };
    let writer = werr(DataWriter::new(), "DataWriter")?;
    werr(writer.WriteBytes(&bytes), "WriteBytes")?;
    let buffer = werr(writer.DetachBuffer(), "DetachBuffer")?;
    werr(SoftwareBitmap::CreateCopyFromBuffer(&buffer, format, p.width, p.height), "CreateCopyFromBuffer")
}

impl Ocr for WinOcr {
    fn producer(&self) -> Vec<(String, String)> {
        vec![("engine".to_string(), "windows-media-ocr".to_string()), ("observed".to_string(), self.observed.clone())]
    }

    fn fulfil(&self, req: &Request, bitmaps: &Bitmaps, _keep_binary: bool) -> Result<Answer, PlanError> {
        init_apartment();
        let oen = crate::emon::oil::oen_windows_media_ocr();
        let mut d = decode(req, bitmaps, oen, &config_text(), "Windows.Media.Ocr")?;
        let langs = d.langs(oen);
        // one engine reads with one language: the first the request names that has a pack installed
        let (engine, lang) = if langs.named == 0 {
            (self.engine(""), 0)
        } else {
            (langs.tags.iter().find_map(|t| self.engine(t)), langs.one)
        };
        let engine = match engine {
            Some(e) => e,
            None => return Ok(unread(req)), // `lang-unavailable`: no language pack of the request's languages is installed
        };
        let (single, cropped) = (d.single(), d.cropped());
        let (b, psm, flags) = (d.bitmap.clone(), d.psm, d.flags);
        let (bw, bh) = (b.width(), b.height());
        let p = prepare(&b, d.rect).limit_side(self.max_dim);
        let reading = {
            let _g = self.lock.lock().unwrap_or_else(|e| e.into_inner());
            let image = software_bitmap(&p)?;
            let op = werr(engine.RecognizeAsync(&image), "RecognizeAsync")?;
            werr(op.join(), "Recognize")?
        };
        let mut lines: Vec<Line> = Vec::new();
        for l in werr(reading.Lines(), "Lines")? {
            let mut words: Vec<(String, [i32; 4])> = Vec::new();
            for w in werr(l.Words(), "Words")? {
                let text = werr(w.Text(), "Text")?.to_string();
                let r = werr(w.BoundingRect(), "BoundingRect")?;
                let (x0, y0) = (p.px(r.X as f64, bw, true), p.px(r.Y as f64, bh, false));
                let (x1, y1) = (p.px((r.X + r.Width) as f64, bw, true), p.px((r.Y + r.Height) as f64, bh, false));
                if !text.trim().is_empty() {
                    words.push((text, [x0, y0, x1 - x0, y1 - y0]));
                }
            }
            if words.is_empty() {
                continue;
            }
            let x0 = words.iter().map(|w| w.1[0]).min().unwrap_or(0);
            let y0 = words.iter().map(|w| w.1[1]).min().unwrap_or(0);
            let x1 = words.iter().map(|w| w.1[0] + w.1[2]).max().unwrap_or(0);
            let y1 = words.iter().map(|w| w.1[1] + w.1[3]).max().unwrap_or(0);
            lines.push(Line { bbox: [x0, y0, x1 - x0, y1 - y0], words });
        }
        // a re-read of a cell or a word that finds nothing did not read: it is unread, never a blank claim
        if lines.is_empty() && (cropped || single) {
            return Ok(unread(req));
        }
        let mut out = Cells::new();
        let at = crate::emon::oil::oil_begin(&mut out, req.id(), oen, CAPS as i32, MEASURED as i32, crate::emon::oil::oil_seg_from_tess(psm), 0, lang, 2);
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
            // no confidence (conf -1), no quad, no angle: only the boxes and the word count are in this engine's line group
            crate::emon::oil::oil_line(&mut out, at, l.bbox[0], l.bbox[1], l.bbox[2], l.bbox[3], -1, l.words.len() as i32, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        for (li, l) in lines.iter().enumerate() {
            for (text, bx) in &l.words {
                let k = crate::emon::oil::oil_word(&mut out, at, bx[0], bx[1], bx[2], bx[3], li as i32, -1, lang, 0, -1, 0);
                for cp in text.chars() {
                    crate::emon::oil::oil_word_cp(&mut out, at, k, cp as i32);
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
