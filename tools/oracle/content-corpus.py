#!/usr/bin/env python3
"""Compare emon's content-stream interpreter with pdfminer.six on real PDFs (DEVLOG P1.7).

    python3 tools/oracle/content-corpus.py                   the committed sample + 100 more
    python3 tools/oracle/content-corpus.py --extra 300       more files
    python3 tools/oracle/content-corpus.py --files a.pdf b.pdf
    python3 tools/oracle/content-corpus.py --write           also (re)write test/content/real.txt

AN ORACLE, NOT A GATE: pdfminer.six is a reference implementation and lives only here
(installed by hand into ~/.cache/emon/pylib, like the font oracle's: `pip install
--target ~/.cache/emon/pylib pdfminer.six pypdf`). Nothing emon runs depends on it.
Every other emon script is babashka (kin/scripts/*); this one is Python because the
reference it drives is.

WHAT IS COMPARED, per page, for the first PAGES pages of each file:

  glyph stream   emon's glyphs (the `gl` arena, in content-stream order) against pdfminer's
                 LTChars with its layout analysis OFF (also in content-stream order), both
                 NFKC-normalised with white space removed, so `fi` and U+FB01 agree and a
                 space glyph does not count. Reported as the share of pages whose streams are
                 identical and the share of characters matched (difflib, autojunk off).
  words          emon's words against pdfminer's text lines split at white space (its
                 default LAParams): multiset precision, recall and F1.
  lines          emon's lines against pdfminer's LTTextLines: the same.
  geometry       for pages whose streams are identical and whose crop box is the media box,
                 each glyph's box against the LTChar's, in points: the share within 0.05 pt.

`--write` keeps a RATCHET, test/content/real.txt: per page, the digest of emon's glyph stream (the
one `emon.contentrows` computes) and its glyph count, for the pages where the two AGREE, for
kin/pdfcontenttest.kin to hold.
"""
import difflib
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

PAGES = 3
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


def norm(s):
    # U+0000 is what pdfminer writes for a glyph its ToUnicode gives no value; emon's is U+FFFD (none)
    s = unicodedata.normalize("NFKC", s.replace("\x00", "\ufffd"))
    return "".join(ch for ch in s if not ch.isspace())


def nk(s):
    return unicodedata.normalize("NFKC", s.replace("\x00", "\ufffd"))


def emon_rows(paths):
    p = subprocess.run([os.path.join(ROOT, "kin", "scripts", "content-sweep"), "all"],
                       input="\n".join(paths) + "\n", capture_output=True, text=True, cwd=ROOT)
    if p.returncode != 0:
        print(p.stderr[-2000:])
    per = {}
    cur = {"G": [], "T": [], "W": []}
    for line in p.stdout.split("\n"):
        if not line:
            continue
        f = line.split("\t")
        if f[0] in ("G", "T", "W"):
            cur[f[0]].append(f[1:])
        elif f[0] == "F":
            per[f[1]] = (f[2:], cur)
            cur = {"G": [], "T": [], "W": []}
    return per


def pdfminer_pages(path, npages):
    """[(chars, lines, width, height)] for the first npages pages: chars in content-stream order."""
    from pdfminer.pdfparser import PDFParser
    from pdfminer.pdfdocument import PDFDocument
    from pdfminer.pdfpage import PDFPage
    from pdfminer.pdfinterp import PDFResourceManager, PDFPageInterpreter
    from pdfminer.converter import PDFPageAggregator
    from pdfminer.layout import LAParams, LTChar, LTTextLine, LTTextContainer, LTFigure, LTAnno

    def chars_of(layout, out):
        for o in layout:
            if isinstance(o, LTChar):
                out.append(o)
            elif isinstance(o, LTFigure):
                chars_of(o, out)
        return out

    def lines_of(layout, out):
        for o in layout:
            if isinstance(o, LTTextLine):
                out.append(o)
            elif isinstance(o, LTTextContainer) or isinstance(o, LTFigure) or hasattr(o, "__iter__"):
                try:
                    lines_of(o, out)
                except TypeError:
                    pass
        return out

    res = []
    with open(path, "rb") as fh:
        doc = PDFDocument(PDFParser(fh))
        pages = list(PDFPage.create_pages(doc))[:npages]
        rsrc = PDFResourceManager()
        for page in pages:
            dev_raw = PDFPageAggregator(rsrc, laparams=None)
            PDFPageInterpreter(rsrc, dev_raw).process_page(page)
            raw = dev_raw.get_result()
            chars = chars_of(raw, [])
            dev = PDFPageAggregator(rsrc, laparams=LAParams())
            PDFPageInterpreter(rsrc, dev).process_page(page)
            lay = dev.get_result()
            lines = [l.get_text().strip() for l in lines_of(lay, [])]
            res.append((chars, lines, raw.width, raw.height, page))
    return res


def emon_chars(rows, page):
    out = []
    for r in rows:
        if int(r[0]) != page:
            continue
        cps = r[1]
        if cps == "-":
            out.append(("�", r))
        else:
            for c in cps.split("+"):
                out.append((chr(int(c, 16)), r))
    return out


WS = {9, 10, 13, 32, 160, 0x1680, 0x202F, 0x205F, 0x3000} | set(range(0x2000, 0x200B))


def digest(rows, page):
    """`emon.contentrows`'s digest of a page's glyph text: h = 7, then h = (h * 31 + cp + 1) mod 67108859
    over every code point of every glyph (U+FFFD for one with none) but white space."""
    h = 7
    n = 0
    for r in rows:
        if int(r[0]) != page:
            continue
        n += 1
        for c in ([0xFFFD] if r[1] == "-" else [int(x, 16) for x in r[1].split("+")]):
            if c not in WS:
                h = (h * 31 + c + 1) % 67108859
    return h, n


def f1(a, b):
    from collections import Counter
    ca, cb = Counter(a), Counter(b)
    common = sum((ca & cb).values())
    p = common / max(1, sum(ca.values()))
    r = common / max(1, sum(cb.values()))
    return common, sum(ca.values()), sum(cb.values())


def main():
    extra = 100
    files = None
    write = False
    pages = PAGES
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        if args[i] == "--extra":
            extra = int(args[i + 1]); i += 1
        elif args[i] == "--files":
            files = args[i + 1:]; break
        elif args[i] == "--write":
            write = True
        elif args[i] == "--pages":
            pages = int(args[i + 1]); i += 1
        i += 1
    paths = files if files else sample_files(extra)
    print("files: %d" % len(paths), file=sys.stderr)
    per = emon_rows(paths)
    signal.signal(signal.SIGALRM, alarm)

    tot = dict(files=0, pages=0, same=0, ch_emon=0, ch_pm=0, ch_match=0,
               w_common=0, w_emon=0, w_pm=0, l_common=0, l_emon=0, l_pm=0,
               g_n=0, g_ok=0, sure_n=0, sure_ok=0, skipped=0, panics=0, open_fail=0, pm_fail=0)
    worst = []
    ratchet = []
    kinds = {}
    for path in paths:
        if path not in per:
            tot["skipped"] += 1
            continue
        status, rows = per[path]
        if status[0].startswith("PANIC"):
            tot["panics"] += 1
            print("PANIC", path, status[0][:200])
            continue
        if status[0] != "ok":
            tot["open_fail"] += 1
            continue
        try:
            signal.alarm(60)
            pmp = pdfminer_pages(path, pages)
            signal.alarm(0)
        except Exception as e:
            signal.alarm(0)
            tot["pm_fail"] += 1
            continue
        tot["files"] += 1
        for pi, (chars, lines, pw, ph, page) in enumerate(pmp):
            pn = pi + 1
            ec = emon_chars(rows["G"], pn)
            etext = norm("".join(c for c, _ in ec))
            # per normalised character: is emon sure of it (`how` is not `unknown`)?
            sure = []
            for c, r in ec:
                sure.extend([r[2] != "5"] * len(norm(c)))
            ptext = norm("".join(c.get_text() if not c.get_text().startswith("(cid:") else "�" for c in chars))
            tot["pages"] += 1
            tot["ch_emon"] += len(etext)
            tot["ch_pm"] += len(ptext)
            if etext == ptext:
                if len(sure) == len(etext):
                    tot["sure_n"] += sum(1 for x in sure if x)
                    tot["sure_ok"] += sum(1 for x in sure if x)
                tot["same"] += 1
                tot["ch_match"] += len(etext)
                dg, ng = digest(rows["G"], pn)
                ratchet.append((path, pn, dg, ng))
            else:
                if len(etext) + len(ptext) < 40000:
                    sm = difflib.SequenceMatcher(None, etext, ptext, autojunk=False)
                    m = sum(b.size for b in sm.get_matching_blocks())
                else:
                    m = 0
                tot["ch_match"] += m
                worst.append((max(len(etext), len(ptext)) - m, path, pn, len(etext), len(ptext)))
            ew = [t for r in rows["T"] if int(r[0]) == pn for t in nk(r[3]).split()]
            pwds = [t for l in lines for t in nk(l).split()]
            c, a, b = f1(ew, pwds)
            tot["w_common"] += c; tot["w_emon"] += a; tot["w_pm"] += b
            el = [nk(" ".join(r[3].split())) for r in rows["T"] if int(r[0]) == pn]
            pl = [nk(" ".join(l.split())) for l in lines]
            c, a, b = f1(el, pl)
            tot["l_common"] += c; tot["l_emon"] += a; tot["l_pm"] += b
            # geometry where the streams agree and the crop box is the media box
            if etext == ptext and len(chars) == len(ec) and len(chars) > 0:
                try:
                    cb = page.cropbox
                    mb = page.mediabox
                    same_box = tuple(round(float(x), 2) for x in cb) == tuple(round(float(x), 2) for x in mb)
                except Exception:
                    same_box = False
                if same_box:
                    for lc, (ch, r) in zip(chars, ec):
                        if lc.get_text() != ch and norm(lc.get_text()) != norm(ch):
                            continue
                        x0, y0, x1, y1 = [int(v) / 100 for v in r[5:9]]
                        # pdfminer is bottom-left origin: its y = page height - emon y
                        # pdfminer's y is measured from the bottom, and its box is `size` tall, so
                        # compare what is defined the same way: the horizontal extent (for upright
                        # text), the baseline (its text matrix's origin) and the size
                        if abs(lc.matrix[1]) > 1e-6 or abs(lc.matrix[2]) > 1e-6:
                            continue
                        base = int(r[9]) / 100
                        pbase = ph - lc.matrix[5]
                        size = int(r[4]) / 100
                        tot["g_n"] += 1
                        ok_x = abs(lc.x0 - x0) <= 0.05 and abs(lc.x1 - x1) <= 0.05
                        ok_b = abs(pbase - base) <= 0.05
                        ok_s = abs(abs(lc.size) - size) <= 0.05
                        if ok_x and ok_b and ok_s:
                            tot["g_ok"] += 1
                        else:
                            k = ("x" if not ok_x else "") + ("b" if not ok_b else "") + ("s" if not ok_s else "")
                            kinds[k] = kinds.get(k, 0) + 1
    worst.sort(reverse=True)
    print()
    print("files compared: %d (skipped %d, pdfminer failed %d, emon open failed %d, emon PANICS %d)" %
          (tot["files"], tot["skipped"], tot["pm_fail"], tot["open_fail"], tot["panics"]))
    print("pages: %d; glyph stream identical on %d (%.2f%%)" % (tot["pages"], tot["same"], 100.0 * tot["same"] / max(1, tot["pages"])))
    print("characters: emon %d, pdfminer %d, matched %d (%.3f%% of the larger)" %
          (tot["ch_emon"], tot["ch_pm"], tot["ch_match"], 100.0 * tot["ch_match"] / max(1, max(tot["ch_emon"], tot["ch_pm"]))))
    print("glyphs emon is sure of (how is not unknown): %d, %.3f%% agree with pdfminer's" % (tot["sure_n"], 100.0 * tot["sure_ok"] / max(1, tot["sure_n"])))
    for nm, c, a, b in (("words", "w_common", "w_emon", "w_pm"), ("lines", "l_common", "l_emon", "l_pm")):
        p = tot[c] / max(1, tot[a]); r = tot[c] / max(1, tot[b])
        print("%s: emon %d, pdfminer %d, common %d; precision %.2f%% recall %.2f%% F1 %.2f%%" %
              (nm, tot[a], tot[b], tot[c], 100 * p, 100 * r, 200 * p * r / max(1e-9, p + r)))
    print("geometry (upright glyphs of identical pages, crop = media): %d glyphs, x extent + baseline + size all within 0.05 pt: %.2f%%; misses by kind %s" %
          (tot["g_n"], 100.0 * tot["g_ok"] / max(1, tot["g_n"]), kinds))
    print("worst pages (chars unmatched, file, page, emon chars, pdfminer chars):")
    for w in worst[:15]:
        print("  ", w)
    if write:
        out = os.path.join(ROOT, "test", "content", "real.txt")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w") as fh:
            fh.write("# emon P1.7: glyph streams of real files from corpus/ that pdfminer.six agrees with, kin/pdfcontenttest.kin.\n")
            fh.write("# Written by tools/oracle/content-corpus.py --write.\n")
            fh.write("# path (relative to test/) <TAB> page <TAB> digest of the glyph stream (emon.contentrows) <TAB> glyphs on the page\n")
            seen = {}
            for path, pn, h, n in ratchet:
                if n < 20:
                    continue
                seen[path] = seen.get(path, 0) + 1
                if seen[path] > 1:
                    continue
                fh.write("%s\t%d\t%s\t%d\n" % (os.path.relpath(path, os.path.join(ROOT, "test")), pn, h, n))
        print("wrote", out)


if __name__ == "__main__":
    main()
