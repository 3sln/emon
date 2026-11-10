/* ABI ownership/error contracts; the live probes check successful answers. */
#include "emon_ppocr.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
int main(void) {
  char error[2048] = {0};
  void *missing = emon_ppocr_open_v1("/nonexistent/op2a-models", error, sizeof(error));
  assert(!missing);
  assert(strncmp(error, "ppocr-models-missing", strlen("ppocr-models-missing")) == 0);
  emon_ppocr_close_v1(NULL);
  emon_ppocr_free_v1(NULL, 0);
  void *port = emon_ppocr_open_v1(".cache/ppocr", error, sizeof(error));
  assert(port);
  int32_t request[10] = {10, 3, 2, 1, 3, 0, 0, 0, 0, 0};
  unsigned char pixel[3] = {255, 255, 255};
  int32_t *out = (int32_t *)1;
  size_t n = 99;
  assert(emon_ppocr_read_v1(NULL, request, 10, pixel, 3, 1, 1, 3, &out, &n, error, sizeof(error)) != 0);
  assert(out == NULL && n == 0 && strcmp(error, "ppocr-bad-request") == 0);
  assert(emon_ppocr_read_v1(port, request, 10, pixel, 3, 1, 1, 3, &out, &n, error, sizeof(error)) != 0);
  assert(out == NULL && n == 0 && strcmp(error, "ppocr-bad-request") == 0);
  char short_error[2] = {1, 1};
  assert(emon_ppocr_read_v1(port, request, 10, pixel, 3, 0, 1, 3, &out, &n, short_error, sizeof(short_error)) != 0);
  assert(short_error[1] == 0 && out == NULL && n == 0);
  n = 99;
  error[0] = 0;
  assert(emon_ppocr_read_v1(port, request, 10, pixel, 3, 1, 1, 3, NULL, &n, error, sizeof(error)) != 0);
  assert(n == 0 && strcmp(error, "ppocr-bad-request") == 0);
  out = (int32_t *)1;
  error[0] = 0;
  assert(emon_ppocr_read_v1(port, request, 10, pixel, 3, 1, 1, 3, &out, NULL, error, sizeof(error)) != 0);
  assert(out == NULL && strcmp(error, "ppocr-bad-request") == 0);
  emon_ppocr_close_v1(port);
  puts("PP-OCR ABI missing-model, malformed-input, output-initialization, bounded-error and null-free contracts pass");
  return 0;
}
