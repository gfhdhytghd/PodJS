package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;

/** Missing atlas characters use Android's Minikin/Skia system font fallback.
 * Both advance and pixels come from the same Paint, at the atlas density and
 * baseline. No font files, app data, or guest character lists are consulted. */
final class PodSystemFontFallback {
    private static final LinkedHashMap<String,Paint> paints=new LinkedHashMap<String,Paint>(32,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Paint> e) { return size()>32; }
    };
    private static boolean validText(String text) {
        if(text==null||text.isEmpty()||text.length()>1024)return false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)) {if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i)))return false;}
            else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
    // JNI callback: header = logical advance/xoff/yoff float32 LE, then straight RGBA.
    static synchronized byte[] raster(String text,float px,int bold,int w,int h,int baseline,int density) {
        if(!validText(text)||!Float.isFinite(px)||px<1||px>255
                ||w<1||h<1||w>255||h>255||density<1||density>4||baseline<0||baseline>h)return null;
        String key=Float.floatToIntBits(px)+":"+bold+":"+density;
        Paint paint=paints.get(key);
        if(paint==null) {
            paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.FILTER_BITMAP_FLAG);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT,bold!=0?Typeface.BOLD:Typeface.NORMAL));
            paint.setTextSize(px*density);paint.setColor(Color.WHITE);paints.put(key,paint);
        }
        if(!paint.hasGlyph(text))return null;
        float advance=paint.measureText(text)/density;
        Rect bounds=new Rect();paint.getTextBounds(text,0,text.length(),bounds);
        float xoff=(float)Math.ceil(Math.max(0,-bounds.left)/(double)density);
        float yoff=(float)Math.ceil(Math.max(0,-(baseline*density+bounds.top))/(double)density);
        Bitmap bitmap=Bitmap.createBitmap(w*density,h*density,Bitmap.Config.ARGB_8888);
        try {
            new Canvas(bitmap).drawText(text,xoff*density,(baseline+yoff)*density,paint);
            int[] pixels=new int[w*h*density*density];bitmap.getPixels(pixels,0,w*density,0,0,w*density,h*density);
            ByteBuffer result=ByteBuffer.allocate(12+pixels.length*4).order(ByteOrder.LITTLE_ENDIAN);
            result.putFloat(advance).putFloat(xoff).putFloat(yoff);
            for(int argb:pixels)result.put((byte)(argb>>>16)).put((byte)(argb>>>8)).put((byte)argb).put((byte)(argb>>>24));
            return result.array();
        } finally { bitmap.recycle(); }
    }
}
