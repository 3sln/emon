// The plan/fulfil PROBE DRIVER, Rust (DEVLOG P2.5, D089). Test-only: it reads
// and writes the transcript DIRECTORY, which the library (`plan.rs`) never does.
//
//     plandriver record DIR [--async] [--chunk=N] [--max-rounds=N]
//     plandriver replay DIR [--async] [--chunk=N] [--max-rounds=N]
//
// It runs `emon.planprobe`'s unit through `plan::run` over two inputs (2000
// bytes and 64 bytes), fulfilling render/decode/ocr with a FAKE deterministic
// fulfiller (recording) or from the transcript (replaying). `--async` makes
// completions arrive in reverse order; `--chunk=N` serves input 0 through an
// async 512-style test source so the unit misses and the loop fetches `bytes`.
//
// STDOUT is what the gate compares across hosts: the evidence dump, a hash per
// bitmap's pixels, and a hash of every transcript file. STDERR is stats.
#![allow(dead_code, unused_mut, unused_variables)]
mod emon;
mod plan;
mod port;
mod testport;

use plan::*;
use port::{cells_at, Bitmap, cells_len, Cells, Src};
use std::cell::Cell;
use std::collections::BTreeMap;
use std::rc::Rc;
use std::sync::Arc;

fn fnv(b: &[u8]) -> u32 {
    let mut h: u32 = 0x811C_9DC5;
    for &x in b {
        h ^= x as u32;
        h = h.wrapping_mul(16777619);
    }
    h
}

fn input0() -> Vec<u8> {
    (0..2000).map(|i| ((i * 7 + 1) & 255) as u8).collect()
}

fn input1() -> Vec<u8> {
    (0..64).map(|i| ((i * 3 + 2) & 255) as u8).collect()
}

fn to_vec(c: &Cells) -> Vec<i32> {
    (0..cells_len(c)).map(|i| cells_at(c, i)).collect()
}

fn ok_answer(mut c: Cells, bm: Option<Bitmap>) -> Result<Answer, PlanError> {
    Ok(Answer { rec: to_vec(&c), bitmap: bm.map(Arc::new) })
}

// THE FAKE: what a real Renderer / ImageDecode / Ocr would be, deterministic and
// the same in every host (the Go driver has its twin).
fn fake(req: &Request, bm: &Bitmaps) -> Result<Answer, PlanError> {
    let id = req.id();
    let mut c = Cells::new();
    match req.kind() {
        KIND_RENDER => {
            let (w, h) = (64, 48);
            let mut px = Vec::new();
            for y in 0..h {
                for x in 0..w {
                    px.push(((x * 3 + y * 5 + id * 7) & 255) as u8);
                }
            }
            emon::plan::an_render(&mut c, id, w, h, 1, req.arg(2));
            ok_answer(c, Some(Bitmap::from_bytes(w, h, 1, &px)))
        }
        KIND_DECODE => {
            let (w, h) = (32, 16);
            let mut px = Vec::new();
            for y in 0..h {
                for x in 0..w {
                    for ch in 0..3 {
                        px.push(((x * 5 + y * 3 + ch * 50 + id * 11) & 255) as u8);
                    }
                }
            }
            emon::plan::an_decode(&mut c, id, w, h, 3, 7200, 0, 1);
            ok_answer(c, Some(Bitmap::from_bytes(w, h, 3, &px)))
        }
        KIND_OCR => {
            let b = match bm.get(&req.arg(0)) {
                Some(b) => b.clone(),
                None => {
                    emon::plan::an_fail(&mut c, KIND_OCR, id, STATUS_FAILED);
                    return ok_answer(c, None);
                }
            };
            let at = emon::plan::an_ocr(&mut c, id);
            let words: [(i32, i32, i32, i32, i32, i32, &[i32]); 3] = [
                (4, 2, b.width() / 2, 10, 900 + (b.to_bytes()[0] as i32 % 50), 0, &[72, 101, 108, 108, 111]),
                (b.width() / 2 + 6, 2, 20, 10, 871, 0, &[119, 246, 114, 108, 100]),
                (4, 16, 6, 10, 640, 1, &[8364, 128512]),
            ];
            for (x, y, w, h, conf, line, text) in words.iter() {
                let wd = emon::plan::an_ocr_word(&mut c, at, *x, *y, *w, *h, *conf, *line, 0);
                for cp in text.iter() {
                    emon::plan::an_ocr_cp(&mut c, wd, *cp);
                }
            }
            emon::plan::an_ocr_end(&mut c, at);
            ok_answer(c, None)
        }
        k => Err(PlanError::Fulfil(format!("the fake has no kind {}", k))),
    }
}

struct Chunked {
    base: Rc<Src>,
    chunk: i32,
    round: Rc<Cell<i32>>,
}

impl Misses for Chunked {
    fn take(&mut self, round: i32) -> Vec<Range> {
        self.round.set(round);
        let n = testport::src_ctl(&self.base, 4, 0);
        if n == 0 {
            return vec![];
        }
        // Informative, not exact: the test source records chunks, not ranges.
        vec![Range { input: 0, offset: 0, length: n * self.chunk }]
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 3 {
        eprintln!("usage: plandriver record|replay DIR [--async] [--chunk=N] [--max-rounds=N]");
        std::process::exit(2);
    }
    let (mode, dir) = (args[1].clone(), args[2].clone());
    let mut use_async = false;
    let mut chunk = 0;
    let mut cfg = Config::default();
    for a in &args[3..] {
        if a == "--async" {
            use_async = true;
        } else if let Some(n) = a.strip_prefix("--chunk=") {
            chunk = n.parse().unwrap();
        } else if let Some(n) = a.strip_prefix("--max-rounds=") {
            cfg.max_rounds = n.parse().unwrap();
        } else {
            eprintln!("unknown flag {}", a);
            std::process::exit(2);
        }
    }
    // The kin numbers the host copies, held equal (a host that drifts fails here).
    assert_eq!(emon::plan::pk_render(), KIND_RENDER);
    assert_eq!(emon::plan::pk_decode(), KIND_DECODE);
    assert_eq!(emon::plan::pk_ocr(), KIND_OCR);
    assert_eq!(emon::plan::pk_bytes(), KIND_BYTES);
    assert_eq!(emon::plan::us_done(), UNIT_DONE);
    assert_eq!(emon::plan::us_requests(), UNIT_REQUESTS);
    assert_eq!(emon::plan::us_need_bytes(), UNIT_NEED_BYTES);
    assert_eq!(emon::plan::ps_ok(), STATUS_OK);

    let in0 = input0();
    let inputs = vec![(0, in0.len() as i32), (1, input1().len() as i32)];
    let base: Rc<Src> = Rc::new(if chunk > 0 { testport::src_chunked(&in0, chunk, 0) } else { Src::of_bytes(in0.clone()) });

    let mut transcript = Transcript { inputs: inputs.clone(), entries: Vec::new() };
    let mut replayer: Option<Replayer> = None;
    if mode == "replay" {
        let mut files: BTreeMap<String, Vec<u8>> = BTreeMap::new();
        for e in std::fs::read_dir(&dir).unwrap_or_else(|e| { eprintln!("{}: {}", dir, e); std::process::exit(2) }) {
            let e = e.unwrap();
            files.insert(e.file_name().to_string_lossy().to_string(), std::fs::read(e.path()).unwrap());
        }
        let t = match Transcript::from_files(&files).and_then(|t| t.check_inputs(&inputs).map(|_| t)) {
            Ok(t) => t,
            Err(e) => fail(&e),
        };
        replayer = Some(Replayer::new(&t));
        transcript = t;
    }

    // The unit: one attempt = one fresh view of input 0 and fresh arenas.
    let mut evidence = String::new();
    let mut unit = |an: &mut Cells, rq: &mut Cells| -> i32 {
        let view = base.view();
        let (mut g, mut w, mut l, mut p, mut i, mut co, mut ow, mut al, mut et, mut sp) = (
            Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(),
            Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(),
        );
        let st = emon::planprobe::pp_unit(&view, an, rq, &mut g, &mut w, &mut l, &mut p, &mut i, &mut co, &mut ow, &mut al, &mut et, &mut sp);
        if st == UNIT_DONE {
            evidence.clear();
            emon::evidence::ev_emit(&mut evidence, 0, &mut g, &mut w, &mut l, &mut p, &mut i, &mut co, &mut ow, &mut al, &mut et, &mut sp);
        }
        st
    };

    let round = Rc::new(Cell::new(0));
    let mut misses: Box<dyn Misses> = if chunk > 0 {
        Box::new(Chunked { base: base.clone(), chunk, round: round.clone() })
    } else {
        Box::new(NoMisses)
    };
    let fetch_base = base.clone();
    let fetch_round = round.clone();
    let bytes_fn = move |req: &Request, _bm: &Bitmaps| -> Result<Answer, PlanError> {
        if chunk > 0 {
            testport::src_ctl(&fetch_base, 0, fetch_round.get());
        }
        let mut c = Cells::new();
        emon::plan::an_bytes(&mut c, req.id(), req.arg(1), req.arg(2));
        ok_answer(c, None)
    };
    let mut bytes = SyncFulfiller::new(bytes_fn);

    let recording = mode == "record";
    let result = {
        let rec = if recording { Some(&mut transcript) } else { None };
        match (recording, use_async) {
            (true, false) => {
                let mut f = SyncFulfiller::new(fake);
                run(&cfg, &mut unit, &mut f, &mut bytes, &mut *misses, rec)
            }
            (true, true) => {
                let mut f = DeferredFulfiller::new(fake);
                run(&cfg, &mut unit, &mut f, &mut bytes, &mut *misses, rec)
            }
            (false, _) => {
                let r = replayer.as_mut().unwrap();
                run(&cfg, &mut unit, r, &mut bytes, &mut *misses, None)
            }
        }
    };
    let stats = match result {
        Ok(s) => s,
        Err(e) => fail(&e),
    };
    if let Some(r) = &replayer {
        if r.unused() != 0 {
            fail(&PlanError::TranscriptBad(format!("{} recorded entries were never asked for", r.unused())));
        }
    }
    eprintln!(
        "stats: attempts={} rounds={} requests={} fetch-rounds={} bytes-requests={}",
        stats.attempts, stats.rounds, stats.requests, stats.fetch_rounds, stats.bytes_requests
    );

    println!("evidence: {}", evidence);
    for e in &transcript.entries {
        if let Some(b) = &e.bitmap {
            println!("bitmap {}: {}x{}x{} {:08x}", e.req[2], b.width(), b.height(), b.channels(), fnv(&b.to_bytes()));
        }
    }
    let files = transcript.to_files();
    for (name, bytes) in &files {
        println!("file {}: {} bytes {:08x}", name, bytes.len(), fnv(bytes));
    }
    if recording {
        std::fs::create_dir_all(&dir).unwrap();
        for (name, bytes) in &files {
            std::fs::write(std::path::Path::new(&dir).join(name), bytes).unwrap();
        }
    }
}

fn fail(e: &PlanError) -> ! {
    println!("error: {}", e.name());
    eprintln!("{}", e);
    std::process::exit(3);
}
