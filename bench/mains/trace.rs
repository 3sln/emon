// P6.4 harness, Rust arm: kin-generated `emon::norm` then `emon::trace` over the `Bitmap` port.
//
//     trace PAGE W H CH N DPI100 [dump]
//
// PAGE is raw 8-bit pixels (CH 1 or 3). It is normalised (`nm-view`), then the binary view is traced with the majority ink layer of every
// component, and every NON-BODY ink layer's mask is traced as a bitmap of its own (fixed layer); `light` and `background` masks also give
// tints. Prints `T <phase> <best ms>`, `S <layers> <components> <runs> <rules> <prims> <tlines> <pts> <refs>`, and with `dump` every row of
// the five tracer arenas, one line each (`C`, `R`, `P`, `Q` = pts, `F` = refs, `L` = tlines), then `H <fnv-1a of all those lines>`: the
// Rust arm and the Go arm print the same bytes.
#![allow(dead_code, unused_mut, unused_variables)]
mod emon;
mod port;
use emon::evidence::*;
use emon::norm::*;
use emon::trace::*;
use port::*;
use std::time::Instant;

fn ms(a: Instant, b: Instant) -> f64 {
    b.duration_since(a).as_secs_f64() * 1000.0
}

fn dump_arena(tag: &str, c: &Cells, stride: usize, out: &mut String) {
    let n = cells_len(c) as usize / stride;
    for r in 0..n {
        out.push_str(tag);
        for f in 0..stride {
            out.push(' ');
            out.push_str(&cells_at(c, (r * stride + f) as i32).to_string());
        }
        out.push('\n');
    }
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let bytes = std::fs::read(&a[1]).unwrap();
    let w: i32 = a[2].parse().unwrap();
    let h: i32 = a[3].parse().unwrap();
    let ch: i32 = a[4].parse().unwrap();
    let n: usize = a[5].parse().unwrap();
    let dpi100: i32 = a[6].parse().unwrap();
    let dump = a.len() > 7 && a[7] == "dump";
    let src = Bitmap::from_bytes(w, h, ch, &bytes);
    let gray = Bitmap::new(w, h, 1);
    let binary = Bitmap::new(w, h, 1);
    let mask = Bitmap::new(w, h, 1);
    let labels = Bitmap::new(w, h, 1);
    let mut ws = Cells::new();
    let mut sv = Cells::new();
    let mut aux = Cells::new();
    let mut views = Cells::new();
    let mut inks = Cells::new();
    let (mut comps, mut rules, mut prims, mut tlines, mut pts, mut refs) = (Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new());
    let (mut rn, mut ri, mut cs, mut vr, mut pc, mut sg, mut mem) = (Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new());
    let (mut s1, mut s2, mut s3, mut s4, mut s5, mut s6) = (Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new(), Cells::new());
    let mut best = [f64::MAX; 5];
    let mut nl = 0;
    let mut ncomp = 0;
    let mut nruns = 0;
    for _ in 0..n {
        views = Cells::new();
        inks = Cells::new();
        comps = Cells::new();
        rules = Cells::new();
        prims = Cells::new();
        tlines = Cells::new();
        pts = Cells::new();
        refs = Cells::new();
        vw_new(&mut views, 0, vk_decode(), -1, -1, w, h, dpi100, 0, -1, 0, 0, 0, 0, 0);
        let t0 = Instant::now();
        nl = nm_view(&mut views, &mut inks, 0, 0, &src, &gray, &binary, &mut ws, &mut sv, &mut aux);
        nm_label_into(&src, &mut ws, &labels);
        let t1 = Instant::now();
        let bview = nm_binary_view(&mut ws);
        ncomp = tr_trace(&mut views, &mut inks, 0, bview, -1, 0, &binary, &gray, &labels, &mut comps, &mut rules, &mut prims, &mut tlines, &mut pts, &mut refs,
            &mut rn, &mut ri, &mut cs, &mut vr, &mut pc, &mut sg, &mut mem, &mut s1, &mut s2, &mut s3, &mut s4, &mut s5, &mut s6);
        nruns = cells_at(&ri, h);
        let t2 = Instant::now();
        for k in 0..nl {
            let role = ik_get(&mut inks, k, ikf_role());
            if role != ir_body() {
                nm_mask_into(&src, &mut ws, k + 1, &mask);
                let mv = ik_get(&mut inks, k, ikf_mask());
                tr_trace(&mut views, &mut inks, 0, mv, k, 0, &mask, &gray, &mask, &mut comps, &mut rules, &mut prims, &mut tlines, &mut pts, &mut refs,
                    &mut rn, &mut ri, &mut cs, &mut vr, &mut pc, &mut sg, &mut mem, &mut s1, &mut s2, &mut s3, &mut s4, &mut s5, &mut s6);
                if role == ir_light() || role == ir_background() {
                    tr_tints(&mut views, 0, mv, dpi100, &mask, k, &mut inks, &mut prims, &mut refs, &mut s1, &mut s2);
                }
            }
        }
        let t3 = Instant::now();
        let ph = [ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t1, t3), ms(t0, t3)];
        for k in 0..5 {
            if ph[k] < best[k] {
                best[k] = ph[k];
            }
        }
    }
    let names = ["norm", "trace-binary", "trace-masks", "trace", "total"];
    for k in 0..5 {
        println!("T {} {:.3}", names[k], best[k]);
    }
    println!(
        "S {} {} {} {} {} {} {} {}",
        nl,
        ncomp,
        nruns,
        cells_len(&rules) / 20,
        cells_len(&prims) / 24,
        cells_len(&tlines) / 20,
        cells_len(&pts) / 3,
        cells_len(&refs) / 2
    );
    for k in 0..ik_count(&mut inks) {
        println!("I {} {} {}", k, ik_get(&mut inks, k, ikf_count()), ik_get(&mut inks, k, ikf_role()));
    }
    let mut out = String::new();
    dump_arena("C", &comps, 24, &mut out);
    dump_arena("R", &rules, 20, &mut out);
    dump_arena("P", &prims, 24, &mut out);
    dump_arena("Q", &pts, 3, &mut out);
    dump_arena("F", &refs, 2, &mut out);
    dump_arena("L", &tlines, 20, &mut out);
    let mut hv: u64 = 0xcbf29ce484222325;
    for b in out.bytes() {
        hv ^= b as u64;
        hv = hv.wrapping_mul(0x100000001b3);
    }
    println!("H {:016x}", hv);
    if dump {
        print!("{}", out);
    }
    if a.len() > 8 && a[8] == "dt" {
        // The stroke width of a text-like component as the run median says (D198) against a distance transform (a 3-4 chamfer of the
        // component's own box, the ridge pixels' median, 2r - 1): `D <components> <median |error|> <p95 |error|> <mean run> <mean dt>`, x100 px.
        let mut errs: Vec<i32> = Vec::new();
        let (mut sr, mut sd) = (0i64, 0i64);
        for r in 0..(cells_len(&comps) / 24) {
            let g = |f: i32| cells_at(&comps, r * 24 + f);
            if g(15) != ck_text_like() || g(5) != nm_binary_view(&mut ws) {
                continue;
            }
            let (px, py, pw, ph) = (g(6), g(7), g(8), g(9));
            let (cw, chh) = (pw + 2, ph + 2);
            let mut d = vec![0i32; (cw * chh) as usize];
            for y in 0..ph {
                for x in 0..pw {
                    if bitmap_u8(&binary, px + x, py + y, 0) != 0 {
                        d[((y + 1) * cw + x + 1) as usize] = 1_000_000;
                    }
                }
            }
            for y in 1..chh - 1 {
                for x in 1..cw - 1 {
                    let i = (y * cw + x) as usize;
                    if d[i] != 0 {
                        let m = (d[i - 1] + 3).min(d[i - cw as usize] + 3).min(d[i - cw as usize - 1] + 4).min(d[i - cw as usize + 1] + 4);
                        d[i] = d[i].min(m);
                    }
                }
            }
            for y in (1..chh - 1).rev() {
                for x in (1..cw - 1).rev() {
                    let i = (y * cw + x) as usize;
                    if d[i] != 0 {
                        let m = (d[i + 1] + 3).min(d[i + cw as usize] + 3).min(d[i + cw as usize - 1] + 4).min(d[i + cw as usize + 1] + 4);
                        d[i] = d[i].min(m);
                    }
                }
            }
            let mut ridge: Vec<i32> = Vec::new();
            for y in 1..chh - 1 {
                for x in 1..cw - 1 {
                    let i = (y * cw + x) as usize;
                    if d[i] > 0 {
                        let mut top = true;
                        for dy in -1i32..=1 {
                            for dx in -1i32..=1 {
                                if d[(i as i32 + dy * cw + dx) as usize] > d[i] {
                                    top = false;
                                }
                            }
                        }
                        if top {
                            ridge.push(d[i]);
                        }
                    }
                }
            }
            if ridge.is_empty() {
                continue;
            }
            ridge.sort();
            let dt = (2 * ridge[ridge.len() / 2] * 100) / 3 - 100;
            let run = g(12) * 100 / 24;
            errs.push((run - dt).abs());
            sr += run as i64;
            sd += dt as i64;
        }
        errs.sort();
        let n = errs.len().max(1);
        println!("D {} {} {} {} {}", errs.len(), errs.get(n / 2).copied().unwrap_or(-1), errs.get(n * 95 / 100).copied().unwrap_or(-1), sr / n as i64, sd / n as i64);
    }
}
