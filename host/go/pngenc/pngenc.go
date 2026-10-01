// Package pngenc is the bridge from the host's transcript writer to emon's
// canonical PNG encoder (`emon.png`, D124/D125). kin's generated packages
// import `port`, so `port` cannot import them; this package can, and registers
// the encoder with `port.PngEncoder` when it is linked in:
//
//	import _ "emon/pngenc"
package pngenc

import (
	"emon/png"
	"emon/port"
)

func init() { port.PngEncoder = Encode }

// Encode writes the bitmap as emon's canonical PNG: 8-bit gray or RGB, no
// pHYs, bytes identical on every target.
func Encode(b *port.Bitmap) []byte {
	ws, out, z, pal, row := port.NewCells(), port.NewBuf(), port.NewBuf(), port.NewCells(), port.NewCells()
	ct := int32(0)
	if b.Ch == 3 {
		ct = 2
	}
	png.PngBegin(ws, out, z, b.W, b.H, ct, 8, 0, pal, 0, 0)
	n := int(b.W) * int(b.Ch)
	for y := 0; y < int(b.H); y++ {
		port.CellsDrop(row, 0)
		for _, v := range b.D[y*int(b.Stride) : y*int(b.Stride)+n] {
			port.CellsPush(row, int32(v))
		}
		png.PngRow(ws, out, z, row, 0)
	}
	png.PngEnd(ws, out, z)
	return out.B
}
