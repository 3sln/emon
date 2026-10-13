// THE C BINDING (DEVLOG P9.4, D001, D471-D475, SPEC 13, 13.1). The Rust host given a stable C ABI: `host/c/include/emon.h` is its
// header and its documentation; this file is the other side of it. It is assembled into the crate beside the host files by
// `kin/scripts/emon-bind-build` (it is not part of the native crate, which has no C ABI), as a `cdylib`, a `staticlib` and the
// `rlib` the babashka pod links.
//
// TWO WAYS IN, the two the Rust host has:
//
//   ONE-SHOT.   `emon_convert` / `emon_intake` take an `emon_input`: bytes (copied) or a LAZY `emon_source` (a length and a
//               `read_at` callback, SPEC 13.1: the engine reads the spans it touches, in chunks, as it goes). The loop runs inside the
//               call, with the Rust host's own ports when the library was built with them and the caller asked (`EMON_NATIVE`:
//               pdfium renders, Tesseract reads, OpenJPEG/jbig2dec decode).
//   INVERTED.   `emon_open` .. `emon_step` .. `emon_finish`: the shape of the browser binding (`host/wasm/src/wasm.rs`'s; the
//               session logic is ONE module both compile, `host/rust/src/session.rs`, D481), for a caller that owns the byte source and the fulfilment. The
//               engine never calls out: `emon_step` runs kin until it needs something of the caller and says what (bytes it has
//               not been given: ranges; or requests it owes: `ocr`), the caller does it and steps again. The unit that missed is
//               re-run from scratch inside `emon_step` (D058).
//
// MEMORY IS EXPLICIT. Everything emon hands out it allocated (`emon_buf`, the strings of `emon_error` and `emon_asset_info`, the arrays
// of `emon_step_out` and `emon_files`) and the caller gives back with the matching `emon_free_*`; a pointer the caller gave emon is
// borrowed for the call only (bytes of an `emon_input` are copied; `emon_provide` copies). An `emon_session` and an `emon_model`
// are freed by `emon_close` and `emon_free_model`. A handle is used by one thread at a time.
//
// PANICS DO NOT CROSS. Every entry point is `catch_unwind`ed: a panic that is not kin's (kin's are caught inside the loop and named)
// is the error `internal`, with its message. The engine works on a worker thread with a 32 MiB stack (kin recurses; a C caller's
// thread may have 512 KiB), so the caller's stack size is nothing the answer depends on.
//
// ERRORS: a function answers `0` (or a positive status, `emon_step`) or `-code` and fills the `emon_error` it was given (NULL: not
// wanted). `code` is the CATEGORY (D040's: one per kind of `Error`), `name` is the stable name a crawler routes on (kin's: a PDF
// status such as `bad-xref`, a limit's `pages`, a plan error's `transcript-miss`), `message` the sentence.
#![allow(clippy::missing_safety_doc, non_camel_case_types)]

use crate::engine::{parse_options, Config, Engine, Error, HtmlOptions, JsonOptions, Model, Options, Parsed, RunStats};
use crate::session as shared;
// the session's failure (the engine's error, or the caller's mistake) is this ABI's `Fail` too
use crate::session::{Fault as Fail, Session, Step};
use crate::source::{ByteSource, Input};
use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};

// ------------------------------------------------------------------------------------------------ the shapes of the header

#[repr(C)]
pub struct emon_buf {
    pub data: *mut u8,
    pub len: usize,
}

#[repr(C)]
pub struct emon_error {
    pub code: c_int,
    pub name: *mut c_char,
    pub message: *mut c_char,
}

#[repr(C)]
pub struct emon_range {
    pub offset: u64,
    pub length: u64,
}

#[repr(C)]
pub struct emon_request {
    pub kind: i32,
    pub id: i32,
    pub cells: *const i32,
    pub ncells: usize,
}

#[repr(C)]
pub struct emon_step_out {
    pub ranges: *mut emon_range,
    pub nranges: usize,
    pub requests: *mut emon_request,
    pub nrequests: usize,
    pub cells: *mut i32,
    pub ncells: usize,
}

#[repr(C)]
pub struct emon_file {
    pub name: *mut c_char,
    pub data: *mut u8,
    pub len: usize,
}

#[repr(C)]
pub struct emon_files {
    pub files: *mut emon_file,
    pub n: usize,
}

#[repr(C)]
pub struct emon_asset_info {
    pub id: i32,
    pub name: *mut c_char,
    pub media_type: *mut c_char,
    pub origin: *mut c_char,
    pub sha256: *mut c_char,
    pub width: i32,
    pub height: i32,
    pub size: usize,
}

#[repr(C)]
pub struct emon_stats {
    pub attempts: i32,
    pub fetch_rounds: i32,
    pub bytes_requests: i32,
    pub requests: i32,
    pub source_reads: u32,
    pub bytes_fetched: u64,
    pub input_bytes: u64,
}

pub type emon_read_fn = Option<unsafe extern "C" fn(ctx: *mut c_void, offset: u64, buf: *mut u8, len: usize) -> i64>;

#[repr(C)]
pub struct emon_source {
    pub length: u64,
    pub read_at: emon_read_fn,
    pub ctx: *mut c_void,
}

#[repr(C)]
pub struct emon_input {
    pub data: *const u8,
    pub len: usize,
    pub source: *const emon_source,
    pub password: *const u8,
    pub password_len: usize,
}

// The handles are opaque to C.
#[repr(C)]
pub struct emon_session {
    _private: [u8; 0],
}

#[repr(C)]
pub struct emon_model {
    _private: [u8; 0],
}

// What `emon_convert` and `emon_open` take in `flags`.
pub const EMON_NATIVE: u32 = 1;
pub const EMON_OPEN_INTAKE: u32 = 1;

// The categories (`emon_error.code`), one per kind of the Rust host's `Error` (D040), and `usage` for what the caller got wrong.
const E_USAGE: c_int = 1;
const E_NO_INPUT: c_int = 2;
const E_LIMIT: c_int = 3;
const E_PASSWORD_REQUIRED: c_int = 4;
const E_PASSWORD_INCORRECT: c_int = 5;
const E_ENCRYPTION_UNSUPPORTED: c_int = 6;
const E_UNSUPPORTED_FORMAT: c_int = 7;
const E_DAMAGED: c_int = 8;
const E_SOURCE_CHANGED: c_int = 9;
const E_IO: c_int = 10;
const E_UNSUPPORTED: c_int = 11;
const E_PLAN: c_int = 12;
const E_INTERNAL: c_int = 13;

// ------------------------------------------------------------------------------------------------ failures, memory, the guard

// What an entry point can fail with -- `Fail::Engine(Error)`, the engine's own error, or `Fail::Usage(String)`, the caller's mistake --
// is `session::Fault`, the session's (imported above as `Fail`).

// The category of an error (`emon_error.code`): the pod (`host/pod`), which is a binary over this same library, reports the same numbers.
pub fn category_code(e: &Error) -> c_int {
    match e {
        Error::NoInput => E_NO_INPUT,
        Error::Limit { .. } => E_LIMIT,
        Error::PasswordRequired { .. } => E_PASSWORD_REQUIRED,
        Error::PasswordIncorrect { .. } => E_PASSWORD_INCORRECT,
        Error::EncryptionUnsupported { .. } => E_ENCRYPTION_UNSUPPORTED,
        Error::UnsupportedFormat { .. } => E_UNSUPPORTED_FORMAT,
        Error::Damaged { .. } => E_DAMAGED,
        Error::SourceChanged { .. } => E_SOURCE_CHANGED,
        Error::Io { .. } => E_IO,
        Error::Unsupported(_) => E_UNSUPPORTED,
        Error::Plan(_) => E_PLAN,
        Error::Internal(_) => E_INTERNAL,
    }
}

fn cstring(s: &str) -> *mut c_char {
    // an interior NUL cannot be in a C string: it is cut there
    let clean: Vec<u8> = s.bytes().take_while(|b| *b != 0).collect();
    CString::new(clean).expect("no NUL left").into_raw()
}

unsafe fn free_cstring(p: *mut c_char) {
    if !p.is_null() {
        drop(CString::from_raw(p));
    }
}

// A Vec as a raw array the caller can read, and back. An empty one is NULL.
fn leak<T>(v: Vec<T>) -> (*mut T, usize) {
    if v.is_empty() {
        return (std::ptr::null_mut(), 0);
    }
    let b = v.into_boxed_slice();
    let n = b.len();
    (Box::into_raw(b) as *mut T, n)
}

unsafe fn unleak<T>(p: *mut T, n: usize) {
    if !p.is_null() {
        drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(p, n)));
    }
}

// Bytes as an `emon_buf`: `len` bytes and one NUL after them (so a JSON or HTML result is a C string too).
fn buf_of(mut v: Vec<u8>) -> emon_buf {
    let n = v.len();
    v.push(0);
    let (p, _) = leak(v);
    emon_buf { data: p, len: n }
}

fn fill(err: *mut emon_error, f: Fail) -> c_int {
    let (code, name, msg) = match &f {
        Fail::Engine(e) => (category_code(e), e.name(), e.to_string()),
        Fail::Usage(m) => (E_USAGE, "usage".to_string(), m.clone()),
    };
    if !err.is_null() {
        unsafe {
            *err = emon_error { code, name: cstring(&name), message: cstring(&msg) };
        }
    }
    -code
}

// Every entry point: the error is cleared, a panic is `internal`, a Fail fills it. (`*err` is overwritten, not freed: a caller reuses an
// `emon_error` after `emon_free_error`, or starts it zeroed.)
unsafe fn guarded(err: *mut emon_error, f: impl FnOnce() -> Result<c_int, Fail>) -> c_int {
    if !err.is_null() {
        *err = emon_error { code: 0, name: std::ptr::null_mut(), message: std::ptr::null_mut() };
    }
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(v)) => v,
        Ok(Err(f)) => fill(err, f),
        Err(p) => fill(err, Fail::Engine(Error::Internal(shared::panic_message(p)))),
    }
}

// The engine's stack: kin recurses and a C caller's thread may be small. A panic on the worker is re-raised here, into `guarded`.
const WORKER_STACK: usize = 32 << 20;

struct AssertSend<T>(T);
unsafe impl<T> Send for AssertSend<T> {}

impl<T> AssertSend<T> {
    fn into_inner(self) -> T {
        self.0
    }
}

fn on_worker<T>(f: impl FnOnce() -> T) -> T {
    let f = AssertSend(f);
    std::thread::scope(|sc| {
        let h = std::thread::Builder::new()
            .name("emon".to_string())
            .stack_size(WORKER_STACK)
            .spawn_scoped(sc, move || AssertSend(f.into_inner()()))
            .unwrap_or_else(|e| panic!("emon could not start its worker thread: {}", e));
        match h.join() {
            Ok(r) => r.into_inner(),
            Err(p) => std::panic::resume_unwind(p),
        }
    })
}

unsafe fn bytes<'a>(ptr: *const u8, len: usize) -> &'a [u8] {
    if len == 0 || ptr.is_null() {
        return &[];
    }
    std::slice::from_raw_parts(ptr, len)
}

unsafe fn text(p: *const c_char) -> Result<String, Fail> {
    if p.is_null() {
        return Ok(String::new());
    }
    CStr::from_ptr(p).to_str().map(|s| s.to_string()).map_err(|_| Fail::Usage("a text argument is not UTF-8".to_string()))
}

unsafe fn out_buf(out: *mut emon_buf, b: emon_buf) -> Result<(), Fail> {
    if out.is_null() {
        unleak(b.data, b.len + 1);
        return Err(Fail::Usage("an output pointer is NULL".to_string()));
    }
    *out = b;
    Ok(())
}

// ------------------------------------------------------------------------------------------------ freeing

#[no_mangle]
pub unsafe extern "C" fn emon_free_buf(b: *mut emon_buf) {
    if let Some(b) = b.as_mut() {
        unleak(b.data, b.len + 1);
        b.data = std::ptr::null_mut();
        b.len = 0;
    }
}

#[no_mangle]
pub unsafe extern "C" fn emon_free_error(e: *mut emon_error) {
    if let Some(e) = e.as_mut() {
        free_cstring(e.name);
        free_cstring(e.message);
        *e = emon_error { code: 0, name: std::ptr::null_mut(), message: std::ptr::null_mut() };
    }
}

#[no_mangle]
pub unsafe extern "C" fn emon_free_step(s: *mut emon_step_out) {
    if let Some(s) = s.as_mut() {
        unleak(s.ranges, s.nranges);
        unleak(s.requests, s.nrequests);
        unleak(s.cells, s.ncells);
        *s = emon_step_out { ranges: std::ptr::null_mut(), nranges: 0, requests: std::ptr::null_mut(), nrequests: 0, cells: std::ptr::null_mut(), ncells: 0 };
    }
}

#[no_mangle]
pub unsafe extern "C" fn emon_free_files(f: *mut emon_files) {
    if let Some(f) = f.as_mut() {
        if !f.files.is_null() {
            let files = Box::from_raw(std::ptr::slice_from_raw_parts_mut(f.files, f.n));
            for x in files.iter() {
                free_cstring(x.name);
                unleak(x.data, x.len + 1);
            }
        }
        *f = emon_files { files: std::ptr::null_mut(), n: 0 };
    }
}

#[no_mangle]
pub unsafe extern "C" fn emon_free_asset_info(a: *mut emon_asset_info) {
    if let Some(a) = a.as_mut() {
        free_cstring(a.name);
        free_cstring(a.media_type);
        free_cstring(a.origin);
        free_cstring(a.sha256);
        *a = emon_asset_info { id: 0, name: std::ptr::null_mut(), media_type: std::ptr::null_mut(), origin: std::ptr::null_mut(), sha256: std::ptr::null_mut(), width: 0, height: 0, size: 0 };
    }
}

// ------------------------------------------------------------------------------------------------ what the library is

static VERSION: &[u8] = b"0.1.0\0";

#[no_mangle]
pub extern "C" fn emon_version() -> *const c_char {
    VERSION.as_ptr() as *const c_char
}

// The native ports this build has, as a bit set: 1 pdfium (render, crop), 2 Tesseract (ocr), 4 OpenJPEG and jbig2dec (decode-img).
#[no_mangle]
pub extern "C" fn emon_capabilities() -> u32 {
    let mut c = 0;
    if cfg!(pdfium) {
        c |= 1;
    }
    if cfg!(feature = "tesseract") {
        c |= 2;
    }
    if cfg!(codecs) {
        c |= 4;
    }
    c
}

// The numbers of the plan/fulfil protocol, kin's (the same table as the browser's `emon_const`):
// 0 kind render, 1 decode, 2 ocr, 3 bytes, 4 crop, 5 decode-img; 10 status ok, 11 failed, 12 unsupported, 13 limit; 14, 15 the ocr word flags.
#[no_mangle]
pub extern "C" fn emon_const(i: u32) -> i32 {
    shared::constant(i)
}

// The name of an error category (`emon_error.code`, or its negation), or NULL. Static.
#[no_mangle]
pub extern "C" fn emon_error_category(code: c_int) -> *const c_char {
    match category_name(code) {
        Some(s) => s.as_ptr() as *const c_char,
        None => std::ptr::null(),
    }
}

// The category's name, NUL-terminated (for C), or None.
pub fn category_name(code: c_int) -> Option<&'static str> {
    Some(match code.abs() {
        E_USAGE => "usage\0",
        E_NO_INPUT => "no-input\0",
        E_LIMIT => "limit\0",
        E_PASSWORD_REQUIRED => "password-required\0",
        E_PASSWORD_INCORRECT => "password-incorrect\0",
        E_ENCRYPTION_UNSUPPORTED => "encryption-unsupported\0",
        E_UNSUPPORTED_FORMAT => "unsupported-format\0",
        E_DAMAGED => "damaged\0",
        E_SOURCE_CHANGED => "source-changed\0",
        E_IO => "io\0",
        E_UNSUPPORTED => "unsupported\0",
        E_PLAN => "plan\0",
        E_INTERNAL => "internal\0",
        _ => return None,
    })
}

// Whether `name` is a key of the options grammar, which kin owns (`emon.config`): 1 or 0.
#[no_mangle]
pub unsafe extern "C" fn emon_is_option_key(name: *const c_char) -> c_int {
    catch_unwind(AssertUnwindSafe(|| match text(name) {
        Ok(n) => crate::engine::is_option_key(&n) as c_int,
        Err(_) => 0,
    }))
    .unwrap_or(0)
}

// Parse option text (`key=value` lines, kin's grammar) and say whether it is good; 0, or the usage error with kin's sentence.
#[no_mangle]
pub unsafe extern "C" fn emon_check_options(options: *const c_char, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let t = text(options)?;
        parse_options(&t).map_err(Fail::Usage)?;
        Ok(0)
    })
}

// A test of the guard: a function that panics, so a C program can see the boundary hold (the error `internal`, with the message).
#[no_mangle]
pub unsafe extern "C" fn emon_test_panic(err: *mut emon_error) -> c_int {
    guarded(err, || -> Result<c_int, Fail> { panic!("emon_test_panic: a panic on purpose") })
}

// ------------------------------------------------------------------------------------------------ options, ports, inputs

// The same list as the drivers' `--html=` (and the browser's `emon_model_html`).
fn parse_html(list: &str) -> Result<HtmlOptions, Fail> {
    shared::parse_html(list).map_err(Fail::Usage)
}

// What the pod (`host/pod`, a binary over this library) shares with the C ABI, as plain Rust: the html list and the native ports.
pub fn html_options(list: &str) -> Result<HtmlOptions, String> {
    shared::parse_html(list)
}

pub fn native_config(cfg: &mut Config) -> Result<(), String> {
    native_ports(cfg).map_err(|f| match f {
        Fail::Usage(m) => m,
        Fail::Engine(e) => e.to_string(),
    })
}

// The Rust host's own ports, for the ones this build has (D127, D211, D336). A build without them has none, and a request for one is
// answered `unsupported` (D199): `emon_capabilities` says which are here.
#[allow(unused_variables)]
fn native_ports(cfg: &mut Config) -> Result<(), Fail> {
    #[cfg(feature = "tesseract")]
    {
        if cfg.ocr.is_none() {
            let t = crate::tess::TessOcr::new(None).map_err(|e| Fail::Usage(format!("tesseract: {}", e)))?;
            cfg.ocr = Some(crate::engine::OcrPort(std::sync::Arc::new(t)));
        }
    }
    #[cfg(pdfium)]
    {
        cfg.renderer = Some(std::sync::Arc::new(crate::pdfium::Pdfium::new()));
    }
    #[cfg(codecs)]
    {
        cfg.pdf_codecs = Some(std::sync::Arc::new(crate::pdfcodecs::Codecs::new()));
    }
    Ok(())
}

// The caller's lazy byte source (SPEC 13.1): a length and a `read_at` it implements however it likes (pread on a file, a range request,
// a database column). The engine reads the spans it touches. Called on the engine's worker thread, one call at a time.
struct CallbackSource {
    length: u64,
    read: unsafe extern "C" fn(*mut c_void, u64, *mut u8, usize) -> i64,
    ctx: *mut c_void,
}

unsafe impl Send for CallbackSource {}
unsafe impl Sync for CallbackSource {}

impl ByteSource for CallbackSource {
    fn len(&self) -> u64 {
        self.length
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> std::io::Result<usize> {
        if off >= self.length || buf.is_empty() {
            return Ok(0);
        }
        let n = std::cmp::min(buf.len() as u64, self.length - off) as usize;
        let r = unsafe { (self.read)(self.ctx, off, buf.as_mut_ptr(), n) };
        if r < 0 {
            return Err(std::io::Error::new(std::io::ErrorKind::Other, format!("the source's read_at answered {}", r)));
        }
        Ok(std::cmp::min(r as usize, n))
    }
}

unsafe fn make_input(inp: *const emon_input) -> Result<Input, Fail> {
    let inp = inp.as_ref().ok_or_else(|| Fail::Usage("the input is NULL".to_string()))?;
    let mut input = if let Some(src) = inp.source.as_ref() {
        let read = src.read_at.ok_or_else(|| Fail::Usage("the source has no read_at".to_string()))?;
        Input::new(CallbackSource { length: src.length, read, ctx: src.ctx })
    } else {
        if inp.data.is_null() && inp.len != 0 {
            return Err(Fail::Usage("the input has a length and no bytes".to_string()));
        }
        Input::bytes(bytes(inp.data, inp.len).to_vec())
    };
    if !inp.password.is_null() && inp.password_len > 0 {
        input = input.with_password(bytes(inp.password, inp.password_len).to_vec());
    }
    Ok(input)
}

// The text parsed by kin, and the Engine's configuration with the native ports when asked. The step API's own switches (`record`,
// `js-ocr`) belong to a session: a one-shot refuses them rather than ignoring them.
fn one_shot_setup(p: &Parsed, flags: u32) -> Result<(Engine, Options), Fail> {
    if p.record || p.js_ocr || p.js_render {
        return Err(Fail::Usage("record=1, js-ocr=1 and js-render=1 are options of a session (emon_open): a one-shot conversion has no caller to fulfil or keep a transcript".to_string()));
    }
    let mut cfg = p.config.clone();
    if flags & EMON_NATIVE != 0 {
        native_ports(&mut cfg)?;
    }
    Ok((Engine::new(cfg), p.options.clone()))
}

fn set_stats(s: &RunStats) -> emon_stats {
    emon_stats {
        attempts: s.attempts,
        fetch_rounds: s.fetch_rounds,
        bytes_requests: s.bytes_requests,
        requests: s.requests,
        source_reads: s.source_reads,
        bytes_fetched: s.bytes_fetched,
        input_bytes: s.input_bytes,
    }
}

// ------------------------------------------------------------------------------------------------ the models

struct Converted {
    model: Model,
    stats: RunStats,
}

fn model_ptr(m: Model, stats: RunStats) -> *mut emon_model {
    Box::into_raw(Box::new(Converted { model: m, stats })) as *mut emon_model
}

unsafe fn converted<'a>(m: *const emon_model) -> Result<&'a Converted, Fail> {
    (m as *const Converted).as_ref().ok_or_else(|| Fail::Usage("the model is NULL".to_string()))
}

// ONE-SHOT: convert one input to a Model. `options`: kin's `key=value` text (NULL or empty: the defaults). `flags`: EMON_NATIVE.
#[no_mangle]
pub unsafe extern "C" fn emon_convert(input: *const emon_input, options: *const c_char, flags: u32, out: *mut *mut emon_model, err: *mut emon_error) -> c_int {
    guarded(err, || {
        if out.is_null() {
            return Err(Fail::Usage("an output pointer is NULL".to_string()));
        }
        let p = parse_options(&text(options)?).map_err(Fail::Usage)?;
        let input = make_input(input)?;
        let (engine, opts) = one_shot_setup(&p, flags)?;
        let (m, stats) = on_worker(|| engine.convert_stats(input, &opts))?;
        *out = model_ptr(m, stats);
        Ok(0)
    })
}

// ONE-SHOT, the common case in one call: bytes in, the canonical JSON out.
#[no_mangle]
pub unsafe extern "C" fn emon_convert_json(input: *const emon_input, options: *const c_char, flags: u32, pretty: c_int, out: *mut emon_buf, err: *mut emon_error) -> c_int {
    let mut m: *mut emon_model = std::ptr::null_mut();
    let r = emon_convert(input, options, flags, &mut m, err);
    if r != 0 {
        return r;
    }
    let r = emon_model_json(m, pretty, out, err);
    emon_free_model(m);
    r
}

// ONE-SHOT: bytes in, the HTML out (`html_options`: the drivers' `--html=` list).
#[no_mangle]
pub unsafe extern "C" fn emon_convert_html(input: *const emon_input, options: *const c_char, flags: u32, html_options: *const c_char, out: *mut emon_buf, err: *mut emon_error) -> c_int {
    let mut m: *mut emon_model = std::ptr::null_mut();
    let r = emon_convert(input, options, flags, &mut m, err);
    if r != 0 {
        return r;
    }
    let r = emon_model_html(m, html_options, out, err);
    emon_free_model(m);
    r
}

// Stage 0 of the input alone: the line `FORMAT MAJOR.MINOR pages=N encrypted=BOOL bytes=N` (the driver's `--out=intake`).
#[no_mangle]
pub unsafe extern "C" fn emon_intake(input: *const emon_input, options: *const c_char, flags: u32, out: *mut emon_buf, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let p = parse_options(&text(options)?).map_err(Fail::Usage)?;
        let input = make_input(input)?;
        let (engine, _) = one_shot_setup(&p, flags)?;
        let v = on_worker(|| engine.intake(input))?;
        let line = v.iter().map(shared::intake_line).collect::<Vec<_>>().join("\n");
        out_buf(out, buf_of(line.into_bytes()))?;
        Ok(0)
    })
}

#[no_mangle]
pub unsafe extern "C" fn emon_free_model(m: *mut emon_model) {
    if !m.is_null() {
        drop(Box::from_raw(m as *mut Converted));
    }
}

#[no_mangle]
pub unsafe extern "C" fn emon_model_json(m: *const emon_model, pretty: c_int, out: *mut emon_buf, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let c = converted(m)?;
        let s = on_worker(|| c.model.to_json(&JsonOptions { pretty: pretty != 0 }));
        out_buf(out, buf_of(s.into_bytes()))?;
        Ok(0)
    })
}

#[no_mangle]
pub unsafe extern "C" fn emon_model_html(m: *const emon_model, html_options: *const c_char, out: *mut emon_buf, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let c = converted(m)?;
        let o = parse_html(&text(html_options)?)?;
        let s = on_worker(|| c.model.to_html(&o));
        out_buf(out, buf_of(s.into_bytes()))?;
        Ok(0)
    })
}

// The text layer's evidence dump when the options asked to keep it (`keep-evidence=1`); empty when they did not.
#[no_mangle]
pub unsafe extern "C" fn emon_model_evidence(m: *const emon_model, out: *mut emon_buf, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let c = converted(m)?;
        out_buf(out, buf_of(c.model.evidence().unwrap_or("").as_bytes().to_vec()))?;
        Ok(0)
    })
}

#[no_mangle]
pub unsafe extern "C" fn emon_model_stats(m: *const emon_model, out: *mut emon_stats) -> c_int {
    match (m as *const Converted).as_ref() {
        Some(c) if !out.is_null() => {
            *out = set_stats(&c.stats);
            0
        }
        _ => -E_USAGE,
    }
}

// ASSETS (SPEC 10.11, D117): the pictures a Model carries when the options said `assets=images` or `figures`.
#[no_mangle]
pub unsafe extern "C" fn emon_model_asset_count(m: *const emon_model) -> c_int {
    match converted(m) {
        Ok(c) => catch_unwind(AssertUnwindSafe(|| c.model.assets().len() as c_int)).unwrap_or(-E_INTERNAL),
        Err(_) => -E_USAGE,
    }
}

// Asset `i`'s name (the file to write beside external HTML), media type, origin, sha256, size, id and pixel size.
#[no_mangle]
pub unsafe extern "C" fn emon_model_asset_info(m: *const emon_model, i: c_int, out: *mut emon_asset_info, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let c = converted(m)?;
        let a = c.model.asset(i).ok_or_else(|| Fail::Usage(format!("the model has no asset {}", i)))?;
        if out.is_null() {
            return Err(Fail::Usage("an output pointer is NULL".to_string()));
        }
        *out = emon_asset_info { id: a.id(), name: cstring(&a.name()), media_type: cstring(&a.media_type()), origin: cstring(&a.origin()), sha256: cstring(&a.sha256()), width: a.width(), height: a.height(), size: a.len() };
        Ok(0)
    })
}

// Asset `i`'s encoded bytes (a JPEG as the document has it, or the canonical PNG).
#[no_mangle]
pub unsafe extern "C" fn emon_model_asset_bytes(m: *const emon_model, i: c_int, out: *mut emon_buf, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let c = converted(m)?;
        let a = c.model.asset(i).ok_or_else(|| Fail::Usage(format!("the model has no asset {}", i)))?;
        out_buf(out, buf_of(a.bytes()))?;
        Ok(0)
    })
}

// ------------------------------------------------------------------------------------------------ the inverted step API
//
// The session itself (`crate::session`, D481) is shared with the browser binding: the state machine over the engine's `Run`, the
// input-bytes check, the error mapping. What is here is this ABI's: the handle, the shapes of the header, the worker thread.

unsafe fn session<'a>(s: *mut emon_session) -> Result<&'a mut Session, Fail> {
    (s as *mut Session).as_mut().ok_or_else(|| Fail::Usage("the session is NULL".to_string()))
}

// Open a session over an input of `length` bytes read in chunks of `chunk` bytes (0: the options' `chunk`, 64 KiB). `flags`:
// EMON_OPEN_INTAKE for stage 0 only (then `emon_finish_intake`). The options are kin's text; `record=1` keeps a transcript
// (`emon_transcript`), `js-ocr=1` makes the `ocr` requests the caller's.
#[no_mangle]
pub unsafe extern "C" fn emon_open(length: u64, chunk: u32, flags: u32, password: *const u8, password_len: usize, options: *const c_char, out: *mut *mut emon_session, err: *mut emon_error) -> c_int {
    guarded(err, || {
        if out.is_null() {
            return Err(Fail::Usage("an output pointer is NULL".to_string()));
        }
        let options = text(options)?;
        let password = if password_len > 0 { Some(bytes(password, password_len).to_vec()) } else { None };
        let s = Session::open(length, if chunk != 0 { Some(chunk as usize) } else { None }, flags & EMON_OPEN_INTAKE != 0, password, &options)?;
        *out = Box::into_raw(Box::new(s)) as *mut emon_session;
        Ok(0)
    })
}

#[no_mangle]
pub unsafe extern "C" fn emon_close(s: *mut emon_session) {
    if !s.is_null() {
        // a Run holds kin's arenas; dropping it on the worker keeps its (deep) drop off the caller's small stack
        let b = AssertSend(Box::from_raw(s as *mut Session));
        let _ = catch_unwind(AssertUnwindSafe(|| on_worker(move || drop(b.into_inner()))));
    }
}

fn put_ranges(out: *mut emon_step_out, r: &[(u64, u64)]) -> Result<(), Fail> {
    if out.is_null() {
        return Err(Fail::Usage("an output pointer is NULL".to_string()));
    }
    let (p, n) = leak(r.iter().map(|&(offset, length)| emon_range { offset, length }).collect::<Vec<_>>());
    unsafe {
        *out = emon_step_out { ranges: p, nranges: n, requests: std::ptr::null_mut(), nrequests: 0, cells: std::ptr::null_mut(), ncells: 0 };
    }
    Ok(())
}

// SPEC 13.1: intake round 0 reads the first and the last chunk. `out->ranges` are the ranges to read and provide first; answers how many.
#[no_mangle]
pub unsafe extern "C" fn emon_prefetch(s: *mut emon_session, out: *mut emon_step_out, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let r = session(s)?.prefetch();
        put_ranges(out, &r)?;
        Ok(r.len() as c_int)
    })
}

// The bytes at `offset` the caller read (copied). Chunks they cover whole become resident.
#[no_mangle]
pub unsafe extern "C" fn emon_provide(s: *mut emon_session, offset: u64, data: *const u8, len: usize, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let s = session(s)?;
        if offset > i32::MAX as u64 {
            return Err(Fail::Usage("an offset past the 2 GiB the engine reads".to_string()));
        }
        s.provide(offset as usize, bytes(data, len));
        Ok(0)
    })
}

// The caller's read failed: `short` non-zero when it gave fewer bytes than the length said (the source changed: `source-changed`), else an
// `io` error with the message.
#[no_mangle]
pub unsafe extern "C" fn emon_fail(s: *mut emon_session, short: c_int, message: *const c_char, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let s = session(s)?;
        let m = text(message)?;
        s.fail(short != 0, &m);
        Ok(0)
    })
}

// REPLAY (SPEC 14.1.5): the transcript's files, one at a time, then `emon_replay_end`. A transcript recorded on any host replays here.
#[no_mangle]
pub unsafe extern "C" fn emon_replay_file(s: *mut emon_session, name: *const c_char, data: *const u8, len: usize, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let s = session(s)?;
        s.replay_file(text(name)?, bytes(data, len).to_vec());
        Ok(0)
    })
}

#[no_mangle]
pub unsafe extern "C" fn emon_replay_end(s: *mut emon_session, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let s = session(s)?;
        on_worker(|| s.replay_end())?;
        Ok(0)
    })
}

// Run kin until it needs something of the caller. Answers 0 DONE (then `emon_finish`), 1 NEED_BYTES (`out->ranges`: read each and
// `emon_provide` it, then step again), 2 NEED_REQUESTS (`out->requests`: fulfil each, `emon_answer` / `emon_answer_ocr`, then step
// again), or `-code` with `err` filled (nothing else is valid afterwards but `emon_close`).
#[no_mangle]
pub unsafe extern "C" fn emon_step(s: *mut emon_session, out: *mut emon_step_out, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let s = session(s)?;
        if out.is_null() {
            return Err(Fail::Usage("an output pointer is NULL".to_string()));
        }
        *out = emon_step_out { ranges: std::ptr::null_mut(), nranges: 0, requests: std::ptr::null_mut(), nrequests: 0, cells: std::ptr::null_mut(), ncells: 0 };
        match on_worker(|| s.step())? {
            Step::Done => Ok(0),
            Step::Bytes(v) => {
                put_ranges(out, &v)?;
                Ok(1)
            }
            Step::Requests(reqs) => {
                let mut pool: Vec<i32> = Vec::new();
                let mut spans: Vec<(usize, usize)> = Vec::new();
                for r in &reqs {
                    spans.push((pool.len(), r.len()));
                    pool.extend_from_slice(r);
                }
                let (cells, ncells) = leak(pool);
                let rs: Vec<emon_request> = reqs
                    .iter()
                    .zip(spans)
                    .map(|(r, (at, n))| emon_request { kind: r.get(1).copied().unwrap_or(-1), id: r.get(2).copied().unwrap_or(-1), cells: if cells.is_null() { std::ptr::null() } else { cells.add(at) }, ncells: n })
                    .collect();
                let (requests, nrequests) = leak(rs);
                *out = emon_step_out { ranges: std::ptr::null_mut(), nranges: 0, requests, nrequests, cells, ncells };
                Ok(2)
            }
        }
    })
}

// The `n` cells at `ptr` (none: empty).
unsafe fn ints<'a>(ptr: *const i32, n: usize) -> &'a [i32] {
    if ptr.is_null() {
        &[]
    } else {
        std::slice::from_raw_parts(ptr, n)
    }
}

// A request the caller was owed, answered by a raw record `[len kind id status ...]` (a failed or unsupported one is four cells).
#[no_mangle]
pub unsafe extern "C" fn emon_answer(s: *mut emon_session, cells: *const i32, ncells: usize, err: *mut emon_error) -> c_int {
    guarded(err, || {
        session(s)?.answer(ints(cells, ncells).to_vec())?;
        Ok(0)
    })
}

// An `ocr` answer from the flat integer form a caller makes of its engine's reading; the form and the record are kin's (`ox-flat`,
// `emon.ocrport`), so the layout is written once and a malformed form is refused by kin.
#[no_mangle]
pub unsafe extern "C" fn emon_answer_ocr(s: *mut emon_session, id: i32, cells: *const i32, ncells: usize, err: *mut emon_error) -> c_int {
    guarded(err, || {
        session(s)?.answer_ocr(id, ints(cells, ncells))?;
        Ok(0)
    })
}

// The bitmap a request names (an `ocr` request's `bitmap` argument, or the answer of any request already answered), as a canonical PNG:
// what the caller's recogniser reads. Valid while the requests of the round are owed.
#[no_mangle]
pub unsafe extern "C" fn emon_bitmap_png(s: *mut emon_session, id: i32, out: *mut emon_buf, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let png = session(s)?.bitmap_png(id);
        let png = png.ok_or_else(|| Fail::Usage(format!("request {} has no bitmap now", id)))?;
        out_buf(out, buf_of(png))?;
        Ok(0)
    })
}

// An `ocr` request's policy is kin's (`emon.ocrport`): the index of the argument its first language is at.
#[no_mangle]
pub unsafe extern "C" fn emon_ocr_lang_first(cells: *const i32, ncells: usize) -> c_int {
    catch_unwind(AssertUnwindSafe(|| shared::ocr_lang_first(ints(cells, ncells)))).unwrap_or(-1)
}

// The installed models that read the request: `installed` holds their names as code points separated by 0; `out` is the int32 indices into
// that list (see `op-models`). Answers how many.
#[no_mangle]
pub unsafe extern "C" fn emon_ocr_models(request: *const i32, nrequest: usize, installed: *const i32, ninstalled: usize, out: *mut emon_buf, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let (n, indices) = shared::ocr_models(ints(request, nrequest), ints(installed, ninstalled));
        let b: Vec<u8> = indices.iter().flat_map(|x| x.to_ne_bytes()).collect();
        out_buf(out, buf_of(b))?;
        Ok(n)
    })
}

// A line's type from the engine's block type (Tesseract's PolyBlockType).
#[no_mangle]
pub extern "C" fn emon_ocr_block_type(poly: i32) -> c_int {
    catch_unwind(|| shared::ocr_block_type(poly)).unwrap_or(-1)
}

// The language the engine read a word in (the model's name as code points), as a request spells it: one packed cell.
#[no_mangle]
pub unsafe extern "C" fn emon_ocr_word_lang(cells: *const i32, ncells: usize) -> c_int {
    catch_unwind(AssertUnwindSafe(|| shared::ocr_word_lang(ints(cells, ncells)))).unwrap_or(-1)
}

// The transcript a recording session (`record=1`) made, as files (`transcript.edn` and PNGs): write them to a directory, or keep them.
#[no_mangle]
pub unsafe extern "C" fn emon_transcript(s: *mut emon_session, out: *mut emon_files, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let s = session(s)?;
        let files = on_worker(|| s.transcript()).ok_or_else(|| Fail::Usage("the session is not recording (record=1)".to_string()))?;
        if out.is_null() {
            return Err(Fail::Usage("an output pointer is NULL".to_string()));
        }
        let v: Vec<emon_file> = files
            .into_iter()
            .map(|(n, d)| {
                let b = buf_of(d);
                emon_file { name: cstring(&n), data: b.data, len: b.len }
            })
            .collect();
        let (files, n) = leak(v);
        *out = emon_files { files, n };
        Ok(0)
    })
}

// The end of a conversion: the Model, with what the loop did. The session is spent (close it).
#[no_mangle]
pub unsafe extern "C" fn emon_finish(s: *mut emon_session, out: *mut *mut emon_model, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let s = session(s)?;
        if out.is_null() {
            return Err(Fail::Usage("an output pointer is NULL".to_string()));
        }
        let (m, st) = on_worker(|| s.finish())?;
        *out = model_ptr(m, st);
        Ok(0)
    })
}

// The end of an intake session: the driver's line `FORMAT MAJOR.MINOR pages=N encrypted=B bytes=N`.
#[no_mangle]
pub unsafe extern "C" fn emon_finish_intake(s: *mut emon_session, out: *mut emon_buf, err: *mut emon_error) -> c_int {
    guarded(err, || {
        let s = session(s)?;
        let line = on_worker(|| s.finish_intake())?;
        out_buf(out, buf_of(line.into_bytes()))?;
        Ok(0)
    })
}
