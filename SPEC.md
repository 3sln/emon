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
no interpretation beyond what the source itself asserts. Each source's
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
   with `kind: chart`, not nothing and not pixels (§10.6).
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
| `heading` | inlines | `level` (1..n, unbounded), `number` (e.g. `"3.2.1"`), `numbering` (scheme) |
| `paragraph` | inlines | `align` (`start`/`center`/`end`/`justify`, when informative) |
| `list` | `item`s | `ordered`, `scheme` (`decimal`, `lower-alpha`, `upper-roman`, `bullet`, …), `start` |
| `item` | inlines, nested blocks | `marker` (the literal marker text), `value` |
| `table` | `caption`?, `rowgroup`s | `profile` (§10.5), `continued` (pages spanned), `columns` |
| `rowgroup` | `row`s | `role`: `head` / `body` / `foot` |
| `row` | `cell`s | `repeated` (a recurring header row that was de-duplicated) |
| `cell` | inlines or blocks | `rowspan`, `colspan`, `header` (`col`/`row`/none), `empty` |
| `caption` | inlines | `label` (e.g. `"Table 3"`), `target` (node id) |
| `figure` | `caption`? | `subkind` (§10.6; D053), `label`, `alt` (from the PDF, if any), `labels` (text found inside, optional) |
| `seal` | — | `subkind` (`seal`/`stamp`/`signature`/`initials`/`digital-signature`; D053), `name`, `text`, `date`, `color` |
| `formula` | — | `text` (best-effort linear text), `display` (block/inline) |
| `code` | inlines | — (monospace block with preserved whitespace) |
| `quote` | blocks | — |
| `note` | blocks | `role`: `footnote` / `endnote` / `margin`; `marker` |
| `field` | inlines | form field: `name`, `type`, `value`, `checked` |
| `furniture` | blocks | running header/footer kept by policy (§10.2): `role`, `pages` |
| `break` | — | page milestone: `page`, `label` (the printed page label) |
| `text` | — | a run of characters (inline) |
| `span` | inlines | `style` flags: `bold`, `italic`, `underline`, `strike`, `sub`, `sup`, `mono`, `smallcaps` |
| `link` | inlines | `href` (URI) or `target` (node id, internal) |
| `ref` | — | a note reference: `marker`, `target` |
| `linebreak` | — | a hard line break that is meaningful (addresses, poetry, table cells) |

### 3.3 Fields every node has

| Field | Meaning |
| --- | --- |
| `id` | Integer, unique in the Model, assigned in document order. **Stable for the same input and settings**, so ids are citeable across runs and across targets. |
| `kind` | From §3.2. |
| `at` | Zero or more regions `[page, x0, y0, x1, y1]` in page space (§3.4). A node spanning pages has one region per page. Inline `text` nodes have regions only at `detail: words` (§13). |
| `src` | Provenance: the set of sources that contributed (§3.5). |
| `conf` | Confidence, integer 0..1000 (§3.5). |
| `flags` | Small closed set of warnings: `low-conf`, `ocr-only`, `text-layer-suspect`, `reconstructed` (e.g. a repaired table), `dpi-unknown`, `rotated`, `vertical`. |

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
  16.16 fixed point in `I64`, angles are integer trigonometry (emon's own), ratios
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
and the API must not pretend otherwise; until calibration lands the Model
header says `"conf": "uncalibrated"`.

### 3.6 Text

* Stored as Unicode code points; serialized as UTF-8.
* **Normalization** (D029): NFC, plus decomposition of the Alphabetic
  Presentation Forms ligatures (`ﬁ` → `fi`, U+FB00–FB06) and removal of soft
  hyphens (U+00AD) at line ends. Nothing else is folded — full-width forms,
  superscript digits and the like are content.
* **Line-end hyphenation** (`-` + newline + lowercase) is joined **only** with
  a locale hint and a hyphenation dictionary agreeing (Liang patterns, emon's own tables);
  without a locale the hyphen is kept and the words joined without a space
  (`multi-page` survives; `infor-mation` survives as written, flagged). Wrong
  dehyphenation corrupts real compounds; kept hyphens are merely ugly.
* **Right-to-left** text stored in *logical* order. PDFs frequently store
  visual order; stage 2 reorders with UAX #9 (emon's `bidi`).
* **Whitespace** is normalized: one space between words, line breaks inside a
  paragraph become spaces, `linebreak` nodes only where the break carries
  meaning (§10.1).

### 3.7 Pages, furniture and the ledger

The Model has three top-level collections besides the tree:

* **`pages`**: per page — index, printed `label` (from `/PageLabels`, else a
  detected page number, else none), size, rotation, source kind (`vector`,
  `scanned`, `mixed`, `image`), resolution (raster), text-layer health (§9.3),
  and per-page variable furniture (e.g. a Bates number) that policy kept
  (§10.2).
* **`furniture`**: running headers/footers retained once, as templates.
* **`dropped`**: the ledger — every suppressed element with `reason`
  (`watermark`, `background`, `page-number`, `running-title`, `bleed-through`,
  `duplicate-header-row`, `ocr-ghost`, `clipped`, `offpage`, `invisible`),
  its region, its text if any, and the confidence of the decision.

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

A node is its index. Four languages cannot disagree about an index, and a
Model that is six integer runs is trivially serializable and trivially cheap
to hand across a WASM or C boundary.

Evidence and PageLayer are arenas of the same kind (`glyphs`, `words`,
`lines`, `paths`, `components`, `images`), defined in `emon.evidence`.

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
{"emon":"1.0","generator":"emon 0.1.0",
 "source":{"format":"pdf","version":"1.7","pages":2,"tagged":false,
           "encrypted":false,"lang":"en"},
 "settings":{"locale":null,"ocr":"always","detail":"blocks"},
 "conf":"uncalibrated",
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
* **Inline nodes** (`text`, `span`, `link`, `ref`, `linebreak`) carry no `id`
  (as in the example above); blocks do (D052). A node's own fields appear in
  the order `id`, `kind`, its kind's fields, `text`, `inlines`, `children`,
  `at`, `src`, `conf`, `flags`. `furniture` items have no `kind` (the array
  says what they are) and write `template` for their text. `settings` always
  carries `locale`; `furniture` and `dropped` are always present, `[]` when
  empty (D051).

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
it.

### 4.5 The evidence dump

Evidence and PageLayer have their own debug serialization (`emon-evidence`),
with the same canonical rules. It is not a public contract — it changes as the
algorithms do — but it is what fusion tests are written against, and what a
maintainer reads to answer "why did emon think that".

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
| `figure` | `<figure data-emon-kind="chart" role="img" aria-label="…">` + `<figcaption>`; **no `<img>`** |
| `seal` | `<figure data-emon-kind="stamp" data-emon-name="…" role="img" aria-label="Stamp: …"></figure>` |
| `formula` | `<figure data-emon-kind="formula">` with the linear text in `aria-label` (MathML parked) |
| `code` | `<pre><code>` |
| `quote` | `<blockquote>` |
| `note` | `<aside role="doc-footnote" id="fn-…">`; `ref` → `<a role="doc-noteref" href="#fn-…">` |
| `furniture` | `<aside data-emon="furniture" data-emon-role="header">` once, at the top of `<main>` |
| `break` | `<span data-emon-page="3" data-emon-page-label="iii"></span>` — empty milestone, inline or between blocks |
| `field` | `<span data-emon="field" data-emon-name="…">value</span>` (never a live `<input>`) |
| `span` styles | `<b>`, `<i>`, `<u>`, `<s>`, `<sub>`, `<sup>`, `<code>`, `<span data-emon-style="smallcaps">` |
| `link` | `<a href>`; internal targets become `#emon-ID` |
| `linebreak` | `<br>` |

`<b>`/`<i>` rather than `<strong>`/`<em>`: emon knows the text was bold, not
that the author meant importance. HTML should not claim what emon cannot know.

### 5.2 Options

`geometry` (adds `data-emon-at`), `ids` (adds `id="emon-N"` on every node),
`confidence` (`data-emon-conf` on nodes below a threshold), `ledger` (dropped
elements as `<template data-emon="dropped">` so they are inert but present),
`furniture` (`keep`/`drop`/`policy`), `pretty`. **Defaults are clean HTML**:
no ids, no geometry, policy furniture, milestones on. Escaping: `&`, `<`, `>`,
`"` and nothing else; attribute values always double-quoted.
The rest of the mapping this table leaves open (extra `data-emon-*` attributes,
forbidden code points, `pretty` layout, what a caption or a seal's label is)
is DEVLOG D068-D069; the code is `kin/html.kin`.

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
  * `Fx` — 16.16 fixed point in `I64` (PDF reals, matrices).
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
3. **`Bitmap` forms** — `bitmap-w`, `bitmap-h`, `bitmap-channels`,
   `bitmap-u8`, `bitmap-new`, `bitmap-set-u8`, plus a small number of
   **bulk** forms added *only* when the not-worse rule demands it by
   measurement (§12.3): e.g. `bitmap-row-sum`. Host-linked.
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
| `Bitmap` | 8-bit gray or RGB, row-major, top-left; new/read/write | pixel buffers differ; decoding is a codec's job |
| `ImageDecode` | encoded bytes → frames → `Bitmap` + resolution | four mature codec stacks exist; includes PDF's `DCTDecode`, `JPXDecode`, `JBIG2Decode`, `CCITTFaxDecode` (D005) |
| `Renderer` | PDF page + resolution → `Bitmap` | a correct PDF rasteriser is pdfium/MuPDF-sized (D007) |
| `Ocr` | `Bitmap` + request → words/lines/symbols with boxes and confidences | Tesseract is a C++ library; any engine may be substituted |
| `Crypto` | MD5, RC4, AES-128/256-CBC decrypt, SHA-256/384/512 | every standard library has them |
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
  engine that cannot mask says so and kin plans around it.
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

Cheap, deterministic ports (`Blob`, `Cells`, `Text`, `Bitmap` pixel access,
`Crypto`, limits) are called synchronously as in nome.

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
and in total; OCR requests and rounds; wall-clock per stage (host-enforced).

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

Words are formed from glyphs by gap analysis against the font's space width
and the `TJ` adjustments; lines by baseline and direction; the content-stream
order is kept as one weak reading-order signal.

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
cheaply (text rendered as outlines, images of tables, scanned inserts).

### 8.3 OCR (D033)

* **Default engine**: Tesseract (LSTM), bound natively in each host (Rust:
  the C API; Go: cgo; Java: JNI/Tess4J-style or the C API via Panama; C#: the
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

### 9.3 Health

Before comparing words, each source is scored **per region** (per font × page
for the text layer):

* **Text layer**: fraction of glyphs with a trustworthy mapping (`ToUnicode` or
  known encoding) versus guessed or unmapped; Private Use Area and control
  characters; CID-without-mapping patterns (`(cid:12)` garbage); impossible
  widths (glyph boxes overlapping heavily or with enormous gaps); and, once
  OCR is in, the **agreement rate** with OCR over words both see.
* **OCR**: engine confidence, dictionary-free plausibility (character-class
  transitions, e.g. `l1Il|` soup), ink support from the tracer (a word over
  blank paper is a ghost), and whether the region overlaps a seal, watermark
  or background layer.
* **Structure tree**: role diversity; agreement of its reading order with
  geometry; coverage (fraction of visible text reachable from it).

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

### 9.6 Confidence calibration

Raw scores from each rule are combined and **calibrated per node kind** by
isotonic mapping fitted on the evaluation corpora (§14), then committed as a
`defdata` table. Recalibration is a reviewed change with its reliability
diagram in the DEVLOG entry.

---

## 10. Analysis

### 10.1 Lines, blocks, reading order

* **Lines** from fused words by baseline and direction; **blocks** by
  spacing, alignment and style continuity (leading jumps, indentation, font
  change, rules between).
* **Columns and reading order**: recursive XY-cut over whitespace, refined
  for elements spanning columns (titles, full-width figures and tables), then
  column-major ordering; RTL scripts flip the horizontal order. Priors, in
  order of trust when healthy: tagged-PDF order, content-stream order, then
  geometry. They are *compared*, and disagreement lowers block confidence.
* **Meaningful line breaks** are kept (`linebreak`) where lines are
  consistently short and ragged in a way paragraphs are not: addresses,
  verse, signature blocks.

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

### 10.5 Tables (D019)

**Profiles** — detected independently; the best-scoring interpretation wins:

| Profile | Evidence |
| --- | --- |
| `ruled` | full grid of rules (vector paths or traced lines) → lattice → cells |
| `partial` | horizontal rules only (booktabs), column boundaries from text alignment |
| `shaded` | alternating row fills or header fills, columns from alignment |
| `whitespace` | no rules: ≥ 2 columns of consistent alignment across ≥ 3 lines, gaps wider than word gaps, numeric-column regularity |
| `form` | key/value grids (label cells + value cells), often ruled |
| `struct` | tagged-PDF `Table` roles, verified against geometry |

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
a `kind` and a confidence — never pixels (a scraper does not want them; an
embedder who does has the region and can crop):

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

Captions are linked by label pattern (`Figure 3`, `Fig. 3`, `Chart`,
`Exhibit`, locale forms) and proximity. Text inside a figure (axis labels,
diagram labels) is **not** body text; it may be kept as the figure's
`labels` (setting `figure-text: omit|labels`, default `labels` — a chart's
axis titles are often exactly what a scraper wanted). An image the PDF gives
alt text for (`/Alt` in the structure tree) keeps it as `alt`.

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

### 10.8 Footnotes, captions, links

Footnotes: a superscript or bracketed marker in body text matched to a
small-font block in the page's bottom band starting with the same marker →
`note` (`footnote`) + `ref`. Endnotes: the same matching against a trailing
"Notes" section. Captions: §10.6 and tables. Links: from annotations (§8.1),
plus bare URLs and e-mail addresses in text (a conservative recogniser; not a
guess at every dotted string).

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
| `sources` | subset of text/ocr/trace | all available |
| `dpi` | OCR render resolution | 300 |
| `detail` | `blocks` / `lines` / `words` | `blocks` |
| `pages` | range | all |
| `furniture` | `policy` / `keep` / `drop` | `policy` |
| `figure-text` | `omit` / `labels` | `labels` |
| `ledger` | on / off in outputs | on in JSON, off in HTML |
| `max-rounds` | plan/fulfil rounds | 3 |

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
   decoder is part of the comparison.
6. **repeat** — serial vs parallel, run vs run, forwards vs reversed page
   submission: byte-identical.
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
| Tables | TEDS and TEDS-structure-only; cross-page merge P/R |
| Furniture | P/R of dropped running elements; label accuracy |
| Figures | detection P/R; `kind` accuracy |
| Seals/signatures | detection P/R; `name`/`text` CER |
| Artifacts | watermark/ghost P/R — specifically, **body-text contamination rate**: characters of watermark/seal text that leaked into the body |
| Confidence | expected calibration error per kind |

### 14.3 Ground truth

1. **The synthetic round trip** (D041) — emon's best oracle. An HTML corpus
   (headings of known level, lists, tables including multi-page tables with
   and without `thead` repetition, running headers and page numbers,
   footnotes, figures with `data-` kinds) is printed to PDF by **external
   tools at development time** — headless Chromium's `printToPDF` as the
   primary producer, and optionally others (nome, WeasyPrint, LibreOffice)
   so emon does not overfit to one producer's idioms. The HTML *is* the
   ground truth. The PDFs are then rendered to images and **degraded**
   synthetically (noise, blur, skew, JPEG artefacts, low resolution, added
   watermarks, overlaid seals and signatures from a small licensed library,
   bleed-through), giving a scanned corpus whose truth is still known.
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

There is no README: this file and `DEVLOG.md` are the documentation, and a
newcomer needs only what is listed here to run every gate.

    bb check     generate, compile and run all four targets; one answer
    bb unit      the laws, as native tests on all four targets
    bb hosts     the four host trees expose the same ports
    bb drift     `gen/` is what the sources say (needs git)
    bb lang-test generation-time refusals of emon's own forms
    bb gates     all of the above; fails if a gate prints no score

**Tools.** `bb` (babashka) and `sh`; `git` (for `drift`); a checkout of
`kin` as a sibling directory (`../kin`, put on the classpath by `bb.edn`);
`python3` (for `unit`'s table and JSON-golden checks, which are tooling, not
runtime). Each target's toolchain is used if present and **skipped loudly**
if not: `rustc`/`cargo`, `go` and `gofmt`, a JDK (`/opt/homebrew/opt/openjdk`,
or `EMON_JDK`; macOS's `/usr/bin/javac` stub is detected and ignored),
`dotnet` (net10.0; xunit is restored on first `bb unit csharp`). The schema
check in `bb unit` needs Python's `jsonschema`; it is found on the path or in
`$EMON_PYLIB`, else installed once into `~/.cache/emon/pylib` (and skipped,
loudly, if that is impossible). The runtime and the generation path need
none of Python, git or a network.

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

1. **Figure pixels**: placeholders only (as specified), or an opt-in to emit
   crops through the host?
2. **Renderer in kin**: a kin PDF renderer would make the raster path deterministic across
   hosts and remove a port — at the cost of implementing PDF painting
   (shadings, blend modes, image codecs still host). Parked; revisit after
   Phase 6's numbers.
3. **nome as an optional corpus producer** (D041): may nome be one of the
   external dev-time PDF producers for the synthetic corpus (never a
   dependency), or should it be excluded?
