//! Run-time binding to the optional versioned shared PP adapter.
//! No ORT dependency is linked into the embedding library.
use crate::{
    plan::{Answer, Bitmaps, PlanError, Request},
    Ocr, OcrPort,
};
use std::{
    ffi::{c_char, c_void, CStr, CString},
    path::Path,
    sync::Mutex,
};
type Open = unsafe extern "C" fn(*const c_char, *mut c_char, usize) -> *mut c_void;
type Close = unsafe extern "C" fn(*mut c_void);
type Read = unsafe extern "C" fn(
    *const c_void,
    *const i32,
    usize,
    *const u8,
    usize,
    i32,
    i32,
    i32,
    *mut *mut i32,
    *mut usize,
    *mut c_char,
    usize,
) -> i32;
type Free = unsafe extern "C" fn(*mut i32, usize);
#[cfg(unix)]
unsafe extern "C" {
    fn dlopen(p: *const c_char, flags: i32) -> *mut c_void;
    fn dlsym(p: *mut c_void, n: *const c_char) -> *mut c_void;
    fn dlclose(p: *mut c_void) -> i32;
}
#[cfg(windows)]
#[link(name = "kernel32")]
unsafe extern "system" {
    fn LoadLibraryExW(path: *const u16, file: *mut c_void, flags: u32) -> *mut c_void;
    fn GetProcAddress(library: *mut c_void, name: *const c_char) -> *mut c_void;
    fn FreeLibrary(library: *mut c_void) -> i32;
}
#[cfg(windows)]
unsafe fn dlopen(path: *const c_char, _: i32) -> *mut c_void {
    use std::os::windows::ffi::OsStrExt;
    let path = std::path::PathBuf::from(CStr::from_ptr(path).to_string_lossy().as_ref());
    let Ok(path) = path.canonicalize() else { return std::ptr::null_mut(); };
    let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
    // Resolve ORT beside the adapter; do not search the current working directory.
    LoadLibraryExW(wide.as_ptr(), std::ptr::null_mut(), 0x100 | 0x1000)
}
#[cfg(windows)]
unsafe fn dlsym(p: *mut c_void, n: *const c_char) -> *mut c_void { GetProcAddress(p, n) }
#[cfg(windows)]
unsafe fn dlclose(p: *mut c_void) -> i32 { FreeLibrary(p) }
pub struct PpOcr {
    state: Mutex<State>,
    incumbent: Option<OcrPort>,
}
struct State {
    library: *mut c_void,
    port: *mut c_void,
    close: Close,
    read: Read,
    free: Free,
}
unsafe impl Send for State {}
impl PpOcr {
    pub fn open(dir: Option<&Path>, incumbent: Option<OcrPort>) -> Result<Self, String> {
        let default = std::env::var("EMON_PPOCR_MODELS").unwrap_or_else(|_| ".cache/ppocr".into());
        let dir = dir.unwrap_or_else(|| Path::new(&default));
        for name in [
            "ch_PP-OCRv5_det_mobile.onnx",
            "en_PP-OCRv5_rec_mobile.onnx",
            "ch_ppocr_mobile_v2.0_cls_mobile.onnx",
        ] {
            if !dir.join(name).is_file() {
                return Err("ppocr-models-missing".into());
            }
        }
        #[cfg(any(unix, windows))]
        unsafe {
            let ext = if cfg!(target_os = "macos") {
                "dylib"
            } else if cfg!(windows) {
                "dll"
            } else {
                "so"
            };
            let path = std::env::var("EMON_PPOCR_LIB")
                .unwrap_or_else(|_| format!(".cache/ppocr/{}emon_ppocr_adapter.{ext}", if cfg!(windows) { "" } else { "lib" }));
            let path = CString::new(path).map_err(|_| "ppocr-runtime-missing")?;
            let library = dlopen(path.as_ptr(), 2);
            if library.is_null() {
                return Err("ppocr-runtime-missing".into());
            }
            let load = |s: &str| dlsym(library, CString::new(s).unwrap().as_ptr());
            let (o, c, r, f) = (
                load("emon_ppocr_open_v1"),
                load("emon_ppocr_close_v1"),
                load("emon_ppocr_read_v1"),
                load("emon_ppocr_free_v1"),
            );
            if [o, c, r, f].iter().any(|p| p.is_null()) {
                dlclose(library);
                return Err("ppocr-runtime-missing".into());
            }
            let open: Open = std::mem::transmute(o);
            let mut message = [0; 2048];
            let dir = CString::new(dir.to_string_lossy().as_bytes())
                .map_err(|_| "ppocr-models-missing")?;
            let port = open(dir.as_ptr(), message.as_mut_ptr(), message.len());
            if port.is_null() {
                dlclose(library);
                return Err(CStr::from_ptr(message.as_ptr())
                    .to_string_lossy()
                    .into_owned());
            }
            Ok(Self {
                state: Mutex::new(State {
                    library,
                    port,
                    close: std::mem::transmute(c),
                    read: std::mem::transmute(r),
                    free: std::mem::transmute(f),
                }),
                incumbent,
            })
        }
        #[cfg(not(any(unix, windows)))]
        {
            let _ = incumbent;
            Err("ppocr-runtime-missing".into())
        }
    }
}
impl Drop for State {
    fn drop(&mut self) {
        unsafe {
            (self.close)(self.port);
            #[cfg(any(unix, windows))]
            {
                dlclose(self.library);
            }
        }
    }
}
impl Ocr for PpOcr {
    fn producer(&self) -> Vec<(String, String)> {
        let mut out = self
            .incumbent
            .as_ref()
            .map(|p| p.0.producer())
            .unwrap_or_default();
        let id = crate::emon::ocrengine::OE_IDENTITY
            .iter()
            .map(|&c| char::from_u32(c as u32).unwrap())
            .collect();
        out.extend([("ocr-engine".into(),"tesseract+ppocr".into()),("ocr-model".into(),id),
            ("pp-lexicon".into(),"tessdata_fast/eng sha256=7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2".into()),
            ("onnxruntime".into(),"1.22.0 CPU sequential intra=1 inter=1".into())]);
        out
    }
    fn fulfil(&self, req: &Request, bitmaps: &Bitmaps, keep: bool) -> Result<Answer, PlanError> {
        let mut r = crate::port::Cells::new();
        for &v in &req.rec {
            crate::port::cells_push(&mut r, v);
        }
        if crate::emon::ocrport::op_identity_at(&mut r) < 0 {
            return self
                .incumbent
                .as_ref()
                .ok_or_else(|| PlanError::Fulfil("ppocr-incumbent-missing".into()))?
                .0
                .fulfil(req, bitmaps, keep);
        }
        let b = bitmaps
            .get(&req.field(crate::emon::plan::oc_bitmap()))
            .ok_or_else(|| PlanError::Fulfil("ppocr-bad-request".into()))?;
        let bytes = b.to_bytes();
        let state = self
            .state
            .lock()
            .map_err(|_| PlanError::Fulfil("ppocr-internal".into()))?;
        let mut out = std::ptr::null_mut();
        let mut count = 0;
        let mut error = [0; 2048];
        unsafe {
            if (state.read)(
                state.port,
                req.rec.as_ptr(),
                req.rec.len(),
                bytes.as_ptr(),
                bytes.len(),
                b.width(),
                b.height(),
                b.channels(),
                &mut out,
                &mut count,
                error.as_mut_ptr(),
                error.len(),
            ) != 0
            {
                return Err(PlanError::Fulfil(
                    CStr::from_ptr(error.as_ptr())
                        .to_string_lossy()
                        .into_owned(),
                ));
            }
            let rec = std::slice::from_raw_parts(out, count).to_vec();
            (state.free)(out, count);
            Ok(Answer { rec, bitmap: None })
        }
    }
}
