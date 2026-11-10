#ifndef EMON_PPOCR_H
#define EMON_PPOCR_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Experimental optional adapter, ABI v1. Tight row-major gray/RGB pixels.
 * Requests and returned complete answers use the canonical emon plan cells.
 * read requires a PP identity trailer; route ordinary requests to Tesseract.
 * Calls may run concurrently; close requires all reads to have completed.
 * No download occurs. open checks model pins; sessions initialize lazily.
 * error is NUL terminated when capacity > 0. Success: read returns zero.
 * Free each successful output exactly once with its returned count. */
void *emon_ppocr_open_v1(const char *model_dir, char *error, size_t capacity);
void emon_ppocr_close_v1(void *port);
int emon_ppocr_read_v1(const void *port, const int32_t *request, size_t length,
    const uint8_t *pixels, size_t bytes, int32_t width, int32_t height, int32_t channels,
    int32_t **answer, size_t *count, char *error, size_t capacity);
void emon_ppocr_free_v1(int32_t *answer, size_t count);
#ifdef __cplusplus
}
#endif
#endif
