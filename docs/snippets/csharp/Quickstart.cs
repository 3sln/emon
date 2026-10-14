using System.Collections.Generic;
using System.IO;

var engine = new emon.Api.Engine(emon.Api.Options.Config.Defaults());
var inputs = new List<emon.Api.Input> { emon.Api.Input.Bytes(File.ReadAllBytes("report.pdf")) };
var model = engine.Convert(inputs, emon.Api.Options.Defaults());
File.WriteAllText("report.html", model.ToHtml(new emon.Api.Options.Html()));
