package dev.podjs.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** One bounded output piece per call. Only EOF certifies the stream's CRC.
 * The cursor is disposable: a new process replays inflate to the secure receipt
 * offset, without decrypting cloud objects or rewriting committed output. */
final class SealedGzipSession implements AutoCloseable {
    static final int PIECE=256*1024;
    static final class Part {
        final File file; final byte[] aad; final int length;
        Part(File file,byte[] aad,int length){this.file=file;this.aad=aad;this.length=length;}
    }
    interface Publish { void write(long offset,byte[] sealed) throws Exception; }
    static final class Step {
        final long offset; final int bytes; final boolean done; final String sha256;
        Step(long offset,int bytes,boolean done,String hash){this.offset=offset;this.bytes=bytes;this.done=done;this.sha256=hash;}
    }
    final String binding;
    private final byte[] key; private final String aadPrefix;
    private final int expected; private final GZIPInputStream gzip; private final InputStream source;
    private final MessageDigest digest; private final java.security.SecureRandom random=new java.security.SecureRandom();
    private long position,deadline; private boolean closed;
    SealedGzipSession(String binding,List<Part> parts,byte[] key,String aadPrefix,int expected,long deadline) throws Exception {
        this.binding=binding;this.key=key.clone();this.aadPrefix=aadPrefix;this.expected=expected;this.deadline=deadline;
        digest=MessageDigest.getInstance("SHA-256");
        source=new InputStream(){
            int index,at;byte[] plain=new byte[0];
            public int read() throws IOException {byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
            public int read(byte[] out,int off,int count)throws IOException {
                check();if(count==0)return 0;
                if(at==plain.length){Arrays.fill(plain,(byte)0);plain=new byte[0];if(index==parts.size())return -1;
                    int i=index++;Part p=parts.get(i);
                    try{if(!p.file.isFile()||p.file.length()!=p.length+28)throw new IOException();
                        byte[] sealed=new byte[p.length+28];
                        try(FileInputStream input=new FileInputStream(p.file)){int at=0;while(at<sealed.length){int n=input.read(sealed,at,sealed.length-at);if(n<1)throw new IOException();at+=n;}if(input.read()!=-1)throw new IOException();}
                        try{plain=PodCloudCrypto.openSealedPart(SealedGzipSession.this.key,p.aad,sealed,p.length);}finally{Arrays.fill(sealed,(byte)0);}
                    }catch(Exception error){throw new IOException("sealed_part_invalid_"+i);}
                    at=0;
                }
                int n=Math.min(count,plain.length-at);System.arraycopy(plain,at,out,off,n);at+=n;return n;
            }
            public void close(){Arrays.fill(plain,(byte)0);}
        };
        GZIPInputStream opened;
        try{opened=new GZIPInputStream(source,65536);}catch(Exception e){source.close();Arrays.fill(this.key,(byte)0);throw e;}
        gzip=opened;
    }
    private void check()throws IOException {if(closed||Thread.currentThread().isInterrupted()||System.currentTimeMillis()>=deadline)throw new IOException("cloud_sync_deadline_exceeded");}
    private void consume(byte[] bytes,int n){digest.update(bytes,0,n);position+=n;}
    Step next(long offset,long deadline,Publish publish)throws Exception {
        this.deadline=deadline;check();if(offset<position||offset>expected||offset!=expected&&offset%PIECE!=0)throw new IllegalArgumentException("invalid_stream_offset");
        byte[] buffer=new byte[PIECE];
        try{
            // Replay only after process reconstruction; subsequent calls retain
            // the inflater and dictionary. Replay also rebuilds the final hash.
            while(position<offset){check();int n=gzip.read(buffer,0,(int)Math.min(buffer.length,offset-position));if(n<1)throw new IOException("invalid_gzip_length");consume(buffer,n);}
            int count=0,wanted=(int)Math.min(PIECE,expected-position);
            while(count<wanted){check();int n=gzip.read(buffer,count,wanted-count);if(n<1)throw new IOException("invalid_gzip_length");digest.update(buffer,count,n);count+=n;position+=n;}
            boolean done=position==expected;String hash=null;
            if(done){check();if(gzip.read()!=-1)throw new IOException("invalid_gzip_length");
                StringBuilder h=new StringBuilder();for(byte b:digest.digest())h.append(String.format(Locale.ROOT,"%02x",b&255));hash=h.toString();}
            if(count>0||expected==0){check();byte[] nonce=new byte[12];random.nextBytes(nonce);byte[] plain=Arrays.copyOf(buffer,count),sealed=null;
                try{byte[] cipher=PodCloudCrypto.aes(true,key,nonce,plain,(aadPrefix+offset).getBytes(StandardCharsets.UTF_8));sealed=new byte[12+cipher.length];System.arraycopy(nonce,0,sealed,0,12);System.arraycopy(cipher,0,sealed,12,cipher.length);publish.write(offset,sealed);}finally{Arrays.fill(plain,(byte)0);if(sealed!=null)Arrays.fill(sealed,(byte)0);}
            }
            return new Step(offset,count,done,hash);
        }finally{Arrays.fill(buffer,(byte)0);}
    }
    public void close()throws IOException {closed=true;try{gzip.close();}finally{source.close();Arrays.fill(key,(byte)0);}}
}
