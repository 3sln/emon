#!/usr/bin/env python3
"""Write test/fonts/fonts.pdf and test/fonts/fonts.txt (DEVLOG P1.6).

    python3 kin/scripts/font-fixtures.py

A DEVELOPMENT TOOL, run by a person; no gate runs it (the committed files are
the truth, as with kin/scripts/pdf-fixtures.py). fonts.pdf is a hand-built file
of fonts chosen to reach every branch of `emon.pdffont`; fonts.txt says what
each must answer, and EVERY EXPECTATION IS KNOWN BY CONSTRUCTION: Unicode
values are written here as literals, widths are read from the Adobe AFM files
(a different parser from the generator's) and ZapfDingbats values from
zapfdingbats.txt. fonts.txt rows (tab separated):

  font  file  objnum  status  kind  ascent  descent  warn  vertical  standard14
  code  hex-code  cps  how  width          cps: hex code points joined by `+`, `-` none;
                                           width: 1/100000 em, `-` none
  len   hex-bytes  n                       how long the code at the bytes is
  cid   hex-code  cid
"""
import os
import re
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
OUT = os.path.join(ROOT, "test", "fonts")


def afm(name):
    """glyph name -> width, and code -> glyph name, from an AFM."""
    w, codes = {}, {}
    for line in open(os.path.join(OUT, name + ".afm"), encoding="latin-1"):
        m = re.match(r"C (-?\d+) ; WX (\d+) ; N (\S+) ;", line)
        if m:
            w[m.group(3)] = int(m.group(2))
            if int(m.group(1)) >= 0:
                codes[int(m.group(1))] = m.group(3)
    bbox = None
    asc = dsc = None
    for line in open(os.path.join(OUT, name + ".afm"), encoding="latin-1"):
        if line.startswith("FontBBox"):
            bbox = [int(x) for x in line.split()[1:]]
        if line.startswith("Ascender"):
            asc = int(line.split()[1])
        if line.startswith("Descender"):
            dsc = int(line.split()[1])
    return w, codes, (asc if asc is not None else bbox[3]), (dsc if dsc is not None else bbox[1])


def zapf_unicode():
    r = {}
    for line in open(os.path.join(OUT, "zapfdingbats.txt"), encoding="latin-1"):
        if line.startswith("#") or ";" not in line:
            continue
        n, u = line.strip().split(";")
        r[n] = int(u, 16)
    return r


class Pdf:
    def __init__(self):
        self.objs = []          # bytes bodies, object number = index + 1

    def add(self, body):
        if isinstance(body, str):
            body = body.encode("latin-1")
        self.objs.append(body)
        return len(self.objs)

    def stream(self, extra, data, flate=False):
        if flate:
            data = zlib.compress(data)
            extra = extra + " /Filter /FlateDecode"
        return self.add(f"<< {extra} /Length {len(data)} >>\nstream\n".encode("latin-1") + data + b"\nendstream")

    def write(self, path, root):
        out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offs = []
        for i, b in enumerate(self.objs):
            offs.append(len(out))
            out += f"{i + 1} 0 obj\n".encode() + b + b"\nendobj\n"
        x = len(out)
        out += f"xref\n0 {len(self.objs) + 1}\n".encode() + b"0000000000 65535 f \n"
        for o in offs:
            out += f"{o:010d} 00000 n \n".encode()
        out += f"trailer\n<< /Size {len(self.objs) + 1} /Root {root} 0 R >>\nstartxref\n{x}\n%%EOF\n".encode()
        open(path, "wb").write(out)


rows = []


def hx(cps):
    if not cps:
        return "-"
    return "+".join("%04X" % c for c in cps)


def wcu(w):
    return "-" if w is None else str(int(round(w * 100)))


def font(pdf, num, status, kind, asc, dsc, warn, vert, s14):
    rows.append(("font", "fonts/fonts.pdf", num, status, kind, asc, dsc, warn, vert, s14))


def code(c, cps, how, width):
    rows.append(("code", "%X" % c, hx(cps), how, wcu(width)))


def length(hexbytes, n):
    rows.append(("len", hexbytes, n))


def cid(c, v):
    rows.append(("cid", "%X" % c, v))


def fd(pdf, flags, asc, dsc, extra=""):
    return pdf.add(f"<< /Type /FontDescriptor /FontName /X /Flags {flags} /Ascent {asc} /Descent {dsc} "
                   f"/FontBBox [0 -200 1000 900] /ItalicAngle 0 /CapHeight 700 /StemV 80 {extra} >>")


def main():
    pdf = Pdf()
    HW, HC, HB = afm("Helvetica")[0], afm("Helvetica")[1], afm("Helvetica-Bold")[0]
    TW = afm("Times-Roman")[0]
    SW, SC, SA, SD = afm("Symbol")
    ZW, ZC, ZA, ZD = afm("ZapfDingbats")
    zu = zapf_unicode()
    fonts = []       # (name, objnum)

    # 1. Helvetica, WinAnsi, nothing else: standard-14 widths
    n = pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    fonts.append(("Helv", n))
    a, d = afm("Helvetica")[2:]
    font(pdf, n, 0, "simple", a, d, 16, 0, 4)
    for c, cps, nm in [(0x41, [0x41], "A"), (0x80, [0x20AC], "Euro"), (0x92, [0x2019], "quoteright"),
                       (0x7F, [0x2022], "bullet"), (0x81, [0x2022], "bullet"), (0x27, [0x27], "quotesingle"),
                       (0xE9, [0xE9], "eacute"), (0xAD, [0x2D], "hyphen"), (0xFB, [0xFB], "ucircumflex"),
                       (0xA0, [0x20], "space"), (0x60, [0x60], "grave"), (0x96, [0x2013], "endash"),
                       (0xDF, [0xDF], "germandbls"), (0xB5, [0xB5], "mu")]:
        code(c, cps, "encoding", HW[nm])
    code(0x00, [], "unknown", None)
    code(0x1F, [], "unknown", None)
    length("41", 1)

    # 2. Times-Roman, no /Encoding: StandardEncoding
    n = pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman >>")
    fonts.append(("Tim", n))
    a, d = afm("Times-Roman")[2:]
    font(pdf, n, 0, "simple", a, d, 16, 0, 8)
    for c, cps, nm in [(0x27, [0x2019], "quoteright"), (0x60, [0x2018], "quoteleft"), (0xAE, [0xFB01], "fi"),
                       (0xAF, [0xFB02], "fl"), (0xC1, [0x60], "grave"), (0xE1, [0xC6], "AE"),
                       (0xA4, [0x2044], "fraction"), (0xA9, [0x27], "quotesingle"), (0x20, [0x20], "space"),
                       (0xFB, [0xDF], "germandbls"), (0xD0, [0x2014], "emdash")]:
        code(c, cps, "encoding", TW[nm])
    code(0x80, [], "unknown", None)

    # 3. A custom font: Differences with every glyph-name form, /Widths with reals, /MissingWidth
    dsc = fd(pdf, 32, 900, -200, "/MissingWidth 300")
    widths = [277.83] + [500] * 63 + [612.5] + [500] * 5      # codes 1..70
    assert len(widths) == 70
    diffs = ("[1 /Euro /uni20AC 65 /f_f_i /A.alt /ffi /g123 /u1F600 /.notdef "
             "71 /uni00660066 /uniD800 /u110000 /a_b /ff /uni004100420043 /u0041 /uni004]")
    n = pdf.add(f"<< /Type /Font /Subtype /Type1 /BaseFont /ABCDEF+MyFont /FirstChar 1 /LastChar 70 "
                f"/Widths [{' '.join(str(w) for w in widths)}] /FontDescriptor {dsc} 0 R "
                f"/Encoding << /Type /Encoding /Differences {diffs} >> >>")
    fonts.append(("Cus", n))
    font(pdf, n, 0, "simple", 900, -200, 0, 0, -1)
    wd = lambda c: widths[c - 1] if 1 <= c <= 70 else 300
    for c, cps, how in [(1, [0x20AC], "glyphname"), (2, [0x20AC], "glyphname"), (65, [0x66, 0x66, 0x69], "glyphname"),
                        (66, [0x41], "glyphname"), (67, [0xFB03], "glyphname"), (68, [], "unknown"),
                        (69, [0x1F600], "glyphname"), (70, [], "unknown"),
                        (71, [0x66, 0x66], "glyphname"), (72, [], "unknown"), (73, [], "unknown"),
                        (74, [0x61, 0x62], "glyphname"), (75, [0xFB00], "glyphname"),
                        (76, [0x41, 0x42, 0x43], "glyphname"), (77, [0x41], "glyphname"), (78, [], "unknown"),
                        (3, [], "unknown"), (0x20, [0x20], "encoding"), (97, [0x61], "encoding"),
                        (0x41 + 40, [0x69], "encoding")]:
        code(c, cps, how, wd(c) if how != "x" else None)

    # 4. TrueType, no /Encoding, nonsymbolic: WinAnsi; /Widths for 3 codes, the AFM for the rest (ArialMT is Helvetica)
    dsc = fd(pdf, 32, 905, -212)
    n = pdf.add(f"<< /Type /Font /Subtype /TrueType /BaseFont /ArialMT /FirstChar 32 /LastChar 34 "
                f"/Widths [278 278 355] /FontDescriptor {dsc} 0 R >>")
    fonts.append(("Ari", n))
    font(pdf, n, 0, "simple", 905, -212, 0, 0, 4)
    code(0x93, [0x201C], "encoding", HW["quotedblleft"])
    code(0x22, [0x22], "encoding", 355)
    code(0x20, [0x20], "encoding", 278)
    code(0x41, [0x41], "encoding", HW["A"])

    # 5. Symbol and ZapfDingbats: their built-in encodings
    n = pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /Symbol >>")
    fonts.append(("Sym", n))
    font(pdf, n, 0, "simple", SA, SD, 16, 0, 12)
    sym_u = {"alpha": 0x3B1, "Alpha": 0x391, "bullet": 0x2022, "infinity": 0x221E, "universal": 0x2200,
             "space": 0x20, "summation": 0x2211, "Omega": 0x2126}
    for c, nm in SC.items():
        if nm in sym_u:
            code(c, [sym_u[nm]], "encoding", SW[nm])
    n = pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /ZapfDingbats >>")
    fonts.append(("Zap", n))
    font(pdf, n, 0, "simple", ZA, ZD, 16, 0, 13)
    for c in (0x21, 0x22, 0x6C, 0x75, 0xA1, 0xFE):
        nm = ZC[c]
        code(c, [zu[nm]], "encoding", ZW[nm])
    code(0x20, [0x20], "encoding", ZW["space"])

    # 6. ToUnicode on a simple font: bfchar, bfrange (incl. the array form), multi-code-point, a surrogate pair,
    #    an empty and a U+0000 and a U+FFFD destination (all ignored), a later definition overriding an earlier
    tu = b"""/CIDInit /ProcSet findresource begin
12 dict begin
begincmap
/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
/CMapName /Adobe-Identity-UCS def
/CMapType 2 def
1 begincodespacerange
<00> <FF>
endcodespacerange
3 beginbfchar
<41> <0058>
<45> <00660066>
<4A> <D83DDE00>
endbfchar
1 beginbfrange
<42> <44> <0061>
endbfrange
3 beginbfchar
<46> <>
<47> <0000>
<48> <FFFD>
endbfchar
1 beginbfrange
<4B> <4D> [<0041> <00420043> <0044>]
endbfrange
2 beginbfchar
<50> <0051>
<50> <0052>
endbfchar
endcmap
CMapName currentdict /CMap defineresource pop
end
end
"""
    tus = pdf.stream("", tu)
    n = pdf.add(f"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding /FirstChar 65 "
                f"/LastChar 72 /Widths [700 701 702 703 704 705 706 707] /ToUnicode {tus} 0 R >>")
    fonts.append(("TUs", n))
    font(pdf, n, 0, "simple", 718, -207, 0, 0, 4)
    for c, cps, how in [(0x41, [0x58], "tounicode"), (0x42, [0x61], "tounicode"), (0x43, [0x62], "tounicode"),
                        (0x44, [0x63], "tounicode"), (0x45, [0x66, 0x66], "tounicode"),
                        (0x46, [0x46], "encoding"), (0x47, [0x47], "encoding"), (0x48, [0x48], "encoding"),
                        (0x49, [0x49], "encoding"), (0x4A, [0x1F600], "tounicode"),
                        (0x4B, [0x41], "tounicode"), (0x4C, [0x42, 0x43], "tounicode"), (0x4D, [0x44], "tounicode"),
                        (0x50, [0x52], "tounicode")]:
        w = None
        if 0x41 <= c <= 0x48:
            w = 700 + (c - 0x41)
        elif c in (0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x50):
            w = HW[{0x49: "I", 0x4A: "J", 0x4B: "K", 0x4C: "L", 0x4D: "M", 0x50: "P"}[c]]
        code(c, cps, how, w)

    # 7. Type0, Identity-H, /W (both forms), /DW, ToUnicode (Flate)
    tu0 = b"""begincmap
1 begincodespacerange
<0000> <FFFF>
endcodespacerange
2 beginbfchar
<0001> <0041>
<0002> <00E9>
endbfchar
2 beginbfrange
<0010> <0012> <0061>
<0020> <0021> [<05D0> <05D1>]
endbfrange
endcmap
"""
    tus = pdf.stream("", tu0, flate=True)
    dsc = fd(pdf, 4, 880, -120)
    cidf = pdf.add(f"<< /Type /Font /Subtype /CIDFontType2 /BaseFont /ABCDEF+Noto /CIDSystemInfo << /Registry (Adobe) "
                   f"/Ordering (Identity) /Supplement 0 >> /DW 1000 /W [1 [500 600] 10 20 750 30 [333.5]] "
                   f"/FontDescriptor {dsc} 0 R >>")
    n = pdf.add(f"<< /Type /Font /Subtype /Type0 /BaseFont /ABCDEF+Noto /Encoding /Identity-H "
                f"/DescendantFonts [{cidf} 0 R] /ToUnicode {tus} 0 R >>")
    fonts.append(("T0H", n))
    font(pdf, n, 0, "composite", 880, -120, 0, 0, -1)
    for c, cps, how, w in [(1, [0x41], "tounicode", 500), (2, [0xE9], "tounicode", 600), (3, [], "unknown", 1000),
                           (0x10, [0x61], "tounicode", 750), (0x12, [0x63], "tounicode", 750),
                           (0x14, [], "unknown", 750), (0x15, [], "unknown", 1000),
                           (0x20, [0x5D0], "tounicode", 1000), (0x21, [0x5D1], "tounicode", 1000),
                           (0x1E, [], "unknown", 333.5), (0xFFFF, [], "unknown", 1000)]:
        code(c, cps, how, w)
    length("0001", 2)
    length("00", 2)
    cid(2, 2)
    cid(0x1234, 0x1234)

    # 8. Type0, Identity-V, /DW2, nothing else
    cidf = pdf.add("<< /Type /Font /Subtype /CIDFontType0 /BaseFont /X /CIDSystemInfo << /Registry (Adobe) "
                   "/Ordering (Japan1) /Supplement 2 >> /DW2 [900 -1100] >>")
    n = pdf.add(f"<< /Type /Font /Subtype /Type0 /BaseFont /X /Encoding /Identity-V /DescendantFonts [{cidf} 0 R] >>")
    fonts.append(("T0V", n))
    font(pdf, n, 0, "composite", 0, 0, 0, 1, -1)
    code(0x41, [], "unknown", 1000)
    rows.append(("dw2", n, 90000, -110000))

    # 9. Type0 with an embedded encoding CMap: mixed 1- and 2-byte codespace, cidrange, cidchar, /WMode
    cm = b"""/CIDInit /ProcSet findresource begin
12 dict begin
begincmap
/CMapName /Test-H def
/WMode 1 def
2 begincodespacerange
<00> <80>
<8140> <9FFC>
endcodespacerange
1 begincidrange
<20> <7E> 1
endcidrange
1 begincidchar
<8140> 500
endcidchar
endcmap
end
end
"""
    cms = pdf.stream("/Type /CMap /CMapName /Test-H", cm)
    cidf = pdf.add("<< /Type /Font /Subtype /CIDFontType0 /BaseFont /X /CIDSystemInfo << /Registry (Adobe) "
                   "/Ordering (Japan1) /Supplement 2 >> /DW 900 /W [34 [650]] >>")
    n = pdf.add(f"<< /Type /Font /Subtype /Type0 /BaseFont /X /Encoding {cms} 0 R /DescendantFonts [{cidf} 0 R] >>")
    fonts.append(("T0C", n))
    font(pdf, n, 0, "composite", 0, 0, 0, 1, -1)
    for c, cid_ in [(0x41, 0x41 - 0x20 + 1), (0x20, 1), (0x7E, 0x7E - 0x20 + 1), (0x7F, 0), (0x8140, 500), (0x8141, 0)]:
        cid(c, cid_)
    code(0x41, [], "unknown", 650)           # cid 34 has /W [34 [650]]
    code(0x20, [], "unknown", 900)
    length("41", 1)
    length("8140", 2)
    length("81", 2)
    length("A0", 1)
    length("7F81", 1)
    length("9FFC", 2)

    # 10. Predefined Unicode CMaps: the code is the Unicode value
    cidf = pdf.add("<< /Type /Font /Subtype /CIDFontType0 /BaseFont /Y /CIDSystemInfo << /Registry (Adobe) "
                   "/Ordering (Japan1) /Supplement 2 >> >>")
    n = pdf.add(f"<< /Type /Font /Subtype /Type0 /BaseFont /Y /Encoding /UniJIS-UCS2-H /DescendantFonts [{cidf} 0 R] >>")
    fonts.append(("UCS", n))
    font(pdf, n, 0, "composite", 0, 0, 1, 0, -1)
    code(0x3042, [0x3042], "encoding", 1000)
    code(0x41, [0x41], "encoding", 1000)
    code(0xD800, [], "unknown", 1000)
    length("3042", 2)
    n = pdf.add(f"<< /Type /Font /Subtype /Type0 /BaseFont /Y /Encoding /UniJIS-UTF16-V /DescendantFonts [{cidf} 0 R] >>")
    fonts.append(("U16", n))
    font(pdf, n, 0, "composite", 0, 0, 1, 1, -1)
    code(0x3042, [0x3042], "encoding", 1000)
    length("D83DDE00", 4)
    length("0041", 2)
    length("E000", 2)
    # a predefined CID CMap we cannot read
    n = pdf.add(f"<< /Type /Font /Subtype /Type0 /BaseFont /Z /Encoding /90ms-RKSJ-H /DescendantFonts [{cidf} 0 R] >>")
    fonts.append(("RKS", n))
    font(pdf, n, 0, "composite", 0, 0, 1, 0, -1)
    code(0x82A0, [], "unknown", 1000)

    # 11. Type 3: FontMatrix scales /Widths; Differences name the glyphs
    n = pdf.add("<< /Type /Font /Subtype /Type3 /FontBBox [0 0 100 100] /FontMatrix [0.01 0 0 0.01 0 0] "
                "/CharProcs << >> /FirstChar 97 /LastChar 98 /Widths [50 60.5] "
                "/Encoding << /Type /Encoding /Differences [97 /a /b] >> >>")
    fonts.append(("Typ3", n))
    # no descriptor: the cell is /FontBBox times the FontMatrix (100 * 0.01 em high), D101
    font(pdf, n, 0, "simple", 1000, 0, 0, 0, -1)
    code(97, [0x61], "glyphname", 500)
    code(98, [0x62], "glyphname", 605)
    code(99, [], "unknown", None)

    # 12. A standard font under another name, with a subset tag and /Widths for two codes
    n = pdf.add("<< /Type /Font /Subtype /TrueType /BaseFont /ABCDEF+Arial,Bold /FirstChar 65 /LastChar 66 "
                "/Widths [700 710] >>")
    fonts.append(("ArB", n))
    a, d = afm("Helvetica-Bold")[2:]
    font(pdf, n, 0, "simple", a, d, 0, 0, 5)
    code(65, [0x41], "encoding", 700)
    code(66, [0x42], "encoding", 710)
    code(67, [0x43], "encoding", HB["C"])
    n = pdf.add("<< /Type /Font /Subtype /TrueType /BaseFont /TimesNewRomanPS-BoldItalicMT /Encoding /WinAnsiEncoding >>")
    fonts.append(("TNR", n))
    font(pdf, n, 0, "simple", afm("Times-BoldItalic")[2], afm("Times-BoldItalic")[3], 16, 0, 11)
    code(0x41, [0x41], "encoding", afm("Times-BoldItalic")[0]["A"])

    # 13. A symbolic TrueType font with no /Encoding: a guess, and it says so
    dsc = fd(pdf, 4, 800, -200)
    n = pdf.add(f"<< /Type /Font /Subtype /TrueType /BaseFont /Wingdings-Regular /FontDescriptor {dsc} 0 R >>")
    fonts.append(("Wng", n))
    font(pdf, n, 0, "simple", 800, -200, 16 + 256, 0, -1)
    code(0x41, [0x41], "unknown", None)
    code(0x20, [0x20], "unknown", None)

    # 14. No /Subtype at all (read as Type1), a MacRoman font, a base encoding in a dictionary, MacExpert
    n = pdf.add("<< /Type /Font /BaseFont /Helvetica >>")
    fonts.append(("NoS", n))
    a, d = afm("Helvetica")[2:]
    font(pdf, n, 0, "simple", a, d, 16, 0, 4)
    code(0x41, [0x41], "encoding", HW["A"])
    code(0xC1, [0x60], "encoding", HW["grave"])
    n = pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /MacRomanEncoding >>")
    fonts.append(("Mac", n))
    a, d = afm("Times-Roman")[2:]
    font(pdf, n, 0, "simple", a, d, 16, 0, 8)
    for c, cps, nm in [(0x8E, [0xE9], "eacute"), (0xCA, [0x20], "space"), (0xDB, [0xA4], "currency"),
                       (0xA5, [0x2022], "bullet"), (0xD2, [0x201C], "quotedblleft"), (0xDE, [0xFB01], "fi"),
                       (0xC6, [0x2206], "Delta"), (0xBD, [0x3A9], "Omegagreek")]:
        code(c, cps, "encoding", TW.get(nm))
    n = pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Type /Encoding "
                "/BaseEncoding /MacRomanEncoding /Differences [142 /Scaron] >> >>")
    fonts.append(("MaD", n))
    a, d = afm("Helvetica")[2:]
    font(pdf, n, 0, "simple", a, d, 16, 0, 4)
    code(0x8E, [0x160], "glyphname", HW["Scaron"])
    code(0x8F, [0xE8], "encoding", HW["egrave"])
    n = pdf.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /MacExpertEncoding >>")
    fonts.append(("MEx", n))
    a, d = afm("Helvetica")[2:]
    font(pdf, n, 0, "simple", a, d, 16 + 8, 0, 4)
    code(0x41, [0x41], "unknown", HW["A"])

    # 15. Not a font at all
    n = pdf.add("7")
    rows.append(("font", "fonts/fonts.pdf", n, 1, "none", 0, 0, 0, 0, -1))
    # a Type0 whose descendant is missing
    n = pdf.add("<< /Type /Font /Subtype /Type0 /BaseFont /Q /Encoding /Identity-H >>")
    fonts.append(("Bad", n))
    font(pdf, n, 0, "composite", 0, 0, 32, 0, -1)
    code(1, [], "unknown", 1000)

    # the page, for readers that want one
    res = " ".join(f"/{nm} {n} 0 R" for nm, n in fonts)
    content = "BT /Helv 12 Tf 72 720 Td (Hello) Tj ET\n".encode()
    cs = pdf.stream("", content)
    page_n = len(pdf.objs) + 1
    page = pdf.add(f"<< /Type /Page /Parent {page_n + 1} 0 R /MediaBox [0 0 612 792] "
                   f"/Resources << /Font << {res} >> >> /Contents {cs} 0 R >>")
    pages = pdf.add(f"<< /Type /Pages /Kids [{page} 0 R] /Count 1 >>")
    cat = pdf.add(f"<< /Type /Catalog /Pages {pages} 0 R >>")
    pdf.write(os.path.join(OUT, "fonts.pdf"), cat)

    with open(os.path.join(OUT, "fonts.txt"), "w") as f:
        f.write("# Written by kin/scripts/font-fixtures.py; read by kin/pdffonttest.kin (DEVLOG P1.6).\n")
        f.write("# font: file, object, load status, kind, ascent, descent, warning bits, vertical, standard font\n")
        f.write("# code: hex code, code points (hex, `+` joined, `-` none), how, width in 1/100000 em (`-` none)\n")
        f.write("# len: hex bytes, length of the code at their start;  cid: hex code, cid;  dw2: font, v, w1\n")
        for r in rows:
            f.write("\t".join(str(x) for x in r) + "\n")
    print("wrote", len(pdf.objs), "objects,", len(rows), "rows")


if __name__ == "__main__":
    main()
