package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.*;
import java.util.concurrent.*;

/** Local completed MP4 with an AAC track; player readback, not audible-output proof. */
public final class PodVideoRingerDeviceTest {
 Instrumentation i; PodVideoServices legacy; PodVideoSessionServices sessions; boolean pooled; String active="active";
 JSONObject call(String operation,JSONObject args)throws Exception{
  CountDownLatch done=new CountDownLatch(1);JSONObject[] value={null};String[] error={null};
  if(pooled){if(!operation.equals("prepare"))args.put("sessionId",active);sessions.dispatch("video.session."+operation,args,new PodVideoSessionServices.Callback(){public void complete(JSONObject v){value[0]=v;done.countDown();}public void fail(String c,String m){error[0]=c;done.countDown();}});}
  else legacy.dispatch("video."+operation,args,new PodVideoServices.Callback(){public void complete(JSONObject v){value[0]=v;done.countDown();}public void fail(String c,String m){error[0]=c;done.countDown();}});
  assertTrue(operation+" timeout",done.await(10,TimeUnit.SECONDS));assertNull(error[0],error[0]);return value[0];
 }
 JSONObject options(String name)throws Exception{return new JSONObject().put("sessionId",name).put("url","ringer-complete.mp4").put("respectSilentMode",true).put("autoplay",true).put("loop",true).put("bounds",new JSONObject().put("x",0).put("y",0).put("width",100).put("height",100));}
 void shell(String command)throws Exception{try(ParcelFileDescriptor p=i.getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(p)){byte[] b=new byte[512];while(in.read(b)!=-1){}}}
 void gain(int expected,String mode)throws Exception{
  long end=SystemClock.uptimeMillis()+5000;JSONObject state=null;
  do{state=call("state",new JSONObject());if(state.optBoolean("hasRenderedFirstFrame")&&state.optBoolean("audioTrackSelected")&&state.optDouble("appliedVolume",-1)==expected&&state.optString("ringerMode").equals(mode))break;Thread.sleep(10);}while(SystemClock.uptimeMillis()<end);
  assertNotNull(state);assertTrue(state.toString(),state.getBoolean("hasRenderedFirstFrame"));assertTrue(state.toString(),state.getBoolean("audioTrackSelected"));assertEquals(mode,state.getString("ringerMode"));assertEquals(expected,state.getDouble("appliedVolume"),0);assertEquals(expected==0,state.getBoolean("silenced"));
  JSONObject fields=new JSONObject().put("state","present").put("ringer_mode",state.getString("ringerMode")).put("silenced",state.getBoolean("silenced")).put("system_index",state.getInt("volumeIndex")).put("system_max",state.getInt("volumeMax")).put("system_muted",state.getBoolean("muted")).put("audio_gain_milli",expected*1000).put("audio_track",state.getBoolean("audioTrackSelected")).put("has_frame",true);
  long[] receipt={0};i.runOnMainSync(()->receipt[0]=PodBenchmark.begin("player_visible",fields));assertTrue(receipt[0]>0);
 }
 @Test public void coldAndLiveModesCrownAndPromotion()throws Exception{
  i=InstrumentationRegistry.getInstrumentation();Context target=i.getTargetContext();File root=PodTakeover.filesRoot(target);root.mkdirs();File source=new File(root,"ringer-complete.mp4");
  try(InputStream in=i.getContext().getAssets().open("tiny.mp4");FileOutputStream out=new FileOutputStream(source)){byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);}
  VideoTestActivity activity=(VideoTestActivity)i.startActivitySync(new Intent(i.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{
   for(boolean pool:new boolean[]{false,true}){pooled=pool;active="active";legacy=new PodVideoServices(target);sessions=new PodVideoSessionServices(target,new PodVideoDecoderBudget(3));i.runOnMainSync(()->{if(pooled)sessions.attachToParent(activity.videoHost());else legacy.attachToParent(activity.videoHost());PodBenchmark.attach(activity.videoHost());});
    try{
     String start=InstrumentationRegistry.getArguments().getString("startMode","VIBRATE");shell("cmd audio set-ringer-mode "+start);shell("cmd audio set-volume 3 5");shell("cmd audio adj-unmute 3");
     call("prepare",options(active));gain(start.equals("NORMAL")?1:0,start.toLowerCase(java.util.Locale.ROOT));
     int originalIndex=call("state",new JSONObject()).getInt("volumeIndex");
     for(String mode:new String[]{"NORMAL","VIBRATE","NORMAL","SILENT","NORMAL","VIBRATE"}){long at=SystemClock.uptimeMillis();shell("cmd audio set-ringer-mode "+mode);gain(mode.equals("NORMAL")?1:0,mode.toLowerCase(java.util.Locale.ROOT));assertEquals(originalIndex,call("state",new JSONObject()).getInt("volumeIndex"));long latency=SystemClock.uptimeMillis()-at;android.util.Log.i("DYSILENT","backend="+(pool?"session":"legacy")+" mode="+mode+" latency_ms="+latency);assertTrue("ringer reaction "+latency,latency<=300);}
     call("setVolume",new JSONObject().put("volume",.5).put("crownDirection",1));gain(1,"vibrate");
     if(pooled){JSONObject standby=options("standby").put("prewarm",true).put("autoplay",false);call("prepare",standby);JSONObject promote=new JSONObject().put("sessionId","standby").put("replaceSessionId",active);active="standby";call("promote",promote);gain(1,"vibrate");}
     call("setVolume",new JSONObject().put("volume",0).put("crownDirection",-1));gain(0,"vibrate");
     shell("cmd audio set-volume 3 5");shell("cmd audio adj-unmute 3");gain(0,"vibrate");
    }finally{legacy.close();sessions.close();i.waitForIdleSync();}
   }
  }finally{shell("cmd audio set-ringer-mode NORMAL");activity.finish();source.delete();}
 }
}
