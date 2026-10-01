// The plan/fulfil PROBE DRIVER, Go (DEVLOG P2.5, D089): the twin of
// test/plan/rust/main.rs, and the same flags and output. Test-only: it reads and
// writes the transcript DIRECTORY, which the library (port/plan.go) never does.
//
//	plandriver record DIR [--async] [--chunk=N] [--max-rounds=N]
//	plandriver replay DIR [--async] [--chunk=N] [--max-rounds=N]
//
// `--async` fulfils on goroutines that finish in a pseudo-random order.
package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"emon/evidence"
	"emon/plan"
	"emon/planprobe"
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

func input0() []byte {
	b := make([]byte, 2000)
	for i := range b {
		b[i] = byte(i*7 + 1)
	}
	return b
}

func input1() []byte {
	b := make([]byte, 64)
	for i := range b {
		b[i] = byte(i*3 + 2)
	}
	return b
}

func toVec(c *port.Cells) []int32 {
	n := port.CellsLen(c)
	v := make([]int32, n)
	for i := int32(0); i < n; i++ {
		v[i] = port.CellsAt(c, i)
	}
	return v
}

func answer(c *port.Cells, bm *port.Bitmap) (port.PlanAnswer, error) {
	return port.PlanAnswer{Rec: toVec(c), Bitmap: bm}, nil
}

// THE FAKE: what a real Renderer / ImageDecode / Ocr would be, deterministic and
// the same in every host (the Rust driver has its twin).
func fake(req port.PlanRequest, bm port.PlanBitmaps) (port.PlanAnswer, error) {
	id := req.ID()
	c := port.NewCells()
	switch req.Kind() {
	case port.PlanKindRender:
		w, h := int32(64), int32(48)
		var px []byte
		for y := int32(0); y < h; y++ {
			for x := int32(0); x < w; x++ {
				px = append(px, byte(x*3+y*5+id*7))
			}
		}
		plan.AnRender(c, id, w, h, 1, req.Arg(2))
		return answer(c, port.BitmapFromBytes(w, h, 1, px))
	case port.PlanKindDecode:
		w, h := int32(32), int32(16)
		var px []byte
		for y := int32(0); y < h; y++ {
			for x := int32(0); x < w; x++ {
				for ch := int32(0); ch < 3; ch++ {
					px = append(px, byte(x*5+y*3+ch*50+id*11))
				}
			}
		}
		plan.AnDecode(c, id, w, h, 3, 7200, 0, 1)
		return answer(c, port.BitmapFromBytes(w, h, 3, px))
	case port.PlanKindOcr:
		b, ok := bm[req.Arg(0)]
		if !ok {
			plan.AnFail(c, port.PlanKindOcr, id, port.PlanStatusFailed)
			return answer(c, nil)
		}
		at := plan.AnOcr(c, id)
		type word struct {
			x, y, w, h, conf, line int32
			text                   []int32
		}
		words := []word{
			{4, 2, b.W / 2, 10, 900 + int32(b.D[0])%50, 0, []int32{72, 101, 108, 108, 111}},
			{b.W/2 + 6, 2, 20, 10, 871, 0, []int32{119, 246, 114, 108, 100}},
			{4, 16, 6, 10, 640, 1, []int32{8364, 128512}},
		}
		for _, w := range words {
			wd := plan.AnOcrWord(c, at, w.x, w.y, w.w, w.h, w.conf, w.line, 0)
			for _, cp := range w.text {
				plan.AnOcrCp(c, wd, cp)
			}
		}
		plan.AnOcrEnd(c, at)
		return answer(c, nil)
	}
	return port.PlanAnswer{}, &port.PlanError{Name: "fulfil-failed", Msg: fmt.Sprintf("the fake has no kind %d", req.Kind())}
}

type chunked struct {
	base  *port.Src
	chunk int32
	round *int32
}

func (c *chunked) Take(round int32) []port.PlanRange {
	*c.round = round
	n := port.SrcCtl(c.base, 4, 0)
	if n == 0 {
		return nil
	}
	// Informative, not exact: the test source records chunks, not ranges.
	return []port.PlanRange{{Input: 0, Offset: 0, Length: n * c.chunk}}
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
	if len(args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: plandriver record|replay DIR [--async] [--chunk=N] [--max-rounds=N]")
		os.Exit(2)
	}
	mode, dir := args[1], args[2]
	useAsync := false
	chunk := int32(0)
	cfg := port.DefaultPlanConfig()
	for _, a := range args[3:] {
		switch {
		case a == "--async":
			useAsync = true
		case strings.HasPrefix(a, "--chunk="):
			n, _ := strconv.Atoi(strings.TrimPrefix(a, "--chunk="))
			chunk = int32(n)
		case strings.HasPrefix(a, "--max-rounds="):
			n, _ := strconv.Atoi(strings.TrimPrefix(a, "--max-rounds="))
			cfg.MaxRounds = int32(n)
		default:
			fmt.Fprintln(os.Stderr, "unknown flag", a)
			os.Exit(2)
		}
	}
	// The kin numbers the host copies, held equal (a host that drifts fails here).
	must := func(ok bool, what string) {
		if !ok {
			panic("the host and emon.plan disagree on " + what)
		}
	}
	must(plan.PkRender() == port.PlanKindRender && plan.PkDecode() == port.PlanKindDecode &&
		plan.PkOcr() == port.PlanKindOcr && plan.PkBytes() == port.PlanKindBytes, "the kinds")
	must(plan.UsDone() == port.PlanUnitDone && plan.UsRequests() == port.PlanUnitRequests &&
		plan.UsNeedBytes() == port.PlanUnitNeedBytes && plan.PsOk() == port.PlanStatusOk, "the statuses")

	in0 := input0()
	inputs := [][2]int32{{0, int32(len(in0))}, {1, int32(len(input1()))}}
	var base *port.Src
	if chunk > 0 {
		base = port.SrcChunked(in0, chunk, 0)
	} else {
		base = port.SrcOfBlob(in0)
	}

	transcript := &port.Transcript{Inputs: inputs}
	var replayer *port.Replayer
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
		t, err := port.TranscriptFromFiles(files)
		if err == nil {
			err = t.CheckInputs(inputs)
		}
		if err != nil {
			fail(err)
		}
		transcript = t
		replayer = port.NewReplayer(t)
	}

	var evBuf strings.Builder
	unit := func(an, rq *port.Cells) int32 {
		view := base.View()
		g, w, l, p, i, co, ow, al, et, sp := port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(),
			port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()
		st := planprobe.PpUnit(view, an, rq, g, w, l, p, i, co, ow, al, et, sp)
		if st == port.PlanUnitDone {
			evBuf.Reset()
			evidence.EvEmit(&evBuf, 0, g, w, l, p, i, co, ow, al, et, sp)
		}
		return st
	}

	round := int32(0)
	var misses port.PlanMisses = port.PlanNoMisses{}
	if chunk > 0 {
		misses = &chunked{base: base, chunk: chunk, round: &round}
	}
	bytesF := &port.PlanSync{F: func(req port.PlanRequest, _ port.PlanBitmaps) (port.PlanAnswer, error) {
		if chunk > 0 {
			port.SrcCtl(base, 0, round)
		}
		c := port.NewCells()
		plan.AnBytes(c, req.ID(), req.Arg(1), req.Arg(2))
		return answer(c, nil)
	}}

	recording := mode == "record"
	var stats port.PlanStats
	var err error
	switch {
	case recording && !useAsync:
		stats, err = port.PlanRun(cfg, unit, &port.PlanSync{F: fake}, bytesF, misses, transcript)
	case recording:
		f := &port.PlanGoroutines{F: fake, Delay: func(r port.PlanRequest) int { return int((r.ID()*7919)%13) * 300 }}
		stats, err = port.PlanRun(cfg, unit, f, bytesF, misses, transcript)
	default:
		stats, err = port.PlanRun(cfg, unit, replayer, bytesF, misses, nil)
	}
	if err != nil {
		fail(err)
	}
	if replayer != nil && replayer.Unused() != 0 {
		fail(&port.PlanError{Name: "transcript-bad", Msg: fmt.Sprintf("%d recorded entries were never asked for", replayer.Unused())})
	}
	fmt.Fprintf(os.Stderr, "stats: attempts=%d rounds=%d requests=%d fetch-rounds=%d bytes-requests=%d\n",
		stats.Attempts, stats.Rounds, stats.Requests, stats.FetchRounds, stats.BytesRequests)

	fmt.Printf("evidence: %s\n", evBuf.String())
	for _, e := range transcript.Entries {
		if b := e.Bitmap; b != nil {
			fmt.Printf("bitmap %d: %dx%dx%d %08x\n", e.Req[2], b.W, b.H, b.Ch, fnv(b.D))
		}
	}
	files := transcript.ToFiles()
	for _, f := range files {
		fmt.Printf("file %s: %d bytes %08x\n", f.Name, len(f.Bytes), fnv(f.Bytes))
	}
	if recording {
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
