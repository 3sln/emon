#![allow(dead_code, unused_mut, unused_variables, unused_imports)]
// TEMPORARY driver for P1.8's measurement (not P1.10's Engine): PDF -> content -> para -> Model -> JSON.
//   naive FILE.pdf     prints the emon-1 Model JSON (compact) on stdout
mod emon;
mod port;
mod testport;
use emon::pdfdoc::*;
use emon::pdfcontent::*;
use emon::evidence::*;
use emon::model::*;
use emon::json::*;
use emon::para::*;

fn main() {
    let path = std::env::args().nth(1).expect("path");
    let data = std::fs::read(&path).expect("read");
    let src = port::Src::of_bytes(data);
    let mut ar = port::Cells::new(); let mut dc = port::Cells::new();
    let mut a = port::Cells::new(); let mut b = port::Cells::new();
    let mut st = port::Cells::new(); let mut pg = port::Cells::new();
    let mut cx = port::Cells::new(); let mut ox = port::Cells::new();
    let mut cs = port::Cells::new(); let mut out = port::Cells::new();
    let mut f0 = port::Cells::new(); let mut f1 = port::Cells::new();
    let mut f2 = port::Cells::new(); let mut f3 = port::Cells::new();
    let mut et = port::Cells::new(); let mut gl = port::Cells::new();
    let mut wd = port::Cells::new(); let mut ln = port::Cells::new();
    let mut mk = port::Cells::new(); let mut fnt = port::Cells::new();
    let ostatus = pd_open(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st);
    if ostatus != 0 { eprintln!("open status {}", ostatus); std::process::exit(4); }
    let status = pc_document(&src, &mut ar, &mut dc, &mut a, &mut b, &mut st, &mut cx, &mut ox,
                             &mut cs, &mut out, &mut f0, &mut f1, &mut f2, &mut f3,
                             &mut gl, &mut et, &mut mk, &mut fnt, &mut pg, &mut wd, &mut ln);
    if status != 0 { eprintln!("content status {}", status); std::process::exit(4); }
    if std::env::args().nth(2).as_deref() == Some("lines") {
        // debugging: every line, as `page y0 y1 x0 x1 rot size font text`
        for l in 0..ln_count(&mut ln) {
            let w0 = ln_get(&mut ln, l, lh_first());
            let wn = ln_get(&mut ln, l, lh_count());
            let mut s = String::new();
            for w in w0..w0 + wn {
                if w > w0 { s.push(' '); }
                let ts = wd_get(&mut wd, w, hd_tstart());
                for k in 0..wd_get(&mut wd, w, hd_tlen()) {
                    s.push(char::from_u32(port::cells_at(&et, ts + k) as u32).unwrap_or('?'));
                }
            }
            println!("{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}", ln_get(&mut ln, l, lh_page()), ln_get(&mut ln, l, lh_y0()), ln_get(&mut ln, l, lh_y1()),
                     ln_get(&mut ln, l, lh_x0()), ln_get(&mut ln, l, lh_x1()), ln_get(&mut ln, l, lh_rot()),
                     wd_get(&mut wd, w0, wf_size()), wd_get(&mut wd, w0, wf_font()), s);
        }
        return;
    }
    let mut nodes = port::Cells::new(); let mut attrs = port::Cells::new();
    let mut regions = port::Cells::new(); let mut text = port::Cells::new();
    let mut pages = port::Cells::new(); let mut ledger = port::Cells::new();
    let mut layout = port::Cells::new();
    let mut ord = port::Cells::new(); let mut raw = port::Cells::new();
    let mut tmp = port::Cells::new(); let mut fr = port::Cells::new();
    let mut gp = port::Cells::new(); let mut chk = port::Cells::new();
    let mut lt = port::Cells::new(); let mut ls = port::Cells::new(); let mut sd = port::Cells::new();
    let mut it = port::Cells::new(); let mut io = port::Cells::new(); let mut rw = port::Cells::new();
    let mut gu = port::Cells::new(); let mut ac = port::Cells::new(); let mut tm = port::Cells::new();
    let mut cf = port::Cells::new(); let mut lo = port::Cells::new(); let mut pr = port::Cells::new(); let mut dbg = port::Cells::new();
    model_init(&mut nodes, &mut attrs, &mut regions, &mut text, &mut pages, &mut ledger, &mut layout);
    let np = if std::env::args().nth(2).as_deref() == Some("cols") { std::env::args().nth(3).map(|s| s.parse::<i32>().unwrap()).unwrap_or(pd_pg_count(&mut pg)) } else { pd_pg_count(&mut pg) };
    pa_pages(&mut pg, &mut pages);
    pa_build(&mut wd, &mut ln, &mut et, np, &mut pages, &mut layout, &mut nodes, &mut attrs, &mut regions, &mut text,
             &mut ord, &mut raw, &mut tmp, &mut fr, &mut gp,
             &mut lt, &mut ls, &mut sd, &mut it, &mut io, &mut rw, &mut gu, &mut ac, &mut tm, &mut cf, &mut lo, &mut pr, &mut dbg);
    if std::env::args().nth(2).as_deref() == Some("cols") {
        // debugging: the last page's gutters (x0 x1 top bot rows lrows rrows lsum rsum flag) and every fragment
        let ng = port::cells_len(&gu) / 10;
        for g in 0..ng { let v: Vec<String> = (0..10).map(|f| port::cells_at(&gu, g * 10 + f).to_string()).collect(); println!("gutter {}", v.join(" ")); }
        { let v: Vec<String> = (0..12).map(|f| port::cells_at(&pr, f).to_string()).collect(); println!("pr {}", v.join(" ")); }
        for i in 0..(port::cells_len(&it) / 7) { let v: Vec<String> = (0..7).map(|f| port::cells_at(&it, i * 7 + f).to_string()).collect(); if port::cells_at(&it, i * 7 + 3) < 1000 { println!("item {}", v.join(" ")); } }
        for j in 0..(port::cells_len(&dbg) / 8) { let v: Vec<String> = (0..8).map(|f| port::cells_at(&dbg, j * 8 + f).to_string()).collect(); println!("join frag/a/b/score/x0/x1/ax1/n {}", v.join(" ")); }
        let nf = port::cells_len(&cf) / 12;
        for r in 0..nf { let v: Vec<String> = (0..12).map(|f| port::cells_at(&cf, r * 12 + f).to_string()).collect(); println!("frag {}", v.join(" ")); }
        return;
    }
    let c = model_check(&mut nodes, &mut attrs, &mut regions, &mut text, &mut pages, &mut ledger, &mut layout, &mut chk);
    if c != 0 { eprintln!("model-check {} at {}", c, port::cells_at(&chk, 0)); std::process::exit(5); }
    let mut t = String::new();
    js_emit(&mut t, if std::env::args().nth(2).as_deref() == Some("pretty") { 1 } else { 0 }, &mut nodes, &mut attrs, &mut regions, &mut text, &mut pages, &mut ledger, &mut layout);
    print!("{}", t);
}
