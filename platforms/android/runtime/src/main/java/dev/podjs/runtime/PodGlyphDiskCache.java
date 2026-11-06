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
    private final long allocationUnit;
    private final long directoryBytes;
    private final LinkedHashMap<String,File> files=new LinkedHashMap<>(128,.75f,true);
    private long bytes,clock,reservedBytes;
    long hits,misses,readNanos,writeNanos;
    PodGlyphDiskCache(File root,long limit)throws IOException {
        this(root,limit,allocationUnit(root));
    }
    // A zero allocation unit is used only by scaled, host-only cache tests.
    PodGlyphDiskCache(File root,long limit,long allocationUnit)throws IOException {
        if(limit<0||limit>MAX_BYTES)throw new IllegalArgumentException("glyph cache budget");
        this.root=root;this.limit=limit;this.allocationUnit=allocationUnit;
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("glyph cache directory");
        // Long hexadecimal names grow a multi-block directory. Reserve 1/32
        // of the budget (512 KiB at 16 MiB), or its larger existing allocation.
        // Deleted entries do not necessarily shrink a directory after restart.
        directoryBytes=allocationUnit==0?0:Math.max(cost(root.length()),
            Math.max(allocationUnit,((limit/32+allocationUnit-1)/allocationUnit)*allocationUnit));
        bytes=directoryBytes;
        File[] entries=root.listFiles();if(entries==null)throw new IOException("glyph cache listing");
        ArrayList<Existing> ordered=new ArrayList<>(entries.length);
        for(File file:entries) {
            String name=file.getName();long length=file.length(),last=file.lastModified();
            if(name.equals(".identity")&&length==130){reservedBytes=cost(130);bytes+=reservedBytes;continue;}
            if(name.length()!=70||!name.endsWith(".glyph")||!isKey(name.substring(0,64))||length>MAX_CELL+20){file.delete();continue;}
            ordered.add(new Existing(file,name,length,last));
        }
        ordered.sort(Comparator.comparingLong((Existing e)->e.last).thenComparing(e->e.name));
        for(Existing e:ordered){files.put(e.name,e.file);bytes+=cost(e.length);clock=Math.max(clock,e.last);}
        if(reservedBytes>limit&&new File(root,".identity").delete()){bytes-=reservedBytes;reservedBytes=0;}
        trim(limit);
    }
    private static final class Existing {final File file;final String name;final long length,last;Existing(File f,String n,long l,long t){file=f;name=n;length=l;last=t;}}
    private static long allocationUnit(File root){try{return Math.max(4096,new android.os.StatFs((root.isDirectory()?root:root.getParentFile()).getAbsolutePath()).getBlockSizeLong());}catch(RuntimeException unavailable){return 4096;}}
    // Reserve one allocation block for each record's inode/directory overhead,
    // plus rounded data blocks. Directory growth has a separate reserve.
    private long cost(long length){return allocationUnit==0?length:((length+allocationUnit-1)/allocationUnit+1)*allocationUnit;}
    private static boolean isKey(String key){if(key.length()!=64)return false;for(int i=0;i<64;i++){char c=key.charAt(i);if(!(c>='0'&&c<='9'||c>='a'&&c<='f'))return false;}return true;}
    private static String name(String key){if(!isKey(key))throw new IllegalArgumentException("glyph cache key");return key+".glyph";}
    private void trim(long target) {
        Iterator<Map.Entry<String,File>> it=files.entrySet().iterator();
        while(bytes>target&&it.hasNext()) {File file=it.next().getValue();long size=cost(file.length());if(file.delete()||!file.exists()){bytes-=size;it.remove();}else break;}
    }
    private void remove(String name,File file){if(file.delete()||!file.exists()){files.remove(name);recount();}}
    private void recount(){bytes=directoryBytes+(new File(root,".identity").isFile()?reservedBytes:0);for(File file:files.values())bytes+=cost(file.length());}
    synchronized boolean contains(String key){File file=files.get(name(key));return file!=null&&file.isFile();}
    synchronized String identity(String signature) {
        if(!isKey(signature))return null;
        try(FileInputStream stream=new FileInputStream(new File(root,".identity"))) {
            byte[] raw=new byte[130];int pos=0;while(pos<raw.length){int n=stream.read(raw,pos,raw.length-pos);if(n<1)return null;pos+=n;}
            String receipt=new String(raw,java.nio.charset.StandardCharsets.US_ASCII);
            if(stream.read()!=-1||receipt.charAt(64)!='\n'||receipt.charAt(129)!='\n'||!isKey(receipt.substring(0,64))||!isKey(receipt.substring(65,129))||!receipt.startsWith(signature+"\n"))return null;
            return receipt.substring(65,129);
        }catch(IOException invalid){return null;}
    }
    synchronized void identity(String signature,String identity) {
        if(!isKey(signature)||!isKey(identity)||limit<cost(130))return;
        File temporary=new File(root,".identity.tmp");
        try {
            trim(limit-cost(130));if(bytes+cost(130)>limit)return;
            try(FileOutputStream stream=new FileOutputStream(temporary)){stream.write((signature+"\n"+identity+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
            if(temporary.renameTo(new File(root,".identity"))){reservedBytes=cost(130);recount();}
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
            byte[] packed=output.toByteArray();long charge=cost(packed.length);if(charge>limit)return;
            trim(limit-charge);if(bytes+charge>limit)return;
            try(FileOutputStream stream=new FileOutputStream(temporary)){stream.write(packed);}
            File file=new File(root,name);if(!temporary.renameTo(file))return;
            clock=Math.max(clock+1,System.currentTimeMillis());file.setLastModified(clock);
            files.put(name,file);bytes+=charge;
        }catch(IOException|RuntimeException unavailable){/* The cache never gates rendering. */}
        finally{temporary.delete();writeNanos+=System.nanoTime()-began;}
    }
    synchronized long bytes(){return bytes;}
}
