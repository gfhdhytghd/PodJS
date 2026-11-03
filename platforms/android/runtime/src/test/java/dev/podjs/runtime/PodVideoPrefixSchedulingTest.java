package dev.podjs.runtime;

import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class PodVideoPrefixSchedulingTest {
    @Test public void byteBurstDoesNotFillMainQueue() throws Exception {
        File root=Files.createTempDirectory("prefix-scheduling").toFile();
        PodMediaPrefixCache cache=new PodMediaPrefixCache(root);
        PodVideoKeyframes frames=new PodVideoKeyframes();
        PodVideoPrefixFrames worker=new PodVideoPrefixFrames(cache,new Handler(Looper.getMainLooper()),frames,key->fail("no cached media"));
        try {
            String uri=cache.register("fixture","https://example.invalid/fixture.mp4",Collections.emptyMap());
            PodMediaPrefixCache.Entry entry=cache.target(uri);
            worker.window(Collections.singletonList(entry));
            for(int i=0;i<10_000;i++)worker.bytes(entry);
            assertTrue("byte arrivals must never queue main-thread scans",Shadows.shadowOf(Looper.getMainLooper()).isIdle());
        } finally {worker.close();cache.close();frames.close();}
    }
    @Test public void diskRevisitPublishesWithoutAnyVideoBytes() throws Exception {
        File root=Files.createTempDirectory("prefix-disk-revisit").toFile();
        PodMediaPrefixCache cache=new PodMediaPrefixCache(root);PodVideoKeyframes frames=new PodVideoKeyframes();
        String uri=cache.register("fixture","https://example.invalid/fixture.mp4",Collections.emptyMap());
        String binding=PodVideoFrameCache.binding(new org.json.JSONObject().put("url",uri),cache);
        java.util.concurrent.ExecutorService io=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{io.submit(()->{
            android.graphics.Bitmap pixels=android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888);
            try{new PodVideoKeyframeDisk(new File(root,"decoded-keyframes")).save(binding,pixels);}finally{pixels.recycle();}
            return null;
        }).get(10,java.util.concurrent.TimeUnit.SECONDS);}finally{io.shutdownNow();}
        java.util.concurrent.atomic.AtomicBoolean ready=new java.util.concurrent.atomic.AtomicBoolean();
        PodVideoPrefixFrames worker=new PodVideoPrefixFrames(cache,new Handler(Looper.getMainLooper()),frames,key->ready.set(key.equals(binding)));
        try{
            PodMediaPrefixCache.Entry entry=cache.target(uri);assertEquals(-1,entry.length);assertFalse(entry.file.exists());
            worker.window(Collections.singletonList(entry));
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(!ready.get()&&System.nanoTime()<end){Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idle();}
            assertTrue("disk pixels must publish with zero video bytes",ready.get());assertTrue(frames.contains(binding));
        }finally{worker.close();cache.close();frames.close();}
    }
    @Test public void digestRetainsPersistentCacheIdentity() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",PodVideoFrameCache.hash(""));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",PodVideoFrameCache.hash("abc"));
    }
}
