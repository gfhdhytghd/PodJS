package dev.podjs.runtime;
import android.content.*;
import android.os.Looper;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ServiceController;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodSessionPlaybackServiceTest {
 @Test public void serviceUsesExistingPlayerAndStopPreservesPauseIntent()throws Exception {
  Context context=ApplicationProvider.getApplicationContext();ExoPlayer player=new ExoPlayer.Builder(context).build();Object owner=new Object();AtomicBoolean paused=new AtomicBoolean();
  ServiceController<PodSessionPlaybackService> controller=Robolectric.buildService(PodSessionPlaybackService.class).create();
  try{
   PodSessionPlaybackService.attach(context,owner,player,()->{paused.set(true);player.pause();PodSessionPlaybackService.detach(context,owner);});
   controller.startCommand(0,1);assertSame(player,controller.get().onGetSession(null).getPlayer());
   java.lang.reflect.Field current=PodSessionPlaybackService.class.getDeclaredField("current");current.setAccessible(true);assertSame(player,current.get(null));
   PodSessionPlaybackService.detach(context,new Object());assertSame(player,current.get(null));
   player.pause();controller.get().commands.onPlayerInteractionFinished(controller.get().onGetSession(null),null,new androidx.media3.common.Player.Commands.Builder().add(androidx.media3.common.Player.COMMAND_PLAY_PAUSE).build());assertTrue(paused.get());assertNull(current.get(null));
   paused.set(false);PodSessionPlaybackService.attach(context,owner,player,()->{paused.set(true);player.pause();PodSessionPlaybackService.detach(context,owner);});controller.startCommand(0,2);
   controller.get().onStartCommand(new Intent(context,PodSessionPlaybackService.class).setAction("dev.podjs.runtime.STOP_SESSION_VIDEO"),0,2);
   assertTrue(paused.get());assertFalse(player.getPlayWhenReady());assertNull(current.get(null));
   controller.destroy();player.play();assertTrue(player.getPlayWhenReady());
  }finally{PodSessionPlaybackService.detach(context,owner);player.release();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
}
