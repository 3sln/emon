//go:build ppocr && cgo && (darwin || linux)

// Experimental PP-OCR binding. Policy and complete answer construction live in
// the versioned shared Rust adapter; this file only copies ABI buffers.
package emon

/*
#cgo linux LDFLAGS: -ldl
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
typedef struct {
 void *lib, *port;
 void *(*open)(const char*, char*, size_t);
 void (*close)(void*);
 int (*read)(const void*, const int32_t*, size_t, const uint8_t*, size_t, int32_t, int32_t, int32_t, int32_t**, size_t*, char*, size_t);
 void (*release)(int32_t*, size_t);
} EmonPP;
static EmonPP *pp_load(const char *name) {
 EmonPP *p = calloc(1, sizeof(*p));
 if (!p) return NULL;
 p->lib = dlopen(name, RTLD_NOW | RTLD_LOCAL);
 if (!p->lib) { free(p); return NULL; }
 p->open = dlsym(p->lib, "emon_ppocr_open_v1");
 p->close = dlsym(p->lib, "emon_ppocr_close_v1");
 p->read = dlsym(p->lib, "emon_ppocr_read_v1");
 p->release = dlsym(p->lib, "emon_ppocr_free_v1");
 if (!p->open || !p->close || !p->read || !p->release) { dlclose(p->lib); free(p); return NULL; }
 return p;
}
static void pp_close(EmonPP *p) { if(p) { if(p->port) p->close(p->port); dlclose(p->lib); free(p); } }
static int pp_open(EmonPP *p, const char *dir, char *e, size_t cap) { p->port = p->open(dir,e,cap); return p->port != NULL; }
static int pp_read(EmonPP *p, const int32_t *r, size_t n, const uint8_t *b, size_t bytes, int32_t w, int32_t h, int32_t ch, int32_t **out, size_t *count, char *e, size_t cap) { return p->read(p->port,r,n,b,bytes,w,h,ch,out,count,e,cap); }
static void pp_free(EmonPP *p, int32_t *v, size_t n) { p->release(v,n); }
*/
import "C"
import (
	"emon/ocrengine"
	"emon/ocrport"
	"emon/plan"
	"emon/port"
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"unsafe"
)

type PpOcr struct {
	mu        sync.Mutex
	native    *C.EmonPP
	incumbent Ocr
}

func NewPpOcr(dir string, incumbent Ocr) (*PpOcr, error) {
	if dir == "" {
		dir = os.Getenv("EMON_PPOCR_MODELS")
		if dir == "" {
			dir = ".cache/ppocr"
		}
	}
	for _, name := range []string{"ch_PP-OCRv5_det_mobile.onnx", "en_PP-OCRv5_rec_mobile.onnx", "ch_ppocr_mobile_v2.0_cls_mobile.onnx"} {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			return nil, errors.New("ppocr-models-missing")
		}
	}
	name := os.Getenv("EMON_PPOCR_LIB")
	if name == "" {
		name = ".cache/ppocr/libemon_ppocr_adapter.so"
		if runtime.GOOS == "darwin" {
			name = ".cache/ppocr/libemon_ppocr_adapter.dylib"
		}
	}
	c := C.CString(name)
	p := C.pp_load(c)
	C.free(unsafe.Pointer(c))
	if p == nil {
		return nil, errors.New("ppocr-runtime-missing")
	}
	d := C.CString(dir)
	defer C.free(unsafe.Pointer(d))
	var msg [2048]C.char
	if C.pp_open(p, d, &msg[0], C.size_t(len(msg))) == 0 {
		C.pp_close(p)
		return nil, errors.New(C.GoString(&msg[0]))
	}
	o := &PpOcr{native: p, incumbent: incumbent}
	runtime.SetFinalizer(o, (*PpOcr).Close)
	return o, nil
}
func (p *PpOcr) Close() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.native != nil {
		C.pp_close(p.native)
		p.native = nil
	}
}
func (p *PpOcr) Producer() [][2]string {
	var out [][2]string
	if p.incumbent != nil {
		out = append(out, p.incumbent.Producer()...)
	}
	id := ""
	for i := int32(0); i < int32(len(ocrengine.OeIdentity)); i++ {
		id += string(rune(ocrengine.OeIdentity[i]))
	}
	return append(out, [2]string{"ocr-engine", "tesseract+ppocr"}, [2]string{"ocr-model", id}, [2]string{"pp-lexicon", "tessdata_fast/eng sha256=7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"}, [2]string{"onnxruntime", "1.22.0 CPU sequential intra=1 inter=1"})
}
func (p *PpOcr) Fulfil(req port.PlanRequest, bitmaps port.PlanBitmaps, keep bool) (port.PlanAnswer, error) {
	r := port.NewCells()
	for _, v := range req.Rec {
		port.CellsPush(r, v)
	}
	if ocrport.OpIdentityAt(r) < 0 {
		if p.incumbent == nil {
			return port.PlanAnswer{}, errors.New("ppocr-incumbent-missing")
		}
		return p.incumbent.Fulfil(req, bitmaps, keep)
	}
	b := bitmaps[req.Field(plan.OcBitmap())]
	if b == nil || len(b.D) == 0 {
		return port.PlanAnswer{}, errors.New("ppocr-bad-request")
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.native == nil {
		return port.PlanAnswer{}, errors.New("ppocr-closed")
	}
	var out *C.int32_t
	var n C.size_t
	var msg [2048]C.char
	status := C.pp_read(p.native, (*C.int32_t)(unsafe.Pointer(&req.Rec[0])), C.size_t(len(req.Rec)), (*C.uint8_t)(unsafe.Pointer(&b.D[0])), C.size_t(len(b.D)), C.int32_t(b.W), C.int32_t(b.H), C.int32_t(b.Ch), &out, &n, &msg[0], C.size_t(len(msg)))
	if status != 0 {
		return port.PlanAnswer{}, errors.New(C.GoString(&msg[0]))
	}
	defer C.pp_free(p.native, out, n)
	return port.PlanAnswer{Rec: append([]int32(nil), unsafe.Slice((*int32)(unsafe.Pointer(out)), int(n))...)}, nil
}
