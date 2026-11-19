package dev.podjs.runtime;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoTextureWorkTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    final Context context = ApplicationProvider.getApplicationContext();
    final FrameLayout parent = new FrameLayout(context);
    final CountingHandler main = new CountingHandler();
    final SurfaceTexture physical = new SurfaceTexture(0);
    final String url = "file:///selected.mp4";
    PodMediaPrefixCache cache;
    PodVideoServices legacy;
    PodVideoSessionServices sessions;

    static class CountingHandler extends Handler {
        int calls;
        CountingHandler() { super(Looper.getMainLooper()); }
        @Override public boolean sendMessageAtTime(Message message, long time) {
            calls++;
            return super.sendMessageAtTime(message, time);
        }
    }

    static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    static long handoffs() {
        return ShadowLog.getLogsForTag("PodJSPerf").stream().filter(log->log.msg.contains("stage=storedVideoTexture")).count();
    }

    void initCache() throws Exception {
        cache = new PodMediaPrefixCache(files.newFolder(),new PodMediaPrefixCacheTest.Remote(256*1024),16L<<20);
        cache.startupPreview = new PodMediaStartupPreview(context,main,cache);
        cache.startupPreview.attach(parent);
    }

    Object session() throws Exception {
        sessions = new PodVideoSessionServices(context,new PodVideoDecoderBudget(3),cache);
        ReflectionHelpers.setField(sessions,"main",main);
        sessions.attachToParent(parent);
        idle();
        Class<?> type = Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");
        Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);
        ctor.setAccessible(true);
        Object slot=ctor.newInstance(sessions,"active");
        Map<String,Object> slots=ReflectionHelpers.getField(sessions,"sessions");
        slots.put("active",slot);
        Class<?> bounds=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Bounds");
        Method read=bounds.getDeclaredMethod("read",JSONObject.class,float.class,boolean.class);
        read.setAccessible(true);
        ReflectionHelpers.setField(slot,"bounds",read.invoke(null,new JSONObject().put("width",80).put("height",80),1f,true));
        ReflectionHelpers.setField(slot,"frameSource",new JSONObject().put("url",url));
        ReflectionHelpers.setField(slot,"presented",true);
        ReflectionHelpers.setField(slot,"hasRenderedFirstFrame",true);
        ReflectionHelpers.setField(slot,"geometryReady",true);
        return slot;
    }

    TextureView.SurfaceTextureListener observe(TextureView view) {
        view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){}
            public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t){return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture t){}
        });
        TextureView.SurfaceTextureListener actual=ReflectionHelpers.getField(view,"mListener");
        actual.onSurfaceTextureAvailable(physical,80,80);
        return actual;
    }

    void checkSteadyAndLatePreview(TextureView view) {
        TextureView.SurfaceTextureListener output=observe(view);
        main.calls=0;
        for(int i=0;i<600;i++)output.onSurfaceTextureUpdated(physical);
        assertEquals("main messages for 600 steady updates",0,main.calls);
        cache.startupPreview.image=new ImageView(context);
        cache.startupPreview.uri="file:///other.mp4";
        output.onSurfaceTextureUpdated(physical);
        assertEquals("unrelated overlay",0,main.calls);
        cache.startupPreview.uri=url;
        long before=handoffs();
        for(int i=0;i<10;i++)output.onSurfaceTextureUpdated(physical);
        assertEquals("one handoff after draw",1,main.calls);
        assertEquals(before,handoffs());
        idle();
        assertEquals(before+1,handoffs());
        assertNotNull("a detached view has no window commit",cache.startupPreview.image);
        cache.startupPreview.hide(0);
        idle();
        main.calls=0;
        output.onSurfaceTextureUpdated(physical);
        assertEquals(0,main.calls);
    }

    @Test public void foregroundSessionSteadyAndLatePreview() throws Exception {
        initCache();
        checkSteadyAndLatePreview(ReflectionHelpers.getField(session(),"videoView"));
    }
    @Test public void legacySteadyAndLatePreview() throws Exception {
        initCache();
        legacy=new PodVideoServices(context,new PodVideoDecoderBudget(3),cache);
        ReflectionHelpers.setField(legacy,"main",main);
        legacy.attachToParent(parent);idle();
        ReflectionHelpers.setField(legacy,"frameSource",new JSONObject().put("url",url));
        ReflectionHelpers.setField(legacy,"hasRenderedFirstFrame",true);
        ReflectionHelpers.setField(legacy,"boundsVisible",true);
        ReflectionHelpers.setField(legacy,"observedTextureFrames",8);
        checkSteadyAndLatePreview(ReflectionHelpers.getField(legacy,"videoView"));
    }
    @Test public void aReplacedViewCannotMarkTheSameGenerationOrHandoffItsOverlay() throws Exception {
        initCache();
        Object slot=session();
        TextureView original=ReflectionHelpers.getField(slot,"videoView");
        TextureView.SurfaceTextureListener old=observe(original);
        cache.startupPreview.image=new ImageView(context);
        cache.startupPreview.uri=url;
        long before=handoffs();
        old.onSurfaceTextureUpdated(physical);
        parent.removeView(original);
        ReflectionHelpers.setField(slot,"videoView",null);
        Method attach=slot.getClass().getDeclaredMethod("attachViews",android.view.ViewGroup.class);
        attach.setAccessible(true);attach.invoke(slot,parent);
        idle();
        old.onSurfaceTextureUpdated(physical);idle();
        assertFalse("stale view cannot supply the replacement's first texture",ReflectionHelpers.getField(slot,"hasUpdatedFrame"));
        assertEquals(before,handoffs());
        TextureView current=ReflectionHelpers.getField(slot,"videoView");
        observe(current).onSurfaceTextureUpdated(physical);idle();
        assertTrue(ReflectionHelpers.getField(slot,"hasUpdatedFrame"));
        assertEquals(before+1,handoffs());
    }
    @Test public void aReplacedPhysicalSurfaceNeedsItsOwnUpdateAndThePreviewNeedsAWindowCommit() throws Exception {
        initCache();
        Object slot=session();
        TextureView view=ReflectionHelpers.getField(slot,"videoView");
        TextureView.SurfaceTextureListener output=observe(view);
        cache.startupPreview.image=new ImageView(context);
        cache.startupPreview.uri=url;
        long before=handoffs();
        output.onSurfaceTextureUpdated(physical);
        assertTrue(ReflectionHelpers.getField(slot,"hasUpdatedFrame"));
        assertTrue(output.onSurfaceTextureDestroyed(physical));
        assertFalse(ReflectionHelpers.getField(slot,"hasUpdatedFrame"));
        SurfaceTexture replacement=new SurfaceTexture(0);
        try{
            // Android's explicit replacement need not deliver onSurfaceTextureAvailable.
            view.setSurfaceTexture(replacement);
            output.onSurfaceTextureUpdated(physical);idle();
            assertEquals(before,handoffs());
            output.onSurfaceTextureUpdated(replacement);idle();
            assertEquals(before+1,handoffs());
            assertNotNull(cache.startupPreview.image);
            PodVideoGeometryRecoveryTest.Texture visible=new PodVideoGeometryRecoveryTest.Texture(context);
            cache.startupPreview.handoff(url,visible,()->true);
            assertTrue(cache.startupPreview.handingOff);
            assertNotNull(cache.startupPreview.image);
            assertTrue(visible.commit()>0);
            assertNull(cache.startupPreview.image);
        }finally{replacement.release();}
    }

    @Test public void realSurfaceBudgetCommitsOnFirstUpdateAndReleasesOnClose() throws Exception {
        PodMediaMemoryAccounting previous=ReflectionHelpers.getStaticField(PodMediaMemoryAccounting.class,"process");
        PodMediaMemoryAccountingTest.Ledger ledger=new PodMediaMemoryAccountingTest.Ledger(256L<<20);
        ReflectionHelpers.setStaticField(PodMediaMemoryAccounting.class,"process",new PodMediaMemoryAccounting(new PodVideoCodecGate(),ledger));
        try{
            initCache();
            Object slot=session();
            TextureView.SurfaceTextureListener current=observe(ReflectionHelpers.getField(slot,"videoView"));
            assertTrue(ledger.held>0);
            assertEquals(0,ledger.commits);
            for(int i=0;i<600;i++)current.onSurfaceTextureUpdated(physical);
            assertEquals(1,ledger.commits);
            sessions.close();idle();sessions=null;
            assertEquals(0,ledger.held);

            legacy=new PodVideoServices(context,new PodVideoDecoderBudget(3),cache);
            legacy.attachToParent(parent);idle();
            TextureView.SurfaceTextureListener old=observe(ReflectionHelpers.getField(legacy,"videoView"));
            old.onSurfaceTextureUpdated(physical);
            assertEquals("legacy has yet to reserve its surface",1,ledger.commits);
            assertTrue(ReflectionHelpers.callInstanceMethod(legacy,"ensureSurfaceMemory"));
            for(int i=0;i<600;i++)old.onSurfaceTextureUpdated(physical);
            assertEquals(2,ledger.commits);
            legacy.close();idle();legacy=null;
            assertEquals(0,ledger.held);
        }finally{ReflectionHelpers.setStaticField(PodMediaMemoryAccounting.class,"process",previous);}
    }

    @Test public void aLateBenchmarkWaitsForTheVisibleDecodedOutputAndStopsRequestingMessages() throws Exception {
        initCache();
        Object slot=session();
        TextureView view=ReflectionHelpers.getField(slot,"videoView");
        TextureView.SurfaceTextureListener output=observe(view);
        output.onSurfaceTextureUpdated(physical);
        long seq=PodBenchmark.begin("gesture_down",new JSONObject());
        ReflectionHelpers.setField(slot,"benchmarkAction",new PodBenchmark.VideoAction(seq,1,0));
        ReflectionHelpers.setField(slot,"benchmarkFramePending",true);
        ReflectionHelpers.setField(slot,"hasRenderedFirstFrame",false);
        output.onSurfaceTextureUpdated(physical);idle();
        assertTrue(ReflectionHelpers.getField(slot,"swapTextureLogged"));
        assertTrue(ReflectionHelpers.getField(slot,"benchmarkFramePending"));
        ReflectionHelpers.setField(slot,"hasRenderedFirstFrame",true);
        ReflectionHelpers.setField(slot,"geometryReady",true);
        output.onSurfaceTextureUpdated(physical);idle();
        assertFalse(ReflectionHelpers.getField(slot,"benchmarkFramePending"));
        main.calls=0;
        for(int i=0;i<600;i++)output.onSurfaceTextureUpdated(physical);
        assertEquals(0,main.calls);
    }
    @Test public void geometryCommitCanArmTheBenchmarkForAnAlreadyLatchedPausedFrame() throws Exception {
        try(PodVideoGeometryRecoveryTest.Fixture fixture=new PodVideoGeometryRecoveryTest.Fixture()){
            Object slot=fixture.slot("current",false);
            long seq=PodBenchmark.begin("gesture_down",new JSONObject());
            fixture.set(slot,"benchmarkAction",new PodBenchmark.VideoAction(seq,1,0));
            fixture.set(slot,"benchmarkFramePending",true);
            fixture.invoke(slot,"confirmGeometry");
            assertTrue((boolean)fixture.get(slot,"benchmarkFramePending"));
            assertTrue(fixture.texture(slot).commit()>0);
            assertTrue(fixture.ready(slot));
            assertFalse((boolean)fixture.get(slot,"benchmarkFramePending"));
        }
    }
    @After public void close() {
        if(legacy!=null)legacy.close();
        if(sessions!=null)sessions.close();
        idle();
        if(cache!=null)cache.close();
        physical.release();
    }
}
