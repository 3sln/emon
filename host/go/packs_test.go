// The packs' laws, Go host (DEVLOG D925 "Not done" (1), D896, D913): the hook's four decisions under each mode, never and offline winning over the hook, every pack verified
// before an adapter reads, the progress stream and cancel, the app's fetcher (no HTTP in the library), `State` (engines()) and `Prepare`. In-process adapters; the real
// English model (`.cache/native/dl/eng.traineddata`) where this machine has it.
package emon

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"emon/port"
)

// engData is the real tess-fast-eng model, or nil where this machine has none.
func engData(t *testing.T) []byte {
	dir, _ := os.Getwd()
	for i := 0; i < 8; i++ {
		if b, err := os.ReadFile(filepath.Join(dir, ".cache", "native", "dl", "eng.traineddata")); err == nil {
			return b
		}
		if b, err := os.ReadFile(filepath.Join(dir, "..", "native", "dl", "eng.traineddata")); err == nil {
			return b
		}
		dir = filepath.Dir(dir)
	}
	return nil
}

// packEngines is a registry with one in-process Tesseract adapter that reads with the recognizer pack, a fresh store and a clean environment.
func packEngines(t *testing.T, mode string) (*Engines, *stubAdapter, string) {
	t.Setenv("EMON_OFFLINE", "")
	t.Setenv("EMON_MODELS", "")
	store := t.TempDir()
	e := NewEngines()
	if err := e.Packs().SetStore(store); err != nil {
		t.Fatal(err)
	}
	if err := e.Packs().SetMode(mode); err != nil {
		t.Fatal(err)
	}
	a := &stubAdapter{info: AdapterInfo{Name: "tesseract", Engine: 1, Needs: 1, Flags: AdapterThreadsafe, Producer: [][2]string{{"engine", "tesseract"}}}, reading: Reading{Status: AdapterFailed, Reason: "stub"}}
	if err := e.Add(a); err != nil {
		t.Fatal(err)
	}
	return e, a, store
}

func convert(e *Engines) (port.PlanAnswer, error) {
	req, bm := ocrRequest(5)
	return e.Fulfil(req, bm, false)
}

func notesText(e *Engines) string { return strings.Join(e.Notes(), "\n") }

func TestPacksNeverAndOfflineWinOverTheHook(t *testing.T) {
	e, a, _ := packEngines(t, "never")
	asked := 0
	e.Packs().OnPackNeeded(func(PackRequest) PackDecision { asked++; return PackDecision{Action: PackFetch} })
	if ans, err := convert(e); err != nil || len(ans.Rec) != 4 {
		t.Fatalf("%v %v", ans.Rec, err)
	}
	if asked != 0 || a.reads != 0 || !strings.Contains(notesText(e), "pack-missing:tess-fast-eng") {
		t.Fatalf("never: asked %d reads %d", asked, a.reads)
	}
	e, a, _ = packEngines(t, "auto")
	t.Setenv("EMON_OFFLINE", "1")
	e.Packs().OnPackNeeded(func(PackRequest) PackDecision { asked++; return PackDecision{Action: PackFetch} })
	convert(e)
	if asked != 0 || a.reads != 0 || !strings.Contains(notesText(e), "pack-missing:tess-fast-eng") {
		t.Fatalf("offline: asked %d reads %d", asked, a.reads)
	}
}

func TestPacksDeclineLeavesThePageUnread(t *testing.T) {
	e, a, _ := packEngines(t, "cache")
	var got PackRequest
	e.Packs().OnPackNeeded(func(r PackRequest) PackDecision { got = r; return PackDecision{Action: PackDecline} })
	ans, _ := convert(e)
	if len(ans.Rec) != 4 || a.reads != 0 || !strings.Contains(notesText(e), "pack-missing:tess-fast-eng") {
		t.Fatalf("decline: %v reads %d", ans.Rec, a.reads)
	}
	if got.Pack != "tess-fast-eng" || got.Engine != "tesseract" || got.Kind != "recognizer" || got.Licence != "Apache-2.0" || got.Bytes != 4113088 || got.Reason != "conversion" || got.RequestEngine != "tesseract" {
		t.Fatalf("the request the hook saw: %+v", got)
	}
}

func TestPacksFetchWithoutAFetcherIsUnavailable(t *testing.T) {
	e, a, _ := packEngines(t, "auto")
	e.Packs().OnPackNeeded(func(PackRequest) PackDecision { return PackDecision{Action: PackFetch} })
	convert(e)
	if a.reads != 0 || !strings.Contains(notesText(e), "pack-fetch-unavailable:tess-fast-eng") {
		t.Fatalf("no transport in the library: reads %d", a.reads)
	}
}

func TestPacksProvideIsVerified(t *testing.T) {
	good := engData(t)
	if good == nil {
		t.Skip("SKIP: .cache/native/dl/eng.traineddata is not on this machine")
	}
	e, a, store := packEngines(t, "cache")
	bad := append([]byte(nil), good...)
	bad[100] ^= 1
	data := bad
	e.Packs().OnPackNeeded(func(PackRequest) PackDecision { return PackDecision{Action: PackProvide, Data: data} })
	convert(e)
	if a.reads != 0 || !strings.Contains(notesText(e), "pack-hash-mismatch:tess-fast-eng") {
		t.Fatalf("tampered bytes: reads %d", a.reads)
	}
	if _, err := os.Stat(filepath.Join(store, "tess-fast-eng", "eng.traineddata")); err == nil {
		t.Fatal("tampered bytes were kept")
	}
	data = good
	convert(e)
	if a.reads != 1 {
		t.Fatalf("the verified pack: reads %d (%s)", a.reads, notesText(e))
	}
	if _, err := os.Stat(filepath.Join(store, "tess-fast-eng", "eng.traineddata")); err != nil {
		t.Fatal("verified bytes were not kept")
	}
	if v, err := e.Packs().View("tesseract"); err != nil {
		t.Fatal(err)
	} else if _, err := os.Stat(filepath.Join(v, "eng.traineddata")); err != nil {
		t.Fatal("the adapter's view lacks the pack")
	}
	// present now: the hook is not asked again
	e.Packs().OnPackNeeded(func(PackRequest) PackDecision { t.Fatal("asked for a pack that is here"); return PackDecision{} })
	convert(e)
	// provided as a path
	e2, a2, _ := packEngines(t, "cache")
	src := filepath.Join(t.TempDir(), "eng.traineddata")
	os.WriteFile(src, good, 0o644)
	e2.Packs().OnPackNeeded(func(PackRequest) PackDecision { return PackDecision{Action: PackProvide, Path: src} })
	convert(e2)
	if a2.reads != 1 {
		t.Fatalf("a path: reads %d", a2.reads)
	}
}

func TestPacksFetcherEventsAndCancel(t *testing.T) {
	good := engData(t)
	if good == nil {
		t.Skip("SKIP: .cache/native/dl/eng.traineddata is not on this machine")
	}
	e, a, _ := packEngines(t, "auto")
	var kinds []PackEventKind
	var last PackEvent
	e.Packs().OnPackEvent(func(ev PackEvent) bool { kinds = append(kinds, ev.Kind); last = ev; return false })
	e.Packs().SetFetcher(func(p PackInfo, f PackFile, progress func(uint64) bool) ([]byte, error) {
		for off := uint64(0); off < f.Bytes; off += f.Bytes / 4 {
			if progress(off) {
				return nil, nil
			}
		}
		progress(f.Bytes)
		return good, nil
	})
	e.Packs().OnPackNeeded(func(PackRequest) PackDecision { return PackDecision{Action: PackFetch} })
	convert(e)
	if a.reads != 1 || len(kinds) < 3 || kinds[0] != PackEventStart || kinds[len(kinds)-1] != PackEventDone || last.Done != 4113088 || last.Total != 4113088 || last.ByApp {
		t.Fatalf("fetch: reads %d events %v last %+v", a.reads, kinds, last)
	}
	// Ensure, cancelled from the progress stream
	e, _, store := packEngines(t, "auto")
	kinds = nil
	e.Packs().OnPackEvent(func(ev PackEvent) bool { kinds = append(kinds, ev.Kind); return ev.Kind == PackEventProgress })
	e.Packs().SetFetcher(func(p PackInfo, f PackFile, progress func(uint64) bool) ([]byte, error) {
		for off := uint64(1); off < f.Bytes; off += f.Bytes / 4 {
			if progress(off) {
				return nil, nil
			}
		}
		return good, nil
	})
	err := e.Packs().Ensure("tess-fast-eng")
	if err == nil || !strings.HasPrefix(err.Error(), "pack-fetch-cancelled:tess-fast-eng") || kinds[len(kinds)-1] != PackEventCancelled {
		t.Fatalf("cancel: %v %v", err, kinds)
	}
	if _, err := os.Stat(filepath.Join(store, "tess-fast-eng")); err == nil {
		t.Fatal("a cancelled pack was kept")
	}
	// a fetcher that returns other bytes: refused, FAILED
	kinds = nil
	e.Packs().OnPackEvent(func(ev PackEvent) bool { kinds = append(kinds, ev.Kind); return false })
	e.Packs().SetFetcher(func(PackInfo, PackFile, func(uint64) bool) ([]byte, error) { return []byte("not a model"), nil })
	if err := e.Packs().Ensure("tess-fast-eng"); err == nil || !strings.HasPrefix(err.Error(), "pack-hash-mismatch") || kinds[len(kinds)-1] != PackEventFailed {
		t.Fatalf("a bad download: %v %v", err, kinds)
	}
	// Ensure by the app is flagged ByApp, and needs a mode that fetches
	e.Packs().SetMode("cache")
	if err := e.Packs().Ensure("tess-fast-eng"); err == nil {
		t.Fatal("Ensure fetched in mode cache")
	}
}

func TestPacksSubstituteRules(t *testing.T) {
	e, a, _ := packEngines(t, "cache")
	e.Packs().OnPackNeeded(func(PackRequest) PackDecision { return PackDecision{Action: PackSubstitute, Engine: "ppocr"} })
	convert(e)
	if a.reads != 0 || !strings.Contains(notesText(e), "pack-substitute-differs:ppocr") {
		t.Fatalf("another OIL engine: reads %d", a.reads)
	}
	// the same OIL engine, another adapter of it
	second := &stubAdapter{info: a.info, reading: Reading{Status: AdapterFailed, Reason: "second"}}
	if err := e.Add(second); err != nil {
		t.Fatal(err)
	}
	// the substitute is asked about the pack in its turn (the pack is still missing): here it provides it
	good := engData(t)
	if good == nil {
		return
	}
	calls := 0
	e.Packs().OnPackNeeded(func(PackRequest) PackDecision {
		calls++
		if calls == 1 {
			return PackDecision{Action: PackSubstitute, Engine: "tesseract"}
		}
		return PackDecision{Action: PackProvide, Data: good}
	})
	convert(e)
	if calls != 2 || a.reads != 0 || second.reads != 1 {
		t.Fatalf("the substitute: calls %d first %d second %d", calls, a.reads, second.reads)
	}
}

func TestPacksStateAndPrepare(t *testing.T) {
	e, _, _ := packEngines(t, "never")
	var st struct {
		Platform string `json:"platform"`
		Mode     string `json:"mode"`
		Engines  []struct {
			Name       string `json:"name"`
			State      string `json:"state"`
			Registered bool   `json:"registered"`
			Reason     string `json:"reason"`
			Needs      []struct {
				ID        string `json:"id"`
				Installed bool   `json:"installed"`
			} `json:"needs"`
		} `json:"engines"`
	}
	if err := json.Unmarshal([]byte(e.State()), &st); err != nil {
		t.Fatalf("State is not JSON: %v", err)
	}
	if st.Mode != "never" || st.Platform == "" {
		t.Fatalf("%+v", st)
	}
	by := map[string]int{}
	for i, en := range st.Engines {
		by[en.Name] = i
	}
	tess := st.Engines[by["tesseract"]]
	if !tess.Registered || tess.State != "unavailable" || !strings.Contains(tess.Reason, "pack") || len(tess.Needs) == 0 || tess.Needs[0].Installed {
		t.Fatalf("a registered engine whose pack is missing under never: %+v", tess)
	}
	if pp, ok := by["ppocr"]; ok && (st.Engines[pp].Registered || st.Engines[pp].State != "unavailable") {
		t.Fatalf("ppocr is not registered: %+v", st.Engines[pp])
	}
	e.Packs().SetMode("auto")
	if err := json.Unmarshal([]byte(e.State()), &st); err != nil || st.Engines[by["tesseract"]].State != "downloadable" {
		t.Fatalf("auto: %v %+v", err, st.Engines[by["tesseract"]])
	}
	var pr struct {
		Verdict string `json:"verdict"`
		Osd     bool   `json:"osd"`
		Missing int    `json:"missing"`
		Packs   []struct {
			ID    string `json:"id"`
			State string `json:"state"`
		} `json:"packs"`
	}
	out, err := e.Prepare(Options{})
	if err != nil || json.Unmarshal([]byte(out), &pr) != nil {
		t.Fatalf("%v %s", err, out)
	}
	if pr.Verdict != "downloadable" || !pr.Osd || pr.Missing == 0 || len(pr.Packs) == 0 {
		t.Fatalf("prepare: %s", out)
	}
	e.Packs().SetMode("never")
	if out, _ = e.Prepare(Options{}); !strings.Contains(out, `"verdict":"blocked"`) {
		t.Fatalf("never: %s", out)
	}
	if out, _ = e.Prepare(Options{Ocr: OcrNever}); !strings.Contains(out, `"verdict":"ready"`) || !strings.Contains(out, `"packs":[]`) {
		t.Fatalf("ocr never: %s", out)
	}
}
