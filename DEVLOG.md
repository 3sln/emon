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

**The fusion critical path** (P6.3-P7.S) has its own ordered wave table at the head of Phase 7; for that work it
supersedes this list.

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

- [x] **P1.8 — Naive analysis: lines → paragraphs** (`kin/para.kin`).
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
  *Done (P1.8, D134-D139).* `kin/para.kin` (`emon.para`: `pa-pages`, `pa-build`) reads v2 `words`, `lines` and `etext` and appends
  paragraphs to a Model the caller made; `kin/paratest.kin` has 6 laws (what it decides, on hand-made evidence, and six real
  documents PDF -> content -> paragraphs -> Model -> pretty JSON byte for byte against `test/para/NAME.json`, which
  `kin/scripts/para-goldens` writes). **Done-when, met**: `kin/scripts/para-check` (babashka, run by `bb unit`) holds each golden's
  paragraphs to the text of the `<h1>`-`<h6>` and `<p>` of the HTML the document was printed from, in order, with every page's
  milestone: 6 documents equal. Where it departs from the brief: the line gap is the lower QUARTILE of the gaps, an indent counts only
  from a line at the margin, a change of STYLE (not of font id: Skia subsets) breaks a paragraph (D135); rotated lines stand alone after
  the page's upright ones (D136); and a `break` with an inline neighbour is an INLINE (`node-is-inline`; `json.kin`, `html.kin`, the
  schema and both Python references learnt it, `corners` has one), because the SPEC's "inline or between blocks" had no
  representation for the first (D134). **Measured** (D139; the temporary predictor `tools/accuracy/predict-naive.sh`, NOT the Engine,
  nothing in the scoreboard) over the 55 synthetic documents, pdf variant: CER 18.5 per cent, WER 24.2, paragraph-boundary P/R/F1
  0.069/0.629/0.125, paragraph-block P/R 0.069/0.724; 245 of 384 gold paragraphs exact, and most of the rest are columns (P4.1), drop
  caps (P4.10), notes (P4.6) and the text layer's CJK radical forms (D139). *Not in this item*: columns, initials, alignment, headings,
  lists, tables, furniture (P4, P5); dehyphenation (needs a locale).

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

- [x] **P1.10 — Rust host: Engine API and a driver.**
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
  *Done (P1.10, D144-D147).* `host/rust/src/{engine,source,lib}.rs` (the Engine API, the byte sources and adapters, the crate
  root; the file is `engine.rs`, not `emon.rs`, D144), `host/rust/tests/engine.rs` (11 native tests), the driver
  `test/driver/rust/main.rs` (`emon-rs`), `kin/scripts/emon-rs-build` (assembles and builds the crate into `.cache/`),
  `kin/scripts/slice` (`bb slice`, now a gate). **Done-when, met**: `bb slice` is green in memory and with `--chunk=512 --async`
  (identical JSON and HTML; attempts, rounds and bytes fetched on stderr: 3 attempts, 2 fetch rounds, the whole file, on each of
  the six fixtures); three adapters (bytes, `Read + Seek`, spooled `Read`) give identical JSON and HTML, and the 512-byte async source
  equals memory on 70 PDFs of the fixture trees, errors included (D147). It reads D040 (the three encryption errors, a password that
  travels with its `Input`), D058/D074/D075 (`Src` over a chunk cache, the re-run loop on `need-bytes`, no `std::fs` in the library,
  tested), D089-D091 (the plan/fulfil loop is wired and the slice issues zero requests), D117 (`assets`: `none` only, the rest refused).
  **Not wired, in the open (D144)**: OCR (the option is accepted, nothing runs, no `ocr` in the header), several inputs as one
  document (`intake` takes many, `convert` refuses), images, the header's `source`/`meta`. **The first real baseline is recorded
  and `accuracy` is a gate (D145)**: `pdf/all` CER 0.1848, WER 0.2423, paragraph-boundary F1 0.125; `scan/all` CER 1.0. The temporary
  predictor of D139 is retired (D147). Timings: 4-5 ms per fixture, in memory, process start to exit. Commit: pending (D037).

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

- [x] **P2.1 — Go host layer, thin**: ports (already there from P0.2),
  Engine/Model wrapper, driver; inputs as `io.ReaderAt` + size (D058).
  *Done when*: `bb slice` passes on Go too.
  *Done (P2.1, D158).* `host/go/{source,engine,model}.go` (the Engine API, the byte sources and adapters, the walkable Model: the Go
  twin of `engine.rs`/`source.rs`), `host/go/engine_test.go` (12 native tests, run by `go test` on the assembled module),
  `test/driver/go/main.go` (`emon-go`: the flags and exit codes of `emon-rs`, plus `--source=readerat`), `kin/scripts/emon-go-build`
  (assembles and builds the module into `.cache/emon-go`), `port.WithLimits` (the Go half of `Config.limits`: a process-wide table
  behind a `sync.RWMutex`; a conversion with custom limits runs alone). **Done-when, met**: `bb slice` runs every section on both hosts
  and is green on Go: the six fixtures to `test/para` and `test/slice` (byte for byte), `--chunk=512 --async` (it missed and re-ran),
  `--source=seek|spool`, the evidence golden, the named errors and exit codes; `go vet` is clean. Both drivers gained `--record`,
  `--replay`, `--parallel=N` and `--out=bundle`. The refusals are the Rust slice's (OCR inert, images, several inputs, `assets`).
  Commit: pending (D037).
- [x] **P2.2 — `bb identity`**: every driver arm over the corpus of (input,
  transcript) pairs; byte-identical JSON and HTML required (SPEC §14.1.5).
  Transcripts are empty in this phase; the format is fixed now (P2.5).
  Each arm also runs every input through a 512-byte-chunk async test
  source and must match its in-memory run (D058).
  *Done (P2.2, D159).* `kin/scripts/identity` (`bb identity`, a gate; `--full` adds chunks of 7, 100 and 4096 bytes and a sync 64-byte
  cache; `--corpus` the public corpus, a non-gate). **258 inputs** (every committed PDF under `test/`, each (file, password) row of
  `test/pdf/p3.txt`, two PNGs) x 9 arms (Rust: bytes, `Read + Seek`, spooled `Read`, 512-byte async; Go: bytes, `io.ReaderAt`,
  `io.ReadSeeker`, spooled `io.Reader`, 512-byte async), each answer the bundle's bytes (compact and pretty JSON, default and full
  HTML, evidence, intake) AND the exit code and stderr, and the record/replay trip across hosts (each host's `--record` directory equal
  byte for byte, each host replaying the other's): **no difference found** (226 converted, 32 refused by name, the same on every arm),
  14 s. The public corpus: all 3,689 PDFs of the never-committed public corpus (`corpus/`, 12 GB) were run through the same nine arms and the transcript trip with `bb identity --corpus` (30 minutes): all 3,689 converted and every answer was byte-identical, no difference.
  Java and C# are not arms (ports only).
- [x] **P2.3 — `bb embedders`**: compare the Rust and Go public API surfaces
  (names, arities) against SPEC §3.9/§13. nome learnt this late (its D568).
  Includes the `Input`/byte-source surface (§13.1) and a check that no host
  library file names a filesystem type (D058).
  *Done (P2.3, D160).* `kin/scripts/embedders` (`bb embedders`, a gate, about a second, no compiler): the Rust crate root and the Go
  package compared by name and arity (24 types, 74 fields and variants, 38 methods, 13 constructors and defaults agree; every
  difference is a table entry with its reason or a failure), SPEC 3.9/13's spelled names present on both (the asset accessors listed as
  PENDING, D117), and no filesystem in 13 host library files or 168 generated non-test modules of the four languages. No API difference
  found between the layers as written; the gate was shown to fail on a rename, an extra parameter and an `os.Open`.
- [x] **P2.4 — `bb repeat`**: run vs run, and pages submitted in reverse,
  byte-identical.
  *Done (P2.4, D161).* `kin/scripts/repeat` (`bb repeat`, a gate; `--full` adds the reversed file over an async source) and
  `tools/lib/pdfreverse.clj`. 210 documents on Rust and Go: run vs run, serial vs eight conversions at once on one Engine, and the file
  with its objects in REVERSE file order (168 of the 200 that convert are rewritable): byte-identical JSON, HTML and evidence. **There
  is no page submission to reverse yet** (the Engine reads a whole document as one unit): what is reversed is the file's layout of its
  pages, tree and resources, and D161 says what the gate gains when per-page units exist. The public corpus: a sample of 308 of the 3,689 public-corpus PDFs (`bb repeat --corpus --sample 300`, 2 minutes): green, 241 rewritten in reverse object order, no difference.
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
  proven on fixtures (oracle perfect, degraded worse); the real emon driver was a
  placeholder until P1.10 and `bb accuracy` was not in `bb gates` (D078); since P1.10
  the predictor is the Rust Engine's driver, `emon` has a baseline and `accuracy` is a gate (D145).
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
- [x] P3.4 — Fonts, the long tail: predefined CJK CMaps (as `defdata`),
  CID orderings → Unicode (Adobe-Japan1/GB1/CNS1/Korea1 tables), embedded font
  program glyph names (emon's own `cff`, `cmap`, a Type 1 eexec reader, TrueType
  `post`), Type 3 fonts, symbolic fonts, the "no mapping at all" path
  (flagged; OCR's problem later).
  *Done (P3.4, D140-D143)*: **CJK**: `kin/scripts/cjkfetch.clj` fetches Adobe's cmap-resources at a pinned commit into
  `.cache/` and checks `test/fonts/cjk-sources.edn`'s SHA-256s; `cjkdata.clj` makes `kin/cmapjp|gb|cns|kr.kin` (112
  predefined CMaps, each stored as its differences from a base, and the four CID -> Unicode tables) and `emon.cjk` reads
  them; `pdffont` takes a predefined CMap's codespace, WMode and CIDs, and a `/CIDSystemInfo` ordering (Identity-H and
  embedded CMaps too) selects the Unicode table (`how` = `cid`). **Font programs**: `kin/fontprog.kin` reads a Type 1
  clear-text `/Encoding`, a CFF Encoding with its charset and strings, and a symbolic TrueType font's cmap and `post` (and
  an OpenType wrapper), with no read that can fault; the program's built-in encoding replaces the guess of P1.6 (this was the
  remaining source of `unknown`), and a program that names glyphs withdraws the guess for a code it gives no Unicode name.
  **MacExpert** is read as text (`fontextra.kin`), **/W2** is parsed (`pfn-vertical-metric`), Type 3 names that name
  drawings are unknown, and a font nothing explains carries warning bit 1024 ("no mapping at all"). **Radicals**: Kangxi and
  CJK radical code points fold to the ideographs they are drawn as, in `emon.normalize` (flag 4; D142). **Validated** on
  1,288 corpus files against pdfminer.six: shown-character agreement 99.92% before and after, `unknown` 1.50% -> 0.40% of
  shown characters; CJK documents 99.94% -> 99.97% (D143). **Gates**: `bb unit` on every arm; `bb unit red` fails on every
  arm. Out: `/CIDToGIDMap` and a reverse cmap for composite TrueType fonts; `pdfcontent` reading `/W2`.
- [x] P3.5 — Full graphics state: clipping paths (visibility), colour spaces
  (to classify ink colour), `ExtGState` alpha, render modes, paths recorded as
  `vector` evidence (lines, rects, fills), image placements (`Do` image,
  inline `BI…EI`), shadings as filled regions. **Done** (D152-D154): the
  interpreter reads `m l c v y h re S s f F f* B B* b b* n W W* sh w d`, the stroking colours and
  every colour space of `emon.pdfgfx`, `gs` (`ca CA BM SMask LW`), Form XObject `/BBox` clips, image
  placements (XObject and inline) and `/OC` spans; the evidence has `paths` rows with their geometry in `pts`,
  `images` rows, clip rows, and the glyph's stroke colour, stroke alpha, clip and blend/soft-mask bits. Not done: a
  tiling pattern's own content, `/Font` in an ExtGState, a soft mask's group (its presence is recorded and makes
  paint "not exactly known"), exact (non-bounding-box) clipping by a non-rectangular path.
- [x] P3.6 — Visibility classification (SPEC §8.1): clipped, off-page,
  white-on-white, covered-by-later-opaque-paint, invisible mode. Writes the
  evidence hooks `vis`, `vby` (the occluder's `z`) and `vchk` on glyphs and
  images (SPEC §8.4.3, D096). **Done** (D155, D156): `emon.pdfvis`, run at the end of each page; classes clipped,
  offpage, white-on-white, zero-size (under one point), occluded, ocg-hidden, transparent; render modes 3 and 7 stay the
  `hidden-text` source and a Tr 3 OCR layer over a scan is never dropped or "occluded". 17 fixtures (3,278 hand
  or independently computed expectations) on every target; poppler's text and page agree on the real corpus (D156).
  `bb gates` green; `bb unit red` fails on every arm.
- [ ] **P3.6a — Path visibility and whole path geometry** (SPEC §8.1, §8.4.3; D219; graphics fusion, D218). **Wave 1**
  of the Phase 7 table; depends on nothing new; parallel with everything in wave 1.
  *Brief*:
  1. **Paths get `vis`, `vby`, `vchk`** in `paths` cells 19, 20, 21, and the backdrop colour the white check found in cell
     22 (0xRRGGBB, -1 unknown; cell 23 stays spare). `pv-page` (`kin/pdfvis.kin`) already takes `pa`: add a pass over the
     page's `paths` rows from `p0` that are not clips (`pg-clip`) and not shadings (`pg-shading`: `vchk` 0), running the
     glyph checks in the glyph order on the path's painted region (its box intersected with its clip's box; a stroked
     rectangle's box shrunk by half its width, as the occluder test already does): clip (no overlap with the clip box),
     offpage, white (ink = stroke colour if stroked, fill colour if filled, both if both; against the same backdrop search
     as a glyph: the nearest EARLIER opaque axis-aligned rectangle fill covering the whole region, else the page white;
     write the backdrop found, -1 when the search went silent), zero-size (the region is empty BOTH ways: a 0-width stroke is
     a hairline and is NOT zero-size), occlusion (a LATER exactly known opaque paint covers the whole region; `vby` its `z`),
     ocg (`ocgdef` 0 on the `ocg` chain), transparent (every painted component's alpha 0). Bits 128/256 (blend, soft mask)
     are set by the interpreter for paths as for glyphs (check `pc-op`'s paint path sets them in `pf-flags` today: copy them
     into `vchk`). The 4,096-row look-back bound applies.
  2. **Whole geometry for straight paths**: `PATH_KEEP` (`kin/pdfcontent.kin`, 128) stays for a path with any curve; a path
     made only of `m l h re` keeps up to `PATH_KEEP_STRAIGHT` (4096) points; a path cut short sets the new flag
     `pg-truncated` (512) and keeps its box (D152's rule). A page's `pts` rows are bounded by `PTS_PAGE` (1,000,000; past
     it every later path is truncated, warning 1024 as for paths).
  3. **Evidence**: `kin/evidence.kin`: `pf-vis` 19, `pf-vby` 20, `pf-vchk` 21, `pf-backdrop` 22 accessors, the `pg-truncated`
     row of the `pg` enum, and the dump's `ev-fd` names for the four cells (the dump changes; the Model does not).
  4. **Fixtures** (`kin/scripts/vis-fixtures`, new expectation rows, `test/vis/paths.pdf`): per class, a horizontal rule
     and a thin filled rectangle that are clipped away, half clipped (visible: §9.9 trims it), off the page, white on
     white, white on a black rectangle (visible), occluded by a later white rectangle and by a later image, under an OCG
     that is off, under an `/AS` print-only OCG (visible, `ocgdef` 2), at alpha 0, with a Multiply blend and with a soft
     mask (visible, bits 128/256), a 0-width hairline (visible), and a grid of 30 `re` subpaths in one path (geometry kept,
     not truncated) and one of 1,100 (truncated).
  *Files*: `kin/pdfvis.kin`, `kin/pdfcontent.kin` (the constant, the straight-path test, the flag), `kin/evidence.kin` (the
  paths section and the `pg` enum only), `kin/pdfvistest.kin`, `kin/pdfcontenttest.kin` (the async run of the new fixture),
  `kin/scripts/vis-fixtures`, `test/vis/`, the evidence goldens that print `paths` (regenerated; list them in this entry).
  *Measured*: `kin/scripts/vis-check --render` on the new fixture: every path expected visible leaves ink in poppler's
  render and every path expected hidden leaves none; `tools/vis/corpus-vis.clj` gains a paths column: per class, the count
  of paths and of rule-like paths (D179's sizes) on the 899 files, recorded here.
  *Done when*: the laws on four targets; every Model of every gate byte-identical (only evidence dumps move); `bb gates`
  green.
- [x] P3.7 — Tagged PDF: structure tree, role map, MCID ↔ content mapping,
  `/Alt`, `/ActualText` (use it when present — it is the author's answer),
  informativeness score (D034). Also (D076): Artifact subtypes as
  furniture/watermark evidence; `H`/`H1`–`H6` as heading-level priors;
  `Table`/`TH`/`TD` `/Scope` `/Headers` `/RowSpan` `/ColSpan` `/Summary`;
  `L`/`LI`/`Lbl` + `/ListNumbering`; `Figure`/`Formula` `/Alt` → placeholder
  description; `Caption`, `Note`, `Reference`, `Link`; per-element `/Lang`;
  `/E` expansions; PDF/UA identification (XMP `pdfuaid:part`) as a trust
  signal. Test with synth pairs: correctly tagged vs deliberately mis-tagged.
  *Done (P3.7, D148-D151)*: `kin/pdfstruct.kin` (names and the 52 standard structure types generated by
  `tools/pdfsnames.clj` into `kin/pdfsnames.kin`). **The tree** is read whole into `se` (StructTreeRoot as row 0, elements in
  pre-order), `sk` (kids in order), `sc` (marked-content references), `so` (object references): RoleMap resolution (a
  standard type is used as it is, a custom one followed to the first standard type, loops and unmapped names say so), `Hn`
  by its numerals, plain `H` given a level prior from its Sect ancestors, `/Pg` inherited, `/Alt /ActualText /E /Lang /ID /T`
  (the effective language's source row), Scope, Headers, RowSpan, ColSpan, Summary, ListNumbering through `/A` and the
  ClassMap, a table's rows and columns, a list's labels checked against its ListNumbering, the ParentTree checked against
  what each element claims; loops, shared elements, 70-deep trees and hostile `/K`s are read once and noted. **The join** is
  exact: `(page, Form XObject, MCID)` to the `marks` row, every glyph owned by the element of its innermost marked content
  with an MCID, or artifact, or neither; one `sa` row per outermost artifact span says where its text sits and whether that is
  what its Type and Subtype claim. **The score** (D149, D150) is seven per-mille components (coverage, role diversity, reading
  order against geometry, heading roles against type size, heading levels, artifacts against position, role nesting), a
  weighted harmonic mean scaled by the weakest check; PDF/UA raises the prior and the trust, never the score. **Verified**
  (D151): `tools/oracle/tagged-check.py` (pypdf, its own walk of the tree) agrees on **11,944,254 items in all 504 tree-bearing
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

- [x] P4.1 — Blocks and reading order: XY-cut with spanning elements,
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
  *Done (P4.1, D163-D166).* New `kin/columns.kin` (`emon.columns`: `lc-copy`, `lc-page`) and `kin/para.kin` rebuilt around it
  (`pa-build` has a new, longer signature: the column stage's scratch and a debug log are arguments; `host/rust/src/engine.rs` was
  changed to pass them). Per page: the lines are copied (the evidence is not touched), side lines (margin notes, sidebars) are set
  aside, **gutters are found over words**, lines that cross one are **split**, the extents are carried along the columns, the **cut
  tree** is made (vertical first; horizontal only at a full-width gap, at the end of an extent that no other gutter's extent
  contains, or under a title) and its leaves are the **column fragments**, numbered `band`/`col`; the page's `layout` map is written
  for every page that has text (one cell for a one-column page); then the paragraphs are built fragment by fragment and a paragraph
  that fills its fragment is **joined** to the next by the points of D164, with hyphenation at the break by the rule of SPEC 3.6
  (no locale: the hyphen is kept). Margin notes and sidebars become `note` blocks placed after the paragraph beside which they begin
  (D165). `bb unit` has seven new laws in `kin/paratest.kin` (two columns, the join, the hyphen at the break, a title across the
  gutter, margin notes, a right-to-left page, a table across the gutter and a gap, a line written across the gutter and split at
  it); `kin/scripts/slice --update` rewrote the six `.full.html` goldens (they now carry `data-emon-col`), `test/para/*.json` the
  page `layout`; no paragraph of them changed.
  **Measured** (Engine, `bb accuracy`, pdf variant over the 55 synthetic documents; before = D145's baseline): all documents CER
  0.1842 to **0.0226**, WER 0.2420 to **0.0325**, paragraph-boundary P/R/F1 0.0699/0.6333/0.1259 to 0.0988/0.7380/0.1743 (what is left
  is headings, list items, cells and captions being paragraphs: P4.4-P5), `frag-col-acc` 0.0 to **0.71**, **join P/R/F1 1.00/0.91/0.95**,
  `hyphen-acc` 0.0 to **0.98**; the nine **column documents** (family `columns`) CER 0.5978 to **0.0053**, WER 0.7687 to 0.0074,
  paragraph-boundary F1 0.0935 to 0.667, `frag-col-acc` 0.67, join 1.00/0.91/0.95, `hyphen-acc` 1.0; `initials` (`initial-columns`)
  CER 0.0659 to 0.0055, `align` (`align-justify-hyphen`, two columns) CER 0.3355 to 0.0079; every other family is unchanged to the
  digit (tables 0.0077, lists, scripts, forms, figures, furniture, headings, footnotes); `pdf-tagged/all` unchanged. `order-tau` stays
  1.0. **`Done when`, honestly**: met on `columns-continue` (frag 0.95, join 1/1), `columns-margin-notes`, `columns-mid-page`,
  `columns-uneven` and `initial-columns` (frag 1.0, join 1/1), `columns-two` and `columns-three-news` (CER 0.0, no frags in their
  gold); **not met** on `columns-span-figure` (frag 0.5, join-r 0.67: the caption of a spanning figure goes with the columns under
  it because the figure is only ink the interpreter does not write yet, and the two-line remnant at a page's top that two columns
  continue has no three rows for a gutter) and `columns-newspaper` (frag 0.13: CER 0.0 and join 1/1, but the gold numbers every story's
  fragments `band 1` of one container where the cut tree makes four bands; the READING ORDER is the gold's).
  **The three arXiv papers** (read by eye from the Engine's JSON, a column map and each block's place and text; the stage's own lines are `pa-dump`'s): **1512.03385**
  title and authors are band 0, then two columns, in order; the abstract, section heads and footnote at the foot of column 1 are in
  their column; the figure's labels (CIFAR curves, `20`, `56-layer`, tick numbers) are paragraphs in the right column's top (P5), and
  the sentence that straddles the figure (`have also` / `greatly benefited from very deep models.`) is not joined (the first line of
  the next column is a chart label, not the continuation: D164). **1810.04805**: title and authors band 0, two columns in order,
  bullet items cut after their marker line (the hanging indent starts a paragraph: D135, P4.5), hyphenated words kept hyphenated (`representa-tion`), `In this paper...` joined from
  page 1 to page 2; correct. **2206.01062**: the page-1 header (author block of five authors in three cells) is read in a plausible
  order but `Rueschlikon, Switzerland bpf@zurich.ibm.com` is joined across the author cells and the abstract's lines are each a
  paragraph (D135: a document-wide line gap; the abstract is set looser than the body, so every line looks like a break): both are
  the paragraph builder's and P4.3's, not the columns'. Also by eye: the GAO report's left margin notes on page 1 and 5 are `margin`
  notes after the paragraph beside them; the ViT paper's display equations come out as two columns (formula, equation number): P4.9.
  **Still wrong** (by cause): (1) the stage reads no figure, path or image evidence (P3.5's `paths` and `images` exist since this branch
  merged main, and are not read: a figure that is only ink does not end a gutter, a caption that sits between a figure and columns goes with the columns), and the text of a figure is
  paragraphs in whichever fragment its words lie; (2) running headers, footers and page numbers are not out of the flow (P4.3), so a
  footer is a paragraph and a header can be joined to the first paragraph of the page (GAO page 5/6); (3) line numbers in a margin
  (a column of digits) are dropped from the flow without a ledger entry; (4) a paragraph that ends exactly at the bottom of a column in a
  full line closing a sentence and whose next column starts in capitals has no evidence of continuing but "the column is full": it
  is joined when the line reaches the edge (D164), and the synthetic corpus has paragraphs that do and do not; (5) two columns that
  continue a paragraph for fewer than three rows at a page's top are read row by row; (6) the first line of a continuation after a
  figure at the head of a column; (7) tight-leading and loose-leading text in one document (`pa-new-par`'s document-wide median);
  (8) a table of cells of three words or more with a gap of under three sizes is read as columns (no table stage yet); (9) a
  producer that writes one word per line in a column (rare) gives words, not lines, to split.
  *Learnt*: a page's structure is found by dumping what the algorithm decided (each gutter's extent and why it ended, each fragment's
  box), not by reading the code: every constant of D163 that is not the SPEC's came from one such dump (the median word gap that
  ended every gutter of a justified three-word column, the table row that closed the gutters of a newspaper, the footnote that
  was body type at 0.77 of the size). Go's `declared and not used` found a local that `rustc` accepted (`r` of `pa-columns`, kept as `^:mut` and never read);
  the nested-read-of-a-mutated-`Cells` borrow error occurred twice (a call that took `(ln-get lt ...)` as an argument while `lt` was the
  mutable parameter) and was fixed by binding the read first.
  *Commit*: on branch `worktree-agent-a7d069f8e28497363` (D037).
- [x] P4.2 — Style inventory and body style (SPEC §10.3.1). *Done (D167, D168).* `kin/style.kin` (`emon.style`): per-line features, size/weight/colour/case classes, body = most characters, the line gap, row siblings, margins and indent; laws in `kin/headingtest.kin`.
- [x] P4.3 — Running furniture: candidates, templates, decomposition,
  policy (D017); page labels from detected numbers.
  *Done (P4.3, D173-D176)*: `kin/furniture.kin` (`emon.furniture`): `fu-find` runs BEFORE the paragraph stage and takes the furniture's
  lines out of `lines` (nothing in `para.kin` knows), `fu-write` AFTER it writes the page labels, the ledger (`page-number`,
  `running-title`, `watermark`), the `furniture` roots (template, role, pages, regions; `ACME-{n:02}` for a number that follows the page;
  identical rows merged) and the `label` of every page milestone. Evidence: position bands, recurrence on a digit- and roman-abstracted
  64-bit template with a per-slot analysis (constant / follows the page / varies), isolation from the body (a table's header row is not
  furniture), the tagged tree's /Artifact marks weighed by `trust` (D076, D150: `tg-trust`), style and position for text that varies
  (chapter titles, odd/even), a diagonal-large-text watermark rule, `/PageLabels` (P3.9) over printed numbers. Decomposition: page slot,
  page words, "of" words and the total, what remains is dropped when the Info `/Title` or a line of the body says it, else kept.
  The Engine (Rust) calls `px_index_build`, `pm_run`, `tg_read`/`tg_join` before the Model, `Options::furniture` (`policy|keep|drop`,
  `--furniture=` on the driver). Verified: 13 laws (`kin/furnituretest.kin`, all four targets), `bb accuracy` (`pdf/all` furniture
  P 0 -> 1.0, R 0 -> 0.914, F1 0 -> 0.955, `page-label-acc` 0.707 -> 1.0, `watermark-leak` 0.002 -> 0, CER 0.1842 -> 0.1761, WER 0.2420 ->
  0.2332; the furniture family CER and WER 0), and by eye on the Berkshire reports, GAO, FOMC, 8 arXiv papers, 199 CUAD contracts and 600
  PDF/A files (no panic; every drop a page number or text the body says). Wired into the Go Engine too and visibility left for later: D177; a header on one page of a short document is found only by the style rule.
- [x] P4.4 — Headings: candidates, numbering schemes, priors (tagged, outline,
  TOC detection), global level assignment, sections (D018). *Done (D167-D171), wired into both Engines (D172), minus the tagged prior (D172) and TOC detection; the outline prior wired and mastheads demoted (D177).* `kin/heading.kin`; `kin/para.kin` takes `hc`; with the real `emon` predictor on the 160 synthetic documents (all variants) the heading P/R on `pdf/all` are 0.986/0.982, level accuracy 0.963, end to end 0.946; `kin/scripts/heading-check` holds 8 goldens (`test/heading`) to the gold.
- [ ] P4.5 — Lists: markers, nesting, continuation across breaks.
- [ ] P4.6 — Footnotes/endnotes and references; captions; bare URLs.
- [ ] P4.7 — Inline styles (bold/italic from font flags, names and stem
  widths; sub/sup from baseline shift; mono; small caps), links onto words.
- [ ] P4.8 — Locale: keyword tables (start with en, de, fr, es, pt, it, nl,
  zh, ja), dehyphenation with emon's own Liang `hyphen` (D021, D029).
- [ ] P4.9 — Code blocks, quotes, formulas (placeholder by font/glyph
  signals), form fields into the flow.
- [x] P4.10 — Initials on the text layer (SPEC 10.9, D108, D109; new
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
  **DONE (D183, D185).** `kin/initial.kin`, wired into `pa-build` right after `lc-copy` and before `pa-columns`, and in `attempt()` of
  both hosts (the glyphs, the fonts and the locale as code points are new arguments). Measured with `bb accuracy`, the Rust Engine
  over the 55 synthetic documents (`pdf/all`): `initial-p` 0 -> 1.0, `initial-r` 0 -> 0.8696 (20 of 23: the three that are left are
  `initial-image` and `initial-vector`, glyph-less, P6.7's), `initial-f1` 0 -> 0.9302, `initial-form-acc` 0 -> 1.0, `first-word-acc`
  0.0435 -> 0.6522 (the rest are the declared `A`/`I` cases: no locale joins, `A` + `long` is `Along`; with `--locale=en` it is `A long`),
  `initial-false-heading` 0 (the heading stage is not in yet: the divider letters of `initial-letter-headings` stay paragraphs of
  their own and the three headings are P4.4's to keep), and on the initials documents `cer` 0.005534 -> 0.001383 and `wer`
  0.018251 -> 0.009642 (`pdf/initials`). The four real files of D108, by hand: `layout/pdfa-eng-wds` 0182448 p1 is one paragraph
  `THE BUREAU has designed and constructed ...` (a drop `T` of two lines whose baseline is its second line's: the text layer made the
  `T` a glyph of that line, and the stage takes it out and puts it first), 4271234 p2 a drop `B` of three lines, 0795114 p2 a drop `W`
  of two, 0116458 a drop of two on p1 (`O`, the first word `On`, whose baseline is the second line's), p3 (`I`) and p4 (`G`, and a
  `W`). A survey of 243 + 160 files of `corpus/` (every 6th of `layout`, `multilingual`, `forms`, `formats`, `tables/gov-reports`;
  every 14th of `doclaynet`, `financial`, `tables`) found 1 more true drop cap (1085401 `S`, 4788866 `S`+`T. LUKE`), Russell's
  chapter openers (`4904875`: fifteen, `IS there`, `THE word`), a raised `m` (0422963), and two kinds of false positive that
  are now rules (D183): a column of lettered labels (`2983077`: `B` `Domestic ...`) and statistic callouts (`3794508`: `89%`).
  *Where it differs from the brief*: `/CapHeight` is read when `fonts` has one (`fnf-capheight`, -1 until a reader writes it) but
  no reader does: the descriptor reader, its name table and the evidence goldens (`capheight` would appear in every dump) are
  P1.12's and a change to all of them for a fallback that works (0.7, and a per-script value) is not this item's; the join
  tables are `defdata` in `initial.kin` and, instead of waiting for P4.8, have English lists (D169).
  *Still wrong*: the join does not ask a tagged tree or `/ActualText` for the space (SPEC 10.9: "a space only when a healthy tagged tree
  or `/ActualText` holds one"): the marks are not read here, so a tagged file's `A long` is still the lists' or the flag's decision;
  a glyph-less initial (P6.7); a drop initial whose paragraph has one line (no line N+1 and N = 1: not found); an
  initial in a table cell or a figure (its geometry is found, and it is then a paragraph with an initial inside it); a margin
  initial at the end of a column with no lines after its N is recorded as a drop (the two cannot be told apart there); a
  CJK paragraph's lines are still joined with a space (`initial-cjk` `wer` 5.5: not this stage's, SPEC 3.6 has no CJK rule yet).
  *Learnt*: Chrome's floats (the whole synthetic corpus) are one placement of the baseline and print is another: the initial
  stands on the baseline of its LAST line, which the text layer then makes a glyph of that line (and a line's baseline
  segment starts at the first glyph's origin, so `0116458`'s first line has a slanted baseline and is not below its second); a
  stage that reads only the synthetic corpus would have found 20 of 23 and none of the real ones. A Hebrew `ב` is not 0.7 of its
  size tall (the cap-top rule failed `initial-rtl` by 1.4 pt until caseless scripts got their own ratio and an upward tolerance), and a
  CJK glyph's baseline is not where the ink starts (a quote mark `「` took the alphabetic ratio of the letter's script).
  *Commit*: on this branch (D037).
- [x] P4.11 — Alignment (SPEC 10.10, D110; `kin/align.kin`). *After P4.1*
  (it needs the fragment boxes); the cell part is P5.3's. *Brief*: the
  SPEC 10.10 tests and single-line rules, tolerance and `align-min` as
  named settings, base direction by P4.1's `bidi` (UAX #9 P2-P3); `align`
  on paragraph, heading and caption. *Done when*: on `align-paragraphs`,
  `align-justify-hyphen`, `align-rtl` and the column documents: `align-acc`
  at least 0.98 and `align-wrong` at most 0.01; `align-cov` recorded
  (single lines lower it by design).
  **DONE (D184).** `kin/align.kin`, called from `pa-build`: `pa-al-add` per line and a marker per block at its flush, `al-pass` after the
  last close. `pdf/all`: `align-acc` 0 -> 1.0, `align-wrong` 0, `align-cov` 0 -> 0.3976 (`paragraph` 0.85, `heading` 0.9412, `caption`
  and `cell` 0: the table cells are P5.3's and the one caption's fragment is its own box; of the gold's 83 aligned blocks 45 are cells).
  `align-paragraphs`: 1.0 and cov 0.643, `align-justify-hyphen` 1.0 and 0.615, `align-rtl` 1.0 and 0.556. The pass runs over the
  stream of marked blocks, so a stage that retypes a paragraph (a heading from P4.4, a caption) runs `al-pass` after it and the
  value goes to the right attribute (`node-set-align` knows the kinds).
  *Where it differs from the brief*: the cell part is P5.3's as briefed; the base direction is the lines' own (`lh-dir`, the
  text layer's UAX #9 result per line) and not a second P2-P3 over the text: a paragraph's lines all read one way (the paragraph
  builder breaks on a change), and it is what `bidi` has already decided.
  *Still wrong*: a block of two lines that are both flush at both edges has no value (justify wants one non-last line more); a
  flush-start single line in a class with justified multi-line blocks gets none (it may be their last line); a centred caption
  alone in its fragment (its own box is the fragment's) has none.
  *Commit*: on this branch (D037).

## Phase 5 — Tables

- [x] P5.1 — `ruled` profile from vector rules: lattice, cells, spans. **Done** (D170, D171): `kin/table.kin`
  (`emon.table`), run before the paragraphs in `attempt()` on both hosts; thin filled rectangles and stroked lines are rules,
  merged and clustered, a component with two rules each way is a lattice, an edge no rule covers is open and the cells it
  joins are one cell with a row or column span; text is assigned by containment.
- [x] P5.2 — `partial`, `shaded` and `whitespace` profiles; profile scoring. **Done** (D172): columns from the whitespace
  channels of the text; partial from chains of horizontal rules (and their sub-chains), shaded from stacks of fill bands,
  whitespace from runs of rows cut in chunks; every candidate scored and the best of a region kept (`tb-select`); prose in
  columns refused by cell length, sentence ends, lowercase starts and how full the grid is.
- [x] P5.3 — Header rows, row groups, numeric columns, captions. Also
  cell `align` per table column (SPEC 10.10, D110): measured over the
  column's body cells against its text extent; header cells separately;
  `align-table-numeric` scores it (`align-acc` for cells at least 0.98). **Done** (D173): head rows from bold (else a
  non-numeric first row over a numeric column), a foot from a bold last row, spans of header cells in text-based tables,
  `align` per column (`align-acc` 1.0, `align-wrong` 0 on `align-table-numeric`), captions by keyword and number.
- [x] P5.4 — Tables across pages: with and without recurring headers,
  "continued" markers, split rows (D019). **Done** (D174) except split rows and a free-standing "(continued)" line: the
  synthetic multi-page documents score TEDS-lite 1.0 and `table-continued-acc` 1.0.
- [ ] **P5.5a — The fused-graphics arenas and the vector source; `emon.table` reads `grl`/`gfl`, never `paths`** (SPEC
  §9.7, §9.9 "Vector candidates", §10.5 "What the profiles read"; D218, D220). **Wave 2** of the Phase 7 table. Depends on
  P3.6a (step B only) and P7.0 (`kin/player.kin`); parallel with P6.4, P7.1, P7.2.
  *Brief*:
  1. `kin/glayer.kin` (`emon.glayer`, new): the layouts of `grl` (24), `gfl` (16), `ggp` (12), `grg` (16) exactly as SPEC
     §9.7, their accessors and builders, the enums `go` (h 1, v 2, slant 3), `gq` (the flags of §9.7, one bit each in the
     order written there), `gk` (rule 1, fill 2, region 3), `rk` (table-in-image 1, figure 2, table-hint 3), `gl-check` (every
     run and reference, as `ev-check`) and `gl-emit` (table driven like `ev-fd`; dump version "2", keys after `hl` in the `ea`
     order of SPEC §9.7). In `kin/player.kin`, in ONE block below P7.0's code headed `;; P5.5a graphics arenas`:
     `pl-append`, `pl-check` and `pl-emit` call the `gl-*` functions (re-basing `refs` runs and `ggp` indices).
  2. `kin/gvec.kin` (`emon.gvec`, new), `gv-page`: the page's vector candidates into a scratch `Cells` (stride 16: kind
     rule/fill, `paths` row, first `pts` row of the segment (-1 for a box-only candidate), page, box, endpoints, thickness,
     colour, style, flags), in TWO modes chosen by an argument: **`legacy`** reproduces `tb-collect-one` exactly (a path's
     box is one candidate; a stroked rectangle four edges; "mean channel under 240"; no visibility), and **`segments`** is
     SPEC §9.9's (per segment and subpath from `pts`; flat curves as lines; `pg-truncated` paths as their box; clip trimming;
     `vis` ≠ 0 kept but flagged so `gf-page` can record it; contrast against `backdrop` ≥ `gf-contrast` 16; dash → style;
     thin visible image placements as `from-image` rules; `/Artifact` type from the `mark` chain via `mk-artifact-of`).
  3. `kin/gfuse.kin` (`emon.gfuse`, new; P7.G extends it), `gf-page` in its **vector-only form**: candidates → `grl`/`gfl`
     rows through rows 501, 505, 514, 560, 562 (as code, the `fr` number written into the row; P7.G moves them into
     `kin/fuserules.kin` as data). Collinear vector candidates that touch (D179's 3 pt) are NOT merged here: `tb-build` merges
     them as it does today, so the identity holds.
  4. `kin/table.kin`: `tb-collect`/`tb-collect-one` become a reader of `grl` (orientation h/v only, `artifact-pagination`
     skipped) and `gfl`; `tb-build`'s `pa` argument becomes `grl gfl`. Nothing else in the file changes.
  5. **Engines**: in both `attempt()`s, per page and before `tb-build`, `gv-page` + `gf-page`; if P7.U has merged, in
     `pu-run` (D195's place, SPEC §9.9) and `pl-append` carries them; `tb-build` takes the document's `grl gfl`.
  **Step A (identity)**: with `legacy`, every Model of every gate byte-identical (`bb slice`, `bb accuracy`'s `emon`
  unchanged, `bb identity`, `bb ocr`, `bb image`); commit. **Step B**: switch the default to `segments`; record BEFORE (step
  A) and AFTER on `pdf/all` and every table category (`table-teds-lite`, `table-teds-struct-lite`, `block-table` P/R),
  on ICDAR 2013 live (D182: P 0.78 R 0.86, TEDS-lite 0.48, struct 0.68), and the 88-PDF survey (no panic); P6.8's `gfx-*`
  fixtures: `gfx-hidden-rules` a 3 × 3 lattice (not 4 × 4), `gfx-onepath-grid` and `gfx-curve-lines` their lattices,
  `table-white-rules` its header's columns ruled; explain every changed table document by document here.
  *Files*: `kin/glayer.kin`, `kin/gvec.kin`, `kin/gfuse.kin` (+ `glayertest.kin`, `gvectest.kin`, `gfusetest.kin`),
  `kin/table.kin` (the collector and `tb-build`'s argument only), `kin/player.kin` (the block above), the two Engines (the
  calls above).
  *Done when*: step A's identity and step B's numbers in this entry; laws (a path of 30 `re` subpaths gives 30 rules in
  `segments` and one fill in `legacy`; a clipped half rule is trimmed; a white rule on black is a rule; a hidden rule is
  flagged; `gl-check` 0 on a hand-built two-page layer) on four targets; `bb gates` green.
- [ ] **P5.5 — `struct` (tagged) tables verified against geometry** (SPEC §10.5 "Tagged tables"; D225, D034, D148-D150).
  **Wave 3** of the Phase 7 table, parallel with P7.3 (disjoint files). Depends on P5.5a and P6.8's tagged-table
  fixtures. `form` moved to P5.5b.
  *Brief*:
  1. `kin/tagtable.kin` (`emon.tagtable`, new): `tt-grids` (every `Table` element with `tg-trust` ≥ `tb-tag-trust` 300
     and a passing nesting check → a tagged grid: rows, cells placed by `ColSpan`/`RowSpan` into the first free slot, head
     (`THead` rows, rows of `TH` with `Scope` Column or Both), row headers (`TH` `Scope` Row), foot (`TFoot`)); `tt-words`
     (a word's cell: its first glyph's `mark` → the `sc` joined on `(page, stm, MCID)` → the element → its nearest `TH`/`TD`
     ancestor; build a `marks` row → `sc` row index once a document); `tt-verify` (the six checks and the score of SPEC
     §10.5, D150's harmonic mean and weakest-check scale, the verdict). Settings `tb-tag-trust` 300, `tb-struct-ok` 800,
     `tb-struct-bad` 500, `tb-tag-win` 700, `tb-tag-veto-trust` 800, `tb-tag-veto` 150, `tb-tie` 25 in the file's head.
  2. `kin/table.kin`: the struct candidate (profile `struct`, case 3), the arbitration of SPEC §10.5 cases 1-7 in
     `tb-select`, the tags' head rows, row headers and spans applied to the chosen candidate, OCR `table-hint` regions as
     the tie-breaker (they are empty until P7.G: the code path is tested by laws), and the table's provenance in `tb`'s
     spare cells: 20 sources (`sr` bits), 21 the `se` row (-1), 22 the verdict, 23 the score; `tb-emit` adds `struct` to the
     table node's `src` when the tags shaped it and flags `struct-disagree` / `tag-span-conflict`.
  3. **Engines**: `tb-build` also takes `se sc ss` (the tagged tree's arenas, already in the document pass) in both.
  *Files*: `kin/tagtable.kin`, `kin/tagtabletest.kin`, `kin/table.kin` (`tb-select`, the struct candidate, `tb-emit`'s
  provenance), the two Engines (the `tb-build` call), `test/table/` goldens of the tagged fixtures.
  *Measured* (BEFORE = P5.5a, AFTER = this): `tag-verdict-acc` 1.0 on P6.8's `ttab-*` fixtures (each fixture's declared
  verdict); `ttab-good` and `ttab-good-ws` `table-teds-lite` 1.0, with the untagged twin of `ttab-good-ws` recorded beside it
  (the gain the tags give a ruleless table); `ttab-bad-*`, `ttab-allp`, `ttab-layout`: the same TEDS as their untagged twins
  (a wrong tag costs nothing); `pdf-tagged/all` table metrics ≥ `pdf/all`'s; `pdf/all` unchanged (no tags); the 504 tagged
  corpus PDFs: `tag-table-agree` and the verdict histogram recorded here, with ten contradicted tables looked at by eye.
  *Done when*: the above; laws (a correct grid confirmed; two visual rows in one `TR` fail `row-band`; a span across a
  closed edge flags `tag-span-conflict`; prose tagged `Table` refused) on four targets; `bb gates` green.
- [ ] P5.5b — `form` profile (key/value grids: label cells and value cells, often ruled; SPEC 10.5), moved out of P5.5
  unchanged; reads `grl`/`gfl` like every profile. The `bank-statement` miss of D182 is its first fixture.
- [ ] P5.6 — TEDS in `bb accuracy`; PubTabNet/FinTabNet/ICDAR scored. ICDAR 2013's adjacency F1 (`table-adj-f1`) comes
  with P6.8.

## Phase 6 — Raster, tracer, OCR

- [x] P6.1 — `Bitmap` port + `ImageDecode` port (Rust: `image`/`tiff`
  crates + a JBIG2/CCITT decoder; Go: stdlib + x/image) under SPEC §7.2's
  contract; raster intake for PNG/JPEG/TIFF/multi-page TIFF; image sets as
  several inputs, mixable with PDFs (D058, SPEC §2.0); codecs read the
  host's byte source (forward or by offset, awaited in async hosts), and kin
  only sniffs the first chunk. *Done (D187-D190), minus image sets and PDF+image mixes (several inputs as one document
  are still `unsupported`, D144) and the PDF image filters (P6.1a).* The `Bitmap` port was P2.7's (D092, D093: its bulk forms
  and their kin reference are P6.3a's); P6.1 is the `ImageDecode` port and the raster intake. `kin/rasterin.kin`
  (`emon.rasterin`: `rs-sniff`, `rs-unit`), `host/rust/src/imgdec.rs` and `host/go/imgdec.go` (the port), the intake in both
  Engines (`dispatch` / `attempt_raster`, apart from `attempt()`), `bb image` (`kin/scripts/image`, a gate) over the 89 fixtures
  of `test/image/` (`tools/image-fixtures.clj`, ImageMagick the oracle). PNG, JPEG, TIFF (multi-page, tiled, planar, LZW,
  PackBits, Deflate, CCITT G4 and G3 1-D, 1-16 bits, palette, CMYK, YCbCr, alpha) decode to **identical pixels on Rust and Go**
  (JPEG within 6 per byte live and exactly through a transcript, D189). *Left*: JPEG in TIFF, CMYK/YCCK JPEG, G3 with fill bits
  or 2-D, float and over-16-bit samples, Lab/LogLuv, BigTIFF are refused by name (`unsupported-image`); no ICC (D188); the image
  page has no text until OCR (P6.5); `dpi-unknown` pages are read as 300 dpi (D187).
  *Learnt*: babashka's SCI drops, without a word, a direct call of a function with more than about a dozen arguments (the fixture
  generator's first picture was never drawn; `apply` a vector); `fax`'s `decode_g3` loses the last line of a stream with no EOL
  after it (libtiff writes none), and `x/image/ccitt` reads no fill bits, so the two hosts' G3 sets were made equal by refusing
  what one of them cannot read; the Go JPEG decoder replicates chroma (differences of 60 at a colour edge) where libjpeg and
  zune filter it, so the Go host now filters it as libjpeg does (differences of 2-5).
  *Commit*: on branch `worktree-agent-a69e90e20db79475c` (D037).
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
- [ ] **P6.3 — Normalization: grayscale, ink layers, Sauvola, deskew, orientation** (SPEC §8.2 "As designed", D092,
  D093, D096, D197). **Critical path, wave 1.** Depends on: P6.2 and P6.5 merged to main (renders and the evidence's
  `views`). Parallel with P7.0, P7.U, P6.6 (different files; see "Who edits what" under Phase 7).
  *Brief*: a kin module `emon.norm` that turns ONE raster (a render or a decode, given as a `Bitmap` plus its `views`
  row) into the derived views and the ink layers, and fills the raster feature columns. No Engine wiring here (P7.U
  calls it from the page unit); everything is laws and a bench.
  1. **Bulk forms** (D092's rule; each a pure whole-page map or reduce, with a kin reference implementation in
     `emon.norm` that a law holds bit-identical on all four targets, D093): `bitmap-gray-into (src, dst)` (`(77r + 150g
     + 29b + 128) >> 8`; a 1-channel source copied); `bitmap-hist-rgb (src, cells, off)` (4096 bins of `r>>4, g>>4,
     b>>4`, gray sources use `v>>4` on all three); `bitmap-lut-into (src, lut cells, off, dst)` (per pixel, the LUT cell
     of its 4096-bin index, low 8 bits, into a 1-channel `dst`); `bitmap-hash (b)` -> FNV-1a 64 over w, h, channels
     and the pixel bytes, answered in two cells by an out `Cells` (D196's key of a kin-made view); `bitmap-quarter-into
     (src, dst, turns)` (clockwise quarter turns, exact). Declared in `host/rust/src/port.rs`, `host/go/port/port.go`,
     `host/java/emon/Port.java`, `host/csharp/Emon/Port.cs`, in ONE new section at the end of each file headed
     `;; P6.3 bulk forms`; `bb hosts` agrees.
  2. **`nm-view`** (`emon.norm`): given `epages views` and the raster's `views` row, writes a `gray` view (`vk-gray`), the
     ink layers (`inks` rows: centroid, lightness, chroma, pixel count, `ir` role, mask view, spread) by histogram
     clustering exactly as SPEC §8.2 says (settings `ink-merge`, `ink-light`, `ink-chroma` named in the file's head
     with their defaults), one `ink-mask` view per non-paper layer, and a `binary` view by **banded Sauvola** (`2r + 2`
     rows of integral images at a time; `r` = 15 at 300 dpi scaled by `dpi/300`, `k` 87/256; bilevel decodes skip it).
     Kin-made bitmaps are allocated with `bitmap-new` here (P7.U switches them to `bms-put` so the host keeps them).
  3. **Deskew** (`nm-deskew`, decode views only): projection profiles of the body layer's component centres (from the
     binary view's row runs; P6.4's labeller is not needed, a centre per run cluster is enough) over ±5° at 0.1°, then
     ±0.1° at 0.01°, `ev-sin`/`ev-cos`; the angle goes to the view's `deskew` and the `oruns.deskew` of later runs; no
     resampled bitmap is made. **Orientation** (`nm-orient`): OSD's quarter turns when `oruns` OSD confidence ≥
     `osd-conf` (named, default 20 = 2.0), else -1 (P6.4's `tlines` decide later).
  4. **Features** (`nm-feat-ocr`, `nm-feat-text`): `xo` 10 (kin ink under the OCR word's box on the source view's binary,
     per-mille), 11 (engine-binary ink, when the run has an `engine-binary` view; else -1), 12 (Jaccard of the two inside
     the box), 13 (share of its ink on the body layer), 16 (mean gray of its ink against the layer-paper gray); `xt` 10
     (ink under the word on the `with-text` render), 11 (ink under it on the `no-text` render), 12 (share of its ink on
     the body layer). Boxes map through the view (`vw-cpt-to-px`); counts read the masks with `bitmap-next-nonzero`
     runs, never per pixel over the box.
  5. **Bench**: `bench/run.sh` gains a `norm` workload (gray, hist, layers, banded Sauvola on `inv_00007.noise.png` and on
     a 300-dpi RGB render of `watermark-draft`) in all four targets; record the table in this entry as D092 did.
  *Inputs*: `Bitmap`(s), `epages views inks feats` and the OCR/text word heads. *Outputs*: `views` rows (gray, binary,
  ink-mask), `inks` rows, `feats` columns above. *Files*: `kin/norm.kin`, `kin/normtest.kin` (new); the four port files
  (section above); `bench/`. Nothing else.
  *Measured* (`bb ink`, a new bb script over the 55 `pdf/` documents, rendered once by `bb render-record` into an ignored
  cache, NOT a gate until it is ratcheted by P7.S): **ink-layer purity from the text layer's own truth** — each visible
  text-layer glyph box on the `with-text` render: the share of its ink pixels in the body layer. Targets: body glyphs
  (opaque, upright, `vis` 0) ≥ 0.98 in `body`; the `watermark-draft` DRAFT glyphs (alpha 13 %) ≥ 0.95 outside `body`;
  the `stamp-seal` seal and stamp glyphs (blue, red) ≥ 0.95 in `colour` layers. Deskew on the P6.6 skewed variants
  (known angles): |error| ≤ 0.1° on 95 % of pages, ≤ 0.3° on all. Bench: within D092's 2× everywhere; Rust total ≤ 0.12 s
  a page (SPEC 12.3 budget).
  *Done when*: the laws (each bulk form against its reference over random bitmaps; banded Sauvola equal to `ras-sauvola`
  on three pages; a two-colour fixture separates into exactly two layers with the right roles; a page of known skew)
  pass on all four targets; `bb ink` meets the targets above; the bench table is in this entry.
- [ ] P6.3a — Raster kernels as host-accelerable whole-stage forms (D093): SIMD and GPU implementations of the P6.3 and
  P6.4 bulk forms behind the same declarations. **Not on the fusion path**: taken under P10.3 only if `bb fusion-bench`
  shows a raster stage over its SPEC 12.3 budget. Gate: every accelerated implementation bit-identical to the kin
  reference over the corpus.
- [ ] **P6.4 — Tracer: runs, components, contours, rules, primitives, text lines without OCR** (SPEC §8.2, §8.4.4, D198).
  **Wave 2.** Depends on P6.3 (binary and ink-mask views). Parallel with P7.1 and P7.2.
  *Brief*: a kin module `emon.trace` over a page's binary view and ink masks, writing the trace bundle.
  1. **Runs and components** (`tr-runs`, `tr-components`): row runs of the binary view (via `bitmap-next-nonzero`; a run is
     `y x0 x1` in a scratch `Cells`), 8-connected components by union-find OVER RUNS (two runs of adjacent rows join when
     their x-ranges touch with one pixel's slack), the smaller root wins (D092's determinism rule), components sorted by
     (top, left, size). Per component the `components` row of SPEC §8.4.4: box (pixels and page), ink pixels, fill,
     **stroke width** (the median horizontal and vertical run length inside it, not a distance transform: integer, cheap,
     measured against the distance transform on the fixtures; record the error), colour and ink row (the majority layer
     of its pixels, via the masks), mean gray, holes (background runs enclosed), parent (containment by box then by
     runs), `kind` (`ck`: text-like, rule-like, large, noise) by named size and aspect thresholds relative to the page's
     median text-like height. Bound: `trace-components` (200,000) a page, the rest counted (warning).
  2. **Contours** (`tr-contour`): the outer boundary of each component that is not text-like or noise, traced on its runs
     (Moore neighbourhood on a component-local bitmap of at most `trace-contour-px` pixels), simplified by integer
     Douglas-Peucker at `dp-eps` (1.5 px), written to `pts`.
  3. **Rules** (`tr-rules`): long thin components and long horizontal/vertical runs merged along their axis (gap ≤
     `rule-gap`), plus near-horizontal/vertical lines found by integer voting over the endpoints of rule-like components
     (angles within ±2°); centre-line endpoints, thickness, style (dashed and dotted from the gap pattern; double from two
     parallel rules within 3 thicknesses), member components in `refs`.
  4. **Primitives** (`tr-prims`): from contours: `rect` (four dominant directions, right angles ± 3°), `circle` /
     `ellipse` (algebraic fit in integers on the contour points, residual per-mille), `ring` (a circle with a concentric
     inner contour: a seal's double border), `arrow`, else `polygon`/`curve`; members in `refs`; confidence = fit
     residual mapped to per-mille.
  5. **Text lines without OCR** (`tr-tlines`): text-like components of one ink layer chained along their dominant
     direction (nearest neighbour within 1.5 heights, angle consistent within 3°), a baseline fitted to the bottoms
     (integer least squares over the members, then `ev-angle`), mean height and gap; a diagonal DRAFT is one `tlines`
     row at its angle on its layer. Orientation fallback for P6.3: the dominant `tlines` direction.
  6. **Features**: `xo`/`xt` 14 (text-like components centred in the box), 15 (components over symbols/glyphs), `xt` 23
     (overlap with a `tlines` row at another angle).
  *Inputs*: the views of P6.3, `inks`, `epages`. *Outputs*: `components rules prims tlines pts refs`, `feats` 14, 15, 23.
  *Files*: `kin/trace.kin`, `kin/tracetest.kin` (new). Nothing else.
  *Measured* (`bb trace`, a bb script over cached renders like `bb ink`; not a gate until P7.S): on the `no-text` renders
  of the 55 `pdf/` documents, **rules against the vector paths** (a `paths` row that is a stroked line or a thin filled
  rectangle is truth): recall ≥ 0.98, precision ≥ 0.98, endpoint error ≤ 2 px at 300 dpi; on the `with-text` renders,
  **`tlines` against the text layer's lines** (IoU ≥ 0.5): recall ≥ 0.97; DRAFT on `watermark-draft` found as one row at
  -30° ± 0.5° on a non-body layer; `stamp-seal`: the seal as `ring` and the stamp as `rect` (2 of 2); **mapo80** (live
  only, `corpus/seals/mapo80-stamps/images/val`, YOLO labels): seal-candidate recall at IoU ≥ 0.5 recorded as the
  baseline P7.7 must keep (no target yet). Rust ≤ 0.15 s a page.
  *Done when*: laws on hand-built bitmaps (two touching runs one component; a ring; a dashed rule; a 30° line of letters)
  on all four targets; `bb trace` meets the targets; timing in this entry.
  **Graphics amendment (D226, for P7.G; same item, same files):** (3a) in `tr-rules`, VERTICAL rules come from stacks of
  short row runs whose x-range agrees within ±1 px over at least `rule-min` rows (a vertical rule is never a long row run),
  and rules are found on the RUNS of every component, not only of rule-like ones (a table grid is one `large` component;
  its edges must still be rules); a rule's `confidence` is fixed as: 1000 minus the per-mille of its length that is gap
  (dashes excepted), minus 300 when its thickness varies by more than half along it, never below 0; (4a) `tr-tints`: on
  each `light` and `background` ink-mask view (P6.3), row runs at least `fill-min` (6 pt) long merged into rectangles whose
  consecutive rows share their x-extent within `fill-tol` (2 px), at least `fill-min` tall, become `prims` rows of kind
  `rect` with flags `filled` + `tint` (SPEC §8.4.4), the colour the layer's centroid, fill per-mille the mask's coverage of
  the box, confidence that coverage. *Measured, added to `bb trace`*: vertical-rule recall reported apart from horizontal
  (each ≥ 0.98 against the vector truth); **tints against vector fills** (a `paths` fill larger than 5 pt each way, visible,
  colour lightness ≥ 600) on the `no-text` renders of `table-shaded`, P6.8's `table-zebra`, `table-header-fill` and
  `gfx-shading-bands`: area recall ≥ 0.95, area precision ≥ 0.95; on their `scan/` variants recorded.
- [x] P6.5 — `Ocr` port with Tesseract in Rust and Go; capabilities; the
  first-pass plan; OCR-only pipeline for images and scanned PDFs (D033). *Done (D211-D216), minus the `Renderer` (P6.2: a PDF page
  that is not one upright JPEG covering it is not OCR'd), fusion (P7: a born-digital page keeps its text layer, D212) and acting
  on OSD.* `kin/scan.kin` (`emon.scan`: the OCR request, the answer's evidence, the pipeline's lines, the Model's provenance, the
  scanned pages of a PDF), `kin/rasterin.kin` (`rs-unit` now decodes, then OCR), `Config::ocr` / `Config.Ocr` (the port; none:
  `unsupported`, an answer), `host/rust/src/tess.rs` (Cargo feature `tesseract`) and `host/go/tess.go` (cgo, build tag
  `tesseract`) binding Tesseract 5.5.3, both live only (`EMON_RS_LIVE=1 kin/scripts/emon-rs-build`, `EMON_GO_LIVE=1
  kin/scripts/emon-go-build`; the drivers take `--tesseract`). An image page (PNG, JPEG, TIFF frames) and a scanned PDF page (no
  text layer, one upright DCT image covering it) go decode -> `ocr` plan request -> the host's engine -> kin's evidence ->
  lines -> furniture -> headings -> paragraphs and columns -> the Model, the same pipeline as a born-digital page, in both Engines.
  `settings.ocr`, `scanned` pages, `ocr` sources and `ocr-only` say where the text came from. `bb ocr` (a gate) replays five
  committed scan transcripts (`test/ocr/scan/`) on Rust AND Go through the whole Engine to one bundle (JSON, HTML, evidence),
  holds `transcript-miss` for a changed request and the `emon-replay` ratchet; `bb ocr-live` (not a gate) scores the 55 scans
  through live Tesseract (the scoreboard's `emon-live`) and a sample of FUNSD, SROIE and CORD gold. **Scan variants, BEFORE (no
  OCR) -> AFTER (live Tesseract)**: scan/all CER 1.0000 -> 0.0878, WER 1.0000 -> 0.1676, bag-of-words F1 0.00 -> 0.87, heading F1 0.00 -> 0.70 (P 0.71, R 0.69), paragraph-boundary F1 0.00 -> 0.47, furniture F1 0.00 -> 0.38 (the text-layer variants of the same documents: CER 0.012, WER 0.021, heading F1 0.99); by family: headings CER 0.024 / heading F1 0.80, furniture CER 0.023 / heading F1 0.96 / paragraph F1 0.81, columns CER 0.081, lists CER 0.068, tables CER 0.105, scripts CER 0.80 (only eng and osd installed). *Left*: the Renderer for the pages that are not a lone JPEG; bold (a bold-only
  heading has its body's size: stroke width is ink, P6.3); non-Latin scripts (only `eng` and `osd` are installed here; the request
  already carries any language); tables and lists on a scan are whatever P4/P5 make of OCR lines; the estimate of an unstated
  resolution (D215); live Go and Rust agree only through a transcript (D211).
  *Learnt*: Tesseract's C API exports no `RowAttributes`, and a symbol's box is sometimes its whole word's height (a third of the
  x-height letters of a body line), so the line size is a low percentile of a letter class's heights, the page's ratio, then
  clustered (D213); `go build` with cgo on this macOS leaves a binary whose linker signature fails its own check (SIGKILL at launch);
  the two hosts' JPEG decoders (D190) move Tesseract's answer (`Chapter1` against `Chapter 1`), so a live comparison of the hosts
  proves nothing and the transcript is the identity; a replay of a slim transcript (no bitmaps) is as exact as a whole one.
  *Commit*: on branch `worktree-agent-afd782ca8bc66f033` (D037).
- [x] P6.5a — The OCR answer extension v2 (D095 open items, D096): `ox`
  version 2 carries the engine's thresholded image as a bitmap answer (a
  `views` row of kind engine-binary, named by the `oruns` row), per-line
  row attributes (x-height, ascenders, descenders), per-block box,
  orientation and writing direction, per-word dictionary/numeric flags and
  point size; `transcript.edn` gains a `:producer` map (`TessVersion()`,
  model hashes); the word-confidence-of-0 finding of P1.12 step 6 is fixed
  in `tess.rs` if it is a reading bug. Re-record `test/ocr/h-keyword/` live
  (Rust only) and fill `xo-*` column 11 and 12 once P6.3 has kin's ink.
  fields are in the extension and answered -1; kin measures them, D213).* Version 2 of `emon.ocr` (14-cell header, 14-cell lines, a
  block table, a word's `psize` and `dflags`, the thresholded image as the answer's bitmap); the evidence takes the blocks' own
  orientation and box, the words' point size and dictionary/numeric flags (so `xo-engine-flags` is filled) and a `views` row of kind
  `engine-binary` named by the run; `transcript.edn` has `:producer` (both hosts write the same bytes; a replay never reads it);
  `test/ocr/h-keyword/` was re-recorded live on Rust (`bb tools/ocr/record.clj`), and `bb ocr` holds its dump on Rust and Go as
  before (199 words, 24 lines, 972 symbols, CER 2.05 per cent). D130 stands: the zero confidences are Tesseract's and are carried verbatim.
  *Commit*: with P6.5.
- [x] **P6.6 — The fusion corpus: degraded and colour scans, watermark/seal/stamp fixtures, the scorer's fusion
  metrics** (SPEC §14.3.1, §14.2, D206, D209). **Wave 1**, no kin: parallel with everything. Depends on nothing new.
  *Brief*:
  1. **New born-digital synthetic documents** (`tools/synth/make_html.clj` + `print.sh`, Chrome, gold by `html2gold`),
     each two pages of ordinary prose, headings and one table so the CER is meaningful: `wm-gray-behind` (CONFIDENTIAL,
     opaque `#d0d0d0`, -45°, 110 pt, `z-index:-1`, crossing body lines on both pages), `wm-image-behind` (a PNG of the word
     SAMPLE at 20 % opacity behind the text: no text-layer witness), `bg-texture` (a full-page light texture image under
     the text), `seal-over-text` (a blue `ring` seal with arc text over the end of a paragraph, and a red rotated PAID
     stamp over a table cell; `data-seal` gold as `stamp-seal` has), `sig-over-name` (an SVG pen-stroke signature crossing
     the typed name above a `Signature` rule; gold `seal` subkind `signature`), `outlined-heading` (two headings drawn as
     SVG `path` outlines made at dev time from a system font by `tools/synth/outline.clj`, so the text layer lacks them;
     the gold has their text).
  2. **Hand-made PDFs** (`tools/synth/wm_pdf.clj`, bytes written by bb like `tagged_pair.clj`, `--check`ed by `bb unit`):
     `wm-marked` (SPECIMEN in `/Artifact <</Subtype /Watermark>>` marked content), `wm-ocg` (the same in an OCG named
     `Watermark` with `/AS` print-only), `wm-annot` (a Watermark annotation), `tl-broken-shift` (a Chrome PDF whose one
     body font's ToUnicode is rewritten so every letter maps to the next letter: plausible garbage, every glyph `how`
     `tounicode`), `tl-broken-unmapped` (the same font's ToUnicode removed: `how` unknown), `hidden-older-ocr` (the
     `scan/h-plain.pdf` pages with a render-mode-3 text layer of the gold text with 5 % of letters substituted by a seeded
     rule: a worse previous OCR). Golds are the source documents' golds.
  3. **Degraded variants** (`tools/synth/degrade.clj`): keep `scan/` as it is (150 dpi gray, the P6.5 baseline); add
     `scan-color/STEM.pdf` for the FUSION SET ONLY (below): 200 dpi RGB, JPEG quality 80, skew from the stem's seed in
     ±3° (the angle written into MANIFEST `facts.skew` per page), light noise; and **overlay** variants `scan-ovl/STEM.pdf`
     of the same set: a light-gray diagonal `COPY` watermark (lightness 200-215), a generated seal (one of six SVG seals:
     rings with arc text, blue/red/purple, rasterised by `magick`) pasted over body text at a seeded box, a pen signature,
     and on two documents bleed-through (the mirrored next page at 10 % darkness); the overlays are added to the gold
     (`dropped` watermark per page, `seal` nodes with `text`, `color`, `subkind`). Two documents also get pages turned 90°
     and 180° (`facts.turns`). The FUSION SET (`test/corpus/synth/FUSION`, one stem a line): `watermark-draft`,
     `stamp-seal`, `figures-logo-screenshot`, `bank-statement`, `invoice`, `columns-two`, `h-numbered`,
     `table-simple-ruled`, `lists-basic`, `scripts-mixed` and every new document of 1 and 2. **Budget**: the new committed
     files ≤ 12 MB in all (measure; if over, lower the dpi of `scan-color` to 150 and say so here).
  4. **Scorer metrics** (`tools/accuracy/score.clj`, D206): `ghost-rate`, `seal-leak`, `src-ocr-share`, `tl-override` as
     SPEC §14.2 defines them (lower is better: add them to the ratchet's direction table beside `cer`); counts at the
     finest level the JSON has (an inline's `src`/`flags` when present, else its block's); `test_score.clj` laws with
     hand-written predictions for each. `bb fusion-report` (`tools/accuracy/fusion_report.clj`, not a gate): reads
     evidence dumps (version "2", §9.7) and prints per-source win rates (`fv` winner over groups with both candidates),
     per-rule counts and per-defence ledger counts, per document and pooled.
  5. **Live sets, never committed** (`tools/fusion/live-sets.edn`): the SROIE derived `*.watermark.png`, `*.stamp.png`,
     `*.ocr-layer.pdf` with their SROIE gold text (`corpus/_gold`); mapo80 `val` (785 images) with the YOLO stamp boxes, for
     the metric `stamp-ghost` (OCR words whose centre lies in a labelled stamp box and that reach the body, over OCR words
     whose centre lies in one), computed from the evidence dump by `bb fusion-live --stamps`. mapo80 is an aggregate of
     unverified licences and its `neg_*` images are research-only: **nothing from it is committed**, it is measured live
     only (D209).
  *Files*: `tools/synth/*`, `tools/accuracy/score.clj`, `tools/accuracy/test_score.clj`, `tools/accuracy/fusion_report.clj`,
  `tools/fusion/` (new), `test/corpus/synth/{html,pdf,scan-color,scan-ovl,gold,FUSION,MANIFEST.json}`,
  `test/scoreboard.edn` (only by `bb accuracy --update`: the new documents enter the `emon` baseline as they are today).
  *Done when*: `bb unit` and `bb accuracy` green with the new documents and variants recorded; `tl-broken-shift` scores
  CER ≥ 0.5 on `emon` (the text layer is wrong, as intended) and every other new `pdf/` document CER ≤ 0.02 except
  `outlined-heading` (its headings are missing: say its CER here); the new metrics have laws; the six seals and the
  overlay boxes are listed in MANIFEST.
  *Done (D206, D209), as written except where said.* **Files**: `tools/synth/{make_html,html2gold,build,degrade,print.sh}` extended;
  new `outline.clj` (+ `outlines.edn`: Chrome and `pdftocairo` turn two heading texts into glyph paths), `assets.clj`
  (`html/img/wm-sample.png`, `bg-texture.jpg`), `wm_pdf.clj` (+ `hidden_ocr_words.edn`), `degrade_fusion.clj`;
  `tools/accuracy/{score,accuracy,fusion_report,test_score}.clj`; `tools/fusion/{live-sets.edn,fusion_live.clj}`;
  `bb fusion-report`, `bb fusion-live` (neither a gate; listed in `not_gates`); schema `facts.tl-untrusted`.
  **Corpus**: 12 new documents (`wm-gray-behind`, `wm-image-behind`, `bg-texture`, `seal-over-text`, `sig-over-name`,
  `outlined-heading`, and the six of item 2: `wm-marked`, `wm-ocg`, `wm-annot`, `tl-broken-shift`, `tl-broken-unmapped`,
  `hidden-older-ocr`), each two pages (`hidden-older-ocr` is `h-plain`'s HTML and scan); 67 documents in all. Every item-2
  file is its source plus ONE appended incremental update, so `wm_pdf.clj check` (in `bb unit`) recovers the source from the file's
  own first `%%EOF` and requires the update to be what the script writes (qpdf finds all six valid). The FUSION set is 22 stems
  (`test/corpus/synth/FUSION`: the brief's ten, with `script-mixed` for the brief's `scripts-mixed`, which does not exist, and
  the 12 new ones). `scan-color/` and `scan-ovl/` of those 22, `gold-ovl/STEM.ovl.gold.json` for the overlay scans (the gold plus
  a `watermark` COPY entry a page and the `seal` nodes: one of six seals, one signature, placed after the paragraph whose printed
  line they cover; facts dropped because ids change), `scan-ovl/FACTS.json` (skews, turns, overlay boxes, seal ids; copied into
  MANIFEST `facts.{skew,skew-ovl,turns,overlays,seal,watermark,bleed}`), checked by `degrade_fusion --check` in `bb unit`.
  **Deviations**: (1) the six seals are drawn by `magick` from a table (ring, inner ring, letters placed round the arc, a
  centre word), not rasterised from SVG: magick's SVG reader has no `textPath`; (2) the 12 new documents have NO plain `scan/`
  (grey, 150 dpi): colour and overlay scans replace it, for the pixel budget; (3) **scan-color and scan-ovl are 130 dpi RGB,
  JPEG 45, noise `attenuate 0.03`** (the brief's 200 dpi / q80 would be about 25 MB; its fall-back to 150 dpi was still
  about 10 MB for the two variants): the committed new pixels measure 3.4 MB (`scan-color`) + 3.7 MB (`scan-ovl`) + 0.7 MB of PDFs +
  0.4 MB of gold-ovl, about 8.2 MB, under the ~10 MB cap of D209; (4) the two turned pages are `table-simple-ruled` page 1
  (90 degrees) and `h-numbered` page 2 (180); bleed-through is on `bank-statement` and `columns-two`.
  **Metrics** (`score.clj`, lower is better, in `LOWER-IS-BETTER`; counts at an inline's own `src`/`flags`, else its block's;
  seal nodes are outside them): `ghost-rate` = OCR-only words (src has `ocr`, no `text`/`hidden-text`) beyond the gold body's count
  of that word, over predicted body words; `seal-leak` = characters of the gold's seal words found in the body OUTSIDE seal nodes
  beyond the gold body's own count, over body characters (absent when the gold has no seal text); `src-ocr-share` (absent when the
  prediction carries no `src` at all, so a prediction that says nothing has no share rather than 0); `tl-override` = chars under
  `text-layer-suspect` over body chars, not counted for the `scan*` variants nor for a gold with `facts.tl-untrusted` (the three
  documents whose text layer is wrong or older ON PURPOSE, where overriding it is right). Hand-computed laws in `test_score.clj`
  (`ghost-rate`, `seal-leak`, `src-ocr-share`, `tl-override`, direction table); the oracle scores 0 on all of them on every
  variant (`bb accuracy --predictor oracle`). `fusion_report.clj --self-test` and `fusion_live.clj --self-test` (stamp-ghost,
  YOLO boxes) are in `bb unit`; **the dump's field names there are guesses from SPEC 9.7** (`cg` `text`/`ocr`, `fv` `group`/`src`/`rule`,
  `fx`, `fa` `defence`/`reason`) that P7.1/P7.3 must reconcile in that one file.
  **Measured on `emon` today (the baseline `bb accuracy --update` recorded; `degraded` and `emon` entries re-recorded, because
  pooled categories changed with the new documents)**: `tl-broken-shift` CER 0.712; `tl-broken-unmapped` 0.885; `hidden-older-ocr`
  0.080 (its 5 % substitutions, by design: the three are the documents where the text layer is wrong); `outlined-heading` 0.0099
  (its two headings are missing); `seal-over-text` 0.030 (a miss of the 0.02 bar: the seal's arc text and the stamp's PAID arrive
  in the text layer's order instead of as `seal` nodes, which is P7.7's, D205); every other new `pdf/` document 0.0000; every
  `scan*` variant 1.0000 (no OCR yet).
- [ ] P6.7 — Initials from ink (SPEC 10.9, D108): the geometry test of P4.10 over non-text ink (a vector path cluster, an
  image placement, a Type 3 glyph without Unicode, the tracer's components on raster pages); the letter from
  `/ActualText` or `/Alt`, then a planned OCR request of pass kind `initial` (`op` row 9, added to `emon.evidence`; psm 10,
  the paragraph's script) on the region of the page's `with-text` render or decode, then the tracer; U+FFFD and `low-conf`
  when nothing passes; the ornament is not a `figure`. **Wave 4**, after P6.4 and P7.4 (it is a targeted round: it rides
  P7.4's `rt-plan` with one more request kind and adds no round). *Files*: `kin/initials.kin` (or wherever P4.10 put the
  geometry test), one `op` row. *Done when*: `initial-image` and `initial-vector` score `initial-r` 1.0 and
  `first-word-acc` 1.0 on `pdf/` and `scan/` under `emon-replay` of their committed transcripts, and the scanned variants of
  every P2.13 initials document have no single-letter heading.
- [ ] **P6.8 — The graphics corpus, rule truth from an oracle, and the graphics metrics** (SPEC §14.2 "Graphics",
  §14.3 "Rule and fill truth"; D227). **Wave 1**, no kin: parallel with everything. Depends on nothing new; if P6.6 has
  not merged, take only new files and rebase onto it for the shared ones (named below).
  *Brief*:
  1. **Born-digital (Chrome) table documents** (entries in `tools/synth/make_html.clj`, gold by `html2gold` as today):
     `table-dashed` (dashed inner rules, solid outer), `table-double` (a double rule under the head), `table-zebra` (bands
     only, no rule), `table-header-fill` (a filled dark header with white text, no rule), `table-white-rules` (white
     column separators on the dark header, dark rules below), `table-slanted-header` (a diagonal rule in the top-left
     header cell). Each two pages: prose, then the table, then prose.
  2. **Hand-made PDFs** (`tools/synth/gfx_pdf.clj`, bytes written by bb as `tagged_pair.clj` does, `--check`ed by `bb
     unit`): `gfx-onepath-grid` (a 5 × 4 grid as ONE path of `m l` pairs, and a 12 × 10 grid as one path of 1,100 points:
     the second is truncated by design and must still be a table through its box and the tracer), `gfx-hidden-rules` (a
     table whose visible rules make 3 × 3 and whose extra rules, clipped away, in an OCG that is off, white on white,
     painted over by a later white rectangle and at alpha 0, would make it 4 × 4), `gfx-shading-bands` (zebra bands as
     axial `sh` shadings), `gfx-pattern-bands` (bands as tiling-pattern fills), `gfx-curve-lines` (a ruled table whose rules
     are `c` curves with collinear control points), `gfx-image-rules` (a ruled table whose rules are 1-pixel-tall images),
     `gfx-softmask-rule` (a ruled table plus one rule under a soft mask that hides it: rendered without it). Golds by hand
     in the generator (the table's HTML), as `tagged_pair.clj`'s are.
  3. **Mixed pages** (`tools/synth/mixed.clj`): `mixed-table-image` (Chrome: prose, then an `<img>` of `table-simple-ruled`'s
     table rendered at 200 dpi by `pdftoppm`, degraded by `degrade.clj`'s noise and a 0.7° skew, 60 % of the page width,
     then prose), `mixed-table-image-shaded` (the same with `table-zebra`'s table), `mixed-two-tables` (one vector table and
     one image table on one page). The gold holds both tables; the rules of an image table are written in the image's
     pixels by the generator and mapped through the placement it chose.
  4. **Scanned twins**: the GRAPHICS set (`test/corpus/synth/GRAPHICS`, one stem a line: every `table-*` document, `invoice`,
     `bank-statement`, every new document of 1-3) gets `scan/` (as today) and `scan-color/` variants by `degrade.clj`.
  5. **Tagged tables** (`tools/synth/tagged_tables.clj`, bytes like `tagged_pair.clj`; MANIFEST `facts.tag-verdict` per
     document): `ttab-good` (ruled; `TH` with `Scope` Column; a `ColSpan` 2 header; a `RowSpan` 2 first-column cell:
     confirmed), `ttab-good-ws` (a whitespace table, no rule, correctly tagged: confirmed), `ttab-bad-spans` (`ColSpan` 3
     where the rules close at 2: partial, `tag-span-conflict`), `ttab-bad-rows` (two visual rows in one `TR`:
     contradicted), `ttab-allp` (cells tagged `P`: no grid, and the whitespace candidate is not vetoed unless trust ≥ 800),
     `ttab-layout` (two columns of prose tagged `Table`/`TR`/`TD`: contradicted or refused as prose), `ttab-th-as-td`
     (headers as `TD`: confirmed, no head from tags, the bold rule still finds it), `ttab-missing-row` (the last row an
     artifact: partial, `cover` low); and an untagged twin of each (`ttab-*-untagged`) for the BEFORE column.
  6. **Rule truth** (`tools/oracle/rules.py`, pdfplumber from `~/.cache/emon/pylib`, D094's exception): per page the
     `lines`, `rects` (edges and fills) and straight `curves`, kept where `pdftoppm -r 150` has ink under them (fills: a
     colour difference from white ≥ 16), written to `test/corpus/synth/gold/STEM.rules.json` (rules: page, endpoints in
     cpt, thickness; fills: box, colour); a scan variant's truth is its twin's mapped through MANIFEST's dpi, `facts.skew`
     and `facts.turns`; a mixed document's image table adds the generator's rules.
  7. **Metrics** (`tools/accuracy/graphics.clj`, new; one section at the END of `tools/accuracy/score.clj` that calls
     it): `rule-p`, `rule-r`, `fill-p`, `fill-r` from the evidence dump's `grl`/`gfl` (version "2") against the rule
     truth, exactly as SPEC §14.2 defines them (higher is better); `twin-gap` (recorded); `table-adj-f1` (ICDAR 2013's
     adjacency relations, from the gold's cells and the Model's: for every non-empty cell its nearest non-empty right and
     down neighbours, a relation (text, text, direction) with text normalized; P/R/F1 over relations); `tag-verdict-acc`
     (the dump's `tb` verdicts against MANIFEST `facts.tag-verdict`); `tag-table-agree` (recorded only, in `bb
     fusion-report`). Laws in `tools/accuracy/test_graphics.clj` with hand-written dumps and golds for each metric.
  **Budget**: new committed files ≤ 6 MB (measure; the mixed documents' images at 200 dpi JPEG quality 80).
  *Files*: `tools/synth/gfx_pdf.clj`, `tools/synth/tagged_tables.clj`, `tools/synth/mixed.clj` (new); entries in
  `tools/synth/make_html.clj` and the `degrade.clj` set list (shared with P6.6: rebase); `tools/oracle/rules.py`;
  `tools/accuracy/graphics.clj`, `tools/accuracy/test_graphics.clj`, the end section of `tools/accuracy/score.clj`;
  `test/corpus/synth/{html,pdf,scan,scan-color,gold,GRAPHICS,MANIFEST.json}`; `test/scoreboard.edn` only by `bb accuracy
  --update`.
  *Done when*: `bb unit` and `bb accuracy` green with the new documents (their `emon` scores recorded as the baseline:
  today `gfx-hidden-rules` is expected 4 × 4, wrong, and that is the point); the rule truth of the 12 existing table
  documents checked by eye on one page each against the render (list them); the metric laws pass; the budget measured.

## Phase 7 — Fusion and artifacts

The maintainer's strategy (D014, D015, D038): for every PDF page and every image, the text layer, OCR and the tracer
run, fusion compares them region by region and keeps the best; watermarks, backgrounds and the ghosts OCR reads in them
are set aside; seals and signatures become placeholders. This phase is now the critical path. It is planned against what
was built after SPEC §8-9 were written (evidence v2, visibility, the tagged tree, furniture, tables, the `Ocr` port,
the `Renderer` port, image intake); D195-D210 record the reconciliation and SPEC §2.2, §7.2-7.3, §8.2-8.3, §9.2-9.8,
§12.3 and §14.2 say it normatively. **Graphics fusion** (the maintainer's request of 2026-10-01: rules, boxes and fills fused from the PDF's
paths, the tracer, OCR's layout blocks and the tagged tree, by the same steps as words) is D218-D228, SPEC §9.9 and §10.5,
and the items P3.6a, P6.8, P5.5a, P5.5 and P7.G placed in the waves below.

**M0 — before any item below (coordinator, not a brief).** Merge `worktree-agent-afd782ca8bc66f033` (P6.1 + P6.5 +
P6.5a: image intake, the `Ocr` port, `emon.scan`, D187-D190 and D211-D216) and `worktree-agent-aac8530d7436e2eec`
(P6.2: the `Renderer` port and pdfium, D191-D194) into main, renumbering their decisions after the highest on main, and
the P4.5/P4.6 and initials branches as they come. Every brief below assumes that main.

**Order and what runs in parallel** (each wave's items touch disjoint files, "Who edits what" below):

| wave | items | needs |
| --- | --- | --- |
| 1 | **P7.U** (page units), **P7.0** (text-layer triage and health), **P6.3** (normalization), **P6.6** (corpus and metrics), **P3.6a** (path visibility and geometry), **P6.8** (graphics corpus, rule truth, metrics) | M0 |
| 2 | **P6.4** (tracer, with D226's graphics amendment), **P7.1** (alignment), **P7.2** (health), **P5.5a** (fused-graphics arenas, the vector source, `emon.table` on `grl`/`gfl`) | P6.3; P7.0; P3.6a (P5.5a's step B) |
| 3 | **P7.3** (arbitration, the PageLayer, the stages wired into the page unit), **P5.5** (tagged tables verified against geometry) | P7.U, P7.1, P7.2, P6.3, P6.4, P5.5a; P5.5: P5.5a, P6.8 |
| 4 | **P7.5** (watermarks, backgrounds, ghosts), **P7.7** (seal and signature placeholders), **P7.6** (`hidden-text`), **P7.4** (targeted rounds), **P7.G** (graphics fusion: tracer and OCR witnesses) | P7.3 (P7.7 and P7.5 also P6.4; P7.G also P6.4, P5.5a, P6.8) |
| 5 | **P7.S** (the policy switch: born-digital pages OCR'd under `always`; `bb fusion` gate; the performance budget) | P7.3, P7.5, P6.6 (P7.G when merged: its contract joins the gate) |
| 6 | P6.7 (initials from ink), P6.3a / P10.3 (acceleration, only if over budget), P5.5b (`form`) | P7.4 |

The critical path is M0 → P7.U → P7.3 → P7.5 → P7.S; P6.3 → P6.4 and P7.0 → P7.1/P7.2 feed P7.3 and are not on it if
they keep pace. **Graphics fusion (D218-D228)** runs beside it: P3.6a and P6.8 (wave 1) → P5.5a (wave 2) → P5.5 (wave 3)
and P7.G (wave 4); P5.5a feeds P7.3 (the document unit's `tb-build` reads `grl`/`gfl`) and must keep pace with it, the
rest is off the critical path.

**Who edits what** (an item edits only its row; a shared file is edited only at the place named):

| file | owner | others |
| --- | --- | --- |
| `kin/norm.kin`, `kin/normtest.kin` | P6.3 | — |
| `kin/trace.kin`, `kin/tracetest.kin` | P6.4 | — |
| `kin/tlhealth.kin`, `kin/player.kin` (+ tests) | P7.0 | P7.3 adds `fv`/`fx` builders below P7.0's in `player.kin`; P5.5a adds ONE block `;; P5.5a graphics arenas` (the `gl-*` calls in `pl-append`/`pl-check`/`pl-emit`) |
| `kin/pageunit.kin` (+ test) | P7.U | P7.3 fills its stage calls (including `gv-page` + `gf-page` before `al-page`); P7.4, P7.5, P7.6, P7.7, P7.S each add ONE call at the hook comment `;; HOOK P7.n` P7.3 leaves; P7.G changes only the arguments of the `gf-page` call |
| `kin/align.kin` / `kin/health.kin` / `kin/arbiter.kin` | P7.1 / P7.2 / P7.3 | — |
| `kin/fuserules.kin` | P7.3 (rows 100-199, the block headers of 100-699, `fr-order` 400, 600, 200, 300, 100) | P7.5 rows 200-299, P7.6 300-399, P7.7 400-499, P7.G 500-599 and 600-699, each inside its own block |
| `kin/glayer.kin`, `kin/gvec.kin` (+ tests) | P5.5a | — |
| `kin/gfuse.kin` (+ test) | P5.5a (the vector-only `gf-page`) | P7.G (everything else in it) |
| `kin/tagtable.kin` (+ test) | P5.5 | — |
| `kin/table.kin` | P5.5a (the collector and `tb-build`'s inputs), then P5.5 (`tb-select`, the struct candidate, `tb-emit`'s provenance) | — (P5.5b after both) |
| `kin/pdfvis.kin`, `kin/pdfcontent.kin` | P3.6a | — |
| `kin/evidence.kin` | P3.6a (the `paths` section and the `pg` enum) | P6.7 (one `op` row) |
| `kin/model.kin`, `schema/emon-1.json` | — | P7.G (the ledger reason `rule-ghost`, flags `in-table-image`, `struct-disagree`, `tag-span-conflict`: additive, D055), P5.5 (the last two if it lands first) |
| `kin/artifact.kin` / `kin/hidden.kin` / `kin/sealmark.kin` / `kin/retarget.kin` / `kin/rasterplan.kin` | P7.5 / P7.6 / P7.7 / P7.4 / P7.S | — |
| `kin/scan.kin` | P7.U (per-page split) | P7.3 (its line measure becomes a library call; `sc-tag` retired) |
| `kin/plan.kin` | P7.U (`KIN_VIEW_BASE`, ids) | P7.4 (`rq-ocr-view`) |
| `host/*/port*` (four trees) | P6.3 (section `P6.3 bulk forms`), P7.U (section `P7.U BitmapSet`), both appended at the end; the second to merge rebases | — |
| `host/rust/src/engine.rs`, `host/go/engine.go` | P7.U | P7.0 (one call, merged before P7.U), P7.3 (the document tail), P7.5 (`pa-run` in the document pass), P7.S (options), P5.5a (`gv-page` + `gf-page` before `tb-build`, until P7.U/P7.3 move them into `pu-run`), P5.5 (the `tb-build` call takes `se sc ss`) |
| `host/rust/src/plan.rs`, `host/go/port/plan.go` | P7.U | P7.4 (fulfilling an OCR of a kin-made view) |
| `tools/synth/`, `tools/accuracy/`, `test/corpus/synth/` | P6.6 | P7.S (scoreboard updates through `bb accuracy --update` only); P6.8 (its new files; entries in `make_html.clj` and the `degrade.clj` set list; the END section of `score.clj`) |
| `tools/oracle/rules.py` | P6.8 | — |
| `tools/fusion/`, `test/fusion/`, `kin/scripts/fusion*` | P6.6 creates `tools/fusion/` | P7.1 (`test/fusion/align/`), P7.3 (`test/fusion/replay/`), P7.S (the gate and the bench), P7.G (`test/fusion/graphics/`, `tools/fusion/record-graphics.clj`, `kin/scripts/gfuse`) |

**Every brief is held to the same conventions.** (1) *Identity*: kin modules generate to Rust, Go, Java and C#; laws in
`emon.test` run on all four (`bb unit`); the Engine wiring is made in BOTH `engine.rs` and `engine.go` at the place D195's
order names, and `bb identity`, `bb embedders` and `bb repeat` stay green; a bulk form is declared in all four host trees
with a law equal to its kin reference (`bb hosts`, D093); anything a live engine produced (renders, OCR) reaches a gate only
through a transcript recorded live on Rust and replayed on Rust and Go to the identical bundle (JSON, HTML, evidence dump)
(D008, D095, D194). (2) *Gates*: `bb gates` green; the `emon` scoreboard (no engine) does not move unless the brief says
it moves and why. (3) *kin's known traps* (the "Found in kin" list): bind every inner read of a `Cells` before an outer
call (Rust `&mut`), unique `let` names per function (Java, C#), no `len`/`cap`/`new`/`go`/`var`/`close` as names,
predicates `^Bool`, no constant named like a function. (4) *Thresholds* are named settings with defaults at the head of
the module (SPEC §9.4); a first value is a first value, P10.1 calibrates. (5) *Measurement first*: an item that changes
what reaches the Model records BEFORE and AFTER for its metrics in its entry, live (`emon-live`) and replayed.

**D195's stage order** (what `attempt()` becomes; the names are the kin entry points):

    document pass (reads the input; no pixels; re-run on byte misses as today):
      pd-open, pc-document                    text layer evidence
      th-run                                  P7.0: epages, xt stage-1 features, visibility triage, tl-health
      px-index-build, pm-run, tg-read, tg-join catalogue, tagged tree, trust
      pa-run                                  P7.5: annotations (Watermark, Stamp)
    page units, one per page, each its own plan/fulfil loop (P7.U), holding only its page's bitmaps:
      rp-plan                                 P7.S (before it: scanned pages as P6.5, born-digital pages ask nothing)
      nm-view, tr-page                        P6.3, P6.4 (only when the page has raster answers)
      sc evidence + nm-feat-*                 OCR evidence (P6.5) and its raster features
      gv-page, gf-page                        P5.5a (vector only, every page) / P7.G (+ tracer, OCR blocks, regions,
                                              xo 30-31): grl gfl ggp grg, graphics hl rows (SPEC 9.9, D218)
      al-page, hl-page, ar-page               P7.1, P7.2, P7.3 (+ P7.5/P7.6/P7.7/P7.G rule blocks)
      rt-plan                                 P7.4 (targeted requests while rounds remain)
      -> the page's PageLayer (fw fl fv fa cg fx hl grl gfl ggp grg sl, SPEC 9.7)
    document unit (no pixels):
      pl-append (pages in order), ar-recur (P7.5 defence 2), then stage 3 on fw/fl/grl/gfl (+ se sc ss for P5.5):
      fu-find, tb-build, hd-analyze, pa-build, fu-write, sm-write (P7.7), fz-write (provenance, ledger; replaces sc-tag)

- [ ] **P7.U — Page units: one plan/fulfil loop per page, `BitmapSet`, an absent `Renderer` answers `unsupported`, five
  rounds** (SPEC §2.2, §7.3, §12.1; D195, D196, D199, D207). **Wave 1, critical path.** Depends on M0.
  *Brief*: restructure both Engines so that stages 1b, 1c and 2 run per page, with NO change to any Model.
  1. **Split `attempt()`** (Rust) and `attempt` (Go) into the *document pass* (everything that reads the input: today's
     `pd-open` … `tg-join`, re-run on byte misses as now, answering the evidence and catalogue arenas) and, for each page
     in order, a *page unit* `pu-run` (`kin/pageunit.kin`, new) with its own plan/fulfil loop (`plan::run` per unit), its
     own `answers`/`rq`, and its own `BitmapSet`; then the *document unit* (stage 3 on the appended PageLayers). A page
     unit reads the document pass's arenas and never the input (a scanned page's `decode` names the input by index, as
     today; the host reads it). The raster path (`attempt_raster`, `rs-unit`) takes the same shape: the document pass is
     the header-only intake (D187), a page unit per frame.
  2. **`pu-run` today** does what the page did before: a born-digital page asks nothing and its PageLayer is its text-layer
     words and lines copied into `fw`/`fl` (P7.0's compaction, if merged, else a copy); a scanned PDF page or an image frame
     asks `decode` then `ocr` (D211's requests, now per page) and its PageLayer is `sc-emit-lines`' lines (D213). `pl-append`
     (in `kin/player.kin`, P7.0's; a stub here if P7.0 is not merged) concatenates pages, re-basing every index.
  3. **Ids** (D207): page `p` (0-based) issues ids `(p + 1) * 256 + k`, `k` < 256 (`k` 1 render with-text, 2 render no-text,
     3 render text-only, 4 decode, 16-127 OCR, 128-255 crops and later kinds); kin-made views are `KIN_VIEW_BASE` (2^24)
     + `p * 256 + k`. Every committed transcript whose ids change is re-recorded (`test/ocr/scan/`, `test/ocr/h-keyword/`,
     `test/render/`: say which, and that their dumps are otherwise unchanged).
  4. **`BitmapSet`** (D196): a port tag in all four host trees (section `P7.U BitmapSet`): `bms-get set id` (a borrow of the
     answer's `Bitmap`, 0×0 when absent), `bms-has set id`, `bms-put set id w h channels` (a host-allocated zeroed bitmap kin
     fills; Rust `&Bitmap` over `Cell<u8>` as D092 found necessary). The Engine fills it from the unit's answers and drops it
     when the unit ends, so peak memory is one page's bitmaps. P6.3's `bitmap-new` allocations in `nm-view` become
     `bms-put` here if P6.3 has merged, else in P7.3.
  5. **No `Renderer` → `unsupported`** (D199, revising D191 (5)): `render` and `crop` without a configured renderer are
     answered `unsupported` and transcribed, as `ocr` is without an engine.
  6. **`max-rounds` 5 per page unit** (D207); `fetch-rounds` stays the document pass's.
  *Inputs/outputs*: the document pass's arenas in; per page `fw fl` (+ the evidence rows the page unit made) out; the
  document unit is today's tail. *Files*: `host/rust/src/engine.rs`, `host/rust/src/plan.rs`, `host/go/engine.go`,
  `host/go/port/plan.go`, the four port trees (section above), `kin/pageunit.kin` (+ `pageunittest.kin`), `kin/scan.kin`
  (`sc-pdf-unit` split into a per-page call), `kin/rasterin.kin` (per frame), `kin/plan.kin` (`KIN_VIEW_BASE`, id helpers).
  *Measured*: nothing may move: every Model and evidence dump of every gate byte-identical to before (`bb slice`, `bb ocr`
  after re-recording, `bb image`, `bb render`, `bb identity`, `bb accuracy`); peak RSS of a conversion of
  `corpus/formats/derived/scanned-receipts-6p.pdf` and of a 40-page scanned PDF from `corpus/scanned/` with live Tesseract,
  before and after (after: one page's bitmaps, under 120 MB); `bb repeat` serial vs. pages in reverse order.
  *Done when*: all of the above; a law that a page unit with no answers asks the same requests on every target; the
  Go and Rust Engines' `RunStats` agree on rounds and requests per page.

- [ ] **P7.0 — The text layer's own triage and health, before OCR** (SPEC §9.3 "without OCR", §9.4 rule 1, §9.7; D203,
  D208). **Wave 1.** Depends on M0. Merge BEFORE P7.U (it adds one call to `attempt()`; P7.U rebases on it).
  *Brief*: `kin/tlhealth.kin` (`emon.tlhealth`) and `kin/player.kin` (`emon.player`).
  1. `player.kin`: the §9.7 layouts (`cg fw fl fv fa fx hl sl`; `fw`/`fl` reuse the `words`/`lines` accessors of
     `emon.evidence`; `fpa` is gone, D218: the graphics arenas `grl gfl ggp grg` are P5.5a's `emon.glayer`, hooked into
     `pl-append`/`pl-check`/`pl-emit` by P5.5a's block), builders, `pl-append` (concatenate a page's arenas onto the document's, re-basing
     `etext` runs, word/line runs, `refs`, glyph runs are NOT re-based: they point into the document pass's `glyphs`),
     `pl-check` (as `ev-check`: every run and reference), `pl-emit` (the dump's version "2" keys after the evidence's, table
     driven like `ev-fd`). Laws: a hand-built two-page layer appends and checks; every cell is printed or named spare.
  2. `th-epages`: an `epages` row for EVERY page (size as displayed, quarter turns, `has-text-layer`), not only scanned ones.
  3. `ev-feat-text` (stage 1 `xt` columns 2-9, 16, 17, 19-21, 28, 29 of SPEC §8.4.5; 18 from the size inventory if P4.2's
     `sf` is at hand, else -1): a `feats` row per visible or hidden text-layer word.
  4. `th-health`: per (font, page) the pooled columns 2-5 → `hl` rows (subject font-page, components mapped, garbage,
     overlap, gap) and a score (the weighted harmonic mean of D150's shape, weights first values), written into each word's
     `xt` 13. Settings `tl-trusted` (700), `auto-health` (500).
  5. `th-triage` (D208): a visible-text word whose glyphs failed a P3.6 check (`vis` ≠ 0) is taken out of the flow and
     written to the ledger (`clipped`, `offpage`, `invisible` for `white-on-white`, `zero-size`, `transparent`,
     `ocg-hidden`), EXCEPT a word `occluded` by an image placement covering at least half the page, which becomes a
     `hidden-text` word (D155: an older producer's OCR layer under its scan). `hidden-text` words stay in the flow (P7.6
     decides about them). The flow is COPIED into `fw`/`fl` without the triaged words (a line loses them and is re-measured
     from its remaining words; an emptied line is dropped), and stage 3 reads `fw`/`fl` from now on: in `attempt()` of both
     hosts, `th-run` after `pc-document`, and `fw`/`fl` passed where `wd`/`ln` were (`fu-find`, `tb-build`, `hd-analyze`,
     `pa-build`); the ledger rows are written after `fu-write` by `th-write`.
  *Files*: `kin/tlhealth.kin`, `kin/player.kin` (+ tests), the two Engines (the calls above only).
  *Measured*: `bb accuracy` `emon` BEFORE and AFTER on every category (expected: unchanged on the Chrome documents; any
  change is explained word by word in this entry); the P3.6 fixtures (`test/vis/`) give one ledger row per hidden glyph
  class; the corpus by eye on the D157 files with white and occluded text (the brochure, the patched form): their hidden
  text is in the ledger, not the body. `tl-health` on the 55 `pdf/` documents: every font ≥ `tl-trusted`; on P6.6's
  `tl-broken-unmapped`: the broken font < 300 (`tl-broken-shift` is NOT caught here: its mapping is plausible; that is
  what OCR agreement in P7.2 is for, and this entry says so).
  *Done when*: the above, and `pl-check` answers 0 on the layers of every gate document.

- [ ] **P7.1 — Alignment and correspondence groups** (SPEC §9.2 "As designed", D202). **Wave 2.** Depends on P7.0
  (`player.kin`, `fw`); not on P7.U (it is tested through a probe).
  *Brief*: `kin/align.kin` (`emon.align`), `al-page`: one page's candidates (the visible text-layer words of `words` with
  their `vis` and `src`; `hidden-text` words; every `owords` run of the page; `tlines`) → `cg` rows with their `refs` runs,
  similarities in both forms, the flags (text-only, OCR-only, split, merged), and `xt`/`xo` columns 24 and 25. The grid
  index, the per-line dynamic program and its tie-breaks exactly as SPEC §9.2 says; settings `align-overlap` 500,
  `align-angle` 300, `align-gap` 400. OCR words of several runs on the same page (round 3) align to the same groups (a group
  holds every run's candidates).
  **Fixtures**: (a) laws on hand-built heads: 1:1, a split (`Chapter 1` / `Chapter1`), a merge, a missing word each way,
  a 30° line, a right-to-left line (logical order on both sides), two OCR runs over one line; (b) `test/fusion/align/`:
  six documents of the fusion set (`h-numbered`, `columns-two`, `table-simple-ruled`, `watermark-draft`, `stamp-seal`,
  `tl-broken-shift`) rendered at 300 dpi `with-text` by pdfium and OCR'd by Tesseract, recorded live by
  `tools/fusion/record-align.clj` (the render and OCR drivers of P6.2/P6.5, no Engine), committed whole; `kin/alignprobe.kin`
  builds the text layer from the PDF and the OCR evidence from the transcript (the `emon.scan` builders) and aligns; its
  dump is held on Rust and Go (`bb align`, a gate).
  *Measured*: **coverage** (visible text-layer words in a group with an OCR candidate) ≥ 0.99 on the five healthy
  documents; **precision** (1:1 groups with case-folded similarity ≥ 800) ≥ 0.98; splits and merges counted; on
  `tl-broken-shift` ≥ 90 % of the shifted font's words grouped (geometry) with similarity < 300; ≤ 10 ms a page (Rust).
  *Files*: `kin/align.kin`, `kin/aligntest.kin`, `kin/alignprobe.kin`, `kin/scripts/align`, `tools/fusion/record-align.clj`,
  `test/fusion/align/`.
  *Done when*: the laws on four targets, `bb align` green on Rust and Go, the measured numbers in this entry.

- [ ] **P7.2 — Health per source and region** (SPEC §9.3 "As designed", D203). **Wave 2.** Depends on P7.0; uses P7.1's
  `cg` and P6.3's ink columns when present (laws build them by hand, so it can start with P7.1).
  *Brief*: `kin/health.kin` (`emon.health`), `hl-page`: (1) the text layer per (font, page): P7.0's components plus the
  **agreement rate** (groups with both candidates whose case-folded similarity ≥ `agree-sim` 900, over groups with both;
  -1 with no OCR), and a document-wide pass `hl-doc` that marks a font unhealthy on every page when its agreement is under
  `agree-min` (600) on every page that has ≥ `agree-words` (8) groups; (2) each OCR block (`oblocks` kind block): its words'
  ink support (`xo` 10, 11, 12), plausibility (8), script consistency (9), body-ink share (13), artifact and seal overlap
  (22, 23) pooled → a score; (3) `xt` 13 rewritten with the agreement-aware score. Every component a cell of the `hl` row.
  *Files*: `kin/health.kin`, `kin/healthtest.kin`.
  *Measured* (on `test/fusion/align/` through the probe, and live on the fusion set): every font of the healthy documents
  ≥ `tl-trusted`; the shifted font of `tl-broken-shift` and the unmapped font of `tl-broken-unmapped` < 300 on every page;
  the OCR blocks over the watermark, seal and texture areas of `scan-ovl` score below every body block of the same page on
  ≥ 95 % of pages (live).
  *Done when*: laws (a healthy font; a shifted font that agrees on nothing; a font that is bad on one page only and stays
  healthy elsewhere; an OCR block over blank paper) on four targets and the numbers above in this entry.

- [ ] **P7.3 — Arbitration, the PageLayer, and the stages wired into the page unit** (SPEC §9.4 with the born-digital
  contract, §9.7; D014, D195, D201, D203). **Wave 3, critical path.** Depends on P7.U, P7.1, P7.2, P6.3, P6.4, P5.5a (`grl`/`gfl` and the
  vector-only `gf-page`).
  *Brief*:
  1. `kin/fuserules.kin`: the `fr` rule table (`defdata`: number, block, winner, ledger reason, flags, up to six
     `(column, comparison, setting)` predicates) with the block headers 100-199 (core), 200-299 (P7.5), 300-399 (P7.6),
     400-499 (P7.7), 500-599 (graphics, P7.G), 600-699 (graphics meeting words, P7.G), the settings' defaults, and
     `fr-order`, the order in which blocks are tried for a group: 400 (seal ownership), 600 (rule ghosts, text of a table in
     an image: empty until P7.G), 200 (watermarks, backgrounds, ghosts), 300 (`hidden-text`), 100 (core); 500 runs on
     graphics groups, not on word groups (D224). Rows 100-199 implement SPEC §9.4's table and the
     born-digital contract: 101 one candidate (the identity: a sole text-layer candidate wins, a sole OCR candidate on a
     page with no text layer wins), 110 healthy font wins (`tl-trusted`), 120 garbage glyphs + plausible OCR → OCR with
     `text-layer-suspect`, 130 unhealthy font + plausible OCR → OCR with `text-layer-suspect`, 140 OCR-only admitted
     (body ink, outside every visible text-layer box, plausible, not `in-figure` unless the image covers ≥ `scan-region`),
     150 OCR-only in a figure → held with `in-figure` (not emitted), 160 two OCR runs → better (support and margins), 190
     the default (the text layer if any, else OCR, `low-conf`). Rule 140's "vector-figure cluster" is a `grg` `figure`
     region (empty until P7.G: until then an image placement alone). D218: the old row 501 (tracer rules into `fpa`) is
     NOT built here; graphics are P5.5a's `gf-page` (vector only) and P7.G's rows 500-699.
  2. `kin/arbiter.kin` (`emon.arbiter`), `ar-page`: per group the first rule whose predicates all hold; writes `fw` (the
     winner's word, its text into `etext`, `src` the bitset of every source that had a candidate, the glyph run kept when
     the text layer won), `fv` (group, winner, rule, confidence: 1000 for a text-layer win with agreement, 900 without OCR
     witness, the scorecard-less first values for OCR, flags), `fx` (the rule's columns and the values read), `fa` (a
     ledger row for a rule that sets one); lines `fl`: a text-layer line keeps its geometry; a line of OCR-won words is
     measured by D213's measure (the `emon.scan` line-size function, made a library call); an OCR-only group joins the
     text-layer line it lies on (baseline within 45 % of the size) or makes a new line.
  3. **Wiring** in `pu-run`: when the page has raster answers, `nm-view` per raster (P6.3; kin-made views by `bms-put`),
     `tr-page` on the `no-text` render or the decode (P6.4), OCR evidence and `nm-feat-*`, then `gv-page` + `gf-page`
     (P5.5a's, on EVERY page, raster answers or not: moved here from where P5.5a put them in `attempt()`), then `al-page`,
     `hl-page`, `ar-page`; the hook comments `;; HOOK P7.4` … `;; HOOK P7.S` left in place. The document unit: `pl-append`,
     stage 3 on `fw`/`fl`/`grl`/`gfl` (`tb-build` reads `grl gfl`, P5.5a), and `fz-write` writes provenance from `fv` onto the Model's blocks and
     inlines (`src`, `conf`, `ocr-only`, `text-layer-suspect`, `low-conf`) and the `fa` ledger rows; `sc-tag` retired
     (its page mask is what `fz-write` computes per word). Born-digital pages are not yet OCR'd by default (P7.S); a hidden
     test setting `ocr-born-digital` (off) makes the page unit ask what P7.S will ask (the `full` row of SPEC §8.3), so
     this item can measure born-digital fusion before the switch.
  4. Dump version "2" (`pl-emit` after `ev-emit`).
  5. **Transcripts**: `test/fusion/replay/`: the fusion set's `pdf/` variants with `ocr-born-digital` on and its
     `scan-color/` and `scan-ovl/` variants, recorded live on Rust with pdfium and Tesseract (`tools/fusion/record.clj`),
     whole (every render and decode bitmap: kin reads pixels now, D196); replayed by `bb fusion-replay` on Rust and Go.
     Budget ≤ 25 MB committed (measure the PNGs first; take first pages only where needed and list them; the maintainer's
     open question on committed pixels may move this to a fetched archive).
  *Files*: `kin/fuserules.kin`, `kin/arbiter.kin` (+ tests), `kin/pageunit.kin` (the stage calls), `kin/player.kin`
  (`fv`/`fx` builders), `kin/scan.kin` (line measure as a library function; `sc-tag` removed), the two Engines (document
  tail), `tools/fusion/record.clj`, `test/fusion/replay/`, `kin/scripts/fusion-replay`.
  *Measured* (BEFORE = P7.0's main; AFTER = this): **G1, identity of one source**: with no engine, every gate's Model and
  JSON byte-identical to BEFORE (the 55 `pdf/`, `pdf-tagged/`, `bb slice`, `bb ocr` replays): fusion over one candidate is
  the identity. **Scans** (`emon-live`): `scan/all` CER ≤ 0.0878 (P6.5's) and heading F1 ≥ 0.70; `scan-color/all` and
  `scan-ovl/all` recorded as their baseline. **Born-digital with `ocr-born-digital`** (`emon-live`, all 55 `pdf/` and the
  new P6.6 documents): `pdf/all` CER ≤ `emon`'s (0.0097 at the time of writing; re-read the scoreboard) and no category
  worse by more than 0.001; `tl-override` 0 on every document but `tl-broken-*`; `tl-broken-shift` and `tl-broken-unmapped`
  CER ≤ 0.03; `outlined-heading` CER ≤ 0.01; `ghost-rate` ≤ 0.002 (P7.5 brings it down); `bb fusion-report` per-source win
  rates and per-rule counts recorded in this entry. Fusion's kin time ≤ 30 ms a page (Rust).
  *Done when*: all of the above, `bb fusion-replay` green on Rust and Go and in `bb gates`.

- [ ] **P7.4 — Targeted re-OCR rounds** (SPEC §8.3 targeted passes, §7.3 kin-made views; D196, D207). **Wave 4.**
  Depends on P7.3. Parallel with P7.5, P7.6, P7.7.
  *Brief*: `kin/retarget.kin` (`emon.retarget`), `rt-plan` at `;; HOOK P7.4`, run when the page has its first-pass OCR
  and rounds remain; it asks, in this order and within `rt-max` (24) requests a page: (1) `op-line`, `psm` 7, on the
  `with-text` render or decode around each line of a group that P7.3 decided with `low-conf` or `text-layer-suspect`
  (≤ `rt-lines` 16); (2) `op-cell`, `psm` 7, per table cell (from `tb`'s geometry or the lattices of `grl` rules, including `in-image` ones) when a first-pass OCR
  line crosses a cell border; (3) `op-text-only`: OCR of the `text-only` render (layer 2, a new render request in the same
  round) of a page whose text layer has an unhealthy font: the broken text re-read with no background; (4) `op-body-ink`:
  OCR of a **kin-made view** (D196: the render or decode with every non-body ink layer painted the paper colour, made by
  `bitmap-lut-into` into `bms-put`) when a `light`/`colour`/`background` layer holds ≥ `rt-layer-min` (5 %) of the page's ink
  and first-pass OCR words overlap it; (5) `op-re-rotated`: OCR of a quarter-turned kin-made view (`bitmap-quarter-into`)
  when P6.3/P6.4's orientation is not 0. `kin/plan.kin` gains `rq-ocr-view` (the bitmap id ≥ `KIN_VIEW_BASE` followed by
  its `bitmap-hash` in two cells); the hosts fulfil it from the `BitmapSet`'s kin-made entry, and the transcript keys it by
  the record (the hash) and never stores the pixels. The new runs join alignment (P7.1 groups all runs) and rule 160.
  *Files*: `kin/retarget.kin` (+ test), `kin/plan.kin` (`rq-ocr-view`), `host/rust/src/plan.rs`, `host/go/port/plan.go`
  (fulfil a kin view), the hook line, re-recorded `test/fusion/replay/`.
  *Measured* (`emon-live` and replay): `scan/all`, `scan-color/all`, `scan-ovl/all` CER at least 10 % lower (relative)
  than after P7.3 + P7.5; `watermark-leak` on `scan-ovl` lower than P7.5's; the turned pages read as well as their
  upright twins (CER within 0.005); targeted requests a page median ≤ 12, OCR time ≤ 1.5× the first pass
  (`bb fusion-bench`); a replay of a kin-made-view request with one changed pixel is `transcript-miss` (law).
  *Done when*: the above in this entry; rounds never exceed 5.

- [ ] **P7.5 — Watermarks, backgrounds, bleed-through, ghosts: the six defences** (SPEC §9.5 "Against what is built",
  D015, D204). **Wave 4, critical path.** Depends on P7.3 and P6.4.
  *Brief*: `kin/artifact.kin` (`emon.artifact`) and rows 200-299 of `fr`: (1) the PDF says so: `mk-artifact-of`
  watermark/background marks, `ocg` spans off by default or `/AS`-hidden or named like a watermark (`watermark`,
  `draft`, `wasserzeichen`, … a short committed list, case-folded), alpha < `wm-alpha` (500) on text or on images covering
  ≥ `wm-cover` (40 %) of the page, Watermark and Stamp annotations (`emon.pdfannot`'s `pa-run`, wired into the document
  pass by this item: it was never called), the tagged tree's verified Watermark artifacts weighed by `tg-trust`; the
  classified text-layer words AND the OCR words aligned only to them go to `fa` as `watermark` (or `background`); (2)
  recurrence: `ar-recur` in the document unit, after `pl-append` and before `fu-find`: OCR-only groups with the same
  case-folded text within `wm-recur-pos` (2 % of the page) of the same place on ≥ `wm-recur-pages` (60 %, at least 3) of
  the pages, outside the furniture bands, go to the ledger as `watermark` on every page, and the furniture stage's
  diagonal rule stays as it is; (3) ink layers: column 13 < `wm-body-share` (300) with its ink on a `light`/`colour` layer,
  and (with P7.4) absence from the `op-body-ink` run; (4) geometry: column 19 > `wm-angle` (1500 centi-degrees) with a
  `tlines` row on a non-body layer, or column 18 > `wm-size` (2500); (5) seal overlap is P7.7's block (400s), which `fr-order` tries before this one; (6) plausibility and ink: `ocr-ghost` for an OCR-only word
  with column 10 < `ghost-ink` (50) or (11 high and 10 low), or with column 8 > `ghost-implausible` and column 5 <
  `ghost-margin`, and no text-layer support; bleed-through: an OCR-only word on a `light` layer whose components are
  mirrored (the tracer's `tlines` direction is reversed) or whose engine confidence and margins are low → `bleed-through`.
  **Defences 3, 4 and 6 judge OCR-only groups only**: a visible text-layer word of a healthy font is removed only by
  defence 1 (the PDF says so), defence 2 (recurrence) or the furniture stage, never by its colour, angle or ink (D201).
  *Files*: `kin/artifact.kin` (+ test), `kin/fuserules.kin` rows 200-299, the hook, the Engines'
  document pass (`pa-run`).
  *Measured* (`emon-live`, replay, and the live sets): `watermark-leak` 0 on every `pdf/` document including `wm-*`;
  ≤ 0.002 on `scan/` and `scan-color/`, ≤ 0.005 on `scan-ovl/`; `ghost-rate` ≤ 0.002 on every variant; SROIE
  `*.watermark.png`: no watermark word in the body; `bow-r` on `pdf/all` and `scan/all` not lower than P7.3's (no body word
  lost: a removed word the gold has is listed in this entry); mapo80 `stamp-ghost` recorded (first target ≤ 0.2, P7.7 lowers
  it); the per-defence ledger counts from `bb fusion-report`.
  *Done when*: the above; every ledger row of the fusion set names its defence in the dump.

- [ ] **P7.6 — `hidden-text` (prior OCR layers)** (SPEC §9.4's hidden row, D155, D208). **Wave 4.** Depends on P7.3.
  *Brief*: `kin/hidden.kin` and rows 300-399: `hidden-text` words (and P7.0's `occluded`-under-a-scan words) are
  candidates of their groups; (301) with no OCR on the page, `hidden-text` is the text, `src` hidden-text, confidence 600 (as
  before P7.0, now explicit); (310) hidden and OCR agree (case-folded similarity ≥ `agree-sim`) → the hidden text's code
  points (it may come from a better engine), `src` both, confidence up; (320) they disagree → this run's OCR, unless the OCR
  word's margins (column 5) are under `hid-margin` and the hidden word's box has better kin-ink agreement (its glyph
  cells' ink coverage on the decode against the OCR word's), then the hidden word, flagged `low-conf`; (330) a hidden word
  with no OCR counterpart and ink under it → kept, `low-conf`; without ink → ledger `invisible`.
  *Files*: `kin/hidden.kin` (+ test), rows 300-399, the hook.
  *Measured*: `hidden-older-ocr` (P6.6) CER ≤ 0.02 and below both the hidden-only and the OCR-only CER; the SROIE
  `*.ocr-layer.pdf` live: CER against SROIE's gold ≤ the lower of the two sources'; the D157 corpus files with Tr 3 layers by
  eye.
  *Done when*: the above in this entry.

- [ ] **P7.7 — Seal, stamp and signature placeholders before P8** (SPEC §9.5 defence 5, §10.7; D016, D205). **Wave 4.**
  Depends on P7.3 and P6.4. P8.2/P8.3 later fill name, date and arc text; this item puts the node where it belongs and
  keeps its text out of the body.
  *Brief*: `kin/sealmark.kin` (`emon.sealmark`) and rows 400-499. Candidates (`sl`): a P6.4 `ring`, `circle` or
  `ellipse` (radius ≥ `seal-min` 8 mm) on any layer, a `rect` with a border ≥ 1.5 pt on a `colour` layer, a cluster of
  `colour`-layer components ≥ `stamp-min-ink` (kind `seal` or `stamp`); a **signature**: stroke-like components (median
  stroke width ≤ 1.2 pt, run length variance high, aspect and density in the named bounds, not text-like by the tracer, OCR
  plausibility low or no OCR) within 3 line heights of a cue (a text line whose words include `signature`, `signed`,
  `sign here`, `by:`, case-folded, or a rule with a short label under it). Rows 4xx: an OCR word whose centre is inside a
  candidate becomes the candidate's text (`refs` run), never body; a text-layer word inside a seal candidate whose glyphs
  are on its arc or ring (rotation varying along the ring: the `stamp-seal` arc text) likewise; ordinary body text
  under a translucent stamp stays body (its glyphs are visible and its font healthy: rule 110 has already won it). The
  document unit writes a `seal` node per candidate (`subkind` seal, stamp or signature; `color` from the ink centroid by a
  12-hue name table; `text` the words in reading order, flagged `low-conf`) inserted in reading order at its top by the
  P4.5 copying mechanism (`kin/restruct.kin`'s, not by changing `pa-build`'s signature); `name`/`date` are P8.2's.
  *Files*: `kin/sealmark.kin` (+ test), rows 400-499, the hook, `sm-write` in the document unit.
  *Measured*: `block-seal` P/R ≥ 0.9 on `stamp-seal`, `seal-over-text`, `sig-over-name` (`pdf/` and `scan-ovl/`); `seal-leak`
  0 on `pdf/`, ≤ 0.005 on scans; `pdf/seals` CER ≤ 0.10 (0.318 now); seal `color` accuracy ≥ 0.9; mapo80 live: candidate
  recall ≥ P6.4's baseline and `stamp-ghost` ≤ 0.1.
  *Done when*: the above in this entry.

- [ ] **P7.G — Graphics fusion: the tracer and OCR as witnesses of rules and fills; health per region; regions;
  where graphics meet words** (SPEC §9.9, §9.3, §9.7, §8.4.5 columns 30-31; D218, D221-D224, D228). **Wave 4.** Depends
  on P7.3 (`fuserules.kin`, the `gf-page` call in `pu-run`), P6.4 with D226's amendment (rules from runs, tints), P5.5a
  (`emon.glayer`, `emon.gvec`, the vector-only `gf-page`) and P6.8 (fixtures, rule truth, metrics). Parallel with P7.4,
  P7.5, P7.6, P7.7.
  *Brief* (`kin/gfuse.kin`, extending P5.5a's `gf-page`; nothing in `gvec.kin` changes unless a law shows a vector bug):
  1. **Candidates of the other sources**: the page's `rules` rows (trace; their `style`, `confidence`, colour), `prims`
     rows of kind `rect` with flag `tint` (trace fills) and, as four `box-edge` rules, `rect` prims that are not filled and
     not `tint`; `oblocks` rows of kind block with `bt-line` (OCR line separators: an `h` or `v` candidate along the long
     axis of the block's box) and `bt-table` / `bt-image` (region candidates only). All in page space (`rules` endpoints
     are already cpt; `prims` boxes too).
  2. **Alignment** exactly as SPEC §9.9 (D221): a per-page 64 × 64 grid over candidate boxes (the `emon.align` grid
     helper if P7.1 exported it, else a copy named `gf-grid-*`), near pairs by `gf-angle` 150, `gf-perp` 120 + half the
     thicknesses, `gf-gap` 300; groups by union-find over candidate indices (smaller root wins); per group the axis, the
     projected endpoints sorted (ties by source then index), the elementary intervals with their coverage bitset, the
     runs of equal verdict; `ggp` rows with their three `refs` runs (vector members as `(paths, i)` + `(pts, k)` pairs).
     Fills: near at box IoU ≥ `gf-fill-iou` 700, groups the same way.
  3. **Health** (D223): the regions R0 and Rᵢ (visible `images` rows ≥ `gf-img-min` 2 cm each way; a raster page is one
     region); per region an `hl` row of subject kind `graphics-region` (subject index: -1 for R0, the `images` row for Rᵢ;
     add the `hl` subject-kind code in `emon.glayer`, not in P7.0's file) with the six components of SPEC §9.7 and the
     score of D150's shape (weights: vector witness 3, tracer precision 3, skew 2, tracer confidence 2; counts are not
     weighed).
  4. **Arbitration**: rows 501-571 of SPEC §9.9's table into `kin/fuserules.kin`'s block 500 as data (the `gmz` measure
     codes ≥ 100 named in `gfuse.kin`: `gmz-vec-exact`, `gmz-vec-uncertain`, `gmz-vec-hidden`, `gmz-trace`, `gmz-trace-conf`,
     `gmz-ocr-line`, `gmz-in-image`, `gmz-raster`, `gmz-region-score`, `gmz-vec-witness`, `gmz-tprec`, `gmz-in-glyph`,
     `gmz-in-ocr-word`, `gmz-contrast`, `gmz-fill-colour-known`, `gmz-tint`, and as many more as the rows need); P5.5a's
     coded rows are removed from `gfuse.kin` in favour of the data; `fx` rows name the `ggp` group; the settings
     `gf-axis` 100, `gf-angle` 150, `gf-perp` 120, `gf-gap` 300, `gf-fill-iou` 700, `gf-img-min` 5669 (2 cm), `gf-agree-min`
     900, `gf-trace-min` 600, `gf-ocr-bonus` 150, `gf-img-health` 500, `gf-tprec-min` 900, `gf-contrast` 16, `gf-flat` 50 at
     the head of `fuserules.kin`'s block 500.
  5. **Regions** (D228): `grg` rows `table-in-image` (the lattice test of D179 without its text test over the region's
     `in-image` `h`/`v` rules, or three `in-image` fills of one extent stacked), `figure` (SPEC §9.9's sources merged at IoU
     ≥ 500; the tagged `Figure` owner of a path or image through `mark` → `sc` → `se`, the index built once a document like
     P5.5's), `table-hint` (OCR `bt-table` blocks, confidence 300; tagged `Table` boxes, confidence `tg-trust`).
  6. **Words**: `xo` columns 30 (share of the box in a `table-in-image` region) and 31 (rule-likeness, SPEC §8.4.5) for every
     OCR word of the page, written before `al-page` runs; rows 601 (`rule-ghost` to the ledger: add `dr-rule-ghost` to
     `kin/model.kin`'s `dr` enum, schema additive) and 610 (`in-table-image`: a word flag, additive) in block 600 of
     `fuserules.kin`; the `gf-page` call in `pu-run` gets its new arguments.
  7. **Transcripts and the gate**: `test/fusion/graphics/`: first pages of the GRAPHICS set's `pdf/` (with
     `ocr-born-digital`), `scan-color/` and the three `mixed-*` documents, and `gfx-softmask-rule`, recorded live on Rust
     with pdfium and Tesseract by `tools/fusion/record-graphics.clj` (≤ 3 MB: the coordinator's pixel budget, SPEC §17 (6));
     `bb gfuse` (`kin/scripts/gfuse`, joins `bb gates` after `fusion-replay`) replays them on Rust and Go to the identical
     bundle and ratchets `rule-p`, `rule-r`, `fill-p`, `fill-r`, `table-teds-lite` under a predictor `emon-gfuse`.
  *Files*: `kin/gfuse.kin`, `kin/gfusetest.kin`, `kin/glayer.kin` (the `hl` subject code only), `kin/fuserules.kin` (blocks
  500 and 600), `kin/pageunit.kin` (the `gf-page` call's arguments), `kin/model.kin` + `schema/emon-1.json` (additive),
  `tools/fusion/record-graphics.clj`, `test/fusion/graphics/`, `kin/scripts/gfuse`, `kin/scripts/gates` (one line).
  *Measured* (BEFORE = P7.3 + P5.5a, i.e. vector only; AFTER = this; `emon-live` and replay; P6.8's metrics):
  **born-digital** (`pdf/`, `ocr-born-digital` on): `rule-p` ≥ 0.995 and `rule-r` ≥ 0.99 against the pdfplumber truth;
  every table of every `pdf/` document without images byte-identical to BEFORE (the graphics contract, D222) except
  `gfx-softmask-rule` (its hidden rule gone: say so) and the `gfx-pattern-bands` tints (their colour); **scans**
  (`scan/`, `scan-color/`): `rule-r` ≥ 0.95, `rule-p` ≥ 0.95, `fill-r` ≥ 0.85, and `table-teds-lite` on the GRAPHICS set
  recorded BEFORE and AFTER with `twin-gap` per document (target: the median `twin-gap` ≤ 0.10); **mixed**:
  `mixed-table-image` and `mixed-two-tables` image tables found (`block-table` R 1.0) with `table-teds-lite` ≥ 0.85, their
  OCR words in the cells (`in-table-image`), not in a figure's labels; **rule ghosts**: no body `|` on the scan variants of
  the ruled documents whose gold has none (count BEFORE and AFTER); **ICDAR 2013** live: `table-adj-f1`, `block-table`
  P/R and TEDS-lite against D182's numbers; `bb fusion-report`'s per-rule counts for block 500/600; `gf-page` ≤ 5 ms a
  page (Rust).
  *Done when*: laws on four targets (a vector rule witnessed; a vector rule under a soft mask not witnessed, dropped
  only when the region's witness ≥ 900; a Chrome border drawn cell by cell against one tracer line: one group, the vector
  pieces kept as they are, no gap bridged; an open edge of a vector table stays open even where the tracer saw ink across
  it (row 520); a tracer
  rule inside a glyph box dropped; a `|` OCR word on a vertical rule to the ledger; a lattice in an image a
  `table-in-image` region); `bb gfuse` green in `bb gates`; the numbers above in this entry.

- [ ] **P7.S — The switch: born-digital pages OCR'd under `always`; the `bb fusion` gate; the cost** (SPEC §8.3 first
  pass, §9.8, §12.3; D038, D212 (3) superseded, D200, D201, D210). **Wave 5, the end of the critical path.** Depends on
  P7.3, P7.5, P6.6 (P7.4, P7.6, P7.7 are not required for the switch and land when ready).
  *Brief*:
  1. `kin/rasterplan.kin` (`emon.rasterplan`), `rp-plan` at `;; HOOK P7.S`: the first-pass requests of SPEC §8.3's table
     for each page kind, policy and scope: `always` + `full` (the default) asks `with-text` and `no-text` renders at 300
     dpi in round 1 and OCR of the whole `with-text` render in round 2 (languages: the locale's, else the text layer's
     scripts through `emon.mscript` mapped to the installed models by the adapter, else every model); `always` + `witness`
     asks the `no-text` OCR and the witness lines; `auto` asks only when `tl-health` < `auto-health`, images cover ≥
     `auto-image` (30 %), or the page has `hidden-text`; `never` nothing. The tracer reads the `no-text` render. The
     hidden test setting of P7.3 is removed. `Options::ocr_scope` / `Options.OcrScope` (`full` | `witness`) in both
     Engines, `--ocr-scope=` on both drivers, `settings.ocr-scope` in the Model when not `full` (schema additive, D055).
  2. **The gate `bb fusion`** (`kin/scripts/fusion`, joins `bb gates` after `ocr`): replays `test/fusion/replay/`
     (re-recorded with the switch on) on Rust and Go, requires the identical bundle, `transcript-miss` for a changed request,
     and the ratchet of a new predictor `emon-fusion` over those documents; plus **the born-digital contract per document**:
     each healthy `pdf/` document's CER under `emon-fusion` ≤ its `emon` CER + 0.0005, `tl-override` = 0; and, when P7.G
     has merged, **the graphics contract per document** (D222): every table of a `pdf/` document without images is the one
     `emon` gives (`table-teds-lite` equal, cell for cell). `bb ink` and `bb trace` (P6.3, P6.4) switch to the transcripts'
     render bitmaps and join the gates with their targets as ratchets.
  3. **`bb fusion-live`** (not a gate): `emon-live` with the switch on over every `pdf/`, `pdf-tagged/`, `scan/`,
     `scan-color/`, `scan-ovl/` document and the live sets; recorded in the scoreboard (`emon-live`).
  4. **`bb fusion-bench`** (not a gate): per stage wall time and peak memory a page (`RunStats` gains per-kind fulfil time
     in both hosts) on the fusion set, `full` against `witness`, and the accuracy of both: the table goes in this entry and
     the maintainer decides the default scope (D200).
  *Files*: `kin/rasterplan.kin` (+ test), the hook, the Engines' options, `kin/scripts/fusion`, `kin/scripts/gates`,
  `tools/fusion/{live,bench}.clj`, `tools/accuracy/predict-emon-fusion.sh`, `test/fusion/replay/` (re-recorded),
  `test/scoreboard.edn` (by `--update`), `schema/emon-1.json` (additive).
  *Measured — the born-digital non-regression contract (D201)*: under `emon-live` with the switch on, `pdf/all` CER ≤
  `emon`'s `pdf/all` CER, every `pdf/` category within 0.001 of `emon`'s, `pdf-tagged/all` likewise; `tl-override` 0 on
  every document but `tl-broken-*`; `ghost-rate` ≤ 0.001 and `watermark-leak` 0 on `pdf/`; the gains recorded:
  `tl-broken-*` and `outlined-heading` (P7.3's numbers held), `pdf/figures` and `pdf/seals`. *The cost*: SPEC §12.3's
  budget table filled in; `witness` ≤ 30 % of `full`'s OCR time on the median born-digital page.
  *Done when*: `bb fusion` green in `bb gates`; the contract above holds; D200's comparison table is in this entry.

## Phase 8 — Figures, seals, signatures

- [ ] P8.1 — Figure classification and placeholders (SPEC §10.6, D020). Its candidate regions are P7.G's `grg` `figure`
  rows and its graphics evidence the fused `grl`/`gfl` inside them (D228).
- [ ] P8.2 — Seals and stamps: detection, polar unwarp, targeted OCR, name
  and date extraction (D016). Refines P7.7's `seal` nodes (D205): the polar strip is a kin-made view OCR'd as
  `op-seal-strip` through D196's mechanism.
- [ ] P8.3 — Handwritten signatures and initials; digital signatures (from
  P3.8) into `seal` nodes. Refines P7.7's signature candidates (D205).
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

### D134 — The paragraph stage: what it reads, what it makes, and a page milestone inside a paragraph is an inline (P1.8; D050-D053, D108, D112)
**Decision**: `emon.para` reads the text layer's `words`, `lines` and `etext` and nothing else (no glyph, no stream order, no fonts
row), and APPENDS to a Model the caller made (`model-init`, a `page-new` per page: `pa-pages` does it from the document layer's page
table, the crop box AS DISPLAYED, `/Rotate` turning width and height). `pa-build wd ln et npages nodes attrs regions text ord raw tmp
fr gp`: the last five are scratch (a Rust `Cells` cannot be shared). Every block is a `paragraph` under the root: the stage knows
no heading, list, table or furniture (P4, P5), so a heading is a paragraph here and the check script says so. A paragraph on one page
has its own `text` run; one that goes on across a page has `text`, `break`, `text` inline children (each text with its own region); the
paragraph's `at` is a LIST, one region per page (D108; one per column fragment after P4.1), and its `text` is derived (D052). `src` of a
block is {text, infer} and `conf` 500 (its structure is inferred: Phase 10 calibrates), of a text inline {text} and 1000 (a glyph's own
text), of a milestone {infer} and 500 (it has no geometry). Page 1 has no milestone, every other page has one, an EMPTY page too.
**THE MODEL CHANGE**: SPEC 3.2 and 5.1 say `break` is a milestone "inline or between blocks", but `nk-is-inline` is a function of the
KIND and a `break` was a block: inside a paragraph the JSON wrote `inlines:[text, text]` and `children:[break]`, the order lost, and the
HTML would put the milestone after the paragraph. `node-is-inline nodes n` (additive, in `model.kin`) is 1 for an inline kind and for a
`break` with an inline neighbour (the sibling before or after); `nk-is-inline` of the kind is unchanged (a law still says a break is not
an inline), and `json.kin` and `html.kin` ask the node (11 call sites; `h-milestone` is the one writer of the span). An inline milestone
is written in `inlines`, in its place, with no `id` (D052); `schema/emon-1.json` allows `break` (with `page`, `label`) in `inline`;
`json-check.py` and `html-check.py` (the independent references) learnt the same and wrote `test/json/corners` and its HTML goldens again
(a paragraph of text, break, text, before the furniture, so the furniture's id is 86 now); `modeltest` has the law.
**Why**: a milestone that cannot say where in the paragraph it is is not a milestone; the neighbour rule needs no kind list and keeps a
break between blocks what it was.
**Alternatives**: the milestone as a child of the paragraph written after its inlines (loses the place); two paragraphs and a block
milestone (D112 says one paragraph); a kind-level change of `nk-is-inline` (every break between blocks would become an inline and lose
its `id`).
- [ ] **Signed off** (maintainer)

### D135 — Where a paragraph ends: the line gap is the lower quartile, an indent counts from the margin, style and size, not the font
**Decision**: a page's lines are sorted into reading order (D136), and line b follows line a in the same paragraph unless: b is not
BELOW a (the distance between their baselines is less than b's height: a table row, two columns), or does not overlap it across the
page; or the gap between a's box and b's (`y0 b - y1 a`) is more than 3/2 of the document's line gap AND more than an eighth of b's
height beyond it; or b starts further in than a by more than half its size while a starts at the margin; or the dominant style (bold,
italic, mono, ...) or the size (one twentieth) changes; or either is not upright or they read in other directions. **The line gap of
the document is the LOWER QUARTILE (element `n/4` of the sorted gaps) of every such gap**, pooled over the pages, not the median the
brief says: of `stamp-seal`'s seven gaps four are between blocks and the median is one of them (857 against a line gap of 401), and a
short document is what most files are; the quartile is the line gap whenever a quarter of the gaps are inside paragraphs. The "eighth
of the height" keeps a document set solid (boxes overlap, gap zero or negative, 1.5 times that is nothing) from breaking at noise.
**An indent is a break only from the margin** (the least start of the page): a centred or right-aligned paragraph has lines that start
further in than the one before, and is one paragraph (the plain rule cut `align-paragraphs` into pieces at every last line; the start is
`x0`, or minus `x1` for a line that runs right to left). A LESS indented line never breaks (a first-line indent, a hanging indent).
**Style and size, not the font**: Skia gives one face several font rows (a subset per 256 glyphs), so two lines of one paragraph have
different font ids (`script-cjk`: 15 pieces from 4 paragraphs); a size within 5 per cent is the same size. All thresholds are
`defconst`s of `kin/para.kin`. The dominant word of a line is the one whose (font, size) the most words of the line share.
**Why**: each was found on the 55 documents with the predictor of D139 and the count of exact paragraphs.
**Alternatives**: the median (the brief); the baseline pitch against its median (Chrome's `<p>` margin of 0.7 em makes a paragraph pitch
of 1.48 times the line pitch, under 1.5); an indent against the previous line only (splits every centred paragraph); the font id.
- [ ] **Signed off** (maintainer)

### D136 — Reading order from position; lines that are not upright stand alone, after the page's upright ones
**Decision**: a page's lines are ordered by the sum of their baseline's two y values, then `x0`, then the evidence row, with every
UPRIGHT line (within one degree of the page's x axis) before every other (`pa-before`, a total order, by a Shell sort in a `Cells`):
the order they were painted in is never read, so a drop cap or any float painted first does not become the first line of its paragraph
(D108 (a); a law gives the lines in the wrong order). A line that is not upright is a paragraph of its own (one per line), written
AFTER the paragraph that is open when its page's lines end, wherever that ends: queued in `gp`, made by `pa-flush-all` at the next
flush. Rotated text in the middle of a page (a `DRAFT` watermark, the letters of a seal's circle) no longer cuts the paragraph it lies
across, nor ends the paragraph that goes on to the next page. Rotated and vertical text proper is the layout stage's and P5's.
**Why**: sorted by y alone, `watermark-draft`'s DRAFT sat between two lines of a paragraph and cut it; closing the open paragraph at
the rotated lines would have cut the continuation of every watermarked page.
**Alternatives**: the rotated lines in the order of their baseline (the cut above); dropping them (they are for P3.5 and P5 to judge).
- [ ] **Signed off** (maintainer)

### D137 — A paragraph across a page's end: a long last line that closes no sentence, a first line not indented past the margin
**Decision**: the paragraph open at the end of page p goes on with page p+1's first upright line when the line before it is long (at
least 3/5 of its page's longest upright line), the text so far does not end in a sentence mark (`. ! ? : ;`, the ellipsis, the
full-width forms, a closing bracket or quote after one allowed), both lines are upright and read the same way, and the new line does
not start further in than half its size past the margin of ITS page. The joint is then a line end for the hyphen rule (SPEC 3.6, D112)
and the paragraph is `text`, `break`, `text` (D134). Otherwise the paragraph is closed and the milestone goes between the blocks. A page
with no upright line closes it. A running header is not known here (P5): `furn-odd-even` joins `Handbook for New Staff` to the paragraph
it interrupts (it is the page's first line), and `furn-fixed-dom` does not continue at all; taking furniture out BEFORE paragraphs is
the furniture stage's.
**Why**: the first line after a page break has no gap to measure; what can be seen is that the sentence did not end and that the line
before ran to the margin.
**Alternatives**: continuing whenever the next page does not start indented (joins every list item and table cell that ends a page); a
tagged tree (P3.7, later).
- [ ] **Signed off** (maintainer)

### D138 — The text of a paragraph: spaces, the line-end hyphen, NFC
**Decision**: words of a line are joined by one space, lines by one space, except that after a line that ends in `-` the next line,
when it starts with a lowercase letter (Basic Latin, Latin-1, Greek, Cyrillic: `pa-lower`), is joined with NO space and the hyphen is
kept (SPEC 3.6: no locale, no dehyphenation; `oppor-` + `tunities` is `oppor-tunities`, which the scorer's gold reads as printed, D114),
and after a line that ends in a soft hyphen U+00AD that character is dropped and nothing is put between. A hyphen before a capital is
a dash and a space follows. The text of each block (or each text inline) is NFC with the seven Latin ligatures decomposed, through
`nz-nfc` (flag 1); soft hyphens inside a line stay, because SPEC 3.6 removes them "at line ends". Words with an empty text run are
skipped. The text is built in a scratch `Cells` (`raw`) and written to the Model's text arena once.
**Why**: each case is a law (`test-line-ends-join-per-spec-3-6`); `align-justify-hyphen`'s lines are the real case.
**Alternatives**: removing every U+00AD; joining after any hyphen (`Smith-` `Jones`); lowercase of Latin only.
- [ ] **Signed off** (maintainer)

### D139 — How P1.8 was measured, and the largest errors it leaves (the temporary predictor)
**Decision**: `tools/accuracy/naive/main.rs` (one Rust `main`: open, interpret, paragraphs, JSON) and `tools/accuracy/predict-naive.sh`
(builds it into `.cache/` from `gen/rust`; for the scan variant it answers an empty document so a run over every variant completes) are
TEMPORARY: P1.10 replaces them with the Engine and `predict-emon.sh`, and `test/scoreboard.edn` has no `naive` or `emon` entry (D078).
**Over the 55 synthetic documents, pdf variant**: CER 0.1848, WER 0.2423, paragraph-boundary P 0.0694 R 0.6287 F1 0.1250,
paragraph-block P 0.0689 R 0.7240. By family, CER: headings 0.038, furniture 0.023, footnotes 0.012, tables 0.008, lists 0.046,
initials 0.069, scripts 0.167, align 0.336, **columns 0.598**. Precision is low because every heading, cell, list item and caption is a
paragraph here: 4,033 predicted against 384 gold paragraphs (more than 2,000 of them cells of tables and statements). Of the 384 gold
paragraphs 245 come out exactly, and `kin/scripts/para-check` holds the pure single-column documents to their HTML. Of the 139 that do
not, by cause: columns interleave 94 (P4.1: the single-column assumption of the brief, including `align-justify-hyphen`, which is
two-column), drop caps 22 (P4.10), a footnote mark or note in the way 9 (P4.6), a running header 3 (P5), a `<br>` block the gold keeps as
one paragraph 3 (`invoice`, `stamp-seal`: P4), a paragraph set right-to-left but aligned left 1 (`align-rtl`: P4.11), and 7 that are
the TEXT's, not the stage's: **(1) CJK text from Skia comes out in Kangxi radical forms** (U+2F57 for U+5341, the block U+2F00-2FD5),
which NFC does not fold and SPEC 3.6 does not let NFKC fold: `script-cjk` and `initial-cjk` CER 0.18 and 0.11 are almost all this (a
fold of the radical block to the unified ideographs would be an amendment of SPEC 3.6, the maintainer's to make); **(2) `script-hebrew`:
`2026,` comes out `,2026`** (D104's right-to-left reordering moves a neutral that follows a number run); **(3) Devanagari conjuncts that
Skia gave no ToUnicode** (D105 counts 37 characters). All three are the text layer's (P1.7). The paragraph is the first place whole
sentences are compared with the source, which is why they showed here.
**Why**: these are the numbers P1.10 and P4 have to beat; the predictor is deliberately not an Engine API.
**Alternatives**: updating the scoreboard now (D078: P1.10's); a hand-picked subset.
- [ ] **Signed off** (maintainer)

### D140 — Predefined CJK CMaps and the Adobe CID-to-Unicode tables: fetched at a pinned commit, checksummed, stored as differences, read through `emon.cjk` (P3.4; D084)
**Decision**: `kin/scripts/cjkfetch.clj` (babashka) fetches, from `adobe-type-tools/cmap-resources` at ONE pinned commit
(`f5cf3bc`, BSD-3-Clause), every CMap of the four collections (Adobe-Japan1-7, GB1-6, CNS1-7, Korea1-2) except the UTF-8 and
UTF-32 ones, each collection's `cid2code.txt`, and four pdf.js files (below, D141) into `.cache/cjk/` (ignored: about 10 MB);
`test/fonts/cjk-sources.edn` (committed) records the commit and every file's size and SHA-256, and a file that does not match is
an error. `kin/scripts/cjkdata.clj` makes `kin/cmapjp.kin`, `cmapgb.kin`, `cmapcns.kin`, `cmapkr.kin` (one module per
collection, so no Java class needs more than 65535 constants): per CMap a row (name, base, WMode, codespace and range
spans), codespace ranges, ranges `[first code, last - first, CID]` (a four-byte code is the signed 32-bit number the content
interpreter builds), and the collection's CID -> Unicode `[first CID, last - first, first code point]`. 112 CMaps in
89,000 ranges instead of 353,000: a CMap is stored as its DIFFERENCES from a base CMap of the collection (a range whose CID is
-1 says "not mapped here, whatever the base says"): `usecmap` (the vertical CMaps), and where two CMaps are nearly one
(`UniJIS-UCS2-H` against `UniJIS-UTF16-H`, the GBK family) the nearest of six by size; chains are at most four deep and the
generator checks that every CMap resolves to exactly what its FILE says. CID -> Unicode takes the first of `cid2code.txt`'s
UTF-32, UCS-2, UTF-16 columns that has a value, and within it the candidate that is not a vertical variant and is not
decomposable in UnicodeData (CID 1 is U+0020, not U+00A0; a CID drawn by U+2F00 and U+4E00 is U+4E00). `emon.cjk` is the four
modules behind one set of questions (`cjk-find-name`, `cjk-cid`, `cjk-uni`). In `emon.pdffont`: an `/Encoding` that names one of
them gives the font its codespace, WMode and CIDs (so `/W` widths are by the right CID), and the collection; a `/CIDSystemInfo`
`/Ordering` of Japan1, GB1, CNS1 or Korea1 (an embedded CMap and Identity-H included) selects the CID -> Unicode table, answered
with `how` = `cid`; ToUnicode still wins, and a `Uni*-UCS2/UTF16` name still means the code IS Unicode (a surrogate pair is one
four-byte code now). `test/fonts/cjk.txt` (7,190 rows, `cjkdata.clj`, from the CMaps as their FILES say, not the compressed
tables) is held on all four targets; `tools/oracle/cjk-check.py` holds the same rows to pdfminer.six's CMaps (4,395 agree; the
151 that do not are pdfminer answering a code the CMap does not map, a `usecmap` CMap it does not resolve (`ETenms-B5`,
the `-V` CMaps) and CIDs newer than its copy). `bb unit` regenerates and compares (`cjkfetch` first; without the network
the checks that need the sources SKIP, loudly).
**Why**: a CJK PDF without a ToUnicode says what its text is only through these tables; widths by CID need the CMap even when
the code is Unicode. Fetching with a checksum rather than vendoring keeps 10 MB of Adobe's files out of git while making
the generated tables a function of exactly those bytes.
**Alternatives**: vendoring the files (10 MB); whole CMaps (353,000 ranges, 11 MB of generated source per target and a Java
class near its constant-pool limit); UCS2 treated as UTF-16 (their CIDs differ for a few thousand codes, and a CID is a
width); an inverse-CMap CID -> Unicode (pdfminer's: it picks U+00A6 for the CID that U+007C also names).
- [ ] **Signed off** (maintainer)

### D141 — Embedded font programs give a symbolic font's built-in encoding; MacExpert and `/W2` are read; a code nothing explains is `unknown` with NO value when the program names its glyphs; "no mapping at all" is a warning bit (P3.4; D080, D084)
**Decision**: `kin/fontprog.kin` (emon's own reader, D039, from the Type 1, CFF TN 5176 and OpenType specifications; no
read can fault: every byte is `fp-u8`, 0 past either end, every loop is bounded) answers, for a simple font whose PDF names no
encoding, what each of the 256 codes is in the font program: **Type 1** (`FontFile`): the clear-text `/Encoding` (the array,
`8#101` radix numbers, or `StandardEncoding`; the encrypted part is never read); **CFF** (`FontFile3`, or the `CFF ` table of
an OpenType file): predefined Standard or Expert, or format 0 or 1 with supplements, glyph names through the charset
(ISOAdobe, Expert, ExpertSubset, formats 0, 1, 2) and the String INDEX; **TrueType/OpenType glyf**: ISO 32000-1 9.6.6.4's
symbolic rule (the (3,0) cmap at the code and +0xF000/F100/F200, else (1,0)), the glyph then named by the Unicode cmap read
BACKWARDS (formats 0, 4, 6, 12; the smallest code point), else by its `post` name (formats 1 and 2). It is asked only when
nothing names the encoding (`/Encoding`, `/BaseEncoding`, Symbol, ZapfDingbats), for any Type 1/CFF font, and for a TrueType font
only when it is flagged symbolic; its answer replaces the base encoding, `/Differences` still win, ToUnicode still wins. The
answer is `encoding` (a table or a Unicode cmap) or `glyphname` (a name). A font program that NAMES its glyphs and gives a code
no Unicode name (`G12`, `a30`: Afenegus, Type 3-like Type 1 fonts) has said what the code is not: the Standard-encoding GUESS is
withdrawn (`unknown`, no value, where P1.6 gave a guess pdfminer.six also gave and nobody could trust); a TrueType program
with no names leaves the guess, flagged. The glyph tables are `kin/fontextra.kin` (`kin/scripts/fontextra.clj`: the 391 CFF
standard strings, the Expert charsets and encoding, MacExpert, the 258 Macintosh glyph names, taken from pdf.js at a pinned
commit through `cjkfetch`, turned into rows of the AGL table, and checked against the AGL and against the Standard encoding
emon derives from the AFMs). **MacExpertEncoding** is read as TEXT: the AGL gives the expert set's small capitals,
old-style figures, superiors and accents code points of Adobe's private use area (`Asmall` is U+F761), which no reader can
use, so `fold-expert` makes them the letters, digits and accents they are variants of (`Asmall` is `a`: code 0x61 is Asmall;
`oneoldstyle` `1`; `rupiah` `Rp`). **`/W2`** is read (`pfn-vertical-metric f code which`: the vertical advance, then the
position vector, in the unit of widths, with `/DW2` and half the width as defaults); `pdfcontent` does not use it yet. **Type 3**
fonts: a glyph name is a name only where it is a text name; `a1`..`a206` are ZapfDingbats' own and `g4`, `c12` name drawings, so
they are `unknown` with no value (pdfminer.six reads `a1` as a dingbat). New warning bits: 1024 **no mapping at all** (no code of
a simple font has a value that is not a guess; a composite font with no ToUnicode, no Unicode CMap and no Adobe ordering: this
is OCR's problem, flagged, P5) and 2048 the font program was unreadable; 256 (symbolic guess) is cleared when a program that
names glyphs knew the encoding; 1 (predefined CMap unsupported) and 8 (MacExpert) now mean only an unknown name.
**Why**: the font program is where a symbolic font's built-in encoding lives (D084), and what it says beats a guess: on MathType's
CFF fonts the guess read `(` as `.` and `ω` as `!` (poppler agrees with the program). A guess that is not a reading must not
be passed on as one.
**Alternatives**: always keeping the guess (it is the half of the old `unknown` that pdfminer.six agreed with, and wrong
for every font whose names are not the standard ones); CIDToGIDMap and a reverse cmap for composite TrueType fonts (the
glyph-to-Unicode table would have to live in the font's `Cells` for the whole document; not done, below); the AGL's private-use
values for MacExpert (unreadable text).
- [ ] **Signed off** (maintainer)

### D142 — CJK radicals are folded to the ideographs they are drawn as, in the text layer, as an `emon.normalize` flag; the evidence keeps what the font said (P3.4; D029, SPEC 3.6)
**Decision**: Chrome/Skia PDFs give CJK glyphs a ToUnicode of the Kangxi Radicals (U+2F00..2FD5) and CJK Radicals
Supplement (U+2E80..2EF3) code points where the text is the unified ideograph (U+2F00 is drawn as U+4E00); NFC leaves them (their
mapping is a compatibility one). `kin/scripts/radicals.clj` (babashka) makes `kin/radicals.kin` and `test/normalize/radicals.txt`
from the vendored UCD files (Unicode 18.0.0, `CJKRadicals.txt` added beside `UnicodeData.txt`): the 214 Kangxi radicals and the
two supplement characters (U+2E9F, U+2EF3) that UnicodeData gives a `<compat>` decomposition, and the 29 supplement characters
CJKRadicals.txt pairs with a unified ideograph (the two agree where both speak, or the script fails): 244 in all.
`emon.normalize` gains `nz-flag-radicals` (4; the flags are bits now, they were `rem 2` and `>= 2`) and `nz-radical-fold`;
a caller that wants SPEC 3.6's text passes 7. The glyph rows (`how` `tounicode`) keep the code point the font said, which is
the "original" for anyone who needs it: nothing is added to the evidence. Measured on the corpus: `ja-wikipedia-tokyo.pdf`
(Chrome) shows 438 radical-block characters of 5,957 (7.4%); the table folds 422; the other 16 are U+2EA0, U+2EC4, U+2ED1, which
NamesList.txt cross-references ("x 6C11", "x 897F", "x 9577") but UnicodeData and CJKRadicals do not pair.
**Why**: a search for the text, and a CER against the real text, are wrong by the whole of every such character otherwise
(`script-cjk` was 0.18 in P1.8's measurement). **Alternatives**: folding in `pfn-map` (the evidence would no longer say what the
font said); NFKC (full-width forms and superscripts are content, D029); also folding the NamesList cross-references (the
three above, two more candidates each for 2E81, 2E8E, 2EAB: "see also" is not equivalence; the maintainer may extend the
table, one line each).
**Wired (merge of P3.4 onto P1.8)**: `emon.para` called `nz-nfc` with flag 1 only; it now passes 1+4, so the Model's paragraph text is radical-folded while the glyph rows keep the font's code point. Soft hyphens (2) stay out: `para` handles line ends itself (D138).
- [ ] **Signed off** (maintainer)

### D143 — P3.4 validation, and what is still out (P3.4; D084)
**Decision**: validated with `kin/scripts/font-sweep` and `font-corpus.py` against pdfminer.six on 1,288 corpus files (6,993
fonts, 8.69M shown characters; the harness now reads whole strings for composite fonts with predefined CMaps and splits them
by emon's own code lengths): agreement of shown characters 99.92% before and after (8,687,773 and 8,687,311 characters), of
distinct codes 99.75% before, 99.87% after; `how` = `unknown` 130,202 shown characters (1.50%) before, 34,723 (0.40%) after, and
what remains is nothing a program or a table says (Afenegus `G12` names, a Hebrew Type 3 font of `a1`.., symbolic TrueType
subsets with no names, ArialUnicodeMS, non-embedded Identity-H with no ToUnicode); the confident answers (`how` not
`unknown`) agree 99.99% before, 99.98% after. The characters the two disagree on now are pdfminer's: MathType's CFF fonts
(`(` read as `.`; poppler agrees with emon), Symbol-like fonts (`dln_00327`: the font's own `second`), `a1` read as a dingbat,
a guess where emon says unknown. CJK documents (12 files: the multilingual UDHR, Chrome and Acrobat Japan1, GB1, CNS1, Korea1,
UCS2 CMap files): 99.94% before, 99.97% after, `unknown` 7.76% before and 7.73% after (what remains is `jpn.pdf` and
`chn.pdf`'s symbolic ArialUnicodeMS digits and punctuation: glyph ids with no names, flagged); the predefined-CMap fonts the
sample lacks are covered by the fixtures. `bb unit` holds 7,190 CMap rows, 120 program-font rows (`test/fonts/progs.pdf`,
`progs.txt`, built by `kin/scripts/font-prog-fixtures.clj` so every expectation is known by construction: Type 1, CFF in three
encoding shapes, an OpenType wrapper, five TrueType shapes, MacExpert, Type 3, six predefined CMaps from the Adobe FILES, an
Identity-H font with an Adobe ordering, vertical metrics, ToUnicode over a table), the truncation of each program at every
byte, the names of `fontprog` against `names.txt`'s 4,000 AGL rows, and the 244 radicals. `fonts.txt` and `real.txt` were
regenerated (the expectations that changed are the ones this decision says: predefined CMaps and MacExpert are read, a font
with nothing to say is flagged 1024), and two rows of `test/content/real.txt` whose digests changed (MathType's `(`, a
Symbol-like font) were dropped from the ratchet. STILL OUT: `/CIDToGIDMap` and a glyph-to-Unicode table for composite
TrueType/CFF fonts with no ToUnicode (the 5,433 shown characters of `1149465.pdf` are not embedded, so nothing says);
Type 1 charstring names inside the encrypted part (the encoding is not there, but a font that gives names only there is not read);
`pdfcontent` using `pfn-vertical-metric` (P3.5 owns that file); the `how` code for a font program is `encoding`/`glyphname`
(a new `gh` would change the evidence schema).
**Why**: the oracle's agreement cannot be the target where the oracle guesses; the numbers say so and the cases are named.
**Alternatives**: scoring against poppler's text (needs P1.7's layout to align); keeping `fonts.txt`'s old rows (they
asserted that a predefined CMap is unsupported).
- [ ] **Signed off** (maintainer)

### D144 — The Rust Engine API, as built: what it takes, what it gives, and what the slice refuses instead of approximating (P1.10; SPEC 13, 3.9; D038, D040, D058, D117)
**Decision**: the crate is `emon` (assembled by `kin/scripts/emon-rs-build` from `gen/rust/emon/` and `host/rust/src/`, below); the
Engine is `host/rust/src/engine.rs`, **not `emon.rs`**: the generated module tree is `crate::emon::*` (its call sites say so) and a
file `emon.rs` is the script's list of those modules. `Engine::new(Config)`; `intake(inputs)` (stage 0: format, version, page count,
encrypted, bytes: any number of inputs); `convert(inputs, &Options) -> Model`; `convert_stats` (and what the loop did: `RunStats`
attempts, fetch rounds, source reads, bytes fetched); `record(inputs, &Options) -> (Model, Transcript)` and `replay(inputs, &Options,
&Transcript)` (SPEC 13). `inputs` is `Input`, `Vec<Input>`, `Vec<u8>` or `&[u8]`. `Options { locale: Option<String>, ocr: OcrPolicy,
assets: Assets, keep_evidence }`; `Output::{Json(JsonOptions{pretty}), Html(HtmlOptions)}`, `HtmlOptions` = the `ho-*` bits of
`kin/html.kin` as named booleans plus `confidence_below` (clean HTML by default, SPEC 5.2); `Model::{to_json, to_html, serialize}`.
**The Model** is the seven arenas behind a `Mutex` (kin's Rust read parameters are `&mut Cells`, so a `&self` accessor needs a lock; a
`Model` is `Send + Sync`), and **the walkable accessor is SPEC 3.9's**: `root()`, `Node` = (Model, index) with `kind() -> Kind`
(`Kind::name()`/`Kind::named()` ask kin's own `nk` table: no second table to drift), `id`, `conf`, `sources`, `flags`, `parent`,
`children`/`inlines`/`blocks`, `text()` (the node's run, else its inline children's as the JSON's derived `text`, D052),
`regions()` (centipoints), `attr(name) -> Attr::{Int,Text,Node,Bool}`; `pages()`, `furniture()`, `dropped()`, `find(kind)` (arena
order, which `model-check` makes document order), `source()` (the intake), `evidence()`. Errors are one enum with a stable `name()`:
`password-required`, `password-incorrect`, `encryption-unsupported` (D040: distinct from each other and from damage), `unsupported-format`
(no `%PDF-`: an image or junk), `no-startxref`/`bad-xref`/`no-root`/`no-pages` (kin's own statuses), `source-changed`, `io`, the named
limits (`input-bytes`, `pages`, `objects`, ..., `max-rounds`, `fetch-rounds`), `unsupported`, `no-input`, `internal` (the Model failed
`model-check`, or kin panicked for a reason that is not a limit: a bug, never the document's).
**WHAT THE SLICE REFUSES OR LEAVES UNSAID, in the open**: (1) **OCR is not wired.** `Options::ocr` is accepted and kept (D038: the
default is `always`) and changes nothing: no Renderer, ImageDecode or Ocr port is configured, a page without a text layer comes out with
no text, and **the Model's header writes no `ocr` setting**, because it would claim a run that did not happen; the loop's port fulfiller
answers a request with a named `fulfil-failed` (the slice issues none, D089). The scanned variants of the corpus are therefore empty
Models and score CER 1.0 (D145). (2) **`assets` other than `none` is `Error::Unsupported`** (D117: specified, not implemented), not
ignored. (3) **Several inputs as one document are `Error::Unsupported`**: `pa-build` pools its line gap over the document and numbers
pages from 1, and a merge of two inputs' evidence is not written; `intake` takes any number, and so the slice's loop already has one
`Src`, cache and password per input. (4) Images and TIFF are `unsupported-format` *[revised by D187: a PNG, JPEG or TIFF is read, one `image` page per frame;
what is neither a PDF nor one of those is still `unsupported-format`]*. (5) The header's `source` (format, version, pages,
encrypted) and `meta` are not filled yet (the intake has them: `Model::source()`); no golden has them. (6) `furniture` has no `keep`
value in HTML: kin has one bit, drop (D133); `keep` and `policy` are the same until P5.
**THE UNIT**: `attempt()` is the one place the Engine calls the text-layer stages (`pd_open_pw`, `pd_pages`, `pc_document`, `pa_pages`,
`pa_build`, `model_check`): everything for one input, from scratch, on a fresh `Src` view and fresh arenas per attempt, a miss checked
after each stage (`Step::Miss`); a panic in an attempt that missed is a miss, never reported (SPEC 13.1), and one that did not is
named (`port::limit_breach`'s message is parsed back into `Error::Limit`). **P3.5-P3.7 will change `pc_document`'s signature; this
function is where the call is.** `Config.limits` installs SPEC 7.4's bounds for one conversion through a per-thread override table in
`port.rs` (`with_limits`, consulted by `limit_of` before `EMON_LIMIT_*`; kin still reads a bound only through `limit-of`).
**Why**: the API shape is SPEC 13's, spelled the Rust way; the refusals are what "accuracy is paramount" asks of a slice that does not do
those things yet.
**Alternatives**: `emon.rs` for the engine (collides with the generated tree); a wrapper over `Rc<RefCell<..>>` (not `Send`); a
`Model` that borrows the arenas without a lock (kin's read parameters are `&mut`); concatenating two inputs' Models (page numbers and
the pooled line gap would be wrong, silently).
- [ ] **Signed off** (maintainer)

### D145 — `bb accuracy` is now a gate, its first baseline is the Rust Engine's, and the numbers are what P4 and P5 must beat (resolves D078)
**Decision**: `tools/accuracy/predict-emon.sh` runs the Engine's driver (`emon-rs`, built by `kin/scripts/emon-rs-build` into `.cache/`,
rebuilt when `gen/`, `host/rust/` or the driver changes, by a cksum stamp so 160 documents do not each ask cargo; no `cargo` on PATH is
exit 3, "not available", loudly). **Every variant goes through the Engine**, the scanned ones too: with no OCR wired their Models have
pages and no text (CER 1.0, WER 1.0), and a document the Engine REFUSES fails the whole run (exit 4 reaches `bb accuracy` as FAIL;
exit 3 is for a missing toolchain only). `bb accuracy --update` recorded `emon` in `test/scoreboard.edn` (1,046 metrics, 32 categories;
the oracle and degraded fixtures untouched). **The baseline over the 55 synthetic documents**, `pdf/all`: CER 0.1848, WER 0.2423,
bag-of-words F1 0.9791 (P 0.9659, R 0.9927), paragraph-boundary P/R/F1 0.0694/0.6287/0.1250, block-paragraph P/R/F1 0.0689/0.7240/
0.1259, order tau 1.0, page-label accuracy 0.7065, watermark leak 0.0020; **identical to D139's** (the same stages: the Engine's JSON
equals the temporary predictor's, which `bb slice` now holds to `test/para`). `pdf-tagged/all`: CER 0.0225, WER 0.0979, bag-of-words
F1 0.9469. `scan/all`: CER 1.0, WER 1.0 (no OCR). Every metric of a stage that does not exist (headings, lists, tables, figures,
seals, furniture, notes, initials, alignment, joins) is recorded as 0.0: that is a floor, it cannot regress, and `--update` raises it
when P4/P5 land. **`accuracy` joins `bb gates`** (it follows `slice`, the last gate): it was excluded because there was no pipeline to
bind (D078); there is one now, and the ratchet is what keeps P4, P5 and P3.x honest about text (a regression in CER beyond 0.002
absolute, or a vanished metric, fails the gate). **Cost**: about 30 seconds warm (160 documents, each a process start of a few
milliseconds, plus the scorer) and a one-time cargo build of the crate (about a minute cold); the cheapest-first order puts it last.
The scoreboard header says so; `placeholder-is-not-a-failure` in `test_score.clj` became `unavailable-predictor-is-not-a-failure` over
a fake exit-3 command (the real predictor on a bogus path would now rightly fail).
**Why**: D023's ratchet means nothing until a real pipeline is on it, and a gate nobody runs is not a gate (D818 of nome). A baseline
this low is the point: it is recorded so that it can only move toward better.
**Alternatives**: leaving `accuracy` out of the gates (nothing would stop a text regression from a P3 stage; the slice's identity
checks do not score); gating only `pdf/all` (the scan rows are the OCR work's floor); recording the scan variant as unavailable (it
would drop the metrics from the ratchet exactly when P2.x makes them move).
- [ ] **Signed off** (maintainer)

### D146 — Byte sources, the chunk cache, and the simulated async source live in the library (P1.10; D058, D074, D075, SPEC 13.1)
**Decision**: `host/rust/src/source.rs` has `trait ByteSource { len() -> u64; read_at(off, buf) -> io::Result<usize>; as_slice() }`
(SPEC 13.1's Rust row; `as_slice` is the in-memory shortcut) and the three adapters: `Vec<u8>`/`Arc<[u8]>`/`BytesSource` (`&[u8]`,
copied: a source outlives its argument), `SeekSource<R: Read + Seek + Send>` (the length from one seek to the end; a lock around the
handle) and `SpoolSource::spool(Read, max)` (a stream spooled to memory, refused past the input-bytes bound with a message that begins
`input-bytes`). `Input { source, password }` with `Input::{bytes, seekable, spooled, new}` and `with_password` (the password is bytes:
R2-R4 use them as given, R5/R6 as UTF-8; it is never printed by `Debug`, never in a Model, evidence or transcript: the transcript has
each input's index and LENGTH only). **The input-bytes bound is checked against `len()` before a byte is read** (a test source that
panics when read proves it), and a length above `i32::MAX` is refused with the same name (kin's offsets are `i32`). `engine.rs` has
`ChunkCache`, a `SrcBacking` (D074) over aligned chunks (default 64 KiB, `Config::chunk_size`): in SYNC mode a read fills its chunk
inline and never misses; **with `Config::simulate_async` it behaves like a browser `Blob`**: a read of a chunk that is not resident
records it pending and misses, `hint`s are remembered, and the loop's `bytes` request makes them resident. The ranges for fetch round
r are every pending or hinted chunk with `2^r - 1` chunks each side, resident ones left out, a run of chunks one range (one read of the
source); round 0 prefetches the first and last chunk (SPEC 13.1: header, `startxref`, trailer). It is the library's own code (the
brief's "an async-simulating source in Rust, since Rust's real sources are sync"), not `testport`'s test source, so the driver and the
embedder's tests exercise what ships; an in-memory source is read in place (`SliceBacking`: no cache, no copy). A read that fails or
returns fewer bytes than the length said is remembered by the cache and reported as `source-changed` (or `io`), whatever the loop made
of the miss it caused. **What the 512-byte async run looks like** on the six slice fixtures: 3 attempts, 2 fetch rounds, 2 `bytes`
requests, 4 reads of the source, and the WHOLE file fetched: a PDF's text layer touches every object, and the read-ahead (and kin's
hints) reach the end of the file by round 2 (`bytes-fetched` = file length, never more); the rounds are far under `FETCH_ROUNDS`.
**Why**: the brief; and a Rust caller has a `File`, a `Cursor`, a `TcpStream`, each of which is one of the three.
**Alternatives**: `&[u8]` borrowed (cannot be held across attempts); making `testport`'s `ChunkedBacking` the engine's (test-only,
panics on a bad op, names no error); reading a `Read + Seek` whole (no bound on a file that is bigger than memory).
- [ ] **Signed off** (maintainer)

### D147 — The slice gate: `bb slice`, the driver `emon-rs`, and the retirement of the temporary predictor (P1.10; D025, D139)
**Decision**: the driver is `test/driver/rust/main.rs` (test-only: it reads the file, which the library never does; the brief's
`test/driver/rust` and `driver --out=json|html|evidence FILE`). Binary `emon-rs`; flags `--out=json|html|evidence|intake`, `--pretty`,
`--html=ids,geometry,confidence=N,ledger,pretty,drop-furniture,no-milestones,initials-plain,align-style`, `--locale`, `--ocr`,
`--assets`, `--password`, `--source=bytes|seek|spool` (which adapter reads the file), `--chunk=N --async` (the simulated async cache),
`--stats` (attempts, fetch rounds, bytes fetched, to stderr). STDOUT is the output and nothing else; a named error is `error: NAME` on
stderr and exit 4 (2 usage, 1 anything else). The crate is ASSEMBLED, not committed (`kin/scripts/emon-rs-build`: every non-law,
non-probe module of `gen/rust/emon`, the host files, the driver and the native tests, into `.cache/emon-rs`, copied only when they
differ so cargo's mtimes hold; warnings off because generated code is noisy, and the host files are warning-clean). **`bb slice`**
(`kin/scripts/slice`, a gate) runs (1) the native tests `host/rust/tests/engine.rs` (11): the three adapters give identical JSON and
HTML (and a tiny sync cache, and `&[u8]`); a 512-byte async source equals memory on the six fixtures, and **on every PDF of `test/pdf`,
`test/pdf/p3`, `test/content` and `test/fuzz` (70 files: 50 convert, 20 are refused, and the result, Model or error name, is the same
both ways)**; D040's errors, the owner and user passwords against the plain twin, the empty password tried by itself; the limits (by
length, deep in kin, not leaking to the next conversion); a lying source; the refusals of D144; `intake`; record and replay; the walk of
the Model; and that `lib.rs`, `engine.rs`, `source.rs`, `plan.rs` and `port.rs` name no `std::fs`, `std::path`, `Path`, `File::open` or
`read_dir` in code (`tess.rs`, the Tesseract adapter's tessdata directory, and `testport.rs` are not the library); (2) per fixture
(`test/para`'s six documents, not three: they are the ones with a witness) the driver's pretty JSON is `test/para/NAME.json`, its HTML
is `test/slice/NAME.html` and `NAME.full.html` (new goldens, `bb slice --update` writes them, a person reviews the diff) and the HTML's
`<p>` are, in order, the JSON's paragraphs (an independent reading); (3) `--chunk=512 --async` and `--source=seek|spool` give the same
JSON and HTML as memory, and the async run missed; (4) `--out=evidence` of `test/pdf/minimal.pdf` is the P1.7 golden; (5) the errors and
exit codes. **Timings** (driver wall time, in memory, process start to exit, warm; a baseline, not a target): 4-5 ms per fixture of
26-55 KB. **`tools/accuracy/predict-naive.sh` and `tools/accuracy/naive/main.rs` are retired** (D139 said P1.10 would): the Engine's
JSON for a document equals theirs, `kin/scripts/para-goldens` now writes through the driver, and the debugging `lines` mode died with
it (the evidence dump has every line and word). Not done, deliberately: the Go twin (P2.1), a `Cargo.toml` committed in the tree
(the assembled crate is a build artefact until the packaging item), and the brief's `driver --out=evidence` for scans (no OCR).
**Why**: the brief; and the identity claim of SPEC 13.1 ("every driver additionally runs each fixture through a simulated async source
with a tiny chunk and must produce byte-identical output") is checked here for the first time end to end, over damaged, repaired,
incremental and encrypted files, where the unit script's per-stage laws only reach the pieces.
**Alternatives**: the six fixtures as three; HTML goldens with no independent reading (an HTML golden of kin's own output proves
nothing about it); a Cargo workspace in `host/rust` (the generated files are in `gen/`, and every other script already assembles).
- [ ] **Signed off** (maintainer)

### D148 — P3.7: the structure tree is read whole into four arenas of its own; a standard type is used as it is, a custom one is followed through the RoleMap; attributes through `/A` and the ClassMap; the ParentTree is checked, not believed
**Decision** (P3.7, D034, D076, D096). (1) **Where**: `kin/pdfstruct.kin`, with its names in a generated `kin/pdfsnames.kin`
(`tools/pdfsnames.clj`, babashka, `--check`ed by `bb unit`: 69 names compared by bytes and the 52 standard structure types of
ISO 32000-1 14.8.4 and 32000-2, plus `Artifact`, which producers write as one; a role's code is its position + 1, forever). A
separate table from `pdfxnames` so that no two agents append to one. `pdfdoc`, `pdflex`, `pdfcontent`, `pdffont` and `para` are NOT
edited (`pdfx` gets one overflow fix, below). SPEC 8.4.7 said the tree is P3.7's arena; these are the arenas. (2) **Arenas**
(integer `Cells`, strings as runs of the shared `txt`, absent -1; the layouts are in the file's header): `se` (64 cells an
element, row 0 the StructTreeRoot, pre-order, so a subtree is a contiguous range), `sk` (4 cells a kid, in document order:
element, content, object reference, skipped with the reason), `sc` (24: an `/MCR` or a bare MCID), `so` (8: an `/OBJR`
and what it points at), `ss` (96: the document), `sa` (16: artifact spans, D149). Their `ea` codes are appended after the annotation
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

### D149 — P3.7: the MCID join is exact, glyphs are owned by the innermost marked content with an MCID, and every claim is checked against the page
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

### D150 — P3.7: the informativeness score is a weighted harmonic mean of per-mille checks, chance-corrected where the check is a coin flip, scaled by the weakest check; PDF/UA moves the prior and the trust, never the score
**Decision** (P3.7, D034, D076). Components, each per mille, -1 when there is nothing to check: **coverage** (tagged glyphs plus artifact glyphs over all glyphs), **diversity** (informative classes in use among elements that hold content: headings, lists, tables, figures, sections, notes and quotes, links, tables of contents; none 0, one 500, two 750, three or more 1000: a paragraph and a span say nothing about the document), **reading order** (D149 pairs, at least 4), **heading fit** (the mean of the verified share of headings and the share of BIG elements that are headings), **level fit** (D149 pairs, at least 4), **artifact fit** (consistent over consistent plus inconsistent spans), **nesting fit**. Reading order and level fit are CHANCE-CORRECTED, `max(0, 2 x agree - 1000)`: a coin flip agrees half the time and is worth nothing. The score is the weighted harmonic mean of the defined components (weights coverage 3, diversity 2, order 2, headings 2, levels 2, artifacts 2, nesting 1; a value floored at 50), times `(1000 + the weakest check) / 2000` where the weakest is taken over everything but diversity: one failed check pulls the whole down, and one that cannot be checked is left out, not assumed. No text to check: score 0 and status 1; no tree: status 0. **Prior and trust**: `prior` is 300 for a tree, 500 when the catalog says `/MarkInfo /Marked true`, 700 when `pdfuaid:part` (P3.9) is also at least 1; `trust` is the score, raised by a tenth of itself when PDF/UA is claimed, so the claim helps a tree that checks out and cannot rescue one that does not (D034: verified, not obeyed). The weights and thresholds are first values, not fitted: P10.1 calibrates them with the rest.
**Result on the synthetic pairs** (the same page, what the file says differs): correct 1000; wrong heading levels 656; reading order reversed 273; artifacts mis-marked 134; all three together (everything P under the Document, headings with the wrong levels, a running header tagged P, a heading marked Footer, a paragraph marked Pagination) 113; everything P 63. **On the corpus** (503 scored files, 476 `/Marked`): median 475, 10th percentile 93, 90th 978; 20 trees use no informative role, 7 cover no glyph (a tree whose content carries no marked content), and a heading role on a nine-point paragraph of 259 glyphs is caught (`pdfa-eng-wds/1897131.pdf`).
**Why**: a mean that one good component can carry hides the failures D076 names; the harmonic mean and the weakest-check scale make each failure alone visible (the single-defect twins fall to 656, 273 and 134, not 900), and chance correction stops a shuffled order from scoring 500.
**Alternatives**: an arithmetic mean; a minimum (one noisy check erases the rest, and no gradient is left); a learned model (parked, SPEC 1.1, and the corpus has one dominant producer).
- [ ] **Signed off** (maintainer)

### D151 — P3.7 is verified against an independent walk of the tree in pypdf, over every tagged PDF in the corpus, and by synthetic pairs written byte for byte
**Decision** (P3.7, D094, D100, D122). (1) **Oracle**: `tools/oracle/tagged-check.py` (Python, pypdf from `~/.cache/emon/pylib`: the third-party-oracle exception of D094) walks the structure tree itself from the standard, with no code in common with kin, and `kin/scripts/struct-sweep` (the generated Rust over a list of files: every page interpreted, the catalogue read, the tree read, joined, dumped) must agree on every ITEM: `/MarkInfo`, the element count; per element its object number, parent, depth, `/S`, resolved role and how, page, effective-language source, Scope, RowSpan, ColSpan, ListNumbering and the six text properties; its kids by kind and target; per marked-content reference its element, kind, page, MCID and stream, whether the text layer has a span for `(page, stream, MCID)` (the spans are pypdf's own walk of the content streams, Form XObjects included), and its ParentTree verdict; per object reference the same. One disagreement fails the file. Counting `/MarkInfo /Marked true` by pypdf alone: **476 of the 3,689 corpus PDFs**, 504 with a StructTreeRoot, 473 both; emon's catalogue reader (D121) gives the same three numbers. **Results**: all 504 tree-bearing PDFs, 455,057 elements, 269,721 marked-content references, **11,944,254 items, 0 differ**; the 3 Chrome-tagged synthetic PDFs (5,249 items) and the 7 fixtures below; `tools/oracle/tagged-check.py` still holds the P1.7 span check (111 spans, 2,553 of 2,553 glyphs) on the three. The first sweep found two things, both fixed: the `px-page-of` overflow (D148) and a ParentTree verdict that was left unset for a document with no ParentTree (now `absent`, as the oracle says). (2) **Fixtures**: `tools/synth/tagged_pair.clj` (babashka, `--check`ed by `bb unit`) writes `test/pdf/tagged/`: `good.pdf` (a title, four sections, paragraphs, a numbered list, a ruled table with header cells, a figure with Alt, a caption, a Span with ActualText and E, a French paragraph, running header, footer and a DRAFT watermark, custom roles through a RoleMap, a ClassMap, a ParentTree) and five twins of the SAME page that say it wrongly (`bad.pdf`: the three failures D076 names together; `bad-allp`, `bad-levels`, `bad-artifacts`, `bad-order`: one each), and `odd.pdf` (a RoleMap loop, a custom role, a remapped standard role, H7, a `/K` naming its own element, an element reached twice, nulls and strings in a `/K`, a duplicate and an unclaimed MCID, Form XObject content joined through `/Stm`, an OBJR to a Link, `/A` arrays with a revision number and a foreign owner, 70 nested elements, stray text and an artifact). (3) **Goldens**: `kin/scripts/struct-goldens` writes the dump of each (`test/pdf/tagged/*.json`, `test/pdf/tagged.txt`) from the generated Rust and runs the oracle on them; `kin/pdfstructtest.kin` holds all four targets to the dumps byte for byte, to what the fixtures were built to say as literals (roles and how, text properties, tables and lists, the join, the artifacts, the scores), to the separation of the correct tree from each twin, through 64- and 512-byte async sources, and on cut and corrupted copies. (4) **Fuzz**: `kin/fuzzprobe.kin` also reads and joins the tree and the fuzz seeds include `test/pdf/tagged`.
**Why**: accuracy is paramount (D023) and emon cannot be its own oracle (D100); a tree of 455,000 elements read by two programs that share only the PDF standard is the strongest check available, and the twins show the score does what D076 asked.
**Alternatives**: qpdf's JSON for the tree (it lists objects, not the tree's resolved roles); comparing whole dumps (a format change would be an oracle change); real mis-tagged files only (nobody knows their truth).
- [ ] **Signed off** (maintainer)

### D152 — The graphics state as built: paths, clips as the intersection box, images, `pa im pts gx` (P3.5; D096, D102, D126)
**Decision**: `pc-page`/`pc-document`/`pc-run`/`pc-do`/`pc-op` take four more arenas after `ln`: the evidence's `paths` (`pa`),
`images` (`im`), `pts`, and `gx`, the colour and optional-content workspace of `emon.pdfgfx` (one `Cells`, D153). A graphics state is 64 cells
(was 32): stroking colour and alpha, both colour-space keys and classes, the clip in force (a `paths` row, -1 none), blend mode,
soft mask in force, line width, first dash length. PATHS: `m l c v y h re` build a path in page space (D009) in `pts` (the first
128 points of a path; a longer one keeps its box and no geometry), its box and kind (a rectangle is one subpath of four
corners axis-aligned to a centipoint, with or without the closing line; else curve, single line, other). A paint operator makes a `paths` row (flags
`filled stroked pattern blend softmask closed evenodd`, fill and stroke colour, alpha, stroke width and dash in centipoints, `z`, `clip`,
`mark`, `ocg`, geometry); the box grows by half the stroke width. 200,000 paths or images a page are recorded (the rest set warning 1024).
A CLIP IS A ROW WHOSE BOX IS THE INTERSECTION of the new clip with the one in force (so a glyph's `clip` names the whole region's bounding box and
P3.6 never walks a chain); a clip that does not shrink the region makes no row; `W`/`W*` is pending until the next paint operator or
`n`. A Form XObject's `/BBox` is a clip, and glyphs shown in a clipping render mode (4-7) clip, at `ET`, to the union of their cells. THE
LIMIT: a clip by a non-rectangular path is its bounding box (a glyph in the corner a triangle leaves out is not clipped), and an even-odd
clip is its box: conservative, never a false "clipped". An IMAGE placement (`Do` and inline `BI`, whose dictionary is read for `W H IM`) is a
row: the unit square through the CTM, `rot` of its x axis, `skewed` unless its axes are within half a degree of a right angle, flags `mask smask
masked inline blend softmask`. `sh` is a `paths` row of kind `other` flagged `shading`, over the clip region. Alpha: `ca` and `CA`; blend modes and soft
masks are recorded as bits (`pg-blend pg-softmask`, `mg-blend mg-softmask`, `vc-blend vc-softmask`) and make a paint "not exactly known". `rg`
and `k` now set the colour-space class (D127 said they did and they did not: the Chrome goldens changed from `gray` to `rgb`).
**Why**: the visibility pass (D155) needs exactly this and nothing more; everything else of a graphics state is already evidence.
**Alternatives**: a clip chain walked by the reader; polygon clipping (a bounded add-on to `emon.pdfvis` if a crawler's trick ever needs it).
- [ ] **Signed off** (maintainer)

### D153 — Colour spaces and PDF functions (`emon.pdfgfx`, P3.5)
**Decision**: the ink colour of every painted object is 0xRRGGBB or -1 (unknown), and the colour-space class is `gray rgb cmyk spot pattern other`.
DeviceGray, DeviceRGB, DeviceCMYK, CalGray and CalRGB (read as gray and RGB: no gamma, no matrix), Lab (through XYZ and sRGB's transfer function, by
integer arithmetic, from the white point and range; D50's and D65's published matrices), ICCBased by `/N` (no profile is read), Indexed (its whole
palette is converted when it is first seen, from any base), Separation and DeviceN by the alternate space and the tint transform (a Separation's
256-colour palette is evaluated once), Pattern (-1). Functions are compiled into cells and run: type 2 (any real exponent, by squaring and square
roots), 3 (stitching), 0 (sampled: linear for one input, the nearest sample for more) and 4 (the whole PostScript calculator language but `ln`, `log` and `exp`
with a negative base and a fractional exponent, which make the function unusable and the colour -1). CMYK is `(1-c)(1-k)`. A space that is not read, and a
colour that cannot be converted, is -1: P3.6 never calls an unknown colour white. A cache of 24 spaces keyed by the object's handle, replaced round
robin. **Validated**: `test/vis/spaces.pdf` holds 27 colours (every space above, stitched, sampled, calculator and gamma-exponent tint transforms,
a Lab alternate); their expected values are computed by a SECOND implementation in `kin/scripts/vis-fixtures` (double precision, a Clojure
PostScript interpreter) and every target is within two levels a channel.
**Alternatives**: reading ICC profiles (a separate task, and the white test needs only the hit at 1 1 1); exact CalRGB.
- [ ] **Signed off** (maintainer)

### D154 — Optional content: `ocgdef`, OCMD and `/VE`, form and image `/OC`, `/AS` is not "off" (P3.5)
**Decision**: `/OCProperties /D` is read once a document: `/BaseState`, `/ON`, `/OFF`. A `BDC /OC` mark's `ocgdef` is 1 on, 0 off (an OCG by its configured
state; an OCMD by `/VE` (`And Or Not`, depth 8) or its policy `AllOn AnyOn AnyOff AllOff`). An `/OC` entry of a Form XObject or an image is a `marks` row
of its own (`mc-xobject`) around the form's run or the image's row. A group whose configuration is on but whose `/Usage /View /ViewState` is OFF
(an `/AS` auto state: a "print only" layer) is `ocgdef` 2: poppler and Ghostscript both DRAW it (checked on `test/vis/ocg.pdf`; Ghostscript also warns that it ignores
`/VE`), Acrobat hides it, so it is not `off` for P3.6 and fusion can read it as a watermark hint. A glyph or image inside any span whose state is 0 on its chain is `ocg-hidden`.
- [ ] **Signed off** (maintainer)

### D155 — Visibility classification (`emon.pdfvis`, P3.6; SPEC 8.1, 8.4.3)
**Decision**: after a page's content is read, every glyph and image gets `vis`, `vby`, `vchk` (SPEC 8.4.3 as amended): `clipped`, `offpage`,
`white-on-white` (the ink is within 4 levels a channel of the backdrop: the page's white, or the NEAREST EARLIER opaque axis-aligned rectangle that covers the whole glyph;
any other earlier paint that touches the cell, an image, a shading, a pattern, a translucent or blended fill, a partial cover, makes the backdrop unknown and the check silent),
`zero-size` (under one point), `occluded` (a LATER opaque paint covers the visible cell: a rectangle fill at alpha 1 with a known colour, no blend or soft mask and itself visible, or an
image with no stencil, `/SMask` or `/Mask`, alpha 1 and an axis-aligned placement; `vby` is its `z`), `ocg-hidden`, `transparent` (alpha 0). The first failing check names the class and
later checks do not run. Render modes 3 and 7 are NOT a class: they paint nothing, so white, occlusion and transparent do not apply (a hidden OCR layer over its scan is never "occluded" by it), while clip, offpage,
zero-size and ocg still do. A word never mixes visibilities: `pc-continues` breaks where `vis` or hidden-ness changes, and `hidden-text` is the source of a word whose glyphs are all mode 3 or 7. At most
4,096 rows are looked at each way per object (past that: "cannot tell"). TEXT UNDER A SCAN drawn in a visible mode (some older producers' OCR layers) is `occluded` with `vby` the scan's `z`: the ledger keeps its text, and fusion (P7.6)
may read an occluder that is a page-sized image as "this is an OCR layer".
**Limits**: a glyph is its CELL (ascent to descent), not its ink; a clip by a non-rectangle is its box (D152); a rotated picture is not an exact occluder.
- [ ] **Signed off** (maintainer)

### D156 — How P3.5 and P3.6 were validated
**Decision**: (1) `kin/scripts/vis-fixtures` (bb) writes 17 PDFs (`test/vis/`) and 3,278 expectation rows: one fixture per class (white in gray, RGB, CMYK, Separation, Lab, Indexed, ICC, DeviceN, on a same-colour rectangle; clipped by a rectangle, a form box,
a nested clip; off the page, off the crop box, on a rotated page; sizes 0, 0.5, 0.9, 1 pt and a `cm` scale; painted over by a rectangle, a picture, a page wall; NOT occluded by a translucent rectangle, a Multiply blend, a soft mask, a stencil, a colour-key image, a clipped-away
or layer-hidden rectangle, a partial cover, a rotated picture; every OCMD policy and `/VE`; alpha 0; Tr 3 and 7 OCR layers before and after a scan; and `spam.pdf`, one page with every trick). Every row is what the operators MEAN. (2) The laws of
`kin/pdfvistest.kin` (all four targets: 278 laws each, `bb unit red` fails on all) check each glyph's `vis`, `vby`, its word's source, the colour and class, `ocgdef`, image rows, counts, `ev-check`, and that no word mixes visibilities; `pdfcontenttest` runs the same files through
the 512-byte async source (paths, images, points and the colour workspace cell for cell) and corrupts and cuts five of them. (3) `kin/scripts/vis-check [--render]`: the generated Rust against the same rows, and the poppler RENDERER (`pdftoppm`): what is expected to be seen leaves ink in its box and what is expected to be hidden leaves none: 1,333 visible and 911 hidden glyphs agree,
every one. (4) `tools/vis/corpus-vis.clj` on the real corpus (899 files, the first three pages): see D157.
**Found**: the corruption law found a fault (a mutated `/Domain` that is not an array was walked as one in `gx-read-nums`: an index out of bounds); fixed, and 9,600 more random mutants of 12 fixtures through the Rust sweep fault nowhere. Two of my own expectations were wrong in the way
a per-glyph classifier is right and a per-string expectation is not (text partly over a rectangle is some glyphs seen and some not); the fixtures now use single straddling glyphs.
- [ ] **Signed off** (maintainer)

### D157 — Hidden-text detection against poppler on the real corpus
**Result** (`bb tools/vis/corpus-vis.clj --n 900 --max-kb 1500`, 899 files judged, first 3 pages, 2.78M text glyphs): the VISIBLE glyphs are in poppler's text 99.75% (2,768,799 of 2,775,784) and leave ink on poppler's rendered page 99.83% (2,751,832 of 2,756,629). OFFPAGE: 330 of 330 are also dropped by `pdftotext`. CLIPPED: 26,620 glyphs, 26,617 (99.99%) are also not in
`pdftotext`'s words: poppler clips too. `hidden-text` (Tr 3, 7): 68,429 glyphs, 67,180 (98.2%) are in poppler's text (it extracts OCR layers; the rest it drops were not examined). WHITE (349 glyphs, in 6 files) and OCCLUDED (536, 8 files): `pdftotext` keeps all, as it does not look at paint; the renderer cannot judge them because something else is
drawn where they are (a picture, a patch, the same text again) for all but 3 of the 27,505 glyphs of the hidden classes (those 3 had no ink, as claimed): the fixtures (D156) are the witness for those classes. ZERO-SIZE: 137. No sampled file had transparent or `ocg-hidden` text. Pdfminer.six does not reason about visibility at all (it extracts Tr 3 text and white text); the glyph streams were already held to it (D127: 99.6% of 2.0M characters).
**Also found**: poppler and Ghostscript ignore the `/AS` View state (D154); `pdftotext` drops clipped text; the corpus's white and occluded text is in a handful of files (white text in a brochure, text under white patches in a form).

### D158 — The Go Engine API, as built: the same machine as the Rust one in Go's idiom, sources are `io.ReaderAt` + a size, and what Go cannot say the way Rust does (P2.1; SPEC 13, 13.1, 3.9; D058, D040, D144-D147)
**Decision**: the Go host is a root package `emon` (`host/go/source.go`, `engine.go`, `model.go`) over the generated packages and
`host/go/port`; like the Rust crate it is ASSEMBLED, not committed as a module (`kin/scripts/emon-go-build` builds `.cache/emon-go`:
`gen/go` without the `*test` and `*probe` packages, `host/go/port` without `testport.go`, `pngenc`, the root files, and the driver
`test/driver/go/main.go`, binary `emon-go`). It is a line-for-line port of `engine.rs`: `NewEngine(Config)`; `Intake`, `Convert`,
`ConvertStats`, `Record`, `Replay` (each takes `[]Input`, as Rust's `Inputs`); `Options`, `Config`, `JSONOptions`, `HTMLOptions`
(`DefaultOptions()`, `DefaultConfig()`, `DefaultHTMLOptions()`: the zero value of `HTMLOptions` is NOT the default, `Milestones` is false
in it); `Model` (`Root`, `Pages`, `Furniture`, `Dropped`, `Find`, `Evidence`, `ToJSON`, `ToHTML`, `Serialize`, `Source`, `NodeCount`) and
`Node` (`Kind`, `ID`, `Conf`, `Sources`, `Flags`, `Parent`, `Children`, `Inlines`, `Blocks`, `Text`, `Regions`, `Attr`, `Index`);
`Intake`; `RunStats`; `Transcript` (an alias of `port.Transcript`). **Sources are SPEC 13.1's Go row**: `ByteSource` is
`io.ReaderAt` + `Size()`; `BytesInput`/`NewBytesSource` (in memory; an optional `SliceSource.AsSlice` lets the engine skip the chunk
cache, as `as_slice` does), `ReaderAtInput(r, size)` (an `*os.File` and its Stat size, an `*io.SectionReader`: the Go-only form),
`SeekableInput` (an `io.ReadSeeker` behind a lock), `SpooledInput` (an `io.Reader`, spooled to the input-bytes bound). The password
travels with the `Input` (`WithPassword`), is bytes, and `Input.String()` never prints it. **The loop is the Rust loop**: a fresh `Src`
view and fresh arenas per attempt, a miss checked after each stage, a panic (Go: `recover`) in an attempt that missed is a miss and in
one that did not is a named `*Error`, `ChunkCache` with doubling read-ahead and coalesced ranges, the `bytes` fulfiller, `simulate async`.
**The refusals are the Rust slice's, in the same words** (D144): OCR accepted and inert, images `unsupported-format` *(read since D187)*, several inputs and
`assets` other than `none` `unsupported`. **What Go says differently, each with its reason in `kin/scripts/embedders`**: errors are one
`*Error` with a `Kind` tag and flattened payload fields (Go has no sum type; `Name()` is the same stable name); `Option<T>` is a second
return value (`Attr`, `Parent`, `Evidence`) or, for `HTMLOptions.ConfidenceBelow`, a `*uint32`; `Page.Label` is a string and a
`HasLabel` flag; `Type::new` is `NewType`; an enum is a named int and constants (`OcrAlways`, `AssetsNone`, `ErrNoInput`). **One real
difference of mechanism**: Rust's `Config.limits` is a per-thread override table; Go has no goroutine-local state, so
`port.WithLimits` keeps a process-wide table behind a `sync.RWMutex`: a conversion WITHOUT limits takes it shared (and never sees an
override), one WITH `Config.Limits` takes it exclusively, so conversions with custom limits run alone in the process. kin still reads a
bound only through `limit-of`. A `Model` locks its arenas on every accessor and is read from many goroutines (a test does). **Tests**:
`host/go/engine_test.go` is the Rust file test for test (11) plus a concurrent-read test (12), run by `bb slice` with `go test` on the
assembled module, and `go vet` is held clean. The driver `emon-go` has the flags and exit codes of `emon-rs` (it adds
`--source=readerat`); both drivers gained `--record=DIR`, `--replay=DIR` (a transcript directory), `--parallel=N` (N conversions at once on
one Engine, all N required equal; not with record/replay: a Rust `Transcript` holds `Arc<Bitmap>` over a `Cell` and is not `Send`, so a
transcript is one thread's) and `--out=bundle` (one conversion, every output, sections `== json`, `== json-pretty`, `== html`,
`== html-full`, `== evidence`, `== intake`). **`bb slice` now runs every section on both hosts** and passes on Go: six fixtures to
`test/para` and `test/slice`, `--chunk=512 --async` (it missed and re-ran), `--source=seek|spool`, the evidence golden, the named
errors and exit codes.
**Why**: SPEC 13 asks for the same small API in four languages; D058 for sources, never paths; the Go row of 13.1 says `io.ReaderAt` +
size, and `*os.File` satisfies it with its size. **Merge note**: `attempt()` is the one place the Engine calls the text-layer stages in
both languages (`engine.rs`, `engine.go`); when P3.5/P3.6 change `pc_document`'s signature, both change together, and `bb identity`
fails if only one does.
**Alternatives**: a Go `Engine` that mirrors `Inputs` with a variadic or an `any` (rejected: `[]Input` and `BytesInput` say it plainly);
`context.Context` on `Convert` (the loop has no suspension point to cancel at yet; it arrives with the Task port); a limits table per
`Engine` (kin's `limit-of(kind)` has no engine argument, so a per-engine table needs a thread or goroutine id to find it).
- [ ] **Signed off** (maintainer)

### D159 — `bb identity`: the bundle, the arms, the corpus, and what an answer is (P2.2; SPEC 14.1.5, D058)
**Decision**: both drivers print a BUNDLE (`--out=bundle`: compact and pretty JSON, default and full HTML, the text layer's evidence
dump, the intake line) so a document is one process per arm, and an ANSWER is the bundle's bytes, the exit code and the stderr, so a
document that is refused (damaged, encrypted, an image, a wrong password) must be refused by the same name, the same message, the same
way on every arm. **The arms** (`kin/scripts/identity`): Rust over bytes, `Read + Seek`, spooled `Read` and a 512-byte async source;
Go over bytes, `io.ReaderAt` (+ size), `io.ReadSeeker`, spooled `io.Reader` and the same async source; and the transcript trip: each
host's `--record` writes a directory, the two directories must be equal byte for byte, and each host REPLAYS the other's to the
reference bundle (the corpus is (input, transcript) pairs: the transcripts are empty because the slice issues no requests, D089, but a
transcript is recorded, checked against the input's index and length, and read back). `--full` adds async chunks of 7, 100 and 4096 bytes
and a sync 64-byte cache on both hosts. **The corpus**: every committed PDF under `test/` (the synthetic documents and their scans, the
PDF-layer, repaired, encrypted, tagged and fuzz-crasher fixtures: 210 files), each (file, password) row of `test/pdf/p3.txt` (wrong
passwords included, 258 inputs with them) and two PNGs (`unsupported-format` when this was written; read since D187, which added
the 89 image fixtures of `test/image/`). `--corpus` runs the never-committed public corpus
(`corpus/`, a symlink in a worktree) INSTEAD: a non-gate, slow. **Results**: the gate is green and found NO difference between hosts or
arms: 258 inputs, 226 converted, 32 refused by name (`bad-xref` 1, `encryption-unsupported` 5, `no-pages` 1, `password-incorrect` 10,
`password-required` 11, `unsupported-format` 4), each across nine arms and the record/replay trip, in 14 s on this machine. The gate
was shown to see: a one-byte change in the Go driver's HTML is reported with its section and byte offset on every Go arm. **The public
corpus**: all 3,689 PDFs of the never-committed public corpus (`corpus/`, 12 GB) were run through the same nine arms and the transcript trip with `bb identity --corpus` (30 minutes): all 3,689 converted and every answer was byte-identical, no difference. **What is NOT an arm yet**: Java and C# (they have ports only; their Engine layers are later phases, and each gets an
arm when it has a driver), images and several inputs (refused by name on both), OCR and every request/answer in a transcript (nothing
is issued; `bb plan` exercises the protocol and the transcript format across the two hosts with a probe, D089), `assets` modes (D117
is not built). Both drivers are built by `kin/scripts/emon-rs-build` and `kin/scripts/emon-go-build`; neither reads anything but the
file it is given.
**Why**: SPEC 14.1.5: identity is over (input bytes, transcript) pairs, and 13.1: how the bytes arrive is not an input. A gate that compares only
the happy path would miss exactly the divergences in error handling that a crawler routes on.
**Alternatives**: comparing JSON and HTML only (a refusal that reads differently on two hosts is a bug a crawler would see); one process per
output (about 6x the spawns for the same claim); keeping the identity gate on the Rust and Go arms only after `--full` (the five
adapters are cheap and the Go one is the one that has never been exercised).
- [ ] **Signed off** (maintainer)

### D160 — `bb embedders`: the surface is read from the source, every difference is a failure or a table entry with its reason, and the library names no filesystem (P2.3; SPEC 3.9, 13, 13.1, D058)
**Decision**: `kin/scripts/embedders` reads the two Engine layers' SOURCE (no compiler: seconds, and it runs on a machine without cargo or
go) and compares (1) THE SURFACE: Rust's is what `host/rust/src/lib.rs` re-exports (25 types), Go's every exported identifier of
`host/go/{source,engine,model}.go` and the loop's `Transcript`; each reduces to `type`, `field`, `variant`, `method` (with arity,
receiver not counted) and constructor lines, compared without case or underscores (`to_json` is `ToJSON`). Today they agree on 24 types,
74 fields and variants, 38 methods with equal arities and 13 constructors and defaults. A name or an arity on one side only fails the
gate unless it is in a table in the script WITH ITS REASON (`static-names`, `default-names`, `member-renames`, `go-only`, `rust-only`:
`Type::new` is `NewType`, an enum with payloads is a tagged struct, `Option<String>` is a string and a flag, `Inputs` is Rust's `Into`
wrapper), and an entry that is no longer needed is a failure too. (2) SPEC 3.9 and 13: every name the SPEC spells (27 members, 9 types,
the byte-source adapters of 13.1) exists on both with the arity the SPEC gives; the asset accessors (`assets`, `asset`, `asset_files`,
`Asset`: D117) are listed as PENDING on both, not failed. (3) NO FILESYSTEM (D058), the Rust test of D144 widened: no host library file
(Rust, Go, Java, C#: 13 files) and no generated non-test module (168, in the four languages) names a file, path or directory function or
type; `testport.*`, `tess.rs`, the tests and the drivers are not the library, and the only operating-system reads are
`std::env`/`os.LookupEnv` for `EMON_LIMIT_*`. **Differences found and fixed**: none between the two layers as written (the Go layer was
built from the Rust surface), and the gate was shown to see: a renamed Go method, an extra Go parameter and an `os.Open` in `model.go`
each fail with the name. The first run of the parser itself found two of its own faults (char literals `'}'` in `plan.rs` broke depth
counting; a greedy argument match miscounted `to_files`), fixed. **Not compared**: Java and C# (ports only), the `plan` and `port` module
surfaces (the loop's internals are held by `bb plan` and `bb hosts`), types and return types (arity and names only: SPEC 3.9's own level).
**Why**: nome's D568: the surfaces drift by hand and are found late; here a mismatch fails the next `bb gates`.
**Alternatives**: `go doc` and `cargo doc --output-format json` (accurate types, but a compiler per host and a nightly flag for Rust); a Go
program using `go/ast` (the language of the thing compared, but not babashka, and the Go source here is regular enough).
- [ ] **Signed off** (maintainer)

### D161 — `bb repeat`: run vs run, serial vs eight at once, and the file with its objects in reverse order; there is no page submission to reverse yet (P2.4; SPEC 12, 14.1.6)
**Decision**: for every document and each of Rust and Go (`kin/scripts/repeat`, 210 documents): (1) RUN VS RUN: two processes give the
same bundle, exit code and stderr; (2) SERIAL VS PARALLEL (SPEC 12.1): `--parallel=8` converts the document eight times at once on ONE
Engine (threads on Rust, goroutines on Go), the driver itself fails if any of the eight differs from the first, and the first must equal
the serial run (this also holds that a `Model` and the source cache are safe under concurrent use); (3) REVERSED: the document with
its objects laid out in reverse file order (`tools/lib/pdfreverse.clj`: each object's bytes untouched, a fresh classic cross-reference
table, the same trailer, `/Prev` and `/XRefStm` refused) gives the same JSON, pretty JSON, HTML (default and full) and evidence on both
hosts; the intake line carries the file's byte count and is left out; `--full` also runs the reversed file over a 512-byte async source
and a second repeat. **The honest limit**: SPEC 12.1's "pages submitted in reverse" needs a seam where a host submits page units and
merges results in submission order; the Engine reads a whole document as ONE unit today (`pc_document` loops the pages inside kin), so
there is no host-side page submission to reverse. What can be reversed is the order in which the FILE presents its pages, page tree and
resources, which exercises the same property (nothing in the output may depend on where in the file a part is met) and is checked in
both languages; when per-page units exist (the Task port, D022), the same gate gets an arm that submits them reversed and merges by
submission order. 168 of the 200 documents that convert are rewritable (single classic table, entries that point at their own
objects); the other 32 (xref streams, hybrid, incremental, repaired, damaged) are counted, not compared. A repaired file whose table
lies (`repair-offsets-shifted.pdf`) was first rewritten from its false offsets: a fault of the rewriter, not the Engine, fixed by
requiring every entry to point at `N G obj`. **Results**: green on both hosts, no difference. `--corpus` runs the public corpus: a sample of 308 of the 3,689 public-corpus PDFs (`bb repeat --corpus --sample 300`, 2 minutes): green, 241 rewritten in reverse object order, no difference.
**Why**: SPEC 12.2: the same input gives the same bytes every run, and a reader that let the file order leak (a hash order, a first-seen
tie-break) would show here first.
**Alternatives**: reversing only the page tree's `/Kids` (a different document); submitting pages from the host (no seam yet);
shuffling instead of reversing (reverse is deterministic, and the interesting order is the extreme one).
- [ ] **Signed off** (maintainer)

### D162 — Right-to-left text is put in logical order by the Unicode Bidirectional Algorithm (W1-W7, N0-N2, L1, L2, L4), in a module of its own; a word with no letter to say so is put right once its line is known; a line follows its paragraph (P1.7; D104, D105, SPEC 3.6)
**Found by** P1.8's measurement (D139): in Hebrew text `2026,` came out `,2026`. **Cause**: D104's rule reversed the glyphs of a word that had more right-to-left than left-to-right CHARACTERS, keeping runs of digits and Latin letters, and every other character was reversed with the letters. Digits counted as left-to-right, so a word of numbers and punctuation (`2026,`) was never reversed and `ל2026` was not either; and a separator or terminator was a letter's neighbour, never a number's: `50%` came out `%50`, `12/05/2026` came out `2026/05/12`, `1,250` came out `250,1`; two numbers apart by a space did not swap places (a word-level run of "left-to-right" words kept them in the order shown); a line whose Latin letters outnumbered its Hebrew ones (a last line that ends in `KPI ו-ROI.`) was not reordered at all.
**Decision**: (1) `kin/bidi.kin` (`emon.bidi`) is UAX #9 for one run inside a RIGHT-TO-LEFT paragraph (level 1), written as the rules are: W1 (marks), W2/W3 (a European number after an Arabic letter is an Arabic number; Arabic letters are R), W4 (a separator between two numbers), W5 (terminators beside a European number), W6, W7 (a number after a left-to-right letter), BD16 and N0 (paired brackets, a stack of 63), N1/N2 (a neutral run takes the direction both sides share, else R), I1/I2 (levels 1 and 2), L1 (trailing white space), L2 (the order); L4 as `bd-mirror`. Explicit embeddings, overrides and isolates are neutral (a PDF's text has none). The classes are `kin/bidiclass.kin`, GENERATED by `kin/scripts/bididata.clj` from the vendored `UnicodeData.txt` (the bidi class), `test/bidi/BidiBrackets.txt` and `test/bidi/BidiMirroring.txt`, so no script, language or locale is listed anywhere: a character is whatever Unicode says it is. kin owns no memory, so a run lives in a region pushed past the end of a caller's `Cells` (the evidence text arena is one) and dropped again. (2) The two directions are one operation. The rules are mirror-symmetric (a number run is one run whichever side its separator is on; N1 and N2 look at both neighbours), so the glyphs, in the order they were SHOWN, are read as if logical, and the order the algorithm would DISPLAY them in is their logical order; the two exceptions, W2 and W7, look back, and the ambiguity is real and inherent (`abc 2026` is the same on the page whether it was typed `abc 2026` or `2026 abc`; the first is taken). (3) `pc-emit-word` writes a word's text through `emon.bidi` when its right-to-left LETTERS outnumber its left-to-right ones (letters, not characters: digits no longer decide), so `ל2026` and `ו-ROI` are right; a glyph at an odd level whose text is one mirrored character (a bracket) is written as its mirror (L4: the glyph was drawn mirrored, `(` at the left of `(2026)` in a right-to-left line is the logical `)`). (4) A word that no letter announced (`2026,`, `50%`, `(1)`, `1.`) is put right by `pc-word-fix` when its LINE is right to left, in place (the same code points, nothing moves if the order does not change). (5) A line is right to left when its right-to-left letters outnumber the left-to-right ones, OR when the line before it on the page is and this one has a right-to-left letter or no left-to-right one: a paragraph has one direction (UAX #9 P2, P3), and its short mostly-Latin last line is not a left-to-right paragraph. (6) Within a line the words are reversed and each group of words that make one left-to-right run (words with Latin letters, the numbers that follow them, and the neutrals between) is turned back; a word of numbers alone is NOT part of a run (two numbers separated by a space are R, R: they swap places, as in the algorithm), and neutral-only words at a run's ends are not either.
**Why**: Hebrew and Arabic text is full of numbers, dates, percentages, prices and Latin names, and each of those is what the algorithm's W and N rules exist for; a hand-made rule per case would be the thing the algorithm replaced, and would assume what text is. **Held to**: (a) Unicode's own `BidiCharacterTest.txt` (18.0.0): every row of its 91,707 that emon's resolver claims (paragraph level 1, only the classes L R AL EN AN ES ET CS NSM WS ON; brackets included) is 45,789 rows, and ALL of them have the resolved levels and the visual order the Unicode file gives; `test/bidi/conformance.txt` holds every twelfth (3,816; `kin/scripts/bididata.clj --rebuild` writes it from `.cache/bidi/`, `--fetch` downloads it), and `kin/biditest.kin` checks each row on every target. (b) 23 hand-written samples (`kin/scripts/bidi-samples.clj`, `test/bidi/samples.txt`: Hebrew and Arabic with numbers, commas, percent signs, dates, times, prices, currency, ranges, Latin words, brackets), each as it is shown and as it is logical, with the rule that decides each named in the row, checked both ways (shown to logical, and logical to what is shown). (c) The synthetic Hebrew document (`script-hebrew`): its line text is now the HTML's, `לשנת 2026, לצד מונחים כמו KPI ו-ROI.` and `יעדים 1.` (the golden `test/content/synth.txt`, one row, was `,2026` and `.ROI-ו KPI כמו`). **Accuracy** (`bb accuracy --update`): `pdf/scripts` CER 0.1546 -> 0.1468, WER 0.3574 -> 0.3460, bag-of-words F1 0.7726 -> 0.7800, paragraph-boundary F1 0.2444 -> 0.2472; `pdf/all` CER 0.18425 -> 0.18414, WER 0.24197 -> 0.24183; nothing regressed (18 metrics improved, 1,046 compared). **Alternatives**: patching trailing punctuation (it would have fixed the one case and left `50%`, `1,250`, dates and `ROI-ו`); a list of Hebrew and Arabic punctuation (a locale; the table is the whole of Unicode); full per-paragraph direction (paragraphs are made by D134 AFTER the text layer; they could take the direction of their first strong letter and hand it back to the lines); the mirror applied at the reading of glyphs (it needs the level, which is the algorithm's).
**Limits / Open questions**: (a) A paragraph's direction is approximated by its line and the line before (5). A short Latin-led FIRST line of a Hebrew paragraph (`iPhone 15 Pro הושק`) is read as left to right; when `emon.para` hands a paragraph's direction back to the text layer this is exact. (b) A glyph that stands for several code points (a ligature) is mirrored only when its text is one code point. (c) Words whose glyphs run right to left on the page (a producer that wrote logical order) are not touched, as before. (d) No isolates or embeddings (a PDF has none). (e) A run's start and end are right to left (`sot` and `eot`) at a WORD, so a number at a word's edge does not see the word next to it; words in a run are ordered at the line (6), which is the same where it matters, and `W7` across a space is the line's. (f) Right-to-left TEXT in a left-to-right line (a Hebrew quotation in an English sentence) keeps the old rule: a word with more right-to-left letters is reversed alone; the order of its words in the line is the page's.
**Also found**: the glyph stream is untouched (D105's witnesses are on it); `ct-rows` goldens are the only text that moved. The Go and Rust engines need no change (`pc-document` has the same signature).
- [ ] **Signed off** (maintainer)


### D163 — The column stage as built: gutters from the rows of a page's words, extents carried along the columns, a cut tree that cuts at full-width gaps and between gutters, never through them (P4.1; D111, SPEC 10.1.1)
**Decision**: `kin/columns.kin` (`emon.columns`) does SPEC 10.1.1's steps over a page's UPRIGHT lines (non-upright ones stand alone, D136), on a
COPY of `lines` (`lc-copy`; the evidence is never written). Everything is an integer in centipoints; each number below is a named constant
(`LC_*`), the settings of SPEC 13 until the Engine has a table. **(0) The page**: its dominant size is the size (within 1/20) with the most line width;
the body block is the dominant-size lines at least 2/5 as long as the longest of them (`LC_LONG`); lines wholly outside it by half a size,
when most of them are in type under 19/20 of the dominant, are SIDE lines (D165) and a line of digits among them is dropped (line numbers).
**(1) Gutters, over words**: items are the words (a producer that writes a row across the columns gives one LINE with a gap in it); rows
are the words whose baselines are within half of the smaller size; per row, the free x-intervals between two runs of ink at least
`gutter-min` wide start an interval (the larger of 3/5 of the row's size and 1.5 times its median word gap: a river of a justified line
does not persist); an interval GOES ON through a later row where a part of it at least 3/5 of the size is not touched by ink (`gutter-min`
is only asked for the median when it starts: a justified column of three words a line has word gaps wider than its gutter, so the
median would end it), and a row counts only where it has type of body class (17/20 to 27/20 of the dominant size: a footnote, a chart
label, a drop cap neither starts, ends nor extends one); rows that have words on one side only count (the longer column's tail, a column
whose baselines are offset by a heading). A gutter needs `gutter-rows` 3 rows, and words beside it in three rows on EACH side, `gutter-words` 3 on
average counted in the run of ink NEXT to it (not the row: a table cell is one or two words, a column's line five); a vertical gap of more
than `hole` 3 sizes between rows of body type ends every gutter (a figure with no text is that tall). **Table rows**: a row of four runs of ink
or more, none of more than three words and each three sizes apart, is a spanning element: it ends the gutters inside its extent and
stops an extent that is carried up through it (a table across two columns is read row by row); this is NOT a table stage (P5.3). **(2) Split**:
a line whose words are on both sides of a gutter inside its extent is split at it (a new row of the copy). **(3) Extents**: carried up
through one-sided lines of body size until a line crosses or a gap of `hole` sizes, except the last line of a spanning paragraph (a line
whose neighbour above crosses the gutter within 4/5 of a size); carried down by the sweep itself, and trimmed above a last row that has
words on one side only when ink crosses the gutter close under it (a heading that goes with the spanning text under it). Gutters of one
column gap seen in pieces are merged. **(4) The cut tree**, a stack, a region cut in this order: the cut under the title (see D165); the
first full-width gap of more than 5/2 sizes between the lines of the region (`LC_GAP`: the story break of a newspaper, a figure's hole), made
only when some gutter divides the region (a region of one column is not cut at every paragraph gap), and never a gap that lines up in
balanced columns, which is not wider than a paragraph gap; a gutter whose extent covers every line of the region, vertically; the first top
or bottom of the extent of a gutter that divides the region, strictly inside its lines and NOT strictly inside another dividing gutter's extent
(the cut is made between the columns of the other gutters, not through them: that is what made five-column newspaper pages come out in pieces).
Right-to-left pages (the majority of the page's lines, D104) put the right half first. **(5) Fragments**: leaves, their lines in rows
(baseline, then x); `band` is the number of horizontal cuts above the middle of the fragment and across its x-range, `col` the number
of earlier body fragments of its band; the page's `layout` has one cell per (band, col); a fragment also keeps the bottom of the region its
vertical cuts came from (the columns of one set end together: D164).
**Why**: SPEC 10.1.1 as written finds gutters from persistence rows with ink on both sides in every row, which fails exactly where real
pages are: columns whose baselines are offset by headings (every row would be one-sided), figures and tables whose labels live in one column, narrow
justified columns, page tails. Each departure was forced by a document of the corpus or a paper (the dump showed it) and is a constant a
person can read and change; the SPEC's rules for what a gutter IS (tall, narrow, persistent, between words) stand, and so does its cut rule
(vertical first, horizontal only at a spanning element or a full-width gap): the rule against cutting through another gutter's columns is
its consequence for pages with more than two.
**Alternatives**: plain recursive XY-cut (fails on aligned paragraph gaps and on a figure over two of three columns: D111); Breuel's
whitespace cover with a constrained line finder (the same gutters, more code); Voronoi or Docstrum (bottom-up, parked for pages this
scores badly); gutter rows counted over lines rather than words (misses the rows a producer writes across columns); a median word gap
that also ends a gutter (ends them in every narrow justified column).
- [ ] **Signed off** (maintainer)

**Added at the merge (D163).** `lc-size`, `lc-width` and the size column of the item table are held to +-10^7 (`lc-cap`): the fuzz crasher `content-size-overflow-s1-i76.pdf` (a 10^9 font size) overflowed `size * 20` on Rust (debug) and wrapped on Go. No real page is near the cap.

### D164 — A paragraph that fills its fragment goes on in the next one when the points say so: the table, `join-min` 4, and what each signal is worth (P4.1; D112, SPEC 10.1.2)
**Decision**: between the last line of a body fragment and the first of the next, in reading order (next column, or the first of the next page),
a paragraph continues when its points reach `join-min` 4. The points (`JS_*` in `kin/para.kin`): last line ends in a hyphen after a letter **+3**;
reaches the column's end edge (within 15 per cent of the fragment's width) **+2**; ends without a sentence mark (a footnote mark of one to
three digits after the mark, `thing.3`, is not read) **+2**; the next line starts in lowercase **+3**; the next line is indented where the
document's paragraphs have first-line indents (`pa-indents`: any indent counted) **-3**; starts with a list marker (a bullet or dash alone,
`1.`, `12)`, `a)`, `(a)`) **-3**; another size or style **-3**; the last line is short (more than a fifth of the width free) **-3**; the next
fragment is of another WIDTH (differs by a quarter, both of three lines or more: a table or figure is not a column) **-3**; the words
follow in the stream (a weak **+1**); and one the SPEC does not have, **+1: the last line is at the bottom of its column set** (the
fragment keeps the bottom of the region its vertical cuts came from, so columns that end together are a set; a column that ends higher
than its neighbours ended a paragraph). The edge and bottom signals need a fragment of two lines, the width one of three. A tagged
`P` that holds both (decisive in the SPEC) is not read: the tagged tree is P3.7's and reaches this stage with P4.2. A join is one
paragraph, `at` one region per fragment, `text` inlines and a `break` milestone only where the page changes, the column break only
the regions; the hyphen at the break is `pa-join`'s (SPEC 3.6: no locale, hyphen kept, a soft hyphen goes); notes that begin
between the two are written after the joined paragraph (D165). `pa-dump` prints every fragment and every join decision as lines
(`F` and `J`, one law, `test/para/two-columns.dump`): the evidence dump of the stage.
**Why**: the table is the SPEC's with weights chosen on the corpus so that the cases with evidence join (a hyphen, a lowercase start, a
full line without a stop) and the cases without do not unless the column is full and the line reaches the
edge (joins 10 of 11, no false join among the nine column documents). **What it cannot know**: a paragraph that ends at the bottom of a
column in a full line with a full stop, followed by a capital, has only its position to say it goes on: the synthetic corpus has both
kinds and the numbers are join P 1.00 R 0.91; lowering `join-min` to 3 gave R 0.82 and P 0.90 before the bottom signal (the false join was a paragraph that
ends at a page's foot, with a footnote mark). The width signal made a table after a column not a continuation (the law of the table).
**Alternatives**: join whenever there is no sentence mark (joins every unindented paragraph that starts a column); decide by a score fitted to
the corpus with a learned weight (the corpus is 11 joins); a signal for the paragraph's own length (unknown here).
- [ ] **Signed off** (maintainer)

### D165 — Margin notes and sidebars are lines outside the body block; a column of them is one `layout` cell, each run of them one `note`; the title above columns is cut from them (P4.1; D111, D113)
**Decision**: the side lines of D163 (outside the body block by half a size, mostly under 19/20 of the dominant size; a digits-only line among
them is a line number and is dropped, with no ledger entry yet: P4.3's) are grouped, per side, into RUNS (a gap of more than two sizes ends
one); a run of at most four lines, all of them in small type, is a `margin` note, any other a `sidebar`; each is a fragment with the body
fragment it overlaps most in y as `assoc` and its band; the notes of one side are ONE column of the page's `layout` (the left strip first, after the body
fragments of the band, `col` counted from there). A note is a `note` block with `sub` (the `rl` role) and its lines made paragraphs as any fragment's, under it; it
is written when the paragraph open beside it is closed: after the last paragraph that begins at or above the note's top, never inside a
paragraph (a note beside a paragraph that goes on to the next column waits for the next paragraph break). When the page has side
lines and something lies above the body's first line that is not of body type (a title), the cut under it is the page's first horizontal cut (the
gold's `band 0`). Not found: a sidebar INSIDE the body block (a box between two columns); it is a column.
**Why**: the gold's notes (`columns-margin-notes`, `columns-uneven`) have no gutter: a note is one or two lines, too few rows for D163's gutter, so
the margin is found by what lies outside the text, which is what a person does; the placement is D111's "after the body block being read
where it begins".
**Alternatives**: gutters only (the notes are in the middle of the paragraphs they stand beside); a note per line; a sidebar by a box (the
interpreter writes no paths yet).
- [ ] **Signed off** (maintainer)

### D166 — `pa-build` makes the Model in two phases from the column fragments; the page `layout` is written for every page; HTML carries the column of a block with `geometry` (P4.1; D112, D113, D132)
**Decision**: `pa-build` copies the lines, runs `lc-page` on every page (phase one: fragments, and the page `layout` from them: `lay-add`, one
cell per (band, col) with the union of the fragments' boxes), then builds paragraphs per fragment (phase two), the line gap being the
lower quartile over the fragments' consecutive lines. A page with text has at least one `layout` cell (a one-column page has one), so every Model's JSON has a
`layout` per page: the six `test/para` goldens and the six `test/slice/*.full.html` changed by exactly that (and `data-emon-col`); no paragraph did. The signature
grew by the column stage's scratch Cells (`lt ls sd it io rw gu ac tm cf lo pr`) and the debug log (`dbg`); `host/rust/src/engine.rs`
is the only caller, and `kin/paratest.kin` makes them in `pt-build`. The lines that are not upright are queued after a page's
upright paragraphs as before (D136; a first version queued them before and moved the ring text of `stamp-seal` ahead of the page).
**Why**: the Model's `layout` is a promise to every consumer that the map is kept (D111); writing it only for multi-column pages
would make "no layout" mean two things.
**Alternatives**: a `layout` only where there are two cells (a consumer cannot tell a one-column page from an unanalysed one); one call per
page from the Engine (it would hold the open paragraph across calls: the paragraph spans pages).
- [ ] **Signed off** (maintainer)

### D167 — The style inventory and the heading analysis are two modules that run BEFORE the paragraph stage and hand it one array, `hc` (P4.2, P4.4; D018, D134)
**Decision**: `kin/style.kin` (`emon.style`, P4.2) and `kin/heading.kin` (`emon.heading`, P4.4) read the text layer's `words`, `lines`, `etext` and the glyphs' fill colour, write only into the caller's scratch, and leave the evidence alone. `st-build` gives every line a row of features (dominant size, style bits, colour, code points, first and last, capitals, the nearest line above and below that overlaps it across the page, the whitespace to each, siblings on its row, the left margin of the body under it, its indent in ems, centred or not) and clusters the upright lines into classes by (size bucket of 0.4 pt, bold, italic, mono, capitals, colour); the class of most CHARACTERS is the body; the document's line gap is the lower quartile of body-to-body whitespace (D135's reasoning). `hd-analyze` writes `hc`: a header, then per line the level (0: not a heading), where the number is in the line's text, how much to skip to reach the title, the scheme and flags. `kin/para.kin` gets the minimum: `pa-build` takes `hc` as a last argument (EMPTY means no headings and the old behaviour: `paratest` and `test/para` are untouched), `pa-new-par` and `pa-continues` ask `hd-opinion` (a heading is a block of its own; lines of one heading join), `pa-flush` makes a `heading` or `paragraph` under `hd-parent` and `pa-milestone` puts the milestone in the innermost open section. The neighbours of a line come from horizontal overlap, not from a reading order, so P4.1's columns need nothing from this stage and it needs nothing from them. SPEC 8.4.5 column 18 (size over the body size) is `st-feats-size`; no text-layer word has a `feats` row yet, so it writes nothing today. Every threshold is a named `defconst`.
**Why**: the inventory must be document-wide and the paragraph builder is line by line; one array per line is the narrowest hand-over that does not make `para.kin` know a heading's rules, and it is the one P4.1 and P4.10 merge against with a signature change and four lines.
**Alternatives**: a post-pass over the finished Model (cannot split a paragraph that swallowed a heading, and re-parenting is not in the Model API); flags written into the evidence `lines` rows (the layout table has no spare cell and the dump checks that).
- [ ] **Signed off** (maintainer)

### D168 — What is a candidate heading, and the numbering patterns (P4.4; D018, D108, D109)
**Decision**: a line is a candidate when it is upright, not a bullet or a symbol-font glyph, not monospaced against a body that is not, has a letter or is not ASCII, and its style stands out of the body's (bigger by 4 per cent: 3 points, by 20: 4; bold 3; capitals 2; italic 1; another colour 1) or it starts with a numbering pattern. A line SMALLER than the body is a candidate only with weight or capitals (the maintainer's h-inverted has a 9.5 pt capitals top level against an 11 pt body) and only if text follows within 5 line gaps or it is numbered (a label inside a seal or a figure is neither). Lines of one class that follow tightly are one UNIT (at most 4 lines, 160 code points); a unit is kept at score 4: style points, numbering (dotted depth 2 or more 4, else 2), the whitespace around it (more above than below counts), at most 80 code points, followed by body text. It must not end a sentence or a clause (`. , ; ! ?`), must not end in a number unless numbered (`Opening balance 4,210.55`), and with italic or colour alone it needs a number as well. A line in the body's own style qualifies only if numbered, set apart, followed by more than the line gap, and not in a run of numbered lines (a list: SPEC 10.4). ROW SIBLINGS (another line on its row) disqualify a line (a table cell; D108 initials) unless the siblings are a column of body text, or the line is set a fifth bigger than the body (a column's headline), or the two halves of one heading line (a bare number and its title with a wide gap: `h-numbered`, the paragraph stage then joins them). One or two code points beside anything, and a short single word near and much taller than text that goes on in lower case, are initials (a STOPGAP: P4.10 finds initials properly and this must not stand in its way); a letter alone on its row is a divider and a heading. NUMBERING is locale independent: `1`, `1.2.3` (components of up to 3 digits, 8 deep: a year is not a number), `I.`, `A.`, `a)`, `(iv)`, `§ 4.1`, and a capitalised word then a number or numeral or, with a colon or at the end of the line, a capital (`Chapter 3`, `Article 1.1`, `Kapitel IV`, `Appendix A:`): no keyword table, so no locale hint is needed for the nesting that the dotted depth and the sequence give; the locale tables of P4.8 may add keyword RANK later. A letter or roman marker that is alone of its style and scheme is dropped (`A. Author`, a name). A unit whose text, digits and case aside, sits at the same place on two other pages is a running header and not a heading (P4.3 does that properly). FONT FAMILY is not a feature (Skia gives one face several font rows; the name table is P4.7's).
**Why**: each rule was found on the 55 synthetic documents and on real arXiv papers and an FOMC report; each has a law or a document that fails without it.
**Alternatives**: a hard length or style gate (loses the smaller top level); keyword tables without a hint (SPEC 10.3 step 3 forbids it); the font id as a style.
- [ ] **Signed off** (maintainer)

### D169 — The level assignment: classes, five penalties, a search (P4.4; D018)
**Decision**: units are grouped into heading CLASSES by (style class, numbering key, indent, centred); an unnumbered class takes the numbered class of its own style when there is exactly one; at most 16 classes. A class's numbering key is the dotted depth (a bare `1.` counts as depth 1 only if the document has dotted numbers), else minus the scheme. The assignment minimises an integer penalty over the whole document: numbering depth mismatch 20 per level per class; an external prior 20 x weight/1000 per level per class; a level skipped on the way down 4 per level per occurrence (the document's first heading is below level 0); a class appearing before a class assigned above it 4 per occurrence (the document's order of first occurrences is its outline's); two classes at one level 2; two classes of one style apart only in alignment or indent at different levels 2 (twins: `align-paragraphs` centres one h2); a bigger or bolder class assigned deeper than a smaller one 1 per pair (the weakest signal, by design: it only decides what nothing else does). Exhaustive up to 6 classes (the first assignment found wins a tie), coordinate descent beyond, 30 sweeps at most. Units are ordered by page, top, left: reading order from P4.1 will replace that for columns.
**Why**: `h-inverted` has its level 1 smaller than its level 4's: only the sequence (it opens the document and the others come after) and the absence of an order that lets a smaller class be a child can say so.
**Alternatives**: ranking by size (wrong exactly where the maintainer said); per-line decisions (inconsistent across a document).
- [ ] **Signed off** (maintainer)

### D170 — The Model of a heading: `level`, `number`, `numbering`, text without the number, and sections (P4.4; D018, SPEC 3.2, 10.3 step 6)
**Decision**: a heading node has `sub` = its level, the title as its text and, when the line began with a pattern and something follows it, the number (`1.2.3`, `IV`, `A`, `Chapter 3`: brackets, dots and colons left out) as the attribute `number` and its scheme as `numbering` (`decimal`, `upper-roman`, `lower-roman`, `upper-alpha`, `lower-alpha`, `keyword`, `section`), as the synthetic gold has them. Each heading opens a `section` of its level under the section that remains when the sections of its level and deeper are closed; blocks and page milestones go in the innermost open section; the stack lives in `hc`'s header. A level skipped goes straight under the nearer parent. The synthetic gold's numbering agrees on all 8 documents `kin/scripts/heading-check` holds.
**Why**: the gold, the SPEC and the HTML (`data-emon-number`) already say so; ids stay pre-order because sections are made when their heading is.
**Alternatives**: keeping the number in the text (it scores below the match threshold on `h-keyword`).
- [ ] **Signed off** (maintainer)

### D171 — Priors, what is not here yet, how it was measured, and what it leaves (P4.2, P4.4; D034, D121)
**Decision**: external priors are rows (text, level, source, weight per-mille) added with `hd-prior-add`; `hd-prior-outline` turns P3.9's outline rows into them (level depth + 1, weight 800). Tagged-PDF H1-H6 (P3.7, `pdfstruct.kin`, D148-D151) and the Engine (P1.10) are NOT on main yet: nothing calls `hd-prior-add` with a tagged role, and the synthetic corpus has neither outline nor (in `pdf`) tags, so priors are held by laws on hand-made evidence only; when P3.7 lands its trust score is the weight. A detected table of contents is not done. Measured with `tools/accuracy/predict-naive.sh` (the temporary predictor, extended with the analysis; not in the scoreboard), 55 synthetic documents, pdf variant: heading P/R 0.000/0.000 before, 0.951/0.959 after; level accuracy 0.967, near 1.000, end to end 0.928; the `headings` family 1.000 on every metric (h-inverted, h-inverted-numbered, h-div-samesize, h-keyword, h-numbered, h-plain), block P of paragraphs there 0.46 to 1.00; `initial-false-heading` 0.000. The remaining errors are not this stage's: columns' order (P4.1) costs 0.1 to 0.4 on five documents (the headings are all found, in the wrong order), letter-spaced capitals read as `A N N U A L` (the text layer, P1.7), a Devanagari conjunct (P1.7), letterhead and logo text that the layout stage will call a figure (`bank-statement`, `figures-logo-screenshot`), and `columns-uneven`, whose gold has an h3 with no h2 above it.
**Why**: the interface exists and is tested so P3.7's merge is one call.
**Alternatives**: none worth listing.
- [ ] **Signed off** (maintainer)

### D172 — The heading analysis runs in both Engines, before `pa-build`; where the notes and the milestones go; the priors are the follow-up (P4.4; D167-D171, D144, D147, D158)
**Decision**: P4.4 was written against the temporary naive predictor, which P1.10 retired, so the wiring is the Engine's: `attempt()` of `host/rust/src/engine.rs` and of `host/go/engine.go` call `hd-analyze` (scratch `sf sc hc ws tmp`, priors `pr ptxt` EMPTY) on the evidence's `wd ln et gl` and hand `hc` to `pa-build` as its last argument; both are byte-identical (the slice, identity and repeat gates). `pa-build` of P4.1 (D166) is two-phased and has notes and fragments; the heading analysis is keyed by the line rows of the document's `ln` (the column stage copies it into `lt` cell for cell, `lc-copy`, and only splits in the copy, so a split piece has no row in `hc` and is a paragraph, which `hd-field` answers for any row it does not have). Every fragment row of `fr` remembers its first line (`FR_STRIDE` 7). A body block is made under the open section (`hd-parent`, which for a body block is `hd-cur`); a MILESTONE and a NOTE are made in the open section too, where P4.1 made them under the root: a root-level child made while a section is open would take an id before the section's later children and break the pre-order the Model requires (`mc-not-preorder`, found on `align-paragraphs`). A note's own paragraphs never are headings (their parent is the note, not the root). A continuation (`pa-join-score`) is refused for a line of a heading on either side (-100 points: a heading is a block of its own). The goldens of `test/para` are now the Engine's whole output, headings and sections included, so `kin/scripts/para-check` counts a heading as a block, with its number put back and compared by letters and digits; `kin/paratest.kin`'s real-document law runs the analysis as the Engine does (its hand-made laws keep an empty `hc`: paragraphs alone, which is also the proof that an empty `hc` changes nothing); `kin/scripts/heading-goldens` and `para-goldens` use the Engine's driver (`emon-rs`).
**Priors are a FOLLOW-UP, not wired**: `hd-prior-add` and `hd-prior-outline` exist and are tested on hand-made evidence, but neither source reaches the Engine. The tagged roles are `tg-read`/`tg-join` (`pdfstruct.kin`, P3.7: they need the xref index, `pdfmeta`'s header, the glyph table and the marked content, and their answer is per glyph run with a trust score, not heading TEXT rows), so turning them into `hd-prior-add` rows (text of the run, level, weight = the trust score per D034/D150) is its own piece of work in `attempt()` of both hosts, with the structure tree's budget and its own miss handling; (CORRECTION, D177: P3.9 is on main, `pm-run`'s `ol` rows are the outline, and its prior is now wired.) The synthetic `pdf` variant has neither tags nor outline, so the scoreboard cannot show the gain yet; `pdf-tagged` gets its headings from the style analysis alone (0.0 to 1.0 on `pdf-tagged/headings`).
**Measured** with the real `emon` predictor (the Rust Engine), `bb accuracy`, 160 documents: `pdf/all` heading P/R 0.986/0.982 (was 0 before: the Engine made no headings), level accuracy 0.963, near 1.000, end to end 0.946; every `pdf-tagged` and `headings` metric rose. ONE REGRESSION beyond the tolerance, and it is a trade the analysis cannot see: `bank-statement`'s masthead (`<div class=bank>`, printed bigger than its `<h1>`, a paragraph in the gold) is a heading of level 1 and the real `<h1>` level 2, so `block-paragraph-r` falls 0.9635 to 0.9609 on `pdf/all` and 0.714 to 0.571 on `pdf/forms` (one block of seven). This is what D171 named (letterhead the layout stage will call a figure, P4.5 or the furniture stage); it is not hidden: the scoreboard was NOT updated.
**Why**: the one place that calls the stages is `attempt()` (D144), so that is where an analysis goes; a root child made while a section is open is the one thing the Model's order forbids.
**Alternatives**: a post-pass (D167 rejects it); keeping notes under the root and reserving their ids (the Model has no way to).
### D173 — P4.3: running furniture is found BEFORE the paragraph stage and written AFTER it; a line is taken out of `lines`, not marked
**Decision** (P4.3, SPEC 10.2, D017). `kin/furniture.kin` (`emon.furniture`) has two entry points the Engine calls around the paragraph stage: `fu-find` (reads the text layer's `words`, `lines`, `etext`, `glyphs`, `marks`, the Model's page sizes, P3.9's `/Title` and `/PageLabels`, the tagged tree's trust, the setting) and `fu-write` (page labels, the dropped-items ledger, the `furniture` roots, the `label` of every page milestone). **`fu-find` removes the furniture's lines from the `lines` arena** (the rows are compacted in place, order kept), so the paragraph stage (P1.8), and P4.1's gutters after it, never see a running header or a page number and are not told about them: nothing in `para.kin` knows this stage exists. The evidence dump is taken before, so `--out=evidence` still shows every line. `fu-write` runs after `pa-build` because the Model's furniture roots (parent -1) must come after the body in id order (D011's pre-order check), as the gold's do. The Engine's `attempt` now also runs `px_index_build`, `pm_run` (P3.9) and `tg_read`/`tg_join` (P3.7) before the Model is made (the driver's wall time per small fixture is 6-7 ms, `bb slice`; the 152-page Berkshire report converts in 1.5 s), because the `/Title`, the `/PageLabels` and the tree's `trust` are inputs (`tg-trust` in `kin/pdfstruct.kin` is the one accessor added to a neighbour). **The setting** `furniture: policy|keep|drop` (SPEC 13) is `Options::furniture` (Rust, `--furniture=` on the driver): `keep` takes nothing out (the labels of `/PageLabels` still come out), `drop` takes out what policy would keep too (the ledger says `running-title`).
**The signals**, none believed alone (D034), every threshold a named constant at the head of the file (first values, calibrated by P10.1): (1) **position**: an upright line whose centre is in the top or bottom 11 % of the page (a sideways one in the outer 12 % of the width) with 1 to 160 characters; (2) **recurrence on a template**: the text with each digit run replaced by `#` and each roman-numeral page number by `$` (a roman numeral is one only on a line that is page words, numbers and numerals: `mix` and `I` are words), a 64-bit FNV-1a hash, grouped by (zone, hash) and then by vertical position (a chain within 1.5 % of the page height); per group the **slots** (the abstracted numbers) are classified over the members in page order: the same on every page, **follows the page** (rises by the page step in at least 3 of 4 consecutive pairs: the PAGE slot), or varies; (3) **isolation**: the next line toward the inside of the page is at least 1.5 times the line's height away (2 times for the style rule), counting only lines that do not themselves recur, which is what keeps a table's repeated header row (followed at once by its first row) out; (4) the tagged tree (D175); (5) **coverage**: a recurring template must cover 30 % of the pages (3 pages for a page number), or the tree must say so; (6) **style and position** for text that varies (a chapter title, an odd/even pair, `Handbook` on even pages and `Employee Handbook` on odd): lines in the tighter 8.5 % bands of the same size (to half a point) at the same vertical position on at least 60 % of the pages, isolated by 2 line heights on 70 % of them, no larger than the body text (the most common line size outside the candidates, plus 3 %), a caption (`Table`, `Figure`, ... first word) never; on a ONE-page document a line of this kind that is smaller than the body is furniture too (there is no recurrence to find). A diagonal line (at least 3 degrees off every right angle) at least 2.5 times the body size and at most four words is a **watermark** (ledger `watermark`; the sharper evidence is P3.5-P3.7's).
**Why**: the maintainer's rule (page numbers, headers and footers are dropped only when they are page numbering and a title) needs the decision to come before layout analysis, or the paragraph stage cuts a running header into the first paragraph and the gutter finder reads it as a column; taking the lines out is the only hook that needs nothing from the stages around it. Hand-set thresholds with a name each are what P10.1 can move; a learned model is parked (SPEC 1.1).
**Alternatives**: marking lines and having every later stage skip them (every stage would have to know, and P4.1 and P4.2 are being written concurrently); doing it after paragraphs (a paragraph that contains a header cannot be split again); a single position-only rule (a table's header row at the top of each page is dropped).
- [ ] **Signed off** (maintainer)

### D174 — P4.3: what a furniture line is made of: the page number, the page words, the total, what remains; and what is done with each part
**Decision** (SPEC 10.2 steps 2 and 3). A template group is **decomposed** into tokens (a token is a run between spaces; its CORE is the token without the brackets, dashes, quotes, bullets and slashes at its ends): out go the token of the **page slot**, a **page word** (`Page`, `Pagina`, `Seite`, `Strona`, `Sida`, `p`, `pp`, ...) next to it, an **"of" word** (`of`, `de`, `von`, `sur`, `di`, `van`, `av`, `z`) before a number and that number, the **total**, and every token that is only punctuation (the tables are `kin/furniture.kin`'s `fu-pw`, `fu-cw`, one flat array each, in D107's style). A number is a page number only as a WHOLE TOKEN (`3`, `iii`, `3/10`, `K-3`: one or two letters, an optional `-` or `.`, digits): **`ACME-01` is an identifier, not a page number** (the synth corpus's court filing: its gold keeps it as furniture `ACME-{n:02}`), and a number inside a longer token that equals the page index becomes the template `{n}` (`{n:0W}` when every member is zero-padded to W digits), the spelling the gold and the scorer already use. **What remains** (R): (a) nothing: a page number, dropped (`page-number`, the whole line's text in the ledger), the number kept as the page's label; (b) R is the Info `/Title` or is, letters and digits only, a line of the body or two or three consecutive lines of it (a title that wraps; the heading that a running section title repeats; 64-bit hashes of at least 4 letters and digits, over every line that is not itself furniture): the output already says it, so it is dropped (`running-title`); (c) anything else: **kept**, as ONE `furniture` root with its template, role (`header`/`footer`/`margin`), the regions and the pages when the members are the same beyond the page (`CONFIDENTIAL`, `Case 1:21-cv-00123-ABC Document 14 Filed 05/03/21`, a company name, a form number), or one root per page when they vary (a Bates number that skips); roots with the same role and text found at different heights are merged into one (`fu-merge-furniture`). In the mixed case (`7 September 17-18, 2024`) the number is the label (ledger `page-number`, the number only) and R is judged as above. A line seen on one page of many, a line that is a caption, and a template that follows no page are never furniture.
**Why**: it is D017's principle ("drop what the output already says, keep once what is the same, keep per page what varies, page numbers become labels") made into steps a program can take, and each step is checked against the synthetic gold (`furn-informative`: header, confidentiality line and `ACME-{n:02}` kept, `Page N of M` dropped; `furn-title-pagenum`: title and page number dropped; `furn-odd-even`: even header dropped, odd header kept).
**Alternatives**: treating `ACME-01` as a page number (loses the identifier, the gold says keep); dropping every recurring line (loses Bates numbers and confidentiality legends: D017's reason for existing); keeping a running title that the body repeats (the output says it twice).
- [ ] **Signed off** (maintainer)

### D175 — P4.3: page labels, and the tagged tree's word weighed by its trust
**Decision**. **Labels** (SPEC 3.7, D121): when the catalogue has `/PageLabels` (`md[54]` ranges) they are every page's label (P3.9's `pm-label-cells`, the range's prefix and style), and the printed numbers are still taken out of the flow; otherwise a page's label is the number AS PRINTED (`iii`, `03`, `K-3`) of the best template group that has one (the group's score picks; none is inferred for a page that prints none: `furn-odd-even` page 1 has none, like its gold). The `label` of every page milestone (`break`) is set from the page's label. **The tree** (D034, D076, D150): a line's glyphs are looked up in the marked content: an `/Artifact` in place (Pagination; Header in the top zone; Footer in the bottom zone) is evidence FOR, an artifact out of place is ignored (the artifact check of D149 does the same), a glyph inside marked content that has an MCID is evidence AGAINST; the share of a group's members that is for, minus the share that is against, times `trust` (the informativeness score, 0..1000, raised a tenth by a PDF/UA claim: `tg-trust`), times 0.6, is added to the recurrence score (300 for one page, 500 + 150 per page up to 1000) and 600 qualifies: a trusted tree can qualify a group seen on one page (its page number, when the line has the shape of one) and a trusted tree that says the recurring line is content (`P`) keeps it in the flow; a distrusted tree moves nothing (`bad.pdf`, whose running header is tagged P and whose score is 113, is still cleaned up by the recurrence).
**Why**: citations use the printed label ("p. iv"), and the catalogue is the author's statement of it; the tree is evidence to be weighed (D034), and what it is weighed by is what D150 computes.
**Alternatives**: believing `/Artifact` always (a mis-tagged file loses body text); ignoring the tree (a page number on a one-page document, the only evidence, is lost); inferring a label for a page that prints none (a guess that the scorer would count as a label).
- [ ] **Signed off** (maintainer)

### D176 — P4.3 is verified on the synthetic gold, by laws on hand-made evidence, and by eye on the real corpus; what it leaves
**Result**. `kin/furnituretest.kin`: laws on hand-made evidence (page numbers, `Page N of M`, `n/m`, roman then arabic, a word that is a numeral, a title the body says, a company name kept once, a court filing, a number beside a title, a line on one page, a table's header row, a number that does not follow the pages, odd and even, a watermark, `/PageLabels`) and the tagged fixtures read whole (`good.pdf`, `bad.pdf`, `keep`). **`bb accuracy` over the 55 synthetic documents, `emon`**: `furniture` P 0 -> 1.0, R 0 -> 0.913 (the 5 `duplicate-header-row` rows are P5's), F1 0 -> 0.955; `page-label-acc` 0.707 -> 1.0 (on the furniture family 0.364 -> 1.0); `watermark-leak` 0.00201 -> 0; CER 0.1842 -> 0.1761 and WER 0.2420 -> 0.2332 over the `pdf` variant (the furniture family: CER 0.0231 -> 0 and WER 0.0215 -> 0: its text is the gold's). `furn-informative`, `furn-title-pagenum`, `furn-fixed-dom`, `furn-odd-even`, `invoice`, `bank-statement`, `footnotes-page` and the three multi-page tables match the gold's furniture, ledger and labels row for row (`tools/synth` + the scorer; the multi-page tables lost 20 false rows to the isolation rule: a table's rows at the top and bottom of a page are not a header). **The real corpus, by eye** (no gold): the Berkshire 10-K report (page numbers 1-19, then `K-1`...`K-118`, `A-1`: 143 of 152 pages labelled; the cover and the inserts print none), the GAO report (`Page i`, `Page ii`, `Page 1`...: 41 labels; the report number and title kept once on 41 pages; the cover's agency name dropped because the body says it), the FOMC minutes (`2 September 17-18, 2024` and `Minutes of the Federal Open Market Committee 3` alternating: 16 labels, the date and the title dropped because the first page says them), the Berkshire letter, the eight arXiv papers (ICLR header kept once; `Learning Transferable Visual Models...` dropped 47 times), 199 CUAD contracts (the `Source: COMPANY, FORM, DATE` footer on every page kept once, `Confidential` kept), and 600 of the PDF/A sample (none panics; every drop is a page number or text the body says). **What it leaves, named**: (1) a header seen on one page of a short document (`Employee Handbook` on page 3 of 4 is found only because the style rule sees it with the other pages' headers); (2) a page number set in words, or in a script the page-word tables do not have (CJK `第3页`), is kept as furniture, not turned into a label; (3) furniture inside a table (a tax table's column headers repeated at the top of 33 pages, `i1040gi.pdf`) is dropped as `running-title` when the body says it elsewhere and otherwise moved to an aside: P5 owns those (they are `duplicate-header-row` there); (4) `/PageLabels` that disagree with the printed numbers are believed over them; (5) visibility (P3.5/P3.6) is not merged: a clipped or white header is judged by position and repetition only; (6) the Go Engine (D158) is not changed by this entry: `fu_find`/`fu_write` and the three catalogue calls before them are the Rust `attempt`'s, and the Go Engine's merge owes the same calls.
**Why**: accuracy is paramount; a stage that drops text must show on the gold that it drops only what the gold drops, and by eye on real files that it does not drop content.
- [ ] **Signed off** (maintainer)

### D177 — The furniture stage in both Engines, masthead (kicker) demotion, and the outline prior wired (P4.3, P4.4; D172, D173-D176)
**Decision**: (1) ORDER in `attempt()` of both hosts: `px-index-build`, `pm-run`, `tg-read`, `tg-join` (the catalogue's Info and `/PageLabels`, the tagged tree's trust: D076), then `fu-find` (takes the furniture's lines out of `lines`), then `hd-analyze` (so headings never see a running header or a page number, and the heading analysis's one-line `hd-recurs` guard becomes the fallback it was meant to be), then `pa-build` (columns and paragraphs: furniture is gone before the column stage's gutters are found), then `fu-write`. The Go Engine has the same calls and `Options.Furniture` (`FurniturePolicy|Keep|Drop`, `--furniture=` on both drivers); `bb identity`, `repeat` and `embedders` hold the two to one answer. Visibility (P3.5/P3.6, D155) is NOT used by `fu-find`: it reads lines and words and does not look at the glyphs' `vis` classes; ignoring `white-on-white`, `clipped` and `offpage` text in the recurrence and the watermark rule is a follow-up, to be measured on the watermark documents. (2) A MASTHEAD IS NOT A HEADING (`hd-kicker`, `KICKER_RATIO` 110): an unnumbered unit set directly above (the next line, same page, no text between) a unit whose size is more than 110 per cent of its own, where both styles are set ONCE in the document, is dropped from the units before the classes are made. `bank-statement`'s `<div class=bank>` (15 pt, green) over the `<h1>` (18 pt) was heading 1 and pushed the real `<h1>` to level 2 (`block-paragraph-r` 0.9635 to 0.9609 on `pdf/all`, 0.714 to 0.571 on `pdf/forms`). The 'once' clause is what keeps `initial-letter-headings`: a title above recurring letter dividers that print larger is a title (the larger style recurs: a rank of the document's own). Signals not used and why: colour (every colour is unknown in hand-made evidence and the masthead may share the body's), repetition as furniture (the furniture stage now says it, but a letterhead on page 1 only recurs nowhere), a different typeface (the style classes have no face yet). (3) THE OUTLINE PRIOR is wired: `hd-prior-outline` over `pm-run`'s `ol` and `txt` fills `hpr`/`hptxt` before `hd-analyze` in both Engines; the tagged roles stay a follow-up (D172). The synthetic corpus has no outline, so the scoreboard cannot show the gain.
**Measured** (`bb accuracy`, 160 documents, all gates green): `pdf/all` CER 0.0118, WER 0.0208, heading P/R 0.995/0.982, level e2e 0.959, furniture P/R/F1 1.0/0.914/0.955, `page-label-acc` 1.0, `watermark-leak` 0, paragraph-boundary F1 0.189, `block-paragraph-r` 0.966 (was 0.9635 on main before D172's regression and 0.9609 after); `pdf/forms` `block-paragraph-r` 0.714.
**Why**: each stage takes its input from the line table, so the removal of furniture must be the first.
**Alternatives**: marking furniture lines instead of removing them (every later stage would have to learn it: D173); a masthead rule by position (page top) alone, which a document's real title also satisfies.
- [ ] **Signed off** (maintainer)

### D178 — Tables are found before the paragraphs, their lines are claimed in the lines' spare cell, and `pa-build` places them by their tops (P5.1; SPEC 10.5, D019)
**Decision**: `kin/table.kin` (`emon.table`) runs between the text layer and `pa-build`, in `attempt()` of both hosts, over the evidence's `words`, `lines`, `etext` and `paths` (`pa`; the exact `pts` are not needed: a rule is a thin box, which holds for filled rectangles and for stroked lines, whose box is grown by the stroke). Its output is five arenas (`tb` tables, stride 24; `tc` cells, 16; `tw` the words of each cell; `tq` caption lines; `tx` column boundaries) and ONE WRITE TO THE EVIDENCE: the spare cell 15 of every line a table holds is the table's index + 1 (`tb-claimed`). `pa-build` takes `tb tc tw tq ledger`: the column stage still sees every line (so what it makes of the rest of the page is what it made before; a table's lines in it are skipped where paragraphs are made, `pa-live`), a table is emitted (`tb-emit`) before the first line or fragment whose top is below its own, closing the open paragraph, and after the last of a page. A table node is `table > [caption] rowgroup(head|body|foot) > row > cell`, `profile` `columns` `continued`, cells with text, `rowspan`/`colspan`, `header` (`col` in the head, `row` in the foot), `align`, `empty`, regions on every fragment; `src` text, infer (and vector for ruled), `conf` 600 (calibration is Phase 10).
**Why**: a table must not be read as columns (P4.1's note 8: cells of three words with a gap under three sizes read as columns) and its text must not be paragraphs; deciding first is the only order in which both hold. The first attempt removed the claimed lines before the column stage; it changed what the stage made of a page that is mostly table (the page header of `bank-statement` became a margin `note`, a regression of `block-note-p`), so the stage keeps its input.
**Alternatives**: tables as pseudo-lines in the line arena (the column stage's splitting would meet them); post-processing the Model (ids are pre-order, nothing can be inserted); a `claimed` array passed to every stage (the spare cell is the one place every copy of the lines, including the column stage's, already carries).
**Integration with furniture and headings** (merge): `attempt()` of both hosts runs `fu-find`, then `tb-build`, then `hd-analyze`, then `pa-build` (which takes `hc` and then `tb tc tw tq ledger`), then `fu-write`. Furniture goes first so a running header never becomes a table row; the table's claim (spare cell 15) is made before the heading analysis, which treats a claimed line as a cell and never a heading (`hd-headish`) but still as the neighbour of the heading above the table (leaving the neighbour out lost `Schedule A` over `table-consecutive-distinct`'s first table). `tb-emit` takes the parent: the open section (`hd-cur`) when headings are on, so a table sits in the section of the heading above it (a table at the root of a document with sections broke the pre-order). A repeated table header row is dropped to the ledger by `tb-emit` and is not a furniture candidate (it sits in a table's claimed lines; furniture ran first and found none on `table-multipage-*`: the page numbers only). The goldens of `test/table` are made with `--furniture=keep` (the laws run no furniture stage).
**Cost**: the Rust and Go `attempt()` and `pa-build` take nineteen scratch `Cells` for the table stage and `tb-build` a page table; a stage after P4.1 that rebuilds `pa-build` must keep `pa-live`, `pa-tables` and the three `tb-due` checks.
- [ ] **Signed off** (maintainer)

### D179 — The ruled profile: rules, components, a lattice, open edges are spans, and a lattice must be mostly text (P5.1)
**Decision**: a RULE is a path that is filled or stroked, not a clip, pattern or shading, visible (alpha), not near white (mean channel under 240), with a box at most 4 pt thick and at least 5 pt long; a stroked rectangle larger than that is its four edges; a filled rectangle larger than 5 pt each way is a FILL. Positions are clustered (2 pt), collinear pieces that touch (3 pt) are one rule (Chrome and Word draw a border cell by cell), horizontal and vertical rules that meet (3 pt) are one component (union by the smaller root), a page with more than 1,500 rules is a drawing and has none. A component with two rules each way gives the boundaries (the distinct positions of its rules and the extents of the other direction, clustered at 4 pt), and the grid between them: an EDGE between two grid cells is closed when rules cover 3/5 of it, open otherwise, and the cells an open edge joins are one cell (rows of a grid cell are scanned, a cell extends right while the edge is open, then down while the whole bottom edge and the inner edges of the new row are open). Words go to the cell that holds their centre. The lattice is refused when it is not mostly text: fewer than four cells with text, or under two fifths of its cells, or three tenths of the cells with text holding two chunks (a gap over 6/10 of the size inside a cell: too few rules, the columns are in the text, `partial` and `whitespace` take it). Score 900.
**Why**: Chrome's border is a 1 px filled rectangle per cell edge; Word's a stroked line; LaTeX's a filled rectangle; all three are a thin box. A figure's axes and boxes make lattices too; the text test is what tells a drawing from a table (`figures-charts`).
**Held to**: 8 synthetic table documents, two ruled with spans, and a survey of 88 public PDFs (no panic; 3 panics found and fixed in the survey, a sort index and a bound).
- [ ] **Signed off** (maintainer)

### D180 — The text-based profiles share one machine: rows, chunks, channels, columns; candidates are scored and the best of a region wins (P5.2)
**Decision**: the words of a region are cut into baseline ROWS (words whose baselines are within 3/10 of the size), each row into CHUNKS where a gap exceeds 6/10 of the row's size (`SEG_NUM/SEG_DEN`); the chunks of the tabular rows (two or more) are projected on the x axis and a CHANNEL is a stretch of at least 6/10 of the size that at most one in six of those rows has a word over (a spanning cell is the exception the table has); the columns are between the channels; a chunk goes to the column of its first word's centre and spans to the column of its last. Rows of one table row: each baseline row, except a CONTINUATION (a pitch of at most 1.35 of the size and 4/5 of the mean pitch, no spanning chunk, only columns the row above had, at most half of them), which wraps the cell above. Candidates: `partial` (chains of horizontal rules of one extent within 300 pt of each other, a candidate for the whole chain and for each pair of consecutive rules, the tabular rows directly above the first rule being the header), `shaded` (fills of one top and bottom make a band; three or more bands of one extent, each starting where the one before ended or a row later, with two colours or a gap, are a stack; a band is a row, a baseline row in no band is a row of its own), `whitespace` (runs of rows of two chunks or more, a single-chunk row between two of them allowed, at least three such rows). SCORES: ruled 900; partial 800 + 90 x the share of tabular rows; shaded 750 + 40 x that; whitespace 400 + 100 each for three columns or more, a numeric column, a header, plus 50 for four rows and 100 for two columns of text and numbers with a short label (a statement's summary), at least 600 and at most 750 (it has no rule or fill to vouch for it). Refused for all of them: under four cells with text, over 3/10 of them starting in lowercase or ending in a hyphen (two words or more), more than two fifths of them ending a sentence, over a fifth of them longer than eight words, a grid less than 35% full (55% for whitespace), average words per cell above 8 (4 for whitespace). Overlapping candidates: the best score keeps the region (`tb-select`).
**Why**: this is SPEC 10.5's table of evidence made a program, and the thresholds are what two failures cost: a five-column newspaper page was a whitespace table (cells of four words, half of them empty, lowercase), and a gridded diagram was a ruled one.
**Limit**: superscripts and subscripts are rows of their own (their baseline moves), so a header like `O(n^2)` splits; a table whose cells wrap over several lines in a compact setting is read row by row unless the continuation rule holds.
- [ ] **Signed off** (maintainer)

### D181 — Header, foot, spans of header cells, alignment and captions (P5.3; SPEC 10.5, 10.10, D110)
**Decision**: HEAD ROWS: the leading rows whose non-empty cells are all bold, if that is at most half of the rows and not all of them; else, with three rows or more, a first row with no number over a column below it that is four fifths numeric (a column: three cells or more). FOOT: the last row when it is bold, the one above it is not, and two body rows remain; its cells are `header: row`, the head's `header: col`; a table that goes on to the next page has no foot (D182). Numeric cell: up to three words made only of digits and `, . - + % $ EUR GBP JPY ( ) / :` and spaces. SPANS OF HEADER CELLS in text-based tables (no rule says where they end): a header cell whose cell below is empty spans down; one followed by empty cells over cells with text spans across them. `align` (SPEC 10.10) is decided per COLUMN over its body cells' text extent with t = a quarter of the size: `start` when every start gap is within t and the end gaps vary by more than 2t, `end` the other way round, `center` when both gaps agree within t on every cell and the start gap varies; a column of equal widths, or of decimals that do not line up, has none; a header cell is judged against its cell box in a ruled table (padding included, which is where a browser centres it) and against the column's BODY extent otherwise, and is `center` or `end` only when it sits inside that extent clear of its edges by 4t. CAPTION: an unclaimed line over the table (or under it, if none is over) within two and a half line heights, overlapping it, that starts with a table keyword (`Table Tab Tabelle Tableau Tabla Tabela Tabella Tabel Tabell`, any case, a full stop ignored) and a number (a digit, or a capital and a digit); the lines on its baseline to its right and the lines under it that continue it are part of it; `label` is keyword and number without the stops (the gold keeps the stop: `Table 1.`), the text is what follows, a lone separator dropped.
**Held to**: `align-table-numeric`: `align-acc` 1.0, `align-wrong` 0; `block-caption` P/R 1.0 on the synthetic tables.
**Limit**: a header's alignment in a text-based table is the body's or nothing; a locale's `Total` words are not a foot (a bold last row is); decimal alignment is not detected as a property.
- [ ] **Signed off** (maintainer)

### D182 — Tables across pages: the same columns, the first and the last body block, no caption of its own, a header that repeats or none (P5.4; SPEC 10.5)
**Decision**: table B on page p+1 continues table A on page p when A is the last table of its page and B the first of its page, they have the same number of columns with every boundary within 1/60 of the width (at least 5 pt), no line outside the page's furniture zones (the top and bottom eighth) lies under A or over B, B has no caption or has A's label again, and B has no head rows or its head rows say, cell for cell and word for word, what A's say. B's repeated head rows are dropped to the ledger as `duplicate-header-row` (their box and text) at emission and A's table node takes B's other rows, `continued` is the number of pages spanned, A's foot is not a foot (it was a row before the break). Chains of three pages or more link tail to tail. Not done: a row split across the break (flagged `reconstructed`), a "(continued)" line that is not a caption of the table's own label, the page furniture zone as a decision (P4.3 decides furniture; the eighths are a stand-in).
**Measured** (not a gate; `corpus/_gold`): ICDAR 2013 (66 PDFs, human-annotated, 66 scored): `block-table` P 0.78, R 0.86, F1 0.82 (IoU 0.5), `table-teds-lite` 0.48, `table-teds-struct-lite` 0.68. cTDaR 2019 and FinTabNet-OTSL are image datasets: `emon-rs` refuses them as `unsupported-format` until P6.1's raster intake, so there is nothing to score. The synthetic corpus (`bb accuracy`, `pdf/all`): `table-teds-lite` 0.83, `table-teds-struct-lite` 0.89, `block-table` P 1.0 R 0.89, `table-continued-acc` 0.89 (the misses are `bank-statement`'s key/value `form` table, P5.5, and `invoice`'s two-column totals, which score under the whitespace threshold); the eight table documents themselves score 1.0 except the partial table's last row, whose gold spans three columns where the text spans two. The ICDAR failures that remain are a table taking the rows above it into its region, a header over a column whose first cell is empty (the gold has three cells, a grid has four), superscripts splitting rows, and chunks that touch. Survey of 88 public PDFs (ICDAR, gov reports, arXiv, derived): no panic, 3 to 17% of the words of a paper in tables.
**Held to**: `table-multipage-thead`, `-no-thead`, `-nohead-repeat` (TEDS-lite 1.0, `table-continued-acc` 1.0) and `table-consecutive-distinct` (two tables on consecutive pages that must stay two).
### D183 — Initials are found on the lines' copy, right after lines and before the column stage; the baseline of a drop initial is one of three places, and a rule rejects labels and statistics (P4.10; D108, D109, SPEC 10.9)
**Decision**: `kin/initial.kin` (`in-detect`) is run by BOTH Engines right after `lc-copy` (the lines' copy `lt`, which `pa-build` now takes
prepared) and BEFORE `hd-analyze` (P4.2/P4.4, D170-D172, which reads `lt` and not the evidence), so the style inventory, the heading
analysis, the column stage and the paragraph builder never see an initial as a line. A drop or margin initial's line is turned a quarter
turn (`rot` 9000: not upright, which the style and heading stages skip) and `in-retire`, the first thing `pa-build` does, takes it off its page
(page 0); `st-dom` skips a raised first word as `pa-dom` does. It reads words, lines,
etext, and for an initial inside a line the glyphs (`gl`) and fonts (`fo`), and rewrites the copy `lt`. **A line of its own** (what
Chrome's floats give: the whole synthetic corpus): a one-word line of at most 12 code points that holds a letter or digit (one
with a digit or a sign in it, `89%`, is a statistic and no initial), at least `initial-min` 2.0 times the page's median line size
(a pre-filter, so the work is not quadratic) and 2.0 times line 1's. Line 1 is the highest line that reads the same way, starts
after the initial's end edge (a quarter of its size of overlap, 1.5 sizes of gap) and has its cap top within half a size of the
initial's. N is the first line whose band holds the initial's baseline (a quarter size of slack), N >= 2; a DROP has exactly N
indented lines and line N+1 back at the start edge within half a size, or none; a MARGIN has every line at one start edge beyond
the initial's end edge and lines after its N. The initial's line leaves the page (`page` 0), lines 1..N of a drop start at its
start edge (so the indent rule and the column box see a paragraph at the margin), and line 1 is marked as the paragraph's first.
**In a line** (print: the initial stands on the baseline of its last line, and the text layer made it a glyph of THAT line, or of the
first line's first word with a later line's baseline): the leading glyphs (at most three, or the whole word) of a line's first word
at least 2.0 times the size of the glyphs after them, saying the text they say. On the line's own baseline with lines above it
that start after it, the highest of them with its cap top where the initial's is and the line below back at the start edge: a drop
whose paragraph begins at the highest of those lines, the initial's code points skipped in its own (cell 15 holds minus their
number: `pa-add-line`, `pa-first-cp`). On the line's baseline with none: raised, line 2 at its start edge. Below the line's baseline
(the initial and the rest of its word share the first line, the baseline is a later line's): a drop in the first line's first word,
lines 2..N after it, line N+1 back. In all three the line's box becomes the box of its other glyphs, and its baseline the
first of theirs (a line's baseline segment starts at its first glyph's origin, the initial's, and `0116458`'s first line was
`not below` its second). **Cap tops**: the font's `/CapHeight` when `fonts` has one, else 0.7 of the size for a cased script, 0.6 for
the other caseless scripts (Hebrew, Arabic, Indic, Thai) with the candidate's cap top allowed to lie 0.15 of its size above line 1's,
0.88 for CJK and its punctuation. **Rejected**: a big letter with nothing wrapping it (a letter divider: the line below is not
beside it); a big letter whose first word after it is in title case (`B` `Domestic and international`: a column of lettered labels,
`2983077`); a word with a digit or a sign (statistic callouts, `3794508`, `6994074`). **How the paragraph is told**: cell 15 of the
target line's row of `lt` (a spare cell of the evidence line stride; cleared at the start, cleared again in the new piece of a line
`lc-split` makes) is `(row of ii + 1) * 4`, plus 1 where the first word is partly the initial (it does not vote on the line's size
and style: `pa-dom`), plus 2 where the initial is its whole first word and joins the next with no space; `ii` is the table (stride 12:
form, lines, initial's line, target line, code points, box, word, join). `pa-ini-start` puts a separate line's text at the head of
`raw` and records the initial in the paragraph's first fragment row (`fr` is 10 wide now); `pa-flush` writes `initial` (`sub` the
form, `aux` the lines, `src` {text, infer}, `conf` 800, or 500 and `low-conf` when the join is in doubt) holding a `text` child, then the rest as
a `text` child, and for a paragraph on one page the paragraph's own `text` too, equal to the two (D052); on several pages only the
inlines' text, as before. The paragraph's region takes in the initial's box. A new inline never has `at` of its own.
**Why**: SPEC 10.9 puts detection before blocks because a heading stage or a paragraph builder that sees `O` as a line has already
broken what a detector would have kept; the copy is the only place a stage may rewrite lines (the evidence is not touched). The three
baselines are three producers' work: the first is Chrome's float, the others are what `0182448` and `0116458` were laid out with, and a
stage written on the first alone would have found none of the four real files of D108 but one.
**Alternatives**: detection after blocks (the paragraph builder has split the paragraph and a heading stage has made the letter a
heading); the glyph list for every line (a glyph read per line: only the first word of a line with a large leading glyph is read);
a separate scratch `im` map from a line to its initial (`lc-split` makes rows it does not index: cell 15 clears itself with the row);
reading `/CapHeight` in this item (the descriptor reader, its generated name table and every evidence golden would change for a fallback that
works: left to a reader that is P1.12's; the code reads the cell when it is set).
- [ ] **Signed off** (maintainer)

### D184 — Alignment as built: per line against the fragment box, a verdict with a confidence, a second pass by style class for the single flush-start line (P4.11; D110, SPEC 10.10)
**Decision**: `kin/align.kin`. `pa-build` records, for every line of every block (`pa-al-add`), the start and end gaps against the box of the
fragment the line sits in (`cf` x0 and x1, each from the line's own side: a right-to-left line is measured from the right, so
`start` and `end` are logical), its size and style bits, in the stream `al` (rows of 4), and closes the block with a marker row
(`al-mark`); `al-pass` runs after the last close. With t a quarter of the first line's size and a first-line indent taken out of line 1's start
gap when every other line starts at the edge: `justify` is at least three lines, every non-last line within t at both edges and the
last at the start; `start` every start within t and the end edge ragged (a non-last end gap above t, or a spread above 2t over the
non-last lines); `end` every end within t and a start spread above 2t; `center` |S - E| <= t on every line and an S spread above
2t. A single line is `center` (S and E at least 4t, within t of each other) or `end` (E within t and S at least 8t: an indented
line that fills its row is not right-aligned). The confidence is 400 + 100 for each line after the first that agrees (at most four) + up to
200 for the margin + 100 where the ragged edge is plain; `align-min` is 800 (named setting, `al-min`; Phase 10 calibrates). A single
flush-start line (S <= t, E > t) is the second pass's: its style class (size within a twentieth, style bits) is looked up in
the tally the multi-line decided blocks made: all `start` makes it `start`, any other value leaves it with none (it may be a justified
block's last line), a class with no multi-line block (a heading, a label) makes it `start`. At most 64 classes are tallied. The pass is
over the marked blocks of the whole stream, so a stage that retypes a paragraph runs it after: `node-set-align` writes `sub` of a paragraph and
`fk-align` of a heading, caption or cell, and refuses justify on a cell (D131).
**Why**: against the block's own box everything is justified (D110); the fragment box is what P4.1 makes. The single-line rule is the one
D110 calls for ("by its style class's majority"); a heading is never justified, so a class of single lines is `start`. The pass is
document-wide because a class is: the 20-point bold lines of `align-justify-hyphen` have no multi-line peer and are still `start` in the gold.
**Alternatives**: the verdict at each flush (the single-line rule needs the class of blocks that come later); one quarter-size tolerance for every
block (a one-line `end` that is a full row with an indent); a decimal value for cells (D110).
- [ ] **Signed off** (maintainer)

### D185 — Joining an initial: joined by default and flagged when the letter could be a word; English lists decide with a locale; a whole enlarged word is followed by a space (P4.10; D108, D109, SPEC 10.9)
**Decision**: `in-join` answers 0 (joined, sure), 1 (joined, `low-conf`) or 2 (a space). A one-letter initial that is a word of its own in the
locale's table is flagged: `en` A I O; `de` O; `fr` A À Y O Ô; `es` A E O U Y; `pt` A E O; `it` A E O È; `nl` A U; no locale, or a language without a
table: the union (`defdata in-one`). With `en` the first word after the initial decides where a list knows it: a word in the list of
what `A` or `I` is followed by (`long`, `few`, `was`, `have`...) is a space (`A long`, `I was`), a word in the list of what a
letter begins (`t`, `s`, `nd`, `nce`...) is a join and sure (`It`, `At`, `Once`); a word in neither is joined and flagged. The lists are
`defdata in-follow`, English only, small, and say so (`kin/initialtest.kin` has every case); a language without lists joins and flags.
A whole enlarged first word of two code points or more (`IT`, `ONCE`) is a word: a space follows, unless its script is written without
them (CJK); `“N` (an opening mark and a letter) is joined; a digit initial is joined. A raised or in-line initial that is only part of
its word has nothing to join; one that is the whole first word (`T` of `T HE BUREAU`) takes the same decision against the next word, and joined
means the line's text is read with no space after it (cell 15, bit 2). The locale reaches the stage as its code points, an argument
of `pa-build`; it was a setting that decided nothing.
**Why**: the page cannot say whether `A long` is `Along` (SPEC 10.9: the same 4.5 pt gap and no space glyph); joined is the better default
(`It`, `In`, `At`, `As` open more paragraphs than `I` and `A`), flagged says where it might be wrong, and with a locale the one place
a lexicon can help is the one the maintainer's novels have most. The union flags `E` and `U` too: a document with no locale gets more
flags than a document with one, which is what no locale means.
**Alternatives**: no lists (the default is correct for `It`, wrong for `A long` and `I was`, which a locale-aware reader of a novel meets every page); a lexicon
of the language's words (SPEC 17.4: the maintainer's call, still open); a space by default (wrong for the commonest openings).
- [ ] **Signed off** (maintainer)

### D187 — The `ImageDecode` port and raster intake as built: one `decode` request per frame, header-only for stage 0, one `image` page per frame, no text yet (P6.1; SPEC 2.0, 7.2, 7.3; D058, D074, D075, D089-D091, D125, D144, D158)
**Decision**: (1) **kin only sniffs.** `emon.rasterin` (`kin/rasterin.kin`) `rs-sniff` reads the first eight bytes of a `Src` and says PNG, JPEG, TIFF (II*\0 or MM\0*) or none; none is the PDF path's (which still answers `unsupported-format` to what is not a PDF). The Engines' unit is `dispatch` (Rust and Go): sniff, then `attempt_raster` for an image or the unchanged `attempt()` for the rest; `attempt()` is not touched, so the stages other agents merge into it do not meet this. (2) **Decoding is a request.** `rs-unit` asks, through plan/fulfil (D089), for frame 0 as `decode` request id 1 (`input offset length filter frame`: the whole input, `flt-none`, frame 0); the answer's `frames` says how many there are; round two asks for frames 1..n-1 at once (ids `f + 1`, kin's, derived from what is asked); then the pages are built. Two rounds, under `max-rounds` 3. The answer is `status w h channels dpi100 bilevel frames` with the size AFTER orientation; the pixels are the answer's bitmap (canonical PNG in a transcript, D125). A negative `frame` (`-f - 1`) asks for the HEADER of frame `f` only, no pixel decoded: stage 0 (`intake`: format `png`/`jpeg`/`tiff`, pages = frames) uses it, so counting the pages of a 300-frame TIFF reads 300 IFDs and no strip. A new answer status `ps-limit` 3 (arg: the pixels the frame has) is the host's PIXELS bound (`limit-of 9`, default 268,435,456, checked from the header before a pixel is decoded), which kin turns into the named limit `pixels` (`Error::Limit`), like every other bound; `ps-failed` is `undecodable` and `ps-unsupported` is `unsupported-image` (both `Error::Damaged`'s status, so a crawler routes on the name). (3) **The host reads the span from its byte source.** The fulfiller (`image_fulfiller` / `imageFulfiller`) is the live port for `convert` and `record`; `replay` answers from the transcript and calls no codec. TIFF is read by offset over random access (IFDs, then only the strips or tiles asked for: a frame's neighbours are not read); PNG and JPEG read the span whole (a streaming codec over a forward-only source is a follow-up). A conversion keeps the pixels in the answer only when it is recording; a plain `convert` decodes (so a damaged file fails the same way) and drops them. (4) **The page**: `image` kind, `w x h` pixels at `dpi100` are `w * 720000 / dpi100` centipoints (SPEC 3.4), `dpi` and `px` in the page table, the root's `source` is `{format, pages}`. A frame that states no resolution is read AS 300 DPI and the document is flagged `dpi-unknown`: SPEC 3.4 wants it estimated from the text size, which needs OCR (P6.5); this is the placeholder, and `px` keeps the pixel size either way. **The body is empty**: with OCR not wired (P6.5) an image page has no text, as a scanned PDF page has none (D144 (1)). (5) **Not done**: several inputs as one document (image sets, an image after a PDF) stay `unsupported` (D144 (3): merging two inputs' evidence is not written; `intake` takes any number and does read images); `assets` (D117); a `Renderer` (P6.2); a streaming codec over a forward-only source; and a TIFF's IFD chain is walked again for every frame asked for (quadratic in the page count: fine for hundreds of pages, a cache per input is the follow-up for tens of thousands). A codec that panics on damage is a `failed` answer (Rust `catch_unwind`, Go `recover`), never a crashed process.
**Why**: the same machine as every other expensive port (D008), so concurrency, transcripts and the identity gate come free; the header-only form is SPEC 2.0's "frame count, per-frame pixel size and resolution without decoding pixels"; keeping `attempt()` alone is the brief.
**Alternatives**: a `Renderer`-style request per page up front (the frame count is unknown until the first answer); decoding every frame in round one by guessing the count (a request for a frame that is not there is an error); pixels in every answer (a 100-page TIFF held in memory to be discarded).
- [ ] **Signed off** (maintainer)

### D188 — The `ImageDecode` contract as built: reduction to 8 bits, alpha over white, colour, orientation, resolution (P6.1; SPEC 7.2; D187)
**Decision**: both hosts do the same integer arithmetic, written once here. **Bitmap**: 8-bit, gray (1 channel) or RGB (3), row-major, top-left, never resampled; the channel count is the SOURCE's (gray, gray+alpha: gray; RGB, RGB+alpha, palette, CMYK, YCbCr: RGB), so a gray PNG with a `tRNS` chunk stays gray on both hosts. **Depth**: an `n`-bit sample `v` becomes `(v * 255 + max / 2) / max`, `max = 2^n - 1` (exact for 1, 2, 4 and 8 bits; 16 bits is `(v * 255 + 32767) / 65535`); 1..16 bits in TIFF, 1, 2, 4, 8, 16 in PNG. **`bilevel`**: one-bit gray (PNG colour type 0 depth 1; TIFF min-is-white or min-is-black at 1 bit, CCITT included): 0 and 255 only, kin skips binarisation. A one-bit palette image is RGB and not bilevel. **Alpha** is composited onto white after both are 8-bit: straight `(c * a + 255 * (255 - a) + 127) / 255`, premultiplied (a TIFF `ExtraSamples` 1) `min(255, c + 255 - a)`; an extra sample that is not alpha is dropped. **Palette**: 16-bit TIFF colour map entries and 8-bit PNG palettes are reduced as above; PNG `tRNS` on a palette is alpha. **CMYK** (TIFF photometric 5): `r = ((255 - c) * (255 - k) + 127) / 255`, no ICC and no black generation (the contract's ICC-aware conversion "where the platform is" is not taken: neither `image` nor Go's stdlib has one; **the host's choice is not recorded in the transcript because there is none to record**, the arithmetic is the choice); Lab, LogLuv and a separation that is not CMYK are refused. **YCbCr** (TIFF, 8-bit, any 1/2/4 subsampling, chroma upsampled by replication): `R = Y + ((91881 Cr' + 32768) >> 16)`, `G = Y - ((22554 Cb' + 46802 Cr' + 32768) >> 16)`, `B = Y + ((116130 Cb' + 32768) >> 16)`, clamped (libjpeg's); the reference black/white tag is ignored (libtiff's table differs by one in 2 to 4 of 307,200 pixels of `dscf0013.tif`). **CCITT**: black runs are 1 bits whatever the photometric (libtiff's reading), so min-is-white (the normal one) gives black text on white. **Orientation**: EXIF 1-8 from a JPEG's APP1, a PNG's `eXIf`, a TIFF's tag 274, applied to the pixels (the size in the answer is after it; the horizontal resolution is the file's vertical one when the axes swap). **Resolution** (dpi x100, 0 unknown; rounded half up): PNG `pHYs` in metres (`ppm * 254 / 100`), a JPEG's JFIF density when its unit is 1 or 2 (else the EXIF `XResolution`/`ResolutionUnit`), a TIFF's `XResolution` in inches or centimetres (`ResolutionUnit` 1, none, is unknown). Gamma, `iCCP`, `sRGB`, `cHRM` and JPEG/TIFF ICC profiles are ignored. **Refused by name** (`unsupported-image`): JPEG with other than 1 or 3 components (CMYK/YCCK: P6.1a, which has the Adobe marker's rules), JPEG-in-TIFF (compression 6/7), SGILog, ThunderScan, float or signed samples, samples over 16 bits, Lab, planar YCbCr, BigTIFF (not even sniffed), G3 two-dimensional, uncompressed-mode or with fill bits (D189). **Fixtures** `test/image/` hold one of each of the rest.
**Why**: gray stays gray so the OCR and tracer keep a third of the memory; composite-after-reduce is the one order both libraries' APIs allow without a second code path; CMYK has no honest cross-platform ICC answer in a library whose hosts must agree.
**Alternatives**: expanding everything to RGBA (SPEC 7.2 says gray or RGB); an ICC engine (a dependency per host and a second opinion on every pixel); `(v * 255) >> n` (not exact at 16 bits, differs from every decoder that rounds).
- [ ] **Signed off** (maintainer)

### D189 — Codecs: the `image` crate and `image/png`/`image/jpeg` for PNG and JPEG, our own TIFF reader over `fax`, `miniz_oxide` and `x/image/ccitt`; what Rust and Go both read (P6.1; D187, D188; D005, D117)
**Decision**: **PNG and JPEG pixels** come from the `image` crate (`png`, `jpeg` features: `png` and `zune-jpeg`) and Go's `image/png` and `image/jpeg`; **everything around them is ours and the same in both**: the header fields (size, depth, colour type, `pHYs`, `eXIf`, JFIF, component count) are read from the bytes before a codec is asked, so the answer does not depend on which library parsed what; the reduction, alpha, orientation and resolution are D188's. **TIFF is read by our own container code**, `Source`/`ByteSource` over random access, in both hosts: IFD chain, strips and tiles (tiles whose offsets are in the strip tags, as SGI wrote them, too), planar 1 and 2, FillOrder 2, predictor 2 (8 and 16 bits), and the decompressors: none, PackBits, LZW (new-style MSB with early change, and the pre-1988 LSB stream libtiff calls "compat": `quad-lzw.tif` of libtiff's own set is one), Deflate (`miniz_oxide`; Go `compress/zlib`), CCITT G4 and G3 one-dimensional (`fax`; Go `golang.org/x/image/ccitt`). **Why not the `tiff` crate and `x/image/tiff`**: `x/image/tiff` decodes the first IFD only, exposes no orientation or resolution and returns typed images, and the `tiff` crate returns raw samples differently (packed rows, palette indices, no alpha rule); two libraries' readings of 16-bit, palette, alpha, CMYK, YCbCr and planar data are two answers, and the identity gate (D190) wants one. Writing the container once in each host and sending only the compressed bytes to a codec is ~700 lines a host and gives pixels that are equal because the arithmetic is. **Pinned**: `image =0.25.10` (`default-features = false`), `fax =0.2.7`, `miniz_oxide =0.8.9` (`host/rust/Cargo.lock`, copied by `kin/scripts/emon-rs-build`, which tries `--offline` first); Go `golang.org/x/image v0.18.0` (`host/go/go.sum`, copied by `emon-go-build`; Go 1.21). The generated modules, `port.rs`, `plan.rs` and `bb unit`'s crates have no new dependency: `imgdec` is an Engine file. **CCITT, the sets made equal**: `fax` reads no 2-D Group 3 and no uncompressed mode and drops the last line of a stream with no EOL after it (six EOLs are appended to a G3 stream, which close it); `x/image/ccitt` reads no fill bits (a run of zeros before an EOL is indistinguishable from the zero tail of the code before it, so the stream cannot be repaired without decoding it). So the TIFF `T4Options` bits 0 (2-D), 1 (uncompressed) and 2 (fill) are refused by name on BOTH hosts, which loses `fax2d.tif` of libtiff's set (one Rust can read) for the identity of the answer. P6.1a's PDF `CCITTFaxDecode` (`K`, `EncodedByteAlign`, `Rows`) will want a decoder of its own; this one is not it. **Not read** (D188): the refusals. **The PIXELS bound** guards every allocation: a frame is refused from its header, before a byte of it is decoded. **Damage is answered alike, by making the strictness equal** (found by mutating the fixtures, `bb image`'s DAMAGE section, 4,605 mutants at a time: truncated, flipped, zeroed, corrupt links and sizes): a PNG is `failed` unless every chunk to IEND is whole and its CRC-32 right (the `png` crate forgives a bad ancillary checksum and a missing last byte, Go's `image/png` does not; both are asked only about a sound file); a Deflate stream gives what a CLEAN end gives, has its checksum read when it fills the expected bytes, and is cut at the expected size otherwise (`miniz_oxide` and Go's `compress/zlib` read the checksum at different moments); a G3 stream must give every row and every row's runs must add up to the width. **One divergence is left and is not claimed away**: a G4 payload with flipped or zeroed bits may be decoded by `fax` (to garbage) where `x/image/ccitt` answers `undecodable`; the gate holds the two to "no crash" there, and a transcript makes the answer one.
**Why**: the brief says "`image`/`tiff` crates" and "stdlib + x/image" and the identity requirement says one answer; the crates are used where they give raw, equal answers (pixels of a Huffman or Deflate stream) and not where they give their own reading.
**Alternatives**: `tiff` + `x/image/tiff` (above); a pure-integer JPEG decoder in both hosts (exact pixels, 1,000 lines each and a second JPEG decoder to keep: D190 is the cheaper answer); `jpeg-decoder` (the older Rust decoder: also its own upsampling).
- [ ] **Signed off** (maintainer)

### D190 — Identity of decoded pixels: PNG and TIFF exact, JPEG within 6 live and exact through the transcript; `bb image`; ImageMagick as the oracle (P6.1; D089, D187-D189; SPEC 14.1.5)
**Decision**: Rust and Go decode PNG and TIFF to **identical pixels** (and, so, identical canonical PNG transcript files, which `bb identity` compares byte for byte). **JPEG cannot be**: two inverse DCTs and two chroma filters. Measured on the fixtures and on real scans (`corpus/formats/derived/*.q40.jpg`, five SROIE receipts): the hosts agree within 2 on 4:4:4, within 1 on gray, within 5 on a 4:2:0 or 4:2:2 image with hard colour edges (48 x 36 synthetic) and within 4 on every real image (about 2 % of the bytes of a photographic receipt differ by 2 to 4); both are within 5 of ImageMagick's libjpeg (Go within 3, after the change below). **The rule is D089's**: a JPEG's answer comes from the transcript when there is one, and then the hosts are identical; a LIVE decode may differ between hosts, and between versions of the same library, **by at most 6 per byte**, which `bb image` holds (`:tol`, and the hosts to each other) and `bb identity` assumes: for a JPEG input the two hosts' `transcript.edn` must be equal, the PNGs need not be, and each host must replay the OTHER's transcript to the same bundle (the Model has no pixel in it; what an OCR does with the pixels is the OCR answer's, which is also transcribed, D095). **Go filters chroma as libjpeg does** (`imgUpsample`: the triangle filter for 4:2:2, 4:2:0 and 4:4:0, others replicated): Go's own decoder hands over the subsampled planes and a nearest-neighbour upsample differed from `zune-jpeg` by up to 61 at a colour edge; with the filter 5. **`bb image`** (`kin/scripts/image`, a gate) runs both drivers' `--out=pixels` (one recording, one line per decoded frame with an FNV-1a 64 of the pixels, `--dump` writing the raw bytes) over every fixture of `test/image/` (89: 81 decoded and 8 refused by name, PNG at 1, 2, 4, 8, 16 bits with alpha, palette, interlace and `pHYs`/`eXIf`; JPEG gray, 4:4:4, 4:2:0, 4:2:2, progressive, JFIF and EXIF resolutions, eight orientations; TIFF none, PackBits, LZW, Deflate, predictors, G3 1-D, G4, 1-16 bits, big and little endian, strips, tiles, planar, palette, CMYK, alpha both ways, multi-page in colour, gray and bilevel, orientations and resolutions; and damaged and refused files): refusals by the same name, every other fixture exactly the line `test/image/expect.edn` states, the same lines through a 512-byte-chunk async source, the hosts' pixels within 6 of each other (JPEG) or equal, and within the row's `:tol` of ImageMagick's. **The oracle is ImageMagick** (`tools/image-fixtures.clj`, babashka; third-party, D094): it writes the fixtures (and libtiff's `tiffcp`/`tiffset` the planar, G3 and orientation ones), and `expect.edn` is ITS reading of them after `-auto-orient`, over white, at eight bits, hashed; `--check` re-reads the committed files with it (the gate runs that, skipping loudly without `magick`). Where the port is not exactly ImageMagick's the row says why: JPEG (two IDCTs), CMYK (`tol 1`: two roundings of one formula), 16-bit alpha (`tol 1`: ImageMagick blends at 16 bits and truncates, D188 reduces first). **Everything else matched ImageMagick byte for byte**, including all of libtiff's own pictures that both read (strike.tif's premultiplied alpha, quad-lzw's old-style LZW, oxford's planar, the 2-16-bit depth set, the multi-page G4 receipts; `dscf0013.tif` and `ycbcr-cat.tif` differ by one in 2 to 4 pixels, D188). `bb image --corpus` runs `corpus/formats` (89 images, never committed): both hosts the same line or refusal on each (74 decoded, 15 refused: JPEG-in-TIFF and old-JPEG 3, LogLuv and float 4, ThunderScan 1, 24/32-bit 6, fax with fill bits 1). `bb identity` now includes the fixtures. **Damage** (`bb image`, DAMAGE: 150 mutants of 15 fixtures in the gate, 4,605 on demand with `IMAGE_FLIPS=300`) never crashes, hangs or panics either host, and PNG and TIFF mutants get the same answer from both but for damaged G4 payloads (D189). **Not claimed**: JPEG pixel identity without a transcript; a recorded transcript of a JPEG is not reproducible by a different decoder, only replayable.
**Why**: D089 exists for exactly this ("two JPEG decoders differ in the last bit", SPEC 7.3); making the filters alike cost 90 lines and took the difference from tens to single digits, which keeps a live run on one host close to a replay of another's.
**Alternatives**: a pure-kin JPEG decoder (the host's job, D058); refusing to compare JPEG (a live difference of 60 would have passed unseen); a tolerance of 2 (fails the hard-edged synthetic: 5).
- [ ] **Signed off** (maintainer)

### D195 — Fusion is a page unit that answers a PageLayer in the text layer's own word and line layouts; stage 3 does not change; fusion over one source is the identity (P7 plan; SPEC 2.2, 9.7, 12.1; D014, D022, D213)
**Decision**: (1) `attempt()` becomes three parts in both Engines: the **document pass** (everything that reads the input:
the text layer, P7.0's triage and health, the catalogue, the tagged tree, the annotations), a **page unit** per page
(`pu-run`, its own plan/fulfil loop and its own bitmaps: render or decode, normalization, tracer, OCR evidence,
alignment, health, arbitration, targeted rounds) and the **document unit** (stage 3). The stage order is the one written at
the head of Phase 7. (2) The PageLayer's words and lines are `fw` and `fl`, **with the layouts of `words` and `lines`**;
stage 3 (furniture, tables, headings, paragraphs and columns) is handed `fw`/`fl` (and the fused graphics `fpa`, with the
layout of `paths`) where it was handed `wd`/`ln`/`pa`, and no stage 3 signature changes. (3) With one source a page's
PageLayer is its words (the text layer's after triage, or the OCR lines D213 measures), so a run with no engine gives every
gate the Model it gave before (a law and gate G1 of P7.3). (4) OCR words stay in the OCR arenas; the pipeline's arenas no
longer receive them directly (P6.5's `sc-emit-lines` appended them to `wd`/`ln`: that becomes the one-source path of the
arbiter, and `sc-tag`'s per-page mask becomes per-word provenance from `fv`).
**Why**: SPEC 12.1 already says stage 1 and 2 are per page; the measured cost of a 300-dpi RGB render is 25 MB, two a
page, and a whole-document unit would hold every page's bitmaps at once and recompute every page's raster work in every
round. Writing the PageLayer in the existing layouts is what lets P4/P5's stages, which took a phase to tune, run on fused
text unchanged, and it is the seam P6.5 already proved (an OCR page through the born-digital pipeline).
**Alternatives**: one unit per document (memory above; raster work repeated per round); a new PageLayer word layout and
an adapter in every stage 3 module (every module touched for nothing); fusion after stage 3 (the structure would be built
on unfused text).
- [ ] **Signed off** (maintainer)

### D196 — Pixels reach kin through a `BitmapSet`; a transcript carries every bitmap kin reads; a kin-made view is OCR'd by naming it and its hash (P7.U, P7.4; SPEC 7.3; D008, D089, D092, D117, D214)
**Decision**: (1) A page unit gets its answered bitmaps as a `BitmapSet` port (`bms-get`, `bms-has`, `bms-put`), declared
in all four host trees; kin allocates derived views with `bms-put` at ids ≥ `KIN_VIEW_BASE` (2^24). (2) From P6.3 on kin
reads pixels, so **a transcript must carry every render and decode bitmap a unit reads**; D214's slim transcripts replay
only units that read none (there will be none with a raster), and the committed scan transcripts are re-recorded whole.
The engine's thresholded image is carried when recorded (it is `xo` 11 and 12's input). (3) An `ocr` request may name a
kin-made view (the body-ink layer, a quarter-turned page, later a polar seal strip): its record carries the view's
FNV-1a-64 pixel hash (`bitmap-hash`, a bulk form), so the transcript is keyed by what was asked, a changed view is a
`transcript-miss`, and the view's pixels are never stored (they are a function of transcribed answers), D117's payload
rule for `decode-img` applied to bitmaps.
**Why**: kin cannot see an answer's pixels today (the Engine never passes them), the tracer and the ink features must,
and SPEC 9.5's body-ink pass and P8.2's polar strips need the engine to read pixels kin made. Hashing rather than storing
keeps transcripts the size of what the hosts answered.
**Alternatives**: a host-side `derive` request (the host would implement ink layers four times, against D092); storing
kin-made bitmaps in transcripts (larger, and redundant); keeping slim transcripts by making kin's raster stages optional
(then the gates would not test them).
- [ ] **Signed off** (maintainer)

### D197 — Normalization as designed: three bulk forms and a hash, ink layers on a 4096-bin histogram, banded Sauvola, deskew only of decodes (P6.3; SPEC 8.2; D092, D093)
**Decision**: as SPEC 8.2's "As designed" list: `bitmap-gray-into`, `bitmap-hist-rgb`, `bitmap-lut-into`, `bitmap-hash` and
`bitmap-quarter-into` are the new bulk forms (each a pure whole-page map or reduce with a kin reference held equal by law,
declared in all four hosts); ink layers are clusters of the 4096 colour bins in an integer opponent space, at most 8, roles
guessed by named thresholds; masks are one LUT pass each; Sauvola runs over a band of `2r + 2` rows, equal by law to P2.7's
whole-page `ras-sauvola`; deskew is measured on decode views only (renders are unskewed); kin never resamples for analysis.
The purity of the layers is measured against the text layer's own truth on renders (`bb ink`).
**Why**: D092 measured that per-pixel kin over `Cells` is within 2× except on pure streaming passes, flagged RGB-to-gray and
ink masks as unmeasured, and found the 70 MB of whole-page integral images to be the real cost; a histogram is how
clustering stays independent of the page size; born-digital renders give a free ground truth for ink layers.
**Alternatives**: k-means over pixels in kin (seconds a page, and float-free k-means still iterates over 8.7 M pixels);
clustering in the host (four implementations of a heuristic); whole-page integrals (memory); a colour model with ICC
(no colour management in kin; the renders are sRGB-ish already).
- [ ] **Signed off** (maintainer)

### D198 — The tracer as designed: union-find over row runs, stroke width from runs, contours on runs, born-digital pages traced on the `no-text` render (P6.4; SPEC 8.2, 8.4.4; D092)
**Decision**: as P6.4's brief: components from runs (memory proportional to ink, no per-pixel label image), stroke width as
the median run length inside a component (measured against a distance transform on the fixtures, its error recorded),
contours traced on a component-local bitmap, integer Douglas-Peucker, rules from merged runs plus integer voting, primitive
fits in integers, `tlines` per ink layer. On a born-digital page only the `no-text` render is traced.
**Why**: D092's point 4 (35 MB of labels a page) and point 3 (work proportional to ink); the text layer already gives exact
glyph geometry, and what the tracer must add on a born-digital page is everything that is not text in the text layer.
**Alternatives**: per-pixel labels (memory); a distance transform per component (cost, and the run median is good enough
for "thin" against "thick" and bold against regular: to be confirmed by the measurement); tracing the `with-text` render
(duplicates the text layer and mixes glyph ink into rule detection).
- [ ] **Signed off** (maintainer)

### D199 — An Engine with no `Renderer` answers `render` and `crop` `unsupported` (revises D191 (5)); a born-digital page's rasters are a `with-text` and a `no-text` render at 300 dpi; OCR languages from the text layer's scripts (P7.U, P7.S; SPEC 7.2, 8.3; D211, D215, D191, D193)
**Decision**: (1) As D211 made a missing `Ocr` an answer, a missing `Renderer` is an `unsupported` answer, transcribed: a
gate with no engines then converts every page as fusion over the sources it has. (2) Under `always` a born-digital page asks
round 1 `render` layer 0 and layer 1 at 300 dpi; the watermark layers are never asked of pdfium (D193 found it cannot make
them); `text-only` (layer 2) is asked only by the targeted round for an unhealthy font (P7.4). (3) On a born-digital page the
OCR request's languages are the locale's, else the models of the text layer's scripts, else every model (D215's default).
**Why**: D191's `fulfil-failed` was right while nothing asked for a render without wanting one; once every born-digital page
asks under `always`, a missing renderer must degrade, not fail. Two renders because the tracer wants the page without text
and OCR wants it with; the text layer's scripts are an exact script detection that costs nothing.
**Alternatives**: one `with-text` render and kin masking the glyph boxes (anti-aliasing and overlapping paint make that
inexact); OSD first, then languages (an engine pass more, on a noisier answer).
- [ ] **Signed off** (maintainer)

### D200 — The OCR policy once fusion exists: `always` means every page has an OCR witness; `ocr-scope` `full` (default) or `witness`; `auto` defined; D212 (3) superseded at P7.S (SPEC 8.3, 9.8; D038, D212)
**Decision**: (1) At P7.S, born-digital pages are OCR'd under `always` (D212 (3) said they would be once fusion existed, and
it now does). (2) `always`'s promise is coverage: every region of ink the text layer does not explain is read by OCR, and
every (font, page) of the text layer is checked against OCR. (3) `ocr-scope: full` (default) reads every page's whole
`with-text` render; `ocr-scope: witness` reads the `no-text` render whole plus three witness lines per (font, page),
escalating a font whose witnesses disagree to all its lines in the targeted round. (4) The default stays `full` until
`bb fusion-bench` shows `witness` equal on every accuracy metric of the fusion set; **the maintainer decides the flip**. (5)
`auto` OCRs a born-digital page only when its health without OCR is under `auto-health`, images cover ≥ `auto-image` of it,
or it has `hidden-text`; `never` asks nothing. (6) The lossless savings of SPEC 9.8 are taken in every scope.
**Why**: D038 chose accuracy over cost and said why (broken encodings, text in images, outlined text, an agreement rate on
every page); `witness` keeps all four reasons (the `no-text` render holds images and outlines, the witness lines give the
agreement rate, and a broken font is escalated) at a fraction of the OCR time, but "keeps" is a claim to measure, not to
assume.
**Alternatives**: `witness` as the default now (unmeasured); sampling pages rather than lines (a broken font on an unsampled
page is missed); OCR only text-free regions (no agreement rate, so a plausible wrong ToUnicode is never caught).
- [ ] **Signed off** (maintainer)

### D201 — The born-digital contract: a healthy text layer is untouchable; three measured guarantees (P7.3, P7.5, P7.S; SPEC 9.4; D014, D038, D145)
**Decision**: (1) Arbitration never replaces a visible text-layer word of a font whose health is ≥ `tl-trusted`; only the
PDF's own say, recurrence (and the furniture stage) and a seal's arc text remove such a word from the body; colour, angle
and ink judge OCR-only text alone. (2) OCR-only text enters the body only on the body ink layer, outside every visible
text-layer box, plausible, and not inside a picture smaller than half the page (that text is the figure's, held for P8.1).
(3) Three guarantees, each a measurement: **G1** with no engine every gate's Model is byte-identical to before fusion; **G2**
`bb fusion` (a gate) holds each healthy born-digital document of the fusion set to its `emon` CER + 0.0005 and
`tl-override` 0; **G3** `bb fusion-live` (before the switch merges) holds `pdf/all` CER ≤ `emon`'s and every category within
0.001.
**Why**: born-digital text is emon's most accurate output (CER under 1 % on the synthetic set) and the bulk of a crawler's
input; OCR on those pages earns its cost only by catching what is broken, and a rule that let a plausible OCR misreading
beat a healthy font would trade a 0.01 CER for a worse one.
**Alternatives**: let OCR win on disagreement when its confidence is high (D130: Tesseract's confidence is not a
probability); per-character fusion of the two (no case where the text layer of a healthy font is wrong per character
and right per word has been seen).
- [ ] **Signed off** (maintainer)

### D202 — Alignment: a grid, overlap and angle, a per-line dynamic program with 1:1, 1:2, 2:1 and gaps, two similarities (P7.1; SPEC 9.2)
**Decision**: as SPEC 9.2's "As designed". Groups hold every OCR run's candidates; similarity is kept with case and
case-folded over letters, both in the group.
**Why**: the shared heads (D096) make one alignment for every source; 1:2 and 2:1 cover the splits and merges the P2.6 spike
saw (`Chapter 1` / `Chapter1`); the case-folded letters-only similarity is what separates a broken mapping (low on it) from
OCR's own case and punctuation errors (high on it).
**Alternatives**: character-level alignment of whole lines (costlier, and fusion decides per word); Hungarian matching on
overlap alone (ignores order along the line).
- [ ] **Signed off** (maintainer)

### D203 — Health and arbitration are data: `hl` rows with their components, the `fr` table in blocks, `fv`/`fx`/`fa` explain every decision in dump version "2" (P7.0, P7.2, P7.3; SPEC 9.3, 9.4, 9.7)
**Decision**: health per (font, page) is computed twice (without OCR in the document pass, with agreement in the page unit);
the rule table is `defdata` in `kin/fuserules.kin` in numbered blocks owned by one item each and tried in the order 400,
200, 300, 100; every word's winner, rule, confidence and the columns its rule read are in `fv` and `fx`, every removal in
`fa`, and the evidence dump becomes version "2" with the PageLayer arenas after the evidence's.
**Why**: SPEC 9.4's "every decision can be explained from the evidence dump"; blocks let P7.5, P7.6 and P7.7 be written at
once without renumbering each other; the order puts ownership (seal, artifact) before reading quality.
**Alternatives**: rules as kin code (no table to diff, no explanation for free); one ordered list (parallel items would
collide on numbers).
- [ ] **Signed off** (maintainer)

### D204 — The six defences on what is built: the PDF's say from marks, OCGs, alpha, annotations and the tree, never from a masked render; OCR words aligned to a watermark go with it; recurrence is a document pass (P7.5; SPEC 9.5; D015, D149, D154, D173-D177, D193)
**Decision**: as SPEC 9.5's "Against what is built". pdfium cannot make the watermark layers (D193), so defence 1 classifies
from the text layer and the annotations, and takes the OCR words aligned only to classified words with them; `pa-run`
(annotations, P3.8) is wired into the document pass, where it had never been called; recurrence of OCR-only text is a
document-unit pass before the furniture stage, beside the furniture stage's diagonal rule; defences 3, 4 and 6 judge
OCR-only groups only (D201).
**Why**: the strongest signal is the file's own and costs nothing; the next strongest is that a watermark repeats; colour,
angle and plausibility are last because they are what a real body word can also have.
**Alternatives**: waiting for a renderer that can mask marks (pdfium cannot, and MuPDF is not bound); a mark-key heuristic in
the renderer (drops headers, D193).
- [ ] **Signed off** (maintainer)

### D205 — Seal, stamp and signature placeholders come with fusion (P7.7), before P8: candidates from the tracer, text inside kept out of the body, `seal` nodes with subkind, colour and raw text (SPEC 10.7; D016)
**Decision**: P7.7 makes `seal` nodes (seal, stamp, signature) from P6.4's primitives and colour components and from
stroke-like components near signature cues, attaches the OCR words (and a seal's arc text from the text layer) inside them
as the node's `text` with `low-conf`, and inserts the node in reading order without changing `pa-build`. P8.2 (polar unwarp,
name, date) and P8.3 (signature association, digital signatures) refine the same nodes.
**Why**: the maintainer's strategy names placeholders for seals and signatures as part of fusion, and defence 5 needs the
regions anyway; a seal's arc text in the body is today `pdf/seals`' CER of 0.318.
**Alternatives**: leaving seals to P8 (their text leaks into the body until then); detecting seals only on colour (a black
notary seal is common).
- [ ] **Signed off** (maintainer)

### D206 — The scorer's fusion metrics: `ghost-rate`, `seal-leak`, `src-ocr-share`, `tl-override`; per-source win rates recorded, not ratcheted (P6.6; SPEC 14.2)
**Decision**: the four metrics of SPEC 14.2 join the scorer, lower is better, in the ratchet's direction table; `bb
fusion-report` reads the dump's `fv`/`fx`/`fa` for win rates, per-rule and per-defence counts, which are recorded in DEVLOG
entries and never gated (no direction is better); `stamp-ghost` on mapo80 is computed live from the dump.
**Why**: `watermark-leak` measures one artifact kind; the strategy's claims (ghosts dropped, seals out of the body, the
text layer kept where reliable) each need a number, and a win rate is a description, not a quality.
**Alternatives**: win rates as ratcheted metrics (a better fusion can lower the text layer's win rate legitimately).
- [ ] **Signed off** (maintainer)

### D207 — Ids per page unit, `KIN_VIEW_BASE`, and five rounds a page unit (P7.U; SPEC 7.3; D211)
**Decision**: page `p` asks ids `(p + 1) * 256 + k` (render with-text 1, no-text 2, text-only 3, decode 4, OCR 16-127,
crops and later kinds 128-255); kin-made views are `2^24 + p * 256 + k`; `max-rounds` is 5 per page unit (render or decode;
first OCR pass; targeted requests including a `text-only` render; their OCR; one spare); `fetch-rounds` stays the document
pass's. D211's ids are replaced and its transcripts re-recorded.
**Why**: per-page id ranges make a page unit's requests independent of how many pages precede it in a round, and leave room
for every request kind planned; the round count follows from the plan (a `text-only` render and its OCR are two rounds).
**Alternatives**: keeping D211's `2i + 1` scheme (no room for more than two requests a page).
- [ ] **Signed off** (maintainer)

### D208 — Visibility triage: a word that failed a P3.6 check leaves the flow for the ledger, before stage 3, with or without OCR; a word occluded by a page-sized image is a hidden OCR layer (P7.0; SPEC 8.1, 9.4; D155, D157)
**Decision**: as P7.0's brief: `vis` ≠ 0 → ledger (`clipped`, `offpage`, `invisible`), except `occluded` by an image covering
half the page or more, which is `hidden-text`. Render modes 3 and 7 stay in the flow as `hidden-text` (P7.6 decides). The
flow is copied into `fw`/`fl`, which stage 3 reads from P7.0 on.
**Why**: SPEC 8.1 always said invisible text goes to the ledger, P3.6 computes it, and no stage reads it (white-on-white and
clipped text reach the body today); D157 measured that the classes are what a renderer would show.
**Alternatives**: waiting for P7.3 (the change is independent of OCR and measurable alone); dropping `hidden-text` too (a
searchable scan would lose its only text before OCR).
- [ ] **Signed off** (maintainer)

### D209 — The fusion corpus: colour and overlay scans of a fusion set, new watermark/seal/signature/broken-encoding documents; mapo80 measured live only (P6.6; SPEC 14.3)
**Decision**: as P6.6's brief. The fusion set is 22 documents (as built); `scan-color/` (200 dpi RGB) and `scan-ovl/` (generated
watermarks, seals, signatures, bleed-through, turned pages) exist only for it; the new committed pixels stay near 10 MB (below).
The six seals are generated by us; nothing from mapo80 (an aggregate of unverified licences, with research-only `neg_*`
images) is committed.
**Pixel budget (coordinator, answering the plan's open question)**: **committed pixels <= ~10 MB** for this item (measured: about
8.2 MB); **larger fixture sets go to the S3 corpus store** (`bb corpus-push`, `tools/corpus.clj`, D085) under a new corpus category
and are used only by non-gate long runs; **no LFS**. (The 12 MB of the brief is superseded.)
**Why**: the 150-dpi gray `scan/` variants cannot test colour ink layers, and the synthetic set had one watermark and one
non-overlapping seal; truth stays known because every overlay is ours.
**Alternatives**: colour variants of all 55 (about 50 MB); pasting mapo80 stamps into fixtures (licence).
- [ ] **Signed off** (maintainer)

### D210 — The page budget with fusion, and where cost is cut without breaking `always` (P7.S; SPEC 9.8, 12.3)
**Decision**: SPEC 12.3's table is the budget (render ≤ 0.3 s, normalization ≤ 0.12 s, tracer ≤ 0.15 s, fusion ≤ 0.03 s a page
on Rust; Go within 2× on kin stages; `witness` OCR ≤ 30 % of `full`; a page unit ≤ 120 MB), measured by `bb fusion-bench`,
not gated; the lossless savings of SPEC 9.8 are design rules (first pass planned without pixels, deskew only of decodes,
tracer on `no-text`, histogram clustering, OCR languages from the text layer, bitmaps freed per page, page units
concurrent in the host).
**Why**: OCR is seconds a page and everything else is a tenth of that; the budget says where the tenth must stay, so a slow
kin stage is noticed, and the scope switch is the one lever that changes the seconds.
**Alternatives**: a gate on time (machine-dependent, as D194 found for pixels).

### D211 — The `Ocr` port as wired: a request kin builds, a port the embedder brings, `unsupported` without one, Tesseract live behind a feature in Rust and a build tag in Go (P6.5; D008, D089-D091, D095, D038, D187)
**Decision**: (1) **kin asks, the host answers.** An image input (`emon.rasterin`'s `rs-unit`) and a PDF page that is a scan (`emon.scan`'s `sc-pdf-unit`) ask for OCR as a plan request (`rq-ocr`, built by `sc-request`: the whole bitmap of the decode request named, `psm` 3, flags symbols + OSD, no region, the language the locale hint names, if any) and are re-run on the answers; the evidence, the lines and the Model are made from the answer by kin alone. Request ids are derived from what is asked: an image's decode of frame `f` is `f + 1` as before (D187) and its OCR `frames + f + 1`; a PDF page `i`'s decode of the embedded stream is `2 i + 1` and its OCR `2 i + 2`. The rounds are three for an image (frame 0; the other frames and OCR of frame 0; OCR of the rest) and two for a PDF whatever its length (every scanned page's stream; every one's OCR), both within `max-rounds` 3. (2) **The port.** Rust: `trait Ocr { fn fulfil(&self, &Request, &Bitmaps, keep_binary: bool) -> Result<Answer, PlanError>; fn producer(&self) -> Vec<(String, String)> }` in `Config::ocr: Option<OcrPort>`; Go: `type Ocr interface { Fulfil(PlanRequest, PlanBitmaps, keepBinary bool) (PlanAnswer, error); Producer() [][2]string }` in `Config.Ocr`. With no port an `ocr` request is answered `unsupported` (a failure is an answer too, D089) and the page keeps the text it has (none, for a scan): so every gate, which has no engine, converts a scan as it did before OCR existed, and a recording made so says it. An engine that panics on a page answers `failed` for it. (3) **Pixels are kept when somebody will read them**: a decode answer carries its bitmap when a transcript is being recorded or an `Ocr` port is configured; a decode of a PDF's embedded DCT stream (kept only for an engine: a recording of a scan with no engine has no 450 KB PNG a page, and the two hosts' JPEG decoders, which differ in the last bits, D190, leave no difference between their transcripts). The engine's own thresholded image goes in the OCR answer's bitmap only when recording. (4) **A DCT stream is a JPEG file**: the `decode` request's `filter` may now be `flt-dct`, and the host reads the span in place with the same decoder (orientation and resolution tags of an embedded stream are not honoured by a PDF viewer and are not here either, which is a follow-up: P6.1a owns the other PDF image filters). (5) **Tesseract** is bound by hand to the C API in both hosts, LIVE only: Rust `host/rust/src/tess.rs` under the Cargo feature `tesseract` (`EMON_RS_LIVE=1 kin/scripts/emon-rs-build` builds `.cache/emon-rs-live`, `-L $EMON_TESS_LIB`), Go `host/go/tess.go` under the build tag `tesseract` with cgo (`EMON_GO_LIVE=1 kin/scripts/emon-go-build`, `.cache/emon-go-live`; the script signs the binary again on macOS, whose linker's signature fails its own check) with `tess_stub.go` for every other build. The two drivers take `--tesseract[=TESSDATA]`. Nothing in `bb gates` builds either (a gate replays a transcript, D095). One engine sits behind a mutex (Tesseract's API objects are not thread safe). **Live outputs of the two hosts are not identical and cannot be asked to be**: the Rust and Go JPEG decoders agree to a few bits per byte (D190) and Tesseract's answer moves with them (a live Go conversion of `h-keyword` read `Chapter1` where Rust read `Chapter 1`); the identity is the transcript's, recorded on one host and replayed on the other, byte for byte (`bb ocr`).
**Why**: SPEC 7.3 and D008 (kin never calls an expensive port; the answer is the transcript); a port is what an embedder brings, so the library names no engine and no filesystem; `unsupported` is the honest answer of a host with no engine and keeps every existing gate meaning what it meant.
**Alternatives**: OCR through a `Renderer` stand-in (P6.2 is not here; the page's own image is the bitmap); a crate or cgo wrapper for Tesseract (a build script and a pinned Leptonica; the C API is 40 declarations); the live engine in `bb gates` (D008: whose Tesseract is installed would decide a gate); making the two live hosts agree by sharing a JPEG decoder (not on the table: D189).
- [ ] **Signed off** (maintainer)

### D212 — Which pages are OCR'd: images and scanned PDF pages; a born-digital page keeps its text layer under `always` until fusion exists (P6.5; D038, SPEC 9)
**Decision**: (1) `ocr: never` asks for nothing, on any input. `auto` and `always` OCR an image page (it has no text layer to prefer) and a PDF page that is a scan. (2) **A scanned PDF page** is a page with no `words` row at all (a hidden one counts: a "searchable scan" is a page with a text layer) and an image placement that is upright and not skewed, a real XObject (not inline, not a stencil mask), at least 16 pixels each way, covering at least half the page, whose stream's whole filter chain is one `DCTDecode` in a file that is not encrypted; the page has no `/Rotate`. Of its placements the largest wins. Its pixels are the stream's own (D211). Everything else is left to the `Renderer` (P6.2): a Flate or CCITT page image, a tiled scan, a rotated one, an encrypted file, a page with the scan AND a text layer. (3) **A born-digital page is not OCR'd under `always`, though D038 says every page is**: D038's reason for it (catch broken encodings, text in images, outlined text, give the health scores an agreement rate) is a FUSION of the text layer with OCR (SPEC 9), and fusion is P7, which is not built. Without it a second reading has nowhere to go: it could only replace the text layer, which is authoritative (D038's own words for every case but the failures fusion exists to find), or be dropped. So the text layer stays authoritative and nothing is asked of a page that has one; P7 will turn the page's text layer + OCR into fused evidence, and the setting stays as it is. `auto` therefore equals `always` here. (4) **Where OCR gave the text, the Model says so** (`emon.scan`'s `sc-tag`, after the paragraphs and the furniture are written): its `text` source becomes `ocr`, an inline text's confidence 1000 (a glyph's own text) becomes 500 (an engine's word is uncalibrated, D130, Phase 10), a block that had `text` gets the `ocr-only` flag, a scanned PDF page's kind is `scanned`, and the document's `settings.ocr` is `always` or `auto`. A Model with no OCR in it is byte for byte what it was.
**Why**: (3) is the maintainer's `always` read at its word: OCR on every page needs fusion to mean anything; recording the choice is the brief's ("record that fusion (P7) isn't built, so the text layer stays authoritative").
**Alternatives**: OCR a born-digital page and keep only the text layer (a cost with no use); OCR it and let OCR win (D038 says the opposite); OCR it and write both (two bodies); decoding any page image (P6.2).
- [ ] **Signed off** (maintainer)

### D213 — An OCR line is a pipeline line: its size, its box and its rotation are the line's, measured (P6.5; D096, D134, D135, SPEC 8.4)
**Decision**: the evidence rows (`olines`, `owords`, `osyms`, `oalts`, `feats`) keep the engine's numbers. The pipeline's rows (`lines`, `words`, `etext`) are made from them by `sc-emit-lines`, in the SAME arenas the text layer fills, so furniture, initials, headings, paragraphs, columns, tables and the Model read an OCR page as they read a born-digital one (SPEC 9.1). Three things differ from a text-layer line and are decided once: **size**, **box** and **rotation**. (1) **A word has no font size.** The stages that read sizes (the paragraph stage breaks a paragraph at a 5 per cent change; the heading stage ranks sizes) read a line's dominant word's `wf-size`, so every word of an OCR line gets the LINE's size, `1.37 x` the ascender line `A` (an em is about `A / 0.73`), in centipoints. **`A` is measured, not given**: the Tesseract C API does not export `RowAttributes` (x-height, ascenders, descenders: `capi.h` has no such function), and a measurement is one implementation for every engine. An engine that does give them (ext v2's `xheight asc desc`) is used; otherwise from the symbols' boxes: `X` the 30th percentile of the HEIGHT of the boxes of the x-height letters (`a c e m n o r s u v w x z`, at least 4 of them), `T` that of the tall ones (`b d f h k l`, else capitals and digits x 1.06, at least 3), `D` the 30th percentile of the hanging letters' bottoms below the baseline. Why heights and not tops above the baseline: the engine's baseline is fitted to a whole line and is a pixel or two off at either end of a tilted one. Why a LOW percentile: a symbol's box is its own component's most of the time and the whole word's height some of the time (in a body line of `columns-two` a third of the x-height letters have the height of an ascender), and those are always too tall; no mean and not the median survives that. Why `X`, and not `T`: a line has dozens of x-height letters and a handful of tall ones (a heading may have none). So a line's `A` is its `X` times THE PAGE'S ratio `A / X` (the median over the lines that have both: one typeface per page; 1.5 with none). The sizes of a page are then CLUSTERED: the densest window `[A, 1.10 A]` over the lines not yet placed takes the median of its members' `A` and `D`, and so on over the rest: the measure is good to 3 or 4 per cent, the paragraph stage breaks at 5. Two sizes less than 10 per cent apart are one size here; the engine could not tell them apart either (a `Chapter` heading of `h-keyword` is 10 per cent over its body). Where nothing is measurable (a script the classes do not know) the size is 1.08 x the box's height. (2) **The box of a line** is its words' extent along it and, across it, `A` above the baseline's middle and `D` below it: not the axis-aligned box of a tilted line, which a 1.2 degree scan makes 20 pixels taller across 1000 and which would swallow the gap between lines that the paragraph stage measures. (3) **The rotation of a line** is its baseline's angle less the page's (the lower median of the run's lines, as `xo-angle` is): a scan fed in a degree off is a page of upright lines, and a line that is not upright is still not. Also: a word's `gap` and `wbreak` as the text layer's (`space`, `line-end`); `lf-order` is the engine's reading order; a line and its words carry `sr-ocr`. The page's `epages` row has the raster flag and the resolution; the decoded raster is a `views` row (kind `decode`, exact at 150, 200, 300 dpi); a scanned PDF page's raster is placed by the image's box (a crop and a dpi).
**Why**: measured with `bb ocr-live` on the 55 scans: this took the headings of the scan variants from the first-draft 0.3-0.6 F1 to the 0.9s, and the paragraph boundaries from 0.4-0.5 to 0.8-0.98 on the documents of plain prose (`h-numbered`, `h-plain`, `furn-odd-even`); the number of paragraph breaks no longer depends on whether a line has an `f` in it.
**Alternatives**: the word box's height as the size (a `was` is half the size of a `Which`); the engine's `pointsize` (0 for LSTM); a median or a trimmed mean (both lost to the oversized boxes); no clustering (a paragraph breaks every time a line measures a pixel different); a window of 14 per cent (hides a 10 per cent heading).
- [ ] **Signed off** (maintainer)

### D214 — The OCR answer, version 2 (P6.5a): row-less lines measured by kin, a block table, a word's point size and dictionary flags, the thresholded image as the answer's bitmap, a `:producer` map in the transcript, and slim transcripts (P6.5a; D095, D096, D130)
**Decision**: (1) **Version 2 replaces version 1** (`ox-version` 2; `ox-find` answers -1 for version 1, which the one committed transcript, re-recorded, was). Header 14 cells (`nblocks binary bw bh` after `nlines`); a line 14 (`xheight asc desc`, pixels, -1 unknown); a BLOCK TABLE after the lines, one row per block id (box, orientation, writing direction, line order AT that block: they differ per block, and the evidence's `oblocks` now carries them, with the block's own box); a word `flags lang psize dflags nsym` (`psize` the engine's point size, -1 unknown, in the evidence `owf-psize` in centipoints; `dflags` bit 1 the engine's dictionary, bit 2 numeric: the evidence's `owf-flags`, which `xo-engine-flags` already reads). (2) **The thresholded image**: `binary` 1 says the OCR ANSWER carries a bitmap (1-channel, 0 ink, 255 paper, `bw x bh`), which the transcript stores like a decode's (`bitmap-NNNN.png`) and kin records as a `views` row of kind `engine-binary` named by the run's `orf-bview`; D096's provenance. The record says `binary` 1 whether or not the bitmap is kept (it is, only when recording: a conversion's Model cannot depend on it). (3) **`:producer`**: `transcript.edn` gains `:producer {:engine \"tesseract\" :version \"5.5.3\" :leptonica ... :models \"eng:4113088:fnv64=... osd:...\" ...}` when the port says (`Ocr::producer`), after `:emon-transcript 1`; a replay never reads it (it depends on the answers, not on whose they are); a transcript with none is byte for byte what it was; both hosts write the same bytes (the Go reader keeps the file's order). `test/ocr/PRODUCER.json`'s note is superseded. (4) **The two row-attribute facts the C API does not give** (x-height and ascenders; the page's deskew is read from the iterator's orientation) are answered -1 and kin measures them (D213); D130's finding stands (nothing was changed in `tess.rs` for the 0 confidences: they are Tesseract's, carried verbatim). (5) **`test/ocr/h-keyword/`** was re-recorded live on Rust with version 2 (`bb tools/ocr/record.clj`; `.dump` rewritten, evidence 274,562 bytes; the gate holds 199 words, 24 lines, 23 blocks and paragraphs, 972 symbols, 2,139 alternatives, CER 2.05 per cent, as before). `xo-*` columns 11 and 12 (engine-ink, Jaccard) are NOT filled: they want kin's ink (P6.3). (6) **Committed transcripts are slim**: `test/ocr/scan/ID/transcript.edn` of five scans, recorded by `tools/ocr/record-scan.clj` and stripped of their bitmaps (a replay returns the recorded answers and reads no pixel); each is about 57 KB a page. Slim or whole, a transcript replays on Rust and Go to one Model (`bb ocr`).
**Why**: the extension was designed to be extended (D095); the row attributes and the block table are what the engine reports and the evidence has room for (D096); a producer is what a transcript is evidence OF.
**Alternatives**: patching version 1 in place (every reader of the line stride would drift); an EDN reader that keeps order for Go's maps (a scanner of one map is smaller); storing the pixels of 50 scans (30 MB).
- [ ] **Signed off** (maintainer)

### D215 — The locale hint chooses the OCR language; with none, every installed model; the resolution is a hint in the flags; an unstated resolution is 300 dpi, not estimated (P6.5; D038, D187, SPEC 3.4)
**Decision**: (1) `Options::locale` (a BCP 47 tag) is passed to kin as code points and `sc-lang` maps its primary subtag to an ISO 639-3 code (the 79 languages of the table in `emon.scan`: `de`, `de-CH`, `deu` all name `deu`); Chinese is `chs` or `cht` by its script (`Hant`) or region (`TW`, `HK`, `MO`), which the adapter turns into `chi_sim` and `chi_tra`; a tag that names nothing is no language. The request carries the language as one more `rq-ocr-lang` cell and the run's `oruns` row records it. The adapter drops the languages it has no model for and, if none is left, reads with every model it has. **With no hint, no language is assumed (D038)**: the engine is loaded with every installed model but `osd` and `snum` (here: `eng`), each word says which model read it, and OSD runs in the same request (orientation, script and their confidences go to `oruns`); a script-specific second pass (Latin models for a Latin page, and so on) and acting on OSD (a page at 180 degrees reads as noise until it is re-rendered rotated: P6.2) are follow-ups. (2) **The resolution is a hint**: the request's flags above the low byte carry it in dots per inch (`ocf-dpi`), from the raster (the frame's, or an embedded image's pixels over the placement's inches), since Tesseract's size heuristics need it and its own default is 70; the recorded `h-keyword` request is flag 38403 (3 + 150 x 256). (3) **A frame that states no resolution is read as 300 dpi and flagged `dpi-unknown`, and the estimate SPEC 3.4 wants from the text size is not made**: it would turn the page's size in the Model into an OCR estimate (a page 612 or 1224 points wide by the median line height), changing the geometry every later stage reads; the 300 is the engine's own default and the pixel size is in the page table either way. Follow-up with P6.3.
**Why**: SPEC's "without assuming English"; Tesseract's `eng` default is exactly that assumption.
**Alternatives**: `eng` by default (D038 says not); running OSD first and choosing models by the script it names (two engine passes, a decision on a noisy answer; the cost belongs to the fusion item); estimating the dpi in kin from the line heights (above).
- [ ] **Signed off** (maintainer)

### D216 — Measuring scans: `bb ocr-live` (not a gate), `bb ocr` (a gate over committed transcripts), the scoreboard's `emon-live` and `emon-replay`, what the first numbers say (P6.5; D145, D077, D095)
**Decision**: (1) `bb accuracy` gains `--variants` and `--ids` and two predictors: `emon-live` (the Rust Engine with Tesseract, `tools/accuracy/predict-emon-live.sh`; `bb ocr-live`: the scan variants, ratcheted) and `emon-replay` (the Rust Engine replaying `test/ocr/scan/ID/`, no engine; a gate's, `bb ocr`: the documents that have a transcript). The default predictor `emon` is unchanged and still scores `scan/*` with no OCR (CER 1.0), which is what a gate with no engine can claim. (2) `bb ocr` now also holds, over the committed scan transcripts: the Rust and the Go Engine replay each to the identical bundle (JSON, HTML, evidence), the Model says `ocr` where the text came from, a changed request is `transcript-miss` on both, and the `emon-replay` ratchet. (3) **First numbers** (BEFORE: no OCR, scan/all CER 1.0000, WER 1.0000, heading F1 0, paragraph-boundary F1 0; AFTER, live Tesseract 5.5.3 on the 55 scan variants, this machine; see the scoreboard's `emon-live`): scan/all CER 1.0000 -> 0.0878, WER 1.0000 -> 0.1676, bag-of-words F1 0.00 -> 0.87, heading F1 0.00 -> 0.70 (P 0.71, R 0.69), paragraph-boundary F1 0.00 -> 0.47, furniture F1 0.00 -> 0.38 (the text-layer variants of the same documents: CER 0.012, WER 0.021, heading F1 0.99); by family: headings CER 0.024 / heading F1 0.80, furniture CER 0.023 / heading F1 0.96 / paragraph F1 0.81, columns CER 0.081, lists CER 0.068, tables CER 0.105, scripts CER 0.80 (only eng and osd installed). The pdf variants of the same documents: CER 0, headings 0.8-1.0, boundaries 0.87-1.0. What remains, in order of cost: bold-only headings (a bold run of body size has the same measure; bold is stroke width, P6.3's ink); lists and tables (P4 and P5, which the scan inherits when they land); non-Latin scripts (only `eng` and `osd` are installed here); a heading 10 per cent over its body (D213). FUNSD, SROIE and CORD (`corpus/_gold`) are sampled by `bb ocr-live` (after the scans): CER of the OCR text against the datasets' words (not a gate): 15 documents each: SROIE (receipt photographs, line-level transcription) CER 0.443, WER 0.664, bag-of-words F1 0.517; FUNSD heading P 0.14, R 0.20, F1 0.165 (no text claim in its gold); CORD claims no text and no kind this scorer measures here (word boxes only) so it reports nothing, and its photographs are the hardest input (Tesseract itself reads nothing on several).
**Why**: D095: the gate replays, the measure is live; both are needed and neither may be mistaken for the other.
**Merged with the table, initials and alignment stages (D178-D185, after P6.1)**: both Engines' raster unit now runs the same stages as the PDF unit (`TbBuild`, `LcCopy`/`InDetect`, headings on the copy, `PaBuild` with tables, initials and alignment, `FuWrite`); OCR words have no glyph or font evidence, so no initial is found in a scan (P6.7 is for that). A scan's rows slope (0.6 degree here), so one table row's baselines spread over several points: `tb-rows` groups a row as a chain of baselines (each within a third of the size of the one before it) instead of a distance from the first word, which lost the `Total` label of `table-simple-ruled` (a one-word row of its own is no table row); identical on a text layer (a row's baselines agree). `emon-replay` `scan/all` after the merge: CER 0.0324, WER 0.0894, heading F1 0.886 (before: 0.0331, 0.0903, 0.886); table, caption and paragraph-boundary metrics now score (`block-table-f1` 0.0 to 1.0, `para-boundary-f1` 0.774 to 0.819).
- [ ] **Signed off** (maintainer)

### D217 — The C# arm of `bb unit` is built in Release: two laws were 12.7 of its 13.5 minutes in Debug, and a cap kill says "timed out" (P0.5; D044)

**Decision**: `kin/scripts/unit` runs `dotnet test -c Release`. `finish` reports a `kin/scripts/cap` kill (124) as `FAIL <arm>    timed out`, and in `red` mode a timeout is a failure, not a law that failed.
**Why (measured)**: `bb unit csharp` alone was not slow because of the build (7 s) or the 3.5 MB of generated CJK CMap tables (they compile in seconds); the 379 laws took 13 m 25 s in Debug, and two of them took 9 m 35 s (`TestTheThreeFixturesAndTheRealFilesAreTheSameThroughAnAsyncSourceOf512ByteChunks`) and 3 m 08 s (`TestRealFilesMakeTheGlyphStreamsPdfminerAgreedWith`): tight loops over megabytes of PDF, unoptimised. Under `bb gates` on a loaded machine that crossed the 900 s cap, and the old script then showed a bare FAIL with a count of 0. In Release the same two take 46 s together and the whole `bb unit csharp` (checks, restore, build, 379 laws) 4 m 47 s under load. The cap stays 900 s as a hang detector.
- [ ] **Signed off** (maintainer)

### D218 — Graphics fusion is text fusion's counterpart: rules and fills from every source are aligned, health-scored and arbitrated by `fr` rows, and ONE fused set (`grl`, `gfl`) is all the table stage reads; `fpa` and rule 501 are retired (SPEC 2.2, 9.7, 9.9, 10.5; D014, D179, D195, D203)
**Decision**: the maintainer's request (2026-10-01): table borders and the like come from the PDF's drawing language where it
has them, from the tracer where it does not, with OCR's layout analysis and the tagged tree as further knowledge sources,
"the same process as for text". So: (1) the fused objects are **rules** (h, v, slant; the edges of boxes), **fills**
(shading bands, header and cell fills) and **regions** (D228); (2) the sources are the PDF's paths (`vector`), the tracer
(`trace`), OCR's block types (`ocr`: line separators and tables, a tie-breaker only) and the tagged tree (`struct`: a prior on
tables, verified in stage 3, D225); (3) the steps are the text's: one space (centipoints), alignment (D221), health per
region (D223), arbitration by data rows (blocks 500 and 600 of `fr`, D222, D224), a confidence and an explanation in `fx`;
(4) the PageLayer carries `grl`, `gfl`, `ggp`, `grg` (SPEC 9.7), `grl` taking `fpa`'s planned `ea` code; (5) `emon.table`
reads `grl`/`gfl` and never `paths` (P5.5a), so a born-digital table, its scan, and a scanned table inside a born-digital PDF
go through one table code; (6) `gf-page` runs in the page unit on EVERY page, after the tracer and OCR evidence and before
`al-page`; over the vector source alone it is the identity (P5.5a proves it byte for byte). This revises D195 (2): stage 3
is handed `grl`/`gfl`, not a `paths`-shaped `fpa`.
**Why**: `fpa` ("vector paths plus tracer rules no vector path explains", P7.3's rule 501) was one rule of one direction: it
could not say a vector rule is hidden, could not let the render witness paint emon cannot model, had no fills from scans,
no per-region trust, and fed the table stage `paths` rows whose box is the only geometry (a grid drawn as one path was one
box). Accuracy on tables is the same problem as accuracy on words: several imperfect witnesses of one page.
**Alternatives**: keeping `fpa` and rule 501 (above); fusing at the table level only (whole candidate tables per source,
then pick: a scan's table would never get the vector's exact rules on a mixed page, and two half-right tables cannot be
combined); a learned layout model (SPEC 1.1: parked).
- [ ] **Signed off** (maintainer)

### D219 — Paths get P3.6's visibility checks and keep their whole straight geometry; a segment, not a path, is the unit of vector graphics (P3.6a; SPEC 8.1, 8.4.3; D152, D155)
**Decision**: `paths` cells 19-22 become `vis`, `vby`, `vchk` and `backdrop`; `pv-page` runs the glyph checks on every painted
path (not clips, not shadings), on the path's painted region, with two path-specific readings: a zero-width stroke is a
hairline and not `zero-size`, and the white check keeps the backdrop it found so a consumer can ask for CONTRAST (a white
rule on a dark header is visible). A path made only of `m l h re` keeps up to 4,096 points (`PATH_KEEP_STRAIGHT`); a path
with a curve keeps D152's 128; a cut path sets `pg-truncated` (512); `pts` is bounded at 1,000,000 rows a page. A path whose
`vis` is 0 and whose `vchk` lacks the blend and soft-mask bits is "exactly known".
**Why**: D179's rule test reads a path's box, colour and alpha, so a rule clipped away, hidden in an OCG, painted over by a
later white rectangle, or white on white is a rule today, and a table built on it has a row or column that is not on the
page; and a grid drawn as one path (ReportLab, Excel exports, some PDF/UA tools) is one box, not a lattice. Both are
silent errors of the kind accuracy forbids. P3.6's checks are already exact and conservative; extending them to paths
costs one pass.
**Alternatives**: visibility inside `emon.table` (the backdrop search and the occlusion test would be written twice); an
unbounded point arena (one malicious path could hold the page's memory); recording every subpath as its own `paths` row
(paint order and the dash state are per path; `pts` already holds the geometry).
- [ ] **Signed off** (maintainer)

### D220 — The vector source's candidates: D179's sizes per segment and subpath, clip-trimmed, flat curves as lines, thin images as rules, contrast for colour, dash for style; identity first (P5.5a; SPEC 9.9)
**Decision**: as SPEC 9.9 "Vector candidates". `gv-page` has a `legacy` mode equal to `tb-collect-one` (step A: every Model
byte-identical) and a `segments` mode that becomes the default in step B, measured before and after. Collinear touching
pieces are not merged by `gv-page`: `tb-build` keeps merging them (D179), so the table stage's behaviour moves only where a
candidate changes.
**Why**: proving the new input path equal to the old one before changing the definition separates a plumbing bug from a
better rule; each change in step B (segments, visibility, contrast, trimming, curves, thin images) is then attributable.
**Alternatives**: changing input and definition in one step (an ICDAR delta could not be explained); merging in `gv-page`
(the table stage's merge tolerances would live in two places).
- [ ] **Signed off** (maintainer)

### D221 — Graphics alignment is a sweep along each rule's axis: near pairs by angle, perpendicular distance and gap; groups; elementary intervals with a coverage per source; no gap is bridged (P7.G; SPEC 9.9)
**Decision**: near = |Δangle| ≤ `gf-angle` (150 centi-degrees), perpendicular distance ≤ half the summed thicknesses +
`gf-perp` (120 cpt), axis extents overlapping or within `gf-gap` (300 cpt); groups by union-find; the axis is the vector
members' thickness-weighted position, else the tracer's; endpoints projected on it cut it into intervals, each with a
coverage bitset (exactly known vector, uncertain vector, hidden vector, trace, OCR line); runs of one verdict become `grl`
rows. Fills: box IoU ≥ 700. Grid: the 64 × 64 page grid of D202.
**Why**: rules are one-dimensional: an interval sweep is exact, deterministic and linear in the members, and it handles the
cases word alignment needs a dynamic program for (Chrome's cell-by-cell border against one tracer line is 1:n; a tracer line
broken by a dropout against one vector rule is n:1) without a cost function. Never bridging keeps open edges open: in a
ruled table a missing stretch of rule IS the span (D179).
**Alternatives**: box overlap as for words (a thin rule's box overlap is meaningless across a 1 pt offset); Hough-space
matching (floating point, and nothing a sweep cannot do here); bridging small gaps (closes real spans).
- [ ] **Signed off** (maintainer)

### D222 — Arbitration of graphics and the graphics contract: an exactly known visible vector is untouchable; the render witnesses what is not exactly known; the tracer adds only where the vector is silent; OCR only breaks ties (P7.G, P5.5a; SPEC 9.9 rows 501-571)
**Decision**: rows 501-571 of SPEC 9.9, in that order. The contract, as D201's for text: a visible, exactly known vector rule
or fill is never removed by another source (rows 510, 511, 560), and on a born-digital page without images every table is
the one the vector source alone gives (gated in `bb fusion` from P7.S, D222's half of the born-digital contract). The render
decides only where the vector source admits it cannot know: blend modes, soft masks, colour -1 (patterns, shadings), thin
images (rows 512, 513, 561, 562); row 513 drops an unwitnessed uncertain rule from the fused set (the render is the arbiter there) but never silently: the ledger records it with a reason (`unwitnessed-uncertain-paint`). Inside an image or on a raster page the tracer is
the source (530, 531, 570), weighed by the region's health; in R0 of a born-digital page a tracer-only rule is admitted only
outside glyphs and words and when the page's own tracer precision is ≥ 900 (532), and never inside a group a vector member
bounds (520). OCR's `bt-line` lowers the tracer's bar by 150 and does nothing alone (540).
**Why**: the vector source is exact where it is sure, and the tracer's errors (letter strokes, noise, Sauvola washout) are
real; asymmetric trust is what makes adding a source safe. Measuring the tracer's precision against the vector truth on the
same page is the strongest per-page reliability estimate available and costs nothing.
**Alternatives**: majority vote (two weak sources outvoting an exact one); always trusting the render (pdfium's antialiasing
and Sauvola lose hairlines and light rules); keeping an unwitnessed uncertain rule always (row 513's alternative; the coordinator answered SPEC 17 (8): dropped, but ledgered).
- [ ] **Signed off** (maintainer)

### D223 — Graphics health is per region: the page outside images and each image placement; vector witness, tracer precision, skew spread, tracer confidence; D150's shape (P7.G; SPEC 9.3, 9.9)
**Decision**: as SPEC 9.9 "Health per region", written as `hl` rows of subject kind `graphics-region` (subject -1 for R0, the
`images` row for Rᵢ). An image region's score borrows its page's tracer precision; weights vector witness 3, tracer precision
3, skew 2, confidence 2; first values, P10.1 calibrates.
**Why**: the reliability of the tracer differs most between "a render of vector art" and "a photographed table pasted into a
report", and the image placement is exactly that boundary, known from the text layer without pixels; a scanned table's own
rules agree on one skew angle and a noisy image's do not.
**Alternatives**: per page only (a mixed page would average a perfect R0 with a noisy image); per table candidate (the table
is stage 3's, after fusion).
- [ ] **Signed off** (maintainer)

### D224 — Where graphics meet words: block 600 (rule ghosts, the text of a table in an image), tried after seals and before artifacts; `xo` columns 30 and 31 (P7.G, P7.3; SPEC 9.4, 9.9, 8.4.5)
**Decision**: rows 601 (`rule-ghost`: an OCR-only word of at most two rule-like glyphs lying on a fused rule's box, ≥ 700
per-mille, to the ledger) and 610 (an OCR-only word inside a `table-in-image` region, with kin ink and plausibility, is body,
flag `in-table-image`, which rule 150 would otherwise hold as a figure's label); `fr-order` 400, 600, 200, 300, 100; the
spare `xo` columns 30 and 31 carry the two measures (`xt` 30-31 stay spare); `dr` gains `rule-ghost` (additive).
**Why**: Tesseract reads scanned borders as `|`, `l`, `I` (the P6.5 scans' table CER of 0.105 is partly that), and D201
rule 150, right for charts, would keep a scanned table in a born-digital PDF out of the body entirely. Both are decisions
about words that only graphics can make, so they are rows like any other and the dump explains them.
**Alternatives**: removing `|` characters by text pattern (a real `|` in a cell is lost); a scanned-table exception inside
row 150 (P7.3's block would read graphics; ownership blurs); a new `feats` stride (every arena width changes).
- [ ] **Signed off** (maintainer)

### D225 — Tagged tables are verified at the table level, in stage 3: a tagged grid, six checks against the page, D150's score, three verdicts; confirmed tags give header scope and spans, contradicted tags cost nothing; OCR table blocks only break ties (P5.5; SPEC 10.5; D034, D148-D150)
**Decision**: as SPEC 10.5 "Tagged tables": `tb-tag-trust` 300 to be read at all; the checks `row-band`, `col-band`,
`order`, `cover`, `rules` (against `grl`) and `geo` (against the overlapping geometric candidate); verdicts at 800 and 500;
the seven arbitration cases in `tb-select` (a confirmed tagged table that no geometry found is emitted on the tags alone only when `tg-trust` ≥ 700 and the tagged cells hold text, `source: tags`; tags never override disagreeing geometry, which decides cell boundaries while the tags may give header/scope: the coordinator's answer to SPEC 17 (7)); provenance in `tb` cells 20-23 and the table node's `src` (`struct`) and flags
(`struct-disagree`, `tag-span-conflict`). The `form` profile, P5.5's other half, moves to P5.5b unchanged.
**Why**: D034: the author's answer is evidence, verified, never obeyed. A tagged table is the only source that knows header
scope (`Scope`, `Headers`) and the spans of a ruleless table, and the only one that can rescue a whitespace table a
geometric profile scores under its threshold; it is also often wrong (layout tables tagged `Table`, two rows in one `TR`,
cells tagged `P`), which the checks catch on the page itself. Tags speak about tables, not about rules, so the verification
is the table stage's, over the fused rules.
**Alternatives**: tags as rule candidates (a TD's text box says nothing about where its border is); obeying a PDF/UA
document's tags (D150: PDF/UA raises the trust, never the score); vetoing ruled candidates with a "not a table" tree (a rule
is seen; only `whitespace` has nothing to vouch for it).
- [ ] **Signed off** (maintainer)

### D226 — The tracer's share of graphics: vertical rules from stacked runs, rules found inside large components, rule confidence defined, shading as `tint` rectangles from the light and background masks (P6.4 amendment; SPEC 8.2, 8.4.4)
**Decision**: as P6.4's "Graphics amendment". No new arena: a tint is a `prims` `rect` with flags `filled` + `tint`.
**Why**: a table grid is one component and Sauvola removes uniform tints, so without the amendment the tracer gives P7.G
neither the inner rules of a scanned grid nor its shading; both are what `table-shaded` and its scans need.
**Alternatives**: a `tfills` arena (one more `ea` code for what `prims` already says); fills from the gray view by a second
threshold (the ink layers already separate a tint by lightness).
- [ ] **Signed off** (maintainer)

### D227 — Graphics are measured against an oracle that is not emon: pdfplumber's lines and rectangles kept where poppler draws ink, inherited by scan twins through their known transform; rule and fill P/R by length and area; ICDAR adjacency F1; tagged verdict accuracy (P6.8; SPEC 14.2, 14.3; D094, D100)
**Decision**: as P6.8's brief and SPEC 14.2-14.3. `rule-p/r`, `fill-p/r`, `table-adj-f1`, `tag-verdict-acc` are ratcheted;
`twin-gap` and `tag-table-agree` are recorded only. The new fixtures: six Chrome table documents, seven hand-made `gfx-*` PDFs,
three mixed documents, eight tagged-table pairs, and the GRAPHICS set's scan twins (≤ 6 MB).
**Why**: rule truth from emon's own vector reading would make P5.5a's step B unmeasurable (D100: emon cannot be its own
oracle); pdfplumber reads paths independently and poppler's render says what is seen; the scan twins make the "same table
scanned" comparison exact. ICDAR 2013 has cell content regions and adjacency relations, not rules, so its measure is the
competition's own.
**Alternatives**: hand-labelled rules (slow, and the twins give them for free); TEDS only (a table can be right while a
rule is wrong, and P7.G's errors would hide inside the profile's tolerance).
- [ ] **Signed off** (maintainer)

### D228 — Graphic regions (`grg`): a table drawn in an image, figure candidates fused from five sources, and table hints; P8.1 consumes figures, nothing reaches the Model before it (P7.G; SPEC 9.9, 10.6)
**Decision**: as SPEC 9.9 "Regions". Rule 140's "vector-figure cluster" becomes a `figure` region; rule 610 and stage 3 read
`table-in-image`; stage 3's tie-break reads `table-hint`.
**Why**: "is this image a table?" must be answered in the page unit, before word arbitration decides whether the image's OCR
text is body; and five sources already say where figures are, so fusing them once spares P8.1 a second alignment.
**Alternatives**: deciding table-in-image in stage 3 (too late: rule 150 has held its words); leaving figures to P8.1
entirely (rule 140 needs them now).
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
- P3.5/P3.6 (`pdfgfx`, `pdfvis`, `pdfcontent`): the Rust `&mut` rule is wider than the audit says: EVERY kin function that takes a `Cells` is `&mut` in Rust (only the `emon.port` primitives `cells-at` etc. take `&`), so `(f cells (g cells))` fails when `g` is a kin reader (`fx-get`, `pl-first`, `px-nth`, `gl-get`), not only a writer: 40-odd sites were hoisted into `let`s. `close` is a Go builtin (refused at generation time, clear); a `defconst GX_SIZE` and a `defn gx-size` are both `GxSize` in C# (again nothing at generation time says so: the `PG`/`pg` case, found by the C# build only). A `^:mut` local that is only assigned and never read is a Go error too.

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
- [x] **Default `ocr-scope`** (D200) — *coordinator, per maintainer's "drive it" mandate (2026-10-01): `full` stays default until P7.S measures both; pick by the measured table then; sign-off via D200.* born-digital pages under `always` read the whole page (`full`, the default as
  planned) or the `no-text` render plus witness lines (`witness`, a fraction of the OCR time). P7.S measures both on the
  fusion set; the maintainer decides the default from that table. Does not block P7.S.
- [x] **Committed pixels for the fusion gates** (D196, D209) — *coordinator (2026-10-01): no LFS (maintainer's quota). Committed pixels ≤ ~10 MB total for gates (first pages, small crops); larger fusion sets go to the S3 corpus store (`bb corpus-push`, D085) and run only in non-gate long runs. Sign-off via D209.* once kin reads pixels, a replayable transcript must hold its
  renders and decodes (a 300-dpi RGB page is roughly 0.3-1 MB as kin's PNG), and the fusion corpus adds colour scans.
  Planned budget: the corpus additions ≤ 12 MB, `test/fusion/` ≤ 25 MB (first pages only where needed). Acceptable in the
  repository as plain files, or should these be LFS or a fetched, hash-pinned archive like `corpus/`? Does not block wave 1.
- [x] **Provisional seal text** (D205) — *coordinator (2026-10-01): yes, the Model may carry it, flagged `low-conf` + provisional, until P8.2; sign-off via D205.* P7.7 emits `seal` nodes with raw OCR text (`low-conf`) before P8.2's unwarp and
  name/date extraction. May a crawler-facing Model carry that provisional text, or should P7.7 keep it in the ledger until
  P8.2?
- [x] **Tags without geometry** (D225, SPEC 17 (7)) — *coordinator (2026-10-01), per the maintainer's drive-it mandate; sign-off via the cited decision*. A confirmed tagged table that no geometry found MAY be emitted on the tags alone, only if the tag trust is high (≥ 700) AND the text layer has text in the tagged cells, with provenance `source: tags`. Tags NEVER override disagreeing geometry: on disagreement the verdict is partial, geometry decides cell boundaries, and tags may contribute header/scope (verify-not-obey, D034).
- [x] **Unseen vector paint** (D222 row 513, SPEC 17 (8)) — *coordinator (2026-10-01), per the maintainer's drive-it mandate; sign-off via the cited decision*. A vector rule whose paint can't be modelled exactly and that the render doesn't show is dropped from the fused set but recorded in the ledger with a reason (never silently deleted).

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

### D186 — A table's claim on a line and the initials' code share cell 15 without meeting: the claim is TB_HELD (2^30) + the table's index

**Decision**: cell 15 of a line row was the table stage's claim (D178) and, on the lines' copy, the initials' code (D183: minus the drop's code points, or `4*(row+1)` plus flags). Both Engines run furniture, then the tables on the evidence lines, then the copy and `in-detect`, then headings, then `pa-build`; so the two met. A table's claim is now `TB_HELD + k` (1073741824 + the table's index, `tb-claimed` answers index + 1 or 0); `in-detect` leaves a held line's cell 15 as it is and never sees the line (`in-collect` skips it, so a big first glyph in a table cell is no initial, and a table's lines never take part in a drop initial's neighbours); `in-row`, `in-cell` (para's raised-initial and no-space bits and `pa-skip`) read a held line as no initial; the column stage's split copies the claim to the new piece instead of clearing it; the heading stage's "a held line is never a heading" tests `>= TB_HELD`, so a heading that opens with a raised initial is still a heading. `pa-build` takes the prepared copy (`lt`) of the lines and no `ln`; the tables' laws and the Engine's hosts prepare it as the Engine does. **Held to**: the unit laws on all four arms (356), the table goldens (now carrying `align`), the accuracy gate (no metric lowered; the branch's lowering of `pdf/forms block-paragraph-r` was not carried over).
**Alternatives**: a 17th line cell (every stride changes in the evidence, the columns and the readers); the claim kept on the original lines only (a column split's new rows are not there to ask).
