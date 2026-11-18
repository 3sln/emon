// WHERE THE NATIVE PORTS ARE (D552; the CLI's lookup of D541, for a library that is not an executable). A BINDING, like `tess.rs`: it names files and directories
// (the pdfium library, the Tesseract models) of the C library's and the pod's own archive; the Engine layers never see a path (D058).
//
//   pdfium    an explicit path (`emon_engines_pdfium`, the pod's `:pdfium`), then $EMON_PDFIUM_LIB, then NEXT TO THE LIBRARY (the directory libemon was
//             loaded from; for a static link the executable's), then next to the executable: in each directory D, D/libpdfium.*, D/lib/libpdfium.* and
//             D/../lib/libpdfium.* (the archive's layout is lib/libemon.* beside lib/libpdfium.*; the pod's is emon-pod, lib/libpdfium.*).
//             MOBILE (K6, D897): inside an app the libraries are not files "next to" each other: an Android APK maps them (dladdr names `base.apk!/lib/...`) and an
//             iOS app carries them as frameworks. When nothing is found by file, the loader is asked for the library by name: `libpdfium.so` (Android: the app's
//             own native-library directory is in the loader's search path) or `@rpath/libpdfium.dylib` and `@rpath/libpdfium.framework/libpdfium` (iOS).
//   models    (the pod, which links Tesseract: the `tesseract` feature) an explicit directory, then $EMON_TESSDATA, then `share/emon/tessdata` (D/share/emon/tessdata or
//             D/../share/emon/tessdata, same D), then $TESSDATA_PREFIX and the usual system places (the Rust host's `TessOcr::new(None)`).
//   packs     (the C library, K2, D900) `share/emon/packs` one directory above `lib/` (the archive's), or beside the executable; $EMON_MODELS is searched by `packs.rs`.
//   adapters  (the C library) `$EMON_ADAPTER_DIR`, then `lib/emon/adapters/` beside or above the library: every `libemon_ocr_*` (`emon_ocr_*.dll`) in it; in an app, by name from the
//             loader (`libemon_ocr_tesseract.so`, `@rpath/libemon_ocr_tesseract.dylib`).
//
// A place that is NAMED (explicit, or the environment variable) and is wrong is an error and is never replaced by the next one. Nothing found
// is not an error: the port is absent (`emon_capabilities` has no bit for it) and a request for it is answered `unsupported` (D199).
//
// Assembled into the crate beside `capi.rs` (`#[path = "native.rs"] mod native;` in it) by kin/scripts/emon-bind-build and tools/package.clj.
use std::path::{Path, PathBuf};

#[cfg(any(target_os = "macos", target_os = "ios"))]
const PDFIUM_LIB: &str = "libpdfium.dylib";
#[cfg(all(unix, not(any(target_os = "macos", target_os = "ios"))))]
const PDFIUM_LIB: &str = "libpdfium.so";
#[cfg(windows)]
const PDFIUM_LIB: &str = "pdfium.dll";


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

#[allow(dead_code)]
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
    if let Some(e) = explicit.map(|s| s.to_string()).filter(|s| !s.is_empty()).or_else(|| nonempty_env("EMON_PDFIUM_LIB")) {
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
    Ok(loader_pdfium())
}

/// The names the platform's loader may know pdfium by, where the app bundle is not a directory of files (Android, iOS): the first the loader opens.
#[cfg(any(target_os = "android", target_os = "ios"))]
fn loader_pdfium() -> Option<PathBuf> {
    use std::ffi::{c_char, c_int, c_void, CString};
    extern "C" {
        fn dlopen(path: *const c_char, flags: c_int) -> *mut c_void;
        fn dlclose(h: *mut c_void) -> c_int;
    }
    let names: &[&str] = if cfg!(target_os = "android") {
        &["libpdfium.so"]
    } else {
        &["@rpath/libpdfium.dylib", "@rpath/libpdfium.framework/libpdfium"]
    };
    for n in names {
        let c = CString::new(*n).ok()?;
        // RTLD_LAZY (1): only asks whether the loader can open it; `pdfium::load` opens it again for real
        let h = unsafe { dlopen(c.as_ptr(), 1) };
        if !h.is_null() {
            unsafe { dlclose(h) };
            return Some(PathBuf::from(*n));
        }
    }
    None
}
#[cfg(not(any(target_os = "android", target_os = "ios")))]
fn loader_pdfium() -> Option<PathBuf> {
    None
}

#[allow(dead_code)]
/// The models directory: Ok(Some) it, Ok(None) nowhere in the lookup order, Err a named place that is wrong.
pub fn find_tessdata(explicit: Option<&str>) -> Result<Option<PathBuf>, String> {
    if let Some(e) = explicit.map(|s| s.to_string()).filter(|s| !s.is_empty()).or_else(|| nonempty_env("EMON_TESSDATA")) {
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

/// The archive's pack directory (`share/emon/packs`, one directory above `lib/` or beside the executable), when it has packs.
pub fn find_packs_dir() -> Option<PathBuf> {
    for b in bases() {
        let up = b.parent().map(|p| p.to_path_buf()).unwrap_or_else(|| b.clone());
        for c in [b.join("share").join("emon").join("packs"), up.join("share").join("emon").join("packs")] {
            if c.is_dir() {
                return Some(c);
            }
        }
    }
    None
}

#[cfg(windows)]
fn is_adapter_name(n: &str) -> bool {
    n.starts_with("emon_ocr_") && n.ends_with(".dll")
}
#[cfg(not(windows))]
fn is_adapter_name(n: &str) -> bool {
    n.starts_with("libemon_ocr_") && (n.ends_with(".so") || n.ends_with(".dylib"))
}

/// The adapter libraries to register by default, in a stable order: `$EMON_ADAPTER_DIR` (a place you NAME that is wrong is an error), else `emon/adapters` in each directory D
/// next to the library or the executable (D/emon/adapters, D/lib/emon/adapters, D/../lib/emon/adapters), else, in an app, the ones the loader knows by name.
pub fn find_adapter_libs() -> Result<Vec<PathBuf>, String> {
    let list = |d: &Path| -> Vec<PathBuf> {
        let mut v: Vec<PathBuf> = std::fs::read_dir(d).map(|r| r.filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.is_file() && p.file_name().map(|n| is_adapter_name(&n.to_string_lossy())).unwrap_or(false)).collect()).unwrap_or_default();
        v.sort();
        v
    };
    if let Some(e) = nonempty_env("EMON_ADAPTER_DIR") {
        let d = PathBuf::from(&e);
        if !d.is_dir() {
            return Err(format!("EMON_ADAPTER_DIR names {}, which is not a directory", e));
        }
        return Ok(list(&d));
    }
    for b in bases() {
        let up = b.parent().map(|p| p.to_path_buf()).unwrap_or_else(|| b.clone());
        for c in [b.join("emon").join("adapters"), b.join("lib").join("emon").join("adapters"), up.join("lib").join("emon").join("adapters")] {
            let v = list(&c);
            if !v.is_empty() {
                return Ok(v);
            }
        }
    }
    Ok(loader_adapters())
}

/// Adapter libraries the platform's loader opens by name, where an app bundle is not a directory of files (Android, iOS).
#[cfg(any(target_os = "android", target_os = "ios"))]
fn loader_adapters() -> Vec<PathBuf> {
    use std::ffi::{c_char, c_int, c_void, CString};
    extern "C" {
        fn dlopen(path: *const c_char, flags: c_int) -> *mut c_void;
        fn dlclose(h: *mut c_void) -> c_int;
    }
    let names: &[&str] = if cfg!(target_os = "android") { &["libemon_ocr_tesseract.so"] } else { &["@rpath/libemon_ocr_tesseract.dylib", "@rpath/libemon_ocr_tesseract.framework/libemon_ocr_tesseract"] };
    let mut out = Vec::new();
    for n in names {
        if let Ok(c) = CString::new(*n) {
            // RTLD_LAZY (1): only asks whether the loader can open it; `Engines::load` opens it again for real
            let h = unsafe { dlopen(c.as_ptr(), 1) };
            if !h.is_null() {
                unsafe { dlclose(h) };
                out.push(PathBuf::from(*n));
                break;
            }
        }
    }
    out
}
#[cfg(not(any(target_os = "android", target_os = "ios")))]
fn loader_adapters() -> Vec<PathBuf> {
    Vec::new()
}
