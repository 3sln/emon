import fs from 'node:fs';
import hljs from 'highlight.js/lib/core';
import xml from 'highlight.js/lib/languages/xml';
hljs.registerLanguage('xml',xml);
import { tr } from './i18n.js';
import { html, raw, rich } from './html.js';
import { card } from './samples.js';
const t=tr.scope('landing');
// Data messages are merged into the extraction catalogue separately.
const dataText=(translate,id)=>translate(id);
function snippet(item){
 if(!item.snippet)return item.quickstart||'';
 const source=fs.readFileSync(item.snippet,'utf8');
 if(!source.includes('docs:begin'))return source.trim();
 return [...source.matchAll(/[^\n]*docs:begin[^\n]*\n([\s\S]*?)\s*[^\n]*docs:end[^\n]*/g)].map(m=>m[1].trim()).join('\n\n');
}
function hero(site){
 const model=JSON.parse(fs.readFileSync('samples/table-spans.json'));const outlines=[];
 function walk(n){for(const box of n.at||[]){const kind=n.kind==='cell'?(n.header?'header-cell':'body-cell'):n.kind;if(['heading','paragraph','header-cell','body-cell'].includes(kind))outlines.push(`<rect class="mark-${kind}" x="${box[1]}" y="${box[2]}" width="${box[3]-box[1]}" height="${box[4]-box[2]}"/>`);}for(const c of n.children||[])walk(c);}walk(model.body);
 const image=site.renders?.hero;
 const markup=fs.readFileSync('samples/table-spans.html','utf8').match(/<section>([\s\S]*)<\/section>/)[1].replace('<h1 ','<h3 ').replace('</h1>','</h3>');
 const pretty=markup.replace(/></g,'>\n<');
 return html`<div class="hero-compare"><div class="paper-panel"><div class="panel-label"><span class="step-number">01</span>${t('The document')}</div><div class="paper-crop">${image?html`<img src="${image.src}" srcset="${image.src} 1x, ${image.src2} 2x" width="492" height="225" alt="${t('A PDF table with grouped year headers and spanning cells')}">`:html`<div class="preview-placeholder">${t('PDF with grouped table headers')}</div>`}<svg viewBox="60 80 492 225" aria-hidden="true">${raw(outlines.join(''))}</svg></div><div class="legend"><span class="heading-key">${t('Heading')}</span><span class="paragraph-key">${t('Paragraph')}</span><span class="header-key">${t('Header cell')}</span><span class="body-key">${t('Body cell')}</span></div></div><div class="paper-panel output-panel"><div class="panel-label"><span class="step-number">02</span>${t('The structure')}</div><div class="output-choice"><input type="radio" name="hero-output" id="hero-rendered" checked><label for="hero-rendered">${t('Rendered')}</label><input type="radio" name="hero-output" id="hero-html"><label for="hero-html">HTML</label><div class="rendered-output">${raw(markup)}</div><pre class="html-output" tabindex="0" aria-label="HTML"><code>${raw(hljs.highlight(pretty,{language:'xml'}).value)}</code></pre></div></div></div>`;
}
export default function landing(site){
 const features=[
 ['columns-two',t('Reading order, recovered.'),t('Columns become a continuous reading order. Headings become a document outline.')],
 ['table-spans',t('Tables that stay tables.'),t('Spanning headers, unruled rows and tables that continue across pages. Real cells, not aligned spaces.')],
 ['footnotes',t('Notes in their place.'),t('Footnotes link back to their marks. Running headers and footers stay out of the body.')],
 ['scan-plain',t('Pictures become text.'),t('Tesseract reads scans and images. emon combines OCR with the existing text layer.')],
 ['figures-charts',t('More than words.'),t('Figures, captions, seals and signatures become distinct nodes, with optional image assets.')],
 ['invoice',t('Every node has evidence.'),t('Page, region, source and calibrated confidence. A ledger records what was dropped.')]
 ];
 return html`<section class="hero"><div class="hero-copy"><a class="release-pill" href="${site.releaseUrl}">v${site.version}<span> · ${t('Open source document conversion')}</span> ↗</a><h1>${t('PDFs and images in.')}<br><span>${t('Semantic HTML out.')}</span></h1><p class="lede">${t('Built for scraping. Reading order, headings, tables with spans, footnotes and figures — with the page, place and confidence of every node.')}</p><div class="button-row"><a class="button" href="${site.href('/demo/')}">${t('Try it in your browser')} <span aria-hidden="true">→</span></a><a class="button secondary" href="#install">${t('Install')}</a></div><p class="hero-note">${t('Local by design. No uploads. MIT licensed.')}</p></div>${hero(site)}</section>
 <section class="plain-text-section"><div><p class="eyebrow">${t('Spaces are not structure')}</p><h2>${t('What plain text extraction gives you.')}</h2><p>${t('A row of spaces cannot tell you which year a number belongs to. Semantic HTML can.')}</p></div><div class="code-pair"><div><span class="code-label">pdftotext -layout</span><pre tabindex="0" aria-label="pdftotext"><code>Product         2024             2025
           Units  Revenue   Units  Revenue
Alpha      1000   $20,000   1100   $23,000
Beta       1120   $21,311   1190   $24,197</code></pre></div><div><span class="code-label">emon</span><pre tabindex="0" aria-label="HTML"><code>&lt;thead&gt;
  &lt;tr&gt;
    &lt;th rowspan="2"&gt;Product&lt;/th&gt;
    &lt;th colspan="2"&gt;2024&lt;/th&gt;
    &lt;th colspan="2"&gt;2025&lt;/th&gt;
  &lt;/tr&gt;
&lt;/thead&gt;</code></pre></div></div></section>
 <section><p class="eyebrow">${t('A document, understood')}</p><h2>${t('Keep what the page means.')}</h2><div class="feature-grid">${features.map(([id,title,text],i)=>html`<a class="feature-card" href="${site.href(`/samples/${id}/`)}"><span class="feature-number">0${i+1}</span><h3>${title}</h3><p>${text}</p><span aria-hidden="true">↗</span></a>`)}</div></section>
 <section class="same-bytes"><p class="eyebrow">${t('One specification. Every platform.')}</p><h2>${t('Same bytes everywhere.')}</h2><p>${t('One kin specification, generated into four languages. Every host gives byte-identical output, and the gates hold them to it.')}</p><div class="host-chips">${['Rust','Go','Java','C#','JavaScript / WASM','C','babashka','CLI'].map(name=>html`<span>${name}</span>`)}</div></section>
 <section class="try-section"><div><p class="eyebrow">${t('Your document stays yours')}</p><h2>${t('See it with your own files.')}</h2><p>${t('The demo runs in your browser with WebAssembly. Nothing is uploaded.')}</p></div><a class="drop-card" href="${site.href('/demo/')}"><span class="drop-icon" aria-hidden="true">↥</span><strong>${t('Try a PDF or image')}</strong><span>${t('Nothing is uploaded')}</span><span class="text-link">${t('Open the browser demo')} →</span></a></section>
 <section id="install"><p class="eyebrow">${t('Build it into your workflow')}</p><h2>${t('Your language. The same Model.')}</h2><p>${t('Start with a born-digital PDF. Add pdfium and Tesseract when you need page rendering and OCR.')}</p><div class="install-tabs" data-tabs><div class="tab-list" aria-label="${t('Choose a host')}">${site.install.map(item=>html`<a href="#install-${item.id}" data-tab>${item.name}</a>`)}</div>${site.install.map(item=>html`<section class="install-panel" id="install-${item.id}" data-panel><h3>${item.name}</h3><p class="muted">${dataText(t,item.requirement)}</p><div class="command"><pre tabindex="0" aria-label="${item.name}"><code id="command-${item.id}">${item.command}</code></pre><button type="button" data-copy="command-${item.id}" data-copied="${t('Copied!')}">${t('Copy')}</button></div><pre tabindex="0" aria-label="${item.name}"><code>${snippet(item)}</code></pre><a class="text-link" href="${item.docs}">${t('Read the host guide')} <abbr lang="en" title="English">EN</abbr> →</a>${['cli','c','pod'].includes(item.id)?html`<a class="text-link release-download" href="https://github.com/3sln/emon/releases">${t('Download from GitHub Releases')} ↗</a>`:''}</section>`)}</div></section>
 <section class="how-section"><p class="eyebrow">${t('A small core. Clear boundaries.')}</p><h2>${t('Plan. Fulfil. Convert.')}</h2><div class="flow-diagram" role="img" aria-label="${t('Document to plan, host fulfilment, Model, then HTML or JSON')}"><span>${t('Document')}</span><b aria-hidden="true">→</b><span>${t('plan')}<small>${t('The engine asks')}</small></span><b aria-hidden="true">→</b><span>${t('fulfil')}<small>pdfium · Tesseract</small></span><b aria-hidden="true">→</b><span>Model</span><b aria-hidden="true">→</b><span>HTML<br>JSON</span></div><p>${t('Conversion is a pure function of the input and the answers. The engine asks for byte ranges, a page render or an OCR reading; the host fulfils each request.')}</p><p>${t('Record those answers as a transcript and replay them. The core is one kin specification generated into Rust, Go, Java and C#.')}</p><a class="text-link" href="/docs/model/">${t('Explore the Model')} <abbr lang="en" title="English">EN</abbr> →</a></section>
 <section><div class="section-heading"><div><p class="eyebrow">${t('Proof, page by page')}</p><h2>${t('Take a closer look.')}</h2></div><a class="text-link" href="${site.href('/samples/')}">${t('See all {#} samples',{count:site.samples.length}).singular('See the {#} sample')} →</a></div><div class="sample-grid landing-samples">${['table-spans','columns-two','footnotes','scan-plain','figures-charts','bank-statement-real'].map(id=>card(site,site.samples.find(s=>s.id===id)))}</div></section>
 <section class="free-section"><span class="license-stamp">MIT</span><div><h2>${t('Free software. Yours to build on.')}</h2><p>${t('emon is MIT licensed. Thanks to pdfium for page rendering and Tesseract for OCR. No telemetry, no cookies, no third-party requests.')}</p><p><a href="/licence/">${t('Read the licence')}</a> · <a href="/notices/">${t('Third-party notices')}</a></p></div></section>`;
}
