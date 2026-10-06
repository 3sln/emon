#!/usr/bin/env python3
"""Baselines against the field (DEVLOG P2.10, SPEC 14.4, D500-D503).

A third-party-oracle script (D094's exception: Python lives in tools/oracle/).  It runs one
pinned extractor over the committed synthetic corpus (test/corpus/synth, MANIFEST.json) and
writes, per document and input form, an emon-1 Model that `bb accuracy` can score:

    .cache/baselines/TOOL/VARIANT/ID.json

      python3 tools/oracle/baselines.py TOOL [--variants pdf,pdf-tagged] [--ids A,B] [--force] [--limit N]

TOOL is  pdfplumber | pymupdf4llm | docling | tesseract.  `bb baselines` drives this and the
scoring; run it directly only to debug an adapter.  One process, one document at a time; the
run is resumable (an existing output is kept unless --force).

WHAT IS THE TOOL'S AND WHAT IS THE ADAPTER'S.  Every extractor's native output is mapped into
the Model by the code below.  Where a tool says it (docling's labels, pymupdf4llm's markdown
headings, tables, lists), that is used as it is.  pdfplumber and Tesseract do not say it, so the
adapter supplies the smallest documented heuristics (font size for headings, line gaps for
paragraphs, leading markers for list items; pdfplumber's own find_tables for tables).  No
heuristic reads the gold or the corpus's ids; nothing here is tuned to the corpus.
"""
import json
import os
import re
import sys
import time
import traceback
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CORPUS = os.path.join(ROOT, "test", "corpus", "synth")
OUT = os.path.join(ROOT, ".cache", "baselines")

# ------------------------------------------------------------------- model building ----


class Ids:
    def __init__(self):
        self.n = 0

    def __call__(self):
        self.n += 1
        return self.n


def model(blocks, npages, furniture=None):
    ids = Ids()

    def stamp(n):
        n["id"] = ids()
        for c in n.get("children", []):
            stamp(c)
        return n

    body = {"id": 0, "kind": "document", "children": [stamp(b) for b in blocks]}
    m = {"emon": "1.0", "pages": [{"n": i + 1} for i in range(npages)], "body": body,
         "furniture": [], "dropped": []}
    for f in furniture or []:
        m["furniture"].append(f)
    return m


def heading(level, text):
    return {"kind": "heading", "level": max(1, min(6, level)), "text": text}


def para(text):
    return {"kind": "paragraph", "text": text}


def lst(items, ordered):
    return {"kind": "list", "ordered": ordered,
            "children": [{"kind": "item", "text": t} for t in items]}


def table(rows):
    """rows: list of list of (text, rowspan, colspan) or text."""
    out = []
    for r in rows:
        cells = []
        for c in r:
            if isinstance(c, str):
                c = (c, 1, 1)
            t, rs, cs = c
            cell = {"kind": "cell", "text": t}
            if rs != 1:
                cell["rowspan"] = rs
            if cs != 1:
                cell["colspan"] = cs
            cells.append(cell)
        out.append({"kind": "row", "children": cells})
    return {"kind": "table", "children": out}


MARKER = re.compile(r"^\s*(?:([•◦▪●○‣⁃∙·*\-–—])|(\d{1,3}[.)]|[A-Za-z][.)]|[ivxIVX]{1,5}[.)]))\s+(\S.*)$")


def split_marker(line):
    """(ordered?, text) if the line starts with a list marker, else None."""
    m = MARKER.match(line)
    if not m:
        return None
    return (m.group(2) is not None, m.group(3))


def flush_items(out, items, ordered):
    if items:
        out.append(lst(items, ordered))
    return [], False


# ------------------------------------------------------------------------ pdfplumber ----


def run_pdfplumber(path):
    import pdfplumber

    with pdfplumber.open(path) as pdf:
        pages = []
        sizes = Counter()
        for pg in pdf.pages:
            tabs = pg.find_tables()
            boxes = [t.bbox for t in tabs]

            def outside(o, boxes=boxes):
                # drop objects whose centre lies in a table box: the table says them
                if o.get("object_type") != "char":
                    return True
                cx = (o["x0"] + o["x1"]) / 2
                cy = (o["top"] + o["bottom"]) / 2
                return not any(b[0] <= cx <= b[2] and b[1] <= cy <= b[3] for b in boxes)

            lines = pg.filter(outside).extract_text_lines(return_chars=True, strip=True)
            recs = []
            for ln in lines:
                chars = [c for c in ln["chars"] if c["text"].strip()]
                if not chars:
                    continue
                sz = Counter(round(c["size"], 1) for c in chars).most_common(1)[0][0]
                bold = all("bold" in c["fontname"].lower() or "black" in c["fontname"].lower() for c in chars)
                recs.append({"text": ln["text"].strip(), "top": ln["top"], "bottom": ln["bottom"],
                             "size": sz, "bold": bold, "n": len(chars)})
                sizes[sz] += len(chars)
            tbls = []
            for t in tabs:
                rows = [[(c or "").replace("\n", " ").strip() for c in r] for r in t.extract()]
                if rows and any(any(c for c in r) for r in rows):
                    tbls.append({"top": t.bbox[1], "rows": rows})
            pages.append((recs, tbls))
        body = sizes.most_common(1)[0][0] if sizes else 10.0
        # heading levels: the distinct sizes above the body size, largest first
        big = sorted({r["size"] for recs, _ in pages for r in recs if r["size"] >= body * 1.12}, reverse=True)
        blocks = []
        for recs, tbls in pages:
            items = [("line", r["top"], r) for r in recs] + [("table", t["top"], t) for t in tbls]
            items.sort(key=lambda x: x[1])
            cur = []          # lines of the paragraph being built
            li, lord = [], False   # list items being built
            prev = None

            def flush_para():
                nonlocal cur
                if cur:
                    blocks.append(para(" ".join(cur)))
                cur = []

            for kind, _, r in items:
                if kind == "table":
                    flush_para()
                    li, lord = flush_items(blocks, li, lord)
                    blocks.append(table(r["rows"]))
                    prev = None
                    continue
                gap = (r["top"] - prev["bottom"]) if prev else None
                lh = r["bottom"] - r["top"]
                head = r["size"] >= body * 1.12 or (r["bold"] and r["n"] <= 90 and (gap is None or gap > 0.3 * lh))
                if head and r["size"] >= body * 1.12:
                    level = 1 + big.index(r["size"]) if r["size"] in big else 1
                elif head:
                    level = 1 + len(big)
                if head:
                    flush_para()
                    li, lord = flush_items(blocks, li, lord)
                    # consecutive heading lines of one size are one heading
                    if blocks and blocks[-1]["kind"] == "heading" and prev and prev.get("_h") == r["size"] and gap is not None and gap < 0.6 * lh:
                        blocks[-1]["text"] += " " + r["text"]
                    else:
                        blocks.append(heading(level, r["text"]))
                    r["_h"] = r["size"]
                    prev = r
                    continue
                newp = gap is None or gap > 0.45 * lh
                mk = split_marker(r["text"])
                if mk:
                    flush_para()
                    if li and lord != mk[0]:
                        li, lord = flush_items(blocks, li, lord)
                    lord = mk[0]
                    li.append(mk[1])
                elif li and not newp:
                    li[-1] += " " + r["text"]          # a wrapped list item
                else:
                    li, lord = flush_items(blocks, li, lord)
                    if newp:
                        flush_para()
                    cur.append(r["text"])
                prev = r
            flush_para()
            li, lord = flush_items(blocks, li, lord)
        return model(blocks, len(pages))


# ------------------------------------------------------------------------ pymupdf4llm ----

PIPE = re.compile(r"^\s*\|.*\|\s*$")
SEP = re.compile(r"^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$")


def md_clean(t):
    t = re.sub(r"<br\s*/?>", " ", t)
    t = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", t)
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)
    t = re.sub(r"(\*\*|__)(.+?)\1", r"\2", t)
    t = re.sub(r"(?<![\w*])([*_])(?!\s)(.+?)(?<!\s)\1(?![\w*])", r"\2", t)
    t = t.replace("`", "")
    t = re.sub(r"\\([\\`*_{}\[\]()#+\-.!|>~])", r"\1", t)
    return re.sub(r"\s+", " ", t).strip()


def md_to_blocks(md):
    blocks = []
    lines = md.split("\n")
    cur, li, lord, i = [], [], False, 0

    def fp():
        nonlocal cur
        if cur:
            blocks.append(para(md_clean(" ".join(cur))))
        cur = []

    def fl():
        nonlocal li, lord
        li, lord = flush_items(blocks, li, lord)

    while i < len(lines):
        ln = lines[i]
        s = ln.strip()
        if not s:
            fp(); i += 1; continue                  # a blank line does not end a list: items are markdown-loose
        if re.fullmatch(r"-{3,}", s):
            fp(); fl(); i += 1; continue
        if re.match(r"^\*\*==>.*<==\*\*$", s) or s.startswith("**----- "):
            fp(); fl(); i += 1; continue            # picture placeholders / page markers
        m = re.match(r"^(#{1,6})\s+(.*)$", s)
        if m:
            fp(); fl()
            t = md_clean(m.group(2))
            if t:
                blocks.append(heading(len(m.group(1)), t))
            i += 1; continue
        if PIPE.match(s):
            fp(); fl()
            rows = []
            while i < len(lines) and PIPE.match(lines[i].strip()):
                r = lines[i].strip()
                if not SEP.match(r):
                    cells = [md_clean(c) for c in r.strip().strip("|").split("|")]
                    rows.append(cells)
                i += 1
            # a header row with every cell empty is the markdown writer's placeholder
            if rows and not any(rows[0]) and len(rows) > 1:
                rows = rows[1:]
            if rows:
                blocks.append(table(rows))
            continue
        m = re.match(r"^\s*(?:([-*+•])|(\d{1,3}[.)]))\s+(.*)$", ln)
        if m:
            fp()
            ordered = m.group(2) is not None
            if li and lord != ordered:
                fl()
            lord = ordered
            li.append(md_clean(m.group(3)))
            i += 1; continue
        if li and ln.startswith("  "):
            li[-1] += " " + md_clean(s)             # a wrapped item
        else:
            fl()
            cur.append(s)
        i += 1
    fp(); fl()
    return [b for b in blocks if b.get("text", "x")]


def run_pymupdf4llm(path):
    import pymupdf
    import pymupdf4llm

    md = pymupdf4llm.to_markdown(path, show_progress=False)
    with pymupdf.open(path) as d:
        n = d.page_count
    return model(md_to_blocks(md), n)


# ---------------------------------------------------------------------------- docling ----

_DOCLING = None


def docling_converter():
    global _DOCLING
    if _DOCLING is None:
        from docling.datamodel.base_models import InputFormat
        from docling.datamodel.pipeline_options import PdfPipelineOptions
        from docling.document_converter import DocumentConverter, PdfFormatOption

        opts = PdfPipelineOptions()
        opts.do_ocr = False            # the born-digital forms have a text layer; scans are not run
        opts.do_table_structure = True
        _DOCLING = DocumentConverter(format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts)})
    return _DOCLING


def docling_table(item):
    data = item.data
    nrows = data.num_rows
    grid = [[None] * data.num_cols for _ in range(nrows)]
    for c in data.table_cells:
        grid[c.start_row_offset_idx][c.start_col_offset_idx] = (
            (c.text or "").replace("\n", " ").strip(),
            max(1, c.end_row_offset_idx - c.start_row_offset_idx),
            max(1, c.end_col_offset_idx - c.start_col_offset_idx))
    rows = []
    for r in grid:
        cells = [c for c in r if c is not None]
        if cells:
            rows.append(cells)
    return table(rows)


def run_docling(path):
    from docling_core.types.doc import DocItemLabel

    L = DocItemLabel
    doc = docling_converter().convert(path).document
    blocks, furn = [], []
    li, lord = [], False
    for item, _level in doc.iterate_items():
        lab = getattr(item, "label", None)
        txt = (getattr(item, "text", "") or "").replace("\n", " ").strip()
        page = item.prov[0].page_no if getattr(item, "prov", None) else 1
        if lab == L.LIST_ITEM:
            o = bool(getattr(item, "enumerated", False))
            if li and lord != o:
                li, lord = flush_items(blocks, li, lord)
            lord = o
            li.append(txt)
            continue
        li, lord = flush_items(blocks, li, lord)
        if lab == L.TITLE:
            blocks.append(heading(1, txt))
        elif lab == L.SECTION_HEADER:
            blocks.append(heading(getattr(item, "level", 1) + 1, txt))
        elif lab in (L.TEXT, L.PARAGRAPH, L.FORMULA, L.REFERENCE, L.CHECKBOX_SELECTED, L.CHECKBOX_UNSELECTED):
            if txt:
                blocks.append(para(txt))
        elif lab == L.CAPTION:
            blocks.append({"kind": "caption", "text": txt})
        elif lab == L.FOOTNOTE:
            blocks.append({"kind": "note", "children": [para(txt)]} if txt else para(""))
        elif lab == L.CODE:
            blocks.append({"kind": "code", "text": txt})
        elif lab in (L.PAGE_HEADER, L.PAGE_FOOTER):
            furn.append({"role": "header" if lab == L.PAGE_HEADER else "footer", "pages": [page], "template": txt})
        elif lab in (L.TABLE, L.DOCUMENT_INDEX):
            blocks.append(docling_table(item))
        elif lab in (L.PICTURE, L.CHART):
            blocks.append({"kind": "figure"})
    flush_items(blocks, li, lord)
    blocks = [b for b in blocks if not (b["kind"] == "paragraph" and not b.get("text"))]
    n = len(doc.pages) or 1
    return model(blocks, n, [dict(f, id=900000 + i) for i, f in enumerate(furn)])


# ---------------------------------------------------------------------------- tesseract ----


def run_tesseract(path):
    """Tesseract's own text (SPEC 14.4), `--psm 3` over poppler's render at 300 dpi: one paragraph per
    blank-line-separated block of its output; nothing else is claimed."""
    import subprocess
    import tempfile

    with tempfile.TemporaryDirectory() as td:
        subprocess.run(["pdftoppm", "-r", "300", "-gray", "-png", path, os.path.join(td, "p")], check=True)
        pngs = sorted(f for f in os.listdir(td) if f.endswith(".png"))
        blocks = []
        for f in pngs:
            r = subprocess.run(["tesseract", os.path.join(td, f), "stdout", "--psm", "3", "-l", "eng"],
                               check=True, capture_output=True, text=True)
            for blk in re.split(r"\n\s*\n", r.stdout):
                t = " ".join(blk.split())
                if t:
                    blocks.append(para(t))
        return model(blocks, len(pngs))


TOOLS = {"pdfplumber": run_pdfplumber, "pymupdf4llm": run_pymupdf4llm, "docling": run_docling,
         "tesseract": run_tesseract}
DEFAULT_VARIANTS = {"pdfplumber": "pdf,pdf-tagged", "pymupdf4llm": "pdf,pdf-tagged",
                    "docling": "pdf,pdf-tagged", "tesseract": "scan"}


PKGS = {"pdfplumber": ["pdfplumber", "pdfminer.six"],
        "pymupdf4llm": ["pymupdf4llm", "pymupdf_layout", "PyMuPDF", "onnxruntime"],
        "docling": ["docling", "docling-core", "docling-ibm-models", "docling-parse", "torch"],
        "tesseract": []}


def write_meta(tool):
    from importlib import metadata

    def v(p):
        try:
            return metadata.version(p)
        except metadata.PackageNotFoundError:
            return "absent"

    if tool == "tesseract":
        import subprocess
        out = subprocess.run(["tesseract", "--version"], capture_output=True, text=True)
        ver = "tesseract " + (out.stdout or out.stderr).split()[1]
        pins = "poppler pdftoppm -r 300; --psm 3 -l eng"
    else:
        ver = "%s %s" % (tool, v(PKGS[tool][0]))
        pins = " ".join("%s==%s" % (p, v(p)) for p in PKGS[tool][1:])
        if tool == "docling":          # the model weights it fetched once from the Hugging Face hub, by revision
            import glob
            for d in sorted(glob.glob(os.path.expanduser("~/.cache/huggingface/hub/models--docling-project--*/snapshots/*"))):
                pins += " %s@%s" % (d.split("models--")[1].split("/")[0].replace("--", "/"), os.path.basename(d)[:12])
        if tool == "pymupdf4llm":      # it calls the system Tesseract on pages it judges to need OCR
            import subprocess
            out = subprocess.run(["tesseract", "--version"], capture_output=True, text=True)
            pins += " tesseract==" + (out.stdout or out.stderr).split()[1]
    meta = {"tool": tool, "version": ver, "pins": pins, "python": sys.version.split()[0]}
    d = os.path.join(OUT, tool)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, "META.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, sort_keys=True)


def main(argv):
    import logging
    logging.getLogger("pdfminer").setLevel(logging.ERROR)    # one "FontBBox" warning per font per page
    if not argv or argv[0] not in TOOLS:
        print(__doc__)
        return 2
    tool = argv[0]
    variants = DEFAULT_VARIANTS[tool].split(",")
    ids, force, limit = None, False, None
    a = argv[1:]
    while a:
        if a[0] == "--variants":
            variants = a[1].split(","); a = a[2:]
        elif a[0] == "--ids":
            ids = set(a[1].split(",")); a = a[2:]
        elif a[0] == "--force":
            force = True; a = a[1:]
        elif a[0] == "--limit":
            limit = int(a[1]); a = a[2:]
        else:
            print("unknown option", a[0]); return 2
    with open(os.path.join(CORPUS, "MANIFEST.json"), encoding="utf-8") as f:
        man = json.load(f)
    jobs = []
    for doc_id in sorted(man["docs"]):
        if ids and doc_id not in ids:
            continue
        files = man["docs"][doc_id]["files"]
        for v in variants:
            if v in files:
                jobs.append((doc_id, v, os.path.join(CORPUS, files[v]["path"])))
    if limit:
        jobs = jobs[:limit]
    run = TOOLS[tool]
    done = skipped = failed = 0
    t0 = time.time()
    for doc_id, v, path in jobs:
        out = os.path.join(OUT, tool, v, doc_id + ".json")
        if os.path.exists(out) and not force:
            skipped += 1
            continue
        os.makedirs(os.path.dirname(out), exist_ok=True)
        t = time.time()
        try:
            m = run(path)
        except Exception:
            failed += 1
            sys.stderr.write("FAILED %s/%s\n%s\n" % (v, doc_id, traceback.format_exc()))
            m = model([], 1)
            m["_failed"] = True
        with open(out + ".tmp", "w", encoding="utf-8") as f:
            json.dump(m, f, ensure_ascii=False, sort_keys=True)
        os.replace(out + ".tmp", out)
        done += 1
        print("%s %s/%s %.1fs" % (tool, v, doc_id, time.time() - t), flush=True)
    write_meta(tool)
    print("%s: %d run, %d kept, %d failed, %.0fs" % (tool, done, skipped, failed, time.time() - t0))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
