// THE ENGINE API, Go host (DEVLOG P2.1; SPEC 13, 13.1, 3.9; D058, D040, D089-D091, D144-D147). The twin of
// `host/rust/src/engine.rs`: read that file for the reasoning; this one is the same machine in Go's idiom.
//
//	engine := emon.NewEngine(emon.DefaultConfig())
//	model, err := engine.Convert([]emon.Input{emon.BytesInput(pdf)}, emon.DefaultOptions())
//	json := model.ToJSON(emon.JSONOptions{})
//	html := model.ToHTML(emon.DefaultHTMLOptions())
//	for _, p := range model.Root().Children() { fmt.Println(p.Text()) }
//
// WHAT THE SLICE DOES is the Rust slice's, and says so in the same words (D144): a born-digital PDF's text
// layer -> lines -> paragraphs -> Model -> JSON/HTML. OCR is not wired (the option is kept and changes
// nothing; the Model's header writes no `ocr`), several inputs as one document and `assets` other than
// `none` are `ErrUnsupported`. IMAGES ARE READ (P6.1, D187-D190; they were `unsupported-format` in D144): a PNG,
// JPEG or TIFF becomes one `image` page per frame, through the `ImageDecode` port (`imgdec.go`) asked by the
// plan/fulfil loop, with no OCR yet (P6.5). The loop is wired (D058): a fresh `Src`
// view and fresh arenas per attempt, a miss checked after each stage, a panic in an attempt that missed is
// a miss, never reported. A sync source is read inline and never misses; `Config.SimulateAsync` makes the
// cache behave like a browser Blob.
//
// NOTHING IN THIS PACKAGE NAMES A FILESYSTEM (D058): inputs are `ByteSource`s.
//
// This package is ASSEMBLED, not importable from the repository tree as it stands: `host/go/*.go` is the
// root package `emon` of the module `emon` whose subpackages are `gen/go/*` and `host/go/port`
// (`kin/scripts/emon-go-build`).

package emon

import (
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"
	"sync"
	"time"

	"emon/config"
	"emon/enctx"
	"emon/engine"
	"emon/html"
	"emon/model"
	"emon/plan"
	"emon/planloop"
	"emon/planrun"
	"emon/port"
)

// Transcript is the plan/fulfil transcript (SPEC 13): per input its index and length, and every request
// the kin side made and its answer. The slice issues no requests, so it has no entries.
type Transcript = planrun.Transcript

// ------------------------------------------------------------------ errors

// ErrorKind is which of the Engine's errors an *Error is (Rust's `Error` variants).
type ErrorKind int

const (
	// ErrNoInput: no input was given.
	ErrNoInput ErrorKind = iota
	// ErrLimit: a named bound (SPEC 7.4): `input-bytes`, `pages`, `objects`, ... or the loop's `max-rounds`, `fetch-rounds`.
	ErrLimit
	// ErrPasswordRequired, ErrPasswordIncorrect and ErrEncryptionUnsupported are D040's three encryption errors.
	ErrPasswordRequired
	ErrPasswordIncorrect
	ErrEncryptionUnsupported
	// ErrUnsupportedFormat: not a PDF (no `%PDF-` header) and not a PNG, JPEG or TIFF either.
	ErrUnsupportedFormat
	// ErrDamaged: a PDF kin could not open: its named status (`no-startxref`, `bad-xref`, `no-root`, `no-pages`, ...).
	ErrDamaged
	// ErrSourceChanged: a source that answered fewer bytes than its size said.
	ErrSourceChanged
	// ErrIO: an error from a source's read.
	ErrIO
	// ErrUnsupported: specified, not implemented in this slice.
	ErrUnsupported
	// ErrPlan: the plan/fulfil loop's own errors (a transcript miss, a bad record, ...).
	ErrPlan
	// ErrInternal: the Model kin built failed its own check, or kin panicked: a bug, never the document's fault.
	ErrInternal
)

// Error is the Engine's one error type; `Name()` is the stable name a crawler routes on.
type Error struct {
	Kind    ErrorKind
	Input   int
	Limit   int64  // ErrLimit
	Actual  int64  // ErrLimit
	Status  string // ErrDamaged, and ErrLimit's name
	Message string // ErrIO, ErrUnsupported, ErrInternal
	Plan    *port.PlanError
}

// Name is the stable name.
func (e *Error) Name() string {
	switch e.Kind {
	case ErrNoInput:
		return "no-input"
	case ErrLimit:
		return e.Status
	case ErrPasswordRequired:
		return "password-required"
	case ErrPasswordIncorrect:
		return "password-incorrect"
	case ErrEncryptionUnsupported:
		return "encryption-unsupported"
	case ErrUnsupportedFormat:
		return "unsupported-format"
	case ErrDamaged:
		return e.Status
	case ErrSourceChanged:
		return "source-changed"
	case ErrIO:
		return "io"
	case ErrUnsupported:
		return "unsupported"
	case ErrPlan:
		return e.Plan.Name
	}
	return "internal"
}

func (e *Error) Error() string {
	switch e.Kind {
	case ErrNoInput:
		return "no-input: nothing to convert"
	case ErrLimit:
		return fmt.Sprintf("%s: the limit is %d and this input reached %d", e.Status, e.Limit, e.Actual)
	case ErrPasswordRequired:
		return fmt.Sprintf("password-required: input %d is encrypted and needs a password", e.Input)
	case ErrPasswordIncorrect:
		return fmt.Sprintf("password-incorrect: the password does not open input %d", e.Input)
	case ErrEncryptionUnsupported:
		return fmt.Sprintf("encryption-unsupported: input %d uses a security handler emon does not read", e.Input)
	case ErrUnsupportedFormat:
		return fmt.Sprintf("unsupported-format: input %d is not a PDF, PNG, JPEG or TIFF", e.Input)
	case ErrDamaged:
		return fmt.Sprintf("%s: input %d could not be opened", e.Status, e.Input)
	case ErrSourceChanged:
		return fmt.Sprintf("source-changed: input %d gave fewer bytes than its length", e.Input)
	case ErrIO:
		return fmt.Sprintf("io: input %d: %s", e.Input, e.Message)
	case ErrUnsupported:
		return "unsupported: " + e.Message
	case ErrPlan:
		return e.Plan.Error()
	}
	return "internal: " + e.Message
}

// Unwrap exposes the loop's own error, when that is what this is.
func (e *Error) Unwrap() error {
	if e.Plan != nil {
		return e.Plan
	}
	return nil
}

func planError(p *port.PlanError) *Error {
	if p.Limited {
		return &Error{Kind: ErrLimit, Status: p.Name, Limit: int64(p.Limit), Actual: int64(p.Actual)}
	}
	return &Error{Kind: ErrPlan, Plan: p}
}

// ----------------------------------------------------------------- options

// OcrScope is SPEC 13's `ocr-scope` (D200): what `ocr: always` reads of a born-digital page. OcrScopeFull (the default) the whole render with its
// text; OcrScopeWitness the render without its text and a few witness lines of every font (a font whose witnesses disagree with its text escalates
// the page to full).
type OcrScope int

const (
	OcrScopeFull OcrScope = iota
	OcrScopeWitness
)

// code is the `scope` of kin's `rp-plan`: 0 full, 1 witness.
func (s OcrScope) code() int32 { return int32(s) }

// OcrPolicy is SPEC 13's OCR setting (D038). WIRED (P6.5, D211-D215; switched at P7.S, D200): OcrNever asks for no OCR; OcrAuto and OcrAlways OCR an
// image and a scanned PDF page; a born-digital page (a page with a text layer) is OCR'd and FUSED with its text layer under OcrAlways, and under
// OcrAuto when its health, the images on it or hidden text call for it (`emon.rasterplan`, SPEC 8.3). With no Renderer or no Ocr every request is
// answered `unsupported` and the text layer stands alone.
type OcrPolicy int

const (
	OcrAlways OcrPolicy = iota
	OcrAuto
	OcrNever
)

// code is kin's: 0 never, 1 auto, 2 always (`emon.scan`, `emon.rasterin`).
func (p OcrPolicy) code() int32 {
	switch p {
	case OcrNever:
		return 0
	case OcrAuto:
		return 1
	}
	return 2
}

// Ocr is THE `Ocr` PORT (SPEC 7.2; D008, D095, D211): it fulfils one `ocr` request. A host brings its engine (Tesseract through
// `tess.go` under the `tesseract` build tag; anything else that answers in the layout of `emon.ocr`). It is LIVE only: a gate
// replays a transcript and never calls it. With no port a request is answered `unsupported`, a page then has no text, and
// nothing is guessed.
type Ocr interface {
	// Fulfil answers req, whose bitmap is in bitmaps. keepBinary: the caller records, so the engine's thresholded image, when it
	// has one, goes in the answer's Bitmap (the record says it has one either way).
	Fulfil(req port.PlanRequest, bitmaps port.PlanBitmaps, keepBinary bool) (port.PlanAnswer, error)
	// Producer is what this engine is, for the transcript's `:producer` (D214): name/value pairs.
	Producer() [][2]string
}

// Assets is SPEC 13's `assets` (D117): AssetsNone (the default: nothing runs, every output is what it was without the
// feature), AssetsImages (a figure or seal that is one embedded image gets it) and AssetsFigures (also the others, cropped or
// rendered: P8.5, through the asset unit, D351).
type Assets int

const (
	AssetsNone Assets = iota
	AssetsImages
	AssetsFigures
)

// level is the level kin's `emon.asset` reads: 0 none, 1 images, 2 figures.
func (a Assets) level() int32 { return int32(a) }

// Furniture is SPEC 13's `furniture` (D017, D153): Policy drops what the output already says and keeps the rest as asides, Keep leaves
// every running header, footer and page number in the flow, Drop takes them all out (the ledger says what).
type Furniture int

const (
	FurniturePolicy Furniture = iota
	FurnitureKeep
	FurnitureDrop
)

// mode is the `mode` of kin/furniture.kin's fu-find.
func (f Furniture) mode() int32 { return int32(f) }

// Options is what a conversion is asked to do. DefaultOptions is SPEC 13's defaults.
type Options struct {
	// Locale is a BCP 47 hint, written to the Model's `settings.locale`; empty is none. It decides how an initial
	// (a drop cap) joins its first word when the letter could be a word of its own (SPEC 10.9).
	Locale string
	Ocr    OcrPolicy
	Assets Assets
	// AssetDPI, AssetPixels, AssetBytes, AssetsBytes and AssetSignatures are the settings that go with Assets (SPEC 10.11), written
	// to the Model's header when it is on: the render-crop resolution (150, 36..600), pixels per asset (2048 squared), encoded
	// bytes per asset (4 MiB) and per document (32 MiB), and whether a handwritten signature's or initials' pixels are kept.
	AssetDPI        int32
	AssetPixels     int32
	AssetBytes      int32
	AssetsBytes     int32
	AssetSignatures bool
	// Furniture is what to do with running headers, footers and page numbers (SPEC 10.2).
	Furniture Furniture
	// KeepEvidence keeps the text layer's evidence dump (`Model.Evidence`): a test and debugging aid.
	KeepEvidence bool
	// OcrScope is SPEC 13's `ocr-scope` (P7.S, D200): under `Ocr: OcrAlways` a born-digital page is read whole with its text (OcrScopeFull, the
	// default) or as the render without its text plus witness lines of its text layer's fonts (OcrScopeWitness, a fraction of the OCR time).
	// Written to the Model's `settings` when not full.
	OcrScope OcrScope
	// NoRetarget is a HIDDEN TEST SETTING (P7.4): leave every pixel unread by the page units, as the Engine was before the ink layers and the targeted
	// passes (a measurement's baseline; an embedder never needs it).
	NoRetarget bool
}

// DefaultOptions is SPEC 13's defaults: kin's (`emon.config`'s `cf-defaults`), the parse of the empty text.
func DefaultOptions() Options {
	return optionsOf(mustParseCfg(""))
}

// Config is the ports and bounds an Engine is made with. Limits are SPEC 7.4's `port.LimitOf` kinds
// ([kind, value] pairs: 0 input-bytes, 3 objects, 8 pages, ...): an entry here beats `EMON_LIMIT_*`. A
// conversion with Limits runs ALONE in the process (Go has no goroutine-local state; see port.WithLimits).
type Config struct {
	Limits [][2]int32
	// ChunkSize is the source cache's chunk size (SPEC 13.1: 64 KiB by default).
	ChunkSize int
	// SimulateAsync makes the cache behave like an ASYNC source (a browser Blob): a read of a chunk that is
	// not resident misses, the unit stops with `need-bytes`, and the loop fetches and re-runs it. The output
	// is identical; the rounds and bytes fetched are in RunStats. Real Go sources are sync.
	SimulateAsync bool
	MaxRounds     int32
	FetchRounds   int32
	// Parallel is the `parallel` option (P10.2, SPEC 12.1, D505): how many workers a conversion may spread its fulfilment over. 1, the default, is
	// serial and starts no goroutine; the limit `workers` caps it (kin's `pl-workers` is the clamp). It changes how the work is spread and nothing in
	// the Model, the transcript or the accounting. A conversion's workers run inside its limit scope (`port.WithLimits`) and end before it does.
	Parallel int32
	// Ocr is the `Ocr` port, if the embedder has one (D211). nil: no live OCR; a replay needs none.
	Ocr Ocr
	// Renderer is the `Renderer` port (D007, D127): what fulfils `render` and `crop` requests when the Engine
	// converts or records. nil (the default): none is configured, and a request for one is answered
	// `unsupported` (D199). A replay never calls it (D008). `pdfium.New()` (build tag `pdfium`) is the default
	// engine.
	Renderer port.Renderer
	// PdfCodecs is the JPX and JBIG2 decoder of the `decode-img` request (P6.1a, D117, D336): what an embedder brings for the two PDF
	// image codecs no pure library here reads. nil (the default, and the only Go host there is): those two are answered
	// `unsupported` and the image is `noasset: "codec"`. DCT and CCITT are the host's own. A replay never calls it (D008).
	PdfCodecs PdfCodecs
}

// DefaultConfig is SPEC 13.1's defaults: kin's too.
func DefaultConfig() Config {
	return configOf(mustParseCfg(""))
}

// ----------------------------------------------------------------- options from text (P10.10)
//
// ONE `key=value` GRAMMAR, PARSED BY KIN (`emon.config`) INTO A `Cfg`: the browser binding, the drivers, and later the C binding and the pod say what a
// conversion is asked to do in the same text. `Options` and `Config` stay the idiomatic typed structs; these functions are the thin wrapper that reads one
// back out of the `Cfg` kin filled. A key's name, a word's spelling, a default and a range are not known here.

// Parsed is what a text asks for: the Options and the Config (limits, chunk size, the loop's bounds), and the browser binding's own switches.
type Parsed struct {
	Options Options
	Config  Config
	// Record is `record=1`: keep a transcript of the conversion. JsOcr is `js-ocr=1`: the host owes the `ocr` requests.
	Record, JsOcr bool
	// JsRender is `js-render=1`: the host owes the `render` and `crop` requests (P11.4c).
	JsRender bool
	// Producer is `producer.NAME=VALUE`, in order: what the transcript says produced it.
	Producer [][2]string
}

func cellsOfString(s string) *port.Cells {
	c := port.NewCells()
	for _, ch := range s {
		port.CellsPush(c, int32(ch))
	}
	return c
}

func stringOfCells(c *port.Cells, from, n int32) string {
	var sb strings.Builder
	for i := from; i < from+n; i++ {
		sb.WriteRune(rune(port.CellsAt(c, i)))
	}
	return sb.String()
}

func parseCfg(text string) (*enctx.Cfg, error) {
	c := enctx.NewCfg()
	if config.CfParse(cellsOfString(text), c) != 0 {
		return nil, errors.New(c.Msg.String())
	}
	return c, nil
}

func mustParseCfg(text string) *enctx.Cfg {
	c, err := parseCfg(text)
	if err != nil {
		panic(err)
	}
	return c
}

// ocrPolicyOf: kin's code is 0 never, 1 auto, 2 always.
func ocrPolicyOf(code int32) OcrPolicy {
	switch code {
	case 0:
		return OcrNever
	case 1:
		return OcrAuto
	}
	return OcrAlways
}

func optionsOf(c *enctx.Cfg) Options {
	o := Options{
		Ocr:             ocrPolicyOf(c.Ocr),
		Assets:          Assets(c.Level),
		AssetDPI:        c.Dpi,
		AssetPixels:     c.Pixels,
		AssetBytes:      c.Abyte,
		AssetsBytes:     c.Tbyte,
		AssetSignatures: c.Sigs != 0,
		Furniture:       Furniture(c.Furn),
		KeepEvidence:    c.Keep != 0,
		OcrScope:        OcrScope(c.Scope),
	}
	if c.Haveloc == 1 {
		o.Locale = stringOfCells(c.Lcl, 0, port.CellsLen(c.Lcl))
	}
	return o
}

func configOf(c *enctx.Cfg) Config {
	cfg := Config{ChunkSize: int(c.Chunk), MaxRounds: c.Maxr, FetchRounds: c.Fetchr, Parallel: c.Par}
	for i := int32(0); i < port.CellsLen(c.Lims)/2; i++ {
		cfg.Limits = append(cfg.Limits, [2]int32{port.CellsAt(c.Lims, 2*i), port.CellsAt(c.Lims, 2*i+1)})
	}
	return cfg
}

// ParseOptions reads `key=value` lines, parsed by kin: the settings of SPEC 13 by their names, `limit.NAME=N` for the limits, the loop's bounds, and
// the ports the host has. An error is the sentence a driver prints after `usage`.
func ParseOptions(text string) (*Parsed, error) {
	c, err := parseCfg(text)
	if err != nil {
		return nil, err
	}
	p := &Parsed{Options: optionsOf(c), Config: configOf(c), Record: c.Rec != 0, JsOcr: c.Jsocr != 0, JsRender: c.Jsrender != 0}
	for at := int32(0); at < port.CellsLen(c.Prod); {
		kl, vl := port.CellsAt(c.Prod, at), port.CellsAt(c.Prod, at+1)
		p.Producer = append(p.Producer, [2]string{stringOfCells(c.Prod, at+2, kl), stringOfCells(c.Prod, at+2+kl, vl)})
		at += 2 + kl + vl
	}
	return p, nil
}

// IsOptionKey reports whether name (a driver's `--name=`) is a key of the grammar, which kin owns.
func IsOptionKey(name string) bool {
	t := cellsOfString(name)
	return config.CfIsKey(t, 0, port.CellsLen(t)) == 1
}

// JSONOptions are SPEC 4's: the indented form or the compact one.
type JSONOptions struct {
	Pretty bool
}

// HTMLOptions are SPEC 5.2's (D133). DefaultHTMLOptions is clean HTML: no ids, no geometry, milestones on,
// furniture templates kept, the ledger off. The zero value is NOT the default (Milestones is false in it):
// use DefaultHTMLOptions. The option bits are `kin/html.kin`'s (`ho-*`).
type HTMLOptions struct {
	IDs      bool
	Geometry bool
	// ConfidenceBelow, when set, writes `data-emon-conf` on nodes whose confidence (0..=1000) is under it.
	ConfidenceBelow *uint32
	Ledger          bool
	Pretty          bool
	FurnitureDrop   bool
	Milestones      bool
	InitialsPlain   bool
	AlignStyle      bool
	// Assets is what the HTML does with the Model's assets (SPEC 10.11): HTMLAssetsInline (the default) writes `data:` URIs,
	// HTMLAssetsExternal writes AssetPrefix + the file name (the caller writes Model.AssetFiles() beside it), HTMLAssetsOmit
	// writes the placeholders as if no asset existed. A Model without assets gives the same HTML under all three.
	Assets      HTMLAssets
	AssetPrefix string
}

// HTMLAssets is HTMLOptions.Assets.
type HTMLAssets int

const (
	HTMLAssetsInline HTMLAssets = iota
	HTMLAssetsExternal
	HTMLAssetsOmit
)

// DefaultHTMLOptions is clean HTML.
func DefaultHTMLOptions() HTMLOptions {
	return HTMLOptions{Milestones: true}
}

func (o HTMLOptions) bits() (int32, int32) {
	var b int32
	if o.IDs {
		b |= html.HoIds()
	}
	if o.Geometry {
		b |= html.HoGeometry()
	}
	if o.ConfidenceBelow != nil {
		b |= html.HoConfidence()
	}
	if o.Ledger {
		b |= html.HoLedger()
	}
	if o.Pretty {
		b |= html.HoPretty()
	}
	if o.FurnitureDrop {
		b |= html.HoFurnitureDrop()
	}
	if !o.Milestones {
		b |= html.HoNoMilestones()
	}
	if o.InitialsPlain {
		b |= html.HoInitialsPlain()
	}
	if o.AlignStyle {
		b |= html.HoAlignStyle()
	}
	switch o.Assets {
	case HTMLAssetsExternal:
		b |= html.HoAssetsExternal()
	case HTMLAssetsOmit:
		b |= html.HoAssetsOmit()
	}
	var below uint32
	if o.ConfidenceBelow != nil {
		below = *o.ConfidenceBelow
	}
	if below > 1000 {
		below = 1000
	}
	return b, int32(below)
}

// Output is an output format, for callers (and the driver) that choose one by value: exactly one is set.
type Output struct {
	JSON *JSONOptions
	HTML *HTMLOptions
}

// ---------------------------------------------------------- the source cache
//
// A `port.SrcBacking` over a `ByteSource`: aligned chunks (SPEC 13.1), resident or not. SYNC: a read of a
// chunk that is not resident fills it inline and answers. SIMULATED ASYNC: it records the chunk as pending
// and misses; `fetch` (what the loop's `bytes` request does) makes the pending chunks resident with a
// read-ahead that doubles per round, coalescing a run of chunks into one read of the source. A read that
// fails, or returns fewer bytes than the size said, is remembered as `source-changed`.

type chunkCache struct {
	src      ByteSource
	n        int
	chunk    int
	deferred bool

	mu     sync.Mutex
	chunks [][]byte
	// 1 for each resident chunk, for kin's planner of the fetches (`fp-plan`)
	res     *port.Cells
	pending map[int]bool
	wanted  map[int]bool
	failure string
	failed  bool
	short   bool
	bytes   uint64
	reads   uint32
	hints   uint32
}

func newChunkCache(src ByteSource, n, chunk int, deferred bool) *chunkCache {
	nc := (n + chunk - 1) / chunk
	res := port.NewCells()
	for i := 0; i < nc; i++ {
		port.CellsPush(res, 0)
	}
	return &chunkCache{src: src, n: n, chunk: chunk, deferred: deferred, chunks: make([][]byte, nc), res: res,
		pending: map[int]bool{}, wanted: map[int]bool{}}
}

func (c *chunkCache) nchunks() int { return (c.n + c.chunk - 1) / c.chunk }

// load reads chunks first..=last from the source into the cache, in one read of the source (mu held).
func (c *chunkCache) load(first, last int) bool {
	lo := first * c.chunk
	hi := min((last+1)*c.chunk, c.n)
	buf := make([]byte, hi-lo)
	got := 0
	for got < len(buf) {
		n, err := c.src.ReadAt(buf[got:], int64(lo+got))
		got += n
		if got >= len(buf) {
			break
		}
		if err == nil && n > 0 {
			continue
		}
		if err == nil || errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
			c.short = true
			c.failed = true
			c.failure = fmt.Sprintf("the source gave %d bytes at offset %d and its length said %d", got, lo, len(buf))
			return false
		}
		c.failed = true
		c.failure = err.Error()
		return false
	}
	c.reads++
	c.bytes += uint64(len(buf))
	for k := first; k <= last; k++ {
		a := k*c.chunk - lo
		b := min(a+c.chunk, len(buf))
		c.chunks[k] = append([]byte(nil), buf[a:b]...)
		port.CellsSet(c.res, int32(k), 1)
	}
	return true
}

// touch is true when chunk k is resident now (a sync cache fills it here; an async one records the miss).
func (c *chunkCache) touch(k int) bool {
	if c.chunks[k] != nil {
		return true
	}
	if c.failed {
		return false
	}
	if c.deferred {
		c.pending[k] = true
		return false
	}
	return c.load(k, k)
}

// planFetch is the ranges to fetch for fetch round `round`: every pending (or hinted) chunk and 2^round - 1
// chunks each side of it, the resident ones left out, a run of chunks one range. THE PLAN IS KIN'S (`fp-plan`): the host says what
// was missed (in ascending order) and what is resident, and takes the ranges.
func (c *chunkCache) planFetch(input int32, round int32) []port.PlanRange {
	c.mu.Lock()
	defer c.mu.Unlock()
	seeds := func(set map[int]bool) *port.Cells {
		ks := make([]int, 0, len(set))
		for k := range set {
			ks = append(ks, k)
		}
		sort.Ints(ks)
		out := port.NewCells()
		for _, k := range ks {
			port.CellsPush(out, int32(k))
		}
		return out
	}
	pend, hint := seeds(c.pending), seeds(c.wanted)
	c.pending = map[int]bool{}
	c.wanted = map[int]bool{}
	out := port.NewCells()
	planloop.FpPlan(pend, hint, c.res, int32(c.chunk), int32(c.n), input, round, out)
	var ranges []port.PlanRange
	for i := int32(0); i < port.CellsLen(out)/3; i++ {
		ranges = append(ranges, port.PlanRange{Input: port.CellsAt(out, 3*i), Offset: port.CellsAt(out, 3*i+1), Length: port.CellsAt(out, 3*i+2)})
	}
	return ranges
}

// fetch makes [offset, offset + length) resident: what a `bytes` request does. False is the source's failure.
func (c *chunkCache) fetch(offset, length int32) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if length <= 0 {
		return true
	}
	first := int(offset) / c.chunk
	last := (int(offset) + int(length) - 1) / c.chunk
	for k := first; k <= last; {
		if c.chunks[k] != nil {
			k++
			continue
		}
		e := k
		for e < last && c.chunks[e+1] == nil {
			e++
		}
		if !c.load(k, e) {
			return false
		}
		k = e + 1
	}
	return true
}

func (c *chunkCache) fail() (bool, bool, string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.failed, c.short, c.failure
}

func (c *chunkCache) stats() (uint32, uint64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.reads, c.bytes
}

// prefetchEnds is SPEC 13.1's intake round 0: the first and the last chunk (the header, `startxref`, the trailer).
func (c *chunkCache) prefetchEnds() {
	if c.n == 0 {
		return
	}
	n := c.nchunks()
	c.fetch(0, int32(min(c.chunk, c.n)))
	c.fetch(int32((n-1)*c.chunk), int32(c.n-(n-1)*c.chunk))
}

func (c *chunkCache) Len() int32 { return int32(c.n) }

func (c *chunkCache) At(i int32) int32 {
	k := int(i) / c.chunk
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.touch(k) {
		return -1
	}
	return int32(c.chunks[k][int(i)-k*c.chunk])
}

func (c *chunkCache) Span(s int32, buf []byte) bool {
	if len(buf) == 0 {
		return true
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	first := int(s) / c.chunk
	last := (int(s) + len(buf) - 1) / c.chunk
	ok := true
	for k := first; k <= last; k++ {
		if c.touch(k) {
			ch := c.chunks[k]
			lo := max(int(s), k*c.chunk)
			hi := min(int(s)+len(buf), k*c.chunk+len(ch))
			copy(buf[lo-int(s):hi-int(s)], ch[lo-k*c.chunk:hi-k*c.chunk])
		} else {
			ok = false
		}
	}
	return ok
}

func (c *chunkCache) Hint(s, e int32) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.hints++
	if c.deferred && e > s && c.n > 0 {
		first := max(int(s), 0) / c.chunk
		last := min(int(e)-1, c.n-1) / c.chunk
		for k := first; k <= last; k++ {
			if c.chunks[k] == nil {
				c.wanted[k] = true
			}
		}
	}
}

// sliceBacking is an in-memory source, read in place: no cache, no copy, no miss.
type sliceBacking []byte

func (b sliceBacking) Len() int32       { return int32(len(b)) }
func (b sliceBacking) At(i int32) int32 { return int32(b[i]) }
func (b sliceBacking) Span(s int32, buf []byte) bool {
	copy(buf, b[s:int(s)+len(buf)])
	return true
}
func (b sliceBacking) Hint(s, e int32) {}

type cacheMisses struct {
	cache *chunkCache
	input int32
}

func (m *cacheMisses) Take(round int32) []port.PlanRange { return m.cache.planFetch(m.input, round) }

// ------------------------------------------------------------ raster intake
//
// An input that kin's `RsSniff` says is a PNG, a JPEG or a TIFF (SPEC 2.0, D187) is not a PDF: its document pass is
// `emon.rasterin`'s `RsDoc` (inside `EnAttempt`), which asks the host's `ImageDecode` port (`imgdec.go`) for each frame
// through the plan/fulfil loop and builds one `image` page per frame.

// spanSource is a ByteSource over a span of an input's ByteSource: what the decode request names (input,
// offset, length), read in place.
type spanSource struct {
	src      ByteSource
	off, len int64
}

func (s spanSource) Size() int64 { return s.len }
func (s spanSource) ReadAt(p []byte, off int64) (int, error) {
	if off >= s.len {
		return 0, io.EOF
	}
	n := len(p)
	short := false
	if int64(n) > s.len-off {
		n = int(s.len - off)
		short = true
	}
	got, err := s.src.ReadAt(p[:n], s.off+off)
	if err == nil && short {
		err = io.EOF
	}
	return got, err
}

// portFulfiller is the LIVE ports as a fulfiller (SPEC 7.2): `ImageDecode` (D187) answers `decode` requests for this input
// from the host's codecs; `Ocr` (D211) answers `ocr` requests from the embedder's engine, or `unsupported` when it has none.
// No Renderer is configured (P6.2). `keep` holds the pixels in a decode answer (a recording wants them, as canonical PNG,
// and so does a live OCR engine; a plain conversion needs only the numbers); `recording` also keeps the OCR engine's
// thresholded image, which only a transcript wants (D211).
func portFulfiller(source ByteSource, idx int, keep, recording bool, ocr Ocr, codecs PdfCodecs) *port.PlanSync {
	return &port.PlanSync{F: func(req port.PlanRequest, bm port.PlanBitmaps) (port.PlanAnswer, error) {
		// WHICH PORT a request goes to, or that none is configured, is kin's (`pl-route`); this host has no renderer (D199: `livePorts`
		// sends a render to one when it has one)
		switch planrun.Route(req.Kind(), ocr != nil, false) {
		case planloop.RtDecodeImg():
			return decodeImg(req, source, idx, codecs)
		case planloop.RtUnsupported():
			return planrun.UnsupportedAnswer(req), nil
		case planloop.RtOcr():
			failed := func(status int32) (port.PlanAnswer, error) {
				c := port.NewCells()
				plan.AnFail(c, port.PlanKindOcr, req.ID(), status)
				return port.PlanAnswer{Rec: cellsVec(c)}, nil
			}
			return ocrSafe(ocr, req, bm, recording, failed)
		case planloop.RtDecode():
		default:
			return port.PlanAnswer{}, planrun.FulfilFailed("no port is configured for this request kind")
		}
		input, off, n, filter, frame := req.Field(plan.DcInput()), req.Field(plan.DcOffset()), req.Field(plan.DcLength()), req.Field(plan.DcFilter()), req.Field(plan.DcFrame())
		if int(input) != idx || off < 0 || n < 0 || int64(off)+int64(n) > source.Size() {
			return port.PlanAnswer{}, planrun.FulfilFailed("a decode request names bytes the input does not have")
		}
		fail := func(status int32) (port.PlanAnswer, error) {
			c := port.NewCells()
			plan.AnFail(c, port.PlanKindDecode, req.ID(), status)
			return port.PlanAnswer{Rec: cellsVec(c)}, nil
		}
		// a DCT stream is a JPEG file: the span is read in place (a scanned PDF page's image, D212); the other PDF
		// image filters (JPX, JBIG2, CCITT) are P6.1a's
		if filter != plan.FltNone() && filter != plan.FltDct() {
			return fail(plan.PsUnsupported())
		}
		span := spanSource{src: source, off: int64(off), len: int64(n)}
		d, err := imgDecodeSafe(span, frame)
		if err != nil {
			var f *imgFail
			if errors.As(err, &f) {
				switch f.Kind {
				case imgUnsupported:
					return fail(plan.PsUnsupported())
				case imgLimit:
					actual := int32(1<<31 - 1)
					if f.Pixels < uint64(actual) {
						actual = int32(f.Pixels)
					}
					return port.PlanAnswer{Rec: []int32{5, port.PlanKindDecode, req.ID(), plan.PsLimit(), actual}}, nil
				}
			}
			return fail(plan.PsFailed())
		}
		c := port.NewCells()
		bl := int32(0)
		if d.Bilevel {
			bl = 1
		}
		plan.AnDecode(c, req.ID(), d.W, d.H, d.Ch, d.Dpi100, bl, d.Frames)
		a := port.PlanAnswer{Rec: cellsVec(c)}
		if keep && (filter != plan.FltDct() || ocr != nil) && d.Pixels != nil {
			a.Bitmap = port.BitmapFromBytes(d.W, d.H, d.Ch, d.Pixels)
		}
		return a, nil
	}}
}

// readSpan is a span of the input, whole or an error (the `decode-img` request names bytes of the input by offset and length).
func readSpan(source ByteSource, off, n int64) ([]byte, error) {
	if off < 0 || n < 0 || off+n > source.Size() {
		return nil, planrun.FulfilFailed("a decode-img request names bytes the input does not have")
	}
	b := make([]byte, n)
	got := 0
	for got < len(b) {
		k, err := source.ReadAt(b[got:], off+int64(got))
		got += k
		if got >= len(b) {
			break
		}
		if err != nil || k == 0 {
			return nil, planrun.FulfilFailed("a read of the source failed")
		}
	}
	return b, nil
}

// namedBytes is the bytes a `decode-img` request names (kin's `dq-where` / `dq-offset` / `dq-length`): a span of this input (where is
// its index), or of the unit's PAYLOAD (where -2); -1 names none (P6.1a, D291).
func namedBytes(req port.PlanRequest, source ByteSource, idx int, where, offset, length int32) ([]byte, error) {
	wh, off, n := req.Field(where), int64(req.Field(offset)), int64(req.Field(length))
	switch {
	case wh == -1:
		return nil, nil
	case wh == -2:
		if off < 0 || n < 0 || off+n > int64(len(req.Payload)) {
			return nil, planrun.FulfilFailed("a decode-img request names payload bytes the unit did not make")
		}
		return req.Payload[off : off+n], nil
	case int(wh) == idx:
		return readSpan(source, off, n)
	}
	return nil, planrun.FulfilFailed("a decode-img request names an input this run does not have")
}

// decodeImg is the `decode-img` request (kind 6; P6.1a, D117, D291): one PDF image's codestream to its 8-bit pixels (`pdfimg.go`). The
// answer always carries the pixels (kin adopts them); a transcript stores them, never the codestream.
func decodeImg(req port.PlanRequest, source ByteSource, idx int, codecs PdfCodecs) (port.PlanAnswer, error) {
	a := pdfArgs{filter: req.Field(plan.DqFilter()), w: req.Field(plan.DqW()), h: req.Field(plan.DqH()), comps: req.Field(plan.DqComps()), bpc: req.Field(plan.DqBpc()), k: req.Field(plan.DqK()), columns: req.Field(plan.DqColumns()), rows: req.Field(plan.DqRows()), flags: req.Field(plan.DqCcitt())}
	data, err := namedBytes(req, source, idx, plan.DqWhere(), plan.DqOffset(), plan.DqLength())
	if err != nil {
		return port.PlanAnswer{}, err
	}
	globals, err := namedBytes(req, source, idx, plan.DqGwhere(), plan.DqGoffset(), plan.DqGlength())
	if err != nil {
		return port.PlanAnswer{}, err
	}
	fail := func(status int32) (port.PlanAnswer, error) {
		c := port.NewCells()
		plan.AnFail(c, port.PlanKindDecodeImg, req.ID(), status)
		return port.PlanAnswer{Rec: cellsVec(c)}, nil
	}
	p, derr := pdfDecodeSafe(a, data, globals, codecs, uint64(port.LimitOf(9)))
	if derr != nil {
		var f *imgFail
		if errors.As(derr, &f) {
			switch f.Kind {
			case imgUnsupported:
				return fail(plan.PsUnsupported())
			case imgLimit:
				actual := int32(1<<31 - 1)
				if f.Pixels < uint64(actual) {
					actual = int32(f.Pixels)
				}
				return port.PlanAnswer{Rec: []int32{5, port.PlanKindDecodeImg, req.ID(), plan.PsLimit(), actual}}, nil
			}
		}
		return fail(plan.PsFailed())
	}
	c := port.NewCells()
	bl := int32(0)
	if p.Bilevel {
		bl = 1
	}
	plan.AnDecodeImg(c, req.ID(), p.W, p.H, p.Ch, bl)
	return port.PlanAnswer{Rec: cellsVec(c), Bitmap: port.BitmapFromBytes(p.W, p.H, p.Ch, p.Px)}, nil
}

// pdfDecodeSafe is pdfDecode, a panic of a codec on damage being a damaged image and not a crashed process.
func pdfDecodeSafe(a pdfArgs, data, globals []byte, codecs PdfCodecs, maxPixels uint64) (p *PdfPix, err error) {
	defer func() {
		if r := recover(); r != nil {
			p, err = nil, imgFailedf("the decoder panicked")
		}
	}()
	return pdfDecode(a, data, globals, codecs, maxPixels)
}

// ocrSafe calls the Ocr port; an engine that panics on a page is a failed OCR of it, not a crashed process.
func ocrSafe(ocr Ocr, req port.PlanRequest, bm port.PlanBitmaps, keepBinary bool, failed func(int32) (port.PlanAnswer, error)) (a port.PlanAnswer, err error) {
	defer func() {
		if p := recover(); p != nil {
			a, err = failed(port.PlanStatusFailed)
		}
	}()
	return ocr.Fulfil(req, bm, keepBinary)
}

// imgDecodeSafe is imgDecode, a panic of a codec on damage being a damaged image and not a crashed process.
func imgDecodeSafe(src ByteSource, frame int32) (d *imgDecoded, err error) {
	defer func() {
		if p := recover(); p != nil {
			d, err = nil, imgFailedf("the decoder panicked")
		}
	}()
	return imgDecode(src, frame)
}

// rstep is one attempt's end for either kind of input: it missed, it asked for something, or it finished.
type rstep struct {
	miss, requests bool
	out            outcome
	// the document pass is done and the page units are next (P7.U)
	doc *unitDoc
}

// localeCells is the locale hint's code points (kin's `loc`: what `emon.scan` reads the OCR language from); empty for none.
func localeCells(opts Options) *port.Cells {
	c := port.NewCells()
	for _, ch := range opts.Locale {
		port.CellsPush(c, int32(ch))
	}
	return c
}

// unitDoc is what the document pass leaves for the page units and the document unit (P7.U, D195): kin's contexts (`emon.enctx`), and what the
// input said it is. `doc.Kind` says whether it is a PDF (the text layer's arenas, the catalogue's extras, the plan of what each page's unit will
// ask) or a raster input.
type unitDoc struct {
	m    *arenas
	ev   *enctx.EvArenas
	doc  *enctx.Doc
	info Intake
}

// intakeOf is what kin's document pass says the input is (`Doc`'s Kind, Ifmt, Ipages, Imajor, Iminor, Ienc).
func intakeOf(doc *enctx.Doc, n uint64) Intake {
	format := "tiff"
	switch doc.Ifmt {
	case engine.EnFmtPdf():
		format = "pdf"
	case engine.EnFmtPng():
		format = "png"
	case engine.EnFmtJpeg():
		format = "jpeg"
	}
	return Intake{Format: format, VersionMajor: doc.Imajor, VersionMinor: doc.Iminor, Pages: int(max(doc.Ipages, 0)), Encrypted: doc.Ienc == 1, Bytes: n}
}

// unitPage is one page's (a raster input's frame's) unit (P7.U, D195): its own requests, answers and BitmapSet; it writes nothing until its
// answers are in, and never reads the input. One call of kin's.
// dry makes it a DRY attempt (P10.2, `pu-dry`): the part before the first write, which a parallel host runs for the pages ahead to learn what they will ask.
func unitPage(d *unitDoc, i, idx int, n uint64, opts Options, dry bool, answers, rq *port.Cells, bms *port.BitmapSet) int32 {
	c := cfgOf(opts)
	c.Dry = boolInt(dry)
	return engine.EnPage(c, d.doc, d.ev, int32(i), int32(idx), int32(n), answers, rq, bms)
}

// finishDoc is the document unit (stage 3) over the text layer and whatever the page units added to it: fusion, the evidence, furniture,
// tables, headings, paragraphs and columns, the Model. All of it is kin's (`emon.engine`), whatever the kind of the input.
func finishDoc(d *unitDoc, opts Options) outcome {
	r := engine.EnFinish(cfgOf(opts), d.doc, d.ev, d.m, enctx.NewScratch())
	return finished(r, d.doc, d.m, d.info, opts)
}

// finished is what kin's document unit answered (`emon.engine`: 0 built, 1 built with the asset unit still to run, 2 `pl-check`, 3
// `model-check`, 4 `model-check-assets`).
func finished(r int32, doc *enctx.Doc, m *arenas, info Intake, opts Options) outcome {
	switch r {
	case 0, 1:
		var evs *string
		if opts.KeepEvidence {
			s := doc.Evidence.String()
			evs = &s
		}
		// `figures` leaves the asset stage to the asset unit (D351): what it needs
		var pending *pendingAssets
		if r == 1 {
			pending = &pendingAssets{doc.Cand, doc.Cbytes, doc.Rfrom}
		}
		return outcome{built: &built{arenas: m, evidence: evs, intake: info, pending: pending}}
	case 2:
		return outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("pl-check %d", doc.Fcode)}}
	case 3:
		return outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("model-check %d at node %d", doc.Fcode, doc.Fnode)}}
	}
	return outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("model-check-assets %d at %d", doc.Fcode, doc.Fnode)}}
}

// cfgOf is the Options the document unit reads (`emon.enctx`'s `Cfg`).
func cfgOf(opts Options) *enctx.Cfg {
	c := enctx.NewCfg()
	c.Ocr = opts.Ocr.code()
	c.Scope = opts.OcrScope.code()
	c.Furn = opts.Furniture.mode()
	c.Level = opts.Assets.level()
	c.Dpi = opts.AssetDPI
	c.Pixels = opts.AssetPixels
	c.Abyte = opts.AssetBytes
	c.Tbyte = opts.AssetsBytes
	c.Sigs = boolInt(opts.AssetSignatures)
	c.Keep = boolInt(opts.KeepEvidence)
	c.Nort = boolInt(opts.NoRetarget)
	if opts.Locale != "" {
		c.Haveloc = 1
		c.Lcl = localeCells(opts)
	}
	return c
}

// sealArenas is `model-check`, and `model-check-assets` when `assets` is on, over a finished Model (kin's `EnSeal`).
func sealArenas(m *arenas, opts Options) *outcome {
	out := port.NewCells()
	switch engine.EnSeal(m, port.NewCells(), out, opts.Assets.level()) {
	case 0:
		return nil
	case 1:
		return &outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("model-check %d at node %d", port.CellsAt(out, 0), port.CellsAt(out, 1))}}
	}
	return &outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("model-check-assets %d at %d", port.CellsAt(out, 0), port.CellsAt(out, 1))}}
}

func boolInt(b bool) int32 {
	if b {
		return 1
	}
	return 0
}

// assetStageFigures is P8.5's asset stage at the level `figures` (SPEC 10.11, D351): one attempt of the asset unit, on copies the
// caller made. It writes the Model's settings and every figure's and seal's asset or reason, and any `crop` or `decode` request it
// has no answer to into rq (then its results are meaningless: the loop answers and runs it again).
func assetStageFigures(m *arenas, p *pendingAssets, opts Options, input, nbytes int32, ans, rq *port.Cells, bms *port.BitmapSet) {
	engine.EnAssetFigures(cfgOf(opts), m, p.cand, p.cbytes, p.rfrom, enctx.NewPass(), input, nbytes, ans, rq, bms)
}

// dispatch is ONE ATTEMPT OF THE DOCUMENT PASS for one input, from scratch on a fresh view and fresh contexts: kin's `EnAttempt` (a PNG, JPEG or
// TIFF by its sniff is read by the raster unit, anything else by the PDF one, which refuses what is not a PDF by name). A miss at any
// checkpoint ends it at once: what a missed attempt computed is never looked at, and a failure or a panic in one that missed is a miss, never
// reported (SPEC 13.1).
func dispatch(view, pw *port.Src, answers, rq *port.Cells, idx int, opts Options, intakeOnly bool, n uint64) (s rstep) {
	defer func() {
		if p := recover(); p != nil {
			if view.Missed() {
				s = rstep{miss: true}
			} else {
				s = rstep{out: outcome{err: panicError(p)}}
			}
		}
	}()
	m, ev, doc := newArenas(), enctx.NewEvArenas(), enctx.NewDoc()
	switch engine.EnAttempt(view, pw, cfgOf(opts), doc, ev, m, enctx.NewPass(), answers, rq, int32(idx), boolInt(intakeOnly)) {
	case engine.AtMiss():
		return rstep{miss: true}
	case engine.AtRequests():
		return rstep{requests: true}
	case engine.AtFailed():
		return rstep{out: outcome{err: statusError(idx, doc.Fcode)}}
	case engine.AtInternal():
		return rstep{out: outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("the raster unit answered status %d", doc.Fcode)}}}
	case engine.AtIntake():
		info := intakeOf(doc, n)
		return rstep{out: outcome{intake: &info}}
	}
	return rstep{doc: &unitDoc{m: m, ev: ev, doc: doc, info: intakeOf(doc, n)}}
}

// ------------------------------------------------------------------- stats

// RunStats is what the loop did, for the driver and the async gate. Not part of the Model (the Model is the
// same whichever way the bytes arrived).
type RunStats struct {
	Attempts      int32
	FetchRounds   int32
	BytesRequests int32
	Requests      int32
	SourceReads   uint32
	BytesFetched  uint64
	InputBytes    uint64
	// per page unit (P7.U): the rounds it took and the requests it made, in page order
	PageRounds   []int32
	PageRequests []int32
	// P7.S (D210, `bb fusion-bench`): wall time in microseconds. FulfilUs is what the host's ports took, summed over every request of the input, by
	// request kind (index: 1 render, 2 decode, 3 `ocr` of a page's first pass, 4 bytes, 5 crop, 6 decode-img; 7 the `ocr` requests of every later
	// pass). PassUs is the document pass (a loop of its own), PagesUs every page unit with the ports' time in it, FinishUs the document unit
	// (stage 3). Counters: the Model never depends on them.
	FulfilUs [8]uint64
	PassUs   uint64
	PagesUs  uint64
	FinishUs uint64
}

// timedPorts is the ports' fulfiller, timed by request kind (RunStats.FulfilUs).
type timedPorts struct {
	inner port.PlanFulfiller
	us    [8]uint64
}

func (t *timedPorts) Start(req port.PlanRequest, bm port.PlanBitmaps) error {
	t0 := time.Now()
	err := t.inner.Start(req, bm)
	k := int(req.Kind())
	if req.Kind() == port.PlanKindOcr && req.ID()%256 != 16 {
		k = 7
	}
	if k < 0 || k > 7 {
		k = 0
	}
	t.us[k] += uint64(time.Since(t0).Microseconds())
	return err
}

func (t *timedPorts) Next() (port.PlanAnswer, error) { return t.inner.Next() }

// ------------------------------------------------------------------- intake

// Intake is stage 0, cheap: what the file says it is (SPEC 13). Opening it reads the header, the trailer,
// the cross reference and the page tree, no page.
type Intake struct {
	Format       string
	VersionMajor int32
	VersionMinor int32
	Pages        int
	Encrypted    bool
	Bytes        uint64
}

// ------------------------------------------------------------------- engine

// Engine converts documents. It holds only its Config: one Engine serves any number of conversions, on any
// number of goroutines.
type Engine struct {
	cfg Config
}

// NewEngine makes an Engine.
func NewEngine(cfg Config) *Engine {
	if cfg.ChunkSize < 1 {
		cfg.ChunkSize = 1
	}
	return &Engine{cfg: cfg}
}

type built struct {
	arenas   *arenas
	evidence *string
	intake   Intake
	// At `assets: figures` the document unit stops before the asset stage (D351): what the asset unit still needs. nil otherwise.
	pending *pendingAssets
}

// pendingAssets is the asset unit's input at the level `figures` (SPEC 10.11, D351): the candidate rows and their bytes the
// document pass made, and rfrom, 0 for a PDF or the `afm` of a raster input's format.
type pendingAssets struct {
	cand   *port.Cells
	cbytes *port.Buf
	rfrom  int32
}

type outcome struct {
	built  *built
	intake *Intake
	err    *Error
}

// damaged is a damaged input, named by kin's `ds` (the status itself when the table names it, `unreadable` otherwise).
func damaged(input int, code int32) *Error {
	var name strings.Builder
	planloop.DsEmitName(&name, planloop.DsKnown(code))
	return &Error{Kind: ErrDamaged, Input: input, Status: name.String()}
}

// statusError is what a document status is as the Engine reports it: which error it is is kin's (`ds-class`).
func statusError(input int, status int32) *Error {
	switch planloop.DsClass(status) {
	case 1:
		return &Error{Kind: ErrUnsupportedFormat, Input: input}
	case 2:
		return &Error{Kind: ErrPasswordRequired, Input: input}
	case 3:
		return &Error{Kind: ErrPasswordIncorrect, Input: input}
	case 4:
		return &Error{Kind: ErrEncryptionUnsupported, Input: input}
	}
	return damaged(input, status)
}

// finishGuarded runs the document unit with a panic in it made the Engine's named error (`panicError`), as in a page unit.
func finishGuarded(f func() outcome) (res outcome) {
	defer func() {
		if p := recover(); p != nil {
			res = outcome{err: panicError(p)}
		}
	}()
	return f()
}

// panicError: a kin limit breach unwinds with a `*port.Breach` (kind, limit, actual), recovered by its type; its name is the table's in kin
// (`emon.limits`). Any other panic is `internal`, with its message.
func panicError(p any) *Error {
	if b, ok := p.(*port.Breach); ok {
		return &Error{Kind: ErrLimit, Status: b.Name(), Limit: int64(b.Limit), Actual: int64(b.Actual)}
	}
	return &Error{Kind: ErrInternal, Message: panicMessage(p)}
}

func panicMessage(p any) string {
	switch v := p.(type) {
	case string:
		return v
	case error:
		return v.Error()
	}
	return fmt.Sprint(p)
}

// arenas are the Model's seven arenas (SPEC 3.8) and the asset table, its uses and bytes (SPEC 10.11): kin's `emon.enctx` `Arenas`
// (`Clone` is the copy the asset unit (D351) runs on until it has every answer).
type arenas = enctx.Arenas

func newArenas() *arenas { return enctx.NewArenas() }

// cloneBuf is a copy of a Buf.
func cloneBuf(b *port.Buf) *port.Buf {
	return &port.Buf{B: append([]byte(nil), b.B...)}
}

func cellsVec(c *port.Cells) []int32 { return append([]int32(nil), c.V...) }

func textOf(c *port.Cells, start, n int32) string {
	var sb strings.Builder
	for i := start; i < start+n; i++ {
		sb.WriteRune(rune(model.TextAt(c, i)))
	}
	return sb.String()
}

// assetUnit is THE ASSET UNIT (P6.1a, D117, D291, SPEC 10.11): the candidates of the document's image placements, in its own plan/fulfil
// loop, one call of kin's (`EnAssetUnit`). It re-opens the document (the document pass's arenas are gone) and collects the placements the
// document pass kept: a codec image whose pixels are the host's is asked for through rq. Answers 0 (the candidates are in d.doc.Cand,
// d.doc.Cbytes), 1 (it asked) or 7 (the source missed).
func assetUnit(view, pw *port.Src, d *unitDoc, opts Options, idx int, answers, rq *port.Cells, bms *port.BitmapSet, payload *port.Buf) int32 {
	return engine.EnAssetUnit(view, pw, cfgOf(opts), d.doc, enctx.NewPass(), int32(idx), answers, rq, bms, payload)
}

// cloneCells is a copy of a Cells.
func cloneCells(c *port.Cells) *port.Cells {
	r := port.NewCells()
	for i := int32(0); i < port.CellsLen(c); i++ {
		port.CellsPush(r, port.CellsAt(c, i))
	}
	return r
}

// Intake is stage 0 of each input: format, version, page count, whether it is encrypted. Any number of inputs.
func (e *Engine) Intake(inputs []Input) ([]Intake, error) {
	if len(inputs) == 0 {
		return nil, &Error{Kind: ErrNoInput}
	}
	var out []Intake
	for i, in := range inputs {
		o, _, err := e.runInput(i, in, DefaultOptions(), true, nil, nil)
		if err != nil {
			return nil, err
		}
		if o.intake == nil {
			return nil, &Error{Kind: ErrInternal, Message: "intake gave no intake"}
		}
		out = append(out, *o.intake)
	}
	return out, nil
}

// Convert is stages 0-3 for the inputs: a Model. Several inputs are not yet one document (see the header).
func (e *Engine) Convert(inputs []Input, opts Options) (*Model, error) {
	m, _, err := e.ConvertStats(inputs, opts)
	return m, err
}

// ConvertStats is Convert and what the loop did (attempts, fetch rounds, bytes fetched).
func (e *Engine) ConvertStats(inputs []Input, opts Options) (*Model, RunStats, error) {
	return e.convertWith(inputs, opts, nil, nil)
}

// Record converts and RECORDS the plan/fulfil transcript (SPEC 13). This slice issues no requests, so it has
// no entries, but it has the input's index and length, which a replay is checked against.
func (e *Engine) Record(inputs []Input, opts Options) (*Model, *Transcript, error) {
	t := &Transcript{}
	m, _, err := e.convertWith(inputs, opts, t, nil)
	if err != nil {
		return nil, nil, err
	}
	return m, t, nil
}

// Replay converts from a transcript: no Renderer or Ocr is called; a request it has no answer for is an error.
func (e *Engine) Replay(inputs []Input, opts Options, t *Transcript) (*Model, error) {
	m, _, err := e.convertWith(inputs, opts, nil, t)
	return m, err
}

func (e *Engine) convertWith(inputs []Input, opts Options, rec, replay *Transcript) (*Model, RunStats, error) {
	if len(inputs) == 0 {
		return nil, RunStats{}, &Error{Kind: ErrNoInput}
	}
	if len(inputs) > 1 {
		return nil, RunStats{}, &Error{Kind: ErrUnsupported, Message: "several inputs as one document are not wired yet (`intake` takes any number; convert one at a time)"}
	}
	o, stats, err := e.runInput(0, inputs[0], opts, false, rec, replay)
	if err != nil {
		return nil, stats, err
	}
	if o.built == nil {
		return nil, stats, &Error{Kind: ErrInternal, Message: "the unit finished with no Model"}
	}
	return &Model{a: o.built.arenas, evidence: o.built.evidence, intake: o.built.intake}, stats, nil
}

// runInput is the loop over one input: limits first, a `Src` over its source, the unit run until an attempt
// does not miss, and the stats.
func (e *Engine) runInput(idx int, in Input, opts Options, intakeOnly bool, rec, replay *Transcript) (o outcome, stats RunStats, err error) {
	port.WithLimits(e.cfg.Limits, func() {
		o, stats, err = e.runInputBounded(idx, in, opts, intakeOnly, rec, replay)
	})
	return
}

func (e *Engine) runInputBounded(idx int, in Input, opts Options, intakeOnly bool, rec, replay *Transcript) (outcome, RunStats, error) {
	// the input-bytes bound is checked against the SIZE, before any byte is read (SPEC 13.1)
	size := in.Source.Size()
	limit := min(int64(port.LimitOf(0)), int64(1<<31-1))
	if size > limit || size > int64(1<<31-1) {
		return outcome{}, RunStats{}, &Error{Kind: ErrLimit, Status: "input-bytes", Limit: limit, Actual: size}
	}
	n := int(size)
	simulate := e.cfg.SimulateAsync
	var backing port.SrcBacking
	var cache *chunkCache
	if ss, ok := in.Source.(SliceSource); ok && !simulate {
		backing = sliceBacking(ss.AsSlice())
	} else {
		cache = newChunkCache(in.Source, n, max(e.cfg.ChunkSize, 1), simulate)
		backing = cache
		if simulate {
			cache.prefetchEnds()
		}
	}
	base := port.NewSrc(backing)
	pw := port.SrcOfBlob(append([]byte(nil), in.Password...))
	var res outcome
	var docU *unitDoc
	unit := func(answers, rq *port.Cells, _ *port.BitmapSet, _ *port.Buf) int32 {
		view := base.View()
		s := dispatch(view, pw, answers, rq, idx, opts, intakeOnly, uint64(size))
		if s.miss {
			return port.PlanUnitNeedBytes
		}
		if s.requests {
			return port.PlanUnitRequests
		}
		res = s.out
		docU = s.doc
		return port.PlanUnitDone
	}
	bytesF := &port.PlanSync{F: func(req port.PlanRequest, _ port.PlanBitmaps) (port.PlanAnswer, error) {
		off, n := req.Field(plan.BqOffset()), req.Field(plan.BqLength())
		if cache != nil && !cache.fetch(off, n) {
			return port.PlanAnswer{}, planrun.FulfilFailed("a read of the source failed")
		}
		c := port.NewCells()
		plan.AnBytes(c, req.ID(), off, n)
		return port.PlanAnswer{Rec: cellsVec(c)}, nil
	}}
	var misses port.PlanMisses = port.PlanNoMisses{}
	if cache != nil {
		misses = &cacheMisses{cache: cache, input: int32(idx)}
	}
	pcfg := port.PlanConfig{MaxRounds: e.cfg.MaxRounds, FetchRounds: e.cfg.FetchRounds}
	thisInput := [][2]int32{{int32(idx), int32(size)}}
	// the live port set (D127, D211): `render` and `crop` go to the configured Renderer, reading the same input through the
	// same byte source; with none they, like the other kinds, fall to the host's own fulfiller, which answers them
	// `unsupported` (D199). Which port a request goes to is kin's (`pl-route`). Pixels are kept in the answers when a transcript is
	// being recorded or an OCR engine will read them.
	live := &livePorts{local: portFulfiller(in.Source, idx, rec != nil || e.cfg.Ocr != nil, rec != nil, e.cfg.Ocr, e.cfg.PdfCodecs), ocr: e.cfg.Ocr != nil}
	if e.cfg.Renderer != nil {
		live.render = port.NewRenderFulfillerFor(e.cfg.Renderer, idx, port.RenderInput{Source: in.Source, Password: in.Password})
	}
	var ports port.PlanFulfiller = live
	var replayer *planrun.Replayer
	if rec != nil {
		rec.Inputs = thisInput
		if e.cfg.Ocr != nil {
			rec.Producer = e.cfg.Ocr.Producer()
		}
	}
	if replay != nil {
		if err := replay.CheckInputs(thisInput); err != nil {
			return outcome{}, RunStats{}, planError(err.(*port.PlanError))
		}
		replayer = planrun.NewReplayer(replay)
		ports = replayer
	}
	// PARALLEL (P10.2, SPEC 12.1): `Parallel` workers fulfil a round's requests at once and the pages ahead of the loop's are looked at meanwhile (`par.go`).
	// A replay answers from the transcript and has nothing to run: it stays serial. The pool ends before this function does, so before the limit scope.
	var pool *parPool
	if replay == nil {
		if workers := int(planloop.PlWorkers(e.cfg.Parallel, port.LimitOf(16))); workers > 1 {
			x := &parExec{local: portFulfiller(in.Source, idx, rec != nil || e.cfg.Ocr != nil, rec != nil, e.cfg.Ocr, e.cfg.PdfCodecs).F, ocr: e.cfg.Ocr != nil}
			if e.cfg.Renderer != nil {
				x.render = port.NewRenderFulfillerFor(e.cfg.Renderer, idx, port.RenderInput{Source: in.Source, Password: in.Password})
			}
			pool = newParPool(x, workers, int(planloop.PlAhead(int32(workers))), pcfg)
			defer pool.close()
			ports = &parPorts{pool: pool}
		}
	}
	timed := &timedPorts{inner: ports}
	ports = timed
	// a source that failed is the answer, whatever the loop made of the miss it caused
	sourceFailed := func() *Error {
		if cache != nil {
			if failed, short, msg := cache.fail(); failed {
				if short {
					return &Error{Kind: ErrSourceChanged, Input: idx}
				}
				return &Error{Kind: ErrIO, Input: idx, Message: msg}
			}
		}
		return nil
	}
	// THE SCHEDULE IS KIN'S (`en-next`): which unit runs next is the machine's phase, and this function is the driver of each unit. The
	// unit that just finished is folded into the totals, and the schedule moves on.
	m := planrun.New(pcfg)
	advance := func() {
		kind, np := int32(0), 0
		if docU != nil {
			kind, np = docU.doc.Kind, docU.info.Pages
		}
		m.EndUnit()
		m.NextPhase(kind, opts.Assets != AssetsNone, false, np)
	}
	stats := RunStats{InputBytes: uint64(size)}
	var tPages time.Time
phases:
	for {
		if pool != nil {
			// outside the page units there is no page to look ahead of
			pool.cur = -1
		}
		switch m.Phase() {
		case planloop.PhDoc():
			// THE DOCUMENT PASS: everything that reads the input, in its own loop (misses fetch and re-run it).
			tPass := time.Now()
			perr := planrun.Drive(m, unit, ports, bytesF, misses, rec)
			stats.PassUs = uint64(time.Since(tPass).Microseconds())
			if err := sourceFailed(); err != nil {
				return outcome{}, RunStats{}, err
			}
			if perr != nil {
				return outcome{}, RunStats{}, loopError(perr)
			}
			tPages = time.Now()
			advance()
		case planloop.PhAsset():
			// THE ASSET UNIT (P6.1a, D117): a PDF's image placements become asset candidates, and a codec image's pixels are asked of the
			// host (`decode-img`, one round for them all); it reads the input (and so may miss, like the document pass)
			var afailed *Error
			unitA := func(answers, rq *port.Cells, bms *port.BitmapSet, payload *port.Buf) (st int32) {
				view := base.View()
				defer func() {
					if p := recover(); p != nil {
						if view.Missed() {
							st = port.PlanUnitNeedBytes
						} else {
							afailed = panicError(p)
							st = port.PlanUnitDone
						}
					}
				}()
				switch s := assetUnit(view, pw, docU, opts, idx, answers, rq, bms, payload); {
				case s == 0:
					return port.PlanUnitDone
				case s == 1:
					return port.PlanUnitRequests
				case s == 7 || view.Missed():
					return port.PlanUnitNeedBytes
				default:
					afailed = &Error{Kind: ErrInternal, Message: fmt.Sprintf("the asset unit answered status %d", s)}
				}
				return port.PlanUnitDone
			}
			aerr := planrun.Drive(m, unitA, ports, bytesF, misses, rec)
			if err := sourceFailed(); err != nil {
				return outcome{}, RunStats{}, err
			}
			if aerr != nil {
				return outcome{}, RunStats{}, loopError(aerr)
			}
			if afailed != nil {
				return outcome{}, RunStats{}, afailed
			}
			advance()
		case planloop.PhPages():
			// THE PAGE UNITS (P7.U, D195): one plan/fulfil loop a page, each with its own answers, requests and BitmapSet, dropped when
			// the unit ends, so a conversion holds one page's bitmaps and not the document's. A page unit never reads the input.
			i := m.Page()
			var failed *Error
			if pool != nil {
				pool.cur, pool.pages = i, docU.info.Pages
				// a dry attempt writes nothing (`pu-dry`); a panic in one ends the chain and is dropped, the real unit meets what caused it in its own turn
				pool.dry = func(page int, answers, rq *port.Cells, bms *port.BitmapSet, _ *port.Buf) (st int32) {
					defer func() {
						if p := recover(); p != nil {
							st = port.PlanUnitDone
						}
					}()
					if unitPage(docU, page, idx, uint64(size), opts, true, answers, rq, bms) == 1 {
						return port.PlanUnitRequests
					}
					return port.PlanUnitDone
				}
			}
			unitP := func(answers, rq *port.Cells, bms *port.BitmapSet, _ *port.Buf) (st int32) {
				defer func() {
					if p := recover(); p != nil {
						failed = panicError(p)
						st = port.PlanUnitDone
					}
				}()
				switch s := unitPage(docU, i, idx, uint64(size), opts, false, answers, rq, bms); s {
				case 0:
					return port.PlanUnitDone
				case 1:
					return port.PlanUnitRequests
				case planloop.DsUndecodable(), planloop.DsUnsupportedImage():
					failed = damaged(idx, s)
				default:
					failed = &Error{Kind: ErrInternal, Message: fmt.Sprintf("a page unit answered status %d", s)}
				}
				return port.PlanUnitDone
			}
			if perr := planrun.Drive(m, unitP, ports, bytesF, port.PlanNoMisses{}, rec); perr != nil {
				return outcome{}, RunStats{}, loopError(perr)
			}
			if failed != nil {
				return outcome{}, RunStats{}, failed
			}
			advance()
		case planloop.PhFinish():
			stats.PagesUs = uint64(time.Since(tPages).Microseconds())
			tFin := time.Now()
			// the document unit is kin too: a panic in it (a bug, or a limit breach) is the Engine's named error, never the embedder's (P10.5, D386)
			res = finishGuarded(func() outcome { return finishDoc(docU, opts) })
			stats.FinishUs = uint64(time.Since(tFin).Microseconds())
			m.NextPhase(0, false, res.built != nil && res.built.pending != nil, 0)
		case planloop.PhFigures():
			// THE ASSET UNIT (P8.5, D351): at `figures` the Model is final but for its assets, whose renders and crops are requests,
			// so one more plan/fulfil loop runs over it, with its own answers and BitmapSet, on copies until every answer is in.
			b := res.built
			pend := b.pending
			b.pending = nil
			var done *arenas
			var afailed *Error
			unitF := func(answers, rq *port.Cells, bms *port.BitmapSet, _ *port.Buf) (st int32) {
				defer func() {
					if p := recover(); p != nil {
						afailed = panicError(p)
						st = port.PlanUnitDone
					}
				}()
				t := b.arenas.Clone()
				p := &pendingAssets{cloneCells(pend.cand), cloneBuf(pend.cbytes), pend.rfrom}
				assetStageFigures(t, p, opts, int32(idx), int32(size), answers, rq, bms)
				if port.CellsLen(rq) > 0 {
					return port.PlanUnitRequests
				}
				done = t
				return port.PlanUnitDone
			}
			if aerr := planrun.Drive(m, unitF, ports, bytesF, port.PlanNoMisses{}, rec); aerr != nil {
				return outcome{}, RunStats{}, loopError(aerr)
			}
			if afailed != nil {
				return outcome{}, RunStats{}, afailed
			}
			if done == nil {
				return outcome{}, RunStats{}, &Error{Kind: ErrInternal, Message: "the asset unit finished with no Model"}
			}
			advance()
			b.arenas = done
			if o := sealArenas(b.arenas, opts); o != nil {
				return outcome{}, RunStats{}, o.err
			}
		default:
			break phases
		}
	}
	t := m.Totals()
	stats.Attempts, stats.FetchRounds, stats.BytesRequests, stats.Requests = t.Attempts, t.FetchRounds, t.BytesRequests, t.Requests
	stats.PageRounds, stats.PageRequests = t.PageRounds, t.PageRequests
	stats.FulfilUs = timed.us
	if replayer != nil {
		if err := replayer.Done(); err != nil {
			return outcome{}, RunStats{}, &Error{Kind: ErrPlan, Plan: err.(*port.PlanError)}
		}
	}
	if cache != nil {
		stats.SourceReads, stats.BytesFetched = cache.stats()
	}
	if res.err != nil {
		return outcome{}, stats, res.err
	}
	if res.built == nil && res.intake == nil {
		return outcome{}, stats, &Error{Kind: ErrInternal, Message: "the unit finished with nothing"}
	}
	return res, stats, nil
}

// loopError is an error of the plan loop as the Engine reports it: a named failure of the loop, or an internal error.
func loopError(err error) *Error {
	var pe *port.PlanError
	if errors.As(err, &pe) {
		return planError(pe)
	}
	return &Error{Kind: ErrInternal, Message: err.Error()}
}

// livePorts is the LIVE port set: a request goes where kin says (`pl-route`, P10.8): a `render` or `crop` to the configured Renderer,
// everything else to the host's own fulfiller (`portFulfiller`), which also answers `unsupported` where no port is configured (D199).
type livePorts struct {
	render port.PlanFulfiller
	local  port.PlanFulfiller
	ocr    bool
	// true when the request in that place went to the renderer
	order []bool
}

func (l *livePorts) Start(req port.PlanRequest, bm port.PlanBitmaps) error {
	if l.render != nil && planrun.Route(req.Kind(), l.ocr, true) == planloop.RtRender() {
		l.order = append(l.order, true)
		return l.render.Start(req, bm)
	}
	l.order = append(l.order, false)
	return l.local.Start(req, bm)
}

func (l *livePorts) Next() (port.PlanAnswer, error) {
	if len(l.order) == 0 {
		return port.PlanAnswer{}, planrun.FulfilFailed("next without a started request")
	}
	toRender := l.order[0]
	l.order = l.order[1:]
	if toRender {
		return l.render.Next()
	}
	return l.local.Next()
}
