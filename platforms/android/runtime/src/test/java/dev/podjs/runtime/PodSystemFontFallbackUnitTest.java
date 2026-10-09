package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PodSystemFontFallbackUnitTest {
    @Test public void rejectsInvalidHostRequestsBeforeAllocation() {
        assertNull(PodSystemFontFallback.raster("\ud800",16,0,24,24,18,2));
        assertNull(PodSystemFontFallback.raster(null,16,0,24,24,18,2));
        assertNull(PodSystemFontFallback.raster("A",Float.NaN,0,24,24,18,2));
        assertNull(PodSystemFontFallback.raster("A",16,0,0,24,18,2));
        assertNull(PodSystemFontFallback.raster("A",16,0,24,24,25,2));
        assertNull(PodSystemFontFallback.raster("A",16,0,24,24,18,5));
    }
    @Test public void rasterAndAdvanceUseTheSameFontSizeWeightAndDensity() {
        for(int bold=0;bold<=1;bold++)for(int density=1;density<=2;density++) {
            byte[] cell=PodSystemFontFallback.raster("A",16,bold,24,24,18,density);
            assertNotNull(cell);assertEquals(12+24*24*density*density*4,cell.length);
            Paint reference=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.FILTER_BITMAP_FLAG);
            reference.setTypeface(Typeface.create(Typeface.DEFAULT,bold!=0?Typeface.BOLD:Typeface.NORMAL));
            reference.setTextSize(16*density);
            assertEquals(reference.measureText("A")/density,ByteBuffer.wrap(cell).order(ByteOrder.LITTLE_ENDIAN).getFloat(),0);
            int ink=0;for(int i=12;i<cell.length;i+=4)if(cell[i+3]!=0)ink++;
            assertTrue("Native raster must contain ink",ink>0);
        }
    }
}
