// The alignment DRIVER, Rust (DEVLOG P7.1, D252-D254). Test-only.
//
//     aligndrv replay DIR PDF                   the gate: answer from DIR (no pdfium, no Tesseract)
//     aligndrv record DIR PDF                   live: `--cfg pdfium --cfg live`, links libpdfium and libtesseract
//
// One PDF goes through the plan/fulfil loop with `emon.alignprobe`'s unit: the document pass reads the text layer, then ONE
// `render` of every page at 300 dpi WITH its text (pdfium when recording), then ONE `ocr` of every render (Tesseract when
// recording), then the alignment. STDOUT is what the gate compares across hosts and against what the recording printed: the
// dump the unit wrote (one line a correspondence group), then the size and hash of each transcript file. STDERR is stats.
#![allow(dead_code, unused_mut, unused_variables)]
mod emon;
mod plan;
mod port;
mod testport;
#[cfg(pdfium)]
mod render;
#[cfg(pdfium)]
mod source;
#[cfg(pdfium)]
mod pdfium;
#[cfg(live)]
mod tess;

use plan::*;
use port::{cells_at, cells_len, Cells, Src};
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

fn fail(e: &PlanError) -> ! {
    println!("error: {}", e.name());
    eprintln!("{}", e);
    std::process::exit(3);
}

#[cfg(pdfium)]
struct FileSource {
    f: std::fs::File,
    len: u64,
}
#[cfg(pdfium)]
impl source::ByteSource for FileSource {
    fn len(&self) -> u64 {
        self.len
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> std::io::Result<usize> {
        use std::os::unix::fs::FileExt;
        self.f.read_at(buf, off)
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 4 {
        eprintln!("usage: aligndrv replay|record DIR PDF");
        std::process::exit(2);
    }
    let (mode, dir, pdf) = (args[1].clone(), args[2].clone(), args[3].clone());
    let pdf_bytes = std::fs::read(&pdf).unwrap();
    let inputs = vec![(0, pdf_bytes.len() as i32)];
    let base = Src::of_bytes(pdf_bytes.clone());
    let cfg = Config::default();
    assert_eq!(emon::plan::pk_ocr(), KIND_OCR);
    assert_eq!(emon::plan::pk_render(), KIND_RENDER);

    let mut transcript = Transcript { inputs: inputs.clone(), entries: Vec::new(), producer: Vec::new() };
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

    let mut dump = String::new();
    let call = |view: &Src, an: &mut Cells, rq: &mut Cells, reps: i32| -> (i32, String) {
        let mut c: Vec<Cells> = (0..51).map(|_| Cells::new()).collect();
        let mut d = String::new();
        let mut loc = Cells::new();
        let (ev, rest) = c.split_at_mut(24);
        let [epages, views, pts, refs, etext, feats, glyphs, words, lines, marks, fonts, paths, images, oruns, oblocks, olines, owords, osyms, oalts, inks, components, rules, prims, tlines] = ev else { unreachable!() };
        let [ar, dc, a, b, st, cx, ox, cs, out, f0, f1, f2, f3, pg, gx, fw, fl, fa, hl, hsc, cg, scr, gp, dp, ta, tb, tt] = rest else { unreachable!() };
        let st_ = emon::alignprobe::ap_unit(view, an, rq, &mut loc, &mut d, reps, epages, views, pts, refs, etext, feats, glyphs, words, lines, marks, fonts, paths, images, oruns, oblocks, olines, owords, osyms, oalts, inks, components, rules, prims, tlines, ar, dc, a, b, st, cx, ox, cs, out, f0, f1, f2, f3, pg, gx, fw, fl, fa, hl, hsc, cg, scr, gp, dp, ta, tb, tt);
        (st_, d)
    };
    let bench = std::env::var("EMON_ALIGN_BENCH").is_ok();
    let mut unit = |an: &mut Cells, rq: &mut Cells| -> i32 {
        let view = base.view();
        let (st_, d) = call(&view, an, rq, 1);
        if st_ == UNIT_DONE {
            dump = d;
            if bench {
                // the alignment alone: the difference of a run that repeats it 21 times and one that does it once, over 20
                let best = |reps: i32, an: &mut Cells, rq: &mut Cells| -> f64 {
                    let mut m = f64::MAX;
                    for _ in 0..3 {
                        let t0 = std::time::Instant::now();
                        call(&view, an, rq, reps);
                        m = m.min(t0.elapsed().as_secs_f64());
                    }
                    m
                };
                let (t1, t21) = (best(1, an, rq), best(21, an, rq));
                eprintln!("align-ms {:.3}", (t21 - t1) * 1000.0 / 20.0);
            }
        }
        st_
    };
    let mut bytes = SyncFulfiller::new(|req: &Request, _bm: &Bitmaps| -> Result<Answer, PlanError> {
        let mut c = Cells::new();
        emon::plan::an_bytes(&mut c, req.id(), req.arg(1), req.arg(2));
        Ok(Answer { rec: to_vec(&c), bitmap: None })
    });

    let recording = mode == "record";
    let result = if recording {
        #[cfg(all(pdfium, live))]
        {
            let tessdata = std::env::var("EMON_TESSDATA").unwrap_or("/opt/homebrew/share/tessdata".to_string());
            eprintln!("tesseract {}", tess::version());
            let mut t = tess::Tess::new(&tessdata, 300);
            transcript.producer = t.producer();
            let f = std::fs::File::open(&pdf).unwrap();
            let len = f.metadata().unwrap().len();
            let rf = render::RenderFulfiller::new(Arc::new(pdfium::Pdfium::new()), vec![render::RenderInput { source: Arc::new(FileSource { f, len }), password: None }]);
            let mut fu = SyncFulfiller::new(|req: &Request, bm: &Bitmaps| -> Result<Answer, PlanError> {
                match req.kind() {
                    KIND_RENDER => rf.fulfil(req),
                    KIND_OCR => t.fulfil(req, bm, true),
                    k => Err(PlanError::Fulfil(format!("no engine for kind {}", k))),
                }
            });
            let mut nm = NoMisses;
            let t0 = std::time::Instant::now();
            let r = run(&cfg, &mut unit, &mut fu, &mut bytes, &mut nm, Some(&mut transcript));
            eprintln!("record: {} ms", t0.elapsed().as_millis());
            r
        }
        #[cfg(not(all(pdfium, live)))]
        {
            eprintln!("record needs --cfg pdfium --cfg live");
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

    print!("{}", dump);
    let files = transcript.to_files();
    if !recording {
        for (name, bytes) in &files {
            println!("file {}: {} bytes {:08x}", name, bytes.len(), fnv(bytes));
        }
    }
    if recording {
        std::fs::create_dir_all(&dir).unwrap();
        for (name, bytes) in &files {
            std::fs::write(std::path::Path::new(&dir).join(name), bytes).unwrap();
        }
    }
}
