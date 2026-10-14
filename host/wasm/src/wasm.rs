// THE WASM BINDING (DEVLOG P9.3, SPEC 13.1, 7.3; D058 revised, D089, D356-D361). The Rust host built for `wasm32-unknown-unknown`
// and given a C ABI: the JS SDK (`host/js/`) instantiates it and is the HOST. This file is assembled into the crate beside
// the host files by `kin/scripts/emon-wasm-build` (it is not part of the native crate) and names no filesystem: the
// module has NO imports at all (the build checks).
//
// WHO OWNS THE LOOP. JavaScript cannot block on a promise, so the engine never calls out. `emon_step` runs kin until it needs
// something of the host and says what; the JS side awaits it (a `read(offset, length)` of the byte source, a tesseract.js
// recognition) and calls back in, then steps again:
//
//     status = emon_step(session)
//       0  done                  -> emon_finish: the Model (or the intake)
//       1  need bytes            -> OUT is pairs of i32 [offset length]: read them, `emon_provide` each, step again
//       2  requests for the host -> OUT is [count, then per request: n, n cells]: fulfil, `emon_answer*`, step again
//      <0  an error              -> OUT is `name\nmessage` (the names of `Error::name`, D040), nothing else is valid
//
// The unit that missed is re-run from scratch inside `emon_step` (D058: the attempt that did not miss is the in-memory run),
// so the bytes a conversion fetched are the bytes it touched. `decode`, `decode-img` and (with no port) `ocr` and `render`
// requests are answered here, in Rust, from the bytes the host provided; `ocr` goes to the host when it said it has an engine
// (`js-ocr`), as SPEC 7.3 says: the JS host owns it. `render` and `crop` are the JS host's too, through pdfium's WebAssembly
// build (P11.4c, `js-render`; `emon_answer_render`); they, and `ocr` from a host with no engine, are answered `unsupported`, which
// is an answer (D199): the page then has no text from it.
//
// MEMORY. Everything crosses as integers and byte ranges of linear memory; the host allocates with `emon_alloc` and frees with
// `emon_free`. Results are written to ONE shared output buffer (`emon_out_ptr`, `emon_out_len`) that the next call overwrites.
#![allow(clippy::missing_safety_doc)]
//
// THE SESSION is `crate::session` (D481), shared with the C binding: the state machine over the engine's `Run`, the input-bytes
// check, the error mapping. This file is the linear-memory layer over it: pointers, the output buffer, the exports.
use crate::engine::{Error, JsonOptions, Model};
use crate::session as shared;
use crate::session::{Fault, Session, Step};
use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};

// ------------------------------------------------------------------------------------------------ the world

#[derive(Default)]
struct World {
    sessions: Vec<Option<Box<Session>>>,
    models: Vec<Option<Box<Model>>>,
    out: Vec<u8>,
}

thread_local! {
    static WORLD: RefCell<World> = RefCell::new(World::default());
}

fn world<T>(f: impl FnOnce(&mut World) -> T) -> T {
    WORLD.with(|w| f(&mut w.borrow_mut()))
}

fn put(bytes: Vec<u8>) {
    world(|w| w.out = bytes);
}

fn put_str(s: &str) {
    put(s.as_bytes().to_vec());
}

fn put_error(e: &Error) -> i32 {
    put_str(&format!("{}\n{}", e.name(), e));
    -1
}

// A session call's failure: the engine's (`name\nmessage`) or the caller's mistake (`usage\nmessage`).
fn put_fault(f: &Fault) -> i32 {
    match f {
        Fault::Engine(e) => put_error(e),
        Fault::Usage(m) => {
            put_str(&format!("usage\n{}", m));
            -1
        }
    }
}

// A panic that is not kin's (kin's are caught inside the loop and named): a bug in this file.
fn guard(f: impl FnOnce() -> i32) -> i32 {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(p) => {
            put_str(&format!("internal\n{}", shared::panic_message(p)));
            -2
        }
    }
}

unsafe fn bytes<'a>(ptr: u32, len: u32) -> &'a [u8] {
    if len == 0 {
        return &[];
    }
    std::slice::from_raw_parts(ptr as usize as *const u8, len as usize)
}

unsafe fn text<'a>(ptr: u32, len: u32) -> &'a str {
    std::str::from_utf8(bytes(ptr, len)).unwrap_or("")
}

fn session<T>(h: i32, f: impl FnOnce(&mut Session) -> T) -> Option<T> {
    world(|w| w.sessions.get_mut((h - 1) as usize).and_then(|s| s.as_mut()).map(|s| f(s)))
}

fn put_i32s(v: &[i32]) {
    let mut b = Vec::with_capacity(v.len() * 4);
    for x in v {
        b.extend_from_slice(&x.to_le_bytes());
    }
    put(b);
}

// ------------------------------------------------------------------------------------------------ memory

#[no_mangle]
pub extern "C" fn emon_alloc(len: u32) -> u32 {
    let mut v = vec![0u8; len.max(1) as usize].into_boxed_slice();
    let p = v.as_mut_ptr();
    std::mem::forget(v);
    p as usize as u32
}

#[no_mangle]
pub unsafe extern "C" fn emon_free(ptr: u32, len: u32) {
    if ptr != 0 {
        drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(ptr as usize as *mut u8, len.max(1) as usize)));
    }
}

#[no_mangle]
pub extern "C" fn emon_out_ptr() -> u32 {
    world(|w| w.out.as_ptr() as usize as u32)
}

#[no_mangle]
pub extern "C" fn emon_out_len() -> u32 {
    world(|w| w.out.len() as u32)
}

// Give the output buffer back (a big result is not kept past its reading).
#[no_mangle]
pub extern "C" fn emon_out_free() {
    world(|w| w.out = Vec::new());
}

// The numbers of the plan/fulfil protocol, so the JS side never restates them (`emon.plan`'s, a kin law holds them):
// 0 kind render, 1 decode, 2 ocr, 3 bytes, 4 crop, 5 decode-img; 10 status ok, 11 failed, 12 unsupported, 13 limit.
#[no_mangle]
pub extern "C" fn emon_const(i: u32) -> i32 {
    shared::constant(i)
}

// The page's clock, in milliseconds (`performance.now()`): the module has none (no imports), so the JS SDK sets it before it steps, and the loop's
// `stage-ms` (P10.4, SPEC 7.4) is judged against the time between two settings. Unset, no stage is ever late.
#[no_mangle]
pub extern "C" fn emon_clock(ms: f64) {
    crate::plan::set_clock_ms(ms);
}

// ------------------------------------------------------------------------------------------------ sessions

// Open a session over an input of `len` bytes (a number: an input over the engine's bound is refused by name, not wrapped, and before
// its chunks are allocated), read in chunks of `chunk` bytes. `mode`: 0 convert, 1 intake only. Answers a handle (> 0) or -1 with
// the error in OUT.
#[no_mangle]
pub unsafe extern "C" fn emon_open(len: f64, chunk: u32, mode: u32, password_ptr: u32, password_len: u32, opts_ptr: u32, opts_len: u32) -> i32 {
    guard(|| {
        let password = if password_len > 0 { Some(bytes(password_ptr, password_len).to_vec()) } else { None };
        // the chunk is the caller's argument (the browser's source decides it), not an option
        let chunk = if chunk == 0 { 65536 } else { chunk as usize };
        match Session::open(len as u64, Some(chunk), mode == 1, password, text(opts_ptr, opts_len)) {
            Ok(s) => world(|w| {
                w.sessions.push(Some(Box::new(s)));
                w.sessions.len() as i32
            }),
            Err(f) => put_fault(&f),
        }
    })
}

// SPEC 13.1: intake round 0 reads the first and the last chunk. OUT: pairs [offset length]; answers how many.
#[no_mangle]
pub extern "C" fn emon_prefetch(h: i32) -> i32 {
    guard(|| match session(h, |s| s.prefetch()) {
        Some(r) => {
            put_ranges(&r);
            r.len() as i32
        }
        None => -1,
    })
}

fn put_ranges(r: &[(u64, u64)]) {
    let mut v = Vec::new();
    for &(offset, length) in r {
        v.push(offset as i32);
        v.push(length as i32);
    }
    put_i32s(&v);
}

// The bytes at `offset` the host read. Chunks they cover whole become resident.
#[no_mangle]
pub unsafe extern "C" fn emon_provide(h: i32, offset: u32, ptr: u32, len: u32) -> i32 {
    guard(|| {
        let data = bytes(ptr, len);
        match session(h, |s| s.provide(offset as usize, data)) {
            Some(()) => 0,
            None => -1,
        }
    })
}

// The host's read failed: `short` 1 when it gave fewer bytes than the length said (the source changed: `source-changed`), else
// an `io` error with the message.
#[no_mangle]
pub unsafe extern "C" fn emon_fail(h: i32, short: u32, ptr: u32, len: u32) -> i32 {
    guard(|| {
        let m = text(ptr, len).to_string();
        match session(h, |s| s.fail(short != 0, &m)) {
            Some(()) => 0,
            None => -1,
        }
    })
}

// REPLAY (SPEC 14.1.5): the transcript's files, one at a time, then `emon_replay_end`. A transcript recorded on any host
// replays here, and the reverse.
#[no_mangle]
pub unsafe extern "C" fn emon_replay_file(h: i32, name_ptr: u32, name_len: u32, ptr: u32, len: u32) -> i32 {
    guard(|| {
        let name = text(name_ptr, name_len).to_string();
        let data = bytes(ptr, len).to_vec();
        match session(h, |s| s.replay_file(name, data)) {
            Some(()) => 0,
            None => -1,
        }
    })
}

#[no_mangle]
pub extern "C" fn emon_replay_end(h: i32) -> i32 {
    guard(|| match session(h, |s| s.replay_end()) {
        Some(Ok(())) => 0,
        Some(Err(e)) => put_error(&e),
        None => -1,
    })
}

// Walk the session on: 0 done, 1 bytes needed (OUT: pairs), 2 requests for the host (OUT: count, then n and n cells each), -1 error.
#[no_mangle]
pub extern "C" fn emon_step(h: i32) -> i32 {
    guard(|| match session(h, |s| s.step()) {
        Some(Ok(Step::Done)) => {
            put(Vec::new());
            0
        }
        Some(Ok(Step::Bytes(r))) => {
            put_ranges(&r);
            1
        }
        Some(Ok(Step::Requests(owed))) => {
            let mut v: Vec<u8> = Vec::new();
            v.extend_from_slice(&(owed.len() as i32).to_le_bytes());
            for rec in owed {
                v.extend_from_slice(&(rec.len() as i32).to_le_bytes());
                for c in &rec {
                    v.extend_from_slice(&c.to_le_bytes());
                }
            }
            put(v);
            2
        }
        Some(Err(e)) => put_error(&e),
        None => -1,
    })
}

// What a session's answer made of the call: 0, or -1 with the caller's mistake in OUT.
fn answered(r: Option<Result<(), Fault>>) -> i32 {
    match r {
        Some(Ok(())) => 0,
        Some(Err(f)) => put_fault(&f),
        None => -1,
    }
}

// A request the host was owed, answered by a raw record (`[len kind id status ...]`; a failed or unsupported one is four cells).
#[no_mangle]
pub unsafe extern "C" fn emon_answer(h: i32, ptr: u32, ncells: u32) -> i32 {
    guard(|| {
        let rec = ints_from(ptr, ncells);
        answered(session(h, |s| s.answer(rec)))
    })
}

// An `ocr` answer from the flat integer form the JS adapter makes of its engine's reading; the form and the record are kin's
// (`ox-flat`, `emon.ocrport`, P10.7), so the layout is written once and a malformed form is refused by kin.
#[no_mangle]
pub unsafe extern "C" fn emon_answer_ocr(h: i32, id: i32, ptr: u32, ncells: u32) -> i32 {
    guard(|| {
        let flat = ints_from(ptr, ncells);
        answered(session(h, |s| s.answer_ocr(id, &flat)))
    })
}

// A `render` or `crop` answer (P11.4c, `js-render`): the WHOLE page the JS adapter's pdfium made, `width` x `height` pixels of RGB, `len`
// bytes at `ptr` (a crop is the cut of it, in Rust, `render::cut`: the pixels are the same however a crop is asked, SPEC 7.2). The
// answer record is kin's; a bitmap that is not `width * height * 3` bytes or is over the `pixels` limit is `usage`.
#[no_mangle]
pub unsafe extern "C" fn emon_answer_render(h: i32, id: i32, width: i32, height: i32, ptr: u32, len: u32) -> i32 {
    guard(|| {
        let rgb = bytes(ptr, len);
        answered(session(h, |s| s.answer_render(id, width, height, rgb)))
    })
}

// The value of limit `kind` in force for the session (kin's table, the conversion's `limit.NAME=N` over it): what bound the JS adapter's
// renderer must keep (`pixels` is kind 9), asked of the module so the SDK types no default. -1 for a handle that is not open.
#[no_mangle]
pub extern "C" fn emon_limit(h: i32, kind: i32) -> i32 {
    guard(|| session(h, |s| s.limit(kind)).unwrap_or(-1))
}

// The `ncells` little-endian i32s at `ptr` in the module's memory.
unsafe fn ints_from(ptr: u32, ncells: u32) -> Vec<i32> {
    bytes(ptr, ncells * 4).chunks_exact(4).map(|c| i32::from_le_bytes([c[0], c[1], c[2], c[3]])).collect()
}

// An `ocr` request's policy is kin's (`emon.ocrport`), and the JS adapters ask it here. `ptr`/`ncells`: the request record
// `[len kind id arg...]`. The index of the argument its first language is at (after a kin-made view's pixel hash).
#[no_mangle]
pub unsafe extern "C" fn emon_ocr_lang_first(ptr: u32, ncells: u32) -> i32 {
    guard(|| shared::ocr_lang_first(&ints_from(ptr, ncells)))
}

// The installed models that read the request: `inst` holds their names as code points separated by 0; OUT is the i32 indices into
// that list (see `op-models`). Answers how many.
#[no_mangle]
pub unsafe extern "C" fn emon_ocr_models(ptr: u32, ncells: u32, iptr: u32, icells: u32) -> i32 {
    guard(|| {
        let (n, indices) = shared::ocr_models(&ints_from(ptr, ncells), &ints_from(iptr, icells));
        put_i32s(&indices);
        n
    })
}

// A line's type from the engine's block type (Tesseract's PolyBlockType).
#[no_mangle]
pub extern "C" fn emon_ocr_block_type(poly: i32) -> i32 {
    guard(|| shared::ocr_block_type(poly))
}

// The language the engine read a word in (the model's name as code points), as a request spells it: one packed cell.
#[no_mangle]
pub unsafe extern "C" fn emon_ocr_word_lang(ptr: u32, ncells: u32) -> i32 {
    guard(|| shared::ocr_word_lang(&ints_from(ptr, ncells)))
}

// The bitmap a request names (an `ocr` request's `bitmap` argument, or the answer of any request already answered), as a
// canonical PNG in OUT: what the host's recogniser reads. Answers the PNG's length, or -1.
#[no_mangle]
pub extern "C" fn emon_bitmap_png(h: i32, id: i32) -> i32 {
    guard(|| match session(h, |s| s.bitmap_png(id)) {
        Some(Some(b)) => {
            let n = b.len() as i32;
            put(b);
            n
        }
        _ => -1,
    })
}

// What the loop did: attempts, fetch rounds, bytes requests, requests, source reads, bytes fetched, input bytes (OUT: 7 i32, the
// last two the low 32 bits).
#[no_mangle]
pub extern "C" fn emon_stats(h: i32) -> i32 {
    guard(|| match session(h, |s| s.source_stats()) {
        Some((reads, bytes)) => {
            put_i32s(&[reads as i32, bytes as i32]);
            0
        }
        None => -1,
    })
}

// The transcript a recording session made: OUT is [count] then per file [name length, name, data length, data], all lengths u32.
#[no_mangle]
pub extern "C" fn emon_transcript(h: i32) -> i32 {
    guard(|| {
        let r = session(h, |s| s.transcript());
        match r {
            Some(Some(files)) => {
                let mut v: Vec<u8> = Vec::new();
                v.extend_from_slice(&(files.len() as u32).to_le_bytes());
                for (n, d) in files {
                    v.extend_from_slice(&(n.len() as u32).to_le_bytes());
                    v.extend_from_slice(n.as_bytes());
                    v.extend_from_slice(&(d.len() as u32).to_le_bytes());
                    v.extend_from_slice(&d);
                }
                put(v);
                0
            }
            _ => -1,
        }
    })
}

// The end of a conversion: a Model handle (> 0) or the error. The session is spent.
#[no_mangle]
pub extern "C" fn emon_finish(h: i32) -> i32 {
    guard(|| match session(h, |s| s.finish()) {
        Some(Ok((m, st))) => {
            put_i32s(&[st.attempts, st.fetch_rounds, st.bytes_requests, st.requests, st.source_reads as i32, st.bytes_fetched as i32, st.input_bytes as i32]);
            world(|w| {
                w.models.push(Some(Box::new(m)));
                w.models.len() as i32
            })
        }
        Some(Err(Fault::Engine(e))) => put_error(&e),
        // the session was finished already: nothing to say
        Some(Err(Fault::Usage(_))) | None => -1,
    })
}

// The end of an intake: OUT is the driver's line `FORMAT MAJOR.MINOR pages=N encrypted=B bytes=N`.
#[no_mangle]
pub extern "C" fn emon_finish_intake(h: i32) -> i32 {
    guard(|| match session(h, |s| s.finish_intake()) {
        Some(Ok(line)) => {
            put_str(&line);
            0
        }
        Some(Err(Fault::Engine(e))) => put_error(&e),
        Some(Err(Fault::Usage(_))) | None => -1,
    })
}

#[no_mangle]
pub extern "C" fn emon_close(h: i32) {
    world(|w| {
        if let Some(s) = w.sessions.get_mut((h - 1) as usize) {
            *s = None;
        }
    });
}

// ------------------------------------------------------------------------------------------------ the Model

fn model<T>(h: i32, f: impl FnOnce(&Model) -> T) -> Option<T> {
    // the Model is taken out of the table for the call: its renderers are long and must not hold the table
    let m = world(|w| w.models.get_mut((h - 1) as usize).and_then(|m| m.take()));
    let m = m?;
    let r = catch_unwind(AssertUnwindSafe(|| f(&m)));
    world(|w| {
        if let Some(slot) = w.models.get_mut((h - 1) as usize) {
            *slot = Some(m);
        }
    });
    match r {
        Ok(v) => Some(v),
        Err(p) => std::panic::resume_unwind(p),
    }
}

#[no_mangle]
pub extern "C" fn emon_model_json(h: i32, pretty: u32) -> i32 {
    guard(|| match model(h, |m| m.to_json(&JsonOptions { pretty: pretty != 0 })) {
        Some(s) => {
            put(s.into_bytes());
            0
        }
        None => -1,
    })
}

#[no_mangle]
pub unsafe extern "C" fn emon_model_html(h: i32, opts_ptr: u32, opts_len: u32) -> i32 {
    guard(|| {
        let o = match shared::parse_html(text(opts_ptr, opts_len)) {
            Ok(o) => o,
            Err(m) => {
                put_str(&format!("usage\n{}", m));
                return -1;
            }
        };
        match model(h, |m| m.to_html(&o)) {
            Some(s) => {
                // the String's own buffer is the output: an inline-asset HTML is tens of MiB and is not copied
                put(s.into_bytes());
                0
            }
            None => -1,
        }
    })
}

// The text layer's evidence dump when the session asked to keep it (OUT empty when it did not).
#[no_mangle]
pub extern "C" fn emon_model_evidence(h: i32) -> i32 {
    guard(|| match model(h, |m| m.evidence().unwrap_or("").to_string()) {
        Some(s) => {
            put_str(&s);
            0
        }
        None => -1,
    })
}

#[no_mangle]
pub extern "C" fn emon_model_asset_count(h: i32) -> i32 {
    guard(|| model(h, |m| m.assets().len() as i32).unwrap_or(-1))
}

// Asset `i`'s file name, media type and size in bytes (OUT: `name\ntype\nsize`) and, with `emon_model_asset_bytes`, its encoded bytes.
#[no_mangle]
pub extern "C" fn emon_model_asset_info(h: i32, i: i32) -> i32 {
    guard(|| match model(h, |m| m.asset(i).map(|a| format!("{}\n{}\n{}", a.name(), a.media_type(), a.len()))) {
        Some(Some(s)) => {
            put_str(&s);
            0
        }
        _ => -1,
    })
}

#[no_mangle]
pub extern "C" fn emon_model_asset_bytes(h: i32, i: i32) -> i32 {
    guard(|| match model(h, |m| m.asset(i).map(|a| a.bytes())) {
        Some(Some(b)) => {
            let n = b.len() as i32;
            put(b);
            n
        }
        _ => -1,
    })
}

#[no_mangle]
pub extern "C" fn emon_model_free(h: i32) {
    world(|w| {
        if let Some(m) = w.models.get_mut((h - 1) as usize) {
            *m = None;
        }
    });
}
