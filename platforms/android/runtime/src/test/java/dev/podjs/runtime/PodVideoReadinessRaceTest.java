package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoReadinessRaceTest {
    static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static class Reply implements PodVideoSessionServices.Callback {
        JSONObject value;String error;
        public void complete(JSONObject value){this.value=value;}
        public void fail(String code,String message){error=code;}
    }
    @Test public void aLostStandbyFrameComposesBlackAndCanBeAdoptedOnce()throws Exception {exercise(false);}
    @Test public void aLiveStandbyWithoutAPrepareCallbackCanBeAdopted()throws Exception {exercise(true);}
    @Test public void pixelsArrivingDuringDragKeepTheNativePageOffset()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(context.getCacheDir(),"readiness-drag-arrival"));
        PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);
        PodVideoSessionServices service=new PodVideoSessionServices(context,budget,cache);
        service.attachToParent(new FrameLayout(context));Shadows.shadowOf(Looper.getMainLooper()).idle();
        try{
            JSONObject bounds=new JSONObject().put("width",233).put("height",233).put("visible",true);
            PodVideoKeyframes frames=(PodVideoKeyframes)field(PodVideoSessionServices.class,"keyframes").get(service);frames.onStored(null);
            String targetBinding=null;Reply targetReady=null;
            for(String id:new String[]{"from","to"}){
                String uri=cache.register(id,"https://fixture.invalid/"+id+".mp4",Collections.emptyMap());
                JSONObject args=new JSONObject().put("sessionId",id).put("url",uri).put("prewarm",true).put("bounds",bounds);
                String binding=PodVideoFrameCache.binding(args,cache);
                frames.put(binding,Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888));
                if(id.equals("to"))targetBinding=binding;
                Reply ready=new Reply();service.dispatch("video.session.prepare",args,ready);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(ready.error);
                if(id.equals("to")){targetReady=ready;assertTrue(ready.value.getBoolean("hasCachedFirstFrame"));}
            }
            Map<?,?> sessions=(Map<?,?>)field(PodVideoSessionServices.class,"sessions").get(service);Object to=sessions.get("to");
            Method clear=to.getClass().getDeclaredMethod("clearKeyframe");clear.setAccessible(true);clear.invoke(to);frames.trim();
            Reply drag=new Reply();service.dispatch("video.session.transition",new JSONObject().put("fromSessionId","from").put("toSessionId","to").put("progress",.5).put("direction","up").put("bounds",bounds),drag);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(drag.error);
            View texture=(View)field(to.getClass(),"videoView").get(to);float offset=texture.getTranslationY();assertEquals(116.5f,offset,0);
            frames.put(targetBinding,Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888));
            Method ready=PodVideoSessionServices.class.getDeclaredMethod("prefixFrameReady",String.class);ready.setAccessible(true);ready.invoke(service,targetBinding);
            View picture=(View)field(to.getClass(),"keyframeView").get(to);
            assertNotNull(picture);assertEquals(offset,texture.getTranslationY(),0);assertEquals(offset,picture.getTranslationY(),0);assertEquals(View.VISIBLE,picture.getVisibility());
            assertTrue(targetReady.value.getBoolean("hasCachedFirstFrame"));assertEquals(0,budget.inUse());assertEquals(2,frames.size());
        }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();cache.close();}
    }
    private void exercise(boolean live)throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(context.getCacheDir(),"readiness-race"));
        PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);
        PodVideoSessionServices service=new PodVideoSessionServices(context,budget,cache);
        service.attachToParent(new FrameLayout(context));Shadows.shadowOf(Looper.getMainLooper()).idle();
        try{
            JSONObject bounds=new JSONObject().put("width",233).put("height",233).put("visible",true);
            PodVideoKeyframes frames=(PodVideoKeyframes)field(PodVideoSessionServices.class,"keyframes").get(service);
            frames.onStored(null);
            for(String id:new String[]{"from","to"}){
                String uri=cache.register(id,"https://fixture.invalid/"+id+".mp4",Collections.emptyMap());
                JSONObject args=new JSONObject().put("sessionId",id).put("url",uri).put("prewarm",true).put("bounds",bounds);
                frames.put(PodVideoFrameCache.binding(args,cache),Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888));
                Reply ready=new Reply();service.dispatch("video.session.prepare",args,ready);Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertNull(ready.error);assertTrue(ready.value.getBoolean("hasCachedFirstFrame"));
            }
            Map<?,?> sessions=(Map<?,?>)field(PodVideoSessionServices.class,"sessions").get(service);Object to=sessions.get("to");
            Method clear=to.getClass().getDeclaredMethod("clearKeyframe");clear.setAccessible(true);clear.invoke(to);frames.retainWindow(Collections.emptySet());
            ShadowLog.clear();
            Reply drag=new Reply();service.dispatch("video.session.transition",new JSONObject().put("fromSessionId","from").put("toSessionId","to").put("progress",.5).put("direction","up").put("bounds",bounds),drag);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(drag.error);assertTrue(drag.value.getBoolean("composited"));
            View texture=(View)field(to.getClass(),"videoView").get(to),background=(View)field(to.getClass(),"background").get(to);
            assertEquals(1f,texture.getAlpha(),0f);assertEquals(View.VISIBLE,background.getVisibility());
            assertFalse(field(to.getClass(),"hasRenderedFirstFrame").getBoolean(to));
            assertNull(field(to.getClass(),"cachedFrame").get(to));
            if(live){
                ExoPlayer player=new ExoPlayer.Builder(context).build();assertTrue(budget.acquire());
                field(to.getClass(),"player").set(to,player);field(to.getClass(),"decoderLease").setBoolean(to,true);
                field(to.getClass(),"audioPolicy").set(to,new PodMediaAudioPolicy(context,player));
                assertNull(field(to.getClass(),"prepareCallback").get(to));
            }
            Reply promoted=new Reply();service.dispatch("video.session.promote",new JSONObject().put("sessionId","to").put("replaceSessionId","from").put("adoptPreparing",true).put("bounds",bounds).put("autoplay",false),promoted);Shadows.shadowOf(Looper.getMainLooper()).idle();
            // Cold preparation completes asynchronously when Media3 becomes
            // ready. Admission must create one player without a not_ready error.
            assertNull("cold admission failure",promoted.error);assertEquals("one admitted player",1,budget.inUse());
            assertNotNull(field(to.getClass(),"player").get(to));
            assertFalse(field(to.getClass(),"prewarming").getBoolean(to));
            if(live){assertNotNull(promoted.value);assertFalse(promoted.value.getBoolean("hasRenderedFirstFrame"));}
            assertFalse(ShadowLog.getLogsForTag("DYBENCH").stream().anyMatch(x->x.msg.contains("code=error_page")));
        }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();cache.close();}
    }
}
