package emon

// The registry's packs, Go host (K3, D925 "Not done" (1); the twin of `Router::ensure_packs`, `describe_json` and `prepare_json` of `host/rust/src/adapter.rs`, D896, D899):
// the pack machinery in `packs.go` met at the registry. Before an adapter that reads with packs is asked, what the request needs is resolved by kin (`pk-resolve`) and made
// present, fetched, provided, declined or substituted by the app's hook (`ad-pack-action`); `State` is D896's `engines()` and `Prepare` its `prepare(options)`, as JSON in
// the shape of `emon_engines_describe` and `emon_prepare`.

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"emon/adapters"
	"emon/engines"
	"emon/ocr"
	"emon/ocrport"
	"emon/plan"
	"emon/port"
	"emon/scan"
)

// Packs is the registry's pack machinery: the store, the hook, the progress stream, the app's fetcher.
func (e *Engines) Packs() *Packs { return e.packs }

// SetRenderer tells the registry that a renderer (pdfium) is wired, which `State` reports for the render engine.
func (e *Engines) SetRenderer(present bool) { e.mu.Lock(); e.renderer = present; e.mu.Unlock() }

// turnkeyPacks is what `Defaults` does for packs (`emon_engines_defaults`): the archive's `share/emon/packs` beside the program is searched, and the mode is
// `$EMON_PACKS_FETCH` (never, cache, auto), else the platform's default when the app set a fetcher, else `cache`; unless the app already chose a mode.
func (e *Engines) turnkeyPacks() error {
	if exe, err := os.Executable(); err == nil {
		b := filepath.Dir(exe)
		for _, c := range []string{filepath.Join(filepath.Dir(b), "share", "emon", "packs"), filepath.Join(b, "share", "emon", "packs")} {
			if st, err := os.Stat(c); err == nil && st.IsDir() {
				e.packs.AddDir(c)
			}
		}
	}
	e.mu.Lock()
	set := e.modeSet
	e.mu.Unlock()
	if set {
		return nil
	}
	mode := ""
	switch v := os.Getenv("EMON_PACKS_FETCH"); v {
	case "never", "cache", "auto":
		mode = v
	case "":
		mode = "cache"
		if e.packs.hasFetcher() {
			var s strings.Builder
			engines.PfetchEmitName(&s, engines.PkDefaultFetch(e.packs.class))
			mode = s.String()
		}
	default:
		return fmt.Errorf("usage: EMON_PACKS_FETCH is never, cache or auto, not %q", v)
	}
	return e.packs.SetMode(mode)
}

func cellsOf(v []int32) *port.Cells { return cellsFrom(v) }

func packedText(p int32) string {
	var b []byte
	for _, c := range []int32{(p >> 24) & 255, (p >> 16) & 255, (p >> 8) & 255, p & 255} {
		if c > 0 {
			b = append(b, byte(c))
		}
	}
	return string(b)
}

func (e *Engines) languagesOf(en *entry, req *port.PlanRequest) []int32 {
	// Tesseract's requests name languages; PP-OCR has one recognizer (English)
	if en.info.Engine != 1 {
		return nil
	}
	c := cellsOf(req.Rec)
	var v []int32
	for i := int32(0); i < ocrport.OpLangCount(c); i++ {
		l := ocrport.OpLangAt(c, i)
		// a script (ISO 15924) is not a language: no pack is named for it
		if l > 0 && ocrport.OpIsScript(l) == 0 {
			v = append(v, l)
		}
	}
	return v
}

// ensurePacks makes the packs a request needs present, or says why the page is unread (the second result). A non-nil first result is the app's substitute: another
// adapter of the same OIL engine.
func (e *Engines) ensurePacks(en *entry, req *port.PlanRequest) (*entry, string) {
	if en.info.Needs == 0 || en.ownPacks {
		return nil, ""
	}
	eng := adapters.AdOenEng(int32(en.info.Engine))
	if eng < 0 {
		return nil, ""
	}
	pk := e.packs
	flags := req.Field(plan.OcFlags())
	osd := flags&ocr.OcfOsd() != 0 && en.info.Needs&2 != 0
	langs := e.languagesOf(en, req)
	rows, err := pk.resolve([]int32{eng}, langs, osd)
	if err != nil {
		return nil, "engine-unavailable: " + err.Error()
	}
	// a language no pack of the catalogue reads is not read; when that is every language the request asked for, the page is unread
	var unreadable []packRow
	for _, r := range rows {
		if r.state == engines.PstateUnsupported() {
			unreadable = append(unreadable, r)
		}
	}
	if len(langs) > 0 && len(unreadable) >= len(langs) {
		return nil, "lang-unavailable:" + packedText(unreadable[0].lang)
	}
	name := engName(eng)
	arrived := false
	for _, r := range rows {
		if r.state != engines.PstateMissing() {
			continue
		}
		kind := engines.PkPackKind(r.pack)
		n := pk.need(r.pack, "conversion", name)
		switch n.kind {
		case needReady:
			arrived = true
		case needUnread:
			// orientation detection degrades to kin's own policy without its model; it never leaves the page unread
			if kind != engines.PkindOsd() {
				return nil, n.text
			}
		case needSubstitute:
			e2 := engines.EngFind([]byte(n.text), 0, int32(len(n.text)))
			if e2 < 0 || uint32(engines.PkEngineOen(e2)) != en.info.Engine {
				return nil, "pack-substitute-differs:" + n.text
			}
			e.mu.Lock()
			var other *entry
			for _, c := range e.entries {
				if c != en && c.info.Engine == en.info.Engine {
					other = c
					break
				}
			}
			e.mu.Unlock()
			if other == nil {
				return nil, "pack-substitute-unavailable:" + n.text
			}
			return other, ""
		}
	}
	// the bundled PP adapter reads English dictionary entries from the Tesseract pack even when used alone
	if eng == engines.EngPpocr() {
		for _, kv := range en.info.Producer {
			if kv[0] == "pp-lexicon-pack" && kv[1] == "tess-fast-eng" {
				c, ok := packFind("tess-fast-eng")
				if !ok {
					return nil, "pack-missing:tess-fast-eng"
				}
				n := pk.need(c, "conversion", name)
				switch n.kind {
				case needReady:
					arrived = true
				case needUnread:
					return nil, n.text
				case needSubstitute:
					return nil, "pack-substitute-differs:" + n.text
				}
			}
		}
	}
	if arrived || func() bool {
		for _, r := range rows {
			if r.state == engines.PstateInstalled() {
				return true
			}
		}
		return false
	}() {
		// the adapter lists its pack directory for each request, so what it is given must hold what arrived
		if _, err := pk.View(name); err != nil {
			return nil, "pack-dir-missing: " + err.Error()
		}
	}
	// orientation detection without its model degrades to kin's own policy: the engine is not asked for it (a Tesseract asked to load a model that is not there gives up)
	if en.info.Needs&2 != 0 && flags&ocr.OcfOsd() != 0 {
		if rs, err := pk.resolve([]int32{eng}, nil, true); err == nil {
			for _, r := range rs {
				if r.state == engines.PstateMissing() && engines.PkPackKind(r.pack) == engines.PkindOsd() {
					rec := append([]int32(nil), req.Rec...)
					rec[3+int(plan.OcFlags())] &^= ocr.OcfOsd()
					req.Rec = rec
					break
				}
			}
		}
	}
	return nil, ""
}

func jsonText(s string) string {
	var o strings.Builder
	o.WriteByte('"')
	for _, c := range s {
		switch {
		case c == '"':
			o.WriteString("\\\"")
		case c == '\\':
			o.WriteString("\\\\")
		case c == '\n':
			o.WriteString("\\n")
		case c == '\r':
			o.WriteString("\\r")
		case c == '\t':
			o.WriteString("\\t")
		case c < 0x20:
			fmt.Fprintf(&o, "\\u%04x", c)
		default:
			o.WriteRune(c)
		}
	}
	o.WriteByte('"')
	return o.String()
}

func nameOf(emit func(*strings.Builder, int32) int32, code int32) string {
	var s strings.Builder
	emit(&s, code)
	return s.String()
}

func (e *Engines) registered(eng int32) bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	if engines.PkEngineKind(eng) == engines.EkindRender() {
		return e.renderer
	}
	oen := engines.PkEngineOen(eng)
	if engines.PkEngineOn(eng, e.packs.class) != 1 {
		return false
	}
	for _, en := range e.entries {
		if int32(en.info.Engine) == oen {
			return true
		}
	}
	return false
}

// State is D896's `engines()`: every engine of kin's table that runs on this platform, with its state (built in, installed, downloadable, unavailable), as JSON in the
// shape of `emon_engines_describe`.
func (e *Engines) State() string {
	pk := e.packs
	class := pk.class
	installed := pk.installed()
	mode, off := pk.modeCode(), b2i(PacksOffline())
	var list []string
	for i := int32(0); i < engines.PkEngineCount(); i++ {
		eng := engines.PkEngineAt(i)
		if engines.PkEngineOn(eng, class) == 0 {
			continue
		}
		name := engName(eng)
		reg := e.registered(eng)
		var rows []packRow
		if engines.PkEngineKind(eng) == engines.EkindOcr() && engines.PkEngineNeeds(eng) != 0 {
			rows, _ = pk.resolveWith([]int32{eng}, nil, false, installed)
		}
		var missing []packRow
		for _, r := range rows {
			if r.state == engines.PstateMissing() {
				missing = append(missing, r)
			}
		}
		state := adapters.AdEngineState(b2i(reg), engines.PkEngineImpl(eng), engines.PkEngineNeeds(eng), int32(len(missing)), mode, off)
		reason := ""
		if !reg {
			reason = "no adapter is registered for it"
		} else if state == adapters.EstUnavailable() {
			if off == 1 {
				reason = "EMON_OFFLINE=1"
			} else {
				reason = "a pack is missing and the pack mode does not fetch"
			}
		}
		var needs []string
		for _, r := range rows {
			if r.pack > 0 {
				info := packInfo(r.pack)
				needs = append(needs, fmt.Sprintf(`{"id":%s,"kind":%s,"bytes":%d,"licence":%s,"installed":%t}`, jsonText(info.ID), jsonText(info.KindName), info.Bytes, jsonText(info.Licence), r.state == engines.PstateInstalled()))
			}
		}
		var dl uint64
		for _, r := range missing {
			dl += packInfo(r.pack).Bytes
		}
		list = append(list, fmt.Sprintf(`{"name":%s,"kind":%s,"impl":%s,"state":%s,"registered":%t,"calibrated":%t,"caps":%d,"oen":%d,"role":%s,"needs":[%s],"download_bytes":%d,"reason":%s}`,
			jsonText(name), jsonText(nameOf(engines.EkindEmitName, engines.PkEngineKind(eng))), jsonText(nameOf(engines.EimplEmitName, engines.PkEngineImpl(eng))), jsonText(nameOf(adapters.EstEmitName, state)), reg,
			engines.PkEngineCalibrated(eng) == 1, engines.PkEngineCaps(eng), engines.PkEngineOen(eng), jsonText(nameOf(engines.EroleEmitName, engines.PkRoleDefault(eng, class))), strings.Join(needs, ","), dl, jsonText(reason)))
	}
	return fmt.Sprintf(`{"platform":%s,"mode":%s,"offline":%t,"store":%s,"engines":[%s]}`, jsonText(nameOf(engines.PclsEmitName, class)), jsonText(nameOf(engines.PfetchEmitName, mode)), off == 1, jsonText(pk.Store()), strings.Join(list, ","))
}

// Prepare is D896's `prepare(options)`: the packs a conversion with these options needs and which are missing, as kin resolves them (`pk-resolve`) and summarises them
// (`ad-verdict`), as JSON in the shape of `emon_prepare`.
func (e *Engines) Prepare(opts Options) (string, error) {
	pk := e.packs
	mode, off := pk.modeCode(), b2i(PacksOffline())
	never := opts.Ocr == OcrNever
	engs := []int32{engines.PkOptionEngine(opts.OcrEngine)}
	if opts.OcrEngine == 1 {
		engs = append(engs, engines.EngPpocr())
	}
	osd := !never && engines.PkUsesTesseract(engs[0]) == 1
	var langs []int32
	if opts.Locale != "" {
		c := port.NewCells()
		for _, ch := range opts.Locale {
			port.CellsPush(c, int32(ch))
		}
		if lg := scan.ScLang(c); lg > 0 {
			langs = append(langs, lg*256)
		}
	}
	var rows []packRow
	verdict := adapters.PverReady()
	if !never {
		l, en, ins, out, scratch := port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells(), port.NewCells()
		for _, v := range langs {
			port.CellsPush(l, v)
		}
		for _, v := range engs {
			port.CellsPush(en, v)
		}
		for _, v := range pk.installed() {
			port.CellsPush(ins, v)
		}
		var flags int32
		if osd {
			flags = engines.PkfOsd()
		}
		if r := engines.PkResolve(pk.class, flags, l, en, ins, out, scratch); r != 0 {
			var msg strings.Builder
			engines.PkExplain(r, out, &msg)
			return "", fmt.Errorf("%s", msg.String())
		}
		for k := int32(0); k < port.CellsLen(out)/4; k++ {
			rows = append(rows, packRow{port.CellsAt(out, 4*k), port.CellsAt(out, 4*k+1), port.CellsAt(out, 4*k+2), port.CellsAt(out, 4*k+3)})
		}
		verdict = adapters.AdVerdict(out, mode, off)
	}
	langName := func(l int32) string {
		var b []byte
		for _, c := range []int32{(l >> 24) & 255, (l >> 16) & 255, (l >> 8) & 255} {
			if c > 0 {
				b = append(b, byte(c))
			}
		}
		return string(b)
	}
	var packsJSON, unsupported []string
	var missing, conditional int
	var missingBytes, conditionalBytes uint64
	for _, r := range rows {
		if r.state == engines.PstateUnsupported() {
			unsupported = append(unsupported, fmt.Sprintf(`{"engine":%s,"language":%s}`, jsonText(engName(r.engine)), jsonText(langName(r.lang))))
			continue
		}
		info := packInfo(r.pack)
		if r.state == engines.PstateMissing() {
			missing++
			missingBytes += info.Bytes
		}
		if r.state == engines.PstateConditional() {
			conditional++
			conditionalBytes += info.Bytes
		}
		packsJSON = append(packsJSON, fmt.Sprintf(`{"id":%s,"state":%s,"engine":%s,"kind":%s,"bytes":%d,"licence":%s,"rev":%d}`, jsonText(info.ID), jsonText(nameOf(engines.PstateEmitName, r.state)), jsonText(info.EngineName), jsonText(info.KindName), info.Bytes, jsonText(info.Licence), info.Rev))
	}
	var engineNames, langNames []string
	if !never {
		for _, en := range engs {
			engineNames = append(engineNames, jsonText(engName(en)))
		}
		if len(langs) == 0 {
			langNames = []string{jsonText("eng")}
		}
		for _, l := range langs {
			langNames = append(langNames, jsonText(langName(l)))
		}
	}
	return fmt.Sprintf(`{"verdict":%s,"mode":%s,"offline":%t,"engines":[%s],"languages":[%s],"osd":%t,"packs":[%s],"unsupported":[%s],"missing":%d,"missing_bytes":%d,"conditional":%d,"conditional_bytes":%d}`,
		jsonText(nameOf(adapters.PverEmitName, verdict)), jsonText(nameOf(engines.PfetchEmitName, mode)), off == 1, strings.Join(engineNames, ","), strings.Join(langNames, ","), osd, strings.Join(packsJSON, ","), strings.Join(unsupported, ","), missing, missingBytes, conditional, conditionalBytes), nil
}
