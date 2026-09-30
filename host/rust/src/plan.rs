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

use crate::port::{cells_at, cells_len, cells_push, Bitmap, Cells};
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::sync::Arc;

// ------------------------------------------------------------ the constants
// The numbers are `emon.plan`'s (a kin law holds them; the driver asserts them).

pub const KIND_RENDER: i32 = 1;
pub const KIND_DECODE: i32 = 2;
pub const KIND_OCR: i32 = 3;
pub const KIND_BYTES: i32 = 4;

pub const STATUS_OK: i32 = 0;
pub const STATUS_FAILED: i32 = 1;

pub const UNIT_DONE: i32 = 0;
pub const UNIT_REQUESTS: i32 = 1;
pub const UNIT_NEED_BYTES: i32 = 7;

// SPEC 13.1's defaults. Both are NAMED limits: a breach is `PlanError::Limit`.
pub const MAX_ROUNDS: i32 = 3;
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
        if rec[1] < KIND_RENDER || rec[1] > KIND_OCR {
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
        let status = unit(&mut answers, &mut rq);
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
        let got = fulfil(ports, &reqs, &bitmaps)?;
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
}

pub const TRANSCRIPT_FILE: &str = "transcript.edn";

fn kind_name(k: i32) -> &'static str {
    match k {
        KIND_RENDER => "render",
        KIND_DECODE => "decode",
        KIND_OCR => "ocr",
        _ => "unknown",
    }
}

fn kind_code(s: &str) -> Option<i32> {
    match s {
        "render" => Some(KIND_RENDER),
        "decode" => Some(KIND_DECODE),
        "ocr" => Some(KIND_OCR),
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
        s.push_str("{:emon-transcript 1\n :inputs [");
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
// Lossless, 8 bits, gray (1) or RGB (3), no interlace, filter 0, and the zlib
// stream in STORED blocks: no compressor is needed, the bytes are a function of
// the pixels alone (so two hosts write the same file), and any PNG reader opens
// it. The decoder reads what this writes and any stored-block PNG with the five
// filters; a compressed PNG is `png-compressed` for now (a compressed writer is
// a follow-up: a real page is 8.7 MB per gray 300-dpi A4 this way).

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

fn chunk(out: &mut Vec<u8>, kind: &[u8; 4], body: &[u8]) {
    out.extend_from_slice(&(body.len() as u32).to_be_bytes());
    let mut c = kind.to_vec();
    c.extend_from_slice(body);
    out.extend_from_slice(&c);
    out.extend_from_slice(&crc32(&c).to_be_bytes());
}

pub fn png_encode(b: &Bitmap) -> Vec<u8> {
    let (w, h, ch) = (b.width() as usize, b.height() as usize, b.channels() as usize);
    let pixels = b.to_bytes();
    let mut raw = Vec::with_capacity(h * (1 + w * ch));
    for y in 0..h {
        raw.push(0);
        raw.extend_from_slice(&pixels[y * w * ch..(y + 1) * w * ch]);
    }
    let mut z = vec![0x78, 0x01];
    if raw.is_empty() {
        z.extend_from_slice(&[1, 0, 0, 0xFF, 0xFF]);
    }
    let mut i = 0;
    while i < raw.len() {
        let n = std::cmp::min(65535, raw.len() - i);
        z.push(if i + n == raw.len() { 1 } else { 0 });
        z.extend_from_slice(&(n as u16).to_le_bytes());
        z.extend_from_slice(&(!(n as u16)).to_le_bytes());
        z.extend_from_slice(&raw[i..i + n]);
        i += n;
    }
    z.extend_from_slice(&adler32(&raw).to_be_bytes());
    let mut out = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A];
    let mut ihdr = Vec::new();
    ihdr.extend_from_slice(&(b.width() as u32).to_be_bytes());
    ihdr.extend_from_slice(&(b.height() as u32).to_be_bytes());
    ihdr.extend_from_slice(&[8, if ch == 1 { 0 } else { 2 }, 0, 0, 0]);
    chunk(&mut out, b"IHDR", &ihdr);
    chunk(&mut out, b"IDAT", &z);
    chunk(&mut out, b"IEND", &[]);
    out
}

pub fn png_decode(p: &[u8]) -> Result<Bitmap, String> {
    if p.len() < 8 || p[..8] != [0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A] {
        return Err("not a PNG".to_string());
    }
    let (mut w, mut h, mut ch) = (0usize, 0usize, 0usize);
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
                w = u32::from_be_bytes([body[0], body[1], body[2], body[3]]) as usize;
                h = u32::from_be_bytes([body[4], body[5], body[6], body[7]]) as usize;
                if body[8] != 8 || body[10] != 0 || body[11] != 0 || body[12] != 0 {
                    return Err("png-unsupported: only 8-bit, unfiltered-method, non-interlaced".to_string());
                }
                ch = match body[9] {
                    0 => 1,
                    2 => 3,
                    _ => return Err("png-unsupported: only gray and RGB".to_string()),
                };
            }
            b"IDAT" => idat.extend_from_slice(body),
            b"IEND" => break,
            _ => {}
        }
        i += 12 + n;
    }
    if ch == 0 || idat.len() < 6 {
        return Err("a PNG without header or data".to_string());
    }
    // zlib, stored blocks only.
    let mut raw: Vec<u8> = Vec::new();
    let mut j = 2;
    loop {
        if j + 5 > idat.len() {
            return Err("a truncated zlib stream".to_string());
        }
        let hdr = idat[j];
        if hdr & 6 != 0 {
            return Err("png-compressed: only stored deflate blocks are read".to_string());
        }
        let n = u16::from_le_bytes([idat[j + 1], idat[j + 2]]) as usize;
        if j + 5 + n > idat.len() {
            return Err("a truncated stored block".to_string());
        }
        raw.extend_from_slice(&idat[j + 5..j + 5 + n]);
        j += 5 + n;
        if hdr & 1 == 1 {
            break;
        }
    }
    if j + 4 > idat.len() || u32::from_be_bytes([idat[j], idat[j + 1], idat[j + 2], idat[j + 3]]) != adler32(&raw) {
        return Err("a zlib stream fails its checksum".to_string());
    }
    let stride = w * ch;
    if raw.len() != h * (1 + stride) {
        return Err("a PNG whose data does not fit its size".to_string());
    }
    let mut px = vec![0u8; h * stride];
    for y in 0..h {
        let f = raw[y * (1 + stride)];
        for x in 0..stride {
            let v = raw[y * (1 + stride) + 1 + x] as i32;
            let a = if x >= ch { px[y * stride + x - ch] as i32 } else { 0 };
            let b = if y > 0 { px[(y - 1) * stride + x] as i32 } else { 0 };
            let c = if x >= ch && y > 0 { px[(y - 1) * stride + x - ch] as i32 } else { 0 };
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
            px[y * stride + x] = (v + pred) as u8;
        }
    }
    Ok(Bitmap::from_bytes(w as i32, h as i32, ch as i32, &px))
}
