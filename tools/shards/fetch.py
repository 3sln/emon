"""Fetch one registered source; sub-shards download only their selected upstream partition."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request

REPO = Path(__file__).resolve().parents[2]
ROOT = (REPO / 'corpus').resolve()
SUPPORT = REPO / 'test/corpus/shards-fetch'

def run(*args, **kw):
    subprocess.run(args, check=True, **kw)

kind = sys.argv[1]
if kind.startswith('doclaynet-'):
    split = kind.removeprefix('doclaynet-')
    remote = {'test': 'test-00004-of-00006.parquet', 'val': 'validation-00004-of-00007.parquet'}[split]
    with tempfile.TemporaryDirectory(prefix='emon-shard-', dir=ROOT) as temp:
        parquet = Path(temp) / remote
        urllib.request.urlretrieve('https://huggingface.co/datasets/ds4sd/DocLayNet-v1.2/resolve/main/data/' + remote, parquet)
        run('python3', str(SUPPORT / 'tools/extract_parquet.py'), str(parquet), str(ROOT / 'layout/doclaynet-v1.2' / (split + '-shard4')), 'dln_')
elif kind.startswith('ctdar-'):
    split = kind.removeprefix('ctdar-')
    paths = {'train': ['training'], 'test': ['test', 'test_ground_truth'], 'samples': ['samples', 'README.md']}[split]
    with tempfile.TemporaryDirectory(prefix='emon-shard-', dir=ROOT) as temp:
        checkout = Path(temp) / 'upstream'
        run('git', 'clone', '--filter=blob:none', '--no-checkout', 'https://github.com/cndplab-founder/ICDAR2019_cTDaR', str(checkout))
        run('git', '-C', str(checkout), 'sparse-checkout', 'set', '--no-cone', *['/' + x for x in paths])
        run('git', '-C', str(checkout), 'checkout', '171934db38a9e230e23b87a5afe86f8c83edaa95')
        dest = ROOT / 'tables/icdar2019-ctdar'
        dest.mkdir(parents=True, exist_ok=True)
        for path in paths:
            src = checkout / path
            if src.is_dir():
                shutil.copytree(src, dest / path, dirs_exist_ok=True)
            else:
                shutil.copy2(src, dest / path)
else:
    # Fetch into a clean staging root: upstream recipes skip existing directories,
    # while a safe prune deliberately leaves empty directories/AppleDouble behind.
    with tempfile.TemporaryDirectory(prefix='emon-shard-', dir=ROOT) as temp:
        run('bash', str(SUPPORT / 'fetch.sh'), kind, env={**os.environ, 'EMON_CORPUS': temp})
        for entry in Path(temp).iterdir():
            if entry.name == '_dl' or entry.name.startswith('._'):
                continue
            dest = ROOT / entry.name
            if entry.is_dir():
                shutil.copytree(entry, dest, dirs_exist_ok=True, ignore=shutil.ignore_patterns('._*'))
            else:
                shutil.copy2(entry, dest)
