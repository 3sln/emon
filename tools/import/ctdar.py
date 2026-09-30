#!/usr/bin/env python3
"""ICDAR 2019 cTDaR (cndplab-founder/ICDAR2019_cTDaR) -> emon gold (DEVLOG P2.11).   tools/import/ctdar.py

Native ground truth, one XML per image: <document filename><table><Coords points="x,y x,y ..."/>
[<cell start-row end-row start-col end-col><Coords points=.../></cell>...]</table></document>.
Track A (table detection) has the `table` region only; Track B (recognition) adds the cells.
Sources used (all under corpus/tables/icdar2019-ctdar):
  train  training/TRACKA/ground_truth (xml+image, 600 archival + 600 modern), and
         training/TRACKB1/ground_truth (600 archival, cells)
  test   test_ground_truth/TRACK{A,B1,B2}/*.xml with the images in test/TRACK{A,B1,B2}/
(test/TRACKB1/*.xml are the INPUT table regions, not truth, and are ignored.)
The same image name can occur in several tracks: per image the richest XML wins (B2 > B1 > A).
Names repeat between training and test, so ids are cTDaR-train-<name> / cTDaR-test-<name>.

COORDINATES need NO transform: points are IMAGE PIXELS, origin top-left, y down; a region is the bounding
box of the polygon's points (cell polygons are not rectangles).  The page is the image at a nominal
1 px = 1 pt.  An image with EXIF orientation != 1 would be refused (none exist).

MAPPING: table > rowgroup(body) > row > cell; a cell is placed in its start-row and ordered by start-col;
rowspan = end-row - start-row + 1, colspan likewise (when > 1).  No text (cTDaR has none).
CLAIMED, per image: Track A only: table presence/absence, table `at`.  With cells (B1/B2): also row, cell,
table `columns`, cell rowspan/colspan.  Cell `at` is claimed only for the HISTORICAL set (cTDaR_t0*):
the README says modern cells' boxes are the CONTENT's bounding box, not the cell area.
NOT claimed: rowgroup roles, header cells, text, empty, everything that is not a table.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as C

IMG_EXT = (".jpg", ".jpeg", ".png", ".tif", ".tiff")


def bbox(points):
    pts = [tuple(float(v) for v in p.split(",")) for p in points.split()]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    return min(xs), min(ys), max(xs), max(ys)


def find_image(dirs, stem):
    for d in dirs:
        for e in IMG_EXT:
            p = os.path.join(d, stem + e)
            if os.path.exists(p):
                return p
    return None


def doc_gold(gid, stem, xmlp, rich, imgp, st):
    page, fmt = C.raster_page(imgp)
    root = ET.parse(xmlp).getroot()
    modern = stem.startswith("cTDaR_t1")
    tables = []
    has_cells = False
    for t in root.findall("table"):
        co = t.find("Coords")
        cells = t.findall("cell")
        if co is None and not cells:
            continue
        tb = bbox(co.get("points")) if co is not None else C.union([bbox(c.find("Coords").get("points")) for c in cells])
        if tb[0] < -1 or tb[1] < -1 or tb[2] > page["w"] + 1 or tb[3] > page["h"] + 1:
            st.warn("table region outside the image (kept)")
        tab = C.node("table", at=C.region(1, *tb))
        if cells:
            has_cells = True
            nrows = max(int(c.get("end-row")) for c in cells) + 1
            ncols = max(int(c.get("end-col")) for c in cells) + 1
            rows = [[] for _ in range(nrows)]
            for c in sorted(cells, key=lambda c: (int(c.get("start-row")), int(c.get("start-col")))):
                sr, er, sc, ec = (int(c.get(k)) for k in ("start-row", "end-row", "start-col", "end-col"))
                f = {}
                if er > sr:
                    f["rowspan"] = er - sr + 1
                if ec > sc:
                    f["colspan"] = ec - sc + 1
                cb = bbox(c.find("Coords").get("points"))
                rows[sr].append(C.node("cell", f, at=C.region(1, *cb)))
            rk = []
            for r in rows:
                n = C.node("row")
                if r:
                    n["children"] = r
                rk.append(n)
            g = C.node("rowgroup", role="body")
            g["children"] = rk
            tab["columns"] = ncols
            tab["children"] = [g]
        tables.append(tab)
    if has_cells:
        cellattrs = ["rowspan", "colspan"] + ([] if modern else ["at"])
        ann = C.annotated(structure=["table", "row", "cell"],
                          attributes={"table": ["at", "columns"], "cell": cellattrs}, pages=["n", "px", "kind"])
    else:
        ann = C.annotated(structure=["table"], attributes={"table": ["at"]}, pages=["n", "px", "kind"])
    prov = {"producer": "ICDAR 2019 cTDaR (Gao et al. 2019), human-annotated; %s" % rich, "variants": {"image": C.rel(imgp)}}
    return C.gold(gid, ann, [page], tables, fmt, None, prov)


def items(corpus, st):
    base = os.path.join(corpus, "tables", "icdar2019-ctdar")
    sets = {
        "train": ([("training/TRACKA/ground_truth", "A"), ("training/TRACKB1/ground_truth", "B1")],
                  ["training/TRACKA/ground_truth", "training/TRACKB1/ground_truth"]),
        "test": ([("test_ground_truth/TRACKA", "A"), ("test_ground_truth/TRACKB1", "B1"), ("test_ground_truth/TRACKB2", "B2")],
                 ["test/TRACKA", "test/TRACKB1", "test/TRACKB2"]),
    }
    rank = {"A": 0, "B1": 1, "B2": 2}
    for split, (xmls, imgdirs) in sets.items():
        best = {}
        for d, track in xmls:
            full = os.path.join(base, d)
            for fn in sorted(os.listdir(full)):
                if fn.endswith(".xml"):
                    stem = fn[:-4]
                    if stem not in best or rank[track] > rank[best[stem][1]]:
                        best[stem] = (os.path.join(full, fn), track)
        dirs = [os.path.join(base, d) for d in imgdirs]
        for stem in sorted(best):
            xmlp, track = best[stem]
            imgp = find_image(dirs, stem)
            gid = "cTDaR-%s-%s" % (split, stem[len("cTDaR_"):])
            if imgp is None:
                st.warn("image missing")
                yield gid, None
                continue
            yield gid, doc_gold(gid, stem, xmlp, "%s Track %s" % (C.rel(xmlp), track), imgp, st)


if __name__ == "__main__":
    sys.exit(C.run("ctdar", items, description=__doc__))
