#!/usr/bin/env python3
"""Offline OP1c diagnosis. Source-bearing rows are written only to scratch.

Counter attribution is deliberately conservative: this is a diagnostic, not
spatial gold or acceptance scoring. It never fits thresholds on receipts.
"""
import argparse, collections, json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools/receipts'))
import receipts as R


def bundle(path, evidence=False):
    # Stream sections: earlier OP1 evidence can be large, and is unnecessary
    # for rescoring represented Model text.
    result = {}
    key, lines = None, []
    with path.open() as stream:
        for line in stream:
            if line.startswith('== '):
                if key is not None:
                    result[key] = json.loads(''.join(lines))
                section = line[3:].strip()
                key = section if section == 'json' or (evidence and section == 'evidence') else None
                lines = []
                if 'json' in result and not evidence:
                    break
            elif key is not None:
                lines.append(line)
        else:
            if key is not None:
                result[key] = json.loads(''.join(lines))
    return result


def counter(model):
    return collections.Counter(R.words(' '.join(R.text(n) for n in
        [model.get('body', {})] + model.get('furniture', []))))


def overlap(a, b):
    area = min((a[2]-a[0])*(a[3]-a[1]), (b[2]-b[0])*(b[3]-b[1]))
    return (max(0, min(a[2], b[2])-max(a[0], b[0])) *
            max(0, min(a[3], b[3])-max(a[1], b[1])) / area) if area > 0 else 0


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument('--out', type=pathlib.Path, required=True)
    a = p.parse_args()
    scratch = pathlib.Path('/Volumes/SSK SSD/emon-scratch')
    totals = collections.defaultdict(collections.Counter)
    rescored = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))
    rows = []
    for dataset in ['sroie', 'cord']:
        summary = json.loads((scratch/'op1b/live/receipts-ppocr/summary.json').read_text())[dataset]
        for ident in summary['rows']:
            name = ident + '.bundle'
            fast = bundle(scratch/'op1/live/receipts-tesseract'/name)
            old = bundle(scratch/'op1/live/receipts-ppocr'/name)
            guarded = bundle(scratch/'op1b/live/receipts-ppocr'/name, evidence=True)
            gold_model = json.loads((ROOT/'corpus/_gold'/dataset/(ident+'.gold.json')).read_text())['model']
            gold = collections.Counter(R.words(R.text(gold_model['body'])))
            for label, value in [('fast', fast), ('op1', old), ('op1b', guarded)]:
                actual = counter(value['json'])
                rescored[dataset][label].update(dict(gold_words=sum(gold.values()), model_words=sum(actual.values()),
                                                    matched=sum((actual & gold).values())))
            fc, oc, gc = [counter(x['json']) & gold for x in [fast, old, guarded]]
            dropped = (oc-fc) - (gc-fc)
            e = guarded['evidence']
            pp = [w for run in e['oruns'] if run.get('request', 0) % 256 == 23
                  for w in e['owords'][run['words'][0]:sum(run['words'])]]
            for token, count in dropped.items():
                candidates = [w for w in pp if token in R.words(w['text'])]
                causes = collections.Counter()
                details = []
                for w in candidates:
                    slots = [s for s in e['ofuse'] if s['page'] == w['page'] and overlap(s['box'], w['box']) >= .5]
                    selected = [v for v in e['words'] if v['page'] == w['page'] and overlap(v['box'], w['box']) >= .5]
                    if not slots:
                        cause = 'alignment-or-eligibility-miss'
                    else:
                        # The optional pass follows default fusion. Prefer its
                        # final decision rather than attributing an earlier veto.
                        s = slots[-1]
                        rule = s['rule']
                        if rule == 'blocked-lexicon':
                            cause = 'dictionary-or-two-reading-agreement'
                        elif rule == 'blocked-format':
                            cause = 'format-protection'
                        elif rule == 'blocked-slot':
                            cause = 'slot-size-or-shortening'
                        elif w['conf'] < 700 and not selected:
                            cause = 'insertion-confidence-below-700'
                        elif rule == 'keep':
                            cause = 'confidence-margin-or-tied-agreement'
                        else:
                            cause = 'selection-or-model-representation'
                    causes[cause] += 1
                    details.append(dict(word=w, cause=cause, slots=slots))
                cause = causes.most_common(1)[0][0] if causes else 'pp-reading-tokenization-or-missing'
                totals[dataset][cause] += count
                rows.append(dict(dataset=dataset, id=ident, token=token, count=count,
                                 cause=cause, candidates=details))
            print(ident, sum(dropped.values()), flush=True)
    a.out.mkdir(parents=True, exist_ok=True)
    for dataset, arms in rescored.items():
        for label, values in arms.items():
            values['word_precision'] = values['matched']/values['model_words']
            values['word_recall'] = values['matched']/values['gold_words']
            folder = 'op1b/live/receipts-ppocr' if label == 'op1b' else 'op1/live/receipts-'+('tesseract' if label == 'fast' else 'ppocr')
            recorded = json.loads((scratch/folder/'summary.json').read_text())[dataset]['totals']
            assert all(values[k] == recorded[k] for k in ['matched', 'gold_words', 'model_words'])
    result = dict(method='Positive OP1 gold-counter gains absent from OP1b; overlap/min-area >= .5; last overlapping fusion decision; primary cause per token. No threshold fitting. Counter attribution is not spatial gold. Whole-line adoption is not offered by this fusion; a separate line-level causal upper bound is not inferred from counter matches.',
                  rescored=rescored, totals={k: dict(v) for k, v in totals.items()}, rows=rows)
    (a.out/'gain-diagnosis.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result['totals'], indent=2))


if __name__ == '__main__':
    main()
