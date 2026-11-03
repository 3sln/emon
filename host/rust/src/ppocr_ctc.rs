//! Deterministic greedy CTC alignment for the optional PP-OCR port.
//! Coordinates remain in recognizer timesteps until the crop transform maps them
//! back to the request bitmap. No line is divided by character/word count.

#[derive(Debug, Clone, PartialEq)]
pub struct Character {
    pub text: String,
    pub start: usize,
    pub end: usize,
    /// Mean posterior over this nonblank run, in millionths; NOT emon confidence.
    pub posterior: i32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Word {
    pub text: String,
    pub start: usize,
    pub end: usize,
    pub posterior: i32,
}

/// Index zero is the CTC blank. Vocabulary must include the model's final space.
/// Exact ties choose the lowest index. Refuse malformed or nonfinite tensors.
pub fn decode(
    logits: &[f32],
    steps: usize,
    vocabulary: &[String],
) -> Result<Vec<Character>, String> {
    let classes = vocabulary.len();
    if classes < 2 || steps == 0 || steps.checked_mul(classes) != Some(logits.len()) {
        return Err("invalid CTC shape".into());
    }
    let mut result: Vec<Character> = Vec::new();
    let mut previous = 0;
    let mut sum = 0f64;
    for (t, row) in logits.chunks_exact(classes).enumerate() {
        let mut best = 0;
        for (i, &value) in row.iter().enumerate() {
            if !value.is_finite() || !(0.0..=1.0).contains(&value) {
                return Err("CTC output is not a finite posterior".into());
            }
            if value > row[best] {
                best = i;
            }
        }
        if best != 0 {
            if best == previous {
                let last = result.last_mut().ok_or("invalid CTC state")?;
                sum += f64::from(row[best]);
                last.end = t + 1;
                last.posterior =
                    (sum * 1_000_000.0 / (last.end - last.start) as f64).round() as i32;
            } else {
                sum = f64::from(row[best]);
                result.push(Character {
                    text: vocabulary[best].clone(),
                    start: t,
                    end: t + 1,
                    posterior: (sum * 1_000_000.0).round() as i32,
                });
            }
        }
        previous = best;
    }
    Ok(result)
}

pub fn words(characters: &[Character]) -> Vec<Word> {
    let mut out = Vec::new();
    let mut text = String::new();
    let mut start = 0;
    let mut end = 0;
    let mut sum = 0i64;
    let mut count = 0i64;
    for c in characters.iter().chain(std::iter::once(&Character {
        text: " ".into(),
        start: 0,
        end: 0,
        posterior: 0,
    })) {
        if c.text.chars().all(char::is_whitespace) {
            if count > 0 {
                out.push(Word {
                    text: std::mem::take(&mut text),
                    start,
                    end,
                    posterior: ((sum + count / 2) / count) as i32,
                });
                count = 0;
                sum = 0;
            }
        } else {
            if count == 0 {
                start = c.start;
            }
            text.push_str(&c.text);
            end = c.end;
            sum += i64::from(c.posterior);
            count += 1;
        }
    }
    out
}

/// Calibration is an explicit frozen lookup, never a direct CTC/Tesseract score
/// conversion. Each bin stores measured source-word correctness in emon's 0..1000.
#[derive(Debug, Clone)]
pub struct Calibration {
    pub bins: Vec<i32>,
}
impl Calibration {
    pub fn new(bins: Vec<i32>) -> Result<Self, String> {
        if bins.is_empty() || bins.iter().any(|v| !(0..=1000).contains(v)) {
            return Err("invalid PP-OCR calibration".into());
        }
        Ok(Self { bins })
    }
    pub fn confidence(&self, posterior: i32) -> Result<i32, String> {
        if !(0..=1_000_000).contains(&posterior) {
            return Err("invalid posterior".into());
        }
        let index = ((posterior as usize * self.bins.len()) / 1_000_000).min(self.bins.len() - 1);
        Ok(self.bins[index])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn vocabulary() -> Vec<String> {
        ["", "a", "b", " "].iter().map(|s| s.to_string()).collect()
    }
    fn tensor(ids: &[usize]) -> Vec<f32> {
        ids.iter()
            .flat_map(|id| (0..4).map(move |i| if i == *id { 0.9 } else { 0.01 }))
            .collect()
    }
    #[test]
    fn repeated_character_needs_blank_and_keeps_measured_spans() {
        let cs = decode(
            &tensor(&[0, 1, 1, 0, 1, 3, 0, 2, 2, 2, 0]),
            11,
            &vocabulary(),
        )
        .unwrap();
        let ws = words(&cs);
        assert_eq!(
            ws,
            vec![
                Word {
                    text: "aa".into(),
                    start: 1,
                    end: 5,
                    posterior: 900000
                },
                Word {
                    text: "b".into(),
                    start: 7,
                    end: 10,
                    posterior: 900000
                }
            ]
        );
    }
    #[test]
    fn blank_ties_choose_blank_and_symbol_ties_choose_lowest_id() {
        assert!(decode(&[0.5; 4], 1, &vocabulary()).unwrap().is_empty());
        assert_eq!(
            decode(&[0.0, 0.5, 0.5, 0.0], 1, &vocabulary()).unwrap()[0].text,
            "a"
        );
    }
    #[test]
    fn rejects_bad_shapes_and_nonfinite_values() {
        assert!(decode(&[0.0], 1, &vocabulary()).is_err());
        assert!(decode(&[f32::NAN; 4], 1, &vocabulary()).is_err());
        assert!(decode(&[f32::INFINITY; 4], 1, &vocabulary()).is_err());
        assert!(decode(&[-0.1; 4], 1, &vocabulary()).is_err());
    }
    #[test]
    fn confidence_comes_from_the_calibration_bin() {
        let c = Calibration::new(vec![25, 180, 500, 720, 830, 880, 915, 940, 960, 975]).unwrap();
        assert_eq!(c.confidence(900000).unwrap(), 975);
        assert_eq!(c.confidence(1000000).unwrap(), 975);
        assert!(c.confidence(-1).is_err());
        assert!(Calibration::new(vec![]).is_err());
        assert!(Calibration::new(vec![1001]).is_err());
    }
}
