#!/usr/bin/env python3
"""Fresh default control costs and exact-output revalidation of prior source reviews."""
import argparse, hashlib, json, os, pathlib, re, shutil, subprocess, sys
from artifacts import read_bytes, read_json

ROOT = pathlib.Path(__file__).resolve().parents[3]


def refresh_default_costs(live, rows):
    """Keep the historical reference intact; time new, byte-verified defaults."""
    binary = ROOT/'.cache/emon-rs-fusion-ppocr/target/release/emon-rs'
    out = live/'fresh-default-controls'
    out.mkdir(exist_ok=True)
    manifest = out/'costs.json'
    costs = json.loads(manifest.read_text()) if manifest.exists() else {}
    env = dict(os.environ, EMON_CS_SHARED_COMPILATION='false', OMP_THREAD_LIMIT='1')
    for r in rows:
        i = r['index']
        stem = out/f'{i:02}'
        expected = read_bytes(live/'controls'/f'{i:02}-tesseract.bundle')
        if str(i) in costs:
            assert costs[str(i)]['sha256'] == hashlib.sha256(expected).hexdigest()
            assert costs[str(i)]['replay_proof']
            continue
        if shutil.disk_usage('/System/Volumes/Data').free < 5*1024**3:
            raise RuntimeError('internal disk below 5 GiB')
        command = [sys.executable, str(ROOT/'tools/oracle/ppocr/measure.py'), '--timeout', '7100', str(binary), '--pdfium',
                   '--tesseract', '--ocr=auto', '--assets=figures', '--out=bundle',
                   '--record='+str(stem)+'.replay', str(ROOT/r['source'])]
        with stem.with_suffix('.bundle').open('wb') as stdout, stem.with_suffix('.err').open('wb') as stderr:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=stdout, stderr=stderr, timeout=7200)
        assert result.returncode == 0, f'default control {i} failed'
        assert stem.with_suffix('.bundle').read_bytes() == expected, f'default control {i} changed'
        measured = json.loads(re.search(r'^emon-cost:(.*)$', stem.with_suffix('.err').read_text(), re.M).group(1))
        recorded = stem.with_suffix('.replay')
        reference = live/'controls'/f'{i:02}-tesseract.replay'
        original = (reference/'transcript.edn').read_bytes()
        same_recording = (recorded/'transcript.edn').read_bytes() == original
        if same_recording and r['tesseract'].get('replayed_byte_identically'):
            for name in re.findall(rb':bitmap "([^"]+)"', original):
                filename = name.decode()
                if hashlib.sha256((recorded/filename).read_bytes()).digest() != hashlib.sha256((reference/filename).read_bytes()).digest():
                    same_recording = False
                    break
        if same_recording:
            proof = 'Complete recording/bitmap equality to the reference already replayed by this candidate'
        else:
            replay = subprocess.run([str(binary), '--ocr-engine=tesseract', '--ocr=auto', '--assets=figures',
                                     '--out=bundle', '--replay='+str(recorded), str(ROOT/r['source'])],
                                    cwd=ROOT, env=env, capture_output=True, timeout=7200)
            assert replay.returncode == 0 and replay.stdout == expected, f'default control {i} replay differs'
            proof = 'Direct port-free replay of the fresh recording reproduces the complete bundle'
        costs[str(i)] = dict(measured, sha256=hashlib.sha256(expected).hexdigest(), byte_identical=True, replay_proof=proof)
        manifest.write_text(json.dumps(costs, indent=2)+'\n')
        stem.with_suffix('.bundle').unlink()
        shutil.rmtree(recorded)
        print('fresh default control', i, 'byte-identical', flush=True)
    return costs


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument('--live', type=pathlib.Path, required=True)
    p.add_argument('--prior', type=pathlib.Path, required=True)
    a = p.parse_args()
    rows = json.loads((a.live/'controls.json').read_text())
    assert len(rows) == 54 and len({r['index'] for r in rows}) == 54
    fresh = refresh_default_costs(a.live, rows)
    born = [15, 16, 17, 19, 23, 25, 26, 32]
    costs = [dict(index=r['index'], pages=r['ppocr']['pages'],
                  fast_cpu=fresh[str(r['index'])]['cpu_seconds']/r['tesseract']['pages'],
                  historical_fast_cpu=r['tesseract']['cpu']/r['tesseract']['pages'],
                  pp_cpu=r['ppocr']['cpu']/r['ppocr']['pages'],
                  ratio=r['ppocr']['cpu']/fresh[str(r['index'])]['cpu_seconds'],
                  peak_rss_bytes=r['ppocr']['peak_rss_bytes']) for r in rows]
    # Source judgements transfer only when the entire emitted Model AND the
    # selected word layer, including text/geometry/confidence, match exactly.
    same = {}
    for r in rows:
        i = r['index']
        equal = True
        for engine in ['tesseract', 'ppocr']:
            stem = f'{i:02}-{engine}'
            current = a.live/'controls'/stem
            prior = a.prior/'live/controls'/stem
            equal &= json.loads(current.with_suffix('.json').read_text()) == json.loads(prior.with_suffix('.json').read_text())
            equal &= read_json(current.with_suffix('.evidence'))['words'] == read_json(prior.with_suffix('.evidence'))['words']
        same[i] = equal
    reviews = json.loads((a.prior/'review/source-review.json').read_text())
    validated = [dict(r, exact_output_revalidated=True, prior_review=str(a.prior/'review/source-review.json'))
                 for r in reviews if same[r['control']]]
    pending = [r['card'] for r in reviews if not same[r['control']]]
    counted = [r for r in validated if r.get('counted_distinct_changed_readable_line')]
    result = dict(controls=54, born_digital={i: next(r for r in rows if r['index'] == i)['same_bundle'] for i in born},
                  same_bundles=sum(r['same_bundle'] for r in rows), costs=costs,
                  single_page_cpu_overruns=[r for r in costs if r['pages'] == 1 and r['ratio'] > 3],
                  document_average_overruns=[r for r in costs if r['pages'] > 1 and r['ratio'] > 3],
                  cost_method='Fresh default control conversions reproduce complete historical bundles byte for byte; PP measurements are this candidate\'s live sweep. Both include recording and whole-process wait4 CPU/RSS. Historical default timings are retained separately.',
                  source_review=dict(exact_revalidated_changed_readable_lines=len(counted),
                                     losses=sum(len(r['new_source_correct_incumbent_word_losses']) for r in counted),
                                     cards_requiring_new_review=pending,
                                     limitation='Prior physical source judgements revalidated by complete Model and selected-word equality; not a fresh independent physical review. Multipage costs are document averages.'))
    (a.live/'control-audit-summary.json').write_text(json.dumps(result, indent=2)+'\n')
    (a.live/'revalidated-source-reviews.json').write_text(json.dumps(validated, indent=2)+'\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
