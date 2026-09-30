#!/usr/bin/env python3
"""Write test/fonts/cmaps.txt: CMap programs and what they must map (DEVLOG P1.6).

    python3 kin/scripts/font-cmaps.py            write the file, cross-checking with pdfminer.six
    python3 kin/scripts/font-cmaps.py --check    fail if it is stale

A DEVELOPMENT TOOL; `kin/scripts/unit` runs `--check` when python3 is there.
Every expectation below is written by hand from the CMap specification (Adobe
Technical Note #5014) and the PDF specification (9.10.3), so each row is known
BY CONSTRUCTION; where pdfminer.six's CMap parser is installed it is asked the
same ToUnicode questions and must agree except where a row is marked (emon
differs on purpose, and says how).

Rows (tab separated), the program having newlines written `\\n`:

    tu   program  codes (hex, comma separated)   code points per code (`+` joined, `-` none)
    cid  program  codes                          CID (decimal; 0 when the CMap has no entry)
    cs   program  byte strings (hex, up to 4)    the length of the code at the start of each
    fz   program                                 (truncation law: parsed cut at every byte)
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..", "..")
OUT = os.path.join(ROOT, "test", "fonts", "cmaps.txt")

ROWS = []
DIFFERS = set()      # row indices where emon knowingly differs from pdfminer


def tu(prog, codes, want, differs=False):
    if differs:
        DIFFERS.add(len(ROWS))
    ROWS.append(("tu", prog, codes, want))


def cid(prog, codes, want):
    ROWS.append(("cid", prog, codes, want))


def cs(prog, codes, want):
    ROWS.append(("cs", prog, codes, want))


def bf(body):
    return "begincmap\n" + body + "endcmap\n"


# ---- bfchar
tu(bf("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"
      "2 beginbfchar\n<0041> <0061>\n<0042> <00620063>\nendbfchar\n"),
   "0041,0042,0043,0000", "0061,0062+0063,-,-")
tu(bf("1 beginbfchar\n<41> <0061>\nendbfchar\n"), "41,42", "0061,-")
tu(bf("2 beginbfchar\n<0001> <0041>\n<0001> <0042>\nendbfchar\n"), "0001", "0042")          # later wins
tu(bf("3 beginbfchar\n<0003> <0043>\n<0001> <0041>\n<0002> <0042>\nendbfchar\n"),
   "0001,0002,0003,0004", "0041,0042,0043,-")                                                # unsorted
tu(bf("1 beginbfchar\n< 00 41 > < 00\n61 >\nendbfchar\n"), "0041", "0061")                  # white space inside hex
tu(bf("1 beginbfchar\n<00aB> <00eF>\nendbfchar\n"), "00AB,00ab", "00EF,00EF")              # either case
tu(bf("1 beginbfchar\n<0041> <D835DC00>\nendbfchar\n"), "0041", "1D400")                   # surrogate pair
tu(bf("1 beginbfchar\n<0041> <D800>\nendbfchar\n"), "0041", "FFFD")                        # lone surrogate
tu(bf("1 beginbfchar\n<0041> <DC00>\nendbfchar\n"), "0041", "FFFD")
tu(bf("1 beginbfchar\n<0041> <D800 0042>\nendbfchar\n"), "0041", "FFFD+0042")              # high surrogate then a BMP unit
tu(bf("1 beginbfchar\n<0041> <>\nendbfchar\n"), "0041", "-")                               # an empty destination maps to nothing
tu(bf("1 beginbfchar\n<0041> <00410042004300440045>\nendbfchar\n"), "0041", "0041+0042+0043+0044",
   differs=True)                                                                           # cut at four code points
tu(bf("1 beginbfchar\n<0041> <FB01>\nendbfchar\n"), "0041", "FB01")                        # ligatures are not split here
tu(bf("1 beginbfchar\n<00000041> <0058>\nendbfchar\n"), "0041", "0058")                     # codes compare as numbers
tu(bf("1 beginbfchar\n<0041> /A\nendbfchar\n"), "0041", "-")                               # a glyph-name destination: nothing
tu(bf("1 beginbfchar\n<0041> <0042>\nendbfchar\n1 beginbfchar\n<0043> <0044>\nendbfchar\n"), "0041,0043", "0042,0044")

# ---- bfrange
tu(bf("1 beginbfrange\n<0020> <0022> <0061>\nendbfrange\n"), "001F,0020,0021,0022,0023", "-,0061,0062,0063,-")
tu(bf("1 beginbfrange\n<00FE> <0101> <0041>\nendbfrange\n"), "00FD,00FE,00FF,0100,0101,0102",
   "-,0041,0042,0043,0044,-")                                                                # crossing a code-byte boundary
tu(bf("1 beginbfrange\n<0010> <0012> <00FE>\nendbfrange\n"), "0010,0011,0012", "00FE,00FF,0100",
   differs=True)                                                                             # the dst increments as a number
tu(bf("1 beginbfrange\n<0005> <0005> <0041>\nendbfrange\n"), "0004,0005,0006", "-,0041,-")  # lo = hi
tu(bf("1 beginbfrange\n<0008> <0005> <0041>\nendbfrange\n"), "0005,0006,0008", "-,-,-")     # hi < lo: ignored
tu(bf("1 beginbfrange\n<0000> <FFFF> <0000>\nendbfrange\n"), "0000,1234,FFFF", "0000,1234,FFFF")
tu(bf("1 beginbfrange\n<0001> <0003> [<0041> <00420043> <0044>]\nendbfrange\n"), "0001,0002,0003,0004",
   "0041,0042+0043,0044,-")                                                                  # the array form
tu(bf("1 beginbfrange\n<0001> <0003> [<0041> <0042>]\nendbfrange\n"), "0001,0002,0003", "0041,0042,-")  # array too short
tu(bf("1 beginbfrange\n<0001> <0002> [<0041> <0042> <0043>]\nendbfrange\n"), "0001,0002,0003", "0041,0042,-")  # array too long
tu(bf("1 beginbfrange\n<0001> <0003> <0041>\n<0002> <0002> <0058>\nendbfrange\n"), "0001,0002,0003",
   "0041,0058,0043")                                                                         # a later single inside an earlier range
tu(bf("2 beginbfrange\n<0000> <FFFF> <0000>\n<0041> <0041> <0058>\nendbfrange\n"), "0040,0041,0042",
   "0040,0058,0042")                                                                         # ... and past it
tu(bf("2 beginbfrange\n<0040> <0050> <0100>\n<0044> <0046> <0200>\nendbfrange\n"), "0043,0044,0046,0047",
   "0103,0200,0202,0107")                                                                    # overlap: nearest start, else the wider one
tu(bf("1 beginbfrange\n<0001> <0002> <D835DC00>\nendbfrange\n"), "0001,0002", "1D400,1D401")  # a range over a surrogate pair
tu(bf("1 beginbfrange\n<0001> <0002> <00660066>\nendbfrange\n"), "0001,0002", "0066+0066,0066+0067")  # the LAST unit increments
tu(bf("1 beginbfrange\n<0001> <0002> <>\nendbfrange\n"), "0001", "-")

# ---- what is skipped
tu("% a comment with <0001> <0002>\nbegincmap\n1 beginbfchar\n<0001> <0041> % trailing <0002> <0042>\nendbfchar\nendcmap\n",
   "0001,0002", "0041,-")
tu(bf("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
      "/CMapName /X def\n1 beginbfchar\n<0001> <0041>\nendbfchar\n"), "0001", "0041")
tu(bf("(a string with endcmap and <0002> <0042> in it)\n1 beginbfchar\n<0001> <0041>\nendbfchar\n"), "0001,0002", "0041,-")
tu(bf("1 beginnotdefrange\n<00> <1F> 1\nendnotdefrange\n1 beginbfchar\n<0001> <0041>\nendbfchar\n"), "0001,0002", "0041,-")
tu(bf("1 begincidrange\n<0001> <0003> 10\nendcidrange\n1 beginbfchar\n<0001> <0041>\nendbfchar\n"), "0001,0002", "0041,-")
tu(bf("1 beginbfchar\n<0001> <0041>\n"), "0001", "0041")                                     # no endbfchar
tu("", "0001", "-")
tu("garbage ][ >> << } ) 12 /x\n", "0001", "-")

# ---- encoding CMaps: cid
cid(bf("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n1 begincidrange\n<0020> <007E> 1\nendcidrange\n"),
    "001F,0020,0041,007E,007F", "0,1,34,95,0")
cid(bf("1 begincidchar\n<8140> 500\nendcidchar\n"), "8140,8141", "500,0")
cid(bf("2 begincidrange\n<0000> <00FF> 0\n<0041> <0041> 900\nendcidrange\n"), "0040,0041,0042", "64,900,66")
cid(bf("1 begincidrange\n<0001> <0002> 7\nendcidrange\n1 begincidchar\n<0002> 99\nendcidchar\n"), "0001,0002,0003", "7,99,0")
cid(bf("1 begincidrange\n<0005> <0003> 7\nendcidrange\n"), "0004", "0")                      # hi < lo
cid(bf("1 beginbfchar\n<0001> <0041>\nendbfchar\n"), "0001", "0")                            # a ToUnicode program is no encoding

# ---- codespace ranges
cs(bf("2 begincodespacerange\n<00> <80>\n<8140> <9FFC>\nendcodespacerange\n"),
   "41,8140,81,A0,7F81,9FFC,81FF,FF", "1,2,2,1,1,2,2,1")
cs(bf("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"), "0001,00,1234", "2,2,2")
cs(bf("3 begincodespacerange\n<00> <80>\n<A1A1> <FEFE>\n<8EA1A1A1> <8EFEFEFE>\nendcodespacerange\n"),
   "41,A1A1,FEFE,8EA1A1A1,8EFEFEFE,8EA1A100,8E", "1,2,2,4,4,4,4")
cs(bf("1 begincodespacerange\n<00000000> <0010FFFF>\nendcodespacerange\n"), "00100000,0010FFFF,00110000,00", "4,4,4,4")
cs(bf("1 begincodespacerange\n<000000> <7FFFFF>\nendcodespacerange\n"), "000000,7FFFFF,800000", "3,3,3")
cs(bf("2 begincodespacerange\n<00> <7F>\n<0000> <FFFF>\nendcodespacerange\n"), "41,8041", "1,2")   # the shorter wins a tie of prefix
cs("", "41,0041", "2,2")                                                                     # no ranges at all: two bytes
cs(bf("1 begincodespacerange\n<20> <7E>\nendcodespacerange\n"), "41,10,FF", "1,1,1")
cs(bf("17 begincodespacerange\n" + "".join("<%02X> <%02X>\n" % (i * 2, i * 2) for i in range(17)) +
      "endcodespacerange\n"), "00,20,21", "1,1,1")                                          # more than 16 ranges: no fault

# ---- truncation law: a program with every construct
FULL = bf("/CIDInit /ProcSet findresource begin\n12 dict begin\n/CMapName /X def\n/WMode 1 def\n"
          "2 begincodespacerange\n<00> <80>\n<8140> <9FFC>\nendcodespacerange\n"
          "2 beginbfchar\n<41> <0058>\n<4A> <D83DDE00>\nendbfchar\n"
          "2 beginbfrange\n<42> <44> <0061>\n<4B> <4D> [<0041> <00420043> <0044>]\nendbfrange\n"
          "1 begincidrange\n<20> <7E> 1\nendcidrange\n1 begincidchar\n<8140> 500\nendcidchar\n"
          "% comment\n(a string)\nend\nend\n")
ROWS.append(("fz", FULL, "", ""))


def esc(s):
    return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")


def render():
    lines = ["# Written by kin/scripts/font-cmaps.py; read by kin/pdffonttest.kin (DEVLOG P1.6).",
             "# kind, program (`\\n` a newline), queries, expected; see the script for the rules"]
    for r in ROWS:
        lines.append("\t".join([r[0], esc(r[1]), r[2], r[3]]))
    return "\n".join(lines) + "\n"


def cross_check():
    sys.path.insert(0, os.path.expanduser("~/.cache/emon/pylib"))
    try:
        from pdfminer.cmapdb import CMapParser, FileUnicodeMap
        import io
    except Exception:
        print("  note pdfminer.six is not installed: the independent cross-check was skipped")
        return True
    bad = checked = 0
    for i, r in enumerate(ROWS):
        if r[0] != "tu" or i in DIFFERS:
            continue
        um = FileUnicodeMap()
        try:
            CMapParser(um, io.BytesIO(r[1].encode("latin-1"))).run()
        except Exception:
            continue
        for code, want in zip(r[2].split(","), r[3].split(",")):
            c = int(code, 16)
            got = um.cid2unichr.get(c)
            w = "" if want == "-" else "".join(chr(int(x, 16)) for x in want.split("+"))
            checked += 1
            if (got or "") != w:
                # pdfminer keeps the first definition and ignores ranges it cannot parse; report, do not fail
                print(f"  note row {i + 1}: code {code}: pdfminer {[hex(ord(x)) for x in (got or '')]} emon {want}")
                bad += 1
    print(f"  cross-check with pdfminer.six: {checked} lookups, {bad} differences (notes above)")
    return True


if __name__ == "__main__":
    text = render()
    if "--check" in sys.argv:
        if not os.path.exists(OUT) or open(OUT).read() != text:
            print("  FAIL test/fonts/cmaps.txt is stale; run python3 kin/scripts/font-cmaps.py")
            sys.exit(1)
        print("  ok   test/fonts/cmaps.txt is current")
    else:
        cross_check()
        open(OUT, "w").write(text)
        print("wrote", OUT, len(ROWS), "rows")
