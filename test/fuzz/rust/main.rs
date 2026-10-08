// The `bb fuzz` driver, Rust (DEVLOG P2.12, D106). Test-only.
//
//     fuzzdriver [--chunk=N]     reads one file path per line on stdin
//
// For each path it writes `NAME<TAB>MEMORY-LINE[<TAB>ASYNC-LINE]` and flushes, so a
// hang or a crash costs the harness only the case in flight. A panic is caught
// and reported as `PANIC <message>` in place of the line. `emon.fuzzprobe`'s
// `fz-case` makes the line; overflow and bounds checks are ON.
#![allow(dead_code, unused_mut, unused_variables, unused_imports)]
mod emon;
mod port;
mod testport;
use std::io::{BufRead, Write};

fn run(bytes: &[u8], chunk: i32) -> String {
    let res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut t = String::new();
        emon::fuzzprobe::fz_case(bytes, chunk, &mut t);
        t
    }));
    match res {
        Ok(t) => t,
        Err(p) => {
            // a limit breach is a typed payload and a NAMED outcome (SPEC 7.4, P10.10), the same line on every host: nothing reads a message
            if let Some(b) = p.downcast_ref::<port::LimitBreach>() {
                return format!("LIMIT-{}", b.name());
            }
            let m = if let Some(s) = p.downcast_ref::<String>() { s.clone() }
                    else if let Some(s) = p.downcast_ref::<&str>() { s.to_string() }
                    else { "?".to_string() };
            let at = LOC.with(|l| l.borrow().clone());
            format!("PANIC {} at {}", m.replace('\n', " ").replace('\t', " "), at)
        }
    }
}

thread_local! { static LOC: std::cell::RefCell<String> = std::cell::RefCell::new(String::new()); }

fn main() {
    let mut chunk = 0;
    for a in std::env::args().skip(1) {
        if let Some(n) = a.strip_prefix("--chunk=") { chunk = n.parse().unwrap(); }
    }
    // the panic's place (a line of gen/rust/emon/*.rs) goes into the answer
    std::panic::set_hook(Box::new(|info| {
        let s = match info.location() { Some(l) => format!("{}:{}", l.file(), l.line()), None => "?".to_string() };
        LOC.with(|l| *l.borrow_mut() = s);
    }));
    let stdin = std::io::stdin();
    let stdout = std::io::stdout();
    for line in stdin.lock().lines() {
        let path = line.unwrap();
        let data = match std::fs::read(&path) { Ok(d) => d, Err(_) => { println!("{}\tIOERROR", path); continue; } };
        let mut out = format!("{}\t{}", path, run(&data, 0));
        if chunk > 0 { out.push('\t'); out.push_str(&run(&data, chunk)); }
        let mut o = stdout.lock();
        writeln!(o, "{}", out).unwrap();
        o.flush().unwrap();
    }
}
