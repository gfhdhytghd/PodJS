package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.view.MotionEvent;
import org.junit.Test;
public class PodTouchReleasePolicyTest {
 private boolean event(PodTouchReleasePolicy p,int a,int n,int id,float x,float y){return p.observe(a,n,id,x,y,16);}
 @Test public void downAndDistantUpWithoutMovePreserveOneEndpoint(){PodTouchReleasePolicy p=new PodTouchReleasePolicy();assertFalse(event(p,MotionEvent.ACTION_DOWN,1,0,233,330));assertTrue(event(p,MotionEvent.ACTION_UP,1,0,233,130));assertFalse(event(p,MotionEvent.ACTION_UP,1,0,233,130));}
 @Test public void subSlopJitterDoesNotDiscardLargeTerminalTravel(){PodTouchReleasePolicy p=new PodTouchReleasePolicy();event(p,MotionEvent.ACTION_DOWN,1,0,233,330);event(p,MotionEvent.ACTION_MOVE,1,0,234,328);assertTrue(event(p,MotionEvent.ACTION_UP,1,0,233,130));}
 @Test public void existingPanDoesNotAddAnotherEndpointFrame(){PodTouchReleasePolicy p=new PodTouchReleasePolicy();event(p,MotionEvent.ACTION_DOWN,1,0,233,330);event(p,MotionEvent.ACTION_MOVE,1,0,233,230);assertFalse(event(p,MotionEvent.ACTION_UP,1,0,233,130));}
 @Test public void tapSmallTravelAndCancellationCannotCreateASwipe(){PodTouchReleasePolicy p=new PodTouchReleasePolicy();event(p,MotionEvent.ACTION_DOWN,1,0,30,30);assertFalse(event(p,MotionEvent.ACTION_UP,1,0,35,34));event(p,MotionEvent.ACTION_DOWN,1,0,30,30);event(p,MotionEvent.ACTION_CANCEL,1,0,130,30);assertFalse(event(p,MotionEvent.ACTION_UP,1,0,130,30));}
 @Test public void multiPointerAndLifecycleResetNeverBridgeTheFormerContact(){PodTouchReleasePolicy p=new PodTouchReleasePolicy();event(p,MotionEvent.ACTION_DOWN,1,0,30,30);event(p,MotionEvent.ACTION_POINTER_DOWN,2,0,30,30);assertFalse(event(p,MotionEvent.ACTION_UP,1,0,130,30));event(p,MotionEvent.ACTION_DOWN,1,0,30,30);p.reset();assertFalse(event(p,MotionEvent.ACTION_UP,1,0,130,30));}
 @Test public void pointerMismatchAndNonfiniteCoordinatesAreRejected(){PodTouchReleasePolicy p=new PodTouchReleasePolicy();event(p,MotionEvent.ACTION_DOWN,1,0,30,30);assertFalse(event(p,MotionEvent.ACTION_UP,1,1,130,30));event(p,MotionEvent.ACTION_DOWN,1,0,30,30);assertFalse(event(p,MotionEvent.ACTION_UP,1,0,Float.NaN,30));event(p,MotionEvent.ACTION_DOWN,1,2,30,30);assertTrue(event(p,MotionEvent.ACTION_UP,1,2,130,30));}
}
