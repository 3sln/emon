#include "emon.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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
    /* pdf, len: the document's bytes */
    printf("emon %s, native ports: %u\n", emon_version(), emon_capabilities());

    /* One text says what to do, limits included (docs/options.md). */
    const char *options = "ocr=never\nlimit.pages=500";
    emon_error err = {0};
    if (emon_check_options(options, &err) < 0) {
        fprintf(stderr, "bad options: %s\n", err.message);
        emon_free_error(&err);
        return 2;
    }

    emon_input in = {0};
    in.data = pdf;
    in.len = len;

    /* Stage 0 alone is cheap: "pdf 1.4 pages=3 encrypted=false bytes=1234". */
    emon_buf line = {0};
    if (emon_intake(&in, options, 0, &line, &err) == 0) {
        printf("%s\n", (const char *)line.data);
        emon_free_buf(&line);
    }

    emon_model *model = NULL;
    if (emon_convert(&in, options, 0, &model, &err) < 0) {
        /* err.name is the stable name to route on, err.code its category */
        fprintf(stderr, "%s (%s): %s\n", err.name, emon_error_category(err.code), err.message);
        emon_free_error(&err);
        return 1;
    }
    emon_buf json = {0};
    if (emon_model_json(model, 0, &json, &err) == 0) {
        printf("%zu bytes of JSON, %d assets\n", json.len, emon_model_asset_count(model));
        emon_free_buf(&json);
    }
    emon_free_model(model);
    // docs:end
    free(pdf);
    return 0;
}
