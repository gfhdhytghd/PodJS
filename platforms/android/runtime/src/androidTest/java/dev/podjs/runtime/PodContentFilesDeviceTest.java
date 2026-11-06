package dev.podjs.runtime;

import android.content.Context;
import android.util.Base64;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real Android fsync/reopen and condition observations; emulator only in P3A. */
public class PodContentFilesDeviceTest {
 @Test public void frameAndCheckpointReopenWithBatchedRead()throws Exception{
  Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();File root=Files.createTempDirectory(c.getCacheDir().toPath(),"content-device-").toFile();
  try{String path=String.join("",Collections.nCopies(64,"a"))+"/"+String.join("",Collections.nCopies(64,"b"))+".data";byte[] b=new byte[65536];for(int i=0;i<b.length;i++)b[i]=(byte)i;
   PodContentFiles host=new PodContentFiles(root);host.execute("content.files.frame",new JSONObject().put("path",path).put("offset",0).put("dataBase64",Base64.encodeToString(b,Base64.NO_WRAP)).put("checkpoint","saved"));host=new PodContentFiles(root);
   JSONObject stat=host.execute("content.files.stat",new JSONObject().put("paths",new JSONArray().put(path))).getJSONArray("entries").getJSONObject(0);assertEquals(65536,stat.getInt("durableOffset"));assertEquals("saved",stat.getString("checkpoint"));
   JSONObject read=host.execute("content.files.read",new JSONObject().put("ranges",new JSONArray().put(new JSONObject().put("path",path).put("offset",0).put("length",32768)).put(new JSONObject().put("path",path).put("offset",32768).put("length",32768))));assertEquals(2,read.getJSONArray("entries").length());assertArrayEquals(Arrays.copyOfRange(b,32768,65536),Base64.decode(read.getJSONArray("entries").getJSONObject(1).getString("dataBase64"),Base64.DEFAULT));
  }finally{try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList()))Files.deleteIfExists(p);}}
 }
 @Test public void conditionsNeverInventApplicationBluetoothSession()throws Exception{JSONObject s=PodContentConditions.snapshot(InstrumentationRegistry.getInstrumentation().getTargetContext());assertFalse(s.has("bluetooth"));assertFalse(s.has("localRoute"));assertTrue(s.has("charging"));assertTrue(s.has("wifi"));assertTrue(s.has("cellular"));assertTrue(s.has("internet"));}
}
