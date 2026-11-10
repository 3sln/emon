#!/usr/bin/env python3
"""Fixed 60+60 receipt oracle with per-page checkpoints and owned-child timeouts."""
import argparse, collections, hashlib, json, os, pathlib, re, shutil, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'tools/receipts'))
import receipts as R
from artifacts import compact, read_bytes, read_text


def totals(rows):
    values = dict(sum((collections.Counter(r) for r in rows.values()), collections.Counter()))
    for key, num, den in [('word_recall', 'matched', 'gold_words'), ('word_precision', 'matched', 'model_words'),
                          ('entity_recall', 'entities_ok', 'entities'), ('item_integrity', 'items_ok', 'items'),
                          ('numeric_recall', 'numeric_ok', 'numeric'), ('line_item_integrity', 'line_items_ok', 'items')]:
        values[key] = values.get(num, 0)/values[den] if values.get(den) else None
    return values


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument('--out', type=pathlib.Path, required=True)
    p.add_argument('--bin', type=pathlib.Path, required=True)
    a = p.parse_args()
    baseline = json.loads((ROOT/'test/receipts/baseline.json').read_text())
    binary = str(a.bin.resolve())
    binary_sha = hashlib.sha256(a.bin.read_bytes()).hexdigest()
    env = dict(os.environ, EMON_CS_SHARED_COMPILATION='false', OMP_THREAD_LIMIT='1')
    selection = {}
    for ds in ['sroie', 'cord']:
        files = [f for f in (ROOT/'corpus/_gold'/ds).glob('*.gold.json')
                 if not f.name.startswith('._') and (ds != 'sroie' or '-test-' in f.name)]
        selection[ds] = R.sample(files, 60)
        assert len(selection[ds]) == 60
    for engine in ['tesseract', 'ppocr']:
        out = a.out/('receipts-'+engine)
        out.mkdir(parents=True, exist_ok=True)
        summary = out/'summary.json'
        report = json.loads(summary.read_text()) if summary.exists() else {}
        checkpoints = out/'measurement-checkpoints.json'
        checked = json.loads(checkpoints.read_text()) if checkpoints.exists() else {}
        for ds, files in selection.items():
            data = report.setdefault(ds, dict(documents=0, totals={}, rows={}, input_views={}))
            ids = {json.loads(f.read_text())['id'] for f in files}
            assert set(data['rows']) <= ids
            for n, f in enumerate(files, 1):
                gold = json.loads(f.read_text())
                ident = gold['id']
                image, entities, items = R.annotations(gold)
                stem = out/ident
                if ident in data['rows']:
                    raw = read_bytes(stem.with_suffix('.bundle'))
                    parts = dict(re.findall(r'== ([^\n]+)\n(.*?)(?=\n== |\Z)', raw.decode(), re.S))
                    score = R.score(gold, json.loads(parts['json']), json.loads(parts['evidence']), entities, items)
                    assert score == data['rows'][ident], ident+' stored score changed'
                    assert 'cpu_seconds' in data['input_views'][ident]
                    checksum = hashlib.sha256(raw).hexdigest()
                    if ident in checked:
                        assert checked[ident]['binary_sha256'] == binary_sha
                        assert checked[ident]['bundle_sha256'] == checksum
                    checked[ident] = dict(binary_sha256=binary_sha, bundle_sha256=checksum, reused_completed_measurement=True)
                    checkpoints.write_text(json.dumps(checked, indent=2)+'\n')
                    print(engine, ds, n, '/ 60', ident, 'verified completed measurement', flush=True)
                    continue
                if shutil.disk_usage('/System/Volumes/Data').free < 5*1024**3:
                    raise RuntimeError('internal disk below 5 GiB')
                if shutil.disk_usage(a.out).free < 268435456:
                    raise RuntimeError('external scratch below 256 MiB')
                flag = '--tesseract' if engine == 'tesseract' else '--ocr-engine=ppocr'
                command = [sys.executable, str(ROOT/'tools/oracle/ppocr/measure.py'), '--timeout', '900', binary,
                           flag, '--out=bundle', '--record='+str(stem)+'.replay', str(image)]
                run = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, timeout=930)
                stem.with_suffix('.bundle').write_bytes(run.stdout)
                stem.with_suffix('.err').write_bytes(run.stderr)
                assert run.returncode == 0, ident+': '+stem.with_suffix('.err').read_text()[-1000:]
                raw = stem.with_suffix('.bundle').read_bytes()
                parts = dict(re.findall(r'== ([^\n]+)\n(.*?)(?=\n== |\Z)', raw.decode(), re.S))
                model, evidence = [json.loads(parts[k]) for k in ['json', 'evidence']]
                data['rows'][ident] = R.score(gold, model, evidence, entities, items)
                measured = json.loads(re.search(r'^emon-cost:(.*)$', stem.with_suffix('.err').read_text(), re.M).group(1))
                data['input_views'][ident] = dict(R.input_views(evidence), **measured)
                data['input_views'][ident]['ocr_reads'] = len(re.findall(r':kind\s+:ocr\b', (stem.with_suffix('.replay')/'transcript.edn').read_text()))
                data['documents'] = len(data['rows'])
                data['totals'] = totals(data['rows'])
                data['baseline_comparison'] = R.baseline_comparison(ds, data['rows'], data['totals'], baseline)
                checked[ident] = dict(binary_sha256=binary_sha, bundle_sha256=hashlib.sha256(raw).hexdigest())
                summary.write_text(json.dumps(report, indent=2)+'\n')
                checkpoints.write_text(json.dumps(checked, indent=2)+'\n')
                compact(stem.with_suffix('.bundle'))
                print(engine, ds, n, '/ 60', ident, flush=True)
            assert set(data['rows']) == ids
    # Use the same official per-document gold-loss audit as earlier rounds.
    from evaluate import receipt_audit
    receipt_audit(argparse.Namespace(out=a.out, fast_dir=None))


if __name__ == '__main__':
    main()
