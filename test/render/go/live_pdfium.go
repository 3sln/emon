//go:build pdfium

package main

import (
	"fmt"
	"os"

	"emon/pdfium"
	"emon/port"
)

// livePorts: the pdfium Renderer reading each PDF by ReadAt, and a report of how much it read.
func livePorts(pdfs []string) (port.PlanFulfiller, func()) {
	var srcs []*fileSource
	var ins []port.RenderInput
	for _, p := range pdfs {
		f, err := os.Open(p)
		if err != nil {
			panic(err)
		}
		st, _ := f.Stat()
		s := &fileSource{f: f, size: st.Size()}
		srcs = append(srcs, s)
		ins = append(ins, port.RenderInput{Source: s})
	}
	f := port.NewRenderFulfiller(pdfium.New(), ins...)
	return f, func() {
		for i, s := range srcs {
			fmt.Fprintf(os.Stderr, "source %s: %d reads, %d bytes read of %d\n", pdfs[i], s.reads.Load(), s.bytes.Load(), s.size)
		}
	}
}
