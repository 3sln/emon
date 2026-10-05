// P7.2 harness, Rust arm (NOT a gate): the health of the OCR blocks of one scanned page, on kin's own ink layers.
//
//     health PAGE W H CH DPI OCR TRUTH.gray...
//
// PAGE is raw 8-bit RGB (CH 3) or gray (CH 1), tightly packed, DPI the resolution the engine read it at (130 for P6.6's overlay
// scans), OCR a text file of integers: the engine's answer record exactly as `tools/ocr/record.clj` records it (the first two cells of
// `tools/fusion/record_health.clj`'s files, the width and height, are NOT in it). Each TRUTH is a raw 1-channel 0/255 mask of the
// page's size. What this does is what the page unit will do once P7.3 wires it: `nm-view` on the raster, `sc-answer` for the OCR
// evidence, `nm-feat-ocr` for every word, `hl-page`. It prints, one line a block of the OCR:
//
//     B <block> <score> <ink> <plausible> <script> <body> <seal> <artifact> <x0> <y0> <x1> <y1> <words> <ink px> <truth px>...
//
// the box in pixels, the number of words, the pixels of kin's binary inside the box and, per TRUTH file, the pixels of the mask
// inside it. `tools/fusion/ghost_health.clj` reads the lines and says what the scores separate.
#![allow(dead_code, unused_mut, unused_variables)]
mod emon;
mod port;
use emon::evidence::*;
use emon::health::*;
use emon::norm::*;
use emon::player::*;
use emon::scan::sc_answer;
use port::*;

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let bytes = std::fs::read(&a[1]).unwrap();
    let w: i32 = a[2].parse().unwrap();
    let h: i32 = a[3].parse().unwrap();
    let ch: i32 = a[4].parse().unwrap();
    let dpi: i32 = a[5].parse().unwrap();
    let ints: Vec<i32> = std::fs::read_to_string(&a[6]).unwrap().split_whitespace().map(|t| t.parse().unwrap()).collect();
    let truths: Vec<Vec<u8>> = a[7..].iter().map(|f| std::fs::read(f).unwrap()).collect();
    let dpi100 = dpi * 100;
    let src = Bitmap::from_bytes(w, h, ch, &bytes);
    let gray = Bitmap::new(w, h, 1);
    let binary = Bitmap::new(w, h, 1);
    let mask = Bitmap::new(w, h, 1);
    let eng = Bitmap::new(w, h, 1);
    let mut ws = Cells::new();
    let mut sv = Cells::new();
    let mut aux = Cells::new();
    let mut views = Cells::new();
    let mut inks = Cells::new();
    let mut epages = Cells::new();
    let mut etext = Cells::new();
    let mut feats = Cells::new();
    let mut oruns = Cells::new();
    let mut oblocks = Cells::new();
    let mut olines = Cells::new();
    let mut owords = Cells::new();
    let mut osyms = Cells::new();
    let mut oalts = Cells::new();
    let mut an = Cells::new();
    for v in &ints {
        cells_push(&mut an, *v);
    }
    vw_new(&mut views, 1, vk_decode(), -1, -1, w, h, dpi100, 0, -1, 0, 0, 0, 0, 0);
    let nl = nm_view(&mut views, &mut inks, 1, 0, &src, &gray, &binary, &mut ws, &mut sv, &mut aux);
    // the body layer's mask (the first layer whose role is body; 1-based)
    let mut body_layer = 0;
    for i in 0..ik_count(&mut inks) {
        if ik_get(&mut inks, i, ikf_role()) == 1 && body_layer == 0 {
            body_layer = i + 1;
        }
    }
    if body_layer > 0 {
        nm_mask_into(&src, &mut ws, body_layer, &mask);
    }
    let wcpt = w * 7200 / dpi;
    let hcpt = h * 7200 / dpi;
    ep_new(&mut epages, 1, wcpt, hcpt);
    sc_answer(&mut an, 0, 1, 0, 2, 0, &mut views, &mut epages, &mut etext, &mut oruns, &mut oblocks, &mut olines, &mut owords, &mut osyms, &mut oalts, &mut feats);
    let nw = ow_count(&mut owords);
    for i in 0..nw {
        let f = ow_get(&mut owords, i, hd_feat());
        let x0 = ow_get(&mut owords, i, owf_px());
        let y0 = ow_get(&mut owords, i, owf_py());
        let x1 = x0 + ow_get(&mut owords, i, owf_pw());
        let y1 = y0 + ow_get(&mut owords, i, owf_ph());
        nm_feat_ocr(&mut feats, f, x0, y0, x1, y1, &binary, &eng, 0, &mask, &gray, 255);
    }
    let mut cg = Cells::new();
    let mut refs = Cells::new();
    let mut fw = Cells::new();
    let mut wd = Cells::new();
    let mut hl = Cells::new();
    let rows = hl_page(1, &mut cg, &mut refs, &mut fw, &mut wd, &mut etext, &mut feats, &mut oblocks, &mut olines, &mut owords, &mut hl);
    println!("S {} {} {} {}", nl, nw, ob_count(&mut oblocks), rows);
    for r in 0..hl_count(&mut hl) {
        if hl_get(&mut hl, r, hlf_kind()) != plh_ocr_block() {
            continue;
        }
        let b = hl_get(&mut hl, r, hlf_subject());
        // the box in pixels from the block's words: the union of the paragraphs' lines' words
        let (mut bx0, mut by0, mut bx1, mut by1) = (i32::MAX, i32::MAX, i32::MIN, i32::MIN);
        let mut nwords = 0;
        let pf = ob_get(&mut oblocks, b, obf_first());
        let pc = ob_get(&mut oblocks, b, obf_count());
        for p in pf..pf + pc {
            let lf = ob_get(&mut oblocks, p, obf_first());
            let lc = ob_get(&mut oblocks, p, obf_count());
            for l in lf..lf + lc {
                let wf = ol_get(&mut olines, l, lh_first());
                let wc = ol_get(&mut olines, l, lh_count());
                for wi in wf..wf + wc {
                    let x0 = ow_get(&mut owords, wi, owf_px());
                    let y0 = ow_get(&mut owords, wi, owf_py());
                    let x1 = x0 + ow_get(&mut owords, wi, owf_pw());
                    let y1 = y0 + ow_get(&mut owords, wi, owf_ph());
                    bx0 = bx0.min(x0);
                    by0 = by0.min(y0);
                    bx1 = bx1.max(x1);
                    by1 = by1.max(y1);
                    nwords += 1;
                }
            }
        }
        if nwords == 0 {
            continue;
        }
        let (cx0, cy0, cx1, cy1) = (bx0.max(0), by0.max(0), bx1.min(w), by1.min(h));
        let mut inkpx = 0i64;
        let mut tpx = vec![0i64; truths.len()];
        for y in cy0..cy1 {
            for x in cx0..cx1 {
                if bitmap_u8(&binary, x, y, 0) != 0 {
                    inkpx += 1;
                }
                for (k, t) in truths.iter().enumerate() {
                    if t[(y * w + x) as usize] != 0 {
                        tpx[k] += 1;
                    }
                }
            }
        }
        let ts: Vec<String> = tpx.iter().map(|c| c.to_string()).collect();
        println!(
            "B {} {} {} {} {} {} {} {} {} {} {} {} {} {} {}",
            b,
            hl_get(&mut hl, r, hlf_score()),
            hl_get(&mut hl, r, hob_ink()),
            hl_get(&mut hl, r, hob_plausible()),
            hl_get(&mut hl, r, hob_script()),
            hl_get(&mut hl, r, hob_body()),
            hl_get(&mut hl, r, hob_seal()),
            hl_get(&mut hl, r, hob_artifact()),
            bx0, by0, bx1, by1, nwords, inkpx, ts.join(" ")
        );
    }
}
