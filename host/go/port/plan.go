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
// The loop's shape is the JavaScript host's too: the host owns the await loop (`emon/planrun`, over kin's `planloop`).
// `Start` begins a request (a sync host finishes it there), `Next` returns the
// next completion in whatever order completions arrive.

package port

import (
	"bytes"
	"compress/zlib"
	"errors"
	"fmt"
	"hash/crc32"
	"io"
	"strconv"
	"strings"
	"time"
)

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

// PlanError is a named failure: `max-rounds`, `fetch-rounds` (limits),
// `transcript-miss`, `transcript-bad`, `fulfil-failed`, `unit-failed`,
// `bad-record`, `need-bytes-stalled`.
type PlanError struct {
	Name string
	Msg  string
	// Limited is true for the two named limits, whose bound and the number reached are kin's (`err-limit`, `err-actual`), not words to parse.
	Limited      bool
	Limit, Actual int32
}

func (e *PlanError) Error() string { return e.Name + ": " + e.Msg }

func planErr(name string, format string, a ...any) *PlanError {
	return &PlanError{Name: name, Msg: fmt.Sprintf(format, a...)}
}

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
// `port`: this file keeps the fulfillers, the requests and answers, and the transcript.

// ---------------------------------------------------------------- transcript

// PlanEntry is one recorded request/answer pair.
type PlanEntry struct {
	Round  int32
	Req    []int32
	Ans    []int32
	Bitmap *Bitmap
}

// Transcript is the complete set of answers of one run. Inputs are (index,
// length) only: never bytes, origin, fetch count or password (SPEC 13.1).
type Transcript struct {
	Inputs  [][2]int32
	Entries []PlanEntry
	// Producer is WHO ANSWERED (D214): the live ports' identities as name/value pairs (an OCR engine and its version, the
	// models it read with), written as `:producer {...}` when there are any and never read back for a replay: a replay
	// depends on the answers, not on whose engine gave them. Empty leaves the file as it always was.
	Producer [][2]string
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
	case PlanKindCrop:
		return "crop"
	case PlanKindDecodeImg:
		return "decode-img"
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
	case "crop":
		return PlanKindCrop, true
	case "decode-img":
		return PlanKindDecodeImg, true
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
	sb.WriteString("{:emon-transcript 1\n")
	if len(t.Producer) > 0 {
		sb.WriteString(" :producer {")
		for i, kv := range t.Producer {
			if i > 0 {
				sb.WriteString(" ")
			}
			fmt.Fprintf(&sb, ":%s \"%s\"", kv[0], strings.NewReplacer("\\", "/", "\"", "'").Replace(kv[1]))
		}
		sb.WriteString("}\n")
	}
	sb.WriteString(" :inputs [")
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
	if _, ok := m["producer"].(map[string]any); ok {
		t.Producer = planProducerInOrder(string(raw))
	}
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

// planProducerInOrder reads the `:producer {:name "value" ...}` map in the order the file has it (the EDN reader's maps are
// unordered, and a transcript read and written again must be the same bytes).
func planProducerInOrder(s string) [][2]string {
	i := strings.Index(s, ":producer {")
	if i < 0 {
		return nil
	}
	s = s[i+len(":producer {"):]
	var out [][2]string
	for {
		s = strings.TrimLeft(s, " \n\t,")
		if s == "" || s[0] == '}' || s[0] != ':' {
			return out
		}
		j := strings.IndexAny(s, " \n\t")
		if j < 0 {
			return out
		}
		key := s[1:j]
		s = strings.TrimLeft(s[j:], " \n\t")
		if s == "" || s[0] != '"' {
			return out
		}
		k := strings.Index(s[1:], "\"")
		if k < 0 {
			return out
		}
		out = append(out, [2]string{key, s[1 : 1+k]})
		s = s[1+k+1:]
	}
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
// Writing: emon's own canonical PNG encoder (`emon.png`, D124, SPEC 10.11),
// compressed and byte-identical on all four targets, so a recording is a
// function of the pixels and every host writes the same file (D125 revises
// D090). The encoder is kin, and kin's generated packages import this one, so
// this package cannot import it back: package `emon/pngenc` registers it here
// (`PngEncoder`), and a program that writes transcripts links it in.
// Reading: any valid non-interlaced PNG (gray, gray+alpha, RGB, RGBA, palette,
// depths 1 to 16), whoever wrote it; the alpha channel is dropped and a palette
// is expanded, so the bitmap is gray (1) or RGB (3).

// PngEncoder is set by package pngenc.
var PngEncoder func(*Bitmap) []byte

// PngEncode writes a bitmap as a canonical, compressed, lossless PNG.
func PngEncode(b *Bitmap) []byte {
	if PngEncoder == nil {
		panic("port.PngEncoder is not set: import _ \"emon/pngenc\"")
	}
	return PngEncoder(b)
}

// PngDecode reads any non-interlaced PNG into a gray or RGB bitmap.
func PngDecode(p []byte) (*Bitmap, error) {
	sig := []byte{0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}
	if len(p) < 8 || !bytes.Equal(p[:8], sig) {
		return nil, errors.New("not a PNG")
	}
	var w, h, depth int
	var ct byte = 255
	haveIhdr := false
	var plte, idat []byte
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
			if n < 13 {
				return nil, errors.New("a truncated PNG header")
			}
			w = int(body[0])<<24 | int(body[1])<<16 | int(body[2])<<8 | int(body[3])
			h = int(body[4])<<24 | int(body[5])<<16 | int(body[6])<<8 | int(body[7])
			depth = int(body[8])
			ct = body[9]
			if body[10] != 0 || body[11] != 0 || body[12] != 0 {
				return nil, errors.New("png-unsupported: only filter method 0 and no interlace")
			}
			haveIhdr = true
		case "PLTE":
			plte = append([]byte(nil), body...)
		case "IDAT":
			idat = append(idat, body...)
		case "IEND":
			break loop
		}
		i += 12 + n
	}
	if !haveIhdr || len(idat) == 0 {
		return nil, errors.New("a PNG without header or data")
	}
	var chans int
	switch ct {
	case 0, 3:
		chans = 1
	case 2:
		chans = 3
	case 4:
		chans = 2
	case 6:
		chans = 4
	default:
		return nil, errors.New("png-unsupported: colour type")
	}
	okDepth := false
	switch ct {
	case 0:
		okDepth = depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16
	case 3:
		okDepth = depth == 1 || depth == 2 || depth == 4 || depth == 8
	default:
		okDepth = depth == 8 || depth == 16
	}
	if !okDepth {
		return nil, errors.New("png-unsupported: bit depth")
	}
	zr, err := zlib.NewReader(bytes.NewReader(idat))
	if err != nil {
		return nil, errors.New("a bad zlib header")
	}
	raw, err := io.ReadAll(zr)
	if err != nil {
		return nil, errors.New("a bad zlib stream: " + err.Error())
	}
	bits := chans * depth
	rowb := (w*bits + 7) / 8
	bpp := bits / 8
	if bpp < 1 {
		bpp = 1
	}
	if len(raw) != h*(1+rowb) {
		return nil, errors.New("a PNG whose data does not fit its size")
	}
	sl := make([]byte, h*rowb)
	for y := 0; y < h; y++ {
		f := raw[y*(1+rowb)]
		for x := 0; x < rowb; x++ {
			v := int(raw[y*(1+rowb)+1+x])
			a, b, c := 0, 0, 0
			if x >= bpp {
				a = int(sl[y*rowb+x-bpp])
			}
			if y > 0 {
				b = int(sl[(y-1)*rowb+x])
			}
			if x >= bpp && y > 0 {
				c = int(sl[(y-1)*rowb+x-bpp])
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
			sl[y*rowb+x] = byte(v + pred)
		}
	}
	// sample k of row y as 0..255 (sub-byte scaled up, 16-bit high byte)
	sample := func(y, k int) int {
		r := sl[y*rowb : (y+1)*rowb]
		switch depth {
		case 8:
			return int(r[k])
		case 16:
			return int(r[2*k])
		}
		per := 8 / depth
		v := (int(r[k/per]) >> (8 - depth*(k%per+1))) & ((1 << depth) - 1)
		if ct == 3 {
			return v
		}
		return v * 255 / ((1 << depth) - 1)
	}
	outCh := 3
	if ct == 0 || ct == 4 {
		outCh = 1
	}
	px := make([]byte, 0, w*h*outCh)
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			switch ct {
			case 0:
				px = append(px, byte(sample(y, x)))
			case 4:
				px = append(px, byte(sample(y, 2*x)))
			case 2, 6:
				for c := 0; c < 3; c++ {
					px = append(px, byte(sample(y, chans*x+c)))
				}
			default:
				idx := sample(y, x)
				if idx*3+3 > len(plte) {
					return nil, errors.New("a PNG pixel beyond its palette")
				}
				px = append(px, plte[idx*3:idx*3+3]...)
			}
		}
	}
	return BitmapFromBytes(int32(w), int32(h), int32(outCh), px), nil
}

func planAbs(x int) int {
	if x < 0 {
		return -x
	}
	return x
}
