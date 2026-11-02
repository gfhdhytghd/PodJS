package dev.podjs.runtime;

import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @LooperMode(LooperMode.Mode.PAUSED)
public class PodCloudDelayTest {
 private static void awaitReplies(List<String> replies,int count)throws Exception {
  for(int n=0;n<200&&replies.size()<count;n++){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1);}
  assertEquals(count,replies.size());
 }
 private static void delay(PodServices services,int id,long ms)throws Exception {
  assertTrue(PodCloudWorker.METHODS.contains("runtime.delay"));
  services.dispatch(new JSONObject().put("t","service.request").put("version",1).put("id",id).put("method","runtime.delay").put("args",new JSONObject().put("milliseconds",ms)));
 }
 @Test public void approvedDelayUsesRealServiceAndRepliesAfterTheTimer()throws Exception {
  List<String> replies=new ArrayList<>();
  try(PodServices services=new PodServices(ApplicationProvider.getApplicationContext(),replies::add)){
   delay(services,1,1);assertTrue(replies.isEmpty());shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.MILLISECONDS);
   awaitReplies(replies,1);JSONObject reply=new JSONObject(replies.get(0));assertTrue(reply.getBoolean("ok"));assertEquals(1,reply.getInt("id"));
  }
  for(String denied:new String[]{"runtime.afterPaint","ui.draw","debug.exportCredentials"})assertFalse(PodCloudWorker.METHODS.contains(denied));
 }
 @Test public void cancelledAndClosedDelayCannotPublishLateSuccess()throws Exception {
  List<String> replies=new ArrayList<>();
  PodServices services=new PodServices(ApplicationProvider.getApplicationContext(),replies::add);
  boolean closed=false;try{
   delay(services,1,1);services.dispatch(new JSONObject().put("t","service.cancel").put("id",1));shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.MILLISECONDS);assertTrue(replies.isEmpty());
   delay(services,2,1);services.close();closed=true;shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.MILLISECONDS);assertTrue(replies.isEmpty());
  }finally{if(!closed)services.close();}
 }
 @Test public void boundedDelayArgumentsAndFailureOutcomeRemainEnforced()throws Exception {
  List<String> replies=new ArrayList<>();
  try(PodServices services=new PodServices(ApplicationProvider.getApplicationContext(),replies::add)){
   delay(services,1,0);delay(services,2,120001);awaitReplies(replies,2);
   for(String line:replies){JSONObject reply=new JSONObject(line);assertFalse(reply.getBoolean("ok"));assertEquals("invalid_argument",reply.getString("code"));}
  }
  for(int attempt=0;attempt<5;attempt++)assertEquals(androidx.work.ListenableWorker.Result.failure().getClass(),PodCloudWorker.resultFor(new JSONObject().put("code","completed").put("status","failure"),attempt).getClass());
 }
}
