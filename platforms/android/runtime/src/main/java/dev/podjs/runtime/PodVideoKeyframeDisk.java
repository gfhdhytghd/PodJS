package dev.podjs.runtime;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Looper;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

/** Worker-only compressed frame-zero LRU. The registered source binding owns
 * pixels, never playback authorization. No source URL or headers reach disk. */
final class PodVideoKeyframeDisk {
    static final int MAX_ENTRIES=128,MAX_FILE_BYTES=512*1024;
    static final long MAX_BYTES=10L*1024*1024;
    private static final Pattern KEY=Pattern.compile("[a-f0-9]{64}");
    private static final Pattern IMAGE=Pattern.compile("[a-f0-9]{64}\\.jpg");
    private final File root;private long maxBytes=MAX_BYTES;
    synchronized void budget(long bytes){maxBytes=Math.min(MAX_BYTES,Math.max(128*1024,bytes));trim();}
    PodVideoKeyframeDisk(File root){this.root=root;File[] parts=root.listFiles((d,n)->n.startsWith("keyframe-")&&n.endsWith(".tmp"));if(parts!=null)for(File part:parts)part.delete();}
    synchronized boolean contains(String key){File f=file(key);return f.isFile()&&f.length()>0&&f.length()<=MAX_FILE_BYTES;}
    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("keyframe_disk_on_main");}
    private File file(String key){if(key==null||!KEY.matcher(key).matches())throw new IllegalArgumentException("invalid_frame_binding");return new File(root,key+".jpg");}
    synchronized Bitmap load(String key){
        worker();File image=file(key);
        if(!image.isFile()||image.length()<1||image.length()>MAX_FILE_BYTES)return null;
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(image.getPath(),bounds);
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>PodVideoKeyframes.MAX_EDGE||bounds.outHeight>PodVideoKeyframes.MAX_EDGE){image.delete();return null;}
        Bitmap pixels=BitmapFactory.decodeFile(image.getPath());
        if(pixels==null){image.delete();return null;}
        image.setLastModified(System.currentTimeMillis());return pixels;
    }
    synchronized void save(String key,Bitmap pixels)throws IOException {
        worker();File image=file(key);
        if(pixels.getWidth()>PodVideoKeyframes.MAX_EDGE||pixels.getHeight()>PodVideoKeyframes.MAX_EDGE)throw new IllegalArgumentException("frame_too_large");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("frame_directory");
        File temporary=File.createTempFile("keyframe-",".tmp",root);
        try{
            try(OutputStream output=new FileOutputStream(temporary)){if(!pixels.compress(Bitmap.CompressFormat.JPEG,85,output))throw new IOException("frame_encode");}
            if(temporary.length()>MAX_FILE_BYTES)throw new IOException("frame_too_large");
            Files.move(temporary.toPath(),image.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            trim();
        }finally{temporary.delete();}
    }
    synchronized void clear(){
        worker();File[] files=root.listFiles();if(files==null)return;
        for(File image:files)if(IMAGE.matcher(image.getName()).matches()||image.getName().startsWith("keyframe-")&&image.getName().endsWith(".tmp"))image.delete();
    }
    private void trim(){
        File[] files=root.listFiles((dir,name)->IMAGE.matcher(name).matches());if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());
        long bytes=0;int count=0;
        for(File image:files){long size=image.length();if(count<MAX_ENTRIES&&bytes+size<=maxBytes){bytes+=size;count++;}else image.delete();}
    }
}
