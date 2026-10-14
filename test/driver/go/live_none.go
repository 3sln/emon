//go:build !pdfium

package main

import (
	"errors"

	"emon/port"
)

// livePdfium in a build WITHOUT the pdfium binding: the binding (host/go/pdfium) loads the library through cgo, and the driver
// links it only under the `pdfium` tag (`EMON_GO_LIVE=1 kin/scripts/emon-go-build`). No gate has it: a gate replays a transcript.
func livePdfium(string) (port.Renderer, error) {
	return nil, errors.New("this build has no pdfium: build with -tags pdfium (EMON_GO_LIVE=1 kin/scripts/emon-go-build)")
}
