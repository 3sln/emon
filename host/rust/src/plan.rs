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
// THE SHAPE IS THE JAVASCRIPT HOST'S TOO: the host owns the loop and the await.
//
//     loop:
//       status = unit(answers, requests)            // kin, from scratch, pure
//       need-bytes -> fetch what the Src missed (`bytes` requests), re-run
//       done       -> return
//       requests   -> START every request, then take completions in WHATEVER
//                     order they arrive, sort them by id, append them to the
//                     answers, record them; re-run
//
// `Fulfiller::start` / `next` is the async shape: a sync host completes inside
// `start` (`SyncFulfiller`); a threaded one or a JS one completes whenever and
// `next` blocks or awaits for the next completion. The loop never depends on
// the order, which is what makes a transcript the same however the answers
// arrived.

use crate::port::{cells_at, cells_len, cells_push, Bitmap, BitmapSet, Cells};
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
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
    // A named limit: `max-rounds` or `fetch-rounds`.
    Limit { name: &'static str, limit: i32, actual: i32 },
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

impl PlanError {
    pub fn name(&self) -> &'static str {
        match self {
            PlanError::Limit { name, .. } => name,
            PlanError::TranscriptMiss(_) => "transcript-miss",
            PlanError::TranscriptBad(_) => "transcript-bad",
            PlanError::Fulfil(_) => "fulfil-failed",
            PlanError::Unit(_) => "unit-failed",
            PlanError::Bad(_) => "bad-record",
            PlanError::Stalled => "need-bytes-stalled",
        }
    }
}

impl std::fmt::Display for PlanError {
    fn fmt(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
        match self {
            PlanError::Limit { name, limit, actual } => {
                write!(f, "{}: the limit is {} and this run reached {}", name, limit, actual)
            }
            PlanError::TranscriptMiss(r) => write!(f, "transcript-miss: no answer recorded for request {:?}", r),
            PlanError::TranscriptBad(s) => write!(f, "transcript-bad: {}", s),
            PlanError::Fulfil(s) => write!(f, "fulfil-failed: {}", s),
            PlanError::Unit(s) => write!(f, "unit-failed: status {}", s),
            PlanError::Bad(s) => write!(f, "bad-record: {}", s),
            PlanError::Stalled => write!(f, "need-bytes-stalled: the unit missed and the source reports nothing missed"),
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
}

impl Request {
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
// `SrcBacking`s (doubling read-ahead, coalescing; D058); the loop only asks.
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

fn record_at(c: &Cells, at: i32, len: i32) -> Vec<i32> {
    (at..at + len).map(|i| cells_at(c, i)).collect()
}

// Take the requests a unit wrote: well formed, kinds render/decode/ocr only
// (`bytes` is the host's), ids positive, unique, and not already answered.
fn read_requests(rq: &Cells, answered: &HashSet<i32>) -> Result<Vec<Request>, PlanError> {
    let n = cells_len(rq);
    let mut at = 0;
    let mut out: Vec<Request> = Vec::new();
    let mut seen: HashSet<i32> = HashSet::new();
    while at < n {
        let len = cells_at(rq, at);
        if len < 3 || at + len > n {
            return Err(PlanError::Bad(format!("a request at {} has length {}", at, len)));
        }
        let rec = record_at(rq, at, len);
        if rec[1] < KIND_RENDER || (rec[1] > KIND_OCR && rec[1] != KIND_CROP && rec[1] != KIND_DECODE_IMG) {
            return Err(PlanError::Bad(format!("request {} has kind {}, which kin may not ask for", rec[2], rec[1])));
        }
        if rec[2] < 1 {
            return Err(PlanError::Bad(format!("a request has id {}", rec[2])));
        }
        if !seen.insert(rec[2]) || answered.contains(&rec[2]) {
            return Err(PlanError::Bad(format!("request id {} is used twice", rec[2])));
        }
        out.push(Request { rec });
        at += len;
    }
    out.sort_by_key(|r| r.id());
    Ok(out)
}

// Start every request and take every completion; answers sorted by id.
fn fulfil(f: &mut dyn Fulfiller, reqs: &[Request], bitmaps: &Bitmaps) -> Result<Vec<Answer>, PlanError> {
    for r in reqs {
        f.start(r, bitmaps)?;
    }
    let mut pending: HashMap<i32, i32> = reqs.iter().map(|r| (r.id(), r.kind())).collect();
    let mut out: Vec<Answer> = Vec::new();
    for _ in 0..reqs.len() {
        let a = f.next()?;
        if a.rec.len() < 4 || a.rec[0] as usize != a.rec.len() {
            return Err(PlanError::Bad(format!("an answer has length {} in a record of {}", a.rec[0], a.rec.len())));
        }
        match pending.remove(&a.id()) {
            Some(kind) if kind == a.kind() => {}
            _ => return Err(PlanError::Bad(format!("an answer for request {} (kind {}) answers nothing asked", a.id(), a.kind()))),
        }
        out.push(a);
    }
    out.sort_by_key(|a| a.id());
    Ok(out)
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
    run_bms(cfg, &mut |a, r, _| unit(a, r), ports, bytes, misses, rec)
}

// `run` for a PAGE UNIT (P7.U, D196): the unit is also handed the `BitmapSet` of this attempt, made from the bitmaps its answers
// carried so far; what kin made in it (`bms-put`) is added to the bitmaps a fulfiller sees, so an `ocr` request may name a
// kin-made view. The bitmaps are this run's alone: they are dropped when the unit ends, so a conversion holds one page's.
pub fn run_bms(
    cfg: &Config,
    unit: &mut dyn FnMut(&mut Cells, &mut Cells, &BitmapSet) -> i32,
    ports: &mut dyn Fulfiller,
    bytes: &mut dyn Fulfiller,
    misses: &mut dyn Misses,
    mut rec: Option<&mut Transcript>,
) -> Result<Stats, PlanError> {
    let mut answers = Cells::new();
    let mut bitmaps = Bitmaps::new();
    let mut answered: HashSet<i32> = HashSet::new();
    let mut st = Stats::default();
    let mut fetch_round = 0;
    let no_bitmaps = Bitmaps::new();
    loop {
        let mut rq = Cells::new();
        st.attempts += 1;
        let bms = BitmapSet::with_answers(&bitmaps);
        let status = unit(&mut answers, &mut rq, &bms);
        let made = bms.made();
        drop(bms);
        match status {
            UNIT_NEED_BYTES => {
                // The attempt missed: discard it whole (its requests too), fetch,
                // re-run. Not a round; counted against `fetch-rounds` alone.
                fetch_round += 1;
                st.fetch_rounds += 1;
                if fetch_round > cfg.fetch_rounds {
                    return Err(PlanError::Limit { name: "fetch-rounds", limit: cfg.fetch_rounds, actual: fetch_round });
                }
                let ranges = misses.take(fetch_round - 1);
                if ranges.is_empty() {
                    return Err(PlanError::Stalled);
                }
                let mut reqs: Vec<Request> = Vec::new();
                for r in &ranges {
                    st.bytes_requests += 1;
                    reqs.push(Request { rec: vec![6, KIND_BYTES, -st.bytes_requests, r.input, r.offset, r.length] });
                }
                for a in fulfil(bytes, &reqs, &no_bitmaps)? {
                    if a.status() != STATUS_OK {
                        return Err(PlanError::Fulfil(format!("fetching bytes: status {}", a.status())));
                    }
                }
                continue;
            }
            UNIT_DONE => {
                if cells_len(&rq) != 0 {
                    return Err(PlanError::Bad("the unit is done and left requests".to_string()));
                }
                return Ok(st);
            }
            UNIT_REQUESTS => {}
            other => return Err(PlanError::Unit(other)),
        }
        fetch_round = 0;
        let reqs = read_requests(&rq, &answered)?;
        if reqs.is_empty() {
            return Err(PlanError::Bad("the unit asked for requests and wrote none".to_string()));
        }
        st.rounds += 1;
        if st.rounds > cfg.max_rounds {
            return Err(PlanError::Limit { name: "max-rounds", limit: cfg.max_rounds, actual: st.rounds });
        }
        st.requests += reqs.len() as i32;
        let round = st.rounds - 1;
        let got = if made.is_empty() {
            fulfil(ports, &reqs, &bitmaps)?
        } else {
            let mut all = bitmaps.clone();
            all.extend(made);
            fulfil(ports, &reqs, &all)?
        };
        for (r, a) in reqs.iter().zip(got.iter()) {
            for &v in &a.rec {
                cells_push(&mut answers, v);
            }
            answered.insert(a.id());
            if let Some(b) = &a.bitmap {
                bitmaps.insert(a.id(), b.clone());
            }
            if let Some(t) = rec.as_deref_mut() {
                t.entries.push(Entry { round, req: r.rec.clone(), ans: a.rec.clone(), bitmap: a.bitmap.clone() });
            }
        }
    }
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

fn kind_name(k: i32) -> &'static str {
    match k {
        KIND_RENDER => "render",
        KIND_DECODE => "decode",
        KIND_OCR => "ocr",
        KIND_CROP => "crop",
        KIND_DECODE_IMG => "decode-img",
        _ => "unknown",
    }
}

fn kind_code(s: &str) -> Option<i32> {
    match s {
        "render" => Some(KIND_RENDER),
        "decode" => Some(KIND_DECODE),
        "ocr" => Some(KIND_OCR),
        "crop" => Some(KIND_CROP),
        "decode-img" => Some(KIND_DECODE_IMG),
        _ => None,
    }
}

fn ints(v: &[i32]) -> String {
    let s: Vec<String> = v.iter().map(|x| x.to_string()).collect();
    format!("[{}]", s.join(" "))
}

impl Transcript {
    // The files of a transcript, in a fixed order: `transcript.edn`, then one
    // lossless PNG per bitmap named by its entry's index. DETERMINISTIC: the
    // same entries always give the same bytes.
    pub fn to_files(&self) -> Vec<(String, Vec<u8>)> {
        let mut files: Vec<(String, Vec<u8>)> = Vec::new();
        let mut s = String::new();
        s.push_str("{:emon-transcript 1\n");
        if !self.producer.is_empty() {
            let ps: Vec<String> = self.producer.iter().map(|(k, v)| format!(":{} \"{}\"", k, v.replace('\\', "/").replace('"', "'"))).collect();
            s.push_str(&format!(" :producer {{{}}}\n", ps.join(" ")));
        }
        s.push_str(" :inputs [");
        let ins: Vec<String> = self.inputs.iter().map(|(i, l)| format!("{{:index {} :length {}}}", i, l)).collect();
        s.push_str(&ins.join(" "));
        s.push_str("]\n :entries [");
        let mut pngs: Vec<(String, Vec<u8>)> = Vec::new();
        for (n, e) in self.entries.iter().enumerate() {
            s.push_str("\n  ");
            s.push_str(&format!(
                "{{:round {} :kind :{} :req {} :ans {}",
                e.round,
                kind_name(e.req.get(1).copied().unwrap_or(0)),
                ints(&e.req),
                ints(&e.ans)
            ));
            if let Some(b) = &e.bitmap {
                let name = format!("bitmap-{:04}.png", n);
                s.push_str(&format!(" :bitmap \"{}\"", name));
                pngs.push((name, png_encode(b)));
            }
            s.push('}');
        }
        s.push_str("]}\n");
        files.push((TRANSCRIPT_FILE.to_string(), s.into_bytes()));
        files.extend(pngs);
        files
    }

    pub fn from_files(files: &BTreeMap<String, Vec<u8>>) -> Result<Transcript, PlanError> {
        let bad = |m: &str| PlanError::TranscriptBad(m.to_string());
        let raw = files.get(TRANSCRIPT_FILE).ok_or_else(|| bad("no transcript.edn"))?;
        let text = String::from_utf8(raw.clone()).map_err(|_| bad("transcript.edn is not UTF-8"))?;
        let top = Edn::parse(&text).map_err(|e| PlanError::TranscriptBad(e))?;
        let m = top.map().ok_or_else(|| bad("the transcript is not a map"))?;
        if m.get("emon-transcript").and_then(|v| v.int()) != Some(1) {
            return Err(bad("not an emon-transcript of version 1"));
        }
        let mut t = Transcript::default();
        if let Some(p) = m.get("producer").and_then(|v| v.map()) {
            for (k, v) in p.0.iter() {
                if let Some(sv) = v.str() {
                    t.producer.push((k.clone(), sv.to_string()));
                }
            }
        }
        for i in m.get("inputs").and_then(|v| v.vec()).ok_or_else(|| bad("no :inputs"))? {
            let im = i.map().ok_or_else(|| bad("an input is not a map"))?;
            let idx = im.get("index").and_then(|v| v.int()).ok_or_else(|| bad("an input has no :index"))?;
            let len = im.get("length").and_then(|v| v.int()).ok_or_else(|| bad("an input has no :length"))?;
            t.inputs.push((idx as i32, len as i32));
        }
        for e in m.get("entries").and_then(|v| v.vec()).ok_or_else(|| bad("no :entries"))? {
            let em = e.map().ok_or_else(|| bad("an entry is not a map"))?;
            let round = em.get("round").and_then(|v| v.int()).ok_or_else(|| bad("an entry has no :round"))? as i32;
            let kind = em.get("kind").and_then(|v| v.kw()).and_then(kind_code).ok_or_else(|| bad("an entry has no known :kind"))?;
            let req = em.get("req").and_then(|v| v.ints()).ok_or_else(|| bad("an entry has no :req"))?;
            let ans = em.get("ans").and_then(|v| v.ints()).ok_or_else(|| bad("an entry has no :ans"))?;
            if req.len() < 3 || req[0] as usize != req.len() || req[1] != kind {
                return Err(bad("an entry's :req is not a record of its :kind"));
            }
            if ans.len() < 4 || ans[0] as usize != ans.len() || ans[1] != kind || ans[2] != req[2] {
                return Err(bad("an entry's :ans is not the answer to its :req"));
            }
            let bitmap = match em.get("bitmap").and_then(|v| v.str()) {
                Some(name) => {
                    let png = files.get(name).ok_or_else(|| PlanError::TranscriptBad(format!("{} is missing", name)))?;
                    Some(Arc::new(png_decode(png).map_err(PlanError::TranscriptBad)?))
                }
                None => None,
            };
            t.entries.push(Entry { round, req, ans, bitmap });
        }
        Ok(t)
    }

    // Replay must be given the inputs the recording saw: same count, same lengths.
    pub fn check_inputs(&self, inputs: &[(i32, i32)]) -> Result<(), PlanError> {
        if self.inputs.as_slice() != inputs {
            return Err(PlanError::TranscriptBad(format!("the transcript is of inputs {:?}, not {:?}", self.inputs, inputs)));
        }
        Ok(())
    }
}

// A fulfiller that answers from a transcript: a request it has no entry for is
// `transcript-miss`, never a guess. `bytes` is not here and cannot be.
pub struct Replayer {
    index: HashMap<Vec<i32>, usize>,
    entries: Vec<Entry>,
    used: Vec<bool>,
    done: VecDeque<Result<Answer, PlanError>>,
}

impl Replayer {
    pub fn new(t: &Transcript) -> Replayer {
        let mut index = HashMap::new();
        for (i, e) in t.entries.iter().enumerate() {
            index.insert(e.req.clone(), i);
        }
        Replayer { index, entries: t.entries.clone(), used: vec![false; t.entries.len()], done: VecDeque::new() }
    }
    // How many recorded entries the run never asked for.
    pub fn unused(&self) -> usize {
        self.used.iter().filter(|u| !**u).count()
    }
}

impl Fulfiller for Replayer {
    fn start(&mut self, req: &Request, _bitmaps: &Bitmaps) -> Result<(), PlanError> {
        let r = match self.index.get(&req.rec) {
            Some(&i) => {
                self.used[i] = true;
                let e = &self.entries[i];
                Ok(Answer { rec: e.ans.clone(), bitmap: e.bitmap.clone() })
            }
            None => Err(PlanError::TranscriptMiss(req.rec.clone())),
        };
        self.done.push_back(r);
        Ok(())
    }
    fn next(&mut self) -> Result<Answer, PlanError> {
        self.done.pop_front().expect("next without a started request")
    }
}

// ---------------------------------------------------------------- EDN (a subset)
// Maps, vectors, keywords, integers and strings: all a transcript holds.

#[derive(Debug, Clone)]
enum Edn {
    Int(i64),
    Str(String),
    Kw(String),
    Vec(Vec<Edn>),
    Map(Vec<(String, Edn)>),
}

struct EdnMap<'a>(&'a Vec<(String, Edn)>);

impl<'a> EdnMap<'a> {
    fn get(&self, k: &str) -> Option<&'a Edn> {
        self.0.iter().find(|(n, _)| n == k).map(|(_, v)| v)
    }
}

impl Edn {
    fn map(&self) -> Option<EdnMap> {
        if let Edn::Map(m) = self { Some(EdnMap(m)) } else { None }
    }
    fn vec(&self) -> Option<&Vec<Edn>> {
        if let Edn::Vec(v) = self { Some(v) } else { None }
    }
    fn int(&self) -> Option<i64> {
        if let Edn::Int(n) = self { Some(*n) } else { None }
    }
    fn kw(&self) -> Option<&str> {
        if let Edn::Kw(s) = self { Some(s) } else { None }
    }
    fn str(&self) -> Option<&str> {
        if let Edn::Str(s) = self { Some(s) } else { None }
    }
    fn ints(&self) -> Option<Vec<i32>> {
        self.vec()?.iter().map(|e| e.int().map(|n| n as i32)).collect()
    }

    fn parse(s: &str) -> Result<Edn, String> {
        let b: Vec<char> = s.chars().collect();
        let mut i = 0;
        let v = Edn::value(&b, &mut i)?;
        Edn::skip(&b, &mut i);
        if i != b.len() {
            return Err(format!("trailing text at {}", i));
        }
        Ok(v)
    }

    fn skip(b: &[char], i: &mut usize) {
        while *i < b.len() && (b[*i].is_whitespace() || b[*i] == ',') {
            *i += 1;
        }
    }

    fn value(b: &[char], i: &mut usize) -> Result<Edn, String> {
        Edn::skip(b, i);
        if *i >= b.len() {
            return Err("unexpected end".to_string());
        }
        match b[*i] {
            '{' => {
                *i += 1;
                let mut m = Vec::new();
                loop {
                    Edn::skip(b, i);
                    if *i >= b.len() {
                        return Err("unterminated map".to_string());
                    }
                    if b[*i] == '}' {
                        *i += 1;
                        return Ok(Edn::Map(m));
                    }
                    let k = match Edn::value(b, i)? {
                        Edn::Kw(k) => k,
                        _ => return Err("a map key is not a keyword".to_string()),
                    };
                    let v = Edn::value(b, i)?;
                    m.push((k, v));
                }
            }
            '[' => {
                *i += 1;
                let mut v = Vec::new();
                loop {
                    Edn::skip(b, i);
                    if *i >= b.len() {
                        return Err("unterminated vector".to_string());
                    }
                    if b[*i] == ']' {
                        *i += 1;
                        return Ok(Edn::Vec(v));
                    }
                    v.push(Edn::value(b, i)?);
                }
            }
            '"' => {
                *i += 1;
                let mut s = String::new();
                while *i < b.len() && b[*i] != '"' {
                    if b[*i] == '\\' && *i + 1 < b.len() {
                        *i += 1;
                    }
                    s.push(b[*i]);
                    *i += 1;
                }
                if *i >= b.len() {
                    return Err("unterminated string".to_string());
                }
                *i += 1;
                Ok(Edn::Str(s))
            }
            ':' => {
                *i += 1;
                let st = *i;
                while *i < b.len() && !b[*i].is_whitespace() && !"[]{},\"".contains(b[*i]) {
                    *i += 1;
                }
                Ok(Edn::Kw(b[st..*i].iter().collect()))
            }
            c if c == '-' || c.is_ascii_digit() => {
                let st = *i;
                *i += 1;
                while *i < b.len() && b[*i].is_ascii_digit() {
                    *i += 1;
                }
                let t: String = b[st..*i].iter().collect();
                t.parse::<i64>().map(Edn::Int).map_err(|_| format!("bad integer {}", t))
            }
            c => Err(format!("unexpected `{}` at {}", c, *i)),
        }
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
