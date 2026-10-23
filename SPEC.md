# emon — SPECIFICATION

A document reader that turns PDFs and page images into a structured,
output-agnostic document model — and from that, into HTML — written once and
compiled into four languages. No browser, no Python, no model server.

Status: **specification**. Nothing is implemented. `DEVLOG.md` is the live
tracker; this file is the thing the tracker is derived from, and it changes
only when a decision changes. Every decision this file rests on has an ID in
`DEVLOG.md`'s Decisions section (`D###`), cited inline where it matters.

emon is **nome read backwards**. nome (`../nome`) takes HTML and lays it out
into a PDF; emon takes a laid-out page and recovers the structure somebody
laid out. The two share a toolchain (kin) and a discipline (one source, four
targets, byte-identical answers), and **nothing else**: they are completely
independent projects (D039). emon shares no code with nome and has no build,
test or runtime dependency on it. It follows nome's conventions and may read
nome's modules for reference when writing its own (§7.5).

---

## 1. What emon is

A **library**, in Rust, Go, Java and C#, that takes a document — a PDF, or a
raster image (PNG, JPEG, TIFF including multi-page, and whatever else a host
can decode) — and produces:

1. a **Model**: an output-agnostic document tree with geometry, provenance and
   confidence on every node, which an embedder can **walk directly** through
   the library API, or
2. the Model **serialized** as canonical JSON (§4), which is easy for any
   language to parse and navigate, or
3. **HTML** (§5), which is a pure projection of the Model and nothing more.

The purpose is **scraping and crawling**. HTML and JSON are easy to scrape;
PDFs and scans are not. So the product is *faithful recovery of content and
structure*, and **accuracy is the goal the whole design is organised
around** — not visual fidelity, not round-tripping, not editing.

The four implementations are one source: logic is written in **kin**
(`../kin`), generated into all four targets, and committed. Where a language
genuinely differs — decoding images, running OCR, rendering a PDF page to
pixels, threads — it differs in a small, named, hand-written **host layer**
(§7), never by letting the four drift.

Distribution beyond the four, as in nome (D001):

| Consumer | How |
| --- | --- |
| JavaScript / TypeScript | Rust → WASM (OCR via a JS-side engine through the plan/fulfil protocol, §7.3; input from `Blob`/`File`, `ArrayBuffer`, `fetch` ranges or any async byte source, no filesystem, §13.1) |
| C, C++ | Rust, `extern "C"` |
| Babashka / Clojure | a pod over Rust |

Those are **bindings, not targets**: they add no kin arm and no vocabulary.

### 1.1 What emon is not

* **Not a renderer.** It never produces pixels for a human; it produces
  structure. HTML output carries no layout CSS by default (§5).
* **Not an editor or round-tripper.** HTML → PDF → emon → HTML is how a
  corpus gets ground truth (§14.3), not a promise that the two HTMLs are equal.
* **Not a script host.** PDF JavaScript, XFA scripting, launch actions and
  embedded executables are never run. Hostile input meets a parser, not an
  interpreter (§16).
* **Not a network client.** emon fetches nothing. A PDF referencing external
  content gets that reference recorded, not resolved.
* **Not a machine-learning runtime.** Detection is rules, statistics and
  geometry, in integers (§12), so that four languages agree. Learned models are
  admissible later **only as host ports whose answers are recorded** (§7.3) —
  exactly as OCR already is. See Parking lot, DEVLOG.
* **Not a format zoo.** DOCX, XLSX, EPUB and HTML inputs are out of scope:
  their structure is already explicit and other tools read it.

### 1.2 The goal, stated so it can be measured

"Accurate" is a number per corpus, not an adjective (§14):

* **Text**: character error rate (CER) and word error rate against ground
  truth, in reading order.
* **Structure**: tree-edit-distance similarity (TEDS) for tables; precision,
  recall and level accuracy for headings; precision/recall for running
  furniture removal, figure and seal detection; reading-order agreement
  (Kendall τ over blocks).
* **Against the field**: the same corpora scored for named open-source
  extractors, offline, so "accurate" is relative to something (§14.4).

Thresholds are set **after** the first baseline measurement and only ever
ratchet upward (D023). A number written before anything was measured is a
guess wearing a unit.

---

## 2. The stages

Five stages. As in nome, **each produces a serializable value**, so every
boundary is real: each stage can be tested alone, cached, diffed across the
four languages and across versions.

    input(s): byte source(s) (§13.1, D058) — never a path
        │
        ├─ 0. INTAKE ─────────► Source     (format, pages, encryption, metadata)
        │
        ├─ 1. EVIDENCE ───────► Evidence   (per page, per source: glyphs, words,
        │      ├─ 1a text layer (kin)        paths, images, components, shapes)
        │      ├─ 1b raster + tracer (host pixels, kin tracing)
        │      └─ 1c OCR (host engine, kin-planned)
        │
        ├─ 2. FUSION ─────────► PageLayer  (per page: arbitrated words, lines,
        │                                    graphics regions, artifacts)
        │
        ├─ 3. ANALYSIS ───────► Model      (document-wide: furniture, blocks,
        │                                    reading order, headings, lists,
        │                                    tables, figures, seals, notes)
        │
        └─ 4. EMISSION ───────► JSON bytes | HTML bytes   (or: walk the Model)

**Stages 1 and 2 are per page and independent**; stage 3 is the first that
needs the whole document, because running headers, tables that continue,
heading styles and footnote numbering are all cross-page facts (D022).

**Stage 1's three sources run concurrently, on every page — born-digital
pages included — by default** (the maintainer's strategy, D014, D038):
the structured parser reads what the PDF *says*; OCR reads what the page
*shows*; the tracer reads the page's *shapes*. Stage 2 compares them region by
region and keeps the best. For a raster input there is no text layer and 1a is
empty; only when an embedder opts out of OCR (`ocr: auto` or `never`) is 1c empty for a born-digital page. The stages
do not change shape when a source is absent — fusion over one source is
arbitration that always has one candidate.

### 2.0 Stage 0 — Intake

Sniffs the format by signature (never by file name or MIME claim), then:

* **PDF**: header, xref (table or stream, incremental updates followed),
  trailer, encryption dictionary, catalog, page tree (with attribute inheritance,
  ISO 32000-1 §7.7.3.4), page count, `/Lang`, Info and XMP metadata,
  outlines, page labels, structure-tree presence (`/MarkInfo /Marked`), AcroForm
  presence, signature fields. **Repair**: a broken xref is rebuilt by scanning
  for `N G obj` (every serious reader does this and so must we; real-world
  PDFs are frequently damaged).
* **Raster**: frame count (multi-page TIFF), per-frame pixel size and
  resolution, as reported by the host's `ImageDecode` port without decoding
  pixels.

A conversion may have **several inputs** (an image set, scans of one
document; §13.1, D058): intake runs once per input, in the caller's order,
and the document's pages are the inputs' pages concatenated in that order.
The Source records, per page, which input and which page or frame of it the
page came from.

The **Source** value is small: it is what the host needs to *plan* stage 1
(which pages to render, at what resolution, what to OCR) before any expensive
work starts.

### 2.1 Stage 1 — Evidence

Evidence is **raw observations in page space** (§3.4) with a source tag and
no interpretation beyond what the source itself asserts; its schema is §8.4
(D056, revised by D096). Each source's
evidence is separately serializable (`emon-evidence` JSON, §4.5), which is
what makes fusion testable against recorded inputs.

* **1a — Text layer** (§8.1): a PDF content-stream interpreter in kin. Emits
  glyphs (Unicode, font, size, colour, render mode, transform, clip,
  visibility), vector paths (lines, rectangles, curves, fills, strokes),
  image placements, marked-content spans (`/Artifact`, MCIDs), annotations
  (links, widgets, stamps, watermarks), and the structure-tree mapping.
* **1b — Raster and tracer** (§8.2): pixels come from the host (`Renderer` for
  a PDF page, `ImageDecode` for an image). Everything after — grayscale,
  binarisation, ink-layer separation, deskew, connected components, contour
  tracing, rule and shape detection — is kin.
* **1c — OCR** (§8.3): the host runs an OCR engine (Tesseract by default) on
  bitmaps **kin prepared and asked for** (§7.3). Words, lines, boxes,
  baselines, confidences, and per-symbol alternatives where the engine gives
  them.

### 2.2 Stage 2 — Fusion

Per page: aligns the sources in one coordinate space, scores each source's
**health** per region, and arbitrates word by word and region by region
(§9). The output **PageLayer** is a single set of words and lines, each
carrying which sources contributed and a calibrated confidence, plus
classified non-text regions (rules, graphics, images, seal candidates) and
the **artifacts** that were set aside (watermarks, backgrounds, bleed-through)
with the reason.

**As planned against what is built (D195, D196).** Stages 1b, 1c and 2 run as
**page units**: after the document pass (the text layer, the catalogue, the
tagged tree, all of which read the input and none of which reads a pixel), the
Engine runs one plan/fulfil loop per page, holding only that page's bitmaps,
and the page unit answers its page's PageLayer. The PageLayer's words and
lines are written in arenas of **the same layout as the text layer's
`words` and `lines`** (`fw`, `fl`, §9.7), so every stage 3 module reads a
fused page exactly as it reads a born-digital one today; fusion over one
source is the identity (a born-digital page with no OCR answer gives the
Model it gave before fusion, byte for byte).

**Graphics are fused too (D218).** Rules (horizontal, vertical and slanted),
the edges of boxes and filled regions (shading bands, header fills) are
aligned across their sources (the PDF's own paths, the tracer, OCR's line and
table blocks) by the same steps as words: one space, alignment within
centipoint tolerances, health per region, arbitration by `fr` rows, a
confidence. The PageLayer carries ONE fused rule set and ONE fused fill set
(`grl`, `gfl`, §9.7, §9.9), and the table stage reads only those, so one table
code serves born-digital, scanned and mixed pages. The tagged tree's tables are
a prior verified at the table level, in stage 3 (§10.5, D225).

### 2.3 Stage 3 — Analysis

Document-wide structure recovery (§10): running furniture detection across
pages, the document style inventory, block segmentation, reading order,
headings and their levels, lists, tables (including tables continuing across
pages), figures and graphics placeholders, seals and signatures, footnotes,
captions and links. The output is the **Model** (§3).

### 2.4 Stage 4 — Emission

Model → canonical JSON (§4) or HTML (§5). Both are written by kin through a
text sink (§6.3), so both are byte-identical across the four targets. An
embedder that wants neither walks the Model through the library API (§3.9).

---

## 3. The document model

The Model is the product. JSON and HTML are views of it.

### 3.1 Principles

1. **Logical, not visual.** The tree is sections, blocks and inlines in reading
   order. Pages are a *table* the tree points into, not the tree's spine — a
   paragraph that crosses a page break is one paragraph with two regions.
2. **Every node says where it came from.** Geometry (one or more page regions),
   provenance (which sources contributed) and confidence are on *every*
   node, not an optional debug layer (D011).
3. **Nothing silently disappears.** What emon removes — watermarks, repeated
   headers, page numbers, background images — goes to a **ledger** with the
   reason (§3.7). A scraper that disagrees with a drop can find it.
4. **Placeholders say what they replace.** A chart becomes an empty `figure`
   with `kind: chart`, not nothing and not pixels (§10.6). Pixels are an
   opt-in (`assets`, §10.11, D117): they ride beside the tree, never inside it.
5. **Output-agnostic.** No node kind exists because HTML has an element for
   it, and none is missing because HTML lacks one (`seal`, `furniture`).

### 3.2 Node kinds

The kind vocabulary is closed and versioned; adding a kind is a schema minor
version (§4.4). Numeric codes are what kin stores; names are what JSON and the
API expose.

| Kind | Children | Kind-specific fields |
| --- | --- | --- |
| `document` | blocks, `section`s | `title`, `lang`, metadata |
| `section` | `heading` then blocks, nested `section`s | `level` |
| `heading` | inlines | `level` (1..n, unbounded), `number` (e.g. `"3.2.1"`), `numbering` (scheme), `align` (as `paragraph`) |
| `paragraph` | inlines | `align` (`start`/`center`/`end`/`justify`: logical, only when detected with confidence; §10.10, D110) |
| `list` | `item`s | `ordered`, `scheme` (`decimal`, `lower-alpha`, `upper-roman`, `bullet`, …), `start` |
| `item` | inlines, nested blocks | `marker` (the literal marker text), `value` |
| `table` | `caption`?, `rowgroup`s | `profile` (§10.5), `continued` (pages spanned), `columns` |
| `rowgroup` | `row`s | `role`: `head` / `body` / `foot` |
| `row` | `cell`s | `repeated` (a recurring header row that was de-duplicated) |
| `cell` | inlines or blocks | `rowspan`, `colspan`, `header` (`col`/`row`/none), `empty`, `align` (`start`/`center`/`end`; §10.10) |
| `caption` | inlines | `label` (e.g. `"Table 3"`), `target` (node id), `align` |
| `figure` | `caption`? | `subkind` (§10.6; D053), `label`, `alt` (from the PDF, if any), `labels` (text found inside, optional), `asset` / `noasset` (§10.11; only when `assets` is not `none`) |
| `seal` | — | `subkind` (`seal`/`stamp`/`signature`/`initials`/`digital-signature`; D053), `name`, `text`, `date`, `color`, `asset` / `noasset` (§10.11); a `digital-signature` also `signed` (boolean), `reason`, `location` (§10.7, D426) |
| `formula` | — | `text` (best-effort linear text), `display` (block/inline) |
| `code` | inlines | — (monospace block with preserved whitespace) |
| `quote` | blocks | — |
| `note` | blocks | `role`: `footnote` / `endnote` / `margin` / `sidebar` (§10.1.1, D111); `marker` |
| `field` | inlines | form field (an AcroForm widget, §10.12): `name`, `type` (`checkbox`, `radio`, `text`, `combo`, `list`), `value`, `checked`. An inline of the block that holds its label; a block among blocks |
| `furniture` | blocks | running header/footer kept by policy (§10.2): `role`, `pages` |
| `break` | — | page milestone: `page`, `label` (the printed page label). Between two blocks it is a block (it has an `id`); with an inline sibling, INSIDE a paragraph, it is an inline, written in `inlines` in its place with no `id` (D134) |
| `text` | — | a run of characters (inline) |
| `span` | inlines | `style` flags: `bold`, `italic`, `underline`, `strike`, `sub`, `sup`, `mono`, `smallcaps` |
| `link` | inlines | `href` (URI) or `target` (node id, internal) |
| `ref` | — | a note reference: `marker`, `target` |
| `initial` | inlines | a typographic initial (drop cap and kin) at the start of its block: `form` (`drop`/`raised`/`margin`), `lines` (lines it spans, `drop`/`margin`). Its text is part of the block's `text`, joined to the first word as printed (§10.9, D108-D109) |
| `linebreak` | — | a hard line break that is meaningful (addresses, poetry, table cells) |

### 3.3 Fields every node has

| Field | Meaning |
| --- | --- |
| `id` | Integer, unique in the Model, assigned in document order. **Stable for the same input and settings**, so ids are citeable across runs and across targets. |
| `kind` | From §3.2. |
| `at` | Zero or more regions `[page, x0, y0, x1, y1]` in page space (§3.4). A node spanning pages or columns has one region per **fragment** (per page and column, in reading order; D112); the column of a region is the page's `layout` cell that contains it (§3.7). Inline `text` nodes have regions only at `detail: words` (§13). |
| `src` | Provenance: the set of sources that contributed (§3.5). |
| `conf` | Confidence, integer 0..1000 (§3.5). |
| `flags` | Small closed set of warnings: `low-conf`, `ocr-only`, `text-layer-suspect`, `reconstructed` (e.g. a repaired table), `dpi-unknown`, `rotated`, `vertical`, `struct-disagree` (a table's tags disagree with its geometry, D225), `tag-span-conflict` (a tagged span crosses an edge the rules close), `in-table-image` (an OCR-only word admitted as the text of a table drawn in an image, §9.9, D224; carried by the fused flow's verdict rows, not by Model nodes), `script-unread` (a block of an OCR'd page whose script the engine has no model for: OSD names a script that no model the engine read it with reads, §8.3, D564; the text is the engine's guess, the block is `low-conf` and its confidence is held at 100). |

### 3.4 Geometry and units (D009)

* **Page space**: origin at the **top-left** of the page *as displayed* (the
  PDF `/Rotate` and crop box applied; an image's EXIF orientation applied),
  x right, y down. PDF's bottom-left origin is converted once, at stage 1.
* **Unit**: the **centipoint** (1/100 of a PDF point; 1 pt = 1/72 in), stored
  as a 32-bit integer. The largest legal PDF page (14,400 pt) is 1,440,000
  cpt — far inside `i32`. 0.01 pt is 3.5 µm; nothing on a page is positioned
  more finely than that and meant it.
* **Why not points as floats**: four languages do not agree on floating point
  (nome SPEC §7). emon uses **no floats anywhere in kin** — matrices are
  fixed point in `I64` with 24 fractional bits (D103; 16.16 was not enough), angles are integer trigonometry (emon's own), ratios
  are per-mille integers.
* **Why centipoints and not nome's 1/64**: the serialized form is decimal
  (`72.5`, never `72.515625`), because the consumer is a scraper, not a
  layout engine. A unit that prints exactly in decimal is worth more than a
  power of two here.
* **Raster pages** convert pixels to points by the image's resolution. When the
  file states none, emon **estimates** it from text size (a body x-height near
  the corpus median) and flags `dpi-unknown`; the pixel geometry is also kept
  in the page table so nothing is lost.

### 3.5 Provenance and confidence (D010)

**Sources** are a bitset:

| Bit | Source |
| --- | --- |
| `text` | PDF text layer (visible glyphs) |
| `hidden-text` | PDF invisible text (render mode 3 — usually a previous OCR pass) |
| `ocr` | this run's OCR |
| `trace` | the tracer (rules, shapes, components) |
| `vector` | PDF vector paths |
| `struct` | tagged-PDF structure tree |
| `annot` | PDF annotations, form fields, signature dictionaries |
| `outline` | PDF bookmarks, page labels |
| `infer` | analysis heuristics (every structural node has this) |

**Confidence** is an integer 0..1000 whose *meaning is calibrated*: on the
evaluation corpora, among nodes of a kind with confidence *c*, about *c*/1000
of them are correct (§9.6). A confidence that is not calibrated is a ranking,
and the API must not pretend otherwise; the Model header says
`"conf": "calibrated"` since P10.1 (it said `"uncalibrated"` before: the
rules' own raw scores went out as they were). What leaves the Model is the
per-kind map of §9.6 applied to the rule's raw score, by the serializers (JSON
`conf`, HTML `data-emon-conf`); a kind the corpora gave too few nodes to fit
has no map and keeps its raw score, and `bb calibration` says which.

### 3.6 Text

* Stored as Unicode code points; serialized as UTF-8.
* **Normalization** (D029, D142): NFC, plus decomposition of the Alphabetic
  Presentation Forms ligatures (`ﬁ` → `fi`, U+FB00–FB06), removal of soft
  hyphens (U+00AD) at line ends, and the fold of the Kangxi Radicals
  (U+2F00–2FD5) and the CJK Radicals Supplement characters the UCD pairs with a
  unified ideograph to that ideograph (`⼀` → `一`: a PDF's ToUnicode names the
  glyph by the radical where the text is the ideograph; the glyph evidence keeps
  the code point the font said), and (D582) the NFKC fold of the Arabic
  Presentation Forms (U+FB50–FDFF, U+FE70–FEFF: a PDF draws the shaped glyph of
  a letter, and the text of the page is the letter; a mark's isolated form folds
  to the mark alone, without the space UAX #15 puts before it). A spacing
  diacritic drawn over the letter after it (TeX's `¨` and `u`) is that letter
  and its combining mark, so NFC composes it (`ü`). Nothing else is folded —
  full-width forms, superscript digits and the like are content.
* **Line-end hyphenation** (`-` + newline + lowercase) is joined **only** with
  a locale hint and a hyphenation dictionary agreeing (Liang patterns, emon's own tables);
  without a locale the hyphen is kept and the words joined without a space
  (`multi-page` survives; `infor-mation` survives as written, flagged). Wrong
  dehyphenation corrupts real compounds; kept hyphens are merely ugly.
  A column or page break inside a paragraph is a line end for this rule,
  nothing more (D112).
* **Initials** (drop caps, §10.9): the initial's text is the start of the
  block's text and is joined to what follows as printed — `O` + `nce upon`
  is `Once upon`, never `O nce upon` and never a separate block. Whether a
  one-letter initial is a word of its own (`A` + `long time`) cannot be
  seen on the page; it is joined unless a tagged tree says otherwise (D108).
* **Right-to-left** text stored in *logical* order. PDFs frequently store
  visual order; stage 2 reorders with UAX #9 (emon's `bidi`, D162).
* **Whitespace** is normalized: one space between words, line breaks inside a
  paragraph become spaces, `linebreak` nodes only where the break carries
  meaning (§10.1).

### 3.7 Pages, furniture and the ledger

The Model has three top-level collections besides the tree:

* **`pages`**: per page — index, printed `label` (from `/PageLabels`, else a
  detected page number, else none), size, rotation, source kind (`vector`,
  `scanned`, `mixed`, `image`), resolution (raster), text-layer health (§9.3),
  and per-page variable furniture (e.g. a Bates number) that policy kept
  (§10.2); and the page's **`layout`**, the column map reading order was
  decided over (§10.1.1, D111): one cell per column fragment, `band` (the
  horizontal band, top first), `col` (its column in the band, in reading
  order), `role` (`body`, `sidebar`, `margin`) and `box`. Absent when the page
  is one plain column.
* **`furniture`**: running headers/footers retained once, as templates.
* **`dropped`**: the ledger — every suppressed element with `reason`
  (`watermark`, `background`, `page-number`, `running-title`, `bleed-through`,
  `duplicate-header-row`, `ocr-ghost`, `clipped`, `offpage`, `invisible`, `rule-ghost`, `unwitnessed-uncertain-paint`, `ocr-empty`, `ocr-unrepresented`),
  its region, its text if any, and the confidence of the decision.
* **`assets`** (only when the `assets` setting is not `none`, §10.11): the
  table of pixel assets — id, file name, media type, origin, byte size,
  SHA-256, pixel size and the regions that use it. The bytes themselves are
  **out of band**: the library hands them over, HTML may inline them, JSON
  never carries them.

### 3.8 Representation in kin (D011)

kin cannot allocate: no arrays, no collections, no constructors (nome
`kin/ir.kin`). So — like nome's DOM — the Model is **arenas of integers**, in
`Cells` the host allocates and kin fills:

    nodes    stride 16: kind, parent, first-child, last-child, next, prev,
             attr-head, text-start, text-len, region-head, region-count,
             src, conf, flags, sub (level / role / kind-specific), aux
    attrs    stride 5:  key, value-kind (int|text|node), value, len, next
    regions  stride 6:  page, x0, y0, x1, y1, next
    text     one code point per cell; every string in the Model is a run here
    pages    stride 16
    ledger   stride 10
    layout   stride 8:  page, band, col, role, x0, y0, x1, y1 (D111; a
             page's cells are one contiguous run, named from its pages row)
    assets   stride 24 (§10.11, D117; empty unless `assets` is on)
    uses     stride 8:  asset, node, page, x0, y0, x1, y1, next
    abytes   a `Buf` (§7.2): every asset's encoded bytes, back to back

A node is its index. Four languages cannot disagree about an index, and a
Model that is a few integer runs is trivially serializable and trivially cheap
to hand across a WASM or C boundary.

Evidence and PageLayer are arenas of the same kind, defined in
`emon.evidence`; the evidence arenas, their strides and the conventions
that let three sources be aligned are §8.4.

### 3.9 Walking the Model from the library

The **accessors are kin** (`node-kind`, `node-first-child`, `node-next`,
`node-text`, `node-region-count`, `node-attr`, …) so they mean the same thing
in every language. The **host layer wraps them idiomatically** and adds
nothing: a Rust iterator, a Go method set, Java/C# `Iterable<Node>` classes.
`Node` is a (Model, index) pair; it never copies. The surface is spelled once
here and compared across hosts by a gate (as nome's `bb embedders`), so it
cannot drift the way nome's did.

    model.root()                       → Node
    node.kind() / .id() / .conf() / .sources() / .flags()
    node.children()                    → iterable Node
    node.text()                        → the node's plain text (derived)
    node.regions()                     → iterable (page, x0, y0, x1, y1)
    node.attr(name)                    → typed value or absent
    model.pages() / model.furniture() / model.dropped()
    model.find(kind)                   → document-order iterable
    model.to_json() / model.to_html(options)
    model.assets()                     → iterable Asset (§10.11)
    model.asset(id) / node.asset()     → Asset or absent
    asset.id() / .name() / .media_type() / .origin() / .sha256()
         / .width() / .height() / .uses() / .bytes()   (a borrow; JS: a copy or a Blob; P3.13: Rust and Go copy)
         / .from() / .object() / .dpi() / .flags()     (P3.13: the rest of the table's fields)
    node.noasset()                     → the reason a figure or seal has no pixels, or absent (P3.13)
    model.asset_files()                → iterable (name, media type, bytes): the sidecar

---

## 4. Serialization: emon JSON (D012)

### 4.1 Why JSON

The model must be "serializable to an easily parseable/navigable format".
**JSON** is the one format every language, every scraper and every command-line
tool (`jq`) already reads. The alternatives were weighed (D012): CBOR/protobuf
(binary: faster but not inspectable, and a schema compiler is a dependency a
consumer does not want), XML (verbose; the real value — hOCR, ALTO, PAGE —
is as *exporters*, parked), and nome's integer stream (perfect for gates,
useless to a consumer).

JSON also serves as the **identity-gate subject**: because kin writes it byte
for byte, "the four targets produce the same JSON" is the whole
cross-language claim in one comparison.

### 4.2 Shape

Nested, because walking is what consumers do; ids on every node, because
citing is the other thing they do.

```json
{"emon":"1.1","generator":"emon 0.1.0",
 "source":{"format":"pdf","version":"1.7","pages":2,"tagged":false,
           "encrypted":false,"lang":"en"},
 "settings":{"locale":null,"ocr":"always","detail":"blocks"},
 "conf":"calibrated",
 "meta":{"title":"Annual Report 2025","author":"…","created":"2025-03-01T10:00:00Z"},
 "pages":[{"n":1,"label":"1","w":612,"h":792,"rot":0,"kind":"vector","health":987}],
 "body":{"id":0,"kind":"document","children":[
   {"id":1,"kind":"section","level":1,"children":[
     {"id":2,"kind":"heading","level":1,"number":"1","text":"Introduction",
      "at":[[1,72,90.5,540,110.25]],"src":["text","ocr","infer"],"conf":962},
     {"id":3,"kind":"paragraph","text":"Revenue grew by 12% …",
      "inlines":[{"kind":"text","text":"Revenue grew by "},
                 {"kind":"span","style":["bold"],"children":[{"kind":"text","text":"12%"}]},
                 {"kind":"text","text":" …"}],
      "at":[[1,72,120,540,188]],"src":["text","ocr","infer"],"conf":991}]}]},
 "furniture":[{"id":40,"role":"header","template":"ACME Corp — Confidential","pages":[1,2]}],
 "dropped":[{"reason":"page-number","page":1,"at":[1,290,760,322,772],"text":"1","conf":998}]}
```

Rules:

* **Leaf blocks carry `text`**, the node's plain text in reading order — the
  field a scraper wants 90 % of the time. **`inlines` appears only when it
  carries information** (any style, link, note reference or line break);
  when present, concatenating its text equals `text`. So a consumer never
  has to walk inlines to get words, and never loses emphasis or links by not
  walking them.
* Geometry in **points, as decimals with at most two fractional digits**,
  converted from centipoints by kin's own integer formatter.
* Absent fields are **omitted**, never `null`, except where `null` is itself
  the answer (`"locale":null`).
* **Inline nodes** (`text`, `span`, `link`, `ref`, `linebreak`, and a `field` that
  is among inlines, §10.12) carry no `id` (as in the example above); blocks do (D052). A node's own fields appear in
  the order `id`, `kind`, its kind's fields, `text`, `inlines`, `children`,
  `at`, `src`, `conf`, `flags`. `furniture` items have no `kind` (the array
  says what they are) and write `template` for their text. `settings` always
  carries `locale`; `furniture` and `dropped` are always present, `[]` when
  empty (D051).
* An **`initial`** inline (§10.9) carries `form` and `lines` and its text as
  `children`, like a `span`; its presence makes `inlines` informative, and
  `text` still reads `Once upon` (D109). A page's `layout` is
  `[{"band":0,"col":0,"role":"body","box":[x0,y0,x1,y1]}, …]` in reading
  order (D111).
* **`assets`** (D117) is written after `dropped` only when the `assets`
  setting is not `none` (then always, `[]` when empty), with the asset
  settings in `settings`; with `assets: none` the JSON is byte-for-byte
  what it would be without the feature. It holds metadata only — never
  bytes, never base64 (§10.11).

### 4.3 Canonical bytes

The same Model always serializes to the same bytes (§12):

* Keys in a **fixed order per kind**, the order written in `emon.json`, never
  a hash map's.
* No insignificant whitespace (a `pretty` setting adds a fixed two-space
  indent, equally canonical).
* Strings: `"` `\` and U+0000–U+001F escaped (`\n`, `\t`, `\u00XX`), U+2028
  and U+2029 escaped (for JavaScript consumers), everything else raw UTF-8.
  Lone surrogates cannot occur (the text arena holds scalar values; invalid
  input decodes to U+FFFD at the source).
* Integers only, plus the fixed two-decimal geometry form. No exponents, no
  `-0`.

### 4.4 Versioning

`"emon": "MAJOR.MINOR"`. **Minor**: additive (a new kind, field, flag or
reason) — consumers must ignore what they do not know. **Major**: anything
that changes the meaning of an existing field. A JSON Schema for each version
is committed (`schema/emon-1.json`) and every gate validates output against
it. The emitter writes `"1.1"` (the `initial` inline, `align` on headings, captions
and cells, the note role `sidebar`, `pages[].layout`: D109-D111, D132); the schema
accepts `"1.0"` too.

### 4.5 The evidence dump

Evidence and PageLayer have their own debug serialization (`emon-evidence`),
with the same canonical rules. It is not a public contract — it changes as the
algorithms do — but it is what fusion tests are written against, and what a
maintainer reads to answer "why did emon think that". Its version is "1"
(§8.4.6, D096). Its arrays are the
evidence arenas (D056): `glyphs`, `words`, `lines`, `paths`, `images`,
`components`, `ocrwords` and `spans`, the marked-content spans of the text
layer (D076, D101: tag, `/MCID`, `/Artifact` class, Type and Subtype,
`/ActualText`, `/Alt`, `/E`, `/Lang`, the run of glyphs inside, and the Form
XObject they came from).

---

## 5. HTML (D013)

HTML is the **last step and a pure function of the Model**. It adds no
information and removes only what the options say.

### 5.1 Mapping

| Model | HTML |
| --- | --- |
| `document` | `<!doctype html><html lang=…><head><meta charset="utf-8"><title>…</title><meta name="generator" content="emon …">` metadata as `<meta name=…>`, then `<body><main>` |
| `section` | `<section>` |
| `heading` level *n* | `<h1>`…`<h6>`; level > 6 is `<h6 aria-level="n">`; `number` kept as `data-emon-number` and in the text |
| `paragraph` | `<p>` |
| `list` / `item` | `<ul>` / `<ol start type>`; `<li value>` |
| `table` | `<table>` with `<caption>`, `<thead>`/`<tbody>`/`<tfoot>`, `<tr>`, `<th scope>` / `<td rowspan colspan>` |
| `figure` | `<figure data-emon-kind="chart" role="img" aria-label="…">` + `<figcaption>`; **no `<img>`** unless it has an asset (§10.11): then `<figure data-emon-kind="chart"><img src alt width height>…<figcaption>` |
| `seal` | `<figure data-emon-kind="stamp" data-emon-name="…" role="img" aria-label="Stamp: …"></figure>`; with an asset, the label moves to the `<img alt>` as for a figure; a digital signature also `data-emon-signed="true"` or `"false"`, `data-emon-reason` and `data-emon-location` |
| `formula` | `<figure data-emon-kind="formula">` with the linear text in `aria-label` (MathML parked) |
| `code` | `<pre><code>` |
| `quote` | `<blockquote>` |
| `note` | `<aside role="doc-footnote" id="fn-…">`; `ref` → `<a role="doc-noteref" href="#fn-…">` |
| `furniture` | `<aside data-emon="furniture" data-emon-role="header">` once, at the top of `<main>` |
| `break` | `<span data-emon-page="3" data-emon-page-label="iii"></span>` — empty milestone, inline or between blocks |
| `field` | `<span data-emon="field" data-emon-name="…" data-emon-type="text">value</span>` (never a live `<input>`); a check box or radio button is an empty `<span … data-emon-type="checkbox" role="checkbox" data-emon-checked="true" aria-checked="true" data-emon-value="1"></span>` (§10.12, D548) |
| `span` styles | `<b>`, `<i>`, `<u>`, `<s>`, `<sub>`, `<sup>`, `<code>`, `<span data-emon-style="smallcaps">` |
| `link` | `<a href>`; internal targets become `#emon-ID` |
| `linebreak` | `<br>` |
| `initial` | `<span data-emon="initial" data-emon-form="drop" data-emon-lines="3">O</span>nce` — no styling, the text intact; option `initials: plain` writes the bare text (D109) |
| `align` | `data-emon-align` (D069); with option `align-style` also `style="text-align:…"` and `dir="auto"` (D110) |
| `note` `margin`/`sidebar` | `<aside data-emon-role="margin">` in reading order, no `doc-footnote` role, and no `id="fn-N"` unless a `ref` or a `link` targets it (D113, D133) |
| column fragments | nothing by default: **one logical flow in reading order**; with `geometry`, `data-emon-col="page:band:col"` on each block (D113) |

`<b>`/`<i>` rather than `<strong>`/`<em>`: emon knows the text was bold, not
that the author meant importance. HTML should not claim what emon cannot know.

### 5.2 Options

`geometry` (adds `data-emon-at`), `ids` (adds `id="emon-N"` on every node),
`confidence` (`data-emon-conf` on nodes below a threshold), `ledger` (dropped
elements as `<template data-emon="dropped">` so they are inert but present),
`furniture` (`keep`/`drop`/`policy`), `pretty`, `align-style` (inline
`text-align`, off), `initials` (`span`/`plain`, default `span`), `assets` (`inline` —
the default: `data:` URIs — / `external`: `src` is `asset-prefix` + the
asset's file name, the caller writes the files / `omit`: placeholders as if
no asset existed; a Model without assets emits the same HTML under all three;
§10.11), `asset-prefix` (a string, default empty). **Defaults are clean HTML**:
no ids, no geometry, policy furniture, milestones on. Escaping: `&`, `<`, `>`,
`"` and nothing else; attribute values always double-quoted.
The rest of the mapping this table leaves open (extra `data-emon-*` attributes,
forbidden code points, `pretty` layout, what a caption or a seal's label is)
is DEVLOG D068-D069 and D133; the code is `kin/html.kin`. The option bits are `ids` 1,
`geometry` 2, `confidence` 4, `ledger` 8, `pretty` 16, furniture-drop 32, no-milestones 64,
`initials: plain` 128 and `align-style` 256 (D133); `assets: external` 512 and `assets: omit` 1024 (P3.13: the 128 and 256 §10.11 first
named were taken by then). The option integer is those twelve bits and the confidence threshold times 4096.

---

## 6. Targets and kin

### 6.1 Targets (D001, D002)

Rust, Go, Java, C#; **Rust is the reference target** — the bindings are built
from it, benchmarks are stated against it, and a disagreement is adjudicated
by it. **Every target exists from day one; every host layer does not**
(DEVLOG's ordering principle): Rust's host layer first, Go's second (the
proof the arrangement survives a different error model), Java and C# after.

### 6.2 What kin can and cannot say, as design constraints

Facts about kin that shape emon (verified in `../kin` and `../nome`):

* **Integers, byte blobs and integer arenas.** No floats (by nome's rule),
  no collections, no allocation; strings are code-point runs in `Cells`.
  Hence §3.8.
* **`^:throws`** marks fallibility: `Result` in Rust, `(T, error)` in Go,
  exceptions in Java/C#. Failures **originate at ports or at explicit
  validation** and kin only propagates (nome D138/D139).
* **One namespace per file, one module per namespace per target,
  generated code committed**, and a drift gate that re-emits and compares.
* **Vocabularies are the project's.** `kin.lang` supplies shape forms
  (`defn`, `let`, `if`, `while`, `case`, `defdata`, `defstruct`, operators)
  for all four targets. Anything else is a **project-local vocabulary**
  (§6.3) or a **host-linked form** (`@kin:link:` annotations in host files,
  scanned into an ordinary vocabulary — §7).

### 6.3 emon's vocabularies (D028)

Mirroring nome's `kin/src/nome/{lang,test,targets,project}.cljc`:

* **`emon.lang`** — tags, every one with four `:types` and a Go `:zero`
  (nome D16): `I32`, `U32`, `I64`, `Bool`, `Blob`, `Cells`, `Sink`, plus
  emon's own:
  * `Cpt` — centipoints (§3.4). Same machine type as `I32`, a different tag
    so geometry is visible in signatures and a form can refuse to add a `Cpt`
    to a pixel count.
  * `Px` — raster pixels. Converting `Px` ↔ `Cpt` goes through a named form
    carrying the resolution; there is no implicit mix.
  * `Fx` — fixed point in `I64`, 24 fractional bits (D103: PDF reals, matrices).
  * `Conf` — per-mille confidence.
  * `Bitmap` — opaque host pixel buffer (§7.2).
  * `Src` — an input byte source as kin sees it: a length and byte reads
    that may **miss** (§13.1, D058). Not a `Blob`: nothing guarantees the
    bytes are in memory.
  * `Text` — a text sink (§6.3.1).

  And emon's own cast and wrapping-arithmetic forms (`i64`, `i32`, `mul64`,
  `add64`, `shl64`, `shr64`), written here following nome's pattern (§7.5).
* **`emon.port`** — **not hand-written**: scanned from `@kin:link:`
  annotations in `host/*/`, exactly as nome's `nome.port` is. `kin.host/
  check-agreement` is the gate that three hosts implementing a form and a
  fourth not is caught at build time.
* **`emon.test`** — nome's `deftest` + assertion shape, so laws written once
  run as native tests on every target with a framework.

#### 6.3.1 The vocabulary extensions emon needs that nome does not have

1. **`emit-ascii` — a string literal into a text sink.** kin renders a
   string literal with `pr-str`, which is correct for ASCII in all four
   targets and has no tag. nome worked around the absence of string literals
   with generated code-point tables (`pdfwords.kin`), which is right for
   PDF's hundreds of fixed words and wrong for a JSON emitter whose every
   key is a literal. So: `(emit-ascii t "\"kind\":")` is a project-local form
   that **checks at generation time** that the literal is printable ASCII
   (refusing by name otherwise), then renders the host call
   `crate::port::text_ascii(t, "…")` / `port.TextAscii(t, "…")` / … — the
   host appends the bytes. Four spellings, one fact, verified where it is
   written.
2. **`Text` sink forms** — `text-cp` (append one code point as UTF-8),
   `text-ascii`, `text-i32` (decimal), `text-len`. Host-linked. One sink type
   for JSON, HTML and the evidence dump, because the formats live in the
   *order kin emits*, not in the host (nome's `Sink` rule, generalised to
   text).
3. **`Bitmap` forms** — `bitmap-w`, `bitmap-h`, `bitmap-stride`,
   `bitmap-channels`, `bitmap-u8`, `bitmap-new`, `bitmap-set-u8`, plus a small
   number of **bulk** forms added *only* when the not-worse rule demands it by
   measurement (§12.3). P2.7 measured and adopted four (D092):
   `bitmap-histogram`, `bitmap-threshold-into`, `bitmap-integral`,
   `bitmap-next-nonzero`, with `cells-grow` on the `Cells` side. Host-linked.
   `bitmap-put-span` (D492) copies a row, or part of one, from a `Cells` into a
   `Bitmap` in one call; the transcript reader (`emon.pngread`) puts each
   unfiltered row there, and its per-sample `bitmap-set-u8` version stays as the
   oracle of a law.
4. **`defenum`** (candidate, P0 decides): a kind table — name, integer
   code, JSON spelling — generating the `nk-*` constants *and* the
   code→spelling emitter from one declaration, so a kind cannot be added to
   the arena without also being serializable. nome writes these by hand as
   `(defn nk-element [] (return 1))`; with ~30 node kinds, ~12 ledger reasons
   and ~10 figure kinds, one table is worth a form.

Each of these is the ordinary kin mechanism used as intended: a form is
`(fn [ctx form] …)`; a tag is data; a host link is an annotation. None of them
changes kin. If one turns out to be general (the `emit-ascii` check is a
candidate) it is offered to `kin.lang` the way nome's Go arms were.

---

## 7. The line between kin and host

**The rule, inherited from nome (D003): kin code never names a library
type.** Where an ecosystem already has a good answer — image codecs, OCR,
rendering a PDF page to pixels, cryptographic primitives, threads — emon
wraps it in an in-project abstraction, gives that a kin tag, and lets each
host implement it however it likes.

### 7.1 In kin (pure logic, four identical implementations)

* **Format sniffing**; **PDF**: lexer, objects, xref/trailer (and repair),
  object streams, incremental updates, page tree, content-stream interpreter,
  graphics state, text state, fonts and encodings (standard, WinAnsi,
  MacRoman, Symbol, ZapfDingbats, `Differences`), glyph-name → Unicode (AGL),
  `ToUnicode` and predefined CJK CMaps (as `defdata` tables), CFF/Type 1/
  TrueType program reading *for glyph names and widths*, marked content, annotations, outlines, page labels,
  structure tree, AcroForm field values, signature dictionaries, metadata
  (Info + XMP read as text).
* **Stream filters that are not image codecs** (D005): `FlateDecode` (emon's own
  `inflate`), `LZWDecode`, `ASCII85Decode`, `ASCIIHexDecode`,
  `RunLengthDecode`, PNG/TIFF predictors.
* **The standard security handler's logic** (key derivation, per-object
  keys, R2–R6) — calling host primitives for MD5, RC4, AES and SHA-2 (D006).
* **All raster analysis** on host-supplied bitmaps: grayscale, colour
  clustering / ink-layer separation, adaptive binarisation (integer Sauvola),
  deskew, connected components, contour tracing, rule detection, shape
  primitives, the seal polar-unwarp (§10.7).
* **Asset pixels and bytes** (D117): PDF image samples, `/Decode`, colour
  spaces, masks, orientation and crops; the canonical PNG writer and its
  DEFLATE compressor; base64; the JPEG marker walk that allows a passthrough.
* **All fusion, all analysis, all emission**, and the Unicode machinery they
  need — normalization, bidi, script, grapheme and word segmentation,
  hyphenation — written in emon (§7.5).

### 7.2 Host ports (hand-written per target, kin-tagged, linkage annotated)

| Port | Answers | Why not ported |
| --- | --- | --- |
| `Blob` | immutable bytes **in memory**, read by index (big- and little-endian helpers): decoded streams, spans cut from a `Src`, test files | every language's byte buffer differs (nome) |
| `Src` | an input byte source (§13.1, D058): `src-len`, `src-u8` (may miss), `src-missed`, `src-slice` (span → `Blob`), `src-of-cells`, `src-of-blob`, `src-hint`; the PDF code reads it through `emon.pdfsrc` (`ps-len`, `ps-u8`, `ps-missed`, `ps-hint`) and the forms `src-slice` and `src-of-cells`, which return borrows and so are forms, not functions (D074) | the chunk cache, and whether a read can block or must be awaited, are the host's |
| `Cells` | growable `i32` arena: len, at, set, push, drop | kin cannot allocate (nome) |
| `Text` | append code point / ASCII literal / decimal; host encodes UTF-8 | output buffers differ; the *format* is kin's |
| `Buf` | growable **byte** buffer: `buf-len`, `buf-at`, `buf-push`, `buf-set`, `buf-truncate` (to a length), `buf-append-blob` (a span of a `Blob`), `buf-blob` (borrow as a `Blob`, valid until the next write; a copy on Java and C#); writes keep the low eight bits, `buf-push` answers the new length; what asset bytes and the PNG encoder write into (D117, D123) | `Cells` spends four bytes a byte, which a 32 MiB asset budget cannot afford in a browser |
| `Bitmap` | 8-bit gray or RGB, row-major, top-left; new/read/write | pixel buffers differ; decoding is a codec's job |
| `ImageDecode` | encoded bytes → frames → `Bitmap` + resolution | four mature codec stacks exist; includes PDF's `DCTDecode`, `JPXDecode`, `JBIG2Decode`, `CCITTFaxDecode` (D005) |
| `Renderer` | PDF page + resolution → `Bitmap` | a correct PDF rasteriser is pdfium/MuPDF-sized (D007) |
| `Ocr` | `Bitmap` + request → words/lines/symbols with boxes and confidences | Tesseract is a C++ library; any engine may be substituted |
| `Crypto` | MD5, SHA-256/384/512, RC4, AES-CBC decrypt, and AES-CBC encrypt (R6's hash): seven `crypto-*` forms over spans of a `Blob`, appending to a `Cells` (D097) | every standard library has them but Rust's, whose host writes them out |
| `Task` | structured fork/join (§12) | four unrelated concurrency models (nome) |
| `Limits`, `Settings` | `limit-of`, `setting-of`, named breach | nome §5.5's pattern, unchanged |

**Contracts**, stated so four hosts can be held to them:

* **`ImageDecode`** must: apply EXIF orientation; composite alpha onto white;
  reduce to 8 bits per channel; convert CMYK/Lab/indexed to RGB (ICC-aware
  where the platform is, and the host's choice is recorded in the transcript);
  report resolution in dots per inch ×100 (integer) or 0 for unknown; expose
  frames of a multi-page TIFF without decoding the others. It must **never**
  resample. Bilevel images (JBIG2, CCITT, 1-bit TIFF) arrive as 8-bit gray
  0/255 with a `bilevel` flag — kin skips binarisation for them.
  *As built (P6.1, D187-D190)*: the formats are PNG, JPEG and TIFF, sniffed by
  signature in kin; the reduction to eight bits is `(v * 255 + max / 2) / max`,
  alpha is composited after it, gray stays gray, orientation comes from EXIF
  (JPEG, PNG `eXIf`) or the TIFF tag, and CMYK is the naive `(255 - c)(255 - k)`
  with no ICC (the platforms have none the four hosts share). What the port
  does not read is refused by name (`unsupported-image`): CMYK JPEG and the PDF
  filters (a PDF image's codec is the `decode-img` request's, §10.11, P6.1a), JPEG in TIFF, float samples, Lab. Pixels are identical on
  Rust and Go for PNG and TIFF; a JPEG's live decode may differ by at most 6 per
  byte between hosts, and is exact through a transcript (D190).
* **`ImageDecode` and `Renderer` read the input from the host's byte
  source** (§13.1), named in the request by input index — never by a path,
  and never by kin handing them a copy of the file. A codec or rasteriser
  that wants random access gets it from the source (pdfium's custom-loader
  callback, pdf.js's range transport, a TIFF reader over a seekable handle);
  one that insists on a slab reads the span it needs from the source itself.
  In an async host these reads are awaited inside the host's fulfilment,
  outside kin.
* **`Renderer`** must: render the page as displayed (rotation and crop box
  applied), white background, anti-aliased, at the requested resolution; and
  accept a **layer mask** — render *with* text, *without* text, *text only*
  — where the engine supports it (pdfium does). The without-text render is
  what the tracer and graphic classifier want; the text-only render is how a
  broken text layer is re-read by OCR without the background (§9.5). An
  engine that cannot mask says so and kin plans around it. It must also
  answer a **crop** (D117): the pixel rectangle `px py pw ph` of the page as
  it would be rendered whole at that resolution and layer — the same pixels,
  cut out, never a re-fit — with annotations' normal appearances painted (a
  stamp is often an annotation). pdfium does this directly
  (`FPDF_RenderPageBitmapWithMatrix` with a translation and a clip); an
  engine that cannot may render the page and cut. **As built** (D245-D248):
  pdfium makes `no-text` and `text-only` by removing page objects, and
  answers the watermark layers (`lay-no-watermark`, `lay-watermark-only`)
  `unsupported`, because pdfium cannot read the NAME values of an
  `/Artifact`'s `/Subtype`; kin therefore never relies on a watermark-free
  render (§9.5 defence 1 classifies from the text layer instead). A crop is
  the fulfiller's cut of the whole-page render it keeps. **An Engine with no
  `Renderer` answers `render` and `crop` `unsupported`** (D199, revising
  D245's `fulfil-failed`), as an Engine with no `Ocr` answers `ocr`: a gate
  with no engine converts every born-digital page as fusion over one source.
* **`Ocr`** must: take a `Bitmap` and a request (languages, page-segmentation
  mode, region, whether symbol alternatives are wanted); return integers only
  — boxes in the *request bitmap's* pixels, confidences ×10 as integers,
  text as code points — through a `Cells` layout `emon.ocr` defines. It
  declares capabilities (languages installed, symbol boxes, alternatives,
  orientation/script detection) once per engine. Tesseract is the default
  binding in every host; an embedder may register another engine
  (PaddleOCR, a cloud service) behind the same port.
* **`Crypto`** is pure functions over bytes; a host must not substitute a
  "compatible" algorithm.

### 7.3 Plan and fulfil: kin never calls an expensive port (D008)

`Renderer`, `Ocr` and `ImageDecode` are slow, may be asynchronous (JavaScript
cannot block on a promise — nome §5.3 rule 8 learnt this), and are **not
deterministic across hosts** (two Tesseract builds differ; two JPEG decoders
differ in the last bit). So kin does not call them. Instead:

    loop:
      kin: given Source and the answers so far → a list of REQUESTS
           (render page 3 at 300 dpi, text-only layer;
            OCR bitmap #7 region R, psm 6, langs [deu, eng]; …)
      host: fulfil them — concurrently, awaiting, however it likes —
            and hand back ANSWERS, each keyed by its request
    until kin asks for nothing more

Requests and answers are **integer records in `Cells`**, so they are
serializable for free. Consequences, each of which is the point:

1. **Concurrency is the host's**, where it belongs (§12): the host can start
   OCR on page 5 while kin parses page 2, because nothing waits inside kin.
2. **Async hosts work** (WASM + tesseract.js): the host owns the loop.
3. **Transcripts**: the complete set of answers for one run, written out, is a
   **transcript**. Given the same input bytes and transcript, the kin core is
   a pure function — identical on every target. The cross-target identity
   gate runs from transcripts (§14.1), so it tests emon and never tests
   whose Tesseract is installed.
4. **Rounds** are what make second opinions possible: fusion that finds a
   disagreement can plan a targeted re-OCR (upscaled, cleaned ink layer,
   different segmentation mode, a seal unwarped to a strip) in the next
   round. Rounds are bounded by a limit and terminate because each round
   asks only about regions the previous round flagged.

**Records and transcripts** (D089-D091): a request is `[len kind id args...]`
and an answer `[len kind id status args...]` in a `Cells` (layouts in
`kin/plan.kin`: render, decode, ocr, bytes); ids are kin's; render and decode
name their input by index. A transcript is a directory: `transcript.edn`
(entries in (round, id) order, each the whole request record and its answer)
plus one lossless PNG per bitmap. Replay looks a request up by its whole record
and answers `transcript-miss` rather than guess. `max-rounds` and `fetch-rounds`
are named limits of the host loop.

**`decode`** 2 (`input offset length filter frame`) is `ImageDecode` (D187): the span
is a whole image file (`filter` 0), `frame` >= 0 asks for that frame's pixels and a negative
`frame` for the header of frame `-frame - 1` only (size after orientation, resolution, frame
count: stage 0 of a raster input, §2.0). Its answer is `status w h channels dpi100 bilevel
frames`, and `status` is `ok`, `failed`, `unsupported` or `limit` (the host's pixel bound,
which kin names as the `pixels` limit).

**`ocr`** 3 (`bitmap psm flags x y w h lang...`; D211, D214) is `Ocr`: `bitmap` names the `render` or `decode` request whose
bitmap is read, `w` 0 the whole of it; `flags` bit 1 asks for symbols and their alternatives, bit 2 for orientation and script
detection, and the flags above the low byte are the bitmap's resolution in dpi, a hint (`flags / 256`); each `lang` is an ISO
639-3 code of up to four ASCII chars (`chs`/`cht` for simplified and traditional Chinese): none means every model the engine has
(D038). The answer is `status nwords word...` and then, in the same record, the extension `emon.ocr` version 2 (`kin/ocr.kin`:
lines with baselines, a block table with each block's own orientation, symbols and alternatives, each word's dictionary and
numeric flags and point size, orientation, script, and whether the answer's `bitmap` is the engine's thresholded image). A host with
no `Ocr` port answers `unsupported`, which is an answer (a page then has no text); a transcript names the engine that answered in
`:producer`. A `decode` of a PDF's embedded JPEG has `filter` 1 (DCT): the span is a JPEG file.

Assets (D117) add two kinds, both transcribed like `render`: **`crop`** 5
(`input page dpi layer px py pw ph`, answered as a render) and
**`decode-img`** 6 (a PDF image codestream with the parameters a codec
needs — filter, size, components, bits, CCITT `K`/`Columns`/`Rows`/flags —
and where its bytes are: a span of an input, or a span of the unit's
**payload** `Buf` when kin produced them (decrypted, or behind a Flate
filter), named by its SHA-256 so the record, and so the transcript key,
never holds the bytes; a second such span for `JBIG2Globals`). A payload is
input-derived, like a `bytes` answer, so it is never written to a transcript.

**WASM.** The browser's host is the Rust host built for
`wasm32-unknown-unknown` (`host/wasm`, D356) and the JS SDK over it
(`host/js`): the module has no imports at all, and the JS side owns the loop
(§7.3, 2). `emon_step` runs kin until it needs something and says what: bytes
of the document its `Src` missed (the JS loop awaits `read(offset, length)` of
the byte source, hands the bytes in and steps again; the unit that missed is
re-run from scratch inside the step), or requests for the host. `decode` and
`decode-img` are the module's own (PNG, JPEG, TIFF, CCITT and DCT in Rust),
read from the bytes the host provided; `ocr` is the JS host's, fulfilled by
`ports.ocr` (tesseract.js: `host/js/src/tesseract.js`, D359), and `render` and
`crop` by `ports.render` (pdfium's WebAssembly build: `host/js/src/pdfium.js`,
D529-D538; the option `js-render=1`, which the SDK sets when it has the
port, makes them the host's, as `js-ocr=1` does `ocr`). A request no port is
configured for (`render` and `crop` without `ports.render`; `ocr` from a host
with no engine) is answered `unsupported`, which is an answer (D199, D358). A
transcript recorded natively replays in the browser and the reverse, to the
same Model (`bb identity`'s browser arm holds it).

**As built (P11.4c): the browser's pdfium.** pdfium has a WebAssembly build
(bblanchon/pdfium-binaries ships `pdfium-wasm.tgz`, pinned beside the native
ones in `tools/pdfium/pdfium.edn`; PDFium's own `FPDF_*` C API, exported
through Emscripten). `host/js/src/pdfium.js` is a port of the native
`host/rust/src/pdfium.rs` call for call, behind a `Renderer` interface
(`host/js/src/render.js`) that the embedder may implement otherwise: `open`
(called by the first `render` or `crop` request of a conversion, and never for
a document that asks for none) and a document that renders a page at a dpi in
a layer to RGB, or says `failed` or `unsupported`, which are answers. The
renderer is asked for WHOLE PAGES only: the SDK keeps the last one in the
module's memory and `emon_answer_render` cuts a crop out of it (`render::cut`,
§7.2: the same pixels, cut out, never a re-fit), so a page and its figures
are one render and the pixels are the same however a crop is asked. The size
(`floor(pt * dpi / 72 + 1/2)`), the bitmap (BGRX, white, annotations, three
bytes kept a pixel) and the layers (page objects removed, recursively through
forms; the watermark layers `unsupported`) are the native binding's, and the
document is reopened for each render. Three things are the browser's own:
(1) **the document is read whole**, once, on the first render
(`RenderDoc.read(0, size)`, an exception to §13.1's "no whole-file read": the
adapter loads it into pdfium's heap with `FPDF_LoadMemDocument64`; a source
that fails or changes under that read ends the conversion with `io` or
`source-changed`, not a failed page; FPDFAvail hint loading is a follow-up);
(2) **`maxPixels`** (default 64 Mpx) bounds a page tighter than the `pixels`
limit (kin's 268 Mpx, which `emon_limit` hands the adapter); the smaller
applies, and the divergence from the native default is accepted; (3) pdfium
draws a base-14 font that is not embedded from its own built-in set, where a
native build uses the system's, so the text of such a page moves (embedded
fonts render to the native pixels: measured 0 of 255 on 2.2 Mpx, `bb
pdfium-wasm`). A transcript the browser records has `:producer` `pdfium-wasm`
and replays on every host (`test/pdfium-wasm/`, which the gate replays on Rust
and Go; the live build agrees with the native pdfium on requests, answers and
sizes, and on pixels within D248's bound where fonts are embedded, D534).

Cheap, deterministic ports (`Blob`, `Cells`, `Text`, `Bitmap` pixel access,
`Crypto`, limits) are called synchronously as in nome.

**Pixels reach kin, and kin's pixels reach the engine (D196).** A page unit
is handed the answered bitmaps as a **`BitmapSet`** (`bms-get set id`, a
borrow of the answer's `Bitmap`, 0×0 when the answer carries none;
`bms-put set id w h channels`, a host-allocated `Bitmap` kin fills, at an id
of `KIN_VIEW_BASE` (2^24) or above). An `ocr` request may name such a
**kin-made view** (a body-ink layer, a quarter-turned page, a polar seal
strip) as its bitmap; its record then carries the view's FNV-1a 64-bit pixel
hash (two cells, by the `bitmap-hash` form), so the transcript is keyed by
what was asked and never stores kin's pixels (they are a function of the
transcribed answers). **As built (P7.4, D344):** the two cells follow `h`
(`bitmap psm flags x y w h hash-hi hash-lo lang...`, `rq-ocr-view`), so a
host reads the languages from argument 9 rather than 7 when `bitmap` is from
`KIN_VIEW_BASE` up; a replay of a request whose view came out with one other
pixel is `transcript-miss`. **A transcript carries every answered bitmap kin
reads**: a "slim" transcript (D214) replays only units that read no pixels,
which from P6.3 on is none that has a raster. A page unit whose answers carry
no bitmap (no `Renderer`, or a slim transcript) reads none: it skips the ink
layers and the targeted passes and is what it was before them, byte for byte.
`max-rounds` is 5 per page unit (D207): render or decode; the first OCR pass;
targeted passes (§8.3); their answers; one spare.

**Reading the input is a plan/fulfil request when the source is async**
(D058, §13.1): a `Src` read that misses the host's chunk cache poisons the
current unit; the unit stops at its next checkpoint (or runs out under its
limits) and its result is discarded; the missed ranges go out as `bytes
(input, offset, length)` requests; the host awaits them and **re-runs the
unit from scratch**. `bytes` rounds are separate from, and do not count
against, `max-rounds`. `bytes` answers are never written to a transcript:
they are the input, not a host's opinion.

### 7.4 Limits

A PDF is hostile input. Every bound is configurable, named and reported as a
named error rather than a hang or an allocation failure (the pattern of nome
§5.5): input
bytes (checked against a byte source's declared length *before* anything is
read, and never above 2^31 − 1, since `Src` offsets are `I32` — D058);
fetch rounds per unit (D058);
decompressed bytes per stream and in total (the zip-bomb bound);
object count; indirect-reference depth; content-stream operators per page;
XObject/Form nesting depth; pattern/Type3 recursion; pages; pixels per render
and in total; OCR requests and rounds; wall-clock per stage (`stage-ms`, D501: the
host owns the clock and supplies the milliseconds a unit has run, rounded up, before
every step of the plan loop; kin owns the limit, its name and the decision, and a
stage that has used its bound is the named error `stage-ms`; 0, the default, is off;
a call into a unit is not interrupted, so a hang inside one attempt is the
embedder's own timer's to cut); fonts per document (`fonts-per-doc`, D498: a
budget, not an error: the font past it has no evidence id and its page is flagged);
workers (`workers`, D505, a budget on how the work is spread, not on the document: `parallel=N` is served with at most this many, §12.1);
and, when assets are on, pixels and bytes per asset and bytes per document
(§10.11) — which, unlike the others, degrade to a `noasset` reason and never
fail a page.

**Failure is per page** (D030): a page whose content stream is corrupt
yields a `pages` entry with an `error` and an empty contribution; the rest of
the document proceeds. A crawler converting ten million PDFs wants 199 pages
of a 200-page report, not an exception.

### 7.5 emon and nome are independent (D039, supersedes D024)

**emon shares no code with nome** and has no build, test or runtime
dependency on it: no vendored modules, no provenance tracking, no shared
library, no path into `../nome` in any script. emon owns its own
implementations of everything it needs — `inflate`, font program reading
(CFF, Type 1, TrueType `cmap`/`post`), Unicode normalization, bidi, script
and segmentation, hyphenation, integer trigonometry, and the rest.

What emon *may* take from nome is **knowledge**, not code: conventions
(repository layout, `bb` gate shapes, the port-annotation pattern, the
decisions-log habit), and nome's modules as **reference or inspiration**
when writing emon's own — the way one reads any other implementation of a
specification. An emon module written with a nome module open beside it is
still emon's module: its own namespace, its own tests against the
specification, its own history.

The cost is honest duplication of substrate both projects need (DEFLATE,
Unicode tables). It is accepted: two independent projects can change
their substrates on their own clocks, and neither's refactor can break the
other's build.

---

## 8. The three sources

### 8.1 The structured parser (text layer) (D004, D034)

A full content-stream interpreter, because text position depends on the
whole graphics state: `cm`, `q`/`Q`, `Tm`, `Td`, `TJ` adjustments, `Tz`,
`Tc`, `Tw`, `Ts`, `TL`, render modes, clipping paths, Form XObjects (recursed,
bounded), Type 3 glyph procedures (interpreted for *their* graphics only when
the font has no usable encoding), inline images, shading and pattern fills
(recorded as filled regions).

Per glyph it records: code point(s) (and *how* they were obtained —
`ToUnicode`, encoding, glyph name, CID ordering, or unknown), font id, size in
page space, bounding box (from font widths and ascent/descent — **not**
guessed from the font size), baseline, rotation, fill colour, alpha, render
mode, whether it is clipped out, and the marked-content context (`/Artifact`
subtype, MCID).

Visibility is decided here, because the text layer lies in characteristic ways:

* **Render mode 3** (invisible) → `hidden-text`: a prior OCR layer. Kept as a
  weak witness, never trusted over this run's OCR.
* **Clipped out, off-page, white-on-white, zero-size, or painted over by a
  later opaque image or fill** → `invisible`; to the ledger.
* **`/Artifact` marked content** with `/Pagination` or `/Watermark`
  subtypes, and **Watermark annotations** → pre-classified artifacts (§9.5).

Paths are judged by the same checks (P3.6a, D219): a rule that is clipped
away, hidden in an optional-content group, the colour of what is behind it,
painted over by a later opaque fill or image, or transparent is no rule, and a
table built on it is wrong. A path's geometry is kept segment by segment (a grid
drawn as one path of many `m l` pairs is many rules, not one box).

Words are formed from glyphs by gap analysis against the font's space width
and the `TJ` adjustments; lines by baseline and direction; the content-stream
order is kept as one weak reading-order signal. A producer may show the runs of one visual line out of order (a bold run after the regular
runs either side of it): consecutive runs of the stream on one baseline whose words, taken in order along it, each continue the one before by
the line rule (no wider a gap than two sizes, none overlapping by more than 6/10 of a size) are one line, their words in that order (D557);
runs that are in order already, that do not close up, that overprint one another or that read right to left are lines as before.

**Tagged PDF.** When the structure tree is present and its roles are
meaningful (`H1`–`H6`, `P`, `L`/`LI`, `Table`/`TR`/`TH`/`TD`, `Figure`,
`Caption`, `Note`, `Artifact`), it is the strongest single structural prior
emon has. It is **still verified**, not obeyed: many producers tag
everything `P`, and some tag wrongly. A tree whose roles are uniform is
scored as uninformative (§9.3).

**Also read**: link annotations (URI and internal destinations, mapped onto the
words they cover), outlines and page labels, AcroForm field values (rendered
appearance *and* `/V` — they disagree, and `/V` usually wins), signature
dictionaries (signer `/Name`, `/M` date, `/Reason`, `/Location` — without
cryptographic validation, which is out of scope).

### 8.2 The raster path and the tracer

The host renders (PDF) or decodes (image) at a planned resolution — 300 dpi
by default for OCR, a lower one for page-level layout classification. kin
then:

1. **Normalizes**: grayscale; ink-layer separation by colour clustering in an
   integer colour space (black text, blue/red/purple seal inks, light-grey
   watermarks and backgrounds separate cleanly more often than not); adaptive
   binarisation (Sauvola in integer arithmetic over an integral image);
   skew estimation (projection profiles at candidate angles) and correction;
   orientation (0/90/180/270) from OCR's orientation detection or from text-line
   geometry.
2. **Traces**: connected components with statistics (size, fill ratio, stroke
   width via distance transform, colour); contour tracing to polygons with
   integer Douglas–Peucker simplification; line and rule detection
   (morphological runs + Hough-like integer voting over components);
   primitive fitting — rectangles, ellipses/circles, arrows.
3. **Produces** evidence: rules (for table grids), boxes, curves, component
   clusters (for charts, diagrams, logos, signatures), and text-like component
   regions (which cross-check OCR — a word OCR reports where no ink exists is
   a ghost).

For a **born-digital** PDF the tracer's job shrinks: rules and fills are
already present as vector paths (source `vector`), which are exact. The tracer
still runs on the rendered page to see what the vector list cannot express
cheaply (text rendered as outlines, images of tables, scanned inserts), and
its rules are the render's witness of the vector rules (§9.9: a vector rule the
render does not show, when the vector paint is not exactly known, is not seen).

**Graphics the tracer must find for §9.9 (D226).** Rules come from long
horizontal row runs AND from stacks of short row runs with the same x-range
(±1 px) over at least `rule-min` (a vertical rule is a column of 1-4 px runs,
not a long run): a table grid is ONE component, so its edges are found from
runs, not from the component's shape. **Fills** (shading bands, header fills)
are not ink to Sauvola, which removes a uniform background: they come from the
`light` and `background` ink-mask views (§8.4.4) as filled `rect` primitives,
the row runs of a mask merged into rectangles whose rows share their x-extent
within `fill-tol` (2 px), at least `fill-min` (6 pt) each way.

**As designed for P6.3/P6.4 (D197, D198), on the P2.7 measurements (D092):**

* **Views.** A born-digital page has two renders, `with-text` (layer 0) and
  `no-text` (layer 1), both RGB at the OCR resolution (300 dpi); a raster
  page has its decode. Every derived raster is a `views` row whose parent
  chain ends at a render or decode (§8.4.2).
* **Grayscale** is `(77 r + 150 g + 29 b + 128) >> 8` by the bulk form
  `bitmap-gray-into`; a 1-channel source is its own gray.
* **Ink layers** are found on a **4096-bin colour histogram** (`r>>4, g>>4,
  b>>4`, the bulk form `bitmap-hist-rgb`), never by iterating pixels in kin:
  the paper is the bin cluster holding the most pixels among the lightest;
  every other bin is assigned to a cluster by integer distance in an
  opponent space (`L = gray`, `a = r - g`, `b = (r + g)/2 - b`), seeded
  deterministically (heaviest bin first, ties by bin index) and merged while
  two centroids are within `ink-merge` (a named setting); at most 8 layers.
  An anti-aliased edge is not a layer: a bin lying on the segment between an
  existing layer's colour and the paper joins that layer unless it holds
  `ink-spike` of the page (a flat watermark does, an edge does not), and a
  bin outside every layer's core (half of `ink-merge`) goes to the layer
  whose ink-to-paper segment it lies nearest (D237).
  Each layer's role (`ir`) is guessed from lightness, chroma and coverage:
  `body` (every low-chroma layer darker than `ink-light`: black headings and
  dark-grey text are two clusters of one kind of ink), `light`
  (low chroma, lightness over `ink-light`), `colour` (chroma over
  `ink-chroma`: blue, red, purple seal inks), `background` (covers
  `ink-bg` of the page, and is light or coloured), `unknown` (a dark neutral
  mass of that coverage: not text). A layer's mask is one `bitmap-lut-into` pass (a
  4096-entry table from bin to mask value). On a **gray** source (a 1-channel
  scan) only lightness separates layers: `colour` cannot occur, and
  watermark separation rests on lightness, geometry and recurrence.
* **Binarisation** is Sauvola (`r` 15 px at 300 dpi, scaled with the dpi;
  `k` 87/256) computed over a **band of rows** (`2r + 2` rows of the two
  integral images at a time), equal by law to the whole-page `ras-sauvola`
  of P2.7 and costing `O(w r)` memory instead of 70 MB. Bilevel decodes
  skip it.
* **Deskew** runs only on decode views (a render is unskewed by
  construction): projection profiles of the ink runs of the body layers' mask
  (each run by its midpoint, weighted by its length, on every few rows) at
  candidate angles (±5°, 0.1° then 0.01°), with `ev-sin`/`ev-cos`; the angle
  goes to the decode view and every view derived from it (D238), **and as
  wired into the page unit (P7.4, D343) it is measured and then NOT applied**:
  the OCR evidence reads the engine's pixel angles as the page's (a line's
  `rot` is its baseline's angle less the page's median, D213), and a view that
  rotated the boxes by the measured skew put every word twice as far off. Kin
  never resamples for its own analysis; it maps coordinates through the
  view. **Orientation** (0/90/180/270) is OSD's when its confidence passes
  `osd-conf`, else the `tlines`' dominant direction; a page not upright is
  re-read by a quarter-turned kin-made view (§7.3, P7.4).
* **Components** are labelled from **row runs** (`bitmap-next-nonzero`
  walks), never a per-pixel label image: memory is proportional to ink
  (1-3 % of a page), and the statistics of §8.4.4 are computed per run set.
  Contours are traced on the component's runs; Douglas-Peucker in integers.
* On a born-digital page the tracer reads the **no-text** render: the text
  layer already gives exact glyph geometry, and what the tracer must find is
  what the text layer lacks (outlined text, text in images, scanned inserts,
  rules of an image table, seals, signatures, image watermarks).

### 8.3 OCR (D033)

* **Default engine**: Tesseract (LSTM), bound natively in each host (Rust:
  the C API; Go: cgo; Java: the C API via the FFM API (Panama, JDK 22+; D449); C#: the
  C API via P/Invoke; WASM: tesseract.js through plan/fulfil).
* **First pass**: full page, automatic segmentation, languages from the
  locale hint or from script detection (below), with orientation and script
  detection enabled.
* **Targeted passes** (later rounds): regions where fusion disagrees,
  regions whose text layer is unhealthy, text inside images, seal strips,
  rotated text blocks, table cells OCR'd as single lines (`psm 7`) when a
  whole-page pass merged columns.
* **No locale** → run script detection (Tesseract OSD, or the text layer's
  own scripts via emon's `script`) and choose the installed models for those
  scripts. With nothing detectable, the engine's default is used and the
  Model records which languages were actually applied.

**The first pass, page by kind, once fusion exists (D199, D200).** A pass is
planned from the Source and the text layer only, never from pixels, so the
raster stages run once per round that has OCR answers (§7.3):

| Page | First-pass requests (round 2) |
| --- | --- |
| Image, scanned PDF page | the whole decode or render, `psm` 3, OSD (D211) |
| Born-digital, `ocr-scope: full` (default) | the whole `with-text` render, `psm` 3; languages from the locale, else from the text layer's scripts (`emon.mscript`) mapped to installed models, else every model (D215) |
| Born-digital, `ocr-scope: witness` | the whole `no-text` render, `psm` 3 (text the text layer lacks); plus **witness lines**: for every (font, page) pair, `witness-lines` (3) of its lines chosen by a fixed rule (the first, the median and the last by `order`), each an `ocr` request of the `with-text` render's region around the line, `psm` 7 |
| Born-digital, `ocr: auto` | nothing, unless the page's text-layer health (§9.3, without OCR) is under `auto-health`, or images cover over `auto-image` of it, or it has `hidden-text`: then as `full` |

Targeted passes (round 3, §9.4 / P7.4) follow fusion's flags.

**As built (P7.S, D363-D369).** `emon.rasterplan`'s `rp-plan` is called by the document pass (`pu-plan`) and writes each born-digital page's `ask` (none, `full`,
`witness`), its witness rows and its scripts into the page plan; the page unit reads nothing else to decide. The table above is exactly what it asks, with these
choices: `auto-health` is `emon.tlhealth`'s (500) and a page's text-layer health is the lowest score of its fonts; `auto-image` is 300 per mille of the page,
image placements clipped to the page and added; a witness line is an upright line of visible text-layer words of one font holding four letters or digits at least,
at most 12 witness lines a page, the fonts with the most lines first; the `psm` 7 box is the line's box padded 3 px. **Escalation** (`rp-weak`): once the witness
lines are in, a font with at least two compared lines of which fewer than `agree-min` (600 per mille) read as its text says (case-folded letters and digits, edit
similarity 900 or more, Latin text only) escalates the page: the whole `with-text` render is read in round 3 (`ri-escalate`) and then the page goes on as `full`
(targeted passes included). A page of the `witness` scope that does not escalate asks no targeted pass of P7.4: its first-pass lines are the `no-text` render's and
there is no foreign ink under them to look for. **Languages**: the locale's, else the page's scripts, as ISO 15924 codes in the request's language cells
(`Latn`, `Cyrl`, `Grek`, `Arab`, `Hebr`, `Deva`, `Hani`, `Hang`, `Jpan`, `Thai`: a cell that starts with an uppercase letter names a script), which the host
adapter maps to the installed models that read it; a page with no letters, or none the adapter has a model for, asks with every model. `Options::ocr_scope` /
`Options.OcrScope` and `--ocr-scope=` carry the scope; the Model records `settings.ocr-scope` when it is not `full`.

**As built (P7.4, D343-D349).** `kin/pageink.kin` runs P6.3 and P6.4 in the page unit, on every raster whose pixels the unit was given (a scan's decode; a
born-digital page's `with-text` render, and its `no-text` render for the tracer): the ink layers, the OCR words' ink columns (`xo` 10-14, 16), the rules and
text lines. `kin/retarget.kin` then asks, in round 3 (round 4 for the text-only pass, which needs its render first), within `rt-max` (24, less the four kept for
the page-level passes) requests a page: **op-cell** (a table cell at `psm` 7, when the page has a lattice of long rules and a first-pass line crosses a column
border; the cell's box is cut inside the rules), **op-line** (a line the engine was unsure of, `psm` 7), **op-body-ink** (the view in which every `light`,
`colour` and `background` layer is painted the paper's colour, except what lies against body ink, which on a scan is the glyphs' own edge; asked when foreign ink
lies under a first-pass line or covers 1.5 per cent of the page), **op-re-rotated** (the page turned by the quarter turns OSD is sure of, or names when the tracer's
text lines run up and down; the page is then read in the upright frame) and **op-text-only** (built, asked by nothing yet). **Which reading the pipeline gets** is
decided line by line for a scanned page, never by a page's score: a first-pass line is kept unless a better reading of it exists that loses no text (same words,
surer; the body-ink run's line that is its partner both ways), or its ink is mostly foreign and the body-ink run read nothing over it (a ghost); lines the body-ink
run read where the first pass read none are taken, in reading order, on a page that runs down in one column. On a born-digital page the runs are written after the
page is aligned and scored: they are evidence, and the body-ink run is a witness (`plg-bink`, `ag-bink`, row 222), never a candidate.

**As built (P12.6, D610-D620).** What a scanned page's lines are made of, once `rt-adopt` has chosen them (`emon.scan`'s `sc-emit-vetted`): (1) a first-pass line that stays
reads with it the words of its partner body-ink line that lie over none of its own and no word on another line of the first-pass run (a right-aligned column the first pass stopped before; D621 prevents reading the same ink twice); additions must extend beyond the partner's last word; alphabetic additions need three letters and confidence 0.6, while numeric additions retain their 0.4 bound (D626); a proposed addition under half the height of every word in its partner needs confidence 0.7 (D623); (2) a word of confidence 0.3 or
less with implausible characters, alone or among such words, a dot leader and a table rule read as a character are kept out of the page's `words`, flagged in the evidence
(`oe`) and written to the dropped ledger (`ocr-ghost`, `rule-ghost`); (3) the corrections a page shows for itself, never a digit: a decimal point the pixels have and the
engine's box swallowed, thousands read with periods, a currency sign read as `S` or `§`. The engine's text stays in the evidence. A first-pass line with no word that has the size
of a line of text is read alone (`psm 7`) and its words are the page's when sure (text that meets a rule); the body-ink view shows the light text of a band of colour as ink.
The ledger names a page that came out empty and held an image (`background`).

**As built (P12.8, D642-D643).** An unread first pass on a scan or full-scope born-digital page is retried once with OSD's script plus English; kin selects the installed models, and a reading still unsupported by a matching model retains `script-unread`. Chinese word-language aliases `chs` and `cht` count as Han models. An isolated punctuation speck at most one point each way is also ledgered as `ocr-ghost` at high engine confidence. Paragraph grouping tolerates twenty-percent inferred size variation between two OCR prose lines of at least four words each; shorter labels keep the five-percent boundary.

**As built (P12.3, P12.6; D564, D616, D625).** (1) *A page whose content is one big image is a scan whatever the codec*: a PDF page with no text layer and an upright image over at least
a quarter of it is read from the host's DECODE of the image's DCT stream when that is one plain stage of 8-bit SOF0/1/2 JPEG with one or three components, and from a RENDER of the whole page (300 dpi) for other JPEG frames
or codecs (arithmetic or CMYK JPEG, Flate, RunLength, LZW, CCITT, JBIG2, JPX, an encrypted DCT). (2) *An image input is a scan too*: each frame runs the same page unit as a scanned PDF page
(`pu-scan-core`): the ink layers, and the targeted passes the first pass calls for, among them the quarter-turned view when OSD is sure the page is turned (a PNG fed
sideways or upside down is read upright; its Model page is then as wide as the text it holds). P12.8 (D640, D644) also runs graphics fusion in the image document unit and the scan seal/signature stages on its frames; their candidates become Model nodes. (3) *A raster too small for the
engine is read again enlarged*: the median x-height of the first pass's text lines (width-weighted) says what resolution the raster has (`emon.upscale`: ten point type is 5 pt
of x-height); under `up-xh-trigger` (9 px, about 130 dpi) the raster is made `ceil(20 / x-height)` times as large (at most 4, at most 25 million pixels) by a separable
Catmull-Rom cubic of kin's own, as a kin-made view, and the engine reads THAT view (pass `upscaled`, told the resolution the text says, not the one the file stated or
300 when it stated none); a first pass that read fewer than four words of a raster whose longer side is at most 1400 px is read again 3 times as large; the enlarged
reading replaces the first when it reads at least as much; a page OSD says is turned is first read upright. (4) *A page in a script the engine has no model for*: OSD sure
(script confidence 15.0) of a script that no model the engine read with (the language of its words) reads, with at least three words: the blocks of the page whose text is OCR's are
flagged `script-unread` and `low-conf` and their confidence is held at 100, written uncalibrated (`cal-node-conf`).

### 8.4 The evidence schema, v2 (D056, revised by D096)

Evidence is what fusion (§9) reads, so its shape is set by what fusion must
decide: which source is right about a word, and whether a word is body text,
furniture, watermark, seal or ghost. v1 (D056, dump version "0") was designed
before OCR existed; the OCR spike (D095) showed it could not hold symbol boxes,
baseline slope, the paragraph and block hierarchy, orientation, script,
multi-character alternatives or the engine's binarised image, and that raw
engine confidence cannot carry the §9.4 rules. v2 is the revision. It is
integer arenas in `Cells`, as the Model is (§3.8, D011, D050): kin cannot
allocate, the host allocates and kin fills, a row is its index, absent is
-1, a count is 0, codes are `defenum` rows assigned once and never reused.

#### 8.4.1 Conventions every source follows

* **Space.** Every row that has a place on the page carries `page` and an
  axis-aligned **box** `x0 y0 x1 y1` in page space (§3.4: centipoints,
  top-left, y down), `x0 <= x1`, `y0 <= y1`, the smallest box containing the
  thing's quad. A box is half-open for area arithmetic (`x1 - x0` is the
  width). Rotated things are not described by their box alone; see baselines.
* **Baselines are two points.** Every glyph, word and line of every source
  (an OCR symbol uses its word's) carries a baseline **segment** `bx0 by0
  bx1 by1` in page space, running the way its glyphs advance (text space +x for the text
  layer; first symbol to last for OCR; first component to last for the
  tracer). Slope is the segment, never a single y. `rot` is the segment's
  angle in centi-degrees, counter-clockwise **as displayed**, `0 <= rot <
  36000`, computed by `ev-angle dx dy` (below) so every source spells it the
  same way; `dir` (`ld`) says how characters progress along it.
* **Raster geometry is kept exact.** A row derived from pixels (OCR, the
  tracer) also names its **view** (§8.4.2) — through its run for OCR — and,
  where its table lists a pixel box, keeps its box in that view's pixels;
  its page box is *derived* by `vw-box-to-cpt`: the four
  corners mapped, the smallest box around them. Pixels are the observation;
  centipoints are the common space.
* **One way to refer to anything.** A reference to a row of any arena is the
  pair `(ea, index)`, `ea` being the arena's code (§8.4.6). Many-to-many
  lists (a primitive's member components, a tracer line's components) are
  runs of the `refs` arena, two cells a reference. Every run is `first,
  count` over a contiguous stretch of one arena (D056's rule, kept), and
  `ev-check` verifies every run and every reference.
* **Pages and regions.** Arenas may hold several pages and later OCR rounds
  append, so rows of one page need not be contiguous; fusion works a page at
  a time and a region at a time by `page` and box. The per-page spatial index
  over the heads is fusion's (P7.1), not evidence.
* **Paint order.** Every painted object of the text layer (glyph, path,
  image, shading) carries `z`, its 0-based paint sequence number on its page.
  `z` identifies a painted object uniquely within a page and is what P3.6's
  occlusion test and a marked-content span's extent are written in.
* **Shared heads.** Word-like and line-like rows of every source begin with
  the same fields, so alignment (§9.2) is written once over `(Cells,
  stride)`:
  * **word head** (16 cells): `page x0 y0 x1 y1 bx0 by0 bx1 by1 rot dir
    tstart tlen line conf feat` — text as an `etext` run; `line` the row of
    its line in the same source; `conf` the source's **raw** confidence
    (per-mille, -1 when the source has none: the text layer); `feat` its
    `feats` row (§8.4.5), -1 until computed.
  * **line head** (13 cells): `page x0 y0 x1 y1 bx0 by0 bx1 by1 rot dir first
    count` — `first, count` the run of its words (or, for the tracer, of
    `refs`).
  * **char head** (9 cells): `page x0 y0 x1 y1 cp tstart tlen word` — `cp`
    the first code point (-1 unknown), `tstart tlen` an `etext` run when it
    stands for more than one (a ligature; a multi-character OCR symbol).
* **Normative helpers** live in `emon.evidence` and are the only way these
  numbers are computed: `ev-angle dx dy` (integer atan2 to centi-degrees:
  octant reduction and a generated 1025-row table of `atan(i/1024)`, error
  under 0.03 degree), `ev-sin`/`ev-cos` (16.16, a generated table per
  centi-degree over 0..9000 and symmetry), `vw-px-to-cpt`, `vw-box-to-cpt`,
  `vw-cpt-to-px` (I64 arithmetic, rounding `floor((v + 2^15) / 2^16)` with
  floor division), `ev-overlap-small` (intersection over the smaller box,
  per-mille), `ev-baseline-at` (a segment's y at x, integer interpolation).

#### 8.4.2 Shared arenas

| arena | stride | fields |
| --- | --- | --- |
| `epages` | 12 | page, width, height (cpt, as displayed), quarter turns of `/Rotate` applied, flags (`has-text-layer`, `raster`, `dpi-unknown`), raster dpi x100 (0 unknown; the dump prints it in dpi, 300 and not 30000, P12.6), spare |
| `views` | 24 | page, kind (`vk`), request id whose bitmap it is (-1 when kin made it), parent view, pixel width, height, dpi x100, render layer (`lay-*`, D089), ink row (an ink-mask view's `inks` row, -1), quarter turns applied, deskew applied (centi-degrees), crop origin x y (cpt), px→cpt linear part a b c d (16.16) and translation e f (cpt), cpt→px inverse a' b' c' d' (16.16), flags (`bilevel`, `nonaffine`, `engine-binary`) |
| `pts` | 3 | x, y (cpt), op (`po`: move, line, curve-control, close) — path and contour geometry |
| `refs` | 2 | `ea`, index |
| `etext` | 1 | one code point per cell (D056, unchanged) |
| `feats` | 32 | subject `ea`, subject index, 30 feature columns (§8.4.5) |

A **view** is any raster kin reasoned about: a render (with its layer mask),
a decoded image, the OCR engine's own binarised image (answered as a bitmap
next to its words, D095's open item), and every raster kin derives
(grayscale, Sauvola binary, an ink-layer mask, a deskewed or cropped copy, a
polar-unwarped seal strip). `views` is the binarised-image provenance: an OCR
run names the view it read and the view the engine thresholded it into, and
that view's `parent` chain ends at a render or decode request. `vw-new`
computes the 16.16 parts from the integer parameters once, so every reader
uses the same numbers. A `nonaffine` view (a polar strip, P8.2) maps back
through a kind-specific function named with its kind; its parameters use the
linear-part cells.

#### 8.4.3 The text layer (§8.1)

| arena | stride | fields |
| --- | --- | --- |
| `glyphs` | 32 | char head (9); baseline segment `bx0 by0 bx1 by1` = origin to origin + advance (without `Tc`/`Tw`, so spacing is the gap to the next origin); `asc`, `desc` (cpt, SIGNED distances from the baseline along the up vector, `desc` negative for a descender: with them the segment gives the glyph's exact quad); `rot`; `how` (`gh`); `font` (`fonts` row); `size` (cpt); render `mode` 0..7; fill colour, stroke colour (0xRRGGBB, -1 none); fill alpha, stroke alpha (per-mille); colour-space class (`cs`: gray, rgb, cmyk, spot, pattern, other); `z`; `clip` (the `paths` row of the clip in force, -1 the page); `mark` (innermost `marks` row, -1); `ocg` (innermost optional-content span, a `marks` row, -1); `vis` (`vz`); `vby` (the `z` of the occluder, -1); `vchk` (bits of the P3.6 checks that have run) |
| `words` | 24 | word head (16); style (`sy`, from the font); `gfirst gcount` (its glyphs); `font`, `size` (dominant); `gap` (cpt to the next word, -1); `wbreak` (`wb`: why it ended: space glyph, gap, `TJ` offset, line end, font change, mark boundary); `src` (`sr`: `text` or `hidden-text`) |
| `lines` | 16 | line head (13); `src`; `order` (content-stream index of its first glyph, the weak reading-order signal); spare |
| `marks` | 24 | page, kind (`mk`: artifact, optional-content, tag), tag `tstart tlen`, parent span, `mcid`, artifact `/Type` (`ar`), artifact `/Subtype` (`as`), `/ActualText`, `/Alt`, `/E`, `/Lang` (each an `etext` run: 8 cells), OCG or OCMD object number, its default state (1 on, 0 off, -1 unknown), `gfirst gcount` (glyphs inside), `zfirst zlast` (painted objects inside), flags (`/Properties` reference, inline dictionary, name not found, closed by the page end), `stm` (the object number of the Form XObject whose stream the span is in, 0 the page's: P3.7 joins a structure element's MCID on `(stm, mcid)`, D101) |
| `fonts` | 16 | object number, subtype (`ft`), descriptor `/Flags` verbatim, embedded program kind (0 none), has `ToUnicode`, base encoding, `/BaseFont` `tstart tlen`, ascent, descent (1/1000 em), space width (D083 units), italic angle (centi-degrees), `/StemV`, style (`sy` inferred from flags and name), `/CapHeight`, `/XHeight` (1/1000 em, -1 absent: the initials rule of §10.9 needs them, P4.10 reads them into the two cells that were spare) |
| `paths` | 24 | page, kind (`pa`), box, fill, stroke, width (cpt), fill alpha, stroke alpha, flags (`pg`: closed, even-odd, **clip**: a clip is a `paths` row, filled, stroked, shading, pattern fill, non-Normal blend, soft mask in force, **truncated**: its geometry in `pts` is cut, D219), `z` (-1 for a clip), `clip`, `mark`, `ocg`, `pfirst pcount` (its geometry in `pts`: a vector rule's exact endpoints, a table grid's segments), dash (0 solid, else the first dash length, cpt), `vis`, `vby`, `vchk` (P3.6a, D219: as a glyph's), backdrop (the colour behind it that the white check found, 0xRRGGBB, -1 unknown), spare |
| `images` | 20 | page, box, object number, pixel width, height, alpha, flags (`mg`: stencil mask, /SMask, inline, /Mask, blend, soft mask in force), `rot` (the image matrix's x axis, as for a baseline), skewed (0/1), `z`, `clip`, `mark`, `ocg`, `vis`, `vby`, `vchk`, `placement` (P3.13, D296: the orientation of the matrix on the page AS DISPLAYED, `pi-orient`'s code 0..7, 8 for one that is not rectilinear; written to the dump when not 0) |

**Visibility hooks (P3.6, D155).** `vis` is the `vz` code of the FIRST check that failed (0 none): `clipped` 1,
`offpage` 2, `white-on-white` 3, `zero-size` 4, `occluded` 5, `ocg-hidden` 6, `transparent` 7. `vchk` has one bit for each
check that ran: clip 1, offpage 2, white 4, zero-size 8, occlusion 16, ocg 32, transparent 64; the interpreter also sets
128 (a non-Normal blend mode is in force) and 256 (a soft mask is). A check after the failing one has not run. `vby` is
the `z` of the paint that covers an `occluded` glyph or image. So `vis` 0 means "passed the checks in `vchk`", never
"visible" outright, and fusion can tell an unchecked glyph from a checked one. Every check is conservative: invisible is
said only when it is certain from what was recorded. Render modes 3 and 7 are never `vis` (they are the `hidden-text`
source, and paint nothing, so the paint checks white, occlusion and transparent do not apply to them; clip, offpage,
zero-size and ocg still do). A word never mixes visibilities or hidden-ness: the grouping breaks where either changes.
**Paths (P3.6a, D219)** get the same three cells from the same checks on their painted region (the box, intersected with
the clip's, a stroked rectangle's shrunk by half its width): clip, offpage, white (the stroke colour of a stroked path, the
fill of a filled one, both for both; the backdrop it found is kept in `backdrop`, so fusion can ask for contrast rather than
for "not white"), zero-size (a path whose box is empty both ways; a zero-width stroke is the thinnest line a device can
draw and is NOT zero-size), occlusion (as for a glyph; `vby` the occluder's `z`), ocg, transparent. A clip row and a
shading's own region are not checked (`vchk` 0). A path whose `vis` is 0 and whose `vchk` lacks 128 and 256 (no blend
mode, no soft mask) is **exactly known**; §9.9 treats the others as needing the render's witness.
A `paths` row that is a clip has `z` -1 and a box that is the intersection of every clip on its chain (D152); a clip
that does not shrink the region has no row. `marks.ocgdef` is 1 on, 0 off, 2 on in the configuration but off by the
group's `/Usage /View /ViewState` (an `/AS` auto state: Acrobat hides it, poppler, Ghostscript and pdf.js draw it, so it is
not `off`), -1 unknown; an `/OC` on a Form XObject or an image is a `marks` row of its own (flag `xobject`).

**Colour and layer.** A glyph's ink is its fill colour for modes 0, 2, 4, 6
and its stroke colour for 1, 5 (both for 2, 6); the colour-space class says
whether it came from a pattern or a spot colour, which a watermark often
does. "Layer" in the PDF sense is optional content: the innermost `/OC` span
is on every glyph, path and image as `ocg`, and its `marks` row carries the
OCG and whether it is on by default (a watermark OCG is often off in print).
The raster sense of layer (an ink cluster) is `inks` (§8.4.4).

**Marked content (D076).** Every `BDC`/`BMC` becomes a `marks` row when it
opens, `EMC` closes it; spans nest by `parent`. Glyphs inside a span are a
contiguous run because glyphs are appended in content-stream order, and the
span's `zfirst zlast` covers its paths and images. P3.7 joins `mcid` to the
structure tree; §9.5 defence 1 walks a glyph's `mark` chain for an
`/Artifact` of type `Pagination` or subtype `Watermark`, and its `ocg`.

#### 8.4.4 OCR (§8.3) and the tracer (§8.2)

| arena | stride | fields |
| --- | --- | --- |
| `oruns` | 28 | page, view read, binarised view (the engine's thresholded image, -1 not asked), request id, round, pass kind (`op`: first, region, line, cell, seal-strip, body-ink, text-only, re-rotated, upscaled), `psm`, region x y w h (view px), languages `tstart tlen` ("eng+deu"), orientation (quarter turns clockwise to upright), writing direction, line order, deskew (centi-degrees), OSD degrees (-1 none), script (ISO 15924 packed), script confidence, orientation confidence (x10, -1), request flags, `bfirst bcount`, `lfirst lcount`, `wfirst wcount` |
| `oblocks` | 16 | page, box, kind (`ob`: block, paragraph), parent block (-1), run, block type (`bt`, D095), orientation, writing direction (per block: Tesseract reports them per block), child run `first count` (a block's paragraphs, a paragraph's lines), raw confidence, spare |
| `olines` | 24 | line head (13); paragraph (`oblocks` row); run; raw confidence; x-height, ascender, descender (cpt, -1 unknown); pixel box x y w h; spare |
| `owords` | 28 | word head (16); style (`sy`); run; `sfirst scount` (its symbols); language (packed); engine flags (`oe`: from-dictionary, numeric, from the engine's fallback model; and what emon did with the word, P12.6: kept out as garbage, kept out as a rule's ghost, text corrected in the page's words); engine point size (cpt, -1); pixel box x y w h; spare |
| `osyms` | 20 | char head (9); raw confidence; `afirst acount` (its alternatives); flags (`os`: superscript, subscript, drop-cap); pixel box x y w h; spare |
| `oalts` | 4 | symbol, `tstart tlen` (the candidate's text: any number of code points), confidence (x10) — a contiguous run per symbol, in engine order, verbatim |
| `inks` | 12 | page, view clustered, centroid colour, lightness (0..1000), chroma, pixel count, role guess (`ir`: body, light, colour, background, unknown), mask view (-1), spread, spare |
| `components` | 24 | page, box, view, pixel box x y w h, ink pixels, fill (per-mille of the box), stroke width (cpt, distance transform), colour, ink row, kind (`ck`), confidence, flags, contour `pfirst pcount`, parent (the component it lies in, -1), holes, mean gray of its ink (0..255), spare |
| `rules` | 20 | page, box, view, centre-line endpoints x0 y0 x1 y1 (cpt), thickness (cpt), colour, ink row, style (`rs`: solid, dashed, dotted, double), confidence, member components `rfirst rcount`, flags, spare |
| `prims` | 24 | page, box, view, kind (`pm`: rect, ellipse, circle, arrow, curve, polygon, ring), centre x y, radii rx ry, `rot`, stroke width, colour, ink row, fill (per-mille), confidence, fitted outline `pfirst pcount`, members `rfirst rcount`, flags (closed, filled, double ring, **tint**: a filled `rect` found on a `light` or `background` mask, a shading band, D226), spare |
| `tlines` | 20 | line head (13; `first count` is a `refs` run of its text-like components); ink row; mean component height (cpt); mean gap (cpt); confidence; spare |

**Hierarchy.** An OCR run's blocks, paragraphs, lines, words, symbols and
alternatives are each a contiguous run, nested in that order: a run is built
from one answer in the engine's iteration order, so every level is a range
of the level below and `ev-check` verifies the nesting. Block and paragraph
share `oblocks` (a `kind` column) because both are few and have the same
fields.

**Alternatives.** An alternative is text, not a code point, and a symbol's
alternatives are a run, not a chain; the engine's own list is kept verbatim
(including a candidate equal to the chosen text). What fusion derives from
them (margins, ambiguity) is a feature.

**Tracer text lines.** `tlines` are text-like components grouped into lines
with a fitted baseline, by the tracer alone. They are the third witness of
geometry: a faint diagonal watermark that OCR does not read at all (D095
finding (e)) still shows up as a `tlines` row at its angle, on its ink layer.

#### 8.4.5 Features, and where confidence comes from

Raw confidence is kept (the `conf` of every head, verbatim) and **never used
as a probability**: the spike scored a correct heading 0 and a misspelling
554 (D095). A word's trustworthiness is instead a **feature vector** in
`feats`, integers computed by named kin functions, each column -1 until the
stage that can compute it has run:

| column | OCR word (`xo-*`) | text-layer word (`xt-*`) | filled by |
| --- | --- | --- | --- |
| 2 | engine word confidence (raw) | glyphs whose `how` is `tounicode` or `encoding`, per-mille | stage 1 |
| 3 | lowest symbol confidence | glyphs that are PUA, control or U+FFFD, per-mille | stage 1 |
| 4 | mean symbol confidence | worst overlap between neighbouring glyph boxes, per-mille | stage 1 |
| 5 | smallest alternative margin: top minus best *differing* alternative, over symbols | worst gap anomaly: largest inter-glyph gap over the font's space width, per-mille | stage 1 |
| 6 | ambiguous symbols: a differing alternative within `alt-close` of the top | worst `vis` of its glyphs | stage 1 |
| 7 | engine flags (dictionary, numeric) | render mode class (fill, stroke, clip, invisible) | stage 1 |
| 8 | implausible character-class transitions, per-mille of pairs | lowest alpha | stage 1 |
| 9 | code points in the dominant script of its line, per-mille | ink contrast: luminance of fill against the page background under it | stage 1 |
| 10 | kin ink under the box (Sauvola on the source view), per-mille | kin ink under the box on the with-text render, per-mille | P6.3 |
| 11 | engine-binary ink under the box, per-mille | ink under the box on the without-text render (text drawn over ink) | P6.5a / P6.3 |
| 12 | Jaccard of kin and engine ink inside the box, per-mille | share of its ink in the body ink layer | P6.3 |
| 13 | share of its ink in the body ink layer | the font's health on this page (col 2 pooled over font × page) | P6.3 |
| 14 | text-like components centred in the box | text-like components centred in the box | P6.4 |
| 15 | components over symbols, per-mille | components over glyphs, per-mille | P6.4 |
| 16 | mean gray of its ink against background | inside an artifact span or watermark-like OCG (0/1) | P6.3 / stage 1 |
| 17 | height over its line's median, per-mille | height over its line's median, per-mille | stage 1 |
| 18 | x-height over the page's body x-height, per-mille | size over the page's body size, per-mille | P4.2 |
| 19 | angle to the page's dominant text angle (centi-degrees) | the same | stage 1 |
| 20 | baseline deviation from its line (cpt) | the same | stage 1 |
| 21 | mean inter-symbol gap over mean symbol width, per-mille | mean inter-glyph gap over mean glyph width, per-mille | stage 1 |
| 22 | overlap with a seal-candidate primitive, per-mille | the same | P8.2 |
| 23 | overlap with artifact/watermark paint of the text layer, per-mille | overlap with an OCR-free `tlines` row at another angle | stage 1 / P6.4 |
| 24 | similarity to the aligned text-layer word(s), per-mille (1000 minus normalized edit distance) | similarity to the aligned OCR word(s) | P7.1 |
| 25 | similarity to aligned `hidden-text` | similarity to a second OCR pass | P7.1 |
| 26 | agreement with other OCR runs over the same region | — | P7.4 |
| 27 | pages on which the same text recurs at the same place (stage 3 feedback) | the same | P4.3 / P7.5 |
| 28 | its code point count | its code point count | stage 1 |
| 29 | distance to the nearest page edge (cpt) | the same | stage 1 |
| 30 | share of its box inside a fused table region drawn in an image (`grg` kind `table-in-image`, §9.9), per-mille | spare | P7.G |
| 31 | rule-likeness: for a word of at most 2 code points, all of them in the rule-glyph set (U+007C vertical bar, `l I 1 ! [ ] _ -`, U+2010-U+2015), the share of its box covered by fused rules' boxes, per-mille; -1 otherwise | spare | P7.G |

Components, rules and primitives carry their own statistics as fields
(ink, fill, stroke width, gray, confidence of a fit) and have no `feats`
row. A text-layer glyph has none either: its word's row speaks for it.

**Calibrated confidence** is derived from features, never read from an
engine. Per subject kind (OCR word, text-layer word, and later the fused
PageLayer word), a **scorecard** — for each feature column, integer bin edges
and integer points per bin, a `defdata` table — sums to a raw score; the
per-kind isotonic map of §9.6 turns the score into per-mille. Both tables are
fitted offline (`bb calibrate`, P10.1) on words labelled right or wrong by
the gold (the synthetic round trip and its degraded copies, SPEC 14.3), and
committed; a refit is a reviewed change with its reliability diagram. A
scorecard rather than a learned model because it is integer, exact on four
targets, and explainable column by column in the evidence dump, which is
what §9.4's "every decision can be explained" asks. The §9.4
and §9.5 rules read feature columns against named thresholds (settings), and
the rules' own scores are RAW (per kind, mostly one constant per rule): what P10.1
calibrates is what leaves the Model, node kind by node kind (§9.6, D370-D372); the
scorecard and the per-word calibrated confidence above stay the later item they were.

**Ghosts and watermarks** (§9.5) are decided from features, not from raw
confidence: a word with engine ink (col 11) but no kin ink (col 10) is an
engine artefact; one with ink only outside the body layer (col 13 low) is
the watermark's or the seal's; one at an angle to the page (col 19) on a
light layer with a matching `tlines` row is a diagonal watermark read as
text; one with no ink at all on either image is a ghost.

#### 8.4.6 Bundles, codes and the dump

The arenas travel in **four bundles** the host holds as structs, and a kin
function takes only the arenas it touches (D050's rule): *shared* (`epages
views pts refs etext feats`), *text* (`glyphs words lines marks fonts paths
images`), *ocr* (`oruns oblocks olines owords osyms oalts`), *trace* (`inks
components rules prims tlines`). `ea` codes, in this order from 1: `epages
views pts refs etext feats glyphs words lines marks fonts paths images oruns
oblocks olines owords osyms oalts inks components rules prims tlines`.
`ev-init` empties them all; `ev-check` answers 0 or the first broken run,
reference, nesting or stride, by a numbered code.

The dump (`emon-evidence`, §4.5) is **version "1"**: one key per arena in the
`ea` order above, an empty arena printed as `[]`, fields in stride order
under the names of the `ek` table, defaults omitted, runs as `[first,
count]`, references (the `refs` arena, a `feats` subject) as `["arena", index]`
(a column that can only point at one arena, `glyph.font` or `word.line`, is
its plain row number), baselines and boxes as `[x0, y0, x1, y1]`, centi-units
as points, colours as `"#rrggbb"`, packed ASCII (script, language) as
strings, `etext` as one string (an array of one, `[]` when empty), and
`feats` rows with their column names (`xo-*` under an OCR word, `xt-*` under
a text-layer word, `cN` for a column with none). It is table driven
(`ev-fd`), so every cell of every stride is printed or named spare, and it
is not a public contract.

`ar`'s codes are forever: `pagination` 1, `watermark` 2 (v0's subtype stored
as a type; never written by v2), `layout` 3, `background` 4, `other` 5 (an
artifact whose `/Type` is none of the known ones, or absent, so that an
artifact span always has a nonzero type and `mk-artifact-of` answers 0 only for
"no artifact"), `page` 6. `as` (the subtype): `header` 1, `footer` 2,
`watermark` 3, `other` 4.

#### 8.4.7 Annotations and the catalogue; what v2 does not hold yet

**Annotations have their own arenas (P3.8, D120)**, as this section promised: `annots` (38 cells a row: page,
subtype, object number, `/F`, the rectangle in page space (§3.4) and as written, the first
action's kind, target and destination, `/Contents`, `/T`, `/Name`, `/CA`, the field it belongs
to, the appearance's text), `quads` (8 cells: a link's `/QuadPoints` in page space, so a link
that wraps maps onto the words each part covers), `fields` (terminal AcroForm fields: full
name, type, flags, `/V`, `/DV`, the first widget's page and rectangle, the appearance's text,
and how `/V` and the appearance agree) and `sigs` (signature dictionaries: `/Name`, `/M` as
written and parsed, `/Reason`, `/Location`, `/ContactInfo`, `/Filter`, `/SubFilter`,
`/ByteRange`, the size of `/Contents`; never validated). Stamp and Watermark annotations are
`annots` rows like any other, carrying the name, contents, opacity, flags and appearance text
that §8.1 and §9.5 read as watermark and seal evidence. A destination is 8 cells: page (0-based,
-1 unresolved), fit kind (plus 16 when the file gave the page as an index, which poppler and pdf.js
follow and qpdf does not), four arguments, the name it was looked up by. Their `ea` codes are
appended after `tlines` (25 to 28) when P1.12 folds them into the bundles; until then they are
`emon.pdfannot`'s (`kin/pdfannot.kin`), with the same conventions (integer `Cells`, rows of a fixed
stride, strings as runs of an `etext`-shaped arena, absent is -1).

**The catalogue's extras are not evidence** (P3.9, D121): outlines, page labels, Info, XMP and
`/Lang` are read once, at stage 0, into `emon.pdfmeta`'s arenas (`md`, `xm`, `ol`, `lb`, `nd`), and
join the Source (§2.0) as facts about the document, not observations on a page.

**The tagged tree has its own arenas too (P3.7, D148-D151)**, in `emon.pdfstruct` (`kin/pdfstruct.kin`), joined to the
text layer by `marks`: `se` (the StructTreeRoot as row 0, then every structure element in pre-order: its role as written and
the standard type it resolves to through the RoleMap, how, heading level and level prior, page, `/Alt` `/ActualText` `/E`
`/Lang` `/ID` `/T`, Scope, Headers, RowSpan, ColSpan, Summary, ListNumbering, its subtree's glyphs, sizes and box, a table's
rows and columns, a list's labels), `sk` (the kids in order: element, marked content, object reference, skipped), `sc` (each
`/MCR` or bare MCID, joined on `(page, Form XObject, MCID)` to its `marks` row, with the glyphs it owns), `so` (`/OBJR`) and
`sa` (one row per outermost artifact span: where its text sits, and whether it is what its Type and Subtype say). A glyph's
owner is the element of its innermost marked content that carries an MCID, or artifact, or neither. The tree is READ whole and
then CHECKED against the page (D034): the score's components are coverage, role diversity, reading order against geometry,
heading roles against type size, heading levels, artifacts against position and the nesting of table and list roles; the
PDF/UA identification (`pdfuaid:part`) raises the prior and the trust, never the score. Their `ea` codes are appended after
`annots` (29 to 33) when the bundles take them.

**Not held yet**: shadings as painted regions beyond
their box (a `paths` row of kind `other` flagged `shading`, its colour -1: §9.9 takes a shading band's colour from the
tracer's tint primitive over it), the VALUES of a font's `/CapHeight`
and `/XHeight` (their cells exist and are -1 until P4.10 reads them, D108), the Model-level
page source kind (analysis, not evidence), and fusion's own output
(correspondence groups and the PageLayer are P7.1's arenas, built on the
heads above).

---

## 9. Fusion and arbitration (D014)

### 9.1 One space

All evidence is mapped into page space (§3.4). A bitmap answer carries its
request's transform (page, resolution, crop, rotation, deskew), so OCR boxes
map back to centipoints exactly and in integers.

### 9.2 Alignment

Words from different sources are matched by **overlap**: intersection over
the smaller box ≥ a threshold, same line direction, then a monotone alignment
along each line (a word in source A may correspond to two in source B — split
words and merged words are common). The result is a set of **correspondence
groups**, each with 0..n candidates per source.

**As designed (P7.1, D202).** Candidates are the visible text-layer words
(`vis` 0, not `hidden-text`), the `hidden-text` words, every OCR run's words
and the `tlines`. A per-page grid (64 × 64 cells over the page box) indexes
the word heads; a pair is a candidate when `ev-overlap-small` ≥
`align-overlap` (500 per-mille) and the angles differ by at most
`align-angle` (300 centi-degrees). Along each text-layer line, the OCR words
that overlap it are ordered by their position along the line's baseline and
aligned to its words by a dynamic program over the two sequences whose steps
are 1:1, 1:2, 2:1, 1:0 and 0:1 (costs: 1000 minus the overlap of the merged
boxes, and `align-gap` for an unmatched word), ties broken toward 1:1 then
toward the earlier word. A group is a connected set of the chosen steps; an
OCR word in no text-layer line forms a group of its own (OCR-only), as does
a text-layer word no OCR word touched (text-only). Text similarity is `1000 -
1000 * lev / max(len)` over NFC code points with case kept (`xt` / `xo`
column 24) and over case-folded letters only (kept in the group, for the
broken-encoding test of §9.4). Every group's text-layer candidates in
content-stream order and OCR candidates in engine order are runs of `refs`.

**As built (P7.1, D278-D280).** `emon.xalign` (`kin/xalign.kin`, entry `xa-page`; not `emon.align`, which is the paragraph
alignment of §10.10) is as designed, with these made precise. A pairing's cost is 1000 minus the intersection over union of the
merged words' **extents along the line** (not of their boxes: the sources draw a word's height differently and agree on where
along the line it is); the candidate test stays on the boxes. An OCR word belongs to the text-layer line of its best candidate
and is ordered by its centre along that line's baseline, reversed for a right-to-left line. The visible layer and the
`hidden-text` layer are aligned apart, each against every OCR run; a group is the connected set of all the steps, so one group
holds a word of each source and each run. What no step matched is **attached** when the other source covers it (an OCR word
inside a text-layer word joins its group as a `split`, a text-layer word inside an OCR word as `merged`); an OCR word still
unmatched joins only an overlapping OCR word of another run. Similarity concatenates a side's words without a separator and is
capped at 256 code points; the folded form keeps letters and digits. The columns written are `xt-sim-ocr` (the first run),
`xt-sim-ocr2` (the second), `xo-sim-text` and `xo-sim-hidden`, per step with case kept. It is measured on nine real
documents (`bb align`: pdfium renders, Tesseract's reading, replayed on Rust and Go; DEVLOG P7.1).

### 9.3 Health

Before comparing words, each source is scored **per region** (per font × page
for the text layer):

* **Text layer**: fraction of glyphs with a trustworthy mapping (`ToUnicode` or
  known encoding) versus guessed or unmapped; Private Use Area and control
  characters; CID-without-mapping patterns (`(cid:12)` garbage); impossible
  widths (glyph boxes overlapping heavily or with enormous gaps); and, once
  OCR is in, the **agreement rate** with OCR over words both see.
* **OCR**: the feature vector of §8.4.5, not engine confidence alone:
  symbol confidences and alternative margins, dictionary-free plausibility
  (character-class transitions, e.g. `l1Il|` soup), ink support from kin's
  and the engine's own binarisation (a word over blank paper is a ghost),
  its ink layer, and whether the region overlaps a seal, watermark or
  background layer.
* **Structure tree** (P3.7, D149): role diversity; agreement of its reading order with
  geometry; coverage (fraction of visible text reachable from it); and the checks of
  what it claims against the page: heading roles against type size, heading levels
  against each other, artifacts against their position, table and list roles against
  where the standard puts them. Integer per-mille components, a weighted harmonic mean,
  scaled by the weakest check; PDF/UA raises the trust and never the score.
* **Graphics** (D223, §9.9): per region (the page outside images, each image
  placement): how much of the exactly known vector paint the tracer
  witnesses, the tracer's precision on the same page, the skew spread and
  confidence of the tracer's rules.

**As designed (P7.0, P7.2, D203); step one as built (P7.0, D240-D242).** The text layer's health is computed in
two steps. **Without OCR** (P7.0, the document pass, `kin/tlhealth.kin`): per (font, page) the
pooled stage-1 `xt` columns (mapped share, PUA/control/U+FFFD share, worst
overlap, worst gap anomaly: glyph-weighted means over the words) give `tl-health`, a per-mille in the `hl` arena
(components 1000 - garbage, mapped, an overlap ramp, a gap ramp; weights 4, 3, 2, 1; each floored at 50; scaled by
`(1000 + weakest) / 2000`),
and fill `xt` column 13; this alone drives `ocr: auto` and is what a run with
no OCR engine has. It catches a font that has no Unicode for its glyphs (`tl-broken-unmapped`: 34 against `tl-trusted` 700) and
does NOT catch a plausible wrong mapping (`tl-broken-shift`): only OCR agreement can. **With OCR** (P7.2, the page unit): the agreement rate of
the (font, page) — groups with a text-layer and an OCR candidate whose
case-folded similarity is at least `agree-sim` (900), over groups with both
— joins as a component, and a font whose agreement is low on **every** page
is unhealthy everywhere (a font's encoding is a document fact). An OCR
**region** (an `oblocks` block) is scored from its words' `xo` columns (ink
support 10-13, plausibility 8, script 9, layer 13, artifact and seal overlap
22-23). Every component and the score are rows of `hl`, so the dump says why.

**Step two as built (P7.2, D281-D283).** The agreement is a fifth component (a ramp from 200 to 800, weight 5) that counts only on a
page with at least `agree-words` (8) groups and only for a group whose text-layer candidate holds a Latin letter or digit (an engine
without the script's model reads soup, and the text layer is not wrong for that); its two counts are the row's spare cells 10 and 11.
A font under `agree-min` (600) on every page that has the groups is unhealthy on all of them (`hl-doc`). An OCR block's row has
the components ink support (averaged with the tracer's symbols-with-a-component share), plausibility, script, body share, seal and
artifact freedom in cells 3-8; a column not computed is absent (-1), never zero, and the engine's confidence is not one (D130).
`hh-word-score` is the same for one word. A graphics region's row (kind `region`, subject -1 for R0 or the `images` row) has the
vector witness and the tracer's precision from the caller (the vector rules are not this module's), the skew spread and the mean
confidence of the region's rules, and their number.

### 9.4 Arbitration

Per correspondence group, in order:

| Situation | Winner |
| --- | --- |
| Healthy text layer, OCR agrees (normalized edit distance small) | text layer — exact code points; conf high; `src` both |
| Healthy text layer, OCR disagrees | text layer; conf lowered; if the region's agreement rate is poor, the region's health drops and the rule below applies instead |
| Unhealthy text layer (garbage, unmapped, suspicious), OCR plausible | OCR; flag `text-layer-suspect`; the text-layer candidate goes to the evidence dump |
| Text layer absent (scan, text in image), OCR present | OCR; `ocr-only` |
| `hidden-text` and OCR | this run's OCR, unless its confidence is low and the hidden text agrees with the ink better (it may come from a better engine) |
| OCR word with no ink support, or inside an artifact layer | dropped as `ocr-ghost`, or reassigned to the seal/watermark it belongs to (§9.5) |
| Two OCR passes | the one with better confidence *and* ink support; symbol-level alternatives used to break ties toward the text layer where one exists |

Rules are **explicit, ordered and data-driven**: thresholds are named
settings with defaults tuned against the corpora, not literals in code.
Every decision can be explained from the evidence dump.

**The born-digital contract (D201).** Accuracy on born-digital PDFs is
emon's best number (the synthetic `pdf/all` CER is under 1 %), and OCR on
those pages exists to catch what the text layer gets wrong, never to
second-guess what it gets right. So, in the order the rules are tried:

1. **Visibility first.** A text-layer word whose glyphs failed a P3.6 check
   (`vis` ≠ 0) is never a body candidate: it goes to the ledger as
   `clipped`, `offpage` or `invisible` (P7.0, D208), whatever OCR says.
2. **A healthy font is untouchable.** A visible text-layer word of a (font,
   page) whose health is at least `tl-trusted` wins its group whatever OCR
   reads; disagreement only lowers its confidence and counts against the
   agreement rate. OCR replaces text-layer words only where the font is
   unhealthy (garbage, unmapped, or an agreement rate under `agree-min` on
   every page of the document), or where the word itself has unmapped,
   PUA or U+FFFD glyphs and OCR is plausible. The only other things that
   take such a word out of the body are the PDF's own say (§9.5 defence 1),
   recurrence (defence 2, and the furniture stage), and a seal's arc text
   (defence 5): never its colour, angle or ink, which judge OCR-only text.
3. **OCR-only text is admitted only where the text layer cannot be.** An
   OCR-only group becomes body text when its ink is on the body layer, it
   lies outside every visible text-layer word's box, it is plausible (§9.5
   defence 6), and it is NOT inside an image placement or vector-figure
   cluster smaller than `scan-region` (half the page): text in a picture is
   the figure's (`labels`, P8.1), held in the PageLayer with flag `in-figure`
   and not emitted to the body until P8.1 consumes it. Outlined text and a
   scanned insert are the cases it admits.

Every rule is a row of the `fr` table (§9.7): its number, the feature columns
it reads with their named thresholds, the winner, the flags and ledger reason
it sets. Numbers are assigned in blocks so that independent items never
renumber each other's rows: 100-199 the core rules of the table above,
200-299 watermarks, backgrounds and ghosts (§9.5), 300-399 `hidden-text`,
400-499 seals and signatures, 500-599 graphics (rules and fills, §9.9: they
run on graphics groups, not on word groups), 600-699 where graphics meet words
(§9.9: a border OCR read as `|`, the text of a table drawn in an image). The
blocks are tried for a word group in the order 400, 600, 200, 300, 100
(`fr-order`, D224): what belongs to a seal, to a rule's ink or to an artifact
is decided before which source reads it best.

**As built (P7.3, D285-D290).** `kin/fuserules.kin` holds the table as data (`fr-rows`: number, block, winner, ledger reason, flags,
confidence, six `(group column, comparison, setting)` predicates; the settings are named in `fr-setting`, the group columns `ag-*`) and
`fr-order`; `kin/arbiter.kin` measures each group's columns and applies the first row that holds. Block 100 is built: 101/102 the identity
(a sole text-layer or hidden candidate), 110-112 a healthy font with no bad glyph of its own wins whatever OCR reads (only the confidence
moves: 1000, 700, 500 with `low-conf`), 120 a bad glyph of the word's own and 130 an unhealthy font give the group to OCR when the engine
reads the script (a Latin letter or digit in the text layer's word) and the OCR is plausible (`text-layer-suspect`), 140/141 an OCR-only
group that is plausible and well scored is admitted when it lies in no image or in a scanned insert, 150 in a smaller image it is held
(`in-figure` on the group), 170/175 an implausible or poorly scored OCR-only group is dropped as an `ocr-ghost`, 190 the default. Row 160
(two OCR passes) is still not built: P7.4's targeted runs are written after the page is aligned (`emon.retarget`), so they are evidence and, for the body-ink one, a
witness (row 222), and arbitration never chooses between two readings of a group. The conditions that need kin's ink layers (body-ink share, kin ink) are read
wherever the Engine was given pixels (P7.4, D343). **Arbitration runs in the document unit**, after every page unit has aligned and scored its page and
`hl-doc` has judged the fonts (an encoding is a document fact); the page unit asks the two rasters (`pu-ask-rasters`), the OCR of the one
with its text, and runs `xa-page` and `hl-page`. The fused flow keeps the text layer's geometry for a word OCR replaced; OCR-only words
admitted are lines of their own after the page's others. Since P7.S (D200) a born-digital page is OCR'd under `ocr: always` (and under `auto` when
§8.3 says); with no engine nothing here runs and every output is byte-identical.

### 9.5 Watermarks, backgrounds and ghosts (D015)

Dropping a background image behind text is easy: it is an image placement
under text, and the text layer or OCR is read on top of it. The hard case is
the maintainer's: **OCR reads a watermark or a background seal as text.**
Defence in depth, strongest signals first:

1. **The PDF says so.** `/Artifact` `/Watermark` marked content, Watermark
   annotations, optional-content groups named like watermarks, and text or
   images drawn with alpha < 1 across most of the page. These classify
   before OCR even runs, and the plan asks the renderer for a render
   **without** those objects where the engine can mask.
2. **Recurrence across pages.** The same text (or the same image XObject, or
   the same component shape) at the same position on most pages, and not in
   a header/footer band → watermark. Computed in stage 3 and fed back: the
   ledger records it, and OCR words matching it on each page are removed.
3. **Ink layers.** A watermark is almost never the body text's colour and
   almost always lighter or differently hued. Colour clustering separates the
   layers; OCR is run on the **body-ink layer** image (the others blanked),
   and a word present in a full-image OCR pass but absent from the body-ink
   pass is attributed to the layer it came from.
4. **Geometry.** Text at a large angle to the page's dominant text direction
   (diagonal watermarks), very large relative size, or letter-spacing far
   from the body's.
5. **Seal overlap.** OCR words inside a detected seal/stamp region (§10.7) are
   assigned to the seal's `text`, never to the body.
6. **Plausibility.** Low confidence plus low plausibility plus no text-layer
   support → ledger (`ocr-ghost`), never body.

Bleed-through (text from the reverse side of a scanned page) is the same
problem mirrored: very light, mirrored glyph shapes, no text-layer support —
classified by (3) and (6).

**Against what is built (P7.5, D204).**

1. *The PDF says so* reads the text layer, not the renderer: a glyph's
   `mark` chain (`mk-artifact-of`: subtype `watermark`, type `background`),
   its `ocg` span (an OCG off by default or with an `/AS` view state, D154,
   or named like a watermark), Watermark annotations and Stamp annotations
   from `emon.pdfannot` (wired into the document pass by P7.5), alpha under
   `wm-alpha` on text or images covering over `wm-cover` of the page, and
   the tagged tree's verified Watermark artifacts (D149, weighed by trust).
   pdfium cannot render without them (D247), so the classified text-layer
   words go to the ledger directly, and the OCR words their glyph boxes
   cover (aligned to them, §9.2) go with them: **an OCR word is never body
   text when it is aligned only to a watermark-classified text-layer word.**
   On a born-digital page the `no-text` render already lacks every text
   watermark; an image watermark is in it and is defence 3's.
2. *Recurrence* is a second arbitration pass, document-wide, after every
   page unit has answered: the furniture stage's watermark rule (D173-D177:
   a diagonal line ≥ 2.5× the body size, at most four words) and a new
   recurrence of OCR-only groups (the same case-folded text within
   `wm-recur-pos` of the same place on ≥ `wm-recur-pages` of the pages,
   outside the furniture bands) mark the groups; their words move to the
   ledger as `watermark` on every page.
3. *Ink layers*: a word's body-ink share (`xo`/`xt` column 13) under
   `wm-body-share` with its ink on a `light` or `colour` layer is attributed
   to that layer. The **body-ink OCR pass** (`op-body-ink`, P7.4) OCRs a
   kin-made view in which every non-body layer is painted paper (§7.3); a
   word in the first pass with no counterpart in the body-ink pass is the
   other layer's. **As built (P7.4):** rows 221 (the ink's body share) and 222
   (absent from the body-ink run) of block 200, for an OCR-only group of at
   least three letters or digits outside every image placement (what is in a
   picture has the picture's ink, and what is shorter is a speck: ghost rows).
4. *Geometry*: column 19 (angle to the page's text) over `wm-angle` with a
   matching `tlines` row on a non-body layer; column 18 over `wm-size`.
5. *Seal overlap*: until P8.2, a seal candidate is a P6.4 primitive (`ring`,
   `circle`, `ellipse`, or a `rect` with a border on a `colour` layer) or a
   cluster of `colour`-layer components; OCR words whose centres are inside
   it go to the seal placeholder's `text` (P7.7), never to the body.
6. *Plausibility*: an OCR-only word with kin ink (column 10) under
   `ghost-ink` is an `ocr-ghost` whatever its engine confidence; one with
   engine ink (11) but no kin ink (10) is the engine's artefact; low
   plausibility (8) together with low symbol margins (5) and no text-layer
   support goes to the ledger as `ocr-ghost`.

Each defence is a block of `fr` rows (200-299), so the dump names the
defence that removed a word, and the ledger entry carries the reason
(`watermark`, `background`, `bleed-through`, `ocr-ghost`).

**As built (P7.7, D314-D319): defence 5.** The seal's block is 400 (rows 401, 402), tried before every other. Its candidates are read from what a
born-digital page holds and a replay reproduces, with no pixel: the page's VECTOR paths (a round stroked path of at least 8 mm radius, a stroked
quadrilateral with a border of at least 1.5 pt in a chromatic ink, open stroked curves near a cue word) and the glyphs' inks; the tracer's ring,
circle, ellipse and `colour`-layer components are the raster half, which comes with P7.S (D316). A text-layer word is a seal's by its ink (chromatic and
the candidate's) or by its turn along the ring (the arc text: rotation varying along it); black upright text under a stamp or inside a ring is body
text (D315). A correspondence group whose every text-layer word is a candidate's, or an OCR-only group inside one, is flagged `in-seal` before
arbitration (`emon.sealmark`'s `sm-find`) and rows 401 and 402 hold it with no winner and no ledger row: its words are the `seal` node's `text`
(`low-conf`), written in reading order after the block the candidate overlaps (`sm-write`, D318). **P8.2 (D412-D418)** adds the raster half: a seal found in
the tracer's primitives is a correspondence group flagged `seal-px`, which `sm-find` turns into the same `sl` row (§10.7).

### 9.6 Confidence calibration

Raw scores from each rule are combined and **calibrated per node kind** by
isotonic mapping fitted on the evaluation corpora (§14), then committed as a
`defdata` table. For evidence words the raw score is the per-kind integer
scorecard over the feature columns of §8.4.5 (D096); an engine's own
confidence is one column of it, never the answer. Recalibration is a reviewed change with its reliability
diagram in the DEVLOG entry.

**As built (P10.1, D370-D375).** The rules' own scores are RAW (one constant per rule and kind, §3.5) and
stay raw in the arenas, where the rules read them. What leaves the Model (JSON `conf`, HTML `data-emon-conf`)
is `cal-conf kind src raw` of `kin/calib.kin`: a step function of the raw score, per **node kind** (the
ledger of dropped words is the kind `dropped`) and **source class** (`text` layer, `ocr`, `fused`, `infer`:
the node's own `src`), read from the `defdata` table `emon.calibdata` that `bb calibration --fit` writes
(`kin/calibdata.kin`, with `test/calibration/fit.edn`); the Model header says `"conf":"calibrated"`. The
measure is `bb calibration` (a report, §14.2's `confidence.ece`), whose data are committed under
`test/calibration/`: the reliability diagram as CSV (`reliability.csv`: per kind, per kind and class, and per
category and run: ten bins of the confidence, how many nodes, their mean confidence, how often they were
right) and SVG, `summary.csv` (ECE, the Brier score, and both held out), and the `raw-` twins of them from
before the map was applied. A node is RIGHT when the gold agrees (D371); the fit is isotonic regression of
that outcome on the raw score, Laplace-smoothed, with a kind of fewer than 8 nodes left as it is (D372). The
scoreboard ratchets `conf-ece-KIND` and `conf-brier-KIND` (lower is better) for every kind and every kind and
class (D374). The honest limit: a rule's raw score is one number for every document, so the map can make
a kind's confidence TRUE ON AVERAGE and cannot say WHICH document is the hard one (the paragraphs of a
chart's labels are as sure as a prose paragraph's); that needs features the rules do not carry, which is
the scorecard of §8.4.5 (still to come) and not a better map.

### 9.7 The PageLayer arenas (D195, D203)

Integer `Cells` like the evidence (§8.4: absent -1, runs `first count`,
references `(ea, index)` in `refs`), made by the page unit, appended to the
document's by `pl-append` (which re-bases every index), and dumped with the
evidence (dump version "2": these arenas after the tagged tree's, `ea` 34 on: `cg fw fl fv fa fx hl` 34-40,
`grl` 41 (the code planned for `fpa`), `sl` 42, `gfl ggp grg` 43-45, D218):

| arena | stride | fields |
| --- | --- | --- |
| `cg` | 16 | page, box, rot, text-layer candidates `refs` run, OCR candidates `refs` run, `hidden-text` candidates `refs` run, `tlines` reference (ONE cell, its arena being known: D240), similarity (case kept), similarity (case-folded letters), flags (`plg`: text-only, OCR-only, split, merged, in-figure, in-seal, **bink** and **bink-absent**: the page has a body-ink OCR run and no word of it lies over the group, P7.4; **seal-px** and **seal-stamp**: the group is a seal's (a stamp's) reading found in pixels, its `rot` the colour code, P8.2; **seal-sig** and **seal-init**: the group is a handwritten signature's (initials') strokes found in pixels, with no word, its `rot` the colour code, P8.3) |
| `fw` | 24 | **the layout of `words`** (word head 16, style, glyph run, font, size, gap, `wbreak`, `src`): `src` is the sources bitset (`sr`: text, hidden-text, ocr, trace), the glyph run is the text-layer word's when it won, empty otherwise |
| `fl` | 16 | **the layout of `lines`**; a line of OCR-won words is measured as D213 measures an OCR line |
| `fv` | 8 | per `fw` row: group, winning source (`sr` code), rule (`fr` number), confidence (per-mille, uncalibrated until P10.1), flags (`fl`: low-conf, ocr-only, text-layer-suspect), spare (3) |
| `fa` | 12 | artifacts set aside: page, box, ledger reason (`dr`), subject `(ea, index)`, defence (1-6, 0 none), group, rule, spare (1: D240) |
| `fx` | 4 | explanation: group, rule, feature column, value read — a run per group, every column the deciding rule read |
| `hl` | 12 | health: subject kind (font-page, OCR block, region, **graphics-region**), page, subject index, components (6: mapped, garbage, overlap, gap, agreement, ink; for a graphics region: vector witness, tracer precision, skew spread, tracer confidence, rules, fills, D223), score |
| `grl` | 24 | **the fused rules** (D218, §9.9; replaces D195's `fpa`, taking its `ea` code): page, box, centre-line endpoints `ex0 ey0 ex1 ey1` (cpt), thickness (cpt), orientation (`go`: h, v, slant), angle (centi-degrees, `ev-angle`), colour (0xRRGGBB, -1), style (`rs`), sources (`gs` bitset: vector, trace, ocr, struct, image), winning source, rule (`fr` number), confidence (per-mille), flags (`gq`: traced, unwitnessed, in-image, box-edge, from-fill, artifact-layout, artifact-pagination, in-figure, from-image), group (`ggp` row), `z` (the vector member's, -1), spare (3) |
| `gfl` | 16 | **the fused fills**: page, box, colour, lightness (0..1000), sources, winning source, rule, confidence, flags (`gq`: shading, pattern, in-image, tint-coloured: the colour came from the tracer), group, `z`, spare (2) |
| `ggp` | 12 | **graphics groups** (the `cg` of graphics): page, kind (`gk`: rule, fill, region), vector members (`refs` run of `(paths, i)` and `(pts, k)` pairs: the path and the first point of the segment), tracer members (`refs` run of `rules` or `prims` rows), OCR members (`refs` run of `oblocks` rows), flags, spare (3) |
| `grg` | 16 | **graphic regions**: page, box, kind (`rk`: table-in-image, figure, table-hint), sources (`gs`: vector, trace, ocr, struct, image), subject (`(ea, index)`: the image placement, the `oblocks` block, the `se` element, -1), fused rules inside (count), fills inside (count), confidence, flags, spare (3) |
| `sl` | 16 | seal and signature candidates: page, box, kind (`seal`, `stamp`, `signature`, `initials`, `digital-signature`), ink row, primitive or component reference (as built: `(paths, row)` of the vector path), confidence, OCR words inside (a `refs` run), flags (as built: 1 chromatic, 2 double ring, 4 inner ring of another, 16 roundness unverified, 32 text from the text layer, 64 written, 128 read from pixels (P8.2: the ink cell is then the colour code), 256 the text run is a digital signature's record, 512 that field is signed, 1024 the text run is a signature's typed name; P8.3), and in the three spare cells the node's `etext` run and ink (D317) |

**The tagged tables' verdicts, `tb` (D302, P5.5).** A document whose tagged tree has a `Table` adds one key after the arenas, `tb`, an
array with a row for every tagged table the table stage read (§10.5), in document order: `page`, `se` (the `Table` element's row), `rows`,
`cols` (its grid), `verdict` (`confirmed`, `partial`, `contradicted`), `score` (per-mille), `checks` (six per-mille values: `row-band`,
`col-band`, `order`, `cover`, `rules`, `geo`; -1 where there was nothing to check) and `trust` (the tree's, D150). It is written once the
tables are found, so it is the only key not computed from the arenas as they stood at the start of stage 3; a document with no tagged table
has no `tb`, and `bb accuracy` reads "no `tb`" as the verdict `none` (D227).

`fr`, the rule table, is `defdata` in `kin/fuserules.kin`: per row its
number, its block, the winner, the ledger reason, flags to set, and up to
six `(feature column, comparison, setting name)` predicates, all of which
must hold. The thresholds are named settings (§13) whose defaults sit in the
same file, so a tuning is a reviewed diff of one file. A row of block 500
reads **graphics measures** instead of feature columns (graphics have no
`feats` row): codes 100 and up in the same predicate cells, named by the `gmz`
table of `kin/gfuse.kin` (§9.9), and `fx` rows of a 5xx rule name a `ggp`
group, not a `cg` one.

### 9.8 What `ocr` costs, and what may be skipped (D200, D210)

`ocr: always` (D038) promises that **every page has an OCR witness**: every
region of ink that the text layer does not explain is read by OCR, and every
(font, page) of the text layer is checked against OCR. Under `ocr-scope:
full` (the default) every page's whole render is read, which keeps the
promise trivially; under `ocr-scope: witness` the no-text render plus
witness lines keep it at a fraction of the cost (§8.3), and a font whose
witnesses disagree escalates the page to the whole render (D364). The
default stays `full` (D200: the maintainer decides the flip); `bb
fusion-bench` shows `witness` equal on every accuracy metric of the fusion
set at 0.48 of the OCR time (§12.3).

Lossless savings, taken in every scope:

1. The raster stages run on a page only in the rounds that have its OCR
   answers (the first pass is planned without pixels), so a page's kin raster
   work runs at most twice (first pass, targeted pass).
2. Deskew runs only on decode views; the tracer reads only the `no-text`
   render of a born-digital page; ink layers are found on a 4096-bin
   histogram, not per pixel.
3. On a born-digital page the OCR languages are the text layer's scripts'
   models (plus the locale's), not every installed model.
4. Bitmaps live only as long as their page unit (§2.2), so memory is one
   page's renders, not a document's.
5. The host may run page units concurrently (§12.1); results merge in page
   order.

`ocr: auto` OCRs a born-digital page only when the health without OCR, the
image coverage or `hidden-text` call for it (§8.3), and `never` asks for
nothing; both are cheaper than `always` by design and weaker by design.

### 9.9 Graphics fusion (D218-D224, D228)

Words are not the only thing three sources see. A table's borders, a header's
fill, a zebra band, a box around a note are drawn by the PDF's paths, seen by
the tracer in a render or a scan, and sometimes reported by OCR's layout
analysis; the tagged tree may say a region is a table. Graphics fusion does for
these what §9.2-9.4 do for words, and its single output, the fused rules
`grl` and fills `gfl` (§9.7), is all the table stage reads (§10.5). A
born-digital table, the same table scanned, and a scanned table pasted into a
born-digital PDF then go through one table code.

**Objects.** A **rule**: a straight stroke with a centre line (two points), a
thickness, an orientation (`h` within `gf-axis` (100 centi-degrees) of
horizontal, `v` of vertical, else `slant`), a colour and a style (`rs`:
solid, dashed, dotted, double). The edges of a **box** are rules flagged
`box-edge`. A **fill**: an axis-aligned filled rectangle (a shading band, a
header fill, a cell background) with a colour. A **region** (`grg`): a box
that is a table drawn in an image, a figure, or a hint (D228).

**The sources, and what each is trusted for:**

| source | gives | trusted for | arenas |
| --- | --- | --- | --- |
| PDF paths (`vector`) | exact geometry, colour, dash, paint order | everything it draws, when visible and exactly known (§8.4.3) | `paths`, `pts`, `images` (a thin image placement is a rule) |
| the tracer (`trace`) | what the render or scan shows | raster-only content: scans, images of tables, tiling patterns emon does not interpret; the render's witness of vector paint that is not exactly known | `rules`, `prims` (`rect`, and `tint` fills, D226) |
| OCR layout (`ocr`) | Tesseract's line-separator and table blocks (`oblocks` `bt-line`, `bt-table`) | a tie-breaker only: never a rule or a table on its own | `oblocks` |
| the tagged tree (`struct`) | that a region is a table; its rows, header scope, spans | a prior, verified against geometry and weighed by `tg-trust` (D034, D225); used in stage 3, at the table level, not per rule | `se`, `sc` |

**Vector candidates (D220).** `gv-page` decomposes each painted `paths` row
(not a clip) into segments by its `pts`: an `m`..`l` run is straight segments;
a closed four-point axis-aligned subpath is a rectangle; a curve whose control
points lie within `gf-flat` (50 cpt) of its chord is a straight segment (some
producers draw rules as curves), any other curve is figure material. Then, with
D179's sizes per segment or subpath: a stroked segment is a rule (thickness the
stroke width; a zero width is drawn as one device pixel and counts as 24 cpt);
a filled rectangle at most `RULE_T` (4 pt) thick and at least `RULE_MIN` (5
pt) long is a rule along its long axis; a stroked rectangle larger than that
is four `box-edge` rules; a filled rectangle larger both ways is a fill; a
shading (`sh`) or pattern fill is a fill of colour -1 over its region. A
visible image placement at most 4 pt thick and at least 5 pt long is a rule
candidate of the vector source too (flag `from-image`, colour unknown). Each
candidate is **trimmed to its clip's box** (a border drawn past a clipped
cell ends where the clip does) and carries its path's `vis`, `vchk`, `z`,
`dash` (a dash at most twice the thickness is `dotted`, else `dashed`), the
`/Artifact` type of its `mark` chain (`artifact-pagination` rules are a
header's or footer's, never a table's) and its **contrast**: the luminance
difference between its ink and the `backdrop` P3.6a found, or against white
when the backdrop is unknown; under `gf-contrast` (16 of 255) it is not a rule
(this replaces D179's "mean channel under 240", so white separators on a dark
header are rules). A truncated path (`pg-truncated`) contributes its box only,
as D179 did, and is flagged.

**Alignment (D221).** Graphics are aligned per orientation class. Two
candidates of different sources are **near** when their angles differ by at
most `gf-angle` (150 centi-degrees), the perpendicular distance between their
centre lines is at most half the sum of their thicknesses plus `gf-perp` (120
cpt), and their extents along the axis overlap or come within `gf-gap` (300
cpt). Near pairs are found through the same 64 × 64 page grid as words (§9.2);
a **group** (`ggp`) is a connected set of near candidates (union-find, the
smaller root wins). A group's **axis** is its vector members' (the
thickness-weighted mean position), else its tracer members'. Every member's
endpoints are projected on the axis, which they cut into **elementary
intervals**; each interval records which kinds of member cover it: exactly
known visible vector, visible vector not exactly known, invisible vector,
trace, OCR line block. Intervals with the same verdict (below) and no gap
merge, and each merged run is one `grl` row. Gaps are never bridged by fusion:
a missing stretch of a vector rule is an open edge, which is how a ruled table
says "span" (D179); the tracer bridges its own dropouts (`rule-gap`) before
fusion sees them. Fills are aligned by box: a pair is near at IoU ≥
`gf-fill-iou` (700). Tolerances are in centipoints, so a 150-dpi scan and a
300-dpi render are aligned by the same numbers.

**Health per region (D223).** The page is cut into **regions**: R0 the page
outside every visible image placement, and one Rᵢ per visible image placement
at least `gf-img-min` (2 cm) each way (a raster page is one region). Each gets
an `hl` row of subject kind `graphics-region` whose components are: **vector
witness** (R0 with raster answers: the length of exactly known visible vector
rules the tracer witnesses, over their length; -1 without raster); **tracer
precision** (R0 of a born-digital page: the length of tracer rules explained
by a vector candidate of any visibility, or lying in glyph or word boxes, over
all tracer rule length: the tracer's precision measured on this very page);
**skew spread** (Rᵢ and raster pages: the spread of the angles of its `h` and
`v` tracer rules, 1000 at ≤ 30 centi-degrees falling linearly to 0 at 300);
**tracer confidence** (the mean of its rules'); and the counts of rules and
fills. The region's score is D150's shape (a weighted harmonic mean scaled by
the weakest component), and a raster region's score borrows the page's
tracer precision when the page has R0.

**Arbitration (D222)** is rows 500-599 of `fr`, tried in order per merged
interval run (rules) or per fill group, each reading graphics measures (the
`gmz` codes, §9.7):

| row | situation | outcome |
| --- | --- | --- |
| 501 | exactly known visible vector, the page has no raster answer | vector, confidence 900: **the identity** (514 is its counterpart for paint not exactly known) |
| 505 | a vector member whose `vis` ≠ 0 | contributes nothing; recorded in `fx` (a hidden rule is not a rule, whatever else is there) |
| 510 | exactly known visible vector, trace witness | vector geometry, sources vector + trace, 1000 |
| 511 | exactly known visible vector, no witness, raster answered | vector, flag `unwitnessed`, 800 |
| 512 | vector not exactly known (blend, soft mask, colour -1, a `from-image` rule), trace witness | vector geometry, the tracer's colour when the vector's is -1, 950 |
| 513 | vector not exactly known, no witness, raster answered | dropped from the fused set, never silently: the ledger records it (`unwitnessed-uncertain-paint`, with the rule's page, box and paint) |
| 514 | vector not exactly known, no raster | vector, 700 |
| 520 | trace only, in R0, in a group that has an exactly known visible vector member | dropped: the vector says where its rule ends, and a stretch it leaves open is a span (D179), not a dropout |
| 530 | trace only, inside an image region Rᵢ | a rule (`traced`, `in-image`) when the tracer's confidence ≥ `gf-trace-min` (600), lowered by `gf-ocr-bonus` (150) when an OCR `bt-line` block covers ≥ 500 per-mille of it (the tie-breaker), and the region's score ≥ `gf-img-health` (500); confidence: the tracer's × the region's / 1000 |
| 531 | trace only, raster page | as 530, without `in-image` |
| 536 | raster-only long, thin, straight, high-confidence light neutral rule | admitted independently of region skew noise, 700; OCR-word rejection still precedes this row (P12.8, D640) |
| 532 | trace only, R0 of a born-digital page | dropped when ≥ 500 per-mille of its box lies in glyph cells or text-layer word boxes (strokes of letters); else a rule (`traced`, 600) only when the page's tracer precision ≥ `gf-tprec-min` (900) |
| 533 | trace only, ≥ 800 per-mille of its box inside one OCR word of ≥ 3 code points not of the rule-glyph set | dropped (the bar of a large `E` or `T`) |
| 540 | OCR line block only | never a rule; recorded |
| 560 | visible vector fill with contrast | vector box and colour, 1000 (900 without raster) |
| 561 | vector fill of colour -1 (shading, pattern) with a `tint` witness | vector box, the tint's colour (`tint-coloured`), 950 |
| 562 | vector fill of colour -1, no witness | vector box, colour -1, 600 |
| 570 | `tint` only, in Rᵢ or on a raster page, region score ≥ `gf-img-health` | a fill (`in-image` in Rᵢ), 700 |
| 575 | raster-only high-confidence uniform wide light neutral row band | admitted independently of region skew noise, 700 (P12.8, D640) |
| 571 | `tint` only, R0 of a born-digital page | a fill, 500, when the page's tracer precision ≥ `gf-tprec-min` (a tiling pattern emon does not interpret); else dropped |

**The graphics contract (D222)**, the counterpart of D201: a visible, exactly
known vector rule or fill is **untouchable**: the tracer and OCR add what the
vector source lacks and witness what it cannot be sure of, and never remove
what it is sure of. On a born-digital page without images, every table is
therefore the one the vector source alone gives, with or without OCR.

**Where graphics meet words (D224)**, rows 600-699, tried for word groups after
the seal block (`fr-order` 400, 600, 200, 300, 100): **601** an OCR-only word
whose rule-likeness (`xo` 31) is at least `rg-cover` (700) goes to the ledger
as `rule-ghost` (Tesseract reads a scanned border as `|`, `l` or `I`); **610**
an OCR-only word inside a table drawn in an image (`xo` 30 ≥ 500) with kin ink
(`xo` 10 ≥ `ghost-ink`) and plausibility (`xo` 8 ≤ `ghost-implausible`) is
body text, flag `in-table-image`: a scanned table in a born-digital PDF is
read as a table, not held as a figure's labels (rule 150).

**Regions (D228).** `grg` rows: **table-in-image**, a visible image placement
whose fused rules make a lattice (two `h` and two `v` rules touching within 3
pt: D179's component test without its text test) or whose fused fills make a
stack of three bands of one extent; **figure**, merged at IoU ≥ 500 from
visible image placements that are not tables, clusters of at least
`fig-min-paths` (8) vector paths that are curves, polygons or slanted rules
within `fig-gap` (6 pt) of each other, the tracer's `large` components on
raster pages, OCR `bt-image` blocks, and the tagged `Figure` elements whose
marked content owns paths or images (their box, through `mark` → `sc` →
`se`); **table-hint**, an OCR `bt-table` block (confidence 300) or a tagged
`Table` element's box (confidence `tg-trust`). Rules 140 and 150 read
`figure` regions as their "vector-figure cluster"; stage 3 reads
`table-in-image` and `table-hint`; P8.1 reads `figure`. None changes the
Model before P8.1.

**Placement and cost.** `gf-page` runs in the page unit after the tracer and
the OCR evidence and before `al-page` (D195's order, as amended), so rules 140,
150, 601, 610 and the seal candidates (P7.7) can read `grl` and `grg`. A page
with no raster answer runs it over the vector source alone (rows 501, 505,
514, 560, 562), which is the identity: `tb-build` over `grl`/`gfl` gives the
tables `tb-build` over `paths` gave (P5.5a proves it byte for byte before it
improves anything). Budget: ≤ 5 ms a page (Rust), inside §12.3's fusion line.

**As built (P7.G, D325-D331).** The identity pass and the fusion are two calls, because the page unit does not run `gf-page` yet and the tracer is
not in it until P7.4: `gf-page` stays in the document pass over the vector candidates alone, now **through the rows of block 500 as data** (`fr-decide-block`
over the `gmz` measures, §9.7; no group is made); `gf-fuse` runs in the document unit after the page units, over the evidence arenas, on every page that has a
**raster answer** (the tracer ran: the page has `inks`, `rules` or `prims` rows; OCR alone is not one, so a page that only OCR read is the identity) and
reads the vector rows already made as the vector source's candidates, so a hidden vector rule (row 505) never reaches it. The rows differ from the table
above only in numbering: **515** (vector paint not exactly known, raster answered, but the region's witness is under `gf-agree-min`: kept at 700, the render's silence
means nothing), **532** and **535** (532 drops, 535 admits), **563** (a vector fill with a colour when a raster answered: 1000; 560 is without) and **574** (a tint
alone on a raster page, as 570 is in an image), and **599**, the default that drops. A vector rule is one `grl` row and is never split by the tracer; its witness is
the share of its length the tracer covers (`gf-agree-min`, 900, witnesses it). Tracer-only stretches of a group are the runs of elementary intervals that only the
tracer covers; slanted tracer rules are figure material and are not emitted. The alignment is the pairs of §9.9 (not through the 64 × 64 grid, which only speeds
them up). Health is `emon.health`'s `hl-regions` (kind `region`; D223), given the page's vector witness and tracer precision; the glyph test of row 532 reads the text
layer's word boxes. The ledger row of 513 is written by `gf-write` from the arena `gfa`. The `fx` rows of block 500 name a `ggp` row. Regions: a
`table-in-image` box is what its `in-image` rules and fills cover; figures are merged from visible image placements, OCR `bt-image` blocks and vector clusters
(on a 32 × 32 grid, at least `fig-min-paths` curves or polygons in a connected set of cells); tagged `Figure` and `Table` elements are not read (the table stage
verifies the tags itself, D225); `table-hint` is OCR's alone. Features 30 and 31 are written by `gf-fuse` for the OCR words of a page it made again.

---

## 10. Analysis

### 10.1 Lines, blocks, reading order

* **Lines** from fused words by baseline and direction; **blocks** by
  spacing, alignment and style continuity (leading jumps, indentation, font
  change, rules between).
* **Columns and reading order**: gutters first, then the elements that
  span them, then a cut tree whose leaves are column fragments, read
  column-major (§10.1.1). Priors, in order of trust when healthy: tagged-PDF
  order, content-stream order, then geometry. They are *compared*, and
  disagreement lowers block confidence.
* **Paragraphs continue** across column and page breaks and are joined into
  one (§10.1.2).
* **Meaningful line breaks** are kept (`linebreak`) where lines are
  consistently short and ragged in a way paragraphs are not: addresses,
  verse, signature blocks.
* **Order within the stage**, per page: lines (D104) → initials (§10.9: a
  local test, and before anything that measures indents or styles) →
  furniture candidates excluded (§10.2's recurrence and gutter line numbers)
  → columns (§10.1.1) → blocks → alignment (§10.10); then, document-wide,
  continuation (§10.1.2), style inventory and headings (§10.3).

#### 10.1.1 Columns (D111)

The column map is what reading order is decided over, and it is kept: it is
the page's `layout` (§3.7).

1. **Gutters.** Over the page's words (not lines: a producer that writes a
   row across two columns in one run, or a gutter narrower than D104's two
   sizes, gives lines that cross it), sweep line rows top to bottom and keep
   the x-intervals free of ink that persist for at least 3 consecutive rows
   on both sides and are at least `gutter-min` wide (the larger of 0.6 body
   size and 1.5 times the median word gap of the rows beside it): the tall,
   narrow maximal whitespace rectangles of the page. A vertical rule
   (`paths`, `rules`) with text on both sides for 3 rows is a gutter at any
   width. Rivers in justified text do not persist 3 rows at that width; a
   gutter holding only numbers at a regular pitch (line numbers in patents
   and pleadings) is furniture (`margin`) and is removed first.
2. **Split** every line that crosses a gutter.
3. **Spanning elements.** A line, figure, table or rule whose box crosses a
   gutter's x-interval inside the gutter's vertical extent ends the gutter
   there; titles, `column-span` figures and tables, and full-width
   footnote areas are found this way, not by kind.
4. **The cut tree.** Recursively: when a gutter runs the full height of the
   region, cut vertically (column-major); otherwise cut horizontally, but
   **only** at the top or bottom of a spanning element or at a full-width
   gap wider than 1.5 times the median paragraph gap. Horizontal gaps that
   happen to line up across columns (balanced columns with the same leading
   do this) are never cuts: that is classic XY-cut's reading-order failure.
   A three-column page with a figure across columns 1-2 cuts at the 2|3
   gutter first and at the figure inside the left part, so column 3 is not
   split.
5. **Order.** Vertical cuts in the page's dominant direction (left first for
   left-to-right, right first for right-to-left, by the direction of the
   majority of its lines, D104), horizontal cuts top first. Leaves are
   **column fragments**, numbered `band` (the index of the horizontal cut
   that contains them, top first) and `col` (reading order within it).
6. **Roles.** A fragment is `sidebar` when it is narrower than a third of its
   band and differs from the body in style or sits in a box or fill (a
   sidebar); `margin` when it lies outside the document's body text block
   (the union of body fragments over all pages) and holds short small-type
   blocks beside body paragraphs (margin notes). Sidebar and margin content
   becomes `note` blocks (`sidebar`, `margin`) placed after the body block
   being read where it begins, never inside a paragraph.
7. **Furniture** is taken out before gutters are sought (it would bridge
   them) and after it is recognised by recurrence (§10.2); a first-page
   masthead that recurs nowhere is ordinary content and a spanning element.
8. **Footnotes** are fragments like any other: per-column notes at the foot
   of their column, full-width notes as the last band. §10.8 classifies them;
   D073's reading position stands.
9. **Priors.** A healthy tagged tree's order wins and lowers the confidence of
   the blocks it moves; content-stream order breaks ties between two cut
   trees of nearly equal score. Newspaper jumps (`continued on page 4`) are
   not followed: the two parts stay two blocks.

**As built (P4.1, D163, D165).** The text layer's stage reads what steps 1-6
say with these departures, each forced by a document and held by a named
constant: a row counts for a gutter only where it has type of body class
(17/20 to 27/20 of the dominant size; a footnote or a chart label is not),
rows with words on one side only continue it, `gutter-min` is asked when a
gutter starts and 3/5 of a size after, `gutter-words` is 3 words in the run
of ink next to it, a gap of more than `hole` (3 sizes) between rows ends
every gutter; a row of four runs of ink or more, none of more than three
words, three sizes apart, is a spanning element; the cut tree's horizontal
cuts are the title cut (when there are side lines), the first full-width
gap of more than 5/2 sizes where a gutter divides the region, and the end
of an extent that is not inside another dividing gutter's extent; margin
notes and sidebars are the lines outside the body block, not a gutter's
fragments; `band` counts the horizontal cuts above a fragment and across its
x-range.

#### 10.1.2 Continuation across column and page breaks (D112)

Between the last body block of a fragment and the first body block of the
next one in reading order (next column, or the first column of the next
page, furniture skipped), a paragraph continues when the evidence says so.
Signals, integer points against a named threshold (`join-min`):

| Signal | Points |
| --- | --- |
| the same tagged `P` holds both (healthy tree) | decisive |
| last line ends in a hyphen after a letter | strong + |
| last line reaches the column's end edge (within the §10.10 tolerance) | medium + |
| last line is short (more than 20 % of the column width free) | strong − |
| last line ends without terminal punctuation | medium + |
| next line starts with a lowercase letter (cased scripts) | strong + |
| next line is indented where the document's paragraphs use first-line indents | strong − |
| next line starts with a list marker or is a heading candidate | strong − |
| a different style class | strong − |
| the next fragment's first glyph follows the last one in content-stream order | weak + |

Joined, it is **one paragraph** with one region per fragment; a page break
inside it is the inline `break` milestone (as today), a column break is
recorded only by its regions and the page `layout`; hyphenation at the break
follows §3.6 exactly. Blocks that sit between the two fragments in reading
order (a note, a figure, a table, a caption) move after the joined
paragraph, in their order. A join near the threshold lowers the paragraph's
confidence.

**As built (P4.1, D164).** `join-min` is 4; the points are +3 hyphen, +2 edge
(within 15 % of the width), +2 no terminal mark (a footnote mark after the
mark is not read), +3 lowercase, −3 indent, −3 list marker, −3 another size
or style, −3 short, +1 following in the stream, and two the table above does
not have: +1 the last line is at the bottom of its column set (the bottom of
the region the fragment's vertical cuts came from) and −3 the fragments are
not of one width (a table or figure is not a column). The tagged `P`
signal is not read yet (P4.2).

### 10.2 Running headers, footers and page numbers (D017)

The maintainer's rule was: drop them when they are only page numbering and
title, otherwise preserve. The principled form of that rule, which covers the
cases it does not name:

> **Drop what the output already says. Keep once what is the same on every
> page. Keep per page what varies with the page.**

Mechanics:

1. **Find candidates**: text in the top and bottom bands (and outer margins)
   that recurs across ≥ *k* pages at a consistent position, allowing
   odd/even alternation and a first-page difference. Recurrence is on a
   **template** — the text with digits and roman numerals abstracted — so
   `Page 3 of 10` and `Page 4 of 10` are one template.
2. **Decompose each template** into parts: page number (`3`, `iii`, `- 3 -`,
   `Page 3 of 10`, `3/10`, locale words when a hint exists), document title
   (matches metadata title or the first heading), current section/chapter
   title (matches the enclosing heading), date, and *other*.
3. **Decide**:
   * Page number → dropped *from the flow*, **kept as the page's `label`**
     (unless `/PageLabels` already gives one). Page labels are what citations
     use ("p. iv"), so this is information a scraper wants.
   * Document title, section title → **dropped**: the heading tree says them.
   * Everything else invariant (company name, "Confidential", a form number)
     → **one `furniture` node** with the template, the pages it covered, and
     its role (`header`/`footer`/`margin`). In HTML, one `<aside>` at the top.
   * Everything else varying (Bates numbers, "Filed 05/03/21 Page 3 of 40",
     per-page document IDs) → **per-page** values on the page table and on
     the `break` milestones.
4. Every drop goes to the ledger. Every decision is a setting that can be
   overridden (`furniture: drop|keep|policy`).

As built (P4.3, D173-D176): the stage runs BEFORE paragraphs and takes the
furniture's lines out of `lines`; its evidence is position, recurrence on a
template with a per-slot analysis (constant, follows the page, varies), isolation
from the body, the tagged tree weighed by its trust (D076), and, for text that
varies, style and position; a number is a page number only as a whole token
(`ACME-01` is an identifier, kept as the template `ACME-{n:02}`); a part of a
line that the body already says (the Info `/Title`, or a line of the body) is
dropped, anything else is kept; the page's `label` is `/PageLabels` when the
catalogue has it, else the number as printed.

### 10.3 Headings and their levels (D018)

Visual size alone is a trap: a document's chapter titles can be smaller than
its section titles, and the heading level is a property of the **document's
system**, not of one line. So emon builds the system first.

1. **Style inventory** (document-wide): cluster every line by
   (font family, size bucket, weight, italic, case pattern, colour, indent,
   spacing above/below). The cluster holding most characters is **body**.
2. **Candidate headings**: lines or short blocks that are standalone, short,
   followed by body text, rarely ending in terminal punctuation, and whose
   style differs from body — *or* that start with a numbering pattern.
   **Never** an initial (§10.9: its glyphs are not in the inventory either,
   so a 40 pt letter is not a style class), and never a block of one or two
   code points that sits beside or inside a paragraph's first lines. A
   letter divider in a glossary or index (`A`, then `Aardvark …` on the
   line below, full width) is a heading: it wraps nothing.
3. **Numbering schemes**: `1`, `1.2`, `1.2.3`, `I.`, `A.`, `a)`, `(i)`, `§ 4`,
   and — with a locale hint — keyword forms (`Chapter`, `Article`, `Kapitel`,
   `Artículo`, …). Hierarchical numbering (`2.3.1` → depth 3) is the
   strongest single level signal; sequences (`A`, `B`, `C` in one style) tie a
   style class to a level.
4. **External priors**, strongest first when healthy: tagged-PDF `H1`–`H6`;
   the PDF outline (bookmark depth, matched to heading text); a detected
   **table of contents** (lines with leaders and page numbers, indentation
   giving depth) — a TOC is the author telling you the hierarchy.
5. **Level assignment** is a small global optimisation over *style classes*,
   not lines: assign each heading class a level to minimise a penalty —
   numbering-depth mismatch (heavy), prior mismatch (heavy), a level skipped
   on descent (medium), a class appearing both above and below another in the
   nesting sequence (medium), prominence inversion (light: size and weight are
   the *weakest* signals, by design). Exhaustive for up to ~8 classes, greedy
   with repair beyond. Integer penalties; deterministic tie-breaks.
6. **Sections** are built from the levels: each heading opens a `section` that
   closes at the next heading of equal or higher rank.

### 10.4 Lists

Marker detection (bullets `•◦▪‣–-*` and dingbats, numbering patterns as in
headings), hanging-indent consistency, sequence continuity (a list resumed
after a page break or a figure continues its numbering), nesting by indent.
A numbered paragraph that is really a heading is disambiguated by the heading
analysis, which runs first.

As built (D229-D232). A marker is read from the characters, in no language: a
bullet or a dash standing alone (a strong bullet glued to its text), one to
three digits of any decimal script with `.` or `)`, the same in parentheses or
brackets, a letter (Latin, Greek, Cyrillic) or a roman numeral with `.` or `)`,
a circled digit. A bullet that is not text is read from the vector evidence: a
small filled or stroked circle or square just before (after, for a line that runs
right to left) the first word, on the x-height; its `marker` is the character it
stands for (`•` `◦` `▪`). A candidate is confirmed, never trusted alone: by a
neighbour of its own scheme and indent whose value is one off (or a few: a list
that skips), or for a bullet by a sibling, or by a hanging continuation line
under it; a lone `Z.` is never an item (an initial). A marker alone on its line
with its text on the line beside it (a text layer builds two lines out of a marker
and its text set in another font; the common case of a right to left list)
is joined to it. Nesting is by the x where an item's *text* starts, measured
inside its column (markers are often right aligned); items of one scheme whose
values go up are one list, across a page milestone or a column break (a milestone
or a note body between two items of a list that goes on stays inside the item
before it); a value that starts again is a new list; `start` is the first value
when it is not 1 and an item that skips has an explicit `value`.

### 10.5 Tables (D019)

**Profiles** — detected independently; the best-scoring interpretation wins:

| Profile | Evidence |
| --- | --- |
| `ruled` | full grid of rules (vector paths or traced lines) → lattice → cells |
| `partial` | horizontal rules only (booktabs), column boundaries from text alignment |
| `shaded` | alternating row fills or header fills, columns from alignment |
| `whitespace` | no rules: ≥ 2 columns of consistent alignment across ≥ 3 lines, gaps wider than word gaps, numeric-column regularity |
| `form` | key/value grids (label cells + value cells), often ruled |
| `struct` | tagged-PDF `Table` roles, verified against geometry (D225, below) |

**Partial tables (D557).** Producers rule what they like: a statement's row borders may stop under the third of five cells. Three things
make a `partial` candidate the table the page shows. (1) *Reach*: the rows' own baselines go on past the ends of the rules (and before their
starts), and the words there are followed while each starts within the table's own widest inner gap (not under 2 sizes, not over 12) of the
reach so far, as far as a vertical rule spanning three in five of the rows, and never past another table's horizontal rule on those rows;
at least three in five of the rows must have such a word, and the candidate is built again over the wider extent and kept if it scores
(prose in columns still refuses it). (2) *Header*: a tabular row above the chain's top rule, at most 4 sizes above the first row, whose
chunks line up (a left or a right edge within 4 pt) with the body's columns is the header, whatever its size, type and colour, and the rows
so taken are the header rows (`thead`) before any bold body row is. (3) *Not a header*: a bold leading row holding a formatted amount (a
currency sign or a decimal point) that the cell below in its column matches ('Opening Balance' over a column of balances) is a row of the table.

**Chains, tails and cuts (D559).** (1) *A chain of rules ends where what lies between two of them is not its own.* Horizontal rules of one extent
less than 300 pt apart chain into one candidate; the chain stops before a rule when the gap above it, wider than half as much again as the gap
before, holds a row in type 1.25 times the chain's rows' or more (a heading, a label), a row of two chunks or more that lines up with no other
row's columns (another grid's), or a band of white deeper than 3.5 sizes between a row and the one before it (the chain's last row included);
the rule after the gap begins a chain of its own, so two tables whose rules are near enough to chain are two. (2) *Rows under the last rule.* A
statement's closing balance and a summary's total have no border under them: up to three rows of two chunks or more under a chain's last rule that
line up with the columns of the rows above them (an edge within 4 pt), each at most 4 sizes and 1.6 times the rows' mean pitch under the row before
it, are rows of the table (the chain needs two rows of its own to have a pitch and columns). (3) *Vertical rules are column cuts.* In every profile
that finds its columns from text (`partial`, `shaded`, `whitespace`) a vertical rule inside the rows' extent that runs along three in five of the
rows (two at least) and through none of their words is a column boundary of its own where the whitespace has none, and the words either side of it
are cells of their own even when a chunk of the row joins them, in the rows it runs along (D563: a phrase in a row it does not run along stays one cell).

**What the profiles read (D218).** Rules and fills are the fused `grl` and
`gfl` of §9.9, never `paths`: a rule is a `grl` row of orientation `h` or `v`
(slanted rules are figure material), a fill a `gfl` row; a rule flagged
`artifact-pagination` that lies wholly in the page's top or bottom eighth is a running header's or footer's and is not read (D272: one
that does not is read, some producers tag a table's own rules so). A
table's `src` is the union of the sources of the rules and fills it used
(`vector`, `trace`, `ocr`), plus `struct` when the tags shaped it, plus `text`
or `ocr` for its words; its confidence is the profile score scaled by the mean
confidence of those rules.

**Tagged tables (D225).** A `Table` element on the page whose nesting check
holds (no table inside it, every `TR` under the table or its `THead`, `TBody`
or `TFoot`, every cell directly under a `TR`) is read, at any trust, into a
**tagged grid**: rows are its `TR`s in order (under `THead`, `TBody`, `TFoot`
or the table), cells its `TH`/`TD`s placed by `ColSpan`/`RowSpan` into the
first free slot (HTML's algorithm); a word belongs to the cell whose subtree
owns its first glyph (D149's ownership). The grid is then **checked against
the page**, each check per-mille (-1 when there is nothing to check):
`row-band` (pairs of non-empty cells of one row whose text boxes share a
vertical band), `col-band` (likewise for columns, horizontally), `order`
(cells of a row in reading direction, chance-corrected as D150), `cover`
(words inside the table's box that it owns, over the words inside its box),
`rules` (non-empty cells whose text no fused `h`/`v` rule of confidence ≥ 800
crosses) and `geo` (when a geometric candidate that `tb-select` kept overlaps it at IoU ≥ 500: the
share of the table's words whose tagged row and column map onto
the candidate's under the majority mapping: one-to-one, except that several ADJACENT candidate rows may be one tagged row, a cell of several lines (D324);
a table that needed that gives its candidate no head rows, spans or row headers from the tags, which assume one-to-one rows). As built (D302): the pairs of `row-band`, `order` and
`col-band` are adjacent ones (a non-empty cell and the next non-empty cell of its row, or of its column), and the box that `cover`
counts the words inside is the table's own, grown by the candidate's when there is one (a tagged table that leaves out a row
the page shows covers fewer of the words inside it). A tree under `tb-tag-trust` (300) is still read and its tables checked, for
the evidence dump's sake, but it can confirm nothing, but for a table that confirms itself (below): its tables are at best `partial` and no case below applies. **A table that
confirms itself** (D557) is a `confirmed` one with at least `tb-own-checks` (4) of the five checks of the grid and the page made and none of them under
`tb-own-floor` (900); it is read as a tree of trust `tb-tag-own` (700, at least the tree's own) would read it, whatever the tree's: `tg-trust` is an average over every element
of the tree and every glyph of the page (`Div`s and `Span`s, text outside the tree), and says little of a `Table` in it that the page bears out check by
check. The **score** is D150's shape
(weights 2, 2, 1, 2, 2, 3); the **verdict** is `confirmed` at ≥
`tb-struct-ok` (800), `contradicted` under `tb-struct-bad` (500), else
`partial`. Then, in `tb-select`:

1. *Confirmed, a geometric candidate agrees* (`geo` ≥ 900): the geometric
   cells (exact boxes) with the tags' head rows, row headers (`TH` with
   `Scope` `Row`) and header scope; spans: a ruled table keeps its lattice's
   (rules are seen; a tagged span across a closed edge sets
   `tag-span-conflict`), any other profile takes the tagged spans (no rule says
   where its spans end, D181).
2. *Confirmed, a geometric candidate disagrees*: the geometry decides the
   cell boundaries, always (tags never override disagreeing geometry: verify,
   not obey, D034); the verdict is `partial` and the table is flagged
   `struct-disagree`; the tags may contribute head rows, row headers and header
   scope (as in case 1).
3. *Confirmed, no geometric candidate*: emitted on the tags alone only when
   `tg-trust` ≥ `tb-tag-win` (700) AND the text layer has text in the tagged
   cells; a `struct` table (provenance `source: tags`) whose cells are the
   cells' text boxes grown to their row and column bands, scored 850 ×
   `tg-trust` / 1000, still subject to D180's refusals (prose tagged `Table`
   is a layout table and is refused: its verdict becomes `contradicted`, whatever the trust). Under 700, or with the cells empty of
   text, nothing is emitted.
4. *Partial*: the geometry decides; the tagged head is used when its row count
   equals the geometry's.
5. *Contradicted*: ignored, and never lowers a geometric candidate; the
   verdict and score stay in the table arena and the dump.
6. A **trusted tree that says "not a table"** (`tg-trust` ≥
   `tb-tag-veto-trust` (800) and every word of a `whitespace` candidate owned
   by `P`, `L`, `LI` or `H*` elements) lowers that candidate by `tb-tag-veto`
   (150). Only `whitespace`: it alone has no rule or fill to vouch for it.
7. **OCR's table blocks** break ties only: of two overlapping candidates
   within `tb-tie` (25) of each other, the one whose box has the higher IoU
   with a `table-hint` region from OCR wins.

**Mixed pages.** A table drawn in an image of a born-digital page is found by
the same profiles over the fused rules inside it (`in-image`, rule 530) and
the OCR words admitted by rule 610; its `src` says `trace` and `ocr`.

**Cells**: text assigned to cells by containment; spanning cells from
missing internal rules (ruled) or from text crossing column boundaries
(whitespace); multi-line cells from row geometry, not line count. **Header
rows** from style (bold, fill, rule below), position, and repetition.
Numeric columns detected locale-independently (digits with separators);
the decimal separator is chosen from the locale hint when given, otherwise
inferred per column and never rewritten.

**Tables across pages** — the second fragment of a table is recognised when
the page's first body block (after furniture) is a table and the previous
page's last body block was a table, and:

* **with a recurring header**: its first row(s) equal the first fragment's
  header row(s) (text-normalized) → merge, drop the repeated rows into the
  ledger (`duplicate-header-row`), mark the merged table `continued`;
* **without a header**: column count and column boundaries agree within
  tolerance (after page-margin normalization), with no heading or caption
  between → merge;
* a **"continued"** marker (`(continued)`, `Table 3 (cont.)`, locale forms)
  is supporting evidence and is itself dropped to the ledger;
* a **row split across the break** (first row of the new fragment has empty
  key cells and continues the text of the last row) → merged into one row,
  flagged `reconstructed`.

### 10.6 Figures and graphics placeholders (D020)

Non-text regions are classified, and each becomes an **empty `figure`** with
a `kind` and a confidence — no pixels by default (a scraper does not want
them; an embedder who does has the region and can crop, or turns on
`assets`, §10.11):

| `kind` | Signals |
| --- | --- |
| `chart` | axes (two perpendicular long rules with tick marks), numeric labels along them, bar/line/area/pie primitives, legend-like swatch+label pairs |
| `diagram` | boxes/ellipses joined by connectors or arrows, short labels inside shapes |
| `photo` | continuous-tone image (high colour entropy, few flat regions) |
| `logo` | small image or vector cluster, often in the header band or repeated across pages, often colour, often with a short brand word |
| `map` | large, dense, many small labels, curved linework |
| `illustration` | line art not fitting the above |
| `screenshot` | image with UI-like rectangular structure and embedded text |
| `qr` / `barcode` | finder patterns / parallel bar runs (decoding parked) |
| `unknown` | graphic of any other sort |

**As built (P8.1; D376-D380).** Every output has the placeholders, at every level of `assets` (which decides only whether they
get pixels, §10.11). The candidate regions are the image placements that passed P3.6's checks, the vector clusters of
P8.5 (paths within 2 pt of one another), lone shaded or patterned fills, and P7.G's `figure` regions, each under 800 per
mille of its page and with both sides of its visible box at least 18 pt (a region that is mostly a `table`'s is not one, nor is a
vector region at least half of whose lines of text a table has claimed, D557).
Each is measured and given a `kind` and a confidence (`conf`, per mille), or is no figure: a region with two or more lines
of prose in it (a text box, a sidebar, a background), rings drawn one in another (a seal) and a lattice of rules with no axes
are not. A region with no caption is a figure only when its confidence is at least 500; a captioned region is one always (kind
`unknown` at 400 when nothing is clear). What decides a kind is language-independent: two long perpendicular rules that meet,
with ticks, bars, a series or numbers by them (`chart`); boxes with words in them joined by connectors or arrow heads
(`diagram`); a small coloured drawing in the header or footer band, or a small flat raster there, or a small drawing that recurs on
other pages (`logo`; **recurrence**, P8.4 below); a raster that needed many bits a pixel, or a shaded fill with no words in it (`photo`);
a run of thin parallel bars, or a two-colour raster far wider than high (`barcode`); many tiny squares in a square, or a
square two-colour raster (`qr`); a large, dense, curved drawing with many small words (`map`); curved line art (`illustration`);
a framed panel with a few short words, or a large flat raster (`screenshot`). A raster's bits a pixel are the stream's length
over its pixels, read from the image dictionary: no pixel is decoded. The figure is placed among the document's and the sections'
blocks by its top edge, or just before its caption when it has one just below it (a column of text may stand between them), so
that the caption finds it as its object. A short one-line paragraph on a figure or within 24 pt of it (an axis tick, a word in
a box, a logo's name) is not body text: its text is the figure's `labels`, in reading order. `alt` is not yet read from the PDF.

**Logos that recur (P8.4; D399-D403).** A logo is often the one thing a letterhead or a report's running head repeats, so what
the same drawing does on other pages is evidence (a small region that has no band to stand in is a logo when it recurs, and a logo
that recurs is surer: +100 per mille for one other page, +160 for two, +220 for three or more; pages, not placements, so a
drawing used twice on a page is no recurrence). Two placements are one drawing when they are the same image object, or images of
the same pixel size whose streams have the same digest (the stream's bytes as the file holds them; all of a stream of 4 KiB or
less, a stride through a longer one) — wherever they stand — or vector art with the same number of paths, the same 4 x 4 occupancy
of their centres (within one cell), a size within 75 to 133 per mille of the other's (so a scale within that tolerance is
ignored), and its centre at the same place of the page (within 4 percent of the page and an eighth of its size). **Every
occurrence is a `figure` of its own**: one placeholder for each page it is on, in its place in that page's reading order (a
reader of page 3 alone still finds the logo that is there); what is one to a document is the *asset* (§10.11: one file, many
uses). A logo in a header or footer band stays a `figure`: running **furniture is text** (§10.2) and its decisions do not read
a figure, so a band that is a logo and a page number or a title is dropped (or kept as a `furniture` aside) as if the logo were
not there, and the logo's placeholder is not dropped with it. No pixel of a language is read.

Captions are linked by label pattern (`Figure 3`, `Fig. 3`, `Chart`,
`Exhibit`, locale forms) and proximity. Text inside a figure (axis labels,
diagram labels) is **not** body text; it may be kept as the figure's
`labels` (setting `figure-text: omit|labels`, default `labels` — a chart's
axis titles are often exactly what a scraper wanted). An image the PDF gives
alt text for (`/Alt` in the structure tree) keeps it as `alt`. The labels stay in reading order (a chart's axis title, its
tick labels), each its own value in JSON and its own `<span data-emon="label">` in HTML, with one space between two spans so that the figure's
text reads as tokens (`Units (thousands) 220 165 110 55 0 Q1 Q2 Q3 Q4`) and not as one run (D559).

The candidate regions are fusion's `figure` regions (§9.9, D228): image
placements, vector clusters, the tracer's large components, OCR's image
blocks and the tagged `Figure` elements, merged; classification reads the
fused rules and fills inside them (a chart's axes are two perpendicular
`grl` rules).

### 10.7 Seals, stamps and signatures (D016)

* **Digital signatures** (PDF `/Sig` fields): exact — signer name, date,
  reason, location. `kind: digital-signature`.
* **Seals and stamps**: tracer finds closed near-circular, elliptical or
  double-bordered rectangular contours, usually in a non-black ink layer,
  usually overlapping body text or a signature line. Text inside is read by
  targeted OCR: rectangular stamps directly; **circular seals by polar
  unwarp** (a kin transform of the seal's annulus into a strip) so text along
  an arc becomes a line OCR can read. The recovered text fills `text`; an
  organisation name / date pattern fills `name` / `date` when found.
* **Handwritten signatures and initials**: stroke-like components (thin,
  high curvature, cursive connectivity, low OCR plausibility) near signature
  cues — a rule with a label beneath (`Signature`, `Signed`, locale forms), a
  "By:" line, a signature block's typed name. The typed name nearby becomes
  `name` when the association is confident.
* In all cases OCR words inside the region are **removed from the body** and
  attached to the seal (§9.5.5).

**As built (P7.7).** Candidates, their words and the node are `emon.sealmark`'s (§9.5, D314-D318): seal, stamp and signature nodes with `subkind`, `color`
(12 hue buckets, black, gray, white) and `text` (the words in the text layer's order, flagged `low-conf`; none for a signature), in reading order; the
candidates are the layer's `sl` arena (§9.7). A signature's typed name, handwriting in pixels, initials and digital signatures are P8.3's (below).

**As built (P8.2, D412-D418): seals and stamps from pixels, `name` and `date`.** *Fields* (`emon.sealfield`, language independent): a seal's `name` is the first
phrase of its text between the separators a ring puts between its phrases (a bullet, a star, a bar, a long dash, a line end, a hyphen with a space beside it or between two words of five letters or more) that has
letters and no digit and is long enough for its script (a seal: one word of four letters or two Han, Kana or Hangul letters; a stamp: two words of five letters), as read;
its `date` is the first digits-separator-digits shape (any decimal digit of any script; year first; day and month in the order the numbers settle or the locale hint says,
else as it stands), the CJK year-month-day marks, or a day, a word of letters and a year; written `YYYY-MM-DD` when no reading disputes it, else as it stands. Both are read
off the node's `text` when it is written (`sm-write`), whoever made the text, so vector seals have them too (a signature has neither). `sm-write` is called without the
document's locale, so a numeric date that either order would read stays as written there (`sf-order` is the hint's reading, used where a unit has the locale).
*Candidates* (`emon.sealpx`): the tracer's `ring`, `circle`, `ellipse` and `curve` primitives of at least 8 mm radius and axes within 15 per cent on a `colour` layer
(on the body layer only a double ring), and its outlined `rect` primitives of the `colour` layers (a stamp), at most four a page, a ring concentric with another one candidate.
*The ink key*: a pixel is the seal's ink by its place on the paper-to-ink colour line and, for a coloured ink, by having a tenth of the chroma that place asks (black text,
the gray of a watermark and the gray edge of a letter are paper). *The polar unwarp*: the annulus between 0.55 and 1.02 of the ring's radius is laid out as a strip of
1280 x 112 pixels (centre refined within three steps by the straightness of the strip's rows), begun at the widest gap of the ring's ink, its rows that are thin
lines painted out, the outside at the top (a ring written clockwise stands upright); the disc inside 0.62 of the radius is a 336-pixel square; a stamp is cut out along
its own axes (the tracer's `rot`) at least 1.5 times and at least 150 pixels high with its border painted out. Each is a kin-made view (`vk-polar` with the `nonaffine` flag
and its parameters in the linear-part cells, or `vk-cropped`), read as a targeted `op-seal-strip` request at `psm` 6 (the request names the view's pixel hash). *Evidence*:
the answers are OCR runs of pass `seal-strip` (region: the seal's box in the raster's pixels) whose words are laid back on the ring (box, baseline and `rot`), and one `cg`
group a seal, flagged `in-seal`, `ocr-only` and `seal-px` (and `seal-stamp`), its `rot` cell the colour code, holds the words: `sm-find` makes its `sl` row (flag 128: the
cell 15 is the colour code), its words never reach the body (rules 401, 402), and a candidate found in the vector paths of the same box is not written twice. A seal on a
scanned PDF page makes the document a fused one (evidence version 2) so that `sm-write` writes its node; the page's chosen lines that lie wholly inside a kept seal are not
body text (`sx-drop-lines`; also a line with 60 per cent of its characters inside whose mean confidence is under 600 per mille). A pixel-read seal's text keeps the words the engine was sure of (`sm-px-keep`: not
a noisy mixed-case or punctuation-ridden misread), and leaves the ring's lone separators out of the node's `text` (the name field is cut by them first). A stamp is also a curve or ellipse of a colour layer
whose sides differ by more than 15 per cent (a rotated outline). P12.8 (D644) wires the same pixel stages for PNG, JPEG and TIFF frames, whose selected OCR body needs no text-layer arbitration. Not built: a seal the layer clustering did not separate from the
body (a faint stamp on a tinted ground), the second strip for text written with its tops inward, the locale hint at `sm-write`.

**As built (P8.3, D426-D433): handwritten signatures, initials and digital signatures.** *Digital signatures* (`emon.sigmark`, `emon.pdfannot`): every `/Sig` field that has a widget on a page is a `seal` node of subkind
`digital-signature`, signed or not; P3.8's reader appends one synthetic `an` row a field (subtype `DigitalSignature`; the Engines hand the document unit `an` and not `sg`), and `sm-find` makes its `sl` row, so the node exists with or
without OCR. Its `name` is the signer's /Name, else the common name of the first certificate's subject in the /Contents bytes (the second `commonName` of the DER: a certificate lists its issuer first; read as bytes);
its `date` is the /M as ISO 8601 (`YYYY-MM-DD`, with `Thh:mm:ss` and the zone when the /M had the time of day), `reason`, `location`, and `signed` (the field's dictionary has a /Contents; an unsigned field says `false` and nothing
else). **NOTHING IS VERIFIED**: no digest is computed, no certificate is checked, no chain, no revocation, and the byte range is reported by the dump only. The node is read after the label whose line starts inside its height (a
field's own row). *Handwritten signatures and initials*: the page's open stroked curves of a pen (at most 2.5 pt wide), merged where they lie within 8 pt, are a cluster (`sgc`); a cluster is judged for its tortuosity (the loops
of a pen: the strokes' control polygons over their boxes' diagonals, squared, at least 1.8), refused when OCR reads two words of it (confidence at least 700), and is INITIALS when it is at most 28 x 24 pt and a SIGNATURE when it
is wider and at least 28 pt and wider than 1.5 times its height. The CUES are shapes (language independent): a rule it stands on (a straight stroke or thin rectangle of 60 pt to four fifths of the page, passing through the lower
part of the cluster or just under it, half the cluster's width over it), a colon token to its left (a word of at most seven characters that ends in a colon), a label under the rule, and P7.7's English hint words (the locale's hint,
kept). Initials are also taken with no cue when the same mark recurs at the same place of a margin on another page, or on two pages anywhere. The typed `name` is the line of 2 to 5 name-like words the strokes are drawn over, else the
first under the rule (a one-word label is not a name). Raster: the tracer's rules of the page and the first pass OCR (`emon.sigpx`): the zone above a rule, its ink not under a word OCR was sure of and not on the rule, measured as
a pen's (stroke width, loops, coverage); the result is a correspondence group with no word and the flags `seal-sig` or `seal-init`, which `sm-raster` turns into the `sl` row; a scan's lines made of the strokes are not the page's text.
The words OCR reads in a signature are the strokes' and are never body text (rules 401 and 402; on a scan `sp-drop-lines`), and the picture of a raster signature is no `figure` (§10.6: its words
stay the body's). A signature, initials or a digital signature that overlaps no block is written after the last block of its page above it (a digital signature field: above its bottom). Not built: Ink annotations, a typed name before the strokes, initials in a margin of a raster page with
no rule, signatures that are filled shapes, certificates verified (by decision).

### 10.8 Footnotes, captions, links

Footnotes: a superscript or bracketed marker in body text matched to a
small-font block in the page's bottom band starting with the same marker →
`note` (`footnote`) + `ref`. Endnotes: the same matching against a trailing
"Notes" section. Captions: §10.6 and tables. Links: from annotations (§8.1),
plus bare URLs and e-mail addresses in text (a conservative recogniser; not a
guess at every dotted string).

As built (D233-D235). A caption starts with a label: a keyword of a table of
caption words (English and a dozen other languages until P4.8's locale tables
replace it) and a number (`1`, `2.3`, `A.1`, `4b`, `IV`), and then a full stop, a
colon, a dash, or the line's own style (smaller than the body, or italic or bold
where the body is not): `Figure 2 shows how` set as body text is a sentence. The
`label` is the keyword and the number as printed, a dot after the number kept
and a colon or dash left out (D073.2); the rest is the caption's text. A caption
is the first child of the `table` or `figure` next to it (above a table, below a
figure; the label's kind says which can be its object), its `target` the object;
a figure caption with no figure beside it is the evidence of a figure nothing
else found and is wrapped in an empty `figure` of kind `unknown`. A footnote
reference is a run of superscript glyphs (smaller than the line and raised, or
the Unicode superscript digits) after a letter or punctuation, followed by
nothing but punctuation: `m²` and `10⁻⁶` are not notes. It is believed only when
a note body with the same marker stands where a footnote stands (the lower half
of the page of the reference or the page after it, set smaller than the body or
marked in superscript) or, for an endnote, later in the document (a
superscript marker at the start of the line, or a plain number among at least
two others after a heading that says `Notes`; or, when the notes print no marker
at all, as many notes under that heading as there are references left over). A
`ref`'s `target` is the next note body with its marker that nothing else
claims (D073.4). A bare link is `https://`, `http://`, `ftp://`, `mailto:`, `www.`
and a host with a top-level domain, or `local@domain.tld`; what trails it
(sentence punctuation, a closing bracket it did not open) is not part of it.

Inline styles and links as built (D273-D277). A `span` is written where bold, italic, monospace, small capitals, subscript or
superscript differs from the paragraph's own style (a paragraph set wholly bold has no span); the style is the glyph's font's (flags,
name, /FontWeight, /ItalicAngle, a stem thicker than the thinnest of its family's) and, for sub- and superscript, its place against the
line's baseline. A `link` is the text under a link annotation: a /URI (only http, https, ftp, ftps, mailto, tel, or a reference with no
scheme) is its `href`, a /GoTo or named destination its `target`, the block on the destination's page at its point. Annotations win: bare
URLs are looked for only in text that is not under a link. Table cells have no inline styles yet.

### 10.9 Initials: drop caps and their kin (D108, D109)

A paragraph may open with a letter (or a word) printed far larger than its
body: a **drop** initial sinking into the first N lines, which wrap around
it; a **raised** initial standing on the first baseline and rising above it;
a **margin** initial hanging outside the column beside the first lines. It
is typography, not structure: the letter is the first letter of the text.

**Detection** runs per page right after lines (§10.1), before blocks,
indents or styles are measured, and is local:

1. **Candidates**: a run of 1-3 code points (a letter or digit, with the
   opening or closing punctuation CSS `::first-letter` would take: `“N`),
   or a whole first word, whose size is at least `initial-min` (2.0) times
   the median size of the lines beside it; or a non-text ink region of that
   size (a vector path cluster, an image) in the same position (below).
2. **Drop**: the candidate's cap top (its baseline minus the font's
   `/CapHeight`, else 0.7 of its size; the tracer's ink box on raster) is
   within half a body size of the first line's cap top; its ink bottom lies
   within line N's vertical band (N >= 2); lines 1..N start after its end
   edge within 1.5 body sizes, and line N+1 starts back at its start edge.
   Baselines need not agree: a CSS float sits between two baselines (Chrome's
   `printToPDF` puts a three-line `O` 6.75 pt above line 3's baseline).
3. **Raised**: the candidate shares the first line's baseline (D104 already
   puts it in the first word), and line 2 starts at its start edge.
4. **Margin**: as drop or raised, but its end edge is before the column's
   start edge and the lines are not indented.
5. **First word**: an enlarged whole word (`IT`) is an initial of that word;
   a small-caps lead-in after an initial (`T` + `HE BUREAU`) is an ordinary
   `smallcaps` span.
6. **Direction**: in a right-to-left paragraph mirror every edge.
7. **Not an initial**: a large letter with nothing wrapping it or beside it
   (a letter divider), a logo letter, a big page number, a letter inside a
   figure. Stream order is no help: Chrome paints every float of a page
   before its text.

**Glyphs that are not text.** An initial drawn as vector outlines, as an
image, or in a Type 3 font with no usable Unicode has the geometry above
and no code point. The letter is recovered, strongest first, from the
tagged tree (`/ActualText`, `/Alt` of its marked content), from a targeted
OCR pass on the region as a single character (pass kind `initial`, the
paragraph's script; Tesseract's own drop-cap flag, `osyms` `os` bit, is
corroboration), then from the tracer's components. With no candidate above
the threshold the inline holds U+FFFD, flagged `low-conf`: a character was
there and emon says so. A decorated initial's ornament is not a `figure`.

**Joining.** The initial's text is prepended to the first line, with no
space: `O` + `nce upon` → `Once upon`. The page cannot say whether a
one-letter initial is a word of its own: Chrome writes `A long time` and
`Once upon` with the same 4.5 pt gap and no space glyph. So: a space only
when a healthy tagged tree or `/ActualText` holds one; otherwise joined, and
flagged `low-conf` when the letter is a word in its own right in the
locale's one-letter-word table (`A`, `I`, `O` for English; without a
locale, the union of the tables emon ships). Joining is the better default
(`It`, `In`, `At`, `As` outnumber `I` and `A` as openings); a per-locale
lexicon that could decide it is an open question (D108).

**Effects on the rest of analysis.** For indents, lines 1..N start at the
initial's start edge; the paragraph-gap rule measures from line 1, not from
a raised initial's top; the initial's box is part of the paragraph's region;
its glyphs are left out of the style inventory, the body-size statistics and
feature column 18 of its line.

**As built (P4.10, D183, D185).** `kin/initial.kin` runs right after the lines are copied for the column stage and
before anything measures them, on that copy: a drop or margin initial's line leaves the page and lines 1..N of a drop start at
its start edge; a raised initial's line takes the box of its other glyphs and its first word does not vote on the line's size or
style. *Candidates* are one-word lines of at most 12 code points (a letter or digit among them) at least `initial-min` (2.0)
times the page's median line size (a pre-filter) and the size of line 1. *Cap tops* are the font's `/CapHeight` when `fonts` has
one (the reader does not yet write it: -1), else 0.7 of the size for a cased script, 0.6 for the other caseless scripts
(Hebrew, Arabic, Indic, Thai: their letters stand lower than a capital) and 0.88 for CJK (an ideograph has none); for the
caseless scripts the candidate's cap top may lie up to 0.15 of its size above line 1's. A drop has N >= 2 indented lines and
line N+1 back at the start edge (within half a size) or, when no line follows in the column, nothing else beside the initial; a
margin initial has lines 1..N and at least one more at one start edge, beyond its end edge. A raised initial is the leading
glyphs (at most three, or the whole word) of a line's first word at least 2.0 times the size of the glyphs after them, on the
line's baseline, with line 2 at the same start edge; a line with one word is not looked at. The join is `0` (joined, sure),
`1` (joined, `low-conf`) or `2` (a space): a one-letter initial that is a word in the locale's table (the union without one;
`A I O` for `en`, tables for `de fr es pt it nl`) is flagged; with `en` the first word after it decides where a list knows it
(`A` + `long` is `A long`, `I` + `t` is `It`, sure); a whole enlarged first word (`IT`) is followed by a space unless its script
is written without them; `“N` is joined. The `initial` inline is `src {text, infer}`, `conf` 800 (500 and `low-conf` when the
join is flagged); a paragraph on one page has its own `text` as well as the inlines (equal), on several only the inlines'.

### 10.10 Alignment (D110)

Per block, from its lines against the **box of the fragment it sits in**
(the column fragment of §10.1.1; a cell's box for a cell), never its own
bounding box (against which everything is justified). Let *t* be a quarter
of the body size; for each line, the start gap *S* and end gap *E* in the
writing direction; a first-line indent is removed from line 1's *S*; the
last line is excluded from the justify and ragged tests:

| Value | Test |
| --- | --- |
| `justify` | ≥ 2 non-last lines, every one with *S* ≤ *t* and *E* ≤ *t*, the last line's *S* ≤ *t* |
| `start` | every *S* ≤ *t*, and *E* varies by more than 2*t* over the non-last lines, or the block is ragged |
| `end` | every *E* ≤ *t* and *S* varies by more than 2*t* |
| `center` | \|*S* − *E*\| ≤ *t* on every line, and *S* varies (or a single line with *S*, *E* ≥ 4*t*) |

A single-line block can only be `center` or `end` from its own geometry; a
single flush-start line is `start` only when its style class's
paragraphs are, and at lower confidence. Values are **logical**:
`start`/`end` mean left/right in a left-to-right block and the reverse in a
right-to-left one (its base direction, UAX #9 P2-P3 over its text).
Confidence rises with the lines that pass and the margin by which they
pass; `align` is written only at or above `align-min` (setting). **Table
cells** are decided per table column over all its body cells together (the
column's text extent is its box): numeric columns are usually `end`, and a
decimal-aligned column whose end edges disagree gets none (there is no
`decimal` value: the numbers read the same). Headings and captions are
tested like paragraphs.

**As built (P4.11, D184).** `kin/align.kin`: the caller makes, for every line of a block, its start and end gaps against
the box of the fragment it sits in (`cf`'s box, each from the line's own side, so a right-to-left line is measured from the
right and `start`/`end` are logical), its size and style bits, and closes the block with a marker; `al-pass` decides at the
end of the stage. The tests are the table's, with *t* a quarter of the first line's size, a first-line indent taken out of line
1's S when every other line starts at the edge, and `justify` needing three lines (two non-last and the last). The confidence
is 400 + 100 for each line after the first that agrees (at most four) + up to 200 for the margin + 100 where the ragged edge is
plain (a spread of more than 2*t*); `align-min` is 800. A single line is `center` (S and E at least 4*t*, within *t*) or `end`
(E within *t*, S at least 8*t*: a one-line paragraph that fills its row and has an indent is not right-aligned); a single
flush-start line is the second pass's: its **style class** (size within a twentieth, the style bits) decides, the blocks of two
lines or more of the class all `start` making it `start`, any other value leaving it with none, and a class with no such block
(a heading, a label: nothing is justified in one line) making it `start`. The pass is over the stream of blocks, so a later stage
that retypes a paragraph (a heading, a caption) is judged by the same rules. Table cells are P5.3's.

### 10.11 Assets: opt-in pixels for figures and seals (D117)

A crawler wants placeholders (§10.6). A caller converting for *reading* —
PDF → EPUB, an archive viewer — wants the pictures. The setting `assets`
lets it ask. Off, nothing in this section runs and every output is
byte-identical to a run without it.

| `assets` | Which nodes get pixels |
| --- | --- |
| `none` (default) | none |
| `images` | a `figure` or `seal` whose content **is one image the document embeds** gets that image: its original bytes where a browser shows them as the PDF did, else its pixels as PNG. No region detection decides the pixels, only which image. |
| `figures` | as `images`, plus **every other** `figure`/`seal`: cut from the image it lies in (a scan, a raster input, a large composite) or rendered by the host `Renderer` (vector art, charts, diagrams, composites, anything drawn over an image) |

Settings that go with it (named, defaulted, in the header when `assets` is
on; §7.4): `asset-dpi` (render crops; default 150, 36..600),
`asset-pixels` (per asset; default 4,194,304 = 2048²), `asset-bytes` (per
encoded asset; default 4 MiB), `assets-bytes` (per document; default
32 MiB), `asset-signatures` (`off`/`on`, default `off`: a handwritten
signature's pixels are a forgery kit, and a crawl should not spread them by
accident).

**Which source, per node** (`emon.asset`, in node-id order). Candidates are
the node's page's `images` rows (§8.4.3) that passed P3.6's checks and
overlap its region. Thresholds are named constants of `emon.asset`
(`asset-cover` 950 ‰, `asset-whole` 980 ‰, `asset-overlay` 20 ‰), not
settings.

1. `seal` of subkind `signature`/`initials` with `asset-signatures` off →
   `noasset: "withheld"`; `digital-signature` has no pixels and no field.
2. **Whole image** — exactly one candidate, its visible box (box ∩ clip
   box) covers ≥ `asset-cover` of the region and the region ≥
   `asset-cover` of it → `embedded` or `decoded`, below. At `figures` it is
   `rendered` instead when what the reader saw is not the image alone:
   paint (paths, shadings, images, visible text) drawn over it covering more
   than `asset-overlay` of it, a clip that is not a rectangle, or a
   placement that is not one of the eight rectilinear orientations. At
   `images` the image is kept and flagged (`overlaid`, `clip-bbox`,
   `transformed`).
3. **Part of an image** — the region lies inside one candidate's visible box
   (≥ `asset-cover` of the region) but is a small part of it: at `figures`,
   `cropped` from that image's pixels (this is the scanned page and the
   raster input: their page image is the candidate); at `images`,
   `noasset: "part"`.
4. **Anything else** (no image, several, vector art): at `figures`,
   `rendered`; at `images`, `noasset: "vector"` or `"composite"`.

**Origins** — every asset has one:

* **`embedded`: the document's own bytes, passed through.** Only a
  `DCTDecode` image (after kin's non-image filters and decryption) whose
  JPEG a browser shows as the PDF painted it: baseline or progressive
  Huffman (no arithmetic coding), 1 or 3 components, colour space
  `DeviceGray`, `DeviceRGB`, `CalGray`/`CalRGB` or `ICCBased` with `/N` 1 or
  3 (the PDF's profile is not carried: flag `icc`), 8 bits, no `/Decode` but
  the default, no `/SMask`, no `/Mask`, not `/ImageMask`, no EXIF orientation
  other than 1 (browsers apply it and PDF viewers do not), its SOF size equal
  to `/Width`/`/Height`, whole (visible ≥ `asset-whole` of it), upright, and
  within `asset-pixels`. kin checks this by walking the JPEG's markers; the
  bytes are never decoded. JPEG 2000, JBIG2 and CCITT never pass through (no
  browser or EPUB reader can be relied on to show them).
* **`decoded`: the image's pixels, as PNG.** Samples come from kin (raw,
  Flate, LZW, RunLength, with predictors) or from the host (`decode-img`,
  §7.3: DCT that cannot pass through, JPX, JBIG2 with its globals, CCITT
  with its parameters — the host's pixels are the codestream's, and they are
  transcribed). kin then applies, in integers and in this order: unpacking
  (1, 2, 4, 8, 16 bits; 16 → 8 as `(v·255 + 32767) / 65535`); `/Decode`;
  the colour space (Gray, RGB; CMYK by ISO 32000's naive formula `r = 255 −
  min(255, c + k)`; `Indexed` over those; `ICCBased` by `/N`, flag `icc`;
  `Separation`, `DeviceN`, `Lab` and a 4-component DCT with a non-default
  `/Decode` are `noasset: "colorspace"` at `images` and `rendered` at
  `figures`); masks into an alpha channel (`/ImageMask` as a stencil in the
  fill colour; `/Mask` colour-key ranges; `/Mask` stencil; `/SMask` with
  `/Matte` un-premultiplied, `c = m + (c' − m)·255 / α`, 0 where α is 0); the
  placement's orientation (one of the eight rectilinear ones, exact); and,
  when the visible part is under `asset-whole` of the image, a cut to the
  clip's box mapped into image pixels (floor at the low edge, ceiling at the
  high). The result is written as PNG (below). A skewed or rotated image at
  `images` is written unrotated, flag `transformed`.
* **`cropped`: a rectangle of an image's pixels.** The region, mapped into
  the image's pixels (for a raster input or a scan, through its view's
  cpt → px transform, §8.4.2), floor/ceiling, exact samples: **no
  resampling and no deskew** (a crop records what was scanned). Quarter
  turns the page was given (orientation) are applied.
* **`rendered`: the host `Renderer`**, through a `crop` request (§7.3): layer
  **with text**, because a chart's axis labels are part of the picture — the
  node's `labels` are a text *copy* for search and accessibility, not a
  replacement, and a without-text render would also keep text drawn as
  outlines, so it could not be consistent anyway. The pixel rectangle is the
  region padded by 2 pt (clipped to the page), at `d = asset-dpi`: `px =
  floor(x0·d / 7200)`, `px1 = ceil(x1·d / 7200)`, likewise y, `pw = px1 −
  px`. While `pw·ph > asset-pixels`, `d ← d·3/4` (integer) and recompute; below
  36 dpi it is `noasset: "too-large"`. When body text (not the node's own
  labels) lies inside the rectangle the asset is kept and flagged
  `text-overlap`. Renders are the host's opinion: planned, fulfilled and
  transcribed like every render (D008).

**The canonical PNG.** Every asset that is not `embedded` is written by
kin's own encoder (`emon.png` over `emon.deflate`), so that its bytes are a
function of (input, transcript, settings) on every target and its SHA-256
can be in the JSON: colour types 0 (gray, and 1-bit gray for bilevel
images), 2 (RGB), 3 (palette, for an `Indexed` image with no alpha), 4 and 6
(with alpha); chunks `IHDR`, `PLTE`/`tRNS` for a palette, `pHYs` when the
resolution is known, `IDAT`s of at most 65,536 bytes, `IEND`, and nothing
else (no `tIME`, no text, no gamma). Each row's filter is the one of the
five with the smallest sum of absolute signed bytes, ties to the lower
filter, filter 0 for palette and sub-byte images. The zlib stream: header
`78 01`, a greedy LZ77 with one step of lazy matching over a 32 KiB window,
3-byte hash, chain limit 32, matches 3..258, a block every 16,384 symbols,
each block stored, fixed or dynamic by fewest bits (ties in that order),
dynamic lengths by package-merge limited to 15 (7 for the code-length code),
ties by symbol, Adler-32. The encoder streams a row at a time, so its
working set is the window and its tables (under 1 MiB), not the image.
The details that the sentence leaves open are fixed in `kin/deflate.kin`
and `kin/png.kin` (D124): hash `((b0<<10) ^ (b1<<5) ^ b2) & 32767`;
nearest candidate first, a longer match replaces the best only when
strictly longer; "one step of lazy" is: having a match at *p*, look at
*p*+1, and when that match is strictly longer emit *p* as a literal and
take it, with no further look; every position is hashed once, in order,
before the next is searched (the last two bytes of the stream never are);
stored is a candidate only for a block covering at most 32,768 input
bytes; in package-merge a leaf precedes a package of equal weight; fewer
than two used symbols get length-1 codes for symbol 0 (or 1) and the used
one; the literal/length and distance lengths are one run-length sequence
(zeros by 18 then 17, a value once and then 16, the rest literal); an
empty final block closes a stream whose symbols divide into blocks
exactly. The output does not depend on how the rows or calls split the
input. Changing any of this changes every committed asset hash, and is a
reviewed change like any output change.

**Admission, in node-id order.** An asset whose bytes equal an earlier one's
(same SHA-256 and length) is that asset, with one more use — a logo on every
page is one file. A new asset larger than `asset-bytes` is `noasset:
"too-large"`; decoded and cropped pixels larger than `asset-pixels` are first
reduced by the smallest integer factor *k* that fits (box mean over *k*×*k*,
partial blocks over the pixels they have, rounded half up; flag `scaled`). The
first new asset that would take the total over `assets-bytes` closes the
budget: it and every later new asset are `noasset: "budget"` (reuses still
admitted, they cost nothing). A decode or render that fails or is
unsupported is `noasset: "codec"` or `"render"`. **An asset never fails a
page**: every problem is a `noasset` reason.

**What P3.13 builds (D294-D301).** The level `images`, on both hosts; `figures` is P8.5's, below (D351-D355). Settings travel in `Options` (`asset_dpi`, `asset_pixels`,
`asset_bytes`, `assets_bytes`, `asset_signatures`), not `setting-of` (an environment poke), and are written to the header
only when `assets` is on. The input is read by the asset unit (`as-collect`, D333): every placement that passed the P3.6
checks is a candidate whose bytes (the JPEG's own, or the PNG) are made there, and the document unit admits them in node-id
order (`as-run`). A candidate is a placement with its object; **an inline image (`BI`) is `noasset: "codec"`** until the
interpreter hands its bytes on. **What P6.1a builds (D332-D338)**: the asset unit runs in a plan/fulfil loop of its own, after
the document pass and before the page units, and asks the host (`decode-img`, kind 6, one round for every codec image and
mask) for the pixels of an image whose codec kin does not read: DCT that cannot pass through, JPX, JBIG2 (with its globals) and
CCITT. The pixels are the codestream's, 8-bit, gray or RGB, never with `/Decode`, masks or `/Matte` applied (those are kin's)
and never with an EXIF orientation; a four-component DCT is converted to RGB honouring the Adobe marker. A host that cannot
answers `unsupported` or `failed` and the image is `codec`; pixels of another size than the dictionary's are `codec`. The two
hosts' pixels agree where both decode (CCITT exactly; DCT within the tolerance of D190 live, exactly through a transcript);
JPX and JBIG2 are the Rust host's, through OpenJPEG and jbig2dec (`--cfg codecs`, live; a gate replays a transcript), and the Go
host answers `unsupported`. A placement of
800 per mille of its page or more (a scan, a background) is a candidate whose pixels are not made (`part`). An `ImageMask`
is written in black (the fill colour is not in the placement's row). A palette PNG's PLTE is the image's own palette and a
pixel's index is the first entry of its colour; a bilevel gray image is 1-bit; alpha exists only when the image has a mask;
no PNG has `pHYs` (the resolution is the page's, not the image's). Images are held whole: more than twice `asset-pixels`
samples is `too-large`; between one and two times it is scaled by the smallest integer factor (box mean, flag `scaled`).
At most 4,096 placements make pixels and the candidates' bytes stop at twice `assets-bytes` (`budget`), so a hostile file
cannot ask for unbounded work.

**What P8.5 builds (D351-D355).** The level `figures`, on both hosts, with the sources above. It needs the host, so after the
document unit the Engine runs **one more unit, the asset unit** (`as-run-figures`): its own plan/fulfil loop and `BitmapSet`,
ids from `au-id` (above every page unit's), its requests the `crop` of a rendered figure's rectangle (layer 0, pad 2 pt,
`asset-dpi`, `d·3/4` step-down, `too-large` under 36 dpi, PNG with `pHYs` at the render's resolution) and the `decode` of the
frame a `cropped` figure is cut from; it runs on copies of the Model until every answer is in, so a transcript holds nothing a
live run did not ask for and a replay needs no Renderer. With no Renderer the crops are answered `unsupported` and the figure
says `render`. The sources, in code: a whole image with `overlaid`, `clip-bbox` or `transformed`, an image that was `colorspace`
or `codec` at `images`, a page-sized image of a PDF, a part of an image of a PDF, several, none, and a region that is not an image
are **rendered**; in a raster input every region that is not rendered is **cropped** from its frame's decode (floor and ceiling
of the box in pixels, no resampling); a whole clean image is its own pixels as at `images`; `text-overlap` is set when a node
with text that is not inside the figure has a region in the padded rectangle. The regions that are not an image are provisional
figures made at this level only: **vector art** (clusters of the page's visible, non-clip paths within 2 pt of one another, at
least two, both sides at least 18 pt, under 800 per mille of the page, not mostly under an image, not mostly in a table) and the
graphics layer's `figure` regions (§9.9, a hook until P7.G fills them). A figure whose only region is its caption's is `noasset:
vector`. Not here: cropping the pixels of an image of a PDF (`decode-img`, P6.1a) and a figure's kind beyond `unknown` (P8.1).

**The Model and JSON.** Asset ids are 0.. in admission order; the file name
is `a<id>.jpg` or `a<id>.png`. A `figure` or `seal` has `asset` (an id) or
`noasset` (a reason: `withheld`, `part`, `vector`, `composite`,
`colorspace`, `codec`, `render`, `too-large`, `budget`), and neither when
`assets` is `none`. The top-level `assets` array (§4.2), fields in this
order:

```json
"assets":[
 {"id":0,"name":"a0.jpg","type":"image/jpeg","origin":"embedded","from":"dct",
  "object":41,"bytes":48213,"sha256":"9f2c…(64 hex)","w":1200,"h":800,
  "uses":[{"node":12,"at":[3,72,140,540,452]}]},
 {"id":1,"name":"a1.png","type":"image/png","origin":"rendered","from":"render",
  "bytes":20511,"sha256":"…","w":938,"h":521,"dpi":150,"flags":["text-overlap"],
  "uses":[{"node":19,"at":[4,70,98,522,348]},{"node":27,"at":[5,70,98,522,348]}]}]
```

`from` is what the pixels were before (`dct`, `jpx`, `jbig2`, `ccitt`,
`flate`, `lzw`, `runlength`, `raw`, `png`, `jpeg`, `tiff`, `render`); `object` the image XObject's object number (absent for inline
images, raster inputs and renders); `dpi` the render's resolution (rendered
only); `flags` from `overlaid`, `clip-bbox`, `transformed`, `icc`, `scaled`,
`text-overlap`; each use's `at` the page region the asset's pixels map to.
`sha256` is over the **encoded bytes**, lower-case hex, computed with the
`Crypto` port. The settings are written as `"assets":"figures"`,
`"asset-dpi"`, `"asset-pixels"`, `"asset-bytes"`, `"assets-bytes"`,
`"asset-signatures"`.

**HTML** (option `assets`, §5.2):

```html
<figure data-emon-kind="chart"><img src="data:image/png;base64,iVBOR…"
 alt="Revenue by quarter" width="603" height="333" data-emon-asset="1">
 <span data-emon="label">Q1</span>…<figcaption>Figure 3. Revenue</figcaption></figure>
```

* The `<img>` is the figure's first child; the rest is the placeholder's
  content in its usual order (labels, `<figcaption>`). `role="img"` and
  `aria-label` leave the `<figure>` (the `<img>` is now the image; both
  would announce it twice).
* `alt` is exactly the text the placeholder's `aria-label` would have had —
  the PDF's `/Alt` (D076) first, a seal's `Stamp: …` label (D069) — and
  when that is empty, `alt=""` if there is a `<figcaption>` (the caption is
  the description), else `alt=""` and `data-emon-alt="missing"` so an EPUB
  pipeline can find every image that needs one. emon does not invent alt
  text.
* `width`/`height` are the use's region in CSS pixels (`cpt·4/300`,
  rounded half up, at least 1): the size the picture had on the page, so a
  reader reserves the space and a 150-dpi asset is sharp on a dense screen.
* `inline`: `data:<type>;base64,` + RFC 4648 base64 (standard alphabet,
  padded, no line breaks, not wrapped by `pretty`), written by kin. A reused
  asset is inlined again at each use (a `data:` URI cannot be shared), which
  is what `external` is for.
* `external`: `src` is `asset-prefix` + name, escaped as any attribute; the
  caller writes `model.asset_files()` beside the HTML. This is the form for
  EPUB (the files go in the container, `type` goes in the OPF manifest) and
  for size.
* A node with `noasset` keeps its placeholder and gains
  `data-emon-noasset="<reason>"`.

**The library hands the bytes over.** `model.assets()` and `node.asset()`
(§3.9) give each asset's metadata and a borrow of its bytes; the bytes live
in the Model's `abytes` `Buf` and are never copied by emon. JSON never carries
them (a caller who wants one self-contained JSON file base64-encodes them
itself).

**The browser (WASM).** Everything above is kin, so the WASM build does the
same; the costs are memory. The `abytes` `Buf` lives in linear memory, up to
`assets-bytes`; `to_html` with `inline` adds 4/3 of that again as UTF-8 (an
all-ASCII `data:` HTML decodes to a one-byte-a-character JS string, not
UTF-16). The JS SDK therefore documents `external` as the browser form:
`asset.blob()` returns a `Blob` of the right `type` (one copy out of linear
memory, which the page's heap never holds), `asset.bytes()` a `Uint8Array`
copy (in the heap's accounting), for `URL.createObjectURL` or a zip writer,
and `model.htmlWithBlobURLs()` does the naming (`asset-prefix` plus a name
map by hand otherwise). **Measured** (D360): a 36 MiB document whose assets
fill 30 MiB of the 32 MiB default (ten of them: the next would not fit) takes
213 MiB of the module's linear memory, and above the page's 36.8 MiB heap
baseline the JS heap peaks at 40.2 MiB in `inline` (the one string of the
HTML) and at 0.2 MiB in `external` with `blob()` (30.2 MiB if each asset is
also copied out with `bytes()`). Crop bitmaps are freed with their unit; decoding
a Flate image still inflates it whole into `Cells` (four bytes a sample),
which `asset-pixels` bounds (about 50 MB at the default for RGB) until
`inflate` can write a `Buf`.

**Identity.** Embedded bytes are the input's; decoded, cropped and rendered
pixels come from kin or from transcribed answers; the PNG and the base64 are
kin's. So (input, transcript, settings) → the same JSON, the same HTML in
all three `assets` modes, and the same asset files, byte for byte, on every
target; the identity gate compares all of them (§14.1). What the gate does
*not* claim: two live hosts' `Renderer`s or codecs agree on pixels (they
need not; that is the transcript's job, as for OCR).

### 10.12 Form fields (P11.x3, D548)

**Source 1, the AcroForm** (§8.1): every widget of a terminal field that is a
check box, a radio button, a text field, a combo box or a list box, that is on a
page, is not hidden or no-view and has an area, becomes a `field` (§3.2) with
`src` `annot`: `name` the field's full name (`topmostSubform[0].Page1[0].c1_1[0]`),
`type` `checkbox`, `radio`, `text`, `combo` or `list`, and, of a button,
`checked` (whether *this* widget is on: its `/AS` names a state other than `Off`)
and `value` its export value (the state it is in when on); of the other kinds
`value` is what the field shows (`/V`, else the appearance's text, several
selected values on separate lines) and is absent for an empty field. Push buttons
and signature fields (§10.7) are no `field`. `conf` is how well `/V` and the
appearance agree (950 they do, 600 they differ, 850 `/V` alone, 700 the
appearance alone, 900 a blank field). A document with no such widget is not
touched.

**Placement is geometric, into the block that holds the label.** A `field` is an
inline of a paragraph, heading, list item, table cell or caption (a `field` in
any other place is a block, as before), put by the widget's rectangle and the
boxes of the blocks of its page; the block's `text` is unchanged, a block that
was plain text gets a `text` inline for its words beside the field, and the
field's `at` is the widget's rectangle. A block can be a label if its text has a
letter or a digit; a dot leader, a dash between the boxes of a number is not one
but joins a label to a box however far apart. The rule, in the order tried:
(1) a *check box or radio button* takes the label to its right on its line whose
left edge is within 12 pt (1.5 times the box's height if more) of the box, and
comes before the block's words; (2) failing that, or for any other kind first,
the label to its left on its line whose right edge is within 24 pt of the box
(or joined by a leader), the field coming after the words; (3) a *text, combo or
list field* may take instead the label above it, ending within 18 pt over its
top and starting left of its right edge, nearest by the gap plus the distance
sideways (none when they overlap, at most 40 pt), after the words; the label to
the left wins unless the one above is nearer by more than 8 pt; (4) a field with
no label gets a paragraph of its own (all its fields on one page), after the last
paragraph, heading or caption of its page's document or section that starts above
it, else before the first of the page, else at the document's end. Fields in one
block are in reading order. "On its line" means the vertical extents overlap by at
least half of the shorter.

**HTML** (§5.1): a `<span data-emon="field">`, never an `<input>`: a check box or
radio button is an empty span with `role` `checkbox` or `radio`, `aria-checked`,
`data-emon-checked` and its export value in `data-emon-value`; any other field has
its value as the span's content.

**Source 2, drawn boxes** (a vector square or a scanned `[ ]` or `☐` before a
label, with no AcroForm) would make the same node with `src` `infer`: **not built**
(P11.x3's follow-up); a scanned form has no `field` yet.

---

## 11. Locale (D021)

* A **locale hint** is optional: a BCP 47 tag (`de`, `pt-BR`, `zh-Hant`).
  **Absent means absent** — not English. Without it, everything is done
  script-driven and locale-independently: numbering patterns are numeric,
  roman and letter based; bullets and punctuation are Unicode-general; the
  decimal separator is inferred per column.
* **With a hint**, emon gains: OCR language selection; keyword tables for
  numbering (`Chapter`/`Kapitel`/…), captions (`Figure`/`Abbildung`/…),
  continuation markers, signature cues and "Page X of Y" forms; dehyphenation
  via hyphenation patterns; and the Model's `lang`.
* A PDF's own `/Lang` sets the Model's `lang` and may select OCR languages,
  but does **not** enable keyword tables unless the embedder's hint agrees —
  producers write `/Lang (en-US)` on documents in every language.
* Keyword tables are `defdata` in kin, per language, small, and extensible
  by contribution; a locale emon has no table for behaves as no locale plus
  OCR language selection.

---

## 12. Concurrency and determinism

### 12.1 Concurrency (D022)

The kin core is **pure and single-threaded**, as in nome. Parallelism is the
host's, through the `Task` port and the plan/fulfil loop (§7.3):

* Per page, stage 1's parser, renders, OCR requests and tracer are
  independent units; stage 2 is per page; stage 3 is one unit.
* nome's three rules hold: tasks are pure functions of immutable inputs;
  no task reads another's data; **results merge in submission order**.
* The gate: serial and parallel runs produce byte-identical JSON.

**What the hosts do with it (P10.2, D505-D510).** `parallel=N` (§13; one `key=value`
line like every setting, `limit.workers` caps it; **1, serial, is the default**) lets a
host spread one conversion's **fulfilment** over a bounded pool of N workers. Kin never
runs on a worker and its schedule is untouched: the document pass, every page unit
and the document unit run on the calling thread in the order they always did, and the
Model, the transcript and the accounting (`RunStats`) are the serial run's, byte for
byte, at every N. Two things use the pool:

* *A round.* The requests of one round are independent (the machine starts every one,
  then takes every completion, and appends the answers in id order, §7.3): they run at
  once, at most N at a time. This is the asset unit's `decode-img` round, the figures
  unit's crops, a born-digital page's two renders and a scanned page's targeted passes.
* *A look ahead.* A page unit writes into arenas the pages share, so its kin cannot run
  beside another's; but **what a page asks is known before it writes anything**. A
  *dry attempt* (`pu-dry`, kin) is the part of an attempt that precedes its first write: it
  asks exactly what the real attempt will in those rounds (a scan's `decode`, then the
  OCR of the page; a born-digital page's two renders, then its OCR) and changes nothing
  (the page-unit tests hold it). While the loop is on page p the host runs a dry chain
  for the `pl-ahead` (N − 1) pages after it, has what they ask answered by the pool, and
  keeps each answer **by the request's record**. When the loop reaches page q it runs
  its own units as always and asks as always; a request a dry chain already asked is
  answered by that job instead of a new one, and any other is answered at once, as in a
  serial run. So nothing a dry chain computes is used but the answer to the very request
  the real unit asked, and the ports are functions of their requests (the property
  `bb repeat` run-vs-run already holds them to): a wrong guess costs a job and changes
  nothing. A dry chain's panic or error ends it and is dropped; the real unit meets the
  same condition at its own turn, in its own place (a limit breach, a refused round).

The document pass (it reads the input, and misses and fetches), the document unit and
every unit's kin remain serial, so the gain is bounded by the share of a document's time
that is a port's (Amdahl): a born-digital page that asks nothing gains nothing and pays N
idle threads; a scan whose OCR is most of its time gains most (D510 has the
measurement and the default). A replay (§13.1) has nothing to fulfil and stays serial;
the browser has no threads and ignores the setting. A worker that **panics** is not
lost: the loop's thread runs the request again itself, in its own place, as a serial
run does, and the panic is that run's.

**Limit overrides and threads (D436, D508).** An override of `limit.NAME` is scoped by
the platform. Rust: a thread-local table, and every job on the pool installs the
conversion's table for itself, so a worker sees the bounds the calling thread was given
and conversions with different bounds run side by side. Go, Java and C#: a process-wide
table behind a read-write lock that a conversion with overrides holds exclusively (it
runs alone) and one without holds shared; a conversion's workers run inside that scope,
read the table without taking the lock (a worker that took it would wait on the
conversion that holds it) and have all ended before the conversion returns, so
no conversion's bounds are seen by another. Tesseract's engines are not thread safe: the
OCR adapters keep a pool of engines (one a concurrent reading, a full model load each).

### 12.2 Determinism

**Same input + same transcript + same settings → byte-identical Model JSON
and HTML, on every target, every run.** What it costs is nome's price: no
floats in kin, fixed-point geometry, integer statistics (medians, not
means, where a mean would need division that rounds differently — and where
division is needed, it is integer division with a stated rounding), sorted
iteration with total tie-breaks (never "whatever order the hash map gave").

What is **not** deterministic across hosts — decoded JPEG pixels, rendered
pixels, OCR output — is exactly what the transcript captures. Across **runs
on one host** with the same engine versions, the whole pipeline including
the ports is expected to be deterministic, and `bb repeat` checks it.

**How the bytes arrive is not an input** (D058). The same bytes read from
memory, a file, a browser `Blob` or a URL in ranges produce the same Model:
a unit's result is kept only from an attempt in which no read missed, so
every byte kin used was the true byte and the kept computation *is* the
in-memory one (§13.1). A source
that changes under a run (length differs from the declared one, a short
read, a URL whose validator changes between range requests) fails with the
named error `source-changed`, never with a different Model.

### 12.3 Performance

nome §10's rules apply verbatim: generated code may not be worse than the
hand-written code it replaces, judged by measurement, as a library, with one
hand-written comparator per bet. emon's specific bet is **per-pixel work
through a port call** (`bitmap-u8`): an A4 page at 300 dpi is 8.7 M pixels,
touched several times. Phase 2's spike measures it; if a generated inner loop
is more than 2× the comparator, the answer is a small number of named bulk
`Bitmap` forms (integral image, row histograms), not hand-writing the
tracer.

**Measured (P2.7, D092).** Otsu, Sauvola over integral images and 8-connected
labelling of a 2481×3508 page through `bitmap-u8` and `Cells` are within 2× of
the hand-written Rust comparator in every target for every stage except the
streaming ones (threshold, zero-fill), which are what the four bulk forms
above replace; the tracer stays in kin.

**The page budget once fusion exists (D210).** Per A4 page at 300 dpi, Rust
host, the reference machine (Apple M1 Pro), measured by `bb fusion-bench`
(not a gate; a regression of more than 25 % on a stage is a finding to
record):

| stage | budget |
| --- | --- |
| render, `with-text` + `no-text` (pdfium) | ≤ 0.3 s |
| normalization (gray, histogram, layers, Sauvola band, deskew) | ≤ 0.12 s Rust, ≤ 2× that in Go |
| tracer (runs, components, contours, rules, primitives, `tlines`) | ≤ 0.15 s Rust, ≤ 2× in Go |
| alignment + health + arbitration + defences (graphics fusion included, ≤ 5 ms of it, §9.9) | ≤ 0.03 s |
| OCR, born-digital, `full` | measured (Tesseract's, about 1-3 s) |
| OCR, born-digital, `witness` | ≤ 30 % of `full` on the median page |
| OCR, scanned page, first pass + targeted | ≤ 1.5 × the first pass |
| peak memory of a page unit | ≤ 120 MB (two RGB renders are 50 MB) |

Kin raster work above budget is answered by a bulk form only under D092's
rule (a pure whole-page map or reduce, measured over 2×).

**Measured (P7.S, D200, D210; `bb fusion-bench --born --scopes`).** Live Rust
host (pdfium, Tesseract 5.5.3) on the 22 documents (39 pages) of the fusion
set, a machine shared with other jobs throughout, so these are loaded times;
per A4 page at 300 dpi:

| stage | `full` | `witness` |
| --- | --- | --- |
| render, `with-text` + `no-text` | 0.26 s | 0.19 s |
| OCR, first pass | 9.3 s | 2.0 s |
| OCR, later passes (targeted; the witness lines) | 1.7 s | 3.5 s |
| **OCR, all** | **11.0 s** | **5.5 s** |
| the page unit's own kin work (all raster stages, alignment, health) | 2.15 s | 1.2 s |
| document unit (stage 3) | 0.003 s | 0.003 s |
| peak memory of the process (the most; 308 MB with no pixel read) | 397 MB | 407 MB |

The OCR time of `witness` on the median document is 0.48 of `full`'s (0.50
pooled): the 30 % row is **not met** (its twelve `psm` 7 requests a page and
the `no-text` page cost a half), at equal accuracy on every metric of the
fusion set. The kin raster work is over the sum of the normalization and
tracer rows; those two are measured alone by `bb ink` and `bb trace`.

---

## 13. Public API and settings

Small and the same shape in all four languages, allowing for spelling:

    Engine(config)                          ports, engines, limits, settings
      .intake(inputs)         → Source      stage 0 (cheap)
      .convert(inputs)        → Model       stages 0–3, driving plan/fulfil
      .convert(inputs, transcript) → Model  replay: no Renderer/Ocr calls
      .record(inputs)         → (Model, Transcript)
    Input(byte source, { password? })       one document or image (§13.1)
    Model
      .root() / .pages() / … (§3.9)
      .to_json(options) / .to_html(options)

`inputs` is one `Input` or an ordered list of them; a bare byte buffer is
accepted wherever an `Input` is, as shorthand for an in-memory source with
no password.

### 13.1 Inputs are byte sources, never paths (D058)

The maintainer's requirement: keep inputs abstract, a streamable byte
interface, so that emon compiles to WASM and runs in a browser; **no
dependence on a concrete filesystem**. So:

* **The library and the Engine API never take a path**, never open a file,
  and never touch a filesystem or network. (Test drivers and CLIs are not the
  library; they may read files and hand the bytes over.)
* **A byte source is random-access with a known length**: `length()` and
  `read_at(offset, buffer)`. PDF needs random access (the xref is at the
  end; objects are reached by offset), and so does TIFF (IFDs are linked by
  offset). A forward-only stream is therefore not the contract; it is one
  *adapter*, which spools into memory bounded by the input-bytes limit.
  Each host spells the interface in its platform's own idiom:

  | Host | Byte source | Adapters shipped with the host |
  | --- | --- | --- |
  | Rust | `trait ByteSource { fn len(&self) -> u64; fn read_at(&self, off: u64, buf: &mut [u8]) -> io::Result<usize>; }` | bytes (`&[u8]`, `Vec<u8>`), `Read + Seek`, `Read` (spooled) |
  | Go | `io.ReaderAt` + size (as `io.SectionReader`) | `[]byte`, `*os.File` satisfies it as-is, `io.Reader` (spooled) |
  | Java | `SeekableByteChannel` | `byte[]`, `ByteBuffer`, `InputStream` (spooled) |
  | C# | `Stream` with `CanSeek` | `byte[]`, `ReadOnlyMemory<byte>`, non-seekable `Stream` (spooled) |
  | JS (WASM) | `{ size, read(offset, length) → Promise<Uint8Array> }` — the primary model: async slices | `ArrayBuffer`/`Uint8Array`, `Blob`/`File` (`slice().arrayBuffer()`), URL (`fetch` with `Range`, validator pinned), `ReadableStream` (spooled), any object with that shape |

  An embedder may implement the interface over anything (object storage, an
  archive member, a database column).
* **The contract is Blob-like, not in-memory** (the maintainer: "an
  abstract interface akin to JS's Blob … lets us read slices and jump around;
  but doesn't guarantee synchronous in-memory access"). Reading everything
  into memory is one trivial source, never an assumption. A source is
  **sync** (bytes, a native file or seekable handle: a read may block) or
  **async** (a browser `Blob`, a URL: a read is a promise).
* **kin reads a `Src`, not a `Blob`.** The `Src` port (§7.2) is a per-unit
  view over a host **chunk cache** (aligned chunks, 64 KiB by default) shared
  by all units of a run. `src-u8` answers the byte if its chunk is resident.
  If not: a **sync** source fills the chunk inline and answers (no rounds,
  ever); an **async** source records the missed chunk, sets the view's
  *missed* flag, and answers 0. The cache only grows *between* attempts,
  never during one.
* **Miss, abort, re-run** (D058) is how synchronous kin consumes async
  reads. A unit (intake of one input; one page's evidence) is attempted;
  kin checks `src-missed` at **checkpoints** — after each indirect object,
  xref section, trailer and stream span — and returns the status
  `need-bytes` up to the unit's top, before any further work. The host then
  fetches every missed range (plus `src-hint`s), awaiting, and re-runs the
  unit **from scratch on fresh or copied `Cells`**. Only an attempt with no
  miss is kept, so every byte it used was the true byte: the kept run is,
  instruction for instruction, the in-memory run. A limit breach or error in
  an attempt that has missed is treated as a miss, never reported. Units
  must therefore be pure and must not mutate `Cells` they did not create or
  copy for the attempt (D008, D022).
* **In the browser** (D356, D357) the loop is the JS SDK's: the WASM module
  answers `need-bytes` with the ranges its `Src` missed, the SDK awaits
  `read(offset, length)` of the source for them (several at once), hands the
  bytes in and steps again; there is no whole-file read **but one**: the
  renderer's. `ports.render` (pdfium's WebAssembly build, §7.3 "As built,
  P11.4c", D531) is opened by the first `render` or `crop` request, with the
  whole file read once from the source (`read(0, size)`; an in-memory source
  hands over what it holds), because pdfium's in-memory loader wants the file
  in its heap; a document that asks for no raster never does it, and a fault
  of the source under that read ends the conversion with its typed error. An
  in-memory source
  (`ArrayBuffer`, `Uint8Array`) is *resident* and the SDK gives the engine its
  bytes at once (reading what the page already holds is not a read; `lazy`
  reads it by range like the rest); a `Blob` or `File`, a URL and any
  `{size, read}` are read by range. A URL source pins its validator at open
  (a strong `ETag`, else `Last-Modified`), sends it as `If-Range` and checks
  every answer against it and against the length: a change is `source-changed`.
  A read that rejects is `io`, a read that comes back short is
  `source-changed`, a stream longer than `input-bytes` is refused while it is
  spooled.
* **Bounds.** Read-ahead doubles per round of a unit: round *r* fetches
  at least 64 KiB · 2^r around each miss, coalesced with adjacent and
  already-requested ranges into one request per run of chunks. Once the
  window covers the input there can be no miss, so a unit takes at most
  ⌈log2(length / 64 KiB)⌉ + 2 rounds (≤ 17 for 2 GiB), and it never fetches
  more than about twice the bytes it touched, capped at the input's length.
  A hard `FETCH_ROUNDS` limit (default 24) names a breach. Wasted compute is
  one attempt per round, each cut short at its first checkpoint after the
  miss.
* **PDF under it.** Intake round 0 prefetches the first and last chunk:
  header, `startxref`, the trailer and usually the last xref section. Each
  `/Prev` section, xref stream, object stream, the catalog and the page-tree
  nodes are fetched by offset as intake discovers them (a round per level of
  dependency unless read-ahead already covers it). Once the xref is known,
  kin **hints** each object's extent (its offset to the next-larger xref
  offset), so a page unit's first attempt typically fetches the page's
  objects, content streams, fonts and resources in one or two rounds.
  Streams are cut from the `Src` as a `Blob` span (`src-slice`) and decoded
  in memory; `inflate`, the filters and `bytes` keep reading `Blob`s.
  Scanning for `endstream` past a wrong `/Length`, and xref repair by
  scanning (P3.1), read forward and may fetch much of the file — correct,
  bounded by doubling, reported.
* **Images and TIFF.** kin only sniffs (the first chunk, prefetched).
  Decoding is `ImageDecode`, a host port through plan/fulfil (§7.3), whose
  host implementation reads the same byte source however suits its codec:
  streamable formats (PNG, JPEG) read forward; TIFF seeks to IFDs by offset;
  a browser decoder takes `Blob.slice`. An image embedded in a PDF is
  requested by (input, offset, length, filters), so the host reads the span
  from the source. None of this touches kin's re-run loop.
* **The gate.** Identity is unchanged — (input bytes, transcript) pairs —
  and every driver additionally runs each fixture through a simulated async
  source with a tiny chunk (512 B) and must produce byte-identical JSON and
  HTML to the in-memory run, reporting rounds and bytes fetched.
* **Several inputs** make one document (§2.0): a multi-page TIFF is one input
  with many frames; an image set or a set of scans is an ordered list of
  inputs; **PDFs and images may be mixed** (the maintainer's answer). Pages
  are numbered across inputs in the caller's order. Each input has its own
  `Src`, chunk cache and password; a PDF's page units never read another
  input.
* **The password travels with its input** (`Input(source, {password})`,
  D040), not in the settings: settings are recorded in the Model header, and
  a password is never written to the Model, the evidence dump or a
  transcript.
* **Transcripts** record, per input, its index and length — never its bytes,
  where they came from, or how many fetch rounds it took. The identity corpus stores the input bytes
  beside the transcript (§14.1); a replay may read them from any source.

Settings (all named, all with defaults, all in the Model header):

| Setting | Values | Default |
| --- | --- | --- |
| `locale` | BCP 47 or none | none |
| `ocr` | `always` / `auto` / `never` | **`always`** — every page, born-digital included (D038); `auto` OCRs only where text-layer health, image coverage or hidden text call for it |
| `ocr-scope` | `full` / `witness`: what `ocr: always` reads of a born-digital page (§8.3, §9.8, D200); written to the Model's `settings` only when not `full` | `full` |
| `sources` | subset of text/ocr/trace | all available |
| `dpi` | OCR render resolution | 300 |
| `detail` | `blocks` / `lines` / `words` | `blocks` |
| `pages` | range | all |
| `furniture` | `policy` / `keep` / `drop` | `policy` |
| `figure-text` | `omit` / `labels` | `labels` |
| `ledger` | on / off in outputs | on in JSON, off in HTML |
| `max-rounds` | plan/fulfil rounds (per page unit, D207) | 5 |
| `parallel` | workers a conversion spreads its fulfilment over (§12.1, D505); capped by `limit.workers` (16); changes nothing in the Model | **1** (serial) |
| `assets` | `none` / `images` / `figures` (§10.11, D117) | `none` |
| `asset-dpi` | render-crop resolution | 150 |
| `asset-pixels` | pixels per asset | 4,194,304 |
| `asset-bytes` | encoded bytes per asset | 4 MiB |
| `assets-bytes` | encoded bytes per document | 32 MiB |
| `asset-signatures` | `off` / `on`: pixels for handwritten signatures and initials | `off` |

### 13.2 Bindings (D001, D471-D476)

The browser (§13.1), the C library and the babashka pod are the Rust host and add no kin arm. All three take the settings above as **one
`key=value` text, parsed by kin** (`emon.config`), and name errors as the Engine does (D040: the stable name, kin's). The C library
(`host/c/include/emon.h`) is the Engine's four verbs in C: a one-shot `convert`/`intake` over bytes or a lazy `read_at` callback, and the inverted
`open`/`step`/`provide`/`finish` session of the browser's ABI for a caller that owns the byte source (and the `ocr` fulfilment), with `record`
and `replay` as a session; emon allocates and the caller frees (`emon_free_*`), and no panic crosses its boundary. It gives the Model as JSON,
HTML and assets, not as a walk. The pod gives `emon/convert` (a path or bytes and option text in; the Model as JSON or EDN, the HTML and the assets out).

---

## 14. Testing and accuracy evaluation (D023)

Two different questions, kept apart: **is emon the same everywhere**
(identity, determinism, drift — gates that are exact) and **is emon right**
(accuracy — measurements with thresholds that ratchet).

### 14.1 The exact gates

1. **kin drift** — re-emit every source, compare with committed `gen/`.
2. **hosts** — `kin.host/check-agreement` over the four host trees, plus
   call-site arity (`check-usage`).
3. **check** — generate, compile and run every target on probe inputs;
   require one answer, and require it to be the *right* answer.
4. **unit** — laws in `emon.test`, native tests on every target with a
   framework.
5. **identity** — the corpus is **(input bytes, transcript)** pairs. Every
   host's driver replays each and must produce **byte-identical Model JSON
   and HTML**. Transcripts store bitmaps losslessly (PNG) so that no lossy
   decoder is part of the comparison. With `assets` on (D117) it also
   requires byte-identical asset files and HTML in each of the `inline`,
   `external` and `omit` modes, and checks each `sha256` independently.
6. **repeat** — serial vs parallel, run vs run, forwards vs reversed page
   submission: byte-identical. Parallel is two things (D507): eight conversions at
   once on one Engine, and one conversion on 2, 4 and 8 workers (`parallel=N`, §12.1) —
   the latter on every host that has threads (the C binding and the pod too: the Rust
   library behind them reads the option), with deterministic fake ports standing in
   for the engines on the four hosts that have a driver for them, so that the
   fulfilment path runs on every document of the corpus. The browser has no threads and
   says so.
7. **schema** — every JSON output validates against `schema/emon-N.json`.
8. **fuzz** — mutated and truncated PDFs and images: no panic, no hang,
   bounded memory, a named error or a partial Model. Cross-host agreement on
   the *error name* too.
9. **embedders** — the public API surfaces of the host layers compared with
   each other and with §3.9/§13.

### 14.2 The accuracy gates

`bb accuracy` scores emon against ground truth, per corpus, per metric, and
compares against a **committed scoreboard**. A metric that falls below its
recorded value fails; a metric that rises updates the scoreboard in the same
commit (the ratchet). Metrics:

| Area | Metric |
| --- | --- |
| Text | CER, WER over reading-order text; bag-of-words F1 (order-insensitive) to separate "wrong words" from "wrong order" |
| Reading order | Kendall τ over matched blocks |
| Blocks | per-kind precision/recall at IoU ≥ 0.5 (DocLayNet classes mapped to Model kinds) |
| Headings | detection P/R; level accuracy; hierarchy TEDS |
| Tables | `table-teds` and `table-teds-struct`: ordered tree edit similarity over canonical table HTML, with unit insertion/deletion, span/tag mismatch cost 1 and normalized cell-token edit cost (content ignored for structure-only); normalized by the maximum HTML descendant count excluding the table root (D631). The older `table-teds-lite` and `table-teds-struct-lite` cell-sequence metrics retain their ratchets. Cross-page merge P/R; ICDAR 2013's own **adjacency-relation F1** (`table-adj-f1`, D227) |
| Graphics (D227) | `rule-p`, `rule-r`: length-weighted, a predicted `grl` stretch matching a truth rule of its orientation (≤ 150 centi-degrees) within half their thicknesses plus max(1 pt, 1 px of the variant's dpi); `fill-p`, `fill-r` by area; `twin-gap` (a document's `pdf/` table TEDS-lite minus its scan variant's, recorded); `tag-verdict-acc` (tagged-table verdicts equal to the fixture's declared truth); `tag-table-agree` (the share of tagged `Table` elements confirmed, on the real corpus: recorded, not ratcheted); `rule-ghost` (lower is better: body words that are only rule marks, beyond the gold's, over the body's words; D517) |
| Furniture | P/R of dropped running elements; label accuracy |
| Figures | detection P/R; `kind` accuracy |
| Seals/signatures | detection P/R; `name`/`text` CER |
| Artifacts | watermark/ghost P/R — specifically, **body-text contamination rate**: characters of watermark/seal text that leaked into the body |
| Initials | detection P/R; first-word accuracy; single-letter false headings (D115) |
| Alignment | accuracy and coverage per kind (paragraph, heading, cell) (D115) |
| Columns | fragment column accuracy; continuation join P/R; hyphen resolution at breaks (D115) |
| Confidence | expected calibration error per kind |
| Seal fields (D418) | `seal-name-acc` and `seal-date-acc`: of the gold's seals and stamps that have a `name` (a `date`), the share whose matched predicted node (same subkind, most words in common, at least a quarter of the union) has the same letters and digits in it; `stamp-ghost` on mapo80, live only (`bb stamp-ghost`: OCR words inside a labelled stamp that reach the body, never ratcheted) |
| Fusion (D206) | `ghost-rate` (body words with OCR and no text-layer source that the gold body does not have, over predicted body words); `seal-leak` (characters of the gold's seal and stamp text found in the body, over body characters, as `watermark-leak` is for watermarks); `src-ocr-share` (body characters whose source has `ocr` and not `text`) and `tl-override` (on born-digital variants: body characters under the `text-layer-suspect` flag, which §9.4 sets exactly where OCR replaced a text-layer candidate, over body characters); per-source win rates and per-rule counts from the evidence dump's `fv` and `fx` (`bb fusion-report`: recorded, never ratcheted, as they have no better direction) |

### 14.3 Ground truth

1. **The synthetic round trip** (D041) — emon's best oracle. An HTML corpus
   (headings of known level, lists, tables including multi-page tables with
   and without `thead` repetition, running headers and page numbers,
   footnotes, figures with `data-` kinds, drop caps and other initials,
   every alignment, multi-column layouts with spanning elements and
   paragraphs continuing across columns and pages; D114) is printed to PDF by **external
   tools at development time** — headless Chromium's `printToPDF` as the
   primary producer, and optionally others (nome, WeasyPrint, LibreOffice)
   so emon does not overfit to one producer's idioms. The HTML *is* the
   ground truth. The PDFs are then rendered to images and **degraded**
   synthetically (noise, blur, skew, JPEG artefacts, low resolution, added
   watermarks, overlaid seals and signatures from a small licensed library,
   bleed-through), giving a scanned corpus whose truth is still known.
   The fusion corpus (D209) adds, for a 22-document set, colour scans and overlay scans (generated watermarks,
   seals, signatures, bleed-through, turned pages, with their own gold) and six hand-edited PDFs (watermark as marked
   content, optional content group and annotation; two broken ToUnicode maps; a worse hidden OCR layer); newly committed
   pixels stay within about 10 MB, larger sets live in the S3 corpus store and feed long runs only (no LFS).
   **The generated PDFs, images and gold Models are committed**; no gate,
   build or test invokes a producer. Regenerating the corpus is a human
   action whose diff is reviewed, and a missing producer only means the
   corpus cannot be *regenerated*, never that emon cannot be tested.
2. **Public datasets**, licences checked before committing anything (DEVLOG item):
   DocLayNet (layout classes including page header/footer, caption, formula,
   table, picture — the closest match to the Model), PubLayNet, PubTabNet and
   FinTabNet (tables, TEDS), ICDAR 2013 / cTDaR 2019 (tables in PDFs), FUNSD
   (scanned forms), Tobacco800 (signatures and logos), RVL-CDIP (scan
   variety), and a seal dataset (candidates noted in DEVLOG, to verify).
   Importers convert each into emon's **gold format**: a partial emon JSON
   Model with a per-corpus declaration of what is and is not annotated
   (DocLayNet has no heading levels; a metric that needs them skips it).
   **Rule and fill truth (D227)** does not come from emon (D100): for a
   born-digital document it is `pdfplumber`'s lines, rectangles and curves
   (pdfminer.six, D094's third-party-oracle exception) kept where poppler's
   render (`pdftoppm`) has ink under them, written once into
   `gold/STEM.rules.json`; the document's scan variants inherit it through
   the variant's known transform (dpi, MANIFEST `facts.skew`, `facts.turns`,
   and the shift ImageMagick's `-rotate A -gravity center -crop WxH+0+0`
   adds to a turned page, D514: the content lands `floor((W' - W) / 2 + 1.5)`
   pixels left and `floor((H' - H) / 2 + 1.5)` up, W' x H' being the turned
   page's box);
   a table drawn in an image takes its rules from the generator that drew it,
   mapped through the placement the generator chose. **Rule ghosts (D517)**:
   `rule-ghost` (lower is better) is the share of a prediction's body words
   that are only rule marks (`|` `_` an em dash, runs of up to three) beyond
   the gold's own count of them, on the documents whose rules are measured: a
   border read by the engine as a letter and left in the body.
3. **A hand-labelled real-world set**, small and hard: court filings (Bates
   numbers, stamps), invoices, annual reports (multi-page tables), scientific
   papers (two columns, formulas), government forms (seals, signatures),
   scanned letters. Labelled in gold format with a small review tool;
   every addition reviewed.

### 14.4 Against the field

The same corpora scored, offline and committed, for named baselines (e.g.
`pdftotext -layout`, pdfminer.six/pdfplumber, Tesseract's own hOCR, and one
or two open-source layout-aware converters), each version-pinned. Not a gate —
a reference line in the scoreboard, so "accurate" means "better than X on Y".
They live under the scoreboard's `:references`, which no gate reads (D339); `bb baselines` runs them (D340-D342).

### 14.5 The text-layer oracle

For born-digital PDFs, stage 1a's extracted characters are diffed (offline,
committed expectations, as nome treats Chromium) against a reference
extractor's per-character output (MuPDF/pdfminer), to catch encoding and
positioning bugs long before they surface as structure errors.

---

## 15. Repository layout

Follows nome's conventions; shares nothing with it (D026, D039):

    bb.edn, deps.edn    tasks and the Clojure-consumer dependency on kin
    SPEC.md DEVLOG.md
    kin/                .kin sources, one namespace per file
    kin/src/emon/       lang.cljc test.cljc targets.cljc project.cljc
    kin/scripts/        gen, emit, check, drift, hosts, identity, …
    gen/{rust,go,java,csharp}/   generated, committed
    host/{rust,go,java,csharp}/  hand-written: ports, engine API, bindings
    host/{wasm,c,pod}/           bindings over the Rust build
    schema/             JSON Schemas per format version
    test/corpus/        inputs, transcripts, gold Models, expectations
    test/import/        dataset importers (not the datasets themselves)
    test/scoreboard.edn the accuracy ratchet
    test/json/          Model goldens: NAME.model (a build script), NAME.json, NAME.pretty.json
    test/evidence/      evidence-dump goldens (§4.5)

### 15.1 Running the gates (nothing else is needed)

`README.md` is the front door for users and `CONTRIBUTING.md` the guide for
contributors (P11.6); this file and `DEVLOG.md` are the specification and the
log, and a newcomer needs only what is listed here to run every gate.

    bb check     generate, compile and run all four targets; one answer
    bb unit      the laws, as native tests on all four targets
    bb hosts     the four host trees expose the same ports
    bb drift     `gen/` is what the sources say (needs git)
    bb lang-test generation-time refusals of emon's own forms
    bb gates     all of the above; fails if a gate prints no score

**Tools.** `bb` (babashka) and `sh`; `git` (for `drift`); a checkout of
`kin` as a sibling directory (`../kin`, put on the classpath by `bb.edn`);
`python3` (only for the third-party reference oracles in `unit`: the table and
JSON-golden checks and the `jsonschema` / html5lib validators, which are tooling,
not runtime; all other scripting is babashka, D094). Each target's toolchain is used if present and **skipped loudly**
if not: `rustc`/`cargo`, `go` and `gofmt`, a JDK (`/opt/homebrew/opt/openjdk`,
or `EMON_JDK`; macOS's `/usr/bin/javac` stub is detected and ignored),
`dotnet` (net10.0; xunit is restored on first `bb unit csharp`). The schema
check in `bb unit` needs Python's `jsonschema`; it is found on the path or in
`$EMON_PYLIB`, else installed once into `~/.cache/emon/pylib` (and skipped,
loudly, if that is impossible). The runtime and the generation path need
none of Python, git or a network.

### 15.2 The sample corpus (`corpus/`, never committed)

`corpus/` (about 12 GB) is gitignored; it is shared through any S3-compatible
bucket (D077). Copy `.env.example` to `.env` (gitignored) and fill in
`EMON_S3_ENDPOINT`, `_REGION`, `_BUCKET`, `_ACCESS_KEY_ID`,
`_SECRET_ACCESS_KEY` (optional: `_PREFIX`, `_PATH_STYLE`).

    bb corpus-push [--dry-run] [category...]           zip per category, upload, write index.json
    bb corpus-pull [--dry-run] [--force] [category...]  download changed archives, verify, extract
    bb corpus-test                                      SigV4 vectors + round trip on a local MinIO (not a gate)

Options for both: `--corpus DIR` (or `$EMON_CORPUS_DIR`), `--part-mb N`
(multipart part size, default 64). One `<category>.zip` per top-level corpus
directory, `_root.zip` for the loose files (MANIFEST, fetch.sh), `_dl/` left
out; archives are deterministic, so an unchanged category is skipped by
sha256. A pull replaces `corpus/<category>` atomically (extract aside, rename).
Pure babashka (own SigV4); no aws/rclone/mc. Implementation: `tools/corpus.clj`.

### 15.3 Running the gates on GitHub (D266)

`.github/workflows/pr.yml` runs the fast core and the gates a change calls for, as `--fast` variants, on every pull request (P11.7, P11.8, D528). `.github/workflows/verify.yml` runs on every push to `main`: the fast core and the gates the change since the last verified commit calls for, IN FULL, on `ubuntu-latest`, in parallel jobs, and posts the commit status `emon/verified` (D550). It also runs weekly (Monday, every gate), on demand, and when `bump.yml` calls it for the release commit:

    gh workflow run verify.yml -f mode=full            # scoped (default) | full
    gh workflow run verify.yml -f sha=COMMIT           # a commit of main; the default is the tip of main

A push runs only when the repository variable `EMON_VERIFY_ON_PUSH` is `true` (the `plan` job's `if:`; skipped, every other job is skipped and no status is posted); the schedule, `workflow_dispatch` and `workflow_call` always run. `concurrency: verify-main` (no cancelling) debounces: a running run finishes and only the newest pending push waits. The base is the newest first-parent ancestor (at most 60 back) whose newest `emon/verified` status is a green one (a description starting `verified:` and a workflow run as its target); `bb ci verify-plan` finds it with the statuses API, and with none, or with GitHub not answering, every gate runs. The plan is `bb ci plan` between the base and the commit, as for a pull request, with one rule more: a diff of `version.edn` or a manifest `bb version` rewrites that changes nothing but the version strings plans no gate (the release bump). Jobs: `plan`; `core` (`bb ci core`); `corpus` (when a planned shard reads it); `unit` and `unit red` (one job per arm: go, rust, java, csharp), when `unit` is planned; `gates` (the planned shards of `ci/gate-map.edn`, in the shape of `bb ci shards`, each `bb gates G...`: static = hosts drift release-audit api-diff lang-test; check; replay = plan ocr render embedders; fusion = align fusion gfuse artifacts seals sealpx sigs retarget; slice; fuzz; identity; repeat; accuracy; png-check; asset; image); `native`, `package` and `archives` (the release archives: the CLI for five targets, the C library and pod for four, `SHA256SUMS`, the artifact `release-archives` kept 90 days) only when the plan says they are affected (a path they are built from changed, no valid archives came with the base, or the version changed); and `verdict`, which posts `emon/archives` (the run holding the archives that are valid for the commit, built here or inherited from the base) and `emon/verified` (`verified: N gates since <sha>`; success only when the plan and every job ran green, and every arm ran the same number of laws). `bb ci coverage` fails if a gate of `kin/scripts/gates` is in no shard, or if a tracked file is matched by no entry of the gate map. Tesseract and pdfium are not installed: `ocr` and `render` replay committed transcripts.

The pull request workflow: `bb ci plan --base origin/main` maps `git diff --name-only` through `ci/gate-map.edn` (path glob to gates; every tracked path must match an entry, `[]` for a document) and fails on a changed path the map does not know; `bb ci core` runs the fast core (check, drift, hosts, api-diff, release-audit, `version --check`, `docs --check` and its self-test, the release tools' and the planner's self-tests, `ci coverage`); the planned gates run as `bb gates --fast G...` where the gate has a `--fast` variant (a labelled, deterministic subset: Rust and Go only, a fixed sample of documents; `EMON_FAST_HOSTS` adds the host arms a change touches) and in full where it has none.

The release flow (D550; `docs/releasing.md`) uses no credential beyond `GITHUB_TOKEN` and the registries' own secrets: `bump.yml` commits `Release vX.Y.Z` to `main` and calls `verify.yml` and `draft.yml` for it; `draft.yml` keeps a DRAFT release `vX.Y.Z` targeting the commit's sha; publishing the draft makes the tag and starts `publish.yml`, which requires a green `emon/verified` on the tag's commit, attaches the archives of the run `emon/archives` records, and publishes to each registry after the maintainer approves the `release` environment.

Repository secrets (Settings, Secrets and variables, Actions): `EMON_S3_ENDPOINT`, `EMON_S3_REGION`, `EMON_S3_BUCKET`, `EMON_S3_ACCESS_KEY_ID`, `EMON_S3_SECRET_ACCESS_KEY`, and optionally `EMON_S3_PREFIX`, `EMON_S3_PATH_STYLE`. The workflow writes them to `.env`, pulls only the `formats` and `financial` categories (the ones `png-check` and `image` read), and caches `corpus/` keyed by `bb corpus-index-sha` (the SHA-256 of the remote `index.json`), so an unchanged corpus is not pulled again. Without the secrets a run that needs the corpus fails with a message naming them. The workflow's `3sln/kin` checkout is the sibling `../kin`.

---

## 16. Security posture

* **No code execution**: PDF JavaScript, XFA scripts, `/Launch`, embedded
  files' contents — recorded as present (a ledger entry), never run or
  opened.
* **No I/O from the core**; no network anywhere; external references are
  data. Input arrives through a byte source the embedder supplies (§13.1,
  D058); the only fetching emon's own code ever does is the JS binding's URL
  adapter, and only when the embedder hands it a URL.
* **Everything is bounded** (§7.4); every breach is a named error; failure is
  per page.
* **Encrypted PDFs** (D040): emon **extracts whenever it can decrypt, and
  ignores permission flags** (`/P`: "no copy", "no extraction" and the rest
  are not consulted). Documents with an empty user password — the common
  owner-password-only case — open with no caller involvement. A caller may
  supply a password alongside the document (`convert(bytes, {password})`),
  tried as user password and then as owner password. When decryption needs a
  password that was not supplied, emon fails with the distinct named error
  **`password-required`**; a supplied password that does not open the
  document fails with **`password-incorrect`**. Both are intake-level errors
  (D030's exception: nothing is readable), and both are distinguishable from
  every other failure so a crawler can route them. Unsupported security
  handlers (public-key `/Adobe.PubSec`, custom) fail with
  **`encryption-unsupported`**.

---

## 17. Open questions

Recorded in DEVLOG as `[?]` items. Answered and removed from this list:
OCR default (→ `always`, D038), sharing with nome (→ none, D039),
permissions on encrypted PDFs (→ ignored, D040), the Java build machine
(→ the JDK at `/opt/homebrew/opt/openjdk`), inputs (→ Blob-like async
byte sources with lazy range reads, required; PDFs and images may be mixed
in one conversion; D058).

1. ~~**Figure pixels**: placeholders only, or an opt-in to emit crops
   through the host?~~ Answered: an opt-in, `assets` (§10.11, D117);
   placeholders stay the default.
2. **Renderer in kin**: a kin PDF renderer would make the raster path deterministic across
   hosts and remove a port — at the cost of implementing PDF painting
   (shadings, blend modes, image codecs still host). Parked; revisit after
   Phase 6's numbers.
3. **nome as an optional corpus producer** (D041): may nome be one of the
   external dev-time PDF producers for the synthetic corpus (never a
   dependency), or should it be excluded?
4. **A lexicon for initials** (D108): may emon ship a small per-locale word
   list (the most frequent few thousand words, a `defdata` table per locale,
   used only when a locale hint is given) to decide whether a one-letter
   initial is a word (`A` + `long` vs `A` + `t the`)? Without one, it is
   joined and flagged.
5. **`ocr-scope` default** (D200, §9.8): `full` (every born-digital page's
   whole render OCR'd) until P7.S's measurement; then the maintainer decides
   whether `witness` becomes the default.
6. **Committed pixels** (D196): fusion gates replay transcripts that must
   hold their renders; plain files in the repository within a stated
   budget, or a fetched, hash-pinned archive? (The coordinator's answer, no LFS and
   about 10 MB of gate pixels, binds `bb gfuse` (P7.G) too: first pages and
   crops, ≤ 3 MB; the rest is measured live from the corpus store.)
7. [x] **Tags without geometry** (D225 case 3) — *coordinator (2026-10-01), per the maintainer's drive-it mandate; sign-off via the cited decision*: a confirmed tagged table that no geometry found MAY be emitted on the tags alone, only if the tag trust is high (`tg-trust` ≥ 700) AND the text layer has text in the tagged cells, with provenance `source: tags`. Tags NEVER override disagreeing geometry: on disagreement the verdict is partial, geometry decides cell boundaries, and tags may contribute header/scope (verify-not-obey, D034).
8. [x] **Unseen vector paint** (D222 row 513) — *coordinator (2026-10-01), per the maintainer's drive-it mandate; sign-off via the cited decision*: a vector rule whose paint cannot be modelled exactly and that the render does not show is dropped from the fused set but recorded in the ledger with a reason (`unwitnessed-uncertain-paint`); it is never silently deleted.

**D698: empty OCR.** An upright scanned page whose first pass and normal body-ink recovery return no words gets one separate contrast-enhanced PSM 6 retry. Its stable request ID and pixel hash bound replay and retry count. It cannot replace a nonempty normal recovery. An emergency reading below mean engine confidence 300 stays evidence; otherwise the existing 700-confidence line rule is preferred before whole-page adoption. If all recorded OCR runs on a page remain empty, a full-page `ocr-empty` dropped entry explicitly diagnoses the unread region without invented text.

**D699: OCR occurrence accounting.** After scanned-page reconstruction, each OCR attempt is compared by normalized word occurrences against page-local Model text and pre-existing dropped text. Unrepresented readings retain their OCR text, box, source and engine confidence in `ocr-unrepresented` dropped entries; they are not promoted to body text. One occurrence budget spans every attempt on the page, so rejected alternative copies are explicitly retained too. This records alternatives as well as losses and does not assert that a rejected reading is correct.
