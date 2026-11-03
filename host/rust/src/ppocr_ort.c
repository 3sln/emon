/* A small ownership shim over the pinned ORT C API. Image/OCR policy is Rust's. */
#include <onnxruntime_c_api.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

typedef struct {
  const OrtApi *api;
  OrtEnv *env;
  OrtSession *session;
  OrtMemoryInfo *memory;
  OrtAllocator *allocator;
  char *input;
  char *output;
} EmonOrt;

static int fail(EmonOrt *p, OrtStatus *s, char *error, size_t cap) {
  if (!s) return 0;
  snprintf(error, cap, "%s", p->api->GetErrorMessage(s));
  p->api->ReleaseStatus(s);
  return 1;
}
void emon_ort_close(EmonOrt *p) {
  if (!p) return;
  if (p->input) p->allocator->Free(p->allocator, p->input);
  if (p->output) p->allocator->Free(p->allocator, p->output);
  if (p->memory) p->api->ReleaseMemoryInfo(p->memory);
  if (p->session) p->api->ReleaseSession(p->session);
  if (p->env) p->api->ReleaseEnv(p->env);
  free(p);
}
EmonOrt *emon_ort_open(const char *path, char *error, size_t cap) {
  if (strcmp(OrtGetApiBase()->GetVersionString(), "1.22.0") != 0) {
    snprintf(error, cap, "PP-OCR requires ONNX Runtime 1.22.0"); return NULL;
  }
  EmonOrt *p = calloc(1, sizeof(*p));
  if (!p) { snprintf(error, cap, "ORT allocation failed"); return NULL; }
  p->api = OrtGetApiBase()->GetApi(ORT_API_VERSION);
  OrtSessionOptions *options = NULL;
#define TRY(call) if (fail(p, (call), error, cap)) goto failed
  TRY(p->api->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "emon-ppocr", &p->env));
  TRY(p->api->CreateSessionOptions(&options));
  TRY(p->api->SetIntraOpNumThreads(options, 1));
  TRY(p->api->SetInterOpNumThreads(options, 1));
  TRY(p->api->SetSessionExecutionMode(options, ORT_SEQUENTIAL));
  TRY(p->api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL));
  /* No provider appended: the only execution provider is the CPU reference. */
  TRY(p->api->CreateSession(p->env, path, options, &p->session));
  p->api->ReleaseSessionOptions(options); options = NULL;
  TRY(p->api->GetAllocatorWithDefaultOptions(&p->allocator));
  TRY(p->api->SessionGetInputName(p->session, 0, p->allocator, &p->input));
  TRY(p->api->SessionGetOutputName(p->session, 0, p->allocator, &p->output));
  TRY(p->api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &p->memory));
  return p;
failed:
  if (options) p->api->ReleaseSessionOptions(options);
  emon_ort_close(p); return NULL;
#undef TRY
}

/* Output is malloc-owned, released with emon_ort_free. Shape capacity is eight. */
int emon_ort_run(EmonOrt *p, float *input, size_t length, const int64_t *shape,
                 size_t rank, float **output, size_t *count, int64_t *out_shape,
                 size_t *out_rank, char *error, size_t cap) {
  OrtValue *in = NULL, *out = NULL;
  OrtTensorTypeAndShapeInfo *info = NULL;
  int result = 1;
#define TRY(call) if (fail(p, (call), error, cap)) goto done
  TRY(p->api->CreateTensorWithDataAsOrtValue(p->memory, input, length * sizeof(float),
      shape, rank, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in));
  TRY(p->api->Run(p->session, NULL, (const char *const *)&p->input,
      (const OrtValue *const *)&in, 1, (const char *const *)&p->output, 1, &out));
  TRY(p->api->GetTensorTypeAndShape(out, &info));
  ONNXTensorElementDataType element_type;
  TRY(p->api->GetTensorElementType(info, &element_type));
  if (element_type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) { snprintf(error, cap, "ORT output must be float32"); goto done; }
  TRY(p->api->GetDimensionsCount(info, out_rank));
  if (*out_rank > 8) { snprintf(error, cap, "ORT output rank exceeds eight"); goto done; }
  TRY(p->api->GetDimensions(info, out_shape, *out_rank));
  TRY(p->api->GetTensorShapeElementCount(info, count));
  if (*count > SIZE_MAX / sizeof(float)) { snprintf(error, cap, "ORT output overflow"); goto done; }
  float *data = NULL;
  TRY(p->api->GetTensorMutableData(out, (void **)&data));
  *output = malloc(*count * sizeof(float));
  if (!*output) { snprintf(error, cap, "ORT output allocation failed"); goto done; }
  memcpy(*output, data, *count * sizeof(float)); result = 0;
done:
  if (info) p->api->ReleaseTensorTypeAndShapeInfo(info);
  if (out) p->api->ReleaseValue(out);
  if (in) p->api->ReleaseValue(in);
  return result;
#undef TRY
}
void emon_ort_free(float *p) { free(p); }
