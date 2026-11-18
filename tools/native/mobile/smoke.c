/* The mobile smoke program (K6, D897): a C program built for a mobile triple against the shipped libemon (`bb mobile --smoke`). It is the docs' quickstart over
 * arguments: it reads a PDF, converts it to HTML with the library and writes the bytes, so the caller can compare them with a golden.
 *   smoke IN.pdf OUT.html            one-shot conversion with `ocr=never` (born-digital: no engine needed)
 *   smoke IN.pdf OUT.html native [PDFIUM_PATH TESSDATA_DIR]
 *                                    the library's own pdfium and Tesseract (EMON_NATIVE); paths optional (NULL: found beside the library, or by name)
 * Prints `emon VERSION caps=N` and exits 0 on success. Compiled with -std=c11 -Wall -Werror. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "emon.h"

static int slurp(const char *path, unsigned char **out, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return -1;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *b = malloc((size_t)n + 1);
    if (!b || fread(b, 1, (size_t)n, f) != (size_t)n) { fclose(f); free(b); return -1; }
    fclose(f);
    *out = b;
    *len = (size_t)n;
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: smoke IN.pdf OUT.html [native [PDFIUM TESSDATA]]\n"); return 2; }
    int native = argc > 3 && strcmp(argv[3], "native") == 0;
    emon_error err = {0};
    if (native) {
        const char *pd = argc > 4 && argv[4][0] ? argv[4] : NULL;
        const char *td = argc > 5 && argv[5][0] ? argv[5] : NULL;
        if (emon_set_native_paths(pd, td, &err) < 0) { fprintf(stderr, "%s: %s\n", err.name, err.message); emon_free_error(&err); return 3; }
    }
    printf("emon %s caps=%u\n", emon_version(), emon_capabilities());
    if (native && (!(emon_capabilities() & EMON_CAP_PDFIUM) || !(emon_capabilities() & EMON_CAP_TESSERACT))) {
        fprintf(stderr, "pdfium or Tesseract not found (caps=%u)\n", emon_capabilities());
        return 4;
    }
    unsigned char *pdf = NULL;
    size_t len = 0;
    if (slurp(argv[1], &pdf, &len) < 0) { fprintf(stderr, "cannot read %s\n", argv[1]); return 5; }
    emon_input in = {0};
    in.data = pdf;
    in.len = len;
    emon_buf html = {0};
    if (emon_convert_html(&in, native ? NULL : "ocr=never", native ? EMON_NATIVE : 0, NULL, &html, &err) < 0) {
        fprintf(stderr, "%s: %s\n", err.name, err.message);
        emon_free_error(&err);
        return 6;
    }
    FILE *out = fopen(argv[2], "wb");
    if (!out) { fprintf(stderr, "cannot write %s\n", argv[2]); return 7; }
    fwrite(html.data, 1, html.len, out);
    fclose(out);
    emon_free_buf(&html);
    free(pdf);
    return 0;
}
