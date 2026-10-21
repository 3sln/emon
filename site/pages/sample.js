import fs from 'node:fs';
import { tr } from './i18n.js';
import { html, raw, rich } from './html.js';
const t=tr.scope('samples');
const dataText=(fn,id)=>fn(id);
export default function sample(site){
 const s=site.sample;const renders=site.renders?.[s.id]?.pages||[];
 const source=fs.readFileSync(`samples/${s.id}.html`,'utf8');
 return html`<div class="page-intro"><a href="${site.href('/samples/')}" class="text-link">← ${t('All samples')}</a><h1>${dataText(t,s.title)}</h1><p class="lede">${dataText(t,s.note)}</p><p>${t('Look at:')} ${rich(dataText(t,s['look-at']||s.note))}</p><div class="button-row"><a class="button secondary" href="/samples/${s.src}" download>${t('Download source')}</a><a href="/samples/${s.id}.html" download>HTML ↓</a><a href="/samples/${s.id}.json" download>JSON ↓</a>${s.assets?html`<a href="/samples/${s.id}.assets.html">${t('HTML with figures')}</a>`:''}</div></div><div class="compare-layout"><section><h2>${t('Source document')}</h2><div class="document-pages">${renders.length?raw(renders.map((p,i)=>String(html`<img src="${p.src}" width="${p.width}" height="${p.height}" alt="${t('Source page {number}',{number:i+1})}" loading="lazy">`)).join('')):html`<p class="preview-placeholder">${t('Preview unavailable. Download the source to view the document.')}</p>`}</div></section><section class="output-pane"><h2>${t('Semantic HTML')}</h2><iframe sandbox src="/samples/${s.id}${s.assets?'.assets':''}.html" title="${t('emon output')}" loading="lazy"></iframe><details><summary>${t('HTML source')}</summary><pre tabindex="0" aria-label="HTML"><code>${source}</code></pre></details><details><summary>JSON Model</summary><iframe sandbox src="/samples/${s.id}.json" title="JSON Model" loading="lazy"></iframe></details></section></div>`;
}
