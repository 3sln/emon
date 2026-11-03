#!/usr/bin/env python3
"""Freeze reliability bins on source-line-aligned SROIE train words.
No corpus download; no baseline IDs; source-bearing outputs stay in scratch.
"""
import argparse, ast, collections, difflib, hashlib, json, pathlib, subprocess, time, unicodedata
ROOT=pathlib.Path(__file__).resolve().parents[3]
def normal(s):return unicodedata.normalize('NFC',s).casefold()
def bins(rows, table=None):
    out=[]
    for i in range(10):
        group=[r for r in rows if min(r['posterior']//100000,9)==i]
        n=len(group);k=sum(r['correct'] for r in group)
        out.append(dict(bin=i,n=n,correct=k,confidence=(1000*k+n//2)//n if n else 0,
                        raw_mean=sum(r['posterior'] for r in group)/n/1000000 if n else None))
    return out

def main():
    p=argparse.ArgumentParser();p.add_argument('--out',required=True);p.add_argument('--n',type=int,default=80);p.add_argument('--reuse',action='store_true');a=p.parse_args()
    out=pathlib.Path(a.out);out.mkdir(parents=True,exist_ok=True)
    baseline=json.loads((ROOT/'test/receipts/baseline.json').read_text())
    excluded={r[0] for s in baseline['sets'].values() for r in s['rows']}
    files=sorted((ROOT/'corpus/financial/sroie2019/train').glob('sroie_*.json'))[:a.n]
    assert len(files)==a.n and a.n>=40
    allrows=[];documents=[]
    for i,f in enumerate(files):
        ident='sroie-train-'+f.stem;assert ident not in excluded
        gold=json.loads(f.read_text());w=gold['image_size']['width'];h=gold['image_size']['height']
        t=time.monotonic();tsv=out/(f.stem+'.tsv')
        if a.reuse:
            output=tsv.read_text()
        else:
            raw=out/(f.stem+'.rgb');subprocess.run(['magick',str(f.with_suffix('.jpg')),'-depth','8','RGB:'+str(raw)],check=True)
            r=subprocess.run([str(ROOT/'.cache/ppocr/probe'),str(raw),str(w),str(h)],cwd=ROOT,check=True,capture_output=True,text=True)
            raw.unlink();output=r.stdout;tsv.write_text(output)
        byline=collections.defaultdict(list);unassigned=0
        for line in output.splitlines():
            text,coords,posterior=line.rsplit('\t',2);x,y,bw,bh=ast.literal_eval(coords)
            if bw<=0 or bh<=0:continue
            candidates=[]
            for j,(x0,y0,x1,y1) in enumerate(gold['bboxes']):
                overlap=max(0,min(x+bw,x1)-max(x,x0))/min(bw,x1-x0)*max(0,min(y+bh,y1)-max(y,y0))/min(bh,y1-y0)
                if overlap>=0.8 and x0<=x+bw/2<=x1 and y0<=y+bh/2<=y1:candidates.append((overlap,j))
            candidates.sort(reverse=True)
            if not candidates or (len(candidates)>1 and candidates[0][0]-candidates[1][0]<0.1):
                unassigned+=1;continue
            byline[candidates[0][1]].append(dict(text=text,x=x,posterior=int(posterior)))
        rows=[]
        for j,pred in byline.items():
            pred.sort(key=lambda r:r['x']);actual=[normal(r['text']) for r in pred];expected=[normal(s) for s in gold['words'][j].split()]
            equal=set()
            for block in difflib.SequenceMatcher(a=expected,b=actual,autojunk=False).get_matching_blocks():equal.update(range(block.b,block.b+block.size))
            for k,row in enumerate(pred):rows.append(dict(id=ident,line=j,posterior=row['posterior'],correct=int(k in equal),split='fit' if i<a.n//2 else 'validation'))
        allrows.extend(rows);documents.append(dict(id=ident,aligned=len(rows),unassigned=unassigned,seconds=time.monotonic()-t))
        print(ident,len(rows),flush=True)
    fit=[r for r in allrows if r['split']=='fit'];validation=[r for r in allrows if r['split']=='validation']
    fitbins=bins(fit);table=[r['confidence'] for r in fitbins]
    def metrics(rows):
        n=len(rows);assert n
        raw=sum((r['posterior']/1000000-r['correct'])**2 for r in rows)/n
        cal=sum((table[min(r['posterior']//100000,9)]/1000-r['correct'])**2 for r in rows)/n
        ece=sum(g['n']*abs(table[g['bin']]/1000-g['correct']/g['n']) for g in bins(rows) if g['n'])/n
        return dict(words=n,raw_brier=raw,calibrated_brier=cal,calibrated_ece=ece,bins=bins(rows))
    result=dict(schema=1,reused_ocr_outputs=a.reuse,document_timing_scope=('Label reconstruction wall time with --reuse; not inference CPU or cost measurement.' if a.reuse else 'Decode, probe and label construction wall time; not an isolated inference CPU measurement.'),policy='source-line overlap/min(side) >= .8 and center inside gold line; ambiguous overlaps excluded; NFC/casefold ordered token alignment within annotated line; 10 equal posterior bins; empty bins conservatively zero',
        limitation='SROIE source annotations are line-aligned, not individual-word polygons. This is not an independent manual source audit, and requires review before production confidence use.',
        model_pins_sha256=hashlib.sha256((ROOT/'tools/ppocr/assets.edn').read_bytes()).hexdigest(),
        confidence=table,fit=metrics(fit),validation=metrics(validation),documents=documents)
    (out/'aligned-labels.json').write_text(json.dumps(allrows,indent=2));(out/'calibration.json').write_text(json.dumps(result,indent=2)+'\n')
if __name__=='__main__':main()
