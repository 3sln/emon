import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const require = createRequire(path.resolve('site/package.json'));
const args = process.argv.slice(2);
const data = JSON.parse(await fs.readFile('.cache/site/data.json','utf8'));
const baseUrl = 'https://emon.3sln.com';
const tagged=spawnSync('git',['show-ref','--verify','--quiet',`refs/tags/v${data.version}`]).status===0;
data.releaseUrl='https://github.com/3sln/emon/releases'+(tagged?`/tag/v${data.version}`:'');
const exists = async p => !!(await fs.stat(p).catch(()=>null));
const write = async (p,s) => {const file=path.join(data.out,p);await fs.mkdir(path.dirname(file),{recursive:true});await fs.writeFile(file,Buffer.isBuffer(s)?s:String(s));};
const copy = async (from,to) => {await fs.mkdir(path.dirname(path.join(data.out,to)),{recursive:true});await fs.copyFile(from,path.join(data.out,to));};
const pageFile = p => p.endsWith('.html') ? p.slice(1) : p.slice(1)+'index.html';
const moduleAt = p => import(pathToFileURL(path.resolve(p)).href);
if(args.includes('--all')) {
 for(const file of await fs.readdir('.cache/site'))if(/^missing-.*\.json$/.test(file))await fs.unlink('.cache/site/'+file);
 const assets={};
 for(const filename of await fs.readdir('site/assets')) {
  const bytes=await fs.readFile('site/assets/'+filename); const ext=path.extname(filename); const name=path.basename(filename,ext);
  const dest=`assets/${name}.${createHash('sha256').update(bytes).digest('hex').slice(0,10)}${ext}`;
  await write(dest,bytes); assets[name==='site'?(ext==='.css'?'css':'js'):name]='/'+dest;
 }
 data.assets=assets;await fs.writeFile('.cache/site/data.json',JSON.stringify(data));
 for(const file of await fs.readdir('samples')) if(/\.(pdf|jpg|png|html|json)$/.test(file) && file!=='index.html') await copy('samples/'+file,'samples/'+file);
 for(const file of ['LICENSE','THIRD_PARTY_NOTICES.md','schema/emon-1.json']) await copy(file,file);
 for(const language of data.languages) {
  const result=spawnSync(process.execPath,['tools/site/render.mjs','--locale',language.code],{stdio:'inherit'});if(result.status)process.exit(result.status);
 }
 const result=spawnSync(process.execPath,['tools/site/render.mjs','--docs'],{stdio:'inherit'});if(result.status)process.exit(result.status);
 if(!data.noDemo){const manifestPath=path.join(data.out,'demo/build.json');const manifest=JSON.parse(await fs.readFile(manifestPath,'utf8'));const bytes=await fs.readFile(path.join(data.out,'demo/index.html'));manifest.files['index.html']={size:bytes.length,sha256:createHash('sha256').update(bytes).digest('hex')};await fs.writeFile(manifestPath,JSON.stringify(manifest,null,2)+'\n');}
 let notfound=await fs.readFile(path.join(data.out,'404.html'),'utf8');
 const templates=[];for(const language of data.languages)templates.push(`<template lang="${language.code}">${await fs.readFile(`.cache/site/404-${language.code}.html`,'utf8')}</template>`);
 notfound=notfound.replace('</body>',templates.join('')+`<script type="module" src="${assets['404']??'/assets/404.js'}"></script></body>`);await write('404.html',notfound);
 const entries=JSON.parse(await fs.readFile('.cache/site/urls.json','utf8'));
 await write('sitemap.xml',`<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">${entries.map(e=>`<url><loc>${baseUrl+e.path}</loc>${e.translated?data.languages.map(l=>`<xhtml:link rel="alternate" hreflang="${l.code}" href="${baseUrl+(l.path?'/'+l.path:'')+e.logical}"/>`).join('')+`<xhtml:link rel="alternate" hreflang="x-default" href="${baseUrl+e.logical}"/>`:''}</url>`).join('')}</urlset>`);
 await write('robots.txt',`User-agent: *\nAllow: /\nSitemap: ${baseUrl}/sitemap.xml\n`);await write('.nojekyll','');
 console.log(`ok site rendered: ${entries.length} pages → ${data.out}`);process.exit(0);
}
const locale=args.includes('--docs')?'en':args[args.indexOf('--locale')+1];
const language=data.languages.find(l=>l.code===locale);if(!language)throw Error('unknown locale '+locale);
const messages=locale==='en'?{}:JSON.parse(await fs.readFile(`site/i18n/${locale}.json`,'utf8'));
const missing=[];
globalThis.__site={locale,messages,onMissing:(...entry)=>{if(locale!=='en')missing.push(entry);}};
const {html,raw,escape}=await moduleAt('site/pages/html.js');
const {default:layout}=await moduleAt('site/pages/layout.js');
const site={...data,locale,language,messages,baseUrl,href:p=>(language.path?'/'+language.path:'')+p,renders:await fs.readFile('.cache/site/renders.json','utf8').then(JSON.parse).catch(()=>({}))};
const urls=args.includes('--docs')?JSON.parse(await fs.readFile('.cache/site/urls.json','utf8').catch(()=> '[]')):locale===data.languages[0].code?[]:JSON.parse(await fs.readFile('.cache/site/urls.json','utf8'));
async function page(logical,title,body,translated=true,description) {
 const p=translated?site.href(logical):logical;site.path=p;
 await write(pageFile(p),layout(site,{path:p,title,description,body,translated}));urls.push({path:p,logical,translated});
}
async function redirect(from,to) {await write(pageFile(from),`<!doctype html><html lang="${language.code}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Redirect · emon</title><meta name="robots" content="noindex"><link rel="canonical" href="${baseUrl+to}"><meta http-equiv="refresh" content="0;url=${to}"></head><body><main><h1>Redirect</h1><a href="${to}">Continue</a></main></body></html>`);}
if(!args.includes('--docs')) {
 for(const [file,p,title] of [['landing','/','Documents, with structure'],['samples','/samples/','Sample documents']]) {
  const {default:template}=await moduleAt(`site/pages/${file}.js`);await page(p,title,await template(site));
 }
 const {default:sample}=await moduleAt('site/pages/sample.js');for(const item of data.samples) await page(`/samples/${item.id}/`,messages.samples?.[item.title]??item.title,await sample({...site,sample:item},item));
 if(data.noDemo) await page('/demo/','Browser demo',html`<main id="main" class="container"><h1>Browser demo</h1><p>The demo is omitted from this quick build.</p></main>`);
 else {
  const {default:demo}=await moduleAt('demo/page.js'); const chrome=String(layout({...site,path:site.href('/demo/')},{path:site.href('/demo/'),title:'emon',body:html`<main id="main"></main>`})); site.header=chrome.match(/<header[\s\S]*?<\/header>/)?.[0]??'';site.footer=chrome.match(/<footer[\s\S]*?<\/footer>/)?.[0]??''; await write(pageFile(site.href('/demo/')),await demo({...site,assetBase:'/demo/',canonical:baseUrl+site.href('/demo/'),alternates:`<link rel="alternate" hreflang="x-default" href="${baseUrl}/demo/">`+data.languages.map(l=>`<link rel="alternate" hreflang="${l.code}" href="${baseUrl+(l.path?'/'+l.path:'')}/demo/">`).join('')}));urls.push({path:site.href('/demo/'),logical:'/demo/',translated:true});
 }
 const {default:localizedNotfound}=await moduleAt('site/pages/notfound.js');await fs.writeFile(`.cache/site/404-${locale}.html`,String(await localizedNotfound(site)).replace(/^<main[^>]*>|<\/main>$/g,''));
 await redirect(site.href('/install/'),site.href('/')+'#install');
 if(locale==='en') {await redirect('/en/','/');const {default:notfound}=await moduleAt('site/pages/notfound.js');await write('404.html',layout(site,{title:'Page not found',path:'/404.html',body:await notfound(site),translated:false}));}
} else {
 const {marked,Renderer}=await import(pathToFileURL(require.resolve('marked')).href);
 const hljs=require('highlight.js/lib/core');for(const lang of ['rust','go','java','csharp','javascript','c','clojure','bash','toml','xml','json'])hljs.registerLanguage(lang,require(`highlight.js/lib/languages/${lang==='toml'?'ini':lang}`));
 const {default:docs,documents}=await moduleAt('site/pages/docs.js');
 const map=new Map([...documents,['LICENSE','/licence/'],['THIRD_PARTY_NOTICES.md','/notices/']].map(([src,url])=>[src,url]));
 async function resolveLink(href,source) {
  if(/^(?:[a-z][a-z\d+.-]*:|\/\/|#)/i.test(href))return href;
  const [pathname,hash]=href.split('#');const normalized=path.posix.normalize(path.posix.join(path.posix.dirname(source),decodeURI(pathname)));
  let url=map.get(normalized);
  if(!url && /^docs\/hosts\/?$/.test(normalized))url='/docs/hosts/';
  if(!url && /^(samples\/?|samples\/index\.html|samples\/README\.md)$/.test(normalized))url='/samples/';
  if(!url && /^(samples\/|schema\/emon-1\.json)/.test(normalized))url='/'+normalized;
  if(!url) {if(!await exists(normalized))throw Error(`Unmapped Markdown link ${source}: ${href} (${normalized})`);url=`https://github.com/3sln/emon/blob/${data.commit}/${normalized}`;}
  return url+(hash?'#'+hash:'');
 }
 for(const [source,url,title] of [...documents,['LICENSE','/licence/','Licence'],['THIRD_PARTY_NOTICES.md','/notices/','Third-party notices']]) {
  let markdown=await fs.readFile(source,'utf8').catch(e=>{if(source==='docs/hosts/README.md')return '# Hosts\n\n'+documents.filter(x=>/^docs\/hosts\/[^/]+\.md$/.test(x[0])&&x[0]!==source).map(x=>`- [${x[2]}](${path.posix.basename(x[0])})`).join('\n');throw e;});
  if(source==='LICENSE') markdown='# Licence\n\n```text\n'+markdown+'\n```';
  markdown=markdown.replace(/<!--[\s\S]*?-->/g,'');
  const tokens=marked.lexer(markdown);const toc=[];const slugs=new Map();const renderer=new Renderer();
  renderer.heading=function(token){const text=token.text.replace(/<[^>]*>/g,'').replace(/[`*_]/g,'');const base=text.toLowerCase().replace(/[^\p{L}\p{N}\p{M}\s_-]/gu,'').replace(/ /g,'-');const n=slugs.get(base)||0;slugs.set(base,n+1);const id=base+(n?'-'+n:'');if(token.depth===2||token.depth===3)toc.push({id,text,level:token.depth});return `<h${token.depth} id="${escape(id)}">${this.parser.parseInline(token.tokens)}</h${token.depth}>\n`;};
  renderer.table=function(token){const table=Renderer.prototype.table.call(this,token);return `<div class="table-scroll" role="region" aria-label="Table" tabindex="0">${table}</div>`;};
  renderer.code=({text,lang})=>`<pre><code${lang?` class="language-${escape(lang)}"`:''}>${lang&&hljs.getLanguage(lang)?hljs.highlight(text,{language:lang}).value:escape(text)}</code></pre>`;
  await Promise.all(marked.walkTokens(tokens,async token=>{if(token.type==='link'||token.type==='image')token.href=await resolveLink(token.href,source);}));
  let content=marked.parser(tokens,{renderer,gfm:true});
  await page(url,title,docs(site,{source,title,content,toc}),false);
 }
}
await fs.writeFile('.cache/site/urls.json',JSON.stringify(urls));
await fs.writeFile(`.cache/site/missing-${locale}${args.includes('--docs')?'-docs':''}.json`,JSON.stringify(missing));
if(missing.length)console.warn(`WARN ${locale}: ${missing.length} missing translations`);
