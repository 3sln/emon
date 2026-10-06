#!/usr/bin/env python3
"""The JSON goldens' second opinion (DEVLOG P1.2, D054).

    kin/scripts/json-check.py            check (what `bb unit` runs)
    kin/scripts/json-check.py --write    (re)write the goldens from the reference

`emon.json` (kin) is what ships; the `test/json/*.json` goldens are what the
four targets must reproduce byte for byte. This script is a SECOND,
INDEPENDENT implementation of SPEC 4.2/4.3 -- in Python, written from the
SPEC and the serialization rules in `kin/json.kin`'s header, sharing no code
with it -- and it does two things tooling may do and the runtime may not:

  1. it builds each `NAME.model` script into a Model and serializes it, and
     requires the result to equal the committed `NAME.json` and
     `NAME.pretty.json` (so a golden was not merely "whatever kin printed");
  2. it validates every golden against `schema/emon-1.json` with a standard
     JSON Schema 2020-12 validator (`jsonschema`).

The runtime has no Python. A golden that this script and kin disagree about is
a bug in one of them, found before a consumer finds it.

`jsonschema` is found on the path, else in `$EMON_PYLIB`, else in
`~/.cache/emon/pylib`, else it is installed there once with pip. If it cannot
be had the schema step says SKIP, loudly, and the byte comparison still runs.
"""
import json
import os
import subprocess
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
JSON_DIR = os.path.join(ROOT, "test", "json")
SCHEMA = os.path.join(ROOT, "schema", "emon-1.json")

# ---------------------------------------------------------------- the tables
# Transcribed from the SPEC (3.2, 3.3, 3.5, 3.7, 10.6, 10.7), NOT imported
# from model.kin: a disagreement about a spelling must show.
KINDS = {1: "document", 2: "section", 3: "heading", 4: "paragraph", 5: "list",
         6: "item", 7: "table", 8: "rowgroup", 9: "row", 10: "cell",
         11: "caption", 12: "figure", 13: "seal", 14: "formula", 15: "code",
         16: "quote", 17: "note", 18: "field", 19: "furniture", 20: "break",
         21: "text", 22: "span", 23: "link", 24: "ref", 25: "linebreak",
         26: "initial"}
INLINE = {"text", "span", "link", "ref", "linebreak", "initial"}
KEYS = {1: "id", 2: "kind", 3: "level", 4: "number", 5: "numbering", 6: "text",
        7: "inlines", 8: "children", 9: "at", 10: "src", 11: "conf",
        12: "flags", 13: "align", 14: "ordered", 15: "scheme", 16: "start",
        17: "marker", 18: "value", 19: "profile", 20: "continued",
        21: "columns", 22: "role", 23: "repeated", 24: "rowspan",
        25: "colspan", 26: "header", 27: "empty", 28: "label", 29: "target",
        30: "alt", 31: "labels", 32: "name", 33: "date", 34: "color",
        35: "display", 36: "style", 37: "href", 38: "page", 39: "title",
        40: "lang", 41: "template", 42: "pages", 43: "emon", 44: "generator",
        45: "source", 46: "settings", 47: "meta", 48: "body", 49: "furniture",
        50: "dropped", 51: "reason", 52: "n", 53: "w", 54: "h", 55: "rot",
        56: "health", 57: "dpi", 58: "px", 59: "format", 60: "version",
        61: "tagged", 62: "encrypted", 63: "locale", 64: "ocr", 65: "detail",
        66: "author", 67: "subject", 68: "keywords", 69: "creator",
        70: "producer", 71: "created", 72: "modified", 73: "type",
        74: "checked", 75: "subkind", 76: "form", 77: "lines", 78: "layout",
        79: "band", 80: "col", 81: "box"}
K = {v: k for k, v in KEYS.items()}
SRC = [(1, "text"), (2, "hidden-text"), (4, "ocr"), (8, "trace"), (16, "vector"),
       (32, "struct"), (64, "annot"), (128, "outline"), (256, "infer")]
FLAGS = [(1, "low-conf"), (2, "ocr-only"), (4, "text-layer-suspect"),
         (8, "reconstructed"), (16, "dpi-unknown"), (32, "rotated"), (64, "vertical"),
         (128, "struct-disagree"), (256, "tag-span-conflict"), (512, "in-table-image")]
STYLE = [(1, "bold"), (2, "italic"), (4, "underline"), (8, "strike"), (16, "sub"),
         (32, "sup"), (64, "mono"), (128, "smallcaps")]
ALIGN = {1: "start", 2: "center", 3: "end", 4: "justify"}
ROLE = {1: "head", 2: "body", 3: "foot", 4: "footnote", 5: "endnote",
        6: "margin", 7: "header", 8: "footer", 9: "sidebar"}
IFORM = {1: "drop", 2: "raised", 3: "margin"}
FIG = {1: "chart", 2: "diagram", 3: "photo", 4: "logo", 5: "map",
       6: "illustration", 7: "screenshot", 8: "qr", 9: "barcode", 10: "unknown"}
SEAL = {1: "seal", 2: "stamp", 3: "signature", 4: "initials", 5: "digital-signature"}
DISPLAY = {1: "block", 2: "inline"}
HEADER = {1: "col", 2: "row"}
PAGEKIND = {1: "vector", 2: "scanned", 3: "mixed", 4: "image"}
REASON = {1: "watermark", 2: "background", 3: "page-number", 4: "running-title",
          5: "bleed-through", 6: "duplicate-header-row", 7: "ocr-ghost",
          8: "clipped", 9: "offpage", 10: "invisible", 11: "rule-ghost",
          12: "unwitnessed-uncertain-paint"}


class Cpt(int):
    """Centipoints; serialized as points with at most two decimals."""


def cpt_text(v):
    sign = "-" if v < 0 else ""
    a = abs(v)
    ip, fr = divmod(a, 100)
    if fr == 0:
        return sign + str(ip)
    return sign + str(ip) + "." + ("%02d" % fr).rstrip("0")


# ------------------------------------------------------------ the script
class Node:
    def __init__(self, kind, parent):
        self.kind, self.parent = kind, parent
        self.children, self.text = [], None
        self.sub = self.aux = self.src = self.flags = 0
        self.conf = -1
        self.regions, self.attrs = [], []     # attrs: (key, vkind, value)


def unescape(s):
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        e = s[i + 1]
        i += 2
        if e == "n":
            out.append("\n")
        elif e == "t":
            out.append("\t")
        elif e == "\\":
            out.append("\\")
        elif e == "u":
            if s[i] == "{":
                j = s.index("}", i)
                out.append(chr(int(s[i + 1:j], 16)))
                i = j + 1
            else:
                out.append(chr(int(s[i:i + 4], 16)))
                i += 4
        else:
            raise ValueError("bad escape \\" + e)
    return "".join(out)


def build(path):
    nodes = [Node(1, -1)]
    pages, ledger = [], []
    # the script's refusals, as the builder's (a refused line is a failure)
    refused = []
    for raw in open(path, encoding="utf-8").read().split("\n"):
        if not raw or raw.startswith("#"):
            continue
        cmd, _, rest = raw.partition(" ")

        def ints(n):
            parts = rest.split(" ", n)
            return [int(x) for x in parts[:n]], (parts[n] if len(parts) > n else "")
        if cmd == "n":
            (k, p), _t = ints(2)
            nodes.append(Node(k, p))
            if p >= 0:
                nodes[p].children.append(len(nodes) - 1)
        elif cmd == "t":
            (n,), t = ints(1)
            nodes[n].text = unescape(t)
        elif cmd in ("s", "x", "c", "f", "sr"):
            (n, v), _t = ints(2)
            setattr(nodes[n], {"s": "sub", "x": "aux", "c": "conf", "f": "flags", "sr": "src"}[cmd], v)
        elif cmd == "r":
            (n, *reg), _t = ints(6)
            nodes[n].regions.append(reg)
        elif cmd in ("ai", "ab", "an", "ni"):
            (n, k, v), _t = ints(3)
            vk = {"ai": "int", "ab": "bool", "an": "node", "ni": "int"}[cmd]
            if cmd == "ab":
                v = 1 if v else 0
            at = nodes[n].attrs
            for i, (ak, _, _) in enumerate(at):
                if cmd != "ni" and ak == k:
                    at[i] = (k, vk, v)
                    break
            else:
                at.append((k, vk, v))
        elif cmd in ("at", "nt"):
            (n, k), t = ints(2)
            at = nodes[n].attrs
            val = unescape(t)
            for i, (ak, _, _) in enumerate(at):
                if cmd == "at" and ak == k:
                    at[i] = (k, "text", val)
                    break
            else:
                at.append((k, "text", val))
        elif cmd == "al":
            (n, v), _t = ints(2)
            kind = KINDS[nodes[n].kind]
            if not 1 <= v <= 4 or (kind == "cell" and v == 4) or \
                    kind not in ("paragraph", "heading", "caption", "cell"):
                refused.append(raw)
            elif kind == "paragraph":
                nodes[n].sub = v
            else:
                at = nodes[n].attrs
                for i, (ak, _, _) in enumerate(at):
                    if ak == K["align"]:
                        at[i] = (ak, "int", v)
                        break
                else:
                    at.append((K["align"], "int", v))
        elif cmd == "ly":
            (i, band, col, role, x0, y0, x1, y1), _t = ints(8)
            if role not in (2, 6, 9):
                refused.append(raw)
            else:
                pages[i]["layout"].append({"band": band, "col": col, "role": role,
                                           "box": (x0, y0, x1, y1)})
        elif cmd == "pg":
            (num, w, h, rot, kind), _t = ints(5)
            pages.append({"n": num, "w": w, "h": h, "rot": rot, "kind": kind,
                          "label": None, "health": -1, "dpi": 0, "px": (0, 0),
                          "layout": []})
        elif cmd == "pl":
            (i,), t = ints(1)
            pages[i]["label"] = unescape(t)
        elif cmd == "ph":
            (i, v), _t = ints(2)
            pages[i]["health"] = v
        elif cmd == "pd":
            (i, dpi, pw, ph), _t = ints(4)
            pages[i]["dpi"], pages[i]["px"] = dpi, (pw, ph)
        elif cmd == "d":
            (reason, page, x0, y0, x1, y1, conf, src), _t = ints(8)
            ledger.append({"reason": reason, "page": page, "box": (x0, y0, x1, y1),
                           "conf": conf, "src": src, "text": None})
        elif cmd == "dt":
            (i,), t = ints(1)
            ledger[i]["text"] = unescape(t)
        else:
            raise ValueError("unknown command " + raw)
    if refused:
        raise ValueError("the builder refuses: " + refused[0])
    return nodes, pages, ledger


# --------------------------------------------------------- the serializer
def names(bits, table):
    return [n for b, n in table if bits & b]


def attr(node, key):
    for k, vk, v in node.attrs:
        if k == K[key]:
            return vk, v
    return None


def attr_all(node, key):
    return [v for k, vk, v in node.attrs if k == K[key]]


def put_attr(d, node, key, name=None):
    a = attr(node, key)
    if a is not None:
        d[name or key] = bool(a[1]) if a[0] == "bool" else a[1]


def put_align_attr(d, node):
    a = attr(node, "align")
    if a is not None and a[1] > 0:
        d["align"] = ALIGN.get(a[1], "")


def kind_fields(d, node, kind):
    sub = node.sub
    if kind in ("section", "heading") and sub > 0:
        d["level"] = sub
    if kind == "heading":
        put_attr(d, node, "number")
        put_attr(d, node, "numbering")
        put_align_attr(d, node)
    if kind == "paragraph" and sub > 0:
        d["align"] = ALIGN.get(sub, "")
    if kind == "list":
        if sub > 0:
            d["ordered"] = (sub == 2)
        put_attr(d, node, "scheme")
        put_attr(d, node, "start")
    if kind == "item":
        put_attr(d, node, "marker")
        put_attr(d, node, "value")
    if kind == "table":
        for k in ("profile", "continued", "columns"):
            put_attr(d, node, k)
    if kind == "rowgroup" and sub > 0:
        d["role"] = ROLE.get(sub, "")
    if kind == "row" and sub == 1:
        d["repeated"] = True
    if kind == "cell":
        put_attr(d, node, "rowspan")
        put_attr(d, node, "colspan")
        if sub > 0:
            d["header"] = HEADER.get(sub, "")
        if node.aux == 1:
            d["empty"] = True
        put_align_attr(d, node)
    if kind == "caption":
        put_attr(d, node, "label")
        put_attr(d, node, "target")
        put_align_attr(d, node)
    if kind == "figure":
        if sub > 0:
            d["subkind"] = FIG.get(sub, "")
        put_attr(d, node, "label")
        put_attr(d, node, "alt")
        if attr_all(node, "labels"):
            d["labels"] = attr_all(node, "labels")
    if kind == "seal":
        if sub > 0:
            d["subkind"] = SEAL.get(sub, "")
        for k in ("name", "date", "color"):
            put_attr(d, node, k)
    if kind == "formula" and sub > 0:
        d["display"] = DISPLAY.get(sub, "")
    if kind == "note":
        if sub > 0:
            d["role"] = ROLE.get(sub, "")
        put_attr(d, node, "marker")
    if kind == "field":
        for k in ("name", "type", "value", "checked"):
            put_attr(d, node, k)
    if kind == "furniture":
        if sub > 0:
            d["role"] = ROLE.get(sub, "")
        if attr_all(node, "pages"):
            d["pages"] = attr_all(node, "pages")
    if kind == "break":
        d["page"] = sub
        put_attr(d, node, "label")
    if kind == "span" and sub > 0:
        d["style"] = names(sub, STYLE)
    if kind == "link":
        put_attr(d, node, "href")
        put_attr(d, node, "target")
    if kind == "ref":
        put_attr(d, node, "marker")
        put_attr(d, node, "target")
    if kind == "initial":
        if node.sub > 0:
            d["form"] = IFORM.get(node.sub, "")
        if node.aux > 0:
            d["lines"] = node.aux


def tail(d, node):
    if node.regions:
        d["at"] = [[r[0]] + [Cpt(x) for x in r[1:]] for r in node.regions]
    if node.src:
        d["src"] = names(node.src, SRC)
    if node.conf >= 0:
        d["conf"] = node.conf
    if node.flags:
        d["flags"] = names(node.flags, FLAGS)


def is_inline(nodes, c):
    """An inline kind, or a `break` with an inline neighbour (D134): the milestone inside a paragraph."""
    k = KINDS[nodes[c].kind]
    if k in INLINE:
        return True
    if k == "break" and nodes[c].parent is not None and nodes[c].parent >= 0:
        sib = nodes[nodes[c].parent].children
        i = sib.index(c)
        return any(0 <= j < len(sib) and KINDS[nodes[sib[j]].kind] in INLINE for j in (i - 1, i + 1))
    return False


def derived(nodes, n):
    out = []
    for c in nodes[n].children:
        k = KINDS[nodes[c].kind]
        if k == "linebreak":
            out.append("\n")
        if k in INLINE:
            if nodes[c].text is not None:
                out.append(nodes[c].text)
            if nodes[c].children:
                out.append(derived(nodes, c))
    return "".join(out)


def node_json(nodes, n):
    node = nodes[n]
    kind = KINDS[node.kind]
    inline = is_inline(nodes, n)
    d = {}
    if not inline:
        d["id"] = n
    d["kind"] = kind
    kind_fields(d, node, kind)
    kids = node.children
    has_inline = any(is_inline(nodes, c) for c in kids)
    informative = any(KINDS[nodes[c].kind] != "text" or nodes[c].regions
                      for c in kids if is_inline(nodes, c))
    if node.text is not None:
        d["text"] = node.text
    elif not inline and has_inline:
        d["text"] = derived(nodes, n)
    if not inline and informative:
        d["inlines"] = [node_json(nodes, c) for c in kids if is_inline(nodes, c)]
    blocks = kids if inline else [c for c in kids if not is_inline(nodes, c)]
    if blocks:
        d["children"] = [node_json(nodes, c) for c in blocks]
    tail(d, node)
    return d


def model_json(nodes, pages, ledger):
    root = nodes[0]
    out = {"emon": "1.1"}
    put_attr(out, root, "generator")
    src = {}
    for k in ("format", "version", "pages", "tagged", "encrypted", "lang"):
        put_attr(src, root, k)
    if src:
        out["source"] = src
    st = {}
    a = attr(root, "locale")
    st["locale"] = a[1] if a else None
    put_attr(st, root, "ocr")
    put_attr(st, root, "detail")
    out["settings"] = st
    out["conf"] = "uncalibrated"
    meta = {}
    for k in ("title", "author", "subject", "keywords", "creator", "producer",
              "created", "modified"):
        put_attr(meta, root, k)
    if meta:
        out["meta"] = meta
    pl = []
    for p in pages:
        d = {"n": p["n"]}
        if p["label"] is not None:
            d["label"] = p["label"]
        d["w"], d["h"] = Cpt(p["w"]), Cpt(p["h"])
        d["rot"] = p["rot"]
        if p["kind"] > 0:
            d["kind"] = PAGEKIND.get(p["kind"], "")
        if p["health"] >= 0:
            d["health"] = p["health"]
        if p["dpi"] > 0:
            d["dpi"] = p["dpi"]
        if p["px"][0] > 0 or p["px"][1] > 0:
            d["px"] = list(p["px"])
        if p["layout"]:
            d["layout"] = [{"band": c["band"], "col": c["col"],
                            "role": ROLE.get(c["role"], ""),
                            "box": [Cpt(x) for x in c["box"]]} for c in p["layout"]]
        pl.append(d)
    out["pages"] = pl
    out["body"] = node_json(nodes, 0)
    fl = []
    for i, n in enumerate(nodes):
        if KINDS[n.kind] == "furniture" and n.parent == -1:
            d = {"id": i}
            if n.sub > 0:
                d["role"] = ROLE.get(n.sub, "")
            if n.text is not None:
                d["template"] = n.text
            if attr_all(n, "pages"):
                d["pages"] = attr_all(n, "pages")
            if n.children:
                d["children"] = [node_json(nodes, c) for c in n.children]
            tail(d, n)
            fl.append(d)
    out["furniture"] = fl
    dl = []
    for l in ledger:
        d = {"reason": REASON.get(l["reason"], ""), "page": l["page"],
             "at": [l["page"]] + [Cpt(x) for x in l["box"]]}
        if l["text"] is not None:
            d["text"] = l["text"]
        if l["conf"] >= 0:
            d["conf"] = l["conf"]
        if l["src"]:
            d["src"] = names(l["src"], SRC)
        dl.append(d)
    out["dropped"] = dl
    return out


# ------------------------------------------------------------ the dumper
def scalar(v):
    if isinstance(v, Raw):
        return str(v)
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None:
        return "null"
    if isinstance(v, Cpt):
        return cpt_text(v)
    if isinstance(v, int):
        return str(v)
    out = ['"']
    for ch in v:
        c = ord(ch)
        if ch == '"':
            out.append('\\"')
        elif ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\t":
            out.append("\\t")
        elif c < 0x20 or c in (0x2028, 0x2029):
            out.append("\\u%04x" % c)
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


def flat(v):
    """Arrays of scalars, or of flat arrays, are written on one line."""
    if isinstance(v, list):
        return all(not isinstance(x, dict) and (not isinstance(x, list) or flat(x)) for x in v)
    return not isinstance(v, dict)


def dump(v, pretty, depth=0):
    sep_flat = ", " if pretty else ","
    if isinstance(v, dict):
        if not v:
            return "{}"
        items = []
        for k, x in v.items():
            items.append(scalar(k) + (": " if pretty else ":") + dump(x, pretty, depth + 1))
        if not pretty:
            return "{" + ",".join(items) + "}"
        ind = "  " * (depth + 1)
        return "{\n" + ",\n".join(ind + i for i in items) + "\n" + "  " * depth + "}"
    if isinstance(v, list):
        if flat(v):
            return "[" + sep_flat.join(dump(x, pretty, depth) for x in v) + "]"
        if not v:
            return "[]"
        if not pretty:
            return "[" + ",".join(dump(x, pretty, depth + 1) for x in v) + "]"
        ind = "  " * (depth + 1)
        return "[\n" + ",\n".join(ind + dump(x, pretty, depth + 1) for x in v) + "\n" + "  " * depth + "]"
    return scalar(v)


class Raw(str):
    """A number exactly as written (2-decimal points stay `90.5`, not `90.50`)."""


def scalar_raw(v):
    return str(v) if isinstance(v, Raw) else None


def check_evidence():
    """The evidence dumps (SPEC 4.5): each `test/evidence/NAME.json` must be
    well-formed, minimal JSON, and its `.pretty.json` must be exactly what the
    canonical pretty rules make of it (this script's dumper, not kin's)."""
    d = os.path.join(ROOT, "test", "evidence")
    bad = n = 0
    for f in sorted(os.listdir(d)):
        if not f.endswith(".json") or f.endswith(".pretty.json"):
            continue
        n += 1
        compact = golden(os.path.join(d, f))
        tree = json.loads(compact, parse_float=Raw)
        if json.dumps(json.loads(compact), separators=(",", ":"), ensure_ascii=False) != compact:
            print("  FAIL evidence: %s is not minimal canonical JSON" % f)
            bad += 1
        pretty_path = os.path.join(d, f[:-5] + ".pretty.json")
        want = dump(tree, True)
        if not os.path.exists(pretty_path) or golden(pretty_path) != want:
            if "--write" in sys.argv:
                open(pretty_path, "w", encoding="utf-8", newline="").write(want + "\n")
            else:
                print("  FAIL evidence: %s differs from the canonical pretty form" % (f[:-5] + ".pretty.json"))
                bad += 1
    if n == 0:
        print("  FAIL evidence: no test/evidence/*.json")
        return 1
    if not bad:
        print("  ok   json-check: %d evidence dumps are canonical" % n)
    return 1 if bad else 0


# ------------------------------------------------------------------ main
def golden(path):
    with open(path, encoding="utf-8", newline="") as f:
        s = f.read()
    return s[:-1] if s.endswith("\n") else s


def find_jsonschema():
    try:
        import jsonschema  # noqa
        return jsonschema
    except ImportError:
        pass
    libs = [os.environ.get("EMON_PYLIB"), os.path.expanduser("~/.cache/emon/pylib")]
    for lib in libs:
        if lib and os.path.isdir(lib):
            sys.path.insert(0, lib)
            try:
                import jsonschema  # noqa
                return jsonschema
            except ImportError:
                sys.path.pop(0)
    lib = os.path.expanduser("~/.cache/emon/pylib")
    r = subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", "--target", lib,
                        "jsonschema"], capture_output=True, text=True)
    if r.returncode == 0:
        sys.path.insert(0, lib)
        try:
            import jsonschema  # noqa
            return jsonschema
        except ImportError:
            pass
    return None


def main():
    write = "--write" in sys.argv
    names_ = sorted(f[:-6] for f in os.listdir(JSON_DIR) if f.endswith(".model"))
    if not names_:
        print("  FAIL json-check: no test/json/*.model")
        return 1
    bad = 0
    goldens = []
    for n in names_:
        tree = model_json(*build(os.path.join(JSON_DIR, n + ".model")))
        for suffix, pretty in ((".json", False), (".pretty.json", True)):
            want = dump(tree, pretty)
            path = os.path.join(JSON_DIR, n + suffix)
            if write:
                with open(path, "w", encoding="utf-8", newline="") as f:
                    f.write(want + "\n")
            elif not os.path.exists(path) or golden(path) != want:
                print("  FAIL json-check: %s differs from the reference serializer" % (n + suffix))
                bad += 1
            goldens.append(path)
        # the compact and pretty forms must be the same JSON
        assert json.loads(dump(tree, False)) == json.loads(dump(tree, True))
    if bad:
        return 1
    print("  ok   json-check: %d models, compact and pretty, equal the reference" % len(names_))
    if check_evidence():
        return 1
    js = find_jsonschema()
    if js is None or not os.path.exists(SCHEMA):
        print("  SKIP json-check: %s" % ("no jsonschema" if js is None else "no schema/emon-1.json"))
        return 0
    schema = json.load(open(SCHEMA, encoding="utf-8"))
    js.Draft202012Validator.check_schema(schema)
    v = js.Draft202012Validator(schema)
    n_bad = 0
    for g in goldens:
        doc = json.load(open(g, encoding="utf-8"))
        errs = sorted(v.iter_errors(doc), key=lambda e: list(e.path))
        for e in errs[:5]:
            print("  FAIL schema: %s: /%s: %s" % (os.path.basename(g), "/".join(map(str, e.path)), e.message[:200]))
            n_bad += 1
    if n_bad:
        return 1
    print("  ok   json-check: %d goldens validate against schema/emon-1.json" % len(goldens))
    return 0


if __name__ == "__main__":
    sys.exit(main())
