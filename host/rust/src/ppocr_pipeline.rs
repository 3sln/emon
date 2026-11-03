//! Native reference pipeline. Raw posteriors are retained for calibration only.
use super::ppocr_ctc;
use super::ppocr_image::{self, Image, Quad};
use super::ppocr_runtime::Session;
use std::path::Path;

#[derive(Debug, Clone)]
pub struct ReadingWord {
    pub text: String,
    pub bounds: [i32; 4],
    pub posterior: i32,
    pub line: usize,
}
#[derive(Debug, Clone)]
pub struct Reading {
    pub words: Vec<ReadingWord>,
    pub lines: Vec<[i32; 4]>,
}
pub struct Pipeline {
    detector: Session,
    classifier: Session,
    recognizer: Session,
    vocabulary: Vec<String>,
}
fn box_of(q: Quad, width: usize, height: usize) -> [i32; 4] {
    let x0 = q
        .iter()
        .map(|p| p.x)
        .fold(f64::INFINITY, f64::min)
        .floor()
        .clamp(0., width as f64) as i32;
    let y0 = q
        .iter()
        .map(|p| p.y)
        .fold(f64::INFINITY, f64::min)
        .floor()
        .clamp(0., height as f64) as i32;
    let x1 = q
        .iter()
        .map(|p| p.x)
        .fold(f64::NEG_INFINITY, f64::max)
        .ceil()
        .clamp(0., width as f64) as i32;
    let y1 = q
        .iter()
        .map(|p| p.y)
        .fold(f64::NEG_INFINITY, f64::max)
        .ceil()
        .clamp(0., height as f64) as i32;
    [x0, y0, x1 - x0, y1 - y0]
}
fn normalized_crop(crop: &Image, max_width: usize) -> (Vec<f32>, usize, usize) {
    let content_width = ((48. * crop.width as f64 / crop.height as f64).ceil() as usize)
        .min(max_width)
        .max(1);
    let width = content_width.max(320).min(max_width);
    let resized = crop.resize_tensor(content_width, 48, false);
    let mut padded = vec![0.; 3 * 48 * width];
    for c in 0..3 {
        for y in 0..48 {
            padded[c * 48 * width + y * width..c * 48 * width + y * width + content_width]
                .copy_from_slice(
                    &resized[c * 48 * content_width + y * content_width
                        ..c * 48 * content_width + (y + 1) * content_width],
                );
        }
    }
    (padded, width, content_width)
}
impl Pipeline {
    pub fn open(dir: &Path, dictionary: &str) -> Result<Self, String> {
        let mut vocabulary = vec![String::new()];
        vocabulary.extend(dictionary.lines().map(str::to_string));
        vocabulary.push(" ".into());
        if vocabulary.len() != 438 {
            return Err("PP-OCR dictionary must have 438 classes including blank and space".into());
        }
        Ok(Self {
            detector: Session::open(&dir.join("ch_PP-OCRv5_det_mobile.onnx"))?,
            classifier: Session::open(&dir.join("ch_ppocr_mobile_v2.0_cls_mobile.onnx"))?,
            recognizer: Session::open(&dir.join("en_PP-OCRv5_rec_mobile.onnx"))?,
            vocabulary,
        })
    }
    pub fn read(&mut self, image: &Image) -> Result<Reading, String> {
        // Fixed max-side 960, nearest multiple of 32 (ties upward), min 32.
        let scale = (960. / image.width.max(image.height) as f64).min(1.);
        let width = ((image.width as f64 * scale / 32.).round() as usize * 32).max(32);
        let height = ((image.height as f64 * scale / 32.).round() as usize * 32).max(32);
        let (shape, prob) = self.detector.run(
            image.resize_tensor(width, height, true),
            &[1, 3, height as i64, width as i64],
        )?;
        if shape != vec![1, 1, height as i64, width as i64] {
            return Err("unexpected PP-OCR detector shape".into());
        }
        let boxes = ppocr_image::detect(&prob, width, height, image.width, image.height)?;
        let mut reading = Reading {
            words: Vec::new(),
            lines: Vec::new(),
        };
        for mut q in boxes {
            if q[3].sub(q[0]).length() > q[1].sub(q[0]).length() * 1.5 {
                q = [q[3], q[0], q[1], q[2]];
            }
            let mut crop = image.crop(q)?;
            let (input, w, _) = normalized_crop(&crop, 192);
            let (shape, scores) = self.classifier.run(input, &[1, 3, 48, w as i64])?;
            if shape != vec![1, 2] || scores.len() != 2 || scores.iter().any(|x| !x.is_finite()) {
                return Err("unexpected orientation classifier output".into());
            }
            let reversed = scores[1] > scores[0] && scores[1] > 0.9;
            if reversed {
                crop.rotate180();
                q = [q[2], q[3], q[0], q[1]];
            }
            let (input, w, content_width) = normalized_crop(&crop, 4096);
            let (shape, scores) = self.recognizer.run(input, &[1, 3, 48, w as i64])?;
            if shape.len() != 3 || shape[0] != 1 || shape[2] != 438 || shape[1] <= 0 {
                return Err("unexpected PP-OCR recognizer shape".into());
            }
            let steps = shape[1] as usize;
            let cs = ppocr_ctc::decode(&scores, steps, &self.vocabulary)?;
            let ws = ppocr_ctc::words(&cs);
            if ws.is_empty() {
                continue;
            }
            let line = reading.lines.len();
            reading.lines.push(box_of(q, image.width, image.height));
            for word in ws {
                // Measured CTC support in padded tensor pixels, inverse-scaled to
                // the actual crop. This deliberately does not spread words evenly.
                let left = (word.start as f64 / steps as f64 * w as f64 / content_width as f64)
                    .clamp(0., 1.);
                let right = (word.end as f64 / steps as f64 * w as f64 / content_width as f64)
                    .clamp(0., 1.);
                let bounds = box_of(
                    [
                        ppocr_image::map(q, left, 0.),
                        ppocr_image::map(q, right, 0.),
                        ppocr_image::map(q, right, 1.),
                        ppocr_image::map(q, left, 1.),
                    ],
                    image.width,
                    image.height,
                );
                if bounds[2] > 0 && bounds[3] > 0 {
                    reading.words.push(ReadingWord {
                        text: word.text,
                        bounds,
                        posterior: word.posterior,
                        line,
                    });
                }
            }
        }
        Ok(reading)
    }
}
