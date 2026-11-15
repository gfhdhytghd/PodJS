package dev.podjs.runtime;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Bounded JVM stripes plus OS locks serialize cooperating private-file writers
 * across guests and processes. Never hold this lock across a bridge call. */
final class FileCommitGuard {
    private static final Object[] STRIPES = new Object[64];
    static { for(int i=0;i<STRIPES.length;i++)STRIPES[i]=new Object(); }
    interface Work<T> { T run() throws Exception; }
    private static int stripe(File file)throws Exception { return (file.getCanonicalPath().hashCode()&0x7fffffff)%STRIPES.length; }
    static <T> T locked(File root,File target,Work<T> work)throws Exception { return lock(root,stripe(target),()->work.run()); }
    static <T> T locked(File root,File source,File target,Work<T> work)throws Exception {
        int a=stripe(source),b=stripe(target);
        if(a==b)return lock(root,a,work);
        return lock(root,Math.min(a,b),()->lock(root,Math.max(a,b),work));
    }
    private static <T> T lock(File root,int stripe,Work<T> work)throws Exception {
        synchronized(STRIPES[stripe]) {
            File directory=new File(root,".pod-file-locks");
            if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new java.io.IOException("file_lock_unavailable");
            try(RandomAccessFile file=new RandomAccessFile(new File(directory,Integer.toString(stripe)),"rw");
                FileLock lock=file.getChannel().lock()) { return work.run(); }
        }
    }
}
