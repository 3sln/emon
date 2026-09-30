#!/usr/bin/env python3
"""mapo80/stamps "Clean Core Stamp Detection Dataset" (HF, CC-BY-4.0) -> emon gold (DEVLOG P2.11).
tools/import/stamps.py

The seal/stamp set found for P2.11 (Tobacco800, StaVer and SPODS need logins; this one downloads
anonymously: `corpus/fetch.sh stamps`).  Native ground truth: images/<split>/<name>.{jpg,png} with
labels/<split>/<name>.txt, YOLO format, one line per object: `class xc yc w h`, all four numbers
NORMALISED to the image (fractions of width / height, origin top-left), class 0 = stamp.  An empty label
file is a negative image.  Only the val and test splits are in the local corpus (train is 783 MB).

COORDINATES: x0 = (xc - w/2) * W, x1 = (xc + w/2) * W, y likewise with H, in IMAGE PIXELS of the file
(W x H from its header).  The page is the image at a nominal 1 px = 1 pt.

MAPPING: one `seal` (subkind `stamp`) per box, in top-to-bottom, left-to-right order.
CLAIMED: seal presence and absence (a negative image claims "no seal"), subkind, at.
CAVEAT (recorded, not hidden): the dataset labels STAMPS only, so a signature or logo on an image is
absent from the gold; a scorer should treat emon's `seal` nodes of subkind `stamp` (and, leniently,
`seal`) as the thing compared.  The negative images are drawn from RVL-CDIP and FUNSD pages and inherit
those sources' research-only terms (see test/corpus/DATASETS.md).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C


def items(corpus, st):
    base = os.path.join(corpus, "seals", "mapo80-stamps")
    for split in ("val", "test"):
        idir = os.path.join(base, "images", split)
        if not os.path.isdir(idir):
            continue
        for fn in sorted(os.listdir(idir)):
            stem, ext = os.path.splitext(fn)
            if ext.lower() not in (".jpg", ".jpeg", ".png"):
                continue
            imgp = os.path.join(idir, fn)
            lab = os.path.join(base, "labels", split, stem + ".txt")
            gid = "stamps-%s-%s" % (split, stem)
            if not os.path.exists(lab):
                st.warn("label file missing")
                yield gid, None
                continue
            page, fmt = C.raster_page(imgp)
            W, H = page["w"], page["h"]
            boxes = []
            for ln in open(lab, encoding="utf-8").read().splitlines():
                p = ln.split()
                if not p:
                    continue
                if len(p) != 5 or p[0] != "0":
                    st.warn("unexpected label line (dropped)")
                    continue
                xc, yc, w, h = (float(v) for v in p[1:])
                if not (0 <= xc - w / 2 and xc + w / 2 <= 1.001 and 0 <= yc - h / 2 and yc + h / 2 <= 1.001):
                    st.warn("box beyond the image (clamped)")
                x0, y0 = max(0.0, (xc - w / 2) * W), max(0.0, (yc - h / 2) * H)
                x1, y1 = min(float(W), (xc + w / 2) * W), min(float(H), (yc + h / 2) * H)
                boxes.append((y0, x0, x1, y1))
            boxes.sort()
            kids = [C.node("seal", subkind="stamp", at=C.region(1, x0, y0, x1, y1)) for y0, x0, x1, y1 in boxes]
            ann = C.annotated(structure=["seal"], attributes={"seal": ["subkind", "at"]}, pages=["n", "px", "kind"])
            prov = {"producer": "mapo80/stamps Clean Core Stamp Detection Dataset (CC-BY-4.0; aggregate of Roboflow/Kaggle "
                    "community sets; stamps only, single class)", "variants": {"image": C.rel(imgp)}}
            yield gid, C.gold(gid, ann, [page], kids, fmt, None, prov)


if __name__ == "__main__":
    sys.exit(C.run("stamps", items, description=__doc__))
