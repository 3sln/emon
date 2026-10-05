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
//   - record and replay; the walkable Model; and that the library names no filesystem;
//   - (render_test.go) the Renderer port, P6.2, D127.
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

	_ "emon/pngenc" // the canonical PNG writer: a transcript's files need it
	"emon/ocr"
	"emon/plan"
	"emon/port"
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

var slice = []string{"h-plain", "h-keyword", "h-inverted-numbered", "h-numbered", "initial-image", "initial-vector"}

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

func TestRecordAndReplayWithABornDigitalPagesTwoRastersUnsupported(t *testing.T) {
	e := NewEngine(DefaultConfig())
	b := synth(t, "h-plain")
	m, tr, err := e.Record(one(BytesInput(b)), DefaultOptions())
	must(t, m, err)
	// under `always` a born-digital page asks for its two rasters (D199): with no Renderer each is answered `unsupported`, in page
	// order, each page its own unit; nothing is OCR'd
	if len(tr.Entries) != 4 {
		t.Fatalf("entries: %d", len(tr.Entries))
	}
	for i, id := range []int32{257, 258, 513, 514} {
		en := tr.Entries[i]
		if en.Round != 0 || en.Req[1] != 1 || en.Req[2] != id || en.Ans[3] != 2 || en.Bitmap != nil {
			t.Errorf("entry %d: %v %v", i, en.Req, en.Ans)
		}
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
	for _, f := range []string{"engine.go", "source.go", "model.go", "port/port.go", "port/plan.go", "port/render.go", "pdfium/pdfium.go", "pdfium/callback.go"} {
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

// ---------------------------------------------------------------------------------------------- raster intake
// (P6.1, D187-D190) An image input is read through the `ImageDecode` port: one `image` page per frame.

func imageFx(t *testing.T, name string) []byte { return read(t, "test/image/"+name) }

func TestAnImageBecomesPagesAndAMultiPageTiffSeveral(t *testing.T) {
	e := NewEngine(DefaultConfig())
	m, err := e.Convert(one(BytesInput(imageFx(t, "png-phys300.png"))), DefaultOptions())
	m = must(t, m, err)
	pages := m.Pages()
	if len(pages) != 1 || pages[0].Kind != "image" {
		t.Fatalf("pages: %+v", pages)
	}
	// 48 x 36 pixels at 300 dpi: 11.52 x 8.64 pt, in centipoints
	if pages[0].Width != 1152 || pages[0].Height != 864 {
		t.Errorf("page size %dx%d", pages[0].Width, pages[0].Height)
	}
	if m.Source().Format != "png" {
		t.Errorf("format %q", m.Source().Format)
	}
	j := m.ToJSON(JSONOptions{})
	if !strings.Contains(j, `"source":{"format":"png","pages":1}`) || !strings.Contains(j, `"dpi":300,"px":[48,36]`) || strings.Contains(j, "dpi-unknown") {
		t.Errorf("json: %s", j)
	}
	// no stated resolution: read as 300 and flagged
	m, err = e.Convert(one(BytesInput(imageFx(t, "png-rgb8.png"))), DefaultOptions())
	if m = must(t, m, err); !strings.Contains(m.ToJSON(JSONOptions{}), "dpi-unknown") {
		t.Error("an image with no resolution is not flagged")
	}
	// a multi-page TIFF: three frames of three sizes, in order
	m, err = e.Convert(one(BytesInput(imageFx(t, "tiff-multi3-rgb-lzw.tif"))), DefaultOptions())
	m = must(t, m, err)
	var got [][3]int32
	for _, p := range m.Pages() {
		got = append(got, [3]int32{p.Number, p.Width, p.Height})
	}
	want := [][3]int32{{1, 1152, 864}, {2, 960, 720}, {3, 792, 600}}
	if len(got) != 3 || got[0] != want[0] || got[1] != want[1] || got[2] != want[2] {
		t.Errorf("pages %v, want %v", got, want)
	}
	// with no Ocr port an image page has no text: the body is the page milestones the paragraph stage writes between pages (P6.5)
	for _, c := range m.Root().Children() {
		if c.Kind().Name() != "break" {
			t.Errorf("an image page has a %s with no OCR port", c.Kind().Name())
		}
	}
}

func TestStageZeroOfAnImageReadsNoPixels(t *testing.T) {
	e := NewEngine(DefaultConfig())
	v, err := e.Intake([]Input{BytesInput(imageFx(t, "tiff-multi3-g4.tif")), BytesInput(imageFx(t, "jpeg-420.jpg")), BytesInput(synth(t, "h-plain"))})
	if err != nil {
		t.Fatal(err)
	}
	if v[0].Format != "tiff" || v[0].Pages != 3 || v[1].Format != "jpeg" || v[1].Pages != 1 || v[2].Format != "pdf" || v[2].Pages != 2 {
		t.Errorf("intake %+v", v)
	}
}

func TestAnImageRecordsItsPixelsAndReplaysWithoutACodec(t *testing.T) {
	e := NewEngine(DefaultConfig())
	in := imageFx(t, "tiff-multi3-gray-none.tif")
	m, tr, err := e.Record(one(BytesInput(in)), DefaultOptions())
	m = must(t, m, err)
	// the document pass asks each frame's HEADER (frame 0, then frames 1 and 2 together: rounds 0 and 1, no pixels), then each
	// frame is its own page unit (P7.U, D207): its decode (round 0, the bitmap kept) and its OCR (round 1: no Ocr port is
	// configured, so it is answered `unsupported`, P6.5, D211)
	if len(tr.Entries) != 9 {
		t.Fatalf("entries: %d", len(tr.Entries))
	}
	ids := []int32{1, 2, 3, 260, 272, 516, 528, 772, 784}
	for i, want := range [][2]int32{{0, 2}, {1, 2}, {1, 2}, {0, 2}, {1, 3}, {0, 2}, {1, 3}, {0, 2}, {1, 3}} {
		if tr.Entries[i].Round != want[0] || tr.Entries[i].Req[1] != want[1] || tr.Entries[i].Req[2] != ids[i] {
			t.Errorf("entry %d: round %d kind %d id %d", i, tr.Entries[i].Round, tr.Entries[i].Req[1], tr.Entries[i].Req[2])
		}
		if (tr.Entries[i].Req[1] == 2 && tr.Entries[i].Req[7] >= 0) != (tr.Entries[i].Bitmap != nil) {
			t.Errorf("entry %d: a decode keeps its pixels and an unsupported OCR has none", i)
		}
		if tr.Entries[i].Req[1] == 3 && tr.Entries[i].Ans[3] != 2 {
			t.Errorf("entry %d: OCR without a port is `unsupported`", i)
		}
	}
	// the transcript's files survive a round trip (the pixels as canonical PNG), and replaying them gives the same Model
	files := map[string][]byte{}
	for _, f := range tr.ToFiles() {
		files[f.Name] = f.Bytes
	}
	t2, err := port.TranscriptFromFiles(files)
	if err != nil {
		t.Fatal(err)
	}
	r, err := e.Replay(one(BytesInput(in)), DefaultOptions(), t2)
	if r = must(t, r, err); js(r) != js(m) {
		t.Error("the replay differs from the recording")
	}
	// a replay of another image misses: the request is not in the transcript
	if _, err := e.Replay(one(BytesInput(imageFx(t, "tiff-multi3-rgb-lzw.tif"))), DefaultOptions(), t2); err == nil {
		t.Error("a replay of another image did not miss")
	}
	// a plain conversion keeps no pixels, and says the same thing
	c, err := e.Convert(one(BytesInput(in)), DefaultOptions())
	if c = must(t, c, err); js(c) != js(m) {
		t.Error("convert differs from record")
	}
}

// ------------------------------------------------------------------------------------- OCR (P6.5, D211-D216)
//
// A FAKE `Ocr` port: it answers every request with the word `Hello` in one line, through the same kin builders a real engine's
// adapter uses, so the whole path (the request kin builds, the answer kin reads, the evidence, the pipeline's lines, the Model's
// provenance) runs without Tesseract. What the real engine does to a real page is `bb ocr` (a replayed transcript) and `bb ocr-live`.
type fakeOcr struct{}

func (fakeOcr) Fulfil(req port.PlanRequest, _ port.PlanBitmaps, _ bool) (port.PlanAnswer, error) {
	c := port.NewCells()
	at := plan.AnOcr(c, req.ID())
	w := plan.AnOcrWord(c, at, 10, 10, 100, 30, 900, 0, 0)
	for _, ch := range "Hello" {
		plan.AnOcrCp(c, w, int32(ch))
	}
	ocr.OxBegin(c, 0, 0, 0, 0, -1, 0, -1, -1, 1, 1, 0, 0, 0)
	ocr.OxLine(c, 10, 10, 100, 30, 10, 35, 110, 35, 0, 0, 0, 20, 14, 6)
	ocr.OxBlock(c, 10, 10, 100, 30, 0, 0, 0)
	ocr.OxWord(c, 0, 0, -1, 0)
	plan.AnOcrEnd(c, at)
	return port.PlanAnswer{Rec: cellsVec(c)}, nil
}

func (fakeOcr) Producer() [][2]string { return [][2]string{{"engine", "fake"}} }

func withOcr() *Engine {
	c := DefaultConfig()
	c.Ocr = fakeOcr{}
	return NewEngine(c)
}

func kinds(tr *Transcript) []int32 {
	var k []int32
	for _, en := range tr.Entries {
		k = append(k, en.Req[1])
	}
	return k
}

func TestAnImageIsReadByTheOcrPortAndItsTranscriptReplaysWithoutIt(t *testing.T) {
	in := imageFx(t, "png-phys300.png")
	o := DefaultOptions()
	m, tr, err := withOcr().Record(one(BytesInput(in)), o)
	m = must(t, m, err)
	j := js(m)
	for _, want := range []string{`"text": "Hello"`, `"ocr": "always"`, `"ocr-only"`} {
		if !strings.Contains(j, want) {
			t.Errorf("%s is not in %s", want, j)
		}
	}
	// the header, then the page unit's decode and ocr; the transcript says whose engine answered
	if k := kinds(tr); len(k) != 3 || k[0] != 2 || k[1] != 2 || k[2] != 3 {
		t.Errorf("kinds %v", k)
	}
	if len(tr.Producer) != 1 || tr.Producer[0] != [2]string{"engine", "fake"} {
		t.Errorf("producer %v", tr.Producer)
	}
	// a replay needs no port, and gives the same Model; the files of the transcript survive a round trip
	files := map[string][]byte{}
	for _, f := range tr.ToFiles() {
		files[f.Name] = f.Bytes
	}
	if !strings.Contains(string(files["transcript.edn"]), `:producer {:engine "fake"}`) {
		t.Errorf("the producer is not in the file: %s", files["transcript.edn"])
	}
	t2, err := port.TranscriptFromFiles(files)
	if err != nil || len(t2.Producer) != 1 {
		t.Fatalf("%v %v", err, t2)
	}
	r, err := NewEngine(DefaultConfig()).Replay(one(BytesInput(in)), o, t2)
	if js(must(t, r, err)) != j {
		t.Error("the replay differs from the recording")
	}
	// `never` asks for nothing: no OCR request, no text and no setting
	never := o
	never.Ocr = OcrNever
	mn, tn, err := withOcr().Record(one(BytesInput(in)), never)
	jn := js(must(t, mn, err))
	if k := kinds(tn); len(k) != 2 || k[0] != 2 || k[1] != 2 || strings.Contains(jn, "Hello") || strings.Contains(jn, `"ocr"`) {
		t.Errorf("never: %v %s", k, jn)
	}
	// with no port the request is answered `unsupported` and the page has no text, as before OCR existed
	mp, err := NewEngine(DefaultConfig()).Convert(one(BytesInput(in)), o)
	if js(must(t, mp, err)) != jn {
		t.Error("no port is not `never`")
	}
}

func TestAScannedPDFPageIsOcredAndABornDigitalOneKeepsItsTextLayer(t *testing.T) {
	scan := read(t, "test/corpus/synth/scan/h-keyword.pdf")
	o := DefaultOptions()
	m, tr, err := withOcr().Record(one(BytesInput(scan)), o)
	m = must(t, m, err)
	// the stream's own bytes (filter DCT, kind 2), then OCR of that bitmap (kind 3): two rounds
	if k := kinds(tr); len(k) != 2 || k[0] != 2 || k[1] != 3 || tr.Entries[0].Round != 0 || tr.Entries[1].Round != 1 {
		t.Fatalf("kinds %v", k)
	}
	if tr.Entries[0].Req[6] != 1 || tr.Entries[0].Bitmap == nil {
		t.Error("the DCT filter, with the pixels an engine will read")
	}
	j := js(m)
	if !strings.Contains(j, `"kind": "scanned"`) || !strings.Contains(j, "Hello") {
		t.Errorf("%s", j)
	}
	files := map[string][]byte{}
	for _, f := range tr.ToFiles() {
		files[f.Name] = f.Bytes
	}
	t2, err := port.TranscriptFromFiles(files)
	if err != nil {
		t.Fatal(err)
	}
	r, err := NewEngine(DefaultConfig()).Replay(one(BytesInput(scan)), o, t2)
	if js(must(t, r, err)) != j {
		t.Error("the replay differs from the recording")
	}
	// no port: the page is there and has no text, and the Model says nothing about OCR
	mn, err := NewEngine(DefaultConfig()).Convert(one(BytesInput(scan)), o)
	none := js(must(t, mn, err))
	if strings.Contains(none, "Hello") || strings.Contains(none, `"ocr"`) || !strings.Contains(none, `"kind": "vector"`) {
		t.Errorf("%s", none)
	}
	// a born-digital page keeps its text layer under `always`: it asks for its rasters and for no OCR, and the Model is the same
	// with or without a port (D212, D199)
	born := synth(t, "h-plain")
	mb, tb, err := withOcr().Record(one(BytesInput(born)), o)
	mb = must(t, mb, err)
	for _, en := range tb.Entries {
		if en.Req[1] != 1 {
			t.Error("a text layer is read as it is: no OCR is asked")
		}
	}
	mp, err := NewEngine(DefaultConfig()).Convert(one(BytesInput(born)), o)
	if js(must(t, mp, err)) != js(mb) {
		t.Error("the port changed a born-digital Model")
	}
}

func TestAnImageThroughASourceThatMissesIsTheInMemoryOne(t *testing.T) {
	for _, name := range []string{"png-rgba8.png", "jpeg-444.jpg", "tiff-multi3-g4.tif", "tiff-tiled16.tif"} {
		b := imageFx(t, name)
		a, err := NewEngine(DefaultConfig()).Convert(one(BytesInput(b)), DefaultOptions())
		a = must(t, a, err)
		c, err := asyncEngine(64).Convert(one(BytesInput(b)), DefaultOptions())
		if c = must(t, c, err); js(a) != js(c) {
			t.Errorf("%s: the async run differs", name)
		}
	}
}

func TestAnImageThatCannotBeReadIsRefusedByName(t *testing.T) {
	e := NewEngine(DefaultConfig())
	for name, want := range map[string]string{"tiff-jpeg.tif": "unsupported-image", "jpeg-cmyk.jpg": "unsupported-image",
		"png-truncated.png": "undecodable", "tiff-truncated.tif": "undecodable"} {
		if _, err := e.Convert(one(BytesInput(imageFx(t, name))), DefaultOptions()); errName(err) != want {
			t.Errorf("%s: %v, want %s", name, err, want)
		}
	}
	// the pixels bound is a named limit, checked from the header before a pixel is decoded
	c := DefaultConfig()
	c.Limits = append(c.Limits, [2]int32{9, 1000})
	if _, err := NewEngine(c).Convert(one(BytesInput(imageFx(t, "png-rgb8.png"))), DefaultOptions()); errName(err) != "pixels" {
		t.Errorf("the pixels bound: %v", err)
	}
}
