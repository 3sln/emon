//! Optional native PP-OCRv5 port. Fixed CPU runtime, serialized sessions.
//! Model files are verified before ORT sees them. Replay never constructs this port.
use crate::engine::Ocr;
use crate::plan::{Answer, Bitmaps, PlanError, Request};
use crate::port::{cells_at, cells_len, crypto_sha256, Cells};
use crate::ppocr_ctc::Calibration;
use crate::ppocr_image::Image;
use crate::ppocr_pipeline::Pipeline;
use std::path::Path;
use std::sync::Mutex;

pub struct PpOcr {
    pipeline: Mutex<Pipeline>,
    incumbent: crate::tess::TessOcr,
    lexicon: Mutex<crate::tess::Tess>,
    calibration: Calibration,
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
        let calibration = Calibration::new(include!("ppocr_calibration.rs"))?;
        let incumbent = crate::tess::TessOcr::new(None)?;
        verify(&Path::new(incumbent.data_dir()).join("eng.traineddata"),
               "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")?;
        let lexicon = Mutex::new(crate::tess::Tess::new(incumbent.data_dir(), 0));
        Ok(Self {
            pipeline: Mutex::new(Pipeline::open(dir, include_str!("ppocr_dictionary.txt"))?),
            incumbent,
            lexicon,
            calibration,
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
        if crate::emon::ocrport::op_identity_at(&mut r) < 0 {
            return self.incumbent.fulfil(req, bitmaps, _keep_binary);
        }
        let marker = usize::try_from(crate::emon::ocrport::op_identity_at(&mut r))
            .map_err(|_| fail("PP-OCR request has no model identity"))?;
        if req.rec.get(marker) != Some(&-3100)
            || req.rec.get(marker + 1) != Some(&1)
            || req.rec.get(marker + 2) != Some(&(self.identity.chars().count() as i32))
            || req.rec.get(marker + 3..)
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
        let reading = self
            .pipeline
            .lock()
            .map_err(|_| fail("PP-OCR session lock poisoned"))?
            .read(&image)
            .map_err(PlanError::Fulfil)?;
        let mut out = Cells::new();
        let at = crate::emon::plan::an_ocr(&mut out, req.id());
        for word in &reading.words {
            let b = word.bounds;
            let confidence = self
                .calibration
                .confidence(word.posterior)
                .map_err(PlanError::Fulfil)?;
            let k = crate::emon::plan::an_ocr_word(
                &mut out,
                at,
                b[0] + dx,
                b[1] + dy,
                b[2],
                b[3],
                confidence,
                word.line as i32,
                word.line as i32,
            );
            for cp in word.text.chars() {
                crate::emon::plan::an_ocr_cp(&mut out, k, cp as i32);
            }
        }
        let n = reading.lines.len() as i32;
        crate::emon::ocr::ox_begin(&mut out, 0, -1, -1, 0, 0, 0, -1, -1, n, n, 0, 0, 0);
        for (i, b) in reading.lines.iter().enumerate() {
            crate::emon::ocr::ox_line(
                &mut out,
                b[0] + dx,
                b[1] + dy,
                b[2],
                b[3],
                -1,
                -1,
                -1,
                -1,
                i as i32,
                i as i32,
                crate::emon::ocr::bt_text(),
                -1,
                -1,
                -1,
            );
        }
        for b in &reading.lines {
            crate::emon::ocr::ox_block(&mut out, b[0] + dx, b[1] + dy, b[2], b[3], 0, -1, -1);
        }
        let mut lexicon = self.lexicon.lock().map_err(|_| fail("PP lexicon lock poisoned"))?;
        for word in &reading.words {
            let flags = if lexicon.valid_english_word(&word.text) { crate::emon::ocr::ocw_dict() } else { 0 };
            crate::emon::ocr::ox_word(&mut out, 0, 1701734144, -1, flags);
        }
        crate::emon::plan::an_ocr_end(&mut out, at);
        Ok(Answer {
            rec: (0..cells_len(&out)).map(|i| cells_at(&out, i)).collect(),
            bitmap: None,
        })
    }
}
