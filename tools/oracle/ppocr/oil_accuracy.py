"""Score the new OIL reference on OP1c's fixed receipt selection, against D864."""
import argparse
import collections
import gzip
import json
import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'tools/receipts'))
import receipts as R


def read_bundle(path):
    if path.suffix == '.gz':
        with gzip.open(path, 'rt', encoding='utf-8') as source:
            return source.read()
    try:
        return path.read_text()
    except FileNotFoundError:
        with gzip.open(str(path) + '.gz', 'rt', encoding='utf-8') as source:
            return source.read()


def test_bundle_reader():
    with tempfile.TemporaryDirectory(prefix='emon-oil-score-') as directory:
        path = pathlib.Path(directory) / 'case.bundle'
        payload = '== json\n{}\n'
        path.write_text(payload)
        with gzip.open(str(path) + '.gz', 'wt', encoding='utf-8') as target:
            target.write(payload)
        assert read_bundle(path) == payload
        path.unlink()
        assert read_bundle(path) == payload
        assert read_bundle(pathlib.Path(str(path) + '.gz')) == payload
    print('PASS raw, compressed fallback and explicit gzip bundle reads')


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--qualification', type=pathlib.Path, required=True)
    parser.add_argument('--partial-dataset', choices=('sroie', 'cord'),
                        help='Development measurement only; writes partial-accuracy.json')
    args = parser.parse_args()
    cases = json.loads((args.qualification / 'replays.json').read_text())
    references = {'sroie': (0.701436, 0.756857), 'cord': (0.469098, 0.696764)}
    if args.partial_dataset:
        references = {args.partial_dataset: references[args.partial_dataset]}
        print('PARTIAL dataset measurement; full qualification is not accepted')
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
            bundle = read_bundle(args.qualification / row['expected'])
            sections = dict(re.findall(r'== ([^\n]+)\n(.*?)(?=\n== |\Z)', bundle, re.S))
            rows[ident] = R.score(gold, json.loads(sections['json']),
                                 json.loads(sections['evidence']), entities, items)
        totals = dict(sum((collections.Counter(row) for row in rows.values()), collections.Counter()))
        measured_precision = totals['matched'] / totals['model_words']
        measured_recall = totals['matched'] / totals['gold_words']
        report[dataset] = dict(documents=60, partial=bool(args.partial_dataset), totals=totals, rows=rows,
                               word_precision=measured_precision, word_recall=measured_recall,
                               d864_precision=precision, d864_recall=recall,
                               accepted=abs(measured_precision - precision) <= 0.002
                               and abs(measured_recall - recall) <= 0.002)
    report_name = 'partial-accuracy.json' if args.partial_dataset else 'accuracy.json'
    (args.qualification / report_name).write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: {k: v for k, v in value.items() if k not in ('rows', 'totals')}
                      for key, value in report.items()}, indent=2))
    assert all(row['accepted'] for row in report.values()), 'PP P/R outside D864 tolerance'


if __name__ == '__main__':
    if sys.argv[1:] == ['--self-test']:
        test_bundle_reader()
    else:
        main()
