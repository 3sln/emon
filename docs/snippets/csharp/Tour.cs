using System;
using System.Collections.Generic;
using System.IO;
using emon.Api;

// One text says what to do, limits included (docs/options.md).
var parsed = Options.Parse("ocr=never\nlimit.pages=500");
var engine = new Engine(parsed.Config);
var inputs = new List<Input> { Input.Bytes(File.ReadAllBytes("report.pdf")) };

// An input is a byte source. A seekable Stream is read lazily, a chunk at a time, only where the engine looks.
// Stage 0 alone is cheap: format, version, pages, encryption.
var lazy = Input.Seekable(File.OpenRead("report.pdf"));
foreach (var intake in engine.Intake(new List<Input> { lazy }))
{
    Console.WriteLine(intake.Line());
}

try
{
    var model = engine.Convert(inputs, parsed.Options);
    // Walk the Model: every heading, with its page.
    foreach (var h in model.Find(Model.Kind.Named("heading")))
    {
        var regions = h.Regions();
        Console.WriteLine("page " + (regions.Count > 0 ? regions[0].Page : 0) + ": " + h.Text());
    }
    Console.WriteLine(model.Pages().Count + " pages, " + model.NodeCount() + " nodes, " + model.Dropped().Count + " dropped");
}
catch (EmonError e)
{
    // e.Name() is the stable name to route on: "password-required", "pages", "bad-xref", ...
    Console.Error.WriteLine(e.Name() + ": " + e.Message);
    Environment.Exit(1);
}
