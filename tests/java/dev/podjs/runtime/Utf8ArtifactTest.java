package dev.podjs.runtime;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

public final class Utf8ArtifactTest {
    interface Checked { void run() throws Exception; }
    static int checks;
    static String sha(byte[] bytes)throws Exception {
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format("%02x",b&255));return s.toString();
    }
    static void rejects(Checked code)throws Exception {
        try{code.run();throw new AssertionError("accepted invalid artifact");}catch(SecurityException|java.nio.charset.CharacterCodingException expected){checks++;}
    }
    static final class Source extends FilterInputStream {
        int largest,reads;boolean closed;
        final int chunk;
        Source(byte[] bytes,int chunk){super(new ByteArrayInputStream(bytes));this.chunk=chunk;}
        @Override public int read(byte[] b,int o,int n)throws IOException{largest=Math.max(largest,n);reads++;return super.read(b,o,Math.min(n,chunk));}
        @Override public void close()throws IOException{closed=true;super.close();}
    }
    public static void main(String[] args)throws Exception {
        byte[] mixed="\ufeffASCII / 龘𠮷𠀀 / \r\n".getBytes(StandardCharsets.UTF_8);
        for(int chunk:new int[]{1,2,3,8192}) {
            Source in=new Source(mixed,chunk);PodUtf8Artifact.verify(in,mixed.length,sha(mixed));assert in.closed;checks++;
        }
        byte[] large=new byte[1024*1024];Arrays.fill(large,(byte)'a');
        // Exercise a code point split across the decoder's internal input boundary.
        byte[] cp="𠮷".getBytes(StandardCharsets.UTF_8);System.arraycopy(cp,0,large,8190,cp.length);
        Source in=new Source(large,8192);PodUtf8Artifact.verify(in,large.length,sha(large));assert in.closed&&in.largest<=8192&&in.reads>100;checks++;
        rejects(()->PodUtf8Artifact.verify(new ByteArrayInputStream(mixed),mixed.length,"0".repeat(64)));
        rejects(()->PodUtf8Artifact.verify(new ByteArrayInputStream(mixed),mixed.length-1,sha(mixed)));
        rejects(()->PodUtf8Artifact.verify(new ByteArrayInputStream(mixed),mixed.length+1,sha(mixed)));
        rejects(()->PodUtf8Artifact.verify(new ByteArrayInputStream(mixed),0,sha(mixed)));
        rejects(()->PodUtf8Artifact.verify(new ByteArrayInputStream(mixed),1024*1024+1,sha(mixed)));
        rejects(()->PodUtf8Artifact.verify(new ByteArrayInputStream(mixed),mixed.length,"invalid"));
        for(byte[] invalid:new byte[][]{{(byte)0xc0,(byte)0xaf},{(byte)0xed,(byte)0xa0,(byte)0x80},{(byte)0xf0,(byte)0x9f}, {(byte)0xff}}) {
            rejects(()->PodUtf8Artifact.verify(new Source(invalid,1),invalid.length,sha(invalid)));
        }
        byte[] tail=large.clone();tail[tail.length-1]=(byte)0xe4;
        rejects(()->PodUtf8Artifact.verify(new Source(tail,8192),tail.length,sha(tail)));
        Source bad=new Source(mixed,2);rejects(()->PodUtf8Artifact.verify(bad,mixed.length,"0".repeat(64)));assert bad.closed;checks++;
        System.out.println("UTF-8 artifact checks: "+checks);
    }
}
