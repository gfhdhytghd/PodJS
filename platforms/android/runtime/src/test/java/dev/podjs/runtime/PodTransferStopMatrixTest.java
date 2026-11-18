package dev.podjs.runtime;

import org.junit.Test;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class PodTransferStopMatrixTest {
    private enum Stop {
        BEFORE_SYNC, AFTER_SYNC, BEFORE_BIND, AFTER_BIND, BEFORE_BUSINESS, AFTER_BUSINESS
    }

    @Test public void stopOrdersEveryPersistenceBoundaryAndRecoveryReopensDurableFacts() throws Exception {
        for (Stop event:Stop.values()) verify(event);
    }

    private static void verify(Stop event) throws Exception {
        Path dir=Files.createTempDirectory("pod-transfer-stop");
        Path data=dir.resolve("part"), binding=dir.resolve("binding"), business=dir.resolve("business");
        PodTransferFence old=new PodTransferFence();
        CountDownLatch reached=new CountDownLatch(1), resume=new CountDownLatch(1), fenceStarted=new CountDownLatch(1);
        ExecutorService commit=Executors.newSingleThreadExecutor(), revoke=Executors.newSingleThreadExecutor();
        byte[] bytes=new byte[65536];
        Arrays.fill(bytes,(byte)42);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        AtomicInteger acks=new AtomicInteger();
        boolean businessSide=event==Stop.BEFORE_BUSINESS||event==Stop.AFTER_BUSINESS;
        try {
            Future<?> worker=commit.submit(()->{
                old.publish(()->{
                    pause(event,Stop.BEFORE_SYNC,reached,resume);
                    force(data,bytes);
                    pause(event,Stop.AFTER_SYNC,reached,resume);
                    pause(event,Stop.BEFORE_BIND,reached,resume);
                    force(binding,digest);
                    pause(event,Stop.AFTER_BIND,reached,resume);
                    return null;
                });
                if(old.live())acks.incrementAndGet();
                try{
                    old.publish(()->{
                        pause(event,Stop.BEFORE_BUSINESS,reached,resume);
                        force(business,digest);
                        pause(event,Stop.AFTER_BUSINESS,reached,resume);
                        return null;
                    });
                }catch(IOException fenced){assertEquals("STALE_EPOCH",fenced.getMessage());}
                return null;
            });
            assertTrue(event.name(),reached.await(5,TimeUnit.SECONDS));
            old.revoke();
            Future<?> drained=revoke.submit(()->{
                fenceStarted.countDown();
                old.awaitRevoked();
                return null;
            });
            assertTrue(fenceStarted.await(5,TimeUnit.SECONDS));
            assertFalse("An admitted durable call still owns the fence",drained.isDone());
            resume.countDown();
            drained.get(5,TimeUnit.SECONDS);
            worker.get(5,TimeUnit.SECONDS);
            assertEquals(event.name(),businessSide?1:0,acks.get());
            assertEquals(businessSide,Files.exists(business));
            try{old.publish(()->{throw new AssertionError("old epoch published");});fail();}
            catch(IOException stale){assertEquals("STALE_EPOCH",stale.getMessage());}
            PodTransferFence recovered=new PodTransferFence();
            recovered.publish(()->{
                assertArrayEquals(digest,Files.readAllBytes(binding));
                assertArrayEquals(digest,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(data)));
                if(businessSide)assertArrayEquals(digest,Files.readAllBytes(business));
                return null;
            });
        }finally{
            resume.countDown();commit.shutdownNow();revoke.shutdownNow();
            for(Path item:Arrays.asList(business,binding,data,dir))Files.deleteIfExists(item);
        }
    }

    private static void pause(Stop event,Stop point,CountDownLatch reached,CountDownLatch resume)throws InterruptedException{
        if(event!=point)return;
        reached.countDown();
        assertTrue(resume.await(5,TimeUnit.SECONDS));
    }

    private static void force(Path path,byte[] bytes)throws IOException{
        try(FileOutputStream output=new FileOutputStream(path.toFile())){
            output.write(bytes);
            output.getFD().sync();
        }
    }

    @Test public void controlAndDataShareBindingBeforeAnyDurableRequestOrAck() throws Exception {
        byte[] body=new byte[65536];
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(body);
        byte[] digest=new byte[32];
        Arrays.fill(digest,(byte)0x11);
        PodReceiveWindow.ObjectSlot slot=new PodReceiveWindow.ObjectSlot(body.length,new byte[][]{hash},
                new String[]{repeat("11",32)},new int[0],false);
        java.util.concurrent.ScheduledExecutorService durable=Executors.newSingleThreadScheduledExecutor();
        PodTransferFence fence=new PodTransferFence();
        try{
            PodReceiveWindow lane=new PodReceiveWindow(1,1,1,new PodReceiveWindow.ObjectSlot[]{slot},
                    new boolean[1],fence,Runnable::run,durable,Runnable::run,
                    batch->{throw new AssertionError("conflict reached durable lane");},new PodReceiveWindow.Writer(){
                public void queued(){throw new AssertionError("conflict was ACKed");}
                public void ack(List<Long> entries,Runnable starting){throw new AssertionError("conflict was ACKed");}
                public void finished(){}
                public void failed(Exception error){throw new AssertionError(error);}
            });
            String operation=repeat("00",16);
            lane.bindControl(operation,repeat("22",32));
            ByteBuffer raw=ByteBuffer.allocate(96);
            raw.position(16);
            raw.putInt(1).putInt(0).putLong(1).putLong(1).putInt(0).putInt(body.length).put(new byte[16]).put(digest);
            PodTransferFrames.Chunk header=new PodTransferFrames.Chunk(raw.array(),body.length);
            java.io.ByteArrayInputStream input=new java.io.ByteArrayInputStream(body);
            try{lane.receive(header,input,new byte[4096]);fail("accepted conflicting DATA");}
            catch(IOException conflict){assertEquals("REQUEST_CONFLICT",conflict.getMessage());}
            assertEquals(body.length,input.available());
            lane.close();
        }finally{durable.shutdownNow();}
    }

    private static String repeat(String pattern,int count) {
        StringBuilder out=new StringBuilder(pattern.length()*count);
        for(int i=0;i<count;i++)out.append(pattern);
        return out.toString();
    }
}
