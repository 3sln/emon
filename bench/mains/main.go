// P2.7 harness, Go arm: kin-generated `emon/raster` over the `Bitmap` port.
//
//	main PAGE W H N [port|bulk]
package main

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

	"emon/port"
	"emon/raster"
)

func ms(a, b time.Time) float64 { return float64(b.Sub(a).Nanoseconds()) / 1e6 }

// "direct" mode: the same three steps hand-written in Go over the raw slices
// (no port call, no Cells), to separate what the port costs Go from what Go's
// scalar code costs. The labelling stays kin's.
func directHistogram(g *port.Bitmap, hist *port.Cells) {
	hist.V = hist.V[:0]
	for i := 0; i < 256; i++ {
		hist.V = append(hist.V, 0)
	}
	for _, v := range g.D {
		hist.V[v]++
	}
}

func directBinarise(g, m *port.Bitmap, thr int32) {
	md := m.D
	for i, v := range g.D {
		if int32(v) <= thr {
			md[i] = 255
		} else {
			md[i] = 0
		}
	}
}

func directAlloc(labels *port.Cells, n int32) {
	labels.V = labels.V[:0]
	labels.V = append(labels.V, make([]int32, n)...)
}

func main() {
	bytes, err := os.ReadFile(os.Args[1])
	if err != nil {
		panic(err)
	}
	w64, _ := strconv.Atoi(os.Args[2])
	h64, _ := strconv.Atoi(os.Args[3])
	n, _ := strconv.Atoi(os.Args[4])
	skip := len(os.Args) > 5 && os.Args[5] == "skip"
	bulk := skip || (len(os.Args) > 5 && os.Args[5] == "bulk")
	direct := len(os.Args) > 5 && os.Args[5] == "direct"
	w, h := int32(w64), int32(h64)
	g := port.BitmapFromBytes(w, h, 1, bytes)
	m := port.BitmapNew(w, h, 1)
	hist, labels, parent, area, stats := port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()
	best := [6]float64{1e18, 1e18, 1e18, 1e18, 1e18, 1e18}
	var thr int32
	for it := 0; it < n; it++ {
		t0 := time.Now()
		if direct {
			directHistogram(g, hist)
		} else if bulk {
			raster.RasHistogramBulk(g, hist)
		} else {
			raster.RasHistogram(g, hist)
		}
		t1 := time.Now()
		thr = raster.RasOtsu(hist, w*h)
		t2 := time.Now()
		if direct {
			directBinarise(g, m, thr)
		} else if bulk {
			raster.RasBinariseBulk(g, m, thr)
		} else {
			raster.RasBinarise(g, m, thr)
		}
		t3 := time.Now()
		if direct {
			directAlloc(labels, w*h)
		} else if bulk {
			raster.RasAllocBulk(labels, w*h)
		} else {
			raster.RasAlloc(labels, w*h)
		}
		t4 := time.Now()
		if skip {
			raster.RasLabelSkip(m, labels, parent, area, stats)
		} else {
			raster.RasLabel(m, labels, parent, area, stats)
		}
		t5 := time.Now()
		ph := [6]float64{ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), ms(t4, t5), ms(t0, t5)}
		for k := range ph {
			if ph[k] < best[k] {
				best[k] = ph[k]
			}
		}
	}
	names := [6]string{"hist", "otsu", "bin", "alloc", "label", "total"}
	for k := range names {
		fmt.Printf("T %s %.3f\n", names[k], best[k])
	}
	var s strings.Builder
	for i := int32(0); i < port.CellsLen(stats); i++ {
		s.WriteString(" " + strconv.Itoa(int(port.CellsAt(stats, i))))
	}
	fmt.Printf("S %d%s\n", thr, s.String())
}
