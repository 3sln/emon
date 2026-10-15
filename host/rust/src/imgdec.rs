// THE `ImageDecode` PORT, Rust host (DEVLOG P6.1, P10.11; SPEC 7.2, 7.3; D058, D089, D187-D190, D396). Encoded bytes (PNG,
// JPEG, TIFF) in, one frame's pixels out, normalised to the Bitmap contract: 8-bit gray or RGB, row-major,
// top-left, EXIF orientation applied, alpha composited onto white, resolution in dots per inch x100 (0 =
// unknown), a `bilevel` flag for one-bit gray. It NEVER resamples.
//
// THE TWIN IS `host/go/imgdec.go`: the two are the same algorithm in two idioms, and `bb identity` holds them
// to the same pixels (PNG, TIFF: exactly; JPEG: within the tolerance D190 records, and through a transcript
// exactly).
//
// WHAT AN IMAGE MAY CONTAIN IS KIN'S (D396(4), P10.11). `emon.pngcheck` and `emon.tiffcheck` parse the container, decide accept or
// refuse (every rule of D389 for a PNG; the byte order, the chain of directories, every tag, the strips and tiles for a TIFF), apply
// the pixel and byte limits, and report the size, orientation, resolution, colour model and the D188 reduction; the platform library
// is asked for pixels only of input they accepted, and is shown only the chunks that matter. So two libraries' leniency is never a
// difference between hosts. CCITT G3 and G4 are kin's too (`emon.ccitt`, the decoder PDF uses): no library reads the whole family.
//
// WHICH LIBRARY DOES WHAT (D189). PNG and JPEG pixels come from the `image` crate (Go: `image/png`, `image/jpeg`); the reduction to 8
// bits, the alpha and the orientation are ours and are the same in both hosts. A JPEG's header (size, components, JFIF, the Adobe
// marker) is read here: JPEG stays native (D190). TIFF samples are unpacked from the strips and tiles the validator listed by our
// own code (neither library exposes frames, orientation or raw samples alike): PackBits, LZW, Deflate (`miniz_oxide`), the
// predictor, bit unpacking and colour. A multi-page TIFF reads only the frame asked for.
//
// WHAT IT REFUSES, by name, as `Fail::Unsupported` (an honest answer, transcribed): JPEG with four components
// (CMYK/YCCK: P6.1a, with the Adobe marker), JPEG-in-TIFF, G3 2-D and uncompressed mode as TIFF compressions (the validator keeps
// refusing them: D189's sets), float and signed samples, bit depths over 16, Lab, LogLuv, ThunderScan, BigTIFF. A damaged file
// is `Fail::Failed`; one over the pixel bound `Fail::Limit`.
//
// NOTHING HERE NAMES A FILESYSTEM (D058): a `Source` is a size and `read_at`.

use crate::emon::ccitt as kccitt;
use crate::emon::{imgcheck, pngcheck, tiffcheck};
use crate::port::{cells_at, Buf, Cells};
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
// frame count), no pixel decoded. A frame bigger than the PIXELS limit (`limit-of` 9) is `Fail::Limit`.
pub fn decode(src: &dyn Source, frame: i32) -> Result<Decoded, Fail> {
    let mut head = [0u8; 8];
    let n = read_at_most(src, 0, &mut head);
    let want = frame >= 0;
    let f = if want { frame } else { -frame - 1 };
    match sniff(&head[..n]) {
        Some(Format::Png) => single(f, || png(src, want)),
        Some(Format::Jpeg) => single(f, || jpeg(src, want)),
        Some(Format::Tiff) => tiff(src, f, want),
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

// --------------------------------------------------------------- the validators (kin)
//
// WHAT AN IMAGE MAY CONTAIN IS KIN'S (D396(4), P10.11): `emon.pngcheck` and `emon.tiffcheck` read the container, decide, and
// report what the host needs; a platform library is asked for pixels only of input they accepted. A refusal comes back as a
// status (damaged, unsupported, over a limit) and is turned into `Fail` here; nothing else of the container is read by this file.

fn at(r: &Cells, i: i32) -> i32 {
    cells_at(r, i)
}

fn refused<T>(status: i32, what: &str, limit: i32) -> Result<T, Fail> {
    if status == imgcheck::ic_unsupported() {
        return Err(Fail::Unsupported(what.to_string()));
    }
    if status == imgcheck::ic_limit() {
        return Err(Fail::Limit(limit as u64));
    }
    Err(Fail::Failed(what.to_string()))
}

// What the first directory of an EXIF blob says: orientation and the horizontal and vertical resolution
// (dpi x100, 0 unknown). A blob that does not read says nothing.
fn exif_of(blob: &[u8]) -> (u32, i32, i32) {
    let mut r = Cells::new();
    tiffcheck::tc_exif(blob, 0, blob.len() as i32, &mut r);
    (at(&r, tiffcheck::tr_eo()) as u32, at(&r, tiffcheck::tr_ex()), at(&r, tiffcheck::tr_ey()))
}

// ------------------------------------------------------------------------ PNG

fn png(src: &dyn Source, want: bool) -> Result<Decoded, Fail> {
    let bytes = read_all(src)?;
    let (mut r, mut st, mut out) = (Cells::new(), Cells::new(), Cells::new());
    let (mut idat, mut clean) = (Buf::new(), Buf::new());
    let s = pngcheck::pc_check(&bytes, want as i32, &mut r, &mut st, &mut out, &mut idat, &mut clean);
    drop((bytes, st, out, idat));
    if s != imgcheck::ic_ok() {
        return refused(s, "a PNG the validator refused: damaged, or past the rules of D389", at(&r, pngcheck::pr_limit()));
    }
    let g = |i: i32| at(&r, i);
    let (w, h) = (g(pngcheck::pr_w()) as usize, g(pngcheck::pr_h()) as usize);
    let gray = g(pngcheck::pr_ch()) == 1;
    let mut out = Decoded { w: g(pngcheck::pr_ow()), h: g(pngcheck::pr_oh()), ch: g(pngcheck::pr_ch()), dpi100: g(pngcheck::pr_dpi()), bilevel: g(pngcheck::pr_bilevel()) != 0, frames: 1, pixels: None };
    if !want {
        return Ok(out);
    }
    let o = g(pngcheck::pr_orient()) as u32;
    // the library is shown the chunks that matter, whole and in order, and nothing else (`clean`)
    use image::DynamicImage as D;
    let mut dec = image::codecs::png::PngDecoder::new(Cursor::new(&clean.0[..])).map_err(|e| Fail::Failed(e.to_string()))?;
    let _ = image::ImageDecoder::set_limits(&mut dec, image::Limits::no_limits());
    let img = D::from_decoder(dec).map_err(|e| Fail::Failed(e.to_string()))?;
    let (iw, ih) = (img.width() as usize, img.height() as usize);
    if iw != w || ih != h {
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

fn jpeg(src: &dyn Source, want: bool) -> Result<Decoded, Fail> {
    let max_pixels = crate::port::limit_of(9) as u64;
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

// CCITT through kin's one decoder (`emon.ccitt`, TIFF's and PDF's): `rows` rows of `cw` pixels, bits packed high first, 1 for a black
// run (the libtiff reading, D188), rows byte-aligned. `ws` is the decoder's scratch, kept for every strip of an image.
fn ccitt(d: &[u8], k: i32, cw: usize, rows: usize, ws: &mut Cells) -> Result<Vec<u8>, Fail> {
    let mut out = Buf(vec![0u8; ((cw + 7) / 8) * rows]);
    if kccitt::cc_decode(d, 0, d.len() as i32, k, cw as i32, rows as i32, 0, &mut out, ws) != kccitt::cc_ok() {
        return failed("a short or damaged CCITT stream");
    }
    Ok(out.0)
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

fn u16_of(le: bool, b: &[u8]) -> u16 {
    if le {
        u16::from_le_bytes([b[0], b[1]])
    } else {
        u16::from_be_bytes([b[0], b[1]])
    }
}

// THE TIFF: the whole file is read, `emon.tiffcheck` decides (the byte order, the chain of frames, every tag, the bounds of every
// strip and tile, the pixel limit) and reports; what is left here is turning the chunks it listed into samples and the samples into
// the 8-bit pixels of D188. A multi-page TIFF reads only the frame asked for.
fn tiff(src: &dyn Source, f: i32, want: bool) -> Result<Decoded, Fail> {
    let bytes = read_all(src)?;
    let mut r = Cells::new();
    let s = tiffcheck::tc_check(&bytes, f, want as i32, &mut r);
    if s != imgcheck::ic_ok() {
        return refused(s, "a TIFF the validator refused: damaged, or a coding the port does not read (D188)", at(&r, tiffcheck::tr_limit()));
    }
    let g = |i: i32| at(&r, i);
    let mut out = Decoded {
        w: g(tiffcheck::tr_ow()),
        h: g(tiffcheck::tr_oh()),
        ch: g(tiffcheck::tr_ch()),
        dpi100: g(tiffcheck::tr_dpi()),
        bilevel: g(tiffcheck::tr_bilevel()) != 0,
        frames: g(tiffcheck::tr_frames()),
        pixels: None,
    };
    if !want {
        return Ok(out);
    }
    out.pixels = Some(tiff_pixels(&bytes, &r)?);
    Ok(out)
}

fn tiff_pixels(bytes: &[u8], rep: &Cells) -> Result<Vec<u8>, Fail> {
    let g = |i: i32| at(rep, i);
    let (w, h) = (g(tiffcheck::tr_w()) as usize, g(tiffcheck::tr_h()) as usize);
    let (spp, bps) = (g(tiffcheck::tr_spp()) as usize, g(tiffcheck::tr_bps()) as u32);
    let (comp, kind, nc) = (g(tiffcheck::tr_comp()), g(tiffcheck::tr_kind()) as u32, g(tiffcheck::tr_nc()) as usize);
    let alpha = match g(tiffcheck::tr_alpha()) {
        1 => Some(true),
        2 => Some(false),
        _ => None,
    };
    let (predictor, planar2, fill2, le) = (g(tiffcheck::tr_predictor()), g(tiffcheck::tr_planar()) == 1, g(tiffcheck::tr_fill2()) == 1, g(tiffcheck::tr_le()) == 1);
    let tiled = g(tiffcheck::tr_tiled()) == 1;
    let (cw0, band_rows) = (g(tiffcheck::tr_cw()) as usize, g(tiffcheck::tr_band()) as usize);
    let (across, down) = (g(tiffcheck::tr_across()) as usize, g(tiffcheck::tr_down()) as usize);
    let (hs, vs) = (g(tiffcheck::tr_hs()) as usize, g(tiffcheck::tr_vs()) as usize);
    let planes = g(tiffcheck::tr_planes()) as usize;
    let (offs_at, lens_at) = (g(tiffcheck::tr_offs_at()), g(tiffcheck::tr_lens_at()));
    let ch = g(tiffcheck::tr_ch()) as usize;
    let orient_tag = g(tiffcheck::tr_orient()) as u32;
    let cmap: Vec<u16> = if kind == 3 { (0..3i32 << bps).map(|i| g(g(tiffcheck::tr_cmap_at()) + i) as u16).collect() } else { Vec::new() };
    let ph = Photo { kind, spp, nc, bps, alpha, cmap };

    let per_plane = across * down;
    let cs = if planar2 { 1 } else { spp };
    let mut px = vec![0u8; w * h * ch];
    let table = lut(bps);
    let mut samp: Vec<u16> = Vec::new();
    let mut ws = Cells::new();
    for by in 0..down {
        let y0 = by * band_rows;
        let rows = std::cmp::min(band_rows, h - y0);
        samp.clear();
        samp.resize(rows * w * spp, 0);
        for p in 0..planes {
            for bx in 0..across {
                let idx = (p * per_plane + by * across + bx) as i32;
                let off = g(offs_at + idx) as u32 as usize;
                let n = g(lens_at + idx) as u32 as usize;
                let mut data = bytes[off..off + n].to_vec();
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
                    4 => ccitt(&data, -1, cw, crows, &mut ws)?,
                    // Group 3: `k` 4 when T4Options says two-dimensional (a row after each EOL and its tag bit), else 0 (P12.6)
                    _ => ccitt(&data, if g(tiffcheck::tr_t4()) & 1 != 0 { 4 } else { 0 }, cw, crows, &mut ws)?,
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
                                let a = u16_of(le, &row[i * 2..]).wrapping_add(u16_of(le, &row[(i - cs) * 2..]));
                                let b = if le { a.to_le_bytes() } else { a.to_be_bytes() };
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
                                    16 => u16_of(le, &row[k * 2..]),
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
    Ok(px)
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
pub fn decode_bytes(b: &[u8], frame: i32) -> Result<Decoded, Fail> {
    decode(&Mem(b), frame)
}
