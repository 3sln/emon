/* emon.h -- the C binding of emon (DEVLOG P9.4, D001, D471-D476, SPEC 13, 13.1).
 *
 * emon converts PDFs and images to a semantic Model (JSON, HTML). This is the Rust host behind a C ABI: libemon.{so,dylib,a}.
 *
 * TWO WAYS IN
 *
 *   One-shot.  emon_convert / emon_convert_json / emon_convert_html / emon_intake take an emon_input: bytes, or a LAZY emon_source
 *              (a length and a read_at callback of yours: the engine reads only the spans it touches, in chunks, as it goes; SPEC
 *              13.1, D058).  The loop runs inside the call.  With EMON_NATIVE the library's own ports are used when it was built with
 *              them (emon_capabilities): pdfium renders, Tesseract reads, OpenJPEG/jbig2dec decode.  Without them (or without the flag)
 *              a request for a port is answered `unsupported` and a page that needed OCR has no text (D199).
 *
 *   Inverted.  emon_open, then emon_step in a loop, then emon_finish: for a caller that owns the byte source and the fulfilment.
 *              The engine never calls out.  emon_step says what it needs and you do it:
 *                  status = emon_step(session, &out, &err)
 *                    EMON_DONE (0)           emon_finish
 *                    EMON_NEED_BYTES (1)     out.ranges: read each [offset, offset+length) and emon_provide it, step again
 *                    EMON_NEED_REQUESTS (2)  out.requests: fulfil each (ocr), emon_answer / emon_answer_ocr, step again
 *                    < 0                     -code, err filled; only emon_close is valid afterwards
 *              A unit that missed is re-run from scratch inside emon_step (D058), so the bytes a conversion read are the bytes it
 *              touched.  Round 0 of an intake wants the first and the last chunk: emon_prefetch says which.
 *              Like the browser binding (host/wasm/src/wasm.rs) the caller fulfils `ocr` only (with the option js-ocr=1); `render` and
 *              `crop` are answered `unsupported` by the session, and `decode`/`decode-img` are answered by emon from the bytes you provided.
 *
 * OPTIONS are one text, kin's grammar (emon.config): `key=value` lines separated by LF (spaces are not trimmed), the last line wins:
 *     locale ocr furniture assets asset-dpi asset-pixels asset-bytes assets-bytes asset-signatures keep-evidence ocr-scope
 *     max-rounds fetch-rounds record js-ocr chunk limit.NAME producer.NAME parallel
 *     e.g. "ocr=never\nassets=images\nlimit.pages=50\n".
 * `parallel=N` (P10.2, SPEC 12.1) spreads the fulfilment of ONE conversion over N worker threads that emon starts and ends inside the call (limit.workers caps
 * it; 1, the default, starts none): the one-shot calls use it, with the ports built in (EMON_NATIVE); the step API (emon_open / emon_step) leaves the
 * fulfilment to its caller and ignores it.  What it produces is the serial run's bytes at any N.
 * emon_check_options parses a text and says what is wrong; emon_is_option_key asks whether a name is a key.  A password is not an option
 * (it travels with its input: D040) and is never written to a Model, a dump or a transcript.
 *
 * MEMORY.  emon allocates; you free, with the matching emon_free_*.  Pointers you pass are borrowed for the call only (an emon_input's
 * bytes are copied, emon_provide copies).  An emon_buf is `len` bytes and a NUL after them (so JSON and HTML are C strings).  A handle
 * (emon_session, emon_model) is used by one thread at a time; different handles may be used by different threads.
 *
 * ERRORS.  A function answers 0 (or a positive status) or `-code`, and fills the emon_error you passed (NULL: not wanted):
 *     code     the CATEGORY, one of EMON_ERR_* (D040's: one per kind of error)
 *     name     the stable name to route on: kin's (a PDF status such as `bad-xref`, a limit such as `pages`, a plan error such as
 *              `transcript-miss`, or `password-required`, `unsupported-format`, `io`, `source-changed`, `internal`, `usage`)
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
#define EMON_CAP_TESSERACT 2   /* ocr */
#define EMON_CAP_CODECS    4   /* OpenJPEG and jbig2dec: the JPX and JBIG2 images of a PDF */
unsigned emon_capabilities(void);
/* kin's numbers of the plan/fulfil protocol: 0 kind render, 1 decode, 2 ocr, 3 bytes, 4 crop, 5 decode-img; 10 status ok, 11 failed,
 * 12 unsupported, 13 limit; 14 and 15 the ocr word flags.  -1 for any other. */
int emon_const(unsigned i);
int emon_is_option_key(const char *name);                       /* 1 or 0 */
int emon_check_options(const char *options, emon_error *err);  /* 0, or -EMON_ERR_USAGE with kin's sentence */
/* Test hook: panics on purpose, to show the boundary holds: answers -EMON_ERR_INTERNAL and the message. */
int emon_test_panic(emon_error *err);

/* ----------------------------------------------------------------------------------------------- one-shot */

#define EMON_NATIVE 1   /* use the library's own pdfium / Tesseract / codecs (as emon_capabilities says it has) */

typedef struct emon_model emon_model;
void emon_free_model(emon_model *m);

/* Convert one input to a Model.  `options`: kin's text (NULL: the defaults); record=1, js-ocr=1 and js-render=1 are options of a session and are refused. */
int emon_convert(const emon_input *input, const char *options, unsigned flags, emon_model **out, emon_error *err);
/* The common case in one call: the canonical JSON (compact, or indented when `pretty`) / the HTML (`html_options` as for emon_model_html). */
int emon_convert_json(const emon_input *input, const char *options, unsigned flags, int pretty, emon_buf *out, emon_error *err);
int emon_convert_html(const emon_input *input, const char *options, unsigned flags, const char *html_options, emon_buf *out, emon_error *err);
/* Stage 0 alone: `FORMAT MAJOR.MINOR pages=N encrypted=BOOL bytes=N`, e.g. `pdf 1.4 pages=3 encrypted=false bytes=1234`. */
int emon_intake(const emon_input *input, const char *options, unsigned flags, emon_buf *out, emon_error *err);

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
 * input-bytes limit is checked against `length` first (EMON_ERR_LIMIT).  record=1 keeps a transcript. */
int emon_open(uint64_t length, uint32_t chunk, unsigned flags, const uint8_t *password, size_t password_len, const char *options,
              emon_session **out, emon_error *err);
void emon_close(emon_session *s);
/* SPEC 13.1: intake round 0 reads the first and the last chunk: out->ranges, as emon_step's; answers how many. */
int emon_prefetch(emon_session *s, emon_step_out *out, emon_error *err);
int emon_provide(emon_session *s, uint64_t offset, const uint8_t *data, size_t len, emon_error *err);
/* A read failed: `short_read` non-zero when it gave fewer bytes than the length said (source-changed), else io, with `message`. */
int emon_fail(emon_session *s, int short_read, const char *message, emon_error *err);
int emon_step(emon_session *s, emon_step_out *out, emon_error *err);

/* Answer an owed request with a raw record `[len kind id status ...]` (a failed or unsupported one is four cells: kin's emon.plan), or an
 * `ocr` request from the flat integer form of your engine's reading (kin's `ox-flat`, emon.ocrport: the layout is written once, in kin). */
int emon_answer(emon_session *s, const int32_t *cells, size_t ncells, emon_error *err);
int emon_answer_ocr(emon_session *s, int32_t id, const int32_t *cells, size_t ncells, emon_error *err);
/* The bitmap a request names, as a canonical PNG: what your recogniser reads.  Valid while the round's requests are owed. */
int emon_bitmap_png(emon_session *s, int32_t id, emon_buf *out, emon_error *err);
/* The policy of an `ocr` request is kin's (emon.ocrport); a caller asks it here. */
int emon_ocr_lang_first(const int32_t *request, size_t nrequest);
int emon_ocr_models(const int32_t *request, size_t nrequest, const int32_t *installed, size_t ninstalled, emon_buf *out, emon_error *err);
int emon_ocr_block_type(int32_t poly);
int emon_ocr_word_lang(const int32_t *request, size_t nrequest);

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

#ifdef __cplusplus
}
#endif

#endif /* EMON_H */
