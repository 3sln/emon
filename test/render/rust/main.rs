// The Renderer DRIVER, Rust (DEVLOG P6.2, D127). Test-only.
//
//     renderdrv replay DIR --pdf P... --req SPEC...     the gate: answer from DIR (no pdfium needed)
//     renderdrv record DIR --pdf P... --req SPEC...     live: `--cfg pdfium`, links libpdfium; writes DIR
//     renderdrv diff A B                                 compare two transcripts' bitmaps, pixel by pixel
//
// SPEC is `input:page:dpi:layer` or, for a crop, `input:page:dpi:layer:px,py,pw,ph`. The unit asks for every
// SPEC in ONE round (ids 1, 2, ...) and is done when all are answered, so the transcript is a function of the
// specs and the PDFs, and nothing else. STDOUT is what the gate compares across hosts and against the dump
// the recording printed: every entry's request and answer, each bitmap's pixel hash, each transcript file's
// size and hash. STDERR is stats.
#![allow(dead_code, unused_mut, unused_variables)]
mod emon;
mod plan;
mod port;
mod render;
mod source;
mod testport;
#[cfg(pdfium)]
mod pdfium;

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

fn fail(e: &PlanError) -> ! {
    println!("error: {}", e.name());
    eprintln!("{}", e);
    std::process::exit(3);
}

#[derive(Clone)]
struct Spec {
    id: i32,
    input: i32,
    page: i32,
    dpi: i32,
    layer: i32,
    clip: Option<[i32; 4]>,
}

fn parse_spec(id: i32, s: &str) -> Spec {
    let p: Vec<&str> = s.split(':').collect();
    let n = |i: usize| p[i].parse::<i32>().unwrap();
    let clip = p.get(4).map(|c| {
        let v: Vec<i32> = c.split(',').map(|x| x.parse().unwrap()).collect();
        [v[0], v[1], v[2], v[3]]
    });
    Spec { id, input: n(0), page: n(1), dpi: n(2), layer: n(3), clip }
}

// A file read by `pread`: random access, no whole-file buffer, with a count of what was asked of it.
#[cfg(unix)]
struct FileSource {
    f: std::fs::File,
    len: u64,
    reads: std::sync::atomic::AtomicU64,
    bytes: std::sync::atomic::AtomicU64,
}
#[cfg(unix)]
impl source::ByteSource for FileSource {
    fn len(&self) -> u64 {
        self.len
    }
    fn read_at(&self, off: u64, buf: &mut [u8]) -> std::io::Result<usize> {
        use std::os::unix::fs::FileExt;
        use std::sync::atomic::Ordering::Relaxed;
        let n = self.f.read_at(buf, off)?;
        self.reads.fetch_add(1, Relaxed);
        self.bytes.fetch_add(n as u64, Relaxed);
        Ok(n)
    }
}

fn read_dir(dir: &str) -> BTreeMap<String, Vec<u8>> {
    let mut files = BTreeMap::new();
    for e in std::fs::read_dir(dir).unwrap() {
        let e = e.unwrap();
        files.insert(e.file_name().to_string_lossy().to_string(), std::fs::read(e.path()).unwrap());
    }
    files
}

fn diff(a: &str, b: &str) {
    let ta = Transcript::from_files(&read_dir(a)).unwrap();
    let tb = Transcript::from_files(&read_dir(b)).unwrap();
    let (mut worst, mut total, mut differing) = (0i32, 0u64, 0u64);
    for ea in &ta.entries {
        let eb = match tb.entries.iter().find(|e| e.req == ea.req) {
            Some(e) => e,
            None => {
                println!("diff {}: no entry in {}", ea.req[2], b);
                continue;
            }
        };
        match (&ea.bitmap, &eb.bitmap) {
            (Some(x), Some(y)) if x.width() == y.width() && x.height() == y.height() && x.channels() == y.channels() => {
                let (bx, by) = (x.to_bytes(), y.to_bytes());
                let mut mx = 0i32;
                let mut nd = 0u64;
                let ch = x.channels() as usize;
                let mut hist = [0u64; 5];
                for (px, py) in bx.chunks(ch).zip(by.chunks(ch)) {
                    let d = px.iter().zip(py).map(|(p, q)| (*p as i32 - *q as i32).abs()).max().unwrap();
                    if d > 0 {
                        nd += 1;
                        mx = mx.max(d);
                    }
                }
                let n = (x.width() as u64) * (x.height() as u64);
                println!("diff {} {}x{}: max channel diff {} differing pixels {} of {}", ea.req[2], x.width(), x.height(), mx, nd, n);
                worst = worst.max(mx);
                total += n;
                differing += nd;
            }
            (Some(x), Some(y)) => {
                println!("diff {}: SIZE {}x{} vs {}x{}", ea.req[2], x.width(), x.height(), y.width(), y.height());
                worst = 255;
            }
            (None, None) => println!("diff {}: no bitmap in either (answers {:?} and {:?})", ea.req[2], ea.ans, eb.ans),
            _ => {
                println!("diff {}: a bitmap in only one", ea.req[2]);
                worst = 255;
            }
        }
    }
    println!("total: max channel diff {} differing pixels {} of {}", worst, differing, total);
}

// `crops DIR`: every crop in the transcript is the same pixels as the cut of the whole-page render it names
// (same input, page, dpi, layer), where the transcript holds that render (SPEC 7.2, D117).
fn crops(dir: &str) {
    let t = Transcript::from_files(&read_dir(dir)).unwrap();
    let (mut checked, mut bad) = (0, 0);
    for c in t.entries.iter().filter(|e| e.req[1] == KIND_CROP) {
        let (cb, a) = match (&c.bitmap, c.req.len() == 11) {
            (Some(b), true) => (b, &c.req),
            _ => continue,
        };
        let whole = t.entries.iter().find(|e| e.req[1] == KIND_RENDER && e.req[3..7] == a[3..7] && e.bitmap.is_some());
        let wb = match whole {
            Some(e) => e.bitmap.as_ref().unwrap(),
            None => {
                println!("crop {}: no whole-page render in the transcript to compare with", a[2]);
                continue;
            }
        };
        let (px, py, pw, ph) = (a[7] as usize, a[8] as usize, a[9] as usize, a[10] as usize);
        let (wbytes, cbytes) = (wb.to_bytes(), cb.to_bytes());
        let ch = wb.channels() as usize;
        let mut diff = 0;
        let mut maxd = 0i32;
        let (mut x0, mut y0, mut x1, mut y1) = (usize::MAX, usize::MAX, 0, 0);
        for y in 0..ph {
            for x in 0..pw {
                let wi = ((py + y) * wb.width() as usize + px + x) * ch;
                let ci = (y * pw + x) * ch;
                if wbytes[wi..wi + ch] != cbytes[ci..ci + ch] {
                    diff += 1;
                    for k in 0..ch {
                        maxd = maxd.max((wbytes[wi + k] as i32 - cbytes[ci + k] as i32).abs());
                    }
                    x0 = x0.min(x);
                    y0 = y0.min(y);
                    x1 = x1.max(x);
                    y1 = y1.max(y);
                }
            }
        }
        if diff > 0 {
            println!("crop {}: differing pixels lie in x {}..{} y {}..{} of the crop, max channel diff {}", a[2], x0, x1, y0, y1, maxd);
        }
        checked += 1;
        if diff > 0 {
            bad += 1;
        }
        println!("crop {} {}x{}+{}+{} equals the cut of its whole-page render: {} ({} differing pixels)", a[2], pw, ph, px, py, diff == 0, diff);
    }
    println!("crops: {} checked, {} differ", checked, bad);
    if bad > 0 {
        std::process::exit(1);
    }
}

// `facts DIR`: what is on each bitmap of input 0 (the layers fixture): its non-white pixels, and the pixels of
// the three colours the fixture was built from (background blue, watermark red, form green), so a layer can be
// held to what it should show.
fn facts(dir: &str) {
    let t = Transcript::from_files(&read_dir(dir)).unwrap();
    for e in t.entries.iter().filter(|e| e.req[3] == 0 && e.req[1] == KIND_RENDER) {
        if let Some(b) = &e.bitmap {
            let (mut nonwhite, mut blue, mut red, mut green) = (0, 0, 0, 0);
            for p in b.to_bytes().chunks(3) {
                if p != [255, 255, 255] {
                    nonwhite += 1;
                }
                if p == [217, 230, 255] {
                    blue += 1;
                }
                if p == [255, 0, 0] {
                    red += 1;
                }
                if p == [0, 153, 0] {
                    green += 1;
                }
            }
            println!("facts layer {}: nonwhite {} blue {} red {} green {}", e.req[6], nonwhite, blue, red, green);
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() == 3 && args[1] == "facts" {
        facts(&args[2]);
        return;
    }
    if args.len() == 3 && args[1] == "crops" {
        crops(&args[2]);
        return;
    }
    if args.len() == 4 && args[1] == "diff" {
        diff(&args[2], &args[3]);
        return;
    }
    if args.len() < 3 {
        eprintln!("usage: renderdrv replay|record DIR --pdf P... --req SPEC... | diff A B");
        std::process::exit(2);
    }
    let (mode, dir) = (args[1].clone(), args[2].clone());
    let mut pdfs: Vec<String> = Vec::new();
    let mut specs: Vec<Spec> = Vec::new();
    let mut i = 3;
    while i < args.len() {
        match args[i].as_str() {
            "--pdf" => {
                pdfs.push(args[i + 1].clone());
                i += 2;
            }
            "--req" => {
                let id = specs.len() as i32 + 1;
                specs.push(parse_spec(id, &args[i + 1]));
                i += 2;
            }
            a => panic!("argument {}", a),
        }
    }
    assert_eq!(emon::plan::pk_render(), KIND_RENDER);
    assert_eq!(emon::plan::pk_crop(), KIND_CROP);
    let lens: Vec<u64> = pdfs.iter().map(|p| std::fs::metadata(p).unwrap().len()).collect();
    let inputs: Vec<(i32, i32)> = lens.iter().enumerate().map(|(i, l)| (i as i32, *l as i32)).collect();
    let cfg = Config::default();
    let mut transcript = Transcript { inputs: inputs.clone(), entries: Vec::new(), producer: Vec::new() };
    let mut replayer: Option<Replayer> = None;
    if mode == "replay" {
        let t = match Transcript::from_files(&read_dir(&dir)).and_then(|t| t.check_inputs(&inputs).map(|_| t)) {
            Ok(t) => t,
            Err(e) => fail(&e),
        };
        replayer = Some(Replayer::new(&t));
        transcript = t;
    }

    let mut unit = |an: &mut Cells, rq: &mut Cells| -> i32 {
        let mut missing = false;
        for s in &specs {
            if emon::plan::rec_find(an, s.id) < 0 {
                missing = true;
                match s.clip {
                    None => {
                        emon::plan::rq_render(rq, s.id, s.input, s.page, s.dpi, s.layer);
                    }
                    Some(c) => {
                        emon::plan::rq_crop(rq, s.id, s.input, s.page, s.dpi, s.layer, c[0], c[1], c[2], c[3]);
                    }
                }
            }
        }
        if missing {
            UNIT_REQUESTS
        } else {
            UNIT_DONE
        }
    };
    let mut bytes = SyncFulfiller::new(|req: &Request, _bm: &Bitmaps| -> Result<Answer, PlanError> {
        Err(PlanError::Fulfil("this driver reads no bytes through the loop".to_string()))
    });

    let recording = mode == "record";
    let result = if recording {
        #[cfg(pdfium)]
        {
            let mut srcs: Vec<Arc<FileSource>> = Vec::new();
            let mut ins: Vec<render::RenderInput> = Vec::new();
            for p in &pdfs {
                let f = std::fs::File::open(p).unwrap();
                let len = f.metadata().unwrap().len();
                let s = Arc::new(FileSource { f, len, reads: Default::default(), bytes: Default::default() });
                srcs.push(s.clone());
                ins.push(render::RenderInput { source: s, password: None });
            }
            let mut f = render::RenderFulfiller::new(Arc::new(pdfium::Pdfium::new()), ins);
            let mut nm = NoMisses;
            let t0 = std::time::Instant::now();
            let r = run(&cfg, &mut unit, &mut f, &mut bytes, &mut nm, Some(&mut transcript));
            eprintln!("render: {} ms for {} requests", t0.elapsed().as_millis(), specs.len());
            for (p, s) in pdfs.iter().zip(&srcs) {
                use std::sync::atomic::Ordering::Relaxed;
                eprintln!("source {}: {} reads, {} bytes read of {}", p, s.reads.load(Relaxed), s.bytes.load(Relaxed), s.len);
            }
            r
        }
        #[cfg(not(pdfium))]
        {
            eprintln!("record needs --cfg pdfium");
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
    if let Some(r) = replayer.as_mut() {
        if let Err(e) = r.check_done() {
            fail(&e);
        }
    }
    eprintln!("stats: attempts={} rounds={} requests={}", stats.attempts, stats.rounds, stats.requests);

    for e in &transcript.entries {
        println!("entry {} {}: req {:?} ans {:?}", e.req[2], kind_name_pub(e.req[1]), e.req, e.ans);
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

fn kind_name_pub(k: i32) -> &'static str {
    match k {
        KIND_RENDER => "render",
        KIND_CROP => "crop",
        _ => "other",
    }
}
