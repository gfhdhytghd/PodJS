package dev.podjs.runtime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class PcmSessionTest {
 static final class Device implements PodPcmSession.Device {
  AtomicInteger closed=new AtomicInteger();boolean fail;CountDownLatch reading,release;
  public int read(short[] b)throws Exception {if(reading!=null){reading.countDown();release.await();}if(fail)throw new IllegalStateException("revoked");b[0]=123;return b.length;}
  public int write(short[] b){if(fail)throw new IllegalStateException("write failed");return b.length/2;}
  public long played(){return Long.MAX_VALUE;}
  public void close(){if(closed.incrementAndGet()!=1)throw new AssertionError("double release");if(release!=null)release.countDown();}
 }
 static void denied(Runnable action){try{action.run();throw new AssertionError("Expected denial");}catch(SecurityException expected){}}
 public static void main(String[] args)throws Exception {
  AtomicInteger opened=new AtomicInteger();PodPcmSession gate=new PodPcmSession(record->{opened.incrementAndGet();return new Device();});
  for(boolean[] c:new boolean[][]{{false,true,true},{true,false,true},{true,true,false}})denied(()->{try{gate.open(true,c[0],c[1],c[2]);}catch(RuntimeException e){throw e;}catch(Exception e){throw new RuntimeException(e);}});
  assert opened.get()==0;gate.close();
  Device d=new Device();PodPcmSession s=new PodPcmSession(record->d);long id=s.open(true,true,true,true);assert s.read(id).length==4096;s.stop(id);s.stop(id);s.close();assert d.closed.get()==1;
  Device failure=new Device();failure.fail=true;PodPcmSession failed=new PodPcmSession(record->failure);long f=failed.open(false,true,true,false);try{failed.write(f,new short[4096]);throw new AssertionError();}catch(IllegalStateException expected){}assert failure.closed.get()==1;
  Device blocked=new Device();blocked.reading=new CountDownLatch(1);blocked.release=new CountDownLatch(1);PodPcmSession pending=new PodPcmSession(record->blocked);long p=pending.open(true,true,true,true);
  ExecutorService executor=Executors.newSingleThreadExecutor();Future<?> read=executor.submit(()->{try{pending.read(p);throw new AssertionError();}catch(IllegalStateException expected){}catch(Exception e){throw new RuntimeException(e);}});
  assert blocked.reading.await(2,TimeUnit.SECONDS);try{pending.read(p);throw new AssertionError();}catch(IllegalStateException expected){}pending.cancel();read.get(2,TimeUnit.SECONDS);assert blocked.closed.get()==1;executor.shutdownNow();
  CountDownLatch starting=new CountDownLatch(1),finish=new CountDownLatch(1);Device late=new Device();PodPcmSession opening=new PodPcmSession(record->{starting.countDown();finish.await();return late;});
  Thread t=new Thread(()->{try{opening.open(true,true,true,true);throw new AssertionError();}catch(IllegalStateException expected){}catch(Exception e){throw new RuntimeException(e);}});t.start();assert starting.await(2,TimeUnit.SECONDS);opening.close();finish.countDown();t.join(2000);assert !t.isAlive();assert late.closed.get()==1;
  Device write=new Device();PodPcmSession player=new PodPcmSession(record->write);long w=player.open(false,true,true,false);assert player.write(w,new short[4096])==2048;assert player.drained(w);try{player.write(w,new short[4097]);throw new AssertionError();}catch(IllegalArgumentException expected){}player.close();assert write.closed.get()==1;
  System.out.println("PASS: PCM debug/foreground/permission denial opens no device; bounded chunks, partial write ACK, single pending read, failed IO release, cancellation during read/open, exactly-once close");
 }
}
