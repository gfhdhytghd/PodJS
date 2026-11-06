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
    private long indexBytes,pendingBytes,writingBytes;
    private final Object writeMutex=new Object();
    private static final class Pending {
        final byte[] bytes;final int[] argb;final int size;
        Pending(byte[] bytes){this.bytes=bytes;argb=null;size=bytes.length;}
        Pending(int[] argb){bytes=null;this.argb=argb;size=argb.length*4;}
        byte[] raw(){if(bytes!=null)return bytes;java.nio.ByteBuffer raw=java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN);raw.asIntBuffer().put(argb);return raw.array();}
    }
    private final LinkedHashMap<String,Pending> pending=new LinkedHashMap<>();
    private Thread writer;
    private boolean writesEnabled;
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
            if(name.equals(".font-index")&&length<=128*1024){indexBytes=cost(length);bytes+=indexBytes;continue;}
            if(name.length()!=70||!name.endsWith(".glyph")||!isKey(name.substring(0,64))||length>MAX_CELL+20){file.delete();continue;}
            ordered.add(new Existing(file,name,length,last));
        }
        ordered.sort(Comparator.comparingLong((Existing e)->e.last).thenComparing(e->e.name));
        for(Existing e:ordered){files.put(e.name,e.file);bytes+=cost(e.length);clock=Math.max(clock,e.last);}
        if(reservedBytes>limit&&new File(root,".identity").delete()){bytes-=reservedBytes;reservedBytes=0;}
        if(bytes>limit&&indexBytes>0&&new File(root,".font-index").delete()){bytes-=indexBytes;indexBytes=0;}
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
    private void recount(){bytes=writingBytes+directoryBytes+(new File(root,".identity").isFile()?reservedBytes:0)+(new File(root,".font-index").isFile()?indexBytes:0);for(File file:files.values())bytes+=cost(file.length());}
    synchronized byte[] fontIndex() {
        File file=new File(root,".font-index");
        try(FileInputStream input=new FileInputStream(file)) {
            long length=file.length();if(length<1||length>128*1024)return null;
            byte[] raw=new byte[(int)length];int pos=0;while(pos<raw.length){int n=input.read(raw,pos,raw.length-pos);if(n<1)return null;pos+=n;}
            return input.read()==-1?raw:null;
        }catch(IOException unavailable){return null;}
    }
    synchronized void fontIndex(byte[] raw) {
        if(raw==null||raw.length<1||raw.length>128*1024)return;
        File temporary=new File(root,".font-index.tmp");long charge=cost(raw.length);
        try {
            trim(limit-charge);if(bytes+charge>limit)return;
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(raw);}
            if(temporary.renameTo(new File(root,".font-index"))){indexBytes=charge;recount();}
        }catch(IOException unavailable){/* A missing index only repeats XML parsing. */}finally{temporary.delete();}
    }
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
            Pending queued=pending.get(name);
            if(queued!=null&&queued.size==expected){hits++;return queued.raw();}
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
    synchronized int[] getArgb(String key,int expected) {
        if(expected<12||expected>MAX_CELL||expected%4!=0)return null;
        Pending queued=pending.get(name(key));
        if(queued!=null&&queued.argb!=null&&queued.size==expected){hits++;return queued.argb;}
        byte[] found=get(key,expected);if(found==null)return null;
        int[] result=new int[found.length/4];java.nio.ByteBuffer.wrap(found).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(result);return result;
    }
    // Pixels are immutable after publication. Keep at most 1 MiB pending, and
    // drop persistence work rather than blocking foreground rendering.
    synchronized void putAsync(String key,byte[] raw) {
        if(raw==null||raw.length<12||raw.length>MAX_CELL||limit==0)return;
        enqueue(key,new Pending(raw));
    }
    // The caller publishes an immutable Bitmap.getPixels result. JNI only
    // reads it. Serialize its integer words on the low-priority writer.
    synchronized void putArgbAsync(String key,int[] raw) {
        if(raw==null||raw.length<3||raw.length>MAX_CELL/4||limit==0)return;
        enqueue(key,new Pending(raw));
    }
    private void enqueue(String key,Pending raw) {
        String name=name(key);if(files.containsKey(name)||pending.containsKey(name)||pendingBytes+raw.size>MAX_CELL)return;
        pending.put(name,raw);pendingBytes+=raw.size;
        startWriter();
    }
    synchronized void enableWrites(){writesEnabled=true;startWriter();}
    private void startWriter() {
        if(writesEnabled&&writer==null&&!pending.isEmpty()) {
            writer=new Thread(()->{
                try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST);}catch(RuntimeException hostTest){}
                while(true) {
                    String next;Pending cell;
                    synchronized(this){if(pending.isEmpty()){writer=null;notifyAll();return;}Map.Entry<String,Pending> e=pending.entrySet().iterator().next();next=e.getKey();cell=e.getValue();}
                    put(next.substring(0,64),cell.raw());
                    synchronized(this){pending.remove(next);pendingBytes-=cell.size;notifyAll();}
                }
            },"pod-glyph-persist");writer.setDaemon(true);writer.setPriority(Thread.MIN_PRIORITY);writer.start();
        }
    }
    synchronized boolean awaitWrites(long millis)throws InterruptedException {
        long deadline=System.nanoTime()+millis*1_000_000L;
        while(writer!=null||!pending.isEmpty()){long left=deadline-System.nanoTime();if(left<=0)return false;wait(Math.max(1,left/1_000_000L));}return true;
    }
    void put(String key,byte[] raw) {
        synchronized(writeMutex){writeRecord(key,raw);}
    }
    private void writeRecord(String key,byte[] raw) {
        if(raw==null||raw.length<12||raw.length>MAX_CELL||limit==0)return;
        long began=System.nanoTime();String name=name(key);File temporary=new File(root,name+".tmp");
        long reservation=0;boolean committed=false;
        try {
            synchronized(this){if(files.containsKey(name))return;}
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            DataOutputStream header=new DataOutputStream(output);CRC32 crc=new CRC32();crc.update(raw);
            header.writeInt(MAGIC);header.writeInt(raw.length);header.writeLong(crc.getValue());
            Deflater deflater=new Deflater(Deflater.BEST_SPEED);
            try(DeflaterOutputStream encoded=new DeflaterOutputStream(output,deflater)){encoded.write(raw);}finally{deflater.end();}
            byte[] packed=output.toByteArray();long charge=cost(packed.length);if(charge>limit)return;
            synchronized(this) {
                if(files.containsKey(name))return;
                trim(limit-charge);if(bytes+charge>limit)return;
                writingBytes+=charge;bytes+=charge;reservation=charge;
            }
            // The reservation includes the temporary file. Foreground misses
            // can inspect metadata while the writer performs filesystem IO.
            try(FileOutputStream stream=new FileOutputStream(temporary)){stream.write(packed);}
            File file=new File(root,name);if(!temporary.renameTo(file))return;
            long stamp;synchronized(this){clock=Math.max(clock+1,System.currentTimeMillis());stamp=clock;}
            file.setLastModified(stamp);
            synchronized(this){files.put(name,file);writingBytes-=reservation;reservation=0;committed=true;}
        }catch(IOException|RuntimeException unavailable){/* The cache never gates rendering. */}
        finally{temporary.delete();synchronized(this){writingBytes-=reservation;if(!committed)bytes-=reservation;writeNanos+=System.nanoTime()-began;}}
    }
    synchronized long bytes(){return bytes;}
}
