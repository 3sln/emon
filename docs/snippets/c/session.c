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
    uint64_t len = (uint64_t)n;

    // docs:begin
    /* pdf, len: the document, which only you can read. The engine never calls out: it says what it needs, you do it. */
    emon_session *s = NULL;
    emon_error err = {0};
    if (emon_open(len, 0, 0, NULL, 0, "ocr=never", &s, &err) < 0) {
        fprintf(stderr, "%s: %s\n", err.name, err.message);
        return 1;
    }
    emon_step_out out = {0};
    int status;
    while ((status = emon_step(s, &out, &err)) > 0) {
        if (status == EMON_NEED_BYTES) {
            /* read each span of the source and hand it in; the engine reads only what it touches */
            for (size_t i = 0; i < out.nranges; i++) {
                uint64_t off = out.ranges[i].offset, want = out.ranges[i].length;
                if (off + want > len) want = len - off;
                emon_provide(s, off, pdf + off, (size_t)want, &err);
            }
        } else {
            /* EMON_NEED_REQUESTS: out.requests, each to fulfil with emon_answer_ocr, emon_answer_render or emon_answer
               (only with the options js-ocr=1 and js-render=1; without them the session answers `unsupported` itself) */
        }
        emon_free_step(&out);
    }
    if (status < 0) {
        fprintf(stderr, "%s: %s\n", err.name, err.message);
        emon_free_error(&err);
        emon_close(s);
        return 1;
    }
    emon_model *model = NULL;
    emon_finish(s, &model, &err); /* EMON_DONE: the Model, as for emon_convert */
    emon_close(s);
    emon_buf html = {0};
    emon_model_html(model, NULL, &html, &err);
    fwrite(html.data, 1, html.len, stdout);
    emon_free_buf(&html);
    emon_free_model(model);
    // docs:end
    free(pdf);
    return 0;
}
