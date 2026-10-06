// THE LIVE JPX AND JBIG2 DECODERS, Rust host (DEVLOG P6.1a; D117, D291, D464): OpenJPEG (`libopenjp2`) and jbig2dec
// (`libjbig2dec`) through a hand-written FFI, under `--cfg codecs` (kin/scripts/emon-rs-build, `EMON_RS_CODECS=DIR`), the way
// pdfium and Tesseract are: LIVE ONLY. A gate replays a transcript recorded with them (`bb asset-record`); the Go host
// answers `unsupported` for both live and replays the same transcript. Both libraries are C, so a damaged stream is the
// library's to survive: this module checks sizes before it hands anything over, and a library that crashes takes the
// process with it (which is why they are not in the default build).
//
// OpenJPEG: a codestream (`.j2k`) or a JP2 file, from memory through a read/skip/seek stream. 8-bit pixels: a component of `prec`
// bits `v` becomes `(v * 255 + max / 2) / max` (a signed one is shifted up first), sub-sampled components are replicated, an
// alpha component is dropped, sYCC is converted (libjpeg's integers). 1 colour component is gray, 3 are RGB; anything else
// (CMYK, 2 or 4 colours) is `Unsupported`.
// jbig2dec: the PDF embedded-stream form, with the globals stream given first (a global context). A black pixel (1) is 0 and a
// white one is 255, the JBIG2Decode filter's convention.

use crate::pdfimg::{CodecFail, PdfCodecs, Pix};
use std::os::raw::{c_char, c_int, c_void};

#[repr(C)]
struct OpjComp {
    dx: u32,
    dy: u32,
    w: u32,
    h: u32,
    x0: u32,
    y0: u32,
    prec: u32,
    bpp: u32,
    sgnd: u32,
    resno_decoded: u32,
    factor: u32,
    data: *mut i32,
    alpha: u16,
}

#[repr(C)]
struct OpjImage {
    x0: u32,
    y0: u32,
    x1: u32,
    y1: u32,
    numcomps: u32,
    color_space: c_int,
    comps: *mut OpjComp,
    icc_profile_buf: *mut u8,
    icc_profile_len: u32,
}

#[repr(C)]
struct Jbig2Image {
    width: u32,
    height: u32,
    stride: u32,
    data: *mut u8,
    refcount: c_int,
}

type ReadFn = extern "C" fn(*mut c_void, usize, *mut c_void) -> usize;
type SkipFn = extern "C" fn(i64, *mut c_void) -> i64;
type SeekFn = extern "C" fn(i64, *mut c_void) -> c_int;
type MsgFn = extern "C" fn(*const c_char, *mut c_void);
type JbMsgFn = extern "C" fn(*mut c_void, *const c_char, c_int, u32);

#[link(name = "openjp2")]
extern "C" {
    fn opj_create_decompress(format: c_int) -> *mut c_void;
    fn opj_destroy_codec(c: *mut c_void);
    fn opj_set_default_decoder_parameters(p: *mut c_void);
    fn opj_setup_decoder(c: *mut c_void, p: *mut c_void) -> c_int;
    fn opj_stream_create(size: usize, input: c_int) -> *mut c_void;
    fn opj_stream_destroy(s: *mut c_void);
    fn opj_stream_set_read_function(s: *mut c_void, f: ReadFn);
    fn opj_stream_set_skip_function(s: *mut c_void, f: SkipFn);
    fn opj_stream_set_seek_function(s: *mut c_void, f: SeekFn);
    fn opj_stream_set_user_data(s: *mut c_void, d: *mut c_void, free: Option<extern "C" fn(*mut c_void)>);
    fn opj_stream_set_user_data_length(s: *mut c_void, n: u64);
    fn opj_read_header(s: *mut c_void, c: *mut c_void, out: *mut *mut OpjImage) -> c_int;
    fn opj_decode(c: *mut c_void, s: *mut c_void, i: *mut OpjImage) -> c_int;
    fn opj_end_decompress(c: *mut c_void, s: *mut c_void) -> c_int;
    fn opj_image_destroy(i: *mut OpjImage);
    fn opj_set_info_handler(c: *mut c_void, f: MsgFn, d: *mut c_void) -> c_int;
    fn opj_set_warning_handler(c: *mut c_void, f: MsgFn, d: *mut c_void) -> c_int;
    fn opj_set_error_handler(c: *mut c_void, f: MsgFn, d: *mut c_void) -> c_int;
}

#[link(name = "jbig2dec")]
extern "C" {
    fn jbig2_ctx_new_imp(alloc: *mut c_void, options: c_int, global: *mut c_void, cb: Option<JbMsgFn>, data: *mut c_void, major: c_int, minor: c_int) -> *mut c_void;
    fn jbig2_ctx_free(ctx: *mut c_void) -> *mut c_void;
    fn jbig2_make_global_ctx(ctx: *mut c_void) -> *mut c_void;
    fn jbig2_global_ctx_free(g: *mut c_void) -> *mut c_void;
    fn jbig2_data_in(ctx: *mut c_void, data: *const u8, size: usize) -> c_int;
    fn jbig2_page_out(ctx: *mut c_void) -> *mut Jbig2Image;
    fn jbig2_release_page(ctx: *mut c_void, i: *mut Jbig2Image);
    fn jbig2_complete_page(ctx: *mut c_void) -> c_int;
}

// the header's JBIG2_VERSION_MAJOR and MINOR the library was built against (jbig2dec 0.20)
const JB_MAJOR: c_int = 0;
const JB_MINOR: c_int = 20;

extern "C" fn quiet(_m: *const c_char, _d: *mut c_void) {}
extern "C" fn jb_quiet(_d: *mut c_void, _m: *const c_char, _s: c_int, _i: u32) {}

struct Mem {
    d: *const u8,
    n: usize,
    pos: usize,
}

extern "C" fn mem_read(buf: *mut c_void, nb: usize, user: *mut c_void) -> usize {
    let m = unsafe { &mut *(user as *mut Mem) };
    if m.pos >= m.n {
        return usize::MAX;
    }
    let k = std::cmp::min(nb, m.n - m.pos);
    unsafe { std::ptr::copy_nonoverlapping(m.d.add(m.pos), buf as *mut u8, k) };
    m.pos += k;
    k
}
extern "C" fn mem_skip(nb: i64, user: *mut c_void) -> i64 {
    let m = unsafe { &mut *(user as *mut Mem) };
    let np = (m.pos as i64).saturating_add(nb).clamp(0, m.n as i64);
    let moved = np - m.pos as i64;
    m.pos = np as usize;
    moved
}
extern "C" fn mem_seek(to: i64, user: *mut c_void) -> c_int {
    let m = unsafe { &mut *(user as *mut Mem) };
    if to < 0 || to as usize > m.n {
        return 0;
    }
    m.pos = to as usize;
    1
}

// OpenJPEG and jbig2dec behind the `PdfCodecs` port
#[derive(Debug, Default, Clone, Copy)]
pub struct Codecs;

impl Codecs {
    pub fn new() -> Codecs {
        Codecs
    }
}

fn scale(v: i64, prec: u32, sgnd: bool) -> u8 {
    let v = if sgnd { v + (1i64 << (prec - 1)) } else { v };
    let max = (1i64 << prec) - 1;
    let v = v.clamp(0, max);
    ((v * 255 + max / 2) / max) as u8
}

impl PdfCodecs for Codecs {
    fn jpx(&self, data: &[u8], max_pixels: u64) -> Result<Pix, CodecFail> {
        // JP2 file (the 12-byte signature box) or a raw codestream (SOC and SIZ markers)
        let fmt = if data.len() >= 12 && data[..12] == [0, 0, 0, 12, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A] {
            2
        } else if data.len() >= 4 && data[..4] == [0xFF, 0x4F, 0xFF, 0x51] {
            0
        } else {
            return Err(CodecFail::Failed("not a JPEG 2000 stream".to_string()));
        };
        unsafe {
            let codec = opj_create_decompress(fmt);
            if codec.is_null() {
                return Err(CodecFail::Failed("OpenJPEG would not make a decoder".to_string()));
            }
            opj_set_info_handler(codec, quiet, std::ptr::null_mut());
            opj_set_warning_handler(codec, quiet, std::ptr::null_mut());
            opj_set_error_handler(codec, quiet, std::ptr::null_mut());
            // the decoder's parameters are an opaque struct of a few KB (two 4 KB file names among them)
            let mut params = vec![0u64; 4096];
            opj_set_default_decoder_parameters(params.as_mut_ptr() as *mut c_void);
            if opj_setup_decoder(codec, params.as_mut_ptr() as *mut c_void) == 0 {
                opj_destroy_codec(codec);
                return Err(CodecFail::Failed("OpenJPEG would not set up".to_string()));
            }
            let mut mem = Mem { d: data.as_ptr(), n: data.len(), pos: 0 };
            let stream = opj_stream_create(1 << 16, 1);
            opj_stream_set_read_function(stream, mem_read);
            opj_stream_set_skip_function(stream, mem_skip);
            opj_stream_set_seek_function(stream, mem_seek);
            opj_stream_set_user_data(stream, &mut mem as *mut Mem as *mut c_void, None);
            opj_stream_set_user_data_length(stream, data.len() as u64);
            let mut img: *mut OpjImage = std::ptr::null_mut();
            let done = |img: *mut OpjImage, r: Result<Pix, CodecFail>| {
                if !img.is_null() {
                    opj_image_destroy(img);
                }
                opj_stream_destroy(stream);
                opj_destroy_codec(codec);
                r
            };
            if opj_read_header(stream, codec, &mut img) == 0 || img.is_null() {
                return done(img, Err(CodecFail::Failed("a bad JPEG 2000 header".to_string())));
            }
            let (x0, y0, x1, y1, nc, cs) = ((*img).x0, (*img).y0, (*img).x1, (*img).y1, (*img).numcomps, (*img).color_space);
            let (w, h) = ((x1.wrapping_sub(x0)) as u64, (y1.wrapping_sub(y0)) as u64);
            if w == 0 || h == 0 || x1 <= x0 || y1 <= y0 {
                return done(img, Err(CodecFail::Failed("a JPEG 2000 image with no size".to_string())));
            }
            if w * h > max_pixels {
                return done(img, Err(CodecFail::Limit(w * h)));
            }
            if opj_decode(codec, stream, img) == 0 || opj_end_decompress(codec, stream) == 0 {
                return done(img, Err(CodecFail::Failed("a damaged JPEG 2000 stream".to_string())));
            }
            if nc == 0 || nc > 8 || (*img).comps.is_null() {
                return done(img, Err(CodecFail::Failed("a JPEG 2000 image with no components".to_string())));
            }
            let comps = std::slice::from_raw_parts((*img).comps, nc as usize);
            // the colour components: those that are not alpha (a JP2 channel definition says which), or, with none said, the
            // first one (gray, or two with an alpha), the first three (RGB or sYCC, or four with an alpha)
            let colour: Vec<usize> = (0..nc as usize).filter(|&i| comps[i].alpha == 0).collect();
            let ncol = match (colour.len(), nc) {
                (1, _) | (_, 2) => 1,
                (3, _) | (_, 4) => 3,
                (_, 3) => 3,
                _ => 0,
            };
            let has_alpha_flag = comps.iter().any(|c| c.alpha != 0);
            let chosen: Vec<usize> = if has_alpha_flag { colour.clone() } else { (0..ncol).collect() };
            if cs == 5 || cs == 4 && ncol == 1 || ncol == 0 || chosen.len() != ncol {
                return done(img, Err(CodecFail::Unsupported("a JPEG 2000 colour that is not gray or RGB".to_string())));
            }
            for &i in &chosen {
                let c = &comps[i];
                if c.data.is_null() || c.dx == 0 || c.dy == 0 || c.prec == 0 || c.prec > 31 || (c.w as u64) * (c.h as u64) == 0 {
                    return done(img, Err(CodecFail::Failed("a JPEG 2000 component with no samples".to_string())));
                }
            }
            let (w, h) = (w as usize, h as usize);
            let mut px = vec![0u8; w * h * ncol];
            for (ci, &i) in chosen.iter().enumerate() {
                let c = &comps[i];
                let (cw, ch) = (c.w as usize, c.h as usize);
                let d = std::slice::from_raw_parts(c.data, cw * ch);
                for y in 0..h {
                    let sy = std::cmp::min(y / c.dy as usize, ch - 1);
                    for x in 0..w {
                        let sx = std::cmp::min(x / c.dx as usize, cw - 1);
                        px[(y * w + x) * ncol + ci] = scale(d[sy * cw + sx] as i64, c.prec, c.sgnd != 0);
                    }
                }
            }
            if ncol == 3 && cs == 3 {
                for q in px.chunks_exact_mut(3) {
                    let rgb = crate::imgdec::ycc_rgb(q[0] as i32, q[1] as i32, q[2] as i32);
                    q.copy_from_slice(&rgb);
                }
            }
            done(img, Ok(Pix { w: w as i32, h: h as i32, ch: ncol as i32, px, bilevel: false }))
        }
    }

    fn jbig2(&self, data: &[u8], globals: &[u8], max_pixels: u64) -> Result<Pix, CodecFail> {
        unsafe {
            let mut gctx: *mut c_void = std::ptr::null_mut();
            let mut gl: *mut c_void = std::ptr::null_mut();
            if !globals.is_empty() {
                gctx = jbig2_ctx_new_imp(std::ptr::null_mut(), 1, std::ptr::null_mut(), Some(jb_quiet), std::ptr::null_mut(), JB_MAJOR, JB_MINOR);
                if gctx.is_null() {
                    return Err(CodecFail::Failed("jbig2dec would not make a context".to_string()));
                }
                let _ = jbig2_data_in(gctx, globals.as_ptr(), globals.len());
                gl = jbig2_make_global_ctx(gctx);
            }
            let ctx = jbig2_ctx_new_imp(std::ptr::null_mut(), 1, gl, Some(jb_quiet), std::ptr::null_mut(), JB_MAJOR, JB_MINOR);
            if ctx.is_null() {
                if !gl.is_null() {
                    jbig2_global_ctx_free(gl);
                }
                return Err(CodecFail::Failed("jbig2dec would not make a context".to_string()));
            }
            let rc = jbig2_data_in(ctx, data.as_ptr(), data.len());
            let _ = jbig2_complete_page(ctx);
            let page = if rc < 0 { std::ptr::null_mut() } else { jbig2_page_out(ctx) };
            let r = if page.is_null() {
                Err(CodecFail::Failed("a damaged JBIG2 stream".to_string()))
            } else {
                let (w, h, stride) = ((*page).width as usize, (*page).height as usize, (*page).stride as usize);
                if w == 0 || h == 0 || (w as u64) * (h as u64) > max_pixels {
                    if w == 0 || h == 0 {
                        Err(CodecFail::Failed("a JBIG2 page with no size".to_string()))
                    } else {
                        Err(CodecFail::Limit((w as u64) * (h as u64)))
                    }
                } else {
                    let bits = std::slice::from_raw_parts((*page).data, stride * h);
                    let mut px = vec![255u8; w * h];
                    for y in 0..h {
                        for x in 0..w {
                            if (bits[y * stride + x / 8] >> (7 - x % 8)) & 1 == 1 {
                                px[y * w + x] = 0;
                            }
                        }
                    }
                    Ok(Pix { w: w as i32, h: h as i32, ch: 1, px, bilevel: true })
                }
            };
            if !page.is_null() {
                jbig2_release_page(ctx, page);
            }
            jbig2_ctx_free(ctx);
            if !gl.is_null() {
                jbig2_global_ctx_free(gl);
            } else if !gctx.is_null() {
                jbig2_ctx_free(gctx);
            }
            r
        }
    }
}
