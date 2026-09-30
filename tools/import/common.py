"""Shared helpers for the public-dataset importers (DEVLOG P2.11).  Tooling, not runtime.

Every importer turns one dataset's NATIVE ground truth into emon GOLD files
(schema/emon-gold-1.json, D072): a partial emon-1 Model plus an `annotated`
declaration of exactly what the source claims.  Standard library only (a
validator is found as kin/scripts/json-check.py finds it).  Output goes
OUTSIDE git, under corpus/_gold/<dataset>/ (corpus/ is never committed).

CONVENTIONS shared by all importers (DEVLOG D0xx, tools/import/README in
test/corpus/DATASETS.md):

  * UNITS AND ORIGIN.  `at` regions are [page, x0, y0, x1, y1] in page space
    (SPEC 3.4): top-left origin, y down.  PDF-backed sources are in POINTS
    (1/72 in) after converting the source's own origin.  RASTER sources
    (no resolution stated) are in PIXELS, with the page's nominal size set
    to the pixel size (1 px = 1 pt), `px` recorded and `dpi` NOT annotated:
    a scorer rescales by (emon page w / gold page px[0]).
  * `annotated.attributes[kind]` may list the pseudo-field `text` (the node's
    own text is annotated) and `at` (its region is annotated).  The global
    `annotated.text` flag is only set where leaf text AND reading order are
    annotated.
  * Body order is the source's order, or a top-to-bottom, left-to-right sort
    when the source has none.  It is never claimed unless `annotated.text`.
  * Text is NFC, whitespace-collapsed, stripped (the gold schema's rule).
  * Nodes the source does not annotate but that are useful context are still
    emitted; a metric reads `annotated` and skips them.
"""
import argparse
import importlib.util
import json
import os
import re
import struct
import subprocess
import sys
import time
import unicodedata

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CORPUS = os.environ.get("EMON_CORPUS") or os.path.join(ROOT, "corpus")
OUT = os.environ.get("EMON_GOLD_OUT") or os.path.join(CORPUS, "_gold")

ALL_KINDS = ["section", "heading", "paragraph", "list", "item", "table", "rowgroup", "row", "cell",
             "caption", "figure", "seal", "formula", "code", "quote", "note", "field", "break"]


# ------------------------------------------------------------------ text, numbers

def norm(s):
    """NFC, whitespace collapsed, stripped."""
    return re.sub(r"\s+", " ", unicodedata.normalize("NFC", s or "")).strip()


def num(x):
    """A coordinate: at most two decimals; an integral value prints as an integer."""
    x = round(float(x), 2)
    return int(x) if x == int(x) else x


def region(page, x0, y0, x1, y1):
    if x1 < x0:
        x0, x1 = x1, x0
    if y1 < y0:
        y0, y1 = y1, y0
    return [page, num(x0), num(y0), num(x1), num(y1)]


def union(boxes):
    """(x0,y0,x1,y1) enclosing every box."""
    return (min(b[0] for b in boxes), min(b[1] for b in boxes),
            max(b[2] for b in boxes), max(b[3] for b in boxes))


# ------------------------------------------------------------------ image headers

def image_info(path):
    """(width, height, format, exif_orientation) of a PNG/JPEG/TIFF/GIF/BMP, from its header."""
    with open(path, "rb") as f:
        head = f.read(32)
        if head[:8] == b"\x89PNG\r\n\x1a\n":
            w, h = struct.unpack(">II", head[16:24])
            return w, h, "png", 1
        if head[:2] == b"\xff\xd8":
            f.seek(2)
            orient = 1
            while True:
                b = f.read(1)
                while b and b != b"\xff":
                    b = f.read(1)
                while b == b"\xff":
                    b = f.read(1)
                if not b:
                    raise ValueError("%s: no JPEG frame" % path)
                m = b[0]
                if m in (0xD8, 0x01) or 0xD0 <= m <= 0xD7:
                    continue
                n = struct.unpack(">H", f.read(2))[0]
                seg = f.read(n - 2)
                if m == 0xE1 and seg[:6] == b"Exif\0\0":
                    orient = _tiff_orientation(seg[6:])
                if 0xC0 <= m <= 0xCF and m not in (0xC4, 0xC8, 0xCC):
                    h, w = struct.unpack(">HH", seg[1:5])
                    return w, h, "jpeg", orient
        if head[:4] in (b"II*\0", b"MM\0*"):
            f.seek(0)
            data = f.read()
            e = "<" if data[:2] == b"II" else ">"
            ifd = struct.unpack(e + "I", data[4:8])[0]
            n = struct.unpack(e + "H", data[ifd:ifd + 2])[0]
            tags = {}
            for i in range(n):
                t, ty, cnt = struct.unpack(e + "HHI", data[ifd + 2 + 12 * i: ifd + 10 + 12 * i])
                raw = data[ifd + 10 + 12 * i: ifd + 14 + 12 * i]
                tags[t] = struct.unpack(e + ("H" if ty == 3 else "I"), raw[:2] if ty == 3 else raw)[0]
            return tags[256], tags[257], "tiff", tags.get(274, 1)
        if head[:6] in (b"GIF87a", b"GIF89a"):
            w, h = struct.unpack("<HH", head[6:10])
            return w, h, "gif", 1
        if head[:2] == b"BM":
            w, h = struct.unpack("<ii", head[18:26])
            return w, abs(h), "bmp", 1
    raise ValueError("%s: unknown image format" % path)


def _tiff_orientation(t):
    try:
        e = "<" if t[:2] == b"II" else ">"
        ifd = struct.unpack(e + "I", t[4:8])[0]
        n = struct.unpack(e + "H", t[ifd:ifd + 2])[0]
        for i in range(n):
            tag, ty, cnt = struct.unpack(e + "HHI", t[ifd + 2 + 12 * i: ifd + 10 + 12 * i])
            if tag == 274:
                return struct.unpack(e + "H", t[ifd + 10 + 12 * i: ifd + 12 + 12 * i])[0]
    except Exception:
        pass
    return 1


def raster_page(path, n=1, size=None):
    """A page-table entry for an image file: nominal 1 px = 1 pt (see the module docstring).
    `size` overrides the header (w, h) when the annotation states the size and it is checked by the caller."""
    w, h, fmt, orient = image_info(path)
    if orient != 1:
        raise ValueError("%s: EXIF orientation %d (annotation space would need rotating)" % (path, orient))
    return {"n": n, "w": w, "h": h, "rot": 0, "kind": "image", "px": [w, h]}, fmt


# ------------------------------------------------------------------ nodes

def node(kind, _fields=None, at=None, text=None, children=None, **fields):
    """A block before ids are assigned.  `_fields` (an ordered dict) is for field names that are not identifiers."""
    n = {"kind": kind}
    n.update(_fields or {})
    n.update(fields)
    if at is not None:
        n["at"] = at if isinstance(at[0], list) else [at]
    if text is not None:
        n["text"] = text
    if children:
        n["children"] = children
    return n


def assign_ids(n, counter):
    """Copy of `n` with `id`s in document order (pre-order), keys in the canonical order."""
    out = {"id": counter[0], "kind": n["kind"]}
    counter[0] += 1
    for k, v in n.items():
        if k in ("kind", "at", "text", "children"):
            continue
        out[k] = v
    if "at" in n:
        out["at"] = n["at"]
    if "text" in n:
        out["text"] = n["text"]
    if n.get("children"):
        out["children"] = [assign_ids(c, counter) for c in n["children"]]
    return out


def annotated(structure=(), attributes=None, text=False, inlines=False, furniture=False, dropped=(), pages=()):
    return {
        "structure": [k for k in ALL_KINDS if k in set(structure)],
        "attributes": {k: sorted(set(v)) for k, v in (attributes or {}).items()},
        "text": text, "inlines": inlines, "furniture": furniture,
        "dropped": list(dropped), "pages": list(pages),
    }


def gold(gid, ann, pages, children, fmt, lang=None, provenance=None, furniture=(), dropped=()):
    counter = [0]
    body = assign_ids({"kind": "document", "children": children}, counter)
    fur = []
    for f in furniture:
        fur.append(dict(id=counter[0], **f))
        counter[0] += 1
    src = {"pages": len(pages), "format": fmt}
    if lang:
        src["lang"] = lang
    g = {
        "emon-gold": "1.0",
        "id": gid,
    }
    if provenance:
        g["provenance"] = provenance
    g["annotated"] = ann
    g["model"] = {"emon": "1.0", "source": src, "settings": {"locale": lang}, "pages": pages,
                  "body": body, "furniture": fur, "dropped": list(dropped)}
    return g


def pdf_pages(pdf):
    """[(w, h, rot)] per page, from pdfinfo (poppler)."""
    out = subprocess.run(["pdfinfo", "-f", "1", "-l", "99999", pdf], capture_output=True, text=True, check=True).stdout
    sizes = {}
    rots = {}
    for m in re.finditer(r"^Page\s+(\d+) size:\s+([\d.]+) x ([\d.]+) pts", out, re.M):
        sizes[int(m.group(1))] = (float(m.group(2)), float(m.group(3)))
    for m in re.finditer(r"^Page\s+(\d+) rot:\s+(\d+)", out, re.M):
        rots[int(m.group(1))] = int(m.group(2))
    return [(sizes[i][0], sizes[i][1], rots.get(i, 0)) for i in sorted(sizes)]


def rel(path):
    return os.path.relpath(path, CORPUS)


# ------------------------------------------------------------------ validation

_validator = None


def validator():
    global _validator
    if _validator is not None:
        return _validator
    spec = importlib.util.spec_from_file_location("json_check", os.path.join(ROOT, "kin", "scripts", "json-check.py"))
    jc = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(jc)
    js = jc.find_jsonschema()
    if js is None:
        raise SystemExit("import: no jsonschema available (pip install jsonschema, or set EMON_PYLIB)")
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012
    one = json.load(open(os.path.join(ROOT, "schema", "emon-1.json"), encoding="utf-8"))
    gs = json.load(open(os.path.join(ROOT, "schema", "emon-gold-1.json"), encoding="utf-8"))
    reg = Registry().with_resources([(one["$id"], Resource.from_contents(one, DRAFT202012)),
                                     (gs["$id"], Resource.from_contents(gs, DRAFT202012))])
    _validator = js.Draft202012Validator(gs, registry=reg)
    return _validator


def check(g):
    """Every problem with a gold: schema, ids in document order, page refs, claims a metric could trip on."""
    errs = []
    for e in sorted(validator().iter_errors(g), key=lambda e: list(e.path))[:3]:
        errs.append("schema: %s at /%s" % (e.message[:160], "/".join(map(str, e.path))))
    m = g["model"]
    ids = []

    def rec(n):
        ids.append(n["id"])
        for c in n.get("children", []):
            rec(c)
    rec(m["body"])
    if ids != list(range(len(ids))):
        errs.append("body ids are not 0..n in document order")
    if [f["id"] for f in m["furniture"]] != list(range(len(ids), len(ids) + len(m["furniture"]))):
        errs.append("furniture ids do not continue after the body's")
    if len(m["pages"]) != m["source"]["pages"]:
        errs.append("source.pages and the page table disagree")
    npages = len(m["pages"])
    pw = {p["n"]: (p["w"], p["h"]) for p in m["pages"]}
    if list(pw) != list(range(1, npages + 1)):
        errs.append("pages are not numbered 1..n")

    def geo(n):
        for r in n.get("at", []):
            p = r[0]
            if p not in pw:
                errs.append("node %d: region on page %d, which does not exist" % (n["id"], p))
            elif not (r[1] <= r[3] and r[2] <= r[4]):
                errs.append("node %d: inverted region" % n["id"])
        for c in n.get("children", []):
            geo(c)
    geo(m["body"])
    return errs


# ------------------------------------------------------------------ driver

class Stats:
    def __init__(self):
        self.written = 0
        self.invalid = 0
        self.skipped = 0
        self.warnings = {}
        self.examples = []
        self.nodes = 0

    def warn(self, msg):
        self.warnings[msg] = self.warnings.get(msg, 0) + 1


def count_nodes(n):
    return 1 + sum(count_nodes(c) for c in n.get("children", []))


def run(name, gen, argv=None, description=""):
    """`gen(corpus, stats)` yields (id, gold) (or (id, None) for a skipped item).  Writes
    corpus/_gold/<name>/<id>.gold.json, validates each, prints one summary line.  Exit 1 if any
    gold is invalid."""
    ap = argparse.ArgumentParser(description=description or name)
    ap.add_argument("--limit", type=int, default=0, help="stop after N items (0: all)")
    ap.add_argument("--corpus", default=CORPUS)
    ap.add_argument("--out", default=OUT)
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args(argv)
    out = os.path.join(a.out, name)
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        if f.endswith(".gold.json"):
            os.remove(os.path.join(out, f))
    st = Stats()
    seen = set()
    t0 = time.time()
    for gid, g in gen(a.corpus, st):
        if g is None:
            st.skipped += 1
            continue
        errs = check(g)
        if gid in seen:
            errs.append("duplicate id %s (an earlier item would be overwritten)" % gid)
        seen.add(gid)
        if errs:
            st.invalid += 1
            if len(st.examples) < 5:
                st.examples.append("%s: %s" % (gid, errs[0]))
            continue
        st.nodes += count_nodes(g["model"]["body"])
        with open(os.path.join(out, gid + ".gold.json"), "w", encoding="utf-8") as f:
            f.write(json.dumps(g, ensure_ascii=False, indent=1) + "\n")
        st.written += 1
        if a.limit and st.written >= a.limit:
            break
    summary = {"dataset": name, "written": st.written, "invalid": st.invalid, "skipped": st.skipped,
               "nodes": st.nodes, "warnings": st.warnings, "seconds": round(time.time() - t0, 1),
               "out": os.path.relpath(out, a.corpus) if out.startswith(a.corpus) else out}
    if st.examples:
        summary["invalid_examples"] = st.examples
    with open(os.path.join(out, "_summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=1)
    print(json.dumps(summary))
    return 1 if st.invalid or st.written == 0 else 0
