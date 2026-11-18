#include "emon.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* what the app decides about a pack the conversion needs and is not here: this one asks nobody and fetches, but only the language models */
static int on_pack_needed(void *ctx, const emon_pack_request *req, emon_pack_decision *out) {
    (void)ctx;
    printf("needs %s (%s, %llu bytes, %s)\n", req->pack, req->kind, (unsigned long long)req->bytes, req->licence);
    out->action = strcmp(req->kind, "recognizer") == 0 ? EMON_PACK_FETCH : EMON_PACK_DECLINE;
    return 0;
}

static int on_pack_event(void *ctx, const emon_pack_event *ev) {
    (void)ctx;
    if (ev->kind == EMON_PACK_EVENT_PROGRESS) printf("  %s: %llu of %llu\n", ev->pack, (unsigned long long)ev->done, (unsigned long long)ev->total);
    return 0; /* non-zero would cancel the download */
}

int main(void) {
    // docs:begin
    emon_error err = {0};
    emon_engines *engines = NULL;
    emon_engines_new(&engines, &err);
    /* where packs live (the pack directory), and how they come: never fetch, use the cache, or fetch on demand. Name it before registering adapters. */
    emon_engines_packs(engines, "packs", EMON_PACKS_AUTO, &err);
    /* an adapter library (it exports emon_ocr_adapter_v1): Tesseract. Platform engines and your own callbacks register the same way (emon_engines_add_ocr). */
    if (emon_engines_load(engines, "lib/emon/adapters/libemon_ocr_tesseract.so", NULL, &err) < 0) {
        fprintf(stderr, "%s: %s\n", err.name, err.message); /* adapter-runtime-missing, adapter-abi-unsupported, ... */
        emon_free_error(&err);
    }
    emon_on_pack_needed(engines, on_pack_needed, NULL, &err);
    emon_on_pack_event(engines, on_pack_event, NULL, &err);

    /* engines(): every engine that runs here and its state: built-in, installed, downloadable (with size and licence) or unavailable */
    emon_buf json = {0};
    if (emon_engines_describe(engines, &json, &err) == 0) {
        printf("%s\n", (const char *)json.data);
        emon_free_buf(&json);
    }
    /* prepare(options): the packs a conversion needs, which are missing, and what that comes to. Fetches nothing. */
    if (emon_prepare(engines, "locale=de-DE", &json, &err) == 0) {
        printf("%s\n", (const char *)json.data); /* "verdict":"downloadable" ... */
        emon_free_buf(&json);
    }
    /* fetch ahead of time, with progress; every file is checked against the catalogue's SHA-256 before it is kept */
    emon_packs_ensure(engines, "tess-fast-eng,tess-osd", &err);
    emon_engines_free(engines);
    // docs:end
    return 0;
}
