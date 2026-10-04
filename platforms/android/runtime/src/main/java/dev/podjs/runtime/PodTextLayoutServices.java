package dev.podjs.runtime;
import android.content.Context;
import android.os.CancellationSignal;
import android.text.StaticLayout;
import android.text.TextPaint;
import org.json.JSONArray;
import org.json.JSONObject;
/** Physical-pixel paragraph metrics; source offsets remain UTF-16 with generated glyphs excluded. */
final class PodTextLayoutServices {
    static JSONObject layout(JSONObject args,CancellationSignal cancellation)throws Exception{return layout(null,args,cancellation,null);}
    static JSONObject layout(Context context,JSONObject args,CancellationSignal cancellation)throws Exception{return layout(context,args,cancellation,null);}
    static JSONObject layout(Context context,JSONObject args,CancellationSignal cancellation,PodParagraphLayout.Cache cache)throws Exception{
        PodParagraphLayout.Prepared prepared=PodParagraphLayout.obtain(context,args,cancellation,cache);StaticLayout layout=prepared.layout;JSONArray lines=new JSONArray();
        for(int i=0;i<layout.getLineCount();i++){cancellation.throwIfCanceled();lines.put(new JSONObject().put("start",layout.getLineStart(i)).put("end",layout.getLineEnd(i)).put("visibleEnd",layout.getLineVisibleEnd(i)).put("topPx",layout.getLineTop(i)).put("bottomPx",prepared.bottom(i)).put("baselinePx",prepared.baseline(i)));}
        return new JSONObject().put("lines",lines);
    }
    /** Bounded batch keeps one host round trip while preserving per-paragraph failures. */
    static JSONObject layoutBatch(Context context,JSONObject args,CancellationSignal cancellation,PodParagraphLayout.Cache cache)throws Exception{
        JSONArray paragraphs=args.optJSONArray("paragraphs");
        if(paragraphs==null||paragraphs.length()<1||paragraphs.length()>32)throw new IllegalArgumentException("invalid paragraph batch size");
        long textUnits=0;
        for(int i=0;i<paragraphs.length();i++){
            JSONObject paragraph=paragraphs.optJSONObject(i);
            if(paragraph==null||!(paragraph.opt("text") instanceof String))throw new IllegalArgumentException("invalid paragraph batch item");
            textUnits+=paragraph.getString("text").length();
            if(textUnits>262144)throw new IllegalArgumentException("paragraph batch text too large");
        }
        JSONArray results=new JSONArray();
        for(int i=0;i<paragraphs.length();i++){
            cancellation.throwIfCanceled();
            try{results.put(layout(context,paragraphs.getJSONObject(i),cancellation,cache));}
            catch(IllegalArgumentException error){results.put(new JSONObject().put("error",error.getMessage()==null?"invalid paragraph":error.getMessage()));}
        }
        cancellation.throwIfCanceled();
        return new JSONObject().put("results",results);
    }
    static JSONObject measure(Context context,JSONObject args,CancellationSignal cancellation)throws Exception{
        Object text=args.opt("text");if(!(text instanceof String)||((String)text).length()>262144)throw new IllegalArgumentException("invalid text");
        TextPaint paint=PodParagraphLayout.paint(context,args);cancellation.throwIfCanceled();float width=paint.measureText((String)text);cancellation.throwIfCanceled();return new JSONObject().put("widthPx",width);
    }
}
