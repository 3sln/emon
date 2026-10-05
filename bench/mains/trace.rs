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
}
