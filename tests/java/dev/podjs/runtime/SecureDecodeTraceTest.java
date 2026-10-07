package dev.podjs.runtime;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
public class SecureDecodeTraceTest {
 static byte[] encrypt(String value,SecretKeySpec key)throws Exception{
  byte[] nonce=new byte[12];Arrays.fill(nonce,(byte)7);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));cipher.updateAAD("PodJS-device-services-v1".getBytes(StandardCharsets.UTF_8));byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),all=new byte[12+encrypted.length];System.arraycopy(nonce,0,all,0,12);System.arraycopy(encrypted,0,all,12,encrypted.length);return all;
 }
 public static void main(String[] args)throws Exception{
  SecretKeySpec key=new SecretKeySpec(new byte[32],"AES");JSONObject expected=new JSONObject().put("synthetic","汉😀"+"x".repeat(4800));byte[] bytes=encrypt(expected.toString(),key);
  ArrayList<String> lines=new ArrayList<>();DeviceServiceTrace trace=new DeviceServiceTrace(true,lines::add);
  if(!PodDeviceServices.decodeSecureBytes(bytes,key,trace,12).toString().equals(expected.toString()))throw new AssertionError("decode differs");
  if(!PodDeviceServices.decodeSecureBytes(bytes,key).toString().equals(expected.toString()))throw new AssertionError("default differs");
  if(lines.size()!=2||!lines.get(0).startsWith("host phase=secure.decrypt id=12 ns=")||!lines.get(1).startsWith("host phase=secure.json id=12 ns="))throw new AssertionError("separate stages");
  bytes[bytes.length-1]^=1;try{PodDeviceServices.decodeSecureBytes(bytes,key,trace,13);throw new AssertionError("tamper accepted");}catch(javax.crypto.AEADBadTagException good){}
  if(lines.size()!=3||!lines.get(2).startsWith("host phase=secure.decrypt id=13 ns="))throw new AssertionError("error phase missing");
  try{PodDeviceServices.decodeSecureBytes(new byte[27],key);throw new AssertionError("truncation accepted");}catch(java.io.IOException good){}
  java.nio.file.Path scratch=java.nio.file.Files.createTempFile("podjs-secure-trace-",".bin");
  try{byte[] valid=encrypt(expected.toString(),key);java.nio.file.Files.write(scratch,valid);
   if(!PodDeviceServices.readSecureFile(scratch.toFile(),key,trace,14).toString().equals(expected.toString()))throw new AssertionError("file result differs");
   if(lines.size()!=6||!lines.get(3).startsWith("host phase=secure.read id=14 ns="))throw new AssertionError("read phase missing");
   if(!PodDeviceServices.readSecureFile(scratch.toFile(),key).toString().equals(expected.toString()))throw new AssertionError("default file differs");
  }finally{java.nio.file.Files.deleteIfExists(scratch);}
  for(String line:lines)System.out.println(line);
  System.out.println("SecureDecodeTrace PASS: synthetic production decoder, default, AEAD tamper, truncation. AndroidKeyStore not emulated.");
 }
}
