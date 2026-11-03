package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
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
    @Test public void cachedStandbyUsesNoFourthLiveLeaseAndNeedsNoPlayerForLoop()throws Exception {exercise(3);}
    @Test public void existingPixelsBecomeReadyWithoutASecondDecodeWhenLiveLeasesAreAvailable()throws Exception {exercise(0);}
    private void exercise(int live)throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(context.getCacheDir(),"pixel-standby"));
        PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);
        for(int i=0;i<live;i++)assertTrue(budget.acquire());
        PodVideoSessionServices service=new PodVideoSessionServices(context,budget,cache);
        service.attachToParent(new FrameLayout(context));Shadows.shadowOf(Looper.getMainLooper()).idle();
        try{
            String uri=cache.register("fixture","https://fixture.invalid/video.mp4",Collections.emptyMap());
            JSONObject args=new JSONObject().put("sessionId","cached").put("url",uri).put("prewarm",true)
                .put("bounds",new JSONObject().put("width",233).put("height",233));
            Field field=PodVideoSessionServices.class.getDeclaredField("keyframes");field.setAccessible(true);
            PodVideoKeyframes frames=(PodVideoKeyframes)field.get(service);
            frames.put(PodVideoFrameCache.binding(args,cache),Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888));
            Reply prepared=new Reply();service.dispatch("video.session.prepare",args,prepared);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(prepared.error);assertNotNull(prepared.value);assertTrue(prepared.value.getBoolean("hasCachedFirstFrame"));
            assertFalse(prepared.value.getBoolean("hasRenderedFirstFrame"));assertEquals(live,budget.inUse());assertEquals(live,budget.peakInUse());
            Reply loop=new Reply();service.dispatch("video.session.setLoop",new JSONObject().put("sessionId","cached").put("loop",true),loop);Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(loop.error);
        }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();cache.close();for(int i=0;i<live;i++)budget.release();}
    }
}
