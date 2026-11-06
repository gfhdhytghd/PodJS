package dev.podjs.runtime;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.util.Arrays;
import java.util.zip.*;

/** Strict v2 primitives. Legacy crypto APIs retain their original behavior. */
public final class PodSyncV2Crypto {
    private static final BigInteger N=new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",16);
    private PodSyncV2Crypto() {}
    private static void require(boolean ok)throws GeneralSecurityException {if(!ok)throw new GeneralSecurityException("invalid_encoding");}
    private static byte[] fixed(BigInteger n){byte[] b=n.toByteArray(),out=new byte[32];System.arraycopy(b,Math.max(0,b.length-32),out,Math.max(0,32-b.length),Math.min(32,b.length));return out;}
    public static byte[] publicBytes(PublicKey publicKey)throws Exception {
        ECPublicKey key=(ECPublicKey)publicKey;AlgorithmParameters p=AlgorithmParameters.getInstance("EC");p.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected=p.getParameterSpec(ECParameterSpec.class),actual=key.getParams();
        require(expected.getCurve().equals(actual.getCurve())&&expected.getGenerator().equals(actual.getGenerator())&&expected.getOrder().equals(actual.getOrder())&&expected.getCofactor()==actual.getCofactor());
        byte[] out=new byte[65];out[0]=4;System.arraycopy(fixed(key.getW().getAffineX()),0,out,1,32);System.arraycopy(fixed(key.getW().getAffineY()),0,out,33,32);return out;
    }
    public static PublicKey publicKey(byte[] sec1)throws Exception {
        require(sec1.length==65&&sec1[0]==4);AlgorithmParameters p=AlgorithmParameters.getInstance("EC");p.init(new ECGenParameterSpec("secp256r1"));ECParameterSpec spec=p.getParameterSpec(ECParameterSpec.class);
        BigInteger x=new BigInteger(1,Arrays.copyOfRange(sec1,1,33)),y=new BigInteger(1,Arrays.copyOfRange(sec1,33,65));
        BigInteger prime=((ECFieldFp)spec.getCurve().getField()).getP();require(x.compareTo(prime)<0&&y.compareTo(prime)<0&&y.multiply(y).mod(prime).equals(x.multiply(x).multiply(x).add(spec.getCurve().getA().multiply(x)).add(spec.getCurve().getB()).mod(prime)));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x,y),spec));
    }
    public static byte[] fromDer(byte[] der)throws Exception {
        require(der.length>=8&&der.length<=72&&der[0]==48&&(der[1]&255)==der.length-2);int at=2;BigInteger[] ns=new BigInteger[2];
        for(int i=0;i<2;i++){require(at+2<=der.length&&der[at++]==2);int n=der[at++]&255;require(n>=1&&n<=33&&at+n<=der.length&&(der[at]&128)==0&&(n==1||der[at]!=0||(der[at+1]&128)!=0));ns[i]=new BigInteger(1,Arrays.copyOfRange(der,at,at+n));require(ns[i].signum()>0&&ns[i].compareTo(N)<0);at+=n;}
        require(at==der.length);if(ns[1].compareTo(N.shiftRight(1))>0)ns[1]=N.subtract(ns[1]);byte[] out=new byte[64];System.arraycopy(fixed(ns[0]),0,out,0,32);System.arraycopy(fixed(ns[1]),0,out,32,32);return out;
    }
    public static byte[] toDer(byte[] signature)throws Exception {
        require(signature.length==64);BigInteger r=new BigInteger(1,Arrays.copyOfRange(signature,0,32)),s=new BigInteger(1,Arrays.copyOfRange(signature,32,64));require(r.signum()>0&&r.compareTo(N)<0&&s.signum()>0&&s.compareTo(N.shiftRight(1))<=0);
        byte[] a=r.toByteArray(),b=s.toByteArray();ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(48);out.write(a.length+b.length+4);out.write(2);out.write(a.length);out.write(a);out.write(2);out.write(b.length);out.write(b);return out.toByteArray();
    }
    public static byte[] sign(PrivateKey key,byte[] input)throws Exception {require(input.length<=262144);Signature s=Signature.getInstance("SHA256withECDSA");s.initSign(key);s.update(input);return fromDer(s.sign());}
    public static boolean verify(byte[] sec1,byte[] input,byte[] signature)throws Exception {if(input.length>262144)return false;try{PublicKey key=publicKey(sec1);byte[] der=toDer(signature);Signature s=Signature.getInstance("SHA256withECDSA");s.initVerify(key);s.update(input);return s.verify(der);}catch(GeneralSecurityException|IllegalArgumentException error){return false;}}
    private static long le(byte[] in,int at){return ((long)in[at]&255)|(((long)in[at+1]&255)<<8)|(((long)in[at+2]&255)<<16)|(((long)in[at+3]&255)<<24);}
    private static void bound(byte[] in,int at,int n)throws Exception {require(n>=0&&at>=0&&at<=in.length-8-n);}
    /** Exactly one member, exact output length, header CRC, trailer CRC and ISIZE. */
    public static byte[] gunzip(byte[] in,int length)throws Exception {
        require(in.length>=18&&in.length<=262144&&length>=0&&length<=262144&&in[0]==31&&(in[1]&255)==139&&in[2]==8&&(in[3]&224)==0);int flags=in[3]&255,at=10;
        if((flags&4)!=0){bound(in,at,2);int n=(in[at]&255)|((in[at+1]&255)<<8);at+=2;bound(in,at,n);at+=n;}
        for(int f:new int[]{8,16})if((flags&f)!=0){while(true){bound(in,at,1);if(in[at++]==0)break;}}
        if((flags&2)!=0){bound(in,at,2);CRC32 c=new CRC32();c.update(in,0,at);require((c.getValue()&65535)==((in[at]&255)|((in[at+1]&255)<<8)));at+=2;}
        Inflater inflater=new Inflater(true);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];
        try{inflater.setInput(in,at,in.length-at);while(!inflater.finished()){int n=inflater.inflate(b);require(n>0||inflater.finished());require(out.size()<=length-n);out.write(b,0,n);}int end=at+(int)inflater.getBytesRead();byte[] plain=out.toByteArray();require(end+8==in.length&&plain.length==length);CRC32 crc=new CRC32();crc.update(plain);require(le(in,end)==crc.getValue()&&le(in,end+4)==length);return plain;}finally{inflater.end();}
    }
    /** Explicit nonce is CSPRNG-produced by the caller before final AAD construction. */
    public static byte[] encrypt(byte[] key,byte[] nonce,byte[] input,byte[] aad)throws Exception {require(input.length<=262144&&aad.length<=4096);return PodCloudCrypto.aes(true,key,nonce,input,aad);}
}
