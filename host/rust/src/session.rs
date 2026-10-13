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
    // requests the caller owes (`ocr`, with `js-ocr=1`): each a record `[len kind id arg...]`
    Requests(Vec<Vec<i32>>),
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
}

impl Session {
    // Open a session over an input of `length` bytes. `chunk`: the size the caller reads in (None: the options' `chunk`). `intake_only`:
    // stage 0 alone (then `finish_intake`). `options`: kin's `key=value` text; `record=1` keeps a transcript, `js-ocr=1` makes the `ocr`
    // requests the caller's. The bound on the input's length is checked BEFORE the cache of its chunks is allocated: a chunk of 1 and a
    // length of 2 GiB would ask for gigabytes (the engine checks it again, against the same limit).
    pub fn open(length: u64, chunk: Option<usize>, intake_only: bool, password: Option<Vec<u8>>, options: &str) -> Result<Session, Fault> {
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
        let keep = p.record || p.js_ocr;
        let ocr = if p.js_ocr { Some(OcrPort(Arc::new(HostOcr))) } else { None };
        let source: Arc<dyn ByteSource> = cache.clone();
        let local = Box::new(crate::engine::port_fulfiller(source, idx, keep, p.record, ocr, None));
        let mut recording = None;
        if p.record {
            let mut t = Transcript::default();
            t.inputs = vec![(idx as i32, ulen as i32)];
            t.producer = p.producer.clone();
            recording = Some(t);
        }
        Ok(Session { cache, run: Some(run), idx, js_ocr: p.js_ocr, files: BTreeMap::new(), replay: false, replayer: None, recording, local, in_round: false, external: BTreeSet::new(), done: false })
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

    // The requests the caller fulfils: `ocr`, when it has an engine (kin's `pl-route`). (`render` and `crop` would be pdfium's, which a
    // session's caller does not have: they are answered `unsupported` here like any request no port is configured for.)
    fn is_external(&self, kind: i32) -> bool {
        plan::route(kind, self.js_ocr, false) == plan::Route::Ocr
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
                        if !s.replay && s.is_external(r.kind()) {
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

    // An `ocr` answer from the flat integer form a caller makes of its engine's reading; the form and the record are kin's (`ox-flat`,
    // `emon.ocrport`), so the layout is written once and a malformed form is refused by kin.
    pub fn answer_ocr(&mut self, id: i32, flat: &[i32]) -> Result<(), Fault> {
        let mut flat = cells_of(flat);
        let mut out = Cells::new();
        if emon::ocrport::ox_flat(&mut flat, id, &mut out) != 0 {
            return Err(Fault::Usage("the ocr form is malformed".to_string()));
        }
        self.answer(cells_vec(&out))
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

// ------------------------------------------------------------------------------------------------ stateless, both bindings export them

// The driver's intake line.
pub fn intake_line(i: &Intake) -> String {
    format!("{} {}.{} pages={} encrypted={} bytes={}", i.format, i.version.0, i.version.1, i.pages, i.encrypted, i.bytes)
}

// The numbers of the plan/fulfil protocol, kin's, so a binding's caller never restates them (`emon.plan`'s, a kin law holds them):
// 0 kind render, 1 decode, 2 ocr, 3 bytes, 4 crop, 5 decode-img; 10 status ok, 11 failed, 12 unsupported, 13 limit; 14, 15 the ocr word flags.
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
pub fn ocr_lang_first(req: &[i32]) -> i32 {
    emon::ocrport::op_lang_first(&mut cells_of(req))
}

// The installed models that read the request: `installed` holds their names as code points separated by 0; the indices into that list
// (see `op-models`), and how many.
pub fn ocr_models(req: &[i32], installed: &[i32]) -> (i32, Vec<i32>) {
    let mut req = cells_of(req);
    let mut inst = cells_of(installed);
    let mut out = Cells::new();
    let n = emon::ocrport::op_models(&mut req, &mut inst, &mut out);
    (n, cells_vec(&out))
}

// A line's type from the engine's block type (Tesseract's PolyBlockType).
pub fn ocr_block_type(poly: i32) -> i32 {
    emon::ocrport::op_block_type(poly)
}

// The language the engine read a word in (the model's name as code points), as a request spells it: one packed cell.
pub fn ocr_word_lang(req: &[i32]) -> i32 {
    emon::ocrport::op_word_lang(&mut cells_of(req))
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
