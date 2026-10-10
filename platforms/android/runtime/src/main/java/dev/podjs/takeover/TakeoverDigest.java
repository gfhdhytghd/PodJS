package dev.podjs.takeover;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Metadata digest projection; never changes the shared recovery protocol. */
public final class TakeoverDigest {
    public static final String[] ROOTS={"databases","files","shared_prefs","no_backup"};
    private static final String[] DIGEST_ROOTS={"databases","files","shared_prefs","no_backup"};
    public static final String FILES_WITHOUT_PLATFORM_METADATA="files_without_platform_metadata";
    /** Exact paths from androidx.profileinstaller 1.3.0; no wildcard metadata exemption. */
    public static boolean platformMetadata(String path) {
        return "files/profileinstaller_profileWrittenFor_lastUpdateTime.dat".equals(path)||"files/profileInstalled".equals(path);
    }
    private TakeoverDigest() {}
    private static void require(boolean valid)throws IOException {if(!valid)throw new IOException("digest_invalid");}
    private static final char[] HEX="0123456789abcdef".toCharArray();
    private static String hex(byte[] bytes) {
        char[] out=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++){int v=bytes[i]&255;out[i*2]=HEX[v>>>4];out[i*2+1]=HEX[v&15];}
        return new String(out);
    }
    private static boolean generationValid(String generation) {
        if(generation==null||!generation.startsWith("g-")||generation.length()==2)return false;
        for(int i=2;i<generation.length();i++) {
            char c=generation.charAt(i);
            if(!(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='-'))return false;
        }
        return true;
    }
    /** Invocation-local SHA and staging buffer: no shared mutable state or JNI update per field. */
    private static final class DigestWriter {
        final MessageDigest digest;
        final byte[] buffer=new byte[16384];
        int used;
        DigestWriter()throws IOException {
            try{digest=MessageDigest.getInstance("SHA-256");}
            catch(java.security.NoSuchAlgorithmException impossible){throw new IOException("digest_invalid");}
        }
        void flush(){if(used!=0){digest.update(buffer,0,used);used=0;}}
        void number(long value,int width) {
            if(buffer.length-used<width)flush();
            for(int i=width-1;i>=0;i--)buffer[used++]=(byte)(value>>>(i*8));
        }
        void bytes(byte[] value,int offset,int length) {
            while(length>0) {
                int count=Math.min(length,buffer.length-used);
                System.arraycopy(value,offset,buffer,used,count);used+=count;offset+=count;length-=count;
                if(used==buffer.length)flush();
            }
        }
        void bytes(byte[] value){bytes(value,0,value.length);}
        String finish(){flush();return hex(digest.digest());}
    }
    private static File safe(File root,String path)throws IOException {
        require(path!=null&&!path.isEmpty()&&!path.startsWith("/")&&!path.contains("\\"));
        for(String bit:path.split("/",-1))require(!bit.isEmpty()&&!bit.equals(".")&&!bit.equals(".."));
        File f=new File(root,path).getAbsoluteFile();require(f.getCanonicalFile().equals(f));
        for(Path p=f.toPath();p!=null;p=p.getParent())require(!Files.isSymbolicLink(p));return f;
    }
    private static boolean owned(String path) {
        int slash=path.indexOf('/'),end=path.indexOf('/',slash+1);
        String root=path.substring(0,slash),n=path.substring(slash+1,end<0?path.length():end);
        return root.equals("files")&&(n.equals("podjs")||n.equals("package-takeover")||n.startsWith(".podjs-"))
            ||root.equals("databases")&&(n.equals("podjs.sqlite")||n.startsWith("podjs.sqlite-"))
            ||root.equals("no_backup")&&n.startsWith("podjs-");
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
        Properties p=new Properties();try(InputStream in=new BufferedInputStream(Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS),16384)){p.load(in);}return p;
    }
    private static String seal(Properties p,DigestWriter writer)throws IOException {
        // Recovery seal v1 uses Java String ordering, unlike digest v1's UTF-8 path ordering.
        List<String> keys=new ArrayList<>(p.stringPropertyNames());Collections.sort(keys);
        for(String k:keys) {
            byte[] key=k.getBytes(StandardCharsets.UTF_8),value=p.getProperty(k).getBytes(StandardCharsets.UTF_8);
            writer.number(key.length,4);writer.bytes(key);
            writer.number(value.length,4);writer.bytes(value);
        }
        return writer.finish();
    }
    /** Raw, immutable evidence retained in memory across an authorized synchronous purge.
     * Parsing, sorting, hashing and validation all remain deferred. No disk state is added. */
    public static final class ActiveMetadata {
        private final String generation;
        private final byte[] manifest,seal,ready;
        private ActiveMetadata(String g,byte[] m,byte[] s,byte[] r){generation=g;manifest=m;seal=s;ready=r;}
        public List<String> lines()throws IOException {
            DigestWriter writer=new DigestWriter();
            Properties p=parse(manifest);String sha=TakeoverDigest.seal(p,writer);
            require("1".equals(p.getProperty("protocol"))&&generation.equals(p.getProperty("generation")));
            require(sha.equals(parse(seal).getProperty("sha256")));
            require(sha.equals(parse(ready).getProperty("sha256")));
            return digestManifest(p,"ACTIVE",generation,writer);
        }
    }
    private static Properties parse(byte[] bytes)throws IOException {
        Properties p=new Properties();try(InputStream in=new ByteArrayInputStream(bytes)){p.load(in);}return p;
    }
    private static byte[] raw(File file)throws IOException {
        try(InputStream in=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS);
            ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;
            while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            return out.toByteArray();
        }
    }
    public static ActiveMetadata captureActive(File sandbox,String generation)throws IOException {
        require(generationValid(generation));
        String base=NativeBackup.BACKUP+"/"+generation+"/";
        return new ActiveMetadata(generation,raw(safe(sandbox,base+"manifest")),
            raw(safe(sandbox,base+"seal")),raw(safe(sandbox,base+"ready")));
    }
    /** Read sealed Properties only. Payload verification remains NativeBackup's job. */
    public static List<String> activeLines(File sandbox,String generation)throws IOException {
        require(generationValid(generation));
        String base=NativeBackup.BACKUP+"/"+generation+"/";
        DigestWriter writer=new DigestWriter();
        Properties p=read(safe(sandbox,base+"manifest"));String sha=seal(p,writer);
        require("1".equals(p.getProperty("protocol"))&&generation.equals(p.getProperty("generation")));
        require(sha.equals(read(safe(sandbox,base+"seal")).getProperty("sha256")));
        require(sha.equals(read(safe(sandbox,base+"ready")).getProperty("sha256")));
        return digestManifest(p,"ACTIVE",generation,writer);
    }
    /** Digest v1: UTF-8 path byte order, uint32be path length + path bytes +
     * uint64be file size + raw SHA256. Root-relative paths for roots; full paths
     * for the combined manifest. Directories do not count; empty roots hash empty.
     * Public JSON has exactly six fields and never contains an entry path. */
    public static List<String> digestManifest(Properties p,String phase,String generation) throws IOException {
        return digestManifest(p,phase,generation,new DigestWriter());
    }
    private static final class Entry {
        final String path;
        final byte[] encoded,sha;
        final long size;
        final int root,relativeOffset;
        Entry(String path,int root,int slash,long size,byte[] sha) {
            this.path=path;this.root=root;this.size=size;this.sha=sha;
            encoded=path.getBytes(StandardCharsets.UTF_8);
            // All root names and '/' are ASCII. Encode once; projections use a byte slice.
            relativeOffset=slash+1;
        }
    }
    private static int rootIndex(String path,int slash) {
        for(int i=0;i<DIGEST_ROOTS.length;i++)
            if(slash==DIGEST_ROOTS[i].length()&&path.startsWith(DIGEST_ROOTS[i]))return i;
        return -1;
    }
    private static void validatePath(String path)throws IOException {
        int start=0;
        for(int i=0;i<=path.length();i++) {
            if(i<path.length()&&path.charAt(i)=='\\')require(false);
            if(i==path.length()||path.charAt(i)=='/') {
                int length=i-start;
                require(length>0&&!(length==1&&path.charAt(start)=='.')
                    &&!(length==2&&path.charAt(start)=='.'&&path.charAt(start+1)=='.'));
                start=i+1;
            }
        }
    }
    private static byte[] hashBytes(String sha)throws IOException {
        require(sha.length()==64);byte[] bytes=new byte[32];
        for(int i=0;i<64;i++) {
            char c=sha.charAt(i);int digit=c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:-1;
            require(digit>=0);bytes[i/2]|=(byte)(digit<<(i%2==0?4:0));
        }
        return bytes;
    }
    private static List<String> digestManifest(Properties p,String phase,String generation,DigestWriter writer)throws IOException {
        require(("SNAPSHOTTED".equals(phase)||"ACTIVE".equals(phase))&&generationValid(generation));
        int count;
        try{count=Integer.parseInt(p.getProperty("entries"));require(count>=0&&count<=1000000);}
        catch(RuntimeException invalid){throw new IOException("digest_invalid");}
        List<Entry> all=new ArrayList<>();
        for(int i=0;i<count;i++) {
            String key="entry."+i+".";
            String type=p.getProperty(key+"type");require("file".equals(type)||"directory".equals(type));
            String path=p.getProperty(key+"path","");int slash=path.indexOf('/'),root=rootIndex(path,slash);
            require(slash>0&&slash<path.length()-1&&root>=0);validatePath(path);require(!owned(path));
            if("directory".equals(type))continue;
            byte[] sha=hashBytes(p.getProperty(key+"sha256",""));long size;
            try{size=Long.parseLong(p.getProperty(key+"size",""));}
            catch(RuntimeException invalid){throw new IOException("digest_invalid");}
            require(size>=0);all.add(new Entry(path,root,slash,size,sha));
        }
        all.sort((a,b)->utf8Compare(a.encoded,b.encoded));
        for(int i=1;i<all.size();i++)require(utf8Compare(all.get(i-1).encoded,all.get(i).encoded)!=0);
        List<List<Entry>> roots=new ArrayList<>();
        for(int i=0;i<DIGEST_ROOTS.length;i++)roots.add(new ArrayList<>());
        List<Entry> business=new ArrayList<>();
        for(Entry entry:all) {
            roots.get(entry.root).add(entry);
            if(entry.root==1&&!platformMetadata(entry.path))business.add(entry);
        }
        List<String> out=new ArrayList<>(6);
        // Filtering the full byte-sorted list preserves root-relative byte order.
        for(int i=0;i<DIGEST_ROOTS.length;i++)out.add(digestLine(roots.get(i),phase,generation,DIGEST_ROOTS[i],true,writer));
        out.add(digestLine(all,phase,generation,"manifest",false,writer));
        out.add(digestLine(business,phase,generation,FILES_WITHOUT_PLATFORM_METADATA,true,writer));return out;
    }
    private static int utf8Compare(byte[] a,byte[] b) {
        for(int i=0;i<Math.min(a.length,b.length);i++)if(a[i]!=b[i])return Integer.compare(a[i]&255,b[i]&255);
        return Integer.compare(a.length,b.length);
    }
    private static String digestLine(List<Entry> entries,String phase,String generation,String root,boolean relative,DigestWriter writer)throws IOException {
        long bytes=0;
        for(Entry entry:entries) {
            try{bytes=Math.addExact(bytes,entry.size);}
            catch(RuntimeException invalid){throw new IOException("digest_invalid");}
            int offset=relative?entry.relativeOffset:0,length=entry.encoded.length-offset;
            writer.number(length,4);writer.bytes(entry.encoded,offset,length);
            writer.number(entry.size,8);writer.bytes(entry.sha);
        }
        return "{\"phase\":\""+phase+"\",\"generation\":\""+generation+"\",\"root\":\""+root+"\",\"files\":"+entries.size()+",\"bytes\":"+bytes+",\"sha256\":\""+writer.finish()+"\"}";
    }
}
