package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** A lost guest frame acknowledgement must not retain a released native slot. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoObservationLifetimeTest {
    static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static Map<?,?> pending()throws Exception {return (Map<?,?>)field(PodBenchmark.class,"videos").get(null);}
    @Test public void releasedSlotsRemoveUnconfirmedFrameObservers()throws Exception {
        pending().clear();
        Context context=ApplicationProvider.getApplicationContext();
        PodVideoSessionServices service=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));
        service.attachToParent(new FrameLayout(context));Shadows.shadowOf(Looper.getMainLooper()).idle();
        Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
        Constructor<?> constructor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);constructor.setAccessible(true);
        Method close=type.getDeclaredMethod("close");close.setAccessible(true);
        try {
            for(int i=0;i<100;i++) {
                Object slot=constructor.newInstance(service,"slot"+i);
                View view=(View)field(type,"videoView").get(slot);
                long seq=PodBenchmark.begin("gesture_down",new JSONObject());
                PodBenchmark.watchVideo(view,new PodBenchmark.VideoAction(seq,i+1,1),()->view.isAttachedToWindow());
                close.invoke(slot);
            }
            assertEquals("released video observers still own old TextureViews",0,pending().size());
        } finally {pending().clear();service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void oneSurfaceKeepsOnlyItsCurrentUnconfirmedObservation()throws Exception {
        pending().clear();View view=new View(ApplicationProvider.getApplicationContext());
        try {
            for(int i=0;i<100;i++) {
                long seq=PodBenchmark.begin("gesture_down",new JSONObject());
                PodBenchmark.watchVideo(view,new PodBenchmark.VideoAction(seq,i+1,1),()->view.isAttachedToWindow());
            }
            assertEquals("one surface retained obsolete observation generations",1,pending().size());
        } finally {pending().clear();}
    }
    @Test public void mismatchedAcknowledgementCannotConsumeTheCurrentGeneration()throws Exception {
        pending().clear();View view=new View(ApplicationProvider.getApplicationContext());
        long seq=PodBenchmark.begin("gesture_down",new JSONObject());
        PodBenchmark.watchVideo(view,new PodBenchmark.VideoAction(seq,2,1),()->true);
        assertEquals(seq,PodBenchmark.dispatch(new JSONObject().put("video_frame",seq).put("generation",1).toString()));
        assertEquals(1,pending().size());
        assertEquals(seq,PodBenchmark.dispatch(new JSONObject().put("video_frame",seq).put("generation",2).toString()));
        assertEquals(0,pending().size());
        assertFalse(org.robolectric.shadows.ShadowLog.getLogsForTag("DYBENCH").stream().anyMatch(row->row.msg.contains("code=incoming_first_frame seq="+seq+" ")));
    }
    @Test public void acknowledgementBeforeNativeFrameStillMatches()throws Exception {
        pending().clear();View view=new View(ApplicationProvider.getApplicationContext());
        long seq=PodBenchmark.begin("gesture_down",new JSONObject());
        assertEquals(seq,PodBenchmark.dispatch(new JSONObject().put("video_frame",seq).put("generation",2).toString()));
        PodBenchmark.watchVideo(view,new PodBenchmark.VideoAction(seq,2,1),()->true);
        assertEquals(0,pending().size());
        assertFalse(((Map<?,?>)field(PodBenchmark.class,"confirmedVideo").get(null)).containsKey(seq));
    }
}
