package dev.podjs.runtime;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Shared size index for foreground and headless owners of one prefix directory. */
final class PodMediaPrefixDisk {
    private static final Map<File,WeakReference<PodMediaPrefixDisk>> directories=new WeakHashMap<>();
    private final File root;
    private final Map<String,Long> sizes=new HashMap<>();
    private final LinkedHashMap<String,Handle> handles=new LinkedHashMap<>(8,.75f,true);
    private long bytes;
    private static final class Handle {
        final RandomAccessFile file;
        final boolean writable;
        Handle(File path,boolean write)throws IOException{file=new RandomAccessFile(path,write?"rw":"r");writable=write;}
    }

    private PodMediaPrefixDisk(File root){this.root=root;}
    static synchronized PodMediaPrefixDisk open(File root){
        File path=root.getAbsoluteFile();
        WeakReference<PodMediaPrefixDisk> ref=directories.get(path);
        PodMediaPrefixDisk disk=ref==null?null:ref.get();
        if(disk==null){disk=new PodMediaPrefixDisk(path);directories.put(path,new WeakReference<>(disk));}
        return disk;
    }
    synchronized void refresh(File[] prefixes){
        sizes.clear();bytes=0;
        for(File prefix:prefixes){
            String name=prefix.getName();
            update(name.substring(0,name.length()-7),prefix);
        }
    }
    synchronized void update(String token,File prefix){
        Long old=sizes.remove(token);
        if(old!=null)bytes-=old;
        if(prefix.isFile()){
            long size=prefix.length()+new File(root,token+".tail").length();
            sizes.put(token,size);bytes+=size;
        }
    }
    synchronized long bytes(){return bytes;}
    synchronized int count(){return sizes.size();}
    synchronized boolean exceeds(long budget,int entries){return bytes>budget||sizes.size()>entries;}
    private RandomAccessFile handle(String token,File prefix,boolean write)throws IOException{
        Handle existing=handles.get(token);
        if(existing!=null&&(!write||existing.writable))return existing.file;
        discard(token);
        if(handles.size()>=8)discard(handles.keySet().iterator().next());
        Handle next=new Handle(prefix,write);handles.put(token,next);return next.file;
    }
    synchronized int read(String token,File prefix,long position,byte[] buffer,int offset,int size)throws IOException{
        try{RandomAccessFile file=handle(token,prefix,false);file.seek(position);return file.read(buffer,offset,size);}
        catch(IOException failure){discard(token);throw failure;}
    }
    synchronized boolean append(String token,File prefix,long position,byte[] buffer,int offset,int size)throws IOException{
        try{
            RandomAccessFile file=handle(token,prefix,true);
            if(file.length()!=position)return false;
            file.seek(position);file.write(buffer,offset,size);update(token,prefix);return true;
        }catch(IOException failure){discard(token);update(token,prefix);throw failure;}
    }
    synchronized void discard(String token){
        Handle old=handles.remove(token);
        if(old!=null)try{old.file.close();}catch(IOException ignored){}
    }
    synchronized void closeHandles(){while(!handles.isEmpty())discard(handles.keySet().iterator().next());}
}
