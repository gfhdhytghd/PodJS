package dev.podjs.runtime;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

/** Descriptor-relative traversal: a renamed/replaced parent cannot redirect a
 * later open outside the approved guest root. Never follows guest symlinks. */
final class PodSyncGuestFiles {
    private static native int publishNoReplace(int sourceDirectory,byte[] source,int targetDirectory,byte[] target);
    private static String[] parts(File root,String path) {
        if(root==null || path==null || path.isEmpty() || path.length()>4096 || path.indexOf('\0')>=0)
            throw new IllegalArgumentException("Invalid guest file path");
        String[] parts=path.split("/",-1);
        for(String part:parts) if(part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("Invalid guest file path");
        return parts;
    }
    private static ParcelFileDescriptor open(String path,int flags) throws Exception {
        FileDescriptor fd=Os.open(path,flags|OsConstants.O_CLOEXEC|OsConstants.O_NOFOLLOW,0600);
        try { return ParcelFileDescriptor.dup(fd); } finally { Os.close(fd); }
    }
    private static ParcelFileDescriptor directory(String path) throws Exception {
        ParcelFileDescriptor result=open(path,OsConstants.O_RDONLY|OsConstants.O_NONBLOCK);
        try {
            if(!OsConstants.S_ISDIR(Os.fstat(result.getFileDescriptor()).st_mode)) throw new IOException("Guest path parent is not a directory");
            return result;
        } catch(Exception error) { result.close(); throw error; }
    }
    static ParcelFileDescriptor.AutoCloseInputStream open(File root,String path) throws Exception {
        String[] parts=parts(root,path);
        ParcelFileDescriptor directory=directory(root.getAbsolutePath());
        try {
            for(int i=0;i<parts.length-1;i++) {
                ParcelFileDescriptor next=directory("/proc/self/fd/"+directory.getFd()+"/"+parts[i]);
                directory.close(); directory=next;
            }
            ParcelFileDescriptor file=open("/proc/self/fd/"+directory.getFd()+"/"+parts[parts.length-1],OsConstants.O_RDONLY|OsConstants.O_NONBLOCK);
            try {
                if(!OsConstants.S_ISREG(Os.fstat(file.getFileDescriptor()).st_mode)) throw new IOException("Guest source is not a regular file");
                return new ParcelFileDescriptor.AutoCloseInputStream(file);
            } catch(Exception error) { file.close(); throw error; }
        } finally { directory.close(); }
    }
    /** Atomic no-overwrite publication into an existing guest directory. */
    static synchronized void save(File root,String path,File source,long size,String expected,android.os.CancellationSignal cancellation) throws Exception {
        cancellation.throwIfCanceled();
        String[] parts=parts(root,path);
        ParcelFileDescriptor parent=directory(root.getAbsolutePath());
        File staging=new File(root.getAbsoluteFile().getParentFile(),".podjs-sync-publish");
        if(!staging.mkdir() && !staging.isDirectory()) { parent.close(); throw new IOException("File staging unavailable"); }
        try(ParcelFileDescriptor privateDirectory=directory(staging.getAbsolutePath());
            ParcelFileDescriptor.AutoCloseOutputStream lease=new ParcelFileDescriptor.AutoCloseOutputStream(open("/proc/self/fd/"+privateDirectory.getFd()+"/lock",OsConstants.O_RDWR|OsConstants.O_CREAT))) {
          java.nio.channels.FileLock acquired;
          try { acquired=lease.getChannel().tryLock(); }
          catch(java.nio.channels.OverlappingFileLockException busy) { throw new IOException("File publication busy",busy); }
          if(acquired==null) throw new IOException("File publication busy");
          try(java.nio.channels.FileLock held=acquired) {
            recover(privateDirectory,cancellation);
            for(int i=0;i<parts.length-1;i++) {
                ParcelFileDescriptor next=directory("/proc/self/fd/"+parent.getFd()+"/"+parts[i]); parent.close(); parent=next;
            }
            String prefix="/proc/self/fd/"+parent.getFd()+"/";
            String temporaryName="save-"+java.util.UUID.randomUUID();
            String destination=prefix+parts[parts.length-1], temporary="/proc/self/fd/"+privateDirectory.getFd()+"/"+temporaryName;
            FileDescriptor output=Os.open(temporary,OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0600);
            try {
                java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256"); long copied=0;
                try(java.nio.channels.FileChannel input=java.nio.channels.FileChannel.open(source.toPath(),java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(65536); int count;
                    while((count=input.read(buffer))!=-1) {
                        cancellation.throwIfCanceled(); if(count==0) throw new IOException("File read made no progress");
                        copied+=count; if(copied>size) throw new IOException("Received file changed"); digest.update(buffer.array(),0,count);
                        int written=0; while(written<count) { int n=Os.write(output,buffer.array(),written,count-written); if(n<=0) throw new IOException("File write made no progress"); written+=n; }
                        buffer.clear();
                    }
                }
                StringBuilder hash=new StringBuilder(); for(byte b:digest.digest()) hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                if(copied!=size || !expected.equals(hash.toString())) throw new IOException("Received file verification failed");
                Os.fsync(output); cancellation.throwIfCanceled();
                int error=publishNoReplace(privateDirectory.getFd(),temporaryName.getBytes(java.nio.charset.StandardCharsets.UTF_8),parent.getFd(),parts[parts.length-1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if(error!=0 && (error!=OsConstants.EEXIST || !matches(destination,size,expected,cancellation))) throw new android.system.ErrnoException("publish received file",error);
                Os.fsync(parent.getFileDescriptor());
            } finally { try { Os.close(output); } finally { java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(temporary)); Os.fsync(privateDirectory.getFileDescriptor()); } }
          }
        } finally { parent.close(); }
    }
    private static void recover(ParcelFileDescriptor directory,android.os.CancellationSignal cancellation) throws Exception {
        int examined=0;
        try(java.nio.file.DirectoryStream<java.nio.file.Path> entries=java.nio.file.Files.newDirectoryStream(java.nio.file.Paths.get("/proc/self/fd/"+directory.getFd()))) {
            for(java.nio.file.Path path:entries) {
                cancellation.throwIfCanceled(); if(++examined>256) throw new IOException("Too many staging entries");
                String name=path.getFileName().toString();
                if(name.matches("save-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") &&
                    java.nio.file.Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)) java.nio.file.Files.delete(path);
            }
        }
        Os.fsync(directory.getFileDescriptor());
    }
    private static boolean matches(String path,long size,String expected,android.os.CancellationSignal cancellation) throws Exception {
        try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(open(path,OsConstants.O_RDONLY|OsConstants.O_NONBLOCK))) {
            if(!OsConstants.S_ISREG(Os.fstat(input.getFD()).st_mode)) return false;
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256"); byte[] bytes=new byte[65536]; long total=0; int count;
            while((count=input.read(bytes))!=-1) { cancellation.throwIfCanceled(); total+=count; if(total>size) return false; digest.update(bytes,0,count); }
            StringBuilder hash=new StringBuilder(); for(byte b:digest.digest()) hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return total==size && expected.equals(hash.toString());
        }
    }
}
