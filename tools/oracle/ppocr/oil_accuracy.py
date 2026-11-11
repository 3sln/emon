"""Score the new OIL reference on OP1c's fixed receipt selection, against D864."""
import argparse
import collections
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools/receipts'))
import receipts as R


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--qualification', type=pathlib.Path, required=True)
    args = parser.parse_args()
    cases = json.loads((args.qualification / 'replays.json').read_text())
    references = {'sroie': (0.701436, 0.756857), 'cord': (0.469098, 0.696764)}
    report = {}
    for dataset, (precision, recall) in references.items():
        selected = [row for row in cases if row['population'] == 'fixed'
                    and row['id'].startswith(dataset + '-')]
        assert len(selected) == 60, f'{dataset}: expected exactly 60 fixed receipts'
        assert len({row['id'] for row in selected}) == 60, 'duplicate receipt'
        rows = {}
        for row in selected:
            ident = pathlib.Path(row['id']).stem
            gold = json.loads((ROOT / 'corpus/_gold' / dataset / (ident + '.gold.json')).read_text())
            image, entities, items = R.annotations(gold)
            assert image.resolve() == (ROOT / row['input']).resolve(), 'receipt input differs from gold'
            bundle = (args.qualification / row['expected']).read_text()
            sections = dict(re.findall(r'== ([^\n]+)\n(.*?)(?=\n== |\Z)', bundle, re.S))
            rows[ident] = R.score(gold, json.loads(sections['json']),
                                 json.loads(sections['evidence']), entities, items)
        totals = dict(sum((collections.Counter(row) for row in rows.values()), collections.Counter()))
        measured_precision = totals['matched'] / totals['model_words']
        measured_recall = totals['matched'] / totals['gold_words']
        report[dataset] = dict(documents=60, totals=totals, rows=rows,
                               word_precision=measured_precision, word_recall=measured_recall,
                               d864_precision=precision, d864_recall=recall,
                               accepted=abs(measured_precision - precision) <= 0.002
                               and abs(measured_recall - recall) <= 0.002)
    (args.qualification / 'accuracy.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: {k: v for k, v in value.items() if k not in ('rows', 'totals')}
                      for key, value in report.items()}, indent=2))
    assert all(row['accepted'] for row in report.values()), 'PP P/R outside D864 tolerance'


if __name__ == '__main__':
    main()
