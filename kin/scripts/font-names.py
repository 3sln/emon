#!/usr/bin/env python3
"""Write test/fonts/names.txt: glyph names -> code points, and BaseFont names
-> standard font (DEVLOG P1.6).

    python3 kin/scripts/font-names.py             write the file (and cross-check)
    python3 kin/scripts/font-names.py --check     fail if it is stale

A DEVELOPMENT TOOL; `kin/scripts/unit` runs `--check` when python3 is there.
The expectations come from an implementation of the Adobe Glyph List
specification written HERE, from glyphlist.txt and zapfdingbats.txt (emon's
kin code shares nothing with it), and every name whose answer is one code
point is also put to pdfminer.six's `name2unicode` when that is installed
(`pip install --target ~/.cache/emon/pylib pdfminer.six`): the two must agree,
except where emon is knowingly more lenient (lower-case hex in uniXXXX, marked
below). Rows, tab separated:

    n   /glyph-name   code points (hex, `+` joined; `-` none)
    s   /BaseFont     the standard font it means (0..13, -1 none)
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
OUT = os.path.join(ROOT, "test", "fonts", "names.txt")
SRC = os.path.join(ROOT, "test", "fonts")


def load(name):
    r = {}
    for line in open(os.path.join(SRC, name), encoding="latin-1"):
        if line.startswith("#") or ";" not in line:
            continue
        n, u = line.strip().split(";")
        r[n] = [int(x, 16) for x in u.split()]
    return r


AGL = load("glyphlist.txt")
ZAPF = load("zapfdingbats.txt")
AGL.update(ZAPF)

HEX = set("0123456789ABCDEFabcdef")


def agl_map(name, lenient=True, zapf=False):
    """The AGL specification, section 'Mapping glyph names to Unicode'."""
    base = name.split(".")[0]
    if base == "":
        return []
    out = []
    for comp in base.split("_"):
        if comp in ZAPF and not zapf:
            continue
        if comp in AGL:
            out += AGL[comp]
        elif comp.startswith("uni") and len(comp) >= 7 and (len(comp) - 3) % 4 == 0 \
                and all(c in HEX for c in comp[3:]) and (lenient or comp[3:] == comp[3:].upper()):
            for i in range(3, len(comp), 4):
                v = int(comp[i:i + 4], 16)
                if not 0xD800 <= v <= 0xDFFF:
                    out.append(v)
        elif comp.startswith("u") and 5 <= len(comp) <= 7 and all(c in HEX for c in comp[1:]) \
                and (lenient or comp[1:] == comp[1:].upper()):
            v = int(comp[1:], 16)
            if v <= 0x10FFFF and not 0xD800 <= v <= 0xDFFF:
                out.append(v)
    return out


EDGE = ["A", "A.alt", ".notdef", ".foo", "notdef", "f_f_i", "f_f", "_", "a__b", "a_", "_a", "uni0041",
        "uni00410042", "uni004", "uniD800", "uniD7FF", "uniE000", "uni0041004", "u0041", "u00041", "u000041",
        "u0000041", "u10FFFF", "u110000", "u1F600", "uni20AC.alt", "Euro.alt", "uni0041_uni0042",
        "ffi_u0041.alt", "Alpha", "g123", "cid123", "glyph1", "afii10017", "fi", "space", "nbspace",
        "uni00e9", "a.b.c", "uniFFFE", "uni0041D800", "u0041_uni0042_ff", "A_", "zero.oldstyle", "f_f_ixyz",
        "uni", "u", "uni00", "u00", "a" * 100, "lam_alef", "Lcommaaccent", "Delta", "Omega", "mu", "hyphen",
        "onesuperior", "nonbreakingspace", "a1", "a206", "g1", "T_h", "a1_a2", "f_a1"]

S14 = [("Helvetica", 4), ("Helvetica-Bold", 5), ("Helvetica-Oblique", 6), ("Helvetica-BoldOblique", 7),
       ("Arial", 4), ("Arial-BoldMT", 5), ("Arial,Bold", 5), ("ArialMT", 4), ("Arial-ItalicMT", 6),
       ("Arial-BoldItalicMT", 7), ("ABCDEF+Arial", 4), ("Times-Roman", 8), ("Times-Bold", 9),
       ("Times-Italic", 10), ("Times-BoldItalic", 11), ("TimesNewRoman", 8), ("TimesNewRomanPSMT", 8),
       ("TimesNewRomanPS-BoldMT", 9), ("TimesNewRoman,Italic", 10), ("TimesNewRoman,BoldItalic", 11),
       ("Courier", 0), ("Courier-Bold", 1), ("Courier-Oblique", 2), ("Courier-BoldOblique", 3),
       ("CourierNew", 0), ("CourierNewPSMT", 0), ("CourierNewPS-BoldMT", 1), ("CourierNew,BoldItalic", 3),
       ("Symbol", 12), ("SymbolMT", 12), ("ZapfDingbats", 13), ("Times", 8), ("ZZZZZZ+Times-Bold", 9),
       ("Verdana", -1), ("Arial-Black", -1), ("Helvetica-Light", -1), ("Georgia", -1), ("ArialNarrow", -1),
       ("ArialNarrow-Bold", -1), ("ABCDE+Arial", -1), ("abcdef+Arial", -1), ("Helvetica Bold", 5)]


def esc(name):
    """A name object as written: `#` escapes for what a name cannot hold."""
    out = ""
    for ch in name:
        if ch in " ,#()<>[]{}/%" and ch != ",":
            out += "#%02X" % ord(ch)
        else:
            out += ch
    return out


def fmt(cps):
    return "-" if not cps else "+".join("%04X" % c for c in cps)


def render():
    lines = ["# Written by kin/scripts/font-names.py; read by kin/pdffonttest.kin (DEVLOG P1.6).",
             "# n: /glyph name (AGL specification), code points;  s: /BaseFont, standard font or -1"]
    for name in sorted(AGL, key=lambda s: s.encode()):
        if name in ZAPF:
            lines.append(f"z\t/{name}\t{fmt(AGL[name])}")       # ZapfDingbats' own
            lines.append(f"n\t/{name}\t-")                     # in any other font: nothing
        else:
            lines.append(f"n\t/{name}\t{fmt(AGL[name])}")
    for name in EDGE:
        lines.append(f"n\t/{esc(name)}\t{fmt(agl_map(name))}")
    lines.append("n\t/a1\t-")
    lines.append("z\t/a1\t2701")
    lines.append("z\t/a1_a2.x\t2701+2702")
    lines.append("n\t/A#2Ealt\t0041")
    lines.append("n\t/f#5Ff#5Fi\t0066+0066+0069")
    for name, idx in S14:
        lines.append(f"s\t/{esc(name)}\t{idx}")
    return "\n".join(lines) + "\n"


def cross_check():
    lib = os.path.expanduser("~/.cache/emon/pylib")
    sys.path.insert(0, lib)
    try:
        from pdfminer.encodingdb import name2unicode
    except Exception:
        print("  note pdfminer.six is not installed: the independent cross-check was skipped")
        return True
    bad = 0
    names = [n for n in AGL if n not in ZAPF] + EDGE
    for name in names:
        mine = agl_map(name, lenient=False, zapf=False)
        try:
            theirs = name2unicode(name)
        except Exception:
            theirs = None
        if theirs is None:
            continue              # pdfminer says nothing (or raises): not a disagreement
        if len(mine) == 1 and ord(theirs) == mine[0]:
            continue
        if len(theirs) == len(mine) and [ord(c) for c in theirs] == mine:
            continue
        if not mine:
            continue
        print(f"  DIFF {name!r}: spec here {fmt(mine)}, pdfminer {[hex(ord(c)) for c in theirs]}")
        bad += 1
    print(f"  cross-check with pdfminer.six: {len(names)} names, {bad} disagreements")
    return bad == 0


if __name__ == "__main__":
    text = render()
    if "--check" in sys.argv:
        if not os.path.exists(OUT) or open(OUT).read() != text:
            print("  FAIL test/fonts/names.txt is stale; run python3 kin/scripts/font-names.py")
            sys.exit(1)
        print("  ok   test/fonts/names.txt is current")
    else:
        ok = cross_check()
        open(OUT, "w").write(text)
        print("wrote", OUT, len(text), "bytes")
        sys.exit(0 if ok else 1)
