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

// ---------------------------------------------------------------- the Buf
//
// A GROWABLE RUN OF BYTES, read and written (D117, SPEC 10.11): what `Cells`
// is for 32-bit integers, at one byte an element, for output that IS bytes (a
// PNG, an asset) and is too big to spend four bytes a byte on. `buf-push` and
// `buf-set` keep the LOW EIGHT BITS; `buf-at` answers 0..255 on every host
// (Java's `byte` is signed, so it masks). `buf-push` answers the NEW LENGTH
// (`cells-push` answers the index it wrote, which is one less). An index or a
// span outside the buffer, or a truncation past the length, panics or throws.
//
// `buf-blob` is a BORROW as a `Blob`, valid until the next write to the buffer:
// Rust and Go hand out the storage itself, Java and C# a copy of the bytes (a
// `byte[]` cannot be a prefix of a larger array). A caller must not rely on
// either: read it, use it, drop it. In Rust the borrow checker enforces that.
#[derive(Clone, Default)]
pub struct Buf(pub Vec<u8>);

impl Buf {
    pub fn new() -> Buf {
        Buf(Vec::new())
    }
}

// @kin:link:form:buf-len: {:template "crate::port::buf_len({0})" :arity 1}
pub fn buf_len(b: &Buf) -> i32 {
    b.0.len() as i32
}

// @kin:link:form:buf-at: {:template "crate::port::buf_at({0}, {1})" :arity 2}
pub fn buf_at(b: &Buf, i: i32) -> i32 {
    b.0[i as usize] as i32
}

// @kin:link:form:buf-push: {:template "crate::port::buf_push({0}, {1})" :arity 2}
pub fn buf_push(b: &mut Buf, v: i32) -> i32 {
    b.0.push(v as u8);
    b.0.len() as i32
}

// @kin:link:form:buf-set: {:template "crate::port::buf_set({0}, {1}, {2})" :arity 3}
pub fn buf_set(b: &mut Buf, i: i32, v: i32) {
    b.0[i as usize] = v as u8;
}

// @kin:link:form:buf-truncate: {:template "crate::port::buf_truncate({0}, {1})" :arity 2}
//
// Truncates TO a length, as `cells-drop` does.
pub fn buf_truncate(b: &mut Buf, n: i32) {
    if n < 0 || n as usize > b.0.len() {
        panic!("buf_truncate out of range");
    }
    b.0.truncate(n as usize);
}

// @kin:link:form:buf-append-blob: {:template "crate::port::buf_append_blob({0}, {1}, {2}, {3})" :arity 4}
//
// Appends blob[start, end).
pub fn buf_append_blob(b: &mut Buf, blob: &[u8], start: i32, end: i32) {
    b.0.extend_from_slice(&blob[start as usize..end as usize]);
}

// @kin:link:form:buf-blob: {:template "crate::port::buf_blob({0})" :arity 1}
pub fn buf_blob(b: &Buf) -> &[u8] {
    &b.0[..]
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

// ---------------------------------------------------------------- Crypto
//
// THE `Crypto` PORT (SPEC 7.2, DEVLOG P3.2, D006, D097): MD5, SHA-256/384/512,
// RC4 and AES-CBC, PURE FUNCTIONS OVER BYTES. The PDF standard security
// handler (key derivation, password checks, per-object keys) is kin
// (`emon.pdfcrypt`); only these primitives are the host's. A host must not
// substitute a "compatible" algorithm, and the four hosts agree on every
// output byte (kin/cryptotest.kin holds them to the published vectors).
//
// RUST'S STANDARD LIBRARY HAS NONE OF THEM, and emon's Rust host has no crate
// dependencies, so they are written out here, small and plain. The constant
// tables are DERIVED from their definitions by `tools/crypto-constants.clj`,
// not typed from memory. Go, Java and C# call their standard libraries (RC4
// is ten lines in each: .NET and modern JDKs disable or drop it).
//
// EVERY FORM takes the input as a span of a `Blob` (`data`, `off`, `len`) and
// APPENDS its output to a `Cells` (one byte per cell, 0..255), answering how
// many cells it appended. Nothing is cleared. An input span that is outside
// the blob panics like any other read past a blob's end. AN UNUSABLE KEY
// (an AES key that is not 16, 24 or 32 bytes) appends nothing and answers -1.

// @kin:link:form:crypto-md5: {:template "crate::port::crypto_md5({0}, {1}, {2}, {3})" :arity 4}
pub fn crypto_md5(data: &[u8], off: i32, len: i32, out: &mut Cells) -> i32 {
    let d = &data[off as usize..(off + len) as usize];
    push_bytes(out, &md5(d))
}

// @kin:link:form:crypto-sha256: {:template "crate::port::crypto_sha256({0}, {1}, {2}, {3})" :arity 4}
pub fn crypto_sha256(data: &[u8], off: i32, len: i32, out: &mut Cells) -> i32 {
    let d = &data[off as usize..(off + len) as usize];
    push_bytes(out, &sha256(d))
}

// @kin:link:form:crypto-sha384: {:template "crate::port::crypto_sha384({0}, {1}, {2}, {3})" :arity 4}
pub fn crypto_sha384(data: &[u8], off: i32, len: i32, out: &mut Cells) -> i32 {
    let d = &data[off as usize..(off + len) as usize];
    push_bytes(out, &sha512(d, true)[..48])
}

// @kin:link:form:crypto-sha512: {:template "crate::port::crypto_sha512({0}, {1}, {2}, {3})" :arity 4}
pub fn crypto_sha512(data: &[u8], off: i32, len: i32, out: &mut Cells) -> i32 {
    let d = &data[off as usize..(off + len) as usize];
    push_bytes(out, &sha512(d, false))
}

// @kin:link:form:crypto-rc4: {:template "crate::port::crypto_rc4({0}, {1}, {2}, {3}, {4}, {5}, {6})" :arity 7}
//
// RC4 over `data[doff, doff + dlen)` with the key `key[koff, koff + klen)`
// (1 to 256 bytes; an empty key answers -1). Encryption and decryption are
// the same operation.
pub fn crypto_rc4(key: &[u8], koff: i32, klen: i32, data: &[u8], doff: i32, dlen: i32, out: &mut Cells) -> i32 {
    if klen < 1 || klen > 256 {
        return -1;
    }
    let k = &key[koff as usize..(koff + klen) as usize];
    let d = &data[doff as usize..(doff + dlen) as usize];
    let mut s = [0u8; 256];
    for i in 0..256 {
        s[i] = i as u8;
    }
    let mut j: usize = 0;
    for i in 0..256 {
        j = (j + s[i] as usize + k[i % k.len()] as usize) & 255;
        s.swap(i, j);
    }
    let (mut i, mut j) = (0usize, 0usize);
    for &b in d {
        i = (i + 1) & 255;
        j = (j + s[i] as usize) & 255;
        s.swap(i, j);
        let ks = s[(s[i] as usize + s[j] as usize) & 255];
        out.v.push((b ^ ks) as i32);
    }
    dlen
}

// @kin:link:form:crypto-aes-decrypt: {:template "crate::port::crypto_aes_decrypt({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7})" :arity 8}
//
// AES-CBC decryption (key of 16, 24 or 32 bytes). The first 16 bytes of
// `data[doff, doff + dlen)` are the IV and the rest is the ciphertext; a
// ragged tail shorter than a block is dropped. With `pad` 1 the PKCS#5
// padding is stripped (the last byte n, 1 to 16, removes n bytes; any other
// last byte removes none: what readers do with a damaged padding is keep the
// data). Appends the plaintext; answers how many cells it appended.
pub fn crypto_aes_decrypt(key: &[u8], koff: i32, klen: i32, data: &[u8], doff: i32, dlen: i32, pad: i32, out: &mut Cells) -> i32 {
    if !(klen == 16 || klen == 24 || klen == 32) {
        return -1;
    }
    let k = &key[koff as usize..(koff + klen) as usize];
    let d = &data[doff as usize..(doff + dlen) as usize];
    if d.len() < 32 {
        return 0;
    }
    let rk = aes_expand(k);
    let nblocks = (d.len() - 16) / 16;
    let mut prev = [0u8; 16];
    prev.copy_from_slice(&d[..16]);
    let mut plain: Vec<u8> = Vec::with_capacity(nblocks * 16);
    for b in 0..nblocks {
        let mut blk = [0u8; 16];
        blk.copy_from_slice(&d[16 + b * 16..32 + b * 16]);
        let ct = blk;
        aes_decrypt_block(&rk, &mut blk);
        for i in 0..16 {
            blk[i] ^= prev[i];
        }
        plain.extend_from_slice(&blk);
        prev = ct;
    }
    if pad == 1 && !plain.is_empty() {
        let n = *plain.last().unwrap() as usize;
        if n >= 1 && n <= 16 && n <= plain.len() {
            plain.truncate(plain.len() - n);
        }
    }
    push_bytes(out, &plain)
}

// @kin:link:form:crypto-aes-encrypt: {:template "crate::port::crypto_aes_encrypt({0}, {1}, {2}, {3}, {4}, {5}, {6})" :arity 7}
//
// AES-CBC encryption WITHOUT padding: the first 16 bytes of the data are the
// IV, the rest is the plaintext, of whole blocks (a ragged tail is dropped).
// Appends the ciphertext only (no IV). PDF's R6 hash needs it (ISO 32000-2
// 7.6.4.3.4).
pub fn crypto_aes_encrypt(key: &[u8], koff: i32, klen: i32, data: &[u8], doff: i32, dlen: i32, out: &mut Cells) -> i32 {
    if !(klen == 16 || klen == 24 || klen == 32) {
        return -1;
    }
    let k = &key[koff as usize..(koff + klen) as usize];
    let d = &data[doff as usize..(doff + dlen) as usize];
    if d.len() < 16 {
        return 0;
    }
    let rk = aes_expand(k);
    let nblocks = (d.len() - 16) / 16;
    let mut prev = [0u8; 16];
    prev.copy_from_slice(&d[..16]);
    let mut ct: Vec<u8> = Vec::with_capacity(nblocks * 16);
    for b in 0..nblocks {
        let mut blk = [0u8; 16];
        blk.copy_from_slice(&d[16 + b * 16..32 + b * 16]);
        for i in 0..16 {
            blk[i] ^= prev[i];
        }
        aes_encrypt_block(&rk, &mut blk);
        ct.extend_from_slice(&blk);
        prev = blk;
    }
    push_bytes(out, &ct)
}

fn push_bytes(out: &mut Cells, b: &[u8]) -> i32 {
    for &x in b {
        out.v.push(x as i32);
    }
    b.len() as i32
}

// ---- MD5 (RFC 1321)

// MD5 K: floor(2^32 * |sin(i + 1)|)
const MD5_K: [u32; 64] = [
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee,
    0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
    0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
    0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
    0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa,
    0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed,
    0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
    0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
    0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
    0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05,
    0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039,
    0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
    0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
    0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
];


const MD5_S: [u32; 64] = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
];

fn md5(data: &[u8]) -> [u8; 16] {
    let mut a0: u32 = 0x67452301;
    let mut b0: u32 = 0xefcdab89;
    let mut c0: u32 = 0x98badcfe;
    let mut d0: u32 = 0x10325476;
    let mut msg = data.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&((data.len() as u64).wrapping_mul(8)).to_le_bytes());
    for chunk in msg.chunks(64) {
        let mut m = [0u32; 16];
        for i in 0..16 {
            m[i] = u32::from_le_bytes([chunk[4 * i], chunk[4 * i + 1], chunk[4 * i + 2], chunk[4 * i + 3]]);
        }
        let (mut a, mut b, mut c, mut d) = (a0, b0, c0, d0);
        for i in 0..64 {
            let (mut f, g);
            if i < 16 {
                f = (b & c) | (!b & d);
                g = i;
            } else if i < 32 {
                f = (d & b) | (!d & c);
                g = (5 * i + 1) % 16;
            } else if i < 48 {
                f = b ^ c ^ d;
                g = (3 * i + 5) % 16;
            } else {
                f = c ^ (b | !d);
                g = (7 * i) % 16;
            }
            f = f.wrapping_add(a).wrapping_add(MD5_K[i]).wrapping_add(m[g]);
            a = d;
            d = c;
            c = b;
            b = b.wrapping_add(f.rotate_left(MD5_S[i]));
        }
        a0 = a0.wrapping_add(a);
        b0 = b0.wrapping_add(b);
        c0 = c0.wrapping_add(c);
        d0 = d0.wrapping_add(d);
    }
    let mut out = [0u8; 16];
    out[0..4].copy_from_slice(&a0.to_le_bytes());
    out[4..8].copy_from_slice(&b0.to_le_bytes());
    out[8..12].copy_from_slice(&c0.to_le_bytes());
    out[12..16].copy_from_slice(&d0.to_le_bytes());
    out
}

// ---- SHA-2 (FIPS 180-4)

// SHA-256 K: cube roots of the first 64 primes, 32 bits
const SHA256_K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
    0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
    0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
    0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];
// SHA-256 H0: square roots of the first 8 primes, 32 bits
const SHA256_H: [u32; 8] = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
];
// SHA-512 K: cube roots of the first 80 primes, 64 bits
const SHA512_K: [u64; 80] = [
    0x428a2f98d728ae22, 0x7137449123ef65cd, 0xb5c0fbcfec4d3b2f, 0xe9b5dba58189dbbc,
    0x3956c25bf348b538, 0x59f111f1b605d019, 0x923f82a4af194f9b, 0xab1c5ed5da6d8118,
    0xd807aa98a3030242, 0x12835b0145706fbe, 0x243185be4ee4b28c, 0x550c7dc3d5ffb4e2,
    0x72be5d74f27b896f, 0x80deb1fe3b1696b1, 0x9bdc06a725c71235, 0xc19bf174cf692694,
    0xe49b69c19ef14ad2, 0xefbe4786384f25e3, 0x0fc19dc68b8cd5b5, 0x240ca1cc77ac9c65,
    0x2de92c6f592b0275, 0x4a7484aa6ea6e483, 0x5cb0a9dcbd41fbd4, 0x76f988da831153b5,
    0x983e5152ee66dfab, 0xa831c66d2db43210, 0xb00327c898fb213f, 0xbf597fc7beef0ee4,
    0xc6e00bf33da88fc2, 0xd5a79147930aa725, 0x06ca6351e003826f, 0x142929670a0e6e70,
    0x27b70a8546d22ffc, 0x2e1b21385c26c926, 0x4d2c6dfc5ac42aed, 0x53380d139d95b3df,
    0x650a73548baf63de, 0x766a0abb3c77b2a8, 0x81c2c92e47edaee6, 0x92722c851482353b,
    0xa2bfe8a14cf10364, 0xa81a664bbc423001, 0xc24b8b70d0f89791, 0xc76c51a30654be30,
    0xd192e819d6ef5218, 0xd69906245565a910, 0xf40e35855771202a, 0x106aa07032bbd1b8,
    0x19a4c116b8d2d0c8, 0x1e376c085141ab53, 0x2748774cdf8eeb99, 0x34b0bcb5e19b48a8,
    0x391c0cb3c5c95a63, 0x4ed8aa4ae3418acb, 0x5b9cca4f7763e373, 0x682e6ff3d6b2b8a3,
    0x748f82ee5defb2fc, 0x78a5636f43172f60, 0x84c87814a1f0ab72, 0x8cc702081a6439ec,
    0x90befffa23631e28, 0xa4506cebde82bde9, 0xbef9a3f7b2c67915, 0xc67178f2e372532b,
    0xca273eceea26619c, 0xd186b8c721c0c207, 0xeada7dd6cde0eb1e, 0xf57d4f7fee6ed178,
    0x06f067aa72176fba, 0x0a637dc5a2c898a6, 0x113f9804bef90dae, 0x1b710b35131c471b,
    0x28db77f523047d84, 0x32caab7b40c72493, 0x3c9ebe0a15c9bebc, 0x431d67c49c100d4c,
    0x4cc5d4becb3e42b6, 0x597f299cfc657e2a, 0x5fcb6fab3ad6faec, 0x6c44198c4a475817,
];
// SHA-512 H0: square roots of the first 8 primes, 64 bits
const SHA512_H: [u64; 8] = [
    0x6a09e667f3bcc908, 0xbb67ae8584caa73b, 0x3c6ef372fe94f82b, 0xa54ff53a5f1d36f1,
    0x510e527fade682d1, 0x9b05688c2b3e6c1f, 0x1f83d9abfb41bd6b, 0x5be0cd19137e2179,
];
// SHA-384 H0: square roots of the 9th to 16th primes, 64 bits
const SHA384_H: [u64; 8] = [
    0xcbbb9d5dc1059ed8, 0x629a292a367cd507, 0x9159015a3070dd17, 0x152fecd8f70e5939,
    0x67332667ffc00b31, 0x8eb44a8768581511, 0xdb0c2e0d64f98fa7, 0x47b5481dbefa4fa4,
];


fn sha256(data: &[u8]) -> [u8; 32] {
    let mut h = SHA256_H;
    let mut msg = data.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&((data.len() as u64).wrapping_mul(8)).to_be_bytes());
    for chunk in msg.chunks(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([chunk[4 * i], chunk[4 * i + 1], chunk[4 * i + 2], chunk[4 * i + 3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let mut v = h;
        for i in 0..64 {
            let s1 = v[4].rotate_right(6) ^ v[4].rotate_right(11) ^ v[4].rotate_right(25);
            let ch = (v[4] & v[5]) ^ (!v[4] & v[6]);
            let t1 = v[7].wrapping_add(s1).wrapping_add(ch).wrapping_add(SHA256_K[i]).wrapping_add(w[i]);
            let s0 = v[0].rotate_right(2) ^ v[0].rotate_right(13) ^ v[0].rotate_right(22);
            let maj = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
            let t2 = s0.wrapping_add(maj);
            v[7] = v[6];
            v[6] = v[5];
            v[5] = v[4];
            v[4] = v[3].wrapping_add(t1);
            v[3] = v[2];
            v[2] = v[1];
            v[1] = v[0];
            v[0] = t1.wrapping_add(t2);
        }
        for i in 0..8 {
            h[i] = h[i].wrapping_add(v[i]);
        }
    }
    let mut out = [0u8; 32];
    for i in 0..8 {
        out[4 * i..4 * i + 4].copy_from_slice(&h[i].to_be_bytes());
    }
    out
}

fn sha512(data: &[u8], is384: bool) -> [u8; 64] {
    let mut h = if is384 { SHA384_H } else { SHA512_H };
    let mut msg = data.to_vec();
    msg.push(0x80);
    while msg.len() % 128 != 112 {
        msg.push(0);
    }
    msg.extend_from_slice(&((data.len() as u128).wrapping_mul(8)).to_be_bytes());
    for chunk in msg.chunks(128) {
        let mut w = [0u64; 80];
        for i in 0..16 {
            let mut b = [0u8; 8];
            b.copy_from_slice(&chunk[8 * i..8 * i + 8]);
            w[i] = u64::from_be_bytes(b);
        }
        for i in 16..80 {
            let s0 = w[i - 15].rotate_right(1) ^ w[i - 15].rotate_right(8) ^ (w[i - 15] >> 7);
            let s1 = w[i - 2].rotate_right(19) ^ w[i - 2].rotate_right(61) ^ (w[i - 2] >> 6);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let mut v = h;
        for i in 0..80 {
            let s1 = v[4].rotate_right(14) ^ v[4].rotate_right(18) ^ v[4].rotate_right(41);
            let ch = (v[4] & v[5]) ^ (!v[4] & v[6]);
            let t1 = v[7].wrapping_add(s1).wrapping_add(ch).wrapping_add(SHA512_K[i]).wrapping_add(w[i]);
            let s0 = v[0].rotate_right(28) ^ v[0].rotate_right(34) ^ v[0].rotate_right(39);
            let maj = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
            let t2 = s0.wrapping_add(maj);
            v[7] = v[6];
            v[6] = v[5];
            v[5] = v[4];
            v[4] = v[3].wrapping_add(t1);
            v[3] = v[2];
            v[2] = v[1];
            v[1] = v[0];
            v[0] = t1.wrapping_add(t2);
        }
        for i in 0..8 {
            h[i] = h[i].wrapping_add(v[i]);
        }
    }
    let mut out = [0u8; 64];
    for i in 0..8 {
        out[8 * i..8 * i + 8].copy_from_slice(&h[i].to_be_bytes());
    }
    out
}

// ---- AES (FIPS 197), the S-box derived rather than typed

struct AesBoxes {
    sbox: [u8; 256],
    inv: [u8; 256],
}

fn aes_boxes() -> &'static AesBoxes {
    static T: std::sync::OnceLock<AesBoxes> = std::sync::OnceLock::new();
    T.get_or_init(|| {
        let mut sbox = [0u8; 256];
        let mut p: u8 = 1;
        let mut q: u8 = 1;
        loop {
            // p * 3 in GF(2^8), and q / 3 (multiplication by 0xf6)
            p = p ^ (p << 1) ^ (if p & 0x80 != 0 { 0x1b } else { 0 });
            q ^= q << 1;
            q ^= q << 2;
            q ^= q << 4;
            if q & 0x80 != 0 {
                q ^= 0x09;
            }
            let x = q ^ q.rotate_left(1) ^ q.rotate_left(2) ^ q.rotate_left(3) ^ q.rotate_left(4);
            sbox[p as usize] = x ^ 0x63;
            if p == 1 {
                break;
            }
        }
        sbox[0] = 0x63;
        let mut inv = [0u8; 256];
        for i in 0..256 {
            inv[sbox[i] as usize] = i as u8;
        }
        AesBoxes { sbox, inv }
    })
}

fn xtime(a: u8) -> u8 {
    (a << 1) ^ (if a & 0x80 != 0 { 0x1b } else { 0 })
}

fn gmul(mut a: u8, mut b: u8) -> u8 {
    let mut r = 0u8;
    while b != 0 {
        if b & 1 != 0 {
            r ^= a;
        }
        a = xtime(a);
        b >>= 1;
    }
    r
}

// The expanded key: (rounds + 1) * 16 bytes, the round count in the first slot.
fn aes_expand(key: &[u8]) -> Vec<u8> {
    let bx = aes_boxes();
    let nk = key.len() / 4;
    let nr = nk + 6;
    let total = 4 * (nr + 1);
    let mut w: Vec<[u8; 4]> = Vec::with_capacity(total);
    for i in 0..nk {
        w.push([key[4 * i], key[4 * i + 1], key[4 * i + 2], key[4 * i + 3]]);
    }
    let mut rcon: u8 = 1;
    for i in nk..total {
        let mut t = w[i - 1];
        if i % nk == 0 {
            t = [bx.sbox[t[1] as usize] ^ rcon, bx.sbox[t[2] as usize], bx.sbox[t[3] as usize], bx.sbox[t[0] as usize]];
            rcon = xtime(rcon);
        } else if nk > 6 && i % nk == 4 {
            t = [bx.sbox[t[0] as usize], bx.sbox[t[1] as usize], bx.sbox[t[2] as usize], bx.sbox[t[3] as usize]];
        }
        let p = w[i - nk];
        w.push([p[0] ^ t[0], p[1] ^ t[1], p[2] ^ t[2], p[3] ^ t[3]]);
    }
    let mut out = Vec::with_capacity(total * 4 + 1);
    out.push(nr as u8);
    for x in w {
        out.extend_from_slice(&x);
    }
    out
}

fn aes_add_round_key(st: &mut [u8; 16], rk: &[u8], round: usize) {
    for i in 0..16 {
        st[i] ^= rk[1 + round * 16 + i];
    }
}

fn aes_encrypt_block(rk: &[u8], st: &mut [u8; 16]) {
    let bx = aes_boxes();
    let nr = rk[0] as usize;
    aes_add_round_key(st, rk, 0);
    for round in 1..=nr {
        for i in 0..16 {
            st[i] = bx.sbox[st[i] as usize];
        }
        let o = *st;
        for c in 0..4 {
            for r in 0..4 {
                st[4 * c + r] = o[4 * ((c + r) % 4) + r];
            }
        }
        if round != nr {
            for c in 0..4 {
                let a = [st[4 * c], st[4 * c + 1], st[4 * c + 2], st[4 * c + 3]];
                st[4 * c] = xtime(a[0]) ^ (xtime(a[1]) ^ a[1]) ^ a[2] ^ a[3];
                st[4 * c + 1] = a[0] ^ xtime(a[1]) ^ (xtime(a[2]) ^ a[2]) ^ a[3];
                st[4 * c + 2] = a[0] ^ a[1] ^ xtime(a[2]) ^ (xtime(a[3]) ^ a[3]);
                st[4 * c + 3] = (xtime(a[0]) ^ a[0]) ^ a[1] ^ a[2] ^ xtime(a[3]);
            }
        }
        aes_add_round_key(st, rk, round);
    }
}

fn aes_decrypt_block(rk: &[u8], st: &mut [u8; 16]) {
    let bx = aes_boxes();
    let nr = rk[0] as usize;
    aes_add_round_key(st, rk, nr);
    for round in (0..nr).rev() {
        let o = *st;
        for c in 0..4 {
            for r in 0..4 {
                st[4 * ((c + r) % 4) + r] = o[4 * c + r];
            }
        }
        for i in 0..16 {
            st[i] = bx.inv[st[i] as usize];
        }
        aes_add_round_key(st, rk, round);
        if round != 0 {
            for c in 0..4 {
                let a = [st[4 * c], st[4 * c + 1], st[4 * c + 2], st[4 * c + 3]];
                st[4 * c] = gmul(a[0], 14) ^ gmul(a[1], 11) ^ gmul(a[2], 13) ^ gmul(a[3], 9);
                st[4 * c + 1] = gmul(a[0], 9) ^ gmul(a[1], 14) ^ gmul(a[2], 11) ^ gmul(a[3], 13);
                st[4 * c + 2] = gmul(a[0], 13) ^ gmul(a[1], 9) ^ gmul(a[2], 14) ^ gmul(a[3], 11);
                st[4 * c + 3] = gmul(a[0], 11) ^ gmul(a[1], 13) ^ gmul(a[2], 9) ^ gmul(a[3], 14);
            }
        }
    }
}
