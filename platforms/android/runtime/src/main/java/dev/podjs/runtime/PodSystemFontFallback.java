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
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

/** Missing atlas characters use Android's Minikin/Skia system font fallback.
 * Both advance and pixels come from the same Paint, at the atlas density and
 * baseline. No font files, app data, or guest character lists are consulted. */
final class PodSystemFontFallback {
    private static final AtomicInteger warmGeneration=new AtomicInteger();
    private static boolean warmStarted;
    private static long rasterCount,rasterNanos;
    private static Bitmap scratch;
    private static Canvas canvas;
    private static int[] pixels=new int[0];
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
    private static Paint paint(float px,int bold,int density) {
        String key=Float.floatToIntBits(px)+":"+bold+":"+density+":"+(PodSystemFonts.enabled()?PodSystemFonts.identity():"");
        Paint paint=paints.get(key);
        if(paint==null) {
            paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.FILTER_BITMAP_FLAG);
            paint.setTypeface(PodSystemFonts.typeface(bold!=0?700:400,false));
            paint.setTextSize(px*density);paint.setColor(Color.WHITE);paints.put(key,paint);
        }
        return paint;
    }
    // JNI: logical cell width/height, baseline, line height. No packaged metrics.
    static synchronized int[] metrics(float px,int bold,int density) {
        if(!Float.isFinite(px)||px<1||px>128||density<1||density>4)return null;
        Paint.FontMetrics f=paint(px,bold,density).getFontMetrics();
        int baseline=(int)Math.ceil(-f.ascent/density),extent=(int)Math.ceil(px*2+4);
        return new int[]{Math.min(255,extent),Math.min(255,Math.max(extent,(int)Math.ceil((f.bottom-f.top)/density))),baseline,
            Math.max(1,(int)Math.ceil((f.descent-f.ascent+f.leading)/density))};
    }
    static synchronized void prewarm() {
        if(warmStarted||!PodSystemFonts.enabled())return;
        warmStarted=true;int generation=warmGeneration.incrementAndGet();
        org.json.JSONObject config=PodSystemFonts.config();
        Thread worker=new Thread(()->{
            int cells=0;long began=System.nanoTime();
            try {
                HashSet<Integer> wanted=new HashSet<>();org.json.JSONArray preload=config.optJSONArray("preloadFontSlots");
                if(preload!=null)for(int i=0;i<preload.length();i++)wanted.add(preload.getInt(i));
                org.json.JSONArray slots=config.getJSONArray("slots");String chars=config.getString("warmChars");
                for(int i=0;i<slots.length();i++) {
                    org.json.JSONObject slot=slots.getJSONObject(i);if(!wanted.isEmpty()&&!wanted.contains(slot.getInt("slot")))continue;
                    float px=(float)slot.getDouble("px");int bold=slot.getBoolean("bold")?1:0,density=slot.getInt("density");
                    int[] m=metrics(px,bold,density);if(m==null)continue;
                    for(int pos=0;pos<chars.length();) {
                        if(warmGeneration.get()!=generation)return;
                        int cp=chars.codePointAt(pos);pos+=Character.charCount(cp);
                        raster(new String(Character.toChars(cp)),px,bold,m[0],m[1],m[2],density);cells++;
                        if(cells%8==0)Thread.sleep(1);
                    }
                }
                android.util.Log.i("PodJSPerf","systemFontWarm cells="+cells+" ms="+(System.nanoTime()-began)/1e6+" "+stats());
            }catch(Exception unavailable){android.util.Log.w("PodJS","system font prewarm stopped",unavailable);}
        },"pod-system-font-warm");worker.setDaemon(true);worker.setPriority(Thread.MIN_PRIORITY);worker.start();
    }
    static synchronized void cancelWarm(){warmGeneration.incrementAndGet();warmStarted=false;}
    static synchronized String stats(){PodGlyphDiskCache cache=PodSystemFonts.disk();return "rasterCount="+rasterCount+" rasterMs="+rasterNanos/1e6+(cache==null?"":" diskHits="+cache.hits+" diskMisses="+cache.misses+" diskReadMs="+cache.readNanos/1e6+" diskWriteMs="+cache.writeNanos/1e6+" diskBytes="+cache.bytes());}
    // JNI callback: header = logical advance/xoff/yoff float32 LE, then straight RGBA.
    static synchronized byte[] raster(String text,float px,int bold,int w,int h,int baseline,int density) {
        if(!validText(text)||!Float.isFinite(px)||px<1||px>255
                ||w<1||h<1||w>255||h>255||density<1||density>4||baseline<0||baseline>h)return null;
        Paint paint=paint(px,bold,density);
        PodGlyphDiskCache cache=PodSystemFonts.disk();
        String cacheKey=cache==null?null:PodSystemFonts.hash((PodSystemFonts.identity()+":"+Float.floatToIntBits(px)+":"+bold+":"+w+":"+h+":"+baseline+":"+density+":"+text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int length=12+w*h*density*density*4;
        if(cache!=null){byte[] found=cache.get(cacheKey,length);if(found!=null)return found;}
        if(!paint.hasGlyph(text))return null;
        long began=System.nanoTime();
        float advance=paint.measureText(text)/density;
        Rect bounds=new Rect();paint.getTextBounds(text,0,text.length(),bounds);
        float xoff=(float)Math.ceil(Math.max(0,-bounds.left)/(double)density);
        float yoff=(float)Math.ceil(Math.max(0,-(baseline*density+bounds.top))/(double)density);
        int width=w*density,height=h*density,count=width*height;
        if(scratch==null||scratch.getWidth()!=width||scratch.getHeight()!=height) {
            if(scratch!=null)scratch.recycle();scratch=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);canvas=new Canvas(scratch);
        }
        if(pixels.length<count)pixels=new int[count];
        canvas.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR);
        try {
            canvas.drawText(text,xoff*density,(baseline+yoff)*density,paint);
            scratch.getPixels(pixels,0,width,0,0,width,height);
            ByteBuffer result=ByteBuffer.allocate(12+count*4).order(ByteOrder.LITTLE_ENDIAN);
            result.putFloat(advance).putFloat(xoff).putFloat(yoff);
            for(int i=0;i<count;i++){int argb=pixels[i];result.put((byte)(argb>>>16)).put((byte)(argb>>>8)).put((byte)argb).put((byte)(argb>>>24));}
            byte[] raw=result.array();rasterCount++;rasterNanos+=System.nanoTime()-began;
            if(cache!=null)cache.put(cacheKey,raw);
            return raw;
        } finally { /* One bounded scratch surface is reused by the serial rasterizer. */ }
    }
}
