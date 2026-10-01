// The Engine API's native tests, Go (DEVLOG P2.1), run by `bb slice` (`kin/scripts/slice`) against the
// assembled module (`kin/scripts/emon-go-build`): the twin of `host/rust/tests/engine.rs`, test for test. They
// read fixtures from the repository's `test/`: a TEST may read files, the library may not (D058). What they hold:
//
//   - the byte-source adapters (bytes, io.ReaderAt, io.ReadSeeker, spooled io.Reader) give IDENTICAL JSON and HTML;
//   - a 512-byte ASYNC source (misses, fetch rounds, re-runs) gives the in-memory run's output, byte for byte,
//     over the slice's fixtures AND every PDF fixture of `test/pdf` (an error must be the same error);
//   - D040's three encryption errors, distinct; a password opens what it opens, and equals the plain twin;
//   - the input-bytes limit is checked against the SIZE before any byte is read; a source that lies about its
//     size is `source-changed`;
//   - `assets` other than `none` and several inputs as one document are refused, not approximated;
//   - record and replay; the walkable Model; and that the library names no filesystem.
package emon

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"testing"
)

func root() string {
	if r := os.Getenv("EMON_ROOT"); r != "" {
		return r
	}
	return filepath.Join("..", "..")
}

func read(t *testing.T, rel string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(root(), rel))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

var slice = []string{"h-plain", "h-keyword", "h-inverted-numbered", "h-numbered", "initial-image", "initial-vector", "furn-informative"}

func synth(t *testing.T, name string) []byte {
	return read(t, "test/corpus/synth/pdf/"+name+".pdf")
}

func js(m *Model) string { return m.ToJSON(JSONOptions{Pretty: true}) }

func asyncEngine(chunk int) *Engine {
	c := DefaultConfig()
	c.ChunkSize, c.SimulateAsync = chunk, true
	return NewEngine(c)
}

func one(in Input) []Input { return []Input{in} }

func must(t *testing.T, m *Model, err error) *Model {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func errName(err error) string {
	var e *Error
	if errors.As(err, &e) {
		return e.Name()
	}
	if err == nil {
		return ""
	}
	return "?" + err.Error()
}

// a plain io.Reader: no seek
type forward struct{ r *bytes.Reader }

func (f forward) Read(b []byte) (int, error) { return f.r.Read(b) }

func TestTheAdaptersGiveIdenticalJSONAndHTML(t *testing.T) {
	for _, name := range slice {
		b := synth(t, name)
		e := NewEngine(DefaultConfig())
		o := DefaultOptions()
		m0, err := e.Convert(one(BytesInput(b)), o)
		by := must(t, m0, err)
		seekIn, err := SeekableInput(bytes.NewReader(b))
		if err != nil {
			t.Fatal(err)
		}
		spoolIn, err := SpooledInput(forward{bytes.NewReader(b)}, 1<<30)
		if err != nil {
			t.Fatal(err)
		}
		tiny := DefaultConfig()
		tiny.ChunkSize = 64
		sections := map[string]Input{
			"readerat": ReaderAtInput(bytes.NewReader(b), int64(len(b))),
			"seek":     seekIn,
			"spool":    spoolIn,
			"copy":     NewInput(NewBytesSource(b)),
		}
		want, wantHTML := js(by), by.ToHTML(DefaultHTMLOptions())
		if !strings.Contains(want, "\"paragraph\"") {
			t.Fatalf("%s has paragraphs", name)
		}
		for what, in := range sections {
			m, err := e.Convert(one(in), o)
			if got := js(must(t, m, err)); got != want {
				t.Errorf("%s: %s JSON differs", name, what)
			}
			if got := m.ToHTML(DefaultHTMLOptions()); got != wantHTML {
				t.Errorf("%s: %s HTML differs", name, what)
			}
		}
		// a seekable source through a tiny SYNC cache: chunks filled inline, no miss ever
		in, _ := SeekableInput(bytes.NewReader(b))
		m, err := NewEngine(tiny).Convert(one(in), o)
		if js(must(t, m, err)) != want {
			t.Errorf("%s: sync chunks differ", name)
		}
	}
}

func TestA512ByteAsyncSourceEqualsTheInMemoryRun(t *testing.T) {
	for _, name := range slice {
		b := synth(t, name)
		o := DefaultOptions()
		mem, ms, err := NewEngine(DefaultConfig()).ConvertStats(one(BytesInput(b)), o)
		must(t, mem, err)
		if ms.Attempts != 1 || ms.FetchRounds != 0 {
			t.Errorf("in memory: one attempt, no fetch: %+v", ms)
		}
		asy, st, err := asyncEngine(512).ConvertStats(one(BytesInput(b)), o)
		must(t, asy, err)
		if js(asy) != js(mem) || asy.ToHTML(DefaultHTMLOptions()) != mem.ToHTML(DefaultHTMLOptions()) {
			t.Errorf("%s: async differs from memory", name)
		}
		if st.FetchRounds < 1 || st.Attempts != st.FetchRounds+1 {
			t.Errorf("%s: it missed and re-ran: %+v", name, st)
		}
		if st.BytesFetched > uint64(len(b)) || st.FetchRounds > 24 {
			t.Errorf("%s: never more than the input, inside FETCH_ROUNDS: %+v", name, st)
		}
		in, _ := SeekableInput(bytes.NewReader(b))
		seek, err := asyncEngine(512).Convert(one(in), o)
		if js(must(t, seek, err)) != js(mem) {
			t.Errorf("%s: async over io.ReadSeeker differs", name)
		}
	}
}

func resultOf(e *Engine, b []byte, pw *string) (string, string) {
	in := BytesInput(b)
	if pw != nil {
		in = in.WithPassword([]byte(*pw))
	}
	m, err := e.Convert(one(in), DefaultOptions())
	if err != nil {
		return "", errName(err)
	}
	return m.ToJSON(JSONOptions{}), ""
}

func TestEveryPDFFixtureIsTheSameResultInMemoryAndAsync(t *testing.T) {
	mem, asy := NewEngine(DefaultConfig()), asyncEngine(512)
	var files []string
	for _, dir := range []string{"test/pdf", "test/pdf/p3", "test/content", "test/fuzz"} {
		m, _ := filepath.Glob(filepath.Join(root(), dir, "*.pdf"))
		files = append(files, m...)
	}
	sort.Strings(files)
	if len(files) <= 60 {
		t.Fatalf("found the fixtures: %d", len(files))
	}
	ok, refused := 0, 0
	for _, f := range files {
		b, err := os.ReadFile(f)
		if err != nil {
			t.Fatal(err)
		}
		a1, e1 := resultOf(mem, b, nil)
		a2, e2 := resultOf(asy, b, nil)
		if a1 != a2 || e1 != e2 {
			t.Errorf("%s: in memory and async 512 differ (%q vs %q)", f, e1, e2)
		}
		if e1 == "" {
			ok++
		} else {
			refused++
		}
	}
	t.Logf("%d fixtures: %d converted, %d refused with a named error, each the same both ways", len(files), ok, refused)
	if ok <= 20 {
		t.Errorf("only %d converted", ok)
	}
}

func TestEncryptionErrorsAreDistinctAndAPasswordOpensWhatItOpens(t *testing.T) {
	e := NewEngine(DefaultConfig())
	s := func(v string) *string { return &v }
	plain, perr := resultOf(e, read(t, "test/pdf/p3/plain.pdf"), nil)
	if perr != "" {
		t.Fatal(perr)
	}
	locked := read(t, "test/pdf/p3/crypt-aes-128-user.pdf")
	if _, n := resultOf(e, locked, nil); n != "password-required" {
		t.Errorf("no password: %q", n)
	}
	if _, n := resultOf(e, locked, s("wrong-password")); n != "password-incorrect" {
		t.Errorf("wrong password: %q", n)
	}
	if j, n := resultOf(e, locked, s("user-secret")); n != "" || j != plain {
		t.Errorf("the user password: the plain twin's Model (%q)", n)
	}
	if j, n := resultOf(e, locked, s("owner-secret")); n != "" || j != plain {
		t.Errorf("the owner password too (%q)", n)
	}
	if j, n := resultOf(e, read(t, "test/pdf/p3/crypt-aes-128-empty.pdf"), nil); n != "" || j != plain {
		t.Errorf("the empty user password is tried by itself (%q)", n)
	}
	if _, n := resultOf(e, read(t, "test/pdf/p3/unsupported-pubsec.pdf"), nil); n != "encryption-unsupported" {
		t.Errorf("pubsec: %q", n)
	}
	asy := asyncEngine(512)
	r6 := read(t, "test/pdf/p3/crypt-aes-256-r6-user.pdf")
	if _, n := resultOf(asy, r6, nil); n != "password-required" {
		t.Errorf("async, no password: %q", n)
	}
	if _, n := resultOf(asy, r6, s("nope")); n != "password-incorrect" {
		t.Errorf("async, wrong password: %q", n)
	}
	// a password is never in the printed form of an Input
	if strings.Contains(BytesInput([]byte{1}).WithPassword([]byte("hunter2")).String(), "hunter2") {
		t.Error("the password is in the Input's String")
	}
}

// A source that must not be read: a size check that happens first never reads it.
type unreadable int64

func (u unreadable) Size() int64 { return int64(u) }
func (u unreadable) ReadAt(p []byte, off int64) (int, error) {
	panic("a source over the input-bytes limit was read")
}

// A source whose size says more than it has.
type liar struct {
	b []byte
	n int64
}

func (l liar) Size() int64 { return l.n }
func (l liar) ReadAt(p []byte, off int64) (int, error) {
	if off >= int64(len(l.b)) {
		return 0, nil
	}
	return copy(p, l.b[off:]), nil
}

func TestTheInputBytesLimitIsCheckedAgainstTheSizeBeforeAByteIsRead(t *testing.T) {
	c := DefaultConfig()
	c.Limits = [][2]int32{{0, 1000}}
	_, err := NewEngine(c).Convert(one(NewInput(unreadable(5000))), DefaultOptions())
	var e *Error
	if !errors.As(err, &e) || e.Kind != ErrLimit || e.Name() != "input-bytes" || e.Limit != 1000 || e.Actual != 5000 {
		t.Errorf("limit 1000, size 5000: %v", err)
	}
	// the default is SPEC 7.4's 1 GiB: a size above it is refused too
	_, err = NewEngine(DefaultConfig()).Convert(one(NewInput(unreadable(5<<30))), DefaultOptions())
	if errName(err) != "input-bytes" {
		t.Errorf("5 GiB: %v", err)
	}
	// a spool that runs past its bound is the same named limit
	if _, err := SpooledInput(bytes.NewReader(make([]byte, 5000)), 1000); err == nil || !strings.HasPrefix(err.Error(), "input-bytes") {
		t.Errorf("spool: %v", err)
	}
	// a limit deeper in kin is named too (pages), and does not leak into the next conversion
	few := DefaultConfig()
	few.Limits = [][2]int32{{8, 1}}
	_, err = NewEngine(few).Convert(one(BytesInput(read(t, "test/pdf/p3/plain.pdf"))), DefaultOptions())
	if errName(err) != "pages" {
		t.Errorf("pages limit: %v", err)
	}
	if _, err := NewEngine(DefaultConfig()).Convert(one(BytesInput(read(t, "test/pdf/p3/plain.pdf"))), DefaultOptions()); err != nil {
		t.Errorf("the limit leaked: %v", err)
	}
}

func TestASourceThatGivesFewerBytesThanItsSizeIsSourceChanged(t *testing.T) {
	b := synth(t, "h-plain")
	c := DefaultConfig()
	c.ChunkSize = 4096
	for name, e := range map[string]*Engine{"sync": NewEngine(c), "async": asyncEngine(512)} {
		_, err := e.Convert(one(NewInput(liar{b, int64(len(b)) + 10000})), DefaultOptions())
		if errName(err) != "source-changed" {
			t.Errorf("%s: %v", name, err)
		}
	}
}

func TestWhatIsNotImplementedIsRefused(t *testing.T) {
	e := NewEngine(DefaultConfig())
	b := synth(t, "h-plain")
	for _, a := range []Assets{AssetsImages, AssetsFigures} {
		o := DefaultOptions()
		o.Assets = a
		if _, err := e.Convert(one(BytesInput(b)), o); errName(err) != "unsupported" {
			t.Errorf("assets %d: %v", a, err)
		}
	}
	if _, err := e.Convert([]Input{BytesInput(b), BytesInput(b)}, DefaultOptions()); errName(err) != "unsupported" {
		t.Errorf("two inputs: %v", err)
	}
	if _, err := e.Convert(nil, DefaultOptions()); errName(err) != "no-input" {
		t.Errorf("no input: %v", err)
	}
	if _, err := e.Convert(one(BytesInput(read(t, "test/pdf/notpdf.pdf"))), DefaultOptions()); errName(err) != "unsupported-format" {
		t.Errorf("not a PDF: %v", err)
	}
	// OCR is accepted and has no effect yet (D038's default is `always`): no policy changes the Model
	always, never := DefaultOptions(), DefaultOptions()
	always.Ocr, never.Ocr = OcrAlways, OcrNever
	m1, err := e.Convert(one(BytesInput(b)), always)
	m2, err2 := e.Convert(one(BytesInput(b)), never)
	if err != nil || err2 != nil || js(m1) != js(m2) {
		t.Error("the OCR policy changed the Model")
	}
}

func TestIntakeTakesAnyNumberOfInputs(t *testing.T) {
	e := NewEngine(DefaultConfig())
	v, err := e.Intake([]Input{BytesInput(synth(t, "h-plain")), BytesInput(read(t, "test/pdf/p3/crypt-aes-128-empty.pdf"))})
	if err != nil || len(v) != 2 {
		t.Fatalf("%v %v", v, err)
	}
	if v[0].Format != "pdf" || v[0].Pages != 2 || v[0].Encrypted || v[1].Pages != 3 || !v[1].Encrypted {
		t.Errorf("%+v", v)
	}
	if _, err := e.Intake(one(BytesInput(read(t, "test/pdf/p3/crypt-aes-128-user.pdf")))); errName(err) != "password-required" {
		t.Errorf("locked: %v", err)
	}
	m, err := e.Convert(one(BytesInput(synth(t, "h-plain"))), DefaultOptions())
	if must(t, m, err).Source().Pages != 2 || m.Source().Encrypted {
		t.Errorf("%+v", m.Source())
	}
}

func TestRecordAndReplayWithTheSliceIssuingNoRequests(t *testing.T) {
	e := NewEngine(DefaultConfig())
	b := synth(t, "h-plain")
	m, tr, err := e.Record(one(BytesInput(b)), DefaultOptions())
	must(t, m, err)
	if len(tr.Entries) != 0 {
		t.Error("the slice issues zero requests")
	}
	if len(tr.Inputs) != 1 || tr.Inputs[0] != [2]int32{0, int32(len(b))} {
		t.Errorf("inputs %v", tr.Inputs)
	}
	r, err := e.Replay(one(BytesInput(b)), DefaultOptions(), tr)
	if js(must(t, r, err)) != js(m) {
		t.Error("replay differs from record")
	}
	// a transcript is of the input it was recorded from
	if _, err := e.Replay(one(BytesInput(synth(t, "h-keyword"))), DefaultOptions(), tr); errName(err) != "transcript-bad" {
		t.Errorf("another input's transcript: %v", err)
	}
}

func TestTheModelCanBeWalked(t *testing.T) {
	e := NewEngine(DefaultConfig())
	o := DefaultOptions()
	o.Locale = "en-GB"
	m, err := e.Convert(one(BytesInput(synth(t, "h-plain"))), o)
	must(t, m, err)
	root := m.Root()
	if root.Kind().Name() != "document" {
		t.Errorf("root kind %s", root.Kind().Name())
	}
	if a, ok := root.Attr("locale"); !ok || a.Kind != AttrText || a.Text != "en-GB" {
		t.Errorf("locale %+v", a)
	}
	if !strings.Contains(m.ToJSON(JSONOptions{}), "\"locale\":\"en-GB\"") {
		t.Error("the JSON has no locale")
	}
	// the title is a heading (the heading analysis, D172) at the head of a section of level 1
	top := root.Children()
	if len(top) < 1 || top[0].Kind().Name() != "section" {
		t.Fatalf("top %v", top)
	}
	sec := top[0].Index()
	kids := top[0].Children()
	if len(kids) <= 2 || kids[0].Kind().Name() != "heading" || kids[0].Text() != "Field Notes on Soil Moisture" {
		t.Fatalf("children %v", kids)
	}
	if s := kids[0].Sources(); len(s) != 2 || s[0] != "text" || s[1] != "infer" {
		t.Errorf("sources %v", s)
	}
	if kids[0].Conf() != 500 {
		t.Errorf("conf %d", kids[0].Conf())
	}
	if p, ok := kids[0].Parent(); !ok || p.Index() != sec {
		t.Error("parent")
	}
	if _, ok := root.Parent(); ok {
		t.Error("the root has a parent")
	}
	rg := kids[0].Regions()
	if len(rg) != 1 || rg[0].Page != 1 || rg[0].X0 != 7200 || rg[0].Y0 != 9781 {
		t.Errorf("regions %v", rg)
	}
	para, ok := KindNamed("paragraph")
	if _, bad := KindNamed("nonsense"); !ok || bad {
		t.Error("KindNamed")
	}
	all := m.Find(para)
	n := 0
	for _, k := range kids {
		if k.Kind() == para {
			n++
		}
	}
	if len(all) < n {
		t.Error("Find misses paragraphs")
	}
	for i := 1; i < len(all); i++ {
		if all[i-1].Index() >= all[i].Index() {
			t.Error("document order")
		}
	}
	pages := m.Pages()
	if len(pages) != 2 || pages[0].Number != 1 || pages[0].Width != 61200 || pages[0].Height != 79200 || pages[0].Kind != "vector" {
		t.Errorf("pages %+v", pages)
	}
	if len(m.Furniture()) != 0 || len(m.Dropped()) != 0 {
		t.Error("furniture or dropped in a plain page")
	}
	// the walk and the JSON say the same text, paragraph by paragraph
	j := m.ToJSON(JSONOptions{})
	for _, k := range kids {
		if k.Kind() != para {
			continue
		}
		q := strings.ReplaceAll(strings.ReplaceAll(k.Text(), "\\", "\\\\"), "\"", "\\\"")
		if !strings.Contains(j, "\"text\":\""+q+"\"") {
			t.Errorf("JSON lacks %q", k.Text())
		}
	}
	// the keep-evidence option
	o2 := DefaultOptions()
	o2.KeepEvidence = true
	ev, err := e.Convert(one(BytesInput(synth(t, "h-plain"))), o2)
	if s, ok := must(t, ev, err).Evidence(); !ok || !strings.HasPrefix(s, "{\"emon-evidence\"") {
		t.Error("evidence")
	}
	if _, ok := m.Evidence(); ok {
		t.Error("evidence kept without asking")
	}
}

func TestTheModelIsReadableFromManyGoroutines(t *testing.T) {
	m, err := NewEngine(DefaultConfig()).Convert(one(BytesInput(synth(t, "h-plain"))), DefaultOptions())
	must(t, m, err)
	want := m.ToJSON(JSONOptions{})
	done := make(chan string, 8)
	for i := 0; i < 8; i++ {
		go func() {
			for _, k := range m.Root().Children() {
				_ = k.Text()
			}
			done <- m.ToJSON(JSONOptions{})
		}()
	}
	for i := 0; i < 8; i++ {
		if <-done != want {
			t.Error("a concurrent read differs")
		}
	}
}

func TestTheLibraryNamesNoFilesystem(t *testing.T) {
	// D058: nothing under host/go (the Engine, its sources, the loop, the ports) opens a file or names a path.
	// `port/testport.go` (test support) is not the library.
	bad := regexp.MustCompile(`"io/ioutil"|"io/fs"|"path"|"path/filepath"|\bos\.(Open|OpenFile|ReadFile|WriteFile|Create|Stat|ReadDir|Mkdir|Remove|File)\b`)
	for _, f := range []string{"engine.go", "source.go", "model.go", "port/port.go", "port/plan.go"} {
		src, err := os.ReadFile(f)
		if err != nil {
			src = read(t, "host/go/"+f)
		}
		for i, line := range strings.Split(string(src), "\n") {
			code := strings.SplitN(line, "//", 2)[0]
			if bad.MatchString(code) {
				t.Errorf("%s:%d names a filesystem: %s", f, i+1, line)
			}
		}
	}
}
