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
    // docs:end
    free(pdf);
    return 0;
}
