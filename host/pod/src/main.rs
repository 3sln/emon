// THE BABASHKA POD (DEVLOG P9.4, D474, D475): emon as a pod, `emon-pod`, a Rust binary over the Rust host. Babashka starts it and talks the
// pod protocol over its stdin and stdout (bencode messages; the arguments and the values are EDN, the pod's `format`):
//
//     (require '[babashka.pods :as pods])
//     (pods/load-pod ["path/to/emon-pod"])
//     (emon/convert "doc.pdf" "ocr=never")                          ; a path, option text
//     (emon/convert {:bytes (base64 ...)} "assets=images" {:html ["" "ids,geometry"] :model [:json :edn]})
//
// THE OPS (the namespace is `emon`):
//
//     emon/convert  INPUT [OPTIONS [SPEC]]   convert one document or image
//       INPUT    a path string, or a map {:path P | :bytes BASE64, :password PW}. A path is a LAZY byte source (the file is read in chunks,
//                on demand, as the engine touches it: SPEC 13.1); :bytes is an in-memory source (pods carry EDN, not byte arrays: base64).
//       OPTIONS  kin's option text (`emon.config`: `key=value` lines, or nil), the same one every binding speaks (P10.10).
//       SPEC     what to return (default {:model :json :html true :assets :bytes}); a vector where a value is listed gives a vector back:
//                  :model   :json | :json-pretty | :edn      the Model, as JSON text or as EDN data (keywords for names)
//                  :html    true | "html,options" | [..]     the HTML (the drivers' --html= list; true is the clean default)
//                  :evidence true                             the text layer's evidence dump (needs keep-evidence=1)
//                  :assets  :bytes | :info                    the Model's assets, with their bytes (BASE64) or without
//                  :native  true                              use the build's own pdfium / Tesseract / codecs (`emon/capabilities`)
//                  :pdfium  "PATH"   :tessdata "DIR"          where they are (a file or a directory; else $EMON_PDFIUM_LIB / $EMON_TESSDATA, else
//                                                             lib/libpdfium.* and share/emon/tessdata next to the pod: the archive's layout)
//       RETURNS  {:model .. :html .. :evidence .. :assets [{:id :name :media-type :origin :sha256 :width :height :size :bytes}] :intake {..} :stats {..}}
//     emon/intake   INPUT [OPTIONS]          stage 0 alone: {:format "pdf" :version "1.4" :pages 3 :encrypted false :bytes 1234}
//     emon/check-options OPTIONS             true, or an error naming kin's sentence
//     emon/option-key? NAME                  whether NAME is a key of kin's grammar
//     emon/capabilities                      {:version .. :pdfium .. :tesseract .. :codecs ..}
//
// ERRORS are exceptions: the message is the engine's sentence, and the ex-data {:name "password-required" :code 4 :category "password-required"}
// (`name` is kin's: a PDF status, a limit, a plan error; `code` the category, the C binding's `EMON_ERR_*`).
//
// NO CRATE IS ADDED: bencode, EDN, JSON-to-EDN and base64 are written here (small, and the pod's wire is what a test reads). The conversion runs
// on a thread with a 32 MiB stack. NOTHING but protocol goes to stdout; diagnostics go to stderr.
use emon::capi::{capabilities, category_code, category_name, html_options, native_config_at};
use emon::{parse_options, Engine, Error, Input, Model};
use std::collections::BTreeMap;
use std::io::{BufRead, Write};
use std::panic::{catch_unwind, AssertUnwindSafe};

// ------------------------------------------------------------------------------------------------ bencode

enum Bv {
    Int(i64),
    Str(Vec<u8>),
    List(Vec<Bv>),
    Dict(BTreeMap<Vec<u8>, Bv>),
}

fn bs(s: &str) -> Bv {
    Bv::Str(s.as_bytes().to_vec())
}

fn read_byte(r: &mut impl BufRead) -> std::io::Result<Option<u8>> {
    let b = r.fill_buf()?;
    if b.is_empty() {
        return Ok(None);
    }
    let c = b[0];
    r.consume(1);
    Ok(Some(c))
}

fn bad(m: &str) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, m.to_string())
}

fn read_until(r: &mut impl BufRead, end: u8) -> std::io::Result<Vec<u8>> {
    let mut v = Vec::new();
    loop {
        match read_byte(r)? {
            Some(c) if c == end => return Ok(v),
            Some(c) => v.push(c),
            None => return Err(bad("the stream ended inside a bencode value")),
        }
    }
}

fn read_bv(r: &mut impl BufRead, first: u8) -> std::io::Result<Bv> {
    match first {
        b'i' => {
            let n = read_until(r, b'e')?;
            std::str::from_utf8(&n).ok().and_then(|s| s.parse().ok()).map(Bv::Int).ok_or_else(|| bad("a bencode integer"))
        }
        b'l' => {
            let mut v = Vec::new();
            loop {
                match read_byte(r)? {
                    Some(b'e') => return Ok(Bv::List(v)),
                    Some(c) => v.push(read_bv(r, c)?),
                    None => return Err(bad("the stream ended inside a list")),
                }
            }
        }
        b'd' => {
            let mut m = BTreeMap::new();
            loop {
                match read_byte(r)? {
                    Some(b'e') => return Ok(Bv::Dict(m)),
                    Some(c) => {
                        let k = match read_bv(r, c)? {
                            Bv::Str(k) => k,
                            _ => return Err(bad("a bencode dictionary key is a string")),
                        };
                        let c = read_byte(r)?.ok_or_else(|| bad("the stream ended inside a dictionary"))?;
                        m.insert(k, read_bv(r, c)?);
                    }
                    None => return Err(bad("the stream ended inside a dictionary")),
                }
            }
        }
        d if d.is_ascii_digit() => {
            let mut n = vec![d];
            n.extend(read_until(r, b':')?);
            let len: usize = std::str::from_utf8(&n).ok().and_then(|s| s.parse().ok()).ok_or_else(|| bad("a bencode string length"))?;
            let mut v = vec![0u8; len];
            r.read_exact(&mut v)?;
            Ok(Bv::Str(v))
        }
        _ => Err(bad("not a bencode value")),
    }
}

fn write_bv(w: &mut Vec<u8>, v: &Bv) {
    match v {
        Bv::Int(n) => w.extend_from_slice(format!("i{}e", n).as_bytes()),
        Bv::Str(s) => {
            w.extend_from_slice(format!("{}:", s.len()).as_bytes());
            w.extend_from_slice(s);
        }
        Bv::List(l) => {
            w.push(b'l');
            for x in l {
                write_bv(w, x);
            }
            w.push(b'e');
        }
        Bv::Dict(m) => {
            w.push(b'd');
            for (k, x) in m {
                write_bv(w, &Bv::Str(k.clone()));
                write_bv(w, x);
            }
            w.push(b'e');
        }
    }
}

fn dict(pairs: Vec<(&str, Bv)>) -> Bv {
    Bv::Dict(pairs.into_iter().map(|(k, v)| (k.as_bytes().to_vec(), v)).collect())
}

fn send(out: &mut impl Write, v: &Bv) {
    let mut b = Vec::new();
    write_bv(&mut b, v);
    out.write_all(&b).and_then(|_| out.flush()).unwrap_or_else(|_| std::process::exit(0));
}

// ------------------------------------------------------------------------------------------------ EDN

#[derive(Debug, Clone, PartialEq)]
enum Edn {
    Nil,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(String),
    Kw(String),
    Vec(Vec<Edn>),
    Map(Vec<(Edn, Edn)>),
}

struct EdnReader {
    s: Vec<char>,
    i: usize,
}

impl EdnReader {
    fn skip(&mut self) {
        while self.i < self.s.len() && (self.s[self.i].is_whitespace() || self.s[self.i] == ',') {
            self.i += 1;
        }
    }

    fn value(&mut self) -> Result<Edn, String> {
        self.skip();
        let c = *self.s.get(self.i).ok_or("the EDN ended early")?;
        match c {
            '[' | '(' => {
                self.i += 1;
                let close = if c == '[' { ']' } else { ')' };
                let mut v = Vec::new();
                loop {
                    self.skip();
                    match self.s.get(self.i) {
                        Some(&x) if x == close => {
                            self.i += 1;
                            return Ok(Edn::Vec(v));
                        }
                        Some(_) => v.push(self.value()?),
                        None => return Err("an unclosed EDN collection".to_string()),
                    }
                }
            }
            '#' if self.s.get(self.i + 1) == Some(&'{') => {
                self.i += 2;
                let mut v = Vec::new();
                loop {
                    self.skip();
                    match self.s.get(self.i) {
                        Some('}') => {
                            self.i += 1;
                            return Ok(Edn::Vec(v));
                        }
                        Some(_) => v.push(self.value()?),
                        None => return Err("an unclosed EDN set".to_string()),
                    }
                }
            }
            '{' => {
                self.i += 1;
                let mut v = Vec::new();
                loop {
                    self.skip();
                    match self.s.get(self.i) {
                        Some('}') => {
                            self.i += 1;
                            return Ok(Edn::Map(v));
                        }
                        Some(_) => {
                            let k = self.value()?;
                            let x = self.value()?;
                            v.push((k, x));
                        }
                        None => return Err("an unclosed EDN map".to_string()),
                    }
                }
            }
            '"' => {
                self.i += 1;
                let mut out = String::new();
                loop {
                    let c = *self.s.get(self.i).ok_or("an unclosed EDN string")?;
                    self.i += 1;
                    match c {
                        '"' => return Ok(Edn::Str(out)),
                        '\\' => {
                            let e = *self.s.get(self.i).ok_or("an unclosed EDN string")?;
                            self.i += 1;
                            match e {
                                'n' => out.push('\n'),
                                't' => out.push('\t'),
                                'r' => out.push('\r'),
                                'b' => out.push('\u{8}'),
                                'f' => out.push('\u{c}'),
                                '"' => out.push('"'),
                                '\\' => out.push('\\'),
                                '/' => out.push('/'),
                                'u' => {
                                    let h: String = self.s.get(self.i..self.i + 4).ok_or("a short \\u escape")?.iter().collect();
                                    self.i += 4;
                                    out.push(char::from_u32(u32::from_str_radix(&h, 16).map_err(|_| "a bad \\u escape")?).unwrap_or('\u{FFFD}'));
                                }
                                other => return Err(format!("an unknown EDN escape \\{}", other)),
                            }
                        }
                        c => out.push(c),
                    }
                }
            }
            _ => {
                let start = self.i;
                while self.i < self.s.len() && !self.s[self.i].is_whitespace() && !"[](){}\",".contains(self.s[self.i]) {
                    self.i += 1;
                }
                let tok: String = self.s[start..self.i].iter().collect();
                if tok.is_empty() {
                    return Err(format!("an EDN character {:?} that starts nothing", c));
                }
                Ok(match tok.as_str() {
                    "nil" => Edn::Nil,
                    "true" => Edn::Bool(true),
                    "false" => Edn::Bool(false),
                    t if t.starts_with(':') => Edn::Kw(t[1..].to_string()),
                    t => {
                        if let Ok(n) = t.trim_end_matches('N').parse::<i64>() {
                            Edn::Int(n)
                        } else if let Ok(f) = t.trim_end_matches('M').parse::<f64>() {
                            Edn::Float(f)
                        } else {
                            Edn::Str(t.to_string())
                        }
                    }
                })
            }
        }
    }
}

fn parse_edn(s: &str) -> Result<Edn, String> {
    let mut r = EdnReader { s: s.chars().collect(), i: 0 };
    r.value()
}

fn write_str(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

fn write_edn(out: &mut String, v: &Edn) {
    match v {
        Edn::Nil => out.push_str("nil"),
        Edn::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Edn::Int(n) => out.push_str(&n.to_string()),
        Edn::Float(f) => out.push_str(&format!("{:?}", f)),
        Edn::Str(s) => write_str(out, s),
        Edn::Kw(k) => {
            out.push(':');
            out.push_str(k);
        }
        Edn::Vec(l) => {
            out.push('[');
            for (i, x) in l.iter().enumerate() {
                if i > 0 {
                    out.push(' ');
                }
                write_edn(out, x);
            }
            out.push(']');
        }
        Edn::Map(m) => {
            out.push('{');
            for (i, (k, x)) in m.iter().enumerate() {
                if i > 0 {
                    out.push(' ');
                }
                write_edn(out, k);
                out.push(' ');
                write_edn(out, x);
            }
            out.push('}');
        }
    }
}

fn edn_text(v: &Edn) -> String {
    let mut s = String::new();
    write_edn(&mut s, v);
    s
}

fn kw(k: &str) -> Edn {
    Edn::Kw(k.to_string())
}

fn s(x: &str) -> Edn {
    Edn::Str(x.to_string())
}

fn map(pairs: Vec<(&str, Edn)>) -> Edn {
    Edn::Map(pairs.into_iter().map(|(k, v)| (kw(k), v)).collect())
}

fn get<'a>(m: &'a [(Edn, Edn)], k: &str) -> Option<&'a Edn> {
    m.iter().find(|(key, _)| matches!(key, Edn::Kw(n) if n == k)).map(|(_, v)| v)
}

// ------------------------------------------------------------------------------------------------ JSON, to EDN

struct JsonReader<'a> {
    s: &'a [u8],
    i: usize,
}

impl<'a> JsonReader<'a> {
    fn ws(&mut self) {
        while self.i < self.s.len() && matches!(self.s[self.i], b' ' | b'\n' | b'\r' | b'\t') {
            self.i += 1;
        }
    }

    fn string(&mut self) -> Result<String, String> {
        self.i += 1;
        let mut out: Vec<u8> = Vec::new();
        loop {
            let c = *self.s.get(self.i).ok_or("an unclosed JSON string")?;
            self.i += 1;
            match c {
                b'"' => return String::from_utf8(out).map_err(|_| "a JSON string that is not UTF-8".to_string()),
                b'\\' => {
                    let e = *self.s.get(self.i).ok_or("an unclosed JSON string")?;
                    self.i += 1;
                    match e {
                        b'n' => out.push(b'\n'),
                        b't' => out.push(b'\t'),
                        b'r' => out.push(b'\r'),
                        b'b' => out.push(8),
                        b'f' => out.push(12),
                        b'u' => {
                            let mut cp = self.hex4()?;
                            if (0xD800..0xDC00).contains(&cp) && self.s.get(self.i) == Some(&b'\\') && self.s.get(self.i + 1) == Some(&b'u') {
                                self.i += 2;
                                let lo = self.hex4()?;
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo.wrapping_sub(0xDC00) & 0x3FF);
                            }
                            let ch = char::from_u32(cp).unwrap_or('\u{FFFD}');
                            let mut buf = [0u8; 4];
                            out.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
                        }
                        o => out.push(o),
                    }
                }
                c => out.push(c),
            }
        }
    }

    fn hex4(&mut self) -> Result<u32, String> {
        let h = self.s.get(self.i..self.i + 4).ok_or("a short \\u escape")?;
        self.i += 4;
        u32::from_str_radix(std::str::from_utf8(h).map_err(|_| "a bad \\u escape")?, 16).map_err(|_| "a bad \\u escape".to_string())
    }

    fn value(&mut self) -> Result<Edn, String> {
        self.ws();
        match *self.s.get(self.i).ok_or("the JSON ended early")? {
            b'{' => {
                self.i += 1;
                let mut v = Vec::new();
                loop {
                    self.ws();
                    match self.s.get(self.i) {
                        Some(b'}') => {
                            self.i += 1;
                            return Ok(Edn::Map(v));
                        }
                        Some(b',') => self.i += 1,
                        Some(b'"') => {
                            let k = self.string()?;
                            self.ws();
                            if self.s.get(self.i) != Some(&b':') {
                                return Err("a JSON object wants a colon".to_string());
                            }
                            self.i += 1;
                            let x = self.value()?;
                            // a name an EDN reader reads back as a keyword is one; anything else stays a string
                            let ok = !k.is_empty() && k.chars().next().is_some_and(|c| c.is_ascii_alphabetic()) && k.chars().all(|c| c.is_ascii_alphanumeric() || "-_.*+!?".contains(c));
                            v.push((if ok { Edn::Kw(k) } else { Edn::Str(k) }, x));
                        }
                        _ => return Err("a JSON object wants a key".to_string()),
                    }
                }
            }
            b'[' => {
                self.i += 1;
                let mut v = Vec::new();
                loop {
                    self.ws();
                    match self.s.get(self.i) {
                        Some(b']') => {
                            self.i += 1;
                            return Ok(Edn::Vec(v));
                        }
                        Some(b',') => self.i += 1,
                        Some(_) => v.push(self.value()?),
                        None => return Err("an unclosed JSON array".to_string()),
                    }
                }
            }
            b'"' => Ok(Edn::Str(self.string()?)),
            _ => {
                let start = self.i;
                while self.i < self.s.len() && !matches!(self.s[self.i], b',' | b'}' | b']' | b' ' | b'\n' | b'\r' | b'\t') {
                    self.i += 1;
                }
                let t = std::str::from_utf8(&self.s[start..self.i]).map_err(|_| "a JSON token that is not UTF-8")?;
                Ok(match t {
                    "null" => Edn::Nil,
                    "true" => Edn::Bool(true),
                    "false" => Edn::Bool(false),
                    t => match t.parse::<i64>() {
                        Ok(n) => Edn::Int(n),
                        Err(_) => Edn::Float(t.parse::<f64>().map_err(|_| format!("a JSON token {:?}", t))?),
                    },
                })
            }
        }
    }
}

fn json_to_edn(json: &str) -> Result<Edn, String> {
    JsonReader { s: json.as_bytes(), i: 0 }.value()
}

// ------------------------------------------------------------------------------------------------ base64

const B64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

fn b64_encode(b: &[u8]) -> String {
    let mut o = String::with_capacity(b.len() / 3 * 4 + 4);
    for c in b.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        o.push(B64[(n >> 18) as usize & 63] as char);
        o.push(B64[(n >> 12) as usize & 63] as char);
        o.push(if c.len() > 1 { B64[(n >> 6) as usize & 63] as char } else { '=' });
        o.push(if c.len() > 2 { B64[n as usize & 63] as char } else { '=' });
    }
    o
}

fn b64_decode(t: &str) -> Result<Vec<u8>, String> {
    let mut out = Vec::with_capacity(t.len() / 4 * 3);
    let (mut acc, mut bits) = (0u32, 0);
    for c in t.bytes() {
        let v = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            b'=' | b'\n' | b'\r' | b' ' => continue,
            _ => return Err("the base64 has a character that is not base64".to_string()),
        };
        acc = acc << 6 | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Ok(out)
}

// ------------------------------------------------------------------------------------------------ the ops

struct PodError {
    name: String,
    code: i32,
    message: String,
}

impl PodError {
    fn usage(m: impl Into<String>) -> PodError {
        PodError { name: "usage".to_string(), code: 1, message: m.into() }
    }
}

impl From<Error> for PodError {
    fn from(e: Error) -> PodError {
        PodError { name: e.name(), code: category_code(&e), message: e.to_string() }
    }
}

fn input_of(v: &Edn) -> Result<Input, PodError> {
    let (path, bytes, password) = match v {
        Edn::Str(p) => (Some(p.clone()), None, None),
        Edn::Map(m) => {
            let text = |k: &str| match get(m, k) {
                Some(Edn::Str(x)) => Ok(Some(x.clone())),
                None | Some(Edn::Nil) => Ok(None),
                Some(_) => Err(PodError::usage(format!("the input's :{} is a string", k))),
            };
            (text("path")?, text("bytes")?, text("password")?)
        }
        _ => return Err(PodError::usage("the input is a path string or a map {:path | :bytes, :password}")),
    };
    let input = match (path, bytes) {
        (Some(p), None) => {
            // a LAZY source: the engine reads the spans it touches (SPEC 13.1)
            let f = std::fs::File::open(&p).map_err(|e| PodError { name: "io".to_string(), code: 10, message: format!("{}: {}", p, e) })?;
            Input::seekable(f).map_err(|e| PodError { name: "io".to_string(), code: 10, message: format!("{}: {}", p, e) })?
        }
        (None, Some(b)) => Input::bytes(b64_decode(&b).map_err(PodError::usage)?),
        _ => return Err(PodError::usage("the input has a :path or :bytes, not both and not neither")),
    };
    Ok(match password {
        Some(pw) => input.with_password(pw.into_bytes()),
        None => input,
    })
}

fn option_text(v: Option<&Edn>) -> Result<String, PodError> {
    match v {
        None | Some(Edn::Nil) => Ok(String::new()),
        Some(Edn::Str(t)) => Ok(t.clone()),
        Some(_) => Err(PodError::usage("the options are kin's option text, a string (or nil)")),
    }
}

// The engine and the Options for an option text; `native` brings the build's own ports.
fn setup(text: &str, native: bool, places: (Option<&str>, Option<&str>)) -> Result<(Engine, emon::Options), PodError> {
    let p = parse_options(text).map_err(PodError::usage)?;
    if p.record || p.js_ocr || p.js_render {
        return Err(PodError::usage("record=1, js-ocr=1 and js-render=1 are options of a session (the C binding's emon_open): the pod converts in one call"));
    }
    let mut cfg = p.config.clone();
    if native {
        native_config_at(&mut cfg, places.0, places.1).map_err(PodError::usage)?;
    }
    Ok((Engine::new(cfg), p.options))
}

fn list_of(v: Option<&Edn>) -> Vec<Edn> {
    match v {
        Some(Edn::Vec(l)) => l.clone(),
        Some(x) => vec![x.clone()],
        None => Vec::new(),
    }
}

fn shaped(v: Option<&Edn>, items: Vec<Edn>) -> Edn {
    if matches!(v, Some(Edn::Vec(_))) {
        Edn::Vec(items)
    } else {
        items.into_iter().next().unwrap_or(Edn::Nil)
    }
}

fn assets_of(model: &Model, with_bytes: bool) -> Edn {
    let mut v = Vec::new();
    for a in model.assets() {
        let bytes = a.bytes();
        let mut m = vec![
            ("id", Edn::Int(a.id() as i64)),
            ("name", s(&a.name())),
            ("media-type", s(&a.media_type())),
            ("origin", s(&a.origin())),
            ("sha256", s(&a.sha256())),
            ("width", Edn::Int(a.width() as i64)),
            ("height", Edn::Int(a.height() as i64)),
            ("size", Edn::Int(bytes.len() as i64)),
        ];
        if with_bytes {
            m.push(("bytes", Edn::Str(b64_encode(&bytes))));
        }
        v.push(map(m));
    }
    Edn::Vec(v)
}

fn intake_edn(i: &emon::Intake) -> Edn {
    map(vec![
        ("format", s(i.format)),
        ("version", s(&format!("{}.{}", i.version.0, i.version.1))),
        ("pages", Edn::Int(i.pages as i64)),
        ("encrypted", Edn::Bool(i.encrypted)),
        ("bytes", Edn::Int(i.bytes as i64)),
    ])
}

fn convert(args: &[Edn]) -> Result<Edn, PodError> {
    let input = input_of(args.first().ok_or_else(|| PodError::usage("emon/convert wants an input"))?)?;
    let mut text = option_text(args.get(1))?;
    let spec: Vec<(Edn, Edn)> = match args.get(2) {
        None | Some(Edn::Nil) => vec![(kw("model"), kw("json")), (kw("html"), Edn::Bool(true)), (kw("assets"), kw("bytes"))],
        Some(Edn::Map(m)) => m.clone(),
        Some(_) => return Err(PodError::usage("the spec is a map")),
    };
    let native = matches!(get(&spec, "native"), Some(Edn::Bool(true)));
    let want_evidence = matches!(get(&spec, "evidence"), Some(Edn::Bool(true)));
    if want_evidence && !text.lines().any(|l| l.starts_with("keep-evidence=")) {
        text.push_str("\nkeep-evidence=1\n");
    }
    // where pdfium and the models are, when the caller says (otherwise $EMON_PDFIUM_LIB, $EMON_TESSDATA, then next to the pod: see `emon/capabilities`)
    let place = |v: Option<&Edn>, k: &str| -> Result<Option<String>, PodError> {
        match v {
            None | Some(Edn::Nil) => Ok(None),
            Some(Edn::Str(p)) => Ok(Some(p.clone())),
            Some(_) => Err(PodError::usage(format!(":{} is a path string", k))),
        }
    };
    let (pdfium, tessdata) = (place(get(&spec, "pdfium"), "pdfium")?, place(get(&spec, "tessdata"), "tessdata")?);
    let (engine, opts) = setup(&text, native, (pdfium.as_deref(), tessdata.as_deref()))?;
    let (model, stats) = engine.convert_stats(input, &opts)?;
    let mut out: Vec<(&str, Edn)> = Vec::new();
    if let Some(m) = get(&spec, "model") {
        let mut items = Vec::new();
        for f in list_of(Some(m)) {
            items.push(match f {
                Edn::Kw(k) if k == "json" => Edn::Str(model.to_json(&emon::JsonOptions { pretty: false })),
                Edn::Kw(k) if k == "json-pretty" => Edn::Str(model.to_json(&emon::JsonOptions { pretty: true })),
                Edn::Kw(k) if k == "edn" => json_to_edn(&model.to_json(&emon::JsonOptions { pretty: false })).map_err(|e| PodError { name: "internal".to_string(), code: 13, message: e })?,
                Edn::Nil => Edn::Nil,
                _ => return Err(PodError::usage(":model is :json, :json-pretty or :edn (or a vector of them)")),
            });
        }
        if !matches!(m, Edn::Nil) {
            out.push(("model", shaped(Some(m), items)));
        }
    }
    if let Some(h) = get(&spec, "html") {
        let mut items = Vec::new();
        for f in list_of(Some(h)) {
            let list = match f {
                Edn::Str(l) => l,
                Edn::Bool(true) => String::new(),
                _ => return Err(PodError::usage(":html is true, an option list string, or a vector of them")),
            };
            items.push(Edn::Str(model.to_html(&html_options(&list).map_err(PodError::usage)?)));
        }
        if !matches!(h, Edn::Nil | Edn::Bool(false)) {
            out.push(("html", shaped(Some(h), items)));
        }
    }
    if want_evidence {
        out.push(("evidence", s(model.evidence().unwrap_or(""))));
    }
    match get(&spec, "assets") {
        Some(Edn::Kw(k)) if k == "bytes" => out.push(("assets", assets_of(&model, true))),
        Some(Edn::Kw(k)) if k == "info" => out.push(("assets", assets_of(&model, false))),
        None | Some(Edn::Nil) => {}
        Some(_) => return Err(PodError::usage(":assets is :bytes or :info")),
    }
    out.push(("intake", intake_edn(model.source())));
    out.push((
        "stats",
        map(vec![
            ("attempts", Edn::Int(stats.attempts as i64)),
            ("fetch-rounds", Edn::Int(stats.fetch_rounds as i64)),
            ("bytes-requests", Edn::Int(stats.bytes_requests as i64)),
            ("requests", Edn::Int(stats.requests as i64)),
            ("source-reads", Edn::Int(stats.source_reads as i64)),
            ("bytes-fetched", Edn::Int(stats.bytes_fetched as i64)),
            ("input-bytes", Edn::Int(stats.input_bytes as i64)),
        ]),
    ));
    Ok(map(out))
}

fn intake(args: &[Edn]) -> Result<Edn, PodError> {
    let input = input_of(args.first().ok_or_else(|| PodError::usage("emon/intake wants an input"))?)?;
    let (engine, _) = setup(&option_text(args.get(1))?, false, (None, None))?;
    let v = engine.intake(input)?;
    Ok(v.first().map(intake_edn).unwrap_or(Edn::Nil))
}

fn invoke(var: &str, args: &[Edn]) -> Result<Edn, PodError> {
    match var.rsplit('/').next().unwrap_or(var) {
        "convert" => convert(args),
        "intake" => intake(args),
        "check-options" => {
            parse_options(&option_text(args.first())?).map_err(PodError::usage)?;
            Ok(Edn::Bool(true))
        }
        "option-key?" => match args.first() {
            Some(Edn::Str(n)) | Some(Edn::Kw(n)) => Ok(Edn::Bool(emon::is_option_key(n))),
            _ => Err(PodError::usage("emon/option-key? wants a name")),
        },
        "capabilities" => Ok(map(vec![
            ("version", s("0.1.0")),
            ("pdfium", Edn::Bool(capabilities() & 1 != 0)),
            ("tesseract", Edn::Bool(capabilities() & 2 != 0)),
            ("codecs", Edn::Bool(capabilities() & 4 != 0)),
        ])),
        other => Err(PodError::usage(format!("emon has no var {}", other))),
    }
}

// ------------------------------------------------------------------------------------------------ the protocol

fn describe() -> Bv {
    let vars: Vec<Bv> = ["convert", "intake", "check-options", "option-key?", "capabilities"].iter().map(|n| dict(vec![("name", bs(n))])).collect();
    dict(vec![
        ("format", bs("edn")),
        ("namespaces", Bv::List(vec![dict(vec![("name", bs("emon")), ("vars", Bv::List(vars))])])),
        ("ops", dict(vec![("shutdown", dict(vec![]))])),
    ])
}

fn text_of(d: &BTreeMap<Vec<u8>, Bv>, k: &str) -> Option<String> {
    match d.get(k.as_bytes()) {
        Some(Bv::Str(b)) => String::from_utf8(b.clone()).ok(),
        _ => None,
    }
}

fn reply_error(out: &mut impl Write, id: &Bv, e: PodError) {
    let data = map(vec![
        ("name", s(&e.name)),
        ("code", Edn::Int(e.code as i64)),
        ("category", s(category_name(e.code).map(|c| c.trim_end_matches('\0')).unwrap_or("internal"))),
    ]);
    let id = match id {
        Bv::Str(b) => Bv::Str(b.clone()),
        _ => bs(""),
    };
    send(out, &dict(vec![("id", id), ("ex-message", bs(&e.message)), ("ex-data", bs(&edn_text(&data))), ("status", Bv::List(vec![bs("done"), bs("error")]))]));
}

fn serve() {
    let stdin = std::io::stdin();
    let mut r = std::io::BufReader::new(stdin.lock());
    let stdout = std::io::stdout();
    let mut out = stdout.lock();
    loop {
        let first = match read_byte(&mut r) {
            Ok(Some(c)) => c,
            _ => return,
        };
        let msg = match read_bv(&mut r, first) {
            Ok(Bv::Dict(d)) => d,
            _ => return,
        };
        match text_of(&msg, "op").as_deref() {
            Some("describe") => send(&mut out, &describe()),
            Some("shutdown") => return,
            Some("invoke") => {
                let id = msg.get("id".as_bytes()).map(|b| match b {
                    Bv::Str(x) => Bv::Str(x.clone()),
                    _ => bs(""),
                });
                let id = id.unwrap_or_else(|| bs(""));
                let var = text_of(&msg, "var").unwrap_or_default();
                let args = text_of(&msg, "args").unwrap_or_else(|| "[]".to_string());
                let result = catch_unwind(AssertUnwindSafe(|| -> Result<Edn, PodError> {
                    match parse_edn(&args).map_err(PodError::usage)? {
                        Edn::Vec(a) => invoke(&var, &a),
                        Edn::Nil => invoke(&var, &[]),
                        other => Err(PodError::usage(format!("the arguments are a vector, not {}", edn_text(&other)))),
                    }
                }));
                match result {
                    Ok(Ok(v)) => {
                        let id = match &id {
                            Bv::Str(b) => Bv::Str(b.clone()),
                            _ => bs(""),
                        };
                        send(&mut out, &dict(vec![("id", id), ("value", bs(&edn_text(&v))), ("status", Bv::List(vec![bs("done")]))]));
                    }
                    Ok(Err(e)) => reply_error(&mut out, &id, e),
                    Err(p) => {
                        let m = if let Some(x) = p.downcast_ref::<String>() {
                            x.clone()
                        } else if let Some(x) = p.downcast_ref::<&str>() {
                            x.to_string()
                        } else {
                            "a panic".to_string()
                        };
                        reply_error(&mut out, &id, PodError { name: "internal".to_string(), code: 13, message: m });
                    }
                }
            }
            _ => {
                eprintln!("emon-pod: an op it does not know");
            }
        }
    }
}

fn main() {
    // kin recurses: the whole pod runs on a thread with room for it
    let h = std::thread::Builder::new().name("emon-pod".to_string()).stack_size(32 << 20).spawn(serve).expect("a thread for the pod");
    let _ = h.join();
}
