//! Ownership-safe Rust wrapper around the pinned ORT C ABI shim.
//! Sessions are deliberately !Sync; the OCR port serializes their use.
use std::ffi::{c_char, c_void, CStr, CString};
use std::path::Path;
use std::ptr::NonNull;

#[link(name = "emon_ppocr", kind = "static")]
#[link(name = "onnxruntime")]
unsafe extern "C" {
    fn emon_ort_open(path: *const c_char, error: *mut c_char, capacity: usize) -> *mut c_void;
    fn emon_ort_close(session: *mut c_void);
    fn emon_ort_run(
        session: *mut c_void,
        input: *mut f32,
        length: usize,
        shape: *const i64,
        rank: usize,
        output: *mut *mut f32,
        count: *mut usize,
        out_shape: *mut i64,
        out_rank: *mut usize,
        error: *mut c_char,
        capacity: usize,
    ) -> i32;
    fn emon_ort_free(output: *mut f32);
}

pub struct Session(NonNull<c_void>);
// A session may move between workers but may only run under exclusive access.
unsafe impl Send for Session {}

fn error(buffer: &[c_char]) -> String {
    // Shim always NUL terminates snprintf into this nonempty buffer.
    unsafe { CStr::from_ptr(buffer.as_ptr()) }
        .to_string_lossy()
        .into_owned()
}
impl Session {
    pub fn open(path: &Path) -> Result<Self, String> {
        let path = CString::new(path.to_str().ok_or("ORT path is not UTF-8")?)
            .map_err(|_| "NUL in ORT path")?;
        let mut message = [0; 1024];
        let raw = unsafe { emon_ort_open(path.as_ptr(), message.as_mut_ptr(), message.len()) };
        NonNull::new(raw).map(Self).ok_or_else(|| error(&message))
    }
    pub fn run(
        &mut self,
        mut input: Vec<f32>,
        shape: &[i64],
    ) -> Result<(Vec<i64>, Vec<f32>), String> {
        let length = shape
            .iter()
            .try_fold(1usize, |n, &d| {
                usize::try_from(d)
                    .ok()
                    .filter(|d| *d > 0)
                    .and_then(|d| n.checked_mul(d))
            })
            .ok_or("invalid ORT input dimensions")?;
        if length != input.len() {
            return Err("ORT input size does not match shape".into());
        }
        let mut output = std::ptr::null_mut();
        let mut count = 0;
        let mut out_shape = [0; 8];
        let mut rank = 0;
        let mut message = [0; 1024];
        let status = unsafe {
            emon_ort_run(
                self.0.as_ptr(),
                input.as_mut_ptr(),
                input.len(),
                shape.as_ptr(),
                shape.len(),
                &mut output,
                &mut count,
                out_shape.as_mut_ptr(),
                &mut rank,
                message.as_mut_ptr(),
                message.len(),
            )
        };
        if status != 0 {
            return Err(error(&message));
        }
        if output.is_null() {
            return Err("ORT returned a null tensor".into());
        }
        let data = unsafe { std::slice::from_raw_parts(output, count).to_vec() };
        unsafe { emon_ort_free(output) };
        Ok((out_shape[..rank].to_vec(), data))
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        unsafe { emon_ort_close(self.0.as_ptr()) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn pinned_models_run_and_repeat_on_cpu() {
        let dir = Path::new(".cache/ppocr");
        let specs = [
            ("ch_PP-OCRv5_det_mobile.onnx", vec![1, 3, 64, 64]),
            ("ch_ppocr_mobile_v2.0_cls_mobile.onnx", vec![1, 3, 48, 192]),
            ("en_PP-OCRv5_rec_mobile.onnx", vec![1, 3, 48, 320]),
        ];
        for (name, shape) in specs {
            let mut session = Session::open(&dir.join(name)).unwrap();
            let n = shape.iter().product::<i64>() as usize;
            let first = session.run(vec![0.; n], &shape).unwrap();
            let second = session.run(vec![0.; n], &shape).unwrap();
            assert_eq!(first.0, second.0);
            assert_eq!(first.1.len(), second.1.len());
            assert!(first.1.iter().all(|v| v.is_finite()));
            assert!(first
                .1
                .iter()
                .zip(&second.1)
                .all(|(a, b)| a.to_bits() == b.to_bits()));
            println!("{name}: {:?}", first.0);
        }
    }
}
