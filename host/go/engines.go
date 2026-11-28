package emon

// THE ENGINE REGISTRY, Go host (DEVLOG D925, K3; the twin of `host/rust/src/adapter.rs` and `host/c/include/emon_adapter.h`, D899).
//
// An OCR engine is an ADAPTER that is registered, never wired: a library with the one export `emon_ocr_adapter_v1` (`Engines.Load`, cgo, `host/go/adapterlib`) or an
// in-process value (`Engines.Add`). Kin decides every rule and this file only carries them out: what an adapter may declare (`adapters.AdRegisterCheck`, against the engine
// table), which adapter a request goes to (`adapters.AdRoute`: the first registered for the OIL engine the request names), what an unread page is (`adapters.AdRefusal`) and
// what an answer is held to (`adapters.AdAnswerCheck`, `oil.OilRecordCheckBitmap`). `*Engines` is itself the `Ocr` port: `Config.Ocr = engines`.
//
// Loading a library needs a loader (`SetLoader(adapterlib.Load)`, the cgo package `host/go/adapterlib`), so this package stays pure Go. Sync adapters only: one that says it is `AdapterAsync` is refused by name until this host has a mailbox. Remote adapters (pixels leave the device) are refused unless
// `AllowRemote` was set first.

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"

	"emon/adapters"
	"emon/engines"
	"emon/oil"
	"emon/ocrport"
	"emon/plan"
	"emon/port"
)

// The flags an adapter sets (`EMON_ADAPTER_*`).
const (
	AdapterAsync      uint32 = 1
	AdapterRemote     uint32 = 2
	AdapterThreadsafe uint32 = 4
)

// How an adapter says a reading went (`EMON_ADAPTER_OK`, `_UNSUPPORTED`, `_FAILED`).
const (
	AdapterOK          int32 = 0
	AdapterUnsupported int32 = 1
	AdapterFailed      int32 = 2
)

// AdapterInfo is what an adapter says it is: the OIL engine it answers as (`oen`: 1 tesseract, 2 ppocr, 3 apple-vision, 4 mlkit-text, 5 windows-media-ocr), the capabilities
// it declares and which of them it measures, the pack kinds it reads with, its flags, its requested configuration and observed identity, and the transcript's producer pairs.
type AdapterInfo struct {
	Name     string
	Engine   uint32
	Caps     uint32
	Measured uint32
	Needs    uint32
	Flags    uint32
	Config   string
	Observed string
	Producer [][2]string
}

// Reading is what an adapter answered: the OIL answer record, or Status AdapterUnsupported / AdapterFailed with a Reason word (the page is then unread, never blank).
type Reading struct {
	Rec    []int32
	Binary *port.Bitmap
	Status int32
	Reason string
}

// Adapter is one OCR engine. Read answers one `ocr` request (bm holds its bitmap); keepBinary asks it to keep its thresholded image.
type Adapter interface {
	Info() AdapterInfo
	Read(req port.PlanRequest, bm port.PlanBitmaps, keepBinary bool) (Reading, error)
	Close()
}

// Loader opens the adapter library at path with its own config (key=value lines) and the pack directory (or ""): `adapterlib.Load`.
type Loader func(path, config, packsDir string) (Adapter, error)

// Engines is the registry. The zero value is not usable: NewEngines.
type Engines struct {
	loader      Loader
	mu          sync.Mutex
	entries     []*entry
	allowRemote bool
	adapterDir  string
	tessdata    string
	notes       []string
	packs       *Packs
	modeSet     bool
	renderer    bool
}

type entry struct {
	a    Adapter
	info AdapterInfo
	gate sync.Mutex // an adapter that is not thread safe is called one thread at a time
	// engine is the kin engine whose packs the adapter reads (its view of the pack store); ownPacks is true for an adapter given its own model directory, which this
	// registry does not manage packs for
	engine   string
	ownPacks bool
}

// NewEngines is an empty registry: no adapter, nothing loaded, nothing fetched.
func NewEngines() *Engines { return &Engines{packs: newPacks()} }

// SetLoader names how adapter libraries are opened (`adapterlib.Load`). Without one `Load` and `Defaults` say so; `Add` of an in-process adapter needs none.
func (e *Engines) SetLoader(l Loader) { e.mu.Lock(); e.loader = l; e.mu.Unlock() }

// AllowRemote lets adapters that send the pixels off the device register (default no: documents may hold personal data). Call it before registering them.
func (e *Engines) AllowRemote(allow bool) { e.mu.Lock(); e.allowRemote = allow; e.mu.Unlock() }

// SetAdapterDir names the directory `Defaults` loads adapter libraries from ($EMON_ADAPTER_DIR, else `lib/emon/adapters` beside the executable).
func (e *Engines) SetAdapterDir(dir string) { e.mu.Lock(); e.adapterDir = dir; e.mu.Unlock() }

// SetTessdata names the models directory `Defaults` gives the Tesseract adapter ($EMON_TESSDATA, $TESSDATA_PREFIX, share/emon/tessdata beside the executable).
func (e *Engines) SetTessdata(dir string) { e.mu.Lock(); e.tessdata = dir; e.mu.Unlock() }

// Add registers an adapter. It is checked against kin's engine table (`ad-register-check`) and closed when it is refused; the error is the refusal's name and sentence.
func (e *Engines) Add(a Adapter) error { return e.add(a, "", false) }

func (e *Engines) add(a Adapter, engine string, own bool) error {
	info := a.Info()
	if info.Name == "" {
		info.Name = "adapter"
	}
	if check := adapters.AdRegisterCheck(adapters.AdAbi(), int32(info.Engine), int32(info.Caps), int32(info.Measured), int32(info.Needs), int32(info.Flags)); check != adapters.AdrOk() {
		var why strings.Builder
		adapters.AdrEmitName(&why, check)
		a.Close()
		return fmt.Errorf("%s: %s (engine %d, caps %d, needs %d, flags %d)", why.String(), info.Name, info.Engine, info.Caps, info.Needs, info.Flags)
	}
	if info.Flags&AdapterAsync != 0 {
		a.Close()
		return fmt.Errorf("adapter-abi-unsupported: %s is async; this host registers sync adapters only", info.Name)
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if info.Flags&AdapterRemote != 0 && !e.allowRemote {
		a.Close()
		return fmt.Errorf("adapter-remote-refused: %s sends the pixels off the device; call AllowRemote(true) first", info.Name)
	}
	if !hasProducerKey(info.Producer, "engine") {
		info.Producer = append([][2]string{{"engine", info.Name}}, info.Producer...)
	}
	if engine == "" {
		if eng := adapters.AdOenEng(int32(info.Engine)); eng >= 0 {
			engine = engName(eng)
		}
	}
	e.entries = append(e.entries, &entry{a: a, info: info, engine: engine, ownPacks: own})
	return nil
}

func hasProducerKey(p [][2]string, k string) bool {
	for _, kv := range p {
		if kv[0] == k {
			return true
		}
	}
	return false
}

// Load registers the adapter library at path (`emon_ocr_adapter_v1`); config is the adapter's own `key=value` lines, packsDir the pack directory (or "").
func (e *Engines) Load(path, config, packsDir string) error {
	e.mu.Lock()
	load := e.loader
	e.mu.Unlock()
	if load == nil {
		return errNoAdapterLoader
	}
	engine := engineOfLibrary(path, config)
	// an adapter given its own model directory (an explicit `tessdata=`, or packsDir) reads from it; else it is given its view of the pack store
	own := packsDir != "" || strings.Contains("\n"+config, "\ntessdata=")
	if !own && engine != "" {
		view, err := e.packs.View(engine)
		if err != nil {
			return fmt.Errorf("pack-dir-missing: %v", err)
		}
		packsDir = view
	}
	a, err := load(path, config, packsDir)
	if err != nil {
		return err
	}
	return e.add(a, engine, own)
}

// engineOfLibrary names the kin engine whose packs the adapter at path reads: `engine=<name>` in its config, else the OCR engine of kin's table whose name the file's carries.
func engineOfLibrary(path, config string) string {
	for _, l := range strings.Split(config, "\n") {
		if v, ok := strings.CutPrefix(l, "engine="); ok {
			return strings.TrimSpace(v)
		}
	}
	stem := strings.ToLower(strings.ReplaceAll(strings.TrimSuffix(filepath.Base(path), filepath.Ext(path)), "-", "_"))
	for i := int32(0); i < engines.PkEngineCount(); i++ {
		eng := engines.PkEngineAt(i)
		if engines.PkEngineKind(eng) != engines.EkindOcr() {
			continue
		}
		if n := engName(eng); strings.Contains(stem, strings.ReplaceAll(n, "-", "_")) {
			return n
		}
	}
	return ""
}

// Defaults is the turnkey: every `libemon_ocr_*` of the adapter directory, in name order. The Tesseract adapter is NOT loaded when the selection does not use Tesseract as its
// reader or fallback (`pk-uses-tesseract`, D920): a PP-OCR-alone conversion registers PP-OCR only.
func (e *Engines) Defaults(opts Options) error {
	uses := engines.PkUsesTesseract(engines.PkOptionEngine(opts.OcrEngine)) == 1
	if err := e.turnkeyPacks(); err != nil {
		return err
	}
	libs, err := e.adapterLibs()
	if err != nil {
		return err
	}
	for _, l := range libs {
		stem := strings.TrimSuffix(filepath.Base(l), filepath.Ext(l))
		cfg := ""
		if stem == "libemon_ocr_tesseract" || stem == "emon_ocr_tesseract" {
			if !uses {
				continue
			}
			if dir := e.findTessdata(); dir != "" {
				cfg = "tessdata=" + dir
			}
		}
		if err := e.Load(l, cfg, ""); err != nil {
			return err
		}
	}
	return nil
}

func (e *Engines) adapterLibs() ([]string, error) {
	e.mu.Lock()
	dir := e.adapterDir
	e.mu.Unlock()
	if dir == "" {
		dir = os.Getenv("EMON_ADAPTER_DIR")
	}
	list := func(d string) []string {
		ents, _ := os.ReadDir(d)
		var v []string
		for _, f := range ents {
			n := f.Name()
			if f.Type().IsRegular() && (strings.HasPrefix(n, "libemon_ocr_") || strings.HasPrefix(n, "emon_ocr_")) && isLibraryName(n) {
				v = append(v, filepath.Join(d, n))
			}
		}
		sort.Strings(v)
		return v
	}
	if dir != "" {
		if st, err := os.Stat(dir); err != nil || !st.IsDir() {
			return nil, fmt.Errorf("the adapter directory %s is not a directory", dir)
		}
		return list(dir), nil
	}
	if exe, err := os.Executable(); err == nil {
		b := filepath.Dir(exe)
		for _, c := range []string{filepath.Join(b, "emon", "adapters"), filepath.Join(b, "lib", "emon", "adapters"), filepath.Join(filepath.Dir(b), "lib", "emon", "adapters")} {
			if v := list(c); len(v) > 0 {
				return v, nil
			}
		}
	}
	return nil, nil
}

func isLibraryName(n string) bool {
	switch runtime.GOOS {
	case "darwin":
		return strings.HasSuffix(n, ".dylib")
	case "windows":
		return strings.HasSuffix(n, ".dll")
	}
	return strings.HasSuffix(n, ".so")
}

func (e *Engines) findTessdata() string {
	e.mu.Lock()
	dir := e.tessdata
	e.mu.Unlock()
	if dir != "" {
		return dir
	}
	for _, v := range []string{"EMON_TESSDATA", "TESSDATA_PREFIX"} {
		if d := os.Getenv(v); d != "" {
			return d
		}
	}
	if exe, err := os.Executable(); err == nil {
		d := filepath.Join(filepath.Dir(filepath.Dir(exe)), "share", "emon", "tessdata")
		if st, err := os.Stat(d); err == nil && st.IsDir() {
			return d
		}
	}
	return ""
}

// Describe lists the registered adapters in registration order.
func (e *Engines) Describe() []AdapterInfo {
	e.mu.Lock()
	defer e.mu.Unlock()
	out := make([]AdapterInfo, len(e.entries))
	for i, en := range e.entries {
		out[i] = en.info
	}
	return out
}

// Notes returns, and clears, what adapters reported since the last call (why a request was left unread), one line each.
func (e *Engines) Notes() []string {
	e.mu.Lock()
	n := e.notes
	e.notes = nil
	e.mu.Unlock()
	return append(n, e.packs.TakeNotes()...)
}

// Close closes every adapter. The registry is empty after it.
func (e *Engines) Close() {
	e.mu.Lock()
	es := e.entries
	e.entries = nil
	e.mu.Unlock()
	for _, en := range es {
		en.a.Close()
	}
}

// Producer implements Ocr: the first adapter's pairs, the combined engine name when there are several (`tesseract+ppocr`), then the other adapters' further keys.
func (e *Engines) Producer() [][2]string {
	e.mu.Lock()
	defer e.mu.Unlock()
	var combined string
	if len(e.entries) > 1 {
		var names []string
		for _, en := range e.entries {
			var n strings.Builder
			engines.EngEmitName(&n, adapters.AdOenEng(int32(en.info.Engine)))
			names = append(names, n.String())
		}
		combined = strings.Join(names, "+")
	}
	var out [][2]string
	for i, en := range e.entries {
		for _, kv := range en.info.Producer {
			if combined != "" && kv[0] == "ocr-engine" {
				continue
			}
			if !hasProducerKey(out, kv[0]) {
				out = append(out, kv)
			}
		}
		if i == 0 && combined != "" {
			out = append(out, [2]string{"ocr-engine", combined})
		}
	}
	return out
}

func cellsFrom(v []int32) *port.Cells {
	c := port.NewCells()
	for _, x := range v {
		port.CellsPush(c, x)
	}
	return c
}

func (e *Engines) note(s string) { e.mu.Lock(); e.notes = append(e.notes, s); e.mu.Unlock() }

// Fulfil implements Ocr: route the request by the engine it names, read, and hold the answer to what the adapter registered.
func (e *Engines) Fulfil(req port.PlanRequest, bm port.PlanBitmaps, keepBinary bool) (port.PlanAnswer, error) {
	reqOen := adapters.AdRequestOen(ocrport.OpRequestEngine(cellsFrom(req.Rec)))
	e.mu.Lock()
	reg := port.NewCells()
	for _, en := range e.entries {
		port.CellsPush(reg, int32(en.info.Engine))
	}
	at := adapters.AdRoute(reqOen, reg, 0)
	var en *entry
	if at >= 0 && int(at) < len(e.entries) {
		en = e.entries[at]
	}
	e.mu.Unlock()
	refuse := func(status int32) (port.PlanAnswer, error) {
		out := port.NewCells()
		adapters.AdRefusal(out, req.ID(), status)
		return port.PlanAnswer{Rec: cellsVec(out)}, nil
	}
	if en == nil {
		return refuse(adapters.AdsUnsupported())
	}
	// the packs the request needs, made present or refused (D896)
	for i := 0; i < 2; i++ {
		other, why := e.ensurePacks(en, &req)
		if why != "" {
			e.note(fmt.Sprintf("%s: unsupported %s", en.info.Name, why))
			return refuse(adapters.AdsUnsupported())
		}
		if other == nil {
			break
		}
		en = other
	}
	if en.info.Flags&AdapterThreadsafe == 0 {
		en.gate.Lock()
		defer en.gate.Unlock()
	}
	r, err := en.a.Read(req, bm, keepBinary)
	if err != nil {
		return port.PlanAnswer{}, err
	}
	if r.Status != AdapterOK {
		e.note(fmt.Sprintf("%s: %s %s", en.info.Name, map[bool]string{true: "unsupported", false: "failed"}[r.Status == AdapterUnsupported], r.Reason))
		return refuse(r.Status)
	}
	bad := func(why string) (port.PlanAnswer, error) {
		return port.PlanAnswer{}, &port.FulfilError{Msg: "ocr-adapter-invalid: " + en.info.Name + ": " + why}
	}
	rec := r.Rec
	if len(rec) < 4 || int(rec[0]) != len(rec) || rec[1] != plan.PkOcr() || rec[2] != req.ID() {
		return bad("the answer is not a record of the request's `ocr` answer")
	}
	if rec[3] != 0 {
		if len(rec) == 4 {
			st := adapters.AdsFailed()
			if rec[3] == plan.PsUnsupported() {
				st = adapters.AdsUnsupported()
			}
			return refuse(st)
		}
		return bad("a status that is not ok on a record with a reading")
	}
	b := bm[req.Field(plan.OcBitmap())]
	if b == nil {
		return bad("the request names no bitmap")
	}
	c := cellsFrom(rec)
	if oil.OilRecordCheckBitmap(c, 0, b.W, b.H, port.NewCells()) != 0 {
		return bad("the reading is not valid OIL for this bitmap")
	}
	if check := adapters.AdAnswerCheck(int32(en.info.Engine), int32(en.info.Caps), reqOen, oil.OilGet(c, 0, oil.OhEngine()), oil.OilGet(c, 0, oil.OhCaps())); check != adapters.AdaOk() {
		var why strings.Builder
		adapters.AdaEmitName(&why, check)
		return bad(why.String())
	}
	a := port.PlanAnswer{Rec: append([]int32(nil), rec...)}
	if keepBinary && r.Binary != nil {
		a.Bitmap = r.Binary
	}
	return a, nil
}

var errNoAdapterLoader = errors.New("adapter-load-failed: no loader: engines.SetLoader(adapterlib.Load) (github.com/3sln/emon/go/adapterlib, cgo, macOS and Linux)")
