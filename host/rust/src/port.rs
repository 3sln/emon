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

// @kin:link:form:cells-grow: {:template "crate::port::cells_grow({0}, {1}, {2})" :arity 3}
//
// Appends `n` copies of `v` (what a loop of `cells-push` does, in one call).
pub fn cells_grow(c: &mut Cells, n: i32, v: i32) {
    let len = c.v.len() + n as usize;
    c.v.resize(len, v);
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

// ---------------------------------------------------------------- the Bitmap
//
// AN OPAQUE 8-BIT PIXEL BUFFER (SPEC 7.2, P2.7): `channels` bytes per pixel
// (1 gray, 3 RGB), row-major, top-left origin, `stride` bytes per row.
// `bitmap_u8(b, x, y, c)` reads, `bitmap_set_u8(b, x, y, c, v)` writes. A
// write takes a SHARED borrow here (the bytes are `Cell`s), so one bitmap can
// be handed to two parameters of a call, which `Cells`' `&mut` cannot.
//
// THE CHECK IS ON THE BYTE INDEX `y*stride + x*channels + c`, not on each
// coordinate: an `x` past the row lands in the next row, as in every C
// raster, and only an index past the BUFFER panics (Java and C# throw). A
// caller that needs per-coordinate checks makes them. The P2.7 spike priced
// the per-pixel call through this port (DEVLOG P2.7).
#[derive(Debug, Clone, PartialEq)]
pub struct Bitmap {
    w: i32,
    h: i32,
    stride: i32,
    ch: i32,
    d: Vec<std::cell::Cell<u8>>,
}

impl Bitmap {
    pub fn new(w: i32, h: i32, ch: i32) -> Bitmap {
        let n = (w as usize) * (h as usize) * (ch as usize);
        Bitmap { w, h, stride: w * ch, ch, d: vec![std::cell::Cell::new(0); n] }
    }
    // The host's way in: decoded pixels, tightly packed (stride = w * ch).
    // The host's way out: the pixels tightly packed, row-major (encoders, transcripts).
    pub fn to_bytes(&self) -> Vec<u8> {
        let (w, h, ch, st) = (self.w as usize, self.h as usize, self.ch as usize, self.stride as usize);
        let mut out = Vec::with_capacity(w * h * ch);
        for y in 0..h {
            out.extend(self.d[y * st..y * st + w * ch].iter().map(|c| c.get()));
        }
        out
    }
    pub fn width(&self) -> i32 { self.w }
    pub fn height(&self) -> i32 { self.h }
    pub fn channels(&self) -> i32 { self.ch }
    pub fn from_bytes(w: i32, h: i32, ch: i32, bytes: &[u8]) -> Bitmap {
        assert_eq!(bytes.len(), (w as usize) * (h as usize) * (ch as usize));
        Bitmap { w, h, stride: w * ch, ch, d: bytes.iter().map(|&b| std::cell::Cell::new(b)).collect() }
    }
}

// @kin:link:form:bitmap-new: {:template "(&crate::port::bitmap_new({0}, {1}, {2}))" :arity 3}
//
// Zero-filled. In Rust the template borrows a temporary: bind it with `let`
// (extended) or nest it in the call that uses it, like `src-of-blob`.
pub fn bitmap_new(w: i32, h: i32, ch: i32) -> Bitmap {
    Bitmap::new(w, h, ch)
}

// @kin:link:form:bitmap-w: {:template "crate::port::bitmap_w({0})" :arity 1}
pub fn bitmap_w(b: &Bitmap) -> i32 {
    b.w
}

// @kin:link:form:bitmap-h: {:template "crate::port::bitmap_h({0})" :arity 1}
pub fn bitmap_h(b: &Bitmap) -> i32 {
    b.h
}

// @kin:link:form:bitmap-stride: {:template "crate::port::bitmap_stride({0})" :arity 1}
pub fn bitmap_stride(b: &Bitmap) -> i32 {
    b.stride
}

// @kin:link:form:bitmap-channels: {:template "crate::port::bitmap_channels({0})" :arity 1}
pub fn bitmap_channels(b: &Bitmap) -> i32 {
    b.ch
}

// @kin:link:form:bitmap-u8: {:template "crate::port::bitmap_u8({0}, {1}, {2}, {3})" :arity 4}
#[inline]
pub fn bitmap_u8(b: &Bitmap, x: i32, y: i32, c: i32) -> i32 {
    b.d[(y * b.stride + x * b.ch + c) as usize].get() as i32
}

// @kin:link:form:bitmap-set-u8: {:template "crate::port::bitmap_set_u8({0}, {1}, {2}, {3}, {4})" :arity 5}
#[inline]
pub fn bitmap_set_u8(b: &Bitmap, x: i32, y: i32, c: i32, v: i32) {
    b.d[(y * b.stride + x * b.ch + c) as usize].set(v as u8);
}

// ---- bulk forms (P2.7): the per-pixel loops the spike priced, done in the host.
//
// @kin:link:form:bitmap-histogram: {:template "crate::port::bitmap_histogram({0}, {1}, {2})" :arity 3}
//
// ADDS the count of each channel-0 value to `hist[off + v]`, v in 0..255; the
// 256 cells must exist. One call per page instead of one per pixel.
pub fn bitmap_histogram(b: &Bitmap, hist: &mut Cells, off: i32) {
    let o = off as usize;
    let bins = &mut hist.v[o..o + 256];
    let (w, ch, stride) = (b.w as usize, b.ch as usize, b.stride as usize);
    // FOUR PARTIAL HISTOGRAMS: one would make every increment wait for the
    // previous one's store when neighbouring pixels share a value (a page is
    // mostly white). The sums are the same.
    let mut part = [[0i32; 256]; 4];
    for y in 0..b.h as usize {
        let row = &b.d[y * stride..y * stride + w * ch];
        let mut it = row.chunks_exact(4 * ch);
        for q in &mut it {
            for k in 0..4 {
                part[k][q[k * ch].get() as usize] += 1;
            }
        }
        for px in it.remainder().chunks_exact(ch) {
            part[0][px[0].get() as usize] += 1;
        }
    }
    for v in 0..256 {
        bins[v] += part[0][v] + part[1][v] + part[2][v] + part[3][v];
    }
}

// @kin:link:form:bitmap-threshold-into: {:template "crate::port::bitmap_threshold_into({0}, {1}, {2})" :arity 3}
//
// `m`'s channel 0 := 255 where `g`'s channel 0 <= t, else 0, for every pixel;
// `g` and `m` must have the same width and height (else a panic, like an
// index). Answers the number of pixels set.
pub fn bitmap_threshold_into(g: &Bitmap, t: i32, m: &Bitmap) -> i32 {
    assert!(g.w == m.w && g.h == m.h);
    let mut ink = 0;
    let w = g.w as usize;
    for y in 0..g.h as usize {
        if g.ch == 1 && m.ch == 1 {
            // The common case, row slices: no index arithmetic per pixel.
            let gr = &g.d[y * g.stride as usize..][..w];
            let mr = &m.d[y * m.stride as usize..][..w];
            for (p, q) in gr.iter().zip(mr) {
                let on = (p.get() as i32) <= t;
                q.set(if on { 255 } else { 0 });
                ink += on as i32;
            }
        } else {
            for x in 0..w {
                let v = g.d[y * g.stride as usize + x * g.ch as usize].get() as i32;
                let o = y * m.stride as usize + x * m.ch as usize;
                let on = v <= t;
                m.d[o].set(if on { 255 } else { 0 });
                ink += on as i32;
            }
        }
    }
    ink
}

// @kin:link:form:bitmap-next-nonzero: {:template "crate::port::bitmap_next_nonzero({0}, {1}, {2})" :arity 3}
//
// The first x' >= x on row `y` whose channel 0 is nonzero, or the width when
// there is none (x may equal the width). What a labeller wants to skip white.
pub fn bitmap_next_nonzero(b: &Bitmap, x: i32, y: i32) -> i32 {
    let (w, ch) = (b.w as usize, b.ch as usize);
    let row = &b.d[y as usize * b.stride as usize..][..w * ch];
    let mut i = x as usize;
    while i < w {
        if row[i * ch].get() != 0 {
            return i as i32;
        }
        i += 1;
    }
    b.w
}

// @kin:link:form:bitmap-integral: {:template "crate::port::bitmap_integral({0}, {1}, {2})" :arity 3}
//
// Sets `sum` and `sq` to the INTEGRAL IMAGES of channel 0 and of its square:
// exactly (w+1)*(h+1) cells each (row-major, a zero row and a zero column
// first), holding the LOW 32 BITS of the running sums (wrapping). A window sum
// is the four-corner difference, taken in 64 bits and truncated; it is exact
// whenever the true sum is below 2^31.
pub fn bitmap_integral(b: &Bitmap, sum: &mut Cells, sq: &mut Cells) {
    let (w, h, ch, stride) = (b.w as usize, b.h as usize, b.ch as usize, b.stride as usize);
    let w1 = w + 1;
    let n = w1 * (h + 1);
    // Only row 0 and column 0 need zeroing: every other cell is written below.
    sum.v.resize(n, 0);
    sum.v.truncate(n);
    sq.v.resize(n, 0);
    sq.v.truncate(n);
    sum.v[..w1].fill(0);
    sq.v[..w1].fill(0);
    for y in 0..h {
        let row = &b.d[y * stride..y * stride + w * ch];
        let (mut rs, mut rq) = (0i32, 0i32);
        let (above_s, below_s) = sum.v.split_at_mut((y + 1) * w1);
        let (above_q, below_q) = sq.v.split_at_mut((y + 1) * w1);
        let (ps, pq) = (&above_s[y * w1..], &above_q[y * w1..]);
        below_s[0] = 0;
        below_q[0] = 0;
        for x in 0..w {
            let v = row[x * ch].get() as i32;
            rs += v;
            rq += v * v;
            below_s[x + 1] = ps[x + 1].wrapping_add(rs);
            below_q[x + 1] = pq[x + 1].wrapping_add(rq);
        }
    }
}

// ---------------------------------------------------------------- the Src
//
// AN INPUT BYTE SOURCE AS KIN SEES IT (SPEC 7.2 and 13.1, D058 revised). A
// `Src` is a per-unit VIEW over a backing: the host's per-input chunk cache,
// or (`src_of_blob`, `src_of_cells`) plain bytes in memory. It answers the
// same questions as a `Blob` and adds one: a read may MISS.
//
// `src_u8` of a byte whose chunk is resident answers it. A backing that
// cannot answer yet (an async source: a browser `Blob`, a URL) records the
// missed chunk itself, answers "not resident", and the view then sets its
// MISSED FLAG (sticky, for this view only) and answers 0. kin checks
// `src_missed` at its checkpoints and stops; the host fetches what the
// backing recorded and re-runs the unit on a FRESH VIEW (`Src::view`) of the
// same backing. A sync backing fills a chunk inline and never misses. The
// backing's cache only grows between attempts, never during one, which is
// what makes a miss-free attempt identical to the in-memory run.
//
// `src_slice` is a SNAPSHOT of a span as a `Blob` (like `cells_bytes`, so in
// Rust the template borrows a temporary: nest it in the call that uses it). A
// span with a non-resident part sets the missed flag and its bytes there are
// 0. `src_of_blob` and `src_of_cells` COPY, so the source outlives its
// argument. An index or span outside 0..len panics, as a `Blob`'s does.
pub trait SrcBacking: Send + Sync {
    fn len(&self) -> i32;
    // The byte 0..255, or -1 when it is not resident (the backing records the miss).
    fn at(&self, i: i32) -> i32;
    // Fill buf with the bytes from s; false when any are not resident (recorded).
    fn span(&self, s: i32, buf: &mut [u8]) -> bool;
    // A prefetch hint: bytes [s, e) will be wanted. May be ignored.
    fn hint(&self, s: i32, e: i32);
    // For the test sources' control surface (`testport`): the concrete backing.
    fn as_any(&self) -> &dyn std::any::Any;
}

struct MemBacking {
    v: Vec<u8>,
}

impl SrcBacking for MemBacking {
    fn len(&self) -> i32 {
        self.v.len() as i32
    }
    fn at(&self, i: i32) -> i32 {
        self.v[i as usize] as i32
    }
    fn span(&self, s: i32, buf: &mut [u8]) -> bool {
        let s = s as usize;
        buf.copy_from_slice(&self.v[s..s + buf.len()]);
        true
    }
    fn hint(&self, _s: i32, _e: i32) {}
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

pub struct Src {
    b: std::sync::Arc<dyn SrcBacking>,
    missed: std::sync::atomic::AtomicBool,
}

impl Src {
    pub fn new(b: std::sync::Arc<dyn SrcBacking>) -> Src {
        Src { b, missed: std::sync::atomic::AtomicBool::new(false) }
    }
    pub fn of_bytes(v: Vec<u8>) -> Src {
        Src::new(std::sync::Arc::new(MemBacking { v }))
    }
    // A fresh view of the same backing: the missed flag is clear.
    pub fn view(&self) -> Src {
        Src::new(self.b.clone())
    }
    pub fn missed(&self) -> bool {
        self.missed.load(std::sync::atomic::Ordering::Relaxed)
    }
    pub fn backing(&self) -> &dyn SrcBacking {
        &*self.b
    }
}

// @kin:link:form:src-len: {:template "crate::port::src_len({0})" :arity 1}
pub fn src_len(s: &Src) -> i32 {
    s.b.len()
}

// @kin:link:form:src-u8: {:template "crate::port::src_u8({0}, {1})" :arity 2}
pub fn src_u8(s: &Src, i: i32) -> i32 {
    if i < 0 || i >= s.b.len() {
        panic!("source index {} is outside 0..{}", i, s.b.len());
    }
    let v = s.b.at(i);
    if v < 0 {
        s.missed.store(true, std::sync::atomic::Ordering::Relaxed);
        return 0;
    }
    v
}

// @kin:link:form:src-missed: {:template "crate::port::src_missed({0})" :arity 1}
pub fn src_missed(s: &Src) -> bool {
    s.missed()
}

// @kin:link:form:src-slice: {:template "(&crate::port::src_slice({0}, {1}, {2})[..])" :arity 3}
pub fn src_slice(s: &Src, a: i32, b: i32) -> Vec<u8> {
    if a < 0 || b < a || b > s.b.len() {
        panic!("source span {}..{} is outside 0..{}", a, b, s.b.len());
    }
    let mut buf = vec![0u8; (b - a) as usize];
    if !s.b.span(a, &mut buf) {
        s.missed.store(true, std::sync::atomic::Ordering::Relaxed);
    }
    buf
}

// @kin:link:form:src-of-cells: {:template "(&crate::port::src_of_cells({0}))" :arity 1}
pub fn src_of_cells(c: &Cells) -> Src {
    Src::of_bytes(c.v.iter().map(|&x| x as u8).collect())
}

// @kin:link:form:src-of-blob: {:template "(&crate::port::src_of_blob({0}))" :arity 1}
pub fn src_of_blob(b: &[u8]) -> Src {
    Src::of_bytes(b.to_vec())
}

// @kin:link:form:src-hint: {:template "crate::port::src_hint({0}, {1}, {2})" :arity 3}
pub fn src_hint(s: &Src, a: i32, b: i32) {
    s.b.hint(a, b);
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
