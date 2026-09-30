// The Go half of emon's TEST support (DEVLOG P0.5, D044). See the Rust half.
// It is in package `port` because a Go package is a directory; only
// `emon.test`'s forms reach it. It takes a `Failer`, not `*testing.T`, so the
// production package does not import `testing`.

package port

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Failer is what `*testing.T` gives an assertion.
type Failer interface {
	Errorf(format string, args ...any)
}

func inverted() bool { return os.Getenv("EMON_TEST_INVERT") == "1" }

func verdict(t Failer, ok bool, what string) {
	if ok == inverted() {
		t.Errorf("%s", what)
	}
}

func AssertEq(t Failer, got int32, want int32, name string) {
	verdict(t, got == want, fmt.Sprintf("%s: got %d, want %d", name, got, want))
}

func AssertEqAt(t Failer, got int32, want int32, name string, row int32) {
	verdict(t, got == want, fmt.Sprintf("%s [row %d]: got %d, want %d", name, row, got, want))
}

func AssertEq64(t Failer, got int64, want int64, name string) {
	verdict(t, got == want, fmt.Sprintf("%s: got %d, want %d", name, got, want))
}

func AssertThat(t Failer, ok bool, name string) {
	verdict(t, ok, name+": not true")
}

func AssertThatAt(t Failer, ok bool, name string, row int32) {
	verdict(t, ok, fmt.Sprintf("%s [row %d]: not true", name, row))
}

func AssertText(t Failer, got *strings.Builder, want string, name string) {
	verdict(t, got.String() == want, fmt.Sprintf("%s: got `%s`, want `%s`", name, got.String(), want))
}

func AssertTextSpan(t Failer, got *strings.Builder, b []byte, from int32, to int32, name string, row int32) {
	want := string(b[from:to])
	verdict(t, got.String() == want, fmt.Sprintf("%s [row %d]: got `%s`, want `%s`", name, row, got.String(), want))
}

func AssertCellsHex(t Failer, c *Cells, hex string, name string) {
	want := HexBlob(hex)
	ok := len(c.V) == len(want)
	got := ""
	for i, v := range c.V {
		got += fmt.Sprintf("%02x", v&0xFF)
		if ok && v != int32(want[i]) {
			ok = false
		}
	}
	verdict(t, ok, fmt.Sprintf("%s: got %d bytes %s, want %s", name, len(c.V), got, hex))
}

func TextBlob(s string) []byte { return []byte(s) }

func HexBlob(s string) []byte {
	var digits []int
	for _, r := range s {
		switch {
		case r >= '0' && r <= '9':
			digits = append(digits, int(r-'0'))
		case r >= 'a' && r <= 'f':
			digits = append(digits, int(r-'a')+10)
		case r >= 'A' && r <= 'F':
			digits = append(digits, int(r-'A')+10)
		}
	}
	out := make([]byte, len(digits)/2)
	for i := range out {
		out[i] = byte(digits[2*i]<<4 | digits[2*i+1])
	}
	return out
}

// TestFileSpan is TestFile with the name taken from b[from:to] (a corpus row).
func TestFileSpan(b []byte, from int32, to int32) []byte {
	return TestFile(string(b[from:to]))
}

// TestFile reads a corpus file whole; `EMON_TEST_DIR` names the directory.
func TestFile(name string) []byte {
	dir := os.Getenv("EMON_TEST_DIR")
	if dir == "" {
		dir = "test"
	}
	b, err := os.ReadFile(filepath.Join(dir, name))
	if err != nil {
		panic(err)
	}
	return b
}

func CellsBlob(c *Cells) []byte {
	out := make([]byte, len(c.V))
	for i, v := range c.V {
		out[i] = byte(v)
	}
	return out
}

func NewText() *strings.Builder { return &strings.Builder{} }

// ------------------------------------------------------ a source that misses
//
// A Src over a blob, served in fixed-size CHUNKS of which only some are
// RESIDENT (P1.11, D058); see the Rust half. Tests only.

type chunkedBacking struct {
	data     []byte
	chunk    int32
	sync     bool
	resident []bool
	pending  []int32
	fills    int32
	hints    int32
}

func (b *chunkedBacking) nchunks() int32 {
	return (int32(len(b.data)) + b.chunk - 1) / b.chunk
}

func (b *chunkedBacking) touch(c int32) bool {
	if b.resident[c] {
		return true
	}
	if b.sync {
		b.resident[c] = true
		b.fills++
		return true
	}
	for _, p := range b.pending {
		if p == c {
			return false
		}
	}
	b.pending = append(b.pending, c)
	return false
}

func (b *chunkedBacking) Len() int32 { return int32(len(b.data)) }

func (b *chunkedBacking) At(i int32) int32 {
	if b.touch(i / b.chunk) {
		return int32(b.data[i])
	}
	return -1
}

func (b *chunkedBacking) Span(s int32, buf []byte) bool {
	n := int32(len(buf))
	if n == 0 {
		return true
	}
	ok := true
	for c := s / b.chunk; c <= (s+n-1)/b.chunk; c++ {
		if b.touch(c) {
			lo, hi := max(s, c*b.chunk), min(s+n, (c+1)*b.chunk)
			for k := lo; k < hi; k++ {
				buf[k-s] = b.data[k]
			}
		} else {
			ok = false
		}
	}
	return ok
}

func (b *chunkedBacking) Hint(s int32, e int32) { b.hints++ }

// SrcChunked is a source over b, in chunks of `chunk` bytes, none resident;
// sync 1 fills inline.
func SrcChunked(b []byte, chunk int32, sync int32) *Src {
	n := (int32(len(b)) + chunk - 1) / chunk
	return NewSrc(&chunkedBacking{data: b, chunk: chunk, sync: sync == 1, resident: make([]bool, n)})
}

// SrcView is a fresh view (missed flag clear) of the same chunk cache.
func SrcView(s *Src) *Src { return s.View() }

// SrcCtl controls the cache: op 0 fetch (a is the round: read-ahead of 2^a - 1
// chunks each side of every pending chunk; answers the chunks made resident),
// 1 make chunk a resident, 2 evict chunk a, 3 make every chunk resident, 4 the
// pending count, 5 the resident count, 6 the hints received, 7 the chunks a
// sync source filled inline, 8 the chunk count.
func SrcCtl(s *Src, op int32, a int32) int32 {
	b := s.B.(*chunkedBacking)
	n := b.nchunks()
	switch op {
	case 0:
		r := int32(1)<<min(a, 20) - 1
		pend := b.pending
		b.pending = nil
		made := int32(0)
		for _, c := range pend {
			for k := max(0, c-r); k <= min(n-1, c+r); k++ {
				if !b.resident[k] {
					b.resident[k] = true
					made++
				}
			}
		}
		return made
	case 1:
		b.resident[a] = true
		keep := b.pending[:0]
		for _, c := range b.pending {
			if c != a {
				keep = append(keep, c)
			}
		}
		b.pending = keep
		return 0
	case 2:
		b.resident[a] = false
		return 0
	case 3:
		for k := range b.resident {
			b.resident[k] = true
		}
		b.pending = nil
		return 0
	case 4:
		return int32(len(b.pending))
	case 5:
		c := int32(0)
		for _, r := range b.resident {
			if r {
				c++
			}
		}
		return c
	case 6:
		return b.hints
	case 7:
		return b.fills
	case 8:
		return n
	}
	panic(fmt.Sprintf("SrcCtl: no op %d", op))
}
