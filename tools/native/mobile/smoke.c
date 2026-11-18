/* The mobile smoke program (K6, D897; K2, D899): a C program built for a mobile triple against the shipped libraries (`bb mobile --smoke`). It is the docs' quickstart over
 * arguments: it reads a PDF, converts it to HTML with the library and writes the bytes, so the caller can compare them with a golden.
 *   smoke IN.pdf OUT.html            one-shot conversion with `ocr=never` (born-digital: no engine needed)
 *   smoke IN.pdf OUT.html native [PDFIUM_PATH [ADAPTER_PATH [PACKS_DIR]]]
 *                                    the engines: the turnkey set next to the library (emon_engines_defaults: pdfium, lib/emon/adapters/, share/emon/packs/), or the paths named
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
    if (argc < 3) { fprintf(stderr, "usage: smoke IN.pdf OUT.html [native [PDFIUM [ADAPTER [PACKS]]]]\n"); return 2; }
    int native = argc > 3 && strcmp(argv[3], "native") == 0;
    emon_error err = {0};
    emon_engines *engines = NULL;
    if (native) {
        const char *pd = argc > 4 && argv[4][0] ? argv[4] : NULL;
        const char *ad = argc > 5 && argv[5][0] ? argv[5] : NULL;
        const char *pk = argc > 6 && argv[6][0] ? argv[6] : NULL;
        if (emon_engines_new(&engines, &err) < 0) { fprintf(stderr, "%s: %s\n", err.name, err.message); emon_free_error(&err); return 3; }
        if (pk && emon_engines_packs(engines, pk, EMON_PACKS_NEVER, &err) < 0) { fprintf(stderr, "%s: %s\n", err.name, err.message); emon_free_error(&err); return 3; }
        if (ad || pd) {
            if (ad && emon_engines_load(engines, ad, NULL, &err) < 0) { fprintf(stderr, "%s: %s\n", err.name, err.message); emon_free_error(&err); return 3; }
            if (emon_engines_pdfium(engines, pd, &err) < 0) { fprintf(stderr, "%s: %s\n", err.name, err.message); emon_free_error(&err); return 3; }
        } else if (emon_engines_defaults(engines, &err) < 0) { fprintf(stderr, "%s: %s\n", err.name, err.message); emon_free_error(&err); return 3; }
    }
    printf("emon %s caps=%u\n", emon_version(), emon_capabilities());
    if (native) {
        emon_buf d = {0};
        if (emon_engines_describe(engines, &d, &err) == 0) { printf("%s\n", (const char *)d.data); emon_free_buf(&d); }
        if (!(emon_capabilities() & EMON_CAP_PDFIUM)) { fprintf(stderr, "pdfium was not found (caps=%u)\n", emon_capabilities()); return 4; }
    }
    unsigned char *pdf = NULL;
    size_t len = 0;
    if (slurp(argv[1], &pdf, &len) < 0) { fprintf(stderr, "cannot read %s\n", argv[1]); return 5; }
    emon_input in = {0};
    in.data = pdf;
    in.len = len;
    emon_buf html = {0};
    if (emon_convert_html(&in, native ? NULL : "ocr=never", engines, NULL, &html, &err) < 0) {
        fprintf(stderr, "%s: %s\n", err.name, err.message);
        emon_free_error(&err);
        return 6;
    }
    FILE *out = fopen(argv[2], "wb");
    if (!out) { fprintf(stderr, "cannot write %s\n", argv[2]); return 7; }
    fwrite(html.data, 1, html.len, out);
    fclose(out);
    emon_free_buf(&html);
    emon_engines_free(engines);
    free(pdf);
    return 0;
}
