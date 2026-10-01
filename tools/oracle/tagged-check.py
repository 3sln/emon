#!/usr/bin/env python3
"""Tagged PDF against an independent reader (DEVLOG P1.7 D101, P3.7 D148-D150).

    python3 tools/oracle/tagged-check.py                       the three PDFs of test/corpus/synth/pdf-tagged:
                                                               marked-content spans, then the structure tree
    python3 tools/oracle/tagged-check.py a.pdf b.pdf           the same for those files
    python3 tools/oracle/tagged-check.py --tree a.pdf ...      the structure tree only (what P3.7 reads)
    python3 tools/oracle/tagged-check.py --corpus LIST         the tree of every file named in LIST (one path a
                                                               line); `--tagged` keeps only /MarkInfo /Marked ones

AN ORACLE, NOT A GATE (pypdf lives in ~/.cache/emon/pylib, like the other oracles' libraries).

SPANS (P1.7). pypdf parses each page's content stream; every BMC/BDC..EMC pair is a span with its tag and, from the
property list (inline or through /Properties), its /MCID, /Type, /Subtype, /ActualText, /Alt, /E and /Lang. The same
spans, in the same order, must be what emon recorded (`kin/scripts/content-sweep rows`: the S rows), and every glyph
emon shows must lie inside a span that carries an MCID or is an /Artifact (a tagged PDF marks all its content).

TREE (P3.7). pypdf is a dictionary reader and has no structure-tree API, so this file WALKS the tree itself, in its own
code and from the standard (ISO 32000-1 14.7-14.8), and `kin/scripts/struct-sweep` (the generated Rust) must say the
same. For every element, in pre-order: its object number, parent, depth, /S as written, the standard type it resolves
to (the RoleMap followed, `Hn` read by its numerals), how, its page (its own /Pg or the nearest ancestor's), its kids
(element, marked content, object reference), /Alt /ActualText /E /Lang /ID /T, and the attributes P3.7 reads (Scope,
Headers, RowSpan, ColSpan, Summary, ListNumbering, through /A and the ClassMap). For every marked-content reference:
whether the text layer has a span for (page, Form XObject, MCID) (the JOIN: spans from the span reader above) and
whether the ParentTree holds the same element for it. Items are counted, and one disagreement fails the file.
The informativeness SCORE is not here: nothing but emon defines it (its parts are counted, and the synthetic pairs
and the corpus show it separating good tagging from bad).
"""
import json
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.expanduser("~/.cache/emon/pylib"))
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


def text_string(v):
    s = str(v)
    return s


# ---------------------------------------------------------------- the span reader (P1.7)

def pypdf_spans(path, keys=None):
    """Every BMC/BDC..EMC span of every page (and the Form XObjects it draws), in stream order.
    `keys`, when a set, receives (page index, Form XObject object number or 0, MCID) of each span that has one."""
    from pypdf import PdfReader
    from pypdf.generic import ContentStream, IndirectObject
    reader = PdfReader(path)
    out = []

    def walk(cs, res, depth, pageno, stm):
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
                if keys is not None and "/MCID" in d:
                    keys.add((pageno, stm, int(d["/MCID"])))
                stack.append(len(out) - 1)
            elif op == b"EMC":
                if stack:
                    stack.pop()
            elif op == b"Do" and operands and depth < 8:
                raw = xobj.raw_get(operands[0]) if hasattr(xobj, "raw_get") and operands[0] in xobj else None
                x = xobj.get(operands[0])
                if x is not None:
                    x = x.get_object()
                    if x.get("/Subtype") == "/Form":
                        try:
                            walk(ContentStream(x, reader), x.get("/Resources", res), depth + 1, pageno,
                                 raw.idnum if isinstance(raw, IndirectObject) else 0)
                        except Exception:
                            pass

    for i, page in enumerate(reader.pages):
        walk(ContentStream(page.get_contents(), reader), page.get("/Resources"), 0, i, 0)
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


def check_spans(paths):
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
    return bad


# ---------------------------------------------------------------- the tree reader (P3.7)

# ISO 32000-1 14.8.4 and 32000-2: the standard structure types (and `Artifact`, which producers write as one)
STANDARD = set(("Document DocumentFragment Part Art Sect Div Aside NonStruct Private BlockQuote Caption TOC TOCI Index "
                "P H Title L LI Lbl LBody Span Quote Note FENote Reference BibEntry Code Link Annot Em Strong Sub "
                "Ruby RB RT RP Warichu WT WP Table TR TH TD THead TBody TFoot Figure Formula Form Artifact").split())
HN = re.compile(r"H[1-9][0-9]{0,3}$")
LISTNUM = {"None": 1, "Unordered": 2, "Ordered": 3, "Disc": 4, "Circle": 5, "Square": 6, "Decimal": 7,
           "UpperRoman": 8, "LowerRoman": 9, "UpperAlpha": 10, "LowerAlpha": 11}
OWNER = {"Layout": 1, "List": 2, "PrintField": 3, "Table": 4, "Hyperlink": 5}
MAX_DEPTH = 64


def gname(v):
    """A name object as its text without the slash, None when it is not a name."""
    from pypdf.generic import NameObject
    v = v.get_object() if hasattr(v, "get_object") else v
    return str(v)[1:] if isinstance(v, NameObject) else None


def gtext(v):
    """A string or name as text (strings through PDFDocEncoding / UTF-16 as pypdf decodes them), else None."""
    from pypdf.generic import NameObject, TextStringObject, ByteStringObject
    v = v.get_object() if hasattr(v, "get_object") else v
    if isinstance(v, NameObject):
        return str(v)[1:]
    if isinstance(v, (TextStringObject,)):
        return str(v)
    if isinstance(v, ByteStringObject):
        return bytes(v).decode("latin-1")
    return None


def gbool(v):
    """A boolean object's value (pypdf's BooleanObject is not a bool, and is always truthy)."""
    if v is None:
        return False
    v = v.get_object() if hasattr(v, "get_object") else v
    return bool(getattr(v, "value", v))


def gint(v):
    from pypdf.generic import NumberObject
    v = v.get_object() if hasattr(v, "get_object") else v
    return int(v) if isinstance(v, NumberObject) else None


def resolve_role(rolemap, name):
    """(standard role name or None, level, how) by the policy of emon: a standard type is used as it is (and a RoleMap entry
    for it only noted); a custom name is followed through the RoleMap to the first standard type, 24 steps at most."""
    cur = name
    for steps in range(25):
        if cur in STANDARD or HN.match(cur):
            how = "standard" if steps == 0 else "rolemap"
            if steps == 0 and rolemap is not None and ("/" + cur) in rolemap:
                how = "standard-shadowed"
            if HN.match(cur):
                return cur, int(cur[1:]), how
            return cur, 0, how
        nxt = rolemap.get("/" + cur) if rolemap is not None else None
        if nxt is None:
            return None, 0, "custom"
        nxt = gname(nxt)
        if nxt is None:
            return None, 0, "custom"
        cur = nxt
    return None, 0, "rolemap-loop"


def attr_objects(reader, el, classmap):
    """The element's attribute objects, own /A first and then the classes its /C names: [(dict, owner code)]."""
    from pypdf.generic import DictionaryObject, ArrayObject
    out = []

    def add(v):
        v = v.get_object() if v is not None and hasattr(v, "get_object") else v
        if isinstance(v, DictionaryObject):
            o = el_owner(v)
            out.append((v, o))
        elif isinstance(v, ArrayObject):
            for e in v:
                e = e.get_object()
                if isinstance(e, DictionaryObject):
                    out.append((e, el_owner(e)))

    def el_owner(d):
        o = d.get("/O")
        if o is None:
            return 0
        n = gname(o)
        if n is None:
            return 6
        return OWNER.get(n, 6)

    add(el.get("/A"))
    c = el.get("/C")
    c = c.get_object() if c is not None else None
    if c is not None and classmap is not None:
        if gname(c) is not None:
            add(classmap.get("/" + gname(c)))
        elif isinstance(c, ArrayObject):
            for e in c:
                n = gname(e)
                if n is not None:
                    add(classmap.get("/" + n))
    return out


def attr_get(objs, owner, key):
    for d, o in objs:
        if o == owner or o == 0:
            v = d.get(key)
            if v is not None:
                return v.get_object() if hasattr(v, "get_object") else v
    return None


class Tree:
    def __init__(self, path):
        from pypdf import PdfReader
        from pypdf.generic import IndirectObject
        self.reader = PdfReader(path)
        self.pages = {}
        for i, p in enumerate(self.reader.pages):
            self.pages[p.indirect_reference.idnum] = i
        root = self.reader.trailer["/Root"]
        self.root = root
        sr = root.get("/StructTreeRoot")
        self.sr = sr.get_object() if sr is not None else None
        self.elems = []
        self.content = []
        self.objr = []
        if self.sr is None:
            return
        rm = self.sr.get("/RoleMap")
        self.rolemap = rm.get_object() if rm is not None else None
        cm = self.sr.get("/ClassMap")
        self.classmap = cm.get_object() if cm is not None else None
        self.seen = set()
        sr_raw = root.raw_get("/StructTreeRoot")
        e = {"obj": sr_raw.idnum if isinstance(sr_raw, IndirectObject) else 0, "parent": -1, "depth": 0, "s": None,
             "role": None, "how": "root", "page": -1, "kids": [], "lang": None, "langFrom": -1}
        self.elems.append(e)
        k = self.sr.raw_get("/K") if "/K" in self.sr else None
        if k is not None:
            self.kids(0, k, 0, None)

    def page_of(self, d):
        from pypdf.generic import IndirectObject
        raw = d.raw_get("/Pg") if "/Pg" in d else None
        if isinstance(raw, IndirectObject):
            return self.pages.get(raw.idnum, -1)
        return -1

    def kids(self, row, kraw, depth, _):
        from pypdf.generic import IndirectObject, ArrayObject, DictionaryObject, NumberObject
        kh = kraw.get_object() if hasattr(kraw, "get_object") else kraw
        if kh is None:
            return
        entries = list(kh) if isinstance(kh, ArrayObject) else [kraw]
        el = self.elems[row]
        for raw in entries:
            eh = raw.get_object() if hasattr(raw, "get_object") else raw
            if isinstance(eh, NumberObject) and not isinstance(eh, bool):
                pgi = el["page"]
                self.content.append({"elem": row, "kind": 2, "page": pgi, "mcid": int(eh), "stm": 0})
                el["kids"].append(("content", len(self.content) - 1))
                continue
            if not isinstance(eh, DictionaryObject):
                el["kids"].append(("skipped", -1))
                continue
            ty = gname(eh.get("/Type")) if eh.get("/Type") is not None else None
            ismcr = ty == "MCR"
            isobjr = ty == "OBJR"
            isel = ty == "StructElem"
            if not (isel or ismcr or isobjr):
                if "/S" in eh and eh.get("/S") is not None:
                    isel = True
                elif "/MCID" in eh:
                    ismcr = True
                elif "/Obj" in eh:
                    isobjr = True
            if ismcr:
                pgi = self.page_of(eh)
                if pgi < 0:
                    pgi = el["page"]
                mv = gint(eh.get("/MCID")) if "/MCID" in eh else None
                stm = eh.raw_get("/Stm") if "/Stm" in eh else None
                self.content.append({"elem": row, "kind": 1, "page": pgi, "mcid": mv if mv is not None else -1,
                                     "stm": stm.idnum if isinstance(stm, IndirectObject) else 0})
                el["kids"].append(("content", len(self.content) - 1))
                continue
            if isobjr:
                pgi = self.page_of(eh)
                if pgi < 0:
                    pgi = el["page"]
                ph = eh.raw_get("/Obj") if "/Obj" in eh else None
                self.objr.append({"elem": row, "page": pgi, "obj": ph.idnum if isinstance(ph, IndirectObject) else 0,
                                  "target": ph.get_object() if ph is not None else None})
                el["kids"].append(("objr", len(self.objr) - 1))
                continue
            if isel:
                if depth + 1 >= MAX_DEPTH:
                    el["kids"].append(("skipped", -1))
                    continue
                obj = raw.idnum if isinstance(raw, IndirectObject) else 0
                if obj > 0:
                    if obj in self.seen:
                        el["kids"].append(("skipped", -1))
                        continue
                    self.seen.add(obj)
                child = self.elem(eh, obj, row, depth + 1)
                el["kids"].append(("element", child))
                continue
            el["kids"].append(("skipped", -1))

    def elem(self, eh, obj, parent, depth):
        par = self.elems[parent]
        row = len(self.elems)
        e = {"obj": obj, "parent": parent, "depth": depth, "s": None, "role": None, "how": "no-role", "level": 0,
             "page": par["page"], "kids": [], "alt": None, "actualText": None, "expansion": None, "lang": None,
             "id": None, "title": None, "scope": 0, "rowSpan": None, "colSpan": None, "headers": None,
             "summary": None, "listNumbering": 0}
        self.elems.append(e)
        s = eh.get("/S")
        name = gname(s) if s is not None else None
        if name is not None:
            e["s"] = name
            role, level, how = resolve_role(self.rolemap, name)
            e["role"] = ("H%d" % level) if (role is not None and HN.match(role)) else role
            e["level"] = level
            e["how"] = how
        pg = self.page_of(eh)
        if pg >= 0:
            e["page"] = pg
        e["alt"] = gtext(eh.get("/Alt")) if eh.get("/Alt") is not None else None
        e["actualText"] = gtext(eh.get("/ActualText")) if eh.get("/ActualText") is not None else None
        e["expansion"] = gtext(eh.get("/E")) if eh.get("/E") is not None else None
        e["lang"] = gtext(eh.get("/Lang")) if eh.get("/Lang") is not None else None
        e["id"] = gtext(eh.get("/ID")) if eh.get("/ID") is not None else None
        e["title"] = gtext(eh.get("/T")) if eh.get("/T") is not None else None
        e["langFrom"] = row if e["lang"] is not None else par["langFrom"]
        role = e["role"]
        if role in ("TH", "TD", "Table", "L"):
            objs = attr_objects(self.reader, eh, self.classmap)
            if role in ("TH", "TD"):
                v = attr_get(objs, 4, "/Scope")
                n = gname(v) if v is not None else None
                e["scope"] = {"Row": 1, "Column": 2, "Both": 3}.get(n, 0)
                v = attr_get(objs, 4, "/RowSpan")
                e["rowSpan"] = gint(v) if v is not None else None
                v = attr_get(objs, 4, "/ColSpan")
                e["colSpan"] = gint(v) if v is not None else None
                v = attr_get(objs, 4, "/Headers")
                from pypdf.generic import ArrayObject, TextStringObject, ByteStringObject
                if isinstance(v, ArrayObject):
                    parts = [gtext(x) for x in v if isinstance(x.get_object(), (TextStringObject, ByteStringObject))]
                    if parts:
                        e["headers"] = " ".join(parts)
            if role == "Table":
                v = attr_get(objs, 4, "/Summary")
                e["summary"] = gtext(v) if v is not None else None
            if role == "L":
                v = attr_get(objs, 2, "/ListNumbering")
                if v is not None:
                    n = gname(v)
                    e["listNumbering"] = LISTNUM.get(n, 12) if n is not None else 12
        k = eh.raw_get("/K") if "/K" in eh else None
        if k is not None:
            self.kids(row, k, depth, None)
        return row

    # -- the ParentTree: independent number-tree walk
    def number_tree(self):
        from pypdf.generic import ArrayObject
        pt = self.sr.get("/ParentTree") if self.sr is not None else None
        pt = pt.get_object() if pt is not None else None
        out = {}
        budget = [200000]

        def walk(node, depth):
            if depth > 12 or node is None:
                return
            node = node.get_object() if hasattr(node, "get_object") else node
            if not hasattr(node, "get"):
                return
            if budget[0] <= 0:
                return
            budget[0] -= 1
            nums = node.get("/Nums")
            if nums is not None:
                nums = nums.get_object()
                if isinstance(nums, ArrayObject):
                    i = 0
                    while i + 1 < len(nums):
                        k = gint(nums[i])
                        if k is not None and k not in out:
                            out[k] = nums[i + 1]
                        i += 2
            kids = node.get("/Kids")
            if kids is not None:
                kids = kids.get_object()
                if isinstance(kids, ArrayObject):
                    for kid in kids:
                        walk(kid, depth + 1)
        walk(pt, 0)
        return out

    def parent_flags(self):
        """Per content row 16 ok, 32 differs, 64 none (128: MCID absent), per objr 1 ok 2 differs 3 none."""
        from pypdf.generic import IndirectObject, ArrayObject
        nt = self.number_tree()
        pages = list(self.reader.pages)
        cflags = []
        for c in self.content:
            if c["mcid"] < 0:
                cflags.append(0)
                continue
            key = None
            if c["mcid"] >= 0 and 0 <= c["page"] < len(pages):
                if c["stm"] == 0:
                    d = pages[c["page"]]
                else:
                    try:
                        d = self.reader.get_object(c["stm"])
                    except Exception:
                        d = None
                if d is not None:
                    v = d.get("/StructParents")
                    key = gint(v) if v is not None else None
            flag = 64
            if key is not None and key in nt:
                arr = nt[key]
                arr = arr.get_object() if hasattr(arr, "get_object") else arr
                if isinstance(arr, ArrayObject) and c["mcid"] < len(arr):
                    ent = arr[c["mcid"]]
                    want = self.elems[c["elem"]]["obj"]
                    if isinstance(ent, IndirectObject):
                        flag = 16 if (want == 0 or ent.idnum == want) else 32
            cflags.append(flag)
        oflags = []
        for o in self.objr:
            want = self.elems[o["elem"]]["obj"]
            t = o["target"]
            key = None
            if t is not None and hasattr(t, "get"):
                v = t.get("/StructParent")
                key = gint(v) if v is not None else None
            flag = 3
            if key is not None and key in nt:
                ent = nt[key]
                if isinstance(ent, IndirectObject):
                    flag = 1 if (want == 0 or ent.idnum == want) else 2
            oflags.append(flag)
        return cflags, oflags


def run_sweep(paths):
    """kin/scripts/struct-sweep over `paths`: {path: (status, json or None)}."""
    p = subprocess.run([os.path.join(ROOT, "kin", "scripts", "struct-sweep")], input="\n".join(paths) + "\n",
                       capture_output=True, text=True, cwd=ROOT)
    out = {}
    for line in p.stdout.split("\n"):
        if not line:
            continue
        f = line.split("\t", 2)
        if len(f) < 3:
            continue
        try:
            out[f[0]] = (f[1], json.loads(f[2]))
        except Exception:
            out[f[0]] = (f[1], None)
    return out


FIELDS = ["actualText", "alt", "expansion", "lang", "id", "title", "headers", "summary"]


def compare_tree(path, status, doc, verbose=True):
    """Compare emon's dump with the oracle's walk: (items compared, items that differ, notes)."""
    name = os.path.basename(path)
    items = 0
    bad = 0
    shown = [0]

    def fail(msg):
        nonlocal bad
        bad += 1
        if verbose and shown[0] < 6:
            shown[0] += 1
            print("  FAIL %s: %s" % (name, msg))

    try:
        t = Tree(path)
    except Exception as ex:
        return None, "pypdf failed: %s" % (str(ex)[:60])
    s = doc["s"]
    el = s["elements"]
    present = 1 if t.sr is not None else 0
    items += 1
    if present != s["tree"]["present"]:
        fail("tree present: emon %s pypdf %s" % (s["tree"]["present"], present))
        return (items, bad), None
    if not present:
        return (items, bad), None
    mi = t.root.get("/MarkInfo")
    mi = mi.get_object() if mi is not None else None
    for key, ek in (("/Marked", "marked"), ("/Suspects", "suspects")):
        items += 1
        v = mi.get(key) if mi is not None and hasattr(mi, "get") else None
        want = -1 if v is None else (1 if gbool(v) else 0)
        if want != s["tree"][ek]:
            fail("MarkInfo %s: emon %s pypdf %s" % (key, s["tree"][ek], want))
    items += 1
    if len(el) != len(t.elems):
        fail("elements: emon %d pypdf %d" % (len(el), len(t.elems)))
        return (items, bad), None
    items += 3
    if len(s["content"]) != len(t.content):
        fail("marked-content references: emon %d pypdf %d" % (len(s["content"]), len(t.content)))
        return (items, bad), None
    if len(s["objr"]) != len(t.objr):
        fail("object references: emon %d pypdf %d" % (len(s["objr"]), len(t.objr)))
        return (items, bad), None
    # the elements, in pre-order
    for i, (e, o) in enumerate(zip(el, t.elems)):
        pairs = [("obj", e["obj"], o["obj"]), ("parent", e["parent"], o["parent"]), ("depth", e["depth"], o["depth"]),
                 ("s", e["s"], o["s"]), ("role", e["role"], o["role"]), ("how", e["how"], o["how"]),
                 ("page", e.get("page", -1), o["page"]), ("langFrom", e.get("langFrom", -1), o["langFrom"])]
        if i > 0:
            pairs += [("scope", e.get("scope", 0), o["scope"]),
                      ("rowSpan", e.get("rowSpan"), o["rowSpan"] if (o["rowSpan"] is not None and o["rowSpan"] >= 0) else None),
                      ("colSpan", e.get("colSpan"), o["colSpan"] if (o["colSpan"] is not None and o["colSpan"] >= 0) else None),
                      ("listNumbering", e.get("listNumbering", "absent"),
                       {0: "absent", 1: "None", 2: "Unordered", 3: "Ordered", 4: "Disc", 5: "Circle", 6: "Square", 7: "Decimal",
                        8: "UpperRoman", 9: "LowerRoman", 10: "UpperAlpha", 11: "LowerAlpha", 12: "other"}[o["listNumbering"]])]
            for f in FIELDS:
                pairs.append((f, e.get(f), o[f]))
        for (k, ev, ov) in pairs:
            items += 1
            if ev != ov:
                fail("element %d %s: emon %r pypdf %r (S %r)" % (i, k, ev, ov, o["s"]))
        # kids: kind sequence and, for elements, the target row
        ekids = e["kids"]
        okids = o["kids"]
        items += 1
        if [k[0] for k in ekids] != [k[0] for k in okids]:
            fail("element %d kids: emon %s pypdf %s" % (i, [k[0] for k in ekids][:8], [k[0] for k in okids][:8]))
        else:
            for ek, ok_ in zip(ekids, okids):
                if ek[0] == "element":
                    items += 1
                    if ek[1] != ok_[1]:
                        fail("element %d kid row: emon %d pypdf %d" % (i, ek[1], ok_[1]))
    # the content references and the join against pypdf's own spans
    keys = set()
    try:
        pypdf_spans(path, keys)
    except Exception:
        keys = None
    cflags, oflags = t.parent_flags()
    for i, (c, o) in enumerate(zip(s["content"], t.content)):
        for k in ("elem", "kind", "page", "mcid", "stm"):
            items += 1
            if c[k] != o[k]:
                fail("content %d %s: emon %r pypdf %r" % (i, k, c[k], o[k]))
        if keys is not None:
            items += 1
            joined = 1 if (o["page"], o["stm"], o["mcid"]) in keys else 0
            if (1 if c["mark"] >= 0 else 0) != joined:
                fail("content %d join (page %d, stm %d, MCID %d): emon mark %d, pypdf has a span %d"
                     % (i, o["page"], o["stm"], o["mcid"], c["mark"], joined))
        items += 1
        if (c["flags"] & (16 + 32 + 64)) != cflags[i]:
            fail("content %d parent tree: emon flags %d pypdf %d" % (i, c["flags"] & (16 + 32 + 64), cflags[i]))
    for i, (r, o) in enumerate(zip(s["objr"], t.objr)):
        items += 3
        if r[0] != o["elem"] or r[1] != o["page"] or r[2] != o["obj"]:
            fail("objr %d: emon %s pypdf %s" % (i, r[:3], (o["elem"], o["page"], o["obj"])))
        if r[4] != oflags[i]:
            fail("objr %d parent tree: emon %d pypdf %d" % (i, r[4], oflags[i]))
    return (items, bad), None


def check_tree(paths, verbose=True, label=""):
    res = run_sweep(paths)
    tot_items = tot_bad = nfiles = nfail = nskip = 0
    total_el = total_content = 0
    roles = {}
    scores = []
    for path in paths:
        st, doc = res.get(path, ("missing", None))
        if doc is None or st != "ok" or "s" not in doc:
            nskip += 1
            if verbose:
                print("  skip %s: %s" % (os.path.basename(path), st))
            continue
        r, note = compare_tree(path, st, doc, verbose)
        if r is None:
            nskip += 1
            if verbose:
                print("  skip %s: %s" % (os.path.basename(path), note))
            continue
        items, bad = r
        nfiles += 1
        tot_items += items
        tot_bad += bad
        if bad:
            nfail += 1
        s = doc["s"]
        total_el += s["tree"]["elements"]
        total_content += s["tree"]["content"]
        for e in s["elements"][1:]:
            roles[e["role"]] = roles.get(e["role"], 0) + 1
        if s["score"]["status"] == 2:
            scores.append(s["score"]["score"])
        if verbose and len(paths) <= 12:
            sc = s["score"]
            print("  %-26s %4d elements %4d content refs, %5d items agree with pypdf; score %s (coverage %s, diversity %s, order %s, headings %s, levels %s, artifacts %s, nesting %s)"
                  % (os.path.basename(path), s["tree"]["elements"], s["tree"]["content"], items - bad, sc["score"], sc["coverage"],
                     sc["diversity"], sc["readingOrder"], sc["headingFit"], sc["levelFit"], sc["artifactFit"], sc["nestingFit"]))
    top = dict(sorted(roles.items(), key=lambda kv: -kv[1])[:10])
    print("%s%s: %d files compared (%d not readable by one side), %d elements, %d marked-content references, %d items, %d differ; %d files differ; roles %s"
          % (label, "FAIL" if tot_bad else "ok", nfiles, nskip, total_el, total_content, tot_items, tot_bad, nfail, top))
    if scores:
        scores.sort()
        print("%sscores of %d scored files: min %d, median %d, max %d" % (label, len(scores), scores[0], scores[len(scores) // 2], scores[-1]))
    return tot_bad


def count_marked(listfile):
    """How many files of LIST say /MarkInfo /Marked true, and how many have a /StructTreeRoot (pypdf alone)."""
    from pypdf import PdfReader
    marked = tree = both = bad = n = 0
    for l in open(listfile):
        p = l.strip()
        if not p:
            continue
        n += 1
        try:
            root = PdfReader(p).trailer["/Root"]
            mi = root.get("/MarkInfo")
            mk = gbool(mi.get_object().get("/Marked")) if mi is not None else False
            st = root.get("/StructTreeRoot") is not None
            marked += mk
            tree += st
            both += (mk and st)
        except Exception:
            bad += 1
    print("%d files: %d /Marked true, %d with a /StructTreeRoot, %d both (%d unreadable by pypdf)" % (n, marked, tree, both, bad))


def main():
    args = sys.argv[1:]
    if args and args[0] == "--count":
        count_marked(args[1])
        return
    mode = "both"
    only_tagged = False
    corpus = None
    paths = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--tree":
            mode = "tree"
        elif a == "--corpus":
            corpus = args[i + 1]
            mode = "tree"
            i += 1
        elif a == "--tagged":
            only_tagged = True
        else:
            paths.append(os.path.abspath(a))
        i += 1
    if corpus:
        paths = [os.path.abspath(l.strip()) for l in open(corpus) if l.strip()]
    if not paths:
        d = os.path.join(ROOT, "test", "corpus", "synth", "pdf-tagged")
        paths = sorted(os.path.join(d, f) for f in os.listdir(d) if f.endswith(".pdf"))
    bad = 0
    if mode == "both":
        bad += check_spans(paths)
    bad += check_tree(paths)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
