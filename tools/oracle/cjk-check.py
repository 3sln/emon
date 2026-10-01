#!/usr/bin/env python3
"""Hold test/fonts/cjk.txt (the predefined CMap rows kin/pdffonttest.kin checks emon's generated tables against) to
pdfminer.six's own predefined CMaps (DEVLOG P3.4, D134).

    python3 tools/oracle/cjk-check.py [--verbose]

A THIRD-PARTY ORACLE, run by a person (no gate runs it; pdfminer.six is installed by hand into
~/.cache/emon/pylib like the other oracles). `cjk.txt`'s `cid` rows are the CIDs the Adobe CMap FILES give a few
thousand codes of every predefined CMap (usecmap resolved, no compression); emon's tables are those files
compressed (a CMap stored as its differences from a base), and pdfminer's are the same files compiled by its own
tooling. If all three agree the compression lost nothing. Rows pdfminer cannot answer (a CMap it does not ship,
a code it does not decode as one code) are counted, not failed.
"""
import os
import sys

sys.path.insert(0, os.path.expanduser("~/.cache/emon/pylib"))
from pdfminer.cmapdb import CMapDB  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROWS = os.path.join(HERE, "..", "..", "test", "fonts", "cjk.txt")


def code_of(tok):
    if tok.startswith("d"):
        return int(tok[1:]) & 0xFFFFFFFF
    return int(tok, 16)


def main():
    verbose = "--verbose" in sys.argv
    cmaps = {}
    agree = disagree = unanswered = missing = 0
    bad = []
    for line in open(ROWS):
        if line.startswith("#") or not line.strip():
            continue
        f = line.rstrip("\n").split("\t")
        if f[0] != "cid":
            continue
        name, code, cid = f[1][1:], code_of(f[2]), int(f[3])
        if name not in cmaps:
            try:
                cmaps[name] = CMapDB.get_cmap(name)
            except Exception:
                cmaps[name] = None
        cm = cmaps[name]
        if cm is None:
            missing += 1
            continue
        got = None
        # the CMap's own codespace decides how many bytes a code takes: try the lengths a code of that size could have
        for n in (1, 2, 3, 4):
            if code >= 256 ** n:
                continue
            try:
                cids = list(cm.decode(code.to_bytes(n, "big")))
            except Exception:
                continue
            if len(cids) == 1:
                got = cids[0]
                break
        if got is None:
            unanswered += 1
            continue
        if got == cid:
            agree += 1
        else:
            disagree += 1
            bad.append((name, hex(code), cid, got))
    print(f"cjk.txt cid rows against pdfminer.six: {agree} agree, {disagree} disagree, "
          f"{unanswered} not answered as one code, {missing} rows of CMaps pdfminer does not ship "
          f"({sum(1 for v in cmaps.values() if v is None)} of {len(cmaps)} CMaps)")
    for b in bad[: (len(bad) if verbose else 15)]:
        print("  disagree:", b)
    return 1 if disagree else 0


if __name__ == "__main__":
    sys.exit(main())
