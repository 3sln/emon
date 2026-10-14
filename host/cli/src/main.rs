// THE `emon` COMMAND (DEVLOG P11.4b, D1470; D524): the convenience CLI on the Rust host. One binary per platform, with pdfium beside it,
// Tesseract and Leptonica linked statically (`bb native`) and the `eng` and `osd` models in `share/emon/tessdata`. It is the Rust driver's
// engine wiring (`test/driver/rust/main.rs`) behind a user-facing surface: nothing here reads a document, decides a default or knows an
// option; every `key=value` is handed to kin's grammar (`emon::parse_options`) and a key kin does not know is kin's refusal.
//
//     emon convert INPUT [-o FILE] [--format html|json|model] [--assets omit|inline|dir] [--ocr auto|never|always]
//                        [--lang eng+deu] [--tessdata DIR] [--pdfium PATH] [--password TEXT] [key=value ...]
//     emon version        emon, pdfium, Tesseract and Leptonica versions, and where the bundled files were found
//     emon licenses       THIRD_PARTY_NOTICES.md
//     emon help
//
// Exit: 0 ok; 2 usage; 3 a bundled file is missing (pdfium, the models); 4 a named emon error (`error: NAME` on stderr); 1 anything else.
use emon::{Engine, HtmlAssets, HtmlOptions, Input, JsonOptions, OcrPolicy, Output};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;

// THIRD_PARTY_NOTICES.md, copied beside this file by kin/scripts/emon-rs-build (EMON_CLI=1)
const NOTICES: &str = include_str!("THIRD_PARTY_NOTICES.md");
// the pdfium build the archive was made with (`bb cli` sets it from tools/pdfium/pdfium.edn)
const PDFIUM_VERSION: &str = match option_env!("EMON_PDFIUM_VERSION") {
    Some(v) => v,
    None => "unknown",
};

#[cfg(target_os = "macos")]
const PDFIUM_LIB: &str = "libpdfium.dylib";
#[cfg(all(unix, not(target_os = "macos")))]
const PDFIUM_LIB: &str = "libpdfium.so";
#[cfg(windows)]
const PDFIUM_LIB: &str = "pdfium.dll";

const USAGE: &str = "usage:
  emon convert INPUT [-o FILE] [--format html|json|model] [--assets omit|inline|dir] [--ocr auto|never|always]
                     [--lang eng+deu] [--tessdata DIR] [--pdfium PATH] [--password TEXT] [key=value ...]
  emon version
  emon licenses
  emon help

INPUT is a PDF or an image file, or - for standard input. The result goes to FILE, else to standard output.
  --format   html (the default), json (the Model, compact) or model (the Model, indented)
  --assets   omit: the figures are left out; inline: figures and seals as PNGs inside the HTML; dir: as files in FILE.assets/ (needs -o)
  --ocr      when to read pages with Tesseract (the engine's own default when omitted)
  --lang     the Tesseract models to read with, joined by + (default: every model in the models directory)
  --tessdata the models directory (default: $EMON_TESSDATA, else ../share/emon/tessdata beside the executable)
  --pdfium   the pdfium library or the directory holding it (default: $EMON_PDFIUM_LIB, else ../lib or beside the executable)
  key=value  any option of the engine's grammar, e.g. ocr-scope=witness, asset-dpi=300, limit.pages=20, locale=de
Docs: docs/cli.md";

fn die(code: i32, msg: &str) -> ! {
    eprintln!("emon: {}", msg);
    std::process::exit(code)
}

fn usage(msg: &str) -> ! {
    eprintln!("emon: {}", msg);
    eprintln!("{}", USAGE);
    std::process::exit(2)
}

fn exe_dir() -> Option<PathBuf> {
    let exe = std::env::current_exe().ok()?;
    // a symlink to the binary (a package manager's bin/) must find the files of the real install
    #[cfg(unix)]
    let exe = exe.canonicalize().unwrap_or(exe);
    exe.parent().map(|p| p.to_path_buf())
}

// Where a bundled thing is looked for, in order: the explicit flag, the environment variable, then the archive's layout.
// An explicit place that is wrong is an error and is never replaced by the next one.
enum Found {
    Path(PathBuf),
    Missing(Vec<PathBuf>),
}

fn find_pdfium(flag: Option<&str>) -> Found {
    let explicit = flag.map(|s| s.to_string()).or_else(|| std::env::var("EMON_PDFIUM_LIB").ok().filter(|s| !s.is_empty()));
    if let Some(e) = explicit {
        let p = PathBuf::from(&e);
        let f = if p.is_dir() { p.join(PDFIUM_LIB) } else { p };
        return if f.is_file() { Found::Path(f) } else { Found::Missing(vec![f]) };
    }
    let mut tried = Vec::new();
    if let Some(d) = exe_dir() {
        let up = d.parent().map(|p| p.to_path_buf()).unwrap_or_else(|| d.clone());
        for c in [up.join("lib").join(PDFIUM_LIB), d.join(PDFIUM_LIB)] {
            if c.is_file() {
                return Found::Path(c);
            }
            tried.push(c);
        }
    }
    Found::Missing(tried)
}

fn has_models(d: &Path) -> bool {
    std::fs::read_dir(d).map(|r| r.filter_map(|e| e.ok()).any(|e| e.file_name().to_string_lossy().ends_with(".traineddata"))).unwrap_or(false)
}

fn find_tessdata(flag: Option<&str>) -> Found {
    let explicit = flag.map(|s| s.to_string()).or_else(|| std::env::var("EMON_TESSDATA").ok().filter(|s| !s.is_empty()));
    if let Some(e) = explicit {
        let p = PathBuf::from(e);
        return if has_models(&p) { Found::Path(p) } else { Found::Missing(vec![p]) };
    }
    let mut tried = Vec::new();
    if let Some(d) = exe_dir() {
        let c = d.parent().unwrap_or(&d).join("share").join("emon").join("tessdata");
        if has_models(&c) {
            return Found::Path(c);
        }
        tried.push(c);
    }
    Found::Missing(tried)
}

fn list(v: &[PathBuf]) -> String {
    v.iter().map(|p| format!("    {}", p.display())).collect::<Vec<_>>().join("\n")
}

// A one-word flag with a value: `--name VALUE` or `--name=VALUE`.
struct Args {
    rest: std::vec::IntoIter<String>,
}

impl Args {
    fn value(&mut self, name: &str, inline: Option<String>) -> String {
        match inline {
            Some(v) => v,
            None => self.rest.next().unwrap_or_else(|| usage(&format!("{} wants a value", name))),
        }
    }
}

fn convert(args: Vec<String>) {
    let mut a = Args { rest: args.into_iter() };
    let (mut input, mut out, mut format, mut assets, mut ocr, mut lang) = (None::<String>, None::<String>, "html".to_string(), None::<String>, None::<String>, None::<String>);
    let (mut tessdata, mut pdfium, mut password) = (None::<String>, None::<String>, None::<String>);
    let mut kv: Vec<String> = Vec::new();
    while let Some(arg) = a.rest.next() {
        let (flag, inline) = match arg.strip_prefix("--").and_then(|f| f.split_once('=')) {
            Some((f, v)) => (format!("--{}", f), Some(v.to_string())),
            None => (arg.clone(), None),
        };
        match flag.as_str() {
            "-o" | "--output" => out = Some(a.value("-o", inline)),
            "--format" => format = a.value("--format", inline),
            "--assets" => assets = Some(a.value("--assets", inline)),
            "--ocr" => ocr = Some(a.value("--ocr", inline)),
            "--lang" => lang = Some(a.value("--lang", inline)),
            "--tessdata" => tessdata = Some(a.value("--tessdata", inline)),
            "--pdfium" => pdfium = Some(a.value("--pdfium", inline)),
            "--password" => password = Some(a.value("--password", inline)),
            "-" => {
                if input.replace(arg).is_some() {
                    usage("one INPUT")
                }
            }
            f if f.starts_with('-') => usage(&format!("unknown flag {}", f)),
            _ => {
                if let Some((k, _)) = arg.split_once('=') {
                    if emon::is_option_key(k) {
                        kv.push(arg);
                    } else if Path::new(&arg).exists() {
                        if input.replace(arg).is_some() {
                            usage("one INPUT")
                        }
                    } else {
                        usage(&format!("{}: not an option of the engine's grammar (docs/cli.md lists them) and not a file", k));
                    }
                } else if input.replace(arg).is_some() {
                    usage("one INPUT")
                }
            }
        }
    }
    let input_path = input.unwrap_or_else(|| usage("no INPUT"));
    if !matches!(format.as_str(), "html" | "json" | "model") {
        usage("--format is html, json or model");
    }
    let has_key = |name: &str| kv.iter().any(|l| l.split_once('=').map(|(k, _)| k == name).unwrap_or(false));
    // the flags that stand for kin options become `key=value` lines; a key given both ways is a usage error, not a precedence rule
    let mut lines: Vec<String> = Vec::new();
    if let Some(o) = &ocr {
        if !matches!(o.as_str(), "auto" | "never" | "always") {
            usage("--ocr is auto, never or always");
        }
        if has_key("ocr") {
            usage("ocr is given twice (--ocr and ocr=)");
        }
        lines.push(format!("ocr={}", o));
    }
    let html_assets = match assets.as_deref() {
        None => None,
        Some("omit") => Some(HtmlAssets::Omit),
        Some("inline") | Some("dir") => {
            // the figures are only there to inline when they were extracted; an assets= of the caller's is theirs
            if !has_key("assets") {
                lines.push("assets=figures".to_string());
            }
            Some(if assets.as_deref() == Some("dir") { HtmlAssets::External } else { HtmlAssets::Inline })
        }
        Some(_) => usage("--assets is omit, inline or dir"),
    };
    if assets.as_deref() == Some("dir") && out.is_none() {
        usage("--assets dir writes beside the output: give -o FILE");
    }
    lines.extend(kv);
    let mut text = lines.join("\n");
    text.push('\n');
    let parsed = emon::parse_options(&text).unwrap_or_else(|m| usage(&m));
    let opts = parsed.options;
    let mut cfg = parsed.config;
    let never = matches!(opts.ocr, OcrPolicy::Never);

    // pdfium: a place the user named that is wrong is an error; with the archive's layout it may be absent only if OCR is off
    let explicit_pdfium = pdfium.is_some() || std::env::var("EMON_PDFIUM_LIB").map(|s| !s.is_empty()).unwrap_or(false);
    match find_pdfium(pdfium.as_deref()) {
        Found::Path(p) => {
            let s = p.to_str().unwrap_or_else(|| die(3, &format!("the path {} is not UTF-8", p.display())));
            emon::pdfium::load(s).unwrap_or_else(|e| die(3, &format!("cannot load pdfium from {}: {}", p.display(), e)));
            cfg.renderer = Some(Arc::new(emon::pdfium::Pdfium::new()));
        }
        Found::Missing(tried) => {
            if explicit_pdfium || !never {
                die(
                    3,
                    &format!(
                        "pdfium ({}) not found. Looked at:\n{}\nPass --pdfium PATH, set EMON_PDFIUM_LIB, or keep lib/ beside bin/ as the archive lays it out.",
                        PDFIUM_LIB,
                        list(&tried)
                    ),
                );
            }
            eprintln!("emon: note: no pdfium ({}): pages that need rendering fail; pass --pdfium PATH or set EMON_PDFIUM_LIB", PDFIUM_LIB);
        }
    }
    // Tesseract's models, unless the pages are never read
    if !never {
        let dir = match find_tessdata(tessdata.as_deref()) {
            Found::Path(p) => p,
            Found::Missing(tried) => die(
                3,
                &format!(
                    "no Tesseract models (*.traineddata) found. Looked at:\n{}\nPass --tessdata DIR, set EMON_TESSDATA, or keep share/emon/tessdata beside bin/ as the archive lays it out.",
                    if tried.is_empty() { "    (nowhere: the executable's own location is unknown)".to_string() } else { list(&tried) }
                ),
            ),
        };
        if !dir.join("osd.traineddata").exists() {
            eprintln!("emon: note: {} has no osd.traineddata: page orientation cannot be detected", dir.display());
        }
        let d = dir.to_string_lossy().to_string();
        let t = match &lang {
            Some(l) => {
                let langs: Vec<String> = l.split('+').filter(|s| !s.is_empty()).map(|s| s.to_string()).collect();
                if langs.is_empty() {
                    usage("--lang wants models joined by +, e.g. eng+deu");
                }
                emon::tess::TessOcr::with_languages(&d, &langs).unwrap_or_else(|e| die(3, &format!("{} (--lang {}); add the .traineddata file to that directory or pass --tessdata", e, l)))
            }
            None => emon::tess::TessOcr::new(Some(&d)).unwrap_or_else(|e| die(3, &e)),
        };
        cfg.ocr = Some(emon::OcrPort(Arc::new(t)));
    } else if lang.is_some() {
        eprintln!("emon: note: --lang does nothing with ocr=never");
    }

    // the document
    let io = |what: &str, e: std::io::Error| -> ! { die(1, &format!("{}: {}", what, e)) };
    let mut inp = if input_path == "-" {
        let mut b = Vec::new();
        std::io::Read::read_to_end(&mut std::io::stdin(), &mut b).unwrap_or_else(|e| io("standard input", e));
        Input::bytes(b)
    } else {
        let f = std::fs::File::open(&input_path).unwrap_or_else(|e| io(&input_path, e));
        Input::seekable(f).unwrap_or_else(|e| io(&input_path, e))
    };
    if let Some(p) = password {
        inp = inp.with_password(p.into_bytes());
    }
    let engine = Engine::new(cfg);
    let model = match engine.convert_stats(inp, &opts) {
        Ok((m, _)) => m,
        Err(e) => {
            eprintln!("error: {}", e.name());
            eprintln!("{}", e);
            std::process::exit(match e {
                emon::Error::Internal(_) => 1,
                _ => 4,
            })
        }
    };

    // the assets, as files, and the HTML that names them
    let mut html = HtmlOptions::default();
    if let Some(m) = html_assets {
        html.assets = m;
    }
    if assets.as_deref() == Some("dir") {
        let o = PathBuf::from(out.as_ref().unwrap());
        let stem = o.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_else(|| "out".to_string());
        let name = format!("{}.assets", stem);
        let dir = o.parent().filter(|p| !p.as_os_str().is_empty()).map(|p| p.join(&name)).unwrap_or_else(|| PathBuf::from(&name));
        let files = model.asset_files();
        if !files.is_empty() {
            std::fs::create_dir_all(&dir).unwrap_or_else(|e| io(&dir.display().to_string(), e));
            for f in files {
                std::fs::write(dir.join(&f.name), f.bytes).unwrap_or_else(|e| io(&dir.display().to_string(), e));
            }
        }
        html.asset_prefix = format!("{}/", name);
    }
    let text = match format.as_str() {
        "html" => model.serialize(&Output::Html(html)),
        "json" => model.serialize(&Output::Json(JsonOptions { pretty: false })),
        _ => model.serialize(&Output::Json(JsonOptions { pretty: true })),
    };
    match out {
        Some(p) => std::fs::write(&p, text).unwrap_or_else(|e| io(&p, e)),
        None => {
            // a closed pipe (`emon convert x.pdf | head`) is not an error worth a message
            let _ = std::io::stdout().write_all(text.as_bytes());
        }
    }
}

fn version() {
    println!("emon {}", env!("CARGO_PKG_VERSION"));
    println!("pdfium {} (bundled build)", PDFIUM_VERSION);
    println!("tesseract {}", emon::tess::version());
    println!("leptonica {}", emon::tess::leptonica_version());
    match find_pdfium(None) {
        Found::Path(p) => println!("pdfium library: {}", p.display()),
        Found::Missing(_) => println!("pdfium library: not found (pass --pdfium or set EMON_PDFIUM_LIB)"),
    }
    match find_tessdata(None) {
        Found::Path(p) => {
            let mut names: Vec<String> =
                std::fs::read_dir(&p).map(|r| r.filter_map(|e| e.ok()).filter_map(|e| e.file_name().to_string_lossy().strip_suffix(".traineddata").map(|s| s.to_string())).collect()).unwrap_or_default();
            names.sort();
            println!("tesseract models: {} ({})", p.display(), names.join(" "));
        }
        Found::Missing(_) => println!("tesseract models: not found (pass --tessdata or set EMON_TESSDATA)"),
    }
}

fn main() {
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    if args.is_empty() {
        usage("no command");
    }
    let cmd = args.remove(0);
    match cmd.as_str() {
        "convert" => convert(args),
        "version" | "--version" | "-V" => version(),
        "licenses" | "licences" => {
            let _ = std::io::stdout().write_all(NOTICES.as_bytes());
        }
        "help" | "--help" | "-h" => println!("{}", USAGE),
        other => usage(&format!("unknown command {}", other)),
    }
}
