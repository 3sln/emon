// THE `ImageDecode` PORT, Rust host (DEVLOG P6.1; SPEC 7.2, 7.3; D058, D089, D187-D190). Encoded bytes (PNG,
// JPEG, TIFF) in, one frame's pixels out, normalised to the Bitmap contract: 8-bit gray or RGB, row-major,
// top-left, EXIF orientation applied, alpha composited onto white, resolution in dots per inch x100 (0 =
// unknown), a `bilevel` flag for one-bit gray. It NEVER resamples.
//
// THE TWIN IS `host/go/imgdec.go`: the two are the same algorithm in two idioms, and `bb identity` holds them
// to the same pixels (PNG, TIFF: exactly; JPEG: within the tolerance D190 records, and through a transcript
// exactly).
//
// WHICH LIBRARY DOES WHAT (D189). PNG and JPEG pixels come from the `image` crate (Go: `image/png`,
// `image/jpeg`); everything AROUND them is ours and is the same in both hosts, because the libraries differ in
// exactly those places: the header fields (size, depth, colour type, resolution, EXIF orientation, Adobe
// marker), the reduction to 8 bits, the alpha, the orientation. TIFF is read by our own container code over
// random access (`Source`), because neither library exposes frames, orientation or raw samples alike: IFDs,
// strips and tiles, then LZW, PackBits, Deflate, CCITT G4 and G3 (1-D) through `fax`, and our own bit
// unpacking and colour. A multi-page TIFF reads only the frame asked for (the others' IFDs are walked, their
// strips never read).
//
// WHAT IT REFUSES, by name, as `Fail::Unsupported` (an honest answer, transcribed): JPEG with four components
// (CMYK/YCCK: P6.1a, with the Adobe marker), JPEG-in-TIFF, old-style LZW, G3 2-D and uncompressed mode, float
// and signed samples, bit depths over 16, Lab, LogLuv, ThunderScan, BigTIFF. A damaged file is `Fail::Failed`.
//
// NOTHING HERE NAMES A FILESYSTEM (D058): a `Source` is a size and `read_at`.

use std::io::Cursor;

// ------------------------------------------------------------------ the contract

pub trait Source {
    fn size(&self) -> u64;
    fn read_at(&self, off: u64, buf: &mut [u8]) -> std::io::Result<usize>;
}

// Bytes in memory as a `Source`.
pub struct Mem<'a>(pub &'a [u8]);

impl<'a> Source for Mem<'a> {
    fn size(&self) -> u64 {
        self.0.len() as u64
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> std::io::Result<usize> {
        if off >= self.0.len() as u64 {
            return Ok(0);
        }
        let s = off as usize;
        let n = std::cmp::min(buf.len(), self.0.len() - s);
        buf[..n].copy_from_slice(&self.0[s..s + n]);
        Ok(n)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Fail {
    // damaged, truncated, or a decoder library said no
    Failed(String),
    // valid, and a thing this port does not read (see the header)
    Unsupported(String),
    // the frame is bigger than the pixel bound: the number of pixels it has
    Limit(u64),
}

fn failed<T>(s: &str) -> Result<T, Fail> {
    Err(Fail::Failed(s.to_string()))
}
fn unsupported<T>(s: &str) -> Result<T, Fail> {
    Err(Fail::Unsupported(s.to_string()))
}

#[derive(Debug, Clone, PartialEq)]
pub struct Decoded {
    // the size AFTER orientation
    pub w: i32,
    pub h: i32,
    // 1 gray or 3 RGB
    pub ch: i32,
    // dots per inch x100 across the displayed page; 0 unknown
    pub dpi100: i32,
    pub bilevel: bool,
    // frames in the input (1 unless a multi-page TIFF)
    pub frames: i32,
    // `w * h * ch` bytes, or none when only the header was asked for
    pub pixels: Option<Vec<u8>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Format {
    Png,
    Jpeg,
    Tiff,
}

// The format by signature (never by name): the Engine's kin sniffs the same bytes (`emon.raster`).
pub fn sniff(head: &[u8]) -> Option<Format> {
    if head.len() >= 8 && head[..8] == [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A] {
        return Some(Format::Png);
    }
    if head.len() >= 3 && head[0] == 0xFF && head[1] == 0xD8 && head[2] == 0xFF {
        return Some(Format::Jpeg);
    }
    if head.len() >= 4 && (head[..4] == [b'I', b'I', 42, 0] || head[..4] == [b'M', b'M', 0, 42]) {
        return Some(Format::Tiff);
    }
    None
}

// `frame` >= 0: that frame's pixels. `frame` < 0: only the header of frame `-frame - 1` (size, resolution,
// frame count), no pixel decoded. `max_pixels` is the PIXELS limit: a bigger frame is `Fail::Limit`.
pub fn decode(src: &dyn Source, frame: i32, max_pixels: u64) -> Result<Decoded, Fail> {
    let mut head = [0u8; 8];
    let n = read_at_most(src, 0, &mut head);
    let want = frame >= 0;
    let f = if want { frame } else { -frame - 1 };
    match sniff(&head[..n]) {
        Some(Format::Png) => single(f, || png(src, want, max_pixels)),
        Some(Format::Jpeg) => single(f, || jpeg(src, want, max_pixels)),
        Some(Format::Tiff) => tiff(src, f, want, max_pixels),
        None => failed("not a PNG, JPEG or TIFF"),
    }
}

fn single(f: i32, g: impl FnOnce() -> Result<Decoded, Fail>) -> Result<Decoded, Fail> {
    if f != 0 {
        return failed("this format has one frame");
    }
    g()
}

fn read_at_most(src: &dyn Source, off: u64, buf: &mut [u8]) -> usize {
    let mut got = 0;
    while got < buf.len() {
        match src.read_at(off + got as u64, &mut buf[got..]) {
            Ok(0) | Err(_) => break,
            Ok(n) => got += n,
        }
    }
    got
}

fn read_exact(src: &dyn Source, off: u64, n: usize) -> Result<Vec<u8>, Fail> {
    if off.checked_add(n as u64).map_or(true, |e| e > src.size()) {
        return failed("a read past the end of the input");
    }
    let mut v = vec![0u8; n];
    if read_at_most(src, off, &mut v) != n {
        return failed("the input is shorter than it said");
    }
    Ok(v)
}

fn read_all(src: &dyn Source) -> Result<Vec<u8>, Fail> {
    read_exact(src, 0, src.size() as usize)
}

// ------------------------------------------------------------- small integer helpers

// n-bit sample -> 8 bits, rounded: (v * 255 + max / 2) / max (D188). Exact for 1, 2, 4 and 8 bits.
fn lut(bits: u32) -> Vec<u8> {
    let max = (1u64 << bits) - 1;
    (0..=max).map(|v| ((v * 255 + max / 2) / max) as u8).collect()
}

fn s16(v: u32) -> u8 {
    ((v as u64 * 255 + 32767) / 65535) as u8
}

// alpha over white, in 8 bits (D188): straight and premultiplied
fn over_white(c: u32, a: u32) -> u8 {
    ((c * a + 255 * (255 - a) + 127) / 255) as u8
}
fn over_white_premul(c: u32, a: u32) -> u8 {
    std::cmp::min(255, c + 255 - a) as u8
}

fn cmyk_rgb(c: u32, m: u32, y: u32, k: u32) -> [u8; 3] {
    let f = |v: u32| (((255 - v) * (255 - k) + 127) / 255) as u8;
    [f(c), f(m), f(y)]
}

fn clamp8(v: i32) -> u8 {
    v.clamp(0, 255) as u8
}

pub(crate) fn ycc_rgb(y: i32, cb: i32, cr: i32) -> [u8; 3] {
    let (cb, cr) = (cb - 128, cr - 128);
    [clamp8(y + ((91881 * cr + 32768) >> 16)), clamp8(y - ((22554 * cb + 46802 * cr + 32768) >> 16)), clamp8(y + ((116130 * cb + 32768) >> 16))]
}

// dots per inch x100 from a rational and a unit: 2 inch, 3 centimetre, anything else unknown. Rounded half up.
fn dpi_rational(num: u64, den: u64, unit: u32) -> i32 {
    if den == 0 || num == 0 {
        return 0;
    }
    let v = match unit {
        2 => (num * 100 * 2 + den) / (den * 2),
        3 => (num * 254 * 2 + den) / (den * 2),
        _ => 0,
    };
    if v > i32::MAX as u64 {
        0
    } else {
        v as i32
    }
}

// ------------------------------------------------------------------ orientation

// EXIF orientation 1..8 applied to `d` (w x h, ch bytes a pixel): the displayed pixels and their size.
fn orient(d: Vec<u8>, w: usize, h: usize, ch: usize, o: u32) -> (Vec<u8>, usize, usize) {
    if o <= 1 || o > 8 {
        return (d, w, h);
    }
    let (ow, oh) = if o >= 5 { (h, w) } else { (w, h) };
    let mut out = vec![0u8; d.len()];
    for y in 0..oh {
        for x in 0..ow {
            let (sx, sy) = match o {
                2 => (w - 1 - x, y),
                3 => (w - 1 - x, h - 1 - y),
                4 => (x, h - 1 - y),
                5 => (y, x),
                6 => (y, h - 1 - x),
                7 => (w - 1 - y, h - 1 - x),
                _ => (w - 1 - y, x),
            };
            let s = (sy * w + sx) * ch;
            let t = (y * ow + x) * ch;
            out[t..t + ch].copy_from_slice(&d[s..s + ch]);
        }
    }
    (out, ow, oh)
}

// ------------------------------------------------------- the TIFF container (also EXIF)

#[derive(Clone)]
struct Entry {
    tag: u16,
    typ: u16,
    count: u32,
    raw: [u8; 4],
}

struct Ifd {
    entries: Vec<Entry>,
    next: u32,
}

struct Tf<'a> {
    src: &'a dyn Source,
    le: bool,
}

fn type_size(t: u16) -> usize {
    match t {
        1 | 2 | 6 | 7 => 1,
        3 | 8 => 2,
        4 | 9 | 11 => 4,
        5 | 10 | 12 => 8,
        _ => 0,
    }
}

impl<'a> Tf<'a> {
    fn new(src: &'a dyn Source) -> Result<Tf<'a>, Fail> {
        let h = read_exact(src, 0, 8)?;
        let le = match &h[..2] {
            b"II" => true,
            b"MM" => false,
            _ => return failed("not a TIFF header"),
        };
        let t = Tf { src, le };
        match t.u16_of(&h[2..4]) {
            42 => Ok(t),
            43 => unsupported("BigTIFF"),
            _ => failed("not a TIFF header"),
        }
    }
    fn u16_of(&self, b: &[u8]) -> u16 {
        if self.le {
            u16::from_le_bytes([b[0], b[1]])
        } else {
            u16::from_be_bytes([b[0], b[1]])
        }
    }
    fn u32_of(&self, b: &[u8]) -> u32 {
        if self.le {
            u32::from_le_bytes([b[0], b[1], b[2], b[3]])
        } else {
            u32::from_be_bytes([b[0], b[1], b[2], b[3]])
        }
    }
    fn first_ifd(&self) -> Result<u32, Fail> {
        let h = read_exact(self.src, 4, 4)?;
        Ok(self.u32_of(&h))
    }
    fn ifd(&self, off: u32) -> Result<Ifd, Fail> {
        if off < 8 {
            return failed("a TIFF directory at a bad offset");
        }
        let c = read_exact(self.src, off as u64, 2)?;
        let n = self.u16_of(&c) as usize;
        let b = read_exact(self.src, off as u64 + 2, n * 12 + 4)?;
        let mut entries = Vec::with_capacity(n);
        for i in 0..n {
            let e = &b[i * 12..i * 12 + 12];
            entries.push(Entry { tag: self.u16_of(&e[0..2]), typ: self.u16_of(&e[2..4]), count: self.u32_of(&e[4..8]), raw: [e[8], e[9], e[10], e[11]] });
        }
        let next = self.u32_of(&b[n * 12..]);
        Ok(Ifd { entries, next })
    }
    // the offsets of the frames: the chain of IFDs, a loop or a bad link ending it
    fn chain(&self) -> Result<Vec<u32>, Fail> {
        let mut out: Vec<u32> = Vec::new();
        let mut seen: std::collections::HashSet<u32> = std::collections::HashSet::new();
        let mut off = self.first_ifd()?;
        while off != 0 && out.len() < (1 << 20) && seen.insert(off) {
            let c = match read_exact(self.src, off as u64, 2) {
                Ok(c) => c,
                Err(e) => {
                    if out.is_empty() {
                        return Err(e);
                    }
                    break;
                }
            };
            let n = self.u16_of(&c) as u64;
            let nb = match read_exact(self.src, off as u64 + 2 + n * 12, 4) {
                Ok(b) => b,
                Err(e) => {
                    if out.is_empty() {
                        return Err(e);
                    }
                    break;
                }
            };
            out.push(off);
            off = self.u32_of(&nb);
        }
        if out.is_empty() {
            return failed("a TIFF with no directory");
        }
        Ok(out)
    }
    // the values of an entry as unsigned integers (BYTE, SHORT, LONG), at most `cap` of them
    fn ints(&self, ifd: &Ifd, tag: u16, cap: usize) -> Result<Option<Vec<u32>>, Fail> {
        let e = match ifd.entries.iter().find(|e| e.tag == tag) {
            Some(e) => e,
            None => return Ok(None),
        };
        let sz = type_size(e.typ);
        if !matches!(e.typ, 1 | 3 | 4) || (e.count as usize) > cap {
            return failed("a TIFF field of a type or size that does not fit");
        }
        let n = e.count as usize;
        let data: Vec<u8> = if sz * n <= 4 { e.raw[..sz * n].to_vec() } else { read_exact(self.src, self.u32_of(&e.raw) as u64, sz * n)? };
        let mut v = Vec::with_capacity(n);
        for i in 0..n {
            v.push(match sz {
                1 => data[i] as u32,
                2 => self.u16_of(&data[i * 2..]) as u32,
                _ => self.u32_of(&data[i * 4..]),
            });
        }
        Ok(Some(v))
    }
    fn int(&self, ifd: &Ifd, tag: u16) -> Result<Option<u32>, Fail> {
        Ok(self.ints(ifd, tag, 1 << 24)?.and_then(|v| v.first().copied()))
    }
    fn rational(&self, ifd: &Ifd, tag: u16) -> Result<Option<(u64, u64)>, Fail> {
        let e = match ifd.entries.iter().find(|e| e.tag == tag) {
            Some(e) => e,
            None => return Ok(None),
        };
        if e.typ != 5 || e.count < 1 {
            return Ok(None);
        }
        let b = read_exact(self.src, self.u32_of(&e.raw) as u64, 8)?;
        Ok(Some((self.u32_of(&b[0..4]) as u64, self.u32_of(&b[4..8]) as u64)))
    }
}

// What the first directory of an EXIF blob says: orientation and the horizontal and vertical resolution
// (dpi x100, 0 unknown). A blob that does not read says nothing.
fn exif_of(blob: &[u8]) -> (u32, i32, i32) {
    let mem = Mem(blob);
    let t = match Tf::new(&mem) {
        Ok(t) => t,
        Err(_) => return (1, 0, 0),
    };
    let ifd = match t.first_ifd().and_then(|o| t.ifd(o)) {
        Ok(i) => i,
        Err(_) => return (1, 0, 0),
    };
    let o = t.int(&ifd, 274).ok().flatten().unwrap_or(1);
    let unit = t.int(&ifd, 296).ok().flatten().unwrap_or(2);
    let r = |tag| t.rational(&ifd, tag).ok().flatten().map_or(0, |(n, d)| dpi_rational(n, d, unit));
    (if (1..=8).contains(&o) { o } else { 1 }, r(282), r(283))
}

// ------------------------------------------------------------------------ PNG

struct PngMeta {
    w: u32,
    h: u32,
    depth: u8,
    ctype: u8,
    interlace: u8,
    dpi: (i32, i32),
    exif: Option<Vec<u8>>,
}

// CRC-32 (IEEE), table-driven: the PNG chunk checksum.
const CRC_TABLE: [u32; 256] = {
    let mut t = [0u32; 256];
    let mut n = 0;
    while n < 256 {
        let mut c = n as u32;
        let mut k = 0;
        while k < 8 {
            c = if c & 1 != 0 { 0xEDB88320 ^ (c >> 1) } else { c >> 1 };
            k += 1;
        }
        t[n] = c;
        n += 1;
    }
    t
};

fn crc32(d: &[u8]) -> u32 {
    let mut c = 0xFFFF_FFFFu32;
    for &b in d {
        c = CRC_TABLE[((c ^ b as u32) & 0xFF) as usize] ^ (c >> 8);
    }
    !c
}

// A PNG is sound when every chunk up to IEND is whole and its checksum is right. The two hosts' PNG libraries differ
// in how much damage they forgive (the Rust one lets an ancillary chunk's checksum or a missing last byte go), and the
// two must answer alike (D190): neither is asked about a file that fails this.
fn png_sound(p: &[u8]) -> bool {
    let mut i = 8usize;
    let mut ctype = 0u8;
    let (mut seen_idat, mut idat_done, mut plte, mut plte_n) = (false, false, false, 0usize);
    loop {
        if i + 12 > p.len() {
            return false;
        }
        let n = u32::from_be_bytes([p[i], p[i + 1], p[i + 2], p[i + 3]]) as usize;
        if n > p.len() || i + 12 + n > p.len() {
            return false;
        }
        let crc = u32::from_be_bytes([p[i + 8 + n], p[i + 9 + n], p[i + 10 + n], p[i + 11 + n]]);
        if crc32(&p[i + 4..i + 8 + n]) != crc {
            return false;
        }
        // The structure is held to one rule set (P10.5, D389; Go's twin is `imgPngSound`): IHDR first and once, then in order PLTE (once,
        // 1..256 entries) and tRNS (of the length its colour type wants; none with an alpha channel) before the first IDAT, the IDATs
        // contiguous, at least one, no unknown critical chunk (a capital first letter), IEND last.
        let kind = &p[i + 4..i + 8];
        if i == 8 {
            if kind != b"IHDR" || n != 13 {
                return false;
            }
            ctype = p[i + 8 + 9];
        } else if kind == b"IHDR" {
            return false;
        }
        if kind == b"IDAT" {
            if idat_done {
                return false;
            }
            seen_idat = true;
        } else if seen_idat {
            idat_done = true;
        }
        match kind {
            b"PLTE" => {
                if seen_idat || plte || n == 0 || n % 3 != 0 || n / 3 > 256 {
                    return false;
                }
                plte = true;
                plte_n = n / 3;
            }
            b"tRNS" => {
                if seen_idat {
                    return false;
                }
                let ok = match ctype {
                    0 => n == 2,
                    2 => n == 6,
                    3 => plte && n <= plte_n,
                    _ => false,
                };
                if !ok {
                    return false;
                }
            }
            b"IEND" => return seen_idat,
            b"IHDR" | b"IDAT" => {}
            _ => {
                if kind[0].is_ascii_uppercase() {
                    return false;
                }
            }
        }
        i += 12 + n;
    }
}

// The byte count of a PNG's decompressed image data, filter bytes included (RFC 2083 8.2): what its header says it holds. 0 when the
// header names a depth or colour type the format does not have (the decoders then refuse it alike).
fn png_raw_size(m: &PngMeta) -> u64 {
    let ch: u64 = match m.ctype {
        0 | 3 => 1,
        2 => 3,
        4 => 2,
        6 => 4,
        _ => 0,
    };
    if ch == 0 || m.depth == 0 || m.depth > 16 {
        return 0;
    }
    let bits = ch * m.depth as u64;
    let (w, h) = (m.w as u64, m.h as u64);
    if m.interlace == 0 {
        return h * (1 + (w * bits + 7) / 8);
    }
    let mut total = 0u64;
    for p in [[0u64, 0, 8, 8], [4, 0, 8, 8], [0, 4, 4, 8], [2, 0, 4, 4], [0, 2, 2, 4], [1, 0, 2, 2], [0, 1, 1, 2]] {
        if w <= p[0] || h <= p[1] {
            continue;
        }
        let (pw, ph) = ((w - p[0] + p[2] - 1) / p[2], (h - p[1] + p[3] - 1) / p[3]);
        total += ph * (1 + (pw * bits + 7) / 8);
    }
    total
}

// The IDAT chunks, joined, are one zlib stream whose checksum is right and which holds exactly the bytes the header says. The two
// hosts' PNG libraries differ here too (Go refuses a bad Adler-32 and surplus data, this crate forgives both), and the two must answer
// alike (D190, P10.5 D389). The caller has seen the chunks are whole (`png_sound`) and the size is in bounds.
fn png_data_sound(p: &[u8], m: &PngMeta) -> bool {
    let want = png_raw_size(m);
    if want == 0 {
        return true;
    }
    let mut idat: Vec<u8> = Vec::new();
    let mut i = 8usize;
    while i + 12 <= p.len() {
        let n = u32::from_be_bytes([p[i], p[i + 1], p[i + 2], p[i + 3]]) as usize;
        if i + 12 + n > p.len() {
            break;
        }
        match &p[i + 4..i + 8] {
            b"IDAT" => idat.extend_from_slice(&p[i + 8..i + 8 + n]),
            b"IEND" => break,
            _ => {}
        }
        i += 12 + n;
    }
    match miniz_oxide::inflate::decompress_to_vec_zlib_with_limit(&idat, want as usize + 1) {
        Ok(v) => v.len() as u64 == want,
        Err(_) => false,
    }
}

// The animation chunks (acTL, fcTL, fdAT) are not read: a PNG's frame is its default image (`frames` is 1). The two hosts' decoders
// judge a damaged animation chunk differently (this crate refuses an fcTL it finds odd, Go's decoder skips all three), so neither is
// shown them (D389). The other chunks, and their checksums, are kept as they are.
fn png_strip_apng(p: Vec<u8>) -> Vec<u8> {
    let mut out = Vec::with_capacity(p.len());
    out.extend_from_slice(&p[..8]);
    let mut i = 8usize;
    let mut cut = false;
    while i + 12 <= p.len() {
        let n = u32::from_be_bytes([p[i], p[i + 1], p[i + 2], p[i + 3]]) as usize;
        if i + 12 + n > p.len() {
            break;
        }
        let kind = &p[i + 4..i + 8];
        if kind == b"acTL" || kind == b"fcTL" || kind == b"fdAT" {
            cut = true;
        } else {
            out.extend_from_slice(&p[i..i + 12 + n]);
        }
        i += 12 + n;
    }
    if !cut {
        return p;
    }
    out.extend_from_slice(&p[i..]);
    out
}

fn png_meta(p: &[u8]) -> Result<PngMeta, Fail> {
    let mut m = PngMeta { w: 0, h: 0, depth: 0, ctype: 255, interlace: 0, dpi: (0, 0), exif: None };
    let mut have = false;
    let mut i = 8usize;
    while i + 8 <= p.len() {
        let n = u32::from_be_bytes([p[i], p[i + 1], p[i + 2], p[i + 3]]) as usize;
        let kind = &p[i + 4..i + 8];
        if i + 8 + n > p.len() {
            break;
        }
        let body = &p[i + 8..i + 8 + n];
        match kind {
            b"IHDR" if n >= 13 => {
                m.w = u32::from_be_bytes([body[0], body[1], body[2], body[3]]);
                m.h = u32::from_be_bytes([body[4], body[5], body[6], body[7]]);
                m.depth = body[8];
                m.ctype = body[9];
                m.interlace = body[12];
                have = true;
            }
            b"pHYs" if n >= 9 && body[8] == 1 => {
                let x = u32::from_be_bytes([body[0], body[1], body[2], body[3]]) as u64;
                let y = u32::from_be_bytes([body[4], body[5], body[6], body[7]]) as u64;
                // pixels per metre -> dpi x100: ppm * 100 / 39.37 = ppm * 254 / 100
                let f = |v: u64| ((v * 254 + 50) / 100).min(i32::MAX as u64) as i32;
                m.dpi = (f(x), f(y));
            }
            b"eXIf" => m.exif = Some(body.to_vec()),
            b"IEND" => break,
            _ => {}
        }
        i += 12 + n;
    }
    if !have || m.w == 0 || m.h == 0 {
        return failed("a PNG without a usable header");
    }
    Ok(m)
}

fn png(src: &dyn Source, want: bool, max_pixels: u64) -> Result<Decoded, Fail> {
    let bytes = read_all(src)?;
    if !png_sound(&bytes) {
        return failed("a PNG with a damaged chunk, a bad checksum or no IEND");
    }
    let m = png_meta(&bytes)?;
    let (o, ex, ey) = m.exif.as_deref().map_or((1, 0, 0), exif_of);
    let gray = m.ctype == 0 || m.ctype == 4;
    let (w, h) = (m.w as u64, m.h as u64);
    let (ow, oh) = if o >= 5 { (h, w) } else { (w, h) };
    let (dx, dy) = if m.dpi.0 > 0 { m.dpi } else { (ex, ey) };
    let mut out = Decoded { w: ow as i32, h: oh as i32, ch: if gray { 1 } else { 3 }, dpi100: if o >= 5 { dy } else { dx }, bilevel: m.ctype == 0 && m.depth == 1, frames: 1, pixels: None };
    if w * h > max_pixels {
        return Err(Fail::Limit(w * h));
    }
    if ow > i32::MAX as u64 || oh > i32::MAX as u64 {
        return Err(Fail::Limit(u64::MAX));
    }
    if !want {
        return Ok(out);
    }
    if !png_data_sound(&bytes, &m) {
        return failed("a PNG whose image data is not one whole zlib stream of the bytes its header says");
    }
    use image::DynamicImage as D;
    let bytes = png_strip_apng(bytes);
    let mut dec = image::codecs::png::PngDecoder::new(Cursor::new(&bytes[..])).map_err(|e| Fail::Failed(e.to_string()))?;
    let _ = image::ImageDecoder::set_limits(&mut dec, image::Limits::no_limits());
    let img = D::from_decoder(dec).map_err(|e| Fail::Failed(e.to_string()))?;
    let (iw, ih) = (img.width() as usize, img.height() as usize);
    if iw as u64 != w || ih as u64 != h {
        return failed("a PNG whose pixels are not the size its header says");
    }
    let n = iw * ih;
    let mut px = vec![0u8; n * out.ch as usize];
    match img {
        D::ImageLuma8(b) if gray => px.copy_from_slice(b.as_raw()),
        D::ImageLumaA8(b) if gray => {
            for (i, c) in b.as_raw().chunks_exact(2).enumerate() {
                px[i] = over_white(c[0] as u32, c[1] as u32);
            }
        }
        D::ImageRgb8(b) if !gray => px.copy_from_slice(b.as_raw()),
        D::ImageRgba8(b) if !gray => {
            for (i, c) in b.as_raw().chunks_exact(4).enumerate() {
                for k in 0..3 {
                    px[i * 3 + k] = over_white(c[k] as u32, c[3] as u32);
                }
            }
        }
        D::ImageLuma16(b) if gray => {
            for (i, c) in b.as_raw().iter().enumerate() {
                px[i] = s16(*c as u32);
            }
        }
        D::ImageLumaA16(b) if gray => {
            for (i, c) in b.as_raw().chunks_exact(2).enumerate() {
                px[i] = over_white(s16(c[0] as u32) as u32, s16(c[1] as u32) as u32);
            }
        }
        D::ImageRgb16(b) if !gray => {
            for (i, c) in b.as_raw().iter().enumerate() {
                px[i] = s16(*c as u32);
            }
        }
        D::ImageRgba16(b) if !gray => {
            for (i, c) in b.as_raw().chunks_exact(4).enumerate() {
                let a = s16(c[3] as u32) as u32;
                for k in 0..3 {
                    px[i * 3 + k] = over_white(s16(c[k] as u32) as u32, a);
                }
            }
        }
        _ => return failed("a PNG the decoder gave in a form that does not match its header"),
    }
    let (px, _, _) = orient(px, iw, ih, out.ch as usize, o);
    out.pixels = Some(px);
    Ok(out)
}

// ----------------------------------------------------------------------- JPEG

struct JpegMeta {
    w: u32,
    h: u32,
    ncomp: u8,
    dpi: (i32, i32),
    exif: Option<Vec<u8>>,
}

fn jpeg_meta(p: &[u8]) -> Result<JpegMeta, Fail> {
    let mut m = JpegMeta { w: 0, h: 0, ncomp: 0, dpi: (0, 0), exif: None };
    let mut i = 2usize;
    let mut sof = false;
    while i + 4 <= p.len() {
        if p[i] != 0xFF {
            i += 1;
            continue;
        }
        let mk = p[i + 1];
        if mk == 0xFF {
            i += 1;
            continue;
        }
        if mk == 0xD8 || mk == 0x01 || (0xD0..=0xD7).contains(&mk) || mk == 0 {
            i += 2;
            continue;
        }
        if mk == 0xD9 || mk == 0xDA {
            break;
        }
        let len = u16::from_be_bytes([p[i + 2], p[i + 3]]) as usize;
        if len < 2 || i + 2 + len > p.len() {
            break;
        }
        let body = &p[i + 4..i + 2 + len];
        match mk {
            0xE0 if body.len() >= 12 && &body[..5] == b"JFIF\0" => {
                let units = body[7];
                let xd = u16::from_be_bytes([body[8], body[9]]) as i64;
                let yd = u16::from_be_bytes([body[10], body[11]]) as i64;
                let f = |d: i64| match units {
                    1 => (d * 100) as i32,
                    2 => (d * 254) as i32,
                    _ => 0,
                };
                m.dpi = (f(xd), f(yd));
            }
            0xE1 if body.len() > 6 && &body[..6] == b"Exif\0\0" && m.exif.is_none() => m.exif = Some(body[6..].to_vec()),
            0xC0..=0xCF if mk != 0xC4 && mk != 0xC8 && mk != 0xCC && !sof => {
                if body.len() >= 6 {
                    m.h = u16::from_be_bytes([body[1], body[2]]) as u32;
                    m.w = u16::from_be_bytes([body[3], body[4]]) as u32;
                    m.ncomp = body[5];
                    sof = true;
                }
            }
            _ => {}
        }
        i += 2 + len;
    }
    if !sof || m.w == 0 || m.h == 0 {
        return failed("a JPEG without a usable frame header");
    }
    Ok(m)
}

fn jpeg(src: &dyn Source, want: bool, max_pixels: u64) -> Result<Decoded, Fail> {
    let bytes = read_all(src)?;
    let m = jpeg_meta(&bytes)?;
    if m.ncomp != 1 && m.ncomp != 3 {
        return unsupported("a JPEG with other than one or three components (CMYK: P6.1a)");
    }
    let (o, ex, ey) = m.exif.as_deref().map_or((1, 0, 0), exif_of);
    let (w, h) = (m.w as u64, m.h as u64);
    let (ow, oh) = if o >= 5 { (h, w) } else { (w, h) };
    let (dx, dy) = if m.dpi.0 > 0 { m.dpi } else { (ex, ey) };
    let dpi = if o >= 5 { dy } else { dx };
    let mut out = Decoded { w: ow as i32, h: oh as i32, ch: m.ncomp as i32, dpi100: dpi, bilevel: false, frames: 1, pixels: None };
    if w * h > max_pixels {
        return Err(Fail::Limit(w * h));
    }
    if !want {
        return Ok(out);
    }
    use image::DynamicImage as D;
    let mut dec = image::codecs::jpeg::JpegDecoder::new(Cursor::new(&bytes[..])).map_err(|e| Fail::Failed(e.to_string()))?;
    let _ = image::ImageDecoder::set_limits(&mut dec, image::Limits::no_limits());
    let img = D::from_decoder(dec).map_err(|e| Fail::Failed(e.to_string()))?;
    let (iw, ih) = (img.width() as usize, img.height() as usize);
    if iw as u64 != w || ih as u64 != h {
        return failed("a JPEG whose pixels are not the size its header says");
    }
    let px = match img {
        D::ImageLuma8(b) if m.ncomp == 1 => b.into_raw(),
        D::ImageRgb8(b) if m.ncomp == 3 => b.into_raw(),
        _ => return failed("a JPEG the decoder gave in a form that does not match its header"),
    };
    let (px, _, _) = orient(px, iw, ih, out.ch as usize, o);
    out.pixels = Some(px);
    Ok(out)
}

// ----------------------------------------------------------------------- TIFF

fn tiff(src: &dyn Source, f: i32, want: bool, max_pixels: u64) -> Result<Decoded, Fail> {
    let t = Tf::new(src)?;
    let chain = t.chain()?;
    if f as usize >= chain.len() {
        return failed("a frame that the TIFF does not have");
    }
    let ifd = t.ifd(chain[f as usize])?;
    let mut d = tiff_frame(&t, &ifd, want, max_pixels)?;
    d.frames = chain.len() as i32;
    Ok(d)
}

fn bit_reverse(b: &mut [u8]) {
    for x in b.iter_mut() {
        *x = x.reverse_bits();
    }
}

// PackBits, stopping at `expect` bytes; short data is zero-filled by the caller
fn packbits(d: &[u8], expect: usize) -> Vec<u8> {
    let mut out = Vec::with_capacity(expect);
    let mut i = 0;
    while i < d.len() && out.len() < expect {
        let n = d[i] as i8 as i32;
        i += 1;
        if n >= 0 {
            let c = n as usize + 1;
            let e = std::cmp::min(i + c, d.len());
            out.extend_from_slice(&d[i..e]);
            i = e;
        } else if n != -128 {
            if i >= d.len() {
                break;
            }
            let c = (1 - n) as usize;
            out.extend(std::iter::repeat(d[i]).take(c));
            i += 1;
        }
    }
    out.truncate(expect);
    out
}

// TIFF LZW (MSB first, early change), the new-style stream only
fn lzw(d: &[u8], expect: usize) -> Result<Vec<u8>, Fail> {
    // the pre-1988 stream (libtiff's "compat"): codes LSB first, the width grows when the table is full
    // (no early change). Told by its first bytes, as libtiff does.
    let compat = d.len() >= 2 && d[0] == 0 && (d[1] & 1) == 1;
    let early = if compat { 0 } else { 1 };
    let mut prefix = vec![0u16; 4096];
    let mut suffix = vec![0u8; 4096];
    let mut out: Vec<u8> = Vec::with_capacity(expect);
    let mut next = 258usize;
    let mut width = 9u32;
    let (mut acc, mut nbits) = (0u32, 0u32);
    let mut pos = 0usize;
    let mut prev: i32 = -1;
    let mut stack: Vec<u8> = Vec::new();
    loop {
        while nbits < width {
            if pos >= d.len() {
                out.truncate(expect);
                return Ok(out);
            }
            if compat {
                acc |= (d[pos] as u32) << nbits;
            } else {
                acc = (acc << 8) | d[pos] as u32;
            }
            pos += 1;
            nbits += 8;
        }
        let code;
        if compat {
            code = (acc & ((1 << width) - 1)) as usize;
            acc >>= width;
            nbits -= width;
        } else {
            code = ((acc >> (nbits - width)) & ((1 << width) - 1)) as usize;
            nbits -= width;
            acc &= (1u32 << nbits) - 1;
        }
        if code == 257 {
            break;
        }
        if code == 256 {
            next = 258;
            width = 9;
            prev = -1;
            continue;
        }
        stack.clear();
        if prev < 0 {
            if code > 255 {
                return failed("a bad LZW code");
            }
            stack.push(code as u8);
        } else {
            let mut c = if code < next { code } else if code == next { prev as usize } else { return failed("a bad LZW code") };
            while c > 255 {
                stack.push(suffix[c]);
                c = prefix[c] as usize;
            }
            stack.push(c as u8);
            let first = c as u8;
            if code == next {
                // the string of `prev`, then its own first byte
                stack.insert(0, first);
            }
            if next < 4096 {
                prefix[next] = prev as u16;
                suffix[next] = first;
                next += 1;
            }
        }
        out.extend(stack.iter().rev());
        prev = code as i32;
        width = if next + early < 512 { 9 } else if next + early < 1024 { 10 } else if next + early < 2048 { 11 } else { 12 };
        if out.len() >= expect {
            break;
        }
    }
    out.truncate(expect);
    Ok(out)
}

fn inflate(d: &[u8], expect: usize) -> Result<Vec<u8>, Fail> {
    match miniz_oxide::inflate::decompress_to_vec_zlib_with_limit(d, expect) {
        Ok(v) => Ok(v),
        Err(e) => {
            if e.status == miniz_oxide::inflate::TINFLStatus::HasMoreOutput {
                let mut v = e.output;
                v.truncate(expect);
                Ok(v)
            } else {
                failed("a bad Deflate stream")
            }
        }
    }
}

// CCITT (bits: 1 = black, rows byte-aligned) -> `rows` rows of `cw` pixels
fn ccitt(d: &[u8], g4: bool, cw: usize, rows: usize) -> Result<Vec<u8>, Fail> {
    let stride = (cw + 7) / 8;
    let mut out = vec![0u8; stride * rows];
    let mut y = 0usize;
    // a G3 row is whole when its runs add up to the width: a damaged one is told apart by it (the decoders differ in
    // what they make of a row that does not)
    let mut bad_row = false;
    let mut put = |tr: &[u16]| {
        if y >= rows {
            return;
        }
        if !g4 && tr.last().map_or(true, |&l| l as usize != cw) {
            bad_row = true;
        }
        // a G4 row's changing elements never go back and never pass the width
        if g4 && (tr.windows(2).any(|p| p[1] < p[0]) || tr.last().map_or(false, |&l| l as usize > cw)) {
            bad_row = true;
        }
        // transitions: white run, black run, white run, ...
        let mut x = 0usize;
        let mut black = false;
        for &p in tr {
            let e = std::cmp::min(p as usize, cw);
            if black {
                for i in x..e {
                    out[y * stride + i / 8] |= 0x80 >> (i % 8);
                }
            }
            x = std::cmp::max(x, e);
            black = !black;
        }
        if black {
            for i in x..cw {
                out[y * stride + i / 8] |= 0x80 >> (i % 8);
            }
        }
        y += 1;
    };
    if g4 {
        if cw > u16::MAX as usize || rows > u16::MAX as usize {
            return unsupported("a G4 strip wider or taller than 65535");
        }
        fax::decoder::decode_g4(d.iter().copied(), cw as u16, Some(rows as u16), |tr| put(tr)).ok_or_else(|| Fail::Failed("a bad CCITT G4 stream".to_string()))?;
        if bad_row {
            return failed("a bad CCITT G4 stream");
        }
    } else {
        // Group 3 one-dimensional, EOL-framed. `fax` drops the last
        // line of a stream that has no EOL after it (libtiff writes none), so six EOLs (the RTC) are appended:
        // they close the last line and end the decode, and do nothing to a stream that has its own.
        let mut data = d.to_vec();
        data.extend_from_slice(&[0x00, 0x10, 0x01, 0x00, 0x10, 0x01, 0x00, 0x10, 0x01]);
        let _ = fax::decoder::decode_g3(data.iter().copied(), |tr| put(tr));
        // every row must have come: a damaged stream is told apart by the rows it did not give, which is
        // all that `fax` and `x/image/ccitt` (whose error recovery differs) can be asked
        if y < rows || bad_row {
            return failed("a short or damaged CCITT G3 stream");
        }
    }
    Ok(out)
}

struct Photo {
    // 0 MinIsWhite 1 MinIsBlack 2 RGB 3 palette 5 CMYK 6 YCbCr
    kind: u32,
    spp: usize,
    nc: usize,
    bps: u32,
    alpha: Option<bool>,
    cmap: Vec<u16>,
}

fn tiff_frame(t: &Tf, ifd: &Ifd, want: bool, max_pixels: u64) -> Result<Decoded, Fail> {
    let one = |tag| t.int(ifd, tag);
    let w = one(256)?.ok_or_else(|| Fail::Failed("a TIFF without a width".to_string()))? as usize;
    let h = one(257)?.ok_or_else(|| Fail::Failed("a TIFF without a height".to_string()))? as usize;
    if w == 0 || h == 0 {
        return failed("a TIFF frame with no pixels");
    }
    let spp = one(277)?.unwrap_or(1) as usize;
    if spp == 0 || spp > 16 {
        return failed("a TIFF with a strange sample count");
    }
    let mut bpsv = t.ints(ifd, 258, 64)?.unwrap_or_else(|| vec![1]);
    if bpsv.is_empty() {
        bpsv = vec![1];
    }
    while bpsv.len() < spp {
        bpsv.push(bpsv[bpsv.len() - 1]);
    }
    if bpsv[..spp].iter().any(|&b| b != bpsv[0]) {
        return unsupported("TIFF samples of different depths");
    }
    let bps = bpsv[0];
    if bps == 0 {
        return failed("a TIFF with a strange bit depth");
    }
    if bps > 16 {
        return unsupported("TIFF samples deeper than 16 bits");
    }
    if let Some(sf) = t.ints(ifd, 339, 64)? {
        // 1 unsigned, 4 undefined (read as unsigned, as libtiff does); 2 signed and 3 float are not read
        if sf.iter().any(|&v| v != 1 && v != 4) {
            return unsupported("TIFF float or signed samples");
        }
    }
    let comp = one(259)?.unwrap_or(1);
    let kind = match one(262)? {
        Some(k) => k,
        None => {
            if comp == 3 || comp == 4 {
                0
            } else if spp >= 3 {
                2
            } else {
                1
            }
        }
    };
    let nc = match kind {
        0 | 1 | 3 => 1,
        2 | 6 => 3,
        5 => 4,
        _ => return unsupported("this TIFF photometric interpretation (Lab, LogLuv, a mask)"),
    };
    if spp < nc {
        return failed("a TIFF with fewer samples than its colour needs");
    }
    if kind == 5 && one(332)?.unwrap_or(1) != 1 {
        return unsupported("a TIFF separation that is not CMYK");
    }
    let alpha = if spp > nc {
        match t.ints(ifd, 338, 64)?.and_then(|v| v.first().copied()) {
            Some(1) => Some(true),
            Some(2) => Some(false),
            _ => None,
        }
    } else {
        None
    };
    let mut cmap: Vec<u16> = Vec::new();
    if kind == 3 {
        let v = t.ints(ifd, 320, 3 * 65536)?.ok_or_else(|| Fail::Failed("a palette TIFF with no colour map".to_string()))?;
        if v.len() < 3 << bps {
            return failed("a TIFF colour map shorter than its depth needs");
        }
        cmap = v.iter().map(|&x| x as u16).collect();
    }
    if kind == 6 && (bps != 8 || spp != 3) {
        return unsupported("YCbCr TIFF other than 8 bits, three samples");
    }
    if (comp == 3 || comp == 4) && (bps != 1 || spp != 1) {
        return failed("a CCITT TIFF that is not one-bit, one-sample");
    }
    if !matches!(comp, 1 | 3 | 4 | 5 | 8 | 32773 | 32946) {
        return unsupported("this TIFF compression (JPEG, ThunderScan, SGILog, ...)");
    }
    let mut t4 = 0;
    if comp == 3 {
        t4 = one(292)?.unwrap_or(0);
        // bit 0 two-dimensional, bit 1 uncompressed mode, bit 2 fill bits before each EOL: the Go host's
        // x/image/ccitt reads none of them, and the two hosts must read the same set (D189)
        if t4 & 7 != 0 {
            return unsupported("CCITT Group 3 two-dimensional, uncompressed mode or fill bits");
        }
    }
    let planar2 = one(284)?.unwrap_or(1) == 2 && spp > 1;
    if planar2 && kind == 6 {
        return unsupported("planar YCbCr");
    }
    let predictor = one(317)?.unwrap_or(1);
    if predictor != 1 && !(predictor == 2 && (bps == 8 || bps == 16)) {
        return unsupported("this TIFF predictor");
    }
    let fill2 = one(266)?.unwrap_or(1) == 2;
    let orient_tag = one(274)?.filter(|o| (1..=8).contains(o)).unwrap_or(1);
    let unit = one(296)?.unwrap_or(2);
    let res = |tag| t.rational(ifd, tag).ok().flatten().map_or(0, |(n, d)| dpi_rational(n, d, unit));
    let (rx, ry) = (res(282), res(283));
    let ch = if matches!(kind, 0 | 1) { 1 } else { 3 };
    let (ow, oh) = if orient_tag >= 5 { (h, w) } else { (w, h) };
    let mut out = Decoded { w: ow as i32, h: oh as i32, ch: ch as i32, dpi100: if orient_tag >= 5 { ry } else { rx }, bilevel: matches!(kind, 0 | 1) && bps == 1, frames: 1, pixels: None };
    let pixels = w as u64 * h as u64;
    if pixels > max_pixels {
        return Err(Fail::Limit(pixels));
    }
    if ow > i32::MAX as usize || oh > i32::MAX as usize {
        return Err(Fail::Limit(u64::MAX));
    }
    if !want {
        return Ok(out);
    }
    let ph = Photo { kind, spp, nc, bps, alpha, cmap };

    // the chunks: tiles or strips
    let tiled = t.ints(ifd, 322, 1)?.is_some();
    let (cw0, band_rows, across, down, offs, cnts);
    if tiled {
        let tw = one(322)?.unwrap_or(0) as usize;
        let th = one(323)?.unwrap_or(0) as usize;
        if tw == 0 || th == 0 || tw > 1 << 20 || th > 1 << 20 {
            return failed("a TIFF with strange tiles");
        }
        cw0 = tw;
        band_rows = th;
        across = (w + tw - 1) / tw;
        down = (h + th - 1) / th;
        // some writers (SGI's) put a tiled image's offsets in the strip tags: libtiff reads those too
        let o = match t.ints(ifd, 324, 1 << 24)? {
            Some(o) => Some(o),
            None => t.ints(ifd, 273, 1 << 24)?,
        };
        offs = o.ok_or_else(|| Fail::Failed("a tiled TIFF with no tile offsets".to_string()))?;
        let c = match t.ints(ifd, 325, 1 << 24)? {
            Some(c) => Some(c),
            None => t.ints(ifd, 279, 1 << 24)?,
        };
        cnts = c.ok_or_else(|| Fail::Failed("a tiled TIFF with no tile byte counts".to_string()))?;
    } else {
        let mut rps = one(278)?.unwrap_or(h as u32) as usize;
        if rps == 0 || rps > h {
            rps = h;
        }
        cw0 = w;
        band_rows = rps;
        across = 1;
        down = (h + rps - 1) / rps;
        offs = t.ints(ifd, 273, 1 << 24)?.ok_or_else(|| Fail::Failed("a TIFF with no strip offsets".to_string()))?;
        cnts = t.ints(ifd, 279, 1 << 24)?.unwrap_or_default();
    }
    let planes = if planar2 { spp } else { 1 };
    let per_plane = across * down;
    if offs.len() < per_plane * planes {
        return failed("a TIFF with fewer strips or tiles than its size needs");
    }
    let (hs, vs) = if kind == 6 {
        let v = t.ints(ifd, 530, 2)?.unwrap_or_else(|| vec![2, 2]);
        if v.len() != 2 || !matches!(v[0], 1 | 2 | 4) || !matches!(v[1], 1 | 2 | 4) {
            return failed("a TIFF with strange chroma subsampling");
        }
        (v[0] as usize, v[1] as usize)
    } else {
        (1, 1)
    };
    let cs = if planar2 { 1 } else { spp };
    let mut px = vec![0u8; w * h * ch];
    let table = lut(bps);
    let mut samp: Vec<u16> = Vec::new();
    for by in 0..down {
        let y0 = by * band_rows;
        let rows = std::cmp::min(band_rows, h - y0);
        samp.clear();
        samp.resize(rows * w * spp, 0);
        for p in 0..planes {
            for bx in 0..across {
                let idx = p * per_plane + by * across + bx;
                let off = offs[idx] as u64;
                let n = match cnts.get(idx) {
                    Some(&c) => c as u64,
                    None if per_plane * planes == 1 => t.src.size().saturating_sub(off),
                    None => return failed("a TIFF with no byte count for a strip"),
                };
                let mut data = read_exact(t.src, off, n as usize)?;
                if fill2 {
                    bit_reverse(&mut data);
                }
                let cw = cw0;
                let crows = if tiled { band_rows } else { rows };
                let rowb = (cw * cs * bps as usize + 7) / 8;
                let expect = if kind == 6 { ((cw + hs - 1) / hs) * ((crows + vs - 1) / vs) * (hs * vs + 2) } else { rowb * crows };
                let mut raw = match comp {
                    1 => data,
                    32773 => packbits(&data, expect),
                    5 => lzw(&data, expect)?,
                    8 | 32946 => inflate(&data, expect)?,
                    4 => ccitt(&data, true, cw, crows)?,
                    _ => {
                        let _ = t4;
                        ccitt(&data, false, cw, crows)?
                    }
                };
                raw.resize(expect, 0);
                if predictor == 2 {
                    for r in 0..crows {
                        let row = &mut raw[r * rowb..(r + 1) * rowb];
                        if bps == 8 {
                            for i in cs..cw * cs {
                                row[i] = row[i].wrapping_add(row[i - cs]);
                            }
                        } else {
                            for i in cs..cw * cs {
                                let a = t.u16_of(&row[i * 2..]).wrapping_add(t.u16_of(&row[(i - cs) * 2..]));
                                let b = if t.le { a.to_le_bytes() } else { a.to_be_bytes() };
                                row[i * 2..i * 2 + 2].copy_from_slice(&b);
                            }
                        }
                    }
                }
                let x0 = bx * cw0;
                let ncols = std::cmp::min(cw0, w - x0);
                if kind == 6 {
                    let (bw, _bh) = ((cw + hs - 1) / hs, (crows + vs - 1) / vs);
                    for r in 0..rows {
                        for c in 0..ncols {
                            let blk = (r / vs) * bw + c / hs;
                            let base = blk * (hs * vs + 2);
                            let yv = raw[base + (r % vs) * hs + c % hs] as u16;
                            let o = (r * w + x0 + c) * 3;
                            samp[o] = yv;
                            samp[o + 1] = raw[base + hs * vs] as u16;
                            samp[o + 2] = raw[base + hs * vs + 1] as u16;
                        }
                    }
                } else {
                    for r in 0..rows {
                        let row = &raw[r * rowb..(r + 1) * rowb];
                        for c in 0..ncols {
                            for s in 0..cs {
                                let k = c * cs + s;
                                let v: u16 = match bps {
                                    8 => row[k] as u16,
                                    16 => t.u16_of(&row[k * 2..]),
                                    b => {
                                        let bit = k * b as usize;
                                        let mut v = 0u32;
                                        for j in 0..b as usize {
                                            let q = bit + j;
                                            v = (v << 1) | ((row[q / 8] >> (7 - q % 8)) & 1) as u32;
                                        }
                                        v as u16
                                    }
                                };
                                let si = if planar2 { p } else { s };
                                samp[(r * w + x0 + c) * spp + si] = v;
                            }
                        }
                    }
                }
            }
        }
        // convert the band
        for r in 0..rows {
            let srow = &samp[r * w * spp..(r + 1) * w * spp];
            let drow = &mut px[(y0 + r) * w * ch..(y0 + r + 1) * w * ch];
            convert_row(&ph, &table, srow, drow);
        }
    }
    let (px, _, _) = orient(px, w, h, ch, orient_tag);
    out.pixels = Some(px);
    Ok(out)
}

fn convert_row(ph: &Photo, table: &[u8], s: &[u16], d: &mut [u8]) {
    let spp = ph.spp;
    let n = s.len() / spp;
    let alpha = |p: &[u16]| -> Option<u32> { ph.alpha.map(|_| table[p[ph.nc] as usize] as u32) };
    let comp = |c: u32, a: Option<u32>| -> u8 {
        match (a, ph.alpha) {
            (Some(a), Some(true)) => over_white_premul(c, a),
            (Some(a), _) => over_white(c, a),
            _ => c as u8,
        }
    };
    for x in 0..n {
        let p = &s[x * spp..(x + 1) * spp];
        let a = alpha(p);
        match ph.kind {
            0 | 1 => {
                let mut g = table[p[0] as usize] as u32;
                if ph.kind == 0 {
                    g = 255 - g;
                }
                d[x] = comp(g, a);
            }
            2 => {
                for k in 0..3 {
                    d[x * 3 + k] = comp(table[p[k] as usize] as u32, a);
                }
            }
            3 => {
                let n = 1usize << ph.bps;
                let i = p[0] as usize;
                for k in 0..3 {
                    d[x * 3 + k] = s16(ph.cmap[k * n + i] as u32);
                }
            }
            5 => {
                let rgb = cmyk_rgb(table[p[0] as usize] as u32, table[p[1] as usize] as u32, table[p[2] as usize] as u32, table[p[3] as usize] as u32);
                for k in 0..3 {
                    d[x * 3 + k] = comp(rgb[k] as u32, a);
                }
            }
            _ => {
                let rgb = ycc_rgb(p[0] as i32, p[1] as i32, p[2] as i32);
                d[x * 3..x * 3 + 3].copy_from_slice(&rgb);
            }
        }
    }
}

// a convenience for callers that hold the bytes
pub fn decode_bytes(b: &[u8], frame: i32, max_pixels: u64) -> Result<Decoded, Fail> {
    decode(&Mem(b), frame, max_pixels)
}
