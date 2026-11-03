package dev.podjs.runtime;

import java.util.*;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Per-primitive ART vs JNI Rust comparison. Synthetic fixed vectors only. */
public final class CloudPerfCryptoProbe {
    static native byte[] nativeRun(int op,byte[] key,byte[] nonce,byte[] aad,byte[] input);
    static final byte[] key=new byte[32],nonce=new byte[12],aad="cloudperf-synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static byte[] javaRun(int op,byte[] k,byte[] n,byte[] a,byte[] p)throws Exception{
        switch(op){case 1:return PodCloudCrypto.aes(true,k,n,p,a);case 2:return PodCloudCrypto.aes(false,k,n,p,a);case 3:return PodCloudCrypto.hkdf(p,k,a);case 4:return MessageDigest.getInstance("SHA-256").digest(p);case 5:return PodCloudCrypto.gzip(p);case 6:return PodCloudCrypto.gunzip(p,8*1024*1024);default:throw new AssertionError();}
    }
    static byte[] run(boolean rust,int op,byte[] p)throws Exception{return rust?nativeRun(op,key,nonce,aad,p):javaRun(op,key,nonce,aad,p);}
    static byte[] plain(int size){byte[] p=new byte[size];for(int i=0;i<size;i++)p[i]=(byte)(32+(i*17+(i/511))%90);return p;}
    static void vectors()throws Exception{
        int vectors=0,gzipEqual=0;
        for(int length:new int[]{0,1,15,16,17,4096,262144}){
            byte[] p=plain(length);
            for(int op:new int[]{1,3,4}){check(Arrays.equals(javaRun(op,key,nonce,aad,p),nativeRun(op,key,nonce,aad,p)),"primitive differs "+op);vectors++;}
            byte[] cipher=javaRun(1,key,nonce,aad,p);check(Arrays.equals(p,nativeRun(2,key,nonce,aad,cipher)),"decrypt differs");
            byte[] bad=cipher.clone();bad[bad.length-1]^=1;check(nativeRun(2,key,nonce,aad,bad)==null,"tamper accepted");
            check(nativeRun(2,key,nonce,new byte[]{1},cipher)==null,"AAD accepted");
            check(nativeRun(2,key,new byte[11],aad,cipher)==null,"nonce accepted");
            byte[] z=javaRun(5,key,nonce,aad,p),rz=nativeRun(5,key,nonce,aad,p);if(Arrays.equals(z,rz))gzipEqual++;
            check(Arrays.equals(p,nativeRun(6,key,nonce,aad,z)),"Java gzip inflate differs");check(Arrays.equals(p,javaRun(6,key,nonce,aad,rz)),"Rust gzip inflate differs");
        }
        check(nativeRun(1,new byte[31],nonce,aad,new byte[0])==null,"short key accepted");
        System.out.println(new JSONObject().put("event","differential").put("byteIdenticalCryptoVectors",vectors).put("gzipByteIdentical",gzipEqual).put("gzipVectors",7).put("tamperRejected",true));
    }
    public static void main(String[] args)throws Exception{
        System.load(args[0]);vectors();
        System.out.println(new JSONObject().put("event","providers").put("aes",javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").getProvider().getName()).put("sha256",MessageDigest.getInstance("SHA-256").getProvider().getName()).put("hmac",javax.crypto.Mac.getInstance("HmacSHA256").getProvider().getName()));
        String[] names={"","AES-encrypt","AES-decrypt","HKDF-SHA256","SHA256","gzip","inflate"};
        byte[] p=plain(262144);for(int op=1;op<=6;op++)for(boolean rust:new boolean[]{false,true}){
            byte[] input=op==2?javaRun(1,key,nonce,aad,p):op==6?javaRun(5,key,nonce,aad,p):op==3?key:p;
            for(int warm=0;warm<4;warm++)check(run(rust,op,input)!=null,"warmup failed");
            for(int mb:new int[]{1,5,20}){
                int operations=mb*4;long start=System.nanoTime(),cpu=android.os.Debug.threadCpuTimeNanos();int bytes=0;
                for(int i=0;i<operations;i++){byte[] value=run(rust,op,input);check(value!=null,"benchmark failed");bytes+=value.length;}
                long elapsed=System.nanoTime()-start,used=android.os.Debug.threadCpuTimeNanos()-cpu;
                System.out.println(new JSONObject().put("primitive",names[op]).put("backend",rust?"Rust-JNI":"Java-host").put("logicalBytes",mb*1048576).put("inputBytes",input.length*(long)operations).put("operations",operations).put("outputBytes",bytes).put("elapsedNs",elapsed).put("cpuNs",used).put("MiBps",mb*1e9/elapsed).put("inputMiBps",input.length*(double)operations/1048576*1e9/elapsed).put("cpuNsPerOp",used/(double)operations).put("cpuMsPerMiB",used/1e6/mb));
            }
        }
        System.out.println("CRYPTO_PROBE_PASS");
    }
}
