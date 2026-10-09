package dev.podjs.debug;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.*;
import androidx.test.core.app.ApplicationProvider;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30,shadows=PodDebugExternalTest.DuplicateModel.class)
public class PodDebugExternalTest {
 // Robolectric's static dup copies into a different file and its pipe is a
 // regular file. Model independent ownership here; the real provider pipe is
 // additionally exercised by dumpsys on Android.
 public static File outputFile;public static ParcelFileDescriptor duplicate;
 @org.robolectric.annotation.Implements(ParcelFileDescriptor.class)
 public static class DuplicateModel extends org.robolectric.shadows.ShadowParcelFileDescriptor {
  @org.robolectric.annotation.Implementation protected static ParcelFileDescriptor dup(FileDescriptor fd)throws IOException {duplicate=ParcelFileDescriptor.open(outputFile,ParcelFileDescriptor.MODE_READ_WRITE);return duplicate;}
 }
 private Context context;private Consumer<String> sink;
 @Before public void setup(){context=ApplicationProvider.getApplicationContext();context.getApplicationInfo().flags|=ApplicationInfo.FLAG_DEBUGGABLE;}
 @After public void cleanup(){if(sink!=null)PodDebugExternal.close(sink);}
 private String dump(Consumer<JSONObject> responder)throws Exception {
  AtomicReference<JSONObject> request=new AtomicReference<>();
  sink=line->{try{JSONObject e=new JSONObject(line);request.set(e);if(responder!=null)new Handler(Looper.getMainLooper()).post(()->responder.accept(e));}catch(Exception e){throw new RuntimeException(e);}};
  PodDebugExternal.execute(context,"debug.external.subscribe",new JSONObject(),sink);
  PodDebugExternal.Provider provider=new PodDebugExternal.Provider();provider.attachInfo(context,new android.content.pm.ProviderInfo());
  outputFile=File.createTempFile("debug-dump",".txt",context.getCacheDir());
  ParcelFileDescriptor original=ParcelFileDescriptor.open(outputFile,ParcelFileDescriptor.MODE_READ_WRITE);
  try {
   long start=System.nanoTime();provider.dump(original.getFileDescriptor(),new PrintWriter(new StringWriter()),new String[]{"--clear"});
   assertTrue("main dump must return without waiting for JS",(System.nanoTime()-start)/1_000_000<300);
   original.close();assertFalse(original.getFileDescriptor().valid());
   org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
   for(Thread worker:Thread.getAllStackTraces().keySet())if(worker.getName().equals("PodDebugDump")){worker.join(2000);assertFalse("worker closes owned fd",worker.isAlive());}
   assertFalse("duplicate closed by worker",duplicate.getFileDescriptor().valid());
   String value=new String(java.nio.file.Files.readAllBytes(outputFile.toPath()),java.nio.charset.StandardCharsets.UTF_8);
   PodDebugExternal.execute(context,"debug.external.reply",new JSONObject().put("id",request.get().getInt("id")).put("data","late"),sink);
   assertEquals(value,new String(java.nio.file.Files.readAllBytes(outputFile.toPath()),java.nio.charset.StandardCharsets.UTF_8));return value;
  }finally{original.close();outputFile.delete();}

 }
 @Test public void replyOnMainAfterReturnWritesThroughDuplicateAndClosesIt()throws Exception {
  assertEquals("DebugLogBuffer cleared.\n",dump(e->{try{PodDebugExternal.execute(context,"debug.external.reply",new JSONObject().put("id",e.getInt("id")).put("data","DebugLogBuffer cleared."),sink);}catch(Exception x){throw new RuntimeException(x);}}));
 }
 @Test public void timeoutWritesExactlyOneResultAndEof()throws Exception {assertEquals("debug_guest_timeout\n",dump(null));}
 @Test public void unsubscribeCompletesOnceWithoutWaitingForTimeout()throws Exception {assertEquals("debug_guest_closed\n",dump(e->PodDebugExternal.close(sink)));}
}
