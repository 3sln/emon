using System;
using System.IO;
using System.Collections.Generic;
public static class Hosts {
 public static void Main(){
  using var p=new emon.Api.OcrEngines();
  string ext=OperatingSystem.IsMacOS()?"dylib":OperatingSystem.IsWindows()?"dll":"so";
  p.Load(Environment.GetEnvironmentVariable("EMON_PPOCR_LIB")??".cache/ppocr/libemon_ppocr_adapter."+ext,null,Environment.GetEnvironmentVariable("EMON_PPOCR_MODELS")??".cache/ppocr");
  string path;
  while((path=Console.ReadLine())!=null){
   using var b=new BinaryReader(File.OpenRead(path));int n=b.ReadInt32(),w=b.ReadInt32(),h=b.ReadInt32(),ch=b.ReadInt32();
   int[] r=new int[n];for(int k=0;k<n;k++)r[k]=b.ReadInt32();
   var a=p.Fulfil(new emon.Planrun.Request(r),new Dictionary<int,emon.Port.Bitmap>{{1,new emon.Port.Bitmap(w,h,ch,b.ReadBytes(checked(w*h*ch)))}},false);
   Console.WriteLine("["+string.Join(",",a.Rec)+"]");
  }
 }
}
