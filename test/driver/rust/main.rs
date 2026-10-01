// THE RUST DRIVER (DEVLOG P1.10): a test-only CLI over the Engine API. Its STDOUT is the only thing gates
// read (nome's driver shape); stats and errors go to stderr. It is not the library: it reads the file
// itself and hands the Engine bytes, a seekable `File` or a spooled `Read` (D058).
//
//     emon-rs [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG]
//             [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--assets=none] [--password=TEXT] [--source=bytes|seek|spool]
//             [--chunk=N --async] [--stats] FILE
//
//   --furniture=     running headers, footers and page numbers (SPEC 10.2, D017): `policy` (the default), `keep`, `drop`
//   --out=json       the canonical JSON (compact; --pretty indents)           (default)
//   --out=html       the HTML; OPTS is a comma list of ids,geometry,confidence=N,ledger,pretty,
//                    drop-furniture,no-milestones,initials-plain,align-style
//   --out=evidence   the text layer's evidence dump (compact JSON)
//   --out=intake     stage 0 only: `pdf VERSION pages=N encrypted=BOOL bytes=N`
//   --source=        which adapter reads FILE: `bytes` (in memory), `seek` (Read + Seek: a File), `spool` (Read, spooled)
//   --chunk=N        the source cache's chunk size; with --async the cache behaves like a browser Blob (it
//                    misses, the loop fetches and re-runs): the output must equal the in-memory run's
//   --stats          print the loop's attempts, fetch rounds and bytes fetched on stderr
//
// Exit: 0 ok; 2 usage; 4 a named emon error (`error: NAME` on stderr, nothing on stdout); 1 anything else.
use emon::{Assets, Config, Engine, Furniture, HtmlOptions, Input, JsonOptions, OcrPolicy, Options, Output};
use std::io::Read;

fn usage(msg: &str) -> ! {
    eprintln!("emon-rs: {}", msg);
    eprintln!("usage: emon-rs [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG] [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--assets=none] [--password=TEXT] [--source=bytes|seek|spool] [--chunk=N --async] [--stats] FILE");
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
            other => match other.strip_prefix("confidence=") {
                Some(n) => o.confidence_below = Some(n.parse().unwrap_or_else(|_| usage("confidence=N wants a number"))),
                None => usage(&format!("unknown html option {}", other)),
            },
        }
    }
    o
}

fn main() {
    let mut out = "json".to_string();
    let mut pretty = false;
    let mut html = HtmlOptions::default();
    let mut opts = Options::default();
    let mut cfg = Config::default();
    let mut password: Option<String> = None;
    let mut source = "bytes".to_string();
    let mut stats = false;
    let mut file: Option<String> = None;
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
        } else if let Some(v) = a.strip_prefix("--password=") {
            password = Some(v.to_string());
        } else if let Some(v) = a.strip_prefix("--source=") {
            source = v.to_string();
        } else if let Some(v) = a.strip_prefix("--chunk=") {
            cfg.chunk_size = v.parse().unwrap_or_else(|_| usage("--chunk=N wants a number"));
        } else if a == "--async" {
            cfg.simulate_async = true;
        } else if a == "--stats" {
            stats = true;
        } else if a.starts_with("--") {
            usage(&format!("unknown flag {}", a));
        } else if file.is_none() {
            file = Some(a);
        } else {
            usage("one FILE");
        }
    }
    let file = file.unwrap_or_else(|| usage("no FILE"));
    opts.keep_evidence = out == "evidence";

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
    let (model, rs) = match engine.convert_stats(input, &opts) {
        Ok(x) => x,
        Err(e) => fail(e),
    };
    if stats {
        eprintln!(
            "stats: attempts={} fetch-rounds={} bytes-requests={} source-reads={} bytes-fetched={} of {} requests={}",
            rs.attempts, rs.fetch_rounds, rs.bytes_requests, rs.source_reads, rs.bytes_fetched, rs.input_bytes, rs.requests
        );
    }
    let text = match out.as_str() {
        "json" => model.serialize(&Output::Json(JsonOptions { pretty })),
        "html" => model.serialize(&Output::Html(html)),
        "evidence" => model.evidence().unwrap_or("").to_string(),
        _ => usage("--out is json, html, evidence or intake"),
    };
    print!("{}", text);
}
