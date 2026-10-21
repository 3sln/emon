// THE RUST DRIVER (DEVLOG P1.10): a test-only CLI over the Engine API. Its STDOUT is the only thing gates
// read (nome's driver shape); stats and errors go to stderr. It is not the library: it reads the file
// itself and hands the Engine bytes, a seekable `File` or a spooled `Read` (D058).
//
//     emon-rs [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG]
//             [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--tesseract[=TESSDATA]] [--tessdata=DIR --lang=eng+spa] [--pdfium] [--ocr-scope=full|witness] [--assets=none|images|figures] [--password=TEXT] [--source=bytes|seek|spool]
//             [--asset-dpi=N] [--asset-pixels=N] [--asset-bytes=N] [--assets-bytes=N] [--asset-signatures=on|off] [--asset-dir=D]
//             [--chunk=N --async] [--stats] [--record=DIR | --replay=DIR] [--concurrent=N] [--parallel=N] FILE
//
//   --furniture=     running headers, footers and page numbers (SPEC 10.2, D017): `policy` (the default), `keep`, `drop`
//   --out=json       the canonical JSON (compact; --pretty indents)           (default)
//   --out=html       the HTML; OPTS is a comma list of ids,geometry,confidence=N,ledger,pretty,
//                    drop-furniture,no-milestones,initials-plain,align-style,assets=inline|external|omit,prefix=TEXT
//   --assets=        SPEC 10.11 (D117): `none` (the default), `images` (a figure or seal that is one embedded image gets its
//                    bytes or its pixels as PNG), `figures` (also the others, rendered or cropped: P8.5); the limits that go with it
//   --html-assets=M  the HTML's mode for the Model's assets: inline (the default), external or omit (the same as `--html=assets=M`);
//   --asset-prefix=P its `asset-prefix` (the same as `--html=prefix=P`)
//   --asset-dir=D    write `Model::asset_files()` (a0.png, a1.jpg, ...) into D: the driver writes them, the library never does
//   --out=evidence   the text layer's evidence dump (compact JSON)
//   --out=bundle     one conversion, every output, for `bb identity`: sections `== json`, `== json-pretty`, `== html`,
//                    `== html-full` (ids,geometry,confidence=950,ledger,pretty), `== evidence`, `== intake`, each a line
//                    `== NAME` then the text and a newline (the flags --pretty and --html are ignored)
//   --out=intake     stage 0 only: `FORMAT VERSION pages=N encrypted=BOOL bytes=N`
//   --tessdata=D    alias for --tesseract=D; --lang=eng+spa filters the models in that directory
//   --out=pixels     one RECORDING, printed as one line per `decode` entry: `frame F w W h H ch C dpi100 D bilevel B frames N
//                    fnv HEX` (FNV-1a 64 of the decoded pixels), or `frame F status S` for an answer that is not ok: what
//                    `bb image` compares across hosts and with the generator's pixels (D190)
//   --source=        which adapter reads FILE: `bytes` (in memory), `seek` (Read + Seek: a File), `spool` (Read, spooled)
//   --chunk=N        the source cache's chunk size; with --async the cache behaves like a browser Blob (it
//                    misses, the loop fetches and re-runs): the output must equal the in-memory run's
//   --dump=DIR       with --out=pixels, also write each frame's pixels to DIR/frame-N.raw (w*h*ch bytes)
//   --ocr-scope=S    SPEC 13 (P7.S, D200): under `--ocr=always` a born-digital page is read whole with its text (`full`, the default) or as the render
//                    without its text plus witness lines of its text layer (`witness`). (`--ocr-born-digital`, the hidden setting of P7.3, is gone: the policy is on.)
//   --pdfium         give the Engine the pdfium `Renderer` (D127). LIVE: only a build with `--cfg pdfium` has it (`EMON_RS_PDFIUM=DIR
//                    kin/scripts/emon-rs-build`); no gate uses it. Without it a `render` request is answered `unsupported`.
//   --codecs         give the Engine the JPX and JBIG2 decoders of the `decode-img` request (OpenJPEG, jbig2dec: P6.1a, D336). LIVE: only a
//                    build with `--cfg codecs` has them (`EMON_RS_CODECS=DIR kin/scripts/emon-rs-build`); no gate uses it (the gates replay
//                    the transcript `bb asset-record` made with it). Without it a JPX or JBIG2 image is answered `unsupported`.
//   --tesseract[=D]  give the Engine the Tesseract `Ocr` port (D211), reading models from D (default: $TESSDATA_PREFIX and the usual
//                    places). LIVE: only a build with the `tesseract` feature has it (`EMON_RS_LIVE=1 kin/scripts/emon-rs-build`); no gate
//                    uses it. Without the flag an `ocr` request is answered `unsupported` and a page that needed OCR has no text.
//   --fake-ports     TEST ONLY (P10.2): a deterministic Renderer and Ocr stand in for pdfium and Tesseract, so the fulfilment path runs on every document with no
//                    engine installed (`bb repeat` holds serial and parallel runs of it to the same bytes). The twins of the other drivers answer alike per host.
//   --stats          print the loop's attempts, fetch rounds and bytes fetched on stderr
//   --record=DIR     also write the plan/fulfil transcript to DIR (transcript.edn and PNGs)
//   --replay=DIR     convert from the transcript in DIR (no port is called)
//   --concurrent=N   run N conversions at once on one Engine; all N must give the same answer (`bb repeat`). (It was `--parallel=N` until P10.2: that
//                    is now kin's option, `parallel=N`, N workers inside ONE conversion; `bb repeat` runs both.)
//   --parallel=N     kin's option (SPEC 13, P10.2): spread one conversion's fulfilment over N workers; the output is the serial run's at any N
//
// `test/driver/go/main.go` is its twin: the same flags (it adds `--source=readerat`), stdout and exit codes (`bb identity`).
//
// Exit: 0 ok; 2 usage; 4 a named emon error (`error: NAME` on stderr, nothing on stdout); 1 anything else.
use emon::{Config, Engine, HtmlAssets, HtmlOptions, Input, JsonOptions, Options, Output};
use std::io::Read;

// `--NAME=VALUE` when NAME is a key of kin's options grammar (`emon.config`): the `NAME=VALUE` line it stands for.
fn kin_flag(a: &str) -> Option<String> {
    let flag = a.strip_prefix("--")?;
    let (k, _) = flag.split_once('=')?;
    if emon::is_option_key(k) {
        Some(flag.to_string())
    } else {
        None
    }
}

fn usage(msg: &str) -> ! {
    eprintln!("emon-rs: {}", msg);
    eprintln!("usage: emon-rs [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG] [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--tesseract[=TESSDATA]] [--tessdata=DIR --lang=eng+spa] [--pdfium] [--codecs] [--ocr-scope=full|witness] [--assets=none|images|figures] [--asset-dpi=N] [--asset-pixels=N] [--asset-bytes=N] [--assets-bytes=N] [--asset-signatures=on|off] [--asset-dir=D] [--html-assets=inline|external|omit] [--asset-prefix=P] [--password=TEXT] [--source=bytes|seek|spool] [--chunk=N --async] [--stats] [--record=DIR | --replay=DIR] [--concurrent=N] [--parallel=N] FILE");
    std::process::exit(2);
}

// `--fake-ports` (P10.2): a deterministic Renderer and Ocr, so that the fulfilment path (a page unit's renders, decodes and OCR, the pages ahead looked
// at, a round fulfilled by several workers) runs on every document of a gate with no engine installed. The Renderer answers a 20 x 30 RGB page whose
// pixels are a function of the page, the layer, the resolution and the position; the Ocr answers every request with the word `Hello` in one line, through
// the kin builders a real engine's adapter uses. Both are pure functions of the request, as the real engines are (the property `bb repeat` holds them to).
#[derive(Debug)]
struct FakeRenderer;
impl emon::Renderer for FakeRenderer {
    fn render(&self, _input: &dyn emon::ByteSource, _pw: Option<&[u8]>, req: &emon::RenderRequest) -> Result<emon::port::Bitmap, emon::RenderError> {
        let mut px = Vec::new();
        for y in 0..30i32 {
            for x in 0..20i32 {
                let v = (x * 7 + y * 3 + req.layer * 50 + req.page * 11 + req.dpi) as u8;
                px.extend_from_slice(&[v, v ^ 0x55, 255 - v]);
            }
        }
        Ok(emon::port::Bitmap::from_bytes(20, 30, 3, &px))
    }
}

struct FakeOcr;
impl emon::Ocr for FakeOcr {
    fn fulfil(&self, req: &emon::plan::Request, _bm: &emon::plan::Bitmaps, _keep: bool) -> Result<emon::plan::Answer, emon::plan::PlanError> {
        use emon::emon::{ocr, plan};
        let mut c = emon::port::Cells::new();
        let at = plan::an_ocr(&mut c, req.id());
        let w = plan::an_ocr_word(&mut c, at, 10, 10, 100, 30, 900, 0, 0);
        for ch in "Hello".chars() {
            plan::an_ocr_cp(&mut c, w, ch as i32);
        }
        ocr::ox_begin(&mut c, 0, 0, 0, 0, -1, 0, -1, -1, 1, 1, 0, 0, 0);
        ocr::ox_line(&mut c, 10, 10, 100, 30, 10, 35, 110, 35, 0, 0, 0, 20, 14, 6);
        ocr::ox_block(&mut c, 10, 10, 100, 30, 0, 0, 0);
        let _ = ocr::ox_word(&mut c, 0, 0, -1, 0);
        plan::an_ocr_end(&mut c, at);
        let rec = (0..emon::port::cells_len(&c)).map(|i| emon::port::cells_at(&c, i)).collect();
        Ok(emon::plan::Answer { rec, bitmap: None })
    }
    fn producer(&self) -> Vec<(String, String)> {
        vec![("engine".to_string(), "fake".to_string())]
    }
}

fn html_options(list: &str) -> HtmlOptions {
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
                (Some(n), _) => o.confidence_below = Some(n.parse().unwrap_or_else(|_| usage("confidence=N wants a number"))),
                (_, Some(p)) => o.asset_prefix = p.to_string(),
                _ => usage(&format!("unknown html option {}", other)),
            },
        }
    }
    o
}

// The text of `--out=NAME` (or the whole bundle) for a converted Model.
// The `--out=pixels` text of a recording: its decode entries, in order.
fn pixel_lines(t: &emon::Transcript, dump: Option<&str>) -> String {
    let mut s = String::new();
    for e in &t.entries {
        if e.req.get(1) != Some(&2) || e.req[7] < 0 {
            continue;
        }
        let frame = e.req[7];
        if e.ans[3] != 0 {
            s.push_str(&format!("frame {} status {}\n", frame, e.ans[3]));
            continue;
        }
        let mut h = 0xcbf29ce484222325u64;
        if let Some(b) = &e.bitmap {
            let bytes = b.to_bytes();
            for &x in &bytes {
                h ^= x as u64;
                h = h.wrapping_mul(0x100000001b3);
            }
            if let Some(d) = dump {
                std::fs::create_dir_all(d).expect("--dump");
                std::fs::write(std::path::Path::new(d).join(format!("frame-{}.raw", frame)), &bytes).expect("--dump");
            }
        }
        s.push_str(&format!("frame {} w {} h {} ch {} dpi100 {} bilevel {} frames {} fnv {:016x}\n", frame, e.ans[4], e.ans[5], e.ans[6], e.ans[7], e.ans[8], e.ans[9], h));
    }
    s
}

// The hex of some bytes, for the bundle's asset section (byte for byte, the whole file).
fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{:02x}", x)).collect()
}

fn render(engine: &Engine, input: &Input, model: &emon::Model, out: &str, pretty: bool, html: HtmlOptions) -> String {
    match out {
        "json" => model.serialize(&Output::Json(JsonOptions { pretty })),
        "html" => model.serialize(&Output::Html(html)),
        "evidence" => model.evidence().unwrap_or("").to_string(),
        _ => {
            let full = HtmlOptions { ids: true, geometry: true, confidence_below: Some(950), ledger: true, pretty: true, ..HtmlOptions::default() };
            let intake = match engine.intake(input.clone()) {
                Ok(v) => v.iter().map(|i| format!("{} {}.{} pages={} encrypted={} bytes={}", i.format, i.version.0, i.version.1, i.pages, i.encrypted, i.bytes)).collect::<Vec<_>>().join("\n"),
                Err(e) => format!("error: {}", e.name()),
            };
            let mut s = format!(
                "== json\n{}\n== json-pretty\n{}\n== html\n{}\n== html-full\n{}\n== evidence\n{}\n== intake\n{}\n",
                model.serialize(&Output::Json(JsonOptions { pretty: false })),
                model.serialize(&Output::Json(JsonOptions { pretty: true })),
                model.serialize(&Output::Html(HtmlOptions::default())),
                model.serialize(&Output::Html(full)),
                model.evidence().unwrap_or(""),
                intake
            );
            // SPEC 10.11 / 14.1: with assets on, the HTML in the other two modes and every asset file, byte for byte (hex)
            if model.root().attr("assets").is_some() {
                let external = HtmlOptions { assets: HtmlAssets::External, asset_prefix: "assets/".to_string(), ..HtmlOptions::default() };
                let omit = HtmlOptions { assets: HtmlAssets::Omit, ..HtmlOptions::default() };
                s.push_str(&format!("== html-external\n{}\n== html-omit\n{}\n== assets\n", model.serialize(&Output::Html(external)), model.serialize(&Output::Html(omit))));
                for f in model.asset_files() {
                    s.push_str(&format!("{} {} {}\n{}\n", f.name, f.media_type, f.bytes.len(), hex(&f.bytes)));
                }
            }
            s
        }
    }
}

fn main() {
    let mut out = "json".to_string();
    let mut pretty = false;
    let mut html = HtmlOptions::default();
    let mut html_assets: Option<HtmlAssets> = None;
    let mut asset_prefix: Option<String> = None;
    let mut kin_text = String::new();
    let mut simulate_async = false;
    let mut no_retarget = false;
    let mut password: Option<String> = None;
    let mut source = "bytes".to_string();
    let mut stats = false;
    let mut file: Option<String> = None;
    let mut record: Option<String> = None;
    let mut replay: Option<String> = None;
    let mut concurrent: usize = 1;
    let mut dump: Option<String> = None;
    let mut asset_dir: Option<String> = None;
    let mut tess: Option<Option<String>> = None;
    let mut use_pdfium = false;
    let mut use_codecs = false;
    let mut fake_ports = false;
    let mut tess_langs: Option<Vec<String>> = None;
    for a in std::env::args().skip(1) {
        if let Some(v) = a.strip_prefix("--out=") {
            out = v.to_string();
        } else if a == "--pretty" {
            pretty = true;
        } else if let Some(v) = a.strip_prefix("--html=") {
            html = html_options(v);
        } else if let Some(v) = a.strip_prefix("--html-assets=") {
            html_assets = Some(match v {
                "inline" => HtmlAssets::Inline,
                "external" => HtmlAssets::External,
                "omit" => HtmlAssets::Omit,
                _ => usage("--html-assets is inline, external or omit"),
            });
        } else if let Some(v) = a.strip_prefix("--asset-prefix=") {
            asset_prefix = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--asset-dir=") {
            asset_dir = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--password=") {
            password = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--source=") {
            source = v.to_string();
        } else if a == "--async" {
            simulate_async = true;
        } else if a == "--no-retarget" {
            no_retarget = true;
        } else if a == "--pdfium" {
            use_pdfium = true;
        } else if a == "--codecs" {
            use_codecs = true;
        } else if a == "--fake-ports" {
            fake_ports = true;
        } else if a == "--tesseract" {
            if tess.is_none() { tess = Some(None); }
        } else if let Some(v) = a.strip_prefix("--tesseract=") {
            tess = Some(Some(v.to_string()));
        } else if let Some(v) = a.strip_prefix("--tessdata=") {
            tess = Some(Some(v.to_string()));
        } else if let Some(v) = a.strip_prefix("--lang=") {
            let names: Vec<String> = v.split('+').filter(|s| !s.is_empty()).map(str::to_string).collect();
            if names.is_empty() { usage("--lang wants models joined by +"); }
            tess_langs = Some(names);
        } else if a == "--stats" {
            stats = true;
        } else if let Some(v) = a.strip_prefix("--record=") {
            record = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--replay=") {
            replay = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--dump=") {
            dump = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--concurrent=") {
            concurrent = v.parse().ok().filter(|n| *n >= 1).unwrap_or_else(|| usage("--concurrent=N wants a positive number"));
        } else if let Some(line) = kin_flag(&a) {
            // a key of kin's options grammar (`emon.config`): --ocr=never, --asset-dpi=300, --limit.pages=5, --chunk=512, ...
            kin_text.push_str(&line);
            kin_text.push('\n');
        } else if a.starts_with("--") {
            usage(&format!("unknown flag {}", a));
        } else if file.is_none() {
            file = Some(a);
        } else {
            usage("one FILE");
        }
    }
    let file = file.unwrap_or_else(|| usage("no FILE"));
    if record.is_some() && replay.is_some() {
        usage("--record and --replay are two ways");
    }
    if (record.is_some() || replay.is_some()) && concurrent > 1 {
        usage("--record and --replay are one conversion");
    }
    // THE OPTIONS AND THE ENGINE'S BOUNDS ARE KIN'S GRAMMAR: the flags it knows were collected as `key=value` lines and are parsed once.
    let parsed = emon::parse_options(&kin_text).unwrap_or_else(|m| usage(&m));
    let mut opts: Options = parsed.options;
    let mut cfg: Config = parsed.config;
    cfg.simulate_async = simulate_async;
    // `no_retarget` is not public API: the field is the crate's `__test_hooks` feature (emon-rs-build turns it on)
    opts.no_retarget = no_retarget;
    opts.keep_evidence = out == "evidence" || out == "bundle";
    if let Some(a) = html_assets {
        html.assets = a;
    }
    if let Some(p) = asset_prefix {
        html.asset_prefix = p;
    }

    // THE DRIVER READS THE FILE (the library never does): three ways of handing the Engine the same bytes.
    let io = |e: std::io::Error| -> ! {
        eprintln!("emon-rs: {}: {}", file, e);
        std::process::exit(1)
    };
    let mut input = match source.as_str() {
        "bytes" => Input::bytes(std::fs::read(&file).unwrap_or_else(|e| io(e))),
        "seek" => Input::seekable(std::fs::File::open(&file).unwrap_or_else(|e| io(e))).unwrap_or_else(|e| io(e)),
        "spool" => {
            let f = std::fs::File::open(&file).unwrap_or_else(|e| io(e));
            // a plain `Read`: no seek is used
            struct ForwardOnly(std::fs::File);
            impl Read for ForwardOnly {
                fn read(&mut self, b: &mut [u8]) -> std::io::Result<usize> {
                    self.0.read(b)
                }
            }
            Input::spooled(ForwardOnly(f), 1 << 30).unwrap_or_else(|e| io(e))
        }
        _ => usage("--source is bytes, seek or spool"),
    };
    if let Some(p) = password {
        input = input.with_password(p.into_bytes());
    }

    if let Some(dir) = &tess {
        #[cfg(feature = "tesseract")]
        {
            let t = match &tess_langs {
                Some(langs) => {
                    let path = dir.clone().or_else(|| std::env::var("TESSDATA_PREFIX").ok()).unwrap_or_else(|| usage("--lang requires --tessdata=DIR (or TESSDATA_PREFIX)"));
                    emon::tess::TessOcr::with_languages(&path, langs)
                }
                None => emon::tess::TessOcr::new(dir.as_deref()),
            }.unwrap_or_else(|e| usage(&e));
            cfg.ocr = Some(emon::OcrPort(std::sync::Arc::new(t)));
        }
        #[cfg(not(feature = "tesseract"))]
        {
            let _ = (dir, &tess_langs);
            usage("--tesseract needs a build with the tesseract feature (EMON_RS_LIVE=1 kin/scripts/emon-rs-build)");
        }
    }
    if use_pdfium {
        #[cfg(pdfium)]
        {
            cfg.renderer = Some(std::sync::Arc::new(emon::pdfium::Pdfium::new()));
        }
        #[cfg(not(pdfium))]
        usage("--pdfium needs a build with --cfg pdfium (EMON_RS_PDFIUM=DIR kin/scripts/emon-rs-build)");
    }
    if fake_ports {
        if tess.is_some() || use_pdfium {
            usage("--fake-ports is the stand-in for --tesseract and --pdfium: not with them");
        }
        cfg.ocr = Some(emon::OcrPort(std::sync::Arc::new(FakeOcr)));
        cfg.renderer = Some(std::sync::Arc::new(FakeRenderer));
    }
    if use_codecs {
        #[cfg(codecs)]
        {
            cfg.pdf_codecs = Some(std::sync::Arc::new(emon::pdfcodecs::Codecs::new()));
        }
        #[cfg(not(codecs))]
        usage("--codecs needs a build with --cfg codecs (EMON_RS_CODECS=DIR kin/scripts/emon-rs-build)");
    }
    let engine = Engine::new(cfg);
    let fail = |e: emon::Error| -> ! {
        eprintln!("error: {}", e.name());
        eprintln!("{}", e);
        std::process::exit(match e {
            emon::Error::Internal(_) => 1,
            _ => 4,
        })
    };
    if out == "intake" {
        match engine.intake(input) {
            Ok(v) => {
                for i in v {
                    println!("{} {}.{} pages={} encrypted={} bytes={}", i.format, i.version.0, i.version.1, i.pages, i.encrypted, i.bytes);
                }
            }
            Err(e) => fail(e),
        }
        return;
    }
    if !matches!(out.as_str(), "json" | "html" | "evidence" | "bundle" | "pixels") {
        usage("--out is json, html, evidence, bundle, pixels or intake");
    }
    let transcript = replay.as_ref().map(|dir| {
        let mut files = std::collections::BTreeMap::new();
        for e in std::fs::read_dir(dir).unwrap_or_else(|e| io(e)) {
            let e = e.unwrap_or_else(|e| io(e));
            files.insert(e.file_name().to_string_lossy().to_string(), std::fs::read(e.path()).unwrap_or_else(|e| io(e)));
        }
        emon::Transcript::from_files(&files).unwrap_or_else(|e| fail(emon::Error::Plan(e)))
    });
    // one conversion's whole answer: the text (and what the loop did, and the transcript recorded), or the error
    type Answer = Result<(String, emon::RunStats), emon::Error>;
    let one = || -> Answer {
        if out == "pixels" {
            let (_, t) = engine.record(input.clone(), &opts)?;
            return Ok((pixel_lines(&t, dump.as_deref()), emon::RunStats::default()));
        }
        let (model, rs) = if let Some(t) = &transcript {
            (engine.replay(input.clone(), &opts, t)?, emon::RunStats::default())
        } else if let Some(dir) = &record {
            let (m, t) = engine.record(input.clone(), &opts)?;
            std::fs::create_dir_all(dir).unwrap_or_else(|e| io(e));
            for (name, bytes) in t.to_files() {
                std::fs::write(std::path::Path::new(dir).join(name), bytes).unwrap_or_else(|e| io(e));
            }
            (m, emon::RunStats::default())
        } else {
            engine.convert_stats(input.clone(), &opts)?
        };
        if let Some(dir) = &asset_dir {
            std::fs::create_dir_all(dir).unwrap_or_else(|e| io(e));
            for f in model.asset_files() {
                std::fs::write(std::path::Path::new(dir).join(f.name), f.bytes).unwrap_or_else(|e| io(e));
            }
        }
        let text = render(&engine, &input, &model, &out, pretty, html.clone());
        Ok((text, rs))
    };
    // the concurrent arm owns no transcript (a Transcript is not Send)
    let plain = || -> Answer {
        let (model, rs) = engine.convert_stats(input.clone(), &opts)?;
        let text = render(&engine, &input, &model, &out, pretty, html.clone());
        Ok((text, rs))
    };
    let mut results: Vec<Answer> = Vec::new();
    if concurrent == 1 {
        results.push(one());
    } else {
        std::thread::scope(|s| {
            let hs: Vec<_> = (0..concurrent).map(|_| s.spawn(|| plain())).collect();
            for h in hs {
                results.push(h.join().unwrap());
            }
        });
    }
    for (i, r) in results.iter().enumerate().skip(1) {
        let same = match (&results[0], r) {
            (Ok(a), Ok(b)) => a.0 == b.0,
            (Err(a), Err(b)) => a.to_string() == b.to_string(),
            _ => false,
        };
        if !same {
            eprintln!("error: nondeterministic");
            eprintln!("conversion {} of {} differs from conversion 0", i, concurrent);
            std::process::exit(1);
        }
    }
    let (text, rs) = match results.swap_remove(0) {
        Ok(x) => x,
        Err(e) => fail(e),
    };
    if stats {
        eprintln!(
            "stats: attempts={} fetch-rounds={} bytes-requests={} source-reads={} bytes-fetched={} of {} requests={}",
            rs.attempts, rs.fetch_rounds, rs.bytes_requests, rs.source_reads, rs.bytes_fetched, rs.input_bytes, rs.requests
        );
        // P7.4: the page units' rounds and requests, and the OCR wall time of the first pass against the targeted ones (microseconds)
        eprintln!(
            "page-stats: rounds={:?} requests={:?} ocr-first-us={} ocr-targeted-us={}",
            rs.page_rounds,
            rs.page_requests,
            emon::engine::OCR_US[0].load(std::sync::atomic::Ordering::Relaxed),
            emon::engine::OCR_US[1].load(std::sync::atomic::Ordering::Relaxed)
        );
        // P7.S (D210): wall time by stage, microseconds: the ports by request kind, the document pass, the page units (with the ports' time in them), the document unit
        eprintln!(
            "time-stats: render-us={} crop-us={} decode-us={} ocr-first-us={} ocr-later-us={} pass-us={} pages-us={} finish-us={}",
            rs.fulfil_us[1], rs.fulfil_us[5], rs.fulfil_us[2], rs.fulfil_us[3], rs.fulfil_us[7], rs.pass_us, rs.pages_us, rs.finish_us
        );
    }
    print!("{}", text);
}
