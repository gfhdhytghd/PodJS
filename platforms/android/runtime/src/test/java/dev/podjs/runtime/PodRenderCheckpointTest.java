package dev.podjs.runtime;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PodRenderCheckpointTest {
 @Test public void checkpointsAreBoundedExpireAndResetWithGuestLifetime(){
  PodRenderCheckpoint trace=new PodRenderCheckpoint();assertFalse(trace.begin(0,0,false));assertTrue(trace.begin(5,100,false));
  assertFalse(trace.begin(5,110,false));assertFalse(trace.begin(4,110,false));
  for(int i=0;i<8;i++)assertTrue(trace.update(200));assertFalse(trace.update(200));
  assertTrue(trace.begin(6,300,false));assertFalse(trace.update(2_000_000_301L));
  trace.reset();assertTrue(trace.begin(1,400,false));
 }
 @Test public void alphaDiagnosticsDescribeOnlyTheFixedInnerRegion(){
  Bitmap bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0);
  bitmap.setPixel(3,3,0xff123456);bitmap.setPixel(4,4,0x80123456);bitmap.setPixel(0,0,0xffffffff);
  assertArrayEquals(new int[]{98,1,1},PodRenderCheckpoint.alphaCounts(bitmap));bitmap.recycle();
 }
 @Test public void observationPreservesMedia3ListenerOwnershipAndDestructionResult(){
  int[] calls=new int[2];PodObservedTextureView view=new PodObservedTextureView(RuntimeEnvironment.getApplication(),t->calls[1]++);
  TextureView.SurfaceTextureListener listener=new TextureView.SurfaceTextureListener(){
   public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){}
   public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){}
   public boolean onSurfaceTextureDestroyed(SurfaceTexture t){return false;}
   public void onSurfaceTextureUpdated(SurfaceTexture t){calls[0]++;}
  };
  view.setSurfaceTextureListener(listener);assertSame(listener,view.getSurfaceTextureListener());
  TextureView.SurfaceTextureListener wrapper=ReflectionHelpers.getField(view,"mListener");
  assertNotSame(listener,wrapper);wrapper.onSurfaceTextureUpdated(null);assertArrayEquals(new int[]{1,1},calls);
  assertFalse(wrapper.onSurfaceTextureDestroyed(null));view.setSurfaceTextureListener(null);assertNull(view.getSurfaceTextureListener());
 }
}
