package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=30)
public class PodVideoPrefixPixelsTest {
    @Test public void cropAndPaddedInterleavedChromaExcludeStridePixels(){
        byte[] luma={0,0,0,0,0,0, 0,16,(byte)235,0,0,0, 0,(byte)235,16,0,0,0, 0,0,0,0,0,0};
        byte[] uv={(byte)128,0,(byte)128,0,(byte)128,0,(byte)128,0};
        Bitmap b=PodVideoPrefixFrames.bitmap(new Rect(1,1,3,3),new ByteBuffer[]{ByteBuffer.wrap(luma),ByteBuffer.wrap(uv),ByteBuffer.wrap(uv)},new int[]{6,4,4},new int[]{1,2,2});
        try{assertEquals(2,b.getWidth());assertEquals(2,b.getHeight());assertEquals(0xff000000,b.getPixel(0,0));assertEquals(0xffffffff,b.getPixel(1,0));assertEquals(0xffffffff,b.getPixel(0,1));assertEquals(0xff000000,b.getPixel(1,1));}finally{b.recycle();}
    }
    @Test public void downsampleBoundsTheLongEdgeWithoutChangingAspect(){
        byte[] y=new byte[800*400],uv=new byte[400*200];java.util.Arrays.fill(y,(byte)235);java.util.Arrays.fill(uv,(byte)128);
        Bitmap b=PodVideoPrefixFrames.bitmap(new Rect(0,0,800,400),new ByteBuffer[]{ByteBuffer.wrap(y),ByteBuffer.wrap(uv),ByteBuffer.wrap(uv)},new int[]{800,400,400},new int[]{1,1,1});
        try{assertEquals(466,b.getWidth());assertEquals(233,b.getHeight());assertEquals(0xffffffff,b.getPixel(465,232));}finally{b.recycle();}
    }
}
