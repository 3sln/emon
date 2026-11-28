package emon

// THE PACKS, Go host (K3, D925 "Not done" (1); the twin of `host/rust/src/packs.rs`, D899, D896, D913): the model files an adapter reads with, found, verified, fetched
// and handed over.
//
// WHAT IS KIN'S. Which packs exist, their SHA-256 and sizes (`engines.Pk*`, `packdata`), which packs a conversion needs (`engines.PkResolve`), and what to do about one that is
// missing (`adapters.AdPackAction`: `never` and `EMON_OFFLINE=1` win over every hook; a hook fetches, provides, declines or substitutes). This file handles directories,
// files, hashes and the app's callbacks.
//
// NO HTTP IN THE LIBRARY (D913). A `fetch` decision, or `Ensure`, is carried out by the app's `SetFetcher` function, which owns the transport (and the trust store); without one the
// pack is `pack-fetch-unavailable:<id>`. An app that has its own copy says `provide` (bytes or a path). EVERY pack is verified against the catalogue's SHA-256 and size before
// an adapter reads it, wherever it came from; a file that does not match is never used (one in the store is deleted, one in the app's directory is left alone).
//
// LAYOUT. The STORE is a directory the library owns: `<store>/<id>/<file>` and, per engine, `<store>/.views/<engine>/<file>` (a flat directory of links to the verified files, which
// is what an adapter is given as its pack directory). Other directories (`AddDir`, `$EMON_MODELS`) are searched read-only as `<dir>/<id>/<file>` or `<dir>/<file>`.

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"

	"emon/adapters"
	"emon/engines"
	"emon/packdata"
	"emon/port"
)

// PackAction is what the app's hook decides about a missing pack.
type PackAction int32

// The hook's answers (`EMON_PACK_*`, emon.h). The zero value is "no decision" and leaves the pack missing.
const (
	PackFetch      PackAction = 1
	PackProvide    PackAction = 2
	PackDecline    PackAction = 3
	PackSubstitute PackAction = 4
)

// PackEventKind is what a pack event reports.
type PackEventKind int32

// The kinds of a pack event (`EMON_PACK_EVENT_*`).
const (
	PackEventStart     PackEventKind = 1
	PackEventProgress  PackEventKind = 2
	PackEventDone      PackEventKind = 3
	PackEventFailed    PackEventKind = 4
	PackEventCancelled PackEventKind = 5
)

// PackRequest is what the library tells the hook about the pack it needs.
type PackRequest struct {
	Pack          string
	Rev           int32
	Engine        string
	Kind          string
	Licence       string
	Bytes         uint64
	Reason        string
	RequestEngine string
}

// PackDecision is what the hook answers: Fetch; Provide with Data (a one-file pack) or Path (the file, or a directory of the pack's files); Decline; or Substitute with the
// Engine to answer instead. The library copies what it keeps.
type PackDecision struct {
	Action PackAction
	Data   []byte
	Path   string
	Engine string
}

// PackEvent is one entry of the progress stream (`packs.events`): library- and app-initiated downloads alike. Done and Total are bytes of the whole pack.
type PackEvent struct {
	Kind    PackEventKind
	Pack    string
	Engine  string
	Done    uint64
	Total   uint64
	ByApp   bool
	Message string
}

// PackFile is one file of a pack as the catalogue holds it.
type PackFile struct {
	Name  string
	Bytes uint64
	SHA   [32]byte
}

// Matches reports whether bytes are this file's: its size and its SHA-256.
func (f PackFile) Matches(b []byte) bool {
	return uint64(len(b)) == f.Bytes && sha256.Sum256(b) == f.SHA
}

// PackInfo is a pack of the catalogue.
type PackInfo struct {
	Code       int32
	ID         string
	Rev        int32
	Engine     int32
	EngineName string
	Kind       int32
	KindName   string
	Licence    string
	Bytes      uint64
	Files      []PackFile
}

// PackFetcher is the app's transport: fetch the file and return its bytes, calling progress with the bytes received so far (it returns true to stop). The library
// verifies what it returns.
type PackFetcher func(pack PackInfo, file PackFile, progress func(done uint64) (cancel bool)) ([]byte, error)

func packCount() int32 { return engines.PkPackCount() }

func packID(p int32) string {
	var s strings.Builder
	packdata.PkidEmitName(&s, p)
	return s.String()
}

func packFind(name string) (int32, bool) {
	c := packdata.PkidFind([]byte(name), 0, int32(len(name)))
	if c > 0 && engines.PkPackKnown(c) == 1 {
		return c, true
	}
	return 0, false
}

func engName(eng int32) string {
	var s strings.Builder
	engines.EngEmitName(&s, eng)
	return s.String()
}

func packInfo(p int32) PackInfo {
	var files []PackFile
	for i := int32(0); i < engines.PkPackFileCount(p); i++ {
		var name strings.Builder
		engines.PkPackFileName(p, i, &name)
		var sha [32]byte
		for w := int32(0); w < 8; w++ {
			v := uint32(engines.PkPackFileSha(p, i, w))
			sha[4*w], sha[4*w+1], sha[4*w+2], sha[4*w+3] = byte(v>>24), byte(v>>16), byte(v>>8), byte(v)
		}
		files = append(files, PackFile{Name: name.String(), Bytes: uint64(uint32(engines.PkPackFileBytes(p, i))), SHA: sha})
	}
	eng := engines.PkPackEngine(p)
	engine := "any"
	if eng != 0 {
		engine = engName(eng)
	}
	var kind, lic strings.Builder
	engines.PkindEmitName(&kind, engines.PkPackKind(p))
	packdata.PlicEmitName(&lic, engines.PkPackLicence(p))
	return PackInfo{Code: p, ID: packID(p), Rev: engines.PkPackRev(p), Engine: eng, EngineName: engine, Kind: engines.PkPackKind(p), KindName: kind.String(), Licence: lic.String(), Bytes: uint64(uint32(engines.PkPackBytes(p))), Files: files}
}

func platformClass() int32 {
	switch runtime.GOOS {
	case "darwin":
		return engines.PclsMacos()
	case "ios":
		return engines.PclsIos()
	case "windows":
		return engines.PclsWindows()
	case "android":
		return engines.PclsAndroid()
	}
	return engines.PclsLinux()
}

func envText(k string) string { return os.Getenv(k) }

// DefaultPackStore is the store when nobody names one: `$EMON_PACK_CACHE`, else the user's cache directory, else the temporary directory.
func DefaultPackStore() string {
	if d := envText("EMON_PACK_CACHE"); d != "" {
		return d
	}
	var p string
	if base, err := os.UserCacheDir(); err == nil && base != "" {
		p = filepath.Join(base, "emon", "packs")
	}
	if p != "" && os.MkdirAll(p, 0o755) == nil {
		return p
	}
	return filepath.Join(os.TempDir(), "emon-packs")
}

// PacksOffline is `EMON_OFFLINE=1`: nothing is fetched and no hook is asked, whatever the mode says.
func PacksOffline() bool { return envText("EMON_OFFLINE") == "1" }

type stamp struct {
	size int64
	mod  int64
}

// Packs finds, verifies, fetches and hands over the model packs of one registry (`Engines.Packs()`).
type Packs struct {
	mu     sync.Mutex
	store  string
	extra  []string
	mode   int32
	hook   func(PackRequest) PackDecision
	events func(PackEvent) bool
	fetchr PackFetcher
	good   map[string]stamp
	notes  []string
	cancel atomic.Bool
	fetch  sync.Mutex // one fetch at a time: a second conversion needing the same pack waits for the first and finds it
	class  int32
}

func newPacks() *Packs {
	return &Packs{store: DefaultPackStore(), mode: engines.PfetchNever(), good: map[string]stamp{}, class: platformClass()}
}

// SetStore names the directory the library owns (the cache). Name it before registering adapters: each is given its view of it when it is opened.
func (p *Packs) SetStore(dir string) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("pack-dir-missing: the pack directory %s cannot be made: %v", dir, err)
	}
	p.mu.Lock()
	p.store = dir
	p.mu.Unlock()
	return nil
}

// Store is the directory the library owns.
func (p *Packs) Store() string { p.mu.Lock(); defer p.mu.Unlock(); return p.store }

// AddDir adds a directory searched, read-only, for packs (`<dir>/<id>/<file>` or `<dir>/<file>`).
func (p *Packs) AddDir(dir string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	for _, d := range p.extra {
		if d == dir {
			return
		}
	}
	p.extra = append(p.extra, dir)
}

// SetMode sets the pack mode: "never" (the default of a new registry), "cache" (use what is here) or "auto" (a missing pack is fetched when no hook decides otherwise).
func (p *Packs) SetMode(mode string) error {
	c := engines.PfetchFind([]byte(mode), 0, int32(len(mode)))
	if c < 0 {
		return fmt.Errorf("usage: the pack mode is never, cache or auto, not %q", mode)
	}
	p.mu.Lock()
	p.mode = c
	p.mu.Unlock()
	return nil
}

// Mode is the pack mode's name.
func (p *Packs) Mode() string {
	var s strings.Builder
	engines.PfetchEmitName(&s, p.modeCode())
	return s.String()
}

func (p *Packs) modeCode() int32 { p.mu.Lock(); defer p.mu.Unlock(); return p.mode }

// OnPackNeeded sets the app's hook, asked when a conversion needs a pack that is not here (unless the mode is never or EMON_OFFLINE=1). nil clears it.
func (p *Packs) OnPackNeeded(f func(PackRequest) PackDecision) { p.mu.Lock(); p.hook = f; p.mu.Unlock() }

// OnPackEvent sets the one progress stream (START, PROGRESS, DONE, FAILED, CANCELLED); returning true from the callback cancels the running fetch. nil clears it.
func (p *Packs) OnPackEvent(f func(PackEvent) bool) { p.mu.Lock(); p.events = f; p.mu.Unlock() }

// SetFetcher sets the app's transport, which carries out `fetch` decisions and `Ensure`.
func (p *Packs) SetFetcher(f PackFetcher) { p.mu.Lock(); p.fetchr = f; p.mu.Unlock() }

// Cancel asks the running fetch to stop (any goroutine): the pack is then missing, `pack-fetch-cancelled:<id>`.
func (p *Packs) Cancel() { p.cancel.Store(true) }

func (p *Packs) note(s string) { p.mu.Lock(); p.notes = append(p.notes, s); p.mu.Unlock() }

// TakeNotes returns, and clears, what happened to packs since the last call (mismatches, failed fetches), one line each.
func (p *Packs) TakeNotes() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	n := p.notes
	p.notes = nil
	return n
}

func (p *Packs) hasHook() bool    { p.mu.Lock(); defer p.mu.Unlock(); return p.hook != nil }
func (p *Packs) hasFetcher() bool { p.mu.Lock(); defer p.mu.Unlock(); return p.fetchr != nil }

type candidate struct {
	path string
	ours bool
}

func (p *Packs) candidates(info PackInfo, f PackFile) []candidate {
	p.mu.Lock()
	store, extra := p.store, append([]string(nil), p.extra...)
	p.mu.Unlock()
	v := []candidate{{filepath.Join(store, info.ID, f.Name), true}}
	for _, d := range extra {
		v = append(v, candidate{filepath.Join(d, info.ID, f.Name), false}, candidate{filepath.Join(d, f.Name), false})
	}
	if m := envText("EMON_MODELS"); m != "" {
		for _, d := range filepath.SplitList(m) {
			v = append(v, candidate{filepath.Join(d, info.ID, f.Name), false}, candidate{filepath.Join(d, f.Name), false})
		}
	}
	return v
}

func hashFile(path string) ([32]byte, error) {
	var out [32]byte
	f, err := os.Open(path)
	if err != nil {
		return out, err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return out, err
	}
	copy(out[:], h.Sum(nil))
	return out, nil
}

// verify: is this file the catalogue's, its size and its SHA-256? A file hashed once and unchanged since is not hashed again.
func (p *Packs) verify(path string, f PackFile) bool {
	st, err := os.Stat(path)
	if err != nil || !st.Mode().IsRegular() || uint64(st.Size()) != f.Bytes {
		return false
	}
	s := stamp{st.Size(), st.ModTime().UnixNano()}
	p.mu.Lock()
	ok := p.good[path] == s
	p.mu.Unlock()
	if ok {
		return true
	}
	h, err := hashFile(path)
	if err != nil || h != f.SHA {
		return false
	}
	p.mu.Lock()
	p.good[path] = s
	p.mu.Unlock()
	return true
}

// locate returns the verified files of pack p (one per file of the pack, in the catalogue's order), or nil when any is absent or does not match.
func (p *Packs) locate(code int32) []string {
	info := packInfo(code)
	var out []string
	for _, f := range info.Files {
		found := ""
		for _, c := range p.candidates(info, f) {
			if _, err := os.Stat(c.path); err != nil {
				continue
			}
			if p.verify(c.path, f) {
				found = c.path
				break
			}
			// there and not the catalogue's: never used. In the store it is deleted and the pack is missing again; the app's directory is left alone
			p.note("pack-hash-mismatch:" + info.ID)
			if c.ours {
				os.Remove(c.path)
			}
		}
		if found == "" {
			return nil
		}
		out = append(out, found)
	}
	return out
}

func (p *Packs) installed() []int32 {
	var v []int32
	for c := int32(1); c <= packCount(); c++ {
		if p.locate(c) != nil {
			v = append(v, c)
		}
	}
	return v
}

// View is the flat directory an adapter of the engine (kin's engine name) reads: links to the verified files of the packs it reads. Rebuilt each time it is asked for,
// so a pack that arrived since is in it and one that no longer verifies is not.
func (p *Packs) View(engine string) (string, error) {
	dir := filepath.Join(p.Store(), ".views", engine)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	eng := engines.EngFind([]byte(engine), 0, int32(len(engine)))
	if eng < 0 {
		eng = 0
	}
	// the bundled PP adapter uses the English Tesseract pack as its lexicon
	lexicon := int32(-1)
	if engine == "ppocr" {
		if c, ok := packFind("tess-fast-eng"); ok {
			lexicon = c
		}
	}
	want := map[string]string{}
	for c := int32(1); c <= packCount(); c++ {
		pe := engines.PkPackEngine(c)
		if pe != 0 && pe != eng && lexicon != c {
			continue
		}
		if paths := p.locate(c); paths != nil {
			for i, f := range packInfo(c).Files {
				want[f.Name] = paths[i]
			}
		}
	}
	if ents, err := os.ReadDir(dir); err == nil {
		for _, e := range ents {
			if _, ok := want[e.Name()]; !ok {
				os.Remove(filepath.Join(dir, e.Name()))
			}
		}
	}
	for name, src := range want {
		dst := filepath.Join(dir, name)
		a, e1 := os.Stat(dst)
		b, e2 := os.Stat(src)
		if e1 == nil && e2 == nil && a.Size() == b.Size() && a.ModTime().Equal(b.ModTime()) {
			continue
		}
		os.Remove(dst)
		if os.Link(src, dst) != nil {
			if err := copyFile(src, dst); err != nil {
				return "", err
			}
		}
	}
	return dir, nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.Create(dst)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

func writeAtomic(dst string, b []byte) error {
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return err
	}
	tmp := fmt.Sprintf("%s.part%d", dst, os.Getpid())
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	os.Remove(dst)
	if err := os.Rename(tmp, dst); err != nil {
		os.Remove(tmp)
		return err
	}
	return nil
}

// packRow is one row of `pk-resolve`'s answer (kin's `pstate`).
type packRow struct{ state, engine, pack, lang int32 }

func (p *Packs) resolve(engs []int32, langs []int32, osd bool) ([]packRow, error) {
	return p.resolveWith(engs, langs, osd, p.installed())
}

func (p *Packs) resolveWith(engs, langs []int32, osd bool, installed []int32) ([]packRow, error) {
	l, e, i, out, scratch := port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()
	for _, v := range langs {
		port.CellsPush(l, v)
	}
	for _, v := range engs {
		port.CellsPush(e, v)
	}
	for _, v := range installed {
		port.CellsPush(i, v)
	}
	var flags int32
	if osd {
		flags = engines.PkfOsd()
	}
	if r := engines.PkResolve(p.class, flags, l, e, i, out, scratch); r != 0 {
		var msg strings.Builder
		engines.PkExplain(r, out, &msg)
		return nil, errors.New(msg.String())
	}
	var rows []packRow
	for k := int32(0); k < port.CellsLen(out)/4; k++ {
		rows = append(rows, packRow{port.CellsAt(out, 4*k), port.CellsAt(out, 4*k+1), port.CellsAt(out, 4*k+2), port.CellsAt(out, 4*k+3)})
	}
	return rows, nil
}

// emit tells the app (a pack event); true when the app, or Cancel, asks to stop.
func (p *Packs) emit(kind PackEventKind, info PackInfo, done, total uint64, app bool, message string) bool {
	p.mu.Lock()
	ev := p.events
	p.mu.Unlock()
	stop := false
	if ev != nil {
		stop = ev(PackEvent{Kind: kind, Pack: info.ID, Engine: info.EngineName, Done: done, Total: total, ByApp: app, Message: message})
	}
	return stop || p.cancel.Load()
}

func (p *Packs) ask(info PackInfo, reason, requestEngine string) PackDecision {
	p.mu.Lock()
	hook := p.hook
	p.mu.Unlock()
	if hook == nil {
		return PackDecision{}
	}
	return hook(PackRequest{Pack: info.ID, Rev: info.Rev, Engine: info.EngineName, Kind: info.KindName, Licence: info.Licence, Bytes: info.Bytes, Reason: reason, RequestEngine: requestEngine})
}

type needKind int

const (
	needReady needKind = iota
	needUnread
	needSubstitute
)

type need struct {
	kind needKind
	text string // the reason (unread) or the engine named (substitute)
}

// need: what becomes of pack code, which a conversion (or the app) needs. Kin decides what to do (`ad-pack-action`); this does it.
func (p *Packs) need(code int32, reason, requestEngine string) need {
	info := packInfo(code)
	present := b2i(p.locate(code) != nil)
	mode, off, hook := p.modeCode(), b2i(PacksOffline()), b2i(p.hasHook())
	asks := adapters.AdAsksHook(present, mode, off, hook) == 1
	var d PackDecision
	if asks {
		d = p.ask(info, reason, requestEngine)
	}
	action := adapters.AdPackAction(present, mode, off, hook, int32(d.Action))
	switch {
	case action == adapters.PactUse():
		return need{kind: needReady}
	case action == adapters.PactFetch():
		if err := p.doFetch(info, false); err != nil {
			return need{needUnread, err.Error()}
		}
		return need{kind: needReady}
	case action == adapters.PactProvided():
		if err := p.install(info, d); err != nil {
			return need{needUnread, err.Error()}
		}
		return need{kind: needReady}
	case action == adapters.PactSubstitute():
		return need{needSubstitute, d.Engine}
	case asks && d.Action == PackFetch && !p.hasFetcher():
		return need{needUnread, p.fetchUnavailable(info)}
	}
	return need{needUnread, "pack-missing:" + info.ID}
}

func b2i(b bool) int32 {
	if b {
		return 1
	}
	return 0
}

func (p *Packs) fetchUnavailable(info PackInfo) string {
	e := "pack-fetch-unavailable:" + info.ID
	p.note(e)
	return e
}

type staged struct {
	f PackFile
	b []byte
}

// install: the app's own bytes or path for a pack, kept in the store after they are verified (D896: every pack, including app-provided bytes, is verified).
func (p *Packs) install(info PackInfo, d PackDecision) error {
	var st []staged
	for _, f := range info.Files {
		var b []byte
		switch {
		case len(d.Data) > 0:
			if len(info.Files) != 1 {
				return errors.New("pack-provide-failed:" + info.ID)
			}
			b = d.Data
		case d.Path != "":
			file := d.Path
			if fi, err := os.Stat(file); err == nil && fi.IsDir() {
				file = filepath.Join(file, f.Name)
			}
			var err error
			if b, err = os.ReadFile(file); err != nil {
				return errors.New("pack-provide-failed:" + info.ID)
			}
		default:
			return errors.New("pack-provide-failed:" + info.ID)
		}
		if !f.Matches(b) {
			p.note("pack-hash-mismatch:" + info.ID)
			return errors.New("pack-hash-mismatch:" + info.ID)
		}
		st = append(st, staged{f, b})
	}
	if p.keep(info, st) != nil {
		return errors.New("pack-provide-failed:" + info.ID)
	}
	return nil
}

func (p *Packs) keep(info PackInfo, st []staged) error {
	dir := filepath.Join(p.Store(), info.ID)
	for _, s := range st {
		if err := writeAtomic(filepath.Join(dir, s.f.Name), s.b); err != nil {
			return err
		}
	}
	if p.locate(info.Code) == nil {
		return errors.New("installed files could not be verified")
	}
	return nil
}

// Ensure fetches the packs now (the app's `packs.ensure`): progress and cancel through OnPackEvent. Only when the mode lets anything be fetched (auto, not offline).
func (p *Packs) Ensure(ids ...string) error {
	if PacksOffline() {
		return errors.New("EMON_OFFLINE=1: nothing is fetched")
	}
	if p.modeCode() != engines.PfetchAuto() {
		return errors.New("the pack mode does not fetch (it is `never` or `cache`)")
	}
	var codes []int32
	for _, id := range ids {
		c, ok := packFind(id)
		if !ok {
			return fmt.Errorf("usage: %s is not a pack of the catalogue", id)
		}
		codes = append(codes, c)
	}
	for _, c := range codes {
		if p.locate(c) != nil {
			continue
		}
		if err := p.doFetch(packInfo(c), true); err != nil {
			return err
		}
	}
	return nil
}

// doFetch asks the app's fetcher for the files. All of them are verified before any is installed.
func (p *Packs) doFetch(info PackInfo, app bool) error {
	p.fetch.Lock()
	defer p.fetch.Unlock()
	if p.locate(info.Code) != nil {
		return nil
	}
	p.mu.Lock()
	fetcher := p.fetchr
	p.mu.Unlock()
	if fetcher == nil {
		return errors.New(p.fetchUnavailable(info))
	}
	p.cancel.Store(false)
	p.emit(PackEventStart, info, 0, info.Bytes, app, "")
	var base uint64
	var st []staged
	for _, f := range info.Files {
		cancelled := p.cancel.Load()
		var received uint64
		b, err := fetcher(info, f, func(done uint64) bool {
			if done > f.Bytes {
				done = f.Bytes
			}
			if done > received {
				received = done
				if p.emit(PackEventProgress, info, base+done, info.Bytes, app, "") {
					cancelled = true
				}
			}
			if p.cancel.Load() {
				cancelled = true
			}
			return cancelled
		})
		if cancelled || p.cancel.Load() {
			p.emit(PackEventCancelled, info, base, info.Bytes, app, "")
			e := "pack-fetch-cancelled:" + info.ID
			p.note(e)
			return errors.New(e)
		}
		if err != nil {
			e := fmt.Sprintf("pack-fetch-failed:%s: %v", info.ID, err)
			p.note(e)
			p.emit(PackEventFailed, info, base, info.Bytes, app, e)
			return errors.New(e)
		}
		if !f.Matches(b) {
			e := "pack-hash-mismatch:" + info.ID
			p.note(e)
			p.emit(PackEventFailed, info, base, info.Bytes, app, e)
			return errors.New(e)
		}
		st = append(st, staged{f, b})
		base += f.Bytes
	}
	if err := p.keep(info, st); err != nil {
		e := fmt.Sprintf("pack-fetch-failed:%s: %v", info.ID, err)
		p.note(e)
		p.emit(PackEventFailed, info, base, info.Bytes, app, e)
		return errors.New(e)
	}
	p.emit(PackEventDone, info, info.Bytes, info.Bytes, app, "")
	return nil
}
