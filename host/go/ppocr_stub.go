//go:build !ppocr || !cgo || (!darwin && !linux)

package emon

import (
	"emon/port"
	"errors"
)

// PpOcr retains the same API when native dynamic loading is unavailable.
type PpOcr struct{}

func NewPpOcr(dir string, incumbent Ocr) (*PpOcr, error) {
	return nil, errors.New("ppocr-runtime-missing")
}
func (p *PpOcr) Close()                {}
func (p *PpOcr) Producer() [][2]string { return nil }
func (p *PpOcr) Fulfil(req port.PlanRequest, bitmaps port.PlanBitmaps, keep bool) (port.PlanAnswer, error) {
	return port.PlanAnswer{}, errors.New("ppocr-runtime-missing")
}
