package dev.podjs.runtime;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import org.junit.Test;
public class PodMediaPrefixSamplesTest {
 static byte[] ints(int... values){ByteBuffer b=ByteBuffer.allocate(values.length*4);for(int value:values)b.putInt(value);return b.array();}
 static byte[] box(String type,byte[]... parts)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);int size=8;for(byte[] p:parts)size+=p.length;out.writeInt(size);out.writeBytes(type);for(byte[] p:parts)out.write(p);return bytes.toByteArray();}
 static byte[] track(int[] sizes,int[] offsets)throws Exception {
  byte[] mdhd=box("mdhd",ints(0,0,0,1000,2000,0));
  byte[] stts=box("stts",ints(0,1,4,500)),stsc=box("stsc",ints(0,1,1,2,1));
  byte[] stsz=box("stsz",ints(0,0,sizes.length),ints(sizes));byte[] stco=box("stco",ints(0,offsets.length),ints(offsets));
  return box("trak",box("mdia",mdhd,box("minf",box("stbl",stts,stsc,stsz,stco))));
 }
 @Test public void variableSizesMapTimeThroughChunksToExactByteEnd()throws Exception {
  byte[] moov=box("moov",track(new int[]{100,100,100,100},new int[]{1000,2000}));
  assertEquals(2100,PodMediaPrefixSamples.prefix(moov,10000,1500));
  assertEquals(1200,PodMediaPrefixSamples.prefix(moov,10000,1000));
 }
 @Test public void bothAudioAndVideoMustCoverTheTimeWindow()throws Exception {
  byte[] moov=box("moov",track(new int[]{100,100,100,100},new int[]{1000,2000}),track(new int[]{20,20,20,20},new int[]{3000,4000}));
  assertEquals(4020,PodMediaPrefixSamples.prefix(moov,10000,1500));
 }
 @Test public void truncatedTablesAndOutOfFileSamplesRejectThePlan()throws Exception {
  assertEquals(-1,PodMediaPrefixSamples.prefix(box("moov",box("trak",new byte[1])),10000,1500));
  assertEquals(-1,PodMediaPrefixSamples.prefix(box("moov",track(new int[]{100,100,100,100},new int[]{1000,2000})),2050,1500));
 }
}
