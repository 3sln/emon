#!/usr/bin/env python3
"""DocLayNet v1.2 (ds4sd/DocLayNet-v1.2 shards) -> emon gold (DEVLOG P2.11).   tools/import/doclaynet.py

Native ground truth, per page <id>.json (+ <id>.png, <id>.pdf, one page): COCO-style
  bboxes      [[x, y, w, h]...]   in the 1025 x 1025 PNG's pixels, origin top-left
  category_id [int...]            1 Caption 2 Footnote 3 Formula 4 List-item 5 Page-footer 6 Page-header
                                  7 Picture 8 Section-header 9 Table 10 Text 11 Title
  pdf_cells   [[{bbox [x,y,w,h], text, font}...] ...]  per box, same pixel space
  metadata    {coco_width, coco_height, original_width, original_height (PDF points), page_no, num_pages,
               doc_category, collection, original_filename}

COORDINATE TRANSFORM (verified against the paired PDF with pdftotext -bbox, see test/corpus/DATASETS.md):
the page is STRETCHED, not letterboxed, onto the square 1025 x 1025 canvas, so
    x_pt = x_px * original_width  / coco_width        y_pt = y_px * original_height / coco_height
and (x, y, w, h) becomes (x_pt, y_pt, x_pt + w_pt, y_pt + h_pt).  Origin stays top-left (the PNG's).
The page is original_width x original_height points, rot 0 (the paired PDF agrees; a 1-page PDF).

MAPPING (decisions, D0xx):
  Title, Section-header -> heading (NO level; DocLayNet has none)   Text -> paragraph
  List-item -> item, consecutive items wrapped in one `list` (the grouping is ours, unclaimed)
  Caption -> caption   Footnote -> note (role footnote)   Formula -> formula (display block)
  Picture -> figure (subkind unknown)   Table -> table (region only: no rows or cells)
  Page-header / Page-footer -> body `furniture` blocks (role, pages [page]) WITH geometry, because the
  gold's model.furniture list has no `at`; text from the PDF cells.
`text` on a block is the PDF cells inside it, joined by a space: convenience, NOT annotation.

CLAIMED (presence, absence and region): heading, paragraph, item, caption, note, formula, figure, table.
NOT claimed: heading level, list/section/furniture structure, text, reading order (body order is the
order of the source's box list, which is not documented as reading order), table internals.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C

CAT = {1: "Caption", 2: "Footnote", 3: "Formula", 4: "List-item", 5: "Page-footer", 6: "Page-header",
       7: "Picture", 8: "Section-header", 9: "Table", 10: "Text", 11: "Title"}


def page_gold(gid, j, pdf_rel, png_rel, st):
    md = j["metadata"]
    cw, ch = md["coco_width"], md["coco_height"]
    ow, oh = md["original_width"], md["original_height"]
    sx, sy = ow / cw, oh / ch
    kids = []
    lst = None
    for i, (b, cat) in enumerate(zip(j["bboxes"], j["category_id"])):
        name = CAT.get(cat)
        if name is None:
            st.warn("unknown category")
            continue
        x, y, w, h = b
        at = C.region(1, x * sx, y * sy, (x + w) * sx, (y + h) * sy)
        if at[3] > ow + 0.5 or at[4] > oh + 0.5:
            st.warn("box beyond the page (kept)")
        cells = j["pdf_cells"][i] if i < len(j["pdf_cells"]) else []
        text = C.norm(" ".join(c["text"] for c in cells))
        if name != "List-item":
            lst = None
        if name in ("Title", "Section-header"):
            n = C.node("heading", at=at, text=text or None)
        elif name == "Text":
            n = C.node("paragraph", at=at, text=text or None)
        elif name == "List-item":
            it = C.node("item", at=at, text=text or None)
            if lst is None:
                lst = C.node("list")
                lst["children"] = []
                kids.append(lst)
            lst["children"].append(it)
            continue
        elif name == "Caption":
            n = C.node("caption", at=at, text=text or None)
        elif name == "Footnote":
            n = C.node("note", role="footnote", at=at, text=text or None)
        elif name == "Formula":
            n = C.node("formula", display="block", at=at, text=text or None)
        elif name == "Picture":
            n = C.node("figure", at=at)
        elif name == "Table":
            n = C.node("table", at=at)
        else:  # Page-header / Page-footer
            n = C.node("furniture", role="header" if name == "Page-header" else "footer", pages=[1],
                       at=at, text=text or None)
        kids.append(n)
    # list regions: the union of their items (ours, unclaimed)
    for k in kids:
        if k["kind"] == "list":
            boxes = [c["at"][0][1:] for c in k["children"]]
            k["at"] = [C.region(1, *C.union(boxes))]
    page = {"n": 1, "w": C.num(ow), "h": C.num(oh), "rot": 0}
    ann = C.annotated(
        structure=["heading", "paragraph", "item", "caption", "note", "formula", "figure", "table"],
        attributes={k: ["at"] for k in ("heading", "paragraph", "item", "caption", "note", "formula", "figure", "table")},
        pages=["n", "w", "h"])
    prov = {"pdf": pdf_rel,
            "producer": "DocLayNet v1.2 (human-annotated layout; %s, %s p.%d of %d)" % (
                md.get("collection"), md.get("original_filename"), md.get("page_no", 0), md.get("num_pages", 0)),
            "variants": {"png": png_rel}}
    return C.gold(gid, ann, [page], kids, "pdf", None, prov)


def items(corpus, st):
    base = os.path.join(corpus, "layout", "doclaynet-v1.2")
    for shard in sorted(os.listdir(base)):
        d = os.path.join(base, shard)
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".json"):
                continue
            j = json.load(open(os.path.join(d, fn), encoding="utf-8"))
            stem = fn[:-5]
            gid = "doclaynet-%s-%s" % (shard.split("-")[0], stem)
            yield gid, page_gold(gid, j, C.rel(os.path.join(d, stem + ".pdf")), C.rel(os.path.join(d, stem + ".png")), st)


if __name__ == "__main__":
    sys.exit(C.run("doclaynet", items, description=__doc__))
