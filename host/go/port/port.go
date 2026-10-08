// The Go half of emon's host ports. See the Rust half for the reasoning.
//
// @kin:link:ns: emon.port

package port

import (
	"crypto/aes"
	"crypto/md5"
	"crypto/sha256"
	"crypto/sha512"
	"emon/limits"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
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

// @kin:link:form:cells-swap: {:template "port.CellsSwap({0}, {1})" :import "emon/port" :arity 2}
//
// Exchanges the CONTENTS of two arenas, O(1): each stays where its owner keeps
// it (a context field), only what it holds changes hands (D362).
func CellsSwap(a *Cells, b *Cells) {
	a.V, b.V = b.V, a.V
}

// @kin:link:form:cells-copy-into: {:template "port.CellsCopyInto({0}, {1})" :import "emon/port" :arity 2}
//
// `(cells-copy-into dst src)`: dst becomes a copy of src, whatever it held
// before. The two are different arenas.
func CellsCopyInto(dst *Cells, src *Cells) {
	dst.V = append(dst.V[:0], src.V...)
}

// @kin:link:form:cells-free: {:template "port.CellsFree({0})" :import "emon/port" :arity 1}
//
// Empties the arena AND gives its storage back (`cells-drop` to 0 keeps it).
func CellsFree(c *Cells) {
	c.V = nil
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

// -------------------------------------------------------------------- the Buf
//
// A growable run of bytes (see the Rust half for the contract).
type Buf struct {
	B []byte
}

func NewBuf() *Buf {
	return &Buf{}
}

// @kin:link:form:buf-len: {:template "port.BufLen({0})" :import "emon/port" :arity 1}
func BufLen(b *Buf) int32 {
	return int32(len(b.B))
}

// @kin:link:form:buf-at: {:template "port.BufAt({0}, {1})" :import "emon/port" :arity 2}
func BufAt(b *Buf, i int32) int32 {
	return int32(b.B[i])
}

// @kin:link:form:buf-push: {:template "port.BufPush({0}, {1})" :import "emon/port" :arity 2}
func BufPush(b *Buf, v int32) int32 {
	b.B = append(b.B, byte(v))
	return int32(len(b.B))
}

// @kin:link:form:buf-set: {:template "port.BufSet({0}, {1}, {2})" :import "emon/port" :arity 3}
func BufSet(b *Buf, i int32, v int32) {
	b.B[i] = byte(v)
}

// @kin:link:form:buf-truncate: {:template "port.BufTruncate({0}, {1})" :import "emon/port" :arity 2}
func BufTruncate(b *Buf, n int32) {
	if n < 0 || int(n) > len(b.B) {
		panic("BufTruncate out of range")
	}
	b.B = b.B[:n]
}

// @kin:link:form:buf-append-blob: {:template "port.BufAppendBlob({0}, {1}, {2}, {3})" :import "emon/port" :arity 4}
func BufAppendBlob(b *Buf, blob []byte, start int32, end int32) {
	b.B = append(b.B, blob[start:end]...)
}

// @kin:link:form:buf-copy-into: {:template "port.BufCopyInto({0}, {1})" :import "emon/port" :arity 2}
//
// `(buf-copy-into dst src)`: dst becomes a copy of src (see `cells-copy-into`).
func BufCopyInto(dst *Buf, src *Buf) {
	dst.B = append(dst.B[:0], src.B...)
}

// @kin:link:form:buf-blob: {:template "port.BufBlob({0})" :import "emon/port" :arity 1}
//
// The storage itself (a borrow, valid until the next write).
func BufBlob(b *Buf) []byte {
	return b.B
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
	if ov := limitOverrides.Load(); ov != nil {
		if v, ok := (*ov)[kind]; ok {
			return v
		}
	}
	def := limits.LmDefault(kind)
	if v, ok := os.LookupEnv(envName("EMON_LIMIT_", LimitName(kind))); ok {
		if n, err := strconv.Atoi(v); err == nil {
			return int32(n)
		}
	}
	return def
}

// THE TABLE OF LIMITS IS KIN'S (`emon.limits`, P10.10): a limit's name and default are read from it. `emon.limits` is a pure module (it refers no port form)
// so this package can import it.
func kinName(n int32, ch func(int32) int32) string {
	var sb strings.Builder
	for i := int32(0); i < n; i++ {
		sb.WriteRune(rune(ch(i)))
	}
	return sb.String()
}

// LimitName is the name of limit `kind` as kin's table spells it (`input-bytes`); `unknown` for a kind it has no row for.
func LimitName(kind int32) string {
	s := kinName(limits.LmNameLen(kind), func(i int32) int32 { return limits.LmNameCh(kind, i) })
	if s == "" {
		return "unknown"
	}
	return s
}

// envName: `input-bytes` is `EMON_LIMIT_INPUT_BYTES`.
func envName(prefix, name string) string {
	return prefix + strings.ToUpper(strings.ReplaceAll(name, "-", "_"))
}

// Breach is the PAYLOAD OF A LIMIT BREACH (P10.10, D435): which limit, the bound that was in force and the number the input reached. `LimitBreach` panics
// with a `*Breach`; a host recovers it by its TYPE and names the error from `Name`. Nothing reads the words of a message; `Error` is for a person.
type Breach struct {
	Kind, Limit, Actual int32
}

// Name is the limit's name in kin's table.
func (b *Breach) Name() string { return LimitName(b.Kind) }

func (b *Breach) Error() string {
	name := b.Name()
	return fmt.Sprintf(
		"the %s limit is %d and this input reached %d. Raise it with %s, or find out why the input needs that much -- a limit is a bound on emon, "+
			"not a statement about the document.",
		strings.ReplaceAll(name, "-", " "), b.Limit, b.Actual, envName("EMON_LIMIT_", name))
}

// THE ENGINE'S LIMITS (P2.1, SPEC 13: `Config` carries the limits): the Rust half installs a per-thread
// override table; Go has no goroutine-local state, so the table is process-wide and WithLimits keeps it
// honest: a conversion with overrides holds the lock EXCLUSIVELY (it runs alone), one without holds it
// shared (it never sees an override). `LimitOf` consults the table before `EMON_LIMIT_*`; kin still reads
// a bound only through `limit-of`.
var (
	limitMu        sync.RWMutex
	limitOverrides atomic.Pointer[map[int32]int32]
)

// WithLimits runs f with the given (kind, value) overrides in force.
func WithLimits(overrides [][2]int32, f func()) {
	if len(overrides) == 0 {
		limitMu.RLock()
		defer limitMu.RUnlock()
		f()
		return
	}
	limitMu.Lock()
	defer limitMu.Unlock()
	m := map[int32]int32{}
	for _, o := range overrides {
		m[o[0]] = o[1]
	}
	limitOverrides.Store(&m)
	defer limitOverrides.Store(nil)
	f()
}

// @kin:link:form:limit-breach: {:template "port.LimitBreach({0}, {1})" :import "emon/port" :arity 2}
func LimitBreach(kind int32, actual int32) int32 {
	panic(&Breach{Kind: kind, Limit: LimitOf(kind), Actual: actual})
}

// @kin:link:form:setting-of: {:template "port.SettingOf({0})" :import "emon/port" :arity 1}
func SettingOf(kind int32) int32 {
	def := limits.SgDefault(kind)
	name := kinName(limits.SgNameLen(kind), func(i int32) int32 { return limits.SgNameCh(kind, i) })
	if name == "" {
		name = "unknown"
	}
	if v, ok := os.LookupEnv(envName("EMON_SETTING_", name)); ok {
		if n, err := strconv.Atoi(v); err == nil {
			return int32(n)
		}
	}
	return def
}


// ------------------------------------------------------------------- Crypto
//
// THE `Crypto` PORT: see the Rust half (host/rust/src/port.rs) for the
// contract. Go's standard library has MD5, SHA-2 and AES; RC4 is written out
// (crypto/rc4 is deprecated and a host may drop it). Every form takes a span
// of a Blob and APPENDS its output to a Cells, answering how many cells it
// appended, or -1 for an unusable key.

func pushBytes(out *Cells, b []byte) int32 {
	for _, x := range b {
		out.V = append(out.V, int32(x))
	}
	return int32(len(b))
}

// @kin:link:form:crypto-md5: {:template "port.CryptoMd5({0}, {1}, {2}, {3})" :import "emon/port" :arity 4}
func CryptoMd5(data []byte, off int32, length int32, out *Cells) int32 {
	h := md5.Sum(data[off : off+length])
	return pushBytes(out, h[:])
}

// @kin:link:form:crypto-sha256: {:template "port.CryptoSha256({0}, {1}, {2}, {3})" :import "emon/port" :arity 4}
func CryptoSha256(data []byte, off int32, length int32, out *Cells) int32 {
	h := sha256.Sum256(data[off : off+length])
	return pushBytes(out, h[:])
}

// @kin:link:form:crypto-sha384: {:template "port.CryptoSha384({0}, {1}, {2}, {3})" :import "emon/port" :arity 4}
func CryptoSha384(data []byte, off int32, length int32, out *Cells) int32 {
	h := sha512.Sum384(data[off : off+length])
	return pushBytes(out, h[:])
}

// @kin:link:form:crypto-sha512: {:template "port.CryptoSha512({0}, {1}, {2}, {3})" :import "emon/port" :arity 4}
func CryptoSha512(data []byte, off int32, length int32, out *Cells) int32 {
	h := sha512.Sum512(data[off : off+length])
	return pushBytes(out, h[:])
}

// @kin:link:form:crypto-rc4: {:template "port.CryptoRc4({0}, {1}, {2}, {3}, {4}, {5}, {6})" :import "emon/port" :arity 7}
func CryptoRc4(key []byte, koff int32, klen int32, data []byte, doff int32, dlen int32, out *Cells) int32 {
	if klen < 1 || klen > 256 {
		return -1
	}
	k := key[koff : koff+klen]
	d := data[doff : doff+dlen]
	var s [256]byte
	for i := 0; i < 256; i++ {
		s[i] = byte(i)
	}
	j := 0
	for i := 0; i < 256; i++ {
		j = (j + int(s[i]) + int(k[i%len(k)])) & 255
		s[i], s[j] = s[j], s[i]
	}
	i, j := 0, 0
	for _, b := range d {
		i = (i + 1) & 255
		j = (j + int(s[i])) & 255
		s[i], s[j] = s[j], s[i]
		out.V = append(out.V, int32(b^s[(int(s[i])+int(s[j]))&255]))
	}
	return dlen
}

// @kin:link:form:crypto-aes-decrypt: {:template "port.CryptoAesDecrypt({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7})" :import "emon/port" :arity 8}
func CryptoAesDecrypt(key []byte, koff int32, klen int32, data []byte, doff int32, dlen int32, pad int32, out *Cells) int32 {
	if !(klen == 16 || klen == 24 || klen == 32) {
		return -1
	}
	d := data[doff : doff+dlen]
	if len(d) < 32 {
		return 0
	}
	blk, _ := aes.NewCipher(key[koff : koff+klen])
	n := (len(d) - 16) / 16
	plain := make([]byte, 0, n*16)
	prev := d[:16]
	for b := 0; b < n; b++ {
		ct := d[16+b*16 : 32+b*16]
		var t [16]byte
		blk.Decrypt(t[:], ct)
		for i := 0; i < 16; i++ {
			t[i] ^= prev[i]
		}
		plain = append(plain, t[:]...)
		prev = ct
	}
	if pad == 1 && len(plain) > 0 {
		p := int(plain[len(plain)-1])
		if p >= 1 && p <= 16 && p <= len(plain) {
			plain = plain[:len(plain)-p]
		}
	}
	return pushBytes(out, plain)
}

// @kin:link:form:crypto-aes-encrypt: {:template "port.CryptoAesEncrypt({0}, {1}, {2}, {3}, {4}, {5}, {6})" :import "emon/port" :arity 7}
func CryptoAesEncrypt(key []byte, koff int32, klen int32, data []byte, doff int32, dlen int32, out *Cells) int32 {
	if !(klen == 16 || klen == 24 || klen == 32) {
		return -1
	}
	d := data[doff : doff+dlen]
	if len(d) < 16 {
		return 0
	}
	blk, _ := aes.NewCipher(key[koff : koff+klen])
	n := (len(d) - 16) / 16
	ct := make([]byte, 0, n*16)
	var prev [16]byte
	copy(prev[:], d[:16])
	for b := 0; b < n; b++ {
		var t [16]byte
		for i := 0; i < 16; i++ {
			t[i] = d[16+b*16+i] ^ prev[i]
		}
		blk.Encrypt(t[:], t[:])
		ct = append(ct, t[:]...)
		prev = t
	}
	return pushBytes(out, ct)
}

// ;; P6.3 bulk forms (D197): see the Rust half for the contracts.

// @kin:link:form:bitmap-gray-into: {:template "port.BitmapGrayInto({0}, {1})" :import "emon/port" :arity 2}
func BitmapGrayInto(src, dst *Bitmap) int32 {
	if src.W != dst.W || src.H != dst.H || dst.Ch != 1 {
		panic("bitmap size")
	}
	w, ch := int(src.W), int(src.Ch)
	for y := 0; y < int(src.H); y++ {
		s := src.D[y*int(src.Stride) : y*int(src.Stride)+w*ch]
		d := dst.D[y*int(dst.Stride) : y*int(dst.Stride)+w]
		if ch < 3 {
			for x := 0; x < w; x++ {
				d[x] = s[x*ch]
			}
		} else {
			for x := 0; x < w; x++ {
				p := s[x*ch : x*ch+3 : x*ch+3]
				d[x] = byte((77*uint32(p[0]) + 150*uint32(p[1]) + 29*uint32(p[2]) + 128) >> 8)
			}
		}
	}
	return 0
}

// @kin:link:form:bitmap-hist-rgb: {:template "port.BitmapHistRgb({0}, {1}, {2})" :import "emon/port" :arity 3}
func BitmapHistRgb(src *Bitmap, cells *Cells, off int32) int32 {
	bins := cells.V[off : off+4096]
	w, ch := int(src.W), int(src.Ch)
	for y := 0; y < int(src.H); y++ {
		s := src.D[y*int(src.Stride) : y*int(src.Stride)+w*ch]
		if ch < 3 {
			for x := 0; x < w; x++ {
				bins[int(s[x*ch]>>4)*273]++
			}
		} else {
			for x := 0; x < w; x++ {
				p := s[x*ch : x*ch+3 : x*ch+3]
				bins[int(p[0]>>4)<<8|int(p[1]>>4)<<4|int(p[2]>>4)]++
			}
		}
	}
	return 0
}

// @kin:link:form:bitmap-lut-into: {:template "port.BitmapLutInto({0}, {1}, {2}, {3})" :import "emon/port" :arity 4}
func BitmapLutInto(src *Bitmap, lut *Cells, off int32, dst *Bitmap) int32 {
	if src.W != dst.W || src.H != dst.H || dst.Ch != 1 {
		panic("bitmap size")
	}
	t := lut.V[off : off+4096]
	w, ch := int(src.W), int(src.Ch)
	for y := 0; y < int(src.H); y++ {
		s := src.D[y*int(src.Stride) : y*int(src.Stride)+w*ch]
		d := dst.D[y*int(dst.Stride) : y*int(dst.Stride)+w]
		if ch < 3 {
			for x := 0; x < w; x++ {
				d[x] = byte(t[int(s[x*ch]>>4)*273])
			}
		} else {
			for x := 0; x < w; x++ {
				p := s[x*ch : x*ch+3 : x*ch+3]
				d[x] = byte(t[int(p[0]>>4)<<8|int(p[1]>>4)<<4|int(p[2]>>4)])
			}
		}
	}
	return 0
}

// @kin:link:form:bitmap-hash: {:template "port.BitmapHash({0}, {1}, {2})" :import "emon/port" :arity 3}
func BitmapHash(b *Bitmap, out *Cells, off int32) int32 {
	h := uint64(0xcbf29ce484222325)
	for _, v := range [3]int32{b.W, b.H, b.Ch} {
		for k := 0; k < 4; k++ {
			h = (h ^ uint64((uint32(v)>>(8*k))&255)) * 0x100000001b3
		}
	}
	n := int(b.W) * int(b.Ch)
	for y := 0; y < int(b.H); y++ {
		for _, c := range b.D[y*int(b.Stride) : y*int(b.Stride)+n] {
			h = (h ^ uint64(c)) * 0x100000001b3
		}
	}
	out.V[off] = int32(uint32(h >> 32))
	out.V[off+1] = int32(uint32(h))
	return 0
}

// @kin:link:form:bitmap-quarter-into: {:template "port.BitmapQuarterInto({0}, {1}, {2})" :import "emon/port" :arity 3}
func BitmapQuarterInto(src, dst *Bitmap, turns int32) int32 {
	q := ((turns % 4) + 4) % 4
	sw, sh, ch := int(src.W), int(src.H), int(src.Ch)
	if src.Ch != dst.Ch || (q%2 == 0 && (src.W != dst.W || src.H != dst.H)) || (q%2 == 1 && (src.W != dst.H || src.H != dst.W)) {
		panic("bitmap size")
	}
	for y := 0; y < int(dst.H); y++ {
		for x := 0; x < int(dst.W); x++ {
			var sx, sy int
			switch q {
			case 0:
				sx, sy = x, y
			case 1:
				sx, sy = y, sh-1-x
			case 2:
				sx, sy = sw-1-x, sh-1-y
			default:
				sx, sy = sw-1-y, x
			}
			copy(dst.D[y*int(dst.Stride)+x*ch:y*int(dst.Stride)+x*ch+ch], src.D[sy*int(src.Stride)+sx*ch:sy*int(src.Stride)+sx*ch+ch])
		}
	}
	return 0
}

// ================================================================ P7.U BitmapSet
//
// The pixels a page unit may read (see the Rust half for the contract): the bitmaps its answers carried by request id
// (Answered, the Engine's) and the ones kin makes (Made, `bms-put`, ids from KIN_VIEW_BASE). One set an attempt of a page
// unit, dropped when the unit ends.
type BitmapSet struct {
	Answered map[int32]*Bitmap
	Made     map[int32]*Bitmap
	none     *Bitmap
}

func NewBitmapSet() *BitmapSet {
	return &BitmapSet{Answered: map[int32]*Bitmap{}, Made: map[int32]*Bitmap{}, none: BitmapNew(0, 0, 1)}
}

// @kin:link:form:bms-get: {:template "port.BmsGet({0}, {1})" :import "emon/port" :arity 2}
//
// The bitmap with this id: an answer's, or one kin made; 0 x 0 when there is none.
func BmsGet(s *BitmapSet, id int32) *Bitmap {
	if b, ok := s.Made[id]; ok {
		return b
	}
	if b, ok := s.Answered[id]; ok {
		return b
	}
	return s.none
}

// @kin:link:form:bms-has: {:template "port.BmsHas({0}, {1})" :import "emon/port" :arity 2}
func BmsHas(s *BitmapSet, id int32) int32 {
	if _, ok := s.Made[id]; ok {
		return 1
	}
	if _, ok := s.Answered[id]; ok {
		return 1
	}
	return 0
}

// @kin:link:form:bms-put: {:template "port.BmsPut({0}, {1}, {2}, {3}, {4})" :import "emon/port" :arity 5}
//
// A new zeroed bitmap of w x h x channels under `id`, for kin to fill. An id already in the set is a bug in kin: it panics.
func BmsPut(s *BitmapSet, id, w, h, ch int32) *Bitmap {
	if BmsHas(s, id) == 1 {
		panic("bms_put: id is already in the set")
	}
	b := BitmapNew(w, h, ch)
	s.Made[id] = b
	return b
}
