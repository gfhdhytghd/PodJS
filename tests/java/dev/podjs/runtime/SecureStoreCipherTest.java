package dev.podjs.runtime;

import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import org.json.JSONObject;

public final class SecureStoreCipherTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        SecretKeySpec wrapping=new SecretKeySpec(new byte[32],"AES");
        byte[] plain="{\"old\":\"preserved\",\"cookie\":\"synthetic\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] legacy=SecureStoreCipher.seal(wrapping,plain,SecureStoreCipher.LEGACY_AAD);
        check(Arrays.equals(SecureStoreCipher.decode(wrapping,legacy).plain,plain),"legacy changed");
        SecureStoreCipher.Material material=SecureStoreCipher.create(wrapping);
        byte[] encoded=SecureStoreCipher.encode(material,plain);
        check(encoded.length==plain.length+SecureStoreCipher.HEADER_BYTES+28,"format size");
        SecureStoreCipher.Opened opened=SecureStoreCipher.decode(wrapping,encoded);
        check(Arrays.equals(opened.plain,plain),"restart decode");
        check(Arrays.equals(SecureStoreCipher.decode(wrapping,SecureStoreCipher.encode(opened.material,plain)).plain,plain),"restart write");
        check(!Arrays.equals(encoded,SecureStoreCipher.encode(material,plain)),"nonce reuse");
        for(int at:new int[]{0,15,16,28,60,75,76,87,encoded.length-1}){
            byte[] bad=encoded.clone();bad[at]^=1;boolean rejected=false;try{SecureStoreCipher.decode(wrapping,bad);}catch(Exception expected){rejected=true;}check(rejected,"tamper accepted "+at);
        }
        for(int n:new int[]{0,15,16,75,76,90,encoded.length-1}){boolean rejected=false;try{SecureStoreCipher.decode(wrapping,Arrays.copyOf(encoded,n));}catch(Exception expected){rejected=true;}check(rejected,"truncation accepted");}
        check(PodDeviceServices.decodeSecureBytes(encoded,wrapping).getString("old").equals("preserved"),"production decoder mismatch");
        FailureBackoff failure=new FailureBackoff();Exception original=new Exception("closed");failure.failed(original,10);boolean rejected=false;try{failure.check(29_000_000_010L);}catch(Exception expected){rejected=expected==original;}check(rejected,"busy retry");failure.check(30_000_000_010L);
        failure.failed(original,30_000_000_010L);rejected=false;try{failure.check(89_000_000_010L);}catch(Exception expected){rejected=true;}check(rejected,"non exponential retry");failure.success();failure.check(1);
        SecureOperationStats stats=new SecureOperationStats();SecureOperationStats.Span span=stats.begin("secure.get",999);stats.op("load");stats.op("UNTRUSTED");stats.end(span,true);List<String> lines=new ArrayList<>();stats.emit(System.nanoTime(),true,lines::add);check(lines.size()==1&&lines.get(0).contains("site=0 count=1 keystoreOps=1")&&!lines.get(0).contains("UNTRUSTED"),"open telemetry labels");check(stats.begin("untrusted-secret",1)==null,"unknown method logged");
        System.out.println("SECURE_CIPHER_PASS legacy, v2 restart, cached key commits, fresh nonces, header/wrapper/body tamper, truncation, exponential backoff, closed counters");
    }
}
