// Package pngenc is the Go host's way into the transcript bitmap codec (`emon.pngread`, P10.11, D489; the writer is `emon.png`, D124/D125).
// The codec is kin's: this only makes the buffers and the Bitmap, which kin cannot return. `port` cannot import kin's packages (they
// import it), so the glue lives here, and `planrun` and the tests call it.
package pngenc

import (
	"errors"
	"strings"

	"github.com/3sln/emon/go/internal/pngcheck"
	"github.com/3sln/emon/go/internal/pngread"
	"github.com/3sln/emon/go/port"
)

// Encode writes the bitmap as emon's canonical PNG: 8-bit gray or RGB, no pHYs, bytes identical on every target.
func Encode(b *port.Bitmap) []byte {
	ws, out, z, pal, row := port.NewCells(), port.NewBuf(), port.NewBuf(), port.NewCells(), port.NewCells()
	pngread.PgEncode(b, ws, out, z, pal, row)
	return out.B
}

// Decode reads a transcript's bitmap: a PNG emon wrote (8-bit gray or RGB).
func Decode(p []byte) (*port.Bitmap, error) {
	r, st, out, rows := port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()
	idat, raw := port.NewBuf(), port.NewBuf()
	s := pngread.PgOpen(p, r, st, out, idat, raw)
	if s != 0 {
		return nil, errors.New(msg(s))
	}
	bm := port.BitmapNew(port.CellsAt(r, pngcheck.PrW()), port.CellsAt(r, pngcheck.PrH()), port.CellsAt(r, pngcheck.PrCh()))
	if s = pngread.PgFill(r, raw, rows, bm); s != 0 {
		return nil, errors.New(msg(s))
	}
	return bm, nil
}

func msg(s int32) string {
	var sb strings.Builder
	pngread.PgMsg(s, &sb)
	return sb.String()
}
