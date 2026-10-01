// The Rust side of kin/scripts/struct-sweep: open each file named on stdin, interpret its pages, read its
// structure tree (kin/pdfstruct.kin), join it to the marked content and print `path<TAB>status<TAB>json`.
#![allow(dead_code, unused_mut, unused_variables, unused_imports)]
mod emon;
mod port;
mod testport;
use emon::pdfdoc::*;
use emon::pdfcontent::*;
use emon::pdfmeta::*;
use emon::pdfstruct::*;
use emon::pdfx::*;
use std::io::BufRead;

fn cells() -> port::Cells {
    port::Cells::new()
}

fn main() {
    let stdin = std::io::stdin();
    for line in stdin.lock().lines() {
        let line = line.unwrap();
        let mut parts = line.splitn(2, '\t');
        let path = parts.next().unwrap().to_string();
        let password: Option<String> = parts.next().map(|s| s.to_string());
        let data = match std::fs::read(&path) {
            Ok(d) => d,
            Err(_) => {
                println!("{}\tioerror\t{{}}", path);
                continue;
            }
        };
        let t0 = std::time::Instant::now();
        let res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let mut ar = cells(); let mut dc = cells(); let mut a = cells(); let mut b = cells();
            let mut st = cells(); let mut pg = cells(); let mut idx = cells(); let mut txt = cells();
            let mut w = cells(); let mut seen = cells(); let mut md = cells(); let mut xm = cells();
            let mut ol = cells(); let mut lb = cells(); let mut nd = cells(); let mut nt = cells();
            let mut cx = cells(); let mut ox = cells(); let mut cs = cells(); let mut out = cells();
            let mut f0 = cells(); let mut f1 = cells(); let mut f2 = cells(); let mut f3 = cells();
            let mut gl = cells(); let mut et = cells(); let mut mk = cells(); let mut fnt = cells();
            let mut wd = cells(); let mut ln = cells();
            let mut pa = cells(); let mut im = cells(); let mut pts = cells(); let mut gx = cells();
            let mut ss = cells(); let mut se = cells(); let mut sk = cells(); let mut sc = cells();
            let mut so = cells(); let mut sa = cells(); let mut wk = cells(); let mut ha = cells();
            let mut hb = cells(); let mut hh = cells();
            let src = port::Src::of_bytes(data.clone());
            let status = match &password {
                Some(pw) => {
                    let pws = port::Src::of_bytes(pw.clone().into_bytes());
                    pd_open_pw(&src, &pws, &mut ar, &mut dc, &mut a, &mut b, &mut st)
                }
                None => pd_open(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st),
            };
            let mut name = String::new();
            pd_status_emit_name(&mut name, status);
            let mut o = String::new();
            if status == 0 {
                let cst = pc_document(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut cx, &mut ox,
                                      &mut cs, &mut out, &mut f0, &mut f1, &mut f2, &mut f3,
                                      &mut gl, &mut et, &mut mk, &mut fnt, &mut pg, &mut wd, &mut ln, &mut pa, &mut im, &mut pts, &mut gx);
                let n = pd_pg_count(&mut pg);
                px_index_build(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut idx);
                pm_run(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut idx,
                       &mut txt, &mut w, &mut seen, &mut md, &mut xm, &mut ol, &mut lb, &mut nd, &mut nt);
                let ne = tg_read(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut idx, &mut txt,
                                 &mut w, &mut seen, &mut ss, &mut se, &mut sk, &mut sc, &mut so, &mut wk, &mut md);
                let jr = tg_join(&mut ss, &mut se, &mut sk, &mut sc, &mut so, &mut sa, &mut gl, &mut mk,
                                 &mut fnt, &mut pg, &mut ha, &mut hb, &mut hh);
                o.push_str(&format!("{{\"pages\":{},\"content\":{},\"read\":{},\"join\":{},\"ms\":{},\"s\":", n, cst, ne, jr, t0.elapsed().as_millis()));
                tg_dump(&mut o, &mut txt, &mut ss, &mut se, &mut sk, &mut sc, &mut so, &mut sa);
                o.push('}');
            } else {
                o.push_str("{}");
            }
            (name, o)
        }));
        match res {
            Ok((name, o)) => println!("{}\t{}\t{}", path, name, o),
            Err(p) => {
                let m = if let Some(s) = p.downcast_ref::<String>() {
                    s.clone()
                } else if let Some(s) = p.downcast_ref::<&str>() {
                    s.to_string()
                } else {
                    "?".to_string()
                };
                println!("{}\tPANIC {}\t{{}}", path, m.replace('\n', " ").replace('\t', " "))
            }
        }
    }
}
