"""Explicit maintainer operation: record current corpus, never run during fetch/status/prune."""
import json
from pathlib import Path
import re
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from corpus import ROOT, REPO, REGISTRY, fingerprint, files

manifest = json.loads((ROOT / 'manifest.json').read_text())
imports = {'sroie2019':'sroie', 'cord-v2':'cord', 'funsd':'funsd', 'doclaynet-v1.2-shards':'doclaynet', 'fintabnet-otsl':'fintabnet_otsl', 'icdar2019-ctdar':'ctdar', 'icdar2013-table-competition':'icdar2013', 'pdfa-eng-wds-shard0':'pdfa_eng_wds', 'xfund':'xfund', 'mapo80-stamps':'stamps', 'expressexpense-srd':'expressexpense-srd'}
shards=[]

def add(id, source, paths, target=None, importer=None, keep=False, reason=''):
    commands = [['python3','tools/shards/fetch.py', target]] if target else []
    if importer:
        commands.append(['bb','import',importer])
    s = dict(id=id, source=source, paths=paths, fetch=commands, keep=keep, reason=reason, regeneratable=not keep)
    print('hashing', id, flush=True)
    s.update(fingerprint(s))
    if not s['files']:
        s.update(keep=True, regeneratable=False, reason='source absent at snapshot; no verified historical digest')
    shards.append(s)

if '--consumers-only' in sys.argv:
    shards = json.loads(REGISTRY.read_text())['shards']
else:
    for src in manifest['sources']:
        name, path = src['name'], src['path']
        target = src['redownload'].split()[-1]
        importer = imports.get(name)
        if name == 'doclaynet-v1.2-shards':
            for split in ['test','val']:
                add('doclaynet-'+split,name,[path+'/'+split+'-shard4','_gold/doclaynet/doclaynet-'+split+'-*.gold.json'], 'doclaynet-'+split, importer)
        elif name == 'icdar2019-ctdar':
            for split, paths in [('train',['training']),('test',['test','test_ground_truth']),('samples',['samples','README.md'])]:
                add('ctdar-'+split,name,[path+'/'+p for p in paths]+(['_gold/ctdar/cTDaR-'+split+'-*.gold.json'] if split!='samples' else []),'ctdar-'+split,importer if split!='samples' else None)
        else:
            paths=[path]
            if importer: paths.append('_gold/'+importer+'/*.gold.json')
            keep = target in {'gov','irs','udhr','derived','agami_bank','rvlsmall','bsb','arxiv'}
            add(name,name,paths,target,importer,keep,'mutable upstream URLs or locally derived data; exact historical bytes not guaranteed' if keep else '')
    # Receipt set added after the original manifest.
    if not any(s['id']=='expressexpense-srd' for s in shards):
        add('expressexpense-srd','expressexpense-srd',['financial/expressexpense-srd','_gold/expressexpense-srd/*.gold.json'],keep=True,reason='locally fetched image set; pinned exact fetch recipe unavailable')
    # Account for every other file (including importer summaries) without making it pruneable.
    owned = {n for s in shards for n in files(s)}
    allfiles=files({'paths':['*']})
    rest=sorted(set(allfiles)-owned)
    add('local-unregistered','local',rest,keep=True,reason='unmanifested/curated files, metadata and importer summaries')


# Declarations include all direct corpus readers and selection files; glob readers need all source shards.
consumers={}
allids=[s['id'] for s in shards if s['id']!='local-unregistered']
for base in ['tools','kin','bench','test']:
    for p in (REPO/base).rglob('*'):
        if not p.is_file() or p.suffix in {'.pdf','.png','.jpg','.tiff','.jpeg','.zip'} or 'shards' in p.parts or 'shards-fetch' in p.parts or p.name=='shards.json': continue
        try: text=p.read_text()
        except (UnicodeError,OSError): continue
        if 'corpus/' not in text: continue
        needed=[]
        for s in shards:
            paths = list(s['paths'])
            source = next((src for src in manifest['sources'] if src['name'] == s['source']), None)
            if source:
                paths.append(source['path'])
            if any(('corpus/'+path.split('*')[0]).rstrip('/') in text for path in paths): needed.append(s['id'])
        # corpus-wide enumerators are conservative, including dynamically constructed paths.
        if not needed and str(p.relative_to(REPO)) in {'kin/scripts/pdf-compare','kin/scripts/pdf-corpus.py','kin/scripts/font-corpus.py','tools/vis/corpus-vis.clj','tools/oracle/content-corpus.py'}:
            needed=allids
        consumers[str(p.relative_to(REPO))]=needed

def declare(task, ids): consumers[task]=ids

def source_ids(*names): return [s['id'] for s in shards if s['source'] in names]
# Default gates use committed selection files that name real corpus fixtures.
default=set()
# Every committed test list (test/pdf, test/content, test/fonts, ...) counts: unit laws read them.
for p in [q for q in (REPO/'test').glob('**/*.txt') if 'test/corpus/' not in str(q)]+list((REPO/'test').glob('**/*corpus*.edn')):
    text=p.read_text()
    for s in shards:
        if any(path in text for path in s['paths'] if '*' not in path): default.add(s['id'])
# Kin laws and default smoke/baseline selections can also name paths directly.
for name, ids in consumers.items():
    if name.startswith('kin/') and name.endswith('.kin'): default.update(ids)
default.update(source_ids('derived-formats-degradations','sroie2019'))
for task in ['unit','gates']:
    declare(task,sorted(default))
declare('png-check',source_ids('derived-formats-degradations','sroie2019'))
for task in ['image','identity','repeat','struct-goldens','content-goldens']:
    declare(task,[])
declare('ocr-live',source_ids('funsd','sroie2019','cord-v2'))
declare('receipts',source_ids('sroie2019','cord-v2','expressexpense-srd'))
declare('stamp-ghost',source_ids('mapo80-stamps'))
declare('trace',source_ids('mapo80-stamps'))
declare('fusion-live',source_ids('derived-formats-degradations','mapo80-stamps'))
declare('gfuse-icdar',source_ids('icdar2013-table-competition'))
declare('png-bench',source_ids('derived-formats-degradations','sroie2019'))
declare('tools/parbench/parbench.clj',source_ids('agami-indian-bank-statements','berkshire-annual-report','arxiv-papers'))
for task in ['baselines','perf','parbench']:
    # These select documents dynamically; require all sources unless their registered tool narrows it.
    ids=set()
    for k,v in consumers.items():
        if task in k: ids.update(v)
    declare(task,sorted(ids))
# Optional --corpus tasks use the complete source inventory.
for task in ['identity:corpus','repeat:corpus']:
    declare(task,source_ids('agami-indian-bank-statements','bank-statement-benchmark','berkshire-annual-report','irs-forms','gov-reports-tables','gov-reports-layout','arxiv-papers','cuad-contracts','doclaynet-v1.2-shards','pdfa-eng-wds-shard0','udhr-and-wiki-multiscript','derived-formats-degradations'))
declare('image:corpus',source_ids('derived-formats-degradations','libtiff-pics'))
REGISTRY.write_text(json.dumps({'version':1,'digest_format':'sha256(sorted(path + NUL + file_sha256_hex + LF)); corpus-relative UTF-8 paths; ignore ._* everywhere','default':sorted(default),'consumers':dict(sorted(consumers.items())),'shards':shards},indent=2)+'\n')
