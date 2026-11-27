using System.Collections.Generic;
using System.IO;

var config = emon.Api.Options.Config.Defaults();
config.Renderer = emon.Ffi.Pdfium.Open(null); // the library: $EMON_PDFIUM_LIB, then the system's search
using var engines = new emon.Api.OcrEngines();   // OCR engines are registered adapters, never wired in
engines.Defaults(emon.Api.Options.Defaults());   // lib/emon/adapters beside the application, or $EMON_ADAPTER_DIR
config.Ocr = engines;
var engine = new emon.Api.Engine(config);
var inputs = new List<emon.Api.Input> { emon.Api.Input.Bytes(File.ReadAllBytes("scan.pdf")) };
var model = engine.Convert(inputs, emon.Api.Options.Defaults());
File.WriteAllText("scan.html", model.ToHtml(new emon.Api.Options.Html()));
