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
  return await(new Commits(){
   public boolean available(){return view.isAttachedToWindow()&&view.isHardwareAccelerated()&&view.isShown();}
   public Runnable next(Runnable committed){
    ViewTreeObserver observer=view.getViewTreeObserver();observer.registerFrameCommitCallback(committed);view.invalidate();
    return ()->{if(observer.isAlive())observer.unregisterFrameCommitCallback(committed);};
   }
  },main,complete);
 }
 static Runnable await(Commits commits,Handler main,Consumer<Boolean> complete){
  class Wait {
   boolean done;int frames;Runnable unregister=()->{};
   final Runnable timeout=()->finish(false);
   void finish(boolean committed){if(done)return;done=true;main.removeCallbacks(timeout);unregister.run();complete.accept(committed);}
   void arm(){
    if(done)return;
    if(!commits.available()){finish(false);return;}
    unregister=commits.next(()->main.post(()->{if(done)return;if(++frames==2)finish(true);else arm();}));
   }
   void cancel(){if(done)return;done=true;main.removeCallbacks(timeout);unregister.run();}
  }
  Wait wait=new Wait();main.postDelayed(wait.timeout,250);wait.arm();return ()->main.post(wait::cancel);
 }
}
