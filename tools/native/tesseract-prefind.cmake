# Included right after project() of Tesseract (-DCMAKE_PROJECT_INCLUDE, P11.5, D1460). Tesseract's configure compiles and RUNS a small program against
# the installed Leptonica (check_leptonica_tiff_support). A STATIC Leptonica's exported link interface names the codec targets ZLIB::ZLIB, JPEG::JPEG,
# WebP::webp and WebP::libwebpmux, which its LeptonicaConfig.cmake does not find itself; without them the test project fails to generate. These are the
# same codecs `bb native` installed in the prefix just before (the find paths are restricted to it), found here as targets and used for nothing else.
find_package(ZLIB REQUIRED)
find_package(JPEG REQUIRED)
find_package(WebP CONFIG REQUIRED)
