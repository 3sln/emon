// THE GO DRIVER (DEVLOG P2.1): a test-only CLI over the Engine API, the twin of `test/driver/rust/main.rs`
// with the same flags, the same stdout and the same exit codes, so that `bb identity` can hold them
// byte for byte. Its STDOUT is the only thing gates read; stats and errors go to stderr. It is not the
// library: it reads the file itself and hands the Engine bytes, an `io.ReaderAt`, a seekable `*os.File`
// or a spooled `io.Reader` (D058).
//
//	emon-go [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG]
//	        [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--tesseract[=TESSDATA]] [--assets=none] [--password=TEXT]
//	        [--source=bytes|readerat|seek|spool] [--chunk=N --async] [--stats]
//	        [--record=DIR | --replay=DIR] [--parallel=N] FILE
//
//	--source=        which adapter reads FILE: `bytes` (in memory), `readerat` (*os.File + size: Go's native
//	                 form, SPEC 13.1), `seek` (io.ReadSeeker), `spool` (io.Reader, spooled)
//	--out=bundle     one conversion, every output, for `bb identity` (see the Rust driver: the sections are the same)
//	--out=pixels     one recording as one line per decode entry (see the Rust driver; `bb image` compares them)
//	--dump=DIR       with --out=pixels, also write each frame's pixels to DIR/frame-N.raw
//	--tesseract[=D]  give the Engine the Tesseract `Ocr` port (D211), reading models from D (default: $TESSDATA_PREFIX and the usual
//	                 places). LIVE: only a build with the `tesseract` tag has it (`EMON_GO_LIVE=1 kin/scripts/emon-go-build`, cgo); no gate
//	                 uses it. Without the flag an `ocr` request is answered `unsupported` and a page that needed OCR has no text.
//	--record=DIR     also write the plan/fulfil transcript to DIR (transcript.edn and PNGs)
//	--replay=DIR     convert from the transcript in DIR (no port is called)
//	--parallel=N     run N conversions at once on one Engine; all N must give the same answer (`bb repeat`)
//
// Exit: 0 ok; 2 usage; 4 a named emon error (`error: NAME` on stderr, nothing on stdout); 1 anything else.
package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"

	"emon"
	_ "emon/pngenc"
	"emon/port"
)

func usage(msg string) {
	fmt.Fprintf(os.Stderr, "emon-go: %s\n", msg)
	fmt.Fprintln(os.Stderr, "usage: emon-go [--out=json|html|evidence|intake] [--pretty] [--html=OPTS] [--locale=TAG] [--ocr=always|auto|never] [--furniture=policy|keep|drop] [--assets=none] [--password=TEXT] [--source=bytes|readerat|seek|spool] [--chunk=N --async] [--stats] [--record=DIR | --replay=DIR] [--parallel=N] FILE")
	os.Exit(2)
}

func htmlOptions(list string) emon.HTMLOptions {
	o := emon.DefaultHTMLOptions()
	for _, item := range strings.Split(list, ",") {
		switch {
		case item == "":
		case item == "ids":
			o.IDs = true
		case item == "geometry":
			o.Geometry = true
		case item == "ledger":
			o.Ledger = true
		case item == "pretty":
			o.Pretty = true
		case item == "drop-furniture":
			o.FurnitureDrop = true
		case item == "no-milestones":
			o.Milestones = false
		case item == "initials-plain":
			o.InitialsPlain = true
		case item == "align-style":
			o.AlignStyle = true
		case strings.HasPrefix(item, "confidence="):
			n, err := strconv.ParseUint(strings.TrimPrefix(item, "confidence="), 10, 32)
			if err != nil {
				usage("confidence=N wants a number")
			}
			v := uint32(n)
			o.ConfidenceBelow = &v
		default:
			usage("unknown html option " + item)
		}
	}
	return o
}

func readDir(dir string) (map[string][]byte, error) {
	ents, err := os.ReadDir(dir)
	if err != nil {
		return nil, err
	}
	files := map[string][]byte{}
	for _, e := range ents {
		b, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			return nil, err
		}
		files[e.Name()] = b
	}
	return files, nil
}

// result is one conversion's whole answer: the text, or the error.
type result struct {
	text  string
	err   error
	stats emon.RunStats
	trans *emon.Transcript
}

func main() {
	out := "json"
	pretty := false
	html := emon.DefaultHTMLOptions()
	opts := emon.DefaultOptions()
	cfg := emon.DefaultConfig()
	var password *string
	source := "bytes"
	stats := false
	var file, record, replay, dump string
	tess, tessdata := false, ""
	parallel := 1
	for _, a := range os.Args[1:] {
		switch {
		case strings.HasPrefix(a, "--out="):
			out = strings.TrimPrefix(a, "--out=")
		case a == "--pretty":
			pretty = true
		case strings.HasPrefix(a, "--html="):
			html = htmlOptions(strings.TrimPrefix(a, "--html="))
		case strings.HasPrefix(a, "--locale="):
			opts.Locale = strings.TrimPrefix(a, "--locale=")
		case strings.HasPrefix(a, "--ocr="):
			switch strings.TrimPrefix(a, "--ocr=") {
			case "always":
				opts.Ocr = emon.OcrAlways
			case "auto":
				opts.Ocr = emon.OcrAuto
			case "never":
				opts.Ocr = emon.OcrNever
			default:
				usage("--ocr is always, auto or never")
			}
		case strings.HasPrefix(a, "--furniture="):
			switch strings.TrimPrefix(a, "--furniture=") {
			case "policy":
				opts.Furniture = emon.FurniturePolicy
			case "keep":
				opts.Furniture = emon.FurnitureKeep
			case "drop":
				opts.Furniture = emon.FurnitureDrop
			default:
				usage("--furniture is policy, keep or drop")
			}
		case strings.HasPrefix(a, "--assets="):
			switch strings.TrimPrefix(a, "--assets=") {
			case "none":
				opts.Assets = emon.AssetsNone
			case "images":
				opts.Assets = emon.AssetsImages
			case "figures":
				opts.Assets = emon.AssetsFigures
			default:
				usage("--assets is none, images or figures")
			}
		case strings.HasPrefix(a, "--password="):
			p := strings.TrimPrefix(a, "--password=")
			password = &p
		case strings.HasPrefix(a, "--source="):
			source = strings.TrimPrefix(a, "--source=")
		case strings.HasPrefix(a, "--chunk="):
			n, err := strconv.Atoi(strings.TrimPrefix(a, "--chunk="))
			if err != nil {
				usage("--chunk=N wants a number")
			}
			cfg.ChunkSize = n
		case a == "--async":
			cfg.SimulateAsync = true
		case a == "--tesseract":
			tess = true
		case strings.HasPrefix(a, "--tesseract="):
			tess, tessdata = true, strings.TrimPrefix(a, "--tesseract=")
		case a == "--stats":
			stats = true
		case strings.HasPrefix(a, "--dump="):
			dump = strings.TrimPrefix(a, "--dump=")
		case strings.HasPrefix(a, "--record="):
			record = strings.TrimPrefix(a, "--record=")
		case strings.HasPrefix(a, "--replay="):
			replay = strings.TrimPrefix(a, "--replay=")
		case strings.HasPrefix(a, "--parallel="):
			n, err := strconv.Atoi(strings.TrimPrefix(a, "--parallel="))
			if err != nil || n < 1 {
				usage("--parallel=N wants a positive number")
			}
			parallel = n
		case strings.HasPrefix(a, "--"):
			usage("unknown flag " + a)
		case file == "":
			file = a
		default:
			usage("one FILE")
		}
	}
	if file == "" {
		usage("no FILE")
	}
	if record != "" && replay != "" {
		usage("--record and --replay are two ways")
	}
	opts.KeepEvidence = out == "evidence" || out == "bundle"

	fail := func(msg string, err error) {
		fmt.Fprintf(os.Stderr, "emon-go: %s: %v\n", msg, err)
		os.Exit(1)
	}

	// THE DRIVER READS THE FILE (the library never does): four ways of handing the Engine the same bytes.
	var input emon.Input
	switch source {
	case "bytes":
		b, err := os.ReadFile(file)
		if err != nil {
			fail(file, err)
		}
		input = emon.BytesInput(b)
	case "readerat":
		f, err := os.Open(file)
		if err != nil {
			fail(file, err)
		}
		defer f.Close()
		st, err := f.Stat()
		if err != nil {
			fail(file, err)
		}
		input = emon.ReaderAtInput(f, st.Size())
	case "seek":
		f, err := os.Open(file)
		if err != nil {
			fail(file, err)
		}
		defer f.Close()
		if input, err = emon.SeekableInput(f); err != nil {
			fail(file, err)
		}
	case "spool":
		f, err := os.Open(file)
		if err != nil {
			fail(file, err)
		}
		defer f.Close()
		// a plain io.Reader: no seek is used
		if input, err = emon.SpooledInput(forward{f}, 1<<30); err != nil {
			fail(file, err)
		}
	default:
		usage("--source is bytes, readerat, seek or spool")
	}
	if password != nil {
		input = input.WithPassword([]byte(*password))
	}
	inputs := []emon.Input{input}
	if tess {
		o, err := emon.NewTessOcr(tessdata)
		if err != nil {
			usage(err.Error())
		}
		cfg.Ocr = o
	}
	engine := emon.NewEngine(cfg)

	errExit := func(err error) {
		var e *emon.Error
		if errors.As(err, &e) {
			fmt.Fprintf(os.Stderr, "error: %s\n%s\n", e.Name(), e.Error())
			if e.Kind == emon.ErrInternal {
				os.Exit(1)
			}
			os.Exit(4)
		}
		fmt.Fprintf(os.Stderr, "error: internal\n%v\n", err)
		os.Exit(1)
	}

	if out == "intake" {
		v, err := engine.Intake(inputs)
		if err != nil {
			errExit(err)
		}
		for _, i := range v {
			fmt.Printf("%s %d.%d pages=%d encrypted=%v bytes=%d\n", i.Format, i.VersionMajor, i.VersionMinor, i.Pages, i.Encrypted, i.Bytes)
		}
		return
	}
	switch out {
	case "json", "html", "evidence", "bundle", "pixels":
	default:
		usage("--out is json, html, evidence, bundle, pixels or intake")
	}

	var tin *emon.Transcript
	if replay != "" {
		files, err := readDir(replay)
		if err != nil {
			fail(replay, err)
		}
		if tin, err = port.TranscriptFromFiles(files); err != nil {
			errExit(&emon.Error{Kind: emon.ErrPlan, Plan: err.(*port.PlanError)})
		}
	}

	one := func() result {
		var m *emon.Model
		var r result
		var err error
		if out == "pixels" {
			var t *emon.Transcript
			if _, t, err = engine.Record(inputs, opts); err != nil {
				r.err = err
				return r
			}
			r.text = pixelLines(t, dump)
			return r
		}
		switch {
		case tin != nil:
			m, err = engine.Replay(inputs, opts, tin)
		case record != "":
			m, r.trans, err = engine.Record(inputs, opts)
		default:
			m, r.stats, err = engine.ConvertStats(inputs, opts)
		}
		if err != nil {
			r.err = err
			return r
		}
		r.text = render(engine, inputs, m, out, pretty, html)
		return r
	}

	results := make([]result, parallel)
	if parallel == 1 {
		results[0] = one()
	} else {
		var wg sync.WaitGroup
		for i := range results {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				results[i] = one()
			}(i)
		}
		wg.Wait()
	}
	for i, r := range results[1:] {
		a, b := results[0], r
		if a.text != b.text || (a.err == nil) != (b.err == nil) || (a.err != nil && a.err.Error() != b.err.Error()) {
			fmt.Fprintf(os.Stderr, "error: nondeterministic\nconversion %d of %d differs from conversion 0\n", i+1, parallel)
			os.Exit(1)
		}
	}
	r := results[0]
	if r.err != nil {
		errExit(r.err)
	}
	if record != "" {
		if err := os.MkdirAll(record, 0o755); err != nil {
			fail(record, err)
		}
		for _, f := range r.trans.ToFiles() {
			if err := os.WriteFile(filepath.Join(record, f.Name), f.Bytes, 0o644); err != nil {
				fail(record, err)
			}
		}
	}
	if stats {
		s := r.stats
		fmt.Fprintf(os.Stderr, "stats: attempts=%d fetch-rounds=%d bytes-requests=%d source-reads=%d bytes-fetched=%d of %d requests=%d\n",
			s.Attempts, s.FetchRounds, s.BytesRequests, s.SourceReads, s.BytesFetched, s.InputBytes, s.Requests)
	}
	fmt.Print(r.text)
}

// pixelLines is the `--out=pixels` text of a recording: its decode entries, in order.
func pixelLines(t *emon.Transcript, dump string) string {
	var sb strings.Builder
	for _, e := range t.Entries {
		if len(e.Req) < 8 || e.Req[1] != 2 {
			continue
		}
		if e.Ans[3] != 0 {
			fmt.Fprintf(&sb, "frame %d status %d\n", e.Req[7], e.Ans[3])
			continue
		}
		h := uint64(0xcbf29ce484222325)
		if e.Bitmap != nil {
			for _, x := range e.Bitmap.D {
				h ^= uint64(x)
				h *= 0x100000001b3
			}
			if dump != "" {
				if err := os.MkdirAll(dump, 0o755); err != nil {
					panic(err)
				}
				if err := os.WriteFile(filepath.Join(dump, fmt.Sprintf("frame-%d.raw", e.Req[7])), e.Bitmap.D, 0o644); err != nil {
					panic(err)
				}
			}
		}
		fmt.Fprintf(&sb, "frame %d w %d h %d ch %d dpi100 %d bilevel %d frames %d fnv %016x\n", e.Req[7], e.Ans[4], e.Ans[5], e.Ans[6], e.Ans[7], e.Ans[8], e.Ans[9], h)
	}
	return sb.String()
}

// render is the text of `--out=NAME` (or the whole bundle) for a converted Model.
func render(engine *emon.Engine, inputs []emon.Input, m *emon.Model, out string, pretty bool, html emon.HTMLOptions) string {
	switch out {
	case "json":
		return m.Serialize(emon.Output{JSON: &emon.JSONOptions{Pretty: pretty}})
	case "html":
		return m.Serialize(emon.Output{HTML: &html})
	case "evidence":
		ev, _ := m.Evidence()
		return ev
	}
	conf := uint32(950)
	full := emon.HTMLOptions{IDs: true, Geometry: true, ConfidenceBelow: &conf, Ledger: true, Pretty: true, Milestones: true}
	var intake string
	if v, err := engine.Intake(inputs); err != nil {
		var e *emon.Error
		name := "internal"
		if errors.As(err, &e) {
			name = e.Name()
		}
		intake = "error: " + name
	} else {
		var lines []string
		for _, i := range v {
			lines = append(lines, fmt.Sprintf("%s %d.%d pages=%d encrypted=%v bytes=%d", i.Format, i.VersionMajor, i.VersionMinor, i.Pages, i.Encrypted, i.Bytes))
		}
		intake = strings.Join(lines, "\n")
	}
	ev, _ := m.Evidence()
	def := emon.DefaultHTMLOptions()
	return "== json\n" + m.ToJSON(emon.JSONOptions{}) + "\n== json-pretty\n" + m.ToJSON(emon.JSONOptions{Pretty: true}) +
		"\n== html\n" + m.ToHTML(def) + "\n== html-full\n" + m.ToHTML(full) + "\n== evidence\n" + ev + "\n== intake\n" + intake + "\n"
}

// forward hides everything of an *os.File but Read.
type forward struct{ f *os.File }

func (r forward) Read(b []byte) (int, error) { return r.f.Read(b) }
