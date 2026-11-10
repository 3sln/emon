#!/usr/bin/env python3
"""Revalidate prior physical reviews only against identical complete outputs."""
import argparse, json, pathlib
from artifacts import read_json

p = argparse.ArgumentParser(__doc__)
p.add_argument('--live', type=pathlib.Path, required=True)
p.add_argument('--prior', type=pathlib.Path, required=True)
a = p.parse_args()
reviews = read_json(a.prior/'review/source-review.json')
controls = sorted({r['control'] for r in reviews})
same = {}
for i in controls:
    equal = True
    for engine in ['tesseract', 'ppocr']:
        stem = f'{i:02}-{engine}'
        current = a.live/'controls'/stem
        prior = a.prior/'live/controls'/stem
        equal &= read_json(current.with_suffix('.json')) == read_json(prior.with_suffix('.json'))
        equal &= read_json(current.with_suffix('.evidence'))['words'] == read_json(prior.with_suffix('.evidence'))['words']
    same[i] = equal
validated = [r for r in reviews if same[r['control']] and pathlib.Path(r['crop']).exists()]
counted = [r for r in validated if r.get('counted_distinct_changed_readable_line')]
result = dict(method='Prior physical crop judgements; revalidated against complete Model and selected-word equality for both arms, with source crops retained. Not an independent fresh source judgement.',
              controls=same, reviewed_cards=len(validated), changed_readable_lines=len(counted),
              incumbent_losses=sum(len(r['new_source_correct_incumbent_word_losses']) for r in counted),
              cards_requiring_review=[r['card'] for r in reviews if r not in validated])
(a.live/'source-review-revalidation.json').write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result, indent=2))
