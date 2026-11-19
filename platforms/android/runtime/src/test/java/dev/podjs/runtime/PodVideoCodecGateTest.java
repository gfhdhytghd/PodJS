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
 @Test public void onlyCancellable256MbPrefixMayBorrowTheThirdCodec(){
  PodVideoCodecGate gate=new PodVideoCodecGate();
  try(PodVideoCodecGate.Permit current=gate.acquire("current",PodVideoCodecGate.CURRENT);
      PodVideoCodecGate.Permit warm=gate.acquire("warm",PodVideoCodecGate.PREWARM)){
   assertNotNull(current);assertNotNull(warm);
   assertNull(gate.acquireCancellablePrefix("prefix",128_000_000L));
   assertNull(gate.acquire("cover",PodVideoCodecGate.OTHER));
   try(PodVideoCodecGate.Permit prefix=gate.acquireCancellablePrefix("prefix",256_000_000L)){
    assertNotNull(prefix);assertEquals(3,gate.live());assertNull(gate.acquire("fourth",PodVideoCodecGate.CURRENT));
   }
   assertEquals(2,gate.live());assertEquals(3,gate.peak());
  }
  assertEquals(0,gate.live());
 }
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
 @Test public void typedLifecycleReplaysSameOwnerAndGenerationAndReleasesExactlyOnce()throws Exception {
  PodVideoCodecGate gate=new PodVideoCodecGate();Object owner=new Object();List<PodVideoCodecGate.Allocation> reserved=new ArrayList<>(),released=new ArrayList<>();
  PodVideoCodecGate.Permit permit=gate.acquire(owner,17,"session",1);
  AutoCloseable subscription=gate.observe(new PodVideoCodecGate.LifecycleListener(){
   public void reserved(PodVideoCodecGate.Allocation a){reserved.add(a);}
   public void released(PodVideoCodecGate.Allocation a){released.add(a);}
  });
  assertEquals(1,reserved.size());assertSame(owner,reserved.get(0).owner);assertEquals(17,reserved.get(0).generation);
  permit.close();permit.close();assertEquals(1,released.size());assertSame(reserved.get(0),released.get(0));
  subscription.close();subscription.close();PodVideoCodecGate.Permit next=gate.acquire(owner,18,"session",1);next.close();assertEquals(1,reserved.size());
 }
 @Test public void failingObserverCannotLeakOrReturnADecoderPermitEarly()throws Exception {
  PodVideoCodecGate gate=new PodVideoCodecGate();AutoCloseable subscription=gate.observe(new PodVideoCodecGate.LifecycleListener(){
   public void reserved(PodVideoCodecGate.Allocation a){throw new IllegalStateException("fixture");}
   public void released(PodVideoCodecGate.Allocation a){throw new IllegalStateException("fixture");}
  });
  PodVideoCodecGate.Permit permit=gate.acquire(new Object(),3,"prefix",0);assertEquals(1,gate.live());permit.close();assertEquals(0,gate.live());subscription.close();
 }
 @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
 public void budgetReclaimUsesMainLaneAndProtectsCurrentAndKeep()throws Exception {
  PodVideoCodecGate gate=new PodVideoCodecGate();Object current=new Object(),next=new Object(),other=new Object();List<String> order=new ArrayList<>();
  gate.register(current,()->2,()->fail("current stolen"));gate.register(next,()->1,()->fail("keep stolen"));
  gate.register(other,()->0,()->{assertSame(android.os.Looper.getMainLooper(),android.os.Looper.myLooper());order.add("other");});
  Thread request=new Thread(()->gate.requestReclaim(next,1));request.start();request.join();assertTrue(order.isEmpty());
  org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(Collections.singletonList("other"),order);
 }
 @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
 public void currentWaitReclaimsWarmOnMainAndAcquiresAfterActualRelease()throws Exception {
  PodVideoCodecGate gate=new PodVideoCodecGate();Object warm=new Object(),selected=new Object();
  PodVideoCodecGate.Permit a=gate.acquire(selected,1,"current",2),b=gate.acquire("other_current",2),w=gate.acquire(warm,1,"warm",1);
  java.util.concurrent.CountDownLatch reclaimed=new java.util.concurrent.CountDownLatch(1);int[] calls={0};
  gate.register(selected,()->2,()->fail("current stolen"));
  gate.register(warm,()->1,()->{assertSame(android.os.Looper.getMainLooper(),android.os.Looper.myLooper());calls[0]++;reclaimed.countDown();});
  java.util.concurrent.atomic.AtomicReference<PodVideoCodecGate.Permit> result=new java.util.concurrent.atomic.AtomicReference<>();
  java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
  Thread worker=new Thread(()->{try{result.set(gate.await(selected,2,"new_current",2));}catch(Throwable e){failure.set(e);}});worker.start();
  try{
   long deadline=System.nanoTime()+2_000_000_000L;
   while(worker.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<deadline)Thread.yield();
   assertEquals(Thread.State.TIMED_WAITING,worker.getState());
   org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();assertEquals(1,calls[0]);
   assertEquals(0,reclaimed.getCount());assertNull(result.get());assertEquals(3,gate.live());
   w.close();worker.join(1000);assertFalse(worker.isAlive());assertNull(failure.get());assertNotNull(result.get());assertEquals(3,gate.live());
  }finally{w.close();a.close();b.close();worker.interrupt();worker.join(1000);if(result.get()!=null)result.get().close();}
  assertEquals(0,gate.live());
 }
}
