package dev.podjs.runtime;
import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
/** Compiles against the card base; the missing behavior fails assertions there. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=30) @LooperMode(LooperMode.Mode.PAUSED)
public class PodVideoKeyframeContractTest {
 @Test public void advertisesIndependentBoundedKeyframes()throws Exception {
  Context c=ApplicationProvider.getApplicationContext();PodVideoSessionServices service=new PodVideoSessionServices(c,new PodVideoDecoderBudget(3));JSONObject[] value={null};
  service.dispatch("video.session.capabilities",new JSONObject(),new PodVideoSessionServices.Callback(){public void complete(JSONObject v){value[0]=v;}public void fail(String code,String message){throw new AssertionError(code);}});Shadows.shadowOf(Looper.getMainLooper()).idle();
  try{assertTrue("decoded frames must outlive leases",value[0].optBoolean("keyframeCache"));assertEquals(8L*466*466*4,value[0].getLong("keyframeMaxBytes"));}finally{service.close();Shadows.shadowOf(Looper.getMainLooper()).idle();}
 }
 @Test public void hostRetainsThreeDecoderCeiling(){assertEquals(3,PodVideoSessionServices.MAX_SESSIONS);}
}
