package dev.podjs.runtime;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=35,manifest=Config.NONE)
public class PodShellProviderTest {
 @Test public void rejectsBeforeReadingContextArgumentsOrExtras(){
  PodShellProvider provider=new PodShellProvider();
  for(int uid:new int[]{1000,10000,102000,202000}){
   ShadowBinder.setCallingUid(uid);
   try{provider.call(null,null,null);fail();}catch(SecurityException denied){assertEquals("denied",denied.getMessage());}
   try{provider.call("secret.authority",null,null,null);fail();}catch(SecurityException denied){assertEquals("denied",denied.getMessage());}
  }
 }
 @After public void reset(){ShadowBinder.reset();}
}
