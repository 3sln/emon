//go:build !tesseract

package emon

import "errors"

// NewTessOcr is the Tesseract adapter of the `Ocr` port (D211) in a build WITHOUT it: the adapter (`tess.go`) links
// libtesseract through cgo and is built only under the `tesseract` tag (`EMON_GO_LIVE=1 kin/scripts/emon-go-build`). No gate has
// it: a gate replays a transcript.
func NewTessOcr(tessdata string) (Ocr, error) {
	return nil, errors.New("this build has no Tesseract: build with -tags tesseract (EMON_GO_LIVE=1 kin/scripts/emon-go-build)")
}
