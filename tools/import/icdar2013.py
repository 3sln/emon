#!/usr/bin/env python3
"""ICDAR 2013 Table Competition (tabula-java copy) -> emon gold (DEVLOG P2.11).   tools/import/icdar2013.py

Native ground truth, per PDF <doc>.pdf:
  <doc>-reg.xml   <table id><region id page><bounding-box x1 y1 x2 y2/></region></table>   table regions
  <doc>-str.xml   <table id><region id page row-increment col-increment><cell start-row start-col
                  [end-row end-col]><bounding-box x1 x2 y1 y2/><content>...</content></cell>...
(<doc>.json is tabula-java's own extraction, not ground truth, and is ignored.)

COORDINATE TRANSFORM (verified against pdftotext -bbox, test/corpus/DATASETS.md): boxes are PDF USER
SPACE in POINTS, ORIGIN BOTTOM-LEFT, y up, with y1 <= y2.  Emon page space is top-left, y down, so for
a page of height H (from `pdfinfo`, the displayed size; all pages here have /Rotate 0, else the item is
skipped):  (x1, y1, x2, y2) -> (x1, H - y2, x2, H - y1).  Pages may differ in size: H is per page.

GRID: a cell occupies rows start-row..end-row and columns start-col..end-col (end defaults to start; the
one `col-end` typo in the data is read as `end-col`).  A region's row-increment / col-increment are added
to its cells' indices (only us-019 and us-035a/b have non-zero increments; the reading is the natural one
and is not verifiable from the files, so those 3 documents are flagged in the summary).

MAPPING: each table -> table > rowgroup(body) > row > cell; the cell's text is the content with whitespace
collapsed (the source's line wraps are not semantic); `empty` when the content is blank; rowspan/colspan
when > 1; the table's `at` is the reg.xml region(s) (one per page), else the union of its cells; `columns` is
the grid width.
CLAIMED: table, row, cell presence AND absence over the whole document (the competition annotates every
table); table at/columns; cell rowspan/colspan/empty/text/at.
NOT claimed: rowgroup (no head/foot roles in the source), header cells, profile, everything that is not a table.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C


def fnum(v, st):
    """A coordinate attribute; the source has typos such as '26ß' (leading number kept, counted)."""
    try:
        return float(v)
    except ValueError:
        m = re.match(r"\s*(-?\d+(?:\.\d+)?)", v)
        if not m:
            raise
        st.warn("malformed coordinate read by its leading number")
        return float(m.group(1))


def geti(e, k, d=None):
    v = e.get(k)
    return int(v) if v is not None else d


def doc_gold(gid, pdf, reg_xml, str_xml, pdf_rel, st):
    pages = C.pdf_pages(pdf)
    if any(p[2] for p in pages):
        st.warn("rotated pages (skipped)")
        return None
    regs = {}
    for t in ET.parse(reg_xml).getroot().iter("table"):
        for r in t.findall("region"):
            b = r.find("bounding-box")
            if b is not None:
                regs[(t.get("id"), r.get("id"))] = (int(r.get("page")), [fnum(b.get(k), st) for k in ("x1", "y1", "x2", "y2")])

    def flip(page, x1, y1, x2, y2):
        if not 1 <= page <= len(pages):
            raise ValueError("page %d not in the PDF" % page)
        h = pages[page - 1][1]
        return C.region(page, x1, h - y2, x2, h - y1)

    tables = []
    flagged = False
    for t in ET.parse(str_xml).getroot().iter("table"):
        cells = []
        tregs = []
        ncols = 0
        for r in t.findall("region"):
            page = geti(r, "page")
            ri, ci = geti(r, "row-increment", 0), geti(r, "col-increment", 0)
            if ri or ci:
                flagged = True
            rb = regs.get((t.get("id"), r.get("id")))
            for c in r.findall("cell"):
                b = c.find("bounding-box")
                if b is None:
                    st.warn("cell without a box (dropped)")
                    continue
                sr = geti(c, "start-row") + ri
                er = geti(c, "end-row", geti(c, "start-row")) + ri
                sc = geti(c, "start-col") + ci
                ec = geti(c, "end-col", geti(c, "col-end", geti(c, "start-col"))) + ci
                ncols = max(ncols, ec + 1)
                txt = c.find("content")
                cells.append((sr, er, sc, ec, C.norm(txt.text if txt is not None else ""),
                              flip(page, *(fnum(b.get(k), st) for k in ("x1", "y1", "x2", "y2")))))
            if rb:
                tregs.append(flip(rb[0], *rb[1]))
            elif r.findall("cell"):
                st.warn("table region missing from reg.xml (union of cells used)")
        if not cells:
            continue
        if not tregs:
            tregs = [C.region(cells[0][5][0], *C.union([c[5][1:] for c in cells]))]
        nrows = max(c[1] for c in cells) + 1
        rows = [[] for _ in range(nrows)]
        for sr, er, sc, ec, txt, at in sorted(cells, key=lambda c: (c[0], c[2])):
            f = {}
            if er > sr:
                f["rowspan"] = er - sr + 1
            if ec > sc:
                f["colspan"] = ec - sc + 1
            if not txt:
                f["empty"] = True
            rows[sr].append(C.node("cell", f, at=at, text=txt or None))
        rowkids = []
        for r in rows:
            n = C.node("row")
            if r:
                n["children"] = r
            rowkids.append(n)
        group = C.node("rowgroup", role="body")
        group["children"] = rowkids
        tab = C.node("table", columns=ncols, at=tregs)
        tab["children"] = [group]
        tables.append(tab)
    if flagged:
        st.warn("document with a non-zero row/col-increment (flagged, see docstring)")
    ann = C.annotated(
        structure=["table", "row", "cell"],
        attributes={"table": ["at", "columns"], "cell": ["rowspan", "colspan", "empty", "text", "at"]},
        pages=["n", "w", "h"])
    plist = [{"n": i + 1, "w": C.num(w), "h": C.num(h), "rot": 0} for i, (w, h, _) in enumerate(pages)]
    prov = {"pdf": pdf_rel, "producer": "ICDAR 2013 Table Competition (Goebel et al. 2013) via tabula-java; human-annotated; "
            "%s-str.xml, %s-reg.xml" % (gid.split("-", 1)[1], gid.split("-", 1)[1])}
    return C.gold(gid, ann, plist, tables, "pdf", None, prov)


def items(corpus, st):
    base = os.path.join(corpus, "tables", "icdar2013")
    for sub in sorted(os.listdir(base)):
        d = os.path.join(base, sub)
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.endswith("-str.xml"):
                continue
            stem = fn[:-8]
            pdf, reg = os.path.join(d, stem + ".pdf"), os.path.join(d, stem + "-reg.xml")
            if not (os.path.exists(pdf) and os.path.exists(reg)):
                st.warn("pdf or reg.xml missing")
                yield stem, None
                continue
            gid = "icdar2013-" + stem
            yield gid, doc_gold(gid, pdf, reg, os.path.join(d, fn), C.rel(pdf), st)


if __name__ == "__main__":
    sys.exit(C.run("icdar2013", items, description=__doc__))
