/* THE C TEST PROGRAM of the C binding (DEVLOG P9.4, D473): the API of host/c/include/emon.h through a C compiler, from the repository root.
 * What it holds: the one-shot calls (bytes and a LAZY source) give the goldens; the inverted step API gives the same Model (and misses and
 * re-runs over a small chunk); the named errors, by category and name; option text parsed by kin; assets; record and replay; every output
 * is the caller's to free and the free is safe; a panic does not cross; and a caller's small thread stack is nothing the answer depends on.
 * Prints `ok   WHAT` per check (`FAIL WHAT` otherwise) and exits 1 on any failure. `bb slice` and `bb c` run it. */
#define _POSIX_C_SOURCE 200809L
#define _DEFAULT_SOURCE 1
#define _DARWIN_C_SOURCE 1
#include "emon.h"
#include "emon_test_hooks.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

static int fails = 0, oks = 0;

static void check(int pass, const char *what) {
    if (pass) {
        oks++;
        printf("ok   %s\n", what);
    } else {
        fails++;
        printf("FAIL %s\n", what);
    }
}

static uint8_t *slurp(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        fprintf(stderr, "cannot read %s (run from the repository root)\n", path);
        exit(2);
    }
    size_t cap = 1 << 16, n = 0;
    uint8_t *b = malloc(cap);
    for (;;) {
        n += fread(b + n, 1, cap - n, f);
        if (n < cap) break;
        cap *= 2;
        b = realloc(b, cap);
    }
    fclose(f);
    *len = n;
    return b;
}

static int same(const emon_buf *b, const uint8_t *want, size_t n) { return b->len == n && (n == 0 || memcmp(b->data, want, n) == 0); }

static emon_input bytes_input(const uint8_t *d, size_t n) {
    emon_input in = {0};
    in.data = d;
    in.len = n;
    return in;
}

/* a lazy source over memory that counts what the engine asks for */
typedef struct {
    const uint8_t *d;
    size_t n;
    size_t calls, bytes;
    int fail_after; /* >= 0: answer -1 from that call on */
} memsrc;

static int64_t mem_read(void *ctx, uint64_t off, uint8_t *buf, size_t len) {
    memsrc *s = ctx;
    s->calls++;
    if (s->fail_after >= 0 && s->calls > (size_t)s->fail_after) return -1;
    if (off >= s->n) return 0;
    size_t k = s->n - (size_t)off < len ? s->n - (size_t)off : len;
    memcpy(buf, s->d + off, k);
    s->bytes += k;
    return (int64_t)k;
}

/* run a whole session over memory in chunks of `chunk`; the Model, or NULL with `err` filled */
static emon_model *step_convert(const uint8_t *d, size_t n, uint32_t chunk, const char *options, const char *replay_dir_unused, emon_error *err, size_t *steps) {
    (void)replay_dir_unused;
    emon_session *s = NULL;
    if (emon_open(n, chunk, 0, NULL, NULL, 0, options, &s, err) < 0) return NULL;
    for (;;) {
        emon_step_out o = {0};
        int st = emon_step(s, &o, err);
        if (steps) (*steps)++;
        if (st < 0) {
            emon_close(s);
            return NULL;
        }
        if (st == EMON_DONE) break;
        if (st == EMON_NEED_BYTES) {
            for (size_t i = 0; i < o.nranges; i++) emon_provide(s, o.ranges[i].offset, d + o.ranges[i].offset, (size_t)o.ranges[i].length, err);
        }
        emon_free_step(&o);
    }
    emon_model *m = NULL;
    int r = emon_finish(s, &m, err);
    emon_close(s);
    return r < 0 ? NULL : m;
}

static int err_is(const emon_error *e, int code, const char *name) { return e->code == code && e->name && strcmp(e->name, name) == 0 && e->message && e->message[0]; }

/* a thread with the smallest stack pthreads allow: the engine works on its own */
typedef struct {
    const uint8_t *d;
    size_t n;
    emon_buf json;
    int rc;
} small;

static void *small_thread(void *p) {
    small *t = p;
    emon_input in = bytes_input(t->d, t->n);
    emon_error err = {0};
    t->rc = emon_convert_json(&in, NULL, NULL, 0, &t->json, &err);
    emon_free_error(&err);
    return NULL;
}


/* ------------------------------------------------------------------------------------------------ adapters (K2, D900): stubs in C behind emon_adapter.h */

typedef struct {
    int reads, starts, cancels;
    int mode;            /* 0 a reading with no words, 1 unsupported, 2 failed */
    uint64_t token;
    const emon_adapter_host *host;
    uint32_t engine, needs, flags;
    int req_engine, req_nlangs, req_id_ok;
    int32_t pending_id;
    uint64_t tokens[256];
    int32_t ids[256];
    int npending;
    char reason[96];
} stub;

static int stub_describe(void *ctx, emon_adapter_info *out, emon_adapter_status *st) {
    stub *s = ctx;
    (void)st;
    memset(out, 0, sizeof *out);
    out->engine = s->engine;
    out->caps = 0;
    out->needs = s->needs;
    out->flags = s->flags;
    out->config = "stub";
    out->observed = "stub;v=1";
    return EMON_ADAPTER_OK;
}

/* the smallest valid OIL answer: [len 3 id 0] and a 24-cell header with the engine and a segmentation, and no rows */
static void stub_record(int32_t *rec, int32_t id, uint32_t engine) {
    memset(rec, 0, 28 * sizeof *rec);
    rec[0] = 28;
    rec[1] = emon_const(2);
    rec[2] = id;
    rec[4] = 1;
    rec[5] = (int32_t)engine;
    rec[10] = 3;
}

static int stub_read(void *ctx, const int32_t *req, size_t n, const emon_adapter_image *img, int want_binary, emon_adapter_answer *out, emon_adapter_status *st) {
    stub *s = ctx;
    emon_ocr_request_info info;
    emon_error e = {0};
    (void)img;
    (void)want_binary;
    s->reads++;
    if (emon_ocr_request(req, n, &info, &e) == 0) {
        s->req_engine = info.engine;
        s->req_nlangs = info.nlangs;
        s->req_id_ok = info.id == req[2] && info.w <= 0;
    }
    emon_free_error(&e);
    if (s->mode == 1) {
        strcpy(st->reason, "lang-unavailable");
        strcpy(st->message, "the stub reads nothing");
        return EMON_ADAPTER_UNSUPPORTED;
    }
    if (s->mode == 2) {
        strcpy(st->reason, "boom");
        return EMON_ADAPTER_FAILED;
    }
    out->cells = malloc(28 * sizeof(int32_t));
    out->ncells = 28;
    stub_record(out->cells, req[2], s->engine);
    return EMON_ADAPTER_OK;
}

static void stub_release(void *ctx, emon_adapter_answer *a) {
    (void)ctx;
    free(a->cells);
    a->cells = NULL;
}

static int stub_start(void *ctx, const int32_t *req, size_t n, const emon_adapter_image *img, uint64_t token, emon_adapter_status *st) {
    stub *s = ctx;
    (void)n;
    (void)img;
    (void)st;
    s->starts++;
    s->token = token;
    s->pending_id = req[2];
    if (s->npending < 256) {
        s->tokens[s->npending] = token;
        s->ids[s->npending++] = req[2];
    }
    return EMON_ADAPTER_OK;
}

static void stub_cancel(void *ctx, uint64_t token) {
    stub *s = ctx;
    (void)token;
    s->cancels++;
}

static void stub_bind(void *ctx, const emon_adapter_host *host) { ((stub *)ctx)->host = host; }
static void stub_close(void *ctx) { (void)ctx; }

static emon_ocr_adapter stub_adapter(stub *s, int async) {
    emon_ocr_adapter a = {0};
    a.abi = EMON_ADAPTER_ABI;
    a.size = sizeof a;
    a.name = "stub";
    a.ctx = s;
    a.describe = stub_describe;
    a.read = async ? NULL : stub_read;
    a.start = async ? stub_start : NULL;
    a.cancel = stub_cancel;
    a.release = stub_release;
    a.bind = stub_bind;
    a.close = stub_close;
    return a;
}

static emon_engines *engines_with(stub *s, int async, emon_error *err) {
    emon_engines *e = NULL;
    emon_ocr_adapter a = stub_adapter(s, async);
    if (emon_engines_new(&e, err) < 0) return NULL;
    if (emon_engines_add_ocr(e, &a, err) < 0) {
        emon_engines_free(e);
        return NULL;
    }
    return e;
}

static int pack_asked = 0;
static int pack_action = EMON_PACK_DECLINE;
static const uint8_t *pack_bytes = NULL;
static size_t pack_len = 0;
static char pack_seen[64];
static int on_pack(void *ctx, const emon_pack_request *req, emon_pack_decision *out) {
    (void)ctx;
    pack_asked++;
    snprintf(pack_seen, sizeof pack_seen, "%s", req->pack);
    out->action = pack_action;
    out->data = pack_bytes;
    out->len = pack_len;
    out->engine = "ppocr";
    return 0;
}

static int has(const emon_buf *b, const char *needle) { return b->data && strstr((const char *)b->data, needle) != NULL; }

static void test_engines(const uint8_t *scan, size_t sn) {
    emon_error err = {0};
    emon_buf j0 = {0}, j1 = {0}, j2 = {0}, b = {0};
    emon_input in = bytes_input(scan, sn);

    /* registration: refused by name, at registration */
    stub bad = {0};
    emon_engines *e = NULL;
    emon_engines_new(&e, &err);
    emon_ocr_adapter a = stub_adapter(&bad, 0);
    bad.engine = EMON_OEN_TESSERACT;
    a.abi = EMON_ADAPTER_ABI + 1;
    check(emon_engines_add_ocr(e, &a, &err) < 0 && err.name && strcmp(err.name, "adapter-abi-unsupported") == 0, "an adapter built for a newer ABI is refused: adapter-abi-unsupported");
    emon_free_error(&err);
    a = stub_adapter(&bad, 0);
    bad.engine = 77;
    check(emon_engines_add_ocr(e, &a, &err) < 0 && err.name && strcmp(err.name, "adapter-engine-unknown") == 0, "an engine kin's table does not have: adapter-engine-unknown");
    emon_free_error(&err);
    bad.engine = EMON_OEN_APPLE_VISION;
    bad.needs = EMON_PACK_RECOGNIZER;
    check(emon_engines_add_ocr(e, &a, &err) < 0 && err.name && strcmp(err.name, "adapter-needs-beyond-engine") == 0, "Vision needs no pack: adapter-needs-beyond-engine");
    emon_free_error(&err);
    bad.engine = EMON_OEN_TESSERACT;
    bad.needs = 0;
    bad.flags = EMON_ADAPTER_REMOTE;
    check(emon_engines_add_ocr(e, &a, &err) < 0 && err.name && strcmp(err.name, "adapter-remote-refused") == 0, "pixels off the device need allow_remote: adapter-remote-refused");
    emon_free_error(&err);
    check(emon_engines_load(e, "/no/such/adapter.so", NULL, &err) < 0 && err.name && strcmp(err.name, "adapter-runtime-missing") == 0, "a library that is not there: adapter-runtime-missing");
    emon_free_error(&err);
    check(emon_engines_add_ocr(NULL, &a, &err) == -EMON_ERR_USAGE, "NULL engines are a usage error");
    emon_free_error(&err);
    emon_engines_free(e);

    /* an adapter that cannot read is no adapter: the page is unread, never blank */
    check(emon_convert_json(&in, "ocr=always\n", NULL, 0, &j0, &err) == 0, "a scan with no engines converts (unread)");
    stub s1 = {0};
    s1.engine = EMON_OEN_TESSERACT;
    s1.mode = 1;
    e = engines_with(&s1, 0, &err);
    check(e != NULL && emon_convert_json(&in, "ocr=always\n", e, 0, &j1, &err) == 0, "a scan with an adapter that says unsupported converts");
    printf("     (the adapter was asked %d times)\n", s1.reads);
    check(s1.reads > 0 && s1.req_engine == EMON_OEN_TESSERACT && s1.req_id_ok, "the adapter is handed Tesseract's requests; emon_ocr_request reads their fields");
    check(j0.len == j1.len && memcmp(j0.data, j1.data, j0.len) == 0, "unsupported by an adapter is unread, exactly as no adapter: never blank");
    emon_buf notes = {0};
    check(emon_engines_notes(e, &notes, &err) == 0 && has(&notes, "stub: unsupported lang-unavailable"), "the reason is in the notes");
    emon_free_buf(&notes);
    emon_free_buf(&j1);
    s1.mode = 2;
    check(emon_convert_json(&in, "ocr=always\n", e, 0, &j1, &err) == 0 && j1.len == j0.len, "an adapter that fails leaves the page unread and the conversion goes on");
    emon_free_buf(&j1);
    s1.mode = 0;
    check(emon_convert_json(&in, "ocr=always\n", e, 0, &j1, &err) == 0 && !(j1.len == j0.len && memcmp(j1.data, j0.data, j0.len) == 0), "an adapter that reads is a reading, not the unread page");

    /* the step API answers its adapter's requests itself and gives the one-shot's bytes */
    int reads_before = s1.reads;
    emon_session *sess = NULL;
    check(emon_open(sn, 0, 0, e, NULL, 0, "ocr=always\n", &sess, &err) == 0, "emon_open with engines");
    int needed_requests = 0, st;
    emon_step_out o = {0};
    while ((st = emon_step(sess, &o, &err)) > 0) {
        if (st == EMON_NEED_BYTES)
            for (size_t i = 0; i < o.nranges; i++) emon_provide(sess, o.ranges[i].offset, scan + o.ranges[i].offset, (size_t)o.ranges[i].length, &err);
        if (st == EMON_NEED_REQUESTS) needed_requests++;
        emon_free_step(&o);
    }
    emon_model *m = NULL;
    check(st == 0 && emon_finish(sess, &m, &err) == 0, "the step loop finishes");
    emon_close(sess);
    check(needed_requests == 0 && s1.reads > reads_before, "emon_step answered the adapter's ocr requests itself: none came out");
    check(m && emon_model_json(m, 0, &j2, &err) == 0 && j2.len == j1.len && memcmp(j1.data, j2.data, j1.len) == 0, "the session's Model is the one-shot's");
    emon_free_model(m);
    emon_free_buf(&j2);
    emon_free_buf(&j0);
    emon_engines_free(e);

    /* routing by engine: an adapter for PP-OCR does not see Tesseract's requests; the hybrid sends it its own */
    stub pp = {0}, tt = {0};
    pp.engine = EMON_OEN_PPOCR;
    tt.engine = EMON_OEN_TESSERACT;
    e = engines_with(&pp, 0, &err);
    emon_convert_json(&in, "ocr=always\n", e, 0, &b, &err);
    check(pp.reads == 0, "PP-OCR's adapter is not asked for a Tesseract request");
    emon_free_buf(&b);
    emon_engines_free(e);
    {
        emon_ocr_adapter ta = stub_adapter(&tt, 0), pa = stub_adapter(&pp, 0);
        emon_engines_new(&e, &err);
        emon_engines_add_ocr(e, &ta, &err);
        emon_engines_add_ocr(e, &pa, &err);
    }
    int ok = emon_convert_json(&in, "ocr=always\nocr-engine=ppocr\n", e, 0, &b, &err);
    check(ok == 0 && pp.reads > 0 && pp.req_engine == EMON_OEN_PPOCR, "with ocr-engine=ppocr it is asked for PP-OCR's");
    emon_free_buf(&b);
    emon_engines_free(e);

    /* async: start returns at once, the step says WAITING, emon_complete answers, and the Model is the sync reading's */
    stub as = {0};
    as.engine = EMON_OEN_TESSERACT;
    as.flags = EMON_ADAPTER_ASYNC;
    e = engines_with(&as, 1, &err);
    emon_open(sn, 0, 0, e, NULL, 0, "ocr=always\n", &sess, &err);
    int waited = 0, completed = 0;
    while ((st = emon_step(sess, &o, &err)) > 0) {
        if (st == EMON_NEED_BYTES)
            for (size_t i = 0; i < o.nranges; i++) emon_provide(sess, o.ranges[i].offset, scan + o.ranges[i].offset, (size_t)o.ranges[i].length, &err);
        if (st == EMON_WAITING) {
            int32_t rec[28];
            emon_error bogus = {0};
            waited++;
            check(emon_wait(sess, 0, &err) == 0, "emon_wait times out when nothing has arrived");
            check(emon_complete(sess, as.token + 1000, NULL, 0, &bogus, &err) == -EMON_ERR_USAGE, "emon_complete for a token that is not this session's is a usage error");
            emon_free_error(&err);
            /* every request of the round was started: answer them all, as an engine of the program's own would */
            for (int i = 0; i < as.npending; i++) {
                stub_record(rec, as.ids[i], EMON_OEN_TESSERACT);
                if (emon_complete(sess, as.tokens[i], rec, 28, NULL, &err) == 0) completed++;
                else fprintf(stderr, "emon_complete: %s: %s\n", err.name, err.message);
                emon_free_error(&err);
            }
            as.npending = 0;
            check(emon_wait(sess, 1000, &err) == 1, "emon_wait returns once an answer has arrived");
            if (waited > 20) break;
        }
        emon_free_step(&o);
    }
    check(st == 0 && waited >= 1 && completed >= 1 && as.starts >= 1, "emon_step says EMON_WAITING while only async work is outstanding, and takes the answer emon_complete gave");
    m = NULL;
    j2 = (emon_buf){0};
    check(emon_finish(sess, &m, &err) == 0 && emon_model_json(m, 0, &j2, &err) == 0 && j2.len == j1.len && memcmp(j1.data, j2.data, j1.len) == 0, "the async reading gives the sync reading's Model");
    emon_free_model(m);
    emon_free_buf(&j2);
    emon_free_buf(&j1);
    emon_close(sess);
    emon_engines_free(e);

    /* closing a session with work outstanding cancels it */
    stub as2 = {0};
    as2.engine = EMON_OEN_TESSERACT;
    as2.flags = EMON_ADAPTER_ASYNC;
    e = engines_with(&as2, 1, &err);
    emon_open(sn, 0, 0, e, NULL, 0, "ocr=always\n", &sess, &err);
    while ((st = emon_step(sess, &o, &err)) == EMON_NEED_BYTES) {
        for (size_t i = 0; i < o.nranges; i++) emon_provide(sess, o.ranges[i].offset, scan + o.ranges[i].offset, (size_t)o.ranges[i].length, &err);
        emon_free_step(&o);
    }
    check(st == EMON_WAITING, "waiting on an adapter that never answers");
    emon_close(sess);
    check(as2.cancels >= 1, "closing the session cancels the work still outstanding");
    emon_engines_free(e);
}


/* packs: the pack directory, prepare and engines as JSON, the hook's decisions, and every pack verified against the catalogue (the real English model: its hash is the catalogue's) */
static void test_packs(const uint8_t *scan, size_t sn) {
    emon_error err = {0};
    emon_buf b = {0}, notes = {0};
    emon_input in = bytes_input(scan, sn);
    char dir[] = "/tmp/emon-c-packs-XXXXXX";
    size_t tn = 0;
    FILE *tf = fopen(".cache/native/dl/eng.traineddata", "rb");
    uint8_t *good = NULL;
    if (tf) {
        fclose(tf);
        good = slurp(".cache/native/dl/eng.traineddata", &tn);
    }
    if (!mkdtemp(dir)) {
        check(0, "a temporary directory");
        return;
    }
    stub s = {0};
    s.engine = EMON_OEN_TESSERACT;
    s.needs = EMON_PACK_RECOGNIZER;
    emon_engines *e = NULL;
    emon_ocr_adapter a = stub_adapter(&s, 0);
    emon_engines_new(&e, &err);
    check(emon_engines_packs(e, dir, EMON_PACKS_CACHE, &err) == 0, "emon_engines_packs names the pack directory and the mode");
    check(emon_engines_packs(e, dir, 99, &err) == -EMON_ERR_USAGE, "a mode that is none of the three is a usage error");
    emon_free_error(&err);
    check(emon_engines_add_ocr(e, &a, &err) >= 0, "an adapter that needs a recognizer pack registers");
    check(emon_engines_packs(e, dir, EMON_PACKS_CACHE, &err) == -EMON_ERR_USAGE && err.message && strstr(err.message, "before registering"), "the pack directory is named before the adapters are registered");
    emon_free_error(&err);

    check(emon_prepare(e, "ocr=auto\n", &b, &err) == 0 && has(&b, "\"verdict\":\"blocked\"") && has(&b, "\"id\":\"tess-fast-eng\",\"state\":\"missing\"") && has(&b, "\"id\":\"tess-osd\""), "emon_prepare: English and orientation are missing and the mode fetches nothing: blocked");
    emon_free_buf(&b);
    check(emon_prepare(e, "locale=de-DE\n", &b, &err) == 0 && has(&b, "tess-fast-deu") && has(&b, "\"languages\":[\"deu\"]"), "emon_prepare names the locale's language pack");
    emon_free_buf(&b);
    check(emon_prepare(e, "ocr=never\n", &b, &err) == 0 && has(&b, "\"verdict\":\"ready\"") && has(&b, "\"packs\":[]"), "ocr=never needs nothing: ready");
    emon_free_buf(&b);
    check(emon_prepare(e, "bogus=1\n", &b, &err) == -EMON_ERR_USAGE, "emon_prepare: an option text that does not parse is a usage error");
    emon_free_error(&err);
    check(emon_engines_describe(e, &b, &err) == 0 && has(&b, "\"name\":\"tesseract\"") && has(&b, "\"state\":\"unavailable\"") && has(&b, "\"mode\":\"cache\""), "emon_engines_describe: Tesseract is registered and its packs are missing, which this mode cannot fetch: unavailable");
    emon_free_buf(&b);

    /* the hook: decline */
    emon_on_pack_needed(e, on_pack, NULL, &err);
    pack_action = EMON_PACK_DECLINE;
    check(emon_convert_json(&in, "ocr=always\n", e, 0, &b, &err) == 0 && pack_asked > 0 && strcmp(pack_seen, "tess-fast-eng") == 0 && s.reads == 0, "decline: the hook is asked for English, the adapter is never called");
    emon_free_buf(&b);
    emon_engines_notes(e, &notes, &err);
    check(has(&notes, "pack-missing:tess-fast-eng"), "the page is unread with pack-missing:<id>");
    emon_free_buf(&notes);
    /* substitute: another engine would answer under its own name: unread */
    pack_action = EMON_PACK_SUBSTITUTE;
    emon_convert_json(&in, "ocr=always\n", e, 0, &b, &err);
    emon_free_buf(&b);
    emon_engines_notes(e, &notes, &err);
    check(has(&notes, "pack-substitute-differs:ppocr") && s.reads == 0, "substitute: another OIL engine leaves the request unread");
    emon_free_buf(&notes);
    /* fetch: the mode is cache, so a fetch answer is a refusal (never the network) */
    pack_action = EMON_PACK_FETCH;
    emon_convert_json(&in, "ocr=always\n", e, 0, &b, &err);
    emon_free_buf(&b);
    check(s.reads == 0, "fetch under `cache`: nothing is fetched");
    /* never wins over the hook */
    emon_engines_free(e);
    emon_engines_new(&e, &err);
    emon_engines_packs(e, dir, EMON_PACKS_NEVER, &err);
    emon_engines_add_ocr(e, &a, &err);
    emon_on_pack_needed(e, on_pack, NULL, &err);
    pack_asked = 0;
    pack_action = EMON_PACK_PROVIDE;
    pack_bytes = good;
    pack_len = tn;
    emon_convert_json(&in, "ocr=always\n", e, 0, &b, &err);
    emon_free_buf(&b);
    check(pack_asked == 0 && s.reads == 0, "EMON_PACKS_NEVER wins over the hook: it is not asked");
    check(emon_packs_ensure(e, "tess-fast-eng", &err) < 0, "emon_packs_ensure under `never` is refused");
    emon_free_error(&err);
    check(emon_packs_ensure(e, "no-such-pack", &err) < 0 && err.name && strcmp(err.name, "unknown-pack") == 0, "an unknown pack id is unknown-pack");
    emon_free_error(&err);
    emon_engines_free(e);

    if (good) {
        /* provide: the catalogue's own bytes are verified and kept, and the adapter reads */
        emon_engines_new(&e, &err);
        emon_engines_packs(e, dir, EMON_PACKS_CACHE, &err);
        emon_engines_add_ocr(e, &a, &err);
        emon_on_pack_needed(e, on_pack, NULL, &err);
        pack_asked = 0;
        s.reads = 0;
        pack_action = EMON_PACK_PROVIDE;
        pack_bytes = good;
        pack_len = tn;
        check(emon_convert_json(&in, "ocr=always\n", e, 0, &b, &err) == 0 && s.reads > 0 && pack_asked == 1, "provide: bytes that are the catalogue's are used and the adapter reads");
        emon_free_buf(&b);
        char kept[256];
        snprintf(kept, sizeof kept, "%s/tess-fast-eng/eng.traineddata", dir);
        check(access(kept, R_OK) == 0, "and kept in the pack directory");
        check(emon_prepare(e, "ocr=auto\n", &b, &err) == 0 && has(&b, "\"id\":\"tess-fast-eng\",\"state\":\"installed\""), "emon_prepare then says English is installed");
        emon_free_buf(&b);
        emon_engines_free(e);
        /* tampered: one byte flipped. Never used, and nothing is kept */
        unlink(kept);
        good[1000] ^= 1;
        emon_engines_new(&e, &err);
        emon_engines_packs(e, dir, EMON_PACKS_CACHE, &err);
        emon_engines_add_ocr(e, &a, &err);
        emon_on_pack_needed(e, on_pack, NULL, &err);
        s.reads = 0;
        pack_bytes = good;
        emon_convert_json(&in, "ocr=always\n", e, 0, &b, &err);
        emon_free_buf(&b);
        emon_engines_notes(e, &notes, &err);
        check(s.reads == 0 && access(kept, R_OK) != 0 && has(&notes, "pack-hash-mismatch:tess-fast-eng"), "tampered bytes are never used: pack-hash-mismatch, unread, nothing kept");
        emon_free_buf(&notes);
        good[1000] ^= 1;
        /* a tampered file already in the pack directory is deleted */
        {
            char pdir[256];
            snprintf(pdir, sizeof pdir, "%s/tess-fast-eng", dir);
            mkdir(pdir, 0700);
            FILE *f = fopen(kept, "wb");
            good[2000] ^= 1;
            fwrite(good, 1, tn, f);
            fclose(f);
            good[2000] ^= 1;
            pack_action = EMON_PACK_DECLINE;
            emon_convert_json(&in, "ocr=always\n", e, 0, &b, &err);
            emon_free_buf(&b);
            check(access(kept, R_OK) != 0, "a file in the pack directory that does not match is deleted");
        }
        emon_engines_free(e);
        free(good);
    } else {
        printf("     SKIP the provide and tamper checks: .cache/native/dl/eng.traineddata is not here (bb native fetches it)\n");
    }
    (void)rmdir;
}

int main(void) {
    const char *DOC = "test/corpus/synth/pdf/h-plain.pdf";
    size_t n, gn, hn;
    uint8_t *doc = slurp(DOC, &n);
    uint8_t *golden = slurp("test/para/h-plain.json", &gn);
    uint8_t *html_golden = slurp("test/slice/h-plain.html", &hn);
    if (gn && golden[gn - 1] == '\n') gn--;
    emon_error err = {0};
    emon_buf b = {0};

    /* the library */
    check(emon_version() && strlen(emon_version()) > 0, "emon_version is a string");
    check(emon_capabilities() <= 5 && (emon_capabilities() & 2) == 0, "emon_capabilities is a set of pdfium and the codecs: Tesseract is an adapter, not a bit");
    check(emon_const(0) != emon_const(2) &&emon_const(2) != emon_const(5) && emon_const(10) != emon_const(12) && emon_const(99) == -1, "emon_const: kin's numbers of the plan/fulfil protocol");
    check(strcmp(emon_error_category(EMON_ERR_LIMIT), "limit") == 0 && emon_error_category(-EMON_ERR_IO) != NULL && emon_error_category(99) == NULL, "emon_error_category names the categories");

    /* the options grammar is kin's */
    check(emon_is_option_key("ocr") == 1 && emon_is_option_key("limit.pages") == 1 && emon_is_option_key("nonsense") == 0, "emon_is_option_key asks kin");
    check(emon_check_options("ocr=never\nassets=images\nlimit.pages=50\n", &err) == 0 && err.code == 0, "emon_check_options: a good text");
    check(emon_check_options("ocr=sometimes\n", &err) == -EMON_ERR_USAGE && err_is(&err, EMON_ERR_USAGE, "usage"), "emon_check_options: a bad word is a usage error with kin's sentence");
    printf("     (kin says: %s)\n", err.message);
    emon_free_error(&err);
    check(err.code == 0 && err.name == NULL && err.message == NULL, "emon_free_error clears it (and is safe to repeat)");
    emon_free_error(&err);

    /* one-shot: bytes in, JSON and HTML out */
    emon_input in = bytes_input(doc, n);
    check(emon_convert_json(&in, "ocr=never\n", NULL, 1, &b, &err) == 0 && same(&b, golden, gn), "emon_convert_json (pretty) of h-plain is test/para/h-plain.json");
    check(b.data && b.data[b.len] == 0, "an emon_buf is NUL-terminated after len");
    emon_free_buf(&b);
    check(b.data == NULL && b.len == 0, "emon_free_buf clears the buffer");
    emon_free_buf(&b);
    check(emon_convert_html(&in, NULL, NULL, NULL, &b, &err) == 0 && same(&b, html_golden, hn), "emon_convert_html of h-plain is test/slice/h-plain.html");
    emon_free_buf(&b);

    /* the Model: its outputs, many times, and its stats */
    emon_model *m = NULL;
    check(emon_convert(&in, "keep-evidence=1\n", NULL, &m, &err) == 0 && m != NULL, "emon_convert gives a Model");
    emon_buf j1 = {0}, j2 = {0};
    check(emon_model_json(m, 1, &j1, &err) == 0 && emon_model_json(m, 1, &j2, &err) == 0 && j1.len == j2.len && memcmp(j1.data, j2.data, j1.len) == 0, "a Model renders the same JSON twice");
    emon_free_buf(&j1);
    emon_free_buf(&j2);
    check(emon_model_evidence(m, &b, &err) == 0 && b.len > 10, "keep-evidence=1 keeps the evidence dump");
    emon_free_buf(&b);
    emon_stats st;
    check(emon_model_stats(m, &st) == 0 && st.attempts >= 1 && st.input_bytes == n, "emon_model_stats: what the loop did");
    check(emon_model_asset_count(m) == 0, "no assets unless the options ask for them");
    check(emon_model_html(m, "bogus", &b, &err) == -EMON_ERR_USAGE && b.data == NULL, "emon_model_html: an unknown html option is a usage error and gives nothing");
    emon_free_error(&err);
    emon_free_model(m);
    emon_free_model(NULL);

    /* a LAZY source: the engine reads spans, not the file */
    memsrc ms = {doc, n, 0, 0, -1};
    emon_source src = {n, mem_read, &ms};
    emon_input lin = {0};
    lin.source = &src;
    check(emon_convert_json(&lin, "chunk=512\n", NULL, 1, &b, &err) == 0 && same(&b, golden, gn) && ms.calls > 0, "a lazy source (chunk=512) gives the same JSON");
    printf("     (%zu reads, %zu of %zu bytes)\n", ms.calls, ms.bytes, n);
    emon_free_buf(&b);
    memsrc bad = {doc, n, 0, 0, 1};
    src.ctx = &bad;
    check(emon_convert_json(&lin, "chunk=512\n", NULL, 0, &b, &err) == -EMON_ERR_IO && err_is(&err, EMON_ERR_IO, "io"), "a failed read is io");
    emon_free_error(&err);
    memsrc shortsrc = {doc, n / 2, 0, 0, -1};
    emon_source src2 = {n, mem_read, &shortsrc};
    lin.source = &src2;
    check(emon_convert_json(&lin, "chunk=512\n", NULL, 0, &b, &err) == -EMON_ERR_SOURCE_CHANGED && err_is(&err, EMON_ERR_SOURCE_CHANGED, "source-changed"), "a source that gives fewer bytes than its length is source-changed");
    emon_free_error(&err);

    /* stage 0 */
    check(emon_intake(&in, NULL, &b, &err) == 0 && b.len > 4 && memcmp(b.data, "pdf ", 4) == 0 && strstr((char *)b.data, "encrypted=false"), "emon_intake: the stage 0 line");
    printf("     (%s)\n", (char *)b.data);
    emon_free_buf(&b);

    /* the named errors */
    size_t ln, pn, xn;
    uint8_t *locked = slurp("test/pdf/p3/crypt-aes-128-user.pdf", &ln);
    uint8_t *plain = slurp("test/pdf/p3/plain.pdf", &pn);
    uint8_t *notpdf = slurp("test/pdf/notpdf.pdf", &xn);
    emon_input li = bytes_input(locked, ln);
    check(emon_convert_json(&li, NULL, NULL, 0, &b, &err) == -EMON_ERR_PASSWORD_REQUIRED && err_is(&err, EMON_ERR_PASSWORD_REQUIRED, "password-required"), "a locked PDF: password-required");
    emon_free_error(&err);
    li.password = (const uint8_t *)"wrong";
    li.password_len = 5;
    check(emon_convert_json(&li, NULL, NULL, 0, &b, &err) == -EMON_ERR_PASSWORD_INCORRECT && err_is(&err, EMON_ERR_PASSWORD_INCORRECT, "password-incorrect"), "a wrong password: password-incorrect");
    emon_free_error(&err);
    li.password = (const uint8_t *)"user-secret";
    li.password_len = 11;
    emon_buf locked_json = {0}, plain_json = {0};
    emon_input pi = bytes_input(plain, pn);
    check(emon_convert_json(&li, NULL, NULL, 0, &locked_json, &err) == 0 && emon_convert_json(&pi, NULL, NULL, 0, &plain_json, &err) == 0 && same(&locked_json, plain_json.data, plain_json.len),
          "the right password opens the locked file to the plain twin's Model");
    emon_free_buf(&locked_json);
    emon_free_buf(&plain_json);
    emon_input ni = bytes_input(notpdf, xn);
    check(emon_convert_json(&ni, NULL, NULL, 0, &b, &err) == -EMON_ERR_UNSUPPORTED_FORMAT && err_is(&err, EMON_ERR_UNSUPPORTED_FORMAT, "unsupported-format"), "not a PDF: unsupported-format");
    emon_free_error(&err);
    check(emon_convert_json(&in, "limit.input-bytes=100\n", NULL, 0, &b, &err) == -EMON_ERR_LIMIT && err_is(&err, EMON_ERR_LIMIT, "input-bytes"), "limit.input-bytes=100: a typed breach, named by kin");
    printf("     (%s)\n", err.message);
    emon_free_error(&err);
    check(emon_convert_json(&in, "record=1\n", NULL, 0, &b, &err) == -EMON_ERR_USAGE, "record=1 is a session's option: a one-shot refuses it");
    emon_free_error(&err);
    check(emon_convert_json(&in, "js-render=1\n", NULL, 0, &b, &err) == -EMON_ERR_USAGE, "js-render=1 is a session's option: a one-shot refuses it");
    emon_free_error(&err);
    check(emon_convert_json(NULL, NULL, NULL, 0, &b, &err) == -EMON_ERR_USAGE && emon_convert_json(&in, NULL, NULL, 0, NULL, &err) == -EMON_ERR_USAGE, "NULL arguments are usage errors, not crashes");
    emon_free_error(&err);
    emon_input empty = {0};
    check(emon_convert_json(&empty, NULL, NULL, 0, &b, NULL) < 0, "an empty input is an error (and a NULL emon_error is allowed)");
    /* stderr is captured around the next two calls (D554): a named limit reached INSIDE kin is an answer and prints nothing; a real panic still prints */
    fflush(stderr);
    int saved_stderr = dup(2);
    FILE *captured = tmpfile();
    dup2(fileno(captured), 2);
    int pages_rc = emon_convert_json(&in, "limit.pages=0\n", NULL, 0, &b, &err);
    int lim_named = pages_rc == -EMON_ERR_LIMIT && err_is(&err, EMON_ERR_LIMIT, "pages");
    emon_free_error(&err);
    fflush(stderr);
    off_t printed_for_limit = lseek(fileno(captured), 0, SEEK_END);
    check(emon_test_panic(&err) == -EMON_ERR_INTERNAL && err_is(&err, EMON_ERR_INTERNAL, "internal") && strstr(err.message, "on purpose"), "a panic is caught at the boundary: internal, with its message");
    emon_free_error(&err);
    fflush(stderr);
    off_t printed_in_all = lseek(fileno(captured), 0, SEEK_END);
    dup2(saved_stderr, 2);
    close(saved_stderr);
    fclose(captured);
    check(lim_named, "limit.pages=0: a typed breach inside kin, named by kin");
    check(printed_for_limit == 0, "a named limit prints nothing on stderr (it printed a Rust panic notice)");
    check(printed_in_all > printed_for_limit, "a real panic still goes through the panic hook (nothing is hidden)");

    /* the inverted API: the same Model, over a chunk that misses */
    size_t steps = 0;
    emon_model *sm = step_convert(doc, n, 512, NULL, NULL, &err, &steps);
    check(sm != NULL && emon_model_json(sm, 1, &b, &err) == 0 && same(&b, golden, gn), "emon_open/step/provide/finish over 512-byte chunks give the one-shot's JSON");
    emon_free_buf(&b);
    check(emon_model_stats(sm, &st) == 0 && st.attempts > 1 && st.fetch_rounds > 0 && st.bytes_fetched <= st.input_bytes, "it missed and re-ran, and fetched no more than the file");
    printf("     (%zu steps, %d attempts, %d fetch rounds, %llu of %llu bytes)\n", steps, st.attempts, st.fetch_rounds, (unsigned long long)st.bytes_fetched, (unsigned long long)st.input_bytes);
    emon_free_model(sm);
    sm = step_convert(doc, n, 0, NULL, NULL, &err, NULL);
    check(sm != NULL && emon_model_html(sm, "", &b, &err) == 0 && same(&b, html_golden, hn), "the default chunk (the options' own) gives the HTML golden");
    emon_free_buf(&b);
    emon_free_model(sm);

    emon_session *s = NULL;
    check(emon_open(n, 0, 0, NULL, NULL, 0, "bogus=1\n", &s, &err) == -EMON_ERR_USAGE && s == NULL, "emon_open: option text is parsed by kin");
    emon_free_error(&err);
    check(emon_open(n, 1, 0, NULL, NULL, 0, "limit.input-bytes=100\n", &s, &err) == -EMON_ERR_LIMIT && s == NULL, "emon_open: the input-bytes bound is checked on the length before a cache is made");
    emon_free_error(&err);
    check(emon_open(n, 0, 0, NULL, NULL, 0, NULL, &s, &err) == 0 && s != NULL, "emon_open");
    emon_step_out so = {0};
    check(emon_answer(s, NULL, 0, &err) == -EMON_ERR_USAGE, "an answer when none is owed is a usage error");
    emon_free_error(&err);
    /* P11.4c: a renderer's answer when none is owed is a usage error, and the pixels bound is kin's (default and an override) */
    uint8_t px[3] = {255, 255, 255};
    check(emon_answer_render(s, 1, 1, 1, px, 3, &err) == -EMON_ERR_USAGE, "an emon_answer_render when no request is owed is a usage error");
    emon_free_error(&err);
    int64_t lim = 0;
    check(emon_limit(s, 9, &lim, &err) == 0 && lim == emon_const(109) && lim > 0, "emon_limit: pixels (kind 9) is kin's default, as emon_const(100 + 9) says");
    check(emon_limit(s, 9, NULL, &err) == -EMON_ERR_USAGE, "emon_limit: a NULL output is a usage error");
    emon_free_error(&err);
    emon_close(s);
    s = NULL;
    check(emon_open(n, 0, 0, NULL, NULL, 0, "js-render=1\nlimit.pixels=1000\n", &s, &err) == 0 && s != NULL && emon_limit(s, 9, &lim, &err) == 0 && lim == 1000, "emon_open: js-render=1 and limit.pixels=1000 -- emon_limit says 1000");
    emon_close(s);
    s = NULL;
    check(emon_open(n, 0, 0, NULL, NULL, 0, NULL, &s, &err) == 0 && s != NULL, "emon_open (again)");
    int first = emon_step(s, &so, &err);
    check(first == EMON_NEED_BYTES && so.nranges > 0 && so.ranges[0].length > 0, "the first step needs bytes, and says which");
    emon_free_step(&so);
    check(so.ranges == NULL && so.nranges == 0, "emon_free_step clears it");
    emon_close(s);
    emon_close(NULL);

    /* intake by the inverted API: prefetch is the first and last chunk */
    s = NULL;
    check(emon_open(n, 512, EMON_OPEN_INTAKE, NULL, NULL, 0, NULL, &s, &err) == 0, "emon_open (intake)");
    int pr = emon_prefetch(s, &so, &err);
    check(pr >= 1 && so.nranges == (size_t)pr, "emon_prefetch: the first and the last chunk");
    for (size_t i = 0; i < so.nranges; i++) emon_provide(s, so.ranges[i].offset, doc + so.ranges[i].offset, (size_t)so.ranges[i].length, &err);
    emon_free_step(&so);
    for (;;) {
        int st2 = emon_step(s, &so, &err);
        if (st2 <= 0) {
            check(st2 == EMON_DONE, "the intake session finishes");
            break;
        }
        for (size_t i = 0; i < so.nranges; i++) emon_provide(s, so.ranges[i].offset, doc + so.ranges[i].offset, (size_t)so.ranges[i].length, &err);
        emon_free_step(&so);
    }
    check(emon_finish_intake(s, &b, &err) == 0 && memcmp(b.data, "pdf ", 4) == 0, "emon_finish_intake: the stage 0 line");
    emon_free_buf(&b);
    check(emon_step(s, &so, &err) < 0, "a finished session does not step");
    emon_free_error(&err);
    emon_close(s);

    /* a source that changed under the session */
    s = NULL;
    emon_open(n, 512, 0, NULL, NULL, 0, NULL, &s, &err);
    int lastst = 0;
    for (int guard = 0; guard < 1000; guard++) {
        lastst = emon_step(s, &so, &err);
        if (lastst != EMON_NEED_BYTES) break;
        emon_fail(s, 1, "the file is shorter than it was", &err);
        emon_free_step(&so);
    }
    check(lastst == -EMON_ERR_SOURCE_CHANGED && err_is(&err, EMON_ERR_SOURCE_CHANGED, "source-changed"), "emon_fail(short): source-changed");
    emon_free_error(&err);
    emon_close(s);

    /* record, and replay of that transcript */
    s = NULL;
    emon_open(n, 0, 0, NULL, NULL, 0, "record=1\n", &s, &err);
    for (;;) {
        int st3 = emon_step(s, &so, &err);
        if (st3 <= 0) break;
        for (size_t i = 0; i < so.nranges; i++) emon_provide(s, so.ranges[i].offset, doc + so.ranges[i].offset, (size_t)so.ranges[i].length, &err);
        emon_free_step(&so);
    }
    emon_files tf = {0};
    check(emon_transcript(s, &tf, &err) == 0 && tf.n >= 1 && strcmp(tf.files[0].name, "transcript.edn") == 0, "record=1 keeps a transcript: transcript.edn first");
    emon_model *rm = NULL;
    emon_finish(s, &rm, &err);
    emon_close(s);
    s = NULL;
    emon_open(n, 0, 0, NULL, NULL, 0, NULL, &s, &err);
    for (size_t i = 0; i < tf.n; i++) emon_replay_file(s, tf.files[i].name, tf.files[i].data, tf.files[i].len, &err);
    check(emon_replay_end(s, &err) == 0, "the transcript replays: emon_replay_file, emon_replay_end");
    for (;;) {
        int st4 = emon_step(s, &so, &err);
        if (st4 <= 0) break;
        for (size_t i = 0; i < so.nranges; i++) emon_provide(s, so.ranges[i].offset, doc + so.ranges[i].offset, (size_t)so.ranges[i].length, &err);
        emon_free_step(&so);
    }
    emon_model *pm = NULL;
    emon_finish(s, &pm, &err);
    emon_buf rj = {0}, pj = {0};
    check(rm && pm && emon_model_json(rm, 0, &rj, &err) == 0 && emon_model_json(pm, 0, &pj, &err) == 0 && rj.len == pj.len && memcmp(rj.data, pj.data, rj.len) == 0, "the replay's Model is the recording's");
    emon_free_buf(&rj);
    emon_free_buf(&pj);
    emon_free_model(rm);
    emon_free_model(pm);
    emon_free_files(&tf);
    check(tf.files == NULL && tf.n == 0, "emon_free_files clears it");
    emon_close(s);
    s = NULL;
    emon_open(n, 0, 0, NULL, NULL, 0, NULL, &s, &err);
    emon_replay_file(s, "transcript.edn", (const uint8_t *)"not edn", 7, &err);
    check(emon_replay_end(s, &err) < 0 && err.code == EMON_ERR_PLAN, "a bad transcript is a plan error");
    emon_free_error(&err);
    emon_close(s);

    /* assets */
    size_t an;
    uint8_t *adoc = slurp("test/assets/dct-rgb.pdf", &an);
    emon_input ai = bytes_input(adoc, an);
    emon_model *am = NULL;
    check(emon_convert(&ai, "assets=images\n", NULL, &am, &err) == 0 && emon_model_asset_count(am) == 1, "assets=images: the embedded JPEG is asset 0");
    emon_asset_info info;
    check(emon_model_asset_info(am, 0, &info, &err) == 0 && strcmp(info.media_type, "image/jpeg") == 0 && info.size > 100 && strlen(info.sha256) == 64, "emon_model_asset_info: name, media type, sha256, size");
    check(emon_model_asset_bytes(am, 0, &b, &err) == 0 && b.len == info.size && b.data[0] == 0xFF && b.data[1] == 0xD8, "emon_model_asset_bytes: the JPEG as the document has it");
    emon_free_buf(&b);
    emon_free_asset_info(&info);
    check(info.name == NULL, "emon_free_asset_info clears it");
    check(emon_model_asset_info(am, 5, &info, &err) == -EMON_ERR_USAGE, "an asset that is not there is a usage error");
    emon_free_error(&err);
    check(emon_model_html(am, "assets=external,prefix=assets/", &b, &err) == 0 && strstr((char *)b.data, "assets/a0.jpg"), "external HTML names the asset's file");
    emon_free_buf(&b);
    emon_free_model(am);

    /* adapters, engines and packs (K2, D900) */
    {
        size_t sn;
        uint8_t *scan = slurp("samples/scan-plain.pdf", &sn);
        test_engines(scan, sn);
        test_packs(scan, sn);
        free(scan);
    }

    /* the engine's stack is its own */
    small t = {doc, n, {0}, 99};
    pthread_attr_t attr;
    pthread_t th;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 256 * 1024);
    check(pthread_create(&th, &attr, small_thread, &t) == 0, "a thread with a 256 KiB stack starts");
    pthread_join(th, NULL);
    check(t.rc == 0 && t.json.len > 0, "a conversion on that small stack succeeds: the engine has its own");
    emon_free_buf(&t.json);

    free(doc);
    free(golden);
    free(html_golden);
    free(locked);
    free(plain);
    free(notpdf);
    free(adoc);
    printf("%s: %d checks, %d failed\n", fails ? "FAIL" : "ok  ", oks + fails, fails);
    return fails ? 1 : 0;
}
