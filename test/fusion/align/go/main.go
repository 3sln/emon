// The alignment DRIVER, Go (DEVLOG P7.1, D252-D254): the REPLAY twin of test/fusion/align/rust/main.rs, with the same output.
// Go never records: pdfium and Tesseract are the Rust host's bindings here (a transcript recorded there replays here to the
// identical correspondence groups).
//
//	aligndrv replay DIR PDF
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"emon/alignprobe"
	"emon/plan"
	_ "emon/pngenc"
	"emon/port"
)

func fnv(b []byte) uint32 {
	h := uint32(0x811C9DC5)
	for _, x := range b {
		h ^= uint32(x)
		h *= 16777619
	}
	return h
}

func toVec(c *port.Cells) []int32 {
	n := port.CellsLen(c)
	v := make([]int32, n)
	for i := int32(0); i < n; i++ {
		v[i] = port.CellsAt(c, i)
	}
	return v
}

func fail(err error) {
	name := "error"
	if pe, ok := err.(*port.PlanError); ok {
		name = pe.Name
	}
	fmt.Printf("error: %s\n", name)
	fmt.Fprintln(os.Stderr, err)
	os.Exit(3)
}

func cells(n int) []*port.Cells {
	c := make([]*port.Cells, n)
	for i := range c {
		c[i] = port.NewCells()
	}
	return c
}

func main() {
	args := os.Args
	if len(args) < 4 || args[1] != "replay" {
		fmt.Fprintln(os.Stderr, "usage: aligndrv replay DIR PDF")
		os.Exit(2)
	}
	dir, pdf := args[2], args[3]
	pdfBytes, err := os.ReadFile(pdf)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	if plan.PkOcr() != port.PlanKindOcr {
		panic("the host and emon.plan disagree on the kinds")
	}
	inputs := [][2]int32{{0, int32(len(pdfBytes))}}
	base := port.SrcOfBlob(pdfBytes)

	files := map[string][]byte{}
	ents, err := os.ReadDir(dir)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	for _, e := range ents {
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
		files[e.Name()] = b
	}
	t, err := port.TranscriptFromFiles(files)
	if err == nil {
		err = t.CheckInputs(inputs)
	}
	if err != nil {
		fail(err)
	}
	replayer := port.NewReplayer(t)

	var dump string
	unit := func(an, rq *port.Cells) int32 {
		view := base.View()
		c := cells(51)
		loc := port.NewCells()
		var d strings.Builder
		st := alignprobe.ApUnit(view, an, rq, loc, &d, 1,
			c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11], c[12], c[13], c[14], c[15], c[16], c[17], c[18], c[19], c[20], c[21], c[22], c[23],
			c[24], c[25], c[26], c[27], c[28], c[29], c[30], c[31], c[32], c[33], c[34], c[35], c[36], c[37], c[38], c[39], c[40], c[41], c[42], c[43], c[44], c[45], c[46], c[47], c[48], c[49], c[50])
		if st == port.PlanUnitDone {
			dump = d.String()
		}
		return st
	}
	bytesF := &port.PlanSync{F: func(req port.PlanRequest, _ port.PlanBitmaps) (port.PlanAnswer, error) {
		c := port.NewCells()
		plan.AnBytes(c, req.ID(), req.Arg(1), req.Arg(2))
		return port.PlanAnswer{Rec: toVec(c)}, nil
	}}
	stats, err := port.PlanRun(port.DefaultPlanConfig(), unit, replayer, bytesF, port.PlanNoMisses{}, nil)
	if err != nil {
		fail(err)
	}
	if replayer.Unused() != 0 {
		fail(&port.PlanError{Name: "transcript-bad", Msg: fmt.Sprintf("%d recorded entries were never asked for", replayer.Unused())})
	}
	fmt.Fprintf(os.Stderr, "stats: attempts=%d rounds=%d requests=%d\n", stats.Attempts, stats.Rounds, stats.Requests)

	fmt.Print(dump)
	for _, f := range t.ToFiles() {
		fmt.Printf("file %s: %d bytes %08x\n", f.Name, len(f.Bytes), fnv(f.Bytes))
	}
}
