package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Generic verified complete media, with manifest-last publication. Network IO
 * belongs to the prefix pump; this store never opens an upstream of its own. */
final class PodMediaLocalStore {
    static final long ENTRY_LIMIT=8L<<20,TOTAL_LIMIT=40L<<20;
    final File root;
    File frame(String token){return new File(new File(cache.frameRoot,"local-media-frames"),token+".jpg");}
    final PodMediaPrefixCache cache;
    volatile int count=3,rate=256*1024,minBattery=20;
    volatile long entryLimit=ENTRY_LIMIT,totalLimit=TOTAL_LIMIT,minFree=96L<<20;
    volatile boolean enabled=true;
    PodMediaLocalStore(PodMediaPrefixCache cache){this.cache=cache;root=new File(cache.root,"complete-media");recover();}
    static String digest(File file)throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[64*1024];
        try(InputStream in=new FileInputStream(file)){for(int n;(n=in.read(buffer))>=0;)if(n>0)hash.update(buffer,0,n);}
        StringBuilder out=new StringBuilder();for(byte b:hash.digest())out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static String token(String key)throws Exception {if(key.isEmpty()||key.length()>2048)throw new IllegalArgumentException("invalid_local_key");return PodVideoFrameCache.hash(key);}
    File file(String token,String suffix){return new File(root,token+suffix);}
    private void recover(){
        File[] files=root.listFiles();if(files==null)return;
        for(File file:files){String name=file.getName();if(name.endsWith(".part")||name.endsWith(".tmp"))file.delete();
            else if(name.matches("[a-f0-9]{64}\\.mp4")&&!this.file(name.substring(0,64),".json").isFile())file.delete();}
    }
    synchronized void configure(JSONObject args)throws Exception {
        enabled=args.optBoolean("enabled",enabled);count=bounded(args,"count",count,1,5);
        entryLimit=bounded(args,"entryBytes",(int)entryLimit,128*1024,(int)ENTRY_LIMIT);
        totalLimit=bounded(args,"totalBytes",(int)totalLimit,128*1024,(int)TOTAL_LIMIT);
        rate=bounded(args,"bytesPerSecond",rate,16*1024,1024*1024);minBattery=bounded(args,"minBattery",minBattery,0,100);
        minFree=bounded(args,"minFreeBytes",(int)minFree,0,512*1024*1024);trim(null,0);
    }
    private static int bounded(JSONObject a,String key,int fallback,int min,int max)throws Exception {
        long value=a.has(key)?a.getLong(key):fallback;if(value<min||value>max)throw new IllegalArgumentException("invalid_store_budget");return (int)value;
    }
    synchronized JSONObject manifest(String token)throws Exception {
        File meta=file(token,".json");if(!meta.isFile()||meta.length()>32768)return null;
        try{JSONObject value=new JSONObject(new String(Files.readAllBytes(meta.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            long size=value.getLong("bytes");if((value.getInt("version")!=1&&value.getInt("version")!=2)||size<1||size>entryLimit||!value.getString("sha256").matches("[a-f0-9]{64}"))return null;
            return value;
        }catch(Exception invalid){return null;}
    }
    synchronized JSONObject lookupToken(String token)throws Exception {
        JSONObject value=manifest(token);File media=file(token,".mp4");
        if(value==null||!media.isFile()||media.length()!=value.getLong("bytes")||!digest(media).equals(value.getString("sha256"))){remove(token);return null;}
        // Older inventories did not describe tracks. Inspect the existing local bytes;
        // no URL refresh or network read is needed for this migration.
        if(value.optInt("version")==1){try{int[] tracks=tracks(media);value.put("version",2).put("audioTracks",tracks[0]).put("videoTracks",tracks[1]);writeManifest(token,value);}catch(Exception invalid){remove(token);return null;}}
        String uri=Uri.fromFile(media).toString();File pixels=frame(token);
        if(!pixels.isFile()||pixels.length()>400*1024||!digest(pixels).equals(value.optString("frameSha256"))){remove(token);return null;}
        JSONObject frame=value.optJSONObject("frame");if(frame==null||!frame.optBoolean("available")||!frame.optString("path").equals("local-media-frames/"+token+".jpg"))return null;
        cache.grantLocal(uri);return new JSONObject(value.toString()).put("uri",uri).put("frame",frame);
    }
    synchronized JSONArray list()throws Exception {
        File dir=new File(cache.frameRoot,"local-media-frames");for(File f:dir.listFiles()==null?new File[0]:dir.listFiles())if(f.getName().endsWith(".part")||f.getName().matches("[a-f0-9]{64}\\.jpg")&&!file(f.getName().substring(0,64),".json").isFile())f.delete();
        JSONArray result=new JSONArray();for(String token:ordered()) {JSONObject value=lookupToken(token);if(value!=null)result.put(value);}return result;
    }
    private List<String> ordered()throws Exception {
        List<String> tokens=new ArrayList<>();File[] files=root.listFiles((d,n)->n.matches("[a-f0-9]{64}\\.json"));
        if(files!=null)for(File f:files)tokens.add(f.getName().substring(0,64));
        tokens.sort(Comparator.comparingLong(t->{try{JSONObject v=manifest(t);return v==null?0:v.optLong("storedAt");}catch(Exception e){return 0L;}}));return tokens;
    }
    synchronized void watched(String key)throws Exception {String token=token(key);JSONObject value=manifest(token);if(value!=null){value.put("watched",true);writeManifest(token,value);}}
    synchronized void remove(String token){cache.revokeLocal(Uri.fromFile(file(token,".mp4")).toString());file(token,".json").delete();file(token,".mp4").delete();frame(token).delete();}
    synchronized void clear(){for(File f:root.listFiles()==null?new File[0]:root.listFiles())if(f.getName().matches("[a-f0-9]{64}\\.mp4"))cache.revokeLocal(Uri.fromFile(f).toString());File frames=new File(cache.frameRoot,"local-media-frames");for(File f:frames.listFiles()==null?new File[0]:frames.listFiles())if(f.getName().matches("[a-f0-9]{64}\\.(jpg|part)"))f.delete();for(File f:root.listFiles()==null?new File[0]:root.listFiles())if(f.getName().matches("[a-f0-9]{64}\\.(json|mp4|part|tmp)"))f.delete();}
    private void writeManifest(String token,JSONObject value)throws Exception {
        File part=file(token,".tmp");try{try(FileOutputStream out=new FileOutputStream(part)){out.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
            Files.move(part.toPath(),file(token,".json").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{part.delete();}
    }
    synchronized boolean trim(String protectedToken,long incoming)throws Exception {
        List<String> tokens=ordered();tokens.sort(Comparator.comparingInt(t->{try{JSONObject v=manifest(t);return v==null||v.optBoolean("watched")?0:1;}catch(Exception e){return 0;}}));
        long bytes=0;for(String t:tokens)bytes+=file(t,".mp4").length()+frame(t).length();int size=tokens.size();
        for(String t:tokens){if(size+(incoming>0?1:0)<=count&&bytes+incoming<=totalLimit)break;if(t.equals(protectedToken)||cache.localPinned(t))continue;bytes-=file(t,".mp4").length()+frame(t).length();size--;remove(t);}
        return size+(incoming>0?1:0)<=count&&bytes+incoming<=totalLimit;
    }
    /** One pump turn transfers at most 64 KiB. Partial files are never playable. */
    void step(PodMediaPrefixCache.Job job)throws Exception {
        PodMediaPrefixCache.backgroundTurn(job);
        File part=file(job.localToken,".part");long position=part.length();
        if(job.entry.length<0){byte[] first=cache.loadRange(job,0,1);if(job.entry.length<=0||job.entry.length>entryLimit||job.entry.length>totalLimit)throw new IOException("complete_size_unavailable_or_over_budget");
            synchronized(this){if(!trim(job.localToken,job.entry.length+400*1024))throw new IOException("complete_store_pinned");}
            try(FileOutputStream out=new FileOutputStream(part)){out.write(first);}position=1;}
        if(job.entry.length>entryLimit||job.entry.length>totalLimit)throw new IOException("complete_over_budget");
        if(position==0)synchronized(this){if(!trim(job.localToken,job.entry.length+400*1024))throw new IOException("complete_store_pinned");}
        int n=(int)Math.min(PodMediaPrefixCache.CHUNK,job.entry.length-position);
        if(n>0){byte[] bytes=cache.loadRange(job,position,n);PodMediaPrefixCache.backgroundTurn(job);try(FileOutputStream out=new FileOutputStream(part,true)){out.write(bytes);}position+=n;}
        if(position==job.entry.length){
            PodMediaPrefixCache.backgroundTurn(job);publish(job,part);job.done=true;
        }else job.deferredUntil=android.os.SystemClock.uptimeMillis()+Math.max(1,n*1000L/rate);
    }
    static int[] tracks(File file)throws Exception {
        MediaExtractor extractor=new MediaExtractor();int audio=0,video=0;
        try{extractor.setDataSource(file.getAbsolutePath());for(int i=0;i<extractor.getTrackCount();i++){String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/"))audio++;if(mime!=null&&mime.startsWith("video/"))video++;}}finally{extractor.release();}
        if(video==0)throw new IOException("complete_video_track_missing");return new int[]{audio,video};
    }
    static void requireTracks(int audio,int video,boolean requireAudio)throws IOException {if(video<1||requireAudio&&audio<1)throw new IOException("complete_track_missing");}
    private void publish(PodMediaPrefixCache.Job job,File part)throws Exception {
        if(!job.current()||part.length()!=job.entry.length)throw new IOException("complete_cancelled");
        int[] tracks=tracks(part);requireTracks(tracks[0],tracks[1],job.requireAudio);
        File media=file(job.localToken,".mp4");String hash=digest(part);
        try(RandomAccessFile synced=new RandomAccessFile(part,"rw")){synced.getFD().sync();}
        Bitmap pixels=null;MediaMetadataRetriever decoder=new MediaMetadataRetriever();
        try{decoder.setDataSource(part.getAbsolutePath());pixels=decoder.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,320);}finally{decoder.release();}
        if(pixels==null)throw new IOException("complete_frame_missing");
        try{
            PodMediaPrefixCache.backgroundTurn(job);
            synchronized(this){
                if(!job.current()||!trim(job.localToken,part.length()+400*1024))throw new IOException("complete_cancelled");
                Files.move(part.toPath(),media.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                File image=frame(job.localToken);image.getParentFile().mkdirs();File imagePart=new File(image.getParentFile(),job.localToken+".part");
                try{try(FileOutputStream out=new FileOutputStream(imagePart)){if(!pixels.compress(Bitmap.CompressFormat.JPEG,85,out))throw new IOException("complete_frame_encode");out.getFD().sync();}
                    if(imagePart.length()>400*1024)throw new IOException("complete_frame_over_budget");
                    Files.move(imagePart.toPath(),image.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }finally{imagePart.delete();}
                JSONObject frame=new JSONObject().put("available",true).put("kind","actual_frame").put("path","local-media-frames/"+job.localToken+".jpg").put("width",pixels.getWidth()).put("height",pixels.getHeight()).put("capturedGeneration",0).put("capturedAt",System.currentTimeMillis()).put("positionMs",0);
                JSONObject value=new JSONObject().put("version",2).put("audioTracks",tracks[0]).put("videoTracks",tracks[1]).put("key",job.localKey).put("metadata",job.localMetadata).put("bytes",media.length()).put("sha256",hash)
                    .put("storedAt",System.currentTimeMillis()).put("watched",false).put("frame",frame).put("frameSha256",digest(image));
                writeManifest(job.localToken,value);android.util.Log.i("PodMediaCache","complete_store phase=ready entry="+job.entry.ordinal+" bytes="+media.length()+" audioTracks="+tracks[0]+" videoTracks="+tracks[1]);
            }
        }finally{pixels.recycle();}
    }
}
