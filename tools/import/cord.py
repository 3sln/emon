#!/usr/bin/env python3
"""CORD v2 (naver-clova-ix/cord-v2) -> emon gold (DEVLOG P2.11).   tools/import/cord.py

Native ground truth: <id>.json {image, ground_truth}; `ground_truth` is a JSON STRING holding
{gt_parse (key-information tree), meta {image_size}, valid_line [{words [{quad {x1..x4,y1..y4}, is_key,
row_id, text}], category, group_id, sub_group_id}], roi, repeating_symbol, dontcare}.  Quads are in
IMAGE PIXELS, top-left origin; the bounding box of a quad's four points is used.  No transform: the
page is the image at 1 px = 1 pt.  The annotated image size is checked against the PNG header.

MAPPING: words that share a `row_id` are one printed ROW of the receipt: one `paragraph` per row
(words left-to-right, joined by a space; `at` = union of the word boxes), rows top to bottom.
CLAIMED: each row paragraph's `at` only.  NOT claimed: structure (CORD's own grouping is
menu/total key-information, not layout: it is NOT imported), text (only the `valid_line`
words are transcribed, `dontcare` text is absent, and the order is derived) and reading order.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C


def items(corpus, st):
    base = os.path.join(corpus, "financial", "cord-v2")
    for split in ("test", "validation"):
        d = os.path.join(base, split)
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".json"):
                continue
            j = json.load(open(os.path.join(d, fn), encoding="utf-8"))
            gt = json.loads(j["ground_truth"])
            img = os.path.join(d, j["image"]["file"])
            page, fmt = C.raster_page(img)
            sz = gt["meta"]["image_size"]
            if [page["w"], page["h"]] != [sz["width"], sz["height"]]:
                st.warn("annotated image size differs from the file")
                yield fn, None
                continue
            rows = {}
            for line in gt["valid_line"]:
                for w in line["words"]:
                    q = w["quad"]
                    xs = [q["x1"], q["x2"], q["x3"], q["x4"]]
                    ys = [q["y1"], q["y2"], q["y3"], q["y4"]]
                    rows.setdefault(w["row_id"], []).append((min(xs), min(ys), max(xs), max(ys), w["text"]))
            kids = []
            for rid, ws in sorted(rows.items(), key=lambda kv: (min(w[1] for w in kv[1]), min(w[0] for w in kv[1]))):
                ws.sort(key=lambda w: w[0])
                text = C.norm(" ".join(w[4] for w in ws))
                if not text:
                    continue
                x0, y0, x1, y1 = C.union([w[:4] for w in ws])
                if x0 < 0 or y0 < 0 or x1 > page["w"] or y1 > page["h"]:
                    st.warn("row box outside the image (kept)")
                kids.append(C.node("paragraph", at=C.region(1, x0, y0, x1, y1), text=text))
            gid = "cord-%s-%s" % (split, fn[:-5])
            ann = C.annotated(structure=[], attributes={"paragraph": ["at"]}, pages=["n", "px", "kind"])
            prov = {"producer": "CORD v2 (Park et al. 2019), naver-clova-ix/cord-v2; word quads, human-annotated",
                    "variants": {"image": C.rel(img)}}
            yield gid, C.gold(gid, ann, [page], kids, fmt, None, prov)


if __name__ == "__main__":
    sys.exit(C.run("cord", items, description=__doc__))
