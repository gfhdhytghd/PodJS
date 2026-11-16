package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.media.MediaFormat;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.source.*;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;

/** Real production adapter rejection and legacy/service listeners. The player
 * proxy records prepare/source mutations without opening the local fixture. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoLegacyAdmissionTest {
    static Field field(Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static Object get(Object o,String name)throws Exception{return field(o.getClass(),name).get(o);}
    static void set(Object o,String name,Object value)throws Exception{field(o.getClass(),name).set(o,value);}
    static void idle(long ms){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));}
    static class Reply implements PodVideoServices.Callback {
        String error;int completed;JSONObject value;final List<JSONObject> changed=new ArrayList<>();
        public void complete(JSONObject value){this.value=value;completed++;}
        public void fail(String code,String message){error=code;}
        public void stateChanged(JSONObject value){changed.add(value);}
    }
    static class Fixture implements AutoCloseable {
        final Context context=ApplicationProvider.getApplicationContext();
        final int logicalLimit;
        final PodVideoDecoderBudget budget;
        final PodVideoServices host;
        final PodVideoPlaybackService owner=new PodVideoPlaybackService();
        final ExoPlayer real=new ExoPlayer.Builder(context).build();
        final ExoPlayer player;
        final Reply reply=new Reply();
        final List<PodVideoCodecGate.Permit> permits=new ArrayList<>();
        int prepares,sources,stops,clears;MediaItem item;Player.Listener listener;
        Fixture()throws Exception {this(3);}
        Fixture(int limit)throws Exception {
            logicalLimit=limit;budget=new PodVideoDecoderBudget(limit);host=new PodVideoServices(context,budget);
            host.attachToParent(new FrameLayout(context));idle(0);
            player=(ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class[]{ExoPlayer.class},(proxy,method,args)->{
                switch(method.getName()){
                    case "prepare":prepares++;return null;
                    case "setMediaSource":sources++;item=((MediaSource)args[0]).getMediaItem();return null;
                    case "getCurrentMediaItem":return item;
                    case "stop":stops++;break;
                    case "clearMediaItems":clears++;item=null;break;
                }
                try{return method.invoke(real,args);}catch(InvocationTargetException error){throw error.getCause();}
            });
            set(owner,"player",player);set(host,"player",player);set(host,"playbackService",owner);
            set(host,"audioPolicy",new PodMediaAudioPolicy(context,player));set(host,"audioStop",new PodVideoAudioStop());
            set(host,"analytics",new PodVideoAnalytics());set(host,"prepareCallback",reply);set(host,"prepareSequence",17);set(host,"autoplay",true);
            configure("one");
            ((View)get(host,"videoView")).setVisibility(View.VISIBLE);((View)get(host,"videoBackground")).setVisibility(View.VISIBLE);
            ShadowLog.clear();
        }
        void configure(String key)throws Exception {
            MediaSource source=new DefaultMediaSourceFactory(context).createMediaSource(MediaItem.fromUri("file:///offline-"+key+".mp4"));
            Method configure=PodVideoServices.class.getDeclaredMethod("configurePlayer",JSONObject.class,MediaSource.class);configure.setAccessible(true);
            configure.invoke(host,new JSONObject().put("url","offline-"+key+".mp4"),source);
            listener=(Player.Listener)get(host,"playerListener");
        }
        PodVideoAdmissionRetry recovery()throws Exception{return (PodVideoAdmissionRetry)get(owner,"admissionRetry");}
        Player.Listener serviceListener()throws Exception{return (Player.Listener)get(recovery(),"listener");}
        void command(String name)throws Exception {
            Reply r=new Reply();host.dispatch("video."+name,new JSONObject(),r);idle(0);assertNull(r.error);
        }
        Throwable denial()throws Exception {
            PodVideoCodecGate gate=PodVideoCodecGate.PROCESS;
            permits.add(gate.acquire("slow-cover",0));permits.add(gate.acquire("slow-prefix",0));permits.add(gate.acquire("other-current",2));
            for(Object p:permits)assertNotNull(p);assertEquals(3,gate.live());
            AtomicReference<Throwable> result=new AtomicReference<>();
            Thread worker=new Thread(()->{try{
                MediaFormat format=MediaFormat.createVideoFormat("video/avc",240,240);
                new PodVideoCodecAdapters(config->{throw new AssertionError("denied before actual codec factory");},"legacy",()->2).createAdapter(
                    MediaCodecAdapter.Configuration.createForVideoDecoding(null,format,new Format.Builder().setSampleMimeType("video/avc").build(),null,null));
            }catch(Throwable error){result.set(error);}});worker.start();
            long deadline=System.nanoTime()+2_000_000_000L;
            while(worker.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<deadline)Thread.yield();
            assertEquals(Thread.State.TIMED_WAITING,worker.getState());ShadowSystemClock.advanceBy(Duration.ofMillis(2100));
            synchronized(gate){gate.notifyAll();}worker.join(1000);assertFalse(worker.isAlive());
            assertTrue(PodVideoCodecGate.isAdmissionDenied(result.get()));return result.get();
        }
        PlaybackException error(Throwable denied){return new PlaybackException("decoder admission deferred",denied,PlaybackException.ERROR_CODE_DECODER_INIT_FAILED);}
        void deliver(Throwable denied){listener.onPlayerError(error(denied));}
        void ready()throws Exception {listener.onPlaybackStateChanged(Player.STATE_READY);listener.onRenderedFirstFrame();assertNull(get(host,"prepareCallback"));}
        void free(){for(PodVideoCodecGate.Permit p:permits)p.close();permits.clear();}
        void retained()throws Exception {
            assertSame(player,get(host,"player"));assertSame(owner,get(host,"playbackService"));assertEquals(17,get(host,"prepareSequence"));
            assertNotEquals("error",get(host,"state"));assertNull(get(host,"playbackErrorCode"));assertNull(reply.error);
            assertEquals(View.VISIBLE,((View)get(host,"videoView")).getVisibility());
            assertEquals(0,stops);assertEquals(0,clears);assertEquals(1,sources);
            assertEquals(3,new PodVideoDecoderCapabilities(context).cachedDeviceLimit(3));assertEquals(logicalLimit,budget.limit());
            for(JSONObject value:reply.changed)assertNotEquals("error",value.optString("state"));noFailure();
        }
        void noFailure(){assertFalse(ShadowLog.getLogs().stream().anyMatch(row->row.msg.contains("code=error_page")));}
        public void close(){
            free();host.close();idle(0);owner.cancelAdmissionRecovery();owner.observeVideo(null);real.release();assertEquals(0,PodVideoCodecGate.PROCESS.live());
        }
    }
    @Test public void preparingLegacyKeepsSourceGenerationPlayerAndPendingCallback()throws Exception {
        try(Fixture f=new Fixture()){
            Object source=get(f.host,"frameSource");f.deliver(f.denial());f.retained();assertSame(source,get(f.host,"frameSource"));assertSame(f.reply,get(f.host,"prepareCallback"));
            assertTrue(f.recovery().deferred());assertEquals(0,f.reply.completed);
        }
    }
    @Test public void retryWaitsForActualPermitAndPreparesSameSourceExactlyOnce()throws Exception {
        try(Fixture f=new Fixture()){
            f.deliver(f.denial());PodVideoAdmissionRetry recovery=f.recovery();Runnable pending=(Runnable)get(recovery,"pending");
            idle(500);assertEquals(1,f.prepares);f.retained();assertEquals(3,PodVideoCodecGate.PROCESS.live());
            f.free();idle(100);assertEquals(2,f.prepares);f.retained();assertFalse(recovery.deferred());assertTrue(f.player.getPlayWhenReady());
            pending.run();idle(200);assertEquals(2,f.prepares);
        }
    }
    @Test public void denialAfterPreparationAndFirstFrameIsNotAPlaybackFailure()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.deliver(f.denial());f.retained();assertTrue((Boolean)get(f.host,"hasRenderedFirstFrame"));assertEquals(1,f.reply.completed);
            f.free();idle(100);assertEquals(2,f.prepares);assertEquals(1,f.reply.completed);
        }
    }
    @Test public void userPauseCancelsPendingRetryUntilExplicitPlay()throws Exception {
        try(Fixture f=new Fixture()){
            f.deliver(f.denial());f.command("pause");f.free();idle(500);assertEquals(1,f.prepares);assertNull(get(f.recovery(),"pending"));assertFalse(f.player.getPlayWhenReady());
            f.command("play");idle(100);assertEquals(2,f.prepares);assertTrue(f.player.getPlayWhenReady());f.retained();
        }
    }
    @Test public void denialDeliveredAfterUserPauseCannotSchedulePreparation()throws Exception {
        try(Fixture f=new Fixture()){
            Throwable denied=f.denial();f.command("pause");f.deliver(denied);f.free();idle(500);
            assertEquals(1,f.prepares);assertNull(get(f.recovery(),"pending"));f.retained();assertEquals("paused",get(f.host,"state"));
        }
    }
    @Test public void lifecyclePauseDefersForegroundRetryUntilExplicitResume()throws Exception {
        try(Fixture f=new Fixture()){
            f.deliver(f.denial());f.host.pauseForLifecycle();idle(0);f.free();idle(500);assertEquals(1,f.prepares);assertFalse(f.player.getPlayWhenReady());
            f.command("play");idle(100);assertEquals(2,f.prepares);f.retained();
        }
    }
    @Test public void authorizedBackgroundLifecycleDoesNotDisableRecovery()throws Exception {
        try(Fixture f=new Fixture()){
            set(f.host,"backgroundPlayback",true);f.deliver(f.denial());f.host.pauseForLifecycle();idle(0);f.free();idle(100);
            assertEquals(2,f.prepares);assertTrue(f.player.getPlayWhenReady());f.retained();
        }
    }
    @Test public void backgroundHandoffKeepsQueuedRetryWithoutForegroundCallbacks()throws Exception {
        try(Fixture f=new Fixture()){
            set(f.host,"backgroundPlayback",true);f.ready();f.deliver(f.denial());PodVideoAdmissionRetry recovery=f.recovery();int changed=f.reply.changed.size();
            f.host.close();idle(0);assertNull(get(f.host,"player"));assertSame(recovery,f.recovery());
            f.free();idle(100);assertEquals(2,f.prepares);assertEquals(changed,f.reply.changed.size());assertEquals(0,f.stops);assertTrue(f.player.getPlayWhenReady());f.noFailure();
        }
    }
    @Test public void serviceListenerCanRecoverADenialArrivingAfterBackgroundHandoff()throws Exception {
        try(Fixture f=new Fixture()){
            set(f.host,"backgroundPlayback",true);f.ready();Throwable denied=f.denial();Player.Listener service=f.serviceListener();
            f.host.close();idle(0);service.onPlayerError(f.error(denied));assertTrue(f.recovery().deferred());
            f.free();idle(100);assertEquals(2,f.prepares);assertEquals(0,f.stops);assertNull(f.reply.error);f.noFailure();
        }
    }
    @Test public void mediaSessionPauseAndPlayControlBackgroundRecovery()throws Exception {
        try(Fixture f=new Fixture()){
            set(f.host,"backgroundPlayback",true);f.ready();f.deliver(f.denial());f.host.close();idle(0);
            f.player.pause();idle(0);f.free();idle(500);assertEquals(1,f.prepares);assertNull(get(f.recovery(),"pending"));
            f.player.play();idle(100);assertEquals(2,f.prepares);assertTrue(f.player.getPlayWhenReady());
        }
    }
    @Test public void stopCancelsRetryAndLateListenerCannotResurrectPlayer()throws Exception {
        try(Fixture f=new Fixture()){
            Throwable denied=f.denial();f.deliver(denied);PodVideoAdmissionRetry recovery=f.recovery();Runnable pending=(Runnable)get(recovery,"pending");Player.Listener service=f.serviceListener();
            f.command("stop");f.free();pending.run();service.onPlayerError(f.error(denied));f.deliver(denied);idle(500);
            assertNull(get(f.host,"player"));assertEquals("stopped",get(f.host,"state"));assertEquals(1,f.prepares);assertNull(get(recovery,"pending"));f.noFailure();
        }
    }
    @Test public void foregroundExitCancelsRetryAndLateCallbacks()throws Exception {
        try(Fixture f=new Fixture()){
            Throwable denied=f.denial();f.deliver(denied);Runnable pending=(Runnable)get(f.recovery(),"pending");
            f.host.close();idle(0);f.free();pending.run();f.deliver(denied);idle(500);assertNull(get(f.host,"player"));assertEquals(1,f.prepares);f.noFailure();
        }
    }
    @Test public void newSourceGenerationRejectsOldRetryAndOldError()throws Exception {
        try(Fixture f=new Fixture()){
            Throwable denied=f.denial();f.deliver(denied);Runnable pending=(Runnable)get(f.recovery(),"pending");Player.Listener old=f.listener;
            set(f.host,"prepareSequence",18);f.configure("two");Object source=get(f.host,"frameSource");f.free();pending.run();old.onPlayerError(f.error(denied));idle(300);
            assertEquals(2,f.prepares);assertEquals(2,f.sources);assertSame(f.player,get(f.host,"player"));assertSame(source,get(f.host,"frameSource"));assertEquals(18,get(f.host,"prepareSequence"));assertNull(f.reply.error);f.noFailure();
        }
    }
    @Test public void initialAutoplayFalseStillRetriesPreparationWithoutStartingPlayback()throws Exception {
        try(Fixture f=new Fixture()){
            set(f.host,"autoplay",false);f.configure("paused");f.deliver(f.denial());f.free();idle(100);
            assertEquals(3,f.prepares);assertFalse(f.player.getPlayWhenReady());assertNull(f.reply.error);f.noFailure();
        }
    }
    @Test public void backgroundPauseBeforeLateDenialDoesNotScheduleRecovery()throws Exception {
        try(Fixture f=new Fixture()){
            set(f.host,"backgroundPlayback",true);f.ready();Throwable denied=f.denial();Player.Listener listener=f.serviceListener();
            f.host.close();idle(0);f.player.pause();idle(0);listener.onPlayerError(f.error(denied));f.free();idle(500);
            assertEquals(1,f.prepares);assertNull(get(f.recovery(),"pending"));assertFalse(f.player.getPlayWhenReady());f.noFailure();
        }
    }
    @Test public void backgroundStopBeforeLateDenialCannotRestartSource()throws Exception {
        try(Fixture f=new Fixture()){
            set(f.host,"backgroundPlayback",true);f.ready();Throwable denied=f.denial();Player.Listener listener=f.serviceListener();
            f.host.close();idle(0);listener.onPlaybackStateChanged(Player.STATE_IDLE);listener.onPlayerError(f.error(denied));f.free();idle(500);
            assertEquals(1,f.prepares);assertNull(get(f.recovery(),"pending"));f.noFailure();
        }
    }
    @Test public void retirementCancelsCompletedSourceRecovery()throws Exception {
        try(Fixture f=new Fixture()){
            f.ready();f.deliver(f.denial());f.command("retire");f.free();idle(500);
            assertEquals(1,f.prepares);assertNull(get(f.recovery(),"pending"));assertFalse(f.player.getPlayWhenReady());assertEquals("paused",get(f.host,"state"));f.noFailure();
        }
    }
    @Test public void serviceDestructionCancelsRetryBeforeReleasingPlayer()throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        org.robolectric.android.controller.ServiceController<PodVideoPlaybackService> controller=Robolectric.buildService(PodVideoPlaybackService.class).create();
        PodVideoPlaybackService service=controller.get();ExoPlayer original=(ExoPlayer)get(service,"player");int[] prepares={0},releases={0};
        MediaItem item=MediaItem.fromUri("file:///offline-service.mp4");
        ExoPlayer fake=(ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class[]{ExoPlayer.class},(proxy,method,args)->{
            if(method.getName().equals("prepare")){prepares[0]++;return null;}
            if(method.getName().equals("release")){releases[0]++;return null;}
            if(method.getName().equals("getCurrentMediaItem"))return item;
            Class<?> type=method.getReturnType();if(type==boolean.class)return true;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;return null;
        });
        try{
            set(service,"player",fake);PodVideoAdmissionRetry recovery=service.beginAdmissionRecovery(fake);recovery.initialized();recovery.defer();Runnable pending=(Runnable)get(recovery,"pending");
            controller.destroy();pending.run();idle(300);assertEquals(0,prepares[0]);assertEquals(1,releases[0]);assertNull(get(service,"admissionRetry"));
        }finally{if(original!=null)original.release();}
    }
    @Test public void capacityOneLegacyFallbackRecoversWithoutChangingTheCapacity()throws Exception {
        try(Fixture f=new Fixture(1)){
            f.deliver(f.denial());f.retained();f.free();idle(100);assertEquals(2,f.prepares);assertEquals(1,f.budget.limit());f.retained();
        }
    }
    @Test public void cancelledTimerCannotStealTheResumedSourceRetry()throws Exception {
        try(Fixture f=new Fixture()){
            f.deliver(f.denial());PodVideoAdmissionRetry recovery=f.recovery();Runnable old=(Runnable)get(recovery,"pending");
            f.command("pause");f.command("play");Runnable current=(Runnable)get(recovery,"pending");assertNotSame(old,current);
            old.run();assertSame(current,get(recovery,"pending"));assertEquals(1,f.prepares);f.free();idle(100);assertEquals(2,f.prepares);f.retained();
        }
    }
    @Test public void actualDecoderFailureStillUsesOrdinaryLegacyFailurePath()throws Exception {
        try(Fixture f=new Fixture()){
            f.deliver(new java.io.IOException("actual_codec_failure"));assertEquals("decode_error",f.reply.error);assertEquals("error",get(f.host,"state"));assertNull(get(f.host,"player"));
            assertEquals(View.GONE,((View)get(f.host,"videoView")).getVisibility());
            assertTrue(ShadowLog.getLogs().stream().anyMatch(row->row.msg.contains("code=error_page")));
        }
    }
}
