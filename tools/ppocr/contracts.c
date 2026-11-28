/* The PP-OCR adapter library's ABI contracts (emon_adapter.h): a missing model directory, malformed input and a bounded status; the live probes check successful answers. */
#include "emon_adapter.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
int main(void) {
  emon_adapter_status st;
  memset(&st, 0, sizeof st);
  const emon_ocr_adapter *missing = emon_ocr_adapter_v1(NULL, "/nonexistent/op2a-models", &st);
  assert(!missing);
  assert(strncmp(st.reason, "pack-missing", strlen("pack-missing")) == 0);
  assert(memchr(st.reason, 0, sizeof st.reason) && memchr(st.message, 0, sizeof st.message));
  const emon_ocr_adapter *a = emon_ocr_adapter_v1(NULL, ".cache/ppocr", &st);
  assert(a && a->abi == EMON_ADAPTER_ABI && a->read && a->close && !a->start);
  emon_adapter_info info;
  memset(&info, 0, sizeof info);
  assert(a->describe(a->ctx, &info, &st) == EMON_ADAPTER_OK && info.engine == EMON_OEN_PPOCR);
  /* a bitmap the adapter cannot read (two channels, or no pixels) is refused with a bounded reason, never answered; the adapter has no `supports` (the registry builds the request) */
  int32_t request[10] = {10, 3, 2, 1, 3, 0, 0, 0, 0, 0};
  unsigned char pixel[3] = {255, 255, 255};
  emon_adapter_image image = {1, 1, 2, 2, pixel, 0};
  emon_adapter_answer out;
  memset(&out, 0, sizeof out);
  assert(a->read(a->ctx, request, 10, &image, 0, &out, &st) != EMON_ADAPTER_OK);
  assert(out.cells == NULL && out.ncells == 0);
  assert(strcmp(st.reason, "bad-image") == 0);
  assert(memchr(st.reason, 0, sizeof st.reason) && memchr(st.message, 0, sizeof st.message));
  image.channels = 3;
  image.pixels = NULL;
  memset(&out, 0, sizeof out);
  assert(a->read(a->ctx, request, 10, &image, 0, &out, &st) != EMON_ADAPTER_OK && out.cells == NULL);
  a->close(a->ctx);
  puts("PP-OCR adapter ABI missing-model, bad-image and bounded-status contracts pass");
  return 0;
}
