#!/usr/bin/env python3
"""Verify frozen lookup, reliability arithmetic, disjoint IDs and asset provenance."""
import hashlib,json,pathlib,re
ROOT=pathlib.Path(__file__).resolve().parents[2]
r=json.loads((ROOT/'tools/ppocr/calibration.json').read_text())
m=re.search(r'vec!\[([0-9,\s]+)\]',(ROOT/'host/rust/src/ppocr_calibration.rs').read_text());assert m
lookup=[int(s) for s in m.group(1).split(',') if s.strip()]
assert lookup==r['confidence'] and len(lookup)==10
assert r['model_pins_sha256']==hashlib.sha256((ROOT/'tools/ppocr/assets.edn').read_bytes()).hexdigest()
baseline=json.loads((ROOT/'test/receipts/baseline.json').read_text())
excluded={row[0] for entry in baseline['sets'].values() for row in entry['rows']}
ids=[d['id'] for d in r['documents']]
assert len(ids)==len(set(ids))==80 and not set(ids)&excluded
assert all(i.startswith('sroie-train-') for i in ids)
for i,row in enumerate(r['fit']['bins']):
    n=row['n'];k=row['correct'];assert 0<=k<=n
    assert lookup[i]==((1000*k+n//2)//n if n else 0)
for split in ['fit','validation']:
    rows=r[split]['bins'];n=sum(g['n'] for g in rows);assert n==r[split]['words']
    brier=sum(g['correct']*(1-lookup[g['bin']]/1000)**2+(g['n']-g['correct'])*(lookup[g['bin']]/1000)**2 for g in rows)/n
    ece=sum(g['n']*abs(lookup[g['bin']]/1000-g['correct']/g['n']) for g in rows if g['n'])/n
    assert abs(brier-r[split]['calibrated_brier'])<1e-12
    assert abs(ece-r[split]['calibrated_ece'])<1e-12
assert sum(d['aligned'] for d in r['documents'][:40])==r['fit']['words']
assert sum(d['aligned'] for d in r['documents'][40:])==r['validation']['words']
print('verified frozen confidence lookup, reliability arithmetic and disjoint calibration IDs')
