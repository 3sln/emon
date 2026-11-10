//! Version 1 C ABI around the unchanged qualified Rust port.
//! Callers own input buffers for the call; returned cells belong to this library.
use crate::{
    plan::{Bitmaps, Request},
    port::Bitmap,
    ppocr::PpOcr,
    Ocr,
};
use std::{
    ffi::{c_char, CStr},
    panic::{catch_unwind, AssertUnwindSafe},
    path::Path,
    sync::Arc,
};

unsafe fn message(out: *mut c_char, cap: usize, value: &str) {
    if !out.is_null() && cap > 0 {
        let n = value.len().min(cap - 1);
        std::ptr::copy_nonoverlapping(value.as_ptr(), out.cast(), n);
        *out.add(n) = 0;
    }
}
#[no_mangle]
pub unsafe extern "C" fn emon_ppocr_open_v1(
    dir: *const c_char,
    error: *mut c_char,
    cap: usize,
) -> *mut PpOcr {
    let result = catch_unwind(AssertUnwindSafe(|| {
        if dir.is_null() {
            return Err("ppocr-models-missing".to_string());
        }
        let dir = CStr::from_ptr(dir)
            .to_str()
            .map_err(|_| "ppocr-models-missing".to_string())?;
        PpOcr::new(Path::new(dir)).map_err(|e| format!("ppocr-models-missing: {e}"))
    }));
    match result {
        Ok(Ok(p)) => Box::into_raw(Box::new(p)),
        Ok(Err(e)) => {
            message(error, cap, &e);
            std::ptr::null_mut()
        }
        Err(_) => {
            message(error, cap, "ppocr-internal");
            std::ptr::null_mut()
        }
    }
}
#[no_mangle]
pub unsafe extern "C" fn emon_ppocr_close_v1(p: *mut PpOcr) {
    if !p.is_null() {
        drop(Box::from_raw(p));
    }
}
#[no_mangle]
pub unsafe extern "C" fn emon_ppocr_read_v1(
    p: *const PpOcr,
    request: *const i32,
    n: usize,
    pixels: *const u8,
    bytes: usize,
    w: i32,
    h: i32,
    ch: i32,
    output: *mut *mut i32,
    count: *mut usize,
    error: *mut c_char,
    cap: usize,
) -> i32 {
    if !output.is_null() {
        *output = std::ptr::null_mut();
    }
    if !count.is_null() {
        *count = 0;
    }
    if output.is_null() || count.is_null() {
        message(error, cap, "ppocr-bad-request");
        return 1;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        if p.is_null()
            || request.is_null()
            || pixels.is_null()
            || n < 10
            || n > i32::MAX as usize
            || w <= 0
            || h <= 0
            || ![1, 3].contains(&ch)
            || (w as usize)
                .checked_mul(h as usize)
                .and_then(|v| v.checked_mul(ch as usize))
                != Some(bytes)
        {
            return Err("ppocr-bad-request".to_string());
        }
        let r = std::slice::from_raw_parts(request, n);
        if r[0] != n as i32 || r[1] != crate::plan::KIND_OCR {
            return Err("ppocr-bad-request".into());
        }
        let mut framing = crate::port::Cells::new();
        for &v in r {
            crate::port::cells_push(&mut framing, v);
        }
        if crate::emon::ocrport::op_identity_at(&mut framing) < 0 {
            return Err("ppocr-bad-request".into());
        }
        let req = Request::new(r.to_vec());
        let bitmap = Bitmap::from_bytes(w, h, ch, std::slice::from_raw_parts(pixels, bytes));
        let mut bitmaps = Bitmaps::new();
        bitmaps.insert(req.field(crate::emon::plan::oc_bitmap()), Arc::new(bitmap));
        (*p).fulfil(&req, &bitmaps, false)
            .map(|a| a.rec)
            .map_err(|e| match e {
                crate::plan::PlanError::Fulfil(message) => message,
                other => other.to_string(),
            })
    }));
    match result {
        Ok(Ok(v)) => {
            let b = v.into_boxed_slice();
            *count = b.len();
            *output = Box::into_raw(b).cast();
            0
        }
        Ok(Err(e)) => {
            message(error, cap, &e);
            1
        }
        Err(_) => {
            message(error, cap, "ppocr-internal");
            1
        }
    }
}
#[no_mangle]
pub unsafe extern "C" fn emon_ppocr_free_v1(cells: *mut i32, count: usize) {
    if !cells.is_null() {
        drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(
            cells, count,
        )));
    }
}
