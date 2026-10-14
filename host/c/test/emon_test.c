/* THE C TEST PROGRAM of the C binding (DEVLOG P9.4, D473): the API of host/c/include/emon.h through a C compiler, from the repository root.
 * What it holds: the one-shot calls (bytes and a LAZY source) give the goldens; the inverted step API gives the same Model (and misses and
 * re-runs over a small chunk); the named errors, by category and name; option text parsed by kin; assets; record and replay; every output
 * is the caller's to free and the free is safe; a panic does not cross; and a caller's small thread stack is nothing the answer depends on.
 * Prints `ok   WHAT` per check (`FAIL WHAT` otherwise) and exits 1 on any failure. `bb slice` and `bb c` run it. */
#define _POSIX_C_SOURCE 200809L
#include "emon.h"
#include "emon_test_hooks.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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
    if (emon_open(n, chunk, 0, NULL, 0, options, &s, err) < 0) return NULL;
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
    t->rc = emon_convert_json(&in, NULL, 0, 0, &t->json, &err);
    emon_free_error(&err);
    return NULL;
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
    check(emon_capabilities() <= 7, "emon_capabilities is a set of the three native ports");
    check(emon_const(0) != emon_const(2) && emon_const(2) != emon_const(5) && emon_const(10) != emon_const(12) && emon_const(99) == -1, "emon_const: kin's numbers of the plan/fulfil protocol");
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
    check(emon_convert_json(&in, "ocr=never\n", 0, 1, &b, &err) == 0 && same(&b, golden, gn), "emon_convert_json (pretty) of h-plain is test/para/h-plain.json");
    check(b.data && b.data[b.len] == 0, "an emon_buf is NUL-terminated after len");
    emon_free_buf(&b);
    check(b.data == NULL && b.len == 0, "emon_free_buf clears the buffer");
    emon_free_buf(&b);
    check(emon_convert_html(&in, NULL, 0, NULL, &b, &err) == 0 && same(&b, html_golden, hn), "emon_convert_html of h-plain is test/slice/h-plain.html");
    emon_free_buf(&b);

    /* the Model: its outputs, many times, and its stats */
    emon_model *m = NULL;
    check(emon_convert(&in, "keep-evidence=1\n", 0, &m, &err) == 0 && m != NULL, "emon_convert gives a Model");
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
    check(emon_convert_json(&lin, "chunk=512\n", 0, 1, &b, &err) == 0 && same(&b, golden, gn) && ms.calls > 0, "a lazy source (chunk=512) gives the same JSON");
    printf("     (%zu reads, %zu of %zu bytes)\n", ms.calls, ms.bytes, n);
    emon_free_buf(&b);
    memsrc bad = {doc, n, 0, 0, 1};
    src.ctx = &bad;
    check(emon_convert_json(&lin, "chunk=512\n", 0, 0, &b, &err) == -EMON_ERR_IO && err_is(&err, EMON_ERR_IO, "io"), "a failed read is io");
    emon_free_error(&err);
    memsrc shortsrc = {doc, n / 2, 0, 0, -1};
    emon_source src2 = {n, mem_read, &shortsrc};
    lin.source = &src2;
    check(emon_convert_json(&lin, "chunk=512\n", 0, 0, &b, &err) == -EMON_ERR_SOURCE_CHANGED && err_is(&err, EMON_ERR_SOURCE_CHANGED, "source-changed"), "a source that gives fewer bytes than its length is source-changed");
    emon_free_error(&err);

    /* stage 0 */
    check(emon_intake(&in, NULL, 0, &b, &err) == 0 && b.len > 4 && memcmp(b.data, "pdf ", 4) == 0 && strstr((char *)b.data, "encrypted=false"), "emon_intake: the stage 0 line");
    printf("     (%s)\n", (char *)b.data);
    emon_free_buf(&b);

    /* the named errors */
    size_t ln, pn, xn;
    uint8_t *locked = slurp("test/pdf/p3/crypt-aes-128-user.pdf", &ln);
    uint8_t *plain = slurp("test/pdf/p3/plain.pdf", &pn);
    uint8_t *notpdf = slurp("test/pdf/notpdf.pdf", &xn);
    emon_input li = bytes_input(locked, ln);
    check(emon_convert_json(&li, NULL, 0, 0, &b, &err) == -EMON_ERR_PASSWORD_REQUIRED && err_is(&err, EMON_ERR_PASSWORD_REQUIRED, "password-required"), "a locked PDF: password-required");
    emon_free_error(&err);
    li.password = (const uint8_t *)"wrong";
    li.password_len = 5;
    check(emon_convert_json(&li, NULL, 0, 0, &b, &err) == -EMON_ERR_PASSWORD_INCORRECT && err_is(&err, EMON_ERR_PASSWORD_INCORRECT, "password-incorrect"), "a wrong password: password-incorrect");
    emon_free_error(&err);
    li.password = (const uint8_t *)"user-secret";
    li.password_len = 11;
    emon_buf locked_json = {0}, plain_json = {0};
    emon_input pi = bytes_input(plain, pn);
    check(emon_convert_json(&li, NULL, 0, 0, &locked_json, &err) == 0 && emon_convert_json(&pi, NULL, 0, 0, &plain_json, &err) == 0 && same(&locked_json, plain_json.data, plain_json.len),
          "the right password opens the locked file to the plain twin's Model");
    emon_free_buf(&locked_json);
    emon_free_buf(&plain_json);
    emon_input ni = bytes_input(notpdf, xn);
    check(emon_convert_json(&ni, NULL, 0, 0, &b, &err) == -EMON_ERR_UNSUPPORTED_FORMAT && err_is(&err, EMON_ERR_UNSUPPORTED_FORMAT, "unsupported-format"), "not a PDF: unsupported-format");
    emon_free_error(&err);
    check(emon_convert_json(&in, "limit.input-bytes=100\n", 0, 0, &b, &err) == -EMON_ERR_LIMIT && err_is(&err, EMON_ERR_LIMIT, "input-bytes"), "limit.input-bytes=100: a typed breach, named by kin");
    printf("     (%s)\n", err.message);
    emon_free_error(&err);
    check(emon_convert_json(&in, "record=1\n", 0, 0, &b, &err) == -EMON_ERR_USAGE, "record=1 is a session's option: a one-shot refuses it");
    emon_free_error(&err);
    check(emon_convert_json(&in, "js-render=1\n", 0, 0, &b, &err) == -EMON_ERR_USAGE, "js-render=1 is a session's option: a one-shot refuses it");
    emon_free_error(&err);
    check(emon_convert_json(NULL, NULL, 0, 0, &b, &err) == -EMON_ERR_USAGE && emon_convert_json(&in, NULL, 0, 0, NULL, &err) == -EMON_ERR_USAGE, "NULL arguments are usage errors, not crashes");
    emon_free_error(&err);
    emon_input empty = {0};
    check(emon_convert_json(&empty, NULL, 0, 0, &b, NULL) < 0, "an empty input is an error (and a NULL emon_error is allowed)");
    check(emon_test_panic(&err) == -EMON_ERR_INTERNAL && err_is(&err, EMON_ERR_INTERNAL, "internal") && strstr(err.message, "on purpose"), "a panic is caught at the boundary: internal, with its message");
    emon_free_error(&err);

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
    check(emon_open(n, 0, 0, NULL, 0, "bogus=1\n", &s, &err) == -EMON_ERR_USAGE && s == NULL, "emon_open: option text is parsed by kin");
    emon_free_error(&err);
    check(emon_open(n, 1, 0, NULL, 0, "limit.input-bytes=100\n", &s, &err) == -EMON_ERR_LIMIT && s == NULL, "emon_open: the input-bytes bound is checked on the length before a cache is made");
    emon_free_error(&err);
    check(emon_open(n, 0, 0, NULL, 0, NULL, &s, &err) == 0 && s != NULL, "emon_open");
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
    check(emon_open(n, 0, 0, NULL, 0, "js-render=1\nlimit.pixels=1000\n", &s, &err) == 0 && s != NULL && emon_limit(s, 9, &lim, &err) == 0 && lim == 1000, "emon_open: js-render=1 and limit.pixels=1000 -- emon_limit says 1000");
    emon_close(s);
    s = NULL;
    check(emon_open(n, 0, 0, NULL, 0, NULL, &s, &err) == 0 && s != NULL, "emon_open (again)");
    int first = emon_step(s, &so, &err);
    check(first == EMON_NEED_BYTES && so.nranges > 0 && so.ranges[0].length > 0, "the first step needs bytes, and says which");
    emon_free_step(&so);
    check(so.ranges == NULL && so.nranges == 0, "emon_free_step clears it");
    emon_close(s);
    emon_close(NULL);

    /* intake by the inverted API: prefetch is the first and last chunk */
    s = NULL;
    check(emon_open(n, 512, EMON_OPEN_INTAKE, NULL, 0, NULL, &s, &err) == 0, "emon_open (intake)");
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
    emon_open(n, 512, 0, NULL, 0, NULL, &s, &err);
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
    emon_open(n, 0, 0, NULL, 0, "record=1\n", &s, &err);
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
    emon_open(n, 0, 0, NULL, 0, NULL, &s, &err);
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
    emon_open(n, 0, 0, NULL, 0, NULL, &s, &err);
    emon_replay_file(s, "transcript.edn", (const uint8_t *)"not edn", 7, &err);
    check(emon_replay_end(s, &err) < 0 && err.code == EMON_ERR_PLAN, "a bad transcript is a plan error");
    emon_free_error(&err);
    emon_close(s);

    /* assets */
    size_t an;
    uint8_t *adoc = slurp("test/assets/dct-rgb.pdf", &an);
    emon_input ai = bytes_input(adoc, an);
    emon_model *am = NULL;
    check(emon_convert(&ai, "assets=images\n", 0, &am, &err) == 0 && emon_model_asset_count(am) == 1, "assets=images: the embedded JPEG is asset 0");
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
