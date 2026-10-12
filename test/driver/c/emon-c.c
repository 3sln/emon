/* THE C DRIVER (DEVLOG P9.4, D1082): a test-only CLI over the C binding (host/c/include/emon.h), the twin of test/driver/rust/main.rs. Its STDOUT
 * is the only thing gates read; stats and errors go to stderr.  It is not the library: it reads the file itself and hands emon bytes, a lazy
 * pread source, or the chunks a step loop asks for.  `bb slice` and `bb identity` hold it to the Rust driver byte for byte.
 *
 *     emon-c [--out=json|html|evidence|intake|bundle] [--pretty] [--html=OPTS] [--html-assets=M] [--asset-prefix=P] [--password=TEXT]
 *            [--source=bytes|seek|spool|step] [--async] [--stats] [--record=DIR | --replay=DIR] [--asset-dir=D] [--KEY=VALUE ...] FILE
 *
 *   --source=bytes   the one-shot emon_convert over the file's bytes (the default)
 *   --source=seek    the one-shot over a LAZY source: a pread callback, so the engine reads only the spans it touches
 *   --source=spool   the file read through a forward-only stream into memory, then as bytes
 *   --source=step    the inverted API (emon_open .. emon_step .. emon_finish) answering every NEED_BYTES from the file
 *   --async          the same, with chunk misses the loop fetches (--chunk=N sets the size): the shape of a browser's Blob
 *   --record=DIR / --replay=DIR   the transcript (the step API): written to / read from DIR
 *   --native         the one-shot with EMON_NATIVE: the library's own pdfium / Tesseract / codecs, when it was built with them (emon_capabilities)
 *   --KEY=VALUE      a key of kin's options grammar (emon_is_option_key): --ocr=never, --assets=images, --chunk=512, --limit.pages=5 ...
 *
 * The same flags, stdout and exit codes as the Rust driver (0 ok; 2 usage; 4 a named emon error, `error: NAME` and the sentence on stderr;
 * 1 anything else) -- see the Rust driver for what each --out is.  The flags that need a port (--tesseract, --pdfium, --codecs) or other
 * threads (--parallel) are not here. */
#define _POSIX_C_SOURCE 200809L
#include "emon.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* ------------------------------------------------------------------------------------------------ small things */

static void usage(const char *msg) {
    fprintf(stderr, "emon-c: %s\n", msg);
    fprintf(stderr, "usage: emon-c [--out=json|html|evidence|intake|bundle] [--pretty] [--html=OPTS] [--html-assets=inline|external|omit] [--asset-prefix=P] "
                    "[--password=TEXT] [--source=bytes|seek|spool|step] [--async] [--stats] [--record=DIR | --replay=DIR] [--asset-dir=D] [--KEY=VALUE] FILE\n");
    exit(2);
}

static void io_die(const char *what, const char *why) {
    fprintf(stderr, "emon-c: %s: %s\n", what, why);
    exit(1);
}

static void *xmalloc(size_t n) {
    void *p = malloc(n ? n : 1);
    if (!p) io_die("memory", "out of memory");
    return p;
}

/* an emon error: a usage error is the driver's exit 2; the rest is `error: NAME` and the sentence, exit 4 (1 for a bug) */
static void fail(emon_error *e) {
    if (e->code == EMON_ERR_USAGE) usage(e->message);
    fprintf(stderr, "error: %s\n%s\n", e->name, e->message);
    exit(e->code == EMON_ERR_INTERNAL ? 1 : 4);
}

#define CHECK(call)                  \
    do {                             \
        if ((call) < 0) fail(&err);  \
    } while (0)

typedef struct {
    char *p;
    size_t n, cap;
} sb;

static void sb_add(sb *s, const void *d, size_t n) {
    if (s->n + n + 1 > s->cap) {
        s->cap = (s->n + n + 1) * 2;
        s->p = realloc(s->p, s->cap);
        if (!s->p) io_die("memory", "out of memory");
    }
    memcpy(s->p + s->n, d, n);
    s->n += n;
}
static void sb_str(sb *s, const char *t) { sb_add(s, t, strlen(t)); }

static uint8_t *read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) io_die(path, strerror(errno));
    size_t cap = 1 << 16, n = 0;
    uint8_t *b = xmalloc(cap);
    for (;;) {
        size_t got = fread(b + n, 1, cap - n, f);
        n += got;
        if (n < cap) break;
        cap *= 2;
        b = realloc(b, cap);
        if (!b) io_die("memory", "out of memory");
    }
    if (ferror(f)) io_die(path, strerror(errno));
    fclose(f);
    *len = n;
    return b;
}

static void make_dirs(const char *dir) {
    char *t = strdup(dir);
    for (char *c = t + 1; *c; c++) {
        if (*c == '/') {
            *c = 0;
            mkdir(t, 0777);
            *c = '/';
        }
    }
    if (mkdir(t, 0777) != 0 && errno != EEXIST) io_die(dir, strerror(errno));
    free(t);
}

static void write_file(const char *dir, const char *name, const uint8_t *d, size_t n) {
    char path[4096];
    snprintf(path, sizeof path, "%s/%s", dir, name);
    FILE *f = fopen(path, "wb");
    if (!f) io_die(path, strerror(errno));
    if (n && fwrite(d, 1, n, f) != n) io_die(path, strerror(errno));
    fclose(f);
}

/* ------------------------------------------------------------------------------------------------ the lazy source */

typedef struct {
    int fd;
} fdsrc;

static int64_t fd_read(void *ctx, uint64_t offset, uint8_t *buf, size_t len) {
    fdsrc *s = ctx;
    ssize_t r = pread(s->fd, buf, len, (off_t)offset);
    return r < 0 ? -1 : (int64_t)r;
}

/* ------------------------------------------------------------------------------------------------ the step loop */

typedef struct {
    int fd;
    uint64_t length;
    const char *options;
    const uint8_t *password;
    size_t password_len;
    unsigned open_flags;
    const char *replay_dir;
    const char *record_dir;
} stepargs;

static void provide_ranges(emon_session *s, int fd, const emon_range *r, size_t n) {
    emon_error err = {0};
    for (size_t i = 0; i < n; i++) {
        uint8_t *b = xmalloc((size_t)r[i].length);
        size_t got = 0;
        while (got < r[i].length) {
            ssize_t k = pread(fd, b + got, (size_t)r[i].length - got, (off_t)(r[i].offset + got));
            if (k <= 0) break;
            got += (size_t)k;
        }
        if (got < r[i].length) {
            /* the file is shorter than its length said (it changed): the source's fault, by name */
            CHECK(emon_fail(s, 1, "the file gave fewer bytes than its length said", &err));
        } else {
            CHECK(emon_provide(s, r[i].offset, b, got, &err));
        }
        free(b);
    }
}

static void replay_dir_in(emon_session *s, const char *dir) {
    emon_error err = {0};
    DIR *d = opendir(dir);
    if (!d) io_die(dir, strerror(errno));
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') continue;
        char path[4096];
        snprintf(path, sizeof path, "%s/%s", dir, e->d_name);
        size_t n;
        uint8_t *b = read_file(path, &n);
        CHECK(emon_replay_file(s, e->d_name, b, n, &err));
        free(b);
    }
    closedir(d);
    CHECK(emon_replay_end(s, &err));
}

/* an intake (flags EMON_OPEN_INTAKE) or a conversion by the inverted API; out_model or out_line is filled */
static void run_session(const stepargs *a, emon_model **out_model, emon_buf *out_line) {
    emon_error err = {0};
    emon_session *s = NULL;
    CHECK(emon_open(a->length, 0, a->open_flags, a->password, a->password_len, a->options, &s, &err));
    if (a->replay_dir) replay_dir_in(s, a->replay_dir);
    if (a->open_flags & EMON_OPEN_INTAKE) {
        emon_step_out pf = {0};
        CHECK(emon_prefetch(s, &pf, &err));
        provide_ranges(s, a->fd, pf.ranges, pf.nranges);
        emon_free_step(&pf);
    }
    for (;;) {
        emon_step_out o = {0};
        int st = emon_step(s, &o, &err);
        if (st < 0) fail(&err);
        if (st == EMON_DONE) break;
        if (st == EMON_NEED_BYTES) {
            provide_ranges(s, a->fd, o.ranges, o.nranges);
        } else {
            /* a request this driver has no port for is answered `unsupported`: an answer (D199) */
            for (size_t i = 0; i < o.nrequests; i++) {
                int32_t rec[4] = {4, o.requests[i].kind, o.requests[i].id, emon_const(12)};
                CHECK(emon_answer(s, rec, 4, &err));
            }
        }
        emon_free_step(&o);
    }
    if (a->open_flags & EMON_OPEN_INTAKE) {
        CHECK(emon_finish_intake(s, out_line, &err));
    } else {
        if (a->record_dir) {
            emon_files fs = {0};
            CHECK(emon_transcript(s, &fs, &err));
            make_dirs(a->record_dir);
            for (size_t i = 0; i < fs.n; i++) write_file(a->record_dir, fs.files[i].name, fs.files[i].data, fs.files[i].len);
            emon_free_files(&fs);
        }
        CHECK(emon_finish(s, out_model, &err));
    }
    emon_close(s);
}

/* ------------------------------------------------------------------------------------------------ the outputs */

static void hex(sb *s, const uint8_t *b, size_t n) {
    static const char d[] = "0123456789abcdef";
    for (size_t i = 0; i < n; i++) {
        char c[2] = {d[b[i] >> 4], d[b[i] & 15]};
        sb_add(s, c, 2);
    }
}

static void html_of(sb *s, emon_model *m, const char *opts) {
    emon_error err = {0};
    emon_buf b = {0};
    CHECK(emon_model_html(m, opts, &b, &err));
    sb_add(s, b.data, b.len);
    emon_free_buf(&b);
}

static void json_of(sb *s, emon_model *m, int pretty) {
    emon_error err = {0};
    emon_buf b = {0};
    CHECK(emon_model_json(m, pretty, &b, &err));
    sb_add(s, b.data, b.len);
    emon_free_buf(&b);
}

static void evidence_of(sb *s, emon_model *m) {
    emon_error err = {0};
    emon_buf b = {0};
    CHECK(emon_model_evidence(m, &b, &err));
    sb_add(s, b.data, b.len);
    emon_free_buf(&b);
}

int main(int argc, char **argv) {
    const char *out = "json", *source = "bytes", *file = NULL, *record = NULL, *replay = NULL, *asset_dir = NULL, *password = NULL;
    const char *html_assets = NULL, *asset_prefix = NULL;
    char *html = strdup("");
    int pretty = 0, async = 0, stats = 0, assets_on = 0, native = 0;
    sb kin = {0};
    for (int i = 1; i < argc; i++) {
        const char *a = argv[i];
        if (!strncmp(a, "--out=", 6)) out = a + 6;
        else if (!strcmp(a, "--pretty")) pretty = 1;
        else if (!strncmp(a, "--html=", 7)) { free(html); html = strdup(a + 7); }
        else if (!strncmp(a, "--html-assets=", 14)) {
            html_assets = a + 14;
            if (strcmp(html_assets, "inline") && strcmp(html_assets, "external") && strcmp(html_assets, "omit")) usage("--html-assets is inline, external or omit");
        } else if (!strncmp(a, "--asset-prefix=", 15)) asset_prefix = a + 15;
        else if (!strncmp(a, "--asset-dir=", 12)) asset_dir = a + 12;
        else if (!strncmp(a, "--password=", 11)) password = a + 11;
        else if (!strncmp(a, "--source=", 9)) source = a + 9;
        else if (!strcmp(a, "--async")) async = 1;
        else if (!strcmp(a, "--stats")) stats = 1;
        else if (!strcmp(a, "--native")) native = 1;
        else if (!strncmp(a, "--record=", 9)) record = a + 9;
        else if (!strncmp(a, "--replay=", 9)) replay = a + 9;
        else if (a[0] == '-' && a[1] == '-' && strchr(a, '=')) {
            /* a key of kin's grammar: --NAME=VALUE is the line NAME=VALUE */
            char name[128];
            const char *eq = strchr(a + 2, '=');
            size_t kl = (size_t)(eq - (a + 2));
            if (kl == 0 || kl >= sizeof name) usage("unknown flag");
            memcpy(name, a + 2, kl);
            name[kl] = 0;
            if (!emon_is_option_key(name)) {
                char m[300];
                snprintf(m, sizeof m, "unknown flag %s", a);
                usage(m);
            }
            sb_str(&kin, a + 2);
            sb_str(&kin, "\n");
            if (!strcmp(name, "assets") && strcmp(eq + 1, "none")) assets_on = 1;
            if (!strcmp(name, "assets") && !strcmp(eq + 1, "none")) assets_on = 0;
        } else if (a[0] == '-' && a[1] == '-') {
            char m[300];
            snprintf(m, sizeof m, "unknown flag %s", a);
            usage(m);
        } else if (!file) file = a;
        else usage("one FILE");
    }
    if (!file) usage("no FILE");
    if (record && replay) usage("--record and --replay are two ways");
    if (strcmp(source, "bytes") && strcmp(source, "seek") && strcmp(source, "spool") && strcmp(source, "step")) usage("--source is bytes, seek, spool or step");
    if (strcmp(out, "json") && strcmp(out, "html") && strcmp(out, "evidence") && strcmp(out, "bundle") && strcmp(out, "intake")) usage("--out is json, html, evidence, bundle or intake");
    if (!strcmp(out, "evidence") || !strcmp(out, "bundle")) sb_str(&kin, "keep-evidence=1\n");
    if (record) sb_str(&kin, "record=1\n");
    char *options = kin.p ? strndup(kin.p, kin.n) : strdup("");
    {
        emon_error err = {0};
        CHECK(emon_check_options(options, &err));
    }

    /* THE DRIVER READS THE FILE (the library never does) */
    int fd = open(file, O_RDONLY);
    if (fd < 0) io_die(file, strerror(errno));
    struct stat st;
    if (fstat(fd, &st) != 0) io_die(file, strerror(errno));
    size_t flen = (size_t)st.st_size;
    const uint8_t *pw = (const uint8_t *)password;
    size_t pwlen = password ? strlen(password) : 0;

    if (native && (async || record || replay || !strcmp(source, "step"))) usage("--native is the one-shot's: a step session is fulfilled by its caller");
    int use_step = async || record || replay || !strcmp(source, "step");
    uint8_t *mem = NULL;
    size_t memlen = 0;
    fdsrc fs = {fd};
    emon_source src = {(uint64_t)flen, fd_read, &fs};
    emon_input in = {0};
    in.password = pw;
    in.password_len = pwlen;
    if (!strcmp(source, "seek")) {
        in.source = &src;
    } else if (!use_step) {
        mem = read_file(file, &memlen);
        in.data = mem;
        in.len = memlen;
    }

    emon_error err = {0};
    /* intake alone */
    if (!strcmp(out, "intake")) {
        emon_buf line = {0};
        if (use_step) {
            stepargs a = {fd, flen, options, pw, pwlen, EMON_OPEN_INTAKE, NULL, NULL};
            run_session(&a, NULL, &line);
        } else {
            CHECK(emon_intake(&in, options, 0, &line, &err));
        }
        printf("%s\n", (char *)line.data);
        return 0;
    }

    emon_model *model = NULL;
    if (use_step) {
        stepargs a = {fd, flen, options, pw, pwlen, 0, replay, record};
        run_session(&a, &model, NULL);
    } else {
        CHECK(emon_convert(&in, options, native ? EMON_NATIVE : 0, &model, &err));
    }

    if (asset_dir) {
        make_dirs(asset_dir);
        int n = emon_model_asset_count(model);
        for (int i = 0; i < n; i++) {
            emon_asset_info info;
            emon_buf b = {0};
            CHECK(emon_model_asset_info(model, i, &info, &err));
            CHECK(emon_model_asset_bytes(model, i, &b, &err));
            write_file(asset_dir, info.name, b.data, b.len);
            emon_free_buf(&b);
            emon_free_asset_info(&info);
        }
    }

    sb o = {0};
    if (!strcmp(out, "json")) {
        json_of(&o, model, pretty);
    } else if (!strcmp(out, "html")) {
        sb h = {0};
        sb_str(&h, html);
        if (html_assets) { if (h.n) sb_str(&h, ","); sb_str(&h, "assets="); sb_str(&h, html_assets); }
        if (asset_prefix) { if (h.n) sb_str(&h, ","); sb_str(&h, "prefix="); sb_str(&h, asset_prefix); }
        sb_add(&h, "", 1);
        html_of(&o, model, h.p);
    } else if (!strcmp(out, "evidence")) {
        evidence_of(&o, model);
    } else {
        /* the bundle: one conversion, every output, for `bb identity` */
        sb_str(&o, "== json\n");
        json_of(&o, model, 0);
        sb_str(&o, "\n== json-pretty\n");
        json_of(&o, model, 1);
        sb_str(&o, "\n== html\n");
        html_of(&o, model, "");
        sb_str(&o, "\n== html-full\n");
        html_of(&o, model, "ids,geometry,confidence=950,ledger,pretty");
        sb_str(&o, "\n== evidence\n");
        evidence_of(&o, model);
        sb_str(&o, "\n== intake\n");
        {
            emon_buf line = {0};
            emon_error ierr = {0};
            int r;
            if (use_step) {
                stepargs a = {fd, flen, options, pw, pwlen, EMON_OPEN_INTAKE, NULL, NULL};
                run_session(&a, NULL, &line);
                r = 0;
            } else {
                r = emon_intake(&in, options, 0, &line, &ierr);
            }
            if (r < 0) {
                sb_str(&o, "error: ");
                sb_str(&o, ierr.name);
            } else {
                sb_add(&o, line.data, line.len);
            }
        }
        sb_str(&o, "\n");
        if (assets_on) {
            sb_str(&o, "== html-external\n");
            html_of(&o, model, "assets=external,prefix=assets/");
            sb_str(&o, "\n== html-omit\n");
            html_of(&o, model, "assets=omit");
            sb_str(&o, "\n== assets\n");
            int n = emon_model_asset_count(model);
            for (int i = 0; i < n; i++) {
                emon_asset_info info;
                emon_buf b = {0};
                char head[512];
                CHECK(emon_model_asset_info(model, i, &info, &err));
                CHECK(emon_model_asset_bytes(model, i, &b, &err));
                snprintf(head, sizeof head, "%s %s %zu\n", info.name, info.media_type, b.len);
                sb_str(&o, head);
                hex(&o, b.data, b.len);
                sb_str(&o, "\n");
                emon_free_buf(&b);
                emon_free_asset_info(&info);
            }
        }
    }
    if (stats) {
        emon_stats s;
        emon_model_stats(model, &s);
        fprintf(stderr, "stats: attempts=%d fetch-rounds=%d bytes-requests=%d source-reads=%u bytes-fetched=%llu of %llu requests=%d\n", s.attempts, s.fetch_rounds,
                s.bytes_requests, s.source_reads, (unsigned long long)s.bytes_fetched, (unsigned long long)s.input_bytes, s.requests);
    }
    if (o.n) fwrite(o.p, 1, o.n, stdout);
    fflush(stdout);
    emon_free_model(model);
    return 0;
}
