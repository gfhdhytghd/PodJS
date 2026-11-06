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
    private static final HashMap<String,ByteBuffer> mappings=new HashMap<>();
    private static final ArrayList<FontFamily> families=new ArrayList<>();
    private static String identity="",packagePath="";
    private static JSONObject config;
    private static PodGlyphDiskCache disk;
    private static volatile boolean enabled;
    static boolean enabled(){return enabled;}
    static synchronized JSONObject config(){return config;}
    static synchronized PodGlyphDiskCache disk(){return disk;}
    static synchronized String identity(){return identity;}
    static String hash(byte[] bytes){try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    static synchronized void ensure(Context context) {
        if(context==null)return;
        String current=context.getApplicationInfo().sourceDir;
        if(current.equals(packagePath))return;
        packagePath=current;enabled=false;config=null;disk=null;faces.clear();families.clear();mappings.clear();
        try {
            byte[] manifest;
            try(InputStream input=context.getAssets().open("pod.manifest.json")){manifest=readBounded(input,2*1024*1024);}
            catch(FileNotFoundException standalone){return;}
            JSONObject root=new JSONObject(new String(manifest,StandardCharsets.UTF_8));config=root.optJSONObject("systemFonts");
            if(config==null)return;
            config.put("preloadFontSlots",root.optJSONArray("preloadFontSlots"));
            if(config.getInt("version")!=1||config.getLong("diskCacheBytes")!=PodGlyphDiskCache.MAX_BYTES)throw new IOException("system font contract");
            byte[] xml;
            try(InputStream input=new FileInputStream("/system/etc/fonts.xml")){xml=readBounded(input,2*1024*1024);}
            List<Family> parsed=parse(new ByteArrayInputStream(xml));
            MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(xml);digest.update(Build.FINGERPRINT.getBytes(StandardCharsets.UTF_8));
            digest.update("podjs-system-skia-v2".getBytes(StandardCharsets.UTF_8));
            String language=Locale.getDefault().getLanguage();
            ArrayList<Family> selected=new ArrayList<>();
            for(Family family:parsed)if(family.name.equals("sans-serif"))selected.add(family);
            for(Family family:parsed)if(family.name.isEmpty()&&(family.lang.isEmpty()||family.lang.startsWith("zh")||family.lang.startsWith("ja")||family.lang.startsWith("ko")||family.lang.equals("und-Zsye")||family.lang.equals("und-Zsym")||(!language.isEmpty()&&family.lang.startsWith(language))))selected.add(family);
            if(selected.isEmpty())throw new IOException("missing default font family");
            MessageDigest signature=MessageDigest.getInstance("SHA-256");signature.update(xml);signature.update(Build.FINGERPRINT.getBytes(StandardCharsets.UTF_8));
            signature.update("podjs-system-skia-v2".getBytes(StandardCharsets.UTF_8));
            TreeSet<String> names=new TreeSet<>();for(Family family:selected)for(Face face:family.faces)if(face.file!=null&&face.file.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))names.add(face.file);
            for(String name:names){File file=new File("/system/fonts",name);signature.update((name+":"+file.length()+":"+file.lastModified()+"\n").getBytes(StandardCharsets.UTF_8));}
            String fileSignature=hex(signature.digest());
            try{disk=new PodGlyphDiskCache(new File(context.getCacheDir(),"podjs-system-glyphs"),PodGlyphDiskCache.MAX_BYTES);}catch(IOException unavailable){disk=null;}
            // /system is an OS-owned immutable partition. Its build fingerprint,
            // XML digest and every selected file's identity invalidate this small
            // receipt; verified font content digests need not be recomputed on
            // every process start. The receipt is inside the same 16 MiB budget.
            String knownIdentity=disk==null?null:disk.identity(fileSignature);
            for(Family family:selected) {
                FontFamily.Builder builder=null;
                for(Face face:family.faces) {
                    if(!face.fallbackFor.isEmpty()&&!face.fallbackFor.equals("sans-serif"))continue;
                    if(face.file==null||!face.file.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")||face.index<0||face.weight<1||face.weight>1000)continue;
                    File file=new File("/system/fonts",face.file);if(!file.isFile())continue;
                    ByteBuffer buffer=mappings.get(face.file);
                    if(buffer==null) {
                        try(FileInputStream stream=new FileInputStream(file)){
                            long size=stream.getChannel().size();if(size<12||size>64*1024*1024)continue;
                            buffer=stream.getChannel().map(FileChannel.MapMode.READ_ONLY,0,size);
                        }
                        mappings.put(face.file,buffer);digest.update(face.file.getBytes(StandardCharsets.UTF_8));
                        // Hash through a small reusable stream buffer, rather than
                        // faulting every page into the retained font mapping's PSS.
                        if(knownIdentity==null)try(InputStream stream=new FileInputStream(file)){byte[] block=new byte[65536];int n;while((n=stream.read(block))!=-1)digest.update(block,0,n);}
                    }
                    try {
                        Font.Builder font=new Font.Builder(buffer.asReadOnlyBuffer()).setTtcIndex(face.index).setWeight(face.weight)
                            .setSlant(face.style.equals("italic")?FontStyle.FONT_SLANT_ITALIC:FontStyle.FONT_SLANT_UPRIGHT);
                        if(!face.axes.isEmpty())font.setFontVariationSettings(String.join(",",face.axes));
                        Font ready=font.build();if(builder==null)builder=new FontFamily.Builder(ready);else builder.addFont(ready);
                    }catch(IOException|IllegalArgumentException invalid){/* Continue through the XML fallback chain. */}
                }
                if(builder!=null)families.add(builder.build());
            }
            if(families.isEmpty())throw new IOException("no usable system font family");
            identity=knownIdentity==null?hex(digest.digest()):knownIdentity;enabled=true;
            if(disk!=null&&knownIdentity==null)disk.identity(fileSignature,identity);
            android.util.Log.i("PodJSPerf","systemFonts mappedFiles="+mappings.size()+" families="+families.size()+" identity="+identity+" diskLimit="+PodGlyphDiskCache.MAX_BYTES);
        }catch(Exception invalid) {
            config=null;throw new IllegalStateException("Cannot resolve configured system fonts",invalid);
        }
    }
    static synchronized Typeface typeface(int weight,boolean italic) {
        if(!enabled)return Typeface.create(Typeface.DEFAULT,weight,italic);
        int key=weight*2+(italic?1:0);Typeface known=faces.get(key);if(known!=null)return known;
        Typeface.CustomFallbackBuilder builder=new Typeface.CustomFallbackBuilder(families.get(0));
        int max=Typeface.CustomFallbackBuilder.getMaxCustomFallbackCount();
        for(int i=1;i<families.size()&&i<max;i++)builder.addCustomFallback(families.get(i));
        Typeface result=builder.setSystemFallback("sans-serif").setStyle(new FontStyle(weight,italic?FontStyle.FONT_SLANT_ITALIC:FontStyle.FONT_SLANT_UPRIGHT)).build();
        if(faces.size()>=32)faces.clear();faces.put(key,result);return result;
    }
    private static byte[] readBounded(InputStream input,int bound)throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=input.read(block))!=-1){if(output.size()+n>bound)throw new IOException("font metadata exceeds bound");output.write(block,0,n);}return output.toByteArray();
    }
}
