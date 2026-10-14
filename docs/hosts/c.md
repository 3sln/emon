# emon for C

`libemon` and its header [`emon.h`](../../host/c/include/emon.h): the Rust host behind a C ABI. Get the archive
`emon-c-<version>-<triple>.tar.gz` from the [GitHub releases](https://github.com/3sln/emon/releases):

    include/emon.h
    lib/libemon.dylib | .so            the shared library
    lib/libemon.a                      the static library
    lib/libpdfium.dylib | .so          pdfium, loaded at run time
    share/emon/tessdata/               the English and orientation models (eng, osd)

Link the shared library with `-I include -L lib -lemon` (and `-Wl,-rpath,<lib dir>` to run it from where it is: the library is
named `@rpath/libemon.dylib` on macOS and has the soname `libemon.so` on Linux). The static library holds Tesseract and
Leptonica, so it needs the C++ runtime and the system's own libraries too: `-lstdc++ -lpthread -ldl -lm` on Linux, `-framework
Security -framework CoreFoundation -liconv -lm -lc++` on macOS. C11; the header is C++ safe.

On Windows (x64, Windows 10 or newer) the library is built with the static C runtime (`/MT`) and needs no Visual C++
redistributable. A program that links the static library should be built with `/MT` too, so that the runtimes agree.

**The released library has the whole pipeline.** Tesseract 5.5.3 and Leptonica are linked into it statically, and pdfium
(`chromium/8076`) is loaded at run time from the file beside it, so a page that needs drawing and reading just works:
`emon_capabilities()` has `EMON_CAP_PDFIUM` and `EMON_CAP_TESSERACT` set when both are found. There is still no JPEG 2000 or
JBIG2 decoder (`EMON_CAP_CODECS` is 0). The ports are used when you pass the `EMON_NATIVE` flag to a one-shot call; without
it, or with the step API, a request for a port is answered `unsupported` (or you answer it yourself, see below).

Where each is looked for, in order. A place you *name* that is wrong is an error, never replaced by the next one; nothing
found at all is no capability bit and no error.

| | pdfium | the Tesseract models |
|---|---|---|
| 1 | `emon_set_native_paths(pdfium, tessdata, &err)`: a file or the directory holding it / a directory with `*.traineddata` | the same call |
| 2 | `$EMON_PDFIUM_LIB` | `$EMON_TESSDATA` |
| 3 | next to the library (`lib/libpdfium.*` beside `lib/libemon.*`); a static link looks next to the executable | `share/emon/tessdata` one directory above `lib/` (the archive's), or beside the executable |
| 4 | | `$TESSDATA_PREFIX` and the system's usual places |

The same layout is the CLI's ([docs/cli.md](../cli.md)) and the pod's ([docs/hosts/pod.md](pod.md)); put the archive's
directories anywhere together and they work. On macOS a downloaded library is quarantined until you run
`xattr -dr com.apple.quarantine <dir>` (the files are not signed).

## First conversion

<!-- snippet: docs/snippets/c/quickstart.c -->
```c
/* pdf, len: the document's bytes (emon copies them) */
emon_input in = {0};
in.data = pdf;
in.len = len;
emon_buf html = {0};
emon_error err = {0};
if (emon_convert_html(&in, NULL, 0, NULL, &html, &err) < 0) {
    fprintf(stderr, "%s: %s\n", err.name, err.message);
    emon_free_error(&err);
    return 1;
}
FILE *out = fopen("report.html", "wb");
fwrite(html.data, 1, html.len, out); /* html.data[html.len] is a NUL */
fclose(out);
emon_free_buf(&html);
```

`emon_convert_html` is one call: bytes in (copied), HTML out. This program is compiled with `-std=c11 -Wall -Werror` and run
against the built archive, linked both ways, by `bb package --check`.

## The shape of the API

| You | C |
|---|---|
| give a document | `emon_input`: `data`/`len` (copied), or `source`, a lazy `emon_source` (a `length` and a `read_at(ctx, offset, buf, len)` callback of yours, called on emon's worker thread, one call at a time: the engine reads only the spans it touches, in chunks); `password`/`password_len` for an encrypted PDF |
| say what to do | the `options` argument, `key=value` lines separated by LF (`"ocr=never\nlimit.pages=500"`), `NULL` for the defaults ([docs/options.md](../options.md)); `emon_check_options` parses a text and says what is wrong; `emon_is_option_key` asks about a name |
| convert | one-shot: `emon_convert` (a Model handle), `emon_convert_json`, `emon_convert_html` (a buffer), `emon_intake` (stage 0 alone: `pdf 1.4 pages=3 encrypted=false bytes=1234`); or the step API: `emon_open`, `emon_step`, `emon_provide`, `emon_finish` |
| read the result | `emon_model_json`, `emon_model_html` (the `html_options` text: a comma list of the [HTML words](../options.md#html-words)), `emon_model_evidence`, `emon_model_stats`, and the assets: `emon_model_asset_count`, `emon_model_asset_info`, `emon_model_asset_bytes`. The Model is given as JSON and HTML and assets, not as a walk of nodes |
| route on a failure | every function answers 0 (or a positive status) or `-code`, and fills an `emon_error` you pass: `code` is a category (`EMON_ERR_*`), `name` the stable name, `message` the sentence. Start it zeroed; `emon_free_error` it |
| free | emon allocates, you free with the matching `emon_free_*` (`emon_free_buf`, `emon_free_model`, `emon_free_error`, `emon_free_step`, `emon_free_files`, `emon_free_asset_info`). An `emon_buf` has a NUL after its `len` bytes, so JSON and HTML are C strings |

The error categories and names are in [docs/options.md](../options.md#errors). A panic inside emon is the error `internal`
(`EMON_ERR_INTERNAL`): it never crosses the boundary. A handle (`emon_session`, `emon_model`) is used by one thread at a time;
different handles may be used by different threads. The engine works on its own 32 MiB-stack thread, so your thread's stack
size does not matter.

## A tour

<!-- snippet: docs/snippets/c/tour.c -->
```c
/* pdf, len: the document's bytes */
printf("emon %s, native ports: %u\n", emon_version(), emon_capabilities());

/* One text says what to do, limits included (docs/options.md). */
const char *options = "ocr=never\nlimit.pages=500";
emon_error err = {0};
if (emon_check_options(options, &err) < 0) {
    fprintf(stderr, "bad options: %s\n", err.message);
    emon_free_error(&err);
    return 2;
}

emon_input in = {0};
in.data = pdf;
in.len = len;

/* Stage 0 alone is cheap: "pdf 1.4 pages=3 encrypted=false bytes=1234". */
emon_buf line = {0};
if (emon_intake(&in, options, 0, &line, &err) == 0) {
    printf("%s\n", (const char *)line.data);
    emon_free_buf(&line);
}

emon_model *model = NULL;
if (emon_convert(&in, options, 0, &model, &err) < 0) {
    /* err.name is the stable name to route on, err.code its category */
    fprintf(stderr, "%s (%s): %s\n", err.name, emon_error_category(err.code), err.message);
    emon_free_error(&err);
    return 1;
}
emon_buf json = {0};
if (emon_model_json(model, 0, &json, &err) == 0) {
    printf("%zu bytes of JSON, %d assets\n", json.len, emon_model_asset_count(model));
    emon_free_buf(&json);
}
emon_free_model(model);
```

## Scans and figures: pdfium and Tesseract

<!-- snippet: docs/snippets/c/native.c -->
```c
/* pdf, len: a scanned document's bytes */
emon_error err = {0};
/* Optional: where pdfium and the models are. Without it: $EMON_PDFIUM_LIB and $EMON_TESSDATA, then lib/ and share/emon/tessdata of the archive. */
if (emon_set_native_paths(NULL, NULL, &err) < 0) {
    fprintf(stderr, "%s: %s\n", err.name, err.message);
    emon_free_error(&err);
    return 1;
}
if (!(emon_capabilities() & EMON_CAP_PDFIUM) || !(emon_capabilities() & EMON_CAP_TESSERACT)) {
    fprintf(stderr, "pdfium or the Tesseract models were not found next to the library\n");
    return 1;
}

emon_input in = {0};
in.data = pdf;
in.len = len;
emon_buf html = {0};
if (emon_convert_html(&in, NULL, EMON_NATIVE, NULL, &html, &err) < 0) { /* EMON_NATIVE: pdfium draws, Tesseract reads */
    fprintf(stderr, "%s: %s\n", err.name, err.message);
    emon_free_error(&err);
    return 1;
}
FILE *out = fopen("scan.html", "wb");
fwrite(html.data, 1, html.len, out);
fclose(out);
emon_free_buf(&html);
```

`bb package --check` compiles this against the built archive and runs it on `samples/scan-plain.pdf`, linked both ways, with no
`EMON_*` variable in the environment (the shared library finds `lib/libpdfium.*` and `share/emon/tessdata` by itself; the static
link is given them through the environment, since the executable is not beside them), and requires the heading the OCR reads. It
runs where `bb native` and `bb pdfium-fetch` have been run, and prints a NOTE and skips on a machine that has neither.

## The step API: when the engine owns nothing and you own everything

`emon_open` / `emon_step` / `emon_finish` is the inverted loop of the browser binding, for a caller that owns the byte source
(a network range reader, an object store, a memory-mapped file) and the fulfilment of what needs pixels. The engine never calls
out: `emon_step` runs until it needs something and says what.

<!-- snippet: docs/snippets/c/session.c -->
```c
/* pdf, len: the document, which only you can read. The engine never calls out: it says what it needs, you do it. */
emon_session *s = NULL;
emon_error err = {0};
if (emon_open(len, 0, 0, NULL, 0, "ocr=never", &s, &err) < 0) {
    fprintf(stderr, "%s: %s\n", err.name, err.message);
    return 1;
}
emon_step_out out = {0};
int status;
while ((status = emon_step(s, &out, &err)) > 0) {
    if (status == EMON_NEED_BYTES) {
        /* read each span of the source and hand it in; the engine reads only what it touches */
        for (size_t i = 0; i < out.nranges; i++) {
            uint64_t off = out.ranges[i].offset, want = out.ranges[i].length;
            if (off + want > len) want = len - off;
            emon_provide(s, off, pdf + off, (size_t)want, &err);
        }
    } else {
        /* EMON_NEED_REQUESTS: out.requests, each to fulfil with emon_answer_ocr, emon_answer_render or emon_answer
           (only with the options js-ocr=1 and js-render=1; without them the session answers `unsupported` itself) */
    }
    emon_free_step(&out);
}
if (status < 0) {
    fprintf(stderr, "%s: %s\n", err.name, err.message);
    emon_free_error(&err);
    emon_close(s);
    return 1;
}
emon_model *model = NULL;
emon_finish(s, &model, &err); /* EMON_DONE: the Model, as for emon_convert */
emon_close(s);
emon_buf html = {0};
emon_model_html(model, NULL, &html, &err);
fwrite(html.data, 1, html.len, stdout);
emon_free_buf(&html);
emon_free_model(model);
```

`EMON_NEED_BYTES` lists the ranges of the source the engine missed: read them and `emon_provide` them; the unit that missed
is re-run from scratch, so the bytes a conversion reads are the bytes it touched (`emon_prefetch` names the first and last chunk
that stage 0 wants, so a remote source can fetch them in one go). `EMON_NEED_REQUESTS` lists the requests that need a port, which
you owe only if the options say so: `js-render=1` makes the `render` and `crop` requests yours (answer with `emon_answer_render`:
the whole page as RGB at the request's dpi and layer; a crop is cut from it by emon), `js-ocr=1` makes the `ocr` requests yours
(`emon_bitmap_png` gives the image to read, and `emon_answer_ocr` takes your engine's reading in the flat integer form of
`emon.ocrport`, whose policy functions `emon_ocr_models`, `emon_ocr_lang_first`, `emon_ocr_block_type` and `emon_ocr_word_lang`
are kin's, so every host reads a scan the same way). A request you have no port for is answered `unsupported` by the session. The
session does not use the library's own pdfium and Tesseract (the engine never calls out); if you only need scans read, the
one-shot call above with `EMON_NATIVE` does it.

The session also records and replays (`record=1`, `emon_transcript`, `emon_replay_file`): a transcript recorded by any host
replays on any other to the same bytes. The programs above need no port, and `bb package --check` compiles and runs each; the
request-answering functions are described by the header and have no example here.

## Notes

- Limits can also be set for the process with `EMON_LIMIT_*` ([docs/options.md](../options.md#limits)); a `limit.NAME` option beats it.
- `parallel=N` spreads the fulfilment of one conversion over N worker threads that emon starts and ends inside the call (the
  one-shot calls, with ports built in); the step API leaves fulfilment to you and ignores it. The bytes are the serial run's.
- `emon_version()` is the library's version; `emon_const(i)` returns kin's protocol numbers (the request kinds and statuses) for
  the step API.

<!-- bb docs:begin api-index -->
<details><summary>The public API of the c host: 124 entries (generated from <code>api/surface.edn</code>)</summary>

Frozen: `bb api-diff` fails on a removal or a change (DEVLOG D527). One row per name; members are `Owner.member` or `Owner#member` or `Owner::member`.

| Name | Signature |
|---|---|
| `EMON_CAP_CODECS` | `#define 4` |
| `EMON_CAP_PDFIUM` | `#define 1` |
| `EMON_CAP_TESSERACT` | `#define 2` |
| `EMON_DONE` | `#define 0` |
| `EMON_ERR_DAMAGED` | `#define 8` |
| `EMON_ERR_ENCRYPTION_UNSUPPORTED` | `#define 6` |
| `EMON_ERR_INTERNAL` | `#define 13` |
| `EMON_ERR_IO` | `#define 10` |
| `EMON_ERR_LIMIT` | `#define 3` |
| `EMON_ERR_NO_INPUT` | `#define 2` |
| `EMON_ERR_PASSWORD_INCORRECT` | `#define 5` |
| `EMON_ERR_PASSWORD_REQUIRED` | `#define 4` |
| `EMON_ERR_PLAN` | `#define 12` |
| `EMON_ERR_SOURCE_CHANGED` | `#define 9` |
| `EMON_ERR_UNSUPPORTED` | `#define 11` |
| `EMON_ERR_UNSUPPORTED_FORMAT` | `#define 7` |
| `EMON_ERR_USAGE` | `#define 1` |
| `EMON_NATIVE` | `#define 1` |
| `EMON_NEED_BYTES` | `#define 1` |
| `EMON_NEED_REQUESTS` | `#define 2` |
| `EMON_OPEN_INTAKE` | `#define 1` |
| `emon_answer` | `int (emon_session *s, const int32_t *cells, size_t ncells, emon_error *err)` |
| `emon_answer_ocr` | `int (emon_session *s, int32_t id, const int32_t *cells, size_t ncells, emon_error *err)` |
| `emon_answer_render` | `int (emon_session *s, int32_t id, int32_t width, int32_t height, const uint8_t *rgb, size_t len, emon_error *err)` |
| `emon_asset_info` | `struct` |
| `emon_asset_info.height` | `@6 int32_t` |
| `emon_asset_info.id` | `@0 int32_t` |
| `emon_asset_info.media_type` | `@2 char *` |
| `emon_asset_info.name` | `@1 char *` |
| `emon_asset_info.origin` | `@3 char *` |
| `emon_asset_info.sha256` | `@4 char *` |
| `emon_asset_info.size` | `@7 size_t` |
| `emon_asset_info.width` | `@5 int32_t` |
| `emon_bitmap_png` | `int (emon_session *s, int32_t id, emon_buf *out, emon_error *err)` |
| `emon_buf` | `struct` |
| `emon_buf.data` | `@0 uint8_t *` |
| `emon_buf.len` | `@1 size_t` |
| `emon_capabilities` | `unsigned (void)` |
| `emon_check_options` | `int (const char *options, emon_error *err)` |
| `emon_close` | `void (emon_session *s)` |
| `emon_const` | `int (unsigned i)` |
| `emon_convert` | `int (const emon_input *input, const char *options, unsigned flags, emon_model **out, emon_error *err)` |
| `emon_convert_html` | `int (const emon_input *input, const char *options, unsigned flags, const char *html_options, emon_buf *out, emon_error *err)` |
| `emon_convert_json` | `int (const emon_input *input, const char *options, unsigned flags, int pretty, emon_buf *out, emon_error *err)` |
| `emon_error` | `struct` |
| `emon_error.code` | `@0 int` |
| `emon_error.message` | `@2 char *` |
| `emon_error.name` | `@1 char *` |
| `emon_error_category` | `const char * (int code)` |
| `emon_fail` | `int (emon_session *s, int short_read, const char *message, emon_error *err)` |
| `emon_file` | `struct` |
| `emon_file.data` | `@1 uint8_t *` |
| `emon_file.len` | `@2 size_t` |
| `emon_file.name` | `@0 char *` |
| `emon_files` | `struct` |
| `emon_files.files` | `@0 emon_file *` |
| `emon_files.n` | `@1 size_t` |
| `emon_finish` | `int (emon_session *s, emon_model **out, emon_error *err)` |
| `emon_finish_intake` | `int (emon_session *s, emon_buf *out, emon_error *err)` |
| `emon_free_asset_info` | `void (emon_asset_info *a)` |
| `emon_free_buf` | `void (emon_buf *b)` |
| `emon_free_error` | `void (emon_error *e)` |
| `emon_free_files` | `void (emon_files *f)` |
| `emon_free_model` | `void (emon_model *m)` |
| `emon_free_step` | `void (emon_step_out *s)` |
| `emon_input` | `struct` |
| `emon_input.data` | `@0 const uint8_t *` |
| `emon_input.len` | `@1 size_t` |
| `emon_input.password` | `@3 const uint8_t *` |
| `emon_input.password_len` | `@4 size_t` |
| `emon_input.source` | `@2 const emon_source *` |
| `emon_intake` | `int (const emon_input *input, const char *options, unsigned flags, emon_buf *out, emon_error *err)` |
| `emon_is_option_key` | `int (const char *name)` |
| `emon_limit` | `int (emon_session *s, int32_t kind, int64_t *out, emon_error *err)` |
| `emon_model` | `opaque struct` |
| `emon_model_asset_bytes` | `int (const emon_model *m, int i, emon_buf *out, emon_error *err)` |
| `emon_model_asset_count` | `int (const emon_model *m)` |
| `emon_model_asset_info` | `int (const emon_model *m, int i, emon_asset_info *out, emon_error *err)` |
| `emon_model_evidence` | `int (const emon_model *m, emon_buf *out, emon_error *err)` |
| `emon_model_html` | `int (const emon_model *m, const char *html_options, emon_buf *out, emon_error *err)` |
| `emon_model_json` | `int (const emon_model *m, int pretty, emon_buf *out, emon_error *err)` |
| `emon_model_stats` | `int (const emon_model *m, emon_stats *out)` |
| `emon_ocr_block_type` | `int (int32_t poly)` |
| `emon_ocr_lang_first` | `int (const int32_t *request, size_t nrequest)` |
| `emon_ocr_models` | `int (const int32_t *request, size_t nrequest, const int32_t *installed, size_t ninstalled, emon_buf *out, emon_error *err)` |
| `emon_ocr_word_lang` | `int (const int32_t *request, size_t nrequest)` |
| `emon_open` | `int (uint64_t length, uint32_t chunk, unsigned flags, const uint8_t *password, size_t password_len, const char *options, emon_session **out, emon_error *err)` |
| `emon_prefetch` | `int (emon_session *s, emon_step_out *out, emon_error *err)` |
| `emon_provide` | `int (emon_session *s, uint64_t offset, const uint8_t *data, size_t len, emon_error *err)` |
| `emon_range` | `struct` |
| `emon_range.length` | `@1 uint64_t` |
| `emon_range.offset` | `@0 uint64_t` |
| `emon_read_fn` | `typedef int64_t (*emon_read_fn)(void *ctx, uint64_t offset, uint8_t *buf, size_t len)` |
| `emon_replay_end` | `int (emon_session *s, emon_error *err)` |
| `emon_replay_file` | `int (emon_session *s, const char *name, const uint8_t *data, size_t len, emon_error *err)` |
| `emon_request` | `struct` |
| `emon_request.cells` | `@2 const int32_t *` |
| `emon_request.id` | `@1 int32_t` |
| `emon_request.kind` | `@0 int32_t` |
| `emon_request.ncells` | `@3 size_t` |
| `emon_session` | `opaque struct` |
| `emon_set_native_paths` | `int (const char *pdfium, const char *tessdata, emon_error *err)` |
| `emon_source` | `struct` |
| `emon_source.ctx` | `@2 void *` |
| `emon_source.length` | `@0 uint64_t` |
| `emon_source.read_at` | `@1 emon_read_fn` |
| `emon_stats` | `struct` |
| `emon_stats.attempts` | `@0 int32_t` |
| `emon_stats.bytes_fetched` | `@5 uint64_t` |
| `emon_stats.bytes_requests` | `@2 int32_t` |
| `emon_stats.fetch_rounds` | `@1 int32_t` |
| `emon_stats.input_bytes` | `@6 uint64_t` |
| `emon_stats.requests` | `@3 int32_t` |
| `emon_stats.source_reads` | `@4 uint32_t` |
| `emon_step` | `int (emon_session *s, emon_step_out *out, emon_error *err)` |
| `emon_step_out` | `struct` |
| `emon_step_out.cells` | `@4 int32_t *` |
| `emon_step_out.ncells` | `@5 size_t` |
| `emon_step_out.nranges` | `@1 size_t` |
| `emon_step_out.nrequests` | `@3 size_t` |
| `emon_step_out.ranges` | `@0 emon_range *` |
| `emon_step_out.requests` | `@2 emon_request *` |
| `emon_transcript` | `int (emon_session *s, emon_files *out, emon_error *err)` |
| `emon_version` | `const char * (void)` |

</details>
<!-- bb docs:end api-index -->
