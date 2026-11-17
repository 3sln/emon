# Experimental optional PP-OCRv5

Tesseract fast remains the default. The maintainer approved experimental opt-in
shipping on 2026-11-09 (D865), with OP1c fusion frozen at policy
`ppocr5-en-0420cd75efc786ba37d1e028a4c9396d2f17a3b4264880f1296a7f186faced7f`.
OP1c's fixed receipt precision floors and raster CPU limit failed: SROIE P/R
.701436/.756857 and CORD .469098/.696764; five single-page raster controls
exceeded 3× CPU, with F16 at 13.42×. Peak process RSS was 2.17 GiB.
All eight required born-digital bundles were identical and the sampled
69 changed readable lines had no new incumbent losses. These measurements
do not establish default-use acceptance or comprehensive source correctness.

`EMON_CS_SHARED_COMPILATION=false bb ppocr-fetch` downloads only the assets in
`assets.edn`, verifies SHA-256 and size, and caches them in `.cache/ppocr`.
It is not part of any gate. Re-running it with verified cached files is offline.
`bb ppocr-check` verifies the cached assets, extracts the checked runtime archive,
compiles the C ABI ownership shim, runs the Rust CTC/geometry/runtime smoke
checks and builds the raw calibration probe. It never downloads. The current
reference checker uses the macOS arm64 runtime. The release builder has separate
five-platform pins in `runtimes.edn`; the other four platforms need their CI
build/smoke checks and are not quality-qualified here. Model files and runtime binaries are carried only by the separate experimental
[release add-on](../../docs/ppocr.md), never the default artifacts.

Build the reference driver with `EMON_RS_PPOCR=1 kin/scripts/emon-rs-build`.
The optional build includes the incumbent Tesseract adapter automatically;
`EMON_RS_PDFIUM=.cache/pdfium/current/lib` adds the PDF renderer. Build caches
stay in the worktree. Run it from the worktree root with
`--ocr-engine=ppocr`. `--ocr-engine=tesseract` is the default and retains the
existing `--tesseract` port configuration.

Native host bindings share the exact Rust preprocessing and complete answer
construction through `host/native/include/emon_ppocr.h` (ABI v1). Run
`bb tools/ppocr/build_shared.clj` after fetching the pinned optional assets;
this builds `.cache/ppocr/libemon_ppocr_adapter.dylib`. It never downloads.
Go uses cgo (`NewPpOcr`, build tag `ppocr`; the native driver build
`EMON_GO_LIVE=1 kin/scripts/emon-go-build` enables it together with Tesseract), Java FFM (`emon.ffi.PpOcr.open`), C# P/Invoke
(`emon.Ffi.PpOcr.Open`), and C/pod the direct native binding. Embedders pass
the incumbent Tesseract OCR port to the wrapper; close/dispose it after all
conversions finish. Drivers select it with `--ocr-engine=ppocr`. C/pod use
their existing native-port builds and native conversion option.
`EMON_PPOCR_LIB` overrides the adapter library and `EMON_PPOCR_MODELS` the
model directory (development defaults under `.cache/ppocr`). The adapter
loads ORT at run time. Default builds and replays need neither the adapter
nor ORT. Missing assets report `ppocr-models-missing`; missing native
libraries report `ppocr-runtime-missing`.

`bb ppocr-hosts` is a non-gate requiring the models and an OIL qualification
artifact (by default `$EMON_SCRATCH/ppocr-qual`). It compares all complete integer answers on exactly 225 bitmaps
through Rust, Go, Java, C# and both C/native transports; missing inputs fail
explicitly. `--qualification DIR` selects the artifact,
`--out DIR` the scratch output. `--limit N` is only a development smoke test
and marks the report unqualified. Browser/JS setup and release bundling are
documented separately in [the experimental engine guide](../../docs/ppocr.md).
The only quality-qualified native runtime remains macOS arm64.

To re-qualify after an answer-format change, use the restored OP1c selections:

```sh
bb ppocr-hosts --qualification "$EMON_SCRATCH/op1c" \
  --record-qualification "$EMON_SCRATCH/ppocr-qual"
```

This records a new Rust reference, requires exact transport and replay bytes,
and scores the same 60+60 fixed receipts against D864's P/R tolerance of 0.002.
Historical answer hashes are not an OIL reference. The new artifact contains
relative bitmap paths, reference answers, recordings, bundles, the replay
manifest, accuracy measurements and `PROVENANCE.edn`; logs stay in `--out`.
The provenance marks transport, replay and accuracy completion separately.
A failed run is not an accepted qualification artifact.

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
`kin/ocalibdata.kin` (weighted isotonic repair by `bb tools/calibration/ocr_words.clj --write`). Empty low-score bins conservatively map to
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

The optional path inspects Tesseract and measured ink before requesting PP,
sharing the retarget round. It skips PP only when every incumbent word meets
the existing 700 confidence floor and has dictionary or valid-format evidence,
and every measured ink component fits wholly inside an incumbent word. Missing
ink evidence never proves completeness. ORT sessions initialize lazily on the
first actual PP request; model pins are still verified at construction. If
Tesseract enlarges its own view, PP keeps the original bitmap's view and scale.
RC5c numeric protection remains in force. Optional
P-ARB sum proposals require ruled numeric columns and a neighboring ruled column, then use the existing bounded arithmetic kernel's
compatibility, uniqueness and competing-explanation guards. An incumbent-only
neighbor identity supplies independent support; otherwise the stricter unique
solution and minimum-total rules for single-column arithmetic apply. Source words are
copied verbatim and `closure-sum` decisions name the identity endpoints.
The option also refuses shortened slot replacements and protects year ranges;
a dictionary-backed incumbent needs independent agreement for lexical
replacement, while a dictionary-backed
insertion still needs calibrated confidence >=700.

An empty slot can also insert a verbatim alphanumeric identifier independently
present in a non-overlapping selected Tesseract word on the same page. Both
words must meet the existing 700 floor. Exact case-sensitive agreement is
required; PP cannot corroborate itself, valid numeric formats are excluded,
and competing candidates are refused. This rule cannot replace an incumbent
and does not manufacture reading votes or dictionary membership (D861).

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

`bb tools/ppocr/models.clj` checks integrated live Models on the six native
hosts and complete inference-free replay bundles on every host with a replay
API. The pod's existing one-shot API has no replay method. The check uses the
committed receipt, empty-page and rotated scan fixtures, and supplies missing
model/runtime paths during replay. Run it serially after `ppocr-hosts`.

`EMON_RS_LIVE=1 kin/scripts/emon-rs-build` also enables the native Rust
run-time binding to the shared adapter, with the same named model/runtime
errors as Go, Java, C#, C and pod. The `EMON_RS_PPOCR=1` reference build stays
available as the independent direct-call qualification oracle. The CLI loads the separate release add-on through the same environment variables.
The browser uses the separate optional npm package.

The integrated check then replays all 198 retained OP1c candidate recordings
(174 fixed plus 24 fresh) through the six available replay drivers, including
both direct-reference and runtime-bound Rust. Complete bundles must match
the qualified bytes. It compares stdout directly through a pipe, keeping only small result summaries. No model or runtime
path is available to those replay invocations.

The optional [browser and Node adapter](../../docs/ppocr-web.md) uses ORT Web
1.22.0 with the same Rust pipeline in a separate optional emon wasm build.
It requires caller-supplied pinned assets and remains experimental; its live
answer qualification is separate from the native hosts. `web_qualify.clj`
reads the new OIL `reference.answers` from `--qualification DIR` (default
`$EMON_SCRATCH/ppocr-qual`), checks its provenance checksum and completed native
transport comparison. D891 compares ORT Web with native ORT using a scoped
rule: text, geometry, indices, capabilities, headers and sidecars are identical,
except the checked web observed-identity hash and raw word-confidence and
`det-score` values, which may differ by at most one per mille. No other score
or sidecar receives a tolerance. Two fresh runs within the web identity must
be byte-identical. Full qualification also replays the actual web answers in
the fixed 60 CORD and 60 SROIE recordings through native kin composition and
requires D864 word P/R within ±0.002. Native transport comparisons stay exact.
It reports header equality and the first differing cell;
historical OP1c answer hashes are not an OIL reference. `--candidate browser`
runs Chrome instead of Node, and `--compare-only true` checks an existing
candidate capture plus its `*-repeat.jsonl`. A `--limit N` run is a development
check, not qualification. Use a fresh output directory for receipt scoring.

The coordinator can run the complete Chrome comparison with one command after
installing the locked JS dependencies and restoring the pinned model/runtime
assets. The frozen wasm input is retained with the qualification artifact:

```sh
EMON_CS_SHARED_COMPILATION=false EMON_PPOCR_WASM="$EMON_SCRATCH/ppocr-qual/web/emon.wasm" bb -cp tools tools/ppocr/web_qualify.clj --candidate browser --out "$EMON_SCRATCH/oil-chrome" --qualification "$EMON_SCRATCH/ppocr-qual"
```

Expected output includes `"answers":225`, `"accepted":225`,
`"deterministic":true`, and `PASS D891 web receipt P/R within +/-0.002 on fixed
60 CORD and 60 SROIE`. The exact native/web byte count is diagnostic; all 225
must satisfy the scoped rule. Chrome launch may require running outside an
agent sandbox. Receipt replay uses `.cache/emon-rs-fusion-ppocr/target/release/emon-rs`
and requires its SHA-256 to match the qualification artifact's recorded native
driver; it does not silently score with a different policy build.
The retained `web/emon.wasm` and its provenance qualify the frozen reference's
policy. A new build may be supplied with `EMON_PPOCR_WASM`; its observed policy
hash must match the native artifact. Qualifying the frozen pair does not accept
later source changes.

The retained replay sweep includes a 74-page booklet and can take hours
serially. It is an optional qualification run, not a normal CI gate.
New reference recordings use the Rust PP build with pinned pdfium and
`--pdfium`, including on the selected controls. Reference provenance records
the renderer and driver hashes. A control that historically rendered
successfully must still exercise successful render fulfilment; a reference
made with unsupported render answers cannot qualify that control.
Reference bundles are gzip-compressed after their six replay comparisons,
with the decompressed SHA-256 checked before removing the raw capture.
Both replay and accuracy readers accept the `.bundle.gz` fallback. To compact
already completed cases of an in-progress run, use `bb tools/ppocr/compact.clj
--qualification DIR --summary FILE`; it only touches complete six-host groups.
