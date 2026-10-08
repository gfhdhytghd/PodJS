package dev.podjs.takeover;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Metadata digest projection; never changes the shared recovery protocol. */
public final class TakeoverDigest {
    public static final String[] ROOTS={"databases","files","shared_prefs","no_backup"};
    private TakeoverDigest() {}
    private static void require(boolean valid)throws IOException {if(!valid)throw new IOException("digest_invalid");}
    private static String hex(byte[] b) {
        StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();
    }
    private static File safe(File root,String path)throws IOException {
        require(path!=null&&!path.isEmpty()&&!path.startsWith("/")&&!path.contains("\\"));
        for(String bit:path.split("/",-1))require(!bit.isEmpty()&&!bit.equals(".")&&!bit.equals(".."));
        File f=new File(root,path).getAbsoluteFile();require(f.getCanonicalFile().equals(f));
        for(Path p=f.toPath();p!=null;p=p.getParent())require(!Files.isSymbolicLink(p));return f;
    }
    private static boolean owned(String path) {
        String[] parts=path.split("/",3);String n=parts[1];
        return parts[0].equals("files")&&(n.equals("podjs")||n.equals("package-takeover")||n.startsWith(".podjs-"))
            ||parts[0].equals("databases")&&(n.equals("podjs.sqlite")||n.startsWith("podjs.sqlite-"))
            ||parts[0].equals("no_backup")&&n.startsWith("podjs-");
    }
    /** Separate private digest snapshot, NOT the NativeBackup recovery manifest.
     * Converter hashes are reused; only files outside their inventories are hashed.
     * NativeBackup.prepare still independently inventories/verifies before ACTIVE. */
    public static Properties snapshot(File sandbox,Map<String,String> hashes)throws IOException {
        Properties p=new Properties();List<String> entries=new ArrayList<>();
        for(String root:ROOTS)walk(sandbox,root,p,entries,hashes);
        p.setProperty("entries",Integer.toString(entries.size()));return p;
    }
    private static void walk(File sandbox,String path,Properties p,List<String> entries,Map<String,String> hashes)throws IOException {
        File f=safe(sandbox,path);if(!Files.exists(f.toPath(),LinkOption.NOFOLLOW_LINKS))return;
        if(path.contains("/")&&owned(path))return;
        BasicFileAttributes before=Files.readAttributes(f.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(before.isDirectory()) {
            File[] children=f.listFiles();require(children!=null);
            for(File child:children)walk(sandbox,path+"/"+child.getName(),p,entries,hashes);
        } else {
            require(before.isRegularFile()&&path.contains("/"));
            String hash=hashes.containsKey(path)?hashes.get(path):NativeBackup.hash(f);
            BasicFileAttributes after=Files.readAttributes(f.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            require(before.size()==after.size()&&Objects.equals(before.fileKey(),after.fileKey())&&before.lastModifiedTime().equals(after.lastModifiedTime()));
            int i=entries.size();entries.add(path);String key="entry."+i+".";
            p.setProperty(key+"type","file");p.setProperty(key+"path",path);
            p.setProperty(key+"size",Long.toString(before.size()));p.setProperty(key+"sha256",hash);
        }
    }
    private static Properties read(File file)throws IOException {
        Properties p=new Properties();try(InputStream in=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS)){p.load(in);}return p;
    }
    private static String seal(Properties p)throws IOException {
        try {
            MessageDigest d=MessageDigest.getInstance("SHA-256");
            for(String k:new TreeSet<>(p.stringPropertyNames())) {
                byte[] key=k.getBytes(java.nio.charset.StandardCharsets.UTF_8),value=p.getProperty(k).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                d.update(java.nio.ByteBuffer.allocate(4).putInt(key.length).array());d.update(key);
                d.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());d.update(value);
            }return hex(d.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IOException("digest_invalid");}
    }
    /** Read sealed Properties only. Payload verification remains NativeBackup's job. */
    public static List<String> activeLines(File sandbox,String generation)throws IOException {
        require(generation!=null&&generation.matches("g-[a-zA-Z0-9-]+"));
        String base=NativeBackup.BACKUP+"/"+generation+"/";
        Properties p=read(safe(sandbox,base+"manifest"));String sha=seal(p);
        require("1".equals(p.getProperty("protocol"))&&generation.equals(p.getProperty("generation")));
        require(sha.equals(read(safe(sandbox,base+"seal")).getProperty("sha256")));
        require(sha.equals(read(safe(sandbox,base+"ready")).getProperty("sha256")));
        return digestManifest(p,"ACTIVE",generation);
    }
    /** Digest v1: UTF-8 path byte order, uint32be path length + path bytes +
     * uint64be file size + raw SHA256. Root-relative paths for roots; full paths
     * for the combined manifest. Directories do not count; empty roots hash empty.
     * Public JSON has exactly six fields and never contains an entry path. */
    public static List<String> digestManifest(Properties p,String phase,String generation) throws IOException {
        require(("SNAPSHOTTED".equals(phase)||"ACTIVE".equals(phase))&&generation!=null&&generation.matches("g-[a-zA-Z0-9-]+"));
        Map<String,SortedMap<String,String[]>> roots=new LinkedHashMap<>();
        for(String root:new String[]{"databases","files","shared_prefs","no_backup"})roots.put(root,new TreeMap<>(TakeoverDigest::utf8Compare));
        SortedMap<String,String[]> all=new TreeMap<>(TakeoverDigest::utf8Compare);
        int entries;
        try {entries=Integer.parseInt(p.getProperty("entries"));require(entries>=0&&entries<=1000000);}
        catch(RuntimeException invalid){throw new IOException("digest_invalid");}
        for(int i=0;i<entries;i++) {
            String key="entry."+i+".";
            String type=p.getProperty(key+"type");require("file".equals(type)||"directory".equals(type));
            String path=p.getProperty(key+"path","");int slash=path.indexOf('/');
            require(slash>0&&slash<path.length()-1&&roots.containsKey(path.substring(0,slash)));
            for(String bit:path.split("/",-1))require(!bit.isEmpty()&&!bit.equals(".")&&!bit.equals("..")&&!bit.contains("\\"));
            require(!owned(path));
            if("directory".equals(type))continue;
            String[] value={p.getProperty(key+"size",""),p.getProperty(key+"sha256","")};
            require(value[1].matches("[a-f0-9]{64}")&&all.put(path,value)==null);
            roots.get(path.substring(0,slash)).put(path.substring(slash+1),value);
        }
        List<String> out=new ArrayList<>();
        for(Map.Entry<String,SortedMap<String,String[]>> root:roots.entrySet())out.add(digestLine(root.getValue(),phase,generation,root.getKey()));
        out.add(digestLine(all,phase,generation,"manifest"));return out;
    }
    private static int utf8Compare(String left,String right) {
        byte[] a=left.getBytes(java.nio.charset.StandardCharsets.UTF_8),b=right.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for(int i=0;i<Math.min(a.length,b.length);i++)if(a[i]!=b[i])return Integer.compare(a[i]&255,b[i]&255);
        return Integer.compare(a.length,b.length);
    }
    private static String digestLine(SortedMap<String,String[]> entries,String phase,String generation,String root) throws IOException {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");long bytes=0;
            for(Map.Entry<String,String[]> entry:entries.entrySet()) {
                byte[] path=entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                long size=Long.parseLong(entry.getValue()[0]);require(size>=0);bytes=Math.addExact(bytes,size);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(path.length).array());hash.update(path);
                hash.update(java.nio.ByteBuffer.allocate(8).putLong(size).array());
                String sha=entry.getValue()[1];byte[] raw=new byte[32];
                for(int i=0;i<32;i++)raw[i]=(byte)Integer.parseInt(sha.substring(i*2,i*2+2),16);
                hash.update(raw);
            }
            return "{\"phase\":\""+phase+"\",\"generation\":\""+generation+"\",\"root\":\""+root+"\",\"files\":"+entries.size()+",\"bytes\":"+bytes+",\"sha256\":\""+hex(hash.digest())+"\"}";
        }catch(java.security.NoSuchAlgorithmException|RuntimeException invalid){throw new IOException("digest_invalid");}
    }
}
