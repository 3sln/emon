// The Go half of the plan/fulfil loop and the transcript (DEVLOG P2.5, SPEC
// 7.3, D008, D058 revised, D089). Its Rust twin is `host/rust/src/plan.rs`: read
// that for the reasoning. The record layouts are KIN's (`kin/plan.kin`); this
// file knows only a record's header [len kind id (status)] and the four kinds.
//
// THE TRANSCRIPT HAS LEFT THIS PACKAGE (P10.9, D396, D438): it is kin's (`kin/transcript.kin`) and `emon/planrun` drives it, for the reason the loop
// is there (D405): the generated packages import this one. What stays is the entry (`PlanEntry`) and the fulfillers (the bitmaps' PNG codec is kin's,
// `emon.pngread`, P10.11, D489).
// Nothing here touches a filesystem (D058). No `@kin:link` annotation is declared: this is a host engine for the ports, not a port kin calls.
// Every exported name is prefixed `Plan` so it cannot collide with the port forms beside it.
//
// The loop's shape is the JavaScript host's too: the host owns the await loop (`emon/planrun`, over kin's `planloop`).
// `Start` begins a request (a sync host finishes it there), `Next` returns the
// next completion in whatever order completions arrive.

package port

import "time"

// The arguments of a render and a crop request, as kin names them (`rd-*` in `kin/plan.kin`). THEY ARE KIN'S: this package cannot import the
// generated `plan` (which imports this one), so the offsets are held here and a Go test holds them to kin's.
const (
	PlanRdInput int32 = 0
	PlanRdPage  int32 = 1
	PlanRdDpi   int32 = 2
	PlanRdLayer int32 = 3
	PlanRdX     int32 = 4
	PlanRdY     int32 = 5
	PlanRdW     int32 = 6
	PlanRdH     int32 = 7
)

// The numbers are `emon.plan`'s (a kin law holds them; the driver asserts them).
const (
	PlanKindRender    int32 = 1
	PlanKindDecode    int32 = 2
	PlanKindOcr       int32 = 3
	PlanKindBytes     int32 = 4
	PlanKindCrop      int32 = 5
	PlanKindDecodeImg int32 = 6

	PlanStatusOk     int32 = 0
	PlanStatusFailed int32 = 1

	PlanUnitDone      int32 = 0
	PlanUnitRequests  int32 = 1
	PlanUnitNeedBytes int32 = 7

	// SPEC 13.1's defaults. Both are NAMED limits: a breach is a `*PlanError`
	// whose `Name` is `max-rounds` or `fetch-rounds`.
	PlanMaxRounds   int32 = 5
	PlanFetchRounds int32 = 24
)

// PlanConfig holds the named limits.
type PlanConfig struct {
	MaxRounds   int32
	FetchRounds int32
}

// DefaultPlanConfig is SPEC 13.1's defaults.
func DefaultPlanConfig() PlanConfig {
	return PlanConfig{MaxRounds: PlanMaxRounds, FetchRounds: PlanFetchRounds}
}

// PlanStats counts what a run did.
type PlanStats struct {
	Attempts, Rounds, Requests, FetchRounds, BytesRequests int32
}

// PlanError is a named failure of the loop: the name is kin's (`planloop.Pe*`: `max-rounds` and `fetch-rounds` (limits), `transcript-miss`,
// `transcript-bad`, `fulfil-failed`, `unit-failed`, `bad-record`, `need-bytes-stalled`), made by `planrun`. This package names none.
type PlanError struct {
	Name string
	Msg  string
	// Limited is true for the two named limits, whose bound and the number reached are kin's (`err-limit`, `err-actual`), not words to parse.
	Limited      bool
	Limit, Actual int32
}

func (e *PlanError) Error() string { return e.Name + ": " + e.Msg }

// PlanBitmaps maps an answer's id to its (port) Bitmap.
type PlanBitmaps map[int32]*Bitmap

// PlanRequest is a request record and the unit's PAYLOAD (P6.1a, D291): the bytes kin made itself in the attempt that wrote
// it (a `decode-img`'s codestream when it is not the input's own, a JBIG2 globals stream), shared by every request of the round.
// Never recorded in a transcript: the record names them by SHA-256. Empty for a request that names none.
type PlanRequest struct {
	Rec     []int32
	Payload []byte
}

func (r PlanRequest) Kind() int32 { return r.Rec[1] }
func (r PlanRequest) ID() int32   { return r.Rec[2] }

// Arg is argument k (kin's `rq-arg`).
func (r PlanRequest) Arg(k int) int32 { return r.Rec[3+k] }

// Field is the argument a kin field names (`plan.DqWhere()`, `plan.BqOffset()`, ...): a host reads a request by these and never by a
// literal offset (D396 item 5).
func (r PlanRequest) Field(f int32) int32 { return r.Rec[3+int(f)] }

// PlanAnswer is an answer record and, for render/decode, its pixels.
type PlanAnswer struct {
	Rec    []int32
	Bitmap *Bitmap
}

func (a PlanAnswer) Kind() int32   { return a.Rec[1] }
func (a PlanAnswer) ID() int32     { return a.Rec[2] }
func (a PlanAnswer) Status() int32 { return a.Rec[3] }

// PlanFulfiller fulfils requests, sync or async-shaped.
type PlanFulfiller interface {
	// Start begins a request. A sync host may finish it here. `bm` holds every
	// bitmap answered so far (an OCR request names one by id); take what is
	// needed, it is not kept.
	Start(req PlanRequest, bm PlanBitmaps) error
	// Next returns the next completed answer, in whatever order completions
	// arrive. It is called once per started request.
	Next() (PlanAnswer, error)
}

// PlanSync adapts a function: it completes inside Start.
type PlanSync struct {
	F    func(PlanRequest, PlanBitmaps) (PlanAnswer, error)
	done []planDone
}

type planDone struct {
	a   PlanAnswer
	err error
}

func (s *PlanSync) Start(req PlanRequest, bm PlanBitmaps) error {
	a, err := s.F(req, bm)
	s.done = append(s.done, planDone{a, err})
	return nil
}

func (s *PlanSync) Next() (PlanAnswer, error) {
	d := s.done[0]
	s.done = s.done[1:]
	return d.a, d.err
}

// PlanGoroutines is a genuinely concurrent fulfiller for tests: every Start
// launches a goroutine that works for a pseudo-random while, so completions
// arrive in an order nobody chose.
type PlanGoroutines struct {
	F     func(PlanRequest, PlanBitmaps) (PlanAnswer, error)
	Delay func(PlanRequest) int // microseconds
	ch    chan planDone
}

func (g *PlanGoroutines) Start(req PlanRequest, bm PlanBitmaps) error {
	if g.ch == nil {
		g.ch = make(chan planDone, 1024)
	}
	snap := PlanBitmaps{}
	for k, v := range bm {
		snap[k] = v
	}
	go func() {
		if g.Delay != nil {
			planSleepMicros(g.Delay(req))
		}
		a, err := g.F(req, snap)
		g.ch <- planDone{a, err}
	}()
	return nil
}

func (g *PlanGoroutines) Next() (PlanAnswer, error) {
	d := <-g.ch
	return d.a, d.err
}

func planSleepMicros(us int) { time.Sleep(time.Duration(us) * time.Microsecond) }

// PlanRange is a range of an input the source missed.
type PlanRange struct{ Input, Offset, Length int32 }

// PlanMisses reports what a `Src` missed since the last call, for fetch round
// `round`. The ENGINE implements it over its backings (D058).
type PlanMisses interface {
	Take(round int32) []PlanRange
}

// PlanNoMisses is a source that never misses.
type PlanNoMisses struct{}

func (PlanNoMisses) Take(int32) []PlanRange { return nil }

// THE LOOP is `emon/planrun` (kin's `planloop` drives it; P10.8). It is not in this package because the generated `planloop` imports
// `port`: this file keeps the fulfillers and the requests and answers. THE TRANSCRIPT is kin's too (`kin/transcript.kin`, P10.9), driven by
// `planrun/transcript.go` for the same reason.

// PlanEntry is one recorded request/answer pair (the transcript that holds them is in `emon/planrun`, with the rest of the loop).
type PlanEntry struct {
	Round  int32
	Req    []int32
	Ans    []int32
	Bitmap *Bitmap
}

// FulfilError is a port that could not run (distinct from an answer that says `failed`): a missing renderer, an input the run does not have.
// The loop reports it as kin's `fulfil-failed` (`planrun` does; this package cannot import kin's names).
type FulfilError struct{ Msg string }

func (e *FulfilError) Error() string { return e.Msg }
