package dev.podjs.runtime;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

/** Fixed reservations bound response + partial bytes across threads/processes.
 * The OS releases slot locks on SIGKILL. Startup reaps only unlocked slots.
 * Lock files stay in place: unlinking them would permit an inode/lock ABA. */
final class PodHttpTempFiles {
    static final long MAX_FILE_BYTES=1024*1024;
    static final int SLOTS=8;
    static final long MAX_TOTAL_BYTES=MAX_FILE_BYTES*SLOTS;
    // POSIX locks can be dropped when ANY descriptor for that inode closes.
    // Never open a second channel for a slot already held in this JVM.
    private static final Set<String> LOCAL_OWNERS=new HashSet<>();
    final File directory;
    PodHttpTempFiles(File root)throws IOException {
        directory=new File(root,"sync2-content-http");
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("http_temp_path");
        if(!directory.exists())return;
        if(!directory.isDirectory())throw new IOException("http_temp_unavailable");
        for(int i=0;i<SLOTS;i++){Lease lease=lock(i);if(lease!=null)lease.close();}
        // Migration of the previous, unleased content transport namespace.
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(directory.toPath(),".pod-write-*.part*")){
            for(Path p:paths)if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))Files.delete(p);
        }
    }
    Lease acquire()throws IOException {
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("http_temp_path");
        if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("http_temp_unavailable");
        for(int i=0;i<SLOTS;i++){
            Lease lease=lock(i);if(lease==null)continue;
            try{lease.clean();if(!lease.slot.isDirectory()&&!lease.slot.mkdirs())throw new IOException("http_temp_unavailable");return lease;}
            catch(IOException failure){lease.close();throw failure;}
        }
        throw new IOException("http_temp_quota");
    }
    private Lease lock(int index)throws IOException {synchronized(LOCAL_OWNERS){
        File slot=new File(directory,"slot-"+index);
        if(Files.isSymbolicLink(slot.toPath()))throw new IOException("http_temp_path");
        File lockFile=new File(directory,".slot-"+index+".lock");
        if(Files.isSymbolicLink(lockFile.toPath()))throw new IOException("http_temp_path");
        String key=lockFile.getCanonicalPath();if(LOCAL_OWNERS.contains(key))return null;
        RandomAccessFile owner=new RandomAccessFile(lockFile,"rw");
        try{
            FileLock lock;
            try{lock=owner.getChannel().tryLock();}catch(OverlappingFileLockException busy){owner.close();return null;}
            if(lock==null){owner.close();return null;}
            LOCAL_OWNERS.add(key);return new Lease(slot,owner,lock,key);
        }catch(IOException|RuntimeException failure){owner.close();throw failure;}
    }}
    static final class Lease implements AutoCloseable {
        final File slot,file;
        private final RandomAccessFile owner;
        private final FileLock lock;
        private final String key;
        private boolean closed;
        Lease(File slot,RandomAccessFile owner,FileLock lock,String key){this.slot=slot;this.owner=owner;this.lock=lock;this.key=key;file=new File(slot,"response-"+UUID.randomUUID()+".data");}
        void clean()throws IOException {
            if(!slot.exists())return;
            try(DirectoryStream<Path> files=Files.newDirectoryStream(slot.toPath())){
                for(Path p:files){String name=p.getFileName().toString();if(!name.matches("response-[a-f0-9-]+\\.data(?:\\.download--?[0-9]+)?")||Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("http_temp_foreign_file");Files.delete(p);}
            }
        }
        @Override public void close()throws IOException {synchronized(LOCAL_OWNERS){if(closed)return;closed=true;try{clean();}finally{try{lock.release();}finally{try{owner.close();}finally{LOCAL_OWNERS.remove(key);}}}}}
    }
}
