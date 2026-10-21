const prefix=location.pathname.split('/')[1];
const locale={'pt-br':'pt-BR','zh-hans':'zh-Hans'}[prefix]||prefix;
const template=document.querySelector(`template[lang="${locale}"]`);
if(template){document.querySelector('main').replaceChildren(template.content.cloneNode(true));document.documentElement.lang=locale;}
