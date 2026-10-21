import { createTranslator, ObjectCatalogue } from './vendor/bab/index.js';
export const tr = createTranslator({
  locale: document.documentElement.lang,
  catalogue: new ObjectCatalogue(JSON.parse(document.getElementById('i18n')?.textContent || '{}')),
}).scope('demo');
