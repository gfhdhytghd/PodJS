package dev.podjs.runtime;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Local-media device gate: repeated re-entry must render and return decoder leases. */
public final class PodVideoOptimizationTest {
 @Test public void repeatedOpenCloseRendersNewGenerationWithoutLeakingDecoders() throws Exception {
  Instrumentation instrument=InstrumentationRegistry.getInstrumentation();Context context=instrument.getTargetContext();
  File root=PodTakeover.filesRoot(context);assertTrue(root.mkdirs()||root.isDirectory());File fixture=new File(root,"video-opt-tiny.mp4");
  try(InputStream in=instrument.getContext().getAssets().open("tiny.mp4");FileOutputStream out=new FileOutputStream(fixture)){
   byte[] bytes=new byte[4096];for(int count;(count=in.read(bytes))!=-1;)out.write(bytes,0,count);
  }
  VideoTestActivity activity=(VideoTestActivity)instrument.startActivitySync(new Intent(instrument.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  PodVideoDecoderBudget budget=new PodVideoDecoderBudget(2);
  try {
   for(int iteration=0;iteration<12;iteration++){
    PodVideoServices service=new PodVideoServices(context,budget);
    instrument.runOnMainSync(()->service.attachToParent(activity.videoHost()));instrument.waitForIdleSync();
    try {
     call(service,"video.prepare",new JSONObject().put("url",fixture.getName()).put("autoplay",true).put("muted",true).put("loop",true).put("bounds",new JSONObject().put("width",64).put("height",64).put("visible",true)));
     JSONObject state=null;long until=android.os.SystemClock.elapsedRealtime()+8000;
     do {state=call(service,"video.state",new JSONObject());if(state.optBoolean("hasRenderedFirstFrame"))break;Thread.sleep(50);}while(android.os.SystemClock.elapsedRealtime()<until);
     assertTrue("iteration="+iteration,state.optBoolean("hasRenderedFirstFrame"));
     assertTrue(state.has("metrics"));assertTrue(state.getJSONObject("metrics").has("firstFrameMs"));
     assertEquals(0,state.getJSONObject("metrics").getLong("bytesLoaded"));
    } finally {service.close();instrument.waitForIdleSync();}
    assertEquals("decoder lease after iteration="+iteration,0,budget.inUse());
   }
  } finally {activity.finish();instrument.waitForIdleSync();assertTrue(fixture.delete()||!fixture.exists());}
 }
 private JSONObject call(PodVideoServices service,String method,JSONObject args)throws Exception {
  CountDownLatch done=new CountDownLatch(1);JSONObject[] result={null};String[] failure={null};
  service.dispatch(method,args,new PodVideoServices.Callback(){public void complete(JSONObject value){result[0]=value;done.countDown();}public void fail(String code,String message){failure[0]=code;done.countDown();}});
  assertTrue("timeout "+method,done.await(10,TimeUnit.SECONDS));assertNull(failure[0]);return result[0];
 }
}
