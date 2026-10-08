// The Renderer DRIVER, Go (DEVLOG P6.2, D127): the twin of test/render/rust/main.rs, with the same output.
//
//	renderdrv replay DIR --pdf P... --req SPEC...     the gate: answer from DIR (no pdfium needed)
//	renderdrv record DIR --pdf P... --req SPEC...     live: build tag `pdfium` (record_pdfium.go), cgo
//
// SPEC is `input:page:dpi:layer` or, for a crop, `input:page:dpi:layer:px,py,pw,ph`.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"

	"emon/plan"
	_ "emon/pngenc"
	"emon/planrun"
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

func fail(err error) {
	name := "error"
	if pe, ok := err.(*port.PlanError); ok {
		name = pe.Name
	}
	fmt.Printf("error: %s\n", name)
	fmt.Fprintln(os.Stderr, err)
	os.Exit(3)
}

type spec struct {
	id, input, page, dpi, layer int32
	clip                        *[4]int32
}

func parseSpec(id int32, s string) spec {
	p := strings.Split(s, ":")
	n := func(i int) int32 {
		v, err := strconv.Atoi(p[i])
		if err != nil {
			panic(err)
		}
		return int32(v)
	}
	sp := spec{id: id, input: n(0), page: n(1), dpi: n(2), layer: n(3)}
	if len(p) > 4 {
		var c [4]int32
		for i, x := range strings.Split(p[4], ",") {
			v, _ := strconv.Atoi(x)
			c[i] = int32(v)
		}
		sp.clip = &c
	}
	return sp
}

// fileSource is a file read by ReadAt: random access, no whole-file buffer, counting what was asked of it.
type fileSource struct {
	f     *os.File
	size  int64
	reads atomic.Int64
	bytes atomic.Int64
}

func (s *fileSource) Size() int64 { return s.size }
func (s *fileSource) ReadAt(p []byte, off int64) (int, error) {
	n, err := s.f.ReadAt(p, off)
	s.reads.Add(1)
	s.bytes.Add(int64(n))
	return n, err
}

func main() {
	args := os.Args
	if len(args) < 3 || (args[1] != "replay" && args[1] != "record") {
		fmt.Fprintln(os.Stderr, "usage: renderdrv replay|record DIR --pdf P... --req SPEC...")
		os.Exit(2)
	}
	mode, dir := args[1], args[2]
	var pdfs []string
	var specs []spec
	for i := 3; i < len(args); i += 2 {
		switch args[i] {
		case "--pdf":
			pdfs = append(pdfs, args[i+1])
		case "--req":
			specs = append(specs, parseSpec(int32(len(specs))+1, args[i+1]))
		default:
			panic("argument " + args[i])
		}
	}
	if plan.PkRender() != port.PlanKindRender || plan.PkCrop() != port.PlanKindCrop {
		panic("the host and emon.plan disagree on the kinds")
	}
	var inputs [][2]int32
	for i, p := range pdfs {
		st, err := os.Stat(p)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
		inputs = append(inputs, [2]int32{int32(i), int32(st.Size())})
	}

	unit := func(an, rq *port.Cells, _ *port.BitmapSet, _ *port.Buf) int32 {
		missing := false
		for _, s := range specs {
			if plan.RecFind(an, s.id) < 0 {
				missing = true
				if s.clip == nil {
					plan.RqRender(rq, s.id, s.input, s.page, s.dpi, s.layer)
				} else {
					plan.RqCrop(rq, s.id, s.input, s.page, s.dpi, s.layer, s.clip[0], s.clip[1], s.clip[2], s.clip[3])
				}
			}
		}
		if missing {
			return port.PlanUnitRequests
		}
		return port.PlanUnitDone
	}
	bytesF := &port.PlanSync{F: func(req port.PlanRequest, _ port.PlanBitmaps) (port.PlanAnswer, error) {
		return port.PlanAnswer{}, &port.PlanError{Name: "fulfil-failed", Msg: "this driver reads no bytes through the loop"}
	}}

	t := &port.Transcript{Inputs: inputs}
	var replayer *port.Replayer
	var rec *port.Transcript
	var ports port.PlanFulfiller
	if mode == "replay" {
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
		t, err = port.TranscriptFromFiles(files)
		if err == nil {
			err = t.CheckInputs(inputs)
		}
		if err != nil {
			fail(err)
		}
		replayer = port.NewReplayer(t)
		ports = replayer
	} else {
		rec = t
		var report func()
		ports, report = livePorts(pdfs)
		if ports == nil {
			fmt.Fprintln(os.Stderr, "record needs -tags pdfium")
			os.Exit(2)
		}
		defer report()
	}
	stats, err := planrun.Run(port.DefaultPlanConfig(), unit, ports, bytesF, port.PlanNoMisses{}, rec)
	if err != nil {
		fail(err)
	}
	if replayer != nil && replayer.Unused() != 0 {
		fail(&port.PlanError{Name: "transcript-bad", Msg: fmt.Sprintf("%d recorded entries were never asked for", replayer.Unused())})
	}
	fmt.Fprintf(os.Stderr, "stats: attempts=%d rounds=%d requests=%d\n", stats.Attempts, stats.Rounds, stats.Requests)

	for _, e := range t.Entries {
		kind := "other"
		switch e.Req[1] {
		case port.PlanKindRender:
			kind = "render"
		case port.PlanKindCrop:
			kind = "crop"
		}
		fmt.Printf("entry %d %s: req %s ans %s\n", e.Req[2], kind, ints(e.Req), ints(e.Ans))
		if b := e.Bitmap; b != nil {
			fmt.Printf("bitmap %d: %dx%dx%d %08x\n", e.Req[2], b.W, b.H, b.Ch, fnv(b.D))
		}
	}
	files := t.ToFiles()
	for _, f := range files {
		fmt.Printf("file %s: %d bytes %08x\n", f.Name, len(f.Bytes), fnv(f.Bytes))
	}
	if mode == "record" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			panic(err)
		}
		for _, f := range files {
			if err := os.WriteFile(filepath.Join(dir, f.Name), f.Bytes, 0o644); err != nil {
				panic(err)
			}
		}
	}
}

// ints spells a record the way Rust's `{:?}` spells a Vec<i32>: [1, 2, 3].
func ints(v []int32) string {
	s := make([]string, len(v))
	for i, x := range v {
		s[i] = strconv.Itoa(int(x))
	}
	return "[" + strings.Join(s, ", ") + "]"
}
