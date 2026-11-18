/* emon_vision.h -- the C face of the Apple Vision shim (K8, D900).  host/vision/emon_vision.m is the whole of the Objective-C: it hands Vision a bitmap and gives back plain
 * C rows (lines, their words and boxes, their candidates).  Everything that is OIL (the answer record, the mapping, the capabilities) is Rust's, host/rust/src/vision.rs,
 * so the policy is written once and the shim stays a thin reader of one framework (macOS 10.15+ and iOS 13+, the same API on both).  No Swift, no new crate.
 *
 * Coordinates are IMAGE PIXELS with the origin at the top left (Vision's own are normalised with the origin at the bottom left; the shim converts).  A quad is
 * top-left, top-right, bottom-right, bottom-left. */
#ifndef EMON_VISION_H
#define EMON_VISION_H
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ev_quad { double x[4]; double y[4]; } ev_quad;

typedef struct ev_word {
    char *text;          /* UTF-8, no whitespace */
    ev_quad quad;
    int32_t precise;     /* 1 when Vision gave this word's own box, 0 when the shim split the line's quad by character count */
} ev_word;

typedef struct ev_cand {
    char *text;          /* UTF-8 */
    float confidence;
} ev_cand;

typedef struct ev_line {
    char *text;          /* the top candidate, UTF-8 */
    float confidence;    /* its confidence, 0..1 */
    ev_quad quad;        /* the observation's quad */
    int32_t nwords;
    ev_word *words;      /* the top candidate split at whitespace */
    int32_t ncands;      /* the other candidates (the top one is `text`), best first */
    ev_cand *cands;
} ev_line;

typedef struct ev_result {
    int32_t nlines;
    ev_line *lines;
} ev_result;

/* The engine's identity for the observed identity string: OS name and version and build, the request's revision, the architecture.  Writes a NUL-terminated line. */
void emon_vision_identity(char *out, size_t cap);

/* The recognition languages Vision reads at its current revision, as one string of BCP-47 tags joined by ',' (the accurate level).  0 when it could be asked, else -1. */
int emon_vision_languages(char *out, size_t cap);

/* Read one bitmap.  `pixels` is row-major, `channels` 1 (gray) or 3 (RGB), `stride` bytes a row.  `langs` are BCP-47 tags (`nlangs` may be 0: Vision's default).  `fast`: the
 * fast recognition level, else accurate.  `correct`: language correction.  `ncands`: how many candidates to keep besides the top (0..9).
 * Answers 0 and fills `out` (free it with emon_vision_free), or non-zero with `err` set to a sentence. */
int emon_vision_read(const uint8_t *pixels, int32_t width, int32_t height, int32_t channels, int32_t stride,
                     const char *const *langs, int32_t nlangs, int32_t fast, int32_t correct, int32_t ncands,
                     ev_result *out, char *err, size_t errcap);

void emon_vision_free(ev_result *r);

#ifdef __cplusplus
}
#endif
#endif
