// THE RUST DRIVER (DEVLOG P1.10): a test-only CLI over the Engine API. Its STDOUT is the only thing gates
// read (nome's driver shape); stats and errors go to stderr. It is not the library: it reads the file
// itself and hands the Engine bytes, a seekable `File` or a spooled `Read` (D058).
//
//     emon-rs [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG]
//             [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--tesseract[=TESSDATA]] [--pdfium] [--ocr-scope=full|witness] [--assets=none|images|figures] [--password=TEXT] [--source=bytes|seek|spool]
//             [--asset-dpi=N] [--asset-pixels=N] [--asset-bytes=N] [--assets-bytes=N] [--asset-signatures=on|off] [--asset-dir=D]
//             [--chunk=N --async] [--stats] [--record=DIR | --replay=DIR] [--parallel=N] FILE
//
//   --furniture=     running headers, footers and page numbers (SPEC 10.2, D017): `policy` (the default), `keep`, `drop`
//   --out=json       the canonical JSON (compact; --pretty indents)           (default)
//   --out=html       the HTML; OPTS is a comma list of ids,geometry,confidence=N,ledger,pretty,
//                    drop-furniture,no-milestones,initials-plain,align-style,assets=inline|external|omit,prefix=TEXT
//   --assets=        SPEC 10.11 (D117): `none` (the default), `images` (a figure or seal that is one embedded image gets its
//                    bytes or its pixels as PNG), `figures` (runs as `images` until P8.5); the limits that go with it
//   --html-assets=M  the HTML's mode for the Model's assets: inline (the default), external or omit (the same as `--html=assets=M`);
//   --asset-prefix=P its `asset-prefix` (the same as `--html=prefix=P`)
//   --asset-dir=D    write `Model::asset_files()` (a0.png, a1.jpg, ...) into D: the driver writes them, the library never does
//   --out=evidence   the text layer's evidence dump (compact JSON)
//   --out=bundle     one conversion, every output, for `bb identity`: sections `== json`, `== json-pretty`, `== html`,
//                    `== html-full` (ids,geometry,confidence=950,ledger,pretty), `== evidence`, `== intake`, each a line
//                    `== NAME` then the text and a newline (the flags --pretty and --html are ignored)
//   --out=intake     stage 0 only: `FORMAT VERSION pages=N encrypted=BOOL bytes=N`
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
//   --stats          print the loop's attempts, fetch rounds and bytes fetched on stderr
//   --record=DIR     also write the plan/fulfil transcript to DIR (transcript.edn and PNGs)
//   --replay=DIR     convert from the transcript in DIR (no port is called)
//   --parallel=N     run N conversions at once on one Engine; all N must give the same answer (`bb repeat`)
//
// `test/driver/go/main.go` is its twin: the same flags (it adds `--source=readerat`), stdout and exit codes (`bb identity`).
//
// Exit: 0 ok; 2 usage; 4 a named emon error (`error: NAME` on stderr, nothing on stdout); 1 anything else.
use emon::{Assets, Config, Engine, Furniture, HtmlAssets, HtmlOptions, Input, JsonOptions, OcrPolicy, OcrScope, Options, Output};
use std::io::Read;

fn usage(msg: &str) -> ! {
    eprintln!("emon-rs: {}", msg);
    eprintln!("usage: emon-rs [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG] [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--tesseract[=TESSDATA]] [--pdfium] [--codecs] [--ocr-scope=full|witness] [--assets=none|images|figures] [--asset-dpi=N] [--asset-pixels=N] [--asset-bytes=N] [--assets-bytes=N] [--asset-signatures=on|off] [--asset-dir=D] [--html-assets=inline|external|omit] [--asset-prefix=P] [--password=TEXT] [--source=bytes|seek|spool] [--chunk=N --async] [--stats] [--record=DIR | --replay=DIR] [--parallel=N] FILE");
    std::process::exit(2);
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
    let mut opts = Options::default();
    let mut cfg = Config::default();
    let mut password: Option<String> = None;
    let mut source = "bytes".to_string();
    let mut stats = false;
    let mut file: Option<String> = None;
    let mut record: Option<String> = None;
    let mut replay: Option<String> = None;
    let mut parallel: usize = 1;
    let mut dump: Option<String> = None;
    let mut asset_dir: Option<String> = None;
    let mut tess: Option<Option<String>> = None;
    let mut use_pdfium = false;
    let mut use_codecs = false;
    for a in std::env::args().skip(1) {
        if let Some(v) = a.strip_prefix("--out=") {
            out = v.to_string();
        } else if a == "--pretty" {
            pretty = true;
        } else if let Some(v) = a.strip_prefix("--html=") {
            html = html_options(v);
        } else if let Some(v) = a.strip_prefix("--locale=") {
            opts.locale = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--ocr=") {
            opts.ocr = match v {
                "always" => OcrPolicy::Always,
                "auto" => OcrPolicy::Auto,
                "never" => OcrPolicy::Never,
                _ => usage("--ocr is always, auto or never"),
            };
        } else if let Some(v) = a.strip_prefix("--furniture=") {
            opts.furniture = match v {
                "policy" => Furniture::Policy,
                "keep" => Furniture::Keep,
                "drop" => Furniture::Drop,
                _ => usage("--furniture is policy, keep or drop"),
            };
        } else if let Some(v) = a.strip_prefix("--assets=") {
            opts.assets = match v {
                "none" => Assets::None,
                "images" => Assets::Images,
                "figures" => Assets::Figures,
                _ => usage("--assets is none, images or figures"),
            };
        } else if let Some(v) = a.strip_prefix("--html-assets=") {
            html_assets = Some(match v {
                "inline" => HtmlAssets::Inline,
                "external" => HtmlAssets::External,
                "omit" => HtmlAssets::Omit,
                _ => usage("--html-assets is inline, external or omit"),
            });
        } else if let Some(v) = a.strip_prefix("--asset-prefix=") {
            asset_prefix = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--asset-dpi=") {
            opts.asset_dpi = v.parse().unwrap_or_else(|_| usage("--asset-dpi=N wants a number"));
        } else if let Some(v) = a.strip_prefix("--asset-pixels=") {
            opts.asset_pixels = v.parse().unwrap_or_else(|_| usage("--asset-pixels=N wants a number"));
        } else if let Some(v) = a.strip_prefix("--asset-bytes=") {
            opts.asset_bytes = v.parse().unwrap_or_else(|_| usage("--asset-bytes=N wants a number"));
        } else if let Some(v) = a.strip_prefix("--assets-bytes=") {
            opts.assets_bytes = v.parse().unwrap_or_else(|_| usage("--assets-bytes=N wants a number"));
        } else if let Some(v) = a.strip_prefix("--asset-signatures=") {
            opts.asset_signatures = match v {
                "on" => true,
                "off" => false,
                _ => usage("--asset-signatures is on or off"),
            };
        } else if let Some(v) = a.strip_prefix("--asset-dir=") {
            asset_dir = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--password=") {
            password = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--source=") {
            source = v.to_string();
        } else if let Some(v) = a.strip_prefix("--chunk=") {
            cfg.chunk_size = v.parse().unwrap_or_else(|_| usage("--chunk=N wants a number"));
        } else if a == "--async" {
            cfg.simulate_async = true;
        } else if let Some(v) = a.strip_prefix("--ocr-scope=") {
            opts.ocr_scope = match v {
                "full" => OcrScope::Full,
                "witness" => OcrScope::Witness,
                _ => usage("--ocr-scope is full or witness"),
            };
        } else if a == "--no-retarget" {
            opts.no_retarget = true;
        } else if a == "--pdfium" {
            use_pdfium = true;
        } else if a == "--codecs" {
            use_codecs = true;
        } else if a == "--tesseract" {
            tess = Some(None);
        } else if let Some(v) = a.strip_prefix("--tesseract=") {
            tess = Some(Some(v.to_string()));
        } else if a == "--stats" {
            stats = true;
        } else if let Some(v) = a.strip_prefix("--record=") {
            record = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--replay=") {
            replay = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--dump=") {
            dump = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--parallel=") {
            parallel = v.parse().ok().filter(|n| *n >= 1).unwrap_or_else(|| usage("--parallel=N wants a positive number"));
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
    if (record.is_some() || replay.is_some()) && parallel > 1 {
        usage("--record and --replay are one conversion");
    }
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
            let t = emon::tess::TessOcr::new(dir.as_deref()).unwrap_or_else(|e| usage(&e));
            cfg.ocr = Some(emon::OcrPort(std::sync::Arc::new(t)));
        }
        #[cfg(not(feature = "tesseract"))]
        {
            let _ = dir;
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
    // the parallel arm owns no transcript (a Transcript is not Send)
    let plain = || -> Answer {
        let (model, rs) = engine.convert_stats(input.clone(), &opts)?;
        let text = render(&engine, &input, &model, &out, pretty, html.clone());
        Ok((text, rs))
    };
    let mut results: Vec<Answer> = Vec::new();
    if parallel == 1 {
        results.push(one());
    } else {
        std::thread::scope(|s| {
            let hs: Vec<_> = (0..parallel).map(|_| s.spawn(|| plain())).collect();
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
            eprintln!("conversion {} of {} differs from conversion 0", i, parallel);
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
