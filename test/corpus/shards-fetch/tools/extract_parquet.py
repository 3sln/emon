#!/usr/bin/env python3
"""Extract a HF parquet shard: binary image/pdf columns -> files, other columns -> <stem>.json sidecar.
usage: extract_parquet.py shard.parquet outdir [prefix] [--max N]"""
import sys, json, os, pyarrow.parquet as pq
def ext(b):
    if b[:4]==b'%PDF': return 'pdf'
    if b[:8]==b'\x89PNG\r\n\x1a\n': return 'png'
    if b[:3]==b'\xff\xd8\xff': return 'jpg'
    if b[:4] in (b'II*\x00', b'MM\x00*'): return 'tif'
    return 'bin'
def conv(o):
    if isinstance(o,(bytes,bytearray)): return {'_bytes':len(o)}
    if isinstance(o,dict): return {k:conv(v) for k,v in o.items()}
    if isinstance(o,list): return [conv(v) for v in o]
    return o
a=sys.argv[1:]; mx=None
if '--max' in a: i=a.index('--max'); mx=int(a[i+1]); del a[i:i+2]
src,out=a[0],a[1]; prefix=a[2] if len(a)>2 else os.path.splitext(os.path.basename(src))[0]+'_'
os.makedirs(out,exist_ok=True)
pf=pq.ParquetFile(src); n=0
for batch in pf.iter_batches(batch_size=16):
    for row in batch.to_pylist():
        stem=f"{prefix}{n:05d}"; meta={}
        for k,v in row.items():
            if isinstance(v,dict) and isinstance(v.get('bytes'),(bytes,bytearray)):
                b=v['bytes']; fn=f"{stem}.{k if k not in('image','img') else 'page'}.{ext(b)}" if k not in ('image','img','pdf') else f"{stem}.{ext(b)}"
                open(os.path.join(out,fn),'wb').write(b); meta[k]={'file':fn}
            elif isinstance(v,(bytes,bytearray)):
                fn=(f"{stem}.{ext(v)}" if k=="pdf" else f"{stem}.{k}.{ext(v)}"); open(os.path.join(out,fn),'wb').write(v); meta[k]={'file':fn}
            else: meta[k]=conv(v)
        json.dump(meta,open(os.path.join(out,stem+'.json'),'w'),ensure_ascii=False)
        n+=1
        if mx and n>=mx: break
    if mx and n>=mx: break
print(f"{src}: {n} rows -> {out}")
