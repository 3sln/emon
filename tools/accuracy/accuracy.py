#!/usr/bin/env python3
"""bb accuracy: score a predictor over the synthetic corpus and ratchet (D023, D074, D075).

    bb accuracy                        predictor "emon" (placeholder until P1.10: reports, exits 0)
    bb accuracy --predictor oracle     reference: gold -> Model, must score perfect
    bb accuracy --predictor degraded   fixture: damaged gold, proves the metrics move
    bb accuracy --predictor fixtures   both of the above
    bb accuracy --predictor "CMD ..."  any command (protocol in predict-oracle.py)
    bb accuracy --update               record improvements and new entries in the scoreboard
    bb accuracy --only ID              score one document (no ratchet)

RATCHET (SPEC 14.2): for every predictor that has a committed entry in
test/scoreboard.edn, every tracked metric of every category must not be worse
than committed by more than the tolerance (absolute; default 0.002, the file's
`:tolerance`). A metric that rose is reported, and `--update` writes it (the
ratchet only ever moves the recorded value toward better, never back). A metric
or category that VANISHED fails. A predictor with no committed entry is reported
"no baseline" and does not fail; `--update` creates it.

Scoreboard keys: predictor -> "VARIANT/CATEGORY" -> metric -> value. VARIANT is
the committed input form (pdf, scan, pdf-tagged); CATEGORY is the document id's
family (h, table, furn, ...) or `all`. Counts are pooled (micro-average).
No PDF tool and no producer is invoked; only committed files are read.
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
CORPUS = os.path.join(ROOT, "test", "corpus", "synth")
BOARD = os.path.join(ROOT, "test", "scoreboard.edn")
sys.path.insert(0, HERE)
import score as S  # noqa: E402

TOLERANCE = 0.002
UNAVAILABLE = 3
PREDICTORS = {
    "oracle": [sys.executable, os.path.join(HERE, "predict-oracle.py")],
    "degraded": [sys.executable, os.path.join(HERE, "predict-degraded.py")],
    "emon": ["sh", os.path.join(HERE, "predict-emon.sh")],
}
FAMILY = {"h": "headings", "table": "tables", "furn": "furniture", "figures": "figures",
          "lists": "lists", "script": "scripts", "columns": "columns", "footnotes": "footnotes",
          "stamp": "seals", "watermark": "watermarks", "invoice": "forms", "bank": "forms"}


def category(doc_id):
    return FAMILY.get(doc_id.split("-")[0], "other")


# ------------------------------------------------------------------ EDN ----
# Just enough EDN for the scoreboard: maps, keyword/string keys, numbers, strings.

def edn_write(v, ind=0):
    pad = "  " * ind
    if isinstance(v, dict):
        if not v:
            return "{}"
        rows = []
        for k in sorted(v, key=lambda k: (not k.startswith(":"), k)):
            ks = k if k.startswith(":") else json.dumps(k)
            rows.append("%s  %s %s" % (pad, ks, edn_write(v[k], ind + 1)))
        return "{\n" + "\n".join(rows) + "}"
    if isinstance(v, float):
        return repr(round(v, 6))
    return json.dumps(v)


def edn_read(text):
    toks = re.findall(r'"(?:[^"\\]|\\.)*"|;[^\n]*|[{}]|[^\s{}]+', text)
    toks = [t for t in toks if not t.startswith(";")]
    pos = 0

    def val():
        nonlocal pos
        t = toks[pos]; pos += 1
        if t == "{":
            d = {}
            while toks[pos] != "}":
                k = val()
                d[k] = val()
            pos += 1
            return d
        if t.startswith('"'):
            return json.loads(t)
        if t.startswith(":"):
            return t
        if t == "nil":
            return None
        return float(t) if re.search(r"[.eE]", t) else int(t)

    return val()


HEADER = """;; The accuracy scoreboard (D023, D074, SPEC 14.2).  `bb accuracy` compares a run
;; with this and FAILS on a regression beyond :tolerance; `bb accuracy --update`
;; records improvements and new entries (the ratchet only moves toward better).
;; predictor -> "VARIANT/CATEGORY" -> metric -> value.  Edit by running, not by hand.
;; `oracle` and `degraded` are fixtures that prove the scorer; the real pipeline
;; ("emon") has no entry until P1.10 exists.
"""


def load_board():
    if not os.path.exists(BOARD):
        return {":format": 1, ":tolerance": TOLERANCE, ":predictors": {}}
    with open(BOARD) as f:
        return edn_read(f.read())


def save_board(b):
    with open(BOARD, "w") as f:
        f.write(HEADER + edn_write(b) + "\n")


# ------------------------------------------------------------------ run ----

def run_predictor(cmd, doc_id, variant, inp, gold_path):
    r = subprocess.run(cmd + [doc_id, variant, inp, gold_path], capture_output=True, text=True)
    if r.returncode == UNAVAILABLE:
        return None, r.stderr.strip()
    if r.returncode != 0:
        raise SystemExit("FAIL predictor exited %d on %s/%s: %s" % (r.returncode, variant, doc_id, r.stderr.strip()))
    return json.loads(r.stdout), ""


def corpus():
    with open(os.path.join(CORPUS, "MANIFEST.json")) as f:
        man = json.load(f)
    for doc_id in sorted(man["docs"]):
        files = man["docs"][doc_id]["files"]
        for variant in ("pdf", "scan", "pdf-tagged"):
            if variant in files:
                yield doc_id, variant, os.path.join(CORPUS, files[variant]["path"]), \
                    os.path.join(CORPUS, "gold", doc_id + ".gold.json")


def evaluate(cmd, only=None):
    """-> (board entry {"variant/cat": {metric: v}}, per-document metrics) or None if unavailable."""
    pools = {}
    per_doc = {}
    for doc_id, variant, inp, gp in corpus():
        if only and doc_id != only:
            continue
        pred, why = run_predictor(cmd, doc_id, variant, inp, gp)
        if pred is None:
            return None, why
        with open(gp) as f:
            gold = json.load(f)
        c = S.score(pred, gold)["counts"]
        per_doc[variant + "/" + doc_id] = S.metrics(c)
        for cat in ("all", category(doc_id)):
            pools.setdefault(variant + "/" + cat, []).append(c)
    entry = {k: {":" + m: round(v, 6) for m, v in S.metrics(S.pool(cs)).items()} for k, cs in sorted(pools.items())}
    return entry, per_doc


def lower_better(metric):
    return metric.lstrip(":") in S.LOWER_IS_BETTER


def compare(old, new, tol):
    """-> (regressions, improvements, vanished) as lists of (key, metric, old, new)."""
    reg, imp, gone = [], [], []
    for key, ms in old.items():
        for m, ov in ms.items():
            nv = new.get(key, {}).get(m)
            if nv is None:
                gone.append((key, m, ov, None)); continue
            d = (ov - nv) if lower_better(m) else (nv - ov)      # positive = improved
            if d < -tol:
                reg.append((key, m, ov, nv))
            elif d > 1e-7:
                imp.append((key, m, ov, nv))
    return reg, imp, gone


def show(title, rows, limit=25):
    if rows:
        print("  %s: (%d)" % (title, len(rows)))
        for key, m, ov, nv in rows[:limit]:
            print("    %-22s %-24s %s -> %s" % (key, m, ov, "absent" if nv is None else "%.6g" % nv))
        if len(rows) > limit:
            print("    ... %d more" % (len(rows) - limit))


def merge_up(old, new):
    """The ratchet: per metric keep the better value; add what is new."""
    out = {k: dict(v) for k, v in old.items()}
    for key, ms in new.items():
        for m, nv in ms.items():
            ov = out.setdefault(key, {}).get(m)
            if ov is None or (nv < ov if lower_better(m) else nv > ov):
                out[key][m] = nv
    return out


def do_predictor(name, cmd, board, update, only):
    entry, extra = evaluate(cmd, only)
    if entry is None:
        print("  ok   accuracy[%s]: not yet available (%s); nothing scored, not a failure" % (name, extra))
        return 0
    if only:
        for k, ms in extra.items():
            print("  %s" % k)
            for m, v in sorted(ms.items()):
                print("    %-24s %.4f" % (m, v))
        return 0
    tol = board.get(":tolerance", TOLERANCE)
    old = board.get(":predictors", {}).get(name)
    n = sum(len(v) for v in entry.values())
    fails = 0
    if name == "oracle":
        bad = [(k, m, 1.0, v) for k, ms in entry.items() for m, v in ms.items()
               if abs(v - (0.0 if lower_better(m) else 1.0)) > 1e-9 and not m.startswith(":order")]
        if bad:
            print("  FAIL accuracy[oracle] is not perfect, so the SCORER is wrong:")
            show("imperfect", bad)
            fails += 1
    if old is None:
        print("  ok   accuracy[%s]: %d metrics in %d categories, no committed baseline%s"
              % (name, n, len(entry), "" if update else " (run --update to record)"))
    else:
        reg, imp, gone = compare(old, entry, tol)
        show("regressions beyond %.4g" % tol, reg)
        show("vanished", gone)
        show("improved (--update records)", imp)
        if reg or gone:
            print("  FAIL accuracy[%s]: %d regression(s), %d vanished metric(s)" % (name, len(reg), len(gone)))
            fails += 1
        else:
            print("  ok   accuracy[%s]: %d metrics, none regressed beyond %.4g (%d improved)"
                  % (name, n, tol, len(imp)))
    if update and not fails:
        board.setdefault(":predictors", {})[name] = merge_up(old or {}, entry)
        board[":tolerance"] = tol
        save_board(board)
        print("  ok   scoreboard updated: %s" % os.path.relpath(BOARD, ROOT))
    return fails


def main(argv):
    pred, update, only = "emon", False, None
    a = argv[1:]
    while a:
        x = a.pop(0)
        if x == "--predictor":
            pred = a.pop(0)
        elif x == "--update":
            update = True
        elif x == "--only":
            only = a.pop(0)
        else:
            print("usage: accuracy.py [--predictor emon|oracle|degraded|fixtures|CMD] [--update] [--only ID]")
            return 2
    board = load_board()
    if pred == "fixtures":
        runs = [("oracle", PREDICTORS["oracle"]), ("degraded", PREDICTORS["degraded"])]
    elif pred in PREDICTORS:
        runs = [(pred, PREDICTORS[pred])]
    else:
        import shlex
        runs = [(shlex.split(pred)[0], shlex.split(pred))]
    fails = 0
    for name, cmd in runs:
        fails += do_predictor(name, cmd, board, update, only)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
