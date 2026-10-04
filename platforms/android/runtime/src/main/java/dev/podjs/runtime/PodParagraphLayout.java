package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.CancellationSignal;
import android.text.*;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared immutable paragraph shaping. The cache belongs to one service worker/lifetime. */
final class PodParagraphLayout {
    static final class Prepared {
        final String text;final StaticLayout layout;final StaticLayout emptyLine;final int width;
        Prepared(String text,StaticLayout layout,StaticLayout emptyLine,int width){this.text=text;this.layout=layout;this.emptyLine=emptyLine;this.width=width;}
        int bottom(int line){return line==layout.getLineCount()-1&&emptyLine!=null?layout.getLineTop(line)+emptyLine.getLineBottom(0):layout.getLineBottom(line);}
        int baseline(int line){return line==layout.getLineCount()-1&&emptyLine!=null?layout.getLineTop(line)+emptyLine.getLineBaseline(0):layout.getLineBaseline(line);}
    }
    static final class Key {
        final Typeface face;final String parameters;
        Key(Typeface face,String parameters){this.face=face;this.parameters=parameters;}
        @Override public int hashCode(){return 31*face.hashCode()+parameters.hashCode();}
        @Override public boolean equals(Object value){return value instanceof Key&&face.equals(((Key)value).face)&&parameters.equals(((Key)value).parameters);}
    }
    static final class Cache {
        final LinkedHashMap<Key,Prepared> entries=new LinkedHashMap<>(16,.75f,true);
        int units;long builds;
        void clear(){entries.clear();units=0;}
        void put(Key key,Prepared value){while(!entries.isEmpty()&&(entries.size()>=32||units+value.text.length()>262144)){Map.Entry<Key,Prepared> oldest=entries.entrySet().iterator().next();units-=oldest.getValue().text.length();entries.remove(oldest.getKey());}entries.put(key,value);units+=value.text.length();builds++;}
    }
    static TextPaint paint(Context context,JSONObject args)throws Exception{
        int weight=args.has("fontWeight")?(int)number(args,"fontWeight",1,1000,true):400;
        for(String key:new String[]{"italic","underline","strikethrough"})if(args.has(key)&&!(args.opt(key) instanceof Boolean))throw new IllegalArgumentException(key+" must be boolean");
        TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(PodFontServices.resolve(context,args,weight,args.optBoolean("italic",false)));
        paint.setTextSize((float)number(args,"fontSizePx",1,1024,false));
        paint.setLetterSpacing(args.has("letterSpacingEm")?(float)number(args,"letterSpacingEm",-1,10,false):0);
        paint.setUnderlineText(args.optBoolean("underline",false));paint.setStrikeThruText(args.optBoolean("strikethrough",false));
        paint.setColor(args.has("colorArgb")?(int)(long)number(args,"colorArgb",Integer.MIN_VALUE,0xffffffffL,true):0xffffffff);
        String motion=choice(args,"textMotion","subpixel","subpixel","static","animated");
        paint.setSubpixelText(!motion.equals("static"));paint.setLinearText(motion.equals("animated"));
        paint.setHinting(motion.equals("animated")?Paint.HINTING_OFF:Paint.HINTING_ON);
        return paint;
    }
    static Prepared obtain(Context context,JSONObject args,CancellationSignal cancellation,Cache cache)throws Exception{
        Object raw=args.opt("text");if(!(raw instanceof String)||((String)raw).length()>262144)throw new IllegalArgumentException("text must contain at most 262144 UTF-16 units");String text=(String)raw;
        int width=(int)number(args,"widthPx",1,16384,true);TextPaint paint=paint(context,args);
        String mode=choice(args,"lineBreak","paragraph","paragraph","simple","balanced"),heightMode=choice(args,"lineHeightMode","span","span","spacing"),hyphens=choice(args,"hyphenation","none","none","full"),align=choice(args,"align","left","left","center","right");
        for(String key:new String[]{"fallbackLineSpacing","justify"})if(args.has(key)&&!(args.opt(key) instanceof Boolean))throw new IllegalArgumentException(key+" must be boolean");
        boolean fallback=args.optBoolean("fallbackLineSpacing",true),justify=args.optBoolean("justify",false);
        float lineHeight=args.has("lineHeightPx")?(float)number(args,"lineHeightPx",1,8192,false):0;
        Key key=new Key(paint.getTypeface(),(context==null?"":context.getFilesDir().getPath())+"|"+paint.getTextLocales().toLanguageTags()+"|"+paint.getTextSize()+"|"+paint.getLetterSpacing()+"|"+paint.getFlags()+"|"+paint.getHinting()+"|"+paint.getColor()+"|"+width+"|"+mode+"|"+heightMode+"|"+hyphens+"|"+align+"|"+fallback+"|"+justify+"|"+lineHeight+"|"+text);
        cancellation.throwIfCanceled();if(cache!=null){Prepared found=cache.entries.get(key);if(found!=null)return found;}
        CharSequence styled=text;
        if(heightMode.equals("span")&&lineHeight>0&&!text.isEmpty()){
            SpannableString span=new SpannableString(text);int end=text.endsWith("\n")?text.length()+1:text.length();
            span.setSpan(new PodLineHeightSpan(lineHeight,end,true),0,text.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);styled=span;
        }
        Layout.Alignment alignment=align.equals("center")?Layout.Alignment.ALIGN_CENTER:align.equals("right")?Layout.Alignment.ALIGN_OPPOSITE:Layout.Alignment.ALIGN_NORMAL;
        StaticLayout layout=StaticLayout.Builder.obtain(styled,0,text.length(),paint,width).setIncludePad(false).setUseLineSpacingFromFallbacks(fallback).setAlignment(alignment)
            .setLineSpacing(heightMode.equals("spacing")&&lineHeight>0?lineHeight-(paint.getFontMetrics().descent-paint.getFontMetrics().ascent):0,1)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).setBreakStrategy(mode.equals("balanced")?Layout.BREAK_STRATEGY_BALANCED:mode.equals("paragraph")?Layout.BREAK_STRATEGY_HIGH_QUALITY:Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(hyphens.equals("full")?Layout.HYPHENATION_FREQUENCY_FULL:Layout.HYPHENATION_FREQUENCY_NONE)
            .setJustificationMode(justify?Layout.JUSTIFICATION_MODE_INTER_WORD:Layout.JUSTIFICATION_MODE_NONE).build();
        int last=layout.getLineCount()-1;StaticLayout empty=null;
        if(heightMode.equals("span")&&lineHeight>0&&layout.getLineStart(last)==layout.getLineEnd(last)){
            SpannableString span=new SpannableString("\u200b");span.setSpan(new PodLineHeightSpan(lineHeight,1,last==0),0,1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            empty=StaticLayout.Builder.obtain(span,0,1,paint,width).setIncludePad(false).setUseLineSpacingFromFallbacks(true).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).build();
        }
        cancellation.throwIfCanceled();Prepared result=new Prepared(text,layout,empty,width);if(cache!=null)cache.put(key,result);return result;
    }
    static String choice(JSONObject args,String name,String fallback,String...choices){Object value=args.opt(name);if(value==null)return fallback;if(value instanceof String)for(String candidate:choices)if(candidate.equals(value))return candidate;throw new IllegalArgumentException("invalid "+name);}
    static double number(JSONObject args,String name,double low,double high,boolean integer){Object raw=args.opt(name);if(!(raw instanceof Number))throw new IllegalArgumentException(name+" must be a number");double value=((Number)raw).doubleValue();if(!Double.isFinite(value)||value<low||value>high||integer&&value!=Math.rint(value))throw new IllegalArgumentException("invalid "+name);return value;}
}
