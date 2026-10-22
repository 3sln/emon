#!/usr/bin/env python3
"""Build derived/synthetic format & degradation variants from corpus sources (deterministic).
Ground truth = the source item's sidecar JSON (see each output's .json 'source')."""
import os, json, random, subprocess, glob, shutil
from PIL import Image, ImageDraw, ImageFilter, ImageFont
random.seed(7)
C=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
def P(*a): return os.path.join(C,*a)
out=P('formats','derived'); os.makedirs(out,exist_ok=True)
sro=sorted(glob.glob(P('financial/sroie2019/test/*.jpg')))[:24]
inv=sorted(glob.glob(P('financial/katanaml-invoices/test/*.jpg')))[:8]
funsd=sorted(glob.glob(P('forms/funsd/dataset/testing_data/images/*.png')))[:4]
def gt(p): return os.path.relpath(os.path.splitext(p)[0]+'.json',C)
notes={}
# 1. multipage TIFF (LZW) of 5 receipts; and G4 bilevel multipage
ims=[Image.open(p).convert('RGB') for p in sro[:5]]
ims[0].save(f'{out}/multipage-receipts-rgb-lzw.tif',save_all=True,append_images=ims[1:],compression='tiff_lzw')
notes['multipage-receipts-rgb-lzw.tif']=[gt(p) for p in sro[:5]]
bl=[i.convert('L').point(lambda v:255 if v>150 else 0).convert('1') for i in ims]
bl[0].save(f'{out}/multipage-receipts-bilevel-g4.tif',save_all=True,append_images=bl[1:],compression='group4',dpi=(200,200))
notes['multipage-receipts-bilevel-g4.tif']=[gt(p) for p in sro[:5]]
# 2. same invoice in PNG/JPEG/TIFF
for p in inv[:3]:
    b=os.path.basename(p)[:-4]; im=Image.open(p).convert('RGB')
    im.save(f'{out}/{b}.png'); im.save(f'{out}/{b}.q40.jpg',quality=40); im.save(f'{out}/{b}.tif',compression='tiff_lzw')
    notes[f'{b}.png|.q40.jpg|.tif']=[gt(p)]
# 3. image-only (scanned) multi-page PDFs
f=[Image.open(p).convert('RGB') for p in funsd]
f[0].save(f'{out}/scanned-funsd-4p.pdf',save_all=True,append_images=f[1:],resolution=100)
notes['scanned-funsd-4p.pdf']=[gt(p) for p in funsd] if False else [os.path.relpath(p,C) for p in funsd]
r=[Image.open(p).convert('RGB') for p in sro[5:11]]
r[0].save(f'{out}/scanned-receipts-6p.pdf',save_all=True,append_images=r[1:],resolution=150)
notes['scanned-receipts-6p.pdf']=[gt(p) for p in sro[5:11]]
# 4. PDFs with hidden OCR text layer (tesseract 'pdf' output = image + invisible text)
for p in sro[:3]+inv[:2]:
    b=os.path.basename(p)[:-4]
    subprocess.run(['tesseract',p,f'{out}/{b}.ocr-layer','pdf'],stderr=subprocess.DEVNULL)
    notes[f'{b}.ocr-layer.pdf']=[gt(p),'text layer produced by tesseract (may contain OCR errors; compare with GT)']
# 5. degradations
def stamp(im):
    d=ImageDraw.Draw(im); w,h=im.size; r=min(w,h)//5; x,y=random.randint(r,w-r),random.randint(r,h-r)
    d.ellipse([x-r,y-r,x+r,y+r],outline=(200,30,30),width=4); d.ellipse([x-r+8,y-r+8,x+r-8,y+r-8],outline=(200,30,30),width=2)
    d.text((x-r//2,y-6),'APPROVED',fill=(200,30,30)); return im
def watermark(im):
    w,h=im.size; ov=Image.new('RGBA',im.size,(0,0,0,0)); d=ImageDraw.Draw(ov)
    try: fnt=ImageFont.truetype('/System/Library/Fonts/Helvetica.ttc',max(24,w//8))
    except Exception: fnt=None
    d.text((w//10,h//2),'CONFIDENTIAL',fill=(120,120,120,90),font=fnt)
    return Image.alpha_composite(im.convert('RGBA'),ov.rotate(30,resample=Image.BICUBIC)).convert('RGB')
def noise(im):
    g=im.convert('L'); px=g.load(); w,h=g.size
    for _ in range(w*h//25): px[random.randrange(w),random.randrange(h)]=random.choice((0,255))
    return g.convert('RGB')
def skew(im,a): return im.rotate(a,expand=True,fillcolor=(255,255,255),resample=Image.BICUBIC)
def lowres(im): return im.resize((im.width//3,im.height//3)).filter(ImageFilter.GaussianBlur(0.6)).resize(im.size)
ops={'skew5':lambda i:skew(i,5),'skew-3':lambda i:skew(i,-3),'noise':noise,'watermark':watermark,'stamp':stamp,'lowres':lowres,'blur':lambda i:i.filter(ImageFilter.GaussianBlur(2))}
for i,p in enumerate(sro[11:23]+inv[3:8]):
    name=list(ops)[i%len(ops)]; b=os.path.basename(p)[:-4]
    ops[name](Image.open(p).convert('RGB')).save(f'{out}/{b}.{name}.png'); notes[f'{b}.{name}.png']=[gt(p),f'synthetic degradation: {name}']
json.dump(notes,open(f'{out}/derived_sources.json','w'),indent=1)
print(len(notes),'derived groups')
