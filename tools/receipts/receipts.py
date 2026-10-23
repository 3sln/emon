"""Live, non-gate receipt measurement. Corpus text stays in external scratch."""
import argparse, collections, json, os, pathlib, re, subprocess, unicodedata
ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRATCH = pathlib.Path(os.environ.get('EMON_RECEIPTS_OUT','/Volumes/SSK SSD/emon-scratch/rc'))
def words(s):
    return unicodedata.normalize('NFC', s).casefold().split()
def walk(n):
    if isinstance(n, dict):
        yield n
        for c in n.get('children', []): yield from walk(c)
def text(n):
    if n.get('text') is not None: return n['text']
    return ' '.join(text(c) for c in n.get('children', []))
def units(model):
    # A leaf text node or a complete table row, never a document/table ancestor.
    return [text(n) for root in [model.get('body', {})]+model.get('furniture',[]) for n in walk(root) if n.get('kind') in ('row','tr') or ('text' in n and not n.get('children'))]
def intact(s, us):
    needle = words(s)
    return bool(needle) and any(any(h[i:i+len(needle)] == needle for i in range(len(h)-len(needle)+1)) for h in map(words,us))
def angle(degrees):
    return abs((degrees+180)%360-180)
def overlap(a,b): return sum((collections.Counter(a)&collections.Counter(b)).values())
def sample(files,n):
    # Split x fixed transcription-length bands; evenly spaced within every stratum.
    groups=collections.defaultdict(list)
    for f in files:
        g=json.loads(f.read_text()); size=len(words(text(g['model']['body'])))
        groups[(g['id'].split('-')[1], min(size//80,2))].append((size,f))
    chosen=[]; groups=[sorted(v) for _,v in sorted(groups.items())]
    for i in range(n):
        eligible=[j for j,v in enumerate(groups) if len(v)>sum(k==j for k,_ in chosen)]
        if not eligible: break
        j=eligible[i%len(eligible)]; chosen.append((j,None))
    out=[]
    for j,v in enumerate(groups):
        k=sum(x==j for x,_ in chosen)
        out.extend(v[i*len(v)//k][1] for i in range(k))
    return sorted(out)
def annotations(g):
    image=ROOT/'corpus'/g['provenance']['variants']['image']
    native=json.loads(image.with_suffix('.json').read_text())
    entities=[]; items=[]
    if g['id'].startswith('sroie'):
        entities=[(k,str(v)) for k,v in native.get('entities',{}).items() if v]
    else:
        gt=native['ground_truth']; gt=json.loads(gt) if isinstance(gt,str) else gt
        for line in gt.get('valid_line',[]):
            cat=line.get('category',''); t=' '.join(w['text'] for w in line['words'])
            if cat.startswith(('menu.','total.','sub_total.')): entities.append((cat,t))
        groups=collections.defaultdict(list)
        for line in gt.get('valid_line',[]):
            if line.get('category') in ('menu.nm','menu.cnt','menu.unitprice','menu.price'):
                groups[(line.get('group_id'),line.get('sub_group_id',0))].append(line)
        for lines in groups.values():
            cats={line['category'] for line in lines}
            if 'menu.nm' not in cats or not cats.intersection(('menu.price','menu.unitprice')): continue
            rows=collections.defaultdict(list)
            for line in lines:
                for w in line['words']: rows[w['row_id']].append(w)
            ordered=sorted(rows.values(),key=lambda ws:min(min(w['quad']['y'+str(i)] for i in range(1,5)) for w in ws))
            items.append([w['text'] for ws in ordered for w in sorted(ws,key=lambda w:min(w['quad']['x'+str(i)] for i in range(1,5)))])
    return image,entities,items
def score(g,model,ev,entities,items):
    us=units(model); expected=words(text(g['model']['body'])); actual=words(' '.join(text(root) for root in [model.get('body',{})]+model.get('furniture',[])))
    dropped=words(' '.join(text(x) for x in model.get('dropped',[])))
    gc=collections.Counter(expected); covered=collections.Counter(actual+dropped)
    nums=lambda ws:[w for w in ws if re.search(r'\d',w)]
    line_us=[' '.join(w.get('text','') for w in ev.get('words',[])[l['words'][0]:sum(l['words'])]) for l in ev.get('lines',[]) if 'words' in l]
    row_us=[text(n) for n in walk(model.get('body',{})) if n.get('kind')=='row']
    result=dict(gold_words=len(expected),model_words=len(actual),matched=overlap(expected,actual),
        silent_words=sum((gc-covered).values()),empty=int(not actual),near_empty=int(len(actual)<max(3,len(expected)*.1)),
        entities=len(entities),entities_ok=sum(intact(t,us) for _,t in entities),items=len(items),
        items_ok=sum(intact(' '.join(item),us) for item in items),
        line_items_ok=sum(intact(' '.join(item),row_us) or (intact(' '.join(item),line_us) and intact(' '.join(item),us)) for item in items),
        silent_regions=sum(bool(words(text(n))) and not overlap(words(text(n)),actual+dropped) for n in g['model']['body'].get('children',[])),
        numeric=len(nums(expected)),numeric_ok=overlap(nums(expected),nums(actual)),
        ocr_missing_words=sum((gc-collections.Counter(words(' '.join(w.get('text','') for w in ev.get('owords',[]))))).values()),
        ocr_present_silent_words=sum(((gc-covered)&collections.Counter(words(' '.join(w.get('text','') for w in ev.get('owords',[]))))).values()),
        skewed_lines=sum(angle(l.get('rot',0))>2 for l in ev.get('lines',[])),
        rotation_retry_runs=sum(r.get('pass')=='re-rotated' for r in ev.get('oruns',[])),
        rotated_pages=sum(p.get('rot',0)!=0 for p in model.get('pages',[])))
    for label,pattern in [('decimal',r'\d[.,]\d'),('minus',r'[-−]\d'),('currency',r'[$€£¥]|^(?:rm|rp|eur|usd)$')]:
        target=[w for w in expected if re.search(pattern,w)]
        result[label]=len(target); result[label+'_ok']=overlap(target,actual)
    # Preserve per-type denominators without receipt content.
    for k,t in entities:
        result['entity_'+k]=result.get('entity_'+k,0)+1
        result['entity_'+k+'_ok']=result.get('entity_'+k+'_ok',0)+int(intact(t,us))
    return result
def main():
    p=argparse.ArgumentParser(__doc__)
    p.add_argument('--n',type=int,default=60)
    p.add_argument('--set',default='sroie,cord')
    p.add_argument('--report',action='store_true')
    p.add_argument('--bin',default=os.environ.get('EMON_RECEIPTS_BIN','/private/tmp/emon-rc/live/release/emon-rs'))
    p.add_argument('--rescore',action='store_true')
    transcripts=p.add_mutually_exclusive_group()
    transcripts.add_argument('--record-transcripts',action='store_true')
    transcripts.add_argument('--replay-transcripts',action='store_true')
    p.add_argument('--save',default=str(SCRATCH/'baseline.json'))
    a=p.parse_args()
    if a.n<1: p.error('--n must be positive')
    if a.rescore and (a.record_transcripts or a.replay_transcripts):
        p.error('--rescore cannot record or replay transcripts')
    if a.report:
        print(pathlib.Path(a.save).read_text()); return
    SCRATCH.mkdir(parents=True,exist_ok=True); report={}
    for dataset in a.set.split(','):
        if dataset not in ('sroie','cord'): p.error('supported sets: sroie,cord')
        files=[f for f in (ROOT/'corpus/_gold'/dataset).glob('*.gold.json') if not f.name.startswith('._') and (dataset!='sroie' or '-test-' in f.name)]
        if not files: raise RuntimeError('missing corpus gold: '+dataset)
        rows={}
        for f in sample(files,a.n):
            g=json.loads(f.read_text()); image,entities,items=annotations(g)
            transcript=SCRATCH/(g['id']+'.replay')
            extra=([f'--record={transcript}'] if a.record_transcripts else
                   [f'--replay={transcript}'] if a.replay_transcripts else [])
            if a.replay_transcripts and not (transcript/'transcript.edn').is_file():
                raise RuntimeError('missing receipt replay: '+str(transcript))
            r=(subprocess.CompletedProcess([],0,(SCRATCH/(g['id']+'.bundle')).read_text(),'') if a.rescore else subprocess.run([a.bin,'--tesseract','--out=bundle',*extra,str(image)],capture_output=True,text=True,timeout=180))
            if not a.rescore: (SCRATCH/(g['id']+'.bundle')).write_text(r.stdout)
            if r.returncode: raise RuntimeError(g['id']+': '+r.stderr[-1000:])
            sections=dict(re.findall(r'== ([^\n]+)\n(.*?)(?=\n== |\Z)',r.stdout,re.S))
            model=json.loads(sections['json']); ev=json.loads(sections.get('evidence','{}'))
            rows[g['id']]=score(g,model,ev,entities,items)
            print(dataset,len(rows),'/',min(a.n,len(files)),g['id'],flush=True)
        totals=dict(sum((collections.Counter(v) for v in rows.values()),collections.Counter()))
        for k,num,den in [('word_recall','matched','gold_words'),('word_precision','matched','model_words'),('entity_recall','entities_ok','entities'),('item_integrity','items_ok','items'),('numeric_recall','numeric_ok','numeric'),('line_item_integrity','line_items_ok','items')]:
            totals[k]=totals.get(num,0)/totals[den] if totals.get(den) else None
        report[dataset]={'documents':len(rows),'totals':totals,'rows':rows}
        pathlib.Path(a.save).write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(totals),flush=True)
if __name__=='__main__': main()
