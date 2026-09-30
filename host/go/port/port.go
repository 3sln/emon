// The Go half of emon's host ports. See the Rust half for the reasoning.
//
// @kin:link:ns: emon.port

package port

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

// ------------------------------------------------------------------ the Blob

// @kin:link:form:blob-len: {:template "port.BlobLen({0})" :import "emon/port" :arity 1}
//
// `:import` IS GO'S ALONE: Rust and the two managed hosts reach the port by
// path or qualified name; Go's header must name the package.
func BlobLen(b []byte) int32 {
	return int32(len(b))
}

// @kin:link:form:blob-u8: {:template "port.BlobU8({0}, {1})" :import "emon/port" :arity 2}
func BlobU8(b []byte, i int32) int32 {
	return int32(b[i])
}

// @kin:link:form:blob-u16: {:template "port.BlobU16({0}, {1})" :import "emon/port" :arity 2}
func BlobU16(b []byte, i int32) int32 {
	return int32(b[i])<<8 | int32(b[i+1])
}

// @kin:link:form:blob-u32: {:template "port.BlobU32({0}, {1})" :import "emon/port" :arity 2}
func BlobU32(b []byte, i int32) int32 {
	return int32(uint32(b[i])<<24 | uint32(b[i+1])<<16 | uint32(b[i+2])<<8 | uint32(b[i+3]))
}

// @kin:link:form:blob-u16le: {:template "port.BlobU16Le({0}, {1})" :import "emon/port" :arity 2}
func BlobU16Le(b []byte, i int32) int32 {
	return int32(b[i+1])<<8 | int32(b[i])
}

// @kin:link:form:blob-u32le: {:template "port.BlobU32Le({0}, {1})" :import "emon/port" :arity 2}
func BlobU32Le(b []byte, i int32) int32 {
	return int32(uint32(b[i+3])<<24 | uint32(b[i+2])<<16 | uint32(b[i+1])<<8 | uint32(b[i]))
}

// ------------------------------------------------------------------ the Cells
type Cells struct {
	V []int32
}

func NewCells() *Cells {
	return &Cells{}
}

// @kin:link:form:cells-len: {:template "port.CellsLen({0})" :import "emon/port" :arity 1}
func CellsLen(c *Cells) int32 {
	return int32(len(c.V))
}

// @kin:link:form:cells-at: {:template "port.CellsAt({0}, {1})" :import "emon/port" :arity 2}
func CellsAt(c *Cells, i int32) int32 {
	return c.V[i]
}

// @kin:link:form:cells-set: {:template "port.CellsSet({0}, {1}, {2})" :import "emon/port" :arity 3}
func CellsSet(c *Cells, i int32, v int32) {
	c.V[i] = v
}

// @kin:link:form:cells-push: {:template "port.CellsPush({0}, {1})" :import "emon/port" :arity 2}
func CellsPush(c *Cells, v int32) int32 {
	c.V = append(c.V, v)
	return int32(len(c.V) - 1)
}

// @kin:link:form:cells-drop: {:template "port.CellsDrop({0}, {1})" :import "emon/port" :arity 2}
func CellsDrop(c *Cells, n int32) {
	c.V = c.V[:n]
}

// @kin:link:form:cells-grow: {:template "port.CellsGrow({0}, {1}, {2})" :import "emon/port" :arity 3}
//
// Appends n copies of v (what a loop of `cells-push` does, in one call).
func CellsGrow(c *Cells, n int32, v int32) {
	old := len(c.V)
	c.V = append(c.V, make([]int32, n)...)
	if v != 0 {
		for i := old; i < len(c.V); i++ {
			c.V[i] = v
		}
	}
}

// @kin:link:form:cells-bytes: {:template "port.CellsBytes({0})" :import "emon/port" :arity 1}
//
// A Blob VIEW of a Cells of bytes: a snapshot (see the Rust half).
func CellsBytes(c *Cells) []byte {
	b := make([]byte, len(c.V))
	for i, v := range c.V {
		b[i] = byte(v)
	}
	return b
}

// ------------------------------------------------------------------ the Bitmap
//
// An opaque 8-bit pixel buffer (see the Rust half for the contract): Ch bytes
// per pixel, row-major, top-left, Stride bytes per row. The check is on the
// byte index, not on each coordinate.
type Bitmap struct {
	W, H, Stride, Ch int32
	D                []byte
}

// The host's way in: decoded pixels, tightly packed.
func BitmapFromBytes(w, h, ch int32, d []byte) *Bitmap {
	if len(d) != int(w)*int(h)*int(ch) {
		panic("bitmap size")
	}
	return &Bitmap{W: w, H: h, Stride: w * ch, Ch: ch, D: d}
}

// @kin:link:form:bitmap-new: {:template "port.BitmapNew({0}, {1}, {2})" :import "emon/port" :arity 3}
func BitmapNew(w, h, ch int32) *Bitmap {
	return BitmapFromBytes(w, h, ch, make([]byte, int(w)*int(h)*int(ch)))
}

// @kin:link:form:bitmap-w: {:template "port.BitmapW({0})" :import "emon/port" :arity 1}
func BitmapW(b *Bitmap) int32 {
	return b.W
}

// @kin:link:form:bitmap-h: {:template "port.BitmapH({0})" :import "emon/port" :arity 1}
func BitmapH(b *Bitmap) int32 {
	return b.H
}

// @kin:link:form:bitmap-stride: {:template "port.BitmapStride({0})" :import "emon/port" :arity 1}
func BitmapStride(b *Bitmap) int32 {
	return b.Stride
}

// @kin:link:form:bitmap-channels: {:template "port.BitmapChannels({0})" :import "emon/port" :arity 1}
func BitmapChannels(b *Bitmap) int32 {
	return b.Ch
}

// @kin:link:form:bitmap-u8: {:template "port.BitmapU8({0}, {1}, {2}, {3})" :import "emon/port" :arity 4}
func BitmapU8(b *Bitmap, x, y, c int32) int32 {
	return int32(b.D[y*b.Stride+x*b.Ch+c])
}

// @kin:link:form:bitmap-set-u8: {:template "port.BitmapSetU8({0}, {1}, {2}, {3}, {4})" :import "emon/port" :arity 5}
func BitmapSetU8(b *Bitmap, x, y, c, v int32) {
	b.D[y*b.Stride+x*b.Ch+c] = byte(v)
}

// Bulk forms (P2.7): the per-pixel loops the spike priced, done in the host.
// See the Rust half for the contracts.

// @kin:link:form:bitmap-histogram: {:template "port.BitmapHistogram({0}, {1}, {2})" :import "emon/port" :arity 3}
func BitmapHistogram(b *Bitmap, hist *Cells, off int32) {
	bins := hist.V[off : off+256]
	w, ch, stride := int(b.W), int(b.Ch), int(b.Stride)
	// Four partial histograms: one makes each increment wait for the previous
	// store when neighbouring pixels share a value (a page is mostly white).
	var part [4][256]int32
	for y := 0; y < int(b.H); y++ {
		row := b.D[y*stride : y*stride+w*ch]
		x := 0
		for ; x+4*ch <= len(row); x += 4 * ch {
			part[0][row[x]]++
			part[1][row[x+ch]]++
			part[2][row[x+2*ch]]++
			part[3][row[x+3*ch]]++
		}
		for ; x < len(row); x += ch {
			part[0][row[x]]++
		}
	}
	for v := 0; v < 256; v++ {
		bins[v] += part[0][v] + part[1][v] + part[2][v] + part[3][v]
	}
}

// @kin:link:form:bitmap-threshold-into: {:template "port.BitmapThresholdInto({0}, {1}, {2})" :import "emon/port" :arity 3}
func BitmapThresholdInto(g *Bitmap, t int32, m *Bitmap) int32 {
	if g.W != m.W || g.H != m.H {
		panic("bitmap size")
	}
	var ink int32
	w := int(g.W)
	for y := int32(0); y < g.H; y++ {
		if g.Ch == 1 && m.Ch == 1 {
			// The common case, row slices: no index arithmetic per pixel.
			gr := g.D[y*g.Stride:][:w]
			mr := m.D[y*m.Stride:][:w]
			for i, v := range gr {
				var on int32
				if int32(v) <= t {
					on = 1
				}
				mr[i] = byte(-on)
				ink += on
			}
		} else {
			for x := int32(0); x < g.W; x++ {
				var on int32
				if int32(g.D[y*g.Stride+x*g.Ch]) <= t {
					on = 1
				}
				m.D[y*m.Stride+x*m.Ch] = byte(-on)
				ink += on
			}
		}
	}
	return ink
}

// @kin:link:form:bitmap-next-nonzero: {:template "port.BitmapNextNonzero({0}, {1}, {2})" :import "emon/port" :arity 3}
func BitmapNextNonzero(b *Bitmap, x, y int32) int32 {
	ch := int(b.Ch)
	row := b.D[int(y)*int(b.Stride):][:int(b.W)*ch]
	for i := int(x); i < int(b.W); i++ {
		if row[i*ch] != 0 {
			return int32(i)
		}
	}
	return b.W
}

// @kin:link:form:bitmap-integral: {:template "port.BitmapIntegral({0}, {1}, {2})" :import "emon/port" :arity 3}
func BitmapIntegral(b *Bitmap, sum *Cells, sq *Cells) {
	w, h, ch, stride := int(b.W), int(b.H), int(b.Ch), int(b.Stride)
	w1 := w + 1
	n := w1 * (h + 1)
	// Only row 0 and column 0 need zeroing: every other cell is written below.
	if cap(sum.V) < n {
		sum.V = make([]int32, n)
	}
	sum.V = sum.V[:n]
	if cap(sq.V) < n {
		sq.V = make([]int32, n)
	}
	sq.V = sq.V[:n]
	for x := 0; x < w1; x++ {
		sum.V[x], sq.V[x] = 0, 0
	}
	for y := 0; y < h; y++ {
		row := b.D[y*stride : y*stride+w*ch]
		ps, pq := sum.V[y*w1:(y+1)*w1], sq.V[y*w1:(y+1)*w1]
		cs, cq := sum.V[(y+1)*w1:(y+2)*w1], sq.V[(y+1)*w1:(y+2)*w1]
		cs[0], cq[0] = 0, 0
		var rs, rq int32
		for x := 0; x < w; x++ {
			v := int32(row[x*ch])
			rs += v
			rq += v * v
			cs[x+1] = ps[x+1] + rs
			cq[x+1] = pq[x+1] + rq
		}
	}
}

// ------------------------------------------------------------------ the Src
//
// An input byte source as kin sees it: a per-unit view over a backing that
// may miss (see the Rust half for the contract). `Src.View` is a fresh view
// of the same backing, for the host's re-run.

// SrcBacking is the host's chunk cache, or plain bytes.
type SrcBacking interface {
	Len() int32
	// At is the byte 0..255, or -1 when it is not resident (the backing records the miss).
	At(i int32) int32
	// Span fills buf from s; false when any of it is not resident (recorded).
	Span(s int32, buf []byte) bool
	// Hint says bytes [s, e) will be wanted. May be ignored.
	Hint(s int32, e int32)
}

type memBacking struct{ v []byte }

func (m *memBacking) Len() int32       { return int32(len(m.v)) }
func (m *memBacking) At(i int32) int32 { return int32(m.v[i]) }
func (m *memBacking) Span(s int32, buf []byte) bool {
	copy(buf, m.v[s:int(s)+len(buf)])
	return true
}
func (m *memBacking) Hint(s int32, e int32) {}

type Src struct {
	B      SrcBacking
	missed bool
}

func NewSrc(b SrcBacking) *Src { return &Src{B: b} }

// View is a fresh view of the same backing: the missed flag is clear.
func (s *Src) View() *Src { return &Src{B: s.B} }

func (s *Src) Missed() bool { return s.missed }

// @kin:link:form:src-len: {:template "port.SrcLen({0})" :import "emon/port" :arity 1}
func SrcLen(s *Src) int32 {
	return s.B.Len()
}

// @kin:link:form:src-u8: {:template "port.SrcU8({0}, {1})" :import "emon/port" :arity 2}
func SrcU8(s *Src, i int32) int32 {
	if i < 0 || i >= s.B.Len() {
		panic(fmt.Sprintf("source index %d is outside 0..%d", i, s.B.Len()))
	}
	v := s.B.At(i)
	if v < 0 {
		s.missed = true
		return 0
	}
	return v
}

// @kin:link:form:src-missed: {:template "port.SrcMissed({0})" :import "emon/port" :arity 1}
func SrcMissed(s *Src) bool {
	return s.missed
}

// @kin:link:form:src-slice: {:template "port.SrcSlice({0}, {1}, {2})" :import "emon/port" :arity 3}
func SrcSlice(s *Src, a int32, b int32) []byte {
	if a < 0 || b < a || b > s.B.Len() {
		panic(fmt.Sprintf("source span %d..%d is outside 0..%d", a, b, s.B.Len()))
	}
	buf := make([]byte, b-a)
	if !s.B.Span(a, buf) {
		s.missed = true
	}
	return buf
}

// @kin:link:form:src-of-cells: {:template "port.SrcOfCells({0})" :import "emon/port" :arity 1}
func SrcOfCells(c *Cells) *Src {
	return NewSrc(&memBacking{v: CellsBytes(c)})
}

// @kin:link:form:src-of-blob: {:template "port.SrcOfBlob({0})" :import "emon/port" :arity 1}
func SrcOfBlob(b []byte) *Src {
	return NewSrc(&memBacking{v: b})
}

// @kin:link:form:src-hint: {:template "port.SrcHint({0}, {1}, {2})" :import "emon/port" :arity 3}
func SrcHint(s *Src, a int32, b int32) {
	s.B.Hint(a, b)
}

// ------------------------------------------------------------------ the Text sink

// @kin:link:form:text-cp: {:template "port.TextCp({0}, {1})" :import "emon/port" :arity 2}
//
// WriteRune writes U+FFFD for an invalid rune (negative, past U+10FFFF, or a
// surrogate) -- the behaviour every host is held to.
func TextCp(s *strings.Builder, cp int32) {
	s.WriteRune(rune(cp))
}

// @kin:link:form:text-ascii: {:template "port.TextAscii({0}, {1})" :import "emon/port" :arity 2}
func TextAscii(s *strings.Builder, lit string) {
	s.WriteString(lit)
}

// @kin:link:form:text-i32: {:template "port.TextI32({0}, {1})" :import "emon/port" :arity 2}
func TextI32(s *strings.Builder, v int32) {
	s.WriteString(strconv.Itoa(int(v)))
}

// ------------------------------------------------------------------ limits

// @kin:link:form:limit-of: {:template "port.LimitOf({0})" :import "emon/port" :arity 1}
func LimitOf(kind int32) int32 {
	name, def := limitSpec(kind)
	if v, ok := os.LookupEnv("EMON_LIMIT_" + name); ok {
		if n, err := strconv.Atoi(v); err == nil {
			return int32(n)
		}
	}
	return def
}

// @kin:link:form:limit-breach: {:template "port.LimitBreach({0}, {1})" :import "emon/port" :arity 2}
func LimitBreach(kind int32, actual int32) int32 {
	name, _ := limitSpec(kind)
	panic(fmt.Sprintf(
		"the %s limit is %d and this input reached %d. Raise it with "+
			"EMON_LIMIT_%s, or find out why the input needs that much -- a limit "+
			"is a bound on emon, not a statement about the document.",
		strings.ToLower(strings.ReplaceAll(name, "_", " ")), LimitOf(kind), actual, name))
}

// @kin:link:form:setting-of: {:template "port.SettingOf({0})" :import "emon/port" :arity 1}
func SettingOf(kind int32) int32 {
	name, def := settingSpec(kind)
	if v, ok := os.LookupEnv("EMON_SETTING_" + name); ok {
		if n, err := strconv.Atoi(v); err == nil {
			return int32(n)
		}
	}
	return def
}

func limitSpec(kind int32) (string, int32) {
	switch kind {
	case 0:
		return "INPUT_BYTES", 1073741824
	case 1:
		return "STREAM_BYTES", 268435456
	case 2:
		return "TOTAL_BYTES", 1073741824
	case 3:
		return "OBJECTS", 10000000
	case 4:
		return "REF_DEPTH", 64
	case 5:
		return "OPERATORS", 50000000
	case 6:
		return "FORM_DEPTH", 32
	case 7:
		return "PATTERN_DEPTH", 8
	case 8:
		return "PAGES", 100000
	case 9:
		return "PIXELS", 268435456
	case 10:
		return "OCR_REQUESTS", 100000
	}
	return "UNKNOWN", 0
}

func settingSpec(kind int32) (string, int32) {
	switch kind {
	case 0:
		return "OCR_MODE", 0
	case 1:
		return "TASKS", 0
	}
	return "UNKNOWN", 0
}
