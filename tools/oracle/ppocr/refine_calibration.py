#!/usr/bin/env python3
"""OP1c calibration-only candidate, never reads receipt test or control labels."""
import argparse, hashlib, json, pathlib


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument('--labels', type=pathlib.Path, required=True)
    p.add_argument('--out', type=pathlib.Path, required=True)
    a = p.parse_args()
    rows = json.loads(a.labels.read_text())
    fit = [r for r in rows if r['split'] == 'fit']
    validation = [r for r in rows if r['split'] == 'validation']
    assert len({r['id'] for r in fit}) == len({r['id'] for r in validation}) == 40
    assert not {r['id'] for r in fit} & {r['id'] for r in validation}
    assert all(r['id'].startswith('sroie-train-') for r in rows)
    # Predeclared resolution: one posterior percentile. PAVA pools violations;
    # empty bins inherit the lower block, with a zero floor before observations.
    groups = []
    for i in range(100):
        sample = [r for r in fit if min(r['posterior']//10000, 99) == i]
        if not sample:
            continue
        groups.append(dict(first=i, last=i, n=len(sample), correct=sum(r['correct'] for r in sample)))
        while len(groups) > 1 and groups[-2]['correct']*groups[-1]['n'] > groups[-1]['correct']*groups[-2]['n']:
            right, left = groups.pop(), groups.pop()
            groups.append(dict(first=left['first'], last=right['last'], n=left['n']+right['n'], correct=left['correct']+right['correct']))
    table = [0]*100
    for g in groups:
        value = (1000*g['correct']+g['n']//2)//g['n']
        for i in range(g['first'], 100):
            table[i] = value
    old = [0, 0, 0, 0, 42, 179, 439, 415, 521, 883]

    def metrics(sample, lookup):
        bins = []
        for i in range(len(lookup)):
            group = [r for r in sample if min(r['posterior']*len(lookup)//1000000, len(lookup)-1) == i]
            bins.append(dict(bin=i, n=len(group), correct=sum(r['correct'] for r in group)))
        n = len(sample)
        return dict(words=n, bins=bins,
                    brier=sum((lookup[min(r['posterior']*len(lookup)//1000000, len(lookup)-1)]/1000-r['correct'])**2 for r in sample)/n,
                    ece=sum(abs(b['n']*lookup[b['bin']]/1000-b['correct']) for b in bins)/n)
    result = dict(policy='100 equal posterior bins; fit-only PAVA; empty bins inherit lower block; integer nearest rounding. No receipt/control labels.',
                  labels_sha256=hashlib.sha256(a.labels.read_bytes()).hexdigest(), confidence=table, blocks=groups,
                  fit=metrics(fit, table), validation=metrics(validation, table), old_validation=metrics(validation, old))
    a.out.mkdir(parents=True, exist_ok=True)
    (a.out/'calibration-candidate.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({k: {m:v for m,v in result[k].items() if m != 'bins'} for k in ['fit', 'validation', 'old_validation']}, indent=2))
    print('lookup', table)


if __name__ == '__main__':
    main()
