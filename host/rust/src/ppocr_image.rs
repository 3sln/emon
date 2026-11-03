//! Canvas-free preprocessing and DB detector geometry for the reference port.
use std::collections::VecDeque;

#[derive(Clone, Copy, Debug)]
pub struct Point {
    pub x: f64,
    pub y: f64,
}
impl Point {
    pub fn add(self, p: Self) -> Self {
        Self {
            x: self.x + p.x,
            y: self.y + p.y,
        }
    }
    pub fn sub(self, p: Self) -> Self {
        Self {
            x: self.x - p.x,
            y: self.y - p.y,
        }
    }
    pub fn scale(self, v: f64) -> Self {
        Self {
            x: self.x * v,
            y: self.y * v,
        }
    }
    pub fn dot(self, p: Self) -> f64 {
        self.x * p.x + self.y * p.y
    }
    pub fn length(self) -> f64 {
        self.dot(self).sqrt()
    }
}
fn cross(a: Point, b: Point) -> f64 {
    a.x * b.y - a.y * b.x
}
pub type Quad = [Point; 4];

pub struct Image {
    pub width: usize,
    pub height: usize,
    pub rgb: Vec<u8>,
}
impl Image {
    pub fn new(width: usize, height: usize, rgb: Vec<u8>) -> Result<Self, String> {
        if width == 0
            || height == 0
            || width.checked_mul(height).and_then(|n| n.checked_mul(3)) != Some(rgb.len())
        {
            return Err("invalid PP-OCR RGB image".into());
        }
        Ok(Self { width, height, rgb })
    }
    /// Bilinear sampling, clamped to the image. Round half upward to a byte.
    pub fn sample(&self, x: f64, y: f64, c: usize) -> f32 {
        let x = x.clamp(0., (self.width - 1) as f64);
        let y = y.clamp(0., (self.height - 1) as f64);
        let ix = x.floor() as usize;
        let iy = y.floor() as usize;
        let jx = (ix + 1).min(self.width - 1);
        let jy = (iy + 1).min(self.height - 1);
        let dx = x - ix as f64;
        let dy = y - iy as f64;
        let at = |x, y| f64::from(self.rgb[(y * self.width + x) * 3 + c]);
        (((1. - dy) * ((1. - dx) * at(ix, iy) + dx * at(jx, iy))
            + dy * ((1. - dx) * at(ix, jy) + dx * at(jx, jy)))
        .round()) as f32
    }
    pub fn resize_tensor(&self, width: usize, height: usize, detector: bool) -> Vec<f32> {
        let mut out = vec![0.; 3 * width * height];
        let mean = [0.485, 0.456, 0.406];
        let std = [0.229, 0.224, 0.225];
        for c in 0..3 {
            for y in 0..height {
                for x in 0..width {
                    let v = self.sample(
                        (x as f64 + 0.5) * self.width as f64 / width as f64 - 0.5,
                        (y as f64 + 0.5) * self.height as f64 / height as f64 - 0.5,
                        c,
                    ) / 255.;
                    out[c * width * height + y * width + x] = if detector {
                        (v - mean[c]) / std[c]
                    } else {
                        (v - 0.5) * 2.
                    };
                }
            }
        }
        out
    }
    pub fn crop(&self, q: Quad) -> Result<Self, String> {
        let width = (q[1].sub(q[0]).length().max(q[2].sub(q[3]).length()).round() as usize).max(1);
        let height = (q[3].sub(q[0]).length().max(q[2].sub(q[1]).length()).round() as usize).max(1);
        let n = width
            .checked_mul(height)
            .and_then(|n| n.checked_mul(3))
            .filter(|n| *n <= 300_000_000)
            .ok_or("PP-OCR crop too large")?;
        let mut rgb = vec![0; n];
        for y in 0..height {
            for x in 0..width {
                let p = map(
                    q,
                    (x as f64 + 0.5) / width as f64,
                    (y as f64 + 0.5) / height as f64,
                );
                for c in 0..3 {
                    rgb[(y * width + x) * 3 + c] = self.sample(p.x, p.y, c) as u8;
                }
            }
        }
        Self::new(width, height, rgb)
    }
    pub fn rotate180(&mut self) {
        let n = self.width * self.height;
        for i in 0..n / 2 {
            for c in 0..3 {
                self.rgb.swap(i * 3 + c, (n - 1 - i) * 3 + c);
            }
        }
    }
}
pub fn map(q: Quad, x: f64, y: f64) -> Point {
    q[0].scale((1. - x) * (1. - y))
        .add(q[1].scale(x * (1. - y)))
        .add(q[2].scale(x * y))
        .add(q[3].scale((1. - x) * y))
}

fn hull(mut points: Vec<Point>) -> Vec<Point> {
    points.sort_by(|a, b| a.x.total_cmp(&b.x).then(a.y.total_cmp(&b.y)));
    points.dedup_by(|a, b| a.x == b.x && a.y == b.y);
    if points.len() < 3 {
        return points;
    }
    let mut out: Vec<Point> = Vec::new();
    for &p in &points {
        while out.len() >= 2
            && cross(
                out[out.len() - 1].sub(out[out.len() - 2]),
                p.sub(out[out.len() - 1]),
            ) <= 0.
        {
            out.pop();
        }
        out.push(p);
    }
    let lower = out.len();
    for &p in points[..points.len() - 1].iter().rev() {
        while out.len() > lower
            && cross(
                out[out.len() - 1].sub(out[out.len() - 2]),
                p.sub(out[out.len() - 1]),
            ) <= 0.
        {
            out.pop();
        }
        out.push(p);
    }
    out.pop();
    out
}
fn rectangle(points: &[Point]) -> Option<Quad> {
    if points.len() < 3 {
        return None;
    }
    let mut best = f64::INFINITY;
    let mut q = None;
    for i in 0..points.len() {
        let a = points[(i + 1) % points.len()].sub(points[i]);
        let a = a.scale(1. / a.length());
        let b = Point { x: -a.y, y: a.x };
        let (mut x0, mut x1, mut y0, mut y1) = (
            f64::INFINITY,
            f64::NEG_INFINITY,
            f64::INFINITY,
            f64::NEG_INFINITY,
        );
        for &p in points {
            x0 = x0.min(p.dot(a));
            x1 = x1.max(p.dot(a));
            y0 = y0.min(p.dot(b));
            y1 = y1.max(p.dot(b));
        }
        let area = (x1 - x0) * (y1 - y0);
        if area < best {
            best = area;
            q = Some([
                a.scale(x0).add(b.scale(y0)),
                a.scale(x1).add(b.scale(y0)),
                a.scale(x1).add(b.scale(y1)),
                a.scale(x0).add(b.scale(y1)),
            ]);
        }
    }
    q.map(|q| {
        // Stable top-left start, clockwise in image coordinates.
        let k = (0..4)
            .min_by(|&a, &b| {
                (q[a].x + q[a].y)
                    .total_cmp(&(q[b].x + q[b].y))
                    .then(a.cmp(&b))
            })
            .unwrap();
        [q[k], q[(k + 1) % 4], q[(k + 2) % 4], q[(k + 3) % 4]]
    })
}
fn unclip(poly: &[Point], ratio: f64) -> Vec<Point> {
    let mut area = 0.;
    let mut perimeter = 0.;
    for i in 0..poly.len() {
        area += cross(poly[i], poly[(i + 1) % poly.len()]);
        perimeter += poly[(i + 1) % poly.len()].sub(poly[i]).length();
    }
    if perimeter == 0. {
        return vec![];
    }
    let distance = area.abs() * 0.5 * ratio / perimeter;
    let mut out = Vec::new();
    for i in 0..poly.len() {
        let a = poly[i].sub(poly[(i + poly.len() - 1) % poly.len()]);
        let b = poly[(i + 1) % poly.len()].sub(poly[i]);
        let na = Point { x: a.y, y: -a.x }.scale(distance / a.length());
        let nb = Point { x: b.y, y: -b.x }.scale(distance / b.length());
        let d = cross(a, b);
        if d.abs() < 1e-9 {
            out.push(poly[i].add(na));
        } else {
            let p = poly[i].add(na);
            let r = poly[i].add(nb);
            out.push(p.add(a.scale(cross(r.sub(p), b) / d)));
        }
    }
    out
}

/// Fixed DB policy: threshold .3, 2x2 dilation, component mean >= .5,
/// convex offset at area*1.6/perimeter, minimum rotated rectangle, stable order.
pub fn detect(
    prob: &[f32],
    width: usize,
    height: usize,
    page_width: usize,
    page_height: usize,
) -> Result<Vec<Quad>, String> {
    if width.checked_mul(height) != Some(prob.len()) || prob.iter().any(|v| !v.is_finite()) {
        return Err("invalid DB tensor".into());
    }
    let mut mask = vec![false; prob.len()];
    for y in 0..height {
        for x in 0..width {
            mask[y * width + x] = (y.saturating_sub(1)..=y)
                .any(|yy| (x.saturating_sub(1)..=x).any(|xx| prob[yy * width + xx] >= 0.3));
        }
    }
    let mut out = Vec::new();
    let mut queue = VecDeque::new();
    for start in 0..mask.len() {
        if !mask[start] {
            continue;
        }
        mask[start] = false;
        queue.push_back(start);
        let mut boundary = Vec::new();
        let mut sum = 0.;
        let mut count = 0;
        while let Some(i) = queue.pop_front() {
            let x = i % width;
            let y = i / width;
            sum += f64::from(prob[i]);
            count += 1;
            let mut edge = x == 0 || y == 0 || x + 1 == width || y + 1 == height;
            for yy in y.saturating_sub(1)..=(y + 1).min(height - 1) {
                for xx in x.saturating_sub(1)..=(x + 1).min(width - 1) {
                    let j = yy * width + xx;
                    if prob[j] < 0.3 {
                        edge = true;
                    }
                    if mask[j] {
                        mask[j] = false;
                        queue.push_back(j);
                    }
                }
            }
            if edge {
                boundary.push(Point {
                    x: x as f64,
                    y: y as f64,
                });
            }
        }
        if count < 3 || sum / (count as f64) < 0.5 {
            continue;
        }
        let poly = hull(boundary);
        if poly.len() < 3 {
            continue;
        }
        let q = match rectangle(&unclip(&poly, 1.6)) {
            Some(q) => q,
            None => continue,
        };
        if q[1].sub(q[0]).length().min(q[3].sub(q[0]).length()) < 3. {
            continue;
        }
        let q = q.map(|p| Point {
            x: (p.x * page_width as f64 / width as f64).clamp(0., page_width as f64),
            y: (p.y * page_height as f64 / height as f64).clamp(0., page_height as f64),
        });
        out.push(q);
        if out.len() >= 1000 {
            break;
        }
    }
    out.sort_by(|a, b| a[0].y.total_cmp(&b[0].y).then(a[0].x.total_cmp(&b[0].x)));
    // Insertion pass groups adjacent boxes on a common ten-pixel baseline.
    for i in 1..out.len() {
        let mut j = i;
        while j > 0 && (out[j][0].y - out[j - 1][0].y).abs() < 10. && out[j][0].x < out[j - 1][0].x
        {
            out.swap(j, j - 1);
            j -= 1;
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn detector_and_crop_are_measured() {
        let mut p = vec![0.; 64 * 32];
        for y in 10..20 {
            for x in 10..45 {
                p[y * 64 + x] = 0.99;
            }
        }
        let q = detect(&p, 64, 32, 128, 64).unwrap();
        assert_eq!(q.len(), 1);
        assert!(q[0][0].x < 20.);
        assert!(q[0][2].x > 88.);
        let im = Image::new(128, 64, vec![255; 128 * 64 * 3]).unwrap();
        let crop = im.crop(q[0]).unwrap();
        assert!(crop.width > 60);
        assert!(crop.rgb.iter().all(|v| *v == 255));
    }
    #[test]
    fn blank_has_no_detection() {
        assert!(detect(&vec![0.; 1024], 32, 32, 100, 100)
            .unwrap()
            .is_empty());
    }
    #[test]
    fn geometry_is_not_uniform_text_partition() {
        let q = [
            Point { x: 10., y: 20. },
            Point { x: 110., y: 30. },
            Point { x: 108., y: 50. },
            Point { x: 8., y: 40. },
        ];
        let p = map(q, 0.1, 0.);
        assert_eq!(p.x, 20.);
        assert_eq!(p.y, 21.);
    }
}
