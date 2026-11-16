package dev.podjs.runtime;

import static org.junit.Assert.*;
import static dev.podjs.runtime.PodVideoGeometryRecoveryTest.*;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.*;
import android.widget.FrameLayout;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

/** Exercises real cache-overlay handoff scheduling without opening any media. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoCachedHandoffRecoveryTest {
 static void cached(Fixture f,Object s,String binding)throws Exception{
  f.set(s,"autoplay",true);f.set(s,"frameBinding",binding);
  PodVideoKeyframes frames=(PodVideoKeyframes)field(PodVideoSessionServices.class,"keyframes").get(f.service);
  frames.put(binding,Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888));f.invoke(s,"revealKeyframe");assertNotNull(f.get(s,"cachedFrame"));
 }
 static ExoPlayer player(Fixture f,Object s)throws Exception{return (ExoPlayer)f.get(s,"player");}
 @SuppressWarnings("unchecked") static void queueCompletedPaint(Texture texture)throws Exception{
  Method capture=ViewTreeObserver.class.getDeclaredMethod("captureFrameCommitCallbacks");capture.setAccessible(true);
  List<Runnable> callbacks=(List<Runnable>)capture.invoke(texture.getViewTreeObserver());assertEquals(1,callbacks.size());
  callbacks.forEach(Runnable::run);
  // Leave Session's completion queued so a reset invalidates an already
  // successful receipt, rather than merely cancelling an unarmed wait.
  Shadows.shadowOf(Looper.getMainLooper()).runOneTask();
 }
 @Test public void detachedRetainedSessionRetriesHandoffAndResumesOnForeground()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);cached(f,s,"resume");f.set(s,"backgroundPlayback",true);
   f.service.lifecycle(false);idle(0);assertSame(s,f.slots.get("current"));f.texture(s).attached=false;
   f.invoke(s,"confirmGeometry");f.invoke(s,"finishDecodedFrame");idle(0);
   assertNull("synchronous completion must clear the returned handle",f.get(s,"frameCompletionCancel"));assertNotNull(f.get(s,"frameCompletionRetry"));
   f.texture(s).attached=true;f.service.lifecycle(true);idle(17);assertEquals(1,f.texture(s).commit());assertTrue(f.ready(s));
   idle(33);assertEquals(1,f.texture(s).commit());assertNull(f.get(s,"cachedFrame"));assertNull(f.get(s,"keyframeView"));assertTrue(player(f,s).getPlayWhenReady());
   assertNull(f.get(s,"frameCompletionCancel"));assertNull(f.get(s,"frameCompletionRetry"));
  }
 }
 @Test public void asynchronousTimeoutRetriesWithoutDiscardingSafeCachedPixels()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);cached(f,s,"timeout");f.invoke(s,"finishDecodedFrame");idle(250);
   assertNotNull(f.get(s,"cachedFrame"));assertFalse(player(f,s).getPlayWhenReady());assertNull(f.get(s,"frameCompletionCancel"));assertNotNull(f.get(s,"frameCompletionRetry"));
   idle(50);assertEquals(1,f.texture(s).commit());assertNull(f.get(s,"cachedFrame"));assertTrue(player(f,s).getPlayWhenReady());
  }
 }
 @Test public void queuedOldGenerationReceiptCannotClearNewHandoff()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);cached(f,s,"old-generation");f.invoke(s,"finishDecodedFrame");queueCompletedPaint(f.texture(s));
   f.invoke(s,"resetPlayer");ExoPlayer fresh=new ExoPlayer.Builder(f.context).build();field(fresh.getClass(),"videoSize").set(fresh,new VideoSize(320,180));f.set(s,"player",fresh);f.set(s,"hasRenderedFirstFrame",true);f.set(s,"hasUpdatedFrame",true);
   cached(f,s,"new-generation");f.invoke(s,"finishDecodedFrame");Object pending=f.get(s,"frameCompletionCancel");idle(0);
   assertSame("old posted completion cannot erase the new handle",pending,f.get(s,"frameCompletionCancel"));assertNotNull(f.get(s,"cachedFrame"));assertFalse(fresh.getPlayWhenReady());
   assertEquals(1,f.texture(s).commit());assertNull(f.get(s,"cachedFrame"));assertTrue(fresh.getPlayWhenReady());
  }
 }
 @Test public void queuedOldTextureReceiptCannotClearReplacementHandoff()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);cached(f,s,"texture");Texture old=f.texture(s);f.invoke(s,"finishDecodedFrame");queueCompletedPaint(old);
   f.parent.removeView(old);f.set(s,"videoView",null);Method attach=f.type.getDeclaredMethod("attachViews",ViewGroup.class);attach.setAccessible(true);attach.invoke(s,f.parent);
   f.parent.removeView((View)f.get(s,"videoView"));Texture replacement=new Texture(f.context);f.parent.addView(replacement,new FrameLayout.LayoutParams(466,466));replacement.layout(0,0,466,466);f.set(s,"videoView",replacement);f.set(s,"hasUpdatedFrame",true);
   f.invoke(s,"finishDecodedFrame");Object pending=f.get(s,"frameCompletionCancel");idle(0);
   assertSame(pending,f.get(s,"frameCompletionCancel"));assertNotNull(f.get(s,"cachedFrame"));assertFalse(player(f,s).getPlayWhenReady());
   assertEquals(1,replacement.commit());assertNull(f.get(s,"cachedFrame"));assertTrue(player(f,s).getPlayWhenReady());
  }
 }
 @Test public void cancelledReceiptCannotClearANewWaitOnTheSameTexture()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);cached(f,s,"same-texture");f.invoke(s,"finishDecodedFrame");queueCompletedPaint(f.texture(s));
   f.invoke(s,"cancelFrameCompletion");f.invoke(s,"finishDecodedFrame");Object pending=f.get(s,"frameCompletionCancel");idle(0);
   assertSame(pending,f.get(s,"frameCompletionCancel"));assertNotNull(f.get(s,"cachedFrame"));assertFalse(player(f,s).getPlayWhenReady());
   assertEquals(1,f.texture(s).commit());assertNull(f.get(s,"cachedFrame"));assertTrue(player(f,s).getPlayWhenReady());
  }
 }
 @Test public void resetRemovesScheduledRetryAndCannotRestartAReleasedPlayer()throws Exception{
  try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);cached(f,s,"reset");f.texture(s).attached=false;f.invoke(s,"finishDecodedFrame");idle(0);assertNotNull(f.get(s,"frameCompletionRetry"));
   f.invoke(s,"resetPlayer");f.texture(s).attached=true;idle(1500);
   assertNull(f.get(s,"frameCompletionCancel"));assertNull(f.get(s,"frameCompletionRetry"));assertNull(f.get(s,"cachedFrame"));assertNull(f.get(s,"player"));assertEquals(0,f.texture(s).commit());
  }
 }
 @Test public void successfulHandoffPreservesAutoplayAndBackgroundPermission()throws Exception{
  for(boolean autoplay:new boolean[]{false,true})for(boolean background:new boolean[]{false,true})try(Fixture f=new Fixture()){
   Object s=f.slot("current",false);cached(f,s,"permission");f.set(s,"autoplay",autoplay);f.set(s,"backgroundPlayback",background);f.service.lifecycle(false);idle(0);
   f.invoke(s,"finishDecodedFrame");assertEquals(1,f.texture(s).commit());assertNull(f.get(s,"cachedFrame"));assertEquals(autoplay&&background,player(f,s).getPlayWhenReady());
  }
 }
}
