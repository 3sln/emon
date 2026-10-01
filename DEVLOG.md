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

After these: P1.2 (canonical JSON) → P1.10 in order, with P1.12 (evidence
v2, D096) taken right after P1.7 merges (P1.11, the `Src`
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

- [x] **P1.7 — Content-stream interpreter, text subset**
  (`kin/pdfcontent.kin`, `kin/fx.kin`).
  *Done (P1.7)*: `kin/pdfcontent.kin` (`pc-init`, `pc-page`, `pc-document`), `kin/fx.kin`, `kin/contentrows.kin`, the
  evidence `spans` arena (D101-D105). Every text operator, Form XObjects, every marked-content span (tag, MCID,
  Artifact class/Type/Subtype, ActualText, Alt, E, Lang; inline or `/Properties`), words and lines (right-to-left
  in logical order). Same on four targets (150 laws; `bb unit red` fails in every arm); through a 512-byte async
  source the evidence is the in-memory run's cell for cell (36 synthetic, 3 tagged, 8 fixtures, 3 P1.5 fixtures,
  120 real pages). **Synthetic documents**: every one of the 64,127 characters of the 36 HTML sources has its glyph
  but 37 (Devanagari vowel signs Skia gave no ToUnicode; one CJK variant), and the 1,116 glyphs not in the HTML
  are the printer's (furniture, markers, page numbers, watermark): `kin/scripts/content-check`. The three P1.5
  fixtures' evidence dumps are goldens; their 52 words are poppler's to 0.03 pt. **Hand-made fixtures**: 8 PDFs, every
  glyph within 1 centipoint of an exact-rational evaluator. **Tagged fixtures**: 111 spans equal pypdf's, 2,553 of
  2,553 glyphs inside an MCID or artifact span. **Real files vs pdfminer.six (536 files, 1,013 pages, no fault)**:
  glyph stream identical on 96.6 per cent of pages, 99.60 per cent of 2.01M characters; of the 1.89M glyphs emon
  is sure of, 100.000 per cent agree (the rest: symbolic fonts, D084); words precision 80.2 / recall 97.2; lines 76.4 /
  63.7; geometry 99.83 per cent of 2.01M glyphs within 0.05 pt. Learnt: 16.16 is not enough (D103); a struct of
  cells must not overlap (a lexer cell sat inside the form stack); an independent evaluator finds what a
  comparison with a peer cannot (the Rotate 90 matrix).
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
  *Note (D096)*: P1.7 writes D056's v0 evidence; do not pre-empt v2. P1.12
  migrates the interpreter and its goldens after P1.7 merges.
  *Note (P1.7 merge)*: P1.7 merged with its own `spans` arena (D101, stride 22,
  the tenth arena) working as-is; D096 was not implemented. P1.12 must fold
  `spans` into v2's `marks` table and retire the arena (done: P1.12, D127).
  *Encrypted files (P1.7 merge)*: `pdfcontent` reads every stream through `pd-stream-data`, so P3.2's
  decryption applies with no change; `test/pdf/p3/crypt-{rc4-128,aes-128,aes-256-r6}-empty.pdf` give the
  same 798 glyphs, 159 words and 6 lines as `base.pdf` (content-sweep).

- [x] **P1.12 — Evidence v2** (`kin/evidence.kin`, `kin/trig.kin`,
  `kin/evidencetest.kin`, `kin/ocrprobe.kin`, P1.7's `kin/pdfcontent.kin`;
  SPEC §8.4, D096). **Take after P1.7 merges on main**, and before P1.8 if
  both are free (P1.8 then reads v2 words and lines from the start). One
  branch; nothing else touches `emon.evidence` meanwhile.
  *Done (P1.12)*: evidence v2 (SPEC §8.4) is in (D126-D130). `kin/evidence.kin` has the 24 arenas in four bundles, the three
  heads once, 25 enums, builders, `ev-angle`/`ev-sin`/`ev-cos` from `kin/trig.kin` (generated by `kin/scripts/trigdata`, which
  `bb drift` checks), views with their transform (`vw-new`, exact at 150 and 300 dpi), `ev-feat-ocr`, and a dump (version "1") and
  a checker (`ev-check`, 92 codes) that are TABLES, not 24 emitters. `kin/pdfcontent.kin` writes v2 (baseline segments, asc/desc,
  `rot` by `ev-angle`, `z`, colour and alpha, marks with kind and optional content, a `fonts` row per font, `wbreak`, `order`) and the
  `spans` arena is folded into `marks` (D127); `kin/ocrprobe.kin` fills pages, views, runs, blocks, paragraphs, lines, words, symbols,
  alternatives and stage 1 features from the D095 extension (D128); `planprobe`, every host driver (`EvShared`, `EvText`, `EvOcr`,
  `EvTrace` structs in `test/{plan,ocr}/{rust,go}`), `kin/scripts/{plan,ocr,content-sweep}` and `tools/ocr/cer.clj` follow.
  **Laws**: 228 per arm, the same count on Rust, Go, Java and C# (`bb unit`; `bb unit red` fails in every arm): every builder's defaults,
  every `ev-check` code (the page of `et-build`, a row in EVERY arena, is broken once per row of the check table and the code
  required), the heads read alike by one function over `(Cells, stride)`, `ev-angle` exact at every multiple of 45 degrees and within
  3 centi-degrees and monotonic at every one of 36,000, `vw` round trips at 7 resolutions x 5 deskews x 4 quarter turns, the features,
  and that every cell of every stride is printed or named spare. **Goldens**: `kin/scripts/evidence-goldens` writes the page and the
  empty dumps; P1.7's three fixture dumps are regenerated; `test/ocr/h-keyword.dump` is rewritten from the COMMITTED transcript
  (`tools/ocr/redump.clj`; not re-recorded). **Nothing was lost (step 9, D129)**: `tools/evidence/v0.clj` turns each new dump back into
  v0 and `minimal`, `html-chrome`, `gs-xrefstream` and `h-keyword.dump` come back BYTE FOR BYTE (0 differences; the tolerances D096
  allows were not needed), `empty` too; `page1` was rebuilt, so it is not comparable. **Step 6 (D130)**: "correct OCR words score 0" is
  Tesseract's word confidence (stock `tesseract` gives "Chapter" 0.0 too), not a reading bug in `tess.rs`; a comment records it and what
  P6.5a should do. **Also**: `bb gates` is green (hosts, drift, lang-test, check, unit, plan, ocr and fuzz: the P2.12 smoke subset, 437 files on Rust and Go, memory
  and a 512-byte async source) and `bb unit red` fails in all four arms (228 laws inverted each); a 512-byte async source still equals
  the in-memory run cell for cell (`mk` and `fnt` included: 36 synthetic documents, the tagged and hand-made fixtures, 120 real pages). The OCR dump: 199 words, 24 lines, 972 symbols, 2,139 alternatives, CER 2.05 per cent
  as before. **Content sweep over the real corpus** (pdfminer.six, `tools/oracle/content-corpus.py` on the 535-file sample P1.7 used, 521 compared,
  1,012 pages): 99.599 per cent of 2,009,346 characters matched (the oracle's 99.60), 100.000 per cent of the glyphs emon is sure of agree,
  words 73.7 / 97.1, lines 71.5 / 65.0, geometry 99.86 per cent within 0.05 pt: EVERY figure identical to the pre-P1.12 interpreter on the
  same files (and the per-page glyph-stream digest of every page of 605 files is identical before and after: `kin/scripts/content-sweep digest`);
  the merged tree's own default sample is a different list (main added fixtures to `test/pdf/corpus.txt`) and prints 99.45; `tagged-check.py`:
  111 spans, 2,553 of 2,553 glyphs. The fonts' two spare cells are
  `/CapHeight` and `/XHeight` for P4.10 (D108), -1 until it reads them. Learnt: a dump and a checker written as data rows cost 275
  and 88 lines of numbers where the v0 versions cost a function per field; `(f cells (g cells))` and `(set c (... (get c) ...))` are
  Rust borrow errors that only `rustc` reports (five sites), so the gate that compiles is the one that finds them; Go's "declared and
  not used" found two unused locals in a law that the other three targets accepted; on a loaded machine the Rust arm of `bb unit`
  takes four times its CPU time in wall time, so read its wall clock with that in mind. *Commit*: pending (D037).
  *Brief*, in this order:
  1. **Record the v0 baseline first.** On the branch, before editing, copy
     every file that holds a v0 dump to `test/evidence/v0/` (not committed
     at the end; it is the migration's reference): `test/evidence/*.json`,
     `test/ocr/h-keyword.dump`, the plan gate's expected output if it holds
     evidence (`kin/scripts/plan`, `test/plan/`), and P1.7's three fixture
     evidence goldens (wherever P1.7 put them; `git log` on main says).
     List them in the entry.
  2. **Tables and trigonometry.** `kin/scripts/trigdata` (bb) generates
     `kin/trig.kin` (`emon.trig`, `defdata`): `atan(i/1024)` in centi-degrees
     for i = 0..1024, and `sin` in 16.16 per centi-degree over 0..9000, each
     rounded half away from zero from the double value; committed, and
     `bb drift` regenerates and compares. `ev-angle dx dy` (octant
     reduction, `i = round(1024 * min / max)`, answer in [0, 36000),
     counter-clockwise as displayed, i.e. with `-dy`; `ev-angle 0 0` is 0),
     `ev-sin`, `ev-cos` (any integer centi-degree, by symmetry).
  3. **`emon.evidence` rewritten to SPEC §8.4 exactly.** 24 arenas in the
     `ea` order of §8.4.6, strides as the tables say, spare cells zero. Field
     constants: the three heads once (`hd-*` word head 0..15, `lh-*` line
     head 0..12, `ch-*` char head 0..8), then per arena from where its head
     ends, with a prefix per arena (`gf- wf- lf- mkf- fnf- pf- mf- epf- vwf-
     ptf- rff- xf- orf- obf- olf- owf- osf- oaf- ikf- cf- rlf- pmf- tlf-`;
     rename if one collides, and say so). Feature columns `xo-*`, `xt-*` as
     §8.4.5 numbers them. New `defenum` tables: `ea vk po cs wb mk as ob op
     oe os ir rs pm ft vc` (spellings = the SPEC's words, lowercase,
     hyphenated). `ar` keeps its four rows (codes are forever); v2 writes
     only `pagination` 1, `layout` 3, `background` 4 and a new `page` 5 as
     `/Type`, and `watermark` 2 is never written again (the subtype is `as`),
     which the table's comment says. Builders `*-new` per arena taking the
     head fields and setting every other field to the SPEC's default
     (-1 absent, 0 counts, `vis` 0, `vchk` 0, alpha 1000); `*-get`/`*-set`/
     `*-count` as today; `rf-add`, `pt-add`, `etx-append-*` (unchanged),
     `mk-open`/`mk-close` (a span stack in the caller's hands: `mk-open`
     answers the row, `mk-close` fills `gcount`/`zlast`), `mk-artifact-of`
     (walk a glyph's mark chain: the `/Type` and `/Subtype` of the nearest
     artifact span, packed `type*16+subtype`, 0 none), `oa-add` (alternatives
     as a run: a symbol's alternatives must be added before the next symbol
     starts; `ev-check` enforces it). `vw-new` takes the integer parameters
     and computes the 16.16 linear part (scale `720000 * 65536 / dpi100` in
     I64, times `ev-cos`/`ev-sin` of the deskew, composed with the quarter
     turns) and its
     inverse. `vw-px-to-cpt`, `vw-cpt-to-px` (I64; `floor((v + 32768) /
     65536)` with FLOOR division — write `ev-floordiv`, `quot` truncates),
     `vw-box-to-cpt` (four corners, smallest box), `ev-overlap-small`,
     `ev-baseline-at`. Bundle inits `evs-init evt-init evo-init evr-init`
     (shared, text, ocr, trace) and `ev-check` over all four with a
     numbered code per failure (strides; each run; each reference's `ea` and
     index; the OCR nesting block ⊇ paragraph ⊇ line ⊇ word ⊇ symbol ⊇
     alternative; `feats` subject). Parameters are listed in bundle order,
     arenas in `ea` order.
  4. **The dump, version "1"**, per SPEC §8.4.6: keys from `ek` (new rows at
     the end; the `ocrwords` key 8 stays in the table unused), one array per
     arena in `ea` order, runs `[first, count]`, references `["arena", i]`,
     baselines `[x0, y0, x1, y1]`, packed ASCII as strings, `feats` rows with
     column names chosen by subject arena. `kin/scripts/json-check.py` keeps
     deriving the pretty files; check whether it knows evidence keys and
     update it if so.
  5. **Laws** (`kin/evidencetest.kin`): defaults of every builder; every
     `ev-check` code; `ev-angle` exact at every multiple of 45 degrees and
     monotonic over a sweep; `vw` round trip px → cpt → px within 1 px for
     dpi 72, 96, 150, 200, 300, 400, 600 × deskew 0, ±85, ±300 centi-degrees
     × quarter turns 0..3, and exact (no rounding at all) at 150 and 300 dpi
     with no deskew; a head-uniformity law (one generic reader over
     `(Cells, stride)` reads page, box and baseline alike from `words`,
     `owords`, `lines`, `olines`, `tlines`); the hand-built golden page
     (`test/evidence/page1.*`, rebuilt with at least one row in EVERY arena,
     a two-span nested mark, a clip, a multi-character alternative, and one
     `feats` row of each subject) and `empty.*`.
  6. **OCR (`kin/ocrprobe.kin`) reads everything the D095 extension
     carries**: one `views` row for the render (kind render, dpi 150, layer
     full) and the probe's image placement; one `oruns` row (orientation,
     writing direction, line order, deskew, OSD degrees, script, both
     confidences, languages as the request's text); `oblocks` for blocks and
     paragraphs grouped from the lines' `block`/`par` ids (box = union of
     their lines, `btype` from the lines, orientation from the run: the
     extension has no per-block value yet); `olines` with the two-point
     baseline mapped through the view; `owords` with the line's baseline
     evaluated at the word's x0 and x1 (in pixels, then mapped), style,
     language, symbol run, pixel box; `osyms` with boxes, text runs,
     confidence; `oalts` with the full candidate text. Then the answer-only
     OCR features (`xo-*` columns 2-8, 17, 19, 20, 21, 28, 29) by
     `ev-feat-ocr`, precisely: col 5 = min over symbols of (symbol conf −
     max conf of its alternatives whose text differs from the symbol's), -1
     if no symbol has one; col 6 = symbols whose margin ≤ `alt-close`
     (setting, default 100); col 8 = per-mille of adjacent code-point pairs
     that are letter↔digit or lowercase→uppercase (not at position 0), over
     ASCII and Latin-1 letters and digits only (anything else is never
     implausible), 0 for one code point; col 19 against the run's lower-median
     line `rot`; col 20 = |word baseline midpoint y − its line's baseline at
     that x|; cols 9-16, 18, 22-27 stay -1 (their stages come later; col 9
     needs a script table emon does not have yet). Also look, in
     `host/rust/src/tess.rs`, at WHY correct words score 0 (is word
     confidence read after the `lstm_choice_mode` choice iterator, or at the
     wrong level?) and record the answer; fix nothing in the host here (that
     is P6.5a).
  7. **Text layer (P1.7's interpreter) writes v2**: per glyph the baseline
     segment `Trm·(0,0)` → `Trm·(w0,0)` (`w0` the width without `Tc`/`Tw`,
     text-space units, so spacing stays measurable), `asc`/`desc` from the
     font's ascent/descent × size along the up-vector, `rot` by `ev-angle`,
     fill and stroke colour and alpha where P1.7 tracks them (else -1 / 1000;
     P3.5 fills the rest), colour-space class -1 if unknown, `z` from a
     per-page paint counter over glyphs, paths and images, `clip` -1 until
     P3.5, `mark` and `ocg` from the open-span stack, `vis` 0 / `vby` -1 /
     `vchk` 0. P1.7's marked-content records move to `marks` (every field of
     D076; if P1.7 stored them in a shape of its own, map it field by field
     and write the mapping in the entry). `fonts` gets a row per font on first
     use from P1.6's `pfn-*` readers; a glyph's `font` is that row. Words:
     baseline from the first glyph's origin to the last glyph's end, `wbreak`
     set where the grouping decides, `src`. Lines: baseline first word start
     to last word end, `order`.
  8. **Consumers**: `tools/ocr/cer.clj` reads `owords`; the comments and
     expectations of `kin/scripts/ocr` and `kin/scripts/plan`; every host
     driver that allocates the nine v0 arenas (`test/plan/{rust,go}`,
     `test/ocr/{rust,go}`, P1.7's fixture drivers, P1.10's engine if it has
     started, and any Java/C# `bb unit` arm) allocates the four bundles as
     structs named `EvShared`, `EvText`, `EvOcr`, `EvTrace`.
  9. **Migrate the goldens and prove nothing was lost.** `tools/evidence/
     v0.clj` (bb, stays in the tree, not a gate) reads a v1 dump and prints
     the v0 dump of the same evidence, by D096's v0 → v1 table; regenerate
     every golden from step 1 with the new code (the OCR transcript
     `test/ocr/h-keyword/` is NOT re-recorded: its answer is unchanged, only
     the dump is), run `v0.clj` on each and diff against `test/evidence/v0/`.
     The only differences allowed are the ones D096 lists (an OCR word's
     `baseline` ±1 cpt, from mapping before interpolating; the glyph
     `artifact` value `watermark` now reached through the mark chain).
     Report the diff counts per file in the entry, then delete
     `test/evidence/v0/`.
  *Done when*: `bb unit` (the laws above, four targets, the same count in
  each), `bb ocr` (Rust and Go replays print the identical v1 dump; the dump
  holds 24 `olines`, 199 `owords`, one `oruns` row with orientation and
  script, every symbol and every alternative's full text; CER unchanged at
  2.05%; a changed request still `transcript-miss`), `bb plan`, P1.7's
  fixture gates and `bb gates` are green; `ev-check` answers 0 on every
  golden; step 9's diff report is in the entry.
  *Not in this item*: the OCR answer extension v2 and re-recording (P6.5a),
  ink and tracer features (P6.3, P6.4), fusion's arenas (P7.1), calibration
  (P10.1).

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
  *Note (D108-D112)*: initials, columns, continuation across columns and
  alignment are NOT this item's (P4.10, P4.1, P4.11): do not special-case
  them. Two things only, so they need no rework later: write `at` as a LIST
  of regions (one per page now, one per column fragment after P4.1), and do
  not read the first line of a paragraph from content-stream order (Chrome
  paints every float, e.g. a drop cap, before the page's text: D108 (a)).

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

- [x] **P1.13 — The Model's vocabulary for initials, alignment and layout**
  (`kin/model.kin`, `kin/json.kin`, `kin/html.kin`, `kin/mscript.kin`, their
  tests; SPEC 3.2, 3.7, 3.8, 4.2, 5; D109-D111, D113). **Independent of P1.8
  and P1.12; take after P2.13's step 1 (the schema) is on main**, because
  the JSON goldens validate against it.
  **DONE (D131, D132, D133).** All five steps as briefed; `bb unit` 4 arms green (all
  four targets write the same bytes for the new goldens; `bb unit red` fails on every arm),
  `json-check.py` and `html-check.py` (the independent references) were extended first and
  write the goldens, every golden validates against `schema/emon-1.json` (1.1) and parses
  with html5lib strict, zero errors. Where it differs from the brief, D131-D133 say so:
  the option bits no longer fit the low byte of `o` (it is now ten bits and the confidence
  threshold times 1024); the layout arena is the last parameter of `model-init`,
  `model-check`, `model-hash`, `js-emit` and `html-emit` (which also takes `pages`, for the
  runs); a margin or sidebar note gets its `fn-` id when a `link` targets it as well as a
  `ref`; and a script's `ly` line adds a layout cell, `al` sets an alignment through the
  builder that refuses `justify` on a cell. Not done, because the brief did not ask for it:
  no pipeline stage writes any of this yet (P4.10, P1.10 and the columns stage will).
  *Brief*: (1) `model.kin`, codes appended, never renumbered (D050):
  `nk-initial 26 "initial"`; `fk` rows `form 76`, `lines 77`, `layout 78`,
  `band 79`, `col 80`, `box 81`; `rl-sidebar 9 "sidebar"`; a new enum
  `ifm` (`drop 1`, `raised 2`, `margin 3`); an `initial` is an inline whose
  `sub` is its form and `aux` its lines (0 = absent); `align` on heading,
  caption and cell is an attribute (`fk-align`, value an `al` code; `cell`
  never `justify`, the builder refuses it); a seventh arena `layout`
  (stride 8: page, band, col, role (`rl`: body 2, sidebar 9, margin 6), x0
  y0 x1 y1) with `lay-add` and, on the `pages` row, two spare cells naming
  the page's contiguous run (first, count). (2) `json.kin`: `initial` as
  SPEC 4.2 (after `kind`: `form`, `lines`, then `children`); `inlines` is
  informative when any `initial` is present; `align` in each kind's field
  order after its kind-specific fields; `pages[].layout` as
  `[{"band","col","role","box":[x0,y0,x1,y1]}]` in run order, omitted when
  the run is empty; the version string becomes `"1.1"` (every JSON golden
  changes by that string only; say so in the entry). (3) `html.kin`: the SPEC
  5.1 rows (the unstyled `<span data-emon="initial" data-emon-form
  data-emon-lines>`; `ho-initials-plain 128` writes the bare text;
  `ho-align-style 256` adds `style="text-align:…" dir="auto"` wherever
  `align` is; `note` roles `margin`/`sidebar` are `<aside
  data-emon-role>` with no `doc-footnote` role and no `fn-` id unless a
  `ref` targets it; with `ho-geometry`, `data-emon-col` / `data-emon-cols`
  from the page `layout` cell containing each region's centre). (4)
  `mscript` lines for the new kinds and fields; the `corners` Model gains a
  drop, a raised and a margin initial (one inside a paragraph with a
  smallcaps span after it), aligned heading, caption and cells, a sidebar
  and a margin note, and a two-page layout of two bands; `kinds` gains one
  of each. (5) `json-check.py` and `html-check.py` learn the same (they are
  the independent references, D054, D067).
  *Done when*: `bb unit` green on four targets with the new goldens (JSON,
  pretty JSON, the four HTML option sets, plus a fifth HTML set with
  `ho-initials-plain | ho-align-style | ho-geometry`), html5lib strict parse
  clean, schema-valid against the 1.1 `schema/emon-1.json`.

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
- [x] **P2.6 — Spike: OCR through the transcript** (D008, D038, D056, D089-D092, D095).
  Rust host binds Tesseract (C API), records one page's OCR into a transcript; kin
  reads the answer into `ocrwords` evidence; the Go host replays it and produces the
  same evidence dump. *Answers*: is the OCR answer layout sufficient
  (symbols, alternatives, baselines)? Does replay make identity exact?
  *Done 2026-09-30.* **Built**: `kin/ocr.kin` (`emon.ocr`, the answer's EXTENSION:
  lines with baselines, block and paragraph ids and block type; per word its style
  bits and language; per symbol its box, confidence, text and alternatives; page
  orientation, writing direction, line order, deskew, OSD degrees, script and their
  confidences; builders for hosts, readers for kin; it follows the D089 words in the
  same record, so nothing that reads the words changed); `kin/ocrprobe.kin`
  (`op-unit`: render at 150 dpi, then OCR psm 3 with symbols and OSD and NO language,
  then evidence); `kin/ocrtest.kin` (2 laws); `host/rust/src/tess.rs` (Tesseract 5.5.3
  through hand-written FFI to `libtesseract`, no crate: the C API is ~35 functions,
  and a crate would pin a build; live only, compiled under `--cfg live`, NOT in any
  gate); `test/ocr/{rust,go}/main.*` (record/replay drivers);
  `tools/ocr/record.clj` (records live; not a gate); `tools/ocr/cer.clj` (the bb CER);
  `kin/scripts/ocr` = `bb ocr`, **a gate** (replay only: no Tesseract needed).
  The recording is `test/ocr/h-keyword/` (transcript.edn 55 KB + one stored PNG 2.1 MB;
  the page is page 1 of `test/corpus/synth/scan/h-keyword.pdf`, 1275x1650 gray, one
  embedded JPEG at 150 dpi, converted by `pdfimages` and ImageMagick as a STAND-IN
  Renderer: emon has no PDF renderer or JPEG decoder yet) and its stdout,
  `test/ocr/h-keyword.dump`; `test/ocr/PRODUCER.json` pins what made it.
  **Answers**
  1. *Is the layout sufficient?* For a first fusion pass yes; it carried everything
     Tesseract's C API exposes through integers alone: 199 words, 24 lines (two-point
     baselines), blocks, symbols with boxes and confidences, 193 of 199 words with
     alternatives (`lstm_choice_mode` 2), orientation (enum), writing direction, deskew
     (-0.85 deg here), OSD degrees, script and confidences. The alternatives pay their way: of
     12 misreads inspected (`tight` for `right`, `stary` for `story`, `nol` for `not`,
     `Seciton`, `honse`, ...) the right character was among the alternatives in 10, at 0.3-0.75
     (the other two were dropped characters). **What is missing or wrong**:
     (a) the EVIDENCE schema (D056) cannot hold most of it: no symbol boxes (only `alts`
     with a symbol index), one baseline y per word (slope lost, and slope is what a
     rotated watermark or a seal arc is), no paragraph, block type, page orientation,
     script or deskew, and `alt-add` keeps one code point so a multi-character
     candidate is cut to its first; the unit therefore reads only what the schema can
     take, and the rest stays in the transcript (follow-up: a D056 revision, `ocrpages`
     or per-page columns, symbol boxes, baseline as two points);
     (b) confidence is thin: words the engine got RIGHT come back at 0 (the heading
     "Chapter 1" scores 0, while `Seciton` scores 554) and most alternatives are 0 at
     x10 resolution, so engine confidence alone cannot carry the SPEC 9 rules;
     (c) OSD is weak on a sparse page: orientation confidence 5-28 of 100, script
     confidence 1.2-2.8, and on the rotated copies it called the Latin text Cyrillic.
     Orientation itself was right (90 and 180 reported) and matters: a page rotated
     90 deg was read perfectly by psm 3 (CER 2.05%, the same 24 edits) but one rotated
     180 deg was read as noise (CER 82%) while OSD said 180, so kin must plan a
     re-render with the rotation in round 2 (the layout carries the number, the
     loop for acting on it is unwritten);
     (d) script names arrive as words ("Latin", "Canadian_Aboriginal"): the host maps
     them to ISO 15924 to fit four chars (table in `tess.rs`);
     (e) FOR WATERMARK / SEAL GHOSTS (SPEC 9.5): the faint watermark of
     `watermark-draft` (red, 13% alpha, -30 deg) was NOT read at all on the gray 150-dpi
     page (413 words, no `DRAFT`), so the ghost rules could not be exercised; what the
     answer would need to support them: per-word baseline SLOPE in evidence (the
     extension has it per line), the engine's BINARISED image as a bitmap answer
     (`TessBaseAPIGetThresholdedImage`) so kin can compare ink under a word with what
     the engine saw, a `layer` tag on the answer (which render it read, the
     render request has one), per-word dictionary/numeric flags, and block orientation
     for non-axis text (Tesseract reports four orientations only; arcs need kin's polar
     unwarp and a second round). Ink support itself is pixel work and stays in kin (D092).
     A structural gap: the request has a `psm` and language but no way to say "read
     this as a single line/word" beyond psm, and no variable/whitelist passthrough.
  2. *Does replay make identity exact?* On the kin side, yes: the committed transcript
     replays on Rust and on Go to the same dump, 87,082 bytes, byte for byte (evidence,
     pixel hash, hash of each transcript file), and a changed request (psm 3 to 4) is
     `transcript-miss` on both. Two separate live recordings on this machine were also
     byte-identical (same transcript.edn and PNG hashes). What replay does NOT give
     is engine repeatability across builds or machines: that is what the transcript is
     for, and why the producer is pinned.
  3. *Pinning and cost.* Tesseract 5.5.3 (brew), Leptonica 1.87.0, `eng.traineddata`
     sha256 7d4322bd... (4,113,088 bytes), `osd.traineddata` 9cf5d576..., variable
     `lstm_choice_mode`=2, default OEM (LSTM); the transcript format (D090) has no
     producer field, so `test/ocr/PRODUCER.json` holds them (follow-up: a `:producer`
     map in `transcript.edn` and `TessVersion()` written by the recorder; the
     `transcript-bad` check never looks at it). Runtime on an M1 Pro, one 1275x1650 gray
     page, psm 3 + OSD + symbols and alternatives, including first-use model load of
     `eng` and `osd`: 0.8-2.4 s wall, 1.3 s for a 413-word page, ~1.4 s for the CLI with
     psm 3 alone. The answer is 17,633 cells for 199 words (~88 cells a word with
     symbols and alternatives), 55 KB as EDN. The PNG is the cost of the transcript
     (2.1 MB stored; a compressed writer, or not keeping a bitmap no replayed unit
     reads, is the follow-up that D090 predicted).
  4. *First CER*: **2.05%** (24 edits over 1169 gold characters, 199 words; reading
     order exact: one column) on `h-keyword` page 1 against its gold, by `tools/ocr/cer.clj`
     (bb; whitespace-normalised, no NFC, bb having no Normalizer) and ceilinged at 5% in
     the gate. The errors are single-letter confusions at 150 dpi (`t` for `l` or `r`, `n` for `u`,
     `o` for `a`), a swapped `ti` in "Section" twice, and dropped letters. Not comparable to `bb accuracy`'s CER until that scorer reads
     evidence; it is one page.
  Commit: pending (D037).
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
  `tools/accuracy/`: `score`, `accuracy`, `predict-*`, `test_score` (babashka since
  D094; run by `bb unit`).
- [ ] **P2.10 — Baselines against the field** (SPEC §14.4): pinned versions
  of 2–3 extractors, scored offline, committed as reference lines.
- [x] **P2.11 — Public datasets: licence review and importers** (D086-D088).
  Licences verified per source and recorded with what may be committed and what
  is (nothing, no LFS) in `test/corpus/DATASETS.md`; importers
  `tools/import/<dataset>.clj` for FUNSD, XFUND, SROIE, CORD, DocLayNet, ICDAR
  2013, cTDaR, FinTabNet-OTSL, pdfa-eng-wds (silver only) and a stamp set, output
  under `corpus/_gold/` (outside git), all validating; `bb import` runs them (D094; not
  a gate). Seal set found: mapo80/stamps. Tobacco800 and PubTabNet not imported
  (login / not fetched).
- [x] **P2.12 — `bb fuzz`, first cut** (D106, D107): truncation and byte-flip
  mutation of the PDF fixtures through both hosts; no panic, no hang, same error name;
  also under the 512-byte async source (a miss must never surface as an
  error, D058).
  *Done*: `kin/fuzzprobe.kin` (one kin unit on every host: open, pages, decode every
  stream, load every dictionary as a font and map every code, one line out; the same
  pass through a 512-byte async source that is re-run until it does not miss), four
  drivers (`test/fuzz/{rust,go,java,cs}`), and `tools/fuzz/` (babashka: SplitMix64
  mutators, the harness with a per-case timeout and resumption after a kill, a
  minimiser, and `--selftest` against a fake host that hangs/crashes/panics/disagrees
  on demand). Mutators: truncation, byte flips, splices, and structure-aware ones
  (xref entry numbers, `/Length` direct and indirect, `N G obj` headers, `stream` /
  `endstream` boundaries, `/Size /Prev /Root /First /N /Kids /W /Index startxref`).
  `bb fuzz` (no arguments) is the gate: selftest, every fixture unmutated, 300 seeded
  mutants, every file under `test/fuzz/crashers/`, on Rust and Go, memory and async.
  `bb fuzz --long N [--seed S] [--hosts all]` is not a gate. Found and fixed: three
  signed-overflow sites that panic in a checked Rust build (a header shift added to a
  saturated xref offset, an `/Index` start plus an entry number, a CMap number of ten
  digits; D107), each kept as a reproducer in `test/fuzz/crashers/`. No hang, no
  cross-host status difference and no async/memory difference was found. The content interpreter is not merged
  (P1.7), so it is not in the probe yet: add its entry point to `fz-run` when it is.
- [x] **P2.13 — Synthetic corpus: initials, alignment, columns** (D114; SPEC
  14.3; `tools/synth/`, `schema/`, `test/corpus/synth/`, `kin/scripts/
  gold-check.py`). **Can start now**: babashka, plus Chrome, poppler and
  ImageMagick at authoring time only (D041, D071); no kin.
  *Brief*, in this order, each step its own commit:
  1. **Schemas (commit this first; P1.13 waits on it).** `schema/emon-1.json`,
     additively: `emon` accepts `"1.0"` and `"1.1"`; the inline kind
     `initial` (fields `form` enum drop/raised/margin, `lines` integer >= 2,
     plus the common inline fields and `children`); `align` allowed on
     heading, caption and cell (`cell`: start/center/end only); `note.role`
     gains `sidebar`; `page.layout` (array of `{band, col, role
     (body|sidebar|margin), box: [4 cpt]}`, closed). `schema/emon-gold-1.json`:
     `model.emon` the same two versions; optional top-level `facts`
     (`frags`: object id → array of `[page, band, col]`; `lines`: object id →
     integer; `hyphens`: array of `{id, printed, word}`); `annotated.layout`
     boolean; `annotated.attributes` accepts the key `initial`. Every
     committed JSON golden, HTML-check input and gold still validates
     (`bb unit`).
  2. **Authoring** (`make_html.clj`): the 19 documents of D114, deterministic
     text from fixed seeds as today, with the authoring attributes D114 lists
     (`data-initial`, `data-lines`, `data-initial-text`, `data-align`,
     `data-cols`). Choose font sizes and line heights so each drop cap spans
     the lines it declares; `initial-image`'s tile is written by `magick`
     (a Georgia letter on a bordered, filled square, 300 dpi) to
     `test/corpus/synth/html/img/initial-W.png` and referenced relatively;
     `initial-vector` draws its letters as SVG `<path>` fills (rectangles
     for `T` and `L`), never `<text>`. `columns-continue` must really break
     paragraphs across a column AND a page (fixed-height column containers or
     enough text; the facts in step 4 prove it, and the build fails if not).
  3. **Gold** (`html2gold.clj`): the `initial` inline (the `::first-letter`
     extent for a `<p data-initial>`: preceding punctuation (Unicode Ps, Pi,
     Pf, Po), one grapheme cluster, following punctuation without a space
     between; for an element: its text, `alt` or `data-initial-text`);
     `align` from `data-align`; the D114 conventions 9-12; `annotated` grows
     for the new documents only (existing golds unchanged, byte for byte).
  4. **Facts** (`build.clj`): per new document, from `pdftotext -bbox-layout`:
     `frags` (anchor each leaf block's words by unique 4-grams, split its
     located words into runs by page and by column, the column being the
     index of the run's x among the container's column starts found from its
     lines' left edges, `band` counting column sets top down), `lines` and
     `hyphens` (a line-final word ending in U+002D or U+2010 whose join with
     the next line's first word equals an HTML word). Fail the build when a
     block cannot be anchored, when a drop cap's box does not overlap exactly
     `data-lines` lines, or when `columns-continue` has no paragraph with
     fragments on two pages.
  5. **Print** with `print.sh` (all 19 stems; existing PDFs are NOT
     reprinted), scans by `degrade.clj`, `MANIFEST.json`. Then
     `kin/scripts/content-goldens` for `test/content/synth.txt`, and
     `kin/scripts/synth-extras.edn` gains the new documents' printer
     glyphs; `content-check` must allow the letters of `initial-image` and
     `initial-vector` to have no glyph (declare them the way the Devanagari
     exceptions are, with the reason).
  6. **Check** (`gold-check.py`): the new keys, `facts` ids resolve, `frags`
     only on documents with `annotated.layout`, and re-derivation equality
     as for every gold.
  *Done when*: `bb unit` green (gold-check re-derives all 55 golds; json and
  html checks untouched); `bb accuracy` (oracle) scores every new document
  perfect; the entry reports, per new document, pages, fragments, hyphen
  points and the line count of each initial, and says anything Chrome did
  that D108's probe did not predict.
  **Done** (branch `worktree-agent-adc4d1cc70612e1aa`, one commit per step, schema first:
  `f3bc765`). 19 documents, 4.5 MB (html, pdf, scan, the PNG and the golds: 4,349,703 bytes of
  committed files plus 156,419 of gold); the 36 old documents, their PDFs, scans and golds are
  byte-identical. `bb unit` is green with gold-check re-deriving all 55 golds; `bb accuracy
  --predictor oracle` scores every new document 1.0 on pdf, scan and tagged; content-check: 55
  documents. Per document (pages; fragments = the sum of `facts.frags` lengths; hyphen points =
  `facts.hyphens`; initials as form and lines, and the lines beside each cap as `build.clj` counted
  them, equal to what was declared or the build fails):

  | document | pages | fragments | hyphens | initials |
  | --- | --- | --- | --- | --- |
  | `initial-firstletter` | 2 | - | 0 | drop 2, 3, 4, 3, 2, 3 (`It` `A long` `I remember` `At` `“Nothing` `T`+`HE BUREAU`) |
  | `initial-raised` | 2 | - | 0 | raised x 4 |
  | `initial-image` | 1 | - | 0 | drop 3 (PNG, no glyph) |
  | `initial-vector` | 1 | - | 0 | drop 3, 2 (filled paths, no glyph) |
  | `initial-firstword` | 1 | - | 0 | drop 2 x 3 (`IT` `ONCE` `WHEN`) |
  | `initial-columns` | 1 | 6 | 0 | drop 3, 3 (one at the top of column 2) |
  | `initial-rtl` | 1 | - | 0 | drop 3, 3 (Hebrew, float right) |
  | `initial-cjk` | 1 | - | 0 | drop 3 x 3 (`彼` `「今` `新`) |
  | `initial-letter-headings` | 1 | - | 0 | none (three headings; annotated absence) |
  | `align-paragraphs` | 2 | - | 0 | - |
  | `align-justify-hyphen` | 2 | - | 31 | - |
  | `align-table-numeric` | 1 | - | 0 | - |
  | `align-rtl` | 1 | - | 0 | - |
  | `columns-continue` | 3 | 25 | 11 | - |
  | `columns-uneven` | 1 | 9 | 0 | - |
  | `columns-span-figure` | 2 | 36 | 0 | - |
  | `columns-mid-page` | 1 | 14 | 0 | - |
  | `columns-margin-notes` | 1 | 11 | 0 | - |
  | `columns-newspaper` | 1 | 25 | 12 | - |

  `columns-continue` has one paragraph that crossed a page (page 1 right column to page 2 left) and
  two that broke between columns (both on page 2; the build requires at least one of each), a
  heading in mid-column, footnote references and their notes. The glyph-less initials are three (`initial-image` one, `initial-vector` two); the P4.10
  and P6.7 done-whens count them. **What was not in D108's probe or the design**:
  (1) Chrome 154 has no per-page footnote area in a multicol flow (no `float: footnote`), an
  absolutely positioned box whose top falls past the page bottom moves to the next page, and a
  multicol box with a fixed `height` does not continue on the next page (its overflow columns go off
  the sheet; only an auto-height one fragments), so `columns-continue`'s footnotes are
  `column-span:all` blocks that end a column set: the columns balance above them, a paragraph cannot
  cross them, and they sit mid-page, not at the page foot. (2) Headless Chrome exits 2 after a
  "teardown watchdog" with the PDF complete (this machine under load); `print.sh` now judges a print
  by the PDF it wrote. (3) The PDF's hyphen from `hyphens:auto` is U+002D (ToUnicode `<0010> <0011>
  <002D>`, as D108 (f) said); poppler reports it as U+2010, so `build.clj` maps it back, and the
  printer's extras in `synth-extras.edn` (31, 11, 12 U+002D glyphs) equal `facts.hyphens` counts
  exactly. (4) poppler splits a justified line with wide word gaps into several `<line>` elements, reads
  right-to-left text in visual order, and reads a table row as one line; `build.clj` anchors by
  characters, not by poppler's words or order. (5) Every drop cap of a float `n*lh - 2 pt` tall
  (font size from the baseline of line n) had exactly n lines beside it, in Georgia, Arial Hebrew and
  Hiragino Sans alike; `::first-letter` took `「今` whole and `“N` whole as D108 (d) said; a `Q`'s tail
  crosses the fourth line, so no `Q` (an artefact of the letter, not a finding). (6) D108 (a) holds
  for every text initial: its glyphs come before the page's text, which is why the content-check
  in-order counts of those documents are small and are not a claim.
  **Conventions the design left open** (all in `html2gold.clj`/`build.clj`, read by `gold-check.py`):
  `data-align="none"` is an annotated absence (the decimal column of `align-table-numeric`); a kind
  is annotated for `align` only when every node of the kind says, and the build fails otherwise; the
  hint `"initial": true` annotates initials that are absent (`initial-letter-headings`) and `"layout":
  true` marks a layout document; `data-span="all"` on a child of a `data-cols` container cuts its
  segment; a band is the rank, top down, of a segment among those with lines on the page, a column
  the index of a line's left edge (its first piece, for a row poppler split) among the container's N
  most common edges; `lines` counts printed rows (a drop cap's own glyph is not one); margin and
  sidebar notes have no reference and sit in column 1 beside the body's column 0; a caption's or
  heading's printed number and label are part of what is anchored. **Scoreboard**: `accuracy
  [degraded]` now reports 26 regressions and 41 improvements against `test/scoreboard.edn` because
  the fixture's pooled counts moved with 19 more documents (the oracle entries are unchanged and
  perfect); it is not a gate, and P2.14, which rewrites the categories (`initials`, `align`; the
  new documents are `other` or `columns` until then) and the board, re-records it.
- [x] **P2.14 — Scorer metrics for initials, alignment and columns** (D115, D118;
  `tools/accuracy/score.clj`, `test_score.clj`, `predict_degraded.clj`,
  `accuracy.clj`). **Can start now** with hand-built golds in the laws; the
  corpus run needs P2.13.
  *Brief*: the D115 metrics exactly as named, counts pooled then ratioed
  (D077), each absent when the gold does not annotate it; the no-locale
  hyphen rewrite of D114 convention 12 applied before CER/WER when the
  predicted Model's `settings.locale` is null; `cer`/`wer` otherwise
  unchanged. `frag-col-acc` maps a predicted region to the predicted page's
  `layout` cell containing its centre; a prediction without `layout` scores
  0 on layout documents (not absent). The degraded predictor learns three
  damages so every metric moves: split each initial into its own
  single-letter heading, drop every `align`, split every multi-fragment
  paragraph at its first fragment boundary. Ratchet direction: the
  lower-is-better list gains `initial-false-heading` and `align-wrong`;
  categories gain `initials` and `align` (D079).
  *Done when*: `bb unit` runs new hand-computed laws in `test_score.clj`
  (at least: an initial split off, an initial moved to line 3, `A long` →
  `Along`, a single-line `justify` gold scored against `start`, a column
  continuation joined and split, a hyphen kept and joined with and without
  a locale); `bb accuracy` oracle is perfect and degraded strictly worse on
  each new metric; the scoreboard's committed fixtures entries are updated
  in the same commit.
  *Done (P2.14, D118)*: `score.clj` gains every D115 metric (initial-p/-r/-f1,
  initial-form-acc, first-word-acc, initial-false-heading, align-acc/-cov (pooled and per
  kind), align-wrong, frag-col-acc, join-p/-r/-f1, hyphen-acc) and the no-locale hyphen
  rewrite; `predict_oracle.clj` and `predict_degraded.clj` learn `predict-gold` and the
  three new damages; 14 new laws in `test_score.clj` (hand-computed). The committed golds
  annotate none of the new fields, so on this corpus every new metric is absent and
  `test/scoreboard.edn` does not change; the first run over the P2.13 corpus must be
  `bb accuracy --predictor fixtures --update` (oracle perfect first). Not done here: the
  corpus run itself (needs P2.13).
  *Merge note (P2.13 + P2.14)*: the committed gold declares the initial attribute on the inline
  kind (`annotated.attributes.initial = [form lines]`), not on a block kind, so `score.clj` now
  gates the initial metrics on that (block-kind declaration still accepted); with it the initial,
  alignment, frag-col, join and hyphen metrics are all computed on the 19 new documents and the
  oracle is perfect on all 55. `test/scoreboard.edn` was re-recorded (oracle and degraded) because
  the corpus grew from 36 to 55 documents, so the degraded baseline legitimately moved and the
  new metrics gained entries.

## Phase 3 — PDF breadth

- [x] P3.1 — xref repair by scanning (`N G obj`), broken `startxref`, broken
  `/Length`; `bb fuzz` corpus grows with real damaged PDFs.
  *Done (P3.1, D099)*: when the ordinary open answers `no-startxref`, `bad-xref`,
  `no-root` or `no-pages`, `pd-open` rebuilds the cross-reference by scanning the whole
  source for `N G obj` (the last version of an object wins; a stream is skipped by its
  length, so text in it is not mistaken for an object; `trailer` and XRef-stream
  dictionaries supply /Root, /Encrypt and /ID; object streams are entered after the key
  is known, and a catalog inside one is found) and notes it in the warning bit 512. Over
  a source that misses the scan answers `need-bytes` every 4 KiB (D075). A wrong
  `/Length` was already P1.4's (D062); a wrong `/Prev` is repaired when the catalog is in
  the section the chain lost. **Real files**: of the 4,000 PDFs under `corpus/` the sweep
  found 17 unreadable (12 encrypted, 3 with a `startxref` that leads nowhere, 1 with none,
  1 with a wrong `/Prev`); all open now, with the page count and every page's boxes and
  rotation equal to `pdfinfo`'s: `bb kin/scripts/pdf-compare` says **4,000 of 4,000 agree**
  (before: 3,983 of 4,000). **Fixtures** (`kin/scripts/pdf-p3-fixtures`, test/pdf/p3/):
  startxref wrong or past the end, cut before the xref, cut inside the table, junk tail,
  every offset shifted, a decoy `N G obj` inside a stream, an incremental update with a
  wrong `/Prev`, an object-stream file whose xref stream is gone, and encrypted ones
  (AES-256 and AES-128 with object streams and a broken `startxref`, RC4 with the table
  cut): poppler opens each like the plain twin except the two no other tool can rebuild,
  and emon's answer equals the twin's object for object. The committed fixtures
  (`test/pdf/fixtures.txt`) were updated where repair changes the answer: `badxref`,
  `nostartxref`, `badxrefstream` and `no-root` open; `encrypt-flag` is
  `password-required`. The 512-byte and 64-byte async laws cover repair and
  decryption cell for cell. **Not done**: `bb fuzz` (P2.12) is another item; the
  truncation and corruption laws of `pdfdoctest` and `pdfcrypttest` are its seed.
- [x] P3.2 — Encryption: standard security handler R2–R6 in kin; `Crypto`
  port (MD5, RC4, AES-CBC, SHA-2) in both hosts; embedder password; the
  password handling and the distinct `password-required` /
  `password-incorrect` / `encryption-unsupported` errors; permission flags
  ignored (SPEC §16, D040).
  *Done (P3.2, D097, D098)*: `kin/pdfcrypt.kin` is the standard security handler (R2 to
  R4: algorithms 1 to 7; R5 and R6: algorithms 2.A and 2.B) over the `Crypto` port, seven
  pure forms in all four host trees (`crypto-md5`, `-sha256`, `-sha384`, `-sha512`,
  `-rc4`, `-aes-decrypt`, `-aes-encrypt`); Go, Java and C# call their libraries (RC4
  written out), Rust's are hand-written because its standard library has none.
  `kin/cryptotest.kin`: 20 laws against the published vectors (RFC 1321, FIPS 180-4, RFC
  6229, FIPS 197, SP 800-38A F.2 at 128, 192 and 256 bits) pass on all four targets.
  `pd-open-pw src pw ...` takes the password as a `Src` (never kept: a law finds it in none
  of the arena, document, scratch or page table); `pd-open` tries the empty user password;
  order: empty user, supplied as user, supplied as owner, empty owner. Statuses
  `password-required` (8), `password-incorrect` (9), `encryption-unsupported` (10).
  Permissions are never consulted. Objects are decrypted as they are loaded (strings in
  place, streams through their plan). **Fixtures** (qpdf, checked by `pdfinfo` with the
  same password): RC4-40 R2, RC4-128 R3, RC4-128 R4 (crypt filters), AES-128 R4, AES-256
  R5 and R6, each with the empty user password and with one (and the owner password
  opens each), with object streams (RC4, AES-128, AES-256), clear-text metadata, a
  46-byte and a non-ASCII password, extraction and printing forbidden; five kinds of
  `encryption-unsupported` (public-key, another handler, V3, an unknown crypt filter
  method, R7). `kin/pdfcrypttest.kin` (8 laws, 83 rows): status, page geometry, the
  first bytes of page 1's content, the Info `/Title`, and a deep hash of the catalog and
  Info (every reference followed, every string, every stream decoded) equal to the plain
  twin's; async-source identity at 512 and 64 bytes; truncation and corruption never
  fault. The 12 encrypted real files (RC4 128-bit, owner-locked) open and decode every
  stream (0 bad; 35 image streams declined) and were the first sweep's failures; two
  more files, whose `/P` is written unsigned (4294965956), found the lexer's saturating
  integers: an int object now keeps its value modulo 2^32 too (`pl-int-wrapped`).
  **Gates**: `bb unit` 192 laws on every arm; `bb unit red` fails on every arm.
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
  white-on-white, covered-by-later-opaque-paint, invisible mode. Writes the
  evidence hooks `vis`, `vby` (the occluder's `z`) and `vchk` on glyphs and
  images (SPEC §8.4.3, D096).
- [x] P3.7 — Tagged PDF: structure tree, role map, MCID ↔ content mapping,
  `/Alt`, `/ActualText` (use it when present — it is the author's answer),
  informativeness score (D034). Also (D076): Artifact subtypes as
  furniture/watermark evidence; `H`/`H1`–`H6` as heading-level priors;
  `Table`/`TH`/`TD` `/Scope` `/Headers` `/RowSpan` `/ColSpan` `/Summary`;
  `L`/`LI`/`Lbl` + `/ListNumbering`; `Figure`/`Formula` `/Alt` → placeholder
  description; `Caption`, `Note`, `Reference`, `Link`; per-element `/Lang`;
  `/E` expansions; PDF/UA identification (XMP `pdfuaid:part`) as a trust
  signal. Test with synth pairs: correctly tagged vs deliberately mis-tagged.
  *Done (P3.7, D134-D137)*: `kin/pdfstruct.kin` (names and the 52 standard structure types generated by
  `tools/pdfsnames.clj` into `kin/pdfsnames.kin`). **The tree** is read whole into `se` (StructTreeRoot as row 0, elements in
  pre-order), `sk` (kids in order), `sc` (marked-content references), `so` (object references): RoleMap resolution (a
  standard type is used as it is, a custom one followed to the first standard type, loops and unmapped names say so), `Hn`
  by its numerals, plain `H` given a level prior from its Sect ancestors, `/Pg` inherited, `/Alt /ActualText /E /Lang /ID /T`
  (the effective language's source row), Scope, Headers, RowSpan, ColSpan, Summary, ListNumbering through `/A` and the
  ClassMap, a table's rows and columns, a list's labels checked against its ListNumbering, the ParentTree checked against
  what each element claims; loops, shared elements, 70-deep trees and hostile `/K`s are read once and noted. **The join** is
  exact: `(page, Form XObject, MCID)` to the `marks` row, every glyph owned by the element of its innermost marked content
  with an MCID, or artifact, or neither; one `sa` row per outermost artifact span says where its text sits and whether that is
  what its Type and Subtype claim. **The score** (D135, D136) is seven per-mille components (coverage, role diversity, reading
  order against geometry, heading roles against type size, heading levels, artifacts against position, role nesting), a
  weighted harmonic mean scaled by the weakest check; PDF/UA raises the prior and the trust, never the score. **Verified**
  (D137): `tools/oracle/tagged-check.py` (pypdf, its own walk of the tree) agrees on **11,944,254 items in all 504 tree-bearing
  corpus PDFs** (455,057 elements, 269,721 marked-content references; the corpus has 476 `/Marked true` PDFs, 504 with a
  StructTreeRoot, counted by pypdf alone: the same numbers emon gives) and on the 3 Chrome-tagged synthetic PDFs and 7 new
  fixtures; the correctly tagged synthetic document scores 1000 and its mis-tagged twin 113 (everything P 63, wrong levels 656,
  artifacts 134, order 273). `bb unit` laws on four targets; the fuzz probe reads the tree too.
- [x] P3.8 — Annotations: links (URI, GoTo, named dests — ISO 32000-1
  §12.3.2 destination arrays), Watermark/Stamp annotations, widgets + AcroForm values,
  signature dictionaries.
  *Done (P3.8, D120, D122)*: `kin/pdfannot.kin` (with `kin/pdfx.kin`, their shared helpers)
  reads every page's `/Annots` into the `annots` arena and the AcroForm tree into `fields` and
  `sigs`, with SPEC 8.4.7's own arenas (D096), and `pdfdoc` untouched. **Links**: URI, GoTo, GoToR,
  GoToE, Launch, Named, JavaScript, SubmitForm (first action, and how many the `/Next` chain
  holds); a destination is page, fit, four arguments and the name it was looked up by; a named
  destination is found in the old `/Dests` dictionary and in the `/Names` `/Dests` tree
  (`/Limits` honoured, then a full walk, inside a node budget). Each annotation keeps its `/Rect`
  in PAGE SPACE (SPEC 3.4: crop box origin, `/Rotate`) and as written, and a link's `/QuadPoints`
  as quads in page space, so P8 can map a wrapped link onto the words each line covers.
  **Stamp and Watermark** annotations keep `/Name`, `/Contents`, `/T`, `/CA`, `/F`, the
  appearance stream and the text it shows. **Widgets and fields**: `/FT /Ff /V /DV /Q /MaxLen`
  inherited down the tree, a widget found by its own `/T` or its `/Parent`, `/V` as text (an array
  joined with a line feed), the first widget's appearance text read beside it (`BT`..`ET`, Latin-1)
  and a verdict on whether they agree; check boxes and radio buttons: which widgets are on and
  whether the one `/V` names is. **Signatures**: `/Name`, `/M` (as written and parsed), `/Reason`,
  `/Location`, `/ContactInfo`, `/Filter`, `/SubFilter`, `/ByteRange`, the size of `/Contents`, a
  document time stamp, `/Perms` `/DocMDP`; nothing validated. **Verified** (D122): over all 4,000
  corpus PDFs, `kin/scripts/pdf-catalog-compare` against qpdf and poppler: **forms 19,215 of
  19,215** field attributes (46 files); **links 91,651 of 91,653** (819 files; the two are a
  `file:` URI with a non-ASCII byte, which emon reads as UTF-8 and qpdf as PDFDocEncoding);
  **urls 11,169 of 11,308** (poppler's `-url`, 586 of 590 files: what differs is how poppler
  prints a control character or a trailing no-break space); **signatures 30 of 30** (`pdfsig`: field names and signed ranges); **stamps 30 of 30**;
  130 real-file rows of oracle facts (`test/pdf/catalog-corpus.txt`) all equal on four targets.
  **Fixtures** (`kin/scripts/pdf-catalog-fixtures`, test/pdf/catalog/): forms with inherited
  fields (type, flags, value, `/Q`, `/MaxLen` from a parent; a radio group; a check box; a
  combo with an array value; `/V` against a different appearance), links of every action on a
  rotated page and a cropped one, stamps and a watermark, signature dictionaries.
- [x] P3.9 — Outlines, page labels (`/PageLabels` number trees), Info + XMP
  metadata.
  *Done (P3.9, D121, D122)*: `kin/pdfmeta.kin`. **Outlines** as a pre-order tree (depth,
  parent, children, subtree size, title, `/Count`, `/F`, `/C`, action or destination), read
  without recursion, a looping `/Next` or `/First` dropped by a visited set and noted on its
  parent. **Page labels**: the number tree sorted and cut into ranges; every style (`D R r A a`),
  prefixes, `/St`, a range past the last page, a repeated key (the first wins), pages before
  the first range (no label); Roman past 3999 repeats M, letters repeat (`aa`, `bb`). **Info**
  (PDFDocEncoding, UTF-16, UTF-8 with BOM; dates read and range-checked, zone kept apart from
  "none given"), **XMP** by a small tolerant scanner (prefix-independent: `dc:` may be spelled
  anything; entities, CDATA, comments, attribute and element forms, `rdf:Seq/Bag/Alt` with
  `xml:lang`) including `pdfuaid:part` (D076) and `pdfaid:part`/`conformance`, and the
  catalog's `/Lang`, `/Version`, `/MarkInfo`, `/ViewerPreferences /DisplayDocTitle`, AcroForm
  `/NeedAppearances` `/SigFlags` `/XFA`. Strings of an encrypted file arrive decrypted (D098). **Verified**
  (D122), all 4,000 corpus PDFs: **labels 24,731 of 24,731 pages** (qpdf); **outlines 5,474 of
  5,474 bookmarks** (qpdf, poppler for named destinations); **named destinations 13,223 of
  13,223** (`pdfinfo -dests`); **Info and metadata flags 39,997 of 40,000** (`pdfinfo`; the
  three are poppler's own dates and a malformed Info dictionary); **XMP 2,298 of 2,298**
  properties over 1,150 packets (parsed by `clojure.data.xml`); **/Lang and /Version 8,000 of
  8,000**. **Fixtures**: label ranges of every style, an odd tree, outlines with named
  destinations, circular outlines, UTF-16/PDFDoc/UTF-8 metadata plain and encrypted by
  AES-256, AES-128 and RC4-128.
- [ ] P3.10 — Text-layer oracle (SPEC §14.5): per-character comparison
  against a pinned reference extractor on a born-digital corpus; committed
  expectations.
- [x] **P3.11 — Canonical PNG writer, DEFLATE compressor, and the `Buf` port**
  (D117, SPEC §10.11). No dependency: may be taken at any time.
  **DONE (D123, D124).** `Buf` is in all four host trees and `bb hosts` agrees on its
  seven forms; `kin/deflate.kin`, `kin/png.kin`, `kin/pngprobe.kin` and the laws
  `kin/buftest.kin`, `kin/deflatetest.kin`, `kin/pngtest.kin` (241 laws per arm, all four
  arms; `bb unit red` fails on every arm); `kin/scripts/check` has a seventh line, the
  SHA-256 of the twelve generated PNGs, **identical on Rust, Go, Java and C#** and equal to
  `test/png/digests.txt`; `kin/scripts/png-check` (a gate) has java.util.zip and
  ImageMagick 7.1.1-33 read the twelve files and six real images (scans at 300 dpi up to
  2481x3508, a 1200x800 photo) to exactly their pixels; `kin/scripts/png-bench` (not a gate)
  writes the real page and photo on all four targets and finds **the same bytes**.
  **Measured** (Apple silicon, arm64, release builds, best of 7, machine shared with other
  builds; `bb png-bench 7`): the 2481x3508 gray bench page (the noisy invoice,
  `inv_00007.noise.png`, 8.7 Mpx): Rust 380 ms, Go 336, Java 316, C# 359, 807,061 B;
  the 1200x800 RGB photo: 141 / 151 / 148 / 154 ms, 543,247 B. **Size against
  `java.util.zip.Deflater` level 6 over the same filtered rows**: page 1.035x (806,839 B of
  IDAT data against 779,679), photo 1.067x (543,094 against 509,103); the clean 300-dpi
  scans of `bb png-check` 1.023-1.033x, two receipt scans 1.027-1.030x (ImageMagick's own PNG of the same pixels, with its own filter choice, is 3-5 %
  smaller than zlib-6 over our rows on the scans and 8 % larger on the photo). The cost of determinism is about 3-7 % of size; no target is slower than 0.4 s per
  scanned page. Debug-mode Rust (`cargo test`) writes 1 MiB of random bytes in about 6 s.
  Deviations from the brief, all in D123/D124: `buf-push` answers the **new length** as the
  brief says (not the index, as `cells-push` does); the Buf laws are in `kin/buftest.kin`
  because `porttest` is a probe and not a law file; the window ring is 64 KiB (32 KiB of
  window and the lookahead), so the workspace is about 670 KiB; `kin/scripts/png-check`
  is a gate (in `bb gates`) and `png-bench` a named non-gate.
  *Brief*: (a) **`Buf` port**, in all four host port files beside `Cells`, with
  `@kin:link` annotations and a `Buf` tag in `emon.lang`: `buf-len b`, `buf-at b i`
  (0..255), `buf-push b v` (low 8 bits; answers the new length, as `cells-push`),
  `buf-set b i v`, `buf-truncate b n`, `buf-append-blob b blob start end`, and the
  form `buf-blob b` (a borrow as a `Blob`, spelled like `src-slice`). Host types: Rust
  `pub struct Buf(Vec<u8>)`, Go `*Buf` over `[]byte`, Java `emon.Buf` (byte[] +
  length, doubling), C# `Emon.Buf`. Laws in `kin/porttest.kin`. (b) **`kin/deflate.kin`**
  (`emon.deflate`), a zlib writer that streams: `df-init ws` (a `Cells` workspace:
  32 KiB window ring, `head` 32,768 and `prev` 32,768 hash chains, a pending-symbol
  buffer of 16,384, frequency tables), `df-write-cells ws out cells start end`,
  `df-write-blob ws out blob start end`, `df-finish ws out`. Parameters exactly as
  SPEC §10.11: header `78 01`; hash `((b0 << 10) ^ (b1 << 5) ^ b2) & 32767`; chain
  limit 32; greedy with one step of lazy matching (take the next position's match
  only if strictly longer); matches 3..258, nearest first on equal length; a block
  every 16,384 symbols and at the end; each block stored, fixed or dynamic by
  fewest bits (ties: stored, fixed, dynamic); dynamic lengths by package-merge,
  limit 15 (7 for the code-length code), frequency ties by symbol; code-length
  runs greedy with 16/17/18; Adler-32. A test-only `df-force` mode forces one block
  type. (c) **`kin/png.kin`** (`emon.png`): `png-begin ws out z w h ctype depth
  dpi100 pal npal trns` (writes signature, `IHDR`, `PLTE`/`tRNS`, `pHYs` when
  `dpi100 > 0` as `(dpi100·100 + 127) / 254` pixels per metre, unit 1), `png-row ws
  out z cells start` (one row of packed samples; filter chosen per SPEC §10.11; the
  filtered row goes to deflate, whose output goes to the scratch `Buf` `z`; every
  65,536 bytes of `z` become one `IDAT` in `out`, the remainder moved to `z`'s
  front and `buf-truncate`d), `png-end ws out z` (last `IDAT`, `IEND`). CRC-32
  table computed in `ws` at `png-begin` (polynomial `EDB88320`). (d) Tests:
  `kin/deflatetest.kin` (round trip through `emon.inflate`: empty, 1 byte, 300
  repeats, 1 MiB of splitmix bytes, 64 KiB of text, each forced block type, a
  match at distance 32,768); `kin/pngtest.kin` (12 images generated from splitmix
  in the test: gray 1×1, RGB 97×61, RGBA 97×61, gray+alpha 97×61, 1-bit gray 33×17,
  palette of 16 with `tRNS`, RGB 3000×1, gray 1×3000, a 2481×64 strip of the bench
  page, and three more of the implementer's choosing; each decoded again by a
  test-only reader (inflate + unfilter) to the same samples, and its SHA-256
  through `crypto-sha256` equal to the committed value); `kin/pngprobe.kin` in
  `bb check` prints the 12 digests, which the four targets must agree on.
  `kin/scripts/png-check` (bb) writes the 12 files from the Rust probe and decodes
  each with ImageMagick (`magick x.png rgba:-`, version recorded) and with
  `java.util.zip.Inflater`, comparing to the generator's samples. (e) Measure
  (D092's rule, not a gate): ms per target for the 2481×3508 gray bench page and a
  1200×800 RGB photo, and size against `java.util.zip.Deflater` level 6 on the
  same filtered rows; record it here.
  *Files*: `host/*/port.*`, `kin/deflate.kin`, `kin/png.kin`, `kin/deflatetest.kin`,
  `kin/pngtest.kin`, `kin/pngprobe.kin`, `kin/porttest.kin`, `kin/scripts/png-check`.
  *Done when*: `bb unit` green on every arm, `bb hosts` agrees on the `Buf` forms,
  the probe's 12 digests are equal on four targets, `png-check` green, and the
  measurement is written here. **Not** in scope: transcript PNGs (D090) keep
  stored blocks until a decision moves them to this writer.
- [ ] **P3.12 — PDF image pixels in kin** (D117, SPEC §10.11 "embedded" and
  "decoded"). Depends on P3.5 (image placements with CTM and clip in `images`
  rows) and P3.11.
  *Brief*: `kin/pdfimage.kin` (`emon.pdfimage`). (1) `pi-info`: an image XObject or
  an inline image (`BI`…`EI`, whose abbreviations `/W /H /BPC /CS /D /F /DP /IM`
  and abbreviated filter and colour-space names are expanded) → a `Cells` record:
  object number (-1 inline), w, h, bpc, colour-space class, base and `/N`, an
  `Indexed` palette (hival and its bytes from a string or stream), `/Decode` as `Fx`
  pairs (default by class), `/ImageMask`, `/Mask` (colour-key ranges or a stencil
  handle), `/SMask` handle and `/Matte`, the filter chain and `DecodeParms`, the
  stream span, and whether its bytes are the input's own (not encrypted, the image
  filter alone) or kin's (decrypted or pre-filtered). (2) `pi-passthrough`: SPEC
  §10.11's JPEG conditions by a marker walk (SOF0/1/2 allowed; SOF3, 5-7, 9-11,
  13-15 refused; component count; APP1 Exif tag 0x0112; SOF size against
  `/Width`/`/Height`), answering 0 or a `pv` `defenum` reason. (3) `pi-samples`:
  raw, Flate, LZW, RunLength with predictors through the existing filters; for DCT
  that cannot pass through, JPX, JBIG2 and CCITT, plan a `decode-img` request
  instead. (4) `pi-pixels`: samples (or the answer's `Bitmap`) → a gray or RGB
  `Bitmap` plus a one-channel alpha `Bitmap` (host-allocated), applying unpack, 16 →
  8 rounding, `/Decode` (Fx, rounded half up), colour (CMYK naive, `Indexed`,
  `ICCBased` by `/N`), masks (a mask of another size mapped by nearest neighbour,
  `floor(x·mw / w)`), `/Matte`; `Separation`/`DeviceN`/`Lab` answer `colorspace`.
  (5) `pi-orient`: the eight rectilinear orientations from the signs and zeros of
  the placement's `Fx` matrix (else `transformed`), and the visible crop (clip box
  ∩ image box mapped into image pixels, floor/ceiling) under `asset-whole`. (6)
  `decode-img` (kind 6) in `kin/plan.kin`: `rq-decode-img` and readers, args
  `filter w h comps bpc k columns rows ccitt-flags where offset length sha×8
  gwhere goffset glength gsha×8` (`where`: input index ≥ 0, -2 the unit's payload
  `Buf`, -1 none; `sha` zero for an input span), answered as `decode`; laws in
  `kin/plantest.kin`; the Rust and Go loops (`host/rust/src/plan.rs`,
  `host/go/port/plan.go`) hand the unit's payload `Buf` to the fulfiller and never
  write it to a transcript (a payload whose SHA-256 differs is a different key, so
  a replay answers `transcript-miss`). Fixtures: `kin/scripts/asset-fixtures`
  (bb, in the manner of `pdf-p3-fixtures`) writes `test/assets/*.pdf` from source
  images it generates deterministically (JPEGs by ImageMagick, committed, version
  recorded): DCT passthrough; DCT with `/Decode [1 0 1 0 1 0]`; CMYK DCT with an
  Adobe marker; DCT with EXIF orientation 6; arithmetic-coded DCT; Flate RGB 8 and
  16 bits; gray 1, 2, 4 bits; `Indexed` 16 over RGB; `/ImageMask` in red; `/Mask`
  colour key; `/Mask` stencil of another size; `/SMask` with and without `/Matte`;
  an inline image; an image in a Form XObject; drawn rotated 90°, flipped, and
  skewed; clipped to a rectangle and to a circle; drawn twice; an encrypted
  (AES-128) copy of the Flate case; JPX, JBIG2 with globals, CCITT G4 (from
  `magick -compress Group4`), for the request record only. Expected pixels are
  computed by the generator itself (Clojure, from the source samples and the rules
  above) and committed as the SHA-256 of RGBA8 row-major bytes, beside an
  ImageMagick PNG for eyes.
  *Files*: `kin/pdfimage.kin`, `kin/pdfimagetest.kin`, `kin/plan.kin`,
  `kin/plantest.kin`, `host/rust/src/plan.rs`, `host/go/port/plan.go`,
  `kin/scripts/asset-fixtures`, `test/assets/`.
  *Done when*: every kin-decodable case's pixel hash equals the generator's on every
  arm (`bb unit`); passthrough bytes equal poppler's `pdfimages -all` output byte for
  byte; unmasked decoded cases equal `pdfimages -png` (`magick compare -metric AE`
  0); every codec case plans the expected `decode-img` record; `bb plan` still green.
- [ ] **P3.13 — Assets in the Model, JSON, HTML and the API, level `images`**
  (D117, SPEC §3.7-3.9, §4.2, §5, §10.11). Depends on P1.10, P3.6, P3.12 (P2.1 for
  the Go arm).
  *Brief*: (1) Settings `assets`, `asset-dpi`, `asset-pixels`, `asset-bytes`,
  `assets-bytes`, `asset-signatures` through `setting-of` and the Engine `Config`.
  (2) `kin/model.kin`: arenas `assets` (stride 24: type, origin, from, object, byte
  start, byte length, sha256 ×8, w, h, dpi, flags, use head, use count, 4 spare)
  and `uses` (stride 8), the `abytes` `Buf`; `defenum`s for type, origin, from,
  flags and `noasset` reasons (codes forever, D050); `model-check` and
  `model-hash` cover them. (3) **Provisional figures** until P8.1: an image
  placement that passed P3.6, covers < 800 ‰ of its page (else it is a scan or a
  background) and has both sides ≥ 18 pt becomes a `figure` of subkind `unknown`,
  placed in reading order by its top edge. This is SPEC §10.6's placeholder, owed
  with or without `assets`; P8.1 replaces the rule. (4) `kin/asset.kin`
  (`emon.asset`): per node in id order, SPEC §10.11 steps 1-4 at `images`
  (`part`/`vector`/`composite` otherwise), passthrough bytes by `buf-append-blob`,
  decoded pixels through `emon.png`, `asset-pixels` scaling, dedup by SHA-256 and
  length, `asset-bytes` and the first-overflow `assets-bytes` rule, uses. Codec
  images answer `noasset: "codec"` until P6.1a. (5) `kin/json.kin`: the `assets`
  array, `asset`/`noasset`, the settings keys, all in SPEC §10.11's order;
  `schema/emon-1.json` extended additively (D055); goldens
  `test/json/assets-*.{model.edn,json}` with the independent Python reference
  (D054) extended. (6) `kin/html.kin`: `ho-assets-external` 128, `ho-assets-omit`
  256 (neither: inline), an `asset-prefix` text argument, the mapping and `alt`
  rule of SPEC §10.11, base64 in kin; HTML goldens in the three modes and D067's
  independent renderer extended; html5lib check. (7) Rust and Go API (`Asset`,
  `assets()`, `asset()`, `asset_files()`); driver flags `--assets=images|figures`,
  `--html-assets=inline|external|omit`, `--asset-dir=D` (the driver writes the
  files; the library never does). (8) End to end: P3.12's fixtures, and four new
  synthetic documents in `tools/synth` with `<img>` of known PNG and JPEG sources
  (whether Chrome passes a JPEG through is measured and recorded; a PNG must come
  back with equal pixels).
  *Files*: `kin/model.kin`, `kin/asset.kin`, `kin/assettest.kin`, `kin/json.kin`,
  `kin/html.kin`, `schema/emon-1.json`, `host/rust/src/emon.rs`, the Go host,
  `test/json/`, `test/html/`, `test/assets/`, `tools/synth/`.
  *Done when*: `bb slice` with `--assets=images` green on Rust and Go; the identity
  arm gives byte-identical JSON, HTML in three modes and asset files across hosts,
  in memory and through the 512-byte async source; a bb check recomputes every
  `sha256` from the files and every PNG decodes (ImageMagick) to the expected
  pixels; the schema gate passes; with `assets: none` every existing golden is
  unchanged except where a provisional figure now appears.

## Phase 4 — Layout and structure, on the text layer

- [ ] P4.1 — Blocks and reading order: XY-cut with spanning elements,
  multi-column, RTL (emon's own UAX #9 `bidi`), priors compared (SPEC §10.1).
  *Brief (D111, D112)*: after P1.8 and P1.12 (v2 words and lines), and
  after P4.10 runs (initials before indents are measured) and P4.3's
  recurrence pass (furniture out before gutters are sought). SPEC 10.1.1
  steps 1-9 as written: gutters over words (named settings `gutter-min`,
  `gutter-rows` 3), lines split at gutters, spanning elements, the
  vertical-first cut tree, `band`/`col` numbering, roles and the
  sidebar/margin `note`s, the page `layout` arena (P1.13); then SPEC
  10.1.2 continuation with `join-min` and its signal points as a `defdata`
  table, one region per fragment, blocks between fragments moved after the
  joined paragraph. The evidence dump (or a debug line format) prints each
  page's cut tree and every join score.
  *Done when*: on P2.13's layout documents and `columns-two`,
  `columns-three-news`: `frag-col-acc` and `join-r` at least 0.95, Kendall
  tau at least 0.95, no `join-p` below 0.95; on `layout/arxiv` 1512.03385,
  1810.04805 and 2206.01062 a person reads the HTML in order and the entry
  lists what is wrong; first numbers recorded in the scoreboard (D023).
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
- [ ] P4.10 — Initials on the text layer (SPEC 10.9, D108, D109; new
  `kin/initial.kin`). *After P1.8 and P1.12* (it reads v2 glyph sizes,
  baselines and fonts), *before P4.2 and P4.4* (the inventory and heading
  candidates must already exclude initials). *Brief*: the candidate, drop,
  raised, margin, first-word, RTL and not-an-initial rules of SPEC 10.9 as
  written, with `initial-min` and the tolerances as named settings;
  `/CapHeight` and `/XHeight` read into `fonts` (D108; 0.7 of the size when
  absent); the join rule (no space unless a tagged tree or `/ActualText`
  holds one; `low-conf` for one-letter words, with one-letter-word tables
  for the P4.8 locales as `defdata`); the paragraph builder of P1.8 told
  where lines 1..N start and from which line the gap is measured; the
  `initial` inline written (P1.13). Glyph-less initials (outlines, images,
  Type 3 without Unicode) are P6.7's; here such a paragraph just starts
  without its letter, and the entry counts them. *Done when*: on P2.13's
  text-layer initials documents (`initial-firstletter`, `-raised`,
  `-firstword`, `-columns`, `-rtl`, `-cjk`): `initial-p`/`-r` 1.0,
  `first-word-acc` 1.0 except the declared `A`/`I` word cases,
  `initial-false-heading` 0, and `initial-letter-headings` keeps its three
  headings; the four real files of D108 are checked by hand in the entry.
- [ ] P4.11 — Alignment (SPEC 10.10, D110; `kin/align.kin`). *After P4.1*
  (it needs the fragment boxes); the cell part is P5.3's. *Brief*: the
  SPEC 10.10 tests and single-line rules, tolerance and `align-min` as
  named settings, base direction by P4.1's `bidi` (UAX #9 P2-P3); `align`
  on paragraph, heading and caption. *Done when*: on `align-paragraphs`,
  `align-justify-hyphen`, `align-rtl` and the column documents: `align-acc`
  at least 0.98 and `align-wrong` at most 0.01; `align-cov` recorded
  (single lines lower it by design).

## Phase 5 — Tables

- [ ] P5.1 — `ruled` profile from vector rules: lattice, cells, spans.
- [ ] P5.2 — `partial`, `shaded` and `whitespace` profiles; profile scoring.
- [ ] P5.3 — Header rows, row groups, numeric columns, captions. Also
  cell `align` per table column (SPEC 10.10, D110): measured over the
  column's body cells against its text extent; header cells separately;
  `align-table-numeric` scores it (`align-acc` for cells at least 0.98).
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
- [ ] **P6.1a — Host decoding for assets: `decode-img`** (D117). Depends on P6.1
  and P3.12.
  *Brief*: Rust and Go fulfil kind 6: DCT (Rust `zune-jpeg`; Go `image/jpeg`), JPX
  (Rust through OpenJPEG; Go `unsupported` unless a pure-Go decoder is adopted),
  JBIG2 with its globals (Rust through `jbig2dec`; Go `unsupported`), CCITT G3/G4
  with `K`, `Columns`, `Rows`, `BlackIs1`, `EncodedByteAlign` (Rust `fax`; Go
  `golang.org/x/image/ccitt`). The answer is the codestream's pixels (8-bit, gray or
  RGB, `bilevel` flag), never with the PDF's `/Decode` or masks applied (those are
  kin's); an engine that cannot is `ps-unsupported`, which becomes `noasset:
  "codec"`. A 4-component DCT is converted to RGB honouring the Adobe APP14 marker.
  Record a transcript of P3.12's codec cases live on Rust into
  `test/assets/transcripts/` and replay it on Rust and Go.
  *Done when*: JPX (lossless), JBIG2 and CCITT cases equal the generator's pixels
  exactly, DCT within 2 per channel of `pdfimages -png`; the replay's JSON, HTML and
  asset files are byte-identical on Rust and Go.
- [ ] P6.2 — `Renderer` port (pdfium in Rust and Go), with layer masks where
  available (D007); pdfium loads through its custom-loader callback over the
  byte source, not a path or a whole-file copy (D058).
- [ ] P6.3 — Normalization: grayscale, ink layers, Sauvola, deskew,
  orientation (SPEC §8.2.1).
- [ ] P6.3a — Raster kernels as host-accelerable whole-stage forms (D093).
  Measure which normalization/tracer stages dominate (ink-layer separation,
  Sauvola, morphology, binarise+label), then define whole-stage forms (e.g.
  `ink-mask`, `binarise-label`) with an exact integer spec and a kin
  reference implementation. Hosts may accelerate (SIMD first; optional GPU:
  wgpu in Rust, WebGPU in the browser). Gate: every accelerated
  implementation is bit-identical to the kin reference over the corpus.
- [ ] P6.4 — Tracer: components, contours, rules, primitives (SPEC §8.2.2).
- [ ] P6.5 — `Ocr` port with Tesseract in Rust and Go; capabilities; the
  first-pass plan; OCR-only pipeline for images and scanned PDFs (D033).
- [ ] P6.5a — The OCR answer extension v2 (D095 open items, D096): `ox`
  version 2 carries the engine's thresholded image as a bitmap answer (a
  `views` row of kind engine-binary, named by the `oruns` row), per-line
  row attributes (x-height, ascenders, descenders), per-block box,
  orientation and writing direction, per-word dictionary/numeric flags and
  point size; `transcript.edn` gains a `:producer` map (`TessVersion()`,
  model hashes); the word-confidence-of-0 finding of P1.12 step 6 is fixed
  in `tess.rs` if it is a reading bug. Re-record `test/ocr/h-keyword/` live
  (Rust only) and fill `xo-*` column 11 and 12 once P6.3 has kin's ink.
- [ ] P6.6 — Degraded corpus from the synthetic round trip (SPEC §14.3.1);
  scanned-document baselines in the scoreboard.
- [ ] P6.7 — Initials from ink (SPEC 10.9, D108): the geometry test of P4.10
  over non-text ink (a vector path cluster, an image placement, a Type 3
  glyph without Unicode, the tracer's components on raster pages); the
  letter from `/ActualText` or `/Alt`, then a planned OCR request of pass kind
  `initial` (a new `op` row; psm 10, the paragraph's script) on the
  region, then the tracer; U+FFFD and `low-conf` when nothing passes; the
  ornament is not a `figure`. *After P6.4, P6.5a and P7.4* (targeted
  rounds). *Done when*: `initial-image` and `initial-vector` score
  `initial-r` 1.0 and `first-word-acc` 1.0 on `pdf/` and `scan/`, and the
  scanned variants of every P2.13 initials document have no single-letter
  heading.

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
- [ ] **P8.5 — Region assets at `figures`: cropped and rendered** (D117, SPEC
  §10.11). Depends on P8.1, P6.1a and P6.2 (seals: P8.2, P8.3).
  *Brief*: (1) `crop` (kind 5) in `kin/plan.kin` (`input page dpi layer px py pw
  ph`, answered as `render`), fulfilled in Rust and Go by pdfium
  (`FPDF_RenderPageBitmapWithMatrix`: scale `dpi/72`, translate `(-px, -py)`, clip
  `(0, 0, pw, ph)`, white fill, `FPDF_ANNOT`), with a law that a crop equals the
  same rectangle of the whole-page render on each host. (2) `emon.asset` at
  `figures`: steps 2-4 in full — the overlay test over `z` (paths, shadings, images
  and visible glyphs painted after the image over more than `asset-overlay` of
  it), the non-rectangular clip (the `clip` `paths` row is not an axis-aligned
  rectangle), the rectilinear test; `cropped` from P3.12/P6.1a pixels or from a
  raster input's or scan's decode answer through its view (no resampling, no
  deskew); `rendered` with the 2 pt pad and the `d·3/4` step-down;
  `text-overlap`; the `seal` policy (`asset-signatures`). (3) Fixtures: synthetic
  documents (`tools/synth`) with inline-SVG charts and diagrams (vector), an image
  clipped by `border-radius` (non-rectangular), one rotated 10° by CSS, text over a
  photo, a logo on every page (one asset, many uses); raster pages composed by bb
  (ImageMagick) from a known figure image pasted at a known offset into a lossless
  PNG and TIFF page; the mapo80 stamps (D088) for seals.
  *Done when*: a law crops a fixed region of a raster fixture and gets exactly the
  pasted pixels; end to end, each raster figure's crop has IoU ≥ 0.9 with the paste
  box and equals the page's pixels over it; each render crop has the computed pixel
  size and a mean absolute difference ≤ 8 per channel from `pdftoppm -r 150 -x -y
  -W -H` over the same rectangle (different rasterisers: a sanity bound, not
  identity); a transcript recorded live on Rust replays byte-identical JSON, HTML
  (three modes) and asset files on Rust and Go; the logo fixture has one asset.

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
  **Assets (D117)**: `asset.bytes()` (a `Uint8Array` copy) and `asset.blob()`
  (a `Blob` of the asset's type), `external` documented as the browser form; the
  P3.13/P8.5 asset fixtures give identical JSON, HTML and asset files in a
  headless browser, and the peak JS heap for a document at the 32 MiB
  `assets-bytes` default is measured in `inline` and `external` modes and written
  here.
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
**Open item resolved by D117**: pixels are an opt-in (`assets`: `none` /
`images` / `figures`, SPEC §10.11); the placeholder stays the default and the
Model never carries bytes.

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
**Revised by D096** (evidence v2, SPEC §8.4; implemented by P1.12).
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
**Superseded in part (P3.1, P3.2)**: repair by scanning is D099, decryption D098; the
workspace is now 448 header cells (the security region sits before the entries).
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
`tools/synth/make_html.clj`, which is authoring tooling: the committed HTML is
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
`tools/synth/html2gold.clj` says of the committed HTML plus the manifest's layout
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
**Decision**, each one encoded in `tools/synth/html2gold.clj`:
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
**Decision**: `tools/accuracy/score.clj` (was stdlib Python; babashka since D094; like json-check and
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
identical text, fuzzy in the gaps). Laws: `tools/accuracy/test_score.clj`, hand
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
(`tools/import/spotcheck.clj`).
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
*(The stored-block writer below is revised by D125: bitmaps are now written by the canonical compressed kin encoder, and any valid PNG is read.)*
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

### D093 — Pixel-parallel work is exposed as whole-stage host forms with a kin reference; kin stays device-agnostic
**Decision**: GPU/SIMD acceleration of raster work (threshold, histogram,
integral images, morphology, ink-layer separation, binarise+label) happens
behind host-implemented forms, never inside kin. Forms are whole stages (so a
GPU host can keep pixels on-device across steps) with an exact integer
definition; kin keeps a reference implementation of each, which is the spec,
the fallback where no accelerator exists (servers, WASM without WebGPU), and
the identity oracle: accelerated output must be bit-identical. Acceleration
is optional per host; SIMD first, GPU later. Per-pixel `bitmap-u8` reads from
kin are for sparse access only (e.g. the tracer walking a finished mask).
**Why**: maintainer asked whether thresholding/masking should use the GPU
and where the seam is. The P2.7 bulk forms already put these passes behind
host ports; making kin device-aware would break the one-source/four-targets
identity model. At one 300-dpi page (~9 Mpx), transfer cost rivals a SIMD
pass, so GPU pays only for chained stages, heavy kernels, or batches.
**Alternatives**: kin-level GPU vocabulary (no device model in kin; breaks
identity); move whole normalization into hosts with no kin reference (loses
the identity oracle and the portable fallback); GPU-only fast path (not
available on all targets).

### D094 — Scripting is babashka; Python only for third-party oracles in `tools/oracle/`
**Decision**: all emon scripting (importers, scorer, synthetic-corpus tooling, tests) is
babashka. Python is allowed ONLY where the script's purpose is to use an independent external
implementation as a reference oracle: pdfminer.six / pypdf cross-checks, the html5lib strict HTML
parser, and the `jsonschema` 2020-12 validator. Those live in one place, `tools/oracle/`
(so far `gold_validate.py`, called by `bb import`). Where a CLI does the job bb calls it
(poppler `pdftotext -bbox` / `pdftoppm` / `pdfinfo`, ImageMagick `magick` for scan degradation, whose
version is recorded). Ported in this pass: `tools/import/*` (+ a real `bb import` task, run into
`corpus/_gold/`, validated, counts reported; not a gate), `tools/accuracy/*` (the tests are bb and
`bb unit` still runs them) and `tools/synth/*`. Shared pieces: `tools/lib/pyjson.clj` (JSON
byte-identical to Python's `json.dumps`, ordered objects, float `repr`), `tools/lib/pyrandom.clj`
(CPython's Mersenne Twister, so the seeded synthetic HTML is unchanged) and `tools/lib/nfc.clj`
(UAX #15 NFC, since babashka has no `java.text.Normalizer`). Output was proved identical: every
`corpus/_gold/**` gold file (only `_summary.json`'s wall-clock `seconds` differs), the synthetic
gold and manifest, `bb accuracy` output and `test/scoreboard.edn`. Not yet moved (they are in
`kin/scripts/`, a later pass): the `jsonschema` step of `gold-check.py` and `json-check.py` and the
html5lib step of `html-check.py` belong logically in `tools/oracle/`. `gold-check.py` and
`kin/scripts/unit` needed one-line edits so they call the bb converter and bb tests instead of the
deleted Python.
**Why**: one scripting language means one toolchain for contributors (bb is already required), no
second dialect of the same normalisation and JSON rules, and scripts that can be run and tested the
same way as the gates; a reference oracle is the one place a second, independent language is the
point, so those stay separate and labelled.
**Alternatives**: leave Python as the tooling language (two toolchains, drift between the Python and
kin implementations of the same rules); drop the oracles (loses the independent check).
- [ ] **Signed off** (maintainer)

### D095 — OCR through the transcript: the answer gains an extension, Tesseract is bound live in the Rust host only, and only a replay is a gate (P2.6)
**Decision**: (1) An OCR answer is D089's words followed, in the same length-prefixed
record, by the `emon.ocr` extension: a 10-cell header (version, orientation, writing
direction, line order, deskew in centi-degrees, OSD degrees, script as an ISO 15924 code
packed in four chars, script and orientation confidence, line count), 11 cells per line
(box, two-point baseline, block, paragraph, block type), and per word its style bits
(the Model's `sy`), language, and symbols (box, confidence x10, code points, alternatives
with confidences). Everything is integers in the request bitmap's pixels; layout,
builders and readers live in `kin/ocr.kin`, so a host and kin cannot drift. An answer
with nothing after its words (a fake, an engine without symbols) has no extension
(`ox-find` answers -1). Request flags: bit 1 symbols and alternatives (D089's), bit 2
orientation and script detection. (2) The Tesseract binding is `host/rust/src/tess.rs`,
hand-written FFI to the C API, LIVE only: nothing in `bb gates` compiles or runs it (it is
built under `--cfg live` by `tools/ocr/record.clj`); with no language in the request it
loads every installed model (D038), OSD runs on its own `osd` instance, and alternatives
come from `lstm_choice_mode` 2. (3) `bb ocr` is a gate: it replays the committed
transcript `test/ocr/h-keyword/` on Rust and Go, requires the identical dump (and that it
is the dump the recording printed), a CER ceiling (5%) on the page against its gold, and
`transcript-miss` for a changed request. (4) The producer of a transcript is pinned in
`test/ocr/PRODUCER.json` until D090's format has a field for it. (5) The unit puts into
`ocrwords` only what D056 holds (text, box, confidence, alternatives by symbol index, line,
block, baseline y, style bits, language); symbol boxes, baseline slope, paragraphs, block
types and page orientation stay in the transcript until D056 is revised.
**Why**: the spike's two questions were whether the layout is enough and whether replay is
exact; an extension after the words answers the first without touching D089 readers, and a
gate that replays proves the second without making the gate depend on whose Tesseract is
installed (SPEC 7.3, D008). FFI by hand keeps the dependency a shared library the OS package
already ships and avoids a crate's build script; it is 35 declarations.
**Alternatives**: changing the word layout to carry symbols in place (breaks D089's walkers
and every recorded transcript); `tesseract-sys` or `leptess` (a build script and a pinned
Leptonica; the C API is small enough to declare); the gate running live Tesseract (makes the
gates machine-dependent, which D008 exists to prevent); recording with the CLI's hOCR/TSV
(no symbol alternatives, no OSD in one call).
**Open**: a D056 revision for the fields above (→ D096, P1.12); a `:producer` map in `transcript.edn`; a
compressed PNG writer or bitmap-free OCR entries (the stored PNG is 2.1 MB a page); the
kin side of acting on OSD (a page at 180 degrees reads as noise until re-rendered rotated);
the engine's binarised image as a bitmap answer for ghost detection (SPEC 9.5); a real
`Renderer` in place of the extracted scan image.
- [ ] **Signed off** (maintainer)

### D096 — Evidence v2: 24 arenas in four bundles, shared heads, two-point baselines, views for raster provenance, features instead of engine confidence (revises D056)
**Decision**: SPEC §8.4 replaces D056's nine arenas; P1.12 implements it after
P1.7 merges. In short:
1. **Common geometry.** Every placed row has `page` and an axis-aligned box in
   centipoints (D009). Every glyph, word and line, whatever its source, has a
   **baseline segment** (two points) and a `rot` computed from it by one
   integer `ev-angle` (centi-degrees, counter-clockwise as displayed); the
   glyph adds `asc`/`desc`, so its quad is exact. Raster rows keep their box in
   pixels and name the **view** they came from; the page box is derived through
   the view's 16.16 transform, computed once by `vw-new`.
2. **Shared heads.** Word-like rows of every source start with the same 16
   cells (page, box, baseline, rot, dir, text run, line, raw conf, feats row),
   line-like rows with the same 13, char-like rows with the same 9, so
   alignment (P7.1) is written once over `(Cells, stride)`.
3. **Common identifiers.** Any row is `(ea, index)`; many-to-many lists are
   runs of `refs`; runs stay contiguous (D056's rule); text-layer painted
   objects carry a page-unique paint order `z`.
4. **Text layer**: glyph (32) with render mode, fill and stroke colour and
   alpha, colour-space class, `z`, `clip` (a clip is a `paths` row), `mark`
   and `ocg`, and P3.6's hooks `vis`/`vby`/`vchk` (a check records that it
   ran, so "unchecked" is distinguishable from "visible"); words with
   `wbreak`; lines with content-stream `order`; `marks` for D076's spans
   (nesting, glyph run, z range, ActualText/Alt/E/Lang, OCG and its default
   state); `fonts`; `paths` with exact geometry in `pts`; `images` with `z`
   and visibility.
5. **OCR**: `oruns` (view read, the engine's binarised view, request, round,
   pass kind, psm, region, languages, orientation, direction, deskew, OSD,
   script, confidences) → `oblocks` (blocks and paragraphs, one arena, a kind
   column) → `olines` → `owords` → `osyms` (boxes) → `oalts` (candidate TEXT,
   a run per symbol), each level a contiguous range of the one below.
6. **Tracer**: `inks` (ink layers), `components` (with contour in `pts` and
   containment), `rules` (centre-line endpoints, thickness, style),
   `prims` (fitted shapes with members in `refs`), and `tlines` (text-like
   components grouped into lines with a fitted baseline: an OCR-free witness
   of a text line's angle and ink layer).
7. **Views** record every raster kin reasons about (render with its layer,
   decode, the engine's thresholded image, kin's gray/binary/ink-mask/
   deskewed/polar derivatives) with a parent chain to the request: this is
   the binarised-image provenance D095 asked for.
8. **Confidence**: every head keeps the source's raw confidence verbatim; a
   `feats` row per word holds 28 integer features (§8.4.5: symbol confidence
   minimum and mean, alternative margin and ambiguity, dictionary flags,
   character-class plausibility, script consistency, kin ink, engine-binary
   ink and their Jaccard, body-ink share, component support, contrast,
   relative height and x-height, angle to the page, baseline deviation,
   spacing, seal and artifact overlap, cross-source similarities,
   recurrence, length, edge distance; the text layer's mirror set), each
   column filled by a named stage and -1 before it. Calibrated confidence is
   a per-kind integer scorecard over the columns followed by §9.6's isotonic
   map, both fitted offline (P10.1) and committed; the rules of §9.4-9.5 read
   feature columns against named thresholds until then.
9. **Bundles**: the arenas travel as four host structs (shared, text, ocr,
   trace); a kin function takes only the arenas it touches.
10. **Dump** version "1" (D056's was "0"), `ea` order, not a contract.

**v0 → v1 field map** (what `tools/evidence/v0.clj` inverts, P1.12 step 9):
glyph `page cp tstart tlen how font size box color alpha mode vis mcid word`
→ the same names (`color` = fill colour; `mcid` = the innermost mark's
`mcid`); glyph `baseline` → `by0`; glyph `rot` → `rot`; glyph `artifact` →
`mk-artifact-of` (v0's `watermark` 2 was a subtype stored as a type; v1 finds
it as `as` watermark and the projection prints it back as `watermark`).
word `page box glyphs text line src conf gap font size` → the same;
word `flags` (`fl`, never written by P1.7) → dropped. line `page box words
baseline dir rot src conf` → same, `baseline` = `by0`. path and image fields →
the same names. component fields → the same names plus `view`. ocrword
`page box text conf line block flags pass lang` → `owords` head, `line`
(its index within its run), the block of its line's paragraph (index within
the run), `style`, `oruns.pass`, `lang`; `baseline` → the word's
baseline at its centre x (±1 cpt, since v1 maps the endpoints then
interpolates where v0 interpolated in pixels then mapped); `alts` `{sym cp
conf}` → `oalts` with `sym` re-expressed as the code-point offset in the
word and `cp` as the candidate's first code point (v0 cut it there).

**Why**: fusion (§9) decides per word and per region which source is right and
whether ink is body text, furniture, watermark, seal or ghost. That needs, from
all three sources in one shape, what D095 found missing: symbol boxes,
slopes, the block/paragraph/line hierarchy, orientation and script,
multi-character alternatives, the engine's binarised image, and a way to
trust a word that does not depend on an engine confidence that scored a
correct heading 0. Two-point baselines are the smallest change that carries
slope for every source, and they are what the watermark (angle) and seal (arc)
defences read. Shared heads make alignment one piece of code instead of three.
Keeping pixel boxes and views makes the OCR and tracer evidence exact and
re-mappable, so a round-2 request can be planned in the pixels of the image
it will be asked about. Features rather than a single number keep each
signal explainable in the dump (§9.4's promise) and let calibration be
integer and exact on four targets. The cost is paid now because only one
probe, one OCR probe and two hand-built goldens use D056, and P1.7 is the
first real writer; after P1.8 and P6 every renumbering costs more.
**Alternatives**: (a) extend D056 in place with more columns: slope, symbols,
the hierarchy and views need new arenas regardless, and a v0-shaped `ocrwords`
with a chained single-code-point alternative list would still be wrong;
(b) let fusion read the OCR answer (the transcript's `emon.ocr` layout)
directly: couples fusion to one engine's layout and pixel space, and leaves
the three sources in three shapes; (c) one generic evidence-node arena with a
kind column, like the Model's nodes: simpler signatures, but a glyph would
pay for the widest row and every reader would check kinds (adopted only where
the rows really are alike: blocks and paragraphs); (d) one `Cells` per
source with a table directory in its header, as D059 does for PDF objects:
the text layer appends glyphs, marks, paths and images interleaved, which a
single growing buffer of fixed tables cannot hold; (e) one reference cell
packing arena and index (`ea << 24 | i`): caps an arena at 16 M rows and adds
an unpacking rule, to save one cell per reference; (f) boxes only in page
space (loses the exact pixel geometry OCR and the tracer measured) or only in
pixels (three sources would not share a space); (g) calibrated confidence
from the engine's number, or from a learned model (logistic regression,
boosted trees): the first is what D095 showed to fail, the second needs
floats and cannot say why; a scorecard plus isotonic map is integer and
readable, and a learned arbiter stays parked as SPEC §1.1 says; (h) baseline
as a y plus an angle: an angle needs trigonometry to reconstruct the line and
loses the endpoints the engine actually gave.
**Consequences**: glyphs grow from 20 to 32 cells (a 5,000-glyph page is 160 K
cells); kin signatures that take all arenas take 24 `Cells` (only `ev-init`,
`ev-check`, the dump and the probes do); `ar` row 2 is retired, not reused;
the OCR answer extension gains the fields the schema now has room for in
P6.5a (binarised image, row attributes, per-block orientation, word dictionary
flags, point size, a `:producer` map), until then they are -1.

### D097 — The `Crypto` port is seven pure forms over bytes; the four hosts agree on every byte; Rust's are written out
**Decision** (P3.2, D006). `emon.port` gains `crypto-md5`, `crypto-sha256`,
`crypto-sha384`, `crypto-sha512`, `crypto-rc4`, `crypto-aes-decrypt` and
`crypto-aes-encrypt`, in all four host trees. Each takes its input as a span of a
`Blob` (`data off len`; RC4 and AES also a key span) and APPENDS its output to a
`Cells`, one byte per cell, answering how many it appended, or -1 for a key it
cannot use (RC4: 1 to 256 bytes; AES: 16, 24 or 32). AES-CBC takes the IV as the
FIRST 16 bytes of the data, for both directions, so one calling convention serves
a PDF string (IV first), an R6 key unwrap (a zero IV prepended) and the R6 hash
(`encrypt` uses the IV and answers the ciphertext only). Decrypt strips PKCS#5
padding when asked (`pad` 1): a last byte of 1 to 16 removes that many bytes, any
other value removes none (what readers do with a damaged padding is keep the data),
and a ragged tail shorter than a block is dropped. Encrypt does not pad. Go, Java
and C# call their standard libraries (MD5, SHA-2, AES-ECB per block under a CBC
loop the port owns); RC4 is ten lines in each (`crypto/rc4` is deprecated, .NET has
none). **Rust's standard library has none of the six, and emon's Rust host has no
dependencies, so `host/rust/src/port.rs` carries MD5, SHA-256/384/512, RC4 and AES
itself** (the S-box is derived, not typed; the SHA and MD5 constants come from
`tools/crypto-constants.clj`, which derives them exactly from their definitions).
`kin/cryptotest.kin` holds all four hosts to the published vectors: RFC 1321 (MD5),
FIPS 180-4 (one-, two-block and 896-bit messages, SHA-256/384/512), the published RC4
vectors and RFC 6229's first bytes, FIPS 197 C.1-C.3 and NIST SP 800-38A F.2 (CBC,
128, 192 and 256 bits, four blocks), and padded ciphertext made by the JDK.
**The split** is D006's: primitives are the host's (pure functions over bytes, SPEC
7.2: a host must not substitute a "compatible" algorithm); the standard security
handler (padding string, the order of what is hashed, the 50 and 20 rounds, R6's
self-selecting hash, owner-to-user recovery, per-object keys) is kin, once, in
`kin/pdfcrypt.kin`.
**Why**: primitives are in every standard library but Rust's, and pure; an API of
"span in, cells out" needs no new tag and reuses `cells-bytes` for kin-built keys.
**Alternatives**: primitives in kin (AES and SHA-512 are not small, and the
not-worse rule would keep hand-written ones anyway); a Rust crate (emon's Rust host
is dependency-free, and `bb unit` builds `--offline`); separate IV and data
parameters (every call site would copy to split them).
- [ ] **Signed off** (maintainer)

### D098 — Encryption: one region of the document's cells, the password read from a `Src` and never kept, three distinct errors, decryption where an object is loaded
**Decision** (P3.2, D040, D058). **API** (additive): `pd-open-pw src pw ar dc a b st`,
where `pw` is a `Src` (an in-memory source over the bytes the embedder supplied with
the input); `pd-open` is the same with no password. New `pd-status` values: 8
`password-required`, 9 `password-incorrect`, 10 `encryption-unsupported`; 6
(`encrypted`) is no longer answered. **Order tried** (D040): the empty user password
(a file that opens for anyone, which is what `pdfinfo` does and what crawlers meet),
the supplied password as the user password, as the owner password, and the empty
owner password. `password-incorrect` is answered only when a password was supplied
(an empty one is none supplied), else `password-required`. Permission flags are read
for one thing, the key (`/P` is part of it); never for policy. **Supported**: the
standard handler, V1 (RC4-40), V2 (RC4 40 to 128), V4 (crypt filters; RC4 or AES-128,
streams and strings separately, `/Identity` and `/None` meaning not encrypted), V5
(AES-256, R5 and R6); `/EncryptMetadata false`; `/P` written unsigned (4294965956: the
lexer's integers saturate, so an int object now also keeps its value modulo 2^32,
`pl-int-wrapped`). **`encryption-unsupported`**: another `/Filter` (public-key
handlers), V0, V3, V beyond 5, R outside 2 to 6 or not matching V, a crypt filter whose
`/CFM` is not `V2`, `AESV2`, `AESV3` or `None`, or one the dictionary lacks.
**Where**: `pd-crypt-setup` (in `pd-open`, after the trailers, before the catalog)
reads `/Encrypt` and the first `/ID`, copies `O U OE UE ID` into a 352-cell region of
`dc` (`D_CR`, `kin/pdfcrypt.kin`), authenticates, and leaves the file key there.
From then on `pd-get` decrypts each object it LOADS FROM THE FILE: its strings in
place (`pd-secure`; an object inside an object stream is not, the stream was), and a
stream is tagged with its object number (`pl-stream-num`, in the record's sibling
link, which only container children use); `pd-stream-plan` appends the stream's
decryption to its plan (mode, key length, this object's key, three bytes to an
integer) so `pd-run-plan` needs nothing but the plan, and decrypts the span into its
output before the filter chain runs. Not decrypted: an XRef stream, a stream with a
`/Crypt` filter (taken as the identity, which every producer writes), the `/Metadata`
stream when `/EncryptMetadata` is false, and the `/Encrypt` dictionary itself (loaded
before the key exists, cached undecrypted). **The password is never kept**: it is read
from its `Src` while a candidate is tried, copied only into the three scratch cells
(zeroed before `cr-authenticate` returns), and the padded candidate parked in the
region (or the user password recovered from `/O`) is zeroed too; a law checks that the
first bytes of every password are found in none of the arena, the document, the
scratch or the page table after an open (D058: not in a Model, a dump or a transcript;
kin never has a place to write one). R2 to R4 take the password's bytes as given
(PDFDocEncoding, in practice Latin-1), R5 and R6 as UTF-8 cut to 127 bytes (SASLprep
is the embedder's). **Not handled**: an encrypted file whose trailer is lost (the key
needs the `/ID`: its page tree opens, its content is noise), embedded-file streams
with their own crypt filter names, `/Perms` validation.
**Why**: the maintainer's D040; decrypting at load keeps every reader above `pdfdoc`
(`pdffont`, content interpretation) unchanged, and the plan carrying the key keeps the
two-phase plan/run split that resolving references forced (D063).
**Alternatives**: the password stored in `dc` (rejected by D058); a decrypting `Src`
wrapper (a stream's key depends on its object, not on a byte offset); decrypting
strings lazily at each read (every consumer would need the object number).
- [ ] **Signed off** (maintainer)

### D099 — Repair by scanning: when the cross-reference or the catalog cannot be used, rebuild the table from `N G obj`; the last version of an object wins; a full scan is a legitimate read of the whole file
**Decision** (P3.1, D075). `pd-open` is unchanged for a good file. When it answers
`no-startxref`, `bad-xref`, `no-root` or `no-pages`, it REBUILDS the table by scanning
the whole source once (`pd-rebuild`) and opens again; when that does not help (no
object at all, no catalog, no pages), the ordinary answer stands. `need-bytes` and the
password errors are never repaired. **The scan** looks for `N G obj` at a token
boundary (start of file, after white space, or after the `>`, `]` or `)` that closes a
container), parses each with `pl-parse-indirect` and jumps to its end, so a stream is
skipped by its `/Length` (by `endstream` when that is wrong, and by a rescan when it is
indirect) and text in a stream that looks like an object is not taken for one (poppler
and qpdf, which rebuild line by line, ARE fooled by that: the fixture has no oracle).
The LAST `N G obj` in the file wins an object number (an incremental update); a
`trailer` keyword's dictionary and an XRef stream's dictionary that has a `/Root` are
collected, newest first (at most 16, as for a chain); the last `/Type /Catalog` object is
remembered; every `/Type /ObjStm` number is noted. **Object streams** are entered AFTER
the key is known (an encrypted file's are encrypted), in file order, from each stream's
own table, and an entry replaces an older object (by file offset) and never a newer
one. **The catalog**: the trailer's `/Root`; else the remembered catalog; else the first
object by number that is a `/Type /Catalog` dictionary, object-stream members included
(neither poppler nor qpdf does that last: a file cut before its xref stream, with the
catalog in an object stream, opens). The warning bit 512 (`the cross-reference was
rebuilt by scanning`) says it happened. **Over a source that misses** the scan checks
`ps-missed` every 4 KiB and answers `need-bytes` (D075 (3)): the host's doubling
read-ahead fetches the whole file in a few rounds, and a law holds the repair, the
encrypted repair and the 64- and 512-byte chunk sources to the in-memory run cell for
cell. **Cost**: nothing for a good file; a bad one is read through once more
(lexing every digit that starts a token). What is NOT repaired: a `/Length` is still
only trusted when `endstream` follows (D062), and an older section that fails to read
is still a warning, not a repair (the corpus's wrong `/Prev` file opens through the
scan because its catalog is in the section the chain lost).
**Why**: `bb` sweeps of the 4,000 real files found 17 unreadable by the ordinary path
(12 encrypted, 3 with a `startxref` that leads nowhere, 1 with a wrong `/Prev`, 1
with no `startxref`); poppler opens all of them.
**Alternatives**: repair always (every good file would pay a whole-file read, against
D058); a line-based scan (fooled by a stream's text); repair only the table and leave
object streams unindexed (the encrypted and xref-stream cases would fail).
- [ ] **Signed off** (maintainer)

### D100 — P3's tooling: poppler and qpdf are the oracles, in babashka; fixtures are committed; the sweep takes a password
**Decision**. `kin/scripts/pdf-compare` (babashka) sweeps `corpus/` through `pdf-sweep`
and `pdfinfo` and classes every file (`agree`, `disagree`, `distinct` error, ...);
`tools/pdfinfo.clj` is the shared geometry digest. `kin/scripts/pdf-sweep` reads
`path<TAB>password` lines. `kin/scripts/pdf-p3-fixtures` (babashka) writes
`test/pdf/p3/*.pdf` and `test/pdf/p3.txt` from a base PDF it builds: qpdf encrypts
it for every revision and algorithm (RC4-40 R2, RC4-128 R3, RC4-128 R4 through
crypt filters, AES-128 R4, AES-256 R5 and R6), each with the empty user password and
with one, with extraction, printing and modification forbidden, with object streams,
with clear-text metadata, with a long and a non-ASCII password; the damaged files are
qpdf's output broken by hand; the unsupported ones carry hand-made `/Encrypt`
dictionaries. Every file that should open is opened by `pdfinfo` with the same
password (or, where poppler's own R6 password check refuses a long one, by poppler
reading qpdf's decryption of it), and must report the page count and geometry digest
of the plain twin. The laws (`kin/pdfcrypttest.kin`) check the status, the geometry,
the first bytes of page 1's content, the Info `/Title`, and a deep hash of the catalog
and Info (every reference followed, every string and decoded stream) against the
plain twin's. Two fixtures have NO oracle, by design (a decoy object inside a stream;
a file cut before its xref stream); their answers are the files' by construction.
The committed fixtures mean a test run needs neither tool. `qpdf` was installed with
`brew install --build-from-source qpdf` (no bottle for this macOS).
**Why**: an independent implementation must say what emon's key derivation and
repair should have produced; emon cannot be its own oracle.
- [ ] **Signed off** (maintainer)

### D101 — Marked-content spans are a tenth evidence arena, `spans`; every BMC/BDC is one
**Folded into `marks` by P1.12 (D127): the arena is retired, the row is a `marks` row.**
**Decision**: `emon.evidence` gains `spans` (stride 22; `ev-init`, `ev-check` and `ev-emit` take it last;
the dump has a `spans` array; `sp-new`, `sp-get`, `sp-set`, `sf-*` fields). A span is one BMC/BDC..EMC of one
content stream: its tag, parent span (-1), `/MCID` (-1), the `/Artifact` class `ar` (0 when the tag is not
`/Artifact`; `ar-other` = 5 is an artifact that says no more: a bare `BMC`, `/Type /Page`), `/Type`,
`/Subtype`, `/ActualText`, `/Alt`, `/E` and `/Lang` as runs of `etext`, the run of glyphs shown inside it
(nested spans' included), flags (`mc`: 1 inline dictionary, 2 through `/Properties`, 4 name not found, 8
unterminated), and `stm`, the object number of the Form XObject whose stream it is in (0: the page's), so P3.7
can join on (`stm`, MCID). The class is the most specific of Subtype and Type: Watermark, else Background,
else Pagination (Type, or Subtype Header/Footer), else Layout, else other. A glyph's own `mcid` and `artifact`
are the INNERMOST enclosing span's that has one. Strings are decoded: UTF-16BE or LE with a byte order mark (PDF 2.0
language escapes dropped), UTF-8 with its BOM, else PDFDocEncoding (Annex D.2). A property list read through
`/Properties` is copied into the operand entries first (`pc-ingest-props`) so one reader serves both forms. An
unbalanced EMC is ignored (warning 64); a span left open when its stream ends is closed there and flagged; 256
deep is the bound (the rest are counted and their EMCs balanced).
**Why**: D076 asks that P3.7 be a tree reader and a join; `ar` had no value for "an artifact that says nothing".
**Alternatives**: spans only as glyph fields (loses Alt/E/Lang/ActualText and empty spans: a Figure has no glyph);
a flat MCID to string table (loses nesting and the form it came from).
**Validation**: the 3 tagged fixtures: 111 spans equal pypdf's (tag, MCID, Type, Subtype, ActualText, Alt, Lang,
`tools/oracle/tagged-check.py`) and 2,553 of 2,553 glyphs lie inside a span with an MCID or an artifact; a
hand-made `marked.pdf` holds all seventeen shapes (inline and named properties, every artifact class, UTF-16,
UTF-8 and PDFDoc strings, nesting, an unbalanced EMC, an open span at the end of a page and of a Form XObject).
- [ ] **Signed off** (maintainer)

### D102 — The content interpreter: one workspace, four font slots, what it reads and what it skips
**Decision**: `emon.pdfcontent` (`pc-init`, `pc-page`, `pc-document`) takes the document layer's workspace
plus `cx` (one `Cells`: scalars, the 256-deep graphics-state stack, the four font slots, the matrices), `ox`
(the operands of one operator: entries of four cells, then the bytes of names and strings; arrays and
dictionaries are flattened with a count; at most 4,096 entries), `cs` (the decoded bytes of the page's streams with
each running Form XObject on top; its OWN lexer, because the object parser interns names and strings into the
document arena for good), `out`, four font `Cells`, and `glyphs`, `etext`, `spans`, `words`, `lines`. Fonts
(`emon.pdffont` keeps one in one `Cells`) live in FOUR SLOTS with least-recently-used replacement, keyed by the font
dictionary's handle, so a page of more than four fonts reloads some; a font's id in the evidence is its index in the
order the document first showed it. Read: `q Q cm BT ET Tf Tc Tw Tz TL Ts Tr Td TD Tm T* Tj TJ ' "`, `Do` of a
Form XObject (its `/Matrix`, `/Resources`, its own `q`; to `limit-of` 6 deep; a form that draws itself or is too
deep is skipped with warning 16, where a limit breach would refuse the page), `BDC BMC EMC`, `g rg k sc scn cs` (the
fill colour by operand count: 1 gray, 3 RGB, 4 CMYK; 0 black after `cs`) and `gs` (`/ca` as per-mille alpha).
Skipped with their operands: paths, images, shading, clipping, stroking colours, everything else; an inline image
is skipped to its `EI`. A glyph is recorded with mode 3 (invisible) too: what to make of it is fusion's. `Tw` applies
to a one-byte code 32 only; vertical fonts (`/DW2`, not `/W2`) advance down. A glyph's box is its cell (width from the
font, height from ascent and descent; a Type 3 font's from `/FontBBox` times `/FontMatrix`, which `pfn-load` now
reads: `test/fonts/fonts.txt`'s Type 3 row changed from ascent 0 to 1000); its `size` is the font size times the
length of the text matrix's y axis; its rotation counter-clockwise from the page's x axis, centi-degrees. A glyph
whose value is U+0000 or U+FFFD has none (cp -1, how unknown): Skia writes <0000> for the parts of a Devanagari
conjunct. An unknown width is half an em (warning 128). Every object loaded is followed by a miss check; a miss
ends the run with `need-bytes` (7) and is never taken for a missing font (D075); the 512-byte async law (D074) holds it
to the in-memory run cell for cell, on all 36 synthetic documents, the 3 tagged, the 8 fixtures, the 3 P1.5
fixtures and 120 real pages.
**Why**: kin cannot allocate; a Rust `Cells` cannot be borrowed twice (every call binds what it reads first);
a bounded, allocation-free interpreter that reads operators as a stream is what the four targets can share.
**Not done**: encrypted files (P3.2; emon reports `encrypted`), vector paths and images (P3), clipping and the
visibility checks SPEC 8.1 lists (`vz`), annotations, `/UserUnit`, the stroking colour, patterns, a font
loaded per Type 3 glyph procedure.
- [ ] **Signed off** (maintainer)

### D103 — `Fx` has 24 fractional bits, not 16 (changes D009's "16.16")
**Decision**: `emon.fx`'s `Fx` is an `I64` with 24 fractional bits: one is 16,777,216, values clamp to +-2^50
(6.7e7 centipoints). The product of two does not fit an `I64`, so `fx-mul` splits its second factor in two
twelve-bit halves and carries the remainder (exact to the half-unit for a coordinate of 2^45 times a scale
of 2^28). `fx-atan2` and `fx-cos`/`fx-sin` are CORDIC (centi-degrees in, `Fx` out; sixteen steps, within a
centi-degree and 3e-5), `fx-isqrt`/`fx-sqrt`/`fx-hypot` integer square roots; nothing rounds differently on
different targets (wrapping `add64`/`mul64`, division `quot` with the rounding written out).
**Why**: measured against pdfminer.six, 16.16 put 12 per cent of 501,000 glyphs (first 148 files) more than 0.05 pt from the
right answer: 0.1 is 6553.6 units of 2^-16, so Ghostscript's `0.1 cm` followed by `10 cm` multiplies to 1.00006 and
a baseline at y = 720 is 0.044 pt off; Chrome's 0.24 scale and a coordinate of 2,500 is 1.4 centipoints off.
At 24 bits: 99.83 per cent of 2,014,475 upright glyphs within 0.05 pt, and the rest are x extents of a few odd fonts.
**Alternatives**: 16.16 as SPEC 3.4 said (the error above); exact rationals (no allocation in kin); 32.32 (a scale
times a coordinate overflows).
- [ ] **Signed off** (maintainer)

### D104 — Words and lines: gap thresholds in the size, rotated text by centres, right-to-left in logical order
**Decision**: `pc-group` runs once per page over its glyphs in stream order. A space glyph (U+0020, U+00A0,
U+1680, U+2000..U+200A, U+202F, U+205F, U+3000, the tab) ends a word and belongs to none; otherwise the next glyph
joins when its rotation agrees to half a degree, its baseline is within 45 per cent of the size (sub- and
superscripts stay), and the gap along the writing axis is at most 16 per cent of the size and the step back at
most 40 per cent (constants `WORD_GAP_PM`, `WORD_BACK_PM`, `OFFSET_PM`, tuned on the corpus against
pdfminer.six). Text within three degrees of a quarter turn is measured along that axis; any other angle along its
own direction from the glyph centres, with 60 per cent of the size taken for the two half-advances. A line is a
run of consecutive words on one baseline (45 per cent), each starting within 2 sizes of the last one's end (the
gap is recorded in the word, `wf-gap`) or a step back of 60 per cent: a wider gap is a column gutter or table cell,
which is the layout stage's (P1.8, P3). RIGHT-TO-LEFT: a word with more right-to-left than left-to-right
letters whose glyphs run left to right on the page (the producers' visual order) has its TEXT written in logical
order (glyphs reversed, runs of digits and Latin letters turned back); a line with more right-to-left glyphs is
`ld-rtl`, and its words (whose rows are swapped in the word arena, their glyph runs untouched, their `gap` and
`line` re-pointed) are in logical order too. `ttb`/`btt` lines are not made: a rotated line has `rot`.
**Why**: SPEC 8.1 groups by gap against the space width and by baseline and direction; Arabic and Hebrew glyph
streams from Chrome are in visual order, and text in the wrong order is wrong text.
**Known limits**: letter-spaced text (a `Tc` over 16 per cent of the size) breaks into letters; a rotated word of wide
capitals may break; a mixed-direction word is reordered one level deep (no embedding levels); a right-to-left word of
one glyph cannot be told from its neighbours' order.
**Measured**: against pdfminer.six on 536 real files (1,013 pages), words: precision 80.2 per cent, recall 97.2 per
cent (emon splits more: pdfminer glues words where a PDF has no space glyph); lines: 76.4 / 63.7 per cent (pdfminer
cuts lines at every gap over 2 of its character widths and emon at 2 sizes, and it lays out text boxes).
- [ ] **Signed off** (maintainer)

### D105 — How P1.7 was validated: four independent witnesses, all babashka but the two that drive a Python library
**Decision**: (1) `kin/scripts/content-fixtures` (bb) writes 8 small PDFs (`test/content/`) and the expected glyph
boxes of four of them from an exact-rational evaluator of the text operators (Clojure ratios; the page turned a quarter
at a time; AFM widths of the standard fonts read from `test/fonts`): 303 rows, each glyph's code point, box,
baseline, size, angle and render mode within one centipoint in `kin/pdfcontenttest.kin`, and the marked-content
rows, words, counts and warning bits written out by hand. (2) `kin/scripts/content-check` (bb) holds the 36 Chrome-printed
synthetic documents' line text (`test/content/synth.txt`) to the text of the HTML they were printed from, each side
decomposed by the vendored UCD: every one of the HTML's 64,127 characters has a glyph but 37 (Devanagari vowel signs
Skia gave no ToUnicode; one CJK variant), and the 1,116 glyphs that are not in the HTML are the printer's (running
furniture, list markers, page numbers, repeated table heads, the DRAFT watermark), counted per document in
`kin/scripts/synth-extras.edn` (a ratchet: fewer fails too); and the three P1.5 fixtures' 52 words have poppler's
`pdftotext -bbox` boxes to 0.03 pt. (3) `tools/oracle/content-corpus.py` and `tools/oracle/tagged-check.py` (Python, because
the references are; `~/.cache/emon/pylib`, never a dependency) compare the generated Rust with pdfminer.six and pypdf on real
files and on the tagged fixtures; `test/content/real.txt` ratchets 120 pages whose glyph streams pdfminer agreed with.
(4) the laws: 10 row-file and async laws plus a fuzz of 1,700 corrupted or cut files per arm. `kin/scripts/content-sweep`
(the generated Rust, overflow checks on, `rows|digest|glyphs|text|json|all`) is the development tool all of them use;
`kin/scripts/content-goldens` writes `synth.txt`, `tagged.txt` and the three evidence dumps from it.
**Why**: the goldens are kin's own output, so they are worth what is held against them (the maintainer's policy: bb
for emon's scripting, Python only where a third-party reference must be driven, under `tools/oracle/`).
**Alternatives**: committing the corpus's glyph streams (not committable); checking the goldens only against themselves.
- [ ] **Signed off** (maintainer)

### D106 — `bb fuzz`: one kin probe on every host, a babashka harness that judges by the whole answer line, and a smoke subset as a gate (P2.12)
**Decision**: (1) What runs on a host is `kin/fuzzprobe.kin`'s `fz-case`: the PDF layer's
whole read path (open, page table, every stream the cross-reference names decoded, every
dictionary loaded as a font with every one-byte code mapped) folded into ONE text line
(status name, page count, warning bits, hashes of geometry, objects, decoded bytes and font
answers). With a `chunk` it reads through D058's async source, re-running on a fresh view
until a run does not miss, then once more into the answer. The four drivers
(`test/fuzz/{rust,go,java,cs}`) read file names on stdin and print `name TAB memory-line
TAB async-line` per file, flushing each, a panic/exception caught and printed as `PANIC
...` (Rust prints the `gen/` file:line). (2) The harness is `tools/fuzz/` (babashka, SplitMix64
per D094 and `kin/splitmix.kin`): case i of seed S is a pure function of (S, i, fixture bytes)
(`sm-at`), so `bb fuzz --seed S --only I --dump F` reproduces it. Mutators are truncation
(uniform, near the end, near `xref`/`endobj`/`endstream`), 1 to 8 byte flips, splices
(delete/duplicate/insert/swap) and structure-aware edits of xref entries, `/Length` (direct and
`N 0 R`), object headers, `stream`/`endstream` boundaries and the trailer/object-stream numbers
(`/Size /Prev /Root /First /N /Count /XRefStm`, `startxref`, `/W`, `/Index`, `/Kids`). Each case
applies one to three. (3) A verdict is a failure when a host panics or throws, hangs (no answer
within `--timeout-ms`, 10 s: the host is killed and the batch resumes after that case), dies
(a Rust or Go stack overflow is not a panic), gives a line different from another host's (a
`status-mismatch` when the `pd-status` name differs, a `detail-mismatch` when only the hashes do),
or gives an async line different from its own memory line: a miss must never surface as an
answer. Rust is built with overflow and bounds checks ON (as `pdf-sweep` is). (4) A failing
mutant is minimised (spans removed while the same set of verdicts holds; 60 s budget) and saved
as `test/fuzz/crashers/NAME.pdf` plus `NAME.edn` (seed, index, fixture, operations, verdicts,
`:status :open|:fixed`). Every crasher is replayed on every run: `:fixed` must pass, `:open` is
reported and does not gate. (5) `bb fuzz` with no arguments is the gate (`fuzz` in `bb gates`): a
self-test of the harness against `tools/fuzz/fakehost.clj` (hang, crash, panic, both mismatches, an
async miss, resumption after a kill, the minimiser), every fixture unmutated, 300 mutants of seed 1,
and the crashers, on Rust and Go, about 15 s. `bb fuzz --long N` (every toolchain present, no gate)
and `--hosts all` for Java and C# are not gates: Java and C# add about 25 s per 1000 files.
**Why**: a line that carries every answer makes "the same status and error name on every host" the
weakest of several checks rather than the only one, and puts the loop that D058 leaves to the host
in kin once, where the four hosts share it. Seeds per case (not one stream per run) keep a case
reproducible after the run that found it. A gate that cannot fail is not a gate, so the harness is
tested against a host built to misbehave.
**Alternatives**: one kin-side fuzz loop over SplitMix (`pdfdoctest`'s `dt-corruptions` already
does this for fault-freedom, but it cannot see a hang, a cross-host difference or a process that
dies, and it only flips bytes); comparing only the status name (hides a font or stream decode that
differs); killing a hang with a thread in the host (a stack overflow or an infinite loop in kin
does not yield).
**Open**: the content interpreter (P1.7) is not merged, so `fz-run` does not run it; corpus PDFs
(`corpus/`, real damaged files, P3.1) are not among the seeds; coverage is not measured (no
instrumented build), so "how much of the PDF layer do 300 cases reach" is unanswered; a
grammar-aware generator of whole objects (rather than mutation) is not built.
- [ ] **Signed off** (maintainer)

### D107 — Three signed-overflow sites in the PDF layer now saturate at 2^31-1 (found by `bb fuzz`, P2.12)
**Decision**: hostile numbers reached integer additions that overflowed I32: a panic in a
checked Rust build, a silent wrap in Go, Java and C# (so, with overflow checks on, a different
answer from Rust). Each is now saturating and has a committed reproducer in
`test/fuzz/crashers/`. (1) `pd-shifted` (`kin/pdfdoc.kin`) adds the header shift to a file offset
and saturates; the five places that added the shift to an offset use it (`pd-open` for `startxref`,
`pd-load-direct`, the `/XRefStm` position, `pd-hint-extents`, `pd-section`). The lexer already
saturates integers, so `startxref 9999999999` or an xref entry `04000000054 0 n` is 2147483647, and
adding a header shift of 1 overflowed (`xref-offset-overflow-s11-i983`,
`startxref-offset-overflow-s21-i118`). (2) `pd-xfill`: with `/Index [2147483647 n]` an entry's
object number `first + k` overflowed; the put is skipped for object numbers past 2^31-1 (they name
nothing; `pd-ent-put` would drop them) and the entry is still consumed, so the byte position of
the next subsection is unchanged (`xrefstream-index-overflow-s31-i12269`). (3) The CMap number
lexer in `kin/pdffont.kin` clamped to 214748364 and then computed `x10 + d`, which overflows for a
digit of 8 or 9; it now saturates at 2147483647 and is otherwise the same for every number that fit
(`cmap-number-overflow-s41-i1146`, a mutated real font).
**Why**: the fixes are what the lexer's own rule ("integers saturate at 2^31-1") implies and change
no result that did not overflow (every unit law and fixture is unchanged). Another agent is changing
`pdfdoc.kin` (xref repair, encryption): from `pdfdoc.kin` this is one helper, its five call sites
and one guard in `pd-xfill`.
**Alternatives**: wrapping arithmetic for offsets and object numbers (keeps the negative-position
behaviour that only happened to be safe); rejecting the entry when it is parsed (a policy change
for P3.1's repair to make). Release builds (no overflow checks) wrapped and did not crash, so
these are robustness fixes (no panic under checks, one answer on every host), not a correctness
regression.
**Open**: the long runs (about 55,000 mutants over seeds 7, 11, 21, 31 and 41 on Rust and Go, 7,000
of them on all four hosts) found only these three; Java and C# are not overflow-checked, so an
overflow there is a wrap, found only as a difference from Rust's panic. Minimisation is span
removal only: a mutated compressed stream does not shrink (the cmap crasher is 18 KB).
- [ ] **Signed off** (maintainer)

### D108 — Initials (drop caps) are found locally, right after lines, and joined to the first word; a letter that is not text is recovered, never lost
**Decision**: SPEC 10.9. Three forms: `drop` (sinks into the first N lines, which wrap it), `raised` (stands on the
first baseline), `margin` (hangs outside the column beside the first lines); a whole enlarged first word is an initial
of that word; a small-caps lead-in after it is an ordinary `smallcaps` span. Detection is a local, per-page test that
runs after lines (D104) and BEFORE blocks, indents, styles and headings are measured: a run of 1-3 code points (with
the punctuation `::first-letter` would take: `“N`) or a first word at least `initial-min` (2.0) times the size of the
lines beside it, whose cap top meets the first line's cap top within half a body size and whose ink bottom falls in
line N's band, lines 1..N starting after its end edge and line N+1 back at its start edge (drop); or on line 1's
baseline with line 2 at its start edge (raised); mirrored for right-to-left. Cap tops come from the font's
`/CapHeight` (else 0.7 of the size) or, on raster, the tracer's ink box; `fonts` gains `/CapHeight` and `/XHeight`
(1/1000 em, -1 absent) as a minor addition (spare cells if P1.12 has not landed; P1.12's owner decides where). An
initial drawn as outlines, as an image, or in a Type 3 font without Unicode is recovered from `/ActualText` or `/Alt`,
then a targeted single-character OCR pass (a new `op` pass kind `initial`; Tesseract's drop-cap bit in `osyms`
corroborates), then the tracer; failing all, U+FFFD flagged `low-conf`. Joining: the initial's text is prepended with
NO space unless a healthy tagged tree or `/ActualText` holds one; when the letter is itself a word in the locale's
one-letter-word table (`A`, `I`, `O` for English; with no locale, the union of the shipped tables) the join is
flagged `low-conf`. Consequences elsewhere (SPEC 10.1, 10.3): lines 1..N start at the initial's start edge for the
indent rule, the paragraph-gap rule measures from line 1, the initial's glyphs are excluded from the style inventory,
the body-size statistics and feature column 18; a heading candidate is never an initial nor a 1-2 code-point block
beside or inside a paragraph's first lines, while a glossary letter divider (`A` above `Aardvark …`, wrapping
nothing) stays a heading.
**Found** (a probe page printed by Chrome's `printToPDF` on this machine and read by P1.7's interpreter through
`kin/scripts/content-sweep`; not committed, P2.13 makes it corpus): with
`p::first-letter{float:left;font-size:4.1em;line-height:.8}` in Georgia 11 pt, (a) Chrome paints all three floats of
the page before any in-flow text, so content-stream order puts `O`, `A` and `“N` first on the page: stream order
cannot place an initial; (b) `A` + `long time` and `O` + `nce upon` have the same 4.5 pt gap and no space glyph
(the page has 189 space glyphs, none there): whether the letter is a word is not on the page; (c) the float's
baseline is 6.75 pt above line 3's, on no line's baseline; its font box starts 5.8 pt above line 1's, but the cap
tops agree within 1.9 pt; (d) `::first-letter` takes the opening quote (`“N`, two code points); (e) a raised initial
(`font-size:3em`, not floated) is already joined by D104 into the word `Raised`; (f) `hyphens:auto` prints U+002D,
not U+00AD, so SPEC 3.6's locale rule governs dehyphenation. **Real corpus**: pdfminer.six over the first 4 pages of
3,190 PDFs in `corpus/layout` and `corpus/multilingual`: 184 hits of "a letter at least 2.2 times the page's median
size with body text to its right and below it" in 37 files, most of them titles and logos; true drop caps checked by
hand in `layout/pdfa-eng-wds` 0182448 (p1 `T` + small-caps `HE BUREAU`, two lines), 4271234 (p2 `B`, journal
style, 4.7 times body), 0795114 (p2 `W`) and 0116458 (p1 `O`, p3 `I`, p4 `G`, Caslon; pdfminer glued its `O` into
a headline's line). Rare in a web crawl (about 1 file in 800), common in the novels and magazines the maintainer
names.
**Why**: a mishandled initial costs a word per paragraph (`O` `nce`: one substitution and one insertion), usually a
false one-letter heading and a false paragraph break, and, placed by its baseline, a letter moved to line 3; local
detection before blocks is the one place no later stage has already mis-grouped it. Joining is the better default
(`It`, `In`, `At`, `As` open more paragraphs than `I` and `A` alone).
**Alternatives**: detect after blocks (the heading and paragraph stages have already split it off); use stream order
(wrong for Chrome); insert a space by default (wrong for most openings); drop an unreadable initial (a character
silently lost); a per-locale lexicon to decide `A long` vs `At the` (SPEC 17.4: the maintainer's call).
- [ ] **Signed off** (maintainer)

### D109 — An initial is an inline `initial` in the Model; its text stays in `text`; HTML wraps it in an unstyled span
**Decision**: a new inline kind `initial` (`nk` 26; schema minor 1.1) whose children are its text inlines, with
`form` (`drop`/`raised`/`margin`: a new enum `ifm`, codes 1-3, in the node's `sub`) and `lines` (N for `drop` and
`margin`, absent for `raised`; the node's `aux`), and the ordinary inline `src`/`conf`/`flags` (`["vector","ocr",
"infer"]` for an outline letter read by OCR; `low-conf` as D108 says). New `fk` rows: `form` 76, `lines` 77. Its
presence makes `inlines` informative; `text` is unchanged (`Once upon`). Its box is in the paragraph's `at` (and its
own at `detail: words`). HTML: `<span data-emon="initial" data-emon-form="drop" data-emon-lines="3">O</span>nce`, no
style; `html-emit` option bit `ho-initials-plain` 128 writes the bare text instead. The seal subkind `initials`
(handwritten initials, D016) is unrelated and keeps its name.
**Why**: the `text` a scraper reads 90 % of the time is right either way; the fact is kept for the converters that
want it (a novel converted to an e-book re-styles it); an inline marks the exact extent (a quote mark, a whole word,
a small-caps lead-in beside it) where a paragraph attribute would need a character offset.
**Alternatives**: a paragraph field `initial: {len, form, lines}` (an offset into `text`, awkward when `inlines`
already splits the first word); a `span` style bit `initial` (no room for `form` and `lines`); no representation
(loses what the maintainer asked to keep); `<span style="float:left;font-size:…">` (styling the SPEC does not make).
- [ ] **Signed off** (maintainer)

### D110 — Alignment is logical, measured against the fragment's box, written only when confident; HTML adds style only on request
**Decision**: SPEC 10.10. `align` on `paragraph` (exists, `al`), and newly on `heading`, `caption` and `cell` (as an
attribute: their `sub` is taken), values `start`/`center`/`end`/`justify` (no `justify` for cells), LOGICAL: start
and end resolve by the block's base direction (UAX #9 P2-P3 over its text), never `left`/`right`. The test runs
against the column fragment's box (a cell's: the table column's text extent over its body cells), with a tolerance of
a quarter body size, the last line out of the justify and ragged tests, a first-line indent removed; a single-line
block is `center` or `end` from geometry, `start` only by its style class's majority, at lower confidence. Written
only at or above `align-min` (setting, default 800 while uncalibrated); there is no separate confidence field (the
evidence dump explains a decision). Decimal-aligned numeric columns whose end edges disagree get no value. HTML:
`data-emon-align` as D069 already writes; `html-emit` option bit `ho-align-style` 256 adds
`style="text-align:start|center|end|justify"` and `dir="auto"` (so start and end resolve for right-to-left text in
a browser).
**Why**: the maintainer asked for it where feasible: it is, from geometry alone, for any block of two or more lines;
crawlers mostly ignore it, converters want it; logical values are correct under right-to-left and are what the Model
already said; a confidently wrong alignment is worse than none.
**Alternatives**: `left`/`right` (wrong under RTL); a document default plus deviations (consumers must resolve
inheritance); a `decimal` value for cells (nothing consumes it and the numbers read the same); inline style by
default (not clean HTML, D013); measuring against the block's own box (every block looks justified).
- [ ] **Signed off** (maintainer)

### D111 — Columns: gutters from words, spanning elements, a vertical-first cut tree; the column map is kept as the page `layout`
**Decision**: SPEC 10.1.1. Gutters are the tall, narrow whitespace rectangles over WORDS persisting 3 rows (or a
vertical rule with text on both sides); lines crossing a gutter are split; an element crossing a gutter ends it and
is a spanning element; the cut tree cuts vertically whenever a gutter runs the region's full height and
horizontally only at spanning elements or at full-width gaps over 1.5 times the median paragraph gap; leaves are
column fragments numbered `band`/`col` in reading order (RTL: right first). Fragment roles `body`/`sidebar`/`margin`;
sidebar and margin content become `note` blocks with the new role `sidebar` (`rl` 9) or `margin`, placed after the
body block being read where they begin. Gutter line numbers are furniture (`margin`) and are removed first, as
recurring furniture is. The map is kept: a Model arena `layout` (stride 8: page, band, col, role, x0 y0 x1 y1; a
page's cells contiguous, its `pages` row naming the run in two spare cells), JSON `pages[].layout` (`fk` `layout` 78,
`band` 79, `col` 80, `box` 81), and a node's regions are one per fragment. Checked on the probe of D108: Chrome
writes one line per column run, so D104 does not merge columns there; a producer that writes rows across columns,
or a gutter under D104's two sizes (the synthetic 3-column paper sets 9.5 pt with a 0.25 in gap: 1.89 sizes), would,
which is why gutters are sought over words.
**Why**: plain recursive XY-cut fails in exactly two common ways: paragraph gaps that line up across balanced
columns (it reads the top halves of both columns first) and a figure spanning two of three columns (it cuts the third
column too); restricting horizontal cuts to spanning elements fixes both. Keeping the map costs one small arena and
lets a consumer check or re-render what emon decided.
**Alternatives**: plain XY-cut; Breuel's full whitespace cover with constrained text-line finding (the gutter sweep
is its tall-narrow special case, simpler in integers); Docstrum or Voronoi segmentation (bottom-up, for skewed scans;
parked as a fallback for pages the cut tree scores badly); a learned layout model (not kin, not identical across
hosts); discarding the map after ordering (then no consumer can recover it).
- [ ] **Signed off** (maintainer)

### D112 — A paragraph broken by a column or a page is one paragraph; the break lives in its regions; hyphenation follows SPEC 3.6
**Decision**: SPEC 10.1.2: integer points over named signals (a shared tagged `P` decides; a final hyphen, a last line
reaching the column's end edge, no terminal punctuation, a lowercase continuation and a content-stream continuation
add; a short last line, an indented continuation where paragraphs are indented, a list marker, a heading candidate or
another style class subtract) against `join-min`. Joined: one paragraph, one region per fragment, the page break as
the inline `break` milestone (as P1.8 already does across pages), a column break recorded only by the regions and the
page `layout`; the break is a line end for dehyphenation, nothing more. Blocks that fall between the fragments in
reading order (note, figure, table, caption) move after the paragraph, in their order. A join near the threshold
lowers the paragraph's confidence.
**Why**: SPEC 3.1.1 already makes a page-crossing paragraph one node; columns are the same case, more often.
**Alternatives**: a `break` milestone with a `column` field (puts a layout fact the regions already hold into the
inline text); joining whenever the last line has no terminal punctuation (joins every paragraph of an unindented,
spaced style that happens to start at a column top); splitting and flagging (a crawler sees half-sentences).
- [ ] **Signed off** (maintainer)

### D113 — HTML for columns: one logical flow in reading order; the column structure only as metadata
**Decision**: no CSS columns and no per-page wrappers. Blocks are written in reading order, a joined paragraph once.
With the `geometry` option every block also gets `data-emon-col="page:band:col"` (its first fragment) and, when it
has more than one, `data-emon-cols` (space-separated, in order); the page map itself stays in JSON. Sidebar and
margin notes are `<aside data-emon-role="sidebar|margin">` in reading order, without `role="doc-footnote"`.
**Why**: the maintainer's own observation: columns only work with a page boundary to wrap on; a crawler and a screen
read a flow, and the Model's tree is logical (D011).
**Alternatives**: (a) per-page `<section data-emon-page>` with CSS `column-count`: reproduces the look but fights the
heading-built sections, splits paragraphs at pages, and makes a crawler index pages; (b) CSS columns around logical
runs as an opt-in "visual" option: without a page height a column is as tall as the article, so the reader scrolls
down and back up; parked; (c) `<aside>` for side columns: adopted, for the roles above; (d) `<article>` per newspaper
story: the Model has no article kind; parked until the corpus shows the need.
- [ ] **Signed off** (maintainer)

### D114 — The synthetic corpus grows by 19 documents for initials, alignment and columns; the gold gains `facts`
**Decision**: P2.13 adds to `test/corpus/synth/` (authored by `tools/synth/make_html.clj`, printed by `print.sh`,
gold by `html2gold.clj`/`build.clj`, D071-D073):
* initials: `initial-firstletter` (`::first-letter` floats of 2, 3 and 4 lines; an `A` and an `I` that are words and
  an `I` + `t` and `A` + `t` that are not; `“N`; a small-caps lead-in), `initial-raised`, `initial-image` (a PNG made
  by ImageMagick at authoring time: a letter on an ornamented tile, `alt` the letter), `initial-vector` (an inline
  SVG whose `T` and `L` are filled paths, never `<text>`), `initial-firstword` (a floated enlarged first word),
  `initial-columns` (a 3-line drop cap in each column of a two-column page, one of them at a column top),
  `initial-rtl` (Hebrew, floated right), `initial-cjk` (Japanese, a three-line first character, no join space) and
  `initial-letter-headings` (the negative control: a glossary whose `A`, `B`, `C` dividers are headings);
* alignment: `align-paragraphs` (start, end, center and justify, each multi-line; single-line center and end; a
  justified paragraph of one line; centered headings and a caption), `align-justify-hyphen` (narrow justified
  columns, `hyphens:auto`, `lang="en"`), `align-table-numeric` (right-aligned numeric columns, a centered header
  row, a decimal column with mixed decimals, left text columns) and `align-rtl` (Hebrew start, end, center,
  justify);
* columns: `columns-continue` (two columns over three pages, justified and hyphenated, paragraphs continuing across
  column AND page breaks, a heading mid-column, page footnotes), `columns-uneven` (a 2fr/1fr grid: body plus a
  boxed sidebar with its own heading), `columns-span-figure` (a `column-span:all` figure and table mid-flow, and a
  figure inside one column), `columns-mid-page` (full width, then 2 columns, full width, then 3, on one page),
  `columns-margin-notes` (notes absolutely positioned in the outer margin beside paragraphs), and
  `columns-newspaper` (masthead, a headline over three of five columns, several stories, a column rule, a
  `Continued on page 2` jump that stays two blocks).
Authoring attributes html2gold reads (it never computes CSS): `data-initial="drop|raised|margin"` with `data-lines`
(on a `<p>`: html2gold takes the CSS `::first-letter` extent, leading and trailing punctuation plus one grapheme; on
a `<span>`, `<img>` or `<svg>`: that element, its text from content, `alt` or `data-initial-text`), `data-align`
(logical values), and `data-cols` on a column container. Gold conventions (D073 continued): 9. an initial is an
`initial` inline wrapping its text, and the paragraph's `text` reads through it with no space unless the HTML has
one; 10. `align` is annotated only where authored, and a justified block that printed as one line is `start`; 11.
the gold gains an optional top-level `facts` object, found at print time by `build.clj` and committed in
`MANIFEST.json` as `rows` is: `frags` (for layout documents, every leaf block's fragments `[page, band, col]` in
order, located by anchoring its words' unique 4-grams in `pdftotext -bbox-layout` output), `lines` (the printed line
count of every block that has `align` or an initial; `build.clj` also fails if a drop cap does not span the
`data-lines` it declares), and `hyphens` (`{id, printed, word}` for every word the printer hyphenated at a line or
column end); `annotated` gains `layout` (boolean: `facts.frags` is exhaustive) and attribute keys `initial` (`form`,
`lines`) and `align` for paragraph, heading, caption and cell; 12. the gold text is the HTML's (no printed hyphens);
a run with no locale is scored against gold words rewritten to their `printed` form at `facts.hyphens`, because SPEC
3.6 requires emon to keep them; a run with `locale: en` against the gold as written. Existing golds do not change
(optionally, `align: end` on the `td.r` cells of `invoice` and `bank-statement`, a reviewed regeneration).
**Real documents that exercise these** (never committed, D086): drop caps in `layout/pdfa-eng-wds` 0182448,
4271234, 0795114 and 0116458 (D108); two columns in `layout/arxiv` 1512.03385, 1810.04805 and 2206.01062, and the
scientific, patent (gutter line numbers) and financial-report pages of `layout/doclaynet-v1.2`, whose `Text`,
`Section-header`, `Page-header`/`-footer` and `Footnote` boxes score reading order and column assignment by IoU;
right-aligned numeric cells in `tables/icdar2013`, `tables/gov-reports` and `financial/bank-statement-benchmark`;
Hebrew and Arabic alignment in `multilingual/udhr`.
**Why**: each feature gets a positive case, the producer's idiom (floats, `column-span`, `hyphens:auto`) and a
negative control; the facts are what only the print knows, found once, like D072's `rows`.
**Alternatives**: computing layout facts in a gate (needs poppler in every gate; D041 forbids it); annotating
alignment from computed CSS (html2gold would need a cascade); one big document per feature (worse localisation).
- [ ] **Signed off** (maintainer)

### D115 — Scorer metrics for initials, alignment and columns
**Decision**: `tools/accuracy/score.clj` (D077's pooled counts, match rule unchanged) gains, each honouring
`annotated`: `initial-p`/`-r` (a predicted `initial` in a block matched to a gold block with one), `initial-form-acc`,
`first-word-acc` (over gold blocks with an initial: the matched prediction's first whitespace token equals the
gold's), `initial-false-heading` (predicted headings of 1-3 code points equal to a gold initial's text or the prefix
of a gold first word; lower is better); `align-acc` and `align-cov` per kind (paragraph, heading, caption, cell:
accuracy over matched blocks both annotate; coverage = gold-aligned blocks the prediction aligns) and `align-wrong`
(predicted aligns the gold contradicts, per predicted aligned block; lower is better); `frag-col-acc` (matched leaf
blocks whose predicted fragments, mapped to columns through the predicted page `layout`, equal `facts.frags` in band
and col), `join-p`/`-r` (positives: gold blocks with two or more fragments; a hit is one predicted block holding all
of it; a false join is a predicted block spanning a gold block boundary that falls on a fragment boundary) and
`hyphen-acc` (at `facts.hyphens`: the printed form without a locale, the joined word with one). Reading-order Kendall
tau is unchanged and is reported for the `columns` category. D079's categories gain `initials` and `align`.
Lower-is-better metrics join `cer`, `wer`, `watermark-leak` in the ratchet's direction table. What a mishandled
initial costs, for reading the board: as its own block, WER +2 and CER +1 per paragraph, a paragraph-boundary false
positive and usually a false heading; placed on line N, WER +3 and a moved letter; unrecovered, CER +1 and WER +1;
`A long` read as `Along`, CER +1 and WER +2.
**Why**: CER and WER already see these errors but cannot say which feature caused them; a metric per feature makes a
ratchet move attributable.
**Alternatives**: CER, WER and paragraph boundaries alone (an initials fix and a columns regression would net out);
scoring alignment only on multi-line blocks (hides the single-line policy).
- [ ] **Signed off** (maintainer)

### D116 — The fuzz probe also runs page content interpretation and seeds from `test/pdf/p3`; one more overflow fixed (P2.12 merge)
**Decision**: after merging P2.12 onto main (repair, encryption D097-D100, the content interpreter D101-D105), `fz-run`
in `kin/fuzzprobe.kin` ends with `fz-content`: `pc-document` over the opened file, its status, warning bits, operator,
glyph, word and span counts and a hash of every glyph's code point joining the answer line. The fixtures gain
`test/pdf/p3/*.pdf` (the encrypted and repair files). The branch's overflow fixes were re-applied to main's `pdfdoc`
(the startxref shift now in `pd-open-normal`, beside the others). The first content run found one panic: a negative
glyph size was clamped above only (`pc-continues` and the line join in `pdfcontent.kin`), so `sz * 450` overflowed;
clamped to +-200000 now, reproducer `test/fuzz/crashers/content-size-overflow-s1-i76.pdf`.
**Open**: `bb fuzz --long 5000 --seed 101` (Rust, Java, C#; Go skipped that run on a build-cache error) found nothing further.
- [ ] **Signed off** (maintainer)

### D117 — Assets: opt-in pixels for figures and seals, bytes out of band, a canonical PNG written by kin (resolves D020's open item)
**Decision** (SPEC §10.11; items P3.11-P3.13, P6.1a, P8.5, P9.3). The maintainer asked for "a
flag to let it try and isolate and clip/crop graphics/art from the pdf/image and retain it in
the output html as base64 … not needed for most cases, but could be useful for like a pdf →
epub conversion".
1. **Option.** `assets` = `none` (default: nothing runs, every output byte-identical to a run
   without the feature) / `images` (a figure or seal whose content is one embedded image gets
   that image) / `figures` (also every other figure or seal, cut from the image it lies in or
   rendered). Limits `asset-dpi` 150, `asset-pixels` 4,194,304, `asset-bytes` 4 MiB,
   `assets-bytes` 32 MiB; `asset-signatures` off. Limits degrade to a `noasset` reason and
   never fail a page.
2. **Sources and origins.** `embedded` (a JPEG passed through, only when a browser shows it as
   the PDF painted it: SPEC §10.11's list — including no non-default `/Decode`, no masks, no
   EXIF rotation, no arithmetic coding, 1 or 3 components); `decoded` (pixels from kin's
   filters or the host's `decode-img`, with `/Decode`, colour, masks, `/Matte`, orientation
   and the visible crop applied by kin in integers, then PNG); `cropped` (an exact rectangle of
   an image's pixels: scans, raster inputs, a figure inside a larger image; no resampling, no
   deskew); `rendered` (a `crop` request to the host `Renderer`, with text, 2 pt pad, dpi
   stepped down by 3/4 to fit `asset-pixels`). A clipped image is kept whole when its visible
   part is ≥ 98 % of it and cut to the clip's box otherwise; a non-rectangular clip, a skew,
   or paint over the image sends it to `rendered` at `figures` and is flagged at `images`.
   Crops keep the text: a chart's labels are the picture; `labels` stays a text copy.
3. **Model.** Bytes are out of band: arenas `assets` (24) and `uses` (8) and one `Buf` of bytes
   in the Model; JSON gets a top-level `assets` array (metadata: id, name, type, origin,
   from, object, bytes, sha256, w, h, dpi, flags, uses with node and region) only when
   `assets` is on, and figure/seal nodes get `asset` or `noasset`. Identical bytes are one
   asset with several uses (a logo on every page is one file). Admission is in node-id order;
   the first new asset that overflows `assets-bytes` closes the budget.
4. **Serialization.** HTML option `assets`: `inline` (default; `data:` URIs), `external`
   (`src` = `asset-prefix` + `a<id>.png|jpg`, the caller writes `model.asset_files()`: the EPUB
   and browser form) or `omit`. `<figure><img src alt width height data-emon-asset>` then the
   placeholder's content; `alt` is the PDF's `/Alt` (D076) or the seal label, else empty
   (`data-emon-alt="missing"` when there is no caption either); `width`/`height` are the
   on-page size in CSS pixels. The library API hands out borrows of the bytes. JSON never
   carries bytes.
5. **Identity.** Every byte of an asset is kin's or the input's: passthrough bytes are the
   input's, pixels come from kin or from transcribed answers (`crop` 5 and `decode-img` 6 are
   transcribed like `render`; `decode-img`'s kin-made payload is named by its SHA-256 in the
   record and never transcribed, like `bytes`), and the PNG, DEFLATE and base64 writers are
   kin. So `sha256` is over the **encoded bytes**, and the identity gate compares JSON, HTML
   in all three modes and the asset files byte for byte, and recomputes each hash. Tests
   additionally compare **decoded pixels** against independently generated originals.
6. **The canonical PNG** is fully specified in SPEC §10.11 (filters, LZ77 parameters, block
   choice, package-merge, chunks) so a change to it is a visible, reviewed change of every
   asset hash.

**Why**: (a) *Opt-in, three levels*: most callers are crawlers (D020 stands for them), and
`images` is cheap, exact and available before P6 (no Renderer, no region detection deciding
pixels), while `figures` needs P6.2 and P8.1 and is where the EPUB case really lives (charts
are vector). (b) *Out of band*: the Model is integer arenas that must cross WASM and C cheaply
(§3.8), and JSON is a scraper's format and the gate subject; base64 in it would multiply its
size by the images' and make every consumer pay for what one wanted. (c) *Kin writes the PNG*:
the maintainer's question was whether to compare pixel hashes or mandate a canonical
encoding. Comparing pixels only would leave HTML with `data:` URIs, and the `sha256` field,
different per host, so the byte-identity claim of §12.2 would silently exclude the one output
the maintainer asked for. A host zlib cannot be mandated to agree byte for byte (D090 found
the same); a kin DEFLATE agrees by construction, costs one module of about the size of
`inflate`, and is also the writer D090 and D095 left open for transcripts. (d) *Passthrough
only for JPEG, and only when faithful*: JPEG is the one PDF image codec every browser and EPUB
reader shows, and passing it through is lossless and small; JPX, JBIG2 and CCITT are not
shown reliably, and a JPEG with inverted `/Decode`, CMYK, masks or an EXIF rotation would
display differently from the PDF, which for a converter is an accuracy bug. (e) *Crops keep
text*: the Renderer's layer mask is all text or none, so "remove only the re-emitted words" is
not expressible; without text a chart loses its axes, and text drawn as outlines would stay
anyway. (f) *Signatures withheld by default*: a crawler that turned on `figures` for charts
should not start publishing handwritten signatures; a converter that wants them says so.
(g) *Dedup and first-overflow budget*: deterministic, cheap, and a repeated logo costs once.

**Alternatives**: (1) always emit crops (rejected: D020's crawlers pay in size for pixels
they do not want); (2) bytes as base64 in the JSON (rejected, above) or a second JSON
document with them (a format nobody reads; the API and the sidecar do it better); (3) host
PNG encoders and a gate over decoded pixels (rejected: HTML and `sha256` would differ per
host); (4) stored-block PNGs as in D090 (canonical and trivial, but 3-10× larger, which is
exactly what `data:` URIs cannot afford); (5) always transcode to PNG, no passthrough
(canonical and simpler, but photos grow several times and the author's JPEG was already
the best version there is); (6) WebP or AVIF output (not universally readable in EPUB 3
readers; no encoder anyone would write in kin); (7) a Renderer crop for every figure,
embedded images included (uniform, but lossy against the original, host-dependent pixels
where exact bytes were available, and impossible before P6.2); (8) rendering crops without
text, or with the figure's labels masked out (not expressible with the port's layer mask,
and wrong for charts); (9) the full image always, even when clipped (shows what the author
hid) or always the clipped part (re-encodes a JPEG to drop a 1 % sliver: hence the 98 %
rule); (10) first-fit budget packing (deterministic too, but "why is figure 40 present when
figure 12 is missing" is harder to explain than a budget that closes); (11) content-addressed
file names (`<sha>.png`: dedup across documents, but long, and the `sha256` field already
gives a caller that); (12) inventing alt text from `labels` or the subkind (emon says what it
knows, D013).

**Consequences**: two plan kinds (`crop` 5, `decode-img` 6) and a payload `Buf` per unit in
the host loops; a `Buf` port in four hosts; the Model grows two arenas and a byte store; the
schema gains `assets`, `asset`, `noasset` and six settings additively (D055; pre-release, so
the version string stays `1.0`); P3.13 also brings provisional image figures before P8.1,
so PDFs with images gain `figure` placeholders in default output then (owed by SPEC §10.6
anyway). A later decision may move D090's transcript PNGs to this writer (every committed
transcript would change once). Flate images are still inflated whole into `Cells` (four bytes
a sample), bounded by `asset-pixels`, until `inflate` can write a `Buf`. In the browser,
`inline` HTML at the 32 MiB budget peaks near 150 MB of heap, so the JS SDK documents
`external` plus `asset.blob()` (P9.3).
- [ ] **Signed off** (maintainer)

### D118 — How the layout-feature metrics read the gold and the Model (P2.14)
**Decision**: D115's metrics, with the points D115 left open settled as follows. Gold reading: a block kind
is scored for initials or alignment only when `annotated.attributes[kind]` holds `initial` or `align`; column
and continuation metrics only when `annotated.layout` is true and `facts.frags` exists; `hyphen-acc` and the
rewrite only from `facts.hyphens`. `facts.frags` is read as a map `{block-id: [[page band col] ...]}` (a list
of `{id, frags}` is accepted too); `facts.hyphens` as a list of `{id, printed, word}` with `printed` the word as
a no-locale run must write it (`hy-phenated`: hyphen kept, parts joined). An initial is the first inline of kind
`initial` (its text its own `text` or its leaves'). Matching is D077's, unchanged; cells are matched by
(row, column) inside a matched table pair. Counts: `align-acc` is over matched blocks both sides align, and is
0 (not absent) when the gold has aligned blocks and none pair up; `align-cov` is those pairs over gold aligned
blocks; `align-wrong` is (pairs - correct) over predicted aligned blocks (0 when none); the per-kind keys are
`align-acc-paragraph`, `-heading`, `-caption`, `-cell` and `align-cov-*`. `initial-form-acc` is 0 when the gold
has initials and none was found; `initial-false-heading` is predicted headings of 1-3 code points that equal a
gold initial's text or prefix a gold first word and are not matched to a gold heading, per gold initial.
`frag-col-acc` is over ALL gold blocks with `facts.frags` (an unmatched block misses), a region mapped through
the predicted page `layout` cell holding its centre, none meaning a miss. `join-r` counts a gold multi-fragment
block as held when one predicted block holds at least 0.9 of its words in order (independent of the match, so
a moved or typo'd block still counts); a false join is a predicted block holding the last two words of a gold
block and the first two of the next across a gold fragment boundary. `hyphen-acc` looks for `printed` (no
locale) or `word` (a locale) in the predicted block matched to the gold block. The hyphen rewrite applies
when the predicted `settings.locale` is null. The oracle writes what a perfect emon writes from the gold's
`facts` (printed hyphens without a locale; a `layout` of one cell per `[page band col]` and one region per
fragment); the degraded predictor adds the three damages of the brief, cutting a multi-fragment paragraph at
its middle word because the gold has no offsets. `align-wrong` cannot rise under the degraded predictor (it drops
every `align`), so it is the one new metric the fixture leaves at its best value.
**Why**: each choice makes a metric a number that moves when the feature does, is never absent merely because
the prediction failed, and never counts a prediction the gold cannot judge.
**Alternatives**: `frag-col-acc` over matched blocks only (a block the matcher drops could not hurt it); `join-r`
through the match (a split paragraph can still be matched to a half at similarity 0.5); `align-wrong` counting
predicted alignments on blocks the gold leaves unaligned (the gold annotates only where authored, so it cannot
judge them).
- [ ] **Signed off** (maintainer)

### D120 — P3.8: annotations, forms and signatures are evidence in their own arenas; `/V` and the appearance are both kept and compared; signatures are read, not validated; `pdfdoc` is untouched
**Decision** (P3.8, D034, D096). (1) **Arenas** (SPEC 8.4.7): `annots` (38 cells a row), `quads`
(8), `fields` (32) and `sigs` (36), integer `Cells`, strings as runs of one `txt` arena (a code
point a cell, `tstart tlen`, absent `-1 0`: the evidence `etext` shape); `kin/pdfannot.kin` owns
the layouts and `pa-dump` writes them as canonical JSON. They take `ea` codes 25 to 28 when P1.12
folds them into the bundles. `kin/pdfdoc.kin`, `pdflex.kin` and `pdfcrypt.kin` are not edited:
the readers use only what they export. (2) **Geometry**: a `/Rect` is normalized and kept twice,
as written (default user space) and in page space (SPEC 3.4: crop-box origin, y down,
`/Rotate` clockwise), the box around the four corners mapped; `/QuadPoints` become quads in page
space (at most 64 an annotation), in the order the file wrote them. (3) **Actions**: the first
action's kind (`URI GoTo GoToR GoToE Launch Named JavaScript SubmitForm`, else `other`), its
target (URI, file specification, named action) as text and, for GoTo and GoToR, a destination;
the `/Next` chain is counted (at most 8), not followed. A `/Dest` on the annotation is a GoTo.
A URI is read as UTF-8 where it is well formed and Latin-1 byte by byte where it is not (qpdf
shows the same bytes as PDFDocEncoding and so differs on a non-ASCII URI). (4) **Destinations**
(8 cells: page, fit kind, four arguments in centipoints or `null`, the name it was looked up by).
A name is looked up in the old catalog `/Dests` dictionary and the `/Names` `/Dests` tree (a name
object tries the dictionary first, a string the tree first, each falls back to the other); the
tree search honours `/Limits` and, on a miss, walks the whole tree (a producer that wrote them
wrong); one search visits at most 20,000 nodes and a whole run at most 4,000,000 nodes and
dictionary entries (a looping tree and a hundred thousand links cost that and no more). **An
integer first element is a page INDEX**: the spec allows it only in a remote destination, but
wkhtmltopdf writes it in local ones and poppler and pdf.js follow it; it is taken as a page of
this document when in range (and always for GoToR), and the record says so by adding 16 to its
kind, so a reader that wants the strict reading can ignore those. qpdf and `pdfinfo -dests` do not
follow them; the oracle comparison accounts for it. A reference to something that is not a page is
page -1. (5) **Fields**: the tree is walked once (a visited set by object number, depth 26, 100,000
terminal fields); `/FT /Ff /V /DV /Q /MaxLen` are inherited; a child with a `/T` (or `/Kids`) is a
field, one without is a widget of its parent; the name is the dotted partial names. `/V` is kept
as text (a name as its text, an array joined with a line feed), and its kind. A key whose value
is `null` or a reference to a missing object is absent. (6) **`/V` against the appearance**: a
widget's appearance stream (`/AP /N`, or the state `/AS` names) is decoded and the text it shows
is read (`BT`..`ET`, `Tj` `TJ` `'` `"`, a line break at a `Td` that moves down and at `T*`), as
Latin-1, at most 64 KiB; a Form XObject it draws and text in a composite font are not followed. The
first widget's text is compared with `/V` and the verdict kept (`equal differ value-only
appearance-only`); a check box or radio group says how many of its widgets are on and whether one
that is on is the state `/V` names. **SPEC 8.1's "`/V` usually wins" is fusion's rule**, not this
reader's: both are recorded. (7) **Signatures**: `/Name` (a text string, so UTF-16 works), `/M` as
written and parsed (a date with a zone, or none, or out of range: unparsed, never guessed),
`/Reason`, `/Location`, `/ContactInfo`, `/Filter`, `/SubFilter`, the first four `/ByteRange`
numbers and how many ranges, the byte count of `/Contents`, whether it is a document time stamp, and
whether it came from `/Perms /DocMDP`. The signer a certificate names is inside the PKCS#7 bytes
and is not read; nothing is validated (SPEC 8.1). (8) **Stamp and Watermark** annotations are rows
like the others, with `/Name`, `/Contents`, `/T`, `/CA` (per-mille), `/F` (hidden, print and
no-view bits are watermark evidence), the appearance stream's object number and its text; P8.2 and
§9.5 decide what a seal or a watermark is (D034: a stamp that says `APPROVED` is evidence, not a
seal). (9) **Names by bytes**: the readers need about 100 names the object arena does not intern
(it holds 192, shared with P1.7), so `tools/pdfxnames.clj` (babashka, checked by `bb unit`) generates
`kin/pdfxnames.kin`, a table of names and XMP strings compared by bytes; a dictionary is walked
rather than hashed, which is fine at these sizes. (10) **A source that misses** (D075): `pa-run`
answers -1 when the view missed, at each field, each page and at the end; nothing it writes is
kept, and the laws read every fixture through 64- and 512-byte async sources to the same dump.
**Why**: the annotations are the author's own statement of what is a link, a stamp, a filled
form and a signature; a fusion that decides on them needs them whole, in page space, with what
disagreed kept beside what was claimed (D034), and in a shape that does not change when the file
is read through a source that misses.
**Alternatives**: extend `pdfdoc`'s page record (another agent works beside it, and a page record
is the wrong place for a variable-length list); one record per annotation with the field nested
(an array of arrays kin cannot build); obey `/V` or the appearance (either loses what the other
says); resolve the integer page index strictly (a third of a wkhtmltopdf document's bookmarks and
links would point nowhere, where the PDF viewers take them); intern the names in the arena
(192 slots, two agents adding).
- [ ] **Signed off** (maintainer)

### D121 — P3.9: outlines, page labels, Info and XMP, `/Lang`: read once, as facts; XMP by a tolerant scanner; nothing believed
**Decision** (P3.9, D034, D076). (1) **Outlines** (`ol`, 24 cells a row, pre-order): walked with no
recursion (an explicit stack of next sibling, parent and depth), a visited set over object
numbers ends a chain that loops (a `/Next` back to a sibling, a `/First` back to an ancestor) and
says so on the parent (bit 4), depth stops at 64 (bit 8), rows at 200,000; `/Parent`, `/Prev` and
`/Last` are ignored (the tree is what the walk finds: qpdf follows a loop and lists its
repeats). A row has depth, parent, children, subtree size, title (text),
`/Count` as written, `/F`, `/C` as 0xRRGGBB, and an action or a destination; a `/Dest` is a GoTo.
(2) **Page labels** (`lb`): the number tree is read to 20,000 nodes into pairs, sorted by key with
the order of equal keys kept, at most 10,000 ranges; the first of a repeated key wins (the rest is
kept with bit 4 and ignored), a key past the last page is kept with bit 1, a negative key is
dropped, and pages before the first range have **no** label (the caller may print the decimal
number or nothing: this says what the file says). Styles: decimal; Roman, upper and lower, with M
repeated past 3999 (poppler and qpdf do the same); letters A to Z then AA to ZZ then AAA; no style
is the prefix alone; `/St` defaults to 1 and is used as written (0 prints `0`). (3) **Info**: the
keys `Title Author Subject Keywords Creator Producer CreationDate ModDate Trapped` as text
strings: PDFDocEncoding (including the eight accent codes at 0x18 and the block at 0x80 to 0xA0 that
differ from Latin-1), UTF-16BE or LE by BOM with language escapes dropped, UTF-8 by BOM (PDF 2.0;
poppler reads it as PDFDocEncoding and shows mojibake); a surrogate or a value past U+10FFFF is
U+FFFD, never passed on (Java and C# would encode a lone surrogate differently from Rust and
Go). A date is read from the DECODED text (it may be UTF-16), every part after the year
optional, the zone in minutes east of UTC or "none given" (-99999; poppler prints UTC for it),
out-of-range parts reject the date (a Y2K-bug `D:19100...` stays a raw string; poppler guesses).
(4) **XMP**: `kin/pdfmeta.kin`'s scanner reads the packet by bytes (up to 1 MiB, 48 elements deep,
64 namespace declarations; UTF-16 packets are noted and not read): it resolves the prefixes the
packet declares to the six namespaces emon knows (so `dc:` may be spelled `x:`), decodes
entities, CDATA and UTF-8, and writes one row per simple element, attribute and `rdf:li` item of a
`rdf:Description` (kind simple, seq, bag, alt, attribute; `xml:lang`); a structure (`xmpMM:History`) is
skipped. The header gets `pdfuaid:part` and `rev` (D076: a trust signal, verified not obeyed
(D034)), `pdfaid:part` and `conformance`. (5) **The catalog**: `/Lang`, `/Version`, `/MarkInfo
/Marked`, whether there is a `/StructTreeRoot`, an `/AcroForm` (`/NeedAppearances`, `/SigFlags`,
`/XFA`), `/Outlines`, `/PageLabels`, `/Perms`, `/ViewerPreferences /DisplayDocTitle`. A `/Lang` is
recorded, not judged: P4 and P9 compare it with what the text is. (6) **Named destinations**
are listed on request (`nd`), the old dictionary first and then the tree, every row, at most
50,000. (7) **Source**: `pd-source-json` (D065) is NOT changed, so the five golden `Source` files
stay byte-identical; the engine that builds the Source (P1.10) adds the catalogue's fields
from `pm-dump`'s cells. (8) Encrypted files: every string and the XMP stream arrive decrypted
(D098: `pd-get` decrypts where an object is loaded); the `/Metadata` stream of a file with
`/EncryptMetadata false` is read as it is.
**Why**: these are the author's own signals of structure, language and accessibility, cheap to
read once and worth having whole before stage 3 decides what a heading is (D018) or whether a
document is in a language it claims. The scanner is small because the packets that exist are a
small subset of RDF/XML; the corpus's 1,150 packets give the same properties as `clojure.data.xml` finds
(where it can parse the packet at all).
**Alternatives**: a full XML parser in kin (thousands of lines for a subset's worth of value); the
library's own XMP reader through a host port (four hosts, four behaviours: SPEC 7.2); labels
as a per-page array (a 100,000-page book pays for it, and the ranges are the structure); trust
`/Count` for an outline's size (wrong in real files).
- [ ] **Signed off** (maintainer)

### D122 — P3.8 and P3.9 are verified against poppler and qpdf over the whole corpus, in babashka, by a tool that counts items; real-file facts come from the oracles
**Decision** (P3.8, P3.9, D034, D094, D100). `kin/scripts/pdf-catalog` runs the generated Rust over
any list of PDFs (one `path<TAB>password` a line) and prints each file's canonical dump
(`pa-dump`, `pm-dump`); `kin/scripts/pdf-catalog-compare` (babashka, NOT a gate) compares every
category with an independent reader and counts agreement as **items** (a page's label, a
bookmark, a field attribute, a link) and **files** (every item agrees). The oracles share no code
with emon: labels (qpdf's per-page label dictionary, turned into text by numbering code written out
in the tool), outlines (qpdf; a named `/A` GoTo qpdf does not follow through poppler's table),
fields (qpdf `acroform`: name, type, flags, value, page, matched by widget), named destinations
(`pdfinfo -dests`), URLs (`pdfinfo -url`), links (qpdf's raw objects: rectangle, action, target
page), signatures (`pdfsig`: field name and signed ranges; qpdf objects: `/Name /Reason /Location
/M`), stamps (qpdf objects), Info and flags (`pdfinfo -isodates`), XMP (`pdfinfo -meta` parsed
by `clojure.data.xml`), `/Lang` (qpdf objects). **Results, 4,000 files** (`bb
kin/scripts/pdf-catalog-compare`): labels 24,731 of 24,731 pages; outlines 5,474 of 5,474; named
destinations 13,223 of 13,223; fields 19,215 of 19,215; XMP 2,298 of 2,298; `/Lang` and
`/Version` 8,000 of 8,000; Info 39,997 of 40,000; signatures 30 of 30; stamps 30 of 30;
links and URLs as D120 says. **What still differs, and is emon's on purpose**: a URI with a
non-ASCII byte (emon reads UTF-8, qpdf PDFDocEncoding); an integer page index (emon and
pdf.js follow it, qpdf and poppler's table do not); a UTF-8 Keywords string with a BOM (poppler
shows mojibake); a `D:` date poppler guesses at; an outline that loops (qpdf lists repeats);
a URL poppler shows with a trailing `?` for a control character. **Fixtures**
(`kin/scripts/pdf-catalog-fixtures`, test/pdf/catalog/, goldens and `test/pdf/catalog.txt`):
known by construction, cross-checked by the same tool (they agree except where a fixture
was built to differ: the loop, the BOM, a date with no seconds), read by
`kin/pdfcatalogtest.kin` on all four targets: the dump is the golden byte for byte, the labels
of every range, the outline tree, the loop, the inherited fields, the link of every action, the
signature's signer, the metadata of every encoding (plain and encrypted), no truncation or
corruption faults, every fixture through 64- and 512-byte async sources. **Real files**
(`test/pdf/catalog-corpus.txt`, 130 rows, written by `pdf-catalog-compare --write`): counts and
digests of the bookmarks, the labels, the links and the `/Lang` that qpdf and poppler state;
kin computes the same digests from its arenas and they are equal on four targets. The corpus
is not in git (D086): the row law needs `../corpus`, as `pdf/corpus.txt`'s does.
**Why**: accuracy is paramount (D023) and emon cannot be its own oracle (D100); rates over the
whole corpus found what the fixtures could not (an integer page index, `/Dest null`, a string
qpdf shows as bytes, poppler's own date and URL quirks), and each was a decision or a fix.
**Alternatives**: Python oracles (D094: bb for everything but third-party libraries, and none is
installed); comparing whole dumps (a format change would be an oracle change); per-file verdicts
only (a 600-page book with one wrong label would count as one failure among 4,000).
- [ ] **Signed off** (maintainer)

---

### D123 — `Buf`: a growable byte buffer beside `Cells` (P3.11; D117)
**Decision**: a fifth opaque port type, `Buf`, tagged in `emon.lang` (Rust `&mut crate::port::Buf`,
Go `*port.Buf`, Java and C# `emon.Port.Buf`, nested in the port class as `Cells` is) with seven
forms in all four host trees, `@kin:link`-annotated and compared by `bb hosts`: `buf-len`,
`buf-at` (0..255 on every host), `buf-push` (the low eight bits; answers the **new length**),
`buf-set` (low eight bits), `buf-truncate` (to a length, as `cells-drop`), `buf-append-blob b
blob start end`, and `buf-blob` (a borrow as a `Blob`, valid until the next write). The Rust host is
`pub struct Buf(pub Vec<u8>)`, so the host loop reads the bytes out with no copy; `buf-blob` hands
out the storage in Rust and Go and a copy in Java and C# (a `byte[]` cannot be a prefix of a
larger array), which a caller must not depend on either way. Out-of-range indices, spans and
truncations panic or throw, as `Cells` does. The test vocabulary gains `new-buf`. The laws are
`kin/buftest.kin`.
**Why**: the assets of D117 and the PNG writer produce bytes by the megabyte (a 32 MiB budget),
which `Cells` holds at four bytes each (128 MiB, four times the heap in a browser); an `&mut`
tag keeps the rule the other arenas live by (bind an inner read first) rather than inventing a
shared-borrow `Buf` with interior mutability like `Bitmap`, which no caller here needs (a PNG
writer writes one buffer and reads another). `buf-push` answers the new length because every
caller that uses the answer (an IDAT length, a chunk offset) wants "how long is it now"; it
differs from `cells-push` (the index written) on purpose and the forms are named for what they
answer, which is written at the port.
**Alternatives**: (1) `Cells` of bytes, as inflate's output (rejected: the cost above);
(2) `buf-blob` a snapshot everywhere (simple and uniform, but a copy of every asset on Rust,
where the borrow is free); (3) a borrow everywhere (impossible on Java and C# without a slice
type); (4) answering the index as `cells-push` does (the brief's "as `cells-push`" and "the new
length" disagree; the explicit words were followed); (5) the laws in `porttest.kin` (it is the
probe `bb check` compares, not a law file; a second law file costs nothing).
- [ ] **Signed off** (maintainer)

### D124 — The canonical PNG as built: `emon.deflate`, `emon.png`, and what holds them (P3.11; D117)
**Decision**: SPEC §10.11's sentence about the zlib stream is fixed to the following, all of it
in `kin/deflate.kin` and `kin/png.kin` and now also in the SPEC.
1. *LZ77.* The hash is `((b0<<10) ^ (b1<<5) ^ b2) & 32767`; candidates nearest first, at most
   32, none further than 32,768; a candidate replaces the best only when strictly longer (so on a
   tie the nearest stays). **One step of lazy matching**: having a match at *p* the encoder looks
   at *p*+1 once and, if that match is strictly longer, emits *p* as a literal and takes it, with
   no further look (zlib keeps looking; this is simpler to state, and its ratio cost was not measured separately).
   Positions are inserted into the hash once, in order, each before the next is searched; the
   last two bytes of a stream never are. A position is decided when 262 bytes of lookahead exist
   (or at `df-finish`), a decision reads at most 259 ahead, so **the output does not depend on how
   the input is split** into calls or rows (a law: byte by byte, chunks, and whole, equal).
2. *Blocks.* One every 16,384 symbols and a last one; the last is empty when the symbols divide
   evenly. Each is stored, fixed or dynamic by exact bit count (the header, the code-length code,
   the run-length coded lengths, every symbol and extra bit, and for stored the padding to the
   byte the stream is at), ties stored, fixed, dynamic. **Stored is a candidate only for a block
   covering at most 32,768 input bytes**, what the 64 KiB ring (32 KiB of window and the
   lookahead) still holds; a block of 16,384 symbols of long matches covers more, and is
   never stored anyway.
3. *Huffman.* Lengths by package-merge (limit 15; 7 for the code-length code), symbols sorted by
   (frequency, symbol), a leaf before a package of equal weight. Fewer than two used symbols: length 1
   for symbol 0 (or 1 when 0 is the used one) and for the used one, as zlib's dummy codes, so every
   code is complete and every decoder accepts it (an all-zero distance code is valid for zlib but not
   for every decoder). The literal/length and distance lengths are one sequence, run-length coded
   greedily (zeros: 18 for 11..138, then 17 for 3..10; a value once, then 16 for 3..6 repeats; shorter
   as literals); HLIT and HDIST are trimmed to the last non-zero length.
4. *PNG.* Chunks IHDR, PLTE and tRNS (palette), pHYs (`(dpi100*100 + 127) / 254`, unit metre), IDATs
   of exactly 65,536 bytes but the last, IEND. Per row the filter of smallest sum of absolute signed
   bytes, ties to the lower number, all five scored in one pass and only the chosen one fed to the
   compressor; filter 0 for palette and depth below 8. The API is `png-begin ws out z w h ctype depth
   dpi100 pal npal trns`, `png-row ws out z cells start`, `png-end ws out z`; `ws` is one `Cells`
   for both modules (deflate's 167,936 cells, then the CRC table and the previous row), `z` the
   scratch `Buf`; the palette is four cells per entry (r, g, b, a) and `trns` the number of alphas
   written. `df-force` (stored/fixed/dynamic) exists for the laws only.
5. *What holds it.* `bb unit` (241 laws per arm): round trips through `emon.inflate` of every block
   type and sizes from 0 to 1 MiB, two streams worked out by hand (empty, one byte: zlib's own
   bytes), a match at distance 32,768, the split independence, and a **separately written** test
   reader (bitwise CRC, chunk order, IDAT sizes, unfilter) over the twelve images
   (`emon.pngprobe`: gray, gray+alpha, RGB, RGBA at 8 and 16 bits, 1/2/4-bit, palettes with tRNS,
   odd sizes 1x1 to 3000x1, a scan-like strip) against `test/png/digests.txt`;
   `bb check` (line 7): the four targets print the twelve digests, equal to that file;
   `bb png-check` (a gate): java.util.zip and ImageMagick read the twelve and six real images
   (a 300-dpi A4 page is 2481x3508) to exactly their pixels; `bb png-bench` (not a gate): the same
   real page and photo on all four targets, same bytes, time and size against zlib-6.
   The independent decoders found one generator bug (a palette index beyond the palette, which
   emon's own reader had accepted): fixed, which is what they are for.
**Why**: the SPEC sentence is a description; a hash needs the parts it leaves open chosen and
written down, and each was chosen for being the simplest that is correct and deterministic (the
one-step lazy rule, leaf-first merges, dummy codes), not for ratio. The ratio is measured
(DEVLOG P3.11): 1.02-1.07 times zlib level 6 over the same rows, which is what "reasonable" means for
an encoder whose output is the same on four languages; the one-pass five-filter score and the
64 KiB ring keep the working set under 700 KiB and a page under 0.4 s everywhere.
**Alternatives**: (1) zlib's continuing lazy chain and `good_length`/`nice_length` cut-offs
(probably better, not measured; several more constants to specify and keep equal); (2) a 32 KiB ring
with the lookahead borrowed from the window (distance 32,768 unusable, or a less simple bound on
stored blocks); (3) storing the block's bytes for stored blocks at any span (a second 64 KiB
buffer for a case that occurs on incompressible input, where 16,384 literals are 16,384 bytes
anyway); (4) separate length code runs for the two trees, as zlib writes them (one extra run
boundary, no gain); (5) moving D090's transcript PNGs to this writer now: **not done**, every
committed transcript would change, and D090's writer is stored-block only and stays until a decision
says otherwise.
- [ ] **Signed off** (maintainer)

### D125 — Transcript bitmaps are written by the canonical kin PNG encoder; any valid PNG is read (revises D090; P3.11, D124)
**Decision**: the Rust and Go transcript writers (`png_encode` in `host/rust/src/plan.rs`,
`PngEncode` in `host/go/port/plan.go`) call `emon.png` (D124): 8-bit gray or RGB, no pHYs,
adaptive filters, `emon.deflate`. The file is still a function of the pixels alone and every
host writes the same bytes (`bb plan` checks Rust against Go, PNGs included), but it is now
compressed. Go's `port` package cannot import kin's generated packages (they import it), so
`host/go/pngenc` registers the encoder in `port.PngEncoder`; a Go program that writes
transcripts links it with `import _ "emon/pngenc"` (the plan and ocr drivers do; `kin/scripts/plan`
and `kin/scripts/ocr` copy it). The readers accept ANY valid non-interlaced PNG: Rust has its own
RFC 1951 inflater (stored, fixed and dynamic blocks), Go uses `compress/zlib`; colour types 0, 2,
3, 4, 6 at the depths the format allows, all five filters; alpha is dropped and palettes expanded,
so a bitmap is gray or RGB as before. Old stored-block transcripts still replay. The format
version (`:emon-transcript 1`) does not change: `transcript.edn` is byte for byte what it was.
**Re-encoded, not re-recorded**: `test/ocr/h-keyword/bitmap-0000.png` was decoded and encoded again
(`ocrdriver reencode DIR`, no Tesseract): 2,105,628 -> 272,174 bytes (7.7 times smaller; the pixel hash
`8787ffdb` is unchanged), and `test/ocr/h-keyword.dump` and `test/ocr/PRODUCER.json` carry the new file
hash (`40abd3ac`) and a note. `test/plan/` holds drivers, not a recording (the gate records afresh).
**Why**: D090 left the writer as a follow-up and D124 supplied it; recordings of a real 300-dpi A4
page were 8.7 MB each.
**Alternatives**: a host zlib (bytes differ by host, which is what D090 ruled out); a version
bump of the transcript format (nothing in the EDN changed, and the reader is a superset).
- [ ] **Signed off** (maintainer)

### D126 — Evidence v2 as built: 24 arenas from one layout table, a table-driven dump and checker, `emon.trig`, three departures from the brief
**Decision**: `kin/evidence.kin` implements SPEC §8.4 as written. The 24 arenas have the strides of §8.4, the three heads
are constants once (`hd-*` 16 cells, `lh-*` 13, `ch-*` 9) and each arena's own fields follow from where its head ends under
the prefixes the brief listed (`gf- wf- lf- mkf- fnf- pf- mf- epf- vwf- ptf- rff- xf- orf- obf- olf- owf- osf- oaf- ikf- cf-
rlf- pmf- tlf-`; none collided; `etf-` is unused because `etext` has no fields). Enum tables: the old `gh vz ar ld pa ck pg mg
mc` plus `as ea vk po cs wb mk ob op ir rs pm ft vc` and the flag sets `eg` (epages), `vg` (views), `oe` (OCR word engine
flags), `os` (symbol flags); `xo` and `xt` spell the feature columns (the spelling is the column's name in the dump and the
row's code its number, so one table serves both). Builders `*-new` take the HEAD fields for the arenas that have one and the
identifying leading fields for the others (written in each `*-new`'s comment) and set every other field to SPEC's default
(-1 absent, 0 counts, `how` unknown 5, alpha 1000, `vchk` 0, baseline on the glyph's box bottom); `*-get/-set/-count`;
`rf-add pt-add oa-add etx-append-cp`; `mk-open` / `mk-close` (`gcount` = next glyph - `gfirst`, `zlast` = next paint number - 1,
so a span that painted nothing has `zlast < zfirst`), `mk-artifact-of` (the nearest artifact mark's `/Type` * 16 + `/Subtype`, 0
none), `mk-artifact-class` (D101's class), `mk-ocg-of`; `fe-new`; `vw-new` and `vw-px-to-cpt / vw-cpt-to-px / vw-box-to-cpt`
(the answer of the two-valued ones is chosen by an axis or corner argument: kin has no multiple returns); `ev-floordiv`,
`ev-overlap-small`, `ev-baseline-at`; `ev-rows / ev-peek / ev-poke / ev-len` over the 24 arenas by their `ea` code (what
the checker and the laws are written with); the four bundle inits and `ev-init`; `ev-emit` and `ev-check` over all 24.
**THE DUMP AND THE CHECKER ARE TABLES, not 24 hand-written emitters**: `ev-fd` (275 rows: arena, first cell, kind, key, default,
argument) says every printed field, `ev-fx` where each arena's rows start, `ev-strides`, and `ev-ck` (88 rows, codes 2..89) every
check `ev-check` makes (a text run inside `etext`; a run inside its arena; an index inside its target or -1; an index of an
EARLIER row; a BACK POINTER, which is how the OCR nesting block > paragraph > line > word > symbol > alternative and the
glyph/word/line and glyph/mark runs are verified: a child lies in the run of the row it points to; a box with `x0 <= x1` and
`y0 <= y1`); 1 is a stride, 100 a `refs` row, 101 a `feats` subject, 102 a word's `feat` that does not name it. A law breaks the page
of `et-build` (a row in every arena) once per table row and requires that row's code, another holds that every cell of every stride
is printed or named spare, so neither table can drift from the layout unnoticed. The dump is version "1", canonical JSON under the
Model's rules (`kin/scripts/json-check.py` needed no change: its pretty rule "an array of flat arrays is flat" is why `refs` and
`etext` print on one line); `ek` gained its new keys at the end (`ocrwords` 8 and `spans` 46 stay, unused).
`emon.trig` (`kin/trig.kin`, generated by `kin/scripts/trigdata`, bb, `bb drift` runs it with `--check`): `atan(i/1024)` in
centi-degrees for 0..1024 and `sin` in 16.16 for 0..9000, each the double rounded half away from zero. `ev-angle dx dy` is octant
reduction plus the table (exact at every multiple of 45 degrees; a law sweeps every centi-degree: within 3 and monotonic);
`ev-sin`/`ev-cos` by symmetry.
Views (`vw-new`): the raster shows the page region whose top-left is `(cropx, cropy)`, turned `turns` quarter turns CLOCKWISE as
displayed, its rows rotated `deskew` centi-degrees counter-clockwise; px -> cpt is `x = e + (a*px + c*py)/2^16`,
`y = f + (b*px + d*py)/2^16`; the inverse is stored (`ia..id`) and `vw-cpt-to-px` uses it. The scale `720000*65536/dpi100` is exact
at 72, 96, 150, 200, 300, 400 and 600 dpi, so with no deskew px -> cpt has NO rounding and a US Letter page round-trips exactly at 150
and 300 dpi; the 16.16 inverse is good to 2^-17 of the distance from the origin (0.6 px at 792 pt), which is why the law's grid is a page,
not a megapixel.
THREE DEPARTURES from the brief, each because the brief predated what P1.7 merged with: (1) `ar` keeps D101's `other` = 5 (an
artifact whose `/Type` is none of the known ones, or absent) and the new `page` is 6, so an artifact span always has a nonzero
`/Type` and `mk-artifact-of` answers 0 only for "no artifact"; `watermark` 2 is never written; `as` (header 1, footer 2, watermark 3,
other 4) holds the subtype. (2) `marks` has no spare cell: its last is `stm` (D101's join key for P3.7, which §8.4.3 had dropped);
SPEC §8.4.3 now says so. `/Type` and `/Subtype` are CODES, so a name that is none of the known ones is `other` and its text is
gone (D101 kept it; no fixture or corpus file has one). (3) A typed index column that can point at one arena only (a glyph's
`font`, a word's `line`) is its plain row number in the dump, and `etext` is one string; `["arena", index]` is for the `refs` arena and a
`feats` subject, where the arena varies. No decision is needed on `z`: only glyphs are painted objects until P3.5, so the counter
counts glyphs (SPEC's "glyph, path, image" is what it will count).
D108 (initials) asked P1.12 where `fonts` should keep a font's `/CapHeight` and `/XHeight`: in the two cells that were spare,
`fnf-capheight` 14 and `fnf-xheight` 15 (1/1000 em, -1 absent; SPEC §8.4.3 and §8.4.7 say so; P4.10 reads them, `pc-font-row` leaves -1).
**Why**: one table for the layout and one for the dump means the four targets cannot disagree about a field, and a change of schema
is an edit of rows rather than of 24 functions; the laws that poke every check and cover every cell are what make that safe.
**Alternatives**: 24 hand-written dump functions and one `ev-check` loop per arena (the v0 shape: ~1,000 lines and every field
named three times); `ar-page` = 5 with `other` moved (codes are forever, D101 is merged).
- [ ] **Signed off** (maintainer)

### D127 — The text layer writes v2; the `spans` arena is folded into `marks`; fonts get rows
**Decision**: P1.7's interpreter (`kin/pdfcontent.kin`) takes the evidence as `gl et mk fnt` (`pc-page`, `pc-document`;
`pc-document` empties `fnt`, which belongs to the document). Per glyph: the baseline segment `Trm.(0,0)` -> `Trm.(w0,0)` (`w0` without
`Tc`/`Tw`, so the gap to the next origin is the spacing), `asc`/`desc` (SIGNED distances from the baseline along the up vector: the
font's ascent and descent times the text matrix's y-axis length), `rot` by `ev-angle` from the EXACT direction of the matrix's
x axis (halved until it fits 30 bits: not from the rounded centipoint segment, whose angle would be good to 6 centi-degrees at
best), so a rotated glyph is within 3 centi-degrees of the old CORDIC value and every upright one is identical (P1.7's angle law
now allows 3); fill colour and alpha (stroke -1 / 1000: P1.7 tracks neither), the colour-space class when the operator named it
(`g` gray, `rg` rgb, `k` cmyk; else -1), `z` from a per-page paint counter, `clip` -1, `mark` the innermost open mark, `ocg` the
innermost optional-content mark, `vis` 0 / `vby` -1 / `vchk` 0. Words: baseline first glyph's origin to last glyph's end, `rot`, `dir`
(rtl when the word was reordered), `style` from the font's row, `wbreak` (`space` a space glyph ended it, `gap` the continuity test,
`line-end` the last of a line: `tj-offset`, `font-change` and `mark-boundary` are not decided by this grouping and stay unused),
`src`. Lines: baseline first word start to last word end (in the order shown, before the right-to-left step), `order` the stream
index of the first glyph. The 512-byte async law compares `mk` and `fnt` too.
**`spans` -> `marks`, field by field** (the mapping the brief asked for): tag `tstart tlen`, `parent`, `mcid`, `/ActualText`, `/Alt`, `/E`,
`/Lang`, `gfirst gcount`, flags, `stm` the same; `artifact` (D101's class) is now DERIVED from `kind` (`artifact` when the tag is
`/Artifact`, `optional-content` for `/OC`, else `tag`), `/Type` (`ar`) and `/Subtype` (`as`) by `mk-artifact-class`, which gives the same
class for every file in the corpus; `type` and `subtype` are the codes (`cr-span` prints the PDF's names for them, so `rows.txt`,
`synth.txt` and `tagged.txt` are byte for byte what they were: 111 spans on the tagged files and 17 shapes on `marked.pdf`). One
non-standard shape changes: `/Subtype /Background` (a Type in PDF 2.0, which D101 also read as a subtype) is now `as-other` and the
class comes from `/Type` only. A glyph's `mcid` and `artifact` are found by walking its `mark`'s chain (`cr-mark-mcid`,
`cr-mark-class`). An `/OC` mark's `ocg` is the object number of its `/Properties` entry when that is a reference; its default state is -1
until P3 reads `/OCProperties`.
Fonts (`pc-font-row`): a row the first time the document shows a font, its index being its id (the glyph's `font`): object number,
subtype, `/Flags`, has-ToUnicode, base encoding (`pfn-flags` and `pfn-encoding` are new readers), `/BaseFont`, ascent, descent, the width
of a space for a simple font, and `style` from the flags (fixed pitch, italic, small caps, force bold) and the name (Bold, Italic,
Oblique). P1.6 reads no font programs, `/ItalicAngle` or `/StemV` (`pdfnames` has no such names), so `program` is 0, `italic` 0 and
`stemv` -1.
**Checked**: the three P1.5 fixtures' evidence dumps are the new goldens (`ev-check` answers 0 on each); every sweep of the old
suite unchanged: the per-page glyph-stream digest of every page of 605 real files (591 that open, 14 encrypted) is identical before and
after, and the oracle on P1.7's 535-file sample gives its figures to the last digit (99.599 per cent of 2,009,346 characters against pdfminer.six;
geometry 99.86 per cent within 0.05 pt); `tools/oracle/tagged-check.py` 111 spans and 2,553 of 2,553 glyphs; `rows.txt`, `synth.txt` and
`tagged.txt` byte for byte.
- [ ] **Signed off** (maintainer)

### D128 — OCR evidence from the answer extension: blocks and paragraphs from ids, baselines mapped first, stage 1 features
**Decision**: `kin/ocrprobe.kin` fills a page (`epages`, `raster`, dpi), a render view (`vw-new`, exact at 150 dpi), the image
placement, one `oruns` row (request, round, psm, flags, the engine's orientation, writing direction and line order VERBATIM, deskew, OSD
degrees, script, both confidences; no language: the request has none, D038), `oblocks` (a block is a run of lines with the same block
id, a paragraph a run of them with the same paragraph id inside it; each block is followed by its paragraphs so both child runs are
contiguous; box = union of the lines; `btype` from the first line; orientation and writing direction from the run, the extension having no
per-block value), `olines` (the pixel box mapped through the view with the four corners, the two-point baseline mapped, `rot` by
`ev-angle`, `dir` from the writing direction), `owords` (box mapped, style, language, symbol run, pixel box), `osyms` (box, text as a run
of `etext` when it is more than one code point, confidence), `oalts` (the candidate's FULL text, a run per symbol in engine order; a
candidate with no text is dropped, as v0 did). A word's baseline is its line's baseline (already in centipoints) evaluated at the word's
left and right edge: D096 says to map first and then interpolate, and interpolating in whole pixels first (what the brief's "in pixels,
then mapped" would do) is a pixel, 48 cpt at 150 dpi, where this is one cpt. The x of an edge is the view's, so the word baseline is exact
for an upright raster only (the probe reads one). `ev-feat-ocr` computes the stage 1 columns of a word (2-8, 17, 19, 20, 21, 28, 29) as
the brief defined them: 3 and 4 lowest and mean (floor) symbol confidence, 5 the smallest margin over symbols that have a differing
alternative (the symbol's confidence less the best differing one's; -1 if none has one), 6 the symbols whose margin is at most `alt-close`
(a parameter: 100), 8 per-mille of adjacent code-point pairs that are letter <-> digit or lowercase -> uppercase over ASCII and
Latin-1 (the denominator is all adjacent pairs), 17 the word's height over the lower median of its line's word heights, 19 the angle
to the lower median of the run's line angles (`ev-rot-median`), 20 the distance of the word's baseline midpoint from its line's
baseline there, 21 the mean gap over the mean width of its symbols along the writing direction, 29 the distance to the nearest page
edge (`epages`). Column 9 (script table) and 10-16, 18, 22-27 stay -1 for their stages.
**Checked**: `bb ocr`: Rust and Go print the identical dump from the committed transcript (not re-recorded; `tools/ocr/redump.clj` rewrites
the dump from it), which holds 199 `owords` with 199 `feats` rows, 24 `olines`, 23 `oblocks` rows, one `oruns` row with script and
confidences, 972 `osyms` and 2,139 `oalts` each with its text; CER 2.05 per cent (24 edits over 1,169 characters), as before; a changed
request is still `transcript-miss`.
- [ ] **Signed off** (maintainer)

### D129 — `tools/evidence/v0.clj` proves the migration lost nothing: every real golden comes back byte for byte
**Decision**: `tools/evidence/v0.clj` (bb, not a gate) reads a version "1" dump and prints the version "0" dump of the same evidence by
D096's field map read backwards (its head comment is the map). The goldens from step 1 were saved first to `test/evidence/v0/` (not
committed): `test/evidence/{empty,page1}{,.pretty}.json`, `test/ocr/h-keyword.dump`, P1.7's `test/content/{minimal,html-chrome,
gs-xrefstream}.evidence.json` (the plan gate has no committed evidence: it compares hosts with each other). Regenerated with the new
code and converted back: `minimal` (1,979 bytes), `html-chrome` (39,802) and `gs-xrefstream` (5,764) are byte-identical to their v0 goldens;
`h-keyword.dump` (199 OCR words, 2,139 alternatives) is byte-identical on its evidence line and on the others (bitmap hash, file hashes);
`empty.json` is identical. No difference was needed: the OCR word `baseline` tolerance (+-1 cpt) D096 allows went unused because the
converter takes the line's baseline back to pixels through the view and interpolates as v0 did, and the glyph `artifact` is found through
the mark chain and is the same class everywhere. `page1.json` is the hand-built page, REBUILT (a row in every arena, nested marks, a
clip...: step 5), so it is not a migration target: its v0 differs by the rows it gained (3 OCR words for 1, 2 lines for 1, 4 spans for
2, 3 words for 2, and the values of the old rows, which were re-chosen); the old page's every field is covered by the four real files
above. `test/evidence/v0/` is deleted. The goldens are written by `kin/scripts/evidence-goldens` (bb over the generated Rust; the laws
compare all four targets with them and `kin/scripts/json-check.py` holds them canonical) and the OCR dump by `tools/ocr/redump.clj`.
- [ ] **Signed off** (maintainer)

### D130 — "Correct OCR words score 0" is Tesseract's, not a reading bug in `tess.rs`
**Decision**: the question (P1.12 step 6): in `host/rust/src/tess.rs`, is word confidence read after the `lstm_choice_mode`
choice iterator is set up, or at the wrong level? No. `lstm_choice_mode` 2 is set once when the engine starts, the word's confidence is
`TessResultIteratorConfidence(it, RIL_WORD)` and symbols' and alternatives' are read on a copy of the iterator at their own levels, all
scaled x10 once. The stock `tesseract` 5.5.3 CLI (`tsv` output) on `test/ocr/h-keyword/bitmap-0000.png` gives the same 0.000000 for "Chapter"
and "1" with `lstm_choice_mode` 0 and 2 alike, and 96.06 for "Formation": Tesseract derives a word's confidence from the LSTM
word certainty clipped to 0..100, so a word the network is unsure of scores exactly 0 while a misspelling can score high (the dump's two zero
words are "Chapter" with every alternative 0 and "1"; "Seciton" scores 554 and 308). 1,765 of the dump's alternative confidences are 0:
under mode 2 the alternatives are path-cost derived and all but the best are near 0. Nothing was changed in the host except a comment at the
setup recording this. **For P6.5a**: carry the confidences verbatim and never treat 0 as "wrong"; do not build a feature on the word or the
alternative confidence without measuring it (SPEC §8.4.5 already makes raw confidence one column of a scorecard, never a probability);
what is reliable is the alternatives' TEXT and rank and a symbol's confidence less the best differing alternative's (`xo-margin`).
- [ ] **Signed off** (maintainer)

### D131 — The Model's vocabulary for initials, alignment and layout, as built: a seventh arena, one builder for `align`, and where each code is (P1.13; D109-D111)
**Decision**: `kin/model.kin`, codes appended and never renumbered (D050): `nk-initial` 26 (an inline: `nk-is-inline`
says so; `node-new` and `model-check` accept kinds 1-26); `fk` rows `form` 76, `lines` 77, `layout` 78, `band` 79,
`col` 80, `box` 81; `rl-sidebar` 9; a new `defenum` `ifm` (`drop` 1, `raised` 2, `margin` 3). An `initial` keeps its form
in `sub` and its lines in `aux` (0 = absent). `align` on a heading, caption or cell is the int attribute `fk-align` (an
`al` code), on a paragraph it is still `sub`; `node-set-align nodes attrs n v` is the one builder and answers -1 for a value
outside 1-4, for a kind that has no alignment, and for `justify` on a cell (D110). The seventh arena is `layout`, stride 8
(page NUMBER, band, col, role as an `rl` code, x0 y0 x1 y1; `layout-stride`); a page's cells are one contiguous run in
reading order, named by cells 11 (first) and 12 (count) of its `pages` row (`page-layout-first`, `page-layout-count`;
the cells were reserved zeros, so an old row means "no map"). `lay-add layout pages p band col role x0 y0 x1 y1` appends
to page INDEX p's run and answers the row, or -1 for a missing page, a role that is not body (2), margin (6) or sidebar
(9), or a page whose run is no longer the last one written. `model-check` gains `mc-bad-layout` 13 (a negative or
out-of-range run, a cell of another page, a role outside the three) and the layout stride; `model-hash` folds the layout
arena last, so the fingerprint of every Model with an empty map is the old one times the FNV prime (the law in
`modeltest` says so, and `kin/scripts/check` expects `model: 1538303818 -13163675`, both worked out apart from kin).
The layout arena is the LAST parameter of `model-init`, `model-check`, `model-hash` and `js-emit` (`... pages ledger
layout`) and `html-emit` takes `... text pages ledger layout` (it needed `pages` for the runs, and has had neither
before). The Model script (`emon.mscript`) gains `al NODE v` and `ly IDX BAND COL ROLE X0 Y0 X1 Y1`; a refused line is a
wrong line, so the goldens cannot hide a refusal. `corners.model` and `kinds.model` gain a drop, a raised and a margin
initial (one ahead of a smallcaps span, one read by OCR with `low-conf`, one with neither form nor lines), aligned
heading, caption and cells, sidebar and margin notes (targeted and not), and layouts of two pages (every role).
**Why**: the same reasons as D050 and D109-D111, and Rust's: no function reads and writes the same `Cells` in one call,
so `lay-add` binds the page's count and number first. A new stride would have been a seventh `Cells` for every host
anyway; a builder that refuses is where a bad alignment must stop, since the serializer writes whatever the arena holds.
**Alternatives**: the layout as pages-row cells only (eight per page would be the stride of a fixed table the spec leaves
open-ended); `align` for headings in `aux` (a cell's `aux` is `empty`, a heading's would be the only user, and an
attribute is what D110 said); a separate `justify` check at the JSON writer (too late).
- [ ] **Signed off** (maintainer)

### D132 — JSON 1.1: the version string is `"1.1"` always; field order for the new keys (P1.13; D109-D111, D055)
**Decision**: `js-emit` writes `"emon":"1.1"` for every Model, not only for one that uses a 1.1 feature: the brief for
P1.13 says the string becomes `"1.1"` and that every JSON golden changes by that string only (and so they did, apart from
the two models that gained content: `kinds` and `corners`). `schema/emon-1.json` accepts both; a consumer must ignore what
it does not know (SPEC 4.4), so a 1.1 document without a 1.1 feature is a 1.0 document. Field order: `initial` is an inline,
so no `id`, then `kind`, `form`, `lines` (when `aux` is above 0), then `children`, `at`, `src`, `conf`, `flags`; `align` sits
after the kind's own fields (heading: `level`, `number`, `numbering`, `align`; caption: `label`, `target`, `align`; cell:
`rowspan`, `colspan`, `header`, `empty`, `align`; paragraph: `align`); a page's `layout` is the last member of the page,
`[{"band","col","role","box":[x0,y0,x1,y1]}]` in run order, omitted when the run is empty. An `initial` makes `inlines`
informative with no new rule (it is not a plain `text`). `kin/scripts/json-check.py` (the independent serializer) learned
all of this first and writes the goldens (`--write`).
**Why**: one version string per build is what a consumer can test; deciding it from the Model's content would make the
version a property of the document a crawler cannot predict, and the goldens would mix both.
**Alternatives**: `"1.0"` unless a 1.1 feature is present (honest, but two strings from one emitter, and the day a stage
writes one the whole corpus flips); leaving the string at `"1.0"` until the first stage writes a feature (the schema would
accept what no one writes, and the brief said otherwise).
- [ ] **Signed off** (maintainer)

### D133 — HTML for initials, alignment, sidebars and columns, as built (P1.13; D109, D110, D113, D067-D069)
**Decision**: `kin/html.kin`. (1) `<span data-emon="initial" data-emon-form data-emon-lines>` with the options' own
attributes after them (`ids` never, `geometry`, `confidence`), the children as its content; `ho-initials-plain` (128) writes
the children bare. (2) `data-emon-align` on `p`, `h1-h6`, `caption`/`figcaption`/`p data-emon="caption"` and `th`/`td`
wherever the Model has an alignment, then, with `ho-align-style` (256), `style="text-align:start|center|end|justify"
dir="auto"` right after it. (3) A note whose role is `margin` or `sidebar` is `<aside data-emon-role="margin|sidebar">`:
no `role="doc-footnote"`, and no `id="fn-N"` unless a `ref` or a `link` targets it (a link to a note already writes
`#fn-N`, so a link counts as well as a ref, which D113 said only of a ref); `footnote` and `endnote` are unchanged.
(4) With `ho-geometry`, every NON-INLINE node with regions gets `data-emon-col="page:band:col"`, then
`data-emon-cols="p:b:c p:b:c"` when more than one of its regions lies in a cell, after `data-emon-at`. A region lies in the
first cell of its page's map (the page with that NUMBER) that contains its CENTRE, edges included; the centre is the sum of
the two halves of each pair of edges, each truncated toward zero (so no sum can overflow an I32); a region in no cell
(a centre in a gutter, a page with no map) writes nothing and is not counted. (5) The option word `o` outgrew its low
byte (`ho-align-style` is 256, which is where the confidence threshold used to start): `o` is now the options in the low
ten bits and the threshold times 1024, and `html-emit` masks `opts` with 1023. (6) The goldens gain a fifth set per model,
`test/html/NAME.plain.html`, under `ho-initials-plain | ho-align-style | ho-geometry` (386, no confidence). `html-check.py`
(the independent renderer; it reads the JSON, including `pages[].layout`) learned the same first, and the html5lib
census counts `span[data-emon=initial]`; strict parsing stays at zero errors.
**Why**: D109, D110 and D113 say what the markup is; this records what they left open: the packing of the options, what
"contains" means at an edge and for an I32, and that a note nobody points at has no anchor to keep (a dead `id` is the
only thing `fn-N` would add; a margin note is not a footnote).
**Alternatives**: a second options parameter for the new bits (a signature every caller changes for the sake of two
bits); the centre by `(x0 + x1) / 2` (overflows at the extremes the goldens use on purpose); `data-emon-col` also on inline
nodes (an inline at `detail: words` has regions of its own, and the block already says which column it began in).
- [ ] **Signed off** (maintainer)

### D134 — P3.7: the structure tree is read whole into four arenas of its own; a standard type is used as it is, a custom one is followed through the RoleMap; attributes through `/A` and the ClassMap; the ParentTree is checked, not believed
**Decision** (P3.7, D034, D076, D096). (1) **Where**: `kin/pdfstruct.kin`, with its names in a generated `kin/pdfsnames.kin`
(`tools/pdfsnames.clj`, babashka, `--check`ed by `bb unit`: 69 names compared by bytes and the 52 standard structure types of
ISO 32000-1 14.8.4 and 32000-2, plus `Artifact`, which producers write as one; a role's code is its position + 1, forever). A
separate table from `pdfxnames` so that no two agents append to one. `pdfdoc`, `pdflex`, `pdfcontent`, `pdffont` and `para` are NOT
edited (`pdfx` gets one overflow fix, below). SPEC 8.4.7 said the tree is P3.7's arena; these are the arenas. (2) **Arenas**
(integer `Cells`, strings as runs of the shared `txt`, absent -1; the layouts are in the file's header): `se` (64 cells an
element, row 0 the StructTreeRoot, pre-order, so a subtree is a contiguous range), `sk` (4 cells a kid, in document order:
element, content, object reference, skipped with the reason), `sc` (24: an `/MCR` or a bare MCID), `so` (8: an `/OBJR`
and what it points at), `ss` (96: the document), `sa` (16: artifact spans, D135). Their `ea` codes are appended after the annotation
arenas when the bundles take them. (3) **Roles**: a `/S` that is a standard type is used as it is, and a RoleMap entry that would
remap it is noted (`standard-shadowed`, bit 16; poppler and pdf.js use the standard type, and PDF/UA forbids the remap); a custom
name is followed through the RoleMap to the first standard type, 24 steps at most (`rolemap`, `custom` for a name nobody
mapped, `rolemap-loop`); `H1`..`H9999` are matched by their numerals (`Hn`, level kept), `H0` and `Hx` are custom; plain `H` gets a
level prior of its Sect ancestors (at least 1, at most 6, flagged derived). (4) **Kids**: a `/K` is a dictionary, an integer
or an array; a dictionary is an element (`/Type /StructElem`, or has `/S`), an `/MCR` (or has `/MCID`), an `/OBJR` (or has
`/Obj`), else skipped; an element seen before (a loop or a DAG, by object number) is skipped and noted, depth stops at 64,
elements at 1,000,000, marked-content references at 2,000,000. An MCR's page is its `/Pg`, else its element's effective page
(own `/Pg` or the nearest ancestor's); its stream is its `/Stm` (0: the page's). (5) **Properties**: `/Alt /ActualText /E /Lang
/ID /T` as text (UTF-16, UTF-8 and PDFDocEncoding), the row that supplies an element's effective language, and attributes from
the element's own `/A` (a dictionary or an array that may hold revision numbers) then the classes its `/C` names through the
ClassMap, an object counting when its `/O` owner is the one asked for or absent: Scope, Headers (the `/ID` strings), RowSpan,
ColSpan (TH, TD), Summary (Table), ListNumbering (L). (6) **ParentTree**: the number tree is collected (12 deep, 200,000 nodes)
and, for each MCR, the entry at `(page's or Form XObject's /StructParents, MCID)` must be the element that claims it; each
OBJR likewise by the object's `/StructParent`. Recorded as agree, differ or absent, never used to change the tree (the document
without a ParentTree has every reference `absent`). (7) **Bounded and failing per page** (D030): nothing recurses without a
depth; a source that misses answers -1 and nothing written is kept (D075).
**Fix found on the way**: `px-page-of` computed `IX_HEAD + num` and overflowed on an object number near 2^31 (a `/Pg` that names
one: `corpus/layout/pdfa-eng-wds/4514833.pdf`); it now compares `num` with the table's size first. One line in `kin/pdfx.kin`.
**Why**: D076 asked for a tree reader and a join; reading everything once, whole and flagged, lets later stages decide (the Model
builder reads `se` and `sc`; fusion reads the checks) without a second pass over the file, and what the file got wrong is data
(`custom`, `rolemap-loop`, `shared`, a duplicate MCID), not a failure.
**Alternatives**: interning 120 more names in the object arena (192 slots, shared); resolving a standard type through the RoleMap
first (a producer that remaps `P` to `Span` would turn every paragraph into inline text); one record per element with the kids
nested (kin builds no arrays of arrays); trusting the ParentTree to find an MCR's element (it is a second claim, and files
disagree with themselves).
- [ ] **Signed off** (maintainer)

### D135 — P3.7: the MCID join is exact, glyphs are owned by the innermost marked content with an MCID, and every claim is checked against the page
**Decision** (P3.7, D034, D076, D096, D101, D127). (1) **The join**: `tg-join` keys `marks` by `(page, stm, MCID)` (the
first mark with the key; `marks.stm` is the Form XObject, D101) and looks up each reference; no tolerance, no fallback to
"any stream". A mark claimed twice keeps its first claimer and the second is flagged `duplicate`; a reference with no mark is
`no-mark` (a page with no `/Stm` on an MCID in a form, say). (2) **Glyph owners**: a glyph walks its `mark` chain from the
innermost span: the first span that is an artifact makes it an artifact glyph, the first with an MCID makes it the owner's
(through the claiming reference: an MCID nobody claims is `unclaimed`); no span at all is `untagged`. A reference collects
the glyphs it owns (count, size in tenths of a point, bold, box, first and last glyph), an element its own and its subtree's
(a roll-up through the ancestors, in the order of the tree, exact), and the box of the subtree's text on the page of its first
text. Bold is the font row's bold style bit. The text layer is bounded to 3,000,000 glyphs for the sums. (3) **Reading
order**: for two consecutive references with upright text on the same page, the tree is right when the second starts no more
than half the smaller line height above where the first ended (down the page, or along a line), or entirely to the right of
it (the next column); rotated text and pages apart make no pair. (4) **Heading roles against the page**: the BODY SIZE is the
most common size (0.5 pt buckets) of tagged text; an element with text of its own inside H, Hn or Title is VERIFIED when its mean size
is 1.08 times the body or it is bold (at least 70 per cent of its glyphs) and not smaller than 0.95; a BIG element (short text,
at most 300 glyphs, 1.25 times the body, not a table cell, a label, a figure or a formula) is a miss when it is in no heading.
**Heading levels** are checked two ways: in document order an `Hn` may not follow with a level more than one deeper than the one
before, and a lower level number may not be set smaller (mean size, 0.97) than the next level present. (5) **Artifacts**: one
`sa` row per OUTERMOST artifact span: the box of its text, where that sits (top or bottom 12 per cent, a side margin 8 per
cent, else body), its rotation, mean size, mean luminance, and a verdict: a Header lies at the top, a Footer at the bottom, a
Watermark is turned, light (luminance 170) or large (36 pt); any other furniture that carries text lies in a margin or looks
like a watermark; a span with no text (a rule, a shading) is `unchecked`. (6) **Tables and lists**: a table's rows and columns
(the sum of ColSpan along a row), regular when every row has as many; a list's items, how many have a label, and whether the
first glyphs of each label look like what ListNumbering says (a bullet for Disc, digits for Decimal, Roman or alphabetic
letters, `lk` bits; Ordered allows any non-bullet). (7) **Nesting**: `TR` in Table or THead, TBody, TFoot; `TH`, `TD` in `TR`;
THead, TBody, TFoot in Table; `LI` in `L`; `Lbl`, `LBody` in `LI`; `TOCI` in `TOC`.
**Not here, on purpose**: running headers and footers the producer did NOT mark (found by recurrence, §10.2); whether a
`/Lang` is the text's language (P4, P9); what a Figure's Alt says.
**Why**: D034: the author's answer is evidence; each piece above is something the page can refute, and each is stored so that
the Model builder and fusion can use the part they need (levels but not order, say).
**Alternatives**: a tolerance on the MCID join (a wrong join would be silent); counting glyphs by walking the span's whole
run (a span with a nested MCID span would double-count); strict left-to-right (a right-to-left page would disagree everywhere).
- [ ] **Signed off** (maintainer)

### D136 — P3.7: the informativeness score is a weighted harmonic mean of per-mille checks, chance-corrected where the check is a coin flip, scaled by the weakest check; PDF/UA moves the prior and the trust, never the score
**Decision** (P3.7, D034, D076). Components, each per mille, -1 when there is nothing to check: **coverage** (tagged glyphs plus artifact glyphs over all glyphs), **diversity** (informative classes in use among elements that hold content: headings, lists, tables, figures, sections, notes and quotes, links, tables of contents; none 0, one 500, two 750, three or more 1000: a paragraph and a span say nothing about the document), **reading order** (D135 pairs, at least 4), **heading fit** (the mean of the verified share of headings and the share of BIG elements that are headings), **level fit** (D135 pairs, at least 4), **artifact fit** (consistent over consistent plus inconsistent spans), **nesting fit**. Reading order and level fit are CHANCE-CORRECTED, `max(0, 2 x agree - 1000)`: a coin flip agrees half the time and is worth nothing. The score is the weighted harmonic mean of the defined components (weights coverage 3, diversity 2, order 2, headings 2, levels 2, artifacts 2, nesting 1; a value floored at 50), times `(1000 + the weakest check) / 2000` where the weakest is taken over everything but diversity: one failed check pulls the whole down, and one that cannot be checked is left out, not assumed. No text to check: score 0 and status 1; no tree: status 0. **Prior and trust**: `prior` is 300 for a tree, 500 when the catalog says `/MarkInfo /Marked true`, 700 when `pdfuaid:part` (P3.9) is also at least 1; `trust` is the score, raised by a tenth of itself when PDF/UA is claimed, so the claim helps a tree that checks out and cannot rescue one that does not (D034: verified, not obeyed). The weights and thresholds are first values, not fitted: P10.1 calibrates them with the rest.
**Result on the synthetic pairs** (the same page, what the file says differs): correct 1000; wrong heading levels 656; reading order reversed 273; artifacts mis-marked 134; all three together (everything P under the Document, headings with the wrong levels, a running header tagged P, a heading marked Footer, a paragraph marked Pagination) 113; everything P 63. **On the corpus** (503 scored files, 476 `/Marked`): median 475, 10th percentile 93, 90th 978; 20 trees use no informative role, 7 cover no glyph (a tree whose content carries no marked content), and a heading role on a nine-point paragraph of 259 glyphs is caught (`pdfa-eng-wds/1897131.pdf`).
**Why**: a mean that one good component can carry hides the failures D076 names; the harmonic mean and the weakest-check scale make each failure alone visible (the single-defect twins fall to 656, 273 and 134, not 900), and chance correction stops a shuffled order from scoring 500.
**Alternatives**: an arithmetic mean; a minimum (one noisy check erases the rest, and no gradient is left); a learned model (parked, SPEC 1.1, and the corpus has one dominant producer).
- [ ] **Signed off** (maintainer)

### D137 — P3.7 is verified against an independent walk of the tree in pypdf, over every tagged PDF in the corpus, and by synthetic pairs written byte for byte
**Decision** (P3.7, D094, D100, D122). (1) **Oracle**: `tools/oracle/tagged-check.py` (Python, pypdf from `~/.cache/emon/pylib`: the third-party-oracle exception of D094) walks the structure tree itself from the standard, with no code in common with kin, and `kin/scripts/struct-sweep` (the generated Rust over a list of files: every page interpreted, the catalogue read, the tree read, joined, dumped) must agree on every ITEM: `/MarkInfo`, the element count; per element its object number, parent, depth, `/S`, resolved role and how, page, effective-language source, Scope, RowSpan, ColSpan, ListNumbering and the six text properties; its kids by kind and target; per marked-content reference its element, kind, page, MCID and stream, whether the text layer has a span for `(page, stream, MCID)` (the spans are pypdf's own walk of the content streams, Form XObjects included), and its ParentTree verdict; per object reference the same. One disagreement fails the file. Counting `/MarkInfo /Marked true` by pypdf alone: **476 of the 3,689 corpus PDFs**, 504 with a StructTreeRoot, 473 both; emon's catalogue reader (D121) gives the same three numbers. **Results**: all 504 tree-bearing PDFs, 455,057 elements, 269,721 marked-content references, **11,944,254 items, 0 differ**; the 3 Chrome-tagged synthetic PDFs (5,249 items) and the 7 fixtures below; `tools/oracle/tagged-check.py` still holds the P1.7 span check (111 spans, 2,553 of 2,553 glyphs) on the three. The first sweep found two things, both fixed: the `px-page-of` overflow (D134) and a ParentTree verdict that was left unset for a document with no ParentTree (now `absent`, as the oracle says). (2) **Fixtures**: `tools/synth/tagged_pair.clj` (babashka, `--check`ed by `bb unit`) writes `test/pdf/tagged/`: `good.pdf` (a title, four sections, paragraphs, a numbered list, a ruled table with header cells, a figure with Alt, a caption, a Span with ActualText and E, a French paragraph, running header, footer and a DRAFT watermark, custom roles through a RoleMap, a ClassMap, a ParentTree) and five twins of the SAME page that say it wrongly (`bad.pdf`: the three failures D076 names together; `bad-allp`, `bad-levels`, `bad-artifacts`, `bad-order`: one each), and `odd.pdf` (a RoleMap loop, a custom role, a remapped standard role, H7, a `/K` naming its own element, an element reached twice, nulls and strings in a `/K`, a duplicate and an unclaimed MCID, Form XObject content joined through `/Stm`, an OBJR to a Link, `/A` arrays with a revision number and a foreign owner, 70 nested elements, stray text and an artifact). (3) **Goldens**: `kin/scripts/struct-goldens` writes the dump of each (`test/pdf/tagged/*.json`, `test/pdf/tagged.txt`) from the generated Rust and runs the oracle on them; `kin/pdfstructtest.kin` holds all four targets to the dumps byte for byte, to what the fixtures were built to say as literals (roles and how, text properties, tables and lists, the join, the artifacts, the scores), to the separation of the correct tree from each twin, through 64- and 512-byte async sources, and on cut and corrupted copies. (4) **Fuzz**: `kin/fuzzprobe.kin` also reads and joins the tree and the fuzz seeds include `test/pdf/tagged`.
**Why**: accuracy is paramount (D023) and emon cannot be its own oracle (D100); a tree of 455,000 elements read by two programs that share only the PDF standard is the strongest check available, and the twins show the score does what D076 asked.
**Alternatives**: qpdf's JSON for the tree (it lists objects, not the tree's resolved roles); comparing whole dumps (a format change would be an oracle change); real mis-tagged files only (nobody knows their truth).
- [ ] **Signed off** (maintainer)


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
- `if` as a value (`(let [x (if c a b)] ...)`, P2.6) generates in every target but
  Rust emits `()` for the taken branch's value (E0308): use `^:mut` + `set`.
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
- P3.11 (`emon.deflate`): a `defconst DF_SIZE` and a `defn df-size` are both `DfSize` in C#
  (`CS0102`, nothing at generation time says so: the `PG`/`pg` case again, for a constant and
  a function); `final` is refused for Java at generation time, as `len` is for Go (both
  clear, both found by the first emit); a `let` binding that a `deftest` never reads is a Go
  compile error only (`raw declared and not used`), the `bb unit` arm printed only the first
  40 lines of the failure, which hid it.
- `var` (a Go keyword, a C# contextual one) as a kin local name was avoided by hand (`vr`);
  worth a generation-time check beside `len`/`cap`/`go`.
- A `(while 1 ...)` with statements after it would be unreachable code in Java
  (a constant condition); kin has no `loop`/`forever` the four targets agree
  on, so loops with several exits use a `done` flag.
- A `\u` in a kin COMMENT becomes a Java unicode escape in the generated Java (`javac`: `illegal
  unicode escape`), because Java reads `\u` everywhere, comments included; Rust, Go and C# do not. P3.9's
  first Java build failed on a comment that spelled a JSON escape. kin could sanitise emitted comments
  (or refuse a `\u` in one at generation time, as it refuses `len` for Go); emon words around it.
- `defconst` names fold to the same Go identifier as a function of the same name in lower case: `PX_NULL`
  and `(px-null)` are both `pxNull`, `PM_OUTLINES` and `pm-outlines` both `pmOutlines`, and the
  generator says nothing (as for `PG`/`pg` and `len`/`cap`/`go`/`var` above). Found by the Go
  compile; worth a generation-time check of constants against functions per target.
- `emit-ascii` refuses a literal TAB (it is not printable ASCII), which is right, and means a row
  law that writes tab-separated fields uses another separator on the output side.
- `(pd-resolve ... (px-nth ar arr i))`, `(cells-set md 41 (pm-xmp-scan ... md ...))`: the Rust
  `&mut Cells` tag again (two mutable borrows in one call): every one of P3.8's and P3.9's Rust errors
  was that or a `let` that was assigned later without `^:mut`. The `CellsRo` tag of the audit above
  would have removed the first kind outright.

## Open questions for the maintainer

- [x] **OCR default**: `always`, every page, born-digital included (D038).
- [x] **Sharing with nome**: none; independent projects (D039, D024 struck).
- [x] **Crawl mix**: mixed; D031's phase order stands.
- [x] **Encrypted PDFs**: extract, ignore permissions; `password-required`
  error; caller-supplied password (D040).
- [x] **Java toolchain**: a JDK exists at `/opt/homebrew/opt/openjdk` and
  `bb check` already runs the Java arm.
- [x] **Figure crops**: an opt-in, `assets` = `none` (default) / `images` /
  `figures`, with bytes out of band (D117, SPEC §10.11; resolves D020's open item).
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

