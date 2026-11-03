# Optional PP-OCRv5 Rust reference

Tesseract fast remains the default (D843). This reference is optional and is
not accepted until the coordinator's complete OP1 table passes. The OE Python
measurements are not acceptance results for this adapter.

`EMON_CS_SHARED_COMPILATION=false bb ppocr-fetch` downloads only the assets in
`assets.edn`, verifies SHA-256 and size, and caches them in `.cache/ppocr`.
It is not part of any gate. Re-running it with verified cached files is offline.
`bb ppocr-check` verifies the cached assets, extracts the checked runtime archive,
compiles the C ABI ownership shim, runs the Rust CTC/geometry/runtime smoke
checks and builds the raw calibration probe. It never downloads. The current
runtime archive is macOS arm64 only; other native platform archives still need
qualification. Model files and runtime binaries are not bundled into releases.

Build the reference driver with `EMON_RS_PPOCR=1 kin/scripts/emon-rs-build`.
The optional build includes the incumbent Tesseract adapter automatically;
`EMON_RS_PDFIUM=.cache/pdfium/current/lib` adds the PDF renderer. Build caches
stay in the worktree. Run it from the worktree root with
`--ocr-engine=ppocr`. `--ocr-engine=tesseract` is the default and retains the
existing `--tesseract` port configuration. Go, Java, C#, and Rust builds without
the optional adapter reject `ppocr` by name. Later bindings are not implemented.

The port verifies all graph bytes before session construction. ORT 1.22.0 CPU
uses sequential execution, one intra-op and one inter-op thread, and fixed graph
optimization. No accelerator provider is registered. The image pipeline is Rust:
RGB bilinear resize with integer byte rounding, max-side-960 detection rounded
to multiples of 32, DB threshold .3, 2x2 dilation, component mean threshold .5,
convex hull offset at area*1.6/perimeter, and a minimum-area rotated rectangle.
This is a pinned reference policy, not an assertion of RapidOCR preprocessing
parity. Geometry and reading order use stable ties. Crops use the measured
rectangle, vertical crops turn a quarter turn, and the classifier reverses a
crop only for a strict 180-degree posterior above .9. Recognition uses height
48, minimum tensor width 320, maximum width 4096, zero padding, the embedded
English vocabulary, CTC blank zero, and greedy lowest-index tie breaking.

Word x extents come from the CTC label support timesteps, undo tensor padding
and resize, and map through the actual crop transform. Integer pixel bounds
round outward and clip to the request bitmap. They are never evenly divided
from a line polygon. Line-height extents are the detector crop extents; source
alignment still needs acceptance review. Font, symbol alternatives and baselines remain unknown. Verbatim PP words
are checked against a dedicated, pixel-free English Tesseract lexicon instance;
its model bytes must match the tessdata-fast English SHA-256 pin. Positive
membership is recorded explicitly and the pin appears in producer provenance.

`tools/oracle/ppocr/calibrate.py` uses existing SROIE train annotations and explicitly excludes
baseline IDs. Its fit and validation populations are disjoint (40 pages each).
It aligns each predicted word to an unambiguous annotated source line by
geometry, then aligns ordered NFC/casefolded tokens within that line. It does
not invent individual gold word boxes. `calibration.json` contains the IDs,
reliability bins and Brier/ECE values; source-bearing labels and probes stay in
external scratch. The frozen ten-bin integer lookup is in
`host/rust/src/ppocr_calibration.rs`. Empty low-score bins conservatively map to
zero. Annotation errors and source-line rather than individual-word gold are
limitations; this is not the 40-line changed-control source audit. Raw CTC
posteriors are never used as Tesseract confidence.

The PP-OCR request trailer contains engine and policy identity. Its tail is parsed independently of the legacy language offset; actual bitmap hashes distinguish compact crops over a view from fully hashed view requests. Repeated tagging is canonical and idempotent. The identity
covers models, vocabulary pin, runtime policy, preprocessing, geometry,
decoding, calibration and answer construction. `identity.clj` checks it;
`bb tools/ppocr/identity.clj --write` regenerates it after a deliberate policy change, followed
by `bb emit`. Changing an identity invalidates recordings. Legacy Tesseract
request bytes stay unchanged for existing replay fixtures. PP-OCR evidence
names the identity on eligible image/scanned pages. Born-digital pages retain their
ordinary Tesseract processing and evidence. One additional PP reading is recorded
separately in `oruns`/`owords`; all incumbent and targeted requests stay Tesseract.
The additional reading enters guarded per-word fusion, with format and
dictionary/agreement protection unchanged. Replays use recorded integer answers and do not create
a PP-OCR port or execute inference.

`bb receipts --ocr-engine=ppocr --n 60 --measure-cost` selects the reference in
the existing receipt scorer. Use `EMON_RECEIPTS_OUT` for external outputs.
`--measure-cost` captures per-process peak RSS using the owned child's `wait4` resource result (macOS byte units, Linux KiB converted to bytes);
CPU includes process/session initialization and all existing emon passes.

The optional path shares its extra full-raster request with the first Tesseract
OCR round. If Tesseract later enlarges its own view, PP keeps the original
bitmap's view and scale. RC5c numeric protection remains in force. Optional
P-ARB sum proposals require ruled numeric columns and a neighboring ruled column, then use the existing bounded arithmetic kernel's
compatibility, uniqueness and competing-explanation guards. An incumbent-only
neighbor identity supplies independent support; otherwise the stricter unique
solution and minimum-total rules for single-column arithmetic apply. Source words are
copied verbatim and `closure-sum` decisions name the identity endpoints.
The option also refuses shortened slot replacements and protects year ranges;
a dictionary-backed incumbent needs independent agreement for lexical
replacement, while a dictionary-backed
insertion still needs calibrated confidence >=700.

Run option-off verification serially with
`bb tools/ppocr/final_gates.clj --out EXTERNAL-DIR`. It snapshots the review
index under `.cache`, leaves the real index untouched and stops on the first
failed command. Live experiments and measurements are under
`tools/oracle/ppocr/`; they are not gates.

PP leaves all four raw baseline coordinates at -1. The shared adapter treats
that as absence and uses the measured line-box bottom for layout only; it is
not a measured engine baseline. Acceptance scoring checks represented Model
text as well as selected evidence, with separate occurrence budgets for repeated
numeric slots, so evidence-only repairs cannot be counted as output gains.
