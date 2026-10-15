#!/usr/bin/env python3
"""Generate kin/nftable.kin, the data behind `emon.normalize`, from the
Unicode Character Database files vendored in test/normalize/.

    python3 kin/scripts/ucd.py            write kin/nftable.kin
    python3 kin/scripts/ucd.py --check    fail when the file is stale

emon's own script (D039); the algorithm is UAX #15 and the inputs are
UnicodeData.txt and DerivedNormalizationProps.txt (UAX #44). What comes out:

  nf-ccc    [first last ccc]   Canonical_Combining_Class ranges, ccc != 0
  nf-dec    [cp offset len]    the FULL canonical decomposition of every code
                               point that has one (recursively flattened, so
                               a lookup is one step), sorted by cp; Hangul
                               syllables are algorithmic and in no table
  nf-pool   cp                 the code points the decompositions point into
  nf-comp   [first second c]   the primary composites: two-code-point
                               canonical decompositions that are NOT in
                               Full_Composition_Exclusion, sorted by
                               (first, second)
  nf-lig    [cp n c0 c1 c2]    SPEC 3.6's ligature expansion, U+FB00..FB06,
                               taken from their compatibility mappings

  nf-arab   [cp offset len]    the compatibility mapping of every Arabic
                               presentation form (U+FB50..FDFF, FE70..FEFF),
                               flattened, offset into nf-apool (D582)
  nf-apool  cp                 the code points those mappings point into

Only CANONICAL data is generated, but for the compatibility mappings of two
sets: SPEC 3.6 asks for NFC and a ligature fold, and explicitly NOT NFKC
(D029), so the compatibility mappings stay out except for those seven
ligatures and, on D582, the Arabic presentation forms (NFKC on that range
only: a PDF draws the shaped glyph of a letter, and the text of the page is the
letter).
"""
import re
import sys

SRC = "test/normalize/"
OUT = "kin/nftable.kin"


def parse_unicode_data():
    ccc, single, compat = {}, {}, {}
    first = None
    for line in open(SRC + "UnicodeData.txt", encoding="utf-8"):
        f = line.rstrip("\n").split(";")
        if len(f) < 6:
            continue
        cp = int(f[0], 16)
        if f[1].endswith(", First>"):
            first = cp
            continue
        if f[1].endswith(", Last>"):
            first = None          # ranges (CJK, Hangul, private use) carry no data
            continue
        c = int(f[3])
        if c:
            ccc[cp] = c
        d = f[5]
        if d:
            if d.startswith("<"):
                compat[cp] = [int(x, 16) for x in d.split(" ")[1:]]
            else:
                single[cp] = [int(x, 16) for x in d.split(" ")]
    return ccc, single, compat


def parse_exclusions():
    fce = set()
    for line in open(SRC + "DerivedNormalizationProps.txt", encoding="utf-8"):
        m = re.match(r"^([0-9A-F]+)(?:\.\.([0-9A-F]+))?\s*;\s*Full_Composition_Exclusion\b", line)
        if m:
            lo = int(m.group(1), 16)
            hi = int(m.group(2), 16) if m.group(2) else lo
            fce.update(range(lo, hi + 1))
    return fce


def full(cp, single):
    """Recursive canonical decomposition (no Hangul: it has no entries)."""
    if cp not in single:
        return [cp]
    out = []
    for c in single[cp]:
        out.extend(full(c, single))
    return out


def full_compat(cp, single, compat):
    m = single.get(cp) or compat.get(cp)
    if m is None:
        return [cp]
    out = []
    for c in m:
        out.extend(full_compat(c, single, compat))
    return out


def ranges(ccc):
    rows, cps = [], sorted(ccc)
    i = 0
    while i < len(cps):
        j = i
        while j + 1 < len(cps) and cps[j + 1] == cps[j] + 1 and ccc[cps[j + 1]] == ccc[cps[i]]:
            j += 1
        rows.append((cps[i], cps[j], ccc[cps[i]]))
        i = j + 1
    return rows


def build():
    ccc, single, compat = parse_unicode_data()
    fce = parse_exclusions()
    ccc_rows = ranges(ccc)
    dec_rows, pool = [], []
    for cp in sorted(single):
        d = full(cp, single)
        dec_rows.append((cp, len(pool), len(d)))
        pool.extend(d)
    comp = sorted((d[0], d[1], cp) for cp, d in single.items()
                  if len(d) == 2 and cp not in fce)
    assert len({(a, b) for a, b, _ in comp}) == len(comp), "duplicate composition pair"
    lig = []
    for cp in range(0xFB00, 0xFB07):
        d = full_compat(cp, single, compat)
        assert 2 <= len(d) <= 3 and all(x < 128 for x in d), (hex(cp), d)
        lig.append((cp, len(d)) + tuple(d) + (0,) * (3 - len(d)))
    assert [r[2:5] for r in lig] == [
        (102, 102, 0), (102, 105, 0), (102, 108, 0), (102, 102, 105),
        (102, 102, 108), (115, 116, 0), (115, 116, 0)], lig
    arab, apool = [], []
    for cp in list(range(0xFB50, 0xFE00)) + list(range(0xFE70, 0xFF00)):
        if cp not in compat:
            continue
        d = full_compat(cp, single, compat)
        # the isolated forms of the marks map to a SPACE and the mark (UAX #15): a space inside a word is no part of what the page
        # shows, so the mark alone is the fold
        if len(d) == 2 and d[0] == 0x20:
            d = d[1:]
        arab.append((cp, len(apool), len(d)))
        apool.extend(d)
    assert len(arab) > 600 and max(r[2] for r in arab) <= 18, (len(arab),)
    return ccc_rows, dec_rows, pool, comp, lig, arab, apool, len(fce), len(single)


def table(name, doc, stride, rows, accessors, pub=True):
    flat = [x for r in (rows if stride > 1 else [[r] for r in rows]) for x in r]
    lines = [f";; {doc}",
             f"(defdata {'^:pub ' if pub else ''}{name}",
             "  :emitter kin.lang/flat-array"]
    if stride > 1:
        lines.append(f"  :stride {stride}")
    per = stride if stride > 1 else 12
    body = []
    for i in range(0, len(flat), per):
        body.append(" ".join(str(x) for x in flat[i:i + per]))
    lines.append("  :data [" + ("\n         ".join(body)) + "]")
    lines.append("  :accessors {" + ("\n              ".join(accessors)) + "})")
    return "\n".join(lines) + "\n"


def render():
    ccc_rows, dec_rows, pool, comp, lig, arab, apool, nfce, nsingle = build()
    head = f''';; GENERATED by kin/scripts/ucd.py from test/normalize/ -- do not edit.
;;
;; UAX #15 data for `emon.normalize` (P0.4). Canonical data only: SPEC 3.6
;; asks for NFC plus a seven-entry ligature fold, and not NFKC (D029); D582 adds the {len(arab)} Arabic presentation forms.
;;
;; {len(ccc_rows)} combining-class ranges; {len(dec_rows)} canonical decompositions
;; (fully flattened, {len(pool)} pooled code points) from {nsingle} single-step
;; mappings; {len(comp)} primary composites (the two-code-point mappings NOT in
;; Full_Composition_Exclusion, of which there are {nfce} code points); 7 ligatures.
;; HANGUL IS ALGORITHMIC and is in no table: 11172 rows would be longer than
;; the arithmetic.
(ns emon.nftable
  (:require [emon.lang :refer [I32]]
            [kin.lang :refer [defdata]]))

'''
    parts = [
        table("nf-ccc", "first, last, Canonical_Combining_Class", 3, ccc_rows,
              ["^I32 nf-ccc-count [^I32 ignored]", "^I32 nf-ccc-at [^I32 ignored ^I32 row ^I32 field]"]),
        table("nf-dec", "code point, offset into nf-pool, length", 3, dec_rows,
              ["^I32 nf-dec-count [^I32 ignored]", "^I32 nf-dec-at [^I32 ignored ^I32 row ^I32 field]"]),
        table("nf-pool", "the code points the decompositions point at", 1, pool,
              ["^I32 nf-pool-len [^I32 ignored]", "^I32 nf-pool-at [^I32 ignored ^I32 i]"]),
        table("nf-comp", "first, second, composite -- sorted by (first, second)", 3, comp,
              ["^I32 nf-comp-count [^I32 ignored]", "^I32 nf-comp-at [^I32 ignored ^I32 row ^I32 field]"]),
        table("nf-lig", "code point, length, then up to three code points (SPEC 3.6)", 5, lig,
              ["^I32 nf-lig-count [^I32 ignored]", "^I32 nf-lig-at [^I32 ignored ^I32 row ^I32 field]"]),
        table("nf-arab", "Arabic presentation form (U+FB50..FDFF, U+FE70..FEFF), offset into nf-apool, length (D582)", 3, arab,
              ["^I32 nf-arab-count [^I32 ignored]", "^I32 nf-arab-at [^I32 ignored ^I32 row ^I32 field]"]),
        table("nf-apool", "the code points the Arabic presentation forms fold to (their compatibility mappings, flattened)", 1, apool,
              ["^I32 nf-apool-len [^I32 ignored]", "^I32 nf-apool-at [^I32 ignored ^I32 i]"]),
    ]
    return head + "\n".join(parts)


if __name__ == "__main__":
    text = render()
    if "--check" in sys.argv:
        if open(OUT, encoding="utf-8").read() != text:
            print("FAIL", OUT, "is stale; run kin/scripts/ucd.py")
            sys.exit(1)
        print("ok  ", OUT, "is current")
    else:
        open(OUT, "w", encoding="utf-8").write(text)
        print("wrote", OUT, len(text), "bytes")
