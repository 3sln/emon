# emon — DEVLOG

The live tracker. `SPEC.md` says what emon is and changes only when a decision
changes; this file says what is being built, in what order, and why that
order. Steps, findings and decisions are added here as work happens. Modelled
on nome's `ROADMAP.md`.

**Status keys:** `[ ]` not started · `[~]` in progress · `[x]` done ·
`[!]` blocked (say on what) · `[?]` open question, must be answered before the
item starts.

**How to take an item.** Every Phase 0 and Phase 1 item has a *Brief* (what
to build), *Files* (where), and *Done when* (the check that goes green). An
item is done when its check is green **and** its entry here says what was
learnt, with the commit. A decision taken while doing it gets the next `D###`
in the Decisions section — decisions do not block work, but they must be
written down.

---

## Current milestones

The next five pieces of work, in the order they should be taken. Each is also
filed under its phase; this block exists so the ORDER survives. Cross off here
and in the phase item both.

1. [x] **P0.1 — Skeleton that generates four targets and agrees.**
   `bb check` green on a `hello.kin`.
2. [x] **P0.2 — The host-port vocabulary**: `emon.lang` tags, `emon.port`
   annotations in four host trees (Blob, Cells, Text, limits), the
   `emit-ascii` form; `bb hosts` green.
3. [x] **P0.3 + P0.6 — The two vocabulary extensions**: `emit-ascii`
   (literal → text sink, ASCII-checked at generation time) and `defenum`
   (kind code ↔ JSON spelling from one table).
4. [x] **P0.4 + P0.5 — emon's own substrate modules and `bb unit`**:
   `bytes`, `inflate`, `normalize` + tables, `splitmix`, written in emon
   (no code shared with nome, D039); `emon.test` laws running natively on
   every target with a framework.
5. [x] **P1.1 — The Model in kin** (`emon.model`): arenas, builder, accessors,
   and laws in `bb unit`.

After these: P1.2 (canonical JSON) → P1.10 in order (P1.11, the `Src`
port of D058, went in before P1.4/P1.5 closed: done), which completes the
first vertical slice (a born-digital PDF's text layer → Model → JSON and
HTML, on the Rust host). P0.7/P0.8 are hygiene and can be taken by anyone
between items.

---

## The ordering principle

**1. Every target exists from day one; every host layer does not** (nome's
rule, D002). The core generates into Rust, Go, Java and C# from the first
commit and `bb check` compiles all four. Host layers arrive in order: Rust,
then Go, then Java and C#. Go is second because it is the proof the kin
arrangement survives a different error model; and **the cross-target identity
gate exists before the second host layer does anything else.**

**2. Measure before you guess** (D031). Accuracy is the product, and
heuristics tuned without a scoreboard are tuned to whatever document the
developer last looked at. So the ground-truth pipeline (the synthetic round
trip, D041; the gold format, `bb accuracy`) is built in Phase 2 — **before** the first
heading, table or fusion heuristic in Phases 4–7.

**3. Clean evidence before noisy evidence.** Structure recovery (blocks,
headings, lists, tables, furniture) is developed first on born-digital text
layers, where the evidence is exact, so an algorithm's errors are the
algorithm's. OCR and the tracer then feed the *same* evidence layer, and what
changes in the scores is attributable to the source. The risk this ordering
carries — an evidence schema designed without OCR in mind — is paid down in
Phase 1 by defining the evidence schema with OCR's fields from the start
(P1.3), and in Phase 2 by the transcript spike (P2.6).

**4. A vertical slice before breadth.** The first slice is thin and goes all
the way through: bytes → Source → Evidence → Model → JSON/HTML → a gate. Every
later item widens a stage that already exists end to end.

---

## Phase 0 — Foundations

No PDF. The goal: an emon repository whose kin sources generate four modules
that compile, run and agree; whose host trees declare their ports and agree;
and whose gates exist before there is anything to gate.

- [x] **P0.1 — Project skeleton, following nome's conventions.**
  *Brief*: create the repository layout of SPEC §15. The layout follows
  nome's conventions, but every file is emon's own; nothing is shared with
  nome and nothing references `../nome` (D039): `bb.edn` with tasks `gen`, `emit`, `check`, `drift`, `hosts`
  (and nothing else yet); `deps.edn` (`{:paths ["kin/src"] :deps
  {io.github.3sln/kin {:local/root "../kin"}}}`); `kin/src/emon/project.cljc`
  (vocabularies `[emon.lang emon.test kin.lang]`, targets, `:sources` over
  `kin/*.kin`, `:host` scans); `kin/src/emon/targets.cljc` (the same
  pattern nome uses, with `go-module "emon"` and `gen/` paths; C# test
  modules to `Tests/`); `kin/src/emon/lang.cljc` with the plain tags (`I32
  U32 I64 Bool Blob Cells Sink`, each with four `:types` and a Go `:zero`)
  and emon's own cast forms; `kin/scripts/{gen,emit,check,drift}` written for
  emon (with a gofmt step and an input-hash cache, the ideas behind nome's
  D566); `kin/hello.kin` (gcd
  or similar); `.gitignore`; `git init` with `gen/` committed (nome D26).
  *Files*: everything above; `gen/{rust,go,java,csharp}/` emitted.
  *Done when*: `bb emit` writes four modules; `bb check` compiles and runs
  Rust, Go, C# (and Java where a JDK exists — record which) and all print the
  expected answer; `bb drift` is green and turns red on a hand edit to `gen/`
  (test that it does — nome D27/D28).
  *Done 2026-09-30.* `bb emit` writes four modules per source; `bb check` compiles and runs
  Rust, Go, Java (JDK at `/opt/homebrew/opt/openjdk`; `/usr/bin/java` is the
  macOS stub, so `check` tests that `javac -version` runs) and C#, all agree;
  `bb drift` green and red on a hand edit to `gen/` (verified). Learnt: drift's
  1a ("re-emit changed nothing") is blind to a hand edit when emit's stamp cache
  says "current" -- 1b (diff vs the index) is what catches it, as in nome.
  `gen/` is **staged, not committed** (the task forbade commits): drift compares
  against the git index, so staging is enough for it to work. Commit: pending
  (see D037). `check` copies whole `gen/<target>/` trees instead of nome's
  hand-listed modules.

- [x] **P0.2 — Host ports, declared at the host.**
  *Brief*: the minimal port set every target needs to run kin code:
  `blob-len`, `blob-u8`, `blob-u16`, `blob-u32` (big-endian) and little-endian
  twins (TIFF-in-PDF and image headers need both); `cells-len/at/set/push/drop`;
  the **`Text` sink**: `text-cp` (append a code point as UTF-8), `text-ascii`
  (append an ASCII literal), `text-i32` (decimal); `limit-of`,
  `limit-breach`, `setting-of`. Each is a hand-written function with an
  `@kin:link:form:` annotation in `host/rust/src/port.rs`,
  `host/go/port/port.go`, `host/java/emon/Port.java`,
  `host/csharp/Emon/Port.cs` — nome's exact pattern (`:template`, `:arity`,
  `:throws`, Go's `:import`). Add tags `Text`, `Cpt`, `Px`, `Fx`, `Conf` to
  `emon.lang` (SPEC §6.3). Add `kin/scripts/hosts` (`kin.host/check-agreement`
  + `kp/check-usage`).
  *Files*: `host/*/…/port.*`, `kin/src/emon/lang.cljc`, `kin/scripts/hosts`.
  *Done when*: `bb hosts` green; deleting one annotation from one host turns it
  red naming the form and the target; a `kin/porttest.kin` using every form
  runs in `bb check` on every target with one answer.
  *Done 2026-09-30.* `bb hosts` green; deleting `blob-u32le`'s annotation from
  Java turned it red: "emon.port/blob-u32le (form) is declared by csharp go
  rust and not by java". `kin/porttest.kin` uses all 22 forms and prints one
  text line, compared on four targets against an expected string worked out by
  hand (D036). Not added (not in the brief): `Bitmap` tag/forms (P6.1),
  `text-len`, `sink-i32`.

- [x] **P0.3 — The `emit-ascii` vocabulary extension** (SPEC §6.3.1, D028).
  *Brief*: a project-local form in `emon.lang`: `(emit-ascii t "literal")`
  checks at *generation time* that the literal is printable ASCII (0x20–0x7E)
  and refuses by name otherwise, then renders the target's call to the host's
  `text-ascii` with the literal spelled by `pr-str` (valid in all four
  languages for printable ASCII — verify `\"` and `\\` in each). Write the
  generation-time refusal as a Clojure test beside the vocabulary.
  *Files*: `kin/src/emon/lang.cljc`, `kin/src/emon/lang_test.clj` (or the kin
  test idiom nome uses), a probe in `kin/porttest.kin`.
  *Done when*: probe prints `{"a\"b\\c":1}` identically on every target; a
  non-ASCII literal fails generation with a message naming the form and file.
  *Done 2026-09-30.* `emit-ascii` in `emon.lang`; `probe-ascii` in
  `kin/porttest.kin` prints `{"a\"b\\c":1}` on all four targets (`bb check`
  now compares four lines per target). `bb lang-test`
  (`kin/src/emon/lang_test.clj`, run first by `bb check`) shows the refusals:
  `emon: (emit-ascii t "é") in emon.x: the literal "é" is not printable ASCII
  (0x20-0x7E): U+00E9 at index 0`. It names the NAMESPACE, not the file path:
  kin's context carries no source label (D042).

- [x] **P0.4 — emon's own substrate modules for the first slice** (D039,
  which supersedes D024).
  *Brief*: write, in emon, the substrate the slice needs: `bytes`
  (big/little-endian reads over `Blob`), `inflate` (RFC 1950/1951, including
  the fixed and dynamic Huffman paths and stored blocks), `normalize` (UAX #15
  NFC plus the ligature decomposition of SPEC §3.6) with its data tables
  generated by an emon script from the Unicode Character Database, and
  `splitmix`. **No code is copied from nome and nothing references
  `../nome`**; nome's modules may be read as reference, as any other
  implementation of the same specification may. Vendor the *specifications*
  (RFC 1951 text, UCD files) under `test/`, as the source of truth for tests.
  *Files*: `kin/{bytes,inflate,normalize,nftable,splitmix}.kin`,
  `kin/scripts/ucd.py` (table generator), `test/inflate/`, `test/normalize/`.
  *Done when*: all modules emit and compile on four targets; `bb check` runs
  an inflate probe over zlib-produced streams (levels 0, 1, 6, 9) and the
  UCD's `NormalizationTest.txt` NFC column, one answer on every target.
  *Done 2026-09-30.* All four modules emit and compile on four targets, and
  their laws and row corpora run in `bb unit`, 39 laws on each of Go, Rust,
  Java and C#, same count. The "done when" asked for a probe in `bb check`;
  the corpora are row drivers written in kin and run under `bb unit`
  instead (D046), which is the stronger check (status, consumed count and
  bytes per row, not one answer line). Per module:
  `bytes` (signed/24-bit/unsigned-32-widened reads, bounds questions,
  defaults, `by-find`/`by-find-back`; D049), `splitmix` (SplitMix64 stream,
  pure `sm-at`, `sm-below`; checked against the reference vectors for five
  seeds; D049), `inflate` (RFC 1950/1951 over a `Blob`; D047; 346-row corpus
  from CPython's zlib at levels 0/1/6/9, five strategies, window sizes, flush
  modes, plus hand-built, damaged and truncated streams (most of the rows) that zlib also
  rejects), `normalize` + `nftable` (NFD/NFC plus the ligature fold, D048;
  the whole of Unicode 18.0.0's `NormalizationTest.txt`, 20171 lines, NFC
  and NFD invariants). Files: `kin/{bytes,inflate,normalize,nftable,splitmix}.kin`
  (+ `*test.kin` beside each), `kin/scripts/{ucd.py,inflate-corpus.py}`,
  `test/inflate/{rfc1950.txt,rfc1951.txt,streams.txt}`,
  `test/normalize/{UnicodeData,DerivedNormalizationProps,NormalizationTest}.txt`
  (official, unmodified, fetched from unicode.org `latest/ucd`; RFCs from
  rfc-editor.org). Learnt: writing the corpus generator found two of my own
  wrong expectations (an "unassigned code" row that is really an incomplete
  code set; a stream whose padding bits are read as the next block) -- each
  caught because zlib itself is asserted to agree. Not done, on purpose:
  output-limit breach (`limit-breach`) has no law, since a law cannot change
  the environment per test; decoding is one bit at a time (D047); NormalizationTest's
  "every other code point is unchanged" invariant (Part 2 preamble) is not
  checked. Commit: pending (D037).

- [x] **P0.5 — `emon.test`: laws as native tests.**
  *Brief*: write `emon.test` (a `deftest` form + assertion forms through the
  port — the same shape nome's test vocabulary has, written fresh), three targets (Rust, Go, C#) plus
  Java if a JDK and JUnit are available here. `kin/scripts/unit` copies
  generated test modules where each framework wants them and runs them.
  *Done when*: `bb unit` runs a trivial law on every framework target and
  fails when the law is broken.
  *Done 2026-09-30.* `bb unit [go|rust|java|csharp|red]` runs every `kin/*.kin`
  whose `ns` requires `emon.test` as native tests on **all four** targets, Java
  included (D044: no JUnit here, so a reflection runner). Forms: `deftest`,
  `assert-eq`, `assert-eq-at` (a row index), `assert-eq64`, `assert-that`,
  `assert-that-at`, `assert-text`, `assert-cells-hex`, and the inputs
  `text-blob`, `hex-blob`, `test-file`, `cells-blob`, `new-cells`, `new-text`.
  `bb unit red` flips every assertion (`EMON_TEST_INVERT=1`) and requires each
  arm to fail: that is the "fails when the law is broken" check, permanent
  and on all four. Also broke `iz-lbase` and `nz-order` by hand: both turned
  the suite red with the row named. The count of laws is compared across arms.
  Host support is in `host/*/testport.*`, beside the ports. Learnt: Go's
  test parameter must not be `t` (a law's text sink is the natural `t`), so it
  is `emonT`; kin's `let` is function-flat in Java and C#, so one function may
  not bind the same local name twice (Rust and Go shadow; see "Found in kin");
  Rust takes every `Cells` as `&mut`, so one `Cells` cannot be passed twice in
  one call. Commit: pending (D037).

- [x] **P0.6 — `defenum`: kinds as one table** (D028, SPEC §6.3.1 item 4).
  *Brief*: a form (in `emon.lang`) taking a name and a vector of
  `[symbol code "json-spelling"]` rows, generating one `^:pub` constant
  function per row (`nk-paragraph` → 3) *and* a `…-emit-name` function that
  writes the spelling for a code to a `Text` sink through `emit-ascii`
  (a `case` over codes). Refuse duplicate codes or spellings at generation
  time.
  *Done when*: a probe enum round-trips code → spelling on every target;
  duplicate rows fail generation by name. If it proves awkward, record why
  and fall back to nome's hand-written constants plus a generated table.
  *Done 2026-09-30.* `(defenum ^:pub nk [[nk-paragraph 3 "paragraph"] ...])`
  in `emon.lang` (D042, D043). `kin/enumprobe.kin` round-trips codes 0..8
  through `nk-emit-name` and sums the constants; all four targets print the
  same line, checked in `bb check`. Duplicate symbol/code/spelling, a bad row,
  a non-ASCII or quote/backslash spelling fail generation naming the form and
  namespace (`bb lang-test`). The probe is `enumprobe`, not `enumtest`: a C#
  namespace ending in `test` goes to `Tests/` (D035).

- [x] **P0.7 — `bb` hygiene gates from day one.** `drift` (P0.1), `hosts`
  (P0.2), and a `bb gates` meta-task that runs every gate listed in this file
  and fails if a gate prints no score (nome's `bb scores` lesson, D818 there:
  a gate nobody runs is not a gate).
  *Done 2026-09-30.* `bb gates` (`kin/scripts/gates`, D057) runs `hosts drift
  lang-test check unit`; each must exit 0, print at least one `  ok ` line
  (its score) and no `FAIL`, and every `bb.edn` task must be in the list or in
  the named non-gates (`gen emit gates`), so a gate cannot be added and
  forgotten. Learnt: `bb check` runs `lang-test` itself, so it scores twice;
  harmless, and cheaper than teaching the gate list who runs whom. Fixed in
  P1.9 (D070): the task-list check printed spurious `gate X is not a bb.edn
  task` lines (newline-separated names tested with space-delimited patterns),
  so `bb gates` is now a trustworthy single green or red. Commit: pending (D037).

- [x] **P0.8 — README-free by design; SPEC and DEVLOG are the docs.** Confirm
  nothing else is needed for a newcomer to run `bb check`; if something is,
  it goes in SPEC §15, not in a new file.
  *Done 2026-09-30.* What a newcomer needs is now SPEC 15.1 (tools, sibling
  `../kin`, which toolchains are optional and skipped loudly, Python for
  tooling only); nothing else was missing except that `git` is required by
  `drift` and `python3` by `unit`. No README was added. Commit: pending (D037).

---

## Phase 1 — The first vertical slice

**Scope, and what is out of it.** A born-digital PDF with: a classic xref
table (or an xref stream — nome writes classic; many producers write
streams), Flate-compressed or uncompressed content, simple fonts (Type 1 /
TrueType with standard encodings or `Differences`) **and** Type0/Identity-H
with `ToUnicode` (because HTML printed to PDF by Chromium — the round-trip corpus's producer,
D041 — is exactly that), single column, text only. Output: a Model with `document`,
`paragraph`, `text`, `break` nodes and the `pages` table → JSON and HTML, on
the **Rust host only** (the core still generates and compiles for all four).
No headings, lists, tables, OCR, raster, encryption, repair, or reading-order
analysis beyond "top-to-bottom lines on a single column".

- [x] **P1.1 — The Model in kin** (`kin/model.kin`, `emon.model`; SPEC §3.8).
  *Brief*: node kinds via `defenum` (all of SPEC §3.2 now, even if unused —
  codes are forever); the arenas (`nodes` stride 16, `attrs`, `regions`,
  `text`, `pages`, `ledger`) as field-offset constant functions; builder
  functions (`model-init`, `node-new kind parent` appending as last child,
  `node-add-region`, `node-set-conf`, `node-add-src`, `node-set-attr-int`,
  `node-set-attr-text`, `text-append-cp`, `page-new`, `ledger-add`) and
  accessors (`node-kind`, `node-parent`, `node-first-child`, `node-next`, …,
  `node-text-start/len`, `region-*`). The host allocates the arenas and passes
  them in (a `Model` struct in the host holds six `Cells`).
  *Done when*: `kin/modeltest.kin` laws in `bb unit` (build a 3-level tree,
  walk it, check parent/sibling links, region chaining, attr lookup on every
  target); `bb check` probe hashes a built model identically everywhere.
  *Done 2026-09-30.* `kin/model.kin` (`emon.model`): 25 kinds and 13 more
  `defenum` tables (D050), six arenas as field-offset constants and
  accessors, builders (`model-init`, `node-new`, `node-set-*`, `node-add-src`,
  `node-add-flag`, `node-add-region`, `node-set-attr-int|text|node|bool`,
  `node-add-attr-*` for repeated keys, `text-append-cp`, `text-append-utf8`,
  `page-new` + setters, `ledger-add` + `ledger-set-text`), `model-check`
  (strides, links, child chains, runs, conf, pre-order ids, pages, ledger,
  answering a code and the node), and `model-hash` (FNV-1a over all six
  arenas). `kin/modeltest.kin`: 3-level tree walked in every direction, region
  chaining, attribute set/replace/repeat, text arena, WHATWG UTF-8 decoding,
  pages and ledger, every way `model-check` can fail, and a fingerprint worked
  out in Python from the hand-derived arenas. `bb check` probe
  (`kin/modelprobe.kin`): all four targets print the same fingerprint and the
  same JSON. The `Model` struct of six `Cells` is host work and arrives with
  P1.10. Learnt: no forward references (every helper before its caller);
  `len` is refused as a name by Go; a read nested in a call that also takes the
  same `Cells` does not compile in Rust (bind it first). Commit: pending (D037).

- [x] **P1.2 — Canonical JSON** (`kin/json.kin`, `emon.json`; SPEC §4).
  *Brief*: serialize a Model to a `Text` sink following §4.2/§4.3 exactly:
  fixed key order per kind, omission of absent fields, string escaping
  (including U+2028/2029), centipoint → decimal-point formatting (`7250` →
  `72.5`, `-5` → `-0.05`, `0` → `0`), leaf `text` and conditional `inlines`,
  `pretty` mode. Write `schema/emon-1.json` (JSON Schema 2020-12) for the
  §3.2 kinds. Golden files: `test/json/*.model.edn` (a small EDN description
  of a Model built through the host) → `*.json` expected.
  *Done when*: goldens byte-equal on Rust (and on every target via
  `bb check`'s probe); the output validates against the schema with a
  standard validator (a Python `jsonschema` or `ajv` call in the gate script
  is fine — tooling, not runtime).
  *Done 2026-09-30.* `kin/json.kin` (`emon.json`, `js-emit t pretty nodes
  attrs regions text pages ledger`) writes SPEC 4.2/4.3 (D051-D053):
  byte-identical on all four targets for three goldens in compact and pretty
  form (`test/json/{minimal,sample,kinds}.*`: `sample` is SPEC 4.2's example;
  `kinds` has every node kind and field, every escape, the extreme
  centipoints, inline and block children, furniture and a ledger), plus laws
  for centipoint printing, string escaping and a chain of 199 nested sections.
  `schema/emon-1.json` (D055) and `kin/scripts/json-check.py` (D054): an
  independent Python serializer reproduces every golden and every golden
  validates against the schema (`jsonschema`, draft 2020-12). Goldens are line
  scripts rather than EDN (D054). Learnt: SPEC's figure/seal `kind` collided
  with the node `kind` (D053); recursion was written self-recursive only (one function
  writes both inline and block nodes) — unnecessary, `declarefn` allows mutual recursion; an `I32` predicate cannot be an `if`
  condition, it must be compared. Commit: pending (D037).

- [x] **P1.3 — The evidence schema** (`kin/evidence.kin`, `emon.evidence`;
  SPEC §2.1, §3.8).
  *Brief*: arenas for `glyphs`, `words`, `lines`, `paths`, `images`,
  `components`, `ocrwords` with **all** the fields SPEC §8 lists, including
  OCR's (confidence, symbol alternatives head) and the tracer's, even though
  only the text layer fills them in Phase 1 — this is ordering principle 3's
  payment. Plus the `emon-evidence` debug serializer (§4.5).
  *Done when*: laws for builders/accessors; the evidence dump of a
  hand-built page is a golden file.
  *Done 2026-09-30.* `kin/evidence.kin` (`emon.evidence`, D056): nine arenas
  with every field SPEC 8 lists, OCR's and the tracer's included, builders
  (`gl-new`, `wd-new`, `ln-new`, `pa-new`, `im-new`, `co-new`, `ow-new`,
  `alt-add`), accessors (`*-get`, `*-set` with `gf-` .. `af-` field constants),
  `ev-check` and the `emon-evidence` dump (`ev-emit`, compact and pretty).
  `kin/evidencetest.kin`: defaults, setters, the alternative chain, every
  `ev-check` code, and the dump of a hand-built page (`test/evidence/page1.*`,
  the pretty file being derived from the compact one by `json-check.py`'s own
  dumper). Not modelled yet: contour polygons, annotations, the structure-tree
  mapping (D056). Commit: pending (D037).

- [x] **P1.11 — The `Src` port** (D058 revised, SPEC §13.1; take BEFORE
  P1.4/P1.5 close).
  *Done (P1.11)*: tag `Src` in `emon.lang` (Rust `&crate::port::Src`, a shared
  borrow over an `Arc<dyn SrcBacking>` and an atomic missed flag; Go `*port.Src`;
  Java `emon.Port.Src`; C# `emon.Port.Src`; Go `:zero nil`). Forms `src-len`,
  `src-u8`, `src-missed` (a `Bool`), `src-slice`, `src-of-cells`, `src-of-blob`,
  `src-hint` in all four `host/*/port.*`, with an interface the chunk cache
  implements (`SrcBacking`: `len`, `at`, `span`, `hint`; a plain in-memory one is
  built in) and `Src::view()`, a fresh view of the same backing, for the engine's
  re-run (P1.10). Test source in all four `host/*/testport.*`: `test-src-chunked
  blob chunk sync`, `test-src-view`, `test-src-ctl` (fetch with doubling
  read-ahead, make resident, evict, pending, counts, hints). `kin/porttest.kin`
  exercises the in-memory forms in `bb check` (one answer on four targets);
  `kin/srctest.kin` (9 laws) exercises every form, a miss included: a miss
  answers 0 and sets a sticky per-view flag, a span over a missing chunk is zero
  there, the cache grows between attempts, a fresh view starts clean, a sync
  source never misses, named chunks can be evicted, read-ahead doubles, hints
  are accepted. The engine's own backing (P1.10) implements `SrcBacking`. D074.
  *Brief*: tag `Src` in `emon.lang` (four `:types`, Go
  `:zero`); forms `src-len`, `src-u8`, `src-missed`, `src-slice` (span →
  `Blob` snapshot), `src-of-cells`, `src-of-blob`, `src-hint` in all four
  `host/*/port.*`, over a per-input chunk cache (64 KiB) with a per-unit
  view; a sync backing fills misses inline, an async one records them.
  Test port: a `Src` over a test file that misses a named chunk set.
  *Files*: `kin/src/emon/lang.cljc`, `host/*/…/port.*`, `host/*/…/testport.*`,
  `kin/porttest.kin`. *Done when*: `bb hosts` green; `porttest` exercises
  every form, a miss included, with one answer on four targets.

- [x] **P1.4 — PDF lexer and object parser** (`kin/pdflex.kin`, with
  `kin/pdfsrc.kin` and `kin/pdfnames.kin`; `pdfobj` is part of `pdflex`, D059).
  *Done (P1.4)*: `test/pdf/objects.txt` (199 rows: numbers, names, strings,
  containers, references, indirect objects, streams with every length case,
  nesting limits, damaged input) and `test/pdf/iso32000-extract.txt` (the standard's
  own worked examples, expectations written from the text before the parser ran)
  are read by kin on all four targets; the fuzz law cuts `minimal.pdf` at every
  length (a real shorter blob each time) and parses tokens and an indirect object at
  every line start; `bb unit` 94 laws on four targets, broken laws fail on all four.
  *Done (P1.11 retag)*: D058's checklist below, all nine steps (D074, D075):
  `ps-len`/`ps-u8` are `Src`; `ps-missed` and `ps-hint` are added, and `ps-slice`
  and `ps-of-cells` are the port forms `src-slice` and `src-of-cells`, not
  functions (Rust cannot return a borrow of what a function made; D074); every
  `^Blob src` in pdflex/pdfdoc is `^Src`; object streams parse through
  `src-of-cells` and filter input is cut by `src-slice`; `need-bytes` is a status
  of `pd`, `pl` and `pf`; checkpoints and the check-before-classify rule (D075);
  no state outside the caller's Cells; object extents are hinted once the xref is
  read; 18 new laws (srctest, and the async laws in `pdfdoctest`) incl. the
  512-byte async-source law over `test/pdf/corpus.txt`, identical to in-memory
  cell for cell. The real-file results are unchanged: all 3,689 PDFs under
  `corpus/` give the same status, page count, digest and warning bits as before
  (status counts 3672 ok, 12 encrypted, 3 bad-xref, 1 no-startxref, 1 no-pages).
  *Input (D058 revised) — checklist, in order, for `pdfsrc`/`pdflex`/`pdfdoc`
  (all done; kept for the record)*:
  1. After P1.11: in `pdfsrc.kin` retag `ps-len`/`ps-u8` to `^Src`; add
     `ps-missed`, `ps-slice src s e` (→ `Blob`), `ps-of-cells`, `ps-hint`;
     cite D058 (not D052) in its header.
  2. Retag every `^Blob src` in `pdflex.kin` and `pdfdoc.kin` that is the
     file or object-stream data to `^Src` (mechanical; `pdffilter`,
     `inflate`, `bytes` stay `Blob`).
  3. `pdfdoc`: object-stream data goes to `pdflex` via `ps-of-cells`, not
     `cells-bytes`; stream spans go to `pf-run` as `ps-slice` (a `Blob`
     indexed 0..len), not the file with `s`/`e`.
  4. Add `need-bytes` to `pd-status` (and `pl-status` if the lexer answers
     statuses to callers that must stop). Checkpoint `ps-missed` after each
     `pl-parse-indirect`, each xref table/stream section, the trailer, each
     stream span and each page-tree node; on a miss return `need-bytes` to
     the top without further work.
  5. Check `ps-missed` BEFORE classifying a failure (`bad-xref`, a warning
     bit, a tolerated repair): garbage from a miss must never become a
     status or warning.
  6. No state outside the caller's `Cells`; never mutate `Cells` passed in
     as another unit's result (a page unit copies intake's arena first).
  7. After the xref is read, `ps-hint` each object's extent (offset to the
     next-larger offset) — may follow the slice as an optimization.
  8. Tests: build sources with `src-of-blob`; add laws that a missing chunk
     yields `need-bytes` (never a crash, status or warning) and that the
     result is identical once the chunk is resident.
  *Brief*: ISO 32000-1 §7.2–7.3 tokens and objects into an **object arena**
  (kind, value/offset/len, first-child/next for arrays and dicts, name
  interning into a names arena); indirect references; streams with `/Length`
  (direct or indirect — and tolerant of a wrong `/Length` by scanning for
  `endstream`, since producers get it wrong). Vendor ISO 32000-1 extracts the
  way nome did, as emon's own `test/pdf/iso32000-extract.txt` (extracted
  from the specification here, not copied from nome). Every
  malformed input a named error (`^:throws`), bounded by limits.
  *Done when*: a row corpus of object snippets → object-arena dumps, golden,
  on every target (nome's row-driver idea: input, expectation, one line
  each); fuzz-lite: 1,000 truncations of a fixture never panic (Rust host).

- [x] **P1.5 — xref, trailer, page tree, filters** (`kin/pdfdoc.kin`,
  `kin/pdffilter.kin`).
  *Done (P1.5)*: Source goldens `test/pdf/source/{minimal,html-chrome,gs-xrefstream,
  inherit,boxes}.json` (a Chrome/Skia file, a hand-written minimal file, a Ghostscript
  xref-stream file, inheritance, odd boxes) equal on four targets; 24 fixtures
  (`test/pdf/fixtures.txt`), 53 object/stream resolution rows, 72 filter rows, 105
  real files from `corpus/` cross-checked against poppler, truncation of five fixtures
  at every byte and 2,400 random corruptions (SplitMix) never fault. **Real files**: of
  the 4,000 PDFs under `corpus/`, 3,976 open with the page count AND every page's
  MediaBox, CropBox and rotation (whole points) equal to `pdfinfo`'s; 12 are encrypted and
  unreadable without decryption (status `encrypted`, P3.2), 3 have a `startxref` that
  does not lead to a section and 1 a wrong `/Prev` (P3.1 repair; poppler rebuilds them);
  none fault. Of ~125,600 streams in them 119,759 decode `ok`, 5,586 are image codecs
  (declined) and the rest are damaged or encrypted (15 in unencrypted files: truncated,
  a wrong checksum, a double `FlateDecode` over 12 bytes, garbage headers). 190 real
  LZW/ASCII85/ASCIIHex/RunLength/Flate streams and 47 PNG-predictor streams are
  byte-identical to independent Python decoders. Opening all 4,000 takes 64 ms of
  parsing in release. D058's chunked-source law is done (P1.11): all fixtures, the
  five `Source` goldens, the object/stream rows and every committed real-file row
  equal the in-memory run through a 512-byte-chunk (64 for the tiny fixtures)
  async test source.
  *Input (D058 revised)*: P1.4's checklist covers `pdfdoc` too (steps 2–7): done.
  *Done when* also: the three fixtures give the same `Source` JSON from a
  512-byte-chunk async test source as from memory: met.
  *Brief*: `startxref` → classic xref table *and* xref streams (with
  `/W`, `/Index`, predictors) → object lookup (with object streams
  `/ObjStm`); trailer → catalog → page tree with inherited attributes
  (ISO 32000-1 §7.7.3.4's inheritable attributes); `MediaBox`/
  `CropBox`/`Rotate`; filters `FlateDecode` (P0.4's inflate) + PNG
  predictors, `ASCIIHexDecode`, `ASCII85Decode`; the Source value (SPEC §2.0)
  for these files. Incremental updates: follow `/Prev`.
  *Done when*: `Source` for three fixtures (an HTML-printed PDF, a
  hand-written minimal PDF, one xref-stream PDF from a mainstream producer —
  licence-clean) is a golden JSON on every target.

- [x] **P1.6 — Fonts and encodings, first cut** (`kin/pdffont.kin`,
  `kin/agl.kin`, `kin/encodings.kin`).
  *Done (P1.6)*: `emon.pdffont` (one font is one `Cells`; `pfn-load` builds it from a
  font dictionary through the document layer; per code `pfn-map`, `pfn-width`,
  `pfn-cid`, `pfn-code-len`, D080-D084), the AGL and the encodings generated from
  Adobe's own files by `kin/scripts/fontdata.py` (D081), CMaps parsed into sorted range
  tables (D082). Laws on every target (102 in all, the same count in each arm): 168
  hand-built font rows (`test/fonts/fonts.pdf`: all 14 standard fonts' routes, every
  glyph-name form, ToUnicode both ways, Identity-H/V, embedded encoding CMaps, Uni*
  CMaps, Type 3, symbolic guesses, damaged fonts), 55 CMap rows with the bfrange
  edge cases, all 4,482 AGL names plus the `uni`/`u`/underscore/suffix cases (checked
  against pdfminer.six's `name2unicode`: 0 disagreements on 4,345 names), 160 fonts of
  real files (`test/fonts/real.txt`, a ratchet), a 3,000-entry reverse-order table, a
  CMap over its limit, and a CMap cut at every byte.
  **Agreement with pdfminer.six on real files** (the codes the pages actually SHOW,
  read from their content streams by pypdf; `kin/scripts/font-corpus.py`): over 1,576
  PDFs, 8,490 fonts and 10.6M shown characters, **99.92% of shown characters
  (99.71% of distinct codes) decode to the same code points**; of the values emon
  is sure of (`how` is not `unknown`) **99.99% (99.97% of distinct codes)**. Widths agree
  to 0.001 em on 99.98% of the shown characters both know. Every remaining
  disagreement is one of: a symbolic Type 1 font whose built-in encoding is in the
  font program (emon says `unknown`, D084); a Type 3 font's own glyph names (emon
  says nothing, pdfminer reads them as ASCII); a ToUnicode entry of `<0000>` (emon
  falls back to the encoding, pdfminer emits NUL or U+FFFD); WinAnsi 0xAD (emon: hyphen, pdfminer:
  space). Zero faults in the generated Rust with overflow and bounds checks on.
  NOT done: font programs (embedded Type 1/CFF/TrueType cmaps and built-in
  encodings), the predefined CID CMaps and Adobe CID-to-Unicode tables, MacExpert, `/W2`.
  *Input for P1.7*: per `Tf` build a font with `(pfn-init f)` + `(pfn-load src ar dc
  a b st f out fh)` (a font is reused for the whole document; `pfn-width` is in
  hundredths of a thousandth of an em, -1 for unknown; `pfn-code-len` splits a string
  into codes from the next four bytes; `pfn-ascent`/`pfn-descent` are 1/1000 em;
  `pfn-how-of` is SPEC 8.1's `gh` code). `pfn-load` takes the file as `^Blob src` only
  to hand it to `emon.pdfdoc`: the D058 retag is that one word in its signature (and in
  `kin/scripts/font-sweep`'s harness, as in `pdf-sweep`'s).
  *Brief*: simple fonts (StandardEncoding, WinAnsi, MacRoman,
  `Differences`), glyph-name → Unicode via the Adobe Glyph List (generated
  `defdata` table from AGL + `uniXXXX`/`uXXXXX` rules), `ToUnicode` CMap
  parsing (`bfchar`, `bfrange` incl. array form, codespace ranges), Type0 with
  Identity-H and ToUnicode, `/Widths`, `/W` arrays, `/FontDescriptor` ascent/
  descent. Record per glyph *how* its Unicode was obtained (SPEC §8.1).
  *Done when*: a font corpus (the fixtures' fonts) produces the expected code
  points for every glyph; laws for CMap range edge cases.

- [ ] **P1.7 — Content-stream interpreter, text subset**
  (`kin/pdfcontent.kin`, `kin/fx.kin`).
  *Brief*: operators `q Q cm BT ET Tf Tm Td TD T* TL Tc Tw Tz Ts Tr Tj TJ ' "`,
  `Do` for Form XObjects (recursive, depth-limited), `BDC/BMC/EMC` — record
  every marked-content span with its tag, `/MCID`, `/Artifact` and its
  `/Type`/`/Subtype` (Pagination/Header/Footer/Watermark/Background/Layout),
  `/ActualText`, `/Alt`, `/E` and `/Lang` (property lists inline or via
  `/Properties`), so P3.7 is a tree-reader + join only (D076) — and skip-with-balance for everything else. `Fx` 16.16 matrix
  arithmetic in `I64` (`fx.kin`); page space conversion to centipoints,
  top-left origin, `/Rotate` applied (SPEC §3.4). Glyph boxes from widths and
  font ascent/descent. Fill the evidence `glyphs` arena; group into `words`
  (gap vs space width and `TJ` offsets) and `lines` (baseline + direction).
  *Done when*: evidence dumps of the three fixtures are goldens on every
  target; for the HTML-printed fixture, every glyph's code point matches the
  source HTML's text (a script can check this directly — the HTML is the
  truth).

- [ ] **P1.8 — Naive analysis: lines → paragraphs** (`kin/para.kin`).
  *Brief*: single-column assumption; lines in top-to-bottom order; a new
  paragraph on a vertical gap larger than 1.5× the median line gap or on an
  indent change; `break` milestones at page boundaries (inline when a
  paragraph continues across the break); `text` normalization per SPEC §3.6
  using P0.4's `normalize` (NFC + ligature decomposition, soft hyphen
  removal; no dehyphenation). Every node gets `at`, `src = {text, infer}` and
  a placeholder `conf` (1000 for text-layer glyph content, 500 for inferred
  structure — calibration is Phase 10).
  *Done when*: the HTML-printed fixture's paragraphs equal the source HTML's `<p>`
  texts (script compares); the JSON is a golden.

- [x] **P1.9 — HTML emitter** (`kin/html.kin`, `emon.html`; SPEC §5).
  *Brief*: the §5.1 mapping for the kinds that exist (and stubs for the rest
  that emit a comment-free placeholder, not nothing), escaping per §5.2, the
  `geometry`/`ids` options.
  *Done when*: HTML goldens for the fixtures; the output parses cleanly
  with html5lib (zero parse errors — tooling in the gate script).
  *Done 2026-09-30.* `kin/html.kin` (`emon.html`, `html-emit t opts conf-below
  nodes attrs regions text ledger`) writes SPEC 5 (D067-D069): byte-identical on
  all four targets for five Models (`test/json/{minimal,sample,kinds,probe,
  corners}.model`, the JSON goldens' own scripts, plus `corners`: every
  escape, fallback and branch `kinds` does not reach) under four option sets
  each (defaults compact, `pretty`, `full` = ids + geometry + confidence +
  ledger, `bare` = furniture dropped + milestones off): 20 goldens under
  `test/html/`, plus laws for the empty document, heading levels, escapes,
  the options, and the 199-deep tree. `kin/scripts/html-check.py` (run first
  by `bb unit`) is an independent Python renderer that reads the JSON goldens,
  reproduces every HTML golden, parses each with html5lib in STRICT mode (zero
  parse errors) and matches its element census and heading/paragraph/code
  texts to the JSON's. Learnt: `&#13;` is itself a parse error (CR stays raw);
  a `<code>` between `<pre>` and its text is why a leading newline survives;
  the script parser moved from `emon.jsontest` to `emon.mscript` because a
  test namespace cannot be imported in Go; a `deftest` helper can assert now
  (D070). Commit: pending (D037).

- [ ] **P1.10 — Rust host: Engine API and a driver.**
  *Brief*: `host/rust/src/{emon.rs,port.rs}`: `Engine::new(Config)`,
  `intake`, `convert` (plan/fulfil loop present but with zero requests in this
  slice), `Model` wrapper with the §3.9 accessors, `to_json`, `to_html`. A
  test-only CLI `test/driver/rust` (`driver --out=json|html|evidence
  <file>`), whose stdout is the only thing gates read — nome's driver shape.
  `bb slice` runs the three fixtures through the driver and compares goldens.
  **Inputs are byte sources, never paths** (D058, SPEC §13.1): add
  `host/rust/src/source.rs` with `trait ByteSource { len, read_at }` and
  adapters for `&[u8]`/`Vec<u8>`, `Read + Seek` and `Read` (spooled);
  `Input { source, password: Option<..> }`; the Engine takes one `Input` or a
  list and gives kin a `Src` per input (P1.11; input-bytes limit checked
  against `len()` first; a short or changed read is `source-changed`; the host
  half of a `Src` exists since P1.11: implement `SrcBacking` over the chunk cache
  and make each attempt's `Src` with `Src::view()`). The
  engine loop runs each unit, and on `need-bytes` fetches the missed ranges
  (doubling read-ahead, coalesced, `FETCH_ROUNDS`) and re-runs it (D058
  revised) — with an async-simulating source in Rust, since Rust's real
  sources are sync. Nothing under `host/rust/src/` may name `std::fs` or
  `Path`; the driver (test-only) reads the file and passes bytes, and takes
  `--chunk=N --async` to run through the simulated async source.
  *Done when*: `bb slice` green both in memory and with `--chunk=512
  --async` (identical JSON/HTML; rounds and bytes fetched printed to stderr),
  and a law feeds one fixture through the bytes, `Read + Seek` and
  spooled-`Read` adapters with identical JSON.
  **This is the first vertical slice.** Record
  timings for the fixtures (baseline, not a target).

---

## Phase 2 — Second host, identity, measurement, spikes

- [ ] **P2.1 — Go host layer, thin**: ports (already there from P0.2),
  Engine/Model wrapper, driver; inputs as `io.ReaderAt` + size (D058).
  *Done when*: `bb slice` passes on Go too.
- [ ] **P2.2 — `bb identity`**: every driver arm over the corpus of (input,
  transcript) pairs; byte-identical JSON and HTML required (SPEC §14.1.5).
  Transcripts are empty in this phase; the format is fixed now (P2.5).
  Each arm also runs every input through a 512-byte-chunk async test
  source and must match its in-memory run (D058).
- [ ] **P2.3 — `bb embedders`**: compare the Rust and Go public API surfaces
  (names, arities) against SPEC §3.9/§13. nome learnt this late (its D568).
  Includes the `Input`/byte-source surface (§13.1) and a check that no host
  library file names a filesystem type (D058).
- [ ] **P2.4 — `bb repeat`**: run vs run, and pages submitted in reverse,
  byte-identical.
- [x] **P2.5 — The plan/fulfil protocol and transcript format** (D008, D089-D091):
  request/answer record layouts in `emon.plan` (render, decode, ocr; `bytes`
  issued by the D058 re-run loop, never transcribed, not counted in
  `max-rounds`; render/decode name the input
  by index), the
  loop in both hosts, transcript read/write (a directory: `transcript.edn` of
  records + lossless PNGs for bitmaps). Zero requests are issued by the
  slice; the protocol is exercised by a synthetic probe.
  *Done 2026-09-30.* `kin/plan.kin` (`emon.plan`): length-prefixed records
  `[len kind id args...]` / `[len kind id status args...]` for render, decode,
  ocr and bytes, builders and readers, unit statuses (`us-done`, `us-requests`,
  `us-need-bytes` = 7); `kin/planprobe.kin` (`pp-unit`: render + decode in round
  0, two OCRs in round 1, then evidence); `kin/plantest.kin` (10 laws; 141 per arm
  now). Host loops: `host/rust/src/plan.rs`, `host/go/port/plan.go` (`run` /
  `PlanRun`: `Fulfiller` start/next for sync and async-shaped fulfilment, a
  `Misses` hook for the D058 re-run, `Transcript`, `Replayer`, PNG and EDN codecs,
  named limits `max-rounds` and `fetch-rounds`); probe drivers `test/plan/{rust,go}`;
  `bb plan` (a gate, `kin/scripts/plan`, babashka): a transcript recorded on Rust
  replays on Go with a byte-identical evidence dump, bitmap hashes and transcript
  files; a recording is byte-identical sync or async on either host and across
  hosts; under a 512-byte async source the unit misses, the loop fetches (1
  round) and nothing of it reaches the transcript; `max-rounds` 1 stops both
  hosts by name; a changed request is `transcript-miss`, a missing PNG
  `transcript-bad`. Learnt: the kin-side units take `&mut Cells` in Rust even to
  read (the `CellsRo` item in "Found in kin"), so the loop hands the unit
  `&mut` answers; ids must come from what is asked, not a global counter.
  Stored-block PNGs only (8.7 MB per gray 300-dpi A4 page; a compressed writer
  is a P2.6 follow-up). Render and decode answers carry the port `Bitmap` (P2.7).
  Commit: pending (D037).
- [ ] **P2.6 — Spike: OCR through the transcript.** Rust host binds
  Tesseract (C API), records one page's OCR into a transcript; kin reads the
  answer into `ocrwords` evidence; the Go host replays it and produces the
  same evidence dump. *Answers*: is the OCR answer layout sufficient
  (symbols, alternatives, baselines)? Does replay make identity exact?
- [x] **P2.7 — Spike: per-pixel cost** (SPEC §12.3, D092). Binarise and label
  connected components of a 300-dpi A4 page in kin through `bitmap-u8`; a
  hand-written Rust comparator of the same algorithm; record the ratio in
  Rust, Go, C#. *Answers*: do we need bulk `Bitmap` forms, and which?
  **Answer (D092)**: the port is not the cost: Otsu, Sauvola over integral
  images and two-pass union-find labelling in kin are 0.7-1.3x the hand-written
  Rust (Go up to 1.8x on Sauvola) and all four targets agree exactly; only the
  whole-page streaming passes break 2x (threshold 18-24x vs a SIMD comparator,
  zero-fill 12-61x), so `bitmap-histogram`, `bitmap-threshold-into`,
  `bitmap-integral`, `bitmap-next-nonzero` and `cells-grow` are adopted and the
  tracer stays in kin. The `Bitmap` tag and forms are in all four host trees
  (`host/*/`), `kin/raster.kin` + `kin/rastertest.kin` (laws), harness in
  `bench/` (`bench/run.sh`, `bb bench/report.clj`; not in `bb gates`).
- [x] **P2.8 — Ground truth: the synthetic round trip** (D041, D071-D073, revising
  D032). An HTML corpus
  (start with ~30 documents covering headings to depth 4 with and without
  numbering, lists, simple tables, a multi-page table with repeated `thead`
  and one without, `@page` running headers and page numbers, footnotes,
  figures) printed to PDF at dev time by an external producer (headless
  Chromium first; others optional) and **committed** to
  `test/corpus/synth/` with the producer and version recorded — no gate
  invokes a producer; an HTML → gold-Model
  converter; the gold format defined (`schema/emon-gold-1.json`: a partial
  Model plus a declaration of what is annotated).
- [x] **P2.9 — `bb accuracy` and the scoreboard** (D023, D077-D079): metrics of SPEC
  §14.2 that the slice can already be scored on (CER/WER, block P/R,
  paragraph boundaries), `test/scoreboard.edn`, the ratchet rule. Built and
  proven on fixtures (oracle perfect, degraded worse); the real emon driver is a
  placeholder until P1.10, and `bb accuracy` is not in `bb gates` (D078).
  `tools/accuracy/`: `score.py`, `accuracy.py`, `predict-*`, `test_score.py`
  (run by `bb unit`).
- [ ] **P2.10 — Baselines against the field** (SPEC §14.4): pinned versions
  of 2–3 extractors, scored offline, committed as reference lines.
- [x] **P2.11 — Public datasets: licence review and importers** (D086-D088).
  Licences verified per source and recorded with what may be committed and what
  is (nothing, no LFS) in `test/corpus/DATASETS.md`; importers
  `tools/import/<dataset>.py` for FUNSD, XFUND, SROIE, CORD, DocLayNet, ICDAR
  2013, cTDaR, FinTabNet-OTSL, pdfa-eng-wds (silver only) and a stamp set, output
  under `corpus/_gold/` (outside git), all validating; `python3 tools/import/import_all.py` runs them (the `bb import` task is pending: adding `import` to `not_gates` in `kin/scripts/gates` was refused by the permission system, so `bb.edn` has no `import` task yet; not
  a gate). Seal set found: mapo80/stamps. Tobacco800 and PubTabNet not imported
  (login / not fetched).
- [ ] **P2.12 — `bb fuzz`, first cut**: truncation and byte-flip mutation of
  the PDF fixtures through both hosts; no panic, no hang, same error name;
  also under the 512-byte async source (a miss must never surface as an
  error, D058).

## Phase 3 — PDF breadth

- [ ] P3.1 — xref repair by scanning (`N G obj`), broken `startxref`, broken
  `/Length`; `bb fuzz` corpus grows with real damaged PDFs.
- [ ] P3.2 — Encryption: standard security handler R2–R6 in kin; `Crypto`
  port (MD5, RC4, AES-CBC, SHA-2) in both hosts; embedder password; the
  password handling and the distinct `password-required` /
  `password-incorrect` / `encryption-unsupported` errors; permission flags
  ignored (SPEC §16, D040).
- [ ] P3.3 — Remaining non-image filters: `LZWDecode` (early change),
  `RunLengthDecode`, TIFF predictor 2; `Crypt` filter identity.
- [ ] P3.4 — Fonts, the long tail: predefined CJK CMaps (as `defdata`),
  CID orderings → Unicode (Adobe-Japan1/GB1/CNS1/Korea1 tables), embedded font
  program glyph names (emon's own `cff`, `cmap`, a Type 1 eexec reader, TrueType
  `post`), Type 3 fonts, symbolic fonts, the "no mapping at all" path
  (flagged; OCR's problem later).
- [ ] P3.5 — Full graphics state: clipping paths (visibility), colour spaces
  (to classify ink colour), `ExtGState` alpha, render modes, paths recorded as
  `vector` evidence (lines, rects, fills), image placements (`Do` image,
  inline `BI…EI`), shadings as filled regions.
- [ ] P3.6 — Visibility classification (SPEC §8.1): clipped, off-page,
  white-on-white, covered-by-later-opaque-paint, invisible mode.
- [ ] P3.7 — Tagged PDF: structure tree, role map, MCID ↔ content mapping,
  `/Alt`, `/ActualText` (use it when present — it is the author's answer),
  informativeness score (D034). Also (D076): Artifact subtypes as
  furniture/watermark evidence; `H`/`H1`–`H6` as heading-level priors;
  `Table`/`TH`/`TD` `/Scope` `/Headers` `/RowSpan` `/ColSpan` `/Summary`;
  `L`/`LI`/`Lbl` + `/ListNumbering`; `Figure`/`Formula` `/Alt` → placeholder
  description; `Caption`, `Note`, `Reference`, `Link`; per-element `/Lang`;
  `/E` expansions; PDF/UA identification (XMP `pdfuaid:part`) as a trust
  signal. Test with synth pairs: correctly tagged vs deliberately mis-tagged.
- [ ] P3.8 — Annotations: links (URI, GoTo, named dests — ISO 32000-1
  §12.3.2 destination arrays), Watermark/Stamp annotations, widgets + AcroForm values,
  signature dictionaries.
- [ ] P3.9 — Outlines, page labels (`/PageLabels` number trees), Info + XMP
  metadata.
- [ ] P3.10 — Text-layer oracle (SPEC §14.5): per-character comparison
  against a pinned reference extractor on a born-digital corpus; committed
  expectations.

## Phase 4 — Layout and structure, on the text layer

- [ ] P4.1 — Blocks and reading order: XY-cut with spanning elements,
  multi-column, RTL (emon's own UAX #9 `bidi`), priors compared (SPEC §10.1).
- [ ] P4.2 — Style inventory and body style (SPEC §10.3.1).
- [ ] P4.3 — Running furniture: candidates, templates, decomposition,
  policy (D017); page labels from detected numbers.
- [ ] P4.4 — Headings: candidates, numbering schemes, priors (tagged, outline,
  TOC detection), global level assignment, sections (D018).
- [ ] P4.5 — Lists: markers, nesting, continuation across breaks.
- [ ] P4.6 — Footnotes/endnotes and references; captions; bare URLs.
- [ ] P4.7 — Inline styles (bold/italic from font flags, names and stem
  widths; sub/sup from baseline shift; mono; small caps), links onto words.
- [ ] P4.8 — Locale: keyword tables (start with en, de, fr, es, pt, it, nl,
  zh, ja), dehyphenation with emon's own Liang `hyphen` (D021, D029).
- [ ] P4.9 — Code blocks, quotes, formulas (placeholder by font/glyph
  signals), form fields into the flow.

## Phase 5 — Tables

- [ ] P5.1 — `ruled` profile from vector rules: lattice, cells, spans.
- [ ] P5.2 — `partial`, `shaded` and `whitespace` profiles; profile scoring.
- [ ] P5.3 — Header rows, row groups, numeric columns, captions.
- [ ] P5.4 — Tables across pages: with and without recurring headers,
  "continued" markers, split rows (D019).
- [ ] P5.5 — `struct` (tagged) tables verified against geometry; `form`
  profile.
- [ ] P5.6 — TEDS in `bb accuracy`; PubTabNet/FinTabNet/ICDAR scored.

## Phase 6 — Raster, tracer, OCR

- [ ] P6.1 — `Bitmap` port + `ImageDecode` port (Rust: `image`/`tiff`
  crates + a JBIG2/CCITT decoder; Go: stdlib + x/image) under SPEC §7.2's
  contract; raster intake for PNG/JPEG/TIFF/multi-page TIFF; image sets as
  several inputs, mixable with PDFs (D058, SPEC §2.0); codecs read the
  host's byte source (forward or by offset, awaited in async hosts), and kin
  only sniffs the first chunk.
- [ ] P6.2 — `Renderer` port (pdfium in Rust and Go), with layer masks where
  available (D007); pdfium loads through its custom-loader callback over the
  byte source, not a path or a whole-file copy (D058).
- [ ] P6.3 — Normalization: grayscale, ink layers, Sauvola, deskew,
  orientation (SPEC §8.2.1).
- [ ] P6.4 — Tracer: components, contours, rules, primitives (SPEC §8.2.2).
- [ ] P6.5 — `Ocr` port with Tesseract in Rust and Go; capabilities; the
  first-pass plan; OCR-only pipeline for images and scanned PDFs (D033).
- [ ] P6.6 — Degraded corpus from the synthetic round trip (SPEC §14.3.1);
  scanned-document baselines in the scoreboard.

## Phase 7 — Fusion and artifacts

- [ ] P7.1 — Alignment and correspondence groups (SPEC §9.2).
- [ ] P7.2 — Health scores per source and region (SPEC §9.3).
- [ ] P7.3 — Arbitration rules as data; explanation in the evidence dump
  (SPEC §9.4, D014).
- [ ] P7.4 — Targeted re-OCR rounds (text-only renders, body-ink layer,
  single-line cells).
- [ ] P7.5 — Watermarks, backgrounds, bleed-through, ghosts: the six
  defences of SPEC §9.5 (D015); contamination rate in the scoreboard.
- [ ] P7.6 — `hidden-text` handling (prior OCR layers).

## Phase 8 — Figures, seals, signatures

- [ ] P8.1 — Figure classification and placeholders (SPEC §10.6, D020).
- [ ] P8.2 — Seals and stamps: detection, polar unwarp, targeted OCR, name
  and date extraction (D016).
- [ ] P8.3 — Handwritten signatures and initials; digital signatures (from
  P3.8) into `seal` nodes.
- [ ] P8.4 — Logos, including recurrence across pages (and their removal from
  furniture templates).

## Phase 9 — The remaining host layers and bindings

- [ ] P9.1 — Java host (ports, Engine, Tesseract binding, pdfium binding).
- [ ] P9.2 — C# host.
- [ ] P9.3 — WASM binding, JS SDK, tesseract.js through plan/fulfil.
  Inputs (D058, SPEC §13.1): JS byte sources `{size, read(off, len)}` with
  adapters for `ArrayBuffer`/`Uint8Array`, `Blob`/`File`, URL (`fetch` +
  `Range`, validator pinned, `source-changed` on a change) and
  `ReadableStream` (spooled), and any `{size, read}` object; kin reads a
  `Src` over them and the JS side owns the await-and-re-run loop (D058
  revised) — no whole-file read. *Done when*: the slice fixtures give
  identical JSON from each adapter in a headless browser, a URL fixture
  fetches measurably less than its length for a one-page conversion, and
  the wasm build links no filesystem import (`wasm32-unknown-unknown`).
- [ ] P9.4 — C binding; babashka pod.
- [ ] P9.5 — `bb identity` over all four hosts and three bindings.
- [x] ~~P9.6 — Ranged inputs, optional~~ — **promoted into the main design**
  by D058's revision (maintainer: lazy range reads are required): now
  P1.11 (the `Src` port), P1.4/P1.5's checklist, P1.10's re-run loop, P2.2's
  async-source arm and P9.3's JS sources.

## Phase 10 — Calibration, performance, hardening

- [ ] P10.1 — Confidence calibration per kind; reliability diagrams (SPEC §9.6).
- [ ] P10.2 — Parallel runners in the hosts; `bb repeat` serial vs parallel;
  default stays serial until a benchmark on a real document says otherwise
  (nome §6).
- [ ] P10.3 — Performance: comparators per bet, the 2× rule.
- [ ] P10.4 — Limits: every bound wired, configurable, named alike in four
  hosts (nome's `bb limits`).
- [ ] P10.5 — Fuzz at scale, cross-host error agreement.

---

## Decisions

Append-only. Each entry says what was decided, why, and what else was
considered, so a later reader does not reopen it by accident.

**Every one of these was taken autonomously during the founding design pass
(2026-09-30), and the sign-off box is the maintainer's.** An unticked box is
not a complaint — it is a decision that has not been read yet. Sign-offs do
**not** block work. Rejecting one means striking it through with a line
saying what replaces it, so the reasoning survives the reversal.

The four most expensive to reverse, if you read nothing else: **D008**
(plan/fulfil and transcripts — every host port and the whole identity story
hang on it), **D011** (the Model's shape and arena representation), **D012**
(JSON as the serialization and gate subject), and **D003/D004** (the kin/host
line, which decides that the PDF parser is ours).

### D001 — Targets are Rust, Go, Java, C#; JS/C/Clojure are bindings over Rust
**Decision**: same four targets as nome; WASM, C and a babashka pod wrap the
Rust build and add no kin arm.
**Why**: the maintainer asked for nome's targets; a binding costs nothing per
feature, a target costs vocabulary arms once.
**Alternatives**: a JS target in kin (rejected: the WASM build is the same
code, and a fifth arm is a fifth thing to keep identical).
- [ ] **Signed off** (maintainer)

### D002 — Rust is the reference target; host layers arrive Rust → Go → Java/C#
**Decision**: Rust host first; Go host second and immediately gated by
identity; Java and C# after the core algorithms exist.
**Why**: nome's ordering principle, for nome's reasons — Go is the different
one, so surviving Go proves the arrangement. Rust also has the easiest
Tesseract and pdfium bindings, which the first spikes need.
**Alternatives**: Go first (its cgo Tesseract binding, gosseract, is mature —
but Rust is what the bindings build from); all four at once (rejected: host
layers are the expensive part and the first slice would take four times as
long to reach).
- [ ] **Signed off** (maintainer)

### D003 — kin code never names a library type
**Decision**: nome's line, unchanged. Every external capability is an
emon-owned abstraction with a kin tag, implemented per host.
**Why**: it is what makes four identical cores possible at all.
**Alternatives**: none seriously; restated so it is signed.
- [ ] **Signed off** (maintainer)

### D004 — The PDF parser, fonts and content-stream interpreter are kin
**Decision**: everything that turns PDF bytes into text-layer evidence is
written in kin: lexer, objects, xref (and repair), streams, page tree,
content streams, fonts, encodings, CMaps, AGL, structure tree, annotations.
**Why**: the text layer is the most accurate source emon has, and its
interpretation *is* the product — glyph positions, encodings and visibility
decide what a word is. Delegating it to pdfium/pdfbox/PdfPig per host would
give four different text layers and defeat the identity gate; nome already
has much of the substrate (inflate, cff, cmap, pdfpage).
**Alternatives**: a host `PdfParser` port (rejected for the reason above; a
parser's *choices* — word gaps, broken-encoding fallbacks — are exactly what
must be identical); Rust-only parser exposed to other hosts via C ABI
(rejected: breaks "one source, four targets" and pulls a native library into
Java/C#/Go builds).
- [ ] **Signed off** (maintainer)

### D005 — Non-image stream filters in kin; image codecs through `ImageDecode`
**Decision**: Flate, LZW, ASCII85, ASCIIHex, RunLength and predictors in kin
(Flate from nome); DCT, JPX, JBIG2 and CCITTFax go to the host's
`ImageDecode` port.
**Why**: content streams and fonts need the first group to be *exactly*
right and they are small; the second group are image codecs the maintainer
assigned to the host, and their output only feeds pixel analysis, whose
cross-host variance transcripts absorb (D008).
**Alternatives**: all filters host-side (rejected: text extraction would
depend on the host); CCITT/JBIG2 in kin because bilevel decoding is exact
(deferred — attractive later, not needed for correctness now).
- [ ] **Signed off** (maintainer)

### D006 — Encryption: handler logic in kin, primitives through `Crypto`
**Decision**: the standard security handler (R2–R6 key derivation,
per-object keys) is kin; MD5, RC4, AES-CBC and SHA-2 are a host port.
**Why**: primitives are in every standard library and are pure functions; the
handler logic is where PDF-specific bugs live and should exist once.
**Alternatives**: primitives in kin (possible — MD5/RC4 are tiny, AES is not
big — but the not-worse rule would likely keep AES hand-written anyway);
refusing encrypted PDFs (rejected: owner-password-only PDFs are common and
readable by everyone).
- [ ] **Signed off** (maintainer)

### D007 — Page rendering is a host `Renderer` port; a kin renderer is parked
**Decision**: rasterising a PDF page for OCR/tracing uses a host engine
(pdfium by default), with layer masks (with/without/only text) where
available.
**Why**: a correct PDF painter (shadings, blend modes, transparency groups,
Type 3, every image codec) is pdfium-sized. Rendered pixels only feed OCR and
the tracer, whose cross-host variance is captured by transcripts.
**Alternatives**: an emon-written kin renderer (nome's rasteriser as
reference only, D039) (deterministic across hosts, no native
dependency — genuinely attractive; parked as SPEC §17.2 until Phase 6
measurements say whether host render variance matters to accuracy).
- [ ] **Signed off** (maintainer)

### D008 — Plan/fulfil: kin never calls an expensive port; answers are transcripts
**Decision**: `Renderer`, `Ocr` and `ImageDecode` are never called from kin.
kin emits requests; the host fulfils them (concurrently, asynchronously,
however); answers go back as integer records; the full answer set for a run
is a **transcript**; (input, transcript) → Model is a pure function.
**Why**: gives the host concurrency (the maintainer's "run concurrently"),
makes WASM/async hosts possible (nome §5.3 rule 8 found this the hard way),
makes the cross-target identity gate test emon and not whichever Tesseract is
installed, and makes second-opinion OCR rounds natural.
**Alternatives**: synchronous port calls from kin as nome's `resource-get`
(rejected: blocks, cannot be async, and identity would need identical OCR
builds on four platforms); host runs all OCR up front with no kin planning
(rejected: loses targeted re-OCR, which is where fusion earns its accuracy).
- [ ] **Signed off** (maintainer)

### D009 — Geometry: centipoints, top-left origin, no floats
**Decision**: page space is top-left, y-down, as displayed; the unit is
1/100 pt in `i32` (`Cpt`); matrices are 16.16 fixed point in `i64` (`Fx`);
no floats in kin; JSON prints points with ≤ 2 decimals.
**Why**: floats disagree across languages (nome §7); centipoints print
exactly in decimal, which consumers want; top-left is what every consumer of
HTML and image coordinates expects.
**Alternatives**: nome's 1/64 unit (prints as ugly binary fractions);
bottom-left PDF space (every image- and web-oriented consumer would convert);
integer points (too coarse for sub/superscript and kerning signals).
- [ ] **Signed off** (maintainer)

### D010 — Provenance is a source bitset; confidence is calibrated per-mille
**Decision**: every node carries `src` (bitset of SPEC §3.5 sources) and
`conf` 0..1000, whose meaning is calibrated per kind on the corpora; until
calibration, the Model says `"conf":"uncalibrated"`.
**Why**: a scraper needs to know what to trust; an uncalibrated score that
looks like a probability is worse than none.
**Alternatives**: float probabilities (floats); per-source confidence vectors
on every node (heavy; available in the evidence dump instead); no confidence
(rejected: accuracy is the product, and confidence is how consumers act on
partial accuracy).
- [ ] **Signed off** (maintainer)

### D011 — The Model: a logical tree + page table + ledger, stored as integer arenas
**Decision**: SPEC §3 — reading-order tree of sections/blocks/inlines with
regions per node, a `pages` table, `furniture`, and a `dropped` ledger; in
kin, six `Cells` arenas with a node stride of 16.
**Why**: consumers crawl logical structure; pages are metadata of it.
Regions-per-node keep paragraphs and tables whole across page breaks. The
ledger makes every drop auditable. Arenas are the only representation kin
can build (it cannot allocate) and are trivially serializable.
**Alternatives**: page-major tree (pages → blocks) as in hOCR/ALTO — easier
to produce, worse to crawl, and breaks multi-page tables; a flat block list
with levels (loses nesting of sections/lists/cells); a DOM-shaped model (ties
the model to HTML, which the maintainer ruled out).
- [ ] **Signed off** (maintainer)

### D012 — Serialization is canonical JSON, and it is also the gate subject
**Decision**: SPEC §4: nested JSON, ids on every node, fixed key order, no
insignificant whitespace, leaf `text` + conditional `inlines`, versioned
with a committed JSON Schema; byte-identical across targets.
**Why**: the most parseable/navigable format there is; `jq`-friendly;
kin can write it deterministically, so one comparison is the whole
cross-language claim.
**Alternatives**: CBOR/protobuf (binary, not inspectable, needs schema
tooling — can be added as an exporter); XML/hOCR/ALTO/PAGE (verbose; parked
as exporters for interop); JSON Lines of flat nodes (better for streaming
huge documents; parked); nome's integer stream (for gates only).
- [ ] **Signed off** (maintainer)

### D013 — HTML is a pure projection: semantic, clean by default, honest
**Decision**: SPEC §5 mapping; no layout CSS; no images; `<b>`/`<i>` not
`<strong>`/`<em>`; `data-emon-*` for things HTML lacks; ids/geometry/
confidence opt-in; milestones for pages.
**Why**: the consumer is a scraper; clean semantic HTML is what scrapers
handle best, and emitting semantics emon cannot know (emphasis vs bold) would
be inventing information.
**Alternatives**: absolutely positioned HTML (pdf2htmlEX-style — visually
faithful, structurally useless); ids always on (noisy; opt-in instead).
- [ ] **Signed off** (maintainer)

### D014 — Fusion: three concurrent sources, aligned, health-scored, arbitrated by explicit rules
**Decision**: text layer + OCR + tracer run concurrently; stage 2 aligns them
into correspondence groups, scores per-region health, and arbitrates by an
ordered, data-driven rule table (SPEC §9); the winning evidence and the
reasons are recorded.
**Why**: the maintainer's strategy, made precise. Rules first because they
are explainable, deterministic in integers, and debuggable from the evidence
dump; health-per-region because a PDF's text layer is often good on some
fonts and garbage on one.
**Alternatives**: always trust the text layer when present (fails on broken
encodings and hidden OCR layers); always OCR (throws away exact born-digital
text); a learned arbiter (parked: admissible later only as a recorded host
port, per SPEC §1.1).
- [ ] **Signed off** (maintainer)

### D015 — Watermark/background/ghost defence is layered, strongest signal first
**Decision**: SPEC §9.5's six defences — PDF artifact markings, cross-page
recurrence, ink-layer separation with OCR on the body-ink image, geometry,
seal overlap, plausibility — each recorded in the ledger with its reason.
**Why**: no single signal is reliable; each catches what the others miss, and
the ordered list means the cheap exact ones (the PDF saying so) pre-empt the
expensive heuristic ones.
**Alternatives**: OCR only the text-only render (depends on renderer layer
masks, unavailable for scans); drop low-confidence OCR words (drops real
faint text too).
- [ ] **Signed off** (maintainer)

### D016 — Seals, stamps and signatures become `seal` nodes with extracted details
**Decision**: one node kind with `kind` ∈ seal/stamp/signature/initials/
digital-signature and optional `name`, `text`, `date`, `color`; digital
signatures from `/Sig` dictionaries; circular seals read via polar unwarp +
targeted OCR; OCR text inside a seal never enters the body.
**Why**: the maintainer asked for a semantic element with details when
detectable; one kind keeps consumers' queries simple.
**Alternatives**: separate kinds per variety (more kinds, same fields);
treating seals as figures (loses the semantics scrapers of legal/government
documents care about).
- [ ] **Signed off** (maintainer)

### D017 — Running furniture: drop what the output already says; keep once what is invariant; keep per page what varies
**Decision**: SPEC §10.2. Page numbers become page labels (not dropped from
the Model — dropped from the flow); document/section titles are dropped
(the heading tree says them); other invariant content becomes one
`furniture` node; varying content (Bates numbers, per-page IDs) stays per
page. Everything dropped is in the ledger; policy is overridable.
**Why**: it is the maintainer's rule generalised into a principle that
decides the cases the rule does not name, and it never destroys information
a scraper might need (citations need page labels; legal scrapers need Bates
numbers).
**Alternatives**: always drop headers/footers (loses Bates numbers,
confidentiality labels); always keep per page (floods the output with
repeats); keep as per-page asides (the maintainer's "maybe" — kept only for
the varying content, where it is actually per-page information).
- [ ] **Signed off** (maintainer)

### D018 — Heading levels come from the document's system, not from size
**Decision**: document-wide style inventory, numbering schemes, external
priors (tagged, outline, detected TOC), then a global integer-penalty level
assignment over style classes, with size/weight as the weakest signal.
**Why**: the maintainer's observation that higher-level headings can be
visually smaller; only a document-wide model can see that a class is a
parent of another.
**Alternatives**: rank by font size (the naive approach, wrong in exactly the
cases the maintainer named); per-page decisions (inconsistent across a
document).
- [ ] **Signed off** (maintainer)

### D019 — Tables: competing profiles; cross-page merge by header equality or column geometry
**Decision**: SPEC §10.5 — six profiles scored independently; continuation
merged with recurring headers (header rows de-duplicated to the ledger) or
without (column-geometry agreement, nothing between); split rows merged and
flagged.
**Why**: real documents mix table styles in one file; a single detector tuned
for ruled tables misses the rest.
**Alternatives**: a single lattice detector (misses whitespace tables);
per-page tables only (the maintainer explicitly wants multi-page).
- [ ] **Signed off** (maintainer)

### D020 — Graphics become empty `figure` placeholders with a kind; no pixels
**Decision**: SPEC §10.6 kinds; captions linked; inner text kept as
`labels` by default; alt text kept when the PDF supplies it.
**Why**: the maintainer's placeholder requirement; scrapers want to know a
chart is there and what it is about, not its pixels.
**Alternatives**: emitting crops as data URIs (bloats output; left as open
question SPEC §17.1); dropping graphics entirely (loses that a chart existed
and its caption association).
- [ ] **Signed off** (maintainer)

### D021 — Locale is an optional hint; absent means locale-independent, not English
**Decision**: SPEC §11. Without a hint: script-driven OCR model selection and
language-neutral patterns only. With a hint: keyword tables, dehyphenation,
OCR languages. A PDF's `/Lang` does not enable keyword tables by itself.
**Why**: the maintainer's requirement; `/Lang` is too often wrong to drive
keyword matching.
**Alternatives**: default to `en` (biases every non-English document);
language identification from text (parked — possible later via n-gram
tables, but a wrong guess would silently enable wrong keywords).
- [ ] **Signed off** (maintainer)

### D022 — Stages 1–2 are per page; stage 3 is document-wide; the kin core is single-threaded
**Decision**: concurrency is the host's via `Task` and plan/fulfil; merge in
submission order; serial default until a benchmark says otherwise.
**Why**: nome §6, plus the fact that running headers, heading systems and
continuing tables are cross-page by nature.
**Alternatives**: streaming stage 3 (page-at-a-time output for huge
documents — parked; it needs a two-pass furniture detector).
- [ ] **Signed off** (maintainer)

### D023 — Exact gates and accuracy gates are separate; accuracy ratchets from a measured baseline
**Decision**: SPEC §14: identity/determinism/drift/schema/fuzz gates are
exact; `bb accuracy` scores metrics against a committed scoreboard and fails
on regression; thresholds are set from the first measurement and only rise.
**Why**: "same everywhere" and "right" are different claims — four
identically wrong answers agree perfectly (nome's `check` comment).
Pre-committed thresholds would be guesses.
**Alternatives**: target numbers up front (arbitrary); no gating on accuracy
(regressions would go unseen).
- [ ] **Signed off** (maintainer)

### D024 — ~~Reuse nome's kin modules by rewrite-copy with provenance, for now~~
**SUPERSEDED by D039** (maintainer, 2026-09-30: "Don't share code, they're
completely independent projects."). Kept below so the reasoning survives.
**Decision (struck)**: `kin/scripts/vendor` copies named nome modules into
`kin/vendor/` with `nome.`→`emon.` rewriting and a commit stamp; `bb vendor`
reports divergence.
**Why**: zero changes to nome today, explicit about what is shared, and the
drift report keeps the copies honest.
**Alternatives**: a shared kin library used by both (the right end state —
open question for the maintainer, SPEC §17.2); including nome's sources
directly in emon's kin project (couples emon's build to nome's working tree
and emits `nome.*` modules into emon's `gen/`).
- [ ] **Signed off** (maintainer)

### D025 — The first vertical slice: born-digital text layer → Model → JSON + HTML, Rust host
**Decision**: Phase 1 as written: classic or stream xref, Flate, simple fonts
and Type0/Identity-H with ToUnicode, single column, paragraphs only; Rust
host; all four targets generated and compiled.
**Why**: it exercises every stage boundary (Source, Evidence, Model, JSON,
HTML, driver, gate) with the least algorithmic risk, on documents whose truth
is known (the HTML they were printed from, D041).
**Alternatives**: OCR-first slice on a PNG (exercises ports and plan/fulfil
earlier, but the Model and emitters would be built against noisy evidence;
OCR is spiked in P2.6 instead).
- [ ] **Signed off** (maintainer)

### D026 — The repository follows nome's conventions, and emon is a library only
**Decision**: SPEC §15 layout; generated code committed; no `emon`
executable in the deliverable (drivers exist under `test/`).
**Why**: one toolchain, one set of habits across both projects; nome's
reasons for committing `gen/` (a build must not need babashka) apply
unchanged.
**Alternatives**: a CLI in the deliverable (a consumer writes one in ten
lines over the library; shipping one commits emon to a CLI surface).
- [ ] **Signed off** (maintainer)

### D027 — Decisions carry a sign-off box and never block work
**Decision**: this section's format.
**Why**: the maintainer asked for it; nome's SIGNED column proved the habit.
**Alternatives**: nome's one-line table (kept for brevity there; emon's
founding decisions warrant alternatives written out).
- [ ] **Signed off** (maintainer)

### D028 — emon's vocabularies: `emon.lang`, `emon.port` (scanned), `emon.test`; four extensions
**Decision**: SPEC §6.3. Tags `Cpt`, `Px`, `Fx`, `Conf`, `Bitmap`, `Text`
beside nome's; host-linked `Text` and `Bitmap` forms; the `emit-ascii` form
with a generation-time ASCII check; a `defenum` form for kind tables.
**Why**: the JSON and HTML emitters are made of literals, and nome's
generated-word-table approach does not scale to them; distinct geometry tags
make unit mixing visible; `defenum` ties a kind code to its serialization so
neither can be added without the other.
**Alternatives**: generated code-point tables for every literal (nome's
`pdfwords` approach — workable, unreadable for an emitter); a `Str` tag and
string values in kin (a larger kin change; not needed — emon only *writes*
literals); hand-written emitters per host (four implementations that must
agree byte for byte — the risk kin exists to remove).
- [ ] **Signed off** (maintainer)

### D029 — Text normalization: NFC, ligatures decomposed, soft hyphens removed, dehyphenation only with a locale
**Decision**: SPEC §3.6.
**Why**: scrapers search for `fi`, not `ﬁ`; aggressive dehyphenation without
a dictionary corrupts real compounds, which is worse than a kept hyphen.
**Alternatives**: NFKC everywhere (destroys superscripts, full-width forms and
other content); always join hyphenated line ends (wrong for `multi-page`).
- [ ] **Signed off** (maintainer)

### D030 — Failure is per page
**Decision**: a corrupt page yields an error entry for that page and the
rest of the Model; only intake-level failure (no pages at all, a limit on
the whole document) fails the conversion.
**Why**: crawling at scale wants 199 good pages of 200.
**Alternatives**: fail the document (nome's model, right for a renderer,
wrong for a reader).
- [ ] **Signed off** (maintainer)

### D031 — Phase order: foundations → slice → identity + measurement → PDF breadth → structure → tables → raster/OCR → fusion → figures/seals → hosts → hardening
**Decision**: the phases in this file, under the ordering principle's four
rules.
**Why**: measurement before heuristics; clean evidence before noisy; the
second host and identity gate before breadth.
**Alternatives**: OCR/fusion earlier, since it is the maintainer's headline
strategy (the counter-argument is real: fusion is where accuracy is won on
scans. Mitigated by P1.3's OCR-ready evidence schema and P2.6's spike; if
the maintainer's corpus is mostly scans, swap Phases 4–5 with 6–7).
**Update 2026-09-30**: the maintainer says the crawl mix is mixed, so the order
stands.
- [ ] **Signed off** (maintainer)

### D032 — ~~The nome round trip is the primary ground-truth source~~
**REVISED by D041**: nome would have been a dependency; the round trip now
uses external producers at dev time, with committed output.
**Decision (struck)**: HTML → nome → PDF (→ render → degrade) gives documents whose
structure is known exactly; public datasets and a hand-labelled set
complement it; Chromium's `printToPDF` gives a second producer.
**Why**: labelling is the expensive part of accuracy work, and a sibling
project that writes PDFs from known structure makes it nearly free.
**Alternatives**: public datasets only (licences, annotation schemes that do
not match the Model, no multi-page tables with known headers); hand
labelling only (slow, small).
- [ ] **Signed off** (maintainer)

### D033 — OCR is engine-agnostic behind `Ocr`; Tesseract is the default binding
**Decision**: the port's contract is SPEC §7.2; every host ships a Tesseract
binding; embedders may register another engine.
**Why**: the maintainer named Tesseract; keeping the port generic lets a
stronger engine be swapped in per deployment without touching kin.
**Alternatives**: shelling out to the `tesseract` CLI (simple, slow,
process-per-call; acceptable as a fallback binding, not the default).
- [ ] **Signed off** (maintainer)

### D034 — External priors are verified, not obeyed
**Decision**: tagged-PDF roles, outlines, `/Lang`, `/ActualText`, AcroForm
appearances are strong *evidence* scored for informativeness and agreement
with geometry — never taken as truth without that check.
**Why**: producers routinely emit uniform `P` tags, stale bookmarks and wrong
`/Lang`; obeying them would import their errors wholesale.
**Alternatives**: trust tagged PDFs fully (fast path, frequently wrong);
ignore them (throws away the author's own answer when it is right).
- [ ] **Signed off** (maintainer)

### D035 — `porttest` is the one named exception to the `Tests/` path rule
**Decision**: `emon.targets` keeps nome's C# rule (a namespace whose leaf ends
in `test` emits to `gen/csharp/Tests/`, because it needs xunit) with one named
exception set, `probe-modules` = `#{"porttest"}`.
**Why**: DEVLOG P0.2 fixes the probe's name as `kin/porttest.kin`, and it is a
plain probe that `bb check` must compile in the ordinary build, not a unit
test.
**Alternatives**: rename to `ports.kin` (departs from the DEVLOG); key the rule
on requiring `emon.test` (kin gives the path fn only the namespace name).
- [ ] **Signed off** (maintainer)

### D036 — Port conventions the four hosts are held to
**Decision**: 32-bit blob readers (`blob-u32`, `-le`) answer an `I32` carrying
the 32-bit *pattern* (high bit set = negative on every target); an invalid
code point in `text-cp` (negative, > U+10FFFF, surrogate) appends U+FFFD on
every target; `text-i32` is decimal, invariant-culture, no separator; limit
and setting *kinds* are integers numbered in each host (11 limits from SPEC
7.4, env `EMON_LIMIT_<NAME>`; settings `OCR_MODE`=0 auto/1 always/2 never
and `TASKS`=0 serial/1 parallel, env `EMON_SETTING_<NAME>`; placeholder
tables, they grow per phase); `limit-breach` never returns (panic / throw).
`emon.lang` has no `F64`/`Str` tags and no `Bitmap` tag yet (arrives with the
`Bitmap` port, P6.1); `emon.test` is a placeholder vocabulary until P0.5.
**Why**: nome's conventions where they exist; `hosts` cannot compare the
numbering tables, so `porttest` exercises the kinds it lists on all four.
**Alternatives**: `U32`-typed readers (Java/C# cannot type it anyway);
throwing on invalid code points (would differ per host).
- [ ] **Signed off** (maintainer)

### D037 — `gen/` is staged, not committed, until the maintainer commits
**Decision**: this pass staged `gen/` (`git add gen`) so `bb drift`, which
diffs against the git index, works; nothing was committed. The first commit
must include `gen/` (nome D26).
**Why**: instructed not to commit; the index is what drift reads.
**Alternatives**: committing (forbidden here); a drift that snapshots instead
of asking git (flint's way; nome rejected it).
- [ ] **Signed off** (maintainer)

### D038 — OCR policy defaults to `always`: every page, born-digital included
**Decision**: by default OCR, the tracer and the structured parser all run
on every page, born-digital pages included, and the results are fused. `auto`
(OCR only where text-layer health, image coverage or hidden text call for it)
and `never` stay as documented options. Resolves the open question in SPEC
§17 (maintainer, 2026-09-30).
**Why**: the maintainer's choice; accuracy over cost. OCR on born-digital
pages is what catches broken encodings, text in images and outlined text,
and it gives the health scores (SPEC §9.3) an agreement rate on every page.
**Alternatives**: `auto` as default (roughly 1–3 s/page cheaper, but misses
failures the health heuristics cannot see without a second witness).
- [ ] **Signed off** (maintainer)

### D039 — emon and nome are independent: no shared code, no dependency (supersedes D024)
**Decision**: emon shares no code with nome and has no build, test or runtime
dependency on it: no vendoring, no provenance tracking, no `bb vendor`, no
shared library, no script path into `../nome`. emon writes its own inflate,
font readers, Unicode machinery, hyphenation and the rest. It may follow
nome's conventions and read nome's modules as reference or inspiration when
writing its own (SPEC §7.5).
**Why**: the maintainer's instruction: completely independent projects.
Each can change its substrate on its own schedule, and neither can break the
other's build.
**Alternatives**: rewrite-copy with provenance (D024, struck); a shared kin
library (rejected by the maintainer).
- [ ] **Signed off** (maintainer)

### D040 — Encrypted PDFs: extract whenever possible, ignore permission flags; `password-required` is a distinct error
**Decision**: emon decrypts and extracts whenever it can and never consults
the `/P` permission flags. An empty user password is tried automatically. A
caller may pass a password alongside the document; emon tries it as the
user password, then as the owner password. The named errors are
`password-required` (a password is needed and none was supplied),
`password-incorrect` (the supplied password does not open the document) and
`encryption-unsupported` (public-key or custom handlers). Each is
distinguishable from every other failure (SPEC §16).
**Why**: the maintainer's instruction. Crawlers need to route "needs a
password" separately from "broken file".
**Alternatives**: honour "no extraction" by default or expose it as a
setting (rejected by the maintainer); a single generic decryption error
(callers could not tell a missing password from a corrupt file).
- [ ] **Signed off** (maintainer)

### D041 — Synthetic ground truth from external producers at dev time, committed (revises D032)
**Decision**: the HTML → PDF → (render → degrade) round trip stays the
primary ground-truth source. PDFs are printed by **external tools at
development time**: headless Chromium's `printToPDF` is the primary producer,
and others (nome, WeasyPrint, LibreOffice) are optional so emon does not
overfit to one producer. The generated PDFs, images and gold Models are
**committed**, together with the producer and version. No gate, build or test
invokes any producer. Regenerating is a reviewed human action.
**Why**: D032 made nome a dependency of emon's test pipeline, which D039
rules out. Producing the corpus offline and committing it keeps the oracle
and removes the dependency: a missing producer only stops *regeneration*,
never testing. Naming Chromium as primary means nome is not even needed for
that.
**Maintainer answer (2026-09-30)**: nome may be an *optional* external
corpus producer (a dev-time tool, never a dependency), but it is not
complete yet, so adding it is deferred (parking lot).
**Alternatives**: an emon-owned test-only PDF writer that prints gold Models
directly (independent, but an extra component with narrow producer
realism); public datasets only (no multi-page tables with known headers).
- [ ] **Signed off** (maintainer)

### D042 — `emit-ascii` and `defenum` are project-local forms that rewrite into existing forms
**Decision**: both live in `emon.lang`'s `:forms` (no kin change). `emit-ascii`
checks its literal (a string literal, printable ASCII 0x20-0x7E) and then
dispatches `emon.port/text-ascii`; `defenum` builds ordinary `kin.lang/defn`
forms and links (`:declare`) and emits (`:generate`) them, so the constants
are locals, exports and `declared-names` like any `defn`. The rewritten heads
are fully qualified and injected into the context's `:scope-syms`, so a source
need not refer `text-ascii`, `defn`, `if` for these forms to work. Refusals
name the form and the NAMESPACE (`emon.x`), not the file: kin's `ctx` has no
source label, only `:kin/ns`; a namespace names its file. Tests: `bb lang-test`.
**Why**: kin has no macros, but a form is `(fn [ctx form])` and may dispatch
other forms through the same context; this keeps four spellings in the forms
that own them.
**Alternatives**: templates per target inside `emit-ascii` (duplicates the
port annotation's spelling); a generated `.kin` from a table (a second source
of truth). If the file path in the message matters, kin would need to put the
label on the context (see "Found in kin").
- [ ] **Signed off** (maintainer)

### D043 — `defenum` shape: `(defenum ^:pub nk [[sym code "spelling"] ...])`, emitter a flat `if` run
**Decision**: one `defn` per row answering its code, plus `nk-emit-name [t code]`
(name = enum name + `-emit-name`) that writes the spelling and answers 1, or
writes nothing and answers 0 for an unknown code. `^:pub` on the enum name makes
every generated function public. Refused at generation time: duplicate symbol,
code or spelling; a non-integer code; a spelling that is non-ASCII or holds `"`
or `\` (it is written between JSON quotes unescaped); a row named
`<enum>-emit-name`. The emitter is a chain of `if (code == N) {...; return 1;}`
and NOT a `case`, because kin's `case` arms are values and an arm here is a
statement; cost is linear in the number of kinds (about 30), fine for
serialization, and revisitable if measurement says otherwise.
**Why**: brief said "a `case` over codes"; that is not expressible, this is the
plainest equivalent that all four targets agree on.
**Alternatives**: a private helper per row so `case` arms are calls (doubles the
functions); returning a row index and mapping it in the host (the spelling
would leave kin).
- [ ] **Signed off** (maintainer)

### D044 — `emon.test` is four-target; Java runs without JUnit; test support lives in `testport`
**Decision**: `emon.test` targets Rust, Go, Java and C#. Java has a JDK here
and no JUnit, so a `deftest` is a `public static void testXxx()` and
`kin/scripts/unit` compiles the generated laws with a small reflection runner
that calls every public static no-argument `test*` method and counts the ones
that do not throw. Assertions throw (Java `AssertionError`, C# `Exception`,
Rust `panic!`) or call `Errorf` through a `Failer` interface (Go, so the
production `port` package does not import `testing`). They live in
`host/{rust/src/testport.rs,go/port/testport.go,java/emon/Testport.java,
csharp/Emon/Testport.cs}` and not in `Port`, so the library's port surface
stays the ports; `bb check` copies them so generated test modules compile in
the ordinary build. Go's test parameter is `emonT`. Which sources are laws is
derived (their `ns` requires `emon.test`), not listed. `bb unit red` sets
`EMON_TEST_INVERT=1`, which every assertion honours by flipping its verdict,
and requires every arm to fail.
**Why**: the brief said "all targets"; the Java arm costs 25 lines. Keeping
test helpers out of `Port` keeps them out of what a host author must port.
**Alternatives**: skip Java (nome's choice, where no JDK exists); install
JUnit (a network dependency in a gate); put the assertions in `Port`.
- [ ] **Signed off** (maintainer)

### D045 — `shl32` and `sar32` join `emon.lang`
**Decision**: two 32-bit shift forms (`(shl32 x n)`, `(sar32 x n)`) in
`emon.lang`, defined for a count of 0..31; `sar32` is the arithmetic shift
(equal to logical for the non-negative values a bit reader holds). They are
`<<` and `>>` in all four languages (Go casts the count to `uint`).
**Why**: `kin.lang` ships no shifts (they depend on the tag's signedness in
Rust), and inflate's bit reader is shifts; multiplying by a power-of-two
table would be slower and less clear. Go folds a shift of two constants at
compile time and rejects `1 << 31` as an `int32` overflow, so laws pass the
operands as locals; real call sites pass variables.
**Alternatives**: `quot`/`*` by `pow2`; `shr64` on widened values.
- [ ] **Signed off** (maintainer)

### D046 — Row corpora are read by kin from `test/` through `test-file`; `bb unit` replaces the `bb check` probe
**Decision**: a corpus is a text file under `test/`, read whole as a `Blob`
through the test port `test-file` (directory from `EMON_TEST_DIR`, which
`bb unit` sets) and parsed by a driver written in kin, so four targets also
agree about the row parser. P0.4's "done when" said `bb check` would run an
inflate probe and the NFC column; both run under `bb unit` instead, per row
(status, consumed, bytes), with the failing line number. `kin/scripts/
inflate-corpus.py` generates `test/inflate/streams.txt` from CPython's zlib
and hand-built streams (and asserts zlib also rejects each damaged one); the
committed file is authoritative and is NOT regenerated or checked by any gate,
because compressed bytes differ between zlib versions and builds. `kin/scripts/
ucd.py --check` (deterministic) runs at the start of `bb unit`. The UCD files
are vendored unmodified under `test/normalize/`, Unicode 18.0.0.
**Why**: a row corpus says which row failed; four-target agreement on the
parser is free; the tests need no second harness per language.
**Alternatives**: a probe in `bb check`; corpus rows compiled into kin
sources (megabytes of string literals); Python-side harness.
- [ ] **Signed off** (maintainer)

### D047 — `emon.inflate`: Blob in, Cells out, a status per failure, the prefix kept, bit-at-a-time
**Decision**: `(iz-zlib src start end out st)` and `(iz-raw ...)` read a
`Blob` span and APPEND bytes (one per cell) to a caller-supplied `Cells`;
`st` is scratch state. They answer an `iz-status` code (`defenum`: ok,
truncated, bad-block-type, bad-stored-length, bad-code, bad-distance,
bad-code-lengths, bad-header, need-dict, bad-checksum, bad-counts); the FIRST
error is the one reported; everything decoded before it stays in `out`
(PDFs with a damaged stream still yield what could be read, D030);
`(iz-consumed st)` gives the index after the stream. Acceptance follows
zlib's: code-length code must be complete; literal/length and distance codes
may be incomplete only with no codes or one code of length 1; end-of-block
must have a code; symbols 286/287 and distance symbols 30/31 are errors;
Adler-32 is checked; `FDICT` is `need-dict`. The output bound is `limit-of`
STREAM_BYTES via `limit-breach` (a limit, not a status). Decoding walks one
bit at a time (puff's canonical method, from scratch): simplest to get right
on four targets; a lookup-table decoder is the optimisation if measurement
says so. A source `Blob` only: a later filter stage reading an earlier one's
`Cells` needs a `Cells`-to-`Blob` port or a `Cells` overload, to be added
with P1's filter chain. One known difference from zlib: for a dynamic-header
fault found only by reading more bits than remain, `truncated` can be answered
where zlib answers a code error.
**Why**: PDF streams arrive as a span of the file (a `Blob`); kin cannot
allocate, so output is caller-owned.
**Alternatives**: `Cells` source (nome's shape; needs a copy in from the file);
raising on error and dropping the prefix.
- [ ] **Signed off** (maintainer)

### D048 — `emon.normalize`: NFD/NFC over canonical data only, ligature fold and soft-hyphen removal as flags
**Decision**: SPEC 3.6 asks for NFC plus U+FB00-FB06 and soft hyphens, not
NFKC (D029), so `emon.nftable` (generated by `kin/scripts/ucd.py` from
`UnicodeData.txt` and `DerivedNormalizationProps.txt`) holds canonical data
only: combining-class ranges, flattened canonical decompositions, primary
composites (two-code-point mappings outside Full_Composition_Exclusion) and a
seven-row ligature table taken from the compatibility mappings. Hangul is
arithmetic. `(nz-nfd src from to out flags)` and `(nz-nfc src from to out tmp
flags)` append to `out`; flag 1 expands ligatures, flag 2 drops U+00AD. The
caller decides WHICH soft hyphens (SPEC says "at line ends", which needs
layout). U+FB05 (long s t) expands to `st`, not `<long s>t`, as NFKC would
fold long s to s; the generator asserts all seven rows. Not built: NFKC/NFKD,
a quick-check property, locale-sensitive anything.
**Why**: the narrowest thing that does what D029 says, and ~100 KB of tables
instead of ~500 KB. The whole of `NormalizationTest.txt` passes on all four
targets for the two forms emon implements.
**Alternatives**: NFKC then restore content (destroys superscripts and
full-width forms, D029); a ligature table by hand (the generator derives it).
- [ ] **Signed off** (maintainer)

### D049 — `emon.bytes` and `emon.splitmix` API shapes
**Decision**: `bytes` (`by-` prefix) adds what the raw ports lack: signed
reads (`by-i8`, `by-i16[le]`), 24-bit (`by-u24[le]`), unsigned 32-bit widened
to `I64` (`by-u32-64`, `by-u32le-64`, built from 16-bit halves), bounds
questions (`by-in`, overflow-safe), reads with a default (`by-u8-or`,
`by-u16-or`, `by-u32-or`) and substring search (`by-find`, `by-find-back`).
`splitmix` (`sm-`) is SplitMix64 as a stream over a two-cell state in a
`Cells` (`sm-seed`, `sm-next`, `sm-below`) plus the stateless `sm-at seed i`
(the i-th word of the stream) and `sm-mix`; constants are written as signed
64-bit literals (kin emits them correctly in all four). `sm-below` reduces the
top 31 bits by `rem`, a bias below n/2^31, documented.
**Why**: the same reasons a PDF/font reader writes these anyway; per-stage
generators threaded explicitly keep determinism (SPEC 12.2) independent of
call order.
**Alternatives**: a global generator; `sm-below` by rejection sampling (a
data-dependent number of steps per call).
- [ ] **Signed off** (maintainer)

### D050 — The Model's arena conventions: codes forever, `sub`/`aux`/attrs, ids are pre-order indices, furniture is a second root
**Decision**: `kin/model.kin` (`emon.model`). Node kinds 1..25 in SPEC 3.2's
table order; every enum (`nk fk sr fl sy al rl fg sl dp ch pk dr mc`) is a
`defenum`, its codes assigned once and never reused, new rows only at the end.
Absent is -1 (links, runs, conf) and count 0. A node's kind-specific small
integer is its `sub` (a level, align, role, figure/seal subkind, formula
display, span style bits, break page, list `ordered` 0/1=unordered/2=ordered,
cell `header`, row `repeated`) and `aux` (a cell's `empty`); every other
field is an attribute (`key, value-kind int|text|node|bool, value, len,
next`), a repeated key (`labels`, `pages`) being repeated attributes. Attribute
keys ARE the JSON field names (`fk`): one table spells both. A node's `id` is
its index, and ids are in document order because the builder appends and trees
are built in pre-order; `model-check` verifies it (`mc-not-preorder`). A
`furniture` node is a second ROOT (parent -1), listed in the JSON `furniture`
array, so the body's ids stay contiguous. Document-level title, lang and
metadata are attributes of node 0 and serialize in the header (`source.lang`,
`meta.*`), not on the body node (no second copy). Pages are stride 16
(slots 11..15 reserved, zero) and the ledger stride 10, per SPEC 3.8. Nesting
is bounded at `max-depth` 200: `node-new` answers -1 beyond it, which is what
lets the serializer recurse without a depth check of its own. Every Cells
function takes all the arenas it touches as parameters (the host's `Model` is
six `Cells`); `text-append-cp` stores U+FFFD for anything that is not a scalar
value and `text-append-utf8` decodes per the WHATWG rule.
**Why**: the layout is SPEC 3.8's, made exact; absent-as-minus-one gives one
spelling of "omit" from arena to JSON; ids that are indices cost nothing and
cannot drift across targets; a second root keeps the body's ids a clean
range.
**Alternatives**: every field as an attribute (uniform, but one extra walk per
level/role read and more cells); furniture as children of a hidden root (ids
interleave); ids assigned at serialization (no `link.target` until then).
- [ ] **Signed off** (maintainer)

### D051 — Canonical JSON details the SPEC left open
**Decision**: `kin/json.kin`. Top-level key order `emon, generator, source,
settings, conf, meta, pages, body, furniture, dropped`; in a node `id, kind,
the kind's fields (SPEC 3.2 order), text, inlines, children, at, src, conf,
flags`. `generator`, `source` and `meta` are omitted when empty; `settings`
always, with `locale` always (`null` when none); `pages`, `furniture` and
`dropped` always, `[]` when empty. `src` and `flags` list names in bit order;
`at` is `[[page,x0,y0,x1,y1],...]`; a `dropped` entry is `reason, page, at
(page first, as SPEC 4.2's example), text, conf, src`. String escapes: `"`
`\` `\n` `\t` short, every other U+0000-U+001F (including `\r`, `\b`, `\f`)
as `\u00xx` in LOWER-case hex, U+2028/U+2029 as `\u2028`/`\u2029`, U+007F and
above raw. Centipoints print as points: `7250` -> `72.5`, `-5` -> `-0.05`, `0`
-> `0` (`quot`/`rem` truncation, exact for the most negative I32). Pretty mode:
two-space indent, `"key": value`, every member and every element of an array
of objects on its own line, arrays of numbers or strings (and of such arrays)
flat as `[1, 2]`, empty containers `{}`/`[]`, no trailing newline in either
mode; the goldens carry one editor newline and the comparison drops it. A
code an enum does not know is written as `""` (valid JSON; the schema rejects
it) rather than guessed.
**Why**: SPEC 4.3 fixes the principle and leaves these to `emon.json`; each is
the plainest rule that four targets and a Python reference implement
identically.
**Alternatives**: short escapes for `\r \b \f` (more table, no gain); uppercase
hex; pretty arrays one element per line (the `at` regions become unreadable).
- [ ] **Signed off** (maintainer)

### D052 — Inline nodes carry no `id`; `inlines` only when informative; `text` is derived if a block has none
**Decision**: SPEC 4.2's example writes inline nodes as `{"kind":"text",
"text":"..."}` with no `id`, `at`, `src` or `conf`, while 3.3 says every node
has an id. The JSON follows the example: blocks have ids, inline nodes do not
(they are not cited); any of `at`/`src`/`conf`/`flags` that an inline node
does carry is written. A block writes its inline children as `inlines` only
when one is not a plain `text` node or has a region of its own (the `detail:
words` case), and its other children as `children`; an inline node writes all
its children as `children`. A block's `text` is its own text run; if it has
inline children and no run, `text` is their concatenation (a linebreak as
`\n`). When both exist the stage must keep them equal (`model-check` does not
yet verify it).
**Why**: the example is the only normative JSON in the SPEC; ids on the
hundreds of inline nodes of a page are noise a scraper never uses.
**Alternatives**: ids on every node (bigger output, contradicts the example);
make `text` mandatory on the node (forces every stage to write it twice).
- [ ] **Signed off** (maintainer)

### D053 — `figure` and `seal` kinds are `subkind` in JSON
**Decision**: SPEC 3.2 gave `figure` and `seal` a field called `kind` and
3.3 gives every node a `kind`: one object cannot carry both. The field is
`subkind` (`"kind":"figure","subkind":"chart"`); SPEC 3.2 is amended. HTML's
`data-emon-kind` is unchanged.
**Why**: a duplicate key is not JSON. **Alternatives**: rename the node kind
key (`type`, which `field` already uses); nest (`"figure":{"kind":...}`).
- [ ] **Signed off** (maintainer)

### D054 — JSON goldens are line scripts read by kin, with an independent Python reference and schema check
**Decision**: DEVLOG P1.2 said `test/json/*.model.edn`. A golden is instead
`test/json/NAME.model` (a line script of builder calls, documented in
`kin/jsontest.kin`) plus `NAME.json` and `NAME.pretty.json`. kin parses the
script (so four targets also agree about the parser, D046), builds the Model
through the real builders, and `assert-text-span` compares the serializer's
bytes with the golden on all four arms. `kin/scripts/json-check.py`, run first
by `bb unit`, is a second implementation of the serializer written from the
SPEC that must reproduce every golden byte for byte, and validates them
against `schema/emon-1.json` with `jsonschema` (found on the path, in
`$EMON_PYLIB`, or installed once into `~/.cache/emon/pylib`; SKIP, loudly,
otherwise). `probe.model` is also what `bb check` builds in code
(`kin/modelprobe.kin`): it compares the model's arena fingerprint
(independently computed) and its JSON line across targets.
**Why**: an EDN reader in kin is not what is under test; a golden that is
"whatever kin printed" proves nothing, and two implementations agreeing does.
**Alternatives**: EDN read by a Clojure harness (not four-target); goldens
only.
- [ ] **Signed off** (maintainer)

### D055 — `schema/emon-1.json` is closed, and edited additively on a minor version
**Decision**: draft 2020-12, `additionalProperties: false` throughout, `emon`
a `const "1.0"`, per-kind allowed keys enforced with `if/then` over
`propertyNames`. SPEC 4.4 says consumers must ignore unknown fields; that
binds consumers, not the validator of THIS version's output, where an unknown
key is a typo in emon. A minor version adds to the file in place.
**Why**: the gate exists to catch emitter mistakes. **Alternatives**: open
schema (catches nothing); one file per minor.
- [ ] **Signed off** (maintainer)

### D056 — The evidence schema: nine arenas, contiguous runs, centi-degrees, a version-0 dump
**Decision**: `kin/evidence.kin`. Arenas `glyphs` (stride 20), `words` (16),
`lines` (12), `paths` (16), `images` (12), `components` (20, the tracer),
`ocrwords` (16), `alts` (4, per-symbol alternatives chained from an OCR word)
and `etext` (text runs; one cell per scalar value). A word's glyphs are a
contiguous run of `glyphs` and a line's words of `words` (`ev-check`
verifies runs and chains). Page space in centipoints; `Px` fields say so;
angles are centi-degrees; colour is `0xRRGGBB` in one cell; confidence is
per-mille, -1 absent; how a glyph's Unicode was obtained is `gh`, invisibility
`vz`, artifact subtype `ar`. OCR words carry engine confidence, an alternatives
head and count, line and block ids, baseline, style bits, pass and language;
components carry page box, pixel box, ink area, fill, stroke width, colour,
layer and kind; a stride wider than its fields (paths, components) leaves spare zero
columns at the end. Not modelled yet: contour
polygons (P6), per-glyph `Tz`/char-spacing, annotations and the structure-tree
mapping (P3). The dump (`emon-evidence`, version "0") uses the Model
serializer's punctuation and escaping, fixed key order, omits defaults,
prints colours as `"#rrggbb"` and centi-units as points.
**Why**: ordering principle 3: OCR's and the tracer's fields exist before
OCR does. **Alternatives**: one evidence row type with a source column
(wide and mostly empty); a chain for a word's glyphs (two cells beat a
list).
- [ ] **Signed off** (maintainer)

### D057 — `bb gates` and SPEC 15.1 (P0.7, P0.8)
**Decision**: `kin/scripts/gates` runs `hosts drift lang-test check unit`,
requires each to exit 0, print at least one `  ok ` line and no `FAIL`, and
fails if a `bb.edn` task is neither in its list nor in a named non-gate list.
What a newcomer needs is written in SPEC 15.1; there is no README.
**Why**: nome's lesson: a gate that prints nothing is not a gate, and a gate
not in the list is not run. **Alternatives**: a `gates` that only chains tasks.
- [ ] **Signed off** (maintainer)

### D058 — Inputs are Blob-like async byte sources; kin reads a `Src` that may miss; a unit that missed is re-run (REVISED)
**Revised** (maintainer, 2026-09-30: "an abstract interface akin to JS's
Blob is probably what we need. Something that lets us read slices and jump
around; but doesn't guarantee synchronous in-memory access."). The first
version below materialized every input into a `Blob` before kin ran; that
is now only the trivial in-memory source. What replaces it (SPEC §13.1):
- **kin reads a new tag `Src`**, not a `Blob`: `src-len`, `src-u8` (may
  miss), `src-missed`, `src-slice` (resident span → `Blob`), `src-of-cells`
  (decoded object-stream bytes → an in-memory `Src`), `src-of-blob`,
  `src-hint` (prefetch, may be a no-op). `Blob` stays for in-memory bytes, so
  `bytes`, `inflate` and the filters are unchanged. A `Src` is a per-unit view
  of a per-input host chunk cache (64 KiB chunks).
- **Miss, abort, re-run.** A sync source fills a missing chunk inline (never
  a round). An async one answers 0, sets the view's missed flag and records
  the chunk; kin checks `src-missed` at checkpoints (after each indirect
  object, xref section, trailer, stream span) and returns `need-bytes` to the
  unit's top; the host awaits the missed ranges (a `bytes` plan/fulfil
  request, never transcribed, not counted in `max-rounds`) and re-runs the
  unit from scratch on fresh or copied `Cells`. Only a miss-free attempt is
  kept, so the kept run IS the in-memory run: determinism and identity need
  no new argument. A breach or error in an attempt that missed counts as a
  miss. Units must not mutate `Cells` they did not create or copy.
- **Bounds**: read-ahead doubles per round (≥ 64 KiB · 2^r around each miss,
  coalesced), so a unit takes ≤ ⌈log2(len / 64 KiB)⌉ + 2 rounds and fetches
  at most ~2× what it touched; `FETCH_ROUNDS` (default 24) is a named limit.
  Intake prefetches the first and last chunk; after the xref, kin hints each
  object's extent (offset to the next xref offset).
- **Images/TIFF**: kin only sniffs; `ImageDecode` reads the source in the
  host, forward or by offset, awaited inside fulfilment.
- **Answered**: lazy ranged reading is required (not the optional P9.6);
  PDFs and images may be mixed in one conversion.
- **Gate**: every driver also runs each fixture through a simulated async
  source with 512-byte chunks; JSON/HTML must equal the in-memory run.
**Why this and not the alternatives**: resumable state machines (every
parser rewritten as explicit continuations in a language with no closures,
no forward references and four targets — the in-flight lexer would be
thrown away); a kin-managed window with explicit "need range" returns at
every read (every read site becomes fallible and must propagate, where
checkpoints only make a handful of sites do so, and correctness does not
depend on getting them all: a missed checkpoint only wastes an attempt);
materializing (the maintainer rejected it). The cost of re-running is
compute proportional to rounds, and each discarded attempt stops at its
first checkpoint after the miss.

*History — the first version, kept for the record (superseded where it
conflicts with the above):*
**Decision** (SPEC §13.1): the library and the Engine API take `Input`s — a
host **byte source** (known `length`, `read_at(offset, buf)`; each host's
idiom: a Rust trait, Go `io.ReaderAt`, Java `SeekableByteChannel`, C# seekable
`Stream`, JS `{size, read(off, len) → Promise}`) plus an optional password
(D040) — and never a path, a file handle opened by emon, or a network call.
Adapters (in-memory, seekable, forward stream spooled into memory under the
input-bytes limit; in JS `Blob`/`File`, `ArrayBuffer`, `fetch` ranges,
`ReadableStream`) are host code. **kin is unchanged**: each input is
MATERIALIZED into one immutable `Blob` before intake (awaited in JS), and the
PDF code reads it only through `emon.pdfsrc` (`ps-len`, `ps-u8`), the seam
already in P1.4/P1.5. A later **ranged mode** (P9.6) backs the `Blob` with a
host window; a miss poisons the unit, which is re-run after the missed ranges
arrive as `bytes` plan/fulfil answers — sound because units are pure (D008,
D022), and checked by ranged == materialized. Several inputs make one
document (pages concatenated in order); the password rides with its input and
is never written to the Model header, the dump or a transcript; transcripts
record input index and length only. A source that changes mid-run is the
named error `source-changed`; input is capped at 2^31 − 1 bytes (`Blob`
offsets are `I32`), enforced by the input-bytes limit against the declared
length. The name is "byte source"/`Input`, because "Source" is already stage
0's value and "sources" are text/OCR/trace.
**Why**: the maintainer: "Try to keep the inputs/sources for files abstract,
some kind of streamable/byte stream interface. I want to be able to compile
this to wasm and run it in the browser, so try to avoid depending on a
concrete filesystem." PDF and TIFF need random access (xref at the end,
objects and IFDs by offset), so random access is the contract and forward
streams are an adapter. kin is synchronous and cannot suspend on a promise
(D008), so the await belongs in the host; materializing first costs the
file's size in memory (fine for the crawl mix, and what every browser PDF
tool does for `File`s) and changes no kin code, no in-flight parser, no gate.
Determinism is untouched: kin sees the same bytes however they came.
**Alternatives**: (a) a pure forward stream — cannot read a PDF without
spooling it all anyway; (b) a kin-side window cache with a `src-read off len`
port into a `Cells` window — every parser would thread a window and re-check
bounds, the lexer's index style would be rewritten mid-flight, and a
synchronous port still cannot await a browser read; (c) every read as a
plan/fulfil request with a resumable parser — kin cannot suspend, so the
parser would be rewritten as a state machine; (d) retagging `Blob`'s host type
as an abstract reader now — a virtual call per byte in `inflate`'s
bit-at-a-time loop, unmeasured; deferred to P9.6, where the miss-and-rerun
design needs it anyway; (e) `Engine.convert(path)` for convenience — the
requirement forbids it in the library; drivers and CLIs read files.
- [ ] **Signed off** (maintainer)

### D059 — The PDF object arena is one `Cells`; a handle is an offset; parse state lives in its header
**Decision**: `emon.pdflex` parses into ONE `Cells` (`ar`): a 464-cell header
(position, end, first error, warning bits, token registers, name-intern
buckets, the ids of the well-known names) then six-cell records `kind a b c
next d` (`a` first child or value, `d` last child; a dictionary is an array of
alternating key and value). Strings and interned names are pushed inline at the
tail; a stream record holds only the file span (`start`, `len`, flags). A handle
is the record's index, 0 is "none". Every function takes the arena and the source
and nothing else: no hidden state, so a unit can be re-run from scratch (D058).
`emon.pdfdoc` writes its own small records into the same arena (the filter plan).
Names are compared by entry (interned, `#xx` already decoded); duplicate
dictionary keys answer the LAST value. `kin/pdfobj.kin` (named in P1.4) is not
a separate file: objects, lexer and parser are one module.
**Why**: kin cannot allocate; three separate arenas made every signature carry
three `Cells` and Rust refuses the same `Cells` twice, so one arena with
offsets as handles is the smallest shape that works on four targets.
**Alternatives**: separate object/text/name arenas (more parameters, same
limits); records in a fixed stride with strings in a side arena.
- [ ] **Signed off** (maintainer)

### D060 — The PDF names and keywords the code compares against are a generated table, interned first
**Decision**: kin has no string values, so `/Type` cannot be written in a
source. `kin/scripts/pdfnames.py` generates `kin/pdfnames.kin`: a `defenum pn`
(the id of each well-known name as a constant function, `(pn-type)`), the bytes
of every name, and the lexer's 13 keywords (`pk`). `pl-init` interns the names
in table order, so an id is a position (append-only) and `(pl-wk ar (pn-type))`
is that name's entry in the arena; `bb unit` runs `pdfnames.py --check` as it
does `ucd.py --check`.
**Why**: the alternative is byte-number literals scattered through the parser.
**Alternatives**: a `defname` form in `emon.lang` (shared file, another agent
editing it); comparing names through `Text` (no read port).
- [ ] **Signed off** (maintainer)

### D061 — Parsing is lenient, records what it forgave in warning bits, and fails with a named status
**Decision**: errors are `defenum` codes answered to the caller (`pl-status`,
`pd-status`, `pf-status`; first error kept, output so far kept), NOT `^:throws`:
there is no host function for kin to propagate a failure from (D047's rule).
What every reader forgives is forgiven and noted in `(pl-warn ar)` /
`(pd-warn dc)`: a container closed by `endobj`, a junk dictionary key, a wrong
`/Length`, a missing `endobj`, `stream` without an end of line. Integers saturate
at 2^31-1 (`--5` is -5, `12abc` is 12 then `abc`); a real is a signed mantissa
of at most nine digits and a count of fractional digits (no floats, D009);
`pl-num-cp` converts to centipoints, half away from zero. Container nesting is
bounded at 128 (`nesting-too-deep`), objects by the OBJECTS limit.
A position outside the source reads as its end: a damaged offset never reads out of
range.
**Why**: a crawler wants what can be read (D030); accuracy on damaged files
matters more than purity.
**Alternatives**: `^:throws` per error (needs a port to originate them); strict
parsing with repair only (P3.1).
- [ ] **Signed off** (maintainer)

### D062 — A stream's length: a direct one is trusted only if `endstream` follows; an indirect one is resolved by the document layer
**Decision**: `pl-stream` trusts a direct `/Length` only when `endstream`
follows the data it names (one read past the span); a wrong or missing one is
replaced by a scan (`pl-scan`, flags 1, warning bit 4). An INDIRECT `/Length`
cannot be resolved by the parser and is not scanned for: the stream is marked
(flags 2, length 0, the parse stops at the data) and `pd-fix-length` resolves
it and checks for `endstream`, scanning (`pl-stream-rescan`) only when that
fails. So reading a file never reads through a good stream.
**Why**: the maintainer's input rule (D058): bounded reads, not whole-file scans,
outside repair.
**Alternatives**: scan at parse time (simpler, reads every stream with an indirect length).
- [ ] **Signed off** (maintainer)

### D063 — The document layer: a workspace of five cells, newest entry wins, no repair, no decryption
**Decision**: `emon.pdfdoc` takes `(src ar dc a b st)`: the arena, the document
(a header then five cells per object number: kind, two fields, cache, flags),
and three scratch cells (`a` decodes an object stream, `b` and `st` are the
filter chain's). Sections are read newest first (`startxref`, each `/Prev`;
a loop or a failing older section ends the chain with a warning); an entry is
stored only where none is, so a newer free entry hides an older in-use one; a
hybrid file's `/XRefStm` overrides only the table's FREE entries (the table lists
object-stream objects as free for older readers); offsets are taken relative to
a header that is not at 0 when `startxref` only leads to a section that way;
classic entries are read as tokens (not 20-byte cells), a subsection that starts
at 1 over the free head means 0. An object is parsed on first use and cached by
number; an entry that points at a different object number is treated as missing;
an object stream is decoded once and every object in it cached where the
cross-reference says it lives there. Numbers above 65536 + 64 x file length (or
the OBJECTS limit) are ignored. `pd-open` answers `no-header`, `no-startxref`,
`bad-xref`, `no-root`, `no-pages`, or `encrypted` when the trailer names
`/Encrypt` and the catalog or page tree could not be read: encryption is REPORTED
(`pd-encrypted`), not handled (P3.2, D040); repair by scanning is P3.1 and is
not here, so a file whose `startxref` does not lead to a section is `bad-xref`.
A stream's filter chain is PLANNED first (`pd-stream-plan` resolves `/Filter`,
`/DecodeParms` and their numbers into integers in the arena) and then RUN
(`pd-run-plan`), because resolving can load an object stream, which decodes into
the scratch cells the chain is using, and because Rust refuses the same `Cells`
as two parameters.
**Why**: one set of rules that reproduces poppler's page count on 3976 of 4000
real files and names the rest.
**Alternatives**: a struct of cells (kin has none); decoding while resolving.
- [ ] **Signed off** (maintainer)

### D064 — Filters: LZW, RunLength and TIFF predictor 2 are here, pulled forward from P3.3; lenient semantics; image codecs are declined, not failed
**Decision**: `emon.pdffilter` has ASCIIHex, ASCII85, LZW (with `/EarlyChange`),
RunLength, Flate (inflate's status renamed; EMPTY input is an empty stream, not
truncated), Crypt (identity), and the predictors: PNG 10-15 and TIFF 2 at 8 and
16 bits per component (other depths: `bad-predictor`). A missing end marker is
not an error; ASCII85 stops at the first bad group and keeps the complete groups
before it; a PNG row with a filter byte above 4 ends the output there. An image
codec (DCT, JPX, CCITT, JBIG2) stops the chain: `image-filter`, with `out`
holding the data as it was when the codec was reached (for the host's
`ImageDecode`, D005); an unknown filter answers `unsupported-filter` the same
way. LZW, RunLength and the predictor output are bounded by STREAM_BYTES.
**Why**: the brief asked for LZW and TIFF is a dozen lines beside PNG; real files
use both (P3.3 will find them done).
**Alternatives**: leave them for P3.3; fail on an image codec.
- [ ] **Signed off** (maintainer)

### D065 — Page tree: inheritance, box normalization, what is skipped, and the `Source` JSON
**Decision**: a node with `/Kids` is an intermediate node whatever its `/Type`,
any other is a page; `/MediaBox`, `/CropBox`, `/Rotate` and `/Resources` are
inherited from the nearest ancestor; boxes are normalized (x0 < x1, y0 < y1) in
centipoints with references allowed per number; an absent or zero-area MediaBox
is US Letter and sets a warning; the CropBox is clipped to the MediaBox (the
MediaBox when empty); `/Rotate` wraps to 0/90/180/270 and a non-multiple of 90
is 0 (poppler reports 45; pdf.js reads 0); a node reached twice, a missing or
non-dictionary kid, and depth beyond 64 are skipped with a warning; pages are
bounded by the PAGES limit. The page count is the walk's, not `/Count`'s (poppler
reports `/Count`, so they differ only on a damaged tree). `pd-source-json` writes
the `Source` value (SPEC 2.0) as JSON itself, with its own centipoint formatter:
the format, header version, `encrypted`, and every page's boxes and rotation.
More fields join it in P3.7-P3.9, appended in that order.
**Why**: what every viewer agrees on, and the one place emon differs on purpose.
**Alternatives**: `emon.json` for the output (P1.2 is a Model serializer).
- [ ] **Signed off** (maintainer)

### D066 — Tooling for P1.4/P1.5: `cells-bytes`, `assert-text-span`, `test-file-span`, generated fixtures, a real-file corpus, and two digests
**Decision**: one port form, `cells-bytes`, in all four host trees: a Blob VIEW
(a snapshot copy) of a `Cells` of bytes, for an object stream's decoded bytes
and a filter chain's next stage (in Rust the template borrows a temporary, so it
is called nested, never bound by `let`). All PDF reads go through
`emon.pdfsrc` (`ps-len`, `ps-u8`), D058. The test vocabulary gained
`assert-text-span` (a `Text` against a span of a `Blob`, as UTF-8) and
`test-file-span` (a corpus file named by bytes of a row). `kin/scripts/
pdf-fixtures.py` writes the hand-built fixtures under `test/pdf/` (classic,
xref stream + object streams, hybrid, incremental, inherited attributes, odd
boxes, bad lengths, junk header, CRLF, a cyclic tree, damaged kids, a filter chain,
and malformed ones) and their expectations, which are known by construction and
cross-checked against `pdfinfo` (poppler) where it can read them; the Chrome and
Ghostscript fixtures take theirs from `pdfinfo`. Filter rows come from Python
encoders that share no code with emon. `kin/scripts/pdf-corpus.py` cross-checks
every real PDF under `corpus/` (read-only) and writes `test/pdf/corpus.txt`:
page count and a digest of every page's boxes and rotation IN WHOLE POINTS
(poppler prints two binary-rounded decimals, so an exact-centipoint digest
differs by one on 8 of 4000 files), plus files emon cannot open with their
status, as a RATCHET (repair and decryption will change them). Fixtures use
the exact-centipoint digest. `kin/scripts/pdf-sweep` runs the generated Rust over
any list of files; no gate runs these scripts, the committed files are the truth.
**Why**: accuracy on real files is the point, and an independent oracle is the
only way to know it.
**Alternatives**: committing corpus PDFs (licences); comparing text instead of
boxes (P1.6+).
- [ ] **Signed off** (maintainer)

### D067 — HTML goldens reuse the JSON goldens' Models, in four option sets, with an independent renderer that reads the JSON
**Decision**: `test/html/NAME.{html,pretty.html,full.html,bare.html}` for each
`test/json/NAME.model`: the same Model `emon.jsontest` builds (the script
parser is now the non-test module `emon.mscript`, which `emon.jsontest` and
`emon.htmltest` both require; Go cannot import a test package), emitted under
defaults / `pretty` / ids+geometry+confidence(<950)+ledger / furniture dropped
and milestones off. A new `corners` Model (with its JSON goldens, also checked
by `emon.jsontest`) covers what `kinds` does not. `kin/scripts/html-check.py`
renders the JSON goldens with no code shared with `kin/html.kin` and requires
the committed bytes; it then parses each with html5lib in strict mode (any
parse error fails) and compares the element census and the plain text of
structure-free headings, paragraphs and code with the JSON. `bb unit` runs it
first; `html5lib` is installed once into `~/.cache/emon/pylib` like
`jsonschema`, and its absence is a loud SKIP of the parse step only.
**Why**: DEVLOG P1.9's done-when; reading the JSON rather than the Model makes
the check that the HTML says what the JSON says, and needs no second Model
parser. **Alternatives**: a Python Model builder (json-check already has one,
but HTML is a projection of the JSON's content so the JSON is the simpler
input); goldens for the three fixtures only.
- [ ] **Signed off** (maintainer)

### D068 — HTML escaping: the four characters, U+FFFD for what HTML forbids, raw CR; ids on blocks only
**Decision**: SPEC 5.2's `& < > "` (in text and attribute values alike, values
always double-quoted). Added: a code point HTML forbids in the input stream
(C0 and C1 controls except tab, LF, FF and CR; surrogates; U+FDD0-FDEF; the
last two code points of every plane) is written as U+FFFD, so the output is
valid HTML5 whatever the Model holds (PDF text layers do hold NUL and C1).
CR is written raw: `&#13;` is itself a parse error and a parser folds a raw CR
into LF. `id="emon-N"` (option `ids`) goes on block elements only, as D052's
JSON does; a `note` is always `id="fn-N"` (what `ref` points at) and never a
second id; an internal `link.target` becomes `#fn-N` for a note and `#emon-N`
otherwise (which resolves when `ids` is on).
**Why**: a scraper's parser must not see parse errors; SPEC said "nothing
else" but did not consider forbidden characters. **Alternatives**: escape
controls as numeric references (parse errors too); drop them silently (loses
the fact that something was there).
- [ ] **Signed off** (maintainer)

### D069 — The mapping SPEC 5.1 left open
**Decision** (`kin/html.kin`; all else is SPEC 5.1 as written):
`html-emit` takes `opts` (a bitset: `ho-ids 1`, `ho-geometry 2`,
`ho-confidence 4`, `ho-ledger 8`, `ho-pretty 16`, `ho-furniture-drop 32`,
`ho-no-milestones 64`) and `conf-below` (nodes with `0 <= conf < conf-below`
get `data-emon-conf`); `keep` and `policy` furniture are the same here
(policy is the Model's content, not the emitter's). `pretty` puts each block
element on its own line, two spaces a level, inline content on its element's
line. A heading's level < 1 is `<h1>`, > 6 is `<h6 aria-level>`, and its
`number` is written in front of its text as well as in `data-emon-number`.
An `ol` gets `start`, and `type` from `lower-alpha`/`upper-alpha`/`lower-roman`/
`upper-roman` (decimal and unknown schemes: none). Attributes the SPEC did not
list, so nothing the Model says is lost: `data-emon-marker` (item, note),
`data-emon-label` (caption, figure), `data-emon-align`, `data-emon-repeated`,
`data-emon-pages` and `data-emon-role` (furniture), `data-emon-date` and
`-color` (seal), `data-emon-display` (formula), `data-emon-type` and
`-checked` (field). A rowgroup is `thead`/`tfoot` by role, else `tbody`; a
table's caption is written first; a caption is `<caption>` in a table,
`<figcaption>` in a figure, else `<p data-emon="caption">`. A figure's
`labels` are `<span data-emon="label">`s inside it; a seal is an empty
`<figure>` whose `aria-label` is `Seal|Stamp|Signature|Initials|Digital
signature` + `: ` + its text (else its name); a furniture aside holds its
template as a `<p>` then its blocks; `ref` with no target is a `<span
role="doc-noteref">`; a kind with no mapping would be a `<div
data-emon-kind>` (none is left). The ledger is one inert `<template
data-emon="dropped" data-emon-reason data-emon-page>` per row, at the end of
`<main>`. `<title>` is always present (empty if the Model has none);
metadata are `<meta name>` with the JSON key as the name.
**Why**: each is the plainest reading of SPEC 5.1/5.2 that keeps the Model's
information in a form a crawler can read. **Alternatives**: `<h2>` for an
unnumbered heading (inventing a level); `<dl>` for a figure's labels.
- [ ] **Signed off** (maintainer)

### D070 — A `deftest` helper asserts by taking the test handle; the gates task list is read whole
**Decision**: `emon.test` has a tag `TestT` (`*testing.T` in Go, an inert `I32`
elsewhere) and a form `(test-t)` (`emonT` in Go, `0` elsewhere). A helper that
asserts declares a parameter named `emon-t` (Go spells it `emonT`, the name the
assert templates use) and the law passes `(test-t)`: `emon.htmltest`'s
`ht-golden` is the first. Also, `kin/scripts/gates` read `bb.edn`'s task names
into a newline-separated string and tested membership with space-delimited
patterns, so the newlines were never spaces and it printed `gate X is not a
bb.edn task` while every score was ok; it now reads the names as one
space-separated line (and finds `gen`, whose line starts with the map's `{`).
**Why**: the audit block in "Found in kin" (this was emon's vocabulary, not
kin); a gate that cries wolf is a gate nobody trusts (D057).
**Alternatives**: templates reading a scoped frame (kin has none); the helper
answering a code for the law to assert (what P1.2 did).
- [ ] **Signed off** (maintainer)

### D071 — The synthetic corpus: 36 semantic HTML documents, printed once by Chrome 154, committed beside their gold
**Decision**: `test/corpus/synth/` holds `html/` (36 documents, authored by
`tools/synth/make_html.py`, which is authoring tooling: the committed HTML is
the corpus), `pdf/` (headless Google Chrome 154.0.8037.59 `printToPDF`, Skia/PDF
m154, **untagged** via `--disable-pdf-tagging`), `pdf-tagged/` (3 documents
printed tagged, for later struct-tree work), `scan/` (image-only degraded
variants: 150 dpi, per-page skew up to 1.2 degrees, Gaussian noise, blur, JPEG
q50; seeded, so a rerun is pixel-stable), `gold/` and `MANIFEST.json`
(producer, version, tool versions, sha256 of every file, and the layout facts
below). Coverage: headings to depth 4 plain, decimal-numbered, keyword-numbered
and roman/alpha-numbered; two documents where the level 1 heading prints
SMALLER than level 2 (and level 3 smaller than 4), and one where headings are
`<div data-level>` of body size; lists (nested, schemes, start/value, 60 items
over 3 pages); ruled, whitespace, partial/spanned and shaded tables; multi-page
tables with repeated `thead`, with a non-repeating `thead`, and with none, and
two distinct tables on consecutive pages; `@page` margin-box running text
(title + "Page n of N", odd/even with a different first page, an informative
court-filing header with invariant parts and a per-page Bates number) and
`position:fixed` furniture; footnotes (page-bottom) and endnotes; bar/line
charts, a diagram, a photo and a blank chart placeholder, logo, screenshot;
watermark (`DRAFT`, every page); a circular seal, a rectangular stamp and a
signature line; 2 and 3 column layouts; an invoice; a 3-page bank statement;
Arabic, Hebrew, CJK (Chinese and Japanese), Devanagari and a mixed-script
table. Only system fonts (Georgia, Helvetica, Courier New, Arial, Geeza Pro,
Arial Hebrew, Hiragino Sans GB, Devanagari Sangam MN): reproducible on the
same OS, which `PRODUCER.json` records. `tools/synth/print.sh` regenerates
PDFs, scans, manifest and gold; **no gate runs it or any PDF tool**. The PDFs
are NOT in LFS (`.gitattributes` only LFS-tracks `corpus/`; these are 2 MB, the
scans 6 MB, everything under 10 MB).
**Why**: P2.8 and D041: an oracle that costs nothing to label, with the
producer's idioms (margin boxes, repeated thead, fixed furniture, tagged or
not) varied so emon does not overfit one. Untagged by default because a struct
tree would hand emon the headings the round trip exists to test it finding.
**Alternatives**: one big document per feature family (worse localisation of
failures); WeasyPrint/LibreOffice as a second producer now (parked with nome).
- [ ] **Signed off** (maintainer)

### D072 — The gold format: a partial emon-1 Model plus a declaration of what is annotated (`schema/emon-gold-1.json`)
**Decision**: a gold file is `{"emon-gold":"1.0","id","provenance","annotated",
"model"}`. `model` has the emon-1 keys, `body` is an ordinary emon-1 `document`
(ids in document order, no `at`/`src`/`conf`/`flags`), `pages` carry `n`,`w`,`h`,
`rot` and the printed `label`, `furniture` items are `{id, role, template,
pages}` (`{n}` in a template marks per-page text, e.g. a Bates number), and
`dropped` items are `{reason, page, text}` with `at` optional (geometry is not
known). `annotated` says which node kinds (presence AND absence), which fields
per kind, whether `text` and `inlines` are annotated, which ledger reasons are
exhaustive, and which page fields. A metric reads `annotated` and skips what is
not claimed, so DocLayNet-style gold (no heading levels) and synthetic gold
(no geometry) fit one format. The schema reuses emon-1's `$defs`
(`emon-1.json#/$defs/document`, `page`, ...) by `$ref`, so a Model change
cannot leave gold behind silently. `kin/scripts/gold-check.py`, run by `bb unit`
(beside json-check and html-check), validates every gold, checks ids, `ref`
targets and the manifest's sha256s, and requires each gold to equal what
`tools/synth/html2gold.py` says of the committed HTML plus the manifest's layout
facts (page count and sizes; the page each row of a `data-multipage` table
landed on, found once by pdftotext at print time). A scan variant shares its
document's gold (its page sizes agree within a point).
**Why**: the converter is deterministic and stdlib-only, so the gate needs
neither Chrome nor poppler; a hand-edited gold or HTML fails the gate instead of
drifting.
**Alternatives**: gold as bare emon JSON (cannot say what is unannotated);
storing facts inside each gold (mixes evidence with truth).
- [ ] **Signed off** (maintainer)

### D073 — Conventions the gold had to choose (the SPEC is silent or ambiguous); maintainer to confirm
**Decision**, each one encoded in `tools/synth/html2gold.py`:
1. A heading's `level` is the HTML level; its printed number is `number`
   (punctuation stripped) and is not in `text`; `numbering` is `decimal`,
   `upper-roman`, `upper-alpha`, `lower-alpha` or `keyword`. Visual size is
   never consulted (SPEC 10.3: a level 1 may print smaller than a level 2).
2. A caption's `label` is the matched prefix (`Table 1`, `Figure 2`) and its
   `text` the remainder, as with headings; a figure also gets the `label`.
3. A `ref` contributes no text to its paragraph's `text`; `<br>` is a
   `linebreak` inline and `"\n"` in `text`. `inlines` is present when any
   style, link, ref or linebreak is.
4. A footnote `note` (`role: footnote`, `marker`, child paragraph without the
   marker) is where it is read, i.e. at the page bottom in document order, not
   next to its reference; each `ref` targets the next unmatched note with the
   same marker.
5. A header row that printed again on continuation pages is ONE `head`
   rowgroup row with `repeated: true`, the table has `continued` = pages
   spanned, and each continuation page has a `duplicate-header-row` ledger
   entry with the row's text. A thead the producer did not repeat has no such
   entries.
6. Page numbers: a `page-number` ledger entry per page with the printed text
   (`Page 3 of 10`, `3`) and the page's `label` set to the number when it
   printed (from the first page it printed on). A title in the running header
   is a `running-title` entry; invariant running text is one `furniture` item
   with the pages it covered; text that varies (`ACME-{n}`) is a furniture
   template.
7. Watermark text is one `watermark` ledger entry per page it printed on, and
   is not body. Seal/stamp text is the seal's `text`, not a paragraph; chart
   axis and legend text is the figure's `labels`.
8. List `marker` is the literal marker (`1.`, `a.`, `II.`, bullets by depth
   `• ◦ ▪`); `start` only when not 1.
**Why**: a gold needs one answer per case; these are the readings of SPEC
3.2, 3.7, 10.2-10.8 that make the answers checkable. Any the maintainer changes
is a converter edit and a regeneration.
**Alternatives**: leaving the cases out of the gold (the weakest metrics would
have nothing to say exactly where emon is most likely wrong).
- [ ] **Signed off** (maintainer)

### D074 — The `Src` port: a shared borrow over a backing, forms not functions for what makes one, a test source with a control surface
**Decision** (P1.11, D058 revised). (1) In Rust a `Src` is `&crate::port::Src`: a
shared borrow whose state is interior (an atomic missed flag; the backing is an
`Arc<dyn SrcBacking>`), so one source can be passed to any number of calls and
to two parameters of one call, which `Cells`' `&mut` cannot. Go `*port.Src`,
Java and C# a class. (2) Every host implements one interface for the chunk cache
(`SrcBacking`: `len`, `at` answering -1 for a non-resident byte and recording the
miss itself, `span`, `hint`); the view (`Src`) turns -1 into "answer 0, set the
flag". `view()` makes a fresh view of the same backing, which is what the engine
does per attempt. `src-slice` of a span with a non-resident part zero-fills it
and sets the flag; an index or span outside 0..len panics/throws like a `Blob`.
`src-of-blob` and `src-of-cells` copy. (3) **`ps-slice` and `ps-of-cells` are the
port forms `src-slice` and `src-of-cells`, not `emon.pdfsrc` functions**: a Rust
function cannot return a borrow of what it made (`Blob` is `&[u8]`, `Src` is
`&Src`), so both are templates that borrow a temporary, like `cells-bytes` (nest
them in the call that uses them, or bind a `Src` with `let`, which Rust extends).
`emon.pdfsrc` wraps only `ps-len`, `ps-u8`, `ps-missed`, `ps-hint`; `pdflex`'s
input is `ps-*`, `pdfdoc` refers `src-slice`/`src-of-cells` from `emon.port`
for its two uses. (4) `pdffilter` reads `Blob`s with `blob-u8`/`blob-len`, no
longer through `pdfsrc` (its input is decoded bytes in memory, D058). (5) The test
source is `test-src-chunked blob chunk sync` (none resident; sync fills inline,
async records the chunk as pending and misses), `test-src-view`, and one control
form `test-src-ctl src op a` (fetch with read-ahead 2^round - 1 chunks each
side, make resident, evict a named chunk, make all resident, and counts of
pending, resident, hints, inline fills and chunks). Hints are counted, not
applied: applying them is the engine's (P1.10). The fetch policy in the test
source is the engine's: doubling per round.
**Why**: forced by Rust's borrow rules for (1) and (3); (2) keeps the four hosts
to one small interface the engine can implement over a browser `Blob`, a URL or a
file; (5) makes "a miss" testable on four targets with no I/O.
**Alternatives**: an owned `Src` by value (not `Copy`: every second use would
clone); a function `ps-slice` returning a leaked or cached buffer (memory per
slice); one form per test operation (nine forms four times; one control form
instead).
- [ ] **Signed off** (maintainer)

### D075 — The PDF layer under a source that misses: checkpoints, `need-bytes`, check-before-classify, bounded garbage, hints
**Decision** (P1.11 retag, D058). (1) `need-bytes` is a status of `pd-status` (7),
`pl-status` (9, latched by `pl-miss`, which `pl-parse` and `pl-parse-indirect`
call when the view has missed) and `pf-status` (12). (2) Checkpoints: after each
indirect object (`pl-parse-indirect`, `pl-parse`), each xref table entry, trailer
and stream section, the `/XRefStm`, each cross-reference section of the chain,
each object load (`pd-get` does not cache what may be zeros), every
filter-chain run (before and after the stream span), each page-tree node and
kid, and `pd-pages` (answers -1 on a miss). (3) Nothing is classified from a
miss: every `bad-xref`, `no-header`, `no-startxref`, `no-root` goes through
`pd-stop src status` (answers `need-bytes` if the view missed), `pd-unreadable`
checks first, and `pd-warn-set` sets no bit on a missed view. The invariant the
laws hold: an attempt that missed answers only `need-bytes` (or `ok`, when the
miss was after the open) and only warning bits the real run has. (4) GARBAGE IS
BOUNDED: zeros, which a miss reads, are white space to the lexer, so a scan
would run to the end of the source asking for all of it. `pl-stalled` (checked
every 1 KiB) stops the white-space/comment skip, literal and hex strings,
and the `endstream`/`endobj` scans at the first sign of a miss, and the
`startxref` search does not go on to its 64 KiB fallback after a miss.
Without it the 512-byte law over the corpus took 11 s in release, with it 0.45 s.
(5) Hints: once the xref is read, `pd-hint-extents` hints every in-use object's
extent (its offset to the next larger offset, or the end), sorting the offsets in
a scratch `Cells` with an in-place heap sort (no recursion). It runs for every
source, in-memory included; opening all 3,689 corpus PDFs went from 61 ms to
149 ms (a cost of the sort, the dynamic read and the per-file copy in the
sweep), which P1.10 may skip with a "hints wanted" flag if it matters.
(6) No state lives outside the caller's `Cells`; `pd-open` re-initializes `ar`
and `dc`, so an attempt restarts on the same Cells and the kept run equals the
in-memory run cell for cell (the law compares `ar`, `dc` and the page table).
**Why**: a run that ignores these gives a status that is the miss's, not the
file's; the gate compares arenas, so a leak shows.
**Alternatives**: a check in every read (every in-memory byte pays); returning
statuses only from the top (garbage between miss and checkpoint is unbounded,
see (4)); hints as one whole-file range (defeats lazy reading).
- [ ] **Signed off** (maintainer)

---

## Found in kin, not ours to fix here

**Audit (2026-09-30, coordinator; checked against kin's source and nome):**
- Forward refs / mutual recursion — **WRONG**: `declarefn` (kin `src/kin.cljc` `declare!`).
- Rust `&mut Cells` (can't pass twice, nested read E0499) — **emon's own tag, not kin**:
  the `&mut` is the `:rust` string of emon's `Cells` tag. Fix in emon: add a
  read-only tag (e.g. `CellsRo` → `&crate::port::Cells`, host accessors `&self`);
  writers keep `^:mut ^Cells`. Until then, bind inner reads first.
- `let` function-flat in Java/C# — **REAL**. Workarounds: unique names, or
  `(local ^:mut ^T x)` + `set`, or a per-target `:local-name` hook.
- `I32` predicate as `if` test — **not a gap**: declare predicates `^Bool`
  (`(defn ^Bool p ...)`) or use the `(== (p x) 1)` idiom.
- `deftest` helpers can't assert — **emon's `emon.test` vocab, not kin**: Go
  assert templates hardcode `t`; fix in `emon.test` (helpers take `t`, or
  templates read a scoped frame).
- Go refuses `len`/`cap`/`new` — **REAL, by design** (`go-reserved`); rename.
- No source label on the generation context — **REAL, kin one-liner**:
  `render-open` (kin `project.cljc`) never sets `:kin/label`, though
  `kin.cljc` already reads it. Maintainer to decide on patching kin.
- Recursive `disk-vfs` listing — **REAL**, deliberately minimal glob.

Things emon needs that might belong in `../kin` rather than here. Record
them; offer them upstream when they have proved general.

- The `emit-ascii` generation-time literal check (D028) may be general.
- kin's generation context carries no source label (only `:kin/ns`), so a
  form's refusal cannot name the file, only the namespace (D042).
- A recursive `kin.vfs/disk-vfs` listing, if emon's sources ever need more
  than one directory level (nome hit the same one-level limit for host scans).
- `let` bindings are flat per function in the Java and C# targets: binding
  the same local name twice in one function (even in separate nested `let`s)
  emits two declarations (`long w = ...` twice) and fails to compile there,
  while Rust and Go shadow. emon works around it with unique names (P0.4 laws).
  Worth either scoping the Java/C# emission in braces or refusing the
  duplicate at generation time.
- `Cells` is `&mut` in Rust (emon.lang's tag), so a function that only READS
  two arenas still cannot be given the same arena twice; a `&` tag for
  read-only parameters would need kin to tell read from write.
- The same tag makes `(f cells (g cells))` fail in Rust (E0499: the outer
  call reborrows `cells` mutably before the inner read), and every serializer
  of a Model then needs `&mut` access to something it only reads. Both are the
  read-only-parameter question again (P1.1, P1.2).
- ~~kin has no forward reference~~ **CORRECTED (maintainer, 2026-09-30):**
  kin supports forward references and mutual recursion via `(declarefn sym)`
  (same-namespace) and `(declarefn other.ns/sym)` (cross-namespace), with
  Clojure `declare` semantics — see `declare!` in kin's `src/kin.cljc`; nome
  uses it (e.g. `kin/geom.kin`). The earlier note was wrong. emon's serializer
  (P1.2) may be split into mutually recursive inline/block writers if clearer.
- A predicate answering an `I32` cannot be an `if` condition (`expected bool,
  found i32` in Rust only); it is written `(== (pred x) 1)` everywhere. kin has
  no `I32` -> `Bool` form, and `emon.lang` has none to borrow.
- A `deftest` helper function cannot use `assert-*`: the forms expand to the
  test parameter (`emonT` in Go) which is only in scope in the `deftest`
  itself, so a shared builder must answer a code and the law asserts (P1.2's
  `jt-build`).
- Go refuses `len` (and `cap`, `new`, ...) as a parameter name at generation
  time, with a clear message; Java and C# would accept `len`. A source that
  names a local `len` is portable only if it is checked for Go, which it is.
- A `defconst` named `PG` and a parameter named `pg` collide in Go (generated
  package-level names are folded, so the compiler reports `mismatched types
  int32 and *port.Cells`); nothing at generation time says so. Named the
  constant `PAGE_STRIDE`. Worth a generation-time check of constants against
  parameters per target.
- A `let` binding that is never read is a compile error in Go only (`declared
  and not used`); the other three targets accept it. Tests that bind a scratch
  cell they do not use fail on Go alone.
- Go also refuses a local named `go` (besides `len`, `cap`, `new`), found at generation time, as
  intended; the Rust borrow rule bites a kin function called INSIDE another call on the same
  `Cells` (`(cells-set f i (g f))`, `(pl-next ar (pl-first ar h))`): bind the inner result first
  (six sites in `pdffont` had to be).
- `Bitmap` in Rust (P2.7): a pixel buffer both read and written by kin wants a SHARED
  borrow (a source and a mask in one call), so its bytes are `Cell<u8>` behind `&Bitmap`;
  `Cells`' `&mut` tag is the same read-only-parameter question again (the labeller passes
  three `Cells` and only works because they are distinct arenas).
- `var` (a Go keyword, a C# contextual one) as a kin local name was avoided by hand (`vr`);
  worth a generation-time check beside `len`/`cap`/`go`.
- A `(while 1 ...)` with statements after it would be unreachable code in Java
  (a constant condition); kin has no `loop`/`forever` the four targets agree
  on, so loops with several exits use a `done` flag.

## Open questions for the maintainer

- [x] **OCR default**: `always`, every page, born-digital included (D038).
- [x] **Sharing with nome**: none; independent projects (D039, D024 struck).
- [x] **Crawl mix**: mixed; D031's phase order stands.
- [x] **Encrypted PDFs**: extract, ignore permissions; `password-required`
  error; caller-supplied password (D040).
- [x] **Java toolchain**: a JDK exists at `/opt/homebrew/opt/openjdk` and
  `bb check` already runs the Java arm.
- [?] **Figure crops**: placeholders only for now (D020); opt-in pixels
  undecided (SPEC §17.1).
- [x] **Inputs**: Blob-like byte sources with async slice reads; lazy range
  reading required; PDFs and images may be mixed in one conversion (D058
  revised).

## Parking lot

- **nome as an optional corpus producer** (D041): allowed by the maintainer
  as an external dev-time tool; deferred until nome is complete.
- **A kin PDF renderer**, written in emon (nome's rasteriser as reference
  only) (D007, D039, SPEC §17.2).
- **Learned components as recorded host ports** — layout detection (a
  DocLayNet-trained detector), table-structure models — admissible only
  through plan/fulfil so identity still holds (SPEC §1.1).
- **Exporters**: hOCR, ALTO, PAGE XML, Markdown, JSON Lines.
- **Streaming stage 3** for very large documents (D022).
- **MathML** for formulas; **QR/barcode decoding**; **CCITT/JBIG2 in kin**.
- **Language identification** from text via n-gram tables (D021).
- **Key/value extraction** for scanned forms (FUNSD-style) beyond AcroForm.
- **Other inputs**: XPS/OXPS (fixed-layout like PDF), DjVu.
- **Signature validation** (cryptographic) — out of scope unless asked.

### D076 — Accessibility data (tagged PDF) is captured from P1.7, used from P3.7
**Decision**: the content interpreter (P1.7) records every marked-content
span (tag, MCID, Artifact type/subtype, ActualText, Alt, E, Lang) into
evidence from the start; P3.7 then reads the structure tree and joins on
MCID. Beyond roles, emon uses: Artifact subtypes (Pagination, Header, Footer,
Watermark, Background) as direct evidence for furniture removal and watermark
dropping; heading roles as level priors; table attributes (Scope, Headers,
spans, Summary) for table structure; list numbering; figure Alt as the
placeholder's description; per-element Lang; abbreviation expansions (E);
PDF/UA identification as a trust signal raising the tree's prior. All still
verified, not obeyed (D034).
**Why**: maintainer asked to tap accessibility data where available; it is
the author's own statement of structure, and Artifact subtypes answer the
header/footer and watermark questions directly. Capturing spans in P1.7
avoids a second content pass later.
**Alternatives**: parse marked content only in P3.7 (second interpreter
pass, or a retrofit of P1.7); trust PDF/UA files outright (still wrong often
enough to need verification).
- [ ] **Signed off** (maintainer)

### D077 — The accuracy scorer: Python tooling, counts pooled then ratioed, normalization and match rule fixed
**Decision**: `tools/accuracy/score.py` (stdlib Python, like json-check and
gold-check; SPEC 14 does not require the scorer to be kin) scores a predicted
emon-1 Model against a gold file and returns COUNTS, pooled (micro-average)
per `VARIANT/CATEGORY` before any ratio is taken. It honours `annotated`: a
metric the gold does not claim is absent, not zero. Implemented: CER, WER,
bag-of-words P/R/F1 (reading-order text units joined by newline); Kendall tau
over matched blocks; per-kind block P/R/F1 (heading, paragraph, list, item,
table, figure, caption, seal, note, code, quote; presence AND absence);
heading detection P/R and heading LEVEL accuracy three ways (`-acc` on matched
headings, `-near` within one level, `-e2e` charged against every gold heading,
so a missed heading counts); paragraph-boundary P/R/F1 (word-stream alignment);
`table-teds-lite` and `table-teds-struct-lite` (cell-sequence stand-ins) and
`table-continued-acc`; figure subkind accuracy; furniture/dropped-ledger P/R
(templates expanded per page) and page-label accuracy; watermark leak
(`watermark-leak`, characters leaked / body characters). Marked stubs (in
`score.STUBS`): real TEDS, hierarchy TEDS, calibration ECE, seal name CER.
NORMALIZATION: NFC, every Unicode whitespace run is one space, trimmed; no case
folding, no locale; WER tokens are whitespace runs (CJK WER is line-level; read
CER). MATCH RULE: blocks match kind-blind and kind is scored after; with `at` on
both sides, per-page bbox IoU >= 0.5 (greedy); otherwise (the synthetic gold has
no geometry) a monotone text alignment at similarity >= 0.5 (anchored by
identical text, fuzzy in the gaps). Laws: `tools/accuracy/test_score.py`, hand
computed, run by `bb unit`.
**Why**: pooled counts make a category score a real micro-average and let a
corpus be added without re-scoring; naming the match rule and the normalization
once keeps scores comparable when P1.10 and the public-dataset importers arrive.
**Alternatives**: a kin scorer (slower to write, no benefit: it is tooling);
macro-averaging per document (lets tiny documents dominate); Levenshtein by DP
(too slow on long documents; a bit-parallel version is used).
- [ ] **Signed off** (maintainer)

### D078 — `bb accuracy` is NOT part of `bb gates` until the real pipeline exists
**Decision**: `bb accuracy` is a named non-gate in `kin/scripts/gates`. It runs a
pluggable predictor (`CMD ID VARIANT INPUT GOLD` -> Model JSON; exit 3 = not
yet available) over every committed PDF/scan of the synthetic corpus
(`pdf`, `scan`, `pdf-tagged`). Predictors: `oracle` (gold -> Model; must be
perfect, else the scorer is wrong), `degraded` (deterministically damaged gold;
proves the metrics move), and `emon` (placeholder, exits 3, reported "not yet
available" and exit 0). `bb accuracy --predictor fixtures` ratchets the two
fixtures; `--update` records improvements and new entries. The scorer's own laws
run in `bb unit`, which is a gate. When P1.10 lands: point `predict-emon.sh` at
the driver, run `bb accuracy --update` to record the first measurement (D023),
and decide whether `accuracy` joins the gates list.
**Why**: a gate that scores the oracle only proves the scorer; a gate over a
missing pipeline would either fail always or pass vacuously (nome's lesson, D818).
**Alternatives**: gating the fixtures (adds seconds and no claim about emon).
- [ ] **Signed off** (maintainer)

### D079 — The ratchet: absolute tolerance, improvements-only update, vanished metrics fail
**Decision**: `test/scoreboard.edn` maps predictor -> `"VARIANT/CATEGORY"` ->
metric -> value (six decimals), with `:tolerance` 0.002 absolute (file-level). A
run FAILS if any committed metric is worse by more than the tolerance (direction
per metric: `cer`, `wer`, `watermark-leak` lower is better), or a committed
metric/category is absent from the run. `--update` only ever moves a recorded
value toward better (plus adds new entries); regressions are never written, so
lowering a threshold is a hand edit whose diff is reviewed. A predictor with no
committed entry reports "no baseline" and does not fail. Categories are the
document id's family (headings, tables, furniture, figures, lists, scripts,
columns, footnotes, seals, watermarks, forms) and `all`. The committed board
holds only the `oracle` and `degraded` fixtures; `emon` has none until P1.10.
**Why**: SPEC 14.2 (the ratchet) and D023 (thresholds from the first measurement,
only rising).
**Alternatives**: relative tolerance (hides small-base regressions); per-metric
tolerances (premature before there are real numbers).
- [ ] **Signed off** (maintainer)

### D080 — A font is one `Cells`, built through the document layer; the Unicode of a code is decided per code, in a fixed order, and says how
**Decision**: `emon.pdffont` keeps a font in ONE caller-supplied `Cells` (a 160-cell
header, then a 256-entry table for a simple font, or sorted range tables for a composite
one). `(pfn-load src ar dc a b st f out fh)` fills it from a font dictionary handle,
following references and decoding the ToUnicode and encoding-CMap streams through
`emon.pdfdoc`; nothing else in the module touches the file. Per code the answer
(`pfn-map`, then `pfn-cp`/`pfn-how-of`) is, for a simple font, the first of: the
ToUnicode CMap when it gives a value that is not empty, U+0000 or U+FFFD (`tounicode`);
a /Differences name by the AGL rules (`glyphname`); the base encoding (`encoding`). The
base encoding is /BaseEncoding or the /Encoding name, else the built-in encoding of
Symbol and ZapfDingbats, StandardEncoding, or WinAnsi for a TrueType font with no
/Encoding; a symbolic font's built-in encoding lives in the font program, so there the
base is a GUESS (Standard/WinAnsi) and the answer says `unknown` with the guessed
value (warning bit 256). A composite font is looked up in its ToUnicode; else a
predefined `Uni*-UCS2-*`/`Uni*-UTF16-*` Encoding means the code is the Unicode value
(`encoding`); else `unknown` with no value. The `how` codes are `emon.evidence`'s `gh`
(a law holds them equal). The names `a1`..`a206` are ZapfDingbats' own and mean nothing
in another font (a Type 3 font's local glyph names are often `a1`, `a2`...).
**Why**: accuracy is the point, and what the file actually says (ToUnicode) must beat
what a table says, while a guess must be visible as one so a consumer can weigh it. The
`<0000>`/`<FFFD>` rule is for producers (pdfTeX) that write NUL for "no mapping".
**Alternatives**: a per-font rather than per-code choice (a partial ToUnicode is
common); mapping a symbolic font's codes to nothing (loses the many that are right:
the guess agreed with pdfminer on 95% of the shown characters it was made for).
- [ ] **Signed off** (maintainer)

### D081 — The AGL, the encodings, the standard-14 metrics and the CMap token ids are generated from Adobe's files by `fontdata.py`; two departures from Annex D are deliberate
**Decision**: `kin/scripts/fontdata.py` writes `kin/agl.kin` (4,482 names sorted by
bytes, so a glyph name is found by binary search and its row is its glyph id; up to three
code points each; a flag for the ZapfDingbats names) and `kin/encodings.kin`
(`enc-gid`: Standard, WinAnsi, MacRoman, Symbol and ZapfDingbats built-ins; `s14-width`:
the 14 standard fonts' AFM widths; `s14-metric`; `s14-alias`: 453 normalised BaseFont
names for them, such as `arialboldmt`, `timesnewromanpsmt`; `cm-kw`: the CMap
tokens) from the vendored Adobe files in `test/fonts/` (glyphlist.txt, zapfdingbats.txt,
aglfn.txt, the 14 AFMs; licences in the files). The encodings are DERIVED, not typed:
Standard, Symbol and Zapf from the AFMs' character codes; WinAnsi and MacRoman from
Python's cp1252 and mac_roman, named by aglfn (then the shortest AGL name for what aglfn
omits: the ligatures, the superscripts), with the PDF specification's exceptions.
`--check-ref` holds the result against pdfminer.six's copy of Annex D, typed by someone
else: 0 disagreements but three, all deliberate. Code 160 (both encodings) is `space`,
not `nbspace`, and WinAnsi 173 is `hyphen` (Annex D's footnote; what poppler and pdf.js
do; a word must split there). MacRoman also gives the Mac OS Roman characters Annex D
leaves out (notequal, infinity, ...): a file that uses them means them. The script is
checked by `bb unit` (`--check`), like `ucd.py`; Java's static-array limit is handled by
kin (the tables are chunked).
**Why**: a typed table is where an encoding bug hides; derived tables have one place to
be wrong and an independent check.
**Alternatives**: typing Annex D (what pdfminer did); fetching at build time (no).
- [ ] **Signed off** (maintainer)

### D082 — CMaps are parsed into sorted range tables: later definitions win, overlaps resolve to the nearest start, a range increments its destination's last code point
**Decision**: `pfn-cmap` reads a CMap program (decoded bytes, a `Blob`) into records
appended to the font: ToUnicode `[lo hi n seq d0..d3 cummax]` (bfchar, bfrange incl. the
array form), CID `[lo hi cid seq cummax]` (cidchar, cidrange), and up to 16
codespace ranges kept per byte (`[bytes, lo hi 16-bit halves, hi hi 16-bit halves]`, so
a 4-byte range with a first byte above 0x7F fits an `I32`). Records are heap-sorted by
(first code, definition order) and found by binary search for the last start <= the
code, then at most 64 records back while the running maximum of `hi` still reaches the
code: a later definition wins a tie, a nearer start wins an overlap, and a wide range
under many singles is still found. Destinations are UTF-16BE (surrogate pairs joined, a
lone surrogate U+FFFD, one or two digits one unit), at most four code points (a fifth
sets a warning); a range adds the offset to the LAST code point as a number (so
`<00FE>..` goes 00FE, 00FF, 0100, where a byte-wise reader would wrap). Code lengths
(`pfn-code-len`) are the shortest codespace range that fully matches the next four
bytes; with no full match, a range whose first byte fits, else the shortest range.
Unparseable or truncated input gives what was read; a CMap holds at most 100,000
records (warning bit 4). `usecmap` is ignored (bit 64).
**Why**: bounded, allocation-free, identical on four targets, and the laws pin the edge
cases (55 rows; the same questions put to pdfminer.six agree on all but the rows where
emon differs on purpose or is more tolerant).
**Alternatives**: a hash map (no allocation in kin); keeping only the first definition
(pdfminer).
- [ ] **Signed off** (maintainer)

### D083 — Widths are hundredths of a thousandth of an em; /Widths, then the standard 14's AFM, then /MissingWidth; Type 3 widths are scaled by the FontMatrix
**Decision**: `pfn-width` answers `w * 100` where `w` is in 1/1000 em (277.83 is 27783),
so real-valued widths keep their precision without floats (D009); -1 is "the font says
nothing". A simple font: /Widths for codes in [FirstChar, LastChar]; otherwise the AFM
width when the BaseFont is one of the 14 (or a common alias: Arial, TimesNewRoman,
CourierNew with their Bold/Italic/MT spellings, subset tags ignored) and the code has a
glyph; otherwise /MissingWidth; otherwise -1. A composite font: /W (both forms) by CID,
else /DW (default 1000). A Type 3 font's widths are in glyph space and are multiplied
by the FontMatrix's `a`. Ascent and descent (1/1000 em) come from the FontDescriptor,
else its FontBBox, else the standard font's AFM; a positive descent is negated. /DW2 is
kept for vertical writing (`pfn-dw2-v`, `pfn-dw2-w`); /W2 is not read.
**Why**: P1.7's glyph boxes come from widths and metrics, never from the font size.
**Alternatives**: floats (D009); thousandths rounded to integers (a hundred glyphs of
error per line).
- [ ] **Signed off** (maintainer)

### D084 — What P1.6 does not read: font programs, predefined CID CMaps and CID-to-Unicode tables, MacExpert; how it was validated
**Decision**: out of this cut and answered honestly rather than guessed: the built-in
encoding of an embedded Type 1/CFF/TrueType font (a symbolic font's codes are then
`unknown`, with a guess, and are where most of the remaining disagreement with
pdfminer.six is); predefined CMaps that map codes to CIDs (90ms-RKSJ-H, GBK-EUC-H, ...:
`unknown`, warning bit 1, and codes are split by the ToUnicode's codespace or two bytes)
and the Adobe-Japan1/GB1/CNS1/Korea1 CID-to-Unicode tables; MacExpertEncoding (warning
bit 8); `/W2`; CIDToGIDMap. They are P3 (PDF breadth). VALIDATION: no content
interpreter exists yet, so `kin/scripts/font-corpus.py` takes the codes the pages show
from pypdf (reading each page's content stream, three Form levels deep, following `Tf`)
and compares emon's `pfn-map`/`pfn-width` (the generated Rust, `kin/scripts/font-sweep`)
with pdfminer.six's `to_unichr`/`char_width` on the committed 130-file sample and a
deterministic extra sample of the corpus. Both libraries are installed by hand into
`~/.cache/emon/pylib` (as the JSON check's are) and are not dependencies of anything
that runs; `test/fonts/real.txt` is the ratchet (up to eight agreed codes of 160 fonts,
held by `bb unit`).
**Why**: an independent oracle is the only way to know the mapping is right, and the
disagreements it finds are a map of what P3 must do.
**Alternatives**: comparing with poppler's text (needs P1.7 and layout to align);
a larger ratchet (the corpus files are not committed).
- [ ] **Signed off** (maintainer)

### D085 — Corpus storage: an S3-compatible object store driven by `bb`, one zip per category, no LFS
**Decision**: the 12 GB `corpus/` is gitignored and shared through any
S3-compatible bucket (AWS, R2, MinIO, B2) by `bb corpus-push` / `bb
corpus-pull` (`tools/corpus.clj`, pure babashka: SigV4 by `javax.crypto`,
`babashka.http-client`; path-style URLs by default, virtual-hosted by
`EMON_S3_PATH_STYLE=false`). Config is a gitignored `.env` (`.env.example`
committed). One `<category>.zip` per top-level corpus dir (plus `_root.zip`
for MANIFEST/fetch.sh; `_dl/` excluded) and an `index.json` (name, size,
sha256, file count, push time, commit). Zips are deterministic (sorted,
fixed mtimes, level 6), so sha256 equality means "unchanged"; a local state
file with an mtime fingerprint avoids even zipping. Uploads over one part
(64 MiB) use multipart with per-part retries and abort on failure; pull
verifies sha256, extracts aside and renames, and takes category names for
partial pulls. `.gitattributes` (the LFS rules) is deleted; `.env` and
`corpus/` are ignored. Usage: SPEC 15.2.
**Why**: the maintainer's LFS quota is tiny; per-category archives give
partial pulls and small retries; no external CLI means nothing to install.
**Tested** (not a gate; needs MinIO): `bb corpus-test` runs AWS's SigV4
vectors, then push / re-push / one-file-change / pull / partial-pull on a
fake corpus against a local MinIO. Costs of determinism: zip entries lose
mtimes and empty directories; `.sh` files are re-marked executable on pull.
**Alternatives**: Git LFS (quota); one monolithic zip; rclone or the aws CLI
(a dependency); committing the corpus.

### D086 — Public datasets: licence review; nothing from any of them is committed
**Decision**: each source's licence was verified from its primary source
(`test/corpus/DATASETS.md`). Research-only, non-commercial, share-alike (GPL,
CC BY-SA, CC BY-NC-SA), missing or unverified licences mean fetch-only. Two
manifest errors corrected: XFUND is CC BY-NC-SA 4.0 (not MIT); FinTabNet_OTSL's
card says `other` (CDLA-Permissive-1.0 is believed for IBM's original, not
verified). By licence alone, committable would be: everything for CORD,
katanaml, agami, bank-statement-benchmark, IRS and the US-government PDFs;
derived gold only for DocLayNet, ICDAR 2013 and CUAD; nothing for the rest.
**We commit none of it**: Git LFS is unavailable (maintainer), so only importers,
manifests and fetch scripts are committed; `/corpus` is git-ignored, importer
output stays under `corpus/_gold/`. Options without LFS, none set up: a pinned
HF revision plus sha256 per file in `fetch.sh`; release assets or a data repo
for the small permissive sources; an object-store bucket.
**Why**: a wrong guess on a licence is irreversible in git history; nothing
needs the content in git since `fetch.sh` reproduces it.
**Alternatives**: commit derived gold for the permissive sets (derived text and
geometry inherit the source's terms, and source-page copyright is not covered
by CDLA); commit small samples (same, and size).
- [ ] **Signed off** (maintainer)

### D087 — Importer conventions: what the gold claims, units, and the pseudo-fields `at` and `text`
**Decision**: importers write `annotated` from what the source truly labels:
no structure is claimed that the source does not annotate (SROIE/CORD claim no
structure; pdfa-eng-wds claims only its page table because its lines are the PDF
text layer with heuristic reading order, i.e. silver). `attributes[kind]` may
list `at` (region annotated) and `text` (the node's own text annotated); the
global `annotated.text` is set only for SROIE. PDF-backed sources are in points
after converting their own origin (ICDAR 2013 is bottom-left; DocLayNet is a
stretched 1025x1025 canvas; pdfa fractions); raster sources are in pixels with
the page at a nominal 1 px = 1 pt, `px` recorded, `dpi` unclaimed, so a scorer
rescales by page width. DocLayNet furniture stays in the body as `furniture`
blocks because the gold's `model.furniture` has no `at`; FUNSD/XFUND questions
become `field` nodes (name, value from linked answers), headers become headings
without level; key-information labels (SROIE, CORD `gt_parse`) are not imported.
Each transform was checked against the PDF (`pdftotext -bbox`) or an overlay
(`tools/import/spotcheck.py`).
**Why**: the gold format lets a metric skip what is not claimed, so honesty in
`annotated` is the whole value of an importer; geometry is most of what the
public sets know.
**Alternatives**: extending the gold schema with `at` on furniture and a
`units` field (a schema change for the maintainer); importing KIE labels as
fields (not document structure).
- [ ] **Signed off** (maintainer)

### D088 — Seal/stamp truth: mapo80/stamps fetched and imported; signatures remain a gap
**Decision**: Tobacco800, StaVer and SPODS need logins. `mapo80/stamps` (HF,
CC-BY-4.0, anonymous) is added to `corpus/` (val + test, 1571 images, YOLO
stamp boxes), `fetch.sh stamps` and the manifest, and imported to `seal` nodes
(subkind `stamp`, region). Fetch-only: its positives aggregate community sets
with unverified licences and its 250 negatives come from RVL-CDIP and FUNSD.
It labels stamps only, so a scorer compares emon `seal` nodes of subkind
`stamp` (signatures on an image are absent from the gold). Exact seal text,
name and date come only from the synthetic corpus (`tools/synth`, `data-seal`).
Signatures: `tech4humans/signature-detection` (Apache-2.0, on HF) is a
candidate, not fetched.
**Why**: a real-stamp detection set is the only way to see stamp recall on
real scans; text-bearing truth is synthesized.
**Alternatives**: synthesize only (no real-world check); register for the
login-gated sets (not reproducible by script).
- [ ] **Signed off** (maintainer)

### D089 — The plan/fulfil records: length-prefixed `Cells` runs, ids chosen by kin, `bytes` made by the host and never transcribed
**Decision** (P2.5, D008, D058 revised). A request is `[len kind id args...]` and an
answer `[len kind id status args...]` in a `Cells`, `len` counting the whole record.
Kinds: render 1 (input page dpi layer), decode 2 (input offset length filter frame),
ocr 3 (bitmap psm flags x y w h lang...; languages packed four ASCII chars to a cell),
bytes 4 (input offset length). Render and decode name their input by INDEX and the
host reads the bytes from its own source. A bitmap's handle is its answer's id (an
OCR request names "the bitmap of request N"); kin never sees pixels, only `w h
channels dpi` (and from P2.7 the `Bitmap` forms). OCR words are `x y w h conf line
block len cp...` in the request bitmap's pixels, confidence x10. A failure is an
answer (`failed`, `unsupported`) and is transcribed; a MISSING answer is "not asked
yet". A unit answers `done` 0, `requests` 1 or `need-bytes` 7 (the number D075 gave
`pd-status`); anything else is the unit's own error. A `need-bytes` attempt is
discarded whole, requests included. `bytes` requests are made only by the host
loop, from the `Src`'s misses (`Misses`), fulfilled by a SEPARATE fulfiller that is
never the transcript, given negative ids, never recorded, never a round and counted
only against `fetch-rounds`. The loop refuses a unit that returns `done` with
requests, `requests` with none, a request kin may not make (`bytes`), a repeated or
already-answered id, or an answer whose id or kind answers nothing asked.
**Why**: a length prefix makes a run of records serializable and walkable in any of
four languages with no schema; ids chosen by kin let it find its answer with no
host cooperation; a separate bytes path makes "the input is not an opinion" a
property of the structure, not a filter.
**Alternatives**: fixed-stride records (OCR words are variable); answers keyed by
position (a re-run that asks in a different order would misread); `bytes` as an
ordinary transcribed kind (breaks identity across sources, D058).
- [ ] **Signed off** (maintainer)

### D090 — The transcript: a directory of `transcript.edn` and one lossless PNG per bitmap, keyed by the whole request record
**Decision**: `transcript.edn` is `{:emon-transcript 1 :inputs [{:index :length}...]
:entries [{:round :kind :req [...] :ans [...] :bitmap "bitmap-NNNN.png"}...]}`, one
entry per line, a fixed spelling (EDN subset: maps, vectors, keywords, integers,
strings), entries ordered by (round, request id) whatever order the answers arrived
in, so a recording is a function of the run alone and two hosts write the same
bytes. A replay looks an entry up by the WHOLE request record (id included); a
request with no entry is `transcript-miss` (never a guess), a recorded entry never
asked for is reported, inputs must match in count and length, and the password, the
input bytes, their origin and the fetch rounds are never written (SPEC 13.1).
Bitmaps are 8-bit gray or RGB PNGs, unfiltered, in STORED deflate blocks: the file is
a function of the pixels, needs no compressor (emon has an inflater and no
deflater, and two compressors would not agree byte for byte), and any PNG reader
opens it; the decoder reads the five filters but refuses compressed blocks
(`png-compressed`). The price is size (8.7 MB for a gray 300-dpi A4 page);
a compressed writer is a follow-up (P2.6 will want it). The library never names a
filesystem: `Transcript` is named files in memory and the driver moves them. The
Rust and Go hosts carry the port `Bitmap` (P2.7; the provisional `PlanBitmap` is gone).
**Why**: EDN is what the project already reads and diffs (DEVLOG, gold files); PNG
makes bitmaps viewable; identical bytes across hosts is the strongest evidence that
identity holds (and it is what `bb plan` checks).
**Alternatives**: JSON (no comments, same cost, not the project's idiom); raw pixels
(not viewable); zlib-compressed PNG (host-dependent bytes); keying by id alone (a
unit that changed its mind about what it asks would be answered wrongly).
- [ ] **Signed off** (maintainer)

### D091 — The host loop: start all, take completions in any order, `max-rounds` and `fetch-rounds` are named limits that live with the loop
**Decision**: per round the loop STARTS every request, then takes one completion
per request in whatever order they arrive (`Fulfiller::start`/`next`; a sync host
finishes inside `start`, a threaded or JS host whenever), sorts by id, appends to
the answers and re-runs the unit from scratch on all answers so far. `max-rounds`
(default 3, SPEC 13.1) counts rounds that ask; asking for round max+1 is the named
breach `max-rounds` (limit, actual). `fetch-rounds` (default 24, D058) counts the
D058 fetches since the last kept attempt and is named the same way. Breaches are
returned errors, not panics, and live in the loop's config rather than the port's
`limit-of` table (kin never reads them and the port files stay untouched; the
engine (P1.10) builds the `Config` from settings). The shape keeps the await loop
in the host, so the JS/WASM host is the same loop over promises; Java and C# get it
when the engine reaches them. Test doubles: a deferred fulfiller (reverse order) on
Rust, goroutines with pseudo-random delays on Go.
**Why**: the order answers arrive must not matter (D008's concurrency promise), and
the tests must be able to make it differ; limits as errors are what D030 wants
(failure per unit, not a crashed process).
**Alternatives**: limit kinds in the port table (touches the four port files, and
P2.7 edits them); panicking breaches (the engine would have to catch them).
- [ ] **Signed off** (maintainer)

### D092 — Per-pixel work stays in kin over `bitmap-u8` and `Cells`; five bulk forms are adopted for the streaming passes (P2.7)
**Decision**: the tracer and the normaliser (SPEC 8.2) are written in kin over the `Bitmap`
port (`bitmap-u8`, `bitmap-set-u8`) and `Cells`; they are not hand-written per host. Five
bulk forms are added, each because one measured stage broke the 2x rule (SPEC 12.3) and each
replacing a whole-page loop that holds no kin-side state: `bitmap-histogram` (b, cells, off),
`bitmap-threshold-into` (g, t, mask; answers the ink count), `bitmap-integral` (b, sum, sq; the
two integral images of channel 0 and its square, low 32 bits, sized (w+1)(h+1)),
`bitmap-next-nonzero` (b, x, y; the next ink pixel of a row, or the width), and `cells-grow`
(c, n, v; n copies appended). They are declared in all four host trees, law-tested, and
`bb hosts` agrees on them. `bitmap-row-copy` was tried and dropped: nothing measured wanted
it. Rule for the next form: a pass earns one only if it is a pure whole-page map or reduce AND a
measurement shows it over 2x; a pass that carries state in `Cells` (labelling, union-find,
Sauvola's window) stays in kin.

**What was measured** (`bench/`, not a gate; `bench/run.sh N port|bulk|skip`, `bb bench/report.clj`).
Workload: Otsu + binarise + 8-connected labelling (two-pass union-find, labels/parents/areas in
`Cells`), and Sauvola (r=15, k=87/256, integer) over two integral images + labelling, on a
2481x3508 grayscale page (`corpus/formats/derived/inv_00007.noise.png`, the scanned katanaml
invoice with scan noise, 300 dpi A4; made by `bb bench/mkpage.clj` through ImageMagick; a clean
page, `inv_00000.png`, was run as well). The comparator is `bench/native/native.rs`, hand-written
safe Rust (no port calls, no `unsafe`), the same algorithm. Release builds (rustc opt-level 3,
`go build`, HotSpot 25 default, .NET 10 Release), best of 20, Apple M1 Pro. **Every arm produced
identical threshold, component counts, areas and label checksum, on both pages and both
pipelines** (e.g. noisy page: threshold 129, 155568 components, 266461 ink px, largest 958,
checksum -285559633/1150869932; Sauvola 274254 ink px, 155391 components).

Ratio to the hand-written Rust comparator, everything per pixel through the port (`port` mode),
noisy page:

| stage | rust-kin | go-kin | java-kin | csharp-kin | native (ms) |
|---|---|---|---|---|---|
| histogram (256 bins) | 0.99 | 1.00 | 1.02 | 1.01 | 18.4 |
| binarise (threshold into mask) | 1.04 | 23.8 | 17.9 | 23.2 | 0.48 |
| allocate labels (push loop vs `vec![0; n]`) | 12.3 | 61.0 | 18.9 | 21.6 | 0.30 |
| label (2-pass union-find, 8-conn.) | 0.91 | 1.12 | 1.16 | 1.29 | 14.5 |
| **Otsu pipeline total** | 1.06 | 1.93 | 1.49 | 1.65 | 33.7 |
| integral images (sum and square) | 0.73 | 1.02 | 1.20 | 1.34 | 20.0 |
| Sauvola threshold (8 `Cells` reads per pixel) | 1.00 | 1.81 | 1.35 | 1.33 | 41.3 |
| **Sauvola pipeline total** (+ alloc, label) | 0.95 | 1.70 | 1.34 | 1.41 | 76.9 |

After the bulk forms (`skip` mode: `bitmap-histogram`, `bitmap-threshold-into`,
`bitmap-integral`, `cells-grow`, and labelling that visits only ink via `bitmap-next-nonzero`):

| stage | rust-kin | go-kin | java-kin | csharp-kin |
|---|---|---|---|---|
| histogram | 0.28 | 0.28 | 0.26 | 0.27 |
| binarise | 1.54 | 9.4 | 6.4 | 15.3 |
| allocate | 1.0 | 1.0 | 1.0 | 1.9 |
| label | 0.92 | 1.08 | 1.01 | 0.79 |
| **Otsu pipeline total** | 0.58 | 0.77 | 0.68 | 0.73 |
| integral images | 0.43 | 0.31 | 0.41 | 0.53 |
| Sauvola threshold (unchanged, still kin) | 1.00 | 1.80 | 1.30 | 1.33 |
| **Sauvola pipeline total** | 0.83 | 1.27 | 1.00 | 1.03 |

Clean page (few components, white dominates), label stage: `port` 0.86 / 1.04 / 1.60 / 1.21, `skip`
0.86 / 0.95 / 0.87 / 0.36 (rust / go / java / csharp).

**Reading it.**
- The per-pixel port call itself is not the cost. Rust-generated kin equals or beats the
  hand-written comparator through `bitmap-u8` on histogram, label, integral and Sauvola (LLVM
  inlines the port and even auto-vectorises the threshold loop: 0.50 ms against the comparator's
  0.48). Go, Java and C# are 1.0-1.3x on the stateful passes (union-find, integral, histogram)
  and 1.8x at worst (Go, Sauvola: several multiplies, two divisions and eight bounds-checked
  `Cells` reads per pixel; the closest any stage comes to the line).
- The two stages over 2x are streaming loops with nothing to hold in kin: binarise (18-24x
  against a SIMD comparator; 2.8-3.7x against the same comparator compiled scalar, 3.1 ms; Go's
  hand-written slice loop is 7.3 ms against 11.4 ms through the port, so the port costs Go
  about 1.6x on a pure per-pixel loop) and zero-filling 8.7 M label cells (12-61x, a
  `cells-push` loop against `memset`). Those are the two bulk forms that pay, and the histogram
  form is worth having anyway: a host can split the bins across four counters to break the
  dependency chain, which no kin loop can (5 ms against 18 ms, every host, 0.27x the comparator).
- What is left in the bulk binarise (6-15x a SIMD comparator, 1.0-2.3x scalar) is the host's
  loop quality, not kin's; a host may vectorise it (.NET `Vector<T>`, Go assembly) later
  without touching a source. In absolute terms it is 3-7 ms of a ~25 ms pass, on a page
  whose OCR takes seconds.
- Skipping white (`bitmap-next-nonzero`) is what makes labelling proportional to ink: on a typical
  clean page it takes C# from 1.2x to 0.36x and Java from 1.6x to 0.87x. Rust and Go gain little
  (their per-pixel scan was already cheap).

**Effect on the path tracer's design (SPEC 8.2, P6.3/P6.4).**
1. Stays in kin: binarisation decisions that read neighbourhoods, integral images, Sauvola,
   connected components, and by the same cost profile (Cells reads, integer arithmetic) distance
   transforms, projection profiles, run and contour tracing. One source, four hosts,
   identical output, as demonstrated.
2. A pass over the whole page that only maps or reduces (thresholds, histograms, fills,
   colour-layer masks, downscaling) goes through a bulk form, written once per host. Expect
   two or three more of these at P6.3 (RGB to gray or to an ink-layer mask reads three
   channels per pixel and was NOT spiked here; measure it before assuming it is fine).
3. Write tracer passes sparse: iterate ink with `bitmap-next-nonzero` and runs, so work is
   proportional to ink (1-3% of a page) rather than to 8.7 M pixels.
4. Memory, not speed, is what a page-sized kin pass costs: per-pixel labels are 35 MB and the
   two integral images 70 MB as `Cells` (32-bit cells). If that bites, the escalation is a
   run-based labeller over a `bitmap-row-runs` form (not built; nothing measured wants it).
5. Sauvola's window reads are the one place Go is near 2x; if Go's ratio degrades, the lever is
   a host `bitmap-window-stats`-style form, not hand-writing the tracer.
6. The `Bitmap` contract (SPEC 7.2) is now concrete: 8-bit, `channels` and `stride`, row-major
   top-left; the bounds check is on the byte index, not each coordinate (an `x` past the row
   reads the next row); writes are allowed. In Rust a `Bitmap` is a shared borrow over `Cell<u8>`,
   so a source and a mask can be passed to one call, which `&mut Cells` does not allow.

**Caveats**: one machine (Apple M1 Pro), one page and one degraded variant of it (the corpus
holds no RVL-CDIP page; the noisy page is the nearest to a scan), default JIT settings (no
warm-up tuning beyond best-of-20), a SIMD-capable comparator (the `rust-native-scalar` arm
in `bench/run.sh` is the same source with the vectoriser off; Go/Java/C# do not vectorise these
loops). The comparator's `binarise` and `histogram` are idiomatic iterator loops.
**Why**: SPEC 12.3's rule is measurement; the spike says the port is cheap enough and that the
only stages worth a bulk form are whole-page streaming ones.
**Alternatives**: bulk forms for everything (rejected: labelling and Sauvola are within 2x, and a
per-host tracer is four implementations that must agree byte for byte); no bulk forms
(rejected: binarise and zero-fill break the rule by 18-60x in three hosts); a run-based
labeller now (premature: nothing measured wants it).
- [ ] **Signed off** (maintainer)
