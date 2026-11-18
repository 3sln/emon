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
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::sync::{Arc, Mutex};
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
    // a round of requests is in progress (the Run holds it: its requests, the answers offered so far, the bitmaps), and the ids of
    // those that are the caller's to answer
    in_round: bool,
    external: BTreeSet<i32>,
    done: bool,
    // the registry's OCR adapters (K2, D899): the session answers the requests they are registered for itself; what they answer later arrives in the mailbox
    router: Option<Router>,
    mailbox: Arc<Mailbox>,
    // token of an async adapter's work -> the request it is for
    pending: HashMap<u64, i32>,
    tokens: Arc<Mutex<HashSet<u64>>>,
}

// What may be done from any thread while a session steps (`emon_complete`, `emon_wait`): hand an async adapter's answer to the session, or wait for one.
#[derive(Clone)]
pub struct Completer {
    router: Option<Router>,
    mailbox: Arc<Mailbox>,
    tokens: Arc<Mutex<HashSet<u64>>>,
}

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
        Session::open_with(length, chunk, intake_only, password, options, None)
    }

    // `open`, with the engines an embedder registered (K2, D899): when they have OCR adapters the session answers the `ocr` requests those are registered for itself, and only
    // the bytes, the requests nobody registered for (with `js-ocr=1`) and WAITING come out of `step`.
    pub fn open_with(length: u64, chunk: Option<usize>, intake_only: bool, password: Option<Vec<u8>>, options: &str, engines: Option<&Engines>) -> Result<Session, Fault> {
        let router = engines.and_then(|e| e.router());
        let mut p = parse_options(options).map_err(Fault::Usage)?;
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
        let run = engine.start_pushed(idx, cache.clone(), password, &p.options, intake_only)?;
        let has_router = router.is_some();
        // the decode answers keep their pixels when something reads them: the caller's engine, or an adapter
        let keep = p.record || p.js_ocr || has_router;
        let ocr = if let Some(r) = &router { Some(OcrPort(Arc::new(r.clone()))) } else if p.js_ocr { Some(OcrPort(Arc::new(HostOcr))) } else { None };
        let source: Arc<dyn ByteSource> = cache.clone();
        let local = Box::new(crate::engine::port_fulfiller(source, idx, keep, p.record, ocr, None));
        let mut recording = None;
        if p.record {
            let mut t = Transcript::default();
            t.inputs = vec![(idx as i32, ulen as i32)];
            t.producer = p.producer.clone();
            // the engines that answer say so in the transcript (`:producer`, and the engine table written first: D898), as the Engine's own recording does
            if let Some(r) = &router {
                let mut prod = r.producer_pairs();
                for (k, v) in &p.producer {
                    if !prod.iter().any(|(k2, _)| k2 == k) {
                        prod.push((k.clone(), v.clone()));
                    }
                }
                t.engines = plan::engine_rows(&prod);
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
            in_round: false,
            external: BTreeSet::new(),
            done: false,
            router,
            mailbox: Mailbox::new(),
            pending: HashMap::new(),
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
            if let Some(r) = &self.router {
            if r.claims(req) {
                return false;
            }
        }
        matches!(plan::route(req.kind(), self.js_ocr, self.js_render), plan::Route::Ocr | plan::Route::Render)
    }

    // What `emon_complete` and `emon_wait` use from any thread.
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
