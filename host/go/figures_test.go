// The level `figures`'s native test, Go (DEVLOG P8.5, D351): the twin of `at_figures_a_vector_figure_is_a_crop_of_the_page_and_replays_
// without_a_renderer` in `host/rust/tests/engine.rs`. A Letter page's renderer whose pixels are a function of (x, y); a vector figure
// is a crop of it, the pixels of its padded rectangle; the replay needs no renderer.
package emon

import (
	"bytes"
	"strings"
	"sync/atomic"
	"testing"

	"emon/pngenc"
	"emon/port"
)

type letterPage struct{ calls atomic.Int32 }

func (f *letterPage) Layers() uint32  { return 1 }
func (f *letterPage) ExactCrop() bool { return false }

func letterPx(x, y uint32) [3]byte { return [3]byte{byte(x*7 + y*13), byte(x ^ y), byte((x + y) * 3)} }

func (f *letterPage) Render(_ port.RenderSource, _ []byte, req port.RenderRequest) (*port.Bitmap, error) {
	f.calls.Add(1)
	if req.Layer != 0 {
		panic("a figure is rendered with its text")
	}
	d := uint32(req.Dpi)
	w, h := (1224*d+72)/144, (1584*d+72)/144
	px := make([]byte, 0, int(w)*int(h)*3)
	for y := uint32(0); y < h; y++ {
		for x := uint32(0); x < w; x++ {
			p := letterPx(x, y)
			px = append(px, p[0], p[1], p[2])
		}
	}
	return port.BitmapFromBytes(int32(w), int32(h), 3, px), nil
}

func TestAtFiguresAVectorFigureIsACropOfThePageAndReplaysWithoutARenderer(t *testing.T) {
	fake := &letterPage{}
	cfg := DefaultConfig()
	cfg.Renderer = fake
	live := NewEngine(cfg)
	// OcrNever: under `always` a born-digital page is itself rendered for OCR since P7.S, and this test counts the renders a figure asks
	o := DefaultOptions()
	o.Ocr = OcrNever
	o.Assets = AssetsFigures
	pdf := read(t, "test/assets/fig/fig-vector-chart.pdf")
	m, tr, err := live.Record(one(BytesInput(pdf)), o)
	must(t, m, err)
	a := m.Assets()
	if len(a) != 2 {
		t.Fatalf("%d assets, want the chart and the diagram", len(a))
	}
	if fake.calls.Load() != 1 {
		t.Errorf("the renderer was called %d times: the page is rendered once for its two crops", fake.calls.Load())
	}
	for _, x := range a {
		d, ok := x.DPI()
		if x.Origin() != "rendered" || x.From() != "render" || !ok || d != 150 || x.MediaType() != "image/png" {
			t.Errorf("asset %d: %s %s %d %s", x.ID(), x.Origin(), x.From(), d, x.MediaType())
		}
	}
	// every crop is the rectangle of the page its figure's region says: padded by 2 pt, floor and ceiling, held to the page
	for _, x := range a {
		r := x.Uses()[0].Region
		px0, py0 := uint32(max(r.X0-200, 0))*150/7200, uint32(max(r.Y0-200, 0))*150/7200
		px1 := (uint32(min(r.X1+200, 61200))*150 + 7199) / 7200
		py1 := (uint32(min(r.Y1+200, 79200))*150 + 7199) / 7200
		var want []byte
		for y := py0; y < py1; y++ {
			for xx := px0; xx < px1; xx++ {
				p := letterPx(xx, y)
				want = append(want, p[0], p[1], p[2])
			}
		}
		got, err := pngenc.Decode(x.Bytes())
		if err != nil {
			t.Fatal(err)
		}
		if uint32(x.Width()) != px1-px0 || uint32(x.Height()) != py1-py0 {
			t.Errorf("asset %d is %d x %d, want %d x %d", x.ID(), x.Width(), x.Height(), px1-px0, py1-py0)
		}
		if !bytes.Equal(got.D, want) {
			t.Errorf("asset %d is not the pixels of its rectangle", x.ID())
		}
	}
	// the replay needs no renderer and gives the same JSON, HTML in three modes and asset files
	r, err := NewEngine(DefaultConfig()).Replay(one(BytesInput(pdf)), o, tr)
	must(t, r, err)
	if js(r) != js(m) {
		t.Error("the replay's JSON differs")
	}
	fr, fm := r.AssetFiles(), m.AssetFiles()
	if len(fr) != len(fm) {
		t.Fatalf("%d files vs %d", len(fr), len(fm))
	}
	for i := range fr {
		if fr[i].Name != fm[i].Name || !bytes.Equal(fr[i].Bytes, fm[i].Bytes) {
			t.Errorf("file %d differs", i)
		}
	}
	for _, h := range []HTMLOptions{{Assets: HTMLAssetsInline}, {Assets: HTMLAssetsExternal, AssetPrefix: "img/"}, {Assets: HTMLAssetsOmit}} {
		if r.ToHTML(h) != m.ToHTML(h) {
			t.Errorf("the replay's HTML differs (mode %d)", h.Assets)
		}
	}
	// no renderer: the crops are answered `unsupported` and the figures say `render`
	none, err := NewEngine(DefaultConfig()).Convert(one(BytesInput(pdf)), o)
	must(t, none, err)
	figure, _ := KindNamed("figure")
	said := false
	for _, f := range none.Find(figure) {
		if r, ok := f.NoAsset(); ok && r == "render" {
			said = true
		}
	}
	if len(none.Assets()) != 0 || !said {
		t.Error("with no renderer a figure says `render`")
	}
	// the default run is what it was, with a renderer or without: no assets, and nothing is rendered
	dflt := DefaultOptions()
	dflt.Ocr = OcrNever
	off, err := live.Convert(one(BytesInput(pdf)), dflt)
	must(t, off, err)
	plain, err := NewEngine(DefaultConfig()).Convert(one(BytesInput(pdf)), dflt)
	must(t, plain, err)
	if js(off) != js(plain) || strings.Contains(js(off), "\"assets\"") || fake.calls.Load() != 1 {
		t.Error("the default run is not what it was")
	}
}
