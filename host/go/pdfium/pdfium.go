//go:build pdfium

// The Go host's binding to pdfium's C API (DEVLOG P6.2, D127; SPEC 7.2, D007). The default Renderer; the twin
// of `host/rust/src/pdfium.rs`, whose header says how a document is read (pdfium's custom file-access loader
// over the run's byte source: no path, no copy, no whole-file buffer), how a layer is made (page objects
// removed in memory, never saved) and why the watermark and background layers are `unsupported` (pdfium's
// mark API cannot read the NAME values `/Artifact /Subtype /Watermark` is made of).
//
// COMPILED ONLY WITH `-tags pdfium`, and then cgo against the pinned bblanchon/pdfium-binaries build that
// `bb pdfium-fetch` installs (tools/pdfium/pdfium.edn):
//
//	CGO_CFLAGS=-I<pdfium>/include CGO_LDFLAGS='-L<pdfium>/lib -lpdfium -Wl,-rpath,<pdfium>/lib' \
//	    go build -tags pdfium ...
//
// (On macOS the linked binary must be ad-hoc signed again, `codesign -f -s - prog`: Go's linker rewrites the
// image after cgo's signature and the kernel kills it otherwise.) Nothing in `bb gates` builds this package:
// a gate replays a committed transcript (`bb render`).
package pdfium

/*
#include <stdlib.h>
#include "fpdfview.h"
#include "fpdf_edit.h"

extern int goGetBlock(void*, unsigned long, unsigned char*, unsigned long);

static void access_setup(FPDF_FILEACCESS* a, unsigned long len, void* param) {
	a->m_FileLen = len;
	a->m_GetBlock = goGetBlock;
	a->m_Param = param;
}
static void init_once(void) { FPDF_InitLibrary(); }
*/
import "C"

import (
	"fmt"
	"runtime/cgo"
	"sync"
	"unsafe"

	"emon/port"
)

const (
	flagAnnot   = 0x01
	flagReverse = 0x10
	bitmapBGRx  = 3
	objText     = 1
	objForm     = 5
)

var (
	initOnce sync.Once
	// pdfium is not thread-safe.
	lock sync.Mutex
)

// Pdfium is the default Renderer.
type Pdfium struct {
	// MaxPixels refuses a render larger than this many pixels (`failed`), before any memory is asked for.
	MaxPixels int64
}

// New initialises the library (once per process) and answers a Renderer.
func New() *Pdfium {
	initOnce.Do(func() { C.init_once() })
	return &Pdfium{MaxPixels: 1 << 28}
}

// Layers: full, no text, text only.
func (p *Pdfium) Layers() uint32 { return 0b00111 }

// ExactCrop is false: a render clipped to a rectangle moves the pixels on the clip's edge (a vertical rule at
// the left edge of a crop of a bank statement differed by up to 107 of 255 from the same pixels cut out of the
// whole page, D127), so the fulfiller renders the whole page, keeps it, and cuts.
func (p *Pdfium) ExactCrop() bool { return false }

func lastError() string {
	switch e := uint64(C.FPDF_GetLastError()); e {
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

// keep: should o stay in this layer? 1 keep, 0 drop, -1 a form to walk into (its contents decide).
func keep(layer int32, o C.FPDF_PAGEOBJECT) int {
	ty := int(C.FPDFPageObj_GetType(o))
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

func pruneForm(layer int32, form C.FPDF_PAGEOBJECT) {
	for i := int(C.FPDFFormObj_CountObjects(form)) - 1; i >= 0; i-- {
		o := C.FPDFFormObj_GetObject(form, C.ulong(i))
		if o == nil {
			continue
		}
		switch keep(layer, o) {
		case 0:
			if C.FPDFFormObj_RemoveObject(form, o) != 0 {
				C.FPDFPageObj_Destroy(o)
			}
		case -1:
			pruneForm(layer, o)
		}
	}
}

func prunePage(layer int32, page C.FPDF_PAGE) {
	for i := int(C.FPDFPage_CountObjects(page)) - 1; i >= 0; i-- {
		o := C.FPDFPage_GetObject(page, C.int(i))
		if o == nil {
			continue
		}
		switch keep(layer, o) {
		case 0:
			if C.FPDFPage_RemoveObject(page, o) != 0 {
				C.FPDFPageObj_Destroy(o)
			}
		case -1:
			pruneForm(layer, o)
		}
	}
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
	lock.Lock()
	defer lock.Unlock()

	h := cgo.NewHandle(&readCtx{src: input})
	defer h.Delete()
	access := (*C.FPDF_FILEACCESS)(C.calloc(1, C.size_t(unsafe.Sizeof(C.FPDF_FILEACCESS{}))))
	defer C.free(unsafe.Pointer(access))
	C.access_setup(access, C.ulong(input.Size()), unsafe.Pointer(uintptr(h)))
	var pw *C.char
	if password != nil {
		pw = C.CString(string(password))
		defer C.free(unsafe.Pointer(pw))
	}
	doc := C.FPDF_LoadCustomDocument(access, pw)
	if doc == nil {
		return nil, failed("%s", lastError())
	}
	defer C.FPDF_CloseDocument(doc)

	n := int32(C.FPDF_GetPageCount(doc))
	if req.Page < 0 || req.Page >= n {
		return nil, failed("page %d of %d", req.Page, n)
	}
	page := C.FPDF_LoadPage(doc, C.int(req.Page))
	if page == nil {
		return nil, failed("%s", lastError())
	}
	defer C.FPDF_ClosePage(page)

	w := port.PixelsOf(float64(C.FPDF_GetPageWidthF(page)), req.Dpi)
	hh := port.PixelsOf(float64(C.FPDF_GetPageHeightF(page)), req.Dpi)
	if int64(w)*int64(hh) > p.MaxPixels {
		return nil, failed("%dx%d pixels is over the limit of %d", w, hh, p.MaxPixels)
	}

	flags := C.int(flagReverse)
	switch req.Layer {
	case port.LayerFull:
		flags |= flagAnnot
	case port.LayerNoText:
		prunePage(req.Layer, page)
		flags |= flagAnnot
	default:
		prunePage(req.Layer, page)
	}

	cw, ch := int(w), int(hh)
	// the pixels live in C memory: pdfium keeps the pointer across calls, which Go memory may not be asked to bear
	cbuf := C.malloc(C.size_t(cw * ch * 4))
	defer C.free(cbuf)
	buf := unsafe.Slice((*byte)(cbuf), cw*ch*4)
	bm := C.FPDFBitmap_CreateEx(C.int(w), C.int(hh), bitmapBGRx, cbuf, C.int(cw*4))
	if bm == nil {
		return nil, failed("the bitmap could not be created")
	}
	C.FPDFBitmap_FillRect(bm, 0, 0, C.int(w), C.int(hh), 0xFFFFFFFF)
	C.FPDF_RenderPageBitmap(bm, page, 0, 0, C.int(w), C.int(hh), 0, flags)
	C.FPDFBitmap_Destroy(bm)
	rgb := make([]byte, 0, cw*ch*3)
	for i := 0; i < len(buf); i += 4 {
		rgb = append(rgb, buf[i], buf[i+1], buf[i+2])
	}
	return port.BitmapFromBytes(w, hh, 3, rgb), nil
}
