package dev.podjs.runtime;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import static org.junit.Assert.*;
public class PodSystemFontWarmStartTest {
    @Test public void firstTextureWarmStartDoesNotWaitForRasterMonitor() throws Exception {
        CountDownLatch locked=new CountDownLatch(1);
        Thread holder=new Thread(()->{synchronized(PodSystemFontFallback.class){
            locked.countDown();try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }});
        holder.start();locked.await();long began=System.nanoTime();
        try {
            PodSystemFontFallback.prewarm();double ms=(System.nanoTime()-began)/1e6;
            System.out.println("first-texture warm start under 200 ms raster monitor hold: "+ms+" ms");
            assertTrue("first texture must not wait for the raster monitor: "+ms,ms<150);
        }finally{holder.join();PodSystemFontFallback.cancelWarm();}
    }
}
