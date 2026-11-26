/* emon.h -- the C binding of emon (DEVLOG P9.4, D001, D471-D476, SPEC 13, 13.1; K2, D899).
 *
 * emon converts PDFs and images to a semantic Model (JSON, HTML). This is the Rust host behind a C ABI: libemon.{so,dylib,a}.  The core library has NO OCR engine in it:
 * OCR is an ADAPTER (emon_adapter.h), a library such as libemon_ocr_tesseract or a set of callbacks of yours, registered in an `emon_engines` (below).
 *
 * TWO WAYS IN
 *
 *   One-shot.  emon_convert / emon_convert_json / emon_convert_html / emon_intake take an emon_input: bytes, or a LAZY emon_source
 *              (a length and a read_at callback of yours: the engine reads only the spans it touches, in chunks, as it goes; SPEC
 *              13.1, D058).  The loop runs inside the call.  The ports it uses are the ENGINES you give it (`emon_engines`, below): pdfium renders, an OCR adapter
 *              reads, OpenJPEG/jbig2dec decode.  Given none (NULL), a request for a port is answered `unsupported` and a page that needed OCR is UNREAD, never
 *              blank (D038, D199).
 *
 *   Inverted.  emon_open, then emon_step in a loop, then emon_finish: for a caller that owns the byte source and, if it wants, the fulfilment.
 *              The engine never calls out.  emon_step says what it needs and you do it:
 *                  status = emon_step(session, &out, &err)
 *                    EMON_DONE (0)           emon_finish
 *                    EMON_NEED_BYTES (1)     out.ranges: read each [offset, offset+length) and emon_provide it, step again
 *                    EMON_NEED_REQUESTS (2)  out.requests: fulfil each (ocr nobody registered an adapter for, with js-ocr=1; render and crop with js-render=1),
 *                                            emon_answer / emon_answer_ocr / emon_answer_render, step again
 *                    EMON_WAITING (3)        only the registered adapters' async work is outstanding: emon_wait (or your own event loop), step again
 *                    < 0                     -code, err filled; only emon_close is valid afterwards
 *              A unit that missed is re-run from scratch inside emon_step (D058), so the bytes a conversion read are the bytes it
 *              touched.  Round 0 of an intake wants the first and the last chunk: emon_prefetch says which.
 *              When emon_open is given engines with OCR adapters, emon_step ANSWERS THOSE ADAPTERS' `ocr` requests itself (so a caller needs no loop of its own for OCR);
 *              the plain step API is unchanged.  Like the browser binding (host/wasm/src/wasm.rs) the caller fulfils `ocr` (with the option js-ocr=1) for the engines nobody
 *              registered, and `render` and `crop` (with js-render=1, P11.4c); a request the caller has no port for is answered `unsupported` by the session, and
 *              `decode`/`decode-img` are answered by emon from the bytes you provided.
 *
 * OPTIONS are one text, kin's grammar (emon.config): `key=value` lines separated by LF (spaces are not trimmed), the last line wins:
 *     locale ocr furniture assets asset-dpi asset-pixels asset-bytes assets-bytes asset-signatures keep-evidence ocr-scope
 *     max-rounds fetch-rounds record js-ocr chunk limit.NAME producer.NAME parallel js-render ocr-engine capture
 *     e.g. "ocr=never\nassets=images\nlimit.pages=50\n".
 * `parallel=N` (P10.2, SPEC 12.1) spreads the fulfilment of ONE conversion over N worker threads that emon starts and ends inside the call (limit.workers caps
 * it; 1, the default, starts none): the one-shot calls use it, with the engines you gave; the step API (emon_open / emon_step) answers its adapters' requests one at a time on
 * the stepping thread.  What it produces is the serial run's bytes at any N.
 * emon_check_options parses a text and says what is wrong; emon_is_option_key asks whether a name is a key.  A password is not an option
 * (it travels with its input: D040) and is never written to a Model, a dump or a transcript.
 *
 * MEMORY.  emon allocates; you free, with the matching emon_free_*.  Pointers you pass are borrowed for the call only (an emon_input's
 * bytes are copied, emon_provide copies).  An emon_buf is `len` bytes and a NUL after them (so JSON and HTML are C strings).  A handle
 * (emon_session, emon_model) is used by one thread at a time; different handles may be used by different threads.  An emon_engines may be given to
 * several calls and sessions at once, and is freed after the last of them.
 *
 * ERRORS.  A function answers 0 (or a positive status) or `-code`, and fills the emon_error you passed (NULL: not wanted):
 *     code     the CATEGORY, one of EMON_ERR_* (D040's: one per kind of error)
 *     name     the stable name to route on: kin's (a PDF status such as `bad-xref`, a limit such as `pages`, a plan error such as
 *              `transcript-miss`, or `password-required`, `unsupported-format`, `io`, `source-changed`, `internal`, `usage`); for the emon_engines_* functions the
 *              refusal's (`adapter-abi-unsupported`, `adapter-runtime-missing`, `adapter-remote-refused`, `pack-dir-missing`, ...)
 *     message  the sentence
 * *err is OVERWRITTEN on entry, not freed: start it zeroed (emon_error e = {0};) and emon_free_error it before reuse.
 *
 * PANICS DO NOT CROSS this boundary: a panic inside emon is the error `internal` (EMON_ERR_INTERNAL), with its message.  The engine works
 * on its own 32 MiB-stack thread, so your thread's stack size is nothing the answer depends on.
 */
#ifndef EMON_H
#define EMON_H

#include <stddef.h>
#include <stdint.h>

#include "emon_adapter.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ----------------------------------------------------------------------------------------------- errors */

#define EMON_ERR_USAGE                  1   /* a bad argument or option text */
#define EMON_ERR_NO_INPUT               2
#define EMON_ERR_LIMIT                  3   /* name: the limit's (`pages`, `input-bytes`, `objects`, ...); a typed breach */
#define EMON_ERR_PASSWORD_REQUIRED      4
#define EMON_ERR_PASSWORD_INCORRECT     5
#define EMON_ERR_ENCRYPTION_UNSUPPORTED 6
#define EMON_ERR_UNSUPPORTED_FORMAT     7   /* not a PDF, PNG, JPEG or TIFF */
#define EMON_ERR_DAMAGED                8   /* a PDF kin could not open (name: `bad-xref`, `no-root`, ...) or an image (`undecodable`) */
#define EMON_ERR_SOURCE_CHANGED         9   /* the source gave fewer bytes than its length said */
#define EMON_ERR_IO                     10  /* the source's read failed */
#define EMON_ERR_UNSUPPORTED            11
#define EMON_ERR_PLAN                   12  /* the plan/fulfil loop's own (`transcript-miss`, a bad transcript, ...) */
#define EMON_ERR_INTERNAL               13  /* a bug (a panic is caught and named here), never the document's fault */

typedef struct emon_error {
    int code;       /* 0: no error */
    char *name;     /* owned: emon_free_error */
    char *message;  /* owned */
} emon_error;

void emon_free_error(emon_error *e);
/* The name of a category (a `code`, or its negation), or NULL.  Static. */
const char *emon_error_category(int code);

/* ----------------------------------------------------------------------------------------------- memory and shapes */

typedef struct emon_buf {
    uint8_t *data;  /* NULL when empty; data[len] == 0 otherwise */
    size_t len;
} emon_buf;
void emon_free_buf(emon_buf *b);

/* A lazy byte source (SPEC 13.1): `length` bytes, read by `read_at`, which fills up to `len` bytes of `buf` from `offset` and answers how many
 * (like pread: fewer is fine, the engine asks again; 0 before the span is full is `source-changed`), or a negative number for a failed read
 * (`io`).  Called on emon's worker thread, one call at a time, during the call that was given the source. */
typedef int64_t (*emon_read_fn)(void *ctx, uint64_t offset, uint8_t *buf, size_t len);
typedef struct emon_source {
    uint64_t length;
    emon_read_fn read_at;
    void *ctx;
} emon_source;

/* One document or image: `source` (if not NULL), else `data`/`len` (copied).  The password is for an encrypted PDF (NULL/0: none). */
typedef struct emon_input {
    const uint8_t *data;
    size_t len;
    const emon_source *source;
    const uint8_t *password;
    size_t password_len;
} emon_input;

/* ----------------------------------------------------------------------------------------------- what the library is */

const char *emon_version(void);                         /* static */
#define EMON_CAP_PDFIUM    1   /* render and crop */
#define EMON_CAP_CODECS    4   /* OpenJPEG and jbig2dec: the JPX and JBIG2 images of a PDF */
/* What THIS LIBRARY can do in this process (a bit set): EMON_CAP_PDFIUM when it was built with the pdfium renderer AND finds pdfium (it is loaded at run time, never linked; where it
 * is looked for: emon_engines_pdfium), EMON_CAP_CODECS when it was built with them.  OCR is not a bit: it is an adapter, and which adapters you have is emon_engines_describe's to say. */
unsigned emon_capabilities(void);
/* kin's numbers of the plan/fulfil protocol: 0 kind render, 1 decode, 2 ocr, 3 bytes, 4 crop, 5 decode-img; 10 status ok, 11 failed,
 * 12 unsupported, 13 limit; 14 and 15 the ocr word flags.  -1 for any other. */
int emon_const(unsigned i);
int emon_is_option_key(const char *name);                       /* 1 or 0 */
int emon_check_options(const char *options, emon_error *err);  /* 0, or -EMON_ERR_USAGE with kin's sentence */

/* ----------------------------------------------------------------------------------------------- one-shot */

typedef struct emon_model emon_model;
void emon_free_model(emon_model *m);

typedef struct emon_engines emon_engines;   /* the engines of a conversion (below) */

/* Convert one input to a Model.  `options`: kin's text (NULL: the defaults); record=1, js-ocr=1 and js-render=1 are options of a session and are refused.  `engines`: the OCR
 * adapters and the renderer the conversion uses (NULL: none: a scan is unread, a request for pdfium is `unsupported`). */
int emon_convert(const emon_input *input, const char *options, const emon_engines *engines, emon_model **out, emon_error *err);
/* Convert an array of single-frame image inputs to one Model, in upload order. Uses the same settings as emon_convert. */
int emon_convert_images(const emon_input *inputs, size_t count, const char *options, const emon_engines *engines, emon_model **out, emon_error *err);
/* The common case in one call: the canonical JSON (compact, or indented when `pretty`) / the HTML (`html_options` as for emon_model_html). */
int emon_convert_json(const emon_input *input, const char *options, const emon_engines *engines, int pretty, emon_buf *out, emon_error *err);
int emon_convert_html(const emon_input *input, const char *options, const emon_engines *engines, const char *html_options, emon_buf *out, emon_error *err);
/* Stage 0 alone: `FORMAT MAJOR.MINOR pages=N encrypted=BOOL bytes=N`, e.g. `pdf 1.4 pages=3 encrypted=false bytes=1234`.  Needs no engine. */
int emon_intake(const emon_input *input, const char *options, emon_buf *out, emon_error *err);

/* ----------------------------------------------------------------------------------------------- the Model */

int emon_model_json(const emon_model *m, int pretty, emon_buf *out, emon_error *err);
/* `html_options`: a comma list of ids, geometry, confidence=N, ledger, pretty, drop-furniture, no-milestones, initials-plain, align-style,
 * assets=inline|external|omit, prefix=TEXT (NULL or "": clean HTML).  With assets=external the HTML names `prefix` + each asset's name: write the
 * assets beside it. */
int emon_model_html(const emon_model *m, const char *html_options, emon_buf *out, emon_error *err);
/* The text layer's evidence dump when the options said keep-evidence=1 (empty otherwise). */
int emon_model_evidence(const emon_model *m, emon_buf *out, emon_error *err);

typedef struct emon_stats {     /* what the loop did: attempts, fetch rounds, bytes requests, requests, source reads, bytes fetched, input size */
    int32_t attempts;
    int32_t fetch_rounds;
    int32_t bytes_requests;
    int32_t requests;
    uint32_t source_reads;
    uint64_t bytes_fetched;
    uint64_t input_bytes;
} emon_stats;
int emon_model_stats(const emon_model *m, emon_stats *out);

/* ASSETS (SPEC 10.11, D117): the pictures of a Model made with assets=images or assets=figures, in admission order. */
typedef struct emon_asset_info {
    int32_t id;
    char *name;         /* the file name to write it as (a0.png, a1.jpg) -- owned: emon_free_asset_info */
    char *media_type;   /* image/png, image/jpeg */
    char *origin;       /* embedded, rendered, ... */
    char *sha256;       /* hex of the encoded bytes */
    int32_t width;
    int32_t height;
    size_t size;        /* encoded bytes */
} emon_asset_info;
int emon_model_asset_count(const emon_model *m);        /* >= 0, or -code */
int emon_model_asset_info(const emon_model *m, int i, emon_asset_info *out, emon_error *err);
void emon_free_asset_info(emon_asset_info *a);
int emon_model_asset_bytes(const emon_model *m, int i, emon_buf *out, emon_error *err);

/* ----------------------------------------------------------------------------------------------- inverted: sessions */

typedef struct emon_session emon_session;

#define EMON_DONE          0
#define EMON_NEED_BYTES    1
#define EMON_NEED_REQUESTS 2
#define EMON_WAITING       3

#define EMON_OPEN_INTAKE   1    /* stage 0 only: finish with emon_finish_intake */

typedef struct emon_range {
    uint64_t offset;
    uint64_t length;
} emon_range;

/* A request the caller owes (an `ocr` request, with js-ocr=1): `cells` is its record `[len kind id ...]` (kin's, emon.plan), `ncells` long, and
 * points into out->cells.  Answer by id. */
typedef struct emon_request {
    int32_t kind;
    int32_t id;
    const int32_t *cells;
    size_t ncells;
} emon_request;

typedef struct emon_step_out {
    emon_range *ranges;         size_t nranges;
    emon_request *requests;     size_t nrequests;
    int32_t *cells;             size_t ncells;      /* the requests' records, back to back */
} emon_step_out;
void emon_free_step(emon_step_out *s);

/* Open a session over an input of `length` bytes you will provide in chunks of `chunk` bytes (0: the options' `chunk`, 64 KiB).  The
 * input-bytes limit is checked against `length` first (EMON_ERR_LIMIT).  record=1 keeps a transcript.  `flags`: EMON_OPEN_INTAKE or 0.  `engines`: the adapters whose `ocr`
 * requests emon_step answers itself (NULL: none, the caller answers or the request is `unsupported`); the engines may be freed once the session is closed. */
int emon_open(uint64_t length, uint32_t chunk, unsigned flags, const emon_engines *engines, const uint8_t *password, size_t password_len, const char *options,
              emon_session **out, emon_error *err);
void emon_close(emon_session *s);
/* SPEC 13.1: intake round 0 reads the first and the last chunk: out->ranges, as emon_step's; answers how many. */
int emon_prefetch(emon_session *s, emon_step_out *out, emon_error *err);
int emon_provide(emon_session *s, uint64_t offset, const uint8_t *data, size_t len, emon_error *err);
/* A read failed: `short_read` non-zero when it gave fewer bytes than the length said (source-changed), else io, with `message`. */
int emon_fail(emon_session *s, int short_read, const char *message, emon_error *err);
int emon_step(emon_session *s, emon_step_out *out, emon_error *err);
/* EMON_WAITING: block until an async adapter has something for the session, or `timeout_ms` has passed (negative: no limit).  1: something is here, step takes it; 0: timed out.
 * May be called from a thread other than the one that steps. */
int emon_wait(emon_session *s, int32_t timeout_ms, emon_error *err);
/* The answer to the work an async adapter was `start`-ed on (emon_adapter.h), from ANY thread: for a callback adapter whose engine answers through the program's own event loop,
 * given the `token` its `start` received.  `cells` is the OIL answer record the adapter's `read` would have given; or `failure`: code EMON_ERR_UNSUPPORTED (the page is unread:
 * `failure->name` is why) or any other (failed, also unread).  A token that is not this session's, or whose work is gone (it was cancelled), is a usage error. */
int emon_complete(emon_session *s, uint64_t token, const int32_t *cells, size_t ncells, const emon_error *failure, emon_error *err);

/* Answer an owed request with a raw record `[len kind id status ...]` (a failed or unsupported one is four cells: kin's emon.plan), or an
 * `ocr` request from the flat integer form of your engine's reading (kin's `oil-flat`, emon.ocrport: the layout is written once, in kin). */
int emon_answer(emon_session *s, const int32_t *cells, size_t ncells, emon_error *err);
int emon_answer_ocr(emon_session *s, int32_t id, const int32_t *cells, size_t ncells, emon_error *err);
/* A `render` or `crop` request (with js-render=1) from your renderer's WHOLE page: `width` x `height` pixels of RGB (3 channels, row-major,
 * `len` = width*height*3 bytes) at the request's dpi and layer.  A crop is the cut of it, made here (SPEC 7.2: the same pixels, cut out, never
 * a re-fit).  A bitmap of another size or over the `pixels` limit is a usage error. */
int emon_answer_render(emon_session *s, int32_t id, int32_t width, int32_t height, const uint8_t *rgb, size_t len, emon_error *err);
/* The value of limit `kind` (kin's table: emon_const(100 + kind) is its default) in force for the session: the bound a renderer must
 * keep.  `pixels` is kind 9. */
int emon_limit(emon_session *s, int32_t kind, int64_t *out, emon_error *err);
/* The bitmap a request names, as a canonical PNG: what your recogniser reads.  Valid while the round's requests are owed. */
int emon_bitmap_png(emon_session *s, int32_t id, emon_buf *out, emon_error *err);
/* The fields of an `ocr` request record, by kin's names (the layout is kin's, emon.plan and emon.ocrport): what an adapter you implement is handed, read for you.
 * `engine` is the OIL engine the request addresses (EMON_OEN_*; 0: Tesseract's, a request that predates the trailer), `psm` the page-segmentation mode, `flags` kin's
 * `emon.ocr` flags (1 symbols and their alternatives, 2 orientation and script detection, 4 eng_best, 8 source measurements; above the low byte the dpi: `dpi` has it),
 * x y w h the region of the bitmap (w <= 0: the whole bitmap), `langs` the languages (emon's codes: `eng`, `deu`, `chs`) and scripts (ISO 15924: `Latn`) it names. */
typedef struct emon_ocr_request_info {
    int32_t id, bitmap, engine, psm, flags, dpi, x, y, w, h;
    int32_t nlangs;
    char langs[8][8];
} emon_ocr_request_info;
int emon_ocr_request(const int32_t *request, size_t nrequest, emon_ocr_request_info *out, emon_error *err);

/* REPLAY (SPEC 14.1.5): a transcript recorded on any host: its files, one at a time, then emon_replay_end, before the first step. */
int emon_replay_file(emon_session *s, const char *name, const uint8_t *data, size_t len, emon_error *err);
int emon_replay_end(emon_session *s, emon_error *err);
/* RECORD (record=1): the transcript so far, as files (transcript.edn and PNGs). */
typedef struct emon_file {
    char *name;
    uint8_t *data;      /* data[len] == 0 */
    size_t len;
} emon_file;
typedef struct emon_files {
    emon_file *files;
    size_t n;
} emon_files;
int emon_transcript(emon_session *s, emon_files *out, emon_error *err);
void emon_free_files(emon_files *f);

/* After EMON_DONE: the Model (with the loop's stats), or the intake line of an EMON_OPEN_INTAKE session.  Then emon_close. */
int emon_finish(emon_session *s, emon_model **out, emon_error *err);
int emon_finish_intake(emon_session *s, emon_buf *out, emon_error *err);

/* ----------------------------------------------------------------------------------------------- engines (K2, D899)
 *
 * The OCR adapters you have, the renderer, and where the packs are: an `emon_engines`.  Give it to emon_convert* and emon_open.  Register in this order: the pack directory
 * (emon_engines_packs), then adapters (emon_engines_load: a library; emon_engines_add_ocr: callbacks), then pdfium.  emon_engines_defaults does the turnkey set for you:
 * what is next to the library, which is what an archive of emon lays out.
 *
 * PACKS (D885, D896) are the model files adapters read with: content-addressed, immutable, listed with their SHA-256 in kin's catalogue (release/packs/packs.edn).  EVERY PACK IS
 * VERIFIED against that SHA-256 and its size before an adapter reads it, wherever it came from: the pack directory, a directory the app named, bytes or a path your hook provided,
 * a download.  A file that does not match is never used; one in the pack directory is deleted.  Each adapter is given a flat directory of the verified files of the packs it reads.
 * A request that needs a pack that is not there is answered `unsupported`: the page is UNREAD (`pack-missing:<id>`), never blank.
 *
 * THE MODE (emon_engines_packs): EMON_PACKS_NEVER uses installed packs and skips the hook. CACHE and AUTO allow apps to supply missing packs through the hook.
 * A new emon_engines is NEVER. emon_engines_defaults is CACHE unless overridden by EMON_PACKS_FETCH. EMON_OFFLINE=1 takes precedence over the mode and hook.
 *
 * THE HOOK (emon_on_pack_needed) is asked on the conversion's calling thread. Answer PROVIDE with bytes or a path, DECLINE to leave the page unread, or SUBSTITUTE to select
 * another implementation of the same OIL engine. FETCH, including AUTO's default decision without a hook, leaves the page unread with `pack-fetch-unavailable:<id>`.
 * Apps can download packs with their platform HTTP client and return PROVIDE.
 */
#define EMON_PACKS_NEVER 1
#define EMON_PACKS_CACHE 2
#define EMON_PACKS_AUTO  3

/* A new, empty registry (no adapter, no pdfium, pack mode NEVER, the user's cache as the pack directory).  Free it with emon_engines_free after the last call and session that
 * was given it is done.  An emon_engines_* function that registers answers the adapter's index (0, 1, ... in registration order, which is preference order) or `-code`. */
int emon_engines_new(emon_engines **out, emon_error *err);
void emon_engines_free(emon_engines *e);     /* closes every adapter */

/* The pack directory and the mode.  `dir`: where packs are looked for first and fetched or provided ones are kept (`<dir>/<pack id>/<file>`); NULL or "": the user's cache
 * ($EMON_PACK_CACHE, else the platform's cache directory).  Do this before registering adapters.  A directory that cannot be made is `pack-dir-missing`. */
int emon_engines_packs(emon_engines *e, const char *dir, int mode, emon_error *err);
/* Register an adapter library (emon_adapter.h: it exports `emon_ocr_adapter_v1`), opened with `config` (the adapter's own key=value lines, or NULL) and the pack directory of its
 * engine.  Errors: `adapter-runtime-missing` (cannot be loaded, or has no export), `adapter-open-failed`, `adapter-abi-unsupported`, `adapter-engine-unknown`,
 * `adapter-caps-beyond-engine`, `adapter-needs-beyond-engine`, `adapter-remote-refused`, ... */
int emon_engines_load(emon_engines *e, const char *path, const char *config, emon_error *err);
/* Register an adapter you implement (emon_adapter.h): the struct is copied, `ctx` stays yours, `close` is called when the registry is freed. */
int emon_engines_add_ocr(emon_engines *e, const emon_ocr_adapter *a, emon_error *err);
/* The built-in pdfium renderer: `lib_or_dir` is the library (a file, or the directory that holds it); NULL or "": $EMON_PDFIUM_LIB, then next to the library (lib/libpdfium.*), then
 * next to the executable, then, in an app, the loader by name.  Not found: `adapter-runtime-missing`. */
int emon_engines_pdfium(emon_engines *e, const char *lib_or_dir, emon_error *err);
/* The turnkey set: packs in `share/emon/packs` above lib/ (and $EMON_MODELS), mode AUTO (or $EMON_PACKS_FETCH: never, cache, auto); every `libemon_ocr_*` in
 * `lib/emon/adapters/` (or $EMON_ADAPTER_DIR; in an app, by name from the loader); pdfium where emon_engines_pdfium(NULL) finds it.  Nothing found is not an error; an
 * adapter that is found and cannot be opened is. */
int emon_engines_defaults(emon_engines *e, emon_error *err);
/* How long a one-shot call waits for an async adapter, per request (default 60000 ms): then the request is `failed` and the page unread. */
int emon_engines_set_timeout(emon_engines *e, int32_t ms, emon_error *err);
/* Allow adapters that send the pixels off the device (EMON_ADAPTER_REMOTE).  The default is no: documents may hold personal data.  Before registering them. */
int emon_engines_allow_remote(emon_engines *e, int allow, emon_error *err);
/* What adapters and packs reported since the last call, one line each (`tesseract: unsupported pack-missing:tess-fast-deu`, `pack-hash-mismatch:tess-osd`): why a page was left
 * unread, for your log. */
int emon_engines_notes(emon_engines *e, emon_buf *out, emon_error *err);

/* `engines()`: every engine of kin's table that runs on this platform, as JSON.
 *   {"platform":"macos","mode":"auto","offline":false,"store":"/path","engines":[
 *      {"name":"tesseract","kind":"ocr","impl":"native-adapter","state":"installed","registered":true,"calibrated":true,"caps":N,"oen":1,"role":"incumbent",
 *       "needs":[{"id":"tess-fast-eng","kind":"recognizer","bytes":4113088,"licence":"Apache-2.0","installed":true}],"download_bytes":0,"reason":""}, ...]}
 * `state`: "built-in" (needs no pack: pdfium, an OS engine), "installed" (registered, packs here), "downloadable" (registered, packs missing and the mode may fetch them:
 * `download_bytes`) or "unavailable" (`reason`).  `needs` are the packs of a conversion with the default options (English). */
int emon_engines_describe(const emon_engines *e, emon_buf *out, emon_error *err);
/* `prepare(options)`: the packs a conversion with these options (`ocr`, `ocr-engine`, `locale`) needs, which are missing, and what that comes to, as JSON.  Fetches nothing.
 *   {"verdict":"downloadable","mode":"auto","offline":false,"engines":["tesseract"],"languages":["eng"],"osd":true,
 *    "packs":[{"id":"tess-fast-eng","state":"missing","engine":"tesseract","kind":"recognizer","bytes":4113088,"licence":"Apache-2.0","rev":1}, ...],
 *    "unsupported":[{"engine":"ppocr","language":"spa"}],"missing":2,"missing_bytes":14675815,"conditional":0,"conditional_bytes":0}
 * `verdict`: "ready" (nothing missing), "partial" (nothing missing, but a language no pack reads: those pages are unread), "downloadable" (missing, and the mode may fetch) or
 * "blocked" (missing, and nothing may be fetched).  A pack whose `state` is "conditional" (`ocr-engine=apple-vision`: Tesseract's packs, the fallback's) is needed only if a page of the conversion
 * goes to the fallback; it is not counted in `missing` and not fetched ahead of time, a page that needs it asks for it (`emon_on_pack_needed`), and `conditional`, `conditional_bytes` count them.
 * An option text that does not parse is a usage error. */
int emon_prepare(const emon_engines *e, const char *options, emon_buf *out, emon_error *err);

/* THE HOOK.  Called with what the library needs; fill `out->action` (and the rest for PROVIDE and SUBSTITUTE).  The pointers you give need only live until you return.
 * Answer 0; non-zero is DECLINE.  `reason` is "conversion" or "ensure". */
#define EMON_PACK_FETCH      1
#define EMON_PACK_PROVIDE    2
#define EMON_PACK_DECLINE    3
#define EMON_PACK_SUBSTITUTE 4
typedef struct emon_pack_request {
    const char *pack;      /* the pack's id: "tess-fast-deu" */
    int32_t rev;
    const char *engine;    /* the engine it is for ("tesseract"), or "any" */
    const char *kind;      /* "recognizer", "osd", "detector", "classifier", "lexicon" */
    const char *licence;   /* "Apache-2.0" */
    uint64_t bytes;        /* what it comes to, all its files */
    const char *reason;
    const char *request_engine;   /* the engine whose request needs it */
} emon_pack_request;
typedef struct emon_pack_decision {
    int32_t action;
    const uint8_t *data;   /* PROVIDE: the bytes of a single-file pack... */
    size_t len;
    const char *path;      /* ...or a path: the file, or a directory holding the pack's files by their names */
    const char *engine;    /* SUBSTITUTE: another implementation of the same OIL engine */
} emon_pack_decision;
typedef int (*emon_pack_needed_fn)(void *ctx, const emon_pack_request *req, emon_pack_decision *out);
int emon_on_pack_needed(emon_engines *e, emon_pack_needed_fn fn, void *ctx, emon_error *err);   /* fn NULL removes it */

/* Progress and cancellation declarations are retained in the ABI. Apps report progress and handle cancellation for downloads they perform. */
#define EMON_PACK_EVENT_START     1
#define EMON_PACK_EVENT_PROGRESS  2
#define EMON_PACK_EVENT_DONE      3
#define EMON_PACK_EVENT_FAILED    4
#define EMON_PACK_EVENT_CANCELLED 5
#define EMON_PACK_BY_LIBRARY 1   /* a conversion needed it */
#define EMON_PACK_BY_APP     2   /* emon_packs_ensure */
typedef struct emon_pack_event {
    int32_t kind;
    const char *pack;
    const char *engine;
    uint64_t done;         /* bytes so far, of the whole pack */
    uint64_t total;
    int32_t initiator;
    const char *message;   /* FAILED: why */
} emon_pack_event;
typedef int (*emon_pack_event_fn)(void *ctx, const emon_pack_event *ev);
int emon_on_pack_event(emon_engines *e, emon_pack_event_fn fn, void *ctx, emon_error *err);     /* fn NULL removes it */
/* Check packs ahead of time: `ids` is a comma list of pack ids. In AUTO and online, the first missing pack is reported as `pack-fetch-unavailable:<id>`. */
int emon_packs_ensure(emon_engines *e, const char *ids, emon_error *err);
/* Retained in the ABI for compatibility. */
void emon_packs_cancel(emon_engines *e);

#ifdef __cplusplus
}
#endif

#endif /* EMON_H */
