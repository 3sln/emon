import { tr } from '../site/pages/i18n.js';
const esc = (s) => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('\"', '&quot;');
export default function renderDemo(site = {}) {
  const t = tr.scope('demo');
  const base = esc(site.assetBase || './');
  return `<!doctype html>
<html lang="${esc(site.locale || 'en')}" dir="${esc(site.language?.dir || 'ltr')}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(t("emon demo"))}</title>
<meta name="description" content="${esc(t("emon turns PDFs and images into semantic HTML, in your browser. Nothing is uploaded."))}">
<link rel="icon" href="${esc(site.assets?.mark || 'data:,')}">
<link rel="stylesheet" href="${base}style.css">
${site.assets?.css ? `<link rel="stylesheet" href="${esc(site.assets.css)}">` : ''}
${site.assets?.js ? `<script type="module" src="${esc(site.assets.js)}"></script>` : ''}
<script type="module" src="${base}app.js"></script>
<link rel="canonical" href="${esc(site.canonical || 'https://emon.3sln.com/demo/')}">
${site.alternates || ''}
<script type="application/json" id="i18n">${JSON.stringify(site.messages || {}).replaceAll('<', '\\u003c')}</script>
</head>
<body>
<a class="skip-link" href="#main">${esc(t("Skip to content"))}</a>
${site.header || ''}
<header>
  <h1>${esc(t("emon"))}</h1>
  <p class="lede">${esc(t("PDFs and images to semantic HTML: headings, paragraphs, tables, lists, figures, footnotes. It runs in this page, in WebAssembly. Nothing is uploaded: the file stays in your browser."))}</p>
</header>

<main id="main">
  <p id="notice" class="error" role="alert" hidden></p>

  <section aria-labelledby="try">
    <h2 id="try">${esc(t("Convert a document"))}</h2>
    <div id="drop" role="button" tabindex="0" aria-describedby="drop-help">
      <strong>${esc(t("Drop a PDF or an image here"))}</strong> ${esc(t("or choose a file"))}<span id="drop-help" class="help">${esc(t("PDF, PNG, JPEG or TIFF"))}</span>
    </div>
    <input id="file" type="file" accept="application/pdf,image/png,image/jpeg,image/tiff,.pdf,.png,.jpg,.jpeg,.tif,.tiff" hidden>
    <p class="row"><span>${esc(t("Or try a sample:"))}</span> <span id="samples" class="samples"></span></p>
    <details class="options">
      <summary>${esc(t("Options"))}</summary>
      <label>${esc(t("Text recognition (OCR)"))}<select id="ocr">
          <option value="auto" selected>${esc(t("auto: read a page when its text is missing or broken"))}</option>
          <option value="always">${esc(t("always: read every page, and fuse it with the text layer"))}</option>
          <option value="never">${esc(t("never: use the text layer only"))}</option>
        </select>
      </label>
      <label class="check"><input id="figures" type="checkbox">${esc(t("Figures as images (pdfium draws each chart, logo and diagram)"))}</label>
    </details>
    <p id="status" role="status" aria-live="polite">${esc(t("Pick a file to begin."))}</p>
    <progress id="busy" hidden></progress>
  </section>

  <section id="out" aria-labelledby="result" hidden>
    <h2 id="result">${esc(t("Result"))}</h2>
    <div class="pair">
      <div class="pane">
        <h3>${esc(t("The document"))}</h3>
        <div id="source" class="frame"></div>
      </div>
      <div class="pane">
        <div role="tablist" aria-label="${esc(t("emon's output"))}" class="tabs">
          <button role="tab" id="tab-html" aria-selected="true" aria-controls="p-html">${esc(t("HTML"))}</button>
          <button role="tab" id="tab-json" aria-selected="false" aria-controls="p-json">${esc(t("JSON"))}</button>
          <button role="tab" id="tab-stats" aria-selected="false" aria-controls="p-stats">${esc(t("Details"))}</button>
          <span class="downloads"><a id="dl-html" download>${esc(t("Download HTML"))}</a> <a id="dl-json" download>${esc(t("Download JSON"))}</a></span>
        </div>
        <div role="tabpanel" id="p-html" aria-labelledby="tab-html" class="frame">
          <iframe id="html" sandbox title="${esc(t("emon's HTML for the document"))}"></iframe>
        </div>
        <div role="tabpanel" id="p-json" aria-labelledby="tab-json" class="frame" hidden>
          <pre><code id="json"></code></pre>
        </div>
        <div role="tabpanel" id="p-stats" aria-labelledby="tab-stats" class="frame" hidden>
          <dl id="stats"></dl>
        </div>
      </div>
    </div>
    <p class="help">${esc(t("The HTML is shown in a sandboxed frame without scripts; its figures are inline images. The JSON is emon's Model, the same on every platform emon runs on."))}</p>
  </section>

  <section aria-labelledby="notes">
    <h2 id="notes">${esc(t("Good to know"))}</h2>
    <ul class="good-to-know">
      <li>${esc(t("It loads what the document needs. A PDF with a healthy text layer never loads pdfium (5 MiB of WebAssembly, for drawing pages) or Tesseract (10 MiB, for reading pictures of text)."))}</li>
      <li>${esc(t("Fonts. pdfium draws a font that a PDF names but does not embed from its own built-in set, not from your system's fonts, so the lettering inside a figure may differ a little from a desktop viewer's. The text of the HTML is the PDF's own and does not depend on it."))}</li>
      <li>${esc(t("Browsers. emon's module needs WebAssembly's standardised exception handling: Chrome 137, Firefox 131, Safari 18.4 or newer. Large pages are limited to 64 megapixels when drawn."))}</li>
      <li>${esc(t("Free software. emon is MIT-licensed; pdfium, Tesseract and the other components this page ships are listed with their licences."))} <a href="${base}THIRD_PARTY_NOTICES.md">THIRD_PARTY_NOTICES.md</a>. ${esc(t("The build manifest lists the versions and checksums of this build."))} <a href="${base}build.json">build.json</a>.</li>
    </ul>
  </section>
</main>
${site.footer || ''}
</body>
</html>
`;
}
