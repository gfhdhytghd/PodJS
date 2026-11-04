package dev.podjs.runtime;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class LocalPlaintextPartTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  byte[] key=new byte[32],scope="fixture scope".getBytes(StandardCharsets.UTF_8),body="plaintext fixture body".getBytes(StandardCharsets.UTF_8),stored=LocalPlaintextPart.seal(scope,body);
  check(new String(stored,28,body.length,StandardCharsets.UTF_8).equals("plaintext fixture body"),"encrypted output");check(Arrays.equals(LocalPlaintextPart.open(scope,stored),body),"open mismatch");
  for(int i:new int[]{4,27,28,stored.length-1}){byte[] bad=stored.clone();bad[i]^=1;boolean rejected=false;try{LocalPlaintextPart.open(scope,bad);}catch(Exception e){rejected=true;}check(rejected,"corruption accepted");}
  Path file=Files.createTempFile("legacy-projection",".bin");try{byte[] nonce=new byte[12],cipher=PodCloudCrypto.aes(true,key,nonce,body,scope),legacy=new byte[12+cipher.length];System.arraycopy(nonce,0,legacy,0,12);System.arraycopy(cipher,0,legacy,12,cipher.length);Files.write(file,legacy);
   check(Arrays.equals(LocalPlaintextPart.read(file.toFile(),scope,body.length,key,true),body),"legacy migration mismatch");check(LocalPlaintextPart.plain(Files.readAllBytes(file)),"legacy not replaced");
   check(Arrays.equals(LocalPlaintextPart.read(file.toFile(),scope,body.length,new byte[32],true),body),"plaintext reopen mismatch");
  }finally{Files.deleteIfExists(file);}
  Path streamRoot=Files.createTempDirectory("plain-gzip-service");try{
   java.io.File files=streamRoot.resolve("files").toFile();files.mkdirs();byte[] output=new byte[1024*1024+7];for(int i=0;i<output.length;i++)output[i]=(byte)i;
   java.io.ByteArrayOutputStream compressed=new java.io.ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream gzip=new java.util.zip.GZIPOutputStream(compressed)){gzip.write(output);}
   byte[] packed=compressed.toByteArray();Files.write(files.toPath().resolve("input.bin"),LocalPlaintextPart.seal(scope,packed));
   PodDeviceServices host=new PodDeviceServices(files,()->{throw new AssertionError("KeyStore in plaintext gzip");});
   org.json.JSONArray parts=new org.json.JSONArray().put(new org.json.JSONObject().put("path","input.bin").put("bytes",packed.length).put("aadBase64",Base64.getEncoder().encodeToString(scope)));
   int offset=0;java.io.ByteArrayOutputStream actual=new java.io.ByteArrayOutputStream();while(true){
    org.json.JSONObject step=host.execute("crypto.gunzipSealedParts",new org.json.JSONObject().put("plaintext",true).put("keyBase64",Base64.getEncoder().encodeToString(key)).put("parts",parts).put("length",packed.length).put("outputPrefix","out/p-").put("aadPrefix","output:").put("maxOutputBytes",output.length).put("offset",offset));
    byte[] published=Files.readAllBytes(files.toPath().resolve("out/p-"+offset+".bin"));check(LocalPlaintextPart.plain(published),"gzip output encrypted");actual.write(LocalPlaintextPart.open(("output:"+offset).getBytes(StandardCharsets.UTF_8),published));offset+=step.getInt("bytes");if(step.getBoolean("done"))break;
   }check(Arrays.equals(actual.toByteArray(),output),"plaintext gzip mismatch");host.close();
   PodDeviceServices keysHost=new PodDeviceServices(files,()->new javax.crypto.spec.SecretKeySpec(new byte[32],"AES"));org.json.JSONObject values=new org.json.JSONObject();for(int i=0;i<128;i++)values.put(String.format(java.util.Locale.ROOT,"fixture.bookkeeping.k%03d",i),"fixture receipt");keysHost.execute("secure.update",new org.json.JSONObject().put("values",values));keysHost.execute("secure.set",new org.json.JSONObject().put("key","fixture.bookkeeping.k128").put("value","fixture receipt"));keysHost.execute("secure.set",new org.json.JSONObject().put("key","fixture.secret").put("value","synthetic secret"));
   org.json.JSONObject first=keysHost.execute("secure.keys",new org.json.JSONObject().put("prefixes",new org.json.JSONArray().put("fixture.bookkeeping.")));
   check(first.getJSONArray("keys").length()==128&&first.getBoolean("hasMore")&&!first.toString().contains("synthetic secret"),"key enumeration leaked value or lost pagination");
   org.json.JSONObject second=keysHost.execute("secure.keys",new org.json.JSONObject().put("prefixes",new org.json.JSONArray().put("fixture.bookkeeping.")).put("after",first.getJSONArray("keys").getString(127)));
   check(second.getJSONArray("keys").length()==1&&!second.getBoolean("hasMore"),"key enumeration cursor failed");
   char[] chars=new char[1024*1024];Arrays.fill(chars,'x');String huge=new String(chars);keysHost.execute("secure.set",new org.json.JSONObject().put("key","fixture.large").put("value",huge));StringBuilder joined=new StringBuilder();int version=0;
   while(joined.length()<huge.length()){org.json.JSONObject page=keysHost.execute("secure.get",new org.json.JSONObject().put("key","fixture.large").put("offset",joined.length()).put("maxChars",16384));check(page.toString().length()<32768,"oversized event");if(joined.length()==0)version=page.getInt("version");else check(page.getInt("version")==version,"unstable read version");joined.append(page.getString("value"));}
   check(joined.toString().equals(huge),"bounded secure read changed bytes");keysHost.close();
  }finally{try(java.util.stream.Stream<Path> paths=Files.walk(streamRoot)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
  System.out.println("LOCAL_PLAIN_PASS checksum, corruption rejection, verified legacy conversion, plaintext reopen, production streamed plaintext gzip without KeyStore, filtered names-only enumeration with pagination, bounded 1MiB legacy reads");
 }
}
