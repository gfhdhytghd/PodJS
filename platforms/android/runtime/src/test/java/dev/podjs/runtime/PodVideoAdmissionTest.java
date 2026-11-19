package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.media.MediaFormat;
import android.os.Looper;
import android.widget.FrameLayout;
import androidx.test.core.app.ApplicationProvider;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Production listener/adapter regression for asynchronous admission versus promotion. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoAdmissionTest {
    static Field field(Class<?> type,String name)throws Exception {
        Field result=type.getDeclaredField(name);result.setAccessible(true);return result;
    }
    static class Reply implements PodVideoSessionServices.Callback {
        String error;JSONObject value,changed;
        public void complete(JSONObject value){this.value=value;}
        public void fail(String code,String message){error=code;}
        public void stateChanged(JSONObject value){changed=value;}
    }
    static void call(PodVideoSessionServices service,String name,JSONObject args,Reply reply)throws Exception {
        Method method=PodVideoSessionServices.class.getDeclaredMethod(name,JSONObject.class,PodVideoSessionServices.Callback.class);
        method.setAccessible(true);method.invoke(service,args,reply);
    }
    static class Fixture implements AutoCloseable {
        final Context context=ApplicationProvider.getApplicationContext();
        final PodVideoDecoderBudget budget=new PodVideoDecoderBudget(3);
        final PodVideoCodecGate gate=PodVideoCodecGate.PROCESS;
        final List<PodVideoCodecGate.Permit> permits=new ArrayList<>();
        final PodVideoSessionServices service;
        final Reply prepare=new Reply();
        final JSONObject bounds=new JSONObject().put("width",233).put("height",233);
        final Object session;final ExoPlayer player;final Player.Listener listener;
        Fixture()throws Exception {
            android.app.ActivityManager.MemoryInfo memory=new android.app.ActivityManager.MemoryInfo();
            memory.availMem=1024L*1024*1024;memory.threshold=128L*1024*1024;
            Shadows.shadowOf((android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(memory);
            service=new PodVideoSessionServices(context,budget);service.attachToParent(new FrameLayout(context));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(budget.acquire()); // Existing current's logical lease.
            java.io.File media=new java.io.File((java.io.File)field(PodVideoSessionServices.class,"mediaRoot").get(service),"admission.mp4");
            media.getParentFile().mkdirs();media.createNewFile();
            call(service,"prepare",new JSONObject().put("sessionId","incoming").put("url","admission.mp4").put("prewarm",true).put("bounds",bounds),prepare);
            session=((Map<?,?>)field(PodVideoSessionServices.class,"sessions").get(service)).get("incoming");assertNotNull(session);
            field(session.getClass(),"profile").set(session,new PodVideoDecoderCapabilities.Profile("video/avc",240,240));
            player=(ExoPlayer)get("player");assertNotNull(player);
            Object listeners=field(player.getClass(),"listeners").get(player);Player.Listener found=null;
            for(Object holder:(Iterable<?>)field(listeners.getClass(),"listeners").get(listeners)) {
                Object candidate=field(holder.getClass(),"listener").get(holder);
                if(candidate.getClass().getName().startsWith("dev.podjs.runtime.PodVideoSessionServices$"))found=(Player.Listener)candidate;
            }
            listener=found;assertNotNull(listener);
        }
        Object get(String name)throws Exception{return field(session.getClass(),name).get(session);}
        void promote()throws Exception {
            Reply reply=new Reply();call(service,"promote",new JSONObject().put("sessionId","incoming").put("adoptPreparing",true).put("autoplay",false).put("bounds",bounds),reply);
            assertNotNull(reply.value);assertEquals(false,get("prewarming"));
        }
        void fill(){
            permits.add(gate.acquire("cover",0));permits.add(gate.acquire("prefix",0));permits.add(gate.acquire("existing-current",2));
            for(Object permit:permits)assertNotNull(permit);assertEquals(3,gate.live());
        }
        MediaCodecAdapter.Configuration config(){return MediaCodecAdapter.Configuration.createForVideoDecoding(null,
            MediaFormat.createVideoFormat("video/avc",240,240),new Format.Builder().setSampleMimeType("video/avc").build(),null,null);}
        Throwable denial()throws Exception {
            fill();
            try {new PodVideoCodecAdapters(config->{throw new AssertionError("denied before real codec factory");},"incoming",()->1).createAdapter(config());fail();}
            catch(java.io.IOException denied){assertTrue(PodVideoCodecGate.isAdmissionDenied(denied));assertFalse(PodVideoCodecGate.isFactoryFailure(denied));return denied;}
            throw new AssertionError();
        }
        void deliver(Throwable cause){listener.onPlayerError(new PlaybackException("init refused",cause,PlaybackException.ERROR_CODE_DECODER_INIT_FAILED));}
        void unchangedCapacity(){assertEquals(3,new PodVideoDecoderCapabilities(context).cachedDeviceLimit(3));assertEquals(3,budget.limit());}
        void assertDeferred()throws Exception {
            assertSame(player,get("player"));assertEquals(true,get("admissionDeferred"));assertNull(get("errorCode"));
            assertNotEquals("error",get("state"));assertNull(prepare.error);assertTrue(prepare.changed.getBoolean("decoderAdmissionDeferred"));unchangedCapacity();
        }
        void free(){for(PodVideoCodecGate.Permit permit:permits)permit.close();permits.clear();}
        public void close(){free();service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(0,gate.live());}
    }
    @Test public void permanentPhysicalContentionEndsTheSelectedGeneration() throws Exception {
        try(Fixture f=new Fixture()){
            Throwable denied=f.denial();f.promote();f.deliver(denied);
            f.assertDeferred();
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(4100));
            ((Runnable)f.get("admissionRetry")).run();
            System.out.println("PERFVIDEO session after 4100 ms of contention: "+f.get("state"));
            assertEquals("error",f.get("state"));
            assertEquals("resource_exhausted",f.get("errorCode"));
            assertEquals(3,f.gate.live());
        }
    }
    @Test public void optionalByteRefusalFinishesPrewarmWithoutRetryingOrLearningCapacity()throws Exception {
        try(Fixture f=new Fixture()){
            f.deliver(new PodVideoCodecGate.ByteAdmissionDenied(PodVideoCodecGate.PREWARM));
            assertEquals("decoder_admission_deferred",f.prepare.error);assertNull(f.get("player"));assertNull(f.get("admissionRetry"));f.unchangedCapacity();
        }
    }
    @Test public void byteRefusalDeliveredAfterPromotionKeepsTheSelectedOwner()throws Exception {
        try(Fixture f=new Fixture()){
            Object generation=f.get("generation");f.promote();f.deliver(new PodVideoCodecGate.ByteAdmissionDenied(PodVideoCodecGate.PREWARM));
            f.assertDeferred();assertSame(f.player,f.get("player"));assertEquals(generation,f.get("generation"));
        }
    }
    @Test public void deniedWarmPromotedBeforeErrorKeepsCurrentPlayerAndGeneration()throws Exception {
        try(Fixture f=new Fixture()) {
            Throwable denied=f.denial();Object generation=f.get("generation");f.promote();f.deliver(denied);f.assertDeferred();
            assertEquals(generation,f.get("generation"));assertEquals(false,f.get("prewarming"));
        }
    }
    @Test public void deniedWarmDoesNotFailCallbackOrPersistHardwareCapacity()throws Exception {
        try(Fixture f=new Fixture()){f.deliver(f.denial());f.assertDeferred();assertEquals(true,f.get("prewarming"));}
    }
    @Test public void deferredWarmCanStillBePromotedBeforeRetry()throws Exception {
        try(Fixture f=new Fixture()){f.deliver(f.denial());f.promote();f.assertDeferred();assertEquals(false,f.get("prewarming"));}
    }
    @Test public void retryUsesSamePlayerOnlyAfterPhysicalReleaseAndHonoursPause()throws Exception {
        try(Fixture f=new Fixture()) {
            f.deliver(f.denial());f.promote();Runnable retry=(Runnable)f.get("admissionRetry");
            retry.run();assertEquals(true,f.get("admissionDeferred"));assertSame(f.player,f.get("player"));
            f.free();call(f.service,"pause",new JSONObject().put("sessionId","incoming"),new Reply());retry.run();
            assertEquals(false,f.get("admissionDeferred"));assertNull(f.get("admissionRetry"));assertSame(f.player,f.get("player"));
            assertFalse(f.player.getPlayWhenReady());f.unchangedCapacity();
        }
    }
    @Test public void deferredRetryWaitsForLifecycleResume()throws Exception {
        try(Fixture f=new Fixture()) {
            f.deliver(f.denial());f.free();Runnable retry=(Runnable)f.get("admissionRetry");
            field(PodVideoSessionServices.class,"lifecyclePaused").setBoolean(f.service,true);retry.run();assertEquals(true,f.get("admissionDeferred"));
            field(PodVideoSessionServices.class,"lifecyclePaused").setBoolean(f.service,false);retry.run();assertEquals(false,f.get("admissionDeferred"));
        }
    }
    @Test public void admittedBackgroundCurrentCanRetryWithoutChangingUserIntent()throws Exception {
        try(Fixture f=new Fixture()) {
            f.deliver(f.denial());f.promote();f.free();
            field(f.session.getClass(),"backgroundPlayback").setBoolean(f.session,true);
            field(f.session.getClass(),"autoplay").setBoolean(f.session,true);
            field(PodVideoSessionServices.class,"lifecyclePaused").setBoolean(f.service,true);
            ((Runnable)f.get("admissionRetry")).run();assertEquals(false,f.get("admissionDeferred"));
            assertTrue(f.player.getPlayWhenReady());assertSame(f.player,f.get("player"));f.unchangedCapacity();
        }
    }
    @Test public void releasedSessionCannotResurrectFromQueuedRetryOrLateError()throws Exception {
        try(Fixture f=new Fixture()) {
            Throwable denied=f.denial();f.deliver(denied);Runnable retry=(Runnable)f.get("admissionRetry");
            call(f.service,"release",new JSONObject().put("sessionId","incoming"),new Reply());f.free();retry.run();f.deliver(denied);
            assertNull(f.get("player"));assertNull(f.get("admissionRetry"));assertEquals(false,f.get("admissionDeferred"));f.unchangedCapacity();
        }
    }
    @Test public void actualCodecFactoryFailureStillRecordsHardwareLimit()throws Exception {
        try(Fixture f=new Fixture()) {
            Throwable failure=null;
            try{new PodVideoCodecAdapters(config->{throw new java.io.IOException("actual_factory_failure");},"incoming",()->1).createAdapter(f.config());fail();}
            catch(java.io.IOException expected){failure=expected;}
            assertTrue(PodVideoCodecGate.isFactoryFailure(failure));assertFalse(PodVideoCodecGate.isAdmissionDenied(failure));
            f.deliver(failure);assertEquals(1,new PodVideoDecoderCapabilities(f.context).cachedDeviceLimit(3));assertEquals(1,f.budget.limit());assertEquals("decode_error",f.prepare.error);
        }
    }
    @Test public void fallbackDecoderRefusalCannotBecomeAnOrdinaryFactoryFailure()throws Exception {
        try(Fixture f=new Fixture()) {
            Class<androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException> type=androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException.class;
            Format format=new Format.Builder().setSampleMimeType("video/avc").build();
            Exception actualFailure=new java.io.IOException("first_codec_failure");actualFailure.addSuppressed(new PodVideoCodecGate.FactoryFailure());
            Object first=new androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException(format,actualFailure,false,1);
            Object fallback=new androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException(format,f.denial(),false,1);
            Method copy=type.getDeclaredMethod("copyWithFallbackException",type);copy.setAccessible(true);
            Throwable combined=(Throwable)copy.invoke(first,fallback);assertTrue(PodVideoCodecGate.isFactoryFailure(combined));assertTrue(PodVideoCodecGate.isAdmissionDenied(combined));
            f.promote();f.deliver(combined);f.assertDeferred();
        }
    }
    @Test public void admissionMarkerSurvivesMedia3SuppressedAndCyclicCauses() {
        Exception outer=new Exception("wrapper"),branch=new Exception("fallback",outer);outer.addSuppressed(branch);
        branch.addSuppressed(new PodVideoCodecGate.AdmissionDenied(1));assertTrue(PodVideoCodecGate.isAdmissionDenied(outer));
        assertEquals("decoder_admission_deferred",PodVideoAnalytics.errorCode(new PlaybackException("wrapped",outer,PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)));
    }
}
