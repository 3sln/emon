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
