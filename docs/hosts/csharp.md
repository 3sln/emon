# emon for C#

The NuGet package [`Emon`](https://www.nuget.org/packages/Emon), namespace `emon.Api`. **.NET 8 or newer.** The package has no
dependency; it converts born-digital PDFs on its own. Install with `dotnet add package Emon`.

## First conversion

<!-- snippet: docs/snippets/csharp/Quickstart.cs -->
```csharp
using System.Collections.Generic;
using System.IO;

var engine = new emon.Api.Engine(emon.Api.Options.Config.Defaults());
var inputs = new List<emon.Api.Input> { emon.Api.Input.Bytes(File.ReadAllBytes("report.pdf")) };
var model = engine.Convert(inputs, emon.Api.Options.Defaults());
File.WriteAllText("report.html", model.ToHtml(new emon.Api.Options.Html()));
```

`new emon.Api.Engine(Config.Defaults())` is made once and reused (one Engine serves any number of conversions on any number of
threads); `Convert` takes a list of inputs and `Options` and gives a `Model`; `ToHtml` and `ToJson` write it. This program is
built and run against the built package by `bb package --check`, as a project with `ImplicitUsings` off (hence the
`using` lines).

## The shape of the API

| You | C# |
|---|---|
| say what to do | `Options` (`Options.Defaults()`: properties such as `Ocr`, `Assets`, `Furniture`) and `Options.Config` (`Config.Defaults()`: `Renderer`, `Ocr`, `Limits`, `ChunkSize`, `Parallel`); or one `key=value` text through `Options.Parse(text)`, whose `Parsed` has `.Options` and `.Config` ([docs/options.md](../options.md)) |
| give a document | `Input`: `Input.Bytes(byte[])` or `ReadOnlyMemory<byte>`, `Input.Seekable(Stream)` (read lazily), `Input.Spooled(Stream, max)` (a forward-only stream, read into memory up to `max`), `Input.Of(IByteSource)`, then `.WithPassword(byte[])`. Several inputs make one document |
| convert | `Engine.Convert(List<Input>, Options)`; `ConvertStats` also gives the `RunStats`; `Intake` is stage 0 alone (format, version, page count, encryption); `Record` and `Replay` keep and re-run a transcript of the port answers |
| read the result | `Model.Root()`, `Find(Model.Kind.Named("heading"))`, `Pages()`, `Furniture()`, `Dropped()`, `Assets()`; a `Node` has `Kind()`, `Text()`, `Children()`, `Inlines()`, `Regions()`, `Sources()`, `Conf()`, `Flags()`, `Attr(name)`, `Parent()` |
| write the result | `Model.ToHtml(Options.Html)`, `ToJson(Options.Json)`, `Serialize(Options.Output)`; `Model.Evidence()` with `KeepEvidence` |
| route on a failure | `catch (EmonError e)`: `e.Name()` |

Coordinates in `Model.Region` and `Model.Page` are integers in centipoints (1/100 point), top-left origin; the JSON and the
HTML write points. The library never opens a path or touches the network. Kin's stages recurse deeply, so the Engine runs a
conversion on a thread with a 1 GiB stack (it starts one itself unless you are already on a thread of your own that big).

## A tour

<!-- snippet: docs/snippets/csharp/Tour.cs -->
```csharp
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
```

`EmonError` is an `Exception` with `Kind` (`NoInput`, `Limit`, `PasswordRequired`, `PasswordIncorrect`,
`EncryptionUnsupported`, `UnsupportedFormat`, `Damaged`, `SourceChanged`, `Io`, `Unsupported`, `Plan`, `Internal`), `Input` (the
position of the input in the list), and for a limit `Status` (its name), `Limit` and `Actual`. `Name()` is the stable name of
[docs/options.md](../options.md#errors). Limits are in the options text (`limit.pages=500`) or, as `[kind, value]` pairs, in
`Config.Limits`. **A conversion that sets limits runs alone in the process** (the limits are process-wide); conversions that set
none run concurrently.

## Pages that need drawing and reading: pdfium and Tesseract

A page is drawn by a renderer and read by an `IOcr`; both are fields of `Options.Config` (`Renderer`, `Ocr`). With neither, a
request for them is answered `unsupported` and the text layer stands alone, so a born-digital PDF needs neither. The adapters in
`emon.Ffi` load the native libraries at run time (P/Invoke), so nothing is linked and a missing library is an
`emon.Ffi.Unavailable` when you open it, not a failed render later.

- `Pdfium.Open(libDirOrFile)`: `null` looks in `$EMON_PDFIUM_LIB` (a directory or a file), then the system's search.
- `Tess.Open(tessdata)`: `null` looks for the models in `$TESSDATA_PREFIX` and the usual places, and for the libraries in
  `$EMON_TESS_LIB` (a directory), `/opt/homebrew/lib`, `/usr/local/lib` and the usual Linux directories.

Both are **provisional**: shipped and tested, outside the freeze of `api/surface.edn`. This program converts a scan with both. It
is built against the built package by `bb package --check`, and on a machine that has pdfium and Tesseract installed also run (it
prints a NOTE and skips that on one that does not, as a CI runner does):

<!-- snippet: docs/snippets/csharp/Native.cs -->
```csharp
using System.Collections.Generic;
using System.IO;

var config = emon.Api.Options.Config.Defaults();
config.Renderer = emon.Ffi.Pdfium.Open(null); // the library: $EMON_PDFIUM_LIB, then the system's search
config.Ocr = emon.Ffi.Tess.Open(null);        // the models: $TESSDATA_PREFIX and the usual places; the libraries: $EMON_TESS_LIB
var engine = new emon.Api.Engine(config);
var inputs = new List<emon.Api.Input> { emon.Api.Input.Bytes(File.ReadAllBytes("scan.pdf")) };
var model = engine.Convert(inputs, emon.Api.Options.Defaults());
File.WriteAllText("scan.html", model.ToHtml(new emon.Api.Options.Html()));
```

pdfium builds are at [bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries/releases) (emon is tested with
`chromium/8076`); Tesseract and Leptonica come from your package manager, with the `eng` and `osd` models. To bring your own
engine, implement `emon.RenderPort.IRenderer` (RGB bitmaps) or `emon.Api.IOcr` (its answers are the flat records of
`emon.ocrport`; prefer the adapter). JBIG2 and JPEG 2000 images are not decoded: `Config.PdfCodecs` is the hook, and nothing
ships to fill it.

## Notes

- Limits can also be set for the process with `EMON_LIMIT_*` ([docs/options.md](../options.md#limits)); a value in `Config` or in
  the options text beats it.
- `parallel=N` (or `Config.Parallel`) spreads the work of one conversion over N threads of emon's own; the Model is the serial
  run's bytes.
- pdfium is not thread-safe: renders happen one at a time, behind one lock.
- The namespace `emon` (not `emon.Api`) holds the generated stages and the plan/fulfil machinery. `api/surface.edn` lists most of
  it as accidental, and it may change in any release.

<!-- bb docs:begin api-index -->
<details><summary>The public API of the csharp host: 364 entries, and 13 provisional (generated from <code>api/surface.edn</code>)</summary>

Frozen: `bb api-diff` fails on a removal or a change (DEVLOG D527). One row per name; members are `Owner.member` or `Owner#member` or `Owner::member`.

| Name | Signature |
|---|---|
| `emon.Api.Asset` | `sealed class` |
| `emon.Api.Asset#Bytes()` | `byte[]` |
| `emon.Api.Asset#Dpi()` | `int` |
| `emon.Api.Asset#Flags()` | `List<string>` |
| `emon.Api.Asset#From()` | `string` |
| `emon.Api.Asset#Height()` | `int` |
| `emon.Api.Asset#Id()` | `int` |
| `emon.Api.Asset#MediaType()` | `string` |
| `emon.Api.Asset#Name()` | `string` |
| `emon.Api.Asset#Object()` | `int` |
| `emon.Api.Asset#Origin()` | `string` |
| `emon.Api.Asset#Sha256()` | `string` |
| `emon.Api.Asset#ToString()` | `override string` |
| `emon.Api.Asset#Uses()` | `List<Model.AssetUse>` |
| `emon.Api.Asset#Width()` | `int` |
| `emon.Api.EmonError` | `sealed class : System.Exception` |
| `emon.Api.EmonError#FromPlan(emon.Planrun.PlanError)` | `static EmonError` |
| `emon.Api.EmonError#Name()` | `string` |
| `emon.Api.EmonError.Actual` | `readonly long` |
| `emon.Api.EmonError.ErrorKind` | `enum` |
| `emon.Api.EmonError.ErrorKind.Damaged` | `const` |
| `emon.Api.EmonError.ErrorKind.EncryptionUnsupported` | `const` |
| `emon.Api.EmonError.ErrorKind.Internal` | `const` |
| `emon.Api.EmonError.ErrorKind.Io` | `const` |
| `emon.Api.EmonError.ErrorKind.Limit` | `const` |
| `emon.Api.EmonError.ErrorKind.NoInput` | `const` |
| `emon.Api.EmonError.ErrorKind.PasswordIncorrect` | `const` |
| `emon.Api.EmonError.ErrorKind.PasswordRequired` | `const` |
| `emon.Api.EmonError.ErrorKind.Plan` | `const` |
| `emon.Api.EmonError.ErrorKind.SourceChanged` | `const` |
| `emon.Api.EmonError.ErrorKind.Unsupported` | `const` |
| `emon.Api.EmonError.ErrorKind.UnsupportedFormat` | `const` |
| `emon.Api.EmonError.Input` | `readonly int` |
| `emon.Api.EmonError.Kind` | `readonly ErrorKind` |
| `emon.Api.EmonError.Limit` | `readonly long` |
| `emon.Api.EmonError.Message` | `override string { get; }` |
| `emon.Api.EmonError.Msg` | `readonly string` |
| `emon.Api.EmonError.Plan` | `readonly emon.Planrun.PlanError` |
| `emon.Api.EmonError.Status` | `readonly string` |
| `emon.Api.Engine` | `sealed class` |
| `emon.Api.Engine#.ctor(Options.Config)` | `ctor` |
| `emon.Api.Engine#Convert(List<Input>,Options)` | `Model` |
| `emon.Api.Engine#ConvertStats(List<Input>,Options)` | `Converted` |
| `emon.Api.Engine#Intake(List<Input>)` | `List<Intake>` |
| `emon.Api.Engine#Kinds()` | `static Dictionary<string,Model.Kind>` |
| `emon.Api.Engine#Record(List<Input>,Options)` | `Converted` |
| `emon.Api.Engine#Replay(List<Input>,Options,emon.Planrun.Transcript)` | `Model` |
| `emon.Api.Engine.Converted` | `sealed class` |
| `emon.Api.Engine.Converted.Model` | `readonly Model` |
| `emon.Api.Engine.Converted.Stats` | `readonly RunStats` |
| `emon.Api.Engine.Converted.Transcript` | `readonly emon.Planrun.Transcript` |
| `emon.Api.IByteSource` | `interface : emon.RenderPort.ISource` |
| `emon.Api.IByteSource#AsSlice()` | `byte[]` |
| `emon.Api.IByteSource#CopyOf(byte[])` | `static IByteSource` |
| `emon.Api.IByteSource#Of(System.IO.Stream)` | `static IByteSource` |
| `emon.Api.IByteSource#Of(System.ReadOnlyMemory<byte>)` | `static IByteSource` |
| `emon.Api.IByteSource#Of(byte[])` | `static IByteSource` |
| `emon.Api.IByteSource#Spool(System.IO.Stream,long)` | `static IByteSource` |
| `emon.Api.IOcr` | `interface` |
| `emon.Api.IOcr#Fulfil(emon.Planrun.Request,System.Collections.Generic.Dictionary<int,emon.Port.Bitmap>,bool)` | `emon.Planrun.Answer` |
| `emon.Api.IOcr#Producer()` | `System.Collections.Generic.List<string[]>` |
| `emon.Api.ImgDec` | `static partial class` |
| `emon.Api.ImgDec#Decode(IByteSource,int)` | `static Decoded` |
| `emon.Api.ImgDec.Decoded` | `sealed class` |
| `emon.Api.ImgDec.Decoded.Bilevel` | `bool` |
| `emon.Api.ImgDec.Decoded.Ch` | `int` |
| `emon.Api.ImgDec.Decoded.Dpi100` | `int` |
| `emon.Api.ImgDec.Decoded.Frames` | `int` |
| `emon.Api.ImgDec.Decoded.H` | `int` |
| `emon.Api.ImgDec.Decoded.Pixels` | `byte[]` |
| `emon.Api.ImgDec.Decoded.W` | `int` |
| `emon.Api.ImgDec.Fail` | `sealed class : Exception` |
| `emon.Api.ImgDec.Fail#.ctor(int,string)` | `ctor` |
| `emon.Api.ImgDec.Fail#.ctor(int,string,long)` | `ctor` |
| `emon.Api.ImgDec.Fail.Kind` | `readonly int` |
| `emon.Api.ImgDec.Fail.Pixels` | `readonly long` |
| `emon.Api.ImgDec.Failed` | `const int = 0` |
| `emon.Api.ImgDec.Limit` | `const int` |
| `emon.Api.ImgDec.PdfPix` | `sealed class` |
| `emon.Api.ImgDec.PdfPix#.ctor(int,int,int,byte[],bool)` | `ctor` |
| `emon.Api.ImgDec.PdfPix.Bilevel` | `bool` |
| `emon.Api.ImgDec.PdfPix.Ch` | `int` |
| `emon.Api.ImgDec.PdfPix.H` | `int` |
| `emon.Api.ImgDec.PdfPix.Px` | `byte[]` |
| `emon.Api.ImgDec.PdfPix.W` | `int` |
| `emon.Api.ImgDec.Unsupported` | `const int` |
| `emon.Api.Input` | `sealed class` |
| `emon.Api.Input#Bytes(System.ReadOnlyMemory<byte>)` | `static Input` |
| `emon.Api.Input#Bytes(byte[])` | `static Input` |
| `emon.Api.Input#Of(IByteSource)` | `static Input` |
| `emon.Api.Input#Seekable(System.IO.Stream)` | `static Input` |
| `emon.Api.Input#Spooled(System.IO.Stream,long)` | `static Input` |
| `emon.Api.Input#ToString()` | `override string` |
| `emon.Api.Input#WithPassword(byte[])` | `Input` |
| `emon.Api.Input.Password` | `readonly byte[]` |
| `emon.Api.Input.Source` | `readonly IByteSource` |
| `emon.Api.Intake` | `sealed class` |
| `emon.Api.Intake#Line()` | `string` |
| `emon.Api.Intake.Bytes` | `readonly long` |
| `emon.Api.Intake.Encrypted` | `readonly bool` |
| `emon.Api.Intake.Format` | `readonly string` |
| `emon.Api.Intake.Pages` | `readonly int` |
| `emon.Api.Intake.VersionMajor` | `readonly int` |
| `emon.Api.Intake.VersionMinor` | `readonly int` |
| `emon.Api.Model` | `sealed class` |
| `emon.Api.Model#Asset(int)` | `Asset` |
| `emon.Api.Model#AssetFiles()` | `List<AssetFile>` |
| `emon.Api.Model#Assets()` | `List<Asset>` |
| `emon.Api.Model#Dropped()` | `List<DroppedItem>` |
| `emon.Api.Model#Evidence()` | `string` |
| `emon.Api.Model#Find(Kind)` | `List<Node>` |
| `emon.Api.Model#Furniture()` | `List<Node>` |
| `emon.Api.Model#NodeCount()` | `int` |
| `emon.Api.Model#Pages()` | `List<Page>` |
| `emon.Api.Model#Root()` | `Node` |
| `emon.Api.Model#Serialize(Options.Output)` | `string` |
| `emon.Api.Model#Source()` | `Intake` |
| `emon.Api.Model#ToHtml(Options.Html)` | `string` |
| `emon.Api.Model#ToJson(Options.Json)` | `string` |
| `emon.Api.Model.AssetFile` | `sealed class` |
| `emon.Api.Model.AssetFile.Bytes` | `readonly byte[]` |
| `emon.Api.Model.AssetFile.MediaType` | `readonly string` |
| `emon.Api.Model.AssetFile.Name` | `readonly string` |
| `emon.Api.Model.AssetUse` | `sealed class` |
| `emon.Api.Model.AssetUse.Node` | `readonly int` |
| `emon.Api.Model.AssetUse.Region` | `readonly Region` |
| `emon.Api.Model.Attr` | `sealed class` |
| `emon.Api.Model.Attr.Bool` | `bool` |
| `emon.Api.Model.Attr.IntValue` | `int` |
| `emon.Api.Model.Attr.Kind` | `AttrKind` |
| `emon.Api.Model.Attr.Node` | `int` |
| `emon.Api.Model.Attr.Text` | `string` |
| `emon.Api.Model.AttrKind` | `enum` |
| `emon.Api.Model.AttrKind.Bool` | `const` |
| `emon.Api.Model.AttrKind.Int` | `const` |
| `emon.Api.Model.AttrKind.Node` | `const` |
| `emon.Api.Model.AttrKind.Text` | `const` |
| `emon.Api.Model.DroppedItem` | `sealed class` |
| `emon.Api.Model.DroppedItem.Page` | `int` |
| `emon.Api.Model.DroppedItem.Reason` | `string` |
| `emon.Api.Model.DroppedItem.Region` | `Region` |
| `emon.Api.Model.DroppedItem.Text` | `string` |
| `emon.Api.Model.Kind` | `sealed class` |
| `emon.Api.Model.Kind#.ctor(int)` | `ctor` |
| `emon.Api.Model.Kind#Equals(object)` | `override bool` |
| `emon.Api.Model.Kind#GetHashCode()` | `override int` |
| `emon.Api.Model.Kind#Name()` | `string` |
| `emon.Api.Model.Kind#Named(string)` | `static Kind` |
| `emon.Api.Model.Kind.Code` | `readonly int` |
| `emon.Api.Model.Page` | `sealed class` |
| `emon.Api.Model.Page.HasLabel` | `bool` |
| `emon.Api.Model.Page.Height` | `int` |
| `emon.Api.Model.Page.Kind` | `string` |
| `emon.Api.Model.Page.Label` | `string` |
| `emon.Api.Model.Page.Number` | `int` |
| `emon.Api.Model.Page.Rotation` | `int` |
| `emon.Api.Model.Page.Width` | `int` |
| `emon.Api.Model.Region` | `sealed class` |
| `emon.Api.Model.Region.Page` | `readonly int` |
| `emon.Api.Model.Region.X0` | `readonly int` |
| `emon.Api.Model.Region.X1` | `readonly int` |
| `emon.Api.Model.Region.Y0` | `readonly int` |
| `emon.Api.Model.Region.Y1` | `readonly int` |
| `emon.Api.Node` | `sealed class` |
| `emon.Api.Node#Asset()` | `Asset` |
| `emon.Api.Node#Attr(string)` | `Model.Attr` |
| `emon.Api.Node#Blocks()` | `List<Node>` |
| `emon.Api.Node#Children()` | `List<Node>` |
| `emon.Api.Node#Conf()` | `int` |
| `emon.Api.Node#Flags()` | `int` |
| `emon.Api.Node#Id()` | `int` |
| `emon.Api.Node#Index()` | `int` |
| `emon.Api.Node#Inlines()` | `List<Node>` |
| `emon.Api.Node#IsInline()` | `bool` |
| `emon.Api.Node#Kind()` | `Model.Kind` |
| `emon.Api.Node#NoAsset()` | `string` |
| `emon.Api.Node#Parent()` | `Node` |
| `emon.Api.Node#Regions()` | `List<Model.Region>` |
| `emon.Api.Node#Sources()` | `List<string>` |
| `emon.Api.Node#Text()` | `string` |
| `emon.Api.Node#ToString()` | `override string` |
| `emon.Api.Options` | `sealed class` |
| `emon.Api.Options#Copy()` | `Options` |
| `emon.Api.Options#Defaults()` | `static Options` |
| `emon.Api.Options#IsKey(string)` | `static bool` |
| `emon.Api.Options#Parse(string)` | `static Parsed` |
| `emon.Api.Options.AssetBytes` | `int` |
| `emon.Api.Options.AssetDpi` | `int` |
| `emon.Api.Options.AssetPixels` | `int` |
| `emon.Api.Options.AssetSignatures` | `bool` |
| `emon.Api.Options.Assets` | `AssetsLevel` |
| `emon.Api.Options.AssetsBytes` | `int` |
| `emon.Api.Options.AssetsLevel` | `enum` |
| `emon.Api.Options.AssetsLevel.Figures` | `const` |
| `emon.Api.Options.AssetsLevel.Images` | `const` |
| `emon.Api.Options.AssetsLevel.None` | `const` |
| `emon.Api.Options.Config` | `sealed class` |
| `emon.Api.Options.Config#Defaults()` | `static Config` |
| `emon.Api.Options.Config.ChunkSize` | `int` |
| `emon.Api.Options.Config.FetchRounds` | `int` |
| `emon.Api.Options.Config.Limits` | `int[][]` |
| `emon.Api.Options.Config.MaxRounds` | `int` |
| `emon.Api.Options.Config.Ocr` | `IOcr` |
| `emon.Api.Options.Config.Parallel` | `int` |
| `emon.Api.Options.Config.PdfCodecs` | `IPdfCodecs` |
| `emon.Api.Options.Config.Renderer` | `emon.RenderPort.IRenderer` |
| `emon.Api.Options.Config.SimulateAsync` | `bool` |
| `emon.Api.Options.Furniture` | `FurnitureMode` |
| `emon.Api.Options.FurnitureMode` | `enum` |
| `emon.Api.Options.FurnitureMode.Drop` | `const` |
| `emon.Api.Options.FurnitureMode.Keep` | `const` |
| `emon.Api.Options.FurnitureMode.Policy` | `const` |
| `emon.Api.Options.Html` | `sealed class` |
| `emon.Api.Options.Html.AlignStyle` | `bool` |
| `emon.Api.Options.Html.AssetPrefix` | `string` |
| `emon.Api.Options.Html.Assets` | `HtmlAssets` |
| `emon.Api.Options.Html.ConfidenceBelow` | `int?` |
| `emon.Api.Options.Html.FurnitureDrop` | `bool` |
| `emon.Api.Options.Html.Geometry` | `bool` |
| `emon.Api.Options.Html.Ids` | `bool` |
| `emon.Api.Options.Html.InitialsPlain` | `bool` |
| `emon.Api.Options.Html.Ledger` | `bool` |
| `emon.Api.Options.Html.Milestones` | `bool` |
| `emon.Api.Options.Html.Pretty` | `bool` |
| `emon.Api.Options.HtmlAssets` | `enum` |
| `emon.Api.Options.HtmlAssets.External` | `const` |
| `emon.Api.Options.HtmlAssets.Inline` | `const` |
| `emon.Api.Options.HtmlAssets.Omit` | `const` |
| `emon.Api.Options.IPdfCodecs` | `interface` |
| `emon.Api.Options.IPdfCodecs#Jbig2(byte[],byte[],long)` | `ImgDec.PdfPix` |
| `emon.Api.Options.IPdfCodecs#Jpx(byte[],long)` | `ImgDec.PdfPix` |
| `emon.Api.Options.Json` | `sealed class` |
| `emon.Api.Options.Json#.ctor()` | `ctor` |
| `emon.Api.Options.Json#.ctor(bool)` | `ctor` |
| `emon.Api.Options.Json.Pretty` | `bool` |
| `emon.Api.Options.KeepEvidence` | `bool` |
| `emon.Api.Options.Locale` | `string` |
| `emon.Api.Options.Ocr` | `OcrPolicy` |
| `emon.Api.Options.OcrPolicy` | `enum` |
| `emon.Api.Options.OcrPolicy.Always` | `const` |
| `emon.Api.Options.OcrPolicy.Auto` | `const` |
| `emon.Api.Options.OcrPolicy.Never` | `const` |
| `emon.Api.Options.OcrScope` | `OcrScopes` |
| `emon.Api.Options.OcrScopes` | `enum` |
| `emon.Api.Options.OcrScopes.Full` | `const` |
| `emon.Api.Options.OcrScopes.Witness` | `const` |
| `emon.Api.Options.Output` | `sealed class` |
| `emon.Api.Options.Output.Html` | `Html` |
| `emon.Api.Options.Output.Json` | `Json` |
| `emon.Api.Options.Parsed` | `sealed class` |
| `emon.Api.Options.Parsed.Config` | `Config` |
| `emon.Api.Options.Parsed.JsOcr` | `bool` |
| `emon.Api.Options.Parsed.JsRender` | `bool` |
| `emon.Api.Options.Parsed.Options` | `Options` |
| `emon.Api.Options.Parsed.Producer` | `readonly List<string[]>` |
| `emon.Api.Options.Parsed.Record` | `bool` |
| `emon.Api.RunStats` | `sealed class` |
| `emon.Api.RunStats.Attempts` | `int` |
| `emon.Api.RunStats.BytesFetched` | `long` |
| `emon.Api.RunStats.BytesRequests` | `int` |
| `emon.Api.RunStats.FetchRounds` | `int` |
| `emon.Api.RunStats.FinishUs` | `long` |
| `emon.Api.RunStats.FulfilUs` | `readonly long[]` |
| `emon.Api.RunStats.InputBytes` | `long` |
| `emon.Api.RunStats.PageRequests` | `readonly System.Collections.Generic.List<int>` |
| `emon.Api.RunStats.PageRounds` | `readonly System.Collections.Generic.List<int>` |
| `emon.Api.RunStats.PagesUs` | `long` |
| `emon.Api.RunStats.PassUs` | `long` |
| `emon.Api.RunStats.Requests` | `int` |
| `emon.Api.RunStats.SourceReads` | `long` |
| `emon.Planrun.Answer` | `sealed class` |
| `emon.Planrun.Answer#.ctor(int[])` | `ctor` |
| `emon.Planrun.Answer#.ctor(int[],Port.Bitmap)` | `ctor` |
| `emon.Planrun.Answer#Id()` | `int` |
| `emon.Planrun.Answer#Kind()` | `int` |
| `emon.Planrun.Answer#Status()` | `int` |
| `emon.Planrun.Answer.Bitmap` | `readonly Port.Bitmap` |
| `emon.Planrun.Answer.Rec` | `readonly int[]` |
| `emon.Planrun.Entry` | `sealed class` |
| `emon.Planrun.Entry#.ctor(int,int[],int[],Port.Bitmap)` | `ctor` |
| `emon.Planrun.Entry.Ans` | `readonly int[]` |
| `emon.Planrun.Entry.Bitmap` | `readonly Port.Bitmap` |
| `emon.Planrun.Entry.Req` | `readonly int[]` |
| `emon.Planrun.Entry.Round` | `readonly int` |
| `emon.Planrun.File` | `sealed class` |
| `emon.Planrun.File#.ctor(string,byte[])` | `ctor` |
| `emon.Planrun.File.Bytes` | `readonly byte[]` |
| `emon.Planrun.File.Name` | `readonly string` |
| `emon.Planrun.PlanError` | `sealed class : System.Exception` |
| `emon.Planrun.PlanError#.ctor(string,string)` | `ctor` |
| `emon.Planrun.PlanError#.ctor(string,string,bool,int,int)` | `ctor` |
| `emon.Planrun.PlanError.Actual` | `readonly int` |
| `emon.Planrun.PlanError.Limit` | `readonly int` |
| `emon.Planrun.PlanError.Limited` | `readonly bool` |
| `emon.Planrun.PlanError.Msg` | `readonly string` |
| `emon.Planrun.PlanError.Name` | `readonly string` |
| `emon.Planrun.Request` | `sealed class` |
| `emon.Planrun.Request#.ctor(int[])` | `ctor` |
| `emon.Planrun.Request#.ctor(int[],byte[])` | `ctor` |
| `emon.Planrun.Request#Field(int)` | `int` |
| `emon.Planrun.Request#Id()` | `int` |
| `emon.Planrun.Request#Kind()` | `int` |
| `emon.Planrun.Request.Payload` | `readonly byte[]` |
| `emon.Planrun.Request.Rec` | `readonly int[]` |
| `emon.Planrun.Transcript` | `sealed class` |
| `emon.Planrun.Transcript#CheckInputs(List<int[]>)` | `void` |
| `emon.Planrun.Transcript#ToFiles()` | `List<File>` |
| `emon.Planrun.Transcript.Entries` | `readonly List<Entry>` |
| `emon.Planrun.Transcript.Inputs` | `List<int[]>` |
| `emon.Planrun.Transcript.Producer` | `List<string[]>` |
| `emon.Port.Bitmap` | `sealed class` |
| `emon.Port.Bitmap#.ctor(int,int,int,byte[])` | `ctor` |
| `emon.RenderPort` | `static class` |
| `emon.RenderPort#Cut(Port.Bitmap,Clip)` | `static Port.Bitmap` |
| `emon.RenderPort#ParseRender(Planrun.Request)` | `static Parsed` |
| `emon.RenderPort#PixelsOf(double,int)` | `static int` |
| `emon.RenderPort.Clip` | `sealed class` |
| `emon.RenderPort.Clip#.ctor(int,int,int,int)` | `ctor` |
| `emon.RenderPort.Clip.H` | `readonly int` |
| `emon.RenderPort.Clip.W` | `readonly int` |
| `emon.RenderPort.Clip.X` | `readonly int` |
| `emon.RenderPort.Clip.Y` | `readonly int` |
| `emon.RenderPort.Fulfiller` | `sealed class : Planrun.IFulfiller` |
| `emon.RenderPort.Fulfiller#.ctor(IRenderer,Dictionary<int,Input>)` | `ctor` |
| `emon.RenderPort.Fulfiller#ForInput(IRenderer,int,Input)` | `static Fulfiller` |
| `emon.RenderPort.Fulfiller#Fulfil(Planrun.Request)` | `Planrun.Answer` |
| `emon.RenderPort.Fulfiller#Next()` | `Planrun.Answer` |
| `emon.RenderPort.Fulfiller#Start(Planrun.Request,Dictionary<int,Port.Bitmap>)` | `void` |
| `emon.RenderPort.Fulfiller.Inputs` | `readonly Dictionary<int,Input>` |
| `emon.RenderPort.Fulfiller.Renderer` | `readonly IRenderer` |
| `emon.RenderPort.IRenderer` | `interface` |
| `emon.RenderPort.IRenderer#ExactCrop()` | `bool` |
| `emon.RenderPort.IRenderer#Layers()` | `int` |
| `emon.RenderPort.IRenderer#Render(ISource,byte[],Request)` | `Port.Bitmap` |
| `emon.RenderPort.ISource` | `interface` |
| `emon.RenderPort.ISource#ReadAt(long,byte[],int,int)` | `int` |
| `emon.RenderPort.ISource#Size()` | `long` |
| `emon.RenderPort.Input` | `sealed class` |
| `emon.RenderPort.Input#.ctor(ISource,byte[])` | `ctor` |
| `emon.RenderPort.Input.Password` | `readonly byte[]` |
| `emon.RenderPort.Input.Source` | `readonly ISource` |
| `emon.RenderPort.KindMux` | `sealed class : Planrun.IFulfiller` |
| `emon.RenderPort.KindMux#Next()` | `Planrun.Answer` |
| `emon.RenderPort.KindMux#Start(Planrun.Request,Dictionary<int,Port.Bitmap>)` | `void` |
| `emon.RenderPort.KindMux#With(int[],Planrun.IFulfiller)` | `KindMux` |
| `emon.RenderPort.LayerFull` | `const int = 0` |
| `emon.RenderPort.LayerNoText` | `const int = 1` |
| `emon.RenderPort.LayerNoWatermark` | `const int = 3` |
| `emon.RenderPort.LayerTextOnly` | `const int = 2` |
| `emon.RenderPort.LayerWatermarkOnly` | `const int = 4` |
| `emon.RenderPort.Parsed` | `sealed class` |
| `emon.RenderPort.Parsed.Input` | `readonly int` |
| `emon.RenderPort.Parsed.Request` | `readonly Request` |
| `emon.RenderPort.RenderError` | `sealed class : System.Exception` |
| `emon.RenderPort.RenderError#.ctor(bool,string)` | `ctor` |
| `emon.RenderPort.RenderError.Unsupported` | `readonly bool` |
| `emon.RenderPort.Request` | `sealed class` |
| `emon.RenderPort.Request#.ctor(int,int,int,Clip)` | `ctor` |
| `emon.RenderPort.Request#WithoutClip()` | `Request` |
| `emon.RenderPort.Request.Clip` | `readonly Clip` |
| `emon.RenderPort.Request.Dpi` | `readonly int` |
| `emon.RenderPort.Request.Layer` | `readonly int` |
| `emon.RenderPort.Request.Page` | `readonly int` |
| `emon.RenderPort.StatusUnsupported` | `const int = 2` |

**Provisional** (shipped, not frozen: live adapters and driver flags)

| Name | Signature |
|---|---|
| `emon.Ffi.Pdfium` | `sealed unsafe class : RenderPort.IRenderer` |
| `emon.Ffi.Pdfium#ExactCrop()` | `bool` |
| `emon.Ffi.Pdfium#Layers()` | `int` |
| `emon.Ffi.Pdfium#Open(string)` | `static Pdfium` |
| `emon.Ffi.Pdfium#Render(RenderPort.ISource,byte[],RenderPort.Request)` | `Port.Bitmap` |
| `emon.Ffi.Tess` | `sealed unsafe class : emon.Api.IOcr` |
| `emon.Ffi.Tess#Fulfil(Planrun.Request,Dictionary<int,Port.Bitmap>,bool)` | `Planrun.Answer` |
| `emon.Ffi.Tess#Open(string)` | `static Tess` |
| `emon.Ffi.Tess#Producer()` | `List<string[]>` |
| `emon.Ffi.Tess#WithDefaultDpi(int)` | `Tess` |
| `emon.Ffi.Unavailable` | `sealed class : System.Exception` |
| `emon.Ffi.Unavailable#.ctor(string)` | `ctor` |
| `emon.Ffi.Unavailable#.ctor(string,System.Exception)` | `ctor` |

</details>
<!-- bb docs:end api-index -->
