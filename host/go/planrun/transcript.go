// The Go half of the transcript (P10.9; D396, D438; DEVLOG P2.5, SPEC 13, D058 revised). Its Rust twin is the end of `host/rust/src/plan.rs`.
//
// THE FILE IS KIN'S (`kin/transcript.kin`): the format (version 1, byte for byte), the kind names, the escaping of the producer, the reader and
// its refusals, the map a replay is answered from and the count of entries it never asked for. This file keeps what is not observable: the
// bitmaps (a PNG each; kin's `emon.pngread` reads and writes them, `pngenc` makes the buffers, P10.11, D489), the in-memory `Transcript` the embedder holds and the
// `Replayer` that answers the loop from kin's map.
//
// It is here, in `planrun`, and no longer in `port`, for `planrun`'s reason (D405): the generated `transcript` imports `port`, so `port` cannot import
// it back. With it went `port`'s own literals of `transcript-bad` and `transcript-miss`: a name is kin's (`planloop.Pe*`).
//
// Nothing here touches a filesystem (D058): a transcript is named files in memory (`File`, `TranscriptFromFiles`) and the driver moves them to a
// directory.
package planrun

import (
	"emon/planloop"
	"emon/pngenc"
	"emon/port"
	"emon/transcript"
	"emon/transcriptctx"
	"strings"
)

// Transcript is the complete set of answers of one run. Inputs are (index, length) only: never bytes, origin, fetch count or password (SPEC 13.1).
type Transcript struct {
	Inputs  [][2]int32
	Entries []port.PlanEntry
	// Producer is WHO ANSWERED (D214): the live ports' identities as name/value pairs (an OCR engine and its version, the models it read with),
	// written as `:producer {...}` when there are any and never read back for a replay: a replay depends on the answers, not on whose engine gave
	// them. Empty leaves the file as it always was.
	Producer [][2]string
}

// TranscriptFile is the name of the records file.
const TranscriptFile = "transcript.edn"

// File is one named file of a transcript.
type File struct {
	Name  string
	Bytes []byte
}

// peName is a name of kin's `pe`.
func peName(code int32) string {
	var b strings.Builder
	planloop.PeEmitName(&b, code)
	return b.String()
}

// Bad is a `transcript-bad` error with this sentence.
func Bad(msg string) *port.PlanError {
	return &port.PlanError{Name: peName(planloop.PeTranscriptBad()), Msg: msg}
}

// FulfilFailed is a `fulfil-failed` error with this sentence: a port could not run (distinct from an answer that says `failed`).
func FulfilFailed(msg string) *port.PlanError {
	return &port.PlanError{Name: peName(planloop.PeFulfilFailed()), Msg: msg}
}

// named is an error a fulfiller returned, as the loop reports it: a `*port.FulfilError` (a port in package `port`, which cannot import kin's names)
// becomes `fulfil-failed`; any other error is already named.
func named(err error) error {
	if fe, ok := err.(*port.FulfilError); ok {
		return FulfilFailed(fe.Msg)
	}
	return err
}

func tbName(code int32) string {
	var b strings.Builder
	transcript.TbEmitName(&b, code)
	return b.String()
}

// cps is a string as code points: the form kin's names and values are in.
func cps(s string) *port.Cells {
	c := port.NewCells()
	for _, r := range s {
		port.CellsPush(c, int32(r))
	}
	return c
}

func cellsOf(v []int32) *port.Cells {
	c := port.NewCells()
	for _, x := range v {
		port.CellsPush(c, x)
	}
	return c
}

func inputsKin(inputs [][2]int32) *transcriptctx.Tr {
	tr := transcriptctx.NewTr()
	for _, in := range inputs {
		transcript.TrAddInput(tr, in[0], in[1])
	}
	return tr
}

// kin is the transcript as kin holds it (`Tr`), the bitmaps left behind: the entry at a place has one when its flag says.
func (t *Transcript) kin() *transcriptctx.Tr {
	tr := inputsKin(t.Inputs)
	for _, kv := range t.Producer {
		transcript.TrAddProducer(tr, cps(kv[0]), cps(kv[1]))
	}
	for _, e := range t.Entries {
		c := cellsOf(e.Req)
		for _, x := range e.Ans {
			port.CellsPush(c, x)
		}
		bm := int32(0)
		if e.Bitmap != nil {
			bm = 1
		}
		transcript.TrAddEntry(tr, e.Round, c, 0, int32(len(e.Req)), int32(len(e.Req)), int32(len(e.Ans)), bm)
	}
	return tr
}

// ToFiles writes the transcript as files in a fixed order: transcript.edn, then one lossless PNG per bitmap named by its entry's index.
// DETERMINISTIC: the same entries always give the same bytes (and the same bytes as the Rust host).
func (t *Transcript) ToFiles() []File {
	var sb strings.Builder
	transcript.TrWrite(t.kin(), &sb)
	files := []File{{TranscriptFile, []byte(sb.String())}}
	for n, e := range t.Entries {
		if e.Bitmap != nil {
			var name strings.Builder
			transcript.TrBitmapName(int32(n), &name)
			files = append(files, File{name.String(), pngenc.Encode(e.Bitmap)})
		}
	}
	return files
}

// TranscriptFromFiles reads what ToFiles wrote.
func TranscriptFromFiles(files map[string][]byte) (*Transcript, error) {
	raw, ok := files[TranscriptFile]
	if !ok {
		return nil, Bad(tbName(transcript.TbNoFile()))
	}
	tr := transcriptctx.NewTr()
	if transcript.TrRead(tr, raw) != 0 {
		return nil, Bad(tr.Msg.String())
	}
	t := &Transcript{}
	for i := int32(0); i < transcript.TrNInputs(tr); i++ {
		t.Inputs = append(t.Inputs, [2]int32{transcript.TrInIndex(tr, i), transcript.TrInLength(tr, i)})
	}
	for p := int32(0); transcript.TrPMore(tr, p) == 1; p = transcript.TrPNext(tr, p) {
		var k, v strings.Builder
		transcript.TrPKey(tr, p, &k)
		transcript.TrPVal(tr, p, &v)
		t.Producer = append(t.Producer, [2]string{k.String(), v.String()})
	}
	for e := int32(0); transcript.TrEMore(tr, e) == 1; e = transcript.TrENext(tr, e) {
		rq, rl := transcript.TrEReq(tr, e), transcript.TrEReqLen(tr, e)
		an, al := transcript.TrEAns(tr, e), transcript.TrEAnsLen(tr, e)
		ent := port.PlanEntry{Round: transcript.TrERound(tr, e), Req: vec(tr.Ents, rq, rq+rl), Ans: vec(tr.Ents, an, an+al)}
		var name strings.Builder
		if transcript.TrEBitmapName(tr, e, &name) == 1 {
			png, ok := files[name.String()]
			if !ok {
				return nil, Bad(name.String() + " " + tbName(transcript.TbBitmapMissing()))
			}
			b, err := pngenc.Decode(png)
			if err != nil {
				return nil, Bad(err.Error())
			}
			ent.Bitmap = b
		}
		t.Entries = append(t.Entries, ent)
	}
	return t, nil
}

// CheckInputs: replay must be given the inputs the recording saw.
func (t *Transcript) CheckInputs(inputs [][2]int32) error {
	want := port.NewCells()
	for _, in := range inputs {
		port.CellsPush(want, in[0])
		port.CellsPush(want, in[1])
	}
	var sb strings.Builder
	if transcript.TrCheckInputs(inputsKin(t.Inputs), want, &sb) != 0 {
		return Bad(sb.String())
	}
	return nil
}

// Replayer answers from a transcript: a request it has no entry for is `transcript-miss`, never a guess. `bytes` is not here and cannot be. The map
// it answers from is kin's (`tr-index`, `tr-take`): keyed by the whole request record.
type Replayer struct {
	tr      *transcriptctx.Tr
	bitmaps []*port.Bitmap
	key     *port.Cells
	done    []replayDone
}

type replayDone struct {
	a   port.PlanAnswer
	err error
}

// NewReplayer indexes a transcript.
func NewReplayer(t *Transcript) *Replayer {
	r := &Replayer{tr: t.kin(), key: port.NewCells()}
	transcript.TrIndex(r.tr)
	for _, e := range t.Entries {
		r.bitmaps = append(r.bitmaps, e.Bitmap)
	}
	return r
}

// Unused is how many recorded entries the run never asked for.
func (r *Replayer) Unused() int { return int(transcript.TrUnused(r.tr)) }

// Done is the end of the run: a `transcript-bad` error, with kin's sentence, when it left recorded entries unasked (a transcript of another run).
func (r *Replayer) Done() error {
	if transcript.TrUnused(r.tr) == 0 {
		return nil
	}
	var sb strings.Builder
	transcript.TrUnusedMsg(r.tr, &sb)
	return Bad(sb.String())
}

func (r *Replayer) Start(req port.PlanRequest, _ port.PlanBitmaps) error {
	port.CellsDrop(r.key, 0)
	for _, v := range req.Rec {
		port.CellsPush(r.key, v)
	}
	if e := transcript.TrTake(r.tr, r.key, 0, int32(len(req.Rec))); e >= 0 {
		an := transcript.TrEAns(r.tr, e)
		r.done = append(r.done, replayDone{a: port.PlanAnswer{Rec: vec(r.tr.Ents, an, an+transcript.TrEAnsLen(r.tr, e)), Bitmap: r.bitmaps[transcript.TrEN(r.tr, e)]}})
	} else {
		var sb strings.Builder
		transcript.TrMissMsg(r.key, 0, int32(len(req.Rec)), &sb)
		r.done = append(r.done, replayDone{err: &port.PlanError{Name: peName(planloop.PeTranscriptMiss()), Msg: sb.String()}})
	}
	return nil
}

func (r *Replayer) Next() (port.PlanAnswer, error) {
	d := r.done[0]
	r.done = r.done[1:]
	return d.a, d.err
}
