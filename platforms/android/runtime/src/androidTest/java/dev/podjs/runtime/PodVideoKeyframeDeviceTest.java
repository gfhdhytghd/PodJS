package dev.podjs.runtime;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Media3/MediaCodec/HWUI on the assigned emulator, using slow-cdn.py.
 * No Douyin service, credentials or watch. Timings are emulator diagnostics. */
public final class PodVideoKeyframeDeviceTest {
    final Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
    final Context context=instrument.getTargetContext();
    final Handler main=new Handler(Looper.getMainLooper());
    final JSONArray samples=new JSONArray();
    final Map<Integer,String> sources=new HashMap<>();
    int commitWaitTimeouts;
    JSONObject bounds;
    PodVideoSessionServices service;
    PodMediaPrefixCache prefix;
    VideoTestActivity activity;
    File evidence;
    static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    Object session(String id)throws Exception {return ((Map<?,?>)field(PodVideoSessionServices.class,"sessions").get(service)).get(id);}
    Object member(String id,String name)throws Exception {Object slot=session(id);return field(slot.getClass(),name).get(slot);}
    String source(int index)throws Exception {
        if(!sources.containsKey(index))sources.put(index,prefix.register("dyframe-"+index,"http://127.0.0.1:18758/front/"+index+".mp4",new HashMap<>()));
        return sources.get(index);
    }
    JSONObject args(String id,int index,boolean warm)throws Exception {return new JSONObject().put("sessionId",id).put("url",source(index)).put("videoMime","video/avc").put("videoWidth",240).put("videoHeight",240).put("bounds",bounds).put("prewarm",warm).put("autoplay",false);}
    static class Reply implements PodVideoSessionServices.Callback {
        final CountDownLatch done=new CountDownLatch(1);JSONObject value;String error;
        public void complete(JSONObject v){value=v;done.countDown();}public void fail(String c,String m){error=c;done.countDown();}
    }
    JSONObject call(String method,JSONObject args)throws Exception {Reply reply=new Reply();service.dispatch(method,args,reply);assertTrue(method,reply.done.await(20,TimeUnit.SECONDS));assertNull(method+" "+reply.error,reply.error);return reply.value;}
    void release(String id)throws Exception {call("video.session.release",new JSONObject().put("sessionId",id));}
    void ready(String id)throws Exception {
        long deadline=SystemClock.uptimeMillis()+15000;boolean[] ready={false};
        do {instrument.runOnMainSync(()->{try{ready[0]=(boolean)member(id,"hasRenderedFirstFrame")&&(boolean)member(id,"hasUpdatedFrame")&&member(id,"keyframeView")==null;}catch(Exception ignored){}});if(ready[0])return;Thread.sleep(16);}while(SystemClock.uptimeMillis()<deadline);
        fail("decoded texture timeout "+id);
    }
    long visible(String id,long began)throws Exception {
        CountDownLatch done=new CountDownLatch(1);long[] at={-1};Throwable[] error={null};
        main.post(new Runnable(){final long deadline=SystemClock.uptimeMillis()+18000;public void run(){
            try{
                Object slot=session(id);if(slot!=null){
                    View view=(View)field(slot.getClass(),"keyframeView").get(slot);
                    if(view==null&&(boolean)field(slot.getClass(),"hasRenderedFirstFrame").get(slot)&&(boolean)field(slot.getClass(),"hasUpdatedFrame").get(slot))view=(View)field(slot.getClass(),"videoView").get(slot);
                    if(view!=null&&view.isShown()&&view.hasWindowFocus()&&view.getTranslationY()==0){
                        View target=view;
                        PodAfterPaint.await(target,main,1,(frame,time)->at[0]=time,committed->{
                            if(committed){done.countDown();return;}
                            commitWaitTimeouts++;
                            if(SystemClock.uptimeMillis()>deadline){error[0]=new AssertionError("window commit deadline");done.countDown();}
                            else main.postDelayed(this,8); // A timeout is never a visibility receipt.
                        });return;
                    }
                }
                if(SystemClock.uptimeMillis()>deadline){error[0]=new AssertionError("visible deadline");done.countDown();}else main.postDelayed(this,8);
            }catch(Throwable failure){error[0]=failure;done.countDown();}
        }});
        assertTrue(done.await(20,TimeUnit.SECONDS));if(error[0]!=null)throw new AssertionError(error[0]);return at[0]-began;
    }
    void sample(String kind,String id,int index)throws Exception {
        long start=SystemClock.uptimeMillis();
        if("cold".equals(kind)&&session("live")!=null)call("video.session.retire",new JSONObject().put("sessionId","live"));
        Reply reply=new Reply();service.dispatch("video.session.prepare",args(id,index,false),reply);
        long latency=visible(id,start);assertTrue(reply.done.await(20,TimeUnit.SECONDS));assertNull(reply.error);ready(id);
        samples.put(new JSONObject().put("kind",kind).put("visibleMs",latency).put("decoderInUse",call("video.session.capabilities",new JSONObject()).getInt("decoderInUse")));
    }
    JSONObject memory()throws Exception {Debug.MemoryInfo info=new Debug.MemoryInfo();Debug.getMemoryInfo(info);return new JSONObject().put("pssKb",info.getTotalPss()).put("nativePssKb",info.nativePss).put("graphicsKb",info.getMemoryStat("summary.graphics")).put("javaHeapKb",info.getMemoryStat("summary.java-heap"));}
    @Test public void neighbourhoodCacheSurvivesDecoderReleaseAndMatchesFrameZero()throws Exception {
        evidence=new File(context.getFilesDir(),"dyframe-evidence");assertTrue(evidence.mkdirs()||evidence.isDirectory());
        activity=(VideoTestActivity)instrument.startActivitySync(new Intent(instrument.getContext(),VideoTestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        float density=context.getResources().getDisplayMetrics().density;
        bounds=new JSONObject().put("x",0).put("y",0).put("width",466/density).put("height",466/density).put("visible",true);
        prefix=new PodMediaPrefixCache(new File(context.getCacheDir(),"dyframe-prefix-"+System.nanoTime()));
        PodVideoDecoderBudget budget=new PodVideoDecoderBudget(8);service=new PodVideoSessionServices(context,budget,prefix);
        instrument.runOnMainSync(()->service.attachToParent(activity.videoHost()));instrument.waitForIdleSync();
        JSONObject report=new JSONObject();
        try{
            report.put("before",memory());sample("cold","active",40);
            for(int index:new int[]{41,39,42,38,43,44,45}){
                JSONObject result=call("video.session.prepare",args("worker",index,true).put("keyframeOnly",true));
                assertTrue("must retain real decoded pixels",result.optBoolean("keyframeAvailable"));assertEquals("short-lived decoder returned",1,budget.inUse());
            }
            JSONObject cached=call("video.session.capabilities",new JSONObject());report.put("cached",cached).put("cachedMemory",memory());
            assertEquals(7,cached.getInt("keyframeEntries"));assertTrue(cached.getLong("keyframeBytes")<=8L*466*466*4);
            Bitmap[] expected={null};instrument.runOnMainSync(()->{try{PodVideoKeyframes frames=(PodVideoKeyframes)field(PodVideoSessionServices.class,"keyframes").get(service);String binding=PodVideoFrameCache.binding(args("unused",43,true),prefix)+":466:466";expected[0]=frames.get(binding).pixels.copy(Bitmap.Config.ARGB_8888,false);}catch(Exception error){throw new RuntimeException(error);}});
            for(int i=0;i<5;i++){
                sample("keyframe_cached","active",43);
                Bitmap[] actual={null};CountDownLatch copied=new CountDownLatch(1);instrument.runOnMainSync(()->{try{assertNull("cache yields only after live texture",member("active","keyframeView"));PodVideoKeyframes.readPixels((android.view.TextureView)member("active","videoView"),240,240,main,pixels->{actual[0]=pixels;copied.countDown();});}catch(Exception error){throw new RuntimeException(error);}});assertTrue(copied.await(5,TimeUnit.SECONDS));assertNotNull(actual[0]);
                int[] a=new int[466*466],b=new int[a.length];expected[0].getPixels(a,0,466,0,0,466,466);actual[0].getPixels(b,0,466,0,0,466,466);long difference=0;
                for(int p=0;p<a.length;p++)for(int shift:new int[]{0,8,16})difference+=Math.abs(((a[p]>>shift)&255)-((b[p]>>shift)&255));
                double mae=difference/(double)(a.length*3);report.put("frameZeroMae",mae);
                try(FileOutputStream out=new FileOutputStream(new File(evidence,"cached-frame-zero.png"))){expected[0].compress(Bitmap.CompressFormat.PNG,100,out);}
                try(FileOutputStream out=new FileOutputStream(new File(evidence,"live-frame-zero.png"))){actual[0].compress(Bitmap.CompressFormat.PNG,100,out);}
                report.put("parityPositionMs",call("video.session.state",new JSONObject().put("sessionId","active")).optLong("positionMs"));assertTrue("seamless same decoded frame: MAE="+mae,mae<2);actual[0].recycle();
                call("video.session.prepare",args("live",41,true));long start=SystemClock.uptimeMillis();call("video.session.promote",new JSONObject().put("sessionId","live").put("replaceSessionId","active").put("bounds",bounds).put("autoplay",false));long latency=visible("live",start);samples.put(new JSONObject().put("kind","live_standby").put("visibleMs",latency));sample("cold","active",50+i);
            }
            try(FileOutputStream out=new FileOutputStream(new File(evidence,"cached-frame-zero.png"))){expected[0].compress(Bitmap.CompressFormat.PNG,100,out);}
            expected[0].recycle();release("live");
            // Query advertised capacity AND exercise more than the historical 3 leases.
            for(int index=0;index<7;index++)call("video.session.prepare",args("capacity"+index,60+index,true));
            report.put("capacity",call("video.session.capabilities",new JSONObject())).put("capacityMemory",memory());assertTrue("device permits more than 3 live codecs",budget.inUse()>3);
            report.put("samples",samples).put("surface","HWUI window commit").put("emulatorOnly",true);
        }finally{
            service.close();instrument.waitForIdleSync();prefix.close();activity.finish();instrument.waitForIdleSync();
            PodVideoKeyframes frames=(PodVideoKeyframes)field(PodVideoSessionServices.class,"keyframes").get(service);
            report.put("commitWaitTimeouts",commitWaitTimeouts).put("after",memory()).put("cacheBytesAfterClose",frames.bytes).put("leasesAfterClose",budget.inUse()).put("samples",samples);
            assertEquals(0,frames.bytes);
            try(FileOutputStream out=new FileOutputStream(new File(evidence,"measurement.json"))){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            android.util.Log.i("DYFRAME_TEST",report.toString());
            assertEquals(0,budget.inUse());
        }
    }
}
