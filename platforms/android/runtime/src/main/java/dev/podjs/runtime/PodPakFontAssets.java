package dev.podjs.runtime;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.WeakHashMap;
import org.json.JSONObject;

/** Font and UI parsers map the same immutable APK pages. No TTF heap/file copy. */
final class PodPakFontAssets {
    private static final WeakHashMap<AssetManager,JSONObject> manifests=new WeakHashMap<>();
    private static final WeakHashMap<AssetManager,Boolean> collected=new WeakHashMap<>();
    private static synchronized boolean firstLargeFont(AssetManager manager,long bytes) {
        if(bytes<8L*1024*1024||collected.containsKey(manager))return false;
        collected.put(manager,Boolean.TRUE);return true;
    }
    private static synchronized JSONObject manifest(AssetManager manager) throws Exception {
        JSONObject known=manifests.get(manager);if(known!=null)return known;
        try(InputStream input=manager.open("pod.manifest.json")) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
            while((n=input.read(block))!=-1){if(bytes.size()+n>2*1024*1024)throw new IOException("font manifest exceeds limit");bytes.write(block,0,n);}
            known=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));manifests.put(manager,known);return known;
        }
    }
    static Typeface resolve(AssetManager manager,String name,int face,int weight,boolean italic,String variation) throws Exception {
        // A missing manifest preserves standalone AAR/legacy asset behavior.
        JSONObject metadata;
        try{metadata=manifest(manager);}catch(java.io.FileNotFoundException absent){return null;}
        PodPakFontEntry entry=PodPakFontEntry.find(metadata,name);if(entry==null)return null;
        try(AssetFileDescriptor source=manager.openFd("main.pak");FileInputStream input=source.createInputStream()) {
            long length=source.getLength(),start=source.getStartOffset();
            FileChannel channel=input.getChannel();byte[] directory=new byte[entry.directoryBytes];ByteBuffer target=ByteBuffer.wrap(directory);
            while(target.hasRemaining()){int n=channel.read(target,start+target.position());if(n<=0)throw new IOException("truncated font pak directory");}
            entry.verifyDirectory(directory,length);
            if(start<0||start>Long.MAX_VALUE-entry.offset)throw new IOException("font offset overflow");
            ByteBuffer mapped=channel.map(FileChannel.MapMode.READ_ONLY,start+entry.offset,entry.bytes);
            entry.verifyFont(mapped);
            Font.Builder builder=new Font.Builder(mapped).setTtcIndex(face).setWeight(weight).setSlant(italic?FontStyle.FONT_SLANT_ITALIC:FontStyle.FONT_SLANT_UPRIGHT);
            if(!variation.trim().isEmpty())builder.setFontVariationSettings(variation);
            Font font=builder.build();
            // Same family/style/fallback construction as Typeface.Builder.build().
            Typeface result=new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build()).setStyle(new FontStyle(weight,italic?FontStyle.FONT_SLANT_ITALIC:FontStyle.FONT_SLANT_UPRIGHT)).build();
            if(firstLargeFont(manager,entry.bytes)) {
                // The legacy compressed-asset path allocates the entire font on
                // ART's heap and thereby collects startup temporaries. A mapped
                // font removes that pressure. Request collection once after the
                // first large font is live, instead of retaining those dead Java
                // objects and their native cleaners until unrelated pressure.
                long began=PodStartupTrace.now();
                Runtime.getRuntime().gc();
                PodStartupTrace.stage("pakFontFirstCollection",began);
            }
            return result;
        }
    }
}
