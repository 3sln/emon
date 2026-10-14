// The `Renderer` port, Go host (DEVLOG P6.2, D127; SPEC 7.2, D007). The twin of `host/rust/src/render.rs`:
// read that for the reasoning. A page of an input, at a resolution, in a layer, optionally cut to a pixel
// rectangle, as an RGB Bitmap.
//
// This file is the PORT: the interface an engine implements, the request it is given, and the fulfiller that
// turns `render` and `crop` plan requests (D089, D117) into calls and answers. It names no engine and no
// filesystem. `host/go/pdfium` (cgo, only to `dlopen` the library at run time: D539) is the default engine (D007).
//
// The input is the host's byte source, named in the request by input INDEX (D089, D058): never a path and
// never a copy kin made. The geometry is `PixelsOf`: the page as displayed is wpt x hpt points, the whole-page
// bitmap is floor(wpt*dpi/72 + 1/2) wide, and a crop is a rectangle of THAT bitmap.
package port

import (
	"fmt"
	"io"
	"math"
	"sync"
)

// `emon.plan`'s layers (`lay-*`); a kin law holds the numbers.
const (
	LayerFull          int32 = 0
	LayerNoText        int32 = 1
	LayerTextOnly      int32 = 2
	LayerNoWatermark   int32 = 3
	LayerWatermarkOnly int32 = 4

	RenderStatusUnsupported int32 = 2
)

// RenderClip is a pixel rectangle of the whole-page bitmap.
type RenderClip struct{ X, Y, W, H int32 }

// RenderRequest is one page of one input, as the Renderer is asked for it.
type RenderRequest struct {
	Page  int32 // 0-based
	Dpi   int32
	Layer int32
	Clip  *RenderClip // nil: the whole page
}

// RenderSource is the host's random-access byte source (the engine's ByteSource, structurally).
type RenderSource interface {
	io.ReaderAt
	Size() int64
}

// RenderError says how a render did not happen. Unsupported is `ps-unsupported` (an engine that cannot
// separate a layer says so and kin plans around it); anything else is `ps-failed`.
type RenderError struct {
	Unsupported bool
	Msg         string
}

func (e *RenderError) Error() string {
	if e.Unsupported {
		return "render unsupported: " + e.Msg
	}
	return "render failed: " + e.Msg
}

// Renderer is the port. It must be callable from any goroutine; an engine that is not thread-safe, as pdfium
// is not, serialises inside.
type Renderer interface {
	// Render renders req of the document `input` (opened with `password` when it is encrypted). The result is
	// RGB (3 channels): the tracer wants colour for ink separation (SPEC 8.2.1) and OCR takes it as it is.
	Render(input RenderSource, password []byte, req RenderRequest) (*Bitmap, error)
	// Layers is the set of layers it can separate, as a bit set over Layer* (bit n = layer n).
	Layers() uint32
	// ExactCrop: is Render with a Clip EXACTLY the cut of the same render without one (SPEC 7.2: "the same
	// pixels, cut out, never a re-fit")? pdfium is not: a clipped render moves the pixels on the clip's edge
	// (measured, D127). When false the fulfiller never passes a Clip: it renders the whole page and cuts.
	ExactCrop() bool
}

// PixelsOf is the size in pixels of `pt` points at `dpi`: floor(pt*dpi/72 + 1/2), at least 1.
func PixelsOf(pt float64, dpi int32) int32 {
	v := math.Floor(pt*float64(dpi)/72.0 + 0.5)
	if v < 1 {
		return 1
	}
	if v > math.MaxInt32 {
		return math.MaxInt32
	}
	return int32(v)
}

// RenderInput is one input of the run, as the fulfiller reads it.
type RenderInput struct {
	Source   RenderSource
	Password []byte
}

// ParseRender reads a `render` (args input page dpi layer) or `crop` (and px py pw ph) request.
func ParseRender(req PlanRequest) (int, RenderRequest, error) {
	var n int
	crop := false
	switch req.Kind() {
	case PlanKindRender:
		n = 4
	case PlanKindCrop:
		n, crop = 8, true
	default:
		return 0, RenderRequest{}, fmt.Errorf("kind %d is not a render", req.Kind())
	}
	if len(req.Rec) != 3+n {
		return 0, RenderRequest{}, fmt.Errorf("a render request has %d args, not %d", len(req.Rec)-3, n)
	}
	if req.Field(PlanRdInput) < 0 {
		return 0, RenderRequest{}, fmt.Errorf("input %d", req.Field(PlanRdInput))
	}
	rr := RenderRequest{Page: req.Field(PlanRdPage), Dpi: req.Field(PlanRdDpi), Layer: req.Field(PlanRdLayer)}
	if crop {
		rr.Clip = &RenderClip{req.Field(PlanRdX), req.Field(PlanRdY), req.Field(PlanRdW), req.Field(PlanRdH)}
	}
	return int(req.Field(PlanRdInput)), rr, nil
}

// RenderCut is the pixels of rectangle c of b, as a Bitmap of its own: the crop law (D117), which a test
// holds an engine's own crops to.
func RenderCut(b *Bitmap, c RenderClip) (*Bitmap, error) {
	if c.W < 1 || c.H < 1 || c.X < 0 || c.Y < 0 || int64(c.X)+int64(c.W) > int64(b.W) || int64(c.Y)+int64(c.H) > int64(b.H) {
		return nil, &RenderError{Msg: fmt.Sprintf("the crop %dx%d+%d+%d is not inside the %dx%d page", c.W, c.H, c.X, c.Y, b.W, b.H)}
	}
	ch := int(b.Ch)
	out := make([]byte, 0, int(c.W)*int(c.H)*ch)
	for y := int32(0); y < c.H; y++ {
		at := int(c.Y+y)*int(b.Stride) + int(c.X)*ch
		out = append(out, b.D[at:at+int(c.W)*ch]...)
	}
	return BitmapFromBytes(c.W, c.H, b.Ch, out), nil
}

type wholeKey struct {
	input            int
	page, dpi, layer int32
}

// RenderFulfiller fulfils `render` and `crop` with a Renderer, synchronously: a render is CPU work, and a host
// that wants them concurrent wraps this in its own pool (PlanGoroutines does). Reading the input happens inside.
// A crop is the cut of a whole-page render (RenderCut), and the LAST whole-page render is kept, so a run that
// asks for a page and then for regions of it (the assets, D117) renders it once.
type RenderFulfiller struct {
	Renderer Renderer
	Inputs   map[int]RenderInput
	done     []planDone

	mu      sync.Mutex
	lastKey wholeKey
	last    *Bitmap
}

// whole is the whole page, from the one-entry cache or the engine.
func (f *RenderFulfiller) whole(idx int, in RenderInput, rr RenderRequest) (*Bitmap, error) {
	key := wholeKey{idx, rr.Page, rr.Dpi, rr.Layer}
	f.mu.Lock()
	if f.last != nil && f.lastKey == key {
		b := f.last
		f.mu.Unlock()
		return b, nil
	}
	f.mu.Unlock()
	rr.Clip = nil
	b, err := f.Renderer.Render(in.Source, in.Password, rr)
	if err != nil {
		return nil, err
	}
	f.mu.Lock()
	f.lastKey, f.last = key, b
	f.mu.Unlock()
	return b, nil
}

// NewRenderFulfiller serves the inputs of a run, by index.
func NewRenderFulfiller(r Renderer, inputs ...RenderInput) *RenderFulfiller {
	m := map[int]RenderInput{}
	for i, in := range inputs {
		m[i] = in
	}
	return &RenderFulfiller{Renderer: r, Inputs: m}
}

// NewRenderFulfillerFor serves ONE input of a run, at its index (the Engine converts one input at a time).
func NewRenderFulfillerFor(r Renderer, idx int, in RenderInput) *RenderFulfiller {
	return &RenderFulfiller{Renderer: r, Inputs: map[int]RenderInput{idx: in}}
}

// Fulfil answers one request. Public so a host that schedules its own goroutines can call it directly.
func (f *RenderFulfiller) Fulfil(req PlanRequest) (PlanAnswer, error) {
	idx, rr, err := ParseRender(req)
	if err != nil {
		return PlanAnswer{}, &FulfilError{Msg: err.Error()}
	}
	in, ok := f.Inputs[idx]
	if !ok {
		return PlanAnswer{}, &FulfilError{Msg: fmt.Sprintf("a render names input %d, which the run does not have", idx)}
	}
	var bm *Bitmap
	switch {
	case rr.Clip == nil:
		bm, err = f.whole(idx, in, rr)
	case f.Renderer.ExactCrop():
		bm, err = f.Renderer.Render(in.Source, in.Password, rr)
	default:
		var w *Bitmap
		if w, err = f.whole(idx, in, rr); err == nil {
			bm, err = RenderCut(w, *rr.Clip)
		}
	}
	if err != nil {
		status := PlanStatusFailed
		if re, ok := err.(*RenderError); ok && re.Unsupported {
			status = RenderStatusUnsupported
		}
		return PlanAnswer{Rec: []int32{4, req.Kind(), req.ID(), status}}, nil
	}
	return PlanAnswer{Rec: []int32{8, req.Kind(), req.ID(), PlanStatusOk, bm.W, bm.H, bm.Ch, rr.Dpi}, Bitmap: bm}, nil
}

// Start and Next make it a PlanFulfiller.
func (f *RenderFulfiller) Start(req PlanRequest, _ PlanBitmaps) error {
	a, err := f.Fulfil(req)
	f.done = append(f.done, planDone{a, err})
	return nil
}

func (f *RenderFulfiller) Next() (PlanAnswer, error) {
	d := f.done[0]
	f.done = f.done[1:]
	return d.a, d.err
}

// KindMux puts several fulfillers behind one: a request goes to the one that takes its kind, and its
// completion is asked of that one. The Engine's port set (render now, OCR and decode as their ports land).
type KindMux struct {
	parts []muxPart
	order []int
}

type muxPart struct {
	kinds []int32
	f     PlanFulfiller
}

// With adds a fulfiller for these kinds.
func (m *KindMux) With(kinds []int32, f PlanFulfiller) *KindMux {
	m.parts = append(m.parts, muxPart{kinds, f})
	return m
}

func (m *KindMux) Start(req PlanRequest, bm PlanBitmaps) error {
	for i, p := range m.parts {
		for _, k := range p.kinds {
			if k == req.Kind() {
				if err := p.f.Start(req, bm); err != nil {
					return err
				}
				m.order = append(m.order, i)
				return nil
			}
		}
	}
	return &FulfilError{Msg: fmt.Sprintf("no port is configured for request kind %d", req.Kind())}
}

func (m *KindMux) Next() (PlanAnswer, error) {
	if len(m.order) == 0 {
		return PlanAnswer{}, &FulfilError{Msg: "next without a started request"}
	}
	i := m.order[0]
	m.order = m.order[1:]
	return m.parts[i].f.Next()
}
