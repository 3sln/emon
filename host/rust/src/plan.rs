// The Rust half of the plan/fulfil loop and the transcript (DEVLOG P2.5, SPEC
// 7.3, D008, D058 revised, D089). Its Go twin is `host/go/port/plan.go`; the
// record layouts are KIN's (`kin/plan.kin`) and this file knows only the header
// of a record -- [len kind id ...], and for an answer [... status] -- plus the
// four kinds.
//
// NOTHING HERE TOUCHES A FILESYSTEM (D058: the library never names `std::fs` or
// `Path`). A transcript is a set of named files held in memory
// (`Transcript::to_files`, `from_files`); the DRIVER writes and reads the
// directory. No kin forms are declared in this file: it is a host-side engine
// for the ports, not a port kin calls (kin never calls an expensive port).
//
// THE TRANSCRIPT FILE IS KIN'S TOO (P10.9, D396, D438: `kin/transcript.kin`): the EDN, the kind names, the escaping of the producer, the replay map,
// the count of entries a replay never asked for and the sentence of every refusal. This file keeps the in-memory `Transcript` the embedder holds, the
// bitmaps (a PNG each, read by `png_decode` until P10.11) and the `Replayer` that answers the loop from kin's map.
//
// THE LOOP IS KIN'S (P10.8, D396, D399: `kin/planloop.kin`) AND THE DRIVER IS INVERTED. This file keeps what is not observable:
// the unit call (the caller's closure, under the caller's panic catch), awaiting, and the fulfilment of the ports. One attempt's
// accounting, the two named limits, the requests a unit wrote (checked, and sorted by id), the answered set, the order the answers
// are appended in, the transcript's entries, the planner of the source's misses and every name are kin's.
//
// THE SHAPE IS THE JAVASCRIPT HOST'S TOO: the host owns the loop and the await.
//
//     loop:
//       status = unit(answers, requests)            // kin, from scratch, pure
//       need-bytes -> fetch what the Src missed (`bytes` requests), re-run
//       done       -> return
//       requests   -> START every request, then take completions in WHATEVER
//                     order they arrive, OFFER each to kin; kin appends them to
//                     the answers in id order, and writes the transcript entries; re-run
//
// `Machine` is the loop with the blocking taken out: one attempt at a time, and what the host must do before the next handed back
// as a value. `run_bms` is its synchronous driver; the browser's (`host/wasm`, D356) is its JS SDK.
//
// `Fulfiller::start` / `next` is the async shape: a sync host completes inside
// `start` (`SyncFulfiller`); a threaded one or a JS one completes whenever and
// `next` blocks or awaits for the next completion. The loop never depends on
// the order, which is what makes a transcript the same however the answers
// arrived.

use crate::emon::plan as kp;
use crate::emon::planctx::Plan;
use crate::emon::planloop as lp;
use crate::emon::transcript as kt;
use crate::emon::transcriptctx::Tr;
use crate::port::{cells_at, cells_drop, cells_len, cells_push, Bitmap, BitmapSet, Buf, Cells};
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::sync::Arc;

// ------------------------------------------------------------ the constants
// The numbers are `emon.plan`'s (a kin law holds them; the driver asserts them).

pub const KIND_RENDER: i32 = 1;
pub const KIND_DECODE: i32 = 2;
pub const KIND_OCR: i32 = 3;
pub const KIND_BYTES: i32 = 4;
pub const KIND_CROP: i32 = 5;
pub const KIND_DECODE_IMG: i32 = 6;

pub const STATUS_OK: i32 = 0;
pub const STATUS_FAILED: i32 = 1;

pub const UNIT_DONE: i32 = 0;
pub const UNIT_REQUESTS: i32 = 1;
pub const UNIT_NEED_BYTES: i32 = 7;

// SPEC 13.1's defaults. Both are NAMED limits: a breach is `PlanError::Limit`.
pub const MAX_ROUNDS: i32 = 5;
pub const FETCH_ROUNDS: i32 = 24;

pub struct Config {
    pub max_rounds: i32,
    pub fetch_rounds: i32,
}

impl Default for Config {
    fn default() -> Config {
        Config { max_rounds: MAX_ROUNDS, fetch_rounds: FETCH_ROUNDS }
    }
}

#[derive(Debug, Default, Clone, PartialEq)]
pub struct Stats {
    pub attempts: i32,
    pub rounds: i32,
    pub requests: i32,
    pub fetch_rounds: i32,
    pub bytes_requests: i32,
}

#[derive(Debug, Clone, PartialEq)]
pub enum PlanError {
    // A named limit: `max-rounds` or `fetch-rounds` (the name is kin's, `pe`).
    Limit { name: String, limit: i32, actual: i32 },
    // Replaying: the transcript has no answer for this request.
    TranscriptMiss(Vec<i32>),
    TranscriptBad(String),
    // A fulfiller could not run (distinct from an answer that says `failed`).
    Fulfil(String),
    // The unit answered a status that is not done, requests or need-bytes.
    Unit(i32),
    // A request or an answer that is not a well-formed record.
    Bad(String),
    // The unit said need-bytes and the source has nothing missed.
    Stalled,
}

// A name of kin's `pe`.
fn pe_name(code: i32) -> String {
    let mut s = String::new();
    lp::pe_emit_name(&mut s, code);
    s
}

impl PlanError {
    pub fn name(&self) -> String {
        match self {
            PlanError::Limit { name, .. } => name.clone(),
            PlanError::TranscriptMiss(_) => pe_name(lp::pe_transcript_miss()),
            PlanError::TranscriptBad(_) => pe_name(lp::pe_transcript_bad()),
            PlanError::Fulfil(_) => pe_name(lp::pe_fulfil_failed()),
            PlanError::Unit(_) => pe_name(lp::pe_unit_failed()),
            PlanError::Bad(_) => pe_name(lp::pe_bad_record()),
            PlanError::Stalled => pe_name(lp::pe_stalled()),
        }
    }
}

impl std::fmt::Display for PlanError {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        match self {
            PlanError::Limit { name, limit, actual } => {
                write!(f, "{}: the limit is {} and this run reached {}", name, limit, actual)
            }
            PlanError::TranscriptMiss(r) => {
                let mut s = String::new();
                kt::tr_miss_msg(&mut cells_of(r), 0, r.len() as i32, &mut s);
                write!(f, "{}: {}", self.name(), s)
            }
            PlanError::TranscriptBad(s) => write!(f, "{}: {}", self.name(), s),
            PlanError::Fulfil(s) => write!(f, "{}: {}", self.name(), s),
            PlanError::Unit(s) => write!(f, "{}: status {}", self.name(), s),
            PlanError::Bad(s) => write!(f, "{}: {}", self.name(), s),
            PlanError::Stalled => write!(f, "{}: the unit missed and the source reports nothing missed", self.name()),
        }
    }
}

// -------------------------------------------------------------- the bitmap
// Render and decode answers carry the port's `Bitmap` (P2.7, `port.rs`); this
// file only encodes it to and from a lossless PNG for the transcript.
pub type Bitmaps = BTreeMap<i32, Arc<Bitmap>>;

// ------------------------------------------------- requests and answers

#[derive(Debug, Clone, PartialEq)]
pub struct Request {
    pub rec: Vec<i32>,
    // The unit's PAYLOAD (P6.1a, D291): the bytes kin made itself in the attempt that wrote this request (a `decode-img`'s
    // codestream when it is not the input's own, a JBIG2 globals stream), shared by every request of the round. Never
    // recorded in a transcript: the record names them by SHA-256. Empty for a request that names none.
    pub payload: Arc<Vec<u8>>,
}

impl Request {
    pub fn new(rec: Vec<i32>) -> Request {
        Request { rec, payload: Arc::new(Vec::new()) }
    }
    pub fn kind(&self) -> i32 {
        self.rec[1]
    }
    pub fn id(&self) -> i32 {
        self.rec[2]
    }
    // Argument k (kin's `rq-arg`).
    pub fn arg(&self, k: usize) -> i32 {
        self.rec[3 + k]
    }
    // The argument a kin field names (`kp::dq_where()`, `kp::bq_offset()`, ...): a host reads a request by these and never by a
    // literal offset (D396 item 5).
    pub fn field(&self, f: i32) -> i32 {
        self.arg(f as usize)
    }
}

#[derive(Debug, Clone)]
pub struct Answer {
    pub rec: Vec<i32>,
    // A render or decode answer's pixels. Keyed by the answer's id.
    pub bitmap: Option<Arc<Bitmap>>,
}

impl Answer {
    pub fn kind(&self) -> i32 {
        self.rec[1]
    }
    pub fn id(&self) -> i32 {
        self.rec[2]
    }
    pub fn status(&self) -> i32 {
        self.rec[3]
    }
}

// ----------------------------------------------------------- fulfilment

pub trait Fulfiller {
    // Begin a request. A sync host may finish it here; an async one returns at
    // once. `bitmaps` holds every bitmap answered so far (an OCR request names
    // one by id); take a clone of what is needed, it is not borrowed past here.
    fn start(&mut self, req: &Request, bitmaps: &Bitmaps) -> Result<(), PlanError>;
    // The next completed answer, in whatever order completions arrive. Called
    // once per started request.
    fn next(&mut self) -> Result<Answer, PlanError>;
}

// A sync function as a fulfiller: completes inside `start`.
pub struct SyncFulfiller<F: FnMut(&Request, &Bitmaps) -> Result<Answer, PlanError>> {
    f: F,
    done: VecDeque<Result<Answer, PlanError>>,
}

impl<F: FnMut(&Request, &Bitmaps) -> Result<Answer, PlanError>> SyncFulfiller<F> {
    pub fn new(f: F) -> Self {
        SyncFulfiller { f, done: VecDeque::new() }
    }
}

impl<F: FnMut(&Request, &Bitmaps) -> Result<Answer, PlanError>> Fulfiller for SyncFulfiller<F> {
    fn start(&mut self, req: &Request, bitmaps: &Bitmaps) -> Result<(), PlanError> {
        let a = (self.f)(req, bitmaps);
        self.done.push_back(a);
        Ok(())
    }
    fn next(&mut self) -> Result<Answer, PlanError> {
        self.done.pop_front().expect("next without a started request")
    }
}

// An async-SHAPED fulfiller for tests: `start` only queues, and the work is done
// when a completion is asked for, LAST STARTED FIRST -- so answers arrive in the
// reverse of the order the requests went out, as they may from a pool of
// threads or a browser's promises.
pub struct DeferredFulfiller<F: FnMut(&Request, &Bitmaps) -> Result<Answer, PlanError>> {
    f: F,
    queue: Vec<(Request, Bitmaps)>,
}

impl<F: FnMut(&Request, &Bitmaps) -> Result<Answer, PlanError>> DeferredFulfiller<F> {
    pub fn new(f: F) -> Self {
        DeferredFulfiller { f, queue: Vec::new() }
    }
}

impl<F: FnMut(&Request, &Bitmaps) -> Result<Answer, PlanError>> Fulfiller for DeferredFulfiller<F> {
    fn start(&mut self, req: &Request, bitmaps: &Bitmaps) -> Result<(), PlanError> {
        self.queue.push((req.clone(), bitmaps.clone()));
        Ok(())
    }
    fn next(&mut self) -> Result<Answer, PlanError> {
        let (req, bm) = self.queue.pop().expect("next without a started request");
        (self.f)(&req, &bm)
    }
}

// What a `Src` missed, as ranges to fetch. The ENGINE implements it over its
// `SrcBacking`s (doubling read-ahead, coalescing; D058) with kin's planner (`fp-plan`); the loop only asks.
#[derive(Debug, Clone, PartialEq)]
pub struct Range {
    pub input: i32,
    pub offset: i32,
    pub length: i32,
}

pub trait Misses {
    // The ranges missed since the last call, for fetch round `round` (0, 1, ...).
    fn take(&mut self, round: i32) -> Vec<Range>;
}

pub struct NoMisses;

impl Misses for NoMisses {
    fn take(&mut self, _round: i32) -> Vec<Range> {
        Vec::new()
    }
}

// ------------------------------------------------------------- the loop

// The records of a `Cells` of them, back to back (kin's framing: `rec-len`, `rec-next`).
fn records(c: &mut Cells) -> Vec<Vec<i32>> {
    let n = cells_len(c);
    let mut at = 0;
    let mut out = Vec::new();
    while at < n {
        let ln = kp::rec_len(c, at);
        out.push((at..at + ln).map(|i| cells_at(c, i)).collect());
        at = kp::rec_next(c, at);
    }
    out
}

// The spans of input `me` that a request names (kin's `pl-spans`): a `decode`'s, and a `decode-img`'s codestream and globals when they
// are the input's own, as (offset, length).
pub fn spans(req: &Request, me: i32) -> Vec<(u64, u64)> {
    let mut c = Cells::new();
    for &v in &req.rec {
        cells_push(&mut c, v);
    }
    let mut out = Cells::new();
    lp::pl_spans(&mut c, 0, me, &mut out);
    (0..cells_len(&out) / 2).map(|i| (cells_at(&out, 2 * i) as u64, cells_at(&out, 2 * i + 1) as u64)).collect()
}

// Which port fulfils a request of this kind (kin's `pl-route`): `ocr` says whether the host has an engine, `render` a renderer.
// `Route::Unsupported` is an answer, not an error (D199): `unsupported_answer` makes it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Route {
    Decode,
    DecodeImg,
    Ocr,
    Render,
    Unsupported,
    None,
}

pub fn route(kind: i32, ocr: bool, render: bool) -> Route {
    let ports = (if ocr { lp::pp_ocr() } else { 0 }) + (if render { lp::pp_render() } else { 0 });
    let r = lp::pl_route(kind, ports);
    if r == lp::rt_decode() {
        Route::Decode
    } else if r == lp::rt_decode_img() {
        Route::DecodeImg
    } else if r == lp::rt_ocr() {
        Route::Ocr
    } else if r == lp::rt_render() {
        Route::Render
    } else if r == lp::rt_unsupported() {
        Route::Unsupported
    } else {
        Route::None
    }
}

// The answer of a request no port is configured for.
pub fn unsupported_answer(req: &Request) -> Answer {
    let mut c = Cells::new();
    lp::pl_unsupported(&mut c, req.kind(), req.id());
    Answer { rec: records(&mut c).remove(0), bitmap: None }
}

// Run one unit to completion. `unit` is kin's function, closed over what it
// needs (its `Src` views, its output arenas): it is handed the answers so far
// and an EMPTY requests `Cells` (both `&mut`: emon's `Cells` tag is, for now),
// and answers a unit status. `ports` fulfils render/decode/ocr; `bytes` fulfils
// the loop's own `bytes` requests; `rec`, when given, records every
// render/decode/ocr pair (never `bytes`) in a deterministic order.
pub fn run(
    cfg: &Config,
    unit: &mut dyn FnMut(&mut Cells, &mut Cells) -> i32,
    ports: &mut dyn Fulfiller,
    bytes: &mut dyn Fulfiller,
    misses: &mut dyn Misses,
    rec: Option<&mut Transcript>,
) -> Result<Stats, PlanError> {
    run_bms(cfg, &mut |a, r, _, _| unit(a, r), ports, bytes, misses, rec)
}

// `run` for a PAGE UNIT (P7.U, D196): the unit is also handed the `BitmapSet` of this attempt, made from the bitmaps its answers
// carried so far; what kin made in it (`bms-put`) is added to the bitmaps a fulfiller sees, so an `ocr` request may name a
// kin-made view. The bitmaps are this run's alone: they are dropped when the unit ends, so a conversion holds one page's.
pub fn run_bms(
    cfg: &Config,
    unit: &mut dyn FnMut(&mut Cells, &mut Cells, &BitmapSet, &mut Buf) -> i32,
    ports: &mut dyn Fulfiller,
    bytes: &mut dyn Fulfiller,
    misses: &mut dyn Misses,
    mut rec: Option<&mut Transcript>,
) -> Result<Stats, PlanError> {
    let mut m = Machine::new(cfg);
    loop {
        match m.attempt(unit, misses)? {
            Next::Done => return Ok(m.stats()),
            Next::Fetch(_) => {
                m.fulfil(bytes)?;
            }
            Next::Requests(..) => {
                let entries = m.fulfil(ports)?;
                if let Some(t) = rec.as_deref_mut() {
                    t.entries.extend(entries);
                }
            }
        }
    }
}

// THE LOOP AS A MACHINE (P9.3). `run_bms` above is a synchronous driver over it; a host whose fulfilments cannot block (the
// browser: a JS `read` is a promise) drives it itself: `attempt` runs one attempt of the unit and says what the host must do
// before the next (fetch these byte ranges, fulfil these requests, or nothing: the unit is done), and the host reports back
// with `fulfil`, or with `offer` for each answer and `commit`. The loop's rules (an attempt that missed is discarded whole, ids
// and rounds, the named limits, the order of the answers) are kin's (`emon.planloop`), so a native host and a browser keep them
// alike. The machine lasts a whole conversation: `end_unit` folds a finished unit's accounting into the totals and starts the
// next (`next_phase` says which, kin's `en-next`).
pub enum Next {
    // the unit finished
    Done,
    // the attempt missed: fetch these `bytes` requests (kind 4, `[6 4 -n input offset length]`, read by `kp::bq_*`), then `attempt` again
    Fetch(Vec<Request>),
    // the unit asked for these (sorted by id; the bitmaps are every one answered so far and the unit's own views): fulfil them,
    // `answered` with an answer for each, then `attempt` again
    Requests(Vec<Request>, Bitmaps),
}

// What the finished units counted (kin's `t-*` and the page units' rounds and requests).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Totals {
    pub attempts: i32,
    pub fetch_rounds: i32,
    pub bytes_requests: i32,
    pub requests: i32,
    pub page_rounds: Vec<i32>,
    pub page_requests: Vec<i32>,
}

pub struct Machine {
    p: Plan,
    bitmaps: Bitmaps,
    all: Bitmaps,
    reqs: Vec<Request>,
    staged: HashMap<i32, Option<Arc<Bitmap>>>,
}

impl Machine {
    pub fn new(cfg: &Config) -> Machine {
        let mut p = Plan::new();
        p.max_rounds = cfg.max_rounds;
        p.fetch_limit = cfg.fetch_rounds;
        Machine { p, bitmaps: Bitmaps::new(), all: Bitmaps::new(), reqs: Vec::new(), staged: HashMap::new() }
    }

    // What the current unit has counted.
    pub fn stats(&self) -> Stats {
        Stats { attempts: self.p.attempts, rounds: self.p.rounds, requests: self.p.requests, fetch_rounds: self.p.fetch_rounds, bytes_requests: self.p.bytes_requests }
    }

    // The error kin holds, as the loop reports it.
    fn error(&self) -> PlanError {
        let code = self.p.err;
        if code == lp::pe_max_rounds() || code == lp::pe_fetch_rounds() {
            PlanError::Limit { name: pe_name(code), limit: self.p.err_limit, actual: self.p.err_actual }
        } else if code == lp::pe_unit_failed() {
            PlanError::Unit(self.p.err_actual)
        } else if code == lp::pe_stalled() {
            PlanError::Stalled
        } else if code == lp::pe_fulfil_failed() {
            PlanError::Fulfil(self.p.msg.clone())
        } else {
            PlanError::Bad(self.p.msg.clone())
        }
    }

    // The round kin holds, as requests.
    fn round(&mut self, payload: Option<Arc<Vec<u8>>>) -> Vec<Request> {
        let mut v: Vec<Request> = records(&mut self.p.reqs).into_iter().map(Request::new).collect();
        if let Some(pl) = payload {
            for r in v.iter_mut() {
                r.payload = pl.clone();
            }
        }
        v
    }

    // One attempt of `unit` from scratch on the answers so far.
    pub fn attempt(
        &mut self,
        unit: &mut dyn FnMut(&mut Cells, &mut Cells, &BitmapSet, &mut Buf) -> i32,
        misses: &mut dyn Misses,
    ) -> Result<Next, PlanError> {
        lp::pl_attempt(&mut self.p);
        self.reqs.clear();
        self.staged.clear();
        self.all.clear();
        let bms = BitmapSet::with_answers(&self.bitmaps);
        let mut payload = Buf::new();
        let status = unit(&mut self.p.ans, &mut self.p.rq, &bms, &mut payload);
        let made = bms.made();
        drop(bms);
        let act = lp::pl_after(&mut self.p, status);
        if act == lp::act_done() {
            Ok(Next::Done)
        } else if act == lp::act_miss() {
            // The attempt missed: it is discarded whole, requests too. The ranges the source missed are the host's to say; what they
            // become, and what none means, is kin's.
            let round = lp::pl_miss_round(&mut self.p);
            for r in misses.take(round) {
                cells_push(&mut self.p.cand, r.input);
                cells_push(&mut self.p.cand, r.offset);
                cells_push(&mut self.p.cand, r.length);
            }
            if lp::pl_fetch(&mut self.p) != lp::act_fetch() {
                return Err(self.error());
            }
            self.reqs = self.round(None);
            Ok(Next::Fetch(self.reqs.clone()))
        } else if act == lp::act_requests() {
            let shared = if payload.0.is_empty() { None } else { Some(Arc::new(std::mem::take(&mut payload.0))) };
            self.reqs = self.round(shared);
            self.all = if made.is_empty() {
                self.bitmaps.clone()
            } else {
                let mut all = self.bitmaps.clone();
                all.extend(made);
                all
            };
            Ok(Next::Requests(self.reqs.clone(), self.all.clone()))
        } else {
            Err(self.error())
        }
    }

    // The requests of the round in progress, sorted by id.
    pub fn requests(&self) -> &[Request] {
        &self.reqs
    }

    // Every bitmap the current round's fulfillers see: answered so far, and the unit's own views.
    pub fn bitmap(&self, id: i32) -> Option<Arc<Bitmap>> {
        self.all.get(&id).cloned()
    }

    pub fn bitmaps(&self) -> &Bitmaps {
        &self.all
    }

    // An answer to the round, in whatever order it arrives: refused (`bad-record`) when it answers nothing the round asked.
    pub fn offer(&mut self, a: Answer) -> Result<(), PlanError> {
        cells_drop(&mut self.p.cur, 0);
        for &v in &a.rec {
            cells_push(&mut self.p.cur, v);
        }
        if lp::pl_offer(&mut self.p) != 0 {
            return Err(self.error());
        }
        self.staged.insert(a.rec[2], a.bitmap);
        Ok(())
    }

    // Whether the round has an answer to request `id`.
    pub fn has(&mut self, id: i32) -> bool {
        lp::pl_has(&mut self.p, id) == 1
    }

    // How many requests of the round have no answer yet.
    pub fn owed(&mut self) -> i32 {
        lp::pl_owed(&mut self.p)
    }

    // Every request of the round is answered: the answers join the unit's, in id order. The entries are what a transcript records
    // (round, request, answer, bitmap), in id order; a `bytes` round has none.
    pub fn commit(&mut self) -> Result<Vec<Entry>, PlanError> {
        let requests_round = self.p.outk == 2;
        if lp::pl_commit(&mut self.p) != 0 {
            return Err(self.error());
        }
        let mut out = Vec::new();
        let n = cells_len(&self.p.tx);
        let mut at = 0;
        while at < n {
            let round = lp::tx_round(&mut self.p.tx, at);
            let (rq, an) = (lp::tx_req(&mut self.p.tx, at), lp::tx_ans(&mut self.p.tx, at));
            let next = lp::tx_next(&mut self.p.tx, at);
            let req: Vec<i32> = (rq..an).map(|i| cells_at(&self.p.tx, i)).collect();
            let ans: Vec<i32> = (an..next).map(|i| cells_at(&self.p.tx, i)).collect();
            let bitmap = self.staged.remove(&ans[2]).flatten();
            if let Some(b) = &bitmap {
                self.bitmaps.insert(ans[2], b.clone());
            }
            out.push(Entry { round, req, ans, bitmap });
            at = next;
        }
        cells_drop(&mut self.p.tx, 0);
        self.staged.clear();
        if requests_round {
            self.reqs.clear();
            self.all.clear();
        }
        Ok(out)
    }

    // The answers to the last round, one for each, in any order: offered, then committed.
    pub fn answered(&mut self, got: Vec<Answer>) -> Result<Vec<Entry>, PlanError> {
        for a in got {
            self.offer(a)?;
        }
        self.commit()
    }

    // Start every request of the round with `f` and take every completion; the round is committed.
    pub fn fulfil(&mut self, f: &mut dyn Fulfiller) -> Result<Vec<Entry>, PlanError> {
        let none = Bitmaps::new();
        let all = if self.p.outk == 2 { &self.all } else { &none };
        for r in &self.reqs {
            f.start(r, all)?;
        }
        for _ in 0..self.reqs.len() {
            let a = f.next()?;
            self.offer(a)?;
        }
        self.commit()
    }

    // The unit is finished: its accounting joins the totals, and the next unit starts with no answers and no bitmaps.
    pub fn end_unit(&mut self) {
        lp::pl_fold(&mut self.p);
        self.bitmaps.clear();
        self.all.clear();
        self.reqs.clear();
        self.staged.clear();
    }

    // THE SCHEDULE (kin's `en-next`): the unit that follows, as a `ph-*` code. `kind` is what the document pass built (0 nothing,
    // 1 a PDF, 2 a raster input), `assets` whether the asset level is on, `pending` whether the figures unit is still to run.
    pub fn next_phase(&mut self, kind: i32, assets: bool, pending: bool, np: usize) -> i32 {
        lp::en_next(&mut self.p, kind, assets as i32, pending as i32, np as i32)
    }

    pub fn phase(&self) -> i32 {
        self.p.phase
    }

    // The page unit the schedule is on.
    pub fn page(&self) -> usize {
        self.p.page as usize
    }

    pub fn totals(&self) -> Totals {
        let (pr, pq) = (records_plain(&self.p.pgr), records_plain(&self.p.pgq));
        Totals {
            attempts: self.p.t_attempts,
            fetch_rounds: self.p.t_fetch_rounds,
            bytes_requests: self.p.t_bytes_requests,
            requests: self.p.t_requests,
            page_rounds: pr,
            page_requests: pq,
        }
    }
}

fn records_plain(c: &Cells) -> Vec<i32> {
    (0..cells_len(c)).map(|i| cells_at(c, i)).collect()
}

// --------------------------------------------------------------- transcript

#[derive(Debug, Clone)]
pub struct Entry {
    pub round: i32,
    pub req: Vec<i32>,
    pub ans: Vec<i32>,
    pub bitmap: Option<Arc<Bitmap>>,
}

#[derive(Debug, Clone, Default)]
pub struct Transcript {
    // Per input: its index and its length -- never its bytes, origin or fetch
    // count (SPEC 13.1). A password is never here.
    pub inputs: Vec<(i32, i32)>,
    pub entries: Vec<Entry>,
    // WHO ANSWERED (D214): the live ports' identities as name/value strings (an OCR engine and its version, the
    // models it read with), written as `:producer {...}` when there are any and never read back for a replay: a
    // replay depends on the answers, not on whose engine gave them. Empty for a run with no live port that
    // identifies itself, which leaves the file as it always was.
    pub producer: Vec<(String, String)>,
}

pub const TRANSCRIPT_FILE: &str = "transcript.edn";

// THE FILE IS KIN'S (P10.9, `kin/transcript.kin`, D438): the format (version 1, byte for byte), the kind names, the escaping of the producer, the reader
// and its refusals, the map a replay is answered from and the count of entries it never asked for. This file keeps what is not observable: the
// bitmaps (a PNG for each, decoded by `png_decode` until P10.11's transcript PNG reader), the in-memory `Transcript` the embedder holds, and the
// `Fulfiller` that hands an answer back to the loop. `kin()` is the transcript as kin holds it.

// A string as code points: the form kin's names and values are in.
fn cps(s: &str) -> Cells {
    let mut c = Cells::new();
    for ch in s.chars() {
        cells_push(&mut c, ch as i32);
    }
    c
}

// A record, as kin frames it.
fn cells_of(v: &[i32]) -> Cells {
    let mut c = Cells::new();
    for &x in v {
        cells_push(&mut c, x);
    }
    c
}

// A name of kin's `tb`: the sentence of a reason.
fn tb_name(code: i32) -> String {
    let mut s = String::new();
    kt::tb_emit_name(&mut s, code);
    s
}

impl Transcript {
    // The transcript as kin holds it (`Tr`), the bitmaps left behind: the entry at a place has one when its flag says.
    fn kin(&self) -> Tr {
        let mut tr = Tr::new();
        for (i, l) in &self.inputs {
            kt::tr_add_input(&mut tr, *i, *l);
        }
        for (k, v) in &self.producer {
            kt::tr_add_producer(&mut tr, &mut cps(k), &mut cps(v));
        }
        for e in &self.entries {
            let mut c = cells_of(&e.req);
            for &x in &e.ans {
                cells_push(&mut c, x);
            }
            kt::tr_add_entry(&mut tr, e.round, &mut c, 0, e.req.len() as i32, e.req.len() as i32, e.ans.len() as i32, e.bitmap.is_some() as i32);
        }
        tr
    }

    // The files of a transcript, in a fixed order: `transcript.edn`, then one
    // lossless PNG per bitmap named by its entry's index. DETERMINISTIC: the
    // same entries always give the same bytes.
    pub fn to_files(&self) -> Vec<(String, Vec<u8>)> {
        let mut tr = self.kin();
        let mut s = String::new();
        kt::tr_write(&mut tr, &mut s);
        let mut files: Vec<(String, Vec<u8>)> = vec![(TRANSCRIPT_FILE.to_string(), s.into_bytes())];
        for (n, e) in self.entries.iter().enumerate() {
            if let Some(b) = &e.bitmap {
                let mut name = String::new();
                kt::tr_bitmap_name(n as i32, &mut name);
                files.push((name, png_encode(b)));
            }
        }
        files
    }

    pub fn from_files(files: &BTreeMap<String, Vec<u8>>) -> Result<Transcript, PlanError> {
        let raw = files.get(TRANSCRIPT_FILE).ok_or_else(|| PlanError::TranscriptBad(tb_name(kt::tb_no_file())))?;
        let mut tr = Tr::new();
        if kt::tr_read(&mut tr, raw) != 0 {
            return Err(PlanError::TranscriptBad(tr.msg));
        }
        let mut t = Transcript::default();
        for i in 0..kt::tr_n_inputs(&mut tr) {
            t.inputs.push((kt::tr_in_index(&mut tr, i), kt::tr_in_length(&mut tr, i)));
        }
        let mut p = 0;
        while kt::tr_p_more(&mut tr, p) == 1 {
            let (mut k, mut v) = (String::new(), String::new());
            kt::tr_p_key(&mut tr, p, &mut k);
            kt::tr_p_val(&mut tr, p, &mut v);
            t.producer.push((k, v));
            p = kt::tr_p_next(&mut tr, p);
        }
        let mut e = 0;
        while kt::tr_e_more(&mut tr, e) == 1 {
            let (rq, rl) = (kt::tr_e_req(&mut tr, e), kt::tr_e_req_len(&mut tr, e));
            let (an, al) = (kt::tr_e_ans(&mut tr, e), kt::tr_e_ans_len(&mut tr, e));
            let req: Vec<i32> = (rq..rq + rl).map(|i| cells_at(&tr.ents, i)).collect();
            let ans: Vec<i32> = (an..an + al).map(|i| cells_at(&tr.ents, i)).collect();
            let mut name = String::new();
            let bitmap = if kt::tr_e_bitmap_name(&mut tr, e, &mut name) == 1 {
                let png = files.get(&name).ok_or_else(|| PlanError::TranscriptBad(format!("{} {}", name, tb_name(kt::tb_bitmap_missing()))))?;
                Some(Arc::new(png_decode(png).map_err(PlanError::TranscriptBad)?))
            } else {
                None
            };
            t.entries.push(Entry { round: kt::tr_e_round(&mut tr, e), req, ans, bitmap });
            e = kt::tr_e_next(&mut tr, e);
        }
        Ok(t)
    }

    // Replay must be given the inputs the recording saw: same count, same lengths.
    pub fn check_inputs(&self, inputs: &[(i32, i32)]) -> Result<(), PlanError> {
        let mut tr = Tr::new();
        for (i, l) in &self.inputs {
            kt::tr_add_input(&mut tr, *i, *l);
        }
        let mut want = Cells::new();
        for (i, l) in inputs {
            cells_push(&mut want, *i);
            cells_push(&mut want, *l);
        }
        let mut s = String::new();
        if kt::tr_check_inputs(&mut tr, &mut want, &mut s) != 0 {
            return Err(PlanError::TranscriptBad(s));
        }
        Ok(())
    }
}

// A fulfiller that answers from a transcript: a request it has no entry for is
// `transcript-miss`, never a guess. `bytes` is not here and cannot be. The map it
// answers from is kin's (`tr-index`, `tr-take`): keyed by the whole request record.
pub struct Replayer {
    tr: Tr,
    bitmaps: Vec<Option<Arc<Bitmap>>>,
    key: Cells,
    done: VecDeque<Result<Answer, PlanError>>,
}

impl Replayer {
    pub fn new(t: &Transcript) -> Replayer {
        let mut tr = t.kin();
        kt::tr_index(&mut tr);
        Replayer { tr, bitmaps: t.entries.iter().map(|e| e.bitmap.clone()).collect(), key: Cells::new(), done: VecDeque::new() }
    }
    // How many recorded entries the run never asked for.
    pub fn unused(&mut self) -> usize {
        kt::tr_unused(&mut self.tr) as usize
    }
    // The run is over: an error (`transcript-bad`, with kin's sentence) when it left recorded entries unasked, which is a transcript of another run.
    pub fn check_done(&mut self) -> Result<(), PlanError> {
        if kt::tr_unused(&mut self.tr) == 0 {
            return Ok(());
        }
        let mut s = String::new();
        kt::tr_unused_msg(&mut self.tr, &mut s);
        Err(PlanError::TranscriptBad(s))
    }
}

impl Fulfiller for Replayer {
    fn start(&mut self, req: &Request, _bitmaps: &Bitmaps) -> Result<(), PlanError> {
        cells_drop(&mut self.key, 0);
        for &v in &req.rec {
            cells_push(&mut self.key, v);
        }
        let e = kt::tr_take(&mut self.tr, &mut self.key, 0, req.rec.len() as i32);
        let r = if e >= 0 {
            let (an, al) = (kt::tr_e_ans(&mut self.tr, e), kt::tr_e_ans_len(&mut self.tr, e));
            let n = kt::tr_e_n(&mut self.tr, e) as usize;
            Ok(Answer { rec: (an..an + al).map(|i| cells_at(&self.tr.ents, i)).collect(), bitmap: self.bitmaps[n].clone() })
        } else {
            Err(PlanError::TranscriptMiss(req.rec.clone()))
        };
        self.done.push_back(r);
        Ok(())
    }
    fn next(&mut self) -> Result<Answer, PlanError> {
        self.done.pop_front().expect("next without a started request")
    }
}

// ------------------------------------------------------------------- PNG
// Writing: emon's own canonical PNG encoder (`emon.png`, D124, SPEC 10.11),
// compressed and byte-identical on all four targets, so a recording is a
// function of the pixels and every host writes the same file (D125 revises
// D090). The encoder is kin; this only feeds it rows.
// Reading: any valid non-interlaced PNG (gray, gray+alpha, RGB, RGBA, palette,
// depths 1 to 16), whoever wrote it; the alpha channel is dropped and a palette
// is expanded, so the bitmap is gray (1) or RGB (3).

pub fn png_encode(b: &Bitmap) -> Vec<u8> {
    use crate::emon::png::{png_begin, png_end, png_row};
    use crate::port::{cells_drop, Buf};
    let (w, h, ch) = (b.width(), b.height(), b.channels());
    let px = b.to_bytes();
    let (mut ws, mut out, mut z, mut pal, mut row) =
        (Cells::new(), Buf::new(), Buf::new(), Cells::new(), Cells::new());
    png_begin(&mut ws, &mut out, &mut z, w, h, if ch == 1 { 0 } else { 2 }, 8, 0, &mut pal, 0, 0);
    let n = (w * ch) as usize;
    for y in 0..h as usize {
        cells_drop(&mut row, 0);
        for &v in &px[y * n..(y + 1) * n] {
            cells_push(&mut row, v as i32);
        }
        png_row(&mut ws, &mut out, &mut z, &mut row, 0);
    }
    png_end(&mut ws, &mut out, &mut z);
    out.0
}

fn crc32(data: &[u8]) -> u32 {
    let mut c: u32 = 0xFFFF_FFFF;
    for &b in data {
        c ^= b as u32;
        for _ in 0..8 {
            c = if c & 1 != 0 { (c >> 1) ^ 0xEDB8_8320 } else { c >> 1 };
        }
    }
    !c
}

fn adler32(data: &[u8]) -> u32 {
    let (mut a, mut b) = (1u32, 0u32);
    for &x in data {
        a = (a + x as u32) % 65521;
        b = (b + a) % 65521;
    }
    (b << 16) | a
}

// RFC 1951, after zlib's puff: canonical Huffman decoding by code length counts.
struct Bits<'a> {
    d: &'a [u8],
    pos: usize,
    bit: u32,
    cnt: u32,
}

impl<'a> Bits<'a> {
    fn need(&mut self, n: u32) -> Result<u32, String> {
        while self.cnt < n {
            if self.pos >= self.d.len() {
                return Err("a truncated zlib stream".to_string());
            }
            self.bit |= (self.d[self.pos] as u32) << self.cnt;
            self.pos += 1;
            self.cnt += 8;
        }
        let v = self.bit & ((1u32 << n) - 1);
        self.bit >>= n;
        self.cnt -= n;
        Ok(v)
    }
}

struct Huff {
    count: [u16; 16],
    symbol: Vec<u16>,
}

fn huff(lengths: &[u8]) -> Result<Huff, String> {
    let mut count = [0u16; 16];
    for &l in lengths {
        count[l as usize] += 1;
    }
    let mut left: i32 = 1;
    for len in 1..16 {
        left = (left << 1) - count[len] as i32;
        if left < 0 {
            return Err("a zlib stream with an over-subscribed code".to_string());
        }
    }
    let mut offs = [0u16; 16];
    for len in 1..15 {
        offs[len + 1] = offs[len] + count[len];
    }
    let mut symbol = vec![0u16; lengths.len()];
    for (s, &l) in lengths.iter().enumerate() {
        if l != 0 {
            symbol[offs[l as usize] as usize] = s as u16;
            offs[l as usize] += 1;
        }
    }
    Ok(Huff { count, symbol })
}

fn decode_sym(b: &mut Bits, h: &Huff) -> Result<usize, String> {
    let (mut code, mut first, mut index) = (0i32, 0i32, 0i32);
    for len in 1..16 {
        code |= b.need(1)? as i32;
        let count = h.count[len] as i32;
        if code - count < first {
            return Ok(h.symbol[(index + (code - first)) as usize] as usize);
        }
        index += count;
        first += count;
        first <<= 1;
        code <<= 1;
    }
    Err("a zlib stream with a bad code".to_string())
}

const LBASE: [u16; 29] = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258];
const LEXT: [u8; 29] = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0];
const DBASE: [u16; 30] = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577];
const DEXT: [u8; 30] = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13];

fn inflate_codes(b: &mut Bits, out: &mut Vec<u8>, lit: &Huff, dist: &Huff) -> Result<(), String> {
    loop {
        let s = decode_sym(b, lit)?;
        if s < 256 {
            out.push(s as u8);
        } else if s == 256 {
            return Ok(());
        } else {
            let s = s - 257;
            if s >= 29 {
                return Err("a zlib stream with a bad length code".to_string());
            }
            let len = LBASE[s] as usize + b.need(LEXT[s] as u32)? as usize;
            let ds = decode_sym(b, dist)?;
            if ds >= 30 {
                return Err("a zlib stream with a bad distance code".to_string());
            }
            let d = DBASE[ds] as usize + b.need(DEXT[ds] as u32)? as usize;
            if d > out.len() {
                return Err("a zlib stream that reaches before its start".to_string());
            }
            for _ in 0..len {
                out.push(out[out.len() - d]);
            }
        }
    }
}

fn zlib_inflate(z: &[u8]) -> Result<Vec<u8>, String> {
    if z.len() < 6 || z[0] & 15 != 8 || ((z[0] as u32) << 8 | z[1] as u32) % 31 != 0 || z[1] & 0x20 != 0 {
        return Err("a bad zlib header".to_string());
    }
    let mut b = Bits { d: z, pos: 2, bit: 0, cnt: 0 };
    let mut out: Vec<u8> = Vec::new();
    loop {
        let last = b.need(1)?;
        match b.need(2)? {
            0 => {
                b.bit = 0;
                b.cnt = 0;
                if b.pos + 4 > z.len() {
                    return Err("a truncated stored block".to_string());
                }
                let n = u16::from_le_bytes([z[b.pos], z[b.pos + 1]]) as usize;
                let nn = u16::from_le_bytes([z[b.pos + 2], z[b.pos + 3]]) as usize;
                if n != (!nn & 0xFFFF) || b.pos + 4 + n > z.len() {
                    return Err("a truncated stored block".to_string());
                }
                out.extend_from_slice(&z[b.pos + 4..b.pos + 4 + n]);
                b.pos += 4 + n;
            }
            1 => {
                let mut l = [0u8; 288];
                for i in 0..288 {
                    l[i] = if i < 144 { 8 } else if i < 256 { 9 } else if i < 280 { 7 } else { 8 };
                }
                inflate_codes(&mut b, &mut out, &huff(&l)?, &huff(&[5u8; 30])?)?;
            }
            2 => {
                let nlen = b.need(5)? as usize + 257;
                let ndist = b.need(5)? as usize + 1;
                let ncode = b.need(4)? as usize + 4;
                if nlen > 286 || ndist > 30 {
                    return Err("a zlib stream with bad code counts".to_string());
                }
                const ORDER: [usize; 19] = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];
                let mut cl = [0u8; 19];
                for i in 0..ncode {
                    cl[ORDER[i]] = b.need(3)? as u8;
                }
                let ch = huff(&cl)?;
                let mut lens = vec![0u8; nlen + ndist];
                let mut i = 0;
                while i < nlen + ndist {
                    let s = decode_sym(&mut b, &ch)?;
                    if s < 16 {
                        lens[i] = s as u8;
                        i += 1;
                    } else {
                        let (v, rep) = match s {
                            16 => {
                                if i == 0 {
                                    return Err("a zlib stream repeating nothing".to_string());
                                }
                                (lens[i - 1], 3 + b.need(2)? as usize)
                            }
                            17 => (0, 3 + b.need(3)? as usize),
                            _ => (0, 11 + b.need(7)? as usize),
                        };
                        if i + rep > nlen + ndist {
                            return Err("a zlib stream with too many code lengths".to_string());
                        }
                        for _ in 0..rep {
                            lens[i] = v;
                            i += 1;
                        }
                    }
                }
                inflate_codes(&mut b, &mut out, &huff(&lens[..nlen])?, &huff(&lens[nlen..])?)?;
            }
            _ => return Err("a zlib stream with a bad block type".to_string()),
        }
        if last == 1 {
            break;
        }
    }
    let j = b.pos;
    if j + 4 > z.len() || u32::from_be_bytes([z[j], z[j + 1], z[j + 2], z[j + 3]]) != adler32(&out) {
        return Err("a zlib stream fails its checksum".to_string());
    }
    Ok(out)
}

pub fn png_decode(p: &[u8]) -> Result<Bitmap, String> {
    if p.len() < 8 || p[..8] != [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A] {
        return Err("not a PNG".to_string());
    }
    let (mut w, mut h, mut depth, mut ct) = (0usize, 0usize, 0usize, 255u8);
    let mut have_ihdr = false;
    let mut plte: Vec<u8> = Vec::new();
    let mut idat: Vec<u8> = Vec::new();
    let mut i = 8;
    while i + 12 <= p.len() {
        let n = u32::from_be_bytes([p[i], p[i + 1], p[i + 2], p[i + 3]]) as usize;
        if i + 12 + n > p.len() {
            return Err("a truncated PNG chunk".to_string());
        }
        let kind = &p[i + 4..i + 8];
        let body = &p[i + 8..i + 8 + n];
        let crc = u32::from_be_bytes([p[i + 8 + n], p[i + 9 + n], p[i + 10 + n], p[i + 11 + n]]);
        if crc != crc32(&p[i + 4..i + 8 + n]) {
            return Err("a PNG chunk fails its checksum".to_string());
        }
        match kind {
            b"IHDR" => {
                if n < 13 {
                    return Err("a truncated PNG header".to_string());
                }
                w = u32::from_be_bytes([body[0], body[1], body[2], body[3]]) as usize;
                h = u32::from_be_bytes([body[4], body[5], body[6], body[7]]) as usize;
                depth = body[8] as usize;
                ct = body[9];
                if body[10] != 0 || body[11] != 0 || body[12] != 0 {
                    return Err("png-unsupported: only filter method 0 and no interlace".to_string());
                }
                have_ihdr = true;
            }
            b"PLTE" => plte = body.to_vec(),
            b"IDAT" => idat.extend_from_slice(body),
            b"IEND" => break,
            _ => {}
        }
        i += 12 + n;
    }
    if !have_ihdr || idat.is_empty() {
        return Err("a PNG without header or data".to_string());
    }
    let chan = match ct {
        0 | 3 => 1,
        2 => 3,
        4 => 2,
        6 => 4,
        _ => return Err("png-unsupported: colour type".to_string()),
    };
    let ok = match ct {
        0 => matches!(depth, 1 | 2 | 4 | 8 | 16),
        3 => matches!(depth, 1 | 2 | 4 | 8),
        _ => matches!(depth, 8 | 16),
    };
    if !ok {
        return Err("png-unsupported: bit depth".to_string());
    }
    let raw = zlib_inflate(&idat)?;
    let bits = chan * depth;
    let rowb = (w * bits + 7) / 8;
    let bpp = std::cmp::max(1, bits / 8);
    if raw.len() != h * (1 + rowb) {
        return Err("a PNG whose data does not fit its size".to_string());
    }
    let mut sl = vec![0u8; h * rowb];
    for y in 0..h {
        let f = raw[y * (1 + rowb)];
        for x in 0..rowb {
            let v = raw[y * (1 + rowb) + 1 + x] as i32;
            let a = if x >= bpp { sl[y * rowb + x - bpp] as i32 } else { 0 };
            let b = if y > 0 { sl[(y - 1) * rowb + x] as i32 } else { 0 };
            let c = if x >= bpp && y > 0 { sl[(y - 1) * rowb + x - bpp] as i32 } else { 0 };
            let pred = match f {
                0 => 0,
                1 => a,
                2 => b,
                3 => (a + b) / 2,
                4 => {
                    let pp = a + b - c;
                    let (pa, pb, pc) = ((pp - a).abs(), (pp - b).abs(), (pp - c).abs());
                    if pa <= pb && pa <= pc { a } else if pb <= pc { b } else { c }
                }
                _ => return Err("a PNG row with an unknown filter".to_string()),
            };
            sl[y * rowb + x] = (v + pred) as u8;
        }
    }
    // sample `k` of row `y` as 0..255 (sub-byte scaled up, 16-bit high byte)
    let sample = |y: usize, k: usize| -> u32 {
        let r = &sl[y * rowb..(y + 1) * rowb];
        match depth {
            8 => r[k] as u32,
            16 => r[2 * k] as u32,
            d => {
                let per = 8 / d;
                let v = (r[k / per] as u32 >> (8 - d * (k % per + 1))) & ((1 << d) - 1);
                if ct == 3 { v } else { v * 255 / ((1 << d) - 1) }
            }
        }
    };
    let out_ch = if ct == 0 || ct == 4 { 1 } else { 3 };
    let mut px = Vec::with_capacity(w * h * out_ch);
    for y in 0..h {
        for x in 0..w {
            match ct {
                0 => px.push(sample(y, x) as u8),
                4 => px.push(sample(y, 2 * x) as u8),
                2 | 6 => {
                    for c in 0..3 {
                        px.push(sample(y, chan * x + c) as u8);
                    }
                }
                _ => {
                    let idx = sample(y, x) as usize;
                    if idx * 3 + 3 > plte.len() {
                        return Err("a PNG pixel beyond its palette".to_string());
                    }
                    px.extend_from_slice(&plte[idx * 3..idx * 3 + 3]);
                }
            }
        }
    }
    Ok(Bitmap::from_bytes(w as i32, h as i32, out_ch as i32, &px))
}
