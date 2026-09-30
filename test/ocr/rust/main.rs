// The OCR spike DRIVER, Rust (DEVLOG P2.6, D095). Test-only.
//
//     ocrdriver replay DIR PDF                     (the gate: no Tesseract needed)
//     ocrdriver record DIR PDF PGM DPI [TESSDATA]  (live: `--cfg live`, links libtesseract)
//
// One scanned page goes through the plan/fulfil loop with `emon.ocrprobe`'s unit:
// `render` (answered from PGM, the scan's page image -- a stand-in Renderer, the
// PDF being one embedded JPEG at DPI) then `ocr` (answered by Tesseract when
// recording). STDOUT is what the gate compares across hosts: the evidence dump, a
// hash of each bitmap's pixels and of every transcript file. STDERR is stats.
#![allow(dead_code, unused_mut, unused_variables)]
mod emon;
mod plan;
mod port;
mod testport;
#[cfg(live)]
mod tess;

use plan::*;
use port::{cells_at, cells_len, Bitmap, Cells, Src};
use std::collections::BTreeMap;
use std::sync::Arc;

fn fnv(b: &[u8]) -> u32 {
    let mut h: u32 = 0x811C_9DC5;
    for &x in b {
        h ^= x as u32;
        h = h.wrapping_mul(16777619);
    }
    h
}

fn to_vec(c: &Cells) -> Vec<i32> {
    (0..cells_len(c)).map(|i| cells_at(c, i)).collect()
}

// Binary PGM (P5, 8-bit) -> a gray bitmap.
fn read_pgm(path: &str) -> Bitmap {
    let d = std::fs::read(path).unwrap();
    let mut pos = 0;
    let mut tok = || {
        while d[pos].is_ascii_whitespace() {
            pos += 1;
        }
        let s = pos;
        while !d[pos].is_ascii_whitespace() {
            pos += 1;
        }
        String::from_utf8_lossy(&d[s..pos]).to_string()
    };
    assert_eq!(tok(), "P5");
    let w: i32 = tok().parse().unwrap();
    let h: i32 = tok().parse().unwrap();
    assert_eq!(tok(), "255");
    pos += 1;
    Bitmap::from_bytes(w, h, 1, &d[pos..])
}

fn fail(e: &PlanError) -> ! {
    println!("error: {}", e.name());
    eprintln!("{}", e);
    std::process::exit(3);
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        eprintln!("usage: ocrdriver replay DIR PDF | record DIR PDF PGM DPI [TESSDATA]");
        std::process::exit(2);
    }
    let (mode, dir, pdf) = (args[1].clone(), args[2].clone(), args[3].clone());
    let pdf_bytes = std::fs::read(&pdf).unwrap();
    let inputs = vec![(0, pdf_bytes.len() as i32)];
    let base = Src::of_bytes(pdf_bytes.clone());
    let cfg = Config::default();
    assert_eq!(emon::plan::pk_ocr(), KIND_OCR);

    let mut transcript = Transcript { inputs: inputs.clone(), entries: Vec::new() };
    let mut replayer: Option<Replayer> = None;
    if mode == "replay" {
        let mut files: BTreeMap<String, Vec<u8>> = BTreeMap::new();
        for e in std::fs::read_dir(&dir).unwrap() {
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

    let mut evidence = String::new();
    let mut unit = |an: &mut Cells, rq: &mut Cells| -> i32 {
        let view = base.view();
        let (mut g, mut w, mut l, mut p, mut i, mut co, mut ow, mut al, mut et) = (
            Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(),
            Cells::new(), Cells::new(), Cells::new(), Cells::new(),
        );
        let st = emon::ocrprobe::op_unit(&view, an, rq, &mut g, &mut w, &mut l, &mut p, &mut i, &mut co, &mut ow, &mut al, &mut et);
        if st == UNIT_DONE {
            evidence.clear();
            emon::evidence::ev_emit(&mut evidence, 0, &mut g, &mut w, &mut l, &mut p, &mut i, &mut co, &mut ow, &mut al, &mut et);
        }
        st
    };
    let mut bytes = SyncFulfiller::new(|req: &Request, _bm: &Bitmaps| -> Result<Answer, PlanError> {
        let mut c = Cells::new();
        emon::plan::an_bytes(&mut c, req.id(), req.arg(1), req.arg(2));
        Ok(Answer { rec: to_vec(&c), bitmap: None })
    });

    let recording = mode == "record";
    let result = if recording {
        #[cfg(live)]
        {
            let (pgm, dpi) = (args[4].clone(), args[5].parse::<i32>().unwrap());
            let tessdata = args.get(6).cloned().unwrap_or("/opt/homebrew/share/tessdata".to_string());
            eprintln!("tesseract {}", tess::version());
            let mut t = tess::Tess::new(&tessdata, dpi);
            let page = read_pgm(&pgm);
            let mut f = SyncFulfiller::new(|req: &Request, bm: &Bitmaps| -> Result<Answer, PlanError> {
                match req.kind() {
                    KIND_RENDER => {
                        let mut c = Cells::new();
                        if req.arg(2) != dpi {
                            emon::plan::an_fail(&mut c, KIND_RENDER, req.id(), 2);
                            return Ok(Answer { rec: to_vec(&c), bitmap: None });
                        }
                        emon::plan::an_render(&mut c, req.id(), page.width(), page.height(), 1, dpi);
                        Ok(Answer { rec: to_vec(&c), bitmap: Some(Arc::new(page.clone())) })
                    }
                    KIND_OCR => {
                        let t0 = std::time::Instant::now();
                        let a = t.fulfil(req, bm);
                        eprintln!("ocr: {} ms", t0.elapsed().as_millis());
                        a
                    }
                    k => Err(PlanError::Fulfil(format!("no engine for kind {}", k))),
                }
            });
            let mut nm = NoMisses;
            let r = run(&cfg, &mut unit, &mut f, &mut bytes, &mut nm, Some(&mut transcript));
            for n in &t.notes {
                eprintln!("note: {}", n);
            }
            r
        }
        #[cfg(not(live))]
        {
            eprintln!("record needs --cfg live");
            std::process::exit(2);
        }
    } else {
        let mut nm = NoMisses;
        run(&cfg, &mut unit, replayer.as_mut().unwrap(), &mut bytes, &mut nm, None)
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
    eprintln!("stats: attempts={} rounds={} requests={}", stats.attempts, stats.rounds, stats.requests);

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
