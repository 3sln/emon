#!/usr/bin/env python3
"""Paired OP1 live measurements; outputs (including source text) stay external."""
import argparse,collections,hashlib,json,os,pathlib,re,resource,shutil,subprocess,sys,time,unicodedata
from artifacts import compact, read_bytes, read_text
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'tools/receipts'))
import receipts as R

def disk():
    if shutil.disk_usage('/System/Volumes/Data').free<5*1024**3:raise RuntimeError('internal disk below 5 GiB')
def sections(s):return dict(re.findall(r'== ([^\n]+)\n(.*?)(?=\n== |\Z)',s,re.S))
def represented_text(node):
    body=node.get('text')
    if body is None:body=' '.join(represented_text(c) for c in node.get('inlines',[]))
    body=' '.join([body]+[represented_text(c) for c in node.get('children',[])])
    extra=[]
    for key in ['labels','number','label','template']:
        value=node.get(key)
        if key=='label' and any(c.get('kind')=='caption' and c.get('label')==value for c in node.get('children',[])):continue
        if isinstance(value,str):extra.append(value)
        elif isinstance(value,list):extra.extend(v for v in value if isinstance(v,str))
    return ' '.join([body]+extra)
def represented_words(model):
    value=' '.join(represented_text(n) for n in [model.get('body',{})]+model.get('furniture',[]))
    return collections.Counter(unicodedata.normalize('NFC',value).split())

def loss(before,after,expected):
    old=collections.Counter(R.words(' '.join(R.text(n) for n in [before.get('body',{})]+before.get('furniture',[]))))
    new=collections.Counter(R.words(' '.join(R.text(n) for n in [after.get('body',{})]+after.get('furniture',[]))))
    gold=collections.Counter(R.words(R.text(expected.get('body',{}))))
    return dict((old&gold)-(new&gold))
def receipts(a,env):
    for engine in a.engines.split(','):
        disk();out=a.out/('receipts-'+engine);out.mkdir(exist_ok=True)
        with (a.out/('receipts-'+engine+'.log')).open('w') as log:
            subprocess.run(['bb','receipts','--n','60','--ocr-engine',engine,'--measure-cost','--bin',str(a.bin),'--save',str(out/'summary.json')],
                cwd=ROOT,env=dict(env,EMON_RECEIPTS_OUT=str(out)),stdout=log,stderr=subprocess.STDOUT,check=True)
        print('receipts',engine,'complete',flush=True)
    receipt_audit(a)

def receipt_audit(a):
    fast_dir=a.fast_dir or a.out/'receipts-tesseract'
    old=json.loads((fast_dir/'summary.json').read_text());new=json.loads((a.out/'receipts-ppocr/summary.json').read_text())
    deltas={}
    for dataset in ['sroie','cord']:
        assert set(old[dataset]['rows'])==set(new[dataset]['rows'])
        rows=[]
        for ident in old[dataset]['rows']:
            gold=json.loads((ROOT/'corpus/_gold'/dataset/(ident+'.gold.json')).read_text())
            b=sections(read_text(fast_dir/(ident+'.bundle')));n=sections(read_text(a.out/'receipts-ppocr'/(ident+'.bundle')))
            lost=loss(json.loads(b['json']),json.loads(n['json']),gold['model'])
            rows.append(dict(id=ident,lost_gold=lost,old_matched=old[dataset]['rows'][ident]['matched'],new_matched=new[dataset]['rows'][ident]['matched']))
        deltas[dataset]=dict(before=old[dataset]['totals'],after=new[dataset]['totals'],rows=rows)
    (a.out/'receipt-losses.json').write_text(json.dumps(deltas,indent=2))

def controls(a,env):
    paths=[p for p in (ROOT/'test/controls/sweep3.txt').read_text().splitlines() if p and not p.startswith('#')]
    assert len(paths)==54
    out=a.out/'controls';out.mkdir(exist_ok=True);rows=[]
    existing={r['index']:r for r in json.loads((a.out/'controls.json').read_text())} if a.resume and (a.out/'controls.json').exists() else {}
    reference={r['index']:r for r in json.loads((a.reference_controls/'controls.json').read_text())} if a.reference_controls and (a.reference_controls/'controls.json').exists() else {}
    for i,path in enumerate(paths):
        if a.indices and i not in set(map(int,a.indices.split(","))):continue
        if i in existing:
            row=existing[i];assert row['source']==path
            for engine in ['tesseract','ppocr']:
                stem=out/f'{i:02}-{engine}'
                assert row[engine]['sha256']==hashlib.sha256(read_bytes(stem.with_suffix('.bundle'))).hexdigest()
                assert (stem.with_suffix('.replay')/'transcript.edn').is_file()
                if engine=='ppocr':
                    recorded=json.loads(read_text(stem.with_suffix('.evidence')))['ocr-engine']['model']
                    identity=''.join(map(chr,map(int,re.search(r':data \[([0-9 ]+)\]',(ROOT/'kin/ocrengine.kin').read_text()).group(1).split())))
                    assert recorded==identity,'cannot reuse controls from a different policy identity'
            rows.append(row);print('control',i,'reused verified candidate run',flush=True);continue
        row=dict(index=i,source=path)
        for engine in ['tesseract','ppocr']:
            disk();stem=out/f'{i:02}-{engine}'
            if engine=='tesseract' and a.reference_controls:
                refroot=a.reference_controls
                ref=refroot/'controls'/f'{i:02}-tesseract'
                if a.fallback_controls and (not a.fallback_indices or i in set(map(int,a.fallback_indices.split(',')))) and not ref.with_suffix('.json').is_file():
                    refroot=a.fallback_controls;ref=refroot/'controls'/f'{i:02}-tesseract'
                if ref.with_suffix('.bundle').is_file() and ref.with_suffix('.json').is_file() and (ref.with_suffix('.replay')/'transcript.edn').is_file():
                    verification=out/f'{i:02}-reference-verification.bundle'
                    with verification.open('wb') as proof:
                        check=subprocess.run([str(a.bin),'--ocr-engine=tesseract','--ocr=auto','--assets=figures','--out=bundle','--replay='+str(ref.with_suffix('.replay')),str(ROOT/path)],cwd=ROOT,env=env,stdout=proof,stderr=subprocess.PIPE)
                    equal=check.returncode==0 and verification.read_bytes()==ref.with_suffix('.bundle').read_bytes()
                    verification.unlink()
                    if equal:
                        if refroot==a.reference_controls and i in reference:metrics=dict(reference[i]['tesseract'])
                        else:
                            marker=re.search(r'^emon-cost:(.*)$',ref.with_suffix('.err').read_text(),re.M)
                            measured=json.loads(marker.group(1)) if marker else None
                            model=json.loads(ref.with_suffix('.json').read_text())
                            metrics=dict(cpu=measured['cpu_seconds'],peak_rss_bytes=measured['peak_rss_bytes'],wall=None,pages=len(model['pages']),sha256=hashlib.sha256(ref.with_suffix('.bundle').read_bytes()).hexdigest()) if measured else None
                        if metrics:
                            for suffix in ['.bundle','.err','.json','.evidence']:
                                shutil.copy2(ref.with_suffix(suffix),stem.with_suffix(suffix))
                            shutil.copytree(ref.with_suffix('.replay'),stem.with_suffix('.replay'),ignore=shutil.ignore_patterns('._*'),dirs_exist_ok=True)
                            metrics.update(reference=str(ref),replayed_byte_identically=True);row[engine]=metrics
                            compact(stem.with_suffix('.bundle'));compact(stem.with_suffix('.evidence'))
                            print('control',i,'fast reference replay verified',flush=True);continue
            flag='--tesseract' if engine=='tesseract' else '--ocr-engine=ppocr'
            command=[sys.executable,str(ROOT/'tools/oracle/ppocr/measure.py'),'--timeout','7100',str(a.bin),'--pdfium',flag,'--ocr=auto','--assets=figures','--out=bundle','--record='+str(stem)+'.replay',str(ROOT/path)]
            start=resource.getrusage(resource.RUSAGE_CHILDREN);wall=time.monotonic()
            with stem.with_suffix('.bundle').open('w') as stdout,stem.with_suffix('.err').open('w') as stderr:
                subprocess.run(command,cwd=ROOT,env=env,stdout=stdout,stderr=stderr,check=True,timeout=7200)
            end=resource.getrusage(resource.RUSAGE_CHILDREN)
            peak=re.search(r'^emon-cost:(.*)$',stem.with_suffix('.err').read_text(),re.M);assert peak
            measured=json.loads(peak.group(1))
            data=sections(stem.with_suffix('.bundle').read_text())
            for kind in ['json','evidence']:stem.with_suffix('.'+kind).write_text(data[kind])
            model=json.loads(data['json']);pages=len(model.get('pages',[]));assert pages
            row[engine]=dict(cpu=measured['cpu_seconds'],wall=time.monotonic()-wall,
                peak_rss_bytes=measured['peak_rss_bytes'],pages=pages,sha256=hashlib.sha256(stem.with_suffix('.bundle').read_bytes()).hexdigest())
            compact(stem.with_suffix('.bundle'));compact(stem.with_suffix('.evidence'))
        before=json.loads((out/f'{i:02}-tesseract.json').read_text());after=json.loads((out/f'{i:02}-ppocr.json').read_text())
        row['same_model']=before==after;row['same_bundle']=row['tesseract']['sha256']==row['ppocr']['sha256']
        old=represented_words(before);new=represented_words(after)
        row['lost']=dict(old-new);row['gained']=dict(new-old);row['tesseract_words']=sum(old.values());row['ppocr_words']=sum(new.values())
        rows.append(row);(a.out/'controls.json').write_text(json.dumps(rows,indent=2));print('control',i,'same' if row['same_bundle'] else 'changed',flush=True)

    (a.out/'controls.json').write_text(json.dumps(rows,indent=2))

def main():
    p=argparse.ArgumentParser(__doc__);p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--bin',type=pathlib.Path,required=True)
    p.add_argument('--fallback-controls',type=pathlib.Path,help='older complete fast reference, still requires exact replay');p.add_argument('--fallback-indices',default='',help='explicit indices eligible for the fallback reference');p.add_argument('--reference-controls',type=pathlib.Path,help='reuse a live fast reference only after byte-identical replay by this candidate; otherwise remeasure');p.add_argument('--resume',action='store_true',help='reuse hash-verified control runs from the same candidate');p.add_argument('--engines',default='tesseract,ppocr',help='measurement arms; reference reuse is explicit via --fast-dir');p.add_argument('--fast-dir',type=pathlib.Path,help='existing paired fast reference for receipt audit');p.add_argument('--indices',help='comma-separated zero-based controls for development trials; omit for acceptance');p.add_argument('--only',choices=['receipts','receipt-audit','controls','all'],default='all');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True);a.bin=a.bin.resolve()
    env=dict(os.environ,EMON_CS_SHARED_COMPILATION='false',OMP_THREAD_LIMIT='1')
    if a.only in ('receipts','all'):receipts(a,env)
    if a.only in ('controls','all'):controls(a,env)
    if a.only=='receipt-audit':receipt_audit(a)
if __name__=='__main__':main()
