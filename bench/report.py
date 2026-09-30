#!/usr/bin/env python3
"""Print the P2.7 ratio table from bench/work/results-<mode>.txt and check that
every arm computed the same threshold, component count and label statistics.

    python3 bench/report.py [port|bulk ...]      (default: port)

Ratios are each arm's best-of-N milliseconds over the hand-written Rust
comparator's (from the `port` results file: the comparator has no bulk mode).
"""
import os, sys

here = os.path.dirname(os.path.abspath(__file__))
PHASES = ["hist", "otsu", "bin", "alloc", "label", "total"]


def load(mode):
    arms, cur = {}, None
    for line in open(os.path.join(here, "work", "results-%s.txt" % mode)):
        line = line.rstrip("\n")
        if line.startswith("== "):
            cur = line[3:].split(" ")[0]
            arms[cur] = {"T": {}, "S": None}
        elif line.startswith("T ") and cur:
            _, k, v = line.split()
            arms[cur]["T"][k] = float(v)
        elif line.startswith("S ") and cur:
            arms[cur]["S"] = line[2:]
    return arms


ok = True
for mode in (sys.argv[1:] or ["port"]):
    # results-<mode><TAG>: the comparator is the one from the same page's port run
    native = load("port" + (mode[mode.index("-"):] if "-" in mode else ""))["rust-native"]
    arms = load(mode)
    print("\n== mode: %s   (ms, best of N; ratio to rust-native in brackets)" % mode)
    print("%-12s" % "arm" + "".join("%18s" % p for p in PHASES))
    for name in ["rust-native"] + [a for a in arms if a != "rust-native"]:
        a = native if name == "rust-native" else arms[name]
        cells = []
        for p in PHASES:
            v = a["T"][p]
            r = v / native["T"][p] if native["T"][p] > 0.05 else float("nan")
            cells.append("%9.2f [%5.2fx]" % (v, r))
        print("%-12s" % name + "".join("%18s" % c for c in cells))
    ref = native["S"]
    for name, a in arms.items():
        if a["S"] != ref:
            ok = False
            print("MISMATCH %s: %s  !=  native %s" % (name, a["S"], ref))
    print("stats (threshold, components, ink, max area, provisional labels, "
          "checksum hi/lo, sum-sq-area hi/lo):\n  " + ref)
print("all arms agree" if ok else "ARMS DISAGREE")
sys.exit(0 if ok else 1)
