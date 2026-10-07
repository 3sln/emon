// THE WASM BINDING (DEVLOG P9.3, SPEC 13.1, 7.3; D058 revised, D089, D580-D585). The Rust host built for `wasm32-unknown-unknown`
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
// (`js-ocr`), as SPEC 7.3 says: the JS host owns it. `render` and `crop` would be the JS host's too, through pdfium's
// WebAssembly build (SPEC 7.3: not built); they, and `ocr` from a host with no engine, are answered `unsupported`, which is an
// answer (D199): the page then has no text from it.
//
// MEMORY. Everything crosses as integers and byte ranges of linear memory; the host allocates with `emon_alloc` and frees with
// `emon_free`. Results are written to ONE shared output buffer (`emon_out_ptr`, `emon_out_len`) that the next call overwrites.
#![allow(clippy::missing_safety_doc)]

use crate::emon;
use crate::engine::{Assets, ChunkCache, Config, Engine, Error, Furniture, HtmlAssets, HtmlOptions, JsonOptions, Model, OcrPolicy, Ocr, OcrPort, Options, Run};
use crate::plan::{self, Answer, Bitmaps, PlanError, Range, Replayer, Request, Transcript};
use crate::port::{Bitmap, Cells};
use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Arc;

// ------------------------------------------------------------------------------------------------ the world

struct Session {
    cache: Arc<ChunkCache>,
    run: Option<Run>,
    idx: usize,
    js_ocr: bool,
    // replay: the files being collected, then the replayer; record: the transcript made
    files: BTreeMap<String, Vec<u8>>,
    replay: bool,
    replayer: Option<Replayer>,
    recording: Option<Transcript>,
    local: Box<dyn FnMut(&Request, &Bitmaps) -> Result<Answer, PlanError>>,
    pending: Option<Pending>,
    done: bool,
}

// One round of requests the unit asked for: the answers made so far (by id) and the ones still owed by the host.
struct Pending {
    reqs: Vec<Request>,
    all: Bitmaps,
    answers: BTreeMap<i32, Answer>,
    external: BTreeSet<i32>,
}

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

// A panic that is not kin's (kin's are caught inside the loop and named): a bug in this file.
fn guard(f: impl FnOnce() -> i32) -> i32 {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(p) => {
            let m = if let Some(s) = p.downcast_ref::<String>() {
                s.clone()
            } else if let Some(s) = p.downcast_ref::<&str>() {
                s.to_string()
            } else {
                "a panic".to_string()
            };
            put_str(&format!("internal\n{}", m));
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
    match i {
        0 => plan::KIND_RENDER,
        1 => plan::KIND_DECODE,
        2 => plan::KIND_OCR,
        3 => plan::KIND_BYTES,
        4 => plan::KIND_CROP,
        5 => plan::KIND_DECODE_IMG,
        10 => plan::STATUS_OK,
        11 => plan::STATUS_FAILED,
        12 => emon::plan::ps_unsupported(),
        13 => emon::plan::ps_limit(),
        14 => emon::ocr::ocw_dict(),
        15 => emon::ocr::ocw_numeric(),
        _ => -1,
    }
}

// ------------------------------------------------------------------------------------------------ options

struct Parsed {
    opts: Options,
    cfg: Config,
    record: bool,
    producer: Vec<(String, String)>,
    js_ocr: bool,
}

// Limits are named as `Config::limits` kinds are numbered (`port::limit_spec`).
fn limit_kind(name: &str) -> Option<i32> {
    Some(match name {
        "input-bytes" => 0,
        "stream-bytes" => 1,
        "total-bytes" => 2,
        "objects" => 3,
        "ref-depth" => 4,
        "operators" => 5,
        "form-depth" => 6,
        "pattern-depth" => 7,
        "pages" => 8,
        "pixels" => 9,
        "ocr-requests" => 10,
        _ => return None,
    })
}

// `key=value` lines: the settings of SPEC 13 by their names, the limits as `limit.NAME=N`, the loop's bounds, the ports the host has.
fn parse_options(t: &str, chunk: usize) -> Result<Parsed, String> {
    let mut p = Parsed { opts: Options::default(), cfg: Config::default(), record: false, producer: Vec::new(), js_ocr: false };
    p.cfg.chunk_size = chunk;
    let num = |k: &str, v: &str| -> Result<i32, String> { v.parse::<i32>().map_err(|_| format!("{} wants a number, not {:?}", k, v)) };
    for line in t.lines().filter(|l| !l.is_empty()) {
        let (k, v) = line.split_once('=').ok_or_else(|| format!("option {:?} has no value", line))?;
        match k {
            "locale" => p.opts.locale = Some(v.to_string()),
            "ocr" => {
                p.opts.ocr = match v {
                    "always" => OcrPolicy::Always,
                    "auto" => OcrPolicy::Auto,
                    "never" => OcrPolicy::Never,
                    _ => return Err("ocr is always, auto or never".to_string()),
                }
            }
            "furniture" => {
                p.opts.furniture = match v {
                    "policy" => Furniture::Policy,
                    "keep" => Furniture::Keep,
                    "drop" => Furniture::Drop,
                    _ => return Err("furniture is policy, keep or drop".to_string()),
                }
            }
            "assets" => {
                p.opts.assets = match v {
                    "none" => Assets::None,
                    "images" => Assets::Images,
                    "figures" => Assets::Figures,
                    _ => return Err("assets is none, images or figures".to_string()),
                }
            }
            "asset-dpi" => p.opts.asset_dpi = num(k, v)?,
            "asset-pixels" => p.opts.asset_pixels = num(k, v)?,
            "asset-bytes" => p.opts.asset_bytes = num(k, v)?,
            "assets-bytes" => p.opts.assets_bytes = num(k, v)?,
            "asset-signatures" => p.opts.asset_signatures = v == "on",
            "keep-evidence" => p.opts.keep_evidence = v == "1",
            "ocr-born-digital" => p.opts.ocr_born_digital = v == "1",
            "max-rounds" => p.cfg.max_rounds = num(k, v)?,
            "fetch-rounds" => p.cfg.fetch_rounds = num(k, v)?,
            "record" => p.record = v == "1",
            "js-ocr" => p.js_ocr = v == "1",
            _ => {
                if let Some(n) = k.strip_prefix("limit.") {
                    let kind = limit_kind(n).ok_or_else(|| format!("no limit named {}", n))?;
                    p.cfg.limits.push((kind, num(k, v)?));
                } else if let Some(n) = k.strip_prefix("producer.") {
                    p.producer.push((n.to_string(), v.to_string()));
                } else {
                    return Err(format!("unknown option {}", k));
                }
            }
        }
    }
    Ok(p)
}

// The same list as the drivers' `--html=`.
fn parse_html(list: &str) -> Result<HtmlOptions, String> {
    let mut o = HtmlOptions::default();
    for item in list.split(',').filter(|s| !s.is_empty()) {
        match item {
            "ids" => o.ids = true,
            "geometry" => o.geometry = true,
            "ledger" => o.ledger = true,
            "pretty" => o.pretty = true,
            "drop-furniture" => o.furniture_drop = true,
            "no-milestones" => o.milestones = false,
            "initials-plain" => o.initials_plain = true,
            "align-style" => o.align_style = true,
            "assets=inline" => o.assets = HtmlAssets::Inline,
            "assets=external" => o.assets = HtmlAssets::External,
            "assets=omit" => o.assets = HtmlAssets::Omit,
            other => match (other.strip_prefix("confidence="), other.strip_prefix("prefix=")) {
                (Some(n), _) => o.confidence_below = Some(n.parse().map_err(|_| "confidence=N wants a number".to_string())?),
                (_, Some(p)) => o.asset_prefix = p.to_string(),
                _ => return Err(format!("unknown html option {}", other)),
            },
        }
    }
    Ok(o)
}

// ------------------------------------------------------------------------------------------------ sessions

// The `ocr` port of a session whose host has an engine: its presence makes the decode answers keep their pixels (the engine
// reads them); it is never asked, the requests go out to the host (`emon_step` status 2).
struct HostOcr;

impl Ocr for HostOcr {
    fn fulfil(&self, _req: &Request, _bitmaps: &Bitmaps, _keep_binary: bool) -> Result<Answer, PlanError> {
        Err(PlanError::Fulfil("an ocr request is the host's to fulfil".to_string()))
    }
}

// Open a session over an input of `len` bytes (a number: an input over 2^32 is refused by name, not wrapped), read in chunks of
// `chunk` bytes. `mode`: 0 convert, 1 intake only. Answers a handle (> 0) or -1 with the error in OUT.
#[no_mangle]
pub unsafe extern "C" fn emon_open(len: f64, chunk: u32, mode: u32, password_ptr: u32, password_len: u32, opts_ptr: u32, opts_len: u32) -> i32 {
    guard(|| {
        let p = match parse_options(text(opts_ptr, opts_len), if chunk == 0 { 65536 } else { chunk as usize }) {
            Ok(p) => p,
            Err(m) => {
                put_str(&format!("usage\n{}", m));
                return -1;
            }
        };
        let password = if password_len > 0 { Some(bytes(password_ptr, password_len).to_vec()) } else { None };
        // an input longer than a `Src` can address is refused by the engine's own check against the LENGTH; `u32::MAX` stands for
        // every length that does not fit, which the check refuses too
        let ulen = if len > i32::MAX as f64 { i32::MAX as usize + 1 } else { len as usize };
        let cache = ChunkCache::pushed(ulen, p.cfg.chunk_size);
        let engine = Engine::new(p.cfg.clone());
        let idx = 0usize;
        let run = match engine.start_pushed(idx, cache.clone(), password, &p.opts, mode == 1) {
            Ok(r) => r,
            Err(e) => return put_error(&e),
        };
        let keep = p.record || p.js_ocr;
        let ocr = if p.js_ocr { Some(OcrPort(Arc::new(HostOcr))) } else { None };
        let source: Arc<dyn crate::source::ByteSource> = cache.clone();
        let local = Box::new(crate::engine::port_fulfiller(source, idx, keep, p.record, ocr, None));
        let mut recording = None;
        if p.record {
            let mut t = Transcript::default();
            t.inputs = vec![(idx as i32, ulen as i32)];
            t.producer = p.producer.clone();
            recording = Some(t);
        }
        let s = Session { cache, run: Some(run), idx, js_ocr: p.js_ocr, files: BTreeMap::new(), replay: false, replayer: None, recording, local, pending: None, done: false };
        world(|w| {
            w.sessions.push(Some(Box::new(s)));
            w.sessions.len() as i32
        })
    })
}

// SPEC 13.1: intake round 0 reads the first and the last chunk. OUT: pairs [offset length]; answers how many.
#[no_mangle]
pub extern "C" fn emon_prefetch(h: i32) -> i32 {
    guard(|| match session(h, |s| s.cache.prefetch_ranges(s.idx as i32)) {
        Some(r) => {
            put_ranges(&r);
            r.len() as i32
        }
        None => -1,
    })
}

fn put_ranges(r: &[Range]) {
    let mut v = Vec::new();
    for x in r {
        v.push(x.offset);
        v.push(x.length);
    }
    put_i32s(&v);
}

// The bytes at `offset` the host read. Chunks they cover whole become resident.
#[no_mangle]
pub unsafe extern "C" fn emon_provide(h: i32, offset: u32, ptr: u32, len: u32) -> i32 {
    guard(|| {
        let data = bytes(ptr, len);
        match session(h, |s| s.cache.provide(offset as usize, data)) {
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
        match session(h, |s| s.cache.fail(short != 0, &m)) {
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
        match session(h, |s| {
            s.files.insert(name, data);
        }) {
            Some(()) => 0,
            None => -1,
        }
    })
}

#[no_mangle]
pub extern "C" fn emon_replay_end(h: i32) -> i32 {
    guard(|| {
        let r = session(h, |s| -> Result<(), Error> {
            let t = Transcript::from_files(&s.files).map_err(Error::Plan)?;
            t.check_inputs(&[(s.idx as i32, s.cache.length() as i32)]).map_err(Error::Plan)?;
            s.replayer = Some(Replayer::new(&t));
            s.replay = true;
            s.files = BTreeMap::new();
            Ok(())
        });
        match r {
            Some(Ok(())) => 0,
            Some(Err(e)) => put_error(&e),
            None => -1,
        }
    })
}

// The spans of the input a request names: `decode` (input offset length ...) and `decode-img`'s codestream and JBIG2 globals,
// when they are the input's own (`where` is the input's index).
fn spans(req: &Request, idx: usize) -> Vec<(usize, u64, u64)> {
    let mut v = Vec::new();
    let me = idx as i32;
    if req.kind() == plan::KIND_DECODE {
        if req.arg(0) == me && req.arg(1) >= 0 && req.arg(2) > 0 {
            v.push((0, req.arg(1) as u64, req.arg(2) as u64));
        }
    } else if req.kind() == plan::KIND_DECODE_IMG {
        for at in [9usize, 20] {
            if req.arg(at) == me && req.arg(at + 1) >= 0 && req.arg(at + 2) > 0 {
                v.push((0, req.arg(at + 1) as u64, req.arg(at + 2) as u64));
            }
        }
    }
    v
}

// The requests the host fulfils: `ocr`, when it has an engine. (`render` and `crop` would be pdfium's WebAssembly build, SPEC 7.3:
// not built, so they are answered `unsupported` here like any request no port is configured for.)
fn is_external(s: &Session, kind: i32) -> bool {
    kind == plan::KIND_OCR && s.js_ocr
}

// Walk the session on: 0 done, 1 bytes needed (OUT: pairs), 2 requests for the host (OUT: count, then n and n cells each), -1 error.
fn step(s: &mut Session) -> Result<(i32, Vec<u8>), Error> {
    loop {
        if let Some(mut p) = s.pending.take() {
            let run = s.run.as_mut().expect("a pending round has a run");
            if let Some(e) = run.source_error() {
                return Err(e);
            }
            if s.replay {
                // the whole round from the transcript: a request it has no answer for is `transcript-miss`
                let r = s.replayer.as_mut().expect("a replay has a replayer");
                let got = plan::fulfil(r, &p.reqs, &p.all).map_err(|e| run.error_of(e))?;
                let entries = run.answered(got);
                if let Some(t) = s.recording.as_mut() {
                    t.entries.extend(entries);
                }
                continue;
            }
            // 1. the input bytes the local requests read
            let local: Vec<&Request> = p.reqs.iter().filter(|r| !p.answers.contains_key(&r.id()) && !p.external.contains(&r.id())).collect();
            let mut want: Vec<(usize, u64, u64)> = Vec::new();
            for r in &local {
                want.extend(spans(r, s.idx));
            }
            let missing = {
                let mut out: Vec<Range> = Vec::new();
                for &(_, off, len) in &want {
                    for r in s.cache.missing(s.idx as i32, off, len) {
                        if !out.contains(&r) {
                            out.push(r);
                        }
                    }
                }
                out.sort_by_key(|r| r.offset);
                out
            };
            if !missing.is_empty() {
                let mut v = Vec::new();
                for x in &missing {
                    v.push(x.offset);
                    v.push(x.length);
                }
                s.pending = Some(p);
                return Ok((1, v.iter().flat_map(|x| x.to_le_bytes()).collect()));
            }
            // 2. the local requests, answered here
            let ids: Vec<i32> = local.iter().map(|r| r.id()).collect();
            for id in ids {
                let req = p.reqs.iter().find(|r| r.id() == id).unwrap().clone();
                let a = (s.local)(&req, &p.all).map_err(|e| run.error_of(e))?;
                p.answers.insert(id, a);
            }
            // 3. what the host still owes
            let owed: Vec<&Request> = p.reqs.iter().filter(|r| p.external.contains(&r.id()) && !p.answers.contains_key(&r.id())).collect();
            if !owed.is_empty() {
                let mut v: Vec<u8> = Vec::new();
                v.extend_from_slice(&(owed.len() as i32).to_le_bytes());
                for r in owed {
                    v.extend_from_slice(&(r.rec.len() as i32).to_le_bytes());
                    for c in &r.rec {
                        v.extend_from_slice(&c.to_le_bytes());
                    }
                }
                s.pending = Some(p);
                return Ok((2, v));
            }
            // 4. the round is answered: the unit is re-run on the answers
            let got: Vec<Answer> = p.reqs.iter().map(|r| p.answers.remove(&r.id()).unwrap()).collect();
            let entries = run.answered(got);
            if let Some(t) = s.recording.as_mut() {
                t.entries.extend(entries);
            }
            continue;
        }
        let run = s.run.as_mut().expect("a session that is not done has a run");
        match run.step()? {
            plan::Next::Done => {
                // a replay that left recorded entries unasked is a transcript of another run, as it is natively
                if let Some(r) = &s.replayer {
                    if r.unused() != 0 {
                        return Err(Error::Plan(PlanError::TranscriptBad(format!("{} recorded entries were never asked for", r.unused()))));
                    }
                }
                return Ok((0, Vec::new()));
            }
            plan::Next::Fetch(reqs) => {
                let mut v = Vec::new();
                for r in &reqs {
                    v.push(r.arg(1));
                    v.push(r.arg(2));
                }
                return Ok((1, v.iter().flat_map(|x| x.to_le_bytes()).collect()));
            }
            plan::Next::Requests(reqs, all) => {
                let mut external = BTreeSet::new();
                for r in &reqs {
                    if !s.replay && is_external(s, r.kind()) {
                        external.insert(r.id());
                    }
                }
                s.pending = Some(Pending { reqs, all, answers: BTreeMap::new(), external });
            }
        }
    }
}

#[no_mangle]
pub extern "C" fn emon_step(h: i32) -> i32 {
    guard(|| {
        let r = session(h, |s| {
            if s.done {
                return Err(Error::Internal("the session is finished".to_string()));
            }
            let r = step(s);
            if r.is_err() || matches!(r, Ok((0, _))) {
                s.done = true;
            }
            r
        });
        match r {
            Some(Ok((status, out))) => {
                put(out);
                status
            }
            Some(Err(e)) => put_error(&e),
            None => -1,
        }
    })
}

// A request the host was owed, answered by a raw record (`[len kind id status ...]`; a failed or unsupported one is four cells).
#[no_mangle]
pub unsafe extern "C" fn emon_answer(h: i32, ptr: u32, ncells: u32) -> i32 {
    guard(|| {
        let b = bytes(ptr, ncells * 4);
        let rec: Vec<i32> = b.chunks_exact(4).map(|c| i32::from_le_bytes([c[0], c[1], c[2], c[3]])).collect();
        answer(h, rec, None)
    })
}

fn answer(h: i32, rec: Vec<i32>, bitmap: Option<Arc<Bitmap>>) -> i32 {
    let r = session(h, |s| -> Result<(), String> {
        if rec.len() < 4 || rec[0] as usize != rec.len() {
            return Err("an answer is a record [len kind id status ...]".to_string());
        }
        let p = s.pending.as_mut().ok_or("no requests are owed")?;
        let id = rec[2];
        if !p.external.contains(&id) || p.answers.contains_key(&id) {
            return Err(format!("request {} is not owed an answer", id));
        }
        p.answers.insert(id, Answer { rec, bitmap });
        Ok(())
    });
    match r {
        Some(Ok(())) => 0,
        Some(Err(m)) => {
            put_str(&format!("usage\n{}", m));
            -1
        }
        None => -1,
    }
}

// An `ocr` answer from the flat integer form the JS adapter makes of its engine's reading; the record is written by the kin
// builders (`emon.plan`, `emon.ocr`), so its layout is written once. The form, all cells:
//   header  orient wdir tlo deskew osd script sconf oconf nlines nblocks nwords has_binary bw bh
//   lines   x y w h  bx1 by1 bx2 by2  block par btype  rowa rowb rowc                     (nlines times)
//   blocks  x y w h  orient wdir tlo                                                      (nblocks times)
//   words   x y w h conf line block flags lang psize dflags  ntext cp...  nsyms  syms...  (nwords times)
//   sym     x y w h conf  ncp cp...  nalts  (conf ncp cp...)...
#[no_mangle]
pub unsafe extern "C" fn emon_answer_ocr(h: i32, id: i32, ptr: u32, ncells: u32) -> i32 {
    guard(|| {
        let b = bytes(ptr, ncells * 4);
        let v: Vec<i32> = b.chunks_exact(4).map(|c| i32::from_le_bytes([c[0], c[1], c[2], c[3]])).collect();
        match build_ocr(id, &v) {
            Some(rec) => answer(h, rec, None),
            None => {
                put_str("usage\nthe ocr form is malformed");
                -1
            }
        }
    })
}

struct Cur<'a> {
    v: &'a [i32],
    at: usize,
}

impl<'a> Cur<'a> {
    fn n(&mut self) -> Option<i32> {
        let x = *self.v.get(self.at)?;
        self.at += 1;
        Some(x)
    }
    fn take(&mut self, k: usize) -> Option<&'a [i32]> {
        let s = self.v.get(self.at..self.at + k)?;
        self.at += k;
        Some(s)
    }
}

fn build_ocr(id: i32, v: &[i32]) -> Option<Vec<i32>> {
    let mut c = Cur { v, at: 0 };
    let hd = c.take(14)?;
    let (orient, wdir, tlo, deskew, osd, script, sconf, oconf, nlines, nblocks, nwords, has_binary, bw, bh) = (hd[0], hd[1], hd[2], hd[3], hd[4], hd[5], hd[6], hd[7], hd[8], hd[9], hd[10], hd[11], hd[12], hd[13]);
    if nlines < 0 || nblocks < 0 || nwords < 0 {
        return None;
    }
    let mut lines: Vec<&[i32]> = Vec::new();
    for _ in 0..nlines {
        lines.push(c.take(14)?);
    }
    let mut blocks: Vec<&[i32]> = Vec::new();
    for _ in 0..nblocks {
        blocks.push(c.take(7)?);
    }
    struct Alt<'a> {
        conf: i32,
        cps: &'a [i32],
    }
    struct Sym<'a> {
        bx: &'a [i32],
        cps: &'a [i32],
        alts: Vec<Alt<'a>>,
    }
    struct W<'a> {
        h: &'a [i32],
        text: &'a [i32],
        syms: Vec<Sym<'a>>,
    }
    let mut words: Vec<W> = Vec::new();
    for _ in 0..nwords {
        let h = c.take(11)?;
        let nt = c.n()?;
        let text = c.take(nt.max(0) as usize)?;
        let ns = c.n()?;
        let mut syms = Vec::new();
        for _ in 0..ns.max(0) {
            let bx = c.take(5)?;
            let ncp = c.n()?;
            let cps = c.take(ncp.max(0) as usize)?;
            let na = c.n()?;
            let mut alts = Vec::new();
            for _ in 0..na.max(0) {
                let conf = c.n()?;
                let nac = c.n()?;
                let cps = c.take(nac.max(0) as usize)?;
                alts.push(Alt { conf, cps });
            }
            syms.push(Sym { bx, cps, alts });
        }
        words.push(W { h, text, syms });
    }
    let mut cells = Cells::new();
    let at = emon::plan::an_ocr(&mut cells, id);
    for w in &words {
        let wd = emon::plan::an_ocr_word(&mut cells, at, w.h[0], w.h[1], w.h[2], w.h[3], w.h[4], w.h[5], w.h[6]);
        for cp in w.text {
            emon::plan::an_ocr_cp(&mut cells, wd, *cp);
        }
    }
    emon::ocr::ox_begin(&mut cells, orient, wdir, tlo, deskew, osd, script, sconf, oconf, nlines, nblocks, has_binary, bw, bh);
    for l in &lines {
        emon::ocr::ox_line(&mut cells, l[0], l[1], l[2], l[3], l[4], l[5], l[6], l[7], l[8], l[9], l[10], l[11], l[12], l[13]);
    }
    for b in &blocks {
        emon::ocr::ox_block(&mut cells, b[0], b[1], b[2], b[3], b[4], b[5], b[6]);
    }
    for w in &words {
        let wx = emon::ocr::ox_word(&mut cells, w.h[7], w.h[8], w.h[9], w.h[10]);
        for s in &w.syms {
            let sx = emon::ocr::ox_sym(&mut cells, wx, s.bx[0], s.bx[1], s.bx[2], s.bx[3], s.bx[4]);
            for cp in s.cps {
                emon::ocr::ox_sym_cp(&mut cells, sx, *cp);
            }
            for a in &s.alts {
                let ax = emon::ocr::ox_alt(&mut cells, sx, a.conf);
                for cp in a.cps {
                    emon::ocr::ox_alt_cp(&mut cells, ax, *cp);
                }
            }
        }
    }
    emon::plan::an_ocr_end(&mut cells, at);
    Some((0..crate::port::cells_len(&cells)).map(|i| crate::port::cells_at(&cells, i)).collect())
}

// The bitmap a request names (an `ocr` request's `bitmap` argument, or the answer of any request already answered), as a
// canonical PNG in OUT: what the host's recogniser reads. Answers the PNG's length, or -1.
#[no_mangle]
pub extern "C" fn emon_bitmap_png(h: i32, id: i32) -> i32 {
    guard(|| {
        let png = session(h, |s| s.pending.as_ref().and_then(|p| p.all.get(&id).map(|b| plan::png_encode(b))));
        match png {
            Some(Some(b)) => {
                let n = b.len() as i32;
                put(b);
                n
            }
            _ => -1,
        }
    })
}

// What the loop did: attempts, fetch rounds, bytes requests, requests, source reads, bytes fetched, input bytes (OUT: 7 i32, the
// last two the low 32 bits).
#[no_mangle]
pub extern "C" fn emon_stats(h: i32) -> i32 {
    guard(|| match session(h, |s| (s.cache.reads(), s.cache.bytes_read())) {
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
        let r = session(h, |s| s.recording.as_ref().map(|t| t.to_files()));
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
    guard(|| {
        let run = session(h, |s| s.run.take()).flatten();
        let Some(run) = run else { return -1 };
        match run.into_model() {
            Ok((m, st)) => {
                put_i32s(&[st.attempts, st.fetch_rounds, st.bytes_requests, st.requests, st.source_reads as i32, st.bytes_fetched as i32, st.input_bytes as i32]);
                world(|w| {
                    w.models.push(Some(Box::new(m)));
                    w.models.len() as i32
                })
            }
            Err(e) => put_error(&e),
        }
    })
}

// The end of an intake: OUT is the driver's line `FORMAT MAJOR.MINOR pages=N encrypted=B bytes=N`.
#[no_mangle]
pub extern "C" fn emon_finish_intake(h: i32) -> i32 {
    guard(|| {
        let run = session(h, |s| s.run.take()).flatten();
        let Some(run) = run else { return -1 };
        match run.into_intake() {
            Ok((i, _)) => {
                put_str(&format!("{} {}.{} pages={} encrypted={} bytes={}", i.format, i.version.0, i.version.1, i.pages, i.encrypted, i.bytes));
                0
            }
            Err(e) => put_error(&e),
        }
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
        let o = match parse_html(text(opts_ptr, opts_len)) {
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
