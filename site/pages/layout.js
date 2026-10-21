import { html, raw } from './html.js';
import { tr } from './i18n.js';
export default function layout(site, { title, description = tr('Documents, with structure.'), path = site.path ?? '/', body, translated = true }) {
  const titles = { 'Documents, with structure': tr('Documents, with structure'), 'Sample documents': tr('Sample documents'), 'Browser demo': tr('Browser demo') };
  if (translated && titles[title]) title = titles[title];
  const base = site.baseUrl ?? 'https://emon.3sln.com';
  const language = site.language ?? {code:'en',path:'',dir:'ltr'};
  const prefix = language.path ? `/${language.path}` : '';
  const logicalPath = prefix && path.startsWith(prefix + '/') ? path.slice(prefix.length) : path;
  const languageHref = l => `${l.path ? '/' + l.path : ''}${translated ? logicalPath : '/'}`;
  return html`<!doctype html>
<html lang="${language.code}" dir="${language.dir ?? 'ltr'}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-src 'self'; object-src 'none'; base-uri 'none'">
<title>${title} · emon</title><meta name="description" content="${description}"><link rel="canonical" href="${base + path}">
${translated ? (site.languages ?? []).map(l => html`<link rel="alternate" hreflang="${l.code}" href="${base + languageHref(l)}">`) : ''}
${translated ? html`<link rel="alternate" hreflang="x-default" href="${base + logicalPath}">` : ''}
<link rel="icon" type="image/svg+xml" href="${site.assets?.mark ?? '/assets/mark.svg'}"><link rel="stylesheet" href="${site.assets?.css ?? '/assets/site.css'}">
<script type="module" src="${site.assets?.js ?? '/assets/site.js'}"></script></head>
<body><a class="skip-link" href="#main">${tr('Skip to content')}</a><header class="site-header"><div class="container header-inner"><a class="wordmark" href="${site.href('/')}"><svg viewBox="0 0 32 36" aria-hidden="true"><path fill="currentColor" d="M3 0h18l8 8v25a3 3 0 0 1-3 3H3a3 3 0 0 1-3-3V3a3 3 0 0 1 3-3Z"/><path d="m10 17-4 4 4 4m10-8 4 4-4 4m-3-10-4 12" fill="none" stroke="var(--bg)" stroke-width="2"/></svg><span class="wordmark-text">emon<span aria-hidden="true">.</span></span></a><nav aria-label="${tr('Main navigation')}"><a href="${site.href('/demo/')}">${tr('Demo')}</a><a href="${site.href('/samples/')}">${tr('Samples')}</a><a href="/docs/">${tr('Docs')}${language.code !== 'en' ? html` <abbr lang="en" title="English">EN</abbr>` : ''}</a><a href="https://github.com/3sln/emon/releases">${tr('Changelog')}</a></nav><details class="language-switcher"><summary>${language.name ?? 'English'}</summary><ul>${(site.languages ?? []).map(l => html`<li><a lang="${l.code}" hreflang="${l.code}" data-language="${l.code}" href="${languageHref(l)}"${raw(l.code === language.code ? ' aria-current="true"' : '')}>${l.name}</a></li>`)}</ul></details></div></header>
${String(body).includes('<main') ? body : html`<main id="main" class="container">${body}</main>`}
<footer class="site-footer container"><p><a class="wordmark" href="${site.href('/')}"><span class="wordmark-text">emon<span aria-hidden="true">.</span></span></a> ${tr('Documents, with structure.')} <a href="${site.releaseUrl ?? 'https://github.com/3sln/emon/releases'}">v${site.version}</a></p><p>${tr('Your documents stay on your device.')}</p><nav aria-label="${tr('Footer navigation')}"><a href="https://github.com/3sln/emon">GitHub</a><a href="/licence/">${tr('Licence')}</a><a href="/notices/">${tr('Third-party notices')}</a><a href="https://github.com/3sln/emon/releases">${tr('Changelog')}</a></nav>${language['translated-by'] && !language['reviewed-by'] ? html`<p class="translation-note">${tr('This translation was made by machine.')} <a href="${`https://github.com/3sln/emon/blob/${site.commit}/site/i18n/${language.code}.json`}">${tr('Improve it on GitHub.')}</a></p>` : ''}</footer></body></html>`;
}
