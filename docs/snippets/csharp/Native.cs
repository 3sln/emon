using System.Collections.Generic;
using System.IO;

var config = emon.Api.Options.Config.Defaults();
config.Renderer = emon.Ffi.Pdfium.Open(null); // the library: $EMON_PDFIUM_LIB, then the system's search
config.Ocr = emon.Ffi.Tess.Open(null);        // the models: $TESSDATA_PREFIX and the usual places; the libraries: $EMON_TESS_LIB
var engine = new emon.Api.Engine(config);
var inputs = new List<emon.Api.Input> { emon.Api.Input.Bytes(File.ReadAllBytes("scan.pdf")) };
var model = engine.Convert(inputs, emon.Api.Options.Defaults());
File.WriteAllText("scan.html", model.ToHtml(new emon.Api.Options.Html()));
