package dev.podjs.runtime;

import android.util.Base64;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33)
public class PodContentFilesTest {
 private File root;private PodContentFiles host;
 private final String path=String.join("",Collections.nCopies(64,"a"))+"/"+String.join("",Collections.nCopies(64,"b"))+".data";
 @Before public void setup()throws Exception{root=Files.createTempDirectory("pod-content-").toFile();host=new PodContentFiles(root);}
 @After public void cleanup()throws Exception{try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(p);}}
 private JSONObject frame(int offset,byte[] bytes)throws Exception{return new JSONObject().put("path",path).put("offset",offset).put("dataBase64",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("checkpoint","checkpoint-"+(offset+bytes.length));}
 private JSONObject stat()throws Exception{return host.execute("content.files.stat",new JSONObject().put("paths",new JSONArray().put(path))).getJSONArray("entries").getJSONObject(0);}
 @Test public void statMissingContentNeverCreatesProjectionOrParentDirectories()throws Exception{assertFalse(new File(root,"content-v2").exists());assertFalse(stat().getBoolean("exists"));assertFalse(new File(root,"content-v2").exists());}
 @Test public void readMissingContentNeverCreatesProjectionOrParentDirectories()throws Exception{assertThrows(IOException.class,()->host.execute("content.files.read",new JSONObject().put("ranges",new JSONArray().put(new JSONObject().put("path",path).put("offset",0).put("length",1)))));assertFalse(new File(root,"content-v2").exists());}
 @Test public void durableFrameAndCheckpointSurviveReopenAndLostReply()throws Exception{byte[] b=new byte[65536];Arrays.fill(b,(byte)7);host.execute("content.files.frame",frame(0,b));host=new PodContentFiles(root);assertEquals(65536,stat().getInt("durableOffset"));assertEquals("checkpoint-65536",stat().getString("checkpoint"));assertEquals(65536,host.execute("content.files.frame",frame(0,b)).getInt("durableOffset"));}
 @Test public void uncheckpointedTailReplaysAtMostOneFrame()throws Exception{byte[] b=new byte[65536];host.execute("content.files.frame",frame(0,b));try(FileOutputStream out=new FileOutputStream(new File(root,"content-v2/"+path),true)){out.write(new byte[65536]);out.getFD().sync();}host=new PodContentFiles(root);assertEquals(65536,stat().getInt("durableOffset"));host.execute("content.files.frame",frame(65536,new byte[]{1,2,3}));assertEquals(65539,stat().getInt("size"));assertEquals(65539,stat().getInt("durableOffset"));}
 @Test public void conflictsAndGapsPreservePrefix()throws Exception{host.execute("content.files.frame",frame(0,new byte[65536]));assertThrows(IOException.class,()->host.execute("content.files.frame",frame(131072,new byte[]{1})));assertThrows(IOException.class,()->host.execute("content.files.frame",frame(0,new byte[]{1})));assertEquals(65536,stat().getInt("durableOffset"));}
 @Test public void batchCountsAndBytesAreBounded()throws Exception{JSONArray paths=new JSONArray();for(int i=0;i<33;i++)paths.put(path);assertThrows(IllegalArgumentException.class,()->host.execute("content.files.stat",new JSONObject().put("paths",paths)));JSONArray ranges=new JSONArray().put(new JSONObject().put("path",path).put("offset",0).put("length",262145));assertThrows(IllegalArgumentException.class,()->host.execute("content.files.read",new JSONObject().put("ranges",ranges)));assertThrows(IllegalArgumentException.class,()->host.execute("content.files.frame",frame(0,new byte[65537])));}
 @Test public void immutablePublicationNeverReplacesOriginalProjection()throws Exception{host.execute("content.files.publish",frame(0,new byte[]{1,2}));assertThrows(IOException.class,()->host.execute("content.files.publish",frame(0,new byte[]{3})));assertArrayEquals(new byte[]{1,2},Files.readAllBytes(new File(root,"content-v2/"+path).toPath()));}
 @Test public void rejectedPrefixHasDurableZeroBeforeCorrectRetry()throws Exception{byte[] b=new byte[65536];Arrays.fill(b,(byte)7);host.execute("content.files.frame",frame(0,b));File source=new File(root,"original.bin");Files.write(source.toPath(),new byte[]{42});String other=path.replace(".data",".verified");host.execute("content.files.publish",new JSONObject().put("path",other).put("dataBase64",Base64.encodeToString(new byte[]{9},Base64.NO_WRAP)));
  host.execute("content.files.discard",new JSONObject().put("path",path));host=new PodContentFiles(root);assertEquals(0,stat().getInt("durableOffset"));assertEquals(0,stat().getInt("size"));host.execute("content.files.discard",new JSONObject().put("path",path));Arrays.fill(b,(byte)8);host.execute("content.files.frame",frame(0,b));assertArrayEquals(b,Files.readAllBytes(new File(root,"content-v2/"+path).toPath()));assertArrayEquals(new byte[]{42},Files.readAllBytes(source.toPath()));assertArrayEquals(new byte[]{9},Files.readAllBytes(new File(root,"content-v2/"+other).toPath()));}
 @Test public void pathsCannotEscapeProjection()throws Exception{for(String p:new String[]{"../original","/tmp/out",path+"/../../old",path+".."})assertThrows(IllegalArgumentException.class,()->host.execute("content.files.stat",new JSONObject().put("paths",new JSONArray().put(p))));}
}
