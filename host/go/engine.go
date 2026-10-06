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

	"emon/asset"
	"emon/columns"
	"emon/evidence"
	"emon/furniture"
	"emon/gfuse"
	"emon/initial"
	"emon/lists"
	"emon/notes"
	"emon/restruct"
	"emon/heading"
	"emon/html"
	"emon/model"
	"emon/pageunit"
	"emon/para"
	"emon/table"
	"emon/pdfcontent"
	"emon/pdfdoc"
	"emon/pdfmeta"
	"emon/pdfstruct"
	"emon/player"
	"emon/pdfx"
	"emon/plan"
	"emon/port"
	"emon/rasterin"
	"emon/scan"
	"emon/tlhealth"
)

// Transcript is the plan/fulfil transcript (SPEC 13): per input its index and length, and every request
// the kin side made and its answer. The slice issues no requests, so it has no entries.
type Transcript = port.Transcript

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
	if p.Name == "max-rounds" || p.Name == "fetch-rounds" {
		var limit, actual int64
		if _, err := fmt.Sscanf(p.Msg, "the limit is %d and this run reached %d", &limit, &actual); err == nil {
			return &Error{Kind: ErrLimit, Status: p.Name, Limit: limit, Actual: actual}
		}
	}
	return &Error{Kind: ErrPlan, Plan: p}
}

// ----------------------------------------------------------------- options

// OcrPolicy is SPEC 13's OCR setting (D038). WIRED (P6.5, D211-D215): OcrNever asks for no OCR; OcrAuto and OcrAlways OCR an
// image and a scanned PDF page, and a born-digital page (a page with a text layer) keeps its text layer under both, because
// fusion (P7) does not exist yet to weigh a second reading against it (D212).
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
// rendered: P8.5; it runs as AssetsImages until then, D360).
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
}

// DefaultOptions is SPEC 13's defaults.
func DefaultOptions() Options {
	return Options{Ocr: OcrAlways, Assets: AssetsNone, AssetDPI: 150, AssetPixels: 4194304, AssetBytes: 4194304, AssetsBytes: 33554432, Furniture: FurniturePolicy}
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
}

// DefaultConfig is SPEC 13.1's defaults.
func DefaultConfig() Config {
	return Config{ChunkSize: 65536, MaxRounds: port.PlanMaxRounds, FetchRounds: port.PlanFetchRounds}
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

	mu      sync.Mutex
	chunks  [][]byte
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
	return &chunkCache{src: src, n: n, chunk: chunk, deferred: deferred, chunks: make([][]byte, nc),
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
// chunks each side of it, the resident ones left out, a run of chunks one range.
func (c *chunkCache) planFetch(input int32, round int32) []port.PlanRange {
	c.mu.Lock()
	defer c.mu.Unlock()
	n := c.nchunks()
	r := (1 << min(int(round), 20)) - 1
	want := map[int]bool{}
	seeds := make([]int, 0, len(c.pending)+len(c.wanted))
	for k := range c.pending {
		seeds = append(seeds, k)
	}
	for k := range c.wanted {
		seeds = append(seeds, k)
	}
	for _, k := range seeds {
		for j := max(0, k-r); j <= min(n-1, k+r); j++ {
			if c.chunks[j] == nil {
				want[j] = true
			}
		}
	}
	c.pending = map[int]bool{}
	c.wanted = map[int]bool{}
	ks := make([]int, 0, len(want))
	for k := range want {
		ks = append(ks, k)
	}
	sort.Ints(ks)
	var out []port.PlanRange
	for i := 0; i < len(ks); {
		first, last := ks[i], ks[i]
		i++
		for i < len(ks) && ks[i] == last+1 {
			last = ks[i]
			i++
		}
		off := first * c.chunk
		end := min((last+1)*c.chunk, c.n)
		out = append(out, port.PlanRange{Input: input, Offset: int32(off), Length: int32(end - off)})
	}
	return out
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
func portFulfiller(source ByteSource, idx int, keep, recording bool, ocr Ocr) *port.PlanSync {
	return &port.PlanSync{F: func(req port.PlanRequest, bm port.PlanBitmaps) (port.PlanAnswer, error) {
		if req.Kind() == port.PlanKindOcr {
			failed := func(status int32) (port.PlanAnswer, error) {
				c := port.NewCells()
				plan.AnFail(c, port.PlanKindOcr, req.ID(), status)
				return port.PlanAnswer{Rec: cellsVec(c)}, nil
			}
			if ocr == nil {
				return failed(plan.PsUnsupported())
			}
			return ocrSafe(ocr, req, bm, recording, failed)
		}
		if req.Kind() == port.PlanKindRender || req.Kind() == port.PlanKindCrop {
			// no Renderer is configured (D199): an answer, as an Engine with no Ocr answers `ocr`
			c := port.NewCells()
			plan.AnFail(c, req.Kind(), req.ID(), plan.PsUnsupported())
			return port.PlanAnswer{Rec: cellsVec(c)}, nil
		}
		if req.Kind() != port.PlanKindDecode {
			return port.PlanAnswer{}, &port.PlanError{Name: "fulfil-failed", Msg: "no port is configured for this request kind"}
		}
		input, off, n, filter, frame := req.Arg(0), req.Arg(1), req.Arg(2), req.Arg(3), req.Arg(4)
		if int(input) != idx || off < 0 || n < 0 || int64(off)+int64(n) > source.Size() {
			return port.PlanAnswer{}, &port.PlanError{Name: "fulfil-failed", Msg: "a decode request names bytes the input does not have"}
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

// evArenas are the evidence arenas the OCR stages fill (SPEC 8.4.6: the arenas `emon.scan` writes and `EvEmit` dumps). A PDF's
// text layer fills `gl`, `mk`, `fnt` and the word and line arenas; an image's OCR fills the `o*` ones and the same word and
// line arenas (D213), so the rest are empty and only dumped.
type evArenas struct {
	epages, views, pts, refs, feats, paths, images, oruns, oblocks, olines, owords, osyms, oalts, inks, comps, rules, prims, tlines *port.Cells
}

func newEvArenas() *evArenas {
	e := newCellsN(18)
	return &evArenas{e[0], e[1], e[2], e[3], e[4], e[5], e[6], e[7], e[8], e[9], e[10], e[11], e[12], e[13], e[14], e[15], e[16], e[17]}
}

// localeCells is the locale hint's code points (kin's `loc`: what `emon.scan` reads the OCR language from); empty for none.
func localeCells(opts Options) *port.Cells {
	c := port.NewCells()
	for _, ch := range opts.Locale {
		port.CellsPush(c, int32(ch))
	}
	return c
}

// evidenceDump is the evidence dump of a conversion: `ev` is what the OCR stages made, the rest the text layer's.
func evidenceDump(ev *evArenas, et, gl, wd, ln, mk, fnt, grl, gfl *port.Cells) string {
	var sb strings.Builder
	if port.CellsLen(grl) == 0 && port.CellsLen(gfl) == 0 {
		evidence.EvEmit(&sb, 0, ev.epages, ev.views, ev.pts, ev.refs, et, ev.feats, gl, wd, ln, mk, fnt, ev.paths, ev.images, ev.oruns, ev.oblocks, ev.olines, ev.owords, ev.osyms, ev.oalts, ev.inks, ev.comps, ev.rules, ev.prims, ev.tlines)
	} else {
		// P5.5a (D270): a document with fused graphics dumps version "2", the evidence's arrays then grl gfl ggp grg (the rest of the layer joins with P7.3)
		player.PlEmitGfx(&sb, 0, ev.epages, ev.views, ev.pts, ev.refs, et, ev.feats, gl, wd, ln, mk, fnt, ev.paths, ev.images, ev.oruns, ev.oblocks, ev.olines, ev.owords, ev.osyms, ev.oalts, ev.inks, ev.comps, ev.rules, ev.prims, ev.tlines, grl, gfl, port.NewCells(), port.NewCells())
	}
	return sb.String()
}

// attemptRaster is ONE ATTEMPT OF THE RASTER UNIT, from scratch on fresh arenas, as `attempt` is for a PDF. An image's pages
// are made by kin's `RsUnit` (decode, then OCR: P6.5), and its OCR lines are the text layer's lines to everything after (D213):
// the stages below are `attempt`'s, in its order, with the glyph-fed ones given empty arenas.
func attemptRaster(view *port.Src, answers, rq *port.Cells, idx int, format int32, opts Options, intakeOnly bool, n uint64) rstep {
	m := newArenas()
	model.ModelInit(m.nodes, m.attrs, m.regions, m.text, m.pages, m.ledger, m.layout)
	mask := port.NewCells()
	status := rasterin.RsDoc(view, int32(idx), format, answers, rq, m.nodes, m.attrs, m.text, m.pages, mask)
	if view.Missed() {
		return rstep{miss: true}
	}
	fail := func(s string) rstep {
		return rstep{out: outcome{err: &Error{Kind: ErrDamaged, Input: idx, Status: s}}}
	}
	switch status {
	case 0:
	case 1:
		return rstep{requests: true}
	case 21:
		return fail("undecodable")
	case 22:
		return fail("unsupported-image")
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
	info := Intake{Format: name, Pages: int(max(model.PageCount(m.pages), 0)), Bytes: n}
	if intakeOnly {
		return rstep{out: outcome{intake: &info}}
	}
	x := newCellsN(3)
	return rstep{raster: &rasterDoc{m: m, ev: newEvArenas(), et: x[0], wd: x[1], ln: x[2], mask: mask, loc: localeCells(opts), info: info}}
}

// rasterDoc is what the raster document pass leaves for its page units and the document unit (P7.U).
type rasterDoc struct {
	m              *arenas
	ev             *evArenas
	et, wd, ln     *port.Cells
	mask, loc      *port.Cells
	info           Intake
}

// unitRaster is one frame's page unit (P7.U): its own requests and answers; it writes nothing until its answers are in.
func unitRaster(d *rasterDoc, f, idx int, n uint64, opts Options, answers, rq *port.Cells, bms *port.BitmapSet) int32 {
	scr := port.NewCells()
	return rasterin.RsPage(int32(f), int32(idx), int32(n), opts.Ocr.code(), answers, rq, d.loc, bms, d.mask,
		d.ev.epages, d.ev.views, d.et, d.ev.feats, d.ev.images, d.ev.oruns, d.ev.oblocks, d.ev.olines, d.ev.owords, d.ev.osyms, d.ev.oalts, d.wd, d.ln, scr)
}

// finishRaster is the raster document unit: stage 3 over the frames' OCR lines.
func finishRaster(d *rasterDoc, opts Options) outcome {
	m, ev, et, wd, ln, mask, info := d.m, d.ev, d.et, d.wd, d.ln, d.mask, d.info
	g := newCellsN(3)
	gl, mk, fnt := g[0], g[1], g[2]
	var evs *string
	grl, gfl := port.NewCells(), port.NewCells()
	if opts.KeepEvidence {
		s := evidenceDump(ev, et, gl, wd, ln, mk, fnt, grl, gfl)
		evs = &s
	}
	// the triage's flow (P7.0, D241): an image has no text layer, so nothing is set aside and the flow is the OCR lines as they are
	fwc := newCellsN(2)
	fw, fl := fwc[0], fwc[1]
	tlhealth.ThAppend(wd, ln, fw, fl, 0)
	np := model.PageCount(m.pages)
	// Running furniture (P4.3), as `attempt` runs it: an image has no catalogue (no Info, no page labels, no tagged tree), so those
	// arenas are empty and the stage reads the lines and the pages alone.
	q := newCellsN(5)
	qtxt, md, lb, ol, ss := q[0], q[1], q[2], q[3], q[4]
	trust := pdfstruct.TgTrust(ss)
	fc := newCellsN(10)
	fo, fp, ft, fpl := fc[6], fc[7], fc[8], fc[9]
	furniture.FuFind(fw, fl, et, gl, mk, m.pages, np, qtxt, md, lb, trust, opts.Furniture.mode(), fc[0], fc[1], fc[2], fc[3], fc[4], fc[5], fo, fp, ft, fpl)
	// Tables (P5), as `attempt` finds them: before the paragraphs, from the OCR lines like any others; an image has no vector
	// paths, so its grl/gfl are empty (the tracer's witnesses are P7.G's) and only the text-based profiles can find one.
	// (pa stays: the lists stage takes an empty paths arena.)
	pa := port.NewCells()
	tb := newCellsN(24)
	table.TbBuild(fw, fl, et, grl, gfl, np, tb[0], tb[1], tb[2], tb[3], tb[4], tb[5], tb[6], tb[7], tb[8], tb[9], tb[10], tb[11], tb[12], tb[13], tb[14], tb[15], tb[16], tb[17], tb[18], tb[19], tb[20], tb[21], tb[22], tb[23], m.pages)
	t := newCellsN(5)
	c := newCellsN(13)
	// initials (P4.10) on the lines' copy, before the headings read it, exactly as `attempt` does; OCR words have no glyph or
	// font evidence (`gl`, `fnt` are empty), which the detector reads as no initial
	ia := newCellsN(3)
	for _, ch := range opts.Locale {
		port.CellsPush(ia[0], int32(ch))
	}
	columns.LcCopy(fl, c[0])
	initial.InDetect(fw, gl, fnt, et, np, ia[0], c[0], c[1], c[10], c[8], ia[1])
	h := newCellsN(7)
	heading.HdPriorOutline(ol, qtxt, h[5], h[6])
	heading.HdAnalyze(fw, c[0], et, gl, h[0], h[1], h[2], h[3], h[4], h[5], h[6])
	// notes and lists, then restructuring, exactly as `attempt` runs them: the OCR lines are the text layer's lines
	l := newCellsN(6)
	notes.NtAnalyze(fw, c[0], et, gl, h[0], h[1], m.pages, h[2], l[0], l[1], l[2], tb[2])
	lists.LsAnalyze(fw, c[0], et, h[0], pa, h[2], l[3], l[4], l[5], tb[2])
	para.PaBuild(fw, et, np, m.pages, m.layout, m.nodes, m.attrs, m.regions, m.text, t[0], t[1], t[2], t[3], t[4],
		c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], h[2], tb[0], tb[1], tb[2], tb[3], m.ledger, ia[1], ia[2])
	r := newCellsN(10)
	restruct.RsRun(m.nodes, m.attrs, m.regions, m.text, r[0], r[1], r[2], r[3], r[4], r[5], r[6], m.layout)
	m.nodes, r[0] = r[0], m.nodes
	m.attrs, r[1] = r[1], m.attrs
	m.regions, r[2] = r[2], m.regions
	furniture.FuWrite(fo, fp, ft, fpl, np, m.nodes, m.attrs, m.regions, m.text, m.pages, m.ledger, t[2])
	scan.ScTag(m.nodes, m.attrs, m.text, m.regions, m.pages, mask, opts.Ocr.code())
	if opts.Locale != "" {
		start := port.CellsLen(m.text)
		for _, ch := range opts.Locale {
			model.TextAppendCp(m.text, int32(ch))
		}
		model.NodeSetAttrText(m.nodes, m.attrs, 0, model.FkLocale(), start, port.CellsLen(m.text)-start)
	}
	if opts.Assets != AssetsNone {
		// a raster input is one picture a page: its own image is the candidate, `part` of which no figure or seal can be at `images`
		cand, cbytes := port.NewCells(), port.NewBuf()
		asset.AsPageCands(m.pages, cand)
		assetStage(m, cand, cbytes, opts)
	}
	chk := port.NewCells()
	if code := model.ModelCheck(m.nodes, m.attrs, m.regions, m.text, m.pages, m.ledger, m.layout, chk); code != 0 {
		return outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("model-check %d at node %d", code, port.CellsAt(chk, 0))}}
	}
	if o := checkAssets(m, chk, opts); o != nil {
		return *o
	}
	return outcome{built: &built{arenas: m, evidence: evs, intake: info}}
}




// figuresStage is P3.13's provisional figures (SPEC 10.11, 10.6), before RsRun (a caption next to one finds it as its object). Only
// when `assets` is on (D360): a default run's Model is what it was.
func figuresStage(m *arenas, cand *port.Cells) {
	f := newCellsN(5)
	fg, fnodes, fattrs, fregions, fnm := f[0], f[1], f[2], f[3], f[4]
	asset.AsFigureRows(cand, m.pages, fg)
	restruct.RsFigures(m.nodes, m.attrs, m.regions, fnodes, fattrs, fregions, fnm, cand, fg)
	m.nodes, fnodes = fnodes, m.nodes
	m.attrs, fattrs = fattrs, m.attrs
	m.regions, fregions = fregions, m.regions
}

// assetStage is P3.13's asset stage (SPEC 10.11): the settings on the root, then each figure's and seal's pixels or reason, in
// node-id order. The candidates `cand` and their bytes `cbytes` were made by the document pass (asset.AsCollect), which is where
// the input is read.
func assetStage(m *arenas, cand *port.Cells, cbytes *port.Buf, opts Options) {
	sigs := boolInt(opts.AssetSignatures)
	asset.AsSettings(m.nodes, m.attrs, m.text, opts.Assets.level(), opts.AssetDPI, opts.AssetPixels, opts.AssetBytes, opts.AssetsBytes, sigs)
	bx := newCellsN(1)[0]
	for i := 0; i < 5; i++ {
		port.CellsPush(bx, 0)
	}
	asset.AsRun(m.nodes, m.attrs, m.regions, cand, cbytes, m.assets, m.uses, m.abytes, opts.AssetBytes, opts.AssetsBytes, sigs, bx)
}

// checkAssets is `model-check-assets` after `model-check`, when `assets` is on.
func checkAssets(m *arenas, chk *port.Cells, opts Options) *outcome {
	if opts.Assets == AssetsNone {
		return nil
	}
	if code := model.ModelCheckAssets(m.nodes, m.attrs, m.assets, m.uses, m.abytes, chk); code != 0 {
		return &outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("model-check-assets %d at %d", code, port.CellsAt(chk, 0))}}
	}
	return nil
}

func boolInt(b bool) int32 {
	if b {
		return 1
	}
	return 0
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
				s = rstep{out: outcome{err: panicError(panicMessage(p))}}
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
}

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
}

type outcome struct {
	built  *built
	intake *Intake
	err    *Error
}

func statusError(input int, status int32) *Error {
	d := func(s string) *Error { return &Error{Kind: ErrDamaged, Input: input, Status: s} }
	switch status {
	case 1:
		return &Error{Kind: ErrUnsupportedFormat, Input: input}
	case 8:
		return &Error{Kind: ErrPasswordRequired, Input: input}
	case 9:
		return &Error{Kind: ErrPasswordIncorrect, Input: input}
	case 10:
		return &Error{Kind: ErrEncryptionUnsupported, Input: input}
	case 2:
		return d("no-startxref")
	case 3:
		return d("bad-xref")
	case 4:
		return d("no-root")
	case 5:
		return d("no-pages")
	case 6:
		return d("encrypted")
	}
	return d("unreadable")
}

// panicError: a kin limit breach is a panic whose message is `port.LimitBreach`'s ("the {name} limit is
// {limit} and this input reached {actual}. ..."): name it.
func panicError(msg string) *Error {
	if rest, ok := strings.CutPrefix(msg, "the "); ok {
		if i := strings.Index(rest, " limit is "); i >= 0 {
			name := strings.ReplaceAll(rest[:i], " ", "-")
			var limit, actual int64
			if _, err := fmt.Sscanf(rest[i+len(" limit is "):], "%d and this input reached %d", &limit, &actual); err == nil {
				return &Error{Kind: ErrLimit, Status: name, Limit: limit, Actual: actual}
			}
		}
	}
	return &Error{Kind: ErrInternal, Message: msg}
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

// arenas are the seven arenas of the Model (SPEC 3.8).
type arenas struct {
	nodes, attrs, regions, text, pages, ledger, layout *port.Cells
	// the asset table, its uses and the encoded bytes (SPEC 10.11): empty unless `assets` is on
	assets, uses *port.Cells
	abytes       *port.Buf
}

func newArenas() *arenas {
	return &arenas{port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewBuf()}
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
			return step{out: outcome{err: &Error{Kind: ErrDamaged, Input: idx, Status: "no-pages"}}}
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
		return step{out: outcome{err: &Error{Kind: ErrDamaged, Input: idx, Status: "content-failed"}}}
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
	sx := newCellsN(2)
	plan0, sp := sx[0], sx[1]
	pageunit.PuPlan(view, ar, dc, ca, cb, st, pg, im, wd, opts.Ocr.code(), sp, plan0)
	if view.Missed() {
		return step{miss: true}
	}
	// The fused graphics (P5.5a, D270): the vector source's rules and fills of every page, which the table stage reads instead of paths
	// (until P7.3 moves it into the page units, SPEC 9.9).
	gx3 := newCellsN(3)
	grl, gfl, gcand := gx3[0], gx3[1], gx3[2]
	gfuse.GfDoc(pa, pts, mk, im, np, gfuse.GfxMode(), grl, gfl, gcand)
	// P3.13 (SPEC 10.11): the asset candidates, the one place the input's image bytes are read. Off, nothing runs.
	cand, cbytes := port.NewCells(), port.NewBuf()
	if opts.Assets != AssetsNone {
		w := newCellsN(10)
		z := port.NewBuf()
		rc := asset.AsCollect(view, ar, dc, ca, cb, st, im, pa, gl, pg, opts.AssetPixels, opts.AssetsBytes, cand, cbytes, w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7], w[8], z)
		if view.Missed() || rc == 12 {
			return step{miss: true}
		}
	}
	var snap []*port.Cells
	if opts.KeepEvidence {
		snap = []*port.Cells{cloneCells(gl), cloneCells(mk), cloneCells(fnt)}
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
	if view.Missed() {
		return step{miss: true}
	}
	mask := port.NewCells()
	for i := int32(0); i < max(np, 0); i++ {
		port.CellsPush(mask, 0)
	}
	return step{pdf: &pdfDoc{ev: newEvArenas(), et: et, gl: gl, mk: mk, fnt: fnt, wd: wd, ln: ln, fw: fw, fl: fl, fa: fa, nl0: nl0, pa: pa, grl: grl, gfl: gfl, pg: pg, txt: qtxt, md: md, lb: lb, ol: ol, ss: ss,
		plan: plan0, mask: mask, loc: localeCells(opts), snap: snap, cand: cand, cbytes: cbytes, info: info}}
}

// pdfDoc is what the PDF document pass leaves for its page units and the document unit (P7.U, D195).
type pdfDoc struct {
	ev                                   *evArenas
	et, gl, mk, fnt, wd, ln, pa, grl, gfl, pg *port.Cells
	txt, md, lb, ol, ss, plan, mask, loc *port.Cells
	// the flow stage 3 reads (P7.0), the words set aside, and how many lines the text layer had before any page unit
	fw, fl, fa *port.Cells
	nl0        int32
	// the text layer's glyphs, marks and fonts as they were BEFORE the tagged tree was joined to them: what the evidence dump has
	// always shown (kept only when the evidence is asked for)
	snap []*port.Cells
	// P3.13: the image placements as asset candidates and their encoded bytes (empty unless `assets` is on)
	cand   *port.Cells
	cbytes *port.Buf
	info   Intake
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
	return pageunit.PuRunPdf(int32(i), int32(idx), opts.Ocr.code(), d.plan, answers, rq, d.loc, bms, d.mask, scr,
		d.ev.epages, d.ev.views, d.et, d.ev.feats, d.ev.images, d.ev.oruns, d.ev.oblocks, d.ev.olines, d.ev.owords, d.ev.osyms, d.ev.oalts, d.wd, d.ln)
}

// finishPdf is the document unit (stage 3) over the text layer and whatever the page units added to it: furniture, tables, headings,
// paragraphs and columns, the Model. THIS IS THE ONE PLACE the Engine calls those stages.
func finishPdf(d *pdfDoc, opts Options) outcome {
	ev, et, gl, mk, fnt, wd, ln, pa, grl, gfl, pg := d.ev, d.et, d.gl, d.mk, d.fnt, d.wd, d.ln, d.pa, d.grl, d.gfl, d.pg
	fw, fl, fa := d.fw, d.fl, d.fa
	qtxt, md, lb, ol, ss, mask, info := d.txt, d.md, d.lb, d.ol, d.ss, d.mask, d.info
	np := pdfdoc.PdPgCount(pg)
	// the OCR lines of the scanned pages join the flow the text layer's words are in (P7.0's copy)
	tlhealth.ThAppend(wd, ln, fw, fl, d.nl0)
	var evs *string
	if opts.KeepEvidence {
		g, k, f := gl, mk, fnt
		if d.snap != nil {
			g, k, f = d.snap[0], d.snap[1], d.snap[2]
		}
		s := evidenceDump(ev, et, g, wd, ln, k, f, grl, gfl)
		evs = &s
	}
	m := newArenas()
	model.ModelInit(m.nodes, m.attrs, m.regions, m.text, m.pages, m.ledger, m.layout)
	para.PaPages(pg, m.pages)
	trust := pdfstruct.TgTrust(ss)
	// Running furniture (P4.3): found BEFORE the headings and paragraphs, which then never see it, and written after them.
	fc := newCellsN(10)
	fo, fp, ft, fpl := fc[6], fc[7], fc[8], fc[9]
	furniture.FuFind(fw, fl, et, gl, mk, m.pages, np, qtxt, md, lb, trust, opts.Furniture.mode(), fc[0], fc[1], fc[2], fc[3], fc[4], fc[5], fo, fp, ft, fpl)
	// Tables (P5): found before the paragraphs, which leave their lines out and place them (`emon.table`).
	tb := newCellsN(24)
	table.TbBuild(fw, fl, et, grl, gfl, np, tb[0], tb[1], tb[2], tb[3], tb[4], tb[5], tb[6], tb[7], tb[8], tb[9], tb[10], tb[11], tb[12], tb[13], tb[14], tb[15], tb[16], tb[17], tb[18], tb[19], tb[20], tb[21], tb[22], tb[23], m.pages)
	t := newCellsN(5)
	// the column stage's scratch (kin/columns.kin), and its debug log (dbg: one row of eight per join decision)
	c := newCellsN(13)
	// the initials (kin/initial.kin, P4.10) read the glyphs and fonts, and the locale tag as code points (it decides how an
	// initial joins its first word); ia is their table and the open paragraph's alignment rows (kin/align.kin, P4.11). They are
	// found in the copy of the lines BEFORE the heading analysis reads it: a big first letter must never be a heading
	ia := newCellsN(3)
	for _, ch := range opts.Locale {
		port.CellsPush(ia[0], int32(ch))
	}
	columns.LcCopy(fl, c[0])
	initial.InDetect(fw, gl, fnt, et, np, ia[0], c[0], c[1], c[10], c[8], ia[1])
	// the heading analysis (kin/heading.kin, P4.4) decides which lines are headings, at what level, before the
	// paragraph stage builds; its priors: the outline (D172); the tagged roles are a follow-up
	h := newCellsN(7)
	// the outline (P3.9) is a prior on the headings' levels (D172); the tagged tree's roles are not read here yet
	heading.HdPriorOutline(ol, qtxt, h[5], h[6])
	heading.HdAnalyze(fw, c[0], et, gl, h[0], h[1], h[2], h[3], h[4], h[5], h[6])
	// captions, note bodies and their references (kin/notes.kin, P4.6), then lists (kin/lists.kin, P4.5): which lines begin them, written
	// into et and the rows after hc's, which the paragraph stage reads; the notes first, the lists never take a line they took. They
	// read the same copy of the lines the headings do (initials already out of it), and leave a table's lines to the table (tb[2])
	l := newCellsN(6)
	notes.NtAnalyze(fw, c[0], et, gl, h[0], h[1], m.pages, h[2], l[0], l[1], l[2], tb[2])
	lists.LsAnalyze(fw, c[0], et, h[0], pa, h[2], l[3], l[4], l[5], tb[2])
	para.PaBuild(fw, et, np, m.pages, m.layout, m.nodes, m.attrs, m.regions, m.text, t[0], t[1], t[2], t[3], t[4],
		c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], h[2], tb[0], tb[1], tb[2], tb[3], m.ledger, ia[1], ia[2])
	// the provisional figures (P3.13): only with `assets`, and before the restructuring, which pairs a caption with the figure beside it
	if opts.Assets != AssetsNone {
		figuresStage(m, d.cand)
	}
	// restructuring (kin/restruct.kin, P4.5, P4.6): items into lists, captions to their objects, bare links, note references; the
	// body is copied into new arenas, in document order, and they replace the old ones
	r := newCellsN(10)
	restruct.RsRun(m.nodes, m.attrs, m.regions, m.text, r[0], r[1], r[2], r[3], r[4], r[5], r[6], m.layout)
	m.nodes, r[0] = r[0], m.nodes
	m.attrs, r[1] = r[1], m.attrs
	m.regions, r[2] = r[2], m.regions
	furniture.FuWrite(fo, fp, ft, fpl, np, m.nodes, m.attrs, m.regions, m.text, m.pages, m.ledger, t[2])
	// the words the triage set aside (P7.0): ledger rows, after the furniture's
	tlhealth.ThWrite(fa, wd, et, m.ledger, m.text, t[2], port.NewCells())
	scan.ScTag(m.nodes, m.attrs, m.text, m.regions, m.pages, mask, opts.Ocr.code())
	if opts.Locale != "" {
		start := port.CellsLen(m.text)
		for _, ch := range opts.Locale {
			model.TextAppendCp(m.text, int32(ch))
		}
		model.NodeSetAttrText(m.nodes, m.attrs, 0, model.FkLocale(), start, port.CellsLen(m.text)-start)
	}
	if opts.Assets != AssetsNone {
		assetStage(m, d.cand, d.cbytes, opts)
	}
	chk := port.NewCells()
	if c := model.ModelCheck(m.nodes, m.attrs, m.regions, m.text, m.pages, m.ledger, m.layout, chk); c != 0 {
		return outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("model-check %d at node %d", c, port.CellsAt(chk, 0))}}
	}
	if o := checkAssets(m, chk, opts); o != nil {
		return *o
	}
	return outcome{built: &built{arenas: m, evidence: evs, intake: info}}
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
	unit := func(answers, rq *port.Cells) int32 {
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
		if cache != nil && !cache.fetch(req.Arg(1), req.Arg(2)) {
			return port.PlanAnswer{}, &port.PlanError{Name: "fulfil-failed", Msg: "a read of the source failed"}
		}
		c := port.NewCells()
		plan.AnBytes(c, req.ID(), req.Arg(1), req.Arg(2))
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
	// `unsupported` (D199). Pixels are kept in the answers when a transcript is being recorded or an OCR engine will read them.
	mux := &port.KindMux{}
	if e.cfg.Renderer != nil {
		rf := port.NewRenderFulfillerFor(e.cfg.Renderer, idx, port.RenderInput{Source: in.Source, Password: in.Password})
		mux.With([]int32{port.PlanKindRender, port.PlanKindCrop}, rf)
	}
	var ports port.PlanFulfiller = mux.With([]int32{port.PlanKindRender, port.PlanKindCrop, port.PlanKindDecode, port.PlanKindOcr}, portFulfiller(in.Source, idx, rec != nil || e.cfg.Ocr != nil, rec != nil, e.cfg.Ocr))
	var replayer *port.Replayer
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
		replayer = port.NewReplayer(replay)
		ports = replayer
	}
	// THE DOCUMENT PASS: everything that reads the input, in its own loop (misses fetch and re-run it).
	ps, perr := port.PlanRun(pcfg, unit, ports, bytesF, misses, rec)
	// a source that failed is the answer, whatever the loop made of the miss it caused
	if cache != nil {
		if failed, short, msg := cache.fail(); failed {
			if short {
				return outcome{}, RunStats{}, &Error{Kind: ErrSourceChanged, Input: idx}
			}
			return outcome{}, RunStats{}, &Error{Kind: ErrIO, Input: idx, Message: msg}
		}
	}
	if perr != nil {
		var pe *port.PlanError
		if errors.As(perr, &pe) {
			return outcome{}, RunStats{}, planError(pe)
		}
		return outcome{}, RunStats{}, &Error{Kind: ErrInternal, Message: perr.Error()}
	}
	stats := RunStats{Attempts: ps.Attempts, FetchRounds: ps.FetchRounds, BytesRequests: ps.BytesRequests, Requests: ps.Requests, InputBytes: uint64(size)}
	// THE PAGE UNITS (P7.U, D195): one plan/fulfil loop a page, each with its own answers, requests and BitmapSet, dropped when
	// the unit ends, so a conversion holds one page's bitmaps and not the document's. A page unit never reads the input. Then
	// the document unit, stage 3, on what the units left.
	if docPdf != nil || docRaster != nil {
		np := 0
		if docPdf != nil {
			np = docPdf.info.Pages
		} else {
			np = docRaster.info.Pages
		}
		for i := 0; i < np; i++ {
			var failed *Error
			unitP := func(answers, rq *port.Cells, bms *port.BitmapSet) (st int32) {
				defer func() {
					if p := recover(); p != nil {
						failed = panicError(panicMessage(p))
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
				case 21:
					failed = &Error{Kind: ErrDamaged, Input: idx, Status: "undecodable"}
				case 22:
					failed = &Error{Kind: ErrDamaged, Input: idx, Status: "unsupported-image"}
				default:
					failed = &Error{Kind: ErrInternal, Message: fmt.Sprintf("a page unit answered status %d", s)}
				}
				return port.PlanUnitDone
			}
			pst, perr := port.PlanRunBms(pcfg, unitP, ports, bytesF, port.PlanNoMisses{}, rec)
			if perr != nil {
				var pe *port.PlanError
				if errors.As(perr, &pe) {
					return outcome{}, RunStats{}, planError(pe)
				}
				return outcome{}, RunStats{}, &Error{Kind: ErrInternal, Message: perr.Error()}
			}
			if failed != nil {
				return outcome{}, RunStats{}, failed
			}
			stats.Requests += pst.Requests
			stats.PageRounds = append(stats.PageRounds, pst.Rounds)
			stats.PageRequests = append(stats.PageRequests, pst.Requests)
		}
		if docPdf != nil {
			res = finishPdf(docPdf, opts)
		} else {
			res = finishRaster(docRaster, opts)
		}
	}
	if replayer != nil && replayer.Unused() != 0 {
		return outcome{}, RunStats{}, &Error{Kind: ErrPlan, Plan: &port.PlanError{Name: "transcript-bad", Msg: fmt.Sprintf("%d recorded entries were never asked for", replayer.Unused())}}
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
