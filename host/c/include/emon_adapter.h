/* emon_adapter.h -- the native OCR adapter ABI, version 1 (DEVLOG D900, K2; D883, D885, D896; pkg1 section 1.2).
 *
 * An ADAPTER is an OCR engine behind emon's port: it is handed an `ocr` request (kin's record, and the pixels it names) and answers with a reading in OIL, the engine-neutral
 * interchange (D883).  Tesseract is the adapter library `libemon_ocr_tesseract`; PP-OCR is `libemon_ocr_ppocr`; an embedder's own engine (a platform recognizer, a cloud service,
 * a Dart plugin) is a set of callbacks.  All of them are this one struct, so a host has one registry and one rule for each of: what an adapter says it is, what it needs, what
 * it answers when it cannot read, and how it answers later.
 *
 * TWO WAYS TO BE ONE
 *
 *   A library.   It exports ONE symbol, `emon_ocr_adapter_v1`, which `emon_engines_load` (emon.h) finds with dlopen/LoadLibrary.  The library needs nothing of libemon: it never
 *                calls it, so it can be built, versioned and shipped on its own.
 *   Callbacks.   A program fills an `emon_ocr_adapter` and gives it to `emon_engines_add_ocr`.  The struct is copied; `ctx` is yours.
 *
 * WHAT AN ANSWER IS
 *
 *   The whole OIL answer record, `[len 3 id 0 <OIL>]` (kin's `oil-end`; DEVLOG D883 section 2), as int32 cells that the adapter allocated and gives back with `release`.  The
 *   library CHECKS it (kin's `oil-record-check-bitmap`, with the bitmap the request named): a malformed answer, one that names another engine than the adapter registered as,
 *   or one that declares capabilities the adapter did not register, is `ocr-adapter-invalid`, an error that FAILS the conversion (a buggy adapter must not quietly degrade the
 *   output).  A reading the adapter cannot give is NOT an empty answer: it is `EMON_ADAPTER_UNSUPPORTED` or `EMON_ADAPTER_FAILED`, and the page is UNREAD, never blank (D038).
 *   `reason` says why, in a word to route on: `lang-unavailable`, `pack-missing:tess-fast-deu`, `too-small`, `network`, ...
 *
 * THREADS.  Unless `info.flags` has EMON_ADAPTER_THREADSAFE, emon calls an adapter from one thread at a time.  `describe` and `close` are never concurrent with a read.
 *
 * PACKS.  An adapter that reads with models (`info.needs`) is given a PACK DIRECTORY when it is opened: a flat directory of the VERIFIED files of the packs it reads, by file
 * name (`eng.traineddata`, `osd.traineddata`; `ch_PP-OCRv5_det_mobile.onnx`).  emon fetches, verifies (every file against the catalogue's SHA-256, D896) and links the files
 * there; the adapter reads and never writes.  It lists the directory again for each request, because packs arrive while it runs.  An adapter that needs no pack (an OS engine,
 * a service) is given NULL.
 *
 * ASYNC.  An adapter that answers later (a service, a platform recognizer, an adapter whose engine lives in another runtime) sets EMON_ADAPTER_ASYNC and implements `start`: it
 * returns at once (EMON_ADAPTER_OK: begun) and later, from any thread, calls the `complete` of the `emon_adapter_host` it was `bind`-ed with, or the program calls `emon_complete`
 * (emon.h) for a callback adapter that answers through the program's own event loop.  `cancel` is called when a conversion fails, closes or times out: a late completion is
 * ignored.  A one-shot call waits for it (the engines' `emon_engines_set_timeout`); the step API returns EMON_WAITING while only async work is outstanding (`emon_wait`).
 *
 * PRIVACY.  An adapter that sends pixels off the device (EMON_ADAPTER_REMOTE) is refused by `emon_engines_*` unless the registry allowed it with `emon_engines_allow_remote`:
 * documents may hold personal data (D883, pkg1 1.6).
 *
 * EVOLUTION.  `abi` and `size` are the library's: a struct only ever grows, at its end, so a newer emon reads an older adapter (the fields past `size` are NULL).  A library that
 * speaks a newer ABI than emon is refused when it is registered (`adapter-abi-unsupported`), never in the middle of a conversion.
 */
#ifndef EMON_ADAPTER_H
#define EMON_ADAPTER_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EMON_ADAPTER_ABI 1

/* What `supports`, `read` and `start` answer. */
#define EMON_ADAPTER_OK          0   /* read: `out` holds the answer.  start: begun, `complete` will come */
#define EMON_ADAPTER_UNSUPPORTED 1   /* this adapter cannot read this request (a language it has no pack for, a crop too small): the page is unread */
#define EMON_ADAPTER_FAILED      2   /* it tried and could not (an engine error, a network error): the page is unread */

/* `emon_adapter_info.flags`. */
#define EMON_ADAPTER_ASYNC      1    /* answers through `start` and `complete` */
#define EMON_ADAPTER_REMOTE     2    /* sends the pixels off the device */
#define EMON_ADAPTER_THREADSAFE 4    /* may be called from several threads at once */

/* The kinds of pack an adapter reads with (`emon_adapter_info.needs`, a bit set; kin's `pkind`). */
#define EMON_PACK_RECOGNIZER 1
#define EMON_PACK_OSD        2
#define EMON_PACK_DETECTOR   4
#define EMON_PACK_CLASSIFIER 8

/* The OIL engines an adapter can be (`emon_adapter_info.engine`; kin's `oen`). */
#define EMON_OEN_TESSERACT         1
#define EMON_OEN_PPOCR             2
#define EMON_OEN_APPLE_VISION      3
#define EMON_OEN_MLKIT_TEXT        4
#define EMON_OEN_WINDOWS_MEDIA_OCR 5

/* Why an adapter did not read, filled by the adapter when it answers UNSUPPORTED or FAILED (or when it cannot be opened). */
typedef struct emon_adapter_status {
    char reason[96];     /* a word to route on, NUL terminated: `lang-unavailable`, `pack-missing:<id>`, `too-small`, `network` */
    char message[224];   /* a sentence, NUL terminated */
} emon_adapter_status;

/* The pixels a request names: row-major, `stride` bytes a row (the library gives tight rows: stride == width * channels), 1 (gray) or 3 (RGB) channels, valid for the call only. */
typedef struct emon_adapter_image {
    int32_t width;
    int32_t height;
    int32_t channels;
    int32_t stride;
    const uint8_t *pixels;
    int32_t dpi;         /* the resolution the request hints at, or 0 */
} emon_adapter_image;

/* What an adapter says it is.  Every string is the adapter's and lives until `close`. */
typedef struct emon_adapter_info {
    uint32_t engine;     /* the OIL engine it answers as: EMON_OEN_*.  It must be an OCR engine of kin's table (emon.engines) */
    uint32_t caps;       /* the OIL capabilities it declares (kin's `oc` bits), a subset of what the table lets that engine declare */
    uint32_t native;     /* of `caps`, the ones it MEASURES rather than derives (a subset of `caps`; D883 2.2) */
    uint32_t needs;      /* the kinds of pack it reads with: EMON_PACK_* */
    uint32_t flags;      /* EMON_ADAPTER_* */
    const char *config;     /* the canonical requested-configuration text of this adapter (D883 2.3): what makes two runs the same configuration */
    const char *observed;   /* the observed identity: the engine's version and what it reads with, as text.  Stable for the adapter's life */
    const char *producer;   /* `key=value` lines (LF) that name whose engine answered, for a transcript's `:producer`.  NULL: emon writes `engine=<name>` */
    const char *const *langs;   /* the languages (emon's codes: `eng`, `deu`, `chs`) it can read now, or NULL for whatever the packs it is given allow */
    size_t nlangs;
} emon_adapter_info;

/* An answer, filled by `read`, given back with `release`. */
typedef struct emon_adapter_answer {
    int32_t *cells;          /* the OIL answer record [len 3 id 0 ...], `ncells` cells */
    size_t ncells;
    const uint8_t *binary;   /* the 1-channel image the engine thresholded to read (0 ink, 255 paper), width x height, when `read` was asked to keep it and the engine made one; else NULL */
    int32_t binary_width;
    int32_t binary_height;
    void *owner;             /* the adapter's, for `release` */
} emon_adapter_answer;

/* What `bind` gives an async adapter: where to say an answer is ready.  `complete` may be called from any thread, once for each token it was given.  `cells` is the OIL answer
 * record (copied); `status` is EMON_ADAPTER_OK, or UNSUPPORTED / FAILED with `why` (copied; may be NULL).  Answers 0, or non-zero when the token is unknown (a late completion). */
typedef struct emon_adapter_host {
    uint32_t abi;
    uint32_t size;
    void *ctx;
    int (*complete)(void *ctx, uint64_t token, int status, const int32_t *cells, size_t ncells, const emon_adapter_status *why);
} emon_adapter_host;

/* The adapter.  `abi` and `size` are the library's own (EMON_ADAPTER_ABI, sizeof this struct as it was built).  A function that is not meant is NULL. */
typedef struct emon_ocr_adapter {
    uint32_t abi;
    uint32_t size;
    const char *name;        /* "tesseract", "ppocr", "apple-vision", "mlkit-text", "windows-media-ocr", or the embedder's own: the engine's name in kin's table */
    void *ctx;

    /* what it is, filled in; EMON_ADAPTER_OK, or FAILED with `st` */
    int (*describe)(void *ctx, emon_adapter_info *out, emon_adapter_status *st);

    /* a cheap pre-check of one request (a language it has no model for, a crop below its minimum): EMON_ADAPTER_OK to go on, UNSUPPORTED with `st->reason` to leave the page
     * unread without calling `read`.  NULL: everything is supported. */
    int (*supports)(void *ctx, const int32_t *request, size_t n, emon_adapter_status *st);

    /* read one request, synchronously.  `request` is kin's record [len kind id arg...] of the `ocr` request (`emon_ocr_request`, emon.h, reads its fields), `image` the pixels it
     * names.  `want_binary` is non-zero when the conversion records a transcript, which keeps the engine's thresholded image.  NULL for an async-only adapter. */
    int (*read)(void *ctx, const int32_t *request, size_t n, const emon_adapter_image *image, int want_binary, emon_adapter_answer *out, emon_adapter_status *st);

    /* begin one request and return at once (async).  The pixels are valid for the call only: copy what is needed.  `token` names the request in `complete`.  NULL for a sync adapter. */
    int (*start)(void *ctx, const int32_t *request, size_t n, const emon_adapter_image *image, uint64_t token, emon_adapter_status *st);

    /* the conversion no longer wants the answer to `token` (it failed, closed or timed out).  NULL: nothing to do. */
    void (*cancel)(void *ctx, uint64_t token);

    /* give back what `read` allocated.  NULL when `read` allocated nothing */
    void (*release)(void *ctx, emon_adapter_answer *a);

    /* async: where to call `complete`.  Called once, after `describe`, before the first `start`.  NULL for a sync adapter */
    void (*bind)(void *ctx, const emon_adapter_host *host);

    /* the adapter is no longer needed: release everything, `ctx` and the struct itself when they were allocated by `emon_ocr_adapter_v1`. Never concurrent with a call. */
    void (*close)(void *ctx);
} emon_ocr_adapter;

/* THE ONE EXPORT of an adapter library.  `config`: `key=value` lines (LF) that are the adapter's own (NULL: none); `packs_dir`: the pack directory (above), or NULL.  Returns the
 * adapter, which lives until its `close`; NULL, with `st` filled, when it cannot be opened. */
typedef const emon_ocr_adapter *(*emon_ocr_adapter_v1_fn)(const char *config, const char *packs_dir, emon_adapter_status *st);
const emon_ocr_adapter *emon_ocr_adapter_v1(const char *config, const char *packs_dir, emon_adapter_status *st);

#ifdef __cplusplus
}
#endif

#endif /* EMON_ADAPTER_H */
