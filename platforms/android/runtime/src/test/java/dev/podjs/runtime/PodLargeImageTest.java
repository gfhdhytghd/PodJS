package dev.podjs.runtime;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.util.zip.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PodLargeImageTest {
 @Test public void fullDecodeKeepsOldLimitAndBoundedPathsHaveHardLimits()throws Exception {
  PodImageServices.checkSourceBounds(8200,11220,true);
  for(int[] size:new int[][]{{8200,11220},{16385,2}})try{PodImageServices.checkSourceBounds(size[0],size[1],false);fail("full source accepted");}catch(IOException expected){assertEquals("image_too_large",expected.getMessage());}
  for(int[] size:new int[][]{{65536,1},{20001,20000},{0,12}})try{PodImageServices.checkSourceBounds(size[0],size[1],true);fail("hard bound accepted");}catch(IOException expected){assertEquals("image_too_large",expected.getMessage());}
 }
 @Test public void ninetyTwoMegapixelSourceDecodesSampleAndNativeRegion()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();File root=PodTakeover.filesRoot(c);root.mkdirs();File png=new File(root,"large-source.png");writePng(png,8200,11220);
  try(PodImageServices images=new PodImageServices(c)){
   JSONObject overview=images.execute("image.decode",new JSONObject().put("path",png.getName()).put("maxDimension",512));assertEquals(8200,overview.getInt("sourceWidth"));assertEquals(11220,overview.getInt("sourceHeight"));assertTrue(overview.getInt("width")<=512);assertTrue(overview.getInt("height")<=512);images.discard(overview);
   JSONObject validation=images.execute("image.decode",new JSONObject().put("path",png.getName()).put("maxDimension",1));assertEquals(1,validation.getInt("width"));assertEquals(1,validation.getInt("height"));images.discard(validation);
   JSONObject tile=images.execute("image.decodeRegion",new JSONObject().put("path",png.getName()).put("x",1000).put("y",2000).put("width",512).put("height",512));assertEquals(512,tile.getInt("contentWidth"));assertEquals(512,tile.getInt("contentHeight"));images.discard(tile);
  }finally{png.delete();}
 }
 private static void chunk(DataOutputStream out,String name,byte[] data)throws IOException{byte[] type=name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);out.writeInt(data.length);out.write(type);out.write(data);CRC32 crc=new CRC32();crc.update(type);crc.update(data);out.writeInt((int)crc.getValue());}
 private static void writePng(File file,int width,int height)throws IOException {
  try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))){
   out.writeLong(0x89504e470d0a1a0aL);ByteArrayOutputStream header=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(header);h.writeInt(width);h.writeInt(height);h.write(new byte[]{8,0,0,0,0});chunk(out,"IHDR",header.toByteArray());
   OutputStream chunks=new OutputStream(){public void write(int b)throws IOException{write(new byte[]{(byte)b});}public void write(byte[] b,int off,int len)throws IOException{chunk(out,"IDAT",java.util.Arrays.copyOfRange(b,off,off+len));}};
   Deflater def=new Deflater(1);try{DeflaterOutputStream stream=new DeflaterOutputStream(chunks,def,32768);byte[] row=new byte[width+1];for(int y=0;y<height;y++){java.util.Arrays.fill(row,(byte)(y%256));row[0]=0;stream.write(row);}stream.finish();}finally{def.end();}chunk(out,"IEND",new byte[0]);
  }
 }
}
