// P6.3 harness, Rust arm: kin-generated `emon::norm` over the `Bitmap` port.
//
//     norm PAGE W H CH N DPI100 [TRUTH.gray ...]
//
// PAGE is raw 8-bit pixels (CH 1 or 3, tightly packed). Prints `T <phase> <best ms>` per phase, then from one whole
// `nm-view`: `S <layers> <deskew>`, one `L` line a layer (count, role, lightness, chroma, colour), `H` lines (FNV-1a of
// the gray and the binary view, high and low halves) and, per TRUTH file (a raw 1-channel 0/255 mask of the page's size),
// `X <index> <truth pixels> <of them in paper> <in layer 1> ... <in layer K>`.
#![allow(dead_code, unused_mut, unused_variables)]
mod emon;
mod port;
use emon::evidence::*;
use emon::norm::*;
use emon::raster::{ras_integral_bulk, ras_sauvola, ras_sqrt_table};
use port::*;
use std::time::Instant;

fn ms(a: Instant, b: Instant) -> f64 {
    b.duration_since(a).as_secs_f64() * 1000.0
}

fn hash(b: &Bitmap) -> (i32, i32) {
    let mut o = Cells::new();
    cells_push(&mut o, 0);
    cells_push(&mut o, 0);
    bitmap_hash(b, &mut o, 0);
    (cells_at(&o, 0), cells_at(&o, 1))
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let bytes = std::fs::read(&a[1]).unwrap();
    let w: i32 = a[2].parse().unwrap();
    let h: i32 = a[3].parse().unwrap();
    let ch: i32 = a[4].parse().unwrap();
    let n: usize = a[5].parse().unwrap();
    let dpi100: i32 = a[6].parse().unwrap();
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
    let names = ["gray", "hist", "layers", "mask", "sauv", "skew", "view", "refsauv"];
    let mut best = [f64::MAX; 8];
    let refm = Bitmap::new(w, h, 1);
    let mut isum = Cells::new();
    let mut isq = Cells::new();
    let mut sqt = Cells::new();
    ras_sqrt_table(&mut sqt);
    let mut nl = 0;
    for _ in 0..n {
        let t0 = Instant::now();
        bitmap_gray_into(&src, &gray);
        let t1 = Instant::now();
        nm_ws_init(&mut ws);
        bitmap_hist_rgb(&src, &mut ws, nm_ws_hist());
        let t2 = Instant::now();
        nm_layers(&mut ws, nm_ink_merge(), nm_ink_paper(), nm_ink_min(), nm_ink_spike());
        nm_roles(&mut ws, nm_ink_light(), nm_ink_chroma(), nm_ink_bg());
        let t3 = Instant::now();
        nm_mask_into(&src, &mut ws, 1, &mask);
        let t4 = Instant::now();
        nm_sauvola(&gray, &binary, nm_sauvola_r(dpi100), nm_sauvola_k(), &mut sv);
        let t5 = Instant::now();
        let ang = nm_deskew(&binary, &mut aux, &mut sv);
        let t6 = Instant::now();
        views = Cells::new();
        inks = Cells::new();
        vw_new(&mut views, 0, vk_decode(), -1, -1, w, h, dpi100, 0, -1, 0, 0, 0, 0, 0);
        nl = nm_view(&mut views, &mut inks, 0, 0, &src, &gray, &binary, &mut ws, &mut sv, &mut aux);
        let t7 = Instant::now();
        // P2.7's whole-page Sauvola (r = 15, integral images of the whole page) for the same page: the banded form's reference
        ras_integral_bulk(&gray, &mut isum, &mut isq);
        ras_sauvola(&gray, &mut isum, &mut isq, &mut sqt, &refm);
        let t8 = Instant::now();
        let ph = [ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), ms(t4, t5), ms(t5, t6), ms(t6, t7), ms(t7, t8)];
        for k in 0..8 {
            if ph[k] < best[k] {
                best[k] = ph[k];
            }
        }
    }
    for k in 0..8 {
        println!("T {} {:.3}", names[k], best[k]);
    }
    println!("S {} {}", nl, vw_get(&mut views, 0, vwf_deskew()));
    for i in 0..ik_count(&mut inks) {
        println!(
            "L {} {} {} {} {} {}",
            i + 1,
            ik_get(&mut inks, i, ikf_count()),
            ik_get(&mut inks, i, ikf_role()),
            ik_get(&mut inks, i, ikf_light()),
            ik_get(&mut inks, i, ikf_chroma()),
            ik_get(&mut inks, i, ikf_color())
        );
    }
    let (gh, gl) = hash(&gray);
    let (bh, bl) = hash(&binary);
    println!("H gray {} {}", gh, gl);
    println!("H binary {} {}", bh, bl);
    nm_label_into(&src, &mut ws, &labels);
    for (ti, f) in a[7..].iter().enumerate() {
        let t = std::fs::read(f).unwrap();
        let mut cnt = vec![0i64; nl as usize + 1];
        let mut tot = 0i64;
        for y in 0..h {
            for x in 0..w {
                if t[(y * w + x) as usize] != 0 {
                    tot += 1;
                    cnt[bitmap_u8(&labels, x, y, 0) as usize] += 1;
                }
            }
        }
        let cs: Vec<String> = cnt.iter().map(|c| c.to_string()).collect();
        println!("X {} {} {}", ti, tot, cs.join(" "));
    }
}
