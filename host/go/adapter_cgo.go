//go:build cgo && (darwin || linux)

package emon

// The Go host's loader of adapter libraries (DEVLOG D925, K3): `dlopen`, the one export `emon_ocr_adapter_v1` of `host/c/include/emon_adapter.h` (ABI 1) and the calls through
// the adapter's function pointers. It copies buffers and decides nothing: registration, routing and the held-to-what-it-registered checks are `engines.go`'s, kin's. The library
// needs nothing of libemon, and neither does this loader: this is how Go reaches Tesseract, through `libemon_ocr_tesseract`, instead of linking libtesseract (the twin it replaces).

/*
#cgo linux LDFLAGS: -ldl
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct { char reason[96]; char message[224]; } emon_adapter_status;
typedef struct { int32_t width, height, channels, stride; const uint8_t *pixels; int32_t dpi; } emon_adapter_image;
typedef struct {
	uint32_t engine, caps, native, needs, flags;
	const char *config, *observed, *producer;
	const char *const *langs;
	size_t nlangs;
} emon_adapter_info;
typedef struct { int32_t *cells; size_t ncells; const uint8_t *binary; int32_t binary_width, binary_height; void *owner; } emon_adapter_answer;
typedef struct emon_ocr_adapter {
	uint32_t abi, size;
	const char *name;
	void *ctx;
	int (*describe)(void *, emon_adapter_info *, emon_adapter_status *);
	int (*supports)(void *, const int32_t *, size_t, emon_adapter_status *);
	int (*read)(void *, const int32_t *, size_t, const emon_adapter_image *, int, emon_adapter_answer *, emon_adapter_status *);
	int (*start)(void *, const int32_t *, size_t, const emon_adapter_image *, uint64_t, emon_adapter_status *);
	void (*cancel)(void *, uint64_t);
	void (*release)(void *, emon_adapter_answer *);
	void (*bind)(void *, const void *);
	void (*close)(void *);
} emon_ocr_adapter;

static void *ad_dlopen(const char *path, char *err, size_t cap) {
	void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
	if (!h) { const char *m = dlerror(); strncpy(err, m ? m : "dlopen failed", cap - 1); err[cap - 1] = 0; }
	return h;
}
static const emon_ocr_adapter *ad_open(void *lib, const char *cfg, const char *packs, emon_adapter_status *st) {
	typedef const emon_ocr_adapter *(*fn_t)(const char *, const char *, emon_adapter_status *);
	fn_t f = (fn_t)dlsym(lib, "emon_ocr_adapter_v1");
	if (!f) return NULL;
	return f(cfg, packs, st);
}
static int ad_has_export(void *lib) { return dlsym(lib, "emon_ocr_adapter_v1") != NULL; }
static int ad_describe(const emon_ocr_adapter *a, emon_adapter_info *i, emon_adapter_status *st) { return a->describe ? a->describe(a->ctx, i, st) : 2; }
static int ad_supports(const emon_ocr_adapter *a, const int32_t *r, size_t n, emon_adapter_status *st) { return a->supports ? a->supports(a->ctx, r, n, st) : 0; }
static int ad_read(const emon_ocr_adapter *a, const int32_t *r, size_t n, const emon_adapter_image *im, int bin, emon_adapter_answer *out, emon_adapter_status *st) { return a->read ? a->read(a->ctx, r, n, im, bin, out, st) : 2; }
static void ad_release(const emon_ocr_adapter *a, emon_adapter_answer *out) { if (a->release) a->release(a->ctx, out); }
static void ad_close(const emon_ocr_adapter *a) { if (a->close) a->close(a->ctx); }
static int ad_complete(const emon_ocr_adapter *a) { return a->read != NULL && a->describe != NULL; }
*/
import "C"

import (
	"fmt"
	"strings"
	"unsafe"

	"emon/adapters"
	"emon/ocr"
	"emon/plan"
	"emon/port"
)

type libAdapter struct {
	a    *C.emon_ocr_adapter
	lib  unsafe.Pointer
	info AdapterInfo
}

func statusText(st *C.emon_adapter_status, fallback string) (string, string) {
	r, m := C.GoString(&st.reason[0]), C.GoString(&st.message[0])
	if r == "" {
		r = fallback
	}
	if m == "" {
		m = fallback
	}
	return r, m
}

func loadAdapter(path, config, packsDir string) (Adapter, error) {
	cp := C.CString(path)
	defer C.free(unsafe.Pointer(cp))
	var derr [512]C.char
	lib := C.ad_dlopen(cp, &derr[0], C.size_t(len(derr)))
	if lib == nil {
		return nil, fmt.Errorf("adapter-load-failed: %s: %s", path, C.GoString(&derr[0]))
	}
	if C.ad_has_export(lib) == 0 {
		return nil, fmt.Errorf("adapter-load-failed: %s has no emon_ocr_adapter_v1", path)
	}
	var cfg, packs *C.char
	if config != "" {
		cfg = C.CString(config)
		defer C.free(unsafe.Pointer(cfg))
	}
	if packsDir != "" {
		packs = C.CString(packsDir)
		defer C.free(unsafe.Pointer(packs))
	}
	var st C.emon_adapter_status
	a := C.ad_open(lib, cfg, packs, &st)
	if a == nil {
		r, m := statusText(&st, "adapter-open-failed")
		return nil, fmt.Errorf("%s: %s: %s", r, path, m)
	}
	if uint32(a.abi) < 1 || uint32(a.abi) > uint32(adapters.AdAbi()) || uintptr(a.size) < unsafe.Sizeof(*a) || C.ad_complete(a) == 0 {
		C.ad_close(a)
		return nil, fmt.Errorf("adapter-abi-unsupported: %s is built for ABI %d (%d bytes); this host speaks %d", path, uint32(a.abi), uint32(a.size), adapters.AdAbi())
	}
	var raw C.emon_adapter_info
	var dst C.emon_adapter_status
	if C.ad_describe(a, &raw, &dst) != 0 {
		r, m := statusText(&dst, "describe failed")
		C.ad_close(a)
		return nil, fmt.Errorf("adapter-describe-failed: %s: %s", r, m)
	}
	info := AdapterInfo{Name: C.GoString(a.name), Engine: uint32(raw.engine), Caps: uint32(raw.caps), Measured: uint32(raw.native), Needs: uint32(raw.needs), Flags: uint32(raw.flags),
		Config: C.GoString(raw.config), Observed: C.GoString(raw.observed)}
	if info.Name == "" {
		info.Name = "adapter"
	}
	for _, line := range strings.Split(C.GoString(raw.producer), "\n") {
		if k, v, ok := strings.Cut(line, "="); ok {
			info.Producer = append(info.Producer, [2]string{k, v})
		}
	}
	return &libAdapter{a: a, lib: lib, info: info}, nil
}

func (l *libAdapter) Info() AdapterInfo { return l.info }

func (l *libAdapter) Close() {
	if l.a != nil {
		C.ad_close(l.a)
		l.a = nil
	}
}

func (l *libAdapter) Read(req port.PlanRequest, bm port.PlanBitmaps, keepBinary bool) (Reading, error) {
	b := bm[req.Field(plan.OcBitmap())]
	if b == nil || len(b.D) == 0 || len(req.Rec) == 0 {
		return Reading{}, &port.FulfilError{Msg: "ocr-adapter-invalid: " + l.info.Name + ": the request names no bitmap"}
	}
	if l.a == nil {
		return Reading{}, &port.FulfilError{Msg: "ocr-adapter-invalid: " + l.info.Name + ": closed"}
	}
	rec := (*C.int32_t)(unsafe.Pointer(&req.Rec[0]))
	n := C.size_t(len(req.Rec))
	var st C.emon_adapter_status
	if C.ad_supports(l.a, rec, n, &st) != 0 {
		r, _ := statusText(&st, "unsupported")
		return Reading{Status: AdapterUnsupported, Reason: r}, nil
	}
	img := C.emon_adapter_image{width: C.int32_t(b.W), height: C.int32_t(b.H), channels: C.int32_t(b.Ch), stride: C.int32_t(b.W * b.Ch), pixels: (*C.uint8_t)(unsafe.Pointer(&b.D[0])), dpi: C.int32_t(ocr.OcfDpi(req.Field(plan.OcFlags())))}
	var out C.emon_adapter_answer
	want := C.int(0)
	if keepBinary {
		want = 1
	}
	rc := C.ad_read(l.a, rec, n, &img, want, &out, &st)
	if rc != 0 {
		r, _ := statusText(&st, "failed")
		if out.cells != nil || out.owner != nil {
			C.ad_release(l.a, &out)
		}
		s := AdapterFailed
		if int32(rc) == AdapterUnsupported {
			s = AdapterUnsupported
		}
		return Reading{Status: s, Reason: r}, nil
	}
	defer C.ad_release(l.a, &out)
	r := Reading{}
	if out.cells != nil {
		r.Rec = append([]int32(nil), unsafe.Slice((*int32)(unsafe.Pointer(out.cells)), int(out.ncells))...)
	}
	if keepBinary && out.binary != nil && out.binary_width > 0 && out.binary_height > 0 {
		px := unsafe.Slice((*byte)(unsafe.Pointer(out.binary)), int(out.binary_width)*int(out.binary_height))
		r.Binary = port.BitmapFromBytes(int32(out.binary_width), int32(out.binary_height), 1, append([]byte(nil), px...))
	}
	return r, nil
}
