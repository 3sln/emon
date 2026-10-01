//go:build !pdfium

package main

import "emon/port"

// livePorts: with no pdfium built in there is no live engine.
func livePorts(pdfs []string) (port.PlanFulfiller, func()) { return nil, func() {} }
