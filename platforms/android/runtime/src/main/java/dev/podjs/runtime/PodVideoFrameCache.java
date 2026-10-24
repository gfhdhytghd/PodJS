package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.TextureView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded actual TextureView frames, independent from covers and playable bytes.
 * Disk contains pixels and digests only, never remote URLs or credentials. A
 * restored picture grants no playback capability or prefix registration. */
final class PodVideoFrameCache {
    static final int MAX_EDGE=320,MAX_ENTRIES=6;
    static final ExecutorService IO=new java.util.concurrent.ThreadPoolExecutor(1,1,0L,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"PodVideoFrames");t.setDaemon(true);return t;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final File root;
    PodVideoFrameCache(File files){root=new File(files,"video-frames");}
    static String hash(String value)throws Exception {StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();}
    static String key(JSONObject args)throws Exception {String key=args.getString("key");if(key.isEmpty()||key.length()>256||key.indexOf('\0')>=0)throw new IllegalArgumentException("invalid_frame_key");return key;}
    static String binding(JSONObject source,PodMediaPrefixCache cache)throws Exception {
        if(source==null)throw new IllegalArgumentException("frame_source_required");
        TreeMap<String,String> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        JSONObject raw=source.optJSONObject("headers");int bytes=0;
        if(raw!=null){if(raw.length()>32)throw new IllegalArgumentException("invalid_frame_headers");for(Iterator<String> it=raw.keys();it.hasNext();){String name=it.next(),value=raw.getString(name);bytes+=name.length()+value.length();if(!name.matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")||value.indexOf('\r')>=0||value.indexOf('\n')>=0||bytes>32768)throw new IllegalArgumentException("invalid_frame_headers");headers.put(name.toLowerCase(Locale.ROOT),value);}}
        JSONArray urls=new JSONArray();boolean present=false;
        for(String name:new String[]{"url","videoUrl","audioUrl"}){
            String value=source.optString(name,"");if(!value.isEmpty())present=true;
            if(value.length()>32768)throw new IllegalArgumentException("invalid_frame_source");
            if(value.startsWith(PodMediaPrefixCache.SCHEME+":")){
                if(cache==null)throw new IllegalArgumentException("media_cache_registration_missing");
                PodMediaPrefixCache.Entry entry=cache.target(value);value=entry.remote;for(Map.Entry<String,String> h:entry.headers.entrySet())headers.put(h.getKey().toLowerCase(Locale.ROOT),h.getValue());
            }
            if(value.startsWith("http:")||value.startsWith("https:")){Uri uri=Uri.parse(value);if(uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException("invalid_frame_source");}
            urls.put(value);
        }
        if(!present)throw new IllegalArgumentException("frame_source_required");
        JSONArray pairs=new JSONArray();for(Map.Entry<String,String> h:headers.entrySet())pairs.put(new JSONArray().put(h.getKey()).put(h.getValue()));
        return hash(new JSONArray().put(urls).put(pairs).toString());
    }
    static Bitmap capture(TextureView view,boolean rendered,long generation,JSONObject args)throws Exception {
        if(view==null||!rendered||generation!=args.getLong("sourceGeneration")||view.getWidth()<1||view.getHeight()<1)throw new IllegalStateException("frame_source_changed");
        double scale=Math.min(1d,MAX_EDGE/(double)Math.max(view.getWidth(),view.getHeight()));
        Bitmap bitmap=view.getBitmap(Math.max(1,(int)(view.getWidth()*scale)),Math.max(1,(int)(view.getHeight()*scale)));
        if(bitmap==null)throw new IllegalStateException("frame_unavailable");return bitmap;
    }
    synchronized JSONObject save(String key,String binding,long generation,long position,Bitmap frame)throws Exception {
        if(frame.getWidth()>MAX_EDGE||frame.getHeight()>MAX_EDGE)throw new IllegalArgumentException("frame_too_large");
        root.mkdirs();String token=hash(key+"\n"+binding);File image=new File(root,token+".jpg"),meta=new File(root,token+".json");
        File temporary=File.createTempFile("frame-",".tmp",root);
        try{try(OutputStream output=new FileOutputStream(temporary)){if(!frame.compress(Bitmap.CompressFormat.JPEG,85,output))throw new IOException("frame_encode_failed");}if(temporary.length()>1024*1024)throw new IOException("frame_too_large");Files.move(temporary.toPath(),image.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            JSONObject value=new JSONObject().put("version",1).put("binding",binding).put("capturedGeneration",generation).put("positionMs",Math.max(0,position)).put("capturedAt",System.currentTimeMillis()).put("width",frame.getWidth()).put("height",frame.getHeight());
            File temporaryMeta=File.createTempFile("meta-",".tmp",root);try{try(Writer writer=new OutputStreamWriter(new FileOutputStream(temporaryMeta),StandardCharsets.UTF_8)){writer.write(value.toString());}Files.move(temporaryMeta.toPath(),meta.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}finally{temporaryMeta.delete();}
            trim(token);return response(token,value);
        }finally{temporary.delete();}
    }
    synchronized JSONObject restore(String key,String binding)throws Exception {
        String token=hash(key+"\n"+binding);File meta=new File(root,token+".json"),image=new File(root,token+".jpg");
        if(!meta.isFile()||!image.isFile()||meta.length()>4096||image.length()>1024*1024)return new JSONObject().put("available",false);
        try{JSONObject value=new JSONObject(new String(Files.readAllBytes(meta.toPath()),StandardCharsets.UTF_8));BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(image.getPath(),bounds);
            if(value.optInt("version")!=1||!binding.equals(value.optString("binding"))||bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>MAX_EDGE||bounds.outHeight>MAX_EDGE)return new JSONObject().put("available",false);
            image.setLastModified(System.currentTimeMillis());return response(token,value);
        }catch(Exception invalid){return new JSONObject().put("available",false);}
    }
    synchronized JSONObject clear()throws Exception {int files=0;File[] entries=root.listFiles();if(entries!=null)for(File file:entries)if(file.isFile()&&file.getName().matches("[a-f0-9]{64}\\.(jpg|json)")){if(!file.delete())throw new IOException("frame_clear_failed");files++;}return new JSONObject().put("removedFiles",files);}
    private JSONObject response(String token,JSONObject value)throws Exception {JSONObject result=new JSONObject(value.toString());result.remove("binding");return result.put("available",true).put("kind","actual_frame").put("path","video-frames/"+token+".jpg");}
    private void trim(String protectedToken){File[] images=root.listFiles((d,n)->n.matches("[a-f0-9]{64}\\.jpg"));if(images==null)return;Arrays.sort(images,Comparator.comparingLong(File::lastModified).reversed());int keep=MAX_ENTRIES-1;for(File image:images){if(image.getName().equals(protectedToken+".jpg"))continue;if(keep-->0)continue;image.delete();new File(root,image.getName().replace(".jpg",".json")).delete();}}
}
