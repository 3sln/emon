import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
const args=process.argv.slice(2);
const voids=new Set('area base br col embed hr img input link meta param source track wbr'.split(' '));
const decode=s=>s.replace(/&amp;/g,'&').replace(/&quot;/g,'"');
async function files(dir){let result=[];for(const e of await fs.readdir(dir,{withFileTypes:true})){const p=path.join(dir,e.name);result.push(...e.isDirectory()?await files(p):[p]);}return result;}
export function htmlErrors(content,file='page') {
 const errors=[];const fail=s=>errors.push(`${file}: ${s}`);
 if(!/^<!doctype html>/i.test(content))fail('doctype must come first');
 const head=content.slice(0,content.indexOf('</head>'));
 if(!/<meta charset=["']utf-8["']>/i.test(head)||!/<meta name=["']viewport["']/i.test(head))fail('charset or viewport missing');
 if(head.indexOf('charset')>head.indexOf('viewport'))fail('charset must precede viewport');
 if(!/<link\b[^>]*rel=["']canonical["']/i.test(head))fail('canonical missing');
 const clean=content.replace(/<!--[\s\S]*?-->/g,'').replace(/<(script|style|pre|template)\b[^>]*>[\s\S]*?<\/\1>/gi,'');
 for(const tag of ['title','h1','main'])if((clean.match(new RegExp(`<${tag}(?:\\s|>)`,'g'))||[]).length!==1)fail(`expected exactly one ${tag}`);
 const ids=[...clean.matchAll(/\bid=["']([^"']+)["']/g)].map(m=>m[1]);if(new Set(ids).size!==ids.length)fail('duplicate ids');
 const stack=[];for(const m of clean.matchAll(/<\/?([a-z][\w-]*)\b[^>]*>/gi)){const tag=m[1].toLowerCase();if(voids.has(tag)||m[0].endsWith('/>'))continue;if(m[0][1]==='/'){if(stack.pop()!==tag)fail(`unbalanced ${tag}`);}else stack.push(tag);}if(stack.length)fail('unclosed '+stack.join(','));
 if(/\bhref=["']\s*["']/.test(clean))fail('empty href');
 for(const m of content.matchAll(/<img\b[^>]*>/gi))for(const attr of ['alt','width','height'])if(!new RegExp(`\\b${attr}=["'][^"']*["']`).test(m[0]))fail(`image missing ${attr}`);
 return errors;
}
function luminance(hex){const values=hex.match(/[\da-f]{2}/gi).map(x=>parseInt(x,16)/255).map(x=>x<=.04045?x/12.92:((x+.055)/1.055)**2.4);return values[0]*.2126+values[1]*.7152+values[2]*.0722;}
export function contrastErrors(css){const errors=[];for(const match of css.matchAll(/--bg\s*:\s*(#[\da-f]{6})[\s\S]*?--accent-ink\s*:\s*(#[\da-f]{3,6})/gi)){const tokens=Object.fromEntries([...match[0].matchAll(/--([\w-]+)\s*:\s*(#[\da-f]{3,6})/gi)].map(m=>[m[1],m[2].length===4?'#'+[...m[2].slice(1)].map(c=>c+c).join(''):m[2]]));for(const [a,b,min] of [['ink','bg',4.5],['muted','bg',4.5],['accent','bg',4.5],['accent-ink','accent',4.5],['ink','surface',4.5],['muted','surface',4.5],['accent','surface',4.5],['accent','bg',3],['accent','surface',3]])if(tokens[a]&&tokens[b]){const l=[luminance(tokens[a]),luminance(tokens[b])].sort((a,b)=>b-a);if((l[0]+.05)/(l[1]+.05)<min)errors.push(`contrast ${a}/${b} below ${min}:1`);}}for(const match of css.matchAll(/\.mark-(heading|paragraph|header-cell|body-cell)\s*\{[^}]*stroke\s*:\s*(#[\da-f]{6})/gi)){const contrast=1.05/(luminance(match[2])+.05);if(contrast<3)errors.push(`contrast markup ${match[1]}/white below 3:1`);}return errors;}
export function budgetErrors(file,size){let max;if(/assets\/site\.[\w]+\.css$/.test(file))max=24*1024;if(/assets\/site\.[\w]+\.js$/.test(file))max=8*1024;if(/(?:^|\/)docs\/.*index\.html$/.test(file))max=160*1024;if(/^(?:(?:es|pt-br|fr|de|ja|zh-hans|ko)\/)?index\.html$/.test(file))max=64*1024;if(/assets\/renders\/.*\.png$/.test(file))max=/hero/.test(file)?160*1024:200*1024;return max&&size>max?[`${file}: ${size} bytes exceeds ${max}`]:[];}
export async function check(root){const errors=[];const all=await files(root);const cache=new Map();const sizes=new Map();let worstFirst=0;for(const file of all){const rel=path.relative(root,file);const size=(await fs.stat(file)).size;sizes.set(rel,size);errors.push(...budgetErrors(rel,size));if(!file.endsWith('.html'))continue;const content=await fs.readFile(file,'utf8');cache.set(file,content);if(!rel.startsWith('samples/')||rel.endsWith('/index.html'))errors.push(...htmlErrors(content,rel));}
 const metadata=await fs.readFile('.cache/site/data.json','utf8').then(JSON.parse).catch(()=>null);
 if(metadata && path.resolve(metadata.out)===root){
  const languages=metadata.languages;
  for(const [file,content] of cache){
   const relative=path.relative(root,file);if(/^samples\/[^/]+\.html$/.test(relative))continue;
   const prefix=relative.split('/')[0];const expected=languages.find(l=>l.path===prefix)?.code??'en';
   if(!new RegExp(`<html\\b[^>]*lang=["']${expected}["']`).test(content))errors.push(relative+': wrong document language');
   const alternates=new Map([...content.matchAll(/<link\b[^>]*rel=["']alternate["'][^>]*>/g)].map(m=>[m[0].match(/hreflang=["']([^"']+)/)?.[1],decode(m[0].match(/href=["']([^"']+)/)?.[1]??'')]));
   const logical='/'+relative.replace(/index\.html$/,'').replace(new RegExp('^'+(languages.find(l=>l.code===expected)?.path||'__en__')+'/'),'');
   const isTranslated=!relative.startsWith('docs/')&&!/^(?:licence|notices|install)\//.test(relative)&&relative!=='404.html'&&relative!=='en/index.html'&&!/^(?:es|pt-br|fr|de|ja|zh-hans|ko)\/install\//.test(relative);
   if(isTranslated){
    for(const lang of [...languages,{code:'x-default',path:''}]){
     const href='https://emon.3sln.com'+(lang.path?'/'+lang.path:'')+logical;
     if(alternates.get(lang.code)!==href)errors.push(relative+': missing or incorrect alternate '+lang.code);
     const target=path.join(root,new URL(href).pathname,'index.html');const targetContent=cache.get(target);
     if(!targetContent)errors.push(relative+': dead alternate '+lang.code);
     else if(lang.code!=='x-default'){
      const self='https://emon.3sln.com/'+relative.replace(/index\.html$/,'');
      const links=[...targetContent.matchAll(/<link\b[^>]*rel=["']alternate["'][^>]*>/g)];
      if(!links.some(m=>m[0].includes(`hreflang="${expected}"`)&&m[0].includes(`href="${self}"`)))errors.push(relative+': nonreciprocal alternate '+lang.code);
     }
    }
   } else if(alternates.size)errors.push(relative+': unexpected language alternates');
  }
  const docs=all.filter(f=>path.relative(root,f).startsWith('docs/')&&f.endsWith('/index.html'));for(const file of docs){const content=cache.get(file);for(const target of docs){const href='/'+path.relative(root,path.dirname(target))+'/';if(!content.includes(`href="${href}"`))errors.push(path.relative(root,file)+': documentation absent from navigation '+href);}}
  const read=filename=>fs.readFile(filename,'utf8');
  const rust=(await read('release/pkg/rust/Cargo.toml')).match(/^name\s*=\s*"([^"]+)"/m)?.[1];
  const java=(await read('release/pkg/java/pom.xml')).replace(/<!--[\s\S]*?-->/g,'');
  const javaNames=[java.match(/<groupId>([^<]+)/)?.[1],java.match(/<artifactId>([^<]+)/)?.[1]];
  const csharp=(await read('release/pkg/csharp/Emon.csproj')).match(/<PackageId>([^<]+)/)?.[1];
  const js=JSON.parse(await read('host/js/package.json')).name;
  const pdfium=JSON.parse(await read('release/pkg/pdfium-wasm/package.json')).name;
  const go=(await read('release/pkg/go/go.mod')).match(/^module\s+(\S+)/m)?.[1];
  const packageNames={rust:[rust],java:javaNames,csharp:[csharp],js:[js,pdfium],go:[go],cli:['emon'],c:['emon'],pod:['emon-pod']};
  for(const item of metadata.install??[]){
   if(item.snippet&&!await fs.stat(item.snippet).catch(()=>null))errors.push('missing install snippet '+item.snippet);
   for(const name of packageNames[item.id]??[])if(!name||!item.command.includes(name))errors.push('install '+item.id+': command does not contain manifest package '+name);
  }
  for(const file of await fs.readdir('.cache/site'))if(/^missing-.*\.json$/.test(file)){const missing=JSON.parse(await fs.readFile('.cache/site/'+file,'utf8'));if(missing.length)errors.push(file+': render has '+missing.length+' untranslated strings');}
 }
 for(const [file,content] of cache){const relative=path.relative(root,file);if(!/^(?:(?:es|pt-br|fr|de|ja|zh-hans|ko)\/)?index\.html$/.test(relative))continue;
  const assetRefs=new Set();for(const match of content.matchAll(/<(?:link|script)\b[^>]*(?:href|src)=["']([^"']+)/g))if(match[1].startsWith('/assets/'))assetRefs.add(match[1]);
  const hero=content.match(/<img\b[^>]*class=["'][^"']*hero[^"']*["'][^>]*>/)?.[0]??content.match(/<img\b[^>]*src=["'][^"']*hero[^"']*["'][^>]*>/)?.[0];
  if(hero){const srcset=hero.match(/srcset=["']([^"']+)/)?.[1];const src=srcset?srcset.split(',').at(-1).trim().split(/\s/)[0]:hero.match(/src=["']([^"']+)/)?.[1];if(src)assetRefs.add(src);}
  let size=(await fs.stat(file)).size;for(const ref of assetRefs)size+=(await fs.stat(path.join(root,ref)).catch(()=>({size:0}))).size;worstFirst=Math.max(worstFirst,size);if(size>300*1024)errors.push(relative+': landing first load '+size+' exceeds 307200 bytes');
 }
 const external=new Set();
 for(const [file,content] of cache){if(/^samples\/[^/]+\.html$/.test(path.relative(root,file)))continue;const refs=[...content.matchAll(/\b(?:href|src)=["']([^"']*)["']/g)].map(m=>m[1]);for(const m of content.matchAll(/\bsrcset=["']([^"']*)["']/g))refs.push(...m[1].split(',').map(x=>x.trim().split(/\s/)[0]));for(let ref of refs){ref=decode(ref);if(/^(?:data:|mailto:|tel:|javascript:)/.test(ref))continue;const url=new URL(ref,'https://emon.3sln.com/'+path.relative(root,file));if(url.origin!=='https://emon.3sln.com'){external.add(url.href);continue;}let target=path.join(root,decodeURIComponent(url.pathname));let stat=await fs.stat(target).catch(()=>null);if(stat?.isDirectory()){target=path.join(target,'index.html');stat=await fs.stat(target).catch(()=>null);}if(!stat){errors.push(`${path.relative(root,file)}: dead link ${ref}`);continue;}if(url.hash&&target.endsWith('.html')){const targetContent=cache.get(target)??await fs.readFile(target,'utf8');const id=decodeURIComponent(url.hash.slice(1));if(![...targetContent.matchAll(/\bid=["']([^"']+)["']/g)].some(m=>decode(m[1])===id))errors.push(`${path.relative(root,file)}: dead fragment ${ref}`);}}}
 for(const file of all.filter(f=>/assets\/site\.[^.]+\.css$/.test(f)))errors.push(...contrastErrors(await fs.readFile(file,'utf8')));
 const sitemap=await fs.readFile(path.join(root,'sitemap.xml'),'utf8').catch(()=> '');
 for(const match of sitemap.matchAll(/<url>([\s\S]*?)<\/url>/g)){
  const loc=match[1].match(/<loc>([^<]+)<\/loc>/)?.[1];
  for(const href of [loc,...[...match[1].matchAll(/href="([^"]+)"/g)].map(m=>m[1])].filter(Boolean)){
   const target=path.join(root,new URL(decode(href)).pathname,'index.html');if(!await fs.stat(target).catch(()=>null))errors.push('dead sitemap URL '+href);
  }
  const locPage=loc?cache.get(path.join(root,new URL(loc).pathname,'index.html')):'';
  if(metadata&&(/rel="alternate"/.test(locPage??'')||match[1].includes('xhtml:link')))for(const lang of [...metadata.languages,{code:'x-default'}])if(!match[1].includes(`hreflang="${lang.code}"`))errors.push('sitemap '+loc+': missing alternate '+lang.code);
 }
 const maximum=pred=>Math.max(0,...[...sizes].filter(([name])=>pred(name)).map(([,size])=>size));
 const total=pred=>[...sizes].filter(([name])=>pred(name)).reduce((n,[,size])=>n+size,0);
 const renderTotal=total(name=>name.startsWith('assets/renders/'));const outsideDemo=total(name=>!name.startsWith('demo/'));
 if(renderTotal>12*1024*1024)errors.push('all renders '+renderTotal+' exceeds 12 MiB');
 if(outsideDemo>25*1024*1024)errors.push('site outside /demo/ '+outsideDemo+' exceeds 25 MiB');
 const measured={landingHTML:maximum(n=>/^(?:(?:es|pt-br|fr|de|ja|zh-hans|ko)\/)?index\.html$/.test(n)),landingFirstLoad:worstFirst,docsHTML:maximum(n=>n.startsWith('docs/')&&n.endsWith('index.html')),css:maximum(n=>/assets\/site\.[^.]+\.css$/.test(n)),js:maximum(n=>/assets\/site\.[^.]+\.js$/.test(n)),hero:maximum(n=>n.startsWith('assets/renders/')&&n.includes('hero')),sampleRender:maximum(n=>n.startsWith('assets/renders/')&&!n.includes('hero')),allRenders:renderTotal,outsideDemo};
 for(const [label,bytes]of Object.entries(measured))console.log(`site size ${label}: ${bytes} bytes (${(bytes/1024).toFixed(1)} KiB)`);
 console.log(`site sizes: ${all.length} files, ${(total(()=>true)/1024/1024).toFixed(2)} MiB total`);
 if(args.includes('--external'))for(const url of external){try{const r=await fetch(url,{method:'HEAD',signal:AbortSignal.timeout(15000)});console.log(`${r.ok?'ok':'WARN'} external ${r.status} ${url}`);}catch(e){console.log(`WARN external ${url}: ${e.message}`);}}
 return errors;
}
if(args.includes('--self-test')){
 const {validateCatalogue}=await import('./i18n.mjs');
 const message=[{scope:'',id:'Hello {name}',plural:false}];
 if(!validateCatalogue(message,{},'es',{}).errors.length)throw Error('missing translation missed');
 if(!validateCatalogue(message,{'':{'Hello {name}':'Hola {other}'}},'es',{}).errors.length)throw Error('placeholder mismatch missed');
 const good='<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>x</title><link rel="canonical" href="https://emon.3sln.com/"></head><body><main><h1 id="one">One</h1><a href="#one">ok</a></main></body></html>';
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'emon-site-test-'));try{await fs.writeFile(path.join(root,'index.html'),good.replace('#one','#absent'));await fs.writeFile(path.join(root,'sitemap.xml'),'');if(!(await check(root)).some(e=>e.includes('dead fragment')))throw Error('dead fragment missed');await fs.writeFile(path.join(root,'index.html'),good.replace('#one','/missing/'));if(!(await check(root)).some(e=>e.includes('dead link')))throw Error('dead link missed');if(!htmlErrors(good.replace('</main>','</aside>')).length)throw Error('unbalanced tag missed');if(!budgetErrors('assets/site.abc.js',9000).length)throw Error('asset budget missed');if(!contrastErrors('--bg:#ffffff;--ink:#ffffff;--muted:#ffffff;--accent:#ffffff;--accent-ink:#fff').length)throw Error('contrast missed');console.log('ok site self-test: translations, placeholders, dead links, fragments, HTML, budgets and contrast');}finally{await fs.rm(root,{recursive:true,force:true});}
} else {const root=path.resolve(args[args.indexOf('--site')+1]??'.cache/site/out');const errors=await check(root);for(const e of errors)console.error('FAIL '+e);if(errors.length)process.exit(1);console.log('ok site HTML, links, fragments, assets, sizes and contrast');}
