// The hand-written comparator for P2.7 (SPEC 12.3): the SAME algorithm as
// kin/raster.kin, written the way a Rust programmer would write it directly:
// slices and Vecs, no port calls, safe code (ordinary bounds checks, no
// `unsafe`, no get_unchecked), usize indices.
//
//     rustc -C opt-level=3 -o native native.rs && ./native PAGE W H N
//
// Output format is shared with the four kin arms (see bench/report.py).
use std::time::Instant;

fn histogram(g: &[u8], hist: &mut [i32; 256]) {
    for b in hist.iter_mut() {
        *b = 0;
    }
    for &v in g {
        hist[v as usize] += 1;
    }
}

fn otsu(hist: &[i32; 256], total: i32) -> i32 {
    let mut sum: i64 = 0;
    for t in 0..256 {
        sum += (t as i64) * (hist[t] as i64);
    }
    let (mut sb, mut wb) = (0i64, 0i64);
    let mut best: i64 = -1;
    let mut thr = 0;
    for t in 0..256 {
        let hv = hist[t] as i64;
        wb += hv;
        sb += (t as i64) * hv;
        let wf = total as i64 - wb;
        if wb > 0 && wf > 0 {
            let mb = sb / wb;
            let mf = (sum - sb) / wf;
            let d = mb - mf;
            let between = (wb * wf) * (d * d);
            if between > best {
                best = between;
                thr = t as i32;
            }
        }
    }
    thr
}

fn binarise(g: &[u8], m: &mut [u8], thr: i32) -> i32 {
    let mut ink = 0;
    for (p, q) in g.iter().zip(m.iter_mut()) {
        if (*p as i32) <= thr {
            *q = 255;
            ink += 1;
        } else {
            *q = 0;
        }
    }
    ink
}

fn find(parent: &mut [i32], a: i32) -> i32 {
    let mut r = a;
    let mut p = parent[r as usize];
    while p != r {
        let g = parent[p as usize];
        parent[r as usize] = g;
        r = g;
        p = parent[r as usize];
    }
    r
}

fn union(parent: &mut [i32], a: i32, b: i32) {
    if b != 0 {
        let ra = find(parent, a);
        let rb = find(parent, b);
        if ra < rb {
            parent[rb as usize] = ra;
        }
        if rb < ra {
            parent[ra as usize] = rb;
        }
    }
}

fn min_nz(a: i32, b: i32) -> i32 {
    if a == 0 {
        return b;
    }
    if b == 0 {
        return a;
    }
    if a < b {
        a
    } else {
        b
    }
}

fn label(
    m: &[u8],
    w: usize,
    h: usize,
    labels: &mut [i32],
    parent: &mut Vec<i32>,
    area: &mut Vec<i32>,
    stats: &mut Vec<i32>,
) -> i32 {
    parent.clear();
    parent.push(0);
    let mut ink = 0;
    let mut i = 0usize;
    for y in 0..h {
        for x in 0..w {
            if m[i] != 0 {
                ink += 1;
                let (mut la, mut lb, mut lc, mut ld) = (0, 0, 0, 0);
                if x > 0 {
                    la = labels[i - 1];
                    if y > 0 {
                        lb = labels[i - w - 1];
                    }
                }
                if y > 0 {
                    lc = labels[i - w];
                    if x < w - 1 {
                        ld = labels[i - w + 1];
                    }
                }
                let mn = min_nz(min_nz(la, lb), min_nz(lc, ld));
                if mn == 0 {
                    let nl = parent.len() as i32;
                    parent.push(nl);
                    labels[i] = nl;
                } else {
                    labels[i] = mn;
                    union(parent, mn, la);
                    union(parent, mn, lb);
                    union(parent, mn, lc);
                    union(parent, mn, ld);
                }
            }
            i += 1;
        }
    }
    let nl = parent.len();
    area.clear();
    area.resize(nl, 0);
    let mut cs: i64 = 0;
    for i in 0..w * h {
        let lv = labels[i];
        if lv != 0 {
            let r = find(parent, lv);
            labels[i] = r;
            area[r as usize] += 1;
            cs = cs.wrapping_mul(31).wrapping_add(r as i64);
        }
    }
    let (mut ncomp, mut maxa) = (0, 0);
    let mut sq: i64 = 0;
    for i in 1..nl {
        if parent[i] == i as i32 {
            ncomp += 1;
            let av = area[i];
            if av > maxa {
                maxa = av;
            }
            sq = sq.wrapping_add((av as i64).wrapping_mul(av as i64));
        }
    }
    stats.clear();
    stats.extend_from_slice(&[
        ncomp,
        ink,
        maxa,
        nl as i32 - 1,
        (cs >> 32) as i32,
        cs as i32,
        (sq >> 32) as i32,
        sq as i32,
    ]);
    ncomp
}

fn ms(a: Instant, b: Instant) -> f64 {
    b.duration_since(a).as_secs_f64() * 1000.0
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let g = std::fs::read(&a[1]).unwrap();
    let w: usize = a[2].parse().unwrap();
    let h: usize = a[3].parse().unwrap();
    let n: usize = a[4].parse().unwrap();
    assert_eq!(g.len(), w * h);
    let mut hist = [0i32; 256];
    let mut m = vec![0u8; w * h];
    let mut labels: Vec<i32> = Vec::new();
    let (mut parent, mut area, mut stats) = (Vec::new(), Vec::new(), Vec::new());
    let mut best = [f64::MAX; 6];
    let mut thr = 0;
    for _ in 0..n {
        let t0 = Instant::now();
        histogram(&g, &mut hist);
        let t1 = Instant::now();
        thr = otsu(&hist, (w * h) as i32);
        let t2 = Instant::now();
        binarise(&g, &mut m, thr);
        let t3 = Instant::now();
        labels.clear();
        labels.resize(w * h, 0);
        let t4 = Instant::now();
        label(&m, w, h, &mut labels, &mut parent, &mut area, &mut stats);
        let t5 = Instant::now();
        let ph = [ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), ms(t4, t5), ms(t0, t5)];
        for k in 0..6 {
            if ph[k] < best[k] {
                best[k] = ph[k];
            }
        }
    }
    let names = ["hist", "otsu", "bin", "alloc", "label", "total"];
    for k in 0..6 {
        println!("T {} {:.3}", names[k], best[k]);
    }
    let s: Vec<String> = stats.iter().map(|x| x.to_string()).collect();
    println!("S {} {}", thr, s.join(" "));
}
