//go:build pdfium

package main

import (
	"emon/pdfium"
	"emon/port"
)

// livePdfium is `--pdfium[=DIR]` (P11.x1, D1450): pdfium loaded at run time (cgo for dlopen only, nothing linked) from DIR, $EMON_PDFIUM_LIB,
// `.cache/pdfium/current/lib` or the system's search.
func livePdfium(dir string) (port.Renderer, error) {
	p, err := pdfium.Open(dir)
	if err != nil {
		return nil, err
	}
	return p, nil
}
