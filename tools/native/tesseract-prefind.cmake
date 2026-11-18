# Included right after project() of Tesseract (-DCMAKE_PROJECT_INCLUDE, P11.5, D540). Tesseract's configure compiles and RUNS a small program against
# the installed Leptonica (check_leptonica_tiff_support). A STATIC Leptonica's exported link interface names the codec targets ZLIB::ZLIB, JPEG::JPEG,
# WebP::webp and WebP::libwebpmux, which its LeptonicaConfig.cmake does not find itself; without them the test project fails to generate. These are the
# same codecs `bb native` installed in the prefix just before (the find paths are restricted to it), found here as targets and used for nothing else.
find_package(ZLIB REQUIRED)
find_package(JPEG REQUIRED)
find_package(WebP CONFIG REQUIRED)

# ANDROID (K6, D900): Tesseract 5.5.3's CMake build does `find_package(CpuFeaturesNdkCompat REQUIRED)` and links CpuFeatures::ndk_compat, a Google cpu_features
# library it never uses here: the only code that includes <cpu-features.h> sits behind HAVE_ANDROID_GETCPUFAMILY, which the CMake build never defines (armv7 NEON is
# detected with getauxval, HAVE_GETAUXVAL). No library is taken from anywhere: an EMPTY interface target of that name satisfies the call.
if(ANDROID)
  set(_stub "${CMAKE_BINARY_DIR}/cpufeatures-stub/lib/cmake/CpuFeaturesNdkCompat")
  file(MAKE_DIRECTORY "${_stub}")
  file(WRITE "${_stub}/CpuFeaturesNdkCompatConfig.cmake"
       "if(NOT TARGET CpuFeatures::ndk_compat)\n  add_library(CpuFeatures::ndk_compat INTERFACE IMPORTED)\nendif()\n")
  set(CpuFeaturesNdkCompat_DIR "${_stub}" CACHE PATH "empty stub, see tools/native/tesseract-prefind.cmake" FORCE)
endif()
