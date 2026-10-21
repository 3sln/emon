import fs from 'node:fs';
globalThis.__site = { locale: 'en', messages: {} };
const { default: render } = await import('../../demo/page.js');
fs.writeFileSync(process.argv[2], render());
