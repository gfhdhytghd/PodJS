package dev.podjs.runtime;

import static org.junit.Assert.*;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
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
    @Test public void bulkArgbMatchesIndependentCanvasForRareAndEmojiClusters() {
        for(int bold=0;bold<=1;bold++)for(int density=1;density<=2;density++)for(String text:new String[]{"A","龘","𠮷","❤️","👍🏽","👨‍👩‍👧‍👦"}) {
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.FILTER_BITMAP_FLAG);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT,bold!=0?Typeface.BOLD:Typeface.NORMAL));paint.setTextSize(17*density);paint.setColor(android.graphics.Color.WHITE);
            int baseline=(int)Math.ceil(-paint.getFontMetrics().ascent/density);
            int[] actual=PodSystemFontFallback.rasterArgb(text,17,bold,38,38,baseline,density);
            if(!paint.hasGlyph(text)){assertNull(actual);continue;}
            assertNotNull(actual);Rect bounds=new Rect();paint.getTextBounds(text,0,text.length(),bounds);
            float x=(float)Math.ceil(Math.max(0,-bounds.left)/(double)density),y=(float)Math.ceil(Math.max(0,-(baseline*density+bounds.top))/(double)density);
            assertEquals(Float.floatToRawIntBits(paint.measureText(text)/density),actual[0]);assertEquals(Float.floatToRawIntBits(x),actual[1]);assertEquals(Float.floatToRawIntBits(y),actual[2]);
            Bitmap bitmap=Bitmap.createBitmap(38*density,38*density,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawText(text,x*density,(baseline+y)*density,paint);
            int[] expected=new int[38*38*density*density];bitmap.getPixels(expected,0,38*density,0,0,38*density,38*density);
            assertArrayEquals(expected,java.util.Arrays.copyOfRange(actual,3,actual.length));bitmap.recycle();
        }
    }
    @Test public void primaryMetricsUseNaturalHeightWhileFallbackPixelsMayOverhang() {
        for(int bold=0;bold<=1;bold++)for(int density=1;density<=2;density++) {
            Paint reference=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.FILTER_BITMAP_FLAG);
            reference.setTypeface(Typeface.create(Typeface.DEFAULT,bold!=0?Typeface.BOLD:Typeface.NORMAL));reference.setTextSize(17*density);
            Paint.FontMetrics f=reference.getFontMetrics();int baseline=(int)Math.ceil(-f.ascent/density);
            int[] metrics=PodSystemFontFallback.metrics(17,bold,density);
            assertEquals(baseline,metrics[2]);assertEquals(baseline+(int)Math.ceil(f.descent/density),metrics[1]);
            assertTrue("An emoji extent must not shift the centered primary line",metrics[1]<38);
            assertNotNull(PodSystemFontFallback.raster("A",17,bold,38,38,baseline,density));
        }
    }
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
