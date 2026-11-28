//! PP-OCR as a native OCR adapter library (K2, D899): `emon_ocr_adapter_v1` (host/c/include/emon_adapter.h) around the unchanged qualified Rust port. It is the only export;
//! the four `emon_ppocr_*_v1` functions of the first Go, Java and C# wrappers are gone (D928): every host loads this library through its engine registry.
use crate::adapter::{port_adapter, AdapterStatus, OcrAdapter, PortInfo};
#[cfg(ppocr_bundle)]
use crate::plan::{Bitmaps, Request};
use crate::{ppocr::PpOcr, Ocr};
#[cfg(not(ppocr_bundle))]
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::{
    ffi::{c_char, CStr},
    path::Path,
    sync::Arc,
};

#[cfg(ppocr_bundle)]
struct BundledPpOcr {
    dir: String,
    port: std::sync::Mutex<Option<Arc<PpOcr>>>,
}

#[cfg(ppocr_bundle)]
impl Ocr for BundledPpOcr {
    fn fulfil(&self, req: &Request, bitmaps: &Bitmaps, keep_binary: bool) -> Result<crate::plan::Answer, crate::plan::PlanError> {
        use crate::plan::PlanError;
        let mut slot = self.port.lock().map_err(|_| PlanError::Fulfil("PP-OCR lock poisoned".into()))?;
        if slot.is_none() {
            // The registry has resolved packs and refreshed the view before calling us.
            let port = PpOcr::new(Path::new(&self.dir)).map_err(PlanError::Fulfil)?;
            *slot = Some(Arc::new(port));
        }
        let port = Arc::clone(slot.as_ref().unwrap());
        drop(slot);
        port.fulfil(req, bitmaps, keep_binary)
    }
}

// THE ADAPTER ABI (emon_adapter.h): the same port, registered by `emon_engines_load` with the PACK DIRECTORY holding the three ONNX models (`ch_PP-OCRv5_det_mobile.onnx`,
// `ch_ppocr_mobile_v2.0_cls_mobile.onnx`, `en_PP-OCRv5_rec_mobile.onnx`: the catalogue's files, verified by core before they are linked there, and again by `PpOcr::new`).
// It answers PP-OCR's requests; the registry routes Tesseract's to Tesseract's adapter, so the incumbent this port holds for its lexicon (until `lex-eng`, K4) is never asked
// to read.
#[no_mangle]
pub unsafe extern "C" fn emon_ocr_adapter_v1(_config: *const c_char, packs_dir: *const c_char, st: *mut AdapterStatus) -> *const OcrAdapter {
    let fail = |reason: &str, message: &str| -> *const OcrAdapter {
        if !st.is_null() {
            (*st).set(reason, message);
        }
        std::ptr::null()
    };
    let dir = if packs_dir.is_null() {
        std::env::var("EMON_PPOCR_MODELS").unwrap_or_else(|_| ".cache/ppocr".to_string())
    } else {
        match CStr::from_ptr(packs_dir).to_str() {
            Ok(d) => d.to_string(),
            Err(_) => return fail("pack-dir-missing", "the pack directory is not UTF-8"),
        }
    };
    #[cfg(ppocr_bundle)]
    let port: Arc<dyn Ocr> = Arc::new(BundledPpOcr { dir, port: std::sync::Mutex::new(None) });
    #[cfg(not(ppocr_bundle))]
    let port: Arc<dyn Ocr> = match catch_unwind(AssertUnwindSafe(|| PpOcr::new(Path::new(&dir)))) {
        Ok(Ok(p)) => Arc::new(p),
        Ok(Err(e)) => return fail("pack-missing", &e),
        Err(_) => return fail("panic", "the PP-OCR adapter panicked while opening"),
    };
    #[cfg(ppocr_bundle)]
    let producer = vec![
        ("ocr-model".to_string(), crate::emon::ocrengine::OE_IDENTITY.iter().map(|&cp| char::from_u32(cp as u32).unwrap()).collect::<String>()),
        ("pp-lexicon".into(), "tessdata_fast/eng sha256=7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2".into()),
        ("onnxruntime".into(), "1.22.0 CPU sequential intra=1 inter=1".into()),
        ("pp-lexicon-pack".into(), "tess-fast-eng".into()),
    ];
    #[cfg(not(ppocr_bundle))]
    let producer: Vec<(String, String)> = port.producer().into_iter().filter(|(k, _)| k == "ocr-model" || k == "pp-lexicon" || k == "onnxruntime").collect();
    let observed = producer.iter().find(|(k, _)| k == "ocr-model").map(|(_, v)| v.clone()).unwrap_or_default();
    let caps = (crate::emon::engines::pk_engine_caps(crate::emon::engines::eng_ppocr())) as u32;
    use crate::emon::engines as ke;
    let info = PortInfo {
        name: "ppocr".to_string(),
        engine: crate::emon::oil::oen_ppocr() as u32,
        caps,
        measured: 32 | 256,
        needs: (ke::pkind_detector() | ke::pkind_classifier() | ke::pkind_recognizer()) as u32,
        flags: crate::adapter::ADAPTER_THREADSAFE,
        config: observed.clone(),
        observed,
        producer,
    };
    port_adapter(info, port)
}
