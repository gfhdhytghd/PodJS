package dev.podjs.runtime;
import android.content.Context;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class PodTextRasterServicesTest {
 private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
 private JSONObject args(String text)throws Exception{return new JSONObject().put("text",text).put("widthPx",512).put("heightPx",42).put("baselinePx",32).put("fontSizePx",28).put("colorArgb",0xffffffffL);}
 @Test public void colorEmojiAndComposedSequencesHaveBoundedTransparentTextures()throws Exception{
  for(String text:new String[]{"汉😀导入正文。","👩‍💻 🇨🇳 👍🏽","©️ 1️⃣ ❤️"}){
   JSONObject result=PodTextRasterServices.raster(context(),args(text),new CancellationSignal());File file=new File(context().getFilesDir(),"podjs/files/"+result.getString("path"));
   try{
    assertEquals(512,result.getInt("width"));assertEquals(64,result.getInt("height"));assertEquals(text.length()+1,result.getJSONArray("carets").length());
    byte[] pixels=Files.readAllBytes(file.toPath());assertEquals(512*64*4,pixels.length);int colored=0,transparent=0;
    for(int p=0;p<pixels.length;p+=4){int r=pixels[p]&255,g=pixels[p+1]&255,b=pixels[p+2]&255,a=pixels[p+3]&255;if(a==0)transparent++;if(a>128&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>50)colored++;}
    assertTrue("no color-font glyphs: "+text,colored>25);assertTrue(transparent>pixels.length/8);
    for(int y=42;y<64;y++)for(int x=0;x<512;x++)assertEquals("texture padding must be transparent",0,pixels[(y*512+x)*4+3]);
   }finally{PodTextRasterServices.discard(context(),result);assertFalse(file.exists());}
  }
 }
 @Test public void invalidBoundsAndCancellationCannotWriteTextures()throws Exception{
  for(JSONObject value:new JSONObject[]{args("a").put("widthPx",1025),args("a").put("heightPx",1025),args("a").put("baselinePx",43),args("a\nb"),args("a").put("italic","true")}){
   try{PodTextRasterServices.raster(context(),value,new CancellationSignal());fail("invalid input accepted");}catch(IllegalArgumentException expected){}
  }
  CancellationSignal cancelled=new CancellationSignal();cancelled.cancel();try{PodTextRasterServices.raster(context(),args("😀"),cancelled);fail("cancelled request completed");}catch(OperationCanceledException expected){}
 }
}
