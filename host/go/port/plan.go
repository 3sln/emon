// The Go half of the plan/fulfil loop and the transcript (DEVLOG P2.5, SPEC
// 7.3, D008, D058 revised, D089). Its Rust twin is `host/rust/src/plan.rs`: read
// that for the reasoning. The record layouts are KIN's (`kin/plan.kin`); this
// file knows only a record's header [len kind id (status)] and the four kinds.
//
// Nothing here touches a filesystem (D058): a transcript is named files in
// memory (`Files`, `TranscriptFromFiles`) and the driver moves them to a
// directory. No `@kin:link` annotation is declared: this is a host engine for
// the ports, not a port kin calls. Every exported name is prefixed `Plan` (or is
// `Transcript`, `Replayer`) so it cannot collide with the port forms beside it.
//
// The loop's shape is the JavaScript host's too: the host owns the await loop.
// `Start` begins a request (a sync host finishes it there), `Next` returns the
// next completion in whatever order completions arrive.

package port

import (
	"bytes"
	"errors"
	"fmt"
	"hash/adler32"
	"hash/crc32"
	"sort"
	"strconv"
	"strings"
	"time"
)

// The numbers are `emon.plan`'s (a kin law holds them; the driver asserts them).
const (
	PlanKindRender int32 = 1
	PlanKindDecode int32 = 2
	PlanKindOcr    int32 = 3
	PlanKindBytes  int32 = 4

	PlanStatusOk     int32 = 0
	PlanStatusFailed int32 = 1

	PlanUnitDone      int32 = 0
	PlanUnitRequests  int32 = 1
	PlanUnitNeedBytes int32 = 7

	// SPEC 13.1's defaults. Both are NAMED limits: a breach is a `*PlanError`
	// whose `Name` is `max-rounds` or `fetch-rounds`.
	PlanMaxRounds   int32 = 3
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

// PlanError is a named failure: `max-rounds`, `fetch-rounds` (limits),
// `transcript-miss`, `transcript-bad`, `fulfil-failed`, `unit-failed`,
// `bad-record`, `need-bytes-stalled`.
type PlanError struct {
	Name string
	Msg  string
}

func (e *PlanError) Error() string { return e.Name + ": " + e.Msg }

func planErr(name string, format string, a ...any) *PlanError {
	return &PlanError{Name: name, Msg: fmt.Sprintf(format, a...)}
}

func planLimit(name string, limit, actual int32) *PlanError {
	return planErr(name, "the limit is %d and this run reached %d", limit, actual)
}

// PlanBitmap is PROVISIONAL (P2.5): the SPEC's `Bitmap` port is P2.7's and lives
// in port.go when it lands; this is the least a render/decode answer needs to
// carry pixels into a transcript, and is named differently so the two cannot
// collide. Replace it by `Bitmap` then and keep PngEncode/PngDecode over it.
type PlanBitmap struct {
	W, H, Channels int32 // channels: 1 gray, 3 RGB
	Pixels         []byte
}

// PlanBitmaps maps an answer's id to its bitmap.
type PlanBitmaps map[int32]*PlanBitmap

// PlanRequest is a request record.
type PlanRequest struct{ Rec []int32 }

func (r PlanRequest) Kind() int32 { return r.Rec[1] }
func (r PlanRequest) ID() int32   { return r.Rec[2] }

// Arg is argument k (kin's `rq-arg`).
func (r PlanRequest) Arg(k int) int32 { return r.Rec[3+k] }

// PlanAnswer is an answer record and, for render/decode, its pixels.
type PlanAnswer struct {
	Rec    []int32
	Bitmap *PlanBitmap
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

// PlanUnit is kin's unit closed over what it needs: handed the answers so far
// and an empty requests Cells, it answers a unit status.
type PlanUnit func(answers, requests *Cells) int32

func planRecordAt(c *Cells, at, n int32) []int32 {
	out := make([]int32, n)
	for i := int32(0); i < n; i++ {
		out[i] = CellsAt(c, at+i)
	}
	return out
}

func planReadRequests(rq *Cells, answered map[int32]bool) ([]PlanRequest, error) {
	n := CellsLen(rq)
	var at int32
	var out []PlanRequest
	seen := map[int32]bool{}
	for at < n {
		ln := CellsAt(rq, at)
		if ln < 3 || at+ln > n {
			return nil, planErr("bad-record", "a request at %d has length %d", at, ln)
		}
		rec := planRecordAt(rq, at, ln)
		if rec[1] < PlanKindRender || rec[1] > PlanKindOcr {
			return nil, planErr("bad-record", "request %d has kind %d, which kin may not ask for", rec[2], rec[1])
		}
		if rec[2] < 1 {
			return nil, planErr("bad-record", "a request has id %d", rec[2])
		}
		if seen[rec[2]] || answered[rec[2]] {
			return nil, planErr("bad-record", "request id %d is used twice", rec[2])
		}
		seen[rec[2]] = true
		out = append(out, PlanRequest{rec})
		at += ln
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].ID() < out[j].ID() })
	return out, nil
}

func planFulfil(f PlanFulfiller, reqs []PlanRequest, bm PlanBitmaps) ([]PlanAnswer, error) {
	for _, r := range reqs {
		if err := f.Start(r, bm); err != nil {
			return nil, err
		}
	}
	pending := map[int32]int32{}
	for _, r := range reqs {
		pending[r.ID()] = r.Kind()
	}
	var out []PlanAnswer
	for range reqs {
		a, err := f.Next()
		if err != nil {
			return nil, err
		}
		if len(a.Rec) < 4 || int(a.Rec[0]) != len(a.Rec) {
			return nil, planErr("bad-record", "an answer has length %d in a record of %d", a.Rec[0], len(a.Rec))
		}
		if k, ok := pending[a.ID()]; !ok || k != a.Kind() {
			return nil, planErr("bad-record", "an answer for request %d (kind %d) answers nothing asked", a.ID(), a.Kind())
		}
		delete(pending, a.ID())
		out = append(out, a)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].ID() < out[j].ID() })
	return out, nil
}

// PlanRun runs one unit to completion. `ports` fulfils render/decode/ocr;
// `bytesF` fulfils the loop's own `bytes` requests; `rec`, when not nil, records
// every render/decode/ocr pair (never `bytes`) in a deterministic order.
func PlanRun(cfg PlanConfig, unit PlanUnit, ports, bytesF PlanFulfiller, misses PlanMisses, rec *Transcript) (PlanStats, error) {
	answers := NewCells()
	bitmaps := PlanBitmaps{}
	answered := map[int32]bool{}
	var st PlanStats
	var fetchRound int32
	for {
		rq := NewCells()
		st.Attempts++
		status := unit(answers, rq)
		switch status {
		case PlanUnitNeedBytes:
			// The attempt missed: discard it whole (its requests too), fetch,
			// re-run. Not a round; counted against `fetch-rounds` alone.
			fetchRound++
			st.FetchRounds++
			if fetchRound > cfg.FetchRounds {
				return st, planLimit("fetch-rounds", cfg.FetchRounds, fetchRound)
			}
			ranges := misses.Take(fetchRound - 1)
			if len(ranges) == 0 {
				return st, planErr("need-bytes-stalled", "the unit missed and the source reports nothing missed")
			}
			var reqs []PlanRequest
			for _, r := range ranges {
				st.BytesRequests++
				reqs = append(reqs, PlanRequest{[]int32{6, PlanKindBytes, -st.BytesRequests, r.Input, r.Offset, r.Length}})
			}
			got, err := planFulfil(bytesF, reqs, PlanBitmaps{})
			if err != nil {
				return st, err
			}
			for _, a := range got {
				if a.Status() != PlanStatusOk {
					return st, planErr("fulfil-failed", "fetching bytes: status %d", a.Status())
				}
			}
			continue
		case PlanUnitDone:
			if CellsLen(rq) != 0 {
				return st, planErr("bad-record", "the unit is done and left requests")
			}
			return st, nil
		case PlanUnitRequests:
		default:
			return st, planErr("unit-failed", "status %d", status)
		}
		fetchRound = 0
		reqs, err := planReadRequests(rq, answered)
		if err != nil {
			return st, err
		}
		if len(reqs) == 0 {
			return st, planErr("bad-record", "the unit asked for requests and wrote none")
		}
		st.Rounds++
		if st.Rounds > cfg.MaxRounds {
			return st, planLimit("max-rounds", cfg.MaxRounds, st.Rounds)
		}
		st.Requests += int32(len(reqs))
		round := st.Rounds - 1
		got, err := planFulfil(ports, reqs, bitmaps)
		if err != nil {
			return st, err
		}
		for i, a := range got {
			for _, v := range a.Rec {
				CellsPush(answers, v)
			}
			answered[a.ID()] = true
			if a.Bitmap != nil {
				bitmaps[a.ID()] = a.Bitmap
			}
			if rec != nil {
				rec.Entries = append(rec.Entries, PlanEntry{Round: round, Req: reqs[i].Rec, Ans: a.Rec, Bitmap: a.Bitmap})
			}
		}
	}
}

// ---------------------------------------------------------------- transcript

// PlanEntry is one recorded request/answer pair.
type PlanEntry struct {
	Round  int32
	Req    []int32
	Ans    []int32
	Bitmap *PlanBitmap
}

// Transcript is the complete set of answers of one run. Inputs are (index,
// length) only: never bytes, origin, fetch count or password (SPEC 13.1).
type Transcript struct {
	Inputs  [][2]int32
	Entries []PlanEntry
}

// TranscriptFile is the name of the records file.
const TranscriptFile = "transcript.edn"

// PlanFile is one named file of a transcript.
type PlanFile struct {
	Name  string
	Bytes []byte
}

func planKindName(k int32) string {
	switch k {
	case PlanKindRender:
		return "render"
	case PlanKindDecode:
		return "decode"
	case PlanKindOcr:
		return "ocr"
	}
	return "unknown"
}

func planKindCode(s string) (int32, bool) {
	switch s {
	case "render":
		return PlanKindRender, true
	case "decode":
		return PlanKindDecode, true
	case "ocr":
		return PlanKindOcr, true
	}
	return 0, false
}

func planInts(v []int32) string {
	s := make([]string, len(v))
	for i, x := range v {
		s[i] = strconv.Itoa(int(x))
	}
	return "[" + strings.Join(s, " ") + "]"
}

// ToFiles writes the transcript as files in a fixed order: transcript.edn, then
// one lossless PNG per bitmap named by its entry's index. DETERMINISTIC: the
// same entries always give the same bytes (and the same bytes as the Rust host).
func (t *Transcript) ToFiles() []PlanFile {
	var sb strings.Builder
	sb.WriteString("{:emon-transcript 1\n :inputs [")
	for i, in := range t.Inputs {
		if i > 0 {
			sb.WriteString(" ")
		}
		fmt.Fprintf(&sb, "{:index %d :length %d}", in[0], in[1])
	}
	sb.WriteString("]\n :entries [")
	var pngs []PlanFile
	for n, e := range t.Entries {
		kind := int32(0)
		if len(e.Req) > 1 {
			kind = e.Req[1]
		}
		fmt.Fprintf(&sb, "\n  {:round %d :kind :%s :req %s :ans %s", e.Round, planKindName(kind), planInts(e.Req), planInts(e.Ans))
		if e.Bitmap != nil {
			name := fmt.Sprintf("bitmap-%04d.png", n)
			fmt.Fprintf(&sb, " :bitmap \"%s\"", name)
			pngs = append(pngs, PlanFile{name, PngEncode(e.Bitmap)})
		}
		sb.WriteString("}")
	}
	sb.WriteString("]}\n")
	return append([]PlanFile{{TranscriptFile, []byte(sb.String())}}, pngs...)
}

// TranscriptFromFiles reads what ToFiles wrote.
func TranscriptFromFiles(files map[string][]byte) (*Transcript, error) {
	bad := func(m string) error { return planErr("transcript-bad", "%s", m) }
	raw, ok := files[TranscriptFile]
	if !ok {
		return nil, bad("no transcript.edn")
	}
	p := &ednParser{b: []rune(string(raw))}
	top, err := p.parse()
	if err != nil {
		return nil, bad(err.Error())
	}
	m, ok := top.(map[string]any)
	if !ok {
		return nil, bad("the transcript is not a map")
	}
	if v, _ := m["emon-transcript"].(int64); v != 1 {
		return nil, bad("not an emon-transcript of version 1")
	}
	t := &Transcript{}
	ins, ok := m["inputs"].([]any)
	if !ok {
		return nil, bad("no :inputs")
	}
	for _, i := range ins {
		im, ok := i.(map[string]any)
		idx, ok1 := im["index"].(int64)
		ln, ok2 := im["length"].(int64)
		if !ok || !ok1 || !ok2 {
			return nil, bad("an input needs :index and :length")
		}
		t.Inputs = append(t.Inputs, [2]int32{int32(idx), int32(ln)})
	}
	ents, ok := m["entries"].([]any)
	if !ok {
		return nil, bad("no :entries")
	}
	for _, e := range ents {
		em, ok := e.(map[string]any)
		if !ok {
			return nil, bad("an entry is not a map")
		}
		round, ok := em["round"].(int64)
		if !ok {
			return nil, bad("an entry has no :round")
		}
		kw, _ := em["kind"].(ednKw)
		kind, ok := planKindCode(string(kw))
		if !ok {
			return nil, bad("an entry has no known :kind")
		}
		req, ok1 := planIntVec(em["req"])
		ans, ok2 := planIntVec(em["ans"])
		if !ok1 || !ok2 {
			return nil, bad("an entry needs :req and :ans")
		}
		if len(req) < 3 || int(req[0]) != len(req) || req[1] != kind {
			return nil, bad("an entry's :req is not a record of its :kind")
		}
		if len(ans) < 4 || int(ans[0]) != len(ans) || ans[1] != kind || ans[2] != req[2] {
			return nil, bad("an entry's :ans is not the answer to its :req")
		}
		ent := PlanEntry{Round: int32(round), Req: req, Ans: ans}
		if name, ok := em["bitmap"].(string); ok {
			png, ok := files[name]
			if !ok {
				return nil, planErr("transcript-bad", "%s is missing", name)
			}
			b, err := PngDecode(png)
			if err != nil {
				return nil, planErr("transcript-bad", "%s", err.Error())
			}
			ent.Bitmap = b
		}
		t.Entries = append(t.Entries, ent)
	}
	return t, nil
}

func planIntVec(v any) ([]int32, bool) {
	a, ok := v.([]any)
	if !ok {
		return nil, false
	}
	out := make([]int32, len(a))
	for i, x := range a {
		n, ok := x.(int64)
		if !ok {
			return nil, false
		}
		out[i] = int32(n)
	}
	return out, true
}

// CheckInputs: replay must be given the inputs the recording saw.
func (t *Transcript) CheckInputs(inputs [][2]int32) error {
	if len(t.Inputs) != len(inputs) {
		return planErr("transcript-bad", "the transcript is of inputs %v, not %v", t.Inputs, inputs)
	}
	for i := range inputs {
		if t.Inputs[i] != inputs[i] {
			return planErr("transcript-bad", "the transcript is of inputs %v, not %v", t.Inputs, inputs)
		}
	}
	return nil
}

// Replayer answers from a transcript: a request it has no entry for is
// `transcript-miss`, never a guess. `bytes` is not here and cannot be.
type Replayer struct {
	entries []PlanEntry
	index   map[string]int
	used    []bool
	done    []planDone
}

func planKey(rec []int32) string { return planInts(rec) }

// NewReplayer indexes a transcript.
func NewReplayer(t *Transcript) *Replayer {
	r := &Replayer{entries: t.Entries, index: map[string]int{}, used: make([]bool, len(t.Entries))}
	for i, e := range t.Entries {
		r.index[planKey(e.Req)] = i
	}
	return r
}

// Unused is how many recorded entries the run never asked for.
func (r *Replayer) Unused() int {
	n := 0
	for _, u := range r.used {
		if !u {
			n++
		}
	}
	return n
}

func (r *Replayer) Start(req PlanRequest, _ PlanBitmaps) error {
	if i, ok := r.index[planKey(req.Rec)]; ok {
		r.used[i] = true
		e := r.entries[i]
		r.done = append(r.done, planDone{PlanAnswer{Rec: e.Ans, Bitmap: e.Bitmap}, nil})
	} else {
		r.done = append(r.done, planDone{err: planErr("transcript-miss", "no answer recorded for request %s", planKey(req.Rec))})
	}
	return nil
}

func (r *Replayer) Next() (PlanAnswer, error) {
	d := r.done[0]
	r.done = r.done[1:]
	return d.a, d.err
}

// ------------------------------------------------------------ EDN (a subset)
// Maps, vectors, keywords, integers and strings: all a transcript holds.

type ednKw string

type ednParser struct {
	b []rune
	i int
}

func (p *ednParser) skip() {
	for p.i < len(p.b) && (p.b[p.i] == ',' || p.b[p.i] == ' ' || p.b[p.i] == '\n' || p.b[p.i] == '\t' || p.b[p.i] == '\r') {
		p.i++
	}
}

func (p *ednParser) parse() (any, error) {
	v, err := p.value()
	if err != nil {
		return nil, err
	}
	p.skip()
	if p.i != len(p.b) {
		return nil, fmt.Errorf("trailing text at %d", p.i)
	}
	return v, nil
}

func (p *ednParser) value() (any, error) {
	p.skip()
	if p.i >= len(p.b) {
		return nil, errors.New("unexpected end")
	}
	switch c := p.b[p.i]; {
	case c == '{':
		p.i++
		m := map[string]any{}
		for {
			p.skip()
			if p.i >= len(p.b) {
				return nil, errors.New("unterminated map")
			}
			if p.b[p.i] == '}' {
				p.i++
				return m, nil
			}
			k, err := p.value()
			if err != nil {
				return nil, err
			}
			kw, ok := k.(ednKw)
			if !ok {
				return nil, errors.New("a map key is not a keyword")
			}
			v, err := p.value()
			if err != nil {
				return nil, err
			}
			m[string(kw)] = v
		}
	case c == '[':
		p.i++
		v := []any{}
		for {
			p.skip()
			if p.i >= len(p.b) {
				return nil, errors.New("unterminated vector")
			}
			if p.b[p.i] == ']' {
				p.i++
				return v, nil
			}
			e, err := p.value()
			if err != nil {
				return nil, err
			}
			v = append(v, e)
		}
	case c == '"':
		p.i++
		var sb strings.Builder
		for p.i < len(p.b) && p.b[p.i] != '"' {
			if p.b[p.i] == '\\' && p.i+1 < len(p.b) {
				p.i++
			}
			sb.WriteRune(p.b[p.i])
			p.i++
		}
		if p.i >= len(p.b) {
			return nil, errors.New("unterminated string")
		}
		p.i++
		return sb.String(), nil
	case c == ':':
		p.i++
		st := p.i
		for p.i < len(p.b) && !strings.ContainsRune(" \n\t\r,[]{}\"", p.b[p.i]) {
			p.i++
		}
		return ednKw(string(p.b[st:p.i])), nil
	case c == '-' || (c >= '0' && c <= '9'):
		st := p.i
		p.i++
		for p.i < len(p.b) && p.b[p.i] >= '0' && p.b[p.i] <= '9' {
			p.i++
		}
		n, err := strconv.ParseInt(string(p.b[st:p.i]), 10, 64)
		if err != nil {
			return nil, fmt.Errorf("bad integer %s", string(p.b[st:p.i]))
		}
		return n, nil
	default:
		return nil, fmt.Errorf("unexpected `%c` at %d", c, p.i)
	}
}

// ------------------------------------------------------------------- PNG
// Lossless, 8 bits, gray (1) or RGB (3), no interlace, filter 0, the zlib
// stream in STORED blocks: no compressor is needed, the bytes are a function of
// the pixels alone (so two hosts write the same file), and any PNG reader opens
// it. The decoder reads what this writes and any stored-block PNG with the five
// filters; a compressed PNG is `png-compressed` for now (a compressed writer is
// a follow-up: a real page is 8.7 MB per gray 300-dpi A4 this way).

func pngChunk(out *bytes.Buffer, kind string, body []byte) {
	var n [4]byte
	n[0], n[1], n[2], n[3] = byte(len(body)>>24), byte(len(body)>>16), byte(len(body)>>8), byte(len(body))
	out.Write(n[:])
	c := append([]byte(kind), body...)
	out.Write(c)
	sum := crc32.ChecksumIEEE(c)
	out.Write([]byte{byte(sum >> 24), byte(sum >> 16), byte(sum >> 8), byte(sum)})
}

// PngEncode writes a bitmap as a lossless PNG.
func PngEncode(b *PlanBitmap) []byte {
	w, h, ch := int(b.W), int(b.H), int(b.Channels)
	raw := make([]byte, 0, h*(1+w*ch))
	for y := 0; y < h; y++ {
		raw = append(raw, 0)
		raw = append(raw, b.Pixels[y*w*ch:(y+1)*w*ch]...)
	}
	z := []byte{0x78, 0x01}
	if len(raw) == 0 {
		z = append(z, 1, 0, 0, 0xFF, 0xFF)
	}
	for i := 0; i < len(raw); {
		n := len(raw) - i
		if n > 65535 {
			n = 65535
		}
		fin := byte(0)
		if i+n == len(raw) {
			fin = 1
		}
		z = append(z, fin, byte(n), byte(n>>8), byte(^uint16(n)), byte(^uint16(n)>>8))
		z = append(z, raw[i:i+n]...)
		i += n
	}
	ad := adler32.Checksum(raw)
	z = append(z, byte(ad>>24), byte(ad>>16), byte(ad>>8), byte(ad))
	var out bytes.Buffer
	out.Write([]byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})
	ct := byte(0)
	if ch == 3 {
		ct = 2
	}
	ihdr := []byte{byte(w >> 24), byte(w >> 16), byte(w >> 8), byte(w), byte(h >> 24), byte(h >> 16), byte(h >> 8), byte(h), 8, ct, 0, 0, 0}
	pngChunk(&out, "IHDR", ihdr)
	pngChunk(&out, "IDAT", z)
	pngChunk(&out, "IEND", nil)
	return out.Bytes()
}

// PngDecode reads a PNG written by PngEncode (or any stored-block gray/RGB PNG).
func PngDecode(p []byte) (*PlanBitmap, error) {
	sig := []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}
	if len(p) < 8 || !bytes.Equal(p[:8], sig) {
		return nil, errors.New("not a PNG")
	}
	var w, h, ch int
	var idat []byte
	i := 8
loop:
	for i+12 <= len(p) {
		n := int(p[i])<<24 | int(p[i+1])<<16 | int(p[i+2])<<8 | int(p[i+3])
		if i+12+n > len(p) {
			return nil, errors.New("a truncated PNG chunk")
		}
		kind := string(p[i+4 : i+8])
		body := p[i+8 : i+8+n]
		crc := uint32(p[i+8+n])<<24 | uint32(p[i+9+n])<<16 | uint32(p[i+10+n])<<8 | uint32(p[i+11+n])
		if crc != crc32.ChecksumIEEE(p[i+4:i+8+n]) {
			return nil, errors.New("a PNG chunk fails its checksum")
		}
		switch kind {
		case "IHDR":
			w = int(body[0])<<24 | int(body[1])<<16 | int(body[2])<<8 | int(body[3])
			h = int(body[4])<<24 | int(body[5])<<16 | int(body[6])<<8 | int(body[7])
			if body[8] != 8 || body[10] != 0 || body[11] != 0 || body[12] != 0 {
				return nil, errors.New("png-unsupported: only 8-bit, unfiltered-method, non-interlaced")
			}
			switch body[9] {
			case 0:
				ch = 1
			case 2:
				ch = 3
			default:
				return nil, errors.New("png-unsupported: only gray and RGB")
			}
		case "IDAT":
			idat = append(idat, body...)
		case "IEND":
			break loop
		}
		i += 12 + n
	}
	if ch == 0 || len(idat) < 6 {
		return nil, errors.New("a PNG without header or data")
	}
	var raw []byte
	j := 2
	for {
		if j+5 > len(idat) {
			return nil, errors.New("a truncated zlib stream")
		}
		hdr := idat[j]
		if hdr&6 != 0 {
			return nil, errors.New("png-compressed: only stored deflate blocks are read")
		}
		n := int(idat[j+1]) | int(idat[j+2])<<8
		if j+5+n > len(idat) {
			return nil, errors.New("a truncated stored block")
		}
		raw = append(raw, idat[j+5:j+5+n]...)
		j += 5 + n
		if hdr&1 == 1 {
			break
		}
	}
	if j+4 > len(idat) || (uint32(idat[j])<<24|uint32(idat[j+1])<<16|uint32(idat[j+2])<<8|uint32(idat[j+3])) != adler32.Checksum(raw) {
		return nil, errors.New("a zlib stream fails its checksum")
	}
	stride := w * ch
	if len(raw) != h*(1+stride) {
		return nil, errors.New("a PNG whose data does not fit its size")
	}
	px := make([]byte, h*stride)
	for y := 0; y < h; y++ {
		f := raw[y*(1+stride)]
		for x := 0; x < stride; x++ {
			v := int(raw[y*(1+stride)+1+x])
			a, b, c := 0, 0, 0
			if x >= ch {
				a = int(px[y*stride+x-ch])
			}
			if y > 0 {
				b = int(px[(y-1)*stride+x])
			}
			if x >= ch && y > 0 {
				c = int(px[(y-1)*stride+x-ch])
			}
			pred := 0
			switch f {
			case 0:
			case 1:
				pred = a
			case 2:
				pred = b
			case 3:
				pred = (a + b) / 2
			case 4:
				pp := a + b - c
				pa, pb, pc := planAbs(pp-a), planAbs(pp-b), planAbs(pp-c)
				if pa <= pb && pa <= pc {
					pred = a
				} else if pb <= pc {
					pred = b
				} else {
					pred = c
				}
			default:
				return nil, errors.New("a PNG row with an unknown filter")
			}
			px[y*stride+x] = byte(v + pred)
		}
	}
	return &PlanBitmap{W: int32(w), H: int32(h), Channels: int32(ch), Pixels: px}, nil
}

func planAbs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}
