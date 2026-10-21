// Sample previews use the same pdfium adapter as the browser. Cache keys include input, renderer and dimensions.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { pdfiumRenderer } from '../../host/js/src/pdfium.js';
const arg = (k,d) => process.argv.includes(k) ? process.argv[process.argv.indexOf(k)+1] : d;
const out = arg('--out','.cache/site/out');
const data = JSON.parse(fs.readFileSync(arg('--data','.cache/site/data.json'),'utf8'));
const dir = (fs.existsSync('.cache/pdfium')?fs.readdirSync('.cache/pdfium'):[]).find(x => fs.existsSync(`.cache/pdfium/${x}/wasm/lib/pdfium.mjs`));
const manifest = {hero:null};
fs.mkdirSync(`${out}/assets/renders`,{recursive:true});
fs.mkdirSync('.cache/site/renders',{recursive:true});
function crc(b) { let c=0xffffffff; for(const x of b){c^=x;for(let k=0;k<8;k++)c=(c>>>1)^((c&1)?0xedb88320:0);}return (c^0xffffffff)>>>0; }
function chunk(type,b){const t=Buffer.from(type),n=Buffer.alloc(4),c=Buffer.alloc(4);n.writeUInt32BE(b.length);c.writeUInt32BE(crc(Buffer.concat([t,b])));return Buffer.concat([n,t,b,c]);}
function png(w,h,rgb){
 // A fixed palette retains 32 antialiasing grays and a 6×6×6 colour cube. Indexed previews stay small without an image dependency.
 const palette=Buffer.alloc(248*3);for(let i=0;i<32;i++)palette.fill(Math.round(i*255/31),i*3,i*3+3);
 for(let r=0;r<6;r++)for(let g=0;g<6;g++)for(let b=0;b<6;b++)palette.set([r*51,g*51,b*51],(32+r*36+g*6+b)*3);
 const head=Buffer.alloc(13);head.writeUInt32BE(w);head.writeUInt32BE(h,4);head[8]=8;head[9]=3;
 const rows=Buffer.alloc(h*(w+1));for(let y=0;y<h;y++)for(let x=0;x<w;x++){const i=(y*w+x)*3,r=rgb[i],g=rgb[i+1],b=rgb[i+2];rows[y*(w+1)+x+1]=Math.max(r,g,b)-Math.min(r,g,b)<18?Math.round((r+g+b)/3*31/255):32+Math.round(r/51)*36+Math.round(g/51)*6+Math.round(b/51);}
 return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',head),chunk('PLTE',palette),chunk('IDAT',zlib.deflateSync(rows,{level:9})),chunk('IEND',Buffer.alloc(0))]);
}
function crop(image,x,y,w,h){const b=Buffer.alloc(w*h*3);for(let row=0;row<h;row++) b.set(image.rgb.subarray(((y+row)*image.width+x)*3,((y+row)*image.width+x+w)*3),row*w*3);return {width:w,height:h,rgb:b};}
let port;
if(dir && !process.argv.includes('--skip')){
 const base=path.resolve(`.cache/pdfium/${dir}/wasm`);const mod=await import(pathToFileURL(`${base}/lib/pdfium.mjs`));
 port=pdfiumRenderer({module:a=>mod.default(a,{require:createRequire(import.meta.url),dirname:`${base}/lib`}),wasm:fs.readFileSync(`${base}/lib/pdfium.wasm`)});
}else console.log('SKIP renders -- pdfium-wasm not fetched (or fast build)');
for(const sample of data.samples){
 const bytes=fs.readFileSync(`samples/${sample.src}`); const model=JSON.parse(fs.readFileSync(`samples/${sample.id}.json`));
 const record={pages:[]};manifest[sample.id]=record;
 if(!sample.src.endsWith('.pdf')){
  const p=model.pages[0]; record.thumb={src:`/samples/${sample.src}`,width:Math.round(p.w),height:Math.round(p.h)}; record.pages=[record.thumb];continue;
 }
 if(!port)continue;
 const hash=crypto.createHash('sha256').update(bytes).update(dir+'indexed-v1').digest('hex').slice(0,20);
 const doc=await port.open({bytes},{});
 async function render(n,width,label,region){
  const pg=model.pages[n],dpi=Math.round(width/pg.w*72);
  const key=`${hash}-${n}-${dpi}-${label}.png`,cached=`.cache/site/renders/${key}`;
  let w=Math.round(pg.w*dpi/72),h=Math.round(pg.h*dpi/72);
  if(region){w=region[2];h=region[3];}
  if(!fs.existsSync(cached)){
   let image=await doc.render({page:n,dpi,layer:0}); if(!image.rgb)throw Error(`${sample.id}: ${JSON.stringify(image)}`);
   if(region)image=crop(image,...region);
   fs.writeFileSync(cached,png(image.width,image.height,image.rgb));
  }
  fs.copyFileSync(cached,`${out}/assets/renders/${key}`);return {src:`/assets/renders/${key}`,width:w,height:h};
 }
 for(let n=0;n<model.pages.length;n++)record.pages.push(await render(n,1000,'page'));
 record.thumb=await render(0,400,'thumb');
 if(sample.id==='table-spans'){
  const a=await render(0,612,'hero',[60,80,492,225]);const b=await render(0,1224,'hero2',[120,160,984,450]);manifest.hero={...a,src2:b.src};
 }
 doc.close();
}
await port?.terminate();fs.writeFileSync('.cache/site/renders.json',JSON.stringify(manifest));
console.log(`ok sample previews: ${Object.values(manifest).filter(x=>x?.thumb).length} samples`);
