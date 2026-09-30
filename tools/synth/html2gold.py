#!/usr/bin/env python3
"""HTML -> gold Model (DEVLOG P2.8, D041, D063). Tooling, not runtime.

    tools/synth/html2gold.py DOC.html FACTS.json     print the gold JSON

The synthetic corpus is authored as semantic HTML.  The HTML *is* the ground
truth; this converter, written by hand (no HTML-to-Model library), reads it
and writes the partial Model that a correct emon run would have to reproduce
for the annotated parts (schema/emon-gold-1.json).  Pure standard library, and
it never runs a PDF producer: what only the printed PDF knows (page count and
size, which page a table row landed on) comes from FACTS, which
`tools/synth/build.py` extracted once, at print time, and committed.

THE HTML SUBSET (anything else is an error, so a typo cannot silently lose
ground truth):

  headings   h1..h6, or any element with `data-level="N"` (a heading that is
             not an <hN>).  The level is the DOCUMENT's level and has nothing
             to do with how big the heading prints: corpus documents on
             purpose print a level 1 heading smaller than a level 2 one
             (SPEC 10.3).  `<span class="num">1.2</span>` inside a heading is
             the printed number: `number` "1.2", excluded from `text`.
  blocks     p, ul/ol (li, nested), table (caption, thead/tbody/tfoot, th/td
             with rowspan/colspan), figure / `data-figure=KIND` (figcaption),
             blockquote, pre, `data-seal=KIND` (name/date/color in
             `data-name`/`data-date`/`data-color`), `data-note=footnote`
             (+ `data-marker`).  div/section/article/aside/main/header/footer
             are transparent: sections are built from heading levels.
  inlines    b strong i em u s del sub sup code kbd span.sc a[href] br and
             `<sup data-fnref="1">` (a note reference).
  ignored    `data-gold="skip"` (chrome that prints but is not content: a
             fixed header, a watermark, a rule, a footnote's own marker),
             script, style, head.
  hints      `<script type="application/emon-gold+json">` holds what no DOM
             node says: margin-box running text from @page, page-number
             text, watermark text.  See `HINTS` below.

Rules that are DECISIONS (DEVLOG D063) rather than facts:
  * a `ref` contributes no text; a `linebreak` contributes "\\n".
  * a caption's `label` is the matched prefix ("Figure 2"); its `text` is the
    rest, like a heading's `number` and `text`.
  * a footnote `note` sits where the HTML puts it, i.e. where it is read
    (the page bottom), and each `ref` targets the next unmatched note with
    the same marker.
  * a table's head rowgroup is the <thead>; a header row that printed again
    on a continuation page is ONE row with `repeated: true` and one
    `duplicate-header-row` ledger entry per continuation page.
"""
import json
import re
import sys
import unicodedata
from html.parser import HTMLParser

VOID = {"br", "img", "hr", "meta", "link", "input", "col", "wbr", "area", "base"}
WS = " \t\r\n\f"

# ---------------------------------------------------------------- DOM


class El:
    __slots__ = ("tag", "attrs", "kids", "parent")

    def __init__(self, tag, attrs, parent):
        self.tag, self.attrs, self.kids, self.parent = tag, dict(attrs), [], parent

    def get(self, k, d=None):
        return self.attrs.get(k, d)

    def cls(self):
        return (self.attrs.get("class") or "").split()


class Parser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = El("#root", [], None)
        self.cur = self.root

    def handle_starttag(self, tag, attrs):
        e = El(tag, [(k, v if v is not None else "") for k, v in attrs], self.cur)
        self.cur.kids.append(e)
        if tag not in VOID:
            self.cur = e

    def handle_startendtag(self, tag, attrs):
        e = El(tag, [(k, v if v is not None else "") for k, v in attrs], self.cur)
        self.cur.kids.append(e)

    def handle_endtag(self, tag):
        if tag in VOID:
            return
        e = self.cur
        while e is not None and e.tag != tag:
            e = e.parent
        if e is None:
            raise ValueError("stray </%s> at line %d" % (tag, self.getpos()[0]))
        self.cur = e.parent

    def handle_data(self, data):
        self.cur.kids.append(data)


def parse(html):
    p = Parser()
    p.feed(html)
    p.close()
    if p.cur is not p.root:
        raise ValueError("unclosed <%s>" % p.cur.tag)
    return p.root


def walk(e):
    for k in e.kids:
        if isinstance(k, El):
            yield k
            yield from walk(k)


def find(e, tag):
    for x in walk(e):
        if x.tag == tag:
            return x
    return None


def text_content(e):
    out = []

    def rec(x):
        for k in x.kids:
            if isinstance(k, str):
                out.append(k)
            elif k.tag == "br":
                out.append(" ")
            elif k.tag not in ("script", "style") and k.get("data-gold") != "skip":
                rec(k)
    rec(e)
    return norm_ws("".join(out))


def norm_ws(s):
    s = re.sub("[" + WS + "]+", " ", s).strip(WS)
    return unicodedata.normalize("NFC", s)


# ---------------------------------------------------------------- inlines

STYLE_TAGS = {"b": "bold", "strong": "bold", "i": "italic", "em": "italic",
              "cite": "italic", "u": "underline", "s": "strike", "del": "strike",
              "strike": "strike", "sub": "sub", "sup": "sup", "code": "mono",
              "kbd": "mono", "samp": "mono", "tt": "mono"}
INLINE_TAGS = set(STYLE_TAGS) | {"a", "span", "small", "br", "mark", "abbr", "q",
                                 "bdi", "bdo", "time", "wbr", "img"}


class Inl:
    """Builds an inline list with lazy whitespace: a space is emitted only
    before the next non-space content, so none is leading or trailing or
    dangling before a line break."""

    def __init__(self, notes):
        self.top = []
        self.stack = [self.top]
        self.pending = False
        self.started = False
        self.notes = notes

    def text(self, s):
        for seg in re.findall("[" + WS + "]+|[^" + WS + "]+", s):
            if seg[0] in WS:
                self.pending = True
            else:
                if self.pending and self.started:
                    self._put(" ")
                self.pending = False
                self.started = True
                self._put(unicodedata.normalize("NFC", seg))

    def _put(self, s):
        c = self.stack[-1]
        if c and c[-1]["kind"] == "text":
            c[-1]["text"] += s
        else:
            c.append({"kind": "text", "text": s})

    def _space(self):
        if self.pending and self.started:
            self._put(" ")
        self.pending = False

    def br(self):
        self.pending = False
        self.stack[-1].append({"kind": "linebreak"})
        self.started = False

    def ref(self, marker):
        self._space()
        self.started = True
        n = {"kind": "ref", "marker": marker}
        self.stack[-1].append(n)
        self.notes["refs"].append(n)

    def open(self, node):
        self._space()
        self.stack[-1].append(node)
        self.stack.append(node["children"])

    def close(self):
        self.stack.pop()

    def walk(self, e, styles=()):
        for k in e.kids:
            if isinstance(k, str):
                self.text(k)
                continue
            t = k.tag
            if k.get("data-gold") == "skip" or t in ("script", "style", "svg"):
                continue
            if t == "br":
                self.br()
            elif t == "sup" and k.get("data-fnref") is not None:
                self.ref(k.get("data-fnref"))
            elif t in STYLE_TAGS or (t == "span" and "sc" in k.cls()):
                st = STYLE_TAGS.get(t, "smallcaps")
                self.open({"kind": "span", "style": [st], "children": []})
                self.walk(k)
                self.close()
            elif t == "a" and k.get("href") is not None:
                self.open({"kind": "link", "href": k.get("href"), "children": []})
                self.walk(k)
                self.close()
            elif t in INLINE_TAGS:
                if t != "img":
                    self.walk(k)
            else:
                raise ValueError("block element <%s> inside inline content" % t)

    def result(self):
        return prune(self.top)


def prune(nodes):
    out = []
    for n in nodes:
        if "children" in n:
            n["children"] = prune(n["children"])
            if not n["children"]:
                continue
        if n["kind"] == "text" and n["text"] == "":
            continue
        out.append(n)
    # merge spans with identical style that became adjacent
    return out


def flat(nodes):
    s = ""
    for n in nodes:
        k = n["kind"]
        if k == "text":
            s += n["text"]
        elif k == "linebreak":
            s += "\n"
        elif k == "ref":
            pass
        else:
            s += flat(n["children"])
    return s


def leaf(kind, e, notes, **fields):
    """A leaf block: `text`, and `inlines` only when they carry information."""
    b = Inl(notes)
    b.walk(e)
    nodes = b.result()
    n = {"kind": kind}
    n.update(fields)
    n["text"] = flat(nodes)
    if any(x["kind"] != "text" for x in nodes):
        n["inlines"] = nodes
    return n


# ---------------------------------------------------------------- blocks

BLOCK_TRANSPARENT = {"div", "section", "article", "aside", "main", "header",
                     "footer", "nav", "figure_", "span", "body", "html", "center"}
LABEL = re.compile(r"^((?:Table|Figure|Fig\.|Chart|Exhibit|Diagram|Schedule|Plate|Image|Photo)"
                   r"\s+[0-9A-Za-z.]+)\s*[.:–—-]?\s*(.*)$", re.S)
ROMAN = re.compile(r"^[IVXLCDM]+$")
SCHEMES = {"1": "decimal", "a": "lower-alpha", "A": "upper-alpha", "i": "lower-roman",
           "I": "upper-roman"}


def roman(n):
    out = ""
    for v, s in ((1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"),
                 (50, "L"), (40, "XL"), (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")):
        while n >= v:
            out += s
            n -= v
    return out


def marker_for(scheme, n, depth):
    if scheme == "bullet":
        return ["•", "◦", "▪"][min(depth, 2)]
    if scheme == "decimal":
        return "%d." % n
    if scheme == "lower-alpha":
        return "%s." % chr(ord("a") + (n - 1) % 26)
    if scheme == "upper-alpha":
        return "%s." % chr(ord("A") + (n - 1) % 26)
    if scheme == "lower-roman":
        return "%s." % roman(n).lower()
    if scheme == "upper-roman":
        return "%s." % roman(n)
    raise ValueError("scheme " + scheme)


def heading_number(span_text):
    n = span_text.strip()
    n = re.sub(r"^[(\[]|[)\].:]$", "", n).strip()
    n = re.sub(r"^§\s*", "", n)
    return n


def numbering_scheme(n):
    if re.fullmatch(r"\d+(\.\d+)*", n):
        return "decimal"
    if ROMAN.match(n):
        return "upper-roman"
    if re.fullmatch(r"[A-Z](\.\d+)*", n):
        return "upper-alpha"
    if re.fullmatch(r"[a-z]", n):
        return "lower-alpha"
    return "decimal"


class Conv:
    def __init__(self, facts, hints):
        self.facts = facts
        self.hints = hints
        self.notes = {"refs": [], "notes": []}
        self.dropped_extra = []

    # -- a run of loose inline content becomes an anonymous paragraph
    def flush(self, run, out):
        if not run:
            return
        holder = El("p", [], None)
        holder.kids = run
        n = leaf("paragraph", holder, self.notes)
        if n["text"] != "" or "inlines" in n:
            out.append(n)
        run.clear()

    def blocks(self, e):
        out, run = [], []
        for k in e.kids:
            if isinstance(k, str):
                run.append(k)
                continue
            t = k.tag
            if t in ("script", "style", "head", "title", "meta", "link") or k.get("data-gold") == "skip":
                continue
            if t in INLINE_TAGS and k.get("data-seal") is None and k.get("data-heading") is None \
                    and k.get("data-note") is None:
                run.append(k)
                continue
            self.flush(run, out)
            out.extend(self.block(k))
        self.flush(run, out)
        return out

    def block(self, e):
        t = e.tag
        if e.get("data-heading") is not None or e.get("data-level") is not None \
                or (len(t) == 2 and t[0] == "h" and t[1] in "123456"):
            return [self.heading(e)]
        if e.get("data-seal") is not None:
            return [self.seal(e)]
        if e.get("data-note") is not None:
            return [self.note(e)]
        if e.get("data-figure") is not None or t == "figure":
            return [self.figure(e)]
        if t == "p":
            return [leaf("paragraph", e, self.notes)]
        if t in ("ul", "ol"):
            return [self.lst(e, 0)]
        if t == "table" and e.get("data-layout") is not None:
            out = []
            for x in walk(e):
                if x.tag == "td" and x.parent.parent.tag == "tbody":
                    out.extend(self.blocks(x))
            return out
        if t == "table":
            return [self.table(e)]
        if t == "blockquote":
            return [{"kind": "quote", "children": self.blocks(e)}]
        if t == "pre":
            s = "".join(k if isinstance(k, str) else text_content(k) for k in e.kids)
            return [{"kind": "code", "text": s.strip("\n")}]
        if t == "hr" or t == "svg" or t == "img":
            return []
        if t in BLOCK_TRANSPARENT:
            return self.blocks(e)
        raise ValueError("unsupported block element <%s>" % t)

    def heading(self, e):
        lvl = int(e.get("data-level") or e.tag[1])
        num = None
        holder = El("h", [], None)
        for k in e.kids:
            if isinstance(k, El) and k.tag == "span" and "num" in k.cls():
                num = heading_number(text_content(k))
            else:
                holder.kids.append(k)
        extra = {"level": lvl}
        if num:
            extra["number"] = num
            extra["numbering"] = e.get("data-numbering") or numbering_scheme(num)
        return leaf("heading", holder, self.notes, **extra)

    def lst(self, e, depth):
        ordered = e.tag == "ol"
        scheme = e.get("data-scheme") or (SCHEMES[e.get("type", "1")] if ordered else "bullet")
        start = int(e.get("start", 1)) if ordered else 1
        n = {"kind": "list", "ordered": ordered, "scheme": scheme}
        if start != 1:
            n["start"] = start
        items, cur = [], start
        for li in e.kids:
            if not isinstance(li, El):
                continue
            if li.tag != "li":
                raise ValueError("<%s> directly in a list" % li.tag)
            value = li.get("value")
            if value is not None:
                cur = int(value)
            own, nested = El("li", [], None), []
            for k in li.kids:
                if isinstance(k, El) and k.tag in ("ul", "ol"):
                    nested.append(k)
                else:
                    own.kids.append(k)
            item = leaf("item", own, self.notes, marker=marker_for(scheme, cur, depth))
            if value is not None:
                item["value"] = cur
            if nested:
                item["children"] = [self.lst(x, depth + 1) for x in nested]
            items.append(item)
            cur += 1
        n["children"] = items
        return n

    def caption(self, e):
        raw = leaf("caption", e, self.notes)
        m = LABEL.match(raw["text"])
        if m:
            n = {"kind": "caption", "label": m.group(1), "text": m.group(2)}
        else:
            n = {"kind": "caption", "text": raw["text"]}
        if "inlines" in raw and not m:
            n["inlines"] = raw["inlines"]
        return n

    def figure(self, e):
        kind = e.get("data-figure") or e.get("data-kind")
        if kind is None:
            raise ValueError("figure without data-kind")
        n = {"kind": "figure", "subkind": kind}
        cap = None
        labels = []
        for x in walk(e):
            if x.tag == "figcaption":
                cap = self.caption(x)
            elif x.tag == "text" and x.get("data-gold") != "skip":
                s = text_content(x)
                if s:
                    labels.append(s)
            elif x.get("data-label") is not None:
                s = text_content(x)
                if s:
                    labels.append(s)
        if cap and "label" in cap:
            n["label"] = cap["label"]
        alt = e.get("aria-label") or e.get("data-alt")
        if alt:
            n["alt"] = alt
        if labels and e.get("data-labels") != "none":
            n["labels"] = labels
        if cap:
            n["children"] = [cap]
        return n

    def seal(self, e):
        n = {"kind": "seal", "subkind": e.get("data-seal")}
        if e.get("data-name"):
            n["name"] = e.get("data-name")
        if e.get("data-date"):
            n["date"] = e.get("data-date")
        if e.get("data-color"):
            n["color"] = e.get("data-color")
        n["text"] = norm_ws(e.get("data-text")) if e.get("data-text") else text_content(e)
        return n

    def note(self, e):
        role = e.get("data-note")
        marker = e.get("data-marker")
        n = {"kind": "note", "role": role}
        if marker:
            n["marker"] = marker
        n["children"] = self.blocks(e) or [{"kind": "paragraph", "text": ""}]
        self.notes["notes"].append(n)
        return n

    # -- tables
    def table(self, e):
        tid = e.get("id")
        n = {"kind": "table"}
        if e.get("data-profile"):
            n["profile"] = e.get("data-profile")
        kids = []
        groups = []  # (role, [tr El])
        trs_all = []
        for k in e.kids:
            if not isinstance(k, El):
                continue
            if k.tag == "caption":
                kids.append(self.caption(k))
            elif k.tag in ("thead", "tbody", "tfoot"):
                trs = [r for r in k.kids if isinstance(r, El) and r.tag == "tr"]
                groups.append(({"thead": "head", "tbody": "body", "tfoot": "foot"}[k.tag], trs))
            elif k.tag == "tr":
                if groups and groups[-1][0] == "body":
                    groups[-1][1].append(k)
                else:
                    groups.append(("body", [k]))
            elif k.tag in ("colgroup", "col"):
                pass
            else:
                raise ValueError("<%s> in table" % k.tag)
        ncols = 0
        head_rows = []
        for role, trs in groups:
            rg = {"kind": "rowgroup", "role": role, "children": []}
            for tr in trs:
                row = {"kind": "row", "children": []}
                width = 0
                for c in tr.kids:
                    if not isinstance(c, El):
                        continue
                    if c.tag not in ("td", "th"):
                        raise ValueError("<%s> in row" % c.tag)
                    cell = leaf("cell", c, self.notes)
                    rs, cs = int(c.get("rowspan", 1)), int(c.get("colspan", 1))
                    if rs > 1:
                        cell["rowspan"] = rs
                    if cs > 1:
                        cell["colspan"] = cs
                    if c.tag == "th":
                        sc = c.get("scope")
                        cell["header"] = "row" if sc == "row" else "col" if sc == "col" else \
                            ("col" if role == "head" else "row")
                    if cell["text"] == "" and "inlines" not in cell:
                        cell["empty"] = True
                    width += cs
                    row["children"].append(cell)
                ncols = max(ncols, width)
                rg["children"].append(row)
                if role == "head":
                    head_rows.append((row, tr))
                trs_all.append(tr)
            kids.append(rg)
        n["columns"] = ncols
        # cross-page facts: which page each <tr> printed on
        pages = (self.facts.get("rows") or {}).get(tid) if tid else None
        if pages:
            lo, hi = min(pages), max(pages)
            if hi > lo:
                n["continued"] = hi - lo + 1
            if hi > lo and e.get("data-repeat-head") == "true" and head_rows:
                for row, tr in head_rows:
                    row["repeated"] = True
                txt = " ".join(c["text"] for c in head_rows[0][0]["children"] if c["text"])
                for p in range(lo + 1, hi + 1):
                    self.dropped_extra.append({"reason": "duplicate-header-row", "page": p,
                                               "text": txt})
        n["children"] = kids
        return n


# ---------------------------------------------------------------- the document


def expand_pages(spec, N):
    if spec in (None, "all"):
        return list(range(1, N + 1))
    if spec == "odd":
        return [p for p in range(1, N + 1) if p % 2 == 1]
    if spec == "odd-rest":
        return [p for p in range(3, N + 1) if p % 2 == 1]
    if spec == "even":
        return [p for p in range(1, N + 1) if p % 2 == 0]
    if spec == "first":
        return [1]
    if spec == "rest":
        return list(range(2, N + 1))
    if spec == "last":
        return [N]
    if isinstance(spec, list):
        return [p for p in spec if 1 <= p <= N]
    raise ValueError("pages spec %r" % (spec,))


def subst(t, n, N):
    return (t.replace("{n:04}", "%04d" % n).replace("{n}", str(n)).replace("{N}", str(N))
            .replace("{roman}", roman(n).lower()).replace("{ROMAN}", roman(n)))


ORDER = ["id", "kind", "level", "number", "numbering", "ordered", "scheme", "start",
         "marker", "value", "profile", "continued", "columns", "role", "repeated",
         "rowspan", "colspan", "header", "empty", "label", "target", "subkind", "alt",
         "labels", "name", "date", "color", "display", "style", "href", "page", "pages",
         "template", "reason", "text", "inlines", "children"]


def ordered(x):
    if isinstance(x, list):
        return [ordered(i) for i in x]
    if isinstance(x, dict):
        keys = sorted(x, key=lambda k: (ORDER.index(k) if k in ORDER else len(ORDER), k))
        return {k: ordered(x[k]) for k in keys}
    return x


def assign_ids(root, start, refs_ok=True):
    nid = [start]

    def rec(n):
        n["id"] = nid[0]
        nid[0] += 1
        for c in n.get("children", []):
            if c["kind"] not in ("text", "span", "link", "ref", "linebreak"):
                rec(c)
    rec(root)
    return nid[0]


def sections(flat_blocks):
    doc = {"kind": "document", "children": []}
    stack = [(0, doc)]
    for b in flat_blocks:
        if b["kind"] == "heading":
            lvl = b["level"]
            while stack[-1][0] >= lvl:
                stack.pop()
            sec = {"kind": "section", "level": lvl, "children": [b]}
            stack[-1][1]["children"].append(sec)
            stack.append((lvl, sec))
        else:
            stack[-1][1]["children"].append(b)
    return doc


ALL_KINDS = ["section", "heading", "paragraph", "list", "item", "table", "rowgroup", "row",
             "cell", "caption", "figure", "seal", "quote", "code", "note"]
ATTRS = {"section": ["level"], "heading": ["level", "number", "numbering"],
         "list": ["ordered", "scheme", "start"], "item": ["marker", "value"],
         "table": ["profile", "columns", "continued"], "rowgroup": ["role"],
         "row": ["repeated"], "cell": ["rowspan", "colspan", "header", "empty"],
         "caption": ["label"], "figure": ["subkind", "label", "alt", "labels"],
         "seal": ["subkind", "name", "date", "color"], "note": ["role", "marker"]}
REASONS = ["page-number", "running-title", "watermark", "duplicate-header-row"]


def convert(html, facts, doc_id, extra_provenance=None):
    root = parse(html)
    htmlel = find(root, "html")
    lang = (htmlel.get("lang") if htmlel else None) or None
    title_el = find(root, "title")
    title = text_content(title_el) if title_el else None
    hints = {}
    for s in walk(root):
        if s.tag == "script" and s.get("type") == "application/emon-gold+json":
            hints = json.loads("".join(k for k in s.kids if isinstance(k, str)))
    body = find(root, "body") or root
    conv = Conv(facts, hints)
    blocks = conv.blocks(body)
    doc = sections(blocks)
    nxt = assign_ids(doc, 0)

    # note references: each targets the next unmatched note with its marker
    pending = {}
    for nt in conv.notes["notes"]:
        pending.setdefault(nt.get("marker"), []).append(nt)
    for r in conv.notes["refs"]:
        q = pending.get(r["marker"])
        if not q:
            raise ValueError("note reference %r has no note" % r["marker"])
        r["target"] = q.pop(0)["id"]
    left = [m for m, q in pending.items() if q]
    if left:
        raise ValueError("notes never referenced: %r" % left)

    pages = facts["pages"]
    N = len(pages)
    mpages = []
    labels = hints.get("labels")
    off = hints.get("label-offset", 0)
    lp = expand_pages(hints.get("label-pages"), N)
    for i, (w, h) in enumerate(pages, 1):
        p = {"n": i, "w": w, "h": h, "rot": 0}
        if i in lp and labels == "arabic":
            p["label"] = str(i + off)
        elif i in lp and labels == "roman":
            p["label"] = roman(i + off).lower()
        mpages.append(p)

    furniture = []
    for f in hints.get("furniture", []):
        assert expand_pages(f.get("pages"), N), "furniture %r covers no page" % f["template"]
        item = {"id": nxt, "role": f["role"], "template": f["template"],
                "pages": expand_pages(f.get("pages"), N)}
        nxt += 1
        furniture.append(item)
    dropped = []
    for d in hints.get("dropped", []):
        for p in expand_pages(d.get("pages"), N):
            dropped.append({"reason": d["reason"], "page": p, "text": subst(d["text"], p + off, N)})
    dropped.extend(conv.dropped_extra)
    dropped.sort(key=lambda d: (d["page"], REASONS.index(d["reason"]) if d["reason"] in REASONS else 9))

    model = {"emon": "1.0", "source": {"format": "pdf", "pages": N}}
    if lang:
        model["source"]["lang"] = lang
    model["settings"] = {"locale": lang}
    if title:
        model["meta"] = {"title": title}
    model["pages"] = mpages
    model["body"] = doc
    model["furniture"] = furniture
    model["dropped"] = dropped

    used = set()

    def kinds(n):
        used.add(n["kind"])
        for c in n.get("children", []):
            if c["kind"] not in ("text", "span", "link", "ref", "linebreak"):
                kinds(c)
    kinds(doc)

    gold = {"emon-gold": "1.0", "id": doc_id}
    if extra_provenance:
        gold["provenance"] = extra_provenance
    gold["annotated"] = {
        "structure": ALL_KINDS,
        "attributes": ATTRS,
        "text": True,
        "inlines": True,
        "furniture": True,
        "dropped": REASONS,
        "pages": ["n", "w", "h", "label"],
    }
    gold["model"] = ordered(model)
    return gold


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2
    html = open(argv[1], encoding="utf-8").read()
    facts = json.load(open(argv[2], encoding="utf-8"))
    g = convert(html, facts, argv[1].rsplit("/", 1)[-1].rsplit(".", 1)[0])
    print(json.dumps(g, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
