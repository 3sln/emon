// The Rust half of emon's host ports (SPEC 7.2, DEVLOG P0.2). Mirrors nome's.
//
// @kin:link:ns: emon.port
//
// THE MARKER IS BESIDE THE FUNCTION IT DESCRIBES: `kin.host` reads these and
// `bb hosts` compares the four trees against each other. A form declared in
// three hosts and not the fourth is the failure this exists to catch.

// ---------------------------------------------------------------- the Blob
//
// IMMUTABLE BYTES, read by index, and the reader NAMES ITS BYTE ORDER rather
// than taking the machine's. TIFF-in-PDF and image headers need both orders,
// so every multi-byte reader has a `-le` twin. The 32-bit readers answer an
// `I32` carrying the 32-bit PATTERN: the high bit set means negative, on every
// target. (Java and C# have no unsigned int; agreeing on the pattern is what
// all four can do.) An out-of-range index panics or throws -- failure is per
// page (D030) and is caught above the port, not here.

// @kin:link:form:blob-len: {:template "crate::port::blob_len({0})" :arity 1}
pub fn blob_len(b: &[u8]) -> i32 {
    b.len() as i32
}

// @kin:link:form:blob-u8: {:template "crate::port::blob_u8({0}, {1})" :arity 2}
pub fn blob_u8(b: &[u8], i: i32) -> i32 {
    b[i as usize] as i32
}

// @kin:link:form:blob-u16: {:template "crate::port::blob_u16({0}, {1})" :arity 2}
pub fn blob_u16(b: &[u8], i: i32) -> i32 {
    let i = i as usize;
    ((b[i] as i32) << 8) | (b[i + 1] as i32)
}

// @kin:link:form:blob-u32: {:template "crate::port::blob_u32({0}, {1})" :arity 2}
pub fn blob_u32(b: &[u8], i: i32) -> i32 {
    let i = i as usize;
    u32::from_be_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]]) as i32
}

// @kin:link:form:blob-u16le: {:template "crate::port::blob_u16le({0}, {1})" :arity 2}
pub fn blob_u16le(b: &[u8], i: i32) -> i32 {
    let i = i as usize;
    ((b[i + 1] as i32) << 8) | (b[i] as i32)
}

// @kin:link:form:blob-u32le: {:template "crate::port::blob_u32le({0}, {1})" :arity 2}
pub fn blob_u32le(b: &[u8], i: i32) -> i32 {
    let i = i as usize;
    u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]]) as i32
}

// ---------------------------------------------------------------- the Cells
//
// A GROWABLE RUN OF 32-BIT INTEGERS, read and written: kin cannot allocate,
// so every arena (SPEC 3.8) is one of these. A node IS its index.
//
// A READ NESTED INSIDE A WRITE DOES NOT COMPILE in Rust -- bind the read to a
// `let` first (nome's rule; the identity gate compiles the generated Rust).
#[derive(Clone)]
pub struct Cells {
    v: Vec<i32>,
}

impl Cells {
    pub fn new() -> Cells {
        Cells { v: Vec::new() }
    }
}

impl Default for Cells {
    fn default() -> Cells {
        Cells::new()
    }
}

// @kin:link:form:cells-len: {:template "crate::port::cells_len({0})" :arity 1}
pub fn cells_len(c: &Cells) -> i32 {
    c.v.len() as i32
}

// @kin:link:form:cells-at: {:template "crate::port::cells_at({0}, {1})" :arity 2}
pub fn cells_at(c: &Cells, i: i32) -> i32 {
    c.v[i as usize]
}

// @kin:link:form:cells-set: {:template "crate::port::cells_set({0}, {1}, {2})" :arity 3}
pub fn cells_set(c: &mut Cells, i: i32, v: i32) {
    c.v[i as usize] = v;
}

// @kin:link:form:cells-push: {:template "crate::port::cells_push({0}, {1})" :arity 2}
//
// Answers the index it wrote, because everything that pushes wants it.
pub fn cells_push(c: &mut Cells, v: i32) -> i32 {
    c.v.push(v);
    (c.v.len() - 1) as i32
}

// @kin:link:form:cells-drop: {:template "crate::port::cells_drop({0}, {1})" :arity 2}
//
// Truncates TO a length rather than removing a count.
pub fn cells_drop(c: &mut Cells, n: i32) {
    c.v.truncate(n as usize);
}

// @kin:link:form:cells-bytes: {:template "(&crate::port::cells_bytes({0})[..])" :arity 1}
//
// A `Blob` VIEW OF A `Cells` OF BYTES (each cell 0..255, the low eight bits
// taken): how a decoded stream, which kin can only build in a `Cells`, is
// handed to the parsers that read a `Blob` (an object stream, the next stage of
// a filter chain). A SNAPSHOT: later writes to the `Cells` are not seen, and
// each call copies. In Rust the template borrows a temporary, which lives to
// the end of the enclosing statement: call it NESTED in the call that uses it,
// never bind it with `let` (the same rule as `emon.test`'s blob makers).
pub fn cells_bytes(c: &Cells) -> Vec<u8> {
    c.v.iter().map(|&x| x as u8).collect()
}

// ---------------------------------------------------------------- the Text sink
//
// THE FORMAT IS KIN'S AND THE HOST ONLY APPENDS (SPEC 6.3.1). Three appends
// and nothing that formats: a code point, an ASCII literal, a decimal. An
// invalid code point (negative, past U+10FFFF, or a surrogate) appends U+FFFD
// on every target rather than throwing on some.

// @kin:link:form:text-cp: {:template "crate::port::text_cp({0}, {1})" :arity 2}
pub fn text_cp(s: &mut String, cp: i32) {
    s.push(char::from_u32(cp as u32).unwrap_or('\u{FFFD}'));
}

// @kin:link:form:text-ascii: {:template "crate::port::text_ascii({0}, {1})" :arity 2}
//
// The literal is checked printable ASCII where it is WRITTEN (`emit-ascii`,
// P0.3); the host trusts it.
pub fn text_ascii(s: &mut String, lit: &str) {
    s.push_str(lit);
}

// @kin:link:form:text-i32: {:template "crate::port::text_i32({0}, {1})" :arity 2}
//
// Decimal, invariant, no separator (unlike nome's `sink-i32`).
pub fn text_i32(s: &mut String, v: i32) {
    s.push_str(&v.to_string());
}

pub fn new_text() -> String {
    String::new()
}

// ---------------------------------------------------------------- limits
//
// EVERY BOUND IS CONFIGURABLE AND EVERY BREACH IS NAMED (SPEC 7.4). The
// KINDS are numbered here, in each host, and `bb hosts` cannot compare a
// table -- so the numbering is asserted by `kin/porttest.kin` on all four.
// Environment `EMON_LIMIT_<NAME>` is the low-level poke; an embedder's API
// installs bounds through its own layer.

// @kin:link:form:limit-of: {:template "crate::port::limit_of({0})" :arity 1}
pub fn limit_of(kind: i32) -> i32 {
    let (name, default) = limit_spec(kind);
    match std::env::var(format!("EMON_LIMIT_{}", name)) {
        Ok(v) => v.parse().unwrap_or(default),
        Err(_) => default,
    }
}

// @kin:link:form:limit-breach: {:template "crate::port::limit_breach({0}, {1})" :arity 2}
//
// A BREACH NEVER RETURNS, which is what lets kin write
// `(if (> n (limit-of K)) (limit-breach K n))` and carry on afterwards.
pub fn limit_breach(kind: i32, actual: i32) -> i32 {
    let (name, _) = limit_spec(kind);
    panic!(
        "the {} limit is {} and this input reached {}. Raise it with \
         EMON_LIMIT_{}, or find out why the input needs that much -- a limit \
         is a bound on emon, not a statement about the document.",
        name.to_lowercase().replace('_', " "),
        limit_of(kind), actual, name
    )
}

// A SETTING IS A CHOICE AND NOT A BOUND: a second pair of forms, not a
// further limit. `EMON_SETTING_<NAME>` is the poke.

// @kin:link:form:setting-of: {:template "crate::port::setting_of({0})" :arity 1}
pub fn setting_of(kind: i32) -> i32 {
    let (name, default) = setting_spec(kind);
    match std::env::var(format!("EMON_SETTING_{}", name)) {
        Ok(v) => v.parse().unwrap_or(default),
        Err(_) => default,
    }
}

// The kinds. Ten limits from SPEC 7.4's list (the wall clock is enforced by
// the host, not read by kin); the table grows with the phases that need a
// bound. Values are i32-representable.
fn limit_spec(kind: i32) -> (&'static str, i32) {
    match kind {
        0 => ("INPUT_BYTES", 1073741824),
        1 => ("STREAM_BYTES", 268435456),
        2 => ("TOTAL_BYTES", 1073741824),
        3 => ("OBJECTS", 10000000),
        4 => ("REF_DEPTH", 64),
        5 => ("OPERATORS", 50000000),
        6 => ("FORM_DEPTH", 32),
        7 => ("PATTERN_DEPTH", 8),
        8 => ("PAGES", 100000),
        9 => ("PIXELS", 268435456),
        10 => ("OCR_REQUESTS", 100000),
        _ => ("UNKNOWN", 0),
    }
}

// Settings are a placeholder table (kinds 0 and 1) until a phase needs more:
// OCR_MODE 0 auto / 1 always / 2 never (SPEC 17.1 is still open, so `auto`),
// and TASKS 0 serial / 1 parallel (D022: serial is the default).
fn setting_spec(kind: i32) -> (&'static str, i32) {
    match kind {
        0 => ("OCR_MODE", 0),
        1 => ("TASKS", 0),
        _ => ("UNKNOWN", 0),
    }
}
