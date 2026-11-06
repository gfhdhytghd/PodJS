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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Missing atlas characters use Android's Minikin/Skia system font fallback.
 * Both advance and pixels come from the same Paint, at the atlas density and
 * baseline. No font files, app data, or guest character lists are consulted. */
final class PodSystemFontFallback {
    private static final AtomicReference<Object> warmRun=new AtomicReference<>();
    private static final AtomicLong rasterCount=new AtomicLong(),rasterNanos=new AtomicLong(),metricHits=new AtomicLong(),metricBuilds=new AtomicLong();
    private static final RasterState foreground=new RasterState();
    private static boolean validText(String text) {
        if(text==null||text.isEmpty()||text.length()>1024)return false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(Character.isHighSurrogate(c)) {if(i+1>=text.length()||!Character.isLowSurrogate(text.charAt(++i)))return false;}
            else if(Character.isLowSurrogate(c))return false;
        }
        return true;
    }
    static int[] metrics(float px,int bold,int density) {return foreground.metrics(px,bold,density);}
    static void prewarm() {
        if(!PodSystemFonts.enabled())return;
        final Object token=new Object();if(!warmRun.compareAndSet(null,token))return;
        Thread worker=new Thread(()->{
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST);
            int cells=0,skipped=0;long began=System.nanoTime();
            RasterState raster=new RasterState();
            try {
                if(warmRun.get()!=token)return;
                PodGlyphDiskCache persistence=PodSystemFonts.disk();if(persistence!=null)persistence.enableWrites();
                org.json.JSONObject config=PodSystemFonts.config();
                HashSet<Integer> wanted=new HashSet<>();org.json.JSONArray preload=config.optJSONArray("preloadFontSlots");
                if(preload!=null)for(int i=0;i<preload.length();i++)wanted.add(preload.getInt(i));
                org.json.JSONArray slots=config.getJSONArray("slots");String chars=config.getString("warmChars");
                for(int i=0;i<slots.length();i++) {
                    org.json.JSONObject slot=slots.getJSONObject(i);if(!wanted.isEmpty()&&!wanted.contains(slot.getInt("slot")))continue;
                    float px=(float)slot.getDouble("px");int bold=slot.getBoolean("bold")?1:0,density=slot.getInt("density");
                    if(warmRun.get()!=token)return;
                    int[] m=raster.metrics(px,bold,density);if(m==null)continue;
                    int extent=Math.min(255,(int)Math.ceil(px*2+4));m[0]=Math.max(m[0],extent);m[1]=Math.max(m[1],extent);
                    for(int pos=0;pos<chars.length();) {
                        if(warmRun.get()!=token)return;
                        int cp=chars.codePointAt(pos);pos+=Character.charCount(cp);
                        String text=new String(Character.toChars(cp));PodGlyphDiskCache cache=PodSystemFonts.disk();
                        if(cache!=null&&cache.contains(cacheKey(text,px,bold,m[0],m[1],m[2],density)))skipped++;
                        else raster.rasterArgb(text,px,bold,m[0],m[1],m[2],density);
                        cells++;
                        if(cells%8==0)Thread.sleep(1);
                    }
                }
                PodSystemFonts.persistMetrics();
                android.util.Log.i("PodJSPerf","systemFontWarm cells="+cells+" existing="+skipped+" ms="+(System.nanoTime()-began)/1e6+" "+stats());
            }catch(Exception unavailable){android.util.Log.w("PodJS","system font prewarm stopped",unavailable);}finally{raster.close();}
        },"pod-system-font-warm");worker.setDaemon(true);worker.setPriority(Thread.MIN_PRIORITY);worker.start();
    }
    static void cancelWarm(){warmRun.set(null);}
    static String stats(){PodGlyphDiskCache cache=PodSystemFonts.disk();return "metricHits="+metricHits+" metricBuilds="+metricBuilds+" rasterCount="+rasterCount+" rasterMs="+rasterNanos.get()/1e6+(cache==null?"":" diskHits="+cache.hits+" nativeDiskHits="+cache.nativeDiskHits+" diskMisses="+cache.misses+" diskReadMs="+cache.readNanos/1e6+" diskWriteMs="+cache.writeNanos/1e6+" diskBytes="+cache.bytes()+" inventoryReady="+cache.inventoryReady()+" cacheEntries="+cache.directoryEntries+" cacheInitializeMs="+cache.directoryInitializeNanos/1e6);}
    private static String cacheKey(String text,float px,int bold,int w,int h,int baseline,int density){return PodSystemFonts.hash((PodSystemFonts.identity()+":"+Float.floatToIntBits(px)+":"+bold+":"+w+":"+h+":"+baseline+":"+density+":"+text).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    // Differential reference/legacy byte contract. Production JNI uses the
    // bulk int array below, with unpremultiplied Android ARGB values.
    static byte[] raster(String text,float px,int bold,int w,int h,int baseline,int density) {
        int[] cell=rasterArgb(text,px,bold,w,h,baseline,density);if(cell==null)return null;
        ByteBuffer bytes=ByteBuffer.allocate(cell.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<3;i++)bytes.putInt(cell[i]);
        for(int i=3;i<cell.length;i++){int argb=cell[i];bytes.put((byte)(argb>>>16)).put((byte)(argb>>>8)).put((byte)argb).put((byte)(argb>>>24));}
        return bytes.array();
    }
    // Foreground and warm-up never share a raster monitor or scratch bitmap.
    // A cancelled warm run owns its state until its current cell returns.
    static int[] rasterArgb(String text,float px,int bold,int w,int h,int baseline,int density) {
        return foreground.rasterArgb(text,px,bold,w,h,baseline,density);
    }
    static final class RasterState {
        private Bitmap scratch;
        private Canvas canvas;
        private final LinkedHashMap<String,Paint> paints=new LinkedHashMap<String,Paint>(32,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,Paint> e) { return size()>32; }
        };
        private final LinkedHashMap<String,Paint> metricPaints=new LinkedHashMap<String,Paint>(32,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,Paint> e){return size()>32;}
        };
        private Paint paint(float px,int bold,int density) {
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
        synchronized int[] metrics(float px,int bold,int density) {
            if(!Float.isFinite(px)||px<1||px>128||density<1||density>4)return null;
            int[] cached=PodSystemFonts.cachedMetrics(px,bold,density);if(cached!=null){metricHits.incrementAndGet();return cached;}metricBuilds.incrementAndGet();
            String key=Float.floatToIntBits(px)+":"+bold+":"+density+":"+PodSystemFonts.identity();
            Paint metrics=metricPaints.get(key);
            if(metrics==null){metrics=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG|Paint.FILTER_BITMAP_FLAG);metrics.setTypeface(PodSystemFonts.primaryTypeface(bold!=0?700:400,false));metrics.setTextSize(px*density);metricPaints.put(key,metrics);}
            Paint.FontMetrics f=metrics.getFontMetrics();
            int baseline=(int)Math.ceil(-f.ascent/density);
            // The retained core centers the PRIMARY cell in each CSS line box.
            // Emoji overhang belongs to Fallback's larger cells, never to this box.
            int[] result=new int[]{(int)Math.ceil(px),Math.min(255,Math.max(1,baseline+(int)Math.ceil(f.descent/density))),baseline,
                Math.max(1,(int)Math.ceil((f.descent-f.ascent+f.leading)/density))};
            PodSystemFonts.cacheMetrics(px,bold,density,result);return result;
        }
        // JNI: three float32 bit patterns followed by unpremultiplied ARGB pixels.
        synchronized int[] rasterArgb(String text,float px,int bold,int w,int h,int baseline,int density) {
            if(!validText(text)||!Float.isFinite(px)||px<1||px>255
                    ||w<1||h<1||w>255||h>255||density<1||density>4||baseline<0||baseline>h)return null;
            PodGlyphDiskCache cache=PodSystemFonts.disk();
            String cacheKey=cache==null?null:cacheKey(text,px,bold,w,h,baseline,density);
            int length=12+w*h*density*density*4;
            if(cache!=null){int[] found=cache.getArgb(cacheKey,length);if(found!=null)return found;}
            Paint paint=paint(px,bold,density);
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
            canvas.drawColor(Color.TRANSPARENT,android.graphics.PorterDuff.Mode.CLEAR);
            try {
                canvas.drawText(text,xoff*density,(baseline+yoff)*density,paint);
                int[] result=new int[3+count];
                result[0]=Float.floatToRawIntBits(advance);result[1]=Float.floatToRawIntBits(xoff);result[2]=Float.floatToRawIntBits(yoff);
                scratch.getPixels(result,3,width,0,0,width,height);
                rasterCount.incrementAndGet();rasterNanos.addAndGet(System.nanoTime()-began);
                if(cache!=null)cache.putArgbAsync(cacheKey,result);
                return result;
            } finally { /* One bounded scratch surface is reused by the serial rasterizer. */ }
        }
        synchronized void close(){if(scratch!=null){scratch.recycle();scratch=null;canvas=null;}paints.clear();metricPaints.clear();}
    }
}
