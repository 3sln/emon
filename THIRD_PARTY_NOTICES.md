# Third-party notices

emon is released under the MIT licence (see `LICENSE`). It includes, links or can load the third-party components below. This file is generated from `release/third-party.edn` by `bb release-audit --write`; do not edit it by hand (`bb release-audit` fails when it is stale). A licence text marked TODO is not yet vendored; P11.5 adds it with the build of the library.

Rust crates are compiled into the Rust, C, pod, WASM and CLI artifacts. pdfium, Tesseract, Leptonica and the tessdata are carried only by the per-platform CLI and the browser demo; every other host loads pdfium and Tesseract from the user's system at run time.

## Rust crates

Compiled into the Rust, C, pod and WASM artifacts (host/rust/Cargo.lock).

- **adler2** 2.0.1 - 0BSD OR MIT OR Apache-2.0 (used under MIT) - https://crates.io/crates/adler2
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [11](#licence-text-11).

- **autocfg** 1.5.1 - Apache-2.0 OR MIT (used under MIT) - https://crates.io/crates/autocfg
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [13](#licence-text-13).

- **bitflags** 2.13.2 - MIT OR Apache-2.0 (used under MIT) - https://crates.io/crates/bitflags
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [8](#licence-text-8).

- **bytemuck** 1.25.2 - Zlib OR Apache-2.0 OR MIT (used under MIT) - https://crates.io/crates/bytemuck
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [7](#licence-text-7).

- **byteorder-lite** 0.1.0 - Unlicense OR MIT (used under MIT) - https://crates.io/crates/byteorder-lite
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [5](#licence-text-5).

- **cfg-if** 1.0.5 - MIT OR Apache-2.0 (used under MIT) - https://crates.io/crates/cfg-if
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [14](#licence-text-14).

- **crc32fast** 1.5.2 - MIT OR Apache-2.0 (used under MIT) - https://crates.io/crates/crc32fast
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [6](#licence-text-6).

- **fdeflate** 0.3.7 - MIT OR Apache-2.0 (used under MIT) - https://crates.io/crates/fdeflate
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [12](#licence-text-12).

- **flate2** 1.1.10 - MIT OR Apache-2.0 (used under MIT) - https://crates.io/crates/flate2
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [10](#licence-text-10).

- **image** 0.25.10 - MIT OR Apache-2.0 (used under MIT) - https://crates.io/crates/image
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [12](#licence-text-12).

- **miniz_oxide** 0.8.9 - MIT OR Zlib OR Apache-2.0 (used under MIT) - https://crates.io/crates/miniz_oxide
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [3](#licence-text-3).

- **miniz_oxide** 0.9.1 - MIT OR Zlib OR Apache-2.0 (used under MIT) - https://crates.io/crates/miniz_oxide
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [3](#licence-text-3).

- **moxcms** 0.8.1 - BSD-3-Clause OR Apache-2.0 (used under BSD-3-Clause) - https://crates.io/crates/moxcms
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [2](#licence-text-2).

- **num-traits** 0.2.19 - MIT OR Apache-2.0 (used under MIT) - https://crates.io/crates/num-traits
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [8](#licence-text-8).

- **png** 0.18.1 - MIT OR Apache-2.0 (used under MIT) - https://crates.io/crates/png
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [18](#licence-text-18).

- **pxfm** 0.1.30 - BSD-3-Clause OR Apache-2.0 (used under BSD-3-Clause) - https://crates.io/crates/pxfm
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [2](#licence-text-2).

- **simd-adler32** 0.3.10 - MIT - https://crates.io/crates/simd-adler32
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [1](#licence-text-1).

- **zlib-rs** 0.6.8 - Zlib - https://crates.io/crates/zlib-rs
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [4](#licence-text-4).

- **zune-core** 0.5.3 - MIT OR Apache-2.0 OR Zlib (used under MIT) - https://crates.io/crates/zune-core
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [16](#licence-text-16).

- **zune-jpeg** 0.5.15 - MIT OR Apache-2.0 OR Zlib (used under MIT) - https://crates.io/crates/zune-jpeg
  carried by the Rust crate `emon` (crates.io), the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c).
  Licence text: [16](#licence-text-16).


## Native libraries

Not carried by the library packages (the Go, Java and C# hosts and the C library and pod load pdfium and Tesseract from the user's system at run time); carried by the per-platform CLI and the browser demo.

- **pdfium** chromium/8076 (156.0.8076.0) - Apache-2.0 - https://pdfium.googlesource.com/pdfium/
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c); loaded at run time (not carried) by the Rust crate `emon` (crates.io), the Go module, the Java library (Maven Central, com.3sln), the NuGet package Emon, the C library and emon.h, the babashka pod. Pinned in tools/pdfium/pdfium.edn. The Go, Java and C# hosts, the C library and the pod load it from the user's system and do not carry it.
  Licence text: TODO: vendored at P11.5.

- **pdfium-binaries (the build scripts and packaging)** chromium/8076 - MIT - https://github.com/bblanchon/pdfium-binaries
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Copyright 2014-2025 Benoit Blanchon; its LICENSE ships in each archive.
  Licence text: TODO: vendored at P11.5.

- **abseil-cpp (in pdfium)** bundled in pdfium chromium/8076 - Apache-2.0 - https://abseil.io/
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform.
  Licence text: TODO: vendored at P11.5.

- **Anti-Grain Geometry 2.3 (in pdfium)** bundled in pdfium chromium/8076 - LicenseRef-AGG-2.3 - https://github.com/google/pdfium/tree/main/third_party/agg23
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform. Permissive notice-retention terms of Maxim Shemanarev, not an SPDX-listed licence.
  Licence text: TODO: vendored at P11.5.

- **fast_float (in pdfium)** bundled in pdfium chromium/8076 - Apache-2.0 OR MIT (used under MIT) - https://github.com/fastfloat/fast_float
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform.
  Licence text: TODO: vendored at P11.5.

- **FreeType (in pdfium)** bundled in pdfium chromium/8076 - FTL - https://freetype.org/
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform. Used under the FreeType Project License; its credit clause is met by this notice.
  Licence text: TODO: vendored at P11.5.

- **ICU (in pdfium)** bundled in pdfium chromium/8076 - Unicode-3.0 - https://icu.unicode.org/
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform.
  Licence text: TODO: vendored at P11.5.

- **Little CMS 2 (in pdfium)** bundled in pdfium chromium/8076 - MIT - https://www.littlecms.com/
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform.
  Licence text: TODO: vendored at P11.5.

- **libjpeg-turbo (in pdfium)** bundled in pdfium chromium/8076 - IJG AND BSD-3-Clause AND Zlib - https://libjpeg-turbo.org/
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform.
  Licence text: TODO: vendored at P11.5.

- **OpenJPEG (in pdfium)** bundled in pdfium chromium/8076 - BSD-2-Clause - https://www.openjpeg.org/
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform.
  Licence text: TODO: vendored at P11.5.

- **libpng (in pdfium)** bundled in pdfium chromium/8076 - libpng-2.0 - http://www.libpng.org/pub/png/libpng.html
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform.
  Licence text: TODO: vendored at P11.5.

- **LLVM libc (in pdfium)** bundled in pdfium chromium/8076 - Apache-2.0 WITH LLVM-exception - https://libc.llvm.org/
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform.
  Licence text: TODO: vendored at P11.5.

- **simdutf (in pdfium)** bundled in pdfium chromium/8076 - Apache-2.0 OR MIT (used under MIT) - https://github.com/simdutf/simdutf
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform.
  Licence text: TODO: vendored at P11.5.

- **zlib (in pdfium)** bundled in pdfium chromium/8076 - Zlib - https://zlib.net/
  carried by the per-platform CLI (P11.4b), the browser demo page (P11.4c). Bundled inside the pinned pdfium build (bblanchon/pdfium-binaries chromium/8076, its licenses/ directory); P11.5 re-verifies the list per platform.
  Licence text: TODO: vendored at P11.5.

- **Tesseract** 5.5.3 - Apache-2.0 - https://github.com/tesseract-ocr/tesseract
  carried by the per-platform CLI (P11.4b); loaded at run time (not carried) by the Rust crate `emon` (crates.io), the Go module, the Java library (Maven Central, com.3sln), the NuGet package Emon, the C library and emon.h, the babashka pod. The version recorded by the committed transcripts (test/ocr/PRODUCER.json); P11.5 pins the release build. Built without libarchive and libcurl.
  Licence text: TODO: vendored at P11.5.

- **Leptonica** 1.87.0 - BSD-2-Clause - http://www.leptonica.org/
  carried by the per-platform CLI (P11.4b); loaded at run time (not carried) by the Rust crate `emon` (crates.io), the Go module, the Java library (Maven Central, com.3sln), the NuGet package Emon, the C library and emon.h, the babashka pod. Leptonica's own licence is a two-clause BSD text. Static in the CLI.
  Licence text: TODO: vendored at P11.5.

- **libpng (Leptonica's PNG codec)** pinned at P11.5 - libpng-2.0 - http://www.libpng.org/pub/png/libpng.html
  carried by the per-platform CLI (P11.4b).
  Licence text: TODO: vendored at P11.5.

- **libjpeg-turbo (Leptonica's JPEG codec)** pinned at P11.5 - IJG AND BSD-3-Clause AND Zlib - https://libjpeg-turbo.org/
  carried by the per-platform CLI (P11.4b).
  Licence text: TODO: vendored at P11.5.

- **libtiff (Leptonica's TIFF codec)** pinned at P11.5 - libtiff - https://libtiff.gitlab.io/libtiff/
  carried by the per-platform CLI (P11.4b). A permissive BSD-style notice, not in the SPEC allowlist; listed so a maintainer decides (D1410).
  Licence text: TODO: vendored at P11.5.

- **zlib (Leptonica's Flate codec)** pinned at P11.5 - Zlib - https://zlib.net/
  carried by the per-platform CLI (P11.4b).
  Licence text: TODO: vendored at P11.5.

- **giflib (Leptonica's GIF codec)** pinned at P11.5 - MIT - https://giflib.sourceforge.net/
  carried by the per-platform CLI (P11.4b).
  Licence text: TODO: vendored at P11.5.

- **libwebp (Leptonica's WebP codec)** pinned at P11.5 - BSD-3-Clause - https://chromium.googlesource.com/webm/libwebp
  carried by the per-platform CLI (P11.4b).
  Licence text: TODO: vendored at P11.5.

- **OpenJPEG (Leptonica's JPEG 2000 codec, if linked)** pinned at P11.5 - BSD-2-Clause - https://www.openjpeg.org/
  carried by the per-platform CLI (P11.4b). Present only if P11.5 builds Leptonica with it; JPX assets stay unsupported in releases (D1400).
  Licence text: TODO: vendored at P11.5.

- **Tesseract eng.traineddata** tessdata_fast, 4113088 bytes, sha256 7d4322bd2a77... - Apache-2.0 - https://github.com/tesseract-ocr/tessdata_fast
  carried by the per-platform CLI (P11.4b).
  Licence text: TODO: vendored at P11.5.

- **Tesseract osd.traineddata** tessdata_fast, sha256 9cf5d576fcc4... - Apache-2.0 - https://github.com/tesseract-ocr/tessdata_fast
  carried by the per-platform CLI (P11.4b).
  Licence text: TODO: vendored at P11.5.


## Data the generated code embeds

Tables built into every artifact by kin's generators from these sources.

- **Unicode Character Database (UnicodeData, BidiBrackets, BidiMirroring, CJKRadicals, DerivedNormalizationProps)** 18.0.0 - Unicode-3.0 - https://www.unicode.org/license.txt
  carried by the Rust crate `emon` (crates.io), the Go module, the Java library (Maven Central, com.3sln), the NuGet package Emon, the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c). In kin/bidiclass.kin, kin/nftable.kin, kin/radicals.kin (test/normalize/, test/bidi/).
  Licence text: [9](#licence-text-9).

- **Adobe Glyph List, AGLFN and ZapfDingbats names** 2.0 (glyphlist.txt) - BSD-3-Clause - https://github.com/adobe-type-tools/agl-aglfn
  carried by the Rust crate `emon` (crates.io), the Go module, the Java library (Maven Central, com.3sln), the NuGet package Emon, the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c). In kin/agl.kin (test/fonts/glyphlist.txt, aglfn.txt, zapfdingbats.txt).
  Licence text: [19](#licence-text-19).

- **Adobe cmap-resources (the predefined CMaps and cid2code tables)** commit f5cf3bca7fdfeaceb77aa82847e974f2306c20b4 - BSD-3-Clause - https://github.com/adobe-type-tools/cmap-resources
  carried by the Rust crate `emon` (crates.io), the Go module, the Java library (Maven Central, com.3sln), the NuGet package Emon, the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c). In kin/cmapjp.kin, cmapgb.kin, cmapcns.kin, cmapkr.kin, cjk.kin (fetched by kin/scripts/cjkfetch.clj).
  Licence text: [15](#licence-text-15).

- **pdf.js font encoding and charset tables (encodings.js, charsets.js, cff_parser.js, fonts.js)** commit 81525fd446779f0a29003cf06e3ba33751954826 - Apache-2.0 - https://github.com/mozilla/pdf.js
  carried by the Rust crate `emon` (crates.io), the Go module, the Java library (Maven Central, com.3sln), the NuGet package Emon, the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c). Copyright Mozilla Foundation. In kin/fontextra.kin (CFF standard strings and predefined charsets, MacExpertEncoding, the TrueType Macintosh glyph order).
  Licence text: [17](#licence-text-17).

- **Adobe Core 14 font metrics (AFM)** 4.1 (1997) - LicenseRef-Adobe-Core14-AFM - https://www.adobe.com/devnet/font/pdfs/Core14_AFMs.zip
  carried by the Rust crate `emon` (crates.io), the Go module, the Java library (Maven Central, com.3sln), the NuGet package Emon, the npm package @3sln/emon (WASM and JS SDK), the C library and emon.h, the babashka pod, the per-platform CLI (P11.4b), the browser demo page (P11.4c). Widths and encodings derived into kin/encodings.kin (test/fonts/*.afm). Adobe permits copying and distribution for any purpose provided its copyright notices stay; the exact text is TODO, a maintainer decides (D1410).
  Licence text: TODO: vendored at P11.5.


## npm packages

Development dependencies of host/js (the browser test harness and the Tesseract.js adapter); `files` is `src` only, so none is in the npm package.

- **yargs-parser** 22.0.0 - ISC - https://www.npmjs.com/package/yargs-parser
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **wasm-feature-detect** 1.9.0 - Apache-2.0 - https://www.npmjs.com/package/wasm-feature-detect
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **tesseract.js** 7.0.0 - Apache-2.0 - https://www.npmjs.com/package/tesseract.js
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **bmp-js** 0.1.0 - MIT - https://www.npmjs.com/package/bmp-js
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **strip-ansi** 7.2.0 - MIT - https://www.npmjs.com/package/strip-ansi
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **emoji-regex** 10.6.0 - MIT - https://www.npmjs.com/package/emoji-regex
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **webidl-conversions** 3.0.1 - BSD-2-Clause - https://www.npmjs.com/package/webidl-conversions
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **devtools-protocol** 0.0.1687809 - BSD-3-Clause - https://www.npmjs.com/package/devtools-protocol
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **opencollective-postinstall** 2.0.3 - MIT - https://www.npmjs.com/package/opencollective-postinstall
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **mitt** 3.0.1 - MIT - https://www.npmjs.com/package/mitt
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **@tesseract.js-data/eng** 1.0.0 - MIT - https://www.npmjs.com/package/@tesseract.js-data/eng
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **regenerator-runtime** 0.13.11 - MIT - https://www.npmjs.com/package/regenerator-runtime
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **whatwg-url** 5.0.0 - MIT - https://www.npmjs.com/package/whatwg-url
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **yargs** 18.2.0 - MIT - https://www.npmjs.com/package/yargs
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **@puppeteer/browsers** 3.2.3 - Apache-2.0 - https://www.npmjs.com/package/@puppeteer/browsers
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **zod** 3.25.76 - MIT - https://www.npmjs.com/package/zod
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **escalade** 3.2.0 - MIT - https://www.npmjs.com/package/escalade
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **string-width** 8.3.0 - MIT - https://www.npmjs.com/package/string-width
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **modern-tar** 0.8.5 - MIT - https://www.npmjs.com/package/modern-tar
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **chromium-bidi** 17.0.2 - Apache-2.0 - https://www.npmjs.com/package/chromium-bidi
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **webdriver-bidi-protocol** 0.4.3 - Apache-2.0 - https://www.npmjs.com/package/webdriver-bidi-protocol
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **tesseract.js-core** 7.0.0 - Apache-2.0 - https://www.npmjs.com/package/tesseract.js-core
  carried by no release artifact. If P11.4c's demo ships this WASM build of Tesseract, it carries Leptonica and its image codecs too, and moves to the native section.
  Licence text: not carried by any artifact; the upstream package holds it.

- **is-url** 1.2.4 - MIT - https://www.npmjs.com/package/is-url
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **typed-query-selector** 2.12.3 - MIT - https://www.npmjs.com/package/typed-query-selector
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **ansi-styles** 6.2.3 - MIT - https://www.npmjs.com/package/ansi-styles
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **idb-keyval** 6.3.0 - Apache-2.0 - https://www.npmjs.com/package/idb-keyval
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **node-fetch** 2.7.0 - MIT - https://www.npmjs.com/package/node-fetch
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **y18n** 5.0.8 - ISC - https://www.npmjs.com/package/y18n
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **puppeteer-core** 25.12.0 - Apache-2.0 - https://www.npmjs.com/package/puppeteer-core
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **get-caller-file** 2.0.5 - ISC - https://www.npmjs.com/package/get-caller-file
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **zlibjs** 0.3.1 - MIT - https://www.npmjs.com/package/zlibjs
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **wrap-ansi** 9.0.2 - MIT - https://www.npmjs.com/package/wrap-ansi
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **string-width** 7.2.0 - MIT - https://www.npmjs.com/package/string-width
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **get-east-asian-width** 1.7.0 - MIT - https://www.npmjs.com/package/get-east-asian-width
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **cliui** 9.0.1 - ISC - https://www.npmjs.com/package/cliui
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **ws** 8.22.0 - MIT - https://www.npmjs.com/package/ws
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **tr46** 0.0.3 - MIT - https://www.npmjs.com/package/tr46
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

- **ansi-regex** 6.4.0 - MIT - https://www.npmjs.com/package/ansi-regex
  carried by no release artifact.
  Licence text: not carried by any artifact; the upstream package holds it.

## Licence texts

### Licence text 1

Used by: simd-adler32 0.3.10.

````text
MIT License

Copyright (c) [2021] [Marvin Countryman]

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
````

### Licence text 2

Used by: moxcms 0.8.1, pxfm 0.1.30.

````text
Copyright (c) Radzivon Bartoshyk. All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1.  Redistributions of source code must retain the above copyright notice, this
    list of conditions and the following disclaimer.

2.  Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions and the following disclaimer in the documentation
    and/or other materials provided with the distribution.

3.  Neither the name of the copyright holder nor the names of its
    contributors may be used to endorse or promote products derived from
    this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````

### Licence text 3

Used by: miniz_oxide 0.8.9, miniz_oxide 0.9.1.

````text
MIT License

Copyright 2013-2014 RAD Game Tools and Valve Software
Copyright 2010-2014 Rich Geldreich and Tenacious Software LLC
Copyright (c) 2017 Frommi
Copyright (c) 2017-2024 oyvindln

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
````

### Licence text 4

Used by: zlib-rs 0.6.8.

````text
(C) 2024 Trifecta Tech Foundation 

This software is provided 'as-is', without any express or implied
warranty. In no event will the authors be held liable for any damages
arising from the use of this software.

Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:

1. The origin of this software must not be misrepresented; you must not
   claim that you wrote the original software. If you use this software
   in a product, an acknowledgment in the product documentation would be
   appreciated but is not required.

2. Altered source versions must be plainly marked as such, and must not be
   misrepresented as being the original software.

3. This notice may not be removed or altered from any source distribution.
````

### Licence text 5

Used by: byteorder-lite 0.1.0.

````text
The MIT License (MIT)

Copyright (c) 2015 Andrew Gallant

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
````

### Licence text 6

Used by: crc32fast 1.5.2.

````text
MIT License

Copyright (c) 2018 Sam Rijs, Alex Crichton and contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
````

### Licence text 7

Used by: bytemuck 1.25.2.

````text
MIT License

Copyright (c) 2019 Daniel "Lokathor" Gee.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice (including the next paragraph) shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
````

### Licence text 8

Used by: bitflags 2.13.2, num-traits 0.2.19.

````text
Copyright (c) 2014 The Rust Project Developers

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
````

### Licence text 9

Used by: Unicode Character Database (UnicodeData, BidiBrackets, BidiMirroring, CJKRadicals, DerivedNormalizationProps) 18.0.0.

````text
UNICODE LICENSE V3

COPYRIGHT AND PERMISSION NOTICE

Copyright © 2016-2025 Unicode, Inc.

NOTICE TO USER: Carefully read the following legal agreement. BY
DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

Permission is hereby granted, free of charge, to any person obtaining a
copy of data files and any associated documentation (the "Data Files") or
software and any associated documentation (the "Software") to deal in the
Data Files or Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, and/or sell
copies of the Data Files or Software, and to permit persons to whom the
Data Files or Software are furnished to do so, provided that either (a)
this copyright and permission notice appear with all copies of the Data
Files or Software, or (b) this copyright and permission notice appear in
associated Documentation.

THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
THIRD PARTY RIGHTS.

IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
FILES OR SOFTWARE.

Except as contained in this notice, the name of a copyright holder shall
not be used in advertising or otherwise to promote the sale, use or other
dealings in these Data Files or Software without prior written
authorization of the copyright holder.

SPDX-License-Identifier: Unicode-3.0
````

### Licence text 10

Used by: flate2 1.1.10.

````text
Copyright (c) 2014-2026 Alex Crichton

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
````

### Licence text 11

Used by: adler2 2.0.1.

````text
Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
````

### Licence text 12

Used by: fdeflate 0.3.7, image 0.25.10.

````text
MIT License

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
````

### Licence text 13

Used by: autocfg 1.5.1.

````text
Copyright (c) 2018 Josh Stone

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
````

### Licence text 14

Used by: cfg-if 1.0.5.

````text
Copyright (c) 2014 Alex Crichton

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
````

### Licence text 15

Used by: Adobe cmap-resources (the predefined CMaps and cid2code tables) commit f5cf3bca7fdfeaceb77aa82847e974f2306c20b4.

````text
Copyright 1990-2023 Adobe. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

Redistributions of source code must retain the above copyright notice,
this list of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

Neither the name of Adobe nor the names of its contributors may be
used to endorse or promote products derived from this software without
specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````

### Licence text 16

Used by: zune-core 0.5.3, zune-jpeg 0.5.15.

````text
MIT License

Copyright (c) zune-image developers

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
````

### Licence text 17

Used by: pdf.js font encoding and charset tables (encodings.js, charsets.js, cff_parser.js, fonts.js) commit 81525fd446779f0a29003cf06e3ba33751954826.

````text
                              Apache License
                        Version 2.0, January 2004
                     https://www.apache.org/licenses/LICENSE-2.0

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.

   "License" shall mean the terms and conditions for use, reproduction,
   and distribution as defined by Sections 1 through 9 of this document.

   "Licensor" shall mean the copyright owner or entity authorized by
   the copyright owner that is granting the License.

   "Legal Entity" shall mean the union of the acting entity and all
   other entities that control, are controlled by, or are under common
   control with that entity. For the purposes of this definition,
   "control" means (i) the power, direct or indirect, to cause the
   direction or management of such entity, whether by contract or
   otherwise, or (ii) ownership of fifty percent (50%) or more of the
   outstanding shares, or (iii) beneficial ownership of such entity.

   "You" (or "Your") shall mean an individual or Legal Entity
   exercising permissions granted by this License.

   "Source" form shall mean the preferred form for making modifications,
   including but not limited to software source code, documentation
   source, and configuration files.

   "Object" form shall mean any form resulting from mechanical
   transformation or translation of a Source form, including but
   not limited to compiled object code, generated documentation,
   and conversions to other media types.

   "Work" shall mean the work of authorship, whether in Source or
   Object form, made available under the License, as indicated by a
   copyright notice that is included in or attached to the work
   (an example is provided in the Appendix below).

   "Derivative Works" shall mean any work, whether in Source or Object
   form, that is based on (or derived from) the Work and for which the
   editorial revisions, annotations, elaborations, or other modifications
   represent, as a whole, an original work of authorship. For the purposes
   of this License, Derivative Works shall not include works that remain
   separable from, or merely link (or bind by name) to the interfaces of,
   the Work and Derivative Works thereof.

   "Contribution" shall mean any work of authorship, including
   the original version of the Work and any modifications or additions
   to that Work or Derivative Works thereof, that is intentionally
   submitted to Licensor for inclusion in the Work by the copyright owner
   or by an individual or Legal Entity authorized to submit on behalf of
   the copyright owner. For the purposes of this definition, "submitted"
   means any form of electronic, verbal, or written communication sent
   to the Licensor or its representatives, including but not limited to
   communication on electronic mailing lists, source code control systems,
   and issue tracking systems that are managed by, or on behalf of, the
   Licensor for the purpose of discussing and improving the Work, but
   excluding communication that is conspicuously marked or otherwise
   designated in writing by the copyright owner as "Not a Contribution."

   "Contributor" shall mean Licensor and any individual or Legal Entity
   on behalf of whom a Contribution has been received by Licensor and
   subsequently incorporated within the Work.

2. Grant of Copyright License. Subject to the terms and conditions of
   this License, each Contributor hereby grants to You a perpetual,
   worldwide, non-exclusive, no-charge, royalty-free, irrevocable
   copyright license to reproduce, prepare Derivative Works of,
   publicly display, publicly perform, sublicense, and distribute the
   Work and such Derivative Works in Source or Object form.

3. Grant of Patent License. Subject to the terms and conditions of
   this License, each Contributor hereby grants to You a perpetual,
   worldwide, non-exclusive, no-charge, royalty-free, irrevocable
   (except as stated in this section) patent license to make, have made,
   use, offer to sell, sell, import, and otherwise transfer the Work,
   where such license applies only to those patent claims licensable
   by such Contributor that are necessarily infringed by their
   Contribution(s) alone or by combination of their Contribution(s)
   with the Work to which such Contribution(s) was submitted. If You
   institute patent litigation against any entity (including a
   cross-claim or counterclaim in a lawsuit) alleging that the Work
   or a Contribution incorporated within the Work constitutes direct
   or contributory patent infringement, then any patent licenses
   granted to You under this License for that Work shall terminate
   as of the date such litigation is filed.

4. Redistribution. You may reproduce and distribute copies of the
   Work or Derivative Works thereof in any medium, with or without
   modifications, and in Source or Object form, provided that You
   meet the following conditions:

   (a) You must give any other recipients of the Work or
       Derivative Works a copy of this License; and

   (b) You must cause any modified files to carry prominent notices
       stating that You changed the files; and

   (c) You must retain, in the Source form of any Derivative Works
       that You distribute, all copyright, patent, trademark, and
       attribution notices from the Source form of the Work,
       excluding those notices that do not pertain to any part of
       the Derivative Works; and

   (d) If the Work includes a "NOTICE" text file as part of its
       distribution, then any Derivative Works that You distribute must
       include a readable copy of the attribution notices contained
       within such NOTICE file, excluding those notices that do not
       pertain to any part of the Derivative Works, in at least one
       of the following places: within a NOTICE text file distributed
       as part of the Derivative Works; within the Source form or
       documentation, if provided along with the Derivative Works; or,
       within a display generated by the Derivative Works, if and
       wherever such third-party notices normally appear. The contents
       of the NOTICE file are for informational purposes only and
       do not modify the License. You may add Your own attribution
       notices within Derivative Works that You distribute, alongside
       or as an addendum to the NOTICE text from the Work, provided
       that such additional attribution notices cannot be construed
       as modifying the License.

   You may add Your own copyright statement to Your modifications and
   may provide additional or different license terms and conditions
   for use, reproduction, or distribution of Your modifications, or
   for any such Derivative Works as a whole, provided Your use,
   reproduction, and distribution of the Work otherwise complies with
   the conditions stated in this License.

5. Submission of Contributions. Unless You explicitly state otherwise,
   any Contribution intentionally submitted for inclusion in the Work
   by You to the Licensor shall be under the terms and conditions of
   this License, without any additional terms or conditions.
   Notwithstanding the above, nothing herein shall supersede or modify
   the terms of any separate license agreement you may have executed
   with Licensor regarding such Contributions.

6. Trademarks. This License does not grant permission to use the trade
   names, trademarks, service marks, or product names of the Licensor,
   except as required for reasonable and customary use in describing the
   origin of the Work and reproducing the content of the NOTICE file.

7. Disclaimer of Warranty. Unless required by applicable law or
   agreed to in writing, Licensor provides the Work (and each
   Contributor provides its Contributions) on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
   implied, including, without limitation, any warranties or conditions
   of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
   PARTICULAR PURPOSE. You are solely responsible for determining the
   appropriateness of using or redistributing the Work and assume any
   risks associated with Your exercise of permissions under this License.

8. Limitation of Liability. In no event and under no legal theory,
   whether in tort (including negligence), contract, or otherwise,
   unless required by applicable law (such as deliberate and grossly
   negligent acts) or agreed to in writing, shall any Contributor be
   liable to You for damages, including any direct, indirect, special,
   incidental, or consequential damages of any character arising as a
   result of this License or out of the use or inability to use the
   Work (including but not limited to damages for loss of goodwill,
   work stoppage, computer failure or malfunction, or any and all
   other commercial damages or losses), even if such Contributor
   has been advised of the possibility of such damages.

9. Accepting Warranty or Additional Liability. While redistributing
   the Work or Derivative Works thereof, You may choose to offer,
   and charge a fee for, acceptance of support, warranty, indemnity,
   or other liability obligations and/or rights consistent with this
   License. However, in accepting such obligations, You may act only
   on Your own behalf and on Your sole responsibility, not on behalf
   of any other Contributor, and only if You agree to indemnify,
   defend, and hold each Contributor harmless for any liability
   incurred by, or claims asserted against, such Contributor by reason
   of your accepting any such warranty or additional liability.

END OF TERMS AND CONDITIONS

APPENDIX: How to apply the Apache License to your work.

   To apply the Apache License to your work, attach the following
   boilerplate notice, with the fields enclosed by brackets "[]"
   replaced with your own identifying information. (Don't include
   the brackets!)  The text should be enclosed in the appropriate
   comment syntax for the file format. We also recommend that a
   file or class name and description of purpose be included on the
   same "printed page" as the copyright notice for easier
   identification within third-party archives.

Copyright [yyyy] [name of copyright owner]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
````

### Licence text 18

Used by: png 0.18.1.

````text
Copyright (c) 2015 nwin

Permission is hereby granted, free of charge, to any
person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the
Software without restriction, including without
limitation the rights to use, copy, modify, merge,
publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software
is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice
shall be included in all copies or substantial portions
of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF
ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT
SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
````

### Licence text 19

Used by: Adobe Glyph List, AGLFN and ZapfDingbats names 2.0 (glyphlist.txt).

````text
Copyright 2002-2019 Adobe (http://www.adobe.com/).

Redistribution and use in source and binary forms, with or
without modification, are permitted provided that the
following conditions are met:

Redistributions of source code must retain the above
copyright notice, this list of conditions and the following
disclaimer.

Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following
disclaimer in the documentation and/or other materials
provided with the distribution.

Neither the name of Adobe nor the names of its contributors
may be used to endorse or promote products derived from this
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
````
