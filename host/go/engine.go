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

	"emon/asset"
	"emon/config"
	"emon/enctx"
	"emon/engine"
	"emon/figclass"
	"emon/gfuse"
	"emon/html"
	"emon/inline"
	"emon/model"
	"emon/pageunit"
	"emon/pdfannot"
	"emon/pdfcontent"
	"emon/pdfdoc"
	"emon/pdfmeta"
	"emon/pdfstruct"
	"emon/pdfx"
	"emon/plan"
	"emon/planloop"
	"emon/planrun"
	"emon/port"
	"emon/rasterin"
	"emon/tlhealth"
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
	cfg := Config{ChunkSize: int(c.Chunk), MaxRounds: c.Maxr, FetchRounds: c.Fetchr}
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
	p := &Parsed{Options: optionsOf(c), Config: configOf(c), Record: c.Rec != 0, JsOcr: c.Jsocr != 0}
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
// An input that kin's `RsSniff` says is a PNG, a JPEG or a TIFF (SPEC 2.0, D187) is not a PDF: its unit is
// `emon.rasterin`'s `RsUnit`, which asks the host's `ImageDecode` port (`imgdec.go`) for each frame through the
// plan/fulfil loop and builds one `image` page per frame. THIS IS SEPARATE FROM `attempt`, which is the PDF unit
// and is not called for an image. OCR is not wired (P6.5), so an image page has no text.

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
		d, err := imgDecodeSafe(span, frame, uint64(port.LimitOf(9)))
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
func imgDecodeSafe(src ByteSource, frame int32, maxPixels uint64) (d *imgDecoded, err error) {
	defer func() {
		if p := recover(); p != nil {
			d, err = nil, imgFailedf("the decoder panicked")
		}
	}()
	return imgDecode(src, frame, maxPixels)
}

// rstep is one attempt's end for either kind of input: it missed, it asked for something, or it finished.
type rstep struct {
	miss, requests bool
	out            outcome
	// the document pass is done and the page units are next (P7.U)
	pdf    *pdfDoc
	raster *rasterDoc
}

// localeCells is the locale hint's code points (kin's `loc`: what `emon.scan` reads the OCR language from); empty for none.
func localeCells(opts Options) *port.Cells {
	c := port.NewCells()
	for _, ch := range opts.Locale {
		port.CellsPush(c, int32(ch))
	}
	return c
}

// attemptRaster is ONE ATTEMPT OF THE RASTER UNIT, from scratch on fresh arenas, as `attempt` is for a PDF. An image's pages
// are made by kin's `RsUnit` (decode, then OCR: P6.5), and its OCR lines are the text layer's lines to everything after (D213):
// the stages below are `attempt`'s, in its order, with the glyph-fed ones given empty arenas.
func attemptRaster(view *port.Src, answers, rq *port.Cells, idx int, format int32, opts Options, intakeOnly bool, n uint64) rstep {
	m := newArenas()
	model.ModelInit(m.Nodes, m.Attrs, m.Regions, m.Text, m.Pages, m.Ledger, m.Layout)
	mask := port.NewCells()
	status := rasterin.RsDoc(view, int32(idx), format, answers, rq, m.Nodes, m.Attrs, m.Text, m.Pages, mask)
	if view.Missed() {
		return rstep{miss: true}
	}
	switch status {
	case 0:
	case 1:
		return rstep{requests: true}
	case planloop.DsUndecodable(), planloop.DsUnsupportedImage():
		return rstep{out: outcome{err: damaged(idx, status)}}
	default:
		return rstep{out: outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("the raster unit answered status %d", status)}}}
	}
	name := "tiff"
	switch format {
	case rasterin.RsPng():
		name = "png"
	case rasterin.RsJpeg():
		name = "jpeg"
	}
	info := Intake{Format: name, Pages: int(max(model.PageCount(m.Pages), 0)), Bytes: n}
	if intakeOnly {
		return rstep{out: outcome{intake: &info}}
	}
	doc := enctx.NewDoc()
	doc.Mask = mask
	doc.Loc = localeCells(opts)
	return rstep{raster: &rasterDoc{m: m, ev: enctx.NewEvArenas(), doc: doc, info: info}}
}

// rasterDoc is what the raster document pass leaves for its page units and the document unit (P7.U): kin's contexts (`emon.enctx`).
type rasterDoc struct {
	m    *arenas
	ev   *enctx.EvArenas
	doc  *enctx.Doc
	info Intake
}

// unitRaster is one frame's page unit (P7.U): its own requests and answers; it writes nothing until its answers are in.
func unitRaster(d *rasterDoc, f, idx int, n uint64, opts Options, answers, rq *port.Cells, bms *port.BitmapSet) int32 {
	scr := port.NewCells()
	return rasterin.RsPage(int32(f), int32(idx), int32(n), opts.Ocr.code(), answers, rq, d.doc.Loc, bms, d.doc.Mask,
		d.ev.Epages, d.ev.Views, d.doc.Et, d.ev.Feats, d.ev.Images, d.ev.Oruns, d.ev.Oblocks, d.ev.Olines, d.ev.Owords, d.ev.Osyms, d.ev.Oalts, d.doc.Wd, d.doc.Ln, scr)
}

// finishRaster is the raster document unit: stage 3 over the frames' OCR lines, in kin (`emon.engine`).
func finishRaster(d *rasterDoc, opts Options) outcome {
	rfrom := model.AfmTiff()
	switch d.info.Format {
	case "png":
		rfrom = model.AfmPng()
	case "jpeg":
		rfrom = model.AfmJpeg()
	}
	r := engine.EnFinishRaster(cfgOf(opts), d.doc, d.ev, d.m, enctx.NewScratch())
	return finished(r, d.doc, d.m, d.info, opts, rfrom)
}

// finished is what kin's document unit answered (`emon.engine`: 0 built, 1 built with the asset unit still to run, 2 `pl-check`, 3
// `model-check`, 4 `model-check-assets`).
func finished(r int32, doc *enctx.Doc, m *arenas, info Intake, opts Options, rfrom int32) outcome {
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
			pending = &pendingAssets{doc.Cand, doc.Cbytes, rfrom}
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
	sigs := boolInt(opts.AssetSignatures)
	asset.AsSettings(m.Nodes, m.Attrs, m.Text, opts.Assets.level(), opts.AssetDPI, opts.AssetPixels, opts.AssetBytes, opts.AssetsBytes, sigs)
	s := newCellsN(5)
	z := port.NewBuf()
	asset.AsRunFigures(m.Nodes, m.Attrs, m.Regions, m.Pages, p.cand, p.cbytes, m.Assets, m.Uses, m.Abytes,
		opts.AssetBytes, opts.AssetsBytes, sigs, opts.AssetDPI, opts.AssetPixels, input, nbytes, p.rfrom, ans, rq, bms,
		s[0], z, s[1], s[2], s[3], s[4])
}

// dispatch is the unit of one input: a PNG, JPEG or TIFF (by kin's sniff) is read by the raster unit, anything
// else by the PDF one (`attempt`, which refuses what is not a PDF by name). A panic in an attempt that missed is
// a miss, never reported (SPEC 13.1).
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
	format := rasterin.RsSniff(view)
	if view.Missed() {
		return rstep{miss: true}
	}
	if format != rasterin.RsNone() {
		return attemptRaster(view, answers, rq, idx, format, opts, intakeOnly, n)
	}
	a := attempt(view, pw, idx, opts, intakeOnly, n)
	return rstep{miss: a.miss, requests: a.requests, out: a.out, pdf: a.pdf}
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

// step is one attempt's end: it missed (discard it, fetch, re-run), asked for something (OCR of a scanned page: the loop
// fulfils it and re-runs the unit on the answers) or it finished.
type step struct {
	miss, requests bool
	out            outcome
	pdf            *pdfDoc
}

func newCellsN(n int) []*port.Cells {
	v := make([]*port.Cells, n)
	for i := range v {
		v[i] = port.NewCells()
	}
	return v
}

// attempt is ONE ATTEMPT OF THE UNIT for one input: everything kin does with it, from scratch, on a fresh
// view and fresh arenas. A miss at any checkpoint ends it at once: what a missed attempt computed is never
// looked at, and a failure or a panic in one that missed is a miss, never reported (SPEC 13.1). THIS IS THE
// ONE PLACE the Engine calls the text-layer stages; when their signatures grow this is what changes.
func attempt(view, pw *port.Src, idx int, opts Options, intakeOnly bool, n uint64) step {
	a := newCellsN(6)
	ar, dc, ca, cb, st, pg := a[0], a[1], a[2], a[3], a[4], a[5]
	ostatus := pdfdoc.PdOpenPw(view, pw, ar, dc, ca, cb, st)
	if view.Missed() || ostatus == pdfdoc.PdNeedBytes() {
		return step{miss: true}
	}
	if ostatus != 0 {
		return step{out: outcome{err: statusError(idx, ostatus)}}
	}
	intake := func(np int32) Intake {
		return Intake{Format: "pdf", VersionMajor: pdfdoc.PdVersionMajor(dc), VersionMinor: pdfdoc.PdVersionMinor(dc),
			Pages: int(max(np, 0)), Encrypted: pdfdoc.PdEncrypted(dc) == 1, Bytes: n}
	}
	if intakeOnly {
		np := pdfdoc.PdPages(view, ar, dc, ca, cb, st, pg)
		if view.Missed() {
			return step{miss: true}
		}
		if np < 0 {
			return step{out: outcome{err: damaged(idx, planloop.DsNoPages())}}
		}
		i := intake(np)
		return step{out: outcome{intake: &i}}
	}
	x := newCellsN(18)
	cx, ox, cs, out, f0, f1, f2, f3, gl, et, mk, fnt, wd, ln := x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], x[8], x[9], x[10], x[11], x[12], x[13]
	pa, im, pts, gx := x[14], x[15], x[16], x[17]
	status := pdfcontent.PcDocument(view, ar, dc, ca, cb, st, cx, ox, cs, out, f0, f1, f2, f3, gl, et, mk, fnt, pg, wd, ln, pa, im, pts, gx)
	if view.Missed() || status == pdfdoc.PdNeedBytes() {
		return step{miss: true}
	}
	if status != 0 {
		return step{out: outcome{err: damaged(idx, planloop.DsContentFailed())}}
	}
	np := pdfdoc.PdPgCount(pg)
	info := intake(np)
	// THE PLAN OF THE PAGE UNITS (P6.5, D212, P7.U): a page with no text layer and one JPEG image that covers it is a scanned page
	// (a `decode` of the stream, then an `ocr` of its bitmap, in its own unit); its stream is located here, the one place that
	// reads the input. A page with a text layer is left as the text layer made it (fusion, P7.3, is what weighs a second reading).
	// The text layer's own triage and health (P7.0, D195, D208): epages, the stage-1 features, the flow COPIED into fw/fl without the
	// words that failed a P3.6 check (set aside in fa, written to the ledger after FuWrite), and the font health in hl. Stage 3 reads
	// fw/fl from here on, never wd/ln; the scanned pages' lines join them after the page units.
	l7 := newCellsN(7)
	tlEpages, tlFeats, fw, fl, fa, hl, hsc := l7[0], l7[1], l7[2], l7[3], l7[4], l7[5], l7[6]
	tlhealth.ThRun(pg, gl, et, mk, fnt, wd, ln, im, tlEpages, tlFeats, fw, fl, fa, hl, hsc)
	nl0 := port.CellsLen(ln) / 16
	sx := newCellsN(4)
	plan0, sp, rs1, rs2 := sx[0], sx[1], sx[2], sx[3]
	pageunit.PuPlan(view, ar, dc, ca, cb, st, pg, im, wd, opts.Ocr.code(), opts.OcrScope.code(), fw, fl, et, hl, sp, plan0, rs1, rs2)
	if view.Missed() {
		return step{miss: true}
	}
	// The fused graphics (P5.5a, D270): the vector source's rules and fills of every page, which the table stage reads instead of paths
	// (until P7.3 moves it into the page units, SPEC 9.9).
	gx4 := newCellsN(4)
	grl, gfl, gcand, gmeas := gx4[0], gx4[1], gx4[2], gx4[3]
	gfuse.GfDoc(pa, pts, mk, im, np, gfuse.GfxMode(), grl, gfl, gcand, gmeas)
	// P3.13 (SPEC 10.11): the asset candidates are made by the ASSET UNIT (`assetUnit`, P6.1a), which runs after this pass and reads
	// the input again, because a codec image's pixels are the host's and ask the plan/fulfil loop; the placements it works from are kept.
	cand, cbytes := port.NewCells(), port.NewBuf()
	aim := port.NewCells()
	if opts.Assets != AssetsNone {
		aim = im
	}
	// P8.1 (SPEC 10.6): what each image placement's dictionary says, which the figures' kinds are read from (one more read of the input)
	fz3 := newCellsN(3)
	fi := fz3[0]
	figclass.FcImages(view, ar, dc, ca, cb, st, im, fz3[1], fz3[2], fi)
	if view.Missed() {
		return step{miss: true}
	}
	var snap []*port.Cells
	if opts.KeepEvidence {
		snap = []*port.Cells{cloneCells(gl), cloneCells(mk), cloneCells(fnt)}
	}
	// With fusion on (every policy but `never`), the text layer's epages and feats are the evidence's own: the OCR of a born-digital page adds
	// its feature rows to the same arena, and alignment reads both (P7.3). Without it they are dropped, as before, so the dump is the one it always was.
	fusion := opts.Ocr.code() != 0
	ev0 := enctx.NewEvArenas()
	if fusion {
		ev0.Epages = tlEpages
		ev0.Feats = tlFeats
	}
	// The catalogue's extras and the tagged tree (P3.9, P3.7): the Info /Title and /PageLabels, and the tree's trust
	// (D076), which the furniture stage weighs the tree's /Artifact marks by.
	q := newCellsN(24)
	qidx, qtxt, qw, qseen := q[0], q[1], q[2], q[3]
	md, xm, ol, lb, nd, nt := q[4], q[5], q[6], q[7], q[8], q[9]
	ss, se, sk, sc, so, sa, wk := q[10], q[11], q[12], q[13], q[14], q[15], q[16]
	ha, hb, hh := q[17], q[18], q[19]
	pdfx.PxIndexBuild(view, ar, dc, ca, cb, st, pg, qidx)
	pdfmeta.PmRun(view, ar, dc, ca, cb, st, pg, qidx, qtxt, qw, qseen, md, xm, ol, lb, nd, nt)
	pdfstruct.TgRead(view, ar, dc, ca, cb, st, pg, qidx, qtxt, qw, qseen, ss, se, sk, sc, so, wk, md)
	pdfstruct.TgJoin(ss, se, sk, sc, so, sa, gl, mk, fnt, pg, ha, hb, hh)
	// inline styles and links (P4.7): what each font says of weight and slant, and the link annotations (P3.8)
	v := newCellsN(8)
	ift, vfs, vfm, van, vaq, vfd, vsg := v[0], v[1], v[2], v[3], v[4], v[5], v[6]
	inline.IlFonts(view, ar, dc, ca, cb, st, fnt, et, ift)
	pdfannot.PaRun(view, ar, dc, ca, cb, st, pg, qidx, qtxt, qw, vfs, qseen, vfm, van, vaq, vfd, vsg)
	if view.Missed() {
		return step{miss: true}
	}
	mask := port.NewCells()
	for i := int32(0); i < max(np, 0); i++ {
		port.CellsPush(mask, 0)
	}
	ptsKept := port.NewCells()
	if fusion {
		ptsKept = pts
	}
	doc := enctx.NewDoc()
	doc.Hl = hl
	doc.Et = et
	doc.Gl = gl
	doc.Mk = mk
	doc.Fnt = fnt
	doc.Wd = wd
	doc.Ln = ln
	doc.Fw = fw
	doc.Fl = fl
	doc.Fa = fa
	doc.Nl0 = nl0
	doc.Pa = pa
	doc.Im = im
	doc.Grl = grl
	doc.Gfl = gfl
	doc.Ift = ift
	doc.An = van
	doc.Pts = ptsKept
	doc.Pg = pg
	doc.Txt = qtxt
	doc.Md = md
	doc.Lb = lb
	doc.Ol = ol
	doc.Ss = ss
	doc.Se = se
	doc.Sc = sc
	doc.Plan = plan0
	doc.Mask = mask
	doc.Loc = localeCells(opts)
	if snap != nil {
		doc.Snapg, doc.Snapk, doc.Snapf = snap[0], snap[1], snap[2]
		doc.Hassnap = 1
	}
	doc.Cand = cand
	doc.Cbytes = cbytes
	doc.Aim = aim
	doc.Fi = fi
	return step{pdf: &pdfDoc{ev: ev0, doc: doc, info: info}}
}

// pdfDoc is what the PDF document pass leaves for its page units and the document unit (P7.U, D195): kin's contexts (`emon.enctx`).
type pdfDoc struct {
	ev   *enctx.EvArenas
	doc  *enctx.Doc
	info Intake
}

// assetUnit is THE ASSET UNIT (P6.1a, D117, D291, SPEC 10.11): the candidates of the document's image placements, in its own plan/fulfil
// loop. It re-opens the document (the document pass's arenas are gone) and runs AsCollect over the placements the document pass kept: a
// codec image whose pixels are the host's is asked for, with the bytes kin made itself in `payload`; the unit is run again with the
// answers and makes the candidates. Answers 0 (the candidates are in d.cand, d.cbytes), 1 (it asked) or 7 (the source missed).
func assetUnit(view, pw *port.Src, d *pdfDoc, opts Options, idx int, answers, rq *port.Cells, bms *port.BitmapSet, payload *port.Buf) int32 {
	a := newCellsN(5)
	ar, dc, ca, cb, st := a[0], a[1], a[2], a[3], a[4]
	ostatus := pdfdoc.PdOpenPw(view, pw, ar, dc, ca, cb, st)
	if view.Missed() || ostatus == pdfdoc.PdNeedBytes() {
		return 7
	}
	if ostatus != 0 {
		return 100 + ostatus
	}
	w := newCellsN(10)
	z := port.NewBuf()
	glob, words, hs := port.NewCells(), port.NewCells(), port.NewCells()
	d.doc.Cbytes = port.NewBuf()
	rc := asset.AsCollect(view, ar, dc, ca, cb, st, d.doc.Aim, d.doc.Pa, d.doc.Gl, d.doc.Pg, opts.AssetPixels, opts.AssetsBytes, opts.Assets.level(), port.NewCells(), d.doc.Cand, d.doc.Cbytes, w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7], w[8], z, int32(idx), answers, rq, payload, bms, glob, words, hs)
	if view.Missed() || rc == 12 {
		return 7
	}
	return rc
}

// cloneCells is a copy of a Cells.
func cloneCells(c *port.Cells) *port.Cells {
	r := port.NewCells()
	for i := int32(0); i < port.CellsLen(c); i++ {
		port.CellsPush(r, port.CellsAt(c, i))
	}
	return r
}

// unitPdf is one page's unit (P7.U, D195): its own requests, answers and BitmapSet; a scanned page is read, any other is left as
// the text layer made it. It writes nothing until its answers are in, and never reads the input.
func unitPdf(d *pdfDoc, i, idx int, opts Options, answers, rq *port.Cells, bms *port.BitmapSet) int32 {
	scr := port.NewCells()
	x := d.doc
	return pageunit.PuRunPdf(int32(i), int32(idx), opts.Ocr.code(), x.Plan, answers, rq, x.Loc, bms, x.Mask, scr,
		d.ev.Epages, d.ev.Views, x.Et, d.ev.Feats, d.ev.Images, d.ev.Oruns, d.ev.Oblocks, d.ev.Olines, d.ev.Owords, d.ev.Osyms, d.ev.Oalts, x.Wd, x.Ln,
		2*boolInt(opts.NoRetarget), x.Fw, x.Fl, x.Hl, d.ev.Refs, x.Cg, d.ev.Tlines, x.Xs0, x.Xs1, x.Xs2, x.Xs3, x.Xs4, x.Xs5,
		d.ev.Inks, d.ev.Comps, d.ev.Rules, d.ev.Prims, d.ev.Pts,
		x.Ys0, x.Ys1, x.Ys2, x.Ys3, x.Ys4, x.Ys5, x.Ys6, x.Ys7, x.Ys8, x.Ys9, x.Ys10, x.Ys11, x.Ys12, x.Ys13, x.Ys14, x.Ys15, x.Ys16, x.Ys17, x.Ys18)
}

// finishPdf is the document unit (stage 3) over the text layer and whatever the page units added to it: fusion, the evidence, furniture,
// tables, headings, paragraphs and columns, the Model. All of it is kin's (`emon.engine`).
func finishPdf(d *pdfDoc, opts Options) outcome {
	m := newArenas()
	r := engine.EnFinishPdf(cfgOf(opts), d.doc, d.ev, m, enctx.NewScratch())
	return finished(r, d.doc, m, d.info, opts, 0)
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
	var docPdf *pdfDoc
	var docRaster *rasterDoc
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
		docPdf, docRaster = s.pdf, s.raster
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
		switch {
		case docPdf != nil:
			kind, np = 1, docPdf.info.Pages
		case docRaster != nil:
			kind, np = 2, docRaster.info.Pages
		}
		m.EndUnit()
		m.NextPhase(kind, opts.Assets != AssetsNone, false, np)
	}
	stats := RunStats{InputBytes: uint64(size)}
	var tPages time.Time
phases:
	for {
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
				switch s := assetUnit(view, pw, docPdf, opts, idx, answers, rq, bms, payload); {
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
			unitP := func(answers, rq *port.Cells, bms *port.BitmapSet, _ *port.Buf) (st int32) {
				defer func() {
					if p := recover(); p != nil {
						failed = panicError(p)
						st = port.PlanUnitDone
					}
				}()
				var s int32
				if docPdf != nil {
					s = unitPdf(docPdf, i, idx, opts, answers, rq, bms)
				} else {
					s = unitRaster(docRaster, i, idx, uint64(size), opts, answers, rq, bms)
				}
				switch s {
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
			if docPdf != nil {
				res = finishGuarded(func() outcome { return finishPdf(docPdf, opts) })
			} else {
				res = finishGuarded(func() outcome { return finishRaster(docRaster, opts) })
			}
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
