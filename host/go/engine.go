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
// nothing; the Model's header writes no `ocr`), images and TIFF are `unsupported-format`, several inputs as
// one document and `assets` other than `none` are `ErrUnsupported`. The loop is wired (D058): a fresh `Src`
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

	"emon/evidence"
	"emon/html"
	"emon/model"
	"emon/para"
	"emon/pdfcontent"
	"emon/pdfdoc"
	"emon/plan"
	"emon/port"
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
	// ErrUnsupportedFormat: not a PDF (no `%PDF-` header): an image or something else; not read in this slice.
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
		return fmt.Sprintf("unsupported-format: input %d is not a PDF (images are not read in this slice)", e.Input)
	case ErrDamaged:
		return fmt.Sprintf("%s: input %d could not be opened as a PDF", e.Status, e.Input)
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

// OcrPolicy is SPEC 13's OCR setting (D038). NOT WIRED IN THIS SLICE: accepted, kept, and without effect.
type OcrPolicy int

const (
	OcrAlways OcrPolicy = iota
	OcrAuto
	OcrNever
)

// Assets is SPEC 13's `assets` (D117). Only AssetsNone is implemented; the others are refused, not ignored.
type Assets int

const (
	AssetsNone Assets = iota
	AssetsImages
	AssetsFigures
)

// Options is what a conversion is asked to do. DefaultOptions is SPEC 13's defaults.
type Options struct {
	// Locale is a BCP 47 hint, written to the Model's `settings.locale`; empty is none. It decides nothing yet.
	Locale string
	Ocr    OcrPolicy
	Assets Assets
	// KeepEvidence keeps the text layer's evidence dump (`Model.Evidence`): a test and debugging aid.
	KeepEvidence bool
}

// DefaultOptions is SPEC 13's defaults.
func DefaultOptions() Options {
	return Options{Ocr: OcrAlways, Assets: AssetsNone}
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
}

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

// noPorts is the kin-side requests this slice issues: none. A Renderer, ImageDecode or Ocr port is not
// configured, so were the unit to ask, the loop says so (a named `fulfil-failed`) rather than guess.
type noPorts struct{}

func (noPorts) Start(port.PlanRequest, port.PlanBitmaps) error {
	return &port.PlanError{Name: "fulfil-failed", Msg: "no Renderer, ImageDecode or Ocr port is configured (this slice issues no requests)"}
}
func (noPorts) Next() (port.PlanAnswer, error) {
	return port.PlanAnswer{}, &port.PlanError{Name: "fulfil-failed", Msg: "no port is configured"}
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
}

func newArenas() *arenas {
	return &arenas{port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()}
}

func cellsVec(c *port.Cells) []int32 { return append([]int32(nil), c.V...) }

func textOf(c *port.Cells, start, n int32) string {
	var sb strings.Builder
	for i := start; i < start+n; i++ {
		sb.WriteRune(rune(model.TextAt(c, i)))
	}
	return sb.String()
}

// step is one attempt's end: it missed (discard it, fetch, re-run) or it finished.
type step struct {
	miss bool
	out  outcome
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
	x := newCellsN(14)
	cx, ox, cs, out, f0, f1, f2, f3, gl, et, mk, fnt, wd, ln := x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], x[8], x[9], x[10], x[11], x[12], x[13]
	status := pdfcontent.PcDocument(view, ar, dc, ca, cb, st, cx, ox, cs, out, f0, f1, f2, f3, gl, et, mk, fnt, pg, wd, ln)
	if view.Missed() || status == pdfdoc.PdNeedBytes() {
		return step{miss: true}
	}
	if status != 0 {
		return step{out: outcome{err: &Error{Kind: ErrDamaged, Input: idx, Status: "content-failed"}}}
	}
	np := pdfdoc.PdPgCount(pg)
	info := intake(np)
	var ev *string
	if opts.KeepEvidence {
		e := newCellsN(18)
		var sb strings.Builder
		evidence.EvEmit(&sb, 0, e[0], e[1], e[2], e[3], et, e[4], gl, wd, ln, mk, fnt, e[5], e[6], e[7], e[8], e[9], e[10], e[11], e[12], e[13], e[14], e[15], e[16], e[17])
		s := sb.String()
		ev = &s
	}
	m := newArenas()
	model.ModelInit(m.nodes, m.attrs, m.regions, m.text, m.pages, m.ledger, m.layout)
	para.PaPages(pg, m.pages)
	t := newCellsN(5)
	para.PaBuild(wd, ln, et, np, m.nodes, m.attrs, m.regions, m.text, t[0], t[1], t[2], t[3], t[4])
	if opts.Locale != "" {
		start := port.CellsLen(m.text)
		for _, ch := range opts.Locale {
			model.TextAppendCp(m.text, int32(ch))
		}
		model.NodeSetAttrText(m.nodes, m.attrs, 0, model.FkLocale(), start, port.CellsLen(m.text)-start)
	}
	chk := port.NewCells()
	if c := model.ModelCheck(m.nodes, m.attrs, m.regions, m.text, m.pages, m.ledger, m.layout, chk); c != 0 {
		return step{out: outcome{err: &Error{Kind: ErrInternal, Message: fmt.Sprintf("model-check %d at node %d", c, port.CellsAt(chk, 0))}}}
	}
	if view.Missed() {
		return step{miss: true}
	}
	return step{out: outcome{built: &built{arenas: m, evidence: ev, intake: info}}}
}

// safeAttempt runs attempt and turns a panic into a miss (when the view missed) or a named error.
func safeAttempt(view, pw *port.Src, idx int, opts Options, intakeOnly bool, n uint64) (s step) {
	defer func() {
		if p := recover(); p != nil {
			if view.Missed() {
				s = step{miss: true}
			} else {
				s = step{out: outcome{err: panicError(panicMessage(p))}}
			}
		}
	}()
	return attempt(view, pw, idx, opts, intakeOnly, n)
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
	if opts.Assets != AssetsNone {
		return nil, RunStats{}, &Error{Kind: ErrUnsupported, Message: "assets other than `none` are specified (D117) and not implemented"}
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
	unit := func(_, _ *port.Cells) int32 {
		view := base.View()
		s := safeAttempt(view, pw, idx, opts, intakeOnly, uint64(size))
		if s.miss {
			return port.PlanUnitNeedBytes
		}
		res = s.out
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
	var ports port.PlanFulfiller = noPorts{}
	var replayer *port.Replayer
	if rec != nil {
		rec.Inputs = thisInput
	}
	if replay != nil {
		if err := replay.CheckInputs(thisInput); err != nil {
			return outcome{}, RunStats{}, planError(err.(*port.PlanError))
		}
		replayer = port.NewReplayer(replay)
		ports = replayer
	}
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
	if replayer != nil && replayer.Unused() != 0 {
		return outcome{}, RunStats{}, &Error{Kind: ErrPlan, Plan: &port.PlanError{Name: "transcript-bad", Msg: fmt.Sprintf("%d recorded entries were never asked for", replayer.Unused())}}
	}
	stats := RunStats{Attempts: ps.Attempts, FetchRounds: ps.FetchRounds, BytesRequests: ps.BytesRequests, Requests: ps.Requests, InputBytes: uint64(size)}
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
