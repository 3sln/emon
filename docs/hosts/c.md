# emon for C

`libemon` and its header [`emon.h`](../../host/c/include/emon.h): the Rust host behind a C ABI. OCR engines are not in it: they are
**adapters** behind a second header, [`emon_adapter.h`](../../host/c/include/emon_adapter.h), registered in an `emon_engines`
that you give to the conversion calls. Get the archive `emon-c-<version>-<triple>.tar.gz` from the
[GitHub releases](https://github.com/3sln/emon/releases):

    include/emon.h
    include/emon_adapter.h             the adapter ABI (version 1)
    lib/libemon.dylib | .so | .dll     the core library: no Tesseract, no Leptonica
    lib/libemon.a                      the static core library (desktop)
    lib/libpdfium.dylib | .so          pdfium, loaded at run time
    lib/emon/adapters/libemon_ocr_tesseract.dylib | .so | emon_ocr_tesseract.dll
                                       Tesseract 5.5.3 and Leptonica, as an adapter library
    share/emon/packs/tess-fast-eng/eng.traineddata
    share/emon/packs/tess-osd/osd.traineddata
                                       the English and orientation model packs

Link the shared library with `-I include -L lib -lemon` (and `-Wl,-rpath,<lib dir>` to run it from where it is: the library is
named `@rpath/libemon.dylib` on macOS and has the soname `libemon.so` on Linux). The core library is small and needs no C++
runtime: only the platform's own libraries (`-lpthread -ldl -lm` on Linux for the static one, `-framework Security
-framework CoreFoundation -liconv -lm` on macOS). C11; the header is C++ safe.

On Windows (x64, Windows 10 or newer) the library is built with the static C runtime (`/MT`) and needs no Visual C++
redistributable. A program that links the static library should be built with `/MT` too, so that the runtimes agree.

## Engines, adapters and packs

Three things make a scan readable, and each is yours to register:

| | what it is | where it comes from |
|---|---|---|
| **an OCR adapter** | an engine behind `emon_ocr_adapter` (`emon_adapter.h`): it is handed an `ocr` request and the pixels it names and answers with a reading in OIL, the engine-neutral interchange; or it says it cannot read (`unsupported`, `failed`), and the page is **unread, never blank** | a library that exports `emon_ocr_adapter_v1` (`emon_engines_load`): `libemon_ocr_tesseract`, `libemon_ocr_ppocr`; or a struct of callbacks of yours (`emon_engines_add_ocr`): a platform recognizer, a cloud service, a Dart plugin |
| **pdfium** | the renderer, loaded at run time | `emon_engines_pdfium` |
| **packs** | the model files an adapter reads with, content-addressed and immutable, each file listed with its SHA-256 in kin's catalogue (`release/packs/packs.edn`) | the pack directory you name (`emon_engines_packs`), `share/emon/packs` of the archive, `$EMON_MODELS`, a download, or bytes of your own |

`emon_engines_defaults` registers the turnkey set from what is next to the library, as the archive lays it out: the adapters
in `lib/emon/adapters/`, the packs in `share/emon/packs/`, pdfium in `lib/`. Nothing found is not an error (the engines are the
ones that were: a core-only library converts born-digital documents and leaves scans unread); an adapter that is found and cannot
be opened is. Registration failures are errors **at registration**, with a name to route on (`adapter-abi-unsupported`,
`adapter-runtime-missing`, `adapter-open-failed`, `adapter-remote-refused`, `pack-dir-missing`), never in the middle of a conversion.

**A request goes to the adapter of its engine.** Kin addresses each `ocr` request to an OIL engine (Tesseract's, PP-OCR's) and a
registry routes it by that, first registered first. A request nobody registered for is `unsupported` (the page is unread), or, in
the step API with `js-ocr=1`, the caller's. What an adapter gives back is held to what it registered: the engine it answers as, the
capabilities it declared, valid OIL for the bitmap. A reading that is not is `ocr-adapter-invalid` (reported as the error
`fulfil-failed` with that in its message), which fails the conversion: a buggy adapter must not quietly degrade the output.

**Packs are always verified.** Every pack file, wherever it came from (the pack directory, a directory you named, bytes or a path your
hook provided, a download), is checked against the catalogue's SHA-256 and size before an adapter reads it. A file that does not
match is never used; one in the pack directory is deleted and the pack is missing again (`pack-hash-mismatch:<id>`, in
`emon_engines_notes`). Each adapter is given a flat directory of the verified files of the packs it reads. A request that needs a
pack that is not there is answered `unsupported` with `pack-missing:<id>`: unread, never blank.

Set the pack directory and mode with `emon_engines_packs(engines, dir, mode, &err)`. `EMON_PACKS_NEVER` uses installed packs and skips the hook.
`EMON_PACKS_CACHE` and `EMON_PACKS_AUTO` let the hook supply missing packs. A new registry starts in `NEVER`.
`emon_engines_defaults` uses `CACHE` unless `EMON_PACKS_FETCH` overrides it. `EMON_OFFLINE=1` takes precedence over the mode and hook.

Use `emon_engines_defaults_for` with the conversion options to skip registering the
bundled Tesseract adapter when the selection has no Tesseract role. For example,
`ocr-engines=ppocr-alone` selects the PP adapter as sole reader in both one-shot
conversions and sessions.

**The platform's own OCR** (DEVLOG D901). The macOS and iOS archives carry a second adapter library, `lib/emon/adapters/libemon_ocr_vision.dylib` (Apple Vision: no pack, no model), which
`emon_engines_defaults` registers with Tesseract's. It is explicit: a conversion reads with it only when its options say `ocr-engine=apple-vision`, and then the pages kin sends to Tesseract (a script Vision cannot read, a seal or a stamp, a table of numbers, D907) are read by the Tesseract adapter beside it (a registry with no Tesseract keeps Vision's reading); the Windows adapter is `emon_ocr_winocr.dll`
(`ocr-engine=windows-media-ocr`). What they say and do not say, and why they are explicit, is [the platform's own OCR](../platform-ocr.md).

**Your app decides** (DEVLOG D896):

- `emon_engines_describe` is `engines()`: every engine that runs on this platform and its state, as JSON: `built-in`, `installed`,
  `downloadable` (with `download_bytes`) or `unavailable` (with `reason`), and the packs it needs with their size and licence.
- `emon_prepare(engines, options, &json, &err)` is `prepare(options)`: the packs a conversion with those options needs and which
  are missing, with a `verdict` (`ready`, `partial`, `downloadable`, `blocked`). Fetches nothing.
- `emon_on_pack_needed` is the hook for a missing pack. Use `EMON_PACK_PROVIDE` with bytes or a path from your platform HTTP client or assets.
  `EMON_PACK_DECLINE` leaves the page unread. `EMON_PACK_SUBSTITUTE` selects another implementation of the OIL engine the conversion planned.
  `EMON_PACK_FETCH`, including the `AUTO` default without a hook, leaves the page unread with `pack-fetch-unavailable:<id>`.
- Apps handle progress and cancellation for their own downloads. In `AUTO` and online, `emon_packs_ensure` verifies installed packs and
  returns `pack-fetch-unavailable` for the first missing pack.

<!-- snippet: docs/snippets/c/engines.c -->
```c
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
```

**Implementing an adapter** is filling one struct (`emon_adapter.h` documents each function): `describe` (the OIL engine it answers as,
its capabilities, the pack kinds it needs, its identity), `supports` (a cheap pre-check), `read` (one request in, an OIL answer out) or,
for an engine that answers later, `start` and a call of `complete` (or your program's `emon_complete`), and `close`. `emon_ocr_request`
reads the fields of the request record for you (the bitmap, the page-segmentation mode, the region, the languages), so you do not learn
its layout. The core library checks what you register against kin's engine table (`emon.adapters`: a law each) and refuses a
declaration beyond what the table lets that engine declare.

## Android and iOS

The release also carries the library for phones, one archive per triple, `emon-c-<version>-<triple>.tar.gz`, built by `bb mobile` (`tools/native/mobile.clj`) from the same
sources and the same pinned Tesseract, Leptonica, codecs and pdfium as the desktop archives (`tools/native/native.edn`, `tools/pdfium/pdfium.edn`):

| Triple | Library | Platform | pdfium (pinned, `chromium/8076`) |
|---|---|---|---|
| `aarch64-linux-android` | `lib/libemon.so` | Android arm64-v8a, API 24+ | `android-arm64` |
| `armv7-linux-androideabi` | `lib/libemon.so` | Android armeabi-v7a, API 24+ | `android-arm` |
| `x86_64-linux-android` | `lib/libemon.so` | Android x86_64 (emulators, Chromebooks), API 24+ | `android-x64` |
| `aarch64-apple-ios` | `lib/libemon.dylib` | iOS device, arm64, iOS 13.0+ | `ios-device-arm64` |
| `aarch64-apple-ios-sim` | `lib/libemon.dylib` | iOS simulator on Apple silicon, arm64 | `ios-simulator-arm64` |

Each archive has `include/emon.h` and `include/emon_adapter.h`, the core library `lib/libemon.so` or `.dylib`, pdfium (`lib/libpdfium.so` or `.dylib`, loaded at run time), the Tesseract
adapter library (`lib/emon/adapters/libemon_ocr_tesseract.so` or `.dylib`) and the English and orientation packs in `share/emon/packs/`, the desktop layout. There is **no
`libemon.a`**: Dart build hooks bundle dynamic libraries only (`DynamicLoadingBundled` code assets,
[dart.dev/tools/hooks](https://dart.dev/tools/hooks), [`DynamicLoadingBundled`](https://pub.dev/documentation/code_assets/latest/code_assets/DynamicLoadingBundled-class.html)).
The core library does not carry Tesseract (an app that reads scans another way pays nothing for it); the Tesseract adapter is a library of its own, which an app opts into.
The Intel iOS simulator is not built (D885).

What each library is, and what `bb native --audit --triple T` holds it to:

- **Android** (the core library and the adapter library alike). NDK r28c (a build with an older NDK is refused), API level 24 (Android 7.0). 24 is where the pieces agree: pdfium-binaries builds pdfium for API 23, Leptonica's
  `fmemopen` and `open_memstream` need 23, and 24 is the floor of the NDK's current defaults and of Flutter's `minSdkVersion`; a Dart hook asked for a lower
  `AndroidCodeConfig.targetNdkApi` should refuse the build (the library does not load below 24). The shared library has **16 KB page alignment**
  (`-Wl,-z,max-page-size=16384`; Google Play requires it of 64-bit apps targeting Android 15, [developer.android.com/guide/practices/page-sizes](https://developer.android.com/guide/practices/page-sizes)):
  every `LOAD` segment aligned to 0x4000 and `GNU_RELRO` ending on a 16 KB boundary (the audit checks the file, and the pinned 64-bit pdfium too; the 32-bit pdfium of armeabi-v7a is
  4 KB aligned upstream, which Play does not require of 32-bit code). The C++ runtime is linked statically, so the library needs only `libc.so`, `libm.so` and `libdl.so`:
  no `libc++_shared.so` goes into the app. The core library's exports are exactly the functions of `emon.h`, the adapter library's exactly `emon_ocr_adapter_v1`.
- **iOS.** One arm64 slice, platform `IOS` (device) or `IOSSIMULATOR`, minimum iOS 13.0, install name `@rpath/libemon.dylib`, loading only `/usr/lib` and `/System/Library`
  (libSystem and libc++). The pinned pdfium is upstream's own build for iOS 17.0 (`libpdfium.dylib`, install name rewritten to `@rpath/libpdfium.dylib` by `bb pdfium-fetch`): on an older device
  libemon loads and finds no pdfium (`emon_capabilities()` has no `EMON_CAP_PDFIUM`; born-digital conversion is unaffected). Flutter embeds a code asset on iOS as a framework and signs it;
  the library is unsigned in the archive. The hook gives a device build the `aarch64-apple-ios` archive and a simulator build the `aarch64-apple-ios-sim` one (`IOSCodeConfig.targetSdk`).

**Finding pdfium and the adapters inside an app.** An APK maps its libraries and an iOS app carries them as frameworks, so "beside the library" is not always a directory. After
the places listed above, the library asks the loader for pdfium by name: `libpdfium.so` (Android) or `@rpath/libpdfium.dylib`, `@rpath/libpdfium.framework/libpdfium` (iOS), and
`emon_engines_defaults` for the Tesseract adapter by name (`libemon_ocr_tesseract.so`, `@rpath/libemon_ocr_tesseract.dylib`). The packs are files: name a directory in the app's own
storage with `emon_engines_packs(engines, dir, EMON_PACKS_AUTO, &err)` (the app's files directory; the library keeps what it fetches there), copy the archive's `share/emon/packs/` into it
for a first run without a network, or answer `emon_on_pack_needed` with `EMON_PACK_PROVIDE` from the app's assets.

Cross-compiling needs the Android NDK (`ANDROID_NDK_HOME`, from macOS or Linux) or Xcode (macOS), `rustup target add <triple>`, then
`bb native --triple T`, `bb pdfium-fetch --platform <pdfium>`, `bb mobile --triple T --check` (build, audit, smoke). The smoke program (`tools/native/mobile/smoke.c`) is linked against every library;
it is *run* in the iOS simulator (`xcrun simctl`: a born-digital sample compared with its golden, a scan read by pdfium and Tesseract), and on an Android device or emulator of the same ABI when
`adb` finds one.

## First conversion

<!-- snippet: docs/snippets/c/quickstart.c -->
```c
/* pdf, len: the document's bytes (emon copies them) */
emon_input in = {0};
in.data = pdf;
in.len = len;
emon_buf html = {0};
emon_error err = {0};
if (emon_convert_html(&in, NULL, NULL, NULL, &html, &err) < 0) {
    fprintf(stderr, "%s: %s\n", err.name, err.message);
    emon_free_error(&err);
    return 1;
}
FILE *out = fopen("report.html", "wb");
fwrite(html.data, 1, html.len, out); /* html.data[html.len] is a NUL */
fclose(out);
emon_free_buf(&html);
```

`emon_convert_html` is one call: bytes in (copied), HTML out. This program is compiled with `-std=c11 -Wall -Werror` and run
against the built archive, linked both ways, by `bb package --check`.

## The shape of the API

| You | C |
|---|---|
| give a document | `emon_input`: `data`/`len` (copied), or `source`, a lazy `emon_source` (a `length` and a `read_at(ctx, offset, buf, len)` callback of yours, called on emon's worker thread, one call at a time: the engine reads only the spans it touches, in chunks); `password`/`password_len` for an encrypted PDF |
| say what to do | the `options` argument, `key=value` lines separated by LF (`"ocr=never\nlimit.pages=500"`), `NULL` for the defaults ([docs/options.md](../options.md)); `emon_check_options` parses a text and says what is wrong; `emon_is_option_key` asks about a name |
| convert | one-shot: `emon_convert` (a Model handle), `emon_convert_json`, `emon_convert_html` (a buffer), `emon_intake` (stage 0 alone: `pdf 1.4 pages=3 encrypted=false bytes=1234`); or the step API: `emon_open`, `emon_step`, `emon_provide`, `emon_finish` |
| read the result | `emon_model_json`, `emon_model_html` (the `html_options` text: a comma list of the [HTML words](../options.md#html-words)), `emon_model_evidence`, `emon_model_stats`, and the assets: `emon_model_asset_count`, `emon_model_asset_info`, `emon_model_asset_bytes`. The Model is given as JSON and HTML and assets, not as a walk of nodes |
| route on a failure | every function answers 0 (or a positive status) or `-code`, and fills an `emon_error` you pass: `code` is a category (`EMON_ERR_*`), `name` the stable name, `message` the sentence. Start it zeroed; `emon_free_error` it |
| free | emon allocates, you free with the matching `emon_free_*` (`emon_free_buf`, `emon_free_model`, `emon_free_error`, `emon_free_step`, `emon_free_files`, `emon_free_asset_info`). An `emon_buf` has a NUL after its `len` bytes, so JSON and HTML are C strings |

The error categories and names are in [docs/options.md](../options.md#errors). A panic inside emon is the error `internal`
(`EMON_ERR_INTERNAL`): it never crosses the boundary. A handle (`emon_session`, `emon_model`) is used by one thread at a time;
different handles may be used by different threads. The engine works on its own 32 MiB-stack thread, so your thread's stack
size does not matter.

## A tour

<!-- snippet: docs/snippets/c/tour.c -->
```c
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
if (emon_intake(&in, options, &line, &err) == 0) {
    printf("%s\n", (const char *)line.data);
    emon_free_buf(&line);
}

emon_model *model = NULL;
if (emon_convert(&in, options, NULL, &model, &err) < 0) {
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
```

## Scans and figures: pdfium and Tesseract

<!-- snippet: docs/snippets/c/native.c -->
```c
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
```

`bb package --check` compiles this against the built archive and runs it on `samples/scan-plain.pdf`, linked both ways, with no
`EMON_*` variable in the environment (the shared library finds `lib/libpdfium.*`, `lib/emon/adapters/` and `share/emon/packs` by itself; the static
link is given them through the environment, since the executable is not beside them), and requires the heading the OCR reads. It
runs where `bb native` and `bb pdfium-fetch` have been run, and prints a NOTE and skips on a machine that has neither.

## The step API: when you own the byte source, and, if you want, the fulfilment

`emon_open` / `emon_step` / `emon_finish` is the inverted loop of the browser binding, for a caller that owns the byte source
(a network range reader, an object store, a memory-mapped file). The engine never calls out: `emon_step` runs until it needs
something and says what. Given engines (`emon_open(..., engines, ...)`), **`emon_step` answers the `ocr` requests those adapters
are registered for itself**, so a caller needs no loop of its own for OCR; the plain loop, in which the caller answers, is unchanged.

<!-- snippet: docs/snippets/c/session.c -->
```c
/* pdf, len: the document, which only you can read. The engine never calls out: it says what it needs, you do it. */
emon_session *s = NULL;
emon_error err = {0};
if (emon_open(len, 0, 0, NULL, NULL, 0, "ocr=never", &s, &err) < 0) {
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
```

`EMON_NEED_BYTES` lists the ranges of the source the engine missed: read them and `emon_provide` them; the unit that missed
is re-run from scratch, so the bytes a conversion reads are the bytes it touched (`emon_prefetch` names the first and last chunk
that stage 0 wants, so a remote source can fetch them in one go). `EMON_NEED_REQUESTS` lists the requests that need a port, which
you owe only if the options say so, and only for what nobody registered an adapter for: `js-render=1` makes the `render` and `crop`
requests yours (answer with `emon_answer_render`: the whole page as RGB at the request's dpi and layer; a crop is cut from it by
emon), `js-ocr=1` makes the `ocr` requests yours (`emon_bitmap_png` gives the image to read, `emon_ocr_request` the fields of the
request, and `emon_answer_ocr` takes your engine's reading in the flat integer form of `emon.ocrport`, which kin validates). A
request you have no port for is answered `unsupported` by the session, and the page is unread.

**`EMON_WAITING`.** An adapter that answers later (`EMON_ADAPTER_ASYNC`: a service, a platform recognizer, an engine in another runtime)
returns from `start` at once. While only that work is outstanding `emon_step` answers `EMON_WAITING`: block in `emon_wait(s,
timeout_ms, &err)`, or run your own event loop, and step again. The answer arrives through the adapter's `complete`, or, for a callback
adapter whose engine answers through your program's loop, through `emon_complete(s, token, cells, ncells, failure, &err)` from any
thread (`token` is the one `start` was given; `failure` with code `EMON_ERR_UNSUPPORTED` or any other leaves the page unread). A
conversion that fails or closes with work outstanding calls the adapter's `cancel`; a late answer is ignored. The one-shot calls wait
for async adapters themselves (`emon_engines_set_timeout`, default 60 s, then the request is `failed`).

The session does not render with the library's own pdfium (the render requests stay the caller's, with `js-render=1`); the one-shot
calls do, when the engines have it.

The session also records and replays (`record=1`, `emon_transcript`, `emon_replay_file`): a transcript recorded by any host
replays on any other to the same bytes, and a recording made with adapters registered writes the engine table (`:pk-engines`) first. The
programs above need no port, and `bb package --check` compiles and runs each; the request-answering functions are described by the
header and have no example here.

## Notes

- Limits can also be set for the process with `EMON_LIMIT_*` ([docs/options.md](../options.md#limits)); a `limit.NAME` option beats it.
- `parallel=N` spreads the fulfilment of one conversion over N worker threads that emon starts and ends inside the call (the
  one-shot calls, with the engines you gave); the step API answers its adapters' requests one at a time on the stepping thread. The bytes
  are the serial run's.
- `emon_capabilities()` is what the core library itself can do in this process: `EMON_CAP_PDFIUM` (built with the renderer and finds pdfium) and
  `EMON_CAP_CODECS`. OCR is not a bit; `emon_engines_describe` says which engines you have.
- `emon_version()` is the library's version; `emon_const(i)` returns kin's protocol numbers (the request kinds and statuses) for
  the step API.

<!-- bb docs:begin api-index -->
<details><summary>The public API of the c host: 256 entries (generated from <code>api/surface.edn</code>)</summary>

Frozen: `bb api-diff` fails on a removal or a change (DEVLOG D527). One row per name; members are `Owner.member` or `Owner#member` or `Owner::member`.

| Name | Signature |
|---|---|
| `EMON_ADAPTER_ABI` | `#define 1` |
| `EMON_ADAPTER_ASYNC` | `#define 1` |
| `EMON_ADAPTER_FAILED` | `#define 2` |
| `EMON_ADAPTER_OK` | `#define 0` |
| `EMON_ADAPTER_REMOTE` | `#define 2` |
| `EMON_ADAPTER_THREADSAFE` | `#define 4` |
| `EMON_ADAPTER_UNSUPPORTED` | `#define 1` |
| `EMON_CAP_CODECS` | `#define 4` |
| `EMON_CAP_PDFIUM` | `#define 1` |
| `EMON_DONE` | `#define 0` |
| `EMON_ERR_DAMAGED` | `#define 8` |
| `EMON_ERR_ENCRYPTION_UNSUPPORTED` | `#define 6` |
| `EMON_ERR_INTERNAL` | `#define 13` |
| `EMON_ERR_IO` | `#define 10` |
| `EMON_ERR_LIMIT` | `#define 3` |
| `EMON_ERR_NO_INPUT` | `#define 2` |
| `EMON_ERR_PASSWORD_INCORRECT` | `#define 5` |
| `EMON_ERR_PASSWORD_REQUIRED` | `#define 4` |
| `EMON_ERR_PLAN` | `#define 12` |
| `EMON_ERR_SOURCE_CHANGED` | `#define 9` |
| `EMON_ERR_UNSUPPORTED` | `#define 11` |
| `EMON_ERR_UNSUPPORTED_FORMAT` | `#define 7` |
| `EMON_ERR_USAGE` | `#define 1` |
| `EMON_NEED_BYTES` | `#define 1` |
| `EMON_NEED_REQUESTS` | `#define 2` |
| `EMON_OEN_APPLE_VISION` | `#define 3` |
| `EMON_OEN_MLKIT_TEXT` | `#define 4` |
| `EMON_OEN_PPOCR` | `#define 2` |
| `EMON_OEN_TESSERACT` | `#define 1` |
| `EMON_OEN_WINDOWS_MEDIA_OCR` | `#define 5` |
| `EMON_OPEN_INTAKE` | `#define 1` |
| `EMON_PACKS_AUTO` | `#define 3` |
| `EMON_PACKS_CACHE` | `#define 2` |
| `EMON_PACKS_NEVER` | `#define 1` |
| `EMON_PACK_BY_APP` | `#define 2` |
| `EMON_PACK_BY_LIBRARY` | `#define 1` |
| `EMON_PACK_CLASSIFIER` | `#define 8` |
| `EMON_PACK_DECLINE` | `#define 3` |
| `EMON_PACK_DETECTOR` | `#define 4` |
| `EMON_PACK_EVENT_CANCELLED` | `#define 5` |
| `EMON_PACK_EVENT_DONE` | `#define 3` |
| `EMON_PACK_EVENT_FAILED` | `#define 4` |
| `EMON_PACK_EVENT_PROGRESS` | `#define 2` |
| `EMON_PACK_EVENT_START` | `#define 1` |
| `EMON_PACK_FETCH` | `#define 1` |
| `EMON_PACK_OSD` | `#define 2` |
| `EMON_PACK_PROVIDE` | `#define 2` |
| `EMON_PACK_RECOGNIZER` | `#define 1` |
| `EMON_PACK_SUBSTITUTE` | `#define 4` |
| `EMON_WAITING` | `#define 3` |
| `emon_adapter_answer` | `struct` |
| `emon_adapter_answer.binary` | `@2 const uint8_t *` |
| `emon_adapter_answer.binary_height` | `@4 int32_t` |
| `emon_adapter_answer.binary_width` | `@3 int32_t` |
| `emon_adapter_answer.cells` | `@0 int32_t *` |
| `emon_adapter_answer.ncells` | `@1 size_t` |
| `emon_adapter_answer.owner` | `@5 void *` |
| `emon_adapter_host` | `struct` |
| `emon_adapter_host.abi` | `@0 uint32_t` |
| `emon_adapter_host.complete` | `@3 int (*complete)(void *ctx, uint64_t token, int status, const int32_t *cells, size_t ncells, const emon_adapter_status *why)` |
| `emon_adapter_host.ctx` | `@2 void *` |
| `emon_adapter_host.size` | `@1 uint32_t` |
| `emon_adapter_image` | `struct` |
| `emon_adapter_image.channels` | `@2 int32_t` |
| `emon_adapter_image.dpi` | `@5 int32_t` |
| `emon_adapter_image.height` | `@1 int32_t` |
| `emon_adapter_image.pixels` | `@4 const uint8_t *` |
| `emon_adapter_image.stride` | `@3 int32_t` |
| `emon_adapter_image.width` | `@0 int32_t` |
| `emon_adapter_info` | `struct` |
| `emon_adapter_info.caps` | `@1 uint32_t` |
| `emon_adapter_info.config` | `@5 const char *` |
| `emon_adapter_info.engine` | `@0 uint32_t` |
| `emon_adapter_info.flags` | `@4 uint32_t` |
| `emon_adapter_info.langs` | `@8 const char *const *` |
| `emon_adapter_info.native` | `@2 uint32_t` |
| `emon_adapter_info.needs` | `@3 uint32_t` |
| `emon_adapter_info.nlangs` | `@9 size_t` |
| `emon_adapter_info.observed` | `@6 const char *` |
| `emon_adapter_info.producer` | `@7 const char *` |
| `emon_adapter_status` | `struct` |
| `emon_adapter_status.message` | `@1 char[224]` |
| `emon_adapter_status.reason` | `@0 char[96]` |
| `emon_answer` | `int (emon_session *s, const int32_t *cells, size_t ncells, emon_error *err)` |
| `emon_answer_ocr` | `int (emon_session *s, int32_t id, const int32_t *cells, size_t ncells, emon_error *err)` |
| `emon_answer_render` | `int (emon_session *s, int32_t id, int32_t width, int32_t height, const uint8_t *rgb, size_t len, emon_error *err)` |
| `emon_asset_info` | `struct` |
| `emon_asset_info.height` | `@6 int32_t` |
| `emon_asset_info.id` | `@0 int32_t` |
| `emon_asset_info.media_type` | `@2 char *` |
| `emon_asset_info.name` | `@1 char *` |
| `emon_asset_info.origin` | `@3 char *` |
| `emon_asset_info.sha256` | `@4 char *` |
| `emon_asset_info.size` | `@7 size_t` |
| `emon_asset_info.width` | `@5 int32_t` |
| `emon_bitmap_png` | `int (emon_session *s, int32_t id, emon_buf *out, emon_error *err)` |
| `emon_buf` | `struct` |
| `emon_buf.data` | `@0 uint8_t *` |
| `emon_buf.len` | `@1 size_t` |
| `emon_capabilities` | `unsigned (void)` |
| `emon_check_options` | `int (const char *options, emon_error *err)` |
| `emon_close` | `void (emon_session *s)` |
| `emon_complete` | `int (emon_session *s, uint64_t token, const int32_t *cells, size_t ncells, const emon_error *failure, emon_error *err)` |
| `emon_const` | `int (unsigned i)` |
| `emon_convert` | `int (const emon_input *input, const char *options, const emon_engines *engines, emon_model **out, emon_error *err)` |
| `emon_convert_html` | `int (const emon_input *input, const char *options, const emon_engines *engines, const char *html_options, emon_buf *out, emon_error *err)` |
| `emon_convert_images` | `int (const emon_input *inputs, size_t count, const char *options, const emon_engines *engines, emon_model **out, emon_error *err)` |
| `emon_convert_json` | `int (const emon_input *input, const char *options, const emon_engines *engines, int pretty, emon_buf *out, emon_error *err)` |
| `emon_engines` | `opaque struct` |
| `emon_engines_add_ocr` | `int (emon_engines *e, const emon_ocr_adapter *a, emon_error *err)` |
| `emon_engines_allow_remote` | `int (emon_engines *e, int allow, emon_error *err)` |
| `emon_engines_defaults` | `int (emon_engines *e, emon_error *err)` |
| `emon_engines_defaults_for` | `int (emon_engines *e, const char *options, emon_error *err)` |
| `emon_engines_describe` | `int (const emon_engines *e, emon_buf *out, emon_error *err)` |
| `emon_engines_free` | `void (emon_engines *e)` |
| `emon_engines_load` | `int (emon_engines *e, const char *path, const char *config, emon_error *err)` |
| `emon_engines_new` | `int (emon_engines **out, emon_error *err)` |
| `emon_engines_notes` | `int (emon_engines *e, emon_buf *out, emon_error *err)` |
| `emon_engines_packs` | `int (emon_engines *e, const char *dir, int mode, emon_error *err)` |
| `emon_engines_pdfium` | `int (emon_engines *e, const char *lib_or_dir, emon_error *err)` |
| `emon_engines_set_timeout` | `int (emon_engines *e, int32_t ms, emon_error *err)` |
| `emon_error` | `struct` |
| `emon_error.code` | `@0 int` |
| `emon_error.message` | `@2 char *` |
| `emon_error.name` | `@1 char *` |
| `emon_error_category` | `const char * (int code)` |
| `emon_fail` | `int (emon_session *s, int short_read, const char *message, emon_error *err)` |
| `emon_file` | `struct` |
| `emon_file.data` | `@1 uint8_t *` |
| `emon_file.len` | `@2 size_t` |
| `emon_file.name` | `@0 char *` |
| `emon_files` | `struct` |
| `emon_files.files` | `@0 emon_file *` |
| `emon_files.n` | `@1 size_t` |
| `emon_finish` | `int (emon_session *s, emon_model **out, emon_error *err)` |
| `emon_finish_intake` | `int (emon_session *s, emon_buf *out, emon_error *err)` |
| `emon_free_asset_info` | `void (emon_asset_info *a)` |
| `emon_free_buf` | `void (emon_buf *b)` |
| `emon_free_error` | `void (emon_error *e)` |
| `emon_free_files` | `void (emon_files *f)` |
| `emon_free_model` | `void (emon_model *m)` |
| `emon_free_step` | `void (emon_step_out *s)` |
| `emon_input` | `struct` |
| `emon_input.data` | `@0 const uint8_t *` |
| `emon_input.len` | `@1 size_t` |
| `emon_input.password` | `@3 const uint8_t *` |
| `emon_input.password_len` | `@4 size_t` |
| `emon_input.source` | `@2 const emon_source *` |
| `emon_intake` | `int (const emon_input *input, const char *options, emon_buf *out, emon_error *err)` |
| `emon_is_option_key` | `int (const char *name)` |
| `emon_limit` | `int (emon_session *s, int32_t kind, int64_t *out, emon_error *err)` |
| `emon_model` | `opaque struct` |
| `emon_model_asset_bytes` | `int (const emon_model *m, int i, emon_buf *out, emon_error *err)` |
| `emon_model_asset_count` | `int (const emon_model *m)` |
| `emon_model_asset_info` | `int (const emon_model *m, int i, emon_asset_info *out, emon_error *err)` |
| `emon_model_evidence` | `int (const emon_model *m, emon_buf *out, emon_error *err)` |
| `emon_model_html` | `int (const emon_model *m, const char *html_options, emon_buf *out, emon_error *err)` |
| `emon_model_json` | `int (const emon_model *m, int pretty, emon_buf *out, emon_error *err)` |
| `emon_model_stats` | `int (const emon_model *m, emon_stats *out)` |
| `emon_ocr_adapter` | `struct` |
| `emon_ocr_adapter.abi` | `@0 uint32_t` |
| `emon_ocr_adapter.bind` | `@10 void (*bind)(void *ctx, const emon_adapter_host *host)` |
| `emon_ocr_adapter.cancel` | `@8 void (*cancel)(void *ctx, uint64_t token)` |
| `emon_ocr_adapter.close` | `@11 void (*close)(void *ctx)` |
| `emon_ocr_adapter.ctx` | `@3 void *` |
| `emon_ocr_adapter.describe` | `@4 int (*describe)(void *ctx, emon_adapter_info *out, emon_adapter_status *st)` |
| `emon_ocr_adapter.name` | `@2 const char *` |
| `emon_ocr_adapter.read` | `@6 int (*read)(void *ctx, const int32_t *request, size_t n, const emon_adapter_image *image, int want_binary, emon_adapter_answer *out, emon_adapter_status *st)` |
| `emon_ocr_adapter.release` | `@9 void (*release)(void *ctx, emon_adapter_answer *a)` |
| `emon_ocr_adapter.size` | `@1 uint32_t` |
| `emon_ocr_adapter.start` | `@7 int (*start)(void *ctx, const int32_t *request, size_t n, const emon_adapter_image *image, uint64_t token, emon_adapter_status *st)` |
| `emon_ocr_adapter.supports` | `@5 int (*supports)(void *ctx, const int32_t *request, size_t n, emon_adapter_status *st)` |
| `emon_ocr_adapter_v1` | `const emon_ocr_adapter * (const char *config, const char *packs_dir, emon_adapter_status *st)` |
| `emon_ocr_adapter_v1_fn` | `typedef const emon_ocr_adapter *(*emon_ocr_adapter_v1_fn)(const char *config, const char *packs_dir, emon_adapter_status *st)` |
| `emon_ocr_request` | `int (const int32_t *request, size_t nrequest, emon_ocr_request_info *out, emon_error *err)` |
| `emon_ocr_request_info` | `struct` |
| `emon_ocr_request_info.bitmap` | `@1 int32_t` |
| `emon_ocr_request_info.dpi` | `@5 int32_t` |
| `emon_ocr_request_info.engine` | `@2 int32_t` |
| `emon_ocr_request_info.flags` | `@4 int32_t` |
| `emon_ocr_request_info.h` | `@9 int32_t` |
| `emon_ocr_request_info.id` | `@0 int32_t` |
| `emon_ocr_request_info.langs` | `@11 char[8][8]` |
| `emon_ocr_request_info.nlangs` | `@10 int32_t` |
| `emon_ocr_request_info.psm` | `@3 int32_t` |
| `emon_ocr_request_info.w` | `@8 int32_t` |
| `emon_ocr_request_info.x` | `@6 int32_t` |
| `emon_ocr_request_info.y` | `@7 int32_t` |
| `emon_on_pack_event` | `int (emon_engines *e, emon_pack_event_fn fn, void *ctx, emon_error *err)` |
| `emon_on_pack_needed` | `int (emon_engines *e, emon_pack_needed_fn fn, void *ctx, emon_error *err)` |
| `emon_open` | `int (uint64_t length, uint32_t chunk, unsigned flags, const emon_engines *engines, const uint8_t *password, size_t password_len, const char *options, emon_session **out, emon_error *err)` |
| `emon_pack_decision` | `struct` |
| `emon_pack_decision.action` | `@0 int32_t` |
| `emon_pack_decision.data` | `@1 const uint8_t *` |
| `emon_pack_decision.engine` | `@4 const char *` |
| `emon_pack_decision.len` | `@2 size_t` |
| `emon_pack_decision.path` | `@3 const char *` |
| `emon_pack_event` | `struct` |
| `emon_pack_event.done` | `@3 uint64_t` |
| `emon_pack_event.engine` | `@2 const char *` |
| `emon_pack_event.initiator` | `@5 int32_t` |
| `emon_pack_event.kind` | `@0 int32_t` |
| `emon_pack_event.message` | `@6 const char *` |
| `emon_pack_event.pack` | `@1 const char *` |
| `emon_pack_event.total` | `@4 uint64_t` |
| `emon_pack_event_fn` | `typedef int (*emon_pack_event_fn)(void *ctx, const emon_pack_event *ev)` |
| `emon_pack_needed_fn` | `typedef int (*emon_pack_needed_fn)(void *ctx, const emon_pack_request *req, emon_pack_decision *out)` |
| `emon_pack_request` | `struct` |
| `emon_pack_request.bytes` | `@5 uint64_t` |
| `emon_pack_request.engine` | `@2 const char *` |
| `emon_pack_request.kind` | `@3 const char *` |
| `emon_pack_request.licence` | `@4 const char *` |
| `emon_pack_request.pack` | `@0 const char *` |
| `emon_pack_request.reason` | `@6 const char *` |
| `emon_pack_request.request_engine` | `@7 const char *` |
| `emon_pack_request.rev` | `@1 int32_t` |
| `emon_packs_cancel` | `void (emon_engines *e)` |
| `emon_packs_ensure` | `int (emon_engines *e, const char *ids, emon_error *err)` |
| `emon_prefetch` | `int (emon_session *s, emon_step_out *out, emon_error *err)` |
| `emon_prepare` | `int (const emon_engines *e, const char *options, emon_buf *out, emon_error *err)` |
| `emon_provide` | `int (emon_session *s, uint64_t offset, const uint8_t *data, size_t len, emon_error *err)` |
| `emon_range` | `struct` |
| `emon_range.length` | `@1 uint64_t` |
| `emon_range.offset` | `@0 uint64_t` |
| `emon_read_fn` | `typedef int64_t (*emon_read_fn)(void *ctx, uint64_t offset, uint8_t *buf, size_t len)` |
| `emon_replay_end` | `int (emon_session *s, emon_error *err)` |
| `emon_replay_file` | `int (emon_session *s, const char *name, const uint8_t *data, size_t len, emon_error *err)` |
| `emon_request` | `struct` |
| `emon_request.cells` | `@2 const int32_t *` |
| `emon_request.id` | `@1 int32_t` |
| `emon_request.kind` | `@0 int32_t` |
| `emon_request.ncells` | `@3 size_t` |
| `emon_session` | `opaque struct` |
| `emon_source` | `struct` |
| `emon_source.ctx` | `@2 void *` |
| `emon_source.length` | `@0 uint64_t` |
| `emon_source.read_at` | `@1 emon_read_fn` |
| `emon_stats` | `struct` |
| `emon_stats.attempts` | `@0 int32_t` |
| `emon_stats.bytes_fetched` | `@5 uint64_t` |
| `emon_stats.bytes_requests` | `@2 int32_t` |
| `emon_stats.fetch_rounds` | `@1 int32_t` |
| `emon_stats.input_bytes` | `@6 uint64_t` |
| `emon_stats.requests` | `@3 int32_t` |
| `emon_stats.source_reads` | `@4 uint32_t` |
| `emon_step` | `int (emon_session *s, emon_step_out *out, emon_error *err)` |
| `emon_step_out` | `struct` |
| `emon_step_out.cells` | `@4 int32_t *` |
| `emon_step_out.ncells` | `@5 size_t` |
| `emon_step_out.nranges` | `@1 size_t` |
| `emon_step_out.nrequests` | `@3 size_t` |
| `emon_step_out.ranges` | `@0 emon_range *` |
| `emon_step_out.requests` | `@2 emon_request *` |
| `emon_transcript` | `int (emon_session *s, emon_files *out, emon_error *err)` |
| `emon_version` | `const char * (void)` |
| `emon_wait` | `int (emon_session *s, int32_t timeout_ms, emon_error *err)` |

</details>
<!-- bb docs:end api-index -->
