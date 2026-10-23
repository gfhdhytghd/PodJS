package dev.podjs.runtime;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Nonblocking, app-private named leases across foreground/background guests and processes.
 * Separate from the short secure.store IO lock: callers may read/write while owning a lease.
 * No credentials are stored in the lease file. Closing the owner or process releases it. */
final class PodSecureLease implements Closeable {
    // POSIX record locks are process-owned: closing a second descriptor for the
    // same file can release the first lock. Never open it twice in one process.
    private static final Map<String,PodSecureLease> OWNERS=new HashMap<>();
    private final File directory;
    private final Map<String,RandomAccessFile> files=new HashMap<>();
    private final Map<String,FileLock> locks=new HashMap<>();
    private final Map<String,String> paths=new HashMap<>();
    PodSecureLease(File directory){this.directory=directory;}
    synchronized boolean acquire(String key)throws Exception {
        synchronized(OWNERS){return acquireLocked(key);}
    }
    private boolean acquireLocked(String key)throws Exception {
        if(key==null||key.isEmpty()||key.length()>128)throw new IllegalArgumentException("secure_lease_key_invalid");
        if(locks.containsKey(key))return false;
        if(locks.size()>=16)throw new IllegalStateException("secure_lease_limit");
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        StringBuilder name=new StringBuilder("secure-lease-");
        for(byte value:hash)name.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        String path=new File(directory,name+".lock").getCanonicalPath();
        if(OWNERS.containsKey(path))return false;
        RandomAccessFile file=new RandomAccessFile(path,"rw");
        try {
            FileLock lock;
            try{lock=file.getChannel().tryLock();}catch(OverlappingFileLockException busy){lock=null;}
            if(lock==null){file.close();return false;}
            files.put(key,file);locks.put(key,lock);paths.put(key,path);OWNERS.put(path,this);return true;
        }catch(Exception error){file.close();throw error;}
    }
    synchronized void release(String key)throws IOException {
        synchronized(OWNERS){releaseLocked(key);}
    }
    private void releaseLocked(String key)throws IOException {
        FileLock lock=locks.remove(key);RandomAccessFile file=files.remove(key);String path=paths.remove(key);
        try{if(lock!=null)lock.release();}finally{
            try{if(file!=null)file.close();}finally{if(path!=null)OWNERS.remove(path,this);}
        }
    }
    @Override public synchronized void close(){
        for(String key:new java.util.ArrayList<>(locks.keySet()))try{release(key);}catch(IOException ignored){}
    }
}
