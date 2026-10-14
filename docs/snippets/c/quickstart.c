#include "emon.h"
#include <stdio.h>
#include <stdlib.h>

int main(void) {
    FILE *f = fopen("report.pdf", "rb");
    if (!f) return 3;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    unsigned char *pdf = malloc((size_t)n);
    if (!pdf || fread(pdf, 1, (size_t)n, f) != (size_t)n) return 3;
    fclose(f);
    size_t len = (size_t)n;

    // docs:begin
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
    // docs:end
    free(pdf);
    return 0;
}
