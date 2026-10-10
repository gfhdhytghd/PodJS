package dev.podjs.debug;
import android.content.Context;
import android.content.Intent;
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

 public static class Main extends android.app.Activity {}
 public static class Entry extends PodDebugExternal.ResultActivity {
  protected String mainActivity(){return Main.class.getName();}
 }
 private org.robolectric.android.controller.ActivityController<Entry> entry(int timeout)throws Exception {
  android.content.pm.ActivityInfo info=new android.content.pm.ActivityInfo();info.packageName=context.getPackageName();info.name=Entry.class.getName();info.metaData=new Bundle();info.metaData.putString("pod.debug.action","example.RESULT");info.metaData.putBoolean("pod.debug.result",true);info.metaData.putInt("pod.debug.timeout",timeout);
  org.robolectric.Shadows.shadowOf(context.getPackageManager()).addOrUpdateActivity(info);
  return org.robolectric.Robolectric.buildActivity(Entry.class,new Intent(context,Entry.class).putExtra("return_remote_url",true)).create();
 }
 private JSONObject subscribe()throws Exception {
  JSONObject event=new JSONObject();sink=line->{try{JSONObject value=new JSONObject(line);for(java.util.Iterator<String> keys=value.keys();keys.hasNext();){String key=keys.next();event.put(key,value.get(key));}}catch(Exception e){throw new RuntimeException(e);}};
  PodDebugExternal.execute(context,"debug.external.subscribe",new JSONObject(),sink);return event;
 }
 @Test public void activityResultWaitsForColdGuestAndPreservesTypedExtrasExactlyOnce()throws Exception {
  org.robolectric.android.controller.ActivityController<Entry> controller=entry(120000);Entry activity=controller.get();assertFalse(activity.isFinishing());
  Intent main=org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(main);assertTrue((main.getFlags()&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)!=0);assertEquals(0,main.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_TOP);
  JSONObject event=subscribe();assertTrue(event.getJSONObject("extras").getBoolean("return_remote_url"));assertTrue(event.getLong("deadline")-System.currentTimeMillis()>100000);
  JSONObject extras=new JSONObject().put("remote_url","https://example.com/rss").put("flag",true).put("count",7).put("ratio",1.5);
  JSONObject reply=new JSONObject().put("id",event.getInt("id")).put("result",new JSONObject().put("code",-1).put("extras",extras));
  PodDebugExternal.execute(context,"debug.external.reply",reply,sink);org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
  assertTrue(activity.isFinishing());assertEquals(-1,org.robolectric.Shadows.shadowOf(activity).getResultCode());Intent data=org.robolectric.Shadows.shadowOf(activity).getResultIntent();assertEquals("https://example.com/rss",data.getStringExtra("remote_url"));assertTrue(data.getBooleanExtra("flag",false));assertEquals(7,data.getIntExtra("count",0));assertEquals(1.5,data.getDoubleExtra("ratio",0),0);
  PodDebugExternal.execute(context,"debug.external.reply",reply.put("result",new JSONObject().put("code",0)),sink);org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(-1,org.robolectric.Shadows.shadowOf(activity).getResultCode());controller.destroy();
 }
 @Test public void resultTimeoutAndOwnerCloseCancelWithoutUrl()throws Exception {
  org.robolectric.android.controller.ActivityController<Entry> controller=entry(4000);JSONObject event=subscribe();org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(4001));assertTrue(controller.get().isFinishing());assertEquals(0,org.robolectric.Shadows.shadowOf(controller.get()).getResultCode());assertNull(org.robolectric.Shadows.shadowOf(controller.get()).getResultIntent());controller.destroy();
  controller=entry(120000);PodDebugExternal.close(sink);org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(controller.get().isFinishing());assertEquals(0,org.robolectric.Shadows.shadowOf(controller.get()).getResultCode());controller.destroy();
 }
 @Test public void destroyedOwnerInvalidResultsAndReleaseNeverReturnSuccess()throws Exception {
  JSONObject event=subscribe();org.robolectric.android.controller.ActivityController<Entry> controller=entry(120000);int id=event.getInt("id");controller.destroy();PodDebugExternal.execute(context,"debug.external.reply",new JSONObject().put("id",id).put("result",new JSONObject().put("code",-1)),sink);org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,org.robolectric.Shadows.shadowOf(controller.get()).getResultCode());
  controller=entry(120000);JSONObject nested=new JSONObject().put("secret",new JSONObject());PodDebugExternal.execute(context,"debug.external.reply",new JSONObject().put("id",event.getInt("id")).put("result",new JSONObject().put("code",-1).put("extras",nested)),sink);org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,org.robolectric.Shadows.shadowOf(controller.get()).getResultCode());assertNull(org.robolectric.Shadows.shadowOf(controller.get()).getResultIntent());controller.destroy();
  while(org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity()!=null){}
  context.getApplicationInfo().flags&=~ApplicationInfo.FLAG_DEBUGGABLE;controller=entry(120000);assertTrue(controller.get().isFinishing());assertNull(org.robolectric.Shadows.shadowOf(controller.get()).getNextStartedActivity());controller.destroy();
 }
}
