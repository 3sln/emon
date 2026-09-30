#!/usr/bin/env python3
"""The synthetic gold's validity check (DEVLOG P2.8, D041, D063).   bb unit runs it.

NO PRODUCER IS INVOKED, and no PDF tool: this reads only committed files.

  1. every test/corpus/synth/gold/*.gold.json validates against
     schema/emon-gold-1.json (which reuses schema/emon-1.json's definitions);
  2. ids are unique and in document order, every `ref` targets a `note`, and
     furniture ids continue after the body's;
  3. MANIFEST.json is honest: every html/pdf/pdf-tagged/scan/asset file it lists
     exists with the recorded sha256 (an `asset` is a file the HTML refers to, the
     PNG of initial-image), every html has a gold and a PDF, and every PDF is a
     PDF, with the producer and its version recorded;
  3b. the gold's `facts` (P2.13, D114) are coherent: every id names a leaf block of the body, `frags` only
     in a document with `annotated.layout` and then for every leaf block (pages inside the page table, in
     reading order), `lines` for every block with `align` or an initial (and a block left `justify` spans
     more than one line: one line is `start`), every `hyphens` entry's `printed` is its `word` with one
     hyphen inside;
  4. the gold IS WHAT THE CONVERTER SAYS: tools/synth/html2gold.clj (babashka, run
     once for all stems) over the committed HTML and the manifest's layout facts reproduces each gold
     byte for byte (so a hand edit of a gold, or of a document, is caught).

`jsonschema` is found as json-check.py finds it.
"""
import hashlib
import importlib.util
import json
import os
import subprocess
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CORPUS = os.path.join(ROOT, "test", "corpus", "synth")


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def converter_disagrees():
    """Stems whose committed gold differs from what tools/synth/html2gold.clj (babashka) derives from the
    committed HTML + MANIFEST facts: one bb process for all of them (the converter was Python until
    the scripting-is-babashka decision; the schema validation below stays a Python oracle)."""
    r = subprocess.run(["bb", "-cp", "tools:tools/lib", "-m", "synth.html2gold", "check"], cwd=ROOT,
                       capture_output=True, text=True)
    if r.returncode not in (0, 1):
        print("  FAIL gold-check: html2gold (bb) exited %d: %s" % (r.returncode, r.stderr.strip()[-300:]))
        sys.exit(1)
    return set(r.stdout.split())


def main():
    differs = converter_disagrees()
    bad = 0

    def fail(msg):
        nonlocal bad
        print("  FAIL gold-check: " + msg)
        bad += 1

    manifest = json.load(open(os.path.join(CORPUS, "MANIFEST.json"), encoding="utf-8"))
    prod = manifest.get("producer", {})
    if not prod.get("version") or not prod.get("name"):
        fail("MANIFEST.json does not record the producer and its version")
    stems = sorted(f[:-5] for f in os.listdir(os.path.join(CORPUS, "html")) if f.endswith(".html"))
    golds = sorted(f[:-10] for f in os.listdir(os.path.join(CORPUS, "gold")) if f.endswith(".gold.json"))
    if stems != golds or stems != sorted(manifest["docs"]):
        fail("html, gold and MANIFEST.json do not list the same documents")
    if len(stems) < 30:
        fail("only %d documents (P2.8 asks for about 30)" % len(stems))

    # 3. manifest honesty
    nfiles = 0
    for stem, d in manifest["docs"].items():
        for kind, f in d["files"].items():
            p = os.path.join(CORPUS, f["path"])
            if not os.path.exists(p):
                fail("%s: %s missing" % (stem, f["path"]))
                continue
            data = open(p, "rb").read()
            nfiles += 1
            if hashlib.sha256(data).hexdigest() != f["sha256"] or len(data) != f["bytes"]:
                fail("%s: %s differs from MANIFEST.json (regenerate with tools/synth/print.sh)" % (stem, f["path"]))
            if kind not in ("html", "asset") and not data.startswith(b"%PDF-"):
                fail("%s: %s is not a PDF" % (stem, f["path"]))

    # 1, 2, 4
    js = load(os.path.join(ROOT, "kin", "scripts", "json-check.py"), "json_check").find_jsonschema()
    validator = None
    if js is None:
        print("  SKIP gold-check: no jsonschema (schema step only)")
    else:
        from referencing import Registry, Resource
        from referencing.jsonschema import DRAFT202012
        one = json.load(open(os.path.join(ROOT, "schema", "emon-1.json"), encoding="utf-8"))
        gold_schema = json.load(open(os.path.join(ROOT, "schema", "emon-gold-1.json"), encoding="utf-8"))
        js.Draft202012Validator.check_schema(gold_schema)
        reg = Registry().with_resources([(one["$id"], Resource.from_contents(one, DRAFT202012)),
                                         (gold_schema["$id"], Resource.from_contents(gold_schema, DRAFT202012))])
        validator = js.Draft202012Validator(gold_schema, registry=reg)
    for stem in stems:
        gp = os.path.join(CORPUS, "gold", stem + ".gold.json")
        text = open(gp, encoding="utf-8").read()
        gold = json.loads(text)
        if validator:
            for e in sorted(validator.iter_errors(gold), key=lambda e: list(e.path))[:3]:
                fail("%s: %s at /%s" % (stem, e.message[:160], "/".join(map(str, e.path))))
        # ids
        seen = []

        def rec(n):
            seen.append(n["id"])
            for c in n.get("children", []):
                if c["kind"] not in ("text", "span", "link", "ref", "linebreak", "initial"):
                    rec(c)
        body = gold["model"]["body"]
        rec(body)
        if seen != list(range(len(seen))):
            fail("%s: body ids are not 0..n in document order" % stem)
        notes = set()

        def collect(n):
            if n["kind"] == "note":
                notes.add(n["id"])
            for c in n.get("children", []):
                if c["kind"] not in ("text", "span", "link", "ref", "linebreak", "initial"):
                    collect(c)
        collect(body)

        def refs(nodes):
            for n in nodes:
                if n["kind"] == "ref" and n["target"] not in notes:
                    fail("%s: ref %r targets no note" % (stem, n["marker"]))
                refs(n.get("children", []))

        def walk(n):
            refs(n.get("inlines", []))
            for c in n.get("children", []):
                if c["kind"] not in ("text", "span", "link", "ref", "linebreak", "initial"):
                    walk(c)
        walk(body)
        fids = [f["id"] for f in gold["model"]["furniture"]]
        if fids != list(range(len(seen), len(seen) + len(fids))):
            fail("%s: furniture ids do not continue after the body's" % stem)
        npages_ = len(gold["model"]["pages"])
        if len(gold["model"]["pages"]) != gold["model"]["source"]["pages"]:
            fail("%s: source.pages and the page table disagree" % stem)
        # 3b. facts (D114)
        facts = gold.get("facts")
        layout = bool(gold["annotated"].get("layout"))
        leaves = {}

        def leafwalk(n):
            if isinstance(n.get("text"), str) and n["text"] != "" and n["kind"] != "document":
                leaves[n["id"]] = n
            for c in n.get("children", []):
                if c["kind"] not in ("text", "span", "link", "ref", "linebreak", "initial"):
                    leafwalk(c)
        leafwalk(body)
        needs = [i for i, n in leaves.items()
                 if "align" in n or any(x["kind"] == "initial" for x in n.get("inlines", []))]
        if facts is None:
            if layout:
                fail("%s: annotated.layout without facts" % stem)
            if needs:
                fail("%s: blocks with align or an initial, and no facts.lines" % stem)
        else:
            frags = facts.get("frags")
            if frags is not None and not layout:
                fail("%s: facts.frags in a document that does not annotate layout" % stem)
            if layout and frags is None:
                fail("%s: annotated.layout without facts.frags" % stem)
            for k, v in (frags or {}).items():
                if int(k) not in leaves:
                    fail("%s: facts.frags id %s is no leaf block" % (stem, k))
                pages = [f[0] for f in v]
                if pages != sorted(pages) or pages[0] < 1 or pages[-1] > npages_:
                    fail("%s: facts.frags %s has pages %r outside 1..%d or out of order" % (stem, k, pages, npages_))
            if layout:
                for i in leaves:
                    if str(i) not in (frags or {}):
                        fail("%s: layout document but leaf block %d has no frags" % (stem, i))
            for k, v in facts.get("lines", {}).items():
                if int(k) not in leaves:
                    fail("%s: facts.lines id %s is no leaf block" % (stem, k))
                if leaves[int(k)].get("align") == "justify" and v == 1:
                    fail("%s: block %s is justify but printed one line (it is start)" % (stem, k))
            for i in needs:
                if str(i) not in facts.get("lines", {}):
                    fail("%s: block %d has align or an initial and no facts.lines" % (stem, i))
            for h in facts.get("hyphens", []):
                n = leaves.get(h["id"])
                p = h["printed"]
                if n is None or h["word"] not in n["text"].split() or not any(
                        p[:j] + p[j + 1:] == h["word"] and p[j] in "-\u2010" for j in range(1, len(p) - 1)):
                    fail("%s: facts.hyphens entry %r is not a hyphenated word of block %r" % (stem, h, h["id"]))
        # 4. the converter says so
        if stem in differs:
            fail("%s: gold differs from tools/synth/html2gold.clj over the committed HTML" % stem)
    if bad:
        return 1
    print("  ok   gold-check: %d gold files valid, ids in order, equal to html2gold over the HTML%s"
          % (len(stems), "" if validator else " (schema skipped)"))
    print("  ok   gold-check: MANIFEST.json agrees with %d committed files (producer: %s)"
          % (nfiles, prod.get("version")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
