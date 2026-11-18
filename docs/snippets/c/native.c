#include "emon.h"
#include <stdio.h>
#include <stdlib.h>

int main(void) {
    FILE *f = fopen("scan.pdf", "rb");
    if (!f) return 3;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *pdf = malloc((size_t)n);
    if (!pdf || fread(pdf, 1, (size_t)n, f) != (size_t)n) return 3;
    fclose(f);
    size_t len = (size_t)n;

    // docs:begin
    /* pdf, len: a scanned document's bytes */
    emon_error err = {0};
    /* The turnkey engines: what is next to the library. pdfium (lib/libpdfium.*), the adapter libraries (lib/emon/adapters/: Tesseract) and the
       packs (share/emon/packs/: English and orientation), found as the archive lays them out; $EMON_PDFIUM_LIB, $EMON_ADAPTER_DIR and $EMON_MODELS name others. */
    emon_engines *engines = NULL;
    if (emon_engines_new(&engines, &err) < 0 || emon_engines_defaults(engines, &err) < 0) {
        fprintf(stderr, "%s: %s\n", err.name, err.message);
        emon_free_error(&err);
        return 1;
    }
    if (!(emon_capabilities() & EMON_CAP_PDFIUM)) {
        fprintf(stderr, "pdfium was not found next to the library\n");
        return 1;
    }

    emon_input in = {0};
    in.data = pdf;
    in.len = len;
    emon_buf html = {0};
    if (emon_convert_html(&in, NULL, engines, NULL, &html, &err) < 0) { /* the engines: pdfium draws, the Tesseract adapter reads */
        fprintf(stderr, "%s: %s\n", err.name, err.message);
        emon_free_error(&err);
        return 1;
    }
    FILE *out = fopen("scan.html", "wb");
    fwrite(html.data, 1, html.len, out);
    fclose(out);
    emon_free_buf(&html);
    emon_engines_free(engines);
    // docs:end
    free(pdf);
    return 0;
}
