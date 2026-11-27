// The reference of the browser parity test (DEVLOG D9xx): the JS SDK driven directly, the way host/js and the demo drive it, with none of the Dart backend between. The test
// compares what this gives with what the Dart API gives for the same bytes and options: they must be byte-identical.
//
//   const r = await reference({ sdk, wasm, tesseract, pdfium, languages }, bytes, options, { password, html });
//   r.json, r.html, r.transcript (a Map)
export async function reference(cfg, bytes, options = {}, extra = {}) {
  const { Emon } = await import(`${cfg.sdk}index.js`);
  const emon = await Emon.load(cfg.wasm);
  const ports = {};
  let tess = null;
  let renderer = null;
  if (cfg.tesseract) {
    const { tesseractPort } = await import(`${cfg.sdk}tesseract.js`);
    tess = tesseractPort({
      createWorker: async (...a) => (await import(cfg.tesseract.module)).default.createWorker(...a),
      languages: ['eng'],
      workerOptions: { workerPath: cfg.tesseract.worker, corePath: cfg.tesseract.core, langPath: cfg.tesseract.lang, cacheMethod: 'none', gzip: true, workerBlobURL: false },
    });
    ports.ocr = tess;
  }
  if (cfg.pdfium) {
    const { pdfiumRenderer } = await import(`${cfg.sdk}pdfium.js`);
    renderer = pdfiumRenderer({ module: () => import(cfg.pdfium.module), wasm: cfg.pdfium.wasm });
    ports.render = renderer;
  }
  try {
    const input = extra.password ? { source: bytes, password: extra.password } : bytes;
    const model = await emon.convert(input, { ...options, ports });
    const out = {
      json: model.json(),
      jsonPretty: model.json({ pretty: true }),
      html: model.html(extra.html || {}),
      transcript: options.record ? model.transcript() : null,
      assets: model.assets.map((a) => ({ name: a.name, type: a.type, size: a.size, bytes: a.bytes() })),
    };
    model.dispose();
    return out;
  } finally {
    await tess?.terminate();
    await renderer?.terminate?.();
  }
}
