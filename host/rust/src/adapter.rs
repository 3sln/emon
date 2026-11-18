// THE NATIVE OCR ADAPTERS AND THE ENGINE REGISTRY (K2, D899; D883, D885, D896; pkg1 sections 1.2 to 1.7). `host/c/include/emon_adapter.h` is the ABI and its documentation; this
// file is the host side of it: loading an adapter library (or taking a struct of callbacks), checking what it says it is, routing an `ocr` request to the adapter of its engine,
// holding what comes back to what the adapter registered, and answering later when an adapter does.
//
// KIN DECIDES, THE HOST DOES (D898). Whether an adapter may be registered (`ad-register-check`), where a request goes (`ad-route`), whether an answer is what its adapter registered
// (`ad-answer-check`), what a refusal is (`ad-refusal`: four cells, an unread page, never an empty reading, D038), what to do about a pack (`ad-pack-action`, `packs.rs`), what
// `prepare()` and `engines()` say (`ad-verdict`, `ad-engine-state`) are `emon.adapters`, a law each. This file loads, calls, locks, waits and spells JSON.
//
// THE `Ocr` PORT. A `Router` is an `engine::Ocr`: the one-shot calls put it in `Config::ocr`, and a request is read (a thread blocks while an async adapter works). The step session
// (`session.rs`) asks it per request instead (`begin`): a sync adapter answers inside the step, an async one returns a token and the answer arrives in the session's `Mailbox`
// (`emon_complete`, or the adapter's own call of `emon_adapter_host.complete`), and the step says WAITING while only that is outstanding.
use crate::emon::{adapters as ka, engines as ke, oil as ko, ocr as kr, ocrport as kop, scan as ks};
use crate::engine::{Ocr, OcrPolicy, Parsed};
use crate::packs::{self, Need, Packs, Row};
use crate::plan::{Answer, Bitmaps, PlanError, Request, KIND_OCR};
use crate::port::{cells_at, cells_len, cells_push, Bitmap, Cells};
use crate::render::Renderer;
use std::collections::{HashMap, VecDeque};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, RwLock};
use std::time::{Duration, Instant};

// ------------------------------------------------------------------------------------------------ the ABI, as Rust sees it (emon_adapter.h)

pub const ADAPTER_ABI: u32 = 1;
pub const ADAPTER_OK: c_int = 0;
pub const ADAPTER_UNSUPPORTED: c_int = 1;
pub const ADAPTER_FAILED: c_int = 2;
pub const ADAPTER_ASYNC: u32 = 1;
pub const ADAPTER_REMOTE: u32 = 2;
pub const ADAPTER_THREADSAFE: u32 = 4;

#[repr(C)]
pub struct AdapterStatus {
    pub reason: [c_char; 96],
    pub message: [c_char; 224],
}

impl AdapterStatus {
    pub fn new() -> AdapterStatus {
        AdapterStatus { reason: [0; 96], message: [0; 224] }
    }

    pub fn set(&mut self, reason: &str, message: &str) {
        fn put(dst: &mut [c_char], s: &str) {
            let b: Vec<u8> = s.bytes().filter(|c| *c != 0).take(dst.len() - 1).collect();
            for (i, c) in b.iter().enumerate() {
                dst[i] = *c as c_char;
            }
            dst[b.len()] = 0;
        }
        put(&mut self.reason, reason);
        put(&mut self.message, message);
    }

    pub fn reason_text(&self) -> String {
        unsafe { CStr::from_ptr(self.reason.as_ptr()) }.to_string_lossy().to_string()
    }

    pub fn message_text(&self) -> String {
        unsafe { CStr::from_ptr(self.message.as_ptr()) }.to_string_lossy().to_string()
    }
}

impl Default for AdapterStatus {
    fn default() -> Self {
        AdapterStatus::new()
    }
}

#[repr(C)]
pub struct AdapterImage {
    pub width: i32,
    pub height: i32,
    pub channels: i32,
    pub stride: i32,
    pub pixels: *const u8,
    pub dpi: i32,
}

#[repr(C)]
pub struct AdapterInfo {
    pub engine: u32,
    pub caps: u32,
    pub native: u32,
    pub needs: u32,
    pub flags: u32,
    pub config: *const c_char,
    pub observed: *const c_char,
    pub producer: *const c_char,
    pub langs: *const *const c_char,
    pub nlangs: usize,
}

#[repr(C)]
pub struct AdapterAnswer {
    pub cells: *mut i32,
    pub ncells: usize,
    pub binary: *const u8,
    pub binary_width: i32,
    pub binary_height: i32,
    pub owner: *mut c_void,
}

pub type CompleteFn = unsafe extern "C" fn(ctx: *mut c_void, token: u64, status: c_int, cells: *const i32, ncells: usize, why: *const AdapterStatus) -> c_int;

#[repr(C)]
pub struct AdapterHost {
    pub abi: u32,
    pub size: u32,
    pub ctx: *mut c_void,
    pub complete: Option<CompleteFn>,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct OcrAdapter {
    pub abi: u32,
    pub size: u32,
    pub name: *const c_char,
    pub ctx: *mut c_void,
    pub describe: Option<unsafe extern "C" fn(*mut c_void, *mut AdapterInfo, *mut AdapterStatus) -> c_int>,
    pub supports: Option<unsafe extern "C" fn(*mut c_void, *const i32, usize, *mut AdapterStatus) -> c_int>,
    pub read: Option<unsafe extern "C" fn(*mut c_void, *const i32, usize, *const AdapterImage, c_int, *mut AdapterAnswer, *mut AdapterStatus) -> c_int>,
    pub start: Option<unsafe extern "C" fn(*mut c_void, *const i32, usize, *const AdapterImage, u64, *mut AdapterStatus) -> c_int>,
    pub cancel: Option<unsafe extern "C" fn(*mut c_void, u64)>,
    pub release: Option<unsafe extern "C" fn(*mut c_void, *mut AdapterAnswer)>,
    pub bind: Option<unsafe extern "C" fn(*mut c_void, *const AdapterHost)>,
    pub close: Option<unsafe extern "C" fn(*mut c_void)>,
}

pub type AdapterV1Fn = unsafe extern "C" fn(config: *const c_char, packs_dir: *const c_char, st: *mut AdapterStatus) -> *const OcrAdapter;

unsafe impl Send for OcrAdapter {}
unsafe impl Sync for OcrAdapter {}

// ------------------------------------------------------------------------------------------------ loading a library (dlopen, LoadLibrary)

#[cfg(unix)]
extern "C" {
    fn dlopen(p: *const c_char, flags: i32) -> *mut c_void;
    fn dlsym(p: *mut c_void, n: *const c_char) -> *mut c_void;
    fn dlclose(p: *mut c_void) -> i32;
}

#[cfg(windows)]
#[link(name = "kernel32")]
extern "system" {
    fn LoadLibraryExW(path: *const u16, file: *mut c_void, flags: u32) -> *mut c_void;
    fn GetProcAddress(library: *mut c_void, name: *const c_char) -> *mut c_void;
    fn FreeLibrary(library: *mut c_void) -> i32;
}

// A loaded library, closed when the last adapter taken from it is gone.
pub struct Lib(*mut c_void);

unsafe impl Send for Lib {}
unsafe impl Sync for Lib {}

impl Lib {
    #[cfg(unix)]
    pub fn open(path: &str) -> Result<Lib, String> {
        let c = CString::new(path).map_err(|_| "the path has a NUL".to_string())?;
        // RTLD_NOW (2): a missing symbol of the adapter's own is found now, not in the middle of a page
        let h = unsafe { dlopen(c.as_ptr(), 2) };
        if h.is_null() {
            Err(format!("{} cannot be loaded", path))
        } else {
            Ok(Lib(h))
        }
    }

    #[cfg(windows)]
    pub fn open(path: &str) -> Result<Lib, String> {
        use std::os::windows::ffi::OsStrExt;
        let p = std::path::PathBuf::from(path).canonicalize().map_err(|_| format!("{} is not there", path))?;
        let wide: Vec<u16> = p.as_os_str().encode_wide().chain(Some(0)).collect();
        // LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS: what the adapter links to is found beside it, never in the working directory
        let h = unsafe { LoadLibraryExW(wide.as_ptr(), std::ptr::null_mut(), 0x100 | 0x1000) };
        if h.is_null() {
            Err(format!("{} cannot be loaded", path))
        } else {
            Ok(Lib(h))
        }
    }

    #[cfg(not(any(unix, windows)))]
    pub fn open(path: &str) -> Result<Lib, String> {
        Err(format!("{}: this platform loads no libraries", path))
    }

    pub fn symbol(&self, name: &str) -> Option<*mut c_void> {
        let c = CString::new(name).ok()?;
        #[cfg(unix)]
        let p = unsafe { dlsym(self.0, c.as_ptr()) };
        #[cfg(windows)]
        let p = unsafe { GetProcAddress(self.0, c.as_ptr()) };
        #[cfg(not(any(unix, windows)))]
        let p: *mut c_void = std::ptr::null_mut();
        if p.is_null() {
            None
        } else {
            Some(p)
        }
    }
}

impl Drop for Lib {
    fn drop(&mut self) {
        #[cfg(unix)]
        unsafe {
            dlclose(self.0);
        }
        #[cfg(windows)]
        unsafe {
            FreeLibrary(self.0);
        }
    }
}

// ------------------------------------------------------------------------------------------------ an adapter, registered

// What an adapter said it is (`describe`), owned.
#[derive(Debug, Clone)]
pub struct Info {
    pub engine: u32,
    pub caps: u32,
    pub measured: u32,
    pub needs: u32,
    pub flags: u32,
    pub config: String,
    pub observed: String,
    // `key=value` pairs for a transcript's `:producer`
    pub producer: Vec<(String, String)>,
    // the languages it can read now (emon's codes), or None: whatever its packs allow
    pub langs: Option<Vec<String>>,
}

// Why a registration was refused: `name` is the stable name to route on (`adapter-abi-unsupported`, `adapter-runtime-missing`, ...).
#[derive(Debug, Clone)]
pub struct RegError {
    pub name: String,
    pub message: String,
}

impl RegError {
    fn new(name: &str, message: impl Into<String>) -> RegError {
        RegError { name: name.to_string(), message: message.into() }
    }
}

pub struct Adapter {
    pub name: String,
    pub info: Info,
    vt: OcrAdapter,
    // the adapter's flags: `info.flags`
    host: Box<AdapterHost>,
    serial: Mutex<()>,
    // dropped after `close`
    _lib: Option<Lib>,
}

unsafe impl Send for Adapter {}
unsafe impl Sync for Adapter {}

fn status_text(st: &AdapterStatus, fallback: &str) -> (String, String) {
    let (r, m) = (st.reason_text(), st.message_text());
    (if r.is_empty() { fallback.to_string() } else { r }, if m.is_empty() { fallback.to_string() } else { m })
}

unsafe fn text_of(p: *const c_char) -> String {
    if p.is_null() {
        String::new()
    } else {
        CStr::from_ptr(p).to_string_lossy().to_string()
    }
}

impl Adapter {
    // Take an adapter: describe it, hold what it says to kin's table (`ad-register-check`), bind it. The adapter is closed if it is refused.
    fn open(vt: OcrAdapter, lib: Option<Lib>, inner: *const Inner, name_hint: Option<&str>) -> Result<Adapter, RegError> {
        let close = |vt: &OcrAdapter| {
            if let Some(c) = vt.close {
                unsafe { c(vt.ctx) }
            }
        };
        if vt.abi < 1 || vt.abi > ka::ad_abi() as u32 {
            close(&vt);
            return Err(RegError::new("adapter-abi-unsupported", format!("the adapter is built for ABI {}; this library speaks {}", vt.abi, ka::ad_abi())));
        }
        if (vt.size as usize) < std::mem::size_of::<OcrAdapter>() {
            close(&vt);
            return Err(RegError::new("adapter-abi-unsupported", format!("the adapter's struct is {} bytes; ABI 1 is {}", vt.size, std::mem::size_of::<OcrAdapter>())));
        }
        let Some(describe) = vt.describe else {
            close(&vt);
            return Err(RegError::new("adapter-abi-unsupported", "the adapter has no `describe`"));
        };
        let mut raw = AdapterInfo { engine: 0, caps: 0, native: 0, needs: 0, flags: 0, config: std::ptr::null(), observed: std::ptr::null(), producer: std::ptr::null(), langs: std::ptr::null(), nlangs: 0 };
        let mut st = AdapterStatus::new();
        let rc = unsafe { describe(vt.ctx, &mut raw, &mut st) };
        if rc != ADAPTER_OK {
            close(&vt);
            let (r, m) = status_text(&st, "describe failed");
            return Err(RegError::new("adapter-describe-failed", format!("{}: {}", r, m)));
        }
        let name = {
            let n = unsafe { text_of(vt.name) };
            if n.is_empty() { name_hint.unwrap_or("adapter").to_string() } else { n }
        };
        let check = ka::ad_register_check(vt.abi as i32, raw.engine as i32, raw.caps as i32, raw.native as i32, raw.needs as i32, raw.flags as i32);
        if check != ka::adr_ok() {
            let mut why = String::new();
            ka::adr_emit_name(&mut why, check);
            close(&vt);
            return Err(RegError::new(&why, format!("{} ({}): engine {}, caps {}, needs {}, flags {}", name, why, raw.engine, raw.caps, raw.needs, raw.flags)));
        }
        let producer_text = unsafe { text_of(raw.producer) };
        let mut producer: Vec<(String, String)> = producer_text.lines().filter_map(|l| l.split_once('=').map(|(k, v)| (k.to_string(), v.to_string()))).collect();
        if !producer.iter().any(|(k, _)| k == "engine") {
            producer.insert(0, ("engine".to_string(), name.clone()));
        }
        let langs = if raw.langs.is_null() {
            None
        } else {
            Some((0..raw.nlangs).map(|i| unsafe { text_of(*raw.langs.add(i)) }).collect())
        };
        let info = Info { engine: raw.engine, caps: raw.caps, measured: raw.native, needs: raw.needs, flags: raw.flags, config: unsafe { text_of(raw.config) }, observed: unsafe { text_of(raw.observed) }, producer, langs };
        if info.flags & ADAPTER_ASYNC != 0 && vt.start.is_none() {
            close(&vt);
            return Err(RegError::new("adapter-abi-unsupported", format!("{} says it is async and has no `start`", name)));
        }
        if info.flags & ADAPTER_ASYNC == 0 && vt.read.is_none() {
            close(&vt);
            return Err(RegError::new("adapter-abi-unsupported", format!("{} is sync and has no `read`", name)));
        }
        let host = Box::new(AdapterHost { abi: ADAPTER_ABI, size: std::mem::size_of::<AdapterHost>() as u32, ctx: inner as *mut c_void, complete: Some(host_complete) });
        if let Some(bind) = vt.bind {
            unsafe { bind(vt.ctx, &*host) };
        }
        Ok(Adapter { name, info, vt, host, serial: Mutex::new(()), _lib: lib })
    }

    fn threadsafe(&self) -> bool {
        self.info.flags & ADAPTER_THREADSAFE != 0
    }

    fn is_async(&self) -> bool {
        self.info.flags & ADAPTER_ASYNC != 0
    }

    pub fn oen(&self) -> i32 {
        self.info.engine as i32
    }

    unsafe fn supports(&self, req: &[i32]) -> Result<(), (String, String)> {
        let Some(f) = self.vt.supports else { return Ok(()) };
        let mut st = AdapterStatus::new();
        let rc = f(self.vt.ctx, req.as_ptr(), req.len(), &mut st);
        if rc == ADAPTER_OK {
            Ok(())
        } else {
            Err(status_text(&st, "unsupported"))
        }
    }

    // Read one request synchronously: the answer record, and the thresholded image when it was asked for.
    #[allow(clippy::type_complexity)]
    unsafe fn read(&self, req: &[i32], image: &AdapterImage, want_binary: bool) -> Result<(Vec<i32>, Option<(i32, i32, Vec<u8>)>), (c_int, String)> {
        let Some(read) = self.vt.read else { return Err((ADAPTER_FAILED, "no-read".to_string())) };
        let _g = if self.threadsafe() { None } else { Some(self.serial.lock().unwrap_or_else(|e| e.into_inner())) };
        let mut out = AdapterAnswer { cells: std::ptr::null_mut(), ncells: 0, binary: std::ptr::null(), binary_width: 0, binary_height: 0, owner: std::ptr::null_mut() };
        let mut st = AdapterStatus::new();
        let rc = read(self.vt.ctx, req.as_ptr(), req.len(), image, want_binary as c_int, &mut out, &mut st);
        if rc != ADAPTER_OK {
            let (r, _) = status_text(&st, if rc == ADAPTER_UNSUPPORTED { "unsupported" } else { "failed" });
            // an adapter may allocate before it fails
            if let Some(rel) = self.vt.release {
                if !out.cells.is_null() || !out.owner.is_null() {
                    rel(self.vt.ctx, &mut out);
                }
            }
            return Err((rc, r));
        }
        let cells = if out.cells.is_null() { Vec::new() } else { std::slice::from_raw_parts(out.cells, out.ncells).to_vec() };
        let binary = if want_binary && !out.binary.is_null() && out.binary_width > 0 && out.binary_height > 0 {
            Some((out.binary_width, out.binary_height, std::slice::from_raw_parts(out.binary, out.binary_width as usize * out.binary_height as usize).to_vec()))
        } else {
            None
        };
        if let Some(rel) = self.vt.release {
            rel(self.vt.ctx, &mut out);
        }
        Ok((cells, binary))
    }

    unsafe fn start(&self, req: &[i32], image: &AdapterImage, token: u64) -> Result<(), (c_int, String)> {
        let Some(start) = self.vt.start else { return Err((ADAPTER_FAILED, "no-start".to_string())) };
        let mut st = AdapterStatus::new();
        let rc = start(self.vt.ctx, req.as_ptr(), req.len(), image, token, &mut st);
        if rc == ADAPTER_OK {
            Ok(())
        } else {
            Err((rc, status_text(&st, if rc == ADAPTER_UNSUPPORTED { "unsupported" } else { "failed" }).0))
        }
    }

    fn cancel(&self, token: u64) {
        if let Some(c) = self.vt.cancel {
            unsafe { c(self.vt.ctx, token) }
        }
    }
}

impl Drop for Adapter {
    fn drop(&mut self) {
        if let Some(c) = self.vt.close {
            unsafe { c(self.vt.ctx) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ answering later

// How a request ended when the adapter was async.
#[derive(Debug)]
pub enum Completion {
    Answer(Vec<i32>),
    Refused { status: i32, reason: String },
}

// What an async completion needs to be settled: the adapter that was asked, the request, the bitmap's size.
pub struct Meta {
    pub entry: Arc<Adapter>,
    pub id: i32,
    pub oen: i32,
    pub width: i32,
    pub height: i32,
}

// Where completions arrive: the session's (or the one-shot call's own).
pub struct Mailbox {
    q: Mutex<VecDeque<(u64, Meta, Completion)>>,
    cv: Condvar,
}

impl Mailbox {
    pub fn new() -> Arc<Mailbox> {
        Arc::new(Mailbox { q: Mutex::new(VecDeque::new()), cv: Condvar::new() })
    }

    fn push(&self, token: u64, meta: Meta, c: Completion) {
        self.q.lock().unwrap_or_else(|e| e.into_inner()).push_back((token, meta, c));
        self.cv.notify_all();
    }

    pub fn take(&self) -> Vec<(u64, Meta, Completion)> {
        self.q.lock().unwrap_or_else(|e| e.into_inner()).drain(..).collect()
    }

    // Wait until something is here or the time is up (`timeout`); true when something is.
    pub fn wait(&self, timeout: Duration) -> bool {
        let g = self.q.lock().unwrap_or_else(|e| e.into_inner());
        if !g.is_empty() {
            return true;
        }
        let (g, _) = self.cv.wait_timeout_while(g, timeout, |q| q.is_empty()).unwrap_or_else(|e| e.into_inner());
        !g.is_empty()
    }
}

struct Pending {
    mailbox: Arc<Mailbox>,
    meta: Meta,
}

unsafe extern "C" fn host_complete(ctx: *mut c_void, token: u64, status: c_int, cells: *const i32, ncells: usize, why: *const AdapterStatus) -> c_int {
    let inner = &*(ctx as *const Inner);
    let c = if status == ADAPTER_OK {
        Completion::Answer(if cells.is_null() { Vec::new() } else { std::slice::from_raw_parts(cells, ncells).to_vec() })
    } else {
        let reason = if why.is_null() { String::new() } else { (*why).reason_text() };
        Completion::Refused { status, reason: if reason.is_empty() { if status == ADAPTER_UNSUPPORTED { "unsupported".to_string() } else { "failed".to_string() } } else { reason } }
    };
    if inner.complete(token, c) {
        0
    } else {
        1
    }
}

// ------------------------------------------------------------------------------------------------ the registry

struct Inner {
    entries: RwLock<Vec<Arc<Adapter>>>,
    renderer: Mutex<Option<Arc<dyn Renderer>>>,
    packs: Packs,
    timeout_ms: AtomicI64,
    allow_remote: AtomicBool,
    next_token: AtomicU64,
    pending: Mutex<HashMap<u64, Pending>>,
    notes: Mutex<Vec<String>>,
    // the pack directory is named before the first adapter is opened (an adapter is given its view of it)
    sealed: AtomicBool,
}

impl Inner {
    fn note(&self, s: String) {
        self.notes.lock().unwrap_or_else(|e| e.into_inner()).push(s);
    }

    // An adapter says a request is done. false: nobody is waiting for that token (a late answer after a cancel).
    fn complete(&self, token: u64, c: Completion) -> bool {
        let p = self.pending.lock().unwrap_or_else(|e| e.into_inner()).remove(&token);
        match p {
            Some(p) => {
                p.mailbox.push(token, p.meta, c);
                true
            }
            None => false,
        }
    }

    fn cancel(&self, token: u64) {
        let p = self.pending.lock().unwrap_or_else(|e| e.into_inner()).remove(&token);
        if let Some(p) = p {
            p.meta.entry.cancel(token);
        }
    }
}

impl Drop for Inner {
    fn drop(&mut self) {
        // every adapter still waited for is told to stop, then closed (by the drop of its entry)
        let pend: Vec<(u64, Pending)> = self.pending.lock().unwrap_or_else(|e| e.into_inner()).drain().collect();
        for (t, p) in pend {
            p.meta.entry.cancel(t);
        }
    }
}

// The engines an embedder has: its OCR adapters (in preference order), its renderer, and where the packs are.
#[derive(Clone)]
pub struct Engines {
    inner: Arc<Inner>,
}

impl Default for Engines {
    fn default() -> Engines {
        Engines::new()
    }
}

impl Engines {
    pub fn new() -> Engines {
        Engines {
            inner: Arc::new(Inner {
                entries: RwLock::new(Vec::new()),
                renderer: Mutex::new(None),
                packs: Packs::new(),
                timeout_ms: AtomicI64::new(60_000),
                allow_remote: AtomicBool::new(false),
                next_token: AtomicU64::new(1),
                pending: Mutex::new(HashMap::new()),
                notes: Mutex::new(Vec::new()),
                sealed: AtomicBool::new(false),
            }),
        }
    }

    pub fn packs(&self) -> &Packs {
        &self.inner.packs
    }

    // How long a one-shot call waits for an async adapter, per request (pkg1 `ocr.timeout-ms`).
    pub fn set_timeout_ms(&self, ms: i64) {
        self.inner.timeout_ms.store(ms, Ordering::SeqCst);
    }

    // Adapters that send pixels off the device are refused unless this says so (pkg1 1.6: documents may hold personal data).
    pub fn allow_remote(&self, allow: bool) {
        self.inner.allow_remote.store(allow, Ordering::SeqCst);
    }

    // The pack directory is the store and the cache. Named before the first adapter is registered: each adapter is given its view of it when it is opened.
    pub fn set_packs_dir(&self, dir: &std::path::Path) -> Result<(), RegError> {
        if self.inner.sealed.load(Ordering::SeqCst) {
            return Err(RegError::new("usage", "name the pack directory before registering adapters: an adapter is given its view of it when it is opened"));
        }
        self.inner.packs.set_store(dir).map_err(|e| RegError::new("pack-dir-missing", e))
    }

    // `set_packs_dir` for a caller that holds the path as text (the C binding names no path type).
    pub fn set_packs_dir_text(&self, dir: &str) -> Result<(), RegError> {
        self.set_packs_dir(std::path::Path::new(dir))
    }

    // The pack mode a TURNKEY registry starts in: `$EMON_PACKS_FETCH` (never, cache, auto), else the platform class's default (kin's `pk-default-fetch`) when this build can fetch, else
    // `cache`. Err names a value that is none of the three.
    pub fn turnkey_mode(&self) -> Result<i32, String> {
        match std::env::var("EMON_PACKS_FETCH").ok().as_deref() {
            Some("never") => Ok(ke::pfetch_never()),
            Some("cache") => Ok(ke::pfetch_cache()),
            Some("auto") => Ok(ke::pfetch_auto()),
            Some(other) if !other.is_empty() => Err(format!("EMON_PACKS_FETCH is never, cache or auto, not {:?}", other)),
            _ => Ok(if cfg!(feature = "packs-fetch") { ke::pk_default_fetch(self.inner.packs.class()) } else { ke::pfetch_cache() }),
        }
    }

    pub fn set_renderer(&self, r: Arc<dyn Renderer>) {
        *self.inner.renderer.lock().unwrap_or_else(|e| e.into_inner()) = Some(r);
    }

    pub fn renderer(&self) -> Option<Arc<dyn Renderer>> {
        self.inner.renderer.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn has_ocr(&self) -> bool {
        !self.inner.entries.read().unwrap_or_else(|e| e.into_inner()).is_empty()
    }

    pub fn adapters(&self) -> Vec<Arc<Adapter>> {
        self.inner.entries.read().unwrap_or_else(|e| e.into_inner()).clone()
    }

    fn admit(&self, e: Adapter) -> Result<usize, RegError> {
        if e.info.flags & ADAPTER_REMOTE != 0 && !self.inner.allow_remote.load(Ordering::SeqCst) {
            return Err(RegError::new("adapter-remote-refused", format!("{} sends the pixels off the device; allow it with emon_engines_allow_remote (documents may hold personal data)", e.name)));
        }
        let mut v = self.inner.entries.write().unwrap_or_else(|e| e.into_inner());
        v.push(Arc::new(e));
        Ok(v.len() - 1)
    }

    // An adapter given as callbacks: the struct is copied, `ctx` stays the caller's, `close` is called when the registry goes.
    pub fn add_adapter(&self, vt: OcrAdapter) -> Result<usize, RegError> {
        self.inner.sealed.store(true, Ordering::SeqCst);
        let e = Adapter::open(vt, None, Arc::as_ptr(&self.inner), None)?;
        self.admit(e)
    }

    // An adapter library: `emon_ocr_adapter_v1` of the file at `path`, opened with `config` and the view of the packs of its engine.
    pub fn load(&self, path: &str, config: Option<&str>) -> Result<usize, RegError> {
        let lib = Lib::open(path).map_err(|m| RegError::new("adapter-runtime-missing", m))?;
        let sym = lib.symbol("emon_ocr_adapter_v1").ok_or_else(|| RegError::new("adapter-runtime-missing", format!("{} exports no emon_ocr_adapter_v1", path)))?;
        let open: AdapterV1Fn = unsafe { std::mem::transmute(sym) };
        // which engine's packs the adapter is given: named by `engine=<name>` in its config, else by the engine of kin's table whose name the file's carries
        // (`libemon_ocr_tesseract`, `libemon_ppocr_adapter`)
        let named = config.and_then(|c| c.lines().find_map(|l| l.strip_prefix("engine=").map(|v| v.trim().to_string())));
        let stem = std::path::Path::new(path).file_stem().map(|s| s.to_string_lossy().to_lowercase().replace('-', "_")).unwrap_or_default();
        let engine = named.unwrap_or_else(|| {
            (0..ke::pk_engine_count())
                .map(|i| Engines::eng_name(ke::pk_engine_at(i)))
                .filter(|n| ke::pk_engine_kind(ke::eng_find(n.as_bytes(), 0, n.len() as i32)) == ke::ekind_ocr())
                .find(|n| stem.contains(&n.replace('-', "_")))
                .unwrap_or_default()
        });
        let view = if engine.is_empty() { None } else { Some(self.inner.packs.view(&engine).map_err(|m| RegError::new("pack-dir-missing", m))?) };
        self.inner.sealed.store(true, Ordering::SeqCst);
        let (cfg, dir) = (config.map(|c| CString::new(c).unwrap_or_default()), view.map(|v| CString::new(v.to_string_lossy().as_bytes()).unwrap_or_default()));
        let mut st = AdapterStatus::new();
        let p = unsafe { open(cfg.as_ref().map(|c| c.as_ptr()).unwrap_or(std::ptr::null()), dir.as_ref().map(|c| c.as_ptr()).unwrap_or(std::ptr::null()), &mut st) };
        if p.is_null() {
            let (r, m) = status_text(&st, "the adapter could not be opened");
            return Err(RegError::new("adapter-open-failed", format!("{}: {}: {}", path, r, m)));
        }
        let vt = unsafe { *p };
        let e = Adapter::open(vt, Some(lib), Arc::as_ptr(&self.inner), Some(&engine))?;
        self.admit(e)
    }

    // What the adapters and the packs noted since the last call (reasons of unsupported and failed answers, mismatches), one line each.
    pub fn take_notes(&self) -> Vec<String> {
        let mut v = std::mem::take(&mut *self.inner.notes.lock().unwrap_or_else(|e| e.into_inner()));
        v.extend(self.inner.packs.take_notes());
        v
    }

    pub fn router(&self) -> Option<Router> {
        if self.has_ocr() {
            Some(Router { inner: self.inner.clone() })
        } else {
            None
        }
    }

    // ---------------------------------------------------------------------------------------- engines() and prepare()

    fn eng_name(eng: i32) -> String {
        let mut s = String::new();
        ke::eng_emit_name(&mut s, eng);
        s
    }

    fn registered(&self, eng: i32) -> bool {
        if ke::pk_engine_kind(eng) == ke::ekind_render() {
            return self.renderer().is_some();
        }
        // the table's `impl` decides: a platform plugin (ML Kit, the browser's PP-OCR) is registered as callbacks, a native engine as a library: both are an entry of the engine's OIL code
        let oen = ke::pk_engine_oen(eng);
        // two rows may share an OIL engine (ppocr, ppocr-web): the one that runs on this platform is the registered one
        ke::pk_engine_on(eng, self.inner.packs.class()) == 1 && self.inner.entries.read().unwrap_or_else(|e| e.into_inner()).iter().any(|a| a.oen() == oen)
    }

    // D896's `engines()`: every engine of kin's table that runs on this platform, with its state: built in, installed, downloadable or unavailable.
    pub fn describe_json(&self) -> String {
        let packs = &self.inner.packs;
        let class = packs.class();
        let installed = packs.installed();
        let (mode, off) = (packs.mode(), packs::offline());
        let mut engines = Vec::new();
        for i in 0..ke::pk_engine_count() {
            let eng = ke::pk_engine_at(i);
            if ke::pk_engine_on(eng, class) == 0 {
                continue;
            }
            let name = Engines::eng_name(eng);
            let registered = self.registered(eng);
            let rows = if ke::pk_engine_kind(eng) == ke::ekind_ocr() && ke::pk_engine_needs(eng) != 0 { packs.resolve_with(&[eng], &[], false, &installed).unwrap_or_default() } else { Vec::new() };
            let missing: Vec<&Row> = rows.iter().filter(|r| r.state == ke::pstate_missing()).collect();
            let state = ka::ad_engine_state(registered as i32, ke::pk_engine_impl(eng), ke::pk_engine_needs(eng), missing.len() as i32, mode, off as i32);
            let mut state_name = String::new();
            ka::est_emit_name(&mut state_name, state);
            let reason = if !registered {
                "no adapter is registered for it".to_string()
            } else if state == ka::est_unavailable() {
                if off { "EMON_OFFLINE=1".to_string() } else { "a pack is missing and the pack mode does not fetch".to_string() }
            } else {
                String::new()
            };
            let mut kind = String::new();
            ke::ekind_emit_name(&mut kind, ke::pk_engine_kind(eng));
            let mut imp = String::new();
            ke::eimpl_emit_name(&mut imp, ke::pk_engine_impl(eng));
            let mut role = String::new();
            ke::erole_emit_name(&mut role, ke::pk_role_default(eng, class));
            let needs: Vec<String> = rows
                .iter()
                .filter(|r| r.pack > 0)
                .map(|r| {
                    let info = packs::pack_info(r.pack);
                    format!("{{\"id\":{},\"kind\":{},\"bytes\":{},\"licence\":{},\"installed\":{}}}", js(&info.id), js(&info.kind_name), info.bytes, js(&info.licence), r.state == ke::pstate_installed())
                })
                .collect();
            let dl: u64 = missing.iter().map(|r| packs::pack_info(r.pack).bytes).sum();
            engines.push(format!(
                "{{\"name\":{},\"kind\":{},\"impl\":{},\"state\":{},\"registered\":{},\"calibrated\":{},\"caps\":{},\"oen\":{},\"role\":{},\"needs\":[{}],\"download_bytes\":{},\"reason\":{}}}",
                js(&name), js(&kind), js(&imp), js(&state_name), registered, ke::pk_engine_calibrated(eng) == 1, ke::pk_engine_caps(eng), ke::pk_engine_oen(eng), js(&role), needs.join(","), dl, js(&reason)
            ));
        }
        let mut pcls = String::new();
        ke::pcls_emit_name(&mut pcls, class);
        let mut m = String::new();
        ke::pfetch_emit_name(&mut m, mode);
        format!("{{\"platform\":{},\"mode\":{},\"offline\":{},\"store\":{},\"engines\":[{}]}}", js(&pcls), js(&m), off, js(&packs.store().to_string_lossy()), engines.join(","))
    }

    // D896's `prepare(options)`: the packs a conversion with these options needs and which are missing, as kin resolves them (`pk-resolve`) and summarises them (`ad-verdict`).
    pub fn prepare_json(&self, options: &str) -> Result<String, String> {
        let p: Parsed = crate::engine::parse_options(options)?;
        let packs = &self.inner.packs;
        let (mode, off) = (packs.mode(), packs::offline());
        let never = p.options.ocr == OcrPolicy::Never;
        // the platform engines read alone and need no pack (K8, D901); PP-OCR, alone or not (D900), is loaded beside Tesseract's pack
        let mut engines = match p.options.ocr_engine {
            crate::engine::OcrEngine::AppleVision => vec![ke::eng_apple_vision()],
            crate::engine::OcrEngine::WindowsMediaOcr => vec![ke::eng_windows_media_ocr()],
            crate::engine::OcrEngine::MlkitText => vec![ke::eng_mlkit_text()],
            _ => vec![ke::eng_tesseract()],
        };
        if p.options.ocr_engine.uses_pp() {
            engines.push(ke::eng_ppocr());
        }
        let mut langs: Vec<i32> = Vec::new();
        if let Some(l) = &p.options.locale {
            let mut c = Cells::new();
            for ch in l.chars() {
                cells_push(&mut c, ch as i32);
            }
            let lg = ks::sc_lang(&mut c);
            if lg > 0 {
                langs.push(lg * 256);
            }
        }
        let installed = packs.installed();
        let (rows, verdict) = if never {
            (Vec::new(), ka::pver_ready())
        } else {
            let (mut l, mut e, mut i) = (Cells::new(), Cells::new(), Cells::new());
            langs.iter().for_each(|v| {
                cells_push(&mut l, *v);
            });
            engines.iter().for_each(|v| {
                cells_push(&mut e, *v);
            });
            installed.iter().for_each(|v| {
                cells_push(&mut i, *v);
            });
            let (mut out, mut scratch) = (Cells::new(), Cells::new());
            let r = ke::pk_resolve(packs.class(), ke::pkf_osd(), &mut l, &mut e, &mut i, &mut out, &mut scratch);
            if r != 0 {
                let mut msg = String::new();
                ke::pk_explain(r, &mut out, &mut msg);
                return Err(msg);
            }
            let rows: Vec<Row> = (0..cells_len(&out) / 4).map(|k| Row { state: cells_at(&out, 4 * k), engine: cells_at(&out, 4 * k + 1), pack: cells_at(&out, 4 * k + 2), lang: cells_at(&out, 4 * k + 3) }).collect();
            (rows, ka::ad_verdict(&mut out, mode, off as i32))
        };
        let mut vname = String::new();
        ka::pver_emit_name(&mut vname, verdict);
        let lang_name = |l: i32| -> String { [(l >> 24) & 255, (l >> 16) & 255, (l >> 8) & 255].iter().filter(|c| **c > 0).map(|c| *c as u8 as char).collect() };
        let mut packs_json = Vec::new();
        let mut unsupported = Vec::new();
        let (mut missing, mut missing_bytes) = (0, 0u64);
        for r in &rows {
            if r.state == ke::pstate_unsupported() {
                unsupported.push(format!("{{\"engine\":{},\"language\":{}}}", js(&Engines::eng_name(r.engine)), js(&lang_name(r.lang))));
                continue;
            }
            let info = packs::pack_info(r.pack);
            let mut st = String::new();
            ke::pstate_emit_name(&mut st, r.state);
            if r.state == ke::pstate_missing() {
                missing += 1;
                missing_bytes += info.bytes;
            }
            packs_json.push(format!("{{\"id\":{},\"state\":{},\"engine\":{},\"kind\":{},\"bytes\":{},\"licence\":{},\"rev\":{}}}", js(&info.id), js(&st), js(&info.engine_name), js(&info.kind_name), info.bytes, js(&info.licence), info.rev));
        }
        let engine_names: Vec<String> = if never { Vec::new() } else { engines.iter().map(|e| js(&Engines::eng_name(*e))).collect() };
        let lang_names: Vec<String> = if never { Vec::new() } else if langs.is_empty() { vec![js("eng")] } else { langs.iter().map(|l| js(&lang_name(*l))).collect() };
        let mut m = String::new();
        ke::pfetch_emit_name(&mut m, mode);
        Ok(format!(
            "{{\"verdict\":{},\"mode\":{},\"offline\":{},\"engines\":[{}],\"languages\":[{}],\"osd\":{},\"packs\":[{}],\"unsupported\":[{}],\"missing\":{},\"missing_bytes\":{}}}",
            js(&vname), js(&m), off, engine_names.join(","), lang_names.join(","), !never, packs_json.join(","), unsupported.join(","), missing, missing_bytes
        ))
    }
}

// A JSON string.
pub fn js(s: &str) -> String {
    let mut o = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\t' => o.push_str("\\t"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

// ------------------------------------------------------------------------------------------------ the request an adapter is handed

// The fields of an `ocr` request, read by kin's names (`emon.plan`, `emon.ocrport`), for a caller that implements an adapter and does not want to learn the record (emon.h's
// `emon_ocr_request`).
#[derive(Debug, Clone, Default)]
pub struct OcrRequest {
    pub id: i32,
    pub bitmap: i32,
    // the OIL engine the request addresses (0 for a request that names none: Tesseract's)
    pub engine: i32,
    pub psm: i32,
    pub flags: i32,
    pub dpi: i32,
    // the region of the bitmap; w <= 0: the whole bitmap
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
    // the languages (up to four ASCII letters each) and scripts (ISO 15924) the request names
    pub langs: Vec<String>,
}

fn cells_of(v: &[i32]) -> Cells {
    let mut c = Cells::new();
    for x in v {
        cells_push(&mut c, *x);
    }
    c
}

fn packed_text(p: i32) -> String {
    [(p >> 24) & 255, (p >> 16) & 255, (p >> 8) & 255, p & 255].iter().filter(|c| **c > 0).map(|c| *c as u8 as char).collect()
}

pub fn parse_request(rec: &[i32]) -> Option<OcrRequest> {
    if rec.len() < 14 || rec[0] as usize != rec.len() || rec[1] != KIND_OCR {
        return None;
    }
    let req = Request::new(rec.to_vec());
    let mut c = cells_of(rec);
    let mut langs = Vec::new();
    let first = kop::op_lang_first(&mut c);
    let n = kop::op_lang_count(&mut c);
    for i in 0..n {
        if first >= 0 {
            langs.push(packed_text(kop::op_lang_at(&mut c, i)));
        }
    }
    let flags = req.field(crate::emon::plan::oc_flags());
    Some(OcrRequest {
        id: req.id(),
        bitmap: req.field(crate::emon::plan::oc_bitmap()),
        engine: kop::op_request_engine(&mut c),
        psm: req.field(crate::emon::plan::oc_psm()),
        flags,
        dpi: kr::ocf_dpi(flags),
        x: req.field(crate::emon::plan::oc_x()),
        y: req.field(crate::emon::plan::oc_y()),
        w: req.field(crate::emon::plan::oc_w()),
        h: req.field(crate::emon::plan::oc_h()),
        langs,
    })
}

// ------------------------------------------------------------------------------------------------ routing and reading

// What `begin` did with a request.
pub enum Begun {
    Done(Answer),
    // an async adapter has it: the answer arrives in the mailbox under this token
    Pending(u64),
}

#[derive(Clone)]
pub struct Router {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for Router {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        f.write_str("Router")
    }
}

fn refusal(id: i32, status: c_int) -> Answer {
    let mut c = Cells::new();
    ka::ad_refusal(&mut c, id, status);
    Answer { rec: (0..cells_len(&c)).map(|i| cells_at(&c, i)).collect(), bitmap: None }
}

fn to_plan_cells(v: &[i32]) -> Cells {
    cells_of(v)
}

impl Router {
    pub fn timeout(&self) -> Duration {
        Duration::from_millis(self.inner.timeout_ms.load(Ordering::SeqCst).max(1) as u64)
    }

    fn registered_oens(&self) -> (Vec<Arc<Adapter>>, Cells) {
        let v = self.inner.entries.read().unwrap_or_else(|e| e.into_inner()).clone();
        let mut c = Cells::new();
        for e in &v {
            cells_push(&mut c, e.oen());
        }
        (v, c)
    }

    // Which adapter, if any, is the request's (kin's `ad-route`): the first registered for the engine it addresses.
    fn route(&self, req: &Request) -> Option<Arc<Adapter>> {
        let (v, mut oens) = self.registered_oens();
        let mut c = cells_of(&req.rec);
        let at = ka::ad_route(kop::op_request_engine(&mut c), &mut oens, 0);
        if at >= 0 {
            v.get(at as usize).cloned()
        } else {
            None
        }
    }

    // Is this request one an adapter answers? The session asks it to decide which `ocr` requests are the caller's (kin's `ad-route` with the caller owing the rest).
    pub fn claims(&self, req: &Request) -> bool {
        req.kind() == KIND_OCR && self.route(req).is_some()
    }

    // The transcript's `:producer` of whatever answers: the first adapter's pairs, the others' after them, and `ocr-engine` naming all of them (`tesseract+ppocr`).
    pub fn producer_pairs(&self) -> Vec<(String, String)> {
        let v = self.inner.entries.read().unwrap_or_else(|e| e.into_inner());
        let mut out: Vec<(String, String)> = Vec::new();
        for e in v.iter() {
            for (k, val) in &e.info.producer {
                if !out.iter().any(|(k2, v2)| k2 == k && v2 == val) {
                    out.push((k.clone(), val.clone()));
                }
            }
        }
        if v.len() > 1 {
            let names: Vec<String> = v.iter().map(|e| e.name.clone()).collect();
            out.push(("ocr-engine".to_string(), names.join("+")));
        }
        out
    }

    fn languages_of(&self, entry: &Adapter, req: &Request) -> Vec<i32> {
        // Tesseract's requests name languages; PP-OCR has one recognizer (English) and its requests' layout differs (a view's hash precedes the languages)
        if entry.oen() != 1 {
            return Vec::new();
        }
        let mut c = cells_of(&req.rec);
        let n = kop::op_lang_count(&mut c);
        let mut v = Vec::new();
        for i in 0..n {
            let l = kop::op_lang_at(&mut c, i);
            // a script (ISO 15924) is not a language: no pack is named for it
            if l > 0 && kop::op_is_script(l) == 0 {
                v.push(l);
            }
        }
        v
    }

    // The packs a request needs, made present or refused (D896). `Ok(None)`: go on with this adapter. `Ok(Some(e))`: the app named another adapter of the same engine. `Err`: the
    // page is unread, with the reason.
    fn ensure_packs(&self, entry: &Arc<Adapter>, req: &Request) -> Result<Option<Arc<Adapter>>, String> {
        if entry.info.needs == 0 {
            return Ok(None);
        }
        let packs = &self.inner.packs;
        let eng = ka::ad_oen_eng(entry.oen());
        if eng < 0 {
            return Ok(None);
        }
        let flags = req.field(crate::emon::plan::oc_flags());
        let osd = flags & kr::ocf_osd() != 0 && entry.info.needs & 2 != 0;
        let langs = self.languages_of(entry, req);
        let rows = packs.resolve(&[eng], &langs, osd).map_err(|e| format!("engine-unavailable: {}", e))?;
        // a language no pack of the catalogue reads is not read; when that is every language the request asked for, the page is unread
        let unreadable: Vec<&Row> = rows.iter().filter(|r| r.state == ke::pstate_unsupported()).collect();
        if !langs.is_empty() && unreadable.len() >= langs.len() {
            return Err(format!("lang-unavailable:{}", packed_text(unreadable[0].lang)));
        }
        let mut engine_name = String::new();
        ke::eng_emit_name(&mut engine_name, eng);
        let mut arrived = false;
        for r in rows.iter().filter(|r| r.state == ke::pstate_missing()) {
            let kind = ke::pk_pack_kind(r.pack);
            match packs.need(r.pack, "conversion", &engine_name) {
                // a pack that came (fetched, or the app's) is not in the adapter's directory yet: that is rebuilt below, before it reads (K7)
                Need::Ready => arrived = true,
                // orientation detection degrades to kin's own policy without its model; it never leaves the page unread
                Need::Unread(_) if kind == ke::pkind_osd() => {}
                Need::Unread(why) => return Err(why),
                Need::Substitute(other) => {
                    let e2 = ke::eng_find(other.as_bytes(), 0, other.len() as i32);
                    if e2 < 0 || ke::pk_engine_oen(e2) != entry.oen() {
                        // another engine would answer under its own name: kin's plan chose this engine, so the request stays unread
                        return Err(format!("pack-substitute-differs:{}", other));
                    }
                    let (v, _) = self.registered_oens();
                    return match v.into_iter().find(|a| a.oen() == entry.oen() && !Arc::ptr_eq(a, entry)) {
                        Some(a) => Ok(Some(a)),
                        None => Err(format!("pack-substitute-unavailable:{}", other)),
                    };
                }
            }
        }
        if arrived {
            // the adapter lists its pack directory for each request, so what it is given must hold what arrived (it was built when the adapter was registered)
            packs.view(&engine_name).map_err(|m| format!("pack-dir-missing: {}", m))?;
        }
        Ok(None)
    }

    fn refused(&self, entry: &Adapter, id: i32, status: c_int, reason: &str) -> Answer {
        self.inner.note(format!("{}: {} {}", entry.name, if status == ADAPTER_UNSUPPORTED { "unsupported" } else { "failed" }, reason));
        refusal(id, status)
    }

    // What an adapter gave back, held to what it registered (kin's `ad-answer-check`), or the refusal it is.
    fn settle(&self, meta: &Meta, r: Result<(Vec<i32>, Option<(i32, i32, Vec<u8>)>), (c_int, String)>) -> Result<Answer, PlanError> {
        let (rec, binary) = match r {
            Ok(v) => v,
            Err((status, reason)) => return Ok(self.refused(&meta.entry, meta.id, status, &reason)),
        };
        let bad = |why: String| -> Result<Answer, PlanError> { Err(PlanError::Fulfil(format!("ocr-adapter-invalid: {}: {}", meta.entry.name, why))) };
        if rec.len() < 4 || rec[0] as usize != rec.len() || rec[1] != KIND_OCR || rec[2] != meta.id {
            return bad("the answer is not a record of the request's `ocr` answer".to_string());
        }
        if rec[3] != 0 {
            // a record that only says how it went: the adapter did not read
            return if rec.len() == 4 { Ok(self.refused(&meta.entry, meta.id, if rec[3] == crate::emon::plan::ps_unsupported() { ADAPTER_UNSUPPORTED } else { ADAPTER_FAILED }, "answered")) } else { bad("a status that is not ok on a record with a reading".to_string()) };
        }
        let mut c = to_plan_cells(&rec);
        if ko::oil_record_check_bitmap(&mut c, 0, meta.width, meta.height, &mut Cells::new()) != 0 {
            return bad("the reading is not valid OIL for this bitmap".to_string());
        }
        let (ans_oen, ans_caps) = (ko::oil_get(&mut c, 0, ko::oh_engine()), ko::oil_get(&mut c, 0, ko::oh_caps()));
        let check = ka::ad_answer_check(meta.entry.oen(), meta.entry.info.caps as i32, meta.oen, ans_oen, ans_caps);
        if check != ka::ada_ok() {
            let mut why = String::new();
            ka::ada_emit_name(&mut why, check);
            return bad(why);
        }
        let bitmap = binary.and_then(|(w, h, px)| if px.len() == w as usize * h as usize { Some(Arc::new(Bitmap::from_bytes(w, h, 1, &px))) } else { None });
        Ok(Answer { rec, bitmap })
    }

    pub fn finish(&self, meta: Meta, c: Completion) -> Result<Answer, PlanError> {
        match c {
            Completion::Answer(rec) => self.settle(&meta, Ok((rec, None))),
            Completion::Refused { status, reason } => self.settle(&meta, Err((status, reason))),
        }
    }

    // Begin one `ocr` request: the adapter of its engine reads it now (sync: `Done`) or takes it (async: `Pending`, the answer comes to `mailbox`). `keep_binary`: the conversion
    // records, which keeps the engine's thresholded image.
    pub fn begin(&self, req: &Request, bitmaps: &Bitmaps, keep_binary: bool, mailbox: &Arc<Mailbox>) -> Result<Begun, PlanError> {
        let id = req.id();
        let Some(mut entry) = self.route(req) else { return Ok(Begun::Done(refusal(id, ADAPTER_UNSUPPORTED))) };
        let Some(bm) = bitmaps.get(&req.field(crate::emon::plan::oc_bitmap())).cloned() else {
            return Ok(Begun::Done(self.refused(&entry, id, ADAPTER_FAILED, "no-bitmap")));
        };
        if bm.channels() != 1 && bm.channels() != 3 {
            return Ok(Begun::Done(self.refused(&entry, id, ADAPTER_UNSUPPORTED, "channels")));
        }
        for _ in 0..2 {
            match self.ensure_packs(&entry, req) {
                Ok(None) => break,
                Ok(Some(other)) => entry = other,
                Err(why) => return Ok(Begun::Done(self.refused(&entry, id, ADAPTER_UNSUPPORTED, &why))),
            }
        }
        if let Err((reason, _)) = unsafe { entry.supports(&req.rec) } {
            return Ok(Begun::Done(self.refused(&entry, id, ADAPTER_UNSUPPORTED, &reason)));
        }
        let mut c = cells_of(&req.rec);
        let oen = kop::op_request_engine(&mut c);
        let px = bm.to_bytes();
        let flags = req.field(crate::emon::plan::oc_flags());
        let image = AdapterImage { width: bm.width(), height: bm.height(), channels: bm.channels(), stride: bm.width() * bm.channels(), pixels: px.as_ptr(), dpi: kr::ocf_dpi(flags) };
        let meta = Meta { entry: entry.clone(), id, oen, width: bm.width(), height: bm.height() };
        if entry.is_async() {
            let token = self.inner.next_token.fetch_add(1, Ordering::SeqCst);
            self.inner.pending.lock().unwrap_or_else(|e| e.into_inner()).insert(token, Pending { mailbox: mailbox.clone(), meta });
            return match unsafe { entry.start(&req.rec, &image, token) } {
                Ok(()) => Ok(Begun::Pending(token)),
                Err((status, reason)) => {
                    self.inner.pending.lock().unwrap_or_else(|e| e.into_inner()).remove(&token);
                    Ok(Begun::Done(self.refused(&entry, id, status, &reason)))
                }
            };
        }
        // an adapter that panics is a failed read, not a crashed process
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| unsafe { entry.read(&req.rec, &image, keep_binary) }));
        let r = r.unwrap_or_else(|_| Err((ADAPTER_FAILED, "panic".to_string())));
        Ok(Begun::Done(self.settle(&meta, r)?))
    }

    // Stop waiting for a token (the session closed or failed).
    pub fn cancel(&self, token: u64) {
        self.inner.cancel(token);
    }

    // An answer for a token that a CALLBACK adapter's program gives later (`emon_complete`).
    pub fn complete(&self, token: u64, c: Completion) -> bool {
        self.inner.complete(token, c)
    }

    // Is the token one of this registry's still waited for?
    pub fn waiting(&self, token: u64) -> bool {
        self.inner.pending.lock().unwrap_or_else(|e| e.into_inner()).contains_key(&token)
    }

    // Read one request and wait for the answer: the one-shot calls' way.
    pub fn read_blocking(&self, req: &Request, bitmaps: &Bitmaps, keep_binary: bool) -> Result<Answer, PlanError> {
        let mailbox = Mailbox::new();
        match self.begin(req, bitmaps, keep_binary, &mailbox)? {
            Begun::Done(a) => Ok(a),
            Begun::Pending(token) => {
                let deadline = Instant::now() + self.timeout();
                loop {
                    let left = deadline.saturating_duration_since(Instant::now());
                    if mailbox.wait(left) {
                        for (t, meta, c) in mailbox.take() {
                            if t == token {
                                return self.finish(meta, c);
                            }
                        }
                    }
                    if Instant::now() >= deadline {
                        self.cancel(token);
                        let entry = self.route(req);
                        self.inner.note(format!("{}: failed timeout", entry.map(|e| e.name.clone()).unwrap_or_default()));
                        return Ok(refusal(req.id(), ADAPTER_FAILED));
                    }
                }
            }
        }
    }
}

impl Ocr for Router {
    fn fulfil(&self, req: &Request, bitmaps: &Bitmaps, keep_binary: bool) -> Result<Answer, PlanError> {
        self.read_blocking(req, bitmaps, keep_binary)
    }

    fn producer(&self) -> Vec<(String, String)> {
        self.producer_pairs()
    }
}

// ------------------------------------------------------------------------------------------------ an in-process `Ocr` as an adapter
//
// The adapter libraries of this repository (`tess_adapter.rs`: libemon_ocr_tesseract; `ppocr_abi.rs`: libemon_ppocr_adapter) are Rust `Ocr` ports under the C ABI, and the
// CLI and the pod, which link Tesseract statically, register their port in the same registry (`Engines::add_port`) when they need to compose it with another (PP-OCR's hybrid).
// One set of trampolines does both: the request's record becomes a `Request`, the pixels the bitmap it names, and the `Answer` comes back as cells.

pub struct PortInfo {
    pub name: String,
    pub engine: u32,
    pub caps: u32,
    pub measured: u32,
    pub needs: u32,
    pub flags: u32,
    pub config: String,
    pub observed: String,
    pub producer: Vec<(String, String)>,
}

impl PortInfo {
    // Tesseract as `tess.rs` answers: every capability kin's table lets the engine declare, a recognizer and the orientation model.
    pub fn tesseract(producer: Vec<(String, String)>) -> PortInfo {
        let caps = ke::pk_engine_caps(ke::eng_tesseract()) as u32;
        let observed = producer.iter().find(|(k, _)| k == "observed").map(|(_, v)| v.clone()).unwrap_or_default();
        PortInfo {
            name: "tesseract".to_string(),
            engine: ko::oen_tesseract() as u32,
            caps,
            measured: caps,
            needs: (ke::pkind_recognizer() | ke::pkind_osd()) as u32,
            flags: ADAPTER_THREADSAFE,
            config: "tesseract;oem=Init3-default;choice=2".to_string(),
            observed,
            producer,
        }
    }
}

// The vtable is the FIRST field of the context, so a pointer to the context is a pointer to the adapter: `emon_ocr_adapter_v1` returns it and one `close` frees both.
#[repr(C)]
struct PortCtx {
    vt: OcrAdapter,
    engine: u32,
    caps: u32,
    measured: u32,
    needs: u32,
    flags: u32,
    ocr: Arc<dyn Ocr>,
    name: CString,
    config: CString,
    observed: CString,
    producer: CString,
}

struct Owned {
    cells: Vec<i32>,
    binary: Vec<u8>,
}

unsafe extern "C" fn port_describe(ctx: *mut c_void, out: *mut AdapterInfo, _st: *mut AdapterStatus) -> c_int {
    let c = &*(ctx as *const PortCtx);
    (*out).engine = c.engine;
    (*out).caps = c.caps;
    (*out).native = c.measured;
    (*out).needs = c.needs;
    (*out).flags = c.flags;
    (*out).config = c.config.as_ptr();
    (*out).observed = c.observed.as_ptr();
    (*out).producer = c.producer.as_ptr();
    (*out).langs = std::ptr::null();
    (*out).nlangs = 0;
    ADAPTER_OK
}

unsafe extern "C" fn port_read(ctx: *mut c_void, req: *const i32, n: usize, image: *const AdapterImage, want_binary: c_int, out: *mut AdapterAnswer, st: *mut AdapterStatus) -> c_int {
    let c = &*(ctx as *const PortCtx);
    let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let img = &*image;
        let rec = std::slice::from_raw_parts(req, n).to_vec();
        let request = Request::new(rec);
        if img.width <= 0 || img.height <= 0 || (img.channels != 1 && img.channels != 3) || img.pixels.is_null() {
            return Err((ADAPTER_FAILED, "bad-image".to_string(), "the image is not a 1 or 3 channel bitmap".to_string()));
        }
        let (w, h, ch, stride) = (img.width as usize, img.height as usize, img.channels as usize, img.stride as usize);
        let mut px = Vec::with_capacity(w * h * ch);
        for y in 0..h {
            px.extend_from_slice(std::slice::from_raw_parts(img.pixels.add(y * stride), w * ch));
        }
        let mut bm = Bitmaps::new();
        bm.insert(request.field(crate::emon::plan::oc_bitmap()), Arc::new(Bitmap::from_bytes(img.width, img.height, img.channels, &px)));
        match c.ocr.fulfil(&request, &bm, want_binary != 0) {
            Ok(a) => {
                if a.rec.len() >= 4 && a.rec[3] != 0 {
                    // the port answered that it could not read
                    let status = if a.rec[3] == crate::emon::plan::ps_unsupported() { ADAPTER_UNSUPPORTED } else { ADAPTER_FAILED };
                    return Err((status, "answered".to_string(), String::new()));
                }
                let binary = match (want_binary != 0, &a.bitmap) {
                    (true, Some(b)) if b.channels() == 1 => (b.width(), b.height(), b.to_bytes()),
                    _ => (0, 0, Vec::new()),
                };
                Ok((a.rec, binary))
            }
            Err(e) => {
                let m = e.to_string();
                Err((ADAPTER_FAILED, m.split(':').next().unwrap_or("failed").trim().to_string(), m))
            }
        }
    }));
    match r {
        Ok(Ok((rec, (bw, bh, bin)))) => {
            let owned = Box::new(Owned { cells: rec, binary: bin });
            (*out).cells = owned.cells.as_ptr() as *mut i32;
            (*out).ncells = owned.cells.len();
            (*out).binary = if owned.binary.is_empty() { std::ptr::null() } else { owned.binary.as_ptr() };
            (*out).binary_width = bw;
            (*out).binary_height = bh;
            (*out).owner = Box::into_raw(owned) as *mut c_void;
            ADAPTER_OK
        }
        Ok(Err((status, reason, message))) => {
            (*st).set(&reason, &message);
            status
        }
        Err(_) => {
            (*st).set("panic", "the port panicked");
            ADAPTER_FAILED
        }
    }
}

unsafe extern "C" fn port_release(_ctx: *mut c_void, a: *mut AdapterAnswer) {
    if !(*a).owner.is_null() {
        drop(Box::from_raw((*a).owner as *mut Owned));
        (*a).owner = std::ptr::null_mut();
        (*a).cells = std::ptr::null_mut();
        (*a).ncells = 0;
        (*a).binary = std::ptr::null();
    }
}

unsafe extern "C" fn port_close(ctx: *mut c_void) {
    if !ctx.is_null() {
        drop(Box::from_raw(ctx as *mut PortCtx));
    }
}

// Wrap an in-process port as the adapter ABI's struct. The result is the adapter, to hand over (an adapter library returns it from `emon_ocr_adapter_v1`; `Engines::add_port`
// takes it): its own `close` frees it.
pub fn port_adapter(info: PortInfo, ocr: Arc<dyn Ocr>) -> *const OcrAdapter {
    let lines: String = info.producer.iter().map(|(k, v)| format!("{}={}\n", k, v.replace('\n', " "))).collect();
    let cs = |s: &str| CString::new(s.replace('\0', "")).unwrap_or_default();
    let ctx = Box::new(PortCtx {
        vt: OcrAdapter {
            abi: ADAPTER_ABI,
            size: std::mem::size_of::<OcrAdapter>() as u32,
            name: std::ptr::null(),
            ctx: std::ptr::null_mut(),
            describe: Some(port_describe),
            supports: None,
            read: Some(port_read),
            start: None,
            cancel: None,
            release: Some(port_release),
            bind: None,
            close: Some(port_close),
        },
        engine: info.engine,
        caps: info.caps,
        measured: info.measured,
        needs: info.needs,
        flags: info.flags,
        ocr,
        name: cs(&info.name),
        config: cs(&info.config),
        observed: cs(&info.observed),
        producer: cs(&lines),
    });
    let p = Box::into_raw(ctx);
    unsafe {
        (*p).vt.name = (*p).name.as_ptr();
        (*p).vt.ctx = p as *mut c_void;
    }
    p as *const OcrAdapter
}

impl Engines {
    // An in-process port (a `TessOcr` the CLI links statically) as an adapter of this registry.
    pub fn add_port(&self, info: PortInfo, ocr: Arc<dyn Ocr>) -> Result<usize, RegError> {
        let p = port_adapter(info, ocr);
        self.add_adapter(unsafe { *p })
    }
}

// PP-OCR's hybrid (`ocr-engine=ppocr`) for a host that links Tesseract itself: the host's port is registered as Tesseract, the PP adapter library is loaded (`$EMON_PPOCR_LIB`, else
// `.cache/ppocr/libemon_ppocr_adapter.<ext>`, as the shared adapter was always found) with the models in `$EMON_PPOCR_MODELS` (else `.cache/ppocr`), and the router of the two is
// the port. Needs the adapter library (a PP archive, `bb ppocr-addon`).
pub fn ppocr_hybrid(incumbent: Option<crate::engine::OcrPort>) -> Result<crate::engine::OcrPort, String> {
    let e = Engines::new();
    let models = std::env::var("EMON_PPOCR_MODELS").unwrap_or_else(|_| ".cache/ppocr".to_string());
    e.packs().add_dir(std::path::Path::new(&models));
    if let Some(t) = incumbent {
        let producer = t.0.producer();
        e.add_port(PortInfo::tesseract(producer), t.0).map_err(|r| format!("{}: {}", r.name, r.message))?;
    }
    let ext = if cfg!(target_os = "macos") { "dylib" } else if cfg!(windows) { "dll" } else { "so" };
    let lib = std::env::var("EMON_PPOCR_LIB").unwrap_or_else(|_| format!(".cache/ppocr/{}emon_ppocr_adapter.{}", if cfg!(windows) { "" } else { "lib" }, ext));
    e.load(&lib, None).map_err(|r| format!("{}: {}", r.name, r.message))?;
    let router = e.router().ok_or_else(|| "no adapter".to_string())?;
    Ok(crate::engine::OcrPort(Arc::new(router)))
}
