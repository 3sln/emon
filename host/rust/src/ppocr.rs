//! Optional native PP-OCRv5 port. Fixed CPU runtime, serialized sessions.
//! Model files are verified before ORT sees them. Replay never constructs this port.
use crate::engine::Ocr;
use crate::plan::{Answer, Bitmaps, PlanError, Request};
use crate::port::{cells_at, cells_len, crypto_sha256, Cells};
use crate::ppocr_image::Image;
use crate::ppocr_pipeline::Pipeline;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub struct PpOcr {
    pipeline: Mutex<Option<Pipeline>>,
    model_dir: PathBuf,
    incumbent: crate::tess::TessOcr,
    lexicon: Mutex<crate::tess::Tess>,
    identity: String,
}
fn verify(path: &Path, want: &str) -> Result<(), String> {
    let bytes = std::fs::read(path).map_err(|e| format!("PP-OCR asset {}: {e}", path.display()))?;
    let mut digest = Cells::new();
    crypto_sha256(
        &bytes,
        0,
        i32::try_from(bytes.len()).map_err(|_| "PP-OCR asset too large")?,
        &mut digest,
    );
    let got = (0..cells_len(&digest))
        .map(|i| format!("{:02x}", cells_at(&digest, i)))
        .collect::<String>();
    if got != want {
        return Err(format!("PP-OCR asset hash mismatch: {}", path.display()));
    }
    Ok(())
}
fn identity() -> String {
    crate::emon::ocrengine::OE_IDENTITY
        .iter()
        .map(|&cp| char::from_u32(cp as u32).unwrap())
        .collect()
}
impl PpOcr {
    pub fn new(dir: &Path) -> Result<Self, String> {
        for (name, sha) in [
            (
                "ch_PP-OCRv5_det_mobile.onnx",
                "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae",
            ),
            (
                "en_PP-OCRv5_rec_mobile.onnx",
                "c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8",
            ),
            (
                "ch_ppocr_mobile_v2.0_cls_mobile.onnx",
                "e47acedf663230f8863ff1ab0e64dd2d82b838fceb5957146dab185a89d6215c",
            ),
        ] {
            verify(&dir.join(name), sha)?;
        }
        // Filled only after the independent training-pool calibration finishes.
        let incumbent = crate::tess::TessOcr::new(None)?;
        verify(&Path::new(incumbent.data_dir()).join("eng.traineddata"),
               "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")?;
        let lexicon = Mutex::new(crate::tess::Tess::new(incumbent.data_dir(), 0));
        Ok(Self {
            // A page proven complete by kin never needs inference sessions.
            // Pins are still checked above, including on such pages.
            pipeline: Mutex::new(None),
            model_dir: dir.to_path_buf(),
            incumbent,
            lexicon,
            identity: identity(),
        })
    }
}
fn unsupported(req: &Request) -> Answer {
    let mut out = Cells::new();
    crate::emon::plan::an_fail(&mut out, crate::plan::KIND_OCR, req.id(), 2);
    Answer {
        rec: (0..cells_len(&out)).map(|i| cells_at(&out, i)).collect(),
        bitmap: None,
    }
}
impl Ocr for PpOcr {
    fn producer(&self) -> Vec<(String, String)> {
        let mut p = self.incumbent.producer();
        p.extend(vec![
            ("ocr-engine".into(), "tesseract+ppocr".into()),
            ("ocr-model".into(), self.identity.clone()),
            ("pp-lexicon".into(), "tessdata_fast/eng sha256=7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2".into()),
            (
                "onnxruntime".into(),
                "1.22.0 CPU sequential intra=1 inter=1".into(),
            ),
        ]);
        p
    }
    fn fulfil(
        &self,
        req: &Request,
        bitmaps: &Bitmaps,
        _keep_binary: bool,
    ) -> Result<Answer, PlanError> {
        let fail = |message: &str| PlanError::Fulfil(message.into());
        let mut r = Cells::new();
        for &v in &req.rec {
            crate::port::cells_push(&mut r, v);
        }
        if crate::emon::ocrport::op_request_engine(&mut r) != 2 {
            return self.incumbent.fulfil(req, bitmaps, _keep_binary);
        }
        let marker = usize::try_from(crate::emon::ocrport::op_identity_at(&mut r))
            .map_err(|_| fail("PP-OCR request has no model identity"))?;
        if req.rec.get(marker) != Some(&-3100)
            || req.rec.get(marker + 1) != Some(&2)
            || req.rec.get(marker + 2) != Some(&2)
            || req.rec.get(marker + 3) != Some(&(self.identity.chars().count() as i32))
            || req.rec.get(marker + 4..)
                != Some(
                    self.identity
                        .chars()
                        .map(|c| c as i32)
                        .collect::<Vec<_>>()
                        .as_slice(),
                )
        {
            return Err(fail("PP-OCR request engine/model identity mismatch"));
        }
        // D787's explicit Tesseract best selector is not a PP model. Preserve
        // the optional-request failure, never label a PP reading eng_best.
        if req.field(crate::emon::plan::oc_flags()) & crate::emon::ocr::ocf_best() != 0 {
            return Ok(unsupported(req));
        }
        let bitmap = bitmaps.get(&req.field(crate::emon::plan::oc_bitmap()));
        let mut hash = Cells::new();
        crate::port::cells_push(&mut hash, 0);
        crate::port::cells_push(&mut hash, 0);
        if req.field(crate::emon::plan::oc_bitmap()) >= crate::emon::plan::view_base()
            && marker >= 12
        {
            if let Some(b) = bitmap {
                crate::port::bitmap_hash(b, &mut hash, 0);
            }
        }
        let first =
            crate::emon::ocrport::op_pp_lang_first(&mut r, cells_at(&hash, 0), cells_at(&hash, 1));
        let count = crate::emon::ocrport::op_pp_lang_count(&mut r, first);
        for i in 0..count {
            let lang = crate::emon::ocrport::op_pp_lang_at(&mut r, first, i);
            if lang != 1701734144 && lang != 1281455214 {
                return Ok(unsupported(req));
            }
        }
        let b = bitmap.ok_or_else(|| fail("PP-OCR request bitmap missing"))?;
        let width = b.width() as usize;
        let height = b.height() as usize;
        let channels = b.channels() as usize;
        if channels != 1 && channels != 3 {
            return Err(fail("PP-OCR bitmap must be grayscale or RGB"));
        }
        let px = b.to_bytes();
        let rgb = if channels == 3 {
            px
        } else {
            px.into_iter().flat_map(|v| [v; 3]).collect()
        };
        let full = Image::new(width, height, rgb).map_err(PlanError::Fulfil)?;
        let (x, y, w, h) = (
            req.field(crate::emon::plan::oc_x()),
            req.field(crate::emon::plan::oc_y()),
            req.field(crate::emon::plan::oc_w()),
            req.field(crate::emon::plan::oc_h()),
        );
        let (image, dx, dy) = if w > 0 && h > 0 {
            if x < 0
                || y < 0
                || i64::from(x) + i64::from(w) > width as i64
                || i64::from(y) + i64::from(h) > height as i64
            {
                return Err(fail("PP-OCR crop outside bitmap"));
            }
            let mut rgb = Vec::with_capacity(w as usize * h as usize * 3);
            for yy in y as usize..(y + h) as usize {
                rgb.extend_from_slice(
                    &full.rgb[(yy * width + x as usize) * 3..(yy * width + (x + w) as usize) * 3],
                );
            }
            (
                Image::new(w as usize, h as usize, rgb).map_err(PlanError::Fulfil)?,
                x,
                y,
            )
        } else {
            (full, 0, 0)
        };
        let reading = {
            let mut pipeline = self.pipeline.lock()
                .map_err(|_| fail("PP-OCR session lock poisoned"))?;
            if pipeline.is_none() {
                *pipeline = Some(Pipeline::open(&self.model_dir, include_str!("ppocr_dictionary.txt"))
                    .map_err(PlanError::Fulfil)?);
            }
            pipeline.as_mut().ok_or_else(|| fail("PP-OCR sessions absent"))?
                .read(&image).map_err(PlanError::Fulfil)?
        };
        let mut out = Cells::new();
        // Segments are not layout blocks; OSD never ran on this engine.
        let caps = 32 | 64 | 256 | 8192 | 16384;
        let at = crate::emon::oil::oil_begin(&mut out, req.id(), 2, caps, 32 | 256, 1, 1, 1701734144, 1);
        let mut observed = Cells::new();
        for i in 0..crate::emon::ocrengine::oe_id_count(0) {
            crate::port::cells_push(&mut observed, crate::emon::ocrengine::oe_id_at(0, i));
        }
        let observed_len = crate::port::cells_len(&observed);
        crate::emon::oil::oil_observed(&mut out, at, &mut observed, 0, observed_len);
        for (i, b) in reading.lines.iter().enumerate() {
            let q = reading.quads[i];
            let px = |v: f64| v.round().clamp(0., image.width as f64) as i32 + dx;
            let py = |v: f64| v.round().clamp(0., image.height as f64) as i32 + dy;
            let angle = crate::emon::evidence::ev_angle(px(q[1].x) - px(q[0].x), py(q[1].y) - py(q[0].y));
            let angle = if angle > 18000 { angle - 36000 } else { angle };
            let count = reading.words.iter().filter(|w| w.line == i).count() as i32;
            crate::emon::oil::oil_line(&mut out, at, b[0] + dx, b[1] + dy, b[2], b[3], -1, count, -1, -1, 0, 0, 0, 0, 0, 0, 0,
                px(q[0].x), py(q[0].y), px(q[1].x), py(q[1].y), px(q[2].x), py(q[2].y), px(q[3].x), py(q[3].y), angle);
        }
        let mut lexicon = self.lexicon.lock().map_err(|_| fail("PP lexicon lock poisoned"))?;
        for word in &reading.words {
            let b = word.bounds;
            let lex = 1 | if lexicon.valid_english_word(&word.text) { 2 } else { 0 };
            let k = crate::emon::oil::oil_word(&mut out, at, b[0] + dx, b[1] + dy, b[2], b[3], word.line as i32,
                word.posterior / 1000, 1701734144, 0, -1, lex);
            for cp in word.text.chars() { crate::emon::oil::oil_word_cp(&mut out, at, k, cp as i32); }
        }
        let mut source = Cells::new();
        for (i, score) in reading.detector_scores.iter().enumerate() {
            crate::port::cells_drop(&mut source, 0);
            crate::port::cells_push(&mut source, crate::emon::oil::oil_pm(*score, 1000));
            crate::emon::oil::oil_sc_put(&mut out, at, 1, 2, i as i32, &mut source, 0, 1);
            crate::port::cells_drop(&mut source, 0);
            crate::port::cells_push(&mut source, reading.classifications[i][0]);
            crate::port::cells_push(&mut source, crate::emon::oil::oil_pm(reading.classifications[i][1], 1000));
            crate::emon::oil::oil_sc_put(&mut out, at, 2, 2, i as i32, &mut source, 0, 2);
            crate::port::cells_drop(&mut source, 0);
            crate::port::cells_push(&mut source, reading.quarters[i]);
            crate::emon::oil::oil_sc_put(&mut out, at, 3, 2, i as i32, &mut source, 0, 1);
        }
        for (i, word) in reading.words.iter().enumerate() {
            crate::port::cells_drop(&mut source, 0);
            for value in word.span { crate::port::cells_push(&mut source, value); }
            crate::emon::oil::oil_sc_put(&mut out, at, 4, 3, i as i32, &mut source, 0, 3);
        }
        crate::emon::oil::oil_end_source(&mut out, at, req.field(crate::emon::plan::oc_flags()));
        let mut validation = Cells::new();
        if crate::emon::oil::oil_record_check_bitmap(&mut out, at, b.width(), b.height(), &mut validation) != 0 {
            return Err(fail("invalid-native-oil"));
        }
        Ok(Answer {
            rec: (0..cells_len(&out)).map(|i| cells_at(&out, i)).collect(),
            bitmap: None,
        })
    }
}
