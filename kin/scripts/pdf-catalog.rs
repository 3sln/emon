// The Rust side of kin/scripts/pdf-catalog: open each file named on stdin, read the catalogue
// extras (kin/pdfannot.kin, kin/pdfmeta.kin) and print `path<TAB>status<TAB>json`.
#![allow(dead_code, unused_mut, unused_variables, unused_imports)]
mod emon;
mod port;
mod testport;
use emon::pdfdoc::*;
use emon::pdfannot::*;
use emon::pdfmeta::*;
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
        let res = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let mut ar = cells();
            let mut dc = cells();
            let mut a = cells();
            let mut b = cells();
            let mut st = cells();
            let mut pg = cells();
            let mut idx = cells();
            let mut txt = cells();
            let mut w = cells();
            let mut fs = cells();
            let mut seen = cells();
            let mut fm = cells();
            let mut an = cells();
            let mut aq = cells();
            let mut fd = cells();
            let mut sg = cells();
            let mut md = cells();
            let mut xm = cells();
            let mut ol = cells();
            let mut lb = cells();
            let mut nd = cells();
            let mut nt = cells();
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
            let mut out = String::new();
            if status == 0 {
                let n = pd_pages(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg);
                px_index_build(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut idx);
                pa_run(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut idx,
                       &mut txt, &mut w, &mut fs, &mut seen, &mut fm, &mut an, &mut aq, &mut fd, &mut sg);
                out.push_str("{\"pages\":");
                out.push_str(&n.to_string());
                out.push_str(",\"a\":");
                pa_dump(&mut out, &mut txt, &mut an, &mut aq, &mut fd, &mut sg);
                out.push_str(",\"m\":");
                pm_run(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut pg, &mut idx,
                       &mut txt, &mut w, &mut seen, &mut md, &mut xm, &mut ol, &mut lb, &mut nd, &mut nt);
                pm_dump(&mut out, &mut txt, &mut md, &mut xm, &mut ol, &mut lb, &mut nd, &mut pg, &mut nt);
                out.push('}');
            } else {
                out.push_str("{}");
            }
            (name, out)
        }));
        match res {
            Ok((name, out)) => println!("{}\t{}\t{}", path, name, out),
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
