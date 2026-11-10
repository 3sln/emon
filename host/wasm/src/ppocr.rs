//! Async bridge. Only tensor inference yields to JavaScript. Dictionary queries
//! call Tesseract wasm directly through a wasm memory-framing shim. The pipeline
//! and integer answer builder are assembled from
//! the unchanged qualified Rust sources by tools/ppocr/wasm_assemble.clj.
use std::{cell::RefCell, future::{Future, poll_fn}, path::Path, pin::Pin,
    task::{Context, Poll, Waker}, panic::{catch_unwind, AssertUnwindSafe}};

pub struct Session(i32);
struct State {
    task: Option<Pin<Box<dyn Future<Output = Result<Vec<i32>, String>>>>>,
    request: Vec<u8>,
    response: Option<(Vec<i64>, Vec<f32>)>,
}
thread_local! { static STATE: RefCell<State> = RefCell::new(State {
    task: None, request: Vec::new(), response: None,
}); }
#[link(wasm_import_module = "ppocr")]
unsafe extern "C" { fn valid_word(ptr: u32, len: u32) -> i32; }
impl Session {
    pub fn open(path: &Path) -> Result<Self, String> {
        Ok(Self(match path.file_name().and_then(|x| x.to_str()) {
            Some("ch_PP-OCRv5_det_mobile.onnx") => 0,
            Some("ch_ppocr_mobile_v2.0_cls_mobile.onnx") => 1,
            Some("en_PP-OCRv5_rec_mobile.onnx") => 2,
            _ => return Err("unknown PP model".into()),
        }))
    }
    pub async fn run(&mut self, input: Vec<f32>, shape: &[i64]) -> Result<(Vec<i64>, Vec<f32>), String> {
        STATE.with(|s| {
            let mut s = s.borrow_mut();
            s.request.clear();
            for v in [self.0, shape.len() as i32].into_iter().chain(shape.iter().map(|&d| d as i32)) {
                s.request.extend(v.to_le_bytes());
            }
            for v in input { s.request.extend(v.to_le_bytes()); }
        });
        poll_fn(|_| STATE.with(|s| match s.borrow_mut().response.take() {
            Some(v) => Poll::Ready(Ok(v)), None => Poll::Pending,
        })).await
    }
}
async fn dictionary(word: &str) -> bool {
    if word.as_bytes().contains(&0) { return false; }
    unsafe { valid_word(word.as_ptr() as u32, word.len() as u32) > 0 }
}
include!("ppocr_answer.rs");

#[no_mangle]
pub unsafe extern "C" fn emon_pp_identity_at(p: u32, n: u32) -> i32 {
    let raw = crate::wasm::bytes(p, n);
    if raw.len() % 4 != 0 { return -1; }
    let mut rec = crate::port::Cells::new();
    for cell in raw.chunks_exact(4) {
        crate::port::cells_push(&mut rec, i32::from_le_bytes(cell.try_into().unwrap()));
    }
    crate::emon::ocrport::op_identity_at(&mut rec)
}

#[no_mangle]
pub unsafe extern "C" fn emon_pp_check(p: u32, n: u32, bp: u32, bn: u32) -> i32 {
    use crate::port::{Cells, cells_push, cells_at};
    let raw = crate::wasm::bytes(p, n);
    if raw.len() % 4 != 0 { return -1; }
    let rec: Vec<i32> = raw.chunks_exact(4).map(|v| i32::from_le_bytes(v.try_into().unwrap())).collect();
    if rec.len() < 10 || rec[0] != rec.len() as i32 || rec[1] != crate::plan::KIND_OCR { return -1; }
    let mut r = Cells::new();
    for &v in &rec { cells_push(&mut r, v); }
    let marker = crate::emon::ocrport::op_identity_at(&mut r);
    if marker < 0 { return 0; }
    let marker = marker as usize;
    let identity = crate::emon::ocrengine::OE_IDENTITY;
    if rec.get(marker) != Some(&-3100) || rec.get(marker + 1) != Some(&1)
        || rec.get(marker + 2) != Some(&(identity.len() as i32))
        || rec.get(marker + 3..) != Some(identity.as_slice()) { return -1; }
    let req = crate::plan::Request::new(rec);
    if req.field(crate::emon::plan::oc_flags()) & crate::emon::ocr::ocf_best() != 0 { return 2; }
    let mut hash = Cells::new(); cells_push(&mut hash, 0); cells_push(&mut hash, 0);
    if req.field(crate::emon::plan::oc_bitmap()) >= crate::emon::plan::view_base() && marker >= 12 {
        let image = match image::load_from_memory(crate::wasm::bytes(bp, bn)) { Ok(v) => v, Err(_) => return -1 };
        // View identity hashes include channel count. Preserve canonical gray
        // bitmaps here; inference itself always prepares RGB as native does.
        let (width, height, channels, pixels) = if image.color().channel_count() == 1 {
            let gray = image.to_luma8(); (gray.width(), gray.height(), 1, gray.into_raw())
        } else {
            let rgb = image.to_rgb8(); (rgb.width(), rgb.height(), 3, rgb.into_raw())
        };
        let bitmap = crate::port::Bitmap::from_bytes(width as i32, height as i32, channels, &pixels);
        crate::port::bitmap_hash(&bitmap, &mut hash, 0);
    }
    let first = crate::emon::ocrport::op_pp_lang_first(&mut r, cells_at(&hash, 0), cells_at(&hash, 1));
    let count = crate::emon::ocrport::op_pp_lang_count(&mut r, first);
    for i in 0..count {
        let lang = crate::emon::ocrport::op_pp_lang_at(&mut r, first, i);
        if lang != 1701734144 && lang != 1281455214 { return 2; }
    }
    1
}

// Own a single suspended job per module; the JS port serializes reads. Output
// uses emon's existing buffer and allocation/free ABI.
fn advance() -> i32 {
    match catch_unwind(AssertUnwindSafe(advance_inner)) {
        Ok(status) => status,
        Err(_) => { emon_pp_cancel(); crate::wasm::put_str("ppocr-internal"); -1 }
    }
}
fn advance_inner() -> i32 {
    let mut task = STATE.with(|s| s.borrow_mut().task.take()).expect("PP job absent");
    match task.as_mut().poll(&mut Context::from_waker(Waker::noop())) {
        Poll::Ready(Ok(v)) => { crate::wasm::put_i32s(&v); 0 }
        Poll::Ready(Err(e)) => { crate::wasm::put_str(&e); -1 }
        Poll::Pending => {
            STATE.with(|s| {
                let mut s = s.borrow_mut();
                crate::wasm::put(std::mem::take(&mut s.request));
                s.task = Some(task);
            });
            1
        }
    }
}
#[no_mangle]
pub unsafe extern "C" fn emon_pp_begin(p: u32, n: u32, id: i32, x: i32, y: i32, w: i32, h: i32) -> i32 {
    if STATE.with(|s| s.borrow().task.is_some()) { crate::wasm::put_str("PP job busy"); return -1; }
    let bytes = crate::wasm::bytes(p, n);
    let decoded = match image::load_from_memory(bytes) {
        Ok(v) => v.to_rgb8(), Err(e) => { crate::wasm::put_str(&e.to_string()); return -1; }
    };
    let (decoded, dx, dy) = if w > 0 && h > 0 {
        if x < 0 || y < 0 || x as i64 + w as i64 > decoded.width() as i64 || y as i64 + h as i64 > decoded.height() as i64 {
            crate::wasm::put_str("PP-OCR crop outside bitmap"); return -1;
        }
        (image::imageops::crop_imm(&decoded, x as u32, y as u32, w as u32, h as u32).to_image(), x, y)
    } else { (decoded, 0, 0) };
    STATE.with(|s| s.borrow_mut().task = Some(Box::pin(async move {
        let image = crate::ppocr_image::Image::new(decoded.width() as usize, decoded.height() as usize, decoded.into_raw())?;
        let mut pipeline = crate::ppocr_pipeline::Pipeline::open(Path::new(""), include_str!("ppocr_dictionary.txt"))?;
        answer(pipeline.read(&image).await?, id, dx, dy).await
    })));
    advance()
}
#[no_mangle]
pub unsafe extern "C" fn emon_pp_resume(p: u32, n: u32, rank: u32) -> i32 {
    if !STATE.with(|s| s.borrow().task.is_some()) { crate::wasm::put_str("PP job absent"); return -1; }
    let bytes = crate::wasm::bytes(p, n);
        if rank == 0 || rank > 8 || bytes.len() < rank as usize * 4 || bytes.len() % 4 != 0 {
            crate::wasm::put_str("invalid PP output tensor"); return -1;
        }
        let dims = bytes[..rank as usize * 4].chunks_exact(4).map(|v| i32::from_le_bytes(v.try_into().unwrap()) as i64).collect();
        let values = bytes[rank as usize * 4..].chunks_exact(4).map(|v| f32::from_le_bytes(v.try_into().unwrap())).collect();
        STATE.with(|s| s.borrow_mut().response = Some((dims, values)));
    advance()
}
#[no_mangle]
pub extern "C" fn emon_pp_cancel() {
    STATE.with(|s| { let mut s = s.borrow_mut(); s.task = None; s.response = None; s.request.clear(); });
}
