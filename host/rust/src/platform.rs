//! What the platform OCR ports share (K8, D901): Apple Vision (`vision.rs`) and Windows.Media.Ocr (`winocr.rs`) are OS recognisers that take one bitmap and a language and answer lines of
//! words. Each does its own call and its own OIL mapping; the rest is here, once: the request as an adapter reads it (this engine and this configuration, the bitmap and the region,
//! the languages as the engine's own locale tags from kin's `emon.ocrloc`), a small crop made big enough to read, and the answer that says "unread".
use crate::plan::{Answer, Bitmaps, PlanError, Request};
use crate::port::{cells_at, cells_len, cells_push, Bitmap, Cells};
use std::sync::Arc;

pub(crate) fn fail(m: &str) -> PlanError {
    PlanError::Fulfil(m.into())
}

pub(crate) fn cells_of(v: &[i32]) -> Cells {
    let mut c = Cells::new();
    for &x in v {
        cells_push(&mut c, x);
    }
    c
}

/// The answer that says this engine could not read the request (`unsupported`: a language it has none for, a crop that read nothing). The page is unread, never blank (D038).
pub(crate) fn unread(req: &Request) -> Answer {
    let mut out = Cells::new();
    crate::emon::plan::an_fail(&mut out, crate::plan::KIND_OCR, req.id(), crate::emon::plan::ps_unsupported());
    Answer { rec: (0..cells_len(&out)).map(|i| cells_at(&out, i)).collect(), bitmap: None }
}

/// The text of kin's configuration for an engine (`emon.ocrengine`'s `oe-config-*`): the text the request's trailer must carry.
pub(crate) fn config_text(oen: i32) -> String {
    (0..crate::emon::ocrengine::oe_config_len(oen)).map(|i| char::from_u32(crate::emon::ocrengine::oe_config_value(oen, i) as u32).unwrap_or('?')).collect()
}

/// A request, as a platform adapter reads it.
pub(crate) struct Decoded {
    pub bitmap: Arc<Bitmap>,
    pub psm: i32,
    pub flags: i32,
    /// the region asked for (all four 0: the whole bitmap)
    pub rect: (i32, i32, i32, i32),
    rec: Cells,
    marker: i32,
}

/// The request for engine `oen` with the configuration `config`, and its bitmap and region; an error when it is not this engine's (a wrong engine or configuration, a missing bitmap, a
/// region outside it).
pub(crate) fn decode(req: &Request, bitmaps: &Bitmaps, oen: i32, config: &str, name: &str) -> Result<Decoded, PlanError> {
    let mut r = cells_of(&req.rec);
    let marker = crate::emon::ocrport::op_identity_at(&mut r);
    if marker < 0 {
        return Err(fail(&format!("{} request has no engine identity", name)));
    }
    let m = marker as usize;
    let got: String = req.rec.get(m + 4..).unwrap_or(&[]).iter().map(|&c| char::from_u32(c as u32).unwrap_or('?')).collect();
    if req.rec.get(m + 2) != Some(&oen) || req.rec.get(m + 3) != Some(&(config.chars().count() as i32)) || got != config {
        return Err(fail(&format!("{} request engine/configuration mismatch", name)));
    }
    let b = bitmaps.get(&req.field(crate::emon::plan::oc_bitmap())).ok_or_else(|| fail(&format!("{} request bitmap missing", name)))?.clone();
    if b.channels() != 1 && b.channels() != 3 {
        return Err(fail(&format!("{} bitmap must be grayscale or RGB", name)));
    }
    let (x, y, w, h) = (req.field(crate::emon::plan::oc_x()), req.field(crate::emon::plan::oc_y()), req.field(crate::emon::plan::oc_w()), req.field(crate::emon::plan::oc_h()));
    if w > 0 && h > 0 && (x < 0 || y < 0 || i64::from(x) + i64::from(w) > i64::from(b.width()) || i64::from(y) + i64::from(h) > i64::from(b.height())) {
        return Err(fail(&format!("{} crop outside bitmap", name)));
    }
    Ok(Decoded { bitmap: b, psm: req.field(crate::emon::plan::oc_psm()), flags: req.field(crate::emon::plan::oc_flags()), rect: (x, y, w, h), rec: r, marker })
}

/// The languages a request names, as the engine's locale tags.
pub(crate) struct Langs {
    /// every tag the request's languages come to, in the request's order, without repeats (the engine keeps the ones its platform has)
    pub tags: Vec<String>,
    /// how many languages the request names (0: the engine's default)
    pub named: i32,
    /// the one language the request names, packed, when it names one (the answer's header language); 0 otherwise
    pub one: i32,
}

/// The engine's locale tags for the Latin script (kin's `ol-locales` of `Latn`): what an engine reads with when the request names no language (no language is assumed, D038).
pub(crate) fn latin(oen: i32) -> Vec<String> {
    let mut out = Cells::new();
    if crate::emon::ocrloc::ol_locales(oen, crate::emon::ocrport::sc_latn(), &mut out) == 0 {
        return Vec::new();
    }
    let list: String = (0..cells_len(&out)).map(|k| char::from_u32(cells_at(&out, k) as u32).unwrap_or('?')).collect();
    list.split(',').map(|s| s.to_string()).collect()
}

impl Decoded {
    /// The region is a part of the bitmap (a re-read of a cell or a word), not all of it.
    pub fn cropped(&self) -> bool {
        self.rect.2 > 0 && self.rect.3 > 0 && (self.rect.2 < self.bitmap.width() || self.rect.3 < self.bitmap.height())
    }

    /// A re-read of one line or one word (Tesseract's single-line, single-word and raw-line modes): nothing found is no reading, not a blank.
    pub fn single(&self) -> bool {
        matches!(self.psm, 7 | 8 | 13)
    }

    /// The request's languages as `oen`'s locale tags (kin's `ol-locales`).
    pub fn langs(&mut self, oen: i32) -> Langs {
        let mut hash = Cells::new();
        cells_push(&mut hash, 0);
        cells_push(&mut hash, 0);
        if crate::emon::plan::rq_arg(&mut self.rec, 0, 0) >= crate::emon::plan::view_base() && self.marker >= 12 {
            crate::port::bitmap_hash(&self.bitmap, &mut hash, 0);
        }
        let first = crate::emon::ocrport::op_pp_lang_first(&mut self.rec, cells_at(&hash, 0), cells_at(&hash, 1));
        let count = crate::emon::ocrport::op_pp_lang_count(&mut self.rec, first);
        let mut tags: Vec<String> = Vec::new();
        let mut one = 0;
        for i in 0..count {
            let lang = crate::emon::ocrport::op_pp_lang_at(&mut self.rec, first, i);
            let mut out = Cells::new();
            if crate::emon::ocrloc::ol_locales(oen, lang, &mut out) == 0 {
                continue;
            }
            let list: String = (0..cells_len(&out)).map(|k| char::from_u32(cells_at(&out, k) as u32).unwrap_or('?')).collect();
            for t in list.split(',') {
                if !tags.iter().any(|x| x == t) {
                    tags.push(t.to_string());
                }
            }
            if i == 0 {
                one = lang;
            }
        }
        Langs { tags, named: count, one: if count == 1 { one } else { 0 } }
    }
}

/// A region as the engine should see it: a small one padded by its own border colour and scaled up by a whole factor, so the text of a cell or a word has the size of a line of a
/// page (Vision answers nothing for a 46 x 45 pixel crop, dart1 section 3), a large one only cut out.
pub(crate) struct Prepared {
    pub pixels: Vec<u8>,
    pub width: i32,
    pub height: i32,
    pub channels: i32,
    // request-bitmap pixel = prepared pixel / scale - margin + (ox, oy)
    scale: f64,
    margin: f64,
    ox: f64,
    oy: f64,
}

const MIN_SIDE: i32 = 160; // a region whose shorter side is below this is padded and scaled up
const TARGET_SIDE: i32 = 192; // to at least this
const MAX_SCALE: i32 = 8;

pub(crate) fn prepare(bm: &Bitmap, rect: (i32, i32, i32, i32)) -> Prepared {
    let (w, h, ch) = (bm.width(), bm.height(), bm.channels());
    let src = bm.to_bytes();
    let (rx, ry, rw, rh) = if rect.2 > 0 && rect.3 > 0 { rect } else { (0, 0, w, h) };
    if rw.min(rh) >= MIN_SIDE {
        let mut px = Vec::with_capacity((rw * rh * ch) as usize);
        for y in ry..ry + rh {
            let from = ((y * w + rx) * ch) as usize;
            px.extend_from_slice(&src[from..from + (rw * ch) as usize]);
        }
        return Prepared { pixels: px, width: rw, height: rh, channels: ch, scale: 1.0, margin: 0.0, ox: rx as f64, oy: ry as f64 };
    }
    // the border colour of the region: the mean of its edge pixels, per channel
    let mut sum = vec![0u64; ch as usize];
    let mut n = 0u64;
    for y in ry..ry + rh {
        for x in rx..rx + rw {
            if y == ry || y == ry + rh - 1 || x == rx || x == rx + rw - 1 {
                for c in 0..ch {
                    sum[c as usize] += src[(((y * w + x) * ch) + c) as usize] as u64;
                }
                n += 1;
            }
        }
    }
    let fill: Vec<u8> = sum.iter().map(|s| (*s / n.max(1)) as u8).collect();
    let margin = ((rw.min(rh)) / 2).max(16);
    let (pw, ph) = (rw + 2 * margin, rh + 2 * margin);
    let mut pad = vec![0u8; (pw * ph * ch) as usize];
    for y in 0..ph {
        for x in 0..pw {
            let (sx, sy) = (x - margin, y - margin);
            for c in 0..ch {
                pad[(((y * pw + x) * ch) + c) as usize] = if sx >= 0 && sy >= 0 && sx < rw && sy < rh { src[((((ry + sy) * w + rx + sx) * ch) + c) as usize] } else { fill[c as usize] };
            }
        }
    }
    let k = ((TARGET_SIDE + pw.min(ph) - 1) / pw.min(ph)).clamp(1, MAX_SCALE);
    if k == 1 {
        return Prepared { pixels: pad, width: pw, height: ph, channels: ch, scale: 1.0, margin: margin as f64, ox: rx as f64, oy: ry as f64 };
    }
    // bilinear, pixel centres aligned
    let (ow, oh) = (pw * k, ph * k);
    let mut out = vec![0u8; (ow * oh * ch) as usize];
    for y in 0..oh {
        let fy = ((y as f64 + 0.5) / k as f64 - 0.5).clamp(0.0, (ph - 1) as f64);
        let (y0, wy) = (fy.floor() as i32, fy - fy.floor());
        let y1 = (y0 + 1).min(ph - 1);
        for x in 0..ow {
            let fx = ((x as f64 + 0.5) / k as f64 - 0.5).clamp(0.0, (pw - 1) as f64);
            let (x0, wx) = (fx.floor() as i32, fx - fx.floor());
            let x1 = (x0 + 1).min(pw - 1);
            for c in 0..ch {
                let at = |xx: i32, yy: i32| pad[(((yy * pw + xx) * ch) + c) as usize] as f64;
                let v = at(x0, y0) * (1.0 - wx) * (1.0 - wy) + at(x1, y0) * wx * (1.0 - wy) + at(x0, y1) * (1.0 - wx) * wy + at(x1, y1) * wx * wy;
                out[(((y * ow + x) * ch) + c) as usize] = (v + 0.5) as u8;
            }
        }
    }
    Prepared { pixels: out, width: ow, height: oh, channels: ch, scale: k as f64, margin: margin as f64, ox: rx as f64, oy: ry as f64 }
}

impl Prepared {
    /// A coordinate of the prepared image as a pixel of the request bitmap, clamped to it (`max` is its width for x, its height for y).
    pub fn px(&self, v: f64, max: i32, x: bool) -> i32 {
        let o = if x { self.ox } else { self.oy };
        ((v / self.scale - self.margin + o).round() as i32).clamp(0, max)
    }
}

impl Prepared {
    /// The image reduced by a whole factor when its longer side is over `max` (an engine's largest image: Windows.Media.Ocr's `MaxImageDimension`), by the mean of each block of
    /// pixels, so `px` maps the engine's coordinates back to the request bitmap's.
    #[allow(dead_code)]
    pub fn limit_side(self, max: i32) -> Prepared {
        let side = self.width.max(self.height);
        if max <= 0 || side <= max {
            return self;
        }
        let f = (side + max - 1) / max;
        let (nw, nh, ch) = ((self.width / f).max(1), (self.height / f).max(1), self.channels);
        let mut out = vec![0u8; (nw * nh * ch) as usize];
        for y in 0..nh {
            for x in 0..nw {
                for c in 0..ch {
                    let mut sum = 0u32;
                    for dy in 0..f {
                        for dx in 0..f {
                            sum += self.pixels[((((y * f + dy).min(self.height - 1)) * self.width + (x * f + dx).min(self.width - 1)) * ch + c) as usize] as u32;
                        }
                    }
                    out[((y * nw + x) * ch + c) as usize] = ((sum + (f * f) as u32 / 2) / (f * f) as u32) as u8;
                }
            }
        }
        Prepared { pixels: out, width: nw, height: nh, channels: ch, scale: self.scale / f as f64, margin: self.margin, ox: self.ox, oy: self.oy }
    }
}
