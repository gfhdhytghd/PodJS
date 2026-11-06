package dev.podjs.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PakFontEntryTest {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError();}
    interface Checked {void run()throws Exception;}
    static void rejected(Checked f)throws Exception{try{f.run();throw new AssertionError("accepted malformed alias");}catch(java.io.IOException expected){checks++;}}
    static String sha(byte[] data)throws Exception{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))s.append(String.format("%02x",b&255));return s.toString();}
    public static void main(String[] args)throws Exception {
        byte[] font="TTF!".getBytes(StandardCharsets.UTF_8);String digest=sha(font),key="ui:font-source."+digest;
        byte[] name=key.getBytes(StandardCharsets.UTF_8);int directoryBytes=(32+24+name.length+15)&~15;
        byte[] directory=new byte[directoryBytes];ByteBuffer b=ByteBuffer.wrap(directory).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,0x4b504344);b.putShort(4,(short)1);b.putInt(8,1);b.putInt(12,32);b.putInt(16,56);b.putInt(20,directoryBytes);b.putInt(24,directoryBytes+16);
        int hash=0x811c9dc5;for(char c:key.toCharArray())hash=(hash^c)*0x01000193;
        b.putInt(32,hash);b.putInt(36,directoryBytes);b.putInt(40,font.length);b.putShort(48,(short)name.length);directory[50]=1;System.arraycopy(name,0,directory,56,name.length);
        JSONObject slice=new JSONObject().put("key",key).put("offset",directoryBytes).put("bytes",font.length).put("sha256",digest);
        JSONObject asset=new JSONObject().put("size",font.length).put("sha256",digest).put("pakFont",new JSONObject(slice.toString()));
        JSONObject integrity=new JSONObject().put("format","sha256-per-entry-v1").put("directoryBytes",directoryBytes).put("directorySha256",sha(directory)).put("entries",new JSONArray().put(slice));
        JSONObject manifest=new JSONObject().put("assets",new JSONObject().put("fonts/body.ttf",asset)).put("pakIntegrity",integrity);
        check(PodPakFontEntry.find(new JSONObject(),"fonts/body.ttf")==null);
        check(PodPakFontEntry.find(manifest,"fonts/other.ttf")==null);
        PodPakFontEntry entry=PodPakFontEntry.find(manifest,"fonts/body.ttf");
        check(entry.offset==directoryBytes&&entry.bytes==font.length);entry.verifyDirectory(directory,directoryBytes+16);checks++;
        entry.verifyFont(ByteBuffer.wrap(font));checks++;
        byte[] wrong=font.clone();wrong[0]^=1;rejected(()->entry.verifyFont(ByteBuffer.wrap(wrong)));
        rejected(()->entry.verifyFont(ByteBuffer.allocate(font.length-1)));
        rejected(()->entry.verifyDirectory(directory,directoryBytes+3));
        byte[] corrupt=directory.clone();corrupt[56]^=1;rejected(()->entry.verifyDirectory(corrupt,directoryBytes+16));
        JSONObject alias=asset.getJSONObject("pakFont");alias.put("offset",directoryBytes+.5);rejected(()->PodPakFontEntry.find(manifest,"fonts/body.ttf"));alias.put("offset",directoryBytes);
        alias.put("sha256","0".repeat(64));rejected(()->PodPakFontEntry.find(manifest,"fonts/body.ttf"));alias.put("sha256",digest);
        asset.put("size",font.length+1);rejected(()->PodPakFontEntry.find(manifest,"fonts/body.ttf"));asset.put("size",font.length);
        integrity.getJSONArray("entries").put(new JSONObject(slice.toString()));rejected(()->PodPakFontEntry.find(manifest,"fonts/body.ttf"));
        integrity.put("entries",new JSONArray());rejected(()->PodPakFontEntry.find(manifest,"fonts/body.ttf"));
        integrity.put("entries",new JSONArray().put(slice));
        // Even a directory re-signed in metadata cannot point the Font API to another range.
        b.putInt(36,directoryBytes+16);integrity.put("directorySha256",sha(directory));
        PodPakFontEntry altered=PodPakFontEntry.find(manifest,"fonts/body.ttf");rejected(()->altered.verifyDirectory(directory,directoryBytes+16));
        System.out.println("PakFontEntryTest PASS: "+checks+" checks; exact source/range/directory, legacy fallback and malformed aliases");
    }
}
