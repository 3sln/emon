#!/usr/bin/env python3
"""Frozen fresh receipt test sample, excluding every baseline ID; paired live oracle."""
import argparse, collections, hashlib, json, os, pathlib, re, shutil, subprocess, sys
from artifacts import compact, read_bytes

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'tools/receipts'))
import receipts as R


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument('--out', type=pathlib.Path, required=True)
    p.add_argument('--bin', type=pathlib.Path, required=True)
    p.add_argument('--select-only', action='store_true')
    p.add_argument('--replay-only', action='store_true')
    a = p.parse_args()
    baseline = json.loads((ROOT/'test/receipts/baseline.json').read_text())
    excluded = {r[0] for s in baseline['sets'].values() for r in s['rows']}
    chosen = {}
    for dataset in ['sroie', 'cord']:
        files = [f for f in (ROOT/'corpus/_gold'/dataset).glob('*.gold.json')
                 if not f.name.startswith('._') and '-test-' in f.name and f.name[:-10] not in excluded]
        chosen[dataset] = R.sample(files, 12)
        assert len(chosen[dataset]) == 12
        assert not {json.loads(f.read_text())['id'] for f in chosen[dataset]} & excluded
    selection = {ds: [json.loads(f.read_text())['id'] for f in files] for ds, files in chosen.items()}
    a.out.mkdir(parents=True, exist_ok=True)
    manifest = a.out/'selection.json'
    if manifest.exists():
        assert json.loads(manifest.read_text()) == selection
    else:
        manifest.write_text(json.dumps(selection, indent=2)+'\n')
    if a.select_only:
        print(json.dumps(selection, indent=2))
        return
    if a.replay_only:
        checks = []
        for ds, files in chosen.items():
            for f in files:
                gold = json.loads(f.read_text())
                image, _, _ = R.annotations(gold)
                for engine in ['tesseract', 'ppocr']:
                    stem = a.out/engine/gold['id']
                    run = subprocess.run([str(a.bin.resolve()), '--ocr-engine='+engine, '--out=bundle',
                                          '--replay='+str(stem)+'.replay', str(image)], cwd=ROOT,
                                         capture_output=True, timeout=600)
                    equal = run.returncode == 0 and run.stdout == read_bytes(stem.with_suffix('.bundle'))
                    checks.append(dict(id=gold['id'], engine=engine, equal=equal, exit=run.returncode))
                    (a.out/'replay-summary.json').write_text(json.dumps(checks, indent=2)+'\n')
                    assert equal, gold['id']+': '+run.stderr.decode(errors='replace')[-1000:]
                    print(gold['id'], engine, equal, flush=True)
        return
    result = dict(selection=selection, selection_sha256=hashlib.sha256(manifest.read_bytes()).hexdigest(), sets={})
    env = dict(os.environ, EMON_CS_SHARED_COMPILATION='false', OMP_THREAD_LIMIT='1')
    for ds, files in chosen.items():
        rows = {'tesseract': {}, 'ppocr': {}}
        costs = {'tesseract': {}, 'ppocr': {}}
        for f in files:
            gold = json.loads(f.read_text())
            image, entities, items = R.annotations(gold)
            for engine in ['tesseract', 'ppocr']:
                if shutil.disk_usage('/System/Volumes/Data').free < 5*1024**3:
                    raise RuntimeError('internal disk below 5 GiB')
                out = a.out/engine
                out.mkdir(exist_ok=True)
                stem = out/gold['id']
                flag = '--tesseract' if engine == 'tesseract' else '--ocr-engine=ppocr'
                command = [sys.executable, str(ROOT/'tools/oracle/ppocr/measure.py'), '--timeout', '600', str(a.bin.resolve()),
                           flag, '--out=bundle', '--record='+str(stem)+'.replay', str(image)]
                run = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, timeout=630)
                stem.with_suffix('.bundle').write_text(run.stdout)
                stem.with_suffix('.err').write_text(run.stderr)
                assert run.returncode == 0, gold['id']+': '+run.stderr[-1000:]
                sections = dict(re.findall(r'== ([^\n]+)\n(.*?)(?=\n== |\Z)', run.stdout, re.S))
                model, evidence = [json.loads(sections[k]) for k in ['json', 'evidence']]
                rows[engine][gold['id']] = R.score(gold, model, evidence, entities, items)
                costs[engine][gold['id']] = json.loads(re.search(r'^emon-cost:(.*)$', run.stderr, re.M).group(1))
                compact(stem.with_suffix('.bundle'))
                print(ds, gold['id'], engine, flush=True)
        data = {}
        for engine in rows:
            totals = dict(sum((collections.Counter(r) for r in rows[engine].values()), collections.Counter()))
            totals['word_precision'] = totals['matched']/totals['model_words']
            totals['word_recall'] = totals['matched']/totals['gold_words']
            data[engine] = dict(totals=totals, rows=rows[engine], costs=costs[engine])
        result['sets'][ds] = data
        (a.out/'summary.json').write_text(json.dumps(result, indent=2)+'\n')


if __name__ == '__main__':
    main()
