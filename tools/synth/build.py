#!/usr/bin/env python3
"""Facts, manifest and gold for the synthetic corpus (DEVLOG P2.8, D063).

    tools/synth/build.py

Run by print.sh after the PDFs exist.  For every test/corpus/synth/html/STEM.html:

  1. LAYOUT FACTS from pdf/STEM.pdf (poppler's pdfinfo and pdftotext): the page
     count and sizes, and for each `<table data-multipage>` the page every
     row landed on (found by the row's first cell, which the corpus keeps
     unique).  These are the only things the HTML cannot say, and they are
     committed in MANIFEST.json so that nothing downstream needs a PDF tool;
  2. MANIFEST.json: producer, tool versions, sha256 and size of every file;
  3. gold/STEM.gold.json: html2gold.convert(html, facts).

kin/scripts/gold-check.py re-derives (3) from (HTML + MANIFEST facts) with
the standard library alone and requires it to equal the committed gold.
"""
import hashlib
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
CORPUS = os.path.join(ROOT, "test", "corpus", "synth")
sys.path.insert(0, HERE)
import html2gold  # noqa: E402


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        h.update(f.read())
    return h.hexdigest()


def run(*a):
    return subprocess.run(list(a), capture_output=True, text=True, check=True).stdout


def page_sizes(pdf):
    out = run("pdfinfo", "-f", "1", "-l", "9999", pdf)
    sizes = []
    for m in re.finditer(r"^Page\s+(\d+) size:\s+([\d.]+) x ([\d.]+) pts", out, re.M):
        sizes.append([round(float(m.group(2)), 2), round(float(m.group(3)), 2)])
    n = int(re.search(r"^Pages:\s+(\d+)", out, re.M).group(1))
    assert n == len(sizes), (pdf, n, len(sizes))
    producer = re.search(r"^Producer:\s+(.*)$", out, re.M)
    return sizes, (producer.group(1) if producer else None)


def norm(s):
    return re.sub(r"\s+", " ", s)


def row_pages(html, pdf, npages):
    """{table id: [page of each <tr> in document order]} for data-multipage tables."""
    root = html2gold.parse(html)
    texts = {}

    def text(p):
        if p not in texts:
            texts[p] = norm(run("pdftotext", "-f", str(p), "-l", str(p), "-raw", pdf, "-"))
        return texts[p]
    out = {}
    for t in html2gold.walk(root):
        if t.tag != "table" or t.get("data-multipage") is None:
            continue
        pages, cur = [], 1
        for tr in html2gold.walk(t):
            if tr.tag != "tr":
                continue
            first = next(c for c in tr.kids if isinstance(c, html2gold.El) and c.tag in ("td", "th"))
            key = html2gold.text_content(first)
            for p in range(cur, npages + 1):
                if re.search(r"(?<![\w-])" + re.escape(key) + r"(?![\w-])", text(p)):
                    cur = p
                    break
            else:
                raise SystemExit("row %r of table %s not found in %s" % (key, t.get("id"), pdf))
            pages.append(cur)
        out[t.get("id")] = pages
    return out


def tool_version(*cmd):
    r = subprocess.run(list(cmd), capture_output=True, text=True)
    return (r.stdout + r.stderr).strip().splitlines()[0]


def main():
    stems = sorted(f[:-5] for f in os.listdir(os.path.join(CORPUS, "html")) if f.endswith(".html"))
    producer = json.load(open(os.path.join(CORPUS, "PRODUCER.json"), encoding="utf-8"))
    manifest = {
        "format": 1,
        "note": "Committed so that no gate needs a producer (D041). Regenerate with tools/synth/print.sh.",
        "producer": producer,
        "tools": {"pdfinfo": tool_version("pdfinfo", "-v"), "magick": tool_version("magick", "-version")},
        "docs": {},
    }
    os.makedirs(os.path.join(CORPUS, "gold"), exist_ok=True)
    for stem in stems:
        hp = os.path.join(CORPUS, "html", stem + ".html")
        pp = os.path.join(CORPUS, "pdf", stem + ".pdf")
        html = open(hp, encoding="utf-8").read()
        sizes, prod = page_sizes(pp)
        facts = {"pages": sizes}
        rp = row_pages(html, pp, len(sizes))
        if rp:
            facts["rows"] = rp
        rel = lambda d, ext=".pdf": "%s/%s%s" % (d, stem, ext)
        files = {"html": rel("html", ".html"), "pdf": rel("pdf")}
        if os.path.exists(os.path.join(CORPUS, rel("pdf-tagged"))):
            files["pdf-tagged"] = rel("pdf-tagged")
        if os.path.exists(os.path.join(CORPUS, rel("scan"))):
            files["scan"] = rel("scan")
        entry = {"files": {k: {"path": v, "sha256": sha(os.path.join(CORPUS, v)),
                               "bytes": os.path.getsize(os.path.join(CORPUS, v))} for k, v in files.items()},
                 "pdf-producer": prod, "facts": facts}
        manifest["docs"][stem] = entry
        prov = {"html": files["html"], "pdf": files["pdf"], "producer": "%s; %s" % (producer["version"], prod)}
        variants = {k: v for k, v in files.items() if k in ("pdf-tagged", "scan")}
        if variants:
            prov["variants"] = variants
        gold = html2gold.convert(html, facts, stem, prov)
        with open(os.path.join(CORPUS, "gold", stem + ".gold.json"), "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(gold, ensure_ascii=False, indent=1) + "\n")
    with open(os.path.join(CORPUS, "MANIFEST.json"), "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n")
    print("  built %d documents: MANIFEST.json and gold/*.gold.json" % len(stems))


if __name__ == "__main__":
    main()
