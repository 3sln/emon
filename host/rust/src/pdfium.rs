// The Rust host's binding to pdfium's C API (DEVLOG P6.2, D127; SPEC 7.2, D007). The default `Renderer`.
//
// COMPILED ONLY UNDER `--cfg pdfium`, and then linked with `-lpdfium`. Nothing in `bb gates` builds it: a
// gate replays a committed transcript (`bb render`), the engine being the host's opinion (D008). The binary
// is the pinned bblanchon/pdfium-binaries build that `bb pdfium-fetch` installs (tools/pdfium/pdfium.edn),
// and `bb render-record` builds this file against it:
//
//     RUSTFLAGS='--cfg pdfium -L native=.cache/pdfium/current/lib -C link-arg=-Wl,-rpath,<abs lib dir>'
//
// HOW A DOCUMENT IS READ (D058, D074). Through `FPDF_LoadCustomDocument` and its `FPDF_FILEACCESS`: pdfium
// asks for `(position, size)` blocks and `get_block` answers each from the run's `ByteSource::read_at`. No
// path is opened, no copy of the file is made and nothing assumes the whole file is resident; pdfium reads
// the xref and the objects a page needs, and a source that is a network range reader pays for those spans.
//
// HOW A LAYER IS MADE. pdfium has no "hide the text" switch, so the page's objects are edited IN MEMORY
// after `FPDF_LoadPage` (`FPDFPage_RemoveObject`, and `FPDFFormObj_RemoveObject` inside form XObjects) and
// the page is rendered; the document is never saved and the page is closed afterwards, so nothing leaks
// from one request to the next. Layers:
//     full            everything, annotations' normal appearances painted (`FPDF_ANNOT`)
//     no-text         text objects removed (annotation appearances stay: their text is not separable)
//     text-only       only text objects (no annotations)
// The watermark and background layers (`LAYER_NO_WATERMARK`, `LAYER_WATERMARK_ONLY`) are `unsupported`: they
// are defined by `/Artifact` marked content whose `/Type` and `/Subtype` are NAMES, and pdfium's public
// mark API reads a mark's name and its string, number and blob parameters but has no reader for a name
// (`FPDFPageObjMark_GetParamValueType` says NAME; `..GetParamStringValue` and `..GetParamBlobValue` answer
// false). Guessing from the key alone would drop headers and footers with the watermark, so the engine says
// it cannot (SPEC 7.2) and kin separates them itself (SPEC 8.1 reads the marks; SPEC 8.2.1 separates ink).
//
// A CROP is not done here. `exact_crop` is false: a render clipped to a rectangle moves the pixels on the
// clip's edge (a vertical rule at the left edge of a crop of a bank statement differed by up to 107 of 255
// from the same pixels cut out of the whole page, D127), so `RenderFulfiller` renders the whole page, keeps it,
// and cuts (SPEC 7.2: "the same pixels, cut out, never a re-fit").
//
// pdfium is NOT thread-safe: every call below holds one process-wide lock, so any number of `Pdfium`
// values and threads may be used and the renders happen one at a time.
use crate::port::Bitmap;
use crate::render::*;
use crate::source::ByteSource;
use std::ffi::{c_char, c_int, c_uchar, c_ulong, c_void, CString};
use std::sync::{Mutex, Once};

type Doc = *mut c_void;
type Page = *mut c_void;
type Bm = *mut c_void;
type Obj = *mut c_void;

#[repr(C)]
struct FileAccess {
    len: c_ulong,
    get_block: extern "C" fn(*mut c_void, c_ulong, *mut c_uchar, c_ulong) -> c_int,
    param: *mut c_void,
}

#[cfg(not(pdfium_dl))]
#[link(name = "pdfium")]
extern "C" {
    fn FPDF_InitLibrary();
    fn FPDF_GetLastError() -> c_ulong;
    fn FPDF_LoadCustomDocument(access: *mut FileAccess, password: *const c_char) -> Doc;
    fn FPDF_CloseDocument(d: Doc);
    fn FPDF_GetPageCount(d: Doc) -> c_int;
    fn FPDF_LoadPage(d: Doc, index: c_int) -> Page;
    fn FPDF_ClosePage(p: Page);
    fn FPDF_GetPageWidthF(p: Page) -> f32;
    fn FPDF_GetPageHeightF(p: Page) -> f32;
    fn FPDFBitmap_CreateEx(w: c_int, h: c_int, format: c_int, first_scan: *mut c_void, stride: c_int) -> Bm;
    fn FPDFBitmap_FillRect(b: Bm, left: c_int, top: c_int, w: c_int, h: c_int, color: c_ulong) -> c_int;
    fn FPDFBitmap_Destroy(b: Bm);
    fn FPDF_RenderPageBitmap(b: Bm, p: Page, x: c_int, y: c_int, sx: c_int, sy: c_int, rotate: c_int, flags: c_int);

    fn FPDFPage_CountObjects(p: Page) -> c_int;
    fn FPDFPage_GetObject(p: Page, i: c_int) -> Obj;
    fn FPDFPage_RemoveObject(p: Page, o: Obj) -> c_int;
    fn FPDFPageObj_Destroy(o: Obj);
    fn FPDFPageObj_GetType(o: Obj) -> c_int;
    fn FPDFFormObj_CountObjects(o: Obj) -> c_int;
    fn FPDFFormObj_GetObject(o: Obj, i: c_ulong) -> Obj;
    fn FPDFFormObj_RemoveObject(form: Obj, o: Obj) -> c_int;
}

// RUN-TIME LOADING (`--cfg pdfium_dl`, P11.4b, D1470): the per-platform CLI does not link libpdfium; it `dlopen`s (`LoadLibrary` on Windows) the
// library it is told to (a flag, an environment variable, or the `lib/` beside its own executable), so a missing library is a sentence the
// CLI prints and not the loader's abort before `main`. The same 21 functions, resolved by name once by `load`; every call below is unchanged.
#[cfg(pdfium_dl)]
pub use dl::load;
#[cfg(pdfium_dl)]
use dl::*;

#[cfg(pdfium_dl)]
#[allow(non_snake_case)]
mod dl {
    use super::{Bm, Doc, FileAccess, Obj, Page};
    use std::ffi::{c_char, c_int, c_ulong, c_void};
    use std::sync::OnceLock;

    #[cfg(unix)]
    mod os {
        use std::ffi::{c_char, c_int, c_void, CStr, CString};
        extern "C" {
            fn dlopen(path: *const c_char, flags: c_int) -> *mut c_void;
            fn dlsym(h: *mut c_void, name: *const c_char) -> *mut c_void;
            fn dlerror() -> *const c_char;
        }
        pub fn open(path: &str) -> Result<*mut c_void, String> {
            let c = CString::new(path).map_err(|_| "a NUL in the path".to_string())?;
            // RTLD_NOW (2 on Linux and macOS): a missing symbol is found now, not at the first call
            let h = unsafe { dlopen(c.as_ptr(), 2) };
            if h.is_null() {
                let e = unsafe { dlerror() };
                return Err(if e.is_null() { "dlopen failed".to_string() } else { unsafe { CStr::from_ptr(e) }.to_string_lossy().to_string() });
            }
            Ok(h)
        }
        pub fn sym(h: *mut c_void, name: &str) -> *mut c_void {
            unsafe { dlsym(h, name.as_ptr() as *const c_char) }
        }
    }

    #[cfg(windows)]
    mod os {
        use std::ffi::{c_char, c_void, CString};
        extern "system" {
            fn LoadLibraryA(path: *const c_char) -> *mut c_void;
            fn GetProcAddress(h: *mut c_void, name: *const c_char) -> *mut c_void;
            fn GetLastError() -> u32;
        }
        pub fn open(path: &str) -> Result<*mut c_void, String> {
            let c = CString::new(path).map_err(|_| "a NUL in the path".to_string())?;
            let h = unsafe { LoadLibraryA(c.as_ptr()) };
            if h.is_null() {
                return Err(format!("LoadLibrary failed (Windows error {})", unsafe { GetLastError() }));
            }
            Ok(h)
        }
        pub fn sym(h: *mut c_void, name: &str) -> *mut c_void {
            unsafe { GetProcAddress(h, name.as_ptr() as *const c_char) }
        }
    }

    macro_rules! symbols {
        ($(fn $name:ident($($arg:ident: $ty:ty),*) $(-> $ret:ty)?;)*) => {
            struct Table { $($name: unsafe extern "C" fn($($ty),*) $(-> $ret)?,)* }
            static TABLE: OnceLock<Table> = OnceLock::new();
            fn fill(h: *mut c_void) -> Result<Table, String> {
                Ok(Table { $($name: {
                    let p = os::sym(h, concat!(stringify!($name), "\0"));
                    if p.is_null() {
                        return Err(format!("the library has no symbol {}: it is not a pdfium build", stringify!($name)));
                    }
                    unsafe { std::mem::transmute::<*mut c_void, unsafe extern "C" fn($($ty),*) $(-> $ret)?>(p) }
                },)* })
            }
            $(pub unsafe fn $name($($arg: $ty),*) $(-> $ret)? {
                (TABLE.get().expect("pdfium: load() was not called"). $name)($($arg),*)
            })*
        };
    }

    symbols! {
        fn FPDF_InitLibrary();
        fn FPDF_GetLastError() -> c_ulong;
        fn FPDF_LoadCustomDocument(access: *mut FileAccess, password: *const c_char) -> Doc;
        fn FPDF_CloseDocument(d: Doc);
        fn FPDF_GetPageCount(d: Doc) -> c_int;
        fn FPDF_LoadPage(d: Doc, index: c_int) -> Page;
        fn FPDF_ClosePage(p: Page);
        fn FPDF_GetPageWidthF(p: Page) -> f32;
        fn FPDF_GetPageHeightF(p: Page) -> f32;
        fn FPDFBitmap_CreateEx(w: c_int, h: c_int, format: c_int, first_scan: *mut c_void, stride: c_int) -> Bm;
        fn FPDFBitmap_FillRect(b: Bm, left: c_int, top: c_int, w: c_int, h: c_int, color: c_ulong) -> c_int;
        fn FPDFBitmap_Destroy(b: Bm);
        fn FPDF_RenderPageBitmap(b: Bm, p: Page, x: c_int, y: c_int, sx: c_int, sy: c_int, rotate: c_int, flags: c_int);
        fn FPDFPage_CountObjects(p: Page) -> c_int;
        fn FPDFPage_GetObject(p: Page, i: c_int) -> Obj;
        fn FPDFPage_RemoveObject(p: Page, o: Obj) -> c_int;
        fn FPDFPageObj_Destroy(o: Obj);
        fn FPDFPageObj_GetType(o: Obj) -> c_int;
        fn FPDFFormObj_CountObjects(o: Obj) -> c_int;
        fn FPDFFormObj_GetObject(o: Obj, i: c_ulong) -> Obj;
        fn FPDFFormObj_RemoveObject(form: Obj, o: Obj) -> c_int;
    }

    /// Load the pdfium library at `path` (the file itself, `libpdfium.dylib`, `libpdfium.so` or `pdfium.dll`). Once; a second call is Ok.
    pub fn load(path: &str) -> Result<(), String> {
        if TABLE.get().is_some() {
            return Ok(());
        }
        let h = os::open(path)?;
        let t = fill(h)?;
        let _ = TABLE.set(t);
        Ok(())
    }
}

const FPDF_ANNOT: c_int = 0x01;
const FPDF_REVERSE_BYTE_ORDER: c_int = 0x10;
const BITMAP_BGRX: c_int = 3;

const OBJ_TEXT: c_int = 1;
const OBJ_FORM: c_int = 5;

static INIT: Once = Once::new();
// pdfium is not thread-safe.
static LOCK: Mutex<()> = Mutex::new(());

// The default Renderer. A render larger than the `pixels` limit (kin's table: the override, `EMON_LIMIT_PIXELS`, or the default; `port::limit_of(9)`, read
// at each render, so a conversion's `limit.pixels` applies to rendering too) is refused (`failed`), before any memory is asked for (P10.4).
#[derive(Debug)]
pub struct Pdfium;

impl Pdfium {
    pub fn new() -> Pdfium {
        INIT.call_once(|| unsafe { FPDF_InitLibrary() });
        Pdfium
    }
}

impl Default for Pdfium {
    fn default() -> Pdfium {
        Pdfium::new()
    }
}

struct Ctx {
    src: *const dyn ByteSource,
}

extern "C" fn get_block(param: *mut c_void, pos: c_ulong, buf: *mut c_uchar, size: c_ulong) -> c_int {
    let r = std::panic::catch_unwind(|| {
        let ctx = unsafe { &*(param as *const Ctx) };
        let src = unsafe { &*ctx.src };
        let out = unsafe { std::slice::from_raw_parts_mut(buf, size as usize) };
        let mut got = 0usize;
        while got < out.len() {
            match src.read_at(pos as u64 + got as u64, &mut out[got..]) {
                Ok(0) | Err(_) => return 0,
                Ok(n) => got += n,
            }
        }
        1
    });
    r.unwrap_or(0)
}

fn last_error() -> String {
    let e = unsafe { FPDF_GetLastError() };
    match e {
        0 => "no error".to_string(),
        2 => "file not found or could not be opened".to_string(),
        3 => "not a PDF, or its structure is damaged".to_string(),
        4 => "password required or incorrect".to_string(),
        5 => "unsupported security scheme".to_string(),
        6 => "page not found or content error".to_string(),
        n => format!("pdfium error {}", n),
    }
}

// Does `layer` keep this object? `None` for a form object: it is walked, its contents decide.
unsafe fn keep(layer: i32, o: Obj) -> Option<bool> {
    let ty = FPDFPageObj_GetType(o);
    if ty == OBJ_FORM {
        return None;
    }
    Some(match layer {
        LAYER_NO_TEXT => ty != OBJ_TEXT,
        LAYER_TEXT_ONLY => ty == OBJ_TEXT,
        _ => true,
    })
}

unsafe fn prune_form(layer: i32, form: Obj) {
    let mut i = FPDFFormObj_CountObjects(form) as i64 - 1;
    while i >= 0 {
        let o = FPDFFormObj_GetObject(form, i as c_ulong);
        if !o.is_null() {
            match keep(layer, o) {
                Some(false) => {
                    if FPDFFormObj_RemoveObject(form, o) != 0 {
                        FPDFPageObj_Destroy(o);
                    }
                }
                None => prune_form(layer, o),
                Some(true) => {}
            }
        }
        i -= 1;
    }
}

unsafe fn prune_page(layer: i32, page: Page) {
    let mut i = FPDFPage_CountObjects(page) as i64 - 1;
    while i >= 0 {
        let o = FPDFPage_GetObject(page, i as c_int);
        if !o.is_null() {
            match keep(layer, o) {
                Some(false) => {
                    if FPDFPage_RemoveObject(page, o) != 0 {
                        FPDFPageObj_Destroy(o);
                    }
                }
                None => prune_form(layer, o),
                Some(true) => {}
            }
        }
        i -= 1;
    }
}

impl Renderer for Pdfium {
    fn layers(&self) -> u32 {
        0b00111
    }

    fn render(&self, input: &dyn ByteSource, password: Option<&[u8]>, req: &RenderRequest) -> Result<Bitmap, RenderError> {
        if req.layer == LAYER_NO_WATERMARK || req.layer == LAYER_WATERMARK_ONLY {
            return Err(RenderError::Unsupported("pdfium's mark API cannot read /Artifact /Subtype (a name)".to_string()));
        }
        if !(0..=4).contains(&req.layer) {
            return Err(RenderError::Unsupported(format!("layer {}", req.layer)));
        }
        if req.dpi < 1 || req.dpi > 4800 {
            return Err(RenderError::Failed(format!("dpi {}", req.dpi)));
        }
        if input.len() > c_ulong::MAX as u64 {
            return Err(RenderError::Failed("the input is longer than pdfium's file access can say".to_string()));
        }
        let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let ctx = Box::new(Ctx { src: unsafe { std::mem::transmute::<&dyn ByteSource, *const dyn ByteSource>(input) } });
        let mut access = Box::new(FileAccess { len: input.len() as c_ulong, get_block, param: &*ctx as *const Ctx as *mut c_void });
        let pw = match password {
            Some(p) => Some(CString::new(p.to_vec()).map_err(|_| RenderError::Failed("a password with a NUL".to_string()))?),
            None => None,
        };
        let doc = unsafe { FPDF_LoadCustomDocument(&mut *access, pw.as_ref().map_or(std::ptr::null(), |p| p.as_ptr())) };
        if doc.is_null() {
            return Err(RenderError::Failed(last_error()));
        }
        let r = self.render_doc(doc, req);
        unsafe { FPDF_CloseDocument(doc) };
        drop(access);
        drop(ctx);
        r
    }
}

impl Pdfium {
    fn render_doc(&self, doc: Doc, req: &RenderRequest) -> Result<Bitmap, RenderError> {
        unsafe {
            let n = FPDF_GetPageCount(doc);
            if req.page < 0 || req.page >= n {
                return Err(RenderError::Failed(format!("page {} of {}", req.page, n)));
            }
            let page = FPDF_LoadPage(doc, req.page);
            if page.is_null() {
                return Err(RenderError::Failed(last_error()));
            }
            let r = self.render_page(page, req);
            FPDF_ClosePage(page);
            r
        }
    }

    unsafe fn render_page(&self, page: Page, req: &RenderRequest) -> Result<Bitmap, RenderError> {
        let (wpt, hpt) = (FPDF_GetPageWidthF(page) as f64, FPDF_GetPageHeightF(page) as f64);
        let (w, h) = (pixels_of(wpt, req.dpi), pixels_of(hpt, req.dpi));
        let max_pixels = crate::port::limit_of(9) as u64;
        if w as u64 * h as u64 > max_pixels {
            return Err(RenderError::Failed(format!("{}x{} pixels is over the limit of {}", w, h, max_pixels)));
        }
        let mut flags = FPDF_REVERSE_BYTE_ORDER;
        match req.layer {
            LAYER_FULL => flags |= FPDF_ANNOT,
            LAYER_NO_TEXT => {
                prune_page(req.layer, page);
                flags |= FPDF_ANNOT;
            }
            _ => prune_page(req.layer, page),
        }
        let (cw, ch) = (w as usize, h as usize);
        let mut buf = vec![0u8; cw * ch * 4];
        let bm = FPDFBitmap_CreateEx(w, h, BITMAP_BGRX, buf.as_mut_ptr() as *mut c_void, (cw * 4) as c_int);
        if bm.is_null() {
            return Err(RenderError::Failed("the bitmap could not be created".to_string()));
        }
        FPDFBitmap_FillRect(bm, 0, 0, w, h, 0xFFFF_FFFF as c_ulong);
        FPDF_RenderPageBitmap(bm, page, 0, 0, w, h, 0, flags);
        FPDFBitmap_Destroy(bm);
        let mut rgb = Vec::with_capacity(cw * ch * 3);
        for px in buf.chunks_exact(4) {
            rgb.extend_from_slice(&px[..3]);
        }
        Ok(Bitmap::from_bytes(w, h, 3, &rgb))
    }
}
