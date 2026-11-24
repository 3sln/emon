//go:build cgo

package pdfium

/*
#include <stddef.h>
*/
import "C"

import (
	"io"
	"runtime/cgo"
	"unsafe"
)

// readCtx is what pdfium's file-access `m_Param` stands for: the run's byte source. It travels as a
// runtime/cgo.Handle (an integer), never as a Go pointer stored in C memory.
type readCtx struct{ src io.ReaderAt }

// goGetBlock is FPDF_FILEACCESS.m_GetBlock: fill buf[0:size] from the source at pos, or answer 0.
//
//export goGetBlock
func goGetBlock(param unsafe.Pointer, pos C.ulong, buf *C.uchar, size C.ulong) (ok C.int) {
	defer func() {
		if recover() != nil {
			ok = 0
		}
	}()
	ctx := cgo.Handle(uintptr(param)).Value().(*readCtx)
	out := unsafe.Slice((*byte)(unsafe.Pointer(buf)), int(size))
	got := 0
	for got < len(out) {
		n, err := ctx.src.ReadAt(out[got:], int64(pos)+int64(got))
		got += n
		if got < len(out) && (err != nil || n == 0) {
			return 0
		}
	}
	return 1
}
