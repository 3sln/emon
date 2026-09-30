// The Rust half of emon's TEST support (DEVLOG P0.5, D044). Only `emon.test`'s
// forms reach it; no production kin source may. It sits beside `port.rs`
// rather than in it so the library's port surface stays exactly the ports.
//
// ASSERTIONS PANIC, and `cargo test` reports the panic against the law that
// raised it. `EMON_TEST_INVERT=1` flips every assertion's verdict: it is how
// `bb unit red` proves that a broken law FAILS on every target (P0.5's
// "fails when the law is broken") without anyone editing a source.

use crate::port::{cells_at, cells_len, Cells};

fn inverted() -> bool {
    std::env::var("EMON_TEST_INVERT").map(|v| v == "1").unwrap_or(false)
}

fn verdict(ok: bool, what: String) {
    if ok == inverted() {
        panic!("{}", what);
    }
}

pub fn assert_eq(got: i32, want: i32, name: &str) {
    verdict(got == want, format!("{}: got {}, want {}", name, got, want));
}

pub fn assert_eq_at(got: i32, want: i32, name: &str, row: i32) {
    verdict(got == want, format!("{} [row {}]: got {}, want {}", name, row, got, want));
}

pub fn assert_eq64(got: i64, want: i64, name: &str) {
    verdict(got == want, format!("{}: got {}, want {}", name, got, want));
}

pub fn assert_that(ok: bool, name: &str) {
    verdict(ok, format!("{}: not true", name));
}

pub fn assert_that_at(ok: bool, name: &str, row: i32) {
    verdict(ok, format!("{} [row {}]: not true", name, row));
}

pub fn assert_text(t: &String, want: &str, name: &str) {
    verdict(t == want, format!("{}: got `{}`, want `{}`", name, t, want));
}

pub fn assert_text_span(t: &String, b: &[u8], from: i32, to: i32, name: &str, row: i32) {
    let want = &b[from as usize..to as usize];
    verdict(
        t.as_bytes() == want,
        format!("{} [row {}]: got `{}`, want `{}`", name, row, t, String::from_utf8_lossy(want)),
    );
}

pub fn assert_cells_hex(c: &Cells, hex: &str, name: &str) {
    let want = hex_blob(hex);
    let n = cells_len(c) as usize;
    let mut ok = n == want.len();
    if ok {
        for i in 0..n {
            if cells_at(c, i as i32) != want[i] as i32 {
                ok = false;
                break;
            }
        }
    }
    let got: Vec<String> = (0..n).map(|i| format!("{:02x}", cells_at(c, i as i32) & 0xFF)).collect();
    verdict(ok, format!("{}: got {} bytes {}, want {}", name, n, got.join(""), hex));
}

pub fn text_blob(s: &str) -> Vec<u8> {
    s.as_bytes().to_vec()
}

pub fn hex_blob(s: &str) -> Vec<u8> {
    let digits: Vec<u8> = s
        .bytes()
        .filter(|b| !b.is_ascii_whitespace())
        .map(|b| (b as char).to_digit(16).expect("hex digit") as u8)
        .collect();
    digits.chunks(2).map(|p| (p[0] << 4) | p[1]).collect()
}

// A corpus file, read whole. `EMON_TEST_DIR` names the directory (the unit
// script sets it); the default is `test` under the working directory.
pub fn test_file(name: &str) -> Vec<u8> {
    let dir = std::env::var("EMON_TEST_DIR").unwrap_or_else(|_| "test".to_string());
    let path = std::path::Path::new(&dir).join(name);
    std::fs::read(&path).unwrap_or_else(|e| panic!("test file {}: {}", path.display(), e))
}

// The same, the file's name being bytes b[from..to] (a corpus row).
pub fn test_file_span(b: &[u8], from: i32, to: i32) -> Vec<u8> {
    let name = String::from_utf8_lossy(&b[from as usize..to as usize]).to_string();
    test_file(&name)
}

pub fn cells_blob(c: &Cells) -> Vec<u8> {
    (0..cells_len(c)).map(|i| cells_at(c, i) as u8).collect()
}

// ------------------------------------------------------ a source that misses
//
// A `Src` over a blob, served in fixed-size CHUNKS of which only some are
// RESIDENT (P1.11, D058). It stands in for a browser `Blob` or a URL: a read
// of a byte in a non-resident chunk records the chunk as PENDING and misses;
// `src_ctl` op 0 (fetch) is what a host does between attempts, making the
// pending chunks resident with a read-ahead that doubles per round. A SYNC one
// fills the chunk inline and never misses. Tests only.

use crate::port::{Src, SrcBacking};
use std::sync::{Arc, Mutex};

struct ChunkState {
    resident: Vec<bool>,
    pending: Vec<i32>,
    fills: i32,
    hints: i32,
}

pub struct ChunkedBacking {
    data: Vec<u8>,
    chunk: i32,
    sync: bool,
    st: Mutex<ChunkState>,
}

impl ChunkedBacking {
    fn nchunks(&self) -> i32 {
        (self.data.len() as i32 + self.chunk - 1) / self.chunk
    }

    // True when chunk c is resident now: a sync source fills it inline, an
    // async one records it as pending and answers false.
    fn touch(&self, st: &mut ChunkState, c: i32) -> bool {
        if st.resident[c as usize] {
            return true;
        }
        if self.sync {
            st.resident[c as usize] = true;
            st.fills += 1;
            return true;
        }
        if !st.pending.contains(&c) {
            st.pending.push(c);
        }
        false
    }
}

impl SrcBacking for ChunkedBacking {
    fn len(&self) -> i32 {
        self.data.len() as i32
    }
    fn at(&self, i: i32) -> i32 {
        let mut st = self.st.lock().unwrap();
        if self.touch(&mut st, i / self.chunk) {
            self.data[i as usize] as i32
        } else {
            -1
        }
    }
    fn span(&self, s: i32, buf: &mut [u8]) -> bool {
        let mut st = self.st.lock().unwrap();
        let mut ok = true;
        let n = buf.len() as i32;
        if n == 0 {
            return true;
        }
        let first = s / self.chunk;
        let last = (s + n - 1) / self.chunk;
        for c in first..=last {
            if self.touch(&mut st, c) {
                let lo = std::cmp::max(s, c * self.chunk);
                let hi = std::cmp::min(s + n, (c + 1) * self.chunk);
                for k in lo..hi {
                    buf[(k - s) as usize] = self.data[k as usize];
                }
            } else {
                ok = false;
            }
        }
        ok
    }
    fn hint(&self, _s: i32, _e: i32) {
        self.st.lock().unwrap().hints += 1;
    }
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

// A source over b, in chunks of `chunk` bytes, none resident; sync 1 fills inline.
pub fn src_chunked(b: &[u8], chunk: i32, sync: i32) -> Src {
    let n = (b.len() as i32 + chunk - 1) / chunk;
    Src::new(Arc::new(ChunkedBacking {
        data: b.to_vec(),
        chunk,
        sync: sync == 1,
        st: Mutex::new(ChunkState {
            resident: vec![false; n as usize],
            pending: Vec::new(),
            fills: 0,
            hints: 0,
        }),
    }))
}

// A fresh view (missed flag clear) of the same chunk cache.
pub fn src_view(s: &Src) -> Src {
    s.view()
}

// Control of the cache: op 0 fetch (a is the round: read-ahead of 2^a - 1
// chunks each side of every pending chunk; answers the chunks made resident),
// 1 make chunk a resident, 2 evict chunk a, 3 make every chunk resident, 4 the
// pending count, 5 the resident count, 6 the hints received, 7 the chunks a
// sync source filled inline, 8 the chunk count.
pub fn src_ctl(s: &Src, op: i32, a: i32) -> i32 {
    let b = s.backing().as_any().downcast_ref::<ChunkedBacking>().expect("a chunked test source");
    let n = b.nchunks();
    let mut st = b.st.lock().unwrap();
    match op {
        0 => {
            let r = (1i32 << std::cmp::min(a, 20)) - 1;
            let pend = std::mem::take(&mut st.pending);
            let mut made = 0;
            for c in pend {
                for k in std::cmp::max(0, c - r)..=std::cmp::min(n - 1, c + r) {
                    if !st.resident[k as usize] {
                        st.resident[k as usize] = true;
                        made += 1;
                    }
                }
            }
            made
        }
        1 => {
            st.resident[a as usize] = true;
            st.pending.retain(|&c| c != a);
            0
        }
        2 => {
            st.resident[a as usize] = false;
            0
        }
        3 => {
            for k in 0..n {
                st.resident[k as usize] = true;
            }
            st.pending.clear();
            0
        }
        4 => st.pending.len() as i32,
        5 => st.resident.iter().filter(|&&r| r).count() as i32,
        6 => st.hints,
        7 => st.fills,
        8 => n,
        _ => panic!("src_ctl: no op {}", op),
    }
}
