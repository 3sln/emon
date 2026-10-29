#!/usr/bin/env python3
"""Freeze the populations of `bb tables-ab` (TR-design): IDs, input paths and input hashes only.

    python3 tools/table/ab_freeze.py [--check]

Writes test/tables-ab/populations.json. The selection is deterministic: every sampled population ranks its
candidates by sha256("tables-ab/v1/" + id) and takes the first N that are not excluded. The held-out sample is
drawn AFTER the tuning populations and is disjoint from them, from every control, and from every ID that any
batch report, DEVLOG entry or committed fixture names (EXCLUDE below, found by grepping those sources on
2026-10-29). --check recomputes the selection and fails if the committed file differs (the freeze is not
redrawn silently). No document content is written: only corpus-relative paths, IDs and sha256 digests.
"""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / 'corpus'
OUT = ROOT / 'test' / 'tables-ab' / 'populations.json'
FROZEN = '/Volumes/SSK SSD/emon-scratch/trd/frozen'
SALT = 'tables-ab/v1/'

# IDs a batch, report, control or fixture already names: never held out (grep of DEVLOG.md, .claude/coord/**/*.md,
# test/controls/*.txt, tools/, test/ on 2026-10-29).
EXCLUDE = {
    'ftn_00000', 'ftn_02204', 'ftn_03429', 'ftn_03897', 'ftn_04781',
    'cTDaR_t00781', 'cTDaR_t10095', 'cTDaR_t10202',
    'Digital_Type1/00005', 'Digital_Type1/00035', 'Digital_Type1/00075',
    'Scanned_Type1/00010', 'Scanned_Type1/00076', 'Scanned_Type1/00077', 'Scanned_Type1/00091',
    'Scanned_Type2/00044',
}

LIVE = ['--pdfium', '--tesseract']
NAMED_FLAGS = ['--pdfium', '--tesseract', '--assets=figures']
CONTROL_FLAGS = ['--pdfium', '--tesseract', '--ocr=auto', '--assets=figures']


def rank(i):
    return hashlib.sha256((SALT + i).encode()).hexdigest()


def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


def walk(n):
    yield n
    for c in n.get('children', []):
        yield from walk(c)


def gold(path):
    return json.loads(Path(path).read_text())


def synth():
    out = []
    for g in sorted((ROOT / 'test/corpus/synth/gold').glob('*.gold.json')):
        doc = g.name[:-len('.gold.json')]
        pdf = ROOT / 'test/corpus/synth/pdf' / (doc + '.pdf')
        if not pdf.exists() or not any(n['kind'] == 'table' for n in walk(gold(g)['model']['body'])):
            continue
        out.append(dict(id=doc, input=str(pdf.relative_to(ROOT)), gold=str(g.relative_to(ROOT)), sha256=sha(pdf)))
    return out


def goldens():
    return [dict(id=p.stem, golden=str(p.relative_to(ROOT)),
                 input=str((ROOT / ('test/table/ttab-low-trust.pdf' if p.stem == 'ttab-low-trust' else 'test/corpus/synth/pdf/' + p.stem + '.pdf')).relative_to(ROOT)))
            for p in sorted((ROOT / 'test/table').glob('*.json'))]


def gold_set(folder, input_of):
    out = []
    for g in sorted(x for x in (CORPUS / '_gold' / folder).glob('*.gold.json') if not x.name.startswith('._')):
        j = gold(g)
        rel = input_of(j)
        if rel is None or not (CORPUS / rel).exists():
            continue
        prod = j['provenance'].get('producer', '')
        track = 'A' if 'Track A' in prod else ('B2' if 'Track B2' in prod else ('B1' if 'Track B1' in prod else None))
        rows = any(n['kind'] == 'row' for n in walk(j['model']['body']))
        out.append(dict(id=j['id'], key=Path(rel).stem, input='corpus/' + rel, gold='corpus/_gold/' + folder + '/' + g.name,
                        track=track, structure=rows))
    return out


def pick(cands, n, taken):
    chosen = [c for c in sorted(cands, key=lambda c: rank(c['id'])) if c['key'] not in EXCLUDE and c['id'] not in taken][:n]
    taken.update(c['id'] for c in chosen)
    return chosen


def agami(kind):
    folder = CORPUS / 'financial/agami-indian-bank-statements/train' / ('India_Bank_Statement_' + kind)
    return [dict(id=kind + '/' + p.stem, key=kind + '/' + p.stem, input=str(p.relative_to(ROOT)),
                 gold=str(p.with_suffix('.json').relative_to(ROOT)))
            for p in sorted(folder.glob('*.pdf')) if not p.name.startswith('._') and p.with_suffix('.json').exists()]


def with_sha(items):
    for i in items:
        i['sha256'] = sha(ROOT / i['input'])
        i.pop('key', None)
    return items


def build():
    taken = set()
    icdar = gold_set('icdar2013', lambda j: j['provenance'].get('pdf'))
    ftn = gold_set('fintabnet_otsl', lambda j: j['provenance'].get('variants', {}).get('image'))
    ctd = gold_set('ctdar', lambda j: j['provenance'].get('variants', {}).get('image'))
    ctd_a = [c for c in ctd if c['track'] == 'A']
    ctd_s = [c for c in ctd if c['structure'] and c['track'] != 'A']
    kinds = ['Digital_Type1', 'Digital_Type2', 'Scanned_Type1', 'Scanned_Type2']
    ag = {k: agami(k) for k in kinds}
    # the tuning populations first (they may contain tuned-on anchors), then the held-out sample from what is left
    fintabnet = pick(ftn, 300, taken)
    ctdar = pick(ctd_a, 60, taken) + pick(ctd_s, 60, taken)
    anchors = [c for k in kinds for c in ag[k] if c['key'] in ('Digital_Type1/00075', 'Scanned_Type1/00077')]
    taken.update(c['id'] for c in anchors)
    ledgers = anchors + [c for k in kinds for c in pick(ag[k], 4, taken)]
    held = dict(
        fintabnet=pick(ftn, 150, taken),
        ctdar=pick(ctd_a, 30, taken) + pick(ctd_s, 30, taken),
        ledgers=[c for k in kinds for c in pick(ag[k], 4, taken)],
        bsb=[dict(id='bsb-003', input='corpus/financial/bank-statement-benchmark/datasets/basic/bsb-003/bsb-003-statement.pdf',
                  gold='corpus/financial/bank-statement-benchmark/datasets/basic/bsb-003/statement-info.json')],
    )
    pubtables = sorted(str(p.relative_to(ROOT)) for p in CORPUS.glob('*/*') if 'pubtables' in p.name.lower() or 'pubtables' in p.parent.name.lower())
    controls = [l.strip() for l in (ROOT / 'test/controls/sweep3.txt').read_text().splitlines() if l.strip() and not l.startswith('#')]
    sel = json.loads(Path(FROZEN, 'selection.json').read_text()) if Path(FROZEN, 'selection.json').exists() else []
    return {
        'format': 1,
        'salt': SALT,
        'frozen': '2026-10-29 at main a39535b1',
        'frozen_dir': FROZEN,
        'flags': {'live': LIVE, 'named': NAMED_FLAGS, 'controls': CONTROL_FLAGS, 'synth': []},
        'exclude': sorted(EXCLUDE),
        'populations': {
            'synth': {'mode': 'direct', 'gold': 'emon', 'items': synth(), 'goldens': goldens()},
            'icdar2013': {'mode': 'record', 'gold': 'emon', 'items': with_sha(icdar)},
            'fintabnet': {'mode': 'record', 'gold': 'emon', 'items': with_sha(fintabnet)},
            'ctdar': {'mode': 'record', 'gold': 'emon', 'items': with_sha(ctdar)},
            'pubtables': {'absent': not pubtables, 'items': pubtables},
            'ledgers': {'mode': 'record', 'gold': 'agami', 'items': with_sha(ledgers)},
            'named': {'mode': 'record', 'gold': 'named', 'flags': 'named', 'items': sel,
                      'expectations': 'test/tables-ab/named.json', 'triples': FROZEN + '/protected-triples.json'},
            'heldout': {'mode': 'record', 'gold': 'mixed', 'items': [dict(i, part=k) for k, v in held.items() for i in with_sha(v)]},
            'controls': {'mode': 'live', 'flags': 'controls', 'items': [dict(id='%02d' % n, input=p) for n, p in enumerate(controls)]},
        },
    }


def main():
    data = build()
    text = json.dumps(data, indent=1, sort_keys=True) + '\n'
    if '--check' in sys.argv:
        same = OUT.exists() and OUT.read_text() == text
        print('  ok populations frozen' if same else '  FAIL populations.json differs from the recomputed selection')
        return 0 if same else 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    pops = data['populations']
    print({k: len(v.get('items', [])) for k, v in pops.items()})
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
