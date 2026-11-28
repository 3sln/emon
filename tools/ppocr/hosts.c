/* The C probe of `bb ppocr-hosts`: the PP-OCR adapter library through the adapter ABI (emon_adapter.h), the way every host's registry loads it. */
#include "emon_adapter.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static int32_t number(FILE *f){ unsigned char b[4]; if(fread(b,1,4,f)!=4) abort();return (int32_t)((uint32_t)b[0]|(uint32_t)b[1]<<8|(uint32_t)b[2]<<16|(uint32_t)b[3]<<24); }
int main(void){
 char path[8192];const char *dir=getenv("EMON_PPOCR_MODELS");
 emon_adapter_status st;memset(&st,0,sizeof st);
 const emon_ocr_adapter *a=emon_ocr_adapter_v1(NULL,dir?dir:".cache/ppocr",&st);if(!a){fprintf(stderr,"%s: %s\n",st.reason,st.message);return 1;}
 while(fgets(path,sizeof(path),stdin)){
  path[strcspn(path,"\r\n")]=0;FILE *f=fopen(path,"rb");if(!f)abort();
  int32_t n=number(f),w=number(f),h=number(f),ch=number(f);
  if(n<=0||w<=0||h<=0||ch!=3)abort();
  int32_t *r=malloc((size_t)n*4);for(int k=0;k<n;k++)r[k]=number(f);
  size_t bytes=(size_t)w*(size_t)h*(size_t)ch;unsigned char *pixels=malloc(bytes);
  if(fread(pixels,1,bytes,f)!=bytes)abort();fclose(f);
  emon_adapter_image image={w,h,ch,w*ch,pixels,0};emon_adapter_answer out;memset(&out,0,sizeof out);
  if(a->read(a->ctx,r,(size_t)n,&image,0,&out,&st)!=EMON_ADAPTER_OK){fprintf(stderr,"%s: %s\n",st.reason,st.message);return 1;}
  putchar('[');for(size_t k=0;k<out.ncells;k++)printf("%s%d",k?",":"",out.cells[k]);puts("]");
  if(a->release)a->release(a->ctx,&out);free(pixels);free(r);
 }
 a->close(a->ctx);return 0;
}
