// P2.7 harness, Rust arm: kin-generated `emon::raster` over the `Bitmap` port.
//
//     main PAGE W H N [port|bulk|skip]
//
// port: every pixel loop in kin, one port call per pixel. bulk: histogram,
// threshold, alloc and integral image as single host calls. skip: bulk, and
// the labeller skips white with `bitmap-next-nonzero`.
#![allow(dead_code, unused_mut, unused_variables)]
mod emon;
mod port;
use std::time::Instant;

fn ms(a: Instant, b: Instant) -> f64 {
    b.duration_since(a).as_secs_f64() * 1000.0
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let bytes = std::fs::read(&a[1]).unwrap();
    let w: i32 = a[2].parse().unwrap();
    let h: i32 = a[3].parse().unwrap();
    let n: usize = a[4].parse().unwrap();
    let skip = a.len() > 5 && a[5] == "skip";
    let bulk = skip || (a.len() > 5 && a[5] == "bulk");
    let g = port::Bitmap::from_bytes(w, h, 1, &bytes);
    let m = port::Bitmap::new(w, h, 1);
    let m2 = port::Bitmap::new(w, h, 1);
    let mut hist = port::Cells::new();
    let mut labels = port::Cells::new();
    let mut parent = port::Cells::new();
    let mut area = port::Cells::new();
    let mut stats = port::Cells::new();
    let mut stats2 = port::Cells::new();
    let mut isum = port::Cells::new();
    let mut isq = port::Cells::new();
    let mut sqt = port::Cells::new();
    emon::raster::ras_sqrt_table(&mut sqt);
    let mut best = [f64::MAX; 11];
    let mut thr = 0;
    let mut ink2 = 0;
    for _ in 0..n {
        let t0 = Instant::now();
        if bulk { emon::raster::ras_histogram_bulk(&g, &mut hist); } else { emon::raster::ras_histogram(&g, &mut hist); }
        let t1 = Instant::now();
        thr = emon::raster::ras_otsu(&mut hist, w * h);
        let t2 = Instant::now();
        if bulk { emon::raster::ras_binarise_bulk(&g, &m, thr); } else { emon::raster::ras_binarise(&g, &m, thr); }
        let t3 = Instant::now();
        if bulk { emon::raster::ras_alloc_bulk(&mut labels, w * h); } else { emon::raster::ras_alloc(&mut labels, w * h); }
        let t4 = Instant::now();
        if skip { emon::raster::ras_label_skip(&m, &mut labels, &mut parent, &mut area, &mut stats); } else { emon::raster::ras_label(&m, &mut labels, &mut parent, &mut area, &mut stats); }
        let t5 = Instant::now();
        if bulk { emon::raster::ras_integral_bulk(&g, &mut isum, &mut isq); } else { emon::raster::ras_integral(&g, &mut isum, &mut isq); }
        let t6 = Instant::now();
        ink2 = emon::raster::ras_sauvola(&g, &mut isum, &mut isq, &mut sqt, &m2);
        let t7 = Instant::now();
        if bulk { emon::raster::ras_alloc_bulk(&mut labels, w * h); } else { emon::raster::ras_alloc(&mut labels, w * h); }
        let t8 = Instant::now();
        if skip { emon::raster::ras_label_skip(&m2, &mut labels, &mut parent, &mut area, &mut stats2); } else { emon::raster::ras_label(&m2, &mut labels, &mut parent, &mut area, &mut stats2); }
        let t9 = Instant::now();
        let ph = [ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), ms(t4, t5), ms(t0, t5),
                  ms(t5, t6), ms(t6, t7), ms(t7, t8), ms(t8, t9), ms(t5, t9)];
        for k in 0..11 {
            if ph[k] < best[k] {
                best[k] = ph[k];
            }
        }
    }
    let names = ["hist", "otsu", "bin", "alloc", "label", "total", "integ", "sauv", "alloc2", "label2", "total2"];
    for k in 0..11 {
        println!("T {} {:.3}", names[k], best[k]);
    }
    let mut s = String::new();
    for i in 0..port::cells_len(&stats) {
        s.push(' ');
        s.push_str(&port::cells_at(&stats, i).to_string());
    }
    println!("S {}{}", thr, s);
    let mut s2 = String::new();
    for i in 0..port::cells_len(&stats2) {
        s2.push(' ');
        s2.push_str(&port::cells_at(&stats2, i).to_string());
    }
    println!("S2 {}{}", ink2, s2);
}
