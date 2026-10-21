import { html, raw } from './html.js';
export const documents = [
 ['README.md','/docs/','Overview'],['docs/cli.md','/docs/cli/','Command line'],['docs/model.md','/docs/model/','Model'],['docs/options.md','/docs/options/','Options'],
 ['docs/hosts/README.md','/docs/hosts/','Hosts'], ...[['rust','Rust'],['go','Go'],['java','Java'],['csharp','C#'],['js','JavaScript'],['c','C'],['pod','Babashka pod']].map(([id,title]) => [`docs/hosts/${id}.md`,`/docs/hosts/${id}/`,title]),
 ['CONTRIBUTING.md','/docs/contributing/','Contributing'],['docs/releasing.md','/docs/releasing/','Releasing']
];
export default function docs(site, {source, content, toc, title}) {
 const navigation = html`<nav aria-label="Documentation">${documents.map(([file,url,label]) => html`<a href="${url}"${raw(file === source ? ' aria-current="page"' : '')}>${label}</a>`)}</nav>`;
 return html`<main id="main" class="docs-layout container"><details class="docs-nav" open><summary>Documentation</summary>${navigation}</details><article class="prose docs-article">${raw(content)}<p class="edit-link"><a href="${source==='docs/hosts/README.md'?`https://github.com/3sln/emon/tree/${site.commit}/docs/hosts/`:`https://github.com/3sln/emon/blob/${site.commit}/${source}`}">Edit on GitHub</a></p></article><details class="docs-toc" open><summary>On this page</summary><nav aria-label="On this page">${toc.map(h => html`<a class="toc-level-${h.level}" href="#${h.id}">${h.text}</a>`)}</nav></details></main>`;
}
