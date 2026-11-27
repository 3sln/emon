import emon.*;
import emon.api.OcrEngines;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
public final class Hosts {
 public static void main(String[] args) throws Exception {
  String ext=System.getProperty("os.name").equals("Mac OS X")?"dylib":"so";
  try(var p=new OcrEngines();var input=new BufferedReader(new InputStreamReader(System.in))){
   p.load(System.getenv().getOrDefault("EMON_PPOCR_LIB",".cache/ppocr/libemon_ppocr_adapter."+ext),null,System.getenv().getOrDefault("EMON_PPOCR_MODELS",".cache/ppocr"));
   for(String path;(path=input.readLine())!=null;){
    ByteBuffer data=ByteBuffer.wrap(Files.readAllBytes(Path.of(path))).order(ByteOrder.LITTLE_ENDIAN);
    int n=data.getInt(),w=data.getInt(),h=data.getInt(),ch=data.getInt();int[] r=new int[n];for(int k=0;k<n;k++)r[k]=data.getInt();
    byte[] b=new byte[data.remaining()];data.get(b);
    var a=p.fulfil(new Planrun.Request(r),Map.of(1,new Port.Bitmap(w,h,ch,b)),false);
    System.out.println(Arrays.toString(a.rec).replace(" ", ""));
   }
  }
 }
}
