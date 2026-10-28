// BYTE SOURCES, Go host (DEVLOG P2.1; SPEC 13.1; D058). The twin of `host/rust/src/source.rs`: read that
// for the reasoning. The library never takes a path, never opens a file and never names a filesystem: a
// document is a `ByteSource` (a size and `ReadAt`), and what is shipped here is every way a Go caller
// commonly HAS one.
//
//	[]byte               BytesInput / NewBytesSource: an in-memory source (AsSlice answers, so the engine reads
//	                     it with no copy and no chunk cache)
//	io.ReaderAt + size   ReaderAtInput: an *os.File (with its size), an *io.SectionReader, *bytes.Reader,
//	                     an object-store reader. Random access; the engine reads it in chunks, on demand
//	io.ReadSeeker        SeekableInput / SeekSource: random access through the seek, under a lock
//	io.Reader            SpooledInput / SpoolSource: a forward-only stream, SPOOLED into memory bounded by
//	                     the input-bytes limit (SPEC 13.1: a stream is an adapter, not the contract)
//
// `Input{Source, Password}` is one document (the password travels with its input, D040: never in the
// settings, never in the Model, a dump or a transcript). Test drivers and CLIs read the file themselves.

package emon

import (
	"errors"
	"fmt"
	"io"
	"sync"
)

// ByteSource is random access with a known length. ReadAt may return fewer bytes than asked for only
// with an error (io.ReaderAt's contract); a source that returns io.EOF before the span is full, or fewer
// bytes than its Size said, is `source-changed`.
type ByteSource interface {
	io.ReaderAt
	Size() int64
}

// SliceSource is an optional interface of a ByteSource that already holds all its bytes in memory: the
// engine then skips the chunk cache. The slice must not be modified while a conversion runs.
type SliceSource interface {
	AsSlice() []byte
}

// BytesSource is an in-memory source. NewBytesSource copies its argument; BytesInput does not.
type BytesSource struct{ b []byte }

// NewBytesSource copies b.
func NewBytesSource(b []byte) *BytesSource {
	return &BytesSource{b: append([]byte(nil), b...)}
}

func (s *BytesSource) Size() int64 { return int64(len(s.b)) }

func (s *BytesSource) ReadAt(p []byte, off int64) (int, error) { return readSlice(s.b, p, off) }

func (s *BytesSource) AsSlice() []byte { return s.b }

func readSlice(v []byte, p []byte, off int64) (int, error) {
	if off < 0 {
		return 0, errors.New("negative offset")
	}
	if off >= int64(len(v)) {
		return 0, io.EOF
	}
	n := copy(p, v[off:])
	if n < len(p) {
		return n, io.EOF
	}
	return n, nil
}

// readerAtSource is an io.ReaderAt and a size.
type readerAtSource struct {
	r io.ReaderAt
	n int64
}

func (s *readerAtSource) Size() int64                            { return s.n }
func (s *readerAtSource) ReadAt(p []byte, off int64) (int, error) { return s.r.ReadAt(p, off) }

// SeekSource is an io.ReadSeeker: the size is taken once, when the source is made (seek to the end);
// every ReadAt seeks and reads under a lock, so one handle serves the engine.
type SeekSource struct {
	mu sync.Mutex
	r  io.ReadSeeker
	n  int64
}

// NewSeekSource takes the size by seeking to the end.
func NewSeekSource(r io.ReadSeeker) (*SeekSource, error) {
	n, err := r.Seek(0, io.SeekEnd)
	if err != nil {
		return nil, err
	}
	return &SeekSource{r: r, n: n}, nil
}

func (s *SeekSource) Size() int64 { return s.n }

func (s *SeekSource) ReadAt(p []byte, off int64) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.r.Seek(off, io.SeekStart); err != nil {
		return 0, err
	}
	return io.ReadFull(s.r, p)
}

// SpoolSource is a forward-only io.Reader, SPOOLED into memory. At most `max` bytes are taken: a longer
// stream is an error whose message begins `input-bytes` (the engine maps it to the named limit).
type SpoolSource struct{ b []byte }

// Spool reads r to its end, up to max bytes.
func Spool(r io.Reader, max int64) (*SpoolSource, error) {
	b, err := io.ReadAll(io.LimitReader(r, max+1))
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > max {
		return nil, fmt.Errorf("input-bytes: the stream is longer than %d bytes", max)
	}
	return &SpoolSource{b: b}, nil
}

func (s *SpoolSource) Size() int64 { return int64(len(s.b)) }

func (s *SpoolSource) ReadAt(p []byte, off int64) (int, error) { return readSlice(s.b, p, off) }

func (s *SpoolSource) AsSlice() []byte { return s.b }

// Input is one document or image: a source and, when the file is encrypted, its password (D040). The
// password is bytes: R2-R4 use them as given (PDFDocEncoding, in practice Latin-1), R5 and R6 as UTF-8.
type Input struct {
	Source   ByteSource
	Password []byte
	hasPw    bool
}

// NewInput is a source with no password.
func NewInput(src ByteSource) Input { return Input{Source: src} }

// BytesInput is an in-memory source over b (not copied) with no password.
func BytesInput(b []byte) Input { return Input{Source: &BytesSource{b: b}} }

// ReaderAtInput is an io.ReaderAt of a known size: an *os.File and its size, an *io.SectionReader, ...
func ReaderAtInput(r io.ReaderAt, size int64) Input {
	return Input{Source: &readerAtSource{r: r, n: size}}
}

// SeekableInput is an io.ReadSeeker: a file, a bytes.Reader, anything seekable.
func SeekableInput(r io.ReadSeeker) (Input, error) {
	s, err := NewSeekSource(r)
	if err != nil {
		return Input{}, err
	}
	return Input{Source: s}, nil
}

// SpooledInput is a forward-only stream, spooled; max is the input-bytes bound to spool to.
func SpooledInput(r io.Reader, max int64) (Input, error) {
	s, err := Spool(r, max)
	if err != nil {
		return Input{}, err
	}
	return Input{Source: s}, nil
}

// WithPassword returns the input with a password (D040).
func (in Input) WithPassword(password []byte) Input {
	in.Password = append([]byte(nil), password...)
	in.hasPw = true
	return in
}

// String never prints the password.
func (in Input) String() string {
	pw := "no"
	if in.hasPw {
		pw = "yes"
	}
	return fmt.Sprintf("Input{len: %d, password: %s}", in.Source.Size(), pw)
}
