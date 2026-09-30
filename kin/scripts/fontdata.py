#!/usr/bin/env python3
"""Generate kin/agl.kin and kin/encodings.kin, the data behind `emon.pdffont`,
from the files vendored in test/fonts/.

    python3 kin/scripts/fontdata.py                write both files
    python3 kin/scripts/fontdata.py --check        fail when either is stale
    python3 kin/scripts/fontdata.py --check-ref    also compare the derived encodings
                                                   with pdfminer.six's copy of the PDF
                                                   specification's Annex D (tooling:
                                                   needs `pip install --target
                                                   ~/.cache/emon/pylib pdfminer.six`)

emon's own script (D039). Inputs, all Adobe's and vendored (licences in the
files): glyphlist.txt and zapfdingbats.txt (the Adobe Glyph List), aglfn.txt
(the AGL for new fonts: ONE preferred name per code point, used here only to
name the characters of Python's codecs), and the fourteen standard-font AFMs.

What comes out:

  agl.kin
    agl-pool   the bytes of every GLYPH NAME, back to back, names sorted by bytes
    agl-name   [offset length unicode-offset unicode-count zapf]; a glyph's ID is its row.
               zapf is 1 for the names that come from zapfdingbats.txt (a1..a206): they
               name ZapfDingbats' glyphs and nothing else (a Type 3 font's own glyph
               names are often a1, a2, ...), so a font that is not ZapfDingbats
               does not read them as Unicode
    agl-uni    the code points the names map to (AGL: up to 3 per name)

  encodings.kin
    enc-gid    glyph id (or -1) of every code of StandardEncoding, WinAnsiEncoding,
               MacRomanEncoding, and the built-in encodings of Symbol and
               ZapfDingbats, 256 each, in that order
    s14-width  [font*8192 + glyph id, width] the AFM widths of the fourteen
               standard fonts (font: Courier 0..3, Helvetica 4..7, Times 8..11,
               Symbol 12, ZapfDingbats 13), sorted
    s14-metric [ascender descender] per font, from the AFM
    s14-alias  the normalised BaseFont names (lower case, alphanumerics only)
               that mean a standard font, sorted by bytes: [offset length font]

The encodings are DERIVED, not typed: StandardEncoding and the two symbolic
built-ins are the AFMs' character codes; WinAnsi and MacRoman are Python's
cp1252 and mac_roman with the PDF specification's own exceptions (below),
named by aglfn.txt. `--check-ref` holds the result against pdfminer.six's
table, which was typed from the specification (Annex D) by someone else.
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
SRC = os.path.join(ROOT, "test", "fonts")

STD14 = ["Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique",
         "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
         "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic",
         "Symbol", "ZapfDingbats"]


def rd(name):
    return open(os.path.join(SRC, name), encoding="latin-1").read()


ZAPF_NAMES = {}


def parse_agl():
    agl = {}
    for line in rd("glyphlist.txt").splitlines():
        if not line or line.startswith("#"):
            continue
        n, u = line.split(";")
        agl[n] = [int(x, 16) for x in u.split()]
    zapf = {}
    for line in rd("zapfdingbats.txt").splitlines():
        if not line or line.startswith("#"):
            continue
        n, u = line.split(";")
        zapf[n] = [int(x, 16) for x in u.split()]
    for n, u in zapf.items():
        assert n not in agl, n          # the two lists share no name
    agl.update(zapf)
    ZAPF_NAMES.update(zapf)
    return agl


def parse_aglfn():
    r = {}
    for line in rd("aglfn.txt").splitlines():
        if not line or line.startswith("#"):
            continue
        u, n, _ = line.split(";")
        r[int(u, 16)] = n
    return r


def parse_afm(font):
    chars = []      # (code, width, name)
    meta = {}
    for line in rd(font + ".afm").splitlines():
        if line.startswith("C "):
            m = re.match(r"C\s+(-?\d+)\s*;\s*WX\s+(-?\d+)\s*;\s*N\s+(\S+)\s*;", line)
            chars.append((int(m.group(1)), int(m.group(2)), m.group(3)))
        else:
            k, _, v = line.partition(" ")
            if k in ("Ascender", "Descender", "FontBBox", "CapHeight"):
                meta[k] = v.split()
    return chars, meta


def name_for(u, aglfn, agl):
    """The glyph name of a code point: aglfn's, else the shortest AGL name that
    maps to exactly it (aglfn leaves out the ligatures and the superscripts)."""
    if u in aglfn:
        return aglfn[u]
    cands = sorted((n for n, v in agl.items() if v == [u] and not n.startswith("afii")),
                   key=lambda n: (len(n), n))
    return cands[0] if cands else None


def derive_encodings(aglfn, afm, agl):
    std = [None] * 256
    for code, _, n in afm["Times-Roman"][0]:
        if code >= 0:
            std[code] = n
    sym = [None] * 256
    for code, _, n in afm["Symbol"][0]:
        if code >= 0:
            sym[code] = n
    zap = [None] * 256
    for code, _, n in afm["ZapfDingbats"][0]:
        if code >= 0:
            zap[code] = n

    # WinAnsiEncoding (ISO 32000-1 D.2): Windows code page 1252, except that
    # every unused code above 32 is the bullet, 160 is the space, 173 the
    # hyphen. Names from aglfn (one per code point).
    win = [None] * 256
    for c in range(32, 256):
        try:
            u = ord(bytes([c]).decode("cp1252"))
        except UnicodeDecodeError:
            win[c] = "bullet"
            continue
        win[c] = name_for(u, aglfn, agl) or "bullet"

    win[0x7F] = "bullet"
    win[0xA0] = "space"
    win[0xAD] = "hyphen"

    # MacRomanEncoding: Mac OS Roman, except 0xDB (Euro in Mac OS 8.5, a
    # currency sign in the PDF), 0xCA (a space) and 0xF0 (the Apple logo).
    mac = [None] * 256
    for c in range(32, 256):
        u = ord(bytes([c]).decode("mac_roman"))
        if u == 0xF8FF:
            continue
        mac[c] = name_for(u, aglfn, agl)
    mac[0xDB] = "currency"
    mac[0xCA] = "space"
    mac[0x7F] = None
    return [std, win, mac, sym, zap]


def std14_aliases():
    """Every normalised name that means one of the fourteen: lower case, only
    letters and digits (`Arial-BoldMT`, `Arial,Bold` -> `arialboldmt`, `arialbold`)."""
    fam = {"courier": 0, "couriernew": 0, "helvetica": 4, "arial": 4,
           "times": 8, "timesroman": 8, "timesnewroman": 8}
    # style -> offset within the family of four
    styles = {"": 0, "roman": 0, "regular": 0, "normal": 0, "bold": 1, "italic": 2, "oblique": 2,
              "bolditalic": 3, "boldoblique": 3, "italicbold": 3, "obliquebold": 3}
    out = {}
    for f, base in fam.items():
        for s, off in styles.items():
            for infix in ("", "ps"):
                for suffix in ("", "mt"):
                    if base == 8 and off == 0 and s == "":
                        pass
                    out[f + infix + s + suffix] = base + off
                    if suffix == "mt":
                        out[f + infix + suffix + s] = base + off
    for n, i in (("symbol", 12), ("symbolmt", 12), ("zapfdingbats", 13), ("itczapfdingbats", 13),
                 ("zapfdingbatsitc", 13), ("zapfdingbatsregular", 13), ("dingbats", 13)):
        out[n] = i
    # the names themselves
    for i, n in enumerate(STD14):
        out[re.sub(r"[^a-z0-9]", "", n.lower())] = i
    # Times-Roman's regular style in the family spelling "Times-Roman"
    return out


# The tokens of a CMap program that `emon.pdffont` acts on (kin has no string
# literals), and the ids it tests for: [name, id]. The names are matched
# whether they are keywords or /names.
CMAP_TOKENS = [("begincodespacerange", 1), ("endcodespacerange", 2),
               ("beginbfchar", 3), ("endbfchar", 4),
               ("beginbfrange", 5), ("endbfrange", 6),
               ("begincidrange", 7), ("endcidrange", 8),
               ("begincidchar", 9), ("endcidchar", 10),
               ("beginnotdefrange", 11), ("endnotdefrange", 12),
               ("beginnotdefchar", 11), ("endnotdefchar", 12),
               ("usecmap", 13), ("def", 14), ("WMode", 20)]


def build():
    agl = parse_agl()
    aglfn = parse_aglfn()
    afm = {f: parse_afm(f) for f in STD14}
    encs = derive_encodings(aglfn, afm, agl)
    names = set(agl)
    for e in encs:
        names.update(n for n in e if n)
    for f in STD14:
        names.update(n for _, _, n in afm[f][0])
    names = sorted(names, key=lambda s: s.encode("ascii"))
    gid = {n: i for i, n in enumerate(names)}
    assert len(names) < 8192, len(names)
    pool, rows, uni = [], [], []
    for n in names:
        b = n.encode("ascii")
        u = agl.get(n, [])
        rows.append((len(pool), len(b), len(uni), len(u), 1 if n in ZAPF_NAMES else 0))
        pool.extend(b)
        uni.extend(u)
    enc_flat = []
    for e in encs:
        enc_flat.extend(gid[n] if n else -1 for n in e)
    widths = []
    for fi, f in enumerate(STD14):
        seen = set()
        for _, w, n in afm[f][0]:
            if n in seen:
                continue
            seen.add(n)
            widths.append((fi * 8192 + gid[n], w))
    widths.sort()
    metric = []
    for f in STD14:
        m = afm[f][1]
        bb = [int(x) for x in m["FontBBox"]]
        asc = int(m["Ascender"][0]) if "Ascender" in m else bb[3]
        dsc = int(m["Descender"][0]) if "Descender" in m else bb[1]
        metric.append((asc, dsc))
    alias = sorted(std14_aliases().items(), key=lambda kv: kv[0].encode("ascii"))
    apool, arows = [], []
    for n, f in alias:
        b = n.encode("ascii")
        arows.append((len(apool), len(b), f))
        apool.extend(b)
    kpool, krows = [], []
    for n, i in CMAP_TOKENS:
        b = n.encode("ascii")
        krows.append((len(kpool), len(b), i))
        kpool.extend(b)
    return dict(kpool=kpool, krows=krows, names=names, gid=gid, pool=pool, rows=rows, uni=uni, enc=encs,
                enc_flat=enc_flat, widths=widths, metric=metric, apool=apool,
                arows=arows, agl=agl)


def table(name, doc, stride, flat, accessors):
    lines = [f";; {doc}",
             f"(defdata ^:pub {name}",
             "  :emitter kin.lang/flat-array"]
    if stride > 1:
        lines.append(f"  :stride {stride}")
    per = stride if stride > 1 else 16
    body = []
    for i in range(0, len(flat), per):
        body.append(" ".join(str(x) for x in flat[i:i + per]))
    lines.append("  :data [" + "\n         ".join(body) + "]")
    lines.append("  :accessors {" + "\n              ".join(accessors) + "})")
    return "\n".join(lines) + "\n"


def render():
    d = build()
    agl_kin = f''';; GENERATED by kin/scripts/fontdata.py from test/fonts/ -- do not edit.
;;
;; The Adobe Glyph List for `emon.pdffont` (P1.6): {len(d["names"])} glyph names (the
;; {len(d["agl"])} of glyphlist.txt and zapfdingbats.txt, plus any name the standard
;; fonts' AFMs and the encodings use that they lack), sorted by their bytes, so
;; a name is found by binary search and its row number IS its glyph id. A name
;; with no Unicode value has a unicode-count of 0.
(ns emon.agl
  (:require [emon.lang :refer [I32]]
            [kin.lang :refer [defdata]]))

'''
    agl_kin += table("agl-pool", "the bytes of every glyph name, back to back", 1, d["pool"],
                     ["^I32 agl-pool-len [^I32 ignored]", "^I32 agl-pool-at [^I32 ignored ^I32 i]"])
    agl_kin += "\n" + table("agl-name", "offset into agl-pool, length, offset into agl-uni, count, 1 for a ZapfDingbats name", 5,
                            [x for r in d["rows"] for x in r],
                            ["^I32 agl-count [^I32 ignored]", "^I32 agl-at [^I32 ignored ^I32 row ^I32 field]"])
    agl_kin += "\n" + table("agl-uni", "the code points the glyph names map to", 1, d["uni"],
                            ["^I32 agl-uni-at [^I32 ignored ^I32 i]"])

    names, gid = d["names"], d["gid"]
    enc_kin = f''';; GENERATED by kin/scripts/fontdata.py from test/fonts/ -- do not edit.
;;
;; Encodings and the standard-14 metrics for `emon.pdffont` (P1.6).
;; Glyph ids are rows of `emon.agl`'s `agl-name`.
;;
;; `enc-gid`: StandardEncoding, WinAnsiEncoding, MacRomanEncoding, then the
;; built-in encodings of Symbol and ZapfDingbats (encoding numbers 0..4), 256
;; codes each, -1 where a code has no glyph.
;; `s14-width`: {len(d["widths"])} AFM widths of the fourteen standard fonts.
;; `s14-alias`: {len(d["arows"])} names (lower case, letters and digits only) that mean one.
(ns emon.encodings
  (:require [emon.lang :refer [I32]]
            [kin.lang :refer [defdata]]))

'''
    enc_kin += table("enc-gid", "glyph id of code c of encoding e is row e * 256 + c", 1, d["enc_flat"],
                     ["^I32 enc-gid-at [^I32 ignored ^I32 i]"])
    enc_kin += "\n" + table("s14-width", "font * 8192 + glyph id, AFM width; sorted", 2,
                            [x for r in d["widths"] for x in r],
                            ["^I32 s14-count [^I32 ignored]", "^I32 s14-at [^I32 ignored ^I32 row ^I32 field]"])
    enc_kin += "\n" + table("s14-metric", "ascender, descender of each standard font (AFM)", 2,
                            [x for r in d["metric"] for x in r],
                            ["^I32 s14-metric-at [^I32 ignored ^I32 row ^I32 field]"])
    enc_kin += "\n" + table("s14-alias-pool", "the bytes of every normalised name, back to back", 1, d["apool"],
                            ["^I32 s14-alias-pool-at [^I32 ignored ^I32 i]"])
    enc_kin += "\n" + table("s14-alias", "offset into s14-alias-pool, length, font; sorted by the bytes", 3,
                            [x for r in d["arows"] for x in r],
                            ["^I32 s14-alias-count [^I32 ignored]",
                             "^I32 s14-alias-at [^I32 ignored ^I32 row ^I32 field]"])
    enc_kin += "\n" + table("cm-kw-pool", "the bytes of the CMap tokens that matter, back to back", 1, d["kpool"],
                            ["^I32 cm-kw-pool-at [^I32 ignored ^I32 i]"])
    enc_kin += "\n" + table("cm-kw", "offset into cm-kw-pool, length, token id (emon.pdffont)", 3,
                            [x for r in d["krows"] for x in r],
                            ["^I32 cm-kw-count [^I32 ignored]", "^I32 cm-kw-at [^I32 ignored ^I32 row ^I32 field]"])
    return agl_kin, enc_kin


# Where the derivation departs from the Annex D table ON PURPOSE: the no-break
# space is a space (the footnote to D.2: code 160 is identical to 32, which is
# also what poppler and pdf.js do, and a word must split there), and 173 is the
# soft hyphen that Windows draws as a hyphen.
DELIBERATE = {("mac", 202), ("win", 160), ("win", 173)}


def check_ref():
    sys.path.insert(0, os.path.expanduser("~/.cache/emon/pylib"))
    from pdfminer.latin_enc import ENCODING
    d = build()
    encs = d["enc"]
    # pdfminer's rows: (name, std, mac, win, pdf)
    bad = 0
    for name, std, mac, win, _pdf in ENCODING:
        for label, code, e in (("standard", std, encs[0]), ("mac", mac, encs[2]), ("win", win, encs[1])):
            if code is None:
                continue
            if (label, code) in DELIBERATE:
                continue
            if e[code] != name:
                print(f"  DIFF {label} code {code}: spec table says {name}, derived {e[code]}")
                bad += 1
    # the other way: what we define that the spec table does not (information)
    extra = {"standard": 0, "mac": 0, "win": 0}
    ref = {"standard": {}, "mac": {}, "win": {}}
    for name, std, mac, win, _pdf in ENCODING:
        for label, code in (("standard", std), ("mac", mac), ("win", win)):
            if code is not None:
                ref[label][code] = name
    for label, e in (("standard", encs[0]), ("win", encs[1]), ("mac", encs[2])):
        for c in range(256):
            if e[c] and c not in ref[label]:
                extra[label] += 1
                print(f"  note {label} code {c}: derived {e[c]}, absent from the spec table")
    print(f"  {bad} disagreements; extras {extra}")
    return bad == 0


if __name__ == "__main__":
    a, e = render()
    outs = [(os.path.join(ROOT, "kin", "agl.kin"), a), (os.path.join(ROOT, "kin", "encodings.kin"), e)]
    if "--check" in sys.argv or "--check-ref" in sys.argv:
        stale = False
        for p, t in outs:
            if not os.path.exists(p) or open(p, encoding="utf-8").read() != t:
                print("  FAIL", os.path.relpath(p, ROOT), "is stale; run python3 kin/scripts/fontdata.py")
                stale = True
            else:
                print("  ok  ", os.path.relpath(p, ROOT), "is current")
        if "--check-ref" in sys.argv and not check_ref():
            stale = True
        sys.exit(1 if stale else 0)
    for p, t in outs:
        open(p, "w", encoding="utf-8").write(t)
        print("wrote", os.path.relpath(p, ROOT), len(t), "bytes")
