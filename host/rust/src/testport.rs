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
