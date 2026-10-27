# The Model, and how HTML comes from it

emon converts a document in two steps. First it builds a **Model**: one tree of blocks (sections, headings,
paragraphs, lists, tables, figures, notes) with the page, the place on the page, the sources and a confidence for
every node. Then a writer turns the Model into text. The JSON writer is a faithful dump of the Model; the HTML
writer is a pure function of it: it adds nothing the Model does not hold and leaves out only what its options say.

The JSON form is described by [`schema/emon-1.json`](../schema/emon-1.json) (JSON Schema 2020-12), and every
conversion the project tests is validated against it. The tables on this page are generated from that schema by
`bb docs`, so a field added to the schema appears here the next time the docs are regenerated. The descriptions
are written by hand; a property the schema has and this page has no sentence for is listed as "not described yet".

<!-- bb docs:begin model-version -->
Versions `1.0` and `1.1` (schema id `https://emon.dev/schema/emon-1.json`).
<!-- bb docs:end model-version -->

A reader must ignore keys it does not know. A minor version only adds (a kind, a field, a flag, a reason); a
major version may change the meaning of a field.

## Reading it

Every host hands you the Model as an object you can walk (`root()`, `kind()`, `text()`, `children()`,
`attr(name)`, the regions and sources of a node: see the host pages) and can serialise it as JSON or HTML:

| What you want | Where |
|---|---|
| the words, in reading order | `text` of each leaf block (`paragraph`, `heading`, `cell`, ...), walking `children` |
| where a node is | `at`: regions `[page, x0, y0, x1, y1]`, in points, origin top-left, y down, rotation applied |
| how sure emon is | `conf`, 0..1000; it is calibrated: of the nodes of a kind with `conf` near 900, about 90 % are right |
| where the text came from | `src`: `text` (the PDF text layer), `ocr`, `trace`, `vector`, `struct`, `annot`, `outline`, `infer` |
| what to double-check | `flags`, and `conf` below a threshold of your choosing |
| what was left out | `dropped` (the ledger), and `furniture` (running headers and footers) |

Conventions that hold everywhere:

- **`text` first.** A leaf block carries `text`, its plain text in reading order. `inlines` appears only when it
  carries information (a style, a link, a note reference, a line break, a drop cap); when present, the
  concatenation of its text is `text`. A scraper never has to walk `inlines` to get words.
- **Absent is omitted**, never `null`, except `settings.locale`, where `null` is the answer.
- **Ids** are integers, unique in the Model, assigned in document order, and stable for the same input and
  settings: you can cite `id 57` across runs and across hosts. Inline nodes have none.
- **Geometry** is in points with at most two decimals, page space, origin at the top-left of the page as
  displayed (the PDF's rotation and crop box applied; an image's EXIF orientation applied).
- **Text** is Unicode in NFC, with the `ﬁ`-style ligatures expanded, soft hyphens at line ends removed, and
  right-to-left text in logical order. A line-end hyphen is kept unless a `locale` hint and a hyphenation table
  agree it is not part of the word; emon prefers an ugly hyphen to a corrupted compound.
- **Canonical bytes.** The same Model always serialises to the same bytes: a fixed key order for each kind, no
  insignificant whitespace (`pretty` adds a fixed two-space indent), integers and two-decimal geometry only.
  Every host gives the same bytes for the same input.

## The document

<!-- bb docs:begin model-top -->
| Field | Required | Type | Values | Meaning |
|---|---|---|---|---|
| `assets` |  | array of asset |  | Present only when `assets` is on: one entry per pixel asset (metadata only; the bytes travel out of band). |
| `body` | yes | document |  | The `document` node: the tree of blocks, in reading order. |
| `conf` | yes | constant `"calibrated"` |  | Always `calibrated`: every `conf` in the document is on the calibrated 0..1000 scale. |
| `dropped` | yes | array of droppedItem |  | The ledger: what the pipeline saw and left out of the body, and why. |
| `emon` | yes | one of | `1.0`, `1.1` | Schema version of the document, `1.0` or `1.1`. A reader must ignore keys it does not know: a later minor version only adds. |
| `furniture` | yes | array of furnitureItem |  | Running headers, footers and margin items kept by the furniture policy, each with the pages it recurs on. |
| `generator` |  | string |  | What produced the document, when given. |
| `meta` |  | object |  | The PDF's own metadata (`title`, `author`, `subject`, `keywords`, `creator`, `producer`, `created`, `modified`), whichever it has. |
| `pages` | yes | array of page |  | One entry per page, numbered across all the inputs in the order they were given. |
| `settings` | yes | object |  | The settings the conversion ran with: `locale` always; `ocr`, `ocr-scope`, `detail`, `assets` and the `asset-*` ones only when they are not the default. |
| `source` |  | object |  | What the input was: `format`, `version`, `pages`, `tagged`, `encrypted`, `lang`. |
<!-- bb docs:end model-top -->

The `body` is a `document` node:

<!-- bb docs:begin model-document -->
| Field | Required | Type | Values | Meaning |
|---|---|---|---|---|
| `id` | yes | constant `0` |  | Always 0. |
| `kind` | yes | constant `"document"` |  | Always `document`. |
| `at` |  | list of regions |  | As for a block. |
| `children` |  | array of block |  | The document's blocks and sections, in reading order. |
| `conf` |  | integer 0..1000 |  | As for a block. |
| `flags` |  | list of flags |  | As for a block. |
| `inlines` |  | array of inline |  | Reserved; absent today. |
| `src` |  | list of sources |  | As for a block. |
| `text` |  | string |  | Reserved; absent today. |
<!-- bb docs:end model-document -->

## Pages

<!-- bb docs:begin model-page -->
| Field | Required | Type | Values | Meaning |
|---|---|---|---|---|
| `dpi` |  | integer >= 1 |  | Resolution of a scanned page or an image, when known. |
| `h` | yes | number (points) |  | Page height in points, as displayed. |
| `health` |  | integer 0..1000 |  | Confidence, 0..1000, that the page's text layer can be trusted. |
| `kind` |  | one of | `vector`, `scanned`, `mixed`, `image` | How the page was read: `vector` (a text layer), `scanned` (pixels only), `mixed`, `image` (an image input). |
| `label` |  | string |  | The printed page label (`iii`, `A-4`), when the document has one. |
| `layout` |  | array of object |  | The column map reading order was decided over: one cell per column fragment (`band`, `col`, `role`, and `box` as `[x0, y0, x1, y1]`). Absent when the page is one plain column. |
| `n` | yes | integer >= 1 |  | Page number, from 1, counted across all inputs. |
| `px` |  | array of integer >= 0 |  | Pixel size `[width, height]` of a scanned page or an image. |
| `rot` | yes | one of | `0`, `90`, `180`, `270` | The page's rotation in degrees. |
| `w` | yes | number (points) |  | Page width in points, as displayed (rotation applied). |
<!-- bb docs:end model-page -->

## Blocks

A block's `kind` says what it is; the other fields below say more about some kinds. The table gives, for each
kind, what it contains, its key fields, and the HTML the writer makes of it.

<!-- bb docs:begin model-kinds -->
| Kind | Children / what | Key fields | HTML |
|---|---|---|---|
| `document` | sections, blocks |  | `<main>` in a full page |
| `section` | `heading`, blocks, nested sections | `level` | `<section>` |
| `heading` | inlines | `level`, `number`, `numbering`, `align` | `<h1>` to `<h6>`; level over 6 is `<h6 aria-level="n">` |
| `paragraph` | inlines | `align` | `<p>` |
| `list` | `item`s | `ordered`, `scheme`, `start` | `<ul>`, or `<ol start type>` |
| `item` | inlines, nested blocks | `marker`, `value` | `<li value>`; the literal marker as `data-emon-marker` |
| `table` | `caption`, `rowgroup`s | `profile`, `continued`, `columns` | `<table>` with `<thead>`, `<tbody>`, `<tfoot>` |
| `rowgroup` | `row`s | `role` | `<thead>` / `<tbody>` / `<tfoot>` |
| `row` | `cell`s | `repeated` | `<tr>` |
| `cell` | inlines or blocks | `header`, `rowspan`, `colspan`, `empty`, `align` | `<th scope>` or `<td rowspan colspan>` |
| `caption` | inlines | `label`, `target`, `align` | `<caption>` in a table, `<figcaption>` in a figure |
| `figure` | `caption` | `subkind`, `label`, `alt`, `labels`, `asset`, `noasset` | `<figure data-emon-kind role="img" aria-label>`; an `<img>` only when it has an asset |
| `seal` |  | `subkind`, `name`, `text`, `date`, `color`, `asset`, `signed`, `reason`, `location` | `<figure data-emon-kind="stamp" data-emon-name role="img">` |
| `formula` |  | `text`, `display` | `<figure data-emon-kind="formula">` with the linear text in `aria-label` |
| `code` | inlines |  | `<pre><code>` |
| `quote` | blocks |  | `<blockquote>` |
| `note` | blocks | `role`, `marker` | `<aside role="doc-footnote" id="fn-N">`; a margin or sidebar note is `<aside data-emon-role>` |
| `field` | inlines | `name`, `type`, `value`, `checked` | `<span data-emon="field" data-emon-name>`; never a live `<input>` |
| `furniture` | blocks | `role`, `pages` | `<aside data-emon="furniture" data-emon-role>` |
| `break` |  | `page`, `label` | an empty `<span data-emon-page data-emon-page-label>` |
<!-- bb docs:end model-kinds -->

All the fields a block can have (one flat definition: the "Meaning" column says which kinds use a field):

<!-- bb docs:begin model-block -->
| Field | Required | Type | Values | Meaning |
|---|---|---|---|---|
| `id` | yes | integer >= 0 |  | Integer, unique in the Model, assigned in document order. Stable for the same input and settings, so ids can be cited across runs and hosts. |
| `kind` | yes | one of | `document`, `section`, `heading`, `paragraph`, `list`, `item`, `table`, `rowgroup`, `row`, `cell`, `caption`, `figure`, `seal`, `formula`, `code`, `quote`, `note`, `field`, `furniture`, `break` | What the node is: see the table of kinds. |
| `align` |  | one of | `start`, `center`, `end`, `justify` | `paragraph`, `heading`, `caption`, `cell`: logical alignment, written only when it was detected with confidence. |
| `alt` |  | string |  | `figure`: the alternative text the PDF gave it. |
| `asset` |  | integer >= 0 |  | `figure`, `seal`: the id of the asset holding its pixels (only when `assets` is on). |
| `at` |  | list of regions |  | Where the node is: regions `[page, x0, y0, x1, y1]` in points, top-left origin, y down. A node spanning pages or columns has one region per fragment. |
| `checked` |  | boolean |  | `field`: whether a checkbox or radio button is on. |
| `children` |  | array of block |  | Child blocks, in reading order. |
| `color` |  | string |  | `seal`: its colour. |
| `colspan` |  | integer >= 1 |  | `cell`: columns spanned. |
| `columns` |  | integer >= 1 |  | `table`: the number of columns. |
| `conf` |  | integer 0..1000 |  | Confidence that the node is right, 0..1000, calibrated. |
| `continued` |  | integer >= 1 |  | `table`: how many pages it spans, when more than one. |
| `date` |  | string |  | `seal`: the date on it. |
| `display` |  | one of | `block`, `inline` | `formula`: `block` or `inline`. |
| `empty` |  | boolean |  | `cell`: the cell holds nothing. |
| `flags` |  | list of flags |  | Warnings, from a closed set: `low-conf`, `ocr-only`, `text-layer-suspect`, `reconstructed`, `dpi-unknown`, `rotated`, `vertical`, `struct-disagree`, `tag-span-conflict`, `in-table-image`, `script-unread`. |
| `header` |  | one of | `col`, `row` | `cell`: `col` or `row` for a header cell. |
| `inlines` |  | array of inline |  | The rich form of the text: runs, spans, links, note references, line breaks, drop caps, page milestones. Present only when it carries information (a style, a link, a note reference, a line break, a drop cap). |
| `label` |  | string |  | `caption`, `figure`: the label (`Table 3`, `Figure 2`). |
| `labels` |  | array of string |  | `figure`: text found inside the picture. |
| `level` |  | integer >= 1 |  | `section` and `heading`: the depth, from 1. The HTML writer stops at `h6` and adds `aria-level` beyond it. |
| `location` |  | string |  | `seal` (`digital-signature`): the signature's stated location. |
| `marker` |  | string |  | `item`, `note`: the marker as printed. |
| `name` |  | string |  | `seal`: the name on it (or the signer); `field`: the field's name. |
| `noasset` |  | one of | `withheld`, `part`, `vector`, `composite`, `colorspace`, `codec`, `render`, `too-large`, `budget` | `figure`, `seal`: why it has none, when `assets` is on. |
| `number` |  | string |  | `heading`: the section number as printed (`3.2.1`). |
| `numbering` |  | string |  | `heading`: the numbering scheme. |
| `ordered` |  | boolean |  | `list`: whether the items are numbered. |
| `page` |  | integer >= 1 |  | `break`: the page number the milestone starts. |
| `pages` |  | array of integer >= 1 |  | `furniture`: the pages the recurring item appears on. |
| `profile` |  | one of | `ruled`, `partial`, `shaded`, `whitespace`, `form`, `struct` | `table`: how the table was found: `ruled`, `partial`, `shaded`, `whitespace`, `form`, `struct`. |
| `reason` |  | string |  | `seal` (`digital-signature`): the signature's stated reason. |
| `repeated` |  | boolean |  | `row`: a recurring header row that was folded into one. |
| `role` |  | one of | `head`, `body`, `foot`, `footnote`, `endnote`, `margin`, `sidebar`, `header`, `footer` | `rowgroup`: `head`, `body`, `foot`. `note`: `footnote`, `endnote`, `margin`, `sidebar`. `furniture`: `header`, `footer`, `margin`. |
| `rowspan` |  | integer >= 1 |  | `cell`: rows spanned. |
| `scheme` |  | string |  | `list`: the marker scheme (`decimal`, `lower-alpha`, `upper-roman`, `bullet`, ...). |
| `signed` |  | boolean |  | `seal` (`digital-signature`): whether the PDF carries a signature value. Nothing is cryptographically verified. |
| `src` |  | list of sources |  | Which sources contributed: `text` (the PDF text layer), `hidden-text`, `ocr`, `trace` (pixels), `vector` (drawn rules and fills), `struct` (the tag tree), `annot`, `outline`, `infer`. |
| `start` |  | integer |  | `list`: the first number. |
| `subkind` |  | one of | `chart`, `diagram`, `photo`, `logo`, `map`, `illustration`, `screenshot`, `qr`, `barcode`, `unknown`, `seal`, `stamp`, `signature`, `initials`, `digital-signature` | `figure`: `chart`, `diagram`, `photo`, `logo`, `map`, `illustration`, `screenshot`, `qr`, `barcode`, `unknown`. `seal`: `seal`, `stamp`, `signature`, `initials`, `digital-signature`. |
| `target` |  | integer >= 0 |  | `caption`: the id of the table or figure it describes. |
| `text` |  | string |  | The block's plain text. When the block has `inlines`, the concatenation of their text. |
| `type` |  | string |  | `field`: the kind of form field. |
| `value` |  | integer or string |  | `item`: the item's number; `field`: a text field's value. |
<!-- bb docs:end model-block -->

## Inlines

Inline nodes are the runs inside a block's `inlines`. They have no `id`.

<!-- bb docs:begin model-inline-kinds -->
| Kind | Children / what | Key fields | HTML |
|---|---|---|---|
| `text` | a run of characters | `text` | the text, escaped |
| `span` | a styled run | `style` | `<b>`, `<i>`, `<u>`, `<s>`, `<sub>`, `<sup>`, `<code>`; small caps as `<span data-emon-style="smallcaps">` |
| `link` | a link | `href` or `target` | `<a href>`; an internal target is `#emon-ID` |
| `ref` | a note reference | `marker`, `target` | `<a role="doc-noteref" href="#fn-N">` |
| `linebreak` | a meaningful hard line break |  | `<br>` |
| `initial` | a drop cap or a kin of it | `form`, `lines` | `<span data-emon="initial" data-emon-form data-emon-lines>`; the text is kept whole |
| `break` | a page milestone inside a paragraph | `page`, `label` | an empty `<span data-emon-page>` |
| `field` | an AcroForm field in the block that holds its label | `name`, `type`, `value`, `checked` | `<span data-emon="field" data-emon-name data-emon-type>` with the value as content; a checkbox or radio button is an empty span with `role`, `aria-checked`, `data-emon-checked` and `data-emon-value` |
<!-- bb docs:end model-inline-kinds -->

<!-- bb docs:begin model-inline -->
| Field | Required | Type | Values | Meaning |
|---|---|---|---|---|
| `kind` | yes | one of | `text`, `span`, `link`, `ref`, `linebreak`, `initial`, `break`, `field` | What the run is: see the table of inline kinds. |
| `at` |  | list of regions |  | Regions of the run (only at the finest detail); on a `field`, the widget's rectangle `[page, x0, y0, x1, y1]`. |
| `checked` |  | boolean |  | `field`: whether a checkbox or radio button is on. |
| `children` |  | array of inline |  | Nested inlines (a `span`, `link` or `initial` wrapping runs). |
| `conf` |  | integer 0..1000 |  | As for a block; a `field`'s says how well the value and the widget's appearance agree. |
| `flags` |  | list of flags |  | As for a block. |
| `form` |  | one of | `drop`, `raised`, `margin` | `initial`: `drop`, `raised` or `margin`. |
| `href` |  | string |  | `link`: an external URI. |
| `label` |  | string |  | `break`: the printed page label. |
| `lines` |  | integer >= 2 |  | `initial`: how many lines a drop or margin initial spans. |
| `marker` |  | string |  | `ref`: the note marker as printed. |
| `name` |  | string |  | `field`: the form field's full name. |
| `page` |  | integer >= 1 |  | `break`: the page number. |
| `src` |  | list of sources |  | As for a block; a `field` read from an AcroForm is `annot`. |
| `style` |  | array of values | `bold`, `italic`, `underline`, `strike`, `sub`, `sup`, `mono`, `smallcaps` | `span`: the styles in force: `bold`, `italic`, `underline`, `strike`, `sub`, `sup`, `mono`, `smallcaps`. |
| `target` |  | integer >= 0 |  | `link`, `ref`: the id of the node it points to. |
| `text` |  | string |  | The run's text. |
| `type` |  | string |  | `field`: `checkbox`, `radio`, `text`, `combo` or `list`. |
| `value` |  | string |  | `field`: a text, combo or list field's value; for a checkbox or radio button, its export value. |
<!-- bb docs:end model-inline -->

## Furniture

Running headers, footers and margin items are kept once, as templates, with the pages they recur on, and
(with the default `furniture=policy`) left out of the flow. A furniture item has no `kind`: the array says what it is.

<!-- bb docs:begin model-furniture -->
| Field | Required | Type | Values | Meaning |
|---|---|---|---|---|
| `id` | yes | integer >= 0 |  | As for a block. |
| `at` |  | list of regions |  | As for a block. |
| `children` |  | array of block |  | The recurring content, as blocks. |
| `conf` |  | integer 0..1000 |  | As for a block. |
| `flags` |  | list of flags |  | As for a block. |
| `pages` |  | array of integer >= 1 |  | The pages it recurs on. |
| `role` |  | one of | `header`, `footer`, `margin` | `header`, `footer` or `margin`. |
| `src` |  | list of sources |  | As for a block. |
| `template` |  | string |  | The recurring text with its page number replaced by a placeholder. |
<!-- bb docs:end model-furniture -->

## The ledger: `dropped`

Everything emon saw and left out of the body is accounted for here, with the reason. A watermark that the
OCR also read, a page number, a row that repeated a header: if it is not in the body, it is here.

<!-- bb docs:begin model-dropped -->
| Field | Required | Type | Values | Meaning |
|---|---|---|---|---|
| `at` | yes | region `[page, x0, y0, x1, y1]` |  | Its region `[page, x0, y0, x1, y1]`. |
| `conf` |  | integer 0..1000 |  | How sure the pipeline was. |
| `page` | yes | integer >= 1 |  | The page it was on. |
| `reason` | yes | one of | `watermark`, `background`, `page-number`, `running-title`, `bleed-through`, `duplicate-header-row`, `ocr-ghost`, `clipped`, `offpage`, `invisible`, `rule-ghost`, `unwitnessed-uncertain-paint`, `ocr-empty`, `ocr-unrepresented`, `figure-unmeasured`, `figure-signature-unmeasured`, `ocr-unread-ink` | Why it was left out: `watermark`, `background`, `page-number`, `running-title`, `bleed-through`, `duplicate-header-row`, `ocr-ghost`, `clipped`, `offpage`, `invisible`, `rule-ghost`, `unwitnessed-uncertain-paint`, `ocr-empty`, `ocr-unrepresented`, `figure-unmeasured`, `figure-signature-unmeasured`, `ocr-unread-ink` (a text-like source trace with no recorded OCR word; no transcription is inferred). |
| `src` |  | list of sources |  | As for a block. |
| `text` |  | string |  | Its text, when it had any. |
<!-- bb docs:end model-dropped -->

## Assets

When `assets` is `images` or `figures`, `assets` lists the pictures of figures and seals. The Model holds their
metadata only; the bytes are out of band (a host method such as `asset.bytes()`), and the HTML can inline them
as `data:` URIs. With `assets=none` the output is byte for byte what it is without the feature.

<!-- bb docs:begin model-asset -->
| Field | Required | Type | Values | Meaning |
|---|---|---|---|---|
| `id` | yes | integer >= 0 |  | Asset id (what a block's `asset` names). |
| `bytes` | yes | integer >= 0 |  | Size of the encoded bytes. |
| `dpi` |  | integer >= 1 |  | Effective resolution, when known. |
| `flags` |  | array of values | `overlaid`, `clip-bbox`, `transformed`, `icc`, `scaled`, `text-overlap` | Notes on how it was made: `overlaid`, `clip-bbox`, `transformed`, `icc`, `scaled`, `text-overlap`. |
| `from` | yes | one of | `dct`, `jpx`, `jbig2`, `ccitt`, `flate`, `lzw`, `runlength`, `raw`, `png`, `jpeg`, `tiff`, `render` | What it came from: the PDF image filter (`dct`, `flate`, `ccitt`, `jpx`, `jbig2`, `lzw`, `runlength`, `raw`), an image format (`png`, `jpeg`, `tiff`) or `render`. |
| `h` | yes | integer >= 0 |  | Height in pixels. |
| `name` | yes | string |  | The file name to write it as: `a<id>.jpg` or `a<id>.png`. |
| `object` |  | integer >= 0 |  | The PDF object number it came from. |
| `origin` | yes | one of | `embedded`, `decoded`, `cropped`, `rendered` | `embedded` (the PDF's own image, passed through), `decoded` (decoded and re-encoded), `cropped` (cut from a render), `rendered` (a render of a region). |
| `sha256` | yes | string |  | SHA-256 of the encoded bytes, hex. |
| `type` | yes | one of | `image/jpeg`, `image/png` | Media type of the encoded bytes. |
| `uses` | yes | array of object |  | Where it is placed: `node` (a block id) and `at` (a region). |
| `w` | yes | integer >= 0 |  | Width in pixels. |
<!-- bb docs:end model-asset -->

## From the Model to HTML

The HTML is semantic and unstyled. It uses the element a reader would expect and puts what HTML has no word for in
`data-emon-*` attributes. It never produces a live `<input>`, and an `<img>` only for an asset. The default is
clean HTML: no ids, no geometry, policy furniture, page milestones on. The options are in
[docs/options.md](options.md).

A table, from the sample `samples/invoice.pdf`: the Model has a `table` with a `rowgroup` (`head`), a `rowgroup`
(`body`), `row`s and `cell`s, each cell with its `text`; the HTML is (line breaks and indentation added here, nothing
else; the file has none):

<!-- excerpt: samples/invoice.html | <table> | </table> -->
```html
<table>
  <thead>
    <tr>
      <th scope="col">Item</th>
      <th scope="col" data-emon-align="start">Description</th>
      <th scope="col">Qty</th>
      <th scope="col">Unit price</th>
      <th scope="col">Amount</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>A-100</td>
      <td data-emon-align="start">Copper pipe 15mm x 3m</td>
      <td data-emon-align="end">40</td>
      <td data-emon-align="end">6.50</td>
      <td data-emon-align="end">260.00</td>
    </tr>
    <tr>
      <td>B-220</td>
      <td data-emon-align="start">Elbow joint 15mm</td>
      <td data-emon-align="end">120</td>
      <td data-emon-align="end">0.85</td>
      <td data-emon-align="end">102.00</td>
    </tr>
    <tr>
      <td>C-310</td>
      <td data-emon-align="start">Flux paste 100g</td>
      <td data-emon-align="end">12</td>
      <td data-emon-align="end">4.20</td>
      <td data-emon-align="end">50.40</td>
    </tr>
    <tr>
      <td>D-005</td>
      <td data-emon-align="start">PTFE tape</td>
      <td data-emon-align="end">60</td>
      <td data-emon-align="end">0.45</td>
      <td data-emon-align="end">27.00</td>
    </tr>
    <tr>
      <td>E-900</td>
      <td data-emon-align="start">Delivery</td>
      <td data-emon-align="end">1</td>
      <td data-emon-align="end">25.00</td>
      <td data-emon-align="end">25.00</td>
    </tr>
  </tbody>
</table>
```

What each element means, besides the column in the table of kinds:

| HTML | From the Model |
|---|---|
| `<main>` | the `document`; `<html>`, `<head>`, `<meta charset>` and `<title>` wrap it |
| `<section>`, `<h1>`, `<h2>`, `<h3>`, `<h4>`, `<h5>`, `<h6>` | `section` and its `heading`; a level over 6 is `<h6 aria-level="n">`; the printed number is kept in `data-emon-number` |
| `<p>` | `paragraph` |
| `<ul>`, `<ol start>`, `<li>` | `list` and `item`; the literal marker (`1.`, `(a)`, a bullet) in `data-emon-marker` |
| `<table>`, `<thead>`, `<tbody>`, `<tfoot>`, `<tr>`, `<th scope>`, `<td>` | `table`, `rowgroup`, `row`, `cell`; spans as `rowspan` and `colspan` |
| `<caption>`, `<figcaption>` | `caption`; its printed label in `data-emon-label` |
| `<figure>` | `figure` and `seal`; the `subkind` in `data-emon-kind`, with `role="img"` and an `aria-label`; no `<img>` unless the figure has an asset |
| `<img>` | an asset (`assets=images` or `figures`), with `src`, `alt`, `width`, `height` and `data-emon-asset` |
| `<aside>` | a note (`role="doc-footnote"`), a margin or sidebar note (`data-emon-role`), or furniture (`data-emon="furniture"`) |
| `<a>` | a `link`, or a `ref` to a note (`role="doc-noteref"`) |
| `<b>`, `<i>`, `<u>`, `<s>`, `<sub>`, `<sup>`, `<code>`, `<br>` | `span` styles and `linebreak` |
| `<pre>`, `<blockquote>` | `code` and `quote`, which the schema and the writer have and the pipeline does not produce yet (deferred until after the first release, like `formula`) |
| `<span>` | an empty page milestone, a drop cap (`data-emon="initial"`), a chart label (`data-emon="label"`; one space between two, so a chart's text reads as tokens), a form field (`data-emon="field"`) |

The `data-emon-*` attributes:

| Attribute | Meaning |
|---|---|
| `data-emon` | what a `<span>` or `<aside>` is: `furniture`, `initial`, `label`, `field`, `dropped` |
| `data-emon-align` | `start`, `center`, `end` or `justify`, logical; `align-style` adds `style="text-align:..."` too |
| `data-emon-asset` | the `id` of the asset an `<img>` shows |
| `data-emon-color`, `data-emon-date`, `data-emon-name` | the colour, the date and the name on a seal or stamp; on a form field, its full name |
| `data-emon-type` | a form field's type: `checkbox`, `radio`, `text`, `combo` or `list` |
| `data-emon-checked` | a checkbox or radio button's state, `true` or `false` (with `aria-checked` and `role`) |
| `data-emon-value` | a checkbox or radio button's export value (a text field's value is its content) |
| `data-emon-form`, `data-emon-lines` | a drop cap's form (`drop`, `raised`, `margin`) and the lines it spans |
| `data-emon-kind` | a figure's or seal's `subkind` |
| `data-emon-label` | the printed label of a figure or caption (`Figure 2.`, `Table 1`) |
| `data-emon-marker` | a list item's literal marker |
| `data-emon-number` | a heading's printed section number |
| `data-emon-page`, `data-emon-page-label` | a page milestone: the page number and its printed label |
| `data-emon-pages` | the pages a furniture aside recurs on |
| `data-emon-role` | `header`, `footer` or `margin` for furniture; `margin` or `sidebar` for a note |
| `data-emon-at`, `data-emon-col` | with the `geometry` word: the regions of a node and its `page:band:col` cell |
| `data-emon-conf` | with `confidence=N`: the confidence of a node below N |
| `data-emon-signed`, `data-emon-reason`, `data-emon-location` | a digital signature's state; nothing is cryptographically verified |

Only the attributes that appear in the committed [samples](../samples/) are checked against this page by
`bb docs --check`; the rest is from the specification (`SPEC.md` section 5) and the writer, `kin/html.kin`.

Escaping is `&`, `<`, `>` and `"` and nothing else; attribute values are always double-quoted.
