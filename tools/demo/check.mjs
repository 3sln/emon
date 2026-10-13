// THE DEMO, DRIVEN LIKE A PERSON (DEVLOG P11.4c): `bb demo --check`. NOT a gate (it needs Chrome and the downloads the build vendors). Headless Chrome (puppeteer-core,
// the repository's devDependency) loads the built site from a local server and converts, through the page's own door (`window.__demo`, which the buttons use):
//
//   1. a born-digital sample: the text is the PDF's own, and neither pdfium nor Tesseract is loaded;
//   2. a sample that needs a render: its figures are PNG images pdfium drew in the Worker;
//   3. a scan: Tesseract reads it in the Worker's own nested Worker; and again with the OCR on the page's thread (`?ocr-main`, the path a browser with no nested
//      Workers takes), to the same words.
//
//   node tools/demo/check.mjs --site DIR
import path from 'node:path';
import { serve } from './serve.mjs';
import { launch } from '../../test/driver/wasm/browser.mjs';

const arg = (k) => process.argv[process.argv.indexOf(k) + 1];
const site = path.resolve(arg('--site'));
let fails = 0;
const ok = (...x) => console.log('  ok   ' + x.join(''));
const bad = (...x) => {
  fails += 1;
  console.log('  FAIL ' + x.join(''));
};
const check = (c, ...m) => (c ? ok(...m) : bad(...m));

const server = await serve(site);
const browser = await launch();

async function open(query = '') {
  const page = await browser.newPage();
  page.errors = [];
  page.on('pageerror', (e) => page.errors.push(String(e)));
  page.on('console', (m) => m.type() === 'error' && page.errors.push(m.text()));
  await page.goto(server.origin + '/' + query, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForFunction('window.__demo !== undefined', { timeout: 30000 });
  const supported = await page.evaluate(() => window.__demo.supported);
  if (!supported) throw new Error('the page says this Chrome cannot run emon (no exception handling): a Chrome 137 or newer is needed');
  const info = await page.evaluate(() => window.__demo.ready());
  return { page, info };
}

// the part of the result a person sees: the HTML in its sandboxed frame, the status line and what was fetched
async function sample(page, file) {
  const t0 = Date.now();
  const r = await page.evaluate(async (f) => {
    const x = await window.__demo.sample(f);
    return { html: x.html, stats: x.stats, status: document.getElementById('status').textContent, frame: document.getElementById('html').srcdoc.length, sandbox: document.getElementById('html').getAttribute('sandbox'), shown: !document.getElementById('out').hidden };
  }, file);
  r.ms = Date.now() - t0;
  return r;
}

try {
  const { page, info } = await open();
  ok('the page loads, the module and the Worker start (nested Workers: ' + info.nested + ')');

  const inv = await sample(page, 'invoice.pdf');
  check(inv.html.includes('INVOICE') && inv.html.includes('INV-2026-0417'), 'invoice.pdf: the HTML has the invoice\'s own text (INVOICE, INV-2026-0417) in ' + inv.ms + ' ms');
  check(!inv.stats.pdfiumLoaded && inv.stats.ocrRequests === 0, 'invoice.pdf: a born-digital document loads neither pdfium nor Tesseract');
  check(inv.shown && inv.frame > 1000 && inv.sandbox === '', 'invoice.pdf: the result is shown in a sandboxed frame without allow-scripts (sandbox="' + inv.sandbox + '")');
  const requested = server.requests.map(([p]) => p);
  check(!requested.some((p) => p.startsWith('/vendor/')), 'invoice.pdf: nothing under /vendor/ was fetched (' + requested.length + ' files: the page, the SDK, emon.wasm, the sample)');

  const fig = await sample(page, 'figures-charts.pdf');
  const imgs = (fig.html.match(/<img src="data:image\/png;base64,/g) || []).length;
  check(fig.html.includes('Sales Dashboard') && imgs >= 1 && fig.stats.pdfiumLoaded && fig.stats.assets === imgs, 'figures-charts.pdf: pdfium (in the Worker) drew ' + imgs + ' figures as inline PNG images, ' + fig.stats.assets + ' assets, in ' + fig.ms + ' ms');
  check(server.requests.some(([p]) => p === '/vendor/pdfium/pdfium.wasm') && !server.requests.some(([p]) => p.startsWith('/vendor/tesseract/')), 'figures-charts.pdf: pdfium.wasm was fetched when the first render was asked, Tesseract was not');

  const scan = await sample(page, 'scan-plain.pdf');
  check(/Sampling/i.test(scan.html) && scan.stats.ocrRequests > 0 && !scan.stats.ocrOnPageThread, 'scan-plain.pdf: Tesseract read the scan in the Worker\'s nested Worker (' + scan.stats.ocrRequests + ' recognitions, "Adaptive Sampling" found) in ' + scan.ms + ' ms');
  check(page.errors.length === 0 && !server.requests.some(([, s]) => s === 404), 'no error on the page and no file asked for that is not there' + (page.errors.length ? ': ' + page.errors.slice(0, 3).join(' | ') : '') + server.requests.filter(([, s]) => s === 404).map(([p]) => ' 404 ' + p).join(''));
  await page.close();

  // a browser with no nested Workers: the page does the OCR
  const m = await open('?ocr-main');
  check(m.info.nested === false, 'with ?ocr-main the Worker behaves as one that cannot start Workers');
  const scan2 = await sample(m.page, 'scan-plain.pdf');
  check(/Sampling/i.test(scan2.html) && scan2.stats.ocrRequests > 0 && scan2.stats.ocrOnPageThread, 'scan-plain.pdf, OCR on the page\'s thread: the same words ("Adaptive Sampling" found), ' + scan2.stats.ocrRequests + ' recognitions, in ' + scan2.ms + ' ms');
  check(m.page.errors.length === 0, 'no error on the page' + (m.page.errors.length ? ': ' + m.page.errors.slice(0, 3).join(' | ') : ''));
  await m.page.close();
} catch (e) {
  bad(String((e && e.stack) || e));
} finally {
  await browser.close();
  await server.close();
}
if (fails) {
  console.log('  FAIL ' + fails + ' check(s) failed');
  process.exit(1);
}
ok('the demo: a born-digital sample, a figure document (pdfium in a Worker) and a scan (Tesseract, nested and on the page thread) convert in headless Chrome');
process.exit(0);
