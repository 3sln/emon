// The Go driver of kin's plan machine (P10.8; D396, D399; DEVLOG P2.5, SPEC 7.3, D058 revised). Its Rust twin is `Machine` in
// `host/rust/src/plan.rs`: read that for the reasoning.
//
// THE LOOP IS KIN'S (`kin/planloop.kin`) AND THE DRIVER IS INVERTED. What was `port.PlanRunBms` and the page loop of `runInputBounded` is
// here the unit call (the caller's closure, under the caller's own panic catch), the await, and the fulfilment of the ports. One
// attempt's accounting, the two named limits, the requests a unit wrote (checked, and sorted by id), the answered set, the order the answers
// are appended in, the transcript's entries, the schedule (`en-next`) and every name are kin's.
//
// This is not in package `port` because the generated `planloop` imports `port` (a Go import cycle otherwise). It is a host file: it names
// no filesystem and declares no `@kin:link` form.
package planrun

import (
	"emon/plan"
	"emon/planctx"
	"emon/planloop"
	"emon/port"
	"math"
	"strings"
	"time"
)

// Unit is kin's unit closed over what it needs: handed the answers so far, an empty requests Cells, the BitmapSet of this attempt and the
// PAYLOAD Buf, it answers a unit status.
type Unit func(answers, rq *port.Cells, bms *port.BitmapSet, payload *port.Buf) int32

// Next is what the host must do before the next attempt.
type Next int

const (
	// Done: the unit finished.
	Done Next = iota
	// Fetch: the attempt missed; fulfil the `bytes` requests of the round (`Machine.Fulfil`), then attempt again.
	Fetch
	// Requests: the unit asked for these; fulfil them (`Machine.Fulfil`), then attempt again.
	Requests
)

// Machine is one conversion's plan machine: it lasts the whole conversion, and `EndUnit` starts the next unit.
type Machine struct {
	p      *planctx.Plan
	bitmap port.PlanBitmaps
	all    port.PlanBitmaps
	reqs   []port.PlanRequest
	staged map[int32]*port.Bitmap
	// when the current unit began, on the host's monotonic clock (D396: a clock is the host's; `stage-ms`, P10.4)
	t0 time.Time
}

// New is a machine with the limits of cfg.
func New(cfg port.PlanConfig) *Machine {
	p := planctx.NewPlan()
	p.MaxRounds = cfg.MaxRounds
	p.FetchLimit = cfg.FetchRounds
	return &Machine{p: p, bitmap: port.PlanBitmaps{}, all: port.PlanBitmaps{}, staged: map[int32]*port.Bitmap{}, t0: time.Now()}
}

// elapsedMs is the milliseconds the current unit has run, rounded up (any time at all is one), saturated at the largest int32: what kin judges
// `stage-ms` against.
func (m *Machine) elapsedMs() int32 {
	ns := time.Since(m.t0).Nanoseconds()
	if ns < 0 {
		return 0
	}
	ms := ns / 1000000
	if ns%1000000 != 0 {
		ms++
	}
	if ms > math.MaxInt32 {
		return math.MaxInt32
	}
	return int32(ms)
}

// Stats is what the current unit has counted.
func (m *Machine) Stats() port.PlanStats {
	return port.PlanStats{Attempts: m.p.Attempts, Rounds: m.p.Rounds, Requests: m.p.Requests, FetchRounds: m.p.FetchRounds, BytesRequests: m.p.BytesRequests}
}

// Totals is what the finished units counted, and the page units' rounds and requests in page order.
type Totals struct {
	Attempts, FetchRounds, BytesRequests, Requests int32
	PageRounds, PageRequests                       []int32
}

// Totals reads kin's totals.
func (m *Machine) Totals() Totals {
	return Totals{Attempts: m.p.TAttempts, FetchRounds: m.p.TFetchRounds, BytesRequests: m.p.TBytesRequests, Requests: m.p.TRequests,
		PageRounds: vec(m.p.Pgr, 0, port.CellsLen(m.p.Pgr)), PageRequests: vec(m.p.Pgq, 0, port.CellsLen(m.p.Pgq))}
}

func vec(c *port.Cells, from, to int32) []int32 {
	var out []int32
	for i := from; i < to; i++ {
		out = append(out, port.CellsAt(c, i))
	}
	return out
}

// err is the error kin holds, as the loop reports it: the name is kin's (`pe`) and so is the sentence.
func (m *Machine) err() *port.PlanError {
	var name strings.Builder
	planloop.PeEmitName(&name, m.p.Err)
	limited := m.p.Err == planloop.PeMaxRounds() || m.p.Err == planloop.PeFetchRounds() || m.p.Err == planloop.PeOcrRequests() || m.p.Err == planloop.PeStageMs()
	return &port.PlanError{Name: name.String(), Msg: m.p.Msg.String(), Limited: limited, Limit: m.p.ErrLimit, Actual: m.p.ErrActual}
}

// records are the records of a Cells of them, back to back (kin's framing).
func records(c *port.Cells) [][]int32 {
	n := port.CellsLen(c)
	var out [][]int32
	for at := int32(0); at < n; at = plan.RecNext(c, at) {
		out = append(out, vec(c, at, at+plan.RecLen(c, at)))
	}
	return out
}

// round is the round kin holds, as requests.
func (m *Machine) round(payload []byte) []port.PlanRequest {
	var out []port.PlanRequest
	for _, rec := range records(m.p.Reqs) {
		out = append(out, port.PlanRequest{Rec: rec, Payload: payload})
	}
	return out
}

// Attempt runs one attempt of the unit from scratch on the answers so far and says what the host must do before the next. An error is
// the loop's (a named limit, a malformed request, ...).
func (m *Machine) Attempt(unit Unit, misses port.PlanMisses) (Next, error) {
	planloop.PlAttempt(m.p)
	m.reqs = nil
	m.staged = map[int32]*port.Bitmap{}
	m.all = port.PlanBitmaps{}
	bms := port.NewBitmapSet()
	for id, b := range m.bitmap {
		bms.Answered[id] = b
	}
	payload := port.NewBuf()
	status := unit(m.p.Ans, m.p.Rq, bms, payload)
	made := bms.Made
	m.p.Elapsed = m.elapsedMs()
	switch act := planloop.PlAfter(m.p, status); act {
	case planloop.ActDone():
		return Done, nil
	case planloop.ActMiss():
		// The attempt missed: it is discarded whole, requests too. The ranges the source missed are the host's to say; what they become,
		// and what none means, is kin's.
		for _, r := range misses.Take(planloop.PlMissRound(m.p)) {
			port.CellsPush(m.p.Cand, r.Input)
			port.CellsPush(m.p.Cand, r.Offset)
			port.CellsPush(m.p.Cand, r.Length)
		}
		if planloop.PlFetch(m.p) != planloop.ActFetch() {
			return Done, m.err()
		}
		m.reqs = m.round(nil)
		return Fetch, nil
	case planloop.ActRequests():
		var shared []byte
		if len(payload.B) > 0 {
			shared = payload.B
		}
		m.reqs = m.round(shared)
		m.all = m.bitmap
		if len(made) > 0 {
			m.all = port.PlanBitmaps{}
			for id, b := range m.bitmap {
				m.all[id] = b
			}
			for id, b := range made {
				m.all[id] = b
			}
		}
		return Requests, nil
	}
	return Done, m.err()
}

// Requests is the round in progress, sorted by id.
func (m *Machine) Requests() []port.PlanRequest { return m.reqs }

// Bitmaps is every bitmap the round's fulfillers may name: answered so far, and the unit's own views.
func (m *Machine) Bitmaps() port.PlanBitmaps { return m.all }

// Offer is an answer to the round, in whatever order it arrives: refused (`bad-record`) when it answers nothing the round asked.
func (m *Machine) Offer(a port.PlanAnswer) error {
	port.CellsDrop(m.p.Cur, 0)
	for _, v := range a.Rec {
		port.CellsPush(m.p.Cur, v)
	}
	if planloop.PlOffer(m.p) != 0 {
		return m.err()
	}
	m.staged[a.Rec[2]] = a.Bitmap
	return nil
}

// Has is whether the round has an answer to request id.
func (m *Machine) Has(id int32) bool { return planloop.PlHas(m.p, id) == 1 }

// Owed is how many requests of the round have no answer yet.
func (m *Machine) Owed() int32 { return planloop.PlOwed(m.p) }

// Commit is called when every request of the round is answered: the answers join the unit's, in id order. The entries are what a transcript
// records (round, request, answer, bitmap), in id order; a `bytes` round has none.
func (m *Machine) Commit() ([]port.PlanEntry, error) {
	requestsRound := m.p.Outk == 2
	if planloop.PlCommit(m.p) != 0 {
		return nil, m.err()
	}
	var out []port.PlanEntry
	tx := m.p.Tx
	n := port.CellsLen(tx)
	for at := int32(0); at < n; {
		round := planloop.TxRound(tx, at)
		rq, an, next := planloop.TxReq(tx, at), planloop.TxAns(tx, at), planloop.TxNext(tx, at)
		req, ans := vec(tx, rq, an), vec(tx, an, next)
		bm := m.staged[ans[2]]
		if bm != nil {
			m.bitmap[ans[2]] = bm
		}
		out = append(out, port.PlanEntry{Round: round, Req: req, Ans: ans, Bitmap: bm})
		at = next
	}
	port.CellsDrop(tx, 0)
	m.staged = map[int32]*port.Bitmap{}
	if requestsRound {
		m.reqs = nil
		m.all = port.PlanBitmaps{}
	}
	return out, nil
}

// Fulfil starts every request of the round with f and takes every completion, in whatever order they arrive; the round is committed.
func (m *Machine) Fulfil(f port.PlanFulfiller) ([]port.PlanEntry, error) {
	all := port.PlanBitmaps{}
	if m.p.Outk == 2 {
		all = m.all
	}
	for _, r := range m.reqs {
		if err := f.Start(r, all); err != nil {
			return nil, named(err)
		}
	}
	for range m.reqs {
		a, err := f.Next()
		if err != nil {
			return nil, named(err)
		}
		if err := m.Offer(a); err != nil {
			return nil, err
		}
	}
	return m.Commit()
}

// EndUnit folds a finished unit's accounting into the totals and starts the next with no answers and no bitmaps.
func (m *Machine) EndUnit() {
	planloop.PlFold(m.p)
	m.t0 = time.Now()
	m.bitmap = port.PlanBitmaps{}
	m.all = port.PlanBitmaps{}
	m.reqs = nil
	m.staged = map[int32]*port.Bitmap{}
}

// NextPhase is the schedule (kin's `en-next`): the unit that follows, as a `planloop.Ph*` code. kind is what the document pass built (0
// nothing, 1 a PDF, 2 a raster input), assets whether the asset level is on, pending whether the figures unit is still to run, np the page count.
func (m *Machine) NextPhase(kind int32, assets, pending bool, np int) int32 {
	return planloop.EnNext(m.p, kind, b2i(assets), b2i(pending), int32(np))
}

// Phase is the unit the schedule is on.
func (m *Machine) Phase() int32 { return m.p.Phase }

// Page is the page unit the schedule is on.
func (m *Machine) Page() int { return int(m.p.Page) }

func b2i(b bool) int32 {
	if b {
		return 1
	}
	return 0
}

// Drive runs the unit to completion with the synchronous driver: a miss is fetched with bytesF and the unit re-run; a round of requests is
// fulfilled with ports (and recorded, when rec is not nil) and the unit re-run on the answers.
func Drive(m *Machine, unit Unit, ports, bytesF port.PlanFulfiller, misses port.PlanMisses, rec *Transcript) error {
	for {
		next, err := m.Attempt(unit, misses)
		if err != nil {
			return err
		}
		switch next {
		case Done:
			return nil
		case Fetch:
			if _, err := m.Fulfil(bytesF); err != nil {
				return err
			}
		case Requests:
			entries, err := m.Fulfil(ports)
			if err != nil {
				return err
			}
			if rec != nil {
				rec.Entries = append(rec.Entries, entries...)
			}
		}
	}
}

// Run runs one unit to completion in a machine of its own (a PAGE UNIT's loop, P7.U, D196: the unit is also handed the BitmapSet of the
// attempt, made from the bitmaps its answers carried; what kin made in it is added to the bitmaps a fulfiller sees). `ports` fulfils
// render/decode/ocr; `bytesF` fulfils the loop's own `bytes` requests; `rec`, when not nil, records every render/decode/ocr pair.
func Run(cfg port.PlanConfig, unit Unit, ports, bytesF port.PlanFulfiller, misses port.PlanMisses, rec *Transcript) (port.PlanStats, error) {
	m := New(cfg)
	err := Drive(m, unit, ports, bytesF, misses, rec)
	return m.Stats(), err
}

// Spans are the spans of input me that a request names (kin's `pl-spans`): as (offset, length).
func Spans(req port.PlanRequest, me int32) [][2]int32 {
	c := port.NewCells()
	for _, v := range req.Rec {
		port.CellsPush(c, v)
	}
	out := port.NewCells()
	planloop.PlSpans(c, 0, me, out)
	var s [][2]int32
	for i := int32(0); i < port.CellsLen(out)/2; i++ {
		s = append(s, [2]int32{port.CellsAt(out, 2*i), port.CellsAt(out, 2*i+1)})
	}
	return s
}

// Route is which port fulfils a request of kind (kin's `pl-route`): ocr says whether the host has an engine, render a renderer.
func Route(kind int32, ocr, render bool) int32 {
	ports := int32(0)
	if ocr {
		ports += planloop.PpOcr()
	}
	if render {
		ports += planloop.PpRender()
	}
	return planloop.PlRoute(kind, ports)
}

// UnsupportedAnswer is the answer of a request no port is configured for.
func UnsupportedAnswer(req port.PlanRequest) port.PlanAnswer {
	c := port.NewCells()
	planloop.PlUnsupported(c, req.Kind(), req.ID())
	return port.PlanAnswer{Rec: records(c)[0]}
}
