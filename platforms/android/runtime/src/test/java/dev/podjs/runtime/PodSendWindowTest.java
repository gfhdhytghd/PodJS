package dev.podjs.runtime;

import org.junit.Test;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class PodSendWindowTest {
    @Test public void prefetchAndBatchAckKeepWindowAndFileRangesIndependentOfGuest() throws Exception {
        for (int width : new int[]{1, 8}) {
            byte[] payload = new byte[65536];
            Arrays.fill(payload, (byte)77);
            String digest = PodFileRanges.hex(MessageDigest.getInstance("SHA-256").digest(payload));
            List<PodSendWindow.Chunk> chunks = new ArrayList<>();
            for (int i = 0; i < 16; i++) chunks.add(new PodSendWindow.Chunk(0, i, payload.length,
                    i*(long)payload.length, "frozen", String.format(Locale.ROOT, "%032x", i), digest, digest));
            LinkedBlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
            ExecutorService writer=Executors.newSingleThreadExecutor(), file=Executors.newSingleThreadExecutor();
            ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor();
            CountDownLatch prefetched=new CountDownLatch(1);
            try {
                PodSendWindow lane=new PodSendWindow(2,3,width,0,0,chunks,writer,file,timers,chunk->{
                    assertEquals("frozen",chunk.path);
                    assertEquals(chunk.index*(long)payload.length,chunk.offset);
                    if(chunk.index==1)prefetched.countDown();
                    return payload;
                },(sender,chunk,bytes)->{
                    if(chunk.index==0)assertTrue(prefetched.await(3,TimeUnit.SECONDS));
                    assertSame(payload,bytes);
                    frames.add(sender.reserve(chunk));
                });
                java.util.concurrent.CompletableFuture<PodSendWindow.Result> done=lane.start();
                long ack=0,tx=0;
                for(int group=0;group<16/width;group++){
                    List<Long> settled=new ArrayList<>();
                    for(int i=0;i<width;i++){
                        byte[] header=frames.poll(3,TimeUnit.SECONDS);
                        assertNotNull(header);
                        assertEquals(++tx,ByteBuffer.wrap(header).getLong(32));
                        settled.add(tx);
                    }
                    assertNull(frames.poll(25,TimeUnit.MILLISECONDS));
                    byte[] ackFrame=PodTransferFrames.ack(2,3,++ack,settled,Collections.nCopies(width,1));
                    assertTrue(lane.acknowledge(ackFrame));
                    assertFalse(lane.acknowledge(ackFrame));
                }
                PodSendWindow.Result saved=done.get(3,TimeUnit.SECONDS);
                assertEquals(tx,saved.tx);
                assertEquals(ack,saved.ack);
                assertEquals(16,saved.staged.size());
            } finally {
                writer.shutdownNow();file.shutdownNow();timers.shutdownNow();
            }
        }
    }
}
