package dev.podjs.runtime;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** File and packaged asset fonts shared by measurement and line rasterization. */
final class PodFontServices {
    private static final LinkedHashMap<Object,Typeface> cache=new LinkedHashMap<Object,Typeface>(32,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Object,Typeface> entry){return size()>32;}
    };
    private static final class AssetKey {
        final AssetManager manager;final String options;
        AssetKey(AssetManager manager,String options){this.manager=manager;this.options=options;}
        @Override public boolean equals(Object other){return other instanceof AssetKey&&manager==((AssetKey)other).manager&&options.equals(((AssetKey)other).options);}
        @Override public int hashCode(){return 31*System.identityHashCode(manager)+options.hashCode();}
    }
    static Typeface resolve(Context context,JSONObject args,int weight,boolean italic)throws IOException {
        int face=0;
        if(args.has("fontFaceIndex")){
            Object raw=args.opt("fontFaceIndex");
            if(!(raw instanceof Number))throw new IllegalArgumentException("fontFaceIndex must be an integer");
            double value=((Number)raw).doubleValue();
            if(!Double.isFinite(value)||value!=Math.rint(value)||value<0||value>Integer.MAX_VALUE)throw new IllegalArgumentException("invalid fontFaceIndex");
            face=(int)value;
        }
        String variation=string(args,"variationSettings");
        if(variation.length()>4096)throw new IllegalArgumentException("variationSettings too long");
        String relative=string(args,"fontPath");
        String asset=string(args,"fontAsset");
        if(!relative.isEmpty()&&!asset.isEmpty())throw new IllegalArgumentException("fontPath and fontAsset are mutually exclusive");
        Typeface fallback=Typeface.create(Typeface.DEFAULT,weight,italic);
        if(!asset.isEmpty()){
            String packaged=packagedAssetPath(asset);
            if(context==null)throw new IllegalArgumentException("fontAsset requires application context");
            AssetManager manager=context.getAssets();
            AssetKey key=new AssetKey(manager,new org.json.JSONArray().put(packaged).put(face).put(weight).put(italic).put(variation).toString());
            synchronized(cache){
                if(cache.containsKey(key)){Typeface result=cache.get(key);return result==null?fallback:result;}
                Typeface result=null;
                try{
                    Typeface.Builder builder=new Typeface.Builder(manager,packaged).setTtcIndex(face).setWeight(weight).setItalic(italic);
                    if(!variation.trim().isEmpty())builder.setFontVariationSettings(variation);
                    result=builder.build();
                }catch(RuntimeException invalidFont){ /* Missing, unreadable or invalid assets use the same fallback as files. */ }
                cache.put(key,result);return result==null?fallback:result;
            }
        }
        if(relative.isEmpty())return fallback;
        if(context==null)throw new IllegalArgumentException("fontPath requires application context");
        File root=PodTakeover.filesRoot(context).getCanonicalFile();
        if(new File(relative).isAbsolute()||relative.indexOf('\\')>=0||relative.indexOf('\0')>=0)throw new IllegalArgumentException("invalid fontPath");
        for(String part:relative.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("invalid fontPath");
        File file=new File(root,relative).getCanonicalFile();
        if(!file.getPath().startsWith(root.getPath()+File.separator))throw new IllegalArgumentException("fontPath escapes application files");
        if(!file.isFile())return fallback;
        String key=new org.json.JSONArray().put(file.getPath()).put(file.length()).put(file.lastModified()).put(face).put(weight).put(italic).put(variation).toString();
        synchronized(cache){
            if(cache.containsKey(key)){Typeface result=cache.get(key);return result==null?fallback:result;}
            Typeface result=null;
            try{
                Typeface.Builder builder=new Typeface.Builder(file).setTtcIndex(face).setWeight(weight).setItalic(italic);
                if(!variation.trim().isEmpty())builder.setFontVariationSettings(variation);
                result=builder.build();
            }catch(RuntimeException invalidFont){ /* Match the reader's system fallback. */ }
            cache.put(key,result);return result==null?fallback:result;
        }
    }
    static String packagedAssetPath(String name){
        if(name==null||name.isEmpty()||name.length()>512)throw new IllegalArgumentException("invalid fontAsset");
        for(String part:name.split("/",-1)){
            if(part.length()>255||!part.matches("[A-Za-z0-9][A-Za-z0-9._-]*")||part.endsWith("."))throw new IllegalArgumentException("invalid fontAsset");
            String stem=part.split("\\.",2)[0];
            if(stem.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))throw new IllegalArgumentException("invalid fontAsset");
        }
        return "pod-assets/"+name;
    }
    private static String string(JSONObject args,String key){
        if(!args.has(key)||args.isNull(key))return "";
        Object raw=args.opt(key);if(!(raw instanceof String))throw new IllegalArgumentException(key+" must be a string");return (String)raw;
    }
}
