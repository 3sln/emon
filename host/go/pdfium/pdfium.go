// The Go host's pdfium Renderer (DEVLOG P6.2, D127; P11.x1, D539; SPEC 7.2, D007). The default `port.Renderer`; the twin of
// `host/rust/src/pdfium.rs`, `host/java/emon/ffi/Pdfium.java` and `host/csharp/Emon/Ffi/Pdfium.cs`, whose headers say how a
// document is read (pdfium's custom file-access loader over the run's byte source: no path, no copy, no whole-file buffer), how a
// layer is made (page objects removed in memory, never saved) and why the watermark and background layers are `unsupported`
// (pdfium's mark API cannot read the NAME values `/Artifact /Subtype /Watermark` is made of).
//
// PDFIUM IS LOADED AT RUN TIME, as Java (FFM) and C# (P/Invoke) load it (D539): nothing links it, no pdfium header or `-lpdfium`
// is needed to build, and the root package `emon` does not import this one, so a program that never renders pays nothing. The
// binding (native_cgo.go) is cgo used only for `dlopen` and `dlsym` and for calling the function pointers they answer: it needs a C
// compiler (cgo, which is the default where one is installed) and no pdfium. Built with CGO_ENABLED=0 the package still compiles:
// `Open` then answers an `*Unavailable` and a Renderer made by `New` cannot be had.
//
// Where the library is looked for, in order: the `libDirOrFile` argument of `Open`, `$EMON_PDFIUM_LIB` (a directory or a file), then
// `.cache/pdfium/current/lib` (relative to the working directory: the repository root) and the system's own search. It is the pinned
// build that `bb pdfium-fetch` installs (tools/pdfium/pdfium.edn). A missing library, or one without a symbol the binding needs, is an
// `*Unavailable` from `Open`, said then and not as a failed render later.
//
// The rules a render follows are the Rust host's, line for line: the layer and dpi (1..4800) checks, the size `port.PixelsOf`, the
// `pixels` limit read from kin's table at each render, the layer pruning, the flags, BGRX to RGB, the error texts. A crop is not done
// here: `ExactCrop` is false and `port.RenderFulfiller` cuts the whole page by the shared rule.
package pdfium

import (
	"fmt"
	"os"
	"strings"
	"sync"

	"emon/port"
)

const (
	flagAnnot   = 0x01
	flagReverse = 0x10
	bitmapBGRx  = 3
	objText     = 1
	objForm     = 5
)

// pdfium is not thread-safe: one process-wide lock, so any number of Pdfium values and goroutines may be used and the renders
// happen one at a time.
var lock sync.Mutex

// Unavailable says pdfium could not be had: the library was not found or could not be loaded, lacks a symbol, or this build has no
// cgo to load it with.
type Unavailable struct{ Msg string }

func (e *Unavailable) Error() string { return e.Msg }

// Pdfium is the default Renderer. A render larger than the `pixels` limit (kin's table, read through `port.LimitOf(9)` at each render, so a
// conversion's `limit.pixels` applies to rendering too) is refused (`failed`), before any memory is asked for (P10.4).
type Pdfium struct{}

// Open loads pdfium and initialises it (once per process; the library found first stays). `libDirOrFile` is a directory holding the
// library or the library itself; "" searches (see the package comment). The error is an *Unavailable.
func Open(libDirOrFile string) (*Pdfium, error) {
	var names []string
	if libDirOrFile != "" {
		names = addCandidates(names, libDirOrFile)
	}
	if env := os.Getenv("EMON_PDFIUM_LIB"); env != "" {
		names = addCandidates(names, env)
	}
	names = addCandidates(names, ".cache/pdfium/current/lib")
	names = append(names, "libpdfium.dylib", "libpdfium.so")
	if err := bind(names); err != nil {
		return nil, err
	}
	return &Pdfium{}, nil
}

// New is Open("") for a program that has the library: it panics where Open would answer an error.
func New() *Pdfium {
	p, err := Open("")
	if err != nil {
		panic(err)
	}
	return p
}

func addCandidates(out []string, p string) []string {
	lower := strings.ToLower(p)
	if strings.HasSuffix(lower, ".dylib") || strings.HasSuffix(lower, ".so") || strings.HasSuffix(lower, ".dll") {
		return append(out, p)
	}
	d := p
	if !strings.HasSuffix(d, "/") {
		d += "/"
	}
	return append(out, d+"libpdfium.dylib", d+"libpdfium.so", d+"pdfium.dll")
}

// notFound is the error of a search that found nothing; cause is the loader's last word.
func notFound(names []string, cause string) error {
	msg := "pdfium was not found (looked for " + strings.Join(names, ", ") + "). Run `bb pdfium-fetch` and set EMON_PDFIUM_LIB to its lib directory" +
		" (.cache/pdfium/current/lib), or pass the directory."
	if cause != "" {
		msg += " (" + cause + ")"
	}
	return &Unavailable{Msg: msg}
}

// Layers: full, no text, text only.
func (p *Pdfium) Layers() uint32 { return 0b00111 }

// ExactCrop is false: a render clipped to a rectangle moves the pixels on the clip's edge (a vertical rule at
// the left edge of a crop of a bank statement differed by up to 107 of 255 from the same pixels cut out of the
// whole page, D127), so the fulfiller renders the whole page, keeps it, and cuts.
func (p *Pdfium) ExactCrop() bool { return false }

func errText(e uint64) string {
	switch e {
	case 0:
		return "no error"
	case 2:
		return "file not found or could not be opened"
	case 3:
		return "not a PDF, or its structure is damaged"
	case 4:
		return "password required or incorrect"
	case 5:
		return "unsupported security scheme"
	case 6:
		return "page not found or content error"
	default:
		return fmt.Sprintf("pdfium error %d", e)
	}
}

func failed(format string, a ...any) error {
	return &port.RenderError{Msg: fmt.Sprintf(format, a...)}
}

// keepIn: should an object of type ty stay in this layer? 1 keep, 0 drop, -1 a form to walk into (its contents decide).
func keepIn(layer int32, ty int) int {
	if ty == objForm {
		return -1
	}
	switch layer {
	case port.LayerNoText:
		if ty == objText {
			return 0
		}
	case port.LayerTextOnly:
		if ty != objText {
			return 0
		}
	}
	return 1
}

// flagsOf: the flags of a layer's render (the pruning of its objects is the binding's).
func flagsOf(layer int32) int {
	flags := flagReverse
	switch layer {
	case port.LayerFull, port.LayerNoText:
		flags |= flagAnnot
	}
	return flags
}

// checkPixels refuses a render over the `pixels` limit, before any memory is asked for.
func checkPixels(w, h int32) error {
	maxPixels := int64(port.LimitOf(9))
	if int64(w)*int64(h) > maxPixels {
		return failed("%dx%d pixels is over the limit of %d", w, h, maxPixels)
	}
	return nil
}

// Render implements port.Renderer.
func (p *Pdfium) Render(input port.RenderSource, password []byte, req port.RenderRequest) (*port.Bitmap, error) {
	if req.Layer == port.LayerNoWatermark || req.Layer == port.LayerWatermarkOnly {
		return nil, &port.RenderError{Unsupported: true, Msg: "pdfium's mark API cannot read /Artifact /Subtype (a name)"}
	}
	if req.Layer < 0 || req.Layer > 4 {
		return nil, &port.RenderError{Unsupported: true, Msg: fmt.Sprintf("layer %d", req.Layer)}
	}
	if req.Dpi < 1 || req.Dpi > 4800 {
		return nil, failed("dpi %d", req.Dpi)
	}
	for _, b := range password {
		if b == 0 {
			return nil, failed("a password with a NUL")
		}
	}
	lock.Lock()
	defer lock.Unlock()
	return renderNative(input, password, req)
}
