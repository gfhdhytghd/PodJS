package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.content.Context;
import android.os.Looper;
import android.view.TextureView;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.lang.reflect.*;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoKeyframesTest {
    @Test public void reportedInstancesNeverLiftTheMeasuredWatchCeiling() {
        assertEquals(3,PodVideoDecoderCapabilities.validatedLimit(new PodVideoDecoderCapabilities.Profile("video/hevc",1280,720),"c2.qti.hevc.decoder",false));
        assertEquals(3,PodVideoDecoderCapabilities.validatedLimit(new PodVideoDecoderCapabilities.Profile("video/avc",240,240),"c2.android.avc.decoder",false));
        assertEquals(3,PodVideoDecoderCapabilities.validatedLimit(new PodVideoDecoderCapabilities.Profile("video/hevc",240,240),"c2.android.hevc.decoder",true));
        assertEquals(3,PodVideoDecoderCapabilities.validatedLimit(new PodVideoDecoderCapabilities.Profile("video/avc",1280,720),"c2.android.avc.decoder",true));
        assertEquals(8,PodVideoDecoderCapabilities.validatedLimit(new PodVideoDecoderCapabilities.Profile("video/avc",240,240),"c2.android.avc.decoder",true));
    }

    @Test public void cachedLayerFollowsTheDragWithTheSameFixedRoundClip()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();FrameLayout host=new FrameLayout(context);
        PodVideoSessionServices service=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));service.attachToParent(host);Shadows.shadowOf(Looper.getMainLooper()).idle();
        try{
            Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
            Object slot=ctor.newInstance(service,"active");
            @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);sessions.put("active",slot);
            JSONObject bounds=new JSONObject().put("width",466).put("height",466).put("roundClip",new JSONObject().put("cx",233).put("cy",233).put("radius",233));
            Reply reply=new Reply();service.dispatch("video.session.setBounds",new JSONObject().put("sessionId","active").put("bounds",bounds),reply);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(reply.error);
            PodVideoKeyframes cache=(PodVideoKeyframes)field(PodVideoSessionServices.class,"keyframes").get(service);PodVideoKeyframes.Frame frame=cache.put("test",pixels());field(type,"frameBinding").set(slot,"test");
            Method reveal=type.getDeclaredMethod("revealKeyframe");reveal.setAccessible(true);reveal.invoke(slot);
            Method move=type.getDeclaredMethod("setTranslation",float.class,float.class);move.setAccessible(true);move.invoke(slot,0f,-120f);
            android.widget.ImageView image=(android.widget.ImageView)field(type,"keyframeView").get(slot);TextureView video=(TextureView)field(type,"videoView").get(slot);
            assertEquals(video.getTranslationY(),image.getTranslationY(),0);assertEquals(-120f,image.getTranslationY(),0);assertSame(video.getOutlineProvider(),image.getOutlineProvider());assertTrue(image.getClipToOutline());
            android.graphics.Outline outline=new android.graphics.Outline();image.getOutlineProvider().getOutline(image,outline);android.graphics.Rect rect=new android.graphics.Rect();assertTrue(outline.getRect(rect));assertEquals(120,rect.top);assertEquals(1,frame.pins);
        }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }

    static Bitmap pixels(){return Bitmap.createBitmap(466,466,Bitmap.Config.ARGB_8888);}
    @Test public void lruBoundsPixelsAndDoesNotRecycleDisplayedFrame(){
        PodVideoKeyframes cache=new PodVideoKeyframes();
        PodVideoKeyframes.Frame pinned=cache.put("0",pixels());pinned.pins++;
        for(int i=1;i<16;i++)cache.put(String.valueOf(i),pixels());
        assertEquals(8,cache.size());assertEquals(8L*466*466*4,cache.bytes);
        assertSame(pinned,cache.get("0"));assertFalse(pinned.pixels.isRecycled());assertNull(cache.get("1"));
        cache.trim();assertEquals(1,cache.size());pinned.pins--;cache.close();assertEquals(0,cache.bytes);assertTrue(pinned.pixels.isRecycled());
    }
    @Test public void changedHeadersCannotReusePixelsAndDuplicateBindingKeepsOriginal()throws Exception {
        JSONObject a=new JSONObject().put("url","https://fixture.invalid/video").put("headers",new JSONObject().put("Authorization","synthetic-a"));
        JSONObject b=new JSONObject(a.toString()).put("headers",new JSONObject().put("Authorization","synthetic-b"));
        String first=PodVideoFrameCache.binding(a,null),second=PodVideoFrameCache.binding(b,null);
        assertNotEquals(first,second);PodVideoKeyframes cache=new PodVideoKeyframes();Bitmap original=pixels();cache.put(first,original);Bitmap duplicate=pixels();cache.put(first,duplicate);
        assertTrue(duplicate.isRecycled());assertSame(original,cache.get(first).pixels);assertNull(cache.get(second));cache.close();
    }
    @Test public void memoryCanAdmitAdvertisedEightButRetainsPressureReserve(){
        PodVideoMemoryBudget budget=new PodVideoMemoryBudget(8);long slot=PodVideoMemoryBudget.decoderBytes(466,466),reserve=PodVideoMemoryBudget.RESERVE_BYTES;
        assertEquals(8,budget.limit(reserve+slot*8,false,slot,0));assertEquals(3,budget.limit(reserve+slot*3,false,slot,0));
        budget.trim(10);assertEquals(2,budget.limit(reserve+slot*8,false,slot,0));budget.trim(80);assertEquals(1,budget.limit(Long.MAX_VALUE,false,slot,0));
    }
    static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static class Reply implements PodVideoSessionServices.Callback {JSONObject value;String error;public void complete(JSONObject v){value=v;}public void fail(String c,String m){error=c;}}
    @Test public void shortLivedWorkerReleasesLeaseAndCachedHitDoesNotAllocateDecoder()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);PodVideoSessionServices service=new PodVideoSessionServices(context,budget);service.attachToParent(new FrameLayout(context));Shadows.shadowOf(Looper.getMainLooper()).idle();
        try{
            Reply capabilities=new Reply();service.dispatch("video.session.capabilities",new JSONObject(),capabilities);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("base has no independently retained keyframe cache",capabilities.value.optBoolean("keyframeCache"));
            Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> constructor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);constructor.setAccessible(true);
            Object worker=constructor.newInstance(service,"worker");
            @SuppressWarnings("unchecked") Map<String,Object> sessions=(Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service);sessions.put("worker",worker);
            JSONObject args=new JSONObject().put("sessionId","worker").put("url","https://fixture.invalid/first").put("prewarm",true).put("keyframeOnly",true).put("bounds",new JSONObject().put("width",466).put("height",466));
            // Robolectric uses density=1; actual decoder and pixel parity are separate instrumentation tests.
            String binding=PodVideoFrameCache.binding(args,null)+":466:466";
            PodVideoKeyframes cache=(PodVideoKeyframes)field(PodVideoSessionServices.class,"keyframes").get(service);cache.put(binding,pixels());
            for(String name:new String[]{"prewarming","keyframeOnly","decoderLease"})field(type,name).setBoolean(worker,true);
            field(type,"frameBinding").set(worker,binding);Reply reply=new Reply();field(type,"prepareCallback").set(worker,reply);budget.acquire();
            Method complete=type.getDeclaredMethod("completePrepare");complete.setAccessible(true);complete.invoke(worker);
            assertNull(reply.error);assertTrue(reply.value.getBoolean("keyframeAvailable"));assertEquals(0,budget.inUse());assertFalse(sessions.containsKey("worker"));assertEquals(1,cache.size());
            Reply cached=new Reply();service.dispatch("video.session.prepare",args,cached);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(cached.error);assertEquals("cached",cached.value.getString("state"));assertEquals(0,budget.inUse());assertTrue(sessions.isEmpty());
        }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
}
