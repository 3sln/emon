// The fixed inputs of `bb perf`. Everything is generated from splitmix64 with a fixed seed, so the synthetic
// inputs are byte-identical on every machine (their FNV-1a hashes are printed and committed with the results),
// except the real scanned page: `bb perf` passes `--page FILE W H` (the P2.7 bench page, 2481x3508 gray, made
// from the corpus by `bb bench/mkpage.clj`) when it can find it, and says which page it used.

pub struct Rng(pub u64);

impl Rng {
    pub fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_add(0x9E3779B97F4A7C15);
        let mut z = self.0;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58476D1CE4E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D049BB133111EB);
        z ^ (z >> 31)
    }
    pub fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }
}

pub fn fnv(b: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    for &x in b {
        h ^= x as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    h
}

pub struct Inputs {
    pub page: Vec<u8>,
    pub pw: usize,
    pub ph: usize,
    pub page_source: String,
    pub photo: Vec<u8>,
    pub photo_w: usize,
    pub photo_h: usize,
    pub content: Vec<u8>,
    pub binary: Vec<u8>,
}

/// A scan-like A4 page at 300 dpi: paper with sensor noise, lines of word-like runs of dark glyph boxes, some
/// speckle. Not the real page, but the same statistics class: mostly paper, ~5% ink, many small components.
fn synth_page(w: usize, h: usize) -> Vec<u8> {
    let mut r = Rng(0x5eed_0001);
    let mut p = vec![0u8; w * h];
    for v in p.iter_mut() {
        *v = (228 + r.below(9) as i32 - 4) as u8;
    }
    let line_h = 54usize;
    let mut y0 = 180usize;
    while y0 + line_h < h - 180 {
        let mut x = 200usize;
        while x < w - 200 {
            let wl = 2 + r.below(9) as usize;
            for _ in 0..wl {
                let gw = 7 + r.below(12) as usize;
                let gh = 14 + r.below(16) as usize;
                let top = y0 + (line_h - gh) / 2 + r.below(4) as usize;
                let ink = 25 + r.below(60) as u8;
                if x + gw >= w - 200 {
                    break;
                }
                for yy in top..(top + gh).min(h) {
                    for xx in x..x + gw {
                        // a stroke-like glyph: a frame with a hollow inside on wide glyphs
                        let edge = xx < x + 3 || xx >= x + gw - 3 || yy < top + 3 || yy >= top + gh - 3;
                        if edge || gw < 10 {
                            p[yy * w + xx] = ink + r.below(12) as u8;
                        }
                    }
                }
                x += gw + 3 + r.below(3) as usize;
            }
            x += 16 + r.below(10) as usize;
        }
        y0 += line_h + 14;
    }
    for _ in 0..(w * h / 2000) {
        let (x, y) = (r.below(w as u64) as usize, r.below(h as u64) as usize);
        p[y * w + x] = 60 + r.below(80) as u8;
    }
    p
}

fn synth_photo(w: usize, h: usize) -> Vec<u8> {
    let mut r = Rng(0x5eed_0002);
    let mut p = vec![0u8; w * h * 3];
    for y in 0..h {
        for x in 0..w {
            for c in 0..3 {
                let v = (x * 255 / w) as i32 * (c as i32 + 1) / 3 + (y * 255 / h) as i32 / 3 + r.below(9) as i32;
                p[(y * w + x) * 3 + c] = v.clamp(0, 255) as u8;
            }
        }
    }
    p
}

/// A PDF content stream: text operators with varying numbers and words from a small vocabulary.
fn synth_content(target: usize) -> Vec<u8> {
    let words = ["the", "of", "and", "invoice", "total", "amount", "date", "payment", "customer", "reference", "tax", "net", "due", "page", "items", "quantity", "price", "description", "balance", "terms"];
    let mut r = Rng(0x5eed_0003);
    let mut s = String::new();
    while s.len() < target {
        let (x, y) = (36 + r.below(500), 40 + r.below(760));
        s.push_str(&format!("BT\n/F{} {} Tf\n1 0 0 1 {}.{:02} {}.{:02} Tm\n[", 1 + r.below(4), 8 + r.below(8), x, r.below(100), y, r.below(100)));
        for _ in 0..(2 + r.below(5)) {
            s.push_str(&format!("({}) {} ", words[r.below(words.len() as u64) as usize], -(r.below(40) as i64) - 1));
        }
        s.push_str("] TJ\nET\n");
        if r.below(6) == 0 {
            s.push_str(&format!("{}.{} {}.{} m {}.{} {}.{} l S\n", r.below(500), r.below(10), r.below(800), r.below(10), r.below(500), r.below(10), r.below(800), r.below(10)));
        }
    }
    s.into_bytes()
}

/// Font-program-like bytes: runs of smooth u16 tables (advance widths), structured records, and random blocks.
fn synth_binary(target: usize) -> Vec<u8> {
    let mut r = Rng(0x5eed_0004);
    let mut b = Vec::with_capacity(target + 1024);
    while b.len() < target {
        match r.below(3) {
            0 => {
                let mut v = 500 + r.below(400) as u16;
                for _ in 0..(100 + r.below(200)) {
                    v = v.wrapping_add((r.below(7) as u16).wrapping_sub(3));
                    b.extend_from_slice(&v.to_be_bytes());
                }
            }
            1 => {
                let rec = [r.below(256) as u8, r.below(256) as u8, 0, 0, 0, 1, r.below(256) as u8, 0];
                for k in 0..(50 + r.below(100)) {
                    let mut x = rec;
                    x[7] = k as u8;
                    b.extend_from_slice(&x);
                }
            }
            _ => {
                for _ in 0..(64 + r.below(192)) {
                    b.push(r.below(256) as u8);
                }
            }
        }
    }
    b.truncate(target);
    b
}

impl Inputs {
    pub fn build(page: &Option<(String, usize, usize)>, quick: bool) -> Inputs {
        let (page_px, pw, ph, src) = match page {
            Some((f, w, h)) => {
                let b = std::fs::read(f).expect("--page file");
                assert_eq!(b.len(), w * h, "--page W*H");
                (b, *w, *h, format!("file {} (the P2.7 bench page)", std::path::Path::new(f).file_name().unwrap().to_string_lossy()))
            }
            None => {
                let (w, h) = if quick { (1000, 1400) } else { (2481, 3508) };
                (synth_page(w, h), w, h, "synthetic".to_string())
            }
        };
        let (photo_w, photo_h) = (1200, 800);
        Inputs {
            page: page_px,
            pw,
            ph,
            page_source: src,
            photo: synth_photo(photo_w, photo_h),
            photo_w,
            photo_h,
            content: synth_content(if quick { 200_000 } else { 2_000_000 }),
            binary: synth_binary(if quick { 100_000 } else { 1_000_000 }),
        }
    }

    /// The three byte streams the deflate bets run over: the page as PNG Sub-filtered rows (what a PDF image or
    /// a PNG holds), a content stream, a font-like blob.
    pub fn flate_inputs(&self) -> Vec<(String, Vec<u8>)> {
        let mut rows = Vec::with_capacity(self.page.len() + self.ph);
        for y in 0..self.ph {
            rows.push(1u8);
            let row = &self.page[y * self.pw..(y + 1) * self.pw];
            for x in 0..self.pw {
                rows.push(row[x].wrapping_sub(if x > 0 { row[x - 1] } else { 0 }));
            }
        }
        vec![
            ("page-rows-sub".to_string(), rows),
            ("content-text".to_string(), self.content.clone()),
            ("binary-font".to_string(), self.binary.clone()),
        ]
    }
}
