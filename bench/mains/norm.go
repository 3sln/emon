// P6.3 harness, Go arm: kin-generated `emon/norm` over the `Bitmap` port. See norm.rs for the arguments and the output.
//
//	norm PAGE W H CH N DPI100 [TRUTH.gray ...]
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
)

func ms(a, b time.Time) float64 { return float64(b.Sub(a).Nanoseconds()) / 1e6 }

func hash(b *port.Bitmap) (int32, int32) {
	o := port.NewCells()
	port.CellsPush(o, 0)
	port.CellsPush(o, 0)
	port.BitmapHash(b, o, 0)
	return port.CellsAt(o, 0), port.CellsAt(o, 1)
}

func main() {
	bytes, err := os.ReadFile(os.Args[1])
	if err != nil {
		panic(err)
	}
	ai := func(i int) int32 { v, _ := strconv.Atoi(os.Args[i]); return int32(v) }
	w, h, ch, n, dpi100 := ai(2), ai(3), ai(4), int(ai(5)), ai(6)
	src := port.BitmapFromBytes(w, h, ch, bytes)
	gray, binary, mask, labels := port.BitmapNew(w, h, 1), port.BitmapNew(w, h, 1), port.BitmapNew(w, h, 1), port.BitmapNew(w, h, 1)
	ws, sv, aux := port.NewCells(), port.NewCells(), port.NewCells()
	var views, inks *port.Cells
	names := []string{"gray", "hist", "layers", "mask", "sauv", "skew", "view"}
	var best [7]float64
	for k := range best {
		best[k] = 1e18
	}
	var nl int32
	for it := 0; it < n; it++ {
		t0 := time.Now()
		port.BitmapGrayInto(src, gray)
		t1 := time.Now()
		norm.NmWsInit(ws)
		port.BitmapHistRgb(src, ws, norm.NmWsHist())
		t2 := time.Now()
		norm.NmLayers(ws, norm.NmInkMerge(), norm.NmInkPaper(), norm.NmInkMin(), norm.NmInkSpike())
		norm.NmRoles(ws, norm.NmInkLight(), norm.NmInkChroma(), norm.NmInkBg())
		t3 := time.Now()
		norm.NmMaskInto(src, ws, 1, mask)
		t4 := time.Now()
		norm.NmSauvola(gray, binary, norm.NmSauvolaR(dpi100), norm.NmSauvolaK(), sv)
		t5 := time.Now()
		norm.NmDeskew(binary, aux, sv)
		t6 := time.Now()
		views, inks = port.NewCells(), port.NewCells()
		evidence.VwNew(views, 0, evidence.VkDecode(), -1, -1, w, h, dpi100, 0, -1, 0, 0, 0, 0, 0)
		nl = norm.NmView(views, inks, 0, 0, src, gray, binary, ws, sv, aux)
		t7 := time.Now()
		ph := [7]float64{ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), ms(t4, t5), ms(t5, t6), ms(t6, t7)}
		for k := 0; k < 7; k++ {
			if ph[k] < best[k] {
				best[k] = ph[k]
			}
		}
	}
	for k := 0; k < 7; k++ {
		fmt.Printf("T %s %.3f\n", names[k], best[k])
	}
	fmt.Printf("S %d %d\n", nl, evidence.VwGet(views, 0, evidence.VwfDeskew()))
	for i := int32(0); i < evidence.IkCount(inks); i++ {
		fmt.Printf("L %d %d %d %d %d %d\n", i+1, evidence.IkGet(inks, i, evidence.IkfCount()), evidence.IkGet(inks, i, evidence.IkfRole()),
			evidence.IkGet(inks, i, evidence.IkfLight()), evidence.IkGet(inks, i, evidence.IkfChroma()), evidence.IkGet(inks, i, evidence.IkfColor()))
	}
	gh, gl := hash(gray)
	bh, bl := hash(binary)
	fmt.Printf("H gray %d %d\n", gh, gl)
	fmt.Printf("H binary %d %d\n", bh, bl)
	norm.NmLabelInto(src, ws, labels)
	for ti, f := range os.Args[7:] {
		t, err := os.ReadFile(f)
		if err != nil {
			panic(err)
		}
		cnt := make([]int64, nl+1)
		var tot int64
		for i, v := range t {
			if v != 0 {
				tot++
				cnt[labels.D[i]]++
			}
		}
		var cs []string
		for _, c := range cnt {
			cs = append(cs, strconv.FormatInt(c, 10))
		}
		fmt.Printf("X %d %d %s\n", ti, tot, strings.Join(cs, " "))
	}
}
