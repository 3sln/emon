// The engine registry's laws, Go host (DEVLOG D925): registration held to kin's table, routing by the engine a request names, an engine nobody registered, an adapter that
// cannot read leaves the page unread (never blank), and an answer is held to what the adapter registered. In-process adapters; the library loader is exercised live.
package emon

import (
	"strings"
	"testing"

	"emon/plan"
	"emon/port"
)

type stubAdapter struct {
	info    AdapterInfo
	reading Reading
	closed  bool
	reads   int
}

func (s *stubAdapter) Info() AdapterInfo { return s.info }
func (s *stubAdapter) Read(req port.PlanRequest, bm port.PlanBitmaps, keep bool) (Reading, error) {
	s.reads++
	return s.reading, nil
}
func (s *stubAdapter) Close() { s.closed = true }

func tessInfo() AdapterInfo {
	return AdapterInfo{Name: "tesseract", Engine: 1, Caps: 0, Flags: AdapterThreadsafe, Producer: [][2]string{{"engine", "tesseract"}}}
}

func ocrRequest(id int32) (port.PlanRequest, port.PlanBitmaps) {
	c := port.NewCells()
	at := plan.RqOcr(c, id, 1, 3, 0, 0, 0, 0, 0)
	plan.RqOcrEnd(c, at)
	bm := port.PlanBitmaps{1: port.BitmapFromBytes(4, 4, 1, make([]byte, 16))}
	return port.PlanRequest{Rec: cellsVec(c)}, bm
}

func TestEnginesRegistrationIsKinsRule(t *testing.T) {
	e := NewEngines()
	bad := &stubAdapter{info: AdapterInfo{Name: "nobody", Engine: 99}}
	err := e.Add(bad)
	if err == nil || !strings.HasPrefix(err.Error(), "adapter-engine-unknown") || !bad.closed {
		t.Fatalf("an engine the table does not know: %v (closed %v)", err, bad.closed)
	}
	beyond := &stubAdapter{info: AdapterInfo{Name: "t", Engine: 1, Caps: 0xffffffff}}
	if err := e.Add(beyond); err == nil || !strings.HasPrefix(err.Error(), "adapter-caps-beyond-engine") {
		t.Fatalf("caps beyond the ceiling: %v", err)
	}
	async := &stubAdapter{info: AdapterInfo{Name: "t", Engine: 1, Flags: AdapterAsync}}
	if err := e.Add(async); err == nil || !strings.HasPrefix(err.Error(), "adapter-abi-unsupported") {
		t.Fatalf("an async adapter: %v", err)
	}
	remote := &stubAdapter{info: AdapterInfo{Name: "t", Engine: 1, Flags: AdapterRemote}}
	if err := e.Add(remote); err == nil || !strings.HasPrefix(err.Error(), "adapter-remote-refused") {
		t.Fatalf("a remote adapter without AllowRemote: %v", err)
	}
	if len(e.Describe()) != 0 {
		t.Fatalf("a refused adapter registered")
	}
	good := &stubAdapter{info: tessInfo()}
	if err := e.Add(good); err != nil {
		t.Fatal(err)
	}
	if got := e.Producer(); len(got) != 1 || got[0] != [2]string{"engine", "tesseract"} {
		t.Fatalf("producer %v", got)
	}
	e.Close()
	if !good.closed {
		t.Fatal("Close does not close adapters")
	}
}

func TestEnginesRouteByRequestEngine(t *testing.T) {
	req, bm := ocrRequest(7)
	// nobody registered: unsupported, a four-cell refusal
	a, err := NewEngines().Fulfil(req, bm, false)
	if err != nil || len(a.Rec) != 4 || a.Rec[2] != 7 || a.Rec[3] != plan.PsUnsupported() {
		t.Fatalf("no adapter: %v %v", a.Rec, err)
	}
	// a PP-OCR adapter only: a request that names Tesseract (no trailer) is not its
	pp := &stubAdapter{info: AdapterInfo{Name: "ppocr", Engine: 2}}
	e := NewEngines()
	if err := e.Add(pp); err != nil {
		t.Fatal(err)
	}
	a, _ = e.Fulfil(req, bm, false)
	if pp.reads != 0 || a.Rec[3] != plan.PsUnsupported() {
		t.Fatalf("routed to the wrong engine: reads %d rec %v", pp.reads, a.Rec)
	}
}

func TestEnginesUnreadIsNeverBlank(t *testing.T) {
	req, bm := ocrRequest(3)
	for _, tc := range []struct {
		status int32
		want   int32
	}{{AdapterUnsupported, plan.PsUnsupported()}, {AdapterFailed, plan.PsFailed()}} {
		s := &stubAdapter{info: tessInfo(), reading: Reading{Status: tc.status, Reason: "pack-missing:tess-fast-deu"}}
		e := NewEngines()
		if err := e.Add(s); err != nil {
			t.Fatal(err)
		}
		a, err := e.Fulfil(req, bm, false)
		if err != nil || len(a.Rec) != 4 || a.Rec[3] != tc.want {
			t.Fatalf("status %d: %v %v", tc.status, a.Rec, err)
		}
		if n := e.Notes(); len(n) != 1 || !strings.Contains(n[0], "pack-missing:tess-fast-deu") {
			t.Fatalf("notes %v", n)
		}
	}
}

func TestEnginesAnswerHeldToWhatWasRegistered(t *testing.T) {
	req, bm := ocrRequest(3)
	s := &stubAdapter{info: tessInfo(), reading: Reading{Rec: []int32{8, plan.PkOcr(), 4, 0, 0, 0, 0, 0}}} // another request's id
	e := NewEngines()
	if err := e.Add(s); err != nil {
		t.Fatal(err)
	}
	_, err := e.Fulfil(req, bm, false)
	if err == nil || !strings.HasPrefix(err.Error(), "ocr-adapter-invalid: tesseract") {
		t.Fatalf("an answer to another request: %v", err)
	}
	s.reading = Reading{Rec: []int32{8, plan.PkOcr(), 3, 0, 0, 0, 0, 0}} // not valid OIL
	if _, err = e.Fulfil(req, bm, false); err == nil || !strings.HasPrefix(err.Error(), "ocr-adapter-invalid") {
		t.Fatalf("an invalid reading: %v", err)
	}
}

func TestEnginesDefaultsSkipTesseractWhenUnused(t *testing.T) {
	// an adapter directory with no libraries registers nothing, for any selection
	dir := t.TempDir()
	e := NewEngines()
	e.SetAdapterDir(dir)
	if err := e.Defaults(Options{OcrEngine: 8}); err != nil || len(e.Describe()) != 0 {
		t.Fatalf("empty dir: %v %v", err, e.Describe())
	}
	if err := e.Defaults(Options{}); err != nil {
		t.Fatal(err)
	}
	if err := e.Defaults(Options{}); err != nil && !strings.Contains(err.Error(), "adapter") {
		t.Fatal(err)
	}
	missing := NewEngines()
	missing.SetAdapterDir(dir + "/nope")
	if err := missing.Defaults(Options{}); err == nil {
		t.Fatal("a directory you name that is wrong is an error")
	}
}
