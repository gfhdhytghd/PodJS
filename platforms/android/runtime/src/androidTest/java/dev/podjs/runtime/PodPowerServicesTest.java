package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.PowerManager;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PodPowerServicesTest {
 private interface Action {void run()throws Exception;}
 private static void main(Instrumentation i,Action action){AtomicReference<Throwable> error=new AtomicReference<>();i.runOnMainSync(()->{try{action.run();}catch(Throwable e){error.set(e);}});if(error.get()!=null)throw new AssertionError(error.get());}
 private static boolean flag(VideoTestActivity a){return (a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)!=0;}
 private static JSONObject lease(String owner,boolean active)throws Exception{return new JSONObject().put("owner",owner).put("active",active);}
 @Test public void displayOwnersPauseResumeAndInheritedFlagArePreserved()throws Exception{
  Instrumentation i=InstrumentationRegistry.getInstrumentation();Intent intent=new Intent(i.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);VideoTestActivity a=(VideoTestActivity)i.startActivitySync(intent);AtomicReference<PodScreenServices> service=new AtomicReference<>();AtomicInteger events=new AtomicInteger();
  try{main(i,()->{
   a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);PodScreenServices s=new PodScreenServices(a,true,value->events.incrementAndGet());service.set(s);
   assertEquals(a.getSystemService(PowerManager.class).isInteractive(),s.state().getBoolean("interactive"));assertFalse(flag(a));
   s.execute("display.keepAwake",lease("a",true));s.execute("display.keepAwake",lease("a",true));s.execute("display.keepAwake",lease("b",true));assertEquals(2,s.state().getJSONArray("owners").length());assertTrue(flag(a));
   s.execute("display.keepAwake",lease("a",false));assertTrue(flag(a));s.lifecycle(false);assertFalse(flag(a));assertFalse(s.state().getBoolean("applied"));s.lifecycle(true);assertTrue(flag(a));
   s.execute("display.keepAwake",lease("b",false));assertFalse(flag(a));
   a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);s.execute("display.keepAwake",lease("a",true));s.execute("display.keepAwake",lease("a",false));assertTrue(flag(a));s.close();assertTrue(flag(a));
   a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);s=new PodScreenServices(a,true,value->{});service.set(s);s.execute("display.keepAwake",lease("a",true));s.close();assertFalse(flag(a));s.close();assertTrue(events.get()>=7);
  });}finally{main(i,()->{if(service.get()!=null)service.get().close();a.finish();});}
 }
 @Test public void silenceDefaultsOffHonorsOwnersAndStopsOnDisableAndClose()throws Exception{
  Instrumentation i=InstrumentationRegistry.getInstrumentation();main(i,()->{PodSilenceServices s=new PodSilenceServices();try{
   assertFalse(s.state().getBoolean("enabled"));assertFalse(s.state().getBoolean("playing"));
   s.execute("audio.silenceLease",lease("rfcomm",true));assertFalse(s.state().getBoolean("playing"));s.execute("audio.silenceEnabled",new JSONObject().put("enabled",true));assertTrue(s.state().getBoolean("playing"));
   s.execute("audio.silenceLease",lease("ip",true));s.execute("audio.silenceLease",lease("rfcomm",false));assertTrue(s.state().getBoolean("playing"));assertEquals(1,s.state().getJSONArray("owners").length());
   s.execute("audio.silenceEnabled",new JSONObject().put("enabled",false));assertFalse(s.state().getBoolean("playing"));s.execute("audio.silenceEnabled",new JSONObject().put("enabled",true));assertTrue(s.state().getBoolean("playing"));
   s.execute("audio.silenceLease",lease("ip",false));assertFalse(s.state().getBoolean("playing"));s.execute("audio.silenceLease",lease("last",true));assertTrue(s.state().getBoolean("playing"));s.close();assertFalse(s.state().getBoolean("playing"));assertEquals(0,s.state().getJSONArray("owners").length());
  }finally{s.close();}});
 }
}
