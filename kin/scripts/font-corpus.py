#!/usr/bin/env python3
"""Compare emon's font decoding with pdfminer.six on real PDFs (DEVLOG P1.6).

    python3 kin/scripts/font-corpus.py                  the committed sample + 200 more, report
    python3 kin/scripts/font-corpus.py --extra 400      more files
    python3 kin/scripts/font-corpus.py --write          also (re)write test/fonts/real.txt

A DEVELOPMENT TOOL, run by a person; no gate runs it and nothing here is a
runtime dependency (pdfminer.six and pypdf live in ~/.cache/emon/pylib, put
there once with `pip install --target ~/.cache/emon/pylib pdfminer.six pypdf`,
the way kin/scripts/json-check.py gets its libraries).

WHAT IS COMPARED. P1.7 (the content interpreter) is not here yet, so the codes
compared are the ones the pages really SHOW: pypdf reads each page's content
stream (and the Form XObjects it draws, three deep), follows `Tf`, and
collects the bytes of every `Tj` `TJ` `'` `"` per font object. Simple fonts'
codes are bytes; composite fonts with an Identity encoding are read two bytes
at a time (others are counted and skipped: their codespaces are P3). For each
(file, font object, code):

  reference   pdfminer.six: `get_font(...).to_unichr(cid)` and `char_width(cid)`
  emon        the generated Rust (`kin/scripts/font-sweep`): `pfn-map`, `pfn-width`

and the report says how often the two agree, by DISTINCT code and WEIGHTED BY
HOW OFTEN THE PAGE SHOWS IT (the latter is the rate a reader of the extracted
text would feel), and lists the disagreements by kind. The widths are compared
to a hundredth of a unit of 1/1000 em.

`--write` keeps a RATCHET: test/fonts/real.txt, rows in fonts.txt's format for up
to eight codes per font where emon and pdfminer AGREE, for kin/pdffonttest.kin
to hold against regressions.
"""
import hashlib
import os
import random
import re
import signal
import subprocess
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
CORPUS = os.path.join(ROOT, "corpus")
sys.path.insert(0, os.path.expanduser("~/.cache/emon/pylib"))

MAXPAGES = 6
MAXCODES = 300
MAXSIZE = 6 * 1024 * 1024


class Timeout(Exception):
    pass


def alarm(_s, _f):
    raise Timeout()


def sample_files(extra):
    files = []
    for line in open(os.path.join(ROOT, "test", "pdf", "corpus.txt")):
        if line.startswith("#"):
            continue
        f = line.rstrip("\n").split("\t")
        if f[1] == "ok":
            files.append(os.path.normpath(os.path.join(ROOT, "test", f[0])))
    have = set(files)
    allp = []
    for dp, dn, fn in os.walk(CORPUS):
        if "/_dl" in dp:
            continue
        for f in fn:
            if f.lower().endswith(".pdf"):
                p = os.path.join(dp, f)
                if p not in have and os.path.getsize(p) <= MAXSIZE:
                    allp.append(p)
    allp.sort(key=lambda p: hashlib.sha1(p.encode()).hexdigest())
    return files + allp[:extra]


def collect(path):
    """{font object number: {bytes string: count}} and notes, from the pages' content."""
    from pypdf import PdfReader
    from pypdf.generic import ContentStream, IndirectObject, ArrayObject
    reader = PdfReader(path)
    if reader.is_encrypted:
        return None, "encrypted"
    uses = {}
    fonts_seen = {}

    def raw(o):
        if hasattr(o, "original_bytes"):
            return bytes(o.original_bytes)
        return bytes(o)

    def walk(cs, res, depth):
        fonts = res.get("/Font") if res else None
        fonts = fonts.get_object() if fonts is not None else {}
        xobj = res.get("/XObject") if res else None
        xobj = xobj.get_object() if xobj is not None else {}
        cur = None
        for operands, op in cs.operations:
            if op == b"Tf" and operands:
                ref = fonts.get(operands[0])
                cur = ref.idnum if isinstance(ref, IndirectObject) else None
                if cur is not None:
                    fonts_seen[cur] = ref.get_object()
            elif op in (b"Tj", b"'") and operands and cur is not None:
                d = uses.setdefault(cur, {})
                s = raw(operands[0])
                d[s] = d.get(s, 0) + 1
            elif op == b'"' and len(operands) == 3 and cur is not None:
                d = uses.setdefault(cur, {})
                s = raw(operands[2])
                d[s] = d.get(s, 0) + 1
            elif op == b"TJ" and operands and cur is not None:
                d = uses.setdefault(cur, {})
                for el in operands[0]:
                    if isinstance(el, (int, float)):
                        continue
                    try:
                        s = raw(el)
                    except Exception:
                        continue
                    d[s] = d.get(s, 0) + 1
            elif op == b"Do" and operands and depth < 3:
                x = xobj.get(operands[0])
                if x is None:
                    continue
                x = x.get_object()
                if x.get("/Subtype") == "/Form":
                    try:
                        walk(ContentStream(x, reader), x.get("/Resources", res), depth + 1)
                    except Exception:
                        pass

    n = 0
    for page in reader.pages:
        if n >= MAXPAGES:
            break
        n += 1
        try:
            c = page.get_contents()
            if c is None:
                continue
            walk(c, page.get("/Resources"), 0)
        except Timeout:
            raise
        except Exception:
            continue
    return (uses, fonts_seen), None


def split_codes(fontdict, uses):
    """(kind, {code: weight}) or (None, reason) for a font's string bytes."""
    sub = fontdict.get("/Subtype")
    weights = {}
    if sub == "/Type0":
        enc = fontdict.get("/Encoding")
        enc = enc.get_object() if hasattr(enc, "get_object") else enc
        if enc not in ("/Identity-H", "/Identity-V"):
            return None, "composite-other"
        for s, n in uses.items():
            for i in range(0, len(s) - 1, 2):
                c = (s[i] << 8) | s[i + 1]
                weights[c] = weights.get(c, 0) + n
        return "composite", weights
    for s, n in uses.items():
        for b in s:
            weights[b] = weights.get(b, 0) + n
    return "simple", weights


def reference(path, wanted):
    """{(objid, code): (cps tuple or None, width or None)} from pdfminer.six."""
    from pdfminer.pdfparser import PDFParser
    from pdfminer.pdfdocument import PDFDocument
    from pdfminer.pdfinterp import PDFResourceManager
    from pdfminer.pdftypes import dict_value
    out = {}
    with open(path, "rb") as fp:
        doc = PDFDocument(PDFParser(fp))
        rm = PDFResourceManager()
        for objid, (kind, codes) in wanted.items():
            try:
                spec = dict_value(doc.getobj(objid))
                font = rm.get_font(objid, spec)
            except Exception:
                continue
            for code in codes:
                try:
                    cid = list(font.decode(code.to_bytes(2, "big") if kind == "composite" else bytes([code])))[0]
                except Exception:
                    continue
                try:
                    u = font.to_unichr(cid)
                    cps = tuple(ord(ch) for ch in u) if u else None
                except Exception:
                    cps = None
                try:
                    w = int(round(font.char_width(cid) * 100000))
                except Exception:
                    w = None
                out[(objid, code)] = (cps, w)
    return out


def sweep(requests):
    text = "".join(f"{p}\t{o}\t{','.join('%X' % c for c in codes)}\n" for p, o, codes in requests)
    r = subprocess.run([os.path.join(HERE, "font-sweep")], input=text, capture_output=True, text=True, cwd=ROOT)
    if r.returncode != 0:
        print(r.stderr[-2000:])
        sys.exit(1)
    fonts, codes = {}, {}
    cur = None
    for line in r.stdout.splitlines():
        f = line.split("\t")
        if f[0] == "F":
            cur = (f[1], int(f[2]))
            fonts[cur] = f[3:]
        elif f[0] == "C":
            cps = None if f[2] == "-" else tuple(int(x, 16) for x in f[2].split("+"))
            codes[(cur[0], cur[1], int(f[1], 16))] = (cps, f[3], None if f[4] == "-" else int(f[4]))
    return fonts, codes


def main():
    extra = 200
    if "--extra" in sys.argv:
        extra = int(sys.argv[sys.argv.index("--extra") + 1])
    write = "--write" in sys.argv
    files = sample_files(extra)
    signal.signal(signal.SIGALRM, alarm)
    plan = []            # (path, objid, kind, {code: weight})
    skipped = {}
    nfiles = 0
    for path in files:
        signal.alarm(40)
        try:
            res, why = collect(path)
        except Timeout:
            skipped["timeout"] = skipped.get("timeout", 0) + 1
            continue
        except Exception as e:
            skipped["pypdf-error"] = skipped.get("pypdf-error", 0) + 1
            continue
        finally:
            signal.alarm(0)
        if res is None:
            skipped[why] = skipped.get(why, 0) + 1
            continue
        uses, fontdicts = res
        nfiles += 1
        for objid, u in uses.items():
            kind, w = split_codes(fontdicts[objid], u)
            if kind is None:
                skipped[w] = skipped.get(w, 0) + 1
                continue
            top = sorted(w, key=lambda c: -w[c])[:MAXCODES]
            plan.append((path, objid, kind, {c: w[c] for c in top}))
    print(f"{nfiles} files, {len(plan)} fonts to compare; skipped: {skipped}")

    # the reference, per file
    ref = {}
    byfile = {}
    for path, objid, kind, w in plan:
        byfile.setdefault(path, {})[objid] = (kind, list(w))
    for path, wanted in byfile.items():
        signal.alarm(60)
        try:
            for (objid, code), v in reference(path, wanted).items():
                ref[(path, objid, code)] = v
        except Exception:
            pass
        finally:
            signal.alarm(0)

    fonts, answers = sweep([(p, o, list(w)) for p, o, k, w in plan])

    tot = {"distinct": 0, "weighted": 0}
    agree = {"distinct": 0, "weighted": 0}
    wagree = {"distinct": 0, "weighted": 0}
    cat = {}
    examples = {}
    howcount = {}
    wboth = {}
    wdiff = []
    groups = {}
    groupfiles = {}
    conf = {}
    load_bad = {}
    panics = 0
    for path, objid, kind, w in plan:
        fo = fonts.get((path, objid))
        if fo is None:
            continue
        if fo[0].startswith("PANIC"):
            panics += 1
            print("PANIC", path, objid, fo[0])
            continue
        if fo[0] != "0":
            load_bad[fo[0]] = load_bad.get(fo[0], 0) + 1
            continue
        for code, weight in w.items():
            e = answers.get((path, objid, code))
            r = ref.get((path, objid, code))
            if e is None or r is None:
                continue
            ecps, how, ew = e
            rcps, rw = r
            tot["distinct"] += 1
            tot["weighted"] += weight
            howcount[how] = howcount.get(how, 0) + weight
            if ecps == rcps:
                c = "equal" if ecps is not None else "both-none"
            else:
                se = "".join(chr(x) for x in (ecps or ()))
                sr = "".join(chr(x) for x in (rcps or ()))
                if unicodedata.normalize("NFKC", se) == unicodedata.normalize("NFKC", sr) and ecps and rcps:
                    c = "equal-after-NFKC"
                elif ecps is None:
                    c = "emon-none"
                elif rcps is None:
                    c = "ref-none"
                else:
                    c = "differ"
            cat[c] = cat.get(c, 0) + weight
            cat[c + "#"] = cat.get(c + "#", 0) + 1
            bh = conf.setdefault(how, {"n": 0, "w": 0, "an": 0, "aw": 0})
            bh["n"] += 1
            bh["w"] += weight
            if c in ("equal", "both-none", "equal-after-NFKC"):
                bh["an"] += 1
                bh["aw"] += weight
            if c in ("equal", "both-none", "equal-after-NFKC"):
                agree["distinct"] += 1
                agree["weighted"] += weight
            if c not in ("equal", "both-none"):
                gk = (c, how, tuple(ecps or ()), tuple(rcps or ()))
                groups[gk] = groups.get(gk, 0) + weight
                groupfiles.setdefault(gk, os.path.relpath(path, CORPUS) + ":%d:%X" % (objid, code))
            if c not in ("equal", "both-none") and len(examples.setdefault(c + "/" + how, [])) < 6:
                examples[c + "/" + how].append((os.path.relpath(path, CORPUS), objid, "%X" % code,
                                                ecps and ["%04X" % x for x in ecps], rcps and ["%04X" % x for x in rcps]))
            wboth["none-emon" if ew is None else "none-ref" if rw is None else "both"] = \
                wboth.get("none-emon" if ew is None else "none-ref" if rw is None else "both", 0) + weight
            if ew is not None and rw is not None and abs(ew - rw) > 100:
                wdiff.append((weight, os.path.relpath(path, CORPUS), objid, "%X" % code, ew, rw))
            if ew is not None and rw is not None:
                wagree["distinct"] += 1 if abs(ew - rw) <= 100 else 0
                wagree["weighted"] += weight if abs(ew - rw) <= 100 else 0
    print()
    print(f"code points: {agree['distinct']}/{tot['distinct']} distinct codes agree "
          f"({100.0 * agree['distinct'] / max(1, tot['distinct']):.2f}%), "
          f"{agree['weighted']}/{tot['weighted']} shown characters "
          f"({100.0 * agree['weighted'] / max(1, tot['weighted']):.2f}%)")
    print("by kind (shown characters / distinct codes):")
    for k in sorted(c for c in cat if not c.endswith("#")):
        print(f"  {k:20s} {cat[k]:10d} {cat[k + '#']:8d}")
    print("agreement by how emon obtained the value (shown characters; distinct codes):")
    for h in sorted(conf):
        b = conf[h]
        print(f"  {h:10s} {b['aw']:9d}/{b['w']:<9d} {100.0 * b['aw'] / max(1, b['w']):6.2f}%   "
              f"{b['an']:6d}/{b['n']:<6d} {100.0 * b['an'] / max(1, b['n']):6.2f}%")
    cw = sum(b["w"] for h, b in conf.items() if h != "unknown")
    ca = sum(b["aw"] for h, b in conf.items() if h != "unknown")
    cn = sum(b["n"] for h, b in conf.items() if h != "unknown")
    can = sum(b["an"] for h, b in conf.items() if h != "unknown")
    print(f"  CONFIDENT (how is not unknown): {ca}/{cw} shown characters {100.0 * ca / max(1, cw):.2f}%, "
          f"{can}/{cn} distinct codes {100.0 * can / max(1, cn):.2f}%")
    print("fonts emon would not load:", load_bad, "panics:", panics)
    print("widths within 0.001 em, of the codes both know: "
          f"{wagree['distinct']} distinct, {wagree['weighted']} shown characters; "
          f"shown characters by who knows a width: {wboth}")
    for x in sorted(wdiff, reverse=True)[:12]:
        print("   width differs:", x)
    print("the largest disagreement groups (shown characters; kind, how, emon, reference; first seen):")
    for gk, wt in sorted(groups.items(), key=lambda kv: -kv[1])[:25]:
        print(f"  {wt:8d}  {gk[0]:10s} {gk[1]:9s} emon {['%04X' % x for x in gk[2]]} ref {['%04X' % x for x in gk[3]]}  {groupfiles[gk]}")
    for k, v in sorted(examples.items()) if "--examples" in sys.argv else []:
        print("examples", k)
        for x in v:
            print("   ", x)

    if write:
        rows = ["# emon P1.6: fonts of real files from corpus/ (read-only), kin/pdffonttest.kin.",
                "# Written by kin/scripts/font-corpus.py: up to eight codes per font on which emon and pdfminer.six",
                "# AGREED when this was written (a ratchet: emon must keep answering them). Same format as fonts.txt."]
        picked = 0
        for path, objid, kind, w in plan:
            fo = fonts.get((path, objid))
            if not fo or fo[0] != "0" or picked >= 160:
                continue
            good = []
            for code in sorted(w, key=lambda c: -w[c]):
                e = answers.get((path, objid, code))
                r = ref.get((path, objid, code))
                if not e or not r or e[0] is None or e[0] != r[0]:
                    continue
                if e[2] is not None and r[1] is not None and abs(e[2] - r[1]) > 100:
                    continue
                good.append((code, e))
                if len(good) >= 8:
                    break
            if not good:
                continue
            picked += 1
            rel = os.path.relpath(path, os.path.join(ROOT, "test"))
            rows.append("\t".join(["font", rel, str(objid), fo[0], "composite" if kind == "composite" else "simple"]
                                  + [fo[3], fo[4], fo[2], fo[6], fo[5]]))
            for code, (cps, how, width) in good:
                rows.append("\t".join(["code", "%X" % code, "+".join("%04X" % x for x in cps), how,
                                       "-" if width is None else str(width)]))
        open(os.path.join(ROOT, "test", "fonts", "real.txt"), "w").write("\n".join(rows) + "\n")
        print("wrote test/fonts/real.txt:", picked, "fonts")


if __name__ == "__main__":
    main()
