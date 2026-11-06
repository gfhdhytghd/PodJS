package dev.podjs.runtime;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.os.Build;
import android.util.Xml;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import org.xmlpull.v1.XmlPullParser;

/** Device-owned font files are mapped read-only. Native glyph callbacks and
 * Android paragraph/EditText services use exactly these immutable typefaces. */
final class PodSystemFonts {
    static final class Face {
        String file,style="normal";int index,weight=400;String fallbackFor="";
        final ArrayList<String> axes=new ArrayList<>();
    }
    static final class Family {String name="",lang="";final ArrayList<Face> faces=new ArrayList<>();}
    static List<Family> parse(InputStream input)throws Exception {
        XmlPullParser parser=Xml.newPullParser();parser.setInput(input,"UTF-8");
        ArrayList<Family> families=new ArrayList<>();Family family=null;Face face=null;
        for(int event=parser.getEventType();event!=XmlPullParser.END_DOCUMENT;event=parser.next()) {
            if(event==XmlPullParser.START_TAG) {
                switch(parser.getName()) {
                    case "family": family=new Family();family.name=attr(parser,"name","");family.lang=attr(parser,"lang","");families.add(family);break;
                    case "font": if(family==null)throw new IOException("font outside family");
                        face=new Face();face.weight=Integer.parseInt(attr(parser,"weight","400"));face.index=Integer.parseInt(attr(parser,"index","0"));
                        face.style=attr(parser,"style","normal");face.fallbackFor=attr(parser,"fallbackFor","");family.faces.add(face);break;
                    case "axis": if(face==null)throw new IOException("axis outside font");
                        String tag=attr(parser,"tag","");float value=Float.parseFloat(attr(parser,"stylevalue","NaN"));
                        if(!tag.matches("[A-Za-z0-9]{4}")||!Float.isFinite(value))throw new IOException("invalid font axis");
                        face.axes.add("'"+tag+"' "+value);break;
                    default: break;
                }
            }else if(event==XmlPullParser.TEXT&&face!=null){String text=parser.getText().trim();if(!text.isEmpty())face.file=(face.file==null?"":face.file)+text;}
            else if(event==XmlPullParser.END_TAG){if(parser.getName().equals("font"))face=null;else if(parser.getName().equals("family"))family=null;}
            if(families.size()>512||(family!=null&&family.faces.size()>64))throw new IOException("font XML exceeds bounds");
        }
        return families;
    }
    private static String attr(XmlPullParser p,String name,String fallback){String value=p.getAttributeValue(null,name);return value==null?fallback:value;}
    private static final HashMap<Integer,Typeface> faces=new HashMap<>();
    private static final HashMap<Integer,Typeface> primaryFaces=new HashMap<>();
    private static final HashMap<String,ByteBuffer> mappings=new HashMap<>();
    private static final ArrayList<Family> families=new ArrayList<>();
    private static final HashMap<String,Font> fonts=new HashMap<>();
    private static String identity="",packagePath="";
    private static JSONObject config;
    private static PodGlyphDiskCache disk;
    private static volatile boolean enabled;
    static boolean enabled(){return enabled;}
    static synchronized JSONObject config(){return config;}
    static synchronized PodGlyphDiskCache disk(){return disk;}
    static synchronized String identity(){return identity;}
    static String hash(byte[] bytes){try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hex(byte[] bytes){char[] text=new char[bytes.length*2];String digits="0123456789abcdef";for(int i=0;i<bytes.length;i++){int b=bytes[i]&255;text[i*2]=digits.charAt(b>>>4);text[i*2+1]=digits.charAt(b&15);}return new String(text);}
    static String fingerprint(byte[] xml,String build,Iterable<File> files)throws Exception {
        MessageDigest signature=MessageDigest.getInstance("SHA-256");signature.update(xml);signature.update(build.getBytes(StandardCharsets.UTF_8));
        signature.update("podjs-system-skia-v4-argb".getBytes(StandardCharsets.UTF_8));
        for(File file:files)signature.update((file.getName()+":"+file.length()+":"+file.lastModified()+"\n").getBytes(StandardCharsets.UTF_8));
        return hex(signature.digest());
    }
    static synchronized void ensure(Context context) {
        if(context==null)return;
        String current=context.getApplicationInfo().sourceDir;
        if(current.equals(packagePath))return;
        packagePath=current;enabled=false;config=null;disk=null;faces.clear();primaryFaces.clear();families.clear();mappings.clear();fonts.clear();
        try {
            byte[] manifest;
            try(InputStream input=context.getAssets().open("pod.manifest.json")){manifest=readBounded(input,2*1024*1024);}
            catch(FileNotFoundException standalone){return;}
            JSONObject root=new JSONObject(new String(manifest,StandardCharsets.UTF_8));config=root.optJSONObject("systemFonts");
            if(config==null)return;
            config.put("preloadFontSlots",root.optJSONArray("preloadFontSlots"));
            if(config.getInt("version")!=1||config.getLong("diskCacheBytes")!=PodGlyphDiskCache.MAX_BYTES)throw new IOException("system font contract");
            try{disk=new PodGlyphDiskCache(new File(context.getCacheDir(),"podjs-system-glyphs"),PodGlyphDiskCache.MAX_BYTES);}catch(IOException unavailable){disk=null;}
            byte[] xml;
            try(InputStream input=new FileInputStream("/system/etc/fonts.xml")){xml=readBounded(input,2*1024*1024);}
            String language=Locale.getDefault().getLanguage(),build=Build.FINGERPRINT+":podjs-system-skia-v4-argb";
            File fontRoot=new File("/system/fonts");String xmlHash=hash(xml);
            PodSystemFontIndex.Value cached=PodSystemFontIndex.decode(disk==null?null:disk.fontIndex(),xmlHash,build,language,fontRoot);
            if(cached!=null){families.addAll(cached.families);identity=cached.identity;}
            else {
                List<Family> parsed=parse(new ByteArrayInputStream(xml));
                for(Family family:parsed)if(family.name.equals("sans-serif"))families.add(family);
                for(Family family:parsed)if(family.name.isEmpty()&&(family.lang.isEmpty()||family.lang.startsWith("zh")||family.lang.startsWith("ja")||family.lang.startsWith("ko")||family.lang.equals("und-Zsye")||family.lang.equals("und-Zsym")||(!language.isEmpty()&&family.lang.startsWith(language))))families.add(family);
                if(families.isEmpty()||!families.get(0).name.equals("sans-serif"))throw new IOException("missing default font family");
                TreeSet<String> names=new TreeSet<>();for(Family family:families)for(Face face:family.faces)if(PodSystemFontIndex.validFile(face.file))names.add(face.file);
                ArrayList<File> selectedFiles=new ArrayList<>();for(String name:names)selectedFiles.add(new File(fontRoot,name));
                identity=fingerprint(xml,Build.FINGERPRINT,selectedFiles);
                if(disk!=null)try{disk.fontIndex(PodSystemFontIndex.encode(xmlHash,build,language,families,fontRoot,identity));}catch(IOException cacheUnavailable){/* Parsed metadata remains valid in memory. */}
            }
            // Font objects and mapped files are created only for a requested
            // style. XML parsing does not contend with native Vulkan creation.
            enabled=true;
            android.util.Log.i("PodJSPerf","systemFonts indexHit="+(cached!=null)+" mappedFiles="+mappings.size()+" families="+families.size()+" identity="+identity+" diskLimit="+PodGlyphDiskCache.MAX_BYTES+(disk==null?"":" cacheEntries="+disk.directoryEntries+" cacheScanMs="+disk.directoryScanNanos/1e6+" cacheInitializeMs="+disk.directoryInitializeNanos/1e6));
        }catch(Exception invalid) {
            config=null;throw new IllegalStateException("Cannot resolve configured system fonts",invalid);
        }
    }
    static Face closest(List<Face> candidates,int weight,boolean italic) {
        Face best=null;float score=Float.POSITIVE_INFINITY;
        for(Face face:candidates) {
            if(!face.fallbackFor.isEmpty()&&!face.fallbackFor.equals("sans-serif"))continue;
            if(!PodSystemFontIndex.validFile(face.file)||face.index<0||face.weight<1||face.weight>1000)continue;
            float candidate=Math.abs(face.weight-weight)/100f+(face.style.equals("italic")==italic?0:2);
            if(candidate<score){best=face;score=candidate;}
        }
        return best;
    }
    private static String fontKey(Face face){return face.file+":"+face.index+":"+face.weight+":"+face.style+":"+String.join(",",face.axes);}
    private static Font font(Face face)throws IOException {
        String key=fontKey(face);Font known=fonts.get(key);if(known!=null)return known;
        File file=new File("/system/fonts",face.file);if(!file.isFile())throw new IOException("missing font");
        ByteBuffer buffer=mappings.get(face.file);
        if(buffer==null) {
            try(FileInputStream stream=new FileInputStream(file)) {
                long size=stream.getChannel().size();if(size<12||size>64*1024*1024)throw new IOException("font size");
                buffer=stream.getChannel().map(FileChannel.MapMode.READ_ONLY,0,size);
            }
            mappings.put(face.file,buffer);
        }
        Font.Builder builder=new Font.Builder(buffer.asReadOnlyBuffer()).setTtcIndex(face.index).setWeight(face.weight)
            .setSlant(face.style.equals("italic")?FontStyle.FONT_SLANT_ITALIC:FontStyle.FONT_SLANT_UPRIGHT);
        if(!face.axes.isEmpty())builder.setFontVariationSettings(String.join(",",face.axes));
        Font result=builder.build();fonts.put(key,result);return result;
    }
    // Full-family construction is retained as a differential reference and for
    // uncommon style requests. Common normal 400/700 styles need one face/family.
    static synchronized Typeface referenceTypeface(int weight,boolean italic){return buildTypeface(weight,italic,true);}
    // Paint.getFontMetrics has no text: its metrics are those of the primary
    // sans face, independent of fallback coverage. Do not construct every CJK
    // and emoji family merely to install a size descriptor.
    static synchronized Typeface primaryTypeface(int weight,boolean italic) {
        if(!enabled)return Typeface.create(Typeface.DEFAULT,weight,italic);
        int key=weight*2+(italic?1:0);Typeface known=primaryFaces.get(key);if(known!=null)return known;
        ArrayList<Face> remaining=new ArrayList<>(families.get(0).faces);
        while(!remaining.isEmpty()) {
            Face face=closest(remaining,weight,italic);if(face==null)break;remaining.remove(face);
            try {
                Typeface result=new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font(face)).build()).setSystemFallback("sans-serif")
                    .setStyle(new FontStyle(weight,italic?FontStyle.FONT_SLANT_ITALIC:FontStyle.FONT_SLANT_UPRIGHT)).build();
                primaryFaces.put(key,result);return result;
            }catch(IOException|IllegalArgumentException invalid){/* Try the next primary XML candidate. */}
        }
        throw new IllegalStateException("no usable primary system font");
    }
    private static Typeface buildTypeface(int weight,boolean italic,boolean full) {
        ArrayList<FontFamily> resolved=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        for(Family family:families) {
            ArrayList<Face> remaining=new ArrayList<>(family.faces);FontFamily.Builder builder=null;String familyKey="";
            while(!remaining.isEmpty()) {
                Face face=closest(remaining,weight,italic);if(face==null)break;remaining.remove(face);
                try {
                    Font ready=font(face);if(builder==null)builder=new FontFamily.Builder(ready);else builder.addFont(ready);
                    familyKey+=fontKey(face)+";";if(!full)break;
                }catch(IOException|IllegalArgumentException invalid){/* Keep the next XML candidate. */}
            }
            if(builder!=null&&seen.add(familyKey))resolved.add(builder.build());
        }
        if(resolved.isEmpty())throw new IllegalStateException("no usable system font family");
        Typeface.CustomFallbackBuilder builder=new Typeface.CustomFallbackBuilder(resolved.get(0));
        int max=Typeface.CustomFallbackBuilder.getMaxCustomFallbackCount();for(int i=1;i<resolved.size()&&i<max;i++)builder.addCustomFallback(resolved.get(i));
        return builder.setSystemFallback("sans-serif").setStyle(new FontStyle(weight,italic?FontStyle.FONT_SLANT_ITALIC:FontStyle.FONT_SLANT_UPRIGHT)).build();
    }
    static synchronized Typeface typeface(int weight,boolean italic) {
        if(!enabled)return Typeface.create(Typeface.DEFAULT,weight,italic);
        int key=weight*2+(italic?1:0);Typeface known=faces.get(key);if(known!=null)return known;
        long began=System.nanoTime();Typeface result=buildTypeface(weight,italic,italic||(weight!=400&&weight!=700));
        if(faces.size()>=32)faces.clear();faces.put(key,result);
        android.util.Log.i("PodJSPerf","systemFontStyle weight="+weight+" italic="+italic+" mappedFiles="+mappings.size()+" ms="+(System.nanoTime()-began)/1e6);
        return result;
    }
    private static byte[] readBounded(InputStream input,int bound)throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=input.read(block))!=-1){if(output.size()+n>bound)throw new IOException("font metadata exceeds bound");output.write(block,0,n);}return output.toByteArray();
    }
}
