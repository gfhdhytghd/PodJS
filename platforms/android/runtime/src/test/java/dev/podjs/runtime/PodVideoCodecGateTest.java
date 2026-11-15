package dev.podjs.runtime;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=34)
public class PodVideoCodecGateTest {
 @Test public void concurrentPurposesNeverExceedThree()throws Exception {
  PodVideoCodecGate gate=new PodVideoCodecGate();ExecutorService workers=Executors.newFixedThreadPool(12);
  try{List<Future<?>> results=new ArrayList<>();for(int i=0;i<100;i++)results.add(workers.submit(()->{
   PodVideoCodecGate.Permit permit=gate.acquire("concurrent",PodVideoCodecGate.CURRENT);
   if(permit!=null){assertTrue(gate.live()<=3);Thread.yield();permit.close();permit.close();}
  }));for(Future<?> result:results)result.get();assertEquals(0,gate.live());assertTrue(gate.peak()<=3);}finally{workers.shutdownNow();}
 }
 @Test public void reclamationOrdersOtherBeforePrewarmAndNeverTakesCurrent(){
  PodVideoCodecGate gate=new PodVideoCodecGate();List<String> order=new ArrayList<>();Object current=new Object(),next=new Object(),other=new Object();
  gate.register(next,()->PodVideoCodecGate.PREWARM,()->order.add("next"));
  gate.register(current,()->PodVideoCodecGate.CURRENT,()->fail("current stolen"));
  gate.register(other,()->PodVideoCodecGate.OTHER,()->order.add("other"));
  gate.reclaim(null,PodVideoCodecGate.PREWARM);assertEquals(Arrays.asList("other","next"),order);
  order.clear();gate.reclaim(next,PodVideoCodecGate.PREWARM);assertEquals(Collections.singletonList("other"),order);
  order.clear();gate.reclaim(null,PodVideoCodecGate.OTHER);assertEquals(Collections.singletonList("other"),order);
 }
 @Test public void fiftyRapidSwipesRetainOneWarmAndKeepCountUntilRelease(){
  PodVideoCodecGate gate=new PodVideoCodecGate();PodVideoCodecGate.Permit current=gate.acquire("current",2),next=gate.acquire("next",1);
  for(int i=0;i<50;i++){
   assertEquals(2,gate.live());assertNull(gate.acquire("cover",0));
   PodVideoCodecGate.Permit transition=gate.acquire("transition",2);assertNotNull(transition);assertEquals(3,gate.live());assertNull(gate.acquire("fourth",2));
   current.close();current=next;next=transition;assertEquals(2,gate.live());
  }
  current.close();next.close();assertEquals(0,gate.live());assertEquals(3,gate.peak());
 }
 @Test public void softwareAndCoverShareThePhysicalLimit(){
  PodVideoCodecGate gate=new PodVideoCodecGate();PodVideoCodecGate.Permit software=gate.acquire("software_prefix",0),cover=gate.acquire("retriever",0);
  assertNotNull(software);assertNotNull(cover);assertNull(gate.acquire("another_cover",0));
  PodVideoCodecGate.Permit current=gate.acquire("current",2);assertNotNull(current);assertNull(gate.acquire("fourth",2));
  software.close();cover.close();current.close();assertEquals(0,gate.live());
 }
}
