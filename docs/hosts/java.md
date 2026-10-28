# emon for Java

The Maven artifact `com.3sln:emon`, package `emon.api`. **JDK 22 or newer**: pdfium and Tesseract are reached through
`java.lang.foreign`, which is final from 22. The artifact is plain Java with no dependency; it converts born-digital PDFs
on its own.

<!-- bb docs:begin maven-dependency -->
```xml
<dependency>
  <groupId>com.3sln</groupId>
  <artifactId>emon</artifactId>
  <version>0.2.0</version>
</dependency>
```
<!-- bb docs:end maven-dependency -->

## First conversion

<!-- snippet: docs/snippets/java/Quickstart.java -->
```java
import emon.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Quickstart {
    public static void main(String[] args) throws Exception {
        Engine engine = new Engine(Options.Config.defaults());
        Model model = engine.convert(List.of(Input.bytes(Files.readAllBytes(Path.of("report.pdf")))), Options.defaults());
        Files.writeString(Path.of("report.html"), model.toHtml(new Options.Html()));
    }
}
```

`new Engine(Options.Config.defaults())` is made once and reused (one Engine serves any number of conversions on any
number of threads); `convert` takes a list of inputs and `Options` and gives a `Model`; `toHtml` and `toJson` write it.
This program is compiled and run against the built jar by `bb package --check`.

## The shape of the API

| You | Java |
|---|---|
| say what to do | `Options` (`Options.defaults()`: public fields such as `ocr`, `assets`, `furniture`) and `Options.Config` (`Config.defaults()`: `renderer`, `ocr`, `limits`, `chunkSize`, `parallel`); or one `key=value` text through `Options.parse(text)`, whose `Parsed` has `.options` and `.config` ([docs/options.md](../options.md)) |
| give a document | `Input`: `Input.bytes(byte[])`, `Input.seekable(SeekableByteChannel)` (read lazily), `Input.spooled(InputStream, max)` (read into memory up to `max`), `Input.of(ByteSource)`, then `.withPassword(byte[])`. Several inputs make one document |
| convert | `Engine.convert(List<Input>, Options) throws EmonError`; `convertStats` also gives the `RunStats`; `intake` is stage 0 alone (format, version, page count, encryption); `record` and `replay` keep and re-run a transcript of the port answers |
| read the result | `Model.root()`, `find(Model.Kind.named("heading"))`, `pages()`, `furniture()`, `dropped()`, `assets()`; a `Node` has `kind()`, `text()`, `children()`, `inlines()`, `regions()`, `sources()`, `conf()`, `flags()`, `attr(name)`, `parent()` |
| write the result | `Model.toHtml(Options.Html)`, `toJson(Options.Json)`, `serialize(Options.Output)`; `Model.evidence()` with `keepEvidence` |
| route on a failure | `catch (EmonError e)`: `e.name()` |

Coordinates in `Model.Region` and `Model.Page` are integers in centipoints (1/100 point), top-left origin; the JSON and the
HTML write points. The library never opens a path or touches the network.

## A tour

<!-- snippet: docs/snippets/java/Tour.java -->
```java
import emon.api.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Tour {
    public static void main(String[] args) throws Exception {
        // One text says what to do, limits included (docs/options.md).
        Options.Parsed parsed = Options.parse("ocr=never\nlimit.pages=500");
        Engine engine = new Engine(parsed.config);
        List<Input> inputs = List.of(Input.bytes(Files.readAllBytes(Path.of("report.pdf"))));

        // An input is a byte source. A file channel is read lazily, a chunk at a time, only where the engine looks.
        // Stage 0 alone is cheap: format, version, pages, encryption.
        Input lazy = Input.seekable(Files.newByteChannel(Path.of("report.pdf")));
        for (Intake in : engine.intake(List.of(lazy))) {
            System.out.println(in.line());
        }

        try {
            Model model = engine.convert(inputs, parsed.options);
            // Walk the Model: every heading, with its page.
            for (Node h : model.find(Model.Kind.named("heading"))) {
                int page = h.regions().isEmpty() ? 0 : h.regions().get(0).page;
                System.out.println("page " + page + ": " + h.text());
            }
            System.out.println(model.pages().size() + " pages, " + model.nodeCount() + " nodes, " + model.dropped().size() + " dropped");
        } catch (EmonError e) {
            // e.name() is the stable name to route on: "password-required", "pages", "bad-xref", ...
            System.err.println(e.name() + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
```

`EmonError` is a checked exception with `kind` (`NO_INPUT`, `LIMIT`, `PASSWORD_REQUIRED`, `PASSWORD_INCORRECT`,
`ENCRYPTION_UNSUPPORTED`, `UNSUPPORTED_FORMAT`, `DAMAGED`, `SOURCE_CHANGED`, `IO`, `UNSUPPORTED`, `PLAN`, `INTERNAL`),
`input` (the position of the input in the list), and for a limit `status` (its name), `limit` and `actual`. `name()` is the
stable name of [docs/options.md](../options.md#errors). Limits are in the options text (`limit.pages=500`) or, as
`[kind, value]` pairs, in `Config.limits`. **A conversion that sets limits runs alone in the process** (the limits are
process-wide); conversions that set none run concurrently.

## Pages that need drawing and reading: pdfium and Tesseract

A page is drawn by a `Renderer` and read by an `Ocr`; both are fields of `Options.Config` (`renderer`, `ocr`). With
neither, a request for them is answered `unsupported` and the text layer stands alone, so a born-digital PDF needs neither.
The adapters in `emon.ffi` load the native libraries at run time, so nothing is linked and a missing library is an
`emon.ffi.Unavailable` when you open it, not a failed render later. Run the JVM with `--enable-native-access=ALL-UNNAMED`.

- `Pdfium.open(libDirOrFile)`: `null` looks in `$EMON_PDFIUM_LIB` (a directory or a file), then the system's search.
- `Tess.open(tessdata)`: `null` looks for the models in `$TESSDATA_PREFIX` and the usual places, and for the libraries in
  `$EMON_TESS_LIB` (a directory), `/opt/homebrew/lib`, `/usr/local/lib` and the usual Linux directories.

This program converts a scan with both. It is compiled against the built jar by `bb package --check`, and on a machine that
has pdfium and Tesseract installed also run (it prints a NOTE and skips that on one that does not, as a CI runner does):

<!-- snippet: docs/snippets/java/Native.java -->
```java
import emon.api.*;
import emon.ffi.Pdfium;
import emon.ffi.Tess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Native {
    public static void main(String[] args) throws Exception {
        Options.Config config = Options.Config.defaults();
        config.renderer = Pdfium.open(null); // the library: $EMON_PDFIUM_LIB, then the system's search
        config.ocr = Tess.open(null);        // the models: $TESSDATA_PREFIX and the usual places; the libraries: $EMON_TESS_LIB
        Engine engine = new Engine(config);
        Model model = engine.convert(List.of(Input.bytes(Files.readAllBytes(Path.of("scan.pdf")))), Options.defaults());
        Files.writeString(Path.of("scan.html"), model.toHtml(new Options.Html()));
    }
}
```

pdfium builds are at [bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries/releases) (emon is tested with
`chromium/8076`); Tesseract and Leptonica come from your package manager, with the `eng` and `osd` models. To bring your own
engine, implement `emon.RenderPort.Renderer` (RGB bitmaps) or `emon.api.Ocr` (its answers are the flat records of
`emon.ocrport`; prefer the adapter). JBIG2 and JPEG 2000 images are not decoded: `Config.pdfCodecs` is the hook, and nothing
ships to fill it.

## Notes

- Limits can also be set for the process with `EMON_LIMIT_*` ([docs/options.md](../options.md#limits)); a value in `Config` or in
  the options text beats it.
- `parallel=N` (or `Config.parallel`) spreads the work of one conversion over N threads of emon's own; the Model is the serial
  run's bytes.
- pdfium is not thread-safe: renders happen one at a time, behind one lock.
- The package `emon` (not `emon.api`) holds the generated stages and the plan/fulfil machinery. It is public only because Java
  cannot hide a package; `api/surface.edn` lists it as accidental, and it may change in any release.

<!-- bb docs:begin api-index -->
<details><summary>The public API of the java host: 385 entries, and 13 provisional (generated from <code>api/surface.edn</code>)</summary>

Frozen: `bb api-diff` fails on a removal or a change (DEVLOG D527). One row per name; members are `Owner.member` or `Owner#member` or `Owner::member`.

| Name | Signature |
|---|---|
| `emon.Planrun.Answer` | `static final class` |
| `emon.Planrun.Answer#<init>(int[])` | `ctor` |
| `emon.Planrun.Answer#<init>(int[],Port.Bitmap)` | `ctor` |
| `emon.Planrun.Answer#id()` | `int` |
| `emon.Planrun.Answer#kind()` | `int` |
| `emon.Planrun.Answer#status()` | `int` |
| `emon.Planrun.Answer.bitmap` | `final Port.Bitmap` |
| `emon.Planrun.Answer.rec` | `final int[]` |
| `emon.Planrun.Entry` | `static final class` |
| `emon.Planrun.Entry#<init>(int,int[],int[],Port.Bitmap)` | `ctor` |
| `emon.Planrun.Entry.ans` | `final int[]` |
| `emon.Planrun.Entry.bitmap` | `final Port.Bitmap` |
| `emon.Planrun.Entry.req` | `final int[]` |
| `emon.Planrun.Entry.round` | `final int` |
| `emon.Planrun.File` | `static final class` |
| `emon.Planrun.File#<init>(String,byte[])` | `ctor` |
| `emon.Planrun.File.bytes` | `final byte[]` |
| `emon.Planrun.File.name` | `final String` |
| `emon.Planrun.Fulfiller` | `interface` |
| `emon.Planrun.Fulfiller#next()` | `Answer` |
| `emon.Planrun.Fulfiller#start(Request,Map<Integer,Port.Bitmap>)` | `void` |
| `emon.Planrun.PlanError` | `static final class extends RuntimeException` |
| `emon.Planrun.PlanError#<init>(String,String)` | `ctor` |
| `emon.Planrun.PlanError#<init>(String,String,boolean,int,int)` | `ctor` |
| `emon.Planrun.PlanError.actual` | `final int` |
| `emon.Planrun.PlanError.limit` | `final int` |
| `emon.Planrun.PlanError.limited` | `final boolean` |
| `emon.Planrun.PlanError.msg` | `final String` |
| `emon.Planrun.PlanError.name` | `final String` |
| `emon.Planrun.Request` | `static final class` |
| `emon.Planrun.Request#<init>(int[])` | `ctor` |
| `emon.Planrun.Request#<init>(int[],byte[])` | `ctor` |
| `emon.Planrun.Request#field(int)` | `int` |
| `emon.Planrun.Request#id()` | `int` |
| `emon.Planrun.Request#kind()` | `int` |
| `emon.Planrun.Request.payload` | `final byte[]` |
| `emon.Planrun.Request.rec` | `final int[]` |
| `emon.Planrun.Transcript` | `static final class` |
| `emon.Planrun.Transcript#checkInputs(List<int[]>)` | `void` |
| `emon.Planrun.Transcript#toFiles()` | `List<File>` |
| `emon.Planrun.Transcript.entries` | `final List<Entry>` |
| `emon.Planrun.Transcript.inputs` | `List<int[]>` |
| `emon.Planrun.Transcript.producer` | `List<String[]>` |
| `emon.Port.Bitmap` | `static final class` |
| `emon.Port.Bitmap#<init>(int,int,int,byte[])` | `ctor` |
| `emon.Port.Bitmap.ch` | `final int` |
| `emon.Port.Bitmap.d` | `final byte[]` |
| `emon.Port.Bitmap.h` | `final int` |
| `emon.Port.Bitmap.stride` | `final int` |
| `emon.Port.Bitmap.w` | `final int` |
| `emon.RenderPort` | `final class` |
| `emon.RenderPort#cut(Port.Bitmap,Clip)` | `static Port.Bitmap throws RenderError` |
| `emon.RenderPort#parseRender(Planrun.Request)` | `static Parsed` |
| `emon.RenderPort#pixelsOf(double,int)` | `static int` |
| `emon.RenderPort.Clip` | `static final class` |
| `emon.RenderPort.Clip#<init>(int,int,int,int)` | `ctor` |
| `emon.RenderPort.Clip.h` | `final int` |
| `emon.RenderPort.Clip.w` | `final int` |
| `emon.RenderPort.Clip.x` | `final int` |
| `emon.RenderPort.Clip.y` | `final int` |
| `emon.RenderPort.Fulfiller` | `static final class implements Planrun.Fulfiller` |
| `emon.RenderPort.Fulfiller#<init>(Renderer,Map<Integer,Input>)` | `ctor` |
| `emon.RenderPort.Fulfiller#forInput(Renderer,int,Input)` | `static Fulfiller` |
| `emon.RenderPort.Fulfiller#fulfil(Planrun.Request)` | `Planrun.Answer` |
| `emon.RenderPort.Fulfiller#next()` | `Planrun.Answer` |
| `emon.RenderPort.Fulfiller#start(Planrun.Request,Map<Integer,Port.Bitmap>)` | `void` |
| `emon.RenderPort.Fulfiller.inputs` | `final Map<Integer,Input>` |
| `emon.RenderPort.Fulfiller.renderer` | `final Renderer` |
| `emon.RenderPort.Input` | `static final class` |
| `emon.RenderPort.Input#<init>(Source,byte[])` | `ctor` |
| `emon.RenderPort.Input.password` | `final byte[]` |
| `emon.RenderPort.Input.source` | `final Source` |
| `emon.RenderPort.KindMux` | `static final class implements Planrun.Fulfiller` |
| `emon.RenderPort.KindMux#next()` | `Planrun.Answer` |
| `emon.RenderPort.KindMux#start(Planrun.Request,Map<Integer,Port.Bitmap>)` | `void` |
| `emon.RenderPort.KindMux#with(int[],Planrun.Fulfiller)` | `KindMux` |
| `emon.RenderPort.LAYER_FULL` | `static final int = 0` |
| `emon.RenderPort.LAYER_NO_TEXT` | `static final int = 1` |
| `emon.RenderPort.LAYER_NO_WATERMARK` | `static final int = 3` |
| `emon.RenderPort.LAYER_TEXT_ONLY` | `static final int = 2` |
| `emon.RenderPort.LAYER_WATERMARK_ONLY` | `static final int = 4` |
| `emon.RenderPort.Parsed` | `static final class` |
| `emon.RenderPort.Parsed.input` | `final int` |
| `emon.RenderPort.Parsed.request` | `final Request` |
| `emon.RenderPort.RenderError` | `static final class extends Exception` |
| `emon.RenderPort.RenderError#<init>(boolean,String)` | `ctor` |
| `emon.RenderPort.RenderError.unsupported` | `final boolean` |
| `emon.RenderPort.Renderer` | `interface` |
| `emon.RenderPort.Renderer#exactCrop()` | `boolean` |
| `emon.RenderPort.Renderer#layers()` | `int` |
| `emon.RenderPort.Renderer#render(Source,byte[],Request)` | `Port.Bitmap throws RenderError` |
| `emon.RenderPort.Request` | `static final class` |
| `emon.RenderPort.Request#<init>(int,int,int,Clip)` | `ctor` |
| `emon.RenderPort.Request#withoutClip()` | `Request` |
| `emon.RenderPort.Request.clip` | `final Clip` |
| `emon.RenderPort.Request.dpi` | `final int` |
| `emon.RenderPort.Request.layer` | `final int` |
| `emon.RenderPort.Request.page` | `final int` |
| `emon.RenderPort.STATUS_UNSUPPORTED` | `static final int = 2` |
| `emon.RenderPort.Source` | `interface` |
| `emon.RenderPort.Source#readAt(long,byte[],int,int)` | `int throws java.io.IOException` |
| `emon.RenderPort.Source#size()` | `long` |
| `emon.api.ByteSource` | `interface extends emon.RenderPort.Source` |
| `emon.api.ByteSource#asSlice()` | `default byte[]` |
| `emon.api.ByteSource#copyOf(byte[])` | `static ByteSource` |
| `emon.api.ByteSource#of(ByteBuffer)` | `static ByteSource` |
| `emon.api.ByteSource#of(SeekableByteChannel)` | `static ByteSource throws IOException` |
| `emon.api.ByteSource#of(byte[])` | `static ByteSource` |
| `emon.api.ByteSource#readAt(long,byte[],int,int)` | `int throws IOException` |
| `emon.api.ByteSource#size()` | `long` |
| `emon.api.ByteSource#spool(InputStream,long)` | `static ByteSource throws IOException` |
| `emon.api.ByteSource.BytesSource` | `final class implements ByteSource` |
| `emon.api.ByteSource.BytesSource#asSlice()` | `byte[]` |
| `emon.api.ByteSource.BytesSource#readAt(long,byte[],int,int)` | `int` |
| `emon.api.ByteSource.BytesSource#size()` | `long` |
| `emon.api.ByteSource.ChannelSource` | `final class implements ByteSource` |
| `emon.api.ByteSource.ChannelSource#readAt(long,byte[],int,int)` | `synchronized int throws IOException` |
| `emon.api.ByteSource.ChannelSource#size()` | `long` |
| `emon.api.EmonError` | `final class extends Exception` |
| `emon.api.EmonError#getMessage()` | `String` |
| `emon.api.EmonError#name()` | `String` |
| `emon.api.EmonError#plan(Planrun.PlanError)` | `static EmonError` |
| `emon.api.EmonError.Kind` | `enum` |
| `emon.api.EmonError.Kind.DAMAGED` | `const` |
| `emon.api.EmonError.Kind.ENCRYPTION_UNSUPPORTED` | `const` |
| `emon.api.EmonError.Kind.INTERNAL` | `const` |
| `emon.api.EmonError.Kind.IO` | `const` |
| `emon.api.EmonError.Kind.LIMIT` | `const` |
| `emon.api.EmonError.Kind.NO_INPUT` | `const` |
| `emon.api.EmonError.Kind.PASSWORD_INCORRECT` | `const` |
| `emon.api.EmonError.Kind.PASSWORD_REQUIRED` | `const` |
| `emon.api.EmonError.Kind.PLAN` | `const` |
| `emon.api.EmonError.Kind.SOURCE_CHANGED` | `const` |
| `emon.api.EmonError.Kind.UNSUPPORTED` | `const` |
| `emon.api.EmonError.Kind.UNSUPPORTED_FORMAT` | `const` |
| `emon.api.EmonError.actual` | `final long` |
| `emon.api.EmonError.input` | `final int` |
| `emon.api.EmonError.kind` | `final Kind` |
| `emon.api.EmonError.limit` | `final long` |
| `emon.api.EmonError.message` | `final String` |
| `emon.api.EmonError.plan` | `final Planrun.PlanError` |
| `emon.api.EmonError.status` | `final String` |
| `emon.api.Engine` | `final class` |
| `emon.api.Engine#<init>(Options.Config)` | `ctor` |
| `emon.api.Engine#convert(List<Input>,Options)` | `Model throws EmonError` |
| `emon.api.Engine#convertStats(List<Input>,Options)` | `Converted throws EmonError` |
| `emon.api.Engine#intake(List<Input>)` | `List<Intake> throws EmonError` |
| `emon.api.Engine#kinds()` | `static Map<String,Model.Kind>` |
| `emon.api.Engine#record(List<Input>,Options)` | `Converted throws EmonError` |
| `emon.api.Engine#replay(List<Input>,Options,Planrun.Transcript)` | `Model throws EmonError` |
| `emon.api.Engine.Converted` | `static final class` |
| `emon.api.Engine.Converted.model` | `final Model` |
| `emon.api.Engine.Converted.stats` | `final RunStats` |
| `emon.api.Engine.Converted.transcript` | `final Planrun.Transcript` |
| `emon.api.ImgDec` | `final class` |
| `emon.api.ImgDec#decode(ByteSource,int)` | `static Decoded throws Fail` |
| `emon.api.ImgDec.Decoded` | `static final class` |
| `emon.api.ImgDec.Decoded.bilevel` | `boolean` |
| `emon.api.ImgDec.Decoded.ch` | `int` |
| `emon.api.ImgDec.Decoded.dpi100` | `int` |
| `emon.api.ImgDec.Decoded.frames` | `int` |
| `emon.api.ImgDec.Decoded.h` | `int` |
| `emon.api.ImgDec.Decoded.pixels` | `byte[]` |
| `emon.api.ImgDec.Decoded.w` | `int` |
| `emon.api.ImgDec.FAILED` | `static final int = 0` |
| `emon.api.ImgDec.Fail` | `static final class extends Exception` |
| `emon.api.ImgDec.Fail#<init>(int,String)` | `ctor` |
| `emon.api.ImgDec.Fail#<init>(int,String,long)` | `ctor` |
| `emon.api.ImgDec.Fail.kind` | `final int` |
| `emon.api.ImgDec.Fail.pixels` | `final long` |
| `emon.api.ImgDec.LIMIT` | `static final int` |
| `emon.api.ImgDec.PdfPix` | `static final class` |
| `emon.api.ImgDec.PdfPix#<init>(int,int,int,byte[],boolean)` | `ctor` |
| `emon.api.ImgDec.PdfPix.bilevel` | `boolean` |
| `emon.api.ImgDec.PdfPix.ch` | `int` |
| `emon.api.ImgDec.PdfPix.h` | `int` |
| `emon.api.ImgDec.PdfPix.px` | `byte[]` |
| `emon.api.ImgDec.PdfPix.w` | `int` |
| `emon.api.ImgDec.UNSUPPORTED` | `static final int` |
| `emon.api.Input` | `final class` |
| `emon.api.Input#bytes(ByteBuffer)` | `static Input` |
| `emon.api.Input#bytes(byte[])` | `static Input` |
| `emon.api.Input#of(ByteSource)` | `static Input` |
| `emon.api.Input#seekable(SeekableByteChannel)` | `static Input throws IOException` |
| `emon.api.Input#spooled(InputStream,long)` | `static Input throws IOException` |
| `emon.api.Input#toString()` | `String` |
| `emon.api.Input#withPassword(byte[])` | `Input` |
| `emon.api.Input.password` | `final byte[]` |
| `emon.api.Input.source` | `final ByteSource` |
| `emon.api.Intake` | `final class` |
| `emon.api.Intake#line()` | `String` |
| `emon.api.Intake.bytes` | `final long` |
| `emon.api.Intake.encrypted` | `final boolean` |
| `emon.api.Intake.format` | `final String` |
| `emon.api.Intake.pages` | `final int` |
| `emon.api.Intake.versionMajor` | `final int` |
| `emon.api.Intake.versionMinor` | `final int` |
| `emon.api.Model` | `final class` |
| `emon.api.Model#asset(int)` | `Asset` |
| `emon.api.Model#assetFiles()` | `List<AssetFile>` |
| `emon.api.Model#assets()` | `List<Asset>` |
| `emon.api.Model#dropped()` | `List<Dropped>` |
| `emon.api.Model#evidence()` | `String` |
| `emon.api.Model#find(Kind)` | `List<Node>` |
| `emon.api.Model#furniture()` | `List<Node>` |
| `emon.api.Model#nodeCount()` | `int` |
| `emon.api.Model#pages()` | `List<Page>` |
| `emon.api.Model#root()` | `Node` |
| `emon.api.Model#serialize(Options.Output)` | `String` |
| `emon.api.Model#source()` | `Intake` |
| `emon.api.Model#toHtml(Options.Html)` | `String` |
| `emon.api.Model#toJson(Options.Json)` | `String` |
| `emon.api.Model.Asset` | `static final class` |
| `emon.api.Model.Asset#bytes()` | `byte[]` |
| `emon.api.Model.Asset#dpi()` | `int` |
| `emon.api.Model.Asset#flags()` | `List<String>` |
| `emon.api.Model.Asset#from()` | `String` |
| `emon.api.Model.Asset#height()` | `int` |
| `emon.api.Model.Asset#id()` | `int` |
| `emon.api.Model.Asset#mediaType()` | `String` |
| `emon.api.Model.Asset#name()` | `String` |
| `emon.api.Model.Asset#object()` | `int` |
| `emon.api.Model.Asset#origin()` | `String` |
| `emon.api.Model.Asset#sha256()` | `String` |
| `emon.api.Model.Asset#toString()` | `String` |
| `emon.api.Model.Asset#uses()` | `List<AssetUse>` |
| `emon.api.Model.Asset#width()` | `int` |
| `emon.api.Model.AssetFile` | `static final class` |
| `emon.api.Model.AssetFile.bytes` | `final byte[]` |
| `emon.api.Model.AssetFile.mediaType` | `final String` |
| `emon.api.Model.AssetFile.name` | `final String` |
| `emon.api.Model.AssetUse` | `static final class` |
| `emon.api.Model.AssetUse.node` | `final int` |
| `emon.api.Model.AssetUse.region` | `final Region` |
| `emon.api.Model.Attr` | `static final class` |
| `emon.api.Model.Attr.bool` | `boolean` |
| `emon.api.Model.Attr.intValue` | `int` |
| `emon.api.Model.Attr.kind` | `AttrKind` |
| `emon.api.Model.Attr.node` | `int` |
| `emon.api.Model.Attr.text` | `String` |
| `emon.api.Model.AttrKind` | `enum` |
| `emon.api.Model.AttrKind.BOOL` | `const` |
| `emon.api.Model.AttrKind.INT` | `const` |
| `emon.api.Model.AttrKind.NODE` | `const` |
| `emon.api.Model.AttrKind.TEXT` | `const` |
| `emon.api.Model.Dropped` | `static final class` |
| `emon.api.Model.Dropped.page` | `int` |
| `emon.api.Model.Dropped.reason` | `String` |
| `emon.api.Model.Dropped.region` | `Region` |
| `emon.api.Model.Dropped.text` | `String` |
| `emon.api.Model.Kind` | `static final class` |
| `emon.api.Model.Kind#<init>(int)` | `ctor` |
| `emon.api.Model.Kind#equals(Object)` | `boolean` |
| `emon.api.Model.Kind#hashCode()` | `int` |
| `emon.api.Model.Kind#name()` | `String` |
| `emon.api.Model.Kind#named(String)` | `static Kind` |
| `emon.api.Model.Kind.code` | `final int` |
| `emon.api.Model.Page` | `static final class` |
| `emon.api.Model.Page.hasLabel` | `boolean` |
| `emon.api.Model.Page.height` | `int` |
| `emon.api.Model.Page.kind` | `String` |
| `emon.api.Model.Page.label` | `String` |
| `emon.api.Model.Page.number` | `int` |
| `emon.api.Model.Page.rotation` | `int` |
| `emon.api.Model.Page.width` | `int` |
| `emon.api.Model.Region` | `static final class` |
| `emon.api.Model.Region.page` | `final int` |
| `emon.api.Model.Region.x0` | `final int` |
| `emon.api.Model.Region.x1` | `final int` |
| `emon.api.Model.Region.y0` | `final int` |
| `emon.api.Model.Region.y1` | `final int` |
| `emon.api.Node` | `final class` |
| `emon.api.Node#asset()` | `Model.Asset` |
| `emon.api.Node#attr(String)` | `Model.Attr` |
| `emon.api.Node#blocks()` | `List<Node>` |
| `emon.api.Node#children()` | `List<Node>` |
| `emon.api.Node#conf()` | `int` |
| `emon.api.Node#flags()` | `int` |
| `emon.api.Node#id()` | `int` |
| `emon.api.Node#index()` | `int` |
| `emon.api.Node#inlines()` | `List<Node>` |
| `emon.api.Node#isInline()` | `boolean` |
| `emon.api.Node#kind()` | `Model.Kind` |
| `emon.api.Node#noAsset()` | `String` |
| `emon.api.Node#parent()` | `Node` |
| `emon.api.Node#regions()` | `List<Model.Region>` |
| `emon.api.Node#sources()` | `List<String>` |
| `emon.api.Node#text()` | `String` |
| `emon.api.Node#toString()` | `String` |
| `emon.api.Ocr` | `interface` |
| `emon.api.Ocr#fulfil(Planrun.Request,Map<Integer,Port.Bitmap>,boolean)` | `Planrun.Answer` |
| `emon.api.Ocr#producer()` | `List<String[]>` |
| `emon.api.Options` | `final class` |
| `emon.api.Options#copy()` | `Options` |
| `emon.api.Options#defaults()` | `static Options` |
| `emon.api.Options#isKey(String)` | `static boolean` |
| `emon.api.Options#parse(String)` | `static Parsed` |
| `emon.api.Options.Assets` | `enum` |
| `emon.api.Options.Assets#level()` | `int` |
| `emon.api.Options.Assets.FIGURES` | `const` |
| `emon.api.Options.Assets.IMAGES` | `const` |
| `emon.api.Options.Assets.NONE` | `const` |
| `emon.api.Options.Config` | `static final class` |
| `emon.api.Options.Config#defaults()` | `static Config` |
| `emon.api.Options.Config.chunkSize` | `int` |
| `emon.api.Options.Config.fetchRounds` | `int` |
| `emon.api.Options.Config.limits` | `int[][]` |
| `emon.api.Options.Config.maxRounds` | `int` |
| `emon.api.Options.Config.ocr` | `Ocr` |
| `emon.api.Options.Config.parallel` | `int` |
| `emon.api.Options.Config.pdfCodecs` | `PdfCodecs` |
| `emon.api.Options.Config.renderer` | `emon.RenderPort.Renderer` |
| `emon.api.Options.Config.simulateAsync` | `boolean` |
| `emon.api.Options.Furniture` | `enum` |
| `emon.api.Options.Furniture#mode()` | `int` |
| `emon.api.Options.Furniture.DROP` | `const` |
| `emon.api.Options.Furniture.KEEP` | `const` |
| `emon.api.Options.Furniture.POLICY` | `const` |
| `emon.api.Options.Html` | `static final class` |
| `emon.api.Options.Html.alignStyle` | `boolean` |
| `emon.api.Options.Html.assetPrefix` | `String` |
| `emon.api.Options.Html.assets` | `HtmlAssets` |
| `emon.api.Options.Html.confidenceBelow` | `Integer` |
| `emon.api.Options.Html.furnitureDrop` | `boolean` |
| `emon.api.Options.Html.geometry` | `boolean` |
| `emon.api.Options.Html.ids` | `boolean` |
| `emon.api.Options.Html.initialsPlain` | `boolean` |
| `emon.api.Options.Html.ledger` | `boolean` |
| `emon.api.Options.Html.milestones` | `boolean` |
| `emon.api.Options.Html.pretty` | `boolean` |
| `emon.api.Options.HtmlAssets` | `enum` |
| `emon.api.Options.HtmlAssets.EXTERNAL` | `const` |
| `emon.api.Options.HtmlAssets.INLINE` | `const` |
| `emon.api.Options.HtmlAssets.OMIT` | `const` |
| `emon.api.Options.Json` | `static final class` |
| `emon.api.Options.Json#<init>()` | `ctor` |
| `emon.api.Options.Json#<init>(boolean)` | `ctor` |
| `emon.api.Options.Json.pretty` | `boolean` |
| `emon.api.Options.OcrPolicy` | `enum` |
| `emon.api.Options.OcrPolicy#code()` | `int` |
| `emon.api.Options.OcrPolicy.ALWAYS` | `const` |
| `emon.api.Options.OcrPolicy.AUTO` | `const` |
| `emon.api.Options.OcrPolicy.NEVER` | `const` |
| `emon.api.Options.OcrScope` | `enum` |
| `emon.api.Options.OcrScope#code()` | `int` |
| `emon.api.Options.OcrScope.FULL` | `const` |
| `emon.api.Options.OcrScope.WITNESS` | `const` |
| `emon.api.Options.Output` | `static final class` |
| `emon.api.Options.Output.html` | `Html` |
| `emon.api.Options.Output.json` | `Json` |
| `emon.api.Options.Parsed` | `static final class` |
| `emon.api.Options.Parsed.config` | `Config` |
| `emon.api.Options.Parsed.jsOcr` | `boolean` |
| `emon.api.Options.Parsed.jsRender` | `boolean` |
| `emon.api.Options.Parsed.options` | `Options` |
| `emon.api.Options.Parsed.producer` | `final List<String[]>` |
| `emon.api.Options.Parsed.record` | `boolean` |
| `emon.api.Options.PdfCodecs` | `interface` |
| `emon.api.Options.PdfCodecs#jbig2(byte[],byte[],long)` | `ImgDec.PdfPix throws ImgDec.Fail` |
| `emon.api.Options.PdfCodecs#jpx(byte[],long)` | `ImgDec.PdfPix throws ImgDec.Fail` |
| `emon.api.Options.assetBytes` | `int` |
| `emon.api.Options.assetDpi` | `int` |
| `emon.api.Options.assetPixels` | `int` |
| `emon.api.Options.assetSignatures` | `boolean` |
| `emon.api.Options.assets` | `Assets` |
| `emon.api.Options.assetsBytes` | `int` |
| `emon.api.Options.furniture` | `Furniture` |
| `emon.api.Options.keepEvidence` | `boolean` |
| `emon.api.Options.locale` | `String` |
| `emon.api.Options.ocr` | `OcrPolicy` |
| `emon.api.Options.ocrScope` | `OcrScope` |
| `emon.api.RunStats` | `final class` |
| `emon.api.RunStats.attempts` | `int` |
| `emon.api.RunStats.bytesFetched` | `long` |
| `emon.api.RunStats.bytesRequests` | `int` |
| `emon.api.RunStats.fetchRounds` | `int` |
| `emon.api.RunStats.finishUs` | `long` |
| `emon.api.RunStats.fulfilUs` | `long[]` |
| `emon.api.RunStats.inputBytes` | `long` |
| `emon.api.RunStats.pageRequests` | `final List<Integer>` |
| `emon.api.RunStats.pageRounds` | `final List<Integer>` |
| `emon.api.RunStats.pagesUs` | `long` |
| `emon.api.RunStats.passUs` | `long` |
| `emon.api.RunStats.requests` | `int` |
| `emon.api.RunStats.sourceReads` | `long` |

**Provisional** (shipped, not frozen: live adapters and driver flags)

| Name | Signature |
|---|---|
| `emon.ffi.Pdfium` | `final class implements RenderPort.Renderer` |
| `emon.ffi.Pdfium#exactCrop()` | `boolean` |
| `emon.ffi.Pdfium#layers()` | `int` |
| `emon.ffi.Pdfium#open(String)` | `static Pdfium` |
| `emon.ffi.Pdfium#render(RenderPort.Source,byte[],RenderPort.Request)` | `Port.Bitmap throws RenderPort.RenderError` |
| `emon.ffi.Tess` | `final class implements Ocr` |
| `emon.ffi.Tess#fulfil(Planrun.Request,Map<Integer,Port.Bitmap>,boolean)` | `Planrun.Answer` |
| `emon.ffi.Tess#open(String)` | `static Tess` |
| `emon.ffi.Tess#producer()` | `List<String[]>` |
| `emon.ffi.Tess#withDefaultDpi(int)` | `Tess` |
| `emon.ffi.Unavailable` | `final class extends RuntimeException` |
| `emon.ffi.Unavailable#<init>(String)` | `ctor` |
| `emon.ffi.Unavailable#<init>(String,Throwable)` | `ctor` |

</details>
<!-- bb docs:end api-index -->
