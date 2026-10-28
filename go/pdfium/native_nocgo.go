//go:build !cgo

package pdfium

import "github.com/3sln/emon/go/port"

// This build has no cgo, and the binding loads pdfium through it (native_cgo.go): Open says so. The package still compiles, so
// a program that names a Renderer need not be built twice.

func bind(names []string) error {
	return &Unavailable{Msg: "this build has no cgo, which loads pdfium at run time: build with CGO_ENABLED=1 and a C compiler"}
}

func renderNative(input port.RenderSource, password []byte, req port.RenderRequest) (*port.Bitmap, error) {
	return nil, failed("this build has no cgo, which loads pdfium at run time")
}
