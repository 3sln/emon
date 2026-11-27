// The native OCR adapters and the engine registry (K2, D899): registration, routing by engine id, what an answer is held to, unread and never blank, the four decisions of a pack
// hook, verification of every pack against the catalogue's SHA-256, prepare() and engines(), and async answers. The adapters here are stubs in Rust behind the C ABI
// (`emon_adapter.h`'s struct), so what is exercised is the host side: `adapter.rs` and `packs.rs`. The kin laws (`kin/adapterstest.kin`) hold the rules themselves.
use emon::adapter::*;
use emon::emon::{ocrengine, oil, plan as kp};
use emon::plan::{Bitmaps, Request};
use emon::port::{cells_at, cells_len, Bitmap, Cells};
use std::ffi::{c_char, c_int, c_void};
use std::path::PathBuf;
use std::sync::atomic::{AtomicI32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

// ------------------------------------------------------------------------------------------------ a stub adapter

#[derive(Default)]
struct Stub {
    reads: AtomicI32,
    starts: AtomicI32,
    cancels: AtomicI32,
    last_token: AtomicU64,
    // what `read` answers: 0 a reading, 1 unsupported, 2 failed, 3 a reading in another engine's name, 4 garbage
    mode: AtomicI32,
    host: Mutex<usize>,
    engine: u32,
}

// the strings of a stub live as long as the program
static NAME: &[u8] = b"stub\0";
static OBSERVED: &[u8] = b"stub;v=1\0";

unsafe extern "C" fn describe(ctx: *mut c_void, out: *mut AdapterInfo, _st: *mut AdapterStatus) -> c_int {
    let s = &*(ctx as *const Stub);
    (*out).engine = s.engine;
    (*out).caps = if s.engine == 2 { 24928 } else { 2225423 };
    (*out).native = 0;
    (*out).needs = if s.engine == 2 { 0 } else { NEEDS.load(Ordering::SeqCst) as u32 };
    (*out).flags = FLAGS.load(Ordering::SeqCst) as u32;
    (*out).config = OBSERVED.as_ptr() as *const c_char;
    (*out).observed = OBSERVED.as_ptr() as *const c_char;
    (*out).producer = std::ptr::null();
    (*out).langs = std::ptr::null();
    (*out).nlangs = 0;
    ADAPTER_OK
}

// describe's knobs, set by a test before it registers a stub (tests run one at a time: see `serial`)
static NEEDS: AtomicI32 = AtomicI32::new(0);
static FLAGS: AtomicI32 = AtomicI32::new(0);

fn reading(id: i32, w: i32, h: i32, engine: u32) -> Vec<i32> {
    let _ = (w, h);
    let mut c = Cells::new();
    let at = if engine == 2 {
        oil::oil_begin(&mut c, id, 2, 32 | 64 | 256 | 8192 | 16384, 0, 1, 1, 1701734144, 1)
    } else {
        oil::oil_tess_begin(&mut c, id, 3, 0, 0, 0, 0, 0, -1, 0, -1, -1, 0)
    };
    let mut o = Cells::new();
    for v in "stub;v=1".chars() {
        emon::port::cells_push(&mut o, v as i32);
    }
    let n = cells_len(&o);
    oil::oil_observed(&mut c, at, &mut o, 0, n);
    oil::oil_end_source(&mut c, at, 0);
    (0..cells_len(&c)).map(|i| cells_at(&c, i)).collect()
}

unsafe extern "C" fn read(ctx: *mut c_void, req: *const i32, n: usize, img: *const AdapterImage, _want: c_int, out: *mut AdapterAnswer, st: *mut AdapterStatus) -> c_int {
    let s = &*(ctx as *const Stub);
    s.reads.fetch_add(1, Ordering::SeqCst);
    let r = std::slice::from_raw_parts(req, n);
    match s.mode.load(Ordering::SeqCst) {
        1 => {
            (*st).set("lang-unavailable", "no");
            ADAPTER_UNSUPPORTED
        }
        2 => {
            (*st).set("boom", "it broke");
            ADAPTER_FAILED
        }
        4 => {
            let v: Vec<i32> = vec![5, 3, r[2], 0, 99];
            fill(out, v)
        }
        m => {
            let mut v = reading(r[2], (*img).width, (*img).height, s.engine);
            if m == 3 {
                // an answer in another engine's name: engine 2 in the header
                let at = 0;
                let _ = at;
                v[4 + 1] = 2;
            }
            fill(out, v)
        }
    }
}

unsafe fn fill(out: *mut AdapterAnswer, v: Vec<i32>) -> c_int {
    let b = v.into_boxed_slice();
    (*out).ncells = b.len();
    (*out).cells = Box::into_raw(b) as *mut i32;
    (*out).owner = std::ptr::null_mut();
    ADAPTER_OK
}

unsafe extern "C" fn release(_ctx: *mut c_void, a: *mut AdapterAnswer) {
    if !(*a).cells.is_null() {
        drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut((*a).cells, (*a).ncells)));
        (*a).cells = std::ptr::null_mut();
    }
}

unsafe extern "C" fn start(ctx: *mut c_void, _req: *const i32, _n: usize, _img: *const AdapterImage, token: u64, _st: *mut AdapterStatus) -> c_int {
    let s = &*(ctx as *const Stub);
    s.starts.fetch_add(1, Ordering::SeqCst);
    s.last_token.store(token, Ordering::SeqCst);
    ADAPTER_OK
}

unsafe extern "C" fn cancel(ctx: *mut c_void, _token: u64) {
    (&*(ctx as *const Stub)).cancels.fetch_add(1, Ordering::SeqCst);
}

unsafe extern "C" fn bind(ctx: *mut c_void, host: *const AdapterHost) {
    *(&*(ctx as *const Stub)).host.lock().unwrap() = host as usize;
}

unsafe extern "C" fn close(_ctx: *mut c_void) {}

fn vt(s: &'static Stub, abi: u32, sync: bool) -> OcrAdapter {
    OcrAdapter {
        abi,
        size: std::mem::size_of::<OcrAdapter>() as u32,
        name: NAME.as_ptr() as *const c_char,
        ctx: s as *const Stub as *mut c_void,
        describe: Some(describe),
        supports: None,
        read: if sync { Some(read) } else { None },
        start: if sync { None } else { Some(start) },
        cancel: Some(cancel),
        release: Some(release),
        bind: Some(bind),
        close: Some(close),
    }
}

fn stub(engine: u32) -> &'static Stub {
    Box::leak(Box::new(Stub { engine, ..Default::default() }))
}

// the tests share the process's describe knobs and EMON_* variables: one at a time
static LOCK: Mutex<()> = Mutex::new(());
fn serial() -> std::sync::MutexGuard<'static, ()> {
    LOCK.lock().unwrap_or_else(|e| e.into_inner())
}

// ------------------------------------------------------------------------------------------------ requests and pictures

fn request(id: i32, engine: i32, osd: bool) -> (Request, Bitmaps) {
    let mut c = Cells::new();
    let at = kp::rq_ocr(&mut c, id, 1, 3, if osd { 2 } else { 0 }, 0, 0, 0, 0);
    kp::rq_ocr_end(&mut c, at);
    // The PP request carries the PP adapter's requested identity (`oe_select`: D920 replaced the request-tag mode `oe_tag`, whose call here no longer compiled).
    if engine == 2 {
        ocrengine::oe_select(&mut c, at, 2);
    }
    let rec: Vec<i32> = (0..cells_len(&c)).map(|i| cells_at(&c, i)).collect();
    let mut bm = Bitmaps::new();
    bm.insert(1, Arc::new(Bitmap::from_bytes(4, 4, 1, &[255u8; 16])));
    (Request::new(rec), bm)
}

fn scratch(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("emon-adapters-test-{}-{}", std::process::id(), name));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

// the real English model: the catalogue's hash is its hash. None when the pinned download is not on this machine
fn eng_traineddata() -> Option<Vec<u8>> {
    let mut d = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    for _ in 0..4 {
        let f = d.join(".cache/native/dl/eng.traineddata");
        if f.exists() {
            return std::fs::read(f).ok();
        }
        if !d.pop() {
            break;
        }
    }
    std::env::var("EMON_TEST_ENG_TRAINEDDATA").ok().and_then(|p| std::fs::read(p).ok())
}

fn status(a: &emon::plan::Answer) -> i32 {
    a.rec[3]
}

fn tess_engines(needs: i32) -> (Engines, &'static Stub) {
    NEEDS.store(needs, Ordering::SeqCst);
    FLAGS.store(0, Ordering::SeqCst);
    let e = Engines::new();
    let s = stub(1);
    e.add_adapter(vt(s, 1, true)).unwrap();
    (e, s)
}

// ------------------------------------------------------------------------------------------------ registration

#[test]
fn an_adapter_is_refused_by_name_for_each_thing_it_gets_wrong() {
    let _g = serial();
    NEEDS.store(0, Ordering::SeqCst);
    FLAGS.store(0, Ordering::SeqCst);
    let e = Engines::new();
    assert_eq!(e.add_adapter(vt(stub(1), 2, true)).unwrap_err().name, "adapter-abi-unsupported", "an ABI newer than this library's");
    assert_eq!(e.add_adapter(vt(stub(9), 1, true)).unwrap_err().name, "adapter-engine-unknown", "an OIL engine the table does not have");
    NEEDS.store(8, Ordering::SeqCst);
    assert_eq!(e.add_adapter(vt(stub(1), 1, true)).unwrap_err().name, "adapter-needs-beyond-engine", "a classifier is not a Tesseract pack");
    NEEDS.store(0, Ordering::SeqCst);
    FLAGS.store(16, Ordering::SeqCst);
    assert_eq!(e.add_adapter(vt(stub(1), 1, true)).unwrap_err().name, "adapter-flags-unknown", "a flag the ABI does not define");
    FLAGS.store(2, Ordering::SeqCst);
    assert_eq!(e.add_adapter(vt(stub(1), 1, true)).unwrap_err().name, "adapter-remote-refused", "pixels off the device need the app's say-so");
    e.allow_remote(true);
    assert!(e.add_adapter(vt(stub(1), 1, true)).is_ok(), "...and with it they are accepted");
    assert_eq!(e.adapters().len(), 1, "a refused adapter is not registered");
    // not a library, and not an adapter library
    let e2 = Engines::new();
    assert_eq!(e2.load("/no/such/adapter.so", None).unwrap_err().name, "adapter-runtime-missing");
}

// ------------------------------------------------------------------------------------------------ routing, unread, never blank

#[test]
fn a_request_goes_to_the_adapter_of_its_engine_and_one_nobody_registered_for_is_unread() {
    let _g = serial();
    NEEDS.store(0, Ordering::SeqCst);
    FLAGS.store(0, Ordering::SeqCst);
    let e = Engines::new();
    let (t, p) = (stub(1), stub(2));
    e.add_adapter(vt(t, 1, true)).unwrap();
    let r = e.router().unwrap();
    let (req1, bm) = request(7, 1, false);
    let a = r.read_blocking(&req1, &bm, false).unwrap();
    assert_eq!(status(&a), 0, "Tesseract's request is read: {:?}", e.take_notes());
    assert_eq!(t.reads.load(Ordering::SeqCst), 1);
    let (req2, bm2) = request(8, 2, false);
    let a = r.read_blocking(&req2, &bm2, false).unwrap();
    assert_eq!(a.rec.len(), 4, "a request for an engine nobody registered is four cells: unread, never blank");
    assert_eq!(status(&a), kp::ps_unsupported(), "unsupported");
    e.add_adapter(vt(p, 1, true)).unwrap();
    let a = r.read_blocking(&req2, &bm2, false).unwrap();
    assert_eq!(status(&a), 0, "once PP-OCR is registered its request is read");
    assert_eq!((t.reads.load(Ordering::SeqCst), p.reads.load(Ordering::SeqCst)), (1, 1), "each adapter read its own engine's request only");
    // a request that names no engine is Tesseract's
    let (req0, bm0) = request(9, 0, false);
    r.read_blocking(&req0, &bm0, false).unwrap();
    assert_eq!(t.reads.load(Ordering::SeqCst), 2);
}

#[test]
fn an_adapter_that_cannot_read_leaves_the_page_unread_and_says_why() {
    let _g = serial();
    let (e, s) = tess_engines(0);
    let r = e.router().unwrap();
    let (req, bm) = request(3, 1, false);
    s.mode.store(1, Ordering::SeqCst);
    let a = r.read_blocking(&req, &bm, false).unwrap();
    assert_eq!((a.rec.len(), status(&a)), (4, kp::ps_unsupported()), "unsupported: four cells");
    s.mode.store(2, Ordering::SeqCst);
    let a = r.read_blocking(&req, &bm, false).unwrap();
    assert_eq!((a.rec.len(), status(&a)), (4, emon::plan::STATUS_FAILED), "failed: four cells, and the conversion goes on");
    let notes = e.take_notes().join("\n");
    assert!(notes.contains("unsupported lang-unavailable") && notes.contains("failed boom"), "the reasons are kept: {}", notes);
}

#[test]
fn an_answer_that_is_not_what_the_adapter_registered_fails_the_conversion() {
    let _g = serial();
    let (e, s) = tess_engines(0);
    let r = e.router().unwrap();
    let (req, bm) = request(3, 1, false);
    s.mode.store(3, Ordering::SeqCst);
    let err = r.read_blocking(&req, &bm, false).unwrap_err().to_string();
    assert!(err.contains("ocr-adapter-invalid"), "another engine's name: {}", err);
    s.mode.store(4, Ordering::SeqCst);
    let err = r.read_blocking(&req, &bm, false).unwrap_err().to_string();
    assert!(err.contains("ocr-adapter-invalid"), "not OIL: {}", err);
    s.mode.store(0, Ordering::SeqCst);
    assert_eq!(status(&r.read_blocking(&req, &bm, false).unwrap()), 0, "and a good one is read");
}

// ------------------------------------------------------------------------------------------------ packs: the hook's four decisions, verification

struct Hook {
    action: i32,
    data: Vec<u8>,
    engine: &'static [u8],
    asked: AtomicI32,
    packs: Mutex<Vec<String>>,
}

unsafe extern "C" fn hook(ctx: *mut c_void, req: *const emon::packs::PackRequest, out: *mut emon::packs::PackDecision) -> c_int {
    let h = &*(ctx as *const Hook);
    h.asked.fetch_add(1, Ordering::SeqCst);
    h.packs.lock().unwrap().push(std::ffi::CStr::from_ptr((*req).pack).to_string_lossy().to_string());
    (*out).action = h.action;
    (*out).data = h.data.as_ptr();
    (*out).len = h.data.len();
    (*out).engine = h.engine.as_ptr() as *const c_char;
    0
}

fn hooked(action: i32, data: Vec<u8>) -> (Engines, &'static Stub, &'static Hook, PathBuf) {
    let dir = scratch(&format!("hook{}", action));
    let (e, s) = tess_engines(1);
    e.packs().set_store(&dir).unwrap();
    e.packs().set_mode(emon::emon::engines::pfetch_cache());
    let h: &'static Hook = Box::leak(Box::new(Hook { action, data, engine: b"ppocr\0", asked: AtomicI32::new(0), packs: Mutex::new(Vec::new()) }));
    e.packs().set_hook(Some(hook), h as *const Hook as *mut c_void);
    (e, s, h, dir)
}

#[test]
fn a_missing_pack_leaves_the_page_unread_under_never_and_offline_without_asking_the_hook() {
    let _g = serial();
    let (e, s, h, _d) = hooked(emon::packs::PACK_PROVIDE, eng_traineddata().unwrap_or_default());
    let r = e.router().unwrap();
    let (req, bm) = request(3, 1, false);
    e.packs().set_mode(emon::emon::engines::pfetch_never());
    let a = r.read_blocking(&req, &bm, false).unwrap();
    assert_eq!((a.rec.len(), status(&a)), (4, kp::ps_unsupported()), "never: unread");
    assert_eq!((h.asked.load(Ordering::SeqCst), s.reads.load(Ordering::SeqCst)), (0, 0), "the hook is not asked and the adapter not called");
    assert!(e.take_notes().join("\n").contains("pack-missing:tess-fast-eng"));
    e.packs().set_mode(emon::emon::engines::pfetch_cache());
    std::env::set_var("EMON_OFFLINE", "1");
    let a = r.read_blocking(&req, &bm, false).unwrap();
    std::env::remove_var("EMON_OFFLINE");
    assert_eq!(status(&a), kp::ps_unsupported(), "EMON_OFFLINE=1 wins over the hook too");
    assert_eq!(h.asked.load(Ordering::SeqCst), 0);
}

#[test]
fn the_hook_declines_and_the_page_is_unread() {
    let _g = serial();
    let (e, s, h, _d) = hooked(emon::packs::PACK_DECLINE, Vec::new());
    let (req, bm) = request(3, 1, false);
    let a = e.router().unwrap().read_blocking(&req, &bm, false).unwrap();
    assert_eq!((a.rec.len(), status(&a)), (4, kp::ps_unsupported()));
    assert_eq!((h.asked.load(Ordering::SeqCst), s.reads.load(Ordering::SeqCst)), (1, 0), "asked once, the adapter never called");
    assert_eq!(h.packs.lock().unwrap().as_slice(), ["tess-fast-eng"], "for the English pack");
    assert!(e.take_notes().join("\n").contains("pack-missing:tess-fast-eng"));
}

#[test]
fn the_hook_substitutes_another_engine_and_the_request_stays_unread() {
    let _g = serial();
    let (e, s, h, _d) = hooked(emon::packs::PACK_SUBSTITUTE, Vec::new());
    let (req, bm) = request(3, 1, false);
    let a = e.router().unwrap().read_blocking(&req, &bm, false).unwrap();
    assert_eq!(status(&a), kp::ps_unsupported(), "another OIL engine would answer under its own name");
    assert_eq!((h.asked.load(Ordering::SeqCst), s.reads.load(Ordering::SeqCst)), (1, 0));
    assert!(e.take_notes().join("\n").contains("pack-substitute-differs:ppocr"));
}

#[test]
fn the_hook_provides_bytes_that_are_verified_before_the_adapter_reads() {
    let _g = serial();
    let Some(good) = eng_traineddata() else {
        eprintln!("SKIP: .cache/native/dl/eng.traineddata is not on this machine");
        return;
    };
    // the catalogue's bytes: installed, and the adapter reads
    let (e, s, h, dir) = hooked(emon::packs::PACK_PROVIDE, good.clone());
    let (req, bm) = request(3, 1, false);
    let r = e.router().unwrap();
    let a = r.read_blocking(&req, &bm, false).unwrap();
    assert_eq!((status(&a), s.reads.load(Ordering::SeqCst), h.asked.load(Ordering::SeqCst)), (0, 1, 1), "provided, verified, read");
    assert!(dir.join("tess-fast-eng/eng.traineddata").exists(), "kept in the pack directory");
    r.read_blocking(&req, &bm, false).unwrap();
    assert_eq!(h.asked.load(Ordering::SeqCst), 1, "installed: the hook is not asked again");
    // the adapter's view of the packs is a flat directory of the verified file
    assert!(e.packs().view("tesseract").unwrap().join("eng.traineddata").exists());
    // one flipped byte: never used, and says why
    let mut bad = good.clone();
    bad[1000] ^= 1;
    let (e2, s2, _h2, dir2) = hooked(emon::packs::PACK_PROVIDE, bad);
    let a = e2.router().unwrap().read_blocking(&req, &bm, false).unwrap();
    assert_eq!((a.rec.len(), status(&a), s2.reads.load(Ordering::SeqCst)), (4, kp::ps_unsupported(), 0), "tampered bytes: unread, adapter not called");
    assert!(!dir2.join("tess-fast-eng/eng.traineddata").exists(), "and nothing is kept");
    assert!(e2.take_notes().join("\n").contains("pack-hash-mismatch:tess-fast-eng"));
}

#[test]
fn a_file_in_the_pack_directory_that_does_not_match_is_deleted_and_the_pack_is_missing_again() {
    let _g = serial();
    let Some(good) = eng_traineddata() else {
        return;
    };
    let (e, s, _h, dir) = hooked(emon::packs::PACK_DECLINE, Vec::new());
    let f = dir.join("tess-fast-eng/eng.traineddata");
    std::fs::create_dir_all(f.parent().unwrap()).unwrap();
    let mut bad = good.clone();
    bad[5] ^= 0x80;
    std::fs::write(&f, &bad).unwrap();
    let (req, bm) = request(3, 1, false);
    let a = e.router().unwrap().read_blocking(&req, &bm, false).unwrap();
    assert_eq!((status(&a), s.reads.load(Ordering::SeqCst)), (kp::ps_unsupported(), 0));
    assert!(!f.exists(), "deleted from the cache");
    assert!(e.take_notes().join("\n").contains("pack-hash-mismatch:tess-fast-eng"));
    // a directory that is the app's own is left alone, and not used
    let theirs = scratch("theirs");
    std::fs::write(theirs.join("eng.traineddata"), &bad).unwrap();
    e.packs().add_dir(&theirs);
    e.router().unwrap().read_blocking(&req, &bm, false).unwrap();
    assert!(theirs.join("eng.traineddata").exists(), "the app's file is not deleted");
    std::fs::write(theirs.join("eng.traineddata"), &good).unwrap();
    let a = e.router().unwrap().read_blocking(&req, &bm, false).unwrap();
    assert_eq!(status(&a), 0, "the right bytes in the app's directory are used");
}

#[test]
fn prepare_and_engines_say_what_is_missing_and_what_it_comes_to() {
    let _g = serial();
    let dir = scratch("prepare");
    let (e, _s) = tess_engines(3);
    e.packs().set_store(&dir).unwrap();
    e.packs().set_mode(emon::emon::engines::pfetch_auto());
    let p = e.prepare_json("").unwrap();
    assert!(p.contains("\"verdict\":\"downloadable\"") && p.contains("\"id\":\"tess-fast-eng\",\"state\":\"missing\""), "{}", p);
    assert!(p.contains("\"id\":\"tess-osd\"") && p.contains("\"missing\":2"), "English and orientation: {}", p);
    e.packs().set_mode(emon::emon::engines::pfetch_never());
    assert!(e.prepare_json("").unwrap().contains("\"verdict\":\"blocked\""));
    assert!(e.prepare_json("ocr=never").unwrap().contains("\"verdict\":\"ready\""), "no OCR, nothing needed");
    assert!(e.prepare_json("locale=de-DE").unwrap().contains("tess-fast-deu"), "the locale's language");
    assert!(e.prepare_json("nonsense=1").is_err(), "an option text that does not parse");
    // `ocr-engine=ppocr` is accepted only by a build that can run PP-OCR (the C library: PPOCR_NATIVE_HOST); this one says so
    if let Ok(j) = e.prepare_json("ocr-engine=ppocr") {
        assert!(j.contains("pp5-det-mobile"), "the hybrid needs PP-OCR's models: {}", j);
    }
    let d = e.describe_json();
    assert!(d.contains("\"name\":\"tesseract\"") && d.contains("\"state\":\"unavailable\""), "packs missing and never fetching: {}", d);
    assert!(d.contains("\"name\":\"ppocr\"") && d.contains("\"registered\":false"), "no PP-OCR adapter here: {}", d);
    e.packs().set_mode(emon::emon::engines::pfetch_auto());
    assert!(e.describe_json().contains("\"state\":\"downloadable\""));
    if let Some(good) = eng_traineddata() {
        std::fs::create_dir_all(dir.join("tess-fast-eng")).unwrap();
        std::fs::write(dir.join("tess-fast-eng/eng.traineddata"), good).unwrap();
        let p = e.prepare_json("ocr-engine=tesseract").unwrap();
        assert!(p.contains("\"id\":\"tess-fast-eng\",\"state\":\"installed\""), "{}", p);
    }
}

// ------------------------------------------------------------------------------------------------ async

#[test]
fn an_async_adapter_answers_later_and_a_one_shot_call_waits_for_it() {
    let _g = serial();
    NEEDS.store(0, Ordering::SeqCst);
    FLAGS.store(1, Ordering::SeqCst);
    let e = Engines::new();
    let s = stub(1);
    e.add_adapter(vt(s, 1, false)).unwrap();
    let r = e.router().unwrap();
    let (req, bm) = request(5, 1, false);
    // the adapter answers from another thread through the host it was bound with
    let host = *s.host.lock().unwrap();
    assert_ne!(host, 0, "bind was called");
    let completer = std::thread::spawn({
        let s: &'static Stub = s;
        move || {
            while s.starts.load(Ordering::SeqCst) == 0 {
                std::thread::sleep(std::time::Duration::from_millis(5));
            }
            let h = unsafe { &*(host as *const AdapterHost) };
            let cells = reading(5, 4, 4, 1);
            let rc = unsafe { (h.complete.unwrap())(h.ctx, s.last_token.load(Ordering::SeqCst), ADAPTER_OK, cells.as_ptr(), cells.len(), std::ptr::null()) };
            assert_eq!(rc, 0);
            // a second completion of the same token is late: refused
            let rc = unsafe { (h.complete.unwrap())(h.ctx, s.last_token.load(Ordering::SeqCst), ADAPTER_OK, cells.as_ptr(), cells.len(), std::ptr::null()) };
            assert_eq!(rc, 1);
        }
    });
    let a = r.read_blocking(&req, &bm, false).unwrap();
    completer.join().unwrap();
    assert_eq!(status(&a), 0, "the answer that came later is the answer");
    // an adapter that never answers: the call gives up at the timeout, tells the adapter, and the page is unread
    e.set_timeout_ms(100);
    let a = r.read_blocking(&req, &bm, false).unwrap();
    assert_eq!((a.rec.len(), status(&a)), (4, emon::plan::STATUS_FAILED), "timed out: failed, unread");
    assert_eq!(s.cancels.load(Ordering::SeqCst), 1, "the adapter was told to stop");
    // ...and its late answer is ignored
    let h = unsafe { &*(host as *const AdapterHost) };
    let cells = reading(5, 4, 4, 1);
    assert_eq!(unsafe { (h.complete.unwrap())(h.ctx, s.last_token.load(Ordering::SeqCst), ADAPTER_OK, cells.as_ptr(), cells.len(), std::ptr::null()) }, 1);
}

#[test]
fn fetch_without_a_host_leaves_the_page_unread_and_names_the_error() {
    let _g = serial();
    let (e, s, h, dir) = hooked(emon::packs::PACK_FETCH, Vec::new());
    e.packs().set_mode(emon::emon::engines::pfetch_auto());
    let (req, bm) = request(3, 1, false);
    let router = e.router().unwrap();
    let a = router.read_blocking(&req, &bm, false).unwrap();
    assert_eq!((a.rec.len(), status(&a)), (4, kp::ps_unsupported()), "unread, never blank");
    assert_eq!((h.asked.load(Ordering::SeqCst), s.reads.load(Ordering::SeqCst)), (1, 0));
    assert!(e.take_notes().join("\n").contains("pack-fetch-unavailable:tess-fast-eng"));
    assert!(!dir.join("tess-fast-eng/eng.traineddata").exists());
    e.packs().set_hook(None, std::ptr::null_mut());
    let a = router.read_blocking(&req, &bm, false).unwrap();
    assert_eq!((a.rec.len(), status(&a)), (4, kp::ps_unsupported()));
    assert!(e.take_notes().join("\n").contains("pack-fetch-unavailable:tess-fast-eng"));
    assert_eq!(e.packs().ensure(&[1]).unwrap_err(), "pack-fetch-unavailable:tess-fast-eng");
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn the_host_supplies_a_local_file_and_core_verifies_it_before_installing() {
    let _g = serial();
    let Some(good) = eng_traineddata() else {
        eprintln!("SKIP: no eng.traineddata");
        return;
    };
    let (e, s, _, dir) = hooked(emon::packs::PACK_FETCH, Vec::new());
    e.packs().set_mode(emon::emon::engines::pfetch_auto());
    let local = dir.join("source");
    let mut bad = good.clone();
    bad[777] ^= 1;
    std::fs::write(&local, bad).unwrap();
    e.packs().set_fetcher(Arc::new({
        let local = local.clone();
        move |_, _, progress| {
            let bytes = std::fs::read(&local).map_err(|e| e.to_string())?;
            if progress(bytes.len() as u64) {
                return Err("cancelled".to_string());
            }
            Ok(bytes)
        }
    }));
    let (req, bm) = request(3, 1, false);
    let router = e.router().unwrap();
    let a = router.read_blocking(&req, &bm, false).unwrap();
    assert_eq!((a.rec.len(), status(&a)), (4, kp::ps_unsupported()));
    assert!(e.take_notes().join("\n").contains("pack-hash-mismatch:tess-fast-eng"));
    assert!(!dir.join("tess-fast-eng/eng.traineddata").exists());
    std::fs::write(local, good).unwrap();

    unsafe extern "C" fn stop(_: *mut c_void, event: *const emon::packs::PackEvent) -> c_int {
        ((*event).kind == emon::packs::PACK_EVENT_PROGRESS) as c_int
    }
    e.packs().set_events(Some(stop), std::ptr::null_mut());
    assert_eq!(e.packs().ensure(&[1]).unwrap_err(), "pack-fetch-cancelled:tess-fast-eng");
    assert!(!dir.join("tess-fast-eng/eng.traineddata").exists());
    e.packs().set_events(None, std::ptr::null_mut());
    let a = router.read_blocking(&req, &bm, false).unwrap();
    assert_eq!((status(&a), s.reads.load(Ordering::SeqCst)), (0, 1));
    assert!(dir.join("tess-fast-eng/eng.traineddata").exists());
    assert!(e.packs().ensure(&[1]).is_ok());
    std::fs::remove_dir_all(dir).unwrap();
}

// ------------------------------------------------------------------------------------------------ one registry per process (D925)

// The CLI and the pod link Tesseract themselves: an in-process port is REGISTERED as the Tesseract adapter and the registry's router is the one `Ocr` port, so it is held to
// kin's table and routed by engine like a library.
struct InProcess;
impl emon::Ocr for InProcess {
    fn fulfil(&self, req: &Request, _bm: &Bitmaps, _keep: bool) -> Result<emon::plan::Answer, emon::plan::PlanError> {
        Ok(emon::plan::Answer { rec: vec![4, req.rec[1], req.rec[2], kp::ps_unsupported()], bitmap: None })
    }
    fn producer(&self) -> Vec<(String, String)> {
        vec![("engine".to_string(), "in-process".to_string())]
    }
}

#[test]
fn an_in_process_port_registers_as_tesseract_and_the_registry_is_the_port() {
    let _g = serial();
    let e = Engines::new();
    assert_eq!(e.port().unwrap_err(), "no adapter", "an empty registry has no port: the host must not wire an engine");
    e.add_tesseract(Arc::new(InProcess)).unwrap();
    assert!(e.has_ocr());
    let port = e.port().unwrap();
    assert_eq!(port.0.producer(), vec![("engine".to_string(), "in-process".to_string())], "one adapter: its producer is the port's");
    let (req, bm) = request(3, 1, false);
    let a = port.0.fulfil(&req, &bm, false).unwrap();
    assert_eq!((a.rec.len(), a.rec[2], status(&a)), (4, 3, kp::ps_unsupported()), "the adapter that cannot read leaves the page unread");
    // a request for PP-OCR has nobody registered for it: unsupported, and the Tesseract port was not asked
    let (req, bm) = request(4, 2, false);
    assert_eq!(status(&port.0.fulfil(&req, &bm, false).unwrap()), kp::ps_unsupported());
    // the second registration of the role is another adapter of the same engine: kin routes to the first
    assert_eq!(e.adapters().len(), 1);
}
