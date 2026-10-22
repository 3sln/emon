#!/usr/bin/env python3
"""The HTML goldens' second opinion (DEVLOG P1.9, D067).

    kin/scripts/html-check.py            check (what `bb unit` runs)
    kin/scripts/html-check.py --write    (re)write the goldens from the reference

`emon.html` (kin) is what ships; the `test/html/*.html` goldens are what the
four targets must reproduce byte for byte. This script is a SECOND,
INDEPENDENT implementation of SPEC 5 in Python, and it does not read the
Model: it reads the committed JSON goldens (`test/json/NAME.json`, which
`json-check.py` proves are right), so what it checks is that the HTML says what
the JSON says. It shares no code with `kin/html.kin`. It does three things:

  1. it renders every JSON golden under each option set in VARIANTS and
     requires the result to equal the committed `test/html/NAME.VARIANT.html`;
  2. it parses every golden with html5lib in STRICT mode (any parse error is
     a failure: well-formedness) and compares what the parser saw with the
     JSON: the census of elements per node kind, and the text of every
     heading, paragraph and code block that has no inline structure;
  3. it checks that a parse-and-reserialize is stable, i.e. the output is
     already in the parser's own normal form for the tags it uses.

`html5lib` is found on the path, else in `$EMON_PYLIB`, else in
`~/.cache/emon/pylib`, else it is installed there once with pip (as
`jsonschema` is, for json-check). If it cannot be had the parse step says
SKIP, loudly, and the byte comparison still runs.
"""
import json
import os
import subprocess
import sys
import unicodedata
from decimal import Decimal

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
JSON_DIR = os.path.join(ROOT, "test", "json")
HTML_DIR = os.path.join(ROOT, "test", "html")

# Option bits, transcribed from `kin/html.kin`'s header (the `ho-*` functions).
IDS, GEOM, CONF, LEDGER, PRETTY, FURN_DROP, NO_MILE = 1, 2, 4, 8, 16, 32, 64
INITIALS_PLAIN, ALIGN_STYLE = 128, 256
# name -> (bits, confidence threshold)
VARIANTS = {
    "html": (0, 0),
    "pretty.html": (PRETTY, 0),
    "full.html": (IDS | GEOM | CONF | LEDGER, 950),
    "bare.html": (FURN_DROP | NO_MILE, 0),
    "plain.html": (INITIALS_PLAIN | ALIGN_STYLE | GEOM, 0),
}


# ------------------------------------------------------------- escaping
def esc_cp(c):
    cp = ord(c)
    if c == "&":
        return "&amp;"
    if c == "<":
        return "&lt;"
    if c == ">":
        return "&gt;"
    if c == '"':
        return "&quot;"
    if cp < 32 and cp not in (9, 10, 12, 13):
        return "�"
    if 127 <= cp <= 159:
        return "�"
    if 0xD800 <= cp <= 0xDFFF or 0xFDD0 <= cp <= 0xFDEF or (cp & 0xFFFE) == 0xFFFE or cp > 0x10FFFF:
        return "�"
    return c


def esc(s):
    return "".join(esc_cp(c) for c in s)


def cpt(v):
    return str(v) if isinstance(v, int) else format(v, "f")


# ------------------------------------------------------------- the renderer
BLOCKLESS = ("text", "span", "link", "ref", "linebreak", "initial")
STYLE_OPEN = [("bold", "<b"), ("italic", "<i"), ("underline", "<u"), ("strike", "<s"),
              ("sub", "<sub"), ("sup", "<sup"), ("mono", "<code"),
              ("smallcaps", '<span data-emon-style="smallcaps"')]
STYLE_CLOSE = {"bold": "</b>", "italic": "</i>", "underline": "</u>", "strike": "</s>",
               "sub": "</sub>", "sup": "</sup>", "mono": "</code>", "smallcaps": "</span>"}
SEAL_WORD = {"seal": "Seal", "stamp": "Stamp", "signature": "Signature",
             "initials": "Initials", "digital-signature": "Digital signature"}


class R:
    def __init__(self, bits, conf_below, node_kinds, pages=(), targeted=()):
        self.o, self.cb, self.kinds = bits, conf_below, node_kinds
        self.pages, self.targeted = pages, targeted

    def cells(self, n):
        """The column cells (`page:band:col`) holding the centre of each region of
        node n, in order (D113). The centre is the sum of the halves, each
        truncated toward zero, in centipoints, and a cell contains it edges
        included; the first cell of the page's map that does wins."""
        out = []
        for r in n.get("at", []):
            pg, x0, y0, x1, y1 = [int(Decimal(v) * 100) for v in r]
            pg = int(Decimal(r[0]))
            cx, cy = half(x0) + half(x1), half(y0) + half(y1)
            for p in self.pages:
                if p["n"] != pg:
                    continue
                for c in p.get("layout", []):
                    bx0, by0, bx1, by1 = [int(Decimal(v) * 100) for v in c["box"]]
                    if bx0 <= cx <= bx1 and by0 <= cy <= by1:
                        out.append("%d:%d:%d" % (pg, c["band"], c["col"]))
                        break
                break
        return out

    def align(self, n):
        if "align" not in n:
            return ""
        s = ' data-emon-align="%s"' % n["align"]
        if self.on(ALIGN_STYLE):
            s += ' style="text-align:%s" dir="auto"' % n["align"]
        return s

    def on(self, bit):
        return bool(self.o & bit)

    def nl(self, d):
        return "\n" + "  " * d if self.on(PRETTY) else ""

    def common(self, n):
        s = ""
        inline = n["kind"] in BLOCKLESS or "id" not in n    # an inline milestone has no id (D134)
        if self.on(IDS) and not inline and n["kind"] != "note":
            s += ' id="emon-%d"' % n["id"]
        if self.on(GEOM) and n.get("at"):
            s += ' data-emon-at="%s"' % ";".join(
                ",".join(cpt(x) for x in r) for r in n["at"])
        if self.on(GEOM) and not inline and n.get("at"):
            cs = self.cells(n)
            if cs:
                s += ' data-emon-col="%s"' % cs[0]
            if len(cs) > 1:
                s += ' data-emon-cols="%s"' % " ".join(cs)
        if self.on(CONF) and "conf" in n and n["conf"] < self.cb:
            s += ' data-emon-conf="%d"' % n["conf"]
        return s

    def target_href(self, t):
        return ("#fn-%d" if self.kinds[t] == "note" else "#emon-%d") % t

    # ---- inline content
    def inner(self, n):
        if "inlines" in n:
            return "".join(self.inline(c) for c in n["inlines"])
        if "children" in n and n["kind"] in BLOCKLESS:
            return "".join(self.inline(c) for c in n["children"])
        return esc(n["text"]) if "text" in n else ""

    def inline(self, n):
        k = n["kind"]
        if k == "text":
            return esc(n.get("text", ""))
        if k == "initial":
            if self.on(INITIALS_PLAIN):
                return self.inner(n)
            s = '<span data-emon="initial"'
            s += self.attr("data-emon-form", n, "form")
            s += self.attr("data-emon-lines", n, "lines", str)
            return s + self.common(n) + ">" + self.inner(n) + "</span>"
        if k == "break":                # a milestone inside a paragraph (D134)
            if self.on(NO_MILE):
                return ""
            s = '<span data-emon-page="%d"' % n["page"]
            s += self.attr("data-emon-page-label", n, "label")
            return s + self.common(n) + "></span>"
        if k == "linebreak":
            return "<br%s>" % self.common(n)
        if k == "span":
            styles = n.get("style", [])
            if not styles:
                return "<span%s>%s</span>" % (self.common(n), self.inner(n))
            out, first = "", True
            for name, tag in STYLE_OPEN:
                if name in styles:
                    out += tag + (self.common(n) if first else "") + ">"
                    first = False
            out += self.inner(n)
            for name, _ in reversed(STYLE_OPEN):
                if name in styles:
                    out += STYLE_CLOSE[name]
            return out
        if k == "link":
            href = ""
            if "href" in n:
                href = ' href="%s"' % esc(n["href"])
            elif "target" in n:
                href = ' href="%s"' % self.target_href(n["target"])
            return "<a%s%s>%s</a>" % (href, self.common(n), self.inner(n))
        if k == "ref":
            if "target" in n:
                open_, close = '<a role="doc-noteref" href="%s"' % self.target_href(n["target"]), "</a>"
            else:
                open_, close = '<span role="doc-noteref"', "</span>"
            if "children" in n or "text" in n:
                body = self.inner(n)
            else:
                body = esc(n["marker"]) if "marker" in n else ""
            return open_ + self.common(n) + ">" + body + close
        if k == "field":                # a form field inside a paragraph (P11.x3, D548)
            return self.field(n)
        raise ValueError(k)

    def field(self, n):
        """A form field (SPEC 5.1): a span, never an input. A check box or radio button says what it is
        (role, aria-checked), keeps its export value in data-emon-value and has an empty element; any
        other field shows its value as its content."""
        button = n.get("type") in ("checkbox", "radio")
        o = '<span data-emon="field"'
        o += self.attr("data-emon-name", n, "name")
        o += self.attr("data-emon-type", n, "type")
        if button:
            o += ' role="%s"' % n["type"]
        if "checked" in n:
            on = "true" if n["checked"] else "false"
            o += ' data-emon-checked="%s"' % on
            if button:
                o += ' aria-checked="%s"' % on
        if button:
            o += self.attr("data-emon-value", n, "value")
        if "value" in n:
            body = "" if button else esc(n["value"])
        else:
            body = self.inner(n)
        return o + self.common(n) + ">" + body + "</span>"

    # ---- blocks
    def blocks(self, n, d, skip=None):
        kids = n.get("children", [])
        return [self.block(c, d, n["kind"]) for c in kids]

    def body(self, n, d):
        """Inline content, then the block children; answers (text, #blocks)."""
        s = self.inner(n)
        parts = [p for p in self.blocks(n, d) if p]
        return s + "".join(parts), len(parts)

    def wrap(self, open_, close, n, d):
        s, hb = self.body(n, d + 1)
        return open_ + s + (self.nl(d) if hb else "") + close

    def attr(self, name, n, key, conv=esc):
        return ' %s="%s"' % (name, conv(n[key])) if key in n else ""

    def block(self, n, d, parent=None):
        k = n["kind"]
        if k in BLOCKLESS:
            return self.nl(d) + self.inline(n)
        if k == "break":
            if self.on(NO_MILE):
                return ""
            s = '<span data-emon-page="%d"' % n["page"]
            s += self.attr("data-emon-page-label", n, "label")
            return self.nl(d) + s + self.common(n) + "></span>"
        nl = self.nl(d)
        c = self.common(n)
        if k == "section":
            return nl + self.wrap("<section%s>" % c, "</section>", n, d)
        if k == "heading":
            lv = max(n.get("level", 0), 1)
            pk = min(lv, 6)
            o = "<h%d" % pk
            if lv > 6:
                o += ' aria-level="%d"' % lv
            o += self.attr("data-emon-number", n, "number")
            o += self.align(n)
            o += c + ">"
            if "number" in n:
                o += esc(n["number"]) + " "
            return nl + self.wrap(o, "</h%d>" % pk, n, d)
        if k == "paragraph":
            return nl + self.wrap("<p%s%s>" % (self.align(n), c), "</p>", n, d)
        if k == "list":
            if n.get("ordered"):
                o = "<ol"
                if "start" in n:
                    o += ' start="%d"' % n["start"]
                sc = n.get("scheme", "")
                if len(sc) == 11:
                    o += {("l", "a"): ' type="a"', ("u", "a"): ' type="A"',
                          ("l", "r"): ' type="i"', ("u", "r"): ' type="I"'}.get((sc[0], sc[6]), "")
                return nl + self.wrap(o + c + ">", "</ol>", n, d)
            return nl + self.wrap("<ul%s>" % c, "</ul>", n, d)
        if k == "item":
            o = "<li"
            if "value" in n:
                o += ' value="%d"' % n["value"]
            o += self.attr("data-emon-marker", n, "marker")
            return nl + self.wrap(o + c + ">", "</li>", n, d)
        if k == "table":
            kids = n.get("children", [])
            s = "<table%s>" % c
            for ch in kids:
                if ch["kind"] == "caption":
                    s += self.block(ch, d + 1, "table")
            for ch in kids:
                if ch["kind"] != "caption":
                    s += self.block(ch, d + 1, "table")
            return nl + s + self.nl(d) + "</table>"
        if k == "rowgroup":
            tag = {"head": "thead", "foot": "tfoot"}.get(n.get("role"), "tbody")
            return nl + self.children_only("<%s%s>" % (tag, c), "</%s>" % tag, n, d)
        if k == "row":
            o = "<tr" + (' data-emon-repeated="true"' if n.get("repeated") else "") + c + ">"
            return nl + self.children_only(o, "</tr>", n, d)
        if k == "cell":
            hd = n.get("header")
            tag = "th" if hd else "td"
            o = '<th scope="%s"' % hd if hd else "<td"
            for key in ("rowspan", "colspan"):
                if key in n and n[key] != 1:
                    o += ' %s="%d"' % (key, n[key])
            return nl + self.wrap(o + self.align(n) + c + ">", "</%s>" % tag, n, d)
        if k == "caption":
            tag = {"table": "caption", "figure": "figcaption"}.get(parent)
            o = "<" + tag if tag else '<p data-emon="caption"'
            o += self.attr("data-emon-label", n, "label") + self.align(n) + c + ">"
            return nl + self.wrap(o, "</%s>" % (tag or "p"), n, d)
        if k == "figure":
            o = '<figure data-emon-kind="%s" role="img"' % n.get("subkind", "unknown")
            o += self.attr("aria-label", n, "alt")
            o += self.attr("data-emon-label", n, "label") + c + ">"
            s = self.inner(n)
            # D559: a space between two label spans (an axis title and its ticks are tokens, not one run)
            s += " ".join('<span data-emon="label">%s</span>' % esc(lb) for lb in n.get("labels", []))
            parts = [p for p in self.blocks(n, d + 1) if p]
            return nl + o + s + "".join(parts) + (self.nl(d) if parts else "") + "</figure>"
        if k == "seal":
            sk = n.get("subkind", "seal")
            o = '<figure data-emon-kind="%s"' % sk
            o += self.attr("data-emon-name", n, "name")
            o += self.attr("data-emon-date", n, "date")
            o += self.attr("data-emon-color", n, "color")
            word = SEAL_WORD.get(sk, "Seal")
            if "text" in n:
                label = word + ": " + esc(n["text"])
            elif "name" in n:
                label = word + ": " + esc(n["name"])
            else:
                label = word
            return nl + o + ' role="img" aria-label="%s"%s></figure>' % (label, c)
        if k == "formula":
            o = '<figure data-emon-kind="formula"'
            o += self.attr("data-emon-display", n, "display")
            return nl + o + ' aria-label="%s"%s></figure>' % (esc(n.get("text", "")), c)
        if k == "code":
            s = self.inner(n)
            parts = [p for p in self.blocks(n, d + 1) if p]
            return (nl + "<pre%s><code>" % c + s + "</code>" + "".join(parts)
                    + (self.nl(d) if parts else "") + "</pre>")
        if k == "quote":
            return nl + self.wrap("<blockquote%s>" % c, "</blockquote>", n, d)
        if k == "note":
            if n.get("role") in ("margin", "sidebar"):
                o = '<aside data-emon-role="%s"' % n["role"]
                if n["id"] in self.targeted:
                    o += ' id="fn-%d"' % n["id"]
            else:
                role = "doc-endnote" if n.get("role") == "endnote" else "doc-footnote"
                o = '<aside role="%s" id="fn-%d"' % (role, n["id"])
            o += self.attr("data-emon-marker", n, "marker") + c + ">"
            return nl + self.wrap(o, "</aside>", n, d)
        if k == "field":
            return nl + self.field(n)
        raise ValueError("kind " + k)

    def children_only(self, open_, close, n, d):
        parts = [p for p in self.blocks(n, d + 1) if p]
        return open_ + "".join(parts) + (self.nl(d) if parts else "") + close

    def furniture(self, f, d):
        o = '<aside data-emon="furniture"'
        o += self.attr("data-emon-role", f, "role")
        if "pages" in f:
            o += ' data-emon-pages="%s"' % " ".join(str(p) for p in f["pages"])
        f = dict(f, kind="furniture")
        o += self.common(f) + ">"
        parts = []
        if "template" in f:
            parts.append(self.nl(d + 1) + "<p>%s</p>" % esc(f["template"]))
        parts += [p for p in self.blocks(f, d + 1) if p]
        return self.nl(d) + o + "".join(parts) + (self.nl(d) if parts else "") + "</aside>"


def half(v):
    """Half, truncated toward zero (kin's `quot`)."""
    return -(-v // 2) if v < 0 else v // 2


def targets(doc):
    """The ids a `ref` or a `link` points at."""
    out = set()

    def walk(n):
        if n["kind"] in ("ref", "link") and "target" in n:
            out.add(n["target"])
        for key in ("children", "inlines"):
            for c in n.get(key, []):
                walk(c)
    walk(doc["body"])
    for f in doc.get("furniture", []):
        for c in f.get("children", []):
            walk(c)
    return out


def kind_index(doc):
    idx = {}

    def walk(n):
        if "id" in n:
            idx[n["id"]] = n["kind"]
        for c in n.get("children", []):
            walk(c)
    walk(doc["body"])
    for f in doc.get("furniture", []):
        idx[f["id"]] = "furniture"
        for c in f.get("children", []):
            walk(c)
    return idx


def render(doc, bits, cb):
    r = R(bits, cb, kind_index(doc), doc.get("pages", []), targets(doc))
    src, meta = doc.get("source", {}), doc.get("meta", {})
    nl = r.nl
    out = "<!doctype html>" + nl(0) + "<html"
    def strong_counts(nodes):
        rtl = ltr = 0
        for n in nodes:
            if isinstance(n, str):
                cr, cl = strong_counts([{"text": n}])
                rtl += cr
                ltr += cl
                continue
            for cp in n.get("text", ""):
                direction = unicodedata.bidirectional(cp)
                rtl += direction in ("R", "AL")
                ltr += direction == "L"
            for key in ("children", "inlines"):
                cr, cl = strong_counts(n.get(key, []))
                rtl += cr
                ltr += cl
        return rtl, ltr
    rtl, ltr = strong_counts([doc["body"]] + doc.get("furniture", []))
    if "lang" in src:
        out += ' lang="%s"' % esc(src["lang"])
    elif rtl > ltr:
        out += ' lang="und"'
    if rtl > ltr:
        out += ' dir="rtl"'
    out += ">" + nl(1) + "<head>" + nl(2) + '<meta charset="utf-8">' + nl(2)
    out += "<title>%s</title>" % esc(meta.get("title", ""))
    for name, val in (("generator", doc.get("generator")),) + tuple(
            (k, meta.get(k)) for k in ("author", "subject", "keywords", "creator",
                                       "producer", "created", "modified")):
        if val is not None:
            out += nl(2) + '<meta name="%s" content="%s">' % (name, esc(val))
    out += nl(1) + "</head>" + nl(1) + "<body>" + nl(2) + "<main>"
    if not bits & FURN_DROP:
        for f in doc.get("furniture", []):
            out += r.furniture(f, 3)
    out += "".join(p for p in r.blocks(doc["body"], 3) if p)
    if bits & LEDGER:
        for d_ in doc.get("dropped", []):
            out += nl(3) + '<template data-emon="dropped" data-emon-reason="%s" data-emon-page="%d">%s</template>' % (
                d_["reason"], d_["page"], esc(d_["text"]) if "text" in d_ else "")
    out += nl(2) + "</main>" + nl(1) + "</body>" + nl(0) + "</html>"
    return out


# ----------------------------------------------------------- html5lib checks
def find_html5lib():
    try:
        import html5lib  # noqa
        return html5lib
    except ImportError:
        pass
    for lib in (os.environ.get("EMON_PYLIB"), os.path.expanduser("~/.cache/emon/pylib")):
        if lib and os.path.isdir(lib):
            sys.path.insert(0, lib)
            try:
                import html5lib  # noqa
                return html5lib
            except ImportError:
                sys.path.pop(0)
    lib = os.path.expanduser("~/.cache/emon/pylib")
    r = subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", "--target", lib,
                        "html5lib"], capture_output=True, text=True)
    if r.returncode == 0:
        sys.path.insert(0, lib)
        try:
            import html5lib  # noqa
            return html5lib
        except ImportError:
            pass
    return None


def text_of(el):
    """textContent, with <br> as a newline (the JSON's derived text)."""
    out = []

    def walk(e):
        if isinstance(e.tag, str) and e.tag.endswith("br"):
            out.append("\n")
        if e.text:
            out.append(e.text)
        for c in e:
            walk(c)
            if c.tail:
                out.append(c.tail)
    walk(el)
    return "".join(out)


def json_census(doc, bits=0):
    """Expected element counts, from the JSON: name -> count."""
    cen = {}

    def add(k, n=1):
        cen[k] = cen.get(k, 0) + n

    def walk(n, parent=None):
        k = n["kind"]
        if k == "section":
            add("section")
        elif k == "heading":
            add("h%d" % min(max(n.get("level", 0), 1), 6))
        elif k == "paragraph":
            add("p")
        elif k == "list":
            add("ol" if n.get("ordered") else "ul")
        elif k == "item":
            add("li")
        elif k == "table":
            add("table")
        elif k == "rowgroup":
            add({"head": "thead", "foot": "tfoot"}.get(n.get("role"), "tbody"))
        elif k == "row":
            add("tr")
        elif k == "cell":
            add("th" if n.get("header") else "td")
        elif k == "caption":
            add({"table": "caption", "figure": "figcaption"}.get(parent, "p"))
        elif k in ("figure", "seal", "formula"):
            add("figure")
        elif k == "code":
            add("pre")
        elif k == "quote":
            add("blockquote")
        elif k == "note":
            add("aside")
        elif k == "field":
            add("span-field")
        elif k == "break":
            add("span-page")
        elif k == "linebreak":
            add("br")
        elif k == "link":
            add("a")
        elif k == "ref":
            add("a-or-span-noteref")
        elif k == "initial":
            if not bits & INITIALS_PLAIN:
                add("span-initial")
        for key in ("children", "inlines"):
            for c in n.get(key, []):
                walk(c, k)
        if k == "figure":
            add("span-label", len(n.get("labels", [])))

    walk(doc["body"])
    for f in doc.get("furniture", []):
        add("aside")
        if "template" in f:
            add("p")
        for c in f.get("children", []):
            walk(c, "furniture")
    return cen


def html_census(root):
    cen = {}

    def add(k):
        cen[k] = cen.get(k, 0) + 1

    def walk(e):
        t = e.tag.split("}")[-1] if isinstance(e.tag, str) else ""
        if t in ("section", "h1", "h2", "h3", "h4", "h5", "h6", "p", "ol", "ul", "li",
                 "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption",
                 "figcaption", "figure", "pre", "blockquote", "aside", "br", "a"):
            add(t)
        elif t == "span":
            if e.get("data-emon") == "field":
                add("span-field")
            elif e.get("data-emon-page") is not None:
                add("span-page")
            elif e.get("data-emon") == "label":
                add("span-label")
            elif e.get("data-emon") == "initial":
                add("span-initial")
            elif e.get("role") == "doc-noteref":
                add("a-or-span-noteref")
        for c in e:
            walk(c)
    walk(root)
    # a ref is an <a> (counted above) or a span; link <a>s are the rest
    return cen


def structure_check(h5, name, html, doc, bits):
    p = h5.HTMLParser(tree=h5.getTreeBuilder("etree"), strict=True,
                      namespaceHTMLElements=False)
    try:
        tree = p.parse(html)
    except Exception as e:  # html5lib raises ParseError in strict mode
        return ["%s: parse error: %s" % (name, e)]
    bad = []
    main = tree.find(".//main")
    if main is None:
        return ["%s: no <main>" % name]
    # ---- census (only for the variants that drop nothing the JSON lists)
    if not bits & (FURN_DROP | NO_MILE):
        want = json_census(doc, bits)
        # ledger templates are not in the census; aside/p of furniture are
        want.setdefault("a", 0)
        refs = want.pop("a-or-span-noteref", 0)
        got = html_census(main)
        got.pop("a-or-span-noteref", None)
        got_refs = sum(1 for e in main.iter() if e.get("role") == "doc-noteref")
        got["a"] = got.get("a", 0) - sum(1 for e in main.iter() if e.tag == "a" and e.get("role") == "doc-noteref")
        if got_refs != refs:
            bad.append("%s: %d noterefs, JSON has %d" % (name, got_refs, refs))
        for k in sorted(set(want) | set(got)):
            if want.get(k, 0) != got.get(k, 0):
                bad.append("%s: %d <%s>, the JSON has %d" % (name, got.get(k, 0), k, want.get(k, 0)))
    # ---- text: headings, paragraphs and code with no inline structure
    want_txt = []

    def walk(n):
        k = n["kind"]
        if k in ("heading", "paragraph", "code") and "inlines" not in n and "children" not in n:
            t = n.get("text", "")
            if k == "heading" and "number" in n:
                t = n["number"] + " " + t
            want_txt.append((k, t))
        for c in n.get("children", []):
            walk(c)
    walk(doc["body"])
    got_txt = []

    def walk_h(e):
        t = e.tag if isinstance(e.tag, str) else ""
        if t in ("h1", "h2", "h3", "h4", "h5", "h6", "p", "pre") and e.get("data-emon") != "caption":
            got_txt.append((t, text_of(e)))
        for c in e:
            walk_h(c)
    walk_h(main)
    kmap = {"heading": "h", "paragraph": "p", "code": "p"}
    pos = 0
    for k, t in want_txt:
        # what a parser makes of what we wrote: forbidden characters were
        # replaced, and a CR is folded into an LF
        t = "".join(esc_cp(c) if c in '\ufffd' or esc_cp(c) == "\ufffd" else c for c in t)
        t = t.replace("\r\n", "\n").replace("\r", "\n")
        for j in range(pos, len(got_txt)):
            tag, s = got_txt[j]
            if tag[0] == kmap[k] and s == t:
                pos = j + 1
                break
        else:
            bad.append("%s: the JSON's %s %r is not in the HTML, in order" % (name, k, t[:40]))
    return bad


# ------------------------------------------------------------------- main
def golden(path):
    with open(path, encoding="utf-8", newline="") as f:
        s = f.read()
    return s[:-1] if s.endswith("\n") else s


def main():
    write = "--write" in sys.argv
    names = sorted(f[:-5] for f in os.listdir(JSON_DIR)
                   if f.endswith(".json") and not f.endswith(".pretty.json"))
    os.makedirs(HTML_DIR, exist_ok=True)
    h5 = None if write else find_html5lib()
    bad, count = 0, 0
    problems = []
    for n in names:
        with open(os.path.join(JSON_DIR, n + ".json"), encoding="utf-8") as f:
            doc = json.load(f, parse_float=Decimal)
        for v, (bits, cb) in VARIANTS.items():
            want = render(doc, bits, cb)
            path = os.path.join(HTML_DIR, "%s.%s" % (n, v))
            count += 1
            if write:
                with open(path, "w", encoding="utf-8", newline="") as f:
                    f.write(want + "\n")
                continue
            if not os.path.exists(path) or golden(path) != want:
                print("  FAIL html-check: %s.%s differs from the reference renderer" % (n, v))
                bad += 1
            elif h5 is not None:
                problems += structure_check(h5, "%s.%s" % (n, v), want, doc, bits)
    if write:
        print("  wrote %d goldens" % count)
        return 0
    if bad:
        return 1
    print("  ok   html-check: %d goldens (%d models x %d option sets) equal the reference" % (
        count, len(names), len(VARIANTS)))
    if h5 is None:
        print("  SKIP html-check: no html5lib")
        return 0
    for p in problems[:10]:
        print("  FAIL html-check: " + p)
    if problems:
        return 1
    print("  ok   html-check: %d goldens parse with html5lib (strict, zero errors) and match the JSON's structure" % count)
    return 0


if __name__ == "__main__":
    sys.exit(main())
