package dev.podjs.runtime;
import android.os.Handler;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.function.Consumer;

/** Lets a changed native/UI texture reach two window commits before expensive
 * guest continuation work. The bounded fallback is explicitly not a paint proof. */
final class PodAfterPaint {
 interface Commits {boolean available();Runnable next(Runnable committed);}
 static Runnable await(View view,Handler main,Consumer<Boolean> complete){
  return await(view,main,(frame,at)->{},complete);
 }
 static Runnable await(View view,Handler main,java.util.function.BiConsumer<Integer,Long> observed,Consumer<Boolean> complete){
  return await(view,main,2,observed,complete);
 }
 static Runnable await(View view,Handler main,int required,java.util.function.BiConsumer<Integer,Long> observed,Consumer<Boolean> complete){
  return await(new Commits(){
   public boolean available(){return view.isAttachedToWindow()&&view.isHardwareAccelerated()&&view.isShown();}
   public Runnable next(Runnable committed){
    ViewTreeObserver observer=view.getViewTreeObserver();observer.registerFrameCommitCallback(committed);view.invalidate();
    return ()->{if(observer.isAlive())observer.unregisterFrameCommitCallback(committed);};
   }
  },main,required,observed,complete);
 }
 static Runnable await(Commits commits,Handler main,Consumer<Boolean> complete){
  return await(commits,main,(frame,at)->{},complete);
 }
 static Runnable await(Commits commits,Handler main,java.util.function.BiConsumer<Integer,Long> observed,Consumer<Boolean> complete){
  return await(commits,main,2,observed,complete);
 }
 static Runnable await(Commits commits,Handler main,int required,java.util.function.BiConsumer<Integer,Long> observed,Consumer<Boolean> complete){
  if(required<1||required>2)throw new IllegalArgumentException("invalid_commit_count");
  class Wait {
   boolean done;int frames;Runnable unregister=()->{};
   final Runnable timeout=()->finish(false);
   void finish(boolean committed){if(done)return;done=true;main.removeCallbacks(timeout);unregister.run();complete.accept(committed);}
   void arm(){
    if(done)return;
    if(!commits.available()){finish(false);return;}
    unregister=commits.next(()->{long at=android.os.SystemClock.uptimeMillis();main.post(()->{if(done)return;observed.accept(++frames,at);if(frames==required)finish(true);else arm();});});
   }
   void cancel(){if(done)return;done=true;main.removeCallbacks(timeout);unregister.run();}
  }
  Wait wait=new Wait();main.postDelayed(wait.timeout,250);wait.arm();return ()->main.post(wait::cancel);
 }
}
