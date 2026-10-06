// P6.4 harness, Go arm: kin-generated `emon/norm` then `emon/trace` over the `Bitmap` port. See trace.rs for the arguments and the output.
//
//	trace PAGE W H CH N DPI100 [dump]
package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"emon/evidence"
	"emon/norm"
	"emon/port"
	"emon/trace"
)

func ms(a, b time.Time) float64 { return float64(b.Sub(a).Nanoseconds()) / 1e6 }

func dumpArena(tag string, c *port.Cells, stride int, out *strings.Builder) {
	n := int(port.CellsLen(c)) / stride
	for r := 0; r < n; r++ {
		out.WriteString(tag)
		for f := 0; f < stride; f++ {
			out.WriteByte(' ')
			out.WriteString(strconv.Itoa(int(port.CellsAt(c, int32(r*stride+f)))))
		}
		out.WriteByte('\n')
	}
}

func main() {
	bytes, err := os.ReadFile(os.Args[1])
	if err != nil {
		panic(err)
	}
	ai := func(i int) int32 { v, _ := strconv.Atoi(os.Args[i]); return int32(v) }
	w, h, ch, n, dpi100 := ai(2), ai(3), ai(4), int(ai(5)), ai(6)
	dump := len(os.Args) > 7 && os.Args[7] == "dump"
	src := port.BitmapFromBytes(w, h, ch, bytes)
	gray, binary, mask, labels := port.BitmapNew(w, h, 1), port.BitmapNew(w, h, 1), port.BitmapNew(w, h, 1), port.BitmapNew(w, h, 1)
	ws, sv, aux := port.NewCells(), port.NewCells(), port.NewCells()
	var views, inks, comps, rules, prims, tlines, pts, refs *port.Cells
	rn, ri, cs, vr, pc, sg, mem := port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()
	s1, s2, s3, s4, s5, s6 := port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()
	best := [5]float64{1e18, 1e18, 1e18, 1e18, 1e18}
	var nl, ncomp, nruns int32
	for it := 0; it < n; it++ {
		views, inks, comps, rules, prims, tlines, pts, refs = port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()
		vkind := evidence.VkRender()
		if os.Getenv("TRACE_DECODE") != "" {
			vkind = evidence.VkDecode()
		}
		evidence.VwNew(views, 0, vkind, -1, -1, w, h, dpi100, 0, -1, 0, 0, 0, 0, 0)
		t0 := time.Now()
		nl = norm.NmView(views, inks, 0, 0, src, gray, binary, ws, sv, aux)
		norm.NmLabelInto(src, ws, labels)
		t1 := time.Now()
		bview := norm.NmBinaryView(ws)
		ncomp = trace.TrTrace(views, inks, 0, bview, -1, 0, binary, gray, labels, comps, rules, prims, tlines, pts, refs,
			rn, ri, cs, vr, pc, sg, mem, s1, s2, s3, s4, s5, s6)
		nruns = port.CellsAt(ri, h)
		t2 := time.Now()
		for k := int32(0); k < nl; k++ {
			role := evidence.IkGet(inks, k, evidence.IkfRole())
			if role != evidence.IrBody() {
				norm.NmMaskInto(src, ws, k+1, mask)
				mv := evidence.IkGet(inks, k, evidence.IkfMask())
				trace.TrTrace(views, inks, 0, mv, k, 0, mask, gray, mask, comps, rules, prims, tlines, pts, refs,
					rn, ri, cs, vr, pc, sg, mem, s1, s2, s3, s4, s5, s6)
				if role == evidence.IrLight() || role == evidence.IrBackground() {
					trace.TrTints(views, 0, mv, dpi100, mask, k, inks, prims, refs, s1, s2)
				}
			}
		}
		gview := norm.NmGrayView(ws)
		trace.TrLightfills(views, 0, gview, dpi100, gray, norm.NmPaperGray(ws), mask, inks, prims, refs, s1, s2)
		t3 := time.Now()
		ph := [5]float64{ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t1, t3), ms(t0, t3)}
		for k := 0; k < 5; k++ {
			if ph[k] < best[k] {
				best[k] = ph[k]
			}
		}
	}
	names := []string{"norm", "trace-binary", "trace-masks", "trace", "total"}
	for k := 0; k < 5; k++ {
		fmt.Printf("T %s %.3f\n", names[k], best[k])
	}
	fmt.Printf("S %d %d %d %d %d %d %d %d\n", nl, ncomp, nruns, port.CellsLen(rules)/20, port.CellsLen(prims)/24, port.CellsLen(tlines)/20, port.CellsLen(pts)/3, port.CellsLen(refs)/2)
	for k := int32(0); k < evidence.IkCount(inks); k++ {
		fmt.Printf("I %d %d %d\n", k, evidence.IkGet(inks, k, evidence.IkfCount()), evidence.IkGet(inks, k, evidence.IkfRole()))
	}
	var out strings.Builder
	dumpArena("C", comps, 24, &out)
	dumpArena("R", rules, 20, &out)
	dumpArena("P", prims, 24, &out)
	dumpArena("Q", pts, 3, &out)
	dumpArena("F", refs, 2, &out)
	dumpArena("L", tlines, 20, &out)
	var hv uint64 = 0xcbf29ce484222325
	s := out.String()
	for i := 0; i < len(s); i++ {
		hv ^= uint64(s[i])
		hv *= 0x100000001b3
	}
	fmt.Printf("H %016x\n", hv)
	if dump {
		fmt.Print(s)
	}
}
