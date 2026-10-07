// The Renderer port's native tests, Go (DEVLOG P6.2, D127): the twin of the last two tests of
// `host/rust/tests/engine.rs`. A fake engine through RenderFulfiller (answers, crops as cuts, one render for a
// page and its crops, failures and unsupported layers as answers) and through the Engine's Config.
package emon

import (
	"bytes"
	"sync/atomic"
	"testing"

	"emon/port"
)

// fakeRenderer is a 20 x 30 RGB page whose pixel (x, y) is a function of x, y, the layer and the first byte of
// the input it READ through the byte source; layer 3 is unsupported, page 9 is not there. It counts calls.
type fakeRenderer struct{ calls, sawClip atomic.Int32 }

func (f *fakeRenderer) Layers() uint32  { return 1 }
func (f *fakeRenderer) ExactCrop() bool { return false }
func (f *fakeRenderer) Render(in port.RenderSource, _ []byte, req port.RenderRequest) (*port.Bitmap, error) {
	f.calls.Add(1)
	if req.Clip != nil {
		f.sawClip.Add(1)
	}
	if req.Layer == 3 {
		return nil, &port.RenderError{Unsupported: true, Msg: "no watermark layer"}
	}
	if req.Page == 9 {
		return nil, &port.RenderError{Msg: "no such page"}
	}
	first := make([]byte, 1)
	if n, _ := in.ReadAt(first, 0); n != 1 {
		panic("the source read nothing")
	}
	var px []byte
	for y := 0; y < 30; y++ {
		for x := 0; x < 20; x++ {
			v := byte(x*7 + y*3 + int(req.Layer)*50 + int(first[0]))
			px = append(px, v, v^0x55, 255-v)
		}
	}
	return port.BitmapFromBytes(20, 30, 3, px), nil
}

func askRender(t *testing.T, f *port.RenderFulfiller, rec ...int32) port.PlanAnswer {
	t.Helper()
	if err := f.Start(port.PlanRequest{Rec: rec}, nil); err != nil {
		t.Fatal(err)
	}
	a, err := f.Next()
	if err != nil {
		t.Fatal(err)
	}
	return a
}

func eqRec(a, b []int32) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestARenderFulfillerAnswersRendersAndCropsAndFailuresAsRecords(t *testing.T) {
	fake := &fakeRenderer{}
	pdf := synth(t, "h-plain")
	f := port.NewRenderFulfiller(fake, port.RenderInput{Source: NewBytesSource(pdf)})
	// render: [len kind id input page dpi layer]; the answer is [len kind id status w h channels dpi]
	a := askRender(t, f, 7, 1, 1, 0, 0, 72, 0)
	if !eqRec(a.Rec, []int32{8, 1, 1, 0, 20, 30, 3, 72}) {
		t.Fatalf("render answer %v", a.Rec)
	}
	whole := a.Bitmap
	// a crop of the same page: answered with a crop record, the very pixels of the cut, from ONE engine call
	c := askRender(t, f, 11, 5, 2, 0, 0, 72, 0, 4, 5, 8, 9)
	if !eqRec(c.Rec, []int32{8, 5, 2, 0, 8, 9, 3, 72}) {
		t.Fatalf("crop answer %v", c.Rec)
	}
	if fake.calls.Load() != 1 {
		t.Fatalf("a page and its crops are rendered once: %d calls", fake.calls.Load())
	}
	if fake.sawClip.Load() != 0 {
		t.Fatal("an engine without exact crops is never handed a clip")
	}
	cut, err := port.RenderCut(whole, port.RenderClip{X: 4, Y: 5, W: 8, H: 9})
	if err != nil || !bytes.Equal(c.Bitmap.D, cut.D) {
		t.Fatal("a crop is the cut of the whole page")
	}
	if !bytes.Equal(cut.D[:3], whole.D[(5*20+4)*3:(5*20+4)*3+3]) {
		t.Fatal("the cut starts at its corner")
	}
	// another layer is another render, and the input was READ through the source (its first byte is in the pixels)
	l1 := askRender(t, f, 7, 1, 3, 0, 0, 72, 1)
	if fake.calls.Load() != 2 || bytes.Equal(l1.Bitmap.D, whole.D) {
		t.Fatal("another layer is another render")
	}
	if whole.D[0] != pdf[0] {
		t.Fatal("pixel (0,0) of layer 0 is the input's first byte")
	}
	// failures are answers: header and status only, and no bitmap
	fails := []struct {
		want []int32
		ask  []int32
	}{
		{[]int32{4, 1, 4, 2}, []int32{7, 1, 4, 0, 0, 72, 3}},
		{[]int32{4, 1, 5, 1}, []int32{7, 1, 5, 0, 9, 72, 0}},
		{[]int32{4, 5, 6, 1}, []int32{11, 5, 6, 0, 0, 72, 0, 15, 0, 10, 10}},
		{[]int32{4, 5, 7, 2}, []int32{11, 5, 7, 0, 0, 72, 3, 0, 0, 1, 1}},
	}
	for _, c := range fails {
		a := askRender(t, f, c.ask...)
		if !eqRec(a.Rec, c.want) || a.Bitmap != nil {
			t.Fatalf("answer %v, want %v and no bitmap", a.Rec, c.want)
		}
	}
	// a request for an input the run does not have, or a malformed one, is the loop's error and not an answer
	for _, rec := range [][]int32{{7, 1, 8, 4, 0, 72, 0}, {6, 1, 9, 0, 0, 72}} {
		f.Start(port.PlanRequest{Rec: rec}, nil)
		if _, err := f.Next(); err == nil || err.(*port.PlanError).Name != "fulfil-failed" {
			t.Fatalf("request %v: %v", rec, err)
		}
	}
}

func TestAnEngineWithARendererIsTheSameEngineAndABornDigitalPageIsRenderedTwice(t *testing.T) {
	// The Engine's port set (D127): `render` and `crop` go to Config.Renderer. Under `always` (P7.S) a born-digital page asks for its two
	// rasters, so the renderer is called twice a page; with no OCR engine there is nothing to fuse and the Model is byte-for-byte the one of
	// an Engine with no renderer.
	fake := &fakeRenderer{}
	cfg := DefaultConfig()
	cfg.Renderer = fake
	with, without := NewEngine(cfg), NewEngine(DefaultConfig())
	for _, name := range slice {
		b := synth(t, name)
		a, err := with.Convert(one(BytesInput(b)), DefaultOptions())
		ma := must(t, a, err)
		c, err := without.Convert(one(BytesInput(b)), DefaultOptions())
		mc := must(t, c, err)
		if js(ma) != js(mc) {
			t.Fatalf("%s: a renderer changed the Model", name)
		}
	}
	if n := fake.calls.Load(); n == 0 || n%2 != 0 {
		t.Fatalf("the renderer was called %d times: two rasters a born-digital page", n)
	}
}
