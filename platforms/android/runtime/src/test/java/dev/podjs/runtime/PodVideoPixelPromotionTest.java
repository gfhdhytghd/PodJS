package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoPixelPromotionTest {
    static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static class Reply implements PodVideoSessionServices.Callback {
        JSONObject value;String error;
        public void complete(JSONObject value){this.value=value;}
        public void fail(String code,String message){error=code;}
    }
    @Test public void revealDoesNotConstructPlayerUntilPaintAndKeepsPixelsDuringAdmission()throws Exception {exercise(false);}
    @Test public void cancelledPromotionCannotResurrectAReleasedSession()throws Exception {exercise(true);}
    @Test public void hardwareWorkerYieldsOnlyAfterPaintAndDoesNotConsumeAFourthDecoder()throws Exception {exercise(false,true);}
    @Test public void releasingDuringHardwarePreemptionCancelsPendingAdmission()throws Exception {exercise(true,true);}
    private void exercise(boolean cancel)throws Exception {exercise(cancel,false);}
    private void exercise(boolean cancel,boolean hardware)throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        PodMediaPrefixCache cache=new PodMediaPrefixCache(new File(context.getCacheDir(),"pixel-promotion-"+cancel));
        PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);for(int i=0;i<(hardware?2:3);i++)assertTrue(budget.acquire());if(hardware)assertTrue(budget.acquireSpare());
        PodVideoSessionServices service=new PodVideoSessionServices(context,budget,cache);
        service.attachToParent(new FrameLayout(context));Shadows.shadowOf(Looper.getMainLooper()).idle();
        Consumer<Boolean>[] painted=new Consumer[1];
        field(PodVideoSessionServices.class,"promotionPaint").set(service,(PodVideoSessionServices.PaintAwaiter)(view,done)->{painted[0]=done;return ()->{};});
        try{
            String uri=cache.register("fixture","https://fixture.invalid/video.mp4",Collections.emptyMap());
            JSONObject bounds=new JSONObject().put("width",233).put("height",233);
            JSONObject args=new JSONObject().put("sessionId","cached").put("url",uri).put("prewarm",true).put("bounds",bounds);
            PodVideoKeyframes frames=(PodVideoKeyframes)field(PodVideoSessionServices.class,"keyframes").get(service);
            frames.put(PodVideoFrameCache.binding(args,cache),Bitmap.createBitmap(100,200,Bitmap.Config.ARGB_8888));
            Reply prepared=new Reply();service.dispatch("video.session.prepare",args,prepared);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(prepared.error);long generation=prepared.value.getLong("sourceGeneration");
            Reply promoted=new Reply();service.dispatch("video.session.promote",new JSONObject().put("sessionId","cached").put("bounds",bounds).put("autoplay",true),promoted);Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(painted[0]);assertNull(promoted.value);assertNull(promoted.error);assertEquals(3,budget.inUse());
            Map<?,?> sessions=(Map<?,?>)field(PodVideoSessionServices.class,"sessions").get(service);Object slot=sessions.get("cached");
            assertNull(field(slot.getClass(),"player").get(slot));assertTrue(field(slot.getClass(),"presented").getBoolean(slot));
            Object pixels=field(slot.getClass(),"cachedFrame").get(slot);assertNotNull(pixels);
            if(hardware){
                painted[0].accept(true);Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(budget.spareCancelled());assertEquals(3,budget.inUse());assertNull(promoted.value);
                assertSame(pixels,field(slot.getClass(),"cachedFrame").get(slot));
            }else for(int i=0;i<3;i++)budget.release();
            if(cancel){service.dispatch("video.session.release",new JSONObject().put("sessionId","cached"),new Reply());Shadows.shadowOf(Looper.getMainLooper()).idle();}
            if(hardware){budget.release();budget.release();budget.releaseSpare();}else painted[0].accept(true);Shadows.shadowOf(Looper.getMainLooper()).idle();
            if(cancel){assertTrue(sessions.isEmpty());assertEquals(0,budget.inUse());assertEquals("cancelled",promoted.error);}
            else {assertNull(promoted.error);assertNotNull(promoted.value);assertEquals(generation,promoted.value.getLong("sourceGeneration"));assertSame(pixels,field(slot.getClass(),"cachedFrame").get(slot));assertEquals(1,budget.inUse());}
        }finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();cache.close();while(budget.inUse()>0)budget.release();}
    }
}
