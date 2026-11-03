package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PodVideoKeyframeDiskTest {
    private <T>T worker(Callable<T> call)throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{return executor.submit(call).get(30,TimeUnit.SECONDS);}finally{executor.shutdownNow();}
    }
    @Test public void pixelsSurviveRestartAndBindOnlyToTheirSource()throws Exception {
        File root=Files.createTempDirectory("keyframe-disk").toFile();String key=PodVideoFrameCache.hash("source-a");
        worker(()->{
            Bitmap original=Bitmap.createBitmap(466,260,Bitmap.Config.ARGB_8888);original.eraseColor(Color.RED);
            try{new PodVideoKeyframeDisk(root).save(key,original);}finally{original.recycle();}
            Bitmap restored=new PodVideoKeyframeDisk(root).load(key);
            assertNotNull(restored);assertEquals(466,restored.getWidth());assertEquals(260,restored.getHeight());
            assertTrue(Color.red(restored.getPixel(230,130))>245);assertTrue(Color.blue(restored.getPixel(230,130))<10);restored.recycle();
            assertNull(new PodVideoKeyframeDisk(root).load(PodVideoFrameCache.hash("source-b")));
            for(File file:root.listFiles())assertTrue(file.getName().matches("[a-f0-9]{64}\\.jpg"));
            return null;
        });
    }
    @Test public void diskCountIsBoundedAndCorruptionFallsBack()throws Exception {
        File root=Files.createTempDirectory("keyframe-lru").toFile();
        worker(()->{
            PodVideoKeyframeDisk disk=new PodVideoKeyframeDisk(root);Bitmap frame=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
            try{for(int i=0;i<140;i++)disk.save(PodVideoFrameCache.hash("item-"+i),frame);}finally{frame.recycle();}
            File[] files=root.listFiles();assertEquals(PodVideoKeyframeDisk.MAX_ENTRIES,files.length);
            File broken=files[0];Files.write(broken.toPath(),new byte[]{1,2,3});
            assertNull(disk.load(broken.getName().substring(0,64)));assertFalse(broken.exists());disk.clear();assertEquals(0,root.listFiles().length);return null;
        });
    }
    @Test public void mainThreadCannotDecodeCompressedPixels()throws Exception {
        PodVideoKeyframeDisk disk=new PodVideoKeyframeDisk(Files.createTempDirectory("keyframe-main").toFile());
        try{disk.load(PodVideoFrameCache.hash("source"));fail("main-thread decode accepted");}
        catch(IllegalStateException expected){assertEquals("keyframe_disk_on_main",expected.getMessage());}
    }
}
