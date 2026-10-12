// The native references of `bb perf`. Libraries where a library is the honest comparator (flate2/miniz_oxide,
// fdeflate, the png crate); hand-written, safe Rust where the bet has no library (PDF's LZW, the tracer's
// union-find over row runs), and bench/native/native.rs, P2.7's comparator, INCLUDED unchanged for the raster
// stages (so the numbers of D092 and of D409 are of the same source).
use std::io::Read;
use std::io::Write;

// ------------------------------------------------------------------------------ flate

pub fn zlib_compress(raw: &[u8], level: u32) -> Vec<u8> {
    let mut e = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::new(level));
    e.write_all(raw).unwrap();
    e.finish().unwrap()
}

pub fn zlib_decompress_flate2(z: &[u8], hint: usize) -> Vec<u8> {
    let mut v = Vec::with_capacity(hint);
    flate2::read::ZlibDecoder::new(z).read_to_end(&mut v).unwrap();
    v
}

pub fn zlib_decompress_fdeflate(z: &[u8]) -> Vec<u8> {
    fdeflate::decompress_to_vec(z).unwrap()
}

// ------------------------------------------------------------------------------ png

pub fn png_encode(px: &[u8], w: usize, h: usize, ch: usize, comp: png::Compression) -> Vec<u8> {
    let mut out = Vec::new();
    {
        let mut e = png::Encoder::new(&mut out, w as u32, h as u32);
        e.set_color(if ch == 1 { png::ColorType::Grayscale } else { png::ColorType::Rgb });
        e.set_depth(png::BitDepth::Eight);
        e.set_compression(comp);
        e.set_filter(png::Filter::Adaptive);
        let mut wr = e.write_header().unwrap();
        wr.write_image_data(px).unwrap();
    }
    out
}

pub fn png_decode(file: &[u8]) -> (usize, usize, Vec<u8>) {
    let dec = png::Decoder::new(std::io::Cursor::new(file));
    let mut r = dec.read_info().unwrap();
    let mut buf = vec![0u8; r.output_buffer_size().unwrap()];
    let info = r.next_frame(&mut buf).unwrap();
    buf.truncate(info.buffer_size());
    (info.width as usize, info.height as usize, buf)
}

/// The concatenated IDAT data of a PNG file: one zlib stream.
pub fn png_idat(file: &[u8]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut p = 8;
    while p + 8 <= file.len() {
        let len = u32::from_be_bytes([file[p], file[p + 1], file[p + 2], file[p + 3]]) as usize;
        if &file[p + 4..p + 8] == b"IDAT" {
            out.extend_from_slice(&file[p + 8..p + 8 + len]);
        }
        p += 12 + len;
    }
    out
}

// ------------------------------------------------------------------------------ ccitt (the `fax` crate; P10.11, D447)

/// Group 4 encode a gray page (below 128 is black) with the `fax` crate's encoder.
pub fn fax_g4_encode(gray: &[u8], w: usize, h: usize) -> Vec<u8> {
    let mut e = fax::encoder::Encoder::new(fax::VecWriter::new());
    for y in 0..h {
        let row = &gray[y * w..(y + 1) * w];
        e.encode_line(row.iter().map(|&p| if p < 128 { fax::Color::Black } else { fax::Color::White }), w as u16).unwrap();
    }
    e.finish().unwrap().finish()
}

/// Set bits [a, b) of a packed row, high bit first.
fn fax_set_bits(row: &mut [u8], a: usize, b: usize) {
    if a >= b {
        return;
    }
    let (fa, fb) = (a / 8, (b - 1) / 8);
    let (ma, mb) = (0xffu8 >> (a % 8), 0xffu8 << (7 - (b - 1) % 8));
    if fa == fb {
        row[fa] |= ma & mb;
    } else {
        row[fa] |= ma;
        for v in &mut row[fa + 1..fb] {
            *v = 0xff;
        }
        row[fb] |= mb;
    }
}

/// Group 4 decode with `fax`'s `decode_g4` and paint each row's black runs into zeroed packed rows (1 = black).
pub fn fax_g4_decode(z: &[u8], w: usize, h: usize) -> Vec<u8> {
    let stride = (w + 7) / 8;
    let mut out = vec![0u8; stride * h];
    let mut y = 0;
    fax::decoder::decode_g4(z.iter().copied(), w as u16, Some(h as u16), |t| {
        let row = &mut out[y * stride..(y + 1) * stride];
        let mut i = 0;
        while i < t.len() {
            let a = t[i] as usize;
            let b = if i + 1 < t.len() { t[i + 1] as usize } else { w };
            fax_set_bits(row, a, b.min(w));
            i += 2;
        }
        y += 1;
    })
    .unwrap();
    out
}

// ------------------------------------------------------------------------------ lzw (PDF LZWDecode, MSB first)

fn lzw_width(nxt: usize, early: usize) -> u32 {
    if nxt + early >= 2048 { 12 } else if nxt + early >= 1024 { 11 } else if nxt + early >= 512 { 10 } else { 9 }
}

/// An encoder (test-side: it makes the input). Codes 256 clear, 257 end; the width follows the decoder's table.
pub fn lzw_encode(src: &[u8]) -> Vec<u8> {
    struct W {
        out: Vec<u8>,
        acc: u32,
        n: u32,
    }
    fn put(w: &mut W, code: u32, width: u32) {
        w.acc = (w.acc << width) | code;
        w.n += width;
        while w.n >= 8 {
            w.out.push((w.acc >> (w.n - 8)) as u8);
            w.n -= 8;
        }
        w.acc &= (1u32 << w.n) - 1;
    }
    let mut dict: std::collections::HashMap<u32, u32> = std::collections::HashMap::new();
    let mut w = W { out: Vec::with_capacity(src.len() / 2), acc: 0, n: 0 };
    let mut width = 9u32;
    let mut next = 258usize;
    put(&mut w, 256, 9);
    let mut cur = src[0] as u32;
    for &b in &src[1..] {
        let key = (cur << 8) | b as u32;
        if let Some(&c) = dict.get(&key) {
            cur = c;
            continue;
        }
        put(&mut w, cur, width);
        dict.insert(key, next as u32);
        let before = next;
        next += 1;
        width = lzw_width(before, 1);
        if next >= 4094 {
            put(&mut w, 256, width);
            dict.clear();
            next = 258;
            width = 9;
        }
        cur = b as u32;
    }
    put(&mut w, cur, width);
    put(&mut w, 257, lzw_width(next, 1));
    if w.n > 0 {
        let pad = 8 - w.n;
        let v = (w.acc << pad) as u8;
        w.out.push(v);
    }
    w.out
}

/// The hand-written reference decoder: prefix/suffix/length tables, strings written backwards into the output.
pub fn lzw_decode(src: &[u8], early: usize) -> Vec<u8> {
    let mut out: Vec<u8> = Vec::with_capacity(src.len() * 4);
    let mut prefix = [0u16; 4096];
    let mut suffix = [0u8; 4096];
    let mut len = [0u32; 4096];
    let mut first = [0u8; 4096];
    for i in 0..256 {
        suffix[i] = i as u8;
        len[i] = 1;
        first[i] = i as u8;
    }
    let (mut acc, mut nbits, mut width, mut nxt, mut prev) = (0u32, 0u32, 9u32, 258usize, -1i32);
    let mut i = 0;
    let emit = |out: &mut Vec<u8>, prefix: &[u16; 4096], suffix: &[u8; 4096], len: &[u32; 4096], code: usize| {
        let l = len[code] as usize;
        let base = out.len();
        out.resize(base + l, 0);
        let mut x = code;
        for at in (base..base + l).rev() {
            out[at] = suffix[x];
            x = prefix[x] as usize;
        }
    };
    loop {
        while nbits < width && i < src.len() {
            acc = (acc << 8) | src[i] as u32;
            i += 1;
            nbits += 8;
        }
        if nbits < width {
            break;
        }
        let code = ((acc >> (nbits - width)) & ((1u32 << width) - 1)) as usize;
        nbits -= width;
        acc &= (1u32 << nbits) - 1;
        if code == 256 {
            nxt = 258;
            width = 9;
            prev = -1;
            continue;
        }
        if code == 257 {
            break;
        }
        if prev < 0 {
            if code > 255 {
                break;
            }
            out.push(code as u8);
            prev = code as i32;
            continue;
        }
        if code > nxt {
            break;
        }
        let p = prev as usize;
        let f = if code < nxt { first[code] } else { first[p] };
        if nxt < 4096 {
            prefix[nxt] = p as u16;
            suffix[nxt] = f;
            len[nxt] = len[p] + 1;
            first[nxt] = first[p];
            nxt += 1;
        }
        emit(&mut out, &prefix, &suffix, &len, code);
        width = lzw_width(nxt, early);
        prev = code as i32;
    }
    out
}

// ------------------------------------------------------------------------------ raster (P2.7's native.rs, included)

#[allow(dead_code)]
mod nat {
    include!("../../../bench/native/native.rs");
    pub fn p_histogram(g: &[u8], hist: &mut [i32; 256]) { histogram(g, hist) }
    pub fn p_otsu(hist: &[i32; 256], total: i32) -> i32 { otsu(hist, total) }
    pub fn p_binarise(g: &[u8], m: &mut [u8], thr: i32) -> i32 { binarise(g, m, thr) }
    pub fn p_label(m: &[u8], w: usize, h: usize, labels: &mut [i32], parent: &mut Vec<i32>, area: &mut Vec<i32>, stats: &mut Vec<i32>) -> i32 { label(m, w, h, labels, parent, area, stats) }
    pub fn p_integral(g: &[u8], w: usize, h: usize, isum: &mut Vec<i32>, isq: &mut Vec<i32>) { integral(g, w, h, isum, isq) }
    pub fn p_sauvola(g: &[u8], w: usize, h: usize, isum: &[i32], isq: &[i32], sq: &[i32], m: &mut [u8]) -> i32 { sauvola(g, w, h, isum, isq, sq, m) }
    pub fn p_sqrt_table() -> Vec<i32> { sqrt_table() }
}

pub struct NativeRaster {
    g: Vec<u8>,
    w: usize,
    h: usize,
    hist: [i32; 256],
    pub mask: Vec<u8>,
    m2: Vec<u8>,
    labels: Vec<i32>,
    parent: Vec<i32>,
    area: Vec<i32>,
    pub stats: Vec<i32>,
    isum: Vec<i32>,
    isq: Vec<i32>,
    sqt: Vec<i32>,
    pub thr: i32,
    pub ink: i32,
    pub ink2: i32,
}

impl NativeRaster {
    pub fn new(g: &[u8], w: usize, h: usize) -> NativeRaster {
        NativeRaster {
            g: g.to_vec(),
            w,
            h,
            hist: [0; 256],
            mask: vec![0; w * h],
            m2: vec![0; w * h],
            labels: Vec::new(),
            parent: Vec::new(),
            area: Vec::new(),
            stats: Vec::new(),
            isum: Vec::new(),
            isq: Vec::new(),
            sqt: nat::p_sqrt_table(),
            thr: 0,
            ink: 0,
            ink2: 0,
        }
    }
    /// histogram + Otsu + threshold
    pub fn binarise(&mut self) {
        nat::p_histogram(&self.g, &mut self.hist);
        self.thr = nat::p_otsu(&self.hist, (self.w * self.h) as i32);
        self.ink = nat::p_binarise(&self.g, &mut self.mask, self.thr);
    }
    /// allocate the label plane and label the mask
    pub fn label(&mut self) {
        self.labels.clear();
        self.labels.resize(self.w * self.h, 0);
        nat::p_label(&self.mask, self.w, self.h, &mut self.labels, &mut self.parent, &mut self.area, &mut self.stats);
    }
    /// integral images + Sauvola
    pub fn sauvola(&mut self) {
        nat::p_integral(&self.g, self.w, self.h, &mut self.isum, &mut self.isq);
        self.ink2 = nat::p_sauvola(&self.g, self.w, self.h, &self.isum, &self.isq, &self.sqt, &mut self.m2);
    }
}

// ------------------------------------------------------------------------------ tracer core, by hand

/// kin/trace.kin's `tr-runs`, `tr-unify`, `tr-label` written the way a Rust programmer would: row runs of
/// the mask (x1 exclusive), 8-connected union-find over them (the smaller root wins; path halving), per
/// component top/left/right/bottom/ink/runs/holes, sorted by (top, left, ink, root run).
pub struct NativeTrace {
    runs: Vec<[i32; 3]>,
    row: Vec<usize>,
    parent: Vec<i32>,
    edges: Vec<i32>,
    pid: Vec<i32>,
    acc: Vec<[i32; 8]>,
    pub comps: Vec<[i32; 8]>,
}

fn find(p: &mut [i32], mut a: usize) -> usize {
    loop {
        let q = p[a] as usize;
        if q == a {
            return a;
        }
        let g = p[q];
        p[a] = g;
        a = g as usize;
    }
}

impl NativeTrace {
    pub fn new() -> NativeTrace {
        NativeTrace { runs: Vec::new(), row: Vec::new(), parent: Vec::new(), edges: Vec::new(), pid: Vec::new(), acc: Vec::new(), comps: Vec::new() }
    }

    pub fn run(&mut self, mask: &[u8], w: usize, h: usize) {
        self.runs.clear();
        self.row.clear();
        for y in 0..h {
            self.row.push(self.runs.len());
            let r = &mask[y * w..(y + 1) * w];
            let mut x = 0;
            while x < w {
                while x < w && r[x] == 0 {
                    x += 1;
                }
                if x >= w {
                    break;
                }
                let s = x;
                while x < w && r[x] != 0 {
                    x += 1;
                }
                self.runs.push([y as i32, s as i32, x as i32]);
            }
        }
        self.row.push(self.runs.len());
        let n = self.runs.len();
        self.parent.clear();
        self.parent.extend(0..n as i32);
        self.edges.clear();
        self.edges.resize(n, 0);
        for y in 1..h {
            let (mut i, ie) = (self.row[y - 1], self.row[y]);
            let (mut j, je) = (ie, self.row[y + 1]);
            while i < ie && j < je {
                let (ax0, ax1) = (self.runs[i][1], self.runs[i][2]);
                let (bx0, bx1) = (self.runs[j][1], self.runs[j][2]);
                if bx0 <= ax1 && ax0 <= bx1 {
                    let ra = find(&mut self.parent, i);
                    let rb = find(&mut self.parent, j);
                    if ra != rb {
                        if ra < rb { self.parent[rb] = ra as i32 } else { self.parent[ra] = rb as i32 }
                    }
                    self.edges[j] += 1;
                }
                if ax1 <= bx1 { i += 1 } else { j += 1 }
            }
        }
        // label: provisional ids in run order, accumulate [top left right bottom ink nruns edges root]
        self.pid.clear();
        self.pid.resize(n, 0);
        self.acc.clear();
        for r in 0..n {
            let root = find(&mut self.parent, r);
            let [y, x0, x1] = self.runs[r];
            let id = if root == r {
                self.acc.push([y, x0, x1, y + 1, 0, 0, 0, r as i32]);
                self.pid[r] = (self.acc.len() - 1) as i32;
                self.acc.len() - 1
            } else {
                self.pid[r] = self.pid[root];
                self.pid[root] as usize
            };
            let a = &mut self.acc[id];
            if x0 < a[1] { a[1] = x0 }
            if x1 > a[2] { a[2] = x1 }
            if y + 1 > a[3] { a[3] = y + 1 }
            a[4] += x1 - x0;
            a[5] += 1;
            a[6] += self.edges[r];
        }
        self.acc.sort_unstable_by_key(|a| (a[0], a[1], a[4], a[7]));
        self.comps.clear();
        for a in &self.acc {
            // columns as kin's `cs`: top left right bottom ink nruns holes ... root (13)
            self.comps.push([a[0], a[1], a[2], a[3], a[4], a[5], a[6] - a[5] + 1, a[7]]);
        }
    }
}
