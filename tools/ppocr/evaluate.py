#!/usr/bin/env python3
"""Paired OP1 live measurements; outputs (including source text) stay external."""
import argparse,collections,hashlib,json,os,pathlib,re,resource,shutil,subprocess,sys,time,unicodedata
ROOT=pathlib.Path(__file__).resolve().parents[2]
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
    for engine in ['tesseract','ppocr']:
        disk();out=a.out/('receipts-'+engine);out.mkdir(exist_ok=True)
        with (a.out/('receipts-'+engine+'.log')).open('w') as log:
            subprocess.run(['bb','receipts','--n','60','--ocr-engine',engine,'--measure-cost','--bin',str(a.bin),'--save',str(out/'summary.json')],
                cwd=ROOT,env=dict(env,EMON_RECEIPTS_OUT=str(out)),stdout=log,stderr=subprocess.STDOUT,check=True)
        print('receipts',engine,'complete',flush=True)
    receipt_audit(a)

def receipt_audit(a):
    old=json.loads((a.out/'receipts-tesseract/summary.json').read_text());new=json.loads((a.out/'receipts-ppocr/summary.json').read_text())
    deltas={}
    for dataset in ['sroie','cord']:
        assert set(old[dataset]['rows'])==set(new[dataset]['rows'])
        rows=[]
        for ident in old[dataset]['rows']:
            gold=json.loads((ROOT/'corpus/_gold'/dataset/(ident+'.gold.json')).read_text())
            b=sections((a.out/'receipts-tesseract'/(ident+'.bundle')).read_text());n=sections((a.out/'receipts-ppocr'/(ident+'.bundle')).read_text())
            lost=loss(json.loads(b['json']),json.loads(n['json']),gold['model'])
            rows.append(dict(id=ident,lost_gold=lost,old_matched=old[dataset]['rows'][ident]['matched'],new_matched=new[dataset]['rows'][ident]['matched']))
        deltas[dataset]=dict(before=old[dataset]['totals'],after=new[dataset]['totals'],rows=rows)
    (a.out/'receipt-losses.json').write_text(json.dumps(deltas,indent=2))

def controls(a,env):
    paths=[p for p in (ROOT/'test/controls/sweep3.txt').read_text().splitlines() if p and not p.startswith('#')]
    assert len(paths)==54
    out=a.out/'controls';out.mkdir(exist_ok=True);rows=[]
    for i,path in enumerate(paths):
        row=dict(index=i,source=path)
        for engine in ['tesseract','ppocr']:
            disk();stem=out/f'{i:02}-{engine}'
            flag='--tesseract' if engine=='tesseract' else '--ocr-engine=ppocr'
            command=[sys.executable,str(ROOT/'tools/ppocr/measure.py'),str(a.bin),'--pdfium',flag,'--ocr=auto','--assets=figures','--out=bundle','--record='+str(stem)+'.replay',str(ROOT/path)]
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
        before=json.loads((out/f'{i:02}-tesseract.json').read_text());after=json.loads((out/f'{i:02}-ppocr.json').read_text())
        row['same_model']=before==after;row['same_bundle']=row['tesseract']['sha256']==row['ppocr']['sha256']
        old=represented_words(before);new=represented_words(after)
        row['lost']=dict(old-new);row['gained']=dict(new-old);row['tesseract_words']=sum(old.values());row['ppocr_words']=sum(new.values())
        rows.append(row);(a.out/'controls.json').write_text(json.dumps(rows,indent=2));print('control',i,'same' if row['same_bundle'] else 'changed',flush=True)

def main():
    p=argparse.ArgumentParser(__doc__);p.add_argument('--out',type=pathlib.Path,required=True);p.add_argument('--bin',type=pathlib.Path,required=True)
    p.add_argument('--only',choices=['receipts','receipt-audit','controls','all'],default='all');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True);a.bin=a.bin.resolve()
    env=dict(os.environ,EMON_CS_SHARED_COMPILATION='false',OMP_THREAD_LIMIT='1')
    if a.only in ('receipts','all'):receipts(a,env)
    if a.only in ('controls','all'):controls(a,env)
    if a.only=='receipt-audit':receipt_audit(a)
if __name__=='__main__':main()
