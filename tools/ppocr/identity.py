#!/usr/bin/env python3
"""Check or regenerate the optional port's request identity from pinned policy."""
import argparse,hashlib,pathlib,re
root=pathlib.Path(__file__).resolve().parents[2]
names=['host/rust/src/ppocr.rs','host/rust/src/ppocr_runtime.rs','tools/ppocr/assets.edn','host/rust/src/ppocr_image.rs','host/rust/src/ppocr_ctc.rs',
       'host/rust/src/ppocr_pipeline.rs','host/rust/src/ppocr_ort.c','host/rust/src/ppocr_calibration.rs','kin/ocrport.kin','kin/ocrengine.kin']
h=hashlib.sha256()
for name in names:
    data=(root/name).read_bytes()
    if name=='kin/ocrengine.kin':
        data=re.sub(rb'(:data \[)[0-9\s]+(\])',rb'\1MODEL_IDENTITY\2',data,count=1)
    h.update(name.encode()+b'\0'+data)
identity='ppocr5-en-'+h.hexdigest()
p=argparse.ArgumentParser(__doc__);p.add_argument('--write',action='store_true');a=p.parse_args()
f=root/'kin/ocrengine.kin';s=f.read_text();m=re.search(r':data \[([0-9 ]+)\]',s);assert m
want=' '.join(str(ord(c)) for c in identity)
if a.write:f.write_text(s[:m.start(1)]+want+s[m.end(1):])
else:assert m.group(1)==want,'PP-OCR policy changed; regenerate identity and invalidate recordings'
print(identity)
