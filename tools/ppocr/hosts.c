#include "emon_ppocr.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static int32_t number(FILE *f){ unsigned char b[4]; if(fread(b,1,4,f)!=4) abort();return (int32_t)((uint32_t)b[0]|(uint32_t)b[1]<<8|(uint32_t)b[2]<<16|(uint32_t)b[3]<<24); }
int main(void){
 char error[2048],path[8192];const char *dir=getenv("EMON_PPOCR_MODELS");
 void *p=emon_ppocr_open_v1(dir?dir:".cache/ppocr",error,sizeof(error));if(!p){fprintf(stderr,"%s\n",error);return 1;}
 while(fgets(path,sizeof(path),stdin)){
  path[strcspn(path,"\r\n")]=0;FILE *f=fopen(path,"rb");if(!f)abort();
  int32_t n=number(f),w=number(f),h=number(f),ch=number(f);
  if(n<=0||w<=0||h<=0||ch!=3)abort();
  int32_t *r=malloc((size_t)n*4);for(int k=0;k<n;k++)r[k]=number(f);
  size_t bytes=(size_t)w*(size_t)h*(size_t)ch;unsigned char *pixels=malloc(bytes);
  if(fread(pixels,1,bytes,f)!=bytes)abort();fclose(f);
  int32_t *out=NULL;size_t count=0;
  if(emon_ppocr_read_v1(p,r,(size_t)n,pixels,bytes,w,h,ch,&out,&count,error,sizeof(error))){fprintf(stderr,"%s\n",error);return 1;}
  putchar('[');for(size_t k=0;k<count;k++)printf("%s%d",k?",":"",out[k]);puts("]");
  emon_ppocr_free_v1(out,count);free(pixels);free(r);
 }
 emon_ppocr_close_v1(p);return 0;
}
