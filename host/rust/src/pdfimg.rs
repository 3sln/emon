// THE PDF IMAGE CODECS, Rust host (DEVLOG P6.1a; SPEC 7.3, 10.11; D117, D291, D332-D470). What a `decode-img` request (kind 6)
// asks for: one PDF image's CODESTREAM in, its pixels out, 8-bit, gray (1 channel) or RGB (3), row-major, top-left, with a
// `bilevel` flag. The pixels are the codestream's and nothing else: never the PDF's `/Decode`, masks or `/Matte` (kin's), and
// never an EXIF orientation (a PDF paints the codestream as it is).
//
// THE TWIN IS `host/go/pdfimg.go`. WHICH LIBRARY DOES WHAT:
//
//   DCT     `zune-jpeg` directly (the `image` crate's own JPEG decoder; the ImageDecode port's JPEG is the same library). 1 and 3
//           components as the library decodes them; 4 components ONLY with an Adobe APP14 marker, converted to RGB honouring it
//           (`dct4`). The Go host: `image/jpeg` and the same rules.
//   CCITT   kin's `emon.ccitt` (P10.11, D396(4)): no platform library reads the whole family (`fax` neither `EncodedByteAlign`, nor
//           Group 3 without EOLs, nor Group 3 two-dimensional; Go's `x/image/ccitt` no two-dimensional Group 3 and no fill bits), so
//           one decoder written from T.4 and T.6 serves TIFF and PDF on every host. The host only paints its packed rows with
//           `BlackIs1` and the image's size. THE TWO HOSTS READ THE SAME SET.
//   JPX     the embedder's `PdfCodecs` (OpenJPEG through `pdfcodecs.rs`, `--cfg codecs`), else `unsupported`. Go: `unsupported`.
//   JBIG2   the same with jbig2dec; its globals are given with the image.
//
// A damaged stream is `Fail::Failed`; a valid one this host does not read is `Fail::Unsupported`; one over the pixel bound is
// `Fail::Limit`. Nothing here names a filesystem (D058).

use crate::emon::ccitt as kccitt;
use crate::imgdec;
use crate::port::{Buf, Cells};
use zune_core::bytestream::ZCursor;
use zune_core::colorspace::ColorSpace;
use zune_core::options::DecoderOptions;
use zune_jpeg::JpegDecoder;

pub use crate::imgdec::Fail as CodecFail;

// What the codec made: `w * h * ch` bytes.
#[derive(Debug, Clone, PartialEq)]
pub struct Pix {
    pub w: i32,
    pub h: i32,
    pub ch: i32,
    pub px: Vec<u8>,
    pub bilevel: bool,
}

// The embedder's JPX and JBIG2 decoders (the two codecs no pure library here reads). Both answer 8-bit pixels at the codestream's
// own size; kin's size rule and the dictionary's are the caller's (`decode`).
//   `jpx`: 1 gray or 3 RGB channels (a JPX with other colour is `Unsupported`; an alpha component is dropped; sYCC is converted).
//   `jbig2`: ONE channel, 0 for a black pixel and 255 for a white one (the PDF's JBIG2Decode convention), the page's own size.
pub trait PdfCodecs: Send + Sync + std::fmt::Debug {
    fn jpx(&self, data: &[u8], max_pixels: u64) -> Result<Pix, CodecFail>;
    fn jbig2(&self, data: &[u8], globals: &[u8], max_pixels: u64) -> Result<Pix, CodecFail>;
}

// `emon.plan`'s `flt` codes
pub const FLT_DCT: i32 = 1;
pub const FLT_JPX: i32 = 2;
pub const FLT_JBIG2: i32 = 3;
pub const FLT_CCITT: i32 = 4;

// The request's fields kin writes (`dq-*`).
#[derive(Debug, Clone, Copy)]
pub struct Args {
    pub filter: i32,
    pub w: i32,
    pub h: i32,
    pub comps: i32,
    pub bpc: i32,
    pub k: i32,
    pub columns: i32,
    pub rows: i32,
    // 1 BlackIs1, 2 EncodedByteAlign, 4 EndOfLine, 8 EndOfBlock false
    pub flags: i32,
}

fn failed<T>(s: &str) -> Result<T, CodecFail> {
    Err(CodecFail::Failed(s.to_string()))
}
fn unsupported<T>(s: &str) -> Result<T, CodecFail> {
    Err(CodecFail::Unsupported(s.to_string()))
}

pub fn decode(a: &Args, data: &[u8], globals: &[u8], codecs: Option<&dyn PdfCodecs>, max_pixels: u64) -> Result<Pix, CodecFail> {
    match a.filter {
        FLT_DCT => dct(data, max_pixels),
        FLT_CCITT => ccitt(a, data, max_pixels),
        FLT_JPX => match codecs {
            Some(c) => c.jpx(data, max_pixels),
            None => unsupported("JPX needs a PdfCodecs (OpenJPEG)"),
        },
        FLT_JBIG2 => match codecs {
            Some(c) => {
                let p = c.jbig2(data, globals, max_pixels)?;
                fit_jbig2(p, a)
            }
            None => unsupported("JBIG2 needs a PdfCodecs (jbig2dec)"),
        },
        _ => unsupported("not an image codec"),
    }
}

// A JBIG2 page is the image, whatever size the dictionary says: it is made the dictionary's size, cut on the right and below and
// padded with white (255) where the page is smaller, as a viewer paints it (the pixels outside the page are the paper's).
fn fit_jbig2(p: Pix, a: &Args) -> Result<Pix, CodecFail> {
    if p.ch != 1 || p.px.len() != (p.w as usize) * (p.h as usize) {
        return failed("a JBIG2 page that is not one channel of its size");
    }
    if p.w == a.w && p.h == a.h {
        return Ok(Pix { bilevel: true, ..p });
    }
    if a.w < 1 || a.h < 1 {
        return failed("a JBIG2 image with no size");
    }
    let mut out = vec![255u8; (a.w as usize) * (a.h as usize)];
    let cw = std::cmp::min(a.w, p.w) as usize;
    for y in 0..std::cmp::min(a.h, p.h) as usize {
        out[y * a.w as usize..y * a.w as usize + cw].copy_from_slice(&p.px[y * p.w as usize..y * p.w as usize + cw]);
    }
    Ok(Pix { w: a.w, h: a.h, ch: 1, px: out, bilevel: true })
}

// ------------------------------------------------------------------------------- DCT

struct JpegHead {
    w: u32,
    h: u32,
    ncomp: u8,
    // the Adobe APP14 marker's transform byte, when there is one
    adobe: Option<u8>,
}

fn jpeg_head(p: &[u8]) -> Result<JpegHead, CodecFail> {
    if p.len() < 4 || p[0] != 0xFF || p[1] != 0xD8 {
        return failed("not a JPEG stream");
    }
    let mut m = JpegHead { w: 0, h: 0, ncomp: 0, adobe: None };
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
            0xEE if body.len() >= 12 && &body[..5] == b"Adobe" && m.adobe.is_none() => m.adobe = Some(body[11]),
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

// The pixels of a JPEG codestream as a PDF paints them. 1 component: gray. 3: RGB (YCbCr converted by the library, or RGB as the
// Adobe marker's transform 0 says). 4: ONLY with an Adobe marker (transform 0, CMYK, or 2, YCCK): Adobe's CMYK is stored inverted,
// so a stored value `s` is ink `255 - s`, and the RGB is `(255 - c)(255 - k) / 255` rounded: `(s_c * s_k + 127) / 255`; YCCK is
// YCbCr -> RGB with libjpeg's integers (`imgdec::ycc_rgb`) for the first three, `K` as stored (D333).
fn dct(data: &[u8], max_pixels: u64) -> Result<Pix, CodecFail> {
    let m = jpeg_head(data)?;
    let (w, h) = (m.w as u64, m.h as u64);
    if w * h > max_pixels {
        return Err(CodecFail::Limit(w * h));
    }
    if m.ncomp != 1 && m.ncomp != 3 && m.ncomp != 4 {
        return unsupported("a JPEG with other than one, three or four components");
    }
    let mut mode4 = 0u8;
    if m.ncomp == 4 {
        match m.adobe {
            Some(0) => mode4 = 1,
            Some(2) => mode4 = 2,
            _ => return unsupported("a four-component JPEG without an Adobe marker of transform 0 or 2"),
        }
    }
    let out_cs = match (m.ncomp, mode4) {
        (1, _) => ColorSpace::Luma,
        (3, _) => ColorSpace::RGB,
        (_, 1) => ColorSpace::CMYK,
        _ => ColorSpace::YCCK,
    };
    let opts = DecoderOptions::default().set_max_width(65535).set_max_height(65535).jpeg_set_out_colorspace(out_cs);
    let mut dec = JpegDecoder::new_with_options(ZCursor::new(data), opts);
    let raw = dec.decode().map_err(|e| CodecFail::Failed(format!("{:?}", e)))?;
    let (iw, ih) = match dec.dimensions() {
        Some((a, b)) => (a, b),
        None => return failed("a JPEG with no size"),
    };
    if iw as u64 != w || ih as u64 != h {
        return failed("a JPEG whose pixels are not the size its header says");
    }
    let n = (w * h) as usize;
    let px = match m.ncomp {
        1 | 3 => {
            if raw.len() != n * m.ncomp as usize {
                return failed("a JPEG the decoder gave in a form that does not match its header");
            }
            raw
        }
        _ => {
            if raw.len() != n * 4 {
                return failed("a JPEG the decoder gave in a form that does not match its header");
            }
            let mut out = Vec::with_capacity(n * 3);
            for q in raw.chunks_exact(4) {
                let (c, mm, y, k) = if mode4 == 1 {
                    (q[0] as u32, q[1] as u32, q[2] as u32, q[3] as u32)
                } else {
                    // YCCK: stored C, M, Y are 255 - (R, G, B); K is stored
                    let rgb = imgdec::ycc_rgb(q[0] as i32, q[1] as i32, q[2] as i32);
                    (255 - rgb[0] as u32, 255 - rgb[1] as u32, 255 - rgb[2] as u32, q[3] as u32)
                };
                out.push(((c * k + 127) / 255) as u8);
                out.push(((mm * k + 127) / 255) as u8);
                out.push(((y * k + 127) / 255) as u8);
            }
            out
        }
    };
    Ok(Pix { w: m.w as i32, h: m.h as i32, ch: if m.ncomp == 1 { 1 } else { 3 }, px, bilevel: false })
}

// ----------------------------------------------------------------------------- CCITT

fn ccitt(a: &Args, data: &[u8], max_pixels: u64) -> Result<Pix, CodecFail> {
    if a.w < 1 || a.h < 1 || a.columns < 1 {
        return failed("a CCITT image with no size");
    }
    if (a.w as u64) * (a.h as u64) > max_pixels {
        return Err(CodecFail::Limit((a.w as u64) * (a.h as u64)));
    }
    if a.columns > 1 << 20 {
        return unsupported("CCITT Columns over 1,048,576");
    }
    let (w, h, cols) = (a.w as usize, a.h as usize, a.columns as usize);
    // the rows the stream has: `Rows`, or the image's height
    let rows = if a.rows > 0 { std::cmp::min(a.rows as usize, h) } else { h };
    // the stream's own area (`Columns` need not be the image's width) is held to the pixel bound too
    if (cols as u64) * (rows as u64) > max_pixels {
        return Err(CodecFail::Limit((cols as u64) * (rows as u64)));
    }
    // the one CCITT decoder (kin's `emon.ccitt`, TIFF's too): T.4 and T.6 over `K`, `Columns`, `Rows`, `EncodedByteAlign` and
    // `EndOfLine`; the rows come packed, high bit first, 1 for black
    let mut ws = Cells::new();
    let mut bits = Buf(vec![0u8; ((cols + 7) / 8) * rows]);
    if kccitt::cc_decode(data, 0, data.len() as i32, a.k, cols as i32, rows as i32, a.flags & 6, &mut bits, &mut ws) != kccitt::cc_ok() {
        return failed("a bad or short CCITT stream");
    }
    let (stride, bits) = ((cols + 7) / 8, &bits.0);
    // the image's size: rows below the stream's and columns past `Columns` are white
    let black_is_1 = a.flags & 1 != 0;
    let (blk, wht) = if black_is_1 { (255u8, 0u8) } else { (0u8, 255u8) };
    let mut px = vec![wht; w * h];
    for y in 0..rows {
        for x in 0..std::cmp::min(w, cols) {
            if (bits[y * stride + x / 8] >> (7 - x % 8)) & 1 == 1 {
                px[y * w + x] = blk;
            }
        }
    }
    Ok(Pix { w: a.w, h: a.h, ch: 1, px, bilevel: true })
}
