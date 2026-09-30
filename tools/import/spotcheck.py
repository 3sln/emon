#!/usr/bin/env python3
"""Draw a gold file's regions on its page, to check the coordinate system by eye (DEVLOG P2.11).

    tools/import/spotcheck.py DATASET [ID ...]    (default: 2 items; needs Pillow; PDFs need pdftoppm)

Writes corpus/_gold/_spotcheck/<id>-p<N>.png.  A PDF page is rendered at 72 dpi, so one output pixel
is exactly one point and a region in points lands where its text is; a raster page is drawn as is
(1 px = 1 pt, nominal).  Colours: table red, cell blue, field/heading green, seal magenta, others orange.
Not a gate: a human looks at the picture.  Also prints, per item, how many regions lie outside the page.
"""
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C

COL = {"table": (220, 0, 0), "cell": (0, 70, 220), "field": (0, 150, 0), "heading": (0, 150, 0),
       "seal": (220, 0, 220), "figure": (150, 90, 0), "row": (0, 0, 0)}


def main(argv):
    for lib in (os.environ.get("EMON_PYLIB"), os.path.expanduser("~/.cache/emon/pylib")):
        if lib and os.path.isdir(lib):
            sys.path.append(lib)
    from PIL import Image, ImageDraw
    ds, ids = argv[0], argv[1:]
    gdir = os.path.join(C.OUT, ds)
    if not ids:
        names = sorted(f[:-10] for f in os.listdir(gdir) if f.endswith(".gold.json"))
        ids = [names[0], names[len(names) // 2]]
    outd = os.path.join(C.OUT, "_spotcheck")
    os.makedirs(outd, exist_ok=True)
    for gid in ids:
        g = json.load(open(os.path.join(gdir, gid + ".gold.json"), encoding="utf-8"))
        prov = g.get("provenance", {})
        regs = []

        def walk(n):
            for r in n.get("at", []):
                regs.append((n["kind"], r))
            for c in n.get("children", []):
                walk(c)
        walk(g["model"]["body"])
        pages = {}
        for p in g["model"]["pages"]:
            if any(r[0] == p["n"] for _, r in regs):
                pages[p["n"]] = p
        if not pages:
            pages = {1: g["model"]["pages"][0]}
        for n, p in pages.items():
            if "pdf" in prov:
                tmp = os.path.join(outd, "_tmp")
                subprocess.run(["pdftoppm", "-r", "72", "-f", str(n), "-l", str(n), "-png", "-singlefile",
                                os.path.join(C.CORPUS, prov["pdf"]), tmp], check=True)
                im = Image.open(tmp + ".png").convert("RGB")
            else:
                im = Image.open(os.path.join(C.CORPUS, prov["variants"]["image"])).convert("RGB")
            sx = im.width / p["w"]
            sy = im.height / p["h"]
            d = ImageDraw.Draw(im)
            out = 0
            for kind, r in regs:
                if r[0] != n:
                    continue
                if r[3] > p["w"] + 1 or r[4] > p["h"] + 1 or r[1] < -1 or r[2] < -1:
                    out += 1
                d.rectangle([r[1] * sx, r[2] * sy, r[3] * sx, r[4] * sy], outline=COL.get(kind, (255, 130, 0)), width=2)
            if max(im.size) > 1600:
                k = 1600 / max(im.size)
                im = im.resize((int(im.width * k), int(im.height * k)))
            path = os.path.join(outd, "%s-p%d.png" % (gid, n))
            im.save(path)
            print("%s page %d: %dx%d page units, image scale %.3f, %d regions outside -> %s" % (
                gid, n, p["w"], p["h"], sx, out, path))


if __name__ == "__main__":
    main(sys.argv[1:])
