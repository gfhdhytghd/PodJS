package dev.podjs.runtime;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;

/** Independent, bounded content projection IO. Never touches application bodies.
 * Data fsync precedes checkpoint rename + directory fsync and the returned ACK.
 * An uncheckpointed tail is truncated on retry: at most one 64KiB frame replays. */
final class PodContentFiles {
    private final File root;
    private static final Object[] LOCKS=new Object[64];
    static { for(int i=0;i<LOCKS.length;i++)LOCKS[i]=new Object(); }
    PodContentFiles(File files)throws IOException {root=new File(files,"content-v2").getCanonicalFile();if(!root.isDirectory()){if(!root.mkdirs())throw new IOException("content_storage_unavailable");syncDir(root.getParentFile());}}
    private File path(String p)throws IOException {if(p.contains("..")||!p.matches("[a-f0-9]{64}(?:/[a-z0-9.-]{1,128})?"))throw new IllegalArgumentException("invalid_content_path");File f=new File(root,p).getCanonicalFile();if(!f.toPath().startsWith(root.toPath()))throw new IllegalArgumentException("invalid_content_path");if(!f.getParentFile().isDirectory()){if(!f.getParentFile().mkdirs())throw new IOException("content_storage_unavailable");syncDir(f.getParentFile().getParentFile());}return f;}
    private Object lock(File f){return LOCKS[(f.getPath().hashCode()&0x7fffffff)%LOCKS.length];}
    private static void syncDir(File dir)throws IOException {try(FileChannel c=FileChannel.open(dir.toPath(),StandardOpenOption.READ)){c.force(true);}}
    private static void atomic(File f,byte[] b)throws IOException {File t=File.createTempFile(".content-",".part",f.getParentFile());try {try(FileOutputStream o=new FileOutputStream(t)){o.write(b);o.getFD().sync();}Files.move(t.toPath(),f.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDir(f.getParentFile());}finally{t.delete();}}
    private static byte[] read(File f,int max)throws IOException {if(f.length()>max)throw new IOException("content_batch_limit");return Files.readAllBytes(f.toPath());}
    private static JSONObject cp(File f)throws Exception {File c=new File(f.getPath()+".checkpoint");return c.exists()?new JSONObject(new String(read(c,16384),java.nio.charset.StandardCharsets.UTF_8)):null;}
    private static int number(JSONObject a,String key,int max)throws Exception {Object v=a.get(key);if(!(v instanceof Number))throw new IllegalArgumentException("invalid_content_number");double d=((Number)v).doubleValue();if(d<0||d>max||d!=Math.rint(d))throw new IllegalArgumentException("invalid_content_number");return (int)d;}
    private static byte[] decode(JSONObject a)throws Exception {String s=a.getString("dataBase64");if(s.length()>355024)throw new IllegalArgumentException("content_batch_limit");return Base64.decode(s,Base64.DEFAULT);}
    JSONObject execute(String method,JSONObject a)throws Exception {
        if("content.files.stat".equals(method)){JSONArray paths=a.getJSONArray("paths"),out=new JSONArray();if(paths.length()>32)throw new IllegalArgumentException("content_batch_limit");for(int i=0;i<paths.length();i++){String p=paths.getString(i);File f=path(p);synchronized(lock(f)){JSONObject c=cp(f);int offset=c==null?0:number(c,"durableOffset",266264);if(offset>f.length())throw new IOException("content_checkpoint_corrupt");out.put(new JSONObject().put("path",p).put("exists",f.isFile()).put("size",f.length()).put("durableOffset",offset).put("checkpoint",c==null?JSONObject.NULL:c.getString("checkpoint")));}}return new JSONObject().put("entries",out);}
        if("content.files.read".equals(method)){JSONArray ranges=a.getJSONArray("ranges"),out=new JSONArray();if(ranges.length()>32)throw new IllegalArgumentException("content_batch_limit");int total=0;for(int i=0;i<ranges.length();i++){JSONObject r=ranges.getJSONObject(i);int offset=number(r,"offset",266264),n=number(r,"length",262144);total+=n;if(total>262144)throw new IllegalArgumentException("content_batch_limit");File f=path(r.getString("path"));byte[] b=new byte[n];synchronized(lock(f)){try(RandomAccessFile in=new RandomAccessFile(f,"r")){if((long)offset+n>in.length())throw new IOException("invalid_content_range");in.seek(offset);in.readFully(b);}}out.put(new JSONObject().put("dataBase64",Base64.encodeToString(b,Base64.NO_WRAP)));}return new JSONObject().put("entries",out);}
        File f=path(a.getString("path"));synchronized(lock(f)){
            if("content.files.frame".equals(method)){
                int offset=number(a,"offset",266264);byte[] b=decode(a);String checkpoint=a.getString("checkpoint");if(offset%65536!=0||b.length<1||b.length>65536||offset+b.length>266264||checkpoint.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException("invalid_content_frame");
                JSONObject saved=cp(f);int durable=saved==null?0:number(saved,"durableOffset",266264);
                if(offset<durable){try(RandomAccessFile in=new RandomAccessFile(f,"r")){byte[] prior=new byte[b.length];in.seek(offset);in.readFully(prior);if(offset+b.length>durable||!Arrays.equals(prior,b))throw new IOException("part_conflict");}return new JSONObject().put("durableOffset",durable);}
                if(offset!=durable)throw new IOException("offset_conflict");
                try(RandomAccessFile out=new RandomAccessFile(f,"rw")){if(out.length()<durable)throw new IOException("content_checkpoint_corrupt");out.setLength(durable);out.seek(durable);out.write(b);out.getFD().sync();}
                atomic(new File(f.getPath()+".checkpoint"),new JSONObject().put("durableOffset",offset+b.length).put("checkpoint",checkpoint).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return new JSONObject().put("durableOffset",offset+b.length);
            }
            if("content.files.publish".equals(method)||"content.files.replace".equals(method)){
                byte[] b=decode(a);boolean replace=method.endsWith("replace");if(b.length>(replace?32768:266264))throw new IllegalArgumentException("content_batch_limit");
                if(!replace&&f.exists()){if(!Arrays.equals(read(f,266264),b))throw new IOException("part_conflict");}
                else atomic(f,b);
                if(a.has("checkpoint")){String marker=a.getString("checkpoint");if(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)throw new IllegalArgumentException("invalid_content_checkpoint");atomic(new File(f.getPath()+".checkpoint"),new JSONObject().put("durableOffset",b.length).put("checkpoint",marker).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                return new JSONObject().put("size",b.length);
            }
            throw new IllegalArgumentException("unsupported_content_method");
        }
    }
}
