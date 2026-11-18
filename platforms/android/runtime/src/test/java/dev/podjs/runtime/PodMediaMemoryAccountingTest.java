package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.media.MediaFormat;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=34)
@LooperMode(LooperMode.Mode.PAUSED)
public class PodMediaMemoryAccountingTest {
    static final class Ledger implements PodMediaMemoryAccounting.Bytes {
        long held,peak;int commits,returns;final long cap;
        Ledger(long cap){this.cap=cap;}
        public synchronized PodMediaMemoryAccounting.Charge reserve(long n){
            if(n>cap-held)return null;held+=n;peak=Math.max(peak,held);
            return new PodMediaMemoryAccounting.Charge(){boolean closed,committed;
                public void commit(){synchronized(Ledger.this){assertFalse(closed);assertFalse(committed);committed=true;commits++;}}
                public void close(){synchronized(Ledger.this){assertFalse(closed);closed=true;held-=n;returns++;}}
            };
        }
    }
    static MediaCodecAdapter.Configuration config(String mime){
        MediaFormat media=new MediaFormat();media.setString(MediaFormat.KEY_MIME,mime);
        return MediaCodecAdapter.Configuration.createForVideoDecoding(null,media,new Format.Builder().setSampleMimeType(mime).build(),null,null);
    }
    static MediaCodecAdapter fake(Runnable release){return (MediaCodecAdapter)Proxy.newProxyInstance(PodMediaMemoryAccountingTest.class.getClassLoader(),new Class[]{MediaCodecAdapter.class},(p,m,a)->{if(m.getName().equals("release"))release.run();return null;});}
    @Test public void constructionRemainsPendingUntilThePhysicalFactoryCompletes()throws Exception{
        PodVideoCodecGate gate=new PodVideoCodecGate();Ledger ledger=new Ledger(22_000_000);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(gate,ledger);
        AutoCloseable binding=gate.bindMemory(memory,memory);
        PodVideoCodecGate.Permit permit=gate.acquire(new Object(),1,"delayed_factory",2);
        assertEquals(6_000_000,ledger.held);assertEquals(0,ledger.commits);
        permit.created();permit.created();assertEquals(1,ledger.commits);permit.close();assertEquals(0,ledger.held);
        PodVideoCodecGate.Permit failed=gate.acquire(new Object(),2,"failed_factory",2);failed.close();assertEquals(1,ledger.commits);assertEquals(2,ledger.returns);binding.close();
    }
    @Test public void byteDenialDoesNotClaimASlotOrNotifyConstruction()throws Exception{
        PodVideoCodecGate gate=new PodVideoCodecGate();Ledger ledger=new Ledger(10_000_000);
        PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(gate,ledger);
        AutoCloseable binding=gate.bindMemory(memory,memory);List<PodVideoCodecGate.Allocation> events=new ArrayList<>();
        gate.observe(new PodVideoCodecGate.LifecycleListener(){public void reserved(PodVideoCodecGate.Allocation a){events.add(a);}public void released(PodVideoCodecGate.Allocation a){}});
        Object owner=new Object();PodVideoCodecGate.Permit current=gate.acquire(owner,7,"current",2);
        assertNull(gate.acquire(new Object(),8,"warm",1));
        try{gate.await(new Object(),8,"warm",1);fail();}catch(PodVideoCodecGate.ByteAdmissionDenied expected){}
        assertEquals(1,gate.live());assertEquals(1,events.size());
        assertSame(owner,events.get(0).owner);assertEquals(7,events.get(0).generation);assertEquals(6_000_000,ledger.held);
        current.close();current.close();assertEquals(0,ledger.held);assertEquals(1,ledger.returns);binding.close();
    }
    @Test public void releaseRequestCannotReturnBytesAndForgedIdentityCannotRelease()throws Exception{
        PodVideoCodecGate gate=new PodVideoCodecGate();Ledger ledger=new Ledger(22_000_000);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(gate,ledger);
        AutoCloseable binding=gate.bindMemory(memory,memory);PodVideoCodecGate.Permit permit=gate.acquire(new Object(),13,"session",2);
        Field f=PodMediaMemoryAccounting.class.getDeclaredField("codecs");f.setAccessible(true);
        PodMediaMemoryBudget.Reservation reservation=((Map<Long,PodMediaMemoryBudget.Reservation>)f.get(memory)).values().iterator().next();
        PodMediaMemoryBudget.Owner owner=reservation.owner();
        memory.onOwnerEvent(owner,PodMediaMemoryBudget.Event.RELEASING);assertEquals(6_000_000,ledger.held);
        memory.onOwnerEvent(new PodMediaMemoryBudget.Owner(owner.id,owner.generation,owner.identity,owner.priority,owner.kind),PodMediaMemoryBudget.Event.RELEASED);
        assertEquals(6_000_000,ledger.held);permit.close();assertEquals(0,ledger.held);binding.close();
    }
    @Test public void opaqueAndSoftwareReservationsShareBytesWithoutChangingThreeSlotPolicy()throws Exception{
        PodVideoCodecGate gate=new PodVideoCodecGate();Ledger ledger=new Ledger(22_000_000);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(gate,ledger);
        AutoCloseable binding=gate.bindMemory(memory,memory);
        PodVideoCodecGate.Permit a=gate.acquire("software",2),b=gate.acquire("retriever",2),c=gate.acquire("container",2);
        assertNotNull(c);assertEquals(3,gate.live());assertNull(gate.acquire("fourth",2));assertEquals(18_000_000,ledger.held);
        a.close();b.close();c.close();assertEquals(0,ledger.held);binding.close();
    }
    @Test public void reservationsCommitOnceAndRejectGrowthAndForeignBudget(){
        Ledger ledger=new Ledger(22_000_000);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(new PodVideoCodecGate(),ledger);
        PodMediaMemoryBudget.Reservation r=memory.tryReserve(new PodMediaMemoryBudget.Owner(1,4,PodMediaMemoryBudget.Priority.CURRENT,PodMediaMemoryBudget.Kind.SURFACE),1000);
        try{memory.commit(r,1001);fail();}catch(IllegalArgumentException expected){}
        memory.commit(r,800);memory.commit(r,1000);assertEquals(1,ledger.commits);assertEquals(1000,ledger.held);
        PodMediaMemoryAccounting other=new PodMediaMemoryAccounting(new PodVideoCodecGate(),ledger);
        try{other.release(r);fail();}catch(IllegalArgumentException expected){}
        memory.release(r);memory.release(r);assertEquals(0,ledger.held);assertEquals(1,ledger.returns);
    }
    @Test public void sameGenerationIdsRemainDistinctAndReleaseOutOfOrder()throws Exception{
        PodVideoCodecGate gate=new PodVideoCodecGate();Ledger ledger=new Ledger(22_000_000);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(gate,ledger);
        AutoCloseable binding=gate.bindMemory(memory,memory);Object owner=new Object();
        PodVideoCodecGate.Permit first=gate.acquire(owner,9,"session",2),replacement=gate.acquire(owner,9,"session",2);
        replacement.close();assertEquals(6_000_000,ledger.held);first.close();assertEquals(0,ledger.held);binding.close();
    }
    @Test public void currentAndPromotedOwnersSurviveBytePressure()throws Exception{
        PodVideoCodecGate gate=new PodVideoCodecGate();Ledger ledger=new Ledger(6_000_000);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(gate,ledger);
        AutoCloseable binding=gate.bindMemory(memory,memory);Object selected=new Object(),promoted=new Object();int[] recycled={0};
        gate.register(selected,()->2,()->fail("current stolen"));gate.register(promoted,()->2,()->fail("promoted stolen"));gate.register(new Object(),()->0,()->recycled[0]++);
        PodVideoCodecGate.Permit first=gate.acquire(selected,1,"current",2);assertNull(gate.acquire(promoted,2,"warm",1));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,recycled[0]);assertEquals(6_000_000,ledger.held);
        first.close();binding.close();
    }
    @Test public void codecFactoryFailureAndFailedPhysicalReleaseUseSameLedger()throws Exception{
        PodVideoCodecGate gate=PodVideoCodecGate.PROCESS;Ledger ledger=new Ledger(22_000_000);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(gate,ledger);
        AutoCloseable binding=gate.bindMemory(memory,memory);
        try{
            try{new PodVideoCodecAdapters(c->{throw new java.io.IOException("offline factory");},"test",()->2).createAdapter(config("video/avc"));fail();}catch(java.io.IOException expected){}
            assertEquals(0,ledger.held);int[] releases={0};
            MediaCodecAdapter codec=new PodVideoCodecAdapters(c->fake(()->{assertEquals(6_000_000,ledger.held);if(++releases[0]==1)throw new IllegalStateException("pending");}),"test",()->2).createAdapter(config("video/avc"));
            try{codec.release();fail();}catch(IllegalStateException expected){}assertEquals(6_000_000,ledger.held);codec.release();codec.release();assertEquals(0,ledger.held);
        }finally{binding.close();}
    }
    @Test public void audioReservesBytesBeforeFactoryWithoutTakingVideoSlot()throws Exception{
        Ledger ledger=new Ledger(1_000_000);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(PodVideoCodecGate.PROCESS,ledger);
        Field f=PodMediaMemoryAccounting.class.getDeclaredField("process");f.setAccessible(true);Object previous=f.get(null);f.set(null,memory);
        try{
            MediaCodecAdapter codec=new PodVideoCodecAdapters(c->{assertEquals(1_000_000,ledger.held);return fake(()->assertEquals(1_000_000,ledger.held));},"audio",()->2).createAdapter(config("audio/mp4a-latm"));
            assertEquals(0,PodVideoCodecGate.PROCESS.live());
            try{new PodVideoCodecAdapters(c->{fail("factory must not run");return null;},"audio",()->1).createAdapter(config("audio/mp4a-latm"));fail();}catch(PodVideoCodecGate.AdmissionDenied expected){}
            codec.release();codec.release();assertEquals(0,ledger.held);
        }finally{f.set(null,previous);}
    }
    @Test public void keyframeDenialKeepsPinnedPixelsUntilTheirActualEviction()throws Exception{
        Ledger ledger=new Ledger(8192);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(PodVideoCodecGate.PROCESS,ledger);
        Field f=PodMediaMemoryAccounting.class.getDeclaredField("process");f.setAccessible(true);Object previous=f.get(null);f.set(null,memory);
        PodVideoKeyframes cache=new PodVideoKeyframes();
        try{
            android.graphics.Bitmap first=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);
            PodVideoKeyframes.Frame frame=cache.put("a",first,false);assertNotNull(frame);cache.pin(frame);assertEquals(8192,ledger.held);
            android.graphics.Bitmap second=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);
            assertNull(cache.put("b",second,false));assertTrue(second.isRecycled());assertFalse(first.isRecycled());
            cache.trim();assertEquals(8192,ledger.held);cache.unpin(frame);cache.trim();assertTrue(first.isRecycled());assertEquals(0,ledger.held);
        }finally{cache.close();f.set(null,previous);}
    }
    @Test public void deferredSurfaceAttachDoesNotCrashOrRetireTheSelectedSession()throws Exception{
        android.content.Context context=androidx.test.core.app.ApplicationProvider.getApplicationContext();
        context.getResources().getDisplayMetrics().widthPixels=466;context.getResources().getDisplayMetrics().heightPixels=466;
        Ledger ledger=new Ledger(22_000_000);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(PodVideoCodecGate.PROCESS,ledger);
        Field process=PodMediaMemoryAccounting.class.getDeclaredField("process");process.setAccessible(true);Object previous=process.get(null);process.set(null,memory);
        PodMediaMemoryBudget.Reservation busy=memory.tryReserve(new PodMediaMemoryBudget.Owner(-1,0,PodMediaMemoryBudget.Priority.CURRENT,PodMediaMemoryBudget.Kind.COMPRESSED_BUFFER),22_000_000);
        PodVideoSessionServices service=new PodVideoSessionServices(context,new PodVideoDecoderBudget(3));
        try{
            Class<?> type=Class.forName("dev.podjs.runtime.PodVideoSessionServices$Session");Constructor<?> ctor=type.getDeclaredConstructor(PodVideoSessionServices.class,String.class);ctor.setAccessible(true);
            Object session=ctor.newInstance(service,"selected");Field slots=PodVideoSessionServices.class.getDeclaredField("sessions");slots.setAccessible(true);Map<String,Object> map=(Map<String,Object>)slots.get(service);map.put("selected",session);
            android.widget.FrameLayout host=new android.widget.FrameLayout(context);service.attachToParent(host);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertSame(session,map.get("selected"));assertEquals(0,host.getChildCount());memory.release(busy);
            service.attachToParent(host);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(2,host.getChildCount());assertEquals(466L*466*12,ledger.held);
        }finally{memory.release(busy);service.close();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();process.set(null,previous);}
        assertEquals(0,ledger.held);
    }
    @Test public void codecEstimateIncludesAlignmentAndDoesNotOverflow(){
        MediaFormat media=new MediaFormat();assertEquals(6_000_000,PodVideoCodecAdapters.estimatedVideoBytes(media));
        media.setInteger(MediaFormat.KEY_WIDTH,320);media.setInteger(MediaFormat.KEY_HEIGHT,480);assertEquals(2_000_000L+320*512*12,PodVideoCodecAdapters.estimatedVideoBytes(media));
        media.setInteger(MediaFormat.KEY_WIDTH,Integer.MAX_VALUE);media.setInteger(MediaFormat.KEY_HEIGHT,Integer.MAX_VALUE);assertEquals(Long.MAX_VALUE,PodVideoCodecAdapters.estimatedVideoBytes(media));
    }
    @Test public void legacySurfaceIsReservedBeforeItsFirstVisibleBounds()throws Exception{
        android.content.Context context=androidx.test.core.app.ApplicationProvider.getApplicationContext();
        Ledger ledger=new Ledger(1);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(PodVideoCodecGate.PROCESS,ledger);
        Field process=PodMediaMemoryAccounting.class.getDeclaredField("process");process.setAccessible(true);Object previous=process.get(null);process.set(null,memory);
        PodVideoServices service=new PodVideoServices(context,new PodVideoDecoderBudget(3));android.widget.FrameLayout host=new android.widget.FrameLayout(context);
        try{
            service.attachToParent(host);org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            String[] error={null};service.dispatch("video.setBounds",new org.json.JSONObject().put("width",233).put("height",233).put("visible",true),new PodVideoServices.Callback(){public void complete(org.json.JSONObject value){org.junit.Assert.fail("visible surface allocated without bytes");}public void fail(String code,String message){error[0]=code;}});
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals("decoder_admission_deferred",error[0]);
            Field view=PodVideoServices.class.getDeclaredField("videoView");view.setAccessible(true);assertEquals(android.view.View.GONE,((android.view.View)view.get(service)).getVisibility());assertEquals(0,ledger.held);
        }finally{service.close();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();process.set(null,previous);}
    }
    @Test public void legacySurfaceOutlivesPlayerRetirementAndReturnsOnceOnViewClose()throws Exception{
        android.content.Context context=androidx.test.core.app.ApplicationProvider.getApplicationContext();
        Ledger ledger=new Ledger(8_000_000);PodMediaMemoryAccounting memory=new PodMediaMemoryAccounting(PodVideoCodecGate.PROCESS,ledger);
        Field process=PodMediaMemoryAccounting.class.getDeclaredField("process");process.setAccessible(true);Object previous=process.get(null);process.set(null,memory);
        PodVideoServices service=new PodVideoServices(context,new PodVideoDecoderBudget(3));
        try{
            service.attachToParent(new android.widget.FrameLayout(context));org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(service.ensureSurfaceMemory());assertTrue(service.ensureSurfaceMemory());assertTrue(ledger.held>0);long held=ledger.held;
            Method retire=PodVideoServices.class.getDeclaredMethod("releasePlayer");retire.setAccessible(true);retire.invoke(service);assertEquals(held,ledger.held);
            service.close();service.close();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(0,ledger.held);assertEquals(1,ledger.returns);
        }finally{service.close();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();process.set(null,previous);}
    }
}
