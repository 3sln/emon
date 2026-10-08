// P10.3 (D860): one comparator per bet. Each bet runs the kin-generated Rust (gen/rust/emon, over the host's
// port.rs) and a NATIVE reference on the SAME input in ONE process, interleaved (kin, ref, kin, ref ...) so
// that load drift hits both alike, and prints one JSON line per (bet, input, reference): the MEDIAN of N
// repetitions of each, their ratio, the minimum of each, and whether the two answers agreed.
//
//     emon-perf [--reps N] [--only BET[,BET..]] [--page FILE W H] [--quick]
//
// NOT a gate. Single thread. Every output is checked against the reference's (a comparator that measures a
// different computation measures nothing); a disagreement is reported in `check`, not hidden.
#![allow(dead_code, unused_mut, unused_variables, unused_imports, unused_assignments, unused_parens)]
mod emon;
#[path = "../../../host/rust/src/port.rs"]
mod port;
mod refs;
mod inputs;

use std::io::{Read, Write};
use std::time::Instant;

use inputs::*;
use port::{Bitmap, Buf, Cells};

// ------------------------------------------------------------------------------ timing

fn ms_since(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

fn median(v: &mut Vec<f64>) -> f64 {
    v.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let n = v.len();
    if n % 2 == 1 { v[n / 2] } else { (v[n / 2 - 1] + v[n / 2]) / 2.0 }
}

struct Cfg {
    reps: usize,
    only: Option<Vec<String>>,
    page: Option<(String, usize, usize)>,
    quick: bool,
}

impl Cfg {
    fn wants(&self, bet: &str) -> bool {
        match &self.only {
            None => true,
            Some(v) => v.iter().any(|b| b == bet),
        }
    }
}

/// Time `kin` and `rf` alternately: one warm-up of each, then `reps` pairs. Answers the two medians, minimums
/// and the median of the per-pair ratios.
fn pair(reps: usize, mut kin: impl FnMut(), mut rf: impl FnMut()) -> (f64, f64, f64, f64, f64) {
    multi(reps, &mut kin, &mut [&mut rf]).remove(0)
}

/// One kin function against several references: each repetition times kin once, then each reference, so the
/// kin sample is shared (and an expensive kin run is not repeated per reference).
fn multi(reps: usize, kin: &mut dyn FnMut(), refs: &mut [&mut dyn FnMut()]) -> Vec<(f64, f64, f64, f64, f64)> {
    kin();
    for r in refs.iter_mut() {
        r();
    }
    let mut ks = Vec::new();
    let mut rs: Vec<Vec<f64>> = refs.iter().map(|_| Vec::new()).collect();
    for _ in 0..reps {
        let t = Instant::now();
        kin();
        ks.push(ms_since(t));
        for (i, r) in refs.iter_mut().enumerate() {
            let t = Instant::now();
            r();
            rs[i].push(ms_since(t));
        }
    }
    let kmin = ks.iter().cloned().fold(f64::MAX, f64::min);
    let mut res = Vec::new();
    for r in rs.iter_mut() {
        let qs: Vec<f64> = ks.iter().zip(r.iter()).map(|(k, r)| k / r.max(1e-9)).collect();
        let rmin = r.iter().cloned().fold(f64::MAX, f64::min);
        let mut q = qs;
        res.push((0.0, median(r), median(&mut q), kmin, rmin));
    }
    let km = median(&mut ks);
    for x in res.iter_mut() {
        x.0 = km;
    }
    res
}

#[allow(clippy::too_many_arguments)]
fn report(bet: &str, input: &str, reference: &str, bytes: usize, reps: usize, t: (f64, f64, f64, f64, f64), check: &str, note: &str) {
    let ratio = t.0 / t.1.max(1e-9);
    println!(
        "{{\"bet\":\"{}\",\"input\":\"{}\",\"ref\":\"{}\",\"bytes\":{},\"reps\":{},\"kin_ms\":{:.3},\"ref_ms\":{:.3},\"ratio\":{:.3},\"pair_ratio\":{:.3},\"kin_min\":{:.3},\"ref_min\":{:.3},\"min_ratio\":{:.3},\"check\":\"{}\",\"note\":\"{}\"}}",
        bet, input, reference, bytes, reps, t.0, t.1, ratio, t.2, t.3, t.4, t.3 / t.4.max(1e-9), check, note
    );
    let _ = std::io::stdout().flush();
}

fn cells_to_bytes(c: &Cells) -> Vec<u8> {
    port::cells_bytes(c)
}

// ------------------------------------------------------------------------------ inflate

fn bet_inflate(cfg: &Cfg, set: &Inputs) {
    for (name, raw) in set.flate_inputs() {
        let z = refs::zlib_compress(&raw, 6);
        let out = std::cell::RefCell::new(Cells::new());
        let mut st = Cells::new();
        let mut kin = || {
            let mut out = out.borrow_mut();
            port::cells_drop(&mut out, 0);
            let s = emon::inflate::iz_zlib(&z, 0, z.len() as i32, &mut out, &mut st);
            std::hint::black_box(s);
        };
        let mut o1: Vec<u8> = Vec::new();
        let mut r1 = || {
            o1 = refs::zlib_decompress_flate2(&z, raw.len());
            std::hint::black_box(&o1);
        };
        let mut o2: Vec<u8> = Vec::new();
        let mut r2 = || {
            o2 = refs::zlib_decompress_fdeflate(&z);
            std::hint::black_box(&o2);
        };
        let t = multi(cfg.reps, &mut kin, &mut [&mut r1, &mut r2]);
        let ok = cells_to_bytes(&out.borrow()) == raw && o1 == raw;
        report("inflate", &name, "flate2(miniz_oxide)", raw.len(), cfg.reps, t[0], if ok { "ok" } else { "MISMATCH" }, "kin iz-zlib vs flate2 ZlibDecoder");
        let ok = o2 == raw;
        report("inflate", &name, "fdeflate", raw.len(), cfg.reps, t[1], if ok { "ok" } else { "MISMATCH" }, "kin iz-zlib vs fdeflate (the png crate's decoder)");
    }
}

// ------------------------------------------------------------------------------ deflate

fn bet_deflate(cfg: &Cfg, set: &Inputs) {
    for (name, raw) in set.flate_inputs() {
        let mut ws = Cells::new();
        let mut out = Buf::new();
        let mut kin = || {
            emon::deflate::df_init(&mut ws);
            out = Buf::new();
            emon::deflate::df_write_blob(&mut ws, &mut out, &raw, 0, raw.len() as i32);
            emon::deflate::df_finish(&mut ws, &mut out);
        };
        let mut rz: Vec<u8> = Vec::new();
        let mut r1 = || {
            rz = refs::zlib_compress(&raw, 6);
            std::hint::black_box(&rz);
        };
        let t = pair(cfg.reps, &mut kin, &mut r1);
        let back = refs::zlib_decompress_flate2(port::buf_blob(&out), raw.len());
        let ok = back == raw;
        let note = format!("kin df-* (deterministic writer) vs flate2 level 6; size kin {} B vs ref {} B ({:.3})", out.0.len(), rz.len(), out.0.len() as f64 / rz.len() as f64);
        report("deflate", &name, "flate2(miniz_oxide,6)", raw.len(), cfg.reps, t, if ok { "ok" } else { "MISMATCH" }, &note);
    }
}

// ------------------------------------------------------------------------------ png write (filters + deflate)

fn bet_png_write(cfg: &Cfg, set: &Inputs) {
    let (page, w, h) = (&set.page, set.pw, set.ph);
    let (photo, pw, ph) = (&set.photo, set.photo_w, set.photo_h);
    let imgs: Vec<(&str, &Vec<u8>, usize, usize, usize)> = vec![("page-gray", page, w, h, 1), ("photo-rgb-1200x800", photo, pw, ph, 3)];
    for (name, px, w, h, ch) in imgs {
        {
            let mut ws = Cells::new();
            let mut zb = Buf::new();
            let mut out = Buf::new();
            let mut row = Cells::new();
            let mut pal = Cells::new();
            let mut kin = || {
                out = Buf::new();
                zb = Buf::new();
                emon::pngprobe::pp_page(&mut ws, &mut out, &mut zb, px, w as i32, h as i32, ch as i32, 0, &mut row, &mut pal);
            };
            let mut bal: Vec<u8> = Vec::new();
            let mut r1 = || {
                bal = refs::png_encode(px, w, h, ch, png::Compression::Balanced);
                std::hint::black_box(&bal);
            };
            let mut fast: Vec<u8> = Vec::new();
            let mut r2 = || {
                fast = refs::png_encode(px, w, h, ch, png::Compression::Fast);
                std::hint::black_box(&fast);
            };
            let t = multi(cfg.reps, &mut kin, &mut [&mut r1, &mut r2]);
            let (dw, dh, dpx) = refs::png_decode(port::buf_blob(&out));
            let ok = dw == w && dh == h && &dpx == px;
            for (i, (rname, rf_out)) in [("png(balanced,adaptive)", &bal), ("png(fast,adaptive)", &fast)].iter().enumerate() {
                let note = format!("kin png-row filter choice + df-* vs the png crate, Filter::Adaptive; file size kin {} B vs ref {} B ({:.3})", out.0.len(), rf_out.len(), out.0.len() as f64 / rf_out.len() as f64);
                report("png-write", name, rname, px.len(), cfg.reps, t[i], if ok { "ok" } else { "MISMATCH" }, &note);
            }
        }
    }
}

// ------------------------------------------------------------------------------ png unfilter (inflate + predictor)

fn bet_png_read(cfg: &Cfg, set: &Inputs) {
    let (page, w, h) = (&set.page, set.pw, set.ph);
    let file = refs::png_encode(page, w, h, 1, png::Compression::Balanced);
    let idat = refs::png_idat(&file);
    let mut out = Cells::new();
    let mut st = Cells::new();
    let mut un = Cells::new();
    let mut kin = || {
        port::cells_drop(&mut out, 0);
        port::cells_drop(&mut un, 0);
        emon::inflate::iz_zlib(&idat, 0, idat.len() as i32, &mut out, &mut st);
        let n = port::cells_len(&out);
        emon::pdffilter::pf_predict(&mut out, 0, n, &mut un, 15, 1, 8, w as i32);
    };
    let mut dpx: Vec<u8> = Vec::new();
    let mut r1 = || {
        let (_, _, p) = refs::png_decode(&file);
        dpx = p;
        std::hint::black_box(&dpx);
    };
    let t = pair(cfg.reps, &mut kin, &mut r1);
    let ok = cells_to_bytes(&un) == *page && dpx == *page;
    report("png-unfilter", "page-gray", "png(decode)", page.len(), cfg.reps, t, if ok { "ok" } else { "MISMATCH" }, "kin iz-zlib + pf-predict (PNG predictor 15) vs the png crate's whole decode of the same file");
}

// ------------------------------------------------------------------------------ lzw

fn bet_lzw(cfg: &Cfg, set: &Inputs) {
    for (name, raw) in [("content-text", &set.content), ("binary-font", &set.binary)] {
        let z = refs::lzw_encode(raw);
        let mut out = Cells::new();
        let mut st = Cells::new();
        let mut kin = || {
            port::cells_drop(&mut out, 0);
            let s = emon::pdffilter::pf_lzw(&z, 0, z.len() as i32, &mut out, &mut st, 1);
            std::hint::black_box(s);
        };
        let mut o: Vec<u8> = Vec::new();
        let mut r1 = || {
            o = refs::lzw_decode(&z, 1);
            std::hint::black_box(&o);
        };
        let t = pair(cfg.reps, &mut kin, &mut r1);
        let ok = cells_to_bytes(&out) == *raw && o == *raw;
        report("lzw", name, "hand-written Rust (prefix/suffix table)", raw.len(), cfg.reps, t, if ok { "ok" } else { "MISMATCH" }, "kin pf-lzw (EarlyChange 1) vs a hand-written decoder of the same algorithm");
    }
}

// ------------------------------------------------------------------------------ raster: Otsu, Sauvola, union-find labelling

fn bet_raster(cfg: &Cfg, set: &Inputs) {
    let (w, h) = (set.pw as i32, set.ph as i32);
    let g = Bitmap::from_bytes(w, h, 1, &set.page);
    let m = Bitmap::new(w, h, 1);
    let m2 = Bitmap::new(w, h, 1);
    let mut hist = Cells::new();
    let mut labels = Cells::new();
    let mut parent = Cells::new();
    let mut area = Cells::new();
    let mut stats = Cells::new();
    let mut stats2 = Cells::new();
    let mut isum = Cells::new();
    let mut isq = Cells::new();
    let mut sqt = Cells::new();
    emon::raster::ras_sqrt_table(&mut sqt);
    let mut nat = refs::NativeRaster::new(&set.page, set.pw, set.ph);
    // The production form is the bulk one (D092 adopted its four streaming forms); the per-pixel `port` form
    // is the bet D092 measured. Both are timed. `skip` is the labeller that skips paper with next-nonzero.
    for variant in ["bulk", "port"] {
        let bulk = variant == "bulk";
        // phase functions on the kin side
        macro_rules! phase {
            ($name:expr, $kin:expr, $nat:expr, $check:expr, $note:expr) => {{
                let t = pair(cfg.reps, &mut $kin, &mut $nat);
                let ok: bool = $check;
                report($name, &format!("page-{}x{}/{}", w, h, variant), "hand-written Rust (safe, autovectorised)", set.page.len(), cfg.reps, t, if ok { "ok" } else { "MISMATCH" }, $note);
            }};
        }
        // histogram + Otsu + binarise (streaming passes)
        let mut thr_k = 0;
        let mut ink_k = 0;
        {
            let mut kin = || {
                if bulk { emon::raster::ras_histogram_bulk(&g, &mut hist); } else { emon::raster::ras_histogram(&g, &mut hist); }
                thr_k = emon::raster::ras_otsu(&mut hist, w * h);
                ink_k = if bulk { emon::raster::ras_binarise_bulk(&g, &m, thr_k) } else { emon::raster::ras_binarise(&g, &m, thr_k) };
            };
            let mut nt = || nat.binarise();
            phase!("raster-binarise", kin, nt, thr_k == nat.thr && ink_k == nat.ink, "histogram + Otsu + threshold (the streaming passes the bulk forms replace)");
        }
        // two-pass union-find labelling of that mask
        {
            emon::raster::ras_binarise_bulk(&g, &m, thr_k);
            let mut kin = || {
                if bulk { emon::raster::ras_alloc_bulk(&mut labels, w * h); } else { emon::raster::ras_alloc(&mut labels, w * h); }
                if bulk { emon::raster::ras_label_skip(&m, &mut labels, &mut parent, &mut area, &mut stats); } else { emon::raster::ras_label(&m, &mut labels, &mut parent, &mut area, &mut stats); }
            };
            let mut nt = || nat.label();
            let same = |a: &Cells, b: &Vec<i32>| -> bool { port::cells_len(a) as usize == b.len() && (0..b.len()).all(|i| port::cells_at(a, i as i32) == b[i]) };
            phase!("raster-label", kin, nt, same(&stats, &nat.stats), "alloc + 8-connected two-pass union-find labelling (area, count, checksum compared)");
        }
        // integral images + Sauvola
        {
            let mut ink2 = 0;
            let mut kin = || {
                if bulk { emon::raster::ras_integral_bulk(&g, &mut isum, &mut isq); } else { emon::raster::ras_integral(&g, &mut isum, &mut isq); }
                ink2 = emon::raster::ras_sauvola(&g, &mut isum, &mut isq, &mut sqt, &m2);
            };
            let mut nt = || nat.sauvola();
            phase!("raster-sauvola", kin, nt, ink2 == nat.ink2, "two integral images + integer Sauvola r=15 k=87/256");
        }
    }
}

// ------------------------------------------------------------------------------ tracer core: runs + union-find + component table

fn bet_tracer(cfg: &Cfg, set: &Inputs) {
    let (w, h) = (set.pw as i32, set.ph as i32);
    let mut nat = refs::NativeRaster::new(&set.page, set.pw, set.ph);
    nat.binarise();
    let mask = Bitmap::from_bytes(w, h, 1, &nat.mask);
    let mut rn = Cells::new();
    let mut ri = Cells::new();
    let mut cs = Cells::new();
    let (mut s1, mut s2, mut s3) = (Cells::new(), Cells::new(), Cells::new());
    let mut nc_k = 0;
    let mut kin = || {
        let n = emon::trace::tr_runs(&mask, &mut rn, &mut ri);
        emon::trace::tr_unify(&mut rn, &mut ri, h);
        nc_k = emon::trace::tr_label(&mut rn, &mut ri, h, &mut cs, &mut s1, &mut s2, &mut s3);
    };
    let mut tr = refs::NativeTrace::new();
    let mut nt = || tr.run(&nat.mask, set.pw, set.ph);
    let t = pair(cfg.reps, &mut kin, &mut nt);
    let mut ok = nc_k as usize == tr.comps.len();
    if ok {
        for (i, c) in tr.comps.iter().enumerate() {
            let g = |f: i32| port::cells_at(&cs, i as i32 * 24 + f);
            if [g(0), g(1), g(2), g(3), g(4), g(5), g(6), g(13)] != *c {
                ok = false;
                break;
            }
        }
    }
    report("tracer-core", &format!("page-{}x{}/otsu-mask", w, h), "hand-written Rust (row runs + union-find)", set.page.len(), cfg.reps, t, if ok { "ok" } else { "MISMATCH" }, "kin tr-runs + tr-unify + tr-label vs the same three steps by hand; component table compared cell for cell");
}

// ------------------------------------------------------------------------------ main

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let mut cfg = Cfg { reps: 7, only: None, page: None, quick: false };
    let mut i = 1;
    while i < a.len() {
        match a[i].as_str() {
            "--reps" => { cfg.reps = a[i + 1].parse().unwrap(); i += 1; }
            "--only" => { cfg.only = Some(a[i + 1].split(',').map(|s| s.to_string()).collect()); i += 1; }
            "--page" => { cfg.page = Some((a[i + 1].clone(), a[i + 2].parse().unwrap(), a[i + 3].parse().unwrap())); i += 3; }
            "--quick" => cfg.quick = true,
            x => { eprintln!("unknown argument {}", x); std::process::exit(2); }
        }
        i += 1;
    }
    let set = Inputs::build(&cfg.page, cfg.quick);
    println!("{{\"inputs\":{{\"page\":\"{}\",\"page_w\":{},\"page_h\":{},\"page_fnv\":\"{:016x}\",\"content_fnv\":\"{:016x}\",\"binary_fnv\":\"{:016x}\",\"photo_fnv\":\"{:016x}\"}}}}",
        set.page_source, set.pw, set.ph, fnv(&set.page), fnv(&set.content), fnv(&set.binary), fnv(&set.photo));
    if cfg.wants("inflate") { bet_inflate(&cfg, &set); }
    if cfg.wants("deflate") { bet_deflate(&cfg, &set); }
    if cfg.wants("png-write") { bet_png_write(&cfg, &set); }
    if cfg.wants("png-unfilter") { bet_png_read(&cfg, &set); }
    if cfg.wants("lzw") { bet_lzw(&cfg, &set); }
    if cfg.wants("raster") { bet_raster(&cfg, &set); }
    if cfg.wants("tracer") { bet_tracer(&cfg, &set); }
}
