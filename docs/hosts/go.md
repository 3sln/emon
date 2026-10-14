# emon for Go

The module `github.com/3sln/emon/go` (package `emon`). Go 1.21 or newer. The root package is pure Go: it converts
born-digital PDFs with no cgo and no native library. Install with `go get github.com/3sln/emon/go`.

## First conversion

<!-- snippet: docs/snippets/go/quickstart.go -->
```go
import (
	"os"

	emon "github.com/3sln/emon/go"
)

data, err := os.ReadFile("report.pdf")
if err != nil {
	panic(err)
}
engine := emon.NewEngine(emon.DefaultConfig())
model, err := engine.Convert([]emon.Input{emon.BytesInput(data)}, emon.DefaultOptions())
if err != nil {
	panic(err) // *emon.Error: err.(*emon.Error).Name() is the stable name
}
err = os.WriteFile("report.html", []byte(model.ToHTML(emon.DefaultHTMLOptions())), 0o644)
```

`emon.NewEngine(emon.DefaultConfig())` is made once and reused; `Convert` takes inputs and `Options` and gives a
`*Model`; `ToHTML` and `ToJSON` write it. This program is compiled and run against the built module by
`bb package --check`.

## The shape of the API

| You | Go |
|---|---|
| say what to do | `Options` (`DefaultOptions()`) and `Config` (`DefaultConfig()`: ports, limits, chunk size); or one `key=value` text through `emon.ParseOptions(text) (*Parsed, error)`, where `Parsed` has `.Options` and `.Config` ([docs/options.md](../options.md)) |
| give a document | `Input`: `BytesInput([]byte)`, `SeekableInput(io.ReadSeeker)` (read lazily), `ReaderAtInput(io.ReaderAt, size)`, `SpooledInput(io.Reader, max)` (read into memory up to `max`), `NewInput(ByteSource)`, then `.WithPassword([]byte)`. Several inputs make one document |
| convert | `Engine.Convert(inputs, opts) (*Model, error)`; `ConvertStats` also gives the `RunStats`; `Intake` is stage 0 alone (format, version, page count, encryption); `Record` and `Replay` keep and re-run a transcript of the port answers |
| read the result | `Model.Root()`, `Find(Kind)` (`KindNamed("heading")`), `Pages()`, `Furniture()`, `Dropped()`, `Assets()`; a `Node` has `Kind()`, `Text()`, `Children()`, `Inlines()`, `Regions()`, `Sources()`, `Conf()`, `Flags()`, `Attr(name)`, `Parent()` |
| write the result | `Model.ToHTML(HTMLOptions)`, `ToJSON(JSONOptions)`, `Serialize(Output)`; `Model.Evidence()` with `KeepEvidence` |
| route on a failure | `errors.As(err, &e)` with `var e *emon.Error`; `e.Name()` |

Coordinates in `Region` and `Page` are integers in centipoints (1/100 point), top-left origin; the JSON and the HTML
write points. A `ByteSource` is an `io.ReaderAt` with a `Size()`; the library never opens a path or touches the network.

## A tour

<!-- snippet: docs/snippets/go/tour.go -->
```go
import (
	"errors"
	"fmt"
	"os"

	emon "github.com/3sln/emon/go"
)

data, err := os.ReadFile("report.pdf")
if err != nil {
	panic(err)
}

// One text says what to do, limits included (docs/options.md).
parsed, err := emon.ParseOptions("ocr=never\nlimit.pages=500")
if err != nil {
	panic(err)
}
engine := emon.NewEngine(parsed.Config)
inputs := []emon.Input{emon.BytesInput(data)} // or the bytes you already have

// An input is a byte source. A file (io.ReadSeeker) is read lazily, a chunk at a time, only where the engine looks.
// Stage 0 alone is cheap: format, version, pages, encryption.
file, err := os.Open("report.pdf")
if err != nil {
	panic(err)
}
defer file.Close()
lazy, err := emon.SeekableInput(file)
if err != nil {
	panic(err)
}
intakes, err := engine.Intake([]emon.Input{lazy})
if err != nil {
	panic(err)
}
for _, in := range intakes {
	fmt.Printf("%s %d.%d, %d pages\n", in.Format, in.VersionMajor, in.VersionMinor, in.Pages)
}

model, err := engine.Convert(inputs, parsed.Options)
if err != nil {
	var e *emon.Error
	if errors.As(err, &e) {
		// e.Name() is the stable name to route on: "password-required", "pages", "bad-xref", ...
		fmt.Fprintln(os.Stderr, e.Name()+": "+e.Error())
		os.Exit(1)
	}
	panic(err)
}

// Walk the Model: every heading, with its page.
heading, _ := emon.KindNamed("heading")
for _, h := range model.Find(heading) {
	page := int32(0)
	if rs := h.Regions(); len(rs) > 0 {
		page = rs[0].Page
	}
	fmt.Printf("page %d: %s\n", page, h.Text())
}
fmt.Printf("%d pages, %d nodes, %d dropped\n", len(model.Pages()), model.NodeCount(), len(model.Dropped()))
```

`*emon.Error` has `Kind` (`ErrNoInput`, `ErrLimit`, `ErrPasswordRequired`, `ErrPasswordIncorrect`,
`ErrEncryptionUnsupported`, `ErrUnsupportedFormat`, `ErrDamaged`, `ErrSourceChanged`, `ErrIO`, `ErrUnsupported`,
`ErrPlan`, `ErrInternal`), `Input` (the position of the input in the list), and for a limit `Status` (its name), `Limit`
and `Actual`. `Name()` is the stable name of [docs/options.md](../options.md#errors). Limits are in the options text
(`limit.pages=500`) or, as `[kind, value]` pairs, in `Config.Limits`. **A conversion that sets limits runs alone in the
process** (Go has no goroutine-local state, so the limits are process-wide and such a conversion holds a lock); conversions
that set none run concurrently.

## Pages that need drawing and reading: pdfium and Tesseract

A page is drawn by a `port.Renderer` and read by an `emon.Ocr`; both are fields of `Config` (`Renderer`, `Ocr`). With
neither, a request for them is answered `unsupported` and the text layer stands alone, so a born-digital PDF needs neither.

- **pdfium**: the package `github.com/3sln/emon/go/pdfium`. It is loaded at run time with `dlopen` (cgo is needed to build it,
  and no pdfium header or `-lpdfium`): `pdfium.Open(dir)` finds `libpdfium` in `dir`, in `$EMON_PDFIUM_LIB`, and in the system's
  search; the error is `*pdfium.Unavailable`. Built with `CGO_ENABLED=0` the package compiles and `Open` answers that error.
  pdfium is not thread-safe, so renders happen one at a time.
- **Tesseract**: `emon.NewTessOcr(tessdata)`, compiled only under the build tag `tesseract` (cgo, linking `libtesseract` and
  `libleptonica`; without the tag it answers an error). `""` looks in `$TESSDATA_PREFIX` and the usual places. Point cgo at the
  libraries if they are not on the default path: `CGO_LDFLAGS=-L/opt/homebrew/lib go build -tags tesseract`. On macOS a binary
  linked to Homebrew's Tesseract may be killed at launch until it is signed again, ad hoc: `codesign -s - -f ./yourbinary`.

This program converts a scan with both. It is built (without the tag, with the stub) by `bb package --check` against the
built module, and on a machine that has pdfium and Tesseract installed also built with `-tags tesseract` and run (it prints
a NOTE and skips that on one that does not, as a CI runner does):

<!-- snippet: docs/snippets/go/native.go -->
```go
import (
	"os"

	emon "github.com/3sln/emon/go"
	"github.com/3sln/emon/go/pdfium"
)

renderer, err := pdfium.Open("") // "": $EMON_PDFIUM_LIB, then the system's search
if err != nil {
	panic(err) // *pdfium.Unavailable: the library was not found
}
ocr, err := emon.NewTessOcr("") // "": $TESSDATA_PREFIX and the usual places (build with -tags tesseract)
if err != nil {
	panic(err)
}
config := emon.DefaultConfig()
config.Renderer = renderer
config.Ocr = ocr
engine := emon.NewEngine(config)

data, err := os.ReadFile("scan.pdf")
if err != nil {
	panic(err)
}
model, err := engine.Convert([]emon.Input{emon.BytesInput(data)}, emon.DefaultOptions())
if err != nil {
	panic(err)
}
err = os.WriteFile("scan.html", []byte(model.ToHTML(emon.DefaultHTMLOptions())), 0o644)
```

pdfium builds are at [bblanchon/pdfium-binaries](https://github.com/bblanchon/pdfium-binaries/releases) (emon is tested with
`chromium/8076`); Tesseract and Leptonica come from your package manager, with the `eng` and `osd` models. To bring your own
engine, implement `port.Renderer` (RGB bitmaps) or `emon.Ocr` (its answers are the flat records of `emon.ocrport`; prefer the
adapter). JBIG2 and JPEG 2000 images are not decoded: `Config.PdfCodecs` is the hook, and nothing ships to fill it.

## Notes

- Limits can also be set for the process with `EMON_LIMIT_*` ([docs/options.md](../options.md#limits)); a value in `Config` or in
  the options text beats it.
- `parallel=N` (or `Config.Parallel`) spreads the work of one conversion over N goroutines of emon's own; the Model is the
  serial run's bytes.
- The packages `port` and `planrun` are public because an `Ocr` or a `Renderer` implementer names their types; the generated
  packages are under `internal/` and cannot be imported.

<!-- bb docs:begin api-index -->
<details><summary>The public API of the go host: 353 entries, and 11 provisional (generated from <code>api/surface.edn</code>)</summary>

Frozen: `bb api-diff` fails on a removal or a change (DEVLOG D527). One row per name; members are `Owner.member` or `Owner#member` or `Owner::member`.

| Name | Signature |
|---|---|
| `emon.Asset` | `struct +unexported` |
| `emon.Asset.Bytes` | `() []byte` |
| `emon.Asset.DPI` | `() (int32, bool)` |
| `emon.Asset.Flags` | `() []string` |
| `emon.Asset.From` | `() string` |
| `emon.Asset.Height` | `() int32` |
| `emon.Asset.ID` | `() int32` |
| `emon.Asset.MediaType` | `() string` |
| `emon.Asset.Name` | `() string` |
| `emon.Asset.Object` | `() (int32, bool)` |
| `emon.Asset.Origin` | `() string` |
| `emon.Asset.SHA256` | `() string` |
| `emon.Asset.String` | `() string` |
| `emon.Asset.Uses` | `() []AssetUse` |
| `emon.Asset.Width` | `() int32` |
| `emon.AssetFile` | `struct` |
| `emon.AssetFile.Bytes` | `field []byte` |
| `emon.AssetFile.MediaType` | `field string` |
| `emon.AssetFile.Name` | `field string` |
| `emon.AssetUse` | `struct` |
| `emon.AssetUse.Node` | `field int32` |
| `emon.AssetUse.Region` | `field Region` |
| `emon.Assets` | `type int` |
| `emon.AssetsFigures` | `const [iota+2]` |
| `emon.AssetsImages` | `const [iota+1]` |
| `emon.AssetsNone` | `const Assets = iota` |
| `emon.Attr` | `struct` |
| `emon.Attr.Bool` | `field bool` |
| `emon.Attr.Int` | `field int32` |
| `emon.Attr.Kind` | `field AttrKind` |
| `emon.Attr.Node` | `field int32` |
| `emon.Attr.Text` | `field string` |
| `emon.AttrBool` | `const [iota+4]` |
| `emon.AttrInt` | `const AttrKind = iota` |
| `emon.AttrKind` | `type int` |
| `emon.AttrNode` | `const [iota+3]` |
| `emon.AttrText` | `const [iota+1]` |
| `emon.ByteSource` | `interface` |
| `emon.ByteSource.Size` | `() int64` |
| `emon.ByteSource.io.ReaderAt` | `embedded` |
| `emon.BytesInput` | `(b []byte) Input` |
| `emon.BytesSource` | `type struct` |
| `emon.BytesSource.AsSlice` | `ptr () []byte` |
| `emon.BytesSource.ReadAt` | `ptr (p []byte, off int64) (int, error)` |
| `emon.BytesSource.Size` | `ptr () int64` |
| `emon.Config` | `struct` |
| `emon.Config.ChunkSize` | `field int` |
| `emon.Config.FetchRounds` | `field int32` |
| `emon.Config.Limits` | `field [][2]int32` |
| `emon.Config.MaxRounds` | `field int32` |
| `emon.Config.Ocr` | `field Ocr` |
| `emon.Config.Parallel` | `field int32` |
| `emon.Config.PdfCodecs` | `field PdfCodecs` |
| `emon.Config.Renderer` | `field port.Renderer` |
| `emon.Config.SimulateAsync` | `field bool` |
| `emon.DefaultConfig` | `() Config` |
| `emon.DefaultHTMLOptions` | `() HTMLOptions` |
| `emon.DefaultOptions` | `() Options` |
| `emon.Dropped` | `struct` |
| `emon.Dropped.Page` | `field int32` |
| `emon.Dropped.Reason` | `field string` |
| `emon.Dropped.Region` | `field Region` |
| `emon.Dropped.Text` | `field string` |
| `emon.Engine` | `struct +unexported` |
| `emon.Engine.Convert` | `ptr (inputs []Input, opts Options) (*Model, error)` |
| `emon.Engine.ConvertStats` | `ptr (inputs []Input, opts Options) (*Model, RunStats, error)` |
| `emon.Engine.Intake` | `ptr (inputs []Input) ([]Intake, error)` |
| `emon.Engine.Record` | `ptr (inputs []Input, opts Options) (*Model, *Transcript, error)` |
| `emon.Engine.Replay` | `ptr (inputs []Input, opts Options, t *Transcript) (*Model, error)` |
| `emon.ErrDamaged` | `const [iota+11]` |
| `emon.ErrEncryptionUnsupported` | `const [iota+7]` |
| `emon.ErrIO` | `const [iota+15]` |
| `emon.ErrInternal` | `const [iota+21]` |
| `emon.ErrLimit` | `const [iota+3]` |
| `emon.ErrNoInput` | `const ErrorKind = iota` |
| `emon.ErrPasswordIncorrect` | `const [iota+6]` |
| `emon.ErrPasswordRequired` | `const [iota+5]` |
| `emon.ErrPdfUnsupported` | `var = imgUnsupportedf("")` |
| `emon.ErrPlan` | `const [iota+19]` |
| `emon.ErrSourceChanged` | `const [iota+13]` |
| `emon.ErrUnsupported` | `const [iota+17]` |
| `emon.ErrUnsupportedFormat` | `const [iota+9]` |
| `emon.Error` | `struct` |
| `emon.Error.Actual` | `field int64` |
| `emon.Error.Error` | `ptr () string` |
| `emon.Error.Input` | `field int` |
| `emon.Error.Kind` | `field ErrorKind` |
| `emon.Error.Limit` | `field int64` |
| `emon.Error.Message` | `field string` |
| `emon.Error.Name` | `ptr () string` |
| `emon.Error.Plan` | `field *port.PlanError` |
| `emon.Error.Status` | `field string` |
| `emon.Error.Unwrap` | `ptr () error` |
| `emon.ErrorKind` | `type int` |
| `emon.Furniture` | `type int` |
| `emon.FurnitureDrop` | `const [iota+2]` |
| `emon.FurnitureKeep` | `const [iota+1]` |
| `emon.FurniturePolicy` | `const Furniture = iota` |
| `emon.HTMLAssets` | `type int` |
| `emon.HTMLAssetsExternal` | `const [iota+1]` |
| `emon.HTMLAssetsInline` | `const HTMLAssets = iota` |
| `emon.HTMLAssetsOmit` | `const [iota+2]` |
| `emon.HTMLOptions` | `struct` |
| `emon.HTMLOptions.AlignStyle` | `field bool` |
| `emon.HTMLOptions.AssetPrefix` | `field string` |
| `emon.HTMLOptions.Assets` | `field HTMLAssets` |
| `emon.HTMLOptions.ConfidenceBelow` | `field *uint32` |
| `emon.HTMLOptions.FurnitureDrop` | `field bool` |
| `emon.HTMLOptions.Geometry` | `field bool` |
| `emon.HTMLOptions.IDs` | `field bool` |
| `emon.HTMLOptions.InitialsPlain` | `field bool` |
| `emon.HTMLOptions.Ledger` | `field bool` |
| `emon.HTMLOptions.Milestones` | `field bool` |
| `emon.HTMLOptions.Pretty` | `field bool` |
| `emon.Input` | `struct +unexported` |
| `emon.Input.Password` | `field []byte` |
| `emon.Input.Source` | `field ByteSource` |
| `emon.Input.String` | `() string` |
| `emon.Input.WithPassword` | `(password []byte) Input` |
| `emon.Intake` | `struct` |
| `emon.Intake.Bytes` | `field uint64` |
| `emon.Intake.Encrypted` | `field bool` |
| `emon.Intake.Format` | `field string` |
| `emon.Intake.Pages` | `field int` |
| `emon.Intake.VersionMajor` | `field int32` |
| `emon.Intake.VersionMinor` | `field int32` |
| `emon.IsOptionKey` | `(name string) bool` |
| `emon.JSONOptions` | `struct` |
| `emon.JSONOptions.Pretty` | `field bool` |
| `emon.Kind` | `type int32` |
| `emon.Kind.Name` | `() string` |
| `emon.KindNamed` | `(name string) (Kind, bool)` |
| `emon.Model` | `struct +unexported` |
| `emon.Model.Asset` | `ptr (id int32) (Asset, bool)` |
| `emon.Model.AssetFiles` | `ptr () []AssetFile` |
| `emon.Model.Assets` | `ptr () []Asset` |
| `emon.Model.Dropped` | `ptr () []Dropped` |
| `emon.Model.Evidence` | `ptr () (string, bool)` |
| `emon.Model.Find` | `ptr (kind Kind) []Node` |
| `emon.Model.Furniture` | `ptr () []Node` |
| `emon.Model.NodeCount` | `ptr () int` |
| `emon.Model.Pages` | `ptr () []Page` |
| `emon.Model.Root` | `ptr () Node` |
| `emon.Model.Serialize` | `ptr (o Output) string` |
| `emon.Model.Source` | `ptr () Intake` |
| `emon.Model.ToHTML` | `ptr (o HTMLOptions) string` |
| `emon.Model.ToJSON` | `ptr (o JSONOptions) string` |
| `emon.NewBytesSource` | `(b []byte) *BytesSource` |
| `emon.NewEngine` | `(cfg Config) *Engine` |
| `emon.NewInput` | `(src ByteSource) Input` |
| `emon.NewSeekSource` | `(r io.ReadSeeker) (*SeekSource, error)` |
| `emon.NewTessOcr` | `(tessdata string) (Ocr, error)` |
| `emon.Node` | `struct +unexported` |
| `emon.Node.Asset` | `() (Asset, bool)` |
| `emon.Node.Attr` | `(name string) (Attr, bool)` |
| `emon.Node.Blocks` | `() []Node` |
| `emon.Node.Children` | `() []Node` |
| `emon.Node.Conf` | `() int32` |
| `emon.Node.Flags` | `() int32` |
| `emon.Node.ID` | `() int32` |
| `emon.Node.Index` | `() int` |
| `emon.Node.Inlines` | `() []Node` |
| `emon.Node.IsInline` | `() bool` |
| `emon.Node.Kind` | `() Kind` |
| `emon.Node.NoAsset` | `() (string, bool)` |
| `emon.Node.Parent` | `() (Node, bool)` |
| `emon.Node.Regions` | `() []Region` |
| `emon.Node.Sources` | `() []string` |
| `emon.Node.String` | `() string` |
| `emon.Node.Text` | `() string` |
| `emon.Ocr` | `interface` |
| `emon.Ocr.Fulfil` | `(req port.PlanRequest, bitmaps port.PlanBitmaps, keepBinary bool) (port.PlanAnswer, error)` |
| `emon.Ocr.Producer` | `() [][2]string` |
| `emon.OcrAlways` | `const OcrPolicy = iota` |
| `emon.OcrAuto` | `const [iota+1]` |
| `emon.OcrNever` | `const [iota+2]` |
| `emon.OcrPolicy` | `type int` |
| `emon.OcrScope` | `type int` |
| `emon.OcrScopeFull` | `const OcrScope = iota` |
| `emon.OcrScopeWitness` | `const [iota+1]` |
| `emon.Options` | `struct +unexported` |
| `emon.Options.AssetBytes` | `field int32` |
| `emon.Options.AssetDPI` | `field int32` |
| `emon.Options.AssetPixels` | `field int32` |
| `emon.Options.AssetSignatures` | `field bool` |
| `emon.Options.Assets` | `field Assets` |
| `emon.Options.AssetsBytes` | `field int32` |
| `emon.Options.Furniture` | `field Furniture` |
| `emon.Options.KeepEvidence` | `field bool` |
| `emon.Options.Locale` | `field string` |
| `emon.Options.Ocr` | `field OcrPolicy` |
| `emon.Options.OcrScope` | `field OcrScope` |
| `emon.Output` | `struct` |
| `emon.Output.HTML` | `field *HTMLOptions` |
| `emon.Output.JSON` | `field *JSONOptions` |
| `emon.Page` | `struct` |
| `emon.Page.HasLabel` | `field bool` |
| `emon.Page.Height` | `field int32` |
| `emon.Page.Kind` | `field string` |
| `emon.Page.Label` | `field string` |
| `emon.Page.Number` | `field int32` |
| `emon.Page.Rotation` | `field int32` |
| `emon.Page.Width` | `field int32` |
| `emon.ParseOptions` | `(text string) (*Parsed, error)` |
| `emon.Parsed` | `struct` |
| `emon.Parsed.Config` | `field Config` |
| `emon.Parsed.JsOcr` | `field bool` |
| `emon.Parsed.JsRender` | `field bool` |
| `emon.Parsed.Options` | `field Options` |
| `emon.Parsed.Producer` | `field [][2]string` |
| `emon.Parsed.Record` | `field bool` |
| `emon.PdfCodecs` | `interface` |
| `emon.PdfCodecs.Jbig2` | `(data, globals []byte, maxPixels uint64) (*PdfPix, error)` |
| `emon.PdfCodecs.Jpx` | `(data []byte, maxPixels uint64) (*PdfPix, error)` |
| `emon.PdfPix` | `struct` |
| `emon.PdfPix.Bilevel` | `field bool` |
| `emon.PdfPix.Ch` | `field int32` |
| `emon.PdfPix.H` | `field int32` |
| `emon.PdfPix.Px` | `field []byte` |
| `emon.PdfPix.W` | `field int32` |
| `emon.ReaderAtInput` | `(r io.ReaderAt, size int64) Input` |
| `emon.Region` | `struct` |
| `emon.Region.Page` | `field int32` |
| `emon.Region.X0` | `field int32` |
| `emon.Region.X1` | `field int32` |
| `emon.Region.Y0` | `field int32` |
| `emon.Region.Y1` | `field int32` |
| `emon.RunStats` | `struct` |
| `emon.RunStats.Attempts` | `field int32` |
| `emon.RunStats.BytesFetched` | `field uint64` |
| `emon.RunStats.BytesRequests` | `field int32` |
| `emon.RunStats.FetchRounds` | `field int32` |
| `emon.RunStats.FinishUs` | `field uint64` |
| `emon.RunStats.FulfilUs` | `field [8]uint64` |
| `emon.RunStats.InputBytes` | `field uint64` |
| `emon.RunStats.PageRequests` | `field []int32` |
| `emon.RunStats.PageRounds` | `field []int32` |
| `emon.RunStats.PagesUs` | `field uint64` |
| `emon.RunStats.PassUs` | `field uint64` |
| `emon.RunStats.Requests` | `field int32` |
| `emon.RunStats.SourceReads` | `field uint32` |
| `emon.SeekSource` | `struct +unexported` |
| `emon.SeekSource.ReadAt` | `ptr (p []byte, off int64) (int, error)` |
| `emon.SeekSource.Size` | `ptr () int64` |
| `emon.SeekableInput` | `(r io.ReadSeeker) (Input, error)` |
| `emon.SliceSource` | `interface` |
| `emon.SliceSource.AsSlice` | `() []byte` |
| `emon.Spool` | `(r io.Reader, max int64) (*SpoolSource, error)` |
| `emon.SpoolSource` | `type struct` |
| `emon.SpoolSource.AsSlice` | `ptr () []byte` |
| `emon.SpoolSource.ReadAt` | `ptr (p []byte, off int64) (int, error)` |
| `emon.SpoolSource.Size` | `ptr () int64` |
| `emon.SpooledInput` | `(r io.Reader, max int64) (Input, error)` |
| `emon.Transcript` | `type = planrun.Transcript` |
| `planrun.File` | `struct` |
| `planrun.File.Bytes` | `field []byte` |
| `planrun.File.Name` | `field string` |
| `planrun.Transcript` | `struct` |
| `planrun.Transcript.CheckInputs` | `ptr (inputs [][2]int32) error` |
| `planrun.Transcript.Entries` | `field []port.PlanEntry` |
| `planrun.Transcript.Inputs` | `field [][2]int32` |
| `planrun.Transcript.Producer` | `field [][2]string` |
| `planrun.Transcript.ToFiles` | `ptr () []File` |
| `planrun.TranscriptFile` | `const = ""` |
| `planrun.TranscriptFromFiles` | `(files map[string][]byte) (*Transcript, error)` |
| `port.Bitmap` | `struct` |
| `port.Bitmap.Ch` | `field int32` |
| `port.Bitmap.D` | `field []byte` |
| `port.Bitmap.H` | `field int32` |
| `port.Bitmap.Stride` | `field int32` |
| `port.Bitmap.W` | `field int32` |
| `port.FulfilError` | `type struct` |
| `port.FulfilError.Error` | `ptr () string` |
| `port.KindMux` | `struct +unexported` |
| `port.KindMux.Next` | `ptr () (PlanAnswer, error)` |
| `port.KindMux.Start` | `ptr (req PlanRequest, bm PlanBitmaps) error` |
| `port.KindMux.With` | `ptr (kinds []int32, f PlanFulfiller) *KindMux` |
| `port.LayerFull` | `const int32 = 0` |
| `port.LayerNoText` | `const int32 = 1` |
| `port.LayerNoWatermark` | `const int32 = 3` |
| `port.LayerTextOnly` | `const int32 = 2` |
| `port.LayerWatermarkOnly` | `const int32 = 4` |
| `port.NewRenderFulfiller` | `(r Renderer, inputs ...RenderInput) *RenderFulfiller` |
| `port.NewRenderFulfillerFor` | `(r Renderer, idx int, in RenderInput) *RenderFulfiller` |
| `port.ParseRender` | `(req PlanRequest) (int, RenderRequest, error)` |
| `port.PlanAnswer` | `struct` |
| `port.PlanAnswer.Bitmap` | `field *Bitmap` |
| `port.PlanAnswer.ID` | `() int32` |
| `port.PlanAnswer.Kind` | `() int32` |
| `port.PlanAnswer.Rec` | `field []int32` |
| `port.PlanAnswer.Status` | `() int32` |
| `port.PlanBitmaps` | `type map[int32]*Bitmap` |
| `port.PlanConfig` | `struct` |
| `port.PlanConfig.FetchRounds` | `field int32` |
| `port.PlanConfig.MaxRounds` | `field int32` |
| `port.PlanEntry` | `struct` |
| `port.PlanEntry.Ans` | `field []int32` |
| `port.PlanEntry.Bitmap` | `field *Bitmap` |
| `port.PlanEntry.Req` | `field []int32` |
| `port.PlanEntry.Round` | `field int32` |
| `port.PlanError` | `struct` |
| `port.PlanError.Actual` | `field int32` |
| `port.PlanError.Error` | `ptr () string` |
| `port.PlanError.Limit` | `field int32` |
| `port.PlanError.Limited` | `field bool` |
| `port.PlanError.Msg` | `field string` |
| `port.PlanError.Name` | `field string` |
| `port.PlanFulfiller` | `interface` |
| `port.PlanFulfiller.Next` | `() (PlanAnswer, error)` |
| `port.PlanFulfiller.Start` | `(req PlanRequest, bm PlanBitmaps) error` |
| `port.PlanMisses` | `interface` |
| `port.PlanMisses.Take` | `(round int32) []PlanRange` |
| `port.PlanRequest` | `struct` |
| `port.PlanRequest.Arg` | `(k int) int32` |
| `port.PlanRequest.Field` | `(f int32) int32` |
| `port.PlanRequest.ID` | `() int32` |
| `port.PlanRequest.Kind` | `() int32` |
| `port.PlanRequest.Payload` | `field []byte` |
| `port.PlanRequest.Rec` | `field []int32` |
| `port.PlanStats` | `struct` |
| `port.PlanStats.Attempts` | `field int32` |
| `port.PlanStats.BytesRequests` | `field int32` |
| `port.PlanStats.FetchRounds` | `field int32` |
| `port.PlanStats.Requests` | `field int32` |
| `port.PlanStats.Rounds` | `field int32` |
| `port.RenderClip` | `type struct` |
| `port.RenderCut` | `(b *Bitmap, c RenderClip) (*Bitmap, error)` |
| `port.RenderError` | `struct` |
| `port.RenderError.Error` | `ptr () string` |
| `port.RenderError.Msg` | `field string` |
| `port.RenderError.Unsupported` | `field bool` |
| `port.RenderFulfiller` | `struct +unexported` |
| `port.RenderFulfiller.Fulfil` | `ptr (req PlanRequest) (PlanAnswer, error)` |
| `port.RenderFulfiller.Inputs` | `field map[int]RenderInput` |
| `port.RenderFulfiller.Next` | `ptr () (PlanAnswer, error)` |
| `port.RenderFulfiller.Renderer` | `field Renderer` |
| `port.RenderFulfiller.Start` | `ptr (req PlanRequest, _ PlanBitmaps) error` |
| `port.RenderInput` | `struct` |
| `port.RenderInput.Password` | `field []byte` |
| `port.RenderInput.Source` | `field RenderSource` |
| `port.RenderRequest` | `struct` |
| `port.RenderRequest.Clip` | `field *RenderClip` |
| `port.RenderRequest.Dpi` | `field int32` |
| `port.RenderRequest.Layer` | `field int32` |
| `port.RenderRequest.Page` | `field int32` |
| `port.RenderSource` | `interface` |
| `port.RenderSource.Size` | `() int64` |
| `port.RenderSource.io.ReaderAt` | `embedded` |
| `port.RenderStatusUnsupported` | `const int32 = 2` |
| `port.Renderer` | `interface` |
| `port.Renderer.ExactCrop` | `() bool` |
| `port.Renderer.Layers` | `() uint32` |
| `port.Renderer.Render` | `(input RenderSource, password []byte, req RenderRequest) (*Bitmap, error)` |

**Provisional** (shipped, not frozen: live adapters and driver flags)

| Name | Signature |
|---|---|
| `emon.TessOcr` | `struct +unexported` |
| `emon.TessOcr.Fulfil` | `ptr (req port.PlanRequest, bm port.PlanBitmaps, keepBinary bool) (port.PlanAnswer, error)` |
| `emon.TessOcr.Producer` | `ptr () [][2]string` |
| `pdfium.New` | `() *Pdfium` |
| `pdfium.Open` | `(libDirOrFile string) (*Pdfium, error)` |
| `pdfium.Pdfium` | `type struct` |
| `pdfium.Pdfium.ExactCrop` | `ptr () bool` |
| `pdfium.Pdfium.Layers` | `ptr () uint32` |
| `pdfium.Pdfium.Render` | `ptr (input port.RenderSource, password []byte, req port.RenderRequest) (*port.Bitmap, error)` |
| `pdfium.Unavailable` | `type struct` |
| `pdfium.Unavailable.Error` | `ptr () string` |

</details>
<!-- bb docs:end api-index -->
