package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoPixelStandbyTest {
    static class Reply implements PodVideoSessionServices.Callback {
        JSONObject value;String error;
        public void complete(JSONObject value){this.value=value;}
        public void fail(String code,String message){error=code;}
    }
    @Test public void cachedStandbyUsesNoFourthLiveLeaseAndNeedsNoPlayerForLoop()throws Exception {exercise(3,false);}
    @Test public void explicitFrameOnlyCacheHitDoesNotAcquireALiveDecoder()throws Exception {exercise(0,false);}
    @Test public void retainedFrameOnlyCacheHitBindsAStandbyWithoutAnotherDecoder()throws Exception {exercise(2,true);}
    private void exercise(int live,boolean retainKeyframe)throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(context.getCacheDir(),"pixel-standby"));
        PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);
        for(int i=0;i<live;i++)assertTrue(budget.acquire());
        PodVideoSessionServices service=new PodVideoSessionServices(context,budget,cache);
        service.attachToParent(new FrameLayout(context));Shadows.shadowOf(Looper.getMainLooper()).idle();
        try{
            String uri=cache.register("fixture","https://fixture.invalid/video.mp4",Collections.emptyMap());
            JSONObject args=new JSONObject().put("sessionId","cached").put("url",uri).put("prewarm",true)
                .put("keyframeOnly",live==0||retainKeyframe).put("retainKeyframe",retainKeyframe)
                .put("bounds",new JSONObject().put("width",233).put("height",233));
            Field field=PodVideoSessionServices.class.getDeclaredField("keyframes");field.setAccessible(true);
            PodVideoKeyframes frames=(PodVideoKeyframes)field.get(service);
            frames.put(PodVideoFrameCache.binding(args,cache),Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888));
            Reply prepared=new Reply();service.dispatch("video.session.prepare",args,prepared);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(prepared.error);assertNotNull(prepared.value);
            if(live==0){assertTrue(prepared.value.getBoolean("keyframeAvailable"));assertEquals("cached",prepared.value.getString("state"));assertEquals(0,budget.inUse());return;}
            assertTrue(prepared.value.getBoolean("hasCachedFirstFrame"));
            assertFalse(prepared.value.getBoolean("hasRenderedFirstFrame"));assertEquals(live,budget.inUse());assertEquals(live,budget.peakInUse());
            Reply state=new Reply();service.dispatch("video.session.state",new JSONObject().put("sessionId","cached"),state);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(state.error);assertTrue(state.value.getBoolean("hasCachedFirstFrame"));
            Reply loop=new Reply();service.dispatch("video.session.setLoop",new JSONObject().put("sessionId","cached").put("loop",true),loop);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(loop.error);
            if(retainKeyframe){
                Reply outgoing=new Reply();
                service.dispatch("video.session.prepare",new JSONObject(args.toString()).put("sessionId","current"),outgoing);
                Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(outgoing.error);assertNotNull(outgoing.value);
                Reply transition=new Reply();
                service.dispatch("video.session.transition",new JSONObject().put("fromSessionId","current").put("toSessionId","cached")
                    .put("direction","down").put("progress",.1).put("bounds",args.getJSONObject("bounds")),transition);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertNull(transition.error);assertTrue(transition.value.getBoolean("composited"));
                Field sessions=PodVideoSessionServices.class.getDeclaredField("sessions");sessions.setAccessible(true);
                Object target=((Map<?,?>)sessions.get(service)).get("cached");
                Field image=target.getClass().getDeclaredField("keyframeView");image.setAccessible(true);
                ImageView pixels=(ImageView)image.get(target);
                assertEquals(View.VISIBLE,pixels.getVisibility());assertNotNull(pixels.getDrawable());
                assertEquals(live,budget.peakInUse());
            }
        }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();cache.close();for(int i=0;i<live;i++)budget.release();}
    }
}
