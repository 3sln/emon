import { html } from './html.js';
import { tr } from './i18n.js';
const homeLabels={en:'Home',es:'Inicio','pt-BR':'Início',fr:'Accueil',de:'Startseite',ja:'ホーム','zh-Hans':'首页',ko:'홈'};
export default site => html`<main id="main" class="container not-found"><p class="eyebrow">404</p><h1>${tr('Page not found')}</h1><p>${tr('This page may have moved, or the address may be incomplete.')}</p><a class="button" href="${site.href('/')}">${tr('Home')}</a><nav aria-label="Home">${site.languages.map(l=>html`<a lang="${l.code}" href="${l.path?'/'+l.path+'/':'/'}">${homeLabels[l.code]}</a> `)}</nav></main>`;
