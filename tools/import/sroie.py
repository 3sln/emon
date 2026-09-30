#!/usr/bin/env python3
"""SROIE 2019 (HF jsdnrs/ICDAR2019-SROIE form) -> emon gold (DEVLOG P2.11).   tools/import/sroie.py

Native ground truth: <id>.json {image, key, image_size {width,height}, entities {company,date,address,
total}, words [line text...], bboxes [[x0,y0,x1,y1]...]} beside <id>.jpg.  Despite the name `words`,
each entry is a whole TEXT LINE (SROIE task 1 is line-level), listed top to bottom; bboxes are in
IMAGE PIXELS, top-left origin, x0<x1, y0<y1.  No transform: the page is the image at 1 px = 1 pt.
The annotated image size is checked against the JPEG header (a mismatch skips the item).

MAPPING: one `paragraph` per line (text, at).  The receipt is not claimed to be paragraphs, so
NO structure is claimed: what IS claimed is `annotated.text` (the transcription in top-to-bottom
order; SROIE's transcription keeps OCR-style typos such as "BND", and is the reference as given)
and each line's `at`.  The key-information entities (company/date/address/total) are an
information-extraction label, not document structure, and are NOT imported.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C


def items(corpus, st):
    base = os.path.join(corpus, "financial", "sroie2019")
    for split in ("train", "test"):
        d = os.path.join(base, split)
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".json"):
                continue
            j = json.load(open(os.path.join(d, fn), encoding="utf-8"))
            img = os.path.join(d, j["image"]["file"])
            page, fmt = C.raster_page(img)
            if [page["w"], page["h"]] != [j["image_size"]["width"], j["image_size"]["height"]]:
                st.warn("annotated image size differs from the file")
                yield fn, None
                continue
            kids = []
            for t, b in zip(j["words"], j["bboxes"]):
                t = C.norm(t)
                if not t:
                    continue
                if not (0 <= b[0] < b[2] <= page["w"] and 0 <= b[1] < b[3] <= page["h"]):
                    st.warn("line box outside the image (kept)")
                kids.append(C.node("paragraph", at=C.region(1, *b), text=t))
            gid = "sroie-%s-%s" % (split, fn[:-5])
            ann = C.annotated(structure=[], attributes={"paragraph": ["text", "at"]}, text=True,
                              pages=["n", "px", "kind"])
            prov = {"producer": "SROIE 2019 (ICDAR competition), via HF jsdnrs/ICDAR2019-SROIE; line-level human transcription",
                    "variants": {"image": C.rel(img)}}
            yield gid, C.gold(gid, ann, [page], kids, fmt, None, prov)


if __name__ == "__main__":
    sys.exit(C.run("sroie", items, description=__doc__))
