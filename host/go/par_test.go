// The parallel host's native tests, Go (DEVLOG P10.2, D505-D508): the twin of the last tests of `host/rust/tests/engine.rs`, test for test.
//
// `Parallel` = N: N workers fulfil a round's requests at once, and the page units AHEAD of the loop's are looked at (dry attempts) so that what they
// will ask is being answered meanwhile. None of it may show in the result: the Model, the transcript (every entry) and the number of times a port is
// called are the serial run's, at every N. A request the look-ahead asked is used by the unit that asks it; a request nobody looked ahead for is
// answered as in a serial run; the limit overrides of the conversion are in force on the workers, and a conversion's workers have ended when it has.
package emon

import (
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"emon/port"
)

type parEntry struct {
	Round    int32
	Req, Ans []int32
}

// entriesOf is the transcript's entries, comparably.
func entriesOf(tr *Transcript) []parEntry {
	var out []parEntry
	for _, e := range tr.Entries {
		out = append(out, parEntry{e.Round, e.Req, e.Ans})
	}
	return out
}

// slowOcr is an OCR engine that takes its time and counts the readings going at once and in all.
type slowOcr struct{ now, peak, calls atomic.Int32 }

func (s *slowOcr) Fulfil(req port.PlanRequest, bm port.PlanBitmaps, keep bool) (port.PlanAnswer, error) {
	n := s.now.Add(1)
	for {
		p := s.peak.Load()
		if n <= p || s.peak.CompareAndSwap(p, n) {
			break
		}
	}
	s.calls.Add(1)
	time.Sleep(60 * time.Millisecond)
	a, err := fakeOcr{}.Fulfil(req, bm, keep)
	s.now.Add(-1)
	return a, err
}

func (*slowOcr) Producer() [][2]string { return [][2]string{{"engine", "fake"}} }

func parEngine(ocr Ocr, r port.Renderer, n int32) *Engine {
	c := DefaultConfig()
	c.Ocr, c.Renderer, c.Parallel = ocr, r, n
	return NewEngine(c)
}

func TestParallelWorkersGiveTheSerialModelAndTranscriptAndOverlapTheOcrOfAMultiPageScan(t *testing.T) {
	tiff := imageFx(t, "tiff-multi3-rgb-lzw.tif")
	o := DefaultOptions()
	one0 := &slowOcr{}
	m0, tr0, err := parEngine(one0, nil, 1).Record(one(BytesInput(tiff)), o)
	j0 := js(must(t, m0, err))
	if one0.peak.Load() != 1 || one0.calls.Load() != 3 {
		t.Fatalf("a serial run reads one page at a time: peak %d calls %d", one0.peak.Load(), one0.calls.Load())
	}
	for _, n := range []int32{2, 3, 4, 8} {
		slow := &slowOcr{}
		m, tr, err := parEngine(slow, nil, n).Record(one(BytesInput(tiff)), o)
		if js(must(t, m, err)) != j0 {
			t.Errorf("parallel=%d: the Model differs from the serial one", n)
		}
		if !reflect.DeepEqual(entriesOf(tr), entriesOf(tr0)) {
			t.Errorf("parallel=%d: the transcript differs from the serial one", n)
		}
		if slow.calls.Load() != 3 {
			t.Errorf("parallel=%d: every page is read once (a look ahead's answer is used, not asked again): %d readings", n, slow.calls.Load())
		}
		if slow.peak.Load() < 2 || slow.peak.Load() > n {
			t.Errorf("parallel=%d: the pages ahead are read while the loop is on one, at most N at once: peak %d", n, slow.peak.Load())
		}
	}
	// limit.workers is the most the option is served with: 1 is serial whatever is asked
	capped := &slowOcr{}
	c := DefaultConfig()
	c.Ocr, c.Parallel, c.Limits = capped, 8, [][2]int32{{16, 1}}
	m, err := NewEngine(c).Convert(one(BytesInput(tiff)), o)
	if js(must(t, m, err)) != j0 || capped.peak.Load() != 1 {
		t.Errorf("limit.workers=1 is serial: peak %d", capped.peak.Load())
	}
}

func TestParallelWorkersWithARendererAndAnOcrPortAreTheSerialRunOverBornDigitalPagesAndAScan(t *testing.T) {
	// under `always` a born-digital page asks its two renders, then the OCR of the page: the dry attempts ask them ahead; the renderer and the OCR
	// engine are called as many times as in a serial run
	o := DefaultOptions()
	docs := map[string][]byte{}
	var names []string
	for _, n := range slice {
		docs[n] = synth(t, n)
		names = append(names, n)
	}
	docs["h-keyword scan"], docs["multi-frame tiff"] = read(t, "test/corpus/synth/scan/h-keyword.pdf"), imageFx(t, "tiff-multi3-rgb-lzw.tif")
	names = append(names, "h-keyword scan", "multi-frame tiff")
	for _, name := range names {
		fake0, ocr0 := &fakeRenderer{}, &slowOcr{}
		m0, tr0, err := parEngine(ocr0, fake0, 1).Record(one(BytesInput(docs[name])), o)
		j0 := js(must(t, m0, err))
		for _, n := range []int32{2, 4, 8} {
			fake, ocr := &fakeRenderer{}, &slowOcr{}
			m, tr, err := parEngine(ocr, fake, n).Record(one(BytesInput(docs[name])), o)
			if js(must(t, m, err)) != j0 {
				t.Errorf("%s at parallel=%d: the Model differs", name, n)
			}
			if !reflect.DeepEqual(entriesOf(tr), entriesOf(tr0)) {
				t.Errorf("%s at parallel=%d: the transcript differs", name, n)
			}
			if fake.calls.Load() != fake0.calls.Load() || ocr.calls.Load() != ocr0.calls.Load() {
				t.Errorf("%s at parallel=%d: the ports are called %d/%d times, not %d/%d", name, n, fake.calls.Load(), ocr.calls.Load(), fake0.calls.Load(), ocr0.calls.Load())
			}
		}
	}
}

// boundCodecs says which pixel bound it was given: the one the workers see.
type boundCodecs struct {
	mu   sync.Mutex
	seen []uint64
}

func (b *boundCodecs) note(max uint64) {
	b.mu.Lock()
	b.seen = append(b.seen, max)
	b.mu.Unlock()
}

func (b *boundCodecs) Jpx(data []byte, max uint64) (*PdfPix, error) {
	b.note(max)
	return nil, ErrPdfUnsupported
}

func (b *boundCodecs) Jbig2(data, globals []byte, max uint64) (*PdfPix, error) {
	b.note(max)
	return nil, ErrPdfUnsupported
}

func TestTheLimitOverridesOfAConversionAreInForceOnItsWorkersAndOnlyThere(t *testing.T) {
	// `limit.pixels` is read where a port decodes (kind 9): on a worker goroutine. The table is process-wide here (D436) and a conversion with
	// overrides holds it exclusively, so a worker, which runs inside that scope, sees it; no other conversion runs while it is held, so none sees it.
	o := DefaultOptions()
	o.Assets = AssetsImages
	doc := read(t, "test/assets/jbig2-globals.pdf")
	for _, n := range []int32{1, 4} {
		codecs := &boundCodecs{}
		c := DefaultConfig()
		c.PdfCodecs, c.Limits, c.Parallel = codecs, [][2]int32{{9, 12345}}, n
		m, err := NewEngine(c).Convert(one(BytesInput(doc)), o)
		must(t, m, err)
		if !reflect.DeepEqual(codecs.seen, []uint64{12345}) {
			t.Errorf("parallel=%d: the codec was given %v, not the conversion's bound", n, codecs.seen)
		}
	}
	// two engines side by side, one bounded, one not, each converting from several goroutines
	tight, free := &boundCodecs{}, &boundCodecs{}
	ct, cf := DefaultConfig(), DefaultConfig()
	ct.PdfCodecs, ct.Limits, ct.Parallel = tight, [][2]int32{{9, 777}}, 4
	cf.PdfCodecs, cf.Parallel = free, 4
	et, ef := NewEngine(ct), NewEngine(cf)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			e := ef
			if i%2 == 0 {
				e = et
			}
			m, err := e.Convert(one(BytesInput(doc)), o)
			if m == nil || err != nil {
				t.Errorf("conversion %d: %v", i, err)
			}
		}(i)
	}
	wg.Wait()
	if !reflect.DeepEqual(tight.seen, []uint64{777, 777, 777, 777}) {
		t.Errorf("the bounded engine's workers saw %v: its bound, every time", tight.seen)
	}
	if !reflect.DeepEqual(free.seen, []uint64{268435456, 268435456, 268435456, 268435456}) {
		t.Errorf("the other's saw %v: the default, never the first's", free.seen)
	}
}

// panicRenderer is a renderer that panics on one page, as a port may.
type panicRenderer struct{ page int32 }

func (p panicRenderer) Layers() uint32  { return 1 }
func (p panicRenderer) ExactCrop() bool { return false }
func (p panicRenderer) Render(_ port.RenderSource, _ []byte, req port.RenderRequest) (*port.Bitmap, error) {
	if req.Page == p.page {
		panic("the renderer broke")
	}
	return port.BitmapFromBytes(2, 2, 3, make([]byte, 12)), nil
}

func TestAPortThatPanicsPanicsTheConversionInAParallelRunAsInASerialOne(t *testing.T) {
	doc := synth(t, "furn-informative")
	for _, n := range []int32{1, 4} {
		func() {
			defer func() {
				if recover() == nil {
					t.Errorf("parallel=%d: the panic did not reach the caller", n)
				}
			}()
			parEngine(nil, panicRenderer{0}, n).Convert(one(BytesInput(doc)), DefaultOptions())
		}()
	}
}
