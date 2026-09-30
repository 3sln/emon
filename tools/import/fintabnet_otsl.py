#!/usr/bin/env python3
"""FinTabNet-OTSL (ds4sd/FinTabNet_OTSL shards) -> emon gold (DEVLOG P2.11).   tools/import/fintabnet_otsl.py

Native ground truth, per table crop <id>.json (+ <id>.png): {filename, imgid, cells [[{tokens [chars],
bbox [x0,y0,x1,y1,k]}...]], otsl [tokens], html, html_restored, rows, cols}.
OTSL tokens (Lysak et al. 2023): fcel (cell with content), ecel (empty cell), lcel (merged with the cell
to its LEFT: a column span), ucel (merged with the cell above: a row span), xcel (both), nl (end of row).
`cells[0]` lists the fcel/ecel cells in OTSL order (the k of a bbox is 2 for fcel, 1 for ecel).
Only fcel, ecel, lcel and nl occur in this shard (spans are column spans only).

COORDINATES need NO transform: bbox is in the CROP IMAGE's pixels, origin top-left (some x0/y0 are -1
because the crop margin is tight; they are clamped to 0).  The page is the crop at a nominal 1 px = 1 pt.
The crop is a TABLE, not a page: the gold body is one table and nothing else.

MAPPING: table > rowgroup(body) > row > cell; text = the cell's characters joined ("L","a","n","d" ->
"Land"), whitespace-collapsed; colspan = 1 + the lcels to its right, rowspan = 1 + the ucels below;
`empty` for an ecel.  The source has no header annotation, so no head rowgroup.
CLAIMED: table, row, cell (presence and absence: the crop holds exactly one table); table columns;
cell rowspan, colspan, empty, text.  `at` of a cell is emitted (the source's word/cell box) but NOT
claimed: for a text cell it is the text's extent, not the ruled cell.  NOT claimed: rowgroup roles,
header cells, table `at`.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C


def grid(otsl):
    """[[token]] rows of an OTSL sequence."""
    rows, cur = [], []
    for t in otsl:
        if t == "nl":
            rows.append(cur)
            cur = []
        else:
            cur.append(t)
    if cur:
        rows.append(cur)
    return rows


def table_gold(gid, j, imgp, rel_img, st):
    page, fmt = C.raster_page(imgp)
    W, H = page["w"], page["h"]
    cells = j["cells"][0] if j["cells"] and isinstance(j["cells"][0], list) else j["cells"]
    rows = grid(j["otsl"])
    want = sum(1 for r in rows for t in r if t in ("fcel", "ecel"))
    if want != len(cells):
        st.warn("otsl and cells disagree (skipped)")
        return None
    widths = {len(r) for r in rows}
    if len(widths) != 1:
        st.warn("ragged otsl rows (skipped)")
        return None
    ci = 0
    rowkids = []
    for r, toks in enumerate(rows):
        kids = []
        for c, t in enumerate(toks):
            if t not in ("fcel", "ecel"):
                continue
            cell = cells[ci]
            ci += 1
            cs = 1
            while c + cs < len(toks) and toks[c + cs] in ("lcel", "xcel"):
                cs += 1
            rs = 1
            while r + rs < len(rows) and rows[r + rs][c] in ("ucel", "xcel"):
                rs += 1
            f = {}
            if rs > 1:
                f["rowspan"] = rs
            if cs > 1:
                f["colspan"] = cs
            text = C.norm("".join(cell["tokens"]))
            if t == "ecel" or not text:
                f["empty"] = True
            x0, y0, x1, y1 = cell["bbox"][:4]
            x0, y0, x1, y1 = max(0, x0), max(0, y0), min(W, x1), min(H, y1)
            at = C.region(1, x0, y0, x1, y1) if x1 > x0 and y1 > y0 else None
            kids.append(C.node("cell", f, at=at, text=text or None))
        row = C.node("row")
        if kids:
            row["children"] = kids
        rowkids.append(row)
    g = C.node("rowgroup", role="body")
    g["children"] = rowkids
    tab = C.node("table", columns=len(rows[0]))
    tab["children"] = [g]
    ann = C.annotated(structure=["table", "row", "cell"],
                      attributes={"table": ["columns"], "cell": ["rowspan", "colspan", "empty", "text"]},
                      pages=["n", "px", "kind"])
    prov = {"producer": "FinTabNet (IBM, SEC filings) in OTSL form, ds4sd/FinTabNet_OTSL; source %s" % j.get("filename", "?"),
            "variants": {"image": rel_img}}
    return C.gold(gid, ann, [page], [tab], fmt, None, prov)


def items(corpus, st):
    base = os.path.join(corpus, "tables", "fintabnet-otsl")
    for shard in sorted(os.listdir(base)):
        d = os.path.join(base, shard)
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".json"):
                continue
            j = json.load(open(os.path.join(d, fn), encoding="utf-8"))
            imgp = os.path.join(d, j["image"]["file"])
            gid = "fintabnet-%s-%s" % (shard.split("-")[0], fn[:-5])
            yield gid, table_gold(gid, j, imgp, C.rel(imgp), st)


if __name__ == "__main__":
    sys.exit(C.run("fintabnet_otsl", items, description=__doc__))
