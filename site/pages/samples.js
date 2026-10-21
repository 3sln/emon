import { tr } from './i18n.js';
import { html, raw } from './html.js';
const t = tr.scope('samples');
const dataText=(fn,id)=>fn(id);
export function thumbnail(site,sample) {
 const image=site.renders?.[sample.id]?.thumb;
 return image ? html`<img src="${image.src}" alt="${dataText(t,sample.title)}" width="${image.width}" height="${image.height}" loading="lazy">` : html`<div class="preview-placeholder">${t('Document preview')} · PDF</div>`;
}
export function card(site,sample) {return html`<a class="sample-card" href="${site.href(`/samples/${sample.id}/`)}"><div class="thumbnail">${thumbnail(site,sample)}</div><div class="card-copy"><h3>${dataText(t,sample.title)}</h3><p>${dataText(t,sample.note)}</p><span class="text-link">${t('Compare source and output')} →</span></div></a>`;}
export default function samples(site){
 const groups=[...new Set(site.samples.map(s=>s.group))];
 return html`<div class="page-intro"><p class="eyebrow">${t('The evidence is in the documents')}</p><h1>${t('Real pages. Structured results.')}</h1><p class="lede">${t('Explore the source documents beside the HTML and JSON that emon produces. Every sample is available to download.')}</p></div>${raw(groups.map(group=>String(html`<section class="sample-group"><h2>${dataText(t,group)}</h2><div class="sample-grid">${raw(site.samples.filter(s=>s.group===group).map(s=>String(card(site,s))).join(''))}</div></section>`)).join(''))}`;
}
