#!/usr/bin/env python3
"""Rule and fill truth for the graphics corpus (DEVLOG P6.8, D227, SPEC 14.3).   Tooling, not runtime: nothing emon runs depends on it.

    python3 tools/oracle/rules.py [STEM ...]      write test/corpus/synth/gold/STEM.rules.json (default: every stem of GRAPHICS)
    python3 tools/oracle/rules.py --check [STEM]  fail if a committed file is not what this writes (needs pdfplumber and poppler)

THE TRUTH IS NOT EMON'S (D100): pdfplumber (pdfminer.six; D094's third-party-oracle exception, installed by hand with
`pip install --target ~/.cache/emon/pylib pdfplumber`) reads the page's `lines`, `rects` and straight `curves`, and poppler's
render (`pdftoppm -r 150`) decides what is SEEN: a candidate is kept only where the render has ink under it.

  * a RULE is a pdfplumber line, a straight curve (every point within 0.5 pt of its chord), a thin filled rectangle (its
    short side <= 3 pt and its long side >= 4 times that: its centre line, thickness = the short side), or an edge of a
    stroked rectangle. Along its centre line, every pixel is a sample; a sample has INK when the pixel (or one a pixel
    either side of the line) differs by at least 16 (largest channel difference) from BOTH pixels two pixels away on each
    side of the line (so a white rule on a dark fill counts, a white rule on white or a rule that is not drawn does not).
    Runs of ink, bridging gaps of at most 8 px, of at least 3 px, are the truth: a rule clipped, hidden in an optional
    content group that is off, painted over, at alpha 0 or under a soft mask comes out shorter or absent.
  * a FILL is a pdfplumber rectangle with a fill colour that differs from white by at least 16, kept when half of a grid of
    sample pixels inside it also differ from white by at least 16 in the render.
  * what pdfplumber cannot read (shadings, patterns, images drawn as rules, the rules of a table drawn in an image) comes from
    `gold/STEM.rules-gen.json`, written by the generator that drew it (top-left page space in points; an `images` entry
    carries rules and fills in the image's pixels, placed through the PDF's own image placement, which pdfplumber reads), and
    goes through the same render check.

The output is `gold/STEM.rules.json`: per page `rules` [x0, y0, x1, y1, thickness, style] and `fills` [x0, y0, x1, y1,
"#rrggbb"], in centi-points (integers), page space as displayed (origin top-left, y down: SPEC 3.9). A scan variant's
truth is NOT stored: the scorer maps its twin's through the variant's dpi, MANIFEST `facts.skew*` and `facts.turns`."""
import json
import math
import os
import subprocess
import sys
import tempfile

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CORPUS = os.path.join(ROOT, "test", "corpus", "synth")
for lib in (os.environ.get("EMON_PYLIB"), os.path.expanduser("~/.cache/emon/pylib")):
    if lib and os.path.isdir(lib):
        sys.path.insert(0, lib)
import pdfplumber  # noqa: E402
from PIL import Image  # noqa: E402

DPI = 150
S = DPI / 72.0
INK = 16
BRIDGE = 8
MIN_RUN = 3


def diff(a, b):
    return max(abs(a[i] - b[i]) for i in range(3))


def color_hex(c):
    """pdfplumber colour (gray, rgb or cmyk tuple, or None) -> (r, g, b) or None."""
    if c is None or isinstance(c, str):
        return None
    c = tuple(c)
    if len(c) == 1:
        v = round(c[0] * 255)
        return (v, v, v)
    if len(c) == 3:
        return tuple(round(x * 255) for x in c)
    if len(c) == 4:
        k = c[3]
        return tuple(round((1 - c[i]) * (1 - k) * 255) for i in range(3))
    return None


def hexs(rgb):
    return "#%02x%02x%02x" % rgb


class Page:
    def __init__(self, img, width, height):
        self.img = img.convert("RGB")
        self.px = self.img.load()
        self.w, self.h = self.img.size
        self.width, self.height = width, height

    def at(self, x, y):
        xi = min(max(int(math.floor(x)), 0), self.w - 1)
        yi = min(max(int(math.floor(y)), 0), self.h - 1)
        return self.px[xi, yi]

    def runs(self, x0, y0, x1, y1, thick):
        """Visible stretches of the centre line (page points) -> [(t0, t1)] as fractions, and whether gaps were bridged."""
        ax, ay, bx, by = x0 * S, y0 * S, x1 * S, y1 * S
        length = math.hypot(bx - ax, by - ay)
        n = max(int(length), 1)
        if length < 1e-6:
            return [], False
        ux, uy = (bx - ax) / length, (by - ay) / length
        nx, ny = -uy, ux
        off = max(2.0, thick * S / 2 + 2.0)
        flags = []
        for i in range(n + 1):
            t = min(i, length)
            cx, cy = ax + ux * t, ay + uy * t
            s1 = self.at(cx + nx * off, cy + ny * off)
            s2 = self.at(cx - nx * off, cy - ny * off)
            ink = False
            for d in (0.0, 1.0, -1.0):
                c = self.at(cx + nx * d, cy + ny * d)
                if diff(c, s1) >= INK and diff(c, s2) >= INK:
                    ink = True
                    break
            flags.append(ink)
        out, start, last, gaps = [], None, None, 0
        for i, f in enumerate(flags):
            if f:
                if start is None:
                    start = i
                elif i - last > 4:
                    gaps += 1          # a bridged gap of 4 px or more: a dash gap, not a crossing rule
                last = i
            elif start is not None and i - last > BRIDGE:
                if last - start + 1 >= MIN_RUN:
                    out.append((start, last))
                start = None
        if start is not None and last - start + 1 >= MIN_RUN:
            out.append((start, last))
        return [(a / length, min(b / length, 1.0)) for a, b in out], gaps >= 3

    def visible_fraction(self, box):
        x0, y0, x1, y1 = box
        ax, ay, bx, by = x0 * S + 1, y0 * S + 1, x1 * S - 1, y1 * S - 1
        if bx <= ax or by <= ay:
            return 0.0
        k = 15
        hit = tot = 0
        for i in range(k):
            for j in range(k):
                c = self.at(ax + (bx - ax) * (i + 0.5) / k, ay + (by - ay) * (j + 0.5) / k)
                tot += 1
                if diff(c, (255, 255, 255)) >= INK:
                    hit += 1
        return hit / tot


def cpt(v):
    return int(round(v * 100))


def keep_rule(page, x0, y0, x1, y1, thick, out):
    runs, bridged = page.runs(x0, y0, x1, y1, thick)
    for a, b in runs:
        out.append([cpt(x0 + (x1 - x0) * a), cpt(y0 + (y1 - y0) * a), cpt(x0 + (x1 - x0) * b), cpt(y0 + (y1 - y0) * b),
                    cpt(thick), "dashed" if bridged else "solid"])


def collinear(pts, tol=0.5):
    (ax, ay), (bx, by) = pts[0], pts[-1]
    d = math.hypot(bx - ax, by - ay)
    if d < 1e-6:
        return False
    return all(abs((bx - ax) * (ay - y) - (ax - x) * (by - ay)) / d <= tol for x, y in pts)


def plumber_candidates(pg):
    """-> (rules [(x0, y0, x1, y1, thick)], fills [(x0, y0, x1, y1, rgb)]) in top-left points."""
    rules, fills = [], []
    for ln in pg.lines:
        p = ln["pts"]
        rules.append((p[0][0], p[0][1], p[-1][0], p[-1][1], ln.get("linewidth") or 0.0))
    for cv in pg.curves:
        p = [tuple(q) for q in cv["pts"]]
        if len(p) >= 2 and collinear(p) and not cv.get("fill"):
            rules.append((p[0][0], p[0][1], p[-1][0], p[-1][1], cv.get("linewidth") or 0.0))
    for r in pg.rects:
        x0, x1, t, b = r["x0"], r["x1"], r["top"], r["bottom"]
        w, h = x1 - x0, b - t
        short = min(w, h)
        if r.get("fill") and short <= 3.0 and max(w, h) >= 4 * max(short, 0.01):
            if w >= h:
                rules.append((x0, (t + b) / 2, x1, (t + b) / 2, h))
            else:
                rules.append(((x0 + x1) / 2, t, (x0 + x1) / 2, b, w))
            continue
        if r.get("stroke"):
            lw = r.get("linewidth") or 0.0
            rules += [(x0, t, x1, t, lw), (x0, b, x1, b, lw), (x0, t, x0, b, lw), (x1, t, x1, b, lw)]
        if r.get("fill"):
            rgb = color_hex(r.get("non_stroking_color"))
            if rgb is not None:
                fills.append((x0, t, x1, b, rgb))
    return rules, fills


def generator_candidates(gen, pageno, pg):
    """The generator's own rules and fills of one page (top-left points)."""
    rules, fills = [], []
    if not gen:
        return rules, fills
    for r in (gen.get("rules") or []):
        if r.get("page", 1) == pageno:
            rules.append((r["x0"], r["y0"], r["x1"], r["y1"], r["t"]))
    for f in (gen.get("fills") or []):
        if f.get("page", 1) == pageno:
            h = f["colour"].lstrip("#")
            fills.append(tuple(f["box"]) + (tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)),))
    for im in (gen.get("images") or []):
        w, h = im["px"]
        for cand in pg.images:
            if tuple(cand.get("srcsize") or ()) != (w, h):
                continue
            bx0, bx1, bt, bb = cand["x0"], cand["x1"], cand["top"], cand["bottom"]
            sx, sy = (bx1 - bx0) / w, (bb - bt) / h
            for (x0, y0, x1, y1, th) in im.get("rules", []):
                rules.append((bx0 + x0 * sx, bt + y0 * sy, bx0 + x1 * sx, bt + y1 * sy, th * (sx + sy) / 2))
            for (x0, y0, x1, y1, col) in im.get("fills", []):
                hh = col.lstrip("#")
                fills.append((bx0 + x0 * sx, bt + y0 * sy, bx0 + x1 * sx, bt + y1 * sy, tuple(int(hh[i:i + 2], 16) for i in (0, 2, 4))))
    return rules, fills


def truth(stem):
    pdf = os.path.join(CORPUS, "pdf", stem + ".pdf")
    genp = os.path.join(CORPUS, "gold", stem + ".rules-gen.json")
    gen = json.load(open(genp)) if os.path.exists(genp) else None
    pages = []
    with tempfile.TemporaryDirectory(prefix="emon-rules-") as tmp:
        subprocess.run(["pdftoppm", "-r", str(DPI), "-png", pdf, os.path.join(tmp, "p")], check=True)
        pngs = sorted(f for f in os.listdir(tmp) if f.endswith(".png"))
        with pdfplumber.open(pdf) as doc:
            for i, pg in enumerate(doc.pages):
                page = Page(Image.open(os.path.join(tmp, pngs[i])), pg.width, pg.height)
                pr, pf = plumber_candidates(pg)
                gr, gf = generator_candidates(gen, i + 1, pg)
                rules, fills = [], []
                for (x0, y0, x1, y1, th) in pr + gr:
                    keep_rule(page, x0, y0, x1, y1, th, rules)
                for (x0, y0, x1, y1, rgb) in pf + gf:
                    if x1 - x0 >= 0.9 * pg.width and y1 - y0 >= 0.9 * pg.height:
                        continue
                    if diff(rgb, (255, 255, 255)) >= INK and page.visible_fraction((x0, y0, x1, y1)) >= 0.5:
                        fills.append([cpt(x0), cpt(y0), cpt(x1), cpt(y1), hexs(rgb)])
                rules = sorted(set(map(tuple, rules)))
                fills = sorted(set(map(tuple, fills)))
                pages.append({"n": i + 1, "w": cpt(pg.width), "h": cpt(pg.height),
                              "rules": [list(r) for r in rules], "fills": [list(f) for f in fills]})
    return {"stem": stem, "oracle": "pdfplumber %s, pdftoppm -r %d" % (pdfplumber.__version__, DPI), "pages": pages}


def dumps(t):
    lines = ["{", ' "stem": %s,' % json.dumps(t["stem"]), ' "oracle": %s,' % json.dumps(t["oracle"]), ' "pages": [']
    for i, p in enumerate(t["pages"]):
        lines.append('  {"n": %d, "w": %d, "h": %d,' % (p["n"], p["w"], p["h"]))
        lines.append('   "rules": [')
        lines.append(",\n".join("    " + json.dumps(r) for r in p["rules"]))
        lines.append("   ],")
        lines.append('   "fills": [')
        lines.append(",\n".join("    " + json.dumps(f) for f in p["fills"]))
        lines.append("   ]}" + ("," if i + 1 < len(t["pages"]) else ""))
    lines += [" ]", "}"]
    return "\n".join(lines) + "\n"


def stems():
    return [l.strip() for l in open(os.path.join(CORPUS, "GRAPHICS")) if l.strip() and not l.startswith("#")]


def main(argv):
    check = "--check" in argv
    names = [a for a in argv if not a.startswith("--")] or stems()
    bad = 0
    for s in names:
        t = dumps(truth(s))
        path = os.path.join(CORPUS, "gold", s + ".rules.json")
        if check:
            if not os.path.exists(path) or open(path).read() != t:
                print("  FAIL %s is not what rules.py writes" % path)
                bad += 1
        else:
            open(path, "w").write(t)
            n = sum(len(p["rules"]) for p in json.loads(t)["pages"])
            f = sum(len(p["fills"]) for p in json.loads(t)["pages"])
            print("  %-30s %4d rules %3d fills" % (s, n, f))
    if check:
        print("  ok   rules.py: %d rule-truth files are current" % len(names)) if not bad else sys.exit(1)


if __name__ == "__main__":
    main(sys.argv[1:])
