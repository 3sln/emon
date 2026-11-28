// THE STEP SESSION, shared by the bindings (DEVLOG D481; D396, D472). The inverted API of SPEC 13.1 -- the caller owns the byte source
// and the fulfilment, the engine never calls out -- is one state machine over `engine::Run` (and the `plan::Machine` inside it): open,
// prefetch, step, provide, fail, answer, answer_ocr, bitmap, replay, transcript, finish. The browser binding (`host/wasm/src/wasm.rs`)
// and the C binding (`host/c/src/capi.rs`) were two copies of it (D472); each is now only its ABI layer over THIS:
//
//     wasm   linear-memory pointers and integers, one shared output buffer, the exports the JS SDK calls
//     C      `emon.h`'s types and explicit frees, the panic catch at the boundary, the 32 MiB worker thread
//
// WHAT IS HERE: everything that decides an answer -- the input-bytes check (BEFORE a cache of the input's chunks is allocated), which
// requests are the caller's (`pl-route`, kin's), which bytes are missing (`pl-spans`, kin's), replay, recording, the order of a round --
// and the stateless helpers both bindings export (the constants table, the `--html=` list, the ocr policy calls, the intake line).
// WHAT IS NOT: how a result crosses the ABI, and on which thread a call runs (this file spawns none: the C binding calls it on its
// worker). The native crate compiles it too (it names no filesystem and no C): `bb unit rust` holds it.
use crate::emon;
use crate::engine::{parse_options, ChunkCache, Engine, Error, HtmlAssets, HtmlOptions, Intake, Model, Ocr, OcrPort, Run, RunStats};
use crate::plan::{self, Answer, Bitmaps, PlanError, Range, Replayer, Request, Transcript};
use crate::port::Cells;
use crate::source::ByteSource;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;
#[cfg(not(target_arch = "wasm32"))]
use std::{collections::{HashMap, HashSet}, sync::Mutex};
#[cfg(not(target_arch = "wasm32"))]
use crate::adapter::{Begun, Completion, Engines, Mailbox, Router};

// What a session call can fail with: the engine's own error, or the caller's mistake (`usage`).
pub enum Fault {
    Engine(Error),
    Usage(String),
}

impl From<Error> for Fault {
    fn from(e: Error) -> Fault {
        Fault::Engine(e)
    }
}

// What `step` says the caller must do next.
pub enum Step {
    // the run is over: `finish` / `finish_intake`
    Done,
    // bytes the engine has not been given: (offset, length) ranges to read and `provide`
    Bytes(Vec<(u64, u64)>),
    // requests the caller owes (`ocr`, with `js-ocr=1`; `render` and `crop`, with `js-render=1`): each a record `[len kind id arg...]`
    Requests(Vec<Vec<i32>>),
    // only async adapters' work is outstanding (K2, D899): wait for a completion (`Completer::wait`) and step again
    #[cfg(not(target_arch = "wasm32"))]
    Waiting,
}

// The `ocr` port of a session whose caller has an engine: its presence makes the decode answers keep their pixels (the engine reads
// them); it is never asked, the requests go out to the caller (`Step::Requests`).
struct HostOcr;

impl Ocr for HostOcr {
    fn fulfil(&self, _req: &Request, _bitmaps: &Bitmaps, _keep_binary: bool) -> Result<Answer, PlanError> {
        Err(PlanError::Fulfil("an ocr request is the caller's to fulfil".to_string()))
    }
}

pub struct Session {
    cache: Arc<ChunkCache>,
    run: Option<Run>,
    idx: usize,
    js_ocr: bool,
    ocr_engine: crate::engine::OcrEngine,
    // `js-render=1` (P11.4c): the `render` and `crop` requests are the caller's too
    js_render: bool,
    // the conversion's `limit.NAME=N` overrides: the caller asks a bound it must keep (`limit`, the pixels of a bitmap it answers with)
    limits: Vec<(i32, i32)>,
    // replay: the files being collected, then the replayer; record: the transcript made
    files: BTreeMap<String, Vec<u8>>,
    replay: bool,
    replayer: Option<Replayer>,
    recording: Option<Transcript>,
    local: Box<dyn FnMut(&Request, &Bitmaps) -> Result<Answer, PlanError>>,
    // the registry's renderer (pdfium), when the engines have one and the caller does not render (`js-render=1`): the session answers `render` and `crop` itself (K7). It reads the input
    // through the session's chunk cache, so the caller gives the whole input (every chunk) before it steps.
    #[cfg(not(target_arch = "wasm32"))]
    render: Option<crate::render::RenderFulfiller>,
    // a round of requests is in progress (the Run holds it: its requests, the answers offered so far, the bitmaps), and the ids of
    // those that are the caller's to answer
    in_round: bool,
    external: BTreeSet<i32>,
    done: bool,
    // the registry's OCR adapters (K2, D899): the session answers the requests they are registered for itself; what they answer later arrives in the mailbox
    #[cfg(not(target_arch = "wasm32"))]
    router: Option<Router>,
    #[cfg(not(target_arch = "wasm32"))]
    mailbox: Arc<Mailbox>,
    // token of an async adapter's work -> the request it is for
    #[cfg(not(target_arch = "wasm32"))]
    pending: HashMap<u64, i32>,
    #[cfg(not(target_arch = "wasm32"))]
    tokens: Arc<Mutex<HashSet<u64>>>,
}

// What may be done from any thread while a session steps (`emon_complete`, `emon_wait`): hand an async adapter's answer to the session, or wait for one.
#[derive(Clone)]
#[cfg(not(target_arch = "wasm32"))]
pub struct Completer {
    router: Option<Router>,
    mailbox: Arc<Mailbox>,
    tokens: Arc<Mutex<HashSet<u64>>>,
}

#[cfg(not(target_arch = "wasm32"))]
impl Completer {
    // The answer to the work `token` names (`Completion::Answer`: the OIL record; `Refused`: unsupported or failed, the page is unread). Err when the token is none of this session's.
    pub fn complete(&self, token: u64, c: Completion) -> Result<(), String> {
        let ours = self.tokens.lock().unwrap_or_else(|e| e.into_inner()).contains(&token);
        match (&self.router, ours) {
            (Some(r), true) if r.complete(token, c) => Ok(()),
            _ => Err(format!("token {} is not waited for by this session", token)),
        }
    }

    // Wait for a completion: true when one is here (step to take it), false when the time ran out.
    pub fn wait(&self, timeout: std::time::Duration) -> bool {
        self.mailbox.wait(timeout)
    }
}

impl Session {
    // Open a session over an input of `length` bytes. `chunk`: the size the caller reads in (None: the options' `chunk`). `intake_only`:
    // stage 0 alone (then `finish_intake`). `options`: kin's `key=value` text; `record=1` keeps a transcript, `js-ocr=1` makes the `ocr`
    // requests the caller's. The bound on the input's length is checked BEFORE the cache of its chunks is allocated: a chunk of 1 and a
    // length of 2 GiB would ask for gigabytes (the engine checks it again, against the same limit).
    pub fn open(length: u64, chunk: Option<usize>, intake_only: bool, password: Option<Vec<u8>>, options: &str) -> Result<Session, Fault> {
        Self::open_impl(length, chunk, intake_only, password, options, #[cfg(not(target_arch = "wasm32"))] None)
    }

    // `open`, with the engines an embedder registered (K2, D899): when they have OCR adapters the session answers the `ocr` requests those are registered for itself, and only
    // the bytes, the requests nobody registered for (with `js-ocr=1`) and WAITING come out of `step`.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn open_with(length: u64, chunk: Option<usize>, intake_only: bool, password: Option<Vec<u8>>, options: &str, engines: Option<&Engines>) -> Result<Session, Fault> {
        Self::open_impl(length, chunk, intake_only, password, options, engines)
    }

    fn open_impl(length: u64, chunk: Option<usize>, intake_only: bool, password: Option<Vec<u8>>, options: &str,
                 #[cfg(not(target_arch = "wasm32"))] engines: Option<&Engines>) -> Result<Session, Fault> {
        let mut p = parse_options(options).map_err(Fault::Usage)?;
        #[cfg(not(target_arch = "wasm32"))]
        let router = engines.and_then(|e| e.router_for(p.options.ocr_engine));
        if let Some(c) = chunk {
            p.config.chunk_size = c;
        }
        let limit = crate::port::with_limits(&p.config.limits, || crate::port::limit_of(0)) as i64;
        let limit = std::cmp::min(limit, i32::MAX as i64);
        if length > limit as u64 {
            return Err(Fault::Engine(Error::Limit { name: "input-bytes".to_string(), limit, actual: std::cmp::min(length, i64::MAX as u64) as i64 }));
        }
        let ulen = length as usize;
        let cache = ChunkCache::pushed(ulen, p.config.chunk_size.max(1));
        let engine = Engine::new(p.config.clone());
        let idx = 0usize;
        #[cfg(not(target_arch = "wasm32"))]
        let password_for_render = password.clone();
        let run = engine.start_pushed(idx, cache.clone(), password, &p.options, intake_only)?;
        let host_ocr = || p.js_ocr.then(|| OcrPort(Arc::new(HostOcr)));
        #[cfg(not(target_arch = "wasm32"))]
        let ocr = router.as_ref().map(|r| OcrPort(Arc::new(r.clone()))).or_else(host_ocr);
        #[cfg(target_arch = "wasm32")]
        let ocr = host_ocr();
        // the decode answers keep their pixels when something reads them: the caller's engine, or an adapter
        let keep = p.record || ocr.is_some();
        let source: Arc<dyn ByteSource> = cache.clone();
        #[cfg(not(target_arch = "wasm32"))]
        let render = engines.and_then(|e| e.renderer()).filter(|_| !p.js_render).map(|r| {
            crate::render::RenderFulfiller::for_input(r, idx, crate::render::RenderInput { source: source.clone(), password: password_for_render.clone() })
        });
        let local = Box::new(crate::engine::port_fulfiller(source, idx, keep, p.record, ocr, None));
        let mut recording = None;
        if p.record {
            let mut t = Transcript::default();
            t.inputs = vec![(idx as i32, ulen as i32)];
            t.producer = p.producer.clone();
            // the engines that answer say so in the transcript (`:producer`, and the engine table written first: D898), as the Engine's own recording does
            #[cfg(not(target_arch = "wasm32"))]
            if let Some(r) = &router {
                let mut prod = r.producer_pairs();
                for (k, v) in &p.producer {
                    if !prod.iter().any(|(k2, _)| k2 == k) {
                        prod.push((k.clone(), v.clone()));
                    }
                }
                t.engines = plan::engine_rows_for(&prod, p.options.ocr_engine.registry_engine());
                t.producer = prod;
            }
            recording = Some(t);
        }
        Ok(Session {
            cache,
            run: Some(run),
            idx,
            js_ocr: p.js_ocr,
            ocr_engine: p.options.ocr_engine,
            js_render: p.js_render,
            limits: p.config.limits.clone(),
            files: BTreeMap::new(),
            replay: false,
            replayer: None,
            recording,
            local,
            #[cfg(not(target_arch = "wasm32"))]
            render,
            in_round: false,
            external: BTreeSet::new(),
            done: false,
            #[cfg(not(target_arch = "wasm32"))]
            router,
            #[cfg(not(target_arch = "wasm32"))]
            mailbox: Mailbox::new(),
            #[cfg(not(target_arch = "wasm32"))]
            pending: HashMap::new(),
            #[cfg(not(target_arch = "wasm32"))]
            tokens: Arc::new(Mutex::new(HashSet::new())),
        })
    }

    // SPEC 13.1: intake round 0 reads the first and the last chunk. The ranges to read and `provide` first.
    pub fn prefetch(&self) -> Vec<(u64, u64)> {
        self.cache.prefetch_ranges(self.idx as i32).iter().map(|x| (x.offset as u64, x.length as u64)).collect()
    }

    // The bytes at `offset` the caller read. Chunks they cover whole become resident.
    pub fn provide(&self, offset: usize, data: &[u8]) {
        self.cache.provide(offset, data);
    }

    // The caller's read failed: `short` when it gave fewer bytes than the length said (the source changed: `source-changed`), else an
    // `io` error with the message.
    pub fn fail(&self, short: bool, message: &str) {
        self.cache.fail(short, message);
    }

    // What the source did: reads, bytes read.
    pub fn source_stats(&self) -> (u32, u64) {
        (self.cache.reads(), self.cache.bytes_read())
    }

    // REPLAY (SPEC 14.1.5): the transcript's files, one at a time, then `replay_end`. A transcript recorded on any host replays here.
    pub fn replay_file(&mut self, name: String, data: Vec<u8>) {
        self.files.insert(name, data);
    }

    pub fn replay_end(&mut self) -> Result<(), Error> {
        let t = Transcript::from_files(&self.files).map_err(Error::Plan)?;

        t.check_inputs(&[(self.idx as i32, self.cache.length() as i32)]).map_err(Error::Plan)?;
        self.replayer = Some(Replayer::new(&t));
        self.replay = true;
        self.files = BTreeMap::new();
        Ok(())
    }

    // The requests the caller fulfils (kin's `pl-route`): `ocr`, when it has an engine (`js-ocr=1`), and `render` and `crop`, when it has a
    // renderer (`js-render=1`, P11.4c). Without a renderer they are answered `unsupported` here like any request no port is configured for.
    fn is_external(&self, req: &Request) -> bool {
        // an `ocr` request an adapter is registered for is answered here, whatever `js-ocr` says: the caller owes what nobody registered for (K2, kin's `ad-route`)
        #[cfg(not(target_arch = "wasm32"))]
        if let Some(r) = &self.router {
            if r.claims(req) {
                return false;
            }
        }
        matches!(plan::route(req.kind(), self.js_ocr, self.js_render), plan::Route::Ocr | plan::Route::Render)
    }

    // What `emon_complete` and `emon_wait` use from any thread.
    #[cfg(not(target_arch = "wasm32"))]
    pub fn completer(&self) -> Completer {
        Completer { router: self.router.clone(), mailbox: self.mailbox.clone(), tokens: self.tokens.clone() }
    }

    // The value of limit `kind` in force for this conversion (the override, else kin's default): the caller reads the bound it must
    // keep, so the host never types a default (`limit.pixels` bounds a bitmap a renderer answers with, kind 9).
    pub fn limit(&self, kind: i32) -> i32 {
        crate::port::with_limits(&self.limits, || crate::port::limit_of(kind))
    }

    // Walk the session on until it needs something of the caller. After `Done` or an error nothing else is valid but `finish`.
    pub fn step(&mut self) -> Result<Step, Error> {
        if self.done {
            return Err(Error::Internal("the session is finished".to_string()));
        }
        let r = self.walk();
        if matches!(r, Err(_) | Ok(Step::Done)) {
            self.done = true;
        }
        r
    }

    fn walk(&mut self) -> Result<Step, Error> {
        let s = self;
        loop {
            if s.in_round {
                let run = s.run.as_mut().expect("a round in progress has a run");
                if let Some(e) = run.source_error() {
                    return Err(e);
                }
                if s.replay {
                    // the whole round from the transcript: a request it has no answer for is `transcript-miss`
                    let r = s.replayer.as_mut().expect("a replay has a replayer");
                    let entries = run.fulfil(r)?;
                    if let Some(t) = s.recording.as_mut() {
                        t.entries.extend(entries);
                    }
                    s.in_round = false;
                    continue;
                }
                // 1. the input bytes the local requests read (which requests those are is kin's, `pl-route`; the spans they name too)
                let reqs: Vec<Request> = run.requests().to_vec();
                let mut local: Vec<&Request> = Vec::new();
                for r in &reqs {
                    #[cfg(not(target_arch = "wasm32"))]
                    if s.pending.values().any(|id| *id == r.id()) {
                        continue;
                    }
                    if !run.has_answer(r.id()) && !s.external.contains(&r.id()) {
                        local.push(r);
                    }
                }
                let mut want: Vec<(u64, u64)> = Vec::new();
                for r in &local {
                    want.extend(plan::spans(r, s.idx as i32));
                }
                let missing = {
                    let mut out: Vec<Range> = Vec::new();
                    for &(off, len) in &want {
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
                    return Ok(Step::Bytes(missing.iter().map(|x| (x.offset as u64, x.length as u64)).collect()));
                }
                // 2. the local requests, answered here
                for req in &local {
                    #[cfg(not(target_arch = "wasm32"))]
                    if let Some(r) = s.router.clone().filter(|r| r.claims(req)) {
                        match r.begin(req, run.bitmaps(), s.recording.is_some(), &s.mailbox).map_err(|e| run.error_of(e))? {
                            Begun::Done(a) => run.offer(a)?,
                            Begun::Pending(t) => {
                                s.pending.insert(t, req.id());
                                s.tokens.lock().unwrap_or_else(|e| e.into_inner()).insert(t);
                            }
                        }
                        continue;
                    }
                    #[cfg(not(target_arch = "wasm32"))]
                    if let Some(rf) = s.render.as_ref().filter(|_| plan::route(req.kind(), false, true) == plan::Route::Render) {
                        let a = rf.fulfil(req).map_err(|e| run.error_of(e))?;
                        run.offer(a)?;
                        continue;
                    }
                    let a = (s.local)(req, run.bitmaps()).map_err(|e| run.error_of(e))?;
                    run.offer(a)?;
                }
                // 3. what the caller still owes
                let mut owed: Vec<Vec<i32>> = Vec::new();
                for r in &reqs {
                    if s.external.contains(&r.id()) && !run.has_answer(r.id()) {
                        owed.push(r.rec.clone());
                    }
                }
                if !owed.is_empty() {
                    return Ok(Step::Requests(owed));
                }
                // 3b. what async adapters have answered since: taken in, and while any is still outstanding the caller is told to wait
                #[cfg(not(target_arch = "wasm32"))]
                if !s.pending.is_empty() {
                    let router = s.router.clone().expect("work is pending only with a router");
                    let arrived = s.mailbox.take();
                    if arrived.is_empty() {
                        return Ok(Step::Waiting);
                    }
                    for (token, meta, c) in arrived {
                        s.pending.remove(&token);
                        s.tokens.lock().unwrap_or_else(|e| e.into_inner()).remove(&token);
                        let a = router.finish(meta, c).map_err(|e| run.error_of(e))?;
                        run.offer(a)?;
                    }
                    continue;
                }
                // 4. the round is answered: the unit is re-run on the answers
                let entries = run.commit()?;
                if let Some(t) = s.recording.as_mut() {
                    t.entries.extend(entries);
                }
                s.in_round = false;
                s.external.clear();
                continue;
            }
            let run = s.run.as_mut().expect("a session that is not done has a run");
            match run.step()? {
                plan::Next::Done => {
                    // a replay that left recorded entries unasked is a transcript of another run, as it is natively
                    if let Some(r) = s.replayer.as_mut() {
                        r.check_done().map_err(Error::Plan)?;
                    }
                    return Ok(Step::Done);
                }
                plan::Next::Fetch(reqs) => {
                    let v = reqs.iter().map(|r| (r.field(emon::plan::bq_offset()) as u64, r.field(emon::plan::bq_length()) as u64)).collect();
                    return Ok(Step::Bytes(v));
                }
                plan::Next::Requests(reqs, _) => {
                    s.external.clear();
                    for r in &reqs {
                        if !s.replay && s.is_external(r) {
                            s.external.insert(r.id());
                        }
                    }
                    s.in_round = true;
                }
            }
        }
    }

    // A request the caller was owed, answered by a raw record `[len kind id status ...]` (a failed or unsupported one is four cells).
    pub fn answer(&mut self, rec: Vec<i32>) -> Result<(), Fault> {
        if rec.len() < 4 || rec[0] as usize != rec.len() {
            return Err(Fault::Usage("an answer is a record [len kind id status ...]".to_string()));
        }
        if !self.in_round {
            return Err(Fault::Usage("no requests are owed".to_string()));
        }
        let run = self.run.as_mut().ok_or_else(|| Fault::Usage("no requests are owed".to_string()))?;
        let id = rec[2];
        if !self.external.contains(&id) || run.has_answer(id) {
            return Err(Fault::Usage(format!("request {} is not owed an answer", id)));
        }
        run.offer(Answer { rec, bitmap: None }).map_err(|e| Fault::Usage(e.to_string()))
    }

    // An `ocr` answer from the flat integer form a caller makes of its engine's reading; the form and the record are kin's (`oil-flat`,
    // `emon.ocrport`), so the layout is written once and a malformed form is refused by kin.
    pub fn answer_ocr(&mut self, id: i32, flat: &[i32]) -> Result<(), Fault> {
        let run = self.run.as_ref().ok_or_else(|| Fault::Usage("no requests are owed".to_string()))?;
        let req = run.requests().iter().find(|r| r.id() == id && r.kind() == plan::KIND_OCR)
            .ok_or_else(|| Fault::Usage(format!("request {} is not owed an ocr answer", id)))?;
        let bitmap = run.bitmap(req.arg(0))
            .ok_or_else(|| Fault::Usage("the ocr request's bitmap is absent".to_string()))?;
        let mut flat = cells_of(flat);
        let mut out = Cells::new();
        if emon::oil::oil_flat_in_bitmap(&mut flat, id, bitmap.width(), bitmap.height(), &mut out) != 0 {
            return Err(Fault::Usage("the ocr form is malformed".to_string()));
        }
        self.answer(cells_vec(&out))
    }

    // A `render` or `crop` answer (P11.4c, `js-render=1`): the caller's renderer made `rgb`, `width` x `height` pixels of 3 channels (the
    // WHOLE page at the request's dpi and layer, even for a `crop`: a crop is the cut of a whole-page render, `render::cut`, so the
    // caller keeps the one page it rendered and the pixels are the same however the crop was asked, SPEC 7.2). The answer record is
    // kin's (`an-render`), laid out for the request's kind; a crop outside the page is an `an-fail` answer, as natively. The caller's
    // bitmap must be the size it says and within the `pixels` limit (`limit(9)`): a caller that breaks that has a bug, and says `usage`.
    pub fn answer_render(&mut self, id: i32, width: i32, height: i32, rgb: &[u8]) -> Result<(), Fault> {
        if !self.in_round {
            return Err(Fault::Usage("no requests are owed".to_string()));
        }
        let limit = self.limit(9) as i64;
        let run = self.run.as_mut().ok_or_else(|| Fault::Usage("no requests are owed".to_string()))?;
        if !self.external.contains(&id) || run.has_answer(id) {
            return Err(Fault::Usage(format!("request {} is not owed an answer", id)));
        }
        let req = run.requests().iter().find(|r| r.id() == id).cloned().ok_or_else(|| Fault::Usage(format!("request {} is not owed an answer", id)))?;
        if req.kind() != plan::KIND_RENDER && req.kind() != plan::KIND_CROP {
            return Err(Fault::Usage(format!("request {} is not a render or a crop", id)));
        }
        let (_, rr) = crate::render::parse_request(&req).map_err(Fault::Usage)?;
        if width < 1 || height < 1 || (rgb.len() as i64) != width as i64 * height as i64 * 3 {
            return Err(Fault::Usage(format!("a {}x{} bitmap of 3 channels is not {} bytes", width, height, rgb.len())));
        }
        if width as i64 * height as i64 > limit {
            return Err(Fault::Usage(format!("a {}x{} bitmap is over the pixels limit of {}", width, height, limit)));
        }
        let whole = crate::port::Bitmap::from_bytes(width, height, 3, rgb);
        let got = match rr.clip {
            Some(c) => crate::render::cut(&whole, c),
            None => Ok(whole),
        };
        let mut c = Cells::new();
        let answer = match got {
            Ok(bm) => {
                emon::plan::an_render(&mut c, id, bm.width(), bm.height(), bm.channels(), rr.dpi);
                let mut rec = cells_vec(&c);
                // a crop is answered with a record of ITS kind, laid out as a render's (as `RenderFulfiller` does)
                rec[1] = req.kind();
                Answer { rec, bitmap: Some(Arc::new(bm)) }
            }
            Err(_) => {
                emon::plan::an_fail(&mut c, req.kind(), id, plan::STATUS_FAILED);
                Answer { rec: cells_vec(&c), bitmap: None }
            }
        };
        run.offer(answer).map_err(|e| Fault::Usage(e.to_string()))
    }

    // The bitmap a request names (an `ocr` request's `bitmap` argument, or the answer of any request already answered), as a canonical
    // PNG: what the caller's recogniser reads. Valid while the requests of the round are owed.
    pub fn bitmap_png(&self, id: i32) -> Option<Vec<u8>> {
        if self.in_round {
            self.run.as_ref().and_then(|r| r.bitmap(id)).map(|b| plan::png_encode(&b))
        } else {
            None
        }
    }

    // The transcript a recording session (`record=1`) made, as files; None when the session is not recording.
    pub fn transcript(&self) -> Option<Vec<(String, Vec<u8>)>> {
        self.recording.as_ref().map(|t| t.to_files())
    }

    // The end of a conversion: the Model, with what the loop did. The session is spent.
    pub fn finish(&mut self) -> Result<(Model, RunStats), Fault> {
        let run = self.run.take().ok_or_else(|| Fault::Usage("the session is finished".to_string()))?;
        Ok(run.into_model()?)
    }

    // The end of an intake session: the driver's line `FORMAT MAJOR.MINOR pages=N encrypted=B bytes=N`.
    pub fn finish_intake(&mut self) -> Result<String, Fault> {
        let run = self.run.take().ok_or_else(|| Fault::Usage("the session is finished".to_string()))?;
        let (i, _) = run.into_intake()?;
        Ok(intake_line(&i))
    }
}

// A session that goes while an async adapter still works on its requests tells the adapter to stop: a late answer is ignored.
#[cfg(not(target_arch = "wasm32"))]
impl Drop for Session {
    fn drop(&mut self) {
        if let Some(r) = &self.router {
            for t in self.pending.keys() {
                r.cancel(*t);
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ stateless, both bindings export them

// The driver's intake line.
pub fn intake_line(i: &Intake) -> String {
    format!("{} {}.{} pages={} encrypted={} bytes={}", i.format, i.version.0, i.version.1, i.pages, i.encrypted, i.bytes)
}

// The numbers of the plan/fulfil protocol, kin's, so a binding's caller never restates them (`emon.plan`'s, a kin law holds them):
// 0 kind render, 1 decode, 2 ocr, 3 bytes, 4 crop, 5 decode-img; 10 status ok, 11 failed, 12 unsupported, 13 limit; 14, 15 the ocr word flags;
// 16..23 the fields of a render / crop request (below).
pub fn constant(i: u32) -> i32 {
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
        // 16..23: the argument indices of a `render` / `crop` request, kin's (`rd-*`, `render::parse_request` reads by them), so the SDK
        // reads a request by these and never by a literal offset: input, page, dpi, layer; the crop's x, y, w, h
        16 => emon::plan::rd_input(),
        17 => emon::plan::rd_page(),
        18 => emon::plan::rd_dpi(),
        19 => emon::plan::rd_layer(),
        20 => emon::plan::rd_x(),
        21 => emon::plan::rd_y(),
        22 => emon::plan::rd_w(),
        23 => emon::plan::rd_h(),
        // 100 + K: the default of kin's limit K (`emon.limits`), so a binding that must say a default (the JS SDK's `input-bytes`) reads kin's
        k if k >= 100 => crate::port::limit_default((k - 100) as i32).unwrap_or(-1),
        _ => -1,
    }
}

// The same list as the drivers' `--html=`.
pub fn parse_html(list: &str) -> Result<HtmlOptions, String> {
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

pub fn cells_of(v: &[i32]) -> Cells {
    let mut c = Cells::new();
    for x in v {
        crate::port::cells_push(&mut c, *x);
    }
    c
}

pub fn cells_vec(c: &Cells) -> Vec<i32> {
    (0..crate::port::cells_len(c)).map(|i| crate::port::cells_at(c, i)).collect()
}

// An `ocr` request's policy is kin's (`emon.ocrport`): the index of the argument its first language is at (after a kin-made view's
// pixel hash). `req`: the request record `[len kind id arg...]`.
pub fn ocr_observed(cps: &[i32]) -> i64 {
    emon::oil::oil_obs_hash(&mut cells_of(cps), 0, cps.len() as i32)
}

pub fn ocr_identity_at(req: &[i32]) -> i32 {
    emon::ocrport::op_identity_at(&mut cells_of(req))
}

pub fn ocr_lang_first(req: &[i32]) -> i32 {
    emon::ocrport::op_lang_first(&mut cells_of(req))
}

// The installed models that read the request: `installed` holds their names as code points separated by 0; the indices into that list
// (see `op-models-configured`), and how many. The browser provider supplies its configured model list.
pub fn ocr_models(req: &[i32], installed: &[i32]) -> (i32, Vec<i32>) {
    let mut req = cells_of(req);
    let mut inst = cells_of(installed);
    let mut out = Cells::new();
    let n = emon::ocrtess::op_models_configured(&mut req, &mut inst, 1, &mut out);
    (n, cells_vec(&out))
}

// A line's type from the engine's block type (Tesseract's PolyBlockType).
pub fn ocr_block_type(poly: i32) -> i32 {
    emon::ocrtess::op_block_type(poly)
}

// The language the engine read a word in (the model's name as code points), as a request spells it: one packed cell.
pub fn ocr_word_lang(req: &[i32]) -> i32 {
    emon::ocrtess::op_word_lang(&mut cells_of(req))
}

// THE ADAPTER REGISTRY'S RULES for a host that cannot link kin's Rust (the JS SDK asks the module, D925): kin's `ad-register-check` (0, or the reason), the reason's name (`adr`) and the
// route of a request of the OIL engine `oen` over the OIL engines registered, in order (`ad-route`: an index or -1).
pub fn adapter_register_check(abi: i32, oen: i32, caps: i32, measured: i32, needs: i32, flags: i32) -> i32 {
    emon::adapters::ad_register_check(abi, oen, caps, measured, needs, flags)
}

pub fn adapter_reason(code: i32) -> String {
    let mut s = String::new();
    emon::adapters::adr_emit_name(&mut s, code);
    s
}

pub fn adapter_route(oen: i32, registered: &[i32]) -> i32 {
    emon::adapters::ad_route(oen, &mut cells_of(registered), 0)
}

// A panic's message (`internal`): a payload of a `String`, a `&str`, or none.
pub fn panic_message(p: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = p.downcast_ref::<String>() {
        s.clone()
    } else if let Some(s) = p.downcast_ref::<&str>() {
        s.to_string()
    } else {
        "a panic".to_string()
    }
}

// THE PACK RULES for a host that cannot link kin's Rust (the JS SDK asks the module, D925, P13.16): the catalogue, what a conversion needs (`pk-resolve`), what to do about a pack that
// is missing (`ad-asks-hook`, `ad-pack-action`), the engines' states (`ad-engine-state`) and `prepare()` (`ad-verdict`). Pure functions of their arguments: no directory, no file.
fn js_s(s: &str) -> String {
    let mut o = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\n' => o.push_str("\\n"),
            c if (c as u32) < 0x20 => o.push_str(&format!("\\u{:04x}", c as u32)),
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

// One pack of kin's catalogue, as the library's Rust host reads it (`packs.rs` is not in the browser build).
struct PackLine {
    code: i32,
    id: String,
    rev: i32,
    engine: String,
    kind: String,
    licence: String,
    bytes: u64,
    files: Vec<(String, u64, String)>,
}

fn pack_line(p: i32) -> PackLine {
    use emon::{engines as ke, packdata as kp};
    let mut id = String::new();
    kp::pkid_emit_name(&mut id, p);
    let mut files = Vec::new();
    for i in 0..ke::pk_pack_file_count(p) {
        let mut name = String::new();
        ke::pk_pack_file_name(p, i, &mut name);
        let mut sha = String::new();
        for w in 0..8 {
            sha.push_str(&format!("{:08x}", ke::pk_pack_file_sha(p, i, w) as u32));
        }
        files.push((name, ke::pk_pack_file_bytes(p, i) as u64, sha));
    }
    let eng = ke::pk_pack_engine(p);
    let mut engine = String::new();
    if eng != 0 {
        ke::eng_emit_name(&mut engine, eng);
    } else {
        engine.push_str("any");
    }
    let mut kind = String::new();
    ke::pkind_emit_name(&mut kind, ke::pk_pack_kind(p));
    let mut licence = String::new();
    kp::plic_emit_name(&mut licence, ke::pk_pack_licence(p));
    PackLine { code: p, id, rev: ke::pk_pack_rev(p), engine, kind, licence, bytes: ke::pk_pack_bytes(p) as u64, files }
}

fn pack_cells(v: &[i32]) -> Cells {
    cells_of(v)
}

/// The catalogue as JSON: the platform class, every pack (code, id, engine, kind, licence, bytes, files with their SHA-256) and the codes kin names things by.
pub fn pack_catalogue() -> String {
    use emon::{engines as ke, adapters as ka};
    let class = crate::plan::platform_class();
    let mut cls = String::new();
    ke::pcls_emit_name(&mut cls, class);
    let mut packs = Vec::new();
    for p in 1..=ke::pk_pack_count() {
        if ke::pk_pack_known(p) != 1 {
            continue;
        }
        let info = pack_line(p);
        let files: Vec<String> = info.files.iter().map(|f| format!("{{\"name\":{},\"bytes\":{},\"sha\":{}}}", js_s(&f.0), f.1, js_s(&f.2))).collect();
        packs.push(format!(
            "{{\"code\":{},\"id\":{},\"rev\":{},\"engine\":{},\"kind\":{},\"licence\":{},\"bytes\":{},\"files\":[{}]}}",
            info.code, js_s(&info.id), info.rev, js_s(&info.engine), js_s(&info.kind), js_s(&info.licence), info.bytes, files.join(",")
        ));
    }
    format!(
        "{{\"class\":{},\"pact\":{{\"use\":{},\"fetch\":{},\"provided\":{},\"substitute\":{}}},\"pfetch\":{{\"never\":{},\"auto\":{},\"cache\":{}}},\"pstate\":{{\"installed\":{},\"missing\":{},\"conditional\":{},\"unsupported\":{}}},\"ppocr\":{},\"packs\":[{}]}}",
        js_s(&cls), ka::pact_use(), ka::pact_fetch(), ka::pact_provided(), ka::pact_substitute(), ke::pfetch_never(), ke::pfetch_auto(), ke::pfetch_cache(),
        ke::pstate_installed(), ke::pstate_missing(), ke::pstate_conditional(), ke::pstate_unsupported(), ke::eng_ppocr(), packs.join(",")
    )
}

/// kin's `ad-asks-hook`: is the app's hook asked about a pack that is `present` (1/0) under `mode`, `offline` and whether there is a hook?
pub fn pack_asks(present: i32, mode: i32, offline: i32, hook: i32) -> i32 {
    emon::adapters::ad_asks_hook(present, mode, offline, hook)
}

/// kin's `ad-pack-action`: what becomes of a pack, given what the hook decided (`decision`: 0 none, else the hook's action).
pub fn pack_action(present: i32, mode: i32, offline: i32, hook: i32, decision: i32) -> i32 {
    emon::adapters::ad_pack_action(present, mode, offline, hook, decision)
}

/// The kin engine (`eng`) an OIL engine answers as, or -1 (`ad-oen-eng`).
pub fn pack_oen_eng(oen: i32) -> i32 {
    emon::adapters::ad_oen_eng(oen)
}

/// kin's engine named `name` (`eng`), or -1.
pub fn pack_eng_find(name: &str) -> i32 {
    emon::engines::eng_find(name.as_bytes(), 0, name.len() as i32)
}

/// `pk-resolve` over `engines` (`eng`), packed `langs` and the codes of the packs held: rows of four (state, engine, pack, language) as JSON, or `{"error":...}`.
pub fn pack_resolve(osd: bool, langs: &[i32], engines: &[i32], installed: &[i32]) -> String {
    match resolve_rows(engines, langs, osd, installed) {
        Err(msg) => format!("{{\"error\":{}}}", js_s(&msg)),
        Ok(rows) => format!("{{\"rows\":[{}]}}", rows.iter().map(|r| format!("[{},{},{},{}]", r[0], r[1], r[2], r[3])).collect::<Vec<_>>().join(",")),
    }
}

fn eng_name(eng: i32) -> String {
    let mut s = String::new();
    emon::engines::eng_emit_name(&mut s, eng);
    s
}

/// D896's `engines()` as the module's JSON (the shape of the C ABI's `emon_engines_describe`). `registered_oens`: the OIL engines of the adapters registered; `renderer`: whether a
/// renderer is registered; `installed`: the codes of the packs held; `store`: what the host calls its store.
pub fn pack_describe(registered_oens: &[i32], renderer: bool, installed: &[i32], mode: i32, off: bool, store: &str) -> String {
    use emon::{adapters as ka, engines as ke};
    let class = crate::plan::platform_class();
    let mut engines = Vec::new();
    for i in 0..ke::pk_engine_count() {
        let eng = ke::pk_engine_at(i);
        if ke::pk_engine_on(eng, class) == 0 {
            continue;
        }
        let name = eng_name(eng);
        let registered = if ke::pk_engine_kind(eng) == ke::ekind_render() { renderer } else { ke::pk_engine_on(eng, class) == 1 && registered_oens.contains(&ke::pk_engine_oen(eng)) };
        let rows: Vec<[i32; 4]> = if ke::pk_engine_kind(eng) == ke::ekind_ocr() && ke::pk_engine_needs(eng) != 0 { resolve_rows(&[eng], &[], false, installed).unwrap_or_default() } else { Vec::new() };
        let missing: Vec<&[i32; 4]> = rows.iter().filter(|r| r[0] == ke::pstate_missing()).collect();
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
            .filter(|r| r[2] > 0)
            .map(|r| {
                let info = pack_line(r[2]);
                format!("{{\"id\":{},\"kind\":{},\"bytes\":{},\"licence\":{},\"installed\":{}}}", js_s(&info.id), js_s(&info.kind), info.bytes, js_s(&info.licence), r[0] == ke::pstate_installed())
            })
            .collect();
        let dl: u64 = missing.iter().map(|r| pack_line(r[2]).bytes).sum();
        engines.push(format!(
            "{{\"name\":{},\"kind\":{},\"impl\":{},\"state\":{},\"registered\":{},\"calibrated\":{},\"caps\":{},\"oen\":{},\"role\":{},\"needs\":[{}],\"download_bytes\":{},\"reason\":{}}}",
            js_s(&name), js_s(&kind), js_s(&imp), js_s(&state_name), registered, ke::pk_engine_calibrated(eng) == 1, ke::pk_engine_caps(eng), ke::pk_engine_oen(eng), js_s(&role), needs.join(","), dl, js_s(&reason)
        ));
    }
    let mut pcls = String::new();
    ke::pcls_emit_name(&mut pcls, class);
    let mut m = String::new();
    ke::pfetch_emit_name(&mut m, mode);
    format!("{{\"platform\":{},\"mode\":{},\"offline\":{},\"store\":{},\"engines\":[{}]}}", js_s(&pcls), js_s(&m), off, js_s(store), engines.join(","))
}

fn resolve_rows(engines: &[i32], langs: &[i32], osd: bool, installed: &[i32]) -> Result<Vec<[i32; 4]>, String> {
    use emon::engines as ke;
    let (mut l, mut e, mut i) = (pack_cells(langs), pack_cells(engines), pack_cells(installed));
    let (mut out, mut scratch) = (Cells::new(), Cells::new());
    let r = ke::pk_resolve(crate::plan::platform_class(), if osd { ke::pkf_osd() } else { 0 }, &mut l, &mut e, &mut i, &mut out, &mut scratch);
    if r != 0 {
        let mut msg = String::new();
        ke::pk_explain(r, &mut out, &mut msg);
        return Err(msg);
    }
    Ok((0..crate::port::cells_len(&out) / 4).map(|k| [crate::port::cells_at(&out, 4 * k), crate::port::cells_at(&out, 4 * k + 1), crate::port::cells_at(&out, 4 * k + 2), crate::port::cells_at(&out, 4 * k + 3)]).collect())
}

/// D896's `prepare(options)` over the options text (the grammar `Engine` parses), the packs held, the mode and offline: the C ABI's JSON.
pub fn pack_prepare(options: &str, installed: &[i32], mode: i32, off: bool) -> Result<String, String> {
    use emon::{adapters as ka, engines as ke};
    let p = crate::engine::parse_options(options)?;
    let never = p.options.ocr == crate::engine::OcrPolicy::Never;
    let mut engines = vec![p.options.ocr_engine.registry_engine()];
    if p.options.ocr_engine == crate::engine::OcrEngine::PpOcr {
        engines.push(ke::eng_ppocr());
    }
    let osd = !never && p.options.ocr_engine.uses_tesseract();
    let mut langs: Vec<i32> = Vec::new();
    if let Some(l) = &p.options.locale {
        let mut c = Cells::new();
        for ch in l.chars() {
            crate::port::cells_push(&mut c, ch as i32);
        }
        let lg = emon::scan::sc_lang(&mut c);
        if lg > 0 {
            langs.push(lg * 256);
        }
    }
    let (rows, verdict): (Vec<[i32; 4]>, i32) = if never {
        (Vec::new(), ka::pver_ready())
    } else {
        let (mut l, mut e, mut i) = (pack_cells(&langs), pack_cells(&engines), pack_cells(installed));
        let (mut out, mut scratch) = (Cells::new(), Cells::new());
        let r = ke::pk_resolve(crate::plan::platform_class(), if osd { ke::pkf_osd() } else { 0 }, &mut l, &mut e, &mut i, &mut out, &mut scratch);
        if r != 0 {
            let mut msg = String::new();
            ke::pk_explain(r, &mut out, &mut msg);
            return Err(msg);
        }
        let rows = (0..crate::port::cells_len(&out) / 4).map(|k| [crate::port::cells_at(&out, 4 * k), crate::port::cells_at(&out, 4 * k + 1), crate::port::cells_at(&out, 4 * k + 2), crate::port::cells_at(&out, 4 * k + 3)]).collect();
        (rows, ka::ad_verdict(&mut out, mode, off as i32))
    };
    let mut vname = String::new();
    ka::pver_emit_name(&mut vname, verdict);
    let lang_name = |l: i32| -> String { [(l >> 24) & 255, (l >> 16) & 255, (l >> 8) & 255].iter().filter(|c| **c > 0).map(|c| *c as u8 as char).collect() };
    let mut packs_json = Vec::new();
    let mut unsupported = Vec::new();
    let (mut missing, mut missing_bytes, mut conditional, mut conditional_bytes) = (0, 0u64, 0, 0u64);
    for r in &rows {
        if r[0] == ke::pstate_unsupported() {
            unsupported.push(format!("{{\"engine\":{},\"language\":{}}}", js_s(&eng_name(r[1])), js_s(&lang_name(r[3]))));
            continue;
        }
        let info = pack_line(r[2]);
        let mut st = String::new();
        ke::pstate_emit_name(&mut st, r[0]);
        if r[0] == ke::pstate_missing() {
            missing += 1;
            missing_bytes += info.bytes;
        }
        if r[0] == ke::pstate_conditional() {
            conditional += 1;
            conditional_bytes += info.bytes;
        }
        packs_json.push(format!("{{\"id\":{},\"state\":{},\"engine\":{},\"kind\":{},\"bytes\":{},\"licence\":{},\"rev\":{}}}", js_s(&info.id), js_s(&st), js_s(&info.engine), js_s(&info.kind), info.bytes, js_s(&info.licence), info.rev));
    }
    let engine_names: Vec<String> = if never { Vec::new() } else { engines.iter().map(|e| js_s(&eng_name(*e))).collect() };
    let lang_names: Vec<String> = if never { Vec::new() } else if langs.is_empty() { vec![js_s("eng")] } else { langs.iter().map(|l| js_s(&lang_name(*l))).collect() };
    let mut m = String::new();
    ke::pfetch_emit_name(&mut m, mode);
    Ok(format!(
        "{{\"verdict\":{},\"mode\":{},\"offline\":{},\"engines\":[{}],\"languages\":[{}],\"osd\":{},\"packs\":[{}],\"unsupported\":[{}],\"missing\":{},\"missing_bytes\":{},\"conditional\":{},\"conditional_bytes\":{}}}",
        js_s(&vname), js_s(&m), off, engine_names.join(","), lang_names.join(","), osd, packs_json.join(","), unsupported.join(","), missing, missing_bytes, conditional, conditional_bytes
    ))
}
