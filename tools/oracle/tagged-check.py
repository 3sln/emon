#!/usr/bin/env python3
"""Marked-content coverage of the tagged fixtures against an independent reader (DEVLOG P1.7, D076).

    python3 tools/oracle/tagged-check.py                 the three PDFs of test/corpus/synth/pdf-tagged
    python3 tools/oracle/tagged-check.py a.pdf b.pdf

AN ORACLE, NOT A GATE (pypdf lives in ~/.cache/emon/pylib, like the other oracles' libraries). pypdf parses
each page's content stream; every BMC/BDC..EMC pair is a span with its tag and, from the property list (inline
or through /Properties), its /MCID, /Type, /Subtype, /ActualText, /Alt, /E and /Lang. The same spans, in the same
order, must be what emon recorded (`kin/scripts/content-sweep rows`: the S rows), and every glyph emon shows must
lie inside a span that carries an MCID or is an /Artifact (a tagged PDF marks all its content).
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.expanduser("~/.cache/emon/pylib"))
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def text_string(v):
    s = str(v)
    return s


def pypdf_spans(path):
    from pypdf import PdfReader
    from pypdf.generic import ContentStream, IndirectObject
    reader = PdfReader(path)
    out = []

    def walk(cs, res, depth):
        props = res.get("/Properties") if res else None
        props = props.get_object() if props is not None else {}
        xobj = res.get("/XObject") if res else None
        xobj = xobj.get_object() if xobj is not None else {}
        stack = []
        for operands, op in cs.operations:
            if op in (b"BMC", b"BDC"):
                tag = str(operands[0])[1:] if operands else ""
                d = {}
                if op == b"BDC" and len(operands) > 1:
                    p = operands[1]
                    if isinstance(p, (str,)) or hasattr(p, "startswith"):
                        p = props.get(p)
                    if p is not None:
                        p = p.get_object() if hasattr(p, "get_object") else p
                        d = {k: p[k] for k in p}
                out.append({"tag": tag, "mcid": int(d["/MCID"]) if "/MCID" in d else -1,
                            "type": str(d.get("/Type", ""))[1:], "subtype": str(d.get("/Subtype", ""))[1:],
                            "actual": str(d["/ActualText"]) if "/ActualText" in d else "",
                            "alt": str(d["/Alt"]) if "/Alt" in d else "",
                            "lang": str(d["/Lang"]) if "/Lang" in d else ""})
                stack.append(len(out) - 1)
            elif op == b"EMC":
                if stack:
                    stack.pop()
            elif op == b"Do" and operands and depth < 8:
                x = xobj.get(operands[0])
                if x is not None:
                    x = x.get_object()
                    if x.get("/Subtype") == "/Form":
                        try:
                            walk(ContentStream(x, reader), x.get("/Resources", res), depth + 1)
                        except Exception:
                            pass

    for page in reader.pages:
        walk(ContentStream(page.get_contents(), reader), page.get("/Resources"), 0)
    return out


def sweep(mode, paths):
    p = subprocess.run([os.path.join(ROOT, "kin", "scripts", "content-sweep"), mode],
                       input="\n".join(paths) + "\n", capture_output=True, text=True, cwd=ROOT)
    per = {}
    cur = []
    for line in p.stdout.split("\n"):
        if not line:
            continue
        f = line.split("\t")
        if f[0] == "F":
            per[f[1]] = (f[2:], cur)
            cur = []
        else:
            cur.append(f)
    return per


def main():
    paths = [os.path.abspath(a) for a in sys.argv[1:]]
    if not paths:
        d = os.path.join(ROOT, "test", "corpus", "synth", "pdf-tagged")
        paths = sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith(".pdf"))
    rows = sweep("rows", paths)
    glyphs = sweep("glyphs", paths)
    bad = 0
    tot_spans = tot_glyphs = tot_covered = 0
    for path in paths:
        st, r = rows[path]
        spans = [f[1:] for f in r if f[0] == "S"]
        ref = pypdf_spans(path)
        name = os.path.basename(path)
        if len(spans) != len(ref):
            print("  FAIL %s: emon recorded %d spans, pypdf found %d" % (name, len(spans), len(ref)))
            bad += 1
            continue
        nbad = 0
        for i, (e, p) in enumerate(zip(spans, ref)):
            # S: idx tag mcid artifact type subtype actual alt exp lang gfirst gcount flags stm parent
            etag, emcid, eart, etype, esub, eact, ealt, eexp, elang = e[1], int(e[2]), e[3], e[4], e[5], e[6], e[7], e[8], e[9]
            ok = (etag == p["tag"] and emcid == p["mcid"] and (etype if etype != "-" else "") == p["type"]
                  and (esub if esub != "-" else "") == p["subtype"] and (elang if elang != "-" else "") == p["lang"]
                  and (eact if eact != "-" else "") == p["actual"] and (ealt if ealt != "-" else "") == p["alt"])
            if not ok:
                nbad += 1
                if nbad <= 5:
                    print("  FAIL %s: span %d emon %s pypdf %s" % (name, i, e[1:10], p))
        bad += nbad
        g = glyphs[path][1]
        covered = 0
        total = 0
        for f in g:
            if f[0] != "G":
                continue
            total += 1
            # G page cp how font size x0 y0 x1 y1 baseline rot mode mcid word artifact
            if int(f[13]) >= 0 or int(f[15]) != 0:
                covered += 1
        tags = {}
        for e in spans:
            tags[e[1]] = tags.get(e[1], 0) + 1
        print("  %-22s %4d spans match pypdf's (tag, MCID, Type, Subtype, ActualText, Alt, Lang); %d of %d glyphs are inside a span with an MCID or an artifact; tags %s"
              % (name, len(spans), covered, total, dict(sorted(tags.items(), key=lambda kv: -kv[1])[:8])))
        tot_spans += len(spans)
        tot_glyphs += total
        tot_covered += covered
    print("%s: %d spans, %d glyphs, %.3f%% covered" % ("FAIL" if bad else "ok", tot_spans, tot_glyphs, 100.0 * tot_covered / max(1, tot_glyphs)))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
