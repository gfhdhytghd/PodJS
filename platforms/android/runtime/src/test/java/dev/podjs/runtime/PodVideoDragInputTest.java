package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.view.MotionEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodVideoDragInputTest {
 boolean input(PodVideoDragInput p,int action,float y){MotionEvent e=MotionEvent.obtain(0,10,action,100,y,0);try{return p.accept(e);}finally{e.recycle();}}
 @Test public void onlyRecognizedDragsTrackNativeDisplacementAndNeverOwnRelease(){
  PodVideoDragInput p=new PodVideoDragInput();assertFalse(p.arm());
  assertFalse(input(p,MotionEvent.ACTION_DOWN,200));assertFalse(input(p,MotionEvent.ACTION_MOVE,180));
  assertTrue(p.arm());assertEquals(-20,p.distanceY(),0);assertTrue(input(p,MotionEvent.ACTION_MOVE,100));assertEquals(-100,p.distanceY(),0);
  assertTrue(input(p,MotionEvent.ACTION_MOVE,230));assertEquals(30,p.distanceY(),0);
  assertFalse(input(p,MotionEvent.ACTION_UP,230));assertFalse(p.tracking());assertFalse(p.arm());
  input(p,MotionEvent.ACTION_DOWN,100);assertTrue(p.arm());assertFalse(input(p,MotionEvent.ACTION_CANCEL,100));assertFalse(p.tracking());
 }
 @Test public void newPointerAndExplicitDisarmInvalidatePriorDrag(){
  PodVideoDragInput p=new PodVideoDragInput();input(p,MotionEvent.ACTION_DOWN,200);p.arm();input(p,MotionEvent.ACTION_MOVE,100);p.disarm();assertFalse(input(p,MotionEvent.ACTION_MOVE,90));
  input(p,MotionEvent.ACTION_DOWN,300);assertFalse(p.tracking());assertEquals(0,p.distanceY(),0);
  p.arm();input(p,MotionEvent.ACTION_POINTER_DOWN,300);assertFalse(p.tracking());
 }
}
