// THE RENDER PORT'S FORM (SPEC 7.2, 7.3, 13.1; DEVLOG P11.4c): what a JS renderer answers, as `ocr.js` is the form of the OCR port. This file has no
// code and no exports: it is the contract `ports.render` is held to, and `pdfium.js` (pdfium's WebAssembly build) is its adapter.
//
//     const renderer = pdfiumRenderer({ module, wasm });
//     const model = await emon.convert(file, { ports: { ocr, render: renderer } });
//     await renderer.terminate();
//
// WHAT IS ASKED. `render` and `crop` requests (kin's: `emon.plan`). The renderer is only ever asked for WHOLE PAGES: a crop is the cut of a
// whole-page render (SPEC 7.2: "the same pixels, cut out, never a re-fit"; a render clipped to a rectangle moves the pixels on its edge, D127),
// which the module makes (`render::cut`), so the SDK renders a page once for the page and its crops (one page is kept between requests).
//
// A Renderer:
//
//     {
//       layers:    number            the layers it can separate, a bit set over kin's `lay-*` (bit n = layer n): 0 full page, 1 without
//                                    text, 2 text only, 3 without watermarks, 4 watermarks only. pdfium: 0b00111. The full page is always there
//       producer?: { [k]: string }   what a transcript says produced the pixels; merged into the conversion's `producer.NAME` lines
//       open(doc, limits): Promise<RendererDocument>
//       terminate?(): Promise<void>
//     }
//
//     doc     { size, bytes?, read(offset, length): Promise<Uint8Array>, password? }
//               the document the conversion reads. `bytes` is there when it is in memory. `read` is the conversion's byte source (a Blob, a URL,
//               a stream's spool): it gives the whole file when asked for it (`read(0, size)`), and a source that fails or changes under the
//               read ends the conversion with its typed error (`io`, `source-changed`), not a failed page. `password` is the conversion's, as bytes
//     limits  { pixels }   the `pixels` limit in force for the conversion (kin's table, `emon_limit`): a page over it is `failed`
//
// `open` is called LAZILY, on the first `render` or `crop` request of a conversion: a born-digital document that asks for no raster never loads
// the renderer. It is called at most once a conversion, its document is closed when the conversion ends, and the renderer's calls are
// SERIALISED (the SDK awaits one before the next: a renderer need not be re-entrant).
//
// A RendererDocument:
//
//     {
//       render({ page, dpi, layer }, sink?): Promise<Rendered>
//       close(): void
//     }
//
//       page    0-based            dpi   1..4800            layer   0..4
//       sink    (n: number) => Uint8Array   where to put the pixels: the SDK's own memory, so the bitmap is copied once. A renderer that
//               has a `sink` calls it with the byte length (`width * height * 3`) AFTER it has the page's size and BEFORE it writes, and
//               writes into the view it gets (it is valid until the renderer next awaits). One that ignores it returns its own `rgb`.
//
// and the result of a render:
//
//     { width, height, rgb }           RGB, 3 bytes a pixel, row-major, `width * height * 3` bytes: the view the sink gave, or the renderer's own
//                                      (valid until the next call)
//     { status: 'failed', reason }     the page could not be rendered (a damaged document, a page out of range, over the `pixels` limit):
//                                      an ANSWER, transcribed, and the page then has no text from it
//     { status: 'unsupported', reason } the renderer cannot do this (a layer it cannot separate): an answer too, and kin plans around it
//
// A rejection is `failed` too (the SDK notes it in `stats.notes`). THE GEOMETRY is SPEC 7.2's, so that two engines and two hosts agree on the
// size before they disagree on a pixel: the page "as displayed" (rotation and crop box applied) is `wpt x hpt` points, and the bitmap is
// `W x H` with `W = floor(wpt * dpi / 72 + 1/2)`, `H` likewise, at least 1.
export {};
