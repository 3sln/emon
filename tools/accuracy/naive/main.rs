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
use emon::heading::*;
use emon::style::*;

fn main() {
    let path = std::env::args().nth(1).expect("path");
    // flags after the path: `plain` (paragraphs only, no heading analysis), `pretty`, `lines`, `heads` (debugging)
    let has = |f: &str| std::env::args().skip(2).any(|a| a == f);
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
    if has("lines") {
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
    let mut sf = port::Cells::new(); let mut sc = port::Cells::new(); let mut hc = port::Cells::new();
    let mut ws = port::Cells::new(); let mut hmp = port::Cells::new();
    let mut pr = port::Cells::new(); let mut ptxt = port::Cells::new();
    if !has("plain") {
        hd_analyze(&mut wd, &mut ln, &mut et, &mut gl, &mut sf, &mut sc, &mut hc, &mut ws, &mut hmp, &mut pr, &mut ptxt);
    }
    if has("heads") {
        // debugging: the style inventory and, for every line, its class, level and the numbers the analysis used
        println!("body class {} size {} gap {} classes {}", st_hget(&mut sf, sfh_body()), st_hget(&mut sf, sfh_size()), st_hget(&mut sf, sfh_gap()), st_hget(&mut sf, sfh_classes()));
        for c in 0..st_hget(&mut sf, sfh_classes()) {
            println!("class {}: bucket {} bits {} caps {} color {} chars {} lines {} size {}", c, sc_get(&mut sc, c, sck_bucket()), sc_get(&mut sc, c, sck_bits()), sc_get(&mut sc, c, sck_caps()), sc_get(&mut sc, c, sck_color()), sc_get(&mut sc, c, sck_chars()), sc_get(&mut sc, c, sck_lines()), sc_get(&mut sc, c, sck_size()));
        }
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
            let g = |f: i32, sf: &mut port::Cells| st_get(sf, l, f);
            println!("p{} L{:<2} cls{:<2} head{} pts{:<2} unit{:<3} lev{} ga{:<6} gb{:<6} sib{} ns{} nc{} ind{} cen{} | {}", g(sfk_page(), &mut sf), hd_level(&mut hc, l), g(sfk_class(), &mut sf), g(sfk_head(), &mut sf), g(sfk_points(), &mut sf), g(sfk_unit(), &mut sf), hd_level(&mut hc, l), g(sfk_gap_above(), &mut sf), g(sfk_gap_below(), &mut sf), g(sfk_sib(), &mut sf), g(sfk_nsib(), &mut sf), g(sfk_ncol(), &mut sf), g(sfk_indent(), &mut sf), g(sfk_centered(), &mut sf), s);
        }
        return;
    }
    model_init(&mut nodes, &mut attrs, &mut regions, &mut text, &mut pages, &mut ledger, &mut layout);
    let np = pd_pg_count(&mut pg);
    pa_pages(&mut pg, &mut pages);
    pa_build(&mut wd, &mut ln, &mut et, np, &mut nodes, &mut attrs, &mut regions, &mut text,
             &mut ord, &mut raw, &mut tmp, &mut fr, &mut gp, &mut hc);
    let c = model_check(&mut nodes, &mut attrs, &mut regions, &mut text, &mut pages, &mut ledger, &mut layout, &mut chk);
    if c != 0 { eprintln!("model-check {} at {}", c, port::cells_at(&chk, 0)); std::process::exit(5); }
    let mut t = String::new();
    js_emit(&mut t, if has("pretty") { 1 } else { 0 }, &mut nodes, &mut attrs, &mut regions, &mut text, &mut pages, &mut ledger, &mut layout);
    print!("{}", t);
}
