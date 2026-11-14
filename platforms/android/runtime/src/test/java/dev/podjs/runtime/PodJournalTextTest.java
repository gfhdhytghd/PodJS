package dev.podjs.runtime;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class PodJournalTextTest {
 private Path root;
 @Before public void setup()throws Exception{root=Files.createTempDirectory("journal-reader-");}
 @After public void cleanup()throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(p);}}
 private PodDeviceServices service(){return new PodDeviceServices(root.toFile(),()->{throw new IOException("no key");});}
 private JSONObject path(String name)throws Exception{return new JSONObject().put("path",name);}
 @Test public void failedStrictReadClosesHandleBeforeClientCleanup()throws Exception{
  Files.write(root.resolve("invalid"),new byte[]{(byte)0xff});
  try(PodDeviceServices s=service()){
   for(int i=0;i<10;i++){
    String id=s.execute("file.openText",path("invalid").put("strictUtf8",true)).getString("id");JSONObject read=new JSONObject().put("id",id);
    assertThrows(IOException.class,()->s.execute("file.readTextChunk",read));
    assertThrows(IllegalArgumentException.class,()->s.execute("file.readTextChunk",read));
    assertFalse(s.execute("file.closeText",read).getBoolean("closed"));
   }
  }
 }
 @Test public void unicodePairsSurviveEverySmallReadBoundaryAndEofCloses()throws Exception{
  String text="汉😀\n".repeat(3000);Files.write(root.resolve("valid"),text.getBytes(StandardCharsets.UTF_8));
  try(PodDeviceServices s=service()){
   for(int max:new int[]{1,2,3,7,32768}){
    String id=s.execute("file.openText",path("valid")).getString("id");StringBuilder out=new StringBuilder();
    while(true){JSONObject part=s.execute("file.readTextChunk",new JSONObject().put("id",id).put("maxChars",max));String chunk=part.getString("text");assertTrue(chunk.length()<=max+1);if(!chunk.isEmpty())assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length()-1)));out.append(chunk);if(part.getBoolean("eof"))break;}
    assertEquals(text,out.toString());assertFalse(s.execute("file.closeText",new JSONObject().put("id",id)).getBoolean("closed"));
   }
  }
 }
 @Test public void readerLimitsCancellationShutdownAndSafePaths()throws Exception{
  Files.write(root.resolve("valid"),new byte[]{65});PodDeviceServices s=service();List<String> ids=new ArrayList<>();
  try{
   assertThrows(SecurityException.class,()->s.execute("file.openText",path("../outside")));
   for(int i=0;i<8;i++)ids.add(s.execute("file.openText",path("valid")).getString("id"));
   assertThrows(IllegalStateException.class,()->s.execute("file.openText",path("valid")));
   s.discardTextReader(new JSONObject().put("id",ids.get(0)));assertThrows(IllegalArgumentException.class,()->s.execute("file.readTextChunk",new JSONObject().put("id",ids.get(0))));
   ids.add(s.execute("file.openText",path("valid")).getString("id"));
  }finally{s.close();}
  assertThrows(IllegalStateException.class,()->s.execute("file.openText",path("valid")));
 }
}
