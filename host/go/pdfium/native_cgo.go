//go:build cgo

// The cgo half of the pdfium Renderer (P11.x1, D539): the library is opened with dlopen at run time and its entry points are
// found with dlsym, so nothing links pdfium and no pdfium header is read. The prototypes are declared here, as tess.go declares
// Tesseract's: a declaration is all a call through a function pointer needs. Read pdfium.go for the rules, and
// `host/rust/src/pdfium.rs` for the twin.
package pdfium

/*
#cgo linux LDFLAGS: -ldl
#include <stdint.h>
#include <stdlib.h>
#include <dlfcn.h>

typedef void* H;

// the entry points, as pdfium's C API declares them (fpdfview.h, fpdf_edit.h)
typedef struct {
	void (*InitLibrary)(void);
	unsigned long (*GetLastError)(void);
	H (*LoadCustomDocument)(void*, const char*);
	void (*CloseDocument)(H);
	int (*GetPageCount)(H);
	H (*LoadPage)(H, int);
	void (*ClosePage)(H);
	float (*GetPageWidthF)(H);
	float (*GetPageHeightF)(H);
	H (*BitmapCreateEx)(int, int, int, void*, int);
	int (*BitmapFillRect)(H, int, int, int, int, unsigned long);
	void (*BitmapDestroy)(H);
	void (*RenderPageBitmap)(H, H, int, int, int, int, int, int);
	int (*PageCountObjects)(H);
	H (*PageGetObject)(H, int);
	int (*PageRemoveObject)(H, H);
	void (*ObjDestroy)(H);
	int (*ObjGetType)(H);
	int (*FormCountObjects)(H);
	H (*FormGetObject)(H, unsigned long);
	int (*FormRemoveObject)(H, H);
} pdfium_api;

static pdfium_api api;

// struct FPDF_FILEACCESS { unsigned long m_FileLen; int (*m_GetBlock)(void*, unsigned long, unsigned char*, unsigned long); void* m_Param; }
typedef struct {
	unsigned long len;
	int (*get)(void*, unsigned long, unsigned char*, unsigned long);
	void* param;
} access_t;

extern int goGetBlock(void*, unsigned long, unsigned char*, unsigned long);

// param is a runtime/cgo.Handle, an integer: it travels as one and is made a pointer here, never as a Go pointer in C memory
static void access_setup(access_t* a, unsigned long len, uintptr_t param) {
	a->len = len;
	a->get = goGetBlock;
	a->param = (void*)param;
}

static void* lib_open(const char* path) { return dlopen(path, RTLD_NOW | RTLD_LOCAL); }
static const char* lib_error(void) { return dlerror(); }

// api_bind finds every entry point in lib; it answers the name of the first one the library lacks, or NULL.
static const char* api_bind(void* lib) {
#define BIND(field, sym) if ((*(void**)&api.field = dlsym(lib, sym)) == NULL) return sym;
	BIND(InitLibrary, "FPDF_InitLibrary")
	BIND(GetLastError, "FPDF_GetLastError")
	BIND(LoadCustomDocument, "FPDF_LoadCustomDocument")
	BIND(CloseDocument, "FPDF_CloseDocument")
	BIND(GetPageCount, "FPDF_GetPageCount")
	BIND(LoadPage, "FPDF_LoadPage")
	BIND(ClosePage, "FPDF_ClosePage")
	BIND(GetPageWidthF, "FPDF_GetPageWidthF")
	BIND(GetPageHeightF, "FPDF_GetPageHeightF")
	BIND(BitmapCreateEx, "FPDFBitmap_CreateEx")
	BIND(BitmapFillRect, "FPDFBitmap_FillRect")
	BIND(BitmapDestroy, "FPDFBitmap_Destroy")
	BIND(RenderPageBitmap, "FPDF_RenderPageBitmap")
	BIND(PageCountObjects, "FPDFPage_CountObjects")
	BIND(PageGetObject, "FPDFPage_GetObject")
	BIND(PageRemoveObject, "FPDFPage_RemoveObject")
	BIND(ObjDestroy, "FPDFPageObj_Destroy")
	BIND(ObjGetType, "FPDFPageObj_GetType")
	BIND(FormCountObjects, "FPDFFormObj_CountObjects")
	BIND(FormGetObject, "FPDFFormObj_GetObject")
	BIND(FormRemoveObject, "FPDFFormObj_RemoveObject")
#undef BIND
	return NULL;
}

static void p_init(void) { api.InitLibrary(); }
static unsigned long p_last_error(void) { return api.GetLastError(); }
static H p_load_doc(access_t* a, const char* pw) { return api.LoadCustomDocument(a, pw); }
static void p_close_doc(H d) { api.CloseDocument(d); }
static int p_page_count(H d) { return api.GetPageCount(d); }
static H p_load_page(H d, int i) { return api.LoadPage(d, i); }
static void p_close_page(H p) { api.ClosePage(p); }
static float p_page_w(H p) { return api.GetPageWidthF(p); }
static float p_page_h(H p) { return api.GetPageHeightF(p); }
static H p_bitmap(int w, int h, int fmt, void* buf, int stride) { return api.BitmapCreateEx(w, h, fmt, buf, stride); }
static void p_fill(H b, int x, int y, int w, int h, unsigned long c) { api.BitmapFillRect(b, x, y, w, h, c); }
static void p_bitmap_destroy(H b) { api.BitmapDestroy(b); }
static void p_render(H b, H p, int x, int y, int w, int h, int rot, int flags) { api.RenderPageBitmap(b, p, x, y, w, h, rot, flags); }
static int p_page_objs(H p) { return api.PageCountObjects(p); }
static H p_page_obj(H p, int i) { return api.PageGetObject(p, i); }
static int p_page_remove(H p, H o) { return api.PageRemoveObject(p, o); }
static void p_obj_destroy(H o) { api.ObjDestroy(o); }
static int p_obj_type(H o) { return api.ObjGetType(o); }
static int p_form_objs(H f) { return api.FormCountObjects(f); }
static H p_form_obj(H f, unsigned long i) { return api.FormGetObject(f, i); }
static int p_form_remove(H f, H o) { return api.FormRemoveObject(f, o); }
*/
import "C"

import (
	"runtime/cgo"
	"strings"
	"unsafe"

	"emon/port"
)

// bound is true once an entry-point table is filled and FPDF_InitLibrary has run; the library found first stays for the process.
var bound bool

// bind opens the first of names that loads and finds every entry point in it, then initialises pdfium (once per process).
func bind(names []string) error {
	lock.Lock()
	defer lock.Unlock()
	if bound {
		return nil
	}
	var causes []string
	for _, n := range names {
		cn := C.CString(n)
		lib := C.lib_open(cn)
		C.free(unsafe.Pointer(cn))
		if lib == nil {
			if e := C.lib_error(); e != nil {
				causes = append(causes, C.GoString(e))
			}
			continue
		}
		if missing := C.api_bind(lib); missing != nil {
			return &Unavailable{Msg: "the pdfium library has no symbol " + C.GoString(missing) + ": is it the pinned build? (`bb pdfium-fetch`)"}
		}
		C.p_init()
		bound = true
		return nil
	}
	cause := ""
	if len(causes) > 0 {
		cause = causes[len(causes)-1]
	}
	return notFound(names, strings.TrimSpace(cause))
}

func lastError() string { return errText(uint64(C.p_last_error())) }

func prune(layer int32, o C.H) int { return keepIn(layer, int(C.p_obj_type(o))) }

func pruneForm(layer int32, form C.H) {
	for i := int(C.p_form_objs(form)) - 1; i >= 0; i-- {
		o := C.p_form_obj(form, C.ulong(i))
		if o == nil {
			continue
		}
		switch prune(layer, o) {
		case 0:
			if C.p_form_remove(form, o) != 0 {
				C.p_obj_destroy(o)
			}
		case -1:
			pruneForm(layer, o)
		}
	}
}

func prunePage(layer int32, page C.H) {
	for i := int(C.p_page_objs(page)) - 1; i >= 0; i-- {
		o := C.p_page_obj(page, C.int(i))
		if o == nil {
			continue
		}
		switch prune(layer, o) {
		case 0:
			if C.p_page_remove(page, o) != 0 {
				C.p_obj_destroy(o)
			}
		case -1:
			pruneForm(layer, o)
		}
	}
}

// renderNative renders req of input; the caller holds the lock and has checked the layer and the dpi.
func renderNative(input port.RenderSource, password []byte, req port.RenderRequest) (*port.Bitmap, error) {
	if !bound {
		return nil, failed("pdfium is not loaded (Open it first)")
	}
	size := input.Size()
	if size < 0 || uint64(C.ulong(size)) != uint64(size) {
		return nil, failed("the input is longer than pdfium's file access can say")
	}
	h := cgo.NewHandle(&readCtx{src: input})
	defer h.Delete()
	access := (*C.access_t)(C.calloc(1, C.size_t(unsafe.Sizeof(C.access_t{}))))
	defer C.free(unsafe.Pointer(access))
	C.access_setup(access, C.ulong(size), C.uintptr_t(h))
	var pw *C.char
	if password != nil {
		pw = C.CString(string(password))
		defer C.free(unsafe.Pointer(pw))
	}
	doc := C.p_load_doc(access, pw)
	if doc == nil {
		return nil, failed("%s", lastError())
	}
	defer C.p_close_doc(doc)

	n := int32(C.p_page_count(doc))
	if req.Page < 0 || req.Page >= n {
		return nil, failed("page %d of %d", req.Page, n)
	}
	page := C.p_load_page(doc, C.int(req.Page))
	if page == nil {
		return nil, failed("%s", lastError())
	}
	defer C.p_close_page(page)

	w := port.PixelsOf(float64(C.p_page_w(page)), req.Dpi)
	hh := port.PixelsOf(float64(C.p_page_h(page)), req.Dpi)
	if err := checkPixels(w, hh); err != nil {
		return nil, err
	}
	if req.Layer == port.LayerNoText || req.Layer == port.LayerTextOnly {
		prunePage(req.Layer, page)
	}
	flags := C.int(flagsOf(req.Layer))

	cw, ch := int(w), int(hh)
	// the pixels live in C memory: pdfium keeps the pointer across calls, which Go memory may not be asked to bear
	cbuf := C.malloc(C.size_t(cw * ch * 4))
	if cbuf == nil {
		return nil, failed("the bitmap could not be created")
	}
	defer C.free(cbuf)
	buf := unsafe.Slice((*byte)(cbuf), cw*ch*4)
	bm := C.p_bitmap(C.int(w), C.int(hh), bitmapBGRx, cbuf, C.int(cw*4))
	if bm == nil {
		return nil, failed("the bitmap could not be created")
	}
	C.p_fill(bm, 0, 0, C.int(w), C.int(hh), 0xFFFFFFFF)
	C.p_render(bm, page, 0, 0, C.int(w), C.int(hh), 0, flags)
	C.p_bitmap_destroy(bm)
	rgb := make([]byte, 0, cw*ch*3)
	for i := 0; i < len(buf); i += 4 {
		rgb = append(rgb, buf[i], buf[i+1], buf[i+2])
	}
	return port.BitmapFromBytes(w, hh, 3, rgb), nil
}
