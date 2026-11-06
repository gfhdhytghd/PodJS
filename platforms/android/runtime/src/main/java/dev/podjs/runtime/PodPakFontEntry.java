package dev.podjs.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Validated raw font slice in the same authenticated DCPK directory as the UI. */
final class PodPakFontEntry {
    final String key,sha256,directorySha256;
    final long offset,bytes;
    final int directoryBytes;
    private PodPakFontEntry(String key,String sha,long offset,long bytes,int directoryBytes,String directorySha) {
        this.key=key;sha256=sha;this.offset=offset;this.bytes=bytes;this.directoryBytes=directoryBytes;directorySha256=directorySha;
    }
    static PodPakFontEntry find(JSONObject manifest,String name) throws Exception {
        JSONObject assets=manifest.optJSONObject("assets"),asset=assets==null?null:assets.optJSONObject(name);
        JSONObject alias=asset==null?null:asset.optJSONObject("pakFont");
        if(alias==null)return null;
        String sha=alias.getString("sha256"),key=alias.getString("key");
        require(sha.matches("[a-f0-9]{64}")&&key.equals("ui:font-source."+sha),"invalid font source key");
        long offset=integer(alias,"offset"),bytes=integer(alias,"bytes");
        require(bytes>0&&bytes<=64L*1024*1024&&offset>=32&&offset%16==0,"invalid font slice");
        require(integer(asset,"size")==bytes&&sha.equals(asset.getString("sha256")),"asset/source mismatch");
        JSONObject integrity=manifest.getJSONObject("pakIntegrity");
        require("sha256-per-entry-v1".equals(integrity.getString("format")),"unsupported pak integrity");
        long directory=integer(integrity,"directoryBytes");
        require(directory>=32&&directory<=1024*1024&&directory%16==0&&offset>=directory,"invalid directory size");
        String directorySha=integrity.getString("directorySha256");
        require(directorySha.matches("[a-f0-9]{64}"),"invalid directory digest");
        JSONArray entries=integrity.getJSONArray("entries");int matches=0;
        for(int i=0;i<entries.length();i++) {
            JSONObject entry=entries.getJSONObject(i);
            if(!key.equals(entry.getString("key")))continue;
            require(integer(entry,"offset")==offset&&integer(entry,"bytes")==bytes&&sha.equals(entry.getString("sha256")),"font integrity mismatch");
            matches++;
        }
        require(matches==1,"missing or duplicated font integrity");
        return new PodPakFontEntry(key,sha,offset,bytes,(int)directory,directorySha);
    }
    void verifyDirectory(byte[] directory,long pakLength) throws Exception {
        require(directory.length==directoryBytes&&offset<=pakLength&&bytes<=pakLength-offset,"font range outside pak");
        ByteBuffer b=ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN);
        require(b.getInt(0)==0x4b504344&&b.getShort(4)==1&&b.getShort(6)==0&&b.getInt(28)==0,"invalid pak header");
        require(u32(b,24)==pakLength&&u32(b,20)==directoryBytes,"pak length mismatch");
        String actual=hex(MessageDigest.getInstance("SHA-256").digest(directory));
        require(actual.equals(directorySha256),"pak directory digest mismatch");
        long count=u32(b,8),records=u32(b,12),names=u32(b,16);
        require(records==32&&names==32+count*24&&names<=directoryBytes,"invalid pak directory");
        int matches=0;
        for(int i=0;i<count;i++) {
            int e=32+i*24;long start=names+u32(b,e+12),length=Short.toUnsignedInt(b.getShort(e+16));
            require(start<=directoryBytes&&length<=directoryBytes-start,"invalid pak name range");
            byte[] encoded=key.getBytes(StandardCharsets.UTF_8);
            if(length!=encoded.length||!Arrays.equals(encoded,Arrays.copyOfRange(directory,(int)start,(int)(start+length))))continue;
            require(u32(b,e+4)==offset&&u32(b,e+8)==bytes&&directory[e+19]==0&&b.getInt(e+20)==0,"pak font record mismatch");
            matches++;
        }
        require(matches==1,"missing or duplicated pak font");
    }
    void verifyFont(ByteBuffer font) throws Exception {
        require(font.remaining()==bytes,"font buffer length mismatch");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update(font.asReadOnlyBuffer());
        require(hex(digest.digest()).equals(sha256),"font source digest mismatch");
    }
    static long integer(JSONObject value,String key) throws Exception {
        Object number=value.get(key);require(number instanceof Number,"font metadata is not numeric");
        double d=((Number)number).doubleValue();long n=((Number)number).longValue();
        require(Double.isFinite(d)&&d>=0&&d<=Integer.MAX_VALUE&&d==n,"invalid font metadata integer");return n;
    }
    private static long u32(ByteBuffer b,int offset){return Integer.toUnsignedLong(b.getInt(offset));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static void require(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
}
