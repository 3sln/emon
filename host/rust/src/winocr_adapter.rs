// libemon_ocr_winocr: Windows.Media.Ocr as a native OCR adapter LIBRARY (K8, D901; D899's ABI, host/c/include/emon_adapter.h). This crate file is the whole of its C side: the one
// export, `emon_ocr_adapter_v1`, over `winocr.rs`. Built by `bb package` and CI (`native.yml`, the `winocr` job) on Windows from the same sources as emon.dll, with the `winocr-adapter`
// feature (the `windows` crate). It needs no pack, no model and no network, so `packs_dir` is ignored; `config` has no keys yet.
use crate::adapter::{port_adapter, AdapterStatus, OcrAdapter};
use crate::winocr::WinOcr;
use std::ffi::c_char;
use std::sync::Arc;

// THE ONE EXPORT. NULL, with `st` filled, when the adapter cannot be opened.
#[no_mangle]
pub unsafe extern "C" fn emon_ocr_adapter_v1(_config: *const c_char, _packs_dir: *const c_char, st: *mut AdapterStatus) -> *const OcrAdapter {
    let r = std::panic::catch_unwind(|| {
        WinOcr::new().map(|v| {
            let info = v.info();
            port_adapter(info, Arc::new(v))
        })
    });
    match r {
        Ok(Ok(p)) => p,
        Ok(Err(e)) => {
            if !st.is_null() {
                (*st).set("open-failed", &e);
            }
            std::ptr::null()
        }
        Err(_) => {
            if !st.is_null() {
                (*st).set("panic", "the Windows.Media.Ocr adapter panicked while opening");
            }
            std::ptr::null()
        }
    }
}
