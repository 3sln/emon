// The OCR spike DRIVER, Go (DEVLOG P2.6, D095): the REPLAY twin of
// test/ocr/rust/main.rs, with the same output. Go never records: Tesseract is the
// Rust host's binding (the spike's point is that a transcript recorded there
// replays here to the identical evidence).
//
//	ocrdriver replay DIR PDF
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"emon/evidence"
	"emon/ocrprobe"
	"emon/plan"
	"emon/port"
)

// The evidence arenas travel in four bundles, held by the host as structs (SPEC 8.4.6, D096).
type EvShared struct{ Epages, Views, Pts, Refs, Etext, Feats *port.Cells }
func NewEvShared() EvShared {
	return EvShared{port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()}
}
type EvText struct{ Glyphs, Words, Lines, Marks, Fonts, Paths, Images *port.Cells }
func NewEvText() EvText {
	return EvText{port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()}
}
type EvOcr struct{ Oruns, Oblocks, Olines, Owords, Osyms, Oalts *port.Cells }
func NewEvOcr() EvOcr {
	return EvOcr{port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()}
}
type EvTrace struct{ Inks, Components, Rules, Prims, Tlines *port.Cells }
func NewEvTrace() EvTrace {
	return EvTrace{port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()}
}

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

func main() {
	args := os.Args
	if len(args) < 4 || args[1] != "replay" {
		fmt.Fprintln(os.Stderr, "usage: ocrdriver replay DIR PDF")
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

	var evBuf strings.Builder
	unit := func(an, rq *port.Cells) int32 {
		view := base.View()
		sh, tx, oc, tr := NewEvShared(), NewEvText(), NewEvOcr(), NewEvTrace()
		st := ocrprobe.OpUnit(view, an, rq, sh.Epages, sh.Views, sh.Pts, sh.Refs, sh.Etext, sh.Feats, tx.Glyphs, tx.Words, tx.Lines, tx.Marks, tx.Fonts, tx.Paths, tx.Images, oc.Oruns, oc.Oblocks, oc.Olines, oc.Owords, oc.Osyms, oc.Oalts, tr.Inks, tr.Components, tr.Rules, tr.Prims, tr.Tlines)
		if st == port.PlanUnitDone {
			evBuf.Reset()
			evidence.EvEmit(&evBuf, 0, sh.Epages, sh.Views, sh.Pts, sh.Refs, sh.Etext, sh.Feats, tx.Glyphs, tx.Words, tx.Lines, tx.Marks, tx.Fonts, tx.Paths, tx.Images, oc.Oruns, oc.Oblocks, oc.Olines, oc.Owords, oc.Osyms, oc.Oalts, tr.Inks, tr.Components, tr.Rules, tr.Prims, tr.Tlines)
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

	fmt.Printf("evidence: %s\n", evBuf.String())
	for _, e := range t.Entries {
		if b := e.Bitmap; b != nil {
			fmt.Printf("bitmap %d: %dx%dx%d %08x\n", e.Req[2], b.W, b.H, b.Ch, fnv(b.D))
		}
	}
	for _, f := range t.ToFiles() {
		fmt.Printf("file %s: %d bytes %08x\n", f.Name, len(f.Bytes), fnv(f.Bytes))
	}
}
