#!/usr/bin/env python3
"""Run every public-dataset importer and report counts and validation (DEVLOG P2.11).   (bb import, once wired)

    tools/import/import_all.py [--limit N] [name ...]

Each importer writes corpus/_gold/<name>/*.gold.json (outside git) and validates every gold against
schema/emon-gold-1.json plus id/page checks.  A dataset that is not in the local corpus is reported
as MISSING (run corpus/fetch.sh), not as a failure; any invalid gold or an importer error fails the run.
NOT part of `bb gates`: the corpus is not in the repository.
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import common as C

# name, script, the path (under corpus/) that must exist
IMPORTERS = [
    ("funsd", "funsd.py", "forms/funsd/dataset"),
    ("xfund", "xfund.py", "multilingual/xfund"),
    ("sroie", "sroie.py", "financial/sroie2019"),
    ("cord", "cord.py", "financial/cord-v2"),
    ("doclaynet", "doclaynet.py", "layout/doclaynet-v1.2"),
    ("icdar2013", "icdar2013.py", "tables/icdar2013"),
    ("ctdar", "ctdar.py", "tables/icdar2019-ctdar"),
    ("fintabnet_otsl", "fintabnet_otsl.py", "tables/fintabnet-otsl"),
    ("pdfa_eng_wds", "pdfa_eng_wds.py", "layout/pdfa-eng-wds"),
    ("stamps", "stamps.py", "seals/mapo80-stamps"),
]


def main(argv):
    limit = []
    names = []
    i = 0
    while i < len(argv):
        if argv[i] == "--limit":
            limit = ["--limit", argv[i + 1]]
            i += 2
        else:
            names.append(argv[i])
            i += 1
    rows, bad = [], 0
    for name, script, need in IMPORTERS:
        if names and name not in names:
            continue
        if not os.path.isdir(os.path.join(C.CORPUS, need)):
            rows.append((name, "MISSING", 0, 0, 0, "%s not in the corpus (corpus/fetch.sh)" % need))
            continue
        r = subprocess.run([sys.executable, os.path.join(HERE, script), "--quiet"] + limit, capture_output=True, text=True)
        try:
            s = json.loads(r.stdout.strip().splitlines()[-1])
        except Exception:
            bad += 1
            rows.append((name, "ERROR", 0, 0, 0, (r.stderr.strip().splitlines() or ["no output"])[-1][:100]))
            continue
        ok = r.returncode == 0
        bad += 0 if ok else 1
        note = "; ".join("%s x%d" % (k, v) for k, v in s.get("warnings", {}).items())
        if s.get("invalid_examples"):
            note = s["invalid_examples"][0] + " | " + note
        rows.append((name, "ok" if ok else "FAIL", s["written"], s["invalid"], s["skipped"], note))
    print("%-16s %-7s %8s %8s %8s  %s" % ("dataset", "status", "written", "invalid", "skipped", "notes"))
    for r in rows:
        print("%-16s %-7s %8d %8d %8d  %s" % r)
    tot = sum(r[2] for r in rows)
    print("total %d gold files under %s; %s" % (tot, os.path.relpath(C.OUT, C.ROOT), "FAILED" if bad else "all valid"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
