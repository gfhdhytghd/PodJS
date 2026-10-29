package dev.podjs.runtime;
import java.io.*;import java.nio.channels.FileLock;import java.nio.charset.StandardCharsets;
/** Bounded append under a process/file lock. Never opens network or guest code. */
final class PodAppendLog {
 private static final Object LOCK=new Object();
 static int append(File file,String text,int maximum)throws Exception {
  byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
  if(bytes.length>8192||maximum<8192||maximum>16*1024*1024)throw new IllegalArgumentException("log_limit");
  File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("log_parent");
  synchronized(LOCK){try(RandomAccessFile lock=new RandomAccessFile(new File(parent,file.getName()+".lock"),"rw");FileLock owned=lock.getChannel().lock();RandomAccessFile out=new RandomAccessFile(file,"rw")){
   if(out.length()+bytes.length>maximum){int keep=(int)Math.min(out.length(),Math.min(maximum/2,256*1024));byte[] tail=new byte[keep];out.seek(out.length()-keep);out.readFully(tail);int start=0;while(start<tail.length&&tail[start]!='\n')start++;if(start<tail.length)start++;out.setLength(0);out.write(tail,start,tail.length-start);}
   out.seek(out.length());out.write(bytes);out.getFD().sync();return bytes.length;
  }}
 }
}
