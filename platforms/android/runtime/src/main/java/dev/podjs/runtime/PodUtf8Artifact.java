package dev.podjs.runtime;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/** Authenticate a UTF-8 artifact without retaining its decoded source. */
final class PodUtf8Artifact {
    private static final class Limited extends FilterInputStream {
        final long expected;
        long count;
        Limited(InputStream input,long expected){super(input);this.expected=expected;}
        private int counted(int n) {
            if(n>0 && (count+=n)>expected)throw new SecurityException("Cloud source too large");
            return n;
        }
        @Override public int read()throws IOException{int value=in.read();if(value>=0)counted(1);return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{return counted(in.read(bytes,offset,length));}
    }
    /** Consumes and closes input. Strict decoding and hashing use bounded buffers. */
    static void verify(InputStream input,long expected,String sha256)throws Exception {
        try(InputStream owned=input) {
            if(expected<1||expected>1024*1024||sha256==null||!sha256.matches("[0-9a-f]{64}"))
                throw new SecurityException("Invalid cloud source metadata");
            Limited limited=new Limited(owned,expected);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStreamReader reader=new InputStreamReader(new DigestInputStream(limited,digest),
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))) {
                char[] chars=new char[4096];
                while(reader.read(chars)!=-1) { /* Validate all UTF-8, without a whole-source String. */ }
            }
            StringBuilder actual=new StringBuilder(64);
            for(byte value:digest.digest()){actual.append(Character.forDigit((value>>>4)&15,16));actual.append(Character.forDigit(value&15,16));}
            if(limited.count!=expected||!actual.toString().equals(sha256))throw new SecurityException("Cloud source hash mismatch");
        }
    }
}
