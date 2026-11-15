package dev.podjs.runtime;

import android.content.Context;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class PodVideoBackgroundAnalyticsTest {
    private static void set(Object object,String name,Object value)throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    private static Object get(Object object,String name)throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static PodVideoServices host()throws Exception {
        Class<?> type=Class.forName("sun.misc.Unsafe");Field field=type.getDeclaredField("theUnsafe");field.setAccessible(true);
        Object unsafe=field.get(null);PodVideoServices host=(PodVideoServices)type.getMethod("allocateInstance",Class.class).invoke(unsafe,PodVideoServices.class);
        set(host,"statsWindowLogged",true);set(host,"context",ApplicationProvider.<Context>getApplicationContext());return host;
    }
    private static ExoPlayer player(List<String> actions,List<Object> removed) {
        return (ExoPlayer)Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),new Class[]{ExoPlayer.class},(proxy,method,args)->{
            actions.add(method.getName());if(method.getName().equals("removeListener"))removed.add(args[0]);
            Class<?> result=method.getReturnType();if(result==boolean.class)return true;if(result==int.class)return 0;if(result==long.class)return 0L;if(result==float.class)return 0f;return null;
        });
    }
    private static void detach(PodVideoServices host,boolean keep)throws Exception {
        Method method=PodVideoServices.class.getDeclaredMethod("detachPlayer",boolean.class);method.setAccessible(true);method.invoke(host,keep);
    }
    @Test public void backgroundHandoffKeepsServiceObserverUntilDecoderRelease()throws Exception {
        int before=PodVideoAnalytics.liveDecoderInstances();PodVideoAnalytics analytics=new PodVideoAnalytics();
        ServiceController<PodVideoPlaybackService> controller=Robolectric.buildService(PodVideoPlaybackService.class).create();PodVideoPlaybackService service=controller.get();
        List<String> actions=new ArrayList<>();List<Object> removed=new ArrayList<>();ExoPlayer player=player(actions,removed);
        try {
            set(service,"player",player);service.observeVideo(analytics);analytics.onVideoDecoderInitialized(null,"synthetic",0,0);
            PodVideoServices host=host();Player.Listener foregroundListener=new Player.Listener(){};
            set(host,"player",player);set(host,"analytics",analytics);set(host,"playbackService",service);set(host,"playerListener",foregroundListener);actions.clear();
            detach(host,true);
            assertEquals(before+1,PodVideoAnalytics.liveDecoderInstances());assertFalse(actions.contains("stop"));assertFalse(actions.contains("release"));assertFalse(actions.contains("removeAnalyticsListener"));
            assertSame(analytics,get(service,"videoAnalytics"));assertNull(get(host,"analytics"));assertNull(get(host,"playbackService"));assertTrue(removed.contains(foregroundListener));
            analytics.onVideoDecoderReleased(null,"synthetic");assertEquals(before,PodVideoAnalytics.liveDecoderInstances());
            analytics.onVideoDecoderInitialized(null,"synthetic",0,0);assertEquals(before+1,PodVideoAnalytics.liveDecoderInstances());
            System.out.println("BACKGROUND_COUNTER before_handoff="+(before+1)+" after_handoff="+PodVideoAnalytics.liveDecoderInstances()+" observer=service release_event=observed");
        } finally {controller.destroy();analytics.retireDecoder();}
        assertTrue(actions.contains("release"));assertEquals(before,PodVideoAnalytics.liveDecoderInstances());assertNull(get(service,"videoAnalytics"));
        analytics.onVideoDecoderInitialized(null,"synthetic",0,0);assertEquals(before,PodVideoAnalytics.liveDecoderInstances());
    }
    @Test public void stoppingForegroundPlaybackEndsServiceObservationAfterStop()throws Exception {
        int before=PodVideoAnalytics.liveDecoderInstances();PodVideoAnalytics analytics=new PodVideoAnalytics();
        ServiceController<PodVideoPlaybackService> controller=Robolectric.buildService(PodVideoPlaybackService.class).create();PodVideoPlaybackService service=controller.get();
        List<String> actions=new ArrayList<>();ExoPlayer player=player(actions,new ArrayList<>());
        try {
            set(service,"player",player);service.observeVideo(analytics);analytics.onVideoDecoderInitialized(null,"synthetic",0,0);
            PodVideoServices host=host();set(host,"player",player);set(host,"analytics",analytics);set(host,"playbackService",service);actions.clear();
            detach(host,false);assertTrue(actions.contains("stop"));assertTrue(actions.contains("clearMediaItems"));assertTrue(actions.contains("removeAnalyticsListener"));assertEquals(before,PodVideoAnalytics.liveDecoderInstances());assertNull(get(service,"videoAnalytics"));
        } finally {controller.destroy();analytics.retireDecoder();}
        assertEquals(before,PodVideoAnalytics.liveDecoderInstances());
    }
    @Test public void destroyingServiceCanRemoveObserverAfterRealPlayerRelease()throws Exception {
        int before=PodVideoAnalytics.liveDecoderInstances();PodVideoAnalytics analytics=new PodVideoAnalytics();
        ServiceController<PodVideoPlaybackService> controller=Robolectric.buildService(PodVideoPlaybackService.class).create();PodVideoPlaybackService service=controller.get();
        ExoPlayer player=new ExoPlayer.Builder(ApplicationProvider.<Context>getApplicationContext()).build();
        set(service,"player",player);service.observeVideo(analytics);analytics.onVideoDecoderInitialized(null,"synthetic",0,0);
        try {controller.destroy();}finally{analytics.retireDecoder();}
        assertEquals(before,PodVideoAnalytics.liveDecoderInstances());assertNull(get(service,"videoAnalytics"));
    }
}
