// WHERE THE NATIVE PORTS ARE (D1541; the CLI's lookup of D541, for a library that is not an executable). A BINDING, like `tess.rs`: it names files and directories
// (the pdfium library, the Tesseract models) of the C library's and the pod's own archive; the Engine layers never see a path (D058).
//
//   pdfium    an explicit path (`emon_set_native_paths`, the pod's `:pdfium`), then $EMON_PDFIUM_LIB, then NEXT TO THE LIBRARY (the directory libemon was
//             loaded from; for a static link the executable's), then next to the executable: in each directory D, D/libpdfium.*, D/lib/libpdfium.* and
//             D/../lib/libpdfium.* (the archive's layout is lib/libemon.* beside lib/libpdfium.*; the pod's is emon-pod, lib/libpdfium.*).
//   models    an explicit directory, then $EMON_TESSDATA, then `share/emon/tessdata` (D/share/emon/tessdata or D/../share/emon/tessdata, same D), then
//             $TESSDATA_PREFIX and the usual system places (the Rust host's `TessOcr::new(None)`).
//
// A place that is NAMED (explicit, or the environment variable) and is wrong is an error and is never replaced by the next one. Nothing found
// is not an error: the port is absent (`emon_capabilities` has no bit for it) and a request for it is answered `unsupported` (D199).
//
// Assembled into the crate beside `capi.rs` (`#[path = "native.rs"] mod native;` in it) by kin/scripts/emon-bind-build and tools/package.clj.
use std::path::{Path, PathBuf};
use std::sync::Mutex;

#[cfg(target_os = "macos")]
const PDFIUM_LIB: &str = "libpdfium.dylib";
#[cfg(all(unix, not(target_os = "macos")))]
const PDFIUM_LIB: &str = "libpdfium.so";
#[cfg(windows)]
const PDFIUM_LIB: &str = "pdfium.dll";

// the process-wide explicit places (`emon_set_native_paths`)
pub static SET: Mutex<(Option<String>, Option<String>)> = Mutex::new((None, None));

fn nonempty_env(k: &str) -> Option<String> {
    std::env::var(k).ok().filter(|s| !s.is_empty())
}

// The directory this library was loaded from (dladdr on one of its own functions; for a static link that is the executable's).
#[cfg(unix)]
fn module_dir() -> Option<PathBuf> {
    use std::ffi::{c_char, c_int, c_void, CStr};
    #[repr(C)]
    struct DlInfo {
        fname: *const c_char,
        fbase: *mut c_void,
        sname: *const c_char,
        saddr: *mut c_void,
    }
    extern "C" {
        fn dladdr(addr: *const c_void, info: *mut DlInfo) -> c_int;
    }
    let mut i = DlInfo { fname: std::ptr::null(), fbase: std::ptr::null_mut(), sname: std::ptr::null(), saddr: std::ptr::null_mut() };
    let f: extern "C" fn() -> *const c_char = super::emon_version;
    if unsafe { dladdr(f as *const c_void, &mut i) } == 0 || i.fname.is_null() {
        return None;
    }
    let p = PathBuf::from(unsafe { CStr::from_ptr(i.fname) }.to_string_lossy().to_string());
    let p = p.canonicalize().unwrap_or(p);
    p.parent().map(|d| d.to_path_buf())
}
#[cfg(not(unix))]
fn module_dir() -> Option<PathBuf> {
    None
}

fn exe_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    let exe = exe.canonicalize().unwrap_or(exe);
    exe.parent().map(|p| p.to_path_buf())
}

// the directories "next to the library or executable" are looked for from, without repeats
fn bases() -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = Vec::new();
    for d in [module_dir(), exe_dir()].into_iter().flatten() {
        if !v.contains(&d) {
            v.push(d);
        }
    }
    v
}

fn has_models(d: &Path) -> bool {
    std::fs::read_dir(d).map(|r| r.filter_map(|e| e.ok()).any(|e| e.file_name().to_string_lossy().ends_with(".traineddata"))).unwrap_or(false)
}

// a named place: a file, or a directory holding the library
fn named_pdfium(e: &str) -> Result<PathBuf, String> {
    let p = PathBuf::from(e);
    let f = if p.is_dir() { p.join(PDFIUM_LIB) } else { p };
    if f.is_file() {
        Ok(f)
    } else {
        Err(format!("pdfium was not found at {} (looked for the file {})", f.display(), PDFIUM_LIB))
    }
}

/// The pdfium library to load: Ok(Some) the file, Ok(None) nowhere, Err a named place that is wrong.
pub fn find_pdfium(explicit: Option<&str>) -> Result<Option<PathBuf>, String> {
    let set = SET.lock().unwrap_or_else(|e| e.into_inner()).0.clone();
    if let Some(e) = explicit.map(|s| s.to_string()).filter(|s| !s.is_empty()).or(set).or_else(|| nonempty_env("EMON_PDFIUM_LIB")) {
        return named_pdfium(&e).map(Some);
    }
    for b in bases() {
        let up = b.parent().map(|p| p.to_path_buf()).unwrap_or_else(|| b.clone());
        for c in [b.join(PDFIUM_LIB), b.join("lib").join(PDFIUM_LIB), up.join("lib").join(PDFIUM_LIB)] {
            if c.is_file() {
                return Ok(Some(c));
            }
        }
    }
    Ok(None)
}

/// The models directory: Ok(Some) it, Ok(None) nowhere in the lookup order, Err a named place that is wrong.
pub fn find_tessdata(explicit: Option<&str>) -> Result<Option<PathBuf>, String> {
    let set = SET.lock().unwrap_or_else(|e| e.into_inner()).1.clone();
    if let Some(e) = explicit.map(|s| s.to_string()).filter(|s| !s.is_empty()).or(set).or_else(|| nonempty_env("EMON_TESSDATA")) {
        let p = PathBuf::from(&e);
        return if has_models(&p) { Ok(Some(p)) } else { Err(format!("no *.traineddata in {}", e)) };
    }
    for b in bases() {
        let up = b.parent().map(|p| p.to_path_buf()).unwrap_or_else(|| b.clone());
        for c in [b.join("share").join("emon").join("tessdata"), up.join("share").join("emon").join("tessdata")] {
            if has_models(&c) {
                return Ok(Some(c));
            }
        }
    }
    Ok(None)
}

/// pdfium is built in and usable (loaded, when it is loaded at run time). Err: a named place is wrong, or the library is not pdfium.
#[cfg(all(pdfium, pdfium_dl))]
pub fn pdfium_ready(explicit: Option<&str>) -> Result<bool, String> {
    match find_pdfium(explicit)? {
        Some(p) => crate::pdfium::load(&p.to_string_lossy()).map(|_| true).map_err(|e| format!("cannot load pdfium from {}: {}", p.display(), e)),
        None => Ok(false),
    }
}
#[cfg(all(pdfium, not(pdfium_dl)))]
pub fn pdfium_ready(_explicit: Option<&str>) -> Result<bool, String> {
    Ok(true)
}
#[cfg(not(pdfium))]
pub fn pdfium_ready(_explicit: Option<&str>) -> Result<bool, String> {
    Ok(false)
}

/// The Tesseract port, when it is built in and a models directory is found.
#[cfg(feature = "tesseract")]
pub fn tesseract(explicit: Option<&str>) -> Result<Option<crate::tess::TessOcr>, String> {
    match find_tessdata(explicit)? {
        Some(d) => crate::tess::TessOcr::new(Some(&d.to_string_lossy())).map(Some),
        // the Rust host's own search ($TESSDATA_PREFIX, the usual system places); nothing there is "absent", not an error
        None => Ok(crate::tess::TessOcr::new(None).ok()),
    }
}
#[cfg(not(feature = "tesseract"))]
pub fn tesseract(_explicit: Option<&str>) -> Result<Option<()>, String> {
    Ok(None)
}

/// Whether `tesseract()` would give a port, without building one (a Tesseract object reads the models to name them).
#[cfg(feature = "tesseract")]
pub fn tesseract_ready() -> bool {
    match find_tessdata(None) {
        Ok(Some(_)) => true,
        Ok(None) => crate::tess::TessOcr::new(None).is_ok(),
        Err(_) => false,
    }
}
#[cfg(not(feature = "tesseract"))]
pub fn tesseract_ready() -> bool {
    false
}
