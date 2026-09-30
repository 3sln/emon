#!/usr/bin/env python3
"""pdfa-eng-wds (pixparse/pdfa-eng-wds) -> emon SILVER gold (DEVLOG P2.11).   tools/import/pdfa_eng_wds.py

Native annotations, per PDF <id>.pdf: <id>.json {"pages": [{words {text, bbox, score, line_pos},
lines {text, bbox, score, word_slice}, images_bbox, images_bbox_no_text_overlap}]}.  The words and
lines are the PDF's own TEXT LAYER (dataset card: "we extracted words, bounding boxes ... readily available
in the pdf file") regrouped into lines in a heuristic reading order by a column-detection algorithm: machine
output, NOT human ground truth.  There is no hand-made ground truth in this dataset.

COORDINATES (verified against pdftotext -bbox, test/corpus/DATASETS.md): bbox is [x, y, w, h] as FRACTIONS
of the page's width and height, origin top-left.  So in points, for a page of W x H points (pdfinfo):
x0 = x*W, y0 = y*H, x1 = (x+w)*W, y1 = (y+h)*H.

MAPPING: one `paragraph` per text-layer line (text, at), in the source's order.  NOTHING IS CLAIMED except the
page table (n, w, h): `annotated.structure` is empty, no attribute is listed, `text` is false.  The lines
are emitted as UNCLAIMED context (silver; usable later as a text-layer oracle input, P3.10): none of it is scored as truth.  Documents whose PDF page count differs from the JSON, or with a
rotated page, are skipped and counted.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C


def items(corpus, st):
    base = os.path.join(corpus, "layout", "pdfa-eng-wds")
    for fn in sorted(os.listdir(base)):
        if not fn.endswith(".json"):
            continue
        stem = fn[:-5]
        pdf = os.path.join(base, stem + ".pdf")
        gid = "pdfa-" + stem
        if not os.path.exists(pdf):
            st.warn("pdf missing")
            yield gid, None
            continue
        try:
            pages = C.pdf_pages(pdf)
        except Exception:
            st.warn("pdfinfo failed (skipped)")
            yield gid, None
            continue
        j = json.load(open(os.path.join(base, fn), encoding="utf-8"))["pages"]
        if len(pages) != len(j):
            st.warn("page count differs from the PDF (skipped)")
            yield gid, None
            continue
        if any(p[2] for p in pages):
            st.warn("rotated pages (skipped)")
            yield gid, None
            continue
        kids = []
        for n, (pg, (W, H, _)) in enumerate(zip(j, pages), 1):
            ln = pg["lines"]
            for t, b in zip(ln["text"], ln["bbox"]):
                t = C.norm(t)
                if not t:
                    continue
                kids.append(C.node("paragraph", at=C.region(n, b[0] * W, b[1] * H, (b[0] + b[2]) * W, (b[1] + b[3]) * H), text=t))
        plist = [{"n": i + 1, "w": C.num(w), "h": C.num(h), "rot": 0} for i, (w, h, _) in enumerate(pages)]
        ann = C.annotated(pages=["n", "w", "h"])
        prov = {"pdf": C.rel(pdf), "producer": "SILVER: pixparse/pdfa-eng-wds text-layer lines with heuristic reading order (not human-verified); "
                "PDF from SafeDocs CC-MAIN-2021-31-PDF-UNTRUNCATED"}
        yield gid, C.gold(gid, ann, plist, kids, "pdf", None, prov)


if __name__ == "__main__":
    sys.exit(C.run("pdfa_eng_wds", items, description=__doc__))
