// libemon_ocr_tesseract: Tesseract as a native OCR adapter LIBRARY (K2, D899; D883, D885; pkg1 section 1.2). This crate file is the whole of its C side: `emon_ocr_adapter_v1`
// (host/c/include/emon_adapter.h), over `tess.rs` unchanged, so the answers are the bytes every transcript in the repository was recorded with.
//
// The library is built by `bb package` and `bb mobile` from the same sources as libemon, with the `tesseract-adapter` feature and the static Tesseract and Leptonica of `bb native`;
// it exports this one function and nothing of libemon's, and needs nothing of libemon at run time. The core library finds it next to itself (`lib/emon/adapters/`,
// `emon_engines_defaults`) or is told its path (`emon_engines_load`), and opens it with the PACK DIRECTORY of the engine: a flat directory of the verified `*.traineddata` files
// (`eng.traineddata`, `osd.traineddata`), which `tess.rs` lists again for every request, so a pack that arrives while a conversion runs is read from the next page on.
//
// `config` (key=value lines, LF): `langs=eng+deu` limits the models the engine may read with to these (the CLI's `--lang`); `tessdata=DIR` names a models directory when no pack
// directory is given.
use crate::adapter::{port_adapter, AdapterStatus, OcrAdapter, PortInfo};
use crate::tess::TessOcr;
use std::ffi::{c_char, CStr};
use std::sync::Arc;

unsafe fn text(p: *const c_char) -> Option<String> {
    if p.is_null() {
        None
    } else {
        Some(CStr::from_ptr(p).to_string_lossy().to_string()).filter(|s| !s.is_empty())
    }
}

fn open(config: Option<String>, dir: Option<String>) -> Result<*const OcrAdapter, (String, String)> {
    let cfg = config.unwrap_or_default();
    let get = |k: &str| cfg.lines().find_map(|l| l.strip_prefix(&format!("{}=", k)).map(|v| v.trim().to_string()));
    let dir = dir.or_else(|| get("tessdata")).ok_or_else(|| ("pack-dir-missing".to_string(), "no pack directory was given".to_string()))?;
    let ocr = match get("langs") {
        Some(l) => {
            let langs: Vec<String> = l.split('+').filter(|s| !s.is_empty()).map(|s| s.to_string()).collect();
            TessOcr::with_languages(&dir, &langs)
        }
        None => TessOcr::new(Some(&dir)),
    }
    .map_err(|e| ("open-failed".to_string(), e))?;
    let producer = crate::engine::Ocr::producer(&ocr);
    Ok(port_adapter(PortInfo::tesseract(producer), Arc::new(ocr)))
}

// THE ONE EXPORT. NULL, with `st` filled, when the adapter cannot be opened.
#[no_mangle]
pub unsafe extern "C" fn emon_ocr_adapter_v1(config: *const c_char, packs_dir: *const c_char, st: *mut AdapterStatus) -> *const OcrAdapter {
    let r = std::panic::catch_unwind(|| open(text(config), text(packs_dir)));
    match r {
        Ok(Ok(p)) => p,
        Ok(Err((reason, message))) => {
            if !st.is_null() {
                (*st).set(&reason, &message);
            }
            std::ptr::null()
        }
        Err(_) => {
            if !st.is_null() {
                (*st).set("panic", "the Tesseract adapter panicked while opening");
            }
            std::ptr::null()
        }
    }
}
