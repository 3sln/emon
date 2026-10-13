// PARALLEL EXECUTION, Rust host (DEVLOG P10.2, D505-D508; SPEC 12.1; D022, D396 item 3).
//
// WHAT RUNS IN PARALLEL is the host's work, never kin's: a request's fulfilment (a render, a decode, the OCR of a page, a crop), on a bounded pool of
// `workers` threads. Kin stays single-threaded and runs in the order it always did; the plan machine is the one it always was; the Model, the transcript
// and the accounting are the serial run's, byte for byte, at any number of workers. Two things use the pool:
//
//   * A ROUND. The requests of one round are independent (the machine starts every one, then takes every completion); here they run at once and the
//     answers are taken in the order the requests were started, which is the order of their ids (`Machine::fulfil`, `pl-commit`).
//   * A LOOK AHEAD. A page unit runs after the one before it, and writes into arenas the pages share, so its kin cannot run beside another's. What it
//     ASKS, though, is known before it writes anything: a DRY attempt (`pu-dry`) is the part of an attempt that precedes its first write, and it asks
//     exactly what the real attempt will in those rounds (the decode of a scan, then the OCR of the page; the two renders of a born-digital page, then
//     its OCR). While the loop is on page p the host runs a dry chain for the `ahead` pages after it (kin's `pl-ahead`), has their requests answered by the
//     pool, and KEEPS THE ANSWERS BY THE REQUEST (its record). When the loop reaches page q its own units run as always, ask as always, and a request whose
//     record a dry chain already asked is answered by that job instead of a new one. A request nobody looked ahead for is answered at once, as in a serial run;
//     a dry chain that guessed wrong costs a wasted job and nothing else. So the result cannot differ from a serial run's: what is used is the answer to
//     the very request the unit asked, and the ports are functions of the request (the property `bb repeat` run-vs-run holds them to).
//
// A DRY CHAIN NEVER WRITES: `pu-dry` stops before the first write, so the arenas the real units share are as they were; only kin's scratch is touched, and
// the dry attempts run on the thread that runs the loop (a worker never touches kin or a document). A panic or an error in a dry chain ends that chain and
// is dropped: the real unit meets the same condition at its own turn, in its own place.
//
// WHAT THE POOL THREADS TOUCH is the port set (`Exec`: the renderer, the OCR engine, the codecs, the source) and bitmaps, which are shared read-only
// (`unsafe impl Sync for Bitmap`, port.rs). The limit overrides are thread-local in this host (D436): each job runs inside `with_limits` with the
// conversion's table, which is how a worker sees the bounds the calling thread was given.
//
// A job that PANICS (a port that panicked) is not lost and not reported from the wrong thread: the loop's thread runs the request again itself, in its
// own place, as a serial run does, and the panic is that run's.

use crate::plan::{self, Answer, Bitmaps, Config as PlanConfig, Fulfiller, Machine, NoMisses, Next, PlanError, Request};
use crate::port::{self, BitmapSet, Buf, Cells};
use crate::render::RenderFulfiller;
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;

// The stack of a pool thread. A port decodes and renders on it; the kin the engine runs stays on the loop's own thread.
const WORKER_STACK: usize = 16 << 20;

// What a worker runs: one request to its answer. The port set of one conversion, callable from any thread (the same routing as the serial run's `Ports`).
pub struct Exec {
    pub render: Option<RenderFulfiller>,
    pub local: Box<dyn Fn(&Request, &Bitmaps) -> Result<Answer, PlanError> + Send + Sync>,
    pub ocr: bool,
}

impl Exec {
    // A `render` or `crop` goes to the Renderer when there is one, every other kind to the host's own fulfiller (kin's `pl-route`).
    pub fn run(&self, req: &Request, bms: &Bitmaps) -> Result<Answer, PlanError> {
        match self.render.as_ref() {
            Some(r) if plan::route(req.kind(), self.ocr, true) == plan::Route::Render => r.fulfil(req),
            _ => (self.local)(req, bms),
        }
    }
}

enum Out {
    Done(Result<Answer, PlanError>),
    Panicked,
}

// One request on its way to an answer. Shared by the loop's thread (which wants it), a dry chain (which asked first) and a worker (which runs it).
pub struct Job {
    req: Request,
    bms: Bitmaps,
    taken: AtomicBool,
    out: Mutex<Option<Out>>,
}

impl Job {
    fn is_done(&self) -> bool {
        self.out.lock().unwrap_or_else(|e| e.into_inner()).is_some()
    }
}

struct St {
    // jobs the loop is waiting on, then those only a dry chain wants
    urgent: VecDeque<Arc<Job>>,
    later: VecDeque<Arc<Job>>,
    // jobs that finished since the loop's thread last looked (it is woken by them)
    events: usize,
    stop: bool,
}

struct Shared {
    st: Mutex<St>,
    cv: Condvar,
}

// A dry chain: one page ahead of the loop, asked round by round in a machine of its own (its accounting is not the run's).
struct Scout {
    machine: Machine,
    // every request this chain has asked, by its record
    jobs: HashMap<Vec<i32>, Arc<Job>>,
    // the round in flight, in id order
    waiting: Vec<Arc<Job>>,
    done: bool,
}

// What the loop's thread lends the pool for a dry attempt of a page unit: the document, which only it may touch. `page` is the page unit the loop is on,
// `None` outside the page units; `pages` how many there are.
pub trait Look {
    fn page(&self) -> Option<usize>;
    fn pages(&self) -> usize;
    fn dry(&mut self, page: usize, answers: &mut Cells, rq: &mut Cells, bms: &BitmapSet, payload: &mut Buf) -> i32;
}

pub struct Par {
    exec: Arc<Exec>,
    shared: Arc<Shared>,
    // the pool's threads are started as jobs arrive, one a job until there are `workers` of them: a document that asks nothing of any port (a born-digital
    // one under `ocr: auto`) starts none
    workers: usize,
    limits: Vec<(i32, i32)>,
    threads: Vec<JoinHandle<()>>,
    pcfg: PlanConfig,
    ahead: usize,
    scouts: BTreeMap<usize, Scout>,
}

fn worker(shared: Arc<Shared>, exec: Arc<Exec>, limits: Vec<(i32, i32)>) {
    loop {
        let job = {
            let mut g = shared.st.lock().unwrap_or_else(|e| e.into_inner());
            loop {
                if g.stop {
                    return;
                }
                if let Some(j) = g.urgent.pop_front().or_else(|| g.later.pop_front()) {
                    break j;
                }
                g = shared.cv.wait(g).unwrap_or_else(|e| e.into_inner());
            }
        };
        // a job the loop asked for after a dry chain queued it is in the queue twice: the first worker to reach it runs it
        if job.taken.swap(true, Ordering::SeqCst) {
            continue;
        }
        let r = catch_unwind(AssertUnwindSafe(|| port::with_limits(&limits, || exec.run(&job.req, &job.bms))));
        *job.out.lock().unwrap_or_else(|e| e.into_inner()) = Some(match r {
            Ok(x) => Out::Done(x),
            Err(_) => Out::Panicked,
        });
        shared.st.lock().unwrap_or_else(|e| e.into_inner()).events += 1;
        shared.cv.notify_all();
    }
}

impl Par {
    // `workers` pool threads (at least 2 is worth having: kin's `pl-workers` says), looking `ahead` pages ahead; `limits` is the conversion's table of
    // overrides, which every job runs under (they are thread-local here).
    pub fn new(exec: Exec, workers: usize, ahead: usize, limits: Vec<(i32, i32)>, pcfg: PlanConfig) -> Par {
        let exec = Arc::new(exec);
        let shared = Arc::new(Shared { st: Mutex::new(St { urgent: VecDeque::new(), later: VecDeque::new(), events: 0, stop: false }), cv: Condvar::new() });
        Par { exec, shared, workers, limits, threads: Vec::new(), pcfg, ahead, scouts: BTreeMap::new() }
    }

    // One more worker, while there are fewer than `workers`.
    fn grow(&mut self) {
        if self.threads.len() < self.workers {
            let (s, e, l) = (self.shared.clone(), self.exec.clone(), self.limits.clone());
            let t = std::thread::Builder::new()
                .name(format!("emon-par-{}", self.threads.len()))
                .stack_size(WORKER_STACK)
                .spawn(move || worker(s, e, l))
                .unwrap_or_else(|e| panic!("emon could not start a worker thread: {}", e));
            self.threads.push(t);
        }
    }

    fn submit(&mut self, req: Request, bms: Bitmaps, urgent: bool) -> Arc<Job> {
        self.grow();
        let job = Arc::new(Job { req, bms, taken: AtomicBool::new(false), out: Mutex::new(None) });
        {
            let mut g = self.shared.st.lock().unwrap_or_else(|e| e.into_inner());
            if urgent {
                g.urgent.push_back(job.clone());
            } else {
                g.later.push_back(job.clone());
            }
        }
        self.shared.cv.notify_all();
        job
    }

    // The job that answers `req`: the one a dry chain of page `page` already asked (moved up the queue if it has not started), or a new one.
    fn want(&mut self, page: Option<usize>, req: &Request, bms: &Bitmaps) -> Arc<Job> {
        if let Some(p) = page {
            if let Some(job) = self.scouts.get(&p).and_then(|s| s.jobs.get(&req.rec)).cloned() {
                if !job.is_done() && !job.taken.load(Ordering::SeqCst) {
                    self.shared.st.lock().unwrap_or_else(|e| e.into_inner()).urgent.push_back(job.clone());
                    self.shared.cv.notify_all();
                }
                return job;
            }
        }
        self.submit(req.clone(), bms.clone(), true)
    }

    // Move every dry chain on as far as the answers in hand allow, and start those for the pages ahead of the loop.
    fn pump(&mut self, look: &mut dyn Look) {
        let cur = match look.page() {
            Some(p) => p,
            None => {
                self.scouts.clear();
                return;
            }
        };
        self.scouts.retain(|p, _| *p >= cur);
        let last = (cur + self.ahead).min(look.pages().saturating_sub(1));
        for p in cur + 1..=last {
            if !self.scouts.contains_key(&p) {
                self.scouts.insert(p, Scout { machine: Machine::new(&self.pcfg), jobs: HashMap::new(), waiting: Vec::new(), done: false });
            }
        }
        let pages: Vec<usize> = self.scouts.keys().copied().collect();
        for p in pages {
            self.advance(p, look);
        }
    }

    // One dry chain, as far as it goes without waiting.
    fn advance(&mut self, page: usize, look: &mut dyn Look) {
        loop {
            let mut s = match self.scouts.remove(&page) {
                Some(s) => s,
                None => return,
            };
            if s.done {
                self.scouts.insert(page, s);
                return;
            }
            if !s.waiting.is_empty() {
                if !s.waiting.iter().all(|j| j.is_done()) {
                    self.scouts.insert(page, s);
                    return;
                }
                let mut ok = true;
                for j in &s.waiting {
                    match j.out.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
                        Some(Out::Done(Ok(a))) => {
                            if s.machine.offer(a.clone()).is_err() {
                                ok = false;
                            }
                        }
                        _ => ok = false,
                    }
                }
                if !ok || s.machine.commit().is_err() {
                    s.done = true;
                    self.scouts.insert(page, s);
                    return;
                }
                s.waiting.clear();
            }
            let mut unit = |ans: &mut Cells, rq: &mut Cells, bms: &BitmapSet, pl: &mut Buf| -> i32 { look.dry(page, ans, rq, bms, pl) };
            match s.machine.attempt(&mut unit, &mut NoMisses) {
                Ok(Next::Requests(reqs, all)) => {
                    for r in reqs {
                        let job = match s.jobs.get(&r.rec) {
                            Some(j) => j.clone(),
                            None => {
                                let j = self.submit(r.clone(), all.clone(), false);
                                s.jobs.insert(r.rec.clone(), j.clone());
                                j
                            }
                        };
                        s.waiting.push(job);
                    }
                    self.scouts.insert(page, s);
                }
                _ => {
                    s.done = true;
                    self.scouts.insert(page, s);
                    return;
                }
            }
        }
    }

    // The answer of `job`, once it has run: the loop's thread keeps the dry chains moving while it waits.
    fn wait(&mut self, job: &Arc<Job>, look: &mut dyn Look) -> Result<Answer, PlanError> {
        loop {
            self.pump(look);
            if let Some(out) = job.out.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
                return match out {
                    Out::Done(r) => r.clone(),
                    // a panic is the loop's own, in its own place: run the request here
                    Out::Panicked => self.exec.run(&job.req, &job.bms),
                };
            }
            let mut g = self.shared.st.lock().unwrap_or_else(|e| e.into_inner());
            if g.events == 0 && !job.is_done() {
                g = self.shared.cv.wait(g).unwrap_or_else(|e| e.into_inner());
            }
            g.events = 0;
        }
    }
}

impl Drop for Par {
    fn drop(&mut self) {
        {
            let mut g = self.shared.st.lock().unwrap_or_else(|e| e.into_inner());
            g.stop = true;
        }
        self.shared.cv.notify_all();
        for t in self.threads.drain(..) {
            let _ = t.join();
        }
    }
}

// A round's fulfiller: `Machine::fulfil` starts every request of the round and then takes every completion in order.
pub struct RoundFulfiller<'a> {
    par: &'a mut Par,
    look: &'a mut dyn Look,
    page: Option<usize>,
    started: VecDeque<Arc<Job>>,
}

impl<'a> RoundFulfiller<'a> {
    pub fn new(par: &'a mut Par, look: &'a mut dyn Look) -> RoundFulfiller<'a> {
        let page = look.page();
        RoundFulfiller { par, look, page, started: VecDeque::new() }
    }
}

impl<'a> Fulfiller for RoundFulfiller<'a> {
    fn start(&mut self, req: &Request, bitmaps: &Bitmaps) -> Result<(), PlanError> {
        let job = self.par.want(self.page, req, bitmaps);
        self.started.push_back(job);
        Ok(())
    }
    fn next(&mut self) -> Result<Answer, PlanError> {
        match self.started.pop_front() {
            Some(job) => self.par.wait(&job, self.look),
            None => Err(PlanError::Fulfil("next without a started request".to_string())),
        }
    }
}
