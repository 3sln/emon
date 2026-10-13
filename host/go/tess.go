//go:build tesseract

// The Go host's binding to Tesseract's C API through cgo (DEVLOG P6.5, D211-D214): the twin of `host/rust/src/tess.rs`, and
// read against it. LIVE ONLY: it is built under the `tesseract` build tag (`EMON_GO_LIVE=1 kin/scripts/emon-go-build`) and
// links `libtesseract` and `libleptonica`; nothing in `bb gates` compiles or runs it (a gate replays a transcript; the engine is
// the host's opinion, D008). `NewTessOcr` is the `Ocr` port an embedder puts in `Config.Ocr`.
//
// The prototypes are declared here and not included: the C API is small, `capi.h` pulls in the C++ headers' configuration, and a
// declaration is all cgo needs to link. The identity that matters is the TRANSCRIPT's (a recording made by either host replays on
// both to the same bytes); two hosts that link the same Tesseract build also answer a request the same, and two that do not may
// differ in the last pixel of a box, which is Tesseract's and not ours.
//
// WHAT THE C API DOES NOT GIVE (D213): `PageIterator::RowAttributes` is not exported, so a line's row attributes are answered as
// -1 and kin measures them from the symbols' boxes.
package emon

/*
#cgo LDFLAGS: -ltesseract -lleptonica
#include <stdlib.h>
#include <string.h>
typedef void* H;
extern const char* TessVersion(void);
extern H TessBaseAPICreate(void);
extern void TessBaseAPIDelete(H);
extern int TessBaseAPIInit3(H, const char*, const char*);
extern int TessBaseAPISetVariable(H, const char*, const char*);
extern void TessBaseAPISetPageSegMode(H, int);
extern void TessBaseAPISetImage(H, const unsigned char*, int, int, int, int);
extern void TessBaseAPISetSourceResolution(H, int);
extern void TessBaseAPISetRectangle(H, int, int, int, int);
extern int TessBaseAPIRecognize(H, void*);
extern void TessBaseAPIClear(H);
extern H TessBaseAPIGetIterator(H);
extern H TessBaseAPIGetThresholdedImage(H);
extern int TessBaseAPIDetectOrientationScript(H, int*, float*, const char**, float*);
extern void TessDeleteText(const char*);
extern void TessResultIteratorDelete(H);
extern H TessResultIteratorCopy(H);
extern H TessResultIteratorGetPageIterator(H);
extern int TessResultIteratorNext(H, int);
extern char* TessResultIteratorGetUTF8Text(H, int);
extern float TessResultIteratorConfidence(H, int);
extern const char* TessResultIteratorWordRecognitionLanguage(H);
extern int TessResultIteratorWordIsFromDictionary(H);
extern int TessResultIteratorWordIsNumeric(H);
extern const char* TessResultIteratorWordFontAttributes(H, int*, int*, int*, int*, int*, int*, int*, int*);
extern H TessResultIteratorGetChoiceIterator(H);
extern int TessChoiceIteratorNext(H);
extern const char* TessChoiceIteratorGetUTF8Text(H);
extern float TessChoiceIteratorConfidence(H);
extern void TessChoiceIteratorDelete(H);
extern int TessPageIteratorIsAtBeginningOf(H, int);
extern int TessPageIteratorIsAtFinalElement(H, int, int);
extern int TessPageIteratorBoundingBox(H, int, int*, int*, int*, int*);
extern int TessPageIteratorBaseline(H, int, int*, int*, int*, int*);
extern int TessPageIteratorBlockType(H);
extern void TessPageIteratorOrientation(H, int*, int*, int*, float*);
extern int pixGetWidth(H);
extern int pixGetHeight(H);
extern int pixGetDepth(H);
extern int pixGetPixel(H, int, int, unsigned int*);
extern void pixDestroy(H*);
extern char* getLeptonicaVersion(void);
extern void lept_free(void*);
*/
import "C"

import (
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"unsafe"

	"emon/ocr"
	"emon/ocrport"
	"emon/plan"
	"emon/port"
)

const (
	rilBlock    = 0
	rilPara     = 1
	rilTextline = 2
	rilWord     = 3
	rilSymbol   = 4
)

func tessVersion() string { return C.GoString(C.TessVersion()) }

func leptonicaVersion() string {
	p := C.getLeptonicaVersion()
	if p == nil {
		return ""
	}
	s := C.GoString(p)
	C.lept_free(unsafe.Pointer(p))
	return s
}

func takeText(p *C.char) string {
	if p == nil {
		return ""
	}
	s := C.GoString(p)
	C.TessDeleteText(p)
	return s
}

// WHAT A REQUEST ASKS FOR AND HOW THE ENGINE'S ANSWER IS NAMED is kin's (`emon.ocrport`, P10.7, D396): which installed models read
// the request's languages and scripts, a model's name as a request spells it, the OSD's script as ISO 15924, a block's type. This
// file passes it cells and takes cells.
func x10(f float32) int32 { return int32(math.Round(float64(f) * 10)) }

func cellsOf(v []int32) *port.Cells {
	c := port.NewCells()
	for _, x := range v {
		port.CellsPush(c, x)
	}
	return c
}

func cps(s string) []int32 {
	var out []int32
	for _, r := range s {
		out = append(out, int32(r))
	}
	return out
}

type tessAlt struct {
	cps  []int32
	conf int32
}
type tessSym struct {
	bx   [4]int32
	conf int32
	cps  []int32
	alts []tessAlt
}
type tessWord struct {
	conf, line, block int32
	bx                [4]int32
	text              []int32
	flags, lang       int32
	psize, dflags     int32
	syms              []tessSym
}
type tessLine struct {
	bx, base           [4]int32
	block, par, btype int32
}
type tessBlock struct {
	bx                      [4]int32
	orient, wdir, tlo, desk int32
}

// tess is the engine: initialised instances per language string, one goroutine at a time (TessOcr gives each reading an engine of its own).
type tess struct {
	tessdata string
	apis     map[string]C.H
	dpi      int32
	notes    []string
}

func (t *tess) installed() []string {
	ents, err := os.ReadDir(t.tessdata)
	if err != nil {
		return nil
	}
	var v []string
	for _, e := range ents {
		n := e.Name()
		if strings.HasSuffix(n, ".traineddata") {
			l := strings.TrimSuffix(n, ".traineddata")
			if l != "osd" && l != "snum" {
				v = append(v, l)
			}
		}
	}
	sort.Strings(v)
	return v
}

func (t *tess) api(langs string) (C.H, bool) {
	if a, ok := t.apis[langs]; ok {
		return a, true
	}
	h := C.TessBaseAPICreate()
	dp := C.CString(t.tessdata)
	lg := C.CString(langs)
	defer C.free(unsafe.Pointer(dp))
	defer C.free(unsafe.Pointer(lg))
	if C.TessBaseAPIInit3(h, dp, lg) != 0 {
		C.TessBaseAPIDelete(h)
		return nil, false
	}
	// lstm_choice_mode 2: the final beam-search candidates per symbol (D095); word confidences are carried verbatim and 0 is
	// never "wrong" (D130).
	n := C.CString("lstm_choice_mode")
	v := C.CString("2")
	C.TessBaseAPISetVariable(h, n, v)
	C.free(unsafe.Pointer(n))
	C.free(unsafe.Pointer(v))
	t.apis[langs] = h
	return h, true
}

func (t *tess) producer() [][2]string {
	var models []string
	names := append(t.installed(), "osd")
	for _, n := range names {
		b, err := os.ReadFile(filepath.Join(t.tessdata, n+".traineddata"))
		if err != nil {
			continue
		}
		h := uint64(0xcbf29ce484222325)
		for _, x := range b {
			h ^= uint64(x)
			h *= 0x100000001b3
		}
		models = append(models, fmt.Sprintf("%s:%d:fnv64=%016x", n, len(b), h))
	}
	return [][2]string{
		{"engine", "tesseract"},
		{"version", tessVersion()},
		{"leptonica", leptonicaVersion()},
		{"engine-mode", "default (LSTM)"},
		{"lstm-choice-mode", "2"},
		{"models", strings.Join(models, " ")},
		{"host", "host/go/tess.go"},
	}
}

// binaryOf is the engine's thresholded image as a gray bitmap (0 ink, 255 paper), when the engine made one.
func (t *tess) binaryOf(h C.H) (w, hh int32, px []byte, ok bool) {
	pix := C.TessBaseAPIGetThresholdedImage(h)
	if pix == nil {
		return 0, 0, nil, false
	}
	w, hh = int32(C.pixGetWidth(pix)), int32(C.pixGetHeight(pix))
	d := int32(C.pixGetDepth(pix))
	px = make([]byte, int(w)*int(hh))
	for i := range px {
		px[i] = 255
	}
	var v C.uint
	for y := int32(0); y < hh; y++ {
		for x := int32(0); x < w; x++ {
			C.pixGetPixel(pix, C.int(x), C.int(y), &v)
			ink := v != 0
			if d != 1 {
				ink = v < 128
			}
			if ink {
				px[int(y)*int(w)+int(x)] = 0
			}
		}
	}
	C.pixDestroy(&pix)
	return w, hh, px, true
}

func (t *tess) fulfil(req port.PlanRequest, bm port.PlanBitmaps, keepBinary bool) (port.PlanAnswer, error) {
	id := req.ID()
	failed := func(status int32) (port.PlanAnswer, error) {
		c := port.NewCells()
		plan.AnFail(c, port.PlanKindOcr, id, status)
		return port.PlanAnswer{Rec: cellsVec(c)}, nil
	}
	b, ok := bm[req.Field(plan.OcBitmap())]
	if !ok {
		return failed(port.PlanStatusFailed)
	}
	if b.Ch != 1 && b.Ch != 3 {
		return failed(2)
	}
	psm, flags := req.Field(plan.OcPsm()), req.Field(plan.OcFlags())
	wantSyms := flags&1 != 0
	rx, ry, rw, rh := req.Field(plan.OcX()), req.Field(plan.OcY()), req.Field(plan.OcW()), req.Field(plan.OcH())
	installed := t.installed()
	// which installed models read the request is kin's (`op-models`: languages, scripts, the ones not installed dropped, none left is
	// every model); the models go in as code points separated by 0
	reqc := cellsOf(req.Rec)
	inst := port.NewCells()
	for i, m := range installed {
		if i > 0 {
			port.CellsPush(inst, 0)
		}
		for _, r := range m {
			port.CellsPush(inst, int32(r))
		}
	}
	picked := port.NewCells()
	ocrport.OpModels(reqc, inst, picked)
	gone := port.NewCells()
	ocrport.OpDropped(reqc, inst, gone)
	var dropped []rune
	for i := int32(0); i < port.CellsLen(gone); i++ {
		if cp := port.CellsAt(gone, i); cp == 0 {
			t.notes = append(t.notes, "no model for "+string(dropped)+": dropped")
			dropped = nil
		} else {
			dropped = append(dropped, rune(cp))
		}
	}
	var langs []string
	for i := int32(0); i < port.CellsLen(picked); i++ {
		langs = append(langs, installed[port.CellsAt(picked, i)])
	}
	lang := strings.Join(langs, "+")
	if lang == "" {
		return failed(2)
	}
	h, ok := t.api(lang)
	if !ok {
		return failed(port.PlanStatusFailed)
	}
	w, hh, ch := b.W, b.H, b.Ch
	px := b.D
	if b.Stride != w*ch {
		// rows are not tightly packed: pack them
		px = make([]byte, 0, int(w)*int(hh)*int(ch))
		for y := int32(0); y < hh; y++ {
			px = append(px, b.D[int(y)*int(b.Stride):int(y)*int(b.Stride)+int(w*ch)]...)
		}
	}
	dpi := ocr.OcfDpi(flags)
	if dpi == 0 {
		dpi = t.dpi
	}
	// Tesseract keeps the pointer until Clear: give it memory the Go runtime does not own
	cpx := C.malloc(C.size_t(len(px)))
	defer C.free(cpx)
	if len(px) > 0 {
		C.memcpy(cpx, unsafe.Pointer(&px[0]), C.size_t(len(px)))
	}
	setimg := func(h C.H) {
		C.TessBaseAPISetImage(h, (*C.uchar)(cpx), C.int(w), C.int(hh), C.int(ch), C.int(w*ch))
		if dpi > 0 {
			C.TessBaseAPISetSourceResolution(h, C.int(dpi))
		}
		if rw > 0 {
			C.TessBaseAPISetRectangle(h, C.int(rx), C.int(ry), C.int(rw), C.int(rh))
		}
	}
	osd, script, sconf, oconf := int32(-1), int32(0), int32(-1), int32(-1)
	if flags&2 != 0 {
		// OSD has its own model (`osd`) and its own engine instance
		ho, ok := t.api("osd")
		if !ok {
			ho = h
		}
		C.TessBaseAPISetPageSegMode(ho, 0)
		setimg(ho)
		var deg C.int
		var oc, sc C.float
		var sn *C.char
		if C.TessBaseAPIDetectOrientationScript(ho, &deg, &oc, &sn, &sc) != 0 {
			osd = int32(deg)
			oconf = x10(float32(oc))
			sconf = x10(float32(sc))
			script = ocrport.OpOsdScript(cellsOf(cps(C.GoString(sn))))
		} else {
			t.notes = append(t.notes, "osd failed")
		}
		C.TessBaseAPIClear(ho)
	}
	var words []tessWord
	var lines []tessLine
	var blocks []tessBlock
	var binW, binH int32
	var binPx []byte
	hasBinary := false
	C.TessBaseAPISetPageSegMode(h, C.int(psm))
	setimg(h)
	if C.TessBaseAPIRecognize(h, nil) != 0 {
		C.TessBaseAPIClear(h)
		return failed(port.PlanStatusFailed)
	}
	it := C.TessBaseAPIGetIterator(h)
	if it != nil {
		block, par, line := int32(-1), int32(-1), int32(-1)
		for {
			pi := C.TessResultIteratorGetPageIterator(it)
			if C.TessPageIteratorIsAtBeginningOf(pi, rilBlock) != 0 {
				block++
				var l, tp, r, bt C.int
				C.TessPageIteratorBoundingBox(pi, rilBlock, &l, &tp, &r, &bt)
				var o, wd, tl C.int
				var dk C.float
				C.TessPageIteratorOrientation(pi, &o, &wd, &tl, &dk)
				blocks = append(blocks, tessBlock{bx: [4]int32{int32(l), int32(tp), int32(r - l), int32(bt - tp)}, orient: int32(o), wdir: int32(wd), tlo: int32(tl),
					desk: int32(math.Round(float64(dk) * 180 / math.Pi * 100))})
			}
			if C.TessPageIteratorIsAtBeginningOf(pi, rilPara) != 0 {
				par++
			}
			if C.TessPageIteratorIsAtBeginningOf(pi, rilTextline) != 0 {
				line++
				var l, tp, r, bt C.int
				C.TessPageIteratorBoundingBox(pi, rilTextline, &l, &tp, &r, &bt)
				var x1, y1, x2, y2 C.int
				C.TessPageIteratorBaseline(pi, rilTextline, &x1, &y1, &x2, &y2)
				btype := ocrport.OpBlockType(int32(C.TessPageIteratorBlockType(pi)))
				lines = append(lines, tessLine{bx: [4]int32{int32(l), int32(tp), int32(r - l), int32(bt - tp)}, base: [4]int32{int32(x1), int32(y1), int32(x2), int32(y2)},
					block: block, par: par, btype: btype})
			}
			text := strings.TrimSpace(takeText(C.TessResultIteratorGetUTF8Text(it, rilWord)))
			if text != "" {
				var l, tp, r, bt C.int
				C.TessPageIteratorBoundingBox(pi, rilWord, &l, &tp, &r, &bt)
				var bo, itl, un, mo, se, sc, sz, fid C.int
				C.TessResultIteratorWordFontAttributes(it, &bo, &itl, &un, &mo, &se, &sc, &sz, &fid)
				fl := int32(bo&1) | int32(itl&1)<<1 | int32(un&1)<<2 | int32(mo&1)<<6 | int32(sc&1)<<7
				dfl := int32(0)
				if C.TessResultIteratorWordIsFromDictionary(it) != 0 {
					dfl |= ocr.OcwDict()
				}
				if C.TessResultIteratorWordIsNumeric(it) != 0 {
					dfl |= ocr.OcwNumeric()
				}
				psize := int32(-1)
				if sz > 0 {
					psize = int32(sz)
				}
				wd := tessWord{
					bx:     [4]int32{int32(l), int32(tp), int32(r - l), int32(bt - tp)},
					conf:   x10(float32(C.TessResultIteratorConfidence(it, rilWord))),
					line:   line,
					block:  block,
					text:   cps(text),
					flags:  fl,
					lang:   ocrport.OpWordLang(cellsOf(cps(C.GoString(C.TessResultIteratorWordRecognitionLanguage(it))))),
					psize:  psize,
					dflags: dfl,
				}
				if wantSyms {
					it2 := C.TessResultIteratorCopy(it)
					for {
						p2 := C.TessResultIteratorGetPageIterator(it2)
						var sl, st, sr, sb C.int
						C.TessPageIteratorBoundingBox(p2, rilSymbol, &sl, &st, &sr, &sb)
						stext := takeText(C.TessResultIteratorGetUTF8Text(it2, rilSymbol))
						sy := tessSym{bx: [4]int32{int32(sl), int32(st), int32(sr - sl), int32(sb - st)}, conf: x10(float32(C.TessResultIteratorConfidence(it2, rilSymbol))), cps: cps(stext)}
						ci := C.TessResultIteratorGetChoiceIterator(it2)
						if ci != nil {
							for {
								ct := C.GoString(C.TessChoiceIteratorGetUTF8Text(ci))
								if ct != stext {
									sy.alts = append(sy.alts, tessAlt{cps: cps(ct), conf: x10(float32(C.TessChoiceIteratorConfidence(ci)))})
								}
								if C.TessChoiceIteratorNext(ci) == 0 {
									break
								}
							}
							C.TessChoiceIteratorDelete(ci)
						}
						wd.syms = append(wd.syms, sy)
						if C.TessPageIteratorIsAtFinalElement(p2, rilWord, rilSymbol) != 0 || C.TessResultIteratorNext(it2, rilSymbol) == 0 {
							break
						}
					}
					C.TessResultIteratorDelete(it2)
				}
				words = append(words, wd)
			}
			if C.TessResultIteratorNext(it, rilWord) == 0 {
				break
			}
		}
		C.TessResultIteratorDelete(it)
	}
	// the image the engine thresholded to read this page; its pixels only when a transcript will keep them
	if keepBinary {
		binW, binH, binPx, hasBinary = t.binaryOf(h)
	} else {
		pix := C.TessBaseAPIGetThresholdedImage(h)
		if pix != nil {
			hasBinary = true
			binW, binH = int32(C.pixGetWidth(pix)), int32(C.pixGetHeight(pix))
			C.pixDestroy(&pix)
		}
	}
	C.TessBaseAPIClear(h)

	// Words, then the extension, through the kin builders.
	var orient, wdir, tlo, deskew int32
	if len(blocks) > 0 {
		orient, wdir, tlo, deskew = blocks[0].orient, blocks[0].wdir, blocks[0].tlo, blocks[0].desk
	}
	c := port.NewCells()
	at := plan.AnOcr(c, id)
	for i := range words {
		w := &words[i]
		wd := plan.AnOcrWord(c, at, w.bx[0], w.bx[1], w.bx[2], w.bx[3], w.conf, w.line, w.block)
		for _, cp := range w.text {
			plan.AnOcrCp(c, wd, cp)
		}
	}
	bin := int32(0)
	if hasBinary {
		bin = 1
	}
	ocr.OxBegin(c, orient, wdir, tlo, deskew, osd, script, sconf, oconf, int32(len(lines)), int32(len(blocks)), bin, binW, binH)
	for _, l := range lines {
		ocr.OxLine(c, l.bx[0], l.bx[1], l.bx[2], l.bx[3], l.base[0], l.base[1], l.base[2], l.base[3], l.block, l.par, l.btype, -1, -1, -1)
	}
	for _, b := range blocks {
		ocr.OxBlock(c, b.bx[0], b.bx[1], b.bx[2], b.bx[3], b.orient, b.wdir, b.tlo)
	}
	for i := range words {
		w := &words[i]
		wx := ocr.OxWord(c, w.flags, w.lang, w.psize, w.dflags)
		for _, s := range w.syms {
			sx := ocr.OxSym(c, wx, s.bx[0], s.bx[1], s.bx[2], s.bx[3], s.conf)
			for _, cp := range s.cps {
				ocr.OxSymCp(c, sx, cp)
			}
			for _, a := range s.alts {
				ax := ocr.OxAlt(c, sx, a.conf)
				for _, cp := range a.cps {
					ocr.OxAltCp(c, ax, cp)
				}
			}
		}
	}
	plan.AnOcrEnd(c, at)
	ans := port.PlanAnswer{Rec: cellsVec(c)}
	if keepBinary && hasBinary && len(binPx) > 0 {
		ans.Bitmap = port.BitmapFromBytes(binW, binH, 1, binPx)
	}
	return ans, nil
}

// TessOcr is the `Ocr` port over Tesseract: an embedder gives `Config.Ocr = ocr`. A POOL OF ENGINES (P10.2, D509): Tesseract's API objects are not
// thread safe, so each reading takes an engine of its own from the pool (or makes one, which is a full model load) and gives it back; the pool holds as
// many as were ever wanted at once, which is as many as the conversions and the workers of a `Parallel` conversion that read at the same moment. A serial
// run has one. A reading is a function of the request and the model alone (the LSTM engine learns nothing between pages), so which engine reads a page
// does not show in the answer.
type TessOcr struct {
	mu       sync.Mutex
	dir      string
	idle     []*tess
	producer [][2]string
}

// NewTessOcr opens the engine on the directory of `.traineddata` files; "" looks in $TESSDATA_PREFIX and the usual places.
func NewTessOcr(tessdata string) (Ocr, error) {
	dir := tessdata
	if dir == "" {
		var cands []string
		if p := os.Getenv("TESSDATA_PREFIX"); p != "" {
			cands = append(cands, p, filepath.Join(p, "tessdata"))
		}
		cands = append(cands, "/opt/homebrew/share/tessdata", "/usr/local/share/tessdata", "/usr/share/tesseract-ocr/5/tessdata", "/usr/share/tesseract-ocr/4.00/tessdata", "/usr/share/tessdata")
		for _, d := range cands {
			if ents, err := os.ReadDir(d); err == nil && len(ents) > 0 {
				dir = d
				break
			}
		}
		if dir == "" {
			return nil, errors.New("no tessdata directory found (set TESSDATA_PREFIX)")
		}
	}
	t := &tess{tessdata: dir, apis: map[string]C.H{}}
	return &TessOcr{dir: dir, idle: []*tess{t}, producer: t.producer()}, nil
}

// Fulfil implements Ocr.
func (o *TessOcr) Fulfil(req port.PlanRequest, bm port.PlanBitmaps, keepBinary bool) (port.PlanAnswer, error) {
	o.mu.Lock()
	var t *tess
	if n := len(o.idle); n > 0 {
		t, o.idle = o.idle[n-1], o.idle[:n-1]
	}
	o.mu.Unlock()
	if t == nil {
		t = &tess{tessdata: o.dir, apis: map[string]C.H{}}
	}
	// an engine that panicked is not given back: its state is not known
	a, err := t.fulfil(req, bm, keepBinary)
	o.mu.Lock()
	o.idle = append(o.idle, t)
	o.mu.Unlock()
	return a, err
}

// Producer implements Ocr.
func (o *TessOcr) Producer() [][2]string { return o.producer }
