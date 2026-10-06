package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.CancellationSignal;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import org.json.JSONObject;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/** One bounded system-shaped text line, including color-font fallback, as straight RGBA. */
final class PodTextRasterServices {
    /** Allocated only for debug/PERF_TRACE service lifetimes. Counts owned text
     * bitmaps, not every Android/media bitmap in the process. */
    static final class Stats {
        private long created,recycled,live,peak,bytes,peakBytes;
        synchronized void allocate(long size){created++;live++;bytes+=size;peak=Math.max(peak,live);peakBytes=Math.max(peakBytes,bytes);}
        synchronized void recycle(long size){recycled++;live--;bytes-=size;}
        synchronized JSONObject snapshot() throws Exception {return new JSONObject().put("created",created).put("recycled",recycled).put("live",live).put("peak",peak).put("liveBytes",bytes).put("peakBytes",peakBytes);}
    }
    static JSONObject raster(Context context, JSONObject args, CancellationSignal cancellation) throws Exception {
        return raster(context,args,cancellation,null);
    }
    static JSONObject raster(Context context,JSONObject args,CancellationSignal cancellation,PodParagraphLayout.Cache cache)throws Exception {
        return raster(context,args,cancellation,cache,null);
    }
    static JSONObject raster(Context context,JSONObject args,CancellationSignal cancellation,PodParagraphLayout.Cache cache,Stats stats)throws Exception {
        Object value=args.opt("text");
        if(!(value instanceof String))throw new IllegalArgumentException("text must be a string");
        String text=(String)value;
        if(text.length()>4096||text.indexOf('\n')>=0||text.indexOf('\r')>=0)throw new IllegalArgumentException("one text line of at most 4096 UTF-16 units required");
        int width=(int)number(args,"widthPx",1,1024,true),height=(int)number(args,"heightPx",1,1024,true);
        float baseline=(float)number(args,"baselinePx",0,height,false),size=(float)number(args,"fontSizePx",1,1024,false);
        StaticLayout layout;int sourceLine=0,sourceStart=0;
        if(args.has("paragraph")){
            if(!(args.opt("paragraph") instanceof JSONObject))throw new IllegalArgumentException("paragraph must be an object");
            JSONObject paragraph=args.getJSONObject("paragraph");
            PodParagraphLayout.Prepared prepared=PodParagraphLayout.obtain(context,paragraph,cancellation,cache);layout=prepared.layout;
            sourceLine=(int)number(paragraph,"lineIndex",0,layout.getLineCount()-1,true);sourceStart=layout.getLineStart(sourceLine);
            if(prepared.width!=width||!prepared.text.substring(sourceStart,layout.getLineVisibleEnd(sourceLine)).equals(text))throw new IllegalArgumentException("paragraph line does not match text or width");
        }else{
            TextPaint paint=PodParagraphLayout.paint(context,args);
            String align=PodParagraphLayout.choice(args,"align","left","left","center","right");
            Layout.Alignment alignment=align.equals("center")?Layout.Alignment.ALIGN_CENTER:align.equals("right")?Layout.Alignment.ALIGN_OPPOSITE:Layout.Alignment.ALIGN_NORMAL;
            layout=StaticLayout.Builder.obtain(text,0,text.length(),paint,width).setIncludePad(false)
                .setUseLineSpacingFromFallbacks(true).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setAlignment(alignment).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).setMaxLines(1).build();
        }
        cancellation.throwIfCanceled();
        int textureWidth=powerOfTwo(width),textureHeight=powerOfTwo(height);
        Bitmap bitmap=Bitmap.createBitmap(textureWidth,textureHeight,Bitmap.Config.ARGB_8888);File output=null;
        long trackedBytes=stats==null?0:bitmap.getAllocationByteCount();if(stats!=null)stats.allocate(trackedBytes);
        try {
            Canvas canvas=new Canvas(bitmap);canvas.clipRect(0,0,width,height);canvas.translate(0,baseline-layout.getLineBaseline(sourceLine));layout.draw(canvas);
            File root=new File(PodTakeover.filesRoot(context),"text");if(!root.isDirectory()&&!root.mkdirs())throw new java.io.IOException("text directory unavailable");
            output=File.createTempFile("line-",".rgba",root);
            int[] pixels=new int[textureWidth];byte[] row=new byte[textureWidth*4];
            try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(output))){
                for(int y=0;y<textureHeight;y++){
                    cancellation.throwIfCanceled();bitmap.getPixels(pixels,0,textureWidth,0,y,textureWidth,1);
                    for(int x=0;x<textureWidth;x++){int pixel=pixels[x],at=x*4;row[at]=(byte)(pixel>>>16);row[at+1]=(byte)(pixel>>>8);row[at+2]=(byte)pixel;row[at+3]=(byte)(pixel>>>24);}out.write(row);
                }
            }
            cancellation.throwIfCanceled();
            org.json.JSONArray carets=new org.json.JSONArray();
            for(int i=0;i<=text.length();i++){
                int offset=sourceStart+i;
                // An end offset belongs to the next logical line. Return this
                // line's visual edge for local hit testing and highlight spans.
                float x=offset==layout.getLineEnd(sourceLine)&&sourceLine+1<layout.getLineCount()?
                    (layout.getParagraphDirection(sourceLine)==1?layout.getLineRight(sourceLine):layout.getLineLeft(sourceLine)):layout.getPrimaryHorizontal(offset);
                carets.put(x);
            }
            return new JSONObject().put("carets",carets).put("path","text/"+output.getName()).put("width",textureWidth).put("height",textureHeight).put("contentWidth",width).put("contentHeight",height);
        }catch(Exception error){if(output!=null)output.delete();throw error;}finally{bitmap.recycle();if(stats!=null)stats.recycle(trackedBytes);}
    }
    static void discard(Context context,Object value){
        if(!(value instanceof JSONObject))return;
        String path=((JSONObject)value).optString("path","");
        if(path.matches("text/line-[A-Za-z0-9_-]+\\.rgba"))new File(PodTakeover.filesRoot(context),path).delete();
    }
    private static int powerOfTwo(int n){int result=1;while(result<n)result*=2;return result;}
    private static double number(JSONObject args,String key,double min,double max,boolean integer){
        Object raw=args.opt(key);if(!(raw instanceof Number))throw new IllegalArgumentException(key+" must be a number");
        double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value<min||value>max||(integer&&value!=Math.rint(value)))throw new IllegalArgumentException("invalid "+key);return value;
    }
}
