package dev.podjs.runtime;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Atomic, disposable glyph records. Data, index and in-progress writes share
 * the same hard byte budget. A corrupt/missing record is always a cache miss. */
final class PodGlyphDiskCache {
    static final long MAX_BYTES=16L*1024*1024;
    private static final int MAGIC=0x50474c31,MAX_CELL=1024*1024;
    private final File root;
    private final long limit;
    private final LinkedHashMap<String,File> files=new LinkedHashMap<>(128,.75f,true);
    private long bytes,clock,reservedBytes;
    long hits,misses,readNanos,writeNanos;
    PodGlyphDiskCache(File root,long limit)throws IOException {
        if(limit<0||limit>MAX_BYTES)throw new IllegalArgumentException("glyph cache budget");
        this.root=root;this.limit=limit;
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("glyph cache directory");
        File[] entries=root.listFiles();if(entries==null)throw new IOException("glyph cache listing");
        Arrays.sort(entries,Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
        for(File file:entries) {
            if(file.getName().equals(".identity")&&file.length()==130){reservedBytes=130;bytes+=130;continue;}
            if(!file.getName().matches("[a-f0-9]{64}\\.glyph")||file.length()>MAX_CELL+20){file.delete();continue;}
            files.put(file.getName(),file);bytes+=file.length();clock=Math.max(clock,file.lastModified());
        }
        if(reservedBytes>limit&&new File(root,".identity").delete()){bytes-=reservedBytes;reservedBytes=0;}
        trim(limit);
    }
    private static String name(String key){if(!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("glyph cache key");return key+".glyph";}
    private void trim(long target) {
        Iterator<Map.Entry<String,File>> it=files.entrySet().iterator();
        while(bytes>target&&it.hasNext()) {File file=it.next().getValue();long size=file.length();if(file.delete()||!file.exists()){bytes-=size;it.remove();}else break;}
    }
    private void remove(String name,File file){if(file.delete()||!file.exists()){files.remove(name);recount();}}
    private void recount(){bytes=new File(root,".identity").isFile()?reservedBytes:0;for(File file:files.values())bytes+=file.length();}
    synchronized String identity(String signature) {
        if(!signature.matches("[a-f0-9]{64}"))return null;
        try(FileInputStream stream=new FileInputStream(new File(root,".identity"))) {
            byte[] raw=new byte[130];int pos=0;while(pos<raw.length){int n=stream.read(raw,pos,raw.length-pos);if(n<1)return null;pos+=n;}
            String receipt=new String(raw,java.nio.charset.StandardCharsets.US_ASCII);
            if(stream.read()!=-1||!receipt.matches("[a-f0-9]{64}\\n[a-f0-9]{64}\\n")||!receipt.startsWith(signature+"\n"))return null;
            return receipt.substring(65,129);
        }catch(IOException invalid){return null;}
    }
    synchronized void identity(String signature,String identity) {
        if(!signature.matches("[a-f0-9]{64}")||!identity.matches("[a-f0-9]{64}")||limit<130)return;
        File temporary=new File(root,".identity.tmp");
        try {
            trim(limit-130);if(bytes+130>limit)return;
            try(FileOutputStream stream=new FileOutputStream(temporary)){stream.write((signature+"\n"+identity+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
            if(temporary.renameTo(new File(root,".identity"))){reservedBytes=130;recount();}
        }catch(IOException unavailable){/* Next process computes the font digests again. */}finally{temporary.delete();}
    }
    synchronized byte[] get(String key,int expected) {
        if(expected<12||expected>MAX_CELL)return null;
        long began=System.nanoTime();String name=name(key);File file=files.get(name);
        try {
            if(file==null){misses++;return null;}
            try(DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                if(input.readInt()!=MAGIC||input.readInt()!=expected)throw new IOException("glyph header");
                long checksum=input.readLong();byte[] result=new byte[expected];
                try(InflaterInputStream decoded=new InflaterInputStream(input)) {
                    int n=0;while(n<expected){int got=decoded.read(result,n,expected-n);if(got<1)throw new IOException("truncated glyph");n+=got;}
                    if(decoded.read()!=-1)throw new IOException("oversized glyph");
                }
                CRC32 crc=new CRC32();crc.update(result);if(crc.getValue()!=checksum)throw new IOException("glyph checksum");
                clock=Math.max(clock+1,System.currentTimeMillis());file.setLastModified(clock);hits++;return result;
            }
        }catch(IOException|RuntimeException invalid){misses++;if(file!=null)remove(name,file);return null;}
        finally{readNanos+=System.nanoTime()-began;}
    }
    synchronized void put(String key,byte[] raw) {
        if(raw==null||raw.length<12||raw.length>MAX_CELL||limit==0)return;
        long began=System.nanoTime();String name=name(key);File temporary=new File(root,name+".tmp");
        try {
            if(files.containsKey(name))return;
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            DataOutputStream header=new DataOutputStream(output);CRC32 crc=new CRC32();crc.update(raw);
            header.writeInt(MAGIC);header.writeInt(raw.length);header.writeLong(crc.getValue());
            Deflater deflater=new Deflater(Deflater.BEST_SPEED);
            try(DeflaterOutputStream encoded=new DeflaterOutputStream(output,deflater)){encoded.write(raw);}finally{deflater.end();}
            byte[] packed=output.toByteArray();if(packed.length>limit)return;
            trim(limit-packed.length);if(bytes+packed.length>limit)return;
            try(FileOutputStream stream=new FileOutputStream(temporary)){stream.write(packed);}
            File file=new File(root,name);if(!temporary.renameTo(file))return;
            clock=Math.max(clock+1,System.currentTimeMillis());file.setLastModified(clock);
            files.put(name,file);bytes+=file.length();
        }catch(IOException|RuntimeException unavailable){/* The cache never gates rendering. */}
        finally{temporary.delete();writeNanos+=System.nanoTime()-began;}
    }
    synchronized long bytes(){return bytes;}
}
