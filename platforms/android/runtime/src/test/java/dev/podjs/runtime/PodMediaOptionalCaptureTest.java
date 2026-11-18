package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.TextureView;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PodMediaOptionalCaptureTest {
    static Field field(Class<?> c,String name)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}
    static class Reply implements PodVideoSessionServices.Callback {volatile JSONObject value;String error;public void complete(JSONObject v){value=v;}public void fail(String c,String m){error=c;}}
    static final class Fixture implements AutoCloseable {
        final Context context=ApplicationProvider.getApplicationContext();
        final PodVideoSessionServices service=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));
        final Object session;final ExoPlayer player;final JSONObject source=new JSONObject().put("url","http://127.0.0.1/frame.mp4");
        final PodMediaMemoryAccountingTest.Ledger ledger;final Field process;final Object previous;int readbacks;
        Fixture(long cap)throws Exception{
            ledger=new PodMediaMemoryAccountingTest.Ledger(cap);process=field(PodMediaMemoryAccounting.class,"process");previous=process.get(null);
            process.set(null,new PodMediaMemoryAccounting(PodVideoCodecGate.PROCESS,ledger));
            Class<?> c=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=c.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
            session=ctor.newInstance(service,"selected");((Map<String,Object>)field(PodVideoSessionServices.class,"sessions").get(service)).put("selected",session);
            player=new ExoPlayer.Builder(context).build();set("player",player);set("frameSource",source);set("generation",7L);set("hasRenderedFirstFrame",true);set("geometryReady",true);
            TextureView view=new TextureView(context){@Override public boolean isAvailable(){return true;}@Override public Bitmap getBitmap(int w,int h){assertTrue("promise precedes bitmap allocation",ledger.held>0);readbacks++;return Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);}};
            view.layout(0,0,100,100);set("videoView",view);
        }
        void set(String key,Object value)throws Exception{field(session.getClass(),key).set(session,value);}
        Reply capture(String id,long generation)throws Exception{
            Reply reply=new Reply();Method method=PodVideoSessionServices.class.getDeclaredMethod("captureFrame",JSONObject.class,PodVideoSessionServices.Callback.class);method.setAccessible(true);
            method.invoke(service,new JSONObject().put("sessionId",id).put("key","fixture").put("source",source).put("sourceGeneration",generation),reply);return reply;
        }
        public void close(){try{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,ledger.held);}finally{try{process.set(null,previous);}catch(Exception error){throw new AssertionError(error);}}}
    }
    @Test public void missingOrStaleSnapshotIsOptionalAndCannotRetireCurrentPlayer()throws Exception{
        try(Fixture f=new Fixture(2_000_000)){
            for(Reply reply:new Reply[]{f.capture("expired",7),f.capture("selected",6)}){assertNull(reply.error);assertFalse(reply.value.getBoolean("available"));}
            assertEquals(0,f.readbacks);assertSame(f.player,field(f.session.getClass(),"player").get(f.session));
        }
    }
    @Test public void unprovenGeometryNeverReadsPixelsOrRecordsAPlaybackFailure()throws Exception{
        try(Fixture f=new Fixture(2_000_000)){
            f.set("geometryReady",false);Reply reply=f.capture("selected",7);assertNull(reply.error);assertFalse(reply.value.getBoolean("available"));assertEquals(0,f.readbacks);assertEquals(0,f.ledger.held);
        }
    }
    @Test public void byteRefusalReturnsNoSnapshotBeforeAllocatingAndKeepsThePlayer()throws Exception{
        try(Fixture f=new Fixture(1)){
            Reply reply=f.capture("selected",7);assertNull(reply.error);assertFalse(reply.value.getBoolean("available"));assertEquals(0,f.readbacks);assertSame(f.player,field(f.session.getClass(),"player").get(f.session));
        }
    }
    @Test public void bitmapPromiseSurvivesUntilEncodingAndPhysicalRecycle()throws Exception{
        try(Fixture f=new Fixture(2_000_000)){
            Reply reply=f.capture("selected",7);
            for(int i=0;i<100&&reply.value==null;i++){Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idle();}
            assertNull(reply.error);assertNotNull(reply.value);assertTrue(reply.value.getBoolean("available"));assertEquals(1,f.readbacks);assertEquals(1,f.ledger.commits);assertEquals(1,f.ledger.returns);assertEquals(0,f.ledger.held);
        }
    }
}
