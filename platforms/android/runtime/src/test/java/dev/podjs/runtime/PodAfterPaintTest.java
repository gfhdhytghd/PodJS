package dev.podjs.runtime;
import android.os.*;
import android.view.View;
import java.time.Duration;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
public class PodAfterPaintTest {
 static class Commits implements PodAfterPaint.Commits {
  List<Runnable> callbacks=new ArrayList<>();int removed;
  public boolean available(){return true;}
  public Runnable next(Runnable callback){callbacks.add(callback);return ()->removed++;}
 }
 @Test public void twoCommitsPrecedeOneContinuation(){
  Commits commits=new Commits();List<Boolean> values=new ArrayList<>();
  PodAfterPaint.await(commits,new Handler(Looper.getMainLooper()),values::add);assertEquals(1,commits.callbacks.size());
  commits.callbacks.get(0).run();shadowOf(Looper.getMainLooper()).idle();assertTrue(values.isEmpty());assertEquals(2,commits.callbacks.size());
  commits.callbacks.get(1).run();shadowOf(Looper.getMainLooper()).idle();assertEquals(Arrays.asList(true),values);
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));commits.callbacks.get(1).run();shadowOf(Looper.getMainLooper()).idle();assertEquals(1,values.size());
 }
 @Test public void timeoutAndCancelNeverPretendToCommitOrDeliverTwice(){
  Handler main=new Handler(Looper.getMainLooper());Commits commits=new Commits();List<Boolean> values=new ArrayList<>();
  PodAfterPaint.await(commits,main,values::add);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(251));assertEquals(Arrays.asList(false),values);
  commits.callbacks.get(0).run();shadowOf(Looper.getMainLooper()).idle();assertEquals(1,values.size());
  Commits cancelled=new Commits();values.clear();Runnable cancel=PodAfterPaint.await(cancelled,main,values::add);cancel.run();shadowOf(Looper.getMainLooper()).idle();
  cancelled.callbacks.get(0).run();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));assertTrue(values.isEmpty());assertEquals(1,cancelled.removed);
 }
 @Test public void productionUnattachedViewReportsFallbackImmediately(){
  List<Boolean> values=new ArrayList<>();PodAfterPaint.await(new View(RuntimeEnvironment.getApplication()),new Handler(Looper.getMainLooper()),values::add);assertEquals(Arrays.asList(false),values);
 }
 @Test public void retainedDecodedTextureUsesFirstCommitAndTimeoutStillIsNotProof(){
  Commits commits=new Commits();List<Boolean> values=new ArrayList<>();List<Integer> observed=new ArrayList<>();
  PodAfterPaint.await(commits,new Handler(Looper.getMainLooper()),1,(frame,at)->observed.add(frame),values::add);
  assertTrue(values.isEmpty());commits.callbacks.get(0).run();shadowOf(Looper.getMainLooper()).idle();
  assertEquals(Arrays.asList(true),values);assertEquals(Arrays.asList(1),observed);assertEquals(1,commits.callbacks.size());
  values.clear();PodAfterPaint.await(new Commits(),new Handler(Looper.getMainLooper()),1,(frame,at)->{},values::add);
  shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(251));assertEquals(Arrays.asList(false),values);
 }
}
